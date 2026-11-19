package com.taocrypt.micmask.ui2

import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taocrypt.micmask.MainViewModel
import com.taocrypt.micmask.RefFeatureStore
import com.taocrypt.micmask.TtsLibrary
import com.taocrypt.micmask.UiState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlin.math.roundToInt

internal val Pad2 = PaddingValues(horizontal = 12.dp)
internal val Dim @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary

/** 单实例试听播放器：同时只有一条在响 */
internal class ClipPlayer {
    var playingKey: String? by mutableStateOf(null)
        private set
    private var mp: MediaPlayer? = null
    fun toggle(ctx: Context, key: String, file: File) {
        if (playingKey == key) { stop(); return }
        stop()
        try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath); p.prepare()
            p.setOnCompletionListener { if (playingKey == key) playingKey = null }
            p.start(); mp = p; playingKey = key
        } catch (t: Throwable) {
            Toast.makeText(ctx, "播放失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }
    fun isPlaying(key: String) = playingKey == key
    fun stop() { runCatching { mp?.release() }; mp = null; playingKey = null }
}

/** 带标题、数值和说明的滑块 */
@Composable
internal fun LabeledSlider(title: String, value: String, hint: String?, v: Float, range: ClosedFloatingPointRange<Float>,
                           steps: Int = 0, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 15.sp)
            Text(value, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
        }
        if (hint != null) Text(hint, fontSize = 12.sp, color = Dim)
        Spacer(Modifier.height(6.dp))
        Slider(value = v.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

/** 文本转语音（GPT-SoVITS）页 */
@Composable
internal fun TtsPage(vm: MainViewModel, st: UiState) {
    val ctx = LocalContext.current
    val clip = remember { ClipPlayer() }
    DisposableEffect(Unit) { onDispose { clip.stop() } }
    LaunchedEffect(Unit) { vm.refreshTtsModels(); vm.refreshLibrary(); if (st.repoTtsSize == 0L && st.repoUrl.isNotBlank()) vm.refreshRepo() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importTtsFiles(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        // ---- 模型状态 ----
        val dl = st.dlId == "tts"
        if (!st.ttsReady || dl) {
            SmallTitle("模型")
            Card(Modifier.fillMaxWidth().padding(Pad2)) {
                Column(Modifier.padding(16.dp)) {
                    Text("合成模型未就绪", fontWeight = FontWeight.SemiBold)
                    val miss = st.ttsMissing.size + (RefFeatureStore.EMOTIONS.size - st.ttsRefsReady.size)
                    Text("还缺 $miss 个文件。从模型仓库一键下载（只下缺的），或导入模型 zip / 文件。", fontSize = 13.sp, color = Dim)
                    if (dl) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = st.dlProgress)
                        Text("%.0f%%  %s".format(st.dlProgress * 100, st.dlName), fontSize = 12.sp, color = Dim)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            when { dl -> "下载中…"; st.repoTtsSize > 0 -> "从仓库下载（%.0f MB）".format(st.repoTtsSize / 1048576f); st.repoLoading -> "读取仓库…"; else -> "从仓库下载" },
                            onClick = { vm.downloadTts() }, enabled = st.dlId == null && !st.repoLoading,
                            modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                        TextButton(if (st.importing) "导入 ${st.importDone}…" else "从手机导入", onClick = { picker.launch(arrayOf("*/*")) },
                            enabled = !st.importing && st.dlId == null, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // ---- 文字 ----
        SmallTitle("要读的文字")
        Card(Modifier.fillMaxWidth().padding(Pad2)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                val onSurface = MiuixTheme.colorScheme.onSurface
                androidx.compose.foundation.text.BasicTextField(
                    value = st.ttsText, onValueChange = { vm.setTtsText(it) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary),
                    minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (st.ttsText.isEmpty()) Text("在这里输入要读的文字…", fontSize = 16.sp, color = Dim)
                            inner()
                        }
                    })
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("仅支持中文，英文和数字会被忽略", Modifier.weight(1f), fontSize = 12.sp, color = Dim)
                    if (st.ttsText.isNotEmpty()) Text("清空", Modifier.clip(RoundedCornerShape(10.dp)).clickable { vm.setTtsText("") }
                        .padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                    Text("${st.ttsText.count { !it.isWhitespace() }} 字", Modifier.padding(start = 8.dp), fontSize = 12.sp, color = Dim)
                }
            }
        }

        // ---- 语气 / 语速 ----
        SmallTitle("语气")
        Card(Modifier.fillMaxWidth().padding(Pad2)) {
            Column(Modifier.padding(vertical = 12.dp)) {
                val emos = RefFeatureStore.EMOTIONS
                // 分段选择：等宽四格，不截断；缺参考音频的格子变灰不可选
                val primary = MiuixTheme.colorScheme.primary
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.secondaryContainer).padding(3.dp)) {
                    emos.forEach { e ->
                        val ok = e in st.ttsRefsReady
                        val sel = e == st.ttsEmotion
                        val bg by androidx.compose.animation.animateColorAsState(if (sel) primary else Color.Transparent, label = "emo")
                        Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(11.dp)).background(bg)
                            .clickable(enabled = ok && !sel) { vm.setTtsEmotion(e) }, contentAlignment = Alignment.Center) {
                            Text(RefFeatureStore.LABELS[e] ?: e, fontSize = 14.sp, maxLines = 1,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                color = when { sel -> MiuixTheme.colorScheme.onPrimary; ok -> MiuixTheme.colorScheme.onSecondaryContainer; else -> Dim.copy(alpha = 0.5f) })
                        }
                    }
                }
                if (emos.any { it !in st.ttsRefsReady }) Text("灰色的语气缺参考音频，下载或导入 TTS 模型后可用",
                    Modifier.padding(horizontal = 16.dp).padding(top = 6.dp), fontSize = 11.sp, color = Dim)
                Text("每种语气配了一段参考原声，合成时模仿它的起伏和节奏；音色不变。",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = Dim)
                LabeledSlider("语速", "%.2fx".format(st.ttsSpeed), null, st.ttsSpeed, 0.5f..2f, 29) { vm.setTtsSpeed(it) }
            }
        }

        // ---- 高级参数 ----
        SmallTitle("高级参数")
        Card(Modifier.fillMaxWidth().padding(Pad2)) {
            SwitchPreference(checked = st.ttsAdvanced, onCheckedChange = { vm.setTtsAdvanced(it) }, title = "显示高级参数",
                summary = "不熟的话保持默认，默认值最稳")
            if (st.ttsAdvanced) {
                LabeledSlider("采样温度", "%.2f".format(st.ttsTemperature), "越低越稳但平，越高起伏越大也越易跑调",
                    st.ttsTemperature, 0.1f..2f, 37) { vm.setTtsTemperature(it) }
                LabeledSlider("Top-K", "${st.ttsTopK}", "每步只从概率最高的 K 个里挑", st.ttsTopK.toFloat(), 1f..50f, 48) { vm.setTtsTopK(it.roundToInt()) }
                LabeledSlider("Top-P", "%.2f".format(st.ttsTopP), "和 Top-K 一起控制随机程度", st.ttsTopP, 0.1f..1f, 17) { vm.setTtsTopP(it) }
                LabeledSlider("重复惩罚", "%.2f".format(st.ttsRepetition), "越大越不容易拖音、重复吐字", st.ttsRepetition, 1f..2f, 19) { vm.setTtsRepetition(it) }
            }
        }

        // ---- 合成 ----
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth().padding(Pad2)) {
            Column(Modifier.padding(16.dp)) {
                TextButton(
                    when { st.ttsBusy -> "合成中…"; !st.ttsReady -> "合成模型未就绪"; st.ttsText.isBlank() -> "请先输入文字"; else -> "开始合成" },
                    onClick = { vm.synthesizeTts() }, enabled = !st.ttsBusy && st.ttsReady && st.ttsText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
                if (st.ttsBusy) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = st.ttsProgress)
                    Text("${st.ttsStage}  ${(st.ttsProgress * 100).roundToInt()}%", fontSize = 12.sp, color = Dim)
                } else Text("全在手机上算，一句要几十秒。", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Dim)
            }
        }

        // ---- 结果 ----
        st.ttsResultFile?.let { f ->
            SmallTitle("合成结果")
            Card(Modifier.fillMaxWidth().padding(Pad2)) {
                Column(Modifier.padding(16.dp)) {
                    Text("%s · %.2f 秒 · 用时 %.1f 秒".format(RefFeatureStore.LABELS[st.ttsEmotion] ?: st.ttsEmotion,
                        st.ttsResultSeconds, st.ttsElapsedMs / 1000.0), fontWeight = FontWeight.Medium)
                    Text("已加入常用语音", fontSize = 12.sp, color = Dim)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(if (clip.isPlaying("res")) "停止" else "试听", onClick = { clip.toggle(ctx, "res", f) }, modifier = Modifier.weight(1f))
                        TextButton("送麦", onClick = { clip.stop(); vm.sendFileToMic(f, st.ttsText) }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary())
                    }
                }
            }
        }

        // ---- 常用语音 ----
        SmallTitle("常用语音  ${st.library.size}/${TtsLibrary.MAX}")
        Card(Modifier.fillMaxWidth().padding(Pad2)) {
            if (st.library.isEmpty()) Text("合成一条就会出现在这里。用得多的一直留着，少用的自动清掉。",
                Modifier.padding(16.dp), fontSize = 13.sp, color = Dim)
            st.library.forEach { e ->
                val key = "lib:" + e.file.name
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.preview, fontSize = 14.sp, maxLines = 2)
                        Text("%s · %.1f 秒 · 用过 %d 次".format(RefFeatureStore.LABELS[e.emotion] ?: e.emotion, e.seconds, e.count),
                            fontSize = 11.sp, color = Dim)
                    }
                    IconButton(onClick = {
                        val was = clip.isPlaying(key); clip.toggle(ctx, key, e.file); if (!was) vm.touchLibrary(e)
                    }) { Icon(if (clip.isPlaying(key)) MiuixIcons.Pause else MiuixIcons.Play, "试听") }
                    TextButton("送麦", onClick = { clip.stop(); vm.touchLibrary(e); vm.sendFileToMic(e.file, e.preview) },
                        colors = ButtonDefaults.textButtonColorsPrimary())
                    IconButton(onClick = { vm.deleteLibrary(e) }) { Icon(MiuixIcons.Delete, "删除", tint = Color(0xFFE5484D)) }
                }
            }
        }
    }
}
