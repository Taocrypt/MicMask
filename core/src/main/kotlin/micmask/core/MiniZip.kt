package micmask.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * 只读 zip（支持 ZIP64、stored/deflate）。PyTorch 的 .pth 用它而不用 java.util.zip.ZipFile：
 * 安卓的 ZipFile 对 PyTorch 写出的 zip（对齐用的额外字段、ZIP64 等）会直接拒绝，电脑上的 JDK 却能打开。
 */
class MiniZip(f: File) : AutoCloseable {
    class Entry(val name: String, val method: Int, val csize: Long, val size: Long, val lho: Long)
    private val raf = RandomAccessFile(f, "r")
    val entries: List<Entry>

    private fun buf(pos: Long, n: Int): ByteBuffer {
        val a = ByteArray(n); raf.seek(pos); raf.readFully(a)
        return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
    }

    init {
        val len = raf.length()
        val tail = minOf(len, 65557L).toInt()
        val t = buf(len - tail, tail)
        var eocd = -1
        for (i in tail - 22 downTo 0) if (t.getInt(i) == 0x06054b50) { eocd = i; break }
        require(eocd >= 0) { "找不到 zip 目录（文件不完整或不是 zip）" }
        var count = t.getShort(eocd + 10).toLong() and 0xffff
        var cdSize = t.getInt(eocd + 12).toLong() and 0xffffffffL
        var cdOff = t.getInt(eocd + 16).toLong() and 0xffffffffL
        // ZIP64
        val locPos = eocd - 20
        if (locPos >= 0 && t.getInt(locPos) == 0x07064b50) {
            val z = buf(t.getLong(locPos + 8), 56)
            require(z.getInt(0) == 0x06064b50) { "ZIP64 目录损坏" }
            count = z.getLong(32); cdSize = z.getLong(40); cdOff = z.getLong(48)
        }
        val cd = buf(cdOff, cdSize.toInt())
        val list = ArrayList<Entry>()
        var p = 0
        repeat(count.toInt()) {
            require(cd.getInt(p) == 0x02014b50) { "zip 目录项损坏" }
            val method = cd.getShort(p + 10).toInt() and 0xffff
            var cs = cd.getInt(p + 20).toLong() and 0xffffffffL
            var us = cd.getInt(p + 24).toLong() and 0xffffffffL
            val nl = cd.getShort(p + 28).toInt() and 0xffff
            val xl = cd.getShort(p + 30).toInt() and 0xffff
            val cl = cd.getShort(p + 32).toInt() and 0xffff
            var lho = cd.getInt(p + 42).toLong() and 0xffffffffL
            val name = String(ByteArray(nl).also { a -> cd.position(p + 46); cd.get(a) }, Charsets.UTF_8)
            var x = p + 46 + nl; val xe = x + xl
            while (x + 4 <= xe) {
                val id = cd.getShort(x).toInt() and 0xffff; val sz = cd.getShort(x + 2).toInt() and 0xffff
                if (id == 1) {
                    var q = x + 4
                    if (us == 0xffffffffL) { us = cd.getLong(q); q += 8 }
                    if (cs == 0xffffffffL) { cs = cd.getLong(q); q += 8 }
                    if (lho == 0xffffffffL) { lho = cd.getLong(q) }
                }
                x += 4 + sz
            }
            list += Entry(name, method, cs, us, lho)
            p += 46 + nl + xl + cl
        }
        entries = list
    }

    fun find(name: String) = entries.firstOrNull { it.name == name }

    fun read(e: Entry): ByteArray {
        val h = buf(e.lho, 30)
        require(h.getInt(0) == 0x04034b50) { "zip 数据头损坏：${e.name}" }
        val start = e.lho + 30 + (h.getShort(26).toInt() and 0xffff) + (h.getShort(28).toInt() and 0xffff)
        require(e.size < Int.MAX_VALUE && e.csize < Int.MAX_VALUE) { "条目过大：${e.name}" }
        val c = ByteArray(e.csize.toInt()); raf.seek(start); raf.readFully(c)
        return when (e.method) {
            0 -> c
            8 -> Inflater(true).run { setInput(c); val o = ByteArray(e.size.toInt()); var n = 0; while (n < o.size && !finished()) n += inflate(o, n, o.size - n); end(); o }
            else -> throw IllegalArgumentException("不支持的 zip 压缩方式 ${e.method}")
        }
    }

    override fun close() = raf.close()
}
