package com.taocrypt.micmask.ui2

import com.taocrypt.micmask.tr

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
import androidx.compose.runtime.snapshotFlow
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

private val KeyColors get() = listOf(tr("壁纸取色") to null, tr("蓝") to Color(0xFF3482FF), tr("绿") to Color(0xFF36D167),
    tr("紫") to Color(0xFF7C4DFF), tr("橙") to Color(0xFFFF5722), tr("粉") to Color(0xFFE91E63), tr("青") to Color(0xFF00BCD4))

private class UiPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("ui", 0)
    var mode by mutableIntStateOf(sp.getInt("mode", 0))          // 0 跟随系统 1 浅色 2 深色
    var monet by mutableStateOf(sp.getBoolean("monet", true))
    var key by mutableIntStateOf(sp.getInt("key", 0))
    var glass by mutableStateOf(sp.getBoolean("glass", true))
    var oled by mutableStateOf(sp.getBoolean("oled", false))     // 深色时背景纯黑
    fun save() = sp.edit().putInt("mode", mode).putBoolean("monet", monet).putInt("key", key).putBoolean("glass", glass).putBoolean("oled", oled).apply()
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

/** 深色 + 纯黑开启时，把背景/表面换成 #000，卡片用极深灰保持层次（OLED 省电） */
@Composable
private fun OledTheme(controller: ThemeController, oled: Boolean, content: @Composable () -> Unit) {
    MiuixTheme(controller = controller) {
        val base = MiuixTheme.colorScheme
        if (oled && base.background.luminance() < 0.5f) {
            val black = Color.Black
            MiuixTheme(colors = base.copy(background = black, surface = black, surfaceVariant = black,
                surfaceContainer = Color(0xFF121212), surfaceContainerHigh = Color(0xFF1A1A1A),
                surfaceContainerHighest = Color(0xFF222222)), content = content)
        } else content()
    }
}

/** v2 界面：四栏（AI 变声 / 普通变声 / 虚拟声卡 / 设置）+ 二级页，悬浮液态玻璃底栏 */
@Composable
fun V2App(vm: MainViewModel) {
    val ctx = LocalContext.current
    val ui = remember { UiPrefs(ctx) }
    val controller = remember(ui.mode, ui.monet, ui.key) { ui.controller() }
    OledTheme(controller, ui.oled) {
        val isLight = MiuixTheme.colorScheme.background.luminance() >= 0.5f
        val window = (ctx as? Activity)?.window
        SideEffect {
            window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = isLight; isAppearanceLightNavigationBars = isLight } }
        }
        val st by vm.state.collectAsStateWithLifecycle()
        // toast 改由 MainViewModel 统一弹出（后台/悬浮窗也显示）
        LaunchedEffect(st.error) { st.error?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.clearError() } }

        var sub by rememberSaveable { mutableStateOf<String?>(null) }
        // 主页当前 tab 提到外层：进二级页再返回时 MainTabs 会重建，靠它回到原来的 tab
        var mainPage by rememberSaveable { mutableStateOf(0) }
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
                    "tts" -> SubPage(tr("文字转语音"), { sub = null }) { TtsPage(vm, st) }
                    "file" -> SubPage(tr("文件变声"), { sub = null }) { FilePage(vm, st) }
                    "whitelist" -> SubPage(tr("目标应用"), { sub = null }) { WhitelistPage(vm) }
                    "theme" -> SubPage(tr("外观"), { sub = null }) { ThemePage(ui) }
                    "about" -> SubPage(tr("关于"), { sub = null }) { AboutPage(vm, { sub = "logs" }, { sub = "mirrors" }, { sub = "disclaimer" }, { sub = "refs" }) }
                    "disclaimer" -> SubPage(tr("免责声明"), { sub = "about" }) { DisclaimerPage() }
                    "refs" -> SubPage(tr("参考项目"), { sub = "about" }) { RefsPage() }
                    "mirrors" -> SubPage(tr("下载镜像"), { sub = "about" }) { com.taocrypt.micmask.update.MirrorPage() }
                    "logs" -> SubPage(tr("日志"), { sub = null }) { LogPage(vm) }
                    "mim3" -> SubPage(tr("制作音效包"), { sub = null }) { Mim3Page(vm, st) }
                    "feedback" -> SubPage(tr("反馈问题"), { sub = null }) { FeedbackPage(st.aiTier, st.speakerMode) }
                    "privacy" -> SubPage(tr("隐私说明"), { sub = null }) { PrivacyPage() }
                    else -> MainTabs(vm, st, ui, mainPage, { mainPage = it }) { sub = it }
                }
            }
        }
        com.taocrypt.micmask.update.UpdateDialog(st.liveOn)
        if (st.importing && st.importTotal > 0) {
            WindowDialog(show = true, title = tr("正在导入模型"), summary = "${st.importDone}/${st.importTotal}  ${st.importName}") {
                LinearProgressIndicator(progress = st.importDone.toFloat() / st.importTotal.coerceAtLeast(1))
            }
        }
        // 首启同意弹窗：等导入弹窗、更新弹窗都结束再弹（启动后稍等一下，让启动时的更新检查先出结果）
        val updShow by com.taocrypt.micmask.update.Updater.dialog.collectAsStateWithLifecycle()
        val updForced by com.taocrypt.micmask.update.Updater.forced.collectAsStateWithLifecycle()
        var consentNeed by remember { mutableStateOf(com.taocrypt.micmask.BeaconHub.consentNeeded(ctx)) }
        var settled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(1500); settled = true }
        val otherDialog = st.importing || updShow || updForced != null
        BeaconConsentDialog(show = consentNeed && settled && !otherDialog) { consentNeed = false }
        // dialog 公告：同意之后、变声中不弹
        BeaconNoticeDialogs(blocked = !settled || consentNeed || otherDialog || st.liveOn)
    }
}

private val tabSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 260f)

private data class Tab(val title: String, val icon: ImageVector)
private val Tabs get() = listOf(Tab(tr("变声"), MiuixIcons.Mic), Tab(tr("效果"), MiuixIcons.Tune), Tab(tr("语音"), MiuixIcons.Music), Tab(tr("设置"), MiuixIcons.Settings))
private val TabTitles get() = listOf(tr("变声"), tr("效果"), tr("语音"), tr("设置"))

@Composable
private fun MainTabs(vm: MainViewModel, st: UiState, ui: UiPrefs, page0: Int, onPage: (Int) -> Unit, open: (String) -> Unit) {
    val pager = rememberPagerState(initialPage = page0.coerceIn(0, Tabs.size - 1)) { Tabs.size }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { onPage(it) } }
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
                    // 用 targetPage：跨两页切换时 currentPage 会经过中间页，玻璃条拿到中间页会回调 onSelected 把翻页截停在中间
                    selectedIndex = { pager.targetPage },
                    onSelected = { i -> if (i != pager.targetPage) scope.launch { pager.animateScrollToPage(i, animationSpec = tabSpring) } },
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
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) { Icon(MiuixIcons.Back, tr("返回")) }
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
                    Text(when { mine -> tr("正在变声"); otherMode -> tr("另一模式运行中"); else -> tr("未运行") },
                        fontWeight = FontWeight.Bold, fontSize = 20.sp, color = fg)
                    val target = vm.whitelist()
                    Text(if (st.speakerMode) tr("外放模式 · 扬声器播放，对方也会听到原声") else if (target.isEmpty()) tr("未选目标应用 · 仅本机试听") else tr("目标 {0} 个应用 · {1}", target.size, if (st.liveAec) tr("通话录音") else tr("原始麦克风")),
                        fontSize = 13.sp, color = fg2)
                }
            }
            if (ai) Text(tr("音色 {0}", if (st.liveVoice.isEmpty()) tr("未安装") else vm.voiceLabel(st.liveVoice)) +
                (if (st.voicePending.isNotEmpty()) " · " + tr("正在切换到 {0}…", vm.voiceLabel(st.voicePending))
                 else if (st.liveBench.isNotEmpty()) " · ${st.liveBench}" else ""), fontSize = 13.sp, color = fg2, maxLines = 2)
            if (st.speakerMode && !st.liveOn && mode < 2) {
                Text(tr("普通模式（录播）：按住下方按钮录一句，松手自动转换并从扬声器播放；悬浮窗里也能按住录音。"),
                    fontSize = 12.sp, color = fg2)
                TextButton(tr("授权 Shizuku（切换到实时变声）"), onClick = { vm.setNormalMode(false) }, modifier = Modifier.fillMaxWidth())
                return@Column
            }
            if (st.liveMsg.isNotEmpty() && (mine || !st.liveOn)) Text(st.liveMsg, fontSize = 12.sp, color = fg2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Button(
                onClick = { vm.startMode(mode) },
                enabled = !otherMode && !st.liveBenching,
                modifier = Modifier.fillMaxWidth(),
                colors = if (mine) ButtonDefaults.buttonColors(color = onPrimary) else ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(if (mine) tr("停止") else tr("开始"), fontWeight = FontWeight.SemiBold, color = if (mine) primary else onPrimary)
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
        if (!ok) Toast.makeText(ctx, tr("需要麦克风权限才能录音"), Toast.LENGTH_LONG).show()
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
            cancel -> tr("松开取消")
            rec -> tr("录音中 %d:%02d · 松手转换，上滑取消").format(st.recElapsedMs / 60000, st.recElapsedMs / 1000 % 60)
            else -> tr("按住 录一句")
        }, color = fgc, fontWeight = FontWeight.SemiBold)
    }
    if (st.clipBusy) LinearProgressIndicator(progress = st.clipProgress, modifier = Modifier.padding(PagePad).padding(bottom = 12.dp))
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
        Text(when { !enabled -> tr("开始变声后可按住录制预览"); cancel -> tr("松开取消"); pressed -> tr("录制中 {0}s · 松手回放，上滑取消", st.previewLeft)
            st.previewPhase == 2 -> tr("转换中…"); st.previewPhase == 3 -> tr("播放中 {0}s · 按住重录", st.previewLeft); else -> tr("按住 录制预览") },
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
        Text(if (st.gridLayout) tr("▦ 网格") else tr("☰ 列表"), Modifier.padding(end = 24.dp).clip(RoundedCornerShape(10.dp))
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
        builtins.forEachIndexed { i, n -> add(Tile(tr(n), selected == n) { onBuiltin(i) }) }
        mine.forEach { p -> add(Tile("★ " + p.name, selected == p.name, onLong = { menu = p }) { vm.applyUserPreset(p) }) }
        add(Tile(tr("保存"), false, accent = true) { saving = true })
        add(Tile(tr("导入"), false, accent = true) { vm.importPresetFromClipboard() })
    }
    TileGroup(entries, st.gridLayout)
    if (mine.isNotEmpty()) Text(tr("长按 ★ 预设可分享、重命名、删除"), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), fontSize = 11.sp, color = Summary)
    if (saving) {
        var text by remember { mutableStateOf(if (selected == "自定义" || selected in builtins) "" else selected) }
        WindowDialog(show = true, title = tr("保存为预设"), summary = tr("保存当前{0}参数", if (mode == 1) tr("音调和音色") else tr("全部旋钮推子")), onDismissRequest = { saving = false }) {
            Column {
                TextField(value = text, onValueChange = { text = it.take(24) }, label = tr("预设名称"), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(tr("取消"), onClick = { saving = false }, modifier = Modifier.weight(1f))
                    TextButton(tr("保存"), onClick = { vm.saveCurrentPreset(mode, text); saving = false }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
    menu?.let { p ->
        var text by remember(p) { mutableStateOf(p.name) }
        WindowDialog(show = true, title = p.name, onDismissRequest = { menu = null }) {
            Column {
                TextField(value = text, onValueChange = { text = it.take(24) }, label = tr("名称"), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(tr("删除"), onClick = { vm.deleteUserPreset(mode, p.name); menu = null }, modifier = Modifier.weight(1f))
                    TextButton(tr("分享"), onClick = { vm.sharePreset(p); menu = null }, modifier = Modifier.weight(1f))
                    TextButton(tr("保存"), onClick = { vm.renameUserPreset(mode, p.name, text); menu = null }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 变声（AI / 音调 双模式）

private fun LazyListScope.voicePage(vm: MainViewModel, st: UiState, open: (String) -> Unit) {
    if (com.taocrypt.micmask.BeaconHub.banners.isNotEmpty()) item(key = "beacon_banners") { BeaconBanners() }
    item(key = "mode_switch") { ModeSwitch(vm, st) }
    if (st.voiceTab == 0) aiPage(vm, st, open) else pitchPage(vm, st)
    item(key = "slot_home_bottom") { BeaconSlotCard("home_bottom", st.liveOn, Modifier.fillMaxWidth().padding(PagePad).padding(top = 12.dp)) }
}

@Composable
private fun ModeSwitch(vm: MainViewModel, st: UiState) {
    val primary = MiuixTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(PagePad).padding(top = 4.dp, bottom = 4.dp)
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.secondaryContainer).padding(4.dp)) {
        listOf(tr("AI 变声"), tr("普通变声")).forEachIndexed { i, n ->
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

@Composable
private fun GainBody(vm: MainViewModel, mode: Int, db: Float) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("音量增益"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("${if (db > 0) "+" else ""}${db.roundToInt()} dB", color = Summary)
                if (db.roundToInt() != 0) Text(tr("  重置"), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clickable { vm.setGain(mode, 0f) })
            }
            Slider(value = db, onValueChange = { vm.setGain(mode, it.roundToInt().toFloat()) },
                valueRange = com.taocrypt.micmask.GAIN_MIN..com.taocrypt.micmask.GAIN_MAX, steps = 29)
            Text(tr("对方听着太小声就调高；声音发炸、破音就调低。开着变声也能实时调。"), fontSize = 12.sp, color = Summary)
}

/** AI 实时变声「高级」：默认折叠 */
@Composable
private fun AiAdvanced(vm: MainViewModel, st: UiState) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(tr("高级"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        val custom = st.aiTier != 0 || st.aiGate != -50f || st.aiFeatTh != 3 || st.aiSynTh != 4
        if (!open && custom) Text(tr("已自定义  "), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
        Text(if (open) tr("收起 ▴") else tr("展开 ▾"), fontSize = 13.sp, color = Summary)
    }
    if (!open) return
    Text(tr("档位"), Modifier.padding(top = 8.dp), fontSize = 14.sp)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(tr("自动"), tr("标准 0.3s"), tr("省电 0.5s")).forEachIndexed { i, t ->
            val on = st.aiTier == i
            Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp))
                .background(if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)
                .clickable { vm.setAiTune(tier = i); vm.commitAiTune() }, contentAlignment = Alignment.Center) {
                Text(t, fontSize = 13.sp, maxLines = 1,
                    color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
    Text(when (st.aiTier) { 1 -> tr("延迟最低；手机跟不上会断音，且不会自动降档"); 2 -> tr("延迟多约 0.2 秒，算力省约 40%，发热少"); else -> tr("按测速选能跑稳的最低延迟档，频繁断音时自动降到省电") },
        fontSize = 12.sp, color = Summary, modifier = Modifier.padding(top = 6.dp))

    Row(Modifier.padding(top = 14.dp)) { Text(tr("静音门限"), Modifier.weight(1f), fontSize = 14.sp); Text("${st.aiGate.roundToInt()} dB", color = Summary) }
    Slider(value = st.aiGate, onValueChange = { vm.setAiTune(gate = it.roundToInt().toFloat()) }, valueRange = -70f..-30f, steps = 39,
        onValueChangeFinished = { vm.commitAiTune() })
    Text(tr("低于它的声音不送进模型（省电、去底噪）。开头字被吞就调低，环境吵、有杂音就调高。默认 -50"), fontSize = 12.sp, color = Summary)

    val cores = Runtime.getRuntime().availableProcessors()
    Row(Modifier.padding(top = 14.dp)) { Text(tr("特征线程"), Modifier.weight(1f), fontSize = 14.sp); Text("${st.aiFeatTh}", color = Summary) }
    Slider(value = st.aiFeatTh.toFloat(), onValueChange = { vm.setAiTune(feat = it.roundToInt()) }, valueRange = 1f..8f, steps = 6,
        onValueChangeFinished = { vm.commitAiTune() })
    Row(Modifier.padding(top = 8.dp)) { Text(tr("合成线程"), Modifier.weight(1f), fontSize = 14.sp); Text("${st.aiSynTh}", color = Summary) }
    Slider(value = st.aiSynTh.toFloat(), onValueChange = { vm.setAiTune(syn = it.roundToInt()) }, valueRange = 1f..8f, steps = 6,
        onValueChangeFinished = { vm.commitAiTune() })
    Text(tr("本机 {0} 核。两者同时在跑，加起来别超过大核数；默认 3 / 4。改档位或线程会重新测速加载，变声中会停顿几秒", cores),
        fontSize = 12.sp, color = Summary)
    Text(tr("恢复默认"), Modifier.padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.resetAiTune() }.padding(vertical = 4.dp),
        fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
}

// ------------------------------------------------------------------ AI 变声

private fun LazyListScope.aiPage(vm: MainViewModel, st: UiState, open: (String) -> Unit) {
    item { AiHeader(vm, st) }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row { Text(tr("音高偏移"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(tr("{0}{1} 半音", if (st.aiSemis > 0) "+" else "", st.aiSemis.roundToInt()), color = Summary) }
                Slider(value = st.aiSemis, onValueChange = { vm.setAiSemis(it.roundToInt().toFloat()) }, valueRange = -12f..12f, steps = 23)
                // 一键预设：按「你的声音 → 音色」选，再用滑杆微调
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("男声→女声") to 12f, tr("同性别") to 0f, tr("女声→男声") to -12f).forEach { (t, v) ->
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
                    n >= 10 -> tr("适合男声用女声音色。声音偏低沉可 +12，偏尖可回到 +10")
                    n >= 4 -> tr("介于两者之间，适合声线偏中性或想要少年/低沉女声的效果")
                    n > -4 -> tr("音高基本不变，适合与音色同性别。听起来偏闷或偏尖时 ±1~3 微调")
                    n > -10 -> tr("略降调，适合偏高的男声或想要低沉一些的效果")
                    else -> tr("适合女声用男声音色")
                }, fontSize = 12.sp, color = Summary, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(18.dp))
                GainBody(vm, 0, st.aiGain)
                AiAdvanced(vm, st)
            }
        }
    }
    item { SmallTitle(tr("本地音色")) }
    if (st.liveVoices.isEmpty()) item {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Text(tr("还没有音色。从下方仓库下载，或导入模型 zip。"), Modifier.padding(16.dp), color = Summary)
        }
    }
    if (st.liveVoices.isNotEmpty()) item(key = "voices") {
        ReorderList(st.liveVoices, key = { it }, onMove = { a, b -> vm.moveVoice(a, b) }) { v, handle, _ -> VoiceItem(vm, st, v, handle) }
    }
    item { ImportRow(vm, st) }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallTitle(tr("模型仓库"), Modifier.weight(1f))
            IconButton(onClick = { vm.refreshRepo() }, enabled = !st.repoLoading, modifier = Modifier.padding(end = 12.dp)) {
                Icon(MiuixIcons.Refresh, tr("刷新"))
            }
        }
    }
    if (st.repoMsg.isNotEmpty() || st.repoLoading) item {
        Text(if (st.repoLoading) tr("正在获取…") else st.repoMsg, Modifier.padding(horizontal = 28.dp, vertical = 4.dp), fontSize = 13.sp, color = Summary)
    }
    if (st.repoModels.isNotEmpty()) item { RepoTabs(vm, st) }
    val shown = repoFiltered(st).filter { it.gender == st.repoTab }
    if (st.repoModels.isNotEmpty() && shown.isEmpty()) item {
        Text(if (st.repoQuery.isNotBlank()) tr("没有找到「{0}」", st.repoQuery.trim()) else tr("这个分类下还没有模型"), Modifier.padding(horizontal = 28.dp, vertical = 8.dp), fontSize = 13.sp, color = Summary)
    }
    // 推荐位 repo_list：第 3 个音色之后插一条（不足 3 个不插），样式同音色项，带「推广」
    val slotAt = 3
    items(shown.take(slotAt), key = { "r_${it.id}" }) { m -> RepoItem(vm, st, m) }
    if (shown.size >= slotAt) item(key = "slot_repo_list") {
        BeaconSlotRepoItem("repo_list", st.liveOn, Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 8.dp))
    }
    items(shown.drop(slotAt), key = { "r_${it.id}" }) { m -> RepoItem(vm, st, m) }
    item { SmallTitle(tr("更多")) }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            ArrowPreference(title = tr("文件变声"), summary = tr("导入或录制音频整段转换，基于 RVC 合成"), onClick = { open("file") })
            ArrowPreference(title = tr("文字转语音"), summary = tr("输入文字合成语音，基于 GPT-SoVITS 合成"), onClick = { open("tts") })
        }
    }
}

/** 搜索词 + 仓库筛选后的仓库模型（分类之前） */
private fun repoFiltered(st: UiState): List<com.taocrypt.micmask.repo.ModelRepo.RModel> {
    val q = st.repoQuery.trim()
    return st.repoModels.filter { m ->
        (st.repoFilter.isEmpty() || m.repo == st.repoFilter) &&
            (q.isEmpty() || m.name.contains(q, true) || m.id.contains(q, true) || m.author.contains(q, true) || m.desc.contains(q, true))
    }
}

/** 模型仓库：搜索框、按仓库筛选（多于一个仓库时）、女声 / 男声 / 其他分类（各带数量） */
@Composable
private fun RepoTabs(vm: MainViewModel, st: UiState) {
    TextField(value = st.repoQuery, onValueChange = { vm.setRepoQuery(it) }, label = tr("搜索模型"), singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
    val srcs = st.repoModels.map { it.repo }.distinct()
    if (srcs.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (u in listOf("") + srcs) {
            val sel = st.repoFilter == u
            val label = if (u.isEmpty()) tr("全部仓库") else st.repos.firstOrNull { it.url == u }?.label ?: u.substringAfter("://")
            Text(label, Modifier.clip(RoundedCornerShape(12.dp))
                .background(if (sel) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.surfaceContainer)
                .clickable { vm.setRepoFilter(u) }.padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 13.sp, maxLines = 1, color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainer)
        }
    }
    val base = repoFiltered(st)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, label) in listOf("male" to tr("男声"), "female" to tr("女声"), "anime" to tr("动漫"), "other" to tr("其他"))) {
            val sel = st.repoTab == k
            val n = base.count { it.gender == k }
            Text("$label $n", Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .background(if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer)
                .clickable { vm.setRepoTab(k) }.padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 14.sp,
                fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                color = if (sel) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceContainer)
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
        TextButton(tr("导入模型"), onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f))
        TextButton(if (st.liveBenching) tr("测试中…") else tr("性能测试"), onClick = { vm.runLiveBench() },
            enabled = !st.liveBenching && !st.liveOn, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun VoiceItem(vm: MainViewModel, st: UiState, v: String, handle: Modifier = Modifier) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val sel = v == st.liveVoice
    val primary = MiuixTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 8.dp), onClick = { vm.setLiveVoice(v) },
        insideMargin = PaddingValues(0.dp)) {
        Row(Modifier.padding(start = 2.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = vm.voiceLabel(v)
            val pinned = v in st.voicePins
            // 拖动把手：按住上下拖排序
            Text("⋮⋮", handle.padding(horizontal = 8.dp, vertical = 10.dp), fontSize = 16.sp, color = Summary)
            Box(Modifier.size(42.dp).clip(CircleShape).background(if (sel) primary else primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Text(label.take(1), fontWeight = FontWeight.Bold, color = if (sel) MiuixTheme.colorScheme.onPrimary else primary)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(label, fontWeight = FontWeight.SemiBold, color = if (sel) primary else MiuixTheme.colorScheme.onSurface)
                val compat = remember(v, st.liveVoices) { vm.isCompatVoice(v) }
                val loading = v == st.voicePending
                if (loading) {
                    Text(tr("正在切换 · {0}", st.voiceStage.ifEmpty { tr("准备中…") }), fontSize = 12.sp, color = primary)
                    Spacer(Modifier.height(6.dp))
                    top.yukonga.miuix.kmp.basic.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                } else
                Text("${if (compat) tr("兼容模式 · ") else ""}${if (pinned) tr("已置顶 · ") else ""}${if (sel) tr("使用中 · ") else ""}%.1f MB".format(vm.voiceSizeMb(v)), fontSize = 12.sp, color = Summary)
            }
            Text(if (pinned) tr("取消置顶") else tr("置顶"), Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.togglePinVoice(v) }
                .padding(horizontal = 6.dp, vertical = 6.dp), fontSize = 13.sp, color = primary)
            IconButton(onClick = { renaming = true }) { Icon(MiuixIcons.Edit, tr("编辑")) }
            IconButton(onClick = { deleting = true }) { Icon(MiuixIcons.Delete, tr("删除")) }
        }
    }
    if (renaming) {
        var text by remember(v) { mutableStateOf(vm.voiceLabel(v)) }
        WindowDialog(show = true, title = tr("编辑音色名称"), onDismissRequest = { renaming = false }) {
            Column {
                TextField(value = text, onValueChange = { text = it }, label = tr("显示名称"), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(tr("取消"), onClick = { renaming = false }, modifier = Modifier.weight(1f))
                    TextButton(tr("保存"), onClick = { vm.renameVoice(v, text); renaming = false }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
    if (deleting) {
        WindowDialog(show = true, title = tr("删除音色"), summary = tr("删除「{0}」的全部模型文件？", vm.voiceLabel(v)), onDismissRequest = { deleting = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(tr("取消"), onClick = { deleting = false }, modifier = Modifier.weight(1f))
                TextButton(tr("删除"), onClick = { vm.deleteVoice(v); deleting = false }, modifier = Modifier.weight(1f),
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
        "Hz" -> if (v <= 25f) tr("关") else "${v.roundToInt()}Hz"
        "ms" -> "${v.roundToInt()}ms"
        "gate" -> if (v <= -79f) tr("关") else "${v.roundToInt()}dB"
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
        Row { Text(tr(c.label), Modifier.weight(1f), fontSize = 14.sp); Text(c.show(v), fontSize = 13.sp, color = Summary) }
        Slider(value = c.norm(v), onValueChange = { vm.setFx(1, c.idx, c.denorm(it)) })
    }
}

private fun LazyListScope.pitchPage(vm: MainViewModel, st: UiState) {
    item { HeroCard(vm, st, 1) }
    item { SectionHead(tr("声线预设"), vm, st) }
    item {
        PresetBar(vm, st, 1, pitchPresets.map { it.name }) { i ->
            val p = pitchPresets[i]; vm.setPitchSemis(p.semis); vm.applyFxPreset(1, p.name, p.fx)
        }
    }
    item { SmallTitle(tr("音调与音量")) }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad).animateContentSize()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("音调"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(tr("{0}{1} 半音", if (st.pitchSemis > 0) "+" else "", st.pitchSemis.roundToInt()), color = Summary)
                    // 重置回当前预设的音调（内置或自建预设），没有对应预设时回 0
                    val baseSemis = pitchPresets.firstOrNull { it.name == st.fxPreset }?.semis
                        ?: st.userPresets[1].orEmpty().firstOrNull { it.name == st.fxPreset }?.semis ?: 0f
                    if (st.pitchSemis.roundToInt() != baseSemis.roundToInt()) Text(tr("  重置"), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable { vm.setPitchSemis(baseSemis) })
                }
                Slider(value = st.pitchSemis, onValueChange = { vm.setPitchSemis(it.roundToInt().toFloat()) }, valueRange = -12f..12f, steps = 23)
                Spacer(Modifier.height(14.dp))
                GainBody(vm, 1, st.pitchGain)
            }
        }
    }
    item { SmallTitle(tr("音色")) }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                FxSlider(vm, st, FxCtl(tr("厚度（低频）"), FxChain.P_LOW, -12f, 12f, "dB"))
                FxSlider(vm, st, FxCtl(tr("清晰度（中频）"), FxChain.P_MID, -12f, 12f, "dB"))
                FxSlider(vm, st, FxCtl(tr("明亮度（高频）"), FxChain.P_HIGH, -12f, 12f, "dB"))
                FxSlider(vm, st, FxCtl(tr("去闷（低切）"), FxChain.P_LOWCUT, 20f, 400f, "Hz"))
                FxSlider(vm, st, FxCtl(tr("沙哑（激励）"), FxChain.P_DRIVE, 0f, 1f))
                FxSlider(vm, st, FxCtl(tr("空间感（混响）"), FxChain.P_REVERB, 0f, 1f))
                FxSlider(vm, st, FxCtl(tr("底噪抑制（噪声门）"), FxChain.P_GATE, -80f, -30f, "gate"))
                Text(tr("当前：{0}。只作用于普通变声模式，和声卡、AI 变声互不影响。", tr(st.fxPreset)), fontSize = 12.sp, color = Summary)
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
                    Text(tr("声卡需要 Shizuku"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(tr("当前是外放模式：没有 Shizuku 授权时，变声和语音改为扬声器外放，声卡效果不可用。授权 Shizuku 后即可使用。"),
                        fontSize = 13.sp, color = Summary)
                    Spacer(Modifier.height(10.dp))
                    TextButton(tr("授权 Shizuku"), onClick = { vm.requestShizuku() }, modifier = Modifier.fillMaxWidth(),
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
        ctls.forEach { c -> Knob(tr(c.label), c.norm(st.fxCard[c.idx]), size = if (ctls.size > 3) 56.dp else 64.dp, text = c.show(st.fxCard[c.idx]), reset = c.norm(FxChain.neutral()[c.idx])) { vm.setFx(2, c.idx, c.denorm(it)) } }
    }
}

@Composable
private fun SoundCardRack(vm: MainViewModel, st: UiState) {
    Column {
        SectionHead(tr("场景预设"), vm, st)
        PresetBar(vm, st, 2, cardPresets.map { it.first }) { i -> vm.applyFxPreset(2, cardPresets[i].first, cardPresets[i].second) }
        SmallTitle(tr("空间"))
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KnobRow(vm, st, listOf(ctlReverb, ctlRoom, ctlChorus))
                KnobRow(vm, st, listOf(ctlEcho, ctlEchoMs, ctlEchoFb))
            }
        }
        SmallTitle(tr("动态"))
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KnobRow(vm, st, listOf(ctlComp, ctlDrive, ctlSub, ctlPre))
                KnobRow(vm, st, listOf(ctlGate, ctlLowCut, ctlCrush))
            }
        }
        SmallTitle(tr("均衡 / 总线"))
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(ctlLow, ctlMid, ctlHigh, ctlOut).forEach { c ->
                    Fader(tr(c.label), c.norm(st.fxCard[c.idx]), text = c.show(st.fxCard[c.idx]), reset = c.norm(FxChain.neutral()[c.idx])) { vm.setFx(2, c.idx, c.denorm(it)) }
                }
            }
        }
        Text(tr("双击旋钮或推子复位。耳返开关在设置里，外放会啸叫，请戴耳机。"), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 12.sp, color = Summary)
    }
}

// ------------------------------------------------------------------ 语音包

private fun LazyListScope.soundBoardPage(vm: MainViewModel, st: UiState, open: (String) -> Unit) {
    item(key = "sfx_tabs") { SfxTabs(vm, st) }
    item(key = "sfx_head") { SfxHeader(vm, st, open) }
    val fx = st.sfxTab == 0
    val mine = st.sounds.filter { com.taocrypt.micmask.SoundBoard.isFx(it) == fx }
    item(key = "sfx_title_$fx") { SectionHead(if (fx) tr("音效包") else tr("本地音频"), vm, st) }
    st.sfxSel?.let { sel -> item(key = "sfx_selbar") { SfxSelBar(vm, st, sel, mine) } }
    if (mine.isEmpty()) item(key = "sfx_empty") {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            Text(if (fx) tr("音效包是空的。添加 mp3 音效、导入 .mim3 音效包，或从下方仓库下载。") else tr("还没有本地音频。导入手机里的音乐或录音。"), Modifier.padding(16.dp), color = Summary)
        }
    }
    mine.groupBy { it.pack }.forEach { (pack, list) ->
        if (fx) item(key = "sfx_pack_$pack") {
            val info = remember(pack, list.size) { vm.packInfo(pack) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 二级标题：分组名用正文色加粗小字 + 左侧色条 + 数量，和上面的「音效包」大标题区分
                Row(Modifier.weight(1f).padding(start = 28.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 3.dp, height = 14.dp).clip(RoundedCornerShape(2.dp)).background(MiuixTheme.colorScheme.primary))
                    Text((if (pack == com.taocrypt.micmask.SoundBoard.PACK_FX) tr("预设音效") else pack), Modifier.padding(start = 8.dp),
                        fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MiuixTheme.colorScheme.onBackground)
                    Text(tr(" {0} 个", list.size) + (info?.author?.takeIf { it.isNotBlank() }?.let { " · by $it" } ?: ""), fontSize = 12.sp, color = Summary, maxLines = 1)
                }
                var askDel by remember(pack) { mutableStateOf(false) }
                // 预设音效分组：只有用户自己加进来的才可删，全是预设时不显示「删除」
                val delCount = list.count { !com.taocrypt.micmask.SoundBoard.isPreset(it) }
                if (delCount > 0) Text(tr("删除"), Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = st.sfxBusy.isEmpty()) { askDel = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 13.sp, color = Color(0xFFE5484D))
                if (askDel) WindowDialog(show = true, title = tr("删除分组"), summary = tr("「{0}」里的 {1} 个音效会全部删除，不能恢复。", if (pack == com.taocrypt.micmask.SoundBoard.PACK_FX) tr("预设音效") else pack, delCount) + if (delCount < list.size) tr("预设音效保留。") else "",
                    onDismissRequest = { askDel = false }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(tr("取消"), onClick = { askDel = false }, modifier = Modifier.weight(1f))
                        TextButton(tr("删除"), onClick = { vm.deleteGroup(pack); askDel = false }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    }
                }
                Text(tr("导出音效包"), Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = st.sfxBusy.isEmpty()) { vm.exportGroup(pack) }
                    .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                // 开关：关闭后不在悬浮窗显示（App 内照常可播）
                top.yukonga.miuix.kmp.basic.Switch(checked = pack !in st.packsOff, onCheckedChange = { vm.togglePack(pack, it) },
                    modifier = Modifier.padding(start = 4.dp, end = 24.dp))
            }
            if (pack in st.packsOff) Text(tr("已关闭，不在悬浮窗显示"), Modifier.padding(horizontal = 28.dp).padding(bottom = 4.dp), fontSize = 12.sp, color = Summary)
            info?.description?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(horizontal = 28.dp).padding(bottom = 4.dp), fontSize = 12.sp, color = Summary) }
        }
        item(key = "sfx_grid_${pack}_${st.gridLayout}") { SoundTiles(vm, st, list) }
    }
    if (!fx) return
    item(key = "sfx_repo_title") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallTitle(tr("仓库素材"), Modifier.weight(1f))
            IconButton(onClick = { vm.refreshRepo() }, enabled = !st.repoLoading, modifier = Modifier.padding(end = 12.dp)) { Icon(MiuixIcons.Refresh, tr("刷新")) }
        }
    }
    if (st.repoSounds.isEmpty()) item(key = "sfx_repo_empty") {
        Text(if (st.repoLoading) tr("正在获取…") else if (st.repoUrl.isBlank()) tr("先在设置里填仓库地址") else tr("仓库里还没有素材（清单 sounds 字段）"),
            Modifier.padding(horizontal = 28.dp, vertical = 4.dp), fontSize = 13.sp, color = Summary)
    }
    items(st.repoSounds, key = { "sfxr_${it.id}" }) { r ->
        val have = st.sounds.any { it.id == r.id }
        Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(r.pack, if (r.size > 0) "%.1f MB".format(r.size / 1048576f) else null, if (have) tr("已下载") else null).joinToString(" · "),
                        fontSize = 12.sp, color = Summary)
                }
                IconButton(onClick = { vm.downloadRepoSound(r) }, enabled = st.sfxBusy.isEmpty()) {
                    Icon(if (have) MiuixIcons.Refresh else MiuixIcons.Download, tr("下载"))
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
        listOf(tr("音效包"), tr("本地音频")).forEachIndexed { i, n ->
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
    var pendingAdd by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) pendingAdd = uris }
    if (pendingAdd.isNotEmpty()) {
        val groups = remember { vm.fxGroups() }
        var g by remember { mutableStateOf(groups.firstOrNull { it != com.taocrypt.micmask.SoundBoard.PACK_FX } ?: tr("我的音效")) }
        WindowDialog(show = true, title = tr("添加 {0} 个音效到分组", pendingAdd.size), onDismissRequest = { pendingAdd = emptyList() }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (groups.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { x ->
                        val label = if (x == com.taocrypt.micmask.SoundBoard.PACK_FX) tr("预设音效") else x
                        TextButton(label, onClick = { g = x }, colors = if (g == x) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
                    }
                }
                TextField(value = if (g == com.taocrypt.micmask.SoundBoard.PACK_FX) tr("预设音效") else g,
                    onValueChange = { g = it.take(40) }, label = tr("分组名（填新名字即新建分组）"), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(tr("取消"), onClick = { pendingAdd = emptyList() }, modifier = Modifier.weight(1f))
                    TextButton(tr("添加"), onClick = {
                        val target = if (g == tr("预设音效")) com.taocrypt.micmask.SoundBoard.PACK_FX else g
                        vm.importToGroup(pendingAdd, target); pendingAdd = emptyList()
                    }, enabled = g.isNotBlank(), modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
    val fx = st.sfxTab == 0
    Card(Modifier.fillMaxWidth().padding(PagePad).padding(top = 4.dp, bottom = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sending = st.sounds.firstOrNull { it.id == st.sfxPlaying }
            Text(when {
                sending != null -> tr("正在送进麦克风：{0}", sending.name)
                st.sfxPreview != null -> tr("试听中（扬声器）：{0}", st.sounds.firstOrNull { it.id == st.sfxPreview }?.name.orEmpty())
                else -> tr("点卡片试听（扬声器），点「送麦」播给对方")
            }, fontWeight = FontWeight.SemiBold)
            Text(tr("送麦会叠加在变声之后，没在变声时自动用声卡模式打开麦克风通道。悬浮窗里点素材也是送麦。"), fontSize = 12.sp, color = Summary)
            Row { Text(tr("音量"), Modifier.weight(1f), fontSize = 14.sp); Text("${(st.sfxGain * 100).roundToInt()}%", fontSize = 13.sp, color = Summary) }
            Slider(value = st.sfxGain, onValueChange = { vm.setSfxGain((it * 20).roundToInt() / 20f) }, valueRange = 0f..1.5f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("送麦时循环"), Modifier.weight(1f), fontSize = 14.sp)
                top.yukonga.miuix.kmp.basic.Switch(checked = st.sfxLoop, onCheckedChange = { vm.setSfxLoop(it) })
            }
            if (fx) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(tr("制作音效包"), onClick = { open("mim3") }, enabled = st.sfxBusy.isEmpty(), modifier = Modifier.weight(1f))
                TextButton(if (st.sfxBusy.isNotEmpty()) st.sfxBusy else tr("导入音效包"), onClick = { fxPicker.launch(arrayOf("*/*")) },
                    enabled = st.sfxBusy.isEmpty(), modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fx) TextButton(tr("添加音效"), onClick = { addPicker.launch(arrayOf("audio/*")) }, enabled = st.sfxBusy.isEmpty(), modifier = Modifier.weight(1f))
                else TextButton(if (st.sfxBusy.isNotEmpty()) st.sfxBusy else tr("导入本地音频"), onClick = { localPicker.launch(arrayOf("audio/*")) },
                    enabled = st.sfxBusy.isEmpty(), modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                TextButton(tr("全部停止"), onClick = { vm.stopPreview(); vm.stopSound() }, enabled = st.sfxPlaying != null || st.sfxPreview != null, modifier = Modifier.weight(1f))
            }
            Text(if (fx) tr("音效包为 .mim3 文件：「制作音效包」把手机里的音频打包成 .mim3，「导入音效包」导入别人分享的 .mim3；「添加音效」支持 mp3 等常见音频，可自选分组；每个分组标题右侧「导出音效包」可打包分享。")
                else tr("支持 mp3、m4a、wav、ogg、flac 等常见音频。"), fontSize = 12.sp, color = Summary)
        }
    }
}

/** 多选操作条：已选数 / 全选 / 重命名（选 1 个时）/ 删除 / 完成 */
@Composable
private fun SfxSelBar(vm: MainViewModel, st: UiState, sel: Set<String>, all: List<com.taocrypt.micmask.SoundBoard.Sound>) {
    var ask by remember { mutableStateOf(false) }
    val ids = all.filterNot { com.taocrypt.micmask.SoundBoard.isPreset(it) }.map { it.id }.toSet()
    Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("已选 {0} 个", sel.size), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(if (sel.containsAll(ids) && ids.isNotEmpty()) tr("全不选") else tr("全选"),
                    onClick = { vm.sfxSelSet(if (sel.containsAll(ids)) emptySet() else ids) }, modifier = Modifier.weight(1f))
                TextButton(tr("重命名"), enabled = sel.size == 1, onClick = { vm.sfxRename(sel.first()) }, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(tr("完成"), onClick = { vm.sfxSelEnd() }, modifier = Modifier.weight(1f))
                TextButton(tr("删除 {0}", sel.size), enabled = sel.isNotEmpty(), onClick = { ask = true }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
    if (ask) WindowDialog(show = true, title = tr("删除 {0} 个音效", sel.size), summary = tr("删除后不能恢复。"), onDismissRequest = { ask = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(tr("取消"), onClick = { ask = false }, modifier = Modifier.weight(1f))
            TextButton(tr("删除"), onClick = { vm.deleteSelectedSounds(); ask = false }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun SoundTiles(vm: MainViewModel, st: UiState, list: List<com.taocrypt.micmask.SoundBoard.Sound>) {
    var edit by remember { mutableStateOf<com.taocrypt.micmask.SoundBoard.Sound?>(null) }
    val primary = MiuixTheme.colorScheme.primary
    val sel = st.sfxSel
    val tiles = list.map { s ->
        val d = s.durMs / 1000
        val prev = st.sfxPreview == s.id
        val send = st.sfxPlaying == s.id
        if (sel != null) {
            val on = s.id in sel
            Tile((if (on) "✓ " else "") + s.name, on, sub = "%d:%02d".format(d / 60, d % 60), onLong = { vm.sfxSelToggle(s.id) }) { vm.sfxSelToggle(s.id) }
        } else
        Tile((if (prev) "▶ " else "") + s.name, prev, sub = if (send) tr("送麦中") else "%d:%02d".format(d / 60, d % 60), onLong = { vm.sfxSelStart(s.id) },
            action = {
                Text(if (send) tr("■ 停止") else tr("送麦"), Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (send) Color(0xFFE5484D) else primary.copy(alpha = 0.15f))
                    .clickable { vm.playSound(s.id) }.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 12.sp, color = if (send) Color.White else if (prev) MiuixTheme.colorScheme.onPrimary else primary)
            }) { vm.previewSound(s.id) }
    }
    TileGroup(tiles, st.gridLayout)
    if (sel == null && (st.gridLayout || list.isNotEmpty())) Text(tr("长按进入多选（删除、重命名）"), Modifier.padding(horizontal = 24.dp), fontSize = 11.sp, color = Summary)
    LaunchedEffect(st.sfxRename) { st.sfxRename?.let { id -> list.firstOrNull { it.id == id }?.let { edit = it; vm.sfxRenameDone() } } }
    edit?.let { s ->
        var text by remember(s.id) { mutableStateOf(s.name) }
        WindowDialog(show = true, title = tr("编辑素材"), onDismissRequest = { edit = null }) {
            Column {
                TextField(value = text, onValueChange = { text = it.take(40) }, label = tr("名称"), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(tr("取消"), onClick = { edit = null }, modifier = Modifier.weight(1f))
                    TextButton(tr("保存"), onClick = { vm.renameSound(s, text); edit = null }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 设置

/** 悬浮窗外观：悬浮球大小/透明度、控制窗大小/透明度（悬浮窗监听配置变化即时重建） */
@Composable
private fun FloatLookSettings() {
    val ctx = LocalContext.current
    val sp = remember { com.taocrypt.micmask.Prefs.main(ctx) }
    var ball by remember { mutableStateOf(sp.getInt("lf_ball_dp", 45).toFloat()) }
    var ballA by remember { mutableStateOf(sp.getFloat("lf_ball_alpha", 0.9f)) }
    var scale by remember { mutableStateOf(sp.getFloat("lf_panel_scale", 0.85f)) }
    var panelA by remember { mutableStateOf(sp.getFloat("lf_panel_alpha", 1f)) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        @Composable fun row(t: String, v: String, value: Float, range: ClosedFloatingPointRange<Float>, on: (Float) -> Unit, done: () -> Unit) {
            Row(Modifier.padding(top = 6.dp)) { Text(t, Modifier.weight(1f), fontSize = 14.sp); Text(v, fontSize = 13.sp, color = Summary) }
            Slider(value = value, onValueChange = on, valueRange = range, onValueChangeFinished = done)
        }
        row(tr("悬浮球大小"), "${ball.roundToInt()} dp", ball, 32f..80f, { ball = it.roundToInt().toFloat() }) { sp.edit().putInt("lf_ball_dp", ball.roundToInt()).apply() }
        row(tr("悬浮球不透明度"), "${(ballA * 100).roundToInt()}%", ballA, 0.2f..1f, { ballA = (it * 20).roundToInt() / 20f }) { sp.edit().putFloat("lf_ball_alpha", ballA).apply() }
        row(tr("控制窗内容缩放"), "${(scale * 100).roundToInt()}%", scale, 0.7f..1.5f, { scale = (it * 20).roundToInt() / 20f }) { sp.edit().putFloat("lf_panel_scale", scale).apply() }
        row(tr("控制窗不透明度"), "${(panelA * 100).roundToInt()}%", panelA, 0.3f..1f, { panelA = (it * 20).roundToInt() / 20f }) { sp.edit().putFloat("lf_panel_alpha", panelA).apply() }
        var ah by remember { mutableStateOf(sp.getBoolean("lf_autohide", true)) }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(tr("收起后自动贴边半隐藏"), fontSize = 14.sp); Text(tr("3 秒不操作，悬浮球缩进屏幕边缘一半"), fontSize = 12.sp, color = Summary) }
            Switch(checked = ah, onCheckedChange = { ah = it; sp.edit().putBoolean("lf_autohide", it).apply() })
        }
        Text(tr("点模式按钮启动，再点一次停止；拖动悬浮球可贴边。控制窗右下角「⤡」长按后拖动，可自由调宽和高；双击「⤡」恢复默认大小。"), fontSize = 12.sp, color = Summary, modifier = Modifier.padding(vertical = 6.dp))
        Text(tr("恢复默认"), Modifier.clip(RoundedCornerShape(8.dp)).clickable {
            // 一次性写入，悬浮窗只重建一次；窗口宽高也一并恢复
            sp.edit().remove("lf_ball_dp").remove("lf_ball_alpha").remove("lf_panel_scale").remove("lf_panel_alpha")
                .remove("lf_autohide").remove("lf_panel_w").remove("lf_panel_h").apply()
            ball = 45f; ballA = 0.9f; scale = 0.85f; panelA = 1f; ah = true
        }.padding(vertical = 4.dp), fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
    }
}

private fun LazyListScope.settingsPage(vm: MainViewModel, st: UiState, open: (String) -> Unit) {
    item { SmallTitle(tr("变声设置")) }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            val n = vm.whitelist().size
            ArrowPreference(title = tr("目标应用"), summary = if (st.liveOn) tr("变声中不可修改，先关闭变声") else if (n == 0) tr("未选择，只做本机试听") else tr("已选 {0} 个应用", n),
                enabled = !st.liveOn, onClick = { if (st.liveOn) vm.toast(tr("请先关闭变声，再修改目标应用")) else open("whitelist") })
            SwitchPreference(checked = st.liveAec, onCheckedChange = { if (it != st.liveAec) vm.toggleLiveAecAnywhere() }, title = tr("降噪模式"),
                summary = (if (st.liveAec) tr("已开：回声消除 + 降噪") else tr("已关：原始麦克风，音质优先")) + if (st.liveOn) tr("（切换会重启变声）") else "")
            SwitchPreference(checked = st.liveMonitor, onCheckedChange = { vm.setLiveMonitor(it) }, title = tr("实时耳返"),
                summary = tr("变声后的声音送到耳机"))
            var rc by remember { mutableStateOf(vm.recCompat()) }
            SwitchPreference(checked = rc, onCheckedChange = { rc = it; vm.setRecCompat(it) }, title = tr("兼容录音"),
                summary = tr("录音失败时自动启用，打开则始终使用"))
            SwitchPreference(checked = st.liveFloat, onCheckedChange = { vm.setLiveFloat(it) }, title = tr("悬浮窗开关"),
                summary = tr("单击展开 · 双击打开 App · 长按关闭"))
            if (st.liveFloat) FloatLookSettings()
        }
    }
    item { SmallTitle(tr("运行模式")) }
    item { PermModeCard(vm, st) }
    item { SmallTitle(tr("模型仓库")) }
    item { RepoSetting(vm, st) }
    item { SmallTitle(tr("隐私")) }
    item { PrivacySettings(vm, open) }
    item { SmallTitle(tr("其他")) }
    item {
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            ArrowPreference(title = tr("外观"), summary = tr("深浅色、莫奈取色、液态玻璃"), onClick = { open("theme") })
            ArrowPreference(title = tr("日志"), summary = tr("查看、筛选、导出日志"), onClick = { open("logs") })
            ArrowPreference(title = tr("反馈问题"), summary = if (com.taocrypt.micmask.BeaconHub.enabled) tr("描述问题，可附带日志和截图")
                else tr("QQ 群 {0} · GitHub Issues", AboutLinks.QQ_GROUP_NO), onClick = { open("feedback") })
            ArrowPreference(title = tr("音频还原"), summary = tr("应用没声音时点这里还原"), onClick = { vm.repairAudio() })
            ArrowPreference(title = tr("关于 MicMask"), summary = tr("v{0} · 更新、反馈、赞赏", com.taocrypt.micmask.BuildConfig.VERSION_NAME), onClick = { open("about") })
        }
    }
}

/** 设置 → 隐私：匿名统计开关、删除统计数据、显示推荐、隐私说明 */
@Composable
private fun PrivacySettings(vm: MainViewModel, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val hub = com.taocrypt.micmask.BeaconHub
    var confirm by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(PagePad)) {
        if (hub.enabled) {
            SwitchPreference(checked = hub.analytics, onCheckedChange = { hub.setAnalytics(it) }, title = tr("匿名统计"),
                summary = if (hub.analytics) tr("已开：上报版本、机型、功能使用与崩溃信息，不含录音") else tr("已关：不上报任何统计"))
            ArrowPreference(title = tr("删除我的统计数据"), summary = if (forgetting) tr("正在删除…") else tr("删除服务器上本机的全部统计记录并重置安装 ID"),
                enabled = !forgetting, onClick = { confirm = true })
            SwitchPreference(checked = hub.showSlots, onCheckedChange = { hub.setShowSlots(ctx, it) }, title = tr("显示推荐"),
                summary = tr("在首页、结果页和关于页显示作者的推荐内容"))
        }
        ArrowPreference(title = tr("隐私说明"), summary = if (hub.enabled) tr("收集什么、不收集什么") else tr("当前版本未启用统计"), onClick = { open("privacy") })
    }
    if (confirm) WindowDialog(show = true, title = tr("删除我的统计数据"), summary = tr("将删除服务器上本机的全部统计记录，并关闭匿名统计。此操作不可撤销。"),
        onDismissRequest = { confirm = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(tr("取消"), onClick = { confirm = false }, modifier = Modifier.weight(1f))
            TextButton(tr("删除"), onClick = {
                confirm = false; forgetting = true
                hub.forgetMe { ok ->
                    forgetting = false
                    if (ok) hub.setAnalytics(false)
                    Toast.makeText(ctx, if (ok) tr("已删除，匿名统计已关闭") else tr("删除失败，请检查网络后重试"), Toast.LENGTH_LONG).show()
                }
            }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun RepoSetting(vm: MainViewModel, st: UiState) {
    // editing：null 不显示；"" 新增；其它为正在编辑的仓库地址
    var editing by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth().padding(PagePad)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            if (st.repos.isEmpty()) Text(tr("还没有模型仓库。仓库为社区维护，请只添加你信任、有权使用的仓库。"),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp, color = Summary)
            ReorderList(st.repos, key = { it.url }, onMove = { a, b -> vm.moveRepo(a, b) }) { r, handle, dragging ->
                Row(Modifier.fillMaxWidth()
                    .background(if (dragging) MiuixTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                    .clickable { editing = r.url }.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("⋮⋮", handle.padding(horizontal = 10.dp, vertical = 10.dp), fontSize = 16.sp, color = Summary)
                    Column(Modifier.weight(1f)) {
                        Text(r.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = if (r.on) MiuixTheme.colorScheme.onSurface else Summary)
                        if (r.name.isNotBlank()) Text(r.url.substringAfter("://"), fontSize = 12.sp, color = Summary, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Switch(checked = r.on, onCheckedChange = { vm.toggleRepo(r.url, it) })
                }
            }
            Text(tr("按住 ⋮⋮ 拖动排序，靠上的仓库优先；点一项可改备注名或删除。"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                fontSize = 12.sp, color = Summary)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(tr("刷新全部"), onClick = { vm.refreshRepo() }, enabled = st.repos.any { it.on } && !st.repoLoading, modifier = Modifier.weight(1f))
                TextButton(tr("添加仓库"), onClick = { editing = "" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
            if (st.repoMsg.isNotBlank()) Text(st.repoMsg, fontSize = 12.sp, color = Summary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
    editing?.let { old ->
        val cur = st.repos.firstOrNull { it.url == old }
        var url by remember(old) { mutableStateOf(cur?.url.orEmpty()) }
        var name by remember(old) { mutableStateOf(cur?.name.orEmpty()) }
        WindowDialog(show = true, title = if (cur == null) tr("添加模型仓库") else tr("编辑模型仓库"),
            summary = tr("仓库为社区维护，请只添加你信任、有权使用的仓库"), onDismissRequest = { editing = null }) {
            Column {
                TextField(value = name, onValueChange = { name = it.take(20) }, label = tr("备注名（可空）"), singleLine = true)
                Spacer(Modifier.height(8.dp))
                TextField(value = url, onValueChange = { url = it }, label = tr("仓库地址或 models.json 链接"), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cur != null) TextButton(tr("删除"), onClick = { vm.removeRepo(old); editing = null }, modifier = Modifier.weight(1f))
                    else TextButton(tr("取消"), onClick = { editing = null }, modifier = Modifier.weight(1f))
                    TextButton(tr("保存"), onClick = { vm.saveRepo(cur?.url, url, name); editing = null }, enabled = url.isNotBlank(),
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

@Composable
private fun ThemePage(ui: UiPrefs) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SmallTitle(tr("语言"))
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            WindowDropdownPreference(items = listOf(tr("跟随系统"), "简体中文", "English"),
                selectedIndex = com.taocrypt.micmask.I18n.lang.coerceIn(0, 2), title = tr("界面语言"),
                summary = tr("日志始终为中文"),
                onSelectedIndexChange = { v ->
                    if (v != com.taocrypt.micmask.I18n.lang) {
                        com.taocrypt.micmask.I18n.set(ctx, v)
                        com.taocrypt.micmask.LiveService.refresh(ctx)
                        (ctx as? android.app.Activity)?.recreate()
                    }
                })
        }
        SmallTitle(tr("主题"))
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            WindowDropdownPreference(items = listOf(tr("跟随系统"), tr("浅色"), tr("深色")), selectedIndex = ui.mode, title = tr("深浅色"),
                onSelectedIndexChange = { ui.mode = it; ui.save() })
            SwitchPreference(checked = ui.oled, onCheckedChange = { ui.oled = it; ui.save() }, title = tr("纯黑背景"),
                summary = tr("深色模式下背景改为纯黑，OLED 屏更省电"), enabled = ui.mode != 1)
            SwitchPreference(checked = ui.monet, onCheckedChange = { ui.monet = it; ui.save() }, title = tr("莫奈取色"),
                summary = tr("按壁纸或所选主色生成整套配色"))
            if (ui.monet) WindowDropdownPreference(items = KeyColors.map { it.first }, selectedIndex = ui.key, title = tr("主色"),
                onSelectedIndexChange = { ui.key = it; ui.save() })
        }
        SmallTitle(tr("底栏"))
        Card(Modifier.fillMaxWidth().padding(PagePad)) {
            SwitchPreference(checked = ui.glass, onCheckedChange = { ui.glass = it; ui.save() }, title = tr("液态玻璃"),
                summary = if (Build.VERSION.SDK_INT >= 33) tr("关闭后改用高斯模糊") else tr("需要 Android 13 及以上"),
                enabled = Build.VERSION.SDK_INT >= 33)
        }
    }
}

@Composable
private fun RepoItem(vm: MainViewModel, st: UiState, m: ModelRepo.RModel) {
    val busy = st.dlId == m.id
    val installed = remember(st.liveVoices, m.id) { vm.repoInstalled(m) }
    Column {
    Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontWeight = FontWeight.SemiBold)
                    val meta = listOfNotNull(m.author.takeIf { it.isNotBlank() }?.let { tr("作者 {0}", it) },
                        if (m.totalSize > 0) "%.0f MB".format(m.totalSize / 1048576f) else null,
                        if (installed) tr("已安装") else null,
                        // 多仓库时标出来源
                        st.repos.takeIf { r -> r.count { it.on } > 1 }?.firstOrNull { it.url == m.repo }?.label).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = Summary)
                }
                IconButton(onClick = { vm.downloadRepoModel(m) }, enabled = st.dlId == null && !st.liveOn) {
                    Icon(if (installed) MiuixIcons.Refresh else MiuixIcons.Download, if (installed) tr("重新下载") else tr("下载"))
                }
            }
            if (m.desc.isNotBlank()) Text(m.desc, fontSize = 13.sp, color = Summary)
            if (m.license.isNotBlank()) Text(tr("授权：{0}", m.license), fontSize = 12.sp, color = Summary)
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = st.dlProgress, modifier = Modifier.padding(end = 8.dp))
                Text("%.0f%%  %s".format(st.dlProgress * 100, st.dlName), fontSize = 12.sp,
                    color = Summary)
            }
        }
    }
    // 推荐位 model_download：下载中显示在该音色卡片下方，下载结束（busy=false）自动收起
    if (busy) BeaconSlotCard("model_download", st.liveOn, Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 8.dp))
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
    // 排序：0=名称（默认） 1=安装时间（新→旧）；记住上次选择（新键 wlSort2，旧 wlSort 的 0/1 含义相反，弃用）
    var sort by remember { mutableStateOf(prefs.getInt("wlSort2", 0)) }
    var showSys by remember { mutableStateOf(prefs.getBoolean("wlSystem", false)) }
    // 方向：安装时间默认新→旧，名称默认 A→Z；再点一次当前项切换
    var desc by remember { mutableStateOf(prefs.getBoolean("wlDesc2", sort == 1)) }
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
        TextField(value = q, onValueChange = { q = it }, label = tr("搜索应用"), singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val primary = MiuixTheme.colorScheme.primary
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.secondaryContainer).padding(3.dp)) {
                listOf(tr("名称"), tr("安装时间")).forEachIndexed { i, t ->
                    val on = sort == i
                    val bg by animateColorAsState(if (on) primary else Color.Transparent, label = "wlsort")
                    Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                        .clickable {
                            if (on) desc = !desc else { sort = i; desc = i == 1 }
                            prefs.edit().putInt("wlSort2", sort).putBoolean("wlDesc2", desc).apply()
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
                Text(tr("系统应用"), fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
        val src = all
        if (src == null) Text(tr("正在读取应用列表…"), Modifier.padding(24.dp))
        else {
            val asc: Comparator<AppInfo> = if (sort == 1) compareBy { it.installed }
                else Comparator { x, y -> collator.compare(x.label, y.label) }
            val byOrder = if (desc) asc.reversed() else asc
            // 已选的置顶，其余按所选方式排
            val list = src.filter { (showSys || !it.system || it.pkg in sel) &&
                    (q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true)) }
                .sortedWith(compareBy<AppInfo> { it.pkg !in sel }.then(byOrder))
            val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA) }
            Text(tr("{0} 个应用", list.size) + if (sel.isNotEmpty()) tr(" · 已选 {0} 个置顶", sel.size) else "", fontSize = 12.sp, color = Summary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.pkg }) { a ->
                    val on = a.pkg in sel
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (a.icon != null) Image(a.icon, null, Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(a.label + if (a.system) tr(" · 系统") else "", fontWeight = FontWeight.Medium)
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


// ------------------------------------------------------------------ 制作音效包

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
        item { SmallTitle(tr("包信息")) }
        item {
            Card(Modifier.fillMaxWidth().padding(PagePad)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(value = group, onValueChange = { group = it.take(40) }, label = tr("分组名（导入后显示的分组）"), singleLine = true)
                    TextField(value = author, onValueChange = { author = it.take(40) }, label = tr("创作者"), singleLine = true)
                    TextField(value = desc, onValueChange = { desc = it.take(200) }, label = tr("简介（可选）"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("打包后同时导入本机"), Modifier.weight(1f), fontSize = 14.sp)
                        top.yukonga.miuix.kmp.basic.Switch(checked = alsoImport, onCheckedChange = { alsoImport = it })
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallTitle(tr("音效 {0} 个", items.size), Modifier.weight(1f))
                TextButton(tr("添加音频"), onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.padding(end = 12.dp))
            }
        }
        if (items.isEmpty()) item {
            Text(tr("点「添加音频」选择多个 mp3（也支持 ogg/m4a/wav/flac），文件原样打包不转码。"), Modifier.padding(horizontal = 28.dp, vertical = 4.dp), fontSize = 13.sp, color = Summary)
        }
        items(items.size, key = { items[it].uri.toString() }) { i ->
            val it0 = items[i]
            Card(Modifier.fillMaxWidth().padding(PagePad).padding(bottom = 8.dp)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TextField(value = it0.name, onValueChange = { v -> val k = items.indexOfFirst { x -> x.uri == it0.uri }; if (k >= 0) items[k] = it0.copy(name = v.take(40)) },
                            label = tr("名称 · .{0}", it0.ext), singleLine = true)
                    }
                    IconButton(onClick = { items.removeAll { x -> x.uri == it0.uri } }) { Icon(MiuixIcons.Delete, tr("移除")) }
                }
            }
        }
        item {
            Column(Modifier.padding(PagePad).padding(vertical = 12.dp)) {
                TextButton(if (st.sfxBusy.isNotEmpty()) st.sfxBusy else tr("打包为音效包"),
                    onClick = { vm.buildMim3(group, author, desc, items.toList(), alsoImport) },
                    enabled = st.sfxBusy.isEmpty() && items.isNotEmpty() && group.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary())
                Text(tr("保存到 Download/MicMask/<分组名>.mim3，可直接发给别人导入。"),
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 12.sp, color = Summary)
            }
        }
    }
}


/** 设置页「权限模式」：Shizuku（实时注入）/ 普通（扬声器外放）两个卡片按钮，底部描述随所选模式变化 */
@Composable
private fun PermModeCard(vm: MainViewModel, st: UiState) {
    val primary = MiuixTheme.colorScheme.primary
    // 授权状态变化后 refreshInjectState 会更新 qqShizuku / speakerMode，以此作为 key 重新读取
    val permitted = remember(st.qqShizuku, st.speakerMode, st.forceNormal) { com.taocrypt.micmask.inject.MicInjector.shizukuPermitted() }
    // 未授权 Shizuku 时实际按普通模式工作，选中态也显示为普通模式
    val effNormal = st.forceNormal || !permitted
    Card(Modifier.fillMaxWidth().padding(PagePad)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(false to tr("Shizuku 模式"), true to tr("普通模式")).forEach { (isNormal, name) ->
                    val sel = effNormal == isNormal
                    val bg by animateColorAsState(if (sel) primary else MiuixTheme.colorScheme.surfaceContainer, label = "perm")
                    val fg = if (sel) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg)
                        .clickable(enabled = !st.liveOn) { if (!sel || (!isNormal && !permitted)) vm.setNormalMode(isNormal) }
                        .padding(vertical = 14.dp, horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = fg)
                        Text(if (isNormal) tr("无需授权") else if (permitted) tr("已授权") else tr("未授权"), fontSize = 12.sp, color = fg.copy(alpha = 0.75f))
                    }
                }
            }
            Text(when {
                st.liveOn -> tr("变声中不能切换，先关闭变声。")
                effNormal -> tr("普通模式：不使用 Shizuku。变声和音效从扬声器外放，AI/普通变声为「录一句再放」的录播方式，声卡不可用；对方可能听到原声。") +
                    if (!st.forceNormal && !permitted) tr("\nShizuku 未授权，点上方「Shizuku 模式」申请。") else ""
                else -> tr("Shizuku 模式：把变声直接送进目标应用的麦克风，支持实时 AI 变声、普通变声和虚拟声卡。")
            }, fontSize = 12.sp, color = Summary)
        }
    }
}
