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
    tr("匿名统计会收集") to tr("• App 版本号、手机型号与系统版本\n• 功能使用事件，如开始/停止 AI 变声（ai_start / ai_stop）、切换音色、应用预设、文件变声、文字转语音，以及时长、档位等数值\n• 崩溃时的异常类名与本应用的几行栈帧\n• 随机生成的安装 ID（与你的身份无关，卸载重装会变）"),
    tr("不会收集") to tr("• 录音、音频内容和变声后的声音\n• 音色模型、文字转语音输入的文字\n• 账号、手机号、联系人、短信、精确位置等个人信息"),
    tr("其他联网") to tr("获取公告与推荐时会发送版本号、系统语言与安装 ID；主动提交反馈时，会上传你填写的内容、联系方式，以及你选择附带的日志与截图。"),
    tr("你可以随时") to tr("在 设置 → 隐私 中关闭匿名统计、删除已上传的统计数据，或关闭推荐。匿名统计默认关闭，只有你勾选同意后才会开启。"),
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
    var stats by remember { mutableStateOf(false) }
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
                        Text(tr("可选，帮助改进兼容性；不勾选也能正常使用"), fontSize = 11.sp, color = Dim)
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

/** dialog 公告逐条弹出；[blocked]（变声中、同意弹窗/更新弹窗/导入中）时不弹 */
@Composable
fun BeaconNoticeDialogs(blocked: Boolean) {
    val ctx = LocalContext.current
    val n = BeaconHub.dialogQueue.firstOrNull()
    if (blocked || n == null) return
    LaunchedEffect(n.id) { BeaconHub.noticeShown(n) }
    val close = { if (BeaconHub.dialogQueue.isNotEmpty()) BeaconHub.dialogQueue.removeAt(0); Unit }
    WindowDialog(show = true, title = n.title.ifBlank { tr("公告") }, onDismissRequest = { close() }) {
        Column {
            if (n.body.isNotBlank()) {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(md(n.body), fontSize = 14.sp, lineHeight = 21.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            NoticeButtons(n, primaryLast = true) { close() }
            if (n.buttons.isEmpty()) TextButton(tr("知道了"), onClick = { close() }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
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

/** 变声页顶部的横幅公告，可关闭（本次启动内不再显示） */
@Composable
fun BeaconBanners() {
    if (!BeaconHub.enabled) return
    BeaconHub.banners.toList().forEach { n ->
        LaunchedEffect(n.id) { BeaconHub.noticeShown(n) }
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.title.ifBlank { tr("公告") }, fontWeight = FontWeight.Medium, fontSize = 15.sp, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { BeaconHub.banners.remove(n) }) {
                        Icon(MiuixIcons.Close, tr("关闭"), tint = Dim, modifier = Modifier.size(18.dp))
                    }
                }
                if (n.body.isNotBlank()) Text(md(n.body), fontSize = 13.sp, lineHeight = 19.sp, color = Dim, modifier = Modifier.padding(end = 12.dp))
                if (n.buttons.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.padding(end = 12.dp)) { NoticeButtons(n, primaryLast = true) { BeaconHub.banners.remove(n) } }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 推荐位

private val slotImgCache = HashMap<String, ImageBitmap?>()

private fun httpGet(url: String, limit: Int): ByteArray? = runCatching {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 8000
        setRequestProperty("User-Agent", "MicMask/${BuildConfig.VERSION_NAME}")
    }
    if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }.takeIf { it.size <= limit }
}.getOrNull()

/** 按 SponsorWall 的写法：先读尺寸再按 2 的幂降采样，RGB_565 省内存 */
private suspend fun loadSlotImage(url: String): ImageBitmap? {
    if (url.isBlank()) return null
    synchronized(slotImgCache) { if (slotImgCache.containsKey(url)) return slotImgCache[url] }
    val bmp = withContext(Dispatchers.IO) {
        httpGet(url, 4 shl 20)?.let { b ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            var s = 1; while (o.outWidth / (s * 2) >= 160 && o.outHeight / (s * 2) >= 160) s *= 2
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 })
                ?.asImageBitmap()
        }
    }
    synchronized(slotImgCache) { slotImgCache[url] = bmp }
    return bmp
}

/**
 * 推荐位公共规则（三种样式共用）：变声中（[liveOn]）隐藏；设置里关闭「显示推荐」、SDK 关闭、该位置已被关闭或没有内容时不显示；
 * 曝光与关闭的频控在 [BeaconHub.slot] / [BeaconHub.slotViewed] / [BeaconHub.dismissSlot]。
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
private fun SlotClose(position: String) = SlotClose { BeaconHub.dismissSlot(position) }

@Composable
private fun SlotClose(onClose: () -> Unit) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        Icon(MiuixIcons.Close, tr("关闭推荐"), Modifier.size(16.dp), tint = Dim)
    }
}

@Composable
private fun slotImage(s: Slot): ImageBitmap? {
    var img by remember(s.image) { mutableStateOf<ImageBitmap?>(s.image?.let { u -> synchronized(slotImgCache) { slotImgCache[u] } }) }
    LaunchedEffect(s.image) { s.image?.let { u -> if (img == null) img = loadSlotImage(u) } }
    return img
}

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
                SlotClose(position)
            }
        }
    }
}

/** 音色仓库列表里的推荐位（repo_list）：外形同仓库音色项，标题旁带「推广」，右侧关闭 */
@Composable
fun BeaconSlotRepoItem(position: String, liveOn: Boolean, modifier: Modifier) {
    val s = rememberSlot(position, liveOn) ?: return
    SlotRepoItemBody(s, modifier) { BeaconHub.dismissSlot(position) }
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
        SlotClose(position)
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

