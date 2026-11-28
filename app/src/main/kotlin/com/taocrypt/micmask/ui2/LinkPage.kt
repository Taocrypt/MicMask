package com.taocrypt.micmask.ui2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taocrypt.micmask.MainViewModel
import com.taocrypt.micmask.UiState
import com.taocrypt.micmask.link.LinkHub
import com.taocrypt.micmask.link.LinkState
import com.taocrypt.micmask.link.MicYou
import com.taocrypt.micmask.link.MmLink
import com.taocrypt.micmask.link.MmNet
import com.taocrypt.micmask.link.SavedDevice
import com.taocrypt.micmask.tr
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Paste
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.ScreenMirroring
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/*
 * 「传音」页（2.1.1-alpha.13 起重排）：发送 / 接收 / 高级 三组。
 *  - 正文 15–16 sp、说明 13–14 sp；长说明收进「高级 › 使用说明」弹窗。
 *  - 状态行：彩色圆点（灰 未连接 / 橙 连接中·重连中，闪烁 / 绿 已连接 / 红 出错）+ 标签（UDP/TCP · 编码 · 延迟 · 丢包）。
 *  - 设备：已保存设备（按设备 ID 认，可重命名/删除）+ 附近设备（mDNS），点一下直接连接，缺配对码才弹输入框；手填地址兜底。
 *  - 自动连接（默认开）：打开 App 时恢复接收、连上配对过的设备，断线退避重连；逻辑在 LinkHub。
 */

private val LinkPad = PaddingValues(horizontal = 12.dp)
private val Green = Color(0xFF2FB45A)
private val Amber = Color(0xFFF29A0C)

internal fun LazyListScope.linkPage(vm: MainViewModel, st: UiState) {
    item(key = "link_send_t") { SmallTitle(tr("发送")) }
    item(key = "link_send") { LinkSendCard(st) }
    item(key = "link_recv_t") { SmallTitle(tr("接收")) }
    item(key = "link_recv") { LinkRecvCard(st) }
    item(key = "link_adv") { LinkAdvanced() }
    item(key = "link_end") { Spacer(Modifier.height(24.dp)) }
}

// ------------------------------------------------------------------ 小部件

private fun toast(ctx: Context, msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

private fun copyText(ctx: Context, text: String, done: String) {
    runCatching {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MicMask", text))
        toast(ctx, done)
    }
}

/** 剪贴板里的 6 位配对码（没有返回空）。只在用户打开输入框 / 点粘贴时读 */
private fun clipCode(ctx: Context): String = runCatching {
    val c = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
    LinkHub.findCode(c?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx))
}.getOrDefault("")

/** 状态：0 空闲 / 1 进行中（闪烁）/ 2 正常 / 3 出错 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusLine(state: Int, text: String, chips: List<String> = emptyList(), detail: String = "") {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val color = when (state) { 1 -> Amber; 2 -> Green; 3 -> MiuixTheme.colorScheme.error; else -> summary }
    val pulse = if (state == 1) rememberInfiniteTransition(label = "pulse").animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value else 1f
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 12.dp)
        .animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (state == 3) color else MiuixTheme.colorScheme.onSurface)
        }
        if (chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { c ->
                Text(c, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MiuixTheme.colorScheme.surface.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        if (detail.isNotEmpty()) Text(detail, fontSize = 13.sp, color = summary)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)

@Composable
private fun DeviceRow(phone: Boolean, title: String, sub: String, tag: String?, enabled: Boolean, active: Boolean, onClick: () -> Unit, onEdit: (() -> Unit)? = null) {
    val primary = MiuixTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (active) primary.copy(alpha = 0.14f) else MiuixTheme.colorScheme.secondaryContainer)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(start = 14.dp, end = if (onEdit != null) 2.dp else 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (phone) MiuixIcons.Phone else MiuixIcons.ScreenMirroring, null, Modifier.size(24.dp), tint = if (active) primary else MiuixTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (tag != null) Text(tag, fontSize = 12.sp, color = Green, maxLines = 1,
            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(Green.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 2.dp))
        if (onEdit != null) IconButton(onClick = onEdit) { Icon(MiuixIcons.Edit, tr("编辑"), Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
    }
}

private fun latencyChips(ls: LinkState): List<String> = buildList {
    if (ls.txTransport.isNotEmpty()) add(ls.txTransport)
    if (ls.txCodec.isNotEmpty() && ls.txCodec != "?") add(ls.txCodec)
    val lat = if (ls.txPeerLatency >= 0) ls.txPeerLatency else if (ls.txRtt >= 0) ls.txRtt / 2 + 10 else -1
    add(if (lat >= 0) tr("延迟 {0} ms", lat) else tr("延迟 —"))
    if (ls.txPeerLoss >= 0f) add(tr("丢包 {0}%", "%.1f".format(ls.txPeerLoss * 100)))
    if (ls.txKbps > 0) add("${ls.txKbps} kbps")
}

private data class PendingConn(val host: String, val port: Int, val label: String, val id: String)

// ------------------------------------------------------------------ 发送

@Composable
private fun LinkSendCard(st: UiState) {
    val ctx = LocalContext.current
    val ls by LinkHub.state.collectAsStateWithLifecycle()
    val sp = remember { com.taocrypt.micmask.Prefs.main(ctx) }
    var host by remember { mutableStateOf(sp.getString("link_host", "") ?: "") }
    var code by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf<PendingConn?>(null) }
    var edit by remember { mutableStateOf<SavedDevice?>(null) }
    var pendingGo by remember { mutableStateOf<(() -> Unit)?>(null) }
    val mm = ls.proto == LinkHub.PROTO_MM
    val defPort = if (mm) MmLink.DEFAULT_PORT else MicYou.DEFAULT_PORT
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val primary = MiuixTheme.colorScheme.primary
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pendingGo?.invoke() else toast(ctx, tr("需要录音权限才能发送原始麦克风"))
        pendingGo = null
    }
    LaunchedEffect(Unit) { LinkHub.onForeground() }
    fun withMic(go: () -> Unit) {
        if (!com.taocrypt.micmask.inject.MicInjector.liveOn && !LinkHub.hasMic()) { pendingGo = go; perm.launch(android.Manifest.permission.RECORD_AUDIO) } else go()
    }
    val scanner = rememberQrScanner(onText = { t ->
        if (com.taocrypt.micmask.link.MmPairUri.parse(t) == null) toast(ctx, tr("不是 MicMask 配对二维码"))
        else withMic { LinkHub.connectScanned(t) }
    }, onDenied = { toast(ctx, tr("没有相机权限：请在下面手填对方地址和配对码")) })
    fun connectTo(h: String, p: Int, label: String, id: String, known: String) {
        if (mm && known.length != 6) ask = PendingConn(h, p, label, id)
        else withMic { LinkHub.startSend(h, p, label, known, id) }
    }

    Card(Modifier.fillMaxWidth().padding(LinkPad)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // 状态
            when {
                ls.txPhase == 2 -> StatusLine(2, tr("已连接 {0}", ls.txTarget), latencyChips(ls), if (ls.txSource.isNotEmpty()) tr("音源：{0}", ls.txSource) else "")
                ls.txPhase == 1 || ls.txPhase == 3 -> StatusLine(1, ls.txStatus.ifEmpty { tr("正在连接…") })
                ls.txError && ls.txStatus.isNotEmpty() -> StatusLine(3, ls.txStatus)
                else -> StatusLine(0, if (ls.txStatus.isNotEmpty() && ls.txStatus != tr("已停止")) ls.txStatus else tr("未连接"),
                    detail = if (mm) tr("把对方实际听到的声音发到电脑或另一台手机") else tr("发到 MicYou 电脑端或另一台手机（明文）"))
            }

            // 已保存设备
            val saved = ls.saved.filter { it.proto == ls.proto }
            if (saved.isNotEmpty()) {
                SectionLabel(tr("已保存设备"))
                saved.forEach { d ->
                    val online = ls.devices.any { x -> LinkHub.savedFor(x)?.key == d.key }
                    val sub = buildList {
                        add(if (d.host.isNotEmpty()) "${d.host}${if (d.port != defPort) ":${d.port}" else ""}" else tr("地址未知"))
                        if (!d.isTarget) add(tr("曾连到本机"))
                        if (mm && d.isTarget && d.code.length != 6) add(tr("需要配对码"))
                    }.joinToString(" · ")
                    DeviceRow(d.phone, d.label, sub, if (online) tr("在线") else null, enabled = !ls.txOn,
                        active = ls.txOn && ls.txTarget == d.label,
                        onClick = { connectTo(d.host, d.port, d.label, d.id, if (d.isTarget) d.code else "") }, onEdit = { edit = d })
                }
            }

            // 附近设备
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(tr("附近设备"), Modifier.weight(1f))
                Text(if (ls.discovering) tr("搜索中…") else tr("搜索"), fontSize = 15.sp, color = if (ls.discovering || ls.txOn) summary else primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !ls.discovering && !ls.txOn) { LinkHub.discoverFor(20_000) }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            val nearby = ls.devices.filter { LinkHub.savedFor(it) == null }
            if (nearby.isEmpty()) Text(when {
                ls.discovering -> if (mm) tr("正在查找开着「接收」的 MicMask 电脑版或手机…") else tr("正在查找 MicYou 电脑端或开着「接收」的手机…")
                ls.searchEmpty -> tr("没有找到。两台设备要在同一个 Wi-Fi / 热点里；跨网段或用 EasyTier、Tailscale 组网时搜不到，请在下面手填对方 IP")
                ls.txOn -> tr("发送中，停止后可搜索其他设备")
                else -> tr("点「搜索」查找同一 Wi-Fi 里的设备")
            }, fontSize = 14.sp, color = summary)
            nearby.forEach { d ->
                DeviceRow(d.phone, d.name, "${d.host}:${d.port}", null, enabled = !ls.txOn, active = false,
                    onClick = { connectTo(d.host, d.port, d.name, "", LinkHub.codeFor(d)) })
            }

            // 手动连接（自有协议可扫码）
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(tr("手动连接"), Modifier.weight(1f))
                if (mm) Text(tr("扫码"), fontSize = 15.sp, color = if (ls.txOn) summary else primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !ls.txOn) { scanner() }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            if (mm && !ls.txOn) Text(tr("点「扫码」扫接收端页面上的二维码，自动填好地址和配对码并记住设备"), fontSize = 13.sp, color = summary)
            TextField(value = host, onValueChange = { host = it.trim().take(64) }, label = tr("对方 IP（USB 填 127.0.0.1）"), singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (mm) Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(value = code, onValueChange = { v -> code = v.filter(Char::isDigit).take(6) }, label = tr("配对码（6 位数字）"), singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                IconButton(onClick = { val c = clipCode(ctx); if (c.isNotEmpty()) { code = c; toast(ctx, tr("已粘贴配对码")) } else toast(ctx, tr("剪贴板里没有 6 位数字")) }) {
                    Icon(MiuixIcons.Paste, tr("粘贴"), Modifier.size(22.dp), tint = primary)
                }
            }
            val parsed = MmNet.parseHostPort(host, defPort)
            val reason = when {
                ls.txOn -> null
                host.isBlank() -> tr("点上方设备直接连接，或填写对方 IP")
                parsed == null -> tr("地址格式不对，例如 192.168.1.8 或 10.144.144.2:47310")
                mm && code.length != 6 -> tr("还需要接收端显示的 6 位配对码")
                else -> null
            }
            TextButton(when (ls.txPhase) { 1 -> tr("取消连接"); 3 -> tr("停止重连"); 2 -> tr("停止发送"); else -> tr("连接并发送") },
                modifier = Modifier.fillMaxWidth(), enabled = ls.txOn || reason == null,
                colors = if (ls.txOn) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    if (ls.txOn) LinkHub.stopSend()
                    else if (parsed != null) {
                        sp.edit().putString("link_host", host.trim()).apply()
                        withMic { LinkHub.startSend(parsed.first, parsed.second, "", code) }
                    }
                })
            if (reason != null && !(host.isBlank() && (saved.isNotEmpty() || nearby.isNotEmpty()))) Text(reason, Modifier.fillMaxWidth(), fontSize = 13.sp, color = summary, textAlign = TextAlign.Center)
        }
    }

    ask?.let { pc -> CodeDialog(pc, onDismiss = { ask = null }) { c -> ask = null; withMic { LinkHub.startSend(pc.host, pc.port, pc.label, c, pc.id) } } }
    edit?.let { d -> SavedEditDialog(d) { edit = null } }
}

@Composable
private fun CodeDialog(pc: PendingConn, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val ctx = LocalContext.current
    var v by remember(pc) { mutableStateOf("") }
    var fromClip by remember(pc) { mutableStateOf(false) }
    LaunchedEffect(pc) { clipCode(ctx).takeIf { it.isNotEmpty() }?.let { v = it; fromClip = true } }
    WindowDialog(show = true, title = tr("输入配对码"), summary = tr("「{0}」的接收页上显示的 6 位数字，连上后会记住", pc.label.ifEmpty { pc.host }), onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(value = v, onValueChange = { x -> v = x.filter(Char::isDigit).take(6); fromClip = false }, label = tr("配对码"), singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                IconButton(onClick = { val c = clipCode(ctx); if (c.isNotEmpty()) { v = c; fromClip = true } else toast(ctx, tr("剪贴板里没有 6 位数字")) }) {
                    Icon(MiuixIcons.Paste, tr("粘贴"), Modifier.size(22.dp), tint = MiuixTheme.colorScheme.primary)
                }
            }
            if (fromClip) Text(tr("已从剪贴板填入"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(tr("取消"), onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(tr("连接"), onClick = { onOk(v) }, enabled = v.length == 6, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

@Composable
private fun SavedEditDialog(d: SavedDevice, onDismiss: () -> Unit) {
    var name by remember(d.key) { mutableStateOf(d.label) }
    WindowDialog(show = true, title = d.label, summary = listOfNotNull(d.host.takeIf { it.isNotEmpty() }?.let { "$it:${d.port}" }, d.name.takeIf { it.isNotEmpty() && it != d.label },
        d.id.takeIf { it.isNotEmpty() }?.let { "ID ${it.take(8)}" }).joinToString(" · "), onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = name, onValueChange = { name = it.take(24) }, label = tr("显示名称"), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(tr("删除"), onClick = { LinkHub.deleteSaved(d.key); onDismiss() }, modifier = Modifier.weight(1f))
                TextButton(tr("取消"), onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(tr("保存"), onClick = { LinkHub.renameSaved(d.key, name); onDismiss() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

// ------------------------------------------------------------------ 接收

private fun addrLabel(a: MmNet.LocalAddr): String {
    val k = when (a.kind) {
        "wifi" -> "Wi-Fi"; "hotspot" -> tr("热点"); "usb" -> "USB"; "ethernet" -> tr("有线"); "cellular" -> tr("移动数据")
        "vpn" -> if (a.vpn.isNotEmpty()) "VPN · ${a.vpn}" else "VPN"
        else -> tr("其他")
    }
    return "$k · ${a.iface}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkRecvCard(st: UiState) {
    val ctx = LocalContext.current
    val ls by LinkHub.state.collectAsStateWithLifecycle()
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val primary = MiuixTheme.colorScheme.primary
    val mm = ls.proto == LinkHub.PROTO_MM
    LaunchedEffect(Unit) { LinkHub.refreshIps() }
    Card(Modifier.fillMaxWidth().padding(LinkPad)) {
        SwitchPreference(checked = ls.rxOn, onCheckedChange = { if (it) LinkHub.startReceive() else LinkHub.stopReceive() }, title = tr("接收"),
            summary = if (st.speakerMode) tr("普通模式只能外放播放，不能注入其他应用") else tr("收到的声音替代麦克风送进目标应用"))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (ls.rxOn) {
                if (ls.rxPeer != null) StatusLine(2, tr("已连接 {0}", ls.rxPeer!!), buildList {
                    add(if (ls.rxLatency >= 0) tr("延迟 {0} ms", ls.rxLatency) else tr("延迟 —"))
                    add(tr("丢包 {0}", ls.rxLost))
                    add(tr("缓冲 {0} ms", ls.rxBufMs))
                    add(if (ls.rxInjecting) tr("注入中") else if (ls.rxPlayback) tr("外放中") else tr("未出声"))
                }, if (!ls.rxInjecting && !ls.rxPlayback) ls.rxStatus else "")
                else StatusLine(1, tr("等待发送端连接…"), detail = ls.rxStatus.takeIf { it.isNotEmpty() && !it.startsWith(tr("等待发送端连接…")) } ?: "")
            } else if (ls.rxStatus.isNotEmpty() && ls.rxStatus != tr("已关闭")) StatusLine(3, ls.rxStatus)
            if (ls.rxOn && st.liveOn && !ls.rxInjecting) Text(tr("变声正在运行：接收的声音改为外放（停止变声后自动改回注入）"), fontSize = 14.sp, color = primary)
            if (ls.rxOn && ls.txOn) Text(tr("同时在发送：接收注入时发送取的是真麦，不会把收到的声音发回去；外放播放时请戴耳机，否则对方会听到回声"), fontSize = 13.sp, color = summary)

            if (mm) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel(tr("配对码"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ls.code.chunked(3).joinToString(" "), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = primary, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .combinedClickable(onClick = { copyText(ctx, ls.code, tr("配对码已复制")) }, onLongClick = { copyText(ctx, ls.code, tr("配对码已复制")) })
                            .padding(horizontal = 4.dp, vertical = 2.dp))
                    IconButton(onClick = { copyText(ctx, ls.code, tr("配对码已复制")) }) { Icon(MiuixIcons.Copy, tr("复制"), Modifier.size(22.dp), tint = primary) }
                    Spacer(Modifier.weight(1f))
                    Text(tr("换一个"), fontSize = 15.sp, color = if (ls.rxPeer == null) primary else summary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            if (ls.rxPeer == null) LinkHub.regenCode() else toast(ctx, tr("有设备连着时不能换配对码，先让对方断开"))
                        }.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                Text(tr("在发送端输入这 6 位数字，点数字可复制"), fontSize = 13.sp, color = summary)
                if (ls.rxOn) {
                    val qr = remember(ls.addrs, ls.code, ls.name) { LinkHub.pairUri() }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QrImage(qr, Modifier.size(168.dp).clip(RoundedCornerShape(10.dp)))
                        Spacer(Modifier.width(12.dp))
                        Text(tr("或在发送端点「扫码」扫这个二维码：自动填好本机全部地址和配对码，连上后记住本机"), Modifier.weight(1f), fontSize = 13.sp, color = summary)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel(tr("本机地址"))
                if (ls.addrs.isEmpty()) Text(tr("没有可用网络：请连接 Wi-Fi、热点或 VPN"), fontSize = 14.sp, color = summary)
                ls.addrs.forEach { a ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { copyText(ctx, a.ip, tr("已复制 {0}", a.ip)) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(a.ip, Modifier.weight(1f), fontSize = 15.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(addrLabel(a), Modifier.padding(start = 8.dp), fontSize = 13.sp, color = if (a.kind == "vpn") primary else summary, maxLines = 1)
                    }
                }
                Text(if (mm) tr("端口 {0}（TCP/UDP）· 本机名称「{1}」· 点地址可复制", MmLink.DEFAULT_PORT, ls.name)
                     else tr("端口 {0}（TCP）/ {1}（UDP）· 本机名称「{2}」", MicYou.DEFAULT_PORT, MicYou.DEFAULT_PORT + 1, ls.name), fontSize = 13.sp, color = summary)
            }
        }
    }
}

// ------------------------------------------------------------------ 高级

@Composable
private fun LinkAdvanced() {
    val ls by LinkHub.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var codec by remember { mutableStateOf(LinkHub.codecPref()) }
    val busy = ls.txOn || ls.rxOn
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            SmallTitle(tr("高级"), Modifier.weight(1f))
            if (!open) Text(listOf(if (ls.auto) tr("自动连接开") else tr("自动连接关"), if (ls.proto == LinkHub.PROTO_MM) "MicMask" else "MicYou").joinToString(" · ") + "  ",
                fontSize = 13.sp, color = summary, maxLines = 1)
            Text(if (open) tr("收起 ▴") else tr("展开 ▾"), Modifier.padding(end = 28.dp), fontSize = 13.sp, color = summary)
        }
        if (open) Card(Modifier.fillMaxWidth().padding(LinkPad)) {
            SwitchPreference(checked = ls.auto, onCheckedChange = { LinkHub.setAuto(it) }, title = tr("自动连接"),
                summary = tr("打开 App 时恢复接收、连上配对过的设备；断线后自动重连"))
            ArrowPreference(title = tr("本机名称"), summary = ls.name, onClick = { rename = true })
            WindowDropdownPreference(items = listOf(tr("MicMask 自有（加密，推荐）"), tr("兼容 MicYou")), selectedIndex = ls.proto, title = tr("协议"),
                summary = if (busy) tr("收发进行中不能切换，先停止") else if (ls.proto == LinkHub.PROTO_MM) tr("与 MicMask 电脑版或另一台 MicMask 互传，加密") else tr("与 MicYou 电脑端互通，明文、只发 PCM"),
                enabled = !busy, onSelectedIndexChange = { LinkHub.setProto(it) })
            if (ls.proto == LinkHub.PROTO_MM) WindowDropdownPreference(items = listOf(tr("Opus（省流量、抗丢包）"), tr("PCM（USB / 热点直连）")), selectedIndex = codec, title = tr("编码偏好"),
                summary = if (busy) tr("收发进行中不能切换，先停止") else (if (codec == 0) tr("约 45 kbps，丢包时能补回") else tr("约 800 kbps，无压缩，丢包只能跳过")) + tr("。接收方的偏好优先：本机接收时对方按这里发，本机发送时跟随对方"),
                enabled = !busy, onSelectedIndexChange = { codec = it; LinkHub.setCodecPref(it) })
            ArrowPreference(title = tr("使用说明"), summary = tr("电脑端、USB、跨网络（EasyTier / Tailscale）"), onClick = { help = true })
        }
    }
    if (rename) DeviceNameDialog { rename = false }
    if (help) WindowDialog(show = true, title = tr("使用说明"), onDismissRequest = { help = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text((if (ls.proto == LinkHub.PROTO_MM) listOf(
                tr("· 加密：配对码换算出密钥（SPAKE2 + AES-GCM），不知道配对码的人连不上、也听不到。"),
                tr("· 电脑：装 MicMask 电脑版并打开「接收」，这里点它、输入电脑上的配对码。USB 连接时在电脑上执行 adb reverse tcp:47310 tcp:47310，再手填 127.0.0.1。"),
                tr("· 手机之间：一台打开「接收」（注入需要 Shizuku / ROOT / 系统权限），另一台点它发送；建议同一个 5 GHz Wi-Fi 或一台开热点。"),
                tr("· 跨网络：两台设备用 EasyTier、Tailscale、ZeroTier 组网后，手填对方「本机地址」里标着 VPN 的那个 IP（局域网搜索跨不了网段）。连接超时多半是电脑防火墙没放行 TCP/UDP 47310，Windows 常把虚拟网卡当成「公用网络」。"),
                tr("· 自动连接：连上过的设备会被记住（按设备 ID，换了 IP 也认得），打开 App 时自动连上，断线后自动重连。"),
                tr("· 扫码：接收端打开「接收」后页面上有二维码，发送端点「扫码」即可连上，不用手填地址和配对码。"),
                tr("· 同时收发：可以同时开「发送」和「接收」。接收注入时，发送取的是真麦（不会把收到的声音发回去）；普通模式外放时对方会听到回声，请戴耳机；变声运行时接收的声音改为外放。"),
                tr("· 跨网络很卡：UDP 不通时声音走 TCP，延迟和断流都会多一些，接收缓冲会自动加大；Windows 防火墙放行 UDP 47310 后通常能切到 UDP。"),
            ) else listOf(
                tr("· 不加密：只在自己的 Wi-Fi 或手机热点里使用，公共网络下别人可能截获声音。"),
                tr("· 电脑端：装 MicYou 电脑端并打开，选它或填电脑 IP 即可；USB 连接时在电脑上执行 adb reverse tcp:8554 tcp:8554，再填 127.0.0.1。"),
                tr("· 手机之间：一台打开「接收」（需要 Shizuku/ROOT/系统权限才能注入），另一台选它发送；建议连同一个 5 GHz Wi-Fi 或一台开热点。"),
                tr("· 暂不支持电脑 → 手机（MicYou 电脑端只收不发），后续版本提供。接收只支持 PCM，MicYou 安卓端（Opus）暂不能当发送端。"),
            )).joinToString("\n\n"), fontSize = 14.sp)
            TextButton(tr("知道了"), onClick = { help = false }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

/** 设备名称（设置页与传音页共用）：用于 mDNS 广播与握手显示 */
@Composable
internal fun LinkDeviceNamePref() {
    val ls by LinkHub.state.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    ArrowPreference(title = tr("设备名称"), summary = tr("{0}（传音时对方看到的名字）", ls.name), onClick = { show = true })
    if (show) DeviceNameDialog { show = false }
}

@Composable
private fun DeviceNameDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ls by LinkHub.state.collectAsStateWithLifecycle()
    var v by remember { mutableStateOf(ls.name) }
    val clean = LinkHub.sanitizeName(v)
    WindowDialog(show = true, title = tr("设备名称"), summary = tr("局域网搜索和连接时对方看到的名字，最多 24 个字"), onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = v, onValueChange = { v = it.take(32) }, label = tr("设备名称"), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(if (clean.isEmpty()) tr("留空则用默认名称「{0}」", LinkHub.defaultDeviceName()) else if (clean != v.trim()) tr("将显示为「{0}」（已去掉不支持的符号）", clean) else tr("支持中英文、数字、空格和 _ . -"),
                fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(tr("恢复默认"), onClick = { LinkHub.setDeviceName(""); toast(ctx, tr("已恢复默认名称")); onDismiss() }, modifier = Modifier.weight(1f))
                TextButton(tr("取消"), onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(tr("保存"), onClick = {
                    val n = LinkHub.setDeviceName(clean)
                    toast(ctx, if (ls.rxOn && ls.rxPeer != null) tr("已保存，下次打开接收时生效") else tr("设备名称已改为「{0}」", n)); onDismiss()
                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

