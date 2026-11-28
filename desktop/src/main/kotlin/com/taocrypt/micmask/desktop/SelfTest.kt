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
        check("i18n") {
            val n = I18n.en.size
            require(n > 100) { "英文表只有 $n 条" }
            val old = I18n.lang
            I18n.set(2); val e = tr("设置"); val e2 = tr("已连接 {0}", "X"); I18n.set(1); val z = tr("设置"); I18n.set(old)
            require(e == "Settings" && z == "设置" && e2 == "Connected to X") { "翻译不对：$e / $z / $e2" }
            "en=$n 条"
        }
        check("audio-enum") {
            val ins = AudioIO.inputs(); val outs = AudioIO.outputs()
            "out=${outs.size} in=${ins.size} cable=${AudioIO.hasCable()} 录音设备：" + ins.joinToString(" | ") { AudioIO.label(it, ins, true) }
        }
        check("win-endpoints-parse") {
            val m = WinEndpoints.parse(REG_SAMPLE)
            val mic = m["microphone (realtek(r) audio)"]; val bt = m["headset (airpods pro hands-free)"]; val usb = m["microphone (usb audio device)"]
            require(mic?.kind == AudioIO.Kind.BUILTIN && bt?.kind == AudioIO.Kind.BLUETOOTH && usb?.kind == AudioIO.Kind.USB) { "类型识别不对：$m" }
            require(usb.isDefault && !mic.isDefault) { "默认设备识别不对：$m" }
            val real = WinEndpoints.capture()
            "样例 ok；本机注册表录音端点 ${real.size} 个" + (if (real.isNotEmpty()) "：" + real.values.joinToString { "${it.name}[${it.kind}${if (it.isDefault) ",默认" else ""}]" } else "")
        }
        check("capture-backlog") {
            val fb = 960
            require(AudioIO.backlogSkip(fb * 5, fb, fb * 6, fb * 2) == 0)
            val k = AudioIO.backlogSkip(fb * 10, fb, fb * 6, fb * 2)
            require(k == fb * 8) { "k=$k" }
            "积压 100 ms → 丢 80 ms 留 20 ms"
        }
        check("playout-drift") { playoutDrift() }
        check("tx-drift") {
            val d = TxDrift(); val silent = ShortArray(480)
            var drops = 0
            for (sec in 0 until 20) {
                val st = com.taocrypt.micmask.link.MmStats(5, 1f, 0f, if (sec < 5) 40 else 40 + sec * 20, 0)
                repeat(100) { if (d.drop(silent, st)) drops++ }
            }
            require(drops in 20..100) { "drops=$drops" }
            "手机缓冲持续上涨时 20 秒追 $drops 帧"
        }
        check("log-export") {
            DeskLog.start()
            DeskLink.addLog("自测：配对码 ${DeskLink.code} 不应出现在日志里")
            Thread.sleep(800)
            val f = File(System.getProperty("java.io.tmpdir"), DeskLog.defaultExportName())
            DeskLog.exportZip(f)
            val names = ArrayList<String>(); val all = StringBuilder()
            java.util.zip.ZipInputStream(f.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; names += e.name; all.append(z.readBytes().toString(Charsets.UTF_8)) } }
            require("system-info.txt" in names && names.any { it.startsWith("logs/") }) { "zip 内容 $names" }
            require(!all.contains(DeskLink.code)) { "导出内容里有配对码" }
            require(all.contains("不应出现在日志里")) { "日志没写进文件" }
            "zip ${f.length() / 1024} KB：$names；配对码已脱敏；日志目录 ${DeskLog.dir}"
        }
        check("local-addrs") { Discovery.addrs().joinToString { "${it.ip.hostAddress}(${it.ifName}${if (it.vpn) ",虚拟" else ""})" }.ifEmpty { "无" } }
        check("codec-follow") { codecFollow() }
        check("opus-bench") {
            val enc = com.taocrypt.micmask.link.MmOpusEncoder(); val dec = com.taocrypt.micmask.link.MmOpusDecoder()
            val f = ShortArray(MmLink.FRAME) { (6000 * Math.sin(it / 7.0)).toInt().toShort() }
            repeat(300) { dec.decode(enc.encode(f)) }
            val t = System.nanoTime(); repeat(500) { dec.decode(enc.encode(f)) }
            val us = (System.nanoTime() - t) / 500 / 1000
            "编码+解码 ${us} µs/帧（10 ms 帧，占单核 ${"%.1f".format(us / 100.0)}%）"
        }
        check("vbcable-bundled") { "bundled=${VbCable.bundled()} installed=${VbCable.installed()}" }
        check("link-loopback-opus") { loopback() }
        check("mdns") {
            Discovery.start { }
            Discovery.register("selftest-pc", 47399, "00112233") { }
            Thread.sleep(300)
            val t = System.currentTimeMillis()
            Discovery.stop(1500)
            "ips=${Discovery.localIps().size} 关闭耗时 ${System.currentTimeMillis() - t} ms"
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
        val scene = ImageComposeScene(1100, 720, Density(1f)) {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.Light) }) { Root() }
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
        } finally { scene.close(); DeskLink.stopPreview() }
    }

    /** 自适应抖动缓冲：手机时钟快 1%（每 100 次读写 101 帧）+ 随机突发，模拟 120 秒：水位要稳定、裁剪要发生、断流少 */
    private fun playoutDrift(): String {
        val pb = DeskPlayout(); pb.jitterMs = 10f
        val f = ShortArray(480) { 1000 }; val o = ShortArray(480)
        val rnd = java.util.Random(1)
        var pending = 0; var maxMs = 0
        for (i in 0 until 12_000) {
            pending += if (i % 100 == 0) 2 else 1
            // 网络突发：偶尔攒 3 帧一起到
            if (rnd.nextInt(20) != 0 || pending > 3) { repeat(pending) { pb.write(f) }; pending = 0 }
            pb.read(o, 480)
            if (i > 1000) maxMs = maxOf(maxMs, pb.sizeMs())
        }
        require(maxMs < 120) { "水位涨到 $maxMs ms" }
        require(pb.trimmedMs in 600..2400) { "裁剪 ${pb.trimmedMs} ms（期望约 1200）" }
        require(pb.underruns <= 3) { "断流 ${pb.underruns}" }
        return "最高水位 $maxMs ms · 目标 ${pb.targetMs()} ms · 裁剪 ${pb.trimmedMs} ms · 断流 ${pb.underruns}"
    }

    /** 编码跟随接收方：接收端偏好 PCM 时，电脑「跟随手机」发 PCM；偏好 Opus 时发 Opus（验证反射改 txCodec 在 ProGuard 后仍可用） */
    private fun codecFollow(): String {
        val r = StringBuilder()
        val oldKey = DeskLink.inputKey; val oldCodec = DeskLink.codecMode
        for (pref in listOf(MmLink.CODEC_PCM16, MmLink.CODEC_OPUS)) {
            val got = java.util.concurrent.atomic.AtomicInteger(-1)
            val srv = MmLinkServer(0, MmLinkConfig(MmCrypto.random(16), "selftest-phone", "android", MmLink.CAP_RECV, pref), { "112233" },
                { object : MmSessionListener { override fun onPcm(s: MmSession, pcm: ShortArray) { got.set(s.rxCodec) } } }, {})
            srv.start()
            try {
                DeskLink.setCodec(0)
                DeskLink.setInput("test:sine")
                DeskLink.startSend("127.0.0.1", srv.boundPort, "自测", "112233")
                val end = System.currentTimeMillis() + 4000
                while (got.get() < 0 && System.currentTimeMillis() < end) Thread.sleep(50)
                require(got.get() == pref) { "接收方偏好 $pref，实际收到编码 ${got.get()}；${DeskLink.txError}" }
                r.append(if (pref == MmLink.CODEC_OPUS) "Opus→Opus " else "PCM→PCM ")
            } finally {
                DeskLink.stopSend(); srv.stop()
                SavedDevices.list.filter { it.name == "selftest-phone" }.forEach { SavedDevices.remove(it.id) }
                DeskLink.setInput(oldKey); DeskLink.setCodec(oldCodec)
            }
        }
        return r.toString().trim()
    }

    /** reg query 输出样例（三个录音端点：内置麦、蓝牙耳机、USB 麦克风=默认） */
    private val REG_SAMPLE = """
HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture\{0.0.1.00000000}.{aaaa}
    DeviceState    REG_DWORD    0x1
    Role:0    REG_BINARY    E8070B00040014000A0000000000E803

HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture\{0.0.1.00000000}.{aaaa}\Properties
    {a45c254e-df1c-4efd-8020-67d146a850e0},2    REG_SZ    Microphone
    {b3f8fa53-0004-438e-9003-51a46e139bfc},6    REG_SZ    Realtek(R) Audio
    {1da5d803-d492-4edd-8c23-e0c0ffee7f0e},0    REG_DWORD    0x4
    {b3f8fa53-0004-438e-9003-51a46e139bfc},2    REG_SZ    {1}.HDAUDIO\FUNC_01&VEN_10EC

HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture\{0.0.1.00000000}.{bbbb}
    DeviceState    REG_DWORD    0x1

HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture\{0.0.1.00000000}.{bbbb}\Properties
    {a45c254e-df1c-4efd-8020-67d146a850e0},2    REG_SZ    Headset
    {b3f8fa53-0004-438e-9003-51a46e139bfc},6    REG_SZ    AirPods Pro Hands-Free
    {1da5d803-d492-4edd-8c23-e0c0ffee7f0e},0    REG_DWORD    0x5
    {b3f8fa53-0004-438e-9003-51a46e139bfc},2    REG_SZ    {2}.\\?\BTHHFENUM#{0000111e}

HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture\{0.0.1.00000000}.{cccc}
    DeviceState    REG_DWORD    0x1
    Role:0    REG_BINARY    EA070B00060018000C0000000000E803

HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture\{0.0.1.00000000}.{cccc}\Properties
    {a45c254e-df1c-4efd-8020-67d146a850e0},2    REG_SZ    Microphone
    {b3f8fa53-0004-438e-9003-51a46e139bfc},6    REG_SZ    USB Audio Device
    {1da5d803-d492-4edd-8c23-e0c0ffee7f0e},0    REG_DWORD    0x4
    {b3f8fa53-0004-438e-9003-51a46e139bfc},2    REG_SZ    {1}.USB\VID_0D8C&PID_0014

HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture\{0.0.1.00000000}.{dddd}
    DeviceState    REG_DWORD    0x4
""".trimIndent()
}

/** 内存测量：工作集 / 私有字节（Windows 用 PowerShell Get-Process，Linux 读 /proc），以及 JVM 堆 / 非堆 / Metaspace / 线程数 */
object MemProbe {
    private val isWin = System.getProperty("os.name").lowercase().contains("win")

    fun header(): String {
        val rt = java.lang.management.ManagementFactory.getRuntimeMXBean()
        val flags = rt.inputArguments.filter { it.startsWith("-X") || it.startsWith("-Dskiko") || it.startsWith("-Dsun.java2d") }
        val gcs = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().joinToString(",") { it.name }
        return "MEM  flags=[${flags.joinToString(" ")}] gc=[$gcs] skiko.renderApi=${System.getProperty("skiko.renderApi") ?: "默认"} maxHeap=${Runtime.getRuntime().maxMemory() / 1048576} MB"
    }

    private fun os(): Pair<Double, Double> = runCatching {
        val pid = ProcessHandle.current().pid()
        if (isWin) {
            val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", "\$p=Get-Process -Id $pid; '{0} {1}' -f \$p.WorkingSet64,\$p.PrivateMemorySize64").redirectErrorStream(true).start()
            val t = p.inputStream.bufferedReader().readText().trim().lines().last().split(' ')
            p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
            t[0].toDouble() / 1048576 to t[1].toDouble() / 1048576
        } else {
            val m = java.io.File("/proc/self/status").readLines().associate { it.substringBefore(':') to it.substringAfter(':').trim() }
            fun kb(k: String) = (m[k]?.substringBefore(' ')?.toDoubleOrNull() ?: -1024.0) / 1024
            kb("VmRSS") to kb("RssAnon")
        }
    }.getOrDefault(-1.0 to -1.0)

    fun line(stage: String): String {
        val mx = java.lang.management.ManagementFactory.getMemoryMXBean()
        val h = mx.heapMemoryUsage; val nh = mx.nonHeapMemoryUsage
        val meta = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().firstOrNull { it.name == "Metaspace" }?.usage?.used ?: -1
        val th = Thread.activeCount()
        val (ws, priv) = os()
        fun mb(b: Long) = "%.1f".format(b / 1048576.0)
        return "MEM  %-28s ws=%.1f MB  private=%.1f MB  heap=%s/%s MB  nonheap=%s MB  metaspace=%s MB  threads=%d  render=%s".format(
            stage, ws, priv, mb(h.used), mb(h.committed), mb(nh.committed), mb(meta), th, Ui.renderApi)
    }
}

/** 自测里的「收发 10 秒」：模拟手机 → 电脑接收（Opus），同时电脑（合成正弦音源）→ 模拟手机接收；都走本机回环 */
object SmokeTraffic {
    fun run(ms: Long): String {
        val oldKey = DeskLink.inputKey
        DeskLink.startReceive()
        val got = java.util.concurrent.atomic.AtomicInteger()
        val phoneRx = MmLinkServer(47311, MmLinkConfig(MmCrypto.random(16), "selftest-phone-rx", "android", MmLink.CAP_RECV), { "135790" },
            { object : MmSessionListener { override fun onPcm(s: MmSession, pcm: ShortArray) { got.incrementAndGet() } } }, {})
        phoneRx.start()
        var phone: MmSession? = null
        val run = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            Thread.sleep(300)
            phone = MmLinkClient.connect("127.0.0.1", MmLink.DEFAULT_PORT, MmLinkConfig(MmCrypto.random(16), "selftest-phone-tx", "android", MmLink.CAP_SEND), DeskLink.code, false, object : MmSessionListener {})
            val ph = phone
            Thread({
                val f = ShortArray(MmLink.FRAME); var p = 0.0; var next = System.nanoTime()
                while (run.get()) {
                    for (i in f.indices) { f[i] = (5000 * Math.sin(p)).toInt().toShort(); p += 2 * Math.PI * 300 / MmLink.SR }
                    ph.sendPcm(f); next += 10_000_000L
                    val w = next - System.nanoTime(); if (w > 0) Thread.sleep(w / 1_000_000, (w % 1_000_000).toInt())
                }
            }, "smoke-phone").apply { isDaemon = true; start() }
            DeskLink.setInput("test:sine")
            DeskLink.startSend("127.0.0.1", 47311, "selftest", "135790")
            Thread.sleep(ms)
            val rx = DeskLink.rxStats
            return "电脑接收：$rx ｜ 电脑发送：${DeskLink.txStats} ｜ 模拟手机收到 ${got.get()} 帧"
        } finally {
            run.set(false)
            DeskLink.stopSend(); phone?.close(); phoneRx.stop(); DeskLink.stopReceive()
            DeskLink.setInput(oldKey)
            SavedDevices.list.filter { it.name.startsWith("selftest-") }.forEach { SavedDevices.remove(it.id) }
        }
    }
}
