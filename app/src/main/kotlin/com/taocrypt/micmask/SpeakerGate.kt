package com.taocrypt.micmask

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 声纹门限（实时 AI 变声，shell 注入进程里跑）：叠加在静音门限之后。
 *
 * 只对静音门限放行的块判断：取最近 [LOOKBACK_S] 秒（48k 原声）截掉静音（[SpeakerFbank.voicedOnly]），只用最后 [WIN_S] 秒有声部分、
 * 抽到 16k 算 CAM++ 嵌入，与录入的声纹算余弦相似度。判定由 [VpDecision] 做（2.1.1-alpha.6）：
 *  - 最近 2 次分数平滑 + 双阈值滞回：平滑分 ≥ 放行阈值（用户滑块，默认 [DEFAULT_THR]）→ 本人；< 拦截阈值（放行 - 0.07）→ 他人；之间保持；
 *  - 判为本人后拖尾 [HOLD_MS]（按音频时间）；
 *  - 有声时长不足 [MIN_SPEECH_MS]（与录入每窗要求一致）→ 证据不足，放行；
 *  - 判为他人的块不再整块静音，由 LiveRvc 照常合成后压低 [ATTEN_DB]（渐变），误判本人时不至于完全丢字。
 *  - 不是每块都算：稳定判定为本人时相邻有声块交替「计算 / 复用」；不像本人时每块都重算（上一次还没算完就复用）；
 *  - 计算放在单独线程（1 线程 ORT），与本块的 HuBERT/RMVPE 并行，合成前取结果（最多等 [WAIT_MS]）；
 *  - 模型缺失 / 加载失败 → 记一条日志后全部放行（功能不可用但不影响变声）。
 * 每 [STAT_S] 秒汇总一条日志；每次计算的分数 / 平滑分 / 有声时长 / 电平记在详细日志（L.m）。
 *
 * 线程：[feed]/[mark] 在采集线程（live-pump），[begin]/[pass] 在特征线程（live-feat，单线程），计算在 live-vp。
 */
class SpeakerGate(private val modelFile: () -> File?) {
    companion object {
        const val SR = 48000
        /** 参与打分的有声音频上限（秒），录入时每个嵌入窗也是这么长 */
        const val WIN_S = 1.5f
        /** 往回找有声段的范围（秒） */
        const val LOOKBACK_S = 2.0f
        /**
         * 放行阈值默认值 0.35（与之前单阈值相同），拦截阈值 = 放行 - 0.07 = 0.28（滞回）。不用 0.45 这类偏高的绝对值：
         * 实测两台机分布差异大——MT6896（alpha.4）本人 0.36–0.59、他人最高 0.46；SM8650（vivo V2329A）本人最低 0.33、他人最高 0.24。
         * 0.35/0.28 在 SM8650 上本人基本不被压低、他人基本都被压低；MT6896 上他人高分段仍会漏，需用户自行调高
         */
        const val DEFAULT_THR = 0.35f
        const val HOLD_MS = 700
        /** 打分 / 录入每个窗至少要有这么多有声时长（两边一致） */
        const val MIN_SPEECH_MS = 800
        /** 判为他人时的压低量（dB）；≤ -60 视为静音（旧行为：不合成、整块静音） */
        const val ATTEN_DB = -20f
        const val WAIT_MS = 200L
        const val THREADS = 1
        const val STAT_S = 10
        private const val WIN = (SR * WIN_S).toInt()
        private const val LOOK = (SR * LOOKBACK_S).toInt()
        private const val RING = SR * 3
        /** 上次结果距今不超过这么多样本（0.7 s）才可复用 */
        private const val REUSE_MAX = SR * 7 / 10
        /** 结果超过这么久（1.5 s）就作废，下一块重新判断 */
        private const val STALE = SR * 3 / 2

        /**
         * 由一段 48k 录音算录入用的各窗嵌入（App 内录入与变声中录入共用）：先截掉静音，再在有声音频上按 [WIN_S] 窗、0.5 s 步长取窗；
         * 每窗有声 ≥ [MIN_SPEECH_MS]（截过静音，整窗都是有声）。返回各窗 L2 归一化后的嵌入
         */
        fun enrollEmbeddings(e: SpeakerEmbedder, x48: FloatArray, floorDb: Float): List<FloatArray> {
            val v = SpeakerFbank.voicedOnly(x48, SR, floorDb)
            val y = v.x
            val embs = ArrayList<FloatArray>()
            val minLen = SR * MIN_SPEECH_MS / 1000
            if (y.size < minLen) return embs
            val hop = SR / 2
            var s = 0
            while (s + WIN <= y.size) {
                e.embed(SpeakerFbank.decim3(y.copyOfRange(s, s + WIN)))?.let { embs += SpeakerFbank.normalize(it) }
                s += hop
            }
            // 有声不足一整窗但够最短有声时长：整段算一个
            if (embs.isEmpty()) e.embed(SpeakerFbank.decim3(y))?.let { embs += SpeakerFbank.normalize(it) }
            return embs
        }

        /** 各窗嵌入 → (参考嵌入, 自洽度) */
        fun meanRef(embs: List<FloatArray>): Pair<FloatArray, Float> {
            val mean = FloatArray(SpeakerEmbedder.DIM)
            for (v in embs) for (i in mean.indices) mean[i] += v[i] / embs.size
            val ref = SpeakerFbank.normalize(mean)
            return ref to embs.map { SpeakerFbank.cosine(it, ref) }.average().toFloat()
        }
    }

    @Volatile private var enabled = false
    @Volatile var threshold = DEFAULT_THR; private set
    /** 拦截阈值（放行阈值 - 0.07） */
    val blockThreshold: Float get() = VpDecision.thresholds(threshold).second
    @Volatile private var ref: FloatArray? = null
    @Volatile private var broken = false
    val active: Boolean get() = enabled && ref != null && !broken
    private val dec = VpDecision(DEFAULT_THR, VpDecision.thresholds(DEFAULT_THR).second, SR.toLong() * HOLD_MS / 1000, STALE.toLong())

    /** 开关 / 阈值（放行阈值）/ 录入的声纹（null = 未录入）；随时可调，即时生效 */
    fun configure(on: Boolean, thr: Float, emb: FloatArray?) {
        val r = emb?.takeIf { it.size == SpeakerEmbedder.DIM }?.let { SpeakerFbank.normalize(it) }
        val was = active
        enabled = on; threshold = thr.coerceIn(0.05f, 0.95f); ref = r
        val (p, b) = VpDecision.thresholds(threshold); dec.passThr = p; dec.blockThr = b
        if (on && emb != null && r == null) L.w("声纹门限：声纹维度 ${emb.size} ≠ ${SpeakerEmbedder.DIM}，忽略（请重新录入）")
        if (active != was || on) L.i("声纹门限：${if (active) "开" else "关"} 放行阈值=${"%.2f".format(p)} 拦截阈值=${"%.2f".format(b)} 拖尾=${HOLD_MS}ms " +
            "压低=${ATTEN_DB.toInt()}dB 声纹=${if (r != null) "已录入" else "未录入"}${if (broken) "（模型不可用）" else ""}")
        if (active) warm()
    }

    // ---------------------------------------------------------------- 原声环形缓冲（48k）
    private val ring = FloatArray(RING)
    private var total = 0L   // 已写入样本数（受 ring 锁保护）

    fun feed(pcm: ShortArray) = synchronized(ring) {
        for (v in pcm) { ring[(total % RING).toInt()] = v / 32768f; total++ }
    }
    fun mark(): Long = synchronized(ring) { total }
    private fun window(end: Long, len: Int = LOOK): FloatArray = synchronized(ring) {
        val e = minOf(end, total); val s = e - len
        FloatArray(len) { i -> val p = s + i; if (p < 0 || p < total - RING) 0f else ring[(p % RING).toInt()] }
    }

    // ---------------------------------------------------------------- 计算线程
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "live-vp").apply { isDaemon = true } }
    private var emb: SpeakerEmbedder? = null   // 只在 live-vp 线程里用

    private fun warm() { if (emb == null && !broken) runCatching { exec.execute { load() } } }
    private fun load(): SpeakerEmbedder? {
        emb?.let { return it }
        if (broken) return null
        val f = modelFile()
        if (f == null || !f.isFile) { broken = true; L.w("声纹门限：缺模型 ${SpeakerEmbedder.MODEL}（在设置里打开声纹门限会自动下载），本次不拦截"); return null }
        return runCatching {
            val t = System.nanoTime()
            SpeakerEmbedder(f, THREADS).also { emb = it; L.i("声纹门限：模型已加载 ${f.length() shr 20}MB 用时 ${(System.nanoTime() - t) / 1_000_000}ms 线程=$THREADS") }
        }.onFailure { broken = true; L.e("声纹门限：模型加载失败，本次不拦截", it) }.getOrNull()
    }

    /** 一次计算的结果：sim=null 表示有声时长不够（证据不足）；[levelDb] = 有声段电平 */
    class Res(val end: Long, val sim: Float?, val speechMs: Int, val costMs: Long, val levelDb: Float)

    // ---------------------------------------------------------------- 判定状态（只在特征线程里读写）
    private var pending: Future<Res>? = null
    private var lastSim = Float.NaN
    private var lastPos = -1L
    private var justComputed = false
    // 汇总
    private var stT = 0L
    private val stSim = ArrayList<Float>(); private val stCost = ArrayList<Long>(); private val stLv = ArrayList<Float>()
    private var stPass = 0; private var stHold = 0; private var stBlock = 0; private var stOnset = 0; private var stNoEv = 0
    var blocked = 0L; private set

    fun reset() {
        pending = null; lastSim = Float.NaN; lastPos = -1L; justComputed = false; dec.reset()
        synchronized(ring) { total = 0L; java.util.Arrays.fill(ring, 0f) }
        stT = System.nanoTime(); stSim.clear(); stCost.clear(); stLv.clear(); stPass = 0; stHold = 0; stBlock = 0; stOnset = 0; stNoEv = 0; blocked = 0
        // 模型缺失：重新开始时再找一次（可能刚下载好、刚传进来）
        if (broken && emb == null) broken = false
        if (active) warm()
    }

    /**
     * 块开始处理时调用（静音门限已放行）。[end] = 该块在 [mark] 上的结束位置，[loud] = 本块确实有声（不是静音拖尾），
     * [floorDb] = 静音门限，用来截静音、数有声时长。需要计算就提交到 live-vp，否则复用上一次结果。
     */
    fun begin(end: Long, loud: Boolean, floorDb: Float) {
        if (!active) return
        pending?.let { p -> if (!p.isDone) return }   // 上一次还没算完：这块复用
        pending?.let { p -> runCatching { p.get() }.getOrNull()?.let { apply(it) } }; pending = null
        // 只在「刚算过、结果新鲜且判为本人」时复用；不像本人时每块都算
        val reuse = justComputed && lastPos >= 0 && end - lastPos <= REUSE_MAX && dec.passing && lastSim >= dec.passThr
        if (!loud || reuse) { justComputed = false; return }
        justComputed = true
        val thr = floorDb
        pending = runCatching {
            exec.submit<Res> {
                val t0 = System.nanoTime()
                val v = SpeakerFbank.voicedOnly(window(end), SR, thr, maxMs = (WIN_S * 1000).toInt())
                if (v.speechMs < MIN_SPEECH_MS) return@submit Res(end, null, v.speechMs, (System.nanoTime() - t0) / 1_000_000, v.levelDb)
                val e = load()?.embed(SpeakerFbank.decim3(v.x))
                val r = ref
                val sim = if (e != null && r != null) SpeakerFbank.cosine(r, e) else null
                Res(end, sim, v.speechMs, (System.nanoTime() - t0) / 1_000_000, v.levelDb)
            }
        }.getOrNull()
    }

    private fun apply(r: Res) {
        if (r.sim == null) {
            stOnset++
            L.m("声纹 计算：证据不足 有声=${r.speechMs}ms 电平=${"%.0f".format(r.levelDb)}dB 耗时=${r.costMs}ms")
            return
        }
        lastSim = r.sim; lastPos = r.end
        val sm = dec.add(r.end, r.sim)
        stSim += r.sim; stCost += r.costMs; stLv += r.levelDb
        L.m("声纹 计算：分数=${"%.2f".format(r.sim)} 平滑=${"%.2f".format(sm)} 判定=${if (dec.passing) "本人" else "他人"} 有声=${r.speechMs}ms " +
            "电平=${"%.0f".format(r.levelDb)}dB 耗时=${r.costMs}ms（放行 ${"%.2f".format(dec.passThr)} / 拦截 ${"%.2f".format(dec.blockThr)}）")
    }

    /** 合成前调用：返回 true = 放行（正常音量），false = 判为他人（压低）。[end] 同 [begin] */
    fun pass(end: Long): Boolean {
        if (!active) return true
        pending?.let { p ->
            val r = runCatching { p.get(WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            if (r != null) { apply(r); pending = null }
            else if (p.isDone) pending = null   // 计算抛异常
        }
        val ok = when (dec.decide(end)) {
            VpDecision.Verdict.PASS -> { stPass++; true }
            VpDecision.Verdict.HOLD -> { stHold++; true }
            VpDecision.Verdict.NO_EVIDENCE -> { stNoEv++; true }   // 证据不足 / 还没算出来：放行
            VpDecision.Verdict.BLOCK -> { stBlock++; blocked++; false }
        }
        summary()
        return ok
    }

    private fun summary() {
        val now = System.nanoTime()
        if (now - stT < STAT_S * 1_000_000_000L) return
        stT = now
        if (stPass + stHold + stBlock + stNoEv == 0) return
        val c = stCost.sorted()
        L.i("声纹 ${STAT_S}s：相似度 均值=${if (stSim.isEmpty()) "-" else "%.2f".format(stSim.average())} 最低=${stSim.minOrNull()?.let { "%.2f".format(it) } ?: "-"} " +
            "最高=${stSim.maxOrNull()?.let { "%.2f".format(it) } ?: "-"} 放行阈值=${"%.2f".format(dec.passThr)} 拦截阈值=${"%.2f".format(dec.blockThr)} " +
            "有声电平 均值=${if (stLv.isEmpty()) "-" else "%.0f".format(stLv.average())}dB | 计算 ${c.size} 次 耗时 p50=${c.getOrNull(c.size / 2) ?: 0}ms 最大=${c.lastOrNull() ?: 0}ms | " +
            "放行 ${stPass + stNoEv} 块（拖尾 $stHold，无新分数 $stNoEv） 压低 $stBlock 块 证据不足 $stOnset 次 | 压低累计 $blocked")
        stSim.clear(); stCost.clear(); stLv.clear(); stPass = 0; stHold = 0; stBlock = 0; stOnset = 0; stNoEv = 0
    }

    fun close() {
        exec.shutdownNow()
        runCatching { exec.awaitTermination(500, TimeUnit.MILLISECONDS) }
        runCatching { emb?.close() }; emb = null
    }
}
