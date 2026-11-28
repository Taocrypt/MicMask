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
data class LinkDevice(val name: String, val host: String, val port: Int, val phone: Boolean, val proto: Int = LinkHub.PROTO_MICYOU,
    /** mDNS TXT 里的设备 ID 前缀（8 位十六进制，MicMask 自有协议才有） */
    val id: String = "")

/**
 * 已保存设备：发送时连过的接收端（ROLE_TARGET），或连到本机「接收」的发送端（ROLE_SENDER）。
 * 自有协议按握手里的设备 ID（[id]，32 位十六进制）认设备，地址变了也能认出；MicYou 兼容协议没有 ID，按地址记。
 */
data class SavedDevice(
    val key: String, val id: String, val name: String, val alias: String, val host: String, val port: Int,
    val code: String, val proto: Int, val role: Int, val lastOk: Long, val phone: Boolean,
) {
    val label get() = alias.ifEmpty { name.ifEmpty { host } }
    val isTarget get() = role and LinkHub.ROLE_TARGET != 0
}

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
    // 协议：0 = MicMask 自有（MicMask Link v1，加密），1 = 兼容 MicYou（明文）
    val proto: Int = 0,
    /** MicMask 自有协议：本机当接收端时显示的 6 位配对码 */
    val code: String = "",
    val txCodec: String = "",
    /** 发送阶段：0 空闲，1 连接中，2 已连接，3 等待自动重连 */
    val txPhase: Int = 0,
    /** 最近一次结束是出错（红色提示）而不是用户停止 */
    val txError: Boolean = false,
    val txTransport: String = "",
    val saved: List<SavedDevice> = emptyList(),
    val auto: Boolean = true,
    /** 本机全部网卡地址（含 VPN / 虚拟网卡） */
    val addrs: List<MmNet.LocalAddr> = emptyList(),
    /** 上一次搜索结束时一个设备也没找到（提示跨网段请手填） */
    val searchEmpty: Boolean = false,
    // 本机
    val name: String = "",
    val ips: List<String> = emptyList(),
    val devices: List<LinkDevice> = emptyList(),
    val discovering: Boolean = false,
)

/** 发送连接的统一外观：MicYou 兼容（MicYouClient）与 MicMask 自有（MmSession）各一个适配器 */
internal interface LinkTx {
    val closed: Boolean
    val closeReason: String
    val sentPackets: Long
    val sentBytes: Long
    val rttMs: Int
    val peerLoss: Float?
    val peerJitter: Float?
    val peerLatency: Int?
    val transport: String
    val codec: String
    fun send(s: ShortArray, off: Int = 0, len: Int = s.size)
    fun close(reason: String)
}

private class MicYouTx(val c: MicYouClient) : LinkTx {
    override val closed get() = c.closed
    override val closeReason get() = c.closeReason
    override val sentPackets get() = c.sentPackets
    override val sentBytes get() = c.sentBytes
    override val rttMs get() = c.rttMs
    override val peerLoss get() = c.peerStats?.packetLoss
    override val peerJitter get() = c.peerStats?.jitterMs
    override val peerLatency get() = c.peerStats?.latencyMs
    override val transport get() = if (c.tcpOnly) "TCP" else "UDP"
    override val codec get() = "PCM"
    override fun send(s: ShortArray, off: Int, len: Int) = c.send(s, off, len)
    override fun close(reason: String) = c.close(reason)
}

private class MmTx(val s: MmSession) : LinkTx {
    override val closed get() = s.closed
    override val closeReason get() = s.closeReason
    override val sentPackets get() = s.txPackets
    override val sentBytes get() = s.txBytes
    override val rttMs get() = s.rttMs
    override val peerLoss get() = s.peerStats?.loss
    override val peerJitter get() = s.peerStats?.jitterMs
    override val peerLatency get() = s.peerStats?.latencyMs
    override val transport get() = if (s.udpTx) "UDP" else "TCP"
    override val codec get() = when (s.txCodec) { MmLink.CODEC_OPUS -> "Opus"; MmLink.CODEC_PCM16 -> "PCM"; else -> "?" }
    override fun send(s: ShortArray, off: Int, len: Int) = this.s.sendPcm(s, off, len)
    override fun close(reason: String) = s.close(reason)
}

/** 接收端的统一外观 */
internal interface LinkRx {
    val packets: Long
    val lost: Long
    val reordered: Long
    val late: Long
    val jitterMs: Float
    val rttMs: Int
    val peer: String?
    val transport: String
    val rejected: Long
    /** MicYou 兼容：收到但解不了的 Opus 包数；自有协议恒 0 */
    val opusPackets: Long
    /** 自有协议：编码与 FEC/PLC 统计 */
    val extra: String
    fun stop()
}

private class MicYouRx(val s: MicYouServer) : LinkRx {
    override val packets get() = s.packets
    override val lost get() = s.lost
    override val reordered get() = s.reordered
    override val late get() = s.late
    override val jitterMs get() = s.jitterMs
    override val rttMs get() = s.rttMs
    override val peer get() = s.peer
    override val transport get() = if (s.transport == "tcp") "TCP" else "UDP"
    override val rejected get() = s.rejected
    override val opusPackets get() = s.opusPackets
    override val extra get() = ""
    override fun stop() = s.stop()
}

/** 自有协议接收统计：[srv] 监听端当前会话；[fixed] = 同一条会话双向时，发送连接上收到的音频（本机是客户端） */
private class MmRx(val srv: MmLinkServer?, val fixed: MmSession? = null) : LinkRx {
    val c get() = fixed ?: srv?.current
    override val packets get() = c?.rxPackets ?: 0L
    override val lost get() = c?.rxLost ?: 0L
    override val reordered get() = c?.rxReordered ?: 0L
    override val late get() = c?.rxLate ?: 0L
    override val jitterMs get() = c?.jitterMs ?: 0f
    override val rttMs get() = c?.rttMs ?: -1
    override val peer get() = c?.let { "${it.peerName}（${it.peerIp.hostAddress}）" }
    override val transport get() = c?.let { if (it.lastAudioMs > 0 && it.rxCodec >= 0) (if (it.rxViaUdp) "UDP" else "TCP") else "" } ?: ""
    val tcp get() = c?.let { it.lastAudioMs > 0 && !it.rxViaUdp } ?: false
    override val rejected get() = c?.rxRejected ?: 0L
    override val opusPackets get() = 0L
    override val extra get() = c?.let { " 编码=${when (it.rxCodec) { MmLink.CODEC_OPUS -> "Opus"; MmLink.CODEC_PCM16 -> "PCM"; else -> "?" }}" + (if (it.rxCodec == MmLink.CODEC_OPUS) " FEC补 ${it.fecFrames} PLC补 ${it.plcFrames}" else "") +
        (if (fixed != null) " 同一连接双向" else "") + " 心跳超时 ${it.heartbeatMs / 1000.0}s" + (if (it.tcpAudioDropped > 0) " TCP积压丢 ${it.tcpAudioDropped}" else "") } ?: ""
    override fun stop() { srv?.stop() }
}

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
    const val PROTO_MM = 0
    const val PROTO_MICYOU = 1

    /** 当前协议（prefs link_proto，默认 MicMask 自有）；收发进行中不能切换 */
    @Volatile var proto = PROTO_MM; private set

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        proto = prefs()?.getInt("link_proto", PROTO_MM)?.takeIf { it == PROTO_MM || it == PROTO_MICYOU } ?: PROTO_MM
        upd { it.copy(name = deviceName(), proto = proto, code = if (proto == PROTO_MM) pairCode() else "", saved = loadSaved(), auto = autoOn()) }
    }

    // ================================================================= 设备名称

    fun defaultDeviceName(): String = "MicMask-" + (Build.MODEL ?: "Android").replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Android" }.take(24)

    /** 只留字母（含中日韩）、数字、空格、_ . -；最多 24 个字符且 UTF-8 不超过 60 字节（mDNS 实例名上限 63） */
    fun sanitizeName(raw: String): String {
        var n = raw.replace(Regex("[^\\p{L}\\p{N} _.-]"), "").replace(Regex("\\s+"), " ").trim().take(24)
        while (n.toByteArray(Charsets.UTF_8).size > 60) n = n.dropLast(1)
        return n.trim()
    }

    /** 自定义设备名（空 = 恢复默认）。接收开着且没人连时立即重开接收，让 mDNS 广播和握手用新名字 */
    fun setDeviceName(raw: String): String {
        val n = sanitizeName(raw)
        prefs()?.edit()?.apply { if (n.isEmpty() || n == defaultDeviceName()) remove("link_dev_name") else putString("link_dev_name", n) }?.apply()
        val shown = deviceName()
        upd { it.copy(name = shown) }
        L.i("传音：设备名称 → $shown")
        if (rxRun && server?.peer == null) { stopReceive(user = false); startReceive(user = false) }
        return shown
    }

    // ================================================================= 已保存设备

    const val ROLE_TARGET = 1
    const val ROLE_SENDER = 2

    private fun loadSaved(): List<SavedDevice> = runCatching {
        val a = org.json.JSONArray(prefs()?.getString("link_saved", "[]") ?: "[]")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            SavedDevice(o.optString("key"), o.optString("id"), o.optString("name"), o.optString("alias"), o.optString("host"), o.optInt("port"),
                o.optString("code"), o.optInt("proto"), o.optInt("role"), o.optLong("last"), o.optBoolean("phone"))
        } }.filter { it.key.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun storeSaved(l: List<SavedDevice>) {
        val a = org.json.JSONArray()
        l.forEach { d -> a.put(org.json.JSONObject().put("key", d.key).put("id", d.id).put("name", d.name).put("alias", d.alias).put("host", d.host).put("port", d.port)
            .put("code", d.code).put("proto", d.proto).put("role", d.role).put("last", d.lastOk).put("phone", d.phone)) }
        prefs()?.edit()?.putString("link_saved", a.toString())?.apply()
        upd { it.copy(saved = l) }
    }

    private val savedLock = Any()

    /** 记住 / 更新一台设备：有 ID 按 ID 合并（同时并掉同地址的无 ID 记录），没有按地址 */
    private fun upsertSaved(id: String, name: String, host: String, port: Int, code: String, p: Int, role: Int, phone: Boolean): SavedDevice = synchronized(savedLock) {
        val l = _state.value.saved.toMutableList()
        val addrKey = "addr:$p:$host:$port"
        val i = l.indexOfFirst { if (id.isNotEmpty()) it.id == id else it.key == addrKey }
        val old = if (i >= 0) l.removeAt(i) else null
        if (id.isNotEmpty() && role == ROLE_TARGET) l.removeAll { it.id.isEmpty() && it.key == addrKey }
        if (old != null && id.isNotEmpty() && (old.host != host || old.port != port) && host.isNotEmpty()) L.i("传音：自动连接 已保存设备「${old.label}」（ID ${id.take(8)}）地址 ${old.host}:${old.port} → $host:$port")
        val d = SavedDevice(old?.key ?: (if (id.isNotEmpty()) id else addrKey), id, name.ifEmpty { old?.name ?: "" }, old?.alias ?: "",
            if (role == ROLE_TARGET || old == null || !old.isTarget) host else old.host, if (role == ROLE_TARGET || old == null || !old.isTarget) port else old.port,
            code.ifEmpty { old?.code ?: "" }, p, (old?.role ?: 0) or role, System.currentTimeMillis(), phone)
        l.add(0, d)
        storeSaved(l.take(30))
        d
    }

    fun renameSaved(key: String, alias: String) = synchronized(savedLock) {
        val a = sanitizeName(alias)
        storeSaved(_state.value.saved.map { if (it.key == key) it.copy(alias = if (a == it.name) "" else a) else it })
        L.i("传音：已保存设备重命名 → ${a.ifEmpty { "（默认）" }}")
    }

    fun deleteSaved(key: String) = synchronized(savedLock) {
        val d = _state.value.saved.firstOrNull { it.key == key }
        storeSaved(_state.value.saved.filterNot { it.key == key })
        d?.let { L.i("传音：已删除保存的设备「${it.label}」") }
    }

    /** 搜到的设备对应哪台已保存设备：自有协议按 ID 前缀，否则按名字 + 协议 */
    fun savedFor(d: LinkDevice): SavedDevice? = _state.value.saved.firstOrNull { d.id.isNotEmpty() && it.id.startsWith(d.id) }
        ?: _state.value.saved.firstOrNull { it.name == d.name && it.proto == d.proto }

    /** 这台设备记住的配对码（已保存设备 → 旧版按名字记的） */
    fun codeFor(d: LinkDevice): String = savedFor(d)?.code?.takeIf { it.length == 6 } ?: savedCode(d.name)

    // ================================================================= 自动连接

    fun autoOn(): Boolean = prefs()?.getBoolean("link_auto", true) ?: true
    fun setAuto(on: Boolean) {
        prefs()?.edit()?.putBoolean("link_auto", on)?.apply()
        upd { it.copy(auto = on) }
        L.i("传音：自动连接 ${if (on) "开" else "关"}")
    }
    private fun rxWanted(): Boolean = prefs()?.getBoolean("link_rx_want", false) ?: false
    private fun setRxWanted(v: Boolean) { prefs()?.edit()?.putBoolean("link_rx_want", v)?.apply() }

    /** 用户手动点了「停止发送」：本次进程里不再自动发起，直到用户再手动开始 */
    @Volatile private var userStoppedTx = false
    /** 在这之前 mDNS 搜到已配对设备会自动连上（打开 App / 传音页后 20 秒） */
    @Volatile private var autoWindowUntil = 0L
    @Volatile private var lastKickMs = 0L

    /** App 回到前台（MainActivity.onResume）：30 秒内只触发一次 */
    fun onForeground() {
        if (app == null) return
        val now = System.currentTimeMillis()
        if (now - lastKickMs < 30_000) return
        lastKickMs = now
        autoKick("打开 App")
    }

    /**
     * 自动连接入口：
     *  - 接收：上次开着「接收」（用户没手动关）→ 重新打开监听；
     *  - 发送：有已配对的接收端 → 依次试上次地址（最近成功的优先，最多 3 台），同时 mDNS 搜 20 秒，按设备 ID 认出地址变了的设备。
     * 只在 App 在前台时调用（Android 不允许后台新开麦克风前台服务）。
     */
    fun autoKick(reason: String) {
        if (app == null || !autoOn()) return
        if (rxWanted() && !rxRun) { L.i("传音：自动连接 恢复接收（$reason）"); startReceive(user = false) }
        if (txRun || userStoppedTx) return
        val cands = savedTargets()
        if (cands.isEmpty()) return
        if (!hasMic() && !MicInjector.liveOn) { L.i("传音：自动连接 跳过发送：没有录音权限"); return }
        autoWindowUntil = System.currentTimeMillis() + 20_000
        if (proto == PROTO_MM && cands.any { it.id.isNotEmpty() }) discoverFor(20_000)
        L.i("传音：自动连接（$reason）尝试 ${cands.size} 台已保存设备：${cands.joinToString { "${it.label}@${it.host}:${it.port}" }}")
        launchTx(cands, auto = true)
    }

    private fun savedTargets(): List<TxTarget> = _state.value.saved
        .filter { it.isTarget && it.proto == proto && it.host.isNotEmpty() && (proto != PROTO_MM || it.code.length == 6) }
        .sortedByDescending { it.lastOk }.take(3)
        .map { TxTarget(it.host, it.port, it.label, it.code, it.id) }

    /** mDNS 搜到设备：已保存的更新地址；自动连接窗口内、没在发送时自动连上 */
    private fun onDiscovered(d: LinkDevice) {
        val sv = savedFor(d) ?: return
        if (sv.id.isNotEmpty() && d.id.isNotEmpty() && (sv.host != d.host || sv.port != d.port) && sv.isTarget) synchronized(savedLock) {
            L.i("传音：自动连接 mDNS 认出已保存设备「${sv.label}」（ID ${sv.id.take(8)}）新地址 ${d.host}:${d.port}（原 ${sv.host}:${sv.port}）")
            storeSaved(_state.value.saved.map { if (it.key == sv.key) it.copy(host = d.host, port = d.port) else it })
        }
        if (autoOn() && !txRun && !userStoppedTx && System.currentTimeMillis() < autoWindowUntil && sv.isTarget && sv.proto == proto && (proto != PROTO_MM || sv.code.length == 6)) {
            L.i("传音：自动连接 mDNS 发现已配对设备「${sv.label}」→ 连接 ${d.host}:${d.port}")
            launchTx(listOf(TxTarget(d.host, d.port, sv.label, sv.code, sv.id)), auto = true)
        }
    }

    private fun prefs() = app?.let { com.taocrypt.micmask.Prefs.main(it) }

    /** 切换协议：收发都停着才允许 */
    fun setProto(p: Int): Boolean {
        if (busy || (p != PROTO_MM && p != PROTO_MICYOU)) return false
        stopDiscovery()
        proto = p
        prefs()?.edit()?.putInt("link_proto", p)?.apply()
        upd { it.copy(proto = p, devices = emptyList(), txStatus = "", rxStatus = "", code = if (p == PROTO_MM) pairCode() else "") }
        L.i("传音：协议 → ${if (p == PROTO_MM) "MicMask 自有" else "兼容 MicYou"}")
        return true
    }

    /** 本机设备 ID（16 字节，首次随机生成并保存） */
    fun deviceId(): ByteArray {
        val sp = prefs() ?: return MmCrypto.random(16)
        val h = sp.getString("link_dev_id", null)
        if (h != null && h.length == 32) return P256.hex(h)
        val id = MmCrypto.random(16)
        sp.edit().putString("link_dev_id", id.joinToString("") { "%02x".format(it) }).apply()
        return id
    }

    /** 本机当接收端时的配对码（6 位，保存；用户可在页面上换一个） */
    fun pairCode(): String {
        val sp = prefs() ?: return "000000"
        sp.getString("link_code", null)?.takeIf { it.length == 6 && it.all(Char::isDigit) }?.let { return it }
        return MmCrypto.newPairingCode().also { sp.edit().putString("link_code", it).apply() }
    }

    fun regenCode() {
        val c = MmCrypto.newPairingCode()
        prefs()?.edit()?.putString("link_code", c)?.apply()
        upd { it.copy(code = c) }
        L.i("传音：已更换配对码")
    }

    /** 发送端记住每台接收端的配对码（按设备名），下次自动填 */
    fun savedCode(key: String): String = prefs()?.getString("link_peer_code_$key", "") ?: ""
    fun saveCode(key: String, code: String) { prefs()?.edit()?.putString("link_peer_code_$key", code)?.apply() }

    /**
     * MicMask 自有协议的编码偏好：0 = Opus（默认，省流量、抗丢包），1 = PCM（USB/热点，少一次编解码）。
     * 2.1.1-alpha.13 起「接收方偏好优先」：本机接收时告诉对方按这个编码发；本机发送时跟随接收方的偏好（对方是旧版才用这里的设置）。
     */
    fun codecPref(): Int = prefs()?.getInt("link_codec", 0) ?: 0
    fun setCodecPref(v: Int) { prefs()?.edit()?.putInt("link_codec", v)?.apply() }

    private fun mmCfg(intent: Int) = MmLinkConfig(deviceId(), deviceName(), "android", intent,
        if (codecPref() == 1) MmLink.CODEC_PCM16 else MmLink.CODEC_OPUS, followPeerCodec = true)

    /** 发送连接的意图：发；「接收」也开着（自有协议）时同时声明收——对端若也在发，可以直接在这条连接上回传（同一条会话双向） */
    private fun txIntent() = MmLink.CAP_SEND or (if (rxRun && rxProto == PROTO_MM) MmLink.CAP_RECV else 0)

    /** 「接收」开关变化时同步到正在发送的连接（重发 CAPS） */
    private fun syncTxIntent() { ((client as? MmTx)?.s)?.takeIf { !it.closed }?.updateIntent(txIntent()) }

    /** 接收页二维码内容：本机全部地址 + 端口 + 配对码 + 设备 ID + 名称 */
    /** 接收端二维码内容：精简写法（只 h + c，见 [MmPairUri.encodeQr]），旧版扫码端照样解析 */
    fun pairUri(): String = MmPairUri(_state.value.addrs.map { it.ip }.ifEmpty { localIps() }, MmLink.DEFAULT_PORT, pairCode(), MmNet.hex(deviceId()), deviceName()).encodeQr()

    /** 扫码连接：按「同网段 > 局域网 > 隧道 > IPv6」依次尝试二维码里的地址，连上后记住设备（含配对码与设备 ID） */
    fun connectScanned(text: String): Boolean {
        val u = MmPairUri.parse(text) ?: return false
        if (proto != PROTO_MM && !setProto(PROTO_MM)) { upd { it.copy(txStatus = tr("收发进行中不能切换协议，先停止"), txError = true) }; return true }
        val order = MmNet.orderHosts(u.hosts, MmNet.localAddrs().map { it.ip })
        L.i("传音：扫码连接" + (if (u.name.isNotEmpty()) "「${u.name}」" else "") + (if (u.id.isNotEmpty()) "（ID ${u.id.take(8)}）" else "") + " 地址 ${order.joinToString()} 端口 ${u.port}")
        userStoppedTx = false
        launchTx(order.map { TxTarget(it, u.port, u.name, u.code, u.id) }, auto = false)
        return true
    }

    /** 本机设备名：设置里自定义的，否则「MicMask-机型」。用于 mDNS 广播与握手显示 */
    fun deviceName(): String = prefs()?.getString("link_dev_name", null)?.let { sanitizeName(it) }?.takeIf { it.isNotEmpty() } ?: defaultDeviceName()

    /** 本机全部 IPv4（Wi-Fi/热点/USB 优先，含 VPN 虚拟网卡如 EasyTier/Tailscale/ZeroTier，蜂窝最后） */
    fun localIps(): List<String> = MmNet.localAddrs(includeV6 = false).map { it.ip }

    fun refreshIps() { val a = MmNet.localAddrs(); upd { it.copy(ips = a.filter { x -> !x.v6 }.map { x -> x.ip }, addrs = a) } }

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
        upd { it.copy(discovering = true, devices = emptyList(), searchEmpty = false) }
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
        val p = proto
        runCatching { m.discoverServices(if (p == PROTO_MM) MmLink.MDNS_TYPE else MicYou.MDNS_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
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
                        val attr = { k: String -> runCatching { s.attributes[k]?.let { String(it) } }.getOrNull() }
                        val mm = s.serviceType?.contains("micmask") == true || attr("v") != null && attr("app") == "micmask" && attr("plat") != null
                        val phone = if (mm) attr("plat") != "windows" else (s.serviceName.startsWith("MicMask") || attr("app") == "micmask")
                        val d = LinkDevice(s.serviceName, host, s.port, phone, if (mm) PROTO_MM else PROTO_MICYOU, if (mm) attr("id")?.lowercase()?.filter { it in "0123456789abcdef" } ?: "" else "")
                        if (host !in localIps()) {
                            upd { st -> st.copy(devices = (st.devices.filterNot { it.name == d.name } + d).sortedBy { it.name }) }
                            runCatching { onDiscovered(d) }
                        }
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
        val was = _state.value.discovering
        upd { it.copy(discovering = false, searchEmpty = was && it.devices.isEmpty()) }
        if (was && _state.value.devices.isEmpty()) L.i("传音：局域网搜索结束，没有发现设备（跨网段 / VPN 组网时 mDNS 不可达，请手填对方 IP）")
    }

    @Volatile private var discGen = 0
    /** 搜索 [ms] 毫秒后自动停止（页面「搜索」按钮与自动连接共用） */
    fun discoverFor(ms: Long) {
        val g = ++discGen
        startDiscovery()
        main.postDelayed({ if (g == discGen) stopDiscovery() }, ms)
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
    @Volatile private var client: LinkTx? = null
    /** 变声即将开始：先放掉 App 自己的录音，避免和 shell 抢麦（由 MainViewModel 调用） */
    @Volatile private var micYield = false

    fun onLiveStarting() { micYield = true }

    fun hasMic(): Boolean = app?.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 一个连接目标：地址、显示名、配对码、设备 ID（自有协议，32 位十六进制；用来在地址变化后认出设备） */
    internal data class TxTarget(val host: String, val port: Int, val label: String, val code: String, val id: String = "")

    /** 一次连接（含连上后的整段发送）的结果 */
    private class TxEnd(val msg: String, val connected: Boolean, val retry: Boolean, val log: String, val durMs: Long = 0)

    private val BACKOFF = intArrayOf(1, 2, 4, 8, 15, 30)

    /** 用户手动开始发送（点设备、点已保存设备或手填地址） */
    fun startSend(host: String, port: Int, label: String, code: String = "", devId: String = "") {
        userStoppedTx = false
        launchTx(listOf(TxTarget(host.trim(), port, label, code.filter(Char::isDigit), devId)), auto = false)
    }

    private val txLock = Any()

    private fun launchTx(cands: List<TxTarget>, auto: Boolean) {
        val c = app ?: return
        val first = cands.firstOrNull() ?: return
        synchronized(txLock) {
            if (txRun) return
            if (first.host.isEmpty()) { upd { it.copy(txStatus = tr("请先选择设备或输入对方 IP"), txError = true) }; return }
            val p = proto
            if (p == PROTO_MM && first.code.length != 6) { upd { it.copy(txStatus = tr("请输入接收端显示的 6 位配对码"), txError = true) }; return }
            txRun = true; micYield = false
            upd { it.copy(txOn = true, txConnected = false, txPhase = 1, txError = false, txTarget = first.label.ifEmpty { "${first.host}:${first.port}" },
                txStatus = tr("正在连接 {0}…", first.label.ifEmpty { "${first.host}:${first.port}" }), txPackets = 0, txKbps = 0, txRtt = -1, txPeerLoss = -1f, txPeerLatency = -1, txTransport = "") }
            serviceSync()
            txThread = Thread({ txSupervisor(c, cands, p, auto) }, "link-tx").apply { start() }
        }
    }

    fun stopSend() {
        userStoppedTx = true
        txRun = false
        client?.close(tr("已停止"))
        txThread?.interrupt()
    }

    private fun sleepWhileRun(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (txRun && System.currentTimeMillis() < end) Thread.sleep(100)
        return txRun
    }

    /** 重连前刷新地址：mDNS 刚搜到的（按设备 ID）> 已保存设备里更新过的 > 原地址 */
    private fun refreshTarget(t: TxTarget): TxTarget {
        if (t.id.isEmpty()) return t
        _state.value.devices.firstOrNull { it.id.isNotEmpty() && t.id.startsWith(it.id) }?.let { return t.copy(host = it.host, port = it.port) }
        _state.value.saved.firstOrNull { it.id == t.id && it.host.isNotEmpty() }?.let { return t.copy(host = it.host, port = it.port) }
        return t
    }

    /**
     * 发送主线程：先依次试候选（手动 = 1 个；自动 = 最多 3 台已保存设备），连上后发送；
     * 断线后（自动连接开着、不是配对码/版本这类重试也没用的错误）按 1、2、4、8、15、30 秒退避重连，连接维持 30 秒以上才重置退避。
     */
    private fun txSupervisor(c: Context, cands: List<TxTarget>, p: Int, auto: Boolean) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        var endMsg = tr("已停止"); var err = false
        try {
            var r: TxEnd? = null
            var cur: TxTarget? = null
            for (t in cands) {
                if (!txRun) break
                if (auto) L.i("传音：自动连接 尝试「${t.label}」${t.host}:${t.port}")
                upd { it.copy(txPhase = 1, txTarget = t.label.ifEmpty { "${t.host}:${t.port}" }, txStatus = if (auto) tr("自动连接 {0}…", t.label.ifEmpty { t.host }) else tr("正在连接 {0}…", t.label.ifEmpty { "${t.host}:${t.port}" })) }
                r = txOnce(c, t, p, auto)
                cur = t
                if (r.connected) break
                if (auto) L.i("传音：自动连接「${t.label}」不可用：${r.log}")
            }
            var tries = 0
            while (txRun && r != null) {
                if (!r.connected && tries == 0) { endMsg = if (auto) tr("自动连接：已保存的设备暂时都连不上（点设备可手动重试）") else r.msg; err = !auto; break }
                if (!autoOn() || !r.retry) { endMsg = r.msg; err = true; break }
                if (r.connected && r.durMs >= 30_000) tries = 0
                val t0 = cur ?: break
                val wait = BACKOFF[minOf(tries, BACKOFF.size - 1)]; tries++
                val rm = r.msg
                upd { it.copy(txConnected = false, txPhase = 3, txStatus = tr("{0}；{1} 秒后自动重连（第 {2} 次）", rm, wait, tries)) }
                L.i("传音：自动连接 ${wait}s 后重连「${t0.label}」${t0.host}:${t0.port}（第 $tries 次）原因：${r.log}")
                if (p == PROTO_MM && t0.id.isNotEmpty() && tries >= 2) discoverFor(15_000)
                if (!sleepWhileRun(wait * 1000L)) break
                val t = refreshTarget(t0)
                if (t.host != t0.host || t.port != t0.port) L.i("传音：自动连接「${t.label}」地址变为 ${t.host}:${t.port}")
                cur = t
                upd { it.copy(txPhase = 1, txStatus = tr("正在重连 {0}…（第 {1} 次）", t.label.ifEmpty { t.host }, tries)) }
                r = txOnce(c, t, p, true)
                if (r.connected) L.i("传音：自动连接 重连成功后又结束：${r.log}")
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            endMsg = tr("发送出错：{0}", t.message ?: t.javaClass.simpleName); err = true
            L.e("传音：发送", t)
        } finally {
            client?.close(endMsg); client = null
            txRun = false; micYield = false
            L.i("传音：发送结束（$endMsg）")
            upd { it.copy(txOn = false, txConnected = false, txPhase = 0, txError = err, txStatus = endMsg) }
            serviceSync()
        }
    }

    /** 把连接异常翻成人话；第二项 = 值不值得自动重试 */
    private fun describeFail(t: Throwable, host: String, port: Int): Pair<String, Boolean> {
        if (t is MmLinkException) return when (t.code) {
            MmLink.E_BAD_CODE -> tr("配对码不对：请核对接收端页面上的 6 位数字") to false
            MmLink.E_BUSY -> tr("配对码错误次数过多，对方已锁定 1 分钟，请稍后再试") to false
            MmLink.E_VERSION -> tr("双方版本不兼容：请把两端都更新到最新版") to false
            else -> tr("对方不是 MicMask 自有协议的接收端（连 MicYou 请在「高级」里切换协议）") to false
        }
        val kind = if (t is MmConnectFail) t.kind else MmLinkClient.classify(t, if ((t.message ?: "").startsWith("握手失败")) 1 else 0)
        if (t !is MmConnectFail && (t.message ?: "").startsWith("握手失败")) return tr("对方不是 MicYou 协议的接收端") to false
        return when (kind) {
            MmNet.F_TIMEOUT -> tr("连接超时：对方没有响应。确认对方已打开「接收」；用 EasyTier、Tailscale 等组网时，还要检查对方防火墙是否放行端口 {0}", port) to true
            MmNet.F_REFUSED -> tr("对方拒绝连接：设备在线，但没有打开「接收」（或两边协议不一致）") to true
            MmNet.F_UNREACHABLE -> tr("找不到对方 {0}：地址写错，或不在同一网络 / VPN 没连上", host) to true
            MmNet.F_HS_TIMEOUT -> tr("已连上对方，但它没有回应：这个端口上可能不是 MicMask，或协议选错") to true
            MmNet.F_CLOSED -> tr("连接时被对方断开：对方可能不是 MicMask 自有协议，或被防火墙拦截") to true
            else -> tr("连接失败：{0}", t.message ?: t.javaClass.simpleName) to true
        }
    }

    /** 记住连上的设备（自有协议带设备 ID 与配对码） */
    private fun onTxConnected(t: TxTarget, ses: MmSession?, p: Int) {
        val sv = if (ses != null) upsertSaved(ses.peerId, ses.peerName, t.host, t.port, t.code, p, ROLE_TARGET, ses.peerPlatform != "windows")
                 else upsertSaved("", t.label.takeIf { it.isNotEmpty() && !it.contains(':') } ?: "", t.host, t.port, "", p, ROLE_TARGET, false)
        if (ses != null) saveCode(t.label.ifEmpty { t.host }, t.code)
        upd { it.copy(txTarget = sv.label) }
    }

    /** 连接一次并发送到结束 */
    private fun txOnce(c: Context, t: TxTarget, p: Int, auto: Boolean): TxEnd {
        val host = t.host; val port = t.port; val code = t.code
        // 127.0.0.1 = USB（电脑端执行 adb reverse）：adb reverse 只转 TCP，音频走 TCP 帧
        val tcpOnly = host == "127.0.0.1" || host == "localhost"
        val remote = MmNet.looksRemote(host)
        val cto = if (auto) (if (remote) 4000 else 1500) else (if (remote) 6000 else 3000)
        var rec: AudioRecord? = null
        var tapOn = false
        var endMsg = tr("已停止")
        var connectedAt = 0L
        var retry = false
        var why = ""
        try {
            val cl: LinkTx = try {
                if (p == PROTO_MM) {
                    val ses = MmLinkClient.connect(host, port, mmCfg(txIntent()), code, tcpOnly, object : MmSessionListener {
                        // 同一条会话双向：本机「接收」开着时，对端在这条连接上发来的音频按接收处理
                        override fun onPcm(s: MmSession, pcm: ShortArray) = onRxPcmFrom(pcm, s)
                    }, { L.i("传音：发送 $it") }, cto, if (remote) 8000 else 5000)
                    ses.localBufMs = { _state.value.rxBufMs }
                    onTxConnected(t, ses, p)
                    MmTx(ses)
                } else {
                    val mc = MicYouClient(host, port, tcpOnly) { L.i("传音：发送 $it") }
                    mc.open(cto); onTxConnected(t, null, p); MicYouTx(mc)
                }
            } catch (e: Throwable) {
                if (e is InterruptedException) throw e
                val (msg, rt) = describeFail(e, host, port)
                L.w("传音：发送连接 $host:$port 失败（${if (auto) "自动" else "手动"}，${if (remote) "跨网段/隧道地址" else "局域网地址"}，超时 ${cto}ms）：${e.message ?: e.javaClass.simpleName} → $msg")
                return TxEnd(msg, false, rt, e.message ?: e.javaClass.simpleName)
            }
            client = cl
            connectedAt = System.currentTimeMillis()
            upd { it.copy(txConnected = true, txPhase = 2, txError = false, txStatus = tr("已连接"), txCodec = if (p == PROTO_MM) "" else "PCM", txTransport = cl.transport) }
            L.i("传音：发送开始 → $host:$port 协议=${if (p == PROTO_MM) "MicMask 自有（加密）" else "兼容 MicYou"} ${if (tcpOnly) "纯 TCP（USB）" else "TCP+UDP"}")
            val buf = ShortArray(MicYou.FRAME)
            var src = ""          // "tap" / "mic"
            var lastStat = System.currentTimeMillis(); var lastPk = 0L; var lastBytes = 0L
            var firstLog = true
            var tapMiss = 0
            var lastWant = ""
            var shortReads = 0L
            var txDropped = 0L
            while (txRun && !cl.closed) {
                // 音源（2.1.1-alpha.13 明确）：
                //  - 传音接收正在注入（shell 的输出是对方的声音）→ 取 shell 录到的真麦（tapmic），绝不取注入前输出，否则会把收到的声音发回去形成回环；
                //  - 自己的变声在运行 → 取注入前输出（tap，对方听到的就是变声后的声音）；
                //  - 都没开 → App 自己录原始麦克风（mic）。
                val rxLive = linkRxActive
                val want = when { rxLive && MicInjector.liveOn -> "tapmic"; MicInjector.liveOn && !rxLive -> "tap"; else -> "" }
                if (want != lastWant) { lastWant = want; tapMiss = 0 }
                if (want.isNotEmpty() && src != want && tapMiss == 0) {
                    rec?.let { runCatching { it.stop() }; it.release() }; rec = null
                    val okSrc = MicInjector.linkTapMic(want == "tapmic")
                    tapOn = (okSrc || want == "tap") && MicInjector.linkTap(true)
                    if (tapOn) {
                        src = want; micYield = false
                        if (want == "tap") { upd { it.copy(txSource = tr("变声输出（对方听到的声音）")) }; L.i("传音：音源 → 变声输出（shell 注入前取点）") }
                        else { upd { it.copy(txSource = tr("原始麦克风（接收注入中，不会把收到的声音发回去）")) }; L.i("传音：音源 → 真麦（接收注入中，经注入服务取，防回环）") }
                    } else if (++tapMiss == 1) L.w("传音：注入服务不支持取点（版本不一致？），改用原始麦克风")
                }
                if (want != src && (src == "tap" || src == "tapmic")) { MicInjector.linkTap(false); MicInjector.linkTapMic(false); tapOn = false; src = "" }
                if (src == "tap" || src == "tapmic") {
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
                        if (!hasMic()) { return TxEnd(tr("需要录音权限：请在系统设置里允许 MicMask 使用麦克风"), true, false, "no mic permission") }
                        rec = openMic()
                        if (rec == null) { return TxEnd(tr("打开麦克风失败：可能被其他应用占用"), true, false, "openMic failed") }
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
                    upd { it.copy(txPackets = cl.sentPackets, txKbps = kbps, txRtt = cl.rttMs, txPeerLoss = cl.peerLoss ?: -1f, txPeerLatency = cl.peerLatency ?: -1, txCodec = cl.codec, txTransport = cl.transport) }
                    L.i("传音：发送 → $host:$port（${cl.transport}，${if (p == PROTO_MM) "自有协议 ${cl.codec}" else "MicYou PCM"}）音源=${when (src) { "tap" -> "变声输出"; "tapmic" -> "真麦（注入服务取，接收注入中）"; else -> "原始麦克风" }} 包 ${cl.sentPackets}（+${cl.sentPackets - lastPk}）" +
                        " 码率 $kbps kbps RTT ${cl.rttMs}ms 本端丢弃积压 ${txDropped * 1000 / SR}ms" + (if (cl.peerLoss != null) " | 对端统计 丢包 ${"%.1f".format(cl.peerLoss!! * 100)}% 抖动 ${"%.1f".format(cl.peerJitter ?: 0f)}ms 延迟估计 ${cl.peerLatency}ms" else " | 对端未回统计"))
                    lastStat = now; lastPk = cl.sentPackets; lastBytes = cl.sentBytes; firstLog = false
                } else if (now - lastStat >= 1000 && cl.sentPackets - lastPk > 0) {
                    upd { it.copy(txPackets = cl.sentPackets, txRtt = cl.rttMs, txPeerLoss = cl.peerLoss ?: -1f, txPeerLatency = cl.peerLatency ?: -1, txCodec = cl.codec, txTransport = cl.transport,
                        txKbps = if (it.txKbps == 0) ((cl.sentBytes - lastBytes) * 8 / (now - lastStat)).toInt() else it.txKbps) }
                }
            }
            if (cl.closed && txRun) {
                why = cl.closeReason
                endMsg = when {
                    why.startsWith("心跳超时") -> tr("连接已断开：对方超过 5 秒没有回应（网络中断或对方被系统冻结）")
                    why.startsWith("对方已断开") -> tr("对方已停止接收")
                    why.startsWith("被新连接接管") -> tr("对方被另一台设备连上了")
                    else -> tr("连接已断开")
                }
                // 对方主动停止 / 被别的设备接管时不自动抢回
                retry = !why.startsWith("对方已断开") && !why.startsWith("被新连接接管")
            }
        } finally {
            val c0 = client
            if (c0 != null && !c0.closed) c0.close(endMsg)
            // 同一连接双向：这条发送连接断了，接收显示也要复位（监听端另有会话时不动）
            val ms0 = (c0 as? MmTx)?.s
            if (ms0 != null && rxSrc === ms0) { rxSrc = null; if (server?.peer == null) onRxPeer(null, endMsg) }
            client = null
            rec?.let { runCatching { it.stop() }; it.release() }
            if (tapOn) { MicInjector.linkTap(false); MicInjector.linkTapMic(false) }
        }
        return TxEnd(endMsg, true, retry, why.ifEmpty { endMsg }, System.currentTimeMillis() - connectedAt)
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? = runCatching {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR / 25 * 2))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); null } else { r.startRecording(); r }
    }.getOrNull()

    // ================================================================= 接收

    @Volatile private var server: LinkRx? = null
    @Volatile private var rxStatThread: Thread? = null
    @Volatile private var rxRun = false
    private var nsdRegL: NsdManager.RegistrationListener? = null
    @Volatile private var rxProto = PROTO_MM
    /** 外放播放（普通模式，或变声占着注入通道时）：自适应抖动缓冲（漂移裁剪 + PLC）+ AudioTrack 线程 */
    private val playBuf = MmPlayout(PRIME_MS)
    /** 接收缓冲目标水位：随抖动、传输方式（TCP 时更高）、断流自适应（2.1.1-alpha.13） */
    private val rxTarget = MmBufTarget(udpMin = 60, udpMax = 140, tcpMin = 100, tcpMax = 200)
    /** 注入路径的漂移裁剪（推给 shell 前在低能量帧上丢 / 补） */
    private val rxDrift = MmDriftCtl(PRIME_MS)
    @Volatile private var rxTargetMs = PRIME_MS
    @Volatile private var shellPrimeMs = PRIME_MS
    /** 传音接收的注入从开始接管到停止期间为 true（发送端据此改取真麦，防回环） */
    @Volatile private var linkRxActive = false
    /** 当前接收音源（监听端会话 / 同一连接双向的发送会话 / MicYou）：两路同时来音频时只用正在出声的那一路 */
    @Volatile private var rxSrc: Any? = null
    @Volatile private var rxSrcMs = 0L
    @Volatile private var rxFadeIn = false
    /** 变声占着注入通道时改外放（提示一次） */
    @Volatile private var rxSpeakerFallback = false
    @Volatile private var playThread: Thread? = null
    @Volatile private var injectStarting = false
    @Volatile private var injected = false
    @Volatile private var lastPushMs = 0L
    /** 注入启动失败后 5 s 内不重试（否则每个包都会重试一次） */
    @Volatile private var injectFailMs = 0L
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var shizukuMode = false

    @Volatile private var rxRetryGen = 0

    /** [user] = 用户手动打开（记住「想接收」，下次打开 App 自动恢复）；自动连接调用时为 false */
    fun startReceive(user: Boolean = true) {
        val c = app ?: return
        if (rxRun) return
        if (user) { setRxWanted(true); rxRetryGen++ }
        val p = proto
        val port = if (p == PROTO_MM) MmLink.DEFAULT_PORT else MicYou.DEFAULT_PORT
        val srv: LinkRx = try {
            if (p == PROTO_MM) {
                val code = pairCode()
                val ms = MmLinkServer(port, mmCfg(MmLink.CAP_RECV), { pairCode() },
                    listenerFor = { object : MmSessionListener {
                        override fun onPcm(s: MmSession, pcm: ShortArray) = onRxPcmFrom(pcm, s)
                        override fun onClosed(s: MmSession, reason: String) = onRxPeer(null, reason)
                    } },
                    onSession = { ses -> ses.localBufMs = { _state.value.rxBufMs }; onRxPeer("${rememberSender(ses)}（${ses.peerIp.hostAddress}）", "") },
                    log = { L.i("传音：接收 $it") })
                ms.start(); upd { it.copy(code = code) }; MmRx(ms)
            } else {
                val mc = MicYouServer(port, onPcm = ::onRxPcm, onPeer = ::onRxPeer, log = { L.i("传音：接收 $it") }, bufMs = { _state.value.rxBufMs })
                mc.start(); MicYouRx(mc)
            }
        } catch (t: Throwable) {
            L.w("传音：接收端口 $port 打开失败：${t.message}")
            upd { it.copy(rxOn = false, rxStatus = tr("端口 {0} 被占用，无法开始接收（关掉占用它的程序或另一个 MicMask 后再试）", port)) }
            scheduleRxRetry()
            return
        }
        server = srv; rxRun = true; rxProto = p
        shizukuMode = MicInjector.shizukuGranted()
        val addrs = MmNet.localAddrs()
        upd { it.copy(rxOn = true, rxPeer = null, rxPackets = 0, rxLost = 0, rxBufMs = 0, rxLatency = -1, rxInjecting = false, rxPlayback = false, ips = addrs.filter { a -> !a.v6 }.map { a -> a.ip }, addrs = addrs,
            rxStatus = if (shizukuMode) tr("等待发送端连接…") else tr("等待发送端连接…（普通模式：只能外放播放，不能注入）")) }
        registerNsd(c)
        serviceSync()
        L.i("传音：接收开启（${if (shizukuMode) "注入模式" else "普通模式，外放播放"}）协议=${if (p == PROTO_MM) "MicMask 自有（加密，配对码）" else "兼容 MicYou"} 本机 ${addrs.joinToString { a -> "${a.ip}（${a.iface}${if (a.vpn.isNotEmpty()) " " + a.vpn else ""}）" }} 监听 0.0.0.0 端口 " +
            if (p == PROTO_MM) "$port（TCP/UDP）" else "$port/${port + 1}")
        rxStatThread = Thread({ rxMonitor() }, "link-rx-stat").apply { isDaemon = true; start() }
        syncTxIntent()
    }

    /** 端口打开失败且「想接收」+ 自动连接开着：5、10、20、30 秒退避重试 */
    private fun scheduleRxRetry() {
        if (!autoOn() || !rxWanted()) return
        val g = ++rxRetryGen
        Thread({
            var i = 0
            while (g == rxRetryGen && !rxRun && autoOn() && rxWanted() && i < 20) {
                val w = intArrayOf(5, 10, 20, 30)[minOf(i, 3)]; i++
                L.i("传音：自动连接 接收端口打开失败，${w}s 后重试（第 $i 次）")
                try { Thread.sleep(w * 1000L) } catch (_: InterruptedException) { return@Thread }
                if (g != rxRetryGen || rxRun) return@Thread
                main.post { if (g == rxRetryGen && !rxRun) startReceive(user = false) }
                try { Thread.sleep(1500) } catch (_: InterruptedException) { return@Thread }
            }
        }, "link-rx-retry").apply { isDaemon = true; start() }
    }

    /** 记住连到本机的发送端（按设备 ID），返回显示名（已重命名的用别名）；日志记录已知设备重新连入 */
    private fun rememberSender(ses: MmSession): String {
        val old = _state.value.saved.firstOrNull { it.id == ses.peerId }
        val ip = ses.peerIp.hostAddress ?: ""
        val d = upsertSaved(ses.peerId, ses.peerName, ip, MmLink.DEFAULT_PORT, "", PROTO_MM, ROLE_SENDER, ses.peerPlatform != "windows")
        if (old != null) L.i("传音：自动连接 已知设备「${d.label}」（ID ${ses.peerId.take(8)}）连入本机，地址 $ip${if (old.host != ip && old.host.isNotEmpty()) "（上次 ${old.host}）" else ""}")
        return d.label
    }

    /** [user] = 用户手动关闭（不再自动恢复接收） */
    fun stopReceive(user: Boolean = true) {
        if (user) { setRxWanted(false); rxRetryGen++ }
        if (!rxRun) return
        rxRun = false
        unregisterNsd()
        server?.stop(); server = null
        stopInject(tr("接收已关闭"))
        stopPlayback()
        upd { it.copy(rxOn = false, rxPeer = null, rxInjecting = false, rxPlayback = false, rxStatus = tr("已关闭")) }
        L.i("传音：接收关闭")
        rxSrc = null; rxSpeakerFallback = false
        syncTxIntent()
        serviceSync()
    }

    private fun registerNsd(c: Context) {
        val m = c.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val mm = rxProto == PROTO_MM
        val si = NsdServiceInfo().apply {
            serviceName = deviceName(); serviceType = if (mm) MmLink.MDNS_TYPE else MicYou.MDNS_TYPE; port = if (mm) MmLink.DEFAULT_PORT else MicYou.DEFAULT_PORT
            runCatching { setAttribute("app", "micmask") }
            if (mm) runCatching {
                setAttribute("v", MmLink.VERSION.toString()); setAttribute("plat", "android")
                setAttribute("id", deviceId().copyOf(4).joinToString("") { "%02x".format(it) })
            }
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

    /** MicYou 兼容接收（只有一路） */
    private fun onRxPcm(pcm: ShortArray) = onRxPcmFrom(pcm, "micyou")

    /** 当前接收音源的统计视图：同一连接双向时是发送会话，否则是监听端 */
    private fun rxView(): LinkRx? {
        val src = rxSrc
        val srv = server
        if (src is MmSession && !src.closed && (srv as? MmRx)?.c !== src && System.currentTimeMillis() - rxSrcMs < 3000) return MmRx(null, src)
        return srv
    }

    /**
     * 网络线程回调：每包 10 ms PCM，[src] = 来源会话。两路同时有音频（对端既连了本机「接收」，又在本机的发送连接上回传）时，
     * 只用正在出声的那一路（300 ms 内没新包才切到另一路），避免同一个声音叠两遍。
     */
    private fun onRxPcmFrom(pcm: ShortArray, src: Any) {
        if (!rxRun) return
        val now = System.currentTimeMillis()
        val cur = rxSrc
        if (cur != null && cur !== src && now - rxSrcMs < 300) return
        if (cur !== src) {
            rxSrc = src
            if (src is MmSession && src !== (server as? MmRx)?.c) {
                L.i("传音：接收 ← 同一连接双向（${src.peerName}，本机发送连接上回传的音频）")
                upd { it.copy(rxPeer = tr("{0}（同一连接）", src.peerName), rxStatus = tr("已连接 {0}，等待音频…", src.peerName)) }
            }
        }
        if (now - lastPushMs > 60) rxFadeIn = true
        rxSrcMs = now
        lastPushMs = now
        // 变声正在运行（占着注入通道）：不能注入远端声音，改外放，免得「什么都听不到」
        val voiceChangerBusy = shizukuMode && MicInjector.liveOn && !injected && !injectStarting && !linkRxActive
        if (shizukuMode && !voiceChangerBusy) {
            if (rxSpeakerFallback) { rxSpeakerFallback = false; stopPlayback() }
            if (injected && MicInjector.liveOn) { pushInject(pcm); return }
            if (!injected && !injectStarting && now - injectFailMs > 5000) {
                injectStarting = true
                main.post { injectStarting = false; startInject() }   // 绑定 Shizuku 服务等要在主线程
            }
        } else {
            if (voiceChangerBusy && !rxSpeakerFallback) {
                rxSpeakerFallback = true
                L.i("传音：变声正在运行，接收的声音不能注入，改为外放")
                upd { it.copy(rxStatus = tr("变声正在运行：接收的声音改为外放（停止变声后自动改回注入）")) }
            }
            if (playThread == null) startPlayback()
            playBuf.write(pcm)
        }
    }

    /** 注入路径：漂移裁剪（低能量帧上丢 / 补）+ 断流恢复淡入，然后推给 shell */
    private fun pushInject(pcm: ShortArray) {
        when (rxDrift.decide(pcm)) {
            MmDriftCtl.DROP -> return
            MmDriftCtl.INSERT -> MicInjector.livePush(if (MmDsp.quiet(pcm)) ShortArray(pcm.size) else pcm.copyOf())
        }
        val f = if (rxFadeIn) pcm.copyOf().also { MmDsp.fadeIn(it, 0, minOf(it.size, SR * 5 / 1000)); rxFadeIn = false } else pcm
        MicInjector.livePush(f)
    }

    private fun startInject() {
        val c = app ?: return
        if (!rxRun || (server?.peer == null && rxSrc !is MmSession)) return
        if (MicInjector.liveOn) {
            injectFailMs = System.currentTimeMillis()
            upd { it.copy(rxStatus = tr("变声正在运行：先停止变声，接收的声音才能注入")) }
            return
        }
        val (target, source) = targetProvider?.invoke() ?: (MicInjector.DEFAULT_TARGET to MediaRecorder.AudioSource.MIC)
        injectStarting = true
        MicInjector.modeLabel = tr("传音接收")
        val prime = rxTargetMs
        L.i("传音：开始注入远端音频 目标=${if (MicInjector.scopeAuto) "自动识别" else target} 预缓冲 ${prime}ms（随链路自适应）")
        upd { it.copy(rxStatus = tr("正在接管麦克风…")) }
        linkRxActive = true; shellPrimeMs = prime; rxDrift.reset(); rxDrift.targetMs = prime
        MicInjector.startLive(c, target, 0f, source, external = true, primeMs = prime, rvcInShell = false, linkRx = true) { msg ->
            if (MicInjector.liveOn && !injected) {
                injected = true; injectStarting = false
                // 远端音频已经是对方处理好的：效果链旁路、耳返关
                Thread { runCatching { MicInjector.setFx(FxChain.neutral()); MicInjector.setMonitor(false) } }.start()
                upd { it.copy(rxInjecting = true, rxStatus = tr("正在注入：对方说话会替代你的麦克风")) }
            }
            if (!MicInjector.liveOn) {
                val was = injected
                if (!was) injectFailMs = System.currentTimeMillis()
                injected = false; injectStarting = false; linkRxActive = false
                if (was || msg.isNotEmpty()) upd { it.copy(rxInjecting = false, rxStatus = if (rxRun) msg else it.rxStatus) }
            }
        }
    }

    private fun stopInject(why: String) {
        if (!injected && !injectStarting) { linkRxActive = false; return }
        injected = false; injectStarting = false; linkRxActive = false
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
            upd { it.copy(rxPlayback = true, rxStatus = if (rxSpeakerFallback) tr("变声正在运行：接收的声音改为外放（停止变声后自动改回注入）") else tr("正在外放播放（普通模式不能注入）")) }
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

    private fun stopPlayback() { playThread = null; playBuf.clear(); upd { it.copy(rxPlayback = false) } }

    /** 每秒刷新界面统计，每 5–10 s 写一行日志；3 s 没音频就停止注入、还原真麦 */
    private fun rxMonitor() {
        var lastLog = System.currentTimeMillis(); var firstLog = true; var lastPk = 0L; var lastLost = 0L
        var tick = 0
        var lastTargetLog = 0
        while (rxRun) {
            // 250 ms 一次：采样注入缓冲水位喂漂移裁剪、更新自适应目标；每秒刷新界面，每 5–10 s 写日志
            try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            tick++
            if (server == null) break
            val s = rxView() ?: break
            val now = System.currentTimeMillis()
            val level = when {
                injected -> MicInjector.outState()?.second ?: -1
                playThread != null -> playBuf.sizeMs()
                else -> -1
            }
            if (injected && level >= 0) rxDrift.report(level, now)
            // 自适应目标：TCP（UDP 不通 / 隧道）时更高
            val tcp = (s as? MmRx)?.tcp ?: (s.transport == "TCP")
            val under = if (injected) MicInjector.underNow.coerceAtLeast(0) else playBuf.underruns
            val target = rxTarget.update(s.jitterMs, tcp, s.rttMs.coerceAtLeast(0), under, now)
            rxTargetMs = target
            playBuf.targetMs = target
            rxDrift.targetMs = target
            if (injected && kotlin.math.abs(target - shellPrimeMs) >= 10) {
                if (MicInjector.linkPrime(target)) shellPrimeMs = target
                else shellPrimeMs = target   // 旧 shell 不支持：不再反复尝试（目标仍用于 App 侧裁剪）
            }
            if (kotlin.math.abs(target - lastTargetLog) >= 20) {
                L.i("传音：接收缓冲目标 → ${target}ms（${if (tcp) "TCP" else "UDP"}，抖动 ${"%.1f".format(s.jitterMs)}ms，RTT ${s.rttMs}ms，断流补偿 ${rxTarget.boostMs}ms）")
                lastTargetLog = target
            }
            if (tick % 4 != 0) continue
            if ((injected || injectStarting) && now - lastPushMs > 3000) {
                stopInject(tr("3 秒没有收到音频，已恢复真麦"))
                upd { it.copy(rxStatus = tr("音频中断，已恢复真麦；恢复后自动重新接管")) }
            }
            if (playThread != null && now - lastPushMs > 3000) stopPlayback()
            // STATS.bufMs = 最近 1 s 待播放队列的谷底（注入：shell 输出缓冲；外放：播放缓冲）
            val bufMs = when {
                injected -> rxDrift.lowWaterMs.takeIf { it >= 0 } ?: level.coerceAtLeast(0)
                playThread != null -> playBuf.lowWaterMs()
                else -> 0
            }
            val rtt = s.rttMs
            val lat = if (s.packets > 0) bufMs + (if (rtt >= 0) rtt / 2 else 0) + 10 else -1
            if (rxProto == PROTO_MICYOU && s.opusPackets > 0 && s.opusPackets == s.packets && s.packets % 50 == 0L)
                upd { it.copy(rxStatus = tr("对方发送的是 Opus 编码，当前版本只支持 PCM（请用 MicMask 发送）")) }
            upd { it.copy(rxPackets = s.packets, rxLost = s.lost, rxBufMs = bufMs, rxLatency = lat) }
            if (s.peer != null && now - lastLog >= (if (firstLog) 5000 else 10000)) {
                val total = s.packets + s.lost
                L.i("传音：接收 ← ${s.peer}（${s.transport.ifEmpty { "?" }}，${if (rxProto == PROTO_MM) "自有协议" else "MicYou"}${s.extra}）包 ${s.packets}（+${s.packets - lastPk}）丢包 ${s.lost}（+${s.lost - lastLost}，" +
                    "${if (total > 0) "%.1f".format(s.lost * 100.0 / total) else "0.0"}%）乱序 ${s.reordered} 迟到 ${s.late} 抖动 ${"%.1f".format(s.jitterMs)}ms " +
                    "缓冲 ${bufMs}ms（目标 $target）RTT ${rtt}ms 延迟估计 ${lat}ms " +
                    (if (injected) "注入=是 断流 ${MicInjector.underNow} 漂移丢 ${rxDrift.dropped * 10}ms 补 ${rxDrift.inserted * 10}ms" else if (playThread != null) "外放${if (rxSpeakerFallback) "（变声占用注入）" else ""} 断流 ${playBuf.underruns} 裁剪 ${playBuf.trimmedMs}ms 补 ${playBuf.insertedMs}ms PLC ${playBuf.plcFrames}" else "注入=否") +
                    (if (s.opusPackets > 0) " Opus包(未解) ${s.opusPackets}" else "") + (if (s.rejected > 0) " 拒收 ${s.rejected}" else ""))
                lastLog = now; firstLog = false; lastPk = s.packets; lastLost = s.lost
            }
        }
    }

    /** 全部停止（App 退出、通知栏「停止」） */
    fun stopAll() { stopSend(); stopReceive(); stopDiscovery() }

    /** 剪贴板文字里找 6 位配对码（允许「123 456」「123-456」写法）；找不到返回空 */
    fun findCode(text: CharSequence?): String {
        if (text.isNullOrEmpty() || text.length > 200) return ""
        return Regex("(?<!\\d)(\\d{3})[ -]?(\\d{3})(?!\\d)").find(text)?.let { it.groupValues[1] + it.groupValues[2] } ?: ""
    }
}
