package com.taocrypt.micmask.desktop

import com.taocrypt.micmask.link.MmLink
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** 局域网里找到的 MicMask 设备（手机开着「接收」，或另一台电脑）；[idHex] = mDNS TXT 里的设备 ID 前缀（协议设备 ID 的前 4 字节十六进制） */
data class PeerDevice(val name: String, val host: String, val port: Int, val platform: String, val idHex: String = "")

/** 本机一个网卡地址：[vpn] = EasyTier / Tailscale / ZeroTier / WireGuard 等虚拟网卡（按网卡名识别） */
data class LocalAddr(val ip: InetAddress, val ifName: String, val vpn: Boolean)

/**
 * mDNS：广播本机 _micmask._tcp（接收开着时），并发现其它 MicMask 设备。每个支持组播的 IPv4 网卡各开一个 JmDNS。
 * 地址列表（界面显示）包含所有启用的网卡，含虚拟网卡（异地组网时手动输入这些地址连接；mDNS 一般过不了这些网络）。
 */
object Discovery {
    private const val TYPE = MmLink.MDNS_TYPE + ".local."
    private val inst = ArrayList<JmDNS>()
    private val found = LinkedHashMap<String, PeerDevice>()
    @Volatile var onChange: (List<PeerDevice>) -> Unit = {}
    private val VPN = listOf("easytier", "tailscale", "zerotier", "wireguard", "wintun", "openvpn", "tap-windows", "tap-", "tun", "radmin", "hamachi", "sakura", "n2n", "vpn", "wg")

    fun addrs(): List<LocalAddr> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
            val label = (ni.displayName ?: ni.name).trim()
            val vpn = VPN.any { label.lowercase().contains(it) || ni.name.lowercase().startsWith(it) } || ni.isPointToPoint
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }.map { LocalAddr(it, label, vpn) }
        }.sortedBy { if (it.vpn) 1 else 0 }
    }.getOrDefault(emptyList())

    fun localIps(): List<InetAddress> = addrs().map { it.ip }

    /** 开 JmDNS 的网卡：支持组播、不是点对点隧道 */
    private fun mdnsIps(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && it.supportsMulticast() && !it.isPointToPoint }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { !it.isLinkLocalAddress }
    }.getOrDefault(emptyList())

    @Synchronized fun start(log: (String) -> Unit) {
        if (inst.isNotEmpty()) return
        for (ip in mdnsIps()) runCatching {
            val j = JmDNS.create(ip, "MicMask-PC-" + ip.hostAddress.replace('.', '-'))
            j.addServiceListener(TYPE, object : ServiceListener {
                override fun serviceAdded(e: ServiceEvent) { j.requestServiceInfo(e.type, e.name, 1500) }
                override fun serviceRemoved(e: ServiceEvent) { synchronized(found) { found.remove(e.name) }; publish() }
                override fun serviceResolved(e: ServiceEvent) {
                    val i = e.info
                    val host = i.inet4Addresses.firstOrNull()?.hostAddress ?: return
                    if (localIps().any { it.hostAddress == host }) return
                    synchronized(found) { found[e.name] = PeerDevice(e.name, host, i.port, i.getPropertyString("plat") ?: "?", (i.getPropertyString("id") ?: "").lowercase()) }
                    publish()
                }
            })
            inst.add(j)
        }.onFailure { log("mDNS 在 ${ip.hostAddress} 上启动失败：${it.message}") }
        log("mDNS 发现已开启（${inst.size} 个网卡）")
    }

    fun current(): List<PeerDevice> = synchronized(found) { found.values.sortedBy { it.name } }
    private fun publish() { onChange(current()) }

    /** 广播本机接收端 */
    @Synchronized fun register(name: String, port: Int, idHex: String, log: (String) -> Unit) {
        if (inst.isEmpty()) start(log)
        val props = mapOf("app" to "micmask", "v" to MmLink.VERSION.toString(), "plat" to "windows", "id" to idHex)
        for (j in inst) runCatching { j.registerService(ServiceInfo.create(TYPE, name, port, 0, 0, props)) }.onFailure { log("mDNS 广播失败：${it.message}") }
        log("mDNS 已广播「$name」端口 $port")
    }

    /** 取消广播：JmDNS 发告别包会阻塞几百毫秒，调用方放后台线程 */
    @Synchronized fun unregister() { for (j in inst) runCatching { j.unregisterAllServices() } }

    /**
     * 关闭所有 JmDNS：每个实例的 close() 会发告别包并等待内部定时任务（实测本机约 0.3–1 秒/个），并行关；最多等 [waitMs]。
     * 退出时不必等完：进程结束后对方 mDNS 记录会按 TTL 过期。
     */
    fun stop(waitMs: Long = 1500) {
        val list = synchronized(this) { ArrayList(inst).also { inst.clear() } }
        synchronized(found) { found.clear() }
        val ts = list.map { j -> Thread({ runCatching { j.close() } }, "mdns-close").apply { isDaemon = true; start() } }
        val end = System.currentTimeMillis() + waitMs
        for (t in ts) { val w = end - System.currentTimeMillis(); if (w > 0) t.join(w) }
    }
}
