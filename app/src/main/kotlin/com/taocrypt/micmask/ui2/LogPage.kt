package com.taocrypt.micmask.ui2

import com.taocrypt.micmask.tr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taocrypt.micmask.L
import com.taocrypt.micmask.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class Lv(val c: Char, val name: String, val color: Color)
private val LEVELS get() = listOf(
    Lv('E', tr("错误"), Color(0xFFE5484D)),
    Lv('W', tr("警告"), Color(0xFFF5A524)),
    Lv('I', tr("信息"), Color(0xFF3B82F6)),
    Lv('D', tr("调试"), Color(0xFF8B8B8B)),
    Lv('M', tr("指标"), Color(0xFF14B8A6)),
)
private fun lvColor(c: Char) = LEVELS.firstOrNull { it.c == c }?.color ?: Color.Gray

private fun copy(ctx: Context, text: String, what: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(tr("MicMask 日志"), text))
    Toast.makeText(ctx, tr("已复制{0}", what), Toast.LENGTH_SHORT).show()
}

@Composable
fun LogPage(vm: MainViewModel) {
    val ctx = LocalContext.current
    var selfCheck by remember { mutableStateOf(false) }
    var exportAsk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf(L.entries()) }
    var levels by remember { mutableStateOf(setOf('E', 'W', 'I', 'D')) }   // 指标默认不看，太多
    var cat by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    val listState = rememberLazyListState()

    // 实时刷新：有新行才重取；暂停时冻结画面
    LaunchedEffect(paused) {
        var v = -1L
        while (!paused) {
            if (L.version != v) { v = L.version; all = L.entries() }
            delay(400)
        }
    }
    val counts = remember(all) { all.groupingBy { it.level }.eachCount() }
    val cats = remember(all) { L.CATS.filter { c -> all.any { it.cat == c } } }
    val shown = remember(all, levels, cat, q) {
        all.filter { it.level in levels && (cat == null || it.cat == cat) &&
            (q.isBlank() || it.msg.contains(q, true) || it.thread.contains(q, true)) }
    }
    // 跟随：停在底部时自动滚到最新，往上翻就不打扰
    val atBottom by remember { derivedStateOf {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
    } }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(atBottom) { if (atBottom) follow = true }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress && !atBottom) follow = false }
    LaunchedEffect(shown.size, follow) { if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.size - 1) }

    val primary = MiuixTheme.colorScheme.primary
    val chipBg = MiuixTheme.colorScheme.secondaryContainer
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TextField(value = q, onValueChange = { q = it }, label = tr("搜索内容或线程"), singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
        // 级别：多选
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LEVELS.forEach { l ->
                val on = l.c in levels
                Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) l.color.copy(alpha = 0.18f) else chipBg)
                    .clickable { levels = if (on) levels - l.c else levels + l.c }
                    .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) l.color else summary.copy(alpha = 0.4f)))
                    Spacer(Modifier.width(5.dp))
                    Text("${l.name} ${counts[l.c] ?: 0}", fontSize = 12.sp, color = if (on) l.color else summary,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        // 分类：单选
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf<String?>(null) + cats).forEach { c ->
                val on = cat == c
                Text(c ?: tr("全部"), fontSize = 12.sp, color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) primary else chipBg)
                        .clickable { cat = c }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Text(tr("{0} / {1} 条", shown.size, all.size) + (if (paused) tr(" · 已暂停") else tr(" · 实时")) + tr(" · 点按展开，长按复制"),
            fontSize = 11.sp, color = summary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.seq }) { e ->
                    val open = e.seq in expanded
                    Row(Modifier.fillMaxWidth()
                        .pointerInput(e.seq) {
                            detectTapGestures(
                                onTap = { expanded = if (open) expanded - e.seq else expanded + e.seq },
                                onLongPress = { copy(ctx, e.line(), tr("这一条")) })
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Box(Modifier.padding(top = 3.dp).width(3.dp).height(if (open) 28.dp else 14.dp).clip(RoundedCornerShape(2.dp)).background(lvColor(e.level)))
                        Column(Modifier.padding(start = 8.dp)) {
                            Text("${e.time.substring(6)}  ${e.level}  ${e.cat} · ${e.thread}", fontSize = 10.sp, color = summary,
                                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.msg, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp,
                                color = if (e.level == 'E' || e.level == 'W') lvColor(e.level) else MiuixTheme.colorScheme.onBackground,
                                maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (!follow && shown.isNotEmpty()) {
                Text(tr("↓ 回到最新"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(RoundedCornerShape(50)).background(primary)
                        .clickable { follow = true }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }

        // 底部操作
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Action(if (paused) tr("继续") else tr("暂停")) { paused = !paused }
            Action(tr("复制")) { copy(ctx, shown.takeLast(2000).joinToString("\n") { it.line() }, tr("{0} 条", minOf(shown.size, 2000))) }
            Action(tr("分享")) {
                val text = shown.takeLast(3000).joinToString("\n") { it.line() }
                runCatching {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, tr("MicMask 日志")).putExtra(Intent.EXTRA_TEXT, text), tr("分享日志"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Action(tr("导出")) { exportAsk = true }
            Action(tr("清屏")) { L.clearView(); expanded = emptySet() }
            Action(tr("自检")) { selfCheck = true }
        }
    }
    if (selfCheck) AdbSelfCheckDialog { selfCheck = false }
    if (exportAsk) ExportLogDialog(onDismiss = { exportAsk = false }) { title, problem ->
        exportAsk = false
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { L.exportForShare(ctx, title, problem) } }
            r.onSuccess { (f, saved) ->
                Toast.makeText(ctx, if (saved != null) tr("已导出到 ") + saved else tr("已打包 {0}", f.name), Toast.LENGTH_LONG).show()
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                    val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, title.trim().ifEmpty { tr("MicMask 日志") })
                        .putExtra(Intent.EXTRA_TEXT, problem.trim().take(2000))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newRawUri(f.name, uri)
                    ctx.startActivity(Intent.createChooser(send, tr("分享日志")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure { L.w("日志导出：唤起分享失败 ${it.message}") }
            }.onFailure { Toast.makeText(ctx, tr("导出失败：{0}", it.message), Toast.LENGTH_LONG).show() }
        }
    }
}

/** 导出日志前填写标题（作文件名）和具体问题（写进 report.txt），确定后打包并唤起系统分享 */
@Composable
private fun ExportLogDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf("") }
    WindowDialog(show = true, title = tr("导出日志"),
        summary = tr("标题用作导出文件名（留空用时间命名）；问题描述会写进压缩包里的 report.txt。日志不含录音。"),
        onDismissRequest = onDismiss) {
        Column {
            TextField(value = title, onValueChange = { title = it.replace('\n', ' ').take(60) }, label = tr("标题（可选）"),
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            TextField(value = problem, onValueChange = { problem = it.take(2000) }, label = tr("遇到的具体问题（可选，怎么复现）"),
                minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth())
            val fn = L.safeExportName(title)
            Text(tr("文件名：{0}", (fn ?: "micmask-logs-" + tr("时间")) + ".zip"), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(tr("取消"), onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(tr("导出并分享"), onClick = { onConfirm(title, problem) },
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

/** 不装 Shizuku 也能用的 adb 自检命令（借鉴 mobile-rvc）：电脑上执行后把输出发给开发者 */
val ADB_SELF_CHECK: String get() = listOf(
    "adb shell getprop ro.product.model",
    "adb shell getprop ro.build.version.release",
    "adb shell getprop ro.soc.model",
    "adb shell dumpsys package com.taocrypt.micmask | grep -E \"versionName|versionCode\"",
    "adb shell dumpsys package moe.shizuku.privileged.api | grep versionName",
    "adb shell \"ps -A | grep -iE 'shizuku|micmask'\"",
    "adb shell appops get com.taocrypt.micmask RECORD_AUDIO",
    "adb shell dumpsys audio | grep -iE \"mix|policy|record\" | head -n 60",
    "adb shell \"dumpsys media.audio_policy | grep -iE 'mix|input' | head -n 60\"",
    "adb logcat -d -s MicMask:V AudioRecord:W AudioPolicyService:W | tail -n 300",
).joinToString("\n")

@Composable
private fun AdbSelfCheckDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    WindowDialog(show = true, title = tr("adb 自检命令"),
        summary = tr("连上电脑、打开 USB 调试，在电脑终端逐条执行，把输出连同日志一起发给开发者。不需要 Shizuku。"),
        onDismissRequest = onDismiss) {
        Column {
            Text(ADB_SELF_CHECK, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x14808080))
                    .horizontalScroll(rememberScrollState()).padding(10.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(tr("关闭"), onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(tr("复制全部"), onClick = { copy(ctx, ADB_SELF_CHECK, tr("自检命令")); onDismiss() },
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

@Composable
private fun Action(t: String, onClick: () -> Unit) {
    Text(t, fontSize = 14.sp, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
}
