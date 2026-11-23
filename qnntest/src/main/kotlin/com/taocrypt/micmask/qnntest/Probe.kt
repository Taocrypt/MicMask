package com.taocrypt.micmask.qnntest

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * 三档测速：CPU / QNN-GPU / QNN-HTP(NPU)。App 进程（MainActivity）和 shell 进程（ShellProbe）共用。
 * 每个模型：建会话耗时、首跑耗时、5 次平均、与 CPU 输出的最大相对误差；
 * HTP 另跑一次「禁止回退 CPU」看能不能整图上 NPU。
 */
object Probe {
    private val env by lazy { OrtEnvironment.getEnvironment() }

    fun header(out: (String) -> Unit, where: String) {
        out("== MicMask QNN 测试 · $where ==")
        out("设备 ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        if (android.os.Build.VERSION.SDK_INT >= 31) out("SoC ${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}")
        out("hardware ${android.os.Build.HARDWARE} · board ${android.os.Build.BOARD}")
        out("uid ${android.os.Process.myUid()} · ORT ${runCatching { env.version }.getOrDefault("?")} · 可用 EP ${runCatching { OrtEnvironment.getAvailableProviders() }.getOrNull()}")
        out("ADSP_LIBRARY_PATH=${System.getenv("ADSP_LIBRARY_PATH")}")
        listOf("/dev/fastrpc-cdsp", "/dev/fastrpc-cdsp-secure", "/dev/fastrpc-adsp", "/dev/adsprpc-smd", "/dev/kgsl-3d0").forEach { p ->
            val f = File(p); out("  $p 存在=${f.exists()} 可读写=${f.canRead() && f.canWrite()}")
        }
    }

    /** 生成输入：按名字和形状猜（流式合成器 phone/pitch/pitchf、hubert source、rmvpe input） */
    private fun inputs(s: OrtSession): Map<String, OnnxTensor> {
        val r = java.util.Random(1)
        return s.inputInfo.mapValues { (name, ni) ->
            val ti = ni.info as TensorInfo
            val shape = ti.shape.mapIndexed { i, d ->
                if (d > 0) d else when {
                    i == 0 -> 1L
                    name == "source" -> 16320L
                    name == "input" -> 128L
                    else -> 100L
                }
            }.toLongArray()
            val n = shape.fold(1L) { a, b -> a * b }.toInt()
            when (ti.type) {
                OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { if (name == "sid") 0 else 120L }), shape)
                else -> OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n) { when (name) { "pitchf" -> 300f; "source" -> (r.nextFloat() - 0.5f) * 0.2f; else -> r.nextFloat() - 0.5f } }), shape)
            }
        }
    }

    private fun firstOut(res: OrtSession.Result): FloatArray? = runCatching {
        val t = res.get(0) as OnnxTensor
        val fb = t.floatBuffer; FloatArray(fb.remaining()).also { fb.get(it) }
    }.getOrNull()

    class R(val ms: Double, val out: FloatArray?)

    private fun bench(path: String, label: String, opts: OrtSession.SessionOptions, out: (String) -> Unit): R? {
        val t0 = System.nanoTime()
        val s = try { env.createSession(path, opts) } catch (t: Throwable) {
            out("  [$label] 建会话失败：${t.javaClass.simpleName}: ${t.message?.lineSequence()?.take(3)?.joinToString(" | ")}"); return null
        }
        val tLoad = (System.nanoTime() - t0) / 1e6
        try {
            val ins = inputs(s)
            try {
                val a = System.nanoTime()
                val first = s.run(ins).use { firstOut(it) }
                val tFirst = (System.nanoTime() - a) / 1e6
                val b = System.nanoTime()
                repeat(5) { s.run(ins).close() }
                val avg = (System.nanoTime() - b) / 5e6
                out("  [$label] 加载 %.0fms · 首跑 %.0fms · 平均 %.1fms".format(tLoad, tFirst, avg))
                return R(avg, first)
            } finally { ins.values.forEach { it.close() } }
        } catch (t: Throwable) {
            out("  [$label] 运行失败：${t.javaClass.simpleName}: ${t.message?.lineSequence()?.take(3)?.joinToString(" | ")}"); return null
        } finally { s.close() }
    }

    private fun relErr(a: FloatArray?, b: FloatArray?): String {
        if (a == null || b == null || a.size != b.size) return "无法比较"
        var mx = 1e-9; var d = 0.0
        for (i in a.indices) { mx = maxOf(mx, Math.abs(a[i].toDouble())); d = maxOf(d, Math.abs((a[i] - b[i]).toDouble())) }
        return "%.3f".format(d / mx)
    }

    private fun cpu(th: Int) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT); setIntraOpNumThreads(th); setInterOpNumThreads(1)
        // QNN 只认静态形状：把动态维固定成实时推理用的长度
        setSymbolicDimensionValue("T", 16320); setSymbolicDimensionValue("time", 128)
    }

    private fun qnn(backend: String, strict: Boolean) = cpu(4).apply {
        val m = HashMap<String, String>()
        m["backend_path"] = backend
        if (backend.contains("Htp")) { m["htp_performance_mode"] = "burst"; m["enable_htp_fp16_precision"] = "1" }
        addQnn(m)
        if (strict) addConfigEntry("session.disable_cpu_ep_fallback", "1")
        setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
    }

    val TIERS = listOf("cpu" to "CPU 4线程", "gpu" to "GPU(QNN)", "htp" to "NPU(QNN HTP)", "htp_strict" to "NPU 整图(禁回退)")

    /** ORT 在安卓把日志写 logcat：抓本进程里 QNN 分区/失败原因相关的行 */
    private fun qnnLogs(since: String, out: (String) -> Unit) {
        runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "tag", "--pid=${android.os.Process.myPid()}", "-T", since).redirectErrorStream(true).start()
            val keys = Regex("(?i)partition|supported|failed|error|fastrpc|transport|backend|op type|validation|qnn")
            var n = 0
            p.inputStream.bufferedReader().forEachLine { l ->
                if (keys.containsMatchIn(l) && n < 40) { out("    log: " + l.take(220)); n++ }
            }
            p.waitFor()
        }
    }

    /** 单个模型 × 单个档位（每项放在独立进程里跑，崩了只丢这一项）。最后输出 RESULT|模型|档位|平均ms 或 FAIL */
    fun runOne(f: File, tier: String, out: (String) -> Unit) {
        val label = TIERS.firstOrNull { it.first == tier }?.second ?: tier
        val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val opts = when (tier) {
            "cpu" -> cpu(4); "gpu" -> qnn("libQnnGpu.so", false)
            "htp" -> qnn("libQnnHtp.so", false); else -> qnn("libQnnHtp.so", true)
        }
        val r = bench(f.absolutePath, label, opts, out)
        if (tier != "cpu") qnnLogs(since, out)
        if (r != null && tier != "cpu") {
            val c = bench(f.absolutePath, "对照 CPU", cpu(4)) {}
            out("  [$label] 与 CPU 输出误差 ${relErr(c?.out, r.out)}")
        }
        out("RESULT|${f.name}|$tier|${r?.let { "%.1f".format(it.ms) } ?: "FAIL"}")
    }

    /** 把 RESULT 行汇总 */
    fun summary(lines: List<String>): List<String> {
        val res = LinkedHashMap<String, MutableMap<String, String>>()
        lines.filter { it.startsWith("RESULT|") }.forEach { l ->
            val p = l.split('|'); if (p.size >= 4) res.getOrPut(p[1]) { HashMap() }[p[2]] = p[3]
        }
        return listOf("== 汇总（平均 ms/次；FAIL=失败，CRASH=进程崩溃）==") + res.map { (m, t) ->
            "$m: " + TIERS.joinToString(" · ") { (k, n) -> "$n ${t[k] ?: "-"}" }
        }
    }

    /** MicMask 正式版 shell 侧的模型目录；有就测这里的（标准名） */
    const val MM_DIR = "/data/local/tmp/micmask-models"
    val MODEL_NAMES = listOf("hubert_qnn_a16w8.onnx", "synth_qnn_a16w8.onnx", "synth_f32_static.onnx", "hubert_dyn8.onnx", "rmvpe_q8.onnx", "atri48_stream_qdq.onnx", "atri48_stream_b50_qdq.onnx", "atri48_stream_fp32.onnx")
}
