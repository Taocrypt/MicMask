package com.taocrypt.micmask.update

import com.taocrypt.micmask.Prefs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.taocrypt.micmask.BuildConfig
import com.taocrypt.micmask.L
import com.taocrypt.micmask.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 应用内更新（移植自 PureConnect 的 updater / update_mirrors）。
 *
 * 检查：GitHub API releases/latest 直连 → 用户镜像/直连取 release 附带的 update.json → 内置镜像并发取 update.json。
 * 下载：用户镜像按顺序（最后直连）；否则「自动」模式下直连与内置镜像并发测速，按速度排序逐个尝试，失败自动换下一个。
 * 强制更新：发布说明里的「最低支持版本: x」，低于它时更新对话框不能关闭。
 */
object Updater {
    const val REPO = "https://github.com/Taocrypt/MicMask"
    const val RELEASES = "$REPO/releases"
    private const val API_LIST = "https://api.github.com/repos/Taocrypt/MicMask/releases?per_page=20"
    /** 滚动发布 update-feed 里的通道清单：stable.json（只含正式版）/ beta.json（含测试版） */
    const val FEED_TAG = "update-feed"
    private fun feedJson(beta: Boolean) = "$RELEASES/download/$FEED_TAG/${if (beta) "beta" else "stable"}.json"
    private const val UPDATE_JSON = "$RELEASES/latest/download/update.json"
    private const val MIRROR_LIST_API = "https://git.mxg.pub/api/github/list"
    val BUILTIN_MIRRORS = listOf(
        "https://gh-proxy.com", "https://fastgit.cc", "https://ghfile.geekertao.top", "https://tvv.tw", "https://git.yylx.win",
    )
    private const val CH = "micmask_update"
    const val NID = 7301
    private const val NID_DONE = 7302

    // ---------------------------------------------------------------- 数据
    data class Asset(val name: String, val url: String, val size: Long)
    data class Release(val tag: String, val notes: String, val pageUrl: String, val assets: List<Asset>, val minVersion: String?) {
        val version get() = tag.removePrefix("v").removePrefix("V")
        fun forces(current: String = BuildConfig.VERSION_NAME) = minVersion != null && isNewer(minVersion, current)
        fun apk(): Asset? = assets.firstOrNull { it.name.endsWith(".apk") && it.name.contains("arm64") } ?: assets.firstOrNull { it.name.endsWith(".apk") }
    }

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class Latest(val tag: String) : State()
        data class Newer(val r: Release) : State()
        data class Fail(val msg: String) : State()
        data class Downloading(val r: Release, val got: Long, val total: Long, val source: String, val bps: Double) : State()
        data class Downloaded(val r: Release, val file: File) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    /** 是否要弹更新对话框（启动自动检查发现新版、或用户点了更新） */
    val dialog = MutableStateFlow(false)
    /** 当前必须安装的强制更新（下载失败/取消后对话框也不消失） */
    val forced = MutableStateFlow<Release?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dlJob: Job? = null

    // ---------------------------------------------------------------- 设置
    fun mode(ctx: Context) = Prefs.main(ctx).getInt("upd_mode", 0) // 0 自动 1 始终直连
    fun setMode(ctx: Context, m: Int) = Prefs.main(ctx).edit().putInt("upd_mode", m).apply()
    fun userMirrors(ctx: Context): List<String> =
        Prefs.main(ctx).getString("upd_mirrors", "")!!.lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun setUserMirrors(ctx: Context, l: List<String>) = Prefs.main(ctx).edit().putString("upd_mirrors", l.joinToString("\n")).apply()
    /** 测试版 = 带 alpha/beta/rc 的版本 */
    fun isPre(v: String) = Regex("(?i)(alpha|beta|rc)").containsMatchIn(v)
    /** 更新通道：true=测试版（收到所有版本）false=稳定版；默认跟随当前安装的版本 */
    fun betaChannel(ctx: Context) = Prefs.main(ctx).getBoolean("upd_beta", isPre(BuildConfig.VERSION_NAME))
    fun setBetaChannel(ctx: Context, on: Boolean) {
        Prefs.main(ctx).edit().putBoolean("upd_beta", on).apply()
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }
    fun autoCheck(ctx: Context) = Prefs.main(ctx).getBoolean("upd_auto", true)
    fun setAutoCheck(ctx: Context, on: Boolean) = Prefs.main(ctx).edit().putBoolean("upd_auto", on).apply()
    private fun skipped(ctx: Context) = Prefs.main(ctx).getString("upd_skip", "")
    fun skip(ctx: Context, tag: String) { Prefs.main(ctx).edit().putString("upd_skip", tag).apply(); dialog.value = false }

    /** 用户输入的镜像 → https://host[/path]，不合法返回 null */
    fun normalizeMirror(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.contains(' ')) return null
        if (!s.contains("://")) s = "https://$s"
        val u = runCatching { Uri.parse(s) }.getOrNull() ?: return null
        if (u.scheme != "http" && u.scheme != "https") return null
        val host = u.host ?: return null
        if (!host.contains('.') && host != "localhost") return null
        if (u.query != null || u.fragment != null) return null
        return s.trimEnd('/')
    }

    fun mirrorUrl(base: String?, gh: String) = if (base == null) gh else base.trimEnd('/') + "/" + gh
    private fun nameOf(base: String?) = if (base == null) "GitHub 直连" else (Uri.parse(base).host ?: base)

    // ---------------------------------------------------------------- 版本比较
    /** "v2.0-alpha15" → [2,0,0,stage,15]；alpha<beta<rc<正式 */
    private fun verKey(v: String): List<Int> {
        val s = v.trim().removePrefix("v").removePrefix("V").lowercase().substringBefore('+')
        val main = s.substringBefore('-')
        val pre = s.substringAfter('-', "")
        val nums = main.split('.').map { it.toIntOrNull() ?: 0 }.let { it + List((3 - it.size).coerceAtLeast(0)) { 0 } }
        val stage = when { pre.startsWith("alpha") -> 0; pre.startsWith("beta") -> 1; pre.startsWith("rc") -> 2; pre.isEmpty() -> 3; else -> 0 }
        val n = Regex("\\d+").find(pre)?.value?.toIntOrNull() ?: 0
        return nums.take(3) + stage + n
    }

    fun isNewer(remote: String, local: String): Boolean {
        val a = verKey(remote); val b = verKey(local)
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    private val minRe = Regex("""^[ \t]*(?:<!--[ \t]*)?(?:[-*][ \t]*)?(?:最低支持版本|min-version|min_supported_version)[ \t]*[:：][ \t]*[vV]?([0-9][0-9A-Za-z.\-]*)[ \t]*(?:-->)?[ \t]*$""",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private fun parseMin(notes: String) = minRe.find(notes)?.groupValues?.get(1)
    private fun stripMin(notes: String) = notes.replace(minRe, "").replace(Regex("\n{3,}"), "\n\n").trim()

    // ---------------------------------------------------------------- 网络
    private class HttpFail(val code: Int, msg: String) : Exception(msg)

    private fun conn(url: String, timeout: Int) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeout; readTimeout = timeout; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "MicMask-updater/${BuildConfig.VERSION_NAME}")
    }

    private fun getText(url: String, timeout: Int, accept: String? = null): String {
        val c = conn(url, timeout)
        if (accept != null) c.setRequestProperty("Accept", accept)
        try {
            val code = c.responseCode
            if (code != 200) throw HttpFail(code, "HTTP $code")
            if (c.contentType?.startsWith("text/html") == true) throw HttpFail(0, "返回网页而不是数据")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun fromApi(j: JSONObject): Release {
        val body = j.optString("body")
        val arr = j.optJSONArray("assets") ?: JSONArray()
        return Release(j.optString("tag_name"), stripMin(body), j.optString("html_url", RELEASES),
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { Asset(it.optString("name"), it.optString("browser_download_url"), it.optLong("size")) },
            parseMin(body))
    }

    private fun fromUpdateJson(j: JSONObject): Release {
        val notes = j.optString("notes")
        var tag = j.optString("tag")
        if (tag.isEmpty() && j.optString("version").isNotEmpty()) tag = "v" + j.optString("version")
        val arr = j.optJSONArray("assets") ?: JSONArray()
        val min = j.optString("min_supported_version").takeIf { it.isNotBlank() && it != "null" }?.removePrefix("v")
        return Release(tag, stripMin(notes), if (tag.isEmpty()) RELEASES else "$RELEASES/tag/$tag",
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { Asset(it.optString("name"), it.optString("url"), it.optLong("size")) },
            min ?: parseMin(notes))
    }

    /** 并发跑 [blocks]，返回第一个非 null 结果 */
    private suspend fun <T> firstOf(blocks: List<suspend () -> T?>): T? = coroutineScope {
        if (blocks.isEmpty()) return@coroutineScope null
        val ch = Channel<T?>(blocks.size)
        val jobs = blocks.map { b -> launch { ch.send(runCatching { b() }.getOrNull()) } }
        var res: T? = null
        repeat(blocks.size) { if (res == null) res = ch.receive() }
        jobs.forEach { it.cancel() }
        res
    }

    private suspend fun mirrorCandidates(ctx: Context): List<String> {
        val sp = Prefs.main(ctx)
        val fetched = withTimeoutOrNull(7000) {
            runCatching {
                val j = JSONObject(getText(MIRROR_LIST_API, 6000))
                val data = j.optJSONArray("data") ?: JSONArray()
                (0 until data.length()).map { data.getJSONObject(it) }
                    .filter { it.optString("status") == "success" }
                    .mapNotNull { o -> normalizeMirror(o.optString("url"))?.takeIf { it.startsWith("https://") }?.let { it to o.optDouble("latency", 1e9) } }
                    .distinctBy { it.first }.sortedBy { it.second }.map { it.first }
            }.getOrNull()
        }.orEmpty()
        if (fetched.isNotEmpty()) sp.edit().putString("upd_mirror_cache", fetched.joinToString("\n")).apply()
        val cached = sp.getString("upd_mirror_cache", "")!!.lines().filter { it.isNotBlank() }
        return ((fetched.ifEmpty { cached }) + BUILTIN_MIRRORS).distinct().take(10)
    }

    private suspend fun fetchRelease(ctx: Context): Release {
        val beta = betaChannel(ctx)
        val noStable = Exception("还没有发布稳定版，可在设置里切到测试版")
        var last: Exception = Exception("网络不通")
        try {
            val arr = JSONArray(getText(API_LIST, 8000, "application/vnd.github+json"))
            val rs = (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { !it.optBoolean("draft") && it.optString("tag_name") != FEED_TAG }
                .map { fromApi(it) }.filter { it.tag.isNotEmpty() && (beta || !isPre(it.tag)) }
            val best = rs.maxWithOrNull { a, b -> if (isNewer(a.tag, b.tag)) 1 else if (isNewer(b.tag, a.tag)) -1 else 0 }
            if (best != null) return best
            if (arr.length() > 0) throw if (beta) Exception("暂无公开发布版本") else noStable
            throw Exception("暂无公开发布版本")
        } catch (e: HttpFail) {
            last = if (e.code == 403 || e.code == 429) Exception("GitHub 请求太频繁") else e
        } catch (e: org.json.JSONException) { last = e
        } catch (e: java.io.IOException) { last = e }
        L.i("更新：GitHub API 不通（${last.message}），改走通道清单（${if (beta) "测试版" else "稳定版"}）")
        // 测试版：先读通道清单，旧发布没有清单时退回 latest 的 update.json
        val urls = listOf(feedJson(beta)) + if (beta) listOf(UPDATE_JSON) else emptyList()
        fun parse(t: String) = fromUpdateJson(JSONObject(t)).also { if (it.tag.isEmpty()) throw Exception("清单为空") }
        val users = userMirrors(ctx)
        for (u in urls) for (m in users + listOf<String?>(null)) {
            try { return parse(getText(mirrorUrl(m, u), 6000)) }
            catch (e: HttpFail) { if (e.code == 404 && !beta) last = noStable else last = e }
            catch (e: Exception) { last = e }
        }
        if (mode(ctx) == 1) throw last
        val ms = mirrorCandidates(ctx)
        for (u in urls) firstOf(ms.map { m -> suspend { parse(getText(mirrorUrl(m, u), 8000)) } })?.let { return it }
        throw if (last === noStable) noStable else Exception("GitHub 和镜像都连不上")
    }

    /** 检查更新；[silent]=启动时自动检查：只在有新版且没被跳过（或强制）时弹框 */
    fun check(ctx: Context, silent: Boolean = false) {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            _state.value = try {
                val r = fetchRelease(ctx.applicationContext)
                if (isNewer(r.tag, BuildConfig.VERSION_NAME)) {
                    L.i("更新：发现 ${r.tag}${if (r.forces()) "（强制）" else ""}")
                    if (r.forces()) forced.value = r
                    if (!silent || r.forces() || (autoCheck(ctx) && skipped(ctx) != r.tag)) dialog.value = true
                    State.Newer(r)
                } else State.Latest(r.tag)
            } catch (e: Exception) {
                L.w("更新：检查失败 ${e.message}")
                State.Fail(e.message ?: "检查失败")
            }
        }
    }

    // ---------------------------------------------------------------- 测速
    data class Speed(val base: String?, val ok: Boolean, val bps: Double)

    private fun probe(base: String?, gh: String, limit: Int = 64 * 1024, timeoutMs: Int = 5000): Speed {
        val t0 = System.nanoTime(); var got = 0
        return try {
            val c = conn(mirrorUrl(base, gh), timeoutMs)
            c.setRequestProperty("Range", "bytes=0-${limit - 1}")
            val code = c.responseCode
            if ((code != 200 && code != 206) || c.contentType?.startsWith("text/html") == true) return Speed(base, false, 0.0)
            c.inputStream.use { ins ->
                val buf = ByteArray(16384)
                while (got < limit) {
                    if ((System.nanoTime() - t0) / 1e6 > timeoutMs) break
                    val n = ins.read(buf); if (n < 0) break; got += n
                }
            }
            c.disconnect()
            val sec = (System.nanoTime() - t0) / 1e9
            Speed(base, got >= limit || (code == 206 && got > 0), got / sec.coerceAtLeast(1e-3))
        } catch (_: Exception) { Speed(base, false, 0.0) }
    }

    /** 测所有候选（直连 + 用户镜像 + 内置镜像），镜像设置页用 */
    suspend fun speedTestAll(ctx: Context, gh: String = UPDATE_JSON): List<Speed> = withContext(Dispatchers.IO) {
        val bases = listOf<String?>(null) + userMirrors(ctx) + mirrorCandidates(ctx)
        probeAll(bases.distinct()) { probe(it, gh, 16 * 1024) }
    }

    private suspend fun probeAll(bases: List<String?>, f: (String?) -> Speed): List<Speed> = coroutineScope {
        bases.map { b -> async(Dispatchers.IO) { f(b) } }.awaitAll()
    }

    private suspend fun plan(ctx: Context, gh: String): List<String?> {
        val users = userMirrors(ctx)
        if (users.isNotEmpty()) return users + listOf(null)
        if (mode(ctx) == 1) return listOf(null)
        val ms = mirrorCandidates(ctx)
        val res = probeAll(listOf<String?>(null) + ms) { probe(it, gh) }
        val direct = res.first()
        val good = res.drop(1).filter { it.ok }.sortedByDescending { it.bps }
        val bad = res.drop(1).filter { !it.ok }
        L.i("更新：测速 " + res.joinToString { "${nameOf(it.base)}=${if (it.ok) "%.0fKB/s".format(it.bps / 1024) else "×"}" })
        // 直连够快（>300KB/s）优先直连
        return if (direct.ok && (good.isEmpty() || direct.bps >= 300 * 1024 || direct.bps >= good.first().bps))
            listOf<String?>(null) + good.map { it.base } + bad.map { it.base }
        else good.map { it.base } + listOf(null) + bad.map { it.base }
    }

    // ---------------------------------------------------------------- 下载与安装
    fun download(ctx: Context, r: Release) {
        val app = ctx.applicationContext
        val asset = r.apk() ?: run { _state.value = State.Fail("这个版本没有安卓安装包"); return }
        if (dlJob?.isActive == true) return
        dlJob = scope.launch {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.filter { it.name != asset.name && it.name != asset.name + ".part" }?.forEach { it.delete() }
            val file = File(dir, asset.name)
            if (asset.size > 0 && file.length() == asset.size) { done(app, r, file); return@launch }
            noteText = Triple("", 0L, asset.size); noteBps = 0.0
            _state.value = State.Downloading(r, 0, asset.size, "测速中…", 0.0)
            UpdateService.start(app)
            var err = "下载失败"
            for (b in plan(app, asset.url)) {
                ensureActive()
                val name = nameOf(b)
                try {
                    fetchTo(mirrorUrl(b, asset.url), file, asset.size) { got, total, bps ->
                        _state.value = State.Downloading(r, got, total, name, bps)
                        progressNote(app, r.tag, got, total, name)
                    }
                    L.i("更新：从 $name 下载完成 ${file.length()} 字节")
                    done(app, r, file); return@launch
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { err = "$name：${e.message}"; L.w("更新：$err，换下一个") }
            }
            UpdateService.stop(app); cancelNote(app)
            _state.value = State.Fail(err)
            failNote(app, r.tag, err)
        }
    }

    fun cancelDownload(ctx: Context) {
        dlJob?.cancel(); dlJob = null; UpdateService.stop(ctx.applicationContext); cancelNote(ctx)
        (_state.value as? State.Downloading)?.let { _state.value = State.Newer(it.r) }
    }

    private suspend fun fetchTo(url: String, file: File, size: Long, onProg: (Long, Long, Double) -> Unit) {
        val part = File(file.path + ".part"); part.delete()
        val c = conn(url, 30000)
        try {
            val code = c.responseCode
            if (code != 200) throw Exception("HTTP $code")
            if (c.contentType?.startsWith("text/html") == true) throw Exception("返回了网页")
            val len = c.contentLengthLong
            if (size > 0 && len > 0 && len != size) throw Exception("大小不对 $len")
            val total = if (len > 0) len else size
            var got = 0L; val t0 = System.nanoTime(); var lastUi = 0L
            c.inputStream.use { ins -> part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); got += n
                    val now = System.nanoTime()
                    if (now - lastUi > 500_000_000L) { lastUi = now; onProg(got, total, got / ((now - t0) / 1e9).coerceAtLeast(1e-3)) }
                }
            } }
            if (total > 0 && got != total) throw Exception("没下完")
            file.delete(); if (!part.renameTo(file)) throw Exception("保存失败")
        } finally { c.disconnect(); part.delete() }
    }

    private fun done(ctx: Context, r: Release, f: File) {
        _state.value = State.Downloaded(r, f)
        UpdateService.stop(ctx)
        doneNote(ctx, r.tag)
        install(ctx, f)
    }

    /** 拉起系统安装器；没给「安装未知应用」权限时先跳设置页 */
    fun install(ctx: Context, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { L.e("更新：拉起安装器失败", it) }
    }

    // ---------------------------------------------------------------- 通知
    private fun nm(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "应用更新", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        return nm
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_update", true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private var noteText = Triple("", 0L, 0L)  // 来源, 已下, 总量
    private var noteBps = 0.0

    private fun cancelIntent(ctx: Context) = PendingIntent.getService(ctx, 1,
        Intent(ctx, UpdateService::class.java).setAction(UpdateService.ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** 进度通知（常驻、不可划掉），前台服务和进度刷新共用 */
    fun progressNotification(ctx: Context): android.app.Notification {
        nm(ctx)
        val tag = (state.value as? State.Downloading)?.r?.tag ?: ""
        val (src, got, total) = noteText
        val pct = if (total > 0) (got * 100 / total).toInt() else 0
        val text = if (got <= 0) "准备下载…" else "$pct% · ${fmtMb(got)}/${fmtMb(total)} · ${fmtSpeed(noteBps)}" + if (src.isNotEmpty()) " · $src" else ""
        return NotificationCompat.Builder(ctx, CH).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载 MicMask $tag").setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true).setSilent(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, pct, total <= 0 || got <= 0).setContentIntent(openApp(ctx))
            .addAction(0, "取消", cancelIntent(ctx)).build()
    }

    private fun progressNote(ctx: Context, @Suppress("UNUSED_PARAMETER") tag: String, got: Long, total: Long, src: String) = runCatching {
        noteText = Triple(src, got, total)
        noteBps = (state.value as? State.Downloading)?.bps ?: 0.0
        nm(ctx).notify(NID, progressNotification(ctx))
    }

    private fun fmtMb(b: Long) = String.format(java.util.Locale.US, "%.1fMB", b / 1048576.0)
    private fun fmtSpeed(bps: Double) = if (bps >= 1048576) String.format(java.util.Locale.US, "%.1fMB/s", bps / 1048576) else String.format(java.util.Locale.US, "%.0fKB/s", bps / 1024)

    private fun doneNote(ctx: Context, tag: String) = runCatching {
        nm(ctx).cancel(NID)
        nm(ctx).notify(NID_DONE, NotificationCompat.Builder(ctx, CH).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MicMask $tag 下载完成").setContentText("点击安装").setAutoCancel(true).setContentIntent(openApp(ctx)).build())
    }

    private fun failNote(ctx: Context, tag: String, err: String) = runCatching {
        nm(ctx).notify(NID_DONE, NotificationCompat.Builder(ctx, CH).setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("MicMask $tag 下载失败").setContentText(err).setAutoCancel(true).setContentIntent(openApp(ctx)).build())
    }

    private fun cancelNote(ctx: Context) = runCatching { nm(ctx).cancel(NID) }
}
