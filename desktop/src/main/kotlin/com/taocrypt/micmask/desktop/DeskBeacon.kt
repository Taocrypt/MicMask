package com.taocrypt.micmask.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * 电脑版 Beacon（统计 / 公告 / 赞赏墙），与手机端同一后台、同一 App（读同一个公告源），接口照手机端 beacon/Beacon.kt：
 *   GET  {base}/v1/{appId}/config?lang=&ch=windows&track=&iid=   公告（不带 ver：电脑版版本号与手机 versionCode 不可比，版本条件对电脑版不生效）
 *   GET  {base}/v1/{appId}/sponsors                              赞赏墙
 *   POST {base}/v1/{appId}/events  {iid,sid,ver,vn,ch,os,dev,lang,plat,ev:[…]}
 *   请求头 X-Client-Key。
 * 平台维度：后台目前没有「平台」字段，电脑版用渠道 ch=windows 区分（统计按渠道分组；公告在后台条件里选渠道
 * 「windows」= 只给电脑版，选「github」= 只给手机版，不选 = 都显示）；另外每条事件带 plat=windows，请求体带 plat 字段，后台以后加平台维度可直接用。
 * 客户端还会过滤公告里的 platforms / plat 字段（数组或逗号分隔，后台以后加了就生效）：写了且不含 windows 的不显示。
 *
 * 开关：BEACON_URL / BEACON_APP_ID / BEACON_KEY 构建时注入（CI：secrets.BEACON_URL、secrets.BEACON_APP_ID、secrets.DESKTOP_BEACON_KEY）。
 * 任何一个为空 → [enabled]=false：不发任何请求，界面只显示「未启用」，不显示任何占位数据。
 * 统计还需要用户同意（首次启动询问一次，设置里随时可关）；公告、赞赏墙不需要统计同意（与手机一致）。
 */
object DeskBeacon {
    /** 自测可覆盖（指向本机模拟服务）；正式运行用构建注入的值 */
    @Volatile var testOverride: Triple<String, String, String>? = null
    private val url get() = testOverride?.first ?: BuildInfo.BEACON_URL
    private val appId get() = testOverride?.second ?: BuildInfo.BEACON_APP_ID
    private val key get() = testOverride?.third ?: BuildInfo.BEACON_KEY
    val enabled get() = url.isNotBlank() && appId.isNotBlank() && key.isNotBlank()
    private val base get() = url.trimEnd('/') + "/v1/" + appId
    const val CHANNEL = "windows"
    const val PLATFORM = "windows"
    const val CONSENT_VERSION = 1

    /** 请求计数（自测验证「未启用时不发任何请求」） */
    @Volatile var requests = 0; private set

    // ------------------------------------------------ 同意 / 统计开关

    var analytics by mutableStateOf(Settings.getBool("beacon_analytics", false)); private set
    fun enableAnalytics(v: Boolean) {
        analytics = v; Settings.setBool("beacon_analytics", v)
        if (!v) synchronized(queue) { queue.clear(); saveQueue() }
    }
    /** 需要弹同意框：启用了后台且没确认过当前版本说明 */
    val consentNeeded get() = enabled && Settings.getInt("beacon_consent", 0) < CONSENT_VERSION
    fun acceptConsent(stats: Boolean) { Settings.setInt("beacon_consent", CONSENT_VERSION); enableAnalytics(stats); if (stats) track("\$launch", launchProps()) }

    val installId: String by lazy {
        Settings.get("beacon_iid").takeIf { Regex("^[A-Za-z0-9_-]{16,64}$").matches(it) }
            ?: Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }).also { Settings.set("beacon_iid", it) }
    }
    private val sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12).also { SecureRandom().nextBytes(it) })

    // ------------------------------------------------ HTTP

    class Resp(val code: Int, val body: String, val etag: String?)
    private fun req(u: String, method: String = "GET", body: String? = null, etag: String? = null): Resp {
        requests++
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 10000; c.useCaches = false
            c.setRequestProperty("X-Client-Key", key)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "MicMask-Desktop/${BuildInfo.VERSION}")
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            if (body != null) {
                val b = body.toByteArray(Charsets.UTF_8)
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }
            }
            val code = c.responseCode
            val st = if (code >= 400) c.errorStream else if (code == 304) null else c.inputStream
            return Resp(code, st?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "", c.getHeaderField("ETag"))
        } finally { c.disconnect() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------ 公告

    data class Button(val text: String, val url: String?)
    data class Notice(
        val id: Long, val title: String, val body: String, val buttons: List<Button>, val style: String, val frequency: String,
        val priority: Int, val rev: Long, val force: String, val ackSeconds: Int, val kind: String, val end: Long?,
    )

    private val cacheFile get() = File(Settings.dir, "beacon_config.json")
    @Volatile var config: JSONObject? = null; private set
    /** 最近一次拉配置是否失败（界面：「暂时无法加载」） */
    var configFailed by mutableStateOf(false); private set
    /** 配置刷新计数（Compose 观察） */
    var configRev by mutableStateOf(0); private set

    /** 拉配置（带 ETag；离线用缓存）。在 IO 线程调用 */
    fun fetchConfig() {
        if (!enabled) return
        if (config == null) runCatching { config = JSONObject(cacheFile.readText()) }
        val q = "lang=${enc(Locale.getDefault().toLanguageTag())}&ch=$CHANNEL&track=${if (Updater.isPre(BuildInfo.VERSION)) "beta" else "stable"}&iid=${enc(installId)}&plat=$PLATFORM"
        val r = runCatching { req("$base/config?$q", etag = Settings.get("beacon_etag").ifEmpty { null }.takeIf { config != null }) }.getOrNull()
        when (r?.code) {
            200 -> runCatching { val j = JSONObject(r.body); config = j; cacheFile.writeText(r.body); Settings.set("beacon_etag", r.etag ?: ""); configFailed = false }
                .onFailure { configFailed = config == null }
            304 -> configFailed = false
            else -> { configFailed = config == null; DeskLog.log('W', "系统", "公告配置拉取失败：${r?.code ?: "网络错误"}") }
        }
        configRev++
    }

    /** 当前有效的全部公告（已按平台过滤、过期的去掉） */
    fun notices(c: JSONObject? = config): List<Notice> {
        val arr = c?.optJSONArray("notices") ?: return emptyList()
        val now = System.currentTimeMillis()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { forWindows(it) }.map { o ->
            val bs = o.optJSONArray("buttons") ?: JSONArray()
            Notice(o.optLong("id"), o.optString("title"), o.optString("body"),
                (0 until bs.length()).mapNotNull { bs.optJSONObject(it) }.map { Button(it.optString("text"), it.optString("url").ifEmpty { null }) },
                o.optString("style", "dialog"), o.optString("frequency", "once"), o.optInt("priority"), o.optLong("rev"),
                o.optString("force", "none"), o.optInt("ackSeconds"), o.optString("kind", "normal"),
                if (o.has("end") && !o.isNull("end")) o.optLong("end") else null)
        }.filter { it.end == null || it.end > now }.sortedByDescending { it.priority }
    }

    /** 公告的平台字段（platforms / plat，数组或逗号分隔）：没写 = 所有平台；写了就必须含 windows / desktop */
    fun forWindows(o: JSONObject): Boolean {
        val raw = o.opt("platforms") ?: o.opt("plat") ?: return true
        val list = when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            else -> raw.toString().split(',')
        }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return list.isEmpty() || "windows" in list || "desktop" in list || "all" in list
    }

    /** 本次启动要弹的公告（频率规则同手机：once 同版本只一次、daily 每天一次、every_launch 每次启动；阻断 > 强制阅读 > 普通） */
    private val shownThisLaunch = HashSet<Long>()
    fun pending(): List<Notice> {
        val today = System.currentTimeMillis() / 86_400_000L
        return notices().filter {
            when {
                it.force == "block" -> true
                it.force == "ack" -> Settings.get("bn_ack_${it.id}") != it.rev.toString() && it.id !in shownThisLaunch
                Settings.get("bn_mute_${it.id}") == it.rev.toString() -> false
                it.frequency == "once" -> Settings.get("bn_once_${it.id}") != it.rev.toString()
                it.frequency == "daily" -> Settings.get("bn_day_${it.id}") != today.toString()
                else -> it.id !in shownThisLaunch
            }
        }.sortedBy { when (it.force) { "block" -> 0; "ack" -> 1; else -> 2 } }
    }
    fun blocking(): Notice? = notices().firstOrNull { it.force == "block" }
    fun markShown(n: Notice) {
        shownThisLaunch += n.id
        Settings.set("bn_once_${n.id}", n.rev.toString()); Settings.set("bn_day_${n.id}", (System.currentTimeMillis() / 86_400_000L).toString())
        track("\$notice_view", mapOf("notice" to n.id))
    }
    fun markAcked(n: Notice) { Settings.set("bn_ack_${n.id}", n.rev.toString()); track("\$notice_ack", mapOf("notice" to n.id)) }
    fun markMuted(n: Notice) { Settings.set("bn_mute_${n.id}", n.rev.toString()); track("\$notice_mute", mapOf("notice" to n.id)) }
    fun clicked(n: Notice, button: String) = track("\$notice_click", mapOf("notice" to n.id, "button" to button))

    // ------------------------------------------------ 赞赏墙

    data class Entry(val name: String, val tier: String?, val active: Boolean, val contributor: Boolean, val remark: String?)
    data class Wall(val count: Int, val total: Double, val sponsors: List<Entry>, val contributors: List<Entry>)

    /** 拉赞赏墙；未启用返回 null，失败抛异常（界面显示「暂时无法加载」），后台返回空表就是空表 —— 不编造任何名字 */
    fun sponsors(): Wall? {
        if (!enabled) return null
        val r = req("$base/sponsors")
        if (r.code != 200) throw IllegalStateException("HTTP ${r.code}")
        return parseWall(JSONObject(r.body))
    }

    fun parseWall(j: JSONObject): Wall {
        val now = System.currentTimeMillis() / 1000
        fun arr(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
        val sp = arr("sponsors").mapNotNull { o ->
            val name = o.optString("name").trim(); if (name.isEmpty()) return@mapNotNull null
            val c = o.optString("kind", "sponsor") == "contributor"
            val exp = o.optLong("expire", 0)
            Entry(name, if (c) null else o.optString("tier").ifEmpty { null }, c || exp == 0L || exp > now, c, o.optString("remark").ifEmpty { null })
        }
        val gh = arr("contributors").mapNotNull { o ->
            val name = o.optString("name").ifEmpty { o.optString("login") }.trim(); if (name.isEmpty()) return@mapNotNull null
            Entry(name, null, true, true, o.optString("remark").ifEmpty { null })
        }
        return Wall(j.optInt("count"), j.optDouble("total", 0.0), sp, gh)
    }

    /** 爱发电链接：启用时带 custom_order_id=<app>:<设备 ID>（与手机一致，付款后可绑定）；未启用给作者主页 */
    fun donateUrl(): String = if (enabled) "https://afdian.com/order/create?product_type=0&custom_order_id=${enc("$appId:$installId")}" else "https://afdian.com/a/taocrypt"

    // ------------------------------------------------ 统计

    private val queue = ArrayList<JSONObject>()
    private val queueFile get() = File(Settings.dir, "beacon_queue.json")
    @Volatile private var loaded = false

    private fun launchProps() = mapOf("plat" to PLATFORM, "osv" to (System.getProperty("os.version") ?: ""), "arch" to (System.getProperty("os.arch") ?: ""))

    /** 启动：拉公告、记 $launch、每 60 秒上报一次。未启用时什么都不做 */
    fun start() {
        if (!enabled) return
        Thread({
            runCatching { fetchConfig() }
            if (analytics && !consentNeeded) track("\$launch", launchProps())
            while (true) {
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
                runCatching { flush() }
            }
        }, "beacon").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** 记录事件（启用 + 用户同意才排队）。只传类别与数值，不传用户内容 */
    fun track(name: String, props: Map<String, Any?> = emptyMap()) {
        if (!enabled || !analytics) return
        val p = JSONObject().put("plat", PLATFORM)
        for ((k, v) in props.entries.take(9)) when (v) { is String -> p.put(k, v.take(100)); is Number, is Boolean -> p.put(k, v); else -> {} }
        synchronized(queue) {
            loadQueue()
            queue.add(JSONObject().put("n", name).put("t", System.currentTimeMillis()).put("p", p))
            while (queue.size > 500) queue.removeAt(0)
            saveQueue()
        }
    }

    fun flush() {
        if (!enabled || !analytics) return
        while (true) {
            val batch = synchronized(queue) { loadQueue(); queue.take(50) }
            if (batch.isEmpty()) return
            val body = JSONObject().put("iid", installId).put("sid", sessionId).put("ver", 0).put("vn", BuildInfo.VERSION).put("ch", CHANNEL)
                .put("os", ("Windows " + (System.getProperty("os.version") ?: "")).take(20)).put("dev", "").put("lang", Locale.getDefault().toLanguageTag())
                .put("plat", PLATFORM).put("ev", JSONArray(batch))
            val code = runCatching { req("$base/events", "POST", body.toString()).code }.getOrDefault(-1)
            if (code in 200..299 || (code in 400..499 && code != 429)) synchronized(queue) { repeat(batch.size) { if (queue.isNotEmpty()) queue.removeAt(0) }; saveQueue() }
            else return
        }
    }

    private fun loadQueue() {
        if (loaded) return
        loaded = true
        runCatching { val a = JSONArray(queueFile.readText()); for (i in 0 until a.length()) a.optJSONObject(i)?.let { queue.add(it) } }
    }
    private fun saveQueue() { runCatching { queueFile.writeText(JSONArray(queue.toList()).toString()) } }

    /** $crash：异常类名 + 前 3 个本包栈帧（DeskLog 的未捕获异常处理里调用） */
    fun crash(th: Thread, ex: Throwable) {
        if (!enabled || !analytics) return
        val frames = ex.stackTrace.filter { it.className.startsWith("com.taocrypt.micmask") }.take(3)
            .map { "${it.className.removePrefix("com.taocrypt.micmask.")}.${it.methodName}:${it.lineNumber}".take(100) }
        val p = linkedMapOf<String, Any?>("cls" to ex.javaClass.name.take(100), "thread" to (if (th.name.contains("AWT-EventQueue")) "ui" else "bg"))
        frames.forEachIndexed { i, s -> p["f${i + 1}"] = s }
        track("\$crash", p)
    }
}
