package com.taocrypt.micmask.link

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal

/*
 * MicMask Link 的 Opus 编解码（Concentus 1.0.2：libopus 的纯 Java 移植，BSD-3-Clause，手机与电脑共用）。
 * 48 kHz 单声道、10 ms 一帧（480 样本）、VOIP 模式、带内 FEC 开、DTX 关（静音段也照常出包，接收端判断断流更简单）。
 */
class MmOpusEncoder(bitrate: Int = DEFAULT_BITRATE, complexity: Int = 5) {
    private val enc = OpusEncoder(MmLink.SR, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(bitrate)
        setComplexity(complexity)
        setUseInbandFEC(true)
        setPacketLossPercent(10)
        setUseDTX(false)
        setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
    }
    private val out = ByteArray(1500)

    /** 编码一帧（必须正好 480 样本）→ Opus 包 */
    fun encode(pcm: ShortArray, off: Int = 0): ByteArray {
        val n = enc.encode(pcm, off, MmLink.FRAME, out, 0, out.size)
        return out.copyOf(n)
    }

    companion object { const val DEFAULT_BITRATE = 48_000 }
}

class MmOpusDecoder {
    private val dec = OpusDecoder(MmLink.SR, 1)
    var plcFrames = 0L; private set
    var fecFrames = 0L; private set

    fun decode(pkt: ByteArray): ShortArray {
        val o = ShortArray(MmLink.FRAME)
        val n = dec.decode(pkt, 0, pkt.size, o, 0, MmLink.FRAME, false)
        return if (n == MmLink.FRAME) o else o.copyOf(n.coerceAtLeast(0))
    }

    /**
     * 丢了 [lost] 帧、下一包是 [next]：最后一帧用 next 里的带内 FEC 恢复，更早的用 PLC 补；最多补 [maxConceal] 帧（之后由抖动缓冲当断流处理）。
     */
    fun conceal(lost: Int, next: ByteArray?, maxConceal: Int = 5): List<ShortArray> {
        val k = lost.coerceAtMost(maxConceal)
        val r = ArrayList<ShortArray>(k)
        for (i in 0 until k) {
            val o = ShortArray(MmLink.FRAME)
            val useFec = i == k - 1 && next != null
            runCatching {
                if (useFec) { dec.decode(next, 0, next.size, o, 0, MmLink.FRAME, true); fecFrames++ }
                else { dec.decode(null, 0, 0, o, 0, MmLink.FRAME, false); plcFrames++ }
            }
            r.add(o)
        }
        return r
    }
}
