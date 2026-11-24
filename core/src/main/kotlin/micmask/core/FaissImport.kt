package micmask.core

import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 手机上把 RVC 训练出的 faiss `.index`（IndexIVFFlat，文件头 IwFl）转成 [FaissIvfIndex] 的 ATRIIDX1 格式。
 * 向量太多时按倒排表等比例抽样到 [cap] 个（默认 1 万，v2 约 30 MB），控制内存和检索耗时；聚类中心保持不变。
 */
object FaissImport {

    class Info(val d: Int, val nlist: Int, val ntotal: Long, val kept: Int, val lists: Int = nlist)

    private class Rd(i: InputStream) {
        val s = BufferedInputStream(i, 1 shl 20)
        private val b8 = ByteArray(8)
        fun bytes(n: Int): ByteArray { val a = ByteArray(n); var o = 0; while (o < n) { val r = s.read(a, o, n - o); if (r < 0) throw IllegalArgumentException("index 文件不完整"); o += r }; return a }
        fun skip(n0: Long) { var n = n0; while (n > 0) { val r = s.skip(n); if (r <= 0) { bytes(1); n-- } else n -= r } }
        fun u8() = bytes(1)[0].toInt() and 0xff
        fun i32() = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int
        fun i64() = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).long
        fun f32() = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).float
        fun fourcc() = String(bytes(4), Charsets.US_ASCII)
    }

    private class Hdr(val d: Int, val ntotal: Long, val metric: Int)
    private fun header(r: Rd): Hdr {
        val d = r.i32(); val nt = r.i64(); r.i64(); r.i64(); r.u8()
        val metric = r.i32(); if (metric > 1) r.f32()
        return Hdr(d, nt, metric)
    }

    fun convert(input: InputStream, out: File, cap: Int = 10000, maxLists: Int = 128): Info {
        val r = Rd(input)
        val h = r.fourcc()
        if (h != "IwFl" && h != "IvFl") throw IllegalArgumentException("不支持的 index 类型 $h（只支持 RVC 的 IVF-Flat）")
        val hd = header(r)
        if (hd.metric != 1) throw IllegalArgumentException("只支持 L2 距离的 index")
        val d = hd.d
        val nlist = r.i64().toInt(); r.i64()   // nlist, nprobe
        // 量化器：IndexFlatL2
        val qh = r.fourcc()
        if (qh != "IxF2" && qh != "IxFl") throw IllegalArgumentException("不支持的量化器 $qh")
        val qhd = header(r)
        val qn = r.i64()   // 向量元素个数（float）
        if (qn != nlist.toLong() * d || qhd.d != d) throw IllegalArgumentException("聚类中心大小不符")
        val cent = r.bytes((qn * 4).toInt())
        // direct map
        if (h == "IwFl") { val dm = r.u8(); r.skip(r.i64() * 8); if (dm == 2) r.skip(r.i64() * 16) }
        else { r.u8(); r.skip(r.i64() * 8) }
        // 倒排表
        val il = r.fourcc()
        if (il != "ilar") throw IllegalArgumentException("不支持的倒排表 $il")
        if (r.i64().toInt() != nlist) throw IllegalArgumentException("倒排表数量不符")
        val codeSize = r.i64().toInt()
        if (codeSize != d * 4) throw IllegalArgumentException("不是 Flat 编码（code_size=$codeSize）")
        val sizes = LongArray(nlist)
        when (val kind = r.fourcc()) {
            "full" -> { val n = r.i64().toInt(); for (i in 0 until n) sizes[i] = r.i64() }
            "sprs" -> { val n = r.i64().toInt(); var i = 0; while (i < n) { sizes[r.i64().toInt()] = r.i64(); i += 2 } }
            else -> throw IllegalArgumentException("不支持的倒排表格式 $kind")
        }
        val ntotal = sizes.sum()
        val ratio = if (ntotal > cap) cap.toDouble() / ntotal else 1.0
        val keep = IntArray(nlist) { if (sizes[it] == 0L) 0 else maxOf(1, Math.round(sizes[it] * ratio).toInt()).coerceAtMost(sizes[it].toInt()) }
        // 读出保留的向量（约 1 万 × d，v2 约 30 MB）
        val lists = Array(nlist) { li ->
            val n = sizes[li].toInt(); val k = keep[li]
            val v = FloatArray(k * d); var next = 0
            for (i in 0 until n) {
                val want = next < k && i == (next.toLong() * n / k).toInt()
                if (want) { ByteBuffer.wrap(r.bytes(codeSize)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v, next * d, d); next++ } else r.skip(codeSize.toLong())
            }
            r.skip(n * 8L)   // ids
            v
        }
        val c0 = FloatArray(nlist * d).also { ByteBuffer.wrap(cent).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        // 聚类中心太多（RVC 常见 1000~3000）时每帧找中心就要扫几千个向量，手机上太慢：
        // 把中心再聚成 maxLists 组、合并倒排表，新中心取组内向量的均值（nprobe=1 检索）
        val (groups, ng) = if (nlist > maxLists) regroup(c0, keep, nlist, d, maxLists) else IntArray(nlist) { it } to nlist
        val members = Array(ng) { g -> (0 until nlist).filter { groups[it] == g && keep[it] > 0 } }.filter { it.isNotEmpty() }
        val nl = members.size
        val kept = keep.sum()
        val starts = IntArray(nl + 1).also { for (g in 0 until nl) it[g + 1] = it[g] + members[g].sumOf { li -> keep[li] } }
        val newCent = FloatArray(nl * d)
        for (g in 0 until nl) {
            if (ng == nlist) { System.arraycopy(c0, members[g][0] * d, newCent, g * d, d); continue }
            val cnt = starts[g + 1] - starts[g]
            for (li in members[g]) { val v = lists[li]; for (i in v.indices) newCent[g * d + i % d] += v[i] }
            for (j in 0 until d) newCent[g * d + j] /= cnt
        }
        DataOutputStream(out.outputStream().buffered(1 shl 20)).use { o ->
            val bb = ByteBuffer.allocate(8 + 12 + (nl + 1) * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("ATRIIDX1".toByteArray(Charsets.US_ASCII)); bb.putInt(nl); bb.putInt(d); bb.putInt(kept)
            starts.forEach { bb.putInt(it) }
            o.write(bb.array())
            fun wf(a: FloatArray) { val x = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN); x.asFloatBuffer().put(a); o.write(x.array()) }
            wf(newCent)
            val ids = ByteBuffer.allocate(kept * 4).order(ByteOrder.LITTLE_ENDIAN); for (i in 0 until kept) ids.putInt(i); o.write(ids.array())
            for (g in 0 until nl) for (li in members[g]) wf(lists[li])
        }
        return Info(d, nlist, ntotal, kept, nl)
    }

    /** 加权 k-means（权重=表内保留向量数），返回每个原中心所属的组 */
    private fun regroup(c: FloatArray, w: IntArray, n: Int, d: Int, k: Int, iters: Int = 10): Pair<IntArray, Int> {
        val cen = FloatArray(k * d)
        for (g in 0 until k) System.arraycopy(c, (g.toLong() * n / k).toInt() * d, cen, g * d, d)
        val asg = IntArray(n)
        repeat(iters) {
            for (i in 0 until n) {
                var best = 0; var bd = Float.MAX_VALUE
                for (g in 0 until k) { var s = 0f; for (j in 0 until d) { val t = c[i * d + j] - cen[g * d + j]; s += t * t }; if (s < bd) { bd = s; best = g } }
                asg[i] = best
            }
            val sum = DoubleArray(k * d); val cnt = DoubleArray(k)
            for (i in 0 until n) { val ww = maxOf(w[i], 1).toDouble(); val g = asg[i]; cnt[g] += ww; for (j in 0 until d) sum[g * d + j] += c[i * d + j] * ww }
            for (g in 0 until k) if (cnt[g] > 0) for (j in 0 until d) cen[g * d + j] = (sum[g * d + j] / cnt[g]).toFloat()
        }
        return asg to k
    }
}
