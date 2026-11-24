package com.taocrypt.beacon

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Beacon 客户端 SDK。
 *
 * - baseUrl 或 clientKey 为空时整个 SDK 关闭（所有调用都是空操作），方便开源仓库里不放密钥。
 * - 统计默认关闭，必须在用户同意后调用 [setAnalyticsEnabled](true)。
 * - 只用匿名 install_id，不读取 IMEI / MAC / Android ID。
 */
object Beacon {
    class Options(
        /** 如 https://beacon-api.example.workers.dev，不带结尾斜杠 */
        val baseUrl: String,
        val appId: String,
        val clientKey: String,
        /** versionCode，用于公告/开关条件与更新检查 */
        val versionCode: Long,
        /** 分发渠道，如 github、play */
        val channel: String = "",
        /** 更新通道：stable / beta */
        val track: String = "stable",
        val debug: Boolean = false,
    )

    private const val TAG = "Beacon"
    private const val SESSION_TIMEOUT = 30 * 60_000L
    private const val MAX_QUEUE = 500
    private const val BATCH = 50

    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "beacon").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var opts: Options? = null
    private val queue = ArrayList<JSONObject>()
    private var sessionId = ""
    private var lastActive = 0L
    private var started = 0

    @Volatile var config: BeaconConfig? = null
        private set

    val isEnabled: Boolean get() = opts != null

    /** 在 Application.onCreate 里调用 */
    @JvmStatic
    fun init(context: Context, options: Options) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("beacon", Context.MODE_PRIVATE)
        if (options.baseUrl.isBlank() || options.clientKey.isBlank() || options.appId.isBlank()) {
            log("baseUrl / appId / clientKey 为空，SDK 已关闭")
            return
        }
        opts = options
        prefs.getString("config", null)?.let { runCatching { config = BeaconConfig(JSONObject(it)) } }
        io.execute { loadQueue() }
        (app as? Application)?.registerActivityLifecycleCallbacks(Lifecycle)
        io.scheduleWithFixedDelay({ flushNow() }, 30, 30, TimeUnit.SECONDS)
    }

    // ---------------- 统计 ----------------

    /** 用户是否同意统计（默认 false）。关闭时会清空本地队列 */
    @JvmStatic
    fun setAnalyticsEnabled(enabled: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean("analytics", enabled).apply()
        if (!enabled) io.execute { synchronized(queue) { queue.clear() }; saveQueue() }
    }

    @JvmStatic
    fun isAnalyticsEnabled(): Boolean = ::prefs.isInitialized && prefs.getBoolean("analytics", false)

    /** 匿名安装 ID（随机生成，卸载重装会变） */
    @JvmStatic
    fun installId(): String {
        prefs.getString("iid", null)?.let { return it }
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString("iid", id).apply()
        return id
    }

    /**
     * 记录事件。name：字母开头，最多 40 位；props 最多 10 个，值为字符串/数字/布尔。
     * 未同意统计时直接丢弃。
     */
    @JvmStatic
    @JvmOverloads
    fun track(name: String, props: Map<String, Any?> = emptyMap()) {
        if (opts == null || !isAnalyticsEnabled()) return
        val now = System.currentTimeMillis()
        touchSession(now)
        val p = JSONObject()
        for ((k, v) in props.entries.take(10)) {
            when (v) {
                is String -> p.put(k, v.take(100))
                is Number, is Boolean -> p.put(k, v)
                else -> {}
            }
        }
        val e = JSONObject().put("n", name).put("t", now).put("p", p)
        io.execute {
            synchronized(queue) { queue.add(e); while (queue.size > MAX_QUEUE) queue.removeAt(0) }
            saveQueue()
            if (queue.size >= BATCH) flushNow()
        }
    }

    /**
     * 同步版 [track]（MicMask 副本新增）：等事件写进本地队列文件再返回，最多等 [timeoutMs]。
     * 用于崩溃处理器——随后进程就会被杀，异步写入来不及。
     */
    @JvmStatic
    @JvmOverloads
    fun trackSync(name: String, props: Map<String, Any?> = emptyMap(), timeoutMs: Long = 1500) {
        if (opts == null || !isAnalyticsEnabled()) return
        val p = JSONObject()
        for ((k, v) in props.entries.take(10)) {
            when (v) {
                is String -> p.put(k, v.take(100))
                is Number, is Boolean -> p.put(k, v)
                else -> {}
            }
        }
        val e = JSONObject().put("n", name).put("t", System.currentTimeMillis()).put("p", p)
        val write = Runnable {
            synchronized(queue) { queue.add(e); while (queue.size > MAX_QUEUE) queue.removeAt(0) }
            saveQueue()
        }
        if (Thread.currentThread().name == "beacon") { write.run(); return }
        runCatching { io.submit(write).get(timeoutMs, TimeUnit.MILLISECONDS) }.onFailure {
            // 写线程忙/超时：直接在当前线程写（可能与队列线程竞争，但总比丢掉好）
            runCatching { write.run() }
        }
    }

    @JvmStatic
    fun screen(name: String) = track("\$screen", mapOf("name" to name))

    /** 立即上报（进入后台时会自动调用） */
    @JvmStatic
    fun flush() { if (opts != null) io.execute { flushNow() } }

    /** 删除服务器上这个安装的全部数据，并重置 install_id */
    @JvmStatic
    @JvmOverloads
    fun forgetMe(callback: ((Boolean) -> Unit)? = null) {
        val o = opts
        if (o == null) { callback?.let { main.post { it(false) } }; return }
        val iid = installId()
        io.execute {
            val ok = runCatching { Http.request("${o.baseUrl}/v1/${o.appId}/forget", "POST", o.clientKey, JSONObject().put("iid", iid).toString()).code == 200 }.getOrDefault(false)
            if (ok) {
                prefs.edit().remove("iid").apply()
                synchronized(queue) { queue.clear() }; saveQueue()
            }
            callback?.let { main.post { it(ok) } }
        }
    }

    // ---------------- 配置 ----------------

    /** 拉取配置（公告、推荐位、开关、更新），带 ETag 缓存；回调在主线程 */
    @JvmStatic
    @JvmOverloads
    fun fetchConfig(callback: ((BeaconConfig?) -> Unit)? = null) {
        val o = opts
        if (o == null) { callback?.let { main.post { it(null) } }; return }
        io.execute {
            val q = buildString {
                append("ver=").append(o.versionCode)
                append("&lang=").append(enc(Locale.getDefault().toLanguageTag()))
                if (o.channel.isNotEmpty()) append("&ch=").append(enc(o.channel))
                append("&track=").append(enc(o.track))
                append("&iid=").append(installId())
            }
            val r = runCatching { Http.request("${o.baseUrl}/v1/${o.appId}/config?$q", "GET", o.clientKey, etag = prefs.getString("etag", null)) }.getOrNull()
            when (r?.code) {
                200 -> runCatching {
                    config = BeaconConfig(JSONObject(r.body))
                    prefs.edit().putString("config", r.body).putString("etag", r.etag).apply()
                }
                304 -> {}
                else -> log("fetchConfig 失败：${r?.code} ${r?.body}")
            }
            callback?.let { main.post { it(config) } }
        }
    }

    @JvmStatic fun flag(key: String, default: Boolean) = config?.flag(key, default) ?: default
    @JvmStatic fun flag(key: String, default: Long) = config?.flag(key, default) ?: default
    @JvmStatic fun flag(key: String, default: String) = config?.flag(key, default) ?: default

    // ---------------- 公告 ----------------

    /** 按频率规则挑出现在应该展示的公告（不包含已展示过的 once / 今天已展示过的 daily） */
    @JvmStatic
    fun pendingNotices(): List<Notice> {
        val c = config ?: return emptyList()
        val today = System.currentTimeMillis() / 86_400_000L
        val list = c.notices.filter {
            when {
                it.force == "block" -> true
                it.force == "ack" -> prefs.getLong("na_${it.id}", -1) != it.rev && !shownThisLaunch.contains(it.id)
                else -> when (it.frequency) {
                "once" -> prefs.getLong("n_${it.id}", -1) != it.rev
                "daily" -> prefs.getLong("nd_${it.id}", -1) != today
                    else -> !shownThisLaunch.contains(it.id)
                }
            }
        }
        // 阻断 > 强制阅读 > 普通
        return list.sortedBy { when (it.force) { "block" -> 0; "ack" -> 1; else -> 2 } }
    }

    /** 当前生效的阻断公告（停服维护等）；不为 null 时应阻止用户继续使用。自带弹窗会自动处理 */
    @JvmStatic
    fun blockingNotice(): Notice? = config?.notices?.firstOrNull { it.force == "block" }

    /** 用户确认已阅读强制公告（自带弹窗会自动调用）；同一版本不再弹出，公告修改后会重新要求确认 */
    @JvmStatic
    fun markNoticeAcked(n: Notice) {
        prefs.edit().putLong("na_${n.id}", n.rev).apply()
        track("\$notice_ack", mapOf("notice" to n.id))
    }

    private val shownThisLaunch = HashSet<Long>()

    /** 标记公告已展示（自带弹窗会自动调用） */
    @JvmStatic
    fun markNoticeShown(n: Notice) {
        shownThisLaunch.add(n.id)
        prefs.edit().putLong("n_${n.id}", n.rev).putLong("nd_${n.id}", System.currentTimeMillis() / 86_400_000L).apply()
        track("\$notice_view", mapOf("notice" to n.id))
    }

    @JvmStatic
    fun reportNoticeClick(n: Notice, button: String) = track("\$notice_click", mapOf("notice" to n.id, "button" to button))

    /** 用系统 AlertDialog 依次展示待显示的 dialog 公告 */
    @JvmStatic
    fun showNotices(activity: Activity) = Ui.showNotices(activity, pendingNotices().filter { it.style == "dialog" || it.force != "none" })

    // ---------------- 轮播图 ----------------

    /** 当前应显示的轮播图（已按后台顺序排好，已过滤时间和条件） */
    @JvmStatic fun banners(): List<Banner> = config?.banners ?: emptyList()
    /** 某张轮播图第一次进入屏幕时调用（同一次启动内同一张只会上报一次） */
    @JvmStatic fun reportBannerView(b: Banner) { if (bannerSeen.add(b.id)) track("\$banner_view", mapOf("banner" to b.id)) }
    @JvmStatic fun reportBannerClick(b: Banner) = track("\$banner_click", mapOf("banner" to b.id))
    /** 上报点击；有弹窗内容且传入的是 Activity 时先弹窗（按钮再跳转），否则直接用浏览器打开链接 */
    @JvmStatic fun openBanner(context: Context, b: Banner) {
        reportBannerClick(b)
        if (b.popup.isNotBlank() && context is android.app.Activity) Ui.showBannerPopup(context, b)
        else b.url?.let { Ui.openUrl(context, it) }
    }

    /** 零依赖的小型图片加载（后台线程下载 + 内存缓存），回调在主线程；失败回调 null。已用 Glide/Coil 的项目直接用它们即可 */
    @JvmStatic fun loadImage(url: String, maxWidthPx: Int = 1080, callback: (android.graphics.Bitmap?) -> Unit) = Images.load(url, maxWidthPx, callback)
    private val bannerSeen = HashSet<Long>()

    // ---------------- 推荐位 ----------------

    @JvmStatic fun pickSlot(position: String): Slot? = config?.pickSlot(position)
    @JvmStatic fun reportSlotView(s: Slot) = track("\$slot_view", mapOf("slot" to s.id, "pos" to s.position))
    @JvmStatic fun reportSlotClick(s: Slot) = track("\$slot_click", mapOf("slot" to s.id, "pos" to s.position))

    /** 上报点击并用浏览器打开推荐位链接 */
    @JvmStatic
    fun openSlot(context: Context, s: Slot) { reportSlotClick(s); s.url?.let { Ui.openUrl(context, it) } }

    // ---------------- 更新 ----------------

    @JvmStatic fun update(): Update? = config?.update

    /** 有更新时弹窗；force=true 时不能取消。返回是否弹出 */
    @JvmStatic
    @JvmOverloads
    fun showUpdateDialog(activity: Activity, ignoreSkipped: Boolean = false): Boolean {
        val u = update() ?: return false
        if (!u.force && !ignoreSkipped && prefs.getLong("skip", -1) == u.versionCode) return false
        track("\$update_view", mapOf("to" to u.versionCode))
        Ui.showUpdate(activity, u) { prefs.edit().putLong("skip", u.versionCode).apply() }
        return true
    }

    // ---------------- 反馈 ----------------

    /**
     * 提交反馈（2-2000 字）。meta 可放机型等附加信息，最多 10 项；回调在主线程。
     * files：附件（最多 5 个，单个 ≤8MB、合计 ≤12MB），支持截图 png/jpg/webp/gif/heic、录屏 mp4、pdf、zip/gz、
     * 以及 txt/log/json/csv/xml/md 文本。可以是 content:// 或 file:// 的 Uri，也可以是 java.io.File。
     */
    @JvmStatic
    @JvmOverloads
    fun sendFeedback(content: String, contact: String? = null, meta: Map<String, Any?> = emptyMap(), files: List<Any> = emptyList(), callback: ((Boolean, String?) -> Unit)? = null) {
        val o = opts
        if (o == null) { callback?.let { main.post { it(false, "disabled") } }; return }
        io.execute {
            val m = JSONObject().put("model", Build.MODEL ?: "").put("sdk", Build.VERSION.SDK_INT)
            for ((k, v) in meta.entries.take(8)) {
                if (v is String || v is Number || v is Boolean) m.put(k, v)
            }
            val b = JSONObject().put("content", content).put("ver", o.versionCode).put("meta", m)
            contact?.takeIf { it.isNotBlank() }?.let { b.put("contact", it) }
            if (isAnalyticsEnabled()) b.put("iid", installId())
            val parts = files.take(5).mapNotNull { f -> attachment(f) }
            val r = runCatching {
                if (parts.isEmpty()) Http.request("${o.baseUrl}/v1/${o.appId}/feedback", "POST", o.clientKey, b.toString())
                else Http.multipart("${o.baseUrl}/v1/${o.appId}/feedback", o.clientKey,
                    buildMap {
                        put("content", content); put("ver", o.versionCode.toString()); put("meta", m.toString())
                        contact?.takeIf { it.isNotBlank() }?.let { put("contact", it) }
                        if (isAnalyticsEnabled()) put("iid", installId())
                    }, parts)
            }.getOrNull()
            val ok = r?.code == 201
            // MicMask 副本：错误带 HTTP 状态码，如 "file_too_large (413)"、"429"
            val err = if (ok) null else if (r == null) "network" else {
                val e = runCatching { JSONObject(r.body).optString("error") }.getOrNull().orEmpty()
                if (e.isEmpty()) "${r.code}" else "$e (${r.code})"
            }
            callback?.let { main.post { it(ok, err) } }
        }
    }

    // ---------------- 内部 ----------------

    private fun attachment(f: Any): Pair<String, () -> java.io.InputStream>? = when (f) {
        is File -> if (f.isFile) f.name to { f.inputStream() } else null
        is android.net.Uri -> {
            val name = runCatching {
                app.contentResolver.query(f, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: f.lastPathSegment ?: "file"
            name to { app.contentResolver.openInputStream(f) ?: throw java.io.FileNotFoundException(f.toString()) }
        }
        else -> null
    }

    private fun touchSession(now: Long) {
        if (sessionId.isEmpty() || now - lastActive > SESSION_TIMEOUT) {
            val b = ByteArray(12).also { SecureRandom().nextBytes(it) }
            sessionId = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
        lastActive = now
    }

    private fun flushNow() {
        val o = opts ?: return
        if (!isAnalyticsEnabled()) return
        while (true) {
            val batch = synchronized(queue) { queue.take(BATCH) }
            if (batch.isEmpty()) return
            val body = JSONObject()
                .put("iid", installId()).put("sid", sessionId.ifEmpty { "s" + installId() })
                .put("ver", o.versionCode).put("ch", o.channel)
                .put("os", Build.VERSION.RELEASE ?: "").put("dev", Build.MODEL ?: "")
                .put("lang", Locale.getDefault().toLanguageTag())
                .put("ev", JSONArray(batch))
            val code = runCatching { Http.request("${o.baseUrl}/v1/${o.appId}/events", "POST", o.clientKey, body.toString()).code }.getOrDefault(-1)
            // 2xx 或 4xx（数据本身有问题，重试也没用）都移出队列；429/5xx/网络错误留到下次
            if (code in 200..299 || (code in 400..499 && code != 429)) {
                synchronized(queue) { repeat(batch.size) { if (queue.isNotEmpty()) queue.removeAt(0) } }
                saveQueue()
                if (code !in 200..299) log("events 被拒绝：$code")
            } else {
                log("events 上报失败：$code，稍后重试"); return
            }
        }
    }

    private val queueFile get() = File(app.filesDir, "beacon_queue.json")
    private fun loadQueue() = runCatching {
        if (!queueFile.exists()) return@runCatching
        val a = JSONArray(queueFile.readText())
        synchronized(queue) { for (i in 0 until a.length()) a.optJSONObject(i)?.let { queue.add(it) } }
    }
    private fun saveQueue() = runCatching { queueFile.writeText(JSONArray(synchronized(queue) { queue.toList() }).toString()) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun log(m: String) { if (opts?.debug != false) Log.d(TAG, m) }

    private object Lifecycle : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) {
            if (started++ == 0) {
                val now = System.currentTimeMillis()
                val newSession = sessionId.isEmpty() || now - lastActive > SESSION_TIMEOUT
                if (newSession) track("\$launch")
                touchSession(now)
            }
        }
        override fun onActivityStopped(a: Activity) {
            if (--started <= 0) { started = 0; lastActive = System.currentTimeMillis(); flush() }
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}
