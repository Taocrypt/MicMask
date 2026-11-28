package com.taocrypt.micmask.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已保存设备（电脑 → 手机发送的目标）。按协议设备 ID（握手里对方的 16 字节 deviceId，十六进制）识别；
 * mDNS 只广播 ID 前 4 字节（TXT "id"），用前缀匹配。存在 Settings "saved_devs"（JSON）。
 * 配对码与设备一起保存（v1 协议每次连接都要配对码；手机不换码就能自动连）。
 */
data class SavedDev(
    val id: String,            // 对方 deviceId 十六进制（32 位）
    val name: String,          // 对方设备名（握手里的）
    val alias: String = "",    // 用户改的名字
    val host: String = "",
    val port: Int = 47310,
    val code: String = "",
    val auto: Boolean = true,  // 参与自动连接
    val lastOk: Long = 0,
) {
    val title get() = alias.ifBlank { name }
    fun matches(p: PeerDevice): Boolean =
        if (p.idHex.length >= 8) id.startsWith(p.idHex) else p.name == name
}

object SavedDevices {
    var list by mutableStateOf(load()); private set

    private fun load(): List<SavedDev> = runCatching {
        val a = JSONArray(Settings.get("saved_devs", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            SavedDev(it.optString("id"), it.optString("name"), it.optString("alias"), it.optString("host"), it.optInt("port", 47310),
                it.optString("code"), it.optBoolean("auto", true), it.optLong("lastOk"))
        }.filter { it.id.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun save(l: List<SavedDev>) {
        list = l
        Settings.set("saved_devs", JSONArray(l.map {
            JSONObject().put("id", it.id).put("name", it.name).put("alias", it.alias).put("host", it.host).put("port", it.port)
                .put("code", it.code).put("auto", it.auto).put("lastOk", it.lastOk)
        }).toString())
    }

    fun byId(id: String) = list.firstOrNull { it.id == id }
    fun match(p: PeerDevice) = list.firstOrNull { it.matches(p) }

    /** 连接成功后记住（同 ID 覆盖地址/配对码/名字，保留用户改的名字） */
    @Synchronized fun remember(id: String, name: String, host: String, port: Int, code: String) {
        val old = byId(id)
        val n = (old ?: SavedDev(id, name)).copy(name = name, host = host, port = port, code = code, lastOk = System.currentTimeMillis())
        save(listOf(n) + list.filter { it.id != id })
    }

    @Synchronized fun update(id: String, f: (SavedDev) -> SavedDev) { save(list.map { if (it.id == id) f(it) else it }) }
    @Synchronized fun remove(id: String) { save(list.filter { it.id != id }) }
}
