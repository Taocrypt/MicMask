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
    }

    private fun qnn(backend: String, strict: Boolean) = cpu(4).apply {
        val m = HashMap<String, String>()
        m["backend_path"] = backend
        if (backend.contains("Htp")) { m["htp_performance_mode"] = "burst"; m["enable_htp_fp16_precision"] = "1" }
        addQnn(m)
        if (strict) addConfigEntry("session.disable_cpu_ep_fallback", "1")
    }

    fun runAll(models: List<File>, out: (String) -> Unit) {
        if (models.isEmpty()) { out("没有找到模型文件"); return }
        val summary = ArrayList<String>()
        for (f in models) {
            out("-- ${f.name} (${f.length() shr 20}MB)")
            val c = bench(f.absolutePath, "CPU 4线程", cpu(4), out)
            val g = bench(f.absolutePath, "GPU(QNN)", qnn("libQnnGpu.so", false), out)
            val n = bench(f.absolutePath, "NPU(QNN HTP)", qnn("libQnnHtp.so", false), out)
            val ns = bench(f.absolutePath, "NPU 整图(禁回退)", qnn("libQnnHtp.so", true), out)
            if (g != null) out("  GPU 与 CPU 输出误差 ${relErr(c?.out, g.out)}")
            if (n != null) out("  NPU 与 CPU 输出误差 ${relErr(c?.out, n.out)}")
            fun ms(r: R?) = r?.let { "%.1f".format(it.ms) } ?: "×"
            summary += "${f.name}: CPU ${ms(c)} · GPU ${ms(g)} · NPU ${ms(n)} · NPU整图 ${if (ns != null) "可以" else "不行"}"
        }
        out("== 汇总（平均 ms/次，× = 失败）==")
        summary.forEach(out)
    }

    /** MicMask 正式版 shell 侧的模型目录；有就测这里的（标准名） */
    const val MM_DIR = "/data/local/tmp/micmask-models"
    val MODEL_NAMES = listOf("hubert_dyn8.onnx", "rmvpe_q8.onnx", "atri48_stream_qdq.onnx", "atri48_stream_b50_qdq.onnx", "atri48_stream_fp32.onnx")
}
