package com.taocrypt.micmask.ui2

import androidx.compose.ui.graphics.luminance
import com.taocrypt.micmask.tr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.painterResource
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.taocrypt.micmask.BuildConfig
import com.taocrypt.micmask.MainViewModel
import com.taocrypt.micmask.update.Updater
import androidx.compose.runtime.collectAsState
import top.yukonga.miuix.kmp.preference.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.HttpURLConnection
import java.net.URL

/** 关于页的所有外链集中在这里，改地址只动这一处。 */
object AboutLinks {
    const val HOME = "https://github.com/Taocrypt/MicMask"
    const val RELEASES = "https://github.com/Taocrypt/MicMask/releases"
    const val ISSUES = "https://github.com/Taocrypt/MicMask/issues"
    /** 官网（暂定主仓库 GitHub Pages，尚未建站） */
    const val SITE = "https://taocrypt.github.io/MicMask/"
    val RELEASE_TAG get() = "$RELEASES/tag/v${BuildConfig.VERSION_NAME}"
        const val MODEL_REPO = "https://cnb.build/taocrypt/MicMask"
    const val QQ_GROUP = "https://qm.qq.com/q/WHDZb3bXws"
    const val QQ_GROUP_NAME = "MicMask用户交流群"
    const val QQ_GROUP_NO = "780988679"
    /** 直接拉起 QQ/TIM 的群资料卡（点「加入群聊」即可）；没装 QQ 时回退到网页链接。 */
    const val QQ_GROUP_APP = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$QQ_GROUP_NO&card_type=group&source=qrcode"
    /** 赞赏链接：爱发电「MicMask 赞助」方案下单页，留空则显示「即将开放」。 */
    const val DONATE = "https://afdian.com/order/create?plan_id=5a2e6d94e42011f18ad35254001e7c00&product_type=0"
    const val DONATE_HOME = "https://afdian.com/a/taocrypt"
    /** 赞赏墙名单（GitHub Action 定时从爱发电生成），按顺序尝试。 */
    val SPONSORS = listOf(
        "https://raw.githubusercontent.com/Taocrypt/MicMask/main/sponsors/sponsors.json",
        "https://cdn.jsdelivr.net/gh/Taocrypt/MicMask@main/sponsors/sponsors.json",
    )

    val REFS get() = listOf(
        Triple("atri-vc", tr("本项目的起点，实时 RVC 安卓实现"), "https://github.com/spider996/atri-vc"),
        Triple("RVC WebUI", tr("Retrieval-based Voice Conversion 音色转换"), "https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI"),
        Triple("GPT-SoVITS", tr("文本转语音与语气参考"), "https://github.com/RVC-Boss/GPT-SoVITS"),
        Triple("ONNX Runtime", tr("端侧推理引擎"), "https://github.com/microsoft/onnxruntime"),
        Triple("Shizuku", tr("免 Root 获取 shell 权限，注入麦克风"), "https://github.com/RikkaApps/Shizuku"),
        Triple("Miuix", tr("界面组件库"), "https://github.com/compose-miuix-ui/miuix"),
    )
}

internal fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, tr("没有可打开链接的应用"), Toast.LENGTH_SHORT).show() }
}

internal fun openQqGroup(ctx: Context) {
    val ok = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutLinks.QQ_GROUP_APP)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!ok) openUrl(ctx, AboutLinks.QQ_GROUP)
}

@Composable
fun AboutPage(vm: MainViewModel, openLogs: () -> Unit, openMirrors: () -> Unit = {}, openDisclaimer: () -> Unit = {}, openRefs: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val upd by Updater.state.collectAsState()
    var auto by remember { mutableStateOf(Updater.autoCheck(ctx)) }
    val icon = remember {
        runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(288, 288).asImageBitmap() }.getOrNull()
    }
    LaunchedEffect(Unit) { if (upd is Updater.State.Idle || upd is Updater.State.Fail) Updater.check(ctx, silent = true) }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val pad = PaddingValues(horizontal = 12.dp)
    var stars by remember { mutableStateOf(com.taocrypt.micmask.Prefs.main(ctx).getInt("gh_stars", -1).takeIf { it >= 0 }) }
    LaunchedEffect(Unit) { fetchStars()?.let { stars = it; com.taocrypt.micmask.Prefs.main(ctx).edit().putInt("gh_stars", it).apply() } }
    var boom by remember { mutableStateOf(0) }   // 彩蛋：点图标放烟花
    if (boom > 0) Fireworks(boom) { boom = 0 }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        // ---- 头部 ----
        Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Image(icon, null, Modifier.size(88.dp).clip(RoundedCornerShape(22.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { boom++ })
            Spacer(Modifier.height(12.dp))
            Text("MicMask", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(tr("安卓实时变声 · 直通游戏与通话麦克风"), fontSize = 13.sp, color = summary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 三个胶囊同款：图标 + 文字；底色跟随莫奈取色，压成低饱和的浅色/深色调
                val cs = MiuixTheme.colorScheme
                val dark = cs.background.luminance() < 0.5f
                val logo = androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.Color(0xFF8E6CE0), cs.primary, 0.35f)
                val gray = androidx.compose.ui.graphics.lerp(cs.onSurface, cs.primary, 0.2f)
                fun tone(c: androidx.compose.ui.graphics.Color) =
                    androidx.compose.ui.graphics.lerp(cs.surface, c, if (dark) 0.22f else 0.14f) to
                        androidx.compose.ui.graphics.lerp(c, if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black, if (dark) 0.25f else 0.15f)
                val (vb, vf) = tone(cs.primary); val (gb, gf) = tone(gray); val (wb, wf) = tone(logo)
                Pill(com.taocrypt.micmask.R.drawable.ic_tag, "v${BuildConfig.VERSION_NAME}", vb, vf) { openUrl(ctx, AboutLinks.RELEASE_TAG) }
                Pill(com.taocrypt.micmask.R.drawable.ic_github, stars?.let { "★ $it" } ?: "GitHub", gb, gf) { openUrl(ctx, AboutLinks.HOME) }
                Pill(com.taocrypt.micmask.R.drawable.ic_globe, "Website", wb, wf) { openUrl(ctx, AboutLinks.SITE) }
            }
        }

        // ---- 版本信息（更新 + 设备信息，设备信息默认折叠） ----
        SmallTitle(tr("版本信息"))
        Card(Modifier.fillMaxWidth().padding(pad)) {
            val (t, s) = when (val u = upd) {
                Updater.State.Idle -> tr("检查更新") to tr("点此检查")
                Updater.State.Checking -> tr("检查更新") to tr("正在检查…")
                is Updater.State.Latest -> tr("已是最新版") to tr("最新发布 {0}", u.tag)
                is Updater.State.Newer -> tr("发现新版本 {0}", u.r.tag) to tr("点此查看更新内容")
                is Updater.State.Downloading -> tr("正在下载 {0}", u.r.tag) to (if (u.total > 0) "${u.got * 100 / u.total}% · ${u.source}" else u.source)
                is Updater.State.Downloaded -> tr("{0} 已下载", u.r.tag) to tr("点此安装")
                is Updater.State.Fail -> tr("检查更新") to u.msg
            }
            ArrowPreference(title = t, summary = s, onClick = {
                when (upd) {
                    is Updater.State.Newer, is Updater.State.Downloading, is Updater.State.Downloaded -> Updater.dialog.value = true
                    Updater.State.Checking -> Unit
                    else -> Updater.check(ctx)
                }
            })
            var beta by remember { mutableStateOf(Updater.betaChannel(ctx)) }
            SwitchPreference(checked = beta, onCheckedChange = { beta = it; Updater.setBetaChannel(ctx, it); Updater.check(ctx) }, title = tr("接收测试版"),
                summary = if (beta) tr("测试版通道：alpha / beta / rc 和正式版都会推送，新功能先到，可能不稳定") else tr("稳定版通道：只推送正式版"))
            SwitchPreference(checked = auto, onCheckedChange = { auto = it; Updater.setAutoCheck(ctx, it) }, title = tr("启动时检查"),
                summary = tr("关闭后仍会检查强制更新"))
            ArrowPreference(title = tr("下载镜像"), summary = tr("GitHub 不通时自动测速选择镜像"), onClick = openMirrors)
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "-"
            Collapsible("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL} · $abi") {
                InfoRow(tr("系统"), "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow(tr("设备"), "${Build.MANUFACTURER} ${Build.MODEL}")
                InfoRow(tr("架构"), abi)
                InfoRow(tr("版本"), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow(tr("构建"), (if (Regex("alpha|beta|rc", RegexOption.IGNORE_CASE).containsMatchIn(BuildConfig.VERSION_NAME)) tr("测试版") else tr("正式版")) +
                    if (BuildConfig.DEBUG) " · debug" else "")
                InfoRow(tr("包名"), ctx.packageName)
            }
        }

        // ---- 反馈 ----
        SmallTitle(tr("交流反馈"))
        Card(Modifier.fillMaxWidth().padding(pad)) {
            ArrowPreference(title = tr("加群交流"), summary = tr("加入MicMask用户交流Q群"), onClick = { openQqGroup(ctx) })
            ArrowPreference(title = tr("提交问题"), summary = tr("GitHub Issues，附上日志更快定位"), onClick = { openUrl(ctx, AboutLinks.ISSUES) })
        }
        val liveOn = vm.state.collectAsState().value.liveOn
        BeaconSlotCard("about", liveOn, Modifier.fillMaxWidth().padding(pad).padding(top = 12.dp))

        // ---- 赞赏 ----
        SmallTitle(tr("支持开发"))
        SponsorWall {
            val ready = AboutLinks.DONATE.isNotBlank()
            ArrowPreference(title = tr("赞赏支持"), summary = if (ready) tr("爱发电 · ¥5/月，请作者喝杯奶茶") else tr("即将开放，感谢关注"),
                enabled = ready, onClick = { if (ready) openUrl(ctx, AboutLinks.DONATE) })
        }

        // ---- 其他：免责声明 + 参考项目（二级页） ----
        SmallTitle(tr("其他"))
        Card(Modifier.fillMaxWidth().padding(pad)) {
            ArrowPreference(title = tr("免责声明"), summary = tr("版权归属、使用准则与隐私"), onClick = openDisclaimer)
            ArrowPreference(title = tr("参考项目"), summary = AboutLinks.REFS.joinToString(" · ") { it.first }, onClick = openRefs)
        }
        Text("© 2026 Taocrypt · Made with AI & ❤️",
            fontSize = 11.sp, color = summary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp))
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(v, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.padding(start = 16.dp))
    }
}

/** 折叠卡片内容：头部一行摘要 + 展开/收起箭头，默认收起 */
@Composable
internal fun Collapsible(summary: String, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "fold")
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) tr("收起") else summary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
            Text("⌄", fontSize = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(start = 8.dp).rotate(rot))
        }
        AnimatedVisibility(open) { Column(Modifier.padding(bottom = 8.dp)) { content() } }
    }
}

/** GitHub star 数（匿名 API，失败返回 null，界面用缓存） */
private suspend fun fetchStars(): Int? = withContext(Dispatchers.IO) {
    runCatching {
        val c = (URL("https://api.github.com/repos/Taocrypt/MicMask").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MicMask/${BuildConfig.VERSION_NAME}")
        }
        if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().readText()).optInt("stargazers_count", -1).takeIf { it >= 0 }
    }.getOrNull()
}

@Composable
private fun Pill(icon: Int, text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)? = null) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(bg).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(14.dp), tint = fg)
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, color = fg)
    }
}
