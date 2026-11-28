package com.taocrypt.micmask.desktop

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 发送端输入增益 + 可选自动增益（AGC）+ 限幅（防爆音）。在采集线程里逐帧（10 ms，480 样本）原地处理。
 *  - 手动增益 [gainDb]：−12 ~ +24 dB。
 *  - AGC（[agc]=true）：目标 RMS ≈ −18 dBFS；只在有声音的帧（RMS > −50 dBFS）上调整，避免把底噪放大；
 *    变大慢（每帧最多 +0.05 dB ≈ 5 dB/秒）、变小快（每帧最多 −0.5 dB），AGC 自身范围 −12 ~ +18 dB，叠加在手动增益上。
 *  - 限幅：增益后峰值超过 −1 dBFS 时整帧压到 −1 dBFS（立即生效，不削波）；不再超限时帧内线性恢复（不咔哒），再硬限幅兜底。
 * 线程安全：参数是 @Volatile，状态只在采集线程里改。
 */
class Gain {
    @Volatile var gainDb = 0f
        set(v) { field = v.coerceIn(MIN_DB, MAX_DB) }
    @Volatile var agc = false
    /** AGC 当前附加增益（dB，界面显示） */
    @Volatile var agcDb = 0f; private set
    /** 限幅动作次数（日志） */
    @Volatile var limited = 0L; private set
    private var limPrev = 1f

    fun reset() { agcDb = 0f; limPrev = 1f }

    fun process(s: ShortArray, n: Int = s.size) {
        if (!agc && gainDb == 0f && limPrev >= 1f) return
        var sq = 0.0
        for (i in 0 until n) { val v = s[i].toDouble(); sq += v * v }
        val rmsDb = AudioIO.Level.db(sqrt(sq / n.coerceAtLeast(1)) / 32768.0)
        if (agc) {
            if (rmsDb > GATE_DB) {
                val want = (TARGET_DB - (rmsDb + gainDb)).coerceIn(AGC_MIN, AGC_MAX)
                val d = want - agcDb
                agcDb += if (d > 0) minOf(d, UP_STEP) else maxOf(d, -DOWN_STEP)
            }
        } else agcDb = 0f
        val g = 10.0.pow((gainDb + agcDb) / 20.0).toFloat()
        var pk = 0f
        for (i in 0 until n) { val a = abs(s[i] * g); if (a > pk) pk = a }
        val lim = if (pk > CEIL) CEIL / pk else 1f
        if (lim < 1f) limited++
        // 压缩立即生效（整帧用新的压缩量，宁可一帧的增益台阶也不削波）；恢复时帧内线性过渡，不咔哒
        val from = if (lim < limPrev) lim else limPrev; limPrev = lim
        for (i in 0 until n) {
            val k = from + (lim - from) * (i + 1) / n
            val v = s[i] * g * k
            s[i] = when { v > 32767f -> 32767; v < -32768f -> -32768; else -> v.toInt() }.toShort()
        }
    }

    companion object {
        const val MIN_DB = -12f
        const val MAX_DB = 24f
        const val TARGET_DB = -18f
        const val GATE_DB = -50f
        const val AGC_MIN = -12f
        const val AGC_MAX = 18f
        const val UP_STEP = 0.05f
        const val DOWN_STEP = 0.5f
        /** −1 dBFS */
        val CEIL = (32767 * 10.0.pow(-1.0 / 20)).toFloat()
        fun dbOf(x: Float) = if (x <= 1e-6f) -120f else (20 * log10(x.toDouble())).toFloat()
    }
}
