package com.taocrypt.micmask.inject

/**
 * 低延迟变调（双读头延迟线 + 三角窗交叉淡化）。
 * 窗长约 40 ms → 额外延迟约 20–40 ms。不改共振峰，只是「变调」，用来先打通实时链路。
 */
class PitchShifter(sr: Int, windowMs: Int = 40) {
    private val win = (sr * windowMs / 1000).coerceAtLeast(256)
    private val size = win * 2
    private val buf = FloatArray(size)
    private var w = 0
    private var phase = 0f // 0..win

    fun process(x: Float, ratio: Float): Float {
        buf[w] = x
        // 读头相对写头的延迟在 [0, win) 里滑动；ratio>1 时读得比写快
        phase += 1f - ratio
        while (phase < 0f) phase += win
        while (phase >= win) phase -= win
        val d1 = phase
        var d2 = phase + win / 2f
        if (d2 >= win) d2 -= win
        val g1 = 1f - Math.abs(d1 - win / 2f) / (win / 2f)
        val g2 = 1f - Math.abs(d2 - win / 2f) / (win / 2f)
        val y = read(d1) * g1 + read(d2) * g2
        w = (w + 1) % size
        return y
    }

    private fun read(delay: Float): Float {
        var p = w - delay
        while (p < 0f) p += size
        val i = p.toInt()
        val f = p - i
        val a = buf[i % size]
        val b = buf[(i + 1) % size]
        return a + (b - a) * f
    }
}
