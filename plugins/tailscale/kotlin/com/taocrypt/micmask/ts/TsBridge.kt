// Part of MicMask (GPL-3.0, see LICENSE at the repository root). Links tailscale.com (BSD-3-Clause).
package com.taocrypt.micmask.ts

import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** A tailnet peer as reported by [TsBridge.status] with peers. */
data class TsPeer(val name: String, val dns: String, val os: String, val ips: List<String>, val online: Boolean)

/**
 * @property running node is logged in and connected (BackendState == "Running")
 * @property state raw BackendState: NotStarted / NoState / NeedsLogin / NeedsMachineAuth / Starting / Running / Stopped
 * @property ips this node's tailnet IPs (IPv4 100.x first, then IPv6 fd7a:...)
 * @property loginUrl set while the node waits for an interactive login; open it in the user's browser
 */
data class TsStatus(
    val running: Boolean,
    val state: String,
    val ips: List<String>,
    val loginUrl: String?,
    val self: String?,
    val tailnet: String?,
    val health: List<String>,
    val peers: List<TsPeer>,
    val exposed: List<String>,
    val dials: List<String>,
    val error: String?,
) {
    companion object {
        fun parse(js: String): TsStatus {
            val o = JSONObject(js)
            fun strs(k: String): List<String> = o.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val peers = o.optJSONArray("peers") ?: JSONArray()
            return TsStatus(
                running = o.optBoolean("running"),
                state = o.optString("state"),
                ips = strs("ips"),
                loginUrl = o.optString("loginUrl").ifEmpty { null },
                self = o.optString("self").ifEmpty { null },
                tailnet = o.optString("tailnet").ifEmpty { null },
                health = strs("health"),
                peers = List(peers.length()) { i ->
                    val p = peers.getJSONObject(i)
                    val ips = p.optJSONArray("ips") ?: JSONArray()
                    TsPeer(p.optString("name"), p.optString("dns"), p.optString("os"), List(ips.length()) { ips.getString(it) }, p.optBoolean("online"))
                },
                exposed = strs("exposed"),
                dials = strs("dials"),
                error = o.optString("error").ifEmpty { null },
            )
        }
    }
}

class TsException(msg: String) : Exception(msg)

/**
 * What MicMask needs from Tailscale. All calls block; run them off the main thread.
 *
 * The core five follow the agreed shape: start / status / expose / dial / stop.
 */
interface TsBridge {
    /**
     * Starts (or reuses) the userspace node and waits up to [waitMs] for it to
     * become Running or to need a login.
     * @param stateDir app-private dir that keeps the node key; reuse it so the device stays logged in
     * @param authKey optional pre-auth key (tskey-auth-...); null = interactive login
     * @param controlUrl null = Tailscale; e.g. "https://headscale.example.com" for Headscale
     * @return the login URL to open in a browser, or null if already Running (or still Starting after [waitMs]; check [status])
     */
    fun start(
        stateDir: String, hostname: String, authKey: String? = null,
        controlUrl: String? = null, ephemeral: Boolean = false, waitMs: Int = 15_000,
    ): String?

    fun status(withPeers: Boolean = false): TsStatus

    /**
     * Makes this node's tailnet [port] reach 127.0.0.1:[localPort] (default: same port),
     * so existing java.net sockets bound on loopback/any become reachable over the tailnet.
     * UDP: each remote peer gets its own loopback socket; the app sees 127.0.0.1:<ephemeral> as the source.
     */
    fun expose(port: Int, udp: Boolean, localPort: Int = port)
    fun unexpose(port: Int, udp: Boolean)

    /**
     * Opens 127.0.0.1:<returned port> forwarding to [host]:[port] on the tailnet
     * (host = tailnet IP or MagicDNS name). TCP: every accepted local connection
     * dials the target. UDP: one flow; replies go to the latest local sender.
     */
    fun dial(host: String, port: Int, udp: Boolean): Int
    fun undial(localPort: Int, udp: Boolean)

    /** Stops the node (keeps login state). Closes all exposes and dials. */
    fun stop()

    /** Logs this node out of the tailnet and stops; [wipe] also deletes tsnet's state files. */
    fun logout(wipe: Boolean = true)

    /**
     * Android only (no-op elsewhere): pass the current network interfaces,
     * since apps cannot enumerate them natively from Go. Call after start
     * and on every ConnectivityManager network change. See [TsInterfaces].
     */
    fun setInterfaces(json: String = TsInterfaces.currentJson())

    /** Plugin library log (tsnet + forwarding) to a file; null turns it off. */
    fun setLogFile(path: String?)

    val pluginVersion: String
}

/** [TsBridge] over the JNI functions of the loaded plugin library. */
internal class NativeTsBridge : TsBridge {
    private fun b(s: String?) = s?.toByteArray(Charsets.UTF_8)
    private fun s(b: ByteArray) = String(b, Charsets.UTF_8)
    private fun check(rc: Int, what: String) {
        if (rc != 0) throw TsException("$what: " + s(TsNative.lastError()))
    }

    override val pluginVersion: String get() = s(TsNative.version())

    override fun start(stateDir: String, hostname: String, authKey: String?, controlUrl: String?, ephemeral: Boolean, waitMs: Int): String? {
        check(TsNative.start(b(stateDir)!!, b(hostname), b(authKey), b(controlUrl), ephemeral, false), "start")
        val st = TsStatus.parse(s(TsNative.waitJson(waitMs)))
        if (st.running) return null
        return st.loginUrl
    }

    override fun status(withPeers: Boolean) = TsStatus.parse(s(TsNative.statusJson(withPeers)))
    override fun expose(port: Int, udp: Boolean, localPort: Int) = check(TsNative.expose(port, localPort, udp), "expose")
    override fun unexpose(port: Int, udp: Boolean) = check(TsNative.unexpose(port, udp), "unexpose")
    override fun dial(host: String, port: Int, udp: Boolean): Int {
        val lp = TsNative.dial(b(host)!!, port, udp)
        if (lp <= 0) throw TsException("dial: " + s(TsNative.lastError()))
        return lp
    }
    override fun undial(localPort: Int, udp: Boolean) = check(TsNative.undial(localPort, udp), "undial")
    override fun stop() = check(TsNative.stop(), "stop")
    override fun logout(wipe: Boolean) = check(TsNative.logout(wipe), "logout")
    override fun setInterfaces(json: String) = check(TsNative.setInterfaces(b(json)!!), "setInterfaces")
    override fun setLogFile(path: String?) = check(TsNative.setLogFile(b(path)), "setLogFile")
}

/** Builds the interface list the Android build of the plugin needs (pure java.net, works on Android). */
object TsInterfaces {
    fun currentJson(): String {
        val arr = JSONArray()
        val list = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (ni in list) {
            val up = runCatching { ni.isUp }.getOrDefault(false)
            val lo = runCatching { ni.isLoopback }.getOrDefault(false)
            // skip Tailscale's own / other VPN tun of the official app is fine to keep; skip down ifaces
            if (!up) continue
            val addrs = JSONArray()
            for (ia in ni.interfaceAddresses) {
                val a = ia.address ?: continue
                val host = a.hostAddress?.substringBefore('%') ?: continue
                addrs.put("$host/${ia.networkPrefixLength.toInt().coerceIn(0, if (a is Inet4Address) 32 else 128)}")
            }
            arr.put(JSONObject().put("name", ni.name).put("index", ni.index)
                .put("mtu", runCatching { ni.mtu }.getOrDefault(1500))
                .put("up", up).put("loopback", lo).put("addrs", addrs))
        }
        return arr.toString()
    }
}
