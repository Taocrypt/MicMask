package com.taocrypt.micmask

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * 骁龙 NPU（QNN HTP）运行库：按需从官方 Maven 仓库下载，不随 App 分发、不由本项目托管。
 *
 * 高通 QNN 库的许可（AI Stack License，随 com.qualcomm.qti:qnn-runtime 发布）只允许并入应用分发，不允许单独分发，
 * 所以 App 只记录官方包的坐标和每个库的 SHA-256，首次使用前让用户同意许可，然后直接从 Maven Central 或其国内镜像
 * 读取官方 AAR 中本机需要的几个 .so（HTTP Range，只下需要的部分），逐个校验后放进 App 外部目录；
 * 注入进程（shell）启动推理时再复制到 /data/local/tmp/micmask-models/ 下加载（外部存储不能直接加载原生库）。
 */
object QnnPack {
    const val VERSION = "2.42.0"
    const val LICENSE_URL = "https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf"
    private const val PATH = "com/qualcomm/qti/qnn-runtime/$VERSION/qnn-runtime-$VERSION.aar"
    /** 同一个官方 AAR 的几个公开镜像（sha1 一致：ab1009dfc48ead8c096543a72d41a0e8ecd77d32） */
    val MIRRORS = listOf(
        "https://maven.aliyun.com/repository/central",
        "https://repo.huaweicloud.com/repository/maven",
        "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
        "https://repo1.maven.org/maven2",
    )
    private val SHA256 = mapOf(
        "libQnnHtp.so" to "429b74453f3dc95c6b94ca20894b4e5f08fbf8604bf339e37ef30fc17819e542",
        "libQnnHtpPrepare.so" to "6db1ae82a43610030e3be326f36cf5f5323ed0f2adf4bb9222e5445e98b86e03",
        "libQnnSystem.so" to "dcdef54ab054072c5713974feada8af8c56558da184b7d9228e2e116aa3c7016",
        "libQnnHtpV68Skel.so" to "160a5a95d3553a6a3e0f12927cf2db4b17e286318c90153c239209c5fe44a4f6",
        "libQnnHtpV68Stub.so" to "e1bb5a7c27be7900996172130320ea03ec0263b2ef3fbf323a95e884fb385f67",
        "libQnnHtpV69Skel.so" to "f9e6fc5a8bcb4dce64e30d63782591bfdc101e469d5e84696515b6e143bfc921",
        "libQnnHtpV69Stub.so" to "8d57cc81e9be654cd71d25d02fab457ad2c8182145c37cb499015cd6e1b96df7",
        "libQnnHtpV73Skel.so" to "5799de9978ccaefd6e560b0aab94cae3e6a93450694df8b21f4ec711e99755cc",
        "libQnnHtpV73Stub.so" to "e91d29f2d6d8077652ba2902c5670dc9045d3caee413662e2d41529dfa93317b",
        "libQnnHtpV75Skel.so" to "443c090f7324b1199c172d0800d080c8c92d9541109a380b8cd34b1941db4e50",
        "libQnnHtpV75Stub.so" to "bec31085d942b9f56bb417ccb81e11b61403fcc452f7011694cf9df1ba1ea5f8",
        "libQnnHtpV79Skel.so" to "b378e9dc83ab255058ff962b4ed334eaa791869c031e9db1c6ec2c780029ddd0",
        "libQnnHtpV79Stub.so" to "9954e242900de0b40cc7a13c1d28696a3e867aa7584a00afaff8191a12f002eb",
        "libQnnHtpV81Skel.so" to "62b289888051553ae9d8d9eb3e37c3db0dc020dffe13e447176d72ec24d134e7",
        "libQnnHtpV81Stub.so" to "2428bc53fe0c1108ab2a0aef784c294755d69caaccbf4f15a7ecedd585a3f62e",
    )
    /** 压缩后大小（字节，用于显示下载量） */
    private val ZSIZE = mapOf(
        "libQnnHtp.so" to 982010L, "libQnnHtpPrepare.so" to 33969994L, "libQnnSystem.so" to 967800L,
        "V68" to 3021828L + 228120L, "V69" to 3430102L + 228119L, "V73" to 3677710L + 230401L,
        "V75" to 3697261L + 230409L, "V79" to 3781239L + 230395L, "V81" to 4078025L + 259335L,
    )

    /** SoC → HTP 架构。只有 V75（骁龙 8 Gen 3）真机验证过，其余为推测 */
    fun archOf(soc: String?): String? = when (soc?.uppercase()?.removeSuffix("P")) {
        "SM8350" -> "V68"
        "SM8450", "SM8475" -> "V69"
        "SM8550", "SM7675", "SM7635" -> "V73"
        "SM8650", "SM8635" -> "V75"
        "SM8750", "SM8735" -> "V79"
        "SM8850", "SM8845" -> "V81"
        else -> null
    }
    fun deviceSoc(): String? = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else null
    fun deviceArch(): String? = archOf(deviceSoc())
    fun verified(arch: String) = arch == "V75"

    fun files(arch: String) = listOf("libQnnSystem.so", "libQnnHtp${arch}Stub.so", "libQnnHtpV${arch.removePrefix("V")}Skel.so", "libQnnHtp.so", "libQnnHtpPrepare.so")
    fun downloadBytes(arch: String) = (ZSIZE["libQnnHtp.so"]!! + ZSIZE["libQnnHtpPrepare.so"]!! + ZSIZE["libQnnSystem.so"]!! + (ZSIZE[arch] ?: 0L))
    fun dirName(arch: String) = "qnn-$VERSION-$arch"
    private const val MARK = "ok.txt"

    /** App 侧目录（外部私有目录，shell 可读） */
    fun appDir(ctx: android.content.Context, arch: String) = File(ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "ext"), dirName(arch))
    fun installed(dir: File, arch: String): Boolean =
        File(dir, MARK).isFile && files(arch).all { n -> File(dir, n).let { it.isFile && it.length() > 0 } }

    // ------------------------------------------------------------ 下载

    class Progress(val done: Long, val total: Long, val mirror: String, val file: String)

    @Volatile var cancel = false

    /** 下载并安装到 dir。可断点续传（每个库的压缩数据先写 .part）；任一镜像失败自动换下一个 */
    fun download(dir: File, arch: String, onProgress: (Progress) -> Unit) {
        cancel = false
        dir.mkdirs()
        File(dir, MARK).delete()
        val mirrors = rankMirrors()
        L.i("QNN 下载：镜像顺序 ${mirrors.joinToString { host(it.first) + " " + it.second + "ms" }}")
        if (mirrors.isEmpty()) throw IllegalStateException("所有镜像都连不上")
        var lastErr: Throwable? = null
        for ((base, _) in mirrors) {
            try {
                val url = "$base/$PATH"
                val entries = centralDir(url)
                val want = files(arch)
                val total = want.sumOf { n -> entries[n]?.csize ?: throw IllegalStateException("官方包里没有 $n") }
                var doneBefore = 0L
                for (n in want) {
                    val e = entries.getValue(n)
                    val out = File(dir, n)
                    if (out.isFile && out.length() == e.usize && sha256(out) == SHA256[n]) { doneBefore += e.csize; onProgress(Progress(doneBefore, total, host(base), n)); continue }
                    val part = File(dir, "$n.part")
                    val start = dataStart(url, e)
                    fetchTo(url, start, e.csize, part) { got -> onProgress(Progress(doneBefore + got, total, host(base), n)) }
                    inflateTo(part, e, out)
                    val h = sha256(out)
                    if (h != SHA256[n]) { out.delete(); part.delete(); throw SecurityException("$n 校验不符（$h）") }
                    part.delete()
                    out.setReadable(true, false)
                    doneBefore += e.csize
                    L.i("QNN 下载：$n 完成（${e.usize shr 10} KB，来源 ${host(base)}）")
                }
                File(dir, MARK).writeText("qnn-runtime $VERSION $arch\n" + want.joinToString("\n") { "$it ${SHA256[it]}" })
                return
            } catch (t: Throwable) {
                if (cancel) throw InterruptedException("已取消")
                lastErr = t
                L.w("QNN 下载：${host(base)} 失败，换下一个镜像：$t")
            }
        }
        throw lastErr ?: IllegalStateException("下载失败")
    }

    fun delete(dir: File) = dir.deleteRecursively()

    private fun host(u: String) = runCatching { URL(u).host }.getOrDefault(u)

    /** 并发试连每个镜像（取 1 KB），按耗时排序，连不上的剔除 */
    private fun rankMirrors(): List<Pair<String, Long>> {
        val res = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val ts = MIRRORS.map { m ->
            Thread {
                runCatching {
                    val t = System.nanoTime()
                    val b = range("$m/$PATH", 0, 1024)
                    if (b.size == 1024) res[m] = (System.nanoTime() - t) / 1_000_000
                }.onFailure { L.w("QNN 镜像 ${host(m)} 不可用：$it") }
            }.also { it.start() }
        }
        ts.forEach { it.join(12_000) }
        return res.entries.sortedBy { it.value }.map { it.key to it.value }
    }

    private class Entry(val name: String, val method: Int, val csize: Long, val usize: Long, val lho: Long)

    private fun open(url: String, from: Long, to: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Range", "bytes=$from-$to")
            setRequestProperty("User-Agent", "MicMask")
        }

    private fun range(url: String, from: Long, len: Int): ByteArray {
        val c = open(url, from, from + len - 1)
        try {
            if (c.responseCode != 206) throw IllegalStateException("不支持分段下载（HTTP ${c.responseCode}）")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun totalSize(url: String): Long {
        val c = open(url, 0, 0)
        try {
            if (c.responseCode != 206) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: throw IllegalStateException("没有 Content-Range")
        } finally { c.disconnect() }
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    /** 读 zip 中央目录：文件名（去掉路径）→ 条目 */
    private fun centralDir(url: String): Map<String, Entry> {
        val size = totalSize(url)
        val tailLen = minOf(size, 65_557L).toInt()
        val tail = range(url, size - tailLen, tailLen)
        var p = tailLen - 22
        while (p >= 0 && u32(tail, p) != 0x06054b50L) p--
        if (p < 0) throw IllegalStateException("不是 zip")
        val cdSize = u32(tail, p + 12); val cdOff = u32(tail, p + 16)
        val cd = range(url, cdOff, cdSize.toInt())
        val m = HashMap<String, Entry>()
        var q = 0
        while (q + 46 <= cd.size && u32(cd, q) == 0x02014b50L) {
            val method = u16(cd, q + 10); val cs = u32(cd, q + 20); val us = u32(cd, q + 24)
            val nl = u16(cd, q + 28); val xl = u16(cd, q + 30); val cl = u16(cd, q + 32); val lho = u32(cd, q + 42)
            val name = String(cd, q + 46, nl, Charsets.UTF_8)
            if (name.startsWith("jni/arm64-v8a/")) { val n = name.substringAfterLast('/'); m[n] = Entry(n, method, cs, us, lho) }
            q += 46 + nl + xl + cl
        }
        return m
    }

    private fun dataStart(url: String, e: Entry): Long {
        val h = range(url, e.lho, 30)
        if (u32(h, 0) != 0x04034b50L) throw IllegalStateException("本地文件头不对")
        return e.lho + 30 + u16(h, 26) + u16(h, 28)
    }

    /** 分块（4 MB）下载 [start, start+len) 到 part，已有部分续传；每块最多重试 3 次 */
    private fun fetchTo(url: String, start: Long, len: Long, part: File, prog: (Long) -> Unit) {
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() > len) raf.setLength(0)
            var got = raf.length()
            raf.seek(got)
            val buf = ByteArray(64 * 1024)
            while (got < len) {
                if (cancel) throw InterruptedException("已取消")
                val end = minOf(len, got + 4L * 1024 * 1024)
                var ok = false; var err: Throwable? = null
                for (attempt in 0 until 3) {
                    try {
                        val c = open(url, start + got, start + end - 1)
                        try {
                            if (c.responseCode != 206) throw IllegalStateException("HTTP ${c.responseCode}")
                            c.inputStream.use { ins ->
                                while (got < end) {
                                    if (cancel) throw InterruptedException("已取消")
                                    val k = ins.read(buf, 0, minOf(buf.size.toLong(), end - got).toInt())
                                    if (k < 0) break
                                    raf.write(buf, 0, k); got += k; prog(got)
                                }
                            }
                        } finally { c.disconnect() }
                        if (got >= end) { ok = true; break }
                    } catch (t: InterruptedException) { throw t } catch (t: Throwable) { err = t; Thread.sleep(500L * (attempt + 1)) }
                }
                if (!ok) throw err ?: IllegalStateException("下载中断")
            }
        }
    }

    private fun inflateTo(part: File, e: Entry, out: File) {
        val tmp = File(out.path + ".tmp")
        part.inputStream().buffered().use { ins ->
            tmp.outputStream().buffered().use { os ->
                if (e.method == 0) ins.copyTo(os)
                else if (e.method == 8) {
                    val inf = Inflater(true); val ib = ByteArray(64 * 1024); val ob = ByteArray(256 * 1024)
                    try {
                        while (!inf.finished()) {
                            if (inf.needsInput()) { val k = ins.read(ib); if (k < 0) break; inf.setInput(ib, 0, k) }
                            val n = inf.inflate(ob); if (n > 0) os.write(ob, 0, n)
                        }
                    } finally { inf.end() }
                } else throw IllegalStateException("不支持的压缩方式 ${e.method}")
            }
        }
        if (tmp.length() != e.usize) { tmp.delete(); throw IllegalStateException("${e.name} 解压后大小不符") }
        out.delete(); if (!tmp.renameTo(out)) throw IllegalStateException("写入 ${out.name} 失败")
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val b = ByteArray(256 * 1024); while (true) { val k = ins.read(b); if (k < 0) break; md.update(b, 0, k) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ shell 侧

    const val SHELL_ROOT = "/data/local/tmp/micmask-models"

    /**
     * 注入进程用：返回可加载的 QNN 目录（/data/local/tmp/micmask-models/qnn-<版本>-<架构>）。
     * 没有就从 App 外部目录复制并逐个校验；App 没下载过或本机不是已知骁龙返回 null。
     */
    fun shellDir(pkg: String?, root: String = SHELL_ROOT): File? {
        val arch = deviceArch() ?: return null
        val dst = File(root, dirName(arch))
        if (installed(dst, arch)) return dst
        val srcs = listOfNotNull(pkg, "com.taocrypt.micmask").distinct().map { File("/storage/emulated/0/Android/data/$it/files", dirName(arch)) }
        val src = srcs.firstOrNull { installed(it, arch) } ?: return null
        return runCatching {
            dst.deleteRecursively(); dst.mkdirs()
            for (n in files(arch)) {
                val o = File(dst, n)
                File(src, n).copyTo(o, overwrite = true)
                if (sha256(o) != SHA256[n]) throw SecurityException("$n 复制后校验不符")
                o.setReadable(true, false)
            }
            File(src, MARK).copyTo(File(dst, MARK), overwrite = true)
            L.i("QNN 库已从 ${src.path} 复制到 ${dst.path}")
            dst
        }.onFailure { L.e("QNN 库复制失败", it); dst.deleteRecursively() }.getOrNull()
    }
}
