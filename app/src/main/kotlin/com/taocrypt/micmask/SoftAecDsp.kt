package com.taocrypt.micmask

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
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
 *  - [PlayoutAligner]：按耳返 AudioTrack 实际播放位置（playbackHeadPosition）把参考对齐到「刚播出去的那 10 ms」。
 *  - [MonQueue]：耳返排队稳定器（启动/欠载后预填静音到目标排队，长期积压过多时少写一点）。
 *  - [AecDuck]：兜底的半双工压低（AEC 持续无效时按参考电平压低麦克风）。
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
 * 按耳返实际播放位置对齐 AEC 参考。
 * - [write]：每次写进耳返 AudioTrack 的样本（含预填的静音）按写入顺序记下，绝对下标从 0 起 = 这个 AudioTrack 的帧号。
 * - [read]：每处理一帧麦克风取一帧参考，取的是「播放头之前的最后 hop 个样本」= 刚播出去的声音；
 *   播放头的跳动（HAL 按周期推进）用指数平滑吸收，偏差超过 [nudgeMs] 才挪一次（计入 [nudges]），
 *   超过 [resyncMs]（如欠载时播放头停住）直接重同步（计入 [resyncs]）。
 * - playedFrames < 0（取不到播放头）时退回「刚写进去的就当参考」（与 alpha.6 以前一致）。
 * 这样 AEC3 看到的残余延迟只剩播放头之后的硬件/HAL 延迟 + 录音延迟，且不随排队长短抖动。
 */
class PlayoutAligner(capacity: Int, private val sr: Int = 48000,
                     private val nudgeMs: Int = 3, private val resyncMs: Int = 50) {
    private val buf = ShortArray(capacity)
    /** 已写入的样本总数（下一个写入样本的绝对下标） */
    var written = 0L; private set
    private var feed = Long.MIN_VALUE
    private var errS = 0.0
    var nudges = 0; private set
    var resyncs = 0; private set
    /** 取参考时落在「还没写」或「已被覆盖」范围、补零的样本数 */
    var missing = 0L; private set
    /** 最近一帧：参考起点相对已写入末尾的距离（样本）= 估计排队时长 */
    var lastQueue = 0L; private set

    fun reset() { written = 0; feed = Long.MIN_VALUE; errS = 0.0 }
    fun clearCounters() { nudges = 0; resyncs = 0; missing = 0 }

    fun write(src: ShortArray, off: Int, n: Int) {
        for (i in 0 until n) { buf[(written % buf.size).toInt()] = src[off + i]; written++ }
    }

    fun writeZeros(n: Int) {
        for (i in 0 until n) { buf[(written % buf.size).toInt()] = 0; written++ }
    }

    /** 取 [hop] 个参考样本到 [dst]；[playedFrames] = 播放头（这个 AudioTrack 已播出的帧数），未知传 -1 */
    fun read(dst: ShortArray, hop: Int, playedFrames: Long) {
        val p = if (playedFrames in 0..written) playedFrames else written
        val ideal = p - hop
        if (feed == Long.MIN_VALUE) { feed = ideal; errS = 0.0 }
        else {
            val d = (ideal - feed).toDouble()
            if (abs(d) > sr * resyncMs / 1000.0) { feed = ideal; errS = 0.0; resyncs++ }
            else {
                errS = 0.95 * errS + 0.05 * d
                if (abs(errS) >= sr * nudgeMs / 1000.0) { val s = errS.roundToLong(); feed += s; errS -= s; nudges++ }
            }
        }
        val lo = written - buf.size
        for (i in 0 until hop) {
            val k = feed + i
            dst[i] = if (k < 0 || k >= written || k < lo) { if (k >= 0) missing++; 0 } else buf[(k % buf.size).toInt()]
        }
        lastQueue = written - feed - hop
        feed += hop
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
 * 兜底半双工压低（纯逻辑）：只在 [allowed]（AEC 开 + 外放）时工作。
 * - 触发：参考有声（≥ [activeDb]）时，AEC3 的 ERLE 持续 < [trigErleDb] 累计 [trigMs]；
 * - 解除：参考有声时 ERLE ≥ [relErleDb] 累计 [relMs]，或不再 allowed；
 * - 触发中：参考包络（带 [holdMs] 保持）越响压得越狠，−45 dBFS 起压 [minDuckDb]，−25 dBFS 及以上压 [maxDuckDb]；
 *   压下去快（每帧最多 8 dB），放开慢（保持后每帧 +1 dB，约 0.25 s 放完）；参考静音时不压。
 * [step] 每 10 ms 调一次，返回这一帧的目标增益（dB，≤ 0），调用方在帧内从上一帧增益线性过渡。
 */
class AecDuck(
    private val hopMs: Int = 10,
    private val activeDb: Float = -50f,
    private val trigErleDb: Float = 3f,
    private val relErleDb: Float = 6f,
    private val trigMs: Int = 2000,
    private val relMs: Int = 1500,
    private val holdMs: Int = 150,
    private val minDuckDb: Float = 15f,
    private val maxDuckDb: Float = 25f,
) {
    var engaged = false; private set
    var gainDb = 0f; private set
    private var lowMs = 0
    private var okMs = 0
    private var envDb = -120f
    private var holdLeft = 0
    /** 统计（调用方读后清零） */
    var engages = 0
    var duckFrames = 0
    var duckDbSum = 0.0
    var maxDuck = 0f

    fun clearStats() { engages = 0; duckFrames = 0; duckDbSum = 0.0; maxDuck = 0f }
    fun reset() { engaged = false; gainDb = 0f; lowMs = 0; okMs = 0; envDb = -120f; holdLeft = 0 }

    /** 返回 true = 这一步状态变了（触发/解除），调用方记日志 */
    fun step(refDb: Float, erleDb: Float, allowed: Boolean): Boolean {
        var changed = false
        val active = refDb >= activeDb
        if (!allowed) {
            if (engaged) { engaged = false; changed = true }
            lowMs = 0; okMs = 0
        } else if (active) {
            if (!engaged) {
                lowMs = if (erleDb < trigErleDb) lowMs + hopMs else 0
                if (lowMs >= trigMs) { engaged = true; engages++; okMs = 0; changed = true }
            } else {
                okMs = if (erleDb >= relErleDb) okMs + hopMs else 0
                if (okMs >= relMs) { engaged = false; lowMs = 0; changed = true }
            }
        }
        // 参考包络：升得快，降之前保持 holdMs
        if (refDb >= envDb) { envDb = refDb; holdLeft = holdMs }
        else if (holdLeft > 0) holdLeft -= hopMs
        else envDb = max(refDb, envDb - 2f)
        val target = if (engaged && envDb >= activeDb + 5f) {
            val t = ((envDb - (-45f)) / 20f).coerceIn(0f, 1f)
            -(minDuckDb + (maxDuckDb - minDuckDb) * t)
        } else 0f
        gainDb = if (target < gainDb) max(target, gainDb - 8f) else min(target, gainDb + 1f)
        if (gainDb < -0.5f) { duckFrames++; duckDbSum += -gainDb; maxDuck = max(maxDuck, -gainDb) }
        return changed
    }

    companion object {
        fun levelDb(x: ShortArray, n: Int): Float {
            var e = 0.0
            for (i in 0 until n) e += x[i].toDouble() * x[i]
            return (10 * log10(e / max(1, n) / (32768.0 * 32768.0) + 1e-12)).toFloat()
        }
    }
}
