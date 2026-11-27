package com.taocrypt.micmask

import ai.alkove.Models
import ai.alkove.Resampler
import ai.alkove.VoiceprintLearner
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File

/**
 * 声纹聚焦（Alkove Focus，App 进程侧）：模型按需下载、Timbre 学习声纹、声纹 JSON 存取。
 * 设计参考了用户转来的 Alkove 接入提案（alkove-micmask.patch），按本仓库现状重写。
 *
 *  - 模型：只下 Focus / Cue / Timbre 三个 .alk（共约 4.5 MB），不进仓库、不进 APK。
 *    Cue 是 ai.alkove.Live 构造时 models.need("focus","cue") 必需的，Timbre 是 Focus 用声纹 / 远端识别必需的。
 *    地址钉在 SDK 0.5.0 内置清单（crates/core/models.json）的 revision 上，不用 main；sha256 与官方 HF 仓库
 *    alkove-*.json 清单一致。按 hf-mirror.com → huggingface.co 顺序试（ModelRepo.downloadDirect，校验大小 + sha256），
 *    存 ModelStore.internalDir，原文件名。实时变声开始时随其它模型经 TX_LIVE_MODELS 交给 shell（ModelStore.liveNames）。
 *  - 声纹：机身麦 48 kHz 录音 → Alkove Resampler 降到 16 kHz → 每 1.5 s 一窗喂 VoiceprintLearner，
 *    攒够 4 个相互一致的有声窗（约 6 s 语音）即完成，最长录 [ENROLL_MAX_S] 秒。不保存录音，
 *    只存 Voiceprint.toJson() 到 App 私有目录，经 tune Parcel 末尾交给 shell 持久化。
 *  - 升级 SDK 时：把下面 [MODELS] 换成新版本 models.json 的值（版本不符时 Models.load 会报 models.hash）。
 */
object AlkoveStore {
    class Spec(val key: String, val repo: String, val file: String, val size: Long, val sha256: String, val revision: String)

    /** ai.alkove:alkove:0.5.0 的模型清单（与 libalkove.so 内嵌清单、HF 仓库 alkove-*.json 核对一致） */
    val MODELS = listOf(
        Spec("focus", "alkove-focus-1", "alkove-focus-1.alk", 2_320_032L,
            "25028c1f9faf765ccab4076716b5e8a8e756cd7662babd2bc078951982df5ce4", "69075e56ca39f9bc09bcf41c3f79288a2d753989"),
        Spec("cue", "alkove-cue-1-preview", "alkove-cue-1-preview.alk", 1_199_892L,
            "19e8b6c66ee9ce23bba3b8011f03827c5ba4bb2fe8bec25fe07558a78950190a", "0e1f30380bdfb3201b5d4fd7d9e4197f71f11a60"),
        Spec("timbre", "alkove-timbre-1", "alkove-timbre-1.alk", 970_064L,
            "fc94dfff533f1a4fb2abea172c1f5ff7a92cc00c6331275ea1a26e38f877b0e8", "cb87ad720218e582f114d89b5a6936b9fd3f811a"),
    )
    val FILES: List<String> get() = MODELS.map { it.file }
    val TOTAL_BYTES: Long get() = MODELS.sumOf { it.size }

    /** 同一文件的下载地址，按顺序试：国内优先 hf-mirror，固定 revision */
    fun urls(s: Spec) = listOf(
        "https://hf-mirror.com/alkove-ai/${s.repo}/resolve/${s.revision}/${s.file}",
        "https://huggingface.co/alkove-ai/${s.repo}/resolve/${s.revision}/${s.file}",
    )

    // ---------------------------------------------------------------- 模型
    fun modelFile(ctx: Context, s: Spec): File? = ModelStore.locate(ctx, s.file)?.takeIf { it.length() == s.size }
    fun modelsReady(ctx: Context) = MODELS.all { modelFile(ctx, it) != null }

    /** 下载缺的模型（依次试镜像，sha256 校验）；[progress] 为三者合计进度。失败抛异常，消息给用户看 */
    fun download(ctx: Context, progress: (Float) -> Unit) {
        val total = TOTAL_BYTES.toFloat()
        var before = 0L
        for (s in MODELS) {
            if (modelFile(ctx, s) == null) {
                val dst = File(ModelStore.internalDir(ctx), s.file)
                com.taocrypt.micmask.repo.ModelRepo.downloadDirect(urls(s), dst, s.sha256, s.size) { p -> progress((before + p * s.size) / total) }
            }
            before += s.size
        }
        checkManifest()
        progress(1f)
    }

    /** SDK 内嵌清单与上面钉的值不一致（升级了 SDK 没改这里）就记一条警告；读不到原生库时跳过 */
    private fun checkManifest() = runCatching {
        @Suppress("UNCHECKED_CAST")
        val m = Models.MANIFEST["models"] as? Map<String, Any?> ?: return@runCatching
        for (s in MODELS) {
            val sha = (m[s.key] as? Map<*, *>)?.get("sha256") as? String
            if (sha != null && sha != s.sha256) L.w("Alkove SDK 清单里 ${s.key} 的 sha256 与 AlkoveStore 钉的不同：请按新版 models.json 更新")
        }
    }.onFailure { L.w("Alkove 清单核对跳过：${it.message}") }

    // ---------------------------------------------------------------- 声纹存取
    fun printFile(ctx: Context) = File(File(ctx.filesDir, "voiceprint"), "alkove-voiceprint.json")
    fun loadPrint(ctx: Context): String? = printFile(ctx).takeIf { it.isFile }?.let { f -> runCatching { f.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }
    /** 注册时间（null = 未注册） */
    fun printTime(ctx: Context): Long? = printFile(ctx).takeIf { it.isFile && it.length() > 0 }?.lastModified()
    fun deletePrint(ctx: Context) { printFile(ctx).delete() }
    private fun savePrint(ctx: Context, json: String) {
        val f = printFile(ctx); f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json)
        f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException(tr("改名失败：{0}", f.name))
    }

    // ---------------------------------------------------------------- 学习声纹（Timbre）
    const val ENROLL_S = 8
    const val ENROLL_MAX_S = 16
    private const val SR = 48000
    private const val SR16 = 16000
    /** VoiceprintLearner.offer 不跨调用拼窗：每次正好给一个 1.5 s 窗 */
    private const val WIN16 = SR16 * 3 / 2

    class Result(val accepted: Int, val rejected: Int, val seconds: Int)

    /**
     * 录音并学习声纹，阻塞；[cancel] 返回 true 时提前结束并抛异常。[tick] 回调已录秒数、学习进度 0..1、当前电平 dBFS。
     * 学够即停（通常约 [ENROLL_S] 秒），最长 [ENROLL_MAX_S] 秒仍不够则抛异常。调用方需已拿到 RECORD_AUDIO 权限。
     */
    @SuppressLint("MissingPermission")
    fun enroll(ctx: Context, cancel: () -> Boolean, tick: (Int, Float, Float) -> Unit): Result {
        val tf = MODELS.first { it.key == "timbre" }.let { modelFile(ctx, it) } ?: throw IllegalStateException(tr("声纹聚焦模型还没下载"))
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t0 = System.nanoTime()
        Models.load(listOf("timbre"), files = mapOf("timbre" to tf)).use { models ->
            VoiceprintLearner(models).use { learner ->
                Resampler(SR, SR16).use { rs ->
                    val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR / 5 * 2))
                    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException(tr("无法创建 AudioRecord（麦克风被占用？）")) }
                    val buf = ShortArray(SR / 10); val fb = FloatArray(buf.size)
                    val acc = FloatArray(WIN16 * 2); var nAcc = 0
                    var accepted = 0; var rejected = 0; var got = 0; var lastSec = -1; var progress = 0.0
                    try {
                        rec.startRecording()
                        while (got < SR * ENROLL_MAX_S) {
                            if (cancel()) throw IllegalStateException(tr("已取消"))
                            val k = rec.read(buf, 0, buf.size)
                            if (k < 0) throw IllegalStateException(tr("麦克风读取失败（{0}）", k))
                            var e = 0.0
                            for (i in 0 until k) { val v = buf[i] / 32768f; fb[i] = v; e += v.toDouble() * v }
                            got += k
                            val y = rs.push(fb, k)
                            var o = 0
                            while (o < y.size) {
                                val c = minOf(y.size - o, acc.size - nAcc)
                                System.arraycopy(y, o, acc, nAcc, c); nAcc += c; o += c
                                if (nAcc >= WIN16) {
                                    val r = learner.offer(acc.copyOf(WIN16))
                                    accepted += r.accepted; rejected += r.rejected; progress = r.progress
                                    if (r.restarted) L.i("声纹聚焦注册：前几段不一致，重新开始累计")
                                    System.arraycopy(acc, WIN16, acc, 0, nAcc - WIN16); nAcc -= WIN16
                                }
                            }
                            val sec = got / SR
                            if (k > 0 && sec != lastSec) { lastSec = sec; tick(sec, progress.toFloat(), (10 * kotlin.math.log10(e / k + 1e-12)).toFloat()) }
                            if (learner.ready) break
                        }
                    } finally { runCatching { rec.stop() }; rec.release() }
                    val vp = learner.voiceprint
                    L.i("声纹聚焦注册：接受 $accepted 窗、拒绝 $rejected 窗，录音 ${got / SR} 秒，用时 ${(System.nanoTime() - t0) / 1_000_000}ms，结果=${vp ?: "无"}")
                    if (vp == null) throw IllegalStateException(tr("有效语音不足：请离麦克风近一些，用平常的音量连续说话后重试"))
                    savePrint(ctx, vp.toJson())
                    return Result(accepted, rejected, got / SR)
                }
            }
        }
    }
}
