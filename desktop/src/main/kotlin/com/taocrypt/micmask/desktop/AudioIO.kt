package com.taocrypt.micmask.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taocrypt.micmask.link.MmLink
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.log10
import kotlin.math.sqrt

/*
 * 电脑版音频 IO：优先 native WASAPI 共享模式（mmaudio.dll，见 NativeAudio），打不开时自动回退 javax.sound（DirectSound）。
 * 按进程抓声音（某个应用的声音）只有 native 能做（Windows 10 2004+）。
 * 统一格式：48 kHz、16 位单声道；多声道设备下混时只平均「有信号」的声道（见 [downmix]），不会把单声道信号平均掉 6 dB。
 */
object AudioIO {
    const val SR = MmLink.SR
    const val FRAME = MmLink.FRAME
    private val MONO = AudioFormat(SR.toFloat(), 16, 1, true, false)
    private val STEREO = AudioFormat(SR.toFloat(), 16, 2, true, false)

    /** 可识别的设备类型（先看 Windows 注册表里的端点属性，没有再按名字猜） */
    enum class Kind { BUILTIN, HEADSET_JACK, USB, BLUETOOTH, VIRTUAL, LINE, OTHER }

    data class Dev(val name: String, val mixer: Mixer.Info?, val kind: Kind = Kind.OTHER, val isDefault: Boolean = false)

    fun kindLabel(k: Kind): String = when (k) {
        Kind.BUILTIN -> tr("电脑内置"); Kind.HEADSET_JACK -> tr("3.5mm 耳机"); Kind.USB -> tr("USB"); Kind.BLUETOOTH -> tr("蓝牙")
        Kind.VIRTUAL -> tr("虚拟声卡"); Kind.LINE -> tr("线路"); Kind.OTHER -> ""
    }

    /** VB-CABLE 的播放端（写进去 = 系统里多出麦克风「CABLE Output」） */
    fun isCableInput(name: String) = name.contains("CABLE Input", ignoreCase = true)
    fun isCableOutput(name: String) = name.contains("CABLE Output", ignoreCase = true)

    /** javax.sound 自带的「主声音驱动」条目 = 系统默认设备，我们已经用 mixer=null 的「系统默认」表示，列表里去掉 */
    private val PRIMARY = listOf("Primary Sound", "主声音", "主音效")

    private fun mixers(line: Class<*>): List<Mixer.Info> = AudioSystem.getMixerInfo().filter { mi ->
        !mi.name.startsWith("Port ") && PRIMARY.none { mi.name.startsWith(it) } &&
            runCatching { val m = AudioSystem.getMixer(mi); m.isLineSupported(DataLine.Info(line, MONO)) || m.isLineSupported(DataLine.Info(line, STEREO)) }.getOrDefault(false)
    }

    private fun build(infos: List<Mixer.Info>, ep: Map<String, WinEndpoints.Ep>): List<Dev> = infos.map { mi ->
        val e = ep[mi.name.lowercase()]
        Dev(mi.name, mi, e?.kind?.takeIf { it != Kind.OTHER } ?: guessKind(mi.name), e?.isDefault == true)
    }

    fun outputs(): List<Dev> = listOf(Dev("", null, isDefault = true)) + build(mixers(SourceDataLine::class.java), WinEndpoints.render())
    fun inputs(): List<Dev> = listOf(Dev("", null, isDefault = true)) + build(mixers(TargetDataLine::class.java), WinEndpoints.capture())

    /** 设备显示名：mixer=null 的是「系统默认」，后面带上当前默认设备的全名（能查到时） */
    fun label(d: Dev?, list: List<Dev>, input: Boolean): String {
        if (d == null || d.mixer == null) {
            val cur = list.firstOrNull { it.mixer != null && it.isDefault }?.name
            return if (input) (if (cur != null) tr("系统默认录音设备（当前：{0}）", cur) else tr("系统默认录音设备"))
            else (if (cur != null) tr("系统默认播放设备（当前：{0}）", cur) else tr("系统默认播放设备"))
        }
        val tags = listOfNotNull(kindLabel(d.kind).ifEmpty { null }, if (d.isDefault) tr("默认") else null)
        return if (tags.isEmpty()) d.name else d.name + "  [" + tags.joinToString(" · ") + "]"
    }

    fun guessKind(n0: String): Kind {
        val n = n0.lowercase()
        return when {
            listOf("cable", "voicemeeter", "vb-audio", "virtual", "虚拟", "steam streaming", "nvidia broadcast", "krisp", "obs").any { it in n } -> Kind.VIRTUAL
            listOf("bluetooth", "hands-free", "handsfree", "蓝牙", "免提", "airpods", "buds").any { it in n } -> Kind.BLUETOOTH
            "usb" in n -> Kind.USB
            listOf("headset", "headphone", "耳机", "耳麦").any { it in n } -> Kind.HEADSET_JACK
            listOf("microphone array", "麦克风阵列", "internal", "内置", "realtek", "intel").any { it in n } -> Kind.BUILTIN
            listOf("line in", "线路").any { it in n } -> Kind.LINE
            else -> Kind.OTHER
        }
    }

    fun cableInput() = outputs().firstOrNull { isCableInput(it.name) }
    fun hasCable() = cableInput() != null

    // ---------------------------------------------------------------- 热插拔：定时刷新设备列表（界面开着时 2 秒一次）

    var inList by mutableStateOf(listOf<Dev>()); private set
    var outList by mutableStateOf(listOf<Dev>()); private set
    @Volatile private var watcher: Thread? = null
    @Volatile var watchFast = true

    fun refresh() {
        val i = runCatching { inputs() }.getOrDefault(listOf(Dev("", null, isDefault = true)))
        val o = runCatching { outputs() }.getOrDefault(listOf(Dev("", null, isDefault = true)))
        if (i != inList) inList = i
        if (o != outList) outList = o
    }

    @Synchronized fun startWatch(onChange: (added: List<String>, removed: List<String>, input: Boolean) -> Unit) {
        if (watcher != null) return
        refresh()
        watcher = Thread({
            var n = 0
            while (true) {
                try { Thread.sleep(if (watchFast) 2000 else 10_000) } catch (_: InterruptedException) { break }
                val oldI = inList.map { it.name }.toSet(); val oldO = outList.map { it.name }.toSet()
                if (++n % 8 == 0) WinEndpoints.invalidate()   // 默认设备可能变了：隔一段时间重读注册表
                val namesI = runCatching { mixers(TargetDataLine::class.java).map { it.name }.toSet() + "" }.getOrDefault(oldI)
                val namesO = runCatching { mixers(SourceDataLine::class.java).map { it.name }.toSet() + "" }.getOrDefault(oldO)
                if (namesI != oldI || namesO != oldO) WinEndpoints.invalidate()
                refresh()
                if (namesI != oldI) onChange((namesI - oldI).toList(), (oldI - namesI).toList(), true)
                if (namesO != oldO) onChange((namesO - oldO).toList(), (oldO - namesO).toList(), false)
            }
        }, "mm-devwatch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun <T : DataLine> open(dev: Dev?, cls: Class<T>): Pair<T, Int> {
        for (f in listOf(MONO, STEREO)) {
            val info = DataLine.Info(cls, f)
            val line = runCatching {
                @Suppress("UNCHECKED_CAST")
                (if (dev?.mixer != null) AudioSystem.getMixer(dev.mixer).getLine(info) else AudioSystem.getLine(info)) as T
            }.getOrNull() ?: continue
            return line to f.channels
        }
        throw IllegalStateException(tr("设备不支持 48 kHz 16 位，或已被拔出：{0}", dev?.name?.ifEmpty { null } ?: tr("系统默认设备")))
    }

    // ---------------------------------------------------------------- 电平

    /** 一帧的峰值 / 均方根（dBFS）；累计 10 秒用于日志 */
    class Level {
        @Volatile var peak = 0f; private set          // 0..1，界面电平条
        @Volatile var peakDb = -120f; private set
        @Volatile var rmsDb = -120f; private set
        @Volatile var lastSignalMs = 0L; private set  // 最近一次超过 -60 dBFS 的时间
        private var accSq = 0.0; private var accN = 0L; private var accPk = 0
        fun observe(s: ShortArray, n: Int = s.size) {
            var pk = 0; var sq = 0.0
            for (i in 0 until n) { val v = s[i].toInt(); val a = if (v < 0) -v else v; if (a > pk) pk = a; sq += v.toDouble() * v }
            peak = pk / 32768f
            peakDb = db(pk / 32768.0); rmsDb = db(sqrt(sq / n.coerceAtLeast(1)) / 32768.0)
            if (peakDb > -60f) lastSignalMs = System.currentTimeMillis()
            synchronized(this) { accSq += sq; accN += n; if (pk > accPk) accPk = pk }
        }
        /** 取出并清零累计值：Pair(平均 RMS dBFS, 最大峰值 dBFS) */
        fun drain(): Pair<Float, Float> = synchronized(this) {
            val r = db(sqrt(accSq / accN.coerceAtLeast(1)) / 32768.0) to db(accPk / 32768.0)
            accSq = 0.0; accN = 0; accPk = 0; r
        }
        fun reset() { peak = 0f; peakDb = -120f; rmsDb = -120f }
        companion object { fun db(x: Double) = if (x <= 1e-6) -120f else (20 * log10(x)).toFloat().coerceAtLeast(-120f) }
    }

    // ---------------------------------------------------------------- 播放

    /** 播放线程：从自适应抖动缓冲按 10 ms 取，写到选定设备。设备缓冲约 50 ms */
    class Player(private val dev: Dev?, private val buf: DeskPlayout) {
        @Volatile private var run = true
        @Volatile var error: String? = null; private set
        @Volatile var lost = false; private set
        /** 实际用的音频引擎（界面/日志显示）：WASAPI（native）或 DirectSound（javax.sound） */
        @Volatile var backend = ""; private set
        private val t = Thread({ loop() }, "mm-play").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
        fun start() = apply { t.start() }
        /** 不 drain：停止要立刻返回（关闭窗口卡顿的原因之一是 drain/close 阻塞），关线在播放线程里做 */
        fun stop() { run = false }

        private fun loop() {
            if (NativeAudio.available) {
                val ns = runCatching { NativeAudio.Stream.device(false, dev?.name ?: "", 20) }
                    .onFailure { DeskLog.log('W', "音频", "WASAPI 播放打不开（${it.message}），回退 DirectSound：${dev?.name?.ifEmpty { null } ?: "系统默认"}") }.getOrNull()
                if (ns != null) { nativeLoop(ns); return }
            }
            javaLoop()
        }

        /** WASAPI：环形缓冲 20 ms，写满就等（音频引擎按设备时钟取，自然限速） */
        private fun nativeLoop(ns: NativeAudio.Stream) {
            backend = "WASAPI"
            DeskLog.log('I', "音频", "播放（WASAPI）：${dev?.name?.ifEmpty { null } ?: "系统默认"} · ${ns.info()}")
            try {
                val s = ShortArray(FRAME)
                while (run) {
                    buf.read(s, s.size)
                    var off = 0
                    while (off < s.size && run) {
                        val k = ns.write(if (off == 0) s else s.copyOfRange(off, s.size), s.size - off, 200)
                        if (k < 0 || !ns.alive) { error = ns.info().substringAfter("error: ", tr("播放设备已断开")); lost = true; return }
                        off += k
                    }
                }
            } catch (e: Throwable) { error = e.message; lost = true } finally { ns.close() }
        }

        private fun javaLoop() {
            backend = "DirectSound"
            val (line, ch) = try { open(dev, SourceDataLine::class.java) } catch (e: Throwable) { error = e.message; lost = true; return }
            try {
                line.open(if (ch == 1) MONO else STEREO, SR / 20 * 2 * ch)
                line.start()
                val s = ShortArray(FRAME)
                val b = ByteArray(FRAME * 2 * ch)
                while (run) {
                    buf.read(s, s.size)
                    var o = 0
                    for (v in s) { repeat(ch) { b[o++] = v.toByte(); b[o++] = (v.toInt() shr 8).toByte() } }
                    if (line.write(b, 0, b.size) <= 0 && !line.isOpen) { error = tr("播放设备已断开"); lost = true; break }
                }
                runCatching { line.stop(); line.flush() }
            } catch (e: Throwable) { error = e.message; lost = true } finally { runCatching { line.close() } }
        }
    }

    // ---------------------------------------------------------------- 采集

    /** 发送音源：所选录音设备、合成正弦（自测）、或转发收到的手机声音 */
    interface Source {
        /** 增益后（= 发出去）的电平，界面电平表用 */
        val level: Level
        /** 增益前（设备原始）电平，日志对比用 */
        val levelIn: Level
        val error: String?; val lost: Boolean; val dropped: Long
        val backend: String
        fun stop()
    }

    /**
     * 采集线程：每 10 ms 回调一帧 480 样本（双声道设备取平均），帧缓冲复用、不每帧分配。
     * 积压上限：设备缓冲里攒了超过 [capMs] 的数据（程序卡顿、设备时钟比网络快）就丢掉旧数据只留最近 [keepMs]，保证发出去的是「现在」的声音。
     */
    class Capturer(private val dev: Dev?, private val capMs: Int = 60, private val keepMs: Int = 20, private val gain: Gain? = null, private val onFrame: (ShortArray) -> Unit) : Source {
        @Volatile private var run = true
        @Volatile override var error: String? = null; private set
        @Volatile override var lost = false; private set
        @Volatile override var dropped = 0L; private set   // 因积压丢掉的样本数
        @Volatile override var backend = ""; private set
        override val level = Level()
        override val levelIn = Level()
        private val t = Thread({ loop() }, "mm-cap").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
        fun start() = apply { t.start() }
        override fun stop() { run = false }

        private fun emit(s: ShortArray) {
            levelIn.observe(s)
            gain?.process(s)
            level.observe(s)
            onFrame(s)
        }

        private fun loop() {
            if (NativeAudio.available) {
                val ns = runCatching { NativeAudio.Stream.device(true, dev?.name ?: "", 200) }
                    .onFailure { DeskLog.log('W', "音频", "WASAPI 录音打不开（${it.message}），回退 DirectSound：${dev?.name?.ifEmpty { null } ?: "系统默认"}") }.getOrNull()
                if (ns != null) { backend = "WASAPI"; DeskLog.log('I', "音频", "录音（WASAPI）：${dev?.name?.ifEmpty { null } ?: "系统默认"} · ${ns.info()}"); nativeLoop(ns, capMs, keepMs, true); return }
            }
            javaLoop()
        }

        /** native 流（设备或进程回环）通用读循环：积压控制 + 10 ms 一帧 */
        internal fun nativeLoop(ns: NativeAudio.Stream, capMs: Int, keepMs: Int, idleIsLost: Boolean) {
            try {
                val s = ShortArray(FRAME)
                var idle = 0
                while (run) {
                    val av = ns.available()
                    if (av > capMs * SR / 1000) { val k = ns.skip(av - keepMs * SR / 1000); dropped += k }
                    val n = ns.read(s, FRAME, 200)
                    if (n < 0 || !ns.alive) { error = ns.info().substringAfter("error: ", tr("录音设备已断开")); lost = true; break }
                    if (n < FRAME) {
                        if (idleIsLost && ++idle > 15) { error = tr("录音设备已断开"); lost = true; break }   // 约 3 秒读不到
                        continue
                    }
                    idle = 0
                    emit(s)
                }
            } catch (e: Throwable) { error = e.message; lost = true } finally { ns.close() }
        }

        private fun javaLoop() {
            backend = "DirectSound"
            val (line, ch) = try { open(dev, TargetDataLine::class.java) } catch (e: Throwable) { error = e.message; lost = true; return }
            try {
                val fb = FRAME * 2 * ch
                line.open(if (ch == 1) MONO else STEREO, SR / 10 * 2 * ch)   // 100 ms 设备缓冲，读的时候再限制积压
                line.start()
                val b = ByteArray(fb)
                val s = ShortArray(FRAME)
                var idle = 0
                while (run) {
                    val skip = backlogSkip(line.available(), fb, capMs * SR / 1000 * 2 * ch, keepMs * SR / 1000 * 2 * ch)
                    var k = skip
                    while (k > 0 && run) { val n = line.read(b, 0, minOf(fb, k)); if (n <= 0) break; k -= n; dropped += n / (2 * ch) }
                    var got = 0
                    while (got < fb && run) { val n = line.read(b, got, fb - got); if (n <= 0) break; got += n }
                    if (!run) break
                    if (got < fb) {
                        // 读不到数据：设备被拔出或被系统停用。连续 50 次（约 0.5 秒）就报告
                        if (!line.isOpen || ++idle > 50) { error = tr("录音设备已断开"); lost = true; break }
                        Thread.sleep(10); continue
                    }
                    idle = 0
                    downmix(b, ch, s, FRAME)
                    emit(s)
                }
                runCatching { line.stop(); line.flush() }
            } catch (e: Throwable) { error = e.message; lost = true } finally { runCatching { line.close() } }
        }
    }

    /**
     * 按进程抓声音（某个应用的声音，Windows 10 2004+，只走 native）。[exclude]=true 时抓「除这个程序以外」的所有声音。
     * 目标没出声时 native 会补静音，所以不会因为读不到数据判定断开；目标进程退出时流失效 → lost。
     */
    class AppCapturer(private val pid: Int, private val exclude: Boolean, private val label: String, private val gain: Gain? = null, private val onFrame: (ShortArray) -> Unit) : Source {
        private val inner = Capturer(null, 60, 20, gain, onFrame)
        override val level get() = inner.level
        override val levelIn get() = inner.levelIn
        @Volatile private var err: String? = null
        @Volatile private var lostF = false
        override val error get() = err ?: inner.error
        override val lost get() = lostF || inner.lost
        override val dropped get() = inner.dropped
        override val backend = "WASAPI process loopback"
        fun start() = apply {
            Thread({
                val ns = runCatching { NativeAudio.Stream.process(pid, exclude, 200) }.getOrElse {
                    err = tr("抓不到「{0}」的声音：{1}", label, it.message ?: ""); lostF = true
                    DeskLog.log('W', "音频", "按应用抓声音失败（pid $pid ${if (exclude) "排除" else "包含"}进程树）：${it.message}")
                    return@Thread
                }
                DeskLog.log('I', "音频", "按应用抓声音：$label（pid $pid，${if (exclude) "排除" else "只抓"}该进程树）· ${ns.info()}")
                inner.nativeLoop(ns, 60, 20, false)
            }, "mm-appcap").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
        }
        override fun stop() { inner.stop() }
    }

    /**
     * 16 位小端多声道 → 单声道：只平均「有信号」的声道（能量不低于最响声道 −20 dB）。
     * 旧做法是所有声道直接除以声道数：Voicemeeter（8 声道 VAIO）等虚拟线只有前两个声道有声时会被平均成 1/4（−12 dB），
     * 单边有声的立体声也会少 6 dB —— 这就是「莫名声音小」的原因之一（推断，看代码）。纯函数，自测覆盖。
     */
    fun downmix(b: ByteArray, ch: Int, out: ShortArray, frames: Int) {
        if (ch == 1) { for (i in 0 until frames) out[i] = ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort(); return }
        val e = DoubleArray(ch)
        for (i in 0 until frames) for (c in 0 until ch) { val o = (i * ch + c) * 2; val v = ((b[o].toInt() and 0xff) or (b[o + 1].toInt() shl 8)).toShort().toDouble(); e[c] += v * v }
        val mx = e.max()
        val use = (0 until ch).filter { mx <= 0 || e[it] >= mx * 0.01 }
        for (i in 0 until frames) {
            var acc = 0
            for (c in use) { val o = (i * ch + c) * 2; acc += ((b[o].toInt() and 0xff) or (b[o + 1].toInt() shl 8)).toShort().toInt() }
            out[i] = (acc / use.size).toShort()
        }
    }

    /** 积压时要丢掉的字节数（按帧对齐）：可读 > cap 时丢到只剩 keep。纯函数，自测覆盖 */
    fun backlogSkip(avail: Int, frameBytes: Int, capBytes: Int, keepBytes: Int): Int {
        if (avail <= capBytes) return 0
        val drop = avail - keepBytes
        return drop - drop % frameBytes
    }

    /** 合成音源（自测 / 内存测量用）：440 Hz 正弦，10 ms 节拍 */
    class SineSource(private val gain: Gain? = null, private val amp: Int = 6000, private val onFrame: (ShortArray) -> Unit) : Source {
        @Volatile private var run = true
        override val level = Level()
        override val levelIn = Level()
        override val backend = "sine"
        override val error: String? = null
        override val lost = false
        override val dropped = 0L
        fun start() = apply {
            Thread({
                val s = ShortArray(FRAME); var ph = 0.0; var next = System.nanoTime()
                while (run) {
                    for (i in s.indices) { s[i] = (amp * Math.sin(ph)).toInt().toShort(); ph += 2 * Math.PI * 440 / SR }
                    levelIn.observe(s); gain?.process(s); level.observe(s); onFrame(s)
                    next += 10_000_000L
                    val w = next - System.nanoTime(); if (w > 0) Thread.sleep(w / 1_000_000, (w % 1_000_000).toInt())
                }
            }, "mm-sine").apply { isDaemon = true }.start()
        }
        override fun stop() { run = false }
    }
}

/**
 * Windows 音频端点信息（注册表 HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\{Capture,Render}）：
 * 端点全名（与 DirectSound 设备名一致，推断）、是否默认设备（Role:0 时间戳最新的那个，推断）、形态（FormFactor）与总线（USB / 蓝牙 / HDAUDIO）。
 * 只读注册表（reg.exe query，不需要管理员）；读不到时返回空表，调用方按名字猜类型。结果缓存到 [invalidate]。
 */
object WinEndpoints {
    data class Ep(val name: String, val kind: AudioIO.Kind, val isDefault: Boolean)
    private const val ROOT = "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\"
    @Volatile private var cap: Map<String, Ep>? = null
    @Volatile private var ren: Map<String, Ep>? = null
    private val isWin = System.getProperty("os.name").lowercase().contains("win")

    fun invalidate() { cap = null; ren = null }
    fun capture(): Map<String, Ep> = cap ?: load("Capture").also { cap = it }
    fun render(): Map<String, Ep> = ren ?: load("Render").also { ren = it }

    private fun load(which: String): Map<String, Ep> {
        if (!isWin) return emptyMap()
        val txt = runCatching {
            val p = ProcessBuilder("reg", "query", ROOT + which, "/s").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader(java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding") ?: "UTF-8")).readText()
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            out
        }.getOrNull() ?: return emptyMap()
        return parse(txt)
    }

    /** 解析 reg query /s 的输出（纯函数，自测覆盖） */
    fun parse(txt: String): Map<String, Ep> {
        class D { var state = 0L; var role = ""; var desc = ""; var iface = ""; var ff = -1; val strs = StringBuilder() }
        val devs = LinkedHashMap<String, D>()
        var cur: D? = null
        for (raw in txt.lineSequence()) {
            val line = raw.trimEnd()
            if (line.startsWith("HKEY_")) {
                val seg = line.substringAfter("\\Audio\\").split('\\')
                cur = if (seg.size >= 2) devs.getOrPut(seg[1]) { D() } else null
                continue
            }
            val d = cur ?: continue
            val parts = line.trim().split(Regex("\\s{2,}|\\t"), 3)
            if (parts.size < 3) continue
            val (k, type, v) = parts
            when {
                k == "DeviceState" -> d.state = v.removePrefix("0x").toLongOrNull(16) ?: 0
                k == "Role:0" -> d.role = v
                k.equals("{a45c254e-df1c-4efd-8020-67d146a850e0},2", true) -> d.desc = v
                k.equals("{b3f8fa53-0004-438e-9003-51a46e139bfc},6", true) -> d.iface = v
                k.equals("{1da5d803-d492-4edd-8c23-e0c0ffee7f0e},0", true) -> d.ff = v.removePrefix("0x").toIntOrNull(16) ?: -1
                type == "REG_SZ" -> d.strs.append(v.uppercase()).append('|')
            }
        }
        val active = devs.values.filter { it.state == 1L && it.desc.isNotEmpty() }
        // Role:0 是 SYSTEMTIME 的 16 字节十六进制：年月(星期)日时分秒毫秒，各 2 字节小端 → 转成可比较的 key
        fun roleKey(h: String): String {
            if (h.length < 32) return ""
            val w = (0 until 8).map { i -> h.substring(i * 4 + 2, i * 4 + 4) + h.substring(i * 4, i * 4 + 2) }
            return listOf(w[0], w[1], w[3], w[4], w[5], w[6], w[7]).joinToString("")
        }
        val def = active.filter { it.role.isNotEmpty() }.maxByOrNull { roleKey(it.role) }
        val r = HashMap<String, Ep>()
        for (d in active) {
            val name = if (d.iface.isNotEmpty()) "${d.desc} (${d.iface})" else d.desc
            val s = d.strs.toString()
            val bus = when { "BTHENUM" in s || "BTHHFENUM" in s || "BTHLEDEVICE" in s || "BLUETOOTH" in s -> "bt"; "USB\\" in s || "USBAUDIO" in s -> "usb"; "HDAUDIO" in s || "INTELAUDIO" in s -> "hda"; else -> "" }
            val g = AudioIO.guessKind(name)
            val kind = when {
                g == AudioIO.Kind.VIRTUAL -> g
                bus == "bt" -> AudioIO.Kind.BLUETOOTH
                bus == "usb" -> AudioIO.Kind.USB
                d.ff == 3 || d.ff == 5 || d.ff == 6 -> AudioIO.Kind.HEADSET_JACK   // Headphones / Headset / Handset
                d.ff == 2 -> AudioIO.Kind.LINE
                bus == "hda" || d.ff == 4 || d.ff == 1 -> AudioIO.Kind.BUILTIN
                else -> g
            }
            r[name.lowercase()] = Ep(name, kind, d === def)
        }
        return r
    }
}
