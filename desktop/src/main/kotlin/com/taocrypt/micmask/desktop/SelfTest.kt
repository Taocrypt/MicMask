package com.taocrypt.micmask.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.taocrypt.micmask.link.MmCrypto
import com.taocrypt.micmask.link.MmLink
import com.taocrypt.micmask.link.MmLinkClient
import com.taocrypt.micmask.link.MmLinkConfig
import com.taocrypt.micmask.link.MmLinkServer
import com.taocrypt.micmask.link.MmSession
import com.taocrypt.micmask.link.MmSessionListener
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 无界面自测：`MicMask.exe --smoke [--smoke-out=路径]`。CI 在装好/解包的安装包上跑它，确认精简后的 JDK 运行时（jlink modules）
 * 和 ProGuard 处理过的 jar 都还能用：Compose + miuix 离屏渲染整页、Skia 本地库、加解密、Opus、本机回环传音、mDNS、声卡枚举、HTTPS(TLS)。
 * 结果写到 stdout 和文件（Windows GUI 启动器没有控制台），全部通过退出码 0，否则 1。
 */
object SelfTest {
    private val out = StringBuilder()
    private var fails = 0
    private var pngOut: String? = null

    private fun line(s: String) { println(s); out.append(s).append('\n') }

    private fun check(name: String, block: () -> String) {
        val t0 = System.nanoTime()
        try {
            val r = block()
            line("PASS  $name  (${(System.nanoTime() - t0) / 1_000_000} ms)  $r")
        } catch (t: Throwable) {
            fails++
            line("FAIL  $name  ${t.javaClass.name}: ${t.message}")
            var c: Throwable? = t
            var depth = 0
            while (c != null && depth++ < 4) {
                if (c !== t) line("    caused by ${c.javaClass.name}: ${c.message}")
                c.stackTrace.take(8).forEach { line("        at $it") }
                c = c.cause
            }
        }
    }

    fun run(args: Array<String>): Int {
        val outFile = args.firstOrNull { it.startsWith("--smoke-out=") }?.substringAfter('=')
            ?: File(System.getProperty("java.io.tmpdir"), "micmask-smoke.txt").path
        pngOut = args.firstOrNull { it.startsWith("--smoke-png=") }?.substringAfter('=')
        line("MicMask 电脑版 ${BuildInfo.VERSION} 自测；${System.getProperty("os.name")} ${System.getProperty("os.arch")}，Java ${System.getProperty("java.version")}")
        line("modules: " + ModuleLayer.boot().modules().map { it.name }.sorted().joinToString(","))

        check("jdk-modules") {
            val need = listOf("java.desktop", "jdk.crypto.ec", "jdk.unsupported", "jdk.charsets")
            val have = ModuleLayer.boot().modules().map { it.name }.toSet()
            val miss = need - have
            require(miss.isEmpty()) { "缺少模块 $miss" }
            "ok"
        }
        check("charsets") {
            for (c in listOf("GBK", "GB18030", "UTF-8", "UTF-16LE")) java.nio.charset.Charset.forName(c)
            "native=${System.getProperty("native.encoding")} jnu=${System.getProperty("sun.jnu.encoding")}"
        }
        check("crypto") {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(ByteArray(16), "AES"), javax.crypto.spec.GCMParameterSpec(128, ByteArray(12)))
            c.doFinal(ByteArray(32))
            javax.crypto.Mac.getInstance("HmacSHA256"); java.security.MessageDigest.getInstance("SHA-256")
            java.security.KeyPairGenerator.getInstance("EC"); javax.crypto.KeyAgreement.getInstance("X25519")
            val ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.3"); ctx.init(null, null, null)
            "AES-GCM/HMAC/EC/X25519/TLSv1.3 ok, code=${MmCrypto.newPairingCode().length} 位"
        }
        check("json") { org.json.JSONObject("{\"tag_name\":\"win-v1.2.3\",\"assets\":[]}").getString("tag_name") }
        check("audio-enum") { "out=${AudioIO.outputs().size} in=${AudioIO.inputs().size} cable=${AudioIO.hasCable()}" }
        check("vbcable-bundled") { "bundled=${VbCable.bundled()} installed=${VbCable.installed()}" }
        check("link-loopback-opus") { loopback() }
        check("mdns") {
            Discovery.start { }
            Thread.sleep(300)
            Discovery.stop()
            "ips=${Discovery.localIps().size}"
        }
        check("compose-miuix-render") { render() }
        check("https") {
            // TLS 握手确认 jdk.crypto.ec 等在；纯网络不通（无网、DNS）只记警告，不算失败
            try {
                val c = java.net.URI("https://api.github.com/").toURL().openConnection() as javax.net.ssl.HttpsURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val code = c.responseCode
                "HTTP $code cipher=${c.cipherSuite}"
            } catch (e: java.net.UnknownHostException) { "WARN 无网络：${e.message}" }
            catch (e: java.net.SocketTimeoutException) { "WARN 超时：${e.message}" }
            catch (e: java.net.ConnectException) { "WARN 连不上：${e.message}" }
        }
        line(if (fails == 0) "SMOKE OK" else "SMOKE FAILED ($fails)")
        runCatching { File(outFile).apply { parentFile?.mkdirs() }.writeText(out.toString(), Charsets.UTF_8) }
        return if (fails == 0) 0 else 1
    }

    /** 本机回环：模拟手机（发送端）连本机接收端，SPAKE2 配对 + AES-GCM + Opus，收到 ≥80/100 帧算通过 */
    private fun loopback(): String {
        val got = AtomicInteger()
        val code = "246810"
        val srv = MmLinkServer(0, MmLinkConfig(MmCrypto.random(16), "selftest-pc", "windows", MmLink.CAP_RECV), { code },
            { object : MmSessionListener { override fun onPcm(s: MmSession, pcm: ShortArray) { got.incrementAndGet() } } }, {})
        srv.start()
        try {
            val phone = MmLinkClient.connect("127.0.0.1", srv.boundPort, MmLinkConfig(MmCrypto.random(16), "selftest-phone", "android", MmLink.CAP_SEND), code, false, object : MmSessionListener {})
            try {
                Thread.sleep(500)
                val f = ShortArray(MmLink.FRAME) { (5000 * Math.sin(it / 10.0)).toInt().toShort() }
                repeat(100) { phone.sendPcm(f); Thread.sleep(10) }
                Thread.sleep(1200)
                require(got.get() >= 80) { "只收到 ${got.get()}/100 帧" }
                return "收到 ${got.get()}/100 帧 udp=${phone.udpTx} codec=${phone.txCodec}"
            } finally { phone.close() }
        } finally { srv.stop() }
    }

    /** 离屏把整页界面（miuix 主题 + 所有卡片）渲染几帧：ProGuard 删掉/改坏 Compose 或 miuix 的类会在这里炸 */
    private fun render(): String {
        val scene = ImageComposeScene(560, 860, Density(1.5f)) {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.Light) }) { App() }
        }
        try {
            var img = scene.render(0)
            for (i in 1..5) img = scene.render(i * 16_000_000L)
            val bmp = org.jetbrains.skia.Bitmap.makeFromImage(img)
            var nonWhite = 0
            for (y in 0 until bmp.height step 20) for (x in 0 until bmp.width step 20) if (bmp.getColor(x, y) != -1) nonWhite++
            require(nonWhite > 0) { "渲染结果全白" }
            pngOut?.let { p -> img.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes?.let { File(p).writeBytes(it) } }
            return "${img.width}x${img.height} 非白采样点=$nonWhite"
        } finally { scene.close(); DeskLink.shutdown() }
    }
}
