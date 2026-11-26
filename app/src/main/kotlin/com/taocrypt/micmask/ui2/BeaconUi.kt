package com.taocrypt.micmask.ui2

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taocrypt.beacon.Notice
import com.taocrypt.beacon.Slot
import com.taocrypt.micmask.BeaconHub
import com.taocrypt.micmask.BuildConfig
import com.taocrypt.micmask.L
import com.taocrypt.micmask.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.net.HttpURLConnection
import java.net.URL

/*
 * Beacon 相关界面：首启同意弹窗、dialog 公告、横幅、推荐位卡片、反馈页、隐私说明页。
 * BeaconHub.enabled=false（源码 / 本地构建）时：同意弹窗只剩免责声明，公告/横幅/推荐位不显示，反馈页换成 QQ 群与 GitHub Issues。
 */


// ------------------------------------------------------------------ 说明文字（同意弹窗与隐私页共用）

/** 信息收集说明：标题 to 正文 */
internal fun privacySections(): List<Pair<String, String>> = listOf(
    tr("基础统计（使用即开启）") to tr("• 启动次数（用于统计活跃用户数）\n• 公告与推荐位的曝光、点击\n• App 版本号、系统语言、随机生成的安装 ID\n不含手机型号、系统版本与功能使用情况。"),
    tr("匿名统计（可选）还会收集") to tr("• App 版本号、手机型号与系统版本、处理器型号（SoC）与核数\n• 功能使用事件，如开始/停止 AI 变声（ai_start / ai_stop）、切换音色、应用预设、文件变声、文字转语音，以及时长、档位、推理耗时与负载等数值（用来定各机型的默认档位）\n• 崩溃时的异常类名与本应用的几行栈帧\n• 随机生成的安装 ID（与你的身份无关，卸载重装会变）"),
    tr("不会收集") to tr("• 录音、音频内容和变声后的声音\n• 音色模型、文字转语音输入的文字\n• 账号、手机号、联系人、短信、精确位置等个人信息"),
    tr("其他联网") to tr("获取公告与推荐时会发送版本号、系统语言与安装 ID；主动提交反馈时，会上传你填写的内容、联系方式，以及你选择附带的日志与截图。从 App 内跳转爱发电赞赏时，下单链接会带上安装 ID，用来关联赞助、关闭推广；安装 ID 不会显示在赞赏墙上。"),
    tr("你可以随时") to tr("在 关于 → 其他 中关闭匿名统计。同意弹窗里「同意匿名统计」默认已勾选，取消勾选则只保留基础统计。"),
)

/** 免责声明要点（完整版见 关于 → 免责声明） */
internal fun disclaimerPoints(): String = tr("• MicMask 仅供娱乐和学习交流使用\n• 禁止用于诈骗、冒充他人身份、骚扰，或侵犯他人声音权、肖像权等其他违法用途\n• 第三方音色模型、声音与素材的版权归原作者/权利人所有，使用前请确认已获授权\n• 因使用本软件产生的一切后果由使用者自行承担，作者与贡献者不承担责任")

// ------------------------------------------------------------------ 简单 markdown：# 标题、- 列表、**粗体**、[文字](链接)

private val inlineMd = Regex("""\*\*(.+?)\*\*|\[([^\]]+)]\((https?://[^)\s]+)\)""")

@Composable
private fun md(src: String): AnnotatedString {
    val link = TextLinkStyles(SpanStyle(color = MiuixTheme.colorScheme.primary))
    return remember(src, link) {
        buildAnnotatedString {
            src.lines().forEachIndexed { i, raw ->
                if (i > 0) append('\n')
                var l = raw.trimEnd()
                val head = l.startsWith("#")
                when {
                    head -> l = l.trimStart('#').trim()
                    l.trimStart().startsWith("- ") || l.trimStart().startsWith("* ") -> l = "• " + l.trimStart().drop(2)
                }
                val start = length
                var pos = 0
                for (m in inlineMd.findAll(l)) {
                    append(l.substring(pos, m.range.first))
                    val bold = m.groups[1]?.value
                    if (bold != null) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
                    else withLink(LinkAnnotation.Url(m.groupValues[3], link)) { append(m.groupValues[2]) }
                    pos = m.range.last + 1
                }
                append(l.substring(pos))
                if (head) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
            }
        }
    }
}

// ------------------------------------------------------------------ 首启同意弹窗

/**
 * 首次启动（或 CONSENT_VERSION 提升后）的同意弹窗。[show] 由 V2App 控制：导入弹窗、更新弹窗都结束后才为 true。
 * 「同意并继续」→ acceptConsent(勾选状态)；「不同意」→ 退出 App。
 */
@Composable
fun BeaconConsentDialog(show: Boolean, onDone: () -> Unit) {
    if (!show) return
    val ctx = LocalContext.current
    val beacon = BeaconHub.enabled
    var stats by remember { mutableStateOf(true) }  // 首次默认勾选（alpha94），可取消
    WindowDialog(show = true, title = if (beacon) tr("使用前请阅读") else tr("免责声明"),
        onDismissRequest = {}) {
        Column {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (beacon) {
                    Text(tr("信息收集说明"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    privacySections().forEach { (t, b) ->
                        Text(t, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        Text(b, fontSize = 13.sp, lineHeight = 19.sp, color = Dim)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(tr("软件免责声明"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                }
                Text(disclaimerPoints(), fontSize = 13.sp, lineHeight = 19.sp)
                Text(tr("完整内容见 设置 → 关于 MicMask → 免责声明。"), fontSize = 12.sp, color = Dim, modifier = Modifier.padding(top = 8.dp))
            }
            if (beacon) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { stats = !stats }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state = ToggleableState(stats), onClick = { stats = !stats })
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("同意匿名统计"), fontSize = 14.sp)
                        Text(tr("可选，帮助改进兼容性；取消勾选也能正常使用，只保留基础统计"), fontSize = 11.sp, color = Dim)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(tr("不同意"), onClick = { (ctx as? Activity)?.finishAffinity() }, modifier = Modifier.weight(1f))
                TextButton(tr("同意并继续"), onClick = { BeaconHub.acceptConsent(ctx, stats && beacon); onDone() },
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

// ------------------------------------------------------------------ dialog 公告

/** 公告类型标签：normal 不显示；important 重要公告 / risk 风险提醒 / event 活动通知（颜色同 SDK 自带弹窗） */
@Composable
private fun NoticeKindTag(n: Notice, modifier: Modifier = Modifier) {
    val (label, color) = when (n.kind) {
        "important" -> tr("重要公告") to Color(0xFF2F6FEB)
        "risk" -> tr("风险提醒") to Color(0xFFD1242F)
        "event" -> tr("活动通知") to Color(0xFFBF8700)
        else -> return
    }
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color,
        modifier = modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
}

/**
 * 公告头图：加载成功才占位，宽度铺满，按原图比例（限制在 [minRatio]:1 ~ 3:1 之间），圆角裁切。
 * 不要再叠 heightIn：和 aspectRatio 冲突时图片会画出自身边界、盖住标题和正文（alpha93 横幅的问题）。
 */
@Composable
private fun NoticeHeaderImage(n: Notice, modifier: Modifier = Modifier, minRatio: Float = 1f) {
    val img = rememberBeaconImage(n.image, header = true) ?: return
    val ratio = (img.width.toFloat() / img.height.coerceAtLeast(1)).coerceIn(minRatio, 3f)
    Box(modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp))) {
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** 弹窗正文：类型标签 + 头图 + Markdown 正文（可滚动） */
@Composable
private fun NoticeBody(n: Notice) {
    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        if (n.kind in KINDS) { NoticeKindTag(n); Spacer(Modifier.height(10.dp)) }
        NoticeHeaderImage(n, Modifier.padding(bottom = 12.dp))
        if (n.body.isNotBlank()) Text(md(n.body), fontSize = 14.sp, lineHeight = 21.sp)
    }
    Spacer(Modifier.height(12.dp))
}

private val KINDS = setOf("important", "risk", "event")

/**
 * dialog 公告逐条弹出（强制阅读 ack 排最前）；[blocked]（变声中、同意弹窗/更新弹窗/导入中、阻断公告显示中）时不弹。
 * - 普通：可关闭；按钮点了也关闭。
 * - force=ack 强制阅读：不能点外部/返回关闭；倒计时 ackSeconds 结束后第一个按钮（缺省「我已阅读」）才可点，
 *   点了 markNoticeAcked（同一 rev 不再弹，后台改内容会重新要求确认）并打开它的链接；其余按钮只打开链接、不关闭。
 *   变声中同普通公告一样等停止后再弹（不打断通话）；被打断后再弹时倒计时重新开始。
 */
@Composable
fun BeaconNoticeDialogs(blocked: Boolean) {
    val ctx = LocalContext.current
    val n = BeaconHub.dialogQueue.firstOrNull()
    if (blocked || n == null) return
    LaunchedEffect(n.id) { BeaconHub.noticeShown(n) }
    val ack = n.force == "ack"
    // 「不再提示」：只给会重复弹的普通公告（daily / every_launch）；勾上后关闭时记下，同一版本不再弹
    val canMute = !ack && n.frequency != "once"
    var mute by remember(n.id) { mutableStateOf(false) }
    val close = {
        if (canMute && mute) BeaconHub.noticeMuted(n)
        if (BeaconHub.dialogQueue.isNotEmpty()) BeaconHub.dialogQueue.removeAt(0); Unit
    }
    var left by remember(n.id) { mutableStateOf(if (ack) n.ackSeconds.coerceIn(0, 60) else 0) }
    if (ack) LaunchedEffect(n.id) { while (left > 0) { kotlinx.coroutines.delay(1000); left-- } }
    WindowDialog(show = true, title = n.title.ifBlank { tr("公告") }, onDismissRequest = { if (!ack) close() }) {
        Column {
            NoticeBody(n)
            if (ack) {
                val first = n.buttons.firstOrNull()
                val okText = first?.text?.ifBlank { null } ?: tr("我已阅读")
                n.buttons.drop(1).take(2).forEach { b ->
                    TextButton(b.text.ifBlank { tr("打开") }, onClick = { BeaconHub.noticeClick(n, b.text); b.url?.let { openUrl(ctx, it) } },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                }
                TextButton(if (left > 0) "$okText（$left）" else okText, enabled = left <= 0, onClick = {
                    BeaconHub.noticeAcked(n)
                    first?.let { b -> BeaconHub.noticeClick(n, b.text); b.url?.let { openUrl(ctx, it) } }
                    close()
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
            } else {
                if (canMute) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { mute = !mute }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state = ToggleableState(mute), onClick = { mute = !mute })
                    Spacer(Modifier.width(8.dp))
                    Text(tr("不再提示"), fontSize = 14.sp, color = Dim)
                }
                NoticeButtons(n, primaryLast = true) { close() }
                if (n.buttons.isEmpty()) TextButton(tr("知道了"), onClick = { close() }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

/**
 * 阻断公告（force=block，停服维护等）：生效期间盖住整个 App，返回/点外部都关不掉；
 * 按钮只打开链接（弹窗留着），「重新检查」重新拉配置（后台下线/到期即自动消失），「退出」结束 App。
 * [blocked]：变声中（不打断正在进行的通话，停止后立即弹；期间 toggleLive 拒绝开始新的变声）、导入/更新弹窗时先不弹。
 */
@Composable
fun BeaconBlockDialog(blocked: Boolean): Boolean {
    val n = BeaconHub.blockingNotice() ?: return false
    if (blocked) return false
    val ctx = LocalContext.current
    LaunchedEffect(n.id) { BeaconHub.noticeShown(n) }
    WindowDialog(show = true, title = n.title.ifBlank { tr("暂停使用") }, onDismissRequest = {}) {
        Column {
            NoticeBody(n)
            n.buttons.filter { it.url != null }.take(2).forEach { b ->
                TextButton(b.text.ifBlank { tr("打开") }, onClick = { BeaconHub.noticeClick(n, b.text); openUrl(ctx, b.url!!) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(tr("重新检查"), onClick = { BeaconHub.fetch() }, modifier = Modifier.weight(1f))
                TextButton(tr("退出"), onClick = { (ctx as? Activity)?.finishAffinity() }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
    return true
}

@Composable
private fun NoticeButtons(n: Notice, primaryLast: Boolean, after: () -> Unit) {
    val ctx = LocalContext.current
    val btns = n.buttons.take(3)
    if (btns.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        btns.forEachIndexed { i, b ->
            TextButton(b.text.ifBlank { tr("打开") }, onClick = {
                BeaconHub.noticeClick(n, b.text)
                b.url?.let { openUrl(ctx, it) }
                after()
            }, modifier = Modifier.weight(1f),
                colors = if (primaryLast && i == btns.lastIndex) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
        }
    }
}

// ------------------------------------------------------------------ 横幅

/** 变声页顶部的横幅公告（style=banner 且非强制），可关闭（本次启动内不再显示）；有类型时标题前加标签，有头图时显示在正文上方 */
@Composable
fun BeaconBanners() {
    if (!BeaconHub.enabled) return
    BeaconHub.banners.toList().forEach { n ->
        LaunchedEffect(n.id) { BeaconHub.noticeShown(n) }
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (n.kind in KINDS) { NoticeKindTag(n); Spacer(Modifier.width(8.dp)) }
                    Text(n.title.ifBlank { tr("公告") }, fontWeight = FontWeight.Medium, fontSize = 15.sp, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { BeaconHub.banners.remove(n) }) {
                        Icon(MiuixIcons.Close, tr("关闭"), tint = Dim, modifier = Modifier.size(18.dp))
                    }
                }
                NoticeHeaderImage(n, Modifier.padding(end = 12.dp, bottom = 8.dp), minRatio = 2.2f)  // 横幅压成宽条
                if (n.body.isNotBlank()) Text(md(n.body), fontSize = 13.sp, lineHeight = 19.sp, color = Dim, modifier = Modifier.padding(end = 12.dp))
                if (n.buttons.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.padding(end = 12.dp)) { NoticeButtons(n, primaryLast = true) { BeaconHub.banners.remove(n) } }
                }
            }
        }
    }
}

/**
 * 推荐位 banner（alpha97）：外形同横幅公告，标题前固定「推广」标签，slot.image 作头图、subtitle 作正文；
 * 点卡片打开链接，关闭只隐藏这一条到当天 24:00；变声中隐藏。
 */
@Composable
fun BeaconSlotBanner(liveOn: Boolean) {
    val s = rememberSlot("banner", liveOn) ?: return
    val ctx = LocalContext.current
    val img = rememberBeaconImage(s.image, header = true)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp), onClick = { BeaconHub.openSlot(ctx, s) }) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val c = Color(0xFF8250DF)
                Text(tr("推广"), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = c,
                    modifier = Modifier.background(c.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.title, fontWeight = FontWeight.Medium, fontSize = 15.sp, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { BeaconHub.dismissSlotItem(s, "banner") }) {
                    Icon(MiuixIcons.Close, tr("关闭推荐"), tint = Dim, modifier = Modifier.size(18.dp))
                }
            }
            img?.let {
                val ratio = (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(2.2f, 3f)
                Box(Modifier.padding(end = 12.dp, bottom = 8.dp).fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp))) {
                    Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            if (s.subtitle.isNotBlank()) Text(s.subtitle, fontSize = 13.sp, lineHeight = 19.sp, color = Dim, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

// ------------------------------------------------------------------ 推荐位

/**
 * 推荐位图标 / 公告头图共用的图片缓存：LruCache 按字节计（最多 maxMemory/16），失败的地址本次启动不再重试。
 * 图片来自后台上传（/v1/img/<app>/<sha256>.<ext>，单张 ≤5MB）或任意 https 地址。
 */
private val imgCache = object : android.util.LruCache<String, ImageBitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 16).toInt()) {
    override fun sizeOf(key: String, value: ImageBitmap) = (value.width * value.height * (if (value.config == androidx.compose.ui.graphics.ImageBitmapConfig.Rgb565) 2 else 4)) / 1024
}
private val imgFailed = HashSet<String>()

private fun httpGet(url: String, limit: Int): ByteArray? = runCatching {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 15000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "MicMask/${BuildConfig.VERSION_NAME}")
    }
    try {
        if (c.responseCode != 200) null else c.inputStream.use { input ->
            // 边读边限长，超限直接放弃（不把超大文件整个读进内存）
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16384); var total = 0
            while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > limit) return@use null; out.write(buf, 0, n) }
            out.toByteArray()
        }
    } finally { c.disconnect() }
}.getOrNull()

/**
 * 按 SponsorWall 的写法：先读尺寸，再按 2 的幂降采样到不小于 [minSide]（短边）/ [maxW]（宽）；
 * [opaque]=true 用 RGB_565 省内存（小图标），头图保留 ARGB（PNG 透明）。
 */
private suspend fun loadBeaconImage(url: String, minSide: Int, maxW: Int, opaque: Boolean): ImageBitmap? {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return null
    val key = "$minSide|$maxW|$url"
    imgCache.get(key)?.let { return it }
    synchronized(imgFailed) { if (key in imgFailed) return null }
    val bmp = withContext(Dispatchers.IO) {
        httpGet(url, 5 shl 20)?.let { b ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return@let null
            var s = 1
            while (o.outWidth / (s * 2) >= maxW || (o.outWidth / (s * 2) >= minSide && o.outHeight / (s * 2) >= minSide)) s *= 2
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply {
                inSampleSize = s; inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            })?.asImageBitmap()
        }
    }
    if (bmp != null) imgCache.put(key, bmp) else synchronized(imgFailed) { imgFailed += key }
    return bmp
}

/** 推荐位图标：短边降到 ~160px */
private suspend fun loadSlotImage(url: String): ImageBitmap? = loadBeaconImage(url, 160, 4096, true)

@Composable
private fun rememberBeaconImage(url: String?, header: Boolean): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    val w = LocalContext.current.resources.displayMetrics.widthPixels.coerceIn(480, 1080)
    val key = if (header) "4096|$w|$url" else "160|4096|$url"
    var img by remember(url, header) { mutableStateOf(imgCache.get(key)) }
    LaunchedEffect(url, header) { if (img == null) img = if (header) loadBeaconImage(url, 4096, w, false) else loadSlotImage(url) }
    return img
}

/**
 * 推荐位公共规则（三种样式共用）：变声中（[liveOn]）隐藏；SDK 关闭、该位置已被关闭或没有内容时不显示；
 * 曝光与关闭的频控在 [BeaconHub.slot] / [BeaconHub.slotViewed] / [BeaconHub.dismissSlotItem]。
 * 不要放进悬浮窗、常驻通知、Shizuku 授权/权限引导、开屏/插屏。
 */
@Composable
private fun rememberSlot(position: String, liveOn: Boolean): Slot? {
    if (liveOn) return null
    val s = BeaconHub.slot(position) ?: return null
    LaunchedEffect(s.id, position) { BeaconHub.slotViewed(s, position) }
    return s
}

@Composable
private fun PromoTag(modifier: Modifier = Modifier) {
    Text(tr("推广"), fontSize = 10.sp, color = Dim,
        modifier = modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
private fun SlotClose(s: Slot, position: String) = SlotClose { BeaconHub.dismissSlotItem(s, position) }

@Composable
private fun SlotClose(onClose: () -> Unit) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        Icon(MiuixIcons.Close, tr("关闭推荐"), Modifier.size(16.dp), tint = Dim)
    }
}

@Composable
private fun slotImage(s: Slot): ImageBitmap? = rememberBeaconImage(s.image, header = false)

/**
 * 自营推荐位卡片。position：home_bottom / file_result / about / model_download。
 */
@Composable
fun BeaconSlotCard(position: String, liveOn: Boolean, modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
    val s = rememberSlot(position, liveOn) ?: return
    val ctx = LocalContext.current
    val img = slotImage(s)
    Card(modifier, onClick = { BeaconHub.openSlot(ctx, s) }) {
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                img?.let {
                    Image(it, null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f).padding(end = 64.dp)) {
                    Text(s.title, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.subtitle.isNotBlank()) Text(s.subtitle, fontSize = 12.sp, color = Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PromoTag()
                SlotClose(s, position)
            }
        }
    }
}

/** 音色仓库列表里的推荐位（repo_list）：外形同仓库音色项，标题旁带「推广」，右侧关闭 */
@Composable
fun BeaconSlotRepoItem(position: String, liveOn: Boolean, modifier: Modifier) {
    val s = rememberSlot(position, liveOn) ?: return
    SlotRepoItemBody(s, modifier) { BeaconHub.dismissSlotItem(s, position) }
}

/** 多条位置里的一条（alpha91 repo_list）：曝光按 日期|id|位置 去重，关闭只隐藏这一条当天 */
@Composable
fun BeaconSlotRepoItem(s: Slot, position: String, liveOn: Boolean, modifier: Modifier) {
    if (liveOn) return
    LaunchedEffect(s.id, position) { BeaconHub.slotViewed(s, position) }
    SlotRepoItemBody(s, modifier) { BeaconHub.dismissSlotItem(s, position) }
}

@Composable
private fun SlotRepoItemBody(s: Slot, modifier: Modifier, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val img = slotImage(s)
    Card(modifier, onClick = { BeaconHub.openSlot(ctx, s) }) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            img?.let {
                Image(it, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    PromoTag()
                }
                if (s.subtitle.isNotBlank()) Text(s.subtitle, fontSize = 13.sp, color = Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            SlotClose(onClose)
        }
    }
}

/** 一行文字链接（update：更新弹窗底部） */
@Composable
fun BeaconSlotLink(position: String, liveOn: Boolean, modifier: Modifier = Modifier.fillMaxWidth()) {
    val s = rememberSlot(position, liveOn) ?: return
    val ctx = LocalContext.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PromoTag()
        Spacer(Modifier.width(6.dp))
        Text(s.title, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { BeaconHub.openSlot(ctx, s) }.padding(vertical = 4.dp))
        SlotClose(s, position)
    }
}

// ------------------------------------------------------------------ 赞赏

/**
 * 点「赞赏支持」弹出：选档位跳爱发电（链接自动带本设备 ID，用户什么都不用填），
 * 或「已赞助？输入订单号」把网页付款 / 换机 / 重装后的设备绑上。
 * 只在 Beacon 启用时弹；本地构建直接打开爱发电。
 */
@Composable
fun SponsorDialog(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val plans = remember { cachedPlans(ctx) }
    var bindMode by remember { mutableStateOf(false) }
    var orderNo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val until = BeaconHub.sponsorUntil
    val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US) }
    WindowDialog(show = true, title = tr("赞赏支持"), onDismissRequest = onDismiss) {
        Column {
            if (until > System.currentTimeMillis()) Text(tr("感谢支持！本设备的推广已隐藏至 {0}。", fmt.format(java.util.Date(until))),
                fontSize = 14.sp, color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
            BeaconHub.deviceId?.let { id ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.secondaryContainer)
                    .clickable {
                        ctx.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText(tr("设备 ID"), id))
                        android.widget.Toast.makeText(ctx, tr("设备 ID 已复制"), android.widget.Toast.LENGTH_SHORT).show()
                    }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("设备 ID"), fontSize = 12.sp, color = Dim)
                        Text(id, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Text(tr("复制"), fontSize = 14.sp, color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (!bindMode) {
                Text(tr("从这里付款会自动关联本设备：名字上赞赏墙，赞助期内我所有 App 的推广都会关闭（付款后重启 App 生效）。留言随意写，不用填任何编号。"),
                    fontSize = 14.sp, lineHeight = 21.sp, color = Dim)
                Spacer(Modifier.height(12.dp))
                val list = plans.ifEmpty { listOf(SponsorPlan(AboutLinks.DONATE_PLAN, tr("每月赞助"), 5.0, "")) }
                list.forEach { p ->
                    TextButton(if (p.price > 0) "${p.title} · ¥${java.text.DecimalFormat("0.##").format(p.price)}" else p.title,
                        onClick = { onDismiss(); openUrl(ctx, BeaconHub.donateUrl(p.id)) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = ButtonDefaults.textButtonColorsPrimary())
                }
                TextButton(tr("自选金额"), onClick = { onDismiss(); openUrl(ctx, BeaconHub.donateUrl(null)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                TextButton(tr("已赞助？输入订单号"), onClick = { bindMode = true; msg = null }, modifier = Modifier.fillMaxWidth())
            } else {
                Text(tr("在网页上付款、换了手机或重装后，在爱发电「我的订单」里复制订单号填到这里。每位赞助者最多同时关联 3 台设备，新关联的会替换最早那台。"),
                    fontSize = 14.sp, lineHeight = 21.sp, color = Dim)
                Spacer(Modifier.height(12.dp))
                TextField(value = orderNo, onValueChange = { v -> orderNo = v.filter { it.isLetterOrDigit() }.take(40) }, label = tr("爱发电订单号"),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                msg?.let { Text(it, fontSize = 13.sp, color = Dim, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(tr("返回"), onClick = { bindMode = false; msg = null }, modifier = Modifier.weight(1f))
                    TextButton(if (busy) tr("关联中…") else tr("关联本设备"), enabled = !busy && orderNo.length >= 10, onClick = {
                        busy = true; msg = null
                        scope.launch {
                            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BeaconHub.bindSponsor(orderNo) }
                            busy = false
                            msg = when (r.code) {
                                "ok" -> tr("已关联，推广已隐藏至 {0}。", fmt.format(java.util.Date(r.until))) +
                                    (if (r.evicted > 0) tr("（每位赞助者最多 {0} 台设备，已替换最早绑定的设备）", r.maxDevices) else "")
                                "not_found" -> tr("没找到这个订单，请检查订单号；刚付款的话等一两分钟再试。")
                                "limit" -> if (r.nextAt > 0) tr("这笔赞助用订单号关联的次数已用完（每 30 天最多 3 次），{0} 后可再试，或在交流群联系作者。", fmt.format(java.util.Date(r.nextAt)))
                                    else tr("这笔赞助用订单号关联的次数已用完（每 30 天最多 3 次），请稍后再试或在交流群联系作者。")
                                "expired" -> tr("这笔赞助已经到期了。")
                                "rate_limited" -> tr("尝试太频繁，请一小时后再试。")
                                "network" -> tr("网络连接失败，请稍后再试。")
                                else -> tr("暂时无法关联（{0}），请稍后再试。", r.code)
                            }
                        }
                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 隐私说明页

@Composable
fun PrivacyPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        if (!BeaconHub.enabled) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp)) {
                Text(tr("当前版本未启用统计与反馈后台（源码或本地构建），不会上传任何统计数据。"), fontSize = 14.sp, lineHeight = 22.sp,
                    modifier = Modifier.padding(16.dp))
            }
        }
        privacySections().forEach { (t, b) ->
            SmallTitle(t)
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(b, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(16.dp))
            }
        }
        Text(tr("变声、录音与模型推理都在本机完成，录音和模型不会上传。"), fontSize = 12.sp, color = Dim,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
    }
}

// ------------------------------------------------------------------ 反馈页

private const val MB = 1L shl 20
private const val MAX_FILE = 8 * MB
private const val MAX_TOTAL = 12 * MB
private const val MAX_SHOTS = 3

private data class Shot(val uri: Uri, val name: String, val size: Long)

private fun fmtSize(b: Long) = if (b >= MB) "%.1f MB".format(b / MB.toDouble()) else "${(b + 1023) / 1024} KB"

@Composable
fun FeedbackPage(liveTier: Int, speakerMode: Boolean) {
    val ctx = LocalContext.current
    if (!BeaconHub.enabled) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SmallTitle(tr("反馈渠道"))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(title = tr("加群交流"), summary = tr("QQ 群 {0}", AboutLinks.QQ_GROUP_NO), onClick = { openQqGroup(ctx) })
                ArrowPreference(title = tr("提交问题"), summary = tr("GitHub Issues，附上日志更快定位"), onClick = { openUrl(ctx, AboutLinks.ISSUES) })
            }
            Text(tr("当前版本未启用 App 内反馈（源码或本地构建），请通过以上渠道反馈。日志可在 设置 → 日志 导出。"), fontSize = 12.sp, color = Dim,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
        }
        return
    }
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var withLog by remember { mutableStateOf(true) }
    var shots by remember { mutableStateOf(listOf<Shot>()) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    val toast = { m: String -> Toast.makeText(ctx, m, Toast.LENGTH_LONG).show() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_SHOTS)) { uris ->
        val add = ArrayList<Shot>()
        for (u in uris) {
            if (shots.size + add.size >= MAX_SHOTS) { toast(tr("截图最多 {0} 张", MAX_SHOTS)); break }
            if (shots.any { it.uri == u } || add.any { it.uri == u }) continue
            var name = u.lastPathSegment ?: "image"; var size = -1L
            runCatching {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
                }
            }
            if (size < 0) size = runCatching { ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } }.getOrNull() ?: -1L
            when {
                size > MAX_FILE -> toast(tr("{0} 超过 8MB，未添加", name))
                shots.sumOf { it.size } + add.sumOf { it.size } + size.coerceAtLeast(0) > MAX_TOTAL -> toast(tr("截图合计超过 12MB，未添加 {0}", name))
                else -> add += Shot(u, name, size.coerceAtLeast(0))
            }
        }
        shots = shots + add
    }

    val len = content.trim().length
    val canSend = !busy && len in 2..2000
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        SmallTitle(tr("问题描述"))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(12.dp)) {
                TextField(value = content, onValueChange = { content = it.take(2000) }, label = tr("遇到了什么问题？怎么复现？（2–2000 字）"),
                    minLines = 5, maxLines = 12, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("$len/2000", fontSize = 11.sp, color = if (len in 2..2000 || len == 0) Dim else MiuixTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp, end = 4.dp))
                Spacer(Modifier.height(8.dp))
                TextField(value = contact, onValueChange = { contact = it.take(100) }, label = tr("联系方式（可选，QQ / 邮箱）"),
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
        }
        SmallTitle(tr("附件"))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { withLog = !withLog }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("附带日志"), fontSize = 15.sp)
                    Text(tr("App 日志与本进程系统日志，不含录音；有助于定位问题"), fontSize = 11.sp, color = Dim)
                }
                Checkbox(state = ToggleableState(withLog), onClick = { if (!busy) withLog = !withLog })
            }
            shots.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmtSize(s.size), fontSize = 11.sp, color = Dim)
                    }
                    IconButton(onClick = { shots = shots - s }, enabled = !busy) { Icon(MiuixIcons.Close, tr("移除"), tint = Dim, modifier = Modifier.size(18.dp)) }
                }
            }
            if (shots.size < MAX_SHOTS) TextButton(tr("添加截图（{0}/{1}）", shots.size, MAX_SHOTS), enabled = !busy,
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
            Text(tr("单个附件不超过 8MB，合计不超过 12MB；日志过大时自动只保留最近的部分。"), fontSize = 11.sp, color = Dim,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
        Spacer(Modifier.height(16.dp))
        if (busy) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                LinearProgressIndicator(progress = null)
                Text(stage, fontSize = 12.sp, color = Dim, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        TextButton(if (busy) tr("提交中…") else tr("提交反馈"), enabled = canSend, colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), onClick = {
                busy = true
                scope.launch {
                    val files = ArrayList<Any>()
                    shots.forEach { files += it.uri }
                    if (withLog) {
                        val limit = minOf(MAX_FILE, MAX_TOTAL - shots.sumOf { it.size })
                        if (limit >= (64L shl 10)) {
                            stage = tr("正在打包日志…")
                            runCatching { withContext(Dispatchers.IO) { L.zipForFeedback(ctx, limit) } }
                                .onSuccess { files += it }.onFailure { L.w("反馈：打包日志失败 ${it.message}") }
                        } else L.w("反馈：截图已占满附件额度，不附带日志")
                    }
                    stage = tr("正在上传…")
                    val meta = linkedMapOf<String, Any?>(
                        "app" to BuildConfig.VERSION_NAME, "vc" to BuildConfig.VERSION_CODE,
                        "android" to Build.VERSION.RELEASE, "brand" to Build.MANUFACTURER,
                        "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: ""), "lang" to java.util.Locale.getDefault().toLanguageTag(),
                        "shizuku" to !speakerMode, "tier" to liveTier,
                    )
                    BeaconHub.sendFeedback(content.trim(), contact.trim().ifBlank { null }, meta, files) { ok, err ->
                        busy = false; stage = ""
                        if (ok) {
                            toast(tr("反馈已提交，感谢！"))
                            BeaconHub.event("feedback", mapOf("log" to withLog, "shots" to shots.size))
                            content = ""; contact = ""; shots = emptyList(); withLog = true
                        } else toast(tr("提交失败：{0}", when (err) { "network" -> tr("网络错误"); null -> tr("未知错误"); else -> err }))
                    }
                }
            })
        Text(tr("提交即表示同意上传以上内容用于排查问题。"), fontSize = 11.sp, color = Dim,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
    }
}

