package com.taocrypt.micmask.ui2

import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.zIndex
import com.taocrypt.micmask.tr

import com.taocrypt.micmask.Prefs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taocrypt.micmask.BuildConfig
import com.taocrypt.micmask.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat

/** 赞赏墙：读 Beacon 赞助接口（/v1/<app>/sponsors），失败时用 GitHub 上的 sponsors.json；格式相同。 */
internal data class SponsorPlan(val id: String, val title: String, val price: Double, val desc: String)

/** 缓存名单里的档位（Beacon 接口才有 plans；没有时返回空，由调用方用默认档位） */
internal fun cachedPlans(ctx: Context): List<SponsorPlan> = runCatching {
    val arr = JSONObject(Prefs.main(ctx).getString(KEY, null) ?: return emptyList()).optJSONArray("plans") ?: return emptyList()
    (0 until arr.length()).map { i -> arr.getJSONObject(i).let { SponsorPlan(it.optString("id"), it.optString("title"), it.optDouble("price", 0.0), it.optString("desc")) } }
        .filter { it.id.isNotBlank() }
}.getOrDefault(emptyList())

private data class Sponsor(val name: String, val avatar: String, val amount: Double, val remark: String, val expire: Long, val since: Long = 0, val last: Long = 0,
                           val contributor: Boolean = false, val commits: Int = 0) {
    /** 旧名单没有 expire 字段时当作在期 */
    val active: Boolean get() = expire <= 0 || expire * 1000 > System.currentTimeMillis()
}
private data class SponsorDoc(val count: Int, val total: Double, val list: List<Sponsor>, val contributors: List<Sponsor> = emptyList())

/** 头像：avatar 优先；没有时填了 QQ 号就用 QQ 官方头像接口 */
private fun avatarOf(o: JSONObject): String = o.optString("avatar").ifBlank {
    o.optString("qq").filter { it.isDigit() }.takeIf { it.length in 5..12 }?.let { "https://q1.qlogo.cn/g?b=qq&nk=$it&s=100" } ?: ""
}
private const val KEY = "sponsors_json"
private val avatarCache = HashMap<String, ImageBitmap?>()

private fun parse(text: String): SponsorDoc? = runCatching {
    val j = JSONObject(text)
    val arr = j.optJSONArray("sponsors")
    val all = (0 until (arr?.length() ?: 0)).map { i ->
        val o = arr!!.getJSONObject(i)
        Sponsor(o.optString("name", "爱发电用户"), avatarOf(o), o.optDouble("amount", 0.0), o.optString("remark"), o.optLong("expire", 0), o.optLong("since", 0), o.optLong("last", 0),
            contributor = o.optString("kind") == "contributor")
    }
    val list = all.filterNot { it.contributor }
    // 贡献者：sponsors 里 kind=contributor 的（后台手动加）+ contributors 数组（后台从 GitHub 拉取并缓存），按名字去重
    val ca = j.optJSONArray("contributors")
    val cons = (all.filter { it.contributor } + (0 until (ca?.length() ?: 0)).map { i ->
        val o = ca!!.getJSONObject(i)
        Sponsor(o.optString("name").ifBlank { o.optString("login") }, avatarOf(o).ifBlank { o.optString("avatar_url") }, 0.0, o.optString("remark"), 0,
            contributor = true, commits = o.optInt("contributions", 0))
    }).filter { it.name.isNotBlank() }.distinctBy { it.name.lowercase() }
    SponsorDoc(j.optInt("count", list.size), j.optDouble("total", list.sumOf { it.amount }), list, cons)
}.getOrNull()

private fun get(url: String, limit: Int = 2 shl 20): ByteArray? = runCatching {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 8000
        setRequestProperty("User-Agent", "MicMask/${BuildConfig.VERSION_NAME}")
    }
    if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }.takeIf { it.size <= limit }
}.getOrNull()

/** Beacon SDK 的赞赏墙 → 本地缓存格式（与 GitHub sponsors.json 同构，parse 可读） */
private fun wallJson(w: com.taocrypt.beacon.SponsorWall): String {
    val j = JSONObject().put("count", w.count).put("total", w.total)
    j.put("plans", org.json.JSONArray().apply { w.plans.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("price", it.price).put("desc", it.desc)) } })
    j.put("sponsors", org.json.JSONArray().apply { w.sponsors.forEach { e ->
        put(JSONObject().put("kind", e.kind).put("name", e.name).put("avatar", e.avatar).put("qq", e.qq ?: "").put("remark", e.remark ?: "")
            .put("amount", e.amount ?: 0.0).put("since", e.since).put("last", e.last ?: 0L).put("expire", e.expire ?: 0L))
    } })
    j.put("contributors", org.json.JSONArray().apply { w.contributors.forEach { c ->
        put(JSONObject().put("login", c.login).put("name", c.name).put("avatar_url", c.avatarUrl).put("html_url", c.htmlUrl)
            .put("contributions", c.contributions).put("remark", c.remark ?: ""))
    } })
    return j.toString()
}

private suspend fun loadSponsors(ctx: Context): SponsorDoc? {
    // Beacon SDK 优先（实时），GitHub Action 生成的 sponsors.json 兜底
    if (com.taocrypt.micmask.BeaconHub.enabled) {
        val w = kotlinx.coroutines.suspendCancellableCoroutine<com.taocrypt.beacon.SponsorWall?> { c -> com.taocrypt.beacon.Beacon.fetchSponsors { if (c.isActive) c.resumeWith(Result.success(it)) } }
        if (w != null) {
            val t = wallJson(w)
            parse(t)?.let { Prefs.main(ctx).edit().putString(KEY, t).apply(); return it }
        }
    }
    return withContext(Dispatchers.IO) {
        for (u in AboutLinks.SPONSORS) {
            val b = get(u) ?: continue
            val t = String(b, Charsets.UTF_8)
            val d = parse(t) ?: continue
            Prefs.main(ctx).edit().putString(KEY, t).apply()
            return@withContext d
        }
        L.w("赞赏墙：名单拉取失败，用缓存")
        null
    }
}

private suspend fun loadAvatar(url: String): ImageBitmap? {
    if (url.isBlank()) return null
    synchronized(avatarCache) { if (avatarCache.containsKey(url)) return avatarCache[url] }
    val bmp = withContext(Dispatchers.IO) {
        get(url, 4 shl 20)?.let { b ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            var s = 1; while (o.outWidth / (s * 2) >= 96 && o.outHeight / (s * 2) >= 96) s *= 2
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 })
                ?.asImageBitmap()
        }
    }
    synchronized(avatarCache) { avatarCache[url] = bmp }
    return bmp
}


/** 预览用示例名单（只在本机显示，不联网、不写缓存）：赞赏墙卡片长按切换 */
private fun demoDoc(): SponsorDoc {
    val now = System.currentTimeMillis() / 1000
    val names = listOf("小鹿乱撞", "夜猫子阿杰", "Kirito", "柠檬不酸", "momo", "一只咸鱼", "星河长明", "阿乌", "Lumi", "南瓜粥",
        "今天也要早睡", "风铃", "Echo", "芝士奶盖", "老王隔壁的", "Neko", "白开水", "七月", "Rain", "橘子汽水",
        "不吃香菜", "阿狸", "Moonlight", "冰美式", "鱼丸粗面", "Sakura", "晚风", "十一", "Pixel", "蓝莓派", "云朵", "Tofu")
    val remarks = listOf("开黑必备，支持！", "亚托莉太可爱了", "", "希望出更多男声", "", "加油作者", "用来整蛊室友哈哈", "", "催更 iOS 版", "")
    val list = names.mapIndexed { i, n ->
        val amt = listOf(200.0, 128.0, 66.6, 50.0, 30.0, 20.0, 18.8, 10.0, 6.0, 5.0)[i % 10] * (if (i < 3) 2 else 1)
        Sponsor(n, "", amt, remarks[i % remarks.size], if (i < 7) now + 86400L * (30 - i) else now - 86400L * (i + 3),
            since = now - 86400L * (40 + i * 5), last = if (i < 7) now - 86400L * i else now - 86400L * (i + 33))
    }.sortedByDescending { it.amount }
    val cons = listOf("Taocrypt" to "维护者", "Kirito" to "修了一个注入 bug", "Lumi" to "英文翻译").map { (n, r) -> Sponsor(n, "", 0.0, r, 0, contributor = true) }
    return SponsorDoc(list.size, list.sumOf { it.amount }, list, cons)
}

/** 秒级时间戳 → 日期；0 表示旧名单没有该字段 */
private fun day(sec: Long, pat: String = "yyyy-MM-dd") = if (sec <= 0) "" else java.text.SimpleDateFormat(pat, java.util.Locale.getDefault()).format(java.util.Date(sec * 1000))
/** 按爱发电档位（累计金额）分色：1月¥5 / 3月¥15 / 半年¥28.2 / 1年¥54 / 2年¥103.2 / 3年¥151.2 */
private fun tierColor(amount: Double): androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(when {
    amount >= 151.2 -> 0xFFE0A030   // 3 年：金
    amount >= 103.2 -> 0xFFE0607A   // 2 年：玫红
    amount >= 54.0 -> 0xFF9A6CE0    // 1 年：紫
    amount >= 28.2 -> 0xFF4C8DF0    // 半年：蓝
    amount >= 15.0 -> 0xFF2FB39A    // 3 个月：青
    else -> 0xFF8A94A6              // 1 个月：灰蓝
})
private fun yuan(v: Double) = "¥" + DecimalFormat("#,##0.##").format(v)

@Composable
fun SponsorWall(header: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    var doc by remember { mutableStateOf(Prefs.main(ctx).getString(KEY, null)?.let(::parse)) }
    var showAll by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { val d = loadSponsors(ctx); if (d != null) doc = d else failed = true }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary

    var demo by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        .pointerInput(Unit) { detectTapGestures(onLongPress = {
            demo = !demo
            android.widget.Toast.makeText(ctx, if (demo) tr("预览示例名单（仅本机，非真实赞助）") else tr("已退出示例名单"), android.widget.Toast.LENGTH_SHORT).show()
        }) }) {
        header()
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(summary.copy(alpha = 0.25f)))
        val d = if (demo) demoDoc() else doc
        if (demo) Text(tr("示例数据，仅预览显示效果 · 长按退出"), fontSize = 12.sp, color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        var tab by remember { mutableStateOf(0) }   // 0 赞助者 1 贡献者
        if (d != null && d.contributors.isNotEmpty()) Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(tr("赞助者"), tr("贡献者")).forEachIndexed { i, t ->
                val on = (if (d.contributors.isEmpty()) 0 else tab) == i
                Text(t, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MiuixTheme.colorScheme.onPrimary else summary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)
                        .clickable { tab = i }.padding(horizontal = 14.dp, vertical = 5.dp))
            }
        }
        if (d != null && tab == 1 && d.contributors.isNotEmpty()) {
            Text(tr("{0} 位贡献者，谢谢你们！", d.contributors.size), fontSize = 13.sp, color = summary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
            Danmaku(d.contributors)
            Collapsible(tr("全部贡献者 {0} 位", d.contributors.size)) {
                Column { d.contributors.forEachIndexed { i, s -> SponsorRow(99, s) }; Spacer(Modifier.size(8.dp)) }
            }
            return@Card
        }
        if (d == null || d.list.isEmpty()) {
            Text(if (d == null) (if (failed) tr("名单暂时无法加载，稍后再看") else tr("名单加载中…")) else tr("还没有赞助者，成为第一位吧"),
                fontSize = 13.sp, color = summary, modifier = Modifier.padding(16.dp))
            return@Card
        }
        Text(tr("{0} 位赞助者 · 累计 {1}，谢谢你们！", d.count, yuan(d.total)), fontSize = 13.sp, color = summary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
        // 完整名单：在期的在上，过期的进「历史赞助」，各自按金额从高到低
        val cur = d.list.filter { it.active }.sortedByDescending { it.amount }
        val past = d.list.filterNot { it.active }.sortedByDescending { it.amount }
        // 弹幕：只放在期赞助者，三条轨道不同速度向左循环，底色按赞助档位区分
        // 弹幕顺序：最近赞助的在前（按最近一次赞助日期从新到旧）
        if (cur.isNotEmpty()) Danmaku(cur.sortedByDescending { if (it.last > 0) it.last else it.since })
        else Text(tr("暂时没有在期的赞助"), fontSize = 13.sp, color = summary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        Collapsible(tr("当前 {0} 位 · 历史 {1} 位", cur.size, past.size)) {
            @Composable fun head(t: String) = Text(t, fontSize = 12.sp, color = summary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp))
            Column {
                head(tr("当前赞助 {0}", cur.size))
                if (cur.isEmpty()) Text(tr("暂时没有在期的赞助"), fontSize = 13.sp, color = summary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                cur.forEachIndexed { i, s -> SponsorRow(i + 1, s) }
                if (past.isNotEmpty()) {
                    head(tr("历史赞助 {0}", past.size))
                    val shown = if (showAll) past else past.take(20)
                    shown.forEach { s -> SponsorRow(99, s) }
                    if (past.size > 20) TextButton(text = if (showAll) tr("收起") else tr("查看全部 {0} 位", past.size), onClick = { showAll = !showAll },
                        modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

@Composable
private fun Danmaku(list: List<Sponsor>) {
    val lanes = 3
    val byLane = List(lanes) { l -> list.filterIndexed { i, _ -> i % lanes == l } }.filter { it.isNotEmpty() }
    // 长按任意轨道：按住期间全部 5 倍速；点一条弹幕：只有这一条停住，再点或 5 秒后继续
    var fast by remember { mutableStateOf(false) }
    // 每条轨道当前滞留几条；只要有一条滞留，其他弹幕全部盖上灰色遮罩
    val held = remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    val dim = held.values.sum() > 0
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        byLane.forEachIndexed { l, items ->
            DanmakuLane(items, speedDp = 26f + l * 9f, lead = (l * 28).dp, fast = fast, onFast = { fast = it },
                dim = dim, onHeld = { held[l] = it })
        }
    }
}

/** 一条弹幕：x 为左边缘（px），w 为测得宽度，paused 时原地滞留 */
private class Bullet(val id: Long, val s: Sponsor, x0: Float) {
    var x by mutableStateOf(x0)
    var w = 0
    var paused by mutableStateOf(false)
    var pauseAt = 0L
}

@Composable
private fun DanmakuLane(items: List<Sponsor>, speedDp: Float, lead: androidx.compose.ui.unit.Dp, fast: Boolean, onFast: (Boolean) -> Unit,
                         dim: Boolean, onHeld: (Int) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapPx = with(density) { 12.dp.toPx() }
    val speedPx = with(density) { speedDp.dp.toPx() }
    val leadPx = with(density) { lead.toPx() }
    var laneW by remember { mutableStateOf(0) }
    val bullets = remember { androidx.compose.runtime.mutableStateListOf<Bullet>() }
    val fastNow by androidx.compose.runtime.rememberUpdatedState(fast)
    val onHeldNow by androidx.compose.runtime.rememberUpdatedState(onHeld)
    val maskA by androidx.compose.animation.core.animateFloatAsState(if (dim) 0.72f else 0f, label = "dim")
    val maskC = MiuixTheme.colorScheme.surface
    LaunchedEffect(items) {
        bullets.clear()
        var next = 0          // 下一条要发的名单下标（循环）
        var seq = 0L
        var tail: Float? = leadPx   // 下一条的起点，跟着轨道一起左移；null = 最新一条还没测出宽度
        var last = 0L
        var heldNow = 0
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                val dt = if (last == 0L) 0f else (t - last) / 1e9f
                last = t
                val dx = dt * speedPx * (if (fastNow) 5f else 1f)
                val now = System.currentTimeMillis()
                for (b in bullets) {
                    if (b.paused && now - b.pauseAt > 5000) b.paused = false
                    if (!b.paused) b.x -= dx
                }
                bullets.removeAll { !it.paused && it.w > 0 && it.x + it.w < 0 }
                val h = bullets.count { it.paused }
                if (h != heldNow) { heldNow = h; onHeldNow(h) }
                if (tail == null) {
                    val nb = bullets.lastOrNull()
                    if (nb == null) tail = laneW.toFloat()
                    // 条数少时拉开间距，不要紧挨着滚：每条之间至少留出 轨道宽 / 条数 的一半
                    else if (nb.w > 0) tail = maxOf(nb.x, -1e6f) + nb.w + maxOf(gapPx, if (items.size < 4) laneW * 0.5f / items.size else 0f)
                } else tail = tail!! - dx
                val tl = tail
                if (laneW > 0 && tl != null && tl < laneW) {
                    bullets.add(Bullet(seq++, items[next % items.size], tl))
                    next++
                    tail = null
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(if (items.any { twoLine(it) }) 50.dp else 33.dp).clipToBounds()
        .onSizeChanged { laneW = it.width }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { p ->
                    // 只停被点到的那一条（滞留的优先，它画在最上层）
                    val hit = bullets.lastOrNull { it.paused && p.x >= it.x && p.x <= it.x + it.w }
                        ?: bullets.lastOrNull { p.x >= it.x && p.x <= it.x + it.w }
                    if (hit != null) { hit.paused = !hit.paused; hit.pauseAt = System.currentTimeMillis() }
                },
                onLongPress = { onFast(true) },
                onPress = { tryAwaitRelease(); onFast(false) },
            )
        }) {
        for (b in bullets) {
            androidx.compose.runtime.key(b.id) {
                Box(Modifier.align(Alignment.CenterStart).wrapContentWidth(Alignment.Start, unbounded = true)
                    .zIndex(if (b.paused) 1f else 0f)
                    .offset { androidx.compose.ui.unit.IntOffset(b.x.toInt(), 0) }
                    .onSizeChanged { b.w = it.width }) { DanmakuChip(b.s, held = b.paused) }
            }
        }
        // 灰度遮罩：盖在流动弹幕上、滞留弹幕下
        if (maskA > 0f) Box(Modifier.matchParentSize().zIndex(0.5f).background(maskC.copy(alpha = maskA)))
    }
}

/** 留言超过 12 字时弹幕改双行 */
private fun twoLine(s: Sponsor) = s.remark.trim().length > 12

@Composable
private fun DanmakuChip(s: Sponsor, held: Boolean = false) {
    var img by remember(s.avatar) { mutableStateOf(synchronized(avatarCache) { avatarCache[s.avatar] }) }
    LaunchedEffect(s.avatar) { if (img == null) img = loadAvatar(s.avatar) }
    val cs = MiuixTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val tc = if (s.contributor) cs.primary else tierColor(s.amount)
    val bg = androidx.compose.ui.graphics.lerp(cs.surface, tc, if (dark) 0.26f else 0.16f)
    val fg = androidx.compose.ui.graphics.lerp(tc, if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black, if (dark) 0.2f else 0.2f)
    // 滞留时突出：略放大、档位色描边、底色加深
    val k by androidx.compose.animation.core.animateFloatAsState(if (held) 1f else 0f, label = "held")
    val two = twoLine(s)
    val shape = RoundedCornerShape(if (two) 22.dp else 50.dp)
    val av = if (two) 34.dp else 24.dp
    Row(Modifier
        .graphicsLayer { val sc = 1f + 0.06f * k; scaleX = sc; scaleY = sc }
        .clip(shape)
        .background(androidx.compose.ui.graphics.lerp(bg, androidx.compose.ui.graphics.lerp(cs.surface, tc, if (dark) 0.42f else 0.30f), k))
        .border((1.5f * k).dp, tc.copy(alpha = k), shape)
        .padding(start = 3.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(av).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
            val b = img
            if (b != null) Image(b, null, Modifier.size(av), contentScale = ContentScale.Crop)
            else Text(s.name.take(1), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))
        @Composable fun head() {
            Text(s.name, fontSize = 13.sp, maxLines = 1, fontWeight = FontWeight.Medium, color = cs.onSurface)
            if (!s.contributor) Text("  " + yuan(s.amount), fontSize = 12.sp, maxLines = 1, color = fg)
            if (!s.contributor && s.last > 0) Text("  " + day(s.last, "MM-dd"), fontSize = 11.sp, maxLines = 1, color = cs.onSurfaceVariantSummary)
        }
        if (two) Column {
            Row(verticalAlignment = Alignment.CenterVertically) { head() }
            // 长留言换到第二行，最多 30 字，宽度不超过 260dp
            Text(s.remark.take(30), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = cs.onSurfaceVariantSummary, modifier = Modifier.widthIn(max = 260.dp))
        } else {
            head()
            if (s.remark.isNotBlank()) Text("  " + s.remark, fontSize = 12.sp, maxLines = 1, color = cs.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun SponsorRow(rank: Int, s: Sponsor) {
    var img by remember(s.avatar) { mutableStateOf(synchronized(avatarCache) { avatarCache[s.avatar] }) }
    LaunchedEffect(s.avatar) { if (img == null) img = loadAvatar(s.avatar) }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(MiuixTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            val b = img
            if (b != null) Image(b, null, Modifier.size(38.dp), contentScale = ContentScale.Crop)
            else Text(s.name.take(1), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (rank <= 3) FontWeight.Bold else FontWeight.Normal)
            if (s.remark.isNotBlank()) Text(s.remark, fontSize = 12.sp, color = summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val dates = if (s.contributor) listOfNotNull(s.commits.takeIf { it > 0 }?.let { tr("提交 {0} 次", it) }) else listOfNotNull(
                day(s.since, "yy-MM-dd").takeIf { it.isNotEmpty() }?.let { tr("首次 {0}", it) },
                day(s.last, "yy-MM-dd").takeIf { it.isNotEmpty() && s.last != s.since }?.let { tr("最近 {0}", it) },
                day(s.expire, "yy-MM-dd").takeIf { it.isNotEmpty() && s.active }?.let { tr("有效至 {0}", it) },
            )
            // 日期一行放不下时可左右滑动
            if (dates.isNotEmpty()) Text(dates.joinToString(" · "), fontSize = 11.sp, color = summary, maxLines = 1, softWrap = false,
                modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()))
        }
        Spacer(Modifier.width(8.dp))
        if (!s.contributor) Text(yuan(s.amount), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (s.active) tierColor(s.amount) else summary)
    }
}
