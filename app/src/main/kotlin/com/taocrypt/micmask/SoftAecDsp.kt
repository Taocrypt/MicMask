package com.taocrypt.micmask

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 软件回声消除（SoftAec）用到的纯计算部分，不依赖 Android，可在 JVM 上单测：
 *  - [AecBandBridge]：48 kHz ⇄ 16 kHz 分带。webrtc-aec3-kmp 1.0.3 的 JNI 在 32/48 kHz 下没有调用
 *    SplitIntoFrequencyBands/MergeFrequencyBands（上游 2026-09-16 的提交 b493670 才补上，Maven 上仍是 1.0.3），
 *    AEC3 看到的参考恒为空、永不收敛、输出原样返回（实测与 vivo V2329A 日志一致：ERL=-30.0 ERLE=0.2 估计延迟=12 ms）。
 *    16 kHz 是单频带，不受影响，所以 AEC3 改在 16 kHz 跑：低频带（< ~7 kHz）由 AEC3 处理，
 *    高频带 = 原信号 − 低频带（同样经过一遍降采样/升采样），按低频带的压低比例同步压低。
 *  - [PlayoutAligner]：按耳返 AudioTrack 实际出声位置（优先 getTimestamp，退回 playbackHeadPosition）把参考对齐到「这帧麦克风录音时刚出声的 10 ms」；
 *    [CaptureClock] 估计麦克风帧的录音时刻，[TrackClock] 把时间戳外推到该时刻。
 *  - [MonQueue]：耳返排队稳定器（启动/欠载后预填静音到目标排队，长期积压过多时少写一点）。
 *  - [AecDuck]：兜底压低（AEC 持续无效时，只在回声主导的帧把麦克风压低最多 8 dB，近端说话不压）。
 */

/** Kaiser 窗低通 FIR（奇数长度，线性相位，群延迟 (n-1)/2，直流增益 1） */
internal object AecFir {
    fun lowpass(n: Int, cutoffHz: Double, sr: Double, beta: Double): FloatArray {
        require(n % 2 == 1)
        val m = (n - 1) / 2
        val fc = cutoffHz / sr
        val h = DoubleArray(n)
        val i0b = besselI0(beta)
        for (i in 0 until n) {
            val k = i - m
            val sinc = if (k == 0) 2 * fc else sin(2 * PI * fc * k) / (PI * k)
            val r = k.toDouble() / m
            val w = besselI0(beta * sqrt(max(0.0, 1 - r * r))) / i0b
            h[i] = sinc * w
        }
        val s = h.sum()
        return FloatArray(n) { (h[it] / s).toFloat() }
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0; var term = 1.0; var k = 1
        while (k < 50) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; if (term < sum * 1e-12) break; k++ }
        return sum
    }
}

/** 流式 3:1 降采样（因果 FIR，群延迟 (h.size-1)/2 个 48k 样本） */
internal class Decim3(private val h: FloatArray) {
    private val hist = FloatArray(h.size - 1 + 480 * 2)

    /** [x] 长度为 3 的倍数（这里固定 480）→ [y] 长度 x.size/3 */
    fun process(x: FloatArray, y: FloatArray) {
        val l = h.size - 1
        System.arraycopy(x, 0, hist, l, x.size)
        for (m in 0 until x.size / 3) {
            // y[m] = Σ h[k]·x[3m-k]（与升采样的补零位置对齐，降+升合起来正好延迟 2G）
            val c = l + 3 * m
            var acc = 0f
            for (k in h.indices) acc += h[k] * hist[c - k]
            y[m] = acc
        }
        // 留下最后 l 个样本作为下一帧的历史
        System.arraycopy(hist, x.size, hist, 0, l)
    }

    fun reset() { java.util.Arrays.fill(hist, 0f) }
}

/** 流式 1:3 升采样（补零 + 同一低通 ×3，群延迟 (h.size-1)/2 个 48k 样本） */
internal class Interp3(h: FloatArray) {
    // 多相分解：phase p 用 h[p], h[p+3], …
    private val nTap = (h.size + 2) / 3
    private val ph = Array(3) { p -> FloatArray(nTap) { j -> val k = p + 3 * j; if (k < h.size) 3f * h[k] else 0f } }
    private val hist = FloatArray(nTap - 1 + 160 * 2)

    /** [x] 16k 一帧（160）→ [y] 48k（480） */
    fun process(x: FloatArray, y: FloatArray) {
        val l = nTap - 1
        System.arraycopy(x, 0, hist, l, x.size)
        for (m in x.indices) {
            val c = l + m
            for (p in 0 until 3) {
                val f = ph[p]; var acc = 0f
                for (j in 0 until nTap) acc += f[j] * hist[c - j]
                y[3 * m + p] = acc
            }
        }
        System.arraycopy(hist, x.size, hist, 0, l)
    }

    fun reset() { java.util.Arrays.fill(hist, 0f) }
}

/** 固定延迟线（float） */
internal class FDelay(private val d: Int) {
    private val buf = FloatArray(max(1, d))
    private var p = 0
    fun process(x: FloatArray, y: FloatArray) {
        if (d == 0) { System.arraycopy(x, 0, y, 0, x.size); return }
        for (i in x.indices) { val v = buf[p]; buf[p] = x[i]; y[i] = v; p++; if (p == d) p = 0 }
    }
    fun reset() { java.util.Arrays.fill(buf, 0f); p = 0 }
}

/**
 * 48 kHz 一帧（480）⇄ 16 kHz 一帧（160）的分带桥。用法（每 10 ms 一次，顺序固定）：
 *  1. [render48]：参考 48k → 16k（喂给 AEC3 的 analyzeRender）
 *  2. [capture48]：麦克风 48k → 16k（喂给 AEC3 的 analyzeCapture/processCapture，原地处理）
 *  3. [merge]：AEC3 处理后的 16k + 第 2 步留下的高频带 → 48k 输出
 * 延迟：低频带 = 降采样 G + AEC3 自身 [aecLat16]×3 + 升采样 G；高频带与「未经 AEC 的低频带」都对齐到同样的延迟，
 * 所以 AEC3 不改动信号时输出 = 输入延迟 [latency48] 个样本（逐样本精确，不含混叠误差）。
 */
internal class AecBandBridge(private val aecLat16: Int = AEC3_LAT16) {
    companion object {
        const val N48 = 480
        const val N16 = 160
        /** 实测（webrtc-aec3-kmp 1.0.3，16 kHz）：processCapture 输出比输入晚 128 个样本（8 ms） */
        const val AEC3_LAT16 = 128
        const val TAPS = 97
        const val CUTOFF_HZ = 7600.0
    }

    private val h = AecFir.lowpass(TAPS, CUTOFF_HZ, 48000.0, 6.0)
    private val g = (TAPS - 1) / 2
    /** 整条链路相对输入的延迟（48k 样本） */
    val latency48 = 2 * g + 3 * aecLat16

    private val decR = Decim3(h)
    private val decC = Decim3(h)
    private val upA = Interp3(h)   // AEC 后的低频带
    private val upD = Interp3(h)   // 未经 AEC 的低频带（同样延迟），用来求高频带
    private val dly16 = FDelay(aecLat16)
    private val dly48 = FDelay(latency48)

    val r16 = FloatArray(N16)
    val c16 = FloatArray(N16)
    private val c16d = FloatArray(N16)
    private val xd = FloatArray(N48)
    private val lowA = FloatArray(N48)
    private val lowD = FloatArray(N48)
    private var eD = 0.0; private var eA = 0.0
    /** 高频带增益（随低频带压低比例变化，平滑） */
    var hiGain = 1f; private set

    fun render48(x: FloatArray): FloatArray { decR.process(x, r16); return r16 }

    /** 麦克风 48k → [c16]（返回同一数组，由 AEC3 原地处理） */
    fun capture48(x: FloatArray): FloatArray {
        decC.process(x, c16)
        dly16.process(c16, c16d)          // 未经 AEC 的 16k，延迟到与 AEC3 输出对齐
        dly48.process(x, xd)              // 原始 48k，延迟到与输出对齐
        return c16
    }

    /** [a16] = AEC3 处理后的 16k（通常就是 [c16]）→ [out] 48k */
    fun merge(a16: FloatArray, out: FloatArray) {
        upA.process(a16, lowA)
        upD.process(c16d, lowD)
        var ed = 0.0; var ea = 0.0
        for (i in 0 until N16) { ed += c16d[i].toDouble() * c16d[i]; ea += a16[i].toDouble() * a16[i] }
        // 低频带的压低比例 → 高频带增益：压得快（立即跟随），放得慢（每帧最多 +1.5 dB）
        eD = 0.5 * eD + 0.5 * ed; eA = 0.5 * eA + 0.5 * ea
        val tgt = if (eD <= 1e-3) 1f else min(1.0, sqrt(eA / eD)).toFloat()
        val prev = hiGain
        hiGain = if (tgt < hiGain) tgt else min(tgt, hiGain * 1.19f)
        for (i in 0 until N48) {
            val gg = prev + (hiGain - prev) * (i + 1) / N48
            out[i] = lowA[i] + gg * (xd[i] - lowD[i])
        }
    }

    fun reset() {
        decR.reset(); decC.reset(); upA.reset(); upD.reset(); dly16.reset(); dly48.reset()
        eD = 0.0; eA = 0.0; hiGain = 1f
    }
}

/**
 * 按耳返实际出声位置对齐 AEC 参考。
 * - [write]：每次写进耳返 AudioTrack 的样本（含预填的静音）按写入顺序记下，绝对下标从 0 起 = 这个 AudioTrack 的帧号。
 * - [read]：每处理一帧麦克风取一帧参考，取的是「出声位置之前的最后 hop 个样本」= 这帧麦克风录音时刚从喇叭出来的声音。
 *   出声位置由调用方给：alpha.7 之后优先用 AudioTrack.getTimestamp()（framePosition + nanoTime，含 HAL/DSP 出声延迟）
 *   外推到这帧麦克风的录音时刻（[CaptureClock]），取不到再退回 playbackHeadPosition（只到混音器，不含 DSP 延迟）。
 *   出声位置的抖动用指数平滑吸收，偏差超过 [nudgeMs] 才挪一次（计入 [nudges]）：
 *   参考落后 → 跳过几毫秒；参考超前（欠载时喇叭在放静音、出声位置停住）→ 补同样长的静音，参考与实际外放保持同一时间线，
 *   不再只靠逐次 3 ms 的跳动慢慢追（每跳一次 AEC3 都当回声路径变了）。偏差超过 [resyncMs]：超前且不超过 [maxZeroMs] 时一次补足静音，
 *   否则直接重同步（都计入 [resyncs]）。
 * - playedFrames < 0（取不到出声位置）时退回「刚写进去的就当参考」（与 alpha.6 以前一致）。
 */
class PlayoutAligner(capacity: Int, private val sr: Int = 48000,
                     private val nudgeMs: Int = 3, private val resyncMs: Int = 50, private val maxZeroMs: Int = 250,
                     private val fastSteps: Int = 3) {
    private val buf = ShortArray(capacity)
    /** 已写入的样本总数（下一个写入样本的绝对下标） */
    var written = 0L; private set
    private var feed = Long.MIN_VALUE
    /** 还要先输出的静音样本数（参考超前时补的，期间 [feed] 不前进） */
    private var zeroPending = 0L
    private var errS = 0.0
    /** 最近几帧的偏差（[fastSteps] > 0 时用）：连续 [fastSteps] 帧同向且都超过门限 → 一次挪到位（时间戳外推平滑，台阶就是真的欠载/跳变） */
    private val lastD = DoubleArray(max(1, fastSteps))
    private var nD = 0
    var nudges = 0; private set
    var resyncs = 0; private set
    /** 因参考超前补的静音样本数 */
    var zeroed = 0L; private set
    /** 取参考时落在「还没写」或「已被覆盖」范围、补零的样本数 */
    var missing = 0L; private set
    /** 最近一帧：参考起点相对已写入末尾的距离（样本）= 写入到出声的估计时长 */
    var lastQueue = 0L; private set

    fun reset() { written = 0; feed = Long.MIN_VALUE; errS = 0.0; zeroPending = 0; nD = 0 }
    fun clearCounters() { nudges = 0; resyncs = 0; missing = 0; zeroed = 0 }

    fun write(src: ShortArray, off: Int, n: Int) {
        for (i in 0 until n) { buf[(written % buf.size).toInt()] = src[off + i]; written++ }
    }

    fun writeZeros(n: Int) {
        for (i in 0 until n) { buf[(written % buf.size).toInt()] = 0; written++ }
    }

    /**
     * 取 [hop] 个参考样本到 [dst]；[playedFrames] = 这帧麦克风录音时已从喇叭出声的帧数（这个 AudioTrack 的帧号），未知传 -1。
     * [smooth] = 出声位置来自时间戳外推（平滑）：偏差连续 [fastSteps] 帧同向超门限就一次挪到位；
     * false（播放头，一跳 20~40 ms）只用指数平滑，免得追着跳动走
     */
    fun read(dst: ShortArray, hop: Int, playedFrames: Long, smooth: Boolean = false) {
        val p = if (playedFrames in 0..written) playedFrames else written
        val ideal = p - hop
        if (feed == Long.MIN_VALUE) { feed = ideal; errS = 0.0; zeroPending = 0 }
        else {
            // 有效读位置 = feed − 还没输出的静音
            val d = (ideal - (feed - zeroPending)).toDouble()
            if (abs(d) > sr * resyncMs / 1000.0) {
                if (d < 0 && -d <= sr * maxZeroMs / 1000.0) zeroPending += (-d).toLong() else { feed = ideal; zeroPending = 0 }
                errS = 0.0; resyncs++; nD = 0
            } else {
                errS = 0.95 * errS + 0.05 * d
                val th = sr * nudgeMs / 1000.0
                if (fastSteps > 0 && smooth) {
                    lastD[nD % fastSteps] = d; nD++
                    if (nD >= fastSteps) {
                        // 只看最近 fastSteps 个
                        var mn = Double.MAX_VALUE; var pos = 0; var neg = 0
                        for (v in lastD) { if (v >= th) pos++ else if (v <= -th) neg++; mn = min(mn, abs(v)) }
                        if (pos == fastSteps || neg == fastSteps) errS = if (pos > 0) mn else -mn
                    }
                }
                if (abs(errS) >= th) {
                    val s = errS.roundToLong()
                    if (s > 0) { val z = min(s, zeroPending); zeroPending -= z; feed += s - z } else zeroPending += -s
                    errS -= s; nudges++; nD = 0
                }
            }
        }
        val lo = written - buf.size
        val z = min(zeroPending, hop.toLong()).toInt()
        for (i in 0 until z) dst[i] = 0
        zeroPending -= z; zeroed += z
        for (i in z until hop) {
            val k = feed + (i - z)
            dst[i] = if (k < 0 || k >= written || k < lo) { if (k >= 0) missing++; 0 } else buf[(k % buf.size).toInt()]
        }
        lastQueue = written - feed - (hop - z) + zeroPending
        feed += hop - z
    }
}

/**
 * 麦克风录音时刻估计：liveLoop 读麦克风是一阵一阵的（HAL 按周期交数据、偶尔卡几十 ms 后连读几帧），
 * 「处理这帧的时刻」比「这帧录下来的时刻」晚得不规则。第 n 帧的录音时刻 ≈ t0 + n·10 ms，
 * t0 取最近 [winFrames] 帧里 (处理时刻 − n·10 ms) 的最小值（处理只会晚不会早），这样参考按录音时刻取、不随读取抖动。
 * 丢帧/重开录音后 1 s 内（窗口滑过）自动跟上。
 */
class CaptureClock(private val hopNs: Long = 10_000_000L, private val winFrames: Int = 100) {
    private var n = 0L
    private val qv = LongArray(winFrames + 1)
    private val qi = LongArray(winFrames + 1)
    private var qh = 0; private var qt = 0; private var qs = 0
    fun reset() { n = 0; qh = 0; qt = 0; qs = 0 }

    /** 每处理一帧调一次，[nowNs] = 当前单调时钟；返回这帧（结束时刻）的估计录音时刻 */
    fun frame(nowNs: Long): Long {
        val v = nowNs - n * hopNs
        // 单调队列求窗口最小值
        while (qs > 0 && qv[(qt - 1 + qv.size) % qv.size] >= v) { qt = (qt - 1 + qv.size) % qv.size; qs-- }
        qv[qt] = v; qi[qt] = n; qt = (qt + 1) % qv.size; qs++
        while (qi[qh] <= n - winFrames) { qh = (qh + 1) % qv.size; qs-- }
        val t = qv[qh] + n * hopNs
        n++
        return t
    }
}

/**
 * AudioTrack.getTimestamp() 外推：时间戳 (framePosition, nanoTime) 表示那一刻喇叭正好放出第 framePosition 帧，
 * 往后按采样率线性外推到 [atNs]。[written] 为上限（不可能放出还没写的）。时间戳无效/太旧（> [maxAgeMs]）返回 -1。
 */
object TrackClock {
    fun framesAt(tsFrame: Long, tsNs: Long, atNs: Long, sr: Int, written: Long, maxAgeMs: Int = 1000): Long {
        if (tsFrame < 0 || tsNs <= 0) return -1
        val age = atNs - tsNs
        if (age > maxAgeMs * 1_000_000L) return -1
        val f = tsFrame + Math.floorDiv(age * sr, 1_000_000_000L)
        return f.coerceIn(0L, written)
    }
}

/**
 * 耳返排队稳定器（纯逻辑）。每次往耳返写一帧前调用 [plan]：
 * - 新建的 AudioTrack 第一次写、或刚发生过欠载且排队已低于目标一半：先补静音到 [targetMs]；
 * - 每秒统计一次排队最小值，若整秒都比目标多出 [excessMs] 以上，接下来几帧少写（丢最旧的几毫秒），把排队拉回目标。
 * 返回值写进 [prefill]（要先写的静音样本数）和 [skip]（这一帧开头要丢掉的样本数）。
 */
class MonQueue(private val sr: Int = 48000, val targetMs: Int = 40, private val excessMs: Int = 25) {
    var prefill = 0; private set
    var skip = 0; private set
    private var started = false
    private var winMin = Long.MAX_VALUE
    private var winN = 0
    private var pendingSkip = 0L
    /** 统计（调用方读后自行清零） */
    var prefills = 0
    var prefillSamples = 0L
    var trimmedSamples = 0L
    var qSum = 0L; var qN = 0; var qMin = Long.MAX_VALUE; var qMax = 0L

    fun reset() { started = false; winMin = Long.MAX_VALUE; winN = 0; pendingSkip = 0 }
    fun clearStats() { prefills = 0; prefillSamples = 0; trimmedSamples = 0; qSum = 0; qN = 0; qMin = Long.MAX_VALUE; qMax = 0 }

    /** [queued] = 写之前耳返里还没播的样本数（已写 − 播放头，未知传 -1）；[underran] = 上次写之后欠载计数是否增加；[n] = 这一帧样本数 */
    fun plan(queued: Long, underran: Boolean, n: Int, hopsPerSec: Int = 100) {
        prefill = 0; skip = 0
        val target = sr.toLong() * targetMs / 1000
        if (queued < 0) { if (!started) started = true; return }
        qSum += queued; qN++; qMin = min(qMin, queued); qMax = max(qMax, queued)
        if (!started) {
            started = true
            prefill = max(0L, target - queued).toInt()
        } else if (underran && queued < target / 2) {
            prefill = (target - queued).toInt()
        }
        if (prefill > 0) { prefills++; prefillSamples += prefill; winMin = Long.MAX_VALUE; winN = 0; pendingSkip = 0; return }
        winMin = min(winMin, queued); winN++
        if (winN >= hopsPerSec) {
            val ex = winMin - target
            if (ex > sr.toLong() * excessMs / 1000) pendingSkip = ex
            winMin = Long.MAX_VALUE; winN = 0
        }
        if (pendingSkip > 0) {
            // 每帧最多丢 1/4 帧，分几帧丢完，避免一下子跳一大段
            val s = min(pendingSkip, (n / 4).toLong()).toInt()
            skip = s; pendingSkip -= s; trimmedSamples += s
        }
    }
}

/**
 * 兜底压低（纯逻辑，alpha.7 之后改温和）：只在 [allowed]（AEC 开 + 外放）时工作。
 * - 触发（与 alpha.7 相同）：参考有声时 AEC3 的 ERLE 持续 < [trigErleDb] 累计 [trigMs]；
 *   解除：参考有声时 ERLE ≥ [relErleDb] 累计 [relMs]，或不再 allowed。
 * - 触发后不再「参考一响就压 15~25 dB」，而是逐帧判断这一帧是不是回声主导，只有回声主导才压，最多压 [maxDuckDb]（默认 8 dB）：
 *   1) 电平先在功率域做 [smoothFrames] 帧滑动平均（压掉逐帧起伏）；
 *   2) 回声延迟：麦克风电平与「延迟 L 帧的参考电平」的相关（L = 0~[maxLagFrames]，约 2 s 平滑），取相关最高的 L；
 *   3) 耦合量 = 麦克风 − 延迟 L 的参考 的 [couplingQ] 分位（分位跟踪，近端说话只会让差值变大，取偏低的分位）；
 *      预期回声 = max(延迟 L 的参考, L±2 帧参考最大值 − 3 dB) + 耦合量（容忍 10~20 ms 的对齐抖动）；
 *   4) 近端说话 = 麦克风 > 预期回声 + [nearMarginDb] → 立即放开，之后 [nearHoldMs] 内不压（保住尾音），计入 [nearCount]；
 *   5) 回声主导 = 延迟参考有声 且 相关 ≥ [minCorr]（麦克风确实跟着参考走）且 不是近端；
 *   6) 压下去慢（每帧 [attackDb]）、回声主导结束后保持 [holdMs] 再按每帧 [releaseDb] 放开。
 * [step] 每 10 ms 调一次，返回这一帧的目标增益（dB，≤ 0），调用方在帧内从上一帧增益线性过渡。
 */
class AecDuck(
    private val hopMs: Int = 10,
    private val activeDb: Float = -50f,
    private val trigErleDb: Float = 3f,
    private val relErleDb: Float = 6f,
    private val trigMs: Int = 2000,
    private val relMs: Int = 1500,
    val maxDuckDb: Float = 8f,
    private val nearMarginDb: Float = 5f,
    private val nearHoldMs: Int = 400,
    private val holdMs: Int = 60,
    private val minCorr: Float = 0.4f,
    val maxLagFrames: Int = 50,
    private val attackDb: Float = 2f,
    private val releaseDb: Float = 2f,
    private val smoothFrames: Int = 3,
    private val couplingQ: Float = 0.3f,
) {
    var engaged = false; private set
    var gainDb = 0f; private set
    private var lowMs = 0
    private var okMs = 0
    private var holdLeft = 0
    private var nearLeft = 0
    private var nearPrev = false
    // 平滑用的原始功率
    private val rawR = DoubleArray(smoothFrames)
    private val rawM = DoubleArray(smoothFrames)
    private var rp = 0
    // 平滑后电平历史（dB，下限 FLOOR）与相关统计
    private val refHist = FloatArray(maxLagFrames + 4)
    private var hp = 0
    private var mMean = 0.0; private var mVar = 1.0
    private var rMean = 0.0; private var rVar = 1.0
    private val cxy = DoubleArray(maxLagFrames + 1)
    private var nStat = 0
    /** 当前估计：回声延迟（帧）、相关、耦合（麦克风 − 参考，dB；NaN = 还没估出来） */
    var lag = 0; private set
    var corr = 0f; private set
    var couplingDb = Float.NaN; private set
    /** 这一帧 麦克风 − 延迟参考（dB；延迟参考无声时 NaN），单测/诊断用 */
    var lastDiff = Float.NaN; private set
    /** 这一帧是否判为近端说话 / 回声主导（诊断用） */
    var lastNear = false; private set
    var lastEchoDom = false; private set
    /** 统计（调用方读后清零） */
    var engages = 0
    var duckFrames = 0
    var duckDbSum = 0.0
    var maxDuck = 0f
    /** 触发期间判为近端说话的次数（每次从「不是近端」变成「是近端」计一次） */
    var nearCount = 0
    /** 触发期间延迟参考有声、但判为近端/相关不足而没压的帧数 */
    var spareFrames = 0

    fun clearStats() { engages = 0; duckFrames = 0; duckDbSum = 0.0; maxDuck = 0f; nearCount = 0; spareFrames = 0 }
    fun reset() {
        engaged = false; gainDb = 0f; lowMs = 0; okMs = 0; holdLeft = 0; nearLeft = 0; nearPrev = false
        java.util.Arrays.fill(rawR, 0.0); java.util.Arrays.fill(rawM, 0.0); rp = 0
        java.util.Arrays.fill(refHist, FLOOR); hp = 0; mMean = 0.0; mVar = 1.0; rMean = 0.0; rVar = 1.0
        java.util.Arrays.fill(cxy, 0.0); nStat = 0; lag = 0; corr = 0f; couplingDb = Float.NaN; lastDiff = Float.NaN
        lastNear = false; lastEchoDom = false
    }
    init { reset() }

    private fun refAt(l: Int): Float = refHist[((hp - 1 - l) % refHist.size + refHist.size) % refHist.size]
    private fun toDb(p: Double) = max(FLOOR, (10 * log10(p + 1e-12)).toFloat())

    /**
     * [refDb] = 这一帧送给 AEC 的参考电平，[micDb] = 这一帧原始麦克风电平（AEC 前），[erleDb] = AEC3 当前 ERLE。
     * 返回 true = 这一步触发/解除状态变了，调用方记日志
     */
    fun step(refDb: Float, micDb: Float, erleDb: Float, allowed: Boolean): Boolean {
        var changed = false
        rawR[rp] = 10.0.pow(max(refDb, FLOOR) / 10.0); rawM[rp] = 10.0.pow(max(micDb, FLOOR) / 10.0); rp = (rp + 1) % smoothFrames
        val r = toDb(rawR.average()); val m = toDb(rawM.average())
        refHist[hp] = r; hp = (hp + 1) % refHist.size
        // ---- 相关（只在参考或麦克风有声时更新，约 2 s 平滑）
        if (r > activeDb || m > activeDb) {
            val a = if (nStat < 200) 1.0 / (nStat + 1) else 0.005
            nStat++
            mMean += a * (m - mMean); mVar += a * ((m - mMean) * (m - mMean) - mVar)
            // 参考的均值/方差用当前帧（各延迟的统计量近似相同）
            rMean += a * (r - rMean); rVar += a * ((r - rMean) * (r - rMean) - rVar)
            for (l in 0..maxLagFrames) cxy[l] += a * ((m - mMean) * (refAt(l) - rMean) - cxy[l])
            var best = 0; var bv = -2.0
            val den = sqrt(max(1e-6, rVar * mVar))
            for (l in 0..maxLagFrames) { val c = cxy[l] / den; if (c > bv) { bv = c; best = l } }
            lag = best; corr = bv.toFloat()
        }
        // 延迟参考：耦合按估计的延迟 L 算；预期回声取 L±2 帧的最大值（上界，容忍 20 ms 级的对齐抖动，近端判定只会更保守）
        val r0 = refAt(lag)
        var rl = FLOOR
        for (l in max(0, lag - 2)..min(maxLagFrames + 2, lag + 2)) rl = max(rl, refAt(l))
        val refActive = rl >= activeDb
        lastDiff = if (r0 >= activeDb) m - r0 else Float.NaN
        // ---- 耦合：差值的 couplingQ 分位（参考明显有声时才更新，每帧 ±0.5 dB 量级）
        if (r0 >= activeDb + 10f) {
            val d = m - r0
            couplingDb = if (couplingDb.isNaN()) d else if (d < couplingDb) couplingDb - 1f * (1 - couplingQ) else couplingDb + 1f * couplingQ
        }
        // ---- 触发 / 解除（ERLE，按延迟参考是否有声）
        if (!allowed) {
            if (engaged) { engaged = false; changed = true }
            lowMs = 0; okMs = 0
        } else if (refActive) {
            if (!engaged) {
                lowMs = if (erleDb < trigErleDb) lowMs + hopMs else 0
                if (lowMs >= trigMs) { engaged = true; engages++; okMs = 0; changed = true }
            } else {
                okMs = if (erleDb >= relErleDb) okMs + hopMs else 0
                if (okMs >= relMs) { engaged = false; lowMs = 0; changed = true }
            }
        }
        // ---- 近端 / 回声主导
        val expected = if (couplingDb.isNaN()) Float.NaN else max(r0, rl - 3f) + couplingDb
        // 原始（未平滑）麦克风也参与近端判定：说话起头更快放开
        val near = refActive && !expected.isNaN() && max(m, micDb) > expected + nearMarginDb
        if (near) { nearLeft = nearHoldMs; if (engaged && !nearPrev) nearCount++ } else if (nearLeft > 0) nearLeft -= hopMs
        nearPrev = near
        val echoDom = engaged && refActive && !expected.isNaN() && corr >= minCorr && !near && nearLeft <= 0
        lastNear = near; lastEchoDom = echoDom
        if (echoDom) holdLeft = holdMs else if (holdLeft > 0) holdLeft -= hopMs
        if (engaged && refActive && !echoDom) spareFrames++
        val target = if (engaged && nearLeft <= 0 && (echoDom || holdLeft > 0)) -maxDuckDb else 0f
        gainDb = when {
            nearLeft > 0 -> 0f                                   // 近端说话：立即放开
            target < gainDb -> max(target, gainDb - attackDb)
            else -> min(target, gainDb + releaseDb)
        }
        if (gainDb < -0.5f) { duckFrames++; duckDbSum += -gainDb; maxDuck = max(maxDuck, -gainDb) }
        return changed
    }

    companion object {
        const val FLOOR = -90f
        fun levelDb(x: ShortArray, n: Int): Float {
            var e = 0.0
            for (i in 0 until n) e += x[i].toDouble() * x[i]
            return (10 * log10(e / max(1, n) / (32768.0 * 32768.0) + 1e-12)).toFloat()
        }
    }
}
