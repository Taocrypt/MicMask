package com.taocrypt.micmask

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.io.File

/**
 * 外放模式（没有 Shizuku 时的回退）下的语音包播放：素材直接从扬声器放出来，由目标 App 的麦克风收回去。
 * 变声在外放模式下走录播（见 MainViewModel.clipStop），不在这里。
 */
object SpeakerSfx {
    private const val SR = 48000
    private const val HOP = SR / 100

    // 语音包
    @Volatile private var sfx: ShortArray? = null
    @Volatile private var sfxPos = 0
    @Volatile private var sfxGain = 1f
    @Volatile private var sfxLoop = false
    @Volatile private var sfxTrack: Thread? = null

    /** 外放播放语音包素材（48k mono s16le） */
    fun playSfx(f: File, gain: Float, loop: Boolean): Int {
        val b = runCatching { f.readBytes() }.getOrNull() ?: return -1
        val a = ShortArray(b.size / 2) { i -> ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort() }
        sfxPos = 0; sfxGain = gain; sfxLoop = loop; sfx = a
        if (sfxTrack == null) sfxTrack = Thread({ sfxOnly() }, "speaker-sfx").also { it.start() }
        return a.size
    }

    fun stopSfx() { sfx = null }

    private fun newTrack(bufMs: Int): AudioTrack {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val fmt = AudioFormat.Builder().setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val b = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, SR * bufMs / 1000 * 2))
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return b.build()
    }

    private fun mixSfx(buf: FloatArray, n: Int) {
        val a = sfx ?: return
        var p = sfxPos; val g = sfxGain / 32768f
        for (i in 0 until n) {
            if (p >= a.size) { if (sfxLoop) p = 0 else { sfx = null; break } }
            val y = buf[i] + a[p++] * g
            buf[i] = if (y > 0.9f || y < -0.9f) kotlin.math.tanh(y) else y
        }
        sfxPos = p
    }

    private fun toPcm(buf: FloatArray, out: ShortArray, n: Int) {
        for (i in 0 until n) out[i] = (buf[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }

    private fun sfxOnly() {
        val t = runCatching { newTrack(120) }.getOrNull()
        try {
            t ?: return
            t.play()
            val buf = FloatArray(HOP); val pcm = ShortArray(HOP)
            while (sfx != null) {
                java.util.Arrays.fill(buf, 0f); mixSfx(buf, HOP); toPcm(buf, pcm, HOP)
                if (t.write(pcm, 0, HOP) < 0) break
            }
        } catch (e: Throwable) { L.e("外放语音包失败", e) }
        finally { runCatching { t?.stop() }; runCatching { t?.release() }; sfxTrack = null }
    }

}
