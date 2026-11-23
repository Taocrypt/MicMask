package com.taocrypt.micmask

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import micmask.core.Resampler
import micmask.core.Wav
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * 音频输入输出。
 *
 * 输入：WAV 直接解析；mp3/m4a/aac/flac/ogg 走 MediaExtractor + MediaCodec 解码成 PCM。
 *       解码后统一下混为单声道，并重采样到 16k（HuBERT / RMVPE 的输入采样率）。
 * 输出：40k 单声道 WAV；优先落到系统「音乐/MicMask」，失败则落到应用外部私有目录。
 */
object AudioIo {

    const val TARGET_SR = 16000

    class Decoded(val samples: FloatArray, val sampleRate: Int, val originRate: Int, val sourceName: String)

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: "audio"
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "audio"
    }

    /** 解码任意可被系统解出的音频，返回 16k 单声道浮点 */
    fun decodeTo16k(ctx: Context, uri: Uri): Decoded {
        val name = displayName(ctx, uri)
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException(tr("无法读取所选文件"))

        // 1) WAV 自己解，采样率原样保留
        val isWav = bytes.size > 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"

        val raw: FloatArray
        val sr: Int
        if (isWav) {
            val d = Wav.read(bytes)
            raw = d.samples
            sr = d.sampleRate
        } else {
            val tmp = File(ctx.cacheDir, "in_${System.currentTimeMillis()}")
            tmp.writeBytes(bytes)
            try {
                val d = decodeWithCodec(tmp)
                raw = d.first
                sr = d.second
            } finally {
                tmp.delete()
            }
        }

        val mono16k = if (sr == TARGET_SR) raw else Resampler.convert(raw, sr, TARGET_SR)
        return Decoded(preprocess16k(mono16k), TARGET_SR, sr, name)
    }

    /**
     * 管线前端统一预处理：去直流 + 峰值归一化到 0.95。
     *
     * 录音和文件解码必须走同一套处理，否则同一个人「录进来」和「选文件进来」
     * 会得到不同幅度的特征，变声结果对不上。
     *
     * @param inPlace true 时直接改写传入数组（音频来自本地变量时省一次拷贝）
     * @param maxGain 增益上限。归一化本身是无脑放大到 0.95，遇到「几乎全是底噪」的
     *   录音（用户没出声、麦克风被静音）会把噪声拉满，再喂给 RVC 就是一段纯噪声。
     *   录音路径传一个上限（约 +30dB）把这种情况压住；文件路径不限制。
     */
    fun preprocess16k(
        samples: FloatArray,
        inPlace: Boolean = true,
        maxGain: Float = Float.MAX_VALUE,
    ): FloatArray {
        val out = if (inPlace) samples else samples.copyOf()
        var mean = 0.0
        for (v in out) mean += v
        mean /= max(1, out.size)
        var peak = 1e-6f
        for (v in out) {
            val a = abs(v - mean).toFloat()
            if (a > peak) peak = a
        }
        val gain = (0.95f / peak).coerceAtMost(maxGain)
        val meanF = mean.toFloat()
        for (i in out.indices) out[i] = (out[i] - meanF) * gain
        return out
    }

    /** MediaExtractor + MediaCodec 解出 PCM16 后转单声道 float */
    private fun decodeWithCodec(file: File): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i; format = f; break
            }
        }
        require(trackIndex >= 0 && format != null) { tr("文件里没有音频轨道") }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val out = ArrayList<Short>(1 shl 18)
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var srcBuf: ByteBuffer? = null

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val sz = extractor.readSampleData(inBuf, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val nf = codec.outputFormat
                sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (outIdx >= 0) {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    srcBuf = buf
                    val sb: ShortBuffer = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    while (sb.hasRemaining()) out.add(sb.get())
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }
        srcBuf?.clear()
        codec.stop(); codec.release(); extractor.release()

        val ch = max(1, channels)
        val frames = out.size / ch
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until ch) acc += out[f * ch + c] / 32768f
            mono[f] = acc / ch
        }
        return mono to sampleRate
    }

    /** 录音落盘目录：外部私有目录/recordings（外部不可用时退回内部） */
    fun recordingsDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "recordings").also { it.mkdirs() }

    /**
     * 只写 App 私有目录的 WAV（不进系统媒体库）。
     *
     * 用于录音这类「中间素材」：用户不需要在系统音乐 App 里看到几十条试录片段，
     * 但要能在应用内回放和再次作为输入。
     */
    fun saveLocalWav(
        ctx: Context,
        samples: FloatArray,
        sampleRate: Int,
        subDir: String,
        baseName: String,
        normalize: Boolean = true,
        maxGain: Float = Float.MAX_VALUE,
    ): File {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, subDir).also { it.mkdirs() }
        val f = File(dir, "$baseName.wav")
        val data = if (normalize) preprocess16k(samples.copyOf(), inPlace = true, maxGain = maxGain) else samples
        // 传 peak = 实际峰值，避免 Wav.write 内部再做一次归一化把 maxGain 的限制抹掉
        var mx = 1e-9f
        for (s in data) if (abs(s) > mx) mx = abs(s)
        Wav.write(f, data, sampleRate, peak = minOf(0.95f, mx))
        return f
    }

    /** 列出某个 App 子目录里的 wav，按修改时间倒序（新录的排前面） */
    fun listWavs(ctx: Context, subDir: String): List<File> {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, subDir)
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    class SaveResult(val file: File, val displayPath: String, val mediaUri: Uri?)

    /** 保存 40k 单声道 WAV。总是先落本地文件（用于播放/分享），再尝试复制进系统「音乐/MicMask」。 */
    fun saveWav(ctx: Context, samples: FloatArray, sampleRate: Int, baseName: String): SaveResult {
        val bytes = buildWav(samples, sampleRate, 0.95f)
        val fileName = "$baseName.wav"

        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "output").also { it.mkdirs() }
        val f = File(dir, fileName)
        f.writeBytes(bytes)

        var uri: Uri? = null
        var display = f.absolutePath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MicMask")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                }
                val u = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                if (u != null) {
                    ctx.contentResolver.openOutputStream(u)?.use { it.write(bytes) }
                    uri = u
                    display = tr("音乐/MicMask/{0}", fileName)
                }
            } catch (_: Exception) {
            }
        }
        return SaveResult(f, display, uri)
    }

    private fun buildWav(samples: FloatArray, sampleRate: Int, peak: Float): ByteArray {
        var maxAbs = 1e-9f
        for (s in samples) if (abs(s) > maxAbs) maxAbs = abs(s)
        val gain = peak / maxAbs
        val dataLen = samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII)); bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII)); bb.putInt(16)
        bb.putShort(1); bb.putShort(1)
        bb.putInt(sampleRate); bb.putInt(sampleRate * 2)
        bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII)); bb.putInt(dataLen)
        for (s in samples) {
            val v = (s * gain).coerceIn(-1f, 1f)
            bb.putShort((v * 32767f).toInt().toShort())
        }
        return bb.array()
    }

    // ---------------------------------------------------------------- 文件变声体验（P3）

    /** 不解码，先读时长（秒）；读不到返回 null。用来在解码前拦截超长音频 */
    fun probeDurationSec(ctx: Context, uri: Uri): Float? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000f }
        } finally { runCatching { r.release() } }
    }.getOrNull()

    /** 输入波形缩略图：分 [bins] 段取峰值（0..1） */
    fun waveOf(x: FloatArray, bins: Int = 160): FloatArray {
        if (x.isEmpty()) return FloatArray(0)
        val out = FloatArray(bins); var mx = 1e-6f
        for (b in 0 until bins) {
            val a = (x.size.toLong() * b / bins).toInt(); val e = maxOf(a + 1, (x.size.toLong() * (b + 1) / bins).toInt())
            var p = 0f; var i = a; while (i < e && i < x.size) { val v = abs(x[i]); if (v > p) p = v; i++ }
            out[b] = p; if (p > mx) mx = p
        }
        for (b in 0 until bins) out[b] /= mx
        return out
    }

    fun outputDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "output").also { it.mkdirs() }

    /** 结果历史：output/ 里的 wav/m4a，新→旧 */
    fun listOutputs(ctx: Context): List<File> = outputDir(ctx).listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in setOf("wav", "m4a") && !it.name.startsWith("autorun") }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** LRU：只留最近 [keep] 条（App 内副本；已存进「音乐/MicMask」的不动） */
    fun pruneOutputs(ctx: Context, keep: Int = 30): Int {
        val old = listOutputs(ctx).drop(keep)
        old.forEach { it.delete() }
        return old.size
    }

    /**
     * WAV → M4A（AAC-LC 单声道 128 kbps），用系统 MediaCodec + MediaMuxer，不引第三方编码库。
     * AAC 不支持 40 kHz，先重采样到 48 kHz。
     */
    fun encodeM4a(src: File, dst: File, bitRate: Int = 128_000) {
        val w = Wav.read(src)
        val sr = 48000
        val pcm = if (w.sampleRate == sr) w.samples else micmask.core.Resampler.convert(w.samples, w.sampleRate, sr)
        val fmt = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val mux = android.media.MediaMuxer(dst.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var muxing = false
        val info = android.media.MediaCodec.BufferInfo()
        var pos = 0; var inDone = false; var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = enc.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = enc.getInputBuffer(ii)!!; buf.clear()
                        val n = minOf(buf.remaining() / 2, pcm.size - pos)
                        val bb = buf.order(ByteOrder.LITTLE_ENDIAN)
                        for (k in 0 until n) bb.putShort((pcm[pos + k].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                        val ptsUs = pos * 1_000_000L / sr
                        pos += n
                        if (pos >= pcm.size) { enc.queueInputBuffer(ii, 0, n * 2, ptsUs, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else enc.queueInputBuffer(ii, 0, n * 2, ptsUs, 0)
                    }
                }
                val oi = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(enc.outputFormat); mux.start(); muxing = true }
                    oi >= 0 -> {
                        val ob = enc.getOutputBuffer(oi)!!
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) { ob.position(info.offset); ob.limit(info.offset + info.size); mux.writeSampleData(track, ob, info) }
                        enc.releaseOutputBuffer(oi, false)
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally {
            runCatching { enc.stop() }; runCatching { enc.release() }
            runCatching { if (muxing) mux.stop() }; runCatching { mux.release() }
        }
    }

    /** 把 App 内文件复制进系统「音乐/MicMask」，返回显示路径（Android 10 以下返回 null） */
    fun copyToMusic(ctx: Context, f: File, mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MicMask")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            val u = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            ctx.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            tr("音乐/MicMask/{0}", f.name)
        }.getOrNull()
    }
}
