package com.taocrypt.micmask.repo

import android.content.Context
import com.taocrypt.micmask.L
import com.taocrypt.micmask.ModelStore
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 在线模型仓库。
 *
 * 仓库根目录放一个 models.json（清单），格式：
 * {
 *   "version": 1,
 *   "base": [ {"name":"hubert_dyn8.onnx","url":"base/hubert_dyn8.onnx","sha256":"…","size":122470000} , … ],
 *   "models": [
 *     {"id":"myvoice","name":"我的音色","author":"…","desc":"…","license":"…","sr":48000,
 *      "files":[ {"name":"myvoice_stream_b50_qdq.onnx","url":"myvoice/myvoice_stream_b50_qdq.onnx","sha256":"…","size":41800000} ]}
 *   ]
 * }
 * url 可写绝对地址，也可写相对清单所在目录的路径（GitHub raw / HF resolve 都行）。
 * base = 所有音色共用的特征模型（hubert/rmvpe），缺了才下。
 * 大文件可写 "parts":[url…]（按顺序拼接，sha256/size 是拼好后的整体），url 仍填第一片以兼容旧版。
 * 下载先写 .part，校验 sha256（清单里有才校验）后改名，落到 ModelStore.internalDir。
 */
object ModelRepo {

    data class RFile(val name: String, val url: String, val sha256: String, val size: Long, val parts: List<String> = emptyList())
    data class RModel(
        val id: String, val name: String, val author: String, val desc: String,
        val license: String, val files: List<RFile>,
        /** 声线分类：info.json / 清单里的 "gender"：female / male，其余都算 other */
        val gender: String = "other",
        /** 来自哪个仓库（地址，fetchAll 填） */
        val repo: String = "",
    ) {
        val totalSize: Long get() = files.sumOf { it.size }
    }
    /** 语音包素材：清单 "sounds":[{"pack":"综艺","name":"掌声","url":"sounds/综艺/掌声.mp3","sha256":"…","size":123}] */
    data class RSound(val pack: String, val name: String, val url: String, val sha256: String, val size: Long) {
        val id: String get() = "$pack/$name"
    }
    /** TTS（GPT-SoVITS）文件：清单 "tts":[{"name":"bert.onnx","path":"bert.onnx"|"ref/ref_joy.refbin","url":"tts/…","sha256":"…","size":1}] */
    data class RTts(val path: String, val url: String, val sha256: String, val size: Long, val parts: List<String> = emptyList())
    data class Manifest(val base: List<RFile>, val models: List<RModel>, val sounds: List<RSound> = emptyList(), val tts: List<RTts> = emptyList(),
                        /** 文件变声（句级）：完整合成器 + 检索索引，清单 "file":[…] */
                        val file: List<RFile> = emptyList(),
                        /** fetchAll：拿不到的仓库及原因 */
                        val errors: List<String> = emptyList()) {
        val ttsSize: Long get() = tts.sumOf { it.size }
    }

    /** 用户填的仓库地址 → 清单 URL。支持直接给 models.json，或给 GitHub 仓库主页。 */
    fun manifestUrl(input: String): String {
        val s = input.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (s.endsWith(".json")) return s
        // https://cnb.cool|cnb.build/group/repo[/-/tree/branch] → raw（CNB 国内/国际）
        Regex("^(?:https?://)?(cnb\\.(?:cool|build))/(.+?)(?:\\.git)?(?:/-/tree/([^/]+))?$").matchEntire(s)?.let { m ->
            val br = m.groupValues[3].ifEmpty { "main" }
            return "https://${m.groupValues[1]}/${m.groupValues[2]}/-/git/raw/$br/models.json"
        }
        // https://github.com/owner/repo[/tree/branch] → raw
        Regex("^https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?(?:/tree/([^/]+))?$").matchEntire(s)?.let { m ->
            val br = m.groupValues[3].ifEmpty { "main" }
            return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/$br/models.json"
        }
        // owner/repo 简写
        Regex("^([\\w.-]+)/([\\w.-]+)$").matchEntire(s)?.let { m ->
            return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/main/models.json"
        }
        return "$s/models.json"
    }

    /** 中文路径等非 ASCII 字符转成 %XX */
    private fun ascii(url: String): URL = URL(url).let { u ->
        URL(java.net.URI(u.protocol, u.userInfo, u.host, u.port, java.net.URLDecoder.decode(u.path.replace("+", "%2B"), "UTF-8"), u.query, null).toASCIIString())
    }

    /** 下载到的是 Git LFS 指针而不是文件本体 */
    private fun checkLfs(f: File) {
        if (f.length() in 1..1024 && f.readText(Charsets.UTF_8).startsWith("version https://git-lfs"))
            throw java.io.IOException("${f.name} 在仓库里是 LFS 指针，raw 地址拿不到本体；请把这个文件放到 Release 附件，清单里写绝对地址")
    }

    private fun open(url: String): HttpURLConnection {
        var u = ascii(url)
        repeat(6) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "MicMask")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw java.io.IOException("重定向无地址")
                c.disconnect(); u = URL(u, loc); return@repeat
            }
            if (code != 200) { c.disconnect(); throw java.io.IOException("HTTP $code：$u") }
            return c
        }
        throw java.io.IOException("重定向次数过多")
    }

    /**
     * 多仓库：逐个拉清单后合并。音色按 id、素材按 pack/name、base/文件变声/TTS 按文件名去重，靠前的仓库优先。
     * 单个仓库失败不影响其它，原因放进 errors；全部失败才抛异常。
     */
    fun fetchAll(inputs: List<String>): Manifest {
        val ok = ArrayList<Manifest>(); val err = ArrayList<String>()
        for (u in inputs) runCatching { fetch(u).let { m -> m.copy(models = m.models.map { it.copy(repo = u) }) } }.onSuccess { ok += it }.onFailure {
            L.w("模型仓库 $u 获取失败：${it.message}"); err += (u.substringAfter("://").take(40) + "：" + (it.message ?: "未知错误"))
        }
        if (ok.isEmpty()) throw java.io.IOException(err.joinToString("；"))
        fun <T, K> merge(sel: (Manifest) -> List<T>, key: (T) -> K) = ok.flatMap(sel).distinctBy(key)
        return Manifest(base = merge({ it.base }, { it.name }), models = merge({ it.models }, { it.id }),
            sounds = merge({ it.sounds }, { it.id }), tts = merge({ it.tts }, { it.path }), file = merge({ it.file }, { it.name }), errors = err)
    }

    fun fetch(repoInput: String): Manifest {
        val mu = manifestUrl(repoInput)
        require(mu.isNotEmpty()) { "还没填模型仓库地址" }
        val text = open(mu).let { c -> c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.also { c.disconnect() } }
        val root = JSONObject(text)
        val baseDir = mu.substringBeforeLast('/') + "/"
        // 相对路径按清单所在目录解析；"/…" 按清单所在站点解析（同一份清单国内 cnb.cool / 国际 cnb.build 通用）
        fun abs(raw: String): String = if (raw.startsWith("http")) raw else URL(URL(mu), raw).toString()
        fun parts(o: JSONObject): List<String> = o.optJSONArray("parts")?.let { a -> (0 until a.length()).map { abs(a.getString(it)) } } ?: emptyList()
        fun files(a: org.json.JSONArray?): List<RFile> = if (a == null) emptyList() else (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val raw = o.getString("url")
            RFile(o.getString("name"), abs(raw),
                o.optString("sha256", ""), o.optLong("size", 0L), parts(o))
        }
        val ms = root.optJSONArray("models")
        val models = if (ms == null) emptyList() else (0 until ms.length()).map { i ->
            val o = ms.getJSONObject(i)
            RModel(o.getString("id"), o.optString("name", o.getString("id")), o.optString("author", ""),
                o.optString("desc", ""), o.optString("license", ""), files(o.optJSONArray("files")),
                o.optString("gender", "").lowercase().let { if (it == "female" || it == "male") it else "other" })
        }
        val ss = root.optJSONArray("sounds")
        val sounds = if (ss == null) emptyList() else (0 until ss.length()).map { i ->
            val o = ss.getJSONObject(i); val raw = o.getString("url")
            RSound(o.optString("pack", "仓库"), o.optString("name", raw.substringAfterLast('/').substringBeforeLast('.')),
                abs(raw), o.optString("sha256", ""), o.optLong("size", 0L))
        }
        val ta = root.optJSONArray("tts")
        val tts = if (ta == null) emptyList() else (0 until ta.length()).map { i ->
            val o = ta.getJSONObject(i); val raw = o.getString("url")
            RTts(o.optString("path", o.optString("name")), abs(raw),
                o.optString("sha256", ""), o.optLong("size", 0L), parts(o))
        }
        L.i("模型仓库 $mu：${models.size} 个音色，基础文件 ${root.optJSONArray("base")?.length() ?: 0} 个，素材 ${sounds.size} 个，TTS ${tts.size} 个")
        return Manifest(files(root.optJSONArray("base")), models, sounds, tts, files(root.optJSONArray("file")))
    }

    /** 本地是否已装该音色（至少一个合成器文件在） */
    fun installed(ctx: Context, m: RModel): Boolean =
        m.files.any { ModelStore.locate(ctx, it.name) != null }

    /**
     * 下载一个音色（连同缺失的基础文件）。[progress] 回调 0..1。
     * 只认合法文件名（ModelStore 认识的或 <音色>_stream_…onnx），防止清单写出奇怪路径。
     */
    fun download(ctx: Context, man: Manifest, m: RModel, progress: (Float, String) -> Unit) {
        val need = man.base.filter { ModelStore.locate(ctx, it.name) == null } + m.files
        for (f in need) {
            val okName = f.name == f.name.substringAfterLast('/') &&
                (f.name in ModelStore.required || f.name in ModelStore.optional || ModelStore.VOICE_RE.matches(f.name))
            require(okName) { "清单里的文件名不合法：${f.name}" }
        }
        val total = need.sumOf { it.size }.coerceAtLeast(1L)
        var done = 0L
        val dir = ModelStore.internalDir(ctx).also { it.mkdirs() }
        for (f in need) {
            val dst = File(dir, f.name)
            if (dst.exists() && f.size > 0 && dst.length() == f.size && (f.sha256.isEmpty() || sha256(dst) == f.sha256.lowercase())) {
                done += f.size; progress(done.toFloat() / total, f.name); continue
            }
            val tmp = File(dir, f.name + ".part")
            val md = MessageDigest.getInstance("SHA-256")
            var lastTick = 0L
            stream(f.parts.ifEmpty { listOf(f.url) }, tmp, md) { n ->
                done += n
                val now = System.currentTimeMillis()
                if (now - lastTick > 200) { lastTick = now; progress((done.toFloat() / total).coerceAtMost(1f), f.name) }
            }
            checkLfs(tmp)
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (f.sha256.isNotEmpty() && hex != f.sha256.lowercase()) {
                tmp.delete(); throw java.io.IOException("${f.name} 校验失败（sha256 不符）")
            }
            dst.delete()
            if (!tmp.renameTo(dst)) throw java.io.IOException("改名失败：${f.name}")
            ModelStore.externalDir(ctx).let { File(it, f.name).delete() }
            L.i("仓库下载完成 ${f.name} ${dst.length() / 1048576}MB")
        }
        progress(1f, "")
    }

    /** 下载文件变声（句级）模型：合成器 + 索引 + 缺失的基础文件 */
    fun downloadFileModel(ctx: Context, man: Manifest, progress: (Float, String) -> Unit) {
        require(man.file.isNotEmpty()) { "仓库里没有句级模型" }
        download(ctx, man, RModel("file", "句级模型", "", "", "", man.file), progress)
    }

    /** TTS 文件名白名单（含 ref/ 子目录），防止清单写出奇怪路径 */
    private val TTS_RE = Regex("^(?:[\\w.]+\\.(?:onnx|data|txt)|ref/[\\w.]+\\.(?:refbin|txt))$")

    /** 下载全部 TTS 文件到 [dst]（缺的、大小或校验不符的才下）。 */
    fun downloadTts(man: Manifest, dst: File, progress: (Float, String) -> Unit) {
        require(man.tts.isNotEmpty()) { "仓库里没有 TTS 模型" }
        man.tts.forEach { require(TTS_RE.matches(it.path)) { "清单里的 TTS 路径不合法：${it.path}" } }
        val total = man.ttsSize.coerceAtLeast(1L)
        var done = 0L
        for (f in man.tts) {
            val out = File(dst, f.path).also { it.parentFile?.mkdirs() }
            if (out.exists() && f.size > 0 && out.length() == f.size && (f.sha256.isEmpty() || sha256(out) == f.sha256.lowercase())) {
                done += f.size; progress(done.toFloat() / total, f.path); continue
            }
            val tmp = File(out.path + ".part")
            val md = MessageDigest.getInstance("SHA-256")
            var last = 0L
            stream(f.parts.ifEmpty { listOf(f.url) }, tmp, md) { n ->
                done += n
                val now = System.currentTimeMillis()
                if (now - last > 200) { last = now; progress((done.toFloat() / total).coerceAtMost(1f), f.path) }
            }
            checkLfs(tmp)
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (f.sha256.isNotEmpty() && hex != f.sha256.lowercase()) { tmp.delete(); throw java.io.IOException("${f.path} 校验失败（sha256 不符）") }
            out.delete()
            if (!tmp.renameTo(out)) throw java.io.IOException("改名失败：${f.path}")
            L.i("TTS 下载完成 ${f.path} ${out.length() / 1048576}MB")
        }
        progress(1f, "")
    }

    /** 下载一个素材并解码进语音包 */
    fun downloadSound(ctx: Context, s: RSound): com.taocrypt.micmask.SoundBoard.Sound {
        val tmp = File(ctx.cacheDir, "sfx_${System.currentTimeMillis()}.${s.url.substringAfterLast('.').take(5)}")
        val md = MessageDigest.getInstance("SHA-256")
        val c = open(s.url)
        try { c.inputStream.use { ins -> tmp.outputStream().use { out ->
            val b = ByteArray(1 shl 16); var tot = 0L
            while (true) { val n = ins.read(b); if (n < 0) break; out.write(b, 0, n); md.update(b, 0, n); tot += n
                if (tot > 200L shl 20) throw java.io.IOException("素材过大") }
        } } } finally { c.disconnect() }
        try {
            checkLfs(tmp)
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (s.sha256.isNotEmpty() && hex != s.sha256.lowercase()) throw java.io.IOException("${s.name} 校验失败")
            return if (tmp.name.endsWith(".mim3", true)) com.taocrypt.micmask.SoundBoard.importMim3(ctx, tmp, s.name).first()
                   else com.taocrypt.micmask.SoundBoard.importFile(ctx, tmp, s.pack, s.name)
        } finally { tmp.delete() }
    }

    /** 依次下载 [urls]（分片按顺序拼接）写进 [tmp]，同时算整体摘要；每个分片先检查不是 LFS 指针。 */
    private fun stream(urls: List<String>, tmp: File, md: MessageDigest, onBytes: (Int) -> Unit) {
        tmp.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            for (u in urls) {
                val c = open(u)
                try {
                    c.inputStream.use { ins ->
                        var first = true
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            if (first) {
                                first = false
                                if (n >= 23 && String(buf, 0, 23, Charsets.UTF_8) == "version https://git-lfs")
                                    throw java.io.IOException("${tmp.name.removeSuffix(".part")} 下到的是 LFS 指针，仓库里这个对象还没上传")
                            }
                            out.write(buf, 0, n); md.update(buf, 0, n); onBytes(n)
                        }
                    }
                } finally { c.disconnect() }
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val b = ByteArray(1 shl 16); while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
