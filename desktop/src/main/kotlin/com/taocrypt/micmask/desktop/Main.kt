package com.taocrypt.micmask.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/*
 * MicMask 电脑版 0.x（Windows）：与手机 MicMask 用 MicMask Link v1 互传声音。
 * 进程模型：窗口可以关到托盘（Window 的组合真正销毁，释放 Skia/D3D 资源），收发与自动连接在后台线程继续；
 * 只有「退出」才清理（后台并行、总超时 1.5 秒）并结束进程。
 */

fun main(args: Array<String>) {
    // 界面渲染后端：设置里选「软件渲染」时用 Skia CPU 渲染（CI 实测比 DIRECT3D 少约 25–35 MB 工作集，CPU 略高），重启生效
    if (System.getProperty("skiko.renderApi") == null) Settings.get("render_api").takeIf { it == "SOFTWARE" || it == "OPENGL" }?.let { System.setProperty("skiko.renderApi", it) }
    // CI 升级测试用：--version-out=文件 写出显示版本后退出；--apply-update=安装包 [--update-args=/qn] 走应用内更新的「启动安装并退出」路径
    args.firstOrNull { it.startsWith("--version-out=") }?.let { java.io.File(it.substringAfter('=')).writeText(BuildInfo.VERSION); kotlin.system.exitProcess(0) }
    args.firstOrNull { it.startsWith("--apply-update=") }?.let { applyUpdateCli(java.io.File(it.substringAfter('=')), args.filter { a -> a.startsWith("--update-args=") }.map { a -> a.substringAfter('=') }) }
    if ("--smoke" in args) { smokeMain(args); return }
    // 单实例：已有实例时把参数和激活请求交给它（窗口前置，含从托盘恢复），本进程立即退出
    if (!SingleInstance().acquire(args.toList()) { a -> SwingUtilities.invokeLater { DeskLog.i("系统", "收到另一次启动的激活请求 ${a.joinToString(" ")}".trim()); Ui.show() } }) kotlin.system.exitProcess(0)
    gui(startInTray = "--tray" in args)
}

private fun applyUpdateCli(f: java.io.File, extra: List<String>) {
    DeskLog.start()
    val code = runCatching { Updater.launchInstaller(f, extra) }
        .onSuccess { DeskLog.i("更新", "安装程序已启动（命令行），退出") }
        .onFailure { DeskLog.e("更新", "启动安装失败：${it.message}") }
        .fold({ 0 }, { 2 })
    DeskLog.flushNow()
    Runtime.getRuntime().halt(code)
}

/** 窗口 / 托盘的界面状态 */
object Ui {
    var windowShown by mutableStateOf(true)
    var page by mutableIntStateOf(0)
    var closePrompt by mutableStateOf(false)
    @Volatile var trayOk = false
    /** Skia 实际用的渲染后端（DIRECT3D / OPENGL / SOFTWARE…），自测打印 */
    @Volatile var renderApi = "?"
    /** 每次 show() 加一：窗口据此前置（toFront / 取消最小化），根布局据此重绘一帧（用来测「恢复到首帧」） */
    var activateTick by mutableIntStateOf(0)
    /** 缩到托盘时释放窗口（销毁 Compose 窗口与 Skia 上下文，更省内存、恢复稍慢）；默认关 = 只隐藏，恢复即时 */
    var releaseOnTray by mutableStateOf(Settings.getBool("tray_release", false))
    /** 从哪一页进的「使用说明」（返回时回到那一页） */
    var manualFrom = 2
    fun openManual() { if (page != 4) manualFrom = page; page = 4 }

    /** 显示并前置主窗口（托盘点击、托盘菜单、另一次启动的激活请求都走这里；须在 EDT 调用） */
    fun show() { windowShown = true; AudioIO.watchFast = true; activateTick++ }

    /** 关到托盘：默认只隐藏窗口（界面轮询/电平表暂停）；选了「释放窗口」时窗口移出组合（真正 dispose）。稍后 GC 一次让堆回缩 */
    fun hideToTray() {
        windowShown = false
        AudioIO.watchFast = false
        DeskLink.stopPreview()
        if (!Settings.getBool("tray_hinted", false)) {
            Settings.setBool("tray_hinted", true)
            AppTray.notify("MicMask", tr("MicMask 在托盘里继续运行，收发不受影响。单击托盘图标打开窗口；右键可退出。"))
        }
        Thread({ Thread.sleep(1500); System.gc() }, "mm-gc").apply { isDaemon = true; start() }
    }

    /** 关闭按钮：tray = 最小化到托盘（默认），exit = 直接退出；没选过时先问一次 */
    fun onCloseRequest() {
        when (Settings.get("close_action")) {
            "exit" -> AppExit.quit()
            "tray" -> if (trayOk) hideToTray() else AppExit.quit()
            else -> if (trayOk) closePrompt = true else AppExit.quit()
        }
    }
}

/** 窗口隐藏在托盘时挂起，直到窗口再次显示（界面里的轮询循环每轮先调它：隐藏期间不读电平、不查剪贴板、不枚举会话） */
suspend fun awaitWindowShown() { if (!Ui.windowShown) snapshotFlow { Ui.windowShown }.first { it } }

object AppExit {
    @Volatile private var quitting = false
    /** 退出：立即隐藏窗口和托盘，后台清理（≤1.5 秒）后直接结束进程 */
    fun quit() {
        if (quitting) return
        quitting = true
        Ui.windowShown = false; Ui.trayOk = false
        SwingUtilities.invokeLater { AppTray.remove() }
        Thread({
            val t0 = System.currentTimeMillis()
            DeskLog.i("系统", "退出")
            val parts = runCatching { DeskLink.shutdown(1500) }.getOrDefault(emptyMap())
            DeskLog.i("系统", "退出清理 ${System.currentTimeMillis() - t0} ms $parts")
            DeskLog.flushNow()
            AppTray.remove()
            Runtime.getRuntime().halt(0)
        }, "mm-exit").apply { isDaemon = false; start() }
    }
}

val appIcon: Painter? by lazy {
    runCatching { BitmapPainter(javax.imageio.ImageIO.read(Thread.currentThread().contextClassLoader.getResourceAsStream("micmask.png")).toComposeImageBitmap()) }.getOrNull()
}

/** 启动后台部分（设备监视、mDNS 发现、自动连接、恢复接收） */
fun startBackground() {
    DeskLink.addLog("MicMask 电脑版 ${BuildInfo.VERSION} 启动；${System.getProperty("os.name")} ${System.getProperty("os.version")}，Java ${System.getProperty("java.version")}；本机「${DeskLink.deviceName}」")
    AudioIO.startWatch(DeskLink::onDevicesChanged)
    DeskLink.startDiscovery()
    DeskLink.startAuto()
    if (Settings.getBool("rx_restore", true) && Settings.getBool("rx_on", false)) DeskLink.startReceive()
    DeskBeacon.start()
}

private fun gui(startInTray: Boolean) {
    DeskLog.start()
    if (startInTray) Ui.windowShown = false
    // 冷启动：先把窗口显示出来，首帧之后（或 --tray 启动时立即）再在后台装托盘、枚举声卡、开 mDNS、恢复收发、Beacon
    Thread({
        if (!startInTray) StartupTimer.awaitFirstFrame(5000)
        val t0 = System.currentTimeMillis()
        val tray = runCatching { AppTray.install() }.getOrDefault(false)
        Ui.trayOk = tray
        if (startInTray && !tray) SwingUtilities.invokeLater { Ui.show() }   // 不支持托盘：只能显示窗口
        runCatching { startBackground() }.onFailure { DeskLog.e("系统", "后台初始化失败：$it") }
        DeskLog.i("系统", "后台初始化 ${System.currentTimeMillis() - t0} ms（托盘 ${if (tray) "已装" else "不支持"}，字体 ${AppTray.font.family}）")
    }, "mm-init").apply { isDaemon = true; start() }
    application(exitProcessOnExit = false) { AppContent() }
    AppExit.quit()
}

/** 主窗口（托盘由 AppTray 管，不在组合里；自测也用这一份） */
@Composable
fun ApplicationScope.AppContent(title: String = "MicMask") {
    val state: WindowState = rememberWindowState(size = DpSize(1100.dp, 720.dp))
    if (!Ui.releaseOnTray || Ui.windowShown) {
        Window(onCloseRequest = { Ui.onCloseRequest() }, state = state, visible = Ui.windowShown, title = title, icon = appIcon) {
            remember { window.minimumSize = java.awt.Dimension(900, 600); Ui.renderApi = runCatching { window.renderApi.toString() }.getOrDefault("?"); 0 }
            LaunchedEffect(Ui.activateTick) {
                if (Ui.activateTick > 0 && Ui.windowShown) {
                    state.isMinimized = false
                    // Windows 不让后台进程抢前台：临时置顶再取消，保证从托盘 / 另一次启动唤起时窗口在最前
                    window.toFront(); window.isAlwaysOnTop = true; window.isAlwaysOnTop = false; window.requestFocus()
                }
            }
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                // 读 activateTick 让每次唤起都重绘一帧（StartupTimer 测首帧 / 恢复耗时）
                Box(Modifier.fillMaxSize().drawBehind { Ui.activateTick; StartupTimer.frame() }) { Root() }
            }
        }
    } else {
        // 「释放窗口」模式下窗口已销毁：留一个从不显示的空窗口占位，Compose application 才不会因为没有窗口而退出
        Window(onCloseRequest = {}, visible = false, title = title, state = rememberWindowState(size = DpSize(1.dp, 1.dp))) {}
    }
}

internal fun trayTooltip(): String = "MicMask · " + listOfNotNull(
    if (DeskLink.rxOn) (if (DeskLink.rxPeer != null) tr("接收中") else tr("等待手机")) else null,
    when (DeskLink.txPhase) { DeskLink.TxPhase.ON -> tr("发送中 → {0}", DeskLink.txTarget); DeskLink.TxPhase.CONNECTING -> tr("连接中"); else -> null },
).ifEmpty { listOf(tr("空闲")) }.joinToString(" · ")

/**
 * --smoke：先跑无界面自测；有桌面时再真开主窗口（含托盘），依次测内存：开窗口 → 收发 10 秒 → 关到托盘 → 退出清理耗时。
 * --mem-only：跳过无界面自测，只做窗口与内存这一段（CI 用它对比不同 JVM 参数 / Skia 渲染后端）。
 */
private fun smokeMain(args: Array<String>) {
    val memOnly = "--mem-only" in args
    val f = args.firstOrNull { it.startsWith("--smoke-out=") }?.substringAfter('=') ?: java.io.File(System.getProperty("java.io.tmpdir"), "micmask-smoke.txt").path
    var code = if (memOnly) 0 else SelfTest.run(args)
    if (memOnly) runCatching { java.io.File(f).writeText("") }
    fun out(s: String) { println(s); runCatching { java.io.File(f).appendText(s + "\n", Charsets.UTF_8) } }
    if (java.awt.GraphicsEnvironment.isHeadless() || "--no-window" in args) kotlin.system.exitProcess(code)

    out(MemProbe.header())
    out(MemProbe.line("start"))
    val err = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
    Thread.setDefaultUncaughtExceptionHandler { _, e -> err.compareAndSet(null, e) }
    Thread({ Thread.sleep(90_000); runCatching { java.io.File(f).appendText("FAIL  window  timeout\nWINDOW FAILED\n") }; Runtime.getRuntime().halt(1) }, "smoke-watchdog").apply { isDaemon = true }.start()
    val t0 = System.currentTimeMillis()
    Ui.trayOk = runCatching { AppTray.install() }.getOrDefault(false)
    Ui.releaseOnTray = false
    val appNs = System.nanoTime()
    // 自测时不碰用户的设置文件里的收发状态：只用内存里的设备与端口
    var scope: ApplicationScope? = null
    var exitMs = -1L
    var exitParts: Map<String, Long> = emptyMap()
    val driver = Thread({
        try {
            val ff = StartupTimer.waitFrameAfter(appNs, 30_000)
            out((if (ff >= 0) "PASS" else "FAIL") + "  first-frame  ($ff ms 从 application 启动；JVM 起算 ${StartupTimer.firstFrameMs} ms，含无界面自测)")
            Thread.sleep(3500)
            out(MemProbe.line("window"))
            val r = SmokeTraffic.run(10_000)
            out(MemProbe.line("active-10s") + "  " + r)
            // 两种托盘模式：只隐藏（默认）/ 释放窗口。各测托盘状态内存与「托盘恢复 → 首帧」耗时
            for (rel in listOf(false, true)) {
                val tag = if (rel) "released" else "hidden"
                javax.swing.SwingUtilities.invokeAndWait { Ui.releaseOnTray = rel; Ui.hideToTray() }
                Thread.sleep(5000)
                out(MemProbe.line("tray($tag+gc)"))
                val ns = System.nanoTime()
                javax.swing.SwingUtilities.invokeLater { Ui.show() }
                val ms = StartupTimer.waitFrameAfter(ns, 15_000)
                out((if (ms >= 0) "PASS" else "FAIL") + "  tray-restore-$tag  ($ms ms 到首帧)")
                if (ms < 0) throw IllegalStateException("托盘恢复（$tag）15 秒内没有画出一帧")
                Thread.sleep(2500)
                out(MemProbe.line("window-again($tag)"))
            }
            javax.swing.SwingUtilities.invokeAndWait { Ui.releaseOnTray = false }
            // 使用说明：打开（懒加载 + 解析 + 渲染）→ 关闭（回到传音页 + GC），对比内存
            System.gc(); Thread.sleep(800)
            out(MemProbe.line("before-manual"))
            javax.swing.SwingUtilities.invokeAndWait { Ui.openManual() }
            Thread.sleep(2500)
            out(MemProbe.line("manual-open"))
            javax.swing.SwingUtilities.invokeAndWait { Ui.page = 0 }
            Thread.sleep(1200); System.gc(); Thread.sleep(800)
            out(MemProbe.line("manual-closed+gc"))
            // 退出：隐藏窗口 → 后台清理（与 AppExit.quit 同一路径，只是不 halt）
            val a = System.currentTimeMillis()
            Ui.windowShown = false
            exitParts = DeskLink.shutdown(1500)
            exitMs = System.currentTimeMillis() - a
        } catch (t: Throwable) { err.compareAndSet(null, t) }
        javax.swing.SwingUtilities.invokeLater { scope?.exitApplication() }
    }, "smoke-driver").apply { isDaemon = true }
    runCatching {
        application(exitProcessOnExit = false) {
            scope = this
            remember { driver.start(); 0 }
            AppContent(title = "MicMask 自测")
        }
    }.onFailure { err.compareAndSet(null, it) }
    val e = err.get()
    out(if (exitMs in 0..1600) "PASS  exit-cleanup  ($exitMs ms) $exitParts" else "FAIL  exit-cleanup  ($exitMs ms，上限 1600) $exitParts")
    if (exitMs !in 0..1600) code = 1
    out(if (e == null) "PASS  window  (${System.currentTimeMillis() - t0} ms) tray=${Ui.trayOk}" else "FAIL  window  ${e.javaClass.name}: ${e.message}")
    if (e != null) { code = 1; e.stackTrace.take(10).forEach { out("        at $it") } }
    out(if (e == null && code == 0) "WINDOW OK" else "WINDOW FAILED")
    AppTray.remove()
    Runtime.getRuntime().halt(code)
}
