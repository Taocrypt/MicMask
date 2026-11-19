package com.taocrypt.micmask.ui2

import com.taocrypt.micmask.Prefs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
private data class Sponsor(val name: String, val avatar: String, val amount: Double, val remark: String)
private data class SponsorDoc(val count: Int, val total: Double, val list: List<Sponsor>)
private const val KEY = "sponsors_json"
private val avatarCache = HashMap<String, ImageBitmap?>()

private fun parse(text: String): SponsorDoc? = runCatching {
    val j = JSONObject(text)
    val arr = j.optJSONArray("sponsors")
    val list = (0 until (arr?.length() ?: 0)).map { i ->
        val o = arr!!.getJSONObject(i)
        Sponsor(o.optString("name", "爱发电用户"), o.optString("avatar"), o.optDouble("amount", 0.0), o.optString("remark"))
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

private fun yuan(v: Double) = "¥" + DecimalFormat("#,##0.##").format(v)

@Composable
fun SponsorWall() {
    val ctx = LocalContext.current
    var doc by remember { mutableStateOf(Prefs.main(ctx).getString(KEY, null)?.let(::parse)) }
    var showAll by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { val d = loadSponsors(ctx); if (d != null) doc = d else failed = true }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary

    SmallTitle("赞赏墙")
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val d = doc
        if (d == null || d.list.isEmpty()) {
            Text(if (d == null) (if (failed) "名单暂时无法加载，稍后再看" else "名单加载中…") else "还没有赞助者，成为第一位吧",
                fontSize = 13.sp, color = summary, modifier = Modifier.padding(16.dp))
            return@Card
        }
        Text("${d.count} 位赞助者 · 累计 ${yuan(d.total)}，谢谢你们！", fontSize = 13.sp, color = summary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
        val shown = if (showAll) d.list else d.list.take(20)
        shown.forEachIndexed { i, s -> SponsorRow(i + 1, s) }
        if (d.list.size > 20) {
            TextButton(text = if (showAll) "收起" else "查看全部 ${d.list.size} 位", onClick = { showAll = !showAll },
                modifier = Modifier.fillMaxWidth().padding(12.dp))
        } else Spacer(Modifier.size(8.dp))
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
        }
        Spacer(Modifier.width(8.dp))
        Text(yuan(s.amount), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary)
    }
}
