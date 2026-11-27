package com.taocrypt.micmask.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taocrypt.micmask.link.MmCrypto
import com.taocrypt.micmask.link.MmLink
import com.taocrypt.micmask.link.MmLinkClient
import com.taocrypt.micmask.link.MmLinkConfig
import com.taocrypt.micmask.link.MmLinkException
import com.taocrypt.micmask.link.MmLinkServer
import com.taocrypt.micmask.link.MmSession
import com.taocrypt.micmask.link.MmSessionListener
import com.taocrypt.micmask.link.P256
import com.taocrypt.micmask.link.PlayoutBuffer

/**
 * 电脑版传音引擎（MicMask Link v1，与手机共用协议源码）：
 *  - 接收：本机监听 47310，显示配对码；手机连上来发的声音 → 抖动缓冲 → 播放到所选设备（默认 VB-CABLE「CABLE Input」，系统里就多出麦克风「CABLE Output」）。
 *  - 发送：连到开着「接收」的手机（输入手机显示的配对码），把电脑上所选录音设备的声音发过去，手机把它注入 QQ/微信/游戏。
 *    想发 RVC 软件的声音：让 RVC 输出到某个播放设备，这里选该设备对应的录音端（如另一条虚拟线的 Output）；按进程抓声音是下一步。
 */
object DeskLink {
    const val PRIME_MS = 60
    private val SR = MmLink.SR

    // ---------------------------------------------------------------- 界面状态
    var log by mutableStateOf(listOf<String>()); private set
    var rxOn by mutableStateOf(false); private set
    var rxStatus by mutableStateOf(""); private set
    var rxPeer by mutableStateOf<String?>(null); private set
    var rxStats by mutableStateOf(""); private set
    var code by mutableStateOf(Settings.get("link_code").takeIf { it.length == 6 } ?: MmCrypto.newPairingCode().also { Settings.set("link_code", it) }); private set
    var txOn by mutableStateOf(false); private set
    var txStatus by mutableStateOf(""); private set
    var txStats by mutableStateOf(""); private set
    var devices by mutableStateOf(listOf<PeerDevice>()); private set
    var inLevel by mutableStateOf(0f); private set

    fun addLog(s: String) {
        val line = java.time.LocalTime.now().withNano(0).toString() + "  " + s
        println(line)
        log = (log + line).takeLast(200)
    }

    val deviceId: ByteArray by lazy {
        Settings.get("link_dev_id").takeIf { it.length == 32 }?.let { P256.hex(it) }
            ?: MmCrypto.random(16).also { id -> Settings.set("link_dev_id", id.joinToString("") { "%02x".format(it) }) }
    }

    fun deviceName(): String = "MicMask-PC-" + (runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: System.getenv("COMPUTERNAME") ?: "Windows").take(24)

    fun regenCode() { code = MmCrypto.newPairingCode(); Settings.set("link_code", code); addLog("已更换配对码") }

    private fun cfg(intent: Int, codec: Int) = MmLinkConfig(deviceId, deviceName(), "windows", intent, codec)

    fun startDiscovery() {
        Discovery.onChange = { devices = it }
        Thread { Discovery.start(::addLog) }.apply { isDaemon = true }.start()
    }

    // ================================================================ 接收（手机 → 电脑）

    private var server: MmLinkServer? = null
    private val playBuf = PlayoutBuffer(SR * PRIME_MS / 1000, SR * (PRIME_MS + 120) / 1000)
    private var player: AudioIO.Player? = null
    private var rxMon: Thread? = null

    fun startReceive(outDevice: AudioIO.Dev?) {
        if (rxOn) return
        val srv = MmLinkServer(MmLink.DEFAULT_PORT, cfg(MmLink.CAP_RECV, MmLink.CODEC_OPUS), { code },
            listenerFor = { object : MmSessionListener {
                override fun onPcm(s: MmSession, pcm: ShortArray) = playBuf.write(pcm)
                override fun onClosed(s: MmSession, reason: String) { if (server?.current === s || server?.current == null) { rxPeer = null; rxStatus = "发送端已断开：$reason" } }
            } },
            onSession = { ses ->
                ses.localBufMs = { playBuf.size() * 1000 / SR }
                rxPeer = "${ses.peerName}（${ses.peerIp.hostAddress}）"
                rxStatus = "已连接 ${ses.peerName}"
                playBuf.clear()
            },
            log = { addLog("接收：$it") })
        try { srv.start() } catch (t: Throwable) { rxStatus = "端口 ${MmLink.DEFAULT_PORT} 被占用或无法监听：${t.message}"; addLog(rxStatus); return }
        server = srv
        player = AudioIO.Player(outDevice, playBuf).start()
        rxOn = true
        rxStatus = "等待手机连接…（手机「传音」页 → 发送 → 选本机 → 输入配对码）"
        Thread { Discovery.register(deviceName(), MmLink.DEFAULT_PORT, deviceId.copyOf(4).joinToString("") { "%02x".format(it) }, ::addLog) }.apply { isDaemon = true }.start()
        addLog("接收开启：播放到 ${outDevice?.name ?: "系统默认播放设备"}，本机 ${Discovery.localIps().joinToString { it.hostAddress }} 端口 ${MmLink.DEFAULT_PORT}")
        rxMon = Thread({ rxMonitor() }, "mm-rxmon").apply { isDaemon = true; start() }
    }

    fun stopReceive() {
        if (!rxOn) return
        rxOn = false
        Discovery.unregister()
        server?.stop(); server = null
        player?.stop(); player = null
        playBuf.clear()
        rxPeer = null; rxStatus = "已关闭"; rxStats = ""
        addLog("接收关闭")
    }

    private fun rxMonitor() {
        var n = 0
        while (rxOn) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            player?.error?.let { rxStatus = "播放设备出错：$it"; }
            val s = server?.current ?: continue
            val bufMs = playBuf.size() * 1000 / SR
            val total = s.rxPackets + s.rxLost
            rxStats = "包 ${s.rxPackets} · 丢包 ${s.rxLost}（${if (total > 0) "%.1f".format(s.rxLost * 100.0 / total) else "0.0"}%）· 抖动 ${"%.1f".format(s.jitterMs)} ms · 缓冲 $bufMs ms · RTT ${if (s.rttMs >= 0) "${s.rttMs} ms" else "—"} · " +
                (when (s.rxCodec) { MmLink.CODEC_OPUS -> "Opus（FEC 补 ${s.fecFrames} / PLC 补 ${s.plcFrames}）"; MmLink.CODEC_PCM16 -> "PCM"; else -> "未收到音频" }) +
                " · 断流 ${playBuf.underruns}"
            if (++n % 10 == 0) addLog("接收 ← ${s.peerName}：$rxStats")
        }
    }

    // ================================================================ 发送（电脑 → 手机）

    @Volatile private var txSession: MmSession? = null
    private var capturer: AudioIO.Capturer? = null

    fun startSend(host: String, port: Int, label: String, pairCode: String, inDevice: AudioIO.Dev?, pcm: Boolean) {
        if (txOn) return
        val code6 = pairCode.filter(Char::isDigit)
        if (code6.length != 6) { txStatus = "请输入手机「接收」页显示的 6 位配对码"; return }
        txOn = true; txStatus = "正在连接 $host:$port…"
        Thread({
            try {
                val ses = MmLinkClient.connect(host, port, cfg(MmLink.CAP_SEND, if (pcm) MmLink.CODEC_PCM16 else MmLink.CODEC_OPUS), code6, host == "127.0.0.1",
                    object : MmSessionListener { override fun onClosed(s: MmSession, reason: String) { if (txSession === s) { txStatus = "连接已断开：$reason"; stopSend() } } },
                    { addLog("发送：$it") })
                Settings.set("peer_code_" + label.ifEmpty { host }, code6)
                txSession = ses
                txStatus = "已连接 ${ses.peerName}"
                val cap = AudioIO.Capturer(inDevice) { f -> ses.sendPcm(f) }.start()
                capturer = cap
                addLog("发送开始 → ${ses.peerName}（$host:$port），音源 ${inDevice?.name ?: "系统默认录音设备"}")
                var n = 0
                while (txOn && !ses.closed) {
                    Thread.sleep(500)
                    inLevel = cap.level
                    cap.error?.let { txStatus = "录音设备出错：$it" }
                    if (++n % 2 == 0) {
                        val ps = ses.peerStats
                        txStats = "已发 ${ses.txPackets} 包 · ${if (ses.udpTx) "UDP" else "TCP"} · ${when (ses.txCodec) { MmLink.CODEC_OPUS -> "Opus"; MmLink.CODEC_PCM16 -> "PCM"; else -> "等待对方能力" }} · RTT ${if (ses.rttMs >= 0) "${ses.rttMs} ms" else "—"}" +
                            (ps?.let { " · 对端丢包 ${"%.1f".format(it.loss * 100)}% · 延迟约 ${it.latencyMs} ms" } ?: "")
                    }
                    if (n % 20 == 0) addLog("发送 → ${ses.peerName}：$txStats")
                }
            } catch (t: Throwable) {
                txStatus = when {
                    t is MmLinkException && t.code == MmLink.E_BAD_CODE -> "连接失败：配对码不对（看手机「传音 › 接收」上的 6 位数字）"
                    t is MmLinkException && t.code == MmLink.E_BUSY -> "连接失败：配对码错误次数过多，请 1 分钟后再试"
                    t is MmLinkException && t.code == MmLink.E_VERSION -> "连接失败：手机版本不兼容，请更新手机 MicMask"
                    t is MmLinkException -> "连接失败：对方不是 MicMask 自有协议（手机「传音」页协议要选「MicMask 自有」）"
                    else -> "连接失败：${t.message ?: t.javaClass.simpleName}"
                }
                addLog(txStatus)
            } finally { stopSend() }
        }, "mm-tx").apply { isDaemon = true }.start()
    }

    fun stopSend() {
        capturer?.stop(); capturer = null
        txSession?.let { if (!it.closed) it.close("电脑端已停止") }
        txSession = null
        if (txOn) { txOn = false; if (txStatus.startsWith("已连接")) txStatus = "已停止" }
        inLevel = 0f
    }

    fun savedCode(key: String) = Settings.get("peer_code_$key")

    fun shutdown() { stopSend(); stopReceive(); Discovery.stop() }
}
