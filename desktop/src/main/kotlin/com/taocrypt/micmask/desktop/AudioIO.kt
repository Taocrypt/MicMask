package com.taocrypt.micmask.desktop

import com.taocrypt.micmask.link.MmLink
import com.taocrypt.micmask.link.PlayoutBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/*
 * 电脑版音频 IO（第一版用 javax.sound：Windows 下走 DirectSound，CI 上能直接编译，不需要 native 库）。
 * 已知局限（推断）：DirectSound 比 WASAPI 多几十毫秒延迟；不能按进程抓声音（进程回环要 WASAPI，列为下一步）。
 * 统一格式：48 kHz、16 位、小端；设备只给双声道时自动上/下混。
 */
object AudioIO {
    const val SR = MmLink.SR
    private val MONO = AudioFormat(SR.toFloat(), 16, 1, true, false)
    private val STEREO = AudioFormat(SR.toFloat(), 16, 2, true, false)

    data class Dev(val name: String, val mixer: Mixer.Info?)

    /** VB-CABLE 的播放端（写进去 = 系统里多出麦克风「CABLE Output」） */
    fun isCableInput(name: String) = name.contains("CABLE Input", ignoreCase = true)
    fun isCableOutput(name: String) = name.contains("CABLE Output", ignoreCase = true)

    private fun mixers(line: Class<*>): List<Dev> = AudioSystem.getMixerInfo().filter { mi ->
        runCatching { val m = AudioSystem.getMixer(mi); m.isLineSupported(DataLine.Info(line, MONO)) || m.isLineSupported(DataLine.Info(line, STEREO)) }.getOrDefault(false)
    }.filterNot { it.name.startsWith("Port ") }.map { Dev(it.name, it) }

    fun outputs(): List<Dev> = listOf(Dev("系统默认播放设备", null)) + mixers(SourceDataLine::class.java)
    fun inputs(): List<Dev> = listOf(Dev("系统默认录音设备", null)) + mixers(TargetDataLine::class.java)

    fun findOutput(name: String) = outputs().firstOrNull { it.name == name }
    fun findInput(name: String) = inputs().firstOrNull { it.name == name }
    fun cableInput() = outputs().firstOrNull { isCableInput(it.name) }
    fun hasCable() = cableInput() != null

    private fun <T : DataLine> open(dev: Dev?, cls: Class<T>): Pair<T, Int> {
        for (f in listOf(MONO, STEREO)) {
            val info = DataLine.Info(cls, f)
            val line = runCatching {
                @Suppress("UNCHECKED_CAST")
                (if (dev?.mixer != null) AudioSystem.getMixer(dev.mixer).getLine(info) else AudioSystem.getLine(info)) as T
            }.getOrNull() ?: continue
            return line to f.channels
        }
        throw IllegalStateException("设备不支持 48 kHz 16 位：${dev?.name ?: "默认设备"}")
    }

    /** 播放线程：从抖动缓冲按 10 ms 取，写到选定设备。设备缓冲约 40 ms */
    class Player(private val dev: Dev?, private val buf: PlayoutBuffer) {
        @Volatile private var run = true
        @Volatile var error: String? = null; private set
        private val t = Thread({ loop() }, "mm-play").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
        fun start() = apply { t.start() }
        fun stop() { run = false }

        private fun loop() {
            val (line, ch) = try { open(dev, SourceDataLine::class.java) } catch (e: Throwable) { error = e.message; return }
            try {
                line.open(if (ch == 1) MONO else STEREO, SR / 25 * 2 * ch)
                line.start()
                val s = ShortArray(MmLink.FRAME)
                val b = ByteArray(MmLink.FRAME * 2 * ch)
                while (run) {
                    buf.read(s, s.size)
                    var o = 0
                    for (v in s) { repeat(ch) { b[o++] = v.toByte(); b[o++] = (v.toInt() shr 8).toByte() } }
                    line.write(b, 0, b.size)
                }
                line.drain(); line.stop()
            } catch (e: Throwable) { error = e.message } finally { runCatching { line.close() } }
        }
    }

    /** 采集线程：每 10 ms 回调一帧 480 样本（双声道设备取平均） */
    class Capturer(private val dev: Dev?, private val onFrame: (ShortArray) -> Unit) {
        @Volatile private var run = true
        @Volatile var error: String? = null; private set
        @Volatile var level = 0f; private set
        private val t = Thread({ loop() }, "mm-cap").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
        fun start() = apply { t.start() }
        fun stop() { run = false }

        private fun loop() {
            val (line, ch) = try { open(dev, TargetDataLine::class.java) } catch (e: Throwable) { error = e.message; return }
            try {
                line.open(if (ch == 1) MONO else STEREO, SR / 25 * 2 * ch)
                line.start()
                val b = ByteArray(MmLink.FRAME * 2 * ch)
                while (run) {
                    var got = 0
                    while (got < b.size && run) { val n = line.read(b, got, b.size - got); if (n <= 0) break; got += n }
                    if (got < b.size) continue
                    val s = ShortArray(MmLink.FRAME) { i ->
                        var acc = 0
                        for (c in 0 until ch) { val o = (i * ch + c) * 2; acc += ((b[o].toInt() and 0xff) or (b[o + 1].toInt() shl 8)).toShort().toInt() }
                        (acc / ch).toShort()
                    }
                    var pk = 0; for (v in s) { val a = kotlin.math.abs(v.toInt()); if (a > pk) pk = a }
                    level = pk / 32768f
                    onFrame(s)
                }
                line.stop()
            } catch (e: Throwable) { error = e.message } finally { runCatching { line.close() } }
        }
    }
}
