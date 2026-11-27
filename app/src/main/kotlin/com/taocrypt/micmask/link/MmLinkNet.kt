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
    val preferCodec: Int = MmLink.CODEC_OPUS,
    val opusBitrate: Int = MmOpusEncoder.DEFAULT_BITRATE,
) {
    val caps get() = MmCaps(intent or MmLink.CAP_OPUS or MmLink.CAP_PCM, preferCodec, opusBitrate)
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

    internal fun start(clientUdp: DatagramSocket?, serverUdpAddr: SocketAddress?) {
        sock.soTimeout = 1000
        if (isClient && !tcpOnly && clientUdp != null) { udp = clientUdp; udpPeer = serverUdpAddr; Thread({ clientUdpLoop(clientUdp) }, "mml-udp").apply { isDaemon = true }.start() }
        sendCtrl(cfg.caps.encode())
        Thread({ readLoop() }, "mml-rd").apply { isDaemon = true }.start()
        Thread({ tick() }, "mml-tick").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ 控制

    private fun sendCtrl(inner: ByteArray) {
        synchronized(wLock) {
            if (closed) return
            val f = MmWire.seal(keys.txCtrl, ctrlCtr++, inner)
            out.write(f); out.flush()
        }
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
                txCodec = MmCaps.pickCodec(cfg.caps, c)
                log("对端能力：${if (c.sends) "发" else ""}${if (c.wantsRecv) "收" else ""} 偏好=${if (c.preferCodec == MmLink.CODEC_OPUS) "Opus" else "PCM"}；本端发送编码=${if (txCodec == MmLink.CODEC_OPUS) "Opus" else "PCM"}")
                listener.onCaps(this, c)
            }
            MmLink.M_PING -> sendCtrl(BOut().u8(MmLink.M_PONG).u64(i.u64()).toBytes())
            MmLink.M_PONG -> { val r = (nowMs() - i.u64()).toInt(); if (r in 0..60_000) rttMs = r }
            MmLink.M_STATS -> peerStats = MmStats.decode(i)
            MmLink.M_BYE -> close("对方已断开：${runCatching { i.str8() }.getOrDefault("")}", sendBye = false)
            MmLink.M_AUDIO -> onAudio(MmWire.tcpAudioDecode(i))
            MmLink.M_UDP_OK -> { if (!isClient && udpPeer != null) { udpTx = true; log("UDP 双向已通，音频改走 UDP") } }
            else -> {}  // 未知消息忽略（向前兼容）
        }
    }

    /** 每秒：ping、给发送方回 STATS、心跳检查、客户端 UDP 探测 */
    private fun tick() {
        var n = 0
        while (!closed) {
            try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            n++
            if (isClient && !tcpOnly && !probeAcked && n % 2 == 0) sendProbe(false)
            if (n % 4 != 0) continue
            runCatching {
                sendCtrl(BOut().u8(MmLink.M_PING).u64(nowMs()).toBytes())
                if (rxPackets > 0) {
                    val total = rxPackets + rxLost
                    val buf = localBufMs()
                    sendCtrl(MmStats(rttMs.coerceAtLeast(0), jitter.jitterMs, if (total > 0) rxLost.toFloat() / total else 0f, buf, buf + rttMs.coerceAtLeast(0) / 2 + 10).encode())
                }
            }.onFailure { close("发送控制消息失败：${it.message}", sendBye = false) }
            if (nowMs() - lastRxMs > HEARTBEAT_MS) close("心跳超时（${HEARTBEAT_MS / 1000} 秒没收到对方消息）", sendBye = false)
        }
    }

    // ------------------------------------------------------------------ UDP

    private fun sendProbe(ack: Boolean) {
        val u = udp ?: return; val a = udpPeer ?: return
        val d = synchronized(wLock) { MmWire.udpProbe(keys.txAudio, connId, udpPn++, ack, nowMs()) }
        runCatching { u.send(DatagramPacket(d, d.size, a)) }
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
                onAudio(a)
            }
            MmLink.U_PROBE, MmLink.U_PROBE_ACK -> {
                MmWire.udpOpenProbe(keys.rxAudio, d, len) ?: run { rxRejected++; return }
                if (!udpReplay.accept(pn)) { rxRejected++; return }
                if (!isClient && h.first == MmLink.U_PROBE) { val first = udpPeer == null; udpPeer = from; sendProbe(true); if (first) log("收到 UDP 探测 $from") }
                if (isClient && h.first == MmLink.U_PROBE_ACK && !probeAcked) {
                    probeAcked = true; udpTx = true
                    runCatching { sendCtrl(byteArrayOf(MmLink.M_UDP_OK.toByte())) }
                    log("UDP 双向已通，音频走 UDP")
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

    /** 追加 48 kHz 单声道 PCM（任意长度），凑满 10 ms 编码发出 */
    fun sendPcm(s: ShortArray, off: Int = 0, len: Int = s.size) {
        if (closed || !peerWantsAudio) return
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
                u.send(DatagramPacket(d, d.size, dst))
                txBytes += d.size
            } else { sendCtrl(MmWire.tcpAudio(a)); txBytes += data.size + 40 }
            txPackets++; sendErrors = 0
        } catch (t: Throwable) {
            if (++sendErrors >= 50) close("连续发送失败：${t.message}")
        }
    }

    fun close(reason: String = "已停止", sendBye: Boolean = true) {
        if (closed) return
        if (sendBye) runCatching { sendCtrl(BOut().u8(MmLink.M_BYE).str8(reason).toBytes()) }
        closeReason = reason; closed = true
        runCatching { sock.close() }
        if (isClient) runCatching { udp?.close() }
        log("会话结束：$reason")
        listener.onClosed(this, reason)
    }

    companion object { const val HEARTBEAT_MS = 5000L }
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
        log("握手成功 ${s.inetAddress.hostAddress} 对方=${peer.name}（${peer.platform}）v$v")
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
        log("握手成功 ${s.inetAddress.hostAddress} 对方=${peer.name}（${peer.platform}）v$v")
        return MmSession(s, inp, false, keys, peer, connId, v, cfg, false, listener, log)
    }
}

/** 客户端：连接监听方 */
object MmLinkClient {
    /**
     * 连接 + 握手。[tcpOnly] = USB（adb reverse 只转 TCP）时音频也走 TCP。失败抛异常（MmLinkException.code 区分版本/配对码/协议）。
     */
    fun connect(host: String, port: Int, cfg: MmLinkConfig, code: String, tcpOnly: Boolean, listener: MmSessionListener, log: (String) -> Unit = {}, timeoutMs: Int = 3000): MmSession {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 5000
            val ses = MmHandshake.client(s, cfg, code, tcpOnly, listener, log)
            val u = if (tcpOnly) null else DatagramSocket().also { runCatching { it.sendBufferSize = 256 * 1024; it.receiveBufferSize = 256 * 1024 } }
            ses.start(u, InetSocketAddress(s.inetAddress, port))
            return ses
        } catch (t: Throwable) { runCatching { s.close() }; throw t }
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
    val boundPort get() = ss?.localPort ?: port

    fun start() {
        val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(port)); ss = s
        val u = try { DatagramSocket(null as SocketAddress?).also { it.reuseAddress = true; it.bind(InetSocketAddress(s.localPort)); runCatching { it.receiveBufferSize = 256 * 1024; it.sendBufferSize = 256 * 1024 } } }
            catch (t: Throwable) { runCatching { s.close() }; throw t }
        us = u; running = true
        Thread({ acceptLoop(s) }, "mml-accept").apply { isDaemon = true }.start()
        Thread({ udpLoop(u) }, "mml-sudp").apply { isDaemon = true }.start()
        log("监听 TCP/UDP ${s.localPort}")
    }

    fun stop() {
        running = false
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
                val h = MmWire.udpPeek(buf, dp.length) ?: continue
                val ses = byConn[h.second] ?: continue
                if ((dp.address as InetAddress) != ses.peerIp) continue   // 来源 IP 必须是 TCP 对端
                ses.onDatagram(buf, dp.length, dp.socketAddress)
            } catch (t: Throwable) { if (running) log("UDP 接收出错：${t.message}") }
        }
    }
}
