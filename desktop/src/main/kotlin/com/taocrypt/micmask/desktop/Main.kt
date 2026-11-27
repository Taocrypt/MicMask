package com.taocrypt.micmask.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.taocrypt.micmask.link.MmLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/*
 * MicMask 电脑版 0.x（Windows）：与手机 MicMask 用 MicMask Link v1 互传声音。
 * 第一版只有中文界面（i18n 与手机共用 en.json 放到 KMP 改造时做）。
 */

fun main(args: Array<String>) {
    if ("--smoke" in args) smokeMain(args) else gui()
}

/** --smoke：先跑无界面自测；有桌面时再真开一次窗口（Skia 走本机 DLL/D3D）约 3 秒后自动关，期间任何未捕获异常都算失败 */
private fun smokeMain(args: Array<String>) {
    var code = SelfTest.run(args)
    if (!java.awt.GraphicsEnvironment.isHeadless() && "--no-window" !in args) {
        val err = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        Thread.setDefaultUncaughtExceptionHandler { _, e -> err.compareAndSet(null, e) }
        val t0 = System.currentTimeMillis()
        runCatching {
            application {
                Window(onCloseRequest = ::exitApplication, title = "MicMask 自测", state = rememberWindowState(size = DpSize(560.dp, 860.dp))) {
                    MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) { App() }
                    LaunchedEffect(Unit) { kotlinx.coroutines.delay(3000); DeskLink.shutdown(); exitApplication() }
                }
            }
        }.onFailure { err.compareAndSet(null, it) }
        val e = err.get()
        val msg = if (e == null) "PASS  window  (${System.currentTimeMillis() - t0} ms)" else "FAIL  window  ${e.javaClass.name}: ${e.message}"
        println(msg)
        val f = args.firstOrNull { it.startsWith("--smoke-out=") }?.substringAfter('=') ?: java.io.File(System.getProperty("java.io.tmpdir"), "micmask-smoke.txt").path
        runCatching { java.io.File(f).appendText(msg + "\n" + (if (e == null && code == 0) "WINDOW OK" else "WINDOW FAILED") + "\n") }
        if (e != null) code = 1
    }
    kotlin.system.exitProcess(code)
}

private fun gui() = application {
    val icon = remember { runCatching { BitmapPainter(javax.imageio.ImageIO.read(Thread.currentThread().contextClassLoader.getResourceAsStream("micmask.png")).toComposeImageBitmap()) }.getOrNull() }
    val state = rememberWindowState(size = DpSize(560.dp, 860.dp))
    Window(onCloseRequest = { DeskLink.shutdown(); DeskBeacon.flush(); exitApplication() }, state = state, title = "MicMask", icon = icon) {
        MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) { App() }
    }
}

private val Pad = PaddingValues(horizontal = 12.dp)

@Composable
internal fun App() {
    LaunchedEffect(Unit) {
        DeskLink.addLog("MicMask 电脑版 ${BuildInfo.VERSION} 启动；${System.getProperty("os.name")} ${System.getProperty("os.version")}，Java ${System.getProperty("java.version")}")
        DeskLink.startDiscovery()
        DeskBeacon.track("app_open")
    }
    Scaffold(topBar = { SmallTopAppBar(title = "MicMask 电脑版") }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("cable") { CableCard() }
            item("rx_t") { SmallTitle("接收：手机 → 电脑（当电脑麦克风）") }
            item("rx") { ReceiveCard() }
            item("tx_t") { SmallTitle("发送：电脑 → 手机（注入 QQ / 微信 / 游戏）") }
            item("tx") { SendCard() }
            item("about_t") { SmallTitle("关于") }
            item("about") { AboutCard() }
            item("log_t") { SmallTitle("日志") }
            item("log") { LogCard() }
        }
    }
}

@Composable
private fun summaryColor() = MiuixTheme.colorScheme.onSurfaceVariantSummary

// ---------------------------------------------------------------- VB-CABLE

@Composable
private fun CableCard() {
    var has by remember { mutableStateOf(VbCable.installed()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth().padding(Pad).padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("虚拟麦克风（VB-CABLE）", fontWeight = FontWeight.SemiBold)
            Text(if (has) "已安装：接收手机声音时播放到「CABLE Input」，聊天/游戏里把麦克风选成「CABLE Output」。"
                else "未检测到 VB-CABLE。要把手机的声音当成电脑麦克风，需要先装它（只需一次，装完重启电脑）。", fontSize = 13.sp, color = summaryColor())
            if (!has) TextButton(if (busy) "正在安装…（请在弹出的 Windows 权限确认里点「是」）" else "一键安装 VB-CABLE（需要管理员权限）", modifier = Modifier.fillMaxWidth(),
                enabled = !busy, colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                    busy = true
                    scope.launch {
                        msg = withContext(Dispatchers.IO) { runCatching { VbCable.install(DeskLink::addLog) }.getOrElse { "安装出错：${it.message}" } }
                        DeskLink.addLog("VB-CABLE：$msg"); has = VbCable.installed(); busy = false
                    }
                })
            if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
            Text(VbCable.NOTICE, fontSize = 12.sp, color = summaryColor())
            Text("访问 vb-cable.com 支持作者", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { openUrl(VbCable.SITE) }.padding(vertical = 2.dp))
        }
    }
}

// ---------------------------------------------------------------- 接收

@Composable
private fun ReceiveCard() {
    val outs = remember { AudioIO.outputs() }
    var outIdx by remember {
        val saved = Settings.get("out_dev")
        mutableIntStateOf(outs.indexOfFirst { it.name == saved }.takeIf { it >= 0 } ?: outs.indexOfFirst { AudioIO.isCableInput(it.name) }.takeIf { it >= 0 } ?: 0)
    }
    Card(Modifier.fillMaxWidth().padding(Pad)) {
        SwitchPreference(checked = DeskLink.rxOn, onCheckedChange = { if (it) DeskLink.startReceive(outs.getOrNull(outIdx)) else DeskLink.stopReceive() }, title = "接收手机的声音",
            summary = if (DeskLink.rxOn) DeskLink.rxStatus else "手机「传音」页 → 发送 → 选这台电脑 → 输入下面的配对码")
        WindowDropdownPreference(items = outs.map { it.name }, selectedIndex = outIdx, title = "播放到",
            summary = if (outs.getOrNull(outIdx)?.let { AudioIO.isCableInput(it.name) } == true) "VB-CABLE：系统里多出麦克风「CABLE Output」" else "选「CABLE Input」才能当麦克风用；选扬声器/耳机 = 直接听",
            enabled = !DeskLink.rxOn, onSelectedIndexChange = { outIdx = it; Settings.set("out_dev", outs[it].name) })
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("配对码", fontSize = 13.sp, color = summaryColor())
                Text(DeskLink.code.chunked(3).joinToString(" "), Modifier.padding(horizontal = 10.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text("换一个", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = DeskLink.rxPeer == null) { DeskLink.regenCode() }.padding(6.dp))
            }
            Text("本机 ${Discovery.localIps().joinToString { it.hostAddress }.ifEmpty { "未联网" }} · 端口 ${MmLink.DEFAULT_PORT}（TCP/UDP）· 加密传输", fontSize = 12.sp, color = summaryColor())
            if (DeskLink.rxPeer != null) Text("来自 ${DeskLink.rxPeer}\n${DeskLink.rxStats}", fontSize = 12.sp, color = summaryColor())
            Text("首次接收时 Windows 防火墙会询问是否允许 MicMask 访问网络，请勾选「专用网络」并允许。", fontSize = 12.sp, color = summaryColor())
        }
    }
}

// ---------------------------------------------------------------- 发送

@Composable
private fun SendCard() {
    val ins = remember { AudioIO.inputs() }
    var inIdx by remember { val saved = Settings.get("in_dev"); mutableIntStateOf(ins.indexOfFirst { it.name == saved }.takeIf { it >= 0 } ?: 0) }
    var host by remember { mutableStateOf(Settings.get("tx_host")) }
    var picked by remember { mutableStateOf<PeerDevice?>(null) }
    var code by remember { mutableStateOf("") }
    var pcm by remember { mutableStateOf(Settings.getBool("tx_pcm", false)) }
    val primary = MiuixTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth().padding(Pad)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("把电脑上的声音（麦克风，或 RVC 等软件输出到的设备）发到手机；手机要在「传音」页打开「接收」（需要 Shizuku / ROOT / 系统权限模式才能注入）。", fontSize = 13.sp, color = summaryColor())
            Text("局域网里的手机", fontWeight = FontWeight.SemiBold)
            if (DeskLink.devices.isEmpty()) Text("还没找到开着「接收」的手机；也可以在下面手动输入手机 IP。", fontSize = 12.sp, color = summaryColor())
            DeskLink.devices.forEach { d ->
                val sel = picked == d
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (sel) primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.secondaryContainer)
                    .clickable(enabled = !DeskLink.txOn) { picked = if (sel) null else d; if (!sel) DeskLink.savedCode(d.name).takeIf { it.isNotEmpty() }?.let { code = it } }
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(d.name, fontSize = 14.sp); Text("${d.host}:${d.port}", fontSize = 12.sp, color = summaryColor()) }
                    Text(if (d.platform == "windows") "电脑" else "手机", fontSize = 12.sp, color = summaryColor())
                }
            }
            TextField(value = host, onValueChange = { host = it.trim().take(64); picked = null }, label = "手动输入手机 IP（USB：adb reverse 后填 127.0.0.1）", singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(value = code, onValueChange = { v -> code = v.filter(Char::isDigit).take(6) }, label = "配对码（手机「接收」显示的 6 位数字）", singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        WindowDropdownPreference(items = ins.map { it.name }, selectedIndex = inIdx, title = "音源（录音设备）",
            summary = "电脑麦克风，或 RVC 输出所到设备的录音端。按进程抓声音在下一版",
            enabled = !DeskLink.txOn, onSelectedIndexChange = { inIdx = it; Settings.set("in_dev", ins[it].name) })
        WindowDropdownPreference(items = listOf("Opus（省流量、抗丢包）", "PCM（USB / 热点直连）"), selectedIndex = if (pcm) 1 else 0, title = "编码",
            enabled = !DeskLink.txOn, onSelectedIndexChange = { pcm = it == 1; Settings.setBool("tx_pcm", pcm) })
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(if (DeskLink.txOn) "停止发送" else "开始发送", modifier = Modifier.fillMaxWidth(),
                colors = if (DeskLink.txOn) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColorsPrimary(), onClick = {
                    if (DeskLink.txOn) DeskLink.stopSend()
                    else {
                        val d = picked
                        if (d != null) DeskLink.startSend(d.host, d.port, d.name, code, ins.getOrNull(inIdx), pcm)
                        else {
                            Settings.set("tx_host", host)
                            val hh = host.substringBefore(':'); val pp = host.substringAfter(':', "").toIntOrNull() ?: MmLink.DEFAULT_PORT
                            if (hh.isBlank()) DeskLink.addLog("请选择手机或输入 IP") else DeskLink.startSend(hh, pp, "", code, ins.getOrNull(inIdx), pcm)
                        }
                    }
                })
            if (DeskLink.txOn) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("输入电平", fontSize = 12.sp, color = summaryColor())
                Box(Modifier.padding(start = 8.dp).width(200.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MiuixTheme.colorScheme.secondaryContainer)) {
                    Box(Modifier.fillMaxWidth(DeskLink.inLevel.coerceIn(0f, 1f)).height(6.dp).background(primary))
                }
            }
            if (DeskLink.txStatus.isNotEmpty()) Text(DeskLink.txStatus + (if (DeskLink.txOn && DeskLink.txStats.isNotEmpty()) "\n" + DeskLink.txStats else ""), fontSize = 12.sp, color = summaryColor())
        }
    }
}

// ---------------------------------------------------------------- 关于 / 更新 / 公告 / 赞赏墙 / 统计

@Composable
private fun AboutCard() {
    val scope = rememberCoroutineScope()
    var upd by remember { mutableStateOf("") }
    var updUrl by remember { mutableStateOf<String?>(null) }
    var notices by remember { mutableStateOf<List<DeskBeacon.Notice>?>(null) }
    var sponsors by remember { mutableStateOf<List<DeskBeacon.Sponsor>?>(null) }
    var analytics by remember { mutableStateOf(DeskBeacon.analytics) }
    fun check() {
        upd = "正在检查更新…"; updUrl = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.check() } }
            r.onSuccess { rel -> if (rel == null) upd = "已是最新版本（${BuildInfo.VERSION}）" else { upd = "发现新版本 ${rel.version}${if (rel.pre) "（测试版）" else ""}"; updUrl = rel.installer()?.url ?: rel.page } }
                .onFailure { upd = "检查更新失败：${it.message}" }
        }
    }
    LaunchedEffect(Unit) {
        if (Settings.getBool("upd_auto", true)) check()
        if (DeskBeacon.enabled) {
            notices = withContext(Dispatchers.IO) { DeskBeacon.notices() }
            sponsors = withContext(Dispatchers.IO) { DeskBeacon.sponsors() }
        }
    }
    Card(Modifier.fillMaxWidth().padding(Pad)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("MicMask 电脑版 ${BuildInfo.VERSION}", fontWeight = FontWeight.SemiBold)
            Text("测试版，未在真机上完整验证。开源（GPL-3.0）：github.com/Taocrypt/MicMask", fontSize = 12.sp, color = summaryColor())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(upd.ifEmpty { "检查更新（GitHub Release，电脑版标签 win-v…）" }, Modifier.weight(1f), fontSize = 13.sp, color = summaryColor())
                Text(if (updUrl != null) "下载" else "检查", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { updUrl?.let { openUrl(it) } ?: check() }.padding(6.dp))
            }
        }
        if (DeskBeacon.enabled) SwitchPreference(checked = analytics, onCheckedChange = { analytics = it; DeskBeacon.analytics = it }, title = "匿名使用统计",
            summary = "默认关。开启后只上报随机安装 ID、版本、系统版本和功能使用次数，不含声音和个人信息")
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!DeskBeacon.enabled) Text("公告、赞赏墙与使用统计：本版本未启用（电脑版接入后台后开放）。", fontSize = 12.sp, color = summaryColor())
            else {
                Text("公告", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                when { notices == null -> Text("加载中…", fontSize = 12.sp, color = summaryColor()); notices!!.isEmpty() -> Text("暂无公告", fontSize = 12.sp, color = summaryColor())
                    else -> notices!!.forEach { n -> Text("${n.title}\n${n.body}", fontSize = 12.sp, color = summaryColor()) } }
                Text("赞赏墙", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                when { sponsors == null -> Text("加载中…", fontSize = 12.sp, color = summaryColor()); sponsors!!.isEmpty() -> Text("暂无", fontSize = 12.sp, color = summaryColor())
                    else -> Text(sponsors!!.joinToString("、") { it.name }, fontSize = 12.sp, color = summaryColor()) }
            }
            Text("赞赏作者：afdian.com/a/taocrypt", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { openUrl("https://afdian.com/a/taocrypt") }.padding(vertical = 2.dp))
            Text("第三方组件：Compose Multiplatform、miuix（Apache-2.0）、Concentus/Opus（BSD-3-Clause）、JmDNS（Apache-2.0）、JSON-java（Public Domain）、VB-CABLE（VB-Audio Software，捐赠软件，vb-cable.com）。详见安装目录与仓库 NOTICE.md。",
                fontSize = 11.sp, color = summaryColor())
        }
    }
}

@Composable
private fun LogCard() {
    Card(Modifier.fillMaxWidth().padding(Pad)) {
        Column(Modifier.padding(12.dp)) {
            val lines = DeskLink.log.takeLast(40)
            Text(if (lines.isEmpty()) "（暂无）" else lines.joinToString("\n"), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = summaryColor())
            Spacer(Modifier.height(6.dp))
            Text("复制全部日志", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(DeskLink.log.joinToString("\n")), null) }
            }.padding(vertical = 2.dp))
        }
    }
}

fun openUrl(u: String) { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(u)) }.onFailure { DeskLink.addLog("打开链接失败：$u") } }
