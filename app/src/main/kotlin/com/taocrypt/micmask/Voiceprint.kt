package com.taocrypt.micmask

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 声纹门限（App 进程侧）：模型按需下载、录入本人声纹、保存/读取。
 *
 *  - 模型：[SpeakerEmbedder.MODEL]（约 27 MB），不打包进 APK；开启或录入时下载到 ModelStore.internalDir，sha256 校验。
 *    实时变声开始时随其它模型一起经 TX_LIVE_MODELS 交给 shell 进程（ModelStore.liveNames）。
 *  - 录入：机身麦 48 kHz 录约 [ENROLL_S] 秒 → 与实时同一套 48k→16k 抽取 + fbank → 每 1.5 s 窗（步长 0.5 s）算嵌入，
 *    有声不足的窗跳过 → 各窗 L2 归一化后取均值再归一化，存 App 私有目录 files/voiceprint/voiceprint.bin（不保存录音）。
 *  - 模式/阈值存主配置（vp_mode：0 关 1 门限 2 聚焦，旧版的 vp_on 兼容读；vp_thr），经 tune Parcel 末尾传给 shell（MicInjector.writeTune → InjectorService.readTune）。
 */
object Voiceprint {
    const val ENROLL_S = 10
    private const val MAGIC = 0x4D4D5650   // "MMVP"
    private const val SR = 48000
    /** 录入时每个 1.5 s 窗至少要有这么多有声时长才算 */
    private const val WIN_SPEECH_MS = 900
    private const val MIN_WINDOWS = 4

    fun file(ctx: Context) = File(File(ctx.filesDir, "voiceprint"), "voiceprint.bin")

    // ---------------------------------------------------------------- 模型
    fun modelFile(ctx: Context): File? = ModelStore.locate(ctx, SpeakerEmbedder.MODEL)?.takeIf { it.length() == SpeakerEmbedder.SIZE }
    fun modelReady(ctx: Context) = modelFile(ctx) != null

    /** 下载模型（依次试镜像，sha256 校验）；失败抛异常，消息给用户看 */
    fun download(ctx: Context, progress: (Float) -> Unit) {
        val dst = File(ModelStore.internalDir(ctx), SpeakerEmbedder.MODEL)
        com.taocrypt.micmask.repo.ModelRepo.downloadDirect(SpeakerEmbedder.URLS, dst, SpeakerEmbedder.SHA256, SpeakerEmbedder.SIZE, progress)
    }

    // ---------------------------------------------------------------- 存取
    class Data(val emb: FloatArray, val time: Long, val consistency: Float, val windows: Int)

    fun load(ctx: Context): Data? {
        val f = file(ctx)
        if (!f.isFile) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { i ->
                val ok = i.readInt() == MAGIC && i.readInt() == 1
                val n = if (ok) i.readInt() else -1
                if (n != SpeakerEmbedder.DIM) null
                else Data(FloatArray(n) { i.readFloat() }, i.readLong(), i.readFloat(), i.readInt())
            }
        }.onFailure { L.w("声纹读取失败：${it.message}") }.getOrNull()
    }

    private fun save(ctx: Context, d: Data) {
        val f = file(ctx); f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(MAGIC); o.writeInt(1); o.writeInt(d.emb.size); d.emb.forEach { o.writeFloat(it) }
            o.writeLong(d.time); o.writeFloat(d.consistency); o.writeInt(d.windows)
        }
        f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException(tr("改名失败：{0}", f.name))
    }

    fun delete(ctx: Context) { file(ctx).delete() }

    // ---------------------------------------------------------------- 录入
    /**
     * 录 [ENROLL_S] 秒机身麦（48 kHz），阻塞；[cancel] 返回 true 时提前结束并抛异常。
     * [tick] 回调已录秒数与当前电平 dBFS。调用方需已拿到 RECORD_AUDIO 权限。
     */
    @SuppressLint("MissingPermission")
    fun record(cancel: () -> Boolean, tick: (Int, Float) -> Unit): FloatArray {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR / 5 * 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException(tr("无法创建 AudioRecord（麦克风被占用？）")) }
        val total = SR * ENROLL_S
        val out = FloatArray(total); var n = 0
        val buf = ShortArray(SR / 10)
        try {
            rec.startRecording()
            var lastSec = -1
            while (n < total) {
                if (cancel()) throw IllegalStateException(tr("已取消"))
                val k = rec.read(buf, 0, minOf(buf.size, total - n))
                if (k < 0) throw IllegalStateException(tr("麦克风读取失败（{0}）", k))
                var e = 0.0
                for (i in 0 until k) { val v = buf[i] / 32768f; out[n + i] = v; e += v.toDouble() * v }
                n += k
                val sec = n / SR
                if (k > 0 && sec != lastSec) { lastSec = sec; tick(sec, (10 * kotlin.math.log10(e / k + 1e-12)).toFloat()) }
            }
        } finally { runCatching { rec.stop() }; rec.release() }
        return out
    }

    /** 由录音算声纹并保存；返回结果。有效语音不够时抛异常（消息给用户看） */
    fun enroll(ctx: Context, x48: FloatArray): Data {
        val mf = modelFile(ctx) ?: throw IllegalStateException(tr("声纹模型还没下载"))
        val win = (SR * SpeakerGate.WIN_S).toInt(); val hop = SR / 2
        val embs = ArrayList<FloatArray>()
        val t0 = System.nanoTime()
        SpeakerEmbedder(mf, 2).use { e ->
            var s = 0
            while (s + win <= x48.size) {
                val w = x48.copyOfRange(s, s + win)
                if (SpeakerFbank.speechMs(w, SR, -55f) >= WIN_SPEECH_MS) e.embed(SpeakerFbank.decim3(w))?.let { embs += SpeakerFbank.normalize(it) }
                s += hop
            }
        }
        L.i("声纹录入：有效窗 ${embs.size} 个，用时 ${(System.nanoTime() - t0) / 1_000_000}ms")
        if (embs.size < MIN_WINDOWS) throw IllegalStateException(tr("有效语音太少：请离麦克风近一些，用平常的音量连续说话后重录"))
        val mean = FloatArray(SpeakerEmbedder.DIM)
        for (v in embs) for (i in mean.indices) mean[i] += v[i] / embs.size
        val ref = SpeakerFbank.normalize(mean)
        val cons = embs.map { SpeakerFbank.cosine(it, ref) }.average().toFloat()
        L.i("声纹录入：自洽度 ${"%.2f".format(cons)}（各窗与均值的平均相似度）")
        val d = Data(ref, System.currentTimeMillis(), cons, embs.size)
        save(ctx, d)
        return d
    }
}
