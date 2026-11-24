package micmask.cli

import micmask.core.FaissImport
import micmask.core.FaissIvfIndex
import java.io.File

/** IdxTest <in.index> <out.bin> <cap> <queries.f32> [maxLists] [mixOut.f32]：转换、计时，并把 indexRate=1 的混合结果写出供对比 */
fun main(a: Array<String>) {
    val t0 = System.currentTimeMillis()
    val inf = FaissImport.convert(File(a[0]).inputStream(), File(a[1]), a[2].toInt(), a.getOrNull(4)?.toInt() ?: 128)
    println("d=${inf.d} nlist=${inf.nlist}→${inf.lists} ntotal=${inf.ntotal} kept=${inf.kept} ${System.currentTimeMillis() - t0}ms size=${File(a[1]).length() shr 20}MB")
    val idx = FaissIvfIndex.load(File(a[1]))
    val q = java.nio.ByteBuffer.wrap(File(a[3]).readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val n = q.remaining() / inf.d
    val qs = Array(n) { FloatArray(inf.d).also { v -> q.get(v) } }
    a.getOrNull(5)?.let { p ->
        val m = idx.mix(qs, 1f)
        val bb = java.nio.ByteBuffer.allocate(n * inf.d * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        m.forEach { bb.asFloatBuffer(); for (x in it) bb.putFloat(x) }
        File(p).writeBytes(bb.array())
    }
    val feats = qs.copyOfRange(0, minOf(50, n))
    repeat(4) { val t = System.nanoTime(); idx.mix(feats, 0.5f); println("mix ${feats.size} 帧 ${(System.nanoTime() - t) / 1000000}ms") }
}
