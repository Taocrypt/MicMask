package com.taocrypt.micmask.link

/*
 * 传音：MicYou 局域网协议的编解码（纯 Kotlin，不依赖 Android，可单独用 kotlinc 测试）。
 *
 * 协议依据 MicYou（https://github.com/MicYou-Dev/MicYou，Copyright (C) 2026 LanRhyme，
 * GPL-3.0-or-later + MicYou Plugin Exception 1.0）的公开源码重新实现，未复制其代码：
 *   tauri-app/crates/micyou-protocol/proto/network.proto（消息与字段号）、micyou-protocol/src/lib.rs（魔数、握手串、mDNS 类型）、
 *   micyou-core/src/transport/{tcp,udp,session}.rs（帧格式、长度上限、会话校验、心跳超时）、
 *   composeApp/.../network/Protocol.kt、audio/AudioEngine.kt（安卓发送端的握手与发包方式）。
 * 详见 NOTICE.md。
 *
 * 线上格式：
 *   TCP（默认 8554）：客户端发 ASCII "MicYouCheck1" → 服务端回 "MicYouCheck2"；之后每条消息 = int32 BE 0x4D696359("MicY") | int32 BE 长度 | Protobuf MessageWrapper。
 *   UDP（TCP 端口 + 1）：每个数据报 = int32 BE 0x4D696355("MicU") | int32 BE 长度 | Protobuf MessageWrapper{audioPacket}。
 */
object MicYou {
    const val HANDSHAKE_CLIENT = "MicYouCheck1"
    const val HANDSHAKE_SERVER = "MicYouCheck2"
    const val PACKET_MAGIC = 0x4D696359
    const val UDP_PACKET_MAGIC = 0x4D696355
    const val DEFAULT_PORT = 8554
    const val UDP_PORT_OFFSET = 1
    const val MDNS_TYPE = "_micyou._tcp"
    const val MAX_CONTROL_PAYLOAD = 1024 * 1024
    const val UDP_MAX_DATAGRAM = 1472
    const val MAX_AUDIO_BUFFER = 64 * 1024
    const val CODEC_PCM = 0
    const val CODEC_OPUS = 1
    /** android.media.AudioFormat.ENCODING_PCM_16BIT / 8BIT / FLOAT / 24BIT_PACKED */
    const val FMT_PCM16 = 2
    const val FMT_PCM8 = 3
    const val FMT_FLOAT = 4
    const val FMT_PCM24 = 6
    const val SR = 48000
    /** 10 ms @ 48 kHz 单声道 = 960 字节，加上 protobuf 与 8 字节头约 1000 字节 < 1472，不分片 */
    const val FRAME = 480
}

class AudioPkt(
    val seq: Int,
    val buffer: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val format: Int,
    val codec: Int,
    val timestamp: Long,
    val sessionId: Long,
    /** fecBuffer 非空 = XOR 校验包（MicYou 的 FEC，组大小 12）；本实现不发，也不用它恢复，收到就丢 */
    val isFec: Boolean,
)

class ConnectMsg(val sessionId: Long, val platform: String, val transport: String)
class StatsMsg(val rttMs: Int, val jitterMs: Float, val packetLoss: Float, val latencyMs: Int)

/** MessageWrapper 里本实现关心的字段；其余（mute=3、pluginMessage=7）解码时跳过 */
class Wrapper(
    val audio: AudioPkt? = null,
    val connect: ConnectMsg? = null,
    val ping: Long? = null,
    val pong: Long? = null,
    val stats: StatsMsg? = null,
    val hasMute: Boolean = false,
)

/** 最小 protobuf（proto3）写入器：默认值（0 / 空）不写，与 prost / kotlinx-serialization-protobuf 一致 */
class PbOut {
    private var b = ByteArray(64)
    var size = 0; private set
    private fun ensure(n: Int) { if (size + n > b.size) b = b.copyOf(maxOf(b.size * 2, size + n)) }
    fun raw(x: Int) { ensure(1); b[size++] = x.toByte() }
    fun varint(v0: Long) { var v = v0; ensure(10); while (true) { if (v and 0x7fL.inv() == 0L) { b[size++] = v.toByte(); return }; b[size++] = ((v and 0x7f) or 0x80).toByte(); v = v ushr 7 } }
    private fun tag(f: Int, wt: Int) = varint(((f shl 3) or wt).toLong())
    /** int32：负数按 64 位符号扩展写 10 字节（proto3 规范） */
    fun int32(f: Int, v: Int) { if (v != 0) { tag(f, 0); varint(v.toLong()) } }
    fun int64(f: Int, v: Long) { if (v != 0L) { tag(f, 0); varint(v) } }
    fun float(f: Int, v: Float) { if (v != 0f) { tag(f, 5); val x = java.lang.Float.floatToIntBits(v); ensure(4); for (i in 0 until 4) b[size++] = (x ushr (8 * i)).toByte() } }
    fun bytes(f: Int, v: ByteArray, off: Int = 0, len: Int = v.size) { if (len > 0) { tag(f, 2); varint(len.toLong()); ensure(len); System.arraycopy(v, off, b, size, len); size += len } }
    fun string(f: Int, v: String) = bytes(f, v.toByteArray(Charsets.UTF_8))
    /** 嵌套消息：proto3 里出现即「有」，空消息也要写（长度 0） */
    fun msg(f: Int, m: PbOut) { tag(f, 2); varint(m.size.toLong()); ensure(m.size); System.arraycopy(m.b, 0, b, size, m.size); size += m.size }
    fun toBytes(): ByteArray = b.copyOf(size)
}

class PbFormatException(msg: String) : Exception(msg)

/** 最小 protobuf 读取器：按字段号回调，未知字段按线型跳过 */
class PbIn(private val b: ByteArray, private var p: Int = 0, private val end: Int = b.size) {
    fun more() = p < end
    fun varint(): Long {
        var r = 0L; var s = 0
        while (true) {
            if (p >= end) throw PbFormatException("varint 截断")
            val x = b[p++].toInt() and 0xff
            r = r or ((x and 0x7f).toLong() shl s)
            if (x and 0x80 == 0) return r
            s += 7; if (s > 63) throw PbFormatException("varint 过长")
        }
    }
    fun fixed32(): Int { if (p + 4 > end) throw PbFormatException("fixed32 截断"); var x = 0; for (i in 0 until 4) x = x or ((b[p++].toInt() and 0xff) shl (8 * i)); return x }
    fun lenDelim(): PbIn { val n = varint(); if (n < 0 || p + n > end) throw PbFormatException("长度越界"); val s = PbIn(b, p, (p + n).toInt()); p += n.toInt(); return s }
    fun bytes(): ByteArray { val n = varint(); if (n < 0 || p + n > end) throw PbFormatException("长度越界"); val r = b.copyOfRange(p, (p + n).toInt()); p += n.toInt(); return r }
    fun skip(wt: Int) {
        when (wt) {
            0 -> varint()
            1 -> { if (p + 8 > end) throw PbFormatException("fixed64 截断"); p += 8 }
            2 -> lenDelim()
            5 -> fixed32()
            else -> throw PbFormatException("不支持的线型 $wt")
        }
    }
    inline fun fields(on: (Int, Int) -> Boolean) {
        while (more()) {
            val t = varint().toInt()
            val f = t ushr 3; val wt = t and 7
            if (f == 0) throw PbFormatException("字段号 0")
            if (!on(f, wt)) skip(wt)
        }
    }
}

object MicYouCodec {
    // ---------------------------------------------------------------- 编码

    fun audio(seq: Int, pcm: ByteArray, off: Int, len: Int, sessionId: Long, timestamp: Long,
              sampleRate: Int = MicYou.SR, channels: Int = 1, format: Int = MicYou.FMT_PCM16, codec: Int = MicYou.CODEC_PCM): ByteArray {
        val ap = PbOut().apply { bytes(1, pcm, off, len); int32(2, sampleRate); int32(3, channels); int32(4, format); int32(5, codec) }
        val ord = PbOut().apply { int32(1, seq); msg(2, ap); int64(3, timestamp); int64(6, sessionId) }
        return PbOut().apply { msg(1, ord) }.toBytes()
    }

    /** s16 单声道样本 → 小端字节后编码成音频包 */
    fun audioPcm16(seq: Int, s: ShortArray, off: Int, n: Int, sessionId: Long, timestamp: Long): ByteArray {
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) { val v = s[off + i].toInt(); bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
        return audio(seq, bytes, 0, bytes.size, sessionId, timestamp)
    }

    fun connect(sessionId: Long, platform: String, transport: String): ByteArray {
        val c = PbOut().apply { int64(1, sessionId); string(2, platform); string(3, transport) }
        return PbOut().apply { msg(2, c) }.toBytes()
    }

    fun ping(ts: Long): ByteArray = PbOut().apply { msg(5, PbOut().apply { int64(1, ts) }) }.toBytes()
    fun pong(ts: Long): ByteArray = PbOut().apply { msg(6, PbOut().apply { int64(1, ts) }) }.toBytes()
    fun stats(rttMs: Int, jitterMs: Float, packetLoss: Float, latencyMs: Int): ByteArray =
        PbOut().apply { msg(8, PbOut().apply { int32(1, rttMs); float(2, jitterMs); float(3, packetLoss); int32(4, latencyMs) }) }.toBytes()

    // ---------------------------------------------------------------- 帧

    private fun be32(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte() }
    fun rdBe32(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    fun tcpFrame(payload: ByteArray): ByteArray = ByteArray(8 + payload.size).also { be32(it, 0, MicYou.PACKET_MAGIC); be32(it, 4, payload.size); System.arraycopy(payload, 0, it, 8, payload.size) }
    fun udpDatagram(payload: ByteArray): ByteArray = ByteArray(8 + payload.size).also { be32(it, 0, MicYou.UDP_PACKET_MAGIC); be32(it, 4, payload.size); System.arraycopy(payload, 0, it, 8, payload.size) }

    /** TCP 帧头 → 负载长度；魔数/长度不合法抛异常（与桌面端 parse_frame_header 一致：负数、超过 1 MiB 都拒绝） */
    fun tcpHeaderLen(h: ByteArray): Int {
        if (rdBe32(h, 0) != MicYou.PACKET_MAGIC) throw PbFormatException("TCP 帧魔数不对")
        val n = rdBe32(h, 4)
        if (n < 0 || n > MicYou.MAX_CONTROL_PAYLOAD) throw PbFormatException("TCP 帧长度不合法：$n")
        return n
    }

    /** UDP 数据报 → 负载区间 [off, off+len)；不合法返回 null（与桌面端 parse_datagram 一致，允许尾部多余字节） */
    fun udpPayload(d: ByteArray, len: Int): IntArray? {
        if (len < 8 || rdBe32(d, 0) != MicYou.UDP_PACKET_MAGIC) return null
        val n = rdBe32(d, 4)
        if (n < 0 || n > MicYou.MAX_AUDIO_BUFFER || 8 + n > len) return null
        return intArrayOf(8, n)
    }

    // ---------------------------------------------------------------- 解码

    fun decode(b: ByteArray, off: Int = 0, len: Int = b.size - off): Wrapper {
        var audio: AudioPkt? = null; var connect: ConnectMsg? = null; var ping: Long? = null; var pong: Long? = null; var stats: StatsMsg? = null; var mute = false
        val p = PbIn(b, off, off + len)
        p.fields { f, wt ->
            if (wt != 2) false
            else when (f) {
                1 -> { audio = decodeOrdered(p.lenDelim()); true }
                2 -> { connect = decodeConnect(p.lenDelim()); true }
                3 -> { p.lenDelim(); mute = true; true }
                5 -> { ping = decodeTs(p.lenDelim()); true }
                6 -> { pong = decodeTs(p.lenDelim()); true }
                8 -> { stats = decodeStats(p.lenDelim()); true }
                else -> false
            }
        }
        return Wrapper(audio, connect, ping, pong, stats, mute)
    }

    private fun decodeTs(p: PbIn): Long { var t = 0L; p.fields { f, wt -> if (f == 1 && wt == 0) { t = p.varint(); true } else false }; return t }

    private fun decodeConnect(p: PbIn): ConnectMsg {
        var sid = 0L; var plat = ""; var tr = ""
        p.fields { f, wt -> when {
            f == 1 && wt == 0 -> { sid = p.varint(); true }
            f == 2 && wt == 2 -> { plat = String(p.bytes(), Charsets.UTF_8); true }
            f == 3 && wt == 2 -> { tr = String(p.bytes(), Charsets.UTF_8); true }
            else -> false } }
        return ConnectMsg(sid, plat, tr)
    }

    private fun decodeStats(p: PbIn): StatsMsg {
        var rtt = 0; var jit = 0f; var loss = 0f; var lat = 0
        p.fields { f, wt -> when {
            f == 1 && wt == 0 -> { rtt = p.varint().toInt(); true }
            f == 2 && wt == 5 -> { jit = java.lang.Float.intBitsToFloat(p.fixed32()); true }
            f == 3 && wt == 5 -> { loss = java.lang.Float.intBitsToFloat(p.fixed32()); true }
            f == 4 && wt == 0 -> { lat = p.varint().toInt(); true }
            else -> false } }
        return StatsMsg(rtt, jit, loss, lat)
    }

    private fun decodeOrdered(p: PbIn): AudioPkt {
        var seq = 0; var ts = 0L; var sid = 0L; var fec = false
        var buf = ByteArray(0); var sr = 0; var ch = 0; var fmt = 0; var codec = 0
        p.fields { f, wt -> when {
            f == 1 && wt == 0 -> { seq = p.varint().toInt(); true }
            f == 2 && wt == 2 -> {
                val a = p.lenDelim()
                a.fields { g, w2 -> when {
                    g == 1 && w2 == 2 -> { buf = a.bytes(); true }
                    g == 2 && w2 == 0 -> { sr = a.varint().toInt(); true }
                    g == 3 && w2 == 0 -> { ch = a.varint().toInt(); true }
                    g == 4 && w2 == 0 -> { fmt = a.varint().toInt(); true }
                    g == 5 && w2 == 0 -> { codec = a.varint().toInt(); true }
                    else -> false } }
                true
            }
            f == 3 && wt == 0 -> { ts = p.varint(); true }
            f == 4 && wt == 2 -> { fec = p.bytes().isNotEmpty(); true }
            f == 6 && wt == 0 -> { sid = p.varint(); true }
            else -> false } }
        return AudioPkt(seq, buf, sr, ch, fmt, codec, ts, sid, fec)
    }

    /** 与桌面端 validate_audio_packet 同样的基本校验：1–2 声道、8–192 kHz、缓冲非空 ≤ 64 KiB、PCM 长度按帧对齐 */
    fun valid(a: AudioPkt): Boolean {
        if (a.channels !in 1..2 || a.sampleRate !in 8000..192000 || a.buffer.isEmpty() || a.buffer.size > MicYou.MAX_AUDIO_BUFFER) return false
        if (a.codec == MicYou.CODEC_OPUS) return true
        val bps = when (a.format) { MicYou.FMT_PCM16 -> 2; MicYou.FMT_PCM8 -> 1; MicYou.FMT_FLOAT -> 4; MicYou.FMT_PCM24 -> 3; else -> return false }
        return a.buffer.size % (bps * a.channels) == 0
    }

    // ---------------------------------------------------------------- PCM

    /** PCM（16/8/float/24 位，单/双声道，任意采样率）→ 48 kHz 单声道 s16。双声道取平均；采样率不同时线性插值（包内独立，MVP 足够）。Opus 返回 null */
    fun toMono48k(a: AudioPkt): ShortArray? {
        if (a.codec != MicYou.CODEC_PCM) return null
        val ch = a.channels.coerceAtLeast(1)
        val b = a.buffer
        val bps = when (a.format) { MicYou.FMT_PCM16 -> 2; MicYou.FMT_PCM8 -> 1; MicYou.FMT_FLOAT -> 4; MicYou.FMT_PCM24 -> 3; else -> return null }
        val frames = b.size / (bps * ch)
        // 常见情况（MicMask 发送端）：48 kHz 单声道 16 位，直接拷贝，无损
        if (a.format == MicYou.FMT_PCM16 && ch == 1 && a.sampleRate == MicYou.SR)
            return ShortArray(frames) { i -> ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort() }
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until ch) {
                val o = (i * ch + c) * bps
                acc += when (a.format) {
                    MicYou.FMT_PCM16 -> ((b[o].toInt() and 0xff) or (b[o + 1].toInt() shl 8)).toShort() / 32768f
                    MicYou.FMT_PCM8 -> ((b[o].toInt() and 0xff) - 128) / 128f
                    MicYou.FMT_FLOAT -> java.lang.Float.intBitsToFloat((b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or (b[o + 3].toInt() shl 24)).let { if (it.isFinite()) it else 0f }
                    else -> ((b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or (b[o + 2].toInt() shl 16)) / 8388608f
                }
            }
            mono[i] = acc / ch
        }
        val out = if (a.sampleRate == MicYou.SR || frames == 0) mono else {
            val n = (frames.toLong() * MicYou.SR / a.sampleRate).toInt().coerceAtLeast(1)
            val step = a.sampleRate.toDouble() / MicYou.SR
            FloatArray(n) { j -> val x = j * step; val i0 = x.toInt().coerceAtMost(frames - 1); val i1 = minOf(i0 + 1, frames - 1); val t = (x - i0).toFloat(); mono[i0] * (1 - t) + mono[i1] * t }
        }
        return ShortArray(out.size) { (out[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort() }
    }
}

/**
 * 接收端按序号重排：连续的直接交付；乱序的先存着，等到比缺口晚 [gapWait] 个包到了才认定丢包、跳过缺口（与桌面端 jitter_buffer 的「晚 5 个包」思路一致，这里取 3 个包 = 30 ms，延迟优先）。
 * 早于期望序号的（迟到/重复）丢弃；序号突然大跳（对端重启计数）就重新同步。
 */
class SeqReorder(private val gapWait: Int = 3, private val maxHold: Int = 64) {
    private val held = java.util.TreeMap<Int, ShortArray>()
    private var expected = 0
    private var maxSeen = 0
    private var init = false
    var received = 0L; private set
    var lost = 0L; private set
    var late = 0L; private set
    var reordered = 0L; private set
    var resyncs = 0L; private set

    fun reset() { held.clear(); init = false }

    fun push(seq: Int, pcm: ShortArray, out: (ShortArray) -> Unit) {
        received++
        if (!init) { expected = seq; maxSeen = seq; init = true }
        if (seq < expected) {
            // 对端重新开始计数（新会话从 0 起）：差得很远就当重启
            if (expected - seq > 1000) { resyncs++; held.clear(); expected = seq; maxSeen = seq } else { late++; return }
        }
        if (seq > expected + 1000) { resyncs++; held.clear(); expected = seq; maxSeen = seq }
        if (seq < maxSeen) reordered++ else maxSeen = seq
        held[seq] = pcm
        drain(out)
    }

    private fun drain(out: (ShortArray) -> Unit) {
        while (held.isNotEmpty()) {
            val first = held.firstKey()
            if (first == expected) { out(held.remove(first)!!); expected++; continue }
            val last = held.lastKey()
            if (last >= expected + gapWait || held.size > maxHold) { lost += (first - expected).toLong(); expected = first; continue }
            return
        }
    }
}

/**
 * 外放播放用的抖动缓冲（普通模式接收、或注入不可用时）：攒够 [primeN] 才开始放；读空 = 断流，重新攒；
 * 水位超过 [maxN] 就丢最旧的回到 [primeN]（吸收时钟漂移和突发积压）。线程安全。
 */
class PlayoutBuffer(private val primeN: Int, private val maxN: Int, cap: Int = MicYou.SR) {
    private val buf = ShortArray(cap)
    private var r = 0; private var n = 0
    private var primed = false
    var underruns = 0L; private set
    var trimmed = 0L; private set
    @Synchronized fun size() = n
    @Synchronized fun clear() { r = 0; n = 0; primed = false }
    @Synchronized fun write(s: ShortArray, off: Int = 0, len: Int = s.size) {
        for (i in 0 until len) { buf[(r + n) % buf.size] = s[off + i]; if (n < buf.size) n++ else r = (r + 1) % buf.size }
        if (n > maxN) { val drop = n - primeN; r = (r + drop) % buf.size; n -= drop; trimmed += drop }
    }
    /** 总是填满 [len]（不够的补 0），返回其中真实音频的样本数 */
    @Synchronized fun read(dst: ShortArray, len: Int): Int {
        if (!primed && n >= primeN) primed = true
        val k = if (primed) minOf(len, n) else 0
        for (i in 0 until k) { dst[i] = buf[r]; r = (r + 1) % buf.size }
        n -= k
        for (i in k until len) dst[i] = 0
        if (primed && k < len) { primed = false; underruns++ }
        return k
    }
}

/** RFC 3550 风格的到达抖动估计：D = (到达间隔) − (发送时间戳间隔)，J += (|D| − J) / 16；时间戳缺失（0）时按 10 ms/包估计 */
class JitterMeter {
    private var lastArr = -1L; private var lastTs = 0L
    var jitterMs = 0f; private set
    fun observe(arrivalMs: Long, senderTs: Long, frameMs: Int) {
        if (lastArr >= 0) {
            val dTs = if (senderTs > 0 && lastTs > 0) senderTs - lastTs else frameMs.toLong()
            val d = (arrivalMs - lastArr) - dTs
            jitterMs += (kotlin.math.abs(d.toFloat()) - jitterMs) / 16f
        }
        lastArr = arrivalMs; lastTs = senderTs
    }
    fun reset() { lastArr = -1L; lastTs = 0L; jitterMs = 0f }
}
