package com.taocrypt.micmask

import ai.alkove.Alkove
import ai.alkove.Event
import ai.alkove.Live
import ai.alkove.Models
import ai.alkove.Voiceprint
import java.io.File

/**
 * 声纹聚焦（Alkove Focus，shell 进程侧）：在 LiveRvc.pumpLoop 里 io.pull 之后、历史窗 / 输入降噪 / 声纹门限 / RVC 之前，
 * 把 48 kHz 单声道 PCM 过一遍 ai.alkove.Live（Focus + Cue + Timbre），只留下注册的声音（未注册时留最近、最响的人声）。
 * 设计参考了用户转来的 Alkove 接入提案（alkove-micmask.patch），按本仓库现状重写。
 *
 *  - 参数固定 gate=false（PCM 连续送 RVC）、normalize=false（不改电平）、sleep=true（静音 2 s 后 Focus/Cue 休眠）。
 *  - Live 内部：48k → 16k（窗口 sinc，实时模式晚半个窗 = 12 个 48k 样本）→ 按 256 样本（16 ms）一跳跑 Focus
 *    （Focus 自身晚 256 样本）→ 16k → 48k（再晚 12 个 16k 样本）。每次 push 只吐出已就绪的样本，数量与输入不必相等，
 *    pumpLoop 本来就按实际样本数累计，所以不补齐、不混原始声。算法延迟约 17–33 ms（视 push 与 16 ms 跳的对齐，平均约 25 ms）。
 *  - 输出带宽 16 kHz（≤ 约 7 kHz）：RVC 的 HuBERT / RMVPE 本来就在 16 kHz 上取特征，对变声结果影响小。
 *  - 模型从 [dir]（shell 的模型目录，App 经 TX_LIVE_MODELS 送来）加载，Alkove.configure(dir) 指向它，加载时校验 sha256。
 *  - 声纹 JSON 由 App 经 tune Parcel 送来，持久化到 [dir]/alkove/voiceprint.json；Live 建立时从这里读，
 *    运行中更新则 Live.setVoiceprint（在下一段静音处生效）。读不出（另一版 Timbre 的声纹）按就近人声运行。
 *  - 2.1.1-alpha.6：处理移到独立线程 alkove-focus（[process] 只入队、取已处理结果，live-pump 不再同步等待）；
 *    最早一段待处理音频超过 [TIMEOUT_MS] 还没处理完 → 这些音频改用原始声音（顺序不乱，记「超时旁路」）。
 *  - 负载分级、可恢复（防抖）：已注册 [LV_FULL] → 跟不上（处理占音频时长 > [RT_LIMIT] 或超时旁路 > 20%）先降为
 *    未注册（就近人声）[LV_NEAR] → 再跟不上降为全旁路 [LV_BYPASS]；降级后冷却 [COOL0_S] 秒再试着升一级
 *    （旁路时直接试；未注册升已注册要占用 < [UP_LIMIT]），升级后 30 s 内又跟不上则冷却时间翻倍（最多 [COOL_MAX_S] 秒）。
 *    跟不上时 [note] 给出「本机性能不足」提示（App 状态行显示）。
 *  - 异常 / 模型缺失 → 本次变声旁路原始 PCM 并记日志；下次开始变声（[reset]）再试。
 *  - 每 10 s 汇总一条日志：处理耗时 p50 / 最大、实时占用、休眠占比、输入增益、等级、超时旁路、旁路原因。
 *
 * 线程：[process] 只在 live-pump 线程调用；处理在 alkove-focus；[configure] 在 binder 线程；[reset] 在启动线程。
 * [q] 保护队列与统计，[lock] 保护 Live 对象。
 */
class AlkoveFocus(private val dir: File) {
    companion object {
        const val SR = 48000
        val NAMES = listOf("focus", "cue", "timbre")
        private const val STAT_NS = 10_000_000_000L
        /** 一个统计窗内处理耗时占音频时长超过这个比例 → 判定跟不上实时，降一级 */
        private const val RT_LIMIT = 0.5
        /** 未注册 → 已注册 的试升条件：未注册时占用低于它（已注册约是未注册的 2–3 倍） */
        private const val UP_LIMIT = 0.2
        /** 一个统计窗内超时旁路的音频占比超过它也算跟不上 */
        private const val TIMEOUT_FRAC = 0.2
        const val TIMEOUT_MS = 150L
        const val COOL0_S = 20
        const val COOL_MAX_S = 160
        const val LV_BYPASS = 0
        const val LV_NEAR = 1
        const val LV_FULL = 2
    }

    /** 声纹聚焦开关（tune 送来）。关时 LiveRvc 只在还有待处理音频时调 [process]（把它们原样送出） */
    @Volatile var enabled = false; private set

    private val lock = Any()
    @Volatile private var models: Models? = null
    @Volatile private var loading = false
    @Volatile private var loadErr: String? = null
    private var live: Live? = null
    /** 当前 Live 是按哪一级建的 */
    private var liveLevel = -1
    /** 本次变声的旁路原因（null = 正常工作）；[reset] 清掉 */
    @Volatile private var fail: String? = null
    @Volatile private var hasPrint = false
    private var fbuf = FloatArray(4800)

    /** 当前等级（[LV_FULL]/[LV_NEAR]/[LV_BYPASS]），负载自适应改它；worker 发现与 Live 不符时重建 */
    @Volatile var level = LV_FULL; private set
    /** 给 App 状态行的提示（空 = 无）：本机性能不足时非空 */
    @Volatile var note = ""; private set

    // ---- 队列（[q] 保护）
    private class Chunk(val x: ShortArray, val t: Long, val gen: Int)
    private val q = Object()
    private val inQ = ArrayDeque<Chunk>()
    private val outQ = ArrayDeque<ShortArray>()
    private var cur: Chunk? = null
    private var gen = 0
    private var worker: Thread? = null

    // ---- 统计（[q] 保护；处理耗时在 worker 写，其余在 pump 写）
    private val costs = LongArray(4096)
    private var nCost = 0
    private var maxCost = 0L
    private var procNs = 0L
    private var procSamples = 0L
    private var inSamples = 0L
    private var bypassSamples = 0L
    private var timeoutSamples = 0L
    private var timeouts = 0
    private var sleepPushes = 0
    @Volatile private var gainDb = Double.NaN
    private var gainT = 0L
    private var statT = 0L
    private var lastReason: String? = null
    // ---- 分级（只在 pump 线程）
    private var lvChangeT = 0L
    private var lastUpT = 0L
    private var coolS = COOL0_S

    val printFile: File get() = File(File(dir, "alkove"), "voiceprint.json")

    /**
     * tune 送来的开关和声纹。[updatePrint] = Parcel 里带了声纹字段（旧版 App 不带 → 不动已存的声纹）；
     * [json] 空 = 未注册（删除已存的，按就近人声）。
     */
    fun configure(on: Boolean, json: String?, updatePrint: Boolean) {
        if (updatePrint) {
            val f = printFile
            val old = runCatching { if (f.isFile) f.readText() else null }.getOrNull()
            val j = json?.takeIf { it.isNotBlank() }
            if (j != old) {
                runCatching {
                    if (j == null) f.delete()
                    else { f.parentFile?.mkdirs(); val tmp = File(f.path + ".part"); tmp.writeText(j); f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("rename ${f.name}") }
                }.onSuccess { L.i("聚焦：声纹已${if (j == null) "删除（按就近人声）" else "保存 ${j.length} 字节"} → ${f.path}") }
                    .onFailure { L.e("聚焦：声纹写入 ${f.path} 失败", it) }
                // 运行中：已注册等级直接换声纹（Alkove 在下一段静音处生效，不重建）；等级不符时 worker 会按新等级重建
                synchronized(lock) { live?.let { l -> if (liveLevel == LV_FULL) runCatching { l.setVoiceprint(readPrint()) }.onFailure { L.w("聚焦：运行中更新声纹失败：${it.message}") } else liveLevel = -1 } }
            }
        }
        if (on != enabled) L.i("声纹聚焦（Alkove）→ ${if (on) "开" else "关"}")
        enabled = on
        if (on) { preload(); ensureWorker() } else { synchronized(lock) { live?.let { runCatching { it.close() } }; live = null; liveLevel = -1 }; note = "" }
    }

    /** 开始变声时调：丢掉上次的流状态与队列，清旁路原因、等级回到最高；模型上次没加载成功（比如当时还没送达）再试一次 */
    fun reset() {
        synchronized(q) { gen++; inQ.clear(); outQ.clear(); cur = null; clearStats() }
        synchronized(lock) { live?.let { runCatching { it.close() } }; live = null; liveLevel = -1; fail = null }
        if (loadErr != null && models == null) loadErr = null
        statT = 0L; lastReason = null
        level = LV_FULL; lvChangeT = 0L; lastUpT = 0L; coolS = COOL0_S; note = ""
        if (enabled) { preload(); ensureWorker() }
    }

    /** 后台加载模型（读文件 + sha256 + 原生初始化），不占 pump 线程；加载完之前 [process] 旁路 */
    private fun preload() {
        if (models != null || loading || loadErr != null) return
        loading = true
        Thread({
            try {
                if (!Alkove.supported) throw IllegalStateException("本机 ABI 没有 Alkove 原生库（只有 arm64-v8a / x86_64）")
                val miss = AlkoveStore.FILES.filter { !File(dir, it).isFile }
                if (miss.isNotEmpty()) throw IllegalStateException("模型未送达：${miss.joinToString()}")
                val t0 = System.nanoTime()
                Alkove.configure(dir = dir)
                models = Models.load(NAMES, dir = dir)
                L.i("聚焦：模型已加载（Alkove ${Alkove.version}，${NAMES.joinToString("+")}，用时 ${(System.nanoTime() - t0) / 1_000_000}ms）")
            } catch (t: Throwable) {
                loadErr = t.message ?: t.javaClass.simpleName
                L.e("聚焦：模型加载失败，本次变声旁路原始声音", t)
            } finally { loading = false }
        }, "alkove-load").start()
    }

    /** 已存声纹；没有或读不出（另一版 Timbre 的声纹）→ null，按就近人声 */
    private fun readPrint(): Voiceprint? {
        val f = printFile
        if (!f.isFile) { hasPrint = false; return null }
        val vp = runCatching { Voiceprint.fromJson(f.readText()) }.onFailure { L.w("聚焦：声纹读取失败：${it.message}") }.getOrNull()
        if (vp == null) L.w("聚焦：已存声纹与当前 Timbre 版本不符或已损坏，按就近人声运行；请重新注册")
        hasPrint = vp != null
        return vp
    }

    private fun ensureWorker() {
        synchronized(q) {
            if (worker?.isAlive == true) return
            worker = Thread({ workLoop() }, "alkove-focus").apply { isDaemon = true; start() }
        }
    }

    /** 处理线程：取一段 → 过 Live → 结果入 outQ（期间被判超时 / 重置的丢弃） */
    private fun workLoop() {
        runCatching { android.os.Process.setThreadPriority(-16) }.onFailure { runCatching { android.os.Process.setThreadPriority(-10) } }
        while (true) {
            val c: Chunk = synchronized(q) {
                while (inQ.isEmpty()) { try { q.wait(200) } catch (_: InterruptedException) { return } }
                inQ.removeFirst().also { cur = it }
            }
            var y: ShortArray? = null
            var cost = 0L; var slept = false
            try {
                val m = models
                if (m != null && fail == null) synchronized(lock) {
                    val lv = level
                    if (live == null || liveLevel != lv) {
                        live?.let { runCatching { it.close() } }; live = null
                        if (lv != LV_BYPASS) {
                            val vp = if (lv == LV_FULL) readPrint() else { hasPrint = printFile.isFile; null }
                            live = Live(m, SR, gate = false, normalize = false, voiceprint = vp, sleep = true)
                            L.i("聚焦：开始（独立线程；48k→16k Focus→48k，算法延迟约 17–33ms；声纹=${if (vp != null) "已注册" else if (lv == LV_NEAR && hasPrint) "已注册但按就近人声（降级）" else "未注册，按就近人声"}；gate=关 normalize=关 sleep=开）")
                        }
                        liveLevel = lv
                    }
                    val l = live
                    if (l != null) {
                        val x = c.x
                        if (fbuf.size < x.size) fbuf = FloatArray(x.size)
                        for (i in x.indices) fbuf[i] = x[i] / 32768f
                        val t0 = System.nanoTime()
                        val (out, ev) = l.push(fbuf, x.size)
                        cost = System.nanoTime() - t0
                        for (e in ev) if (e is Event.Diag && e.code == "idle") L.m("聚焦：${if (e.data["asleep"] == true) "静音休眠" else "唤醒"}")
                        slept = l.sleeping
                        if (t0 - gainT > 1_000_000_000L) { gainT = t0; gainDb = runCatching { l.inputGainDb }.getOrDefault(Double.NaN) }
                        y = ai.alkove.toInt16(out)
                    }
                }
            } catch (t: Throwable) {
                fail = "处理出错：${t.message ?: t.javaClass.simpleName}"
                L.e("聚焦：处理出错，本次变声改为旁路原始声音", t)
                synchronized(lock) { live?.let { runCatching { it.close() } }; live = null; liveLevel = -1 }
            }
            synchronized(q) {
                if (c.gen == gen) {
                    outQ.addLast(y ?: c.x)          // 没处理（旁路 / 出错）就送原始声音
                    if (y == null) bypassSamples += c.x.size
                    else {
                        if (nCost < costs.size) costs[nCost++] = cost
                        maxCost = maxOf(maxCost, cost); procNs += cost; procSamples += c.x.size
                        if (slept) sleepPushes++
                    }
                }
                cur = null
            }
        }
    }

    /** 还有没送出的音频（关掉聚焦时 LiveRvc 据此再调一次 [process] 把它们送出） */
    fun pending(): Boolean = synchronized(q) { inQ.isNotEmpty() || outQ.isNotEmpty() || cur != null }

    /**
     * 处理一段 48 kHz 单声道 PCM：入队交给 alkove-focus，返回已处理好的结果（长度可与输入不同，通常晚约一次拉取）。
     * 旁路 / 关闭时把队列里剩下的（原始或已处理）按顺序送出，再原样接上输入。只在 live-pump 线程调用。
     */
    fun process(x: ShortArray): ShortArray {
        val now = System.nanoTime()
        if (statT == 0L) statT = now
        val m = models
        val why = when {
            !enabled -> "已关闭"
            fail != null -> fail
            loadErr != null -> "模型加载失败：$loadErr"
            m == null -> if (loading) "模型加载中" else "模型未加载"
            level == LV_BYPASS -> "本机性能不足，暂时旁路"
            else -> null
        }
        val parts = ArrayList<ShortArray>(4)
        synchronized(q) {
            inSamples += x.size
            if (why == null) {
                if (x.isNotEmpty()) { inQ.addLast(Chunk(x.copyOf(), now, gen)); q.notifyAll() }
                while (outQ.isNotEmpty()) parts += outQ.removeFirst()
                val oldest = cur ?: inQ.firstOrNull()
                if (oldest != null && oldest.gen == gen && now - oldest.t > TIMEOUT_MS * 1_000_000L) {
                    // 超时：在途 + 排队的都改用原始声音（在途那段处理完会因 gen 变了被丢弃）
                    gen++
                    var n = 0
                    cur?.let { parts += it.x; n += it.x.size }
                    while (inQ.isNotEmpty()) { val c = inQ.removeFirst(); parts += c.x; n += c.x.size }
                    timeouts++; timeoutSamples += n
                }
            } else {
                // 旁路：先把队列里剩下的按顺序送出（已处理的 + 在途/排队的原始声音），再接上本段原始声音
                while (outQ.isNotEmpty()) parts += outQ.removeFirst()
                if (cur != null || inQ.isNotEmpty()) {
                    gen++
                    cur?.let { parts += it.x }
                    while (inQ.isNotEmpty()) parts += inQ.removeFirst().x
                }
                parts += x; bypassSamples += x.size
            }
        }
        val r = fail ?: why?.takeIf { enabled }
        if (r != lastReason) { if (r != null) L.w("聚焦：旁路（$r）") else if (lastReason != null) L.i("聚焦：恢复处理"); lastReason = r }
        if (enabled && now - statT >= STAT_NS) { report(now); statT = now }
        return when (parts.size) {
            0 -> ShortArray(0)
            1 -> parts[0]
            else -> { val n = parts.sumOf { it.size }; val y = ShortArray(n); var o = 0; for (p in parts) { System.arraycopy(p, 0, y, o, p.size); o += p.size }; y }
        }
    }

    private fun lvName(lv: Int) = when (lv) { LV_FULL -> "已注册"; LV_NEAR -> "就近人声"; else -> "旁路" }

    /** 10 s 汇总 + 负载分级（pump 线程） */
    private fun report(now: Long) {
        val n: Int; val a: LongArray; val load: Double; val audioS: Double; val tFrac: Double
        val mx: Long; val sl: Int; val bp: Long; val tos: Int; val tSamp: Long
        synchronized(q) {
            n = nCost; a = costs.copyOf(n); mx = maxCost; sl = sleepPushes; bp = bypassSamples; tos = timeouts; tSamp = timeoutSamples
            audioS = inSamples.toDouble() / SR
            load = if (procSamples > 0) procNs / 1e9 / (procSamples.toDouble() / SR) else 0.0
            tFrac = if (inSamples > 0) timeoutSamples.toDouble() / inSamples else 0.0
            clearStats()
        }
        val maxLv = if (printFile.isFile) LV_FULL else LV_NEAR
        val lv0 = level
        // ---- 分级：跟不上降一级；冷却后试着升一级（防抖：升级后很快又跟不上，冷却翻倍）
        if (fail == null && models != null) {
            val over = (n > 0 && load > RT_LIMIT) || tFrac > TIMEOUT_FRAC
            if (lv0 > maxLv) level = maxLv
            if (over && lv0 > LV_BYPASS) {
                val to = if (lv0 == LV_FULL && maxLv == LV_FULL) LV_NEAR else LV_BYPASS
                if (lastUpT != 0L && now - lastUpT < 30_000_000_000L) coolS = minOf(coolS * 2, COOL_MAX_S)
                level = to; lvChangeT = now
                note = "声纹聚焦：本机性能不足，已降级"
                L.w("聚焦：跟不上实时（处理占音频时长 ${(load * 100).toInt()}%，超时旁路 ${(tFrac * 100).toInt()}%）→ ${lvName(to)}；${coolS}s 后再试（本机性能不足）")
            } else if (!over && level < maxLv && lvChangeT != 0L && now - lvChangeT >= coolS * 1_000_000_000L &&
                (level == LV_BYPASS || load < UP_LIMIT)) {
                val to = level + 1
                L.i("聚焦：负载回落（${if (level == LV_BYPASS) "旁路 ${coolS}s" else "占用 ${(load * 100).toInt()}%"}）→ 试着恢复为 ${lvName(to)}")
                level = to; lvChangeT = now; lastUpT = now
            } else if (!over && lastUpT != 0L && now - lastUpT > 60_000_000_000L) {
                coolS = COOL0_S; lastUpT = 0L
            }
            if (level == maxLv && !over) note = ""
        }
        val head = if (n > 0) {
            a.sort()
            "处理耗时 p50=${"%.2f".format(a[n / 2] / 1e6)}ms 最大=${"%.2f".format(mx / 1e6)}ms（$n 次，实时占用 ${(load * 100).toInt()}%，休眠 ${sl * 100 / n}%）"
        } else "处理耗时 —（本 10 s 未处理）"
        val g = gainDb
        val bpS = if (bp > 0) "旁路 ${bp * 1000 / SR}ms 音频，原因：${fail ?: lastReason ?: "?"}" else "旁路原因：无"
        L.i("聚焦：$head · 输入增益 ${if (g.isNaN()) "?" else "%.1f".format(g)}dB · 等级 ${lvName(level)}${if (level != lv0) "（本窗 ${lvName(lv0)}）" else ""} · " +
            "超时旁路 $tos 次 ${tSamp * 1000 / SR}ms · 声纹=${if (printFile.isFile) "已注册" else "未注册（就近人声）"} · $bpS · 已用 ${"%.1f".format(audioS)}s 音频")
    }

    private fun clearStats() { nCost = 0; maxCost = 0L; procNs = 0L; procSamples = 0L; inSamples = 0L; bypassSamples = 0L; timeoutSamples = 0L; timeouts = 0; sleepPushes = 0 }
}
