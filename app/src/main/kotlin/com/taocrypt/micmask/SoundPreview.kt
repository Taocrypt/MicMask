package com.taocrypt.micmask

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File

/** 页面内试听：直接走扬声器/耳机（媒体通道），不进麦克风。 */
object SoundPreview {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var gen = 0

    fun stop() { gen++; track?.let { t -> runCatching { t.pause(); t.flush(); t.stop() }; t.release() }; track = null }

    /** 返回时长 ms；onEnd 在播完（未被打断）时回调 */
    fun play(f: File, gain: Float, onEnd: () -> Unit): Long {
        stop()
        val my = gen
        val bytes = f.readBytes()
        val n = bytes.size / 2
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SoundBoard.SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(SoundBoard.SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2, 19200))
            .build()
        track = t
        t.setVolume(gain.coerceIn(0f, 1f))
        t.play()
        Thread {
            var off = 0
            while (off < bytes.size && gen == my) {
                val w = t.write(bytes, off, minOf(9600, bytes.size - off))
                if (w <= 0) break
                off += w
            }
            if (gen == my) { Thread.sleep(150); if (gen == my) { stop(); onEnd() } }
        }.apply { isDaemon = true; name = "sfx-preview" }.start()
        return n * 1000L / SoundBoard.SR
    }
}
