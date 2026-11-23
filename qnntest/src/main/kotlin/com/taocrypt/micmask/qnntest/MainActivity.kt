package com.taocrypt.micmask.qnntest

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.system.Os
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 测试流程：
 * 1. 「从 MicMask 复制模型」：用 Shizuku 把正式版已经交给 shell 的模型（/data/local/tmp/micmask-models）拷到本 App 目录；
 *    没装正式版 / 没用过 AI 变声时，用「选择模型文件」自己挑 .onnx（可多选）。
 * 2. 「App 进程测试」「shell 进程测试」各跑一遍 CPU / GPU / NPU。
 * 3. 「复制日志」发给开发者。
 */
class MainActivity : Activity() {
    private lateinit var log: TextView
    private val sb = StringBuilder()
    private val all = java.util.Collections.synchronizedList(ArrayList<String>())
    private val modelDir by lazy { File(getExternalFilesDir(null), "models").also { it.mkdirs() } }
    private val libDir by lazy { applicationInfo.nativeLibraryDir }
    private val adsp by lazy { "$libDir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp" }
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // QNN HTP 的 skel 库在 App 的 lib 目录里，DSP 侧按 ADSP_LIBRARY_PATH 找
        runCatching { Os.setenv("ADSP_LIBRARY_PATH", adsp, true) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }.also { root.addView(it) }
        btn("1. 从 MicMask 复制模型（需 Shizuku）") { copyFromMicMask() }
        btn("1'. 选择模型文件（.onnx，可多选）") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }, 1)
        }
        btn("2. App 进程测试（CPU / GPU / NPU）") { appTest() }
        btn("3. shell 进程测试（需 Shizuku）") { shellTest() }
        btn("复制日志") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("qnn", sb.toString()))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        log = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        out("模型目录：$modelDir（现有 ${models().joinToString { it.name }.ifEmpty { "无" }}）")
        out("提示：测试时保持亮屏、App 在前台；整个过程约 1–3 分钟。")
    }

    private fun out(s: String) { all.add(s); runOnUiThread { sb.append(s).append('\n'); log.text = sb } }
    private fun models() = Probe.MODEL_NAMES.map { File(modelDir, it) }.filter { it.isFile } +
        (modelDir.listFiles()?.filter { it.isFile && it.name.endsWith(".onnx") && it.name !in Probe.MODEL_NAMES } ?: emptyList())

    private fun bg(f: () -> Unit) {
        if (busy) { Toast.makeText(this, "还在跑，稍等", Toast.LENGTH_SHORT).show(); return }
        busy = true
        Thread { try { f() } catch (t: Throwable) { out("异常：$t") } finally { busy = false } }.start()
    }

    private fun shizukuOk(): Boolean {
        val ok = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        if (!ok) {
            out("Shizuku 未运行或未授权，正在申请…")
            runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(7) }
        }
        return ok
    }

    /** 以 shell 身份跑命令（newProcess 在 API 13 是私有的，反射调用），逐行回调 */
    private fun sh(cmd: String, line: (String) -> Unit): Int {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
        p.inputStream.bufferedReader().forEachLine(line)
        return p.waitFor()
    }

    private fun copyFromMicMask() {
        if (!shizukuOk()) return
        bg {
            out("从 ${Probe.MM_DIR} 复制模型…")
            val files = Probe.MODEL_NAMES.joinToString(" ") { "'${Probe.MM_DIR}/$it'" }
            sh("for f in $files; do [ -f \"\$f\" ] && cp \"\$f\" '$modelDir/' && echo \"已复制 \$(basename \$f)\"; done; ls -l '$modelDir'") { out(it) }
            val m = models()
            out(if (m.isEmpty()) "没找到模型：先在 MicMask 里开一次 AI 实时变声（会把模型交给 shell），或用「选择模型文件」" else "就绪：${m.joinToString { it.name }}")
        }
    }

    @Deprecated("simple test app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK || data == null) return
        val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        bg {
            uris.forEach { u ->
                val name = (contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("_display_name")) else null } ?: "model_${System.nanoTime()}.onnx")
                contentResolver.openInputStream(u)?.use { i -> File(modelDir, name).outputStream().use { i.copyTo(it) } }
                out("已导入 $name")
            }
        }
    }

    private fun tasks(): List<Pair<File?, String>> = listOf<Pair<File?, String>>(null to "header") +
        models().flatMap { m -> Probe.TIERS.map { m to it.first } }

    /** App 进程：每项起一次 :probe 进程，跟踪它写的日志文件；进程消失却没写 END 就记为崩溃 */
    private fun appTest() = bg {
        val start = all.size
        for ((m, tier) in tasks()) {
            if (m != null) out("-- ${m.name} · ${Probe.TIERS.first { it.first == tier }.second}")
            val f = File(cacheDir, "probe.log"); f.delete()
            val conn = object : android.content.ServiceConnection {
                override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {}
                override fun onServiceDisconnected(n: android.content.ComponentName?) {}
            }
            // 每次用不同的 action，保证 onBind 都会被调用（同一 Intent 的 binder 会被缓存）
            val it = Intent(this, ProbeService::class.java).setAction("run${System.nanoTime()}").putExtra("log", f.path).putExtra("model", m?.path).putExtra("tier", tier)
            bindService(it, conn, BIND_AUTO_CREATE or BIND_IMPORTANT or BIND_ABOVE_CLIENT)
            var read = 0; var pid = 0; var done = false; val t0 = System.currentTimeMillis(); var gone = 0
            while (!done) {
                Thread.sleep(200)
                val lines = if (f.exists()) f.readLines() else emptyList()
                for (l in lines.drop(read)) when {
                    l.startsWith("PID|") -> pid = l.substring(4).toInt()
                    l == "END" -> done = true
                    else -> out(l)
                }
                read = lines.size
                if (!done && pid != 0 && !File("/proc/$pid").exists()) { if (++gone > 3) { out("  进程崩溃（无输出）"); if (m != null) out("RESULT|${m.name}|$tier|CRASH"); done = true } }
                if (!done && System.currentTimeMillis() - t0 > 180_000) { out("  超时 180s"); if (m != null) out("RESULT|${m.name}|$tier|TIMEOUT"); done = true }
            }
            runCatching { unbindService(conn) }; Thread.sleep(300)
        }
        Probe.summary(all.drop(start)).forEach(::out)
    }

    private fun shellTest() {
        if (!shizukuOk()) return
        bg {
            val start = all.size
            val apk = applicationInfo.sourceDir
            val base = "export LD_LIBRARY_PATH='$libDir'; export ADSP_LIBRARY_PATH='$adsp'; " +
                "CLASSPATH='$apk' app_process -Djava.class.path='$apk' -Djava.library.path='$libDir' /system/bin com.taocrypt.micmask.qnntest.ShellProbe"
            for ((m, tier) in tasks()) {
                if (m != null) out("-- ${m.name} · ${Probe.TIERS.first { it.first == tier }.second}（shell）")
                val rc = sh(if (m == null) "$base header" else "$base '${m.path}' $tier") { out(it) }
                if (rc != 0 && m != null) { out("  shell 进程异常退出 rc=$rc"); out("RESULT|${m.name}|$tier|CRASH") }
            }
            Probe.summary(all.drop(start)).forEach(::out)
        }
    }
}
