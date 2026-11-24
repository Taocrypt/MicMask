package com.taocrypt.beacon

import org.json.JSONArray
import org.json.JSONObject

data class NoticeButton(val text: String, val url: String?)

data class Notice(
    val id: Long, val title: String, val body: String, val buttons: List<NoticeButton>,
    /** dialog | banner */ val style: String,
    /** once | every_launch | daily */ val frequency: String,
    val priority: Int, val rev: Long,
    /** none 普通 | ack 必须点「我已阅读」| block 生效期间 App 不可用 */ val force: String = "none",
    /** force=ack 时的阅读倒计时（秒） */ val ackSeconds: Int = 0,
    /** normal 普通公告 | important 重要公告 | risk 风险提醒 | event 活动通知 */ val kind: String = "normal",
    /** 头图地址，可空 */ val image: String? = null,
)

/** 轮播图，已按权重从大到小排好 */
data class Banner(
    val id: Long,
    /** normal 普通（图上文下）| large 大封面（文字压图）| small 小封面（左图右文） */ val style: String,
    /** 大标题 */ val title: String,
    /** 小标题 */ val subtitle: String,
    /** 标签，如 广告 / 推荐 / 上新，可空 */ val tag: String,
    /** 按钮文案，空=不显示按钮 */ val button: String,
    /** 点击后的弹窗内容（Markdown），空=直接跳转 */ val popup: String,
    val image: String, val url: String?,
)

data class Slot(
    val id: Long, val position: String, val title: String, val subtitle: String,
    val image: String?, val url: String?, val weight: Int, val priority: Int,
)

data class Mirror(val name: String, val url: String)

data class Update(
    val track: String, val versionCode: Long, val versionName: String, val changelog: String,
    val mirrors: List<Mirror>, val size: Long?, val sha256: String?, val force: Boolean, val publishedAt: Long,
)

class BeaconConfig internal constructor(val raw: JSONObject) {
    val notices: List<Notice> = raw.optJSONArray("notices").objs().map {
        Notice(
            it.optLong("id"), it.optString("title"), it.optString("body"),
            it.optJSONArray("buttons").objs().map { b -> NoticeButton(b.optString("text"), b.optStringOrNull("url")) },
            it.optString("style", "dialog"), it.optString("frequency", "once"), it.optInt("priority"), it.optLong("rev"),
            it.optString("force", "none"), it.optInt("ackSeconds"),
            it.optString("kind", "normal"), it.optStringOrNull("image"),
        )
    }
    val slots: List<Slot> = raw.optJSONArray("slots").objs().map {
        Slot(
            it.optLong("id"), it.optString("position"), it.optString("title"), it.optString("subtitle"),
            it.optStringOrNull("image"), it.optStringOrNull("url"), it.optInt("weight", 1), it.optInt("priority"),
        )
    }
    val banners: List<Banner> = raw.optJSONArray("banners").objs().map {
        Banner(
            it.optLong("id"), it.optString("style", "normal"), it.optString("title"), it.optString("subtitle"), it.optString("tag"),
            it.optString("button"), it.optString("popup"), it.optString("image"), it.optStringOrNull("url"),
        )
    }
    val flags: JSONObject = raw.optJSONObject("flags") ?: JSONObject()
    val update: Update? = raw.optJSONObject("update")?.let {
        Update(
            it.optString("track"), it.optLong("versionCode"), it.optString("versionName"), it.optString("changelog"),
            it.optJSONArray("mirrors").objs().map { m -> Mirror(m.optString("name"), m.optString("url")) },
            if (it.isNull("size")) null else it.optLong("size"), it.optStringOrNull("sha256"),
            it.optBoolean("force"), it.optLong("publishedAt"),
        )
    }

    fun flag(key: String, default: Boolean): Boolean = if (flags.has(key)) flags.optBoolean(key, default) else default
    fun flag(key: String, default: Long): Long = if (flags.has(key)) flags.optLong(key, default) else default
    fun flag(key: String, default: Double): Double = if (flags.has(key)) flags.optDouble(key, default) else default
    fun flag(key: String, default: String): String = if (flags.has(key)) flags.optString(key, default) else default
    fun flagJson(key: String): Any? = if (flags.has(key)) flags.opt(key) else null

    /** 按优先级取最高的一组，再按权重随机选一条 */
    fun pickSlot(position: String): Slot? {
        val list = slots.filter { it.position == position }
        if (list.isEmpty()) return null
        val top = list.maxOf { it.priority }
        val cands = list.filter { it.priority == top }
        var r = (Math.random() * cands.sumOf { maxOf(1, it.weight) }).toInt()
        for (s in cands) { r -= maxOf(1, s.weight); if (r < 0) return s }
        return cands.last()
    }
}

internal fun JSONArray?.objs(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.optStringOrNull(k: String): String? =
    if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
