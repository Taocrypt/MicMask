package com.taocrypt.micmask

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taocrypt.beacon.Beacon
import com.taocrypt.beacon.BeaconConfig
import com.taocrypt.beacon.Notice
import com.taocrypt.beacon.Slot

/**
 * Beacon 后台（自建：公告 / 自营推荐位 / 匿名统计 / 反馈）在 App 里的薄封装。
 *
 * - 只在 App 主进程初始化（[MicMaskApp]）；Shizuku shell 进程（InjectorService 等）不走 Application，也不会调用这里。
 * - BuildConfig 里的 BEACON_* 为空（源码、本地构建、fork）时 SDK 关闭：[enabled]=false，所有入口隐藏、调用空操作。
 * - 统计默认关闭，只有首启同意弹窗里勾选「同意匿名统计」或设置里打开才上报。
 */
object BeaconHub {
    /** 同意弹窗（免责声明 + 信息收集说明）版本；声明有实质改动时 +1，所有人重新确认 */
    const val CONSENT_VERSION = 1
    private const val K_CONSENT = "consent_v"
    private const val K_SLOTS = "show_slots"

    @Volatile private var inited = false
    val enabled: Boolean get() = inited && Beacon.isEnabled

    /** Compose 可观察：统计开关、推荐位开关、配置 */
    @set:JvmName("setAnalyticsState") var analytics by mutableStateOf(false); private set
    var showSlots by mutableStateOf(true); private set
    var config by mutableStateOf<BeaconConfig?>(null); private set

    /** 本次启动待弹的 dialog 公告（首次拉到配置时快照一次） */
    val dialogQueue = mutableStateListOf<Notice>()
    /** 本次启动的横幅公告（关闭即移除） */
    val banners = mutableStateListOf<Notice>()
    private var queued = false
    /** 推荐位：每个位置每次启动挑一次，避免重组时随机换来换去 */
    private val picked = HashMap<String, Slot?>()

    fun init(app: Application) {
        if (inited) return
        val o = Beacon.Options(
            baseUrl = BuildConfig.BEACON_URL.trimEnd('/'),
            appId = BuildConfig.BEACON_APP_ID,
            clientKey = BuildConfig.BEACON_KEY,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            channel = "github",
            track = if (Regex("alpha|beta", RegexOption.IGNORE_CASE).containsMatchIn(BuildConfig.VERSION_NAME)) "beta" else "stable",
            debug = BuildConfig.DEBUG,
        )
        Beacon.init(app, o)
        inited = true
        analytics = Beacon.isAnalyticsEnabled()
        showSlots = Prefs.main(app).getBoolean(K_SLOTS, true)
        if (!Beacon.isEnabled) return
        config = Beacon.config
        installCrashHook()
        fetch()
    }

    fun fetch() {
        if (!enabled) return
        Beacon.fetchConfig { c ->
            config = c
            synchronized(picked) { picked.clear() }
            if (!queued && c != null) {
                queued = true
                val p = Beacon.pendingNotices()
                dialogQueue.addAll(p.filter { it.style == "dialog" }.sortedByDescending { it.priority })
                banners.addAll(p.filter { it.style == "banner" }.sortedByDescending { it.priority })
            }
        }
    }

    // ------------------------------------------------ 同意

    fun consentNeeded(ctx: Context) = Prefs.main(ctx).getInt(K_CONSENT, 0) < CONSENT_VERSION

    /** 同意弹窗「同意并继续」：记录版本；统计开关按勾选框 */
    fun acceptConsent(ctx: Context, stats: Boolean) {
        Prefs.main(ctx).edit().putInt(K_CONSENT, CONSENT_VERSION).apply()
        setAnalytics(stats)
        L.i("同意弹窗 v$CONSENT_VERSION：已同意，匿名统计=${stats && enabled}")
    }

    fun setAnalytics(on: Boolean) {
        val v = on && enabled
        Beacon.setAnalyticsEnabled(v)
        analytics = v
    }

    fun forgetMe(cb: (Boolean) -> Unit) = Beacon.forgetMe(cb)

    fun setShowSlots(ctx: Context, on: Boolean) {
        Prefs.main(ctx).edit().putBoolean(K_SLOTS, on).apply()
        showSlots = on
    }

    // ------------------------------------------------ 统计

    /** 自定义事件（未同意 / SDK 关闭时空操作）。只传类别与数值，不传用户内容 */
    fun event(name: String, props: Map<String, Any?> = emptyMap()) {
        if (!enabled || !analytics) return
        runCatching { Beacon.track(name, props) }
    }

    /** $crash：异常类名 + 前 3 个本包栈帧；同步写进本地队列后再交回原 handler（链式） */
    private fun installCrashHook() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            runCatching {
                if (Beacon.isAnalyticsEnabled()) {
                    val frames = ArrayList<String>()
                    var t: Throwable? = ex
                    var depth = 0
                    while (t != null && frames.size < 3 && depth < 5) {
                        for (f in t.stackTrace) {
                            if (f.className.startsWith("com.taocrypt.micmask")) {
                                frames += "${f.className.removePrefix("com.taocrypt.micmask.")}.${f.methodName}:${f.lineNumber}".take(100)
                                if (frames.size >= 3) break
                            }
                        }
                        t = t.cause; depth++
                    }
                    val p = linkedMapOf<String, Any?>("cls" to ex.javaClass.name.take(100), "thread" to (if (th.name == "main") "main" else "bg"))
                    frames.forEachIndexed { i, s -> p["f${i + 1}"] = s }
                    Beacon.trackSync("\$crash", p)
                }
            }
            prev?.uncaughtException(th, ex)
        }
    }

    // ------------------------------------------------ 推荐位

    fun slot(position: String): Slot? {
        if (!enabled || !showSlots) return null
        config ?: return null
        return synchronized(picked) { picked.getOrPut(position) { Beacon.pickSlot(position) } }
    }

    fun slotViewed(s: Slot) = Beacon.reportSlotView(s)
    fun openSlot(ctx: Context, s: Slot) = Beacon.openSlot(ctx, s)

    // ------------------------------------------------ 公告

    fun noticeShown(n: Notice) = Beacon.markNoticeShown(n)
    fun noticeClick(n: Notice, button: String) = Beacon.reportNoticeClick(n, button)

    // ------------------------------------------------ 反馈

    /** 反馈（2–2000 字，meta ≤8 项，附件 File/Uri）；回调在主线程，err 形如 "file_too_large (413)"、"429"、"network" */
    fun sendFeedback(content: String, contact: String?, meta: Map<String, Any?>, files: List<Any>, cb: (Boolean, String?) -> Unit) {
        if (!enabled) { cb(false, "disabled"); return }
        Beacon.sendFeedback(content, contact, meta.entries.take(8).associate { it.key to it.value }, files, cb)
    }

    // ------------------------------------------------ 杂项

    /** 主进程判断：Shizuku shell / 其他进程里不初始化 */
    fun isMainProcess(app: Application): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrNull()
        return name == null || name == app.packageName
    }
}

/** 应用入口：只在主进程初始化 Beacon（Shizuku shell 进程用 app_process 启动，不会创建 Application） */
class MicMaskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!BeaconHub.isMainProcess(this)) return
        runCatching { BeaconHub.init(this) }.onFailure { android.util.Log.w("MicMask", "Beacon init", it) }
    }
}
