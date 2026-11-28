// Part of MicMask (GPL-3.0, see LICENSE at the repository root). Links tailscale.com (BSD-3-Clause).
// JVM smoke test of the loader + JNI bridge against a local test tailnet
// (native: MMTS_PEER_OUT=<file> go test -run TestPeerServer). Not part of the app.
//
// usage: TsSmoke <pluginDir> <manifestUrl> <peerInfoFile>
//        TsSmoke <pluginDir> <manifestUrl> login     (real Tailscale control: expects a login URL)
import com.taocrypt.micmask.ts.TsInterfaces
import com.taocrypt.micmask.ts.TsPlugin
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

fun echoTcpOnce(c: Socket, msg: String): String {
    c.soTimeout = 5000
    c.getOutputStream().write(msg.toByteArray()); c.getOutputStream().flush()
    val buf = ByteArray(4096); val n = c.getInputStream().read(buf)
    return String(buf, 0, n)
}

fun echoUdp(port: Int, msg: String): String {
    DatagramSocket().use { s ->
        s.soTimeout = 2000
        repeat(5) {
            s.send(DatagramPacket(msg.toByteArray(), msg.length, InetAddress.getLoopbackAddress(), port))
            try {
                val p = DatagramPacket(ByteArray(4096), 4096); s.receive(p)
                return String(p.data, 0, p.length)
            } catch (_: java.net.SocketTimeoutException) {}
        }
    }
    error("no udp echo")
}

fun peerProbe(info: File, req: String): String {
    val res = File(info.path + ".dialres"); res.delete()
    File(info.path + ".dial").writeText(req)
    val end = System.currentTimeMillis() + 20_000
    while (System.currentTimeMillis() < end) { if (res.isFile) return res.readText(); Thread.sleep(100) }
    error("peer did not answer")
}

fun loginSmoke(dir: File, manifest: String) {
    val t0 = System.nanoTime()
    val ts = TsPlugin.ensure(dir, listOf(manifest))
    println("PASS load+verify (${(System.nanoTime() - t0) / 1_000_000} ms) plugin=${ts.pluginVersion} platform=${TsPlugin.platformId()}")
    ts.setLogFile(File(dir, "plugin.log").path)
    val t1 = System.nanoTime()
    val login = ts.start(File(dir, "state").path, "micmask-ci-smoke", ephemeral = true, waitMs = 30_000)
    val st = ts.status()
    println("start -> state=${st.state} (${(System.nanoTime() - t1) / 1_000_000} ms) loginUrl=${login?.substringBefore("/a/")}/...")
    check(login != null && login.startsWith("https://login.tailscale.com/")) { "no login URL: ${st}" }
    ts.logout(wipe = true)
    check(ts.status().state == "NotStarted")
    println("ALL PASS (login)")
}

fun main(args: Array<String>) {
    if (args[2] == "login") return loginSmoke(File(args[0]), args[1])
    val dir = File(args[0]); val manifest = args[1]; val info = File(args[2])
    val kv = info.readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    var t = System.nanoTime()
    fun lap(): Long { val n = System.nanoTime(); val d = (n - t) / 1_000_000; t = n; return d }

    val ts = TsPlugin.ensure(dir, listOf(manifest)) { d, tot -> if (d == tot) println("downloaded $d bytes") }
    println("PASS load+verify (${lap()} ms) plugin=${ts.pluginVersion} installed=${TsPlugin.installed(dir)?.version}")
    ts.setLogFile(File(dir, "plugin.log").path)
    ts.setInterfaces(TsInterfaces.currentJson())
    println("interfaces: " + TsInterfaces.currentJson().take(160))
    val login = ts.start(File(dir, "state").path, "mm-jvm", controlUrl = kv["CONTROL"], ephemeral = true)
    val st = ts.status()
    println("PASS start (${lap()} ms) loginUrl=$login running=${st.running} ips=${st.ips}")
    check(st.running && st.ips.isNotEmpty())
    val bip = kv["BIP"]!!

    val lp = ts.dial(bip, 8000, udp = false)
    Socket("127.0.0.1", lp).use { check(echoTcpOnce(it, "jvm-tcp") == "echo:jvm-tcp") }
    println("PASS dial tcp 127.0.0.1:$lp -> $bip:8000 (${lap()} ms)")
    val ulp = ts.dial(bip, 8000, udp = true)
    check(echoUdp(ulp, "jvm-udp") == "echo:jvm-udp")
    println("PASS dial udp 127.0.0.1:$ulp -> $bip:8000 (${lap()} ms)")

    val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    thread(isDaemon = true) { while (true) { val c = ss.accept(); thread(isDaemon = true) { c.use { val b = ByteArray(4096); val n = it.getInputStream().read(b); it.getOutputStream().write("jvm-echo:".toByteArray() + b.copyOf(n)) } } } }
    val ds = DatagramSocket(0, InetAddress.getLoopbackAddress())
    thread(isDaemon = true) { val b = ByteArray(4096); while (true) { val p = DatagramPacket(b, b.size); ds.receive(p); val r = "jvm-echo:".toByteArray() + b.copyOf(p.length); ds.send(DatagramPacket(r, r.size, p.socketAddress)) } }
    ts.expose(7000, udp = false, localPort = ss.localPort)
    ts.expose(7000, udp = true, localPort = ds.localPort)
    val myIp = st.ips.first()
    val r1 = peerProbe(info, "tcp $myIp:7000"); println("expose tcp: $r1"); check(r1 == "OK jvm-echo:probe")
    val r2 = peerProbe(info, "udp $myIp:7000"); println("expose udp: $r2"); check(r2 == "OK jvm-echo:probe")
    println("PASS expose tcp+udp :7000 -> 127.0.0.1 (${lap()} ms)")

    val full = ts.status(withPeers = true)
    println("status: peers=${full.peers.map { it.name + "/" + it.ips.firstOrNull() + "/online=" + it.online }} exposed=${full.exposed} dials=${full.dials}")
    ts.undial(lp, false); ts.unexpose(7000, true)
    ts.stop()
    check(ts.status().state == "NotStarted")
    println("PASS stop")
    // restart from saved state (non-ephemeral would skip login; ephemeral re-registers on test control)
    println("ALL PASS")
}
