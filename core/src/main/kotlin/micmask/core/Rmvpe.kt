package micmask.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * RMVPE 后处理，逐行对齐 infer/lib/rmvpe.py 的 RMVPE.decode / to_local_average_cents，
 * 以及 infer/modules/vc/pipeline.py 里的 f0 -> (pitch, pitchf) 变换。
 */
object Rmvpe {

    private const val N_CLASS = 360
    private const val CENTS_BASE = 1997.3794084376191
    private const val PAD = 4

    /** cents_mapping = 20*arange(360) + 1997.3794084376191，再 pad(4,4) 补零 -> 368 */
    private val centsPadded: DoubleArray = DoubleArray(N_CLASS + 2 * PAD) { j ->
        val i = j - PAD
        if (i < 0 || i >= N_CLASS) 0.0 else 20.0 * i + CENTS_BASE
    }

    /**
     * @param hidden (T, 360) 模型输出的 salience
     * @return f0 (T,) 单位 Hz，未发声帧为 0
     */
    fun decode(hidden: Array<FloatArray>, thred: Double = 0.03): FloatArray {
        val t = hidden.size
        val cents = DoubleArray(t)
        val maxx = DoubleArray(t)

        val tmp = FloatArray(N_CLASS + 2 * PAD)
        for (f in 0 until t) {
            val row = hidden[f]
            // argmax：numpy 取第一个最大值，故用严格大于
            var best = 0
            var bestV = row[0]
            for (i in 1 until N_CLASS) {
                if (row[i] > bestV) { bestV = row[i]; best = i }
            }
            maxx[f] = bestV.toDouble()

            // 零填充后 9 点窗口
            for (j in tmp.indices) {
                val i = j - PAD
                tmp[j] = if (i < 0 || i >= N_CLASS) 0f else row[i]
            }
            val c = best + PAD
            var psum = 0.0
            var wsum = 0.0
            for (j in (c - 4)..(c + 4)) {
                val s = tmp[j].toDouble()
                psum += s * centsPadded[j]
                wsum += s
            }
            cents[f] = if (wsum != 0.0) psum / wsum else 0.0
        }

        val f0 = FloatArray(t)
        for (f in 0 until t) {
            if (maxx[f] <= thred) { f0[f] = 0f; continue }
            val hz = 10.0 * 2.0.pow(cents[f] / 1200.0)
            f0[f] = if (hz == 10.0) 0f else hz.toFloat()
        }
        return f0
    }

    /**
     * f0(Hz) -> pitch(整数 mel 级) / pitchf(float f0)。
     * 对齐 infer/modules/vc/pipeline.py：
     *   lo, hi = 1127*ln(1+50/700), 1127*ln(1+1100/700)
     *   m = 1127*ln(1+f0/700); m[m>0] = (m[m>0]-lo)*254/(hi-lo)+1
     *   m[m<=1] = 1; m[m>255] = 255; pitch = rint(m)   // rint 是四舍六入五取偶
     *
     * @param pitchShift 半音数（f0_up_key），在转换前作用
     */
    fun f0ToPitch(f0: FloatArray, pitchShift: Int = 0): Pair<LongArray, FloatArray> {
        // 逐位对齐 numpy 的 float32 计算（RVC / voice-changer 的 f0 是 float32 数组）：
        // f*2^(k/12)、1+f/700、log、1127*… 都是 float32；减 lo 那步 numpy 升到 float64 再存回 float32。
        // jfk 1000 万帧随机测试与 numpy 2.5 结果不一致 1 帧（原 double 版约 7.5e-6）
        val n = f0.size
        val pitch = LongArray(n)
        val pitchf = FloatArray(n)
        val lo = 1127.0 * ln(1.0 + 50.0 / 700.0)
        val hi = 1127.0 * ln(1.0 + 1100.0 / 700.0)
        val scale = 2.0.pow(pitchShift / 12.0).toFloat()

        for (i in 0 until n) {
            val f = f0[i] * scale
            pitchf[i] = f
            val a = 1f + f / 700f
            var m = 1127f * ln(a.toDouble()).toFloat()
            if (m > 0f) m = ((m.toDouble() - lo) * 254.0 / (hi - lo) + 1.0).toFloat()
            if (m <= 1f) m = 1f
            if (m > 255f) m = 255f
            pitch[i] = Math.rint(m.toDouble()).toLong()
        }
        return pitch to pitchf
    }

    /**
     * RMVPE 输入：帧数补到 32 的倍数，镜像填充（对齐 voice-changer / Applio 的 F.pad(mode="reflect")）。
     * 原来补 0：log-mel 里 0 相当于很响的平坦谱，实时窗口尾部（正好是要用的帧）有声/无声误判约 20%，镜像后约 10%（jfk 实测）。
     * 返回按 [mel][帧] 展平的数组和补齐后的帧数。
     */
    fun padMel(mel: Array<FloatArray>): Pair<FloatArray, Int> {
        val nMels = mel.size
        val frames = mel[0].size
        val total = 32 * ((frames - 1) / 32 + 1)
        val flat = FloatArray(nMels * total)
        for (m in 0 until nMels) {
            val row = mel[m]; val o = m * total
            System.arraycopy(row, 0, flat, o, frames)
            for (j in frames until total) {
                var k = 2 * (frames - 1) - j
                if (k < 0) k = 0                           // 帧数太少（< 补的数）时退化为边缘复制
                flat[o + j] = row[k]
            }
        }
        return flat to total
    }

    /** 最近邻上采样 ×2（等价 F.interpolate(scale_factor=2)，默认 mode='nearest'） */
    fun nearestUp2(x: Array<FloatArray>): Array<FloatArray> {
        val t = x.size
        if (t == 0) return x
        val d = x[0].size
        val out = Array(t * 2) { FloatArray(d) }
        for (i in 0 until t) {
            System.arraycopy(x[i], 0, out[2 * i], 0, d)
            System.arraycopy(x[i], 0, out[2 * i + 1], 0, d)
        }
        return out
    }

    @Suppress("unused")
    private fun unusedAbs(v: Double) = abs(v).let { exp(it) - 1.0 }
}
