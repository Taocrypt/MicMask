package com.taocrypt.micmask.link

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

/*
 * MicMask Link v1 线上格式（纯 Kotlin，不依赖 Android；手机端与电脑端共用，规范见 docs/link-protocol.md）。
 *
 * TCP（默认 47310）：每帧 = u16 BE 长度（含类型字节，≤ 65535）| u8 类型 | 内容
 *   握手前（明文）：HELLO(0x01) → HELLO(0x01) → PAKE(0x02, A→B) → PAKE(0x02, B→A) → CONFIRM(0x03, A→B) → CONFIRM(0x03, B→A)
 *   握手后：SEALED(0x10) = u64 BE 计数 | AES-GCM(内层消息)；内层 = u8 消息类型 | 内容（CAPS/PING/PONG/STATS/BYE/AUDIO/UDP_OK）
 *   任一时刻：ERROR(0x7F) = u8 错误码 | UTF-8 说明（明文，只在握手失败时发）
 * UDP（与 TCP 同端口号）：数据报 = 明文头（AAD）| AES-GCM(内容)‖tag
 *   头 = "MA"(2) | ver(1) | 类型(1) | connId(4) | 包号 pn(4, 每方向递增，作 nonce)
 *   音频（类型 1）头后追加：seq(4) | ts(4, 发送端 48 kHz 样本计数) | codec(1) | flags(1) | 样本数(2)，加密内容 = 编码后的音频
 *   探测（类型 2）/ 探测回应（类型 3）：加密内容 = u64 发送时刻毫秒
 */
object MmLink {
    const val VERSION = 1
    const val MIN_VERSION = 1
    const val DEFAULT_PORT = 47310
    const val MDNS_TYPE = "_micmask._tcp"
    val MAGIC = "MMLK".toByteArray(Charsets.US_ASCII)
    const val SR = 48000
    const val FRAME = 480            // 10 ms
    const val MAX_FRAME = 65535

    // TCP 帧类型
    const val T_HELLO = 0x01
    const val T_PAKE = 0x02
    const val T_CONFIRM = 0x03
    const val T_SEALED = 0x10
    const val T_ERROR = 0x7F

    // 内层消息类型
    const val M_CAPS = 0x20
    const val M_PING = 0x21
    const val M_PONG = 0x22
    const val M_STATS = 0x23
    const val M_BYE = 0x24
    const val M_AUDIO = 0x25         // 音频走 TCP（USB/adb reverse 或 UDP 不通时）
    const val M_UDP_OK = 0x26

    // 错误码
    const val E_VERSION = 1
    const val E_BAD_CODE = 2
    const val E_BUSY = 3
    const val E_PROTOCOL = 4

    // codec
    const val CODEC_PCM16 = 0
    const val CODEC_OPUS = 1

    // 能力位
    const val CAP_SEND = 1           // 本端会发音频
    const val CAP_RECV = 2           // 本端想收音频
    const val CAP_OPUS = 4           // 支持 Opus
    const val CAP_PCM = 8            // 支持 PCM16
    /**
     * 本端 CAPS 里的 preferCodec 同时是「接收偏好」，请发送方照它选编码（2.1.1-alpha.13 起新版恒置位）。
     * 旧版不认识这一位（忽略未知位），照旧按发送方自己的偏好发，所以线格式与互通性不变。
     */
    const val CAP_PREF_RX = 16

    // UDP
    val UDP_MAGIC = byteArrayOf('M'.code.toByte(), 'A'.code.toByte())
    const val U_AUDIO = 1
    const val U_PROBE = 2
    const val U_PROBE_ACK = 3
    const val UDP_HDR = 12
    const val UDP_AUDIO_HDR = 24
    const val TAG = 16
    /**
     * UDP 数据报总长上限（含明文头、加密内容与 GCM tag），按隧道 MTU 留余量：Tailscale 1280、EasyTier/ZeroTier 约 1380–2800，
     * IPv6 + UDP 头 48 字节 → 1200 + 48 ≤ 1280。v1 的帧本来就满足（PCM 10 ms = 1000 字节，Opus 约 55–150 字节），超出时这一帧改走 TCP。
     */
    const val MAX_UDP_DATAGRAM = 1200
    /** 客户端握手后这么久没收到 UDP 探测回应就认定「UDP 暂不通」：日志提示、探测放慢（音频从一开始就走 TCP，不中断） */
    const val UDP_FALLBACK_MS = 1500L
    /** UDP 已通后，连续这么久收不到探测回应（客户端）/ 探测（服务端）就把音频切回 TCP，回应恢复后再切回 UDP */
    const val UDP_DEAD_MS = 3500L
}

class MmLinkException(val code: Int, msg: String) : IOException(msg)

/** 简单的大端写入器 */
class BOut {
    private val o = ByteArrayOutputStream()
    fun u8(v: Int) = apply { o.write(v and 0xff) }
    fun u16(v: Int) = apply { o.write((v ushr 8) and 0xff); o.write(v and 0xff) }
    fun u32(v: Int) = apply { for (i in 3 downTo 0) o.write((v ushr (8 * i)) and 0xff) }
    fun u64(v: Long) = apply { for (i in 7 downTo 0) o.write(((v ushr (8 * i)) and 0xff).toInt()) }
    fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))
    fun raw(b: ByteArray, off: Int = 0, len: Int = b.size) = apply { o.write(b, off, len) }
    fun bytes8(b: ByteArray) = apply { require(b.size <= 255); u8(b.size); raw(b) }
    fun str8(s: String) = bytes8(s.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it })
    fun toBytes(): ByteArray = o.toByteArray()
}

/** 简单的大端读取器；越界抛 MmLinkException(E_PROTOCOL) */
class BIn(private val b: ByteArray, var p: Int = 0, private val end: Int = b.size) {
    private fun need(n: Int) { if (p + n > end) throw MmLinkException(MmLink.E_PROTOCOL, "消息截断") }
    fun left() = end - p
    fun u8(): Int { need(1); return b[p++].toInt() and 0xff }
    fun u16(): Int { need(2); return ((b[p++].toInt() and 0xff) shl 8) or (b[p++].toInt() and 0xff) }
    fun u32(): Int { need(4); var v = 0; repeat(4) { v = (v shl 8) or (b[p++].toInt() and 0xff) }; return v }
    fun u64(): Long { need(8); var v = 0L; repeat(8) { v = (v shl 8) or (b[p++].toLong() and 0xff) }; return v }
    fun f32(): Float = java.lang.Float.intBitsToFloat(u32())
    fun raw(n: Int): ByteArray { need(n); val r = b.copyOfRange(p, p + n); p += n; return r }
    fun bytes8(): ByteArray = raw(u8())
    fun str8(): String = String(bytes8(), Charsets.UTF_8)
    fun rest(): ByteArray = raw(left())
}

/** HELLO：双方各发一次。connId 只有服务端填（客户端发 0） */
data class MmHello(
    val minVer: Int, val maxVer: Int,
    val deviceId: ByteArray,      // 16 字节，本机安装时随机生成并保存
    val name: String,             // 设备名（显示用）
    val platform: String,         // android / windows
    val nonce: ByteArray,         // 16 字节随机数，进 HKDF salt
    val connId: Int = 0,
    val needCode: Boolean = true, // 服务端：本次是否需要配对码（v1 恒为 true）
) {
    fun encode(): ByteArray = BOut().raw(MmLink.MAGIC).u8(minVer).u8(maxVer).raw(deviceId).str8(name).str8(platform).raw(nonce).u32(connId).u8(if (needCode) 1 else 0).toBytes()
    companion object {
        fun decode(b: ByteArray): MmHello {
            val i = BIn(b)
            if (!i.raw(4).contentEquals(MmLink.MAGIC)) throw MmLinkException(MmLink.E_PROTOCOL, "对方不是 MicMask Link 协议")
            val mn = i.u8(); val mx = i.u8()
            return MmHello(mn, mx, i.raw(16), i.str8(), i.str8(), i.raw(16), i.u32(), i.u8() != 0)
        }
        /** 版本协商：取双方共同支持的最高版本；没有交集返回 -1 */
        fun negotiate(a: MmHello, b: MmHello): Int { val v = minOf(a.maxVer, b.maxVer); return if (v >= maxOf(a.minVer, b.minVer)) v else -1 }
    }
}

/** CAPS：握手后双方各发一次，声明能力与本次会话意图 */
data class MmCaps(val flags: Int, val preferCodec: Int, val opusBitrate: Int, val frameSamples: Int = MmLink.FRAME) {
    val sends get() = flags and MmLink.CAP_SEND != 0
    val wantsRecv get() = flags and MmLink.CAP_RECV != 0
    val opus get() = flags and MmLink.CAP_OPUS != 0
    /** 对方声明 preferCodec 也是它的接收偏好（新版恒置位） */
    val prefRx get() = flags and MmLink.CAP_PREF_RX != 0
    fun encode(): ByteArray = BOut().u8(MmLink.M_CAPS).u32(flags).u8(preferCodec).u32(opusBitrate).u16(frameSamples).toBytes()
    companion object {
        fun decode(i: BIn) = MmCaps(i.u32(), i.u8(), i.u32(), i.u16())
        /**
         * 发送方实际用的编码（双方必支持 PCM；Opus 要双方都支持）：
         *  - [followReceiver] = true（默认，「接收方偏好优先」）且接收方声明了 CAP_PREF_RX → 用接收方的 preferCodec；
         *  - 否则（发送方选了「固定编码」，或接收方是旧版没有这一位）→ 用发送方自己的 preferCodec（v1 原规则）。
         */
        fun pickCodec(sender: MmCaps, receiver: MmCaps, followReceiver: Boolean = true): Int {
            if (!(sender.opus && receiver.opus)) return MmLink.CODEC_PCM16
            val want = if (followReceiver && receiver.prefRx) receiver.preferCodec else sender.preferCodec
            return if (want == MmLink.CODEC_OPUS) MmLink.CODEC_OPUS else MmLink.CODEC_PCM16
        }
    }
}

data class MmStats(val rttMs: Int, val jitterMs: Float, val loss: Float, val bufMs: Int, val latencyMs: Int) {
    fun encode(): ByteArray = BOut().u8(MmLink.M_STATS).u16(rttMs.coerceIn(0, 65535)).f32(jitterMs).f32(loss).u16(bufMs.coerceIn(0, 65535)).u16(latencyMs.coerceIn(0, 65535)).toBytes()
    companion object { fun decode(i: BIn) = MmStats(i.u16(), i.f32(), i.f32(), i.u16(), i.u16()) }
}

/** 一帧音频（解密后） */
class MmAudio(val seq: Int, val ts: Int, val codec: Int, val flags: Int, val samples: Int, val data: ByteArray)

object MmWire {
    /** TCP 帧：u16 长度（类型 + 内容）| 类型 | 内容 */
    fun frame(type: Int, body: ByteArray): ByteArray {
        val n = body.size + 1
        if (n > MmLink.MAX_FRAME) throw MmLinkException(MmLink.E_PROTOCOL, "帧过长 $n")
        return ByteArray(2 + n).also { it[0] = (n ushr 8).toByte(); it[1] = n.toByte(); it[2] = type.toByte(); System.arraycopy(body, 0, it, 3, body.size) }
    }

    /** 读一帧，返回 (类型, 内容) */
    fun readFrame(inp: DataInputStream): Pair<Int, ByteArray> {
        val n = inp.readUnsignedShort()
        if (n < 1) throw MmLinkException(MmLink.E_PROTOCOL, "空帧")
        val t = inp.readUnsignedByte()
        val b = ByteArray(n - 1); inp.readFully(b)
        return t to b
    }

    fun error(code: Int, msg: String) = frame(MmLink.T_ERROR, BOut().u8(code).str8(msg).toBytes())

    /** SEALED 帧内容：u64 计数 | GCM(内层)。AAD = 帧类型字节 */
    fun seal(box: GcmBox, ctr: Long, inner: ByteArray): ByteArray {
        val ct = box.seal(ctr, AAD_SEALED, 0, 1, inner, 0, inner.size)
        return frame(MmLink.T_SEALED, BOut().u64(ctr).raw(ct).toBytes())
    }

    fun open(box: GcmBox, body: ByteArray, replay: ReplayWindow): ByteArray {
        if (body.size < 8 + MmLink.TAG) throw MmLinkException(MmLink.E_PROTOCOL, "SEALED 太短")
        val ctr = BIn(body).u64()
        val pt = box.open(ctr, AAD_SEALED, 0, 1, body, 8, body.size - 8) ?: throw MmLinkException(MmLink.E_PROTOCOL, "控制消息认证失败")
        if (!replay.accept(ctr)) throw MmLinkException(MmLink.E_PROTOCOL, "控制消息重放")
        return pt
    }
    private val AAD_SEALED = byteArrayOf(MmLink.T_SEALED.toByte())

    // ------------------------------------------------------------------ UDP

    private fun udpHdr(type: Int, connId: Int, pn: Int) = BOut().raw(MmLink.UDP_MAGIC).u8(MmLink.VERSION).u8(type).u32(connId).u32(pn)

    fun udpAudio(box: GcmBox, connId: Int, pn: Int, a: MmAudio): ByteArray {
        val h = udpHdr(MmLink.U_AUDIO, connId, pn).u32(a.seq).u32(a.ts).u8(a.codec).u8(a.flags).u16(a.samples).toBytes()
        return h + box.seal(pn.toLong() and 0xffffffffL, h, 0, h.size, a.data, 0, a.data.size)
    }

    fun udpProbe(box: GcmBox, connId: Int, pn: Int, ack: Boolean, timeMs: Long): ByteArray {
        val h = udpHdr(if (ack) MmLink.U_PROBE_ACK else MmLink.U_PROBE, connId, pn).toBytes()
        val body = BOut().u64(timeMs).toBytes()
        return h + box.seal(pn.toLong() and 0xffffffffL, h, 0, h.size, body, 0, body.size)
    }

    /** 只解明文头：返回 (类型, connId, pn)；不是本协议返回 null */
    fun udpPeek(d: ByteArray, len: Int): Triple<Int, Int, Int>? {
        if (len < MmLink.UDP_HDR + MmLink.TAG || d[0] != MmLink.UDP_MAGIC[0] || d[1] != MmLink.UDP_MAGIC[1] || d[2].toInt() != MmLink.VERSION) return null
        val i = BIn(d, 3, len)
        return Triple(i.u8(), i.u32(), i.u32())
    }

    /** 解密音频数据报；认证失败返回 null（不检查重放，调用方用 pn 查 ReplayWindow） */
    fun udpOpenAudio(box: GcmBox, d: ByteArray, len: Int): MmAudio? {
        if (len < MmLink.UDP_AUDIO_HDR + MmLink.TAG) return null
        val i = BIn(d, MmLink.UDP_HDR, len)
        val seq = i.u32(); val ts = i.u32(); val codec = i.u8(); val fl = i.u8(); val ns = i.u16()
        val pn = BIn(d, 8, 12).u32()
        val pt = box.open(pn.toLong() and 0xffffffffL, d, 0, MmLink.UDP_AUDIO_HDR, d, MmLink.UDP_AUDIO_HDR, len - MmLink.UDP_AUDIO_HDR) ?: return null
        return MmAudio(seq, ts, codec, fl, ns, pt)
    }

    /** 解密探测包，返回对方发送时刻；失败 null */
    fun udpOpenProbe(box: GcmBox, d: ByteArray, len: Int): Long? {
        val pn = BIn(d, 8, 12).u32()
        val pt = box.open(pn.toLong() and 0xffffffffL, d, 0, MmLink.UDP_HDR, d, MmLink.UDP_HDR, len - MmLink.UDP_HDR) ?: return null
        return if (pt.size >= 8) BIn(pt).u64() else null
    }

    /** TCP 上的音频（内层 M_AUDIO）：seq | ts | codec | flags | 样本数 | 数据 */
    fun tcpAudio(a: MmAudio): ByteArray = BOut().u8(MmLink.M_AUDIO).u32(a.seq).u32(a.ts).u8(a.codec).u8(a.flags).u16(a.samples).raw(a.data).toBytes()
    fun tcpAudioDecode(i: BIn): MmAudio { val s = i.u32(); val t = i.u32(); val c = i.u8(); val f = i.u8(); val n = i.u16(); return MmAudio(s, t, c, f, n, i.rest()) }

    // ------------------------------------------------------------------ PCM

    fun pcmToBytes(s: ShortArray, off: Int, n: Int): ByteArray {
        val b = ByteArray(n * 2)
        for (k in 0 until n) { val v = s[off + k].toInt(); b[2 * k] = v.toByte(); b[2 * k + 1] = (v shr 8).toByte() }
        return b
    }
    fun bytesToPcm(b: ByteArray): ShortArray = ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xff) or (b[2 * it + 1].toInt() shl 8)).toShort() }
}


/**
 * 网络辅助（纯 java.net，两端共用）：本机全部网卡地址（含 VPN / 虚拟网卡）、手填地址解析、连接失败分类。
 */
object MmNet {
    /** 本机地址：接口名、IP、类型（wifi/hotspot/usb/ethernet/vpn/cellular/other）、VPN 提示（Tailscale/ZeroTier/EasyTier/空） */
    data class LocalAddr(val iface: String, val ip: String, val kind: String, val vpn: String, val v6: Boolean)

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** 按接口名与地址推断类型。Android 上第三方 VPN 一律是 tunN，只能按网段猜（EasyTier 默认 10.144.144.0/24，Tailscale 100.64.0.0/10） */
    fun kindOf(iface: String, ip: String): Pair<String, String> {
        val n = iface.lowercase()
        val vpn = when {
            n.startsWith("tailscale") || n.startsWith("utun") && ip.startsWith("100.") || isCgnat(ip) || ip.lowercase().startsWith("fd7a:115c:a1e0") -> "Tailscale"
            n.startsWith("zt") -> "ZeroTier"
            Regex("^et(\\d+|_.*)?$").matches(n) || n.contains("easytier") || ip.startsWith("10.144.144.") -> "EasyTier"
            else -> ""
        }
        val kind = when {
            vpn.isNotEmpty() || n.startsWith("tun") || n.startsWith("tap") || n.startsWith("ppp") || n.startsWith("wg") || n.startsWith("ipsec") || n.startsWith("utun") -> "vpn"
            n.startsWith("wlan") && n != "wlan1" || n.startsWith("wifi") -> "wifi"
            n.startsWith("ap") || n.startsWith("swlan") || n == "wlan1" || n.startsWith("softap") -> "hotspot"
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> "usb"
            n.startsWith("eth") || n.startsWith("en") -> "ethernet"
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") || n.startsWith("v4-rmnet") -> "cellular"
            else -> "other"
        }
        return kind to vpn
    }

    /** 100.64.0.0/10（运营商级 NAT，Tailscale 用这段） */
    fun isCgnat(ip: String): Boolean {
        val p = ip.split('.'); if (p.size != 4 || p[0] != "100") return false
        val b = p[1].toIntOrNull() ?: return false
        return b in 64..127
    }

    private fun kindRank(k: String) = when (k) { "wifi" -> 0; "hotspot" -> 1; "usb" -> 2; "ethernet" -> 3; "vpn" -> 4; "other" -> 5; else -> 6 }

    /** 本机全部可用地址：IPv4 在前，IPv6 只要全局/ULA（不要 fe80 链路本地）；Wi-Fi/热点/USB 优先，VPN 其次，蜂窝最后 */
    fun localAddrs(includeV6: Boolean = true): List<LocalAddr> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { ni -> ni.inetAddresses.toList().mapNotNull { a ->
                if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return@mapNotNull null
                val v6 = a is java.net.Inet6Address
                if (v6 && !includeV6) return@mapNotNull null
                val ip = (a.hostAddress ?: return@mapNotNull null).substringBefore('%')
                if (ip.startsWith("169.254.")) return@mapNotNull null
                val (k, v) = kindOf(ni.name, ip)
                LocalAddr(ni.name, ip, k, v, v6)
            } }
            .let { l -> l + TsBridge.current.tailnetAddrs().filter { ip -> l.none { it.ip == ip } }.map { ip -> LocalAddr("tailscale", ip, "vpn", "Tailscale", ip.contains(':')) } }
            .distinctBy { it.ip }
            .sortedWith(compareBy({ it.v6 }, { kindRank(it.kind) }, { it.iface }))
    }.getOrDefault(emptyList())

    /**
     * 扫码 / 多地址连接时的尝试顺序：与本机某个 IPv4 同 /24 的优先，其次私网局域网，再次隧道（VPN / CGNAT / 主机名），IPv6 最后。
     * 同类保持原顺序（接收端已按网卡类型排好）。
     */
    fun orderHosts(hosts: List<String>, local: List<String>): List<String> {
        fun p24(ip: String) = ip.split('.').takeIf { it.size == 4 }?.take(3)?.joinToString(".")
        val mine = local.mapNotNull { p24(it) }.toSet()
        fun rank(h: String) = when {
            h.contains(':') -> 3
            p24(h)?.let { it in mine } == true -> 0
            !looksRemote(h) -> 1
            else -> 2
        }
        return hosts.distinct().withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
    }

    /**
     * 解析手填地址：`host`、`host:port`、`[v6]:port`、裸 IPv6（含 2 个以上冒号）。去掉首尾空白和 http(s):// 前缀。
     * 端口非法或主机为空返回 null。
     */
    fun parseHostPort(input: String, defPort: Int): Pair<String, Int>? {
        var s = input.trim().removePrefix("http://").removePrefix("https://").removePrefix("micmask://").trimEnd('/')
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val e = s.indexOf(']'); if (e < 2) return null
            val h = s.substring(1, e); val rest = s.substring(e + 1)
            val port = if (rest.isEmpty()) defPort else if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else return null
            return if (port in 1..65535) h to port else null
        }
        if (s.count { it == ':' } >= 2) return s to defPort   // 裸 IPv6
        val i = s.lastIndexOf(':')
        if (i < 0) return s to defPort
        val h = s.substring(0, i); val port = s.substring(i + 1).toIntOrNull() ?: return null
        if (h.isEmpty() || port !in 1..65535) return null
        return h to port
    }

    /** 地址是否像跨网段 / 隧道（VPN、CGNAT、公网），用于提示「mDNS 搜不到请手填」与放宽超时 */
    fun looksRemote(host: String): Boolean {
        if (host == "127.0.0.1" || host == "localhost") return false
        if (host.contains(':')) return !host.lowercase().startsWith("fe80")
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return true   // 主机名
        return when {
            p[0] == 192 && p[1] == 168 -> false
            p[0] == 172 && p[1] in 16..31 -> false
            p[0] == 10 -> host.startsWith("10.144.144.")   // EasyTier 默认段算隧道
            else -> true
        }
    }

    // 连接失败分类（MmConnectFail.kind）
    const val F_TIMEOUT = 1        // TCP 连接超时：对方不在线 / 防火墙丢包 / 隧道没打通
    const val F_REFUSED = 2        // 对方明确拒绝：端口没开（没打开「接收」）
    const val F_UNREACHABLE = 3    // 本机没有到对方的路由 / 地址写错 / 主机名解析失败
    const val F_HS_TIMEOUT = 4     // TCP 连上了但对方不回握手：不是 MicMask、被别的程序占用端口、中间设备拦截
    const val F_CLOSED = 5         // 握手中对方断开：对方忙 / 不是 MicMask / 被防火墙重置
    const val F_OTHER = 9

    fun failText(kind: Int, host: String, port: Int): String = when (kind) {
        F_TIMEOUT -> "连接超时：$host:$port 没有响应（对方没开接收、防火墙拦截，或 VPN/打洞还没通）"
        F_REFUSED -> "连接被拒绝：$host 在线，但端口 $port 没在监听（对方没打开「接收」或协议选错）"
        F_UNREACHABLE -> "无法到达 $host（地址写错、不在同一网络，或 VPN 没连上）"
        F_HS_TIMEOUT -> "已连上 $host:$port，但对方没有回应握手（端口被别的程序占用，或对方不是 MicMask）"
        F_CLOSED -> "握手时对方断开了连接（对方不是 MicMask 自有协议，或连接被防火墙重置）"
        else -> "连接失败"
    }
}

/** 客户端连接失败（非协议错误）：[kind] 见 MmNet.F_*，[stage] 0 = TCP 连接，1 = 握手 */
class MmConnectFail(val kind: Int, val stage: Int, msg: String, cause: Throwable?) : IOException(msg, cause)

// ====================================================================== 二维码配对

/**
 * 二维码配对内容（2.1.1-alpha.13）：`micmask://link?h=<地址,…>&p=<端口>&c=<配对码>&id=<设备 ID 32 位十六进制>&n=<设备名>`。
 * 值做 URL 编码（UTF-8）；`h` 是逗号分隔的地址列表（IPv4 / IPv6 / 主机名，接收端全部网卡，局域网在前、VPN 其次、IPv6 最后）。
 * 只在本地二维码里出现，不上网络线格式；纯 Kotlin，两端共用。
 */
data class MmPairUri(val hosts: List<String>, val port: Int, val code: String, val id: String, val name: String) {
    fun encode(): String {
        fun e(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "micmask://link?h=${e(hosts.joinToString(","))}&p=$port&c=$code&id=${e(id)}&n=${e(name)}"
    }
    companion object {
        const val PREFIX = "micmask://link?"
        /** 解析扫到的文字；不是 MicMask 配对二维码、没有地址或配对码不是 6 位数字时返回 null */
        fun parse(text: String?): MmPairUri? {
            val t = text?.trim() ?: return null
            if (!t.startsWith(PREFIX, ignoreCase = true) || t.length > 2048) return null
            val q = t.substring(PREFIX.length).split('&').mapNotNull { kv ->
                val i = kv.indexOf('='); if (i <= 0) null else kv.substring(0, i) to runCatching { java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8") }.getOrNull()
            }.filter { it.second != null }.associate { it.first to it.second!! }
            val hosts = (q["h"] ?: "").split(',').map { it.trim().removePrefix("[").removeSuffix("]") }.filter { it.isNotEmpty() && it.length <= 253 && it.none { c -> c.isWhitespace() || c == '/' } }.distinct()
            val port = q["p"]?.toIntOrNull() ?: MmLink.DEFAULT_PORT
            val code = q["c"] ?: ""
            if (hosts.isEmpty() || port !in 1..65535 || code.length != 6 || !code.all(Char::isDigit)) return null
            val id = (q["id"] ?: "").lowercase().takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: ""
            return MmPairUri(hosts.take(12), port, code, id, (q["n"] ?: "").take(64))
        }
    }
}

// ====================================================================== 接收缓冲：目标水位、漂移裁剪、播放缓冲

/** 小工具：10 ms 帧的能量判断、淡入淡出 */
object MmDsp {
    /** 峰值低于这个（约 −40 dBFS）算「低能量帧」：在这里丢 / 插 10 ms 听不出来 */
    const val QUIET_PEAK = 330
    fun peak(s: ShortArray, off: Int = 0, n: Int = s.size - off): Int { var p = 0; for (i in off until off + n) { val a = kotlin.math.abs(s[i].toInt()); if (a > p) p = a }; return p }
    fun quiet(s: ShortArray, off: Int = 0, n: Int = s.size - off) = peak(s, off, n) < QUIET_PEAK
    fun fadeIn(s: ShortArray, off: Int, n: Int) { for (i in 0 until n) s[off + i] = (s[off + i] * (i + 1) / (n + 1)).toShort() }
    fun fadeOut(s: ShortArray, off: Int, n: Int) { for (i in 0 until n) s[off + i] = (s[off + i] * (n - i) / (n + 1)).toShort() }
}

/**
 * 接收缓冲目标水位（毫秒），两端共用的控制律：
 *  - UDP：clamp(2.5×抖动 + 30, 40, 120)；
 *  - TCP（UDP 不通 / USB / 隧道）：clamp(3×抖动 + RTT, 80, 200)——TCP 丢一个段要等重传（≈ 1 个 RTT）后面的包才一起到，缓冲至少要盖住一次重传；
 *  - 加上断流补偿：每次断流 +15 ms（最多 +80），20 s 没断流后每 5 s 回落 5 ms；
 *  - 目标升高立即生效，降低每 5 s 最多降 10 ms（避免抖来抖去）。
 * 单线程使用（接收端统计线程每 250 ms–1 s 调一次 [update]）。
 */
class MmBufTarget(private val udpMin: Int = 40, private val udpMax: Int = 120, private val tcpMin: Int = 80, private val tcpMax: Int = 200) {
    var targetMs = udpMin; private set
    var boostMs = 0; private set
    private var lastUnder = -1L
    private var lastUnderAt = 0L
    private var lastDecayAt = 0L
    private var lastDownAt = 0L

    fun base(jitterMs: Float, tcp: Boolean, rttMs: Int): Int =
        if (tcp) (3 * jitterMs + rttMs.coerceIn(0, 400)).toInt().coerceIn(tcpMin, tcpMax)
        else (2.5f * jitterMs + 30).toInt().coerceIn(udpMin, udpMax)

    /** [underruns] = 累计断流次数；返回新的目标水位 */
    fun update(jitterMs: Float, tcp: Boolean, rttMs: Int, underruns: Long, nowMs: Long): Int {
        if (lastUnder < 0) { lastUnder = underruns; lastUnderAt = nowMs; lastDecayAt = nowMs; lastDownAt = nowMs }
        if (underruns > lastUnder) { boostMs = (boostMs + 15 * (underruns - lastUnder).toInt().coerceAtMost(4)).coerceAtMost(80); lastUnder = underruns; lastUnderAt = nowMs; lastDecayAt = nowMs }
        else if (boostMs > 0 && nowMs - lastUnderAt > 20_000 && nowMs - lastDecayAt >= 5_000) { boostMs = (boostMs - 5).coerceAtLeast(0); lastDecayAt = nowMs }
        val want = (base(jitterMs, tcp, rttMs) + boostMs).coerceAtMost(if (tcp) tcpMax + 40 else udpMax + 60)
        if (want >= targetMs) { targetMs = want; lastDownAt = nowMs }
        else if (nowMs - lastDownAt >= 5_000) { targetMs = maxOf(want, targetMs - 10); lastDownAt = nowMs }
        return targetMs
    }
}

/**
 * 漂移裁剪决策（推入式：手机注入路径在把 10 ms 帧推给注入服务前调用；[MmPlayout] 内部也用它）。
 *  - [report] 喂当前水位（毫秒）与时刻；每 [windowMs] 结算一次：
 *    窗口最低水位 > 目标 + [highMs]（持续超标，不是一次突发）→ 要丢 (最低水位 − 目标) / 2，每窗口最多 [maxDropMs]；
 *    窗口最高水位 < 目标 且 最低水位 < 目标 − [lowMs]（持续偏低）→ 要补 (目标 − 最低水位) / 2，至少 10 ms、每窗口最多 [maxDropMs]。
 *  - [decide] 对每一帧给出动作：在低能量帧上执行（丢掉这帧 / 先插一帧静音）；等了 [maxWaitFrames] 帧还找不到低能量帧，
 *    丢帧时做交叉淡化强制执行，补帧时插一帧由上一帧镜像衰减出来的 PLC 帧。
 * 单线程使用；时间由调用方给（单测可快进）。
 */
class MmDriftCtl(@Volatile var targetMs: Int = 80, private val highMs: Int = 20, private val lowMs: Int = 30, private val windowMs: Int = 2000,
                 private val maxDropMs: Int = 40, private val maxWaitFrames: Int = 150) {
    companion object { const val KEEP = 0; const val DROP = -1; const val INSERT = 1 }
    private var winMin = Int.MAX_VALUE; private var winMax = Int.MIN_VALUE; private var winStart = -1L
    private var lowMin = Int.MAX_VALUE; private var lowStart = -1L
    /** 最近一个完整 1 秒窗口里的最低水位（谷底），用作 STATS.bufMs；-1 = 还没有 */
    @Volatile var lowWaterMs = -1; private set
    var dropMs = 0; private set
    var insertMs = 0; private set
    private var waited = 0
    var dropped = 0L; private set
    var inserted = 0L; private set
    var forced = 0L; private set

    fun report(levelMs: Int, nowMs: Long) {
        if (winStart < 0) { winStart = nowMs; lowStart = nowMs }
        if (levelMs < winMin) winMin = levelMs
        if (levelMs > winMax) winMax = levelMs
        if (levelMs < lowMin) lowMin = levelMs
        if (nowMs - lowStart >= 1000) { lowWaterMs = lowMin; lowMin = Int.MAX_VALUE; lowStart = nowMs }
        if (nowMs - winStart < windowMs) return
        val t = targetMs
        if (winMin > t + highMs) { dropMs = ((winMin - t) / 2).coerceAtMost(maxDropMs) / 10 * 10; insertMs = 0 }
        else if (winMax < t && winMin < t - lowMs && winMax > 0) { insertMs = ((t - winMin) / 2).coerceIn(10, maxDropMs) / 10 * 10; dropMs = 0 }
        else { dropMs = 0; insertMs = 0 }
        winMin = Int.MAX_VALUE; winMax = Int.MIN_VALUE; winStart = nowMs
    }

    /** 清掉待执行的动作（断流、重新预缓冲时） */
    fun reset() { dropMs = 0; insertMs = 0; waited = 0; winMin = Int.MAX_VALUE; winMax = Int.MIN_VALUE; winStart = -1L }

    /** 对即将送出的这一帧（10 ms）做决定：KEEP / DROP / INSERT（等太久后的强制执行计入 [forced]） */
    fun decide(frame: ShortArray, off: Int = 0, n: Int = frame.size - off): Int {
        if (dropMs <= 0 && insertMs <= 0) { waited = 0; return KEEP }
        val q = MmDsp.quiet(frame, off, n)
        val go = q || ++waited > maxWaitFrames
        if (!go) return KEEP
        if (!q) forced++
        waited = 0
        return if (dropMs > 0) { dropMs -= 10; dropped++; DROP } else { insertMs -= 10; inserted++; INSERT }
    }
}

/**
 * 拉取式播放缓冲（手机外放、可供电脑端使用）：网络线程 [write]，播放线程 [read]，线程安全。
 *  - 目标水位 [targetMs] 由上层按 [MmBufTarget] 设置（随抖动、TCP/UDP、断流自适应）；攒够目标才出声，出声时 2 ms 淡入；
 *  - 漂移：读之前按水位喂 [MmDriftCtl]，持续偏高就在低能量的 10 ms 段上丢掉（2 ms 交叉淡化接缝），持续偏低就在低能量处插 10 ms 静音；
 *  - 断流：已有的尾部之后用最后几毫秒「镜像衰减」补 10 ms（简单 PLC，避免硬切到 0 的爆音），然后静音并重新预缓冲；
 *  - 硬上限 [hardMs]：异常堆积时直接丢旧的回到目标。
 */
class MmPlayout(@Volatile var targetMs: Int = 80, private val hardMs: Int = 500, private val sr: Int = MmLink.SR) {
    private val cap = sr                       // 1 秒环形缓冲
    private val buf = ShortArray(cap)
    private var r = 0; private var n = 0
    private var primed = false
    private val ms = sr / 1000
    private val fade = 2 * ms
    private val frame = MmLink.FRAME
    private var needFadeIn = false
    private var clockMs = 0L
    private var clockRem = 0
    private val last = ShortArray(frame)
    private val tmp = ShortArray(frame)
    val drift = MmDriftCtl(targetMs)
    var underruns = 0L; private set
    var trimmedMs = 0L; private set
    var insertedMs = 0L; private set
    var plcFrames = 0L; private set

    @Synchronized fun size() = n
    @Synchronized fun sizeMs() = n / ms
    /** STATS.bufMs：最近 1 s 的最低水位（谷底）；还没有完整窗口时给瞬时值 */
    fun lowWaterMs(): Int = drift.lowWaterMs.takeIf { it >= 0 } ?: sizeMs()
    @Synchronized fun clear() { r = 0; n = 0; primed = false; drift.reset() }

    @Synchronized fun write(s: ShortArray, off: Int = 0, len: Int = s.size) {
        for (i in 0 until len) { buf[(r + n) % cap] = s[off + i]; if (n < cap) n++ else r = (r + 1) % cap }
        if (n > hardMs * ms) { val drop = n - targetMs * ms; r = (r + drop) % cap; n -= drop; trimmedMs += drop / ms; needFadeIn = true }
    }

    private fun peekQuiet(len: Int): Boolean { var p = 0; for (i in 0 until minOf(len, n)) { val a = kotlin.math.abs(buf[(r + i) % cap].toInt()); if (a > p) p = a }; return p < MmDsp.QUIET_PEAK }

    /** 总是填满 [len]（不够的补 PLC / 静音），返回其中真实音频的样本数 */
    @Synchronized fun read(dst: ShortArray, len: Int): Int {
        clockRem += len; clockMs += clockRem / ms; clockRem %= ms
        val target = targetMs * ms
        drift.targetMs = targetMs
        if (!primed && n >= target) { primed = true; needFadeIn = true; drift.reset() }
        if (!primed) { for (i in 0 until len) dst[i] = 0; return 0 }
        drift.report(n / ms, clockMs)
        // 漂移：动作在低能量段执行（decide 用即将读出的这一段判断）
        if (n >= len + frame + fade) {
            val m = minOf(frame, n); for (i in 0 until m) tmp[i] = buf[(r + i) % cap]
            when (drift.decide(tmp, 0, m)) {
                MmDriftCtl.DROP -> {
                    val drop = frame
                    for (i in 0 until fade) { val a = buf[(r + i) % cap].toInt(); val b = buf[(r + drop + i) % cap].toInt(); buf[(r + drop + i) % cap] = ((a * (fade - i) + b * i) / fade).toShort() }
                    r = (r + drop) % cap; n -= drop; trimmedMs += drop / ms
                }
                MmDriftCtl.INSERT -> {
                    // 插 len 个样本：低能量段直接给静音（两侧各 2 ms 渐变由前后音频本身很小保证），强制执行时给 PLC
                    plc(dst, 0, len, if (peekQuiet(frame)) 0 else len)
                    insertedMs += len / ms
                    return len
                }
            }
        }
        val k = minOf(len, n)
        for (i in 0 until k) { dst[i] = buf[r]; r = (r + 1) % cap }
        n -= k
        if (needFadeIn && k > 0) { MmDsp.fadeIn(dst, 0, minOf(fade, k)); needFadeIn = false }
        if (k < len) {
            // 断流：尾部之后补一段 PLC（镜像衰减，最多 10 ms），其余静音；重新预缓冲
            plc(dst, k, len - k, minOf(len - k, frame))
            primed = false; underruns++; needFadeIn = true; drift.reset()
        }
        if (len >= frame) System.arraycopy(dst, len - frame, last, 0, frame)
        return k
    }

    /** 从 dst[off] 起写 [count] 个样本：前 [plcN] 个是上一段末尾的镜像（连续无跳变）并线性衰减到 0，其余为 0 */
    private fun plc(dst: ShortArray, off: Int, count: Int, plcN: Int) {
        val src = if (off > 0) null else last
        for (i in 0 until count) {
            dst[off + i] = if (i < plcN) {
                val j = i + 1
                val v = if (src == null) dst[(off - j).coerceAtLeast(0)].toInt() else src[(frame - j).coerceAtLeast(0)].toInt()
                (v * (plcN - i) / (plcN + 1)).toShort()
            } else 0
        }
        if (plcN > 0) plcFrames++
    }
}
