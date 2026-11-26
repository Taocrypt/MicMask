package micmask.core

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 把 MicMask 流式合成器（<音色>_stream[_b50]_qdq.onnx，或手机导入 .pth 生成的同结构模型）拆成两半：
 *  - 前半：ORT 继续跑 enc_p / flow / 正弦源（m_source），输出 dec 的输入 z[1,C,L] 与谐波源 har[1,1,L*upp]
 *    （模板模型没有把说话人条件并进 conv_pre 时，再多输出 cond[1,c0,1]）；
 *  - 后半：NSF-HiFiGAN dec 的权重（QDQ 反量化后存 fp16）+ 48k 重采样尾巴，写成 MMVOC16 二进制，由原生 NEON 声码器跑。
 * 另外可生成「校验图」：原图不剪枝，只把 z / har / cond 追加为输出，同一次运行即可对比原生结果与 ORT 原输出。
 *
 * 全部在手机上从音色自带的 ONNX 初始化器里提取，不需要改素材仓库、不需要重新下载。
 * 只依赖 ONNX protobuf 线格式（手写解析，不引 protobuf 库）。
 *
 * MMVOC16 布局（小端）：
 *   "MMVOC16\0", i32 ver=1, i32 cin, c0, nstage, nk, resblock(1|2), upp
 *   conv_pre:  i32 K, pad; f16 W[c0][cin][K]; f32 b[c0]
 *   每个 stage：i32 rate, K, pad, cin, cout; f16 W[cin][cout][K]; f32 b[cout]
 *              i32 kn, stride, pad; f16 W[cout][1][kn]; f32 b[cout]
 *              nk 个 resblock：i32 nconv；每个 conv：i32 K, dil, pad; f16 W[cout][cout][K]; f32 b[cout]
 *   conv_post: i32 K, pad, hasBias; f16 W[1][ch][K]; (f32 b)
 *   tail:      i32 up, dn, taps, off, perFrame; f32 h[taps]
 *   "MMEN"
 */
object VocoderSplit {
    const val FORMAT = 1

    class Plan internal constructor(
        val z: String, val har: String, val cond: String?,
        val blob: ByteArray, val summary: String,
        internal val model: Model, internal val keepPre: BooleanArray,
    )

    class Unsupported(msg: String) : Exception(msg)

    // ---------------- protobuf 线格式 ----------------
    internal class Field(val num: Int, val wire: Int, val start: Int, val end: Int, val vStart: Int, val vEnd: Int, val varint: Long)

    internal class Reader(val b: ByteBuffer, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var r = 0L; var s = 0
            while (true) {
                val x = b.get(pos++).toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl s)
                if (x < 0x80) return r
                s += 7
                if (s > 63) throw Unsupported("坏的 varint")
            }
        }
        fun next(): Field {
            val st = pos
            val tag = varint(); val num = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
            return when (wire) {
                0 -> { val v = varint(); Field(num, wire, st, pos, pos, pos, v) }
                1 -> { pos += 8; Field(num, wire, st, pos, pos - 8, pos, 0) }
                5 -> { pos += 4; Field(num, wire, st, pos, pos - 4, pos, 0) }
                2 -> { val n = varint().toInt(); val vs = pos; pos += n; if (pos > end) throw Unsupported("protobuf 越界"); Field(num, wire, st, pos, vs, pos, 0) }
                else -> throw Unsupported("不支持的 wire type $wire")
            }
        }
    }

    private fun str(b: ByteBuffer, f: Field): String {
        val a = ByteArray(f.vEnd - f.vStart); for (i in a.indices) a[i] = b.get(f.vStart + i); return String(a, Charsets.UTF_8)
    }

    internal class Attr(val name: String) {
        var i = 0L; var f = 0f; var s: String? = null
        val ints = ArrayList<Long>(); val floats = ArrayList<Float>()
        var t: Tensor? = null
    }

    internal class Node(val idx: Int, val start: Int, val end: Int) {
        val inputs = ArrayList<String>(); val outputs = ArrayList<String>()
        var name = ""; var op = ""
        val attrs = HashMap<String, Attr>()
        fun ints(n: String) = attrs[n]?.ints?.map { it.toInt() } ?: emptyList()
        fun int(n: String, d: Long) = attrs[n]?.i ?: d
    }

    internal class Tensor(val b: ByteBuffer, val start: Int, val end: Int) {
        var name = ""; var type = 0; val dims = ArrayList<Long>()
        var raw: Field? = null; var floatData: Field? = null; var int32Data: Field? = null; var int64Data: Field? = null
        var external = false
        val count: Long get() = dims.fold(1L) { a, d -> a * d }
        /** 整数/浮点统一读成 double（int8/uint8/int32/int64/float/float16） */
        fun values(): DoubleArray {
            if (external) throw Unsupported("外部数据初始化器 $name")
            val n = count.toInt(); val out = DoubleArray(n)
            val r = raw
            if (r != null) {
                val bb = b.duplicate().order(ByteOrder.LITTLE_ENDIAN); val p = r.vStart
                for (i in 0 until n) out[i] = when (type) {
                    1 -> bb.getFloat(p + 4 * i).toDouble()
                    2 -> (bb.get(p + i).toInt() and 0xff).toDouble()
                    3 -> bb.get(p + i).toDouble()
                    6 -> bb.getInt(p + 4 * i).toDouble()
                    7 -> bb.getLong(p + 8 * i).toDouble()
                    10 -> halfToFloat(bb.getShort(p + 2 * i).toInt() and 0xffff).toDouble()
                    11 -> bb.getDouble(p + 8 * i)
                    else -> throw Unsupported("不支持的张量类型 $type（$name）")
                }
                return out
            }
            val ff = floatData
            if (ff != null && type == 1) { val bb = b.duplicate().order(ByteOrder.LITTLE_ENDIAN); for (i in 0 until n) out[i] = bb.getFloat(ff.vStart + 4 * i).toDouble(); return out }
            val iff = int32Data ?: int64Data
            if (iff != null) {
                val rd = Reader(b, iff.vStart, iff.vEnd); var i = 0
                while (rd.more() && i < n) {
                    var v = rd.varint()
                    if (type == 3) v = v.toByte().toLong() else if (type == 6) v = v.toInt().toLong()
                    out[i++] = v.toDouble()
                }
                if (i != n) throw Unsupported("张量 $name 数据长度不符")
                return out
            }
            if (n == 0) return out
            throw Unsupported("张量 $name 没有数据")
        }
    }

    internal class Model(val buf: ByteBuffer) {
        val top = ArrayList<Field>()
        /** graph 可能出现多次（手机导入 .pth 时逐个追加只含一个初始化器的 ModelProto，protobuf 按合并语义读） */
        val graphs = ArrayList<Field>()
        val graphFields = ArrayList<Field>()
        val nodes = ArrayList<Node>()
        val inits = HashMap<String, Tensor>()
        val initField = HashMap<String, Field>()
        val initNameAt = HashMap<Int, String>()   // initializer 字段起点 → 名字
        val producer = HashMap<String, Node>()
        val consumers = HashMap<String, ArrayList<Node>>()
        val graphOutputs = ArrayList<String>()
    }

    internal fun parseTensor(b: ByteBuffer, s: Int, e: Int): Tensor {
        val t = Tensor(b, s, e); val r = Reader(b, s, e)
        while (r.more()) {
            val f = r.next()
            when (f.num) {
                1 -> if (f.wire == 2) { val q = Reader(b, f.vStart, f.vEnd); while (q.more()) t.dims += q.varint() } else t.dims += f.varint
                2 -> t.type = f.varint.toInt()
                4 -> if (f.wire == 2) t.floatData = f
                5 -> if (f.wire == 2) t.int32Data = f
                7 -> if (f.wire == 2) t.int64Data = f
                8 -> t.name = str(b, f)
                9 -> t.raw = f
                14 -> if (f.varint == 1L) t.external = true
            }
        }
        return t
    }

    private fun parseAttr(b: ByteBuffer, s: Int, e: Int): Attr {
        val r = Reader(b, s, e); var name = ""; val tmp = ArrayList<Field>()
        while (r.more()) { val f = r.next(); if (f.num == 1) name = str(b, f) else tmp += f }
        val a = Attr(name)
        for (f in tmp) when (f.num) {
            2 -> a.f = b.duplicate().order(ByteOrder.LITTLE_ENDIAN).getFloat(f.vStart)
            3 -> a.i = f.varint
            4 -> a.s = str(b, f)
            5 -> a.t = parseTensor(b, f.vStart, f.vEnd)
            7 -> if (f.wire == 2) { val bb = b.duplicate().order(ByteOrder.LITTLE_ENDIAN); var p = f.vStart; while (p < f.vEnd) { a.floats += bb.getFloat(p); p += 4 } } else a.floats += b.duplicate().order(ByteOrder.LITTLE_ENDIAN).getFloat(f.vStart)
            8 -> if (f.wire == 2) { val q = Reader(b, f.vStart, f.vEnd); while (q.more()) a.ints += q.varint() } else a.ints += f.varint
        }
        return a
    }

    internal fun parse(buf: ByteBuffer): Model {
        val m = Model(buf)
        val r = Reader(buf, 0, buf.limit())
        while (r.more()) { val f = r.next(); m.top += f; if (f.num == 7 && f.wire == 2) m.graphs += f }
        if (m.graphs.isEmpty()) throw Unsupported("没有 graph")
        for (g in m.graphs) { val gr = Reader(buf, g.vStart, g.vEnd); while (gr.more()) {
            val f = gr.next(); m.graphFields += f
            when (f.num) {
                1 -> {
                    val n = Node(m.nodes.size, f.start, f.end); val nr = Reader(buf, f.vStart, f.vEnd)
                    while (nr.more()) {
                        val x = nr.next()
                        when (x.num) {
                            1 -> n.inputs += str(buf, x); 2 -> n.outputs += str(buf, x)
                            3 -> n.name = str(buf, x); 4 -> n.op = str(buf, x)
                            5 -> { val a = parseAttr(buf, x.vStart, x.vEnd); n.attrs[a.name] = a }
                        }
                    }
                    m.nodes += n
                    for (o in n.outputs) if (o.isNotEmpty()) m.producer[o] = n
                    for (i in n.inputs) if (i.isNotEmpty()) m.consumers.getOrPut(i) { ArrayList() } += n
                }
                5 -> { val t = parseTensor(buf, f.vStart, f.vEnd); m.inits[t.name] = t; m.initField[t.name] = f; m.initNameAt[f.start] = t.name }
                12 -> { val vr = Reader(buf, f.vStart, f.vEnd); while (vr.more()) { val x = vr.next(); if (x.num == 1) m.graphOutputs += str(buf, x) } }
            }
        } }
        return m
    }

    // ---------------- 图分析 ----------------
    private fun Model.node(suffix: String) = nodes.firstOrNull { it.name.endsWith(suffix) }

    /** 常量张量：初始化器或 Constant 节点 */
    private fun Model.const(name: String): Tensor? = inits[name] ?: producer[name]?.takeIf { it.op == "Constant" }?.attrs?.get("value")?.t

    /** 常量值：常量本身，或常量经 Unsqueeze/Reshape/Squeeze/Identity/Cast 后的值（未做常量折叠的模板图） */
    private fun Model.constVal(name: String, depth: Int = 0): DoubleArray? {
        const(name)?.let { return it.values() }
        val p = producer[name] ?: return null
        return if (depth < 6 && p.op in setOf("Unsqueeze", "Reshape", "Squeeze", "Identity", "Cast")) constVal(p.inputs[0], depth + 1) else null
    }

    /** 跳过 QDQ 对：DequantizeLinear(QuantizeLinear(x)) → x */
    private fun Model.fp(name: String): String {
        val dq = producer[name] ?: return name
        if (dq.op != "DequantizeLinear") return name
        val q = producer[dq.inputs[0]] ?: return name
        return if (q.op == "QuantizeLinear") q.inputs[0] else name
    }

    /** 向后跳过 Q→DQ，返回实际使用这个 fp32 张量的节点 */
    private fun Model.nextUse(name: String): List<Node> {
        val cs = consumers[name].orEmpty()
        if (cs.size == 1 && cs[0].op == "QuantizeLinear") {
            val dq = consumers[cs[0].outputs[0]].orEmpty()
            if (dq.size == 1 && dq[0].op == "DequantizeLinear") return consumers[dq[0].outputs[0]].orEmpty()
        }
        return cs
    }

    /** 权重/偏置：fp32 常量，或 DequantizeLinear(int 常量, scale, zp) 反量化（支持逐通道 axis） */
    private fun Model.weight(name: String, shape: List<Long>? = null): Pair<FloatArray, List<Long>> {
        const(name)?.let { t ->
            if (t.type != 1 && t.type != 10) throw Unsupported("权重 $name 类型 ${t.type}")
            return t.values().let { v -> FloatArray(v.size) { v[it].toFloat() } } to t.dims
        }
        val dq = producer[name] ?: throw Unsupported("找不到权重 $name")
        if (dq.op != "DequantizeLinear") throw Unsupported("权重 $name 来自 ${dq.op}")
        val q = const(dq.inputs[0]) ?: throw Unsupported("量化权重 ${dq.inputs[0]} 不是常量")
        val sc = const(dq.inputs[1]) ?: throw Unsupported("scale 不是常量")
        val zp = dq.inputs.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { const(it) ?: throw Unsupported("zero point 不是常量") }
        val qv = q.values(); val sv = sc.values(); val zv = zp?.values()
        val dims = q.dims
        val out = FloatArray(qv.size)
        if (sv.size == 1) {
            val z = zv?.get(0) ?: 0.0
            for (i in qv.indices) out[i] = ((qv[i] - z) * sv[0]).toFloat()
        } else {
            var axis = dq.int("axis", 1).toInt(); if (axis < 0) axis += dims.size
            val n = dims[axis].toInt(); if (sv.size != n) throw Unsupported("逐通道 scale 长度不符 $name")
            val inner = dims.drop(axis + 1).fold(1L) { a, d -> a * d }.toInt()
            for (i in qv.indices) { val c = (i / inner) % n; out[i] = ((qv[i] - (zv?.get(c) ?: 0.0)) * sv[c]).toFloat() }
        }
        if (shape != null && shape != dims) throw Unsupported("权重 $name 形状 $dims ≠ $shape")
        return out to dims
    }

    private class ConvW(val w: FloatArray, val dims: List<Long>, val b: FloatArray?, val k: Int, val dil: Int, val pad: Int, val stride: Int)

    private fun Model.conv(n: Node): ConvW {
        if (n.op != "Conv" && n.op != "ConvTranspose") throw Unsupported("${n.name} 不是卷积")
        if (n.int("group", 1) != 1L) throw Unsupported("${n.name} 分组卷积")
        val (w, dims) = weight(n.inputs[1])
        if (dims.size != 3) throw Unsupported("${n.name} 不是 1-D 卷积")
        val b = n.inputs.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { weight(it).first }
        val pads = n.ints("pads").ifEmpty { listOf(0, 0) }
        if (pads.size != 2 || pads[0] != pads[1]) throw Unsupported("${n.name} 非对称 padding $pads")
        if (n.attrs["auto_pad"]?.s.let { it != null && it != "NOTSET" }) throw Unsupported("${n.name} auto_pad")
        if (n.op == "ConvTranspose" && n.ints("output_padding").any { it != 0 }) throw Unsupported("${n.name} output_padding")
        val k = n.ints("kernel_shape").firstOrNull() ?: dims[2].toInt()
        return ConvW(w, dims, b, k, n.ints("dilations").firstOrNull() ?: 1, pads[0], n.ints("strides").firstOrNull() ?: 1)
    }

    private class Out {
        val o = java.io.ByteArrayOutputStream()
        private val tmp = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun i32(v: Int) { tmp.clear(); tmp.putInt(v); o.write(tmp.array(), 0, 4) }
        fun f16(a: FloatArray) { val bb = ByteBuffer.allocate(a.size * 2).order(ByteOrder.LITTLE_ENDIAN); for (x in a) bb.putShort(floatToHalf(x).toShort()); o.write(bb.array()) }
        fun f32(a: FloatArray) { val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN); for (x in a) bb.putFloat(x); o.write(bb.array()) }
        fun raw(s: String) = o.write(s.toByteArray(Charsets.ISO_8859_1))
    }

    private fun need(c: Boolean, msg: () -> String) { if (!c) throw Unsupported(msg()) }

    /** 分析并提取。失败抛 [Unsupported]（调用方回退 ORT 整图）。 */
    fun plan(buf: ByteBuffer): Plan {
        val m = parse(buf)
        val pre = m.node("/dec/conv_pre/Conv") ?: throw Unsupported("没找到 dec/conv_pre（不是 RVC 流式合成器？）")
        val post = m.node("/dec/conv_post/Conv") ?: throw Unsupported("没找到 dec/conv_post")
        val z = m.fp(pre.inputs[0])
        // conv_pre 之后：已融合 cond → 直接 LeakyRelu；未融合 → Add(cond) 再 LeakyRelu
        var cond: String? = null
        run {
            val u = m.nextUse(pre.outputs[0])
            need(u.size == 1) { "conv_pre 输出有 ${u.size} 个使用者" }
            when (u[0].op) {
                "LeakyRelu" -> {}
                "Add" -> {
                    val add = u[0]
                    val other = add.inputs.first { m.fp(it) != pre.outputs[0] && it != pre.outputs[0] }
                    val c = m.fp(other)
                    val cn = m.producer[c]
                    need(cn != null && cn.name.endsWith("/dec/cond/Conv")) { "conv_pre 后的 Add 不是 cond" }
                    cond = c
                    val u2 = m.nextUse(add.outputs[0]); need(u2.size == 1 && u2[0].op == "LeakyRelu") { "cond 之后不是 LeakyRelu" }
                }
                else -> throw Unsupported("conv_pre 之后是 ${u[0].op}")
            }
        }
        val ups = generateSequence(0) { it + 1 }.map { m.node("/dec/ups.$it/ConvTranspose") }.takeWhile { it != null }.map { it!! }.toList()
        need(ups.isNotEmpty()) { "没找到 dec/ups" }
        val rbIdx = m.nodes.mapNotNull { Regex("/dec/resblocks\\.(\\d+)/").find(it.name)?.groupValues?.get(1)?.toInt() }.toSet()
        need(rbIdx.isNotEmpty() && rbIdx.size % ups.size == 0) { "resblock 数量 ${rbIdx.size} 与上采样层数 ${ups.size} 不符" }
        val nk = rbIdx.size / ups.size
        val rb1 = m.node("/dec/resblocks.0/convs1.0/Conv") != null
        need(rb1 || m.node("/dec/resblocks.0/convs.0/Conv") != null) { "认不出 resblock 结构" }

        val o = Out()
        o.raw("MMVOC16\u0000"); o.i32(FORMAT)
        val cp = m.conv(pre)
        val c0 = cp.dims[0].toInt(); val cin = cp.dims[1].toInt()
        val rates = ArrayList<Int>()
        // 先算 upp
        var upp = 1
        for (u in ups) upp *= m.conv(u).stride
        o.i32(cin); o.i32(c0); o.i32(ups.size); o.i32(nk); o.i32(if (rb1) 1 else 2); o.i32(upp)
        need(cp.k == cp.dims[2].toInt() && cp.dil == 1 && cp.stride == 1 && 2 * cp.pad == cp.k - 1) { "conv_pre 几何不符" }
        o.i32(cp.k); o.i32(cp.pad); o.f16(cp.w); o.f32(cp.b ?: FloatArray(c0))
        var ch = c0; var har: String? = null; var nconvTotal = 0
        for ((i, un) in ups.withIndex()) {
            val u = m.conv(un)
            val ci = u.dims[0].toInt(); val co = u.dims[1].toInt()
            need(ci == ch) { "ups.$i 输入通道 $ci ≠ $ch" }
            need(u.k == u.stride + 2 * u.pad && u.dil == 1) { "ups.$i 几何不符（K=${u.k} stride=${u.stride} pad=${u.pad}）" }
            rates += u.stride
            o.i32(u.stride); o.i32(u.k); o.i32(u.pad); o.i32(ci); o.i32(co); o.f16(u.w); o.f32(u.b ?: FloatArray(co))
            val nn = m.node("/dec/noise_convs.$i/Conv") ?: throw Unsupported("没找到 noise_convs.$i")
            val h = m.fp(nn.inputs[0]); if (har == null) har = h else need(har == h) { "noise_convs 输入不一致" }
            val nc = m.conv(nn)
            need(nc.dims[0].toInt() == co && nc.dims[1] == 1L && nc.dil == 1 && nc.k == nc.stride + 2 * nc.pad) { "noise_convs.$i 几何不符" }
            o.i32(nc.k); o.i32(nc.stride); o.i32(nc.pad); o.f16(nc.w); o.f32(nc.b ?: FloatArray(co))
            for (j in 0 until nk) {
                val r = i * nk + j
                val names = if (rb1) generateSequence(0) { it + 1 }.map { mm -> listOf("/dec/resblocks.$r/convs1.$mm/Conv", "/dec/resblocks.$r/convs2.$mm/Conv") }
                    .takeWhile { p -> m.node(p[0]) != null }.flatten().toList()
                else generateSequence(0) { it + 1 }.map { mm -> "/dec/resblocks.$r/convs.$mm/Conv" }.takeWhile { p -> m.node(p) != null }.toList()
                need(names.isNotEmpty()) { "resblocks.$r 为空" }
                o.i32(names.size)
                for (nm in names) {
                    val c = m.conv(m.node(nm) ?: throw Unsupported("缺 $nm"))
                    need(c.dims[0].toInt() == co && c.dims[1].toInt() == co && c.stride == 1 && 2 * c.pad == (c.k - 1) * c.dil) { "$nm 几何不符" }
                    o.i32(c.k); o.i32(c.dil); o.i32(c.pad); o.f16(c.w); o.f32(c.b ?: FloatArray(co))
                    nconvTotal++
                }
            }
            ch = co
        }
        val pc = m.conv(post)
        need(pc.dims[0] == 1L && pc.dims[1].toInt() == ch && pc.stride == 1 && 2 * pc.pad == pc.k - 1) { "conv_post 几何不符" }
        o.i32(pc.k); o.i32(pc.pad); o.i32(if (pc.b != null) 1 else 0); o.f16(pc.w); pc.b?.let { o.f32(it) }
        // conv_post → (Q/DQ) → Tanh → [Reshape → ConvTranspose(1→1) → Slice(off::dn) → Reshape] → Slice
        val tanh = m.nextUse(post.outputs[0]).singleOrNull()?.takeIf { it.op == "Tanh" } ?: throw Unsupported("conv_post 之后不是 Tanh")
        var up = 1; var dn = 1; var off = 0; var h = FloatArray(0)
        run {
            var cur = tanh.outputs[0]; var guard = 0
            while (cur !in m.graphOutputs && guard++ < 12) {
                val n = m.nextUse(cur).filter { it.op != "Shape" }.singleOrNull() ?: throw Unsupported("dec 输出后有分叉")
                when (n.op) {
                    "Reshape", "Squeeze", "Unsqueeze", "Identity", "Flatten" -> {}
                    "ConvTranspose" -> {
                        val c = m.conv(n)
                        need(c.dims[0] == 1L && c.dims[1] == 1L && c.pad == 0 && c.b == null && up == 1) { "重采样层结构不符" }
                        up = c.stride; h = c.w
                    }
                    "Slice" -> {
                        val st = m.constVal(n.inputs[1]); val steps = n.inputs.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { m.constVal(it) ?: throw Unsupported("Slice 步长不是常量") }
                        need(st != null && st.size == 1) { "Slice 起点不是常量" }
                        val step = steps?.get(0)?.toInt() ?: 1
                        if (up != 1 && dn == 1 && step != 1) { off = st!![0].toInt(); dn = step }
                        else need(st!![0] == 0.0 && step == 1) { "未知的 Slice" }
                    }
                    else -> throw Unsupported("dec 输出后有 ${n.op}")
                }
                cur = n.outputs[0]
            }
            need(cur in m.graphOutputs) { "没走到图输出" }
        }
        need((upp.toLong() * up) % dn == 0L && upp * up / dn == 480) { "输出不是 48k（upp=$upp up=$up dn=$dn）" }
        o.i32(up); o.i32(dn); o.i32(h.size); o.i32(off); o.i32(480); o.f32(h)
        o.raw("MMEN")
        val harName = har ?: throw Unsupported("没找到谐波源")
        // 前半图：z / har / cond 的全部祖先
        val keep = BooleanArray(m.nodes.size)
        val stack = ArrayDeque(listOfNotNull(z, harName, cond))
        val seen = HashSet<String>()
        while (stack.isNotEmpty()) {
            val t = stack.removeLast(); if (!seen.add(t)) continue
            val p = m.producer[t] ?: continue
            if (keep[p.idx]) continue
            keep[p.idx] = true
            for (i in p.inputs) if (i.isNotEmpty()) stack.addLast(i)
        }
        val kept = keep.count { it }
        val sr = 48000 * dn / up
        val summary = "dec ${ups.size} 级 rates=$rates upp=$upp ${if (rb1) "ResBlock1" else "ResBlock2"}×$nk（$nconvTotal 卷积）cin=$cin c0=$c0 " +
            "sr=${sr / 1000}k${if (up != dn) "→48k" else ""} cond=${if (cond != null) "外置" else "已并入"} 前半图 $kept/${m.nodes.size} 节点 blob=${o.o.size() shr 10}KB"
        return Plan(z, harName, cond, o.o.toByteArray(), summary, m, keep)
    }

    // ---------------- 写图 ----------------
    private fun varintBytes(v: Long): ByteArray {
        val o = java.io.ByteArrayOutputStream(); var x = v
        while (true) { if (x and 0x7fL.inv() == 0L) { o.write(x.toInt()); break }; o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
        return o.toByteArray()
    }
    private fun lenField(num: Int, payload: ByteArray) = varintBytes(((num shl 3) or 2).toLong()) + varintBytes(payload.size.toLong()) + payload
    private fun outputInfo(name: String): ByteArray {
        val elem = varintBytes((1 shl 3).toLong()) + varintBytes(1)           // elem_type = FLOAT
        val tensorType = lenField(1, elem)                                     // TypeProto.tensor_type
        return lenField(12, lenField(1, name.toByteArray(Charsets.UTF_8)) + lenField(2, tensorType))
    }

    /**
     * 写出改过的模型。pruned=true：只保留前半图节点与它们用到的初始化器，输出 = z/har/cond；
     * pruned=false：原图原样，额外追加 z/har/cond 输出（校验用）。
     */
    fun write(plan: Plan, out: File, pruned: Boolean) {
        val m = plan.model; val b = m.buf
        val outs = listOfNotNull(plan.z, plan.har, plan.cond)
        // graph 片段：(start,end) 原文复制，或新字节
        val segs = ArrayList<Any>()
        var outputsDone = false
        val used = HashSet<String>()
        if (pruned) for (n in m.nodes) if (plan.keepPre[n.idx]) used.addAll(n.inputs)
        var ni = 0
        for (f in m.graphFields) {
            when (f.num) {
                1 -> { val keep = !pruned || plan.keepPre[ni]; ni++; if (keep) segs += f }
                5 -> if (!pruned || m.initNameAt[f.start] in used) segs += f
                12 -> {
                    if (!outputsDone) { outputsDone = true; for (x in outs) segs += outputInfo(x) }
                    if (!pruned) segs += f
                }
                13 -> if (!pruned) segs += f
                else -> segs += f
            }
        }
        if (!outputsDone) for (x in outs) segs += outputInfo(x)
        val glen = segs.sumOf { s -> if (s is Field) (s.end - s.start).toLong() else (s as ByteArray).size.toLong() }
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        FileOutputStream(tmp).use { fo ->
            val ch = fo.channel
            fun copy(s: Int, e: Int) { val d = b.duplicate(); d.limit(e); d.position(s); while (d.hasRemaining()) ch.write(d) }
            fun bytes(a: ByteArray) { val d = ByteBuffer.wrap(a); while (d.hasRemaining()) ch.write(d) }
            var graphDone = false
            for (f in m.top) {
                if (f.num == 7 && f.wire == 2) {
                    if (graphDone) continue   // 多段 graph 合并成一段写出
                    graphDone = true
                    bytes(varintBytes(((7 shl 3) or 2).toLong())); bytes(varintBytes(glen))
                    for (s in segs) if (s is Field) copy(s.start, s.end) else bytes(s as ByteArray)
                } else copy(f.start, f.end)
            }
            fo.fd.sync()
        }
        if (!tmp.renameTo(out)) { tmp.delete(); throw java.io.IOException("写入 ${out.name} 失败") }
    }

    fun map(f: File): ByteBuffer = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

    // ---------------- half ----------------
    fun floatToHalf(v: Float): Int {
        val x = java.lang.Float.floatToRawIntBits(v)
        val sign = (x ushr 16) and 0x8000
        val e8 = (x ushr 23) and 0xff
        var exp = e8 - 127 + 15
        var mant = x and 0x7fffff
        if (e8 == 0xff) return sign or 0x7c00 or (if (mant != 0) 0x200 else 0)
        if (exp >= 31) return sign or 0x7c00
        if (exp <= 0) {
            if (exp < -10) return sign
            mant = mant or 0x800000
            val shift = 14 - exp
            var h = mant ushr shift
            val rem = mant and ((1 shl shift) - 1); val half = 1 shl (shift - 1)
            if (rem > half || (rem == half && (h and 1) != 0)) h++
            return sign or h
        }
        var h = (exp shl 10) or (mant ushr 13)
        val rem = mant and 0x1fff
        if (rem > 0x1000 || (rem == 0x1000 && (h and 1) != 0)) h++
        return sign or h
    }

    fun halfToFloat(h: Int): Float {
        val sign = (h and 0x8000) shl 16; var exp = (h ushr 10) and 0x1f; var mant = h and 0x3ff
        val f = when {
            exp == 0 && mant == 0 -> sign
            exp == 0 -> { exp = 127 - 15 + 1; while (mant and 0x400 == 0) { mant = mant shl 1; exp-- }; mant = mant and 0x3ff; sign or (exp shl 23) or (mant shl 13) }
            exp == 31 -> sign or 0x7f800000 or (mant shl 13)
            else -> sign or ((exp + 127 - 15) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(f)
    }
}
