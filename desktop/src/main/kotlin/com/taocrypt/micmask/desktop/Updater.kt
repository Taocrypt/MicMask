package com.taocrypt.micmask.desktop

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
        fun sums(): Asset? = assets.firstOrNull { it.name.equals("SHA256SUMS.txt", true) }
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

    // ------------------------------------------------------------ 下载并安装（应用内更新）
    // 流程：下载安装包到 %TEMP%\MicMask-update\ → 校验大小与 SHA256SUMS.txt → 用 msiexec 启动「带界面」的安装（不加 /quiet，
    // 用户能看到安装向导与 UAC，出错有提示）并写详细日志到日志文件夹 → 确认安装程序已在运行后退出自身（释放安装目录里的文件）。
    // 任何一步失败都写日志并在关于页显示原因，不退出。

    /** 下载目录（%TEMP%\MicMask-update） */
    val downloadDir: File get() = File(System.getProperty("java.io.tmpdir"), "MicMask-update").apply { mkdirs() }

    private fun open(url: String): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "MicMask-Desktop/${BuildInfo.VERSION}")
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); return@repeat }
            if (code != 200) throw IllegalStateException("下载返回 HTTP $code")
            return c
        }
        throw IllegalStateException("重定向次数过多")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 下载安装包并校验，返回本地文件；onProgress(已下载, 总大小) */
    fun download(rel: Release, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val a = rel.installer() ?: throw IllegalStateException("这个版本没有 Windows 安装包")
        val dst = File(downloadDir, a.name)
        val part = File(downloadDir, a.name + ".part")
        DeskLog.i("更新", "下载 ${a.name}（${a.size} 字节）← ${a.url}")
        val c = open(a.url)
        val total = c.contentLengthLong.takeIf { it > 0 } ?: a.size
        var got = 0L
        c.inputStream.use { i -> part.outputStream().use { o ->
            val b = ByteArray(1 shl 16); var last = 0L
            while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); got += n
                val now = System.currentTimeMillis(); if (now - last > 200) { last = now; onProgress(got, total) } }
        } }
        onProgress(got, total)
        if (a.size > 0 && got != a.size) { part.delete(); throw IllegalStateException("下载不完整：$got / ${a.size} 字节") }
        val want = rel.sums()?.let { s -> runCatching {
            open(s.url).inputStream.bufferedReader().readLines().map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size >= 2 && it.last().removePrefix("*") == a.name }?.first()?.lowercase()
        }.onFailure { DeskLog.w("更新", "取 SHA256SUMS 失败：${it.message}") }.getOrNull() }
        val have = sha256(part)
        if (want != null && want != have) { part.delete(); throw IllegalStateException("校验失败（SHA-256 不符），已删除下载文件") }
        dst.delete()
        if (!part.renameTo(dst)) { part.copyTo(dst, true); part.delete() }
        DeskLog.i("更新", "下载完成 ${dst.path}，SHA-256 $have${if (want == null) "（没有 SHA256SUMS，未比对）" else "，与 Release 一致"}")
        return dst
    }

    /**
     * 启动安装程序（带界面）。MSI：msiexec /i 安装包 /l*v 日志；EXE（jpackage 外壳，参数原样转给 msiexec）：安装包 /l*v 日志。
     * extra 只给自测用（如 /qn）；返回进程和日志文件。等 1.5 秒：安装程序已自己退出且退出码非 0/3010 时视为失败抛异常。
     */
    fun launchInstaller(f: File, extra: List<String> = emptyList()): Pair<Process, File> {
        val log = File(DeskLog.dir, "install-${f.nameWithoutExtension}-${System.currentTimeMillis() / 1000}.log")
        val cmd = if (f.name.endsWith(".msi", true))
            listOf(File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\msiexec.exe").path, "/i", f.absolutePath, "/l*v", log.absolutePath) + extra
        else listOf(f.absolutePath, "/l*v", log.absolutePath) + extra
        DeskLog.i("更新", "启动安装：${cmd.joinToString(" ")}")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            val code = p.exitValue()
            if (code != 0 && code != 3010) {
                DeskLog.e("更新", "安装程序立即退出，退出码 $code（${msiError(code)}），日志 ${log.path}")
                throw IllegalStateException("安装程序退出码 $code（${msiError(code)}），日志：${log.path}")
            }
        }
        return p to log
    }

    fun msiError(code: Int) = when (code) {
        0 -> "成功"; 1602 -> "用户取消"; 1603 -> "安装出错"; 1618 -> "另一个安装正在进行"; 1619 -> "安装包打不开"
        1638 -> "已安装同一产品的另一版本"; 3010 -> "成功，需要重启"; else -> "见日志"
    }

    /** 关于页「下载并安装」：下载 → 启动带界面的安装 → 退出自身。失败抛异常（调用方显示 + 已写日志） */
    fun downloadAndInstall(rel: Release, onProgress: (Long, Long) -> Unit) {
        val f = try { download(rel, onProgress) } catch (t: Throwable) { DeskLog.e("更新", "下载失败：${t.message}"); throw t }
        launchInstaller(f)
        DeskLog.i("更新", "安装程序已启动，退出 MicMask 以便覆盖安装（${BuildInfo.VERSION} → ${rel.version}）")
        AppExit.quit()
    }
}
