package com.taocrypt.micmask

import org.json.JSONArray
import org.json.JSONObject

/** 用户自存的声线/场景预设。mode：1=普通变声 2=虚拟声卡 */
data class UserPreset(val name: String, val mode: Int, val semis: Float, val fx: List<Float>) {
    fun toJson(): JSONObject = JSONObject().put("v", 1).put("name", name).put("mode", mode).put("semis", semis.toDouble())
        .put("fx", JSONArray(fx.map { it.toDouble() }))

    /** 分享码：MICMASK-PRESET:<base64(json)>，可直接贴进聊天 */
    fun toShareCode(): String = PREFIX + android.util.Base64.encodeToString(toJson().toString().toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)

    companion object {
        const val PREFIX = "MICMASK-PRESET:"
        fun fromJson(o: JSONObject): UserPreset {
            val a = o.getJSONArray("fx")
            val n = com.taocrypt.micmask.inject.FxChain.neutral()
            // 旧版本参数少：缺的补中性值；多的丢弃
            val fx = n.indices.map { i -> if (i < a.length()) a.getDouble(i).toFloat() else n[i] }
            return UserPreset(o.getString("name").take(24), o.optInt("mode", 2).coerceIn(1, 2), o.optDouble("semis", 0.0).toFloat().coerceIn(-12f, 12f), fx)
        }
        fun fromShareCode(text: String): UserPreset? = runCatching {
            val i = text.indexOf(PREFIX); if (i < 0) return null
            val b64 = text.substring(i + PREFIX.length).trim().takeWhile { !it.isWhitespace() }
            fromJson(JSONObject(String(android.util.Base64.decode(b64, android.util.Base64.URL_SAFE))))
        }.getOrNull()
    }
}
