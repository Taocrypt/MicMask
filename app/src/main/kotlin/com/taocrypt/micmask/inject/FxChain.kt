package com.taocrypt.micmask.inject

import kotlin.math.*

/**
 * 轻量效果链（48k 单声道，逐样本，手机上每 10 ms 一块约 0.1 ms 级）。
 * 顺序：噪声门 → 低切 → 三段 EQ → 激励/失真 → 压限 → 合唱/叠声 → 回声 → 混响 → 输出增益/限幅。
 * 参数用一个 FloatArray 传（Binder 简单），索引见 P_*。所有值都是「界面值」，这里换算。
 */
class FxChain(private val sr: Int = 48000) {
    companion object {
        const val P_GATE = 0      // 噪声门阈值 dB，-80(关)..-30
        const val P_LOWCUT = 1    // 低切 Hz，20(关)..400
        const val P_LOW = 2       // 低频 dB ±12（120 Hz 搁架）
        const val P_MID = 3       // 中频 dB ±12（1.5 kHz 峰值）
        const val P_HIGH = 4      // 高频 dB ±12（6 kHz 搁架）
        const val P_DRIVE = 5     // 激励/失真 0..1
        const val P_COMP = 6      // 压缩 0..1
        const val P_CHORUS = 7    // 合唱/叠声 0..1
        const val P_ECHO = 8      // 回声混合 0..1
        const val P_ECHO_MS = 9   // 回声时间 60..600 ms
        const val P_ECHO_FB = 10  // 回声反馈 0..0.85
        const val P_REVERB = 11   // 混响混合 0..1
        const val P_ROOM = 12     // 房间大小 0..1
        const val P_OUT = 13      // 输出增益 dB -12..+12
        const val P_DUCK = 14     // 低音炮/低频增强 0..1（club）
        const val P_CRUSH = 15    // 全损：降采样+降位深 0..1
        const val P_PREAMP = 16   // 爆音：前级增益 dB 0..36，之后硬削波
        const val N = 17
        fun neutral() = FloatArray(N).also {
            it[P_GATE] = -80f; it[P_LOWCUT] = 20f; it[P_ECHO_MS] = 250f; it[P_ECHO_FB] = 0.35f; it[P_ROOM] = 0.5f
        }
        fun isNeutral(p: FloatArray): Boolean {
            val n = neutral()
            for (i in 0 until N) if (abs(p[i] - n[i]) > 1e-3f && i != P_ECHO_MS && i != P_ECHO_FB && i != P_ROOM) return false
            return true
        }
    }

    @Volatile private var pending: FloatArray? = null
    private var p = neutral()
    fun set(params: FloatArray) { if (params.size >= N) pending = params.copyOf(N) }

    // ---- biquad ----
    private class Bq { var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f; var z1 = 0f; var z2 = 0f
        fun run(x: Float): Float { val y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y } }
    private val hpf = Bq(); private val lo = Bq(); private val mid = Bq(); private val hi = Bq(); private val sub = Bq()

    private fun setHp(q: Bq, f: Float) { val w = 2 * PI * f / sr; val c = cos(w); val a = sin(w) / (2 * 0.707)
        norm(q, (1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + a, -2 * c, 1 - a) }
    private fun setLp(q: Bq, f: Float) { val w = 2 * PI * f / sr; val c = cos(w); val a = sin(w) / (2 * 0.707)
        norm(q, (1 - c) / 2, 1 - c, (1 - c) / 2, 1 + a, -2 * c, 1 - a) }
    private fun setPeak(q: Bq, f: Float, db: Float, Q: Double) { val A = 10.0.pow(db / 40.0); val w = 2 * PI * f / sr; val a = sin(w) / (2 * Q); val c = cos(w)
        norm(q, 1 + a * A, -2 * c, 1 - a * A, 1 + a / A, -2 * c, 1 - a / A) }
    private fun setShelf(q: Bq, f: Float, db: Float, high: Boolean) {
        val A = 10.0.pow(db / 40.0); val w = 2 * PI * f / sr; val c = cos(w); val s = sin(w); val al = s / 2 * sqrt(2.0); val sa = 2 * sqrt(A) * al
        if (!high) norm(q, A * ((A + 1) - (A - 1) * c + sa), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sa),
            (A + 1) + (A - 1) * c + sa, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sa)
        else norm(q, A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sa),
            (A + 1) - (A - 1) * c + sa, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa)
    }
    private fun norm(q: Bq, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        q.b0 = (b0 / a0).toFloat(); q.b1 = (b1 / a0).toFloat(); q.b2 = (b2 / a0).toFloat(); q.a1 = (a1 / a0).toFloat(); q.a2 = (a2 / a0).toFloat()
    }

    // ---- 动态 ----
    private var gateEnv = 0f; private var gateG = 1f; private var compEnv = 0f
    private val atk = exp(-1f / (0.003f * sr)); private val rel = exp(-1f / (0.12f * sr))

    // ---- 合唱 ----
    private val chBuf = FloatArray(sr / 20); private var chW = 0; private var chPh = 0f
    // ---- 回声 ----
    private val ecBuf = FloatArray(sr * 7 / 10); private var ecW = 0
    // ---- 混响（Freeverb 简化：4 梳 + 2 全通）----
    private val combL = intArrayOf(1557, 1617, 1491, 1422).map { it * sr / 44100 }
    private val combs = combL.map { FloatArray(it) }; private val combI = IntArray(4); private val combLp = FloatArray(4)
    private val apL = intArrayOf(556, 341).map { it * sr / 44100 }
    private val aps = apL.map { FloatArray(it) }; private val apI = IntArray(2)
    private val preBuf = FloatArray(sr / 50); private var preW = 0

    // 换算后的系数
    private var gateTh = 0f; private var gateOn = false; private var lcOn = false; private var drive = 0f; private var comp = 0f
    private var chorus = 0f; private var echo = 0f; private var ecLen = 1; private var ecFb = 0f; private var rev = 0f; private var fb = 0.84f; private var damp = 0.2f
    private var outG = 1f; private var subAmt = 0f; private var active = false
    private var preG = 1f; private var crush = 0f; private var crHold = 0f; private var crCnt = 0f; private var crStep = 1f; private var crQ = 0f
    private val bandLp = Bq()

    private fun apply(n: FloatArray) {
        p = n
        gateOn = n[P_GATE] > -79f; gateTh = 10f.pow(n[P_GATE] / 20f)
        lcOn = n[P_LOWCUT] > 25f; if (lcOn) setHp(hpf, n[P_LOWCUT])
        setShelf(lo, 120f, n[P_LOW], false); setPeak(mid, 1500f, n[P_MID], 0.9); setShelf(hi, 6000f, n[P_HIGH], true)
        drive = n[P_DRIVE].coerceIn(0f, 1f); comp = n[P_COMP].coerceIn(0f, 1f); chorus = n[P_CHORUS].coerceIn(0f, 1f)
        echo = n[P_ECHO].coerceIn(0f, 1f); ecLen = (n[P_ECHO_MS].coerceIn(60f, 600f) * sr / 1000).toInt().coerceAtMost(ecBuf.size - 1); ecFb = n[P_ECHO_FB].coerceIn(0f, 0.85f)
        rev = n[P_REVERB].coerceIn(0f, 1f); val room = n[P_ROOM].coerceIn(0f, 1f); fb = 0.70f + 0.28f * room; damp = 0.4f - 0.3f * room
        outG = 10f.pow(n[P_OUT].coerceIn(-12f, 12f) / 20f); subAmt = n[P_DUCK].coerceIn(0f, 1f); setLp(sub, 110f)
        preG = 10f.pow(n[P_PREAMP].coerceIn(0f, 36f) / 20f)
        crush = n[P_CRUSH].coerceIn(0f, 1f)
        crStep = 1f + crush * 7f                       // 48k → 最低约 6k
        crQ = 2f.pow(16f - crush * 12f)                // 16bit → 4bit
        setLp(bandLp, 7000f - 4000f * crush)           // 全损时顺带收窄频带（廉价麦/手机外放感）
        active = !isNeutral(n)
    }

    init { apply(p) }

    /** 原地处理一块 float（-1..1） */
    fun process(x: FloatArray, n: Int) {
        pending?.let { pending = null; apply(it) }
        if (!active) return
        for (i in 0 until n) {
            var s = x[i]
            if (gateOn) {
                val a = abs(s); gateEnv = if (a > gateEnv) a else gateEnv * 0.9995f
                val tgt = if (gateEnv > gateTh) 1f else 0f
                gateG += (tgt - gateG) * (if (tgt > gateG) 0.02f else 0.0008f); s *= gateG
            }
            if (lcOn) s = hpf.run(s)
            if (p[P_LOW] != 0f) s = lo.run(s)
            if (p[P_MID] != 0f) s = mid.run(s)
            if (p[P_HIGH] != 0f) s = hi.run(s)
            if (subAmt > 0f) s += sub.run(s) * subAmt * 2.5f
            if (preG > 1.01f) {                       // 爆音：硬削波
                s *= preG; s = if (s > 0.9f) 0.9f else if (s < -0.9f) -0.9f else s
            }
            if (crush > 0f) {
                crCnt += 1f; if (crCnt >= crStep) { crCnt -= crStep; crHold = Math.round(s * crQ) / crQ }
                s = bandLp.run(crHold)
            }
            if (drive > 0f) { val k = 1f + drive * 20f; s = (tanh(s * k) / tanh(k).coerceAtLeast(0.3f)) * (1f - drive * 0.3f) }
            if (comp > 0f) {
                val a = abs(s); compEnv = if (a > compEnv) atk * compEnv + (1 - atk) * a else rel * compEnv + (1 - rel) * a
                val th = 10f.pow((-6f - 24f * comp) / 20f); val ratio = 1f + 7f * comp
                if (compEnv > th) s *= (th * (compEnv / th).pow(1f / ratio)) / compEnv
                s *= 1f + comp * 1.5f // 补偿
            }
            if (chorus > 0f) {
                chBuf[chW] = s
                chPh += (0.8f * 2f * PI.toFloat() / sr); if (chPh > 2 * PI) chPh -= (2 * PI).toFloat()
                val d1 = sr * (0.012f + 0.004f * sin(chPh)); val d2 = sr * (0.019f + 0.005f * sin(chPh + 2.1f))
                s = s * (1f - chorus * 0.4f) + (tap(chBuf, chW, d1) + tap(chBuf, chW, d2)) * chorus * 0.45f
                chW = (chW + 1) % chBuf.size
            }
            if (echo > 0f) {
                var r = ecW - ecLen; if (r < 0) r += ecBuf.size
                val d = ecBuf[r]; ecBuf[ecW] = s + d * ecFb; ecW = (ecW + 1) % ecBuf.size
                s += d * echo
            }
            if (rev > 0f) {
                preBuf[preW] = s; val pr = preBuf[(preW + 1) % preBuf.size]; preW = (preW + 1) % preBuf.size
                val inp = pr * 0.3f; var acc = 0f
                for (c in 0 until 4) {
                    val b = combs[c]; val y = b[combI[c]]
                    combLp[c] = y * (1 - damp) + combLp[c] * damp
                    b[combI[c]] = inp + combLp[c] * fb; combI[c] = (combI[c] + 1) % b.size; acc += y
                }
                for (a in 0 until 2) { val b = aps[a]; val bo = b[apI[a]]; val y = -acc + bo; b[apI[a]] = acc + bo * 0.5f; apI[a] = (apI[a] + 1) % b.size; acc = y }
                s = s * (1f - rev * 0.35f) + acc * rev * 0.9f
            }
            s *= outG
            x[i] = if (s > 0.98f || s < -0.98f) tanh(s) else s // 软限幅
        }
    }

    private fun tap(b: FloatArray, w: Int, d: Float): Float {
        var pp = w - d; while (pp < 0) pp += b.size
        val i = pp.toInt(); val f = pp - i; return b[i % b.size] * (1 - f) + b[(i + 1) % b.size] * f
    }
}
