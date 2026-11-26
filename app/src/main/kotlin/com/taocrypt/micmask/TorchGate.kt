package com.taocrypt.micmask

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 谱门限降噪，参考 TorchGate（MIT，RVC realtime_gui 的输入/输出降噪）的「平稳 + 噪声样本」模式：
 * STFT → 每个频点的噪声 dB 均值 + nStd（2.5，合成谐波+白噪声测试：底噪 -17 dB、人声段 SNR 17.4→12.2）倍标准差作门限 → 高于门限的保留 → 掩码按 prop 衰减
 * → 频率、时间三角核平滑 → 乘回频谱 → iSTFT。
 * 原版不给噪声样本时用「这一段自己」的均值和标准差：人声占这一段四成以上时门限会高过人声，把人声一起压掉
 * （合成谐波测试 SNR 15→3 dB）。这里改成跨调用持续跟踪的噪声谱：低于估计时快跟、高于时慢涨（约 5 s），方差同样跟踪。
 * 与原版的差别：n_fft 用 2048（原版 RVC 实时用 4×480=1920，这里取 2 的幂省去 Bluestein），hop 512；
 * 平滑核可分离，按行列两次一维卷积做。
 * 非线程安全：每条处理线程各用一个实例。
 */
class TorchGate(
    private val sr: Int = 48000,
    private val nFft: Int = 2048,
    private val prop: Float = 0.9f,
    private val nStd: Float = 2.5f,
    freqSmoothHz: Float = 100f,
    timeSmoothMs: Float = 50f,
) {
    private val hop = nFft / 4
    private val nBin = nFft / 2 + 1
    private val win = FloatArray(nFft) { (0.5 - 0.5 * cos(2 * PI * it / nFft)).toFloat() }   // 周期 Hann，同 torch.hann_window
    private val kF = tri((freqSmoothHz / (sr / (nFft / 2f))).toInt())
    private val kT = tri((timeSmoothMs / (hop * 1000f / sr)).toInt())
    private val cosT = DoubleArray(nFft / 2) { cos(-2 * PI * it / nFft) }
    private val sinT = DoubleArray(nFft / 2) { sin(-2 * PI * it / nFft) }
    /** 噪声谱：每个频点的 dB 均值与均方（持续跟踪）；null = 还没有估计 */
    private var nMean: FloatArray? = null
    private var nVar = FloatArray(nBin) { 30f }
    private val rev = IntArray(nFft).also { r ->
        val bits = Integer.numberOfTrailingZeros(nFft)
        for (i in 0 until nFft) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** torch: cat(linspace(0,1,n+1), linspace(1,0,n+2))[1:-1]，归一化前的一维三角核（外积再整体归一化 = 两个一维核各自归一化） */
    private fun tri(n: Int): FloatArray {
        if (n < 1) return floatArrayOf(1f)
        val a = FloatArray(n + 1) { it.toFloat() / n } + FloatArray(n + 2) { 1f - it.toFloat() / (n + 1) }
        val k = a.copyOfRange(1, a.size - 1)
        val s = k.sum(); for (i in k.indices) k[i] /= s
        return k
    }

    private fun fft(re: DoubleArray, im: DoubleArray, inv: Boolean) {
        val n = nFft
        for (i in 0 until n) { val j = rev[i]; if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var len = 2
        while (len <= n) {
            val half = len / 2; val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]; val wi = if (inv) -sinT[k * step] else sinT[k * step]
                    val a = i + k; val b = a + half
                    val xr = re[b] * wr - im[b] * wi; val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun reset() { nMean = null; nVar = FloatArray(nBin) { 30f } }

    /** 返回降噪后的新数组，长度与 x 相同。learnFrom：从这个样本位置起的帧才更新噪声谱（前面是上次已学过的上下文） */
    fun process(x: FloatArray, learnFrom: Int = 0): FloatArray {
        val n = x.size
        if (n < nFft / 2 + 1) return x.copyOf()
        val pad = nFft / 2
        // center=True，反射填充
        val xp = FloatArray(n + 2 * pad) { i ->
            val j = i - pad
            when { j < 0 -> x[-j]; j >= n -> x[2 * (n - 1) - j]; else -> x[j] }
        }
        val nFr = 1 + (xp.size - nFft) / hop
        val sRe = Array(nFr) { FloatArray(nBin) }; val sIm = Array(nFr) { FloatArray(nBin) }
        val db = Array(nFr) { FloatArray(nBin) }
        val re = DoubleArray(nFft); val im = DoubleArray(nFft)
        for (f in 0 until nFr) {
            val o = f * hop
            for (i in 0 until nFft) { re[i] = (xp[o + i] * win[i]).toDouble(); im[i] = 0.0 }
            fft(re, im, false)
            for (k in 0 until nBin) {
                sRe[f][k] = re[k].toFloat(); sIm[f][k] = im[k].toFloat()
                val a = sqrt(re[k] * re[k] + im[k] * im[k])
                val d = (20 * log10(a + 1.1920929e-7)).toFloat()
                db[f][k] = d
            }
        }
        // 噪声谱：首次用本段每个频点最低的 20% 帧初始化；之后逐帧（只学新帧）低于均值快跟、高于均值慢涨
        val mean = nMean ?: FloatArray(nBin) { k ->
            val v = FloatArray(nFr) { db[it][k] }; v.sort(); v[(nFr / 5).coerceAtMost(nFr - 1)]
        }.also { nMean = it }
        val f0 = ((learnFrom + pad - nFft / 2) / hop).coerceIn(0, nFr)
        val mask = Array(nFr) { FloatArray(nBin) }
        for (f in 0 until nFr) {
            for (k in 0 until nBin) {
                val d = db[f][k]
                val th = mean[k] + nStd * sqrt(nVar[k])
                mask[f][k] = prop * ((if (d > th) 1f else 0f) - 1f) + 1f
            }
            if (f >= f0) for (k in 0 until nBin) {
                val d = db[f][k]; val e = d - mean[k]
                val a = if (e < 0) 0.1f else 0.002f   // 帧长约 10.7 ms：下跟约 0.1 s，上涨约 5 s
                mean[k] += a * e
                if (e < 12f) nVar[k] += 0.02f * (e * e - nVar[k]).coerceIn(-50f, 50f)
            }
        }
        // 平滑：先沿频率，再沿时间。原版 conv2d 零填充会把段首尾和高低频边缘的掩码压低；流式按段处理时段首尾正好是块边界，改成边缘复制
        val tmp = FloatArray(nBin); val hf = kF.size / 2
        for (f in 0 until nFr) {
            val row = mask[f]
            for (k in 0 until nBin) { var a = 0f; for (j in kF.indices) { val kk = (k + j - hf).coerceIn(0, nBin - 1); a += row[kk] * kF[j] }; tmp[k] = a }
            System.arraycopy(tmp, 0, row, 0, nBin)
        }
        val ht = kT.size / 2; val col = FloatArray(nFr)
        for (k in 0 until nBin) {
            for (f in 0 until nFr) { var a = 0f; for (j in kT.indices) { val ff = (f + j - ht).coerceIn(0, nFr - 1); a += mask[ff][k] * kT[j] }; col[f] = a }
            for (f in 0 until nFr) mask[f][k] = col[f]
        }
        // iSTFT：加窗重叠相加，除以窗平方和
        val out = DoubleArray(xp.size); val wsum = DoubleArray(xp.size)
        for (f in 0 until nFr) {
            for (k in 0 until nBin) { val m = mask[f][k]; re[k] = (sRe[f][k] * m).toDouble(); im[k] = (sIm[f][k] * m).toDouble() }
            for (k in 1 until nFft / 2) { re[nFft - k] = re[k]; im[nFft - k] = -im[k] }
            im[0] = 0.0; im[nFft / 2] = 0.0
            fft(re, im, true)
            val o = f * hop
            for (i in 0 until nFft) { val w = win[i]; out[o + i] += re[i] / nFft * w; wsum[o + i] += (w * w).toDouble() }
        }
        return FloatArray(n) { i -> val j = i + pad; if (wsum[j] > 1e-8) (out[j] / wsum[j]).toFloat() else 0f }
    }
}
