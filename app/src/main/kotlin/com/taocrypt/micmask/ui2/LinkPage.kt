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
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 「传音」页（2.1.1-alpha.11，MVP）：发送（手机 → MicYou 电脑端 / 另一台 MicMask）与接收（本机当接收端，注入或外放）。
 * 全部默认关；局域网搜索也只在点「搜索」后跑 20 秒。
 */

private val LinkPad = PaddingValues(horizontal = 12.dp)

internal fun LazyListScope.linkPage(vm: MainViewModel, st: UiState) {
    item(key = "link_send_t") { SmallTitle(tr("发送")) }
    item(key = "link_send") { LinkSendCard(st) }
    item(key = "link_recv_t") { SmallTitle(tr("接收")) }
    item(key = "link_recv") { LinkRecvCard(st) }
    item(key = "link_note") { LinkNotes() }
}

@Composable
private fun LinkSendCard(st: UiState) {
    val ctx = LocalContext.current
    val ls by LinkHub.state.collectAsStateWithLifecycle()
    val sp = remember { com.taocrypt.micmask.Prefs.main(ctx) }
    var host by remember { mutableStateOf(sp.getString("link_host", "") ?: "") }
    var picked by remember { mutableStateOf<LinkDevice?>(null) }
    var searchGen by remember { mutableStateOf(0) }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val primary = MiuixTheme.colorScheme.primary
    fun go() {
        val d = picked
        if (d != null) LinkHub.startSend(d.host, d.port, d.name)
        else {
            val h = host.trim()
            val (hh, pp) = h.substringBeforeLast(':', h).let { a -> if (a != h) a to (h.substringAfterLast(':').toIntOrNull() ?: MicYou.DEFAULT_PORT) else h to MicYou.DEFAULT_PORT }
            sp.edit().putString("link_host", h).apply()
            LinkHub.startSend(hh, pp, "")
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) go() else vm0Toast(ctx, tr("需要录音权限才能发送原始麦克风")) }
    LaunchedEffect(searchGen) { if (searchGen > 0) { LinkHub.startDiscovery(); kotlinx.coroutines.delay(20_000); LinkHub.stopDiscovery() } }

    Card(Modifier.fillMaxWidth().padding(LinkPad)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("把对方实际听到的声音发到电脑或另一台手机：开着变声时发变声结果，没开时发原始麦克风。兼容 MicYou 电脑端。"), fontSize = 13.sp, color = summary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("局域网设备"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(if (ls.discovering) tr("搜索中…") else tr("搜索"), fontSize = 14.sp, color = primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !ls.discovering && !ls.txOn) { searchGen++ }.padding(6.dp))
            }
            if (ls.devices.isEmpty()) Text(if (ls.discovering) tr("正在查找开着 MicYou 电脑端或 MicMask「接收」的设备…") else tr("点「搜索」查找设备，或在下面手动输入 IP"), fontSize = 12.sp, color = summary)
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
            TextField(value = host, onValueChange = { host = it.trim().take(64); picked = null }, label = tr("手动输入 IP（USB 连接填 127.0.0.1）"), singleLine = true,
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
                        add(tr("已发 {0} 包 · 码率 {1} kbps · RTT {2}", ls.txPackets, ls.txKbps, if (ls.txRtt >= 0) "${ls.txRtt} ms" else "—"))
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
    Text(listOf(
        tr("· 不加密：只在自己的 Wi-Fi 或手机热点里使用，公共网络下别人可能截获声音。"),
        tr("· 电脑端：装 MicYou 电脑端并打开，选它或填电脑 IP 即可；USB 连接时在电脑上执行 adb reverse tcp:8554 tcp:8554，再填 127.0.0.1。"),
        tr("· 手机之间：一台打开「接收」（需要 Shizuku/ROOT/系统权限才能注入），另一台选它发送；建议连同一个 5 GHz Wi-Fi 或一台开热点。"),
        tr("· 暂不支持电脑 → 手机（MicYou 电脑端只收不发），后续版本提供。接收只支持 PCM，MicYou 安卓端（Opus）暂不能当发送端。"),
    ).joinToString("\n"), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), fontSize = 12.sp, color = summary)
}
