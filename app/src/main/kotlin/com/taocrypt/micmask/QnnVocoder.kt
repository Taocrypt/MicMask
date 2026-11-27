package com.taocrypt.micmask

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import micmask.core.VocoderSplit
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 骁龙 NPU（QNN HTP）声码器：与 NEON 声码器共用拆分产物（前半图 + MMVOC16 权重），
 * 把 MMVOC16 里的 NSF-HiFiGAN dec 写成 HTP 能整图接受的 fp32 ONNX（固定形状），由 HTP 以 fp16 执行。
 *
 * 改写（做法移植自 nasuiyile/mobile-rvc，MIT）：
 *  - 谐波源上的 noise_convs（单输入通道、kernel=2s、stride=s）→ 在 CPU 上把 har 按 s 做多相分解，
 *    图里变成 s 个输入通道上的 3 抽头普通卷积，W'[c,p,j] = W[c,(j-1)s+p+s/2]（越界为 0）；
 *  - 48k 重采样尾巴不进图，在 CPU 上做多相 FIR（与 NEON 声码器同一实现）。
 * 首次为某音色 + 块长生成 QNN 上下文缓存（约 5–9 秒），之后从缓存加载（约 0.2 秒）。
 * 首次还会与 fp32 原生声码器比对输出，SNR 不够就判为不可用（回退）。
 *
 * 判定稳定性（2.1.1-alpha.9）：
 *  - 校验输入改为固定种子的多组样本（调用方给），SNR 按总能量合并，同一设备 + 模型 + QNN 版本结果可复现；
 *    以前用随机输入单次比对，同一音色在阈值附近时每次判定可能不同（实测 17.4 dB 被永久判死）。
 *  - 滞回：≥ [CHECK_SNR_DB] 启用，< [FAIL_SNR_DB] 才禁用；中间灰区沿用该缓存之前的结论，没有之前结论按不启用。
 *  - 结论写进 .json（rule=[RULE]），同一进程内同一缓存只判一次，不来回切。
 *  - 旧规则（无 rule 字段）落在灰区的「不可用」结论按新规则重判一次（需重新生成上下文，约 5–9 秒，只此一次）。
 */
object QnnVocoder {
    private const val CHECK_SNR_DB = 20.0
    private const val FAIL_SNR_DB = 15.0
    private const val RULE = 2

    /** 本进程内已下的结论（缓存路径 → 是否可用），同一会话不重判 */
    private val verdicts = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 滞回判定：≥20 启用，<15 禁用，灰区沿用 prev（没有按不启用） */
    internal fun verdict(snr: Double, prev: Boolean?): Boolean = when {
        snr >= CHECK_SNR_DB -> true
        snr < FAIL_SNR_DB -> false
        else -> prev ?: false
    }

    @Volatile private var loadedDir: String? = null

    /** 预加载 QNN 库并设置 DSP 搜索路径；同进程只做一次 */
    @Synchronized fun init(dir: File): Boolean {
        if (loadedDir == dir.absolutePath) return true
        if (loadedDir != null) { L.w("QNN 已从 $loadedDir 加载，忽略 ${dir.path}"); return true }
        runCatching { android.system.Os.setenv("ADSP_LIBRARY_PATH", "${dir.absolutePath};/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true) }
        // Stub 依赖厂商库 libcdsprpc.so：App 进程靠清单 uses-native-library 放行；shell(app_process) 进程的命名空间里找不到它时，
        // 依次试 loadLibrary、绝对路径、复制到组件目录再加载（一加 SM8750 / Android 17 实测 shell 里 not found）
        if (runCatching { System.loadLibrary("cdsprpc") }.isFailure) {
            val sys = listOf("/vendor/lib64/libcdsprpc.so", "/system/vendor/lib64/libcdsprpc.so", "/odm/lib64/libcdsprpc.so").map(::File).firstOrNull { it.isFile }
            val e1 = sys?.let { f -> runCatching { System.load(f.absolutePath) }.exceptionOrNull() }
            if (sys == null) L.w("QNN：找不到 libcdsprpc.so")
            else if (e1 != null) {
                val cp = File(dir, "libcdsprpc.so")
                val e2 = runCatching { if (!cp.isFile || cp.length() != sys.length()) sys.copyTo(cp, true); System.load(cp.absolutePath) }.exceptionOrNull()
                if (e2 != null) L.w("QNN：加载 libcdsprpc.so 失败（${e1.message}；复制后：${e2.message}）")
                else L.i("QNN：libcdsprpc.so 已从组件目录加载（直接加载失败：${e1.message}）")
            } else L.i("QNN：libcdsprpc.so 已从 ${sys.path} 加载")
        }
        val stubs = dir.listFiles()?.map { it.name }?.filter { Regex("libQnnHtpV\\d+Stub\\.so").matches(it) }.orEmpty()
        for (n in listOf("libQnnSystem.so") + stubs + listOf("libQnnHtp.so", "libQnnHtpPrepare.so")) {
            val f = File(dir, n)
            if (!f.isFile) continue
            try { System.load(f.absolutePath) } catch (t: Throwable) { L.e("QNN 预加载 $n 失败", t); return false }
        }
        loadedDir = dir.absolutePath
        L.i("QNN 库已加载：${dir.path}")
        return true
    }

    // ------------------------------------------------------------ MMVOC16 解析

    class Conv(val w: FloatArray, val dims: LongArray, val b: FloatArray?, val k: Int, val pad: Int, val stride: Int = 1, val dil: Int = 1)
    class Stage(val rate: Int, val up: Conv, val noise: Conv, val noiseS: Int, val res: List<List<Conv>>)
    class Spec(
        val cin: Int, val c0: Int, val nk: Int, val rb1: Boolean, val upp: Int,
        val pre: Conv, val stages: List<Stage>, val post: Conv,
        val up: Int, val dn: Int, val off: Int, val perFrame: Int, val h: FloatArray,
    )

    fun parse(blob: File): Spec {
        val b = ByteBuffer.wrap(blob.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8).also { b.get(it) }
        require(String(magic, Charsets.ISO_8859_1) == "MMVOC16\u0000") { "不是 MMVOC16" }
        require(b.int == VocoderSplit.FORMAT) { "MMVOC16 版本不符" }
        fun i() = b.int
        fun f16(n: Int) = FloatArray(n) { VocoderSplit.halfToFloat(b.short.toInt() and 0xffff) }
        fun f32(n: Int) = FloatArray(n) { b.float }
        val cin = i(); val c0 = i(); val ns = i(); val nk = i(); val rb1 = i() == 1; val upp = i()
        val pk = i(); val ppad = i()
        val pre = Conv(f16(c0 * cin * pk), longArrayOf(c0.toLong(), cin.toLong(), pk.toLong()), f32(c0), pk, ppad)
        val stages = ArrayList<Stage>()
        var cumul = 1
        for (s in 0 until ns) {
            val rate = i(); val k = i(); val pad = i(); val ci = i(); val co = i()
            val up = Conv(f16(ci * co * k), longArrayOf(ci.toLong(), co.toLong(), k.toLong()), f32(co), k, pad, rate)
            val kn = i(); val st = i(); val np = i()
            val noise = Conv(f16(co * kn), longArrayOf(co.toLong(), 1, kn.toLong()), f32(co), kn, np, st)
            val res = ArrayList<List<Conv>>()
            for (j in 0 until nk) {
                val nc = i()
                res += (0 until nc).map {
                    val kk = i(); val dil = i(); val pp = i()
                    Conv(f16(co * co * kk), longArrayOf(co.toLong(), co.toLong(), kk.toLong()), f32(co), kk, pp, 1, dil)
                }
            }
            cumul *= rate
            stages += Stage(rate, up, noise, st, res)
        }
        val ch = stages.last().up.dims[1].toInt()
        val qk = i(); val qp = i(); val hasB = i() == 1
        val post = Conv(f16(ch * qk), longArrayOf(1, ch.toLong(), qk.toLong()), null, qk, qp).let { c -> if (hasB) Conv(c.w, c.dims, f32(1), c.k, c.pad) else c }
        val up = i(); val dn = i(); val taps = i(); val off = i(); val per = i(); val h = f32(taps)
        return Spec(cin, c0, nk, rb1, upp, pre, stages, post, up, dn, off, per, h)
    }

    // ------------------------------------------------------------ ONNX 写出（手写 protobuf，流式）

    private sealed class P {
        abstract fun size(): Long
        abstract fun write(o: DataOutputStream)
    }
    private class Varint(val num: Int, val v: Long) : P() {
        override fun size() = vlen(tag(num, 0)) + vlen(v)
        override fun write(o: DataOutputStream) { wv(o, tag(num, 0)); wv(o, v) }
    }
    private class Fixed32(val num: Int, val v: Float) : P() {
        override fun size() = vlen(tag(num, 5)) + 4L
        override fun write(o: DataOutputStream) { wv(o, tag(num, 5)); o.writeInt(Integer.reverseBytes(java.lang.Float.floatToRawIntBits(v))) }
    }
    private class Bytes(val num: Int, val b: ByteArray) : P() {
        override fun size() = vlen(tag(num, 2)) + vlen(b.size.toLong()) + b.size
        override fun write(o: DataOutputStream) { wv(o, tag(num, 2)); wv(o, b.size.toLong()); o.write(b) }
    }
    private class Floats(val num: Int, val f: FloatArray) : P() {
        override fun size() = vlen(tag(num, 2)) + vlen(f.size * 4L) + f.size * 4L
        override fun write(o: DataOutputStream) {
            wv(o, tag(num, 2)); wv(o, f.size * 4L)
            val bb = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            for (x in f) { if (!bb.hasRemaining()) { o.write(bb.array(), 0, bb.position()); bb.clear() }; bb.putFloat(x) }
            o.write(bb.array(), 0, bb.position())
        }
    }
    private class Msg(val num: Int, val parts: List<P>) : P() {
        private var cached = -1L
        fun body(): Long { if (cached < 0) cached = parts.sumOf { it.size() }; return cached }
        override fun size() = vlen(tag(num, 2)) + vlen(body()) + body()
        override fun write(o: DataOutputStream) { wv(o, tag(num, 2)); wv(o, body()); parts.forEach { it.write(o) } }
    }
    private fun tag(num: Int, w: Int) = ((num shl 3) or w).toLong()
    private fun vlen(v: Long): Long { var x = v; var n = 1L; while (x and 0x7fL.inv() != 0L) { x = x ushr 7; n++ }; return n }
    private fun wv(o: DataOutputStream, v: Long) { var x = v; while (x and 0x7fL.inv() != 0L) { o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()) }
    private fun s(num: Int, v: String) = Bytes(num, v.toByteArray(Charsets.UTF_8))

    private class G {
        val nodes = ArrayList<P>(); val inits = ArrayList<P>(); val ins = ArrayList<P>(); val outs = ArrayList<P>()
        var n = 0
        fun t(p: String) = "${p}_${n++}"
        fun init(name: String, dims: LongArray, f: FloatArray): String {
            inits += Msg(5, dims.map { Varint(1, it) } + listOf(Varint(2, 1), s(8, name), Floats(9, f))); return name
        }
        fun vi(num: Int, name: String, dims: LongArray) = Msg(num, listOf(s(1, name), Msg(2, listOf(Msg(1, listOf(Varint(1, 1), Msg(2, dims.map { Msg(1, listOf(Varint(1, it)))})))))))
        fun attrI(name: String, v: Long) = Msg(5, listOf(s(1, name), Varint(3, v), Varint(20, 2)))
        fun attrF(name: String, v: Float) = Msg(5, listOf(s(1, name), Fixed32(2, v), Varint(20, 1)))
        fun attrIs(name: String, v: List<Long>) = Msg(5, listOf(s(1, name)) + v.map { Varint(8, it) } + listOf(Varint(20, 7)))
        fun node(op: String, inputs: List<String>, attrs: List<P> = emptyList()): String {
            val out = t(op.lowercase())
            nodes += Msg(1, inputs.map { s(1, it) } + listOf(s(2, out), s(3, out), s(4, op)) + attrs); return out
        }
        fun conv(x: String, c: Conv, pfx: String, transpose: Boolean = false): String {
            val w = init("${pfx}_w", c.dims, c.w)
            val ins = mutableListOf(x, w); c.b?.let { ins += init("${pfx}_b", longArrayOf(it.size.toLong()), it) }
            val a = mutableListOf(attrIs("kernel_shape", listOf(c.k.toLong())), attrIs("pads", listOf(c.pad.toLong(), c.pad.toLong())), attrIs("strides", listOf(c.stride.toLong())))
            if (!transpose) a += attrIs("dilations", listOf(c.dil.toLong()))
            return node(if (transpose) "ConvTranspose" else "Conv", ins, a)
        }
        fun lrelu(x: String, a: Float) = node("LeakyRelu", listOf(x), listOf(attrF("alpha", a)))
    }

    /** 多相改写后的 noise_conv 权重 [co][s][3] */
    private fun noiseW(c: Conv, s: Int): FloatArray {
        val co = c.dims[0].toInt(); val k = c.k
        val w = FloatArray(co * s * 3)
        for (ch in 0 until co) for (p in 0 until s) for (j in 0 until 3) {
            val kk = (j - 1) * s + p + s / 2
            if (kk in 0 until k) w[(ch * s + p) * 3 + j] = c.w[ch * k + kk]
        }
        return w
    }

    /** 写 dec 图：输入 z[1,cin,T]、(cond[1,c0,1])、h<i>[1,s_i,T·∏rate]；输出 y[1,1,T·upp]（tanh 之后，不含 48k 尾巴） */
    fun writeGraph(sp: Spec, t: Int, hasCond: Boolean, out: File) {
        val g = G()
        g.ins += g.vi(11, "z", longArrayOf(1, sp.cin.toLong(), t.toLong()))
        var x = g.conv("z", sp.pre, "pre")
        if (hasCond) { g.ins += g.vi(11, "cond", longArrayOf(1, sp.c0.toLong(), 1)); x = g.node("Add", listOf(x, "cond")) }
        var len = t
        val invNk = g.init("inv_nk", LongArray(0), floatArrayOf(1f / sp.nk))
        for ((i, st) in sp.stages.withIndex()) {
            len *= st.rate
            val co = st.up.dims[1]
            val u = g.conv(g.lrelu(x, 0.1f), st.up, "up$i", transpose = true)
            val hn = "h$i"
            g.ins += g.vi(11, hn, longArrayOf(1, st.noiseS.toLong(), len.toLong()))
            val nc = Conv(noiseW(st.noise, st.noiseS), longArrayOf(co, st.noiseS.toLong(), 3), st.noise.b, 3, 1)
            val xn = g.node("Add", listOf(u, g.conv(hn, nc, "noise$i")))
            val a1 = g.lrelu(xn, 0.1f)
            var xs: String? = null
            for ((j, convs) in st.res.withIndex()) {
                var r = xn
                if (sp.rb1) {
                    var m = 0
                    while (m + 1 < convs.size) {
                        val a = if (m == 0) a1 else g.lrelu(r, 0.1f)
                        val c1 = g.conv(a, convs[m], "r${i}_${j}_$m")
                        val c2 = g.conv(g.lrelu(c1, 0.1f), convs[m + 1], "r${i}_${j}_${m + 1}")
                        r = g.node("Add", listOf(c2, r)); m += 2
                    }
                } else for ((m, c) in convs.withIndex()) {
                    val a = if (m == 0) a1 else g.lrelu(r, 0.1f)
                    r = g.node("Add", listOf(g.conv(a, c, "r${i}_${j}_$m"), r))
                }
                xs = if (xs == null) r else g.node("Add", listOf(xs, r))
            }
            x = if (sp.nk == 1) xs!! else g.node("Mul", listOf(xs!!, invNk))
        }
        val y = g.node("Tanh", listOf(g.conv(g.lrelu(x, 0.01f), sp.post, "post")))
        val yo = g.node("Identity", listOf(y))
        // 把最后一个节点的输出名改成 "y"：直接再加一个 Identity 命名 y
        g.nodes += Msg(1, listOf(s(1, yo), s(2, "y"), s(3, "y_out"), s(4, "Identity")))
        g.outs += g.vi(12, "y", longArrayOf(1, 1, (t * sp.upp).toLong()))
        val graph = Msg(7, g.nodes + listOf(s(2, "micmask_dec")) + g.inits + g.ins + g.outs)
        val model = listOf(Varint(1, 8), s(2, "micmask"), Msg(8, listOf(s(1, ""), Varint(2, 17))), graph)
        val tmp = File(out.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered(1 shl 20)).use { o -> model.forEach { it.write(o) } }
        out.delete(); require(tmp.renameTo(out)) { "写 ${out.name} 失败" }
    }

    // ------------------------------------------------------------ 运行

    class Dec internal constructor(
        private val env: OrtEnvironment, private val s: OrtSession, private val sp: Spec, val t: Int, private val hasCond: Boolean,
        val desc: String,
    ) : java.io.Closeable {
        private val lens = IntArray(sp.stages.size).also { var l = t; for ((i, st) in sp.stages.withIndex()) { l *= st.rate; it[i] = l } }
        private val poly = Array(sp.stages.size) { i -> FloatArray(sp.stages[i].noiseS * lens[i]) }

        /** 与 NeonVocoder.Dec.run 同样的接口：z[C*T] har[T*upp] cond[c0]? → 48k 波形 [T*480] */
        @Synchronized fun run(z: FloatArray, tt: Int, har: FloatArray, cond: FloatArray?): FloatArray {
            require(tt == t) { "块长 $tt 与 NPU 图 $t 不符" }
            val ins = LinkedHashMap<String, OnnxTensor>()
            try {
                ins["z"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(z), longArrayOf(1, sp.cin.toLong(), t.toLong()))
                if (hasCond) ins["cond"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(cond ?: FloatArray(sp.c0)), longArrayOf(1, sp.c0.toLong(), 1))
                for ((i, st) in sp.stages.withIndex()) {
                    val sN = st.noiseS; val lo = lens[i]; val p = poly[i]
                    for (m in 0 until lo) { val base = m * sN; for (ph in 0 until sN) p[ph * lo + m] = har[base + ph] }
                    ins["h$i"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(p), longArrayOf(1, sN.toLong(), lo.toLong()))
                }
                s.run(ins).use { res ->
                    val fb = (res.get(0) as OnnxTensor).floatBuffer
                    val y = FloatArray(fb.remaining()); fb.get(y)
                    return tail(y)
                }
            } finally { ins.values.forEach { runCatching { it.close() } } }
        }

        /** 48k 尾巴（ConvTranspose(stride=up) + Slice(off::dn)），与原生声码器同一写法 */
        private fun tail(o: FloatArray): FloatArray {
            val nout = t * sp.perFrame
            if (sp.up == sp.dn) return if (o.size == nout) o else o.copyOf(nout)
            val taps = sp.h.size; val h = sp.h; val up = sp.up.toLong(); val L = o.size
            val out = FloatArray(nout)
            for (j in 0 until nout) {
                val pos = sp.off + j.toLong() * sp.dn
                val mlo = if (pos - taps + 1 <= 0) 0L else (pos - taps + 1 + up - 1) / up
                val mhi = minOf(L - 1L, pos / up)
                var acc = 0f
                var m = mlo
                while (m <= mhi) { acc += o[m.toInt()] * h[(pos - m * up).toInt()]; m++ }
                out[j] = acc
            }
            return out
        }

        override fun close() { runCatching { s.close() } }
    }

    private fun qnnOpts(dir: File, gen: File?): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(1); setInterOpNumThreads(1)
        addQnn(mapOf(
            "backend_path" to File(dir, "libQnnHtp.so").absolutePath,
            "htp_performance_mode" to "burst",
            "enable_htp_fp16_precision" to "1",
            "htp_graph_finalization_optimization_mode" to "3",
        ))
        addConfigEntry("session.disable_cpu_ep_fallback", "1")
        if (gen != null) {
            addConfigEntry("ep.context_enable", "1")
            addConfigEntry("ep.context_file_path", gen.absolutePath)
            addConfigEntry("ep.context_embed_mode", "1")
        }
    }

    /**
     * 为拆分产物准备 NPU 声码器。t = 前半图 z 的帧数（块长相关）。
     * 缓存：<拆分目录>/<stem>.qnn<T>_<架构>.ctx.onnx；校验结论 .qnn<T>_<架构>.json（不通过就不再试）。
     * ref = fp32 参考实现（原生声码器），用于首次校验；为 null 时跳过校验。
     */
    fun open(env: OrtEnvironment, dir: File, split: NeonVocoder.Split, t: Int, samples: List<Triple<FloatArray, FloatArray, FloatArray?>>?,
             ref: ((FloatArray, Int, FloatArray, FloatArray?) -> FloatArray)?): Dec? {
        val arch = QnnPack.deviceArch() ?: return null
        val stem = split.blob.name.removeSuffix(".mmvoc")
        val base = File(split.blob.parentFile, "$stem.qnn${t}_${arch}_${QnnPack.VERSION}")
        val ctx = File(base.path + ".ctx.onnx"); val meta = File(base.path + ".json")
        val mj = if (meta.isFile) runCatching { org.json.JSONObject(meta.readText()) }.getOrNull() else null
        val prevOk: Boolean? = mj?.optBoolean("ok", true)
        val prevSnr: Double? = mj?.let { j -> j.optDouble("snr").takeIf { !it.isNaN() }
            ?: Regex("SNR (-?[0-9.]+) dB").find(j.optString("why"))?.groupValues?.get(1)?.toDoubleOrNull() }
        // 旧规则（随机输入单次比对）落在灰区的「不可用」：按新规则重判一次
        val recheck = mj != null && prevOk == false && mj.optInt("rule", 1) < RULE && prevSnr != null && prevSnr >= FAIL_SNR_DB
        val sessionNo = verdicts[base.path] == false
        if (sessionNo || (prevOk == false && !recheck)) {
            L.w("NPU 声码器：之前判定不可用（${mj?.optString("why") ?: "本次运行已判定"}）"); return null
        }
        if (recheck) L.i("NPU 声码器：旧结论 SNR ${"%.1f".format(prevSnr)} dB 在灰区（${FAIL_SNR_DB}–${CHECK_SNR_DB}），按新规则（固定样本 + 滞回）重判一次")
        val sp = parse(split.blob)
        val hasCond = split.cond != null
        val t0 = System.nanoTime()
        var genMs = 0L
        if (!ctx.isFile || ctx.length() == 0L) {
            val g = File(base.path + ".dec.onnx")
            try {
                writeGraph(sp, t, hasCond, g)
                env.createSession(g.absolutePath, qnnOpts(dir, ctx)).close()
            } catch (e: Throwable) {
                ctx.delete()
                L.e("NPU 声码器：生成 QNN 上下文失败", e)
                meta.writeText(org.json.JSONObject().put("ok", false).put("why", "生成失败：${e.message?.take(160)}").toString())
                return null
            } finally { g.delete() }
            genMs = (System.nanoTime() - t0) / 1_000_000
            L.i("NPU 声码器：已生成 QNN 上下文 ${ctx.name}（${ctx.length() shr 10} KB，${genMs}ms）")
        }
        val t1 = System.nanoTime()
        val s = try { env.createSession(ctx.absolutePath, qnnOpts(dir, null)) } catch (e: Throwable) {
            L.e("NPU 声码器：从缓存加载失败，删缓存下次重建", e); ctx.delete(); return null
        }
        val loadMs = (System.nanoTime() - t1) / 1_000_000
        val dec = Dec(env, s, sp, t, hasCond, "QNN HTP fp16 $arch · T=$t · 加载 ${loadMs}ms${if (genMs > 0) " · 首次编译 ${genMs}ms" else ""}")
        if (!samples.isNullOrEmpty() && ref != null && (!meta.isFile || recheck) && verdicts[base.path] == null) {
            val each = ArrayList<String>()
            val snr = runCatching {
                var sig = 0.0; var err = 0.0
                for ((z, har, cond) in samples) {
                    val a = ref(z, t, har, cond); val b = dec.run(z, t, har, cond)
                    var s1 = 0.0; var e1 = 0.0
                    for (i in 0 until minOf(a.size, b.size)) { val d = (a[i] - b[i]).toDouble(); s1 += a[i].toDouble() * a[i]; e1 += d * d }
                    each += "%.1f".format(10 * kotlin.math.log10(s1 / maxOf(e1, 1e-30)))
                    sig += s1; err += e1
                }
                10 * kotlin.math.log10(sig / maxOf(err, 1e-30))
            }.onFailure { L.e("NPU 声码器校验出错", it) }.getOrNull()
            if (snr == null || snr.isNaN()) { dec.close(); return null }   // 出错不记结论
            // 重判旧灰区结论时不拿旧的「不可用」当滞回前态（那是随机输入下的结论），只按新阈值判
            val ok = verdict(snr, if (recheck) null else prevOk)
            val why = "与 fp32 原生声码器 SNR ${"%.1f".format(snr)} dB（${samples.size} 组固定样本 ${each.joinToString("/")}；启用 ≥$CHECK_SNR_DB，禁用 <$FAIL_SNR_DB）"
            L.i("NPU 声码器校验：$why → ${if (ok) "通过" else "不通过"}")
            meta.writeText(org.json.JSONObject().put("ok", ok).put("why", why).put("snr", snr).put("rule", RULE)
                .put("arch", arch).put("qnn", QnnPack.VERSION).toString())
            verdicts[base.path] = ok
            if (!ok) { dec.close(); ctx.delete(); return null }
        }
        verdicts.putIfAbsent(base.path, true)
        return dec
    }
}
