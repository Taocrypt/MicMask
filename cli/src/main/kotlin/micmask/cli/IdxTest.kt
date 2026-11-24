package micmask.cli

import micmask.core.FaissImport
import micmask.core.FaissIvfIndex
import java.io.File

/** IdxTest <in.index> <out.bin> <cap> <queries.f32>：转换并打印前 5 个查询的 top-8 */
fun main(a: Array<String>) {
    val t0 = System.currentTimeMillis()
    val inf = FaissImport.convert(File(a[0]).inputStream(), File(a[1]), a[2].toInt())
    println("d=${inf.d} nlist=${inf.nlist} ntotal=${inf.ntotal} kept=${inf.kept} ${System.currentTimeMillis() - t0}ms size=${File(a[1]).length() shr 20}MB")
    val idx = FaissIvfIndex.load(File(a[1]))
    val q = java.nio.ByteBuffer.wrap(File(a[3]).readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val n = q.remaining() / inf.d
    for (i in 0 until n) { val v = FloatArray(inf.d); q.get(v); val r = idx.search(v); println(r.indices.joinToString(" ") + " | " + r.distances.take(3).joinToString(" ") { "%.3f".format(it) }) }
    val feats = Array(50) { FloatArray(inf.d) { Math.random().toFloat() } }
    repeat(3) { val t = System.nanoTime(); idx.mix(feats, 0.5f); println("mix 50 帧 ${(System.nanoTime() - t) / 1000000}ms") }
}
