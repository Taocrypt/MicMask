package com.taocrypt.micmask.link

import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/*
 * MicMask Link v1 网络层（只用 java.net，不依赖 Android；手机端与电脑端共用）。
 * 一条会话 = 一条 TCP（握手、控制、必要时音频）+ 一条 UDP 路径（音频），双向：任一端都可以发也可以收，由 CAPS 声明。
 * 监听方 = 服务端（显示配对码），发起方 = 客户端（输入配对码）。规范见 docs/link-protocol.md。
 */

private fun nowMs() = System.currentTimeMillis()

/** 本端身份与偏好 */
class MmLinkConfig(
    val deviceId: ByteArray,
    val name: String,
    val platform: String,
    /** MmLink.CAP_SEND / CAP_RECV 组合；CAP_OPUS、CAP_PCM 自动加上 */
    val intent: Int,
    /** 本端偏好编码：作接收方时是「接收偏好」（新版发送方照它发），作发送方时在 [followPeerCodec] = false 或对方是旧版时用 */
    val preferCodec: Int = MmLink.CODEC_OPUS,
    val opusBitrate: Int = MmOpusEncoder.DEFAULT_BITRATE,
    /** 发送编码跟随接收方偏好（默认，「接收方偏好优先」）；false = 固定用本端 [preferCodec] */
    val followPeerCodec: Boolean = true,
) {
    val caps get() = capsFor(intent)
    fun capsFor(intent: Int) = MmCaps(intent or MmLink.CAP_OPUS or MmLink.CAP_PCM or MmLink.CAP_PREF_RX, preferCodec, opusBitrate)
}

/**
 * Tailscale 在线插件预留接口（2.1.1-alpha.13 只定义接口，默认空实现 [None]；插件下载、库加载与实现由插件子任务提供，最后集成）。
 * 纯 Kotlin，两端共用。启用后：
 *  - [tailnetAddrs]：本机在 tailnet 里的地址（100.64.0.0/10、fd7a:115c:a1e0::/48），并入「本机地址」与二维码；
 *  - [expose]：把本机监听端口（TCP + UDP 同号）暴露到 tailnet（userspace / tsnet 模式需要；内核 TUN 模式直接监听 0.0.0.0 就够，返回 true）；
 *  - [route]：要连 tailnet 地址时给出本地代理端口（userspace 模式没有系统路由）；返回 null = 直接连（系统路由可达或不是 tailnet 地址）。
 * 所有方法要快速返回、不抛异常（实现内部自己兜底），可在任意线程调用。
 */
interface TsBridge {
    /** 本地代理路由：TCP 连 [tcpHost]:[tcpPort]；UDP 发往 [udpHost]:[udpPort]（null = 代理不转 UDP，音频走 TCP） */
    data class Route(val tcpHost: String, val tcpPort: Int, val udpHost: String?, val udpPort: Int)
    val enabled: Boolean
    fun tailnetAddrs(): List<String>
    fun expose(port: Int): Boolean
    fun unexpose(port: Int)
    fun route(host: String, port: Int): Route?

    object None : TsBridge {
        override val enabled get() = false
        override fun tailnetAddrs() = emptyList<String>()
        override fun expose(port: Int) = false
        override fun unexpose(port: Int) {}
        override fun route(host: String, port: Int): Route? = null
    }
    companion object {
        /** 当前实现；插件加载成功后替换，停用时设回 [None] */
        @Volatile var current: TsBridge = None
    }
}

/** 会话回调 */
interface MmSessionListener {
    /** 按序、已解码（含 FEC/PLC 补帧）的 48 kHz 单声道 10 ms PCM；在网络线程回调，不要阻塞 */
    fun onPcm(s: MmSession, pcm: ShortArray) {}
    fun onCaps(s: MmSession, peer: MmCaps) {}
    fun onClosed(s: MmSession, reason: String) {}
}

class MmSession internal constructor(
    private val sock: Socket,
    private val inp: DataInputStream,
    val isClient: Boolean,
    private val keys: MmKeys,
    val peerHello: MmHello,
    val connId: Int,
    val version: Int,
    private val cfg: MmLinkConfig,
    private val tcpOnly: Boolean,
    private val listener: MmSessionListener,
    private val log: (String) -> Unit,
) {
    val peerName get() = peerHello.name
    /** 对端设备 ID（HELLO 里的 16 字节，十六进制 32 位）：地址变了也能认出是同一台设备 */
    val peerId: String get() = MmNet.hex(peerHello.deviceId)
    val peerPlatform get() = peerHello.platform
    val peerIp: InetAddress = sock.inetAddress
    @Volatile var peerCaps: MmCaps? = null; private set
    @Volatile var closed = false; private set
    @Volatile var closeReason = ""; private set
    @Volatile var rttMs = -1; private set
    @Volatile var peerStats: MmStats? = null; private set
    @Volatile var lastRxMs = nowMs(); private set
    /** 本端发音频走 UDP（true）还是 TCP */
    @Volatile var udpTx = false; private set
    @Volatile var txCodec = -1; private set
    @Volatile var rxCodec = -1; private set
    /** 本端当前声明的意图（CAP_SEND / CAP_RECV）；可在会话中用 [updateIntent] 改（同一条会话双向） */
    @Volatile var intent = cfg.intent; private set
    /** 最近一帧收到的音频走的是 UDP（true）还是 TCP 内层 AUDIO（false） */
    @Volatile var rxViaUdp = false; private set
    /** TCP 发送队列积压时丢掉的旧音频帧数（链路卡住时不让延迟越积越大） */
    @Volatile var tcpAudioDropped = 0L; private set
    /** 当前心跳超时（毫秒）：max(5 s, 8 × 最近 30 s 最大 RTT)，音频走 TCP 时至少 10 s，最多 20 s */
    @Volatile var heartbeatMs = HEARTBEAT_MS; private set
    @Volatile var udpProbesTx = 0L; private set
    @Volatile var udpProbesRx = 0L; private set

    // 统计
    @Volatile var txPackets = 0L; private set
    @Volatile var txBytes = 0L; private set
    @Volatile var rxPackets = 0L; private set
    @Volatile var rxBytes = 0L; private set
    @Volatile var rxRejected = 0L; private set
    @Volatile var lastAudioMs = 0L; private set
    val rxLost get() = reorder.lost
    val rxLate get() = reorder.late
    val rxReordered get() = reorder.reordered
    val jitterMs get() = jitter.jitterMs
    val plcFrames get() = opusDec?.plcFrames ?: 0L
    val fecFrames get() = opusDec?.fecFrames ?: 0L
    /** 由上层设置：本端接收缓冲（毫秒），随 STATS 回给发送端 */
    @Volatile var localBufMs: () -> Int = { 0 }

    private val out: OutputStream = sock.getOutputStream()
    private val wLock = Any()
    // TCP 发送队列：调用方（音频线程）只入队不阻塞，写线程负责写 socket；音频积压超过 TCP_AUDIO_Q 帧就丢最旧的音频
    private class OutFrame(val b: ByteArray, val audio: Boolean)
    private val outQ = java.util.ArrayDeque<OutFrame>()
    private val qLock = Object()
    private var outAudioN = 0
    @Volatile private var writer: Thread? = null
    private val closing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val rttHist = java.util.ArrayDeque<Pair<Long, Int>>()
    private var ctrlCtr = 0L
    private val ctrlReplay = ReplayWindow()
    private val udpReplay = ReplayWindow()
    private var udpPn = 0
    private var seq = 0
    private var tsSamples = 0
    private val pend = ShortArray(MmLink.FRAME)
    private var pendN = 0
    private var opusEnc: MmOpusEncoder? = null
    @Volatile private var opusDec: MmOpusDecoder? = null
    private val reorder = SeqReorder<MmAudio>()
    private val jitter = JitterMeter()
    private val rxLock = Any()

    // UDP：客户端自己的 socket；服务端共用监听 socket（udpShared）
    internal var udp: DatagramSocket? = null
    @Volatile internal var udpPeer: SocketAddress? = null
    @Volatile private var probeAcked = false
    private val startMs = nowMs()
    /** 客户端：最近一次收到探测回应；服务端：最近一次收到探测 */
    @Volatile private var lastProbeRxMs = 0L
    /** 服务端：UDP_OK 之后又收到的探测数（新版客户端会持续保活探测；老版本打通后就不再探测，不能据此判断 UDP 断了） */
    @Volatile private var probesAfterOk = 0
    @Volatile private var udpFallbackLogged = false
    @Volatile private var oversizeLogged = false

    internal fun start(clientUdp: DatagramSocket?, serverUdpAddr: SocketAddress?) {
        sock.soTimeout = 1000
        if (isClient && !tcpOnly && clientUdp != null) { udp = clientUdp; udpPeer = serverUdpAddr; Thread({ clientUdpLoop(clientUdp) }, "mml-udp").apply { isDaemon = true }.start() }
        writer = Thread({ writeLoop() }, "mml-wr").apply { isDaemon = true; start() }
        sendCtrl(cfg.capsFor(intent).encode())
        Thread({ readLoop() }, "mml-rd").apply { isDaemon = true }.start()
        Thread({ tick() }, "mml-tick").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ 控制

    private fun sendCtrl(inner: ByteArray, audio: Boolean = false) {
        synchronized(wLock) {
            if (closed) return
            val f = MmWire.seal(keys.txCtrl, ctrlCtr++, inner)
            synchronized(qLock) {
                if (audio && outAudioN >= TCP_AUDIO_Q) {
                    val it = outQ.iterator()
                    while (it.hasNext()) { if (it.next().audio) { it.remove(); outAudioN--; tcpAudioDropped++; break } }
                }
                outQ.addLast(OutFrame(f, audio)); if (audio) outAudioN++
                qLock.notifyAll()
            }
        }
    }

    /** 写线程：按入队顺序写 socket（SEALED 计数因此保持递增；被丢掉的音频帧只造成计数空洞，接收端滑动窗口接受） */
    private fun writeLoop() {
        try {
            while (true) {
                val f = synchronized(qLock) {
                    if (outQ.isEmpty()) { if (closed) return; qLock.wait(200) }
                    outQ.pollFirst()?.also { if (it.audio) outAudioN-- }
                } ?: continue
                out.write(f.b)
                if (synchronized(qLock) { outQ.isEmpty() }) out.flush()
            }
        } catch (t: Throwable) {
            if (!closed) close("发送失败：${t.message ?: t.javaClass.simpleName}", sendBye = false)
        }
    }

    /** 会话中改本端意图（例：发送中打开了「接收」→ 同一条会话也收）；重发 CAPS。旧版对端收到第二个 CAPS 也只是更新能力 */
    fun updateIntent(newIntent: Int) {
        if (closed || newIntent == intent) return
        intent = newIntent
        runCatching { sendCtrl(cfg.capsFor(newIntent).encode()) }
        log("本端意图改为：${if (newIntent and MmLink.CAP_SEND != 0) "发" else ""}${if (newIntent and MmLink.CAP_RECV != 0) "收" else ""}")
    }

    /** 改发送编码（例：上层要固定 PCM / Opus）；对方不支持 Opus 时只能 PCM。返回实际生效的编码 */
    fun setTxCodec(codec: Int): Int {
        val c = if (codec == MmLink.CODEC_OPUS && peerCaps?.opus != false) MmLink.CODEC_OPUS else MmLink.CODEC_PCM16
        if (c != txCodec) { txCodec = c; log("发送编码改为 ${if (c == MmLink.CODEC_OPUS) "Opus" else "PCM"}") }
        return c
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val (t, body) = try { MmWire.readFrame(inp) } catch (_: SocketTimeoutException) { continue }
                lastRxMs = nowMs()
                when (t) {
                    MmLink.T_SEALED -> onCtrl(MmWire.open(keys.rxCtrl, body, ctrlReplay))
                    MmLink.T_ERROR -> { val i = BIn(body); val c = i.u8(); throw MmLinkException(c, "对方报错：${runCatching { i.str8() }.getOrDefault("")}") }
                    else -> throw MmLinkException(MmLink.E_PROTOCOL, "握手后收到未知帧 $t")
                }
            }
        } catch (t: Throwable) { close(if (closed) closeReason else "连接断开：${t.message ?: t.javaClass.simpleName}", sendBye = false) }
    }

    private fun onCtrl(m: ByteArray) {
        val i = BIn(m)
        when (i.u8()) {
            MmLink.M_CAPS -> {
                val c = MmCaps.decode(i); peerCaps = c
                txCodec = MmCaps.pickCodec(cfg.capsFor(intent), c, cfg.followPeerCodec)
                log("对端能力：${if (c.sends) "发" else ""}${if (c.wantsRecv) "收" else ""} 偏好=${if (c.preferCodec == MmLink.CODEC_OPUS) "Opus" else "PCM"}${if (c.prefRx) "（接收偏好）" else "（旧版）"}；" +
                    "本端发送编码=${if (txCodec == MmLink.CODEC_OPUS) "Opus" else "PCM"}${if (cfg.followPeerCodec && c.prefRx) "（跟随接收方）" else ""}")
                listener.onCaps(this, c)
            }
            MmLink.M_PING -> sendCtrl(BOut().u8(MmLink.M_PONG).u64(i.u64()).toBytes())
            MmLink.M_PONG -> { val r = (nowMs() - i.u64()).toInt(); if (r in 0..60_000) { rttMs = r; synchronized(rttHist) { rttHist.addLast(nowMs() to r) } } }
            MmLink.M_STATS -> peerStats = MmStats.decode(i)
            MmLink.M_BYE -> close("对方已断开：${runCatching { i.str8() }.getOrDefault("")}", sendBye = false)
            MmLink.M_AUDIO -> { rxViaUdp = false; onAudio(MmWire.tcpAudioDecode(i)) }
            MmLink.M_UDP_OK -> { if (!isClient && udpPeer != null && !udpTx) { udpTx = true; probesAfterOk = 0; lastProbeRxMs = nowMs(); log("UDP 双向已通，音频改走 UDP") } }
            else -> {}  // 未知消息忽略（向前兼容）
        }
    }

    /** 每秒：ping、给发送方回 STATS、心跳检查、客户端 UDP 探测 */
    private fun tick() {
        var n = 0
        while (!closed) {
            try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            n++
            if (isClient && !tcpOnly) clientProbeTick(n)
            if (!isClient && udpTx && probesAfterOk >= 2 && nowMs() - lastProbeRxMs > MmLink.UDP_DEAD_MS) {
                udpTx = false; probesAfterOk = 0
                log("UDP 路径 ${MmLink.UDP_DEAD_MS / 1000.0} 秒没有探测，音频改走 TCP（UDP 恢复后自动切回）")
            }
            if (n % 4 != 0) continue
            runCatching {
                sendCtrl(BOut().u8(MmLink.M_PING).u64(nowMs()).toBytes())
                if (rxPackets > 0) {
                    val total = rxPackets + rxLost
                    val buf = localBufMs()
                    sendCtrl(MmStats(rttMs.coerceAtLeast(0), jitter.jitterMs, if (total > 0) rxLost.toFloat() / total else 0f, buf, buf + rttMs.coerceAtLeast(0) / 2 + 10).encode())
                }
            }.onFailure { close("发送控制消息失败：${it.message}", sendBye = false) }
            heartbeatMs = adaptiveHeartbeat()
            if (nowMs() - lastRxMs > heartbeatMs) close("心跳超时（${heartbeatMs / 1000.0} 秒没收到对方消息，最近 RTT ${rttMs} ms）", sendBye = false)
        }
    }

    // ------------------------------------------------------------------ UDP

    /** 心跳超时随链路自适应：偶发 RTT 尖峰（隧道重传）不断线，真断了最多 20 s 发现 */
    private fun adaptiveHeartbeat(): Long {
        val now = nowMs()
        val maxRtt = synchronized(rttHist) { while (rttHist.isNotEmpty() && now - rttHist.first().first > 30_000) rttHist.removeFirst(); rttHist.maxOfOrNull { it.second } ?: 0 }
        val tcpAudio = (lastAudioMs > 0 && !rxViaUdp) || (txPackets > 0 && !udpTx)
        return heartbeatFor(maxRtt, tcpAudio)
    }

    /**
     * 客户端探测节奏（每 250 ms 调一次）：未通时前 1.5 s 每 0.5 s 一次，之后每 2 s 一次（音频一直走 TCP，不中断）；
     * 已通后每 1 s 保活一次，连续 UDP_DEAD_MS 收不到回应就切回 TCP，回应恢复后再切回 UDP（重发 UDP_OK）。
     */
    private fun clientProbeTick(n: Int) {
        val now = nowMs()
        if (!probeAcked) {
            val early = now - startMs < MmLink.UDP_FALLBACK_MS
            if (!early && !udpFallbackLogged) { udpFallbackLogged = true; log("UDP ${MmLink.UDP_FALLBACK_MS / 1000.0} 秒内没有回应（已发探测 $udpProbesTx 个，收到 $udpProbesRx 个；目标 $udpPeer），音频走 TCP（继续低频探测，通了自动切到 UDP）") }
            if (if (early) n % 2 == 0 else n % 8 == 0) sendProbe(false)
        } else {
            if (n % 4 == 0) sendProbe(false)
            if (udpTx && now - lastProbeRxMs > MmLink.UDP_DEAD_MS) {
                udpTx = false
                log("UDP 路径 ${MmLink.UDP_DEAD_MS / 1000.0} 秒没有回应（隧道切换 / NAT 映射过期？），音频改走 TCP")
            }
        }
    }

    private fun sendProbe(ack: Boolean) {
        val u = udp ?: return; val a = udpPeer ?: return
        val d = synchronized(wLock) { MmWire.udpProbe(keys.txAudio, connId, udpPn++, ack, nowMs()) }
        runCatching { u.send(DatagramPacket(d, d.size, a)); udpProbesTx++ }.onFailure { if (udpProbesTx == 0L) log("UDP 探测发送失败（$a）：${it.message}") }
    }

    private fun clientUdpLoop(u: DatagramSocket) {
        val buf = ByteArray(2048); val dp = DatagramPacket(buf, buf.size)
        u.soTimeout = 1000
        while (!closed) {
            try { dp.setData(buf); u.receive(dp) } catch (_: SocketTimeoutException) { continue } catch (t: Throwable) { if (!closed) log("UDP 接收出错：${t.message}"); break }
            onDatagram(buf, dp.length, dp.socketAddress)
        }
    }

    /** 服务端分发器或客户端 UDP 线程调用 */
    internal fun onDatagram(d: ByteArray, len: Int, from: SocketAddress) {
        val h = MmWire.udpPeek(d, len) ?: return
        if (h.second != connId) { rxRejected++; return }
        val pn = h.third.toLong() and 0xffffffffL
        when (h.first) {
            MmLink.U_AUDIO -> {
                val a = MmWire.udpOpenAudio(keys.rxAudio, d, len) ?: run { rxRejected++; return }
                if (!udpReplay.accept(pn)) { rxRejected++; return }
                if (!isClient) udpPeer = from
                lastRxMs = nowMs(); rxViaUdp = true
                onAudio(a)
            }
            MmLink.U_PROBE, MmLink.U_PROBE_ACK -> {
                MmWire.udpOpenProbe(keys.rxAudio, d, len) ?: run { rxRejected++; return }
                if (!udpReplay.accept(pn)) { rxRejected++; return }
                udpProbesRx++; lastRxMs = nowMs()
                if (!isClient && h.first == MmLink.U_PROBE) {
                    val first = udpPeer == null; udpPeer = from; lastProbeRxMs = nowMs(); if (udpTx) probesAfterOk++
                    sendProbe(true)
                    if (first) log("收到 UDP 探测 $from${if ((from as? InetSocketAddress)?.address != peerIp) "（来源与 TCP 对端 ${peerIp.hostAddress} 不同：隧道 / 代理把 TCP 换了源地址，按认证接受，回包发往 $from）" else ""}")
                }
                if (isClient && h.first == MmLink.U_PROBE_ACK) {
                    lastProbeRxMs = nowMs()
                    if (!probeAcked || !udpTx) {
                        val again = probeAcked
                        probeAcked = true; udpTx = true
                        runCatching { sendCtrl(byteArrayOf(MmLink.M_UDP_OK.toByte())) }
                        log(if (again) "UDP 恢复，音频切回 UDP" else "UDP 双向已通（${nowMs() - startMs} ms），音频走 UDP")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 收

    private fun onAudio(a: MmAudio) {
        synchronized(rxLock) {
            if (closed) return
            if (a.samples != MmLink.FRAME || (a.codec != MmLink.CODEC_PCM16 && a.codec != MmLink.CODEC_OPUS) || (a.codec == MmLink.CODEC_PCM16 && a.data.size != MmLink.FRAME * 2)) { rxRejected++; return }
            rxPackets++; rxBytes += a.data.size
            val t = nowMs(); lastAudioMs = t
            if (rxCodec != a.codec) { rxCodec = a.codec; if (a.codec == MmLink.CODEC_OPUS && opusDec == null) opusDec = MmOpusDecoder() }
            jitter.observe(t, (a.ts.toLong() and 0xffffffffL) / (MmLink.SR / 1000), 10)
            reorder.push(a.seq, a, ::deliver) { lost, next ->
                if (next.codec == MmLink.CODEC_OPUS) opusDec?.conceal(lost, next.data)?.forEach { listener.onPcm(this, it) }
            }
        }
    }

    private fun deliver(a: MmAudio) {
        val pcm = if (a.codec == MmLink.CODEC_OPUS) runCatching { opusDec!!.decode(a.data) }.getOrElse { rxRejected++; return } else MmWire.bytesToPcm(a.data)
        if (pcm.isNotEmpty()) listener.onPcm(this, pcm)
    }

    // ------------------------------------------------------------------ 发

    /** 对端声明了想收（CAP_RECV）才真正发 */
    val peerWantsAudio get() = peerCaps?.wantsRecv == true
    /** 对端声明会发（CAP_SEND）：同一条会话里会收到它的音频 */
    val peerSends get() = peerCaps?.sends == true

    /** 追加 48 kHz 单声道 PCM（任意长度），凑满 10 ms 编码发出（本端意图里没有 CAP_SEND 时不发） */
    fun sendPcm(s: ShortArray, off: Int = 0, len: Int = s.size) {
        if (closed || !peerWantsAudio || intent and MmLink.CAP_SEND == 0) return
        var i = off; val end = off + len
        while (i < end) {
            val k = minOf(MmLink.FRAME - pendN, end - i)
            System.arraycopy(s, i, pend, pendN, k); pendN += k; i += k
            if (pendN == MmLink.FRAME) { sendFrame(pend); pendN = 0 }
        }
    }

    private var sendErrors = 0
    private fun sendFrame(f: ShortArray) {
        val codec = txCodec.takeIf { it >= 0 } ?: MmLink.CODEC_PCM16
        val data = if (codec == MmLink.CODEC_OPUS) (opusEnc ?: MmOpusEncoder(cfg.opusBitrate).also { opusEnc = it }).encode(f) else MmWire.pcmToBytes(f, 0, MmLink.FRAME)
        val a = MmAudio(seq, tsSamples, codec, 0, MmLink.FRAME, data)
        seq = if (seq == Int.MAX_VALUE) 0 else seq + 1
        tsSamples += MmLink.FRAME
        try {
            val u = udp; val dst = udpPeer
            if (udpTx && u != null && dst != null) {
                val d = synchronized(wLock) { MmWire.udpAudio(keys.txAudio, connId, udpPn++, a) }
                if (d.size <= MmLink.MAX_UDP_DATAGRAM) { u.send(DatagramPacket(d, d.size, dst)); txBytes += d.size }
                else {
                    if (!oversizeLogged) { oversizeLogged = true; log("音频数据报 ${d.size} 字节超过 ${MmLink.MAX_UDP_DATAGRAM}（隧道 MTU 保护），这类帧改走 TCP") }
                    sendCtrl(MmWire.tcpAudio(a), audio = true); txBytes += data.size + 40
                }
            } else { sendCtrl(MmWire.tcpAudio(a), audio = true); txBytes += data.size + 40 }
            txPackets++; sendErrors = 0
        } catch (t: Throwable) {
            if (++sendErrors >= 50) close("连续发送失败：${t.message}")
        }
    }

    fun close(reason: String = "已停止", sendBye: Boolean = true) {
        if (!closing.compareAndSet(false, true)) return
        if (sendBye) {
            runCatching { synchronized(qLock) { val it = outQ.iterator(); while (it.hasNext()) if (it.next().audio) it.remove(); outAudioN = 0 } }
            runCatching { sendCtrl(BOut().u8(MmLink.M_BYE).str8(reason).toBytes()) }
        }
        closeReason = reason; closed = true
        synchronized(qLock) { qLock.notifyAll() }
        // 等写线程把 BYE 写出去（最多 300 ms；写线程自己调用 close 时不等）
        val w = writer
        if (sendBye && w != null && w !== Thread.currentThread()) runCatching { w.join(300) }
        runCatching { sock.close() }
        if (isClient) runCatching { udp?.close() }
        log("会话结束：$reason")
        listener.onClosed(this, reason)
    }

    companion object {
        const val HEARTBEAT_MS = 5000L
        /** TCP 发送队列里最多积压的音频帧（10 ms/帧）；更多时丢最旧的 */
        const val TCP_AUDIO_Q = 20
        /** 心跳超时：max(5 s, 8 × 最近 30 s 最大 RTT)；音频走 TCP（隧道 / UDP 不通）时至少 10 s；上限 20 s */
        fun heartbeatFor(maxRttMs: Int, tcpAudio: Boolean): Long =
            maxOf(HEARTBEAT_MS, 8L * maxRttMs, if (tcpAudio) 10_000L else 0L).coerceAtMost(20_000L)
    }
}

/** 握手（双方共用）。成功返回会话；配对码错抛 MmLinkException(E_BAD_CODE) */
internal object MmHandshake {
    private val AAD = "MicMask Link v1".toByteArray()

    private fun send(o: OutputStream, f: ByteArray) { o.write(f); o.flush() }
    private fun expect(inp: DataInputStream, type: Int): ByteArray {
        val (t, b) = MmWire.readFrame(inp)
        if (t == MmLink.T_ERROR) { val i = BIn(b); val c = i.u8(); throw MmLinkException(c, runCatching { i.str8() }.getOrDefault("对方拒绝")) }
        if (t != type) throw MmLinkException(MmLink.E_PROTOCOL, "握手顺序不对：期望 $type 收到 $t")
        return b
    }

    fun client(s: Socket, cfg: MmLinkConfig, code: String, tcpOnly: Boolean, listener: MmSessionListener, log: (String) -> Unit): MmSession {
        val o = s.getOutputStream(); val inp = DataInputStream(s.getInputStream())
        val my = MmHello(MmLink.MIN_VERSION, MmLink.VERSION, cfg.deviceId, cfg.name, cfg.platform, MmCrypto.random(16))
        send(o, MmWire.frame(MmLink.T_HELLO, my.encode()))
        val peer = MmHello.decode(expect(inp, MmLink.T_HELLO))
        val v = MmHello.negotiate(my, peer)
        if (v < 0) { runCatching { send(o, MmWire.error(MmLink.E_VERSION, "版本不兼容")) }; throw MmLinkException(MmLink.E_VERSION, "版本不兼容：本端 ${my.minVer}–${my.maxVer}，对方 ${peer.minVer}–${peer.maxVer}") }
        val sp = Spake2(true, my.deviceId, peer.deviceId, MmCrypto.pairingW(code))
        send(o, MmWire.frame(MmLink.T_PAKE, sp.myMsg))
        val r = sp.finish(expect(inp, MmLink.T_PAKE), AAD)
        send(o, MmWire.frame(MmLink.T_CONFIRM, r.myConfirm))
        val cb = expect(inp, MmLink.T_CONFIRM)
        if (!MmCrypto.ctEquals(cb, r.peerConfirm)) throw MmLinkException(MmLink.E_BAD_CODE, "对方确认值不对（配对码不一致或中间人）")
        val keys = MmKeys.derive(r.ke, my.nonce, peer.nonce, isClient = true)
        log("握手成功 ${s.inetAddress.hostAddress}（本机 ${s.localAddress.hostAddress}）对方=${peer.name}（${peer.platform}，ID ${MmNet.hex(peer.deviceId).take(8)}）v$v")
        return MmSession(s, inp, true, keys, peer, peer.connId, v, cfg, tcpOnly, listener, log)
    }

    fun server(s: Socket, cfg: MmLinkConfig, code: String, connId: Int, listener: MmSessionListener, log: (String) -> Unit): MmSession {
        val o = s.getOutputStream(); val inp = DataInputStream(s.getInputStream())
        val peer = MmHello.decode(expect(inp, MmLink.T_HELLO))
        val my = MmHello(MmLink.MIN_VERSION, MmLink.VERSION, cfg.deviceId, cfg.name, cfg.platform, MmCrypto.random(16), connId, true)
        val v = MmHello.negotiate(peer, my)
        if (v < 0) { runCatching { send(o, MmWire.error(MmLink.E_VERSION, "版本不兼容")) }; throw MmLinkException(MmLink.E_VERSION, "版本不兼容：对方 ${peer.minVer}–${peer.maxVer}") }
        send(o, MmWire.frame(MmLink.T_HELLO, my.encode()))
        val sp = Spake2(false, peer.deviceId, my.deviceId, MmCrypto.pairingW(code))
        val pa = expect(inp, MmLink.T_PAKE)
        send(o, MmWire.frame(MmLink.T_PAKE, sp.myMsg))
        val r = sp.finish(pa, AAD)
        val ca = expect(inp, MmLink.T_CONFIRM)
        if (!MmCrypto.ctEquals(ca, r.peerConfirm)) { runCatching { send(o, MmWire.error(MmLink.E_BAD_CODE, "配对码不对")) }; throw MmLinkException(MmLink.E_BAD_CODE, "配对码不对") }
        send(o, MmWire.frame(MmLink.T_CONFIRM, r.myConfirm))
        val keys = MmKeys.derive(r.ke, peer.nonce, my.nonce, isClient = false)
        log("握手成功 ${s.inetAddress.hostAddress} → 本机 ${s.localAddress.hostAddress} 对方=${peer.name}（${peer.platform}，ID ${MmNet.hex(peer.deviceId).take(8)}）v$v")
        return MmSession(s, inp, false, keys, peer, connId, v, cfg, false, listener, log)
    }
}

/** 客户端：连接监听方 */
object MmLinkClient {
    /**
     * 连接 + 握手。[tcpOnly] = USB（adb reverse 只转 TCP）时音频也走 TCP。
     * 失败抛异常：协议层错误是 MmLinkException（code 区分版本/配对码/忙/协议），网络层错误是 MmConnectFail（kind 见 MmNet.F_*，message 是人话）。
     * [timeoutMs] TCP 连接超时；[handshakeTimeoutMs] 连上后等对方握手回应的超时（隧道 / 中继首包可能要几秒）。
     */
    fun connect(host: String, port: Int, cfg: MmLinkConfig, code: String, tcpOnly: Boolean, listener: MmSessionListener, log: (String) -> Unit = {}, timeoutMs: Int = 3000,
                handshakeTimeoutMs: Int = 8000): MmSession {
        val s = Socket()
        var stage = 0
        try {
            s.tcpNoDelay = true
            val route = runCatching { TsBridge.current.takeIf { it.enabled }?.route(host, port) }.getOrNull()
            if (route != null) log("经 Tailscale 插件代理：TCP ${route.tcpHost}:${route.tcpPort}" + (route.udpHost?.let { "，UDP $it:${route.udpPort}" } ?: "，UDP 不转（音频走 TCP）"))
            val addr = if (route != null) InetSocketAddress(route.tcpHost, route.tcpPort) else InetSocketAddress(host, port)
            if (addr.isUnresolved) throw java.net.UnknownHostException(host)
            s.connect(addr, timeoutMs)
            stage = 1
            log("TCP 已连上 ${addr.address.hostAddress}:$port（本机 ${s.localAddress.hostAddress}），开始握手")
            s.soTimeout = handshakeTimeoutMs
            val noUdp = tcpOnly || (route != null && route.udpHost == null)
            val ses = MmHandshake.client(s, cfg, code, noUdp, listener, log)
            val u = if (noUdp) null else DatagramSocket().also { runCatching { it.sendBufferSize = 256 * 1024; it.receiveBufferSize = 256 * 1024 } }
            ses.start(u, if (route?.udpHost != null) InetSocketAddress(route.udpHost, route.udpPort) else InetSocketAddress(s.inetAddress, port))
            return ses
        } catch (t: Throwable) {
            runCatching { s.close() }
            if (t is MmLinkException) { log("连接 $host:$port 失败（协议，错误码 ${t.code}）：${t.message}"); throw t }
            val kind = classify(t, stage)
            val msg = MmNet.failText(kind, host, port).let { if (kind == MmNet.F_OTHER) "$it：${t.message ?: t.javaClass.simpleName}" else it }
            log("连接 $host:$port 失败（${if (stage == 0) "TCP 连接" else "握手"}阶段，${t.javaClass.simpleName}: ${t.message}）→ $msg")
            throw MmConnectFail(kind, stage, msg, t)
        }
    }

    /** 把 java.net 异常归类成 MmNet.F_*（[stage] 0 = TCP 连接，1 = 握手） */
    fun classify(t: Throwable, stage: Int): Int {
        val m = (t.message ?: "").lowercase()
        return when {
            t is MmConnectFail -> t.kind
            stage == 1 && t is SocketTimeoutException -> MmNet.F_HS_TIMEOUT
            stage == 1 && (t is java.io.EOFException || t is java.net.SocketException) -> MmNet.F_CLOSED
            t is SocketTimeoutException || "timed out" in m || "etimedout" in m -> MmNet.F_TIMEOUT
            t is java.net.ConnectException && ("refused" in m || "econnrefused" in m) -> MmNet.F_REFUSED
            t is java.net.NoRouteToHostException || t is java.net.UnknownHostException || "unreachable" in m || "no route" in m || "enetunreach" in m || "ehostunreach" in m -> MmNet.F_UNREACHABLE
            t is java.net.ConnectException -> MmNet.F_REFUSED
            stage == 1 -> MmNet.F_CLOSED
            else -> MmNet.F_OTHER
        }
    }
}

/**
 * 服务端：监听 TCP/UDP 同一端口号，同一时间服务一条会话（新连接接管旧连接）。
 * 配对码连续错 5 次锁 60 秒（限制在线猜码）。
 */
class MmLinkServer(
    private val port: Int,
    private val cfg: MmLinkConfig,
    private val code: () -> String,
    private val listenerFor: () -> MmSessionListener,
    private val onSession: (MmSession) -> Unit,
    private val log: (String) -> Unit = {},
) {
    @Volatile var running = false; private set
    @Volatile var current: MmSession? = null; private set
    private var ss: ServerSocket? = null
    private var us: DatagramSocket? = null
    private val byConn = ConcurrentHashMap<Int, MmSession>()
    private val rnd = java.security.SecureRandom()
    private var badCount = 0
    private var lockUntil = 0L
    /** 单测钩子：丢弃所有收到的 UDP（模拟隧道 / 防火墙不放行 UDP） */
    @Volatile internal var testDropUdp = false
    val boundPort get() = ss?.localPort ?: port

    /** 监听 0.0.0.0（IPv4 + IPv6 双栈，所有网卡：Wi-Fi、热点、USB、VPN / EasyTier / Tailscale / ZeroTier 虚拟网卡） */
    fun start() {
        val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(port)); ss = s
        val u = try { DatagramSocket(null as SocketAddress?).also { it.reuseAddress = true; it.bind(InetSocketAddress(s.localPort)); runCatching { it.receiveBufferSize = 256 * 1024; it.sendBufferSize = 256 * 1024 } } }
            catch (t: Throwable) { runCatching { s.close() }; throw t }
        us = u; running = true
        runCatching { TsBridge.current.takeIf { it.enabled }?.let { if (it.expose(s.localPort)) log("Tailscale 插件：端口 ${s.localPort} 已暴露到 tailnet") } }
        Thread({ acceptLoop(s) }, "mml-accept").apply { isDaemon = true }.start()
        Thread({ udpLoop(u) }, "mml-sudp").apply { isDaemon = true }.start()
        log("监听 TCP/UDP ${s.localPort}（所有网卡）本机地址：${MmNet.localAddrs().joinToString { "${it.ip}（${it.iface}${if (it.vpn.isNotEmpty()) " " + it.vpn else ""}）" }.ifEmpty { "无" }}")
    }

    fun stop() {
        running = false
        runCatching { TsBridge.current.takeIf { it.enabled }?.unexpose(boundPort) }
        current?.close("接收端已关闭"); current = null
        runCatching { ss?.close() }; runCatching { us?.close() }
        byConn.clear()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (t: Throwable) { if (running) log("accept 失败：${t.message}"); break }
            Thread({ handle(c) }, "mml-hs").apply { isDaemon = true }.start()
        }
    }

    private fun handle(c: Socket) {
        try {
            c.tcpNoDelay = true; c.soTimeout = 10_000
            if (nowMs() < lockUntil) { runCatching { c.getOutputStream().write(MmWire.error(MmLink.E_BUSY, "配对码错误次数过多，请 1 分钟后再试")) }; c.close(); return }
            var cid: Int; do { cid = rnd.nextInt() } while (cid == 0 || byConn.containsKey(cid))
            val ses = try { MmHandshake.server(c, cfg, code(), cid, listenerFor(), log) } catch (e: MmLinkException) {
                if (e.code == MmLink.E_BAD_CODE) synchronized(this) { if (++badCount >= 5) { lockUntil = nowMs() + 60_000; badCount = 0; log("配对码连续错误 5 次，锁定 60 秒") } }
                throw e
            }
            synchronized(this) { badCount = 0 }
            current?.let { log("新连接接管旧连接 ${it.peerName}"); it.close("被新连接接管") }
            byConn[cid] = ses; current = ses
            ses.udp = us
            ses.start(null, null)
            onSession(ses)
            // 会话结束后清理
            Thread({ while (!ses.closed) try { Thread.sleep(500) } catch (_: InterruptedException) { break }; byConn.remove(cid); if (current === ses) current = null }, "mml-reap").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            log("连接 ${c.inetAddress.hostAddress} 握手失败：${t.message ?: t.javaClass.simpleName}")
            runCatching { c.close() }
        }
    }

    private fun udpLoop(u: DatagramSocket) {
        val buf = ByteArray(2048); val dp = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                dp.setData(buf); u.receive(dp)
                if (testDropUdp) continue
                val h = MmWire.udpPeek(buf, dp.length) ?: continue
                val ses = byConn[h.second] ?: continue
                // 2.1.1-alpha.13：不再要求来源 IP = TCP 对端。EasyTier / Tailscale userspace 等会代理 TCP（服务端看到的 TCP 来源变成本机或网关地址），
                // UDP 却是原始来源，旧规则把它们全丢了，表现为「UDP 始终不通、全程走 TCP」。数据报本身有 connId + AES-GCM 认证 + 重放窗口，
                // 认证不过的照样丢（rxRejected），回包发往最近一个认证通过的来源地址。
                ses.onDatagram(buf, dp.length, dp.socketAddress)
            } catch (t: Throwable) { if (running) log("UDP 接收出错：${t.message}") }
        }
    }
}
