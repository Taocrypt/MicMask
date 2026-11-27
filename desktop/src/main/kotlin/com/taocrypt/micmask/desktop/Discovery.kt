package com.taocrypt.micmask.desktop

import com.taocrypt.micmask.link.MmLink
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** 局域网里找到的 MicMask 设备（手机开着「接收」，或另一台电脑） */
data class PeerDevice(val name: String, val host: String, val port: Int, val platform: String)

/**
 * mDNS：广播本机 _micmask._tcp（接收开着时），并发现其它 MicMask 设备。每个局域网 IPv4 地址各开一个 JmDNS（多网卡、热点、USB 共享网络都能覆盖）。
 */
object Discovery {
    private const val TYPE = MmLink.MDNS_TYPE + ".local."
    private val inst = ArrayList<JmDNS>()
    private val found = LinkedHashMap<String, PeerDevice>()
    @Volatile var onChange: (List<PeerDevice>) -> Unit = {}

    fun localIps(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
    }.getOrDefault(emptyList())

    @Synchronized fun start(log: (String) -> Unit) {
        if (inst.isNotEmpty()) return
        for (ip in localIps()) runCatching {
            val j = JmDNS.create(ip, "MicMask-PC-" + ip.hostAddress.replace('.', '-'))
            j.addServiceListener(TYPE, object : ServiceListener {
                override fun serviceAdded(e: ServiceEvent) { j.requestServiceInfo(e.type, e.name, 1500) }
                override fun serviceRemoved(e: ServiceEvent) { synchronized(found) { found.remove(e.name) }; publish() }
                override fun serviceResolved(e: ServiceEvent) {
                    val i = e.info
                    val host = i.inet4Addresses.firstOrNull()?.hostAddress ?: return
                    if (localIps().any { it.hostAddress == host }) return
                    synchronized(found) { found[e.name] = PeerDevice(e.name, host, i.port, i.getPropertyString("plat") ?: "?") }
                    publish()
                }
            })
            inst.add(j)
        }.onFailure { log("mDNS 在 ${ip.hostAddress} 上启动失败：${it.message}") }
        log("mDNS 发现已开启（${inst.size} 个网卡）")
    }

    private fun publish() { onChange(synchronized(found) { found.values.sortedBy { it.name } }) }

    /** 广播本机接收端 */
    @Synchronized fun register(name: String, port: Int, idHex: String, log: (String) -> Unit) {
        if (inst.isEmpty()) start(log)
        val props = mapOf("app" to "micmask", "v" to MmLink.VERSION.toString(), "plat" to "windows", "id" to idHex)
        for (j in inst) runCatching { j.registerService(ServiceInfo.create(TYPE, name, port, 0, 0, props)) }.onFailure { log("mDNS 广播失败：${it.message}") }
        log("mDNS 已广播「$name」端口 $port")
    }

    @Synchronized fun unregister() { for (j in inst) runCatching { j.unregisterAllServices() } }

    @Synchronized fun stop() { for (j in inst) runCatching { j.close() }; inst.clear(); synchronized(found) { found.clear() } }
}
