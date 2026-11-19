package com.taocrypt.micmask.ui2

import com.taocrypt.micmask.Prefs

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.absoluteValue
import com.taocrypt.micmask.inject.FxChain
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taocrypt.micmask.MainViewModel
import com.taocrypt.micmask.UiState
import com.taocrypt.micmask.repo.ModelRepo
import com.taocrypt.micmask.ui2.glass.FloatingBottomBar
import com.taocrypt.micmask.ui2.glass.FloatingBottomBarItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------------ 外观设置（存 prefs "ui"）

private val KeyColors = listOf("壁纸取色" to null, "蓝" to Color(0xFF3482FF), "绿" to Color(0xFF36D167),
    "紫" to Color(0xFF7C4DFF), "橙" to Color(0xFFFF5722), "粉" to Color(0xFFE91E63), "青" to Color(0xFF00BCD4))

private class UiPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("ui", 0)
    var mode by mutableIntStateOf(sp.getInt("mode", 0))          // 0 跟随系统 1 浅色 2 深色
    var monet by mutableStateOf(sp.getBoolean("monet", true))
    var key by mutableIntStateOf(sp.getInt("key", 0))
    var glass by mutableStateOf(sp.getBoolean("glass", true))
    fun save() = sp.edit().putInt("mode", mode).putBoolean("monet", monet).putInt("key", key).putBoolean("glass", glass).apply()
    fun controller(): ThemeController {
        val m = when (mode) {
            1 -> if (monet) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
            2 -> if (monet) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
            else -> if (monet) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
        }
        return if (monet) ThemeController(m, keyColor = KeyColors.getOrNull(key)?.second, paletteStyle = ThemePaletteStyle.TonalSpot)
        else ThemeController(m)
    }
}

/** v2 界面：四栏（AI 变声 / 普通变声 / 虚拟声卡 / 设置）+ 二级页，悬浮液态玻璃底栏 */
@Composable
fun V2App(vm: MainViewModel) {
    val ctx = LocalContext.current
    val ui = remember { UiPrefs(ctx) }
    val controller = remember(ui.mode, ui.monet, ui.key) { ui.controller() }
    MiuixTheme(controller = controller) {
        val isLight = MiuixTheme.colorScheme.background.luminance() >= 0.5f
        val window = (ctx as? Activity)?.window
        SideEffect {
            window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = isLight; isAppearanceLightNavigationBars = isLight } }
        }
        val st by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(st.toast) { st.toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.clearToast() } }
        LaunchedEffect(st.error) { st.error?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.clearError() } }

        var sub by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(sub) { com.taocrypt.micmask.L.i("打开页面：${sub ?: "主页"}") }
        BackHandler(enabled = sub != null) { sub = null }

        LaunchedEffect(Unit) { vm.initFx() }
        // 子页面：从右侧推入 + 主页轻微后退变暗；返回时反向（仿 MIUI 页面转场）
        AnimatedContent(
            targetState = sub,
            transitionSpec = {
                val forward = targetState != null
                val dur = 380
                val ease = CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f)
                if (forward) {
                    (slideInHorizontally(tween(dur, easing = ease)) { it } + fadeIn(tween(dur / 2))) togetherWith
                        (slideOutHorizontally(tween(dur, easing = ease)) { -it / 4 } + fadeOut(tween(dur, easing = ease), 0.6f))
                } else {
                    (slideInHorizontally(tween(dur, easing = ease)) { -it / 4 } + fadeIn(tween(dur, easing = ease), 0.6f)) togetherWith
                        (slideOutHorizontally(tween(dur, easing = ease)) { it } + fadeOut(tween(dur / 2)))
                }.apply { targetContentZIndex = if (forward) 1f else 0f }
            },
            label = "sub",
        ) { page ->
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                when (page) {
                    "tts" -> SubPage("文本转语音", { sub = null }) { TtsPage(vm, st) }
                    "file" -> SubPage("文件变声", { sub = null }) { FilePage(vm, st) }
                    "whitelist" -> SubPage("目标应用白名单", { sub = null }) { WhitelistPage(vm) }
                    "theme" -> SubPage("外观", { sub = null }) { ThemePage(ui) }
                    "about" -> SubPage("关于", { sub = null }) { AboutPage(vm, { sub = "logs" }, { sub = "mirrors" }) }
                    "mirrors" -> SubPage("下载镜像", { sub = "about" }) { com.taocrypt.micmask.update.MirrorPage() }
                    "logs" -> SubPage("日志", { sub = null }) { LogPage(vm) }
                    "mim3" -> SubPage("制作效果包", { sub = null }) { Mim3Page(vm, st) }
                    else -> MainTabs(vm, st, ui) { sub = it }
                }
            }
        }
        com.taocrypt.micmask.update.UpdateDialog()
        if (st.importing && st.importTotal > 0) {
            WindowDialog(show = true, title = "正在导入模型", summary = "${st.importDone}/${st.importTotal}  ${st.importName}") {
                LinearProgressIndicator(progress = st.importDone.toFloat() / st.importTotal.coerceAtLeast(1))
            }
        }
    }
}

private val tabSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 260f)

private data class Tab(val title: String, val icon: ImageVector)
private val Tabs = listOf(Tab("变声", MiuixIcons.Mic), Tab("效果", MiuixIcons.Tune), Tab("语音", MiuixIcons.Music), Tab("设置", MiuixIcons.Settings))
private val TabTitles = listOf("变声", "效果", "语音", "设置")

@Composable
private fun MainTabs(vm: MainViewModel, st: UiState, ui: UiPrefs, open: (String) -> Unit) {
    val pager = rememberPagerState { Tabs.size }
    val scope = rememberCoroutineScope()
    val floating = Build.VERSION.SDK_INT >= 33
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
    Scaffold(
        bottomBar = {
            if (!floating) NavigationBar {
                Tabs.forEachIndexed { i, t -> NavigationBarItem(selected = pager.currentPage == i,
                    onClick = { scope.launch { pager.animateScrollToPage(i, animationSpec = tabSpring) } }, icon = t.icon, label = t.title) }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.fillMaxSize().then(if (floating) Modifier.layerBackdrop(backdrop) else Modifier),
                beyondViewportPageCount = 1) { page ->
                val sb = MiuixScrollBehavior()
                Scaffold(modifier = Modifier.graphicsLayer {
                        // 翻页时：离开的页轻微缩小、变淡，进来的页反向，减少「硬切」感
                        val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                        alpha = 1f - off * 0.35f
                        val sc = 1f - off * 0.06f
                        scaleX = sc; scaleY = sc
                    }, topBar = { TopAppBar(title = TabTitles[page], scrollBehavior = sb) },
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)) { pad ->
                    val bottom = if (floating) 100.dp else 16.dp
                    LazyColumn(
                        Modifier.fillMaxSize().overScrollVertical().nestedScroll(sb.nestedScrollConnection),
                        contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = bottom),
                    ) {
                        when (page) {
                            0 -> voicePage(vm, st, open)
                            1 -> soundCardPage(vm, st)
                            2 -> soundBoardPage(vm, st, open)
                            else -> settingsPage(vm, st, open)
                        }
                    }
                }
            }
            if (floating) {
                FloatingBottomBar(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)
                        .clickable(remember { MutableInteractionSource() }, null) {},
                    selectedIndex = { pager.currentPage },
                    onSelected = { i -> scope.launch { pager.animateScrollToPage(i, animationSpec = tabSpring) } },
                    backdrop = backdrop,
                    tabsCount = Tabs.size,
                    isBlurEnabled = ui.glass,
                    isGaussianBlurEnabled = !ui.glass,
                ) {
                    Tabs.forEachIndexed { i, t ->
                        FloatingBottomBarItem(onClick = { scope.launch { pager.animateScrollToPage(i, animationSpec = tabSpring) } },
                            modifier = Modifier.defaultMinSize(minWidth = 72.dp)) {
                            Icon(t.icon, t.title, tint = MiuixTheme.colorScheme.onSurface)
                            Text(t.title, fontSize = 11.sp, lineHeight = 14.sp, color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                        }
                    }
                }
            }
        }
    }
    // AI/音调页 LiveCard 共用 ui 状态，无需额外处理
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        SmallTopAppBar(title = title, navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) { Icon(MiuixIcons.Back, "返回") }
        })
    }) { pad -> Box(Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize()) { content() } }
}

private val PagePad = PaddingValues(horizontal = 12.dp)
private val Summary @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary

// ------------------------------------------------------------------ 主控卡（三栏共用）

/** mode: 0=AI 1=音调 2=声卡（声卡暂复用变调链路，效果器下一版接入） */
@Composable
private fun HeroCard(vm: MainViewModel, st: UiState, mode: Int) {
    val ai = mode == 0
    val mine = st.liveOn && st.liveMode == mode
    val otherMode = st.liveOn && !mine
    val primary = MiuixTheme.colorScheme.primary
    val onPrimary = MiuixTheme.colorScheme.onPrimary
    val cSpec = tween<androidx.compose.ui.graphics.Color>(420)
    val c1 by animateColorAsState(if (mine) primary else MiuixTheme.colorScheme.surfaceContainer, cSpec, label = "c1")
    val c2 by animateColorAsState(if (mine) primary.copy(alpha = 0.65f) else MiuixTheme.colorScheme.surfaceContainer, cSpec, label = "c2")
    val bg = Brush.linearGradient(listOf(c1, c2))
    val fg by animateColorAsState(if (mine) onPrimary else MiuixTheme.colorScheme.onSurface, cSpec, label = "fg")
    val fg2 by animateColorAsState(if (mine) onPrimary.copy(alpha = 0.8f) else Summary, cSpec, label = "fg2")
    Card(Modifier.fillMaxWidth().padding(PagePad).padding(top = 4.dp, bottom = 12.dp).animateContentSize(), insideMargin = PaddingValues(0.dp)) {
        Column(Modifier.background(bg).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(if (mine) onPrimary.copy(alpha = 0.2f) else primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    Icon(listOf(MiuixIcons.Mic, MiuixIcons.Tune, MiuixIcons.Music)[mode], null, tint = if (mine) onPrimary else primary)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(when { mine -> "正在变声"; otherMode -> "另一模式运行中"; else -> "未运行" },
                        fontWeight = FontWeight.Bold, fontSize = 20.sp, color = fg)
                    val target = vm.whitelist()
                    Text(if (st.speakerMode) "外放模式 · 扬声器播放，对方也会听到原声" else if (target.isEmpty()) "未选目标应用 · 仅本机试听" else "目标 ${target.size} 个应用 · ${if (st.liveAec) "通话录音" else "原始麦克风"}",
                        fontSize = 13.sp, color = fg2)
                }
            }
            if (ai) Text("音色 ${if (st.liveVoice.isEmpty()) "未安装" else vm.voiceLabel(st.liveVoice)}" +
                (if (st.liveBench.isNotEmpty()) " · ${st.liveBench}" else ""), fontSize = 13.sp, color = fg2, maxLines = 2)
            if (st.speakerMode && !st.liveOn && mode < 2) {
                Text("未授权 Shizuku，当前为录播模式：按住下方按钮录一句，松手自动转换并设为待发送；到 QQ 按住说话前点悬浮球，从扬声器放出。",
                    fontSize = 12.sp, color = fg2)
                TextButton("授权 Shizuku（切换到实时变声）", onClick = { vm.requestShizuku() }, modifier = Modifier.fillMaxWidth())
                return@Column
            }
            if (st.liveMsg.isNotEmpty() && (mine || !st.liveOn)) Text(st.liveMsg, fontSize = 12.sp, color = fg2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Button(
                onClick = { vm.startMode(mode) },
                enabled = !otherMode && !st.liveBenching,
                modifier = Modifier.fillMaxWidth(),
                colors = if (mine) ButtonDefaults.buttonColors(color = onPrimary) else ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(if (mine) "停止" else "开始", fontWeight = FontWeight.SemiBold, color = if (mine) primary else onPrimary)
            }
        }
    }
    if (st.speakerMode && !st.liveOn && mode < 2) { ClipRecorder(vm, st, mode); return }
    // 按住录制预览（松手回放，上滑取消）
    HoldToRecord(vm, st, enabled = mine)
}

/** 录播（外放模式）：按住录音，松手转换；上滑取消 */
@Composable
private fun ClipRecorder(vm: MainViewModel, st: UiState, mode: Int) {
    val ctx = LocalContext.current
    val primary = MiuixTheme.colorScheme.primary
    var cancel by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(ctx, "需要麦克风权限才能录音", Toast.LENGTH_LONG).show()
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cancelPx = with(density) { 72.dp.toPx() }
    val enabled = !st.clipBusy
    val rec = st.recording
    val bgc by animateColorAsState(when { !enabled -> MiuixTheme.colorScheme.surfaceContainer; cancel -> Color(0xFFE5484D)
        rec -> primary; else -> primary.copy(alpha = 0.12f) }, label = "clipc")
    val fgc = if (rec || cancel) MiuixTheme.colorScheme.onPrimary else if (enabled) primary else Summary
    Box(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 12.dp).height(64.dp)
        .clip(RoundedCornerShape(32.dp)).background(bgc)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                if (!vm.hasMicPermission()) { perm.launch(android.Manifest.permission.RECORD_AUDIO); return@awaitEachGesture }
                cancel = false; vm.clipStart()
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    cancel = (down.position.y - c.position.y) > cancelPx
                    if (!c.pressed) break
                    c.consume()
                }
                if (cancel) vm.clipCancel() else vm.clipStop(mode)
                cancel = false
            }
        }, contentAlignment = Alignment.Center) {
        Text(when {
            st.clipBusy -> "${st.clipStage} ${(st.clipProgress * 100).roundToInt()}%"
            cancel -> "松开取消"
            rec -> "录音中 %d:%02d · 松手转换，上滑取消".format(st.recElapsedMs / 60000, st.recElapsedMs / 1000 % 60)
            else -> "按住 录一句"
        }, color = fgc, fontWeight = FontWeight.SemiBold)
    }
    if (st.clipBusy) LinearProgressIndicator(progress = st.clipProgress, modifier = Modifier.padding(PagePad).padding(bottom = 12.dp))
    st.clipFile?.let { f ->
        Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 12.dp)) {
            SwitchPreference(checked = st.qqFloating, onCheckedChange = { vm.setFloating(it) }, title = "悬浮球",
                summary = "浮在 QQ 上，按住说话前点一下就外放待发送语音")
            ArrowPreference(title = if (st.qqPlaying) "停止外放" else "现在外放一遍",
                summary = "待发送：%s · %.1f 秒".format(st.qqPendingName ?: f.name, st.clipSeconds),
                onClick = { if (st.qqPlaying) vm.stopQqLoop() else vm.playPendingNow() })
        }
    }
}

@Composable
private fun HoldToRecord(vm: MainViewModel, st: UiState, enabled: Boolean) {
    val primary = MiuixTheme.colorScheme.primary
    var pressed by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cancelPx = with(density) { 72.dp.toPx() }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.96f else 1f, label = "hold")
    val bgc by animateColorAsState(when { !enabled -> MiuixTheme.colorScheme.surfaceContainer; cancel -> androidx.compose.ui.graphics.Color(0xFFE5484D)
        pressed -> primary; else -> primary.copy(alpha = 0.12f) }, label = "holdc")
    val fgc = if (pressed || cancel) MiuixTheme.colorScheme.onPrimary else if (enabled) primary else Summary
    Box(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 12.dp).height(56.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp)).background(bgc)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                pressed = true; cancel = false; vm.holdPreview(true)
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    cancel = (down.position.y - c.position.y) > cancelPx
                    if (!c.pressed) break
                    c.consume()
                }
                vm.holdPreview(false, cancel); pressed = false; cancel = false
            }
        }, contentAlignment = Alignment.Center) {
        Text(when { !enabled -> "开始变声后可按住录制预览"; cancel -> "松开取消"; pressed -> "录制中 ${st.previewLeft}s · 松手回放，上滑取消"; else -> "按住 录制预览" },
            color = fgc, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------------------ 卡片网格 / 列表

internal class Tile(val text: String, val sel: Boolean, val accent: Boolean = false, val sub: String? = null,
                    val onLong: (() -> Unit)? = null, val action: (@Composable () -> Unit)? = null, val onClick: () -> Unit)

/** 标题 + 网格/列表切换（全局记忆） */
@Composable
private fun SectionHead(title: String, vm: MainViewModel, st: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallTitle(title, Modifier.weight(1f))
        Text(if (st.gridLayout) "▦ 网格" else "☰ 列表", Modifier.padding(end = 24.dp).clip(RoundedCornerShape(10.dp))
            .clickable { vm.setGridLayout(!st.gridLayout) }.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun TileCell(t: Tile, grid: Boolean, modifier: Modifier) {
    val primary = MiuixTheme.colorScheme.primary
    val bg by animateColorAsState(if (t.sel) primary else MiuixTheme.colorScheme.secondaryContainer, label = "tile")
    val fg = if (t.sel) MiuixTheme.colorScheme.onPrimary else if (t.accent) primary else MiuixTheme.colorScheme.onSecondaryContainer
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(bg)
        .pointerInput(t.text, t.onLong, t.onClick) { detectTapGestures(onTap = { t.onClick() }, onLongPress = { t.onLong?.invoke() }) }) {
        if (grid) Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(t.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.sub != null) Text(t.sub, fontSize = 11.sp, color = fg.copy(alpha = 0.7f), maxLines = 1)
            if (t.action != null) { Spacer(Modifier.height(4.dp)); t.action.invoke() }
        } else Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.sub != null) Text(t.sub, fontSize = 12.sp, color = fg.copy(alpha = 0.7f))
            }
            t.action?.invoke()
        }
    }
}

/** 默认多行多列卡片（3 列）；列表模式一行一个 */
@Composable
private fun TileGroup(tiles: List<Tile>, grid: Boolean, cols: Int = 3) {
    Column(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 4.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (grid) tiles.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t -> TileCell(t, true, Modifier.weight(1f)) }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        } else tiles.forEach { t -> TileCell(t, false, Modifier.fillMaxWidth()) }
    }
}

// ------------------------------------------------------------------ 预设行（内置 + 自存 + 保存/导入）

@Composable
private fun PresetBar(vm: MainViewModel, st: UiState, mode: Int, builtins: List<String>, onBuiltin: (Int) -> Unit) {
    val selected = if (mode == 2) st.fxCardPreset else st.fxPreset
    val mine = st.userPresets[mode].orEmpty()
    var saving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<com.taocrypt.micmask.UserPreset?>(null) }
    val primary = MiuixTheme.colorScheme.primary
    @Composable fun Chip(text: String, sel: Boolean, accent: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        val bg by animateColorAsState(if (sel) primary else MiuixTheme.colorScheme.secondaryContainer, label = "chip")
        Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(bg)
            .pointerInput(text, onLong) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLong?.invoke() }) }
            .padding(horizontal = 16.dp, vertical = 9.dp)) {
            Text(text, fontSize = 14.sp, color = if (sel) MiuixTheme.colorScheme.onPrimary else if (accent) primary else MiuixTheme.colorScheme.onSecondaryContainer)
        }
    }
    val entries = buildList {
        builtins.forEachIndexed { i, n -> add(Tile(n, selected == n) { onBuiltin(i) }) }
        mine.forEach { p -> add(Tile("★ " + p.name, selected == p.name, onLong = { menu = p }) { vm.applyUserPreset(p) }) }
        add(Tile("保存", false, accent = true) { saving = true })
        add(Tile("导入", false, accent = true) { vm.importPresetFromClipboard() })
    }
    TileGroup(entries, st.gridLayout)
    if (mine.isNotEmpty()) Text("长按 ★ 预设可分享、重命名、删除", Modifier.padding(horizontal = 24.dp, vertical = 4.dp), fontSize = 11.sp, color = Summary)
    if (saving) {
        var text by remember { mutableStateOf(if (selected == "自定义" || selected in builtins) "" else selected) }
        WindowDialog(show = true, title = "保存为预设", summary = "保存当前${if (mode == 1) "音调和音色" else "全部旋钮推子"}参数", onDismissRequest = { saving = false }) {
            Column {
                TextField(value = text, onValueChange = { text = it.take(24) }, label = "预设名称", singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("取消", onClick = { saving = false }, modifier = Modifier.weight(1f))
                    TextButton("保存", onClick = { vm.saveCurrentPreset(mode, text); saving = false }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
    menu?.let { p ->
        var text by remember(p) { mutableStateOf(p.name) }
        WindowDialog(show = true, title = p.name, onDismissRequest = { menu = null }) {
            Column {
                TextField(value = text, onValueChange = { text = it.take(24) }, label = "名称", singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("删除", onClick = { vm.deleteUserPreset(mode, p.name); menu = null }, modifier = Modifier.weight(1f))
                    TextButton("分享", onClick = { vm.sharePreset(p); menu = null }, modifier = Modifier.weight(1f))
                    TextButton("保存", onClick = { vm.renameUserPreset(mode, p.name, text); menu = null }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 变声（AI / 音调 双模式）

private fun LazyListScope.voicePage(vm: MainViewModel, st: UiState, open: (String) -> Unit) {
    item(key = "mode_switch") { ModeSwitch(vm, st) }
    if (st.voiceTab == 0) aiPage(vm, st, open) else pitchPage(vm, st)
}

@Composable
private fun ModeSwitch(vm: MainViewModel, st: UiState) {
    val primary = MiuixTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(PagePad).padding(top = 4.dp, bottom = 4.dp)
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.secondaryContainer).padding(4.dp)) {
        listOf("AI 变声", "普通变声").forEachIndexed { i, n ->
            val sel = st.voiceTab == i
            val bg by animateColorAsState(if (sel) primary else androidx.compose.ui.graphics.Color.Transparent, tween(260), label = "seg$i")
            Box(Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(bg)
                .clickable {
                    if (st.voiceTab != i) {
                        vm.setVoiceTab(i)
                        // 运行中切换 = 直接切到另一种变声
                        if (st.liveOn && st.liveMode in 0..1 && st.liveMode != i) vm.startMode(i)
                    }
                }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(n, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    color = if (sel) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

// ------------------------------------------------------------------ AI 变声

private fun LazyListScope.aiPage(vm: MainViewModel, st: UiState, open: (String) -> Unit) {
    item { AiHeader(vm, st) }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row { Text("音高偏移", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("${if (st.aiSemis > 0) "+" else ""}${st.aiSemis.roundToInt()} 半音", color = Summary) }
                Slider(value = st.aiSemis, onValueChange = { vm.setAiSemis(it.roundToInt().toFloat()) }, valueRange = -12f..12f, steps = 23)
                // 一键预设：按「你的声音 → 音色」选，再用滑杆微调
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("男声→女声" to 12f, "同性别" to 0f, "女声→男声" to -12f).forEach { (t, v) ->
                        val on = st.aiSemis.roundToInt() == v.roundToInt()
                        Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)
                            .clickable { vm.setAiSemis(v) }, contentAlignment = Alignment.Center) {
                            Text(t, fontSize = 13.sp, maxLines = 1,
                                color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
                val n = st.aiSemis.roundToInt()
                Text(when {
                    n >= 10 -> "适合男声用女声音色。声音偏低沉可 +12，偏尖可回到 +10"
                    n >= 4 -> "介于两者之间，适合声线偏中性或想要少年/低沉女声的效果"
                    n > -4 -> "音高基本不变，适合与音色同性别。听起来偏闷或偏尖时 ±1~3 微调"
                    n > -10 -> "略降调，适合偏高的男声或想要低沉一些的效果"
                    else -> "适合女声用男声音色"
                }, fontSize = 12.sp, color = Summary, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    item { SmallTitle("本地音色") }
    if (st.liveVoices.isEmpty()) item {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Text("还没有音色。从下方仓库下载，或导入模型 zip。", Modifier.padding(16.dp), color = Summary)
        }
    }
    items(st.liveVoices, key = { "v_$it" }) { v -> VoiceItem(vm, st, v) }
    item { ImportRow(vm, st) }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallTitle("模型仓库", Modifier.weight(1f))
            IconButton(onClick = { vm.refreshRepo() }, enabled = !st.repoLoading, modifier = Modifier.padding(end = 12.dp)) {
                Icon(MiuixIcons.Refresh, "刷新")
            }
        }
    }
    if (st.repoMsg.isNotEmpty() || st.repoLoading) item {
        Text(if (st.repoLoading) "正在获取…" else st.repoMsg, Modifier.padding(horizontal = 28.dp, vertical = 4.dp), fontSize = 13.sp, color = Summary)
    }
    items(st.repoModels, key = { "r_${it.id}" }) { m -> RepoItem(vm, st, m) }
    item { SmallTitle("更多") }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            ArrowPreference(title = "文本转语音（TTS）", summary = "输入文字合成语音，可发到 QQ", onClick = { open("tts") })
        }
    }
}

@Composable
private fun AiHeader(vm: MainViewModel, st: UiState) {
    LaunchedEffect(Unit) { if (st.repoModels.isEmpty() && st.repoUrl.isNotBlank()) vm.refreshRepo() }
    HeroCard(vm, st, 0)
}

@Composable
private fun ImportRow(vm: MainViewModel, st: UiState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importModelFiles(uris)
    }
    Row(Modifier.fillMaxWidth().padding(PagePad).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton("导入模型", onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f))
        TextButton(if (st.liveBenching) "测试中…" else "性能测试", onClick = { vm.runLiveBench() },
            enabled = !st.liveBenching && !st.liveOn, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun VoiceItem(vm: MainViewModel, st: UiState, v: String) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val sel = v == st.liveVoice
    val primary = MiuixTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 8.dp), onClick = { vm.setLiveVoice(v) },
        insideMargin = PaddingValues(0.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = vm.voiceLabel(v)
            Box(Modifier.size(42.dp).clip(CircleShape).background(if (sel) primary else primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Text(label.take(1), fontWeight = FontWeight.Bold, color = if (sel) MiuixTheme.colorScheme.onPrimary else primary)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(label, fontWeight = FontWeight.SemiBold, color = if (sel) primary else MiuixTheme.colorScheme.onSurface)
                Text("${if (sel) "使用中 · " else ""}%.1f MB".format(vm.voiceSizeMb(v)), fontSize = 12.sp, color = Summary)
            }
            IconButton(onClick = { renaming = true }) { Icon(MiuixIcons.Edit, "编辑") }
            IconButton(onClick = { deleting = true }) { Icon(MiuixIcons.Delete, "删除") }
        }
    }
    if (renaming) {
        var text by remember(v) { mutableStateOf(vm.voiceLabel(v)) }
        WindowDialog(show = true, title = "编辑音色名称", onDismissRequest = { renaming = false }) {
            Column {
                TextField(value = text, onValueChange = { text = it }, label = "显示名称", singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("取消", onClick = { renaming = false }, modifier = Modifier.weight(1f))
                    TextButton("保存", onClick = { vm.renameVoice(v, text); renaming = false }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
    if (deleting) {
        WindowDialog(show = true, title = "删除音色", summary = "删除「${vm.voiceLabel(v)}」的全部模型文件？", onDismissRequest = { deleting = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("取消", onClick = { deleting = false }, modifier = Modifier.weight(1f))
                TextButton("删除", onClick = { vm.deleteVoice(v); deleting = false }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

// ------------------------------------------------------------------ 音调变声

// ------------------------------------------------------------------ 效果链参数（音调页、声卡页共用一条链，对三种模式都生效）

private class FxCtl(val label: String, val idx: Int, val min: Float, val max: Float, val unit: String = "") {
    fun norm(v: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f)
    fun denorm(n: Float) = min + (max - min) * n
    fun show(v: Float): String = when (unit) {
        "dB" -> "${if (v > 0) "+" else ""}${v.roundToInt()}dB"
        "Hz" -> if (v <= 25f) "关" else "${v.roundToInt()}Hz"
        "ms" -> "${v.roundToInt()}ms"
        "gate" -> if (v <= -79f) "关" else "${v.roundToInt()}dB"
        else -> "${(norm(v) * 100).roundToInt()}"
    }
}

private val ctlReverb = FxCtl("混响", FxChain.P_REVERB, 0f, 1f)
private val ctlRoom = FxCtl("房间", FxChain.P_ROOM, 0f, 1f)
private val ctlEcho = FxCtl("回声", FxChain.P_ECHO, 0f, 1f)
private val ctlEchoMs = FxCtl("延时", FxChain.P_ECHO_MS, 60f, 600f, "ms")
private val ctlEchoFb = FxCtl("反馈", FxChain.P_ECHO_FB, 0f, 0.85f)
private val ctlChorus = FxCtl("合唱", FxChain.P_CHORUS, 0f, 1f)
private val ctlComp = FxCtl("压缩", FxChain.P_COMP, 0f, 1f)
private val ctlDrive = FxCtl("激励", FxChain.P_DRIVE, 0f, 1f)
private val ctlSub = FxCtl("低音炮", FxChain.P_DUCK, 0f, 1f)
private val ctlGate = FxCtl("噪声门", FxChain.P_GATE, -80f, -30f, "gate")
private val ctlLowCut = FxCtl("低切", FxChain.P_LOWCUT, 20f, 400f, "Hz")
private val ctlLow = FxCtl("低", FxChain.P_LOW, -12f, 12f, "dB")
private val ctlMid = FxCtl("中", FxChain.P_MID, -12f, 12f, "dB")
private val ctlHigh = FxCtl("高", FxChain.P_HIGH, -12f, 12f, "dB")
private val ctlOut = FxCtl("总输出", FxChain.P_OUT, -12f, 12f, "dB")
private val ctlPre = FxCtl("爆音", FxChain.P_PREAMP, 0f, 36f, "dB")
private val ctlCrush = FxCtl("全损", FxChain.P_CRUSH, 0f, 1f)

internal fun fx(vararg kv: Pair<Int, Float>): FloatArray = FxChain.neutral().also { a -> kv.forEach { (k, v) -> a[k] = v } }

internal class VoicePreset(val name: String, val semis: Float, val fx: FloatArray)
internal val pitchPresets = listOf(
    VoicePreset("原声", 0f, fx()),
    VoicePreset("男变女", 5f, fx(FxChain.P_HIGH to 3f, FxChain.P_LOW to -3f, FxChain.P_LOWCUT to 120f)),
    VoicePreset("萝莉", 9f, fx(FxChain.P_HIGH to 5f, FxChain.P_LOW to -6f, FxChain.P_LOWCUT to 180f)),
    VoicePreset("御姐", 3f, fx(FxChain.P_MID to 2f, FxChain.P_REVERB to 0.12f, FxChain.P_COMP to 0.3f)),
    VoicePreset("女变男", -5f, fx(FxChain.P_LOW to 4f, FxChain.P_HIGH to -2f)),
    VoicePreset("大叔", -7f, fx(FxChain.P_LOW to 5f, FxChain.P_HIGH to -4f, FxChain.P_COMP to 0.4f)),
    VoicePreset("怪兽", -12f, fx(FxChain.P_LOW to 6f, FxChain.P_DRIVE to 0.35f, FxChain.P_REVERB to 0.25f, FxChain.P_ROOM to 0.8f)),
    VoicePreset("机器人", 0f, fx(FxChain.P_CHORUS to 1f, FxChain.P_DRIVE to 0.25f, FxChain.P_ECHO to 0.3f, FxChain.P_ECHO_MS to 60f, FxChain.P_ECHO_FB to 0.6f)),
    VoicePreset("电话音", 0f, fx(FxChain.P_LOWCUT to 400f, FxChain.P_HIGH to -12f, FxChain.P_MID to 6f, FxChain.P_DRIVE to 0.2f)),
)

internal val cardPresets = listOf(
    "原声" to fx(),
    "KTV" to fx(FxChain.P_REVERB to 0.45f, FxChain.P_ROOM to 0.65f, FxChain.P_ECHO to 0.18f, FxChain.P_ECHO_MS to 180f, FxChain.P_COMP to 0.35f, FxChain.P_HIGH to 2f),
    "唱歌" to fx(FxChain.P_REVERB to 0.35f, FxChain.P_ROOM to 0.55f, FxChain.P_CHORUS to 0.25f, FxChain.P_COMP to 0.45f, FxChain.P_HIGH to 3f, FxChain.P_LOWCUT to 90f),
    "喊麦" to fx(FxChain.P_ECHO to 0.45f, FxChain.P_ECHO_MS to 320f, FxChain.P_ECHO_FB to 0.45f, FxChain.P_DRIVE to 0.3f, FxChain.P_COMP to 0.7f, FxChain.P_MID to 4f, FxChain.P_REVERB to 0.2f),
    "炸房" to fx(FxChain.P_GATE to -80f, FxChain.P_LOWCUT to 260f, FxChain.P_LOW to 12f, FxChain.P_MID to 12f, FxChain.P_HIGH to 12f, FxChain.P_DRIVE to 0.603f, FxChain.P_COMP to 0.401f, FxChain.P_CHORUS to 0f, FxChain.P_ECHO to 0f, FxChain.P_ECHO_MS to 250f, FxChain.P_ECHO_FB to 0.85f, FxChain.P_REVERB to 0f, FxChain.P_ROOM to 0.5f, FxChain.P_OUT to 12f, FxChain.P_DUCK to 0.798f, FxChain.P_CRUSH to 1f, FxChain.P_PREAMP to 36f),
    "主播" to fx(FxChain.P_GATE to -50f, FxChain.P_LOWCUT to 80f, FxChain.P_COMP to 0.5f, FxChain.P_LOW to 2f, FxChain.P_HIGH to 2f, FxChain.P_REVERB to 0.08f),
    "大厅" to fx(FxChain.P_REVERB to 0.6f, FxChain.P_ROOM to 1f),
)

@Composable
private fun PresetRow(names: List<String>, selected: String, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(PagePad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { i, n ->
            TextButton(n, onClick = { onPick(i) },
                colors = if (selected == n) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
        }
    }
}

@Composable
private fun FxSlider(vm: MainViewModel, st: UiState, c: FxCtl) {
    val v = st.fx[c.idx]
    Column(Modifier.padding(vertical = 6.dp)) {
        Row { Text(c.label, Modifier.weight(1f), fontSize = 14.sp); Text(c.show(v), fontSize = 13.sp, color = Summary) }
        Slider(value = c.norm(v), onValueChange = { vm.setFx(1, c.idx, c.denorm(it)) })
    }
}

private fun LazyListScope.pitchPage(vm: MainViewModel, st: UiState) {
    item { HeroCard(vm, st, 1) }
    item { SectionHead("声线预设", vm, st) }
    item {
        PresetBar(vm, st, 1, pitchPresets.map { it.name }) { i ->
            val p = pitchPresets[i]; vm.setPitchSemis(p.semis); vm.applyFxPreset(1, p.name, p.fx)
        }
    }
    item { SmallTitle("音调") }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad).animateContentSize()) {
            Column(Modifier.padding(16.dp)) {
                Text("音调  ${if (st.pitchSemis > 0) "+" else ""}${st.pitchSemis.roundToInt()} 半音", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Slider(value = st.pitchSemis, onValueChange = { vm.setPitchSemis(it.roundToInt().toFloat()) }, valueRange = -12f..12f, steps = 23)
            }
        }
    }
    item { SmallTitle("音色") }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                FxSlider(vm, st, FxCtl("厚度（低频）", FxChain.P_LOW, -12f, 12f, "dB"))
                FxSlider(vm, st, FxCtl("清晰度（中频）", FxChain.P_MID, -12f, 12f, "dB"))
                FxSlider(vm, st, FxCtl("明亮度（高频）", FxChain.P_HIGH, -12f, 12f, "dB"))
                FxSlider(vm, st, FxCtl("去闷（低切）", FxChain.P_LOWCUT, 20f, 400f, "Hz"))
                FxSlider(vm, st, FxCtl("沙哑（激励）", FxChain.P_DRIVE, 0f, 1f))
                FxSlider(vm, st, FxCtl("空间感（混响）", FxChain.P_REVERB, 0f, 1f))
                FxSlider(vm, st, FxCtl("底噪抑制（噪声门）", FxChain.P_GATE, -80f, -30f, "gate"))
                Text("当前：${st.fxPreset}。只作用于普通变声模式，和声卡、AI 变声互不影响。", fontSize = 12.sp, color = Summary)
            }
        }
    }
}

// ------------------------------------------------------------------ 虚拟声卡

private fun LazyListScope.soundCardPage(vm: MainViewModel, st: UiState) {
    if (st.speakerMode && !st.liveOn) {
        item {
            Card(Modifier.fillMaxWidth().padding(PagePad).padding(top = 4.dp, bottom = 12.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("声卡需要 Shizuku", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("当前是外放模式：没有 Shizuku 授权时，变声和语音改为扬声器外放，声卡效果不可用。授权 Shizuku 后即可使用。",
                        fontSize = 13.sp, color = Summary)
                    Spacer(Modifier.height(10.dp))
                    TextButton("授权 Shizuku", onClick = { vm.requestShizuku() }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
        return
    }
    item { HeroCard(vm, st, 2) }
    item { SoundCardRack(vm, st) }
}

@Composable
private fun KnobRow(vm: MainViewModel, st: UiState, ctls: List<FxCtl>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ctls.forEach { c -> Knob(c.label, c.norm(st.fxCard[c.idx]), size = if (ctls.size > 3) 56.dp else 64.dp, text = c.show(st.fxCard[c.idx]), reset = c.norm(FxChain.neutral()[c.idx])) { vm.setFx(2, c.idx, c.denorm(it)) } }
    }
}

@Composable
private fun SoundCardRack(vm: MainViewModel, st: UiState) {
    Column {
        SectionHead("场景预设", vm, st)
        PresetBar(vm, st, 2, cardPresets.map { it.first }) { i -> vm.applyFxPreset(2, cardPresets[i].first, cardPresets[i].second) }
        SmallTitle("空间")
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KnobRow(vm, st, listOf(ctlReverb, ctlRoom, ctlChorus))
                KnobRow(vm, st, listOf(ctlEcho, ctlEchoMs, ctlEchoFb))
            }
        }
        SmallTitle("动态")
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KnobRow(vm, st, listOf(ctlComp, ctlDrive, ctlSub, ctlPre))
                KnobRow(vm, st, listOf(ctlGate, ctlLowCut, ctlCrush))
            }
        }
        SmallTitle("均衡 / 总线")
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(ctlLow, ctlMid, ctlHigh, ctlOut).forEach { c ->
                    Fader(c.label, c.norm(st.fxCard[c.idx]), text = c.show(st.fxCard[c.idx]), reset = c.norm(FxChain.neutral()[c.idx])) { vm.setFx(2, c.idx, c.denorm(it)) }
                }
            }
        }
        Text("双击旋钮或推子复位。耳返开关在设置里，外放会啸叫，请戴耳机。", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 12.sp, color = Summary)
    }
}

// ------------------------------------------------------------------ 语音包

private fun LazyListScope.soundBoardPage(vm: MainViewModel, st: UiState, open: (String) -> Unit) {
    item(key = "sfx_tabs") { SfxTabs(vm, st) }
    item(key = "sfx_head") { SfxHeader(vm, st, open) }
    val fx = st.sfxTab == 0
    val mine = st.sounds.filter { com.taocrypt.micmask.SoundBoard.isFx(it) == fx }
    item(key = "sfx_title_$fx") { SectionHead(if (fx) "效果包" else "本地音频", vm, st) }
    if (mine.isEmpty()) item(key = "sfx_empty") {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Text(if (fx) "效果包是空的。导入 .mim3 效果包、自己制作一个，或从下方仓库下载。" else "还没有本地音频。导入手机里的音乐或录音。", Modifier.padding(16.dp), color = Summary)
        }
    }
    mine.groupBy { it.pack }.forEach { (pack, list) ->
        if (fx && pack != com.taocrypt.micmask.SoundBoard.PACK_FX) item(key = "sfx_pack_$pack") {
            val info = remember(pack, list.size) { vm.packInfo(pack) }
            SmallTitle(pack + (info?.author?.takeIf { it.isNotBlank() }?.let { " · by $it" } ?: ""))
            info?.description?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(horizontal = 28.dp).padding(bottom = 4.dp), fontSize = 12.sp, color = Summary) }
        }
        item(key = "sfx_grid_${pack}_${st.gridLayout}") { SoundTiles(vm, st, list) }
    }
    if (!fx) return
    item(key = "sfx_repo_title") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallTitle("仓库素材", Modifier.weight(1f))
            IconButton(onClick = { vm.refreshRepo() }, enabled = !st.repoLoading, modifier = Modifier.padding(end = 12.dp)) { Icon(MiuixIcons.Refresh, "刷新") }
        }
    }
    if (st.repoSounds.isEmpty()) item(key = "sfx_repo_empty") {
        Text(if (st.repoLoading) "正在获取…" else if (st.repoUrl.isBlank()) "先在设置里填仓库地址" else "仓库里还没有素材（清单 sounds 字段）",
            Modifier.padding(horizontal = 28.dp, vertical = 4.dp), fontSize = 13.sp, color = Summary)
    }
    items(st.repoSounds, key = { "sfxr_${it.id}" }) { r ->
        val have = st.sounds.any { it.id == r.id }
        Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(r.pack, if (r.size > 0) "%.1f MB".format(r.size / 1048576f) else null, if (have) "已下载" else null).joinToString(" · "),
                        fontSize = 12.sp, color = Summary)
                }
                IconButton(onClick = { vm.downloadRepoSound(r) }, enabled = st.sfxBusy.isEmpty()) {
                    Icon(if (have) MiuixIcons.Refresh else MiuixIcons.Download, "下载")
                }
            }
        }
    }
}

@Composable
private fun SfxTabs(vm: MainViewModel, st: UiState) {
    val primary = MiuixTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(PagePad).padding(top = 4.dp, bottom = 4.dp)
        .clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.secondaryContainer).padding(4.dp)) {
        listOf("效果包", "本地音频").forEachIndexed { i, n ->
            val sel = st.sfxTab == i
            val bg by animateColorAsState(if (sel) primary else Color.Transparent, tween(260), label = "sfxseg$i")
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(bg).clickable { vm.setSfxTab(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text(n, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    color = if (sel) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun SfxHeader(vm: MainViewModel, st: UiState, open: (String) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshSounds(); if (st.repoSounds.isEmpty() && st.repoUrl.isNotBlank() && !st.repoLoading) vm.refreshRepo() }
    val fxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importSounds(uris, fx = true) }
    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importSounds(uris, fx = false) }
    val fx = st.sfxTab == 0
    Card(Modifier.fillMaxWidth().padding(PagePad).padding(top = 4.dp, bottom = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sending = st.sounds.firstOrNull { it.id == st.sfxPlaying }
            Text(when {
                sending != null -> "正在送进麦克风：${sending.name}"
                st.sfxPreview != null -> "试听中（扬声器）：${st.sounds.firstOrNull { it.id == st.sfxPreview }?.name.orEmpty()}"
                else -> "点卡片试听（扬声器），点「送麦」播给对方"
            }, fontWeight = FontWeight.SemiBold)
            Text("送麦会叠加在变声之后，没在变声时自动用声卡模式打开麦克风通道。悬浮窗里点素材也是送麦。", fontSize = 12.sp, color = Summary)
            Row { Text("音量", Modifier.weight(1f), fontSize = 14.sp); Text("${(st.sfxGain * 100).roundToInt()}%", fontSize = 13.sp, color = Summary) }
            Slider(value = st.sfxGain, onValueChange = { vm.setSfxGain((it * 20).roundToInt() / 20f) }, valueRange = 0f..1.5f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("送麦时循环", Modifier.weight(1f), fontSize = 14.sp)
                top.yukonga.miuix.kmp.basic.Switch(checked = st.sfxLoop, onCheckedChange = { vm.setSfxLoop(it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(if (st.sfxBusy.isNotEmpty()) st.sfxBusy else if (fx) "导入效果包 (.mim3)" else "导入本地音频",
                    onClick = { if (fx) fxPicker.launch(arrayOf("*/*")) else localPicker.launch(arrayOf("audio/*")) },
                    enabled = st.sfxBusy.isEmpty(), modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                TextButton("全部停止", onClick = { vm.stopPreview(); vm.stopSound() }, enabled = st.sfxPlaying != null || st.sfxPreview != null, modifier = Modifier.weight(1f))
            }
            if (fx) TextButton("制作效果包（自定义分组，多个 mp3 打包成 .mim3）", onClick = { open("mim3") }, enabled = st.sfxBusy.isEmpty(), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SoundTiles(vm: MainViewModel, st: UiState, list: List<com.taocrypt.micmask.SoundBoard.Sound>) {
    var edit by remember { mutableStateOf<com.taocrypt.micmask.SoundBoard.Sound?>(null) }
    val primary = MiuixTheme.colorScheme.primary
    val tiles = list.map { s ->
        val d = s.durMs / 1000
        val prev = st.sfxPreview == s.id
        val send = st.sfxPlaying == s.id
        Tile((if (prev) "▶ " else "") + s.name, prev, sub = if (send) "送麦中" else "%d:%02d".format(d / 60, d % 60), onLong = { edit = s },
            action = {
                Text(if (send) "■ 停止" else "送麦", Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (send) Color(0xFFE5484D) else primary.copy(alpha = 0.15f))
                    .clickable { vm.playSound(s.id) }.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 12.sp, color = if (send) Color.White else if (prev) MiuixTheme.colorScheme.onPrimary else primary)
            }) { vm.previewSound(s.id) }
    }
    TileGroup(tiles, st.gridLayout)
    if (st.gridLayout || list.isNotEmpty()) Text("长按可重命名或删除", Modifier.padding(horizontal = 24.dp), fontSize = 11.sp, color = Summary)
    edit?.let { s ->
        var text by remember(s.id) { mutableStateOf(s.name) }
        WindowDialog(show = true, title = "编辑素材", onDismissRequest = { edit = null }) {
            Column {
                TextField(value = text, onValueChange = { text = it.take(40) }, label = "名称", singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton("删除", onClick = { vm.deleteSound(s); edit = null }, modifier = Modifier.weight(1f))
                    TextButton("取消", onClick = { edit = null }, modifier = Modifier.weight(1f))
                    TextButton("保存", onClick = { vm.renameSound(s, text); edit = null }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 设置

private fun LazyListScope.settingsPage(vm: MainViewModel, st: UiState, open: (String) -> Unit) {
    item { SmallTitle("变声目标") }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            val n = vm.whitelist().size
            ArrowPreference(title = "目标应用白名单", summary = if (n == 0) "未选择（只做本机试听）" else "已选 $n 个应用", onClick = { open("whitelist") })
            SwitchPreference(checked = st.liveAec, onCheckedChange = { vm.setLiveAec(it) }, title = "通话模式录音",
                summary = "开：回声消除+降噪，外放不串音；关：原始麦克风，音质优先", enabled = !st.liveOn)
            SwitchPreference(checked = st.liveMonitor, onCheckedChange = { vm.setLiveMonitor(it) }, title = "实时耳返",
                summary = "三种模式通用：变声后的声音同时送到耳机（外放会啸叫，请戴耳机）")
            SwitchPreference(checked = st.liveFloat, onCheckedChange = { vm.setLiveFloat(it) }, title = "悬浮窗快捷切换",
                summary = "在游戏/QQ 上切换模式、预设，查看 CPU、温度、内存")
        }
    }
    item { SmallTitle("外观与权限") }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            ArrowPreference(title = "外观", summary = "深浅色、莫奈取色、液态玻璃", onClick = { open("theme") })
            ArrowPreference(title = "Shizuku 授权", summary = st.qqShizuku.ifEmpty { "点此检查/申请" }, onClick = { vm.requestShizuku() })
        }
    }
    item { SmallTitle("模型仓库") }
    item { RepoSetting(vm, st) }
    item { SmallTitle("日志与其他") }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            ArrowPreference(title = "日志", summary = "实时查看、筛选、复制，导出到 下载/MicMask", onClick = { open("logs") })
            ArrowPreference(title = "音频还原", summary = "某个应用突然没声音时点这里：停止变声、还原麦克风，并恢复被禁止播放声音的应用", onClick = { vm.repairAudio() })
            ArrowPreference(title = "文件变声", summary = "整段转换、音色检索、无声段保护、悬浮球录播发送", onClick = { open("file") })
            ArrowPreference(title = "关于 MicMask", summary = "v${com.taocrypt.micmask.BuildConfig.VERSION_NAME} · 检查更新、交流群、致谢", onClick = { open("about") })
        }
    }
}

@Composable
private fun RepoSetting(vm: MainViewModel, st: UiState) {
    var repo by remember(st.repoUrl) { mutableStateOf(st.repoUrl) }
    Card(Modifier.fillMaxWidth().padding(PagePad)) {
        Column(Modifier.padding(16.dp)) {
            TextField(value = repo, onValueChange = { repo = it }, label = "GitHub 仓库地址或 models.json 链接", singleLine = true)
            Spacer(Modifier.height(8.dp))
            TextButton("保存并刷新", onClick = { vm.setRepoUrl(repo); vm.refreshRepo() }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun ThemePage(ui: UiPrefs) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SmallTitle("主题")
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            WindowDropdownPreference(items = listOf("跟随系统", "浅色", "深色"), selectedIndex = ui.mode, title = "深浅色",
                onSelectedIndexChange = { ui.mode = it; ui.save() })
            SwitchPreference(checked = ui.monet, onCheckedChange = { ui.monet = it; ui.save() }, title = "莫奈取色",
                summary = "按壁纸或所选主色生成整套配色")
            if (ui.monet) WindowDropdownPreference(items = KeyColors.map { it.first }, selectedIndex = ui.key, title = "主色",
                onSelectedIndexChange = { ui.key = it; ui.save() })
        }
        SmallTitle("底栏")
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            SwitchPreference(checked = ui.glass, onCheckedChange = { ui.glass = it; ui.save() }, title = "液态玻璃",
                summary = if (Build.VERSION.SDK_INT >= 33) "关闭后改用高斯模糊" else "需要 Android 13 及以上",
                enabled = Build.VERSION.SDK_INT >= 33)
        }
    }
}

@Composable
private fun RepoItem(vm: MainViewModel, st: UiState, m: ModelRepo.RModel) {
    val busy = st.dlId == m.id
    val installed = remember(st.liveVoices, m.id) { vm.repoInstalled(m) }
    Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontWeight = FontWeight.SemiBold)
                    val meta = listOfNotNull(m.author.takeIf { it.isNotBlank() }?.let { "作者 $it" },
                        if (m.totalSize > 0) "%.0f MB".format(m.totalSize / 1048576f) else null,
                        if (installed) "已安装" else null).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = Summary)
                }
                IconButton(onClick = { vm.downloadRepoModel(m) }, enabled = st.dlId == null && !st.liveOn) {
                    Icon(if (installed) MiuixIcons.Refresh else MiuixIcons.Download, if (installed) "重新下载" else "下载")
                }
            }
            if (m.desc.isNotBlank()) Text(m.desc, fontSize = 13.sp, color = Summary)
            if (m.license.isNotBlank()) Text("授权：${m.license}", fontSize = 12.sp, color = Summary)
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = st.dlProgress, modifier = Modifier.padding(end = 8.dp))
                Text("%.0f%%  %s".format(st.dlProgress * 100, st.dlName), fontSize = 12.sp,
                    color = Summary)
            }
        }
    }
}


/** 仿真旋钮：上下拖动调值，270° 行程 */
@Composable
fun Knob(label: String, value: Float, size: Dp = 64.dp, text: String? = null, reset: Float? = null, onChange: (Float) -> Unit) {
    val track = MiuixTheme.colorScheme.secondaryContainer
    val accent = MiuixTheme.colorScheme.primary
    val body = MiuixTheme.colorScheme.surfaceContainerHigh
    val cur by androidx.compose.runtime.rememberUpdatedState(value)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val start = remember { value }
        Canvas(Modifier.size(size).pointerInput(Unit) {
            detectDragGestures { ch, drag -> ch.consume(); onChange((cur - drag.y / 300f + drag.x / 600f).coerceIn(0f, 1f)) }
        }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { onChange(reset ?: start) }) }) {
            val r = this.size.minDimension / 2
            val sw = r * 0.14f
            drawArc(track, 135f, 270f, false, style = Stroke(sw, cap = StrokeCap.Round),
                topLeft = Offset(sw / 2, sw / 2), size = androidx.compose.ui.geometry.Size(r * 2 - sw, r * 2 - sw))
            drawArc(accent, 135f, 270f * value, false, style = Stroke(sw, cap = StrokeCap.Round),
                topLeft = Offset(sw / 2, sw / 2), size = androidx.compose.ui.geometry.Size(r * 2 - sw, r * 2 - sw))
            drawCircle(body, r * 0.68f)
            val a = ((135f + 270f * value) * PI / 180).toFloat()
            drawLine(accent, center + Offset(cos(a), sin(a)) * (r * 0.25f), center + Offset(cos(a), sin(a)) * (r * 0.6f),
                strokeWidth = sw * 0.7f, cap = StrokeCap.Round)
        }
        Text(label, fontSize = 12.sp)
        Text(text ?: "${(value * 100).roundToInt()}", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 调音推子：竖直拖动 */
@Composable
fun Fader(label: String, value: Float, text: String? = null, reset: Float? = null, onChange: (Float) -> Unit) {
    val track = MiuixTheme.colorScheme.secondaryContainer
    val accent = MiuixTheme.colorScheme.primary
    val cap = MiuixTheme.colorScheme.onSurface
    val cur by androidx.compose.runtime.rememberUpdatedState(value)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val start = remember { value }
        Canvas(Modifier.width(44.dp).height(180.dp).pointerInput(Unit) {
            detectVerticalDragGestures { ch, dy -> ch.consume(); onChange((cur - dy / size.height).coerceIn(0f, 1f)) }
        }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { onChange(reset ?: start) }) }) {
            val cx = size.width / 2
            val top = 12f; val bot = size.height - 12f
            drawLine(track, Offset(cx, top), Offset(cx, bot), strokeWidth = 8f, cap = StrokeCap.Round)
            val y = bot - (bot - top) * value
            drawLine(accent, Offset(cx, y), Offset(cx, bot), strokeWidth = 8f, cap = StrokeCap.Round)
            for (i in 0..10) { val ty = top + (bot - top) * i / 10f; drawLine(track, Offset(cx + 10f, ty), Offset(cx + 18f, ty), 2f) }
            drawRoundRect(cap, topLeft = Offset(cx - 18f, y - 10f), size = androidx.compose.ui.geometry.Size(36f, 20f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f))
        }
        Text(label, fontSize = 12.sp)
        Text(text ?: "${(value * 100).roundToInt()}", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}


// ------------------------------------------------------------------ 白名单选择

private class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?, val installed: Long, val system: Boolean)

@Composable
private fun WhitelistPage(vm: MainViewModel) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.main(ctx) }
    var all by remember { mutableStateOf<List<AppInfo>?>(null) }
    var sel by remember { mutableStateOf(vm.whitelist()) }
    var q by remember { mutableStateOf("") }
    // 排序：0=安装时间（新→旧） 1=名称；记住上次选择
    var sort by remember { mutableStateOf(prefs.getInt("wlSort", 0)) }
    var showSys by remember { mutableStateOf(prefs.getBoolean("wlSystem", false)) }
    // 方向：安装时间默认新→旧，名称默认 A→Z；再点一次当前项切换
    var desc by remember { mutableStateOf(prefs.getBoolean("wlDesc", sort == 0)) }
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launchable = pm.queryIntentActivities(launch, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
            pm.getInstalledPackages(0).mapNotNull { pi ->
                val ai = pi.applicationInfo ?: return@mapNotNull null
                if (pi.packageName == ctx.packageName) return@mapNotNull null
                val sys = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                // 默认只列有桌面图标的用户应用；勾选「系统应用」后列出全部（含系统、无图标的），已选中的始终保留
                AppInfo(pi.packageName, ai.loadLabel(pm).toString(),
                    runCatching { ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
                    pi.firstInstallTime, sys || pi.packageName !in launchable)
            }
        }
    }
    val collator = remember { java.text.Collator.getInstance(java.util.Locale.CHINA) }
    Column(Modifier.fillMaxSize()) {
        TextField(value = q, onValueChange = { q = it }, label = "搜索应用", singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val primary = MiuixTheme.colorScheme.primary
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.secondaryContainer).padding(3.dp)) {
                listOf("安装时间", "名称").forEachIndexed { i, t ->
                    val on = sort == i
                    val bg by animateColorAsState(if (on) primary else Color.Transparent, label = "wlsort")
                    Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                        .clickable {
                            if (on) desc = !desc else { sort = i; desc = i == 0 }
                            prefs.edit().putInt("wlSort", sort).putBoolean("wlDesc", desc).apply()
                        },
                        contentAlignment = Alignment.Center) {
                        Text(if (on) t + if (desc) " ↓" else " ↑" else t, fontSize = 13.sp, maxLines = 1, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            Row(Modifier.padding(start = 12.dp).clip(RoundedCornerShape(10.dp))
                .clickable { showSys = !showSys; prefs.edit().putBoolean("wlSystem", showSys).apply() }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state = ToggleableState(showSys), onClick = { showSys = !showSys; prefs.edit().putBoolean("wlSystem", showSys).apply() })
                Text("系统应用", fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
        val src = all
        if (src == null) Text("正在读取应用列表…", Modifier.padding(24.dp))
        else {
            val asc: Comparator<AppInfo> = if (sort == 0) compareBy { it.installed }
                else Comparator { x, y -> collator.compare(x.label, y.label) }
            val byOrder = if (desc) asc.reversed() else asc
            // 已选的置顶，其余按所选方式排
            val list = src.filter { (showSys || !it.system || it.pkg in sel) &&
                    (q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true)) }
                .sortedWith(compareBy<AppInfo> { it.pkg !in sel }.then(byOrder))
            val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA) }
            Text("${list.size} 个应用" + if (sel.isNotEmpty()) " · 已选 ${sel.size} 个置顶" else "", fontSize = 12.sp, color = Summary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.pkg }) { a ->
                    val on = a.pkg in sel
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (a.icon != null) Image(a.icon, null, Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(a.label + if (a.system) " · 系统" else "", fontWeight = FontWeight.Medium)
                            Text("${a.pkg} · ${fmt.format(java.util.Date(a.installed))}", fontSize = 11.sp, color = Summary, maxLines = 1)
                        }
                        Checkbox(state = ToggleableState(on), onClick = {
                            sel = if (!on) sel + a.pkg else sel - a.pkg
                            vm.setWhitelist(sel)
                        })
                    }
                }
            }
        }
    }
}


// ------------------------------------------------------------------ 制作效果包

@Composable
private fun Mim3Page(vm: MainViewModel, st: UiState) {
    val ctx = LocalContext.current
    var group by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var alsoImport by rememberSaveable { mutableStateOf(true) }
    val items = remember { androidx.compose.runtime.mutableStateListOf<com.taocrypt.micmask.SoundBoard.PackItem>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u -> if (items.none { it.uri == u }) items += com.taocrypt.micmask.SoundBoard.PackItem(u, com.taocrypt.micmask.SoundBoard.safe(com.taocrypt.micmask.SoundBoard.displayName(ctx, u)), com.taocrypt.micmask.SoundBoard.audioExt(ctx, u)) }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SmallTitle("包信息") }
        item {
            Card(Modifier.fillMaxWidth().padding(PagePad)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(value = group, onValueChange = { group = it.take(40) }, label = "分组名（导入后显示的分组）", singleLine = true)
                    TextField(value = author, onValueChange = { author = it.take(40) }, label = "创作者", singleLine = true)
                    TextField(value = desc, onValueChange = { desc = it.take(200) }, label = "简介（可选）")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("打包后同时导入本机", Modifier.weight(1f), fontSize = 14.sp)
                        top.yukonga.miuix.kmp.basic.Switch(checked = alsoImport, onCheckedChange = { alsoImport = it })
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallTitle("音效 ${items.size} 个", Modifier.weight(1f))
                TextButton("添加音频", onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.padding(end = 12.dp))
            }
        }
        if (items.isEmpty()) item {
            Text("点「添加音频」选择多个 mp3（也支持 ogg/m4a/wav/flac），文件原样打包不转码。", Modifier.padding(horizontal = 28.dp, vertical = 4.dp), fontSize = 13.sp, color = Summary)
        }
        items(items.size, key = { items[it].uri.toString() }) { i ->
            val it0 = items[i]
            Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 8.dp)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TextField(value = it0.name, onValueChange = { v -> val k = items.indexOfFirst { x -> x.uri == it0.uri }; if (k >= 0) items[k] = it0.copy(name = v.take(40)) },
                            label = "名称 · .${it0.ext}", singleLine = true)
                    }
                    IconButton(onClick = { items.removeAll { x -> x.uri == it0.uri } }) { Icon(MiuixIcons.Delete, "移除") }
                }
            }
        }
        item {
            Column(Modifier.padding(PagePad).padding(vertical = 12.dp)) {
                TextButton(if (st.sfxBusy.isNotEmpty()) st.sfxBusy else "打包为 .mim3",
                    onClick = { vm.buildMim3(group, author, desc, items.toList(), alsoImport) },
                    enabled = st.sfxBusy.isEmpty() && items.isNotEmpty() && group.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary())
                Text("保存到 下载/MicMask/<分组名>.mim3，可直接发给别人导入。格式：zip，内含 manifest.json（分组、创作者、简介、文件清单）和音频本身。",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 12.sp, color = Summary)
            }
        }
    }
}
