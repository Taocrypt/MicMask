package com.taocrypt.micmask.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * VB-CABLE（VB-Audio Software 的捐赠软件，https://vb-cable.com）。
 * 许可（vb-audio.com/Services/licensing.htm「VB-CABLE Distribution with another product」，2026-11-28 核对）：允许把 VB-CABLE 包原样随软件分发，
 * 并可嵌入安装程序静默安装，条件是用户能清楚看出 VB-CABLE 是 VB-Audio 的应用、并有机会捐赠；须写明来源 www.vb-cable.com 与「捐赠软件」。
 * A+B / C+D 版不可分发。企业/机构环境分发需另购授权。
 *
 * 做法：安装包里原样附带官方 VBCABLE_Driver_Pack46.zip（CI 从官网下载并校验 sha256，不改动内容）；
 * 首次运行检测没有「CABLE Input」时提示，用户点「一键安装」后解压到临时目录，以管理员身份静默运行 VBCABLE_Setup_x64.exe。
 * 开发构建没有附带包时，改为从官网下载同一个包（同样校验 sha256）。
 */
object VbCable {
    const val PACK = "VBCABLE_Driver_Pack46.zip"
    const val URL_PACK = "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack46.zip"
    /** 2026-11-28 从官网下载的 Pack46 的 sha256（与 CI 校验值一致） */
    const val SHA256 = "bc44e8974191a5a2427d6c979e2dab08dbb09925f3f435dfa2f0ba3e45b6eb90"
    const val NOTICE = "VB-CABLE 由 VB-Audio Software 提供，是捐赠软件（donationware），欢迎到 vb-cable.com 支持作者。MicMask 只原样附带其官方安装包，不修改。"
    const val SITE = "https://vb-cable.com"

    fun installed() = AudioIO.hasCable()

    /** 安装包附带的官方 zip（jpackage 的 app 资源目录：compose.application.resources.dir/vbcable/） */
    fun bundled(): File? = System.getProperty("compose.application.resources.dir")?.let { File(it, "vbcable/$PACK") }?.takeIf { it.isFile }

    private fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md -> f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }; md.digest().joinToString("") { "%02x".format(it) } }

    private fun download(dst: File, log: (String) -> Unit) {
        log("从官网下载 VB-CABLE：$URL_PACK")
        val c = URL(URL_PACK).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.inputStream.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
    }

    /**
     * 解压并以管理员身份静默安装（弹 Windows UAC 确认）。阻塞直到安装程序退出；返回结果说明。
     * 静默参数 -i -h：VB-CABLE 安装程序的命令行参数（社区/分发方普遍使用，官网未成文，推断）；失败时提示用户手动运行解压目录里的安装程序。
     */
    fun install(log: (String) -> Unit): String {
        if (!System.getProperty("os.name").lowercase().contains("win")) return "只有 Windows 需要安装 VB-CABLE"
        val work = File(System.getProperty("java.io.tmpdir"), "MicMask-VBCABLE").apply { deleteRecursively(); mkdirs() }
        val zip = bundled() ?: File(work, PACK).also { runCatching { download(it, log) }.onFailure { e -> return "下载失败：${e.message}。可手动到 $SITE 下载安装" } }
        val sum = sha256(zip)
        if (sum != SHA256) return "安装包校验失败（sha256 $sum），已取消。可手动到 $SITE 下载安装"
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(work, e.name).canonicalFile
                if (!f.path.startsWith(work.canonicalPath)) continue
                if (e.isDirectory) f.mkdirs() else { f.parentFile.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
        val arch = (System.getenv("PROCESSOR_ARCHITEW6432") ?: System.getenv("PROCESSOR_ARCHITECTURE") ?: "").uppercase()
        val exe = File(work, if (arch.contains("64")) "VBCABLE_Setup_x64.exe" else "VBCABLE_Setup.exe")
        if (!exe.isFile) return "安装包里没有 ${exe.name}"
        log("以管理员身份运行 ${exe.name} -i -h（会弹出 Windows 权限确认）")
        val ps = "Start-Process -FilePath '${exe.absolutePath.replace("'", "''")}' -ArgumentList '-i','-h' -WorkingDirectory '${work.absolutePath.replace("'", "''")}' -Verb RunAs -Wait"
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val rc = p.waitFor()
        if (rc != 0) return "安装未完成（可能取消了权限确认）：${out.trim().take(200)}。也可手动运行 ${exe.absolutePath}"
        return if (installed()) "VB-CABLE 已安装。建议重启电脑后再用" else "安装程序已运行完。VB-CABLE 需要重启电脑后才会出现；重启后在聊天软件里把麦克风选成「CABLE Output」"
    }
}
