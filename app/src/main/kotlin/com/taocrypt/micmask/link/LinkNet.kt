package com.taocrypt.micmask.link

import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/*
 * 传音的网络层（只用 java.net，不依赖 Android，可在 JVM 上做回环测试）。
 * 客户端 = 发送端（手机 → MicYou 电脑端 / 另一台 MicMask），服务端 = 接收端（MicMask 当「电脑端」）。
 * 行为对照 MicYou micyou-core/src/transport/tcp.rs、udp.rs 与安卓 AudioEngine.kt，见 MicYouWire.kt 文件头。
 */

private fun now() = System.currentTimeMillis()

/** 发送端：连 MicYou 协议的接收端，发 48k 单声道 PCM16，10 ms 一包 */
class MicYouClient(
    private val host: String,
    private val port: Int = MicYou.DEFAULT_PORT,
    /** true = 纯 TCP（USB/adb reverse 时用，音频也走 TCP 帧）；false = TCP 控制 + UDP 音频（MicYou 默认的 both） */
    val tcpOnly: Boolean = false,
    private val log: (String) -> Unit = {},
) {
    @Volatile var closed = false; private set
    @Volatile var closeReason = ""; private set
    @Volatile var rttMs = -1; private set
    /** 接收端每秒下发的统计（MicYou 桌面端与 MicMask 接收端都会发）；没收到为 null */
    @Volatile var peerStats: StatsMsg? = null; private set
    @Volatile var lastRxMs = 0L; private set
    @Volatile var sentPackets = 0L; private set
    @Volatile var sentBytes = 0L; private set
    @Volatile var sendErrors = 0L; private set
    val sessionId: Long = (java.security.SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1L)

    private var sock: Socket? = null
    private var out: OutputStream? = null
    private var udp: DatagramSocket? = null
    private var udpAddr: InetSocketAddress? = null
    private var seq = 0
    private var lastPingMs = 0L
    private val pend = ShortArray(MicYou.FRAME)
    private var pendN = 0
    private val wLock = Any()

    /** 连接 + 握手 + connect；失败抛异常（调用方显示原因） */
    fun open(timeoutMs: Int = 3000) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = 5000
        sock = s
        val o = s.getOutputStream(); out = o
        val inp = DataInputStream(s.getInputStream())
        o.write(MicYou.HANDSHAKE_CLIENT.toByteArray(Charsets.US_ASCII)); o.flush()
        val hs = ByteArray(MicYou.HANDSHAKE_SERVER.length)
        inp.readFully(hs)
        if (String(hs, Charsets.US_ASCII) != MicYou.HANDSHAKE_SERVER) { runCatching { s.close() }; throw IllegalStateException("握手失败：对方不是 MicYou 协议") }
        writeFrame(MicYouCodec.connect(sessionId, "android", if (tcpOnly) "tcp" else "both"))
        if (!tcpOnly) {
            udp = DatagramSocket().also { runCatching { it.sendBufferSize = 256 * 1024 } }
            udpAddr = InetSocketAddress(s.inetAddress, port + MicYou.UDP_PORT_OFFSET)
        }
        lastRxMs = now()
        Thread({ readLoop(inp) }, "link-tx-rd").apply { isDaemon = true }.start()
        log("握手成功 ${s.inetAddress.hostAddress}:$port ${if (tcpOnly) "纯 TCP" else "TCP+UDP"} 会话=$sessionId")
    }

    private fun readLoop(inp: DataInputStream) {
        val h = ByteArray(8)
        try {
            while (!closed) {
                try { inp.readFully(h) } catch (e: SocketTimeoutException) { if (now() - lastRxMs > HEARTBEAT_MS) throw IllegalStateException("心跳超时"); continue }
                val n = MicYouCodec.tcpHeaderLen(h)
                val p = ByteArray(n); inp.readFully(p)
                lastRxMs = now()
                val w = MicYouCodec.decode(p)
                w.ping?.let { writeFrame(MicYouCodec.pong(it)) }
                w.pong?.let { val r = (now() - it).toInt(); if (r >= 0) rttMs = r }
                w.stats?.let { peerStats = it }
            }
        } catch (t: Throwable) { close(if (closed) closeReason else "连接断开：${t.message ?: t.javaClass.simpleName}") }
    }

    private fun writeFrame(payload: ByteArray) {
        val f = MicYouCodec.tcpFrame(payload)
        synchronized(wLock) { val o = out ?: throw IllegalStateException("未连接"); o.write(f); o.flush() }
    }

    /** 追加 PCM（任意长度），凑满 10 ms 就发一包；顺带每秒发一次 ping、检查心跳 */
    fun send(s: ShortArray, off: Int = 0, len: Int = s.size) {
        if (closed) return
        var i = off; val end = off + len
        while (i < end) {
            val k = minOf(MicYou.FRAME - pendN, end - i)
            System.arraycopy(s, i, pend, pendN, k); pendN += k; i += k
            if (pendN == MicYou.FRAME) { sendFrame(pend); pendN = 0 }
        }
        val t = now()
        if (t - lastPingMs >= 1000) { lastPingMs = t; runCatching { writeFrame(MicYouCodec.ping(t)) }.onFailure { close("发送 ping 失败：${it.message}") } }
        if (t - lastRxMs > HEARTBEAT_MS) close("心跳超时（${HEARTBEAT_MS / 1000} s 没收到对方消息）")
    }

    private fun sendFrame(f: ShortArray) {
        val payload = MicYouCodec.audioPcm16(seq, f, 0, f.size, sessionId, now())
        seq = if (seq == Int.MAX_VALUE) 0 else seq + 1
        try {
            if (tcpOnly) writeFrame(payload)
            else { val d = MicYouCodec.udpDatagram(payload); udp!!.send(DatagramPacket(d, d.size, udpAddr)) }
            sentPackets++; sentBytes += payload.size + 8; sendErrors = 0
        } catch (t: Throwable) {
            // 与 MicYou 安卓端相同的熔断：连续失败太多次就断开
            if (++sendErrors >= 50) close("连续发送失败：${t.message}")
        }
    }

    fun close(reason: String = "已停止") {
        if (closed) return
        closeReason = reason; closed = true
        runCatching { sock?.close() }; runCatching { udp?.close() }
        log("关闭：$reason")
    }

    companion object { const val HEARTBEAT_MS = 5000L }
}

/**
 * 接收端：MicYou 协议的最小服务端（TCP 8554 握手 + 控制，UDP 8555 音频）。同一时间只服务一个发送端，新连接接管旧连接（与 MicYou 桌面端一致）。
 * 只解 PCM；Opus 包计数后丢弃（[opusPackets]）。
 */
class MicYouServer(
    private val port: Int = MicYou.DEFAULT_PORT,
    /** 已按序号重排、转成 48k 单声道的 PCM（在 UDP/TCP 读线程里回调，回调里不要阻塞太久） */
    private val onPcm: (ShortArray) -> Unit,
    /** 连接状态变化：peer=null 表示断开 */
    private val onPeer: (peer: String?, reason: String) -> Unit,
    private val log: (String) -> Unit = {},
    /** 回给发送端的「接收缓冲毫秒」（用于延迟估计），由上层提供 */
    private val bufMs: () -> Int = { 0 },
) {
    @Volatile var running = false; private set
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    @Volatile private var cur: Conn? = null
    private val reorder = SeqReorder()
    private val jitter = JitterMeter()
    private val rxLock = Any()

    @Volatile var packets = 0L; private set
    @Volatile var bytes = 0L; private set
    @Volatile var opusPackets = 0L; private set
    @Volatile var rejected = 0L; private set
    @Volatile var lastAudioMs = 0L; private set
    val lost get() = reorder.lost
    val late get() = reorder.late
    val reordered get() = reorder.reordered
    val jitterMs get() = jitter.jitterMs
    val rttMs get() = cur?.rttMs ?: -1
    val peer get() = cur?.peerIp?.hostAddress
    val transport get() = cur?.transport ?: ""

    private inner class Conn(val s: Socket, val peerIp: InetAddress, val sessionId: Long, val bound: Boolean, val transport: String) {
        @Volatile var alive = true
        @Volatile var rttMs = -1
        @Volatile var boundSession = sessionId
        @Volatile var isBound = bound
        val out: OutputStream = s.getOutputStream()
        fun write(p: ByteArray) { val f = MicYouCodec.tcpFrame(p); synchronized(this) { out.write(f); out.flush() } }
        fun close() { alive = false; runCatching { s.close() } }
    }

    /** 绑定端口并开始监听；端口被占用等失败抛异常 */
    fun start() {
        val ss = ServerSocket(); ss.reuseAddress = true; ss.bind(InetSocketAddress(port)); server = ss
        if (ss.localPort != port) { runCatching { ss.close() }; throw IllegalStateException("TCP 端口 $port 绑定异常") }
        // 注意：不能在 DatagramSocket.apply {} 里直接写 port（会解析成 DatagramSocket.getPort() = -1）
        val udpPort = port + MicYou.UDP_PORT_OFFSET
        val us = try { DatagramSocket(null as java.net.SocketAddress?).also { it.reuseAddress = true; it.bind(InetSocketAddress(udpPort)); runCatching { it.receiveBufferSize = 256 * 1024 } } }
            catch (t: Throwable) { runCatching { ss.close() }; throw t }
        udp = us
        running = true
        Thread({ acceptLoop(ss) }, "link-rx-accept").apply { isDaemon = true }.start()
        Thread({ udpLoop(us) }, "link-rx-udp").apply { isDaemon = true }.start()
        log("监听 TCP $port / UDP ${port + MicYou.UDP_PORT_OFFSET}")
    }

    fun stop() {
        running = false
        cur?.close(); cur = null
        runCatching { server?.close() }; runCatching { udp?.close() }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try { ss.accept() } catch (t: Throwable) { if (running) log("accept 失败：${t.message}"); break }
            Thread({ handle(s) }, "link-rx-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(s: Socket) {
        val ip = s.inetAddress
        try {
            s.tcpNoDelay = true
            s.soTimeout = 5000
            val inp = DataInputStream(s.getInputStream())
            val hs = ByteArray(MicYou.HANDSHAKE_CLIENT.length)
            inp.readFully(hs)
            if (String(hs, Charsets.US_ASCII) != MicYou.HANDSHAKE_CLIENT) { log("拒绝 ${ip.hostAddress}：握手串不对"); s.close(); return }
            s.getOutputStream().apply { write(MicYou.HANDSHAKE_SERVER.toByteArray(Charsets.US_ASCII)); flush() }
            s.soTimeout = 10_000
            val h = ByteArray(8)
            inp.readFully(h)
            val first = ByteArray(MicYouCodec.tcpHeaderLen(h)); inp.readFully(first)
            val w = MicYouCodec.decode(first)
            val c = w.connect
            val conn = Conn(s, ip, c?.sessionId ?: 0L, c != null, c?.transport?.lowercase() ?: "")
            // 新连接接管旧连接
            synchronized(rxLock) {
                cur?.let { log("新连接接管旧连接 ${it.peerIp.hostAddress}"); it.close() }
                cur = conn
                reorder.reset(); jitter.reset()
            }
            log("握手成功 ${ip.hostAddress} 平台=${c?.platform?.ifEmpty { "?" } ?: "旧版"} 传输=${conn.transport.ifEmpty { "both" }} 会话=${conn.sessionId}")
            onPeer(ip.hostAddress, "")
            handleMsg(conn, w)
            val pinger = Thread({
                var tick = 0
                while (conn.alive && running) {
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                    runCatching {
                        conn.write(MicYouCodec.ping(now()))
                        if (++tick % 2 == 0) {
                            val total = packets + lost
                            val loss = if (total > 0) lost.toFloat() / total else 0f
                            conn.write(MicYouCodec.stats(conn.rttMs.coerceAtLeast(0), jitter.jitterMs, loss, bufMs() + conn.rttMs.coerceAtLeast(0) / 2))
                        }
                    }.onFailure { conn.close() }
                }
            }, "link-rx-ping").apply { isDaemon = true }
            pinger.start()
            while (conn.alive && running) {
                inp.readFully(h)
                val p = ByteArray(MicYouCodec.tcpHeaderLen(h)); inp.readFully(p)
                handleMsg(conn, MicYouCodec.decode(p))
            }
        } catch (t: Throwable) {
            if (running && cur?.s === s) log("连接 ${ip.hostAddress} 结束：${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { s.close() }
            var was = false
            synchronized(rxLock) { if (cur?.s === s) { cur = null; was = true } }
            if (was) onPeer(null, if (running) "发送端断开" else "已停止")
        }
    }

    private fun handleMsg(c: Conn, w: Wrapper) {
        w.ping?.let { runCatching { c.write(MicYouCodec.pong(it)) } }
        w.pong?.let { val r = (now() - it).toInt(); if (r >= 0) c.rttMs = r }
        w.audio?.let { onAudio(c, c.peerIp, it, true) }
    }

    private fun udpLoop(us: DatagramSocket) {
        val buf = ByteArray(65535)
        val dp = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                dp.setData(buf)
                us.receive(dp)
                val r = MicYouCodec.udpPayload(buf, dp.length) ?: continue
                val w = runCatching { MicYouCodec.decode(buf, r[0], r[1]) }.getOrNull() ?: continue
                val a = w.audio ?: continue
                val c = cur ?: continue
                onAudio(c, dp.address, a, false)
            } catch (t: Throwable) { if (running) log("UDP 接收出错：${t.message}") }
        }
    }

    /** 会话校验同 MicYou udp.rs try_accept_audio_packet：来源 IP 必须是 TCP 对端；带 connect 的按 sessionId 绑定，旧客户端用前几个包的 sessionId 绑定 */
    private fun onAudio(c: Conn, from: InetAddress, a: AudioPkt, viaTcp: Boolean) {
        if (!MicYouCodec.valid(a)) { rejected++; return }
        if (!viaTcp && from != c.peerIp) { rejected++; return }
        if (c.isBound) { if (a.sessionId != c.boundSession) { rejected++; return } }
        else { if (a.seq !in 0..4) { rejected++; return }; c.boundSession = a.sessionId; c.isBound = true }
        if (a.isFec) return
        synchronized(rxLock) {
            if (cur !== c) return
            packets++; bytes += a.buffer.size
            val t = now(); lastAudioMs = t
            if (a.codec != MicYou.CODEC_PCM) { opusPackets++; return }
            val pcm = MicYouCodec.toMono48k(a) ?: run { rejected++; return }
            jitter.observe(t, a.timestamp, pcm.size * 1000 / MicYou.SR)
            reorder.push(a.seq, pcm, onPcm)
        }
    }
}
