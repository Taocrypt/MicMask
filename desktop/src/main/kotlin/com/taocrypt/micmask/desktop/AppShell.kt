package com.taocrypt.micmask.desktop

import java.awt.Font
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.SecureRandom
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * 单实例：%LOCALAPPDATA%\MicMask\instance.lock（FileChannel.tryLock，进程结束系统自动释放），
 * 拿到锁的进程在 127.0.0.1 随机端口监听激活请求，端口 + 随机令牌写进 instance.port。
 * 再次启动时拿不到锁 → 把命令行参数和「激活」请求发给已运行的实例（它把窗口前置显示，含从托盘恢复）→ 新进程立即退出。
 * 已有实例还在启动、端口文件没写好时最多重试 5 秒；仍连不上也退出（宁可不响应一次，也不开出第二个实例抢端口和声卡）。
 */
class SingleInstance(private val dir: File = defaultDir) {
    companion object {
        val defaultDir: File get() = (System.getenv("LOCALAPPDATA")?.let { File(it, "MicMask") } ?: File(System.getProperty("user.home"), ".micmask"))
        private const val SEP = "\u0001"
    }

    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** true = 本进程是唯一实例（已开始监听）；false = 已有实例，请求已转交（或转交失败），调用方应立即退出 */
    fun acquire(args: List<String>, onActivate: (List<String>) -> Unit): Boolean {
        dir.mkdirs()
        val lf = File(dir, "instance.lock"); val pf = File(dir, "instance.port")
        val ch = runCatching { RandomAccessFile(lf, "rw").channel }.getOrElse { return true }   // 锁文件都开不了：不做单实例，照常启动
        val l = try { ch.tryLock() } catch (_: OverlappingFileLockException) { null } catch (_: Exception) { ch.close(); return true }
        if (l == null) {
            ch.close()
            val ok = signal(pf, args)
            System.err.println("MicMask 已在运行，${if (ok) "已把窗口前置" else "未能联系到已运行的实例"}，本进程退出")
            return false
        }
        channel = ch; lock = l
        val tok = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val ss = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = ss
        val tmp = File(dir, "instance.port.tmp")
        tmp.writeText("${ss.localPort} $tok")
        if (!tmp.renameTo(pf)) { pf.delete(); tmp.renameTo(pf) }
        Thread({
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching {
                    s.use {
                        it.soTimeout = 3000
                        val line = it.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: return@use
                        val parts = line.split(SEP)
                        if (parts.size >= 2 && parts[0] == tok && parts[1] == "ACTIVATE") {
                            it.getOutputStream().write("OK\n".toByteArray()); it.getOutputStream().flush()
                            onActivate(parts.drop(2).filter { a -> a.isNotEmpty() })
                        }
                    }
                }
            }
        }, "mm-single-instance").apply { isDaemon = true; start() }
        return true
    }

    /** 给已运行的实例发激活请求；成功返回 true */
    fun signal(pf: File = File(dir, "instance.port"), args: List<String> = emptyList()): Boolean {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            val ok = runCatching {
                val (port, tok) = pf.readText().trim().split(" ").let { it[0].toInt() to it[1] }
                Socket().use { s ->
                    s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000)
                    s.soTimeout = 3000
                    s.getOutputStream().write((listOf(tok, "ACTIVATE") + args).joinToString(SEP).plus("\n").toByteArray(Charsets.UTF_8))
                    s.getOutputStream().flush()
                    s.getInputStream().bufferedReader().readLine() == "OK"
                }
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(200)
        }
        return false
    }

    fun release() {
        runCatching { server?.close() }; runCatching { lock?.release() }; runCatching { channel?.close() }
        server = null; lock = null; channel = null
    }
}

/**
 * 托盘：自己用 java.awt.SystemTray + Swing JPopupMenu，不用 Compose 的 Tray。
 * 原因：Compose Tray 的菜单是 AWT 原生 PopupMenu，文字经 AWT 逻辑字体（Dialog）映射到系统字体，
 * 在精简（jlink）运行时里中文映射不到 → 菜单显示成方块。Swing 菜单由 Java2D 直接用指定的物理字体绘制，
 * 这里显式选能显示中文菜单文字的 Windows 字体：Microsoft YaHei UI → 微软雅黑 → SimSun → 等线 → 正黑 → Segoe UI，都不行才用 Dialog。
 * 左键单击 / 双击：前置显示主窗口；右键：菜单（每次打开时按当前收发状态重建）。
 */
object AppTray {
    /** 菜单里会出现的文字（字体检查用） */
    val SAMPLE get() = listOf("显示主窗口", "停止接收（接收中）", "开始接收（手机 → 电脑）", "开始发送（先在窗口里连一次手机）", "导出日志…", "退出 MicMask").joinToString("") { tr(it) }
    private val CANDIDATES = listOf("Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑", "SimSun", "宋体", "DengXian", "Microsoft JhengHei UI", "Segoe UI")

    /** 选出的菜单字体（第一个真实存在且能显示 SAMPLE 全部字符的） */
    val font: Font by lazy { pickFont(SAMPLE) }

    fun pickFont(sample: String, size: Int = 13): Font {
        for (n in CANDIDATES) {
            val f = Font(n, Font.PLAIN, size)
            // 不存在的字体名会被映射成 Dialog：用 family 判断是否真的拿到了这个字体
            if (!f.family.equals(Font.DIALOG, true) && f.canDisplayUpTo(sample) == -1) return f
        }
        return Font(Font.DIALOG, Font.PLAIN, size)
    }

    @Volatile var icon: TrayIcon? = null; private set
    private var host: JDialog? = null
    private var lafSet = false

    /** 安装托盘图标；不支持托盘返回 false */
    fun install(): Boolean {
        if (icon != null) return true
        if (!runCatching { SystemTray.isSupported() }.getOrDefault(false)) return false
        val img = runCatching { javax.imageio.ImageIO.read(Thread.currentThread().contextClassLoader.getResourceAsStream("micmask.png")) }.getOrNull() ?: return false
        val ti = TrayIcon(img, "MicMask").apply { isImageAutoSize = true }
        ti.addActionListener { Ui.show() }   // 双击（Windows 的 TrayIcon 动作）
        ti.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e)) showMenu(e.xOnScreen, e.yOnScreen)
                else if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) Ui.show()
            }
        })
        return runCatching { SystemTray.getSystemTray().add(ti); icon = ti; true }.getOrDefault(false).also { ok ->
            if (ok) Thread({
                var last = ""
                while (icon != null) {
                    val t = runCatching { trayTooltip() }.getOrDefault("MicMask")
                    if (t != last) { last = t; runCatching { icon?.toolTip = t } }
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                }
            }, "mm-tray-tip").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        }
    }

    fun remove() { val i = icon ?: return; icon = null; runCatching { SystemTray.getSystemTray().remove(i) } }

    fun notify(title: String, msg: String) { runCatching { icon?.displayMessage(title, msg, TrayIcon.MessageType.INFO) } }

    private fun item(m: JPopupMenu, text: String, enabled: Boolean = true, a: () -> Unit) {
        m.add(JMenuItem(text).apply { font = AppTray.font; isEnabled = enabled; addActionListener { a() } })
    }

    fun buildMenu(): JPopupMenu {
        val m = JPopupMenu()
        item(m, tr("显示主窗口")) { Ui.show() }
        m.addSeparator()
        item(m, if (DeskLink.rxOn) tr("停止接收（接收中）") else tr("开始接收（手机 → 电脑）")) { DeskLink.toggleReceive() }
        val q = DeskLink.quickTarget()
        item(m, when {
            DeskLink.txOn -> tr("停止发送（→ {0}）", DeskLink.txTarget)
            q != null -> tr("开始发送 → {0}", q.title)
            else -> tr("开始发送（先在窗口里连一次手机）")
        }, enabled = DeskLink.txOn || q != null) { if (DeskLink.txOn) DeskLink.stopSend() else q?.let { DeskLink.connectSaved(it) } }
        m.addSeparator()
        item(m, tr("导出日志…")) { Thread({ DeskLog.i("系统", DeskLog.exportInteractive()) }, "mm-export").start() }
        item(m, tr("退出 MicMask")) { AppExit.quit() }
        return m
    }

    private fun showMenu(x: Int, y: Int) = SwingUtilities.invokeLater {
        if (!lafSet) { lafSet = true; runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } }
        // 菜单挂在一个 1×1 的无边框置顶小窗上：小窗失焦（点了别处）时菜单自动收起
        val h = host ?: JDialog().apply {
            isUndecorated = true; type = java.awt.Window.Type.UTILITY; isAlwaysOnTop = true; setSize(1, 1)
            addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                override fun windowLostFocus(e: java.awt.event.WindowEvent) { isVisible = false }
            })
        }.also { host = it }
        val m = buildMenu()
        m.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {}
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) { h.isVisible = false }
            override fun popupMenuCanceled(e: PopupMenuEvent) { h.isVisible = false }
        })
        h.setLocation(x, y); h.isVisible = true; h.toFront()
        val ps = m.preferredSize
        val scr = h.graphicsConfiguration?.bounds
        val up = scr != null && y + ps.height > scr.y + scr.height - 48   // 任务栏在下方：菜单往上开
        m.show(h, 0, if (up) -ps.height else 0)
    }
}

/** 启动计时：JVM 启动 → 首帧；托盘恢复 → 首帧 */
object StartupTimer {
    val jvmStartMs: Long = runCatching { java.lang.management.ManagementFactory.getRuntimeMXBean().startTime }.getOrDefault(System.currentTimeMillis())
    @Volatile var firstFrameMs = -1L; private set
    @Volatile var lastFrameNs = 0L; private set
    private val firstLatch = java.util.concurrent.CountDownLatch(1)

    /** 根布局每次真正绘制时调用 */
    fun frame() {
        lastFrameNs = System.nanoTime()
        if (firstFrameMs < 0) {
            firstFrameMs = System.currentTimeMillis() - jvmStartMs
            firstLatch.countDown()
            DeskLog.i("系统", "首帧 $firstFrameMs ms（从 JVM 启动算）")
        }
    }

    fun awaitFirstFrame(ms: Long) = firstLatch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

    /** 等 since 之后的下一帧，返回耗时毫秒；超时 -1 */
    fun waitFrameAfter(sinceNs: Long, timeoutMs: Long = 10_000): Long {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) { if (lastFrameNs > sinceNs) return (lastFrameNs - sinceNs) / 1_000_000; Thread.sleep(2) }
        return -1
    }
}
