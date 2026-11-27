package com.taocrypt.micmask.link

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.taocrypt.micmask.L
import com.taocrypt.micmask.inject.FxChain
import com.taocrypt.micmask.inject.MicInjector
import com.taocrypt.micmask.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.NetworkInterface

/** 局域网里发现的 MicYou 协议接收端：MicYou 电脑端，或开着「接收」的 MicMask 手机 */
data class LinkDevice(val name: String, val host: String, val port: Int, val phone: Boolean)

data class LinkState(
    // 发送
    val txOn: Boolean = false,
    val txConnected: Boolean = false,
    val txTarget: String = "",
    val txStatus: String = "",
    val txSource: String = "",
    val txPackets: Long = 0,
    val txKbps: Int = 0,
    val txRtt: Int = -1,
    val txPeerLoss: Float = -1f,
    val txPeerLatency: Int = -1,
    // 接收
    val rxOn: Boolean = false,
    val rxPeer: String? = null,
    val rxStatus: String = "",
    val rxPackets: Long = 0,
    val rxLost: Long = 0,
    val rxBufMs: Int = 0,
    val rxLatency: Int = -1,
    val rxInjecting: Boolean = false,
    val rxPlayback: Boolean = false,
    // 本机
    val name: String = "",
    val ips: List<String> = emptyList(),
    val devices: List<LinkDevice> = emptyList(),
    val discovering: Boolean = false,
)

/**
 * 传音（2.1.1-alpha.11，MVP）：兼容 MicYou 协议的局域网音频收发。全部默认关，用户在「传音」页主动开启才运行。
 *  - 发送：把「对方实际听到的声音」发到 MicYou 电脑端或另一台 MicMask。变声运行中取 shell 注入前的 tapBuf（TX_LINK），没开变声时 App 自己录原始麦克风。
 *  - 接收：本机当 MicYou「电脑端」（TCP 8554 / UDP 8555 + mDNS 广播 _micyou._tcp），收到的 PCM 经实时变声的外部通道（TX_LIVE_PUSH）注入目标 App；
 *    没有 Shizuku/ROOT/系统权限时只能外放播放。只在有音频时接管麦克风，断线或 3 s 没音频就停止注入、还原真麦。
 * 网络都在 App 进程里做（前台服务 [LinkService] 保活）；不做加密，只适合局域网。
 */
@SuppressLint("StaticFieldLeak")
object LinkHub {
    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> = _state
    private fun upd(f: (LinkState) -> LinkState) { synchronized(this) { _state.value = f(_state.value) } }

    @Volatile private var app: Context? = null
    /** 注入目标与录音源（由 MainViewModel 提供，与实时变声一致） */
    @Volatile var targetProvider: (() -> Pair<String, Int>)? = null

    const val PRIME_MS = 80
    const val SR = MicYou.SR

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        upd { it.copy(name = deviceName()) }
    }

    fun deviceName(): String = "MicMask-" + (Build.MODEL ?: "Android").replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Android" }.take(24)

    /** 本机局域网 IPv4（Wi-Fi/热点优先） */
    fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { n -> val nm = n.name.lowercase(); when { nm.startsWith("wlan") -> 0; nm.startsWith("ap") || nm.startsWith("swlan") -> 1; nm.startsWith("rndis") || nm.startsWith("usb") -> 2; else -> 3 } }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { it.hostAddress ?: "" } }
            .filter { it.isNotEmpty() && !it.startsWith("127.") && !it.startsWith("169.254.") }
            .distinct()
    }.getOrDefault(emptyList())

    fun refreshIps() = upd { it.copy(ips = localIps()) }

    val rxInjecting get() = _state.value.rxInjecting
    val busy get() = _state.value.txOn || _state.value.rxOn

    // ================================================================= 发现（NsdManager）

    private var nsd: NsdManager? = null
    private var discL: NsdManager.DiscoveryListener? = null
    private val resolveQ = ArrayDeque<NsdServiceInfo>()
    @Volatile private var resolving = false
    @Volatile private var myServiceName: String? = null

    fun startDiscovery() {
        val c = app ?: return
        if (discL != null) return
        val m = c.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = m
        upd { it.copy(discovering = true, devices = emptyList()) }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { L.i("传音：开始发现 $t") }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { L.w("传音：发现失败 err=$e"); upd { it.copy(discovering = false) }; discL = null }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(si: NsdServiceInfo) {
                if (si.serviceName == myServiceName) return
                synchronized(resolveQ) { resolveQ.addLast(si) }
                pumpResolve()
            }
            override fun onServiceLost(si: NsdServiceInfo) {
                upd { s -> s.copy(devices = s.devices.filterNot { it.name == si.serviceName }) }
            }
        }
        discL = l
        runCatching { m.discoverServices(MicYou.MDNS_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { L.w("传音：discoverServices 失败 ${it.message}"); discL = null; upd { s -> s.copy(discovering = false) } }
    }

    /** 老版本 Android 同时只能解析一个服务，排队逐个解析 */
    private fun pumpResolve() {
        val m = nsd ?: return
        val si = synchronized(resolveQ) { if (resolving) return; resolveQ.removeFirstOrNull()?.also { resolving = true } } ?: return
        @Suppress("DEPRECATION")
        runCatching {
            m.resolveService(si, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, e: Int) { resolving = false; pumpResolve() }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    resolving = false
                    val host = s.host?.hostAddress
                    if (host != null && host.contains('.')) {
                        val phone = s.serviceName.startsWith("MicMask") || runCatching { s.attributes["app"]?.let { String(it) } == "micmask" }.getOrDefault(false)
                        val d = LinkDevice(s.serviceName, host, s.port, phone)
                        if (host !in localIps()) upd { st -> st.copy(devices = (st.devices.filterNot { it.name == d.name } + d).sortedBy { it.name }) }
                    }
                    pumpResolve()
                }
            })
        }.onFailure { resolving = false }
    }

    fun stopDiscovery() {
        val m = nsd; val l = discL
        discL = null
        if (m != null && l != null) runCatching { m.stopServiceDiscovery(l) }
        synchronized(resolveQ) { resolveQ.clear() }
        upd { it.copy(discovering = false) }
    }

    // ================================================================= Wi-Fi 低延迟锁

    private var wifiLock: WifiManager.WifiLock? = null
    private fun holdWifi(on: Boolean) {
        val c = app ?: return
        if (on) {
            if (wifiLock?.isHeld == true) return
            runCatching {
                val wm = c.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "MicMask:link").apply { setReferenceCounted(false); acquire() }
            }.onFailure { L.w("传音：Wi-Fi 低延迟锁失败 ${it.message}") }
        } else { runCatching { wifiLock?.release() }; wifiLock = null }
    }

    private fun serviceSync() {
        val c = app ?: return
        val on = _state.value.txOn || _state.value.rxOn
        holdWifi(on)
        if (on) LinkService.start(c) else LinkService.stop(c)
    }

    // ================================================================= 发送

    @Volatile private var txThread: Thread? = null
    @Volatile private var txRun = false
    @Volatile private var client: MicYouClient? = null
    /** 变声即将开始：先放掉 App 自己的录音，避免和 shell 抢麦（由 MainViewModel 调用） */
    @Volatile private var micYield = false

    fun onLiveStarting() { micYield = true }

    fun hasMic(): Boolean = app?.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startSend(host: String, port: Int, label: String) {
        val c = app ?: return
        if (txRun) return
        val h = host.trim()
        if (h.isEmpty()) { upd { it.copy(txStatus = tr("请先选择设备或输入 IP")) }; return }
        // 127.0.0.1 = USB（电脑端执行 adb reverse tcp:8554 tcp:8554）：adb reverse 只转 TCP，音频走 TCP 帧
        val tcpOnly = h == "127.0.0.1" || h == "localhost"
        txRun = true; micYield = false
        upd { it.copy(txOn = true, txConnected = false, txTarget = label.ifEmpty { "$h:$port" }, txStatus = tr("正在连接 {0}…", "$h:$port"), txPackets = 0, txKbps = 0, txRtt = -1, txPeerLoss = -1f, txPeerLatency = -1) }
        serviceSync()
        txThread = Thread({ txLoop(c, h, port, tcpOnly) }, "link-tx").apply { start() }
    }

    fun stopSend() {
        txRun = false
        client?.close(tr("已停止"))
        txThread?.interrupt()
    }

    private fun txLoop(c: Context, host: String, port: Int, tcpOnly: Boolean) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        var rec: AudioRecord? = null
        var tapOn = false
        var endMsg = tr("已停止")
        try {
            val cl = MicYouClient(host, port, tcpOnly) { L.i("传音：发送 $it") }
            client = cl
            try { cl.open() } catch (t: Throwable) {
                val m = t.message ?: t.javaClass.simpleName
                endMsg = if (m.startsWith("握手失败")) tr("连接失败：对方不是 MicYou 协议的接收端") else tr("连接失败：{0}", m)
                L.w("传音：发送连接 $host:$port 失败：${t.message}")
                return
            }
            upd { it.copy(txConnected = true, txStatus = tr("已连接")) }
            L.i("传音：发送开始 → $host:$port ${if (tcpOnly) "纯 TCP（USB）" else "TCP+UDP"}")
            val buf = ShortArray(MicYou.FRAME)
            var src = ""          // "tap" / "mic"
            var lastStat = System.currentTimeMillis(); var lastPk = 0L; var lastBytes = 0L
            var firstLog = true
            var tapMiss = 0
            var shortReads = 0L
            var txDropped = 0L
            while (txRun && !cl.closed) {
                val wantTap = MicInjector.liveOn && !rxInjecting
                if (wantTap && src != "tap") {
                    rec?.let { runCatching { it.stop() }; it.release() }; rec = null
                    tapOn = MicInjector.linkTap(true)
                    if (tapOn) { src = "tap"; micYield = false; upd { it.copy(txSource = tr("变声输出（对方听到的声音）")) }; L.i("传音：音源 → 变声输出（shell 注入前取点）") }
                    else if (++tapMiss == 1) L.w("传音：注入服务不支持取点（版本不一致？），改用原始麦克风")
                }
                if (!wantTap && src == "tap") { MicInjector.linkTap(false); tapOn = false; src = "" }
                if (src == "tap") {
                    val p = MicInjector.linkPull(SR / 2)
                    if (p == null) { src = ""; tapOn = false; continue }
                    // App 被系统卡住后会一次拉到一大段：超过 120 ms 只发最新的 40 ms，不把积压变成延迟
                    val keep = SR * 40 / 1000
                    if (p.size > SR * 120 / 1000) { txDropped += p.size - keep; cl.send(p, p.size - keep, keep) }
                    else if (p.isNotEmpty()) cl.send(p) else cl.send(p, 0, 0)
                    Thread.sleep(10)
                } else if (micYield) {
                    // 变声马上开始：先不录，等 liveOn 再切到取点（最多 5 s）
                    rec?.let { runCatching { it.stop() }; it.release() }; rec = null
                    Thread.sleep(20)
                    if (!MicInjector.liveOn) { if (++shortReads > 250) { micYield = false; shortReads = 0 } }
                    cl.send(buf, 0, 0)
                } else {
                    if (rec == null) {
                        if (!hasMic()) { endMsg = tr("需要录音权限"); return }
                        rec = openMic()
                        if (rec == null) { endMsg = tr("打开麦克风失败"); return }
                        src = "mic"
                        upd { it.copy(txSource = tr("原始麦克风（未开变声）")) }
                        L.i("传音：音源 → 原始麦克风（App 录音，未开变声）")
                    }
                    val k = rec.read(buf, 0, buf.size)
                    if (k > 0) cl.send(buf, 0, k) else Thread.sleep(5)
                }
                val now = System.currentTimeMillis()
                if (now - lastStat >= (if (firstLog) 5000 else 10000)) {
                    val dt = (now - lastStat).coerceAtLeast(1)
                    val kbps = ((cl.sentBytes - lastBytes) * 8 / dt).toInt()
                    val ps = cl.peerStats
                    upd { it.copy(txPackets = cl.sentPackets, txKbps = kbps, txRtt = cl.rttMs, txPeerLoss = ps?.packetLoss ?: -1f, txPeerLatency = ps?.latencyMs ?: -1) }
                    L.i("传音：发送 → $host:$port（${if (tcpOnly) "TCP" else "UDP"}）音源=${if (src == "tap") "变声输出" else "原始麦克风"} 包 ${cl.sentPackets}（+${cl.sentPackets - lastPk}）" +
                        " 码率 $kbps kbps RTT ${cl.rttMs}ms 本端丢弃积压 ${txDropped * 1000 / SR}ms" + (ps?.let { " | 对端统计 丢包 ${"%.1f".format(it.packetLoss * 100)}% 抖动 ${"%.1f".format(it.jitterMs)}ms 延迟估计 ${it.latencyMs}ms" } ?: " | 对端未回统计"))
                    lastStat = now; lastPk = cl.sentPackets; lastBytes = cl.sentBytes; firstLog = false
                } else if (now - lastStat >= 1000 && cl.sentPackets - lastPk > 0) {
                    val ps = cl.peerStats
                    upd { it.copy(txPackets = cl.sentPackets, txRtt = cl.rttMs, txPeerLoss = ps?.packetLoss ?: -1f, txPeerLatency = ps?.latencyMs ?: -1,
                        txKbps = if (it.txKbps == 0) ((cl.sentBytes - lastBytes) * 8 / (now - lastStat)).toInt() else it.txKbps) }
                }
            }
            if (cl.closed && txRun) endMsg = if (cl.closeReason.startsWith("心跳超时")) tr("连接已断开：对方超过 5 秒没有回应") else tr("连接已断开")
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            endMsg = tr("发送出错：{0}", t.message ?: t.javaClass.simpleName)
            L.e("传音：发送", t)
        } finally {
            client?.close(endMsg); client = null
            rec?.let { runCatching { it.stop() }; it.release() }
            if (tapOn) MicInjector.linkTap(false)
            txRun = false; micYield = false
            L.i("传音：发送结束（$endMsg）")
            upd { it.copy(txOn = false, txConnected = false, txStatus = endMsg) }
            serviceSync()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? = runCatching {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR / 25 * 2))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); null } else { r.startRecording(); r }
    }.getOrNull()

    // ================================================================= 接收

    @Volatile private var server: MicYouServer? = null
    @Volatile private var rxStatThread: Thread? = null
    @Volatile private var rxRun = false
    private var nsdRegL: NsdManager.RegistrationListener? = null
    /** 外放播放（普通模式）：抖动缓冲 + AudioTrack 线程 */
    private val playBuf = PlayoutBuffer(SR * PRIME_MS / 1000, SR * (PRIME_MS + 120) / 1000)
    @Volatile private var playThread: Thread? = null
    @Volatile private var injectStarting = false
    @Volatile private var injected = false
    @Volatile private var lastPushMs = 0L
    /** 注入启动失败后 5 s 内不重试（否则每个包都会重试一次） */
    @Volatile private var injectFailMs = 0L
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var shizukuMode = false

    fun startReceive() {
        val c = app ?: return
        if (rxRun) return
        val srv = MicYouServer(MicYou.DEFAULT_PORT, onPcm = ::onRxPcm, onPeer = ::onRxPeer, log = { L.i("传音：接收 $it") }, bufMs = { _state.value.rxBufMs })
        try { srv.start() } catch (t: Throwable) {
            L.w("传音：接收端口 ${MicYou.DEFAULT_PORT} 打开失败：${t.message}")
            upd { it.copy(rxOn = false, rxStatus = tr("端口 {0} 被占用或无法监听：{1}", MicYou.DEFAULT_PORT, t.message ?: "")) }
            return
        }
        server = srv; rxRun = true
        shizukuMode = MicInjector.shizukuGranted()
        upd { it.copy(rxOn = true, rxPeer = null, rxPackets = 0, rxLost = 0, rxBufMs = 0, rxLatency = -1, rxInjecting = false, rxPlayback = false, ips = localIps(),
            rxStatus = if (shizukuMode) tr("等待发送端连接…") else tr("等待发送端连接…（普通模式：只能外放播放，不能注入）")) }
        registerNsd(c)
        serviceSync()
        L.i("传音：接收开启（${if (shizukuMode) "注入模式" else "普通模式，外放播放"}）本机 ${localIps().joinToString()} 端口 ${MicYou.DEFAULT_PORT}/${MicYou.DEFAULT_PORT + 1}")
        rxStatThread = Thread({ rxMonitor() }, "link-rx-stat").apply { isDaemon = true; start() }
    }

    fun stopReceive() {
        if (!rxRun) return
        rxRun = false
        unregisterNsd()
        server?.stop(); server = null
        stopInject(tr("接收已关闭"))
        stopPlayback()
        upd { it.copy(rxOn = false, rxPeer = null, rxInjecting = false, rxPlayback = false, rxStatus = tr("已关闭")) }
        L.i("传音：接收关闭")
        serviceSync()
    }

    private fun registerNsd(c: Context) {
        val m = c.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val si = NsdServiceInfo().apply {
            serviceName = deviceName(); serviceType = MicYou.MDNS_TYPE; port = MicYou.DEFAULT_PORT
            runCatching { setAttribute("app", "micmask") }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { myServiceName = s.serviceName; L.i("传音：mDNS 已广播「${s.serviceName}」") }
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) { L.w("传音：mDNS 广播失败 err=$e（对方可手动输入 IP）") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        nsdRegL = l
        runCatching { m.registerService(si, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { L.w("传音：registerService 失败 ${it.message}"); nsdRegL = null }
    }

    private fun unregisterNsd() {
        val c = app ?: return; val l = nsdRegL ?: return
        nsdRegL = null; myServiceName = null
        runCatching { (c.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) }
    }

    private fun onRxPeer(peer: String?, why: String) {
        if (peer != null) {
            upd { it.copy(rxPeer = peer, rxStatus = tr("已连接 {0}，等待音频…", peer)) }
        } else {
            stopInject(tr("发送端断开，已恢复真麦"))
            stopPlayback()
            upd { it.copy(rxPeer = null, rxInjecting = false, rxPlayback = false, rxStatus = if (rxRun) tr("发送端已断开，等待重新连接…") else tr("已关闭")) }
        }
    }

    /** 网络线程回调：每包 10 ms PCM */
    private fun onRxPcm(pcm: ShortArray) {
        if (!rxRun) return
        lastPushMs = System.currentTimeMillis()
        if (shizukuMode) {
            if (injected && MicInjector.liveOn) { MicInjector.livePush(pcm); return }
            if (!injected && !injectStarting && System.currentTimeMillis() - injectFailMs > 5000) {
                injectStarting = true
                main.post { injectStarting = false; startInject() }   // 绑定 Shizuku 服务等要在主线程
            }
        } else {
            if (playThread == null) startPlayback()
            playBuf.write(pcm)
        }
    }

    private fun startInject() {
        val c = app ?: return
        if (!rxRun || server?.peer == null) return
        if (MicInjector.liveOn) {
            injectFailMs = System.currentTimeMillis()
            upd { it.copy(rxStatus = tr("变声正在运行：先停止变声，接收的声音才能注入")) }
            return
        }
        val (target, source) = targetProvider?.invoke() ?: (MicInjector.DEFAULT_TARGET to MediaRecorder.AudioSource.MIC)
        injectStarting = true
        MicInjector.modeLabel = tr("传音接收")
        L.i("传音：开始注入远端音频 目标=${if (MicInjector.scopeAuto) "自动识别" else target} 预缓冲 ${PRIME_MS}ms")
        upd { it.copy(rxStatus = tr("正在接管麦克风…")) }
        MicInjector.startLive(c, target, 0f, source, external = true, primeMs = PRIME_MS, rvcInShell = false, linkRx = true) { msg ->
            if (MicInjector.liveOn && !injected) {
                injected = true; injectStarting = false
                // 远端音频已经是对方处理好的：效果链旁路、耳返关
                Thread { runCatching { MicInjector.setFx(FxChain.neutral()); MicInjector.setMonitor(false) } }.start()
                upd { it.copy(rxInjecting = true, rxStatus = tr("正在注入：对方说话会替代你的麦克风")) }
            }
            if (!MicInjector.liveOn) {
                val was = injected
                if (!was) injectFailMs = System.currentTimeMillis()
                injected = false; injectStarting = false
                if (was || msg.isNotEmpty()) upd { it.copy(rxInjecting = false, rxStatus = if (rxRun) msg else it.rxStatus) }
            }
        }
    }

    private fun stopInject(why: String) {
        if (!injected && !injectStarting) return
        injected = false; injectStarting = false
        if (MicInjector.liveOn) MicInjector.stopLive()
        MicInjector.modeLabel = null
        L.i("传音：停止注入（$why）")
        upd { it.copy(rxInjecting = false) }
    }

    private fun startPlayback() {
        playBuf.clear()
        val t = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
            val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val at = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(min, SR / 25 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
            }.getOrNull() ?: return@Thread
            at.play()
            upd { it.copy(rxPlayback = true, rxStatus = tr("正在外放播放（普通模式不能注入）")) }
            val b = ShortArray(MicYou.FRAME)
            try {
                while (rxRun && playThread === Thread.currentThread()) {
                    playBuf.read(b, b.size)
                    at.write(b, 0, b.size)
                }
            } finally { runCatching { at.stop() }; at.release() }
        }, "link-rx-play")
        playThread = t; t.start()
    }

    private fun stopPlayback() { playThread = null; playBuf.clear() }

    /** 每秒刷新界面统计，每 5–10 s 写一行日志；3 s 没音频就停止注入、还原真麦 */
    private fun rxMonitor() {
        var lastLog = System.currentTimeMillis(); var firstLog = true; var lastPk = 0L; var lastLost = 0L
        while (rxRun) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            val s = server ?: break
            val now = System.currentTimeMillis()
            if ((injected || injectStarting) && now - lastPushMs > 3000) {
                stopInject(tr("3 秒没有收到音频，已恢复真麦"))
                upd { it.copy(rxStatus = tr("音频中断，已恢复真麦；恢复后自动重新接管")) }
            }
            if (playThread != null && now - lastPushMs > 3000) { stopPlayback(); upd { it.copy(rxPlayback = false) } }
            val bufMs = when {
                injected -> MicInjector.outState()?.second ?: 0
                playThread != null -> playBuf.size() * 1000 / SR
                else -> 0
            }
            val rtt = s.rttMs
            val lat = if (s.packets > 0) bufMs + (if (rtt >= 0) rtt / 2 else 0) + 10 else -1
            if (s.opusPackets > 0 && s.opusPackets == s.packets && s.packets % 50 == 0L)
                upd { it.copy(rxStatus = tr("对方发送的是 Opus 编码，当前版本只支持 PCM（请用 MicMask 发送）")) }
            upd { it.copy(rxPackets = s.packets, rxLost = s.lost, rxBufMs = bufMs, rxLatency = lat) }
            if (s.peer != null && now - lastLog >= (if (firstLog) 5000 else 10000)) {
                val total = s.packets + s.lost
                L.i("传音：接收 ← ${s.peer}（${if (s.transport == "tcp") "TCP" else "UDP"}）包 ${s.packets}（+${s.packets - lastPk}）丢包 ${s.lost}（+${s.lost - lastLost}，" +
                    "${if (total > 0) "%.1f".format(s.lost * 100.0 / total) else "0.0"}%）乱序 ${s.reordered} 迟到 ${s.late} 抖动 ${"%.1f".format(s.jitterMs)}ms " +
                    "缓冲 ${bufMs}ms（目标 $PRIME_MS）RTT ${rtt}ms 延迟估计 ${lat}ms " +
                    (if (injected) "注入=是 断流 ${MicInjector.underNow}" else if (playThread != null) "外放 断流 ${playBuf.underruns} 裁剪 ${playBuf.trimmed * 1000 / SR}ms" else "注入=否") +
                    (if (s.opusPackets > 0) " Opus包(未解) ${s.opusPackets}" else "") + (if (s.rejected > 0) " 拒收 ${s.rejected}" else ""))
                lastLog = now; firstLog = false; lastPk = s.packets; lastLost = s.lost
            }
        }
    }

    /** 全部停止（App 退出、通知栏「停止」） */
    fun stopAll() { stopSend(); stopReceive(); stopDiscovery() }
}
