package com.taocrypt.micmask.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * native 音频库 mmaudio.dll（desktop/native/mmaudio.cpp，CI 在 windows-latest 用 MSVC 构建，打进安装目录的 app 资源目录）。
 * 提供 WASAPI 共享模式低延迟采集/播放 + 按进程抓声音（Windows 10 2004+）。JNA 调用。
 * 加载失败（非 Windows、文件缺失、JNA 不可用、被安全软件拦截）时 [available]=false，AudioIO 自动回退 javax.sound；
 * 单条流打开失败也回退（日志记原因）。设置里可以关掉 native（「音频引擎」）。
 */
object NativeAudio {
    @Suppress("FunctionName")
    interface Lib : Library {
        fun mm_version(): Int
        fun mm_win_build(): Int
        fun mm_open_device(capture: Int, nameUtf8: ByteArray, ringMs: Int, err: ByteArray, errLen: Int): Pointer?
        fun mm_open_process(pid: Int, exclude: Int, ringMs: Int, err: ByteArray, errLen: Int): Pointer?
        fun mm_read(h: Pointer, buf: ShortArray, n: Int, timeoutMs: Int): Int
        fun mm_write(h: Pointer, buf: ShortArray, n: Int, timeoutMs: Int): Int
        fun mm_available(h: Pointer): Int
        fun mm_skip(h: Pointer, n: Int): Int
        fun mm_alive(h: Pointer): Int
        fun mm_info(h: Pointer, buf: ByteArray, len: Int): Int
        fun mm_close(h: Pointer)
        fun mm_list_endpoints(capture: Int, buf: ByteArray, len: Int): Int
        fun mm_list_sessions(buf: ByteArray, len: Int): Int
    }

    /** 测试 / 自测用：覆盖 dll 路径（指向不存在的文件即可模拟加载失败） */
    @Volatile var overridePath: String? = null
    @Volatile private var lib: Lib? = null
    @Volatile private var tried = false
    @Volatile var loadError: String = ""; private set
    @Volatile var version = 0; private set
    @Volatile var winBuild = 0; private set

    /** 用户设置：false = 只用 javax.sound（排查兼容性问题时用） */
    var enabledSetting: Boolean
        get() = Settings.getBool("native_audio", true)
        set(v) { Settings.setBool("native_audio", v) }

    fun dllFile(): File? {
        overridePath?.let { return File(it) }
        System.getProperty("compose.application.resources.dir")?.let { File(it, "mmaudio.dll") }?.takeIf { it.isFile }?.let { return it }
        // 开发时：desktop/resources/windows/mmaudio.dll
        return File("resources/windows/mmaudio.dll").takeIf { it.isFile }
    }

    @Synchronized fun load(): Lib? {
        if (tried) return lib
        tried = true
        if (!System.getProperty("os.name").lowercase().contains("win")) { loadError = "not Windows"; return null }
        val f = dllFile() ?: run { loadError = "mmaudio.dll not found"; return null }
        if (!f.isFile) { loadError = "mmaudio.dll not found: ${f.path}"; return null }
        return runCatching {
            val l = Native.load(f.absolutePath, Lib::class.java)
            version = l.mm_version(); winBuild = l.mm_win_build()
            lib = l
            DeskLog.log('I', "音频", "native 音频库已加载：mmaudio v$version，Windows build $winBuild，${f.path}")
            l
        }.getOrElse { t ->
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            DeskLog.log('W', "音频", "native 音频库加载失败，回退 javax.sound：$loadError")
            null
        }
    }

    /** 自测：重新尝试加载（配合 overridePath） */
    @Synchronized fun resetForTest() { tried = false; lib = null; loadError = ""; version = 0 }

    val available: Boolean get() = enabledSetting && load() != null
    /** 按进程抓声音要 Windows 10 2004（build 19041）+ */
    val processLoopbackSupported: Boolean get() = available && (winBuild == 0 || winBuild >= 19041)

    private fun cstr(s: String) = (s + "\u0000").toByteArray(Charsets.UTF_8)
    private fun str(b: ByteArray): String { val n = b.indexOf(0).let { if (it < 0) b.size else it }; return String(b, 0, n, Charsets.UTF_8) }

    /** 一条 native 流。失败时构造函数抛异常（调用方回退 javax.sound） */
    class Stream private constructor(private val l: Lib, private val h: Pointer) {
        @Volatile private var closed = false
        fun read(buf: ShortArray, n: Int, timeoutMs: Int): Int = if (closed) -1 else l.mm_read(h, buf, n, timeoutMs)
        fun write(buf: ShortArray, n: Int, timeoutMs: Int): Int = if (closed) -1 else l.mm_write(h, buf, n, timeoutMs)
        fun available(): Int = if (closed) 0 else l.mm_available(h)
        fun skip(n: Int): Int = if (closed) 0 else l.mm_skip(h, n)
        val alive: Boolean get() = !closed && l.mm_alive(h) == 1
        fun info(): String { if (closed) return ""; val b = ByteArray(1024); l.mm_info(h, b, b.size); return str(b) }
        @Synchronized fun close() { if (closed) return; closed = true; l.mm_close(h) }

        companion object {
            /** 设备流：name 为 javax.sound/DirectSound 里看到的设备全名（端点友好名），空 = 系统默认 */
            fun device(capture: Boolean, name: String, ringMs: Int): Stream {
                val l = NativeAudio.load() ?: throw IllegalStateException(NativeAudio.loadError)
                val err = ByteArray(512)
                val h = l.mm_open_device(if (capture) 1 else 0, cstr(name), ringMs, err, err.size) ?: throw IllegalStateException(NativeAudio.str(err).ifEmpty { "open failed" })
                return Stream(l, h)
            }
            /** 进程回环：exclude=false 只抓该进程树；true 抓除它以外的系统声音 */
            fun process(pid: Int, exclude: Boolean, ringMs: Int): Stream {
                val l = NativeAudio.load() ?: throw IllegalStateException(NativeAudio.loadError)
                val err = ByteArray(512)
                val h = l.mm_open_process(pid, if (exclude) 1 else 0, ringMs, err, err.size) ?: throw IllegalStateException(NativeAudio.str(err).ifEmpty { "open failed" })
                return Stream(l, h)
            }
        }
    }

    data class AppSession(val pid: Int, val active: Boolean, val peak: Float, val path: String) {
        val exe: String get() = path.substringAfterLast('\\').substringAfterLast('/').ifEmpty { "pid $pid" }
        val title: String get() = friendly(exe)
    }

    /** 正在用声音的进程（同一进程在多个设备上合并；在出声的排前面）。native 不可用返回空表 */
    fun sessions(): List<AppSession> {
        val l = if (available) lib else null
        l ?: return emptyList()
        val b = ByteArray(64 * 1024)
        val n = runCatching { l.mm_list_sessions(b, b.size) }.getOrDefault(-1)
        if (n < 0) return emptyList()
        return parseSessions(String(b, 0, n.coerceAtMost(b.size), Charsets.UTF_8))
    }

    /** 解析 mm_list_sessions 的输出（纯函数，自测覆盖） */
    fun parseSessions(txt: String): List<AppSession> = txt.lineSequence().mapNotNull { line ->
        val p = line.split('\t'); if (p.size < 4) return@mapNotNull null
        val pid = p[0].toIntOrNull() ?: return@mapNotNull null
        AppSession(pid, p[1] == "1", p[2].toFloatOrNull() ?: 0f, p[3].trim())
    }.sortedWith(compareByDescending<AppSession> { it.active }.thenByDescending { it.peak }.thenBy { it.exe.lowercase() }).toList()

    /** 常见程序给个好认的名字（变声 / 直播软件） */
    fun friendly(exe: String): String {
        val e = exe.lowercase()
        return when {
            e.startsWith("mmvcserversio") || e.contains("w-okada") || e.contains("voice-changer") -> "w-okada Voice Changer ($exe)"
            e.startsWith("go-web") || e.startsWith("go-realtime-gui") || e.contains("rvc") -> "RVC ($exe)"
            e.startsWith("obs64") || e.startsWith("obs32") || e == "obs.exe" -> "OBS Studio ($exe)"
            e.startsWith("python") || e.startsWith("pythonw") -> "Python ($exe) · RVC / w-okada"
            else -> exe
        }
    }
}
