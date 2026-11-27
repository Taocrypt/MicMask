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

fun main() {
    testSpake2Vector(); testSpake2WrongCode(); testHkdf(); testGcmAndWire(); testOpus(); testReorder()
    testLoopback(MmLink.CODEC_PCM16); testLoopback(MmLink.CODEC_OPUS); testTcpOnly()
    println("\n通过 $passes，失败 $fails")
    if (fails > 0) kotlin.system.exitProcess(1)
}
