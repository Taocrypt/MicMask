package com.taocrypt.micmask.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taocrypt.micmask.link.MmCaps
import com.taocrypt.micmask.link.MmCrypto
import com.taocrypt.micmask.link.MmLink
import com.taocrypt.micmask.link.MmLinkClient
import com.taocrypt.micmask.link.MmLinkConfig
import com.taocrypt.micmask.link.MmLinkException
import com.taocrypt.micmask.link.MmLinkServer
import com.taocrypt.micmask.link.MmSession
import com.taocrypt.micmask.link.MmSessionListener
import com.taocrypt.micmask.link.MmStats
import com.taocrypt.micmask.link.P256
import java.util.concurrent.ConcurrentHashMap

/**
 * 电脑版传音引擎（MicMask Link v1，与手机共用协议源码）：
 *  - 接收：本机监听 0.0.0.0:47310（所有网卡），显示配对码；手机连上来发的声音 → 自适应抖动缓冲 → 播放到所选设备（默认 VB-CABLE「CABLE Input」，系统里就多出麦克风「CABLE Output」）。
 *  - 发送：连到开着「接收」的手机（配对码），把所选「输入通道」的声音发过去（电脑/耳机麦克风、任一录音设备，或转发收到的手机声音）。
 *  - 已保存设备 + 自动连接：连接成功后按协议设备 ID 记住；被 mDNS 发现或上次地址可达时自动连，断线按 2→60 秒退避重连。
 */
object DeskLink {
    private val SR = MmLink.SR

    // ---------------------------------------------------------------- 日志（写到 DeskLog：文件 + 内存，自动脱敏）
    fun addLog(s: String) {
        val lv = if (Regex("失败|出错|错误|异常").containsMatchIn(s)) 'W' else 'I'
        val cat = when {
            s.startsWith("接收") || s.startsWith("发送") || s.startsWith("自动连接") || s.contains("配对码") -> "传音"
            s.startsWith("mDNS") -> "网络"
            s.contains("设备") || s.startsWith("VB-CABLE") -> "音频"
            else -> "系统"
        }
        DeskLog.log(lv, cat, s)
    }

    // ---------------------------------------------------------------- 身份
    val deviceId: ByteArray by lazy {
        Settings.get("link_dev_id").takeIf { it.length == 32 }?.let { P256.hex(it) }
            ?: MmCrypto.random(16).also { id -> Settings.set("link_dev_id", hex(id)) }
    }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun defaultName(): String = "MicMask-PC-" + (System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "Windows").take(24)
    var deviceName by mutableStateOf(Settings.get("dev_name").ifBlank { defaultName() }); private set
    fun setName(n: String) {
        val v = n.trim().take(40).ifBlank { defaultName() }
        if (v == deviceName) return
        deviceName = v; Settings.set("dev_name", if (v == defaultName()) "" else v)
        addLog("本机设备名改为「$v」")
        if (rxOn) bg("mdns-rereg") { Discovery.unregister(); Discovery.register(deviceName, MmLink.DEFAULT_PORT, hex(deviceId.copyOf(4)), ::addLog) }
    }

    var code by mutableStateOf(Settings.get("link_code").takeIf { it.length == 6 } ?: MmCrypto.newPairingCode().also { Settings.set("link_code", it) }); private set
    fun regenCode() { code = MmCrypto.newPairingCode(); Settings.set("link_code", code); addLog("已更换配对码") }

    private fun cfg(intent: Int, codec: Int) = MmLinkConfig(deviceId, deviceName, "windows", intent, codec)
    private fun bg(name: String, f: () -> Unit) = Thread({ runCatching(f) }, name).apply { isDaemon = true; start() }

    // ---------------------------------------------------------------- 发现
    var devices by mutableStateOf(listOf<PeerDevice>()); private set
    fun startDiscovery() {
        Discovery.onChange = { l ->
            val old = devices.map { it.name }.toSet()
            devices = l
            // 已保存设备刚出现：清掉它的退避，自动连接马上试
            l.filter { it.name !in old }.forEach { p -> SavedDevices.match(p)?.let { backoff.remove(it.id) } }
        }
        bg("mdns-start") { Discovery.start(::addLog) }
    }

    // ================================================================ 输入通道 / 编码设置

    /** 输入通道 key：phone（手机麦克风 = 收到的手机声音）、default、pcmic（电脑麦克风）、headset（耳机麦克风）、dev:<设备全名>；test:sine 只在自测用 */
    var inputKey by mutableStateOf(Settings.get("in_key").ifBlank { Settings.get("in_dev").takeIf { it.isNotBlank() && it != "系统默认录音设备" }?.let { "dev:$it" } ?: "default" }); private set
    fun setInput(k: String) { inputKey = k; if (!k.startsWith("test:")) Settings.set("in_key", k) }

    /** 0 跟随手机偏好（默认）、1 Opus、2 PCM */
    var codecMode by mutableIntStateOf(Settings.getInt("tx_codec", 0)); private set
    fun setCodec(m: Int) { codecMode = m; Settings.setInt("tx_codec", m) }

    var autoConnect by mutableStateOf(Settings.getBool("auto_connect", true)); private set
    fun setAuto(v: Boolean) { autoConnect = v; Settings.setBool("auto_connect", v); if (v) backoff.clear() }

    /** 把输入通道解析成具体设备；找不到返回 null（调用方回退系统默认并提示） */
    fun resolveInput(key: String, list: List<AudioIO.Dev> = AudioIO.inList): AudioIO.Dev? = when {
        key == "default" -> list.firstOrNull { it.mixer == null } ?: AudioIO.Dev("", null, isDefault = true)
        key == "pcmic" -> list.firstOrNull { it.mixer != null && it.kind == AudioIO.Kind.BUILTIN }
        key == "headset" -> list.firstOrNull { it.mixer != null && it.kind in HEADSET_KINDS }
        key.startsWith("dev:") -> list.firstOrNull { it.name == key.removePrefix("dev:") }
        else -> null
    }
    val HEADSET_KINDS = setOf(AudioIO.Kind.HEADSET_JACK, AudioIO.Kind.USB, AudioIO.Kind.BLUETOOTH)

    /** 设备热插拔回调（AudioIO 监视线程） */
    fun onDevicesChanged(added: List<String>, removed: List<String>, input: Boolean) {
        if (added.isNotEmpty()) addLog("${if (input) "录音" else "播放"}设备接入：${added.joinToString()}")
        if (removed.isNotEmpty()) addLog("${if (input) "录音" else "播放"}设备移除：${removed.joinToString()}")
        if (input && inputKey.startsWith("dev:") && inputKey.removePrefix("dev:") in removed) devNotice = tr("所选录音设备「{0}」已拔出，已改用系统默认录音设备", inputKey.removePrefix("dev:"))
        if (!input && outName.isNotEmpty() && outName in removed) devNotice = tr("所选播放设备「{0}」已拔出，已改用系统默认播放设备", outName)
    }
    /** 设备拔出 / 回退等提示（界面显示，可关闭） */
    var devNotice by mutableStateOf(""); internal set

    // ================================================================ 接收（手机 → 电脑）

    var rxOn by mutableStateOf(false); private set
    var rxStatus by mutableStateOf(""); private set
    var rxPeer by mutableStateOf<String?>(null); private set
    var rxStats by mutableStateOf(""); private set
    val rxLevel = AudioIO.Level()
    var outName by mutableStateOf(Settings.get("out_dev")); private set
    fun setOutput(name: String) { outName = name; Settings.set("out_dev", name) }

    private var server: MmLinkServer? = null
    val playBuf = DeskPlayout()
    @Volatile private var player: AudioIO.Player? = null
    @Volatile private var playerIsDefault = false
    @Volatile private var rxGen = 0

    /** 默认输出：上次选的；没选过用 VB-CABLE「CABLE Input」；都没有用系统默认 */
    fun resolveOutput(list: List<AudioIO.Dev> = AudioIO.outList.ifEmpty { AudioIO.outputs() }): AudioIO.Dev? =
        (if (outName.isNotEmpty()) list.firstOrNull { it.name == outName } else null)
            ?: (if (outName.isEmpty()) list.firstOrNull { AudioIO.isCableInput(it.name) } else null)
            ?: list.firstOrNull { it.mixer == null }

    fun startReceive() {
        if (rxOn) return
        val out = resolveOutput()
        val srv = MmLinkServer(MmLink.DEFAULT_PORT, cfg(MmLink.CAP_RECV, MmLink.CODEC_OPUS), { code },
            listenerFor = { object : MmSessionListener {
                override fun onPcm(s: MmSession, pcm: ShortArray) {
                    playBuf.write(pcm); rxLevel.observe(pcm)
                    if (txRelay) txSession?.sendPcm(pcm)   // 输入通道 = 手机麦克风：再转发给发送目标
                }
                override fun onClosed(s: MmSession, reason: String) { if (server?.current === s || server?.current == null) { rxPeer = null; rxStatus = tr("手机已断开：{0}", reason) } }
            } },
            onSession = { ses ->
                ses.localBufMs = { playBuf.sizeMs() }
                rxPeer = "${ses.peerName}（${ses.peerIp.hostAddress}）"
                rxStatus = tr("已连接 {0}", ses.peerName)
                playBuf.clear()
            },
            log = { addLog("接收：$it") })
        try { srv.start() } catch (t: Throwable) {
            rxStatus = tr("端口 {0} 被占用或无法监听：{1}。可能另一个 MicMask 已在运行", MmLink.DEFAULT_PORT, t.message ?: ""); addLog(rxStatus); return
        }
        server = srv
        playerIsDefault = out == null || out.mixer == null
        player = AudioIO.Player(out, playBuf).start()
        rxOn = true; Settings.setBool("rx_on", true)
        rxStatus = tr("等待手机连接…（手机「传音」页 → 发送 → 选本机 → 输入配对码）")
        val gen = ++rxGen
        bg("mdns-reg") { Discovery.register(deviceName, MmLink.DEFAULT_PORT, hex(deviceId.copyOf(4)), ::addLog) }
        addLog("接收开启：播放到 ${out?.name?.ifEmpty { null } ?: "系统默认播放设备"}，监听 0.0.0.0:${MmLink.DEFAULT_PORT}（本机 ${Discovery.localIps().joinToString { it.hostAddress }}）")
        Thread({ rxMonitor(gen) }, "mm-rxmon").apply { isDaemon = true; start() }
    }

    /** 立刻返回（界面不卡）：状态先改，关端口/取消 mDNS 广播在后台做 */
    fun stopReceive(remember: Boolean = true) {
        if (!rxOn) return
        rxOn = false; rxGen++
        if (remember) Settings.setBool("rx_on", false)
        val srv = server; server = null
        val pl = player; player = null
        pl?.stop()
        rxPeer = null; rxStatus = tr("已关闭"); rxStats = ""
        rxLevel.reset()
        addLog("接收关闭")
        bg("rx-stop") { srv?.stop(); Discovery.unregister(); playBuf.clear() }
    }
    private fun stopReceiveSync() {
        rxOn = false; rxGen++
        player?.stop(); player = null
        server?.stop(); server = null
    }

    fun toggleReceive() { if (rxOn) stopReceive() else startReceive() }

    private fun rxMonitor(gen: Int) {
        var n = 0
        while (rxOn && rxGen == gen) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            val pl = player
            if (pl != null && pl.lost) {
                if (!playerIsDefault) {
                    // 播放设备拔出/出错：回退到系统默认播放设备，继续接收
                    addLog("播放设备出错（${pl.error}），回退到系统默认播放设备")
                    devNotice = tr("播放设备「{0}」不可用（{1}），已改用系统默认播放设备", outName.ifEmpty { tr("系统默认") }, pl.error ?: "")
                    playerIsDefault = true
                    player = AudioIO.Player(AudioIO.Dev("", null, isDefault = true), playBuf).start()
                } else {
                    // 系统默认也打不开（没有任何播放设备）：报一次，不再反复重试
                    addLog("播放设备出错（${pl.error}），没有可用的播放设备")
                    rxStatus = tr("没有可用的播放设备：{0}", pl.error ?: ""); player = null
                }
            }
            val s = server?.current ?: continue
            playBuf.jitterMs = s.jitterMs
            val bufMs = playBuf.sizeMs()
            val total = s.rxPackets + s.rxLost
            rxStats = tr("包 {0} · 丢包 {1}（{2}%）· 抖动 {3} ms · 缓冲 {4}/{5} ms · RTT {6} · {7} · 断流 {8} · 漂移裁剪 {9} ms",
                s.rxPackets, s.rxLost, if (total > 0) "%.1f".format(s.rxLost * 100.0 / total) else "0.0", "%.1f".format(s.jitterMs), bufMs, playBuf.targetMs(),
                if (s.rttMs >= 0) "${s.rttMs} ms" else "—",
                when (s.rxCodec) { MmLink.CODEC_OPUS -> "Opus（FEC ${s.fecFrames} / PLC ${s.plcFrames}）"; MmLink.CODEC_PCM16 -> "PCM"; else -> tr("还没收到音频") },
                playBuf.underruns, playBuf.trimmedMs)
            if (++n % 10 == 0) {
                val (avg, pk) = rxLevel.drain()
                addLog("接收 ← ${s.peerName}：包 ${s.rxPackets} · 丢包 ${s.rxLost} · 抖动 ${"%.1f".format(s.jitterMs)} ms · 缓冲 $bufMs/${playBuf.targetMs()} ms · RTT ${s.rttMs} ms · " +
                    "${when (s.rxCodec) { MmLink.CODEC_OPUS -> "Opus FEC ${s.fecFrames} PLC ${s.plcFrames}"; MmLink.CODEC_PCM16 -> "PCM"; else -> "无音频" }} · 断流 ${playBuf.underruns} · 漂移裁剪 ${playBuf.trimmedMs} ms · 电平 平均 ${"%.1f".format(avg)} / 峰值 ${"%.1f".format(pk)} dBFS")
            }
        }
    }

    // ================================================================ 发送（电脑 → 手机）

    enum class TxPhase { IDLE, CONNECTING, ON }
    var txPhase by mutableStateOf(TxPhase.IDLE); private set
    val txOn get() = txPhase != TxPhase.IDLE
    var txStatus by mutableStateOf(""); private set
    var txStats by mutableStateOf(""); private set
    var txTarget by mutableStateOf(""); private set
    /** 发送卡里显示的错误（人话），与状态分开，便于高亮 */
    var txError by mutableStateOf(""); private set

    @Volatile private var txSession: MmSession? = null
    @Volatile private var txRelay = false
    @Volatile private var txSource: AudioIO.Source? = null
    @Volatile private var srcIsDefault = false
    @Volatile private var txGen = 0
    /** 用户手动停止发送后不再自动连接，直到用户再次手动开始 */
    @Volatile var userStopped = false; private set
    private val backoff = ConcurrentHashMap<String, Pair<Long, Long>>()   // id → (下次尝试时间, 当前间隔)

    /** 当前电平来源：发送中 = 发送音源；输入通道是手机 = 收到的手机声音；否则 = 预览 */
    fun meter(): AudioIO.Level? = txSource?.level ?: if (inputKey == "phone") rxLevel else preview?.level

    /** 连接并开始发送。[code] 6 位；[saved] 非空 = 已保存设备（自动连接/一键连接）；[auto] = 自动连接发起 */
    fun startSend(host: String, port: Int, label: String, code: String, auto: Boolean = false, savedId: String? = null) {
        if (txOn) return
        val code6 = code.filter(Char::isDigit)
        if (code6.length != 6) { txError = tr("请输入手机「接收」页显示的 6 位配对码"); return }
        if (host.isBlank()) { txError = tr("请先在上面选择一台手机，或手动输入手机 IP"); return }
        if (!auto) userStopped = false
        txError = ""
        txPhase = TxPhase.CONNECTING; txTarget = label.ifEmpty { host }
        txStatus = tr("正在连接 {0}（{1}:{2}）…", txTarget, host, port)
        val gen = ++txGen
        val mode = codecMode
        Thread({ txLoop(gen, host, port, label, code6, auto, savedId, mode) }, "mm-tx").apply { isDaemon = true }.start()
    }

    private fun setTxCodec(s: MmSession, codec: Int) {
        runCatching { MmSession::class.java.getDeclaredField("txCodec").apply { isAccessible = true }.setInt(s, codec) }
            .onFailure { addLog("改发送编码失败：${it.message}") }
    }

    private fun txLoop(gen: Int, host: String, port: Int, label: String, code6: String, auto: Boolean, savedId: String?, mode: Int) {
        var ses: MmSession? = null
        try {
            val prefer = if (mode == 2) MmLink.CODEC_PCM16 else MmLink.CODEC_OPUS
            val s = MmLinkClient.connect(host, port, cfg(MmLink.CAP_SEND, prefer), code6, host == "127.0.0.1",
                object : MmSessionListener {
                    override fun onCaps(s: MmSession, peer: MmCaps) {
                        // 跟随手机：协议 v1 由发送方偏好决定编码（MmCaps.pickCodec），这里按接收方（手机）的偏好改回来
                        if (mode == 0) {
                            val want = if (peer.preferCodec == MmLink.CODEC_OPUS && peer.opus) MmLink.CODEC_OPUS else MmLink.CODEC_PCM16
                            if (s.txCodec != want) setTxCodec(s, want)
                            addLog("发送：编码跟随手机偏好 → ${if (want == MmLink.CODEC_OPUS) "Opus" else "PCM"}")
                        }
                    }
                    override fun onClosed(s: MmSession, reason: String) { if (txSession === s) { txStatus = tr("连接已断开：{0}", reason) } }
                },
                { addLog("发送：$it") }, timeoutMs = if (auto) 1500 else 3000)
            ses = s
            if (gen != txGen) { s.close(tr("电脑端已停止")); return }
            val id = hex(s.peerHello.deviceId)
            SavedDevices.remember(id, s.peerName, host, port, code6)
            backoff.remove(id); savedId?.let { backoff.remove(it) }
            txSession = s
            txPhase = TxPhase.ON; txTarget = SavedDevices.byId(id)?.title ?: s.peerName
            txStatus = tr("已连接 {0}{1}", txTarget, if (auto) tr("（自动连接）") else "")
            val drift = TxDrift()
            openSource(s, drift)
            addLog("发送开始 → ${s.peerName}（$host:$port），输入通道 $inputKey${if (auto) "，自动连接" else ""}")
            var n = 0
            var lastLevelLog = System.currentTimeMillis()
            while (gen == txGen && !s.closed) {
                Thread.sleep(500)
                val cur = txSource
                if (cur != null && cur.lost && !txRelay && srcIsDefault && txError.isEmpty()) txError = tr("录音设备出错：{0}", cur.error ?: "")
                if (cur != null && cur.lost && !txRelay && !srcIsDefault) {
                    srcIsDefault = true
                    addLog("发送音源出错（${cur.error}），回退到系统默认录音设备")
                    devNotice = tr("录音设备不可用（{0}），已改用系统默认录音设备", cur.error ?: "")
                    cur.stop()
                    txSource = AudioIO.Capturer(AudioIO.Dev("", null, isDefault = true)) { f -> if (!drift.drop(f, s.peerStats)) s.sendPcm(f) }.start()
                }
                if (++n % 2 == 0) {
                    val ps = s.peerStats
                    txStats = tr("已发 {0} 包 · {1} · {2} · RTT {3}", s.txPackets, if (s.udpTx) "UDP" else "TCP",
                        when (s.txCodec) { MmLink.CODEC_OPUS -> "Opus"; MmLink.CODEC_PCM16 -> "PCM"; else -> tr("等待对方能力") },
                        if (s.rttMs >= 0) "${s.rttMs} ms" else "—") +
                        (ps?.let { tr(" · 对端丢包 {0}% · 手机缓冲 {1} ms · 延迟约 {2} ms", "%.1f".format(it.loss * 100), it.bufMs, it.latencyMs + (txSource?.let { 15 } ?: 0)) } ?: "") +
                        (if (drift.dropped > 0) tr(" · 追帧 {0}", drift.dropped) else "")
                }
                if (System.currentTimeMillis() - lastLevelLog >= 10_000) {
                    lastLevelLog = System.currentTimeMillis()
                    val lv = txSource?.level?.drain() ?: (-120f to -120f)
                    val ps = s.peerStats
                    addLog("发送 → ${s.peerName}：已发 ${s.txPackets} 包 · ${if (s.udpTx) "UDP" else "TCP"} · ${if (s.txCodec == MmLink.CODEC_OPUS) "Opus" else "PCM"} · RTT ${s.rttMs} ms" +
                        (ps?.let { " · 对端丢包 ${"%.1f".format(it.loss * 100)}% · 手机缓冲 ${it.bufMs} ms · 对端估计延迟 ${it.latencyMs} ms" } ?: "") +
                        " · 输入电平 平均 ${"%.1f".format(lv.first)} / 峰值 ${"%.1f".format(lv.second)} dBFS · 采集积压丢弃 ${(txSource?.dropped ?: 0) * 1000 / SR} ms · 追帧 ${drift.dropped}")
                    if (lv.second < -60f && !txRelay) addLog("提示：所选音源 10 秒内几乎没有信号（峰值 < -60 dBFS），可能选到了静音设备或没有程序往这条虚拟线播放")
                }
            }
            if (s.closed && gen == txGen) {
                txError = tr("连接已断开：{0}", s.closeReason)
                if (autoConnect && !userStopped) txError += tr("。稍后自动重连")
            }
        } catch (t: Throwable) {
            if (gen != txGen) return
            val msg = connectError(t, host, port)
            txError = msg; txStatus = ""
            addLog("发送连接失败（$host:$port）：${t.javaClass.simpleName}: ${t.message}")
            val sid = savedId
            if (sid != null) {
                if (t is MmLinkException && t.code == MmLink.E_BAD_CODE) {
                    SavedDevices.update(sid) { it.copy(code = "") }   // 配对码已变：不再用旧码自动连，避免把手机锁住
                    txError = tr("「{0}」的配对码已变，请点它重新输入配对码", label)
                }
                val (_, iv) = backoff[sid] ?: (0L to 1000L)
                val next = (iv * 2).coerceAtMost(60_000)
                backoff[sid] = (System.currentTimeMillis() + next) to next
            }
        } finally {
            if (gen == txGen) stopSendInternal()
            if (ses != null && !ses.closed) ses.close(tr("电脑端已停止"))
        }
    }

    private fun openSource(s: MmSession, drift: TxDrift): AudioIO.Source? {
        stopPreview()
        val key = inputKey
        txRelay = key == "phone"
        if (txRelay) {
            if (!rxOn) startReceive()
            txSource = null
            return null
        }
        val sink: (ShortArray) -> Unit = { f -> if (!drift.drop(f, s.peerStats)) s.sendPcm(f) }
        val src: AudioIO.Source = if (key == "test:sine") AudioIO.SineSource(sink).start() else {
            val dev = resolveInput(key) ?: run {
                devNotice = tr("输入通道「{0}」没有找到对应设备，已改用系统默认录音设备", inputLabel(key))
                AudioIO.Dev("", null, isDefault = true)
            }
            srcIsDefault = dev.mixer == null
            AudioIO.Capturer(dev, onFrame = sink).start()
        }
        txSource = src
        return src
    }

    fun inputLabel(key: String): String = when {
        key == "phone" -> tr("手机麦克风")
        key == "default" -> tr("系统默认录音设备")
        key == "pcmic" -> tr("电脑麦克风")
        key == "headset" -> tr("耳机麦克风")
        key.startsWith("dev:") -> key.removePrefix("dev:")
        else -> key
    }

    /** 用户点「停止发送」 */
    fun stopSend() { userStopped = true; txGen++; stopSendInternal(); txStatus = tr("已停止"); txError = "" }

    private fun stopSendInternal() {
        txSource?.stop(); txSource = null
        txRelay = false
        val s = txSession; txSession = null
        if (s != null && !s.closed) bg("tx-close") { s.close(tr("电脑端已停止")) }
        txPhase = TxPhase.IDLE; txStats = ""
    }

    /** 一键连接扫描到的设备：已保存且有配对码直接连，否则返回 false（界面弹框要配对码） */
    fun connectPeer(p: PeerDevice, code: String? = null): Boolean {
        val sv = SavedDevices.match(p)
        val c = code ?: sv?.code?.takeIf { it.length == 6 } ?: return false
        startSend(p.host, p.port, sv?.title ?: p.name, c, savedId = sv?.id)
        return true
    }

    fun connectSaved(d: SavedDev, code: String? = null): Boolean {
        val c = code ?: d.code.takeIf { it.length == 6 } ?: return false
        val p = devices.firstOrNull { d.matches(it) }
        startSend(p?.host ?: d.host, p?.port ?: d.port, d.title, c, savedId = d.id)
        return true
    }

    /** 托盘「开始发送」：最近成功连过的已保存设备 */
    fun quickTarget(): SavedDev? = SavedDevices.list.filter { it.code.length == 6 }.maxByOrNull { it.lastOk }

    fun connectError(t: Throwable, host: String, port: Int): String = when {
        t is MmLinkException && t.code == MmLink.E_BAD_CODE -> tr("配对码不对：请核对手机「传音 › 接收」上显示的 6 位数字")
        t is MmLinkException && t.code == MmLink.E_BUSY -> tr("配对码错得太多，手机暂时锁定 1 分钟，请稍后再试")
        t is MmLinkException && t.code == MmLink.E_VERSION -> tr("两边版本不兼容：把手机和电脑上的 MicMask 都更新到最新版再试")
        t is MmLinkException -> tr("对方不是 MicMask 自有协议：手机「传音」页的协议请选「MicMask 自有」")
        t is java.net.SocketTimeoutException && (t.message ?: "").contains("connect", true) ->
            tr("连接超时：{0}:{1} 没有回应。请确认手机开着「接收」、和电脑在同一网络（或同一个组网），IP 没填错", host, port)
        t is java.net.SocketTimeoutException -> tr("连上了，但对方没有完成握手：手机可能不在「接收」状态，或该端口是别的程序")
        t is java.net.ConnectException -> tr("连接被拒绝：{0}:{1} 上没有程序在监听。请在手机「传音」页打开「接收」（默认端口 47310）", host, port)
        t is java.net.NoRouteToHostException -> tr("到 {0} 的网络不通：不在同一网段、路由器开了 AP 隔离，或被防火墙拦截", host)
        t is java.net.UnknownHostException -> tr("地址无效：{0}", host)
        t is java.io.EOFException || t is java.net.SocketException -> tr("连接被对方中断：{0}", t.message ?: t.javaClass.simpleName)
        else -> tr("连接失败：{0}", t.message ?: t.javaClass.simpleName)
    }

    // ---------------------------------------------------------------- 自动连接 / 重连

    @Volatile private var autoThread: Thread? = null
    @Volatile private var running = true

    @Synchronized fun startAuto() {
        if (autoThread != null) return
        autoThread = Thread({
            while (running) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                if (!autoConnect || txOn || userStopped) continue
                val now = System.currentTimeMillis()
                val cand = SavedDevices.list.filter { it.auto && it.code.length == 6 }.sortedByDescending { it.lastOk }
                for (d in cand) {
                    val (next, _) = backoff[d.id] ?: (0L to 1000L)
                    if (now < next) continue
                    val p = devices.firstOrNull { d.matches(it) }
                    val host = p?.host ?: d.host
                    if (host.isBlank()) continue
                    // 没被 mDNS 看到的设备：上次地址最少 5 秒试一次
                    if (p == null && backoff[d.id] == null) backoff[d.id] = (now + 5000) to 4000L
                    addLog("自动连接 → ${d.title}（$host）")
                    startSend(host, p?.port ?: d.port, d.title, d.code, auto = true, savedId = d.id)
                    break
                }
            }
        }, "mm-auto").apply { isDaemon = true; start() }
    }

    // ---------------------------------------------------------------- 电平预览（没在发送时也能看所选音源有没有信号）

    @Volatile private var preview: AudioIO.Capturer? = null
    @Volatile private var previewKey = ""
    @Synchronized fun startPreview(key: String) {
        if (txOn || key == "phone" || key.startsWith("test:")) { stopPreview(); return }
        if (preview != null && previewKey == key) return
        stopPreview()
        val dev = resolveInput(key) ?: AudioIO.Dev("", null, isDefault = true)
        previewKey = key
        preview = AudioIO.Capturer(dev) { }.start()
    }
    @Synchronized fun stopPreview() { preview?.stop(); preview = null; previewKey = "" }
    fun previewError(): String? = preview?.error

    // ---------------------------------------------------------------- 退出

    /**
     * 退出清理：发送、接收、mDNS、统计上报并行进行，总共最多等 [timeoutMs]；返回各部分耗时（毫秒，-1 = 超时没做完）。
     * 调用方随后直接结束进程（Runtime.halt），不再跑 JmDNS 的关闭钩子。
     */
    fun shutdown(timeoutMs: Long = 1500): Map<String, Long> {
        running = false
        val t0 = System.currentTimeMillis()
        val res = ConcurrentHashMap<String, Long>()
        val parts = listOf<Pair<String, () -> Unit>>(
            "send" to { txGen++; stopSendInternal(); txSession?.close() },
            "recv" to { stopReceiveSync() },
            // JmDNS 关闭要发告别包、等内部任务，CI 实测单个实例 >1.5 秒；只给 300 ms，没关完就随进程结束（对方记录按 TTL 过期）
            "mdns" to { Discovery.stop(300) },
            "preview" to { stopPreview() },
            "beacon" to { DeskBeacon.flush() },
        )
        val ts = parts.map { (n, f) -> Thread({ val a = System.currentTimeMillis(); runCatching(f); res[n] = System.currentTimeMillis() - a }, "exit-$n").apply { isDaemon = true; start() } }
        for (t in ts) { val w = t0 + timeoutMs - System.currentTimeMillis(); if (w > 0) t.join(w) }
        // mdns 自己只等 300 ms；它的线程返回即算完成（JmDNS 的关闭线程留给进程结束）
        return parts.associate { (n, _) -> n to (res[n] ?: -1L) }
    }
}

/**
 * 发送端追帧（推断的时钟漂移补偿）：手机回报的缓冲（STATS.bufMs）比本次会话里见过的最低值高出 60 ms 以上，
 * 说明电脑这边送得比手机消耗得快（声卡时钟漂移，或手机注入端偶尔卡），就在之后的静音帧里少发几帧（每秒最多 5 帧 = 50 ms），
 * 找不到静音帧时 50 帧后强制丢一帧。手机端的缓冲语义以手机实现为准；这里只看「相对涨幅」，不看绝对值。
 */
class TxDrift {
    private var lastStats: MmStats? = null
    private var baseline = Int.MAX_VALUE
    private var budget = 0
    private var waited = 0
    var dropped = 0L; private set

    fun drop(f: ShortArray, st: MmStats?): Boolean {
        if (st != null && st !== lastStats) {
            lastStats = st
            if (st.bufMs < baseline) baseline = st.bufMs
            val over = st.bufMs - baseline - 60
            budget = if (over > 0 && st.bufMs > 80) minOf(5, over / 20 + 1) else 0
        }
        if (budget <= 0) return false
        var pk = 0; for (v in f) { val a = kotlin.math.abs(v.toInt()); if (a > pk) pk = a }
        if (pk < 330 || ++waited > 50) { budget--; waited = 0; dropped++; return true }   // 330 ≈ -40 dBFS
        return false
    }
}
