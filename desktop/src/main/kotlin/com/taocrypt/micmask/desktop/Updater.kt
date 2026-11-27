package com.taocrypt.micmask.desktop

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * 电脑版检查更新：读 GitHub Releases，只看 win-v 开头的标签（电脑版独立标签，Release 一律 make_latest=false，不影响安卓的 Latest）。
 * 资产：MicMask-<版本>-windows-x64.msi / .exe。比较规则同手机端 Updater（数字段 + alpha<beta<rc<正式）。
 * 镜像、update-feed（win-beta.json / win-stable.json）是下一步，接口与手机端一致。
 */
object Updater {
    const val API = "https://api.github.com/repos/Taocrypt/MicMask/releases?per_page=30"
    const val TAG_PREFIX = "win-v"

    data class Asset(val name: String, val url: String, val size: Long)
    data class Release(val tag: String, val version: String, val pre: Boolean, val notes: String, val page: String, val assets: List<Asset>) {
        fun installer(): Asset? = assets.firstOrNull { it.name.endsWith(".msi") } ?: assets.firstOrNull { it.name.endsWith(".exe") }
    }

    fun isPre(v: String) = Regex("(?i)(alpha|beta|rc|dev)").containsMatchIn(v)

    private fun verKey(v: String): List<Int> {
        val core = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }.let { it + List((3 - it.size).coerceAtLeast(0)) { 0 } }
        val pre = v.substringAfter('-', "")
        val rank = when { pre.isEmpty() -> 4; pre.startsWith("rc") -> 3; pre.startsWith("beta") -> 2; pre.startsWith("alpha") -> 1; else -> 0 }
        val n = Regex("\\d+").find(pre)?.value?.toIntOrNull() ?: 0
        return core.take(3) + rank + n
    }

    fun isNewer(remote: String, local: String): Boolean {
        val a = verKey(remote); val b = verKey(local)
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    /** 返回比当前新的最新 Release；没有返回 null；网络失败抛异常 */
    fun check(current: String = BuildInfo.VERSION, includePre: Boolean = isPre(BuildInfo.VERSION)): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "MicMask-Desktop/${BuildInfo.VERSION}")
        if (c.responseCode != 200) throw IllegalStateException("GitHub 返回 ${c.responseCode}")
        val arr = JSONArray(c.inputStream.bufferedReader().readText())
        val rels = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && it.optString("tag_name").startsWith(TAG_PREFIX) }
            .map { j ->
                val a = j.optJSONArray("assets") ?: JSONArray()
                Release(j.optString("tag_name"), j.optString("tag_name").removePrefix(TAG_PREFIX), j.optBoolean("prerelease"), j.optString("body"), j.optString("html_url"),
                    (0 until a.length()).map { a.getJSONObject(it) }.map { Asset(it.optString("name"), it.optString("browser_download_url"), it.optLong("size")) })
            }
            .filter { includePre || !it.pre }
        return rels.filter { isNewer(it.version, current) }.maxWithOrNull { x, y -> if (isNewer(x.version, y.version)) 1 else if (isNewer(y.version, x.version)) -1 else 0 }
    }
}
