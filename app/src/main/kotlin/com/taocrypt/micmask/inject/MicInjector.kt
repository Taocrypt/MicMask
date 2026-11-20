package com.taocrypt.micmask.inject

import com.taocrypt.micmask.tr

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import micmask.core.Resampler
import micmask.core.Wav
import com.taocrypt.micmask.BuildConfig
import com.taocrypt.micmask.L
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.math.roundToInt

/**
 * App 侧：通过 Shizuku 拉起 [InjectorService]（shell 身份），把待发送语音直接「注入」目标 App 的麦克风。
 * 不外放、不受蓝牙耳机影响、目标 App 不需要在前台小窗。
 */
object MicInjector {

    const val DEFAULT_TARGET = "com.tencent.mobileqq,com.tencent.mm"
    private const val REQ_CODE = 7301

    enum class Phase { IDLE, CONNECTING, WAITING, PLAYING, TAIL, LIVE, ERROR }

    @Volatile var binder: IBinder? = null
        private set
    @Volatile var phase: Phase = Phase.IDLE
        private set
    @Volatile var lastMessage: String = ""
        private set

    /** 状态变化回调（UI / 悬浮球） */
    @Volatile var onPhase: ((Phase, String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val pendingAfterBind = ArrayList<() -> Unit>()
    private var bindAttempt = 0

    // ------------------------------------------------------------ Shizuku

    /** 查哪些包的 PLAY_AUDIO 被设成 ignore/deny（会让该应用完全没声音）。MicMask 自身从不写 appops，这里只是自检 */
    fun mutedByAppOps(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (mode in listOf("ignore", "deny")) {
            val r = shell("cmd appops query-op PLAY_AUDIO $mode 2>&1 || appops query-op PLAY_AUDIO $mode 2>&1")
            if (r.startsWith("ERR")) throw IllegalStateException(r)
            r.lines().map { it.trim() }.filter { Regex("^[A-Za-z][\\w.]*\\.[\\w.]+$").matches(it) }.forEach { out += it to mode }
        }
        return out.distinctBy { it.first }
    }

    /** 开始变声时顺手自检一次，只写日志 */
    fun auditPlayAudio() {
        if (!shizukuRunning() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
        val m = mutedByAppOps()
        if (m.isNotEmpty()) L.w("音频自检：以下应用的 PLAY_AUDIO 被设为禁止，会完全没声音（不是 MicMask 写的）：${m.joinToString { "${it.first}=${it.second}" }}；可在 设置→音频还原 一键恢复")
        else L.i("音频自检：没有应用的 PLAY_AUDIO 被禁止")
    }

    /**
     * 一键还原：停掉注入（注销 AudioPolicy、还原真麦），把所有被设成 ignore/deny 的 PLAY_AUDIO 改回 allow。
     * 返回给用户看的结果。需要 Shizuku。
     */
    fun repairAudio(app: Context): String {
        if (!shizukuRunning() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return tr("需要 Shizuku 授权才能还原")
        runCatching { stopLive() }
        val m = mutedByAppOps()
        L.i("音频还原：PLAY_AUDIO 被禁止的应用 ${m.ifEmpty { null }?.joinToString { "${it.first}=${it.second}" } ?: "无"}")
        val fixed = ArrayList<String>(); val failed = ArrayList<String>()
        for ((pkg, _) in m) {
            val r = shell("cmd appops set $pkg PLAY_AUDIO allow 2>&1 || appops set $pkg PLAY_AUDIO allow 2>&1")
            val now = shell("cmd appops get $pkg PLAY_AUDIO 2>&1")
            L.i("音频还原：$pkg → ${r.ifEmpty { "ok" }} 现在=${now.replace('\n', ';')}")
            if (now.contains("ignore") || now.contains("deny")) failed += pkg else fixed += pkg
        }
        val names = { l: List<String> -> l.joinToString("、") { p -> runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) } }
        return when {
            m.isEmpty() -> tr("已停止变声并还原麦克风；没有应用被禁止播放声音")
            failed.isEmpty() -> tr("已恢复 {0} 的声音，请把它们彻底关掉再打开", names(fixed))
            else -> tr("已恢复 {0}；{1} 改不回来，请在系统设置里检查", names(fixed).ifEmpty { tr("无") }, names(failed))
        }
    }

    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 用户在设置里选了「普通模式」：即使 Shizuku 已授权，也按外放模式工作 */
    @Volatile var forceNormal = false

    /** 变声是否走 Shizuku 注入（已授权且用户没选普通模式） */
    fun shizukuGranted(): Boolean = !forceNormal && shizukuPermitted()

    /** Shizuku 本身是否已授权（不看用户选的模式；音频还原/自检/授权页用） */
    fun shizukuPermitted(): Boolean = runCatching {
        shizukuRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizuku(onResult: (Boolean) -> Unit) {
        if (!shizukuRunning()) { onResult(false); return }
        if (shizukuPermitted()) { onResult(true); return }
        val l = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQ_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(l)
        Shizuku.requestPermission(REQ_CODE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            L.i("MicInjector 注入服务已连接：${ping()}")
            startShellLogPump()
            bindAttempt = 0
            val todo = synchronized(pendingAfterBind) { pendingAfterBind.toList().also { pendingAfterBind.clear() } }
            todo.forEach { it() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            setPhase(Phase.IDLE, tr("注入服务断开"))
        }
    }

    private fun args(ctx: Context) = Shizuku.UserServiceArgs(
        ComponentName(ctx.packageName, InjectorService::class.java.name)
    ).daemon(false)  // 1.10 用 daemon 后升级会卡在连接，改回
        .processNameSuffix("injector")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private fun ensureBound(ctx: Context, then: () -> Unit) {
        if (binder?.pingBinder() == true) { then(); return }
        if (!shizukuPermitted()) {
            setPhase(Phase.ERROR, if (shizukuRunning()) tr("还没授权 Shizuku") else tr("Shizuku 没有运行"))
            return
        }
        val first = synchronized(pendingAfterBind) { pendingAfterBind.add(then); pendingAfterBind.size == 1 }
        if (!first) return   // 已在连接中，排队
        setPhase(Phase.CONNECTING, tr("正在启动注入服务…"))
        bindAttempt = 0; selfLaunched = false
        val app = ctx.applicationContext
        appCtx = app
        if (!staleCleared) {
            // App 重启后上一个进程留下的注入服务可能还没退干净，直接绑会卡住（1.13 日志：重启后卡加载，重新授权才好）
            staleCleared = true
            L.i("MicInjector 冷启动：先清理旧注入服务 peek=${runCatching { Shizuku.peekUserService(args(app), conn) }.getOrDefault(-99)}")
            Thread {
                runCatching { Shizuku.unbindUserService(args(app), conn, true) }.onFailure { L.w("清理旧服务：${it.message}") }
                killStaleInjector(app)
                main.post { startConnect(app) }
            }.start()
        } else startConnect(app)
    }
    @Volatile private var appCtx: Context? = null
    private fun selfPref(app: Context) = app.getSharedPreferences("live", 0)

    /** 上次自启成功过 → 直接自启（省掉等 Shizuku 的 5 s）；不行再回退 Shizuku */
    private fun startConnect(app: Context) {
        if (!selfPref(app).getBoolean("self_ok", false)) { doBind(app); return }
        val attempt = ++bindAttempt
        selfLaunched = true
        L.i("MicInjector 快速启动：直接自启注入进程（上次自启成功）")
        Thread { selfLaunch(app) }.start()
        val t0 = android.os.SystemClock.elapsedRealtime()
        fun check() {
            if (binder?.pingBinder() == true || bindAttempt != attempt) return
            val waited = (android.os.SystemClock.elapsedRealtime() - t0) / 1000
            if (waited >= 8) {
                L.w("MicInjector 快速自启 ${waited}s 没交回 binder，回退 Shizuku 用户服务")
                Thread { L.w("MicInjector 自启日志：" + shell("tail -n 20 /data/local/tmp/micmask-models/shellmain.log")) }.start()
                selfPref(app).edit().putBoolean("self_ok", false).apply()
                selfLaunched = false
                doBind(app)
            } else main.postDelayed({ check() }, 1000)
        }
        main.postDelayed({ check() }, 1000)
    }
    private var staleCleared = false

    /** 交给 ShellMain 做死亡监听：App 进程没了，shell 侧自己退出 */
    val appToken = android.os.Binder()
    @Volatile private var selfLaunched = false

    /** ShellMain 交回来的 binder，等同 onServiceConnected */
    fun onShellBinder(b: android.os.IBinder) {
        if (binder?.pingBinder() == true) { L.w("MicInjector 已有注入服务，忽略自启进程的 binder"); return }
        L.i("MicInjector 自启注入进程已交回 binder")
        appCtx?.let { selfPref(it).edit().putBoolean("self_ok", true).apply() }
        runCatching { b.linkToDeath({ main.post { if (binder === b) conn.onServiceDisconnected(null) } }, 0) }
        main.post { conn.onServiceConnected(null, b) }
    }

    /** 不走 Shizuku UserService，用 newProcess 起 app_process 跑 ShellMain */
    private fun selfLaunch(app: Context) {
        val apk = app.applicationInfo.sourceDir
        val pkg = app.packageName
        val logF = "/data/local/tmp/micmask-models/shellmain.log"
        val cmd = "rm -rf /data/local/tmp/andrvc-models; mkdir -p /data/local/tmp/micmask-models; " +
            "setsid nohup sh -c \"CLASSPATH='$apk' exec app_process -Djava.class.path='$apk' /system/bin --nice-name=$pkg:injector com.taocrypt.micmask.inject.ShellMain $pkg\" >$logF 2>&1 </dev/null &"
        L.i("MicInjector 改用自启注入进程（绕过 Shizuku 用户服务）")
        L.i("MicInjector 自启结果：${shell(cmd).ifEmpty { "已发出" }}")
    }

    /** 通过 Shizuku 以 shell 身份跑命令（newProcess 在 API 13 是私有的，反射调用） */
    private fun shell(cmd: String): String = runCatching {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor(); out.trim()
    }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }

    /**
     * App 重启后，上个进程留下的「:injector」shell 进程有时还活着，Shizuku 的 remove 清不掉它，
     * 新绑定就一直等不到回调（1.15 日志：3 次都连不上，重新授权才好）。直接按进程名杀掉。
     */
    private fun killStaleInjector(app: Context) {
        val name = app.packageName + ":injector"
        val ps = shell("ps -A -o PID,USER,NAME | grep -F '$name' | grep -v grep")
        L.i("MicInjector 残留注入进程：${ps.ifEmpty { "无" }.replace('\n', ';')}")
        val pids = ps.lines().mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() }
        if (pids.isNotEmpty()) {
            val r = shell("kill -9 ${pids.joinToString(" ")}")
            L.i("MicInjector 已结束残留进程 $pids ${r}")
            Thread.sleep(300)
        }
    }

    /** 绑定看门狗：8 s 没连上就强制移除旧服务进程（含旧版遗留的常驻进程）再绑一次 */
    private fun doBind(app: Context) {
        val attempt = ++bindAttempt
        L.i("MicInjector 绑定注入服务 第${attempt}次 version=${BuildConfig.VERSION_CODE}")
        runCatching { Shizuku.bindUserService(args(app), conn) }
            .onFailure { L.e("bindUserService", it); setPhase(Phase.ERROR, tr("启动注入服务失败：{0}", it.message)); return }
        // 新装/升级后第一次起 shell 进程要编译校验整个 APK 的 dex，可能远超 5 s。
        // 1.15~1.17 每 5 s 就 remove 重来，等于把正在启动的进程反复杀掉 → 永远连不上。
        // 现在：只要进程在就一直等（最多 40 s）；连进程都没有才重绑。
        val t0 = android.os.SystemClock.elapsedRealtime()
        fun check(n: Int) {
            if (binder?.pingBinder() == true || bindAttempt != attempt) return
            Thread {
                val ps = shell("ps -A -o PID,NAME,S | grep -F '${app.packageName}:injector' | grep -v grep").replace('\n', ';')
                val waited = (android.os.SystemClock.elapsedRealtime() - t0) / 1000
                main.post {
                    if (binder?.pingBinder() == true || bindAttempt != attempt) return@post
                    L.i("MicInjector 等待注入服务 ${waited}s 进程：${ps.ifEmpty { "无" }}")
                    when {
                        waited >= 30 || (ps.isEmpty() && waited >= 12 && selfLaunched) -> {
                            L.w("MicInjector 注入服务没连上（第${attempt}次，${waited}s）")
                            synchronized(pendingAfterBind) { pendingAfterBind.clear() }
                            setPhase(Phase.ERROR, tr("注入服务连不上：请在 Shizuku 里停止再启动服务后重试"))
                            Thread { val sl = shell("tail -n 40 /data/local/tmp/micmask-models/shellmain.log"); L.w("MicInjector 自启日志：$sl"); val spid = Regex("pid=(\\d+)").find(sl)?.groupValues?.get(1); if (spid != null) L.w("MicInjector 自启进程系统日志：\n" + shell("logcat -d -t 2000 | grep -E ' $spid |$spid\\]|pid=$spid|pid $spid|\\($spid\\)' | tail -n 60")); L.w("MicInjector 系统日志：\n" + shell("logcat -d -t 400 | grep -i -E 'shizuku|micmask|injector|app_process|UserService|AndroidRuntime' | tail -n 80")) }.start()
                        }
                        ps.isEmpty() && waited >= 4 && !selfLaunched -> {
                            L.w("MicInjector ${waited}s 内 Shizuku 没拉起注入进程，改为自启")
                            selfLaunched = true
                            runCatching { Shizuku.unbindUserService(args(app), conn, true) }
                            Thread { selfLaunch(app) }.start()
                            main.postDelayed({ check(n + 1) }, 2500)
                        }
                        else -> {
                            if (n == 1) setPhase(Phase.CONNECTING, tr("首次启动注入服务较慢，请稍等…"))
                            main.postDelayed({ check(n + 1) }, 2500)
                        }
                    }
                }
            }.start()
        }
        main.postDelayed({ check(0) }, 2500)
    }

    // ------------------------------------------------------------ 对外

    /** 目标 App 的 uid；没装返回 -1 */
    fun uidOf(ctx: Context, pkg: String): Int = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0).uid
    }.getOrDefault(-1)

    /**
     * 装载 [wav] 并接管目标 App 的麦克风。目标开始录音时自动放，录音结束自动还原真麦克风。
     * 立即返回；结果走 [onPhase]。
     */
    fun inject(ctx: Context, wav: File, targetPkg: String, timeoutMs: Long = 60_000L) {
        val app = ctx.applicationContext
        val pkgs = targetPkg.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val uids = pkgs.map { uidOf(app, it) }.filter { it >= 0 }.distinct().toIntArray()
        if (uids.isEmpty()) { setPhase(Phase.ERROR, tr("没找到目标 App：{0}", targetPkg)); return }
        ensureBound(app) {
            Thread {
                try {
                    val raw = toPcm48kStereo(app, wav)
                    val loaded = loadPcm(raw)
                    raw.delete()
                    if (loaded <= 0) { setPhase(Phase.ERROR, tr("语音装载失败")); return@Thread }
                    val err = arm(uids, timeoutMs, auto = true)
                    if (err != null) { setPhase(Phase.ERROR, err); return@Thread }
                    setPhase(Phase.WAITING, tr("已接管麦克风（{0} 个 App），现在去按住说话", uids.size))
                    pollUntilIdle()
                } catch (t: Throwable) {
                    L.e("MicInjector 注入失败", t)
                    setPhase(Phase.ERROR, tr("注入失败：{0}", t.message))
                }
            }.start()
        }
    }

    // ------------------------------------------------------------ 实时变声

    @Volatile var liveOn = false
        private set

    /** 开始实时变声：真麦 → 变调 → 持续注入 [targetPkg]（逗号分隔多个）。[status] 回调运行状态文字。 */
    fun startLive(ctx: Context, targetPkg: String, semis: Float, source: Int, external: Boolean = false, primeMs: Int = 100, rvcInShell: Boolean = false, status: (String) -> Unit) {
        val app = ctx.applicationContext
        val uids = targetPkg.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            .map { uidOf(app, it) }.filter { it >= 0 }.distinct().toIntArray()
        if (uids.isEmpty()) { status(tr("没找到目标 App：{0}", targetPkg)); return }
        if (!shizukuGranted()) { status(if (shizukuRunning()) tr("还没授权 Shizuku") else tr("Shizuku 没有运行")); return }
        ensureBound(app) {
            Thread {
                val err = runCatching {
                    call(InjectorService.TX_LIVE_START, {
                        it.writeInt(uids.size); uids.forEach { u -> it.writeInt(u) }
                        it.writeFloat(semis); it.writeInt(source); it.writeInt(if (external) 1 else 0); it.writeInt(primeMs); it.writeInt(if (rvcInShell) 1 else 0)
                        writeTune(it)
                    }) { r -> r.readString() }
                }.getOrElse { it.message ?: tr("调用失败") }
                if (err != null) { main.post { status(tr("启动失败：{0}", err)) }; setPhase(Phase.ERROR, tr("实时变声启动失败：{0}", err)); return@Thread }
                liveOn = true
                setPhase(Phase.LIVE, tr("实时变声中"))
                while (liveOn) {
                    val s = status()
                    val kv = s.split("|").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 } }
                        .associate { it[0] to it[1] }
                    if (kv["phase"] != "LIVE") {
                        liveOn = false
                        val e = kv["err"].orEmpty()
                        main.post { status(if (e.isNotEmpty()) tr("已停止：{0}", e) else tr("已停止")) }
                        setPhase(if (e.isNotEmpty()) Phase.ERROR else Phase.IDLE, if (e.isNotEmpty()) e else tr("实时变声已停止"))
                        break
                    }
                    main.post { status((modeLabel ?: if (external) tr("AI 变声") else tr("普通变声")) + tr(" · 麦电平 {0} · 目标在录={1} · 缓冲 {2} ms · 断流 {3}", kv["live"], kv["recs"], (kv["outBuf"]?.toIntOrNull() ?: 0) / 48, kv["under"]) + (kv["eng"]?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: statusExtra?.invoke()?.let { " · $it" } ?: "")) }
                    Thread.sleep(300)
                }
            }.start()
        }
    }

    /** 附加状态（实时 RVC 引擎的耗时等） */
    @Volatile var statusExtra: (() -> String)? = null
    /** 状态栏显示的模式名（AI 变声 / 普通变声 / 虚拟声卡），由界面在启动时设置 */
    @Volatile var modeLabel: String? = null

    /** 把模型文件描述符交给 shell 进程（它读不了 App 私有目录），shell 侧 mmap */
    fun sendLiveModels(ctx: Context, names: List<String>): String? {
        val files = names.mapNotNull { n -> com.taocrypt.micmask.ModelStore.locate(ctx, n)?.let { n to it } }
        if (files.isEmpty()) return tr("没有模型")
        var err: String? = tr("未连接")
        val done = java.util.concurrent.CountDownLatch(1)
        ensureBound(ctx.applicationContext) { Thread {
            err = runCatching {
                val pfds = files.map { (n, f) -> n to android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY) }
                try {
                    call(InjectorService.TX_LIVE_MODELS, { p ->
                        p.writeInt(pfds.size); pfds.forEach { (n, fd) -> p.writeString(com.taocrypt.micmask.ModelStore.canonicalName(n)); p.writeFileDescriptor(fd.fileDescriptor) }
                    }) { r -> r.readString() }
                } finally { pfds.forEach { runCatching { it.second.close() } } }
            }.getOrElse { it.message ?: tr("调用失败") }
            done.countDown()
        }.start() }
        // 首次要把约 360 MB 模型拷进 shell 目录
        if (!done.await(90, java.util.concurrent.TimeUnit.SECONDS)) return tr("传模型超时")
        return err
    }

    /** 性能测试：把模型交给 shell 后重新测速定档，返回摘要；阻塞调用，别在主线程 */
    fun liveBench(ctx: Context, names: List<String>): String {
        sendLiveModels(ctx, names)?.let { return "失败：$it" }
        return runCatching { call(InjectorService.TX_LIVE_BENCH, { d -> writeTune(d) }) { r -> r.readString() } ?: "" }.getOrElse { "失败：${it.message}" }
    }

    /** 外部模式：拉取真麦 PCM（48k mono），没有则返回空数组 */
    fun livePull(max: Int): ShortArray {
        val bytes = runCatching { call(InjectorService.TX_LIVE_PULL, { it.writeInt(max) }) { r -> r.createByteArray() } }
            .getOrNull() ?: return ShortArray(0)
        return ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
    }

    /** 外部模式：推送处理后的 PCM（48k mono） */
    fun livePush(pcm: ShortArray) {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { val v = pcm[i].toInt(); bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
        runCatching { call(InjectorService.TX_LIVE_PUSH, { it.writeByteArray(bytes) }) { } }
    }

    fun setLiveSemis(semis: Float) {
        if (!liveOn) return
        runCatching { call(InjectorService.TX_LIVE_SET, { it.writeFloat(semis) }) { } }
    }

    /** (距上次有声输出 ms, 输出缓冲 ms)；未连接返回 null */
    fun outState(): Pair<Int, Int>? = if (!liveOn) null else runCatching {
        call(InjectorService.TX_OUT_STATE) { r -> r.readInt() to r.readInt() }
    }.getOrNull()

    class SysStat(val cpu: Float, val temp: Float, val rssMb: Int, val underruns: Long)
    /** 需要注入服务已连接（实时变声开着或刚停）；失败返回 null */
    fun sysStat(): SysStat? = runCatching {
        call(InjectorService.TX_SYS_STAT) { r -> SysStat(r.readFloat(), r.readFloat(), r.readInt(), r.readLong()) }
    }.getOrNull()

    /** 实时 AI 高级参数（由 MainViewModel 从 prefs 设置） */
    @Volatile var tuneTier = 0
    @Volatile var tuneGate = -50f
    @Volatile var tuneFeat = 3
    @Volatile var tuneSyn = 4
    private fun writeTune(d: android.os.Parcel) { d.writeInt(tuneTier); d.writeFloat(tuneGate); d.writeInt(tuneFeat); d.writeInt(tuneSyn) }
    fun pushTune() {
        if (!liveOn) return
        runCatching { call(InjectorService.TX_LIVE_TUNE, { d -> writeTune(d) }) { } }
    }

    fun setFx(a: FloatArray) {
        if (!liveOn) return
        runCatching { call(InjectorService.TX_LIVE_FX, { d -> d.writeInt(a.size); a.forEach { d.writeFloat(it) } }) { } }
    }

    /** 语音包：把 48k mono s16le 文件交给注入进程混进麦克风通道。返回采样数，失败 -1 */
    fun playSfx(f: File, gain: Float, loop: Boolean): Int = runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            call(InjectorService.TX_SFX_PLAY, { d -> d.writeFileDescriptor(pfd.fileDescriptor); d.writeFloat(gain); d.writeInt(if (loop) 1 else 0) }) { it.readInt() }
        }
    }.getOrDefault(-1)

    fun stopSfx() { runCatching { call(InjectorService.TX_SFX_STOP) { } } }

    fun setMonitor(on: Boolean) {
        if (!liveOn) return
        runCatching { call(InjectorService.TX_LIVE_MONITOR, { it.writeInt(if (on) 1 else 0) }) { } }
    }

    fun capturePreview(sec: Int) {
        if (!liveOn) return
        runCatching { call(InjectorService.TX_LIVE_CAPTURE, { it.writeInt(sec) }) { } }
    }

    fun stopLive() {
        liveOn = false
        runCatching { call(InjectorService.TX_DISARM) { } }
        setPhase(Phase.IDLE, tr("实时变声已停止，真麦克风已还原"))
    }

    fun stop() {
        runCatching { call(InjectorService.TX_DISARM) { } }
        setPhase(Phase.IDLE, tr("已还原真麦克风"))
    }

    val active: Boolean get() = phase == Phase.WAITING || phase == Phase.PLAYING || phase == Phase.CONNECTING

    fun ping(): String = runCatching {
        call(InjectorService.TX_PING) { r -> tr("uid={0} 路由权限={1}", r.readInt(), if (r.readInt() == 1) tr("有") else tr("无")) }
    }.getOrElse { tr("未连接") }

    fun status(): String = runCatching { call(InjectorService.TX_STATUS) { it.readString() ?: "" } }
        .getOrElse { tr("未连接") }

    // ------------------------------------------------------------ 内部

    private fun pollUntilIdle() {
        while (true) {
            Thread.sleep(200)
            val s = status()
            val kv = s.split("|").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 } }
                .associate { it[0] to it[1] }
            when (kv["phase"]) {
                "PLAYING", "LEAD" -> if (phase != Phase.PLAYING) setPhase(Phase.PLAYING, tr("正在注入…"))
                // 语音已放完，目标还在录：继续垫底噪保持接管，等用户松手；UI 视为播完
                "TAIL" -> if (phase != Phase.TAIL) setPhase(Phase.TAIL, tr("播放完毕，可以松手发送了"))
                "WAITING" -> {}
                else -> {
                    val err = kv["err"].orEmpty()
                    if (err.isNotEmpty()) setPhase(Phase.ERROR, tr("注入中断：{0}", err))
                    else setPhase(Phase.IDLE, if (kv["seenRec"] == "1") tr("已注入（触发={0}），真麦克风已还原", kv["trig"])
                        else tr("等了 60 秒没看到目标录音，已还原（recs={0}）", kv["recs"]))
                    return
                }
            }
        }
    }

    private fun arm(uids: IntArray, timeoutMs: Long, auto: Boolean): String? = call(InjectorService.TX_ARM, {
        it.writeInt(uids.size); uids.forEach { u -> it.writeInt(u) }
        it.writeInt(if (auto) 1 else 0); it.writeLong(timeoutMs)
    }) { r -> r.readString() }

    // ------------------------------------------------------------ 自检

    /**
     * 注入自检：把本 App 自己当目标，注入 1 kHz 测试音，再用 AudioRecord 录 3 秒看录到了什么。
     * 分别测 VOICE_COMMUNICATION（QQ/微信语音常用）和 MIC 两种音源。
     * 结果文字回调到 [done]（主线程）。
     */
    fun selfTest(ctx: Context, done: (String) -> Unit) {
        val app = ctx.applicationContext
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            done(tr("自检需要录音权限，先在「变声」页录一次音授权")); return
        }
        if (!shizukuPermitted()) { done(if (shizukuRunning()) tr("还没授权 Shizuku") else tr("Shizuku 没有运行")); return }
        main.postDelayed({ if (binder == null) done(tr("注入服务 10 秒没连上（Shizuku 拉起失败）")) }, 10_000)
        ensureBound(app) {
            Thread {
                val sb = StringBuilder()
                sb.append(tr("服务：")).append(ping()).append('\n')
                for ((name, src) in listOf(
                    "VOICE_COMMUNICATION" to android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    "MIC" to android.media.MediaRecorder.AudioSource.MIC,
                )) {
                    sb.append(name).append("：").append(runCatching { testOnce(app, src) }
                        .getOrElse { tr("出错 {0}", it.message) }).append('\n')
                }
                runCatching { call(InjectorService.TX_DISARM) { } }
                phase = Phase.IDLE
                val r = sb.toString().trim()
                L.i("MicInjector 自检结果：\n$r")
                main.post { done(r) }
            }.start()
        }
    }

    private fun testOnce(app: Context, source: Int): String {
        val sr = 48000
        // 2.5 秒 1 kHz 测试音（-12 dBFS）
        val n = sr * 5 / 2
        val pcm = ByteArray(n * 4)
        for (i in 0 until n) {
            val v = (Math.sin(2 * Math.PI * 1000.0 * i / sr) * 8000).toInt()
            val lo = (v and 0xff).toByte(); val hi = ((v shr 8) and 0xff).toByte()
            pcm[i * 4] = lo; pcm[i * 4 + 1] = hi; pcm[i * 4 + 2] = lo; pcm[i * 4 + 3] = hi
        }
        val f = File(app.cacheDir, "selftest.pcm").apply { writeBytes(pcm) }
        loadPcm(f); f.delete()
        val err = arm(intArrayOf(android.os.Process.myUid()), 8000L, auto = true)
        if (err != null) return tr("ARM 失败：{0}", err)
        Thread.sleep(200)
        val minBuf = android.media.AudioRecord.getMinBufferSize(sr,
            android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val rec = android.media.AudioRecord(source, sr, android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr / 5 * 2))
        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
            call(InjectorService.TX_DISARM) { }; return tr("AudioRecord 初始化失败")
        }
        val buf = ShortArray(sr * 3)
        var got = 0
        rec.startRecording()
        val dev = runCatching { rec.routedDevice?.let { "${it.type}/${it.productName}" } }.getOrNull() ?: "?"
        while (got < buf.size) {
            val r = rec.read(buf, got, minOf(2048, buf.size - got))
            if (r <= 0) break
            got += r
        }
        rec.stop(); rec.release()
        val st = status()
        call(InjectorService.TX_DISARM) { }
        Thread.sleep(300)
        // 跳过前 0.4 秒，算整体能量和 1 kHz 能量占比（Goertzel）
        val from = minOf(got, sr * 2 / 5)
        var e = 0.0
        for (i in from until got) e += buf[i].toDouble() * buf[i]
        val cnt = (got - from).coerceAtLeast(1)
        val rms = Math.sqrt(e / cnt)
        val db = if (rms < 1e-9) -120.0 else 20 * Math.log10(rms / 32768.0)
        val k = 2 * Math.cos(2 * Math.PI * 1000.0 / sr)
        var s1 = 0.0; var s2 = 0.0
        for (i in from until got) { val s0 = buf[i] + k * s1 - s2; s2 = s1; s1 = s0 }
        val pTone = (s1 * s1 + s2 * s2 - k * s1 * s2) * 2 / cnt / cnt
        val ratio = if (e <= 0) 0.0 else (pTone / (e / cnt)).coerceIn(0.0, 1.0)
        val verdict = when {
            db < -70 -> tr("录到静音 → 麦克风被接管了，但注入的声音没进来")
            ratio > 0.4 -> tr("✅ 录到了测试音，注入可用")
            else -> tr("❌ 录到的是真实环境声 → 没被接管")
        }
        return tr("{0}（%.0f dBFS，测试音占比 %.0f%%，设备 {1}，{2}）", verdict, dev, st).format(db, ratio * 100)
    }

    private fun loadPcm(f: File): Int {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            return call(InjectorService.TX_LOAD, { it.writeFileDescriptor(pfd.fileDescriptor) }) { it.readInt() }
        }
    }

    private fun <T> call(code: Int, reader: (Parcel) -> T): T = call(code, {}, reader)

    private fun <T> call(code: Int, writer: (Parcel) -> Unit, reader: (Parcel) -> T): T {
        val b = binder ?: throw IllegalStateException(tr("注入服务未连接"))
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            writer(d)
            b.transact(code, d, r, 0)
            r.readException()
            return reader(r)
        } finally { d.recycle(); r.recycle() }
    }

    /** WAV → 48k 立体声 PCM16LE 裸数据（写到 cache） */
    private fun toPcm48kStereo(ctx: Context, wav: File): File {
        val d = Wav.read(wav)
        val mono = if (d.sampleRate == 48000) d.samples else Resampler.convert(d.samples, d.sampleRate, 48000)
        val out = ByteArray(mono.size * 4)
        var o = 0
        for (x in mono) {
            val s = (x.coerceIn(-1f, 1f) * 32767f).roundToInt()
            val lo = (s and 0xff).toByte(); val hi = ((s shr 8) and 0xff).toByte()
            out[o++] = lo; out[o++] = hi; out[o++] = lo; out[o++] = hi
        }
        val f = File(ctx.cacheDir, "inject_${System.currentTimeMillis()}.pcm")
        f.writeBytes(out)
        return f
    }

    // ------------------------------------------------------------ shell 日志搬运

    @Volatile private var shellSeq = 0L
    @Volatile private var logPump: Thread? = null

    /** 每秒把注入服务（shell 进程）里的日志搬进 App 日志文件 */
    private fun startShellLogPump() {
        if (logPump?.isAlive == true) return
        logPump = Thread({
            while (binder != null) {
                pullShellLogs()
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }, "shell-log").apply { isDaemon = true; start() }
    }

    fun pullShellLogs() {
        runCatching {
            call(InjectorService.TX_LOG_DUMP, { it.writeLong(shellSeq) }) { r ->
                val next = r.readLong(); val lines = r.createStringArray() ?: emptyArray()
                shellSeq = next
                for (l in lines) L.shell(l)
            }
        }
    }

    private fun setPhase(p: Phase, msg: String) {
        phase = p
        lastMessage = msg
        L.i("MicInjector [$p] $msg")
        main.post { onPhase?.invoke(p, msg) }
    }
}
