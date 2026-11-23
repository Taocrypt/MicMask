package com.taocrypt.micmask

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import micmask.core.Mel
import micmask.core.Resampler
import micmask.core.Rmvpe
import com.taocrypt.micmask.inject.MicInjector
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 流式 RVC（48k 流式模型）。
 *
 * 每 0.3 s 一步：取最近 1.0 s 真麦 → HuBERT + RMVPE → 流式 net_g（只合成最后 0.35 s）
 * → 与上一块尾部 50 ms 交叉淡化 → 推回 shell 注入。
 * 两段流水线（特征线程 / 合成线程）并行，吞吐取两者较慢者。
 *
 * 模型：atri48_stream_d8.onnx，输入 phone[1,100,768] pitch[1,100] pitchf[1,100]，输出 [1,1,16800]@48k。
 */
class LiveRvc(private val open: (String) -> Any?) {

    /** 音频进出：shell 进程里直接读写环形缓冲 */
    interface Io { fun pull(max: Int): ShortArray; fun push(pcm: ShortArray) }
    @Volatile var io: Io? = null

    // 档位由选中的合成器决定：标准 LEN=35（每步 0.3 s）/ 省电 LEN=55（每步 0.5 s，延迟 +0.2 s，算力约省 40%）
    @Volatile var LEN = 35; private set
    val BLOCK get() = LEN - XF_FR
    val BLOCK_S get() = BLOCK * UPP
    val XF get() = XF_FR * UPP
    /** 档位定下后通知 shell（预缓冲要跟着块长变） */
    var onConfig: ((blockMs: Int) -> Unit)? = null
    /** 最近一次测速的摘要（给 UI 显示） */
    @Volatile var benchSummary: String = ""; private set

    /** 测速结果缓存（按模型文件大小做键），下次启动直接用，不再逐个测 */
    var pickCache: File? = null
    /** 上次特征模型测速（HuBERT+RMVPE+抽取），换音色时沿用 */
    @Volatile private var benchFeatMs = 0L

    fun stats(): String = "特征 ${lastFeatMs}ms / 合成 ${lastSynMs}ms（预算 ${BLOCK * 10}ms）· 丢块 $dropped"


    companion object {
        const val FEAT_THREADS = 3
        const val SYN_THREADS = 4
        val NETG_FILES = ModelStore.LIVE_NETG_ALL
        const val SR = 48000
        const val T = 100                 // 流式窗口帧数（10 ms/帧）

        const val UPP = SR / 100          // 480
        const val XF_FR = 5               // 交叉淡化帧数（50 ms），LEN = BLOCK + XF_FR
        const val WIN48 = (T * UPP) + 960 // 1.02 s @48k（让 HuBERT 正好出 50 帧）
        const val GATE_DB = -50f

        fun modelPresent(ctx: Context) = ModelStore.voices(ctx).isNotEmpty()
    }

    private val env = OrtEnvironment.getEnvironment()
    private var hubert: OrtSession? = null
    private var rmvpe: OrtSession? = null
    private var netG: OrtSession? = null
    /** 内容特征维度：v2=768（hubert 第 12 层 feat），v1=256（第 9 层 + final_proj，hubert_dyn8.onnx 新版的 feat9 输出） */
    @Volatile private var emb = 768

    /** 按合成器的 ONNX 元数据（转换器写入 key=micmask 的 JSON）判断特征维度；旧模型没有元数据时看 phone 输入的形状 */
    private fun embOf(s: OrtSession): Int {
        for (k in listOf("micmask", "metadata")) runCatching {   // metadata = w-okada 导出的元数据
            val j = s.metadata.customMetadata[k]
            if (!j.isNullOrBlank()) { val e = org.json.JSONObject(j).optInt("embChannels", 0); if (e == 256 || e == 768) return e }
        }
        runCatching {
            val d = ((s.inputInfo["phone"]?.info ?: s.inputInfo["feats"]?.info) as? TensorInfo)?.shape?.getOrNull(2)?.toInt() ?: 0
            if (d == 256 || d == 768) return d
        }
        return 768
    }
    // ---- 兼容模式：w-okada 导出的整段模型（feats/p_len/pitch/pitchf/sid → 模型采样率整段波形）
    /** 每块取最后 compatFrames 帧、重采样到 48k；手动标准档 35 帧，其余 55 帧（整段推理慢，留余量） */
    private val compatFrames get() = if (tier == 1) 35 else 55
    private class Compat(val sr: Int, val hop: Int, val up: Int, val dn: Int, val h: FloatArray) {
        /** 多相 FIR 重采样 sr→48k（与 convert_rvc.py 同一设计：UP*24 抽头 Kaiser-sinc） */
        fun to48k(x: FloatArray): FloatArray {
            if (up == dn) return x
            val taps = h.size; val off = (taps - 1) / 2
            val n = (x.size.toLong() * up / dn).toInt(); val y = FloatArray(n)
            for (m in 0 until n) {
                val pos = m.toLong() * dn + off
                var k = maxOf(0L, Math.floorDiv(pos - taps + 1 + up - 1, up.toLong())).toInt()
                val k1 = minOf((x.size - 1).toLong(), Math.floorDiv(pos, up.toLong())).toInt()
                var acc = 0f
                while (k <= k1) { acc += x[k] * h[(pos - k.toLong() * up).toInt()]; k++ }
                y[m] = acc
            }
            return y
        }
    }
    private val compatInfo = java.util.WeakHashMap<OrtSession, Compat?>()
    private fun compatOf(s: OrtSession): Compat? = synchronized(compatInfo) {
        if (compatInfo.containsKey(s)) return compatInfo[s]
        val c = if ("feats" in s.inputInfo && "p_len" in s.inputInfo) {
            val m = runCatching { org.json.JSONObject(s.metadata.customMetadata["metadata"] ?: "{}") }.getOrNull()
            val sr = m?.optInt("samplingRate", 40000)?.takeIf { it > 0 } ?: 40000
            var g = 48000; var b = sr; while (b != 0) { val t = g % b; g = b; b = t }
            val up = 48000 / g; val dn = sr / g
            val taps = up * 24; val fc = 0.5 / maxOf(up, dn) * 0.92
            fun i0(x: Double): Double { var a = 1.0; var t = 1.0; for (k in 1..40) { t *= (x / (2 * k)) * (x / (2 * k)); a += t }; return a }
            val h = FloatArray(taps) { j ->
                val n = j - (taps - 1) / 2.0
                val sinc = if (n == 0.0) 1.0 else kotlin.math.sin(PI * 2 * fc * n) / (PI * 2 * fc * n)
                val r = 2.0 * j / (taps - 1) - 1
                (2 * fc * sinc * i0(8.0 * sqrt(maxOf(0.0, 1 - r * r))) / i0(8.0) * up).toFloat()
            }
            L.i("live 兼容模式模型：${sr}Hz → 48k（${up}/${dn}），每块取 $compatFrames 帧")
            Compat(sr, sr / 100, up, dn, h)
        } else null
        compatInfo[s] = c
        c
    }

    private fun featOut() = if (emb == 256) "feat9" else "feat"
    /** v1 音色要新版 hubert（带 feat9 输出），旧文件没有就报错让用户更新基础模型 */
    private fun checkHubert() {
        val h = hubert ?: return
        if (emb == 256 && "feat9" !in h.outputNames)
            throw IllegalStateException("该音色是 RVC v1 模型，需要新版基础模型（hubert）。请在「模型」页重新下载该音色（会自动更新基础模型）")
    }

    @Volatile var semis: Int = 0

    // ---- 高级（实时变声页「高级」可调）
    /** 0=自动（测速选）1=标准 2=省电 */
    @Volatile var tier = 0
    /** 静音门限 dBFS：块响度低于它跳过推理 */
    @Volatile var gateDb = GATE_DB
    @Volatile var featThreads = FEAT_THREADS
    @Volatile var synThreads = SYN_THREADS
    /** 应用调参；返回 true 表示要重新加载模型才生效（档位/线程变了） */
    fun tune(t: Int, gate: Float, fth: Int, sth: Int): Boolean {
        gateDb = gate.coerceIn(-80f, -20f)
        val nt = t.coerceIn(0, 2); val nf = fth.coerceIn(1, 8); val ns = sth.coerceIn(1, 8)
        val changed = nt != tier || nf != featThreads || ns != synThreads
        tier = nt; featThreads = nf; synThreads = ns
        return changed
    }
    @Volatile private var running = false
    private var pump: Thread? = null
    private val featExec = Executors.newSingleThreadExecutor { r -> Thread({ boost(); r.run() }, "live-feat") }
    private val synExec = Executors.newSingleThreadExecutor { r -> Thread({ boost(); r.run() }, "live-syn") }
    private fun boost() { runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }.onFailure { runCatching { android.os.Process.setThreadPriority(-10) } } }
    private val inflight = AtomicInteger(0)

    @Volatile var lastFeatMs = 0L; private set
    @Volatile var lastSynMs = 0L; private set
    @Volatile var dropped = 0L; private set
    private var blkSeq = 0L
    private var gated = 0L
    private val stFeat = ArrayList<Long>(); private val stSyn = ArrayList<Long>(); private val stLag = ArrayList<Long>()
    private var pulls = 0; private var pullSamples = 0L; private var pullGapMax = 0L; private var lastPullT = 0L; private var pumpStatT = 0L

    private fun pct(a: List<Long>, p: Double): Long = if (a.isEmpty()) 0 else a.sorted()[((a.size - 1) * p).toInt()]

    @Synchronized private fun record(feat: Long, syn: Long, lag: Long) {
        stFeat.add(feat); stSyn.add(syn); stLag.add(lag)
        if (stLag.size >= 20) {
            L.i("live 汇总(20块) 特征 p50=${pct(stFeat, .5)} p95=${pct(stFeat, .95)} max=${stFeat.max()}ms | 合成 p50=${pct(stSyn, .5)} p95=${pct(stSyn, .95)} max=${stSyn.max()}ms | " +
                "端到端 p50=${pct(stLag, .5)} p95=${pct(stLag, .95)}ms | 预算 ${BLOCK * 10}ms | 丢块累计 $dropped 静音块累计 $gated")
            stFeat.clear(); stSyn.clear(); stLag.clear()
        }
    }

    private val history = FloatArray(WIN48)
    private var fresh = 0
    private var prevTail: FloatArray? = null
    // 48k→16k 正好 3:1，用预计算的 Kaiser-sinc FIR 直接抽取（原通用重采样器每抽头算 Bessel，真机 ~360ms）
    private val dk: FloatArray = run {
        val half = 48; val fc = 1.0 / 3 * 0.95; val beta = 8.0
        fun i0(x: Double): Double { var s = 1.0; var t = 1.0; for (k in 1..40) { t *= (x / (2 * k)) * (x / (2 * k)); s += t }; return s }
        val k = DoubleArray(2 * half + 1) { j ->
            val n = (j - half).toDouble()
            val sinc = if (n == 0.0) 1.0 else kotlin.math.sin(PI * fc * n) / (PI * fc * n)
            val r = n / half; fc * sinc * i0(beta * kotlin.math.sqrt(maxOf(0.0, 1 - r * r))) / i0(beta)
        }
        val sum = k.sum(); FloatArray(k.size) { (k[it] / sum).toFloat() }
    }
    private fun decim3(x: FloatArray): FloatArray {
        val half = dk.size / 2; val n = x.size / 3; val y = FloatArray(n)
        for (i in 0 until n) {
            val c = i * 3; var acc = 0f
            val j0 = maxOf(0, c - half); val j1 = minOf(x.size - 1, c + half)
            var j = j0; while (j <= j1) { acc += x[j] * dk[j - c + half]; j++ }
            y[i] = acc
        }
        return y
    }
    private val fadeIn = FloatArray(XF_FR * UPP) { i -> (0.5 - 0.5 * cos(PI * (i + 0.5) / (XF_FR * UPP))).toFloat() }

    /** force=true：忽略缓存重新测速（「性能测试」按钮） */
    fun load(onLog: (String) -> Unit, force: Boolean = false) {
        if (netG != null && !force) return
        if (force) { runCatching { netG?.close() }; netG = null; runCatching { pickCache?.delete() } }
        fun need(n: String): Any = open(n) ?: throw IllegalStateException("缺模型 $n，请先导入")
        fun opts(th: Int) = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(th)
            setInterOpNumThreads(1)
        }
        var o = opts(featThreads)
        var curTh = featThreads
        fun load(label: String, n: String): OrtSession {
            onLog("加载 $label …")
            val f = need(n); val t = System.nanoTime()
            val th = curTh
            val sess = when (f) {
                is File -> OrtLoad.create(env, f) { opts(th) }
                else -> env.createSession(f.toString(), o)
            }
            val mb = when (f) { is File -> f.length(); else -> 0L } shr 20
            L.i("live 加载 $n ${mb}MB 用时 ${(System.nanoTime() - t) / 1_000_000}ms 输入=${sess.inputInfo.map { (k, v) -> k + (v.info as? TensorInfo)?.shape?.contentToString() }}")
            return sess
        }
        L.i("live 配置：T=$T LEN=$LEN BLOCK=${BLOCK * 10}ms XF=${XF * 1000 / SR}ms WIN=${WIN48 * 1000 / SR}ms featThreads=$featThreads synThreads=$synThreads gate=${gateDb}dB tier=$tier")
        val featWarm = hubert != null && rmvpe != null && benchFeatMs > 0 && !force
        if (hubert == null) hubert = load("HuBERT", ModelStore.HUBERT)
        if (rmvpe == null) rmvpe = load("RMVPE", ModelStore.RMVPE)
        var featMs = 0L
        // 预热 + 测速特征模型（换音色时特征模型没动，沿用上次结果，省半秒多）
        if (featWarm) { featMs = benchFeatMs; L.i("live 特征模型已在内存，沿用测速 ${featMs}ms") }
        else runCatching {
            val z = FloatArray(16320) { (Math.random().toFloat() - 0.5f) * 0.1f }
            runHubert(z); runRmvpe(z)
            var th = 0L; var tr = 0L
            repeat(3) {
                val a = System.nanoTime(); runHubert(z); val b = System.nanoTime(); runRmvpe(z); val c = System.nanoTime()
                th += b - a; tr += c - b
            }
            val t = System.nanoTime(); decim3(FloatArray(WIN48)); val td = System.nanoTime() - t
            featMs = th / 3_000_000 + tr / 3_000_000 + td / 1_000_000
            benchFeatMs = featMs
            L.i("live 测速 hubert=${th / 3_000_000}ms rmvpe=${tr / 3_000_000}ms 抽取=${td / 1_000_000}ms 特征合计≈${featMs}ms")
        }.onFailure { L.e("live 特征预热失败", it) }
        // 流式合成器：有几个版本就都测一遍，用最快的
        o = opts(synThreads); curTh = synThreads
        fun phFor(s: OrtSession) = FloatArray(T * embOf(s)) { (Math.random().toFloat() - 0.5f) }
        val pi = LongArray(T) { 120 }; val pf = FloatArray(T) { 300f }
        // 每个候选：测合成耗时 + 读出它的块长；选「特征和合成都在块长 80% 以内」里延迟最低的，都不行就选最从容的
        data class Cand(val name: String, val ms: Long, val len: Int) {
            val blockMs get() = (len - XF_FR) * 10
        }
        fun ratio(c: Cand) = maxOf(c.ms, featMs).toDouble() / c.blockMs
        val key = NETG_FILES.joinToString(",") { n -> "$n:" + ((open(n) as? File)?.let { "${it.parentFile?.name}/${it.length()}@${it.lastModified()}" } ?: -1) } + "|t$tier f$featThreads s$synThreads"
        var chosen: Cand? = null
        runCatching {
            // 每个音色一条（4 行一组），换来换去不用重测
            val c = pickCache?.takeIf { it.exists() }?.readText()?.split("\n").orEmpty().chunked(4).firstOrNull { it.size == 4 && it[0] == key }
            if (c != null && open(c[1]) != null) chosen = Cand(c[1], c[2].toLong(), c[3].toInt())
        }
        if (chosen != null) {
            L.i("live 用首次测速结果：${chosen!!.name}（${chosen!!.ms}ms/块，块长 ${chosen!!.blockMs}ms）")
        } else {
            val cands = ArrayList<Cand>()
            for (n in NETG_FILES) {
                if (open(n) == null) continue
                val s = try { load("测速 $n", n) } catch (t: Throwable) { L.e("live 加载 $n 失败", t); continue }
                netG?.close(); netG = s
                val ph = phFor(s)
                runCatching {
                    val len = runNetG(ph, pi, pf).size / UPP
                    val t = System.nanoTime(); repeat(3) { runNetG(ph, pi, pf) }
                    Cand(n, (System.nanoTime() - t) / 3_000_000, len)
                }.onSuccess { c ->
                    cands += c
                    L.i("live 测速 $n = ${c.ms}ms/块 块长 ${c.blockMs}ms 负载 ${"%.0f".format(ratio(c) * 100)}%")
                }.onFailure { L.e("live 测速 $n 失败", it) }
                s.close(); netG = null
            }
            // 手动档位：只在对应块长里选，没有就退回全部
            val pool = when (tier) {
                1 -> cands.filter { it.blockMs <= 30 }
                2 -> cands.filter { it.blockMs > 30 }
                else -> cands
            }.ifEmpty { if (tier != 0) L.w("live 本音色没有${if (tier == 1) "标准" else "省电"}档模型，改为自动"); cands }
            val ok = pool.filter { ratio(it) <= 0.8 }
            chosen = (if (tier != 0) pool.minByOrNull { ratio(it) } else if (ok.isNotEmpty()) ok.minWith(compareBy<Cand> { it.blockMs }.thenBy { it.ms }) else pool.minByOrNull { ratio(it) })
                ?: throw IllegalStateException("缺流式合成器模型，请导入实时变声模型包")
        }
        val pick = chosen!!
        netG = load("流式合成器", pick.name)
        curName = pick.name
        emb = embOf(netG!!)
        L.i("live 特征维度 $emb（${if (emb == 256) "RVC v1" else "RVC v2"}）${if (compatOf(netG!!) != null) " 兼容模式" else ""}")
        if (emb == 256 && hubert?.outputNames?.contains("feat9") == false) {   // 基础模型可能刚更新过：重载一次再判断
            runCatching { hubert?.close() }; hubert = null
            o = opts(featThreads); curTh = featThreads; hubert = load("HuBERT", ModelStore.HUBERT); o = opts(synThreads); curTh = synThreads
        }
        checkHubert()
        LEN = runNetG(phFor(netG!!), pi, pf).size / UPP
        runCatching {
            val f = pickCache ?: return@runCatching
            val old = f.takeIf { it.exists() }?.readText()?.split("\n").orEmpty().chunked(4).filter { it.size == 4 && it[0] != key }
            f.writeText((listOf(listOf(key, pick.name, "${pick.ms}", "$LEN")) + old).take(16).flatten().joinToString("\n"))
        }
        val tier = if (BLOCK <= 30) "标准" else "省电"
        benchSummary = (if (compatOf(netG!!) != null) "兼容模式·" else "") + "${tier}档：每步 ${BLOCK * 10}ms · 特征 ${featMs}ms · 合成 ${pick.ms}ms · 负载 ${"%.0f".format(ratio(pick) * 100)}%"
        L.i("live 选用 ${pick.name} → $benchSummary")
        if (ratio(pick) > 1.0) L.w("live 本机最快的档位也超预算，会断音")
        onConfig?.invoke(BLOCK * 10)
        onLog("模型就绪")
    }

    // ---- 自动降档：10 s 内丢块/断流 ≥3 次 → 热切到省电档（只本次运行，不写测速缓存）
    private val trouble = java.util.ArrayDeque<Long>()
    @Volatile private var shifting = false
    @Volatile private var startT = 0L
    @Volatile private var noB50Logged = false

    /** 丢块或 shell 输出断流时调用 */
    fun noteTrouble(src: String) {
        if (!running || shifting || BLOCK > 30 || tier == 1) return  // 手动选了标准档就不自动降
        val now = System.nanoTime()
        if (now - startT < 5_000_000_000L) return           // 刚开始的预缓冲抖动不算
        val n = synchronized(trouble) {
            trouble.addLast(now)
            while (trouble.isNotEmpty() && now - trouble.first() > 10_000_000_000L) trouble.removeFirst()
            trouble.size
        }
        if (n >= 3) { synchronized(trouble) { trouble.clear() }; downshift(src) }
    }

    /** 当前在用的合成器（标准名） */
    @Volatile private var curName: String? = null

    /** 合成器的输出块长（帧）：优先读转换器写的元数据 outFrames，没有再试跑一次 */
    private fun lenOf(s: OrtSession): Int {
        runCatching {
            val j = s.metadata.customMetadata["micmask"]
            if (!j.isNullOrBlank()) { val f = org.json.JSONObject(j).optInt("outFrames", 0); if (f > XF_FR) return f }
        }
        val old = netG; netG = s
        try {
            val ph = FloatArray(T * embOf(s)); val pi = LongArray(T) { 120 }; val pf = FloatArray(T) { 300f }
            return runNetG(ph, pi, pf).size / UPP
        } finally { netG = old }
    }

    /** 降档目标：本音色其余合成器里，块长比当前大的最小那个（按元数据/实测块长挑，不看文件名；兼容模式不参与） */
    private fun downshift(src: String) {
        val others = NETG_FILES.filter { it != curName && !it.endsWith("_compat.onnx") && open(it) != null }
        if (others.isEmpty()) { if (!noB50Logged) { noB50Logged = true; L.w("live 连续断流（$src），但该音色没有省电档模型，无法自动降档") }; return }
        shifting = true
        L.w("live 10s 内断流/丢块 ≥3 次（最近：$src）→ 找块长更大的档位")
        Thread({
            try {
                val o = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(synThreads); setInterOpNumThreads(1)
                }
                var best: Pair<String, OrtSession>? = null; var bestLen = Int.MAX_VALUE
                for (n in others) {
                    val f = open(n) ?: continue
                    val t = System.nanoTime()
                    val sess = runCatching { when (f) { is File -> OrtLoad.create(env, f) { OrtSession.SessionOptions().apply { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT); setIntraOpNumThreads(synThreads); setInterOpNumThreads(1) } }; else -> env.createSession(f.toString(), o) } }
                        .getOrElse { L.e("live 加载 $n 失败", it); continue }
                    val len = runCatching { synExec.submit(java.util.concurrent.Callable { lenOf(sess) }).get() }.getOrElse { L.e("live 读 $n 块长失败", it); -1 }
                    L.i("live 候选 $n 块长 ${(len - XF_FR) * 10}ms（加载 ${(System.nanoTime() - t) / 1_000_000}ms）")
                    if (len > LEN && len < bestLen) { best?.second?.close(); best = n to sess; bestLen = len } else sess.close()
                }
                val b = best
                if (b == null) {
                    if (!noB50Logged) { noB50Logged = true; L.w("live 本音色没有块长更大的档位，无法自动降档") }
                    shifting = false; return@Thread
                }
                synExec.execute {
                    val old = netG
                    netG = b.second
                    val ph = FloatArray(T * embOf(b.second)); val pi = LongArray(T) { 120 }; val pf = FloatArray(T) { 300f }
                    val newLen = runCatching { runNetG(ph, pi, pf).size / UPP }.getOrElse { L.e("live 省电档试跑失败", it); -1 }
                    if (newLen <= 0) { netG = old; runCatching { b.second.close() }; shifting = false; return@execute }
                    LEN = newLen; curName = b.first
                    runCatching { old?.close() }
                    benchSummary = "省电档(自动)：每步 ${BLOCK * 10}ms"
                    L.i("live 已自动降档到 ${b.first}，每步 ${BLOCK * 10}ms（本次运行有效，下次开始仍按测速结果）")
                    onConfig?.invoke(BLOCK * 10)
                    shifting = false
                }
            } catch (t: Throwable) { L.e("live 自动降档失败", t); shifting = false }
        }, "live-downshift").start()
    }

    fun start() {
        if (running) return
        running = true
        startT = System.nanoTime(); synchronized(trouble) { trouble.clear() }
        java.util.Arrays.fill(history, 0f); fresh = 0; prevTail = null; dropped = 0; gated = 0; blkSeq = 0
        L.i("live 开始，semis=$semis")
        pump = Thread({ boost(); pumpLoop() }, "live-pump").apply { start() }
    }

    /**
     * 离线转换整段（录播模式用）：按块喂入、等上一块处理完再喂下一块，不丢块。
     * [pcm48] 48 kHz mono；返回同采样率结果。需先 [load]。阻塞调用。
     */
    fun offline(pcm48: ShortArray, progress: (Float) -> Unit): ShortArray {
        val tail = SR                                     // 末尾补 1 s 静音，把流水线里的块冲出来
        val total = pcm48.size + tail
        val out = java.io.ByteArrayOutputStream(total * 2)
        var pos = 0
        val old = io
        io = object : Io {
            override fun pull(max: Int): ShortArray {
                if (pos >= total) return ShortArray(0)
                while (inflight.get() > 0 && running) Thread.sleep(3)
                val n = minOf(BLOCK_S, total - pos)
                val a = ShortArray(n) { i -> val j = pos + i; if (j < pcm48.size) pcm48[j] else 0 }
                pos += n; progress(pos.toFloat() / total)
                return a
            }
            override fun push(pcm: ShortArray) {
                synchronized(out) { for (v in pcm) { out.write(v.toInt() and 0xff); out.write((v.toInt() shr 8) and 0xff) } }
            }
        }
        try {
            start()
            while (pos < total || inflight.get() > 0) Thread.sleep(10)
            Thread.sleep(50)
        } finally { stop(); io = old }
        val b = synchronized(out) { out.toByteArray() }
        val y = ShortArray(b.size / 2) { i -> ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort() }
        // 流式有约一块的延迟：去掉开头的延迟段，长度对齐输入
        val lead = minOf(maxOf(0, y.size - pcm48.size), XF)
        return y.copyOfRange(minOf(lead, y.size), minOf(y.size, lead + pcm48.size))
    }

    fun stop() {
        if (running) L.i("live 停止：共 $blkSeq 块，丢 $dropped，静音 $gated")
        running = false
        pump?.interrupt(); pump = null
    }

    /**
     * 换音色：只关合成器会话，HuBERT/RMVPE 留着（下次 [load] 只加载新合成器，切音色快、内存峰值低）。
     * 正在跑就先停，等在途块结束再关。
     */
    fun dropSynth() {
        stop()
        val t0 = System.nanoTime()
        while (inflight.get() > 0 && System.nanoTime() - t0 < 1_500_000_000L) Thread.sleep(5)
        runCatching { synExec.submit { runCatching { netG?.close() }; netG = null }.get(2, java.util.concurrent.TimeUnit.SECONDS) }
            .onFailure { runCatching { netG?.close() }; netG = null }
        curName = null; noB50Logged = false
        L.i("live 换音色：保留特征模型，只重载合成器")
    }

    fun release() {
        stop()
        featExec.shutdownNow(); synExec.shutdownNow()
        runCatching { hubert?.close() }; runCatching { rmvpe?.close() }; runCatching { netG?.close() }
        hubert = null; rmvpe = null; netG = null
    }

    private fun pumpLoop() {
        while (running) {
            val tp = System.nanoTime()
            val got = io?.pull(SR) ?: ShortArray(0)
            val pullMs = (System.nanoTime() - tp) / 1_000_000
            if (lastPullT != 0L && got.isNotEmpty()) pullGapMax = maxOf(pullGapMax, (tp - lastPullT) / 1_000_000)
            if (got.isNotEmpty()) { lastPullT = tp; pulls++; pullSamples += got.size }
            if (pullMs > 30) L.w("live 拉取慢：${pullMs}ms")
            if (tp - pumpStatT > 5_000_000_000L) {
                if (pumpStatT != 0L) L.m("live 拉取/5s 次数=$pulls 样本=${pullSamples * 1000 / SR}ms 最大间隔=${pullGapMax}ms 在途=${inflight.get()}")
                pumpStatT = tp; pulls = 0; pullSamples = 0; pullGapMax = 0
            }
            if (got.isEmpty()) { try { Thread.sleep(15) } catch (_: InterruptedException) { return }; continue }
            // 滚动进历史窗
            val n = got.size
            if (n >= WIN48) {
                for (i in 0 until WIN48) history[i] = got[n - WIN48 + i] / 32768f
            } else {
                System.arraycopy(history, n, history, 0, WIN48 - n)
                for (i in 0 until n) history[WIN48 - n + i] = got[i] / 32768f
            }
            fresh += n
            while (fresh >= BLOCK_S && running) {
                fresh -= BLOCK_S
                // 若中途还有未处理的新样本，这一步的窗口其实"超前"了 fresh 个样本；量很小，忽略
                val id = ++blkSeq
                if (inflight.get() >= 2) {
                    dropped++
                    L.w("live 块#$id 丢弃：处理跟不上（在途 ${inflight.get()}），补静音 ${BLOCK * 10}ms")
                    io?.push(ShortArray(BLOCK_S)); noteTrouble("丢块"); continue
                }
                val win = history.copyOf()
                inflight.incrementAndGet()
                submit(win, id, System.nanoTime())
            }
        }
    }

    private fun submit(win48: FloatArray, id: Long, tAvail: Long) {
        val sh = semis
        featExec.execute {
            try {
                val t0 = System.nanoTime()
                val waitFeat = (t0 - tAvail) / 1_000_000
                // 门限：太安静就不推理，直接送静音（省电也避免底噪被"唱"出来）
                var e = 0.0
                for (i in win48.size - BLOCK_S until win48.size) e += win48[i] * win48[i]
                val db = (10 * log10(e / BLOCK_S + 1e-12)).toFloat()
                if (db < gateDb) {
                    gated++
                    if (gated % 10 == 1L) L.m("live 块#$id 静音（${"%.0f".format(db)}dB < $gateDb）跳过推理")
                    synExec.execute { emit(FloatArray(LEN * UPP)); inflight.decrementAndGet() }
                    return@execute
                }
                val x16 = decim3(win48)
                val n16 = 16320
                val a16 = if (x16.size >= n16) x16.copyOfRange(x16.size - n16, x16.size)
                          else FloatArray(n16).also { System.arraycopy(x16, 0, it, n16 - x16.size, x16.size) }
                val tH = System.nanoTime()
                val feats = Rmvpe.nearestUp2(runHubert(a16))           // ~100 x emb
                val tR = System.nanoTime()
                val f0All = runRmvpe(a16)                                 // ~103
                val tE = System.nanoTime()
                val ed = emb
                val phone = FloatArray(T * ed)
                val fOff = maxOf(0, feats.size - T)
                for (i in 0 until T) {
                    val src = feats[minOf(fOff + i, feats.size - 1)]
                    System.arraycopy(src, 0, phone, i * ed, minOf(ed, src.size))
                }
                val f0 = FloatArray(T) { i -> f0All[minOf(i + 1, f0All.size - 1)] }
                val (pitch, pitchf) = Rmvpe.f0ToPitch(f0, sh)
                lastFeatMs = (System.nanoTime() - t0) / 1_000_000
                val featMs = lastFeatMs
                val tQ = System.nanoTime()
                val voiced = f0.count { it > 0f }
                synExec.execute {
                    try {
                        val t1 = System.nanoTime()
                        val y = runNetG(phone, pitch, pitchf)
                        lastSynMs = (System.nanoTime() - t1) / 1_000_000
                        val t2 = System.nanoTime()
                        emit(y)
                        val t3 = System.nanoTime()
                        var pk = 0f; for (v in y) pk = maxOf(pk, kotlin.math.abs(v))
                        val lag = (t3 - tAvail) / 1_000_000
                        L.m("live 块#$id in=${"%.0f".format(db)}dB 等待=${waitFeat}ms 重采样=${(tH - t0) / 1_000_000} hubert=${(tR - tH) / 1_000_000} rmvpe=${(tE - tR) / 1_000_000} 特征=${featMs} " +
                            "排队=${(t1 - tQ) / 1_000_000} 合成=$lastSynMs 推送=${(t3 - t2) / 1_000_000} 端到端=${lag}ms 浊音帧=$voiced/$T 峰值=${"%.2f".format(pk)} 在途=${inflight.get()}")
                        if (lag > BLOCK * 10) L.w("live 块#$id 超预算：端到端 ${lag}ms > ${BLOCK * 10}ms")
                        record(featMs, lastSynMs, lag)
                    } catch (t: Throwable) {
                        L.e("live netg", t)
                    } finally { inflight.decrementAndGet() }
                }
            } catch (t: Throwable) {
                L.e("live feat", t)
                inflight.decrementAndGet()
            }
        }
    }

    /** y: L*UPP 个样本；前 XF 与上块尾巴交叉淡化，输出 BLOCK_S 个，留后 XF 作新尾巴 */
    private fun emit(y: FloatArray) {
        val out = ShortArray(BLOCK_S)
        val tail = prevTail
        for (i in 0 until BLOCK_S) {
            var v = y[i]
            if (i < XF) {
                val p = tail?.get(i) ?: 0f
                v = p * (1f - fadeIn[i]) + v * fadeIn[i]
            }
            out[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        prevTail = y.copyOfRange(BLOCK_S, BLOCK_S + XF)
        io?.push(out)
    }

    private fun runHubert(x: FloatArray): Array<FloatArray> {
        val s = hubert!!
        val name = s.inputInfo.keys.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { tin ->
            val want = featOut().takeIf { it in s.outputNames } ?: s.outputNames.first()
            s.run(mapOf(name to tin), setOf(want)).use { res ->
                val o = res.get(0) as OnnxTensor
                val sh = (o.info as TensorInfo).shape
                val t = sh[1].toInt(); val d = sh[2].toInt()
                val flat = FloatArray(t * d); o.floatBuffer.get(flat)
                return Array(t) { i -> flat.copyOfRange(i * d, (i + 1) * d) }
            }
        }
    }

    private fun runRmvpe(x16: FloatArray): FloatArray {
        val mel = Mel.logMel(x16)
        val nMels = mel.size
        val frames = mel[0].size
        val (flat, total) = Rmvpe.padMel(mel)
        val s = rmvpe!!
        val name = s.inputInfo.keys.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, nMels.toLong(), total.toLong())).use { tin ->
            s.run(mapOf(name to tin)).use { res ->
                val o = res.get(0) as OnnxTensor
                val d = (o.info as TensorInfo).shape[2].toInt()
                val all = FloatArray(total * d); o.floatBuffer.get(all)
                val hidden = Array(frames) { i -> all.copyOfRange(i * d, (i + 1) * d) }
                return Rmvpe.decode(hidden, 0.03)
            }
        }
    }

    private fun runNetG(phone: FloatArray, pitch: LongArray, pitchf: FloatArray): FloatArray {
        val s = netG!!
        compatOf(s)?.let { return runCompat(s, it, phone, pitch, pitchf) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(phone), longArrayOf(1, T.toLong(), (phone.size / T).toLong())).use { a ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(pitch), longArrayOf(1, T.toLong())).use { b ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(pitchf), longArrayOf(1, T.toLong())).use { c ->
                    s.run(mapOf("phone" to a, "pitch" to b, "pitchf" to c)).use { res ->
                        val o = res.get(0) as OnnxTensor
                        val fb = o.floatBuffer; val y = FloatArray(fb.remaining()); fb.get(y)
                        return y
                    }
                }
            }
        }
    }

    private fun runCompat(s: OrtSession, c: Compat, phone: FloatArray, pitch: LongArray, pitchf: FloatArray): FloatArray {
        val d = (phone.size / T).toLong(); val n = T.toLong()
        val ins = LinkedHashMap<String, OnnxTensor>()
        try {
            ins["feats"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(phone), longArrayOf(1, n, d))
            ins["p_len"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(n)), longArrayOf(1))
            ins["pitch"] = OnnxTensor.createTensor(env, LongBuffer.wrap(pitch), longArrayOf(1, n))
            ins["pitchf"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(pitchf), longArrayOf(1, n))
            if ("sid" in s.inputInfo) ins["sid"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0)), longArrayOf(1))
            s.run(ins).use { res ->
                val fb = (res.get(0) as OnnxTensor).floatBuffer; val y = FloatArray(fb.remaining()); fb.get(y)
                val lc = compatFrames
                val seg = y.copyOfRange(maxOf(0, y.size - (lc + 4) * c.hop), y.size)   // 多取 4 帧给滤波器
                val r = c.to48k(seg)
                val want = lc * UPP
                return if (r.size >= want) r.copyOfRange(r.size - want, r.size) else FloatArray(want - r.size) + r
            }
        } finally { ins.values.forEach { runCatching { it.close() } } }
    }

    @Suppress("unused") private fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / x.size)
}
