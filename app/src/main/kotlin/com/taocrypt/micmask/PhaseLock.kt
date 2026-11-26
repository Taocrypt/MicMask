package com.taocrypt.micmask

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 相位锁定（借鉴 mobile-rvc 的 phase_lock，MIT）：RVC 的正弦激励每块从相位 0 起算，块与块之间相位跳变会带来轻微「嗒嗒」声。
 * 这里把合成器里 l_sin_gen 的随机初相（ScatterND，基频那一路恒为 0）换成新输入 ph0[1,1,1]（单位：周），
 * 由 LiveRvc 按上一块实际推进的相位喂进去，使相邻块的正弦相位连续。
 * 改写结果放在原模型旁 <名>.pl.onnx；没有这个结构的模型返回 null（照常用原模型）。
 */
object PhaseLock {
    const val INPUT = "ph0"
    private class F(val num: Int, val wire: Int, val start: Int, val end: Int, val vs: Int, val ve: Int)

    private fun varint(b: ByteArray, p0: Int): Pair<Long, Int> {
        var p = p0; var r = 0L; var sh = 0
        while (true) { val x = b[p++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl sh); if (x < 0x80) break; sh += 7 }
        return r to p
    }
    private fun fields(b: ByteArray, from: Int, to: Int): List<F> {
        val out = ArrayList<F>(); var p = from
        while (p < to) {
            val s = p; val (tag, p1) = varint(b, p); p = p1
            val num = (tag ushr 3).toInt(); val w = (tag and 7).toInt()
            when (w) {
                0 -> { val (_, p2) = varint(b, p); out += F(num, w, s, p2, p, p2); p = p2 }
                1 -> { out += F(num, w, s, p + 8, p, p + 8); p += 8 }
                5 -> { out += F(num, w, s, p + 4, p, p + 4); p += 4 }
                2 -> { val (len, p2) = varint(b, p); val e = p2 + len.toInt(); out += F(num, w, s, e, p2, e); p = e }
                else -> throw IllegalStateException("wire $w")
            }
        }
        return out
    }
    private fun str(b: ByteArray, f: F) = String(b, f.vs, f.ve - f.vs, Charsets.UTF_8)
    private fun wv(o: OutputStream, v: Long) { var x = v; while (x and 0x7fL.inv() != 0L) { o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()) }
    private fun wb(o: OutputStream, num: Int, b: ByteArray) { wv(o, (num shl 3 or 2).toLong()); wv(o, b.size.toLong()); o.write(b) }
    private fun wi(o: OutputStream, num: Int, v: Long) { wv(o, (num shl 3).toLong()); wv(o, v) }
    private fun msg(f: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(f).toByteArray()

    /** ValueInfoProto：ph0 float[1,1,1] */
    private fun inputInfo(): ByteArray {
        val dim = msg { wi(it, 1, 1) }
        val shape = msg { o -> repeat(3) { wb(o, 1, dim) } }
        val tt = msg { wi(it, 1, 1); wb(it, 2, shape) }
        val type = msg { wb(it, 1, tt) }
        return msg { wb(it, 1, INPUT.toByteArray()); wb(it, 2, type) }
    }

    fun prepare(model: File): File? {
        if (model.name.endsWith(".pl.onnx")) return model
        val dst = File(model.parentFile, model.name.removeSuffix(".onnx") + ".pl.onnx")
        val no = File(model.parentFile, model.name + ".nopl")
        if (no.isFile && no.readText() == "${model.length()}@${model.lastModified()}") return null
        if (dst.isFile && dst.lastModified() >= model.lastModified()) return dst
        return try {
            if (rewrite(model, dst)) { L.i("相位锁定：已改写 ${model.name}"); dst }
            else { runCatching { no.writeText("${model.length()}@${model.lastModified()}") }; L.w("相位锁定：${model.name} 没有可改写的正弦激励，照常"); null }
        } catch (t: Throwable) { L.e("相位锁定改写 ${model.name} 失败", t); dst.delete(); null }
    }

    private fun rewrite(src: File, dst: File): Boolean {
        val b = src.readBytes()
        val top = fields(b, 0, b.size)
        val gf = top.firstOrNull { it.num == 7 && it.wire == 2 } ?: return false
        val gfs = fields(b, gf.vs, gf.ve)
        // 找 l_sin_gen 里 ScatterND 的输出，以及吃它的 Add
        var scat: String? = null
        for (f in gfs) if (f.num == 1 && f.wire == 2) {
            val xs = fields(b, f.vs, f.ve)
            val op = xs.firstOrNull { it.num == 4 }?.let { str(b, it) }
            val name = xs.firstOrNull { it.num == 3 }?.let { str(b, it) } ?: ""
            if (op == "ScatterND" && name.contains("l_sin_gen")) scat = xs.firstOrNull { it.num == 2 }?.let { str(b, it) }
        }
        val sc = scat ?: return false
        var addNode: F? = null
        for (f in gfs) if (f.num == 1 && f.wire == 2) {
            val xs = fields(b, f.vs, f.ve)
            if (xs.firstOrNull { it.num == 4 }?.let { str(b, it) } == "Add" && xs.any { it.num == 1 && str(b, it) == sc }) { addNode = f; break }
        }
        val an = addNode ?: return false
        val newNode = msg { o ->
            for (x in fields(b, an.vs, an.ve)) if (x.num == 1 && str(b, x) == sc) wb(o, 1, INPUT.toByteArray()) else o.write(b, x.start, x.end - x.start)
        }
        val graph = msg { o ->
            var inputAdded = false
            for (f in gfs) {
                if (f === an) { wb(o, 1, newNode); continue }
                o.write(b, f.start, f.end - f.start)
            }
            if (!inputAdded) { wb(o, 11, inputInfo()); inputAdded = true }
        }
        BufferedOutputStream(FileOutputStream(dst), 1 shl 20).use { o ->
            for (f in top) if (f === gf) wb(o, 7, graph) else o.write(b, f.start, f.end - f.start)
        }
        return true
    }
}
