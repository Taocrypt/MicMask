package micmask.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 手机上直接导入 RVC .pth（借鉴 mobile-rvc 的思路，代码自写）。
 *
 * 做法：电脑上用随机权重为每种结构（v1/v2 × 32k/40k/48k × 标准/省电档）各导出一份「模板」——
 *   <k>_f32.onnx（无权重的 fp32 图）、<k>_qdq.onnx（无权重与量化参数的 QDQ 图）、<k>.plan（每个初始化器的算法）。
 * 手机端：解析 .pth（PyTorch zip + pickle）→ 按配方算出 fp32 权重 → 拼成 fp32 模型并带上中间张量输出 →
 * 用几段内置人声特征校准，得到每个激活的 min/max → 算出 int8 权重、int32 偏置、uint8 激活量化参数 → 拼成 QDQ 模型。
 *
 * 拼模型不需要 protobuf 库：ONNX 是 protobuf，两个序列化后的 ModelProto 直接首尾相接，解析时会按规则合并
 * （单个子消息 graph 合并、repeated 的 initializer / output 追加），所以模板字节后面逐个追加只含一个初始化器的 ModelProto 即可。
 */
object PthImport {

    // ======================================================================= .pth（PyTorch zip）解析

    class Storage(val type: String, val key: String)
    class Tensor(val storage: Storage, val offset: Long, val shape: IntArray, val stride: IntArray) {
        val numel get() = shape.fold(1) { a, b -> a * b }
    }
    private class Global(val module: String, val name: String)
    private class Reduced(val g: Global, val args: Any?)
    private object Mark

    class Pth(private val zip: MiniZip, private val prefix: String, val root: Any?) : AutoCloseable {
        @Suppress("UNCHECKED_CAST")
        val dict get() = root as? Map<String, Any?> ?: throw IllegalArgumentException("不是 RVC 模型（根对象不是字典）")
        @Suppress("UNCHECKED_CAST")
        val weight: Map<String, Tensor> get() = (dict["weight"] as? Map<String, Any?>)?.mapNotNull { (k, v) -> (v as? Tensor)?.let { k to it } }?.toMap()
            ?: throw IllegalArgumentException("没有 weight（不是 RVC 推理模型，可能是训练存档 G_xxx.pth）")
        private val cache = HashMap<String, ByteArray>()

        fun floats(t: Tensor): FloatArray {
            val raw = cache.getOrPut(t.storage.key) {
                val e = zip.find("${prefix}data/${t.storage.key}") ?: throw IllegalArgumentException("缺少张量数据 ${t.storage.key}")
                zip.read(e)
            }
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val es = when (t.storage.type) { "HalfStorage", "BFloat16Storage" -> 2; "FloatStorage" -> 4; "DoubleStorage" -> 8
                else -> throw IllegalArgumentException("不支持的张量类型 ${t.storage.type}") }
            val n = t.numel; val out = FloatArray(n)
            val idx = IntArray(t.shape.size)
            for (i in 0 until n) {
                var off = t.offset
                for (d in idx.indices) off += idx[d].toLong() * t.stride[d]
                val p = (off * es).toInt()
                out[i] = when (t.storage.type) {
                    "HalfStorage" -> halfToFloat(bb.getShort(p))
                    "BFloat16Storage" -> java.lang.Float.intBitsToFloat((bb.getShort(p).toInt() and 0xffff) shl 16)
                    "FloatStorage" -> bb.getFloat(p)
                    else -> bb.getDouble(p).toFloat()
                }
                var d = idx.size - 1
                while (d >= 0) { idx[d]++; if (idx[d] < t.shape[d]) break; idx[d] = 0; d-- }
            }
            return out
        }
        override fun close() = zip.close()
    }

    fun openPth(f: File): Pth {
        val head = f.inputStream().use { i -> ByteArray(2).also { i.read(it) } }
        if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte())
            throw IllegalArgumentException("不是新版 PyTorch 格式（旧版 .pth 需要先在电脑上用新版 torch 重新保存）")
        val zip = MiniZip(f)
        val pkl = zip.entries.firstOrNull { it.name.endsWith("data.pkl") } ?: run { zip.close(); throw IllegalArgumentException("压缩包里没有 data.pkl") }
        val prefix = pkl.name.removeSuffix("data.pkl")
        val root = runCatching { unpickle(zip.read(pkl)) }.getOrElse { zip.close(); throw it }
        return Pth(zip, prefix, root)
    }

    private fun halfToFloat(h: Short): Float {
        val x = h.toInt() and 0xffff
        val s = x ushr 15; val e = (x ushr 10) and 0x1f; val m = x and 0x3ff
        val v = when (e) {
            0 -> if (m == 0) 0f else (m / 1024f) * Math.pow(2.0, -14.0).toFloat()
            31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> java.lang.Float.intBitsToFloat(((e - 15 + 127) shl 23) or (m shl 13))
        }
        return if (s == 1) -v else v
    }

    /** 够用的 pickle（协议 2–5）解释器：只认 torch.save 会用到的指令 */
    @Suppress("UNCHECKED_CAST")
    private fun unpickle(b: ByteArray): Any? {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val st = ArrayList<Any?>(); val memo = HashMap<Int, Any?>()
        fun pop() = st.removeAt(st.size - 1)
        fun popMark(): List<Any?> { val i = st.lastIndexOf(Mark); val l = ArrayList(st.subList(i + 1, st.size)); while (st.size > i) pop(); return l }
        fun str(n: Int): String { val a = ByteArray(n); bb.get(a); return String(a, Charsets.UTF_8) }
        fun line(): String { val sb = StringBuilder(); while (true) { val c = bb.get().toInt().toChar(); if (c == '\n') break; sb.append(c) }; return sb.toString() }
        fun call(g: Global, args: List<Any?>): Any? = when {
            g.name == "OrderedDict" -> LinkedHashMap<String, Any?>()
            g.name == "_rebuild_tensor_v2" || g.name == "_rebuild_tensor" -> Tensor(args[0] as Storage, (args[1] as Number).toLong(),
                (args[2] as List<Any?>).map { (it as Number).toInt() }.toIntArray(), (args[3] as List<Any?>).map { (it as Number).toInt() }.toIntArray())
            g.name == "_rebuild_parameter" -> args[0]
            else -> Reduced(g, args)
        }
        while (true) {
            when (val op = bb.get().toInt() and 0xff) {
                0x80 -> bb.get()                                   // PROTO
                0x95 -> bb.getLong()                               // FRAME
                0x7d -> st.add(LinkedHashMap<Any?, Any?>())        // EMPTY_DICT
                0x5d -> st.add(ArrayList<Any?>())                  // EMPTY_LIST
                0x29 -> st.add(emptyList<Any?>())                  // EMPTY_TUPLE
                0x28 -> st.add(Mark)                               // MARK
                0x58 -> st.add(str(bb.int))                        // BINUNICODE
                0x8c -> st.add(str(bb.get().toInt() and 0xff))     // SHORT_BINUNICODE
                0x8d -> st.add(str(bb.long.toInt()))               // BINUNICODE8
                0x43 -> { val a = ByteArray(bb.get().toInt() and 0xff); bb.get(a); st.add(a) }   // SHORT_BINBYTES
                0x42 -> { val a = ByteArray(bb.int); bb.get(a); st.add(a) }                       // BINBYTES
                0x63 -> st.add(Global(line(), line()))             // GLOBAL
                0x93 -> { val n = pop() as String; val m = pop() as String; st.add(Global(m, n)) } // STACK_GLOBAL
                0x71 -> memo[bb.get().toInt() and 0xff] = st.last()  // BINPUT
                0x72 -> memo[bb.int] = st.last()                     // LONG_BINPUT
                0x94 -> memo[memo.size] = st.last()                  // MEMOIZE
                0x68 -> st.add(memo[bb.get().toInt() and 0xff])      // BINGET
                0x6a -> st.add(memo[bb.int])                         // LONG_BINGET
                0x4b -> st.add(bb.get().toInt() and 0xff)            // BININT1
                0x4d -> st.add(bb.short.toInt() and 0xffff)          // BININT2
                0x4a -> st.add(bb.int)                               // BININT
                0x8a -> { val n = bb.get().toInt() and 0xff; var v = 0L; for (i in 0 until n) v = v or ((bb.get().toLong() and 0xff) shl (8 * i))
                          if (n in 1..7 && (v shr (8 * n - 1)) and 1L == 1L) v = v or (-1L shl (8 * n)); st.add(v) }   // LONG1
                0x47 -> st.add(ByteBuffer.wrap(ByteArray(8).also { bb.get(it) }).double)   // BINFLOAT（大端）
                0x4e -> st.add(null)                                 // NONE
                0x88 -> st.add(true); 0x89 -> st.add(false)          // NEWTRUE / NEWFALSE
                0x74 -> st.add(popMark())                            // TUPLE
                0x85 -> st.add(listOf(pop()))                        // TUPLE1
                0x86 -> { val b2 = pop(); val a = pop(); st.add(listOf(a, b2)) }
                0x87 -> { val c = pop(); val b2 = pop(); val a = pop(); st.add(listOf(a, b2, c)) }
                0x6c -> st.add(ArrayList(popMark()))                 // LIST
                0x64 -> { val l = popMark(); val m = LinkedHashMap<Any?, Any?>(); for (i in l.indices step 2) m[l[i]] = l[i + 1]; st.add(m) }   // DICT
                0x61 -> { val v = pop(); (st.last() as MutableList<Any?>).add(v) }                 // APPEND
                0x65 -> { val l = popMark(); (st.last() as MutableList<Any?>).addAll(l) }           // APPENDS
                0x73 -> { val v = pop(); val k = pop(); (st.last() as MutableMap<Any?, Any?>)[k] = v }    // SETITEM
                0x75 -> { val l = popMark(); val m = st.last() as MutableMap<Any?, Any?>; for (i in l.indices step 2) m[l[i]] = l[i + 1] }   // SETITEMS
                0x8f -> st.add(LinkedHashSet<Any?>())                // EMPTY_SET
                0x90 -> { val l = popMark(); (st.last() as MutableSet<Any?>).addAll(l) }   // ADDITEMS
                0x52 -> { val a = pop() as List<Any?>; val g = pop(); st.add(if (g is Global) call(g, a) else Reduced(Global("?", "?"), a)) }   // REDUCE
                0x81 -> { val a = pop() as List<Any?>; val g = pop() as Global; st.add(call(g, a)) }   // NEWOBJ
                0x62 -> { val s = pop(); val o = st.last(); if (o is MutableMap<*, *> && s is Map<*, *>) (o as MutableMap<Any?, Any?>).putAll(s) }   // BUILD
                0x51 -> { val pid = pop() as List<Any?>   // BINPERSID: ('storage', 类型, key, 设备, 元素数)
                          val t = pid[1]; st.add(Storage(if (t is Global) t.name else t.toString(), pid[2].toString())) }
                0x2e -> return pop()                                 // STOP
                else -> throw IllegalArgumentException("pickle 指令 0x${op.toString(16)} 不支持")
            }
        }
    }

    // ======================================================================= 模型信息

    class Info(val version: String, val sr: Int, val f0: Int, val upsample: List<Int>)

    @Suppress("UNCHECKED_CAST")
    fun info(p: Pth): Info {
        val d = p.dict
        val cfg = d["config"] as? List<Any?> ?: throw IllegalArgumentException("没有 config")
        val sr = (cfg.last() as? Number)?.toInt() ?: when (d["sr"]?.toString()) { "32k" -> 32000; "40k" -> 40000; "48k" -> 48000; else -> 0 }
        val ups = (cfg.getOrNull(12) as? List<Any?>)?.map { (it as Number).toInt() } ?: emptyList()
        val f0 = (d["f0"] as? Number)?.toInt() ?: 1
        return Info(d["version"]?.toString() ?: "v1", sr, f0, ups)
    }

    // ======================================================================= 配方

    class R(val name: String, val src: String, val op: String, val dims: LongArray)
    class Plan(val recipe: List<R>, val w: List<Pair<String, Int>>, val bias: List<Triple<String, String, String>>, val acts: List<String>, val f32: List<String>)

    /** <k>.plan：每行一条，空格分隔。R 名 源 算法 维度(逗号) / W 名 轴 / B 名 激活 权重 / A 名 / F 名 */
    fun parsePlan(text: String): Plan {
        val r = ArrayList<R>(); val w = ArrayList<Pair<String, Int>>(); val b = ArrayList<Triple<String, String, String>>()
        val a = ArrayList<String>(); val f = ArrayList<String>()
        for (ln in text.lineSequence()) {
            val p = ln.split('\t'); if (p.size < 2) continue
            when (p[0]) {
                "R" -> r += R(p[1], p[2], p[3], p[4].split(',').filter { it.isNotEmpty() }.map { it.toLong() }.toLongArray())
                "W" -> w += p[1] to p[2].toInt()
                "B" -> b += Triple(p[1], p[2], p[3])
                "A" -> a += p[1]
                "F" -> f += p[1]
            }
        }
        return Plan(r, w, b, a, f)
    }

    /** 按配方从 .pth 算出 fp32 权重（weight_norm 折叠 / 转置 / 说话人嵌入取第 0 行） */
    fun weights(p: Pth, plan: Plan): Map<String, FloatArray> {
        val ws = p.weight
        fun t(k: String) = ws[k] ?: throw IllegalArgumentException("模型缺少 $k，结构和模板不一致")
        val out = LinkedHashMap<String, FloatArray>()
        for (r in plan.recipe) {
            val n = r.dims.fold(1L) { x, y -> x * y }.toInt()
            val a: FloatArray = when (r.op) {
                "wn" -> {
                    val g = p.floats(t(r.src + "_g")); val vt = t(r.src + "_v"); val v = p.floats(vt)
                    val per = v.size / vt.shape[0]
                    FloatArray(v.size).also { o ->
                        for (c in 0 until vt.shape[0]) {
                            var s = 0.0; for (i in 0 until per) { val x = v[c * per + i].toDouble(); s += x * x }
                            val k = g[c] / Math.sqrt(s)
                            for (i in 0 until per) o[c * per + i] = (v[c * per + i] * k).toFloat()
                        }
                    }
                }
                "T" -> { val tt = t(r.src); val x = p.floats(tt); val rows = tt.shape[0]; val cols = tt.shape[1]
                         FloatArray(x.size).also { o -> for (i in 0 until rows) for (j in 0 until cols) o[j * rows + i] = x[i * cols + j] } }
                "row0" -> { val tt = t(r.src); p.floats(tt).copyOf(tt.shape[1]) }
                else -> p.floats(t(r.src))
            }
            if (a.size != n) throw IllegalArgumentException("${r.src} 形状不符（${a.size} ≠ $n），结构和模板不一致")
            out[r.name] = a
        }
        return out
    }

    // ======================================================================= protobuf 追加

    private class Pb { val b = ByteArrayOutputStream()
        fun varint(v0: Long) { var v = v0; while (true) { if (v and 0x7fL.inv() == 0L) { b.write(v.toInt()); return }; b.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 } }
        fun tag(f: Int, w: Int) = varint(((f shl 3) or w).toLong())
        fun bytes(f: Int, x: ByteArray) { tag(f, 2); varint(x.size.toLong()); b.write(x) }
        fun int(f: Int, x: Long) { tag(f, 0); varint(x) }
        fun out() = b.toByteArray()
    }
    const val FLOAT = 1; const val UINT8 = 2; const val INT8 = 3; const val INT32 = 6

    /** 一个只含 graph.initializer[0] 的 ModelProto */
    private fun initMsg(name: String, dt: Int, dims: LongArray, raw: ByteArray): ByteArray {
        val t = Pb(); dims.forEach { t.int(1, it) }; t.int(2, dt.toLong()); t.bytes(8, name.toByteArray()); t.bytes(9, raw)
        val g = Pb(); g.bytes(5, t.out())
        val m = Pb(); m.bytes(7, g.out()); return m.out()
    }
    private fun outputMsg(name: String): ByteArray {
        val v = Pb(); v.bytes(1, name.toByteArray()); val g = Pb(); g.bytes(12, v.out()); val m = Pb(); m.bytes(7, g.out()); return m.out()
    }
    private fun f32(a: FloatArray) = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(a) }.array()
    private fun i32(a: IntArray) = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asIntBuffer().put(a) }.array()

    // ======================================================================= 校准 + 量化

    /** 校准数据：n 段，每段 phone[100×emb](fp16)、pitch[100](int16)、pitchf[100](f32)；文件 cal.bin 里先 768 维再 256 维两组 */
    class Cal(val phone: List<FloatArray>, val pitch: List<LongArray>, val pitchf: List<FloatArray>)

    fun readCal(f: File, emb: Int): Cal {
        DataInputStream(f.inputStream().buffered()).use { s ->
            val bb = ByteBuffer.wrap(s.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val n = bb.int
            val p768 = List(n) { FloatArray(100 * 768) { halfToFloat(bb.short) } }
            val p256 = List(n) { FloatArray(100 * 256) { halfToFloat(bb.short) } }
            val pitch = List(n) { LongArray(100) { bb.short.toLong() } }
            val pitchf = List(n) { FloatArray(100) { bb.float } }
            return Cal(if (emb == 768) p768 else p256, pitch, pitchf)
        }
    }

    /**
     * 生成一个档位的 QDQ 模型。
     * @param tplF32 / tplQdq 模板文件；@param tmp 临时 fp32 模型路径（校准用，完成后删除）
     */
    fun build(env: OrtEnvironment, plan: Plan, w: Map<String, FloatArray>, tplF32: File, tplQdq: File, cal: Cal, emb: Int,
              tmp: File, out: File, threads: Int = 4, progress: (String, Float) -> Unit = { _, _ -> }) {
        val dims = plan.recipe.associate { it.name to it.dims }
        // 1) fp32 + 中间输出
        progress("拼装 fp32", 0f)
        tmp.outputStream().buffered(1 shl 20).use { o ->
            o.write(tplF32.readBytes())
            for ((n, a) in w) o.write(initMsg(n, FLOAT, dims.getValue(n), f32(a)))
            for (a in plan.acts) o.write(outputMsg(a))
        }
        val mn = HashMap<String, Float>(); val mx = HashMap<String, Float>()
        OrtSession.SessionOptions().use { so ->
            so.setIntraOpNumThreads(threads)
            env.createSession(tmp.absolutePath, so).use { s ->
                val want = plan.acts.toSet()
                for (i in cal.phone.indices) {
                    progress("校准 ${i + 1}/${cal.phone.size}", 0.1f + 0.7f * i / cal.phone.size)
                    val ins = mapOf(
                        "phone" to OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(cal.phone[i]), longArrayOf(1, 100, emb.toLong())),
                        "pitch" to OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(cal.pitch[i]), longArrayOf(1, 100)),
                        "pitchf" to OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(cal.pitchf[i]), longArrayOf(1, 100)))
                    try {
                        s.run(ins, want).use { r ->
                            for ((name, v) in r) {
                                val fb = (v as OnnxTensor).floatBuffer
                                var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
                                while (fb.hasRemaining()) { val x = fb.get(); if (x < lo) lo = x; if (x > hi) hi = x }
                                mn[name] = minOf(mn[name] ?: lo, lo); mx[name] = maxOf(mx[name] ?: hi, hi)
                            }
                        }
                    } finally { ins.values.forEach { it.close() } }
                }
            }
        }
        tmp.delete()
        // 2) QDQ：激活 uint8 非对称（MinMax），权重 int8 按通道对称，偏置 int32 = 激活 scale × 权重 scale
        progress("量化", 0.85f)
        val aScale = HashMap<String, Float>(); val wScale = HashMap<String, FloatArray>()
        out.outputStream().buffered(1 shl 20).use { o ->
            o.write(tplQdq.readBytes())
            for (a in plan.acts) {
                val lo = minOf(mn[a] ?: 0f, 0f); val hi = maxOf(mx[a] ?: 0f, 0f)
                val sc = if (hi > lo) (hi - lo) / 255f else 1f
                val zp = Math.round(-lo / sc).coerceIn(0, 255)
                aScale[a] = sc
                o.write(initMsg(a + "_scale", FLOAT, LongArray(0), f32(floatArrayOf(sc))))
                o.write(initMsg(a + "_zero_point", UINT8, LongArray(0), byteArrayOf(zp.toByte())))
            }
            for (n in plan.f32) o.write(initMsg(n, FLOAT, dims.getValue(n), f32(w.getValue(n))))
            for ((n, axis) in plan.w) {
                val a = w.getValue(n); val d = dims.getValue(n).map { it.toInt() }
                val ch = d[axis]; val inner = d.drop(axis + 1).fold(1) { x, y -> x * y }; val outer = d.take(axis).fold(1) { x, y -> x * y }
                val amax = FloatArray(ch)
                for (oi in 0 until outer) for (c in 0 until ch) for (ii in 0 until inner) { val x = Math.abs(a[(oi * ch + c) * inner + ii]); if (x > amax[c]) amax[c] = x }
                val sc = FloatArray(ch) { if (amax[it] > 0f) 2f * amax[it] / 254f else 1f }
                val q = ByteArray(a.size)
                for (oi in 0 until outer) for (c in 0 until ch) for (ii in 0 until inner) { val k = (oi * ch + c) * inner + ii
                    q[k] = Math.rint((a[k] / sc[c]).toDouble()).coerceIn(-127.0, 127.0).toInt().toByte() }
                wScale[n] = sc
                o.write(initMsg(n + "_quantized", INT8, dims.getValue(n), q))
                o.write(initMsg(n + "_scale", FLOAT, longArrayOf(ch.toLong()), f32(sc)))
                o.write(initMsg(n + "_zero_point", INT8, longArrayOf(ch.toLong()), ByteArray(ch)))
            }
            for ((n, act, wn) in plan.bias) {
                val b = w.getValue(n); val ws = wScale.getValue(wn); val asc = aScale.getValue(act)
                val bs = FloatArray(b.size) { (asc * ws[it]).let { s -> if (s == 0f) 1e-8f else s } }
                val q = IntArray(b.size) { Math.rint((b[it] / bs[it]).toDouble()).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt() }
                val dl = longArrayOf(b.size.toLong())
                o.write(initMsg(n + "_quantized", INT32, dl, i32(q)))
                o.write(initMsg(n + "_quantized_scale", FLOAT, dl, f32(bs)))
                o.write(initMsg(n + "_quantized_zero_point", INT32, dl, i32(IntArray(b.size))))
            }
        }
        progress("完成", 1f)
    }

    @Suppress("unused") private fun writeAll(o: OutputStream, vararg b: ByteArray) = b.forEach { o.write(it) }
}
