package com.taocrypt.micmask.qnntest

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * 三档测速：CPU / QNN-GPU / QNN-HTP(NPU)。App 进程（MainActivity）和 shell 进程（ShellProbe）共用。
 * 每个模型：建会话耗时、首跑耗时、5 次平均、与 CPU 输出的最大相对误差；
 * HTP 另跑一次「禁止回退 CPU」看能不能整图上 NPU。
 * qnntest-4：首轮、稳态 p50/p95（20 次）、与 CPU 输出的 SNR；模型旁有 <模型>.onnx.inputs 时用里面的真实输入；
 * 新档位 htp_ctx（整图 + QNN 上下文缓存）。
 */
object Probe {
    const val VERSION = "qnntest-7c"
    private val env by lazy { OrtEnvironment.getEnvironment() }

    fun header(out: (String) -> Unit, where: String) {
        out("== MicMask QNN 测试 $VERSION · $where ==")
        out("设备 ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        if (android.os.Build.VERSION.SDK_INT >= 31) out("SoC ${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}")
        out("hardware ${android.os.Build.HARDWARE} · board ${android.os.Build.BOARD}")
        out("uid ${android.os.Process.myUid()} · ORT ${runCatching { env.version }.getOrDefault("?")} · 可用 EP ${runCatching { OrtEnvironment.getAvailableProviders() }.getOrNull()}")
        out("ADSP_LIBRARY_PATH=${System.getenv("ADSP_LIBRARY_PATH")}")
        if (android.os.Build.VERSION.SDK_INT >= 31) out("HTP 架构推测 ${htpArch(android.os.Build.SOC_MODEL) ?: "未知"}（qnntest-6 的包只含 V75）")
        listOf("/dev/fastrpc-cdsp", "/dev/fastrpc-cdsp-secure", "/dev/fastrpc-adsp", "/dev/adsprpc-smd", "/dev/kgsl-3d0").forEach { p ->
            val f = File(p); out("  $p 存在=${f.exists()} 可读写=${f.canRead() && f.canWrite()}")
        }
    }

    /**
     * 转换脚本（convert/dec_rewrite.py）在模型旁写的 <模型>.onnx.inputs：按会话输入顺序拼接的原始小端字节
     * （float→fp32，int64→int64）。大小对不上就不用。
     */
    private fun fileInputs(s: OrtSession, model: File?): Map<String, OnnxTensor>? {
        val f = model?.let { File(it.path + ".inputs") } ?: return null
        if (!f.isFile) return null
        val infos = s.inputNames.map { it to (s.inputInfo[it]!!.info as TensorInfo) }
        if (infos.any { (_, ti) -> ti.shape.any { it <= 0 } }) return null
        val need = infos.sumOf { (_, ti) -> ti.shape.fold(1L) { a, b -> a * b } * (if (ti.type == OnnxJavaType.INT64) 8 else 4) }
        if (f.length() != need) return null
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val m = LinkedHashMap<String, OnnxTensor>()
        for ((name, ti) in infos) {
            val n = ti.shape.fold(1L) { a, b -> a * b }.toInt()
            m[name] = if (ti.type == OnnxJavaType.INT64) OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { bb.long }), ti.shape)
            else OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n) { bb.float }), ti.shape)
        }
        return m
    }

    /** 生成输入：按名字和形状猜（流式合成器 phone/pitch/pitchf、hubert source、rmvpe input） */
    private fun inputs(s: OrtSession, model: File? = null): Map<String, OnnxTensor> {
        fileInputs(s, model)?.let { return it }
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

    class R(val ms: Double, val out: FloatArray?, val load: Double = 0.0, val first: Double = 0.0, val p50: Double = 0.0, val p95: Double = 0.0, val realInput: Boolean = false)

    /** qnntest-4 用：首轮 + 3 次预热 + 20 次逐次计时，给 p50/p95。model 用来找 .inputs 文件（ctx 缓存模型传原模型）。 */
    private fun bench4(path: String, model: File, label: String, opts: OrtSession.SessionOptions, out: (String) -> Unit): R? {
        val t0 = System.nanoTime()
        val s = try { env.createSession(path, opts) } catch (t: Throwable) {
            out("  [$label] 建会话失败：${t.javaClass.simpleName}: ${t.message?.lineSequence()?.take(4)?.joinToString(" | ")}"); return null
        }
        val tLoad = (System.nanoTime() - t0) / 1e6
        out("  [$label] 建会话成功 %.0fms".format(tLoad))
        try {
            val fi = fileInputs(s, model)
            val ins = fi ?: inputs(s)
            val usedReal = fi != null
            try {
                val a = System.nanoTime()
                val first = s.run(ins).use { firstOut(it) }
                val tFirst = (System.nanoTime() - a) / 1e6
                repeat(3) { s.run(ins).close() }
                val ts = DoubleArray(20) { val b = System.nanoTime(); s.run(ins).close(); (System.nanoTime() - b) / 1e6 }
                val sorted = ts.sorted()
                val p50 = sorted[sorted.size / 2]; val p95 = sorted[Math.ceil(0.95 * sorted.size).toInt() - 1]
                out("  [$label] 首轮 %.1fms · 稳态 p50 %.1fms · p95 %.1fms · 平均 %.1fms（20 次）· 输入=%s".format(tFirst, p50, p95, ts.average(), if (usedReal) "真实(.inputs)" else "随机"))
                return R(ts.average(), first, tLoad, tFirst, p50, p95, usedReal)
            } finally { ins.values.forEach { it.close() } }
        } catch (t: Throwable) {
            out("  [$label] 运行失败：${t.javaClass.simpleName}: ${t.message?.lineSequence()?.take(4)?.joinToString(" | ")}"); return null
        } finally { s.close() }
    }

    private fun snrDb(ref: FloatArray?, x: FloatArray?): String {
        if (ref == null || x == null || ref.size != x.size) return "无法比较"
        var e = 0.0; var p = 0.0; var bad = 0
        for (i in ref.indices) {
            if (x[i].isNaN() || x[i].isInfinite()) { bad++; continue }
            val d = (ref[i] - x[i]).toDouble(); e += d * d; p += ref[i].toDouble() * ref[i]
        }
        if (bad > 0) return "NaN/Inf×$bad"
        return "%.1fdB".format(10 * Math.log10(p / maxOf(e, 1e-30)))
    }

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

    private fun qnn(backend: String, strict: Boolean, extra: Map<String, String> = emptyMap(), cfg: Map<String, String> = emptyMap()) = cpu(4).apply {
        val m = HashMap<String, String>()
        m["backend_path"] = qnnDir?.let { "$it/$backend" } ?: backend
        m.putAll(extra)
        cfg.forEach { (k, v) -> addConfigEntry(k, v) }
        // fp32 图 + enable_htp_fp16_precision=1：由 HTP 以 fp16 执行（ORT 1.22 QNN EP 支持此选项）
        if (backend.contains("Htp")) { m["htp_performance_mode"] = "burst"; m["enable_htp_fp16_precision"] = "1" }
        addQnn(m)
        if (strict) addConfigEntry("session.disable_cpu_ep_fallback", "1")
        setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
    }

    /** qnntest-7：ORT WebGPU EP（Dawn/Vulkan），同 mobile-rvc；需 -PqnntestWebgpu=true 用通用 onnxruntime-android 构建 */
    private fun webgpu(): OrtSession.SessionOptions {
        env.version  // 先建 OrtEnvironment：否则 addWebGPU 报「DefaultLogger 未注册」
        return cpu(4).apply { addWebGPU(HashMap()); setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO) }
    }
    private fun nnapi(): OrtSession.SessionOptions {
        env.version
        return cpu(4).apply { addNnapi(); setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO) }
    }
    val TIERS7 = listOf("cpu" to "CPU 4线程", "nnapi" to "NNAPI(APU/NPU)", "webgpu" to "GPU(WebGPU/Vulkan)")

    val TIERS = listOf("cpu" to "CPU 4线程", "gpu" to "GPU(QNN)", "htp" to "NPU(QNN HTP)", "htp_strict" to "NPU 整图(禁回退)")
    /** qnntest-4：声码器图 / hubert fp32 图在 HTP 上以 fp16 跑 */
    val TIERS4 = listOf("cpu" to "CPU 4线程", "htp" to "NPU fp16(可回退)", "htp_strict" to "NPU fp16 整图(禁回退)", "htp_ctx" to "NPU fp16 整图+上下文缓存")
    /** qnntest-4 测哪些模型：dec_rewrite.py 的产物和 fp32 hubert */
    fun isQ4Model(name: String) = name.contains("_dec_") || name.contains("_synth_rw") || name.startsWith("hubert_f32")
    private fun tierLabel(t: String) = (TIERS + TIERS4 + TIERS6 + TIERS7).firstOrNull { it.first == t }?.second ?: t

    /** ORT 在安卓把日志写 logcat：抓本进程里 QNN 分区/失败原因相关的行 */
    private fun qnnLogs(since: String, out: (String) -> Unit) {
        runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "tag", "--pid=${android.os.Process.myPid()}", "-T", since).redirectErrorStream(true).start()
            val keys = Regex("(?i)partition|supported|failed|error|fastrpc|transport|backend|op type|validation|qnn|safeint|fp16|precision|context")
            var n = 0
            p.inputStream.bufferedReader().forEachLine { l ->
                if (keys.containsMatchIn(l) && n < 60) { out("    log: " + l.take(260)); n++ }
            }
            p.waitFor()
        }
    }

    /** 单个模型 × 单个档位（每项放在独立进程里跑，崩了只丢这一项）。最后输出 RESULT|模型|档位|平均ms 或 FAIL */
    fun runOne(f: File, tier: String, out: (String) -> Unit) {
        if (isQ4Model(f.name)) { runOne4(f, tier, out); return }
        val label = tierLabel(tier)
        val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val opts = when (tier) {
            "cpu" -> cpu(4); "gpu" -> qnn("libQnnGpu.so", false); "webgpu" -> webgpu(); "nnapi" -> nnapi()
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

    /**
     * qnntest-4：单个模型 × 档位。输出
     *   RESULT|模型|档位|平均ms（兼容旧汇总）
     *   RESULT4|模型|档位|建会话ms|首轮ms|p50|p95|与CPU的SNR|输入
     */
    fun runOne4(f: File, tier: String, out: (String) -> Unit) {
        val label = tierLabel(tier)
        val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        var r: R? = null
        var loadNote = ""
        when (tier) {
            "cpu" -> r = bench4(f.absolutePath, f, label, cpu(4), out)
            "gpu" -> r = bench4(f.absolutePath, f, label, qnn("libQnnGpu.so", false), out)
            "webgpu" -> r = bench4(f.absolutePath, f, label, webgpu(), out)
            "nnapi" -> r = bench4(f.absolutePath, f, label, nnapi(), out)
            "htp" -> r = bench4(f.absolutePath, f, label, qnn("libQnnHtp.so", false), out)
            "htp_strict" -> r = bench4(f.absolutePath, f, label, qnn("libQnnHtp.so", true), out)
            "htp_ctx" -> {
                // 第一步：编译并导出 QNN 上下文（finalization mode 3，同 mobile-rvc）；第二步：从缓存加载再测
                val dir = File(f.parentFile, "ctxcache").also { it.mkdirs() }
                val ctx = File(dir, f.name.removeSuffix(".onnx") + "_htp_ctx.onnx"); ctx.delete()
                val opt = mapOf("htp_graph_finalization_optimization_mode" to "3")
                val t0 = System.nanoTime()
                val ok = try {
                    env.createSession(f.absolutePath, qnn("libQnnHtp.so", true, opt, mapOf("ep.context_enable" to "1", "ep.context_file_path" to ctx.absolutePath, "ep.context_embed_mode" to "1"))).close(); true
                } catch (t: Throwable) { out("  [$label] 生成上下文失败：${t.javaClass.simpleName}: ${t.message?.lineSequence()?.take(4)?.joinToString(" | ")}"); false }
                val tGen = (System.nanoTime() - t0) / 1e6
                if (ok && ctx.isFile) {
                    out("  [$label] 生成上下文 %.0fms（%.1f MB）".format(tGen, ctx.length() / 1e6))
                    loadNote = "生成%.0f".format(tGen)
                    r = bench4(ctx.absolutePath, f, "$label·从缓存加载", qnn("libQnnHtp.so", true, opt), out)
                } else if (ok) out("  [$label] 会话建好了但没写出上下文文件 ${ctx.path}")
            }
            else -> out("  未知档位 $tier")
        }
        if (tier != "cpu") qnnLogs(since, out)
        var snr = "-"
        if (r != null && tier != "cpu") {
            val c = bench4(f.absolutePath, f, "对照 CPU", cpu(4)) {}
            snr = snrDb(c?.out, r.out)
            out("  [$label] 与 CPU 输出 SNR $snr · 最大相对误差 ${relErr(c?.out, r.out)}")
        }
        out("RESULT|${f.name}|$tier|${r?.let { "%.1f".format(it.ms) } ?: "FAIL"}")
        out("RESULT4|${f.name}|$tier|" + (r?.let { "%.0f%s|%.1f|%.1f|%.1f|%s|%s".format(it.load, if (loadNote.isEmpty()) "" else "($loadNote)", it.first, it.p50, it.p95, snr, if (it.realInput) "真实" else "随机") } ?: "FAIL"))
    }

    /** qnntest-4 汇总：每个模型 × 档位一行 */
    fun summary4(lines: List<String>): List<String> {
        val rows = lines.filter { it.startsWith("RESULT4|") }.map { it.split('|') }
        val crash = lines.filter { it.startsWith("RESULT|") }.map { it.split('|') }.filter { it.size >= 4 && (it[3] == "CRASH" || it[3] == "TIMEOUT") }
        return listOf("== $VERSION 汇总（建会话ms|首轮ms|p50|p95|与CPU SNR|输入）==") +
            rows.map { p -> "${p[1]} · ${tierLabel(p[2])}: " + p.drop(3).joinToString(" | ") } +
            crash.map { p -> "${p[1]} · ${tierLabel(p[2])}: ${p[3]}" }
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

    // ---------------- qnntest-6：QNN 库不进 APK，按需下载到外部目录后加载 ----------------

    /** SoC → HTP 架构（stub/skel 版本） */
    fun htpArch(soc: String?): String? = when (soc?.uppercase()) {
        "SM8550", "SM8550P" -> "V73"; "SM8650", "SM8650P" -> "V75"; "SM8750", "SM8750P" -> "V79"; "SM8850", "SM8850P" -> "V81"
        "SM7675", "SM7635" -> "V73"; "SM8635" -> "V75"; else -> null
    }

    /** 非空时 backend_path 用这个目录下的绝对路径 */
    @Volatile var qnnDir: String? = null

    /**
     * 从 dir 预加载 QNN 库（按绝对路径 System.load），这样 libQnnHtp 之后按 soname dlopen stub/prepare 时能命中已加载的库；
     * 再把 dir 放到 ADSP_LIBRARY_PATH 最前面，DSP 侧按它找 skel。
     */
    fun useQnnDir(dir: String, out: (String) -> Unit): Boolean {
        val d = File(dir)
        if (!File(d, "libQnnHtp.so").isFile) { out("  QNN 目录 $dir 里没有 libQnnHtp.so"); return false }
        qnnDir = d.absolutePath
        runCatching { android.system.Os.setenv("ADSP_LIBRARY_PATH", "${d.absolutePath};/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true) }
        val stubs = d.listFiles()?.map { it.name }?.filter { Regex("libQnnHtpV\\d+Stub\\.so").matches(it) }?.sorted() ?: emptyList()
        val order = listOf("libQnnSystem.so") + stubs + listOf("libQnnHtp.so", "libQnnHtpPrepare.so")
        val loaded = ArrayList<String>()
        for (n in order) {
            val f = File(d, n)
            if (!f.isFile) { out("  预加载 $n：目录里没有"); continue }
            try { System.load(f.absolutePath); loaded += n } catch (t: Throwable) { out("  预加载 $n 失败：${t.javaClass.simpleName}: ${t.message?.take(200)}") }
        }
        out("  QNN 目录 ${d.absolutePath} · 预加载成功 ${loaded.joinToString()}")
        return true
    }

    /** 从 /proc/self/maps 看实际映射进来的 QNN 库及其路径 */
    private fun mapsQnn(out: (String) -> Unit) {
        val libs = runCatching { File("/proc/self/maps").readLines().mapNotNull { l -> l.substringAfter(" /", "").takeIf { it.contains("libQnn") }?.let { "/$it".trim() } }.distinct() }.getOrDefault(emptyList())
        out("  已映射的 QNN 库：" + (if (libs.isEmpty()) "无" else libs.joinToString("、")))
    }

    val TIERS6 = listOf("cpu" to "CPU 4线程", "x_htp" to "外置QNN·NPU fp16 整图", "x_ctx" to "外置QNN·整图+上下文缓存", "x_noprep" to "外置QNN·无编译器·仅缓存")

    /**
     * qnntest-6：单个模型 × 档位。dir = 完整 QNN 包目录；noprepDir = 同一个包去掉 libQnnHtpPrepare.so。
     * x_ctx 生成 ctxcache/<模型>_x_ctx.onnx；x_noprep 只从这个缓存加载（模拟编译器下载用完即删）。
     */
    fun runOne6(f: File, tier: String, dir: String?, noprepDir: String?, out: (String) -> Unit) {
        val label = tierLabel(tier)
        val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        var r: R? = null
        var loadNote = ""
        val ctx = File(File(f.parentFile, "ctxcache").also { it.mkdirs() }, f.name.removeSuffix(".onnx") + "_x_ctx.onnx")
        val opt = mapOf("htp_graph_finalization_optimization_mode" to "3")
        if (tier != "cpu") {
            val use = if (tier == "x_noprep") noprepDir else dir
            if (use == null || !useQnnDir(use, out)) { out("RESULT|${f.name}|$tier|FAIL"); out("RESULT4|${f.name}|$tier|FAIL"); return }
        }
        when (tier) {
            "cpu" -> r = bench4(f.absolutePath, f, label, cpu(4), out)
            "x_htp" -> r = bench4(f.absolutePath, f, label, qnn("libQnnHtp.so", true), out)
            "x_ctx" -> {
                ctx.delete()
                val t0 = System.nanoTime()
                val ok = try {
                    env.createSession(f.absolutePath, qnn("libQnnHtp.so", true, opt, mapOf("ep.context_enable" to "1", "ep.context_file_path" to ctx.absolutePath, "ep.context_embed_mode" to "1"))).close(); true
                } catch (t: Throwable) { out("  [$label] 生成上下文失败：${t.javaClass.simpleName}: ${t.message?.lineSequence()?.take(4)?.joinToString(" | ")}"); false }
                val tGen = (System.nanoTime() - t0) / 1e6
                if (ok && ctx.isFile) {
                    out("  [$label] 生成上下文 %.0fms（%.1f MB）".format(tGen, ctx.length() / 1e6))
                    loadNote = "生成%.0f".format(tGen)
                    r = bench4(ctx.absolutePath, f, "$label·从缓存加载", qnn("libQnnHtp.so", true, opt), out)
                } else if (ok) out("  [$label] 会话建好了但没写出上下文文件 ${ctx.path}")
            }
            "x_noprep" -> {
                if (!ctx.isFile) out("  [$label] 没有上下文缓存 ${ctx.path}（先跑 x_ctx）")
                else r = bench4(ctx.absolutePath, f, label, qnn("libQnnHtp.so", true, opt), out)
            }
            else -> out("  未知档位 $tier")
        }
        if (tier != "cpu") { mapsQnn(out); qnnLogs(since, out) }
        var snr = "-"
        if (r != null && tier != "cpu") {
            val c = bench4(f.absolutePath, f, "对照 CPU", cpu(4)) {}
            snr = snrDb(c?.out, r.out)
            out("  [$label] 与 CPU 输出 SNR $snr · 最大相对误差 ${relErr(c?.out, r.out)}")
        }
        out("RESULT|${f.name}|$tier|${r?.let { "%.1f".format(it.ms) } ?: "FAIL"}")
        out("RESULT4|${f.name}|$tier|" + (r?.let { "%.0f%s|%.1f|%.1f|%.1f|%s|%s".format(it.load, if (loadNote.isEmpty()) "" else "($loadNote)", it.first, it.p50, it.p95, snr, if (it.realInput) "真实" else "随机") } ?: "FAIL"))
    }

    /** MicMask 正式版 shell 侧的模型目录；有就测这里的（标准名） */
    const val MM_DIR = "/data/local/tmp/micmask-models"
    val MODEL_NAMES = listOf("hubert_f32_static.onnx", "hubert_qnn_a16w8.onnx", "synth_qnn_a16w8.onnx", "synth_f32_static.onnx", "hubert_dyn8.onnx", "rmvpe_q8.onnx", "atri48_stream_qdq.onnx", "atri48_stream_b50_qdq.onnx", "atri48_stream_fp32.onnx")
}
