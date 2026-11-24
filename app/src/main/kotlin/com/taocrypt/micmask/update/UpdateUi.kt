package com.taocrypt.micmask.update

import com.taocrypt.micmask.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private fun mb(b: Long) = "%.1f MB".format(b / 1048576.0)
private fun speed(bps: Double) = if (bps >= 1048576) "%.1f MB/s".format(bps / 1048576) else "%.0f KB/s".format(bps / 1024)

/** 发布说明（CHANGELOG 小节）简单渲染：### 标题加粗，- 列表变圆点 */
@Composable
private fun Notes(md: String) {
    val text = buildAnnotatedString {
        md.lines().forEachIndexed { i, raw ->
            val l = raw.trimEnd()
            if (i > 0) append('\n')
            when {
                l.startsWith("#") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(l.trimStart('#').trim()) }
                l.trimStart().startsWith("- ") || l.trimStart().startsWith("* ") -> append("• " + l.trimStart().drop(2).replace("**", "").replace("`", ""))
                else -> append(l.replace("**", "").replace("`", ""))
            }
        }
    }
    Text(text, fontSize = 13.sp, lineHeight = 19.sp)
}

/** 全局更新对话框：V2App 里挂一次，Updater.dialog 控制显示。底部有推荐位 update（一行文字链接；强制更新与变声中不显示） */
@Composable
fun UpdateDialog(liveOn: Boolean = false) {
    val ctx = LocalContext.current
    val show by Updater.dialog.collectAsState()
    val st by Updater.state.collectAsState()
    val forcedRel by Updater.forced.collectAsState()
    val r = when (val s = st) {
        is Updater.State.Newer -> s.r
        is Updater.State.Downloading -> s.r
        is Updater.State.Downloaded -> s.r
        else -> forcedRel  // 强制更新：下载失败后对话框也不能消失
    }
    // 通知权限（Android 13+）：没有就只在 App 内显示进度，不影响下载
    val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { _ -> r?.let { Updater.download(ctx, it) } }
    val startDl: (Updater.Release) -> Unit = { rel ->
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else Updater.download(ctx, rel)
    }
    if (r == null || !(show || forcedRel != null)) return
    val forced = r.forces()
    val close = { if (!forced) Updater.dialog.value = false }
    WindowDialog(show = true, title = tr("发现新版本 {0}", r.tag),
        summary = if (forced) tr("当前版本已停止支持，需要更新后才能继续使用") else tr("当前 v{0}", com.taocrypt.micmask.BuildConfig.VERSION_NAME),
        onDismissRequest = { close() }) {
        Column {
            if (r.notes.isNotBlank()) {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { Notes(r.notes) }
                Spacer(Modifier.height(12.dp))
            }
            when (val s = st) {
                is Updater.State.Downloading -> {
                    val p = if (s.total > 0) s.got.toFloat() / s.total else 0f
                    LinearProgressIndicator(progress = p)
                    Spacer(Modifier.height(6.dp))
                    Text(if (s.got == 0L) s.source else "${mb(s.got)} / ${mb(s.total)} · ${speed(s.bps)} · ${s.source}",
                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(tr("取消下载"), onClick = { Updater.cancelDownload(ctx) }, modifier = Modifier.weight(1f))
                        if (!forced) TextButton(tr("后台下载"), onClick = { close() }, modifier = Modifier.weight(1f))
                    }
                }
                is Updater.State.Downloaded -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!forced) TextButton(tr("稍后"), onClick = { close() }, modifier = Modifier.weight(1f))
                    TextButton(tr("安装"), onClick = { Updater.install(ctx, s.file) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
                else -> Column {
                    (st as? Updater.State.Fail)?.let {
                        Text(tr("下载失败：{0}", it.msg), fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!forced) TextButton(tr("跳过此版"), onClick = { Updater.skip(ctx, r.tag) }, modifier = Modifier.weight(1f))
                        TextButton(if (st is Updater.State.Fail) tr("重试") else tr("更新"), onClick = { startDl(r) }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary())
                    }
                }
            }
            if (!forced) com.taocrypt.micmask.ui2.BeaconSlotLink("update", liveOn, Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

/** 下载镜像设置页 */
@Composable
fun MirrorPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Updater.mode(ctx)) }
    var list by remember { mutableStateOf(Updater.userMirrors(ctx)) }
    var input by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Updater.Speed>>(emptyList()) }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    fun save(l: List<String>) { list = l; Updater.setUserMirrors(ctx, l) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        SmallTitle(tr("下载方式"))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            WindowDropdownPreference(items = listOf(tr("自动（GitHub 不通时测速选镜像）"), tr("始终直连 GitHub")), selectedIndex = mode, title = tr("镜像"),
                onSelectedIndexChange = { mode = it; Updater.setMode(ctx, it) })
        }
        Text(tr("填了自定义镜像时优先按顺序使用它们，最后直连；都失败才会用内置镜像。镜像格式：https://example.com（会拼成 镜像/https://github.com/…）"),
            fontSize = 11.sp, color = summary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))

        SmallTitle(tr("自定义镜像"))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            list.forEachIndexed { i, m ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. $m", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (i > 0) TextButton(tr("上移"), onClick = { save(list.toMutableList().apply { add(i - 1, removeAt(i)) }) })
                    TextButton(tr("删除"), onClick = { save(list.filterIndexed { j, _ -> j != i }) })
                }
            }
            Column(Modifier.padding(12.dp)) {
                TextField(value = input, onValueChange = { input = it; err = null }, label = tr("添加镜像，如 https://gh-proxy.com"), singleLine = true)
                err?.let { Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(8.dp))
                TextButton(tr("添加"), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                    val m = Updater.normalizeMirror(input)
                    when {
                        m == null -> err = tr("地址不对，需要 http(s)://域名[/路径]")
                        m in list -> err = tr("已经有了")
                        else -> { save(list + m); input = "" }
                    }
                })
            }
        }

        SmallTitle(tr("测速"))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            ArrowPreference(title = if (testing) tr("正在测速…") else tr("测试全部下载源"), summary = tr("直连、自定义镜像与内置镜像"),
                enabled = !testing, onClick = {
                    testing = true
                    scope.launch { results = Updater.speedTestAll(ctx).sortedByDescending { if (it.ok) it.bps else -1.0 }; testing = false }
                })
            results.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(r.base ?: tr("GitHub 直连"), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if (r.ok) speed(r.bps) else tr("不可用"), fontSize = 13.sp,
                        color = if (r.ok) MiuixTheme.colorScheme.primary else summary)
                }
            }
            if (results.isNotEmpty()) Spacer(Modifier.height(8.dp))
        }
    }
}
