package com.taocrypt.micmask.audiodiag

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MicMask 音频注入诊断：
 *  1. App 进程（前台 Activity）建一条普通 USAGE_MEDIA AudioTrack 作对照；
 *  2. 通过 Shizuku 以 shell 身份起 app_process 跑 [ShellDiag]，批量测注入轨道的各种组合；
 *  3. 可选：临时关闭 Android 17 音频加固后再测一遍，测完无论成败都恢复 enable；
 *  4. 报告可复制、分享 txt、保存到 Download/MicMask/。
 */
class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var hardenBox: CheckBox
    private val sb = StringBuilder()
    private val results = ArrayList<Triple<String, String, String>>() // 轮次, 编号, 状态|说明
    @Volatile private var busy = false
    private var savedUri: Uri? = null
    private val prefs by lazy { getSharedPreferences("diag", 0) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        fun btn(t: String, parent: ViewGroup = root, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
            .also { parent.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).takeIf { parent !== root } ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "用途：排查 MicMask 注入通道（AudioPolicy 注入轨道）建不起来的问题。需要 Shizuku 已运行并授权。全程约 1–2 分钟，请保持亮屏、本 App 在前台。本工具不会录音，也不会播放可听见的声音。"
        })
        hardenBox = CheckBox(this).apply {
            text = "临时关闭音频加固后再测一遍\n⚠️ 会临时修改系统设置：先执行 cmd audio set-enable-hardening disable，测完无论成败都执行 cmd audio set-enable-hardening enable 恢复。"
            textSize = 12f
        }
        root.addView(hardenBox)
        btn("开始诊断") { start() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row)
        btn("复制", row) { copy() }
        btn("分享 txt", row) { share() }
        btn("保存到 Download", row) { save()?.let { toast("已保存到 Download/MicMask/") } }
        log = TextView(this).apply { textSize = 10.5f; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        out("点「开始诊断」。")
        // 上次诊断中途被杀、加固没恢复：这次进来先补恢复
        if (prefs.getBoolean("hardeningPending", false)) Thread { restorePending() }.start()
    }

    private fun out(s: String) = runOnUiThread {
        sb.append(s).append('\n'); log.text = sb
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    // ------------------------------------------------------------------ Shizuku

    private fun shizukuOk(): Boolean {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) { toast("Shizuku 没有运行"); out("Shizuku 没有运行：请先在 Shizuku App 里启动服务。"); return false }
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        if (!granted) {
            out("正在申请 Shizuku 授权，允许后再点一次「开始诊断」。")
            runCatching {
                Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        out(if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权，可以开始诊断。" else "Shizuku 授权被拒绝。")
                    }
                })
                Shizuku.requestPermission(17)
            }
        }
        return granted
    }

    /** 以 shell 身份跑命令（newProcess 在 Shizuku API 13 是私有的，反射调用），逐行回调，返回退出码 */
    private fun sh(cmd: String, timeoutMs: Long = 30_000, line: (String) -> Unit): Int {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
        val dog = Thread { try { Thread.sleep(timeoutMs); line("（超时 ${timeoutMs / 1000}s，强制结束）"); p.destroy() } catch (_: InterruptedException) {} }
        dog.isDaemon = true; dog.start()
        try { p.inputStream.bufferedReader().forEachLine(line) } catch (t: Throwable) { line("（读取输出出错：$t）") }
        val rc = p.waitFor(); dog.interrupt(); return rc
    }
    private fun shText(cmd: String): String = StringBuilder().also { b -> runCatching { sh(cmd) { b.append(it).append('\n') } }.onFailure { b.append("（失败：$it）") } }.toString().trimEnd()

    // ------------------------------------------------------------------ 流程

    private fun start() {
        if (busy) { toast("还在跑，稍等"); return }
        if (!shizukuOk()) return
        busy = true; savedUri = null
        val harden = hardenBox.isChecked
        runOnUiThread { sb.setLength(0); log.text = "" }
        synchronized(results) { results.clear() }
        Thread {
            try { runDiag(harden) } catch (t: Throwable) { out("诊断异常：${android.util.Log.getStackTraceString(t)}") }
            finally { busy = false; out("\n诊断结束。可以「复制」「分享 txt」或「保存到 Download」。"); toast("诊断结束") }
        }.start()
    }

    private fun runDiag(harden: Boolean) {
        out("MicMask 音频注入诊断报告（audiodiag ${packageManager.getPackageInfo(packageName, 0).versionName}）")
        out("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        out("")
        out("== 设备 ==")
        out("型号：${Build.MANUFACTURER} ${Build.MODEL}（品牌 ${Build.BRAND}，设备 ${Build.DEVICE}，产品 ${Build.PRODUCT}）")
        out("SoC：" + (if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "（API<31）") + "；硬件 ${Build.HARDWARE}；board ${Build.BOARD}")
        out("Android：${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}，安全补丁 ${Build.VERSION.SECURITY_PATCH}）")
        out("ro.build.fingerprint：${Build.FINGERPRINT}")
        out("ro.build.display.id：${Build.DISPLAY}")
        out("")
        out("== Shizuku ==")
        out("运行：${runCatching { Shizuku.pingBinder() }.getOrElse { "异常 $it" }}")
        out("服务版本：${runCatching { Shizuku.getVersion() }.getOrElse { "取不到 $it" }}；补丁版本：${runCatching { Shizuku.getServerPatchVersion() }.getOrElse { "取不到" }}")
        out("服务 uid：${runCatching { Shizuku.getUid() }.getOrElse { "取不到 $it" }}（2000=shell，0=root）")
        out("本 App 已授权：${runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrElse { false }}")
        out("SELinux 上下文：${runCatching { Shizuku.getSELinuxContext() }.getOrElse { "取不到" }}")
        out("")
        out("== 权限检查（shell 侧，dumpsys package com.android.shell）==")
        out(shText("dumpsys package com.android.shell | grep -E 'MODIFY_AUDIO_ROUTING|CAPTURE_AUDIO_OUTPUT|RECORD_AUDIO|MODIFY_AUDIO_SETTINGS' | sed 's/^ *//' | sort -u | head -n 20").ifEmpty { "（无匹配）" })
        out("（shell 进程内的 checkPermission 结果见下方 shell 环境段）")
        out("")

        val wxUid = runCatching { packageManager.getApplicationInfo("com.tencent.mm", 0).uid }.getOrNull()
        val targetUid = wxUid ?: applicationInfo.uid
        out("目标 uid：$targetUid（${if (wxUid != null) "com.tencent.mm" else "没找到微信，用本 App 自身 uid"}）")
        out("")

        // App 进程对照
        out("### [APP-MEDIA] 对照：App 进程（前台 Activity）普通 USAGE_MEDIA AudioTrack")
        val app = appMediaTrack()
        out("  $app")
        record("常规", "APP-MEDIA|$app")
        out("")

        // 第一轮：常规
        runShell(targetUid, "常规")

        if (harden) {
            out("")
            out("================ 临时关闭音频加固后再测一遍 ================")
            out("关闭前状态（dumpsys audio | grep -i harden）：")
            out(shText("dumpsys audio | grep -i -E 'harden' | head -n 30").ifEmpty { "（无匹配）" })
            prefs.edit().putBoolean("hardeningPending", true).commit()
            try {
                var rc = -1
                val o = StringBuilder()
                runCatching { rc = sh("cmd audio set-enable-hardening disable") { o.append(it).append('\n') } }.onFailure { o.append("异常：$it") }
                out("执行 cmd audio set-enable-hardening disable → rc=$rc ${o.toString().trim()}")
                out("关闭后状态：")
                out(shText("dumpsys audio | grep -i -E 'harden' | head -n 30").ifEmpty { "（无匹配）" })
                runShell(targetUid, "关加固")
            } finally {
                restoreHardening()
            }
        }

        // 汇总
        out("")
        out("================ 汇总 ================")
        synchronized(results) {
            for ((round, id, r) in results) out("[$round] $id → ${r.lineSequence().first().take(200)}")
            val ok = results.count { it.third.startsWith("OK") }
            out("共 ${results.size} 项，成功 $ok，失败/超时 ${results.size - ok}")
        }
    }

    private fun record(round: String, raw: String) {
        // raw = 编号|状态|说明
        val p = raw.split('|', limit = 3)
        synchronized(results) { results.add(Triple(round, p.getOrElse(0) { "?" }, p.drop(1).joinToString("｜"))) }
    }

    private fun runShell(targetUid: Int, round: String) {
        val apk = applicationInfo.sourceDir
        val cmd = "CLASSPATH='$apk' app_process -Djava.class.path='$apk' /system/bin --nice-name=micmask-audiodiag " +
            "com.taocrypt.micmask.audiodiag.ShellDiag $targetUid $packageName '$round'"
        var sawEnd = false
        val rc = runCatching {
            sh(cmd, timeoutMs = 600_000) { l ->
                if (l.startsWith("RESULT|")) record(round, l.removePrefix("RESULT|")) else out(l)
                if (l.contains("结束 ==")) sawEnd = true
            }
        }.getOrElse { out("拉起 shell 进程失败：${android.util.Log.getStackTraceString(it)}"); -1 }
        if (rc != 0 || !sawEnd) {
            out("shell 进程退出码 $rc（${if (sawEnd) "正常结束" else "没有正常结束"}）")
            if (!sawEnd) record(round, "SHELL-EXIT|FAIL|shell 诊断进程异常退出 rc=$rc")
        }
    }

    private fun restoreHardening() {
        var rc = -1
        val o = StringBuilder()
        runCatching { rc = sh("cmd audio set-enable-hardening enable") { o.append(it).append('\n') } }.onFailure { o.append("异常：$it") }
        out("恢复：执行 cmd audio set-enable-hardening enable → rc=$rc ${o.toString().trim()}")
        out("恢复后状态：")
        out(shText("dumpsys audio | grep -i -E 'harden' | head -n 30").ifEmpty { "（无匹配）" })
        if (rc == 0) prefs.edit().putBoolean("hardeningPending", false).commit()
        else out("⚠️ 恢复命令没有返回 0。下次打开本 App 会自动再试；也可以手动在 shell 执行：cmd audio set-enable-hardening enable")
    }

    private fun restorePending() {
        if (!runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) {
            out("⚠️ 上次诊断临时关闭了音频加固但没来得及恢复。请启动 Shizuku 并授权后重开本 App，会自动恢复。")
            return
        }
        out("上次诊断临时关闭的音频加固没有恢复，现在补恢复：")
        restoreHardening()
    }

    private fun appMediaTrack(): String {
        var t: AudioTrack? = null
        return try {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val fmt = AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3840)).build()
            val st = t.state
            if (st != AudioTrack.STATE_INITIALIZED) return "FAIL|state=$st 未初始化"
            t.play()
            val w = t.write(ShortArray(48000 / 50 * 2), 0, 48000 / 50 * 2)
            Thread.sleep(60)
            "OK|state=$st playState=${t.playState} write=$w"
        } catch (e: Throwable) {
            "FAIL|" + android.util.Log.getStackTraceString(e).trimEnd().replace("\n", "\n    ")
        } finally { runCatching { t?.stop() }; runCatching { t?.release() } }
    }

    // ------------------------------------------------------------------ 导出

    private fun report(): String = sb.toString()

    private fun copy() {
        var s = report()
        if (s.length > 400_000) { s = s.take(400_000) + "\n…（超过 40 万字，复制已截断，完整内容请用「分享 txt」）"; toast("报告太长，已截断复制") }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MicMask 音频诊断", s))
        toast("已复制")
    }

    /** 写到 Download/MicMask/（MediaStore，无需存储权限），返回 content:// uri */
    private fun save(): Uri? = runCatching {
        if (busy) { toast("诊断还没结束"); return null }
        val name = "MicMask-audiodiag-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MicMask")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("MediaStore 插入失败")
        contentResolver.openOutputStream(uri)!!.use { it.write(report().toByteArray()) }
        savedUri = uri
        uri
    }.getOrElse { toast("保存失败：${it.message}"); null }

    private fun share() {
        val uri = savedUri ?: save() ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MicMask 音频注入诊断报告")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "分享诊断报告"))
    }
}
