package com.taocrypt.micmask

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 声纹门限的前处理（纯 Kotlin，不依赖 Android，可在 JVM 上单独编译测试）。
 *
 * fbank 与 3D-Speaker 训练时一致：torchaudio.compliance.kaldi.fbank 默认参数 —
 * 16 kHz、80 维、帧长 25 ms / 帧移 10 ms、snip_edges、去直流、预加重 0.97、povey 窗、
 * FFT 512（功率谱）、Kaldi mel 刻度 20 Hz–8 kHz、log(max(e, ε))、dither 0；
 * 之后按时间减均值（CMN，即 ONNX 元数据里的 feature_normalize_type=global-mean）。
 * 输入是 [-1, 1] 的浮点样本（元数据 normalize_samples=1，不乘 32768）。
 * 与 kaldi-native-fbank 对拍最大误差约 2e-4（float 精度差异）。
 */
object SpeakerFbank {
    const val SR = 16000
    const val FRAME = 400
    const val SHIFT = 160
    const val NFFT = 512
    const val MELS = 80
    private const val NB = NFFT / 2          // 256 个有效频点（第 257 个 Nyquist 点权重为 0）
    private const val EPS = 1.1920928955078125e-07   // torch.finfo(float32).eps

    private val window = DoubleArray(FRAME) { i -> (0.5 - 0.5 * cos(2 * PI * i / (FRAME - 1))).pow(0.85) }

    /** mel 三角滤波器：每个 mel 通道只存非零区间 [start, start+w.size) */
    private val bankStart = IntArray(MELS)
    private val bankW: Array<DoubleArray>

    // 512 点 FFT 的旋转因子与位反转表（只读，线程安全）
    private val twCos = DoubleArray(NFFT / 2) { cos(-2 * PI * it / NFFT) }
    private val twSin = DoubleArray(NFFT / 2) { sin(-2 * PI * it / NFFT) }
    private val rev = IntArray(NFFT).also { r ->
        val bits = 9
        for (i in 0 until NFFT) { var x = i; var y = 0; repeat(bits) { y = (y shl 1) or (x and 1); x = x shr 1 }; r[i] = y }
    }

    init {
        fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        val binW = SR.toDouble() / NFFT
        val lo = mel(20.0); val hi = mel(SR / 2.0)
        val d = (hi - lo) / (MELS + 1)
        val m = DoubleArray(NB) { mel(binW * it) }
        bankW = Array(MELS) { b ->
            val l = lo + b * d; val c = lo + (b + 1) * d; val r = lo + (b + 2) * d
            val w = DoubleArray(NB) { k -> max(0.0, min((m[k] - l) / (c - l), (r - m[k]) / (r - c))) }
            var s = 0; while (s < NB && w[s] == 0.0) s++
            var e = NB; while (e > s && w[e - 1] == 0.0) e--
            bankStart[b] = s
            w.copyOfRange(s, e)
        }
    }

    /** 样本数 n 能切出的帧数（snip_edges） */
    fun frames(n: Int): Int = if (n < FRAME) 0 else 1 + (n - FRAME) / SHIFT

    /**
     * 算 [x]（16 kHz，[-1,1]）的 80 维 log-mel fbank，按时间减均值。
     * 返回行优先 [T × 80] 的 FloatArray，T = [frames]；不够一帧返回空数组。
     */
    fun compute(x: FloatArray): FloatArray {
        val t = frames(x.size)
        if (t == 0) return FloatArray(0)
        val out = FloatArray(t * MELS)
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT); val pw = DoubleArray(NB)
        val fr = DoubleArray(FRAME)
        val mean = DoubleArray(MELS)
        for (f in 0 until t) {
            val o = f * SHIFT
            var dc = 0.0
            for (i in 0 until FRAME) { fr[i] = x[o + i].toDouble(); dc += fr[i] }
            dc /= FRAME
            for (i in 0 until FRAME) fr[i] -= dc
            // 预加重（torchaudio：第 0 个样本与自己做差）
            for (i in FRAME - 1 downTo 1) fr[i] -= 0.97 * fr[i - 1]
            fr[0] -= 0.97 * fr[0]
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until NFFT) re[i] = 0.0
            for (i in 0 until FRAME) re[rev[i]] = fr[i] * window[i]
            fftInPlace(re, im)
            for (k in 0 until NB) pw[k] = re[k] * re[k] + im[k] * im[k]
            for (b in 0 until MELS) {
                val w = bankW[b]; val s = bankStart[b]
                var e = 0.0
                for (j in w.indices) e += w[j] * pw[s + j]
                val v = ln(max(e, EPS))
                out[f * MELS + b] = v.toFloat(); mean[b] += v
            }
        }
        for (b in 0 until MELS) mean[b] /= t
        for (f in 0 until t) for (b in 0 until MELS) out[f * MELS + b] = (out[f * MELS + b] - mean[b]).toFloat()
        return out
    }

    /** 输入已按位反转放好的原位 FFT（长度 NFFT） */
    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        var len = 2
        while (len <= NFFT) {
            val h = len / 2; val step = NFFT / len
            var i = 0
            while (i < NFFT) {
                for (k in 0 until h) {
                    val wr = twCos[k * step]; val wi = twSin[k * step]
                    val a = i + k; val b = a + h
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ---------------------------------------------------------------- 48k → 16k

    /** 与 LiveRvc 同一设计的 Kaiser-sinc 抗混叠 FIR（97 抽头，截止 0.95×8 kHz），3:1 抽取 */
    private val dk: FloatArray = run {
        val half = 48; val fc = 1.0 / 3 * 0.95; val beta = 8.0
        fun i0(x: Double): Double { var s = 1.0; var t = 1.0; for (k in 1..40) { t *= (x / (2 * k)) * (x / (2 * k)); s += t }; return s }
        val k = DoubleArray(2 * half + 1) { j ->
            val n = (j - half).toDouble()
            val sinc = if (n == 0.0) 1.0 else sin(PI * fc * n) / (PI * fc * n)
            val r = n / half; fc * sinc * i0(beta * sqrt(max(0.0, 1 - r * r))) / i0(beta)
        }
        val sum = k.sum(); FloatArray(k.size) { (k[it] / sum).toFloat() }
    }

    fun decim3(x: FloatArray): FloatArray {
        val half = dk.size / 2; val n = x.size / 3; val y = FloatArray(n)
        for (i in 0 until n) {
            val c = i * 3; var acc = 0f
            val j0 = max(0, c - half); val j1 = min(x.size - 1, c + half)
            var j = j0; while (j <= j1) { acc += x[j] * dk[j - c + half]; j++ }
            y[i] = acc
        }
        return y
    }

    // ---------------------------------------------------------------- 向量 / 能量

    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0; for (a in v) s += a.toDouble() * a
        val n = sqrt(s); if (n < 1e-12) return v.copyOf()
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        val d = sqrt(aa * bb); return if (d < 1e-12) 0f else (ab / d).toFloat()
    }

    /** [voicedOnly] 的结果：只含有声段的样本、有声时长（ms）、有声段平均电平（dBFS，没有有声段为 -120） */
    class Voiced(val x: FloatArray, val speechMs: Int, val levelDb: Float)

    /**
     * 截掉静音（2.1.1-alpha.6，声纹门限）：按 [segMs] 分段，与 [speechMs] 同一判据（≥ max([floorDb], 最响段 - [relDb])）挑出有声段，
     * 按时间顺序拼接，只保留最后 [maxMs] 毫秒（0 = 不限）。段与段接缝处做 1 ms 线性淡入淡出，避免拼接咔嗒声影响 fbank。
     * 录入与实时打分都用它，两边的嵌入都只看「有声」部分，有声时长要求一致。
     */
    fun voicedOnly(x: FloatArray, sr: Int, floorDb: Float, relDb: Float = 30f, segMs: Int = 20, maxMs: Int = 0): Voiced {
        val seg = sr * segMs / 1000
        if (seg <= 0 || x.size < seg) return Voiced(FloatArray(0), 0, -120f)
        val n = x.size / seg
        val db = FloatArray(n); val en = DoubleArray(n)
        var top = -200f
        for (s in 0 until n) {
            var e = 0.0; val o = s * seg
            for (i in o until o + seg) e += x[i].toDouble() * x[i]
            en[s] = e / seg
            db[s] = (10 * log10(en[s] + 1e-12)).toFloat(); if (db[s] > top) top = db[s]
        }
        val th = max(floorDb, top - relDb)
        val keep = (0 until n).filter { db[it] >= th }
        if (keep.isEmpty()) return Voiced(FloatArray(0), 0, -120f)
        val maxSeg = if (maxMs > 0) max(1, maxMs / segMs) else keep.size
        val use = if (keep.size > maxSeg) keep.subList(keep.size - maxSeg, keep.size) else keep
        val y = FloatArray(use.size * seg)
        val fade = min(seg / 2, max(1, sr / 1000))
        var eSum = 0.0
        for ((k, s) in use.withIndex()) {
            System.arraycopy(x, s * seg, y, k * seg, seg)
            eSum += en[s]
            // 接缝（与前一段不相邻）处淡入，前一段末尾淡出
            if (k > 0 && use[k - 1] != s - 1) {
                for (i in 0 until fade) {
                    val g = (i + 1).toFloat() / (fade + 1)
                    y[k * seg + i] *= g
                    y[k * seg - 1 - i] *= g
                }
            }
        }
        val lv = (10 * log10(eSum / use.size + 1e-12)).toFloat()
        return Voiced(y, use.size * segMs, lv)
    }

    /**
     * 有声时长（ms）：按 [segMs] 分段算能量，dBFS 不低于 max([floorDb], 窗内最响段 - [relDb]) 的段计入。
     * 用来判断窗口里人声够不够算声纹（开口第一下窗口里大半是静音，相似度会偏低）。
     */
    fun speechMs(x: FloatArray, sr: Int, floorDb: Float, relDb: Float = 30f, segMs: Int = 20): Int {
        val seg = sr * segMs / 1000
        if (seg <= 0 || x.size < seg) return 0
        val n = x.size / seg
        val db = FloatArray(n)
        var top = -200f
        for (s in 0 until n) {
            var e = 0.0; val o = s * seg
            for (i in o until o + seg) e += x[i].toDouble() * x[i]
            db[s] = (10 * log10(e / seg + 1e-12)).toFloat(); if (db[s] > top) top = db[s]
        }
        val th = max(floorDb, top - relDb)
        return db.count { it >= th } * segMs
    }
}
