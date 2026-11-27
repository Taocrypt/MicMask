package com.taocrypt.micmask

/**
 * 声纹门限的判定状态机（2.1.1-alpha.6，纯 Kotlin，不依赖 Android，可在 JVM 上单测）。
 *
 *  - 分数平滑：最近 [smoothN] 次有效相似度（且距本块不超过 [staleSamples]）取均值；
 *  - 双阈值 + 滞回：平滑分 ≥ [passThr] → 判为本人（放行）；平滑分 < [blockThr] → 判为他人（压低）；
 *    两者之间保持上一判定。单次分数 ≥ passThr + [FAST_MARGIN] 立即放行（本人一开口别被平滑拖慢）；
 *  - 拖尾：最后一个判为本人的窗口之后 [holdSamples]（按音频时间）内一律放行，句尾/换气不被压低；
 *  - 证据过期（一段时间没有新分数，比如停顿后重新开口）：清空平滑窗，判定回到「放行」，宁可漏一点别人的开头也不吃本人的字。
 * 位置单位是样本（SpeakerGate 环形缓冲里的绝对位置）。线程：只在特征线程里调用。
 */
class VpDecision(
    @Volatile var passThr: Float,
    @Volatile var blockThr: Float,
    private val holdSamples: Long,
    private val staleSamples: Long,
    private val smoothN: Int = 2,
) {
    companion object {
        /** 单次分数比放行阈值高出这么多就不等平滑，直接放行 */
        const val FAST_MARGIN = 0.05f
        /** 用户阈值（放行）与拦截阈值之差 */
        const val HYST = 0.07f
        /** 用户滑块值 → (放行, 拦截) */
        fun thresholds(user: Float): Pair<Float, Float> {
            val p = user.coerceIn(0.05f, 0.95f)
            return p to (p - HYST).coerceAtLeast(0.02f)
        }
    }

    enum class Verdict { PASS, HOLD, NO_EVIDENCE, BLOCK }

    private val posH = LongArray(smoothN.coerceAtLeast(1))
    private val simH = FloatArray(smoothN.coerceAtLeast(1))
    private var nH = 0
    /** true = 当前判为本人（放行） */
    var passing = true; private set
    var holdUntil = -1L; private set
    /** 最近一次平滑分（没有为 NaN） */
    var smoothed = Float.NaN; private set
    private var lastPos = -1L

    fun reset() { nH = 0; passing = true; holdUntil = -1L; smoothed = Float.NaN; lastPos = -1L }

    /** 加一次有效分数（[end] = 该窗口结束位置）；返回这次的平滑分 */
    fun add(end: Long, sim: Float): Float {
        if (lastPos >= 0 && end - lastPos > staleSamples) { nH = 0; passing = true }
        // 丢掉过期的历史
        var k = 0
        for (i in 0 until nH) if (end - posH[i] <= staleSamples) { posH[k] = posH[i]; simH[k] = simH[i]; k++ }
        nH = k
        if (nH == posH.size) { System.arraycopy(posH, 1, posH, 0, nH - 1); System.arraycopy(simH, 1, simH, 0, nH - 1); nH-- }
        posH[nH] = end; simH[nH] = sim; nH++
        lastPos = end
        var s = 0f; for (i in 0 until nH) s += simH[i]
        smoothed = s / nH
        val pass = passThr; val block = blockThr
        if (smoothed >= pass || sim >= pass + FAST_MARGIN) passing = true
        else if (smoothed < block) passing = false
        // 仍判为本人（含滞回区保持）的窗口都续拖尾：从最后一个本人窗口算起再放行 holdSamples
        if (passing) holdUntil = maxOf(holdUntil, end + holdSamples)
        return smoothed
    }

    /** 块 [end] 的判定 */
    fun decide(end: Long): Verdict {
        val fresh = lastPos >= 0 && end - lastPos <= staleSamples
        return when {
            !fresh -> if (end <= holdUntil) Verdict.HOLD else Verdict.NO_EVIDENCE
            passing -> Verdict.PASS
            end <= holdUntil -> Verdict.HOLD
            else -> Verdict.BLOCK
        }
    }
}
