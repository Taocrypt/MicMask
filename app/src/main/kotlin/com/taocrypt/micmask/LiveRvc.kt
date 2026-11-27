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
class LiveRvc(private val open0: (String) -> Any?) {
    /** 相位锁定（重载生效）：合成器换成 PhaseLock 改写版 */
    @Volatile var phaseLock = false
    /** 响度跟随 0..1（RVC rms_mix_rate 取 1-x）：输出音量包络向原声靠 */
    @Volatile var rmsFollow = 0f
    /** 共振峰偏移（半音，-6..6）：音高不变，只移音色的共振峰 */
    @Volatile var formant = 0f
    /** 输入/输出降噪（TorchGate 式谱门限，alpha.7）；即时生效 */
    @Volatile var denoiseIn = false
    @Volatile var denoiseOut = false
    private val tgIn = TorchGate(SR)
    private val tgOut = TorchGate(SR)
    /** 降噪后的输入窗口（只在特征线程里读写），与 history 同长同对齐 */
    private val dnHist = FloatArray(WIN48)
    private var dnFresh = true
    /** 上次提交以来 history 前进的样本数（采集线程写，提交时带走） */
    private var sinceSub = 0
    @Volatile private var ph0Now = 0f
    private fun open(n: String): Any? {
        val f = open0(n)
        if (!phaseLock || f !is File || n == ModelStore.HUBERT || n == ModelStore.RMVPE) return f
        // 只改写合成器 ONNX：检索索引（*_stream_index.bin）等非 .onnx 文件原样返回（以前每次启动都报 wire 6 并重读整文件）
        if (!f.name.endsWith(".onnx", ignoreCase = true)) return f
        return PhaseLock.prepare(f) ?: f
    }

    /** 音频进出：shell 进程里直接读写环形缓冲 */
    interface Io { fun pull(max: Int): ShortArray; fun push(pcm: ShortArray) }
    @Volatile var io: Io? = null

    // 档位由选中的合成器决定：标准 LEN=35（每步 0.3 s）/ 省电 LEN=55（每步 0.5 s，延迟 +0.2 s，算力约省 40%）
    @Volatile var LEN = 35; private set
    /** 高级 0.25s：仍用标准档合成器，每步只前进 25 帧，丢掉输出最前面 CUT 帧（输出与窗口末端对齐，丢的是最旧的部分） */
    @Volatile var CUT = 0; private set
    /** 每块实际用到的输出帧数 = 块长 + 交叉淡化 */
    val OUT get() = LEN - CUT
    val BLOCK get() = OUT - XF_FR
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
        const val GATE_DB = -60f
        /** 手动门限上限：再高就会把正常音量的说话也当成静音（2604FRK1EC 设 -30 后 206 块静音 205） */
        const val GATE_MAX = -40f
        /** 门限拖尾：出现人声后至少再推理这么多块，避免句尾半块人声半块静音被整块吞掉（2410DPN6CC） */
        const val GATE_HOLD = 2
        /** 相位锁定模型多一个输入 ph0[1,1,1]；没有就原样。调用方负责 run 完关闭（这里的小张量随 GC 释放） */
        /** 每个线程一个直接内存张量（推理在合成线程里串行），改 buffer 即改值，不每块新建。
         *  按线程分开（2.1.1-alpha.10）：热切换音色时后台测速与合成线程同时跑，共用一个会互相改掉 ph0 */
        private val phLocal = ThreadLocal<Pair<FloatBuffer, OnnxTensor>>()
        fun withPh0(env: OrtEnvironment, s: OrtSession, m: Map<String, OnnxTensor>, ph: Float): Map<String, OnnxTensor> {
            if (PhaseLock.INPUT !in s.inputNames) return m
            val p = phLocal.get() ?: run {
                val b = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                (b to OnnxTensor.createTensor(env, b, longArrayOf(1, 1, 1))).also { phLocal.set(it) }
            }
            p.first.put(0, ph)
            return m + (PhaseLock.INPUT to p.second)
        }

        fun modelPresent(ctx: Context) = ModelStore.voices(ctx).isNotEmpty()
    }

    private val env = OrtEnvironment.getEnvironment()
    private var hubert: OrtSession? = null
    private var rmvpe: OrtSession? = null
    private var netG: OrtSession? = null
    /** 拆分声码器通路（ORT 前半图 + NEON fp16 或 NPU 跑 dec），owner = 它所属的整图会话 */
    private class VocPath(val owner: OrtSession, val pre: OrtSession, val split: NeonVocoder.Split, val desc: String, val kind: String,
                          val dec: (FloatArray, Int, FloatArray, FloatArray?) -> FloatArray, private val closeDec: () -> Unit) : java.io.Closeable {
        override fun close() { runCatching { closeDec() }; runCatching { pre.close() } }
    }
    @Volatile private var voc: VocPath? = null
    @Volatile private var lastPreMs = 0L
    @Volatile private var lastDecMs = 0L
    private val stPre = ArrayList<Long>(); private val stDec = ArrayList<Long>()
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
    private val compatFrames get() = if (tier == 1 || tier == 3) 35 else 55
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
    /** 0=自动（测速选）1=标准 2=省电 3=高级 0.25s（标准档模型每 0.25 s 推一次，只手动选） */
    @Volatile var tier = 0
    /** 静音门限 dBFS：块响度低于它跳过推理 */
    @Volatile var gateDb = GATE_DB
    /** 声纹门限（可选，默认关）：叠加在静音门限之后，不像本人的块按静音处理。模型由 open0 按名取（shell 侧 TX_LIVE_MODELS 送来） */
    val spk = SpeakerGate { open0(SpeakerEmbedder.MODEL) as? File }
    /** 开关 / 阈值 / 录入的声纹嵌入；即时生效 */
    fun voiceprint(on: Boolean, thr: Float, emb: FloatArray?) = spk.configure(on, thr, emb)
    /** 声纹聚焦（Alkove，可选，默认关；只在 shell 进程由 InjectorService 设置）：pumpLoop 里 io.pull 之后、其它处理之前 */
    @Volatile var focus: AlkoveFocus? = null
    @Volatile var featThreads = FEAT_THREADS
    @Volatile var synThreads = SYN_THREADS
    /** 实际用的线程数与是否串行：默认 3/4 时，若绑大核且大核只有 ≤5 个，特征+合成同时跑会抢核
     *  （MT6896 4 大核实测：合成单测 163ms，并发时均 501ms；改 2/2 并行后合成单测 358ms，仍超 500ms 档）。
     *  这时改为串行：特征和合成轮流跑、各用满全部大核（2.1.1-alpha.2）。
     *  2.1.1-alpha.6：没绑大核时按大核数自适应（只对「默认线程 3/4」的用户）：两段并行的线程合计不超过「大核数 + 1」，
     *  避免 7 个推理线程挤 8 核（其中 4 个小核）互相抢占（alpha.4 天玑 8200 实测 3/4 未绑大核，持续超预算）。
     *  手动改过线程就照用户设的、仍并行 */
    @Volatile private var effF = FEAT_THREADS
    @Volatile private var effS = SYN_THREADS
    /** true = 特征与合成串行（下一块的特征等上一块合成完再开始），吞吐看两段之和 */
    @Volatile var serial = false; private set
    private fun effThreads() {
        effF = featThreads; effS = synThreads; serial = false
        val cores = Runtime.getRuntime().availableProcessors()
        if (featThreads != FEAT_THREADS || synThreads != SYN_THREADS) {
            L.i("线程：手动设置 $featThreads/$synThreads，照用（不自适应）"); return
        }
        val n = CpuPin.bigCores().size
        if (pinBig) {
            if (n < 2 || featThreads + synThreads <= n + 1) { L.i("线程：默认 $featThreads/$synThreads 并行（绑大核，大核 ${if (n < 2) "不可区分" else "$n 个"}，核心 $cores）"); return }
            effF = n; effS = n; serial = true
            L.i("线程 $featThreads/$synThreads 超过大核数 $n → 串行推理，特征/合成各用 $n 线程（避免抢核）")
            return
        }
        // 未绑大核：大核可区分时以「大核 + 1」为两段合计上限；全是性能核 / 不可区分时以核心数为上限
        val budget = if (n in 2 until cores) n + 1 else cores
        val (f, sy) = when {
            budget >= 7 -> FEAT_THREADS to SYN_THREADS
            budget == 6 -> 3 to 3
            budget == 5 -> 2 to 3
            budget == 4 -> 2 to 2
            budget == 3 -> 1 to 2
            else -> 1 to 1
        }
        effF = f; effS = sy
        L.i("线程自适应：默认 $featThreads/$synThreads，核心 $cores（大核 ${if (n in 2 until cores) "$n 个" else "不可区分"}），未绑大核 → 特征/合成 $f/$sy 并行" +
            if (f != featThreads || sy != synThreads) "（两段合计 ≤ ${budget}，避免挤到小核）" else "（不变）")
    }
    /** ADPF（PerformanceHint）：shell 侧由 InjectorService 设 Context 提供者；没设或系统不支持就不用 */
    @Volatile var hintCtx: (() -> Context?)? = null
    private val hint = PerfHint { hintCtx?.invoke() }
    /** 声纹门限判为他人时的输出增益（渐变），只在合成线程读写 */
    private var outGain = 1f
    private val attenGain = Math.pow(10.0, SpeakerGate.ATTEN_DB / 20.0).toFloat()
    /** 把 y 的增益从上一块的 [outGain] 渐变到 [g]（前 100 ms 线性），合成线程里调用 */
    private fun applyGain(y: FloatArray, g: Float) {
        val g0 = outGain
        if (g0 == 1f && g == 1f) return
        val ramp = minOf(y.size, SR / 10)
        for (i in y.indices) y[i] *= if (i < ramp) g0 + (g - g0) * (i + 1) / ramp else g
        outGain = g
    }
    /** 检索混合比例（RVC index_rate）；0 = 不用。音色带 <音色>_stream_index.bin 才生效 */
    @Volatile var indexRate = 0.3f
    @Volatile private var index: micmask.core.FaissIvfIndex? = null
    @Volatile private var idxMs = 0L
    /** 推理线程绑大核（高级设置，默认开） */
    @Volatile var pinBig = true
    /** 声码器：0=自动（本机实测选最快：ORT 整图 / NEON fp16 / NPU）1=ORT 整图 2=NEON fp16（支持就用）3=骁龙 NPU（可用就用） */
    @Volatile var vocoder = 0; private set
    /** 返回 true = 变了，要重新加载 */
    /** HuBERT 也尝试上 NPU（setVocoder 的第 3 位，值 4） */
    @Volatile var hubWant = false; private set
    /** 最近一次测速选出的配置「声码器;HuBERT 是否 NPU」，性能测试据此自动采用 */
    @Volatile var benchCfg = ""
    fun setVocoder(v: Int): Boolean { val n = (v and 3).coerceIn(0, 3); val h = v and 4 != 0; val c = n != vocoder || h != hubWant; vocoder = n; hubWant = h; return c }
    /** 取 QNN 库目录（只在注入进程里设置；返回 null = 不用 NPU） */
    @Volatile var qnnDirProvider: (() -> File?)? = null
    /** 应用调参；返回 true 表示要重新加载模型才生效（档位/线程变了） */
    fun tune(t: Int, gate: Float, fth: Int, sth: Int): Boolean {
        gateDb = gate.coerceIn(-80f, GATE_MAX)
        val nt = t.coerceIn(0, 3); val nf = fth.coerceIn(1, 8); val ns = sth.coerceIn(1, 8)
        val changed = nt != tier || nf != featThreads || ns != synThreads
        tier = nt; featThreads = nf; synThreads = ns
        return changed
    }
    @Volatile private var running = false
    private var pump: Thread? = null
    private val featExec = Executors.newSingleThreadExecutor { r -> Thread({ boost(); hint.featTid = android.os.Process.myTid(); r.run() }, "live-feat") }
    private val synExec = Executors.newSingleThreadExecutor { r -> Thread({ boost(); r.run() }, "live-syn") }
    private fun boost() { runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }.onFailure { runCatching { android.os.Process.setThreadPriority(-10) } } }
    private val inflight = AtomicInteger(0)

    @Volatile var lastFeatMs = 0L; private set
    @Volatile var lastSynMs = 0L; private set
    @Volatile var dropped = 0L; private set
    /** 最近一块真实推理的端到端延迟，静音块按它延后送出 */
    @Volatile private var lastLagMs = 0L
    private var blkSeq = 0L
    private var gated = 0L
    /** 拖尾剩余块数（只在 featExec 单线程里读写） */
    private var gateHold = 0
    private val stFeat = ArrayList<Long>(); private val stSyn = ArrayList<Long>(); private val stLag = ArrayList<Long>()
    private var pulls = 0; private var pullSamples = 0L; private var pullGapMax = 0L; private var lastPullT = 0L; private var pumpStatT = 0L

    private fun pct(a: List<Long>, p: Double): Long = if (a.isEmpty()) 0 else a.sorted()[((a.size - 1) * p).toInt()]

    // 状态行用的滚动窗口（最近 30 块），和日志汇总（每 20 块清空）分开
    private val wFeat = ArrayDeque<Long>(); private val wSyn = ArrayDeque<Long>(); private val wLag = ArrayDeque<Long>()
    private var nBlocks = 0L; private var nOver = 0L
    @Volatile private var lastOverWarn = 0L
    /** 给 UI 状态行：blk 块长 / feat syn lag 中位 / fp95 sp95 lp95 / over 超预算块 / n 推理块 / drop 丢块 / gated 静音块（键:值;） */
    @Synchronized fun perf(): String {
        if (wLag.isEmpty()) return "blk:${BLOCK * 10};n:0;gated:$gated;drop:$dropped"
        return "blk:${BLOCK * 10};feat:${pct(wFeat.toList(), .5)};syn:${pct(wSyn.toList(), .5)};lag:${pct(wLag.toList(), .5)};" +
            "fp95:${pct(wFeat.toList(), .95)};sp95:${pct(wSyn.toList(), .95)};lp95:${pct(wLag.toList(), .95)};over:$nOver;n:$nBlocks;drop:$dropped;gated:$gated"
    }
    @Synchronized private fun record(feat: Long, syn: Long, lag: Long) {
        wFeat.addLast(feat); wSyn.addLast(syn); wLag.addLast(lag)
        while (wLag.size > 30) { wFeat.removeFirst(); wSyn.removeFirst(); wLag.removeFirst() }
        nBlocks++; if (maxOf(feat, syn) > BLOCK * 10) nOver++
        stFeat.add(feat); stSyn.add(syn); stLag.add(lag)
        if (stLag.size >= 20) {
            L.i("live 汇总(20块) 特征 p50=${pct(stFeat, .5)} p95=${pct(stFeat, .95)} max=${stFeat.max()}ms | 合成 p50=${pct(stSyn, .5)} p95=${pct(stSyn, .95)} max=${stSyn.max()}ms | " +
                "端到端 p50=${pct(stLag, .5)} p95=${pct(stLag, .95)}ms | 预算 ${BLOCK * 10}ms | 丢块累计 $dropped 静音块累计 $gated" +
                (if (spk.active) " 声纹压低累计 ${spk.blocked}" else "") +
                (if (stDec.isNotEmpty()) " | 声码器 ${voc?.kind ?: "?"} 前半图 p50=${pct(stPre, .5)} 声码器 p50=${pct(stDec, .5)} p95=${pct(stDec, .95)} max=${stDec.max()}ms" else " | 声码器 ORT 整图"))
            stFeat.clear(); stSyn.clear(); stLag.clear(); stPre.clear(); stDec.clear()
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

    /** 测速缓存的键：本音色各合成器文件 + 档位 + 线程 */
    private fun pickKey() = NETG_FILES.joinToString(",") { n -> "$n:" + ((open(n) as? File)?.let { "${it.parentFile?.name}/${it.length()}@${it.lastModified()}" } ?: -1) } + "|t$tier f$effF s$effS"

    /** force=true：忽略缓存重新测速（「性能测试」按钮） */
    fun load(onLog: (String) -> Unit, force: Boolean = false) {
        if (netG != null && !force) return
        if (force) { runCatching { voc?.close() }; voc = null; runCatching { netG?.close() }; netG = null; runCatching { pickCache?.delete() } }
        if (pinBig) CpuPin.pinSelf("加载前")
        effThreads()
        fun need(n: String): Any = open(n) ?: throw IllegalStateException("缺模型 $n，请先导入")
        fun opts(th: Int) = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(th)
            setInterOpNumThreads(1)
        }
        var o = opts(effF)
        var curTh = effF
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
        L.i("live 配置：T=$T LEN=$LEN BLOCK=${BLOCK * 10}ms XF=${XF * 1000 / SR}ms WIN=${WIN48 * 1000 / SR}ms featThreads=$effF synThreads=$effS gate=${gateDb}dB tier=$tier")
        val featWarm = hubert != null && rmvpe != null && benchFeatMs > 0 && !force
        if (hubert == null) hubert = load("HuBERT", ModelStore.HUBERT)
        if (rmvpe == null) rmvpe = load("RMVPE", ModelStore.RMVPE)
        var featMs = 0L
        // 预热 + 测速特征模型（换音色时特征模型没动，沿用上次结果，省半秒多）
        if (featWarm) {
            featMs = benchFeatMs
            // 沿用测速也要跑一遍：长时间没推理（尤其进游戏后），mmap 的权重页可能被回收、大核降频，
            // 首块会慢好几倍（实测 hubert 219 + rmvpe 362ms，丢 1 块、吞掉首句约半秒）。这里先把它跑热
            val w = warmFeat(2)
            L.i("live 特征模型已在内存，沿用测速 ${featMs}ms（预热 $w）")
        }
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
            hubMs = th / 3_000_000
            lastFeatT = System.nanoTime()
            L.i("live 测速 hubert=${th / 3_000_000}ms rmvpe=${tr / 3_000_000}ms 抽取=${td / 1_000_000}ms 特征合计≈${featMs}ms")
        }.onFailure { L.e("live 特征预热失败", it) }
        // 流式合成器：有几个版本就都测一遍，用最快的
        o = opts(effS); curTh = effS
        fun phFor(s: OrtSession) = FloatArray(T * embOf(s)) { (Math.random().toFloat() - 0.5f) }
        val pi = LongArray(T) { 120 }; val pf = FloatArray(T) { 300f }
        // 每个候选：测合成耗时 + 读出它的块长；选「特征和合成都在块长 80% 以内」里延迟最低的，都不行就选最从容的
        data class Cand(val name: String, val ms: Long, val len: Int) {
            val blockMs get() = (len - XF_FR) * 10
        }
        fun ratio(c: Cand) = (if (serial) c.ms + featMs else maxOf(c.ms, featMs)).toDouble() / c.blockMs
        val key = pickKey()
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
            var badFile = false
            for (n in NETG_FILES) {
                if (open(n) == null) continue
                val s = try { load("测速 $n", n) } catch (t: Throwable) {
                    L.e("live 加载 $n 失败", t)
                    // 文件在但读不出来（protobuf 解析失败、格式不对）：多半是音色文件损坏或导入了不支持的文件
                    if (t is ai.onnxruntime.OrtException) badFile = true
                    continue
                }
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
                1, 3 -> cands.filter { it.blockMs <= 300 }   // blockMs 是毫秒（alpha33 起误写成帧数，标准档永远落空）
                2 -> cands.filter { it.blockMs > 300 }
                else -> cands
            }.ifEmpty { if (tier != 0) L.w("live 本音色没有${if (tier == 2) "省电" else "标准"}档模型，改为自动"); cands }
            val ok = pool.filter { ratio(it) <= 0.8 }
            chosen = (if (tier != 0) pool.minByOrNull { ratio(it) } else if (ok.isNotEmpty()) ok.minWith(compareBy<Cand> { it.blockMs }.thenBy { it.ms }) else pool.minByOrNull { ratio(it) })
                ?: throw IllegalStateException(if (badFile) "音色文件损坏或格式不对，请删除该音色后重新下载或导入" else "缺流式合成器模型，请导入实时变声模型包")
        }
        val pick = chosen!!
        netG = load("流式合成器", pick.name)
        curName = pick.name
        emb = embOf(netG!!)
        index = (open(ModelStore.LIVE_INDEX) as? java.io.File)?.let { f ->
            runCatching { micmask.core.FaissIvfIndex.load(f) }.onFailure { L.e("live 检索索引加载失败", it) }.getOrNull()
        }?.takeIf { ix -> (ix.d == emb).also { if (!it) L.w("live 检索索引维度 ${ix.d} 与模型 $emb 不符，忽略") } }
        index?.let { L.i("live 检索索引：${it.ntotal} 条 × ${it.d} 维，${it.nlist} 簇，比例 $indexRate") }
        L.i("live 特征维度 $emb（${if (emb == 256) "RVC v1" else "RVC v2"}）${if (compatOf(netG!!) != null) " 兼容模式" else ""}")
        if (emb == 256 && hubert?.outputNames?.contains("feat9") == false) {   // 基础模型可能刚更新过：重载一次再判断
            runCatching { hubert?.close() }; hubert = null
            o = opts(effF); curTh = effF; hubert = load("HuBERT", ModelStore.HUBERT); o = opts(effS); curTh = effS
        }
        checkHubert()
        if (hubWant && !hubertNpu) runCatching { featMs = tryNpuHubert(featMs) }.onFailure { L.e("live NPU HuBERT 失败，留在 CPU", it) }
        LEN = runNetG(phFor(netG!!), pi, pf).size / UPP
        CUT = if (tier == 3 && LEN == 35) 5 else 0
        if (tier == 3 && CUT == 0) L.w("live 高级 0.25s 需要标准档（0.3 s）合成器，当前块长 ${(LEN - XF_FR) * 10}ms，按原块长运行")
        // 声码器：ORT 前半图 + 原生 NEON fp16 / 骁龙 NPU 跑 dec（失败或不划算就留在 ORT 整图）
        var synMs = pick.ms
        var vocNote = ""
        voc?.close(); voc = null
        val ph0 = phFor(netG!!)
        val ortMs = if (vocoder == 1) -1L else runCatching { runOrt(netG!!, ph0, pi, pf); val t = System.nanoTime(); repeat(3) { runOrt(netG!!, ph0, pi, pf) }; (System.nanoTime() - t) / 3_000_000 }.getOrDefault(-1L)
        fun bench(vp: VocPath): Long? = runCatching {
            runSplit(vp, ph0, pi, pf)
            var a = 0L; var b = 0L
            repeat(3) { runSplit(vp, ph0, pi, pf); a += lastPreMs; b += lastDecMs }
            L.i("live 声码器测速：${vp.kind} 前半图 ${a / 3}ms + 声码器 ${b / 3}ms = ${(a + b) / 3}ms（${vp.desc}）")
            (a + b) / 3
        }.onFailure { L.e("live 声码器 ${vp.kind} 试跑失败", it) }.getOrNull()
        val cands = ArrayList<Pair<VocPath, Long>>()
        if (vocoder == 0 || vocoder == 2) runCatching { buildVoc(open(pick.name), netG!!) }.onFailure { L.e("live NEON 声码器准备失败", it) }.getOrNull()
            ?.let { vp -> bench(vp)?.let { cands += vp to it } ?: vp.close() }
        if (vocoder == 0 || vocoder == 3) runCatching { buildNpu(open(pick.name), netG!!, ph0, pi, pf) }.onFailure { L.e("live NPU 声码器准备失败", it) }.getOrNull()
            ?.let { vp -> bench(vp)?.let { cands += vp to it } ?: vp.close() }
        if (ortMs >= 0) L.i("live 声码器测速：ORT 整图 ${ortMs}ms")
        val forced = cands.firstOrNull { (vocoder == 2 && it.first.kind == "NEON") || (vocoder == 3 && it.first.kind == "NPU") }
        val best = forced ?: cands.minByOrNull { it.second }?.takeIf { ortMs < 0 || it.second * 100 < ortMs * 95 }
        for ((vp, _) in cands) if (vp !== best?.first) vp.close()
        if (best != null) {
            voc = best.first; synMs = best.second; vocNote = if (best.first.kind == "NPU") " · NPU 声码器" else " · NEON 声码器"
            L.i("live 声码器：用 ${best.first.kind}${if (forced != null) "（设置指定）" else "（${best.second}ms，ORT 整图 ${ortMs}ms）"}")
        } else if (vocoder != 1) L.i("live 声码器：用 ORT 整图（${ortMs}ms）${if (cands.isNotEmpty()) "，拆分通路不更快" else ""}")
        runCatching {
            val f = pickCache ?: return@runCatching
            val old = f.takeIf { it.exists() }?.readText()?.split("\n").orEmpty().chunked(4).filter { it.size == 4 && it[0] != key }
            f.writeText((listOf(listOf(key, pick.name, "${pick.ms}", "$LEN")) + old).take(16).flatten().joinToString("\n"))
        }
        val tier = if (BLOCK <= 25) "高级" else if (BLOCK <= 30) "标准" else "省电"
        benchSummary = (if (compatOf(netG!!) != null) "兼容模式·" else "") + "${tier}档：每步 ${BLOCK * 10}ms · 特征 ${featMs}ms · 合成 ${synMs}ms · 负载 ${"%.0f".format((if (serial) synMs + featMs else maxOf(synMs, featMs)) * 100.0 / (BLOCK * 10))}%${if (serial) " · 串行" else ""}$vocNote"
        L.i("live 选用 ${pick.name} → $benchSummary")
        benchCfg = "${best?.first?.kind ?: "ORT"};${if (hubertNpu) 1 else 0}"
        if ((if (serial) synMs + featMs else maxOf(synMs, featMs)) > BLOCK * 10) L.w("live 本机最快的档位也超预算，会断音")
        if (pinBig) CpuPin.pinSelf("加载后")   // ORT 线程池在建会话时才创建，再绑一次
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
        if (!running || shifting || BLOCK > 30 || tier == 1 || (tier == 3 && CUT == 0)) return  // 手动选了标准档就不自动降；高级只退回标准
        val now = System.nanoTime()
        if (now - startT < 5_000_000_000L) return           // 刚开始的预缓冲抖动不算
        val n = synchronized(trouble) {
            trouble.addLast(now)
            while (trouble.isNotEmpty() && now - trouble.first() > 10_000_000_000L) trouble.removeFirst()
            trouble.size
        }
        if (n >= 3) { synchronized(trouble) { trouble.clear() }; if (CUT > 0) upBlock(src) else downshift(src) }
    }

    /** 高级 0.25s 跟不上：在合成线程里把 CUT 清零，回到标准 0.3s（本次运行有效） */
    private fun upBlock(src: String) {
        shifting = true
        L.w("live 高级 0.25s：10s 内断流/丢块 ≥3 次（最近：$src）→ 退回标准 0.3s")
        synExec.execute {
            CUT = 0
            benchSummary = "标准档(高级跟不上自动退回)：每步 ${BLOCK * 10}ms"
            L.i("live 已退回标准档，每步 ${BLOCK * 10}ms（本次运行有效，下次开始仍按高级 0.25s）")
            onConfig?.invoke(BLOCK * 10)
            shifting = false
        }
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
                    setIntraOpNumThreads(effS); setInterOpNumThreads(1)
                }
                var best: Pair<String, OrtSession>? = null; var bestLen = Int.MAX_VALUE
                for (n in others) {
                    val f = open(n) ?: continue
                    val t = System.nanoTime()
                    val sess = runCatching { when (f) { is File -> OrtLoad.create(env, f) { OrtSession.SessionOptions().apply { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT); setIntraOpNumThreads(effS); setInterOpNumThreads(1) } }; else -> env.createSession(f.toString(), o) } }
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
                // 当前在用 NEON 声码器：给新档位也准备一份（首次拆分约 1–3 s，在本后台线程里做）
                val nv = if (voc != null) runCatching { buildVoc(open(b.first), b.second) }.onFailure { L.e("live 降档后声码器准备失败，用 ORT 整图", it) }.getOrNull() else null
                synExec.execute {
                    val old = netG; val oldVoc = voc
                    netG = b.second; voc = nv
                    val ph = FloatArray(T * embOf(b.second)); val pi = LongArray(T) { 120 }; val pf = FloatArray(T) { 300f }
                    val newLen = runCatching { runNetG(ph, pi, pf).size / UPP }.getOrElse { L.e("live 省电档试跑失败", it); -1 }
                    if (newLen <= 0) { netG = old; voc = oldVoc; runCatching { nv?.close() }; runCatching { b.second.close() }; shifting = false; return@execute }
                    LEN = newLen; curName = b.first
                    runCatching { oldVoc?.close() }
                    runCatching { old?.close() }
                    benchSummary = "省电档(自动)：每步 ${BLOCK * 10}ms" + (if (voc != null) " · NEON 声码器" else "")
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
        // 上次运行高级 0.25s 跟不上退回了标准：重新开始时恢复
        if (tier == 3 && LEN == 35 && CUT == 0 && netG != null) { CUT = 5; L.i("live 高级 0.25s：恢复每步 ${BLOCK * 10}ms"); onConfig?.invoke(BLOCK * 10) }
        java.util.Arrays.fill(history, 0f); fresh = 0; sinceSub = 0; dnFresh = true; tgIn.reset(); tgOut.reset(); prevTail = null; dropped = 0; gated = 0; blkSeq = 0; synchronized(this) { wFeat.clear(); wSyn.clear(); wLag.clear(); nBlocks = 0; nOver = 0 }
        spk.reset(); focus?.reset()
        synExec.execute { outGain = 1f; hint.reset() }
        // 停了一阵再开始（没重新 load）：首块到来前在特征线程上先跑一遍热身，与首块录音并行
        if (hubert != null && rmvpe != null && System.nanoTime() - lastFeatT > 10_000_000_000L) featExec.execute {
            if (running) runCatching { L.i("live 开始前预热特征模型（闲置 ${(System.nanoTime() - lastFeatT) / 1_000_000_000}s）：${warmFeat(1)}") }
        }
        L.i("live 开始，semis=$semis" + if (spk.active) "，声纹门限 放行阈值=${"%.2f".format(spk.threshold)} 拦截阈值=${"%.2f".format(spk.blockThreshold)}" else "")
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

    /** 推理流水线是否在跑（热切换音色要求在跑） */
    val isRunning get() = running

    /**
     * 变声中热切换音色（2.1.1-alpha.10）。以前换音色要先停推理再重载，停的这段输出缓冲被读空（断流、爆音），
     * 新音色首次用 NPU 还要等编译 4–9 s 才出声。现在：
     * 1. 旧合成器照常出声，本线程加载新合成器（测速缓存命中照用；没命中沿用当前文件名/块长，不在变声中整套测速）并试跑一次；
     * 2. 在块边界原子换上：特征线程先切特征维度/检索索引，再在合成线程里换合成器，前后块的顺序不乱；
     *    新音色首块与旧音色尾巴照常走 50 ms 交叉淡化。先用 ORT 整图出声，[onLive] 回调；
     * 3. 后台按设置准备 NEON / NPU 声码器（含首次编译），好了再在合成线程换上；[stale] 为 true（又切换了 / 停了）就放弃。
     * 返回 null = 已换上；"" = 被新的切换取代，什么都不用做；其他 = 不能热切换的原因，调用方退回「停下重载」。
     */
    fun hotSwap(onLog: (String) -> Unit, onLive: () -> Unit, stale: () -> Boolean): String? {
        if (!running || netG == null || hubert == null || rmvpe == null) return "推理没在运行"
        if (shifting) return "正在自动换档"
        shifting = true   // 换的过程中不触发自动降档（降档也会换合成器会话）
        try { return hotSwap0(onLog, onLive, stale) } finally { shifting = false }
    }

    private fun hotSwap0(onLog: (String) -> Unit, onLive: () -> Unit, stale: () -> Boolean): String? {
        val t0 = System.nanoTime()
        fun ms(t: Long) = (System.nanoTime() - t) / 1_000_000
        fun opts() = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(effS); setInterOpNumThreads(1)
        }
        val key = pickKey()
        var name: String? = null
        runCatching {
            val c = pickCache?.takeIf { it.exists() }?.readText()?.split("\n").orEmpty().chunked(4).firstOrNull { it.size == 4 && it[0] == key }
            if (c != null && open(c[1]) != null) name = c[1]
        }
        val cached = name != null
        if (name == null) name = curName?.takeIf { open(it) != null }
            ?: NETG_FILES.firstOrNull { !it.endsWith("_compat.onnx") && open(it) != null } ?: NETG_FILES.firstOrNull { open(it) != null }
        val pick = name ?: return "缺流式合成器模型"
        onLog("加载合成器 …")
        val f = open(pick) ?: return "缺模型 $pick"
        val tl = System.nanoTime()
        val ns = try { when (f) { is File -> OrtLoad.create(env, f) { opts() }; else -> env.createSession(f.toString(), opts()) } }
                 catch (t: Throwable) { L.e("live 热切换：加载 $pick 失败", t); return "加载 $pick 失败" }
        var owned = false   // ns 交给流水线后由流水线负责关闭
        try {
            L.i("live 热切换：加载 $pick ${((f as? File)?.length() ?: 0L) shr 20}MB 用时 ${ms(tl)}ms（${if (cached) "测速缓存" else "无测速缓存，沿用当前块长对应文件"}）")
            val ne = embOf(ns)
            if (ne == 256 && hubert?.outputNames?.contains("feat9") == false) return "RVC v1 音色需要新版基础模型"
            if (ne != emb && hubertNpu) return "NPU HuBERT 与新音色特征维度不同"
            val ph = FloatArray(T * ne) { (Math.random().toFloat() - 0.5f) }
            val pi = LongArray(T) { 120 }; val pf = FloatArray(T) { 300f }
            val cp = compatOf(ns)
            // 试跑：拿块长，顺便把权重页跑热（第一块不会因冷启动慢）
            val tr = System.nanoTime()
            val nl = runCatching { (if (cp != null) runCompat(ns, cp, ph, pi, pf) else runOrt(ns, ph, pi, pf)).size / UPP }
                .onFailure { L.e("live 热切换：试跑 $pick 失败", it) }.getOrDefault(-1)
            val ortMs1 = ms(tr)
            if (nl <= XF_FR) return "试跑新合成器失败"
            val ix = (open(ModelStore.LIVE_INDEX) as? File)?.let { fi ->
                runCatching { micmask.core.FaissIvfIndex.load(fi) }.onFailure { L.e("live 检索索引加载失败", it) }.getOrNull()
            }?.takeIf { x -> (x.d == ne).also { if (!it) L.w("live 检索索引维度 ${x.d} 与模型 $ne 不符，忽略") } }
            if (stale()) { L.i("live 热切换：已被新的切换取代，不换 $pick"); return "" }
            // 换上：特征线程先切维度/索引（之后的块按新维度抽特征），再排到合成线程（排在之前各块的合成之后）
            val st = java.util.concurrent.atomic.AtomicInteger(0)   // 0 等待 1 已换 2 调用方已放弃等待
            val done = java.util.concurrent.CountDownLatch(1)
            var oldS: OrtSession? = null; var oldV: VocPath? = null
            featExec.execute {
                emb = ne; index = ix
                synExec.execute {
                    val os = netG; val ov = voc
                    netG = ns; voc = null; curName = pick; noB50Logged = false
                    val blk0 = BLOCK
                    LEN = nl; CUT = if (tier == 3 && nl == 35) 5 else 0
                    startT = System.nanoTime(); synchronized(trouble) { trouble.clear() }
                    if (BLOCK != blk0) onConfig?.invoke(BLOCK * 10)
                    if (st.compareAndSet(0, 1)) { oldS = os; oldV = ov } else { runCatching { ov?.close() }; runCatching { os?.close() } }
                    done.countDown()
                }
            }
            owned = true
            if (!done.await(10, java.util.concurrent.TimeUnit.SECONDS) && st.compareAndSet(0, 2)) {
                L.w("live 热切换：流水线 10 s 没轮到换合成器（会在轮到时换上）")
                return null
            }
            runCatching { oldV?.close() }; runCatching { oldS?.close() }
            if (index != null) L.i("live 检索索引：${ix?.ntotal} 条 × ${ix?.d} 维，${ix?.nlist} 簇，比例 $indexRate")
            L.i("live 特征维度 $emb（${if (emb == 256) "RVC v1" else "RVC v2"}）${if (cp != null) " 兼容模式" else ""}")
            val wantVoc = vocoder != 1 && cp == null && f is File
            benchSummary = hotSummary(ortMs1, cp != null, "") + if (wantVoc) " · 声码器准备中" else ""
            benchCfg = "ORT;${if (hubertNpu) 1 else 0}"
            L.i("live 热切换：已换上 $pick（ORT 整图出声，块长 ${BLOCK * 10}ms），从开始切换到出声 ${ms(t0)}ms，旧音色一直出声到换上为止")
            if (pinBig) CpuPin.pinSelf("热切换后")
            onLive()
            if (!wantVoc) return null
            // ---- 阶段 2：后台准备 NEON / NPU 声码器（与推理并行，测速偏慢但两边同样偏慢）
            val tv = System.nanoTime()
            val ortMs = if (vocoder == 0 && !stale()) runCatching {
                runOrt(ns, ph, pi, pf); val t = System.nanoTime(); repeat(3) { runOrt(ns, ph, pi, pf) }; (System.nanoTime() - t) / 3_000_000
            }.getOrDefault(-1L) else ortMs1
            fun bench(vp: VocPath): Long? = runCatching {
                runSplit(vp, ph, pi, pf, rec = false)
                val m = LongArray(2)
                repeat(3) { runSplit(vp, ph, pi, pf, rec = false, ms = m) }
                L.i("live 声码器测速：${vp.kind} 前半图 ${m[0] / 3}ms + 声码器 ${m[1] / 3}ms = ${(m[0] + m[1]) / 3}ms（${vp.desc}，与推理并行测）")
                (m[0] + m[1]) / 3
            }.onFailure { L.e("live 声码器 ${vp.kind} 试跑失败", it) }.getOrNull()
            val cands = ArrayList<Pair<VocPath, Long>>()
            if ((vocoder == 0 || vocoder == 2) && !stale()) runCatching { buildVoc(f, ns) }.onFailure { L.e("live NEON 声码器准备失败", it) }.getOrNull()
                ?.let { vp -> bench(vp)?.let { cands += vp to it } ?: vp.close() }
            if ((vocoder == 0 || vocoder == 3) && !stale()) runCatching { buildNpu(f, ns, ph, pi, pf) }.onFailure { L.e("live NPU 声码器准备失败", it) }.getOrNull()
                ?.let { vp -> bench(vp)?.let { cands += vp to it } ?: vp.close() }
            val forced = cands.firstOrNull { (vocoder == 2 && it.first.kind == "NEON") || (vocoder == 3 && it.first.kind == "NPU") }
            val best = forced ?: cands.minByOrNull { it.second }?.takeIf { ortMs < 0 || it.second * 100 < ortMs * 95 }
            for ((vp, _) in cands) if (vp !== best?.first) vp.close()
            if (best == null || stale()) {
                best?.first?.close()
                benchSummary = hotSummary(ortMs, false, "")
                L.i(if (stale()) "live 热切换：声码器准备被新的切换/停止取代，留在 ORT 整图" else "live 声码器：用 ORT 整图（${ortMs}ms）${if (cands.isNotEmpty()) "，拆分通路不更快" else ""}")
                return null
            }
            val ok = java.util.concurrent.CountDownLatch(1)
            val put = java.util.concurrent.atomic.AtomicBoolean(false)
            synExec.execute {
                if (netG === ns && running) { voc = best.first; startT = System.nanoTime(); synchronized(trouble) { trouble.clear() }; put.set(true) }
                ok.countDown()
            }
            runCatching { ok.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            if (!put.get()) { best.first.close(); benchSummary = hotSummary(ortMs, false, ""); L.i("live 热切换：合成器已变，新声码器作废"); return null }
            val note = if (best.first.kind == "NPU") " · NPU 声码器" else " · NEON 声码器"
            benchSummary = hotSummary(best.second, false, note)
            benchCfg = "${best.first.kind};${if (hubertNpu) 1 else 0}"
            L.i("live 热切换：声码器换成 ${best.first.kind}${if (forced != null) "（设置指定）" else "（${best.second}ms，ORT 整图 ${ortMs}ms）"}，后台准备用时 ${ms(tv)}ms（期间 ORT 整图出声）")
            if (pinBig) CpuPin.pinSelf("热切换声码器后")
            return null
        } finally { if (!owned) runCatching { ns.close() } }
    }

    /** 与 [load] 末尾同格式的测速摘要（热切换用；特征耗时沿用上次测速） */
    private fun hotSummary(synMs: Long, compat: Boolean, vocNote: String): String {
        val featMs = benchFeatMs
        val tn = if (BLOCK <= 25) "高级" else if (BLOCK <= 30) "标准" else "省电"
        return (if (compat) "兼容模式·" else "") + "${tn}档：每步 ${BLOCK * 10}ms · 特征 ${featMs}ms · 合成 ${synMs}ms · 负载 ${"%.0f".format((if (serial) synMs + featMs else maxOf(synMs, featMs)) * 100.0 / (BLOCK * 10))}%${if (serial) " · 串行" else ""}$vocNote"
    }

    fun stop() {
        if (running) L.i("live 停止：共 $blkSeq 块，丢 $dropped，静音 $gated" + if (spk.blocked > 0) "，声纹压低 ${spk.blocked}" else "")
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
        runCatching { synExec.submit { runCatching { voc?.close() }; voc = null; runCatching { netG?.close() }; netG = null }.get(2, java.util.concurrent.TimeUnit.SECONDS) }
            .onFailure { runCatching { voc?.close() }; voc = null; runCatching { netG?.close() }; netG = null }
        curName = null; noB50Logged = false
        L.i("live 换音色：保留特征模型，只重载合成器")
    }

    fun release() {
        stop()
        runCatching { synExec.submit { hint.close() }.get(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        featExec.shutdownNow(); synExec.shutdownNow(); spk.close()
        runCatching { hubert?.close() }; runCatching { rmvpe?.close() }; runCatching { voc?.close() }; runCatching { netG?.close() }
        hubert = null; rmvpe = null; voc = null; netG = null; hubertNpu = false
    }

    private fun pumpLoop() {
        while (running) {
            val tp = System.nanoTime()
            val raw = io?.pull(SR) ?: ShortArray(0)
            val pullMs = (System.nanoTime() - tp) / 1_000_000
            if (lastPullT != 0L && raw.isNotEmpty()) pullGapMax = maxOf(pullGapMax, (tp - lastPullT) / 1_000_000)
            if (raw.isNotEmpty()) { lastPullT = tp; pulls++; pullSamples += raw.size }
            // 声纹聚焦（Alkove）：AEC 已在 shell 录音线程做完（micRing 里就是 AEC 后的声音）；这里在历史窗 / 输入降噪 /
            // 声纹门限 / RVC 之前处理。输出只含已就绪样本（开头少约一跳），出错由 AlkoveFocus 自己旁路成原始 PCM
            // 2.1.1-alpha.6：聚焦在独立线程处理，这里只入队、取已处理好的样本，不再同步等待；关掉时把它队列里剩下的送完
            val fc = focus
            val got = if (fc != null && raw.isNotEmpty() && (fc.enabled || fc.pending())) fc.process(raw) else raw
            if (pullMs > 30) L.w("live 拉取慢：${pullMs}ms")
            if (tp - pumpStatT > 5_000_000_000L) {
                if (pumpStatT != 0L) L.m("live 拉取/5s 次数=$pulls 样本=${pullSamples * 1000 / SR}ms 最大间隔=${pullGapMax}ms 在途=${inflight.get()}")
                pumpStatT = tp; pulls = 0; pullSamples = 0; pullGapMax = 0
            }
            if (raw.isEmpty()) { try { Thread.sleep(15) } catch (_: InterruptedException) { return }; continue }
            if (got.isEmpty()) continue   // 聚焦：样本还在处理线程 / Alkove 的 16 ms 跳里
            // 滚动进历史窗
            val n = got.size
            if (n >= WIN48) {
                for (i in 0 until WIN48) history[i] = got[n - WIN48 + i] / 32768f
            } else {
                System.arraycopy(history, n, history, 0, WIN48 - n)
                for (i in 0 until n) history[WIN48 - n + i] = got[i] / 32768f
            }
            fresh += n; sinceSub += n
            if (spk.active) spk.feed(got)
            while (fresh >= BLOCK_S && running) {
                fresh -= BLOCK_S
                // 若中途还有未处理的新样本，这一步的窗口其实"超前"了 fresh 个样本；量很小，忽略
                val id = ++blkSeq
                // 开始后 2 s 内允许多 1 块在途：首块常因冷启动（页回收 / 降频 / 游戏抢核）慢，宁可晚一点也不丢首句
                val cap = if (System.nanoTime() - startT < 2_000_000_000L) 3 else 2
                if (inflight.get() >= cap) {
                    dropped++
                    L.w("live 块#$id 丢弃：处理跟不上（在途 ${inflight.get()}），补静音 ${BLOCK * 10}ms")
                    io?.push(ShortArray(BLOCK_S)); noteTrouble("丢块"); continue
                }
                val win = history.copyOf()
                inflight.incrementAndGet()
                val adv = sinceSub; sinceSub = 0
                submit(win, id, System.nanoTime(), adv, spk.mark())
            }
        }
    }

    /** 输入降噪：只处理新进来的 adv 个样本（前面带 2048 个上下文），接到 dnHist 末尾；返回降噪后的窗口 */
    private fun denoiseWindow(win48: FloatArray, adv: Int): FloatArray {
        val ctxN = 2048
        val a = if (dnFresh) WIN48 - ctxN else adv.coerceIn(1, WIN48 - ctxN)
        val s0 = WIN48 - a - ctxN
        val y = tgIn.process(win48.copyOfRange(s0, WIN48), ctxN)
        System.arraycopy(dnHist, a, dnHist, 0, WIN48 - a)
        System.arraycopy(y, ctxN, dnHist, WIN48 - a, a)
        if (dnFresh) System.arraycopy(y, 0, dnHist, s0, ctxN)
        dnFresh = false
        return dnHist.copyOf()
    }

    /** 跳过推理的块（静音门限 / 声纹门限）：送一块静音，按最近一块真实推理的延迟送出 */
    private fun emitSilence(tAvail: Long) {
        // 静音块也要按真实推理的延迟送出：否则静音→说话时输出时间轴突然往后跳一个推理延迟（~360ms），
        // shell 侧先断流再积压，积压被裁掉就是「说到一半被吞」（alpha103 真机日志）
        synExec.execute {
            try {
                val target = lastLagMs
                val waitMs = target - (System.nanoTime() - tAvail) / 1_000_000
                if (waitMs > 0) Thread.sleep(minOf(waitMs, BLOCK * 20L))
                outGain = 1f
                emit(FloatArray(OUT * UPP))
            } catch (_: InterruptedException) {
            } finally { inflight.decrementAndGet() }
        }
    }

    /** [vpEnd] = 该块在声纹门限缓冲里的结束位置（SpeakerGate.mark） */
    private fun submit(win48raw: FloatArray, id: Long, tAvail: Long, adv: Int = BLOCK_S, vpEnd: Long = -1L) {
        val sh = semis
        featExec.execute {
            try {
                val t0 = System.nanoTime()
                val waitFeat = (t0 - tAvail) / 1_000_000
                // 门限用原声判断（降噪会把轻声也压低）；推理用降噪后的窗口。关掉再打开时从头重学噪声谱
                val win48 = if (denoiseIn) runCatching { denoiseWindow(win48raw, adv) }.getOrElse { L.e("输入降噪失败", it); denoiseIn = false; win48raw }
                            else { if (!dnFresh) { dnFresh = true; tgIn.reset() }; win48raw }
                // 门限：太安静就不推理，直接送静音（省电也避免底噪被"唱"出来）
                // 按 20 ms 分段取能量最大的一段：整块平均会被句尾的静音段拉低（alpha.6 起）
                val seg = SR / 50
                var eMax = 0.0
                var i0 = win48raw.size - BLOCK_S
                while (i0 < win48raw.size) {
                    val i1 = minOf(win48raw.size, i0 + seg); var e = 0.0
                    for (i in i0 until i1) e += win48raw[i] * win48raw[i]
                    eMax = maxOf(eMax, e / (i1 - i0)); i0 = i1
                }
                val db = (10 * log10(eMax + 1e-12)).toFloat()
                val loud = db >= gateDb
                if (loud) gateHold = GATE_HOLD else if (gateHold > 0) gateHold--
                if (!loud && gateHold == 0) {
                    gated++
                    if (gated % 10 == 1L) L.m("live 块#$id 静音（${"%.0f".format(db)}dB < $gateDb）跳过推理")
                    emitSilence(tAvail)
                    return@execute
                }
                // 声纹门限：静音门限放行的块，与 HuBERT/RMVPE 并行算声纹（约每 2 块算一次），合成前取结果
                if (spk.active) spk.begin(vpEnd, loud, gateDb)
                val x16 = decim3(win48)
                val n16 = 16320
                val a16 = if (x16.size >= n16) x16.copyOfRange(x16.size - n16, x16.size)
                          else FloatArray(n16).also { System.arraycopy(x16, 0, it, n16 - x16.size, x16.size) }
                val tH = System.nanoTime()
                val hf = runHubert(a16)
                val ix = index; val ir = indexRate
                val feats = Rmvpe.nearestUp2(if (ix != null && ir > 0.01f) {
                    val ti = System.nanoTime(); ix.mix(hf, ir).also { idxMs = (System.nanoTime() - ti) / 1_000_000 }
                } else { idxMs = 0; hf })                                   // ~100 x emb
                val tR = System.nanoTime()
                val f0All = runRmvpe(a16)                                 // ~103
                val tE = System.nanoTime()
                lastFeatT = tE
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
                // 2.1.1-alpha.6：判为他人不再整块静音，照常合成后压低 SpeakerGate.ATTEN_DB（渐变）；ATTEN_DB ≤ -60 时退回旧行为
                val vpLow = spk.active && !spk.pass(vpEnd)
                if (vpLow && SpeakerGate.ATTEN_DB <= -60f) {
                    if (spk.blocked % 10 == 1L) L.m("live 块#$id 声纹不符（放行阈值 ${"%.2f".format(spk.threshold)}），跳过合成、输出静音")
                    emitSilence(tAvail)
                    return@execute
                }
                if (vpLow && spk.blocked % 10 == 1L) L.m("live 块#$id 声纹不符（放行阈值 ${"%.2f".format(spk.threshold)} / 拦截 ${"%.2f".format(spk.blockThreshold)}），压低 ${SpeakerGate.ATTEN_DB.toInt()}dB")
                // 串行：等这块合成完，featExec 才接下一块，两段不同时抢大核
                val done = if (serial) java.util.concurrent.CountDownLatch(1) else null
                synExec.execute {
                    try {
                        val t1 = System.nanoTime()
                        val usedVoc = voc?.takeIf { it.owner === netG } != null
                        val pl = phaseLock; if (pl) ph0Now = phAcc
                        val cut = CUT
                        val y = runNetG(phone, pitch, pitchf).let { if (cut > 0) it.copyOfRange(cut * UPP, it.size) else it }
                        if (pl) { var a = phAcc.toDouble(); val o = T - y.size / UPP; for (j in 0 until BLOCK) { val r = pitchf[o + j] / 100.0 + 0.5; a += r - Math.floor(r) - 0.5 }; phAcc = (a - Math.floor(a)).toFloat() }
                        post(y, win48raw, pitchf)
                        if (denoiseOut) runCatching { System.arraycopy(tgOut.process(y), 0, y, 0, y.size) }.onFailure { L.e("输出降噪失败", it); denoiseOut = false }
                        applyGain(y, if (vpLow) attenGain else 1f)
                        lastSynMs = (System.nanoTime() - t1) / 1_000_000
                        val vocTag = if (usedVoc && voc != null) "(前半${lastPreMs}+NEON声码器${lastDecMs})" else ""
                        val t2 = System.nanoTime()
                        emit(y)
                        val t3 = System.nanoTime()
                        var pk = 0f; for (v in y) pk = maxOf(pk, kotlin.math.abs(v))
                        val lag = (t3 - tAvail) / 1_000_000
                        L.m("live 块#$id in=${"%.0f".format(db)}dB 等待=${waitFeat}ms 重采样=${(tH - t0) / 1_000_000} hubert=${(tR - tH) / 1_000_000}${if (idxMs > 0) "(检索 $idxMs)" else ""} rmvpe=${(tE - tR) / 1_000_000} 特征=${featMs} " +
                            "排队=${(t1 - tQ) / 1_000_000} 合成=$lastSynMs$vocTag 推送=${(t3 - t2) / 1_000_000} 端到端=${lag}ms 浊音帧=$voiced/$T 峰值=${"%.2f".format(pk)} 在途=${inflight.get()}")
                        // 特征与合成分线程流水，吞吐只看较慢的一段是否超过块长；端到端只是延迟，超过 2 倍块长才提示。5 s 内最多一条
                        val slow = if (serial) featMs + lastSynMs else maxOf(featMs, lastSynMs)
                        val nowW = System.currentTimeMillis()
                        if ((slow > BLOCK * 10 || lag > BLOCK * 20) && nowW - lastOverWarn > 5000) {
                            lastOverWarn = nowW
                            if (slow > BLOCK * 10) L.w("live 块#$id 吞吐超预算：${if (serial) "特征+合成" else if (featMs >= lastSynMs) "特征" else "合成"} ${slow}ms > 块长 ${BLOCK * 10}ms，会积压/断音")
                            else L.w("live 块#$id 延迟偏高：端到端 ${lag}ms（块长 ${BLOCK * 10}ms）")
                        }
                        lastLagMs = lag
                        record(featMs, lastSynMs, lag)
                        // ADPF：关键路径（串行看两段之和，并行看较慢一段），目标 = 块长
                        runCatching { hint.report(slow * 1_000_000L, BLOCK * 10_000_000L) }
                    } catch (t: Throwable) {
                        L.e("live netg", t)
                    } finally { inflight.decrementAndGet(); done?.countDown() }
                }
                done?.let { runCatching { it.await(3, java.util.concurrent.TimeUnit.SECONDS) } }
            } catch (t: Throwable) {
                L.e("live feat", t)
                inflight.decrementAndGet()
            }
        }
    }

    @Volatile private var phAcc = 0f

    /** 合成后处理：共振峰偏移（按 f0 的基音同步重叠相加，PSOLA）→ 响度跟随。y 与 win48 末端对齐 */
    private fun post(y: FloatArray, win48: FloatArray, pitchf: FloatArray) {
        val fm = formant; val rf = rmsFollow
        val n = y.size; val fr = n / UPP; val o = T - fr
        if (kotlin.math.abs(fm) >= 0.05f && o >= 0) {
            val r = Math.pow(2.0, fm / 12.0)
            val src = y.copyOf(); val acc = FloatArray(n); val ws = FloatArray(n)
            var m = 0.0
            while (m < n) {
                val f = pitchf[o + minOf(fr - 1, (m / UPP).toInt())]
                if (f < 50f) { m += UPP / 2; continue }
                val p = SR / f; val c = m.toInt(); val h = (p / r).toInt().coerceAtLeast(2)
                for (k in -h..h) {
                    val t = c + k; if (t < 0 || t >= n) continue
                    val sp = c + k * r; val i0 = Math.floor(sp).toInt(); val fr2 = (sp - i0).toFloat()
                    val a = src.getOrElse(i0) { 0f }; val b = src.getOrElse(i0 + 1) { 0f }
                    val w = (0.5 + 0.5 * cos(PI * k / (h + 1))).toFloat()
                    acc[t] += (a + (b - a) * fr2) * w; ws[t] += w
                }
                m += p
            }
            for (i in 0 until n) {
                val f = pitchf[o + minOf(fr - 1, i / UPP)]
                if (f >= 50f && ws[i] > 0.3f) y[i] = acc[i] / ws[i]
            }
        }
        if (rf > 0.01f && win48.size >= n) {
            val off = win48.size - n
            fun env(x: FloatArray, base: Int, len: Int): FloatArray = FloatArray(fr + 1) { j ->
                val c = j * UPP; val a = maxOf(0, c - 2 * UPP); val b = minOf(len, c + 2 * UPP)
                var e = 0.0; for (i in a until b) { val v = x[base + i]; e += v * v }
                sqrt(e / maxOf(1, b - a)).toFloat()
            }
            val e1 = env(win48, off, n); val e2 = env(y, 0, n)
            for (i in 0 until n) {
                val j = i / UPP; val t = (i % UPP).toFloat() / UPP; val jn = minOf(fr, j + 1)
                val r1 = e1[j] + (e1[jn] - e1[j]) * t; val r2 = e2[j] + (e2[jn] - e2[j]) * t
                y[i] *= Math.pow((r1 / maxOf(r2, 1e-3f)).toDouble(), rf.toDouble()).toFloat().coerceAtMost(8f)
            }
        }
    }

    /** y: OUT*UPP 个样本；前 XF 与上块尾巴交叉淡化，输出 BLOCK_S 个，留后 XF 作新尾巴 */
    private fun emit(y0: FloatArray) {
        // 档位刚切换时在途块的长度可能和新块长不一致：取末尾 BLOCK_S+XF（输出与窗口末端对齐）
        val need = BLOCK_S + XF
        val y = if (y0.size == need) y0 else if (y0.size > need) y0.copyOfRange(y0.size - need, y0.size) else FloatArray(need).also { System.arraycopy(y0, 0, it, need - y0.size, y0.size) }
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

    @Volatile private var hubertNpu = false
    private var hubMs = 0L
    /** 最近一次特征模型推理（含测速 / 预热）的时间 */
    @Volatile private var lastFeatT = 0L

    /**
     * 特征模型预热：弱噪声跑 hubert + rmvpe（不输出），最多 [maxRuns] 次；
     * 第一次明显比测速慢（>1.5 倍）才跑第二次。返回各次耗时说明。
     */
    private fun warmFeat(maxRuns: Int): String {
        val z = java.util.Random(7).let { r -> FloatArray(16320) { (r.nextFloat() - 0.5f) * 0.01f } }
        val parts = ArrayList<String>()
        try {
            for (k in 0 until maxRuns) {
                val a = System.nanoTime(); runHubert(z); val b = System.nanoTime(); runRmvpe(z); val c = System.nanoTime()
                val h = (b - a) / 1_000_000; val r = (c - b) / 1_000_000
                parts += "hubert=${h} rmvpe=${r}ms"
                lastFeatT = c
                if (benchFeatMs > 0 && h + r <= benchFeatMs * 3 / 2) break
            }
        } catch (t: Throwable) { L.e("live 特征预热失败", t); parts += "失败" }
        return parts.joinToString("，")
    }

    /** 骁龙 NPU 跑 HuBERT：成功就替换 CPU 会话，返回新的特征耗时 */
    private fun tryNpuHubert(featMs: Long): Long {
        val dir = runCatching { qnnDirProvider?.invoke() }.getOrNull() ?: return featMs
        val f = open(ModelStore.HUBERT) as? File ?: return featMs
        if (!QnnVocoder.init(dir)) return featMs
        val cpu = hubert ?: return featMs
        val t = System.nanoTime()
        val s = QnnHubert.open(env, dir, f, 16320, cpu, featOut()) ?: return featMs
        val z = FloatArray(16320) { (Math.random().toFloat() - 0.5f) * 0.1f }
        QnnHubert.run(env, s, z, featOut())
        var ms = 0L
        repeat(3) { val a = System.nanoTime(); QnnHubert.run(env, s, z, featOut()); ms += System.nanoTime() - a }
        ms /= 3_000_000
        L.i("live NPU HuBERT 就绪：${ms}ms（CPU ${hubMs}ms），用时 ${(System.nanoTime() - t) / 1_000_000}ms")
        if (hubMs > 0 && ms >= hubMs) { L.w("live NPU HuBERT 不比 CPU 快，不用"); s.close(); return featMs }
        hubert = s; hubertNpu = true; runCatching { cpu.close() }
        val nf = if (hubMs > 0) featMs - hubMs + ms else featMs
        benchFeatMs = nf; hubMs = ms
        return nf
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
        val v = voc
        if (v != null && v.owner === s) {
            try { return runSplit(v, phone, pitch, pitchf) } catch (t: Throwable) {
                // 原生通路出错：本次运行退回 ORT 整图，不影响出声
                L.e("live 声码器 ${v.kind} 运行出错，退回 ORT 整图", t)
                if (voc === v) { voc = null; runCatching { v.close() } }
            }
        }
        return runOrt(s, phone, pitch, pitchf)
    }

    private fun runOrt(s: OrtSession, phone: FloatArray, pitch: LongArray, pitchf: FloatArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(phone), longArrayOf(1, T.toLong(), (phone.size / T).toLong())).use { a ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(pitch), longArrayOf(1, T.toLong())).use { b ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(pitchf), longArrayOf(1, T.toLong())).use { c ->
                    s.run(withPh0(env, s, mapOf("phone" to a, "pitch" to b, "pitchf" to c), ph0Now)).use { res ->
                        val o = res.get(0) as OnnxTensor
                        val fb = o.floatBuffer; val y = FloatArray(fb.remaining()); fb.get(y)
                        return y
                    }
                }
            }
        }
    }

    private data class PreOut(val z: FloatArray, val zl: Int, val har: FloatArray, val cond: FloatArray?)

    private fun preRun(v: VocPath, phone: FloatArray, pitch: LongArray, pitchf: FloatArray): PreOut {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(phone), longArrayOf(1, T.toLong(), (phone.size / T).toLong())).use { a ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(pitch), longArrayOf(1, T.toLong())).use { b ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(pitchf), longArrayOf(1, T.toLong())).use { c ->
                    v.pre.run(withPh0(env, v.pre, mapOf("phone" to a, "pitch" to b, "pitchf" to c), ph0Now)).use { res ->
                        fun get(n: String): OnnxTensor = res.get(n).orElseThrow { IllegalStateException("前半图缺输出 $n") } as OnnxTensor
                        val zt = get(v.split.z); val zl = (zt.info as TensorInfo).shape[2].toInt()
                        val z = FloatArray(zt.floatBuffer.remaining()).also { zt.floatBuffer.get(it) }
                        val ht = get(v.split.har); val har = FloatArray(ht.floatBuffer.remaining()).also { ht.floatBuffer.get(it) }
                        val cond = v.split.cond?.let { n -> val ct = get(n); FloatArray(ct.floatBuffer.remaining()).also { ct.floatBuffer.get(it) } }
                        return PreOut(z, zl, har, cond)
                    }
                }
            }
        }
    }

    /** ORT 前半图（z / har / cond）→ NEON / NPU 声码器 → 48k 波形 */
    /** [rec]=false：测速用（热切换时与推理并行跑），不改 lastPreMs/lastDecMs、不计入统计；耗时写进 [ms]（前半图, 声码器） */
    private fun runSplit(v: VocPath, phone: FloatArray, pitch: LongArray, pitchf: FloatArray, rec: Boolean = true, ms: LongArray? = null): FloatArray {
        val t0 = System.nanoTime()
        val p = preRun(v, phone, pitch, pitchf)
        val t1 = System.nanoTime()
        val y = v.dec(p.z, p.zl, p.har, p.cond)
        val t2 = System.nanoTime()
        ms?.let { it[0] += (t1 - t0) / 1_000_000; it[1] += (t2 - t1) / 1_000_000 }
        if (!rec) return y
        lastPreMs = (t1 - t0) / 1_000_000; lastDecMs = (t2 - t1) / 1_000_000
        if (running) synchronized(this) { stPre.add(lastPreMs); stDec.add(lastDecMs) }
        return y
    }

    /** 拆分（首次会处理模型）+ 前半图会话；不支持返回 null */
    private fun splitFor(f: Any?, owner: OrtSession): Pair<NeonVocoder.Split, OrtSession>? {
        if (f !is File) return null
        if (compatOf(owner) != null) return null
        if (!NeonVocoder.available()) { L.w("live 声码器：原生库不可用（${NeonVocoder.loadError}）"); return null }
        val sp = NeonVocoder.prepare(env, f, effS) ?: return null
        val pre = OrtLoad.create(env, sp.pre) {
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(effS); setInterOpNumThreads(1)
                // 前半图跑完马上轮到声码器，ORT 线程别空转抢核
                addConfigEntry("session.intra_op.allow_spinning", "0")
            }
        }
        return sp to pre
    }

    /** NEON fp16 声码器通路 */
    private fun buildVoc(f: Any?, owner: OrtSession): VocPath? {
        if (f !is File) return null
        if (!NeonVocoder.fp16Supported()) { L.w("live 声码器：CPU 不支持 ARMv8.2 fp16（asimdhp），不用 NEON"); return null }
        val t = System.nanoTime()
        val (sp, pre) = splitFor(f, owner) ?: return null
        val dec = try { NeonVocoder.open(sp.blob, effS, true) } catch (e: Throwable) { pre.close(); throw e }
        L.i("live NEON 声码器就绪 ${f.name}：${dec.desc}，用时 ${(System.nanoTime() - t) / 1_000_000}ms")
        return VocPath(owner, pre, sp, dec.desc, "NEON", { z, zl, h, c -> dec.run(z, zl, h, c) }, { dec.close() })
    }

    /** 骁龙 NPU 声码器通路：QNN 库在注入进程里才有（qnnDir） */
    private fun buildNpu(f: Any?, owner: OrtSession, ph: FloatArray, pi: LongArray, pf: FloatArray): VocPath? {
        val dir = runCatching { qnnDirProvider?.invoke() }.onFailure { L.e("live NPU：取 QNN 组件失败", it) }.getOrNull() ?: run { if (vocoder == 3) L.w("live NPU 声码器：没有 QNN 组件（在 AI 页「高级」里下载）或本机不支持"); return null }
        if (f !is File) return null
        if (!QnnVocoder.init(dir)) return null
        val t = System.nanoTime()
        val (sp, pre) = splitFor(f, owner) ?: return null
        try {
            // 前半图跑一次：拿 z 的帧数（NPU 图要固定形状）；首次校验用固定种子的 3 组输入（同一音色每次判定结果一致）
            val probe = VocPath(owner, pre, sp, "", "probe", { _, _, _, _ -> FloatArray(0) }, {})
            val zl = preRun(probe, ph, pi, pf).zl
            val samples = checkInputs(ph.size).map { (cp, cpi, cpf) ->
                val o = preRun(probe, cp, cpi, cpf)
                check(o.zl == zl) { "前半图 z 帧数不一致 ${o.zl} != $zl" }
                Triple(o.z, o.har, o.cond)
            }
            var ref: NeonVocoder.Dec? = null
            val dec = try {
                QnnVocoder.open(env, dir, sp, zl, samples) { a, b, c, d ->
                    (ref ?: NeonVocoder.open(sp.blob, effS, false).also { ref = it }).run(a, b, c, d)
                }
            } finally { runCatching { ref?.close() } }
            if (dec == null) { pre.close(); return null }
            L.i("live NPU 声码器就绪 ${f.name}：${dec.desc}，用时 ${(System.nanoTime() - t) / 1_000_000}ms")
            return VocPath(owner, pre, sp, dec.desc, "NPU", { a, b, c, d -> dec.run(a, b, c, d) }, { dec.close() })
        } catch (e: Throwable) { pre.close(); throw e }
    }

    /** NPU 声码器校验输入：固定种子（与音色无关），3 组不同音高（约 110 / 220 / 330 Hz），可复现 */
    private fun checkInputs(n: Int): List<Triple<FloatArray, LongArray, FloatArray>> =
        listOf(110f, 220f, 330f).mapIndexed { k, hz ->
            val r = java.util.Random(0x4D4D4E50L + k)
            val coarse = (1127.0 * kotlin.math.ln(1 + hz / 700.0)).let { m ->
                val lo = 1127.0 * kotlin.math.ln(1 + 50 / 700.0); val hi = 1127.0 * kotlin.math.ln(1 + 1100 / 700.0)
                ((m - lo) * 254 / (hi - lo) + 1).toLong().coerceIn(1, 255)
            }
            Triple(FloatArray(n) { r.nextFloat() - 0.5f }, LongArray(T) { coarse }, FloatArray(T) { hz })
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
