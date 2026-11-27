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
 * - 对外固定 48 kHz 单声道、10 ms 一帧（480 样本），与 InjectorService.liveLoop 的 hop 一致。
 * - 2.1.1-alpha.7：AEC3 改在 16 kHz 跑。1.0.3 的 JNI 在 32/48 kHz 下不做分频带（上游之后才修，Maven 未发新版），
 *   AEC3 看到的参考恒为空、永不收敛、输出原样返回（alpha.6 真机 ERL=-30.0 ERLE=0.2 估计延迟=12 ms 一直不变即此）。
 *   现在由 [AecBandBridge] 把参考和麦克风降到 16 kHz 给 AEC3，处理完的低频带升回 48 kHz，
 *   高频带（约 7 kHz 以上）按低频带的压低比例同步压低。代价：麦克风多 [AecBandBridge.latency48] 个样本（10 ms）延迟。
 * - 样本按 int16 刻度的 float 喂给 AEC3（不是 −1..1）。
 * - [render] 喂参考信号（本机刚外放出去的声音），[process] 先 analyzeCapture 再 processCapture 处理麦克风。
 * - AEC3 配置保持库默认（alpha.7 之后用 JVM 模拟验证过：200 ms 延迟、喇叭 DRC/限幅、双讲下，加长 refined/coarse 滤波器（13→20/30 块）
 *   反而变差 4~10 dB，delay_headroom、has_clock_drift、ep_strength、echo_audibility、bounded_erl 的改动都在 ±1.5 dB 内；
 *   真正决定收敛的是参考时间线是否稳定，见 [PlayoutAligner]）。
 * - 不是线程安全的：render 与 process 都在 liveLoop 线程里串行调用。
 * - 任何异常（含 .so 加载失败的 UnsatisfiedLinkError / ExceptionInInitializerError）→ 自动关闭、记一条日志，
 *   之后 [process] 一律返回 false，调用方继续用原始麦克风。
 */
class SoftAec(private val log: (Char, String) -> Unit) {
    companion object {
        const val SR = 48000
        const val FRAME = SR / 100
        /** AEC3 实际运行的采样率（见类注释） */
        const val CORE_SR = 16000
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
    private val of = FloatArray(FRAME)
    private val bridge = AecBandBridge()
    /** 最近一次读到的 AEC3 指标（每 10 帧读一次，给兜底判断用）；未读到为 NaN / -1 */
    var lastErle = Float.NaN; private set
    var lastDelayMs = -1; private set
    private var metricTick = 0

    // 统计（由调用方定时取一次 [report] 后清零）：每帧 render+analyze+process 耗时；参考 / 麦克风处理前后能量（int16 刻度）
    private val costs = LongArray(1200)
    private var nCost = 0
    private var maxCost = 0L
    private var pendingRenderNs = 0L
    private var refE = 0.0; private var inE = 0.0; private var outE = 0.0
    private var nRef = 0L; private var nCap = 0L

    val ready: Boolean get() = ec != null && failed == null

    private fun ensure(): Boolean {
        if (failed != null) return false
        if (ec != null) return true
        return guard("初始化") {
            val c = createAec3Config()
            cfg = c
            env = createAec3Environment()
            factory = createAec3FactoryWithConfig(c)
            ec = createAec3EchoControl(factory!!, env!!, CORE_SR, 1, 1)
            rBuf = createAec3AudioBuffer(CORE_SR, 1)
            cBuf = createAec3AudioBuffer(CORE_SR, 1)
            bridge.reset()
            log('I', "AEC：已初始化（WebRTC AEC3，内部 ${CORE_SR / 1000} kHz + 高频带跟随，对外 ${SR / 1000} kHz 单声道 10 ms 帧，" +
                "麦克风多 ${bridge.latency48 * 1000 / SR} ms 延迟；1.0.3 在 48 kHz 下不分频带、不会收敛，故改 16 kHz）")
            // 2.1.1-alpha.6：记录默认配置里和延迟相关的值（只记录，不改默认），排查外放大延迟时对照
            runCatching {
                val j = org.json.JSONObject(c.toJson())
                val root = j.optJSONObject("aec3") ?: j
                val d = root.optJSONObject("delay")
                val f = root.optJSONObject("filter")
                val b = root.optJSONObject("buffering")
                log('I', "AEC：默认配置（未修改）delay=${d?.toString()?.take(500) ?: "?"} buffering=${b?.toString()?.take(200) ?: "?"} " +
                    "filter.config_change_duration_blocks=${f?.opt("config_change_duration_blocks") ?: "?"} filter.initial_state_seconds=${f?.opt("initial_state_seconds") ?: "?"}")
            }.onFailure { log('W', "AEC：读默认配置失败（${it.javaClass.simpleName}）") }
        }
    }

    /** 喂一帧参考（n ≤ 480，不足补零）。失败返回 false */
    fun render(pcm: ShortArray, n: Int): Boolean {
        if (!ensure()) return false
        val t0 = System.nanoTime()
        val ok = guard("参考信号") {
            var e = 0.0
            for (i in 0 until FRAME) { val v = if (i < n) pcm[i].toFloat() else 0f; rf[i] = v; e += v.toDouble() * v }
            refE += e; nRef += FRAME
            rBuf!!.writeChannel(0, bridge.render48(rf))
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
            var ei = 0.0
            for (i in 0 until FRAME) { val v = inp[i].toFloat(); cf[i] = v; ei += v.toDouble() * v }
            val b = cBuf!!
            b.writeChannel(0, bridge.capture48(cf))
            ec!!.analyzeCapture(b)
            ec!!.processCapture(b, false)
            val y = b.readChannel(0)
            check(y.size == AecBandBridge.N16) { "AEC3 输出 ${y.size} 样本（应为 ${AecBandBridge.N16}）" }
            bridge.merge(y, of)
            for (i in 0 until FRAME) { val v = of[i]; out[i] = (if (v >= 32767f) 32767 else if (v <= -32768f) -32768 else v.toInt()).toShort() }
            if (++metricTick >= 10) {
                metricTick = 0
                val m = ec!!.getMetrics()
                lastErle = m.echoReturnLossEnhancement.toFloat(); lastDelayMs = m.delayMs
            }
            var eo = 0.0
            for (i in 0 until FRAME) eo += out[i].toDouble() * out[i]
            inE += ei; outE += eo; nCap += FRAME
        }
        val dt = System.nanoTime() - t0 + pendingRenderNs
        pendingRenderNs = 0
        if (ok) { if (nCost < costs.size) costs[nCost++] = dt; if (dt > maxCost) maxCost = dt }
        return ok
    }

    private fun db(e: Double, n: Long) = if (n <= 0) "?" else "%.0f".format(10 * kotlin.math.log10(e / n / (32768.0 * 32768.0) + 1e-12))

    /** 统计行（不含前缀），并清零统计；没有帧时返回 null。含 ERL/ERLE/估计延迟、参考电平、麦克风处理前/后电平（dBFS） */
    fun report(): String? {
        val e = ec ?: return null
        if (failed != null || nCost == 0) return null
        var line: String? = null
        guard("读取指标") {
            val m = e.getMetrics()
            val a = costs.copyOf(nCost); a.sort()
            val p50 = a[a.size / 2]
            line = "ERL=${"%.1f".format(m.echoReturnLoss)} ERLE=${"%.1f".format(m.echoReturnLossEnhancement)} AEC估计延迟=${m.delayMs} ms（内部 ${CORE_SR / 1000}k，高频带增益 ${"%.2f".format(bridge.hiGain)}） " +
                "参考电平=${db(refE, nRef)}dB 麦克风 处理前=${db(inE, nCap)}dB 处理后=${db(outE, nCap)}dB " +
                "耗时 p50/最大=${"%.2f".format(p50 / 1e6)}/${"%.2f".format(maxCost / 1e6)} ms（${nCost} 帧）"
        }
        nCost = 0; maxCost = 0; refE = 0.0; inE = 0.0; outE = 0.0; nRef = 0; nCap = 0
        return line
    }

    fun release() {
        // 释放顺序：缓冲 → EchoControl → 工厂/环境/配置
        runCatching { rBuf?.close() }; runCatching { cBuf?.close() }
        runCatching { ec?.close() }
        runCatching { factory?.close() }; runCatching { env?.close() }; runCatching { cfg?.close() }
        rBuf = null; cBuf = null; ec = null; factory = null; env = null; cfg = null
        bridge.reset(); lastErle = Float.NaN; lastDelayMs = -1; metricTick = 0
        nCost = 0; maxCost = 0; pendingRenderNs = 0; refE = 0.0; inE = 0.0; outE = 0.0; nRef = 0; nCap = 0
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
