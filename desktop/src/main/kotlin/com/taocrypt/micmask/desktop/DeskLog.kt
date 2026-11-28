package com.taocrypt.micmask.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 电脑版日志：格式对齐手机端 L（`MM-dd HH:mm:ss.SSS 级别 [线程] {模块} 消息`）。
 *  - 文件：%LOCALAPPDATA%\MicMask\logs\micmask-desktop-yyyyMMdd[-n].log，按天切分，单文件超过 4 MB 换下一个；
 *    保留最近 7 个文件且总量 ≤ 20 MB（先到者为准）。写文件在独立线程，不阻塞音频。
 *  - 内存：最近 2000 条，界面「日志」页查看（按级别筛选、复制）。
 *  - 脱敏：写入前把当前配对码、已保存设备的配对码、Beacon 密钥替换成 ******（文件、界面、导出都不含）。
 *  - 未捕获异常（任何线程，含界面线程）记为 E 级并带堆栈。
 */
object DeskLog {
    class Entry(val time: String, val level: Char, val thread: String, val cat: String, val msg: String) {
        fun line() = "$time $level [$thread] {$cat} $msg"
    }

    private const val MAX_FILE = 4L shl 20
    private const val KEEP_FILES = 7
    private const val KEEP_BYTES = 20L shl 20
    private const val RING = 2000

    val dir: File = ((System.getenv("LOCALAPPDATA")?.let { File(it, "MicMask") } ?: File(System.getProperty("user.home"), ".micmask")).let { File(it, "logs") }).apply { mkdirs() }
    private val q = LinkedBlockingQueue<Entry>(20000)
    private val ring = ArrayDeque<Entry>(RING)
    var tail by mutableStateOf(listOf<Entry>()); private set
    @Volatile private var dirty = false
    private val ts = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }
    private val day = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat("yyyyMMdd", Locale.US) }
    @Volatile private var started = false

    @Synchronized fun start() {
        if (started) return
        started = true
        Thread({ writer() }, "mm-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log('E', "崩溃", "未捕获异常（线程 ${t.name}）：${e.javaClass.name}: ${e.message}\n" + e.stackTraceToString().lines().take(40).joinToString("\n"))
            runCatching { DeskBeacon.crash(t, e); DeskBeacon.flush() }
            flushNow()
            prev?.uncaughtException(t, e)
        }
    }

    fun i(cat: String, msg: String) = log('I', cat, msg)
    fun w(cat: String, msg: String) = log('W', cat, msg)
    fun e(cat: String, msg: String) = log('E', cat, msg)

    fun log(level: Char, cat: String, msg0: String) {
        val msg = redact(msg0)
        val e = Entry(ts.get().format(Date()), level, Thread.currentThread().name.take(20), cat, msg)
        println(e.line())
        synchronized(ring) { if (ring.size >= RING) ring.removeFirst(); ring.addLast(e) }
        dirty = true
        if (!started) return
        q.offer(e)
    }

    /** 脱敏：配对码（当前 + 已保存设备）、Beacon 密钥 */
    fun redact(s: String): String {
        var r = s
        val secrets = ArrayList<String>()
        runCatching { secrets += DeskLink.code }
        runCatching { SavedDevices.list.forEach { if (it.code.length == 6) secrets += it.code } }
        if (BuildInfo.BEACON_KEY.isNotEmpty()) secrets += BuildInfo.BEACON_KEY
        for (c in secrets) if (c.isNotEmpty()) {
            r = r.replace(c, "******")
            if (c.length == 6) r = r.replace(c.substring(0, 3) + " " + c.substring(3), "*** ***")
        }
        return r
    }

    private var cur: File? = null
    private var curDay = ""
    private var w: java.io.Writer? = null

    private fun writer() {
        var lastUi = 0L
        while (true) {
            val e = q.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (e != null) {
                runCatching { write(e) }
                var k = 0
                while (k++ < 500) { val n = q.poll() ?: break; runCatching { write(n) } }
                runCatching { w?.flush() }
            }
            val now = System.currentTimeMillis()
            if (dirty && now - lastUi >= 300) { dirty = false; lastUi = now; tail = synchronized(ring) { ring.toList() } }
        }
    }

    @Synchronized private fun write(e: Entry) {
        val d = day.get().format(Date())
        val f = cur
        if (f == null || d != curDay || f.length() > MAX_FILE) {
            runCatching { w?.close() }
            curDay = d
            var n = 0
            var nf: File
            do { nf = File(dir, "micmask-desktop-$d${if (n == 0) "" else "-$n"}.log"); n++ } while (nf.exists() && nf.length() > MAX_FILE)
            cur = nf
            w = java.io.OutputStreamWriter(java.io.FileOutputStream(nf, true), Charsets.UTF_8).buffered()
            prune()
        }
        w?.write(e.line()); w?.write("\n")
    }

    /** 保留最近 7 个文件且总量 ≤ 20 MB */
    private fun prune() {  // 在 write 的锁内调用
        val files = logFiles()
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if ((i >= KEEP_FILES || total > KEEP_BYTES) && f != cur) f.delete()
        }
    }

    fun logFiles(): List<File> = (dir.listFiles { f -> f.name.startsWith("micmask-desktop-") && f.name.endsWith(".log") } ?: emptyArray()).sortedByDescending { it.lastModified() }

    @Synchronized fun flushNow() {
        while (true) { val n = q.poll() ?: break; runCatching { write(n) } }
        runCatching { w?.flush() }
    }

    // ---------------------------------------------------------------- 导出

    /** 系统信息（导出用；设置项脱敏：配对码、密钥不写） */
    fun systemInfo(): String = buildString {
        val rt = Runtime.getRuntime()
        appendLine("MicMask 电脑版 ${BuildInfo.VERSION}")
        appendLine("导出时间 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("系统 ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}，语言 ${Locale.getDefault()}，编码 ${System.getProperty("native.encoding")}")
        appendLine("Java ${System.getProperty("java.version")}（${System.getProperty("java.vendor")}），JVM 参数 ${runCatching { java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" ") }.getOrDefault("?")}")
        appendLine("CPU 核数 ${rt.availableProcessors()}，PROCESSOR_IDENTIFIER=${System.getenv("PROCESSOR_IDENTIFIER") ?: "?"}")
        appendLine("JVM 堆 已用 ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB / 已提交 ${rt.totalMemory() shr 20} MB / 上限 ${rt.maxMemory() shr 20} MB；Skia 渲染 ${Ui.renderApi}")
        appendLine("本机设备名 ${DeskLink.deviceName}，设备 ID ${DeskLink.hex(DeskLink.deviceId).take(8)}…")
        appendLine()
        appendLine("== 录音设备")
        runCatching { val l = AudioIO.inputs(); l.forEach { appendLine("  " + (if (it.mixer == null) "[系统默认]" else "${it.name}  kind=${it.kind}${if (it.isDefault) " 默认" else ""}")) } }
        appendLine("== 播放设备")
        runCatching { val l = AudioIO.outputs(); l.forEach { appendLine("  " + (if (it.mixer == null) "[系统默认]" else "${it.name}  kind=${it.kind}${if (it.isDefault) " 默认" else ""}")) } }
        appendLine("== 网卡地址")
        Discovery.addrs().forEach { appendLine("  ${it.ip.hostAddress}  ${it.ifName}${if (it.vpn) "（虚拟网卡）" else ""}") }
        appendLine("== 局域网发现的设备")
        DeskLink.devices.forEach { appendLine("  ${it.name} ${it.host}:${it.port} ${it.platform} id=${it.idHex}") }
        appendLine("== 已保存设备（配对码不导出）")
        SavedDevices.list.forEach { appendLine("  ${it.title}（${it.name}）id=${it.id.take(8)}… ${it.host}:${it.port} auto=${it.auto} 有配对码=${it.code.length == 6}") }
        appendLine("== 状态")
        appendLine("  接收 ${DeskLink.rxOn} ${DeskLink.rxStatus} ${DeskLink.rxStats}")
        appendLine("  发送 ${DeskLink.txPhase} ${DeskLink.txStatus} ${DeskLink.txStats} ${DeskLink.txError}")
        appendLine("  输入通道 ${DeskLink.inputKey}，编码模式 ${DeskLink.codecMode}，自动连接 ${DeskLink.autoConnect}")
        appendLine("== 设置项（已脱敏）")
        Settings.snapshot().toSortedMap().forEach { (k, v) ->
            val vv = when {
                k == "link_code" || k.startsWith("peer_code_") -> "******"
                k == "saved_devs" -> "（见上）"
                k == "link_dev_id" || k == "beacon_iid" -> v.take(8) + "…"
                else -> v
            }
            appendLine("  $k=$vv")
        }
    }.let(::redact)

    /** 打包：最近的日志文件 + system-info.txt → [dst]（zip） */
    fun exportZip(dst: File): File {
        i("系统", "导出日志到 ${dst.path}")
        flushNow()
        ZipOutputStream(dst.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("system-info.txt")); z.write(systemInfo().toByteArray(Charsets.UTF_8)); z.closeEntry()
            for (f in logFiles()) {
                z.putNextEntry(ZipEntry("logs/" + f.name))
                // 文件里的内容写入时已脱敏；这里再过一遍，防止旧版本写入的配对码
                f.bufferedReader(Charsets.UTF_8).useLines { ls -> ls.forEach { z.write((redact(it) + "\n").toByteArray(Charsets.UTF_8)) } }
                z.closeEntry()
            }
        }
        return dst
    }

    fun defaultExportName() = "micmask-desktop-logs-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"

    fun desktopDir(): File = listOfNotNull(System.getenv("USERPROFILE")?.let { File(it, "Desktop") }, File(System.getProperty("user.home"), "Desktop"))
        .firstOrNull { it.isDirectory } ?: File(System.getProperty("user.home"))

    /**
     * 「导出日志」：弹系统保存对话框（默认放桌面、默认文件名）；取消则不导出。保存后在资源管理器里选中该文件。
     * 在后台线程调用（对话框是模态的）。返回结果说明。
     */
    fun exportInteractive(): String {
        val name = defaultExportName()
        var target: File? = null
        runCatching {
            javax.swing.SwingUtilities.invokeAndWait {
                val fd = java.awt.FileDialog(null as java.awt.Frame?, tr("导出日志"), java.awt.FileDialog.SAVE)
                fd.directory = desktopDir().path; fd.file = name
                fd.isVisible = true
                if (fd.file != null) target = File(fd.directory, fd.file.let { if (it.endsWith(".zip", true)) it else "$it.zip" })
            }
        }.onFailure { target = File(desktopDir(), name) }   // 无法弹框（如无界面环境）：直接放桌面
        val t = target ?: return tr("已取消导出")
        return runCatching {
            exportZip(t)
            if (System.getProperty("os.name").lowercase().contains("win")) runCatching { ProcessBuilder("explorer.exe", "/select,", t.absolutePath).start() }
            tr("已导出：{0}", t.absolutePath)
        }.getOrElse { tr("导出失败：{0}", it.message ?: "") }
    }
}
