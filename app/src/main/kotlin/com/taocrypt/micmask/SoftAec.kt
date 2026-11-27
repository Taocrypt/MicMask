package com.taocrypt.micmask

import cn.enaium.webrtc.aec3.Aec3AudioBuffer
import cn.enaium.webrtc.aec3.Aec3Config
import cn.enaium.webrtc.aec3.Aec3EchoControl
import cn.enaium.webrtc.aec3.Aec3Environment
import cn.enaium.webrtc.aec3.Aec3Factory
import cn.enaium.webrtc.aec3.createAec3AudioBuffer
import cn.enaium.webrtc.aec3.createAec3Config
import cn.enaium.webrtc.aec3.createAec3EchoControl
import cn.enaium.webrtc.aec3.createAec3Environment
import cn.enaium.webrtc.aec3.createAec3FactoryWithConfig

/**
 * 软件回声消除（WebRTC AEC3，经 webrtc-aec3-kmp 1.0.3 绑定；MIT/BSD-3，见 NOTICE.md）。
 *
 * - 固定 48 kHz 单声道、10 ms 一帧（480 样本），与 InjectorService.liveLoop 的 hop 一致，不引入额外缓冲。
 * - 样本按 int16 刻度的 float 喂给 AEC3（不是 −1..1）。
 * - [render] 喂参考信号（本机将要/正在外放的声音），[process] 先 analyzeCapture 再 processCapture 处理麦克风。
 * - 不是线程安全的：render 与 process 都在 liveLoop 线程里串行调用。
 * - 任何异常（含 .so 加载失败的 UnsatisfiedLinkError / ExceptionInInitializerError）→ 自动关闭、记一条日志，
 *   之后 [process] 一律返回 false，调用方继续用原始麦克风。
 */
class SoftAec(private val log: (Char, String) -> Unit) {
    companion object {
        const val SR = 48000
        const val FRAME = SR / 100
    }

    /** 出错原因（null = 正常）；一旦出错本实例不再工作，直到 [release] 后重建 */
    @Volatile var failed: String? = null; private set

    private var cfg: Aec3Config? = null
    private var env: Aec3Environment? = null
    private var factory: Aec3Factory? = null
    private var ec: Aec3EchoControl? = null
    private var rBuf: Aec3AudioBuffer? = null
    private var cBuf: Aec3AudioBuffer? = null
    private val rf = FloatArray(FRAME)
    private val cf = FloatArray(FRAME)

    // 统计（每 10 s 由调用方取一次 [report] 后清零）：每帧 render+analyze+process 耗时
    private val costs = LongArray(1200)
    private var nCost = 0
    private var maxCost = 0L
    private var pendingRenderNs = 0L

    val ready: Boolean get() = ec != null && failed == null

    private fun ensure(): Boolean {
        if (failed != null) return false
        if (ec != null) return true
        return guard("初始化") {
            val c = createAec3Config()
            cfg = c
            env = createAec3Environment()
            factory = createAec3FactoryWithConfig(c)
            ec = createAec3EchoControl(factory!!, env!!, SR, 1, 1)
            rBuf = createAec3AudioBuffer(SR, 1)
            cBuf = createAec3AudioBuffer(SR, 1)
            log('I', "AEC：已初始化（WebRTC AEC3，${SR / 1000} kHz 单声道，10 ms 帧）")
        }
    }

    /** 喂一帧参考（n ≤ 480，不足补零）。失败返回 false */
    fun render(pcm: ShortArray, n: Int): Boolean {
        if (!ensure()) return false
        val t0 = System.nanoTime()
        val ok = guard("参考信号") {
            for (i in 0 until FRAME) rf[i] = if (i < n) pcm[i].toFloat() else 0f
            rBuf!!.writeChannel(0, rf)
            ec!!.analyzeRender(rBuf!!)
        }
        pendingRenderNs += System.nanoTime() - t0
        return ok
    }

    /** 处理一帧麦克风 [inp] → [out]（各 480）。返回 false = 没处理（出错/未就绪），调用方用原始信号 */
    fun process(inp: ShortArray, out: ShortArray): Boolean {
        if (!ensure()) { pendingRenderNs = 0; return false }
        val t0 = System.nanoTime()
        val ok = guard("处理麦克风") {
            for (i in 0 until FRAME) cf[i] = inp[i].toFloat()
            val b = cBuf!!
            b.writeChannel(0, cf)
            ec!!.analyzeCapture(b)
            ec!!.processCapture(b, false)
            val y = b.readChannel(0)
            val m = minOf(FRAME, y.size)
            for (i in 0 until m) { val v = y[i]; out[i] = (if (v >= 32767f) 32767 else if (v <= -32768f) -32768 else v.toInt()).toShort() }
            for (i in m until FRAME) out[i] = 0
        }
        val dt = System.nanoTime() - t0 + pendingRenderNs
        pendingRenderNs = 0
        if (ok) { if (nCost < costs.size) costs[nCost++] = dt; if (dt > maxCost) maxCost = dt }
        return ok
    }

    /** 统计行（不含前缀），并清零耗时统计；没有帧时返回 null */
    fun report(): String? {
        val e = ec ?: return null
        if (failed != null || nCost == 0) return null
        var line: String? = null
        guard("读取指标") {
            val m = e.getMetrics()
            val a = costs.copyOf(nCost); a.sort()
            val p50 = a[a.size / 2]
            line = "ERL=${"%.1f".format(m.echoReturnLoss)} ERLE=${"%.1f".format(m.echoReturnLossEnhancement)} 估计延迟=${m.delayMs} ms " +
                "耗时 p50/最大=${"%.2f".format(p50 / 1e6)}/${"%.2f".format(maxCost / 1e6)} ms（${nCost} 帧）"
        }
        nCost = 0; maxCost = 0
        return line
    }

    fun release() {
        // 释放顺序：缓冲 → EchoControl → 工厂/环境/配置
        runCatching { rBuf?.close() }; runCatching { cBuf?.close() }
        runCatching { ec?.close() }
        runCatching { factory?.close() }; runCatching { env?.close() }; runCatching { cfg?.close() }
        rBuf = null; cBuf = null; ec = null; factory = null; env = null; cfg = null
        nCost = 0; maxCost = 0; pendingRenderNs = 0
    }

    private inline fun guard(what: String, block: () -> Unit): Boolean = try {
        block(); true
    } catch (t: Throwable) {
        failed = "$what：${t.javaClass.simpleName}: ${t.message}"
        log('E', "AEC：$what 出错，已自动关闭软件回声消除（${t.javaClass.simpleName}: ${t.message}）")
        release()
        false
    }
}
