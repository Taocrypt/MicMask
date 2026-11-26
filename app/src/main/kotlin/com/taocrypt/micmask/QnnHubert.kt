package com.taocrypt.micmask

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.FloatBuffer

/**
 * HuBERT 上骁龙 NPU：基础模型 hubert_dyn8.onnx 是动态量化（DynamicQuantizeLinear + MatMulInteger），QNN 不支持。
 * 在本机把每条 MatMulInteger 链改写成 MatMul(x, DequantizeLinear(int8 权重, 按列 scale/zp))，
 * 权重仍是原来的 int8（文件大小不变，免额外下载），再按固定输入长度交给 QNN HTP fp16 生成上下文缓存。
 * 宿主验证：改写后与 fp32 原模型 cos 0.999（原 dyn8 为 0.989）。
 */
object QnnHubert {
    const val CHECK_COS = 0.97

    // ---------- 极简 protobuf 读写（只处理用到的字段，其余原样拷贝） ----------
    private class Field(val num: Int, val wire: Int, val start: Int, val end: Int, val vStart: Int, val vEnd: Int)

    private fun varint(b: ByteArray, p0: Int): Pair<Long, Int> {
        var p = p0; var r = 0L; var sh = 0
        while (true) { val x = b[p++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl sh); if (x < 0x80) break; sh += 7 }
        return r to p
    }

    private fun fields(b: ByteArray, from: Int, to: Int): List<Field> {
        val out = ArrayList<Field>(); var p = from
        while (p < to) {
            val s = p; val (tag, p1) = varint(b, p); p = p1
            val num = (tag ushr 3).toInt(); val w = (tag and 7).toInt()
            when (w) {
                0 -> { val (_, p2) = varint(b, p); out += Field(num, w, s, p2, p, p2); p = p2 }
                1 -> { out += Field(num, w, s, p + 8, p, p + 8); p += 8 }
                5 -> { out += Field(num, w, s, p + 4, p, p + 4); p += 4 }
                2 -> { val (len, p2) = varint(b, p); val e = p2 + len.toInt(); out += Field(num, w, s, e, p2, e); p = e }
                else -> throw IllegalStateException("不支持的 protobuf wire type $w")
            }
        }
        return out
    }

    private fun str(b: ByteArray, f: Field) = String(b, f.vStart, f.vEnd - f.vStart, Charsets.UTF_8)

    private class Node(val raw: ByteArray?, val inputs: List<String>, val outputs: List<String>, val op: String, val name: String = "", val attrs: ByteArray = ByteArray(0)) {
        fun encode(): ByteArray {
            if (raw != null) return raw
            val o = ByteArrayOutputStream()
            inputs.forEach { ws(o, 1, it) }; outputs.forEach { ws(o, 2, it) }
            if (name.isNotEmpty()) ws(o, 3, name); ws(o, 4, op); o.write(attrs)
            return o.toByteArray()
        }
    }

    private fun wv(o: OutputStream, v: Long) { var x = v; while (x and 0x7fL.inv() != 0L) { o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()) }
    private fun vlen(v: Long): Int { var x = v; var n = 1; while (x and 0x7fL.inv() != 0L) { x = x ushr 7; n++ }; return n }
    private fun wb(o: OutputStream, num: Int, b: ByteArray) { wv(o, (num shl 3 or 2).toLong()); wv(o, b.size.toLong()); o.write(b) }
    private fun ws(o: OutputStream, num: Int, s: String) = wb(o, num, s.toByteArray(Charsets.UTF_8))
    private fun attrInt(name: String, v: Long): ByteArray {
        val a = ByteArrayOutputStream(); ws(a, 1, name); wv(a, (3 shl 3).toLong()); wv(a, v); wv(a, (20 shl 3).toLong()); wv(a, 2)
        val o = ByteArrayOutputStream(); wb(o, 5, a.toByteArray()); return o.toByteArray()
    }

    /** 改写 dyn8 → 权重 DequantizeLinear 版。返回改写的 MatMul 数 */
    fun rewrite(src: File, dst: File): Int {
        val b = src.readBytes()
        val top = fields(b, 0, b.size)
        val gf = top.firstOrNull { it.num == 7 && it.wire == 2 } ?: error("没有 graph")
        val gfs = fields(b, gf.vStart, gf.vEnd)
        val inits = HashSet<String>()
        for (f in gfs) if (f.num == 5 && f.wire == 2) fields(b, f.vStart, f.vEnd).firstOrNull { it.num == 8 }?.let { inits += str(b, it) }
        val nodes = ArrayList<Node>()
        for (f in gfs) if (f.num == 1 && f.wire == 2) {
            val ins = ArrayList<String>(); val outs = ArrayList<String>(); var op = ""
            for (x in fields(b, f.vStart, f.vEnd)) when (x.num) { 1 -> ins += str(b, x); 2 -> outs += str(b, x); 4 -> op = str(b, x) }
            nodes += Node(b.copyOfRange(f.vStart, f.vEnd), ins, outs, op)
        }
        val prod = HashMap<String, Node>(); val cons = HashMap<String, MutableList<Node>>()
        for (n in nodes) { n.outputs.forEach { prod[it] = n }; n.inputs.forEach { cons.getOrPut(it) { ArrayList() } += n } }
        val drop = HashSet<Node>(); val replace = HashMap<Node, List<Node>>()
        var count = 0
        for (n in nodes) {
            if (n.op != "MatMulInteger" || n.inputs.size < 4) continue
            val (xq, wq, _, wz) = n.inputs
            val dql = prod[xq] ?: continue
            val cast = cons[n.outputs[0]]?.singleOrNull() ?: continue
            val mul = cons[cast.outputs[0]]?.singleOrNull()?.takeIf { it.op == "Mul" } ?: continue
            val smul = prod[mul.inputs.firstOrNull { it != cast.outputs[0] } ?: continue] ?: continue
            val ws = smul.inputs.firstOrNull { it in inits } ?: continue
            if (wq !in inits || wz !in inits) continue
            val w = wq + "_mmw"
            replace[mul] = listOf(
                Node(null, listOf(wq, ws, wz), listOf(w), "DequantizeLinear", "${wq}_dqw", attrInt("axis", 1)),
                Node(null, listOf(dql.inputs[0], w), listOf(mul.outputs[0]), "MatMul", "${mul.outputs[0]}_mm"))
            drop += n; drop += cast; drop += smul
            count++
        }
        var list = nodes.flatMap { replace[it] ?: if (it in drop) emptyList() else listOf(it) }
        val gOut = HashSet<String>()
        for (f in gfs) if (f.num == 12 && f.wire == 2) fields(b, f.vStart, f.vEnd).firstOrNull { it.num == 1 }?.let { gOut += str(b, it) }
        repeat(6) {   // 删悬空节点（DynamicQuantizeLinear 等）
            val used = HashSet<String>(gOut); list.forEach { used.addAll(it.inputs) }
            list = list.filter { n -> n.outputs.any { it in used } }
        }
        val enc = list.map { it.encode() }
        // 新 graph：节点放在原第一个节点的位置，其余字段原样
        val graphParts = ArrayList<Any>()   // ByteArray 片段或 Field
        var nodesPlaced = false
        for (f in gfs) {
            if (f.num == 1 && f.wire == 2) { if (!nodesPlaced) { graphParts += "N"; nodesPlaced = true } } else graphParts += f
        }
        var gLen = 0L
        for (p in graphParts) gLen += if (p is Field) (p.end - p.start).toLong() else enc.sumOf { (vlen((1 shl 3 or 2).toLong()) + vlen(it.size.toLong()) + it.size).toLong() }
        BufferedOutputStream(FileOutputStream(dst), 1 shl 20).use { o ->
            for (f in top) {
                if (f !== gf) { o.write(b, f.start, f.end - f.start); continue }
                wv(o, (7 shl 3 or 2).toLong()); wv(o, gLen)
                for (p in graphParts) if (p is Field) o.write(b, p.start, p.end - p.start) else enc.forEach { wb(o, 1, it) }
            }
        }
        return count
    }

    /**
     * 打开 NPU 版 HuBERT。n = 输入采样数（固定），ref = 当前 CPU 版会话（首次校验用）。
     * 缓存放在 hubert 文件旁：<stem>.qnn<n>_<架构>_<版本>.ctx.onnx / .json
     */
    fun open(env: OrtEnvironment, dir: File, src: File, n: Int, ref: OrtSession, outName: String): OrtSession? {
        val arch = QnnPack.deviceArch() ?: return null
        val base = File(src.parentFile, src.name.removeSuffix(".onnx") + ".qnn${n}_${arch}_${QnnPack.VERSION}")
        val ctx = File(base.path + ".ctx.onnx"); val meta = File(base.path + ".json")
        if (meta.isFile && runCatching { !org.json.JSONObject(meta.readText()).optBoolean("ok", true) }.getOrDefault(false)) {
            L.w("NPU HuBERT：之前判定不可用（${runCatching { org.json.JSONObject(meta.readText()).optString("why") }.getOrNull()}）"); return null
        }
        fun fail(why: String, e: Throwable? = null): OrtSession? {
            if (e != null) L.e("NPU HuBERT：$why", e) else L.w("NPU HuBERT：$why")
            runCatching { meta.writeText(org.json.JSONObject().put("ok", false).put("why", why + (e?.message?.take(160)?.let { "：$it" } ?: "")).toString()) }
            return null
        }
        val t0 = System.nanoTime(); var genMs = 0L
        if (!ctx.isFile || ctx.length() == 0L) {
            val g = File(base.path + ".dq.onnx")
            try {
                val c = rewrite(src, g)
                L.i("NPU HuBERT：已改写 $c 个动态量化矩阵乘（${g.length() shr 20} MB，${(System.nanoTime() - t0) / 1_000_000}ms）")
                env.createSession(g.absolutePath, opts(dir, ctx, n)).close()
            } catch (e: Throwable) { ctx.delete(); return fail("生成 QNN 上下文失败", e) } finally { g.delete() }
            genMs = (System.nanoTime() - t0) / 1_000_000
            L.i("NPU HuBERT：已生成 QNN 上下文 ${ctx.name}（${ctx.length() shr 10} KB，${genMs}ms）")
        }
        val t1 = System.nanoTime()
        val s = try { env.createSession(ctx.absolutePath, opts(dir, null, n)) } catch (e: Throwable) {
            L.e("NPU HuBERT：从缓存加载失败，删缓存下次重建", e); ctx.delete(); return null
        }
        L.i("NPU HuBERT：加载 ${(System.nanoTime() - t1) / 1_000_000}ms${if (genMs > 0) " · 首次编译 ${genMs}ms" else ""}")
        if (!meta.isFile) {
            val cos = runCatching {
                val x = FloatArray(n) { i -> (kotlin.math.sin(i * 0.031) * 0.3 + kotlin.math.sin(i * 0.0071) * 0.2 + (Math.random() - 0.5) * 0.05).toFloat() }
                val a = run(env, ref, x, outName); val bb = run(env, s, x, outName)
                var sum = 0.0; var m = 0
                for (i in 0 until minOf(a.size, bb.size)) {
                    var ab = 0.0; var aa = 0.0; var b2 = 0.0
                    for (j in a[i].indices) { ab += a[i][j] * bb[i][j]; aa += a[i][j] * a[i][j]; b2 += bb[i][j] * bb[i][j] }
                    sum += ab / kotlin.math.sqrt(aa * b2 + 1e-30); m++
                }
                sum / maxOf(m, 1)
            }.onFailure { L.e("NPU HuBERT 校验出错", it) }.getOrNull()
            if (cos == null || cos.isNaN()) { s.close(); return null }
            val ok = cos >= CHECK_COS
            val why = "与 CPU 版平均余弦相似度 ${"%.4f".format(cos)}（阈值 $CHECK_COS）"
            L.i("NPU HuBERT 校验：$why → ${if (ok) "通过" else "不通过"}")
            meta.writeText(org.json.JSONObject().put("ok", ok).put("why", why).toString())
            if (!ok) { s.close(); ctx.delete(); return null }
        }
        return s
    }

    fun run(env: OrtEnvironment, s: OrtSession, x: FloatArray, outName: String): Array<FloatArray> {
        val name = s.inputInfo.keys.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { tin ->
            val want = outName.takeIf { it in s.outputNames } ?: s.outputNames.first()
            s.run(mapOf(name to tin), setOf(want)).use { res ->
                val o = res.get(0) as OnnxTensor
                val sh = (o.info as TensorInfo).shape
                val t = sh[1].toInt(); val d = sh[2].toInt()
                val flat = FloatArray(t * d); o.floatBuffer.get(flat)
                return Array(t) { i -> flat.copyOfRange(i * d, (i + 1) * d) }
            }
        }
    }

    private fun opts(dir: File, gen: File?, n: Int): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(2); setInterOpNumThreads(1)
        setSymbolicDimensionValue("T", n.toLong())
        addQnn(mapOf(
            "backend_path" to File(dir, "libQnnHtp.so").absolutePath,
            "htp_performance_mode" to "burst",
            "enable_htp_fp16_precision" to "1",
            "htp_graph_finalization_optimization_mode" to "3",
        ))
        // 个别算子 HTP 不支持时允许留在 CPU（生成的上下文模型里会是 EPContext + CPU 节点）
        if (gen != null) {
            addConfigEntry("ep.context_enable", "1")
            addConfigEntry("ep.context_file_path", gen.absolutePath)
            addConfigEntry("ep.context_embed_mode", "1")
        }
    }
}
