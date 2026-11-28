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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import top.yukonga.miuix.kmp.basic.Slider
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
    var linkCode by mutableStateOf<LinkUri.Link?>(null)
    /** 本次启动待弹的公告（拉到配置后快照一次） */
    var noticeQueue by mutableStateOf<List<DeskBeacon.Notice>>(emptyList())
    var noticesQueued = false
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
        Dlg.linkCode?.let { LinkCodeDialog(it) }
        if (Ui.closePrompt) ClosePrompt()
        BeaconOverlays()
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
            if (!Ui.windowShown) { awaitWindowShown(); since = System.currentTimeMillis() }
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
        NoticeBanners()
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
private fun inputChoices(ins: List<AudioIO.Dev>, apps: List<NativeAudio.AppSession>): List<Pair<String, String>> {
    val r = ArrayList<Pair<String, String>>()
    r += "phone" to tr("手机麦克风（来自配对手机，= 接收到的声音）")
    r += "default" to AudioIO.label(null, ins, true)
    r += "pcmic" to (DeskLink.resolveInput("pcmic", ins)?.let { tr("电脑麦克风 · {0}", it.name) } ?: tr("电脑麦克风（未检测到）"))
    r += "headset" to (DeskLink.resolveInput("headset", ins)?.let { tr("耳机麦克风 · {0}", it.name) } ?: tr("耳机麦克风（未检测到）"))
    for (d in ins.filter { it.mixer != null }) r += ("dev:" + d.name) to AudioIO.label(d, ins, true)
    if (NativeAudio.processLoopbackSupported) {
        r += "sysmix" to tr("电脑播放的所有声音（除 MicMask，不用 VB-CABLE）")
        for (a in apps.distinctBy { it.exe.lowercase() }) r += ("app:" + a.exe) to tr("应用：{0}", a.title) + (if (a.active) tr("（正在出声）") else "")
    }
    val k = DeskLink.inputKey
    if (k.startsWith("dev:") && r.none { it.first == k }) r += k to tr("{0}（已拔出）", k.removePrefix("dev:"))
    if (k.startsWith("app:") && r.none { it.first == k }) r += k to tr("应用：{0}（现在没在出声）", NativeAudio.friendly(k.removePrefix("app:")))
    return r
}

@Composable
private fun SendCard() {
    val ins = AudioIO.inList
    var apps by remember { mutableStateOf(emptyList<NativeAudio.AppSession>()) }
    // 正在用声音的程序：3 秒刷新一次（只在页面开着时）
    LaunchedEffect(Unit) { while (true) { awaitWindowShown(); apps = withContext(Dispatchers.IO) { runCatching { NativeAudio.sessions() }.getOrDefault(emptyList()) }; delay(3000) } }
    val choices = inputChoices(ins, apps)
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
            if (key.startsWith("app:") || key == "sysmix") {
                Hint(tr("按应用抓声音（Windows 10 2004 及以上）：直接把这个程序播放的声音发给手机，不占用 VB-CABLE。RVC / w-okada 里把输出设备选成任意扬声器即可；这个程序要先开始出声才会出现在列表里。"))
                if (key.startsWith("app:")) WindowDropdownPreference(items = listOf(tr("只抓这个程序（含它启动的子进程）"), tr("抓除这个程序以外的所有声音")), selectedIndex = if (DeskLink.appExclude) 1 else 0,
                    title = tr("抓取范围"), enabled = !busy, onSelectedIndexChange = { DeskLink.changeAppExclude(it == 1) })
            }
            if (!NativeAudio.available) Hint(tr("音频引擎：DirectSound（兼容模式）。{0}", NativeAudio.loadError.ifEmpty { tr("设置里关掉了 WASAPI") }))
            LevelBar({ DeskLink.meter() }, if (key == "phone") tr("没有收到手机的声音：确认手机已连上右边的「接收」并在说话") else tr("所选音源几乎没有信号：可能选到了静音/没在用的设备，或没有程序往这条虚拟线播放。换一个输入通道试试"))
            DeskLink.previewError()?.let { if (!busy) Hint(tr("打不开这个录音设备：{0}", it), errC()) }
            if (DeskLink.srcError.isNotEmpty()) Hint(DeskLink.srcError, errC())
            if (key != "phone") GainRow()
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
            if (DeskLink.duplex) Hint(tr("双向：和这台手机用同一条连接同时收发"), pri())
            if (DeskLink.txError.isNotEmpty()) Text(DeskLink.txError, fontSize = S13, color = errC())
            if (busy) TextButton(if (DeskLink.txPhase == DeskLink.TxPhase.CONNECTING) tr("取消连接") else tr("停止发送"), onClick = { DeskLink.stopSend() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 输入增益 −12 ~ +24 dB + 自动增益（AGC）。电平表显示的是增益后的电平 */
@Composable
private fun GainRow() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("输入增益"), fontSize = S12, color = sub())
            Slider(value = DeskLink.gainDb, onValueChange = { DeskLink.changeGainDb(it) }, valueRange = Gain.MIN_DB..Gain.MAX_DB, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            Text("%+.1f dB".format(DeskLink.gainDb), fontSize = S12, color = sub(), fontFamily = FontFamily.Monospace)
            Link(tr("归零"), enabled = DeskLink.gainDb != 0f) { DeskLink.changeGainDb(0f) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            var agcDb by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(DeskLink.agcOn) { while (DeskLink.agcOn) { awaitWindowShown(); agcDb = DeskLink.gain.agcDb; delay(300) } }
            Text(if (DeskLink.agcOn) tr("自动增益：开（目标约 −18 dBFS，当前 {0} dB，带限幅防爆音）", "%+.1f".format(agcDb)) else tr("自动增益：关（声音忽大忽小 / 太小时可以打开）"), Modifier.weight(1f), fontSize = S12, color = sub())
            Link(if (DeskLink.agcOn) tr("停用") else tr("启用")) { DeskLink.setAgc(!DeskLink.agcOn) }
        }
        DeskLink.srcBackend().takeIf { it.isNotEmpty() && it != "sine" }?.let { Hint(tr("音频引擎：{0}", it)) }
    }
}

/** 二维码（黑白方块，四周留 4 格白边） */
@Composable
private fun QrImage(text: String, size: androidx.compose.ui.unit.Dp) {
    val m = remember(text) { runCatching { LinkUri.qr(text) }.getOrNull() } ?: return
    Canvas(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val n = m.size + 8
        val c = this.size.width / n
        for (y in m.indices) for (x in m.indices) if (m[y][x]) drawRect(Color.Black, Offset((x + 4) * c, (y + 4) * c), Size(c + 0.5f, c + 0.5f))
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
            Text(tr("粘贴手机分享的链接"), fontSize = S14, fontWeight = FontWeight.SemiBold)
            var linkText by remember { mutableStateOf("") }
            var clipLink by remember { mutableStateOf<LinkUri.Link?>(null) }
            LaunchedEffect(Unit) { while (true) { awaitWindowShown(); clipLink = clipboardLink(); delay(1500) } }
            TextField(value = linkText, onValueChange = { linkText = it.take(600) }, label = tr("micmask://link?…（手机「接收」页的链接 / 二维码内容）"), singleLine = true, modifier = Modifier.fillMaxWidth())
            val parsed = LinkUri.parse(linkText)
            if (linkText.isNotBlank() && parsed == null) Hint(tr("不是 MicMask 链接：应以 micmask://link? 开头，带 h=地址"), errC())
            if (parsed != null) Hint(tr("「{0}」· {1} · 端口 {2}{3}", parsed.name.ifEmpty { tr("手机") }, parsed.hosts.joinToString(), parsed.port, if (parsed.code.length == 6) tr(" · 含配对码") else tr(" · 不含配对码，连接时再输入")))
            TextButton(tr("导入并连接"), enabled = parsed != null && !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                val l = parsed ?: return@TextButton
                if (!DeskLink.connectLink(l)) Dlg.linkCode = l
                linkText = ""
            })
            val cl = clipLink
            if (cl != null && linkText.isBlank() && !busy) Link(tr("剪贴板里有「{0}」的链接，点这里导入", cl.name.ifEmpty { cl.hosts.first() })) { if (!DeskLink.connectLink(cl)) Dlg.linkCode = cl }
            Spacer(Modifier.height(4.dp))
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
    LaunchedEffect(Unit) { while (true) { awaitWindowShown(); clip = clipboardCode(); delay(1500) } }
    TextField(value = code, onValueChange = { v -> onChange(v.filter(Char::isDigit).take(6)) }, label = tr("配对码（手机「接收」显示的 6 位数字）"), singleLine = true, modifier = Modifier.fillMaxWidth())
    val c = clip
    if (c != null && c != code) Link(tr("粘贴剪贴板里的配对码 {0}", c.chunked(3).joinToString(" "))) { onChange(c) }
}

private fun clipboardCode(): String? = runCatching {
    val s = java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: return null
    Regex("(?<!\\d)(\\d{3})\\s?(\\d{3})(?!\\d)").find(s.take(200))?.let { it.groupValues[1] + it.groupValues[2] }
}.getOrNull()

private fun clipboardLink(): LinkUri.Link? = runCatching {
    val s = java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: return null
    LinkUri.parse(s.take(2000))
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
            if (DeskLink.rxOn) {
                val link = remember(DeskLink.code, DeskLink.deviceName, DeskLink.rxOn) { LinkUri.build(LinkUri.forReceiver()) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    QrImage(link, 150.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tr("手机扫码连接"), fontSize = S14, fontWeight = FontWeight.SemiBold)
                        Hint(tr("手机 MicMask「传音 › 发送」点扫码，扫这个二维码即可连上（含本机所有地址和配对码，不用手输）。二维码含配对码，别发给不认识的人。"))
                        var copiedL by remember { mutableStateOf(false) }
                        LaunchedEffect(copiedL) { if (copiedL) { delay(1500); copiedL = false } }
                        Link(if (copiedL) tr("已复制") else tr("复制链接")) { copyText(link); copiedL = true }
                    }
                }
            }
            Text(tr("本机「{0}」· 监听 0.0.0.0:{1}（所有网卡，TCP/UDP）· 加密传输", DeskLink.deviceName, MmLink.DEFAULT_PORT), fontSize = S12, color = sub())
            val addrs = remember(DeskLink.rxOn) { Discovery.addrs() }
            if (addrs.isEmpty()) Hint(tr("没有可用的网络：请连上 Wi-Fi / 网线"), errC())
            SelectionContainer {
                Column { addrs.forEach { a -> Text("${a.ip.hostAddress}  ·  ${a.ifName}" + (if (a.vpn) tr("（虚拟网卡 / 组网）") else ""), fontSize = S12, color = sub(), fontFamily = FontFamily.Monospace) } }
            }
            if (DeskLink.rxOn) LevelBar({ if (DeskLink.rxPeer != null) DeskLink.rxLevel else null }, tr("连上了但没有声音：手机那边可能静音或没在说话"))
            if (DeskLink.rxPeer != null) Hint(tr("来自 {0}", DeskLink.rxPeer ?: "") + (if (DeskLink.duplex) tr("（双向：同一条连接）") else "") + "\n" + DeskLink.rxStats)
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
private fun LinkCodeDialog(l: LinkUri.Link) {
    var code by remember { mutableStateOf("") }
    Overlay({ Dlg.linkCode = null }) {
        Text(tr("连接「{0}」", l.name.ifEmpty { l.hosts.first() }), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Hint(tr("链接里没有配对码：输入手机「传音 › 接收」上显示的 6 位配对码。"))
        CodeField(code) { code = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(tr("取消"), onClick = { Dlg.linkCode = null }, modifier = Modifier.weight(1f))
            TextButton(tr("连接"), enabled = code.length == 6 && !DeskLink.txOn, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                DeskLink.connectLink(l, code); Dlg.linkCode = null
            })
        }
    }
}

// ---------------------------------------------------------------- 公告 / 同意（Beacon 未启用时全部不显示）

@Composable
private fun BeaconOverlays() {
    if (!DeskBeacon.enabled) return
    val rev = DeskBeacon.configRev
    LaunchedEffect(rev) {
        if (rev > 0 && !Dlg.noticesQueued) { Dlg.noticesQueued = true; Dlg.noticeQueue = DeskBeacon.pending().filter { it.force != "block" && (it.style == "dialog" || it.force == "ack") } }
    }
    val block = remember(rev) { DeskBeacon.blocking() }
    when {
        block != null -> NoticeDialog(block, blocking = true)
        DeskBeacon.consentNeeded -> ConsentDialog()
        Dlg.noticeQueue.isNotEmpty() -> NoticeDialog(Dlg.noticeQueue.first(), blocking = false)
    }
}

@Composable
private fun ConsentDialog() {
    var stats by remember { mutableStateOf(true) }
    Overlay({}) {
        Text(tr("帮助改进 MicMask"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Hint(tr("电脑版和手机版用同一个后台：读取公告、赞赏墙。勾选后还会上报匿名使用统计：随机安装 ID、版本、Windows 版本、功能使用次数（开始接收/发送、用的输入类型）和崩溃类名。不含声音、设备名、IP、配对码或任何个人信息。随时可在「设置」里关闭。"))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { stats = !stats }.padding(vertical = 4.dp)) {
            top.yukonga.miuix.kmp.basic.Checkbox(state = androidx.compose.ui.state.ToggleableState(stats), onClick = { stats = !stats })
            Text(tr("同意匿名使用统计"), Modifier.padding(start = 8.dp), fontSize = S14)
        }
        TextButton(tr("确定"), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary(), onClick = { DeskBeacon.acceptConsent(stats) })
    }
}

@Composable
private fun NoticeDialog(n: DeskBeacon.Notice, blocking: Boolean) {
    LaunchedEffect(n.id) { if (!blocking) DeskBeacon.markShown(n) }
    var left by remember(n.id) { mutableStateOf(if (n.force == "ack") n.ackSeconds.coerceIn(0, 60) else 0) }
    LaunchedEffect(n.id) { while (left > 0) { delay(1000); left-- } }
    fun done() { Dlg.noticeQueue = Dlg.noticeQueue.filter { it.id != n.id } }
    Overlay({ if (!blocking && n.force != "ack") done() }) {
        Text(n.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (n.kind == "risk" || blocking) errC() else MiuixTheme.colorScheme.onSurface)
        SelectionContainer { Text(n.body, fontSize = S13, color = sub()) }
        n.buttons.filter { it.url != null }.forEach { b -> Link(b.text.ifEmpty { tr("打开") }) { DeskBeacon.clicked(n, b.text); openUrl(b.url!!) } }
        if (blocking) Hint(tr("维护期间暂停使用，结束后会自动恢复。"), errC())
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (n.force == "none" && n.frequency != "once") TextButton(tr("不再提示"), modifier = Modifier.weight(1f), onClick = { DeskBeacon.markMuted(n); done() })
            TextButton(if (n.force == "ack") (if (left > 0) tr("我已阅读（{0}）", left) else tr("我已阅读")) else tr("知道了"), enabled = left == 0, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(), onClick = { if (n.force == "ack") DeskBeacon.markAcked(n); done() })
        }
    }
}

/** 传音页顶部的横幅公告（style=banner，可关闭，本次启动内不再显示） */
@Composable
private fun NoticeBanners() {
    if (!DeskBeacon.enabled) return
    val rev = DeskBeacon.configRev
    var closed by remember { mutableStateOf(setOf<Long>()) }
    val list = remember(rev) { DeskBeacon.pending().filter { it.style == "banner" && it.force == "none" } }
    list.filter { it.id !in closed }.take(2).forEach { n ->
        LaunchedEffect(n.id) { DeskBeacon.markShown(n) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(pri().copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(n.title, fontSize = S13, fontWeight = FontWeight.SemiBold); if (n.body.isNotBlank()) Hint(n.body) }
            n.buttons.firstOrNull { it.url != null }?.let { b -> Link(b.text.ifEmpty { tr("打开") }) { DeskBeacon.clicked(n, b.text); openUrl(b.url!!) } }
            Link(tr("关闭")) { closed = closed + n.id }
        }
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
        Hint(tr("最小化到托盘：窗口收起，接收 / 发送 / 自动连接继续在后台工作；单击托盘图标再打开。直接退出：停止所有收发。以后可在「设置」里改。"))
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
            SwitchPreference(checked = Ui.releaseOnTray, onCheckedChange = { Ui.releaseOnTray = it; Settings.setBool("tray_release", it) }, title = tr("缩到托盘时释放窗口"),
                summary = tr("开：关到托盘时销毁窗口，更省内存，从托盘恢复稍慢；关（默认）：只隐藏窗口，恢复即时，隐藏期间暂停界面刷新"))
            var render by remember { mutableStateOf(if (Settings.get("render_api") == "SOFTWARE") 1 else 0) }
            WindowDropdownPreference(items = listOf(tr("自动（显卡加速，默认）"), tr("软件渲染（更省内存）")), selectedIndex = render, title = tr("界面渲染"),
                summary = tr("软件渲染约少占 30 MB 内存，界面动画时 CPU 略高；重启 MicMask 后生效。当前：{0}", Ui.renderApi),
                onSelectedIndexChange = { render = it; Settings.set("render_api", if (it == 1) "SOFTWARE" else "") })
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
            var nat by remember { mutableStateOf(NativeAudio.enabledSetting) }
            SwitchPreference(checked = nat, onCheckedChange = { nat = it; NativeAudio.enabledSetting = it }, title = tr("低延迟音频引擎（WASAPI）"),
                summary = if (NativeAudio.load() == null) tr("不可用：{0}。正在用 DirectSound（兼容模式）", NativeAudio.loadError)
                    else tr("开：WASAPI 共享模式，延迟更低，并支持按应用抓声音；关：DirectSound（兼容模式）。打不开的设备会自动回退。下次开始收发时生效"))
            if (DeskBeacon.enabled) {
                SwitchPreference(checked = DeskBeacon.analytics, onCheckedChange = { DeskBeacon.enableAnalytics(it) }, title = tr("匿名使用统计"),
                    summary = tr("只上报随机安装 ID、版本、Windows 版本、功能使用次数和崩溃类名，不含声音、设备名、IP、配对码和个人信息"))
            } else Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { Hint(tr("统计 / 公告 / 赞赏墙：本版本未启用（构建时没有配置后台密钥），不会发出任何统计请求。")) }
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
    var updRel by remember { mutableStateOf<Updater.Release?>(null) }
    var updBusy by remember { mutableStateOf(false) }
    var wall by remember { mutableStateOf<DeskBeacon.Wall?>(null) }
    var wallState by remember { mutableStateOf(0) }   // 0 加载中 1 成功 2 失败
    fun check() {
        upd = tr("正在检查更新…"); updUrl = null; updRel = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.check() } }
            r.onSuccess { rel -> if (rel == null) upd = tr("已是最新版本（{0}）", BuildInfo.VERSION) else { upd = tr("发现新版本 {0}", rel.version) + (if (rel.pre) tr("（测试版）") else ""); updUrl = rel.page; updRel = rel.takeIf { it.installer() != null } } }
                .onFailure { upd = tr("检查更新失败：{0}", it.message ?: "") }
        }
    }
    LaunchedEffect(Unit) {
        if (Settings.getBool("upd_auto", true)) check()
        if (DeskBeacon.enabled) {
            val r = withContext(Dispatchers.IO) { runCatching { DeskBeacon.sponsors() } }
            wall = r.getOrNull(); wallState = if (r.isSuccess && wall != null) 1 else 2
        }
    }
    val rev = DeskBeacon.configRev
    val notices = remember(rev) { DeskBeacon.notices() }
    Page(tr("关于")) {
        Section(tr("版本")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("MicMask 电脑版 {0}", BuildInfo.VERSION), fontWeight = FontWeight.SemiBold)
                Hint(tr("测试版，未在真机上完整验证。开源（GPL-3.0）：github.com/Taocrypt/MicMask"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(upd.ifEmpty { tr("检查更新（GitHub Release，电脑版标签 win-v…）") }, Modifier.weight(1f), fontSize = S13, color = sub())
                    if (updBusy) Unit
                    else if (updRel != null) Link(tr("下载并安装")) {
                        val rel = updRel!!
                        updBusy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { Updater.downloadAndInstall(rel) { got, total -> upd = tr("正在下载 {0}：{1} / {2} MB", rel.version, "%.1f".format(got / 1048576.0), "%.1f".format(total / 1048576.0)) } }
                            }
                            r.onSuccess { upd = tr("安装程序已启动，MicMask 将退出") }
                                .onFailure { upd = tr("自动安装失败：{0}。可点「发布页」手动下载安装", it.message ?: it.javaClass.simpleName); updBusy = false }
                        }
                    }
                    else Link(tr("检查")) { check() }
                    if (updUrl != null && !updBusy) Link(tr("发布页")) { openUrl(updUrl!!) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Link(tr("电脑版使用手册")) { openUrl(MANUAL_URL) }
                    Link(tr("问题反馈（GitHub Issues）")) { openUrl("https://github.com/Taocrypt/MicMask/issues") }
                }
                Hint(tr("音频引擎：{0}", if (NativeAudio.available) tr("WASAPI（mmaudio v{0}，Windows build {1}）", NativeAudio.version, NativeAudio.winBuild) else tr("DirectSound（{0}）", NativeAudio.loadError.ifEmpty { tr("设置里关掉了 WASAPI") })))
            }
        }
        Section(tr("公告")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !DeskBeacon.enabled -> Hint(tr("本版本未启用（构建时没有配置后台密钥）。"))
                    rev == 0 -> Hint(tr("加载中…"))
                    DeskBeacon.configFailed -> Hint(tr("公告暂时无法加载，稍后再看"))
                    notices.isEmpty() -> Hint(tr("暂无公告"))
                    else -> notices.forEach { n ->
                        Text(n.title, fontSize = S14, fontWeight = FontWeight.SemiBold, color = if (n.kind == "risk") errC() else MiuixTheme.colorScheme.onSurface)
                        SelectionContainer { Hint(n.body) }
                        n.buttons.filter { it.url != null }.forEach { b -> Link(b.text.ifEmpty { tr("打开") }) { DeskBeacon.clicked(n, b.text); openUrl(b.url!!) } }
                    }
                }
            }
        }
        Section(tr("赞赏")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("赞赏墙"), fontSize = S14, fontWeight = FontWeight.SemiBold)
                val w = wall
                when {
                    !DeskBeacon.enabled -> Hint(tr("本版本未启用（构建时没有配置后台密钥）。"))
                    wallState == 0 -> Hint(tr("名单加载中…"))
                    wallState == 2 || w == null -> Hint(tr("名单暂时无法加载，稍后再看"))
                    else -> {
                        val cur = w.sponsors.filter { !it.contributor && it.active }
                        val past = w.sponsors.filter { !it.contributor && !it.active }
                        val contrib = w.sponsors.filter { it.contributor } + w.contributors
                        if (cur.isEmpty() && past.isEmpty()) Hint(tr("还没有赞助者，成为第一位吧"))
                        else {
                            Hint(tr("{0} 位赞助者，谢谢你们！", w.count.coerceAtLeast(cur.size + past.size)))
                            if (cur.isNotEmpty()) Text(tr("当前赞助：") + cur.joinToString("、") { it.name + (it.tier?.let { t -> "（$t）" } ?: "") }, fontSize = S13)
                            if (past.isNotEmpty()) Hint(tr("历史赞助：") + past.take(60).joinToString("、") { it.name } + (if (past.size > 60) tr(" 等 {0} 位", past.size) else ""))
                        }
                        if (contrib.isNotEmpty()) Hint(tr("贡献者：") + contrib.take(60).joinToString("、") { it.name })
                    }
                }
                Link(tr("赞赏作者（爱发电）")) { openUrl(DeskBeacon.donateUrl()) }
            }
        }
        Section(tr("第三方组件")) {
            Column(Modifier.padding(16.dp)) {
                Hint(tr("Compose Multiplatform、miuix（Apache-2.0）、Concentus/Opus（BSD-3-Clause）、JmDNS（Apache-2.0）、JSON-java（Public Domain）、JNA（Apache-2.0）、QR Code generator（Nayuki，MIT）、VB-CABLE（VB-Audio Software，捐赠软件，vb-cable.com）。详见安装目录与仓库 NOTICE.md。"))
            }
        }
    }
}

/** 电脑版手册（仓库 docs/desktop-manual.md） */
const val MANUAL_URL = "https://github.com/Taocrypt/MicMask/blob/feat/desktop-windows/docs/desktop-manual.md"

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
