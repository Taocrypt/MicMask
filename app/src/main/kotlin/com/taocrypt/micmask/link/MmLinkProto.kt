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

    // UDP
    val UDP_MAGIC = byteArrayOf('M'.code.toByte(), 'A'.code.toByte())
    const val U_AUDIO = 1
    const val U_PROBE = 2
    const val U_PROBE_ACK = 3
    const val UDP_HDR = 12
    const val UDP_AUDIO_HDR = 24
    const val TAG = 16
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
    fun encode(): ByteArray = BOut().u8(MmLink.M_CAPS).u32(flags).u8(preferCodec).u32(opusBitrate).u16(frameSamples).toBytes()
    companion object {
        fun decode(i: BIn) = MmCaps(i.u32(), i.u8(), i.u32(), i.u16())
        /** 发送方实际用的编码：发送方偏好 Opus 且接收方支持 Opus 才用 Opus，否则 PCM（双方必支持 PCM） */
        fun pickCodec(sender: MmCaps, receiver: MmCaps) = if (sender.preferCodec == MmLink.CODEC_OPUS && receiver.opus && sender.opus) MmLink.CODEC_OPUS else MmLink.CODEC_PCM16
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
