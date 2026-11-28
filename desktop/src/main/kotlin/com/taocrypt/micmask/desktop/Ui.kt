package com.taocrypt.micmask.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taocrypt.micmask.link.MmLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 电脑版界面（桌面布局）：左侧导航（传音 / 设置 / 关于 / 日志）+ 主区；传音页宽屏两栏（左发送、右接收），窄时自动上下排。
 * 所有界面文字走 tr()（中文即 key，英文表 resources/i18n/en-desktop.json）；日志不翻译。
 */

/** 弹框状态 */
private object Dlg {
    var codePeer by mutableStateOf<PeerDevice?>(null)
    var codeSaved by mutableStateOf<SavedDev?>(null)
    var rename by mutableStateOf<SavedDev?>(null)
}

@Composable private fun sub() = MiuixTheme.colorScheme.onSurfaceVariantSummary
@Composable private fun pri() = MiuixTheme.colorScheme.primary
@Composable private fun errC() = MiuixTheme.colorScheme.error

private val S12 = 12.sp
private val S13 = 13.sp
private val S14 = 14.sp

@Composable
fun Root() {
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxSize()) {
            NavRail()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (Ui.page) { 1 -> SettingsPage(); 2 -> AboutPage(); 3 -> LogPage(); else -> LinkPage() }
            }
        }
        if (Dlg.codePeer != null || Dlg.codeSaved != null) CodeDialog()
        Dlg.rename?.let { RenameDialog(it) }
        if (Ui.closePrompt) ClosePrompt()
    }
}

// ---------------------------------------------------------------- 导航

@Composable
private fun NavRail() {
    Column(Modifier.width(188.dp).fillMaxHeight().background(MiuixTheme.colorScheme.surfaceContainer).padding(horizontal = 10.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.padding(start = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            appIcon?.let { Icon(it, null, Modifier.size(28.dp), tint = Color.Unspecified) }
            Column(Modifier.padding(start = 10.dp)) {
                Text("MicMask", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(tr("电脑版 {0}", BuildInfo.VERSION), fontSize = 11.sp, color = sub())
            }
        }
        NavItem(0, MiuixIcons.Link, tr("传音"))
        NavItem(1, MiuixIcons.Settings, tr("设置"))
        NavItem(2, MiuixIcons.Info, tr("关于"))
        NavItem(3, MiuixIcons.Notes, tr("日志"))
        Spacer(Modifier.weight(1f))
        // 底部：当前收发状态
        val st = listOfNotNull(
            if (DeskLink.rxOn) (if (DeskLink.rxPeer != null) tr("● 接收中") else tr("○ 等待手机连接")) else null,
            when (DeskLink.txPhase) { DeskLink.TxPhase.ON -> tr("● 发送中 → {0}", DeskLink.txTarget); DeskLink.TxPhase.CONNECTING -> tr("… 正在连接"); else -> null },
        )
        Text(if (st.isEmpty()) tr("空闲") else st.joinToString("\n"), fontSize = S12, color = sub(), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun NavItem(idx: Int, icon: ImageVector, label: String) {
    val sel = Ui.page == idx
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) pri().copy(alpha = 0.12f) else Color.Transparent)
        .clickable { Ui.page = idx }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, Modifier.size(20.dp), tint = if (sel) pri() else MiuixTheme.colorScheme.onSurface)
        Text(label, Modifier.padding(start = 12.dp), fontSize = S14, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) pri() else MiuixTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Page(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, fontSize = S13, color = sub())
        }
        content()
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = S13, fontWeight = FontWeight.SemiBold, color = sub(), modifier = Modifier.padding(start = 4.dp))
        Card(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun Link(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(text, fontSize = S13, color = if (enabled) pri() else MiuixTheme.colorScheme.disabledOnSurface,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp))
}

@Composable
private fun Hint(text: String, color: Color = sub()) { Text(text, fontSize = S12, color = color) }

@Composable
private fun Notice(text: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(errC().copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = S13, color = errC())
        Link(tr("知道了"), onClick = onClose)
    }
}

/** 电平条：每 100 ms 读一次音源电平（不在音频线程里写 Compose 状态） */
@Composable
private fun LevelBar(src: () -> AudioIO.Level?, silentHint: String) {
    var lv by remember { mutableFloatStateOf(0f) }
    var db by remember { mutableFloatStateOf(-120f) }
    var silent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var since = System.currentTimeMillis()
        while (true) {
            delay(100)
            val l = src()
            if (l == null) { lv = 0f; db = -120f; silent = false; since = System.currentTimeMillis(); continue }
            lv = lv * 0.6f + l.peak * 0.4f; db = l.peakDb
            val last = maxOf(l.lastSignalMs, since)
            silent = System.currentTimeMillis() - last > 3000
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("输入电平"), fontSize = S12, color = sub())
            Box(Modifier.padding(horizontal = 10.dp).weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MiuixTheme.colorScheme.secondaryContainer)) {
                Box(Modifier.fillMaxWidth(lv.coerceIn(0f, 1f)).height(8.dp).background(if (db > -1f) errC() else pri()))
            }
            Text(if (db <= -119f) "−∞ dBFS" else "%.0f dBFS".format(db), fontSize = S12, color = sub(), fontFamily = FontFamily.Monospace)
        }
        if (silent && src() != null) Hint(silentHint, errC())
    }
}

// ================================================================ 传音页

@Composable
fun LinkPage() {
    LaunchedEffect(Unit) { AudioIO.refresh() }
    Page(tr("传音"), tr("电脑和手机 MicMask 互传声音：左边把电脑的声音发到手机，右边把手机的声音收进电脑")) {
        if (DeskLink.devNotice.isNotEmpty()) Notice(DeskLink.devNotice) { DeskLink.devNotice = "" }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 760.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { SendCard(); TargetsCard() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { ReceiveCard(); CableCard() }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { SendCard(); TargetsCard(); ReceiveCard(); CableCard() }
        }
    }
}

/** 输入通道列表：手机麦克风、系统默认、电脑麦克风、耳机麦克风 + 所有录音设备（全名 + 类型 + 默认标记） */
private fun inputChoices(ins: List<AudioIO.Dev>): List<Pair<String, String>> {
    val r = ArrayList<Pair<String, String>>()
    r += "phone" to tr("手机麦克风（来自配对手机，= 接收到的声音）")
    r += "default" to AudioIO.label(null, ins, true)
    r += "pcmic" to (DeskLink.resolveInput("pcmic", ins)?.let { tr("电脑麦克风 · {0}", it.name) } ?: tr("电脑麦克风（未检测到）"))
    r += "headset" to (DeskLink.resolveInput("headset", ins)?.let { tr("耳机麦克风 · {0}", it.name) } ?: tr("耳机麦克风（未检测到）"))
    for (d in ins.filter { it.mixer != null }) r += ("dev:" + d.name) to AudioIO.label(d, ins, true)
    val k = DeskLink.inputKey
    if (k.startsWith("dev:") && r.none { it.first == k }) r += k to tr("{0}（已拔出）", k.removePrefix("dev:"))
    return r
}

@Composable
private fun SendCard() {
    val ins = AudioIO.inList
    val choices = inputChoices(ins)
    val key = DeskLink.inputKey
    val busy = DeskLink.txOn
    // 没在发送时也开着所选设备看电平（窗口关掉 / 切页时停）
    DisposableEffect(key, busy) {
        if (!busy) DeskLink.startPreview(key)
        onDispose { DeskLink.stopPreview() }
    }
    Section(tr("发送：电脑 → 手机")) {
        WindowDropdownPreference(items = choices.map { it.second }, selectedIndex = choices.indexOfFirst { it.first == key }.coerceAtLeast(0), title = tr("输入通道"),
            summary = if (busy) tr("发送中不能更换，先停止发送") else tr("决定发给手机的声音和下面的电平表；设备插拔会自动刷新"),
            enabled = !busy, onSelectedIndexChange = { DeskLink.setInput(choices[it].first) })
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (key == "phone") {
                Hint(tr("「手机麦克风」= 用右边「接收」收到的手机声音：它会播放到右边所选输出（默认 CABLE Input，系统里多出麦克风「CABLE Output」），这就是「手机当电脑麦克风」。在下面选另一台手机并开始发送，还可以把它再转发出去。"))
                if (!DeskLink.rxOn) TextButton(tr("开启接收（手机 → 电脑）"), onClick = { DeskLink.startReceive() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
            }
            LevelBar({ DeskLink.meter() }, if (key == "phone") tr("没有收到手机的声音：确认手机已连上右边的「接收」并在说话") else tr("所选音源几乎没有信号：可能选到了静音/没在用的设备，或没有程序往这条虚拟线播放。换一个输入通道试试"))
            DeskLink.previewError()?.let { if (!busy) Hint(tr("打不开这个录音设备：{0}", it), errC()) }
        }
        WindowDropdownPreference(items = listOf(tr("跟随手机（默认，手机选什么就用什么）"), tr("Opus（省流量、抗丢包）"), tr("PCM（无压缩，USB / 热点直连）")),
            selectedIndex = DeskLink.codecMode, title = tr("编码"), enabled = !busy, onSelectedIndexChange = { DeskLink.setCodec(it) })
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (DeskLink.txPhase) {
                DeskLink.TxPhase.CONNECTING -> Text(DeskLink.txStatus, fontSize = S13, color = pri())
                DeskLink.TxPhase.ON -> {
                    Text(DeskLink.txStatus, fontSize = S13, fontWeight = FontWeight.SemiBold)
                    if (DeskLink.txStats.isNotEmpty()) Hint(DeskLink.txStats)
                }
                else -> Hint(if (DeskLink.txStatus.isNotEmpty() && DeskLink.txError.isEmpty()) DeskLink.txStatus else tr("在下面「手机」里点一台手机即可连接发送；手机要在「传音」页打开「接收」（需要 Shizuku / ROOT / 系统权限模式才能注入 QQ / 微信 / 游戏）"))
            }
            if (DeskLink.txError.isNotEmpty()) Text(DeskLink.txError, fontSize = S13, color = errC())
            if (busy) TextButton(if (DeskLink.txPhase == DeskLink.TxPhase.CONNECTING) tr("取消连接") else tr("停止发送"), onClick = { DeskLink.stopSend() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TargetsCard() {
    var host by remember { mutableStateOf(Settings.get("tx_host")) }
    var code by remember { mutableStateOf("") }
    val busy = DeskLink.txOn
    Section(tr("手机")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("局域网里开着「接收」的手机"), fontSize = S14, fontWeight = FontWeight.SemiBold)
            val found = DeskLink.devices
            if (found.isEmpty()) Hint(tr("正在扫描…还没发现。请在手机「传音」页打开「接收」并保持在同一 Wi-Fi；异地组网（EasyTier / Tailscale 等）扫不到，请用下面的手动连接。"))
            found.forEach { p ->
                val sv = SavedDevices.match(p)
                val cur = busy && (DeskLink.txTarget == (sv?.title ?: p.name))
                DevRow(sv?.title ?: p.name, "${p.host}:${p.port} · " + (if (p.platform == "windows") tr("电脑") else tr("手机")) + (if (sv != null) tr(" · 已保存") else ""),
                    action = if (cur) tr("发送中") else if (sv?.code?.length == 6) tr("连接") else tr("添加"), enabled = !busy,
                    disabledWhy = tr("正在发送，先停止")) {
                    if (!DeskLink.connectPeer(p)) Dlg.codePeer = p
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(tr("已保存的设备"), fontSize = S14, fontWeight = FontWeight.SemiBold)
            val saved = SavedDevices.list
            if (saved.isEmpty()) Hint(tr("还没有。连接成功一次后会按设备 ID 记住，下次点一下或自动连接。"))
            saved.forEach { d ->
                val online = DeskLink.devices.any { d.matches(it) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.secondaryContainer).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title, fontSize = S14)
                        Hint((if (online) tr("在线") else tr("未发现")) + " · ${d.host}:${d.port}" + (if (d.code.length != 6) tr(" · 需要重新输入配对码") else "") + (if (!d.auto) tr(" · 不自动连接") else ""))
                    }
                    Link(if (d.code.length == 6) tr("连接") else tr("输入配对码"), enabled = !busy) { if (!DeskLink.connectSaved(d)) Dlg.codeSaved = d }
                    Link(if (d.auto) tr("自动✓") else tr("自动✗")) { SavedDevices.update(d.id) { it.copy(auto = !it.auto) } }
                    Link(tr("重命名")) { Dlg.rename = d }
                    Link(tr("删除")) { SavedDevices.remove(d.id) }
                }
            }
        }
        SwitchPreference(checked = DeskLink.autoConnect, onCheckedChange = { DeskLink.setAuto(it) }, title = tr("自动连接"),
            summary = tr("已保存的手机被发现或上次地址可达时自动连接发送，断线后自动重连（间隔 2 秒起逐步加长到 1 分钟）；手动点「停止发送」后不再自动连，直到你再手动连一次"))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("手动连接（IP）"), fontSize = S14, fontWeight = FontWeight.SemiBold)
            TextField(value = host, onValueChange = { host = it.trim().take(64) }, label = tr("手机 IP（可带端口，如 192.168.1.5:47310；USB：adb reverse 后填 127.0.0.1）"), singleLine = true, modifier = Modifier.fillMaxWidth())
            CodeField(code) { code = it }
            val why = when { busy -> tr("正在发送，先停止"); host.isBlank() -> tr("先填手机 IP"); code.length != 6 -> tr("先填 6 位配对码"); else -> null }
            TextButton(tr("连接并发送"), enabled = why == null, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                Settings.set("tx_host", host)
                val hh = host.substringBefore(':'); val pp = host.substringAfter(':', "").toIntOrNull() ?: MmLink.DEFAULT_PORT
                DeskLink.startSend(hh, pp, "", code)
            })
            if (why != null) Hint(why)
        }
    }
}

@Composable
private fun DevRow(title: String, summary: String, action: String, enabled: Boolean, disabledWhy: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.secondaryContainer)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = S14); Hint(summary) }
        Text(if (enabled) action else disabledWhy, fontSize = S13, color = if (enabled) pri() else sub())
    }
}

/** 配对码输入：只收数字（粘贴「123 456」也行）；剪贴板里有 6 位码时给一个一键粘贴 */
@Composable
private fun CodeField(code: String, onChange: (String) -> Unit) {
    var clip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { clip = clipboardCode(); delay(1500) } }
    TextField(value = code, onValueChange = { v -> onChange(v.filter(Char::isDigit).take(6)) }, label = tr("配对码（手机「接收」显示的 6 位数字）"), singleLine = true, modifier = Modifier.fillMaxWidth())
    val c = clip
    if (c != null && c != code) Link(tr("粘贴剪贴板里的配对码 {0}", c.chunked(3).joinToString(" "))) { onChange(c) }
}

private fun clipboardCode(): String? = runCatching {
    val s = java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: return null
    Regex("(?<!\\d)(\\d{3})\\s?(\\d{3})(?!\\d)").find(s.take(200))?.let { it.groupValues[1] + it.groupValues[2] }
}.getOrNull()

fun copyText(s: String) { runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(s), null) } }

@Composable
private fun ReceiveCard() {
    val outs = AudioIO.outList
    val sel = DeskLink.resolveOutput(outs.ifEmpty { listOf(AudioIO.Dev("", null, isDefault = true)) })
    val labels = outs.map { AudioIO.label(it, outs, false) }
    Section(tr("接收：手机 → 电脑（当电脑麦克风）")) {
        SwitchPreference(checked = DeskLink.rxOn, onCheckedChange = { if (it) DeskLink.startReceive() else DeskLink.stopReceive() }, title = tr("接收手机的声音"),
            summary = if (DeskLink.rxOn) DeskLink.rxStatus else tr("手机「传音」页 → 发送 → 选这台电脑 → 输入下面的配对码"))
        WindowDropdownPreference(items = labels.ifEmpty { listOf(tr("系统默认播放设备")) }, selectedIndex = outs.indexOf(sel).coerceAtLeast(0), title = tr("播放到"),
            summary = if (DeskLink.rxOn) tr("接收中不能更换，先关闭接收") else if (sel?.let { AudioIO.isCableInput(it.name) } == true) tr("VB-CABLE：系统里多出麦克风「CABLE Output」，聊天/游戏里选它") else tr("选「CABLE Input」才能当麦克风用；选扬声器/耳机 = 直接听"),
            enabled = !DeskLink.rxOn, onSelectedIndexChange = { DeskLink.setOutput(outs[it].name) })
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("配对码"), fontSize = S13, color = sub())
                SelectionContainer { Text(DeskLink.code.chunked(3).joinToString(" "), Modifier.padding(horizontal = 12.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = pri(), fontFamily = FontFamily.Monospace) }
                Spacer(Modifier.weight(1f))
                var copied by remember { mutableStateOf(false) }
                LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
                Link(if (copied) tr("已复制") else tr("复制")) { copyText(DeskLink.code); copied = true }
                Link(tr("换一个"), enabled = DeskLink.rxPeer == null) { DeskLink.regenCode() }
            }
            if (DeskLink.rxPeer != null) Hint(tr("手机连着时不能换配对码"))
            Text(tr("本机「{0}」· 监听 0.0.0.0:{1}（所有网卡，TCP/UDP）· 加密传输", DeskLink.deviceName, MmLink.DEFAULT_PORT), fontSize = S12, color = sub())
            val addrs = remember(DeskLink.rxOn) { Discovery.addrs() }
            if (addrs.isEmpty()) Hint(tr("没有可用的网络：请连上 Wi-Fi / 网线"), errC())
            SelectionContainer {
                Column { addrs.forEach { a -> Text("${a.ip.hostAddress}  ·  ${a.ifName}" + (if (a.vpn) tr("（虚拟网卡 / 组网）") else ""), fontSize = S12, color = sub(), fontFamily = FontFamily.Monospace) } }
            }
            if (DeskLink.rxOn) LevelBar({ if (DeskLink.rxPeer != null) DeskLink.rxLevel else null }, tr("连上了但没有声音：手机那边可能静音或没在说话"))
            if (DeskLink.rxPeer != null) Hint(tr("来自 {0}", DeskLink.rxPeer ?: "") + "\n" + DeskLink.rxStats)
            Hint(tr("首次接收时 Windows 防火墙会询问是否允许 MicMask 访问网络，请勾选「专用网络」并允许。"))
        }
    }
}

@Composable
private fun CableCard() {
    var has by remember { mutableStateOf(VbCable.installed()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Section(tr("虚拟麦克风（VB-CABLE）")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (has) tr("已安装：接收手机声音时播放到「CABLE Input」，聊天/游戏里把麦克风选成「CABLE Output」。")
                else tr("未检测到 VB-CABLE。要把手机的声音当成电脑麦克风，需要先装它（只需一次，装完重启电脑）。"), fontSize = S13, color = sub())
            if (!has) TextButton(if (busy) tr("正在安装…（请在弹出的 Windows 权限确认里点「是」）") else tr("一键安装 VB-CABLE（需要管理员权限）"), modifier = Modifier.fillMaxWidth(),
                enabled = !busy, colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                    busy = true
                    scope.launch {
                        msg = withContext(Dispatchers.IO) { runCatching { VbCable.install(DeskLink::addLog) }.getOrElse { "安装出错：${it.message}" } }
                        DeskLink.addLog("VB-CABLE：$msg"); has = VbCable.installed(); busy = false
                    }
                })
            if (msg.isNotEmpty()) Text(msg, fontSize = S12, color = pri())
            Hint(tr(VbCable.NOTICE))
            Link(tr("访问 vb-cable.com 支持作者")) { openUrl(VbCable.SITE) }
        }
    }
}

// ================================================================ 弹框

@Composable
private fun Overlay(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 460.dp).fillMaxWidth(0.9f).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun CodeDialog() {
    val p = Dlg.codePeer; val d = Dlg.codeSaved
    val name = d?.title ?: p?.name ?: ""
    var code by remember { mutableStateOf("") }
    fun close() { Dlg.codePeer = null; Dlg.codeSaved = null }
    Overlay(::close) {
        Text(tr("连接「{0}」", name), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Hint(tr("输入手机「传音 › 接收」上显示的 6 位配对码。连接成功后会记住这台手机，下次点一下就连。"))
        CodeField(code) { code = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(tr("取消"), onClick = ::close, modifier = Modifier.weight(1f))
            TextButton(tr("连接"), enabled = code.length == 6 && !DeskLink.txOn, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                if (p != null) DeskLink.connectPeer(p, code) else if (d != null) DeskLink.connectSaved(d, code)
                close()
            })
        }
        if (DeskLink.txOn) Hint(tr("正在发送，先停止"))
    }
}

@Composable
private fun RenameDialog(d: SavedDev) {
    var name by remember(d.id) { mutableStateOf(d.title) }
    Overlay({ Dlg.rename = null }) {
        Text(tr("重命名"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Hint(tr("设备 ID {0}…（按 ID 识别，改名不影响连接）", d.id.take(8)))
        TextField(value = name, onValueChange = { name = it.take(40) }, label = tr("名字"), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(tr("取消"), onClick = { Dlg.rename = null }, modifier = Modifier.weight(1f))
            TextButton(tr("保存"), modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                SavedDevices.update(d.id) { it.copy(alias = if (name.trim() == it.name) "" else name.trim()) }; Dlg.rename = null
            })
        }
    }
}

@Composable
private fun ClosePrompt() {
    Overlay({ Ui.closePrompt = false }) {
        Text(tr("关闭窗口后…"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Hint(tr("最小化到托盘：窗口收起，接收 / 发送 / 自动连接继续在后台工作（占用内存更少）；双击托盘图标再打开。直接退出：停止所有收发。以后可在「设置」里改。"))
        TextButton(tr("最小化到托盘（推荐）"), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
            Settings.set("close_action", "tray"); Ui.closePrompt = false; Ui.hideToTray()
        })
        TextButton(tr("直接退出"), modifier = Modifier.fillMaxWidth(), onClick = { Settings.set("close_action", "exit"); Ui.closePrompt = false; AppExit.quit() })
    }
}

// ================================================================ 设置

@Composable
private fun SettingsPage() {
    Page(tr("设置")) {
        Section(tr("通用")) {
            WindowDropdownPreference(items = listOf(tr("跟随系统"), "简体中文", "English"), selectedIndex = I18n.lang, title = tr("语言"), onSelectedIndexChange = { I18n.set(it) })
            val ca = Settings.get("close_action")
            var close by remember { mutableStateOf(when (ca) { "exit" -> 1; "tray" -> 0; else -> 2 }) }
            WindowDropdownPreference(items = listOf(tr("最小化到托盘"), tr("直接退出"), tr("每次询问")), selectedIndex = close, title = tr("点关闭按钮时"),
                summary = if (!Ui.trayOk) tr("这台电脑不支持托盘图标，关闭即退出") else tr("托盘里收发继续工作；托盘菜单可开始/停止接收和发送、退出"),
                onSelectedIndexChange = { close = it; Settings.set("close_action", when (it) { 0 -> "tray"; 1 -> "exit"; else -> "" }) })
            var restore by remember { mutableStateOf(Settings.getBool("rx_restore", true)) }
            SwitchPreference(checked = restore, onCheckedChange = { restore = it; Settings.setBool("rx_restore", it) }, title = tr("启动时恢复接收"),
                summary = tr("上次退出时开着「接收」，下次启动自动打开"))
            var upd by remember { mutableStateOf(Settings.getBool("upd_auto", true)) }
            SwitchPreference(checked = upd, onCheckedChange = { upd = it; Settings.setBool("upd_auto", it) }, title = tr("启动时检查更新"), summary = tr("只查 GitHub Release 的电脑版标签，不自动下载"))
        }
        Section(tr("本机设备名")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var name by remember { mutableStateOf(DeskLink.deviceName) }
                Hint(tr("手机扫描列表和握手里显示的名字（mDNS 广播用它）。默认「{0}」。", DeskLink.defaultName()))
                TextField(value = name, onValueChange = { name = it.take(40) }, label = tr("设备名"), singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(tr("恢复默认"), onClick = { name = DeskLink.defaultName(); DeskLink.setName(name) }, modifier = Modifier.weight(1f))
                    TextButton(tr("保存"), enabled = name.trim() != DeskLink.deviceName, onClick = { DeskLink.setName(name) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
        Section(tr("传音")) {
            SwitchPreference(checked = DeskLink.autoConnect, onCheckedChange = { DeskLink.setAuto(it) }, title = tr("自动连接"), summary = tr("已保存的手机出现时自动连接发送，断线自动重连"))
            if (DeskBeacon.enabled) {
                var analytics by remember { mutableStateOf(DeskBeacon.analytics) }
                SwitchPreference(checked = analytics, onCheckedChange = { analytics = it; DeskBeacon.analytics = it }, title = tr("匿名使用统计"),
                    summary = tr("默认关。开启后只上报随机安装 ID、版本、系统版本和功能使用次数，不含声音和个人信息"))
            }
        }
        Section(tr("数据与日志")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Hint(tr("设置与已保存设备存在 {0}", Settings.dir.path))
                Hint(tr("日志文件：{0}（按天保存，保留最近 7 个文件、最多 20 MB）", DeskLog.dir.path))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Link(tr("打开设置文件夹")) { runCatching { java.awt.Desktop.getDesktop().open(Settings.dir) } }
                    Link(tr("打开日志文件夹")) { runCatching { java.awt.Desktop.getDesktop().open(DeskLog.dir) } }
                    ExportLink()
                }
                Hint(tr("导出为 zip：日志 + 系统信息（Windows / Java 版本、CPU 与内存、音频设备、网卡地址、版本号、设置项）。配对码和密钥不会写入。"))
            }
        }
    }
}

// ================================================================ 关于 / 更新 / 公告 / 赞赏

@Composable
private fun AboutPage() {
    val scope = rememberCoroutineScope()
    var upd by remember { mutableStateOf("") }
    var updUrl by remember { mutableStateOf<String?>(null) }
    var notices by remember { mutableStateOf<List<DeskBeacon.Notice>?>(null) }
    var sponsors by remember { mutableStateOf<List<DeskBeacon.Sponsor>?>(null) }
    fun check() {
        upd = tr("正在检查更新…"); updUrl = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.check() } }
            r.onSuccess { rel -> if (rel == null) upd = tr("已是最新版本（{0}）", BuildInfo.VERSION) else { upd = tr("发现新版本 {0}", rel.version) + (if (rel.pre) tr("（测试版）") else ""); updUrl = rel.installer()?.url ?: rel.page } }
                .onFailure { upd = tr("检查更新失败：{0}", it.message ?: "") }
        }
    }
    LaunchedEffect(Unit) {
        if (Settings.getBool("upd_auto", true)) check()
        if (DeskBeacon.enabled) {
            notices = withContext(Dispatchers.IO) { DeskBeacon.notices() }
            sponsors = withContext(Dispatchers.IO) { DeskBeacon.sponsors() }
        }
    }
    Page(tr("关于")) {
        Section(tr("版本")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("MicMask 电脑版 {0}", BuildInfo.VERSION), fontWeight = FontWeight.SemiBold)
                Hint(tr("测试版，未在真机上完整验证。开源（GPL-3.0）：github.com/Taocrypt/MicMask"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(upd.ifEmpty { tr("检查更新（GitHub Release，电脑版标签 win-v…）") }, Modifier.weight(1f), fontSize = S13, color = sub())
                    Link(if (updUrl != null) tr("下载") else tr("检查")) { updUrl?.let { openUrl(it) } ?: check() }
                }
            }
        }
        Section(tr("公告")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !DeskBeacon.enabled -> Hint(tr("本版本未启用（电脑版接入后台后开放）。"))
                    notices == null -> Hint(tr("加载中…"))
                    notices!!.isEmpty() -> Hint(tr("暂无公告"))
                    else -> notices!!.forEach { n -> Text(n.title, fontSize = S14, fontWeight = FontWeight.SemiBold); Hint(n.body) }
                }
            }
        }
        Section(tr("赞赏")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("赞赏墙"), fontSize = S14, fontWeight = FontWeight.SemiBold)
                when {
                    !DeskBeacon.enabled -> Hint(tr("本版本未启用（电脑版接入后台后开放）。"))
                    sponsors == null -> Hint(tr("加载中…"))
                    sponsors!!.isEmpty() -> Hint(tr("暂无"))
                    else -> Hint(sponsors!!.joinToString("、") { it.name })
                }
                Link(tr("赞赏作者：afdian.com/a/taocrypt")) { openUrl("https://afdian.com/a/taocrypt") }
            }
        }
        Section(tr("第三方组件")) {
            Column(Modifier.padding(16.dp)) {
                Hint(tr("Compose Multiplatform、miuix（Apache-2.0）、Concentus/Opus（BSD-3-Clause）、JmDNS（Apache-2.0）、JSON-java（Public Domain）、VB-CABLE（VB-Audio Software，捐赠软件，vb-cable.com）。详见安装目录与仓库 NOTICE.md。"))
            }
        }
    }
}

// ================================================================ 日志

@Composable
private fun LogPage() {
    var lv by remember { mutableStateOf(0) }   // 0 全部 1 W+E 2 只看 E
    val all = DeskLog.tail
    val shown = all.filter { when (lv) { 1 -> it.level == 'W' || it.level == 'E'; 2 -> it.level == 'E'; else -> true } }.takeLast(400)
    Page(tr("日志"), tr("反馈问题时请点「导出日志」把 zip 发给作者；日志已脱敏，不含声音和配对码")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(tr("全部"), tr("警告和错误"), tr("只看错误")).forEachIndexed { i, t ->
                Text(t, fontSize = S13, color = if (lv == i) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (lv == i) pri() else MiuixTheme.colorScheme.secondaryContainer).clickable { lv = i }.padding(horizontal = 12.dp, vertical = 5.dp))
            }
            Spacer(Modifier.weight(1f))
            var copied by remember { mutableStateOf(false) }
            LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
            Link(if (copied) tr("已复制") else tr("复制显示的日志")) { copyText(shown.joinToString("\n") { it.line() }); copied = true }
            ExportLink()
        }
        Hint(tr("日志文件：{0}（按天保存，保留最近 7 个文件、最多 20 MB）", DeskLog.dir.path))
        Card(Modifier.fillMaxWidth()) {
            SelectionContainer {
                Column(Modifier.padding(14.dp)) {
                    if (shown.isEmpty()) Text(tr("（暂无）"), fontSize = 11.sp, color = sub())
                    shown.forEach { e ->
                        Text(e.line(), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = when (e.level) { 'E' -> errC(); 'W' -> Color(0xFFE08A00); else -> sub() })
                    }
                }
            }
        }
    }
}

/** 「导出日志」：系统保存对话框（默认桌面），导出后在资源管理器中选中 */
@Composable
private fun ExportLink() {
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Link(if (busy) tr("正在导出…") else tr("导出日志"), enabled = !busy) {
        busy = true
        scope.launch { msg = withContext(Dispatchers.IO) { DeskLog.exportInteractive() }; busy = false }
    }
    if (msg.isNotEmpty()) Text(msg, fontSize = S12, color = sub())
}

fun openUrl(u: String) { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(u)) }.onFailure { DeskLink.addLog("打开链接失败：$u") } }
