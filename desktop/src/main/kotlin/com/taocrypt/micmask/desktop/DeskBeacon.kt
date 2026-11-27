package com.taocrypt.micmask.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

/**
 * 电脑版 Beacon（骨架）：接口照手机端 beacon/Beacon.kt —— 同一后台、同样的路径与请求头：
 *   GET  {base}/v1/{appId}/config?ver=&lang=&ch=&track=&iid=&plat=windows   公告/推荐位/开关
 *   GET  {base}/v1/{appId}/sponsors                                          赞赏墙
 *   POST {base}/v1/{appId}/events   {iid, sid, ver, vn, ch, os, dev, lang, plat, ev:[…]}   统计
 *   请求头 X-Client-Key。
 * 现状：BEACON_URL/APP_ID/KEY 只在构建时从环境变量注入，desktop.yml 暂不传 → 整体关闭（所有调用空操作，界面显示「未启用」，不显示任何占位数据）。
 * 统计默认关，需用户在设置里同意；只用随机 install_id。后台按 plat 区分平台（需要 Beacon 后台加平台维度，见交接说明）。
 */
object DeskBeacon {
    val enabled get() = BuildInfo.BEACON_URL.isNotBlank() && BuildInfo.BEACON_APP_ID.isNotBlank() && BuildInfo.BEACON_KEY.isNotBlank()
    private val base get() = BuildInfo.BEACON_URL.trimEnd('/') + "/v1/" + BuildInfo.BEACON_APP_ID

    var analytics: Boolean
        get() = Settings.getBool("beacon_analytics", false)
        set(v) { Settings.setBool("beacon_analytics", v); if (!v) synchronized(queue) { queue.clear() } }

    val installId: String by lazy { Settings.get("beacon_iid").ifEmpty { UUID.randomUUID().toString().replace("-", "").also { Settings.set("beacon_iid", it) } } }
    private val sessionId = "s" + UUID.randomUUID().toString().replace("-", "").take(16)
    private val queue = ArrayList<JSONObject>()

    data class Notice(val id: Long, val title: String, val body: String)
    data class Sponsor(val name: String, val tier: String?)

    private fun req(url: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("X-Client-Key", BuildInfo.BEACON_KEY)
        c.setRequestProperty("Accept", "application/json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" }.getOrDefault("")
        return code to text
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** 拉公告；未启用或失败返回空表 */
    fun notices(): List<Notice> {
        if (!enabled) return emptyList()
        val q = "ver=0&vn=${enc(BuildInfo.VERSION)}&lang=${enc(Locale.getDefault().toLanguageTag())}&ch=github&track=${if (Updater.isPre(BuildInfo.VERSION)) "beta" else "stable"}&iid=$installId&plat=windows"
        val (code, text) = runCatching { req("$base/config?$q") }.getOrNull() ?: return emptyList()
        if (code != 200) return emptyList()
        val arr = runCatching { JSONObject(text).optJSONArray("notices") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { Notice(it.optLong("id"), it.optString("title"), it.optString("body")) }
    }

    /** 拉赞赏墙；未启用或失败返回空表（界面显示「未启用 / 暂无」，不编造名字） */
    fun sponsors(): List<Sponsor> {
        if (!enabled) return emptyList()
        val (code, text) = runCatching { req("$base/sponsors") }.getOrNull() ?: return emptyList()
        if (code != 200) return emptyList()
        val arr = runCatching { JSONObject(text).optJSONArray("sponsors") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { Sponsor(it.optString("name"), it.optString("tier").ifEmpty { null }) }.filter { it.name.isNotBlank() }
    }

    /** 记录事件（只有启用且用户同意统计时才排队） */
    fun track(name: String, props: Map<String, Any?> = emptyMap()) {
        if (!enabled || !analytics) return
        synchronized(queue) { if (queue.size < 500) queue.add(JSONObject().put("n", name).put("t", System.currentTimeMillis()).put("p", JSONObject(props))) }
    }

    fun flush() {
        if (!enabled || !analytics) return
        val batch = synchronized(queue) { queue.take(50).also { queue.removeAll(it.toSet()) } }
        if (batch.isEmpty()) return
        val body = JSONObject().put("iid", installId).put("sid", sessionId).put("ver", 0).put("vn", BuildInfo.VERSION).put("ch", "github")
            .put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")).put("dev", "").put("lang", Locale.getDefault().toLanguageTag())
            .put("plat", "windows").put("ev", JSONArray(batch))
        runCatching { req("$base/events", "POST", body.toString()) }
    }
}
