package com.taocrypt.micmask

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteOrder

/**
 * 语音包：音乐/音效素材。导入时统一解码成 48k 单声道 PCM16LE（.pcm），
 * 播放时把文件 fd 交给注入进程，在效果链之后混进麦克风通道。
 * 目录 files/sounds/<分组>/<名字>.pcm；分组 = 包名（本地导入的是「本地」）。
 */
object SoundBoard {
    const val SR = 48000
    const val MAX_SEC = 600
    const val PACK_FX = "效果包"
    const val PACK_LOCAL = "本地"
    /** 内置效果包版本：资源里 assets/sfx 里的 mp3 变了就加 1，会补装新增的 */
    private const val BUILTIN_VER = 2
    private val ORDER = listOf("大笑", "鼓掌", "欢呼", "唏嘘", "哇哦", "叮", "失败", "冷笑话", "乌鸦", "答错", "胜利", "打鼓", "喇叭", "爆炸", "疑问", "惊讶", "叹气", "心跳", "巴掌", "吞口水", "口哨", "猫叫", "狗叫", "金币")

    fun isFx(s: Sound) = s.pack != PACK_LOCAL

    /** 首次启动把 assets/sfx 里的主播常用音效解码进「效果包」；已删除的不再补回 */
    fun installBuiltins(ctx: Context) {
        val sp = Prefs.main(ctx)
        if (sp.getInt("sfx_builtin_ver", 0) >= BUILTIN_VER) return
        val names = runCatching { ctx.assets.list("sfx")?.toList() }.getOrNull().orEmpty().filter { it.endsWith(".mp3") || it.endsWith(".mim3") }
            .sortedBy { ORDER.indexOf(it.substringBeforeLast('.')).let { i -> if (i < 0) 99 else i } }
        val done = sp.getStringSet("sfx_builtin_done", emptySet())!!.toMutableSet()
        for (a in names) {
            val n = a.substringBeforeLast('.')
            if (n in done) continue
            runCatching {
                val tmp = File(ctx.cacheDir, "sfx_tmp.mp3")
                ctx.assets.open("sfx/$a").use { i -> tmp.outputStream().use { i.copyTo(it) } }
                importFile(ctx, tmp, PACK_FX, n); tmp.delete()
                done += n
            }.onFailure { L.e("内置效果包 $a 解码失败", it) }
        }
        sp.edit().putStringSet("sfx_builtin_done", done).putInt("sfx_builtin_ver", BUILTIN_VER).apply()
    }

    /** 效果包只认 .mim3 */
    fun isMim3(ctx: Context, uri: Uri): Boolean =
        runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }
            .getOrNull()?.lowercase()?.endsWith(".mim3") == true

    // ---------------------------------------------------------------- .mim3 效果包
    /**
     * .mim3 = zip：根目录 manifest.json + 音频文件（mp3 为主，也认 ogg/m4a/wav/flac）。
     * manifest.json 示例：
     * {"format":"mim3","version":1,"group":"主播常用","author":"某某","description":"…","created":"2026-11-19",
     *  "sounds":[{"file":"大笑.mp3","name":"大笑"}]}
     * group 决定导入后的分组；sounds 缺省时按 zip 里的音频文件名导入。
     * 兼容旧版：不是 zip 的 .mim3 当单个 mp3 导入「效果包」分组。
     */
    data class PackInfo(val group: String, val author: String = "", val description: String = "", val count: Int = 0)
    private val AUDIO_EXT = Regex("\\.(mp3|ogg|m4a|aac|wav|flac|opus)$", RegexOption.IGNORE_CASE)
    private const val META = ".pack.json"

    private fun isZip(f: File) = runCatching { f.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() } }.getOrDefault(false)

    /** 导入一个 .mim3 文件（zip 或旧版裸 mp3），返回导入的素材 */
    fun importMim3(ctx: Context, src: File, fallbackName: String): List<Sound> {
        if (!isZip(src)) return listOf(importFile(ctx, src, PACK_FX, fallbackName))
        val work = File(ctx.cacheDir, "mim3_${System.nanoTime()}").also { it.mkdirs() }
        try {
            var manifest: org.json.JSONObject? = null
            val files = HashMap<String, File>()
            var total = 0L
            java.util.zip.ZipInputStream(src.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val base = e.name.substringAfterLast('/')
                    if (base.startsWith(".") || e.name.contains("..")) continue
                    if (base.equals("manifest.json", true)) {
                        manifest = org.json.JSONObject(z.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF"))
                    } else if (AUDIO_EXT.containsMatchIn(base)) {
                        val f = File(work, "${files.size}_" + safe(base))
                        f.outputStream().use { o -> val b = ByteArray(1 shl 16); while (true) { val n = z.read(b); if (n < 0) break; total += n
                            if (total > 300L shl 20) throw java.io.IOException("音效包过大"); o.write(b, 0, n) } }
                        files[base] = f
                    }
                }
            }
            val m = manifest ?: throw IllegalArgumentException("音效包缺少 manifest.json")
            if (m.optString("format", "mim3") != "mim3") throw IllegalArgumentException("不是 mim3 声明文件")
            if (m.optInt("version", 1) > 1) throw IllegalArgumentException("音效包版本过新，请更新 MicMask")
            val group = safe(m.optString("group").ifBlank { m.optString("name").ifBlank { fallbackName } })
            require(group != PACK_LOCAL) { "分组名不能叫「本地」" }
            val list = m.optJSONArray("sounds")
            val pairs = ArrayList<Pair<File, String>>()
            if (list != null && list.length() > 0) {
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val fn = o.optString("file"); val f = files[fn.substringAfterLast('/')] ?: run { L.w("mim3 缺文件 $fn"); null } ?: continue
                    pairs += f to o.optString("name").ifBlank { fn.substringBeforeLast('.') }
                }
            } else files.forEach { (k, f) -> pairs += f to k.substringBeforeLast('.') }
            require(pairs.isNotEmpty()) { "音效包里没有音频" }
            val out = pairs.mapNotNull { (f, n) -> runCatching { importFile(ctx, f, group, n) }.onFailure { L.e("mim3 解码 $n 失败", it) }.getOrNull() }
            require(out.isNotEmpty()) { "音效包里的音频都无法解码" }
            runCatching {
                val meta = org.json.JSONObject().put("group", group).put("author", m.optString("author")).put("description", m.optString("description"))
                    .put("created", m.optString("created")).put("version", m.optInt("version", 1))
                File(File(dir(ctx), group), META).writeText(meta.toString())
            }
            L.i("mim3 导入 分组=$group 作者=${m.optString("author")} 共 ${out.size}/${pairs.size} 个")
            return out
        } finally { work.deleteRecursively() }
    }

    fun importMim3Uri(ctx: Context, uri: Uri): List<Sound> {
        val tmp = File(ctx.cacheDir, "in_${System.nanoTime()}.mim3")
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            return importMim3(ctx, tmp, safe(displayName(ctx, uri)))
        } finally { tmp.delete() }
    }

    /** 分组信息（作者、简介），没有声明文件返回 null */
    fun packInfo(ctx: Context, group: String): PackInfo? = runCatching {
        val f = File(File(dir(ctx), group), META); if (!f.exists()) return null
        val o = org.json.JSONObject(f.readText())
        PackInfo(group, o.optString("author"), o.optString("description"))
    }.getOrNull()

    data class PackItem(val uri: Uri, val name: String, val ext: String)

    /** 把选中的音频原样（不转码）打包成 .mim3，写到 下载/MicMask/<分组>.mim3，返回路径 */
    fun buildMim3(ctx: Context, group: String, author: String, desc: String, items: List<PackItem>): String {
        val g = safe(group); require(g != PACK_LOCAL) { "分组名不能叫「本地」" }
        require(items.isNotEmpty()) { "至少选一个音频" }
        val used = HashSet<String>()
        val arr = org.json.JSONArray()
        val entries = items.map { it ->
            var fn = safe(it.name) + "." + it.ext.lowercase(); var k = 2
            while (!used.add(fn.lowercase())) fn = safe(it.name) + " ($k)." + it.ext.lowercase().also { k++ }
            arr.put(org.json.JSONObject().put("file", fn).put("name", safe(it.name)))
            fn to it.uri
        }
        val m = org.json.JSONObject().put("format", "mim3").put("version", 1).put("group", g).put("author", author.trim())
            .put("description", desc.trim()).put("created", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
            .put("app", "MicMask " + BuildConfig.VERSION_NAME).put("sounds", arr)
        val name = writeMim3(ctx, g, m, entries.map { (fn, u) -> fn to { z: java.io.OutputStream -> ctx.contentResolver.openInputStream(u)!!.use { it.copyTo(z) }; Unit } })
        L.i("mim3 打包 分组=$g 作者=$author 共 ${entries.size} 个 → 下载/MicMask/$name")
        return "下载/MicMask/$name"
    }

    /** 写 .mim3（zip）到 下载/MicMask/，返回实际文件名 */
    private fun writeMim3(ctx: Context, g: String, m: org.json.JSONObject, entries: List<Pair<String, (java.io.OutputStream) -> Unit>>): String {
        var name = "$g.mim3"
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/MicMask")
        }
        val out = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("无法在下载目录创建文件")
        try {
            ctx.contentResolver.openOutputStream(out)!!.use { os ->
                java.util.zip.ZipOutputStream(os).use { z ->
                    z.putNextEntry(java.util.zip.ZipEntry("manifest.json")); z.write(m.toString(1).toByteArray()); z.closeEntry()
                    for ((fn, w) in entries) { z.putNextEntry(java.util.zip.ZipEntry(fn)); w(z); z.closeEntry() }
                }
            }
        } catch (t: Throwable) { runCatching { ctx.contentResolver.delete(out, null, null) }; throw t }
        runCatching { ctx.contentResolver.query(out, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) } }
        return name
    }

    /** 把已导入的一个分组导出成 .mim3（音频以 48k 单声道 WAV 存放），返回路径 */
    fun exportGroup(ctx: Context, group: String, author: String = "", desc: String = ""): String {
        val list = list(ctx).filter { it.pack == group }
        require(list.isNotEmpty()) { "这个分组是空的" }
        val info = packInfo(ctx, group)
        val arr = org.json.JSONArray()
        val used = HashSet<String>()
        val entries = list.map { so ->
            var fn = safe(so.name) + ".wav"; var k = 2
            while (!used.add(fn.lowercase())) fn = safe(so.name) + " ($k).wav".also { k++ }
            arr.put(org.json.JSONObject().put("file", fn).put("name", so.name))
            fn to { z: java.io.OutputStream ->
                val n = so.file.length().toInt()
                val hd = java.nio.ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                hd.put("RIFF".toByteArray()).putInt(36 + n).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(1).putInt(SR).putInt(SR * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(n)
                z.write(hd.array()); so.file.inputStream().use { it.copyTo(z) }; Unit
            }
        }
        val g = if (group == PACK_FX) "常用音效" else group
        val m = org.json.JSONObject().put("format", "mim3").put("version", 1).put("group", g)
            .put("author", author.ifBlank { info?.author.orEmpty() }).put("description", desc.ifBlank { info?.description.orEmpty() })
            .put("created", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
            .put("app", "MicMask " + BuildConfig.VERSION_NAME).put("sounds", arr)
        val name = writeMim3(ctx, safe(g), m, entries)
        L.i("mim3 导出 分组=$group 共 ${entries.size} 个 → 下载/MicMask/$name")
        return "下载/MicMask/$name"
    }

    /** 音效包里已有的分组（用于「添加音效」选择分组） */
    fun fxGroups(ctx: Context): List<String> = list(ctx).map { it.pack }.filter { it != PACK_LOCAL }.distinct()

    fun audioExt(ctx: Context, uri: Uri): String {
        val n = runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull().orEmpty()
        val e = n.substringAfterLast('.', "").lowercase()
        if (e in setOf("mp3", "ogg", "m4a", "aac", "wav", "flac", "opus")) return e
        return when (ctx.contentResolver.getType(uri)) { "audio/mpeg" -> "mp3"; "audio/ogg" -> "ogg"; "audio/mp4", "audio/aac" -> "m4a"; "audio/wav", "audio/x-wav" -> "wav"; "audio/flac" -> "flac"; else -> "mp3" }
    }

    data class Sound(val pack: String, val name: String, val file: File) {
        val id: String get() = "$pack/$name"
        val durMs: Long get() = file.length() / 2 * 1000 / SR
    }

    fun dir(ctx: Context) = File(ctx.filesDir, "sounds").also { it.mkdirs() }

    fun list(ctx: Context): List<Sound> =
        (dir(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { if (it.name == "本地") "" else it.name }
            .flatMap { d -> (d.listFiles() ?: emptyArray()).filter { it.name.endsWith(".pcm") }
                .sortedWith(compareBy<File>({ ORDER.indexOf(it.name.removeSuffix(".pcm")).let { i -> if (i < 0) 99 else i } }, { it.lastModified() }))
                .map { Sound(d.name, it.name.removeSuffix(".pcm"), it) } }

    fun find(ctx: Context, id: String): Sound? = list(ctx).firstOrNull { it.id == id }

    fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(40).ifEmpty { "素材" }

    fun displayName(ctx: Context, uri: Uri): String =
        runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }
            .getOrNull()?.substringBeforeLast('.') ?: "素材"

    /** 从 SAF 导入；返回保存的素材 */
    fun importUri(ctx: Context, uri: Uri, pack: String = PACK_LOCAL): Sound {
        val name = safe(displayName(ctx, uri))
        val pcm = ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { decode(it.fileDescriptor, null) }
        return save(ctx, pack, name, pcm)
    }

    fun importFile(ctx: Context, src: File, pack: String, name: String): Sound {
        val pcm = decode(null, src.absolutePath)
        return save(ctx, safe(pack), safe(name), pcm)
    }

    private fun save(ctx: Context, pack: String, name: String, pcm: ShortArray): Sound {
        require(pcm.size > SR / 20) { "解码后没有声音" }
        val d = File(dir(ctx), safe(pack)).also { it.mkdirs() }
        var f = File(d, "$name.pcm"); var k = 2
        while (f.exists()) f = File(d, "$name ($k).pcm").also { k++ }
        val bb = java.nio.ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pcm)
        f.writeBytes(bb.array())
        L.i("语音包 导入 ${f.parentFile?.name}/${f.name} ${pcm.size * 1000L / SR}ms")
        return Sound(d.name, f.name.removeSuffix(".pcm"), f)
    }

    fun delete(s: Sound) { s.file.delete(); s.file.parentFile?.let { d -> if (d.list()?.none { it.endsWith(".pcm") } == true) d.deleteRecursively() } }

    fun rename(s: Sound, newName: String): Boolean {
        val dst = File(s.file.parentFile, safe(newName) + ".pcm")
        return !dst.exists() && s.file.renameTo(dst)
    }

    /** 任意 Android 能解的音频 → 48k mono PCM16（最长 MAX_SEC 秒） */
    fun decode(fd: FileDescriptor?, path: String?): ShortArray {
        val ex = MediaExtractor()
        if (fd != null) ex.setDataSource(fd) else ex.setDataSource(path!!)
        try {
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalArgumentException("不是音频文件")
            ex.selectTrack(ti)
            val inFmt = ex.getTrackFormat(ti)
            val codec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(inFmt, null, null, 0)
            codec.start()
            var sr = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var mono = FloatArray(1 shl 18); var n = 0
            val info = MediaCodec.BufferInfo()
            var inEos = false; var outEos = false
            val maxIn = MAX_SEC.toLong() * 192000
            try {
                while (!outEos) {
                    if (!inEos) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val buf = codec.getInputBuffer(ii)!!
                            val sz = ex.readSampleData(buf, 0)
                            if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                            else { codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (oi >= 0) {
                        val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val frames = if (isFloat) info.size / 4 / ch else info.size / 2 / ch
                        if (n + frames > mono.size) mono = mono.copyOf(maxOf(mono.size * 2, n + frames))
                        if (isFloat) { val fb = ob.asFloatBuffer(); for (i in 0 until frames) { var s = 0f; for (c in 0 until ch) s += fb.get(); mono[n++] = s / ch } }
                        else { val sb = ob.asShortBuffer(); for (i in 0 until frames) { var s = 0f; for (c in 0 until ch) s += sb.get() / 32768f; mono[n++] = s / ch } }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                        if (n.toLong() * 4 > maxIn) outEos = true
                    }
                }
            } finally { runCatching { codec.stop() }; codec.release() }
            // 线性插值重采样到 48k
            val outN = (n.toLong() * SR / sr).toInt().coerceAtMost(SR * MAX_SEC)
            val step = sr.toDouble() / SR
            var peak = 1e-6f
            for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(mono[i]))
            val norm = if (peak > 0.99f) 0.99f / peak else 1f
            return ShortArray(outN) { i ->
                val x = i * step; val j = x.toInt(); val fr = (x - j).toFloat()
                val a = mono[j.coerceAtMost(n - 1)]; val b = mono[(j + 1).coerceAtMost(n - 1)]
                ((a + (b - a) * fr) * norm * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
        } finally { ex.release() }
    }
}
