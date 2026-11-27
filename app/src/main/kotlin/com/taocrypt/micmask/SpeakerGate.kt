package com.taocrypt.micmask

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 声纹门限（实时 AI 变声，shell 注入进程里跑）：叠加在静音门限之后。
 *
 * 只对静音门限放行的块判断：取最近 [WIN_S] 秒（48k 原声，抽到 16k）算 CAM++ 嵌入，与录入的声纹算余弦相似度，
 * 低于阈值 → 这块按静音门限同样的方式处理（不送合成、输出静音）。
 *  - 不是每块都算：稳定判定为本人时相邻有声块交替「计算 / 复用上一次结果」（约每 2 块算一次）；
 *    不像本人时每块都重算（上一次还没算完就复用），好让本人一开口尽快放行；
 *  - 计算放在单独线程（1 线程 ORT），与本块的 HuBERT/RMVPE 并行，合成前取结果（最多等 [WAIT_MS]），不额外加延迟；
 *  - 判定为本人后拖尾 [HOLD_MS]（按音频时间），避免句尾/换气被吃；本人说话中单次相似度跌破阈值不立即拦截，
 *    要连续两次低于阈值才拦（迟滞，防止一个窗口里停顿/气声多时误吞）；
 *  - 窗口里有声时长不足 [MIN_SPEECH_MS]（刚开口）→ 证据不足，放行（宁可漏一点别人的开头，也不吃本人的字）；
 *  - 模型缺失 / 加载失败 → 记一条日志后全部放行（功能不可用但不影响变声）。
 * 每 [STAT_S] 秒汇总一条日志（相似度、耗时、放行/拦截块数），不逐块刷屏。
 *
 * 线程：[feed]/[mark] 在采集线程（live-pump），[begin]/[pass] 在特征线程（live-feat，单线程），计算在 live-vp。
 */
class SpeakerGate(private val modelFile: () -> File?) {
    companion object {
        const val SR = 48000
        const val WIN_S = 1.5f
        const val DEFAULT_THR = 0.35f
        const val HOLD_MS = 500
        const val MIN_SPEECH_MS = 500
        const val WAIT_MS = 200L
        const val THREADS = 1
        const val STAT_S = 10
        private const val WIN = (SR * WIN_S).toInt()
        private const val RING = SR * 3
        /** 上次结果距今不超过这么多样本（0.7 s）才可复用 */
        private const val REUSE_MAX = SR * 7 / 10
        /** 结果超过这么久（1.5 s）就作废，下一块重新判断 */
        private const val STALE = SR * 3 / 2
    }

    @Volatile private var enabled = false
    @Volatile var threshold = DEFAULT_THR; private set
    @Volatile private var ref: FloatArray? = null
    @Volatile private var broken = false
    val active: Boolean get() = enabled && ref != null && !broken

    /** 开关 / 阈值 / 录入的声纹（null = 未录入）；随时可调，即时生效 */
    fun configure(on: Boolean, thr: Float, emb: FloatArray?) {
        val r = emb?.takeIf { it.size == SpeakerEmbedder.DIM }?.let { SpeakerFbank.normalize(it) }
        val was = active
        enabled = on; threshold = thr.coerceIn(0.05f, 0.95f); ref = r
        if (on && emb != null && r == null) L.w("声纹门限：声纹维度 ${emb.size} ≠ ${SpeakerEmbedder.DIM}，忽略（请重新录入）")
        if (active != was || on) L.i("声纹门限：${if (active) "开" else "关"} 阈值=${"%.2f".format(threshold)} 声纹=${if (r != null) "已录入" else "未录入"}${if (broken) "（模型不可用）" else ""}")
        if (active) warm()
    }

    // ---------------------------------------------------------------- 原声环形缓冲（48k）
    private val ring = FloatArray(RING)
    private var total = 0L   // 已写入样本数（受 ring 锁保护）

    fun feed(pcm: ShortArray) = synchronized(ring) {
        for (v in pcm) { ring[(total % RING).toInt()] = v / 32768f; total++ }
    }
    fun mark(): Long = synchronized(ring) { total }
    private fun window(end: Long): FloatArray = synchronized(ring) {
        val e = minOf(end, total); val s = e - WIN
        FloatArray(WIN) { i -> val p = s + i; if (p < 0 || p < total - RING) 0f else ring[(p % RING).toInt()] }
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

    /** 一次计算的结果：sim=null 表示有声时长不够（证据不足） */
    class Res(val end: Long, val sim: Float?, val speechMs: Int, val costMs: Long)

    // ---------------------------------------------------------------- 判定状态（只在特征线程里读写）
    private var pending: Future<Res>? = null
    private var lastSim = Float.NaN
    private var lastPos = -1L
    /** 上上次的有效结果（迟滞用） */
    private var prevSim = Float.NaN
    private var prevPos = -1L
    private var holdUntil = -1L
    private var justComputed = false
    // 汇总
    private var stT = 0L
    private val stSim = ArrayList<Float>(); private val stCost = ArrayList<Long>()
    private var stPass = 0; private var stHold = 0; private var stBlock = 0; private var stOnset = 0
    var blocked = 0L; private set

    fun reset() {
        pending = null; lastSim = Float.NaN; lastPos = -1L; prevSim = Float.NaN; prevPos = -1L; holdUntil = -1L; justComputed = false
        synchronized(ring) { total = 0L; java.util.Arrays.fill(ring, 0f) }
        stT = System.nanoTime(); stSim.clear(); stCost.clear(); stPass = 0; stHold = 0; stBlock = 0; stOnset = 0; blocked = 0
        // 模型缺失：重新开始时再找一次（可能刚下载好、刚传进来）
        if (broken && emb == null) broken = false
        if (active) warm()
    }

    /**
     * 块开始处理时调用（静音门限已放行）。[end] = 该块在 [mark] 上的结束位置，[loud] = 本块确实有声（不是静音拖尾），
     * [floorDb] = 静音门限，用来数窗口里的有声时长。需要计算就提交到 live-vp，否则复用上一次结果。
     */
    fun begin(end: Long, loud: Boolean, floorDb: Float) {
        if (!active) return
        pending?.let { p -> if (!p.isDone) return }   // 上一次还没算完：这块复用
        pending?.let { p -> runCatching { p.get() }.getOrNull()?.let { apply(it) } }; pending = null
        // 只在「刚算过、结果新鲜且像本人」时复用；不像本人时每块都算
        val reuse = justComputed && lastPos >= 0 && end - lastPos <= REUSE_MAX && lastSim >= threshold
        if (!loud || reuse) { justComputed = false; return }
        justComputed = true
        val thr = floorDb
        pending = runCatching {
            exec.submit<Res> {
                val t0 = System.nanoTime()
                val w = window(end)
                val sp = SpeakerFbank.speechMs(w, SR, thr)
                if (sp < MIN_SPEECH_MS) return@submit Res(end, null, sp, (System.nanoTime() - t0) / 1_000_000)
                val e = load()?.embed(SpeakerFbank.decim3(w))
                val r = ref
                val sim = if (e != null && r != null) SpeakerFbank.cosine(r, e) else null
                Res(end, sim, sp, (System.nanoTime() - t0) / 1_000_000)
            }
        }.getOrNull()
    }

    private fun apply(r: Res) {
        if (r.sim == null) { stOnset++; return }
        prevSim = lastSim; prevPos = lastPos
        lastSim = r.sim; lastPos = r.end
        stSim += r.sim; stCost += r.costMs
        if (r.sim >= threshold) holdUntil = maxOf(holdUntil, r.end + SR.toLong() * HOLD_MS / 1000)
    }

    /** 合成前调用：返回 true = 放行（送合成），false = 按静音处理。[end] 同 [begin] */
    fun pass(end: Long): Boolean {
        if (!active) return true
        pending?.let { p ->
            val r = runCatching { p.get(WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            if (r != null) { apply(r); pending = null }
            else if (p.isDone) pending = null   // 计算抛异常
        }
        val fresh = lastPos >= 0 && end - lastPos <= STALE && !lastSim.isNaN()
        val prevHigh = prevPos >= 0 && end - prevPos <= STALE && !prevSim.isNaN() && prevSim >= threshold
        val ok = when {
            fresh && lastSim >= threshold -> { stPass++; true }
            end <= holdUntil -> { stHold++; true }
            !fresh -> { stPass++; true }          // 证据不足 / 还没算出来：放行
            prevHigh -> { stHold++; true }        // 迟滞：上一次还像本人，单次跌破先不拦
            else -> { stBlock++; blocked++; false }
        }
        summary()
        return ok
    }

    private fun summary() {
        val now = System.nanoTime()
        if (now - stT < STAT_S * 1_000_000_000L) return
        stT = now
        if (stPass + stHold + stBlock == 0) return
        val c = stCost.sorted()
        L.i("声纹 ${STAT_S}s：相似度 均值=${if (stSim.isEmpty()) "-" else "%.2f".format(stSim.average())} 最低=${stSim.minOrNull()?.let { "%.2f".format(it) } ?: "-"} " +
            "最高=${stSim.maxOrNull()?.let { "%.2f".format(it) } ?: "-"} 阈值=${"%.2f".format(threshold)} | 计算 ${c.size} 次 耗时 p50=${c.getOrNull(c.size / 2) ?: 0}ms 最大=${c.lastOrNull() ?: 0}ms | " +
            "放行 $stPass 块（拖尾 $stHold） 拦截 $stBlock 块 证据不足 $stOnset 次 | 拦截累计 $blocked")
        stSim.clear(); stCost.clear(); stPass = 0; stHold = 0; stBlock = 0; stOnset = 0
    }

    fun close() {
        exec.shutdownNow()
        runCatching { exec.awaitTermination(500, TimeUnit.MILLISECONDS) }
        runCatching { emb?.close() }; emb = null
    }
}
