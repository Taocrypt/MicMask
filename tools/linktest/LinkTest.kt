// MicMask Link v1 纯逻辑单测：kotlinc 独立编译运行，不依赖 Android / Gradle。用法：bash tools/linktest/run.sh
import com.taocrypt.micmask.link.*
import java.math.BigInteger

private var fails = 0
private var passes = 0
private fun check(name: String, ok: Boolean, detail: String = "") { if (ok) { passes++; println("PASS $name") } else { fails++; println("FAIL $name $detail") } }
private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
private fun unhex(s: String) = P256.hex(s.replace(Regex("\\s"), ""))

fun testSpake2Vector() {
    // RFC 9382 附录 B 第一组：A='server', B='client'
    val w = BigInteger("2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f", 16)
    val x = BigInteger("43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729", 16)
    val y = BigInteger("dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be", 16)
    val a = Spake2(true, "server".toByteArray(), "client".toByteArray(), w, x)
    val b = Spake2(false, "server".toByteArray(), "client".toByteArray(), w, y)
    check("spake2 pA", hex(a.myMsg) == "04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c", hex(a.myMsg))
    check("spake2 pB", hex(b.myMsg) == "0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7", hex(b.myMsg))
    val ra = a.finish(b.myMsg); val rb = b.finish(a.myMsg)
    check("spake2 K", hex(ra.k) == "0412af7e89717850671913e6b469ace67bd90a4df8ce45c2af19010175e37eed69f75897996d539356e2fa6a406d528501f907e04d97515fbe83db277b715d3325")
    check("spake2 K 双方一致", ra.k.contentEquals(rb.k))
    check("spake2 Ke", hex(ra.ke) == "0e0672dc86f8e45565d338b0540abe69", hex(ra.ke))
    check("spake2 A conf", hex(ra.myConfirm) == "58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0", hex(ra.myConfirm))
    check("spake2 B conf", hex(rb.myConfirm) == "d3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b", hex(rb.myConfirm))
    check("spake2 确认交叉", ra.peerConfirm.contentEquals(rb.myConfirm) && rb.peerConfirm.contentEquals(ra.myConfirm))
}

fun testSpake2WrongCode() {
    val a = Spake2(true, "c".toByteArray(), "s".toByteArray(), MmCrypto.pairingW("123456"))
    val b = Spake2(false, "c".toByteArray(), "s".toByteArray(), MmCrypto.pairingW("123457"))
    val ra = a.finish(b.myMsg); val rb = b.finish(a.myMsg)
    check("spake2 配对码不同 → 确认值不同", !ra.myConfirm.contentEquals(rb.peerConfirm) && !ra.ke.contentEquals(rb.ke))
    val c = Spake2(true, "c".toByteArray(), "s".toByteArray(), MmCrypto.pairingW("654321"))
    val d = Spake2(false, "c".toByteArray(), "s".toByteArray(), MmCrypto.pairingW("654321"))
    val rc = c.finish(d.myMsg); val rd = d.finish(c.myMsg)
    check("spake2 配对码相同 → 密钥一致", rc.ke.contentEquals(rd.ke) && rc.myConfirm.contentEquals(rd.peerConfirm))
    check("点解码拒绝非曲线点", runCatching { P256.decode(byteArrayOf(4) + ByteArray(64) { 1 }) }.isFailure)
}

fun testHkdf() {
    // RFC 5869 测试用例 1
    val okm = MmCrypto.hkdf(unhex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), unhex("000102030405060708090a0b0c"), unhex("f0f1f2f3f4f5f6f7f8f9"), 42)
    check("hkdf RFC5869 #1", hex(okm) == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex(okm))
}

fun testGcmAndWire() {
    val ke = MmCrypto.random(16); val nc = MmCrypto.random(16); val ns = MmCrypto.random(16)
    val c = MmKeys.derive(ke, nc, ns, true); val s = MmKeys.derive(ke, nc, ns, false)
    // TCP SEALED
    val f = MmWire.seal(c.txCtrl, 7, byteArrayOf(MmLink.M_PING.toByte(), 1, 2, 3))
    val inp = java.io.DataInputStream(f.inputStream())
    val (t, body) = MmWire.readFrame(inp)
    val rw = ReplayWindow()
    check("sealed 帧类型", t == MmLink.T_SEALED)
    check("sealed 往返", MmWire.open(s.rxCtrl, body, rw).contentEquals(byteArrayOf(MmLink.M_PING.toByte(), 1, 2, 3)))
    check("sealed 重放拒绝", runCatching { MmWire.open(s.rxCtrl, body, rw) }.isFailure)
    val bad = body.copyOf(); bad[bad.size - 1] = (bad[bad.size - 1] + 1).toByte()
    check("sealed 篡改拒绝", runCatching { MmWire.open(s.rxCtrl, bad, ReplayWindow()) }.isFailure)
    check("方向密钥不同", runCatching { MmWire.open(c.rxCtrl, body, ReplayWindow()) }.isFailure)
    // UDP 音频
    val pcm = ShortArray(MmLink.FRAME) { (it * 37 - 9000).toShort() }
    val a = MmAudio(42, 123456, MmLink.CODEC_PCM16, 0, MmLink.FRAME, MmWire.pcmToBytes(pcm, 0, pcm.size))
    val d = MmWire.udpAudio(c.txAudio, 0x11223344, 5, a)
    val h = MmWire.udpPeek(d, d.size)
    check("udp 头", h != null && h.first == MmLink.U_AUDIO && h.second == 0x11223344 && h.third == 5)
    val r = MmWire.udpOpenAudio(s.rxAudio, d, d.size)
    check("udp 音频往返", r != null && r.seq == 42 && r.ts == 123456 && MmWire.bytesToPcm(r.data).contentEquals(pcm))
    val d2 = d.copyOf(); d2[14] = 99   // 改 seq（在 AAD 里）
    check("udp 头篡改拒绝", MmWire.udpOpenAudio(s.rxAudio, d2, d2.size) == null)
    check("udp 长度", d.size == MmLink.UDP_AUDIO_HDR + 960 + MmLink.TAG && d.size <= 1472, "${d.size}")
    val p = MmWire.udpProbe(c.txAudio, 1, 6, false, 999L)
    check("udp 探测往返", MmWire.udpOpenProbe(s.rxAudio, p, p.size) == 999L)
    // HELLO / CAPS / 版本协商
    val hc = MmHello(1, 1, ByteArray(16) { 1 }, "手机", "android", ByteArray(16) { 2 })
    val hd = MmHello.decode(hc.encode())
    check("hello 往返", hd.name == "手机" && hd.platform == "android" && hd.deviceId.contentEquals(hc.deviceId))
    check("版本协商 1–1 vs 1–3 → 1", MmHello.negotiate(hc, hc.copy(maxVer = 3)) == 1)
    check("版本协商 无交集 → -1", MmHello.negotiate(hc, hc.copy(minVer = 2, maxVer = 3)) == -1)
    val caps = MmCaps(MmLink.CAP_SEND or MmLink.CAP_OPUS or MmLink.CAP_PCM, MmLink.CODEC_OPUS, 48000)
    val cd = BIn(caps.encode()).let { it.u8(); MmCaps.decode(it) }
    check("caps 往返", cd == caps)
    check("编码选择：对方不支持 Opus → PCM", MmCaps.pickCodec(caps, MmCaps(MmLink.CAP_RECV or MmLink.CAP_PCM, 0, 0)) == MmLink.CODEC_PCM16)
    check("编码选择：双方支持 Opus → Opus", MmCaps.pickCodec(caps, MmCaps(MmLink.CAP_RECV or MmLink.CAP_PCM or MmLink.CAP_OPUS, 0, 0)) == MmLink.CODEC_OPUS)
    // 防重放窗口
    val w = ReplayWindow()
    check("重放窗口", w.accept(5) && w.accept(3) && !w.accept(3) && w.accept(100) && !w.accept(30) && w.accept(99))
}

fun testOpus() {
    val enc = MmOpusEncoder(); val dec = MmOpusDecoder()
    val n = 100
    val src = ShortArray(MmLink.FRAME * n) { (8000 * Math.sin(2 * Math.PI * 440 * it / 48000.0)).toInt().toShort() }
    val outAll = ShortArray(src.size)
    var bytes = 0
    for (f in 0 until n) {
        val pkt = enc.encode(src, f * MmLink.FRAME); bytes += pkt.size
        val o = dec.decode(pkt); System.arraycopy(o, 0, outAll, f * MmLink.FRAME, o.size)
        if (f == 0) check("opus 帧长 480", o.size == MmLink.FRAME, "${o.size}")
    }
    // Opus 有约 6.5 ms（312 样本）算法延迟：按最佳对齐比较 SNR
    var best = -1e9; var bestLag = 0
    for (lag in 0..800) {
        var sig = 0.0; var err = 0.0
        for (i in 10 * MmLink.FRAME until src.size - 1000) { val e = src[i].toDouble() - outAll[i + lag]; sig += src[i].toDouble() * src[i]; err += e * e }
        val snr = 10 * Math.log10(sig / err.coerceAtLeast(1.0))
        if (snr > best) { best = snr; bestLag = lag }
    }
    check("opus 往返 SNR ≥ 10 dB", best >= 10, "SNR=${"%.1f".format(best)} lag=$bestLag")
    println("    opus 平均包长 ${bytes / n} 字节（≈ ${bytes * 8 / n / 10} kbps），对齐延迟 $bestLag 样本，SNR ${"%.1f".format(best)} dB")
    val pl = dec.conceal(2, enc.encode(src, 0))
    check("opus PLC/FEC 补 2 帧", pl.size == 2 && pl.all { it.size == MmLink.FRAME } && dec.plcFrames == 1L && dec.fecFrames == 1L)
}

fun testReorder() {
    val r = SeqReorder<Int>()
    val got = ArrayList<Int>(); val gaps = ArrayList<Pair<Int, Int>>()
    for (s in listOf(0, 1, 3, 2, 4, 8, 9, 10, 11)) r.push(s, s, { got.add(it) }) { n, next -> gaps.add(n to next) }
    check("重排 + 丢包", got == listOf(0, 1, 2, 3, 4, 8, 9, 10, 11) && gaps == listOf(3 to 8) && r.lost == 3L && r.reordered == 1L, "$got $gaps")
}

fun testLoopback(codec: Int) {
    val tag = if (codec == MmLink.CODEC_OPUS) "Opus" else "PCM"
    val code = MmCrypto.newPairingCode()
    val srvRx = java.util.concurrent.LinkedBlockingQueue<ShortArray>()
    val cliRx = java.util.concurrent.LinkedBlockingQueue<ShortArray>()
    val srvCfg = MmLinkConfig(MmCrypto.random(16), "电脑", "windows", MmLink.CAP_SEND or MmLink.CAP_RECV, codec)
    val cliCfg = MmLinkConfig(MmCrypto.random(16), "手机", "android", MmLink.CAP_SEND or MmLink.CAP_RECV, codec)
    var srvSes: MmSession? = null
    val srv = MmLinkServer(0, srvCfg, { code }, { object : MmSessionListener { override fun onPcm(s: MmSession, pcm: ShortArray) { srvRx.add(pcm) } } }, { srvSes = it }, { println("    [服务端] $it") })
    srv.start()
    val port = srv.boundPort
    // 配对码错
    val e = runCatching { MmLinkClient.connect("127.0.0.1", port, cliCfg, if (code == "000000") "111111" else "000000", false, object : MmSessionListener {}) }.exceptionOrNull()
    check("$tag 回环：配对码错被拒", e is MmLinkException && e.code == MmLink.E_BAD_CODE, "$e")
    val cli = MmLinkClient.connect("127.0.0.1", port, cliCfg, code, false, object : MmSessionListener { override fun onPcm(s: MmSession, pcm: ShortArray) { cliRx.add(pcm) } }, { println("    [客户端] $it") })
    val t0 = System.currentTimeMillis()
    while ((srvSes?.peerCaps == null || cli.peerCaps == null || !cli.udpTx || srvSes?.udpTx != true) && System.currentTimeMillis() - t0 < 4000) Thread.sleep(20)
    check("$tag 回环：能力交换", cli.peerCaps != null && srvSes?.peerCaps != null)
    check("$tag 回环：UDP 双向打通", cli.udpTx && srvSes?.udpTx == true)
    val frames = 50
    val tone = ShortArray(MmLink.FRAME * frames) { (6000 * Math.sin(2 * Math.PI * 300 * it / 48000.0)).toInt().toShort() }
    for (f in 0 until frames) { cli.sendPcm(tone, f * MmLink.FRAME, MmLink.FRAME); srvSes!!.sendPcm(tone, f * MmLink.FRAME, MmLink.FRAME); Thread.sleep(2) }
    Thread.sleep(500)
    check("$tag 回环：手机→电脑 收到 ${srvRx.size}/$frames 帧", srvRx.size >= frames - 3)
    check("$tag 回环：电脑→手机 收到 ${cliRx.size}/$frames 帧", cliRx.size >= frames - 3)
    if (codec == MmLink.CODEC_PCM16) check("$tag 回环：PCM 无损", srvRx.peek()?.contentEquals(tone.copyOfRange(0, MmLink.FRAME)) == true)
    check("$tag 回环：编码协商", cli.txCodec == codec && srvSes!!.rxCodec == codec)
    Thread.sleep(1200)
    check("$tag 回环：RTT 测得", cli.rttMs >= 0 && cli.peerStats != null, "rtt=${cli.rttMs} stats=${cli.peerStats}")
    cli.close("测试结束")
    Thread.sleep(300)
    check("$tag 回环：BYE 后服务端会话关闭", srvSes!!.closed)
    srv.stop()
}

fun testTcpOnly() {
    val code = "246810"
    val got = java.util.concurrent.atomic.AtomicInteger()
    val srv = MmLinkServer(0, MmLinkConfig(MmCrypto.random(16), "电脑", "windows", MmLink.CAP_RECV), { code }, { object : MmSessionListener { override fun onPcm(s: MmSession, pcm: ShortArray) { got.incrementAndGet() } } }, {})
    srv.start()
    val cli = MmLinkClient.connect("127.0.0.1", srv.boundPort, MmLinkConfig(MmCrypto.random(16), "手机", "android", MmLink.CAP_SEND, MmLink.CODEC_PCM16), code, true, object : MmSessionListener {})
    val t0 = System.currentTimeMillis(); while (cli.peerCaps == null && System.currentTimeMillis() - t0 < 3000) Thread.sleep(20)
    repeat(20) { cli.sendPcm(ShortArray(MmLink.FRAME)) }
    Thread.sleep(400)
    check("纯 TCP（USB）音频 20/20", got.get() == 20 && !cli.udpTx, "got=${got.get()} udp=${cli.udpTx}")
    cli.close(); srv.stop()
}

fun testNetHelpers() {
    check("地址解析 IPv4", MmNet.parseHostPort(" 192.168.1.5 ", 47310) == ("192.168.1.5" to 47310))
    check("地址解析 IPv4:端口", MmNet.parseHostPort("10.144.144.2:5000", 47310) == ("10.144.144.2" to 5000))
    check("地址解析 [v6]:端口", MmNet.parseHostPort("[fd7a:115c:a1e0::1]:47311", 47310) == ("fd7a:115c:a1e0::1" to 47311))
    check("地址解析 裸 v6", MmNet.parseHostPort("fd7a:115c:a1e0::1", 47310) == ("fd7a:115c:a1e0::1" to 47310))
    check("地址解析 主机名", MmNet.parseHostPort("pc.local", 47310) == ("pc.local" to 47310))
    check("地址解析 非法端口", MmNet.parseHostPort("1.2.3.4:99999", 47310) == null && MmNet.parseHostPort("1.2.3.4:x", 47310) == null && MmNet.parseHostPort("  ", 47310) == null)
    check("网卡类型 Tailscale", MmNet.kindOf("tun0", "100.101.2.3") == ("vpn" to "Tailscale") && MmNet.kindOf("tailscale0", "100.64.0.1").second == "Tailscale")
    check("网卡类型 ZeroTier", MmNet.kindOf("ztabcdef12", "172.23.1.2") == ("vpn" to "ZeroTier"))
    check("网卡类型 EasyTier", MmNet.kindOf("tun0", "10.144.144.3") == ("vpn" to "EasyTier") && MmNet.kindOf("et_abc", "10.0.0.2").second == "EasyTier")
    check("网卡类型 普通", MmNet.kindOf("wlan0", "192.168.1.9").first == "wifi" && MmNet.kindOf("eth0", "192.168.1.9") == ("ethernet" to "") && MmNet.kindOf("tun1", "10.8.0.2") == ("vpn" to "") && MmNet.kindOf("rmnet_data0", "10.1.2.3").first == "cellular")
    check("CGNAT 判定", MmNet.isCgnat("100.64.0.1") && MmNet.isCgnat("100.127.255.1") && !MmNet.isCgnat("100.128.0.1") && !MmNet.isCgnat("10.0.0.1"))
    check("跨网段判定", !MmNet.looksRemote("192.168.3.4") && MmNet.looksRemote("10.144.144.2") && MmNet.looksRemote("100.100.1.1") && !MmNet.looksRemote("127.0.0.1"))
    val la = MmNet.localAddrs()
    check("本机地址枚举不抛异常", la.none { it.ip.startsWith("127.") || it.ip.startsWith("fe80") || it.ip.startsWith("169.254.") })
    println("    本机地址：" + la.joinToString { "${it.ip}(${it.iface}/${it.kind}${if (it.vpn.isNotEmpty()) "/" + it.vpn else ""})" })
    // MTU：PCM 一帧 UDP 数据报 ≤ 1200
    val ke = MmCrypto.random(16); val k = MmKeys.derive(ke, MmCrypto.random(16), MmCrypto.random(16), true)
    val d = MmWire.udpAudio(k.txAudio, 1, 1, MmAudio(0, 0, MmLink.CODEC_PCM16, 0, MmLink.FRAME, ByteArray(MmLink.FRAME * 2)))
    check("UDP 数据报 ≤ ${MmLink.MAX_UDP_DATAGRAM}（PCM ${d.size}）", d.size <= MmLink.MAX_UDP_DATAGRAM && d.size + 48 <= 1280)
}

private fun failOf(block: () -> Unit): Throwable? = runCatching(block).exceptionOrNull()
private val cfgC = MmLinkConfig(MmCrypto.random(16), "手机", "android", MmLink.CAP_SEND)

fun testConnectFailures() {
    // 拒绝：绑定后关掉的端口
    val free = java.net.ServerSocket(0).let { val p = it.localPort; it.close(); p }
    val r = failOf { MmLinkClient.connect("127.0.0.1", free, cfgC, "123456", false, object : MmSessionListener {}) }
    check("连接失败：拒绝", r is MmConnectFail && r.kind == MmNet.F_REFUSED && r.stage == 0, "$r")
    // 主机名解析失败
    val u = failOf { MmLinkClient.connect("no-such-host.invalid", 47310, cfgC, "123456", false, object : MmSessionListener {}, timeoutMs = 1000) }
    check("连接失败：无法到达（解析失败）", u is MmConnectFail && u.kind == MmNet.F_UNREACHABLE, "$u")
    // 连上不回握手
    val silent = java.net.ServerSocket(0); val held = ArrayList<java.net.Socket>()
    Thread { runCatching { while (true) held.add(silent.accept()) } }.apply { isDaemon = true }.start()
    val t0 = System.currentTimeMillis()
    val h = failOf { MmLinkClient.connect("127.0.0.1", silent.localPort, cfgC, "123456", false, object : MmSessionListener {}, handshakeTimeoutMs = 600) }
    check("连接失败：握手超时", h is MmConnectFail && h.kind == MmNet.F_HS_TIMEOUT && h.stage == 1 && System.currentTimeMillis() - t0 < 3000, "$h")
    // 连上即断
    val closer = java.net.ServerSocket(0)
    Thread { runCatching { while (true) closer.accept().close() } }.apply { isDaemon = true }.start()
    val c = failOf { MmLinkClient.connect("127.0.0.1", closer.localPort, cfgC, "123456", false, object : MmSessionListener {}) }
    check("连接失败：握手中断开", c is MmConnectFail && c.kind == MmNet.F_CLOSED, "$c")
    // 对方不是 MicMask（回了别的协议）
    val other = java.net.ServerSocket(0)
    Thread { runCatching { while (true) { val x = other.accept(); x.getOutputStream().write(MmWire.frame(MmLink.T_HELLO, "HTTP/1.1 400 Bad Request\r\n".toByteArray())); x.getOutputStream().flush() } } }.apply { isDaemon = true }.start()
    val o = failOf { MmLinkClient.connect("127.0.0.1", other.localPort, cfgC, "123456", false, object : MmSessionListener {}) }
    check("连接失败：不是 MicMask", o is MmLinkException && o.code == MmLink.E_PROTOCOL, "$o")
    // 版本不符：服务端只支持 v2–v3
    val vs = java.net.ServerSocket(0)
    Thread { runCatching { while (true) { val x = vs.accept(); val inp = java.io.DataInputStream(x.getInputStream()); MmWire.readFrame(inp)
        x.getOutputStream().write(MmWire.frame(MmLink.T_HELLO, MmHello(2, 3, ByteArray(16), "新版", "windows", ByteArray(16), 5).encode())); x.getOutputStream().flush() } } }.apply { isDaemon = true }.start()
    val v = failOf { MmLinkClient.connect("127.0.0.1", vs.localPort, cfgC, "123456", false, object : MmSessionListener {}) }
    check("连接失败：版本不符", v is MmLinkException && v.code == MmLink.E_VERSION, "$v")
    listOf(silent, closer, other, vs).forEach { runCatching { it.close() } }; held.forEach { runCatching { it.close() } }
}

/** UDP 被拦：1.5 s 后确认走 TCP、音频照常；放行后自动切到 UDP；再拦截时 UDP_DEAD_MS 内切回 TCP；保活期间不误判 */
fun testUdpFallback() {
    val code = "135790"
    val got = java.util.concurrent.atomic.AtomicInteger()
    var srvSes: MmSession? = null
    val srv = MmLinkServer(0, MmLinkConfig(MmCrypto.random(16), "电脑", "windows", MmLink.CAP_RECV), { code }, { object : MmSessionListener { override fun onPcm(s: MmSession, pcm: ShortArray) { got.incrementAndGet() } } }, { srvSes = it }, {})
    srv.start(); srv.testDropUdp = true
    val id = MmCrypto.random(16)
    val logs = java.util.Collections.synchronizedList(ArrayList<String>())
    val cli = MmLinkClient.connect("127.0.0.1", srv.boundPort, MmLinkConfig(id, "手机", "android", MmLink.CAP_SEND, MmLink.CODEC_PCM16), code, false, object : MmSessionListener {}, { logs.add(it) })
    fun waitFor(ms: Long, f: () -> Boolean): Boolean { val t = System.currentTimeMillis(); while (!f() && System.currentTimeMillis() - t < ms) Thread.sleep(20); return f() }
    waitFor(2000) { cli.peerCaps != null }
    repeat(10) { cli.sendPcm(ShortArray(MmLink.FRAME)) }
    Thread.sleep(1700)
    check("UDP 被拦：音频走 TCP 照常到达", !cli.udpTx && got.get() >= 10, "udp=${cli.udpTx} got=${got.get()}")
    check("UDP 被拦：1.5 s 后日志说明回退", logs.any { it.contains("音频走 TCP") }, logs.toString())
    check("对端设备 ID 可识别", srvSes?.peerId == MmNet.hex(id))
    srv.testDropUdp = false
    check("UDP 放行后自动切到 UDP", waitFor(3500) { cli.udpTx })
    check("服务端同步：收到 UDP_OK", waitFor(1000) { srvSes?.udpTx == true })
    Thread.sleep(MmLink.UDP_DEAD_MS + 800)
    check("UDP 保活期间不误判断开", cli.udpTx)
    srv.testDropUdp = true
    check("UDP 中途中断 → ${MmLink.UDP_DEAD_MS} ms 内切回 TCP", waitFor(MmLink.UDP_DEAD_MS + 1500) { !cli.udpTx })
    val before = got.get(); repeat(10) { cli.sendPcm(ShortArray(MmLink.FRAME)) }
    check("切回 TCP 后音频照常", waitFor(1000) { got.get() >= before + 10 }, "${got.get() - before}")
    cli.close(); srv.stop()
}

private fun waitUntil(ms: Long, f: () -> Boolean): Boolean { val t = System.currentTimeMillis(); while (!f() && System.currentTimeMillis() - t < ms) Thread.sleep(20); return f() }

/** 编码「接收方偏好优先」：纯逻辑 + 回环会话 */
fun testCodecFollow() {
    val snd = MmCaps(MmLink.CAP_SEND or MmLink.CAP_OPUS or MmLink.CAP_PCM or MmLink.CAP_PREF_RX, MmLink.CODEC_OPUS, 48000)
    val rxPcmNew = MmCaps(MmLink.CAP_RECV or MmLink.CAP_OPUS or MmLink.CAP_PCM or MmLink.CAP_PREF_RX, MmLink.CODEC_PCM16, 48000)
    val rxPcmOld = MmCaps(MmLink.CAP_RECV or MmLink.CAP_OPUS or MmLink.CAP_PCM, MmLink.CODEC_PCM16, 48000)
    val rxOpusNew = MmCaps(MmLink.CAP_RECV or MmLink.CAP_OPUS or MmLink.CAP_PCM or MmLink.CAP_PREF_RX, MmLink.CODEC_OPUS, 48000)
    val rxNoOpus = MmCaps(MmLink.CAP_RECV or MmLink.CAP_PCM or MmLink.CAP_PREF_RX, MmLink.CODEC_OPUS, 0)
    check("编码跟随：新版接收方偏好 PCM → PCM", MmCaps.pickCodec(snd, rxPcmNew) == MmLink.CODEC_PCM16)
    check("编码跟随：固定本端（不跟随）→ Opus", MmCaps.pickCodec(snd, rxPcmNew, followReceiver = false) == MmLink.CODEC_OPUS)
    check("编码跟随：旧版接收方（无 PREF_RX）→ 按发送方 Opus", MmCaps.pickCodec(snd, rxPcmOld) == MmLink.CODEC_OPUS)
    check("编码跟随：发送方偏好 PCM、接收方偏好 Opus → Opus", MmCaps.pickCodec(snd.copy(preferCodec = MmLink.CODEC_PCM16), rxOpusNew) == MmLink.CODEC_OPUS)
    check("编码跟随：接收方不支持 Opus → PCM", MmCaps.pickCodec(snd, rxNoOpus) == MmLink.CODEC_PCM16)
    check("编码跟随：caps 带 PREF_RX 位且往返不变", MmLinkConfig(ByteArray(16), "a", "b", MmLink.CAP_RECV).caps.let { c -> c.prefRx && BIn(c.encode()).let { it.u8(); MmCaps.decode(it) } == c })
    // 回环：电脑接收端偏好 PCM，手机发送端偏好 Opus → 跟随后发 PCM；固定模式发 Opus；setTxCodec 可改
    val code = "135790"
    val srv = MmLinkServer(0, MmLinkConfig(MmCrypto.random(16), "电脑", "windows", MmLink.CAP_RECV, MmLink.CODEC_PCM16), { code }, { object : MmSessionListener {} }, {})
    srv.start()
    val c1 = MmLinkClient.connect("127.0.0.1", srv.boundPort, MmLinkConfig(MmCrypto.random(16), "手机", "android", MmLink.CAP_SEND, MmLink.CODEC_OPUS), code, true, object : MmSessionListener {})
    check("编码跟随（回环）：发送端跟随接收方 → PCM", waitUntil(2000) { c1.txCodec == MmLink.CODEC_PCM16 }, "tx=${c1.txCodec}")
    check("编码跟随（回环）：setTxCodec(Opus) 免反射", c1.setTxCodec(MmLink.CODEC_OPUS) == MmLink.CODEC_OPUS && c1.txCodec == MmLink.CODEC_OPUS)
    c1.close()
    val c2 = MmLinkClient.connect("127.0.0.1", srv.boundPort, MmLinkConfig(MmCrypto.random(16), "手机", "android", MmLink.CAP_SEND, MmLink.CODEC_OPUS, followPeerCodec = false), code, true, object : MmSessionListener {})
    check("编码跟随（回环）：固定编码 → Opus", waitUntil(2000) { c2.txCodec == MmLink.CODEC_OPUS }, "tx=${c2.txCodec}")
    c2.close(); srv.stop()
}

/** 同一条会话双向：客户端发送中改意图为「发 + 收」，服务端（电脑）在同一条会话上回发 */
fun testBidirectionalSingleSession() {
    val code = "864209"
    var srvSes: MmSession? = null
    val srvGot = java.util.concurrent.atomic.AtomicInteger(); val cliGot = java.util.concurrent.atomic.AtomicInteger()
    val srv = MmLinkServer(0, MmLinkConfig(MmCrypto.random(16), "电脑", "windows", MmLink.CAP_RECV or MmLink.CAP_SEND, MmLink.CODEC_OPUS),
        { code }, { object : MmSessionListener { override fun onPcm(s: MmSession, pcm: ShortArray) { srvGot.incrementAndGet() } } }, { srvSes = it }, {})
    srv.start()
    val cli = MmLinkClient.connect("127.0.0.1", srv.boundPort, MmLinkConfig(MmCrypto.random(16), "手机", "android", MmLink.CAP_SEND, MmLink.CODEC_OPUS), code, false,
        object : MmSessionListener { override fun onPcm(s: MmSession, pcm: ShortArray) { cliGot.incrementAndGet() } })
    waitUntil(2000) { srvSes?.peerCaps != null && cli.peerCaps != null }
    repeat(10) { srvSes!!.sendPcm(ShortArray(MmLink.FRAME)) }
    Thread.sleep(300)
    check("双向：客户端只声明发送时，服务端不发", cliGot.get() == 0 && srvSes?.peerWantsAudio == false)
    cli.updateIntent(MmLink.CAP_SEND or MmLink.CAP_RECV)
    check("双向：服务端收到新 CAPS（想收）", waitUntil(2000) { srvSes?.peerWantsAudio == true })
    repeat(20) { srvSes!!.sendPcm(ShortArray(MmLink.FRAME)); cli.sendPcm(ShortArray(MmLink.FRAME)); Thread.sleep(2) }
    check("双向：同一条会话两个方向都到", waitUntil(1500) { cliGot.get() >= 18 && srvGot.get() >= 18 }, "cli=${cliGot.get()} srv=${srvGot.get()}")
    cli.close(); srv.stop()
}

fun testPairUri() {
    val u = MmPairUri(listOf("192.168.1.8", "10.126.126.5", "fd7a:115c:a1e0::1"), 47310, "012345", "00112233445566778899aabbccddeeff", "MicMask-小米 15")
    val t = u.encode()
    check("二维码：编码以 micmask://link? 开头", t.startsWith("micmask://link?h="), t)
    check("二维码：往返一致（含 IPv6、中文名）", MmPairUri.parse(t) == u, "${MmPairUri.parse(t)}")
    check("二维码：缺配对码 → null", MmPairUri.parse("micmask://link?h=1.2.3.4&p=47310&c=12") == null)
    check("二维码：别的二维码 → null", MmPairUri.parse("https://example.com/?h=1.2.3.4&c=123456") == null)
    check("二维码：没写端口用默认、坏 ID 置空", MmPairUri.parse("micmask://link?h=1.2.3.4&c=123456&id=zz")?.let { it.port == MmLink.DEFAULT_PORT && it.id == "" } == true)
    // 2.1.1-alpha.14 二维码精简写法：字段名不变、只省略可选字段（p 默认、id、n、IPv6），旧解析照样认
    val q = u.encodeQr()
    check("二维码精简：只含 h、c", q == "micmask://link?h=192.168.1.8,10.126.126.5&c=012345", q)
    check("二维码精简：解析出地址与配对码、端口默认、ID/名字为空", MmPairUri.parse(q) == MmPairUri(listOf("192.168.1.8", "10.126.126.5"), MmLink.DEFAULT_PORT, "012345", "", ""), "${MmPairUri.parse(q)}")
    check("二维码精简：非默认端口保留 p", u.copy(port = 47311).encodeQr().let { MmPairUri.parse(it)?.port == 47311 })
    check("二维码精简：只有 IPv6 时保留 IPv6", MmPairUri(listOf("fd7a:115c:a1e0::1"), 47310, "012345", "", "").encodeQr().let { MmPairUri.parse(it)?.hosts == listOf("fd7a:115c:a1e0::1") })
    check("连接顺序：同网段 > 局域网 > 隧道 > IPv6",
        MmNet.orderHosts(listOf("fd7a::1", "100.100.1.2", "192.168.9.9", "192.168.1.8"), listOf("192.168.1.20")) == listOf("192.168.1.8", "192.168.9.9", "100.100.1.2", "fd7a::1"),
        MmNet.orderHosts(listOf("fd7a::1", "100.100.1.2", "192.168.9.9", "192.168.1.8"), listOf("192.168.1.20")).toString())
}

fun testHeartbeatAndTarget() {
    check("心跳：RTT 100 ms → 5 s", MmSession.heartbeatFor(100, false) == 5000L)
    check("心跳：RTT 913 ms → 7.3 s", MmSession.heartbeatFor(913, false) == 7304L)
    check("心跳：音频走 TCP → 至少 10 s", MmSession.heartbeatFor(100, true) == 10_000L)
    check("心跳：上限 20 s", MmSession.heartbeatFor(8073, true) == 20_000L)
    val bt = MmBufTarget()
    check("缓冲目标：UDP 抖动 10 → 55 ms", bt.base(10f, false, 5) == 55, "${bt.base(10f, false, 5)}")
    check("缓冲目标：TCP 抖动 10 RTT 90 → 120 ms", bt.base(10f, true, 90) == 120, "${bt.base(10f, true, 90)}")
    check("缓冲目标：TCP RTT 300 → 封顶 200 ms", bt.base(20f, true, 300) == 200)
    var t = 0L
    val a = bt.update(10f, true, 90, 0, t)
    t += 1000; val b = bt.update(10f, true, 90, 2, t)
    check("缓冲目标：断流后立即升高（+30）", a == 120 && b == 150, "$a → $b")
    t += 1000; val c = bt.update(10f, false, 5, 2, t)
    t += 5000; val d = bt.update(10f, false, 5, 2, t)
    check("缓冲目标：降低是缓慢的（5 s 最多 10 ms）", c == 150 && d == 140, "$c → $d")
}

private fun tone(n: Int, amp: Int, phase0: Int = 0) = ShortArray(n) { (amp * Math.sin(2 * Math.PI * 220 * (phase0 + it) / 48000.0)).toInt().toShort() }

fun testDriftCtl() {
    val d = MmDriftCtl(targetMs = 80)
    var t = 0L
    for (i in 0..200) { d.report(160, t); t += 10 }
    check("漂移：持续高于目标 → 计划丢帧", d.dropMs > 0, "drop=${d.dropMs}")
    val loud = tone(480, 8000); val quiet = ShortArray(480)
    val r1 = d.decide(loud)
    val r2 = d.decide(quiet)
    check("漂移：有声帧先不丢，低能量帧上丢", r1 == MmDriftCtl.KEEP && r2 == MmDriftCtl.DROP)
    val d2 = MmDriftCtl(targetMs = 80, maxWaitFrames = 20)
    t = 0; for (i in 0..200) { d2.report(160, t); t += 10 }
    var acts = 0; repeat(25) { if (d2.decide(loud) == MmDriftCtl.DROP) acts++ }
    check("漂移：一直有声时等 maxWait 帧后强制执行", acts == 1 && d2.forced == 1L, "acts=$acts forced=${d2.forced}")
    val d3 = MmDriftCtl(targetMs = 80)
    t = 0; for (i in 0..200) { d3.report(30 + (i % 3) * 5, t); t += 10 }
    check("漂移：持续偏低 → 低能量处插帧", d3.insertMs > 0 && d3.decide(quiet) == MmDriftCtl.INSERT)
    val d4 = MmDriftCtl(targetMs = 80)
    t = 0; for (i in 0..200) { d4.report(if (i % 50 == 0) 300 else 85, t); t += 10 }
    check("漂移：偶发尖峰（谷底正常）不丢", d4.dropMs == 0 && d4.insertMs == 0)
    check("漂移：lowWaterMs = 1 s 谷底", d4.lowWaterMs == 85, "${d4.lowWaterMs}")
}

/** 模拟两端时钟漂移：发送比播放快 / 慢 1 %，60 s 后缓冲仍贴近目标、几乎不断流；断流处无硬跳变 */
fun testPlayoutDrift() {
    for (ppm in intArrayOf(10_000, -10_000)) {
        val p = MmPlayout(targetMs = 80)
        var wAcc = 0.0; var phase = 0
        val out = ShortArray(480)
        var maxMs = 0
        for (tick in 0 until 6000) {      // 6000 × 10 ms = 60 s
            wAcc += 480.0 * (1 + ppm / 1e6)
            while (wAcc >= 480) {
                // 语音样：1 s 有声 + 0.5 s 静音 交替
                val voiced = (phase / 48000.0) % 1.5 < 1.0
                p.write(if (voiced) tone(480, 6000, phase) else ShortArray(480)); phase += 480; wAcc -= 480
            }
            p.read(out, 480)
            if (tick > 3000) maxMs = maxOf(maxMs, p.sizeMs())
        }
        val tag = if (ppm > 0) "发送快 1%" else "发送慢 1%"
        check("播放缓冲 $tag：60 s 后水位 ${p.sizeMs()} ms 接近目标 80（后 30 s 最高 $maxMs）", p.sizeMs() in 40..140 && maxMs < 170, "trim=${p.trimmedMs} ins=${p.insertedMs}")
        check("播放缓冲 $tag：断流 ≤ 1 次（${p.underruns}）", p.underruns <= 1, "trim=${p.trimmedMs} ins=${p.insertedMs} forced=${p.drift.forced}")
        if (ppm > 0) check("播放缓冲 $tag：裁剪发生在低能量段（强制 ${p.drift.forced} 次）", p.trimmedMs > 300 && p.drift.forced == 0L, "trim=${p.trimmedMs}")
        else check("播放缓冲 $tag：靠低能量处补帧（补 ${p.insertedMs} ms）", p.insertedMs > 300, "ins=${p.insertedMs}")
    }
    // 断流：最后一段有声音频之后不硬切到 0
    val p = MmPlayout(targetMs = 30)
    p.write(tone(48 * 40, 10000))
    val out = ShortArray(480)
    repeat(3) { p.read(out, 480) }
    val k = p.read(out, 480)   // 只剩 10 ms → 正好读完；下一次断流
    val o2 = ShortArray(480); val k2 = p.read(o2, 480)
    val jump = kotlin.math.abs(o2[0].toInt() - out[479].toInt())
    check("断流：PLC 接缝无硬跳变（跳变 $jump）", k2 == 0 && p.underruns == 1L && jump < 2500 && kotlin.math.abs(o2[479].toInt()) < 50, "k=$k k2=$k2 jump=$jump")
}

fun main() {
    testSpake2Vector(); testSpake2WrongCode(); testHkdf(); testGcmAndWire(); testOpus(); testReorder()
    testLoopback(MmLink.CODEC_PCM16); testLoopback(MmLink.CODEC_OPUS); testTcpOnly()
    testNetHelpers(); testConnectFailures(); testUdpFallback()
    testCodecFollow(); testBidirectionalSingleSession(); testPairUri(); testHeartbeatAndTarget(); testDriftCtl(); testPlayoutDrift()
    println("\n通过 $passes，失败 $fails")
    if (fails > 0) kotlin.system.exitProcess(1)
}
