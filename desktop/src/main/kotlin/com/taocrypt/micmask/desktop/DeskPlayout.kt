package com.taocrypt.micmask.desktop

import com.taocrypt.micmask.link.MmLink

/**
 * 电脑端接收用的自适应抖动缓冲（只在电脑版用；手机端仍用共享的 PlayoutBuffer）。
 *
 * 旧做法（PlayoutBuffer 60/180 ms）的问题（看用户日志推断）：
 *  - 缓冲涨到 150–170 ms 才一次性砍回 60 ms，砍的那一下是可听见的跳变；手机与电脑声卡时钟有漂移时会反复「涨 → 砍」；
 *  - 断流后要重新攒满 60 ms 才出声，突发抖动时断流次数累加。
 * 新做法：
 *  - 目标水位 = clamp(2×抖动 + 20 ms, 30, 120) + 断流补偿（每次断流 +10 ms，最多 +60，30 秒无断流逐步回落）；
 *  - 漂移裁剪：看 2 秒窗口里的「最低水位」，比目标高出 15 ms 以上就丢掉多出来的一半（每次 ≤ 20 ms），在丢点做 2 ms 交叉淡化，听不出跳变；
 *    用最低水位而不是平均值，所以抖动尖峰不会触发裁剪；
 *  - 断流时最后 2 ms 淡出，恢复时淡入，避免爆音；硬上限 400 ms 防止异常堆积。
 *
 * 第三轮（EasyTier 全程 TCP、RTT 70–150 ms、断流累计到 66 的日志）：
 *  - TCP 传输时音频是「一阵一阵」到的（队头阻塞），抖动均值很小但偶尔一次 100+ ms 的空档 → 目标水位还要覆盖「最近 10 秒最大到达间隔」；
 *  - 按传输方式分档：UDP 下限 30 / 上限 150 ms，TCP 下限 60 / 上限 250 ms；断流补偿 UDP 最多 +60、TCP 最多 +100 ms。
 * 所有方法线程安全（网络线程写、播放线程读）。
 */
class DeskPlayout(private val sr: Int = MmLink.SR) {
    private val cap = sr                       // 1 秒环形缓冲
    private val buf = ShortArray(cap)
    private var r = 0; private var n = 0
    private var primed = false
    private val ms = sr / 1000
    private val fade = 2 * ms

    @Volatile var jitterMs = 0f
    /** 音频经 TCP 到达（UDP 不通 / 隧道只走 TCP）：水位下限、上限更高 */
    @Volatile var tcp = false
    // 最近 10 秒每秒最大到达间隔（毫秒）
    private val gapMax = IntArray(10)
    private var gapSec = 0L
    private var lastWriteNs = 0L
    var underruns = 0L; private set
    var trimmedMs = 0L; private set
    private var boostMs = 0
    private var lastUnderrunAt = 0L
    private var winMin = Int.MAX_VALUE
    private var winStart = 0L
    private var needFadeIn = false
    private var clock = 0L   // 读过的样本数（不依赖墙钟，自测可快进）

    fun targetMs(): Int {
        val (lo, hi) = if (tcp) 60 to 250 else 30 to 150
        val gap = synchronized(this) { gapMax.max() } - 10
        return maxOf((2 * jitterMs + 20).toInt(), gap).coerceIn(lo, hi) + boostMs
    }
    /** 最近 10 秒最大到达间隔（日志用） */
    @Synchronized fun peakGapMs() = gapMax.max()

    private fun noteArrival() {
        val now = System.nanoTime()
        if (lastWriteNs != 0L) {
            val g = ((now - lastWriteNs) / 1_000_000).toInt().coerceIn(0, 1000)
            val sec = now / 1_000_000_000L
            if (sec != gapSec) { val steps = (sec - gapSec).coerceIn(1, 10).toInt(); repeat(steps) { i -> gapMax[((gapSec + 1 + i) % 10).toInt()] = 0 }; gapSec = sec }
            val b = (sec % 10).toInt(); if (g > gapMax[b]) gapMax[b] = g
        }
        lastWriteNs = now
    }
    @Synchronized fun size() = n
    @Synchronized fun sizeMs() = n / ms
    @Synchronized fun clear() { r = 0; n = 0; primed = false; winMin = Int.MAX_VALUE; winStart = clock; gapMax.fill(0); lastWriteNs = 0L }

    @Synchronized fun write(s: ShortArray, off: Int = 0, len: Int = s.size) {
        noteArrival()
        for (i in 0 until len) { buf[(r + n) % cap] = s[off + i]; if (n < cap) n++ else r = (r + 1) % cap }
        val hard = 400 * ms
        if (n > hard) { val drop = n - targetMs() * ms; r = (r + drop) % cap; n -= drop; trimmedMs += drop / ms; needFadeIn = true }
    }

    /** 总是填满 [len]（不够的补 0），返回其中真实音频的样本数 */
    @Synchronized fun read(dst: ShortArray, len: Int): Int {
        clock += len
        val target = targetMs() * ms
        if (!primed && n >= target) { primed = true; needFadeIn = true }
        if (primed) driftTrim(target)
        val k = if (primed) minOf(len, n) else 0
        for (i in 0 until k) { dst[i] = buf[r]; r = (r + 1) % cap }
        n -= k
        if (needFadeIn && k > 0) { val f = minOf(fade, k); for (i in 0 until f) dst[i] = (dst[i] * i / f).toShort(); needFadeIn = false }
        if (k < len) {
            if (primed) {
                // 断流：已拿到的尾部淡出，余下补静音；水位目标加 10 ms
                val f = minOf(fade, k); for (i in 0 until f) { val j = k - f + i; dst[j] = (dst[j] * (f - i) / f).toShort() }
                primed = false; underruns++; boostMs = (boostMs + 10).coerceAtMost(if (tcp) 100 else 60); lastUnderrunAt = clock
            }
            for (i in k until len) dst[i] = 0
        }
        // 30 秒没断流，补偿每 5 秒回落 5 ms
        if (boostMs > 0 && clock - lastUnderrunAt > 30L * sr) { boostMs -= 5; lastUnderrunAt = clock - 25L * sr }
        return k
    }

    private fun driftTrim(target: Int) {
        if (n < winMin) winMin = n
        if (clock - winStart < 2L * sr) return
        val excess = winMin - target
        winStart = clock; winMin = Int.MAX_VALUE
        if (excess <= 15 * ms) return
        val drop = (excess / 2).coerceAtMost(20 * ms)
        // 在读指针处丢 drop 个样本，用 2 ms 交叉淡化把丢点两侧接起来
        if (n < drop + fade) return
        for (i in 0 until fade) {
            val a = buf[(r + i) % cap].toInt(); val b = buf[(r + drop + i) % cap].toInt()
            buf[(r + drop + i) % cap] = ((a * (fade - i) + b * i) / fade).toShort()
        }
        r = (r + drop) % cap; n -= drop; trimmedMs += drop / ms
    }
}
