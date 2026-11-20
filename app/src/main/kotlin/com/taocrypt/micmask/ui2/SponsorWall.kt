package com.taocrypt.micmask.ui2

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

/** 赞赏墙：读 GitHub Action 从爱发电生成的 sponsors.json，只显示「MicMask 赞助」方案的赞助者。 */
private data class Sponsor(val name: String, val avatar: String, val amount: Double, val remark: String, val expire: Long, val since: Long = 0, val last: Long = 0) {
    /** 旧名单没有 expire 字段时当作在期 */
    val active: Boolean get() = expire <= 0 || expire * 1000 > System.currentTimeMillis()
}
private data class SponsorDoc(val count: Int, val total: Double, val list: List<Sponsor>)
private const val KEY = "sponsors_json"
private val avatarCache = HashMap<String, ImageBitmap?>()

private fun parse(text: String): SponsorDoc? = runCatching {
    val j = JSONObject(text)
    val arr = j.optJSONArray("sponsors")
    val list = (0 until (arr?.length() ?: 0)).map { i ->
        val o = arr!!.getJSONObject(i)
        Sponsor(o.optString("name", "爱发电用户"), o.optString("avatar"), o.optDouble("amount", 0.0), o.optString("remark"), o.optLong("expire", 0), o.optLong("since", 0), o.optLong("last", 0))
    }
    SponsorDoc(j.optInt("count", list.size), j.optDouble("total", list.sumOf { it.amount }), list)
}.getOrNull()

private fun get(url: String, limit: Int = 2 shl 20): ByteArray? = runCatching {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 8000
        setRequestProperty("User-Agent", "MicMask/${BuildConfig.VERSION_NAME}")
    }
    if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }.takeIf { it.size <= limit }
}.getOrNull()

private suspend fun loadSponsors(ctx: Context): SponsorDoc? = withContext(Dispatchers.IO) {
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
    return SponsorDoc(list.size, list.sumOf { it.amount }, list)
}

/** 秒级时间戳 → 日期；0 表示旧名单没有该字段 */
private fun day(sec: Long, pat: String = "yyyy-MM-dd") = if (sec <= 0) "" else java.text.SimpleDateFormat(pat, java.util.Locale.getDefault()).format(java.util.Date(sec * 1000))
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
        if (d == null || d.list.isEmpty()) {
            Text(if (d == null) (if (failed) tr("名单暂时无法加载，稍后再看") else tr("名单加载中…")) else tr("还没有赞助者，成为第一位吧"),
                fontSize = 13.sp, color = summary, modifier = Modifier.padding(16.dp))
            return@Card
        }
        Text(tr("{0} 位赞助者 · 累计 {1}，谢谢你们！", d.count, yuan(d.total)), fontSize = 13.sp, color = summary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
        // 弹幕：三条轨道不同速度向左循环滚动，在期赞助者主题色高亮
        Danmaku(d.list)
        // 完整名单折叠：在期的在上，过期的进「历史赞助」
        val cur = d.list.filter { it.active }; val past = d.list.filterNot { it.active }
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
    // 长按任意轨道：按住期间全部加速；点一下某条轨道：该轨道停住（内容滞留），再点或 6 秒后继续
    var fast by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        byLane.forEachIndexed { l, items ->
            // 名单太少时重复填满一行，保证一直在滚
            val fill = generateSequence { items }.flatten().take(maxOf(items.size, 6)).toList()
            DanmakuLane(fill, speedDp = 26f + l * 9f, lead = (l * 28).dp, fast = fast, onFast = { fast = it })
        }
    }
}

@Composable
private fun DanmakuLane(items: List<Sponsor>, speedDp: Float, lead: androidx.compose.ui.unit.Dp, fast: Boolean, onFast: (Boolean) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapPx = with(density) { 12.dp.toPx() }
    val speedPx = with(density) { speedDp.dp.toPx() }
    var w by remember { mutableStateOf(0) }
    var x by remember { mutableStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var pauseAt by remember { mutableStateOf(0L) }
    val fastNow by androidx.compose.runtime.rememberUpdatedState(fast)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                val dt = if (last == 0L) 0f else (t - last) / 1e9f
                last = t
                if (paused && System.currentTimeMillis() - pauseAt > 6000) paused = false
                val period = w + gapPx
                if (!paused && period > gapPx) x = (x + dt * speedPx * (if (fastNow) 2f else 1f)) % period
            }
        }
    }
    Box(Modifier.fillMaxWidth().clipToBounds()
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { paused = !paused; pauseAt = System.currentTimeMillis() },
                onLongPress = { paused = false; onFast(true) },
                onPress = { tryAwaitRelease(); onFast(false) },
            )
        }) {
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
            .offset { androidx.compose.ui.unit.IntOffset(-x.toInt(), 0) },
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.onSizeChanged { w = it.width }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.width(lead))
                items.forEach { DanmakuChip(it) }
            }
            // 第二份接在后面，实现无缝循环
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.width(lead))
                items.forEach { DanmakuChip(it) }
            }
        }
    }
}

@Composable
private fun DanmakuChip(s: Sponsor) {
    var img by remember(s.avatar) { mutableStateOf(synchronized(avatarCache) { avatarCache[s.avatar] }) }
    LaunchedEffect(s.avatar) { if (img == null) img = loadAvatar(s.avatar) }
    val cs = MiuixTheme.colorScheme
    val bg = if (s.active) cs.primary.copy(alpha = 0.14f) else cs.secondaryContainer.copy(alpha = 0.6f)
    val fg = if (s.active) cs.primary else cs.onSurfaceVariantSummary
    Row(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(start = 3.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
            val b = img
            if (b != null) Image(b, null, Modifier.size(24.dp), contentScale = ContentScale.Crop)
            else Text(s.name.take(1), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))
        Text(s.name, fontSize = 13.sp, maxLines = 1, fontWeight = FontWeight.Medium, color = cs.onSurface)
        Text("  " + yuan(s.amount), fontSize = 12.sp, maxLines = 1, color = fg)
        if (s.last > 0) Text("  " + day(s.last, "MM-dd"), fontSize = 11.sp, maxLines = 1, color = cs.onSurfaceVariantSummary)
        if (s.remark.isNotBlank()) Text("  " + s.remark.take(16), fontSize = 12.sp, maxLines = 1, color = cs.onSurfaceVariantSummary)
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
            val dates = listOfNotNull(
                day(s.since).takeIf { it.isNotEmpty() }?.let { tr("首次 {0}", it) },
                day(s.last).takeIf { it.isNotEmpty() && s.last != s.since }?.let { tr("最近 {0}", it) },
                day(s.expire).takeIf { it.isNotEmpty() && s.active }?.let { tr("有效至 {0}", it) },
            )
            if (dates.isNotEmpty()) Text(dates.joinToString(" · "), fontSize = 11.sp, color = summary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(yuan(s.amount), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary)
    }
}
