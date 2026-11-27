package com.taocrypt.micmask.ui2

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taocrypt.micmask.MainViewModel
import com.taocrypt.micmask.UiState
import com.taocrypt.micmask.link.LinkDevice
import com.taocrypt.micmask.link.LinkHub
import com.taocrypt.micmask.link.MicYou
import com.taocrypt.micmask.tr
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import com.taocrypt.micmask.link.MmLink
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 「传音」页（2.1.1-alpha.11，MVP）：发送（手机 → MicYou 电脑端 / 另一台 MicMask）与接收（本机当接收端，注入或外放）。
 * 全部默认关；局域网搜索也只在点「搜索」后跑 20 秒。
 */

private val LinkPad = PaddingValues(horizontal = 12.dp)

internal fun LazyListScope.linkPage(vm: MainViewModel, st: UiState) {
    item(key = "link_proto") { LinkProtoCard() }
    item(key = "link_send_t") { SmallTitle(tr("发送")) }
    item(key = "link_send") { LinkSendCard(st) }
    item(key = "link_recv_t") { SmallTitle(tr("接收")) }
    item(key = "link_recv") { LinkRecvCard(st) }
    item(key = "link_note") { LinkNotes() }
}

/** 协议选择：MicMask 自有（默认，加密 + 配对码 + Opus/PCM）/ 兼容 MicYou（原样保留）。收发进行中不能切换 */
@Composable
private fun LinkProtoCard() {
    val ls by LinkHub.state.collectAsStateWithLifecycle()
    val busy = ls.txOn || ls.rxOn
    var codec by remember { mutableStateOf(LinkHub.codecPref()) }
    Card(Modifier.fillMaxWidth().padding(LinkPad).padding(top = 8.dp)) {
        WindowDropdownPreference(items = listOf(tr("MicMask 自有（加密，推荐）"), tr("兼容 MicYou")), selectedIndex = ls.proto, title = tr("协议"),
            summary = if (busy) tr("收发进行中不能切换，先停止") else if (ls.proto == LinkHub.PROTO_MM) tr("与 MicMask 电脑版或另一台 MicMask 互传：加密，首次连接输入对方显示的配对码") else tr("与 MicYou 电脑端互通：明文传输，只发 PCM"),
            enabled = !busy, onSelectedIndexChange = { LinkHub.setProto(it) })
        if (ls.proto == LinkHub.PROTO_MM) WindowDropdownPreference(items = listOf(tr("Opus（省流量、抗丢包）"), tr("PCM（USB / 热点直连）")), selectedIndex = codec, title = tr("发送编码"),
            summary = if (codec == 0) tr("约 45 kbps，Wi-Fi 丢包时能补回") else tr("约 800 kbps，无压缩，丢包只能跳过"),
            enabled = !ls.txOn, onSelectedIndexChange = { codec = it; LinkHub.setCodecPref(it) })
    }
}

@Composable
private fun LinkSendCard(st: UiState) {
    val ctx = LocalContext.current
    val ls by LinkHub.state.collectAsStateWithLifecycle()
    val sp = remember { com.taocrypt.micmask.Prefs.main(ctx) }
    var host by remember { mutableStateOf(sp.getString("link_host", "") ?: "") }
    var picked by remember { mutableStateOf<LinkDevice?>(null) }
    var code by remember { mutableStateOf("") }
    val mm = ls.proto == LinkHub.PROTO_MM
    val defPort = if (mm) MmLink.DEFAULT_PORT else MicYou.DEFAULT_PORT
    LaunchedEffect(picked, ls.proto) { picked?.let { d -> LinkHub.savedCode(d.name).takeIf { it.isNotEmpty() }?.let { code = it } } }
    LaunchedEffect(ls.proto) { picked = null }
    var searchGen by remember { mutableStateOf(0) }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val primary = MiuixTheme.colorScheme.primary
    fun go() {
        val d = picked
        if (d != null) LinkHub.startSend(d.host, d.port, d.name, code)
        else {
            val h = host.trim()
            val (hh, pp) = h.substringBeforeLast(':', h).let { a -> if (a != h) a to (h.substringAfterLast(':').toIntOrNull() ?: defPort) else h to defPort }
            sp.edit().putString("link_host", h).apply()
            LinkHub.startSend(hh, pp, "", code)
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) go() else vm0Toast(ctx, tr("需要录音权限才能发送原始麦克风")) }
    LaunchedEffect(searchGen) { if (searchGen > 0) { LinkHub.startDiscovery(); kotlinx.coroutines.delay(20_000); LinkHub.stopDiscovery() } }

    Card(Modifier.fillMaxWidth().padding(LinkPad)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (mm) tr("把对方实际听到的声音发到 MicMask 电脑版或另一台手机：开着变声时发变声结果，没开时发原始麦克风。") else tr("把对方实际听到的声音发到电脑或另一台手机：开着变声时发变声结果，没开时发原始麦克风。兼容 MicYou 电脑端。"), fontSize = 13.sp, color = summary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("局域网设备"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(if (ls.discovering) tr("搜索中…") else tr("搜索"), fontSize = 14.sp, color = primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !ls.discovering && !ls.txOn) { searchGen++ }.padding(6.dp))
            }
            if (ls.devices.isEmpty()) Text(if (ls.discovering) (if (mm) tr("正在查找开着 MicMask 电脑版或 MicMask「接收」的设备…") else tr("正在查找开着 MicYou 电脑端或 MicMask「接收」的设备…")) else tr("点「搜索」查找设备，或在下面手动输入 IP"), fontSize = 12.sp, color = summary)
            ls.devices.forEach { d ->
                val sel = picked?.name == d.name
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (sel) primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.secondaryContainer)
                    .clickable(enabled = !ls.txOn) { picked = if (sel) null else d }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontSize = 14.sp, maxLines = 1)
                        Text("${d.host}:${d.port}", fontSize = 12.sp, color = summary)
                    }
                    Text(if (d.phone) tr("手机") else tr("电脑"), fontSize = 12.sp, color = summary)
                }
            }
            TextField(value = host, onValueChange = { host = it.trim().take(64); picked = null; if (mm) LinkHub.savedCode(host.trim()).takeIf { c -> c.isNotEmpty() }?.let { c -> code = c } }, label = tr("手动输入 IP（USB 连接填 127.0.0.1）"), singleLine = true,
                modifier = Modifier.fillMaxWidth())
            if (mm) TextField(value = code, onValueChange = { v -> code = v.filter(Char::isDigit).take(6) }, label = tr("配对码（接收端显示的 6 位数字）"), singleLine = true,
                modifier = Modifier.fillMaxWidth())
            TextButton(if (ls.txOn) tr("停止发送") else tr("开始发送"), modifier = Modifier.fillMaxWidth(),
                colors = if (ls.txOn) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    if (ls.txOn) LinkHub.stopSend()
                    else if (!com.taocrypt.micmask.inject.MicInjector.liveOn && !LinkHub.hasMic()) perm.launch(android.Manifest.permission.RECORD_AUDIO)
                    else go()
                })
            if (ls.txStatus.isNotEmpty() || ls.txOn) {
                val lines = buildList {
                    add(if (ls.txOn && ls.txConnected) tr("已连接 {0}", ls.txTarget) else ls.txStatus)
                    if (ls.txOn && ls.txConnected) {
                        if (ls.txSource.isNotEmpty()) add(tr("音源：{0}", ls.txSource))
                        add(tr("已发 {0} 包 · 码率 {1} kbps · RTT {2}", ls.txPackets, ls.txKbps, if (ls.txRtt >= 0) "${ls.txRtt} ms" else "—") + if (mm && ls.txCodec.isNotEmpty()) " · ${ls.txCodec}" else "")
                        if (ls.txPeerLoss >= 0f) add(tr("对端统计：丢包 {0}% · 延迟约 {1} ms", "%.1f".format(ls.txPeerLoss * 100), ls.txPeerLatency))
                    }
                }
                Text(lines.joinToString("\n"), fontSize = 12.sp, color = summary)
            }
        }
    }
}

private fun vm0Toast(ctx: android.content.Context, msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

@Composable
private fun LinkRecvCard(st: UiState) {
    val ls by LinkHub.state.collectAsStateWithLifecycle()
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    LaunchedEffect(Unit) { LinkHub.refreshIps() }
    Card(Modifier.fillMaxWidth().padding(LinkPad)) {
        SwitchPreference(checked = ls.rxOn, onCheckedChange = { if (it) LinkHub.startReceive() else LinkHub.stopReceive() }, title = tr("接收"),
            summary = if (ls.rxOn) ls.rxStatus else if (st.speakerMode) tr("普通模式（无 Shizuku）只能外放播放，不能注入其他应用") else tr("本机当接收端：收到的声音替代你的麦克风送进目标应用"))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("本机名称：{0}", ls.name), fontSize = 12.sp, color = summary)
            if (ls.proto == LinkHub.PROTO_MM) {
                Text(tr("本机 IP：{0} · 端口 {1}（TCP/UDP）", ls.ips.joinToString(", ").ifEmpty { tr("未连接 Wi-Fi") }, MmLink.DEFAULT_PORT), fontSize = 12.sp, color = summary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("配对码"), fontSize = 13.sp, color = summary)
                    Text(ls.code.chunked(3).joinToString(" "), Modifier.padding(horizontal = 8.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text(tr("换一个"), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = ls.rxPeer == null) { LinkHub.regenCode() }.padding(6.dp))
                }
                Text(tr("发送端（电脑版或另一台手机）连接时输入这 6 位数字；换码后已连接的设备下次要重新输入"), fontSize = 12.sp, color = summary)
            } else
            Text(tr("本机 IP：{0} · 端口 {1}（TCP）/ {2}（UDP）", ls.ips.joinToString(", ").ifEmpty { tr("未连接 Wi-Fi") }, MicYou.DEFAULT_PORT, MicYou.DEFAULT_PORT + 1), fontSize = 12.sp, color = summary)
            if (ls.rxOn && ls.rxPeer != null) {
                Text(tr("来自 {0} · 包 {1} · 丢包 {2} · 缓冲 {3} ms · 延迟约 {4} ms", ls.rxPeer, ls.rxPackets, ls.rxLost, ls.rxBufMs, if (ls.rxLatency >= 0) ls.rxLatency else "—") +
                    " · " + (if (ls.rxInjecting) tr("注入中") else if (ls.rxPlayback) tr("外放中") else tr("未出声")), fontSize = 12.sp, color = summary)
            }
            if (ls.rxOn && st.liveOn) Text(tr("变声正在运行：接收的声音要先停止变声才能注入"), fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun LinkNotes() {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Spacer(Modifier.height(8.dp))
    val mm = LinkHub.state.collectAsStateWithLifecycle().value.proto == LinkHub.PROTO_MM
    if (mm) Text(listOf(
        tr("· MicMask 自有协议：配对码换算出加密密钥（SPAKE2 + AES-GCM），声音和控制消息都加密；不知道配对码的人连不上、也听不到。"),
        tr("· 电脑端：装 MicMask 电脑版（Windows），在电脑上打开「接收」后用这里搜索并输入电脑显示的配对码；电脑也能把声音发到开着「接收」的手机。USB 连接时在电脑上执行 adb reverse tcp:47310 tcp:47310，再填 127.0.0.1。"),
        tr("· 手机之间：一台打开「接收」（需要 Shizuku/ROOT/系统权限才能注入），另一台选它、输入配对码发送；建议连同一个 5 GHz Wi-Fi 或一台开热点。"),
        tr("· 要连 MicYou 电脑端，在上方把协议改成「兼容 MicYou」。"),
    ).joinToString("\n"), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), fontSize = 12.sp, color = summary)
    else Text(listOf(
        tr("· 不加密：只在自己的 Wi-Fi 或手机热点里使用，公共网络下别人可能截获声音。"),
        tr("· 电脑端：装 MicYou 电脑端并打开，选它或填电脑 IP 即可；USB 连接时在电脑上执行 adb reverse tcp:8554 tcp:8554，再填 127.0.0.1。"),
        tr("· 手机之间：一台打开「接收」（需要 Shizuku/ROOT/系统权限才能注入），另一台选它发送；建议连同一个 5 GHz Wi-Fi 或一台开热点。"),
        tr("· 暂不支持电脑 → 手机（MicYou 电脑端只收不发），后续版本提供。接收只支持 PCM，MicYou 安卓端（Opus）暂不能当发送端。"),
    ).joinToString("\n"), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), fontSize = 12.sp, color = summary)
}
