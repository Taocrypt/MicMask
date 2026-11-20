package com.taocrypt.micmask.ui2

import com.taocrypt.micmask.tr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
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

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, tr("没有可打开链接的应用"), Toast.LENGTH_SHORT).show() }
}

private fun openQqGroup(ctx: Context) {
    val ok = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutLinks.QQ_GROUP_APP)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!ok) openUrl(ctx, AboutLinks.QQ_GROUP)
}

@Composable
fun AboutPage(vm: MainViewModel, openLogs: () -> Unit, openMirrors: () -> Unit = {}) {
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        // ---- 头部 ----
        Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Image(icon, null, Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)))
            Spacer(Modifier.height(12.dp))
            Text("MicMask", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(tr("安卓实时变声 · 直通游戏与通话麦克风"), fontSize = 13.sp, color = summary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(MiuixTheme.colorScheme.primary).padding(horizontal = 10.dp, vertical = 3.dp))
        }

        // ---- 更新 ----
        SmallTitle(tr("更新"))
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
            SwitchPreference(checked = auto, onCheckedChange = { auto = it; Updater.setAutoCheck(ctx, it) }, title = tr("启动时检查更新"),
                summary = tr("关闭后仍会检查强制更新"))
            ArrowPreference(title = tr("下载镜像"), summary = tr("GitHub 不通时自动测速选择镜像"), onClick = openMirrors)
            ArrowPreference(title = tr("全部版本"), summary = "GitHub Releases", onClick = { openUrl(ctx, AboutLinks.RELEASES) })
        }

        // ---- 版本信息 ----
        SmallTitle(tr("版本信息"))
        Card(Modifier.fillMaxWidth().padding(pad)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                InfoRow(tr("版本"), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow(tr("构建"), if (BuildConfig.DEBUG) tr("测试版") else tr("正式版"))
                InfoRow(tr("包名"), ctx.packageName)
                InfoRow(tr("系统"), "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow(tr("设备"), "${Build.MANUFACTURER} ${Build.MODEL}")
                InfoRow(tr("架构"), Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
            }
        }

        // ---- 项目 ----
        SmallTitle(tr("项目"))
        Card(Modifier.fillMaxWidth().padding(pad)) {
            ArrowPreference(title = tr("项目主页"), summary = "github.com/Taocrypt/MicMask", onClick = { openUrl(ctx, AboutLinks.HOME) })
            ArrowPreference(title = tr("模型与素材仓库"), summary = "cnb.build/taocrypt/MicMask", onClick = { openUrl(ctx, AboutLinks.MODEL_REPO) })
        }

        // ---- 反馈 ----
        SmallTitle(tr("反馈与交流"))
        Card(Modifier.fillMaxWidth().padding(pad)) {
            ArrowPreference(title = tr("加入 QQ 群"), summary = "【${AboutLinks.QQ_GROUP_NAME}】${AboutLinks.QQ_GROUP_NO}", onClick = { openQqGroup(ctx) })
            ArrowPreference(title = tr("提交问题"), summary = tr("GitHub Issues，附上日志更快定位"), onClick = { openUrl(ctx, AboutLinks.ISSUES) })
            ArrowPreference(title = tr("日志"), summary = tr("查看并导出日志，反馈时一起发"), onClick = openLogs)
        }

        // ---- 赞赏 ----
        SmallTitle(tr("支持开发"))
        Card(Modifier.fillMaxWidth().padding(pad)) {
            val ready = AboutLinks.DONATE.isNotBlank()
            ArrowPreference(title = tr("赞赏支持"), summary = if (ready) tr("爱发电 · ¥5/月，请作者喝杯奶茶") else tr("即将开放，感谢关注"),
                enabled = ready, onClick = { if (ready) openUrl(ctx, AboutLinks.DONATE) })
        }
        SponsorWall()

        // ---- 参考项目 ----
        SmallTitle(tr("参考项目与致谢"))
        Card(Modifier.fillMaxWidth().padding(pad)) {
            AboutLinks.REFS.forEach { (name, desc, url) ->
                ArrowPreference(title = name, summary = desc, onClick = { openUrl(ctx, url) })
            }
        }
        Text(tr("音色模型来自第三方，版权归原作者；请勿用于冒充他人、诈骗等违法用途。"),
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
