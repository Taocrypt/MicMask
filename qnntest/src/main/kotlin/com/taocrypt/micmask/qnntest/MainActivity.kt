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
 * qnntest-4：「4. qnntest-4 App 进程」只测 dec_rewrite.py 的产物（*_dec_*、*_synth_rw）和 hubert_f32_static，
 *    档位 CPU / NPU fp16 / NPU fp16 整图 / NPU fp16 整图+上下文缓存。模型旁的 <模型>.onnx.inputs 一起导入。
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
        btn("4. qnntest-4：声码器/hubert fp16（App 进程）") { appTest(tasks4(), 600_000L, true) }
        btn("5. qnntest-4：同上（shell 进程，需 Shizuku）") { shellTest(tasks4(), true) }
        btn("6a. 导入 QNN 包（zip，按需下载的模拟）") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 2)
        }
        btn("6. qnntest-6：外置 QNN（App 进程）") { appTest(tasks6(), 600_000L, true, q6 = true) }
        btn("7. qnntest-6：外置 QNN（shell 进程，需 Shizuku）") { shellTest6() }
        btn("8. qnntest-7：NNAPI + GPU（App 进程）") { appTest(tasks7(), 600_000L, true) }
        btn("9. qnntest-7：NNAPI + GPU（shell 进程，需 Shizuku）") { shellTest(tasks7(), true) }
        btn("10. qnntest-8：档位压测 0.3 / 0.25 / 0.2 s（shell 进程，需 Shizuku，约 4 分钟）") { tierStress() }
        btn("复制日志") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("qnn", sb.toString()))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        log = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        out("${Probe.VERSION} · 模型目录：$modelDir（现有 ${models().joinToString { it.name }.ifEmpty { "无" }}）")
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
        if (requestCode == 2 && resultCode == RESULT_OK && data?.data != null) { importPack(data.data!!); return }
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
        models().filter { !Probe.isQ4Model(it.name) }.flatMap { m -> Probe.TIERS.map { m to it.first } }

    /** qnntest-4：先测改写后的声码器（notail → 带尾巴 → 整合成器），再测未改写的对照和 hubert */
    private fun tasks4(): List<Pair<File?, String>> {
        val order = listOf("_dec_rw_notail", "_dec_rw.", "_synth_rw", "_dec_f32", "hubert_f32")
        val ms = models().filter { Probe.isQ4Model(it.name) }.sortedBy { m -> order.indexOfFirst { m.name.contains(it) }.let { if (it < 0) 99 else it } }
        if (ms.isEmpty()) out("没有 qnntest-4 模型：用「选择模型文件」导入 *_dec_rw*.onnx（连同 .onnx.inputs）或 hubert_f32_static.onnx")
        return listOf<Pair<File?, String>>(null to "header") + ms.flatMap { m -> Probe.TIERS4.map { m to it.first } }
    }
    private fun tierName(t: String) = (Probe.TIERS + Probe.TIERS4 + Probe.TIERS6 + Probe.TIERS7).first { it.first == t }.second

    /** App 进程：每项起一次 :probe 进程，跟踪它写的日志文件；进程消失却没写 END 就记为崩溃 */
    private fun appTest(list: List<Pair<File?, String>>? = null, timeoutMs: Long = 180_000L, q4: Boolean = false, q6: Boolean = false) = bg {
        if (q6 && !File(appQnn, "libQnnHtp.so").isFile) { out("还没导入 QNN 包：先点「6a. 导入 QNN 包」"); return@bg }
        val start = all.size
        for ((m, tier) in list ?: tasks()) {
            if (m != null) out("-- ${m.name} · ${tierName(tier)}")
            val f = File(cacheDir, "probe.log"); f.delete()
            val conn = object : android.content.ServiceConnection {
                override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {}
                override fun onServiceDisconnected(n: android.content.ComponentName?) {}
            }
            // 每次用不同的 action，保证 onBind 都会被调用（同一 Intent 的 binder 会被缓存）
            val it = Intent(this, ProbeService::class.java).setAction("run${System.nanoTime()}").putExtra("log", f.path).putExtra("model", m?.path).putExtra("tier", tier)
                .putExtra("q6", q6).putExtra("qnn", appQnn.path).putExtra("qnn_noprep", appQnnNoPrep.path)
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
                if (!done && System.currentTimeMillis() - t0 > timeoutMs) { out("  超时 ${timeoutMs / 1000}s"); if (m != null) out("RESULT|${m.name}|$tier|TIMEOUT"); done = true }
            }
            runCatching { unbindService(conn) }; Thread.sleep(300)
        }
        (if (q4) Probe.summary4(all.drop(start)) else Probe.summary(all.drop(start))).forEach(::out)
    }

    // ---------------- qnntest-6 ----------------
    /** 包解压到外部目录（shell 能读）；App 进程从内部 files/qnn 加载（外部存储不可执行），shell 从 /data/local/tmp 加载 */
    private val packDir by lazy { File(getExternalFilesDir(null), "qnnpack") }
    private val appQnn by lazy { File(filesDir, "qnn") }
    private val appQnnNoPrep by lazy { File(filesDir, "qnn_noprep") }
    private val shQnn = "/data/local/tmp/micmask-qnn"
    private val shQnnNoPrep = "/data/local/tmp/micmask-qnn-noprep"

    private fun importPack(u: android.net.Uri) = bg {
        packDir.deleteRecursively(); packDir.mkdirs()
        var n = 0L
        contentResolver.openInputStream(u)?.use { ins ->
            java.util.zip.ZipInputStream(ins).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = File(e.name).name
                    if (e.isDirectory || !name.endsWith(".so")) continue
                    File(packDir, name).outputStream().use { z.copyTo(it) }
                    n += File(packDir, name).length(); out("  解压 $name ${File(packDir, name).length() / 1024} KB")
                }
            }
        }
        for ((dir, noPrep) in listOf(appQnn to false, appQnnNoPrep to true)) {
            dir.deleteRecursively(); dir.mkdirs()
            packDir.listFiles()?.filter { !(noPrep && it.name == "libQnnHtpPrepare.so") }?.forEach { it.copyTo(File(dir, it.name), true) }
        }
        out("QNN 包已导入：共 %.1f MB → ${appQnn.path}（无编译器版 ${appQnnNoPrep.path}）".format(n / 1e6))
        if (Shizuku.pingBinder()) shellCopy()
    }

    private fun shellCopy(): Boolean {
        if (!shizukuOk()) return false
        sh("rm -rf $shQnn $shQnnNoPrep; mkdir -p $shQnn $shQnnNoPrep && cp '${packDir.path}'/*.so $shQnn/ && cp '${packDir.path}'/*.so $shQnnNoPrep/ && rm -f $shQnnNoPrep/libQnnHtpPrepare.so && chmod 755 $shQnn $shQnnNoPrep && chmod 644 $shQnn/*.so $shQnnNoPrep/*.so && echo \"shell 侧 QNN 目录就绪：\$(ls $shQnn | wc -l) 个库\"") { out(it) }
        return true
    }

    /** qnntest-7：hubert / rmvpe / 声码器 × CPU 与 WebGPU */
    private fun tasks7(): List<Pair<File?, String>> {
        val order = listOf("hubert_f32", "hubert_dyn8", "rmvpe", "_dec_rw_notail", "_dec_rw.", "atri48_stream_fp32")
        val ms = models().filter { m -> order.any { m.name.contains(it) } }.sortedBy { m -> order.indexOfFirst { m.name.contains(it) } }
        if (ms.isEmpty()) out("没有模型：先点 1 从 MicMask 复制，或用 1' 导入 hubert_f32_static.onnx、*_dec_rw*.onnx")
        return listOf<Pair<File?, String>>(null to "header") + ms.flatMap { m -> Probe.TIERS7.map { m to it.first } }
    }

    private fun tasks6(): List<Pair<File?, String>> {
        val order = listOf("_dec_rw_notail", "atri48_dec_rw", "_dec_rw.")
        val ms = models().filter { m -> m.name.contains("_dec_rw") }.sortedBy { m -> order.indexOfFirst { m.name.contains(it) }.let { if (it < 0) 99 else it } }
        if (ms.isEmpty()) out("没有声码器模型：用「选择模型文件」导入 qnntest-4 的 *_dec_rw*.onnx（连同 .onnx.inputs）")
        return listOf<Pair<File?, String>>(null to "header") + ms.flatMap { m -> Probe.TIERS6.map { m to it.first } }
    }

    private fun shellTest6() {
        if (!shizukuOk()) return
        if (!File(packDir, "libQnnHtp.so").isFile) { out("还没导入 QNN 包：先点「6a. 导入 QNN 包」"); return }
        bg {
            shellCopy()
            val start = all.size
            val apk = applicationInfo.sourceDir
            // LD_LIBRARY_PATH 只给 ORT 本体；QNN 库由 Probe.useQnnDir 从 /data/local/tmp 按绝对路径加载
            val base = "export LD_LIBRARY_PATH='$libDir'; export MICMASK_QNN_DIR='$shQnn'; export MICMASK_QNN_NOPREP='$shQnnNoPrep'; " +
                "CLASSPATH='$apk' app_process -Djava.class.path='$apk' -Djava.library.path='$libDir' /system/bin com.taocrypt.micmask.qnntest.ShellProbe"
            for ((m, tier) in tasks6()) {
                if (m != null) out("-- ${m.name} · ${tierName(tier)}（shell）")
                val rc = sh(if (m == null) "$base header" else "$base '${m.path}' $tier q6") { out(it) }
                if (rc != 0 && m != null) { out("  shell 进程异常退出 rc=$rc"); out("RESULT|${m.name}|$tier|CRASH") }
            }
            Probe.summary4(all.drop(start)).forEach(::out)
        }
    }

    private fun shellTest(list: List<Pair<File?, String>>? = null, q4: Boolean = false) {
        if (!shizukuOk()) return
        bg {
            val start = all.size
            val apk = applicationInfo.sourceDir
            val base = "export LD_LIBRARY_PATH='$libDir'; export ADSP_LIBRARY_PATH='$adsp'; " +
                "CLASSPATH='$apk' app_process -Djava.class.path='$apk' -Djava.library.path='$libDir' /system/bin com.taocrypt.micmask.qnntest.ShellProbe"
            for ((m, tier) in list ?: tasks()) {
                if (m != null) out("-- ${m.name} · ${tierName(tier)}（shell）")
                val rc = sh(if (m == null) "$base header" else "$base '${m.path}' $tier") { out(it) }
                if (rc != 0 && m != null) { out("  shell 进程异常退出 rc=$rc"); out("RESULT|${m.name}|$tier|CRASH") }
            }
            (if (q4) Probe.summary4(all.drop(start)) else Probe.summary(all.drop(start))).forEach(::out)
        }
    }

    /** qnntest-8：解出 assets/tier 的随机权重合成器，缺特征模型就先从 MicMask 复制，然后在 shell 进程里按实时节拍压测三档 */
    private fun tierStress() {
        if (!shizukuOk()) return
        bg {
            val tierDir = File(getExternalFilesDir(null), "tier").also { it.mkdirs() }
            for (n in assets.list("tier").orEmpty()) {
                val dst = File(tierDir, n)
                if (!dst.isFile) assets.open("tier/$n").use { i -> dst.outputStream().use { i.copyTo(it) } }
            }
            if (!File(modelDir, "hubert_dyn8.onnx").isFile || !File(modelDir, "rmvpe_q8.onnx").isFile) {
                out("先从 ${Probe.MM_DIR} 复制特征模型…")
                sh("for f in hubert_dyn8.onnx rmvpe_q8.onnx; do [ -f '${Probe.MM_DIR}'/\$f ] && cp '${Probe.MM_DIR}'/\$f '$modelDir/' && echo 已复制 \$f; done") { out(it) }
            }
            out("开始压测：保持亮屏；中途可以切到别的 App 看后台表现，结束前别关 Shizuku。")
            val apk = applicationInfo.sourceDir
            val cmd = "export LD_LIBRARY_PATH='$libDir'; CLASSPATH='$apk' app_process -Djava.class.path='$apk' -Djava.library.path='$libDir' /system/bin " +
                "com.taocrypt.micmask.qnntest.ShellProbe stress '$modelDir' '$tierDir' 60 4 3"
            val rc = sh(cmd) { out(it) }
            if (rc != 0) out("shell 进程异常退出 rc=$rc")
            out("压测结束，点「复制日志」发给开发者。")
        }
    }
}
