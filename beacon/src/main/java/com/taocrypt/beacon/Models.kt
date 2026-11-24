package com.taocrypt.beacon

import org.json.JSONArray
import org.json.JSONObject

data class NoticeButton(val text: String, val url: String?)

data class Notice(
    val id: Long, val title: String, val body: String, val buttons: List<NoticeButton>,
    /** dialog | banner */ val style: String,
    /** once | every_launch | daily */ val frequency: String,
    val priority: Int, val rev: Long,
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
        )
    }
    val slots: List<Slot> = raw.optJSONArray("slots").objs().map {
        Slot(
            it.optLong("id"), it.optString("position"), it.optString("title"), it.optString("subtitle"),
            it.optStringOrNull("image"), it.optStringOrNull("url"), it.optInt("weight", 1), it.optInt("priority"),
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
