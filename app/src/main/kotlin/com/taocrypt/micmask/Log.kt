package com.taocrypt.micmask

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 日志：logcat（TAG = MicMask）+ 文件（每次启动一个会话文件）+ 内存环形缓冲（App 内查看）。
 *
 * - 文件：files/logs/micmask-yyyyMMdd-HHmmss.log，单文件上限 8 MB，最多保留 12 个
 * - 写文件在独立线程，不阻塞实时链路
 * - [exportToDownloads] 把全部日志打成 zip 放到「下载/MicMask」，方便发给开发者
 */
object L {
    const val TAG = "MicMask"
    private const val MAX_FILE = 8L shl 20
    private const val KEEP_FILES = 12
    private const val RING = 4000

    var enabled: Boolean = true

    /** shell 进程里没有文件目录：日志交给注入服务的环形缓冲 */
    @Volatile var sink: ((Char, String) -> Unit)? = null

    @Volatile private var dir: File? = null
    @Volatile private var file: File? = null
    private val q = LinkedBlockingQueue<String>(20000)
    private val ring = ArrayDeque<Entry>(RING)
    @Volatile private var seq = 0L
    /** 每写一行 +1，查看器据此判断有无新日志 */
    @Volatile var version = 0L; private set

    /** 一条日志。cat = 分类（变声/注入/TTS/音频/模型/界面/服务/系统/其他） */
    class Entry(val seq: Long, val time: String, val level: Char, val cat: String, val thread: String, val msg: String) {
        fun line() = "$time $level [$thread] {$cat} $msg"
    }

    val CATS = listOf("变声", "注入", "TTS", "音频", "模型", "界面", "服务", "系统", "其他")
    private val catCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun catOf(cls: String): String = catCache.getOrPut(cls) {
        val n = cls.substringAfterLast('.').substringBefore('$')
        when {
            n in setOf("LiveRvc", "VcEngine", "Recorder", "RvcEngine") || n.startsWith("Rvc") -> "变声"
            n in setOf("MicInjector", "InjectorService", "ShellMain") -> "注入"
            n.startsWith("Tts") || n == "RefFeatureStore" -> "TTS"
            n in setOf("SoundBoard", "SpeakerSfx", "HeadsetLoopTest", "VoiceSender", "FloatingPlayService") || n.contains("Audio") -> "音频"
            n.startsWith("Model") -> "模型"
            n == "MainViewModel" || n == "MainActivity" || cls.contains(".ui") -> "界面"
            n.endsWith("Service") -> "服务"
            n == "L" -> "系统"
            else -> "其他"
        }
    }
    private fun callerCat(): String {
        for (f in Throwable().stackTrace) { val c = f.className; if (c != "com.taocrypt.micmask.L" && !c.startsWith("com.taocrypt.micmask.L$") && !c.startsWith("java.") && !c.startsWith("dalvik.")) return catOf(c) }
        return "其他"
    }
    @Volatile private var dropped = 0L
    private val tsFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Synchronized
    fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.applicationContext.filesDir, "logs").apply { mkdirs() }
        dir = d
        d.listFiles()?.filter { it.name.endsWith(".log") }?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_FILES - 1)?.forEach { it.delete() }
        newFile()
        Thread({ writerLoop() }, "micmask-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        i(header(ctx))
        // 崩溃：同步写入文件后交回系统处理
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            runCatching {
                e("崩溃 [${th.name}]", ex)
                file?.let { f -> FileWriter(f, true).use { w -> while (true) { val l = q.poll() ?: break; w.write(l); w.write("\n") } } }
            }
            prev?.uncaughtException(th, ex)
        }
        // 前后台切换、页面创建销毁
        (ctx.applicationContext as? android.app.Application)?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            var started = 0
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) { sys('I', "界面创建 ${a.javaClass.simpleName}") }
            override fun onActivityStarted(a: android.app.Activity) { if (started++ == 0) sys('I', "App 回到前台") }
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) { if (--started == 0) sys('I', "App 进入后台") }
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) { sys('I', "界面销毁 ${a.javaClass.simpleName}") }
        })
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 3).forEach { r ->
                if (r.timestamp > System.currentTimeMillis() - 86400_000L)
                    sys(if (r.reason == android.app.ApplicationExitInfo.REASON_CRASH || r.reason == android.app.ApplicationExitInfo.REASON_ANR ||
                            r.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) 'W' else 'I',
                        "上次退出：${exitName(r.reason)} ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(r.timestamp))} ${r.description ?: ""}")
            }
        }
    }

    private fun exitName(r: Int) = when (r) {
        android.app.ApplicationExitInfo.REASON_CRASH -> "崩溃"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
        android.app.ApplicationExitInfo.REASON_ANR -> "无响应(ANR)"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被杀"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "用户结束"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "自行退出"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "被系统信号结束"
        android.app.ApplicationExitInfo.REASON_OTHER -> "其他"
        else -> "原因$r"
    }

    private fun newFile() {
        val d = dir ?: return
        file = File(d, "micmask-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log")
    }

    private fun header(ctx: Context): String {
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val ort = runCatching { ai.onnxruntime.OrtEnvironment.getEnvironment().version }.getOrDefault("?")
        return "===== 会话开始 ===== app=${pi?.versionName}(${pi?.longVersionCode}) " +
            "device=${Build.MANUFACTURER} ${Build.MODEL} soc=$soc cores=${Runtime.getRuntime().availableProcessors()} " +
            "android=${Build.VERSION.RELEASE}(api ${Build.VERSION.SDK_INT}) abi=${Build.SUPPORTED_ABIS.joinToString(",")} " +
            "ram=${Runtime.getRuntime().maxMemory() shr 20}MB ort=$ort pid=${Process.myPid()}"
    }

    private fun writerLoop() {
        var w: FileWriter? = null
        var cur: File? = null
        while (true) {
            val first = try { q.take() } catch (_: InterruptedException) { return }
            try {
                val f = file ?: continue
                if (f.exists() && f.length() > MAX_FILE) { newFile() }
                if (cur != file) { runCatching { w?.close() }; cur = file; w = FileWriter(cur, true) }
                w!!.write(first); w!!.write("\n")
                var n = 0
                while (n < 500) { val s = q.poll() ?: break; w!!.write(s); w!!.write("\n"); n++ }
                if (dropped > 0) { w!!.write("[log] 队列满丢弃 $dropped 行\n"); dropped = 0 }
                w!!.flush()
            } catch (t: Throwable) {
                Log.e(TAG, "log write", t)
            }
        }
    }

    private fun put(level: Char, msg: String, t: Throwable? = null, cat: String = callerCat(),
                    time: String = tsFmt.get()!!.format(Date()), thread: String = Thread.currentThread().name) {
        val full = if (t != null) msg + "\n" + Log.getStackTraceString(t) else msg
        val en = synchronized(ring) {
            if (ring.size >= RING) ring.removeFirst()
            Entry(++seq, time, level, cat, thread, full).also { ring.addLast(it); version++ }
        }
        if (dir != null && !q.offer(en.line())) dropped++
    }

    private fun sys(level: Char, msg: String) { if (enabled) { Log.i(TAG, msg); put(level, msg, cat = "系统") } }

    /** 注入服务（shell 进程）搬过来的整行：「MM-dd HH:mm:ss.SSS X [shell/线程] 内容」 */
    fun shell(line: String) {
        if (!enabled) return
        val m = Regex("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3}) (\\w) \\[([^\\]]*)\\] ([\\s\\S]*)$").find(line)
        if (m == null) { put('M', line, cat = "注入"); return }
        val (tm, lv, th, msg) = m.destructured
        val cat = when {
            msg.contains("rvc", true) || msg.contains("推理") || msg.contains("engine", true) -> "变声"
            else -> "注入"
        }
        put(lv.firstOrNull() ?: 'I', msg, cat = cat, time = tm, thread = th)
    }

    private fun out(level: Char, msg: String, t: Throwable? = null): Boolean {
        val s = sink ?: return false
        s(level, if (t != null) msg + "\n" + Log.getStackTraceString(t) else msg); return true
    }
    fun d(msg: String) { if (enabled && !out('D', msg)) { Log.d(TAG, msg); put('D', msg) } }
    fun i(msg: String) { if (enabled && !out('I', msg)) { Log.i(TAG, msg); put('I', msg) } }
    fun w(msg: String) { if (enabled && !out('W', msg)) { Log.w(TAG, msg); put('W', msg) } }
    fun e(msg: String, t: Throwable? = null) { if (enabled && !out('E', msg, t)) { Log.e(TAG, msg, t); put('E', msg, t) } }

    /** 只进文件/环形缓冲，不进 logcat（高频指标用） */
    fun m(msg: String) { if (enabled && !out('M', msg)) put('M', msg) }

    fun recent(n: Int = 300): List<String> = synchronized(ring) { ring.toList().takeLast(n).map { it.line() } }
    fun entries(): List<Entry> = synchronized(ring) { ring.toList() }
    /** 只清空 App 内查看用的缓冲，文件不动 */
    fun clearView() = synchronized(ring) { ring.clear(); version++ }

    /** 打包全部日志到「下载/MicMask/micmask-logs-时间.zip」，返回给用户看的路径 */
    fun exportToDownloads(ctx: Context): String {
        val d = dir ?: throw IllegalStateException("日志未初始化")
        // 等写线程把队列写完
        val t0 = System.currentTimeMillis()
        while (q.isNotEmpty() && System.currentTimeMillis() - t0 < 2000) Thread.sleep(20)
        val name = "micmask-logs-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        val files = d.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: emptyList()
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MicMask")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("无法在下载目录创建文件")
        ctx.contentResolver.openOutputStream(uri)!!.use { os ->
            ZipOutputStream(os).use { z ->
                for (f in files) {
                    z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
                // 附带本进程的系统 logcat（含原生崩溃、系统音频报错）
                runCatching {
                    val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "20000", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
                    z.putNextEntry(ZipEntry("logcat.txt"))
                    p.inputStream.use { it.copyTo(z) }
                    z.closeEntry(); p.destroy()
                }
            }
        }
        i("日志已导出：$name（${files.size} 个文件）")
        return "下载/MicMask/$name"
    }
}
