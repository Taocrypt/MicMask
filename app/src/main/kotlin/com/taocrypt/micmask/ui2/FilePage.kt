package com.taocrypt.micmask.ui2

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taocrypt.micmask.MainViewModel
import com.taocrypt.micmask.UiState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import kotlin.math.roundToInt

/**
 * 文件变声（句级，原版功能迁移）：选音频或录音 → 整段 RVC 转换（支持音色检索、无声段保护）→ 试听 / 设为待发送。
 * 用的是句级三件套（hubert + rmvpe + net_g），不是实时流式模型。
 */
@Composable
internal fun FilePage(vm: MainViewModel, st: UiState) {
    val ctx = LocalContext.current
    val clip = remember { ClipPlayer() }
    DisposableEffect(Unit) { onDispose { clip.stop() } }
    LaunchedEffect(Unit) { vm.refreshModels() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.pickAudio(uri)
        }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importModelFiles(it) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startRecording() else Toast.makeText(ctx, "没有麦克风权限，无法录音", Toast.LENGTH_LONG).show()
    }
    var recList by remember { mutableStateOf<List<File>?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        // ---- 模型 ----
        SmallTitle("句级模型")
        Card(Modifier.fillMaxWidth().padding(Pad2)) {
            st.modelItems.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name + if (m.required) "" else "（可选）", fontSize = 14.sp)
                        Text(if (m.present) "%.1f MB".format(m.sizeBytes / 1048576.0) else "缺失", fontSize = 12.sp,
                            color = if (m.present) Dim else Color(0xFFE5484D))
                    }
                }
            }
            ArrowPreference(title = when { st.loadingEngine -> "加载中…"; st.engineLoaded -> "重新加载模型"; else -> "加载模型" },
                summary = if (st.engineLoaded) "已在内存里" else if (st.modelsReady) "加载一次会一直留在内存" else "缺模型，先导入",
                onClick = { if (st.modelsReady && !st.loadingEngine) vm.loadEngine() })
            ArrowPreference(title = if (st.importing) "导入中 ${st.importDone}/${st.importTotal}" else "从手机导入模型",
                summary = "zip 或 .onnx / .bin", onClick = { if (!st.importing) modelPicker.launch(arrayOf("*/*")) })
            if (st.engineLog.isNotBlank()) Text(st.engineLog, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 11.sp, color = Dim)
        }

        // ---- 输入 ----
        SmallTitle("输入")
        Card(Modifier.fillMaxWidth().padding(Pad2)) {
            Column(Modifier.padding(16.dp)) {
                Text(st.srcName ?: "未选择", fontWeight = FontWeight.Medium)
                Text(st.srcInfo ?: "支持 wav / mp3 / m4a / flac / ogg，或直接录一段", fontSize = 12.sp, color = Dim)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(if (st.srcName == null) "选择音频" else "换一个", onClick = { picker.launch(arrayOf("audio/*")) },
                        enabled = !st.busy && !st.recording, modifier = Modifier.weight(1f))
                    TextButton(if (st.recording) "停止录音" else "录音", onClick = {
                        when { st.recording -> vm.stopRecording(); vm.hasMicPermission() -> vm.startRecording()
                            else -> perm.launch(android.Manifest.permission.RECORD_AUDIO) }
                    }, enabled = !st.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
                if (st.recording) {
                    Spacer(Modifier.height(10.dp))
                    Text("录音中 %02d:%02d · %.1f kHz".format(st.recElapsedMs / 60000, st.recElapsedMs / 1000 % 60, st.recSampleRate / 1000f), fontSize = 13.sp)
                    LevelMeter(st.recLevel)
                    Text(when { st.recLevel > 0.95f -> "音量偏大，可能削波"; st.recLevel < 0.12f -> "声音太小，靠近一点"; else -> "电平正常" },
                        fontSize = 12.sp, color = Dim)
                }
                if (!st.recording && st.recFile != null) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(if (clip.isPlaying("rec")) "停止" else "试听录音 %.1f 秒".format(st.recSeconds),
                        onClick = { clip.toggle(ctx, "rec", st.recFile!!) }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (st.recCount > 0) ArrowPreference(title = "历史录音", summary = "${st.recCount} 条", onClick = { recList = vm.listRecordings() })
            ArrowPreference(title = "从 App 目录导入", summary = "读取 App 外部目录里放好的音频", onClick = { vm.importFromDir() })
        }

        // ---- 参数 ----
        SmallTitle("参数")
        Card(Modifier.fillMaxWidth().padding(Pad2)) {
            LabeledSlider("变调", "%+d 半音".format(st.pitchShift), "男声转女声一般 +8 ~ +12", st.pitchShift.toFloat(), -24f..24f, 47) { vm.setPitch(it.roundToInt()) }
            SwitchPreference(checked = st.useIndex, onCheckedChange = { vm.setUseIndex(it) }, title = "音色检索",
                summary = "更像目标音色，慢一点；需要检索文件")
            if (st.useIndex) LabeledSlider("检索混合比例", "%.2f".format(st.indexRate), null, st.indexRate, 0f..1f) { vm.setIndexRate(it) }
            LabeledSlider("无声段保护", "%.2f".format(st.protect), "越小越贴近检索音色，太小静音段易出杂音，默认 0.33", st.protect, 0f..0.5f) { vm.setProtect(it) }
        }
        SmallTitle("高级")
        Card(Modifier.fillMaxWidth().padding(Pad2)) {
            LabeledSlider("说话人 ID", "${st.sid}", "单人模型保持 0", st.sid.toFloat(), 0f..108f, 107) { vm.setSid(it.roundToInt()) }
            LabeledSlider("拼接重叠", "${st.overlap} 帧", "长音频分块拼接的平滑程度", st.overlap.toFloat(), 0f..256f, 15) { vm.setOverlap(it.roundToInt()) }
            LabeledSlider("推理线程", if (st.threads == 0) "自动" else "${st.threads}", "本机 ${Runtime.getRuntime().availableProcessors()} 核，0 = 自动；改后需重新加载",
                st.threads.toFloat(), 0f..8f, 7) { vm.setThreads(it.roundToInt()) }
        }

        // ---- 转换 ----
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth().padding(Pad2)) {
            Column(Modifier.padding(16.dp)) {
                TextButton(when { st.recording -> "先停止录音"; st.busy -> "转换中…"; !st.engineLoaded -> "请先加载模型"; st.srcName == null -> "请先选择音频或录音"; else -> "开始变声" },
                    onClick = { vm.convert() }, enabled = st.srcName != null && st.engineLoaded && !st.busy && !st.recording,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
                if (st.busy) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = st.progress)
                    Text("${st.stage}  ${(st.progress * 100).roundToInt()}%", fontSize = 12.sp, color = Dim)
                }
            }
        }

        // ---- 结果 ----
        st.resultFile?.let { rf ->
            SmallTitle("结果")
            Card(Modifier.fillMaxWidth().padding(Pad2)) {
                Column(Modifier.padding(16.dp)) {
                    Text("时长 %.1f 秒 · 用时 %.1f 秒".format(st.resultSeconds, st.elapsedMs / 1000.0), fontWeight = FontWeight.Medium)
                    Text(st.resultDisplay ?: rf.name, fontSize = 11.sp, color = Dim)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(if (clip.isPlaying("res")) "停止" else "试听", onClick = { clip.toggle(ctx, "res", rf) }, modifier = Modifier.weight(1f))
                        TextButton("送麦", onClick = { clip.stop(); vm.sendFileToMic(rf, rf.nameWithoutExtension) }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary())
                    }
                }
            }
        }

        // ---- 待发送 / 悬浮球（录播外放） ----
        SmallTitle("录播发送")
        Card(Modifier.fillMaxWidth().padding(Pad2)) {
            ArrowPreference(title = "待发送", summary = st.qqPendingName ?: "还没有（转换或合成后自动设置）",
                onClick = { if (st.qqPlaying) vm.stopQqLoop() else if (st.qqPendingName != null) vm.playPendingNow() })
            SwitchPreference(checked = st.qqFloating, onCheckedChange = { vm.setFloating(it) }, title = "悬浮球",
                summary = "到 QQ 按住说话前点一下，从扬声器外放待发送语音；建议小窗让 QQ 保持前台")
            SwitchPreference(checked = st.qqAutoSend, onCheckedChange = { vm.setQqAutoSend(it) }, title = "检测到录音时自动播",
                summary = if (st.qqWatchEnabled) "无障碍服务已开启" else "需在系统无障碍里开启，部分 QQ 版本检测不到")
            if (st.qqBlockedByHeadset) Text("上次没能外放：耳机（${st.qqHeadsetName}）还连着，QQ 会录成静音。先断开耳机再试。",
                Modifier.padding(16.dp), fontSize = 12.sp, color = Color(0xFFE5A100))
        }
    }

    recList?.let { list ->
        WindowDialog(show = true, title = "历史录音", onDismissRequest = { recList = null }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (list.isEmpty()) Text("还没有录音", color = Dim)
                list.forEach { f -> TextButton(f.name, onClick = { vm.useRecordingAsInput(f); recList = null }, modifier = Modifier.fillMaxWidth()) }
                TextButton("关闭", onClick = { recList = null }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LevelMeter(level: Float) {
    val pct = level.coerceIn(0f, 1f)
    val c = when { pct > 0.95f -> Color(0xFFE5484D); pct > 0.78f -> Color(0xFFE5A100); else -> MiuixTheme.colorScheme.primary }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MiuixTheme.colorScheme.secondaryContainer)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(pct.coerceAtLeast(0.02f)).clip(RoundedCornerShape(4.dp)).background(c))
    }
}
