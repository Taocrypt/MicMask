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
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
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
    if ("--smoke" in args) smokeMain(args) else gui(startInTray = "--tray" in args)
}

/** 窗口 / 托盘的界面状态 */
object Ui {
    var windowShown by mutableStateOf(true)
    var page by mutableIntStateOf(0)
    var closePrompt by mutableStateOf(false)
    var trayState: TrayState? = null
    @Volatile var trayOk = false
    /** Skia 实际用的渲染后端（DIRECT3D / OPENGL / SOFTWARE…），自测打印 */
    @Volatile var renderApi = "?"

    fun show() { windowShown = true }

    /** 关到托盘：Window 移出组合（真正 dispose），稍后 GC 一次让堆回缩 */
    fun hideToTray() {
        windowShown = false
        AudioIO.watchFast = false
        DeskLink.stopPreview()
        if (!Settings.getBool("tray_hinted", false)) {
            Settings.setBool("tray_hinted", true)
            trayState?.sendNotification(Notification("MicMask", tr("MicMask 在托盘里继续运行，收发不受影响。双击托盘图标打开窗口；右键可退出。")))
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

object AppExit {
    @Volatile private var quitting = false
    /** 退出：立即隐藏窗口和托盘，后台清理（≤1.5 秒）后直接结束进程 */
    fun quit() {
        if (quitting) return
        quitting = true
        Ui.windowShown = false; Ui.trayOk = false
        Thread({
            val t0 = System.currentTimeMillis()
            DeskLog.i("系统", "退出")
            val parts = runCatching { DeskLink.shutdown(1500) }.getOrDefault(emptyMap())
            DeskLog.i("系统", "退出清理 ${System.currentTimeMillis() - t0} ms $parts")
            DeskLog.flushNow()
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
    DeskBeacon.track("app_open")
}

private fun gui(startInTray: Boolean) {
    DeskLog.start()
    Ui.trayOk = runCatching { isTraySupported }.getOrDefault(false)
    if (startInTray && Ui.trayOk) Ui.windowShown = false
    startBackground()
    application(exitProcessOnExit = false) { AppContent() }
    AppExit.quit()
}

/** 托盘 + 主窗口（自测也用这一份） */
@Composable
fun ApplicationScope.AppContent(title: String = "MicMask") {
    val state: WindowState = rememberWindowState(size = DpSize(1100.dp, 720.dp))
    if (Ui.trayOk) {
        val ts = rememberTrayState()
        Ui.trayState = ts
        val icon = appIcon
        if (icon != null) Tray(icon = icon, state = ts, tooltip = trayTooltip(), onAction = { Ui.show() }, menu = {
            Item(tr("显示主窗口"), onClick = { Ui.show() })
            Separator()
            Item(if (DeskLink.rxOn) tr("停止接收（接收中）") else tr("开始接收（手机 → 电脑）"), onClick = { DeskLink.toggleReceive() })
            val q = DeskLink.quickTarget()
            Item(when {
                DeskLink.txOn -> tr("停止发送（→ {0}）", DeskLink.txTarget)
                q != null -> tr("开始发送 → {0}", q.title)
                else -> tr("开始发送（先在窗口里连一次手机）")
            }, enabled = DeskLink.txOn || q != null, onClick = { if (DeskLink.txOn) DeskLink.stopSend() else q?.let { DeskLink.connectSaved(it) } })
            Separator()
            Item(tr("导出日志…"), onClick = { Thread({ DeskLog.i("系统", DeskLog.exportInteractive()) }, "mm-export").start() })
            Item(tr("退出 MicMask"), onClick = { AppExit.quit() })
        })
    }
    if (Ui.windowShown) {
        AudioIO.watchFast = true
        Window(onCloseRequest = { Ui.onCloseRequest() }, state = state, title = title, icon = appIcon) {
            remember { window.minimumSize = java.awt.Dimension(900, 600); Ui.renderApi = runCatching { window.renderApi.toString() }.getOrDefault("?"); 0 }
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) { Root() }
        }
    }
}

private fun trayTooltip(): String = "MicMask · " + listOfNotNull(
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
    Ui.trayOk = runCatching { isTraySupported }.getOrDefault(false)
    // 自测时不碰用户的设置文件里的收发状态：只用内存里的设备与端口
    var scope: ApplicationScope? = null
    var exitMs = -1L
    var exitParts: Map<String, Long> = emptyMap()
    val driver = Thread({
        try {
            Thread.sleep(3500)
            out(MemProbe.line("window"))
            val r = SmokeTraffic.run(10_000)
            out(MemProbe.line("active-10s") + "  " + r)
            Ui.hideToTray()
            Thread.sleep(5000)
            out(MemProbe.line("tray(window disposed+gc)"))
            Ui.show()
            Thread.sleep(2500)
            out(MemProbe.line("window-again"))
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
    Runtime.getRuntime().halt(code)
}
