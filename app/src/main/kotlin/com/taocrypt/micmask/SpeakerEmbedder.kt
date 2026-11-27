package com.taocrypt.micmask

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * 说话人嵌入（声纹门限用）：3D-Speaker CAM++ 中文通用模型，sherpa-onnx 导出的 ONNX。
 * 输入 x[N, T, 80]（[SpeakerFbank] 的 fbank + CMN），输出 embedding[N, 192]。
 * 只依赖 ONNX Runtime 的 Java API（App 与 shell 进程、桌面 JVM 都能用）。
 *
 * 模型不打包进 APK，开启声纹门限时按需下载到 ModelStore.internalDir（见 Voiceprint.kt）。
 * 许可：Apache-2.0（3D-Speaker / ModelScope iic/speech_campplus_sv_zh-cn_16k-common；ONNX 导出 k2-fsa/sherpa-onnx，Apache-2.0），见 NOTICE.md。
 */
class SpeakerEmbedder(file: File, threads: Int = 1) : java.io.Closeable {
    companion object {
        const val MODEL = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
        const val SHA256 = "e6af4d4c444f3dab01d991bb9b7d5af98eb979e329f6c8880a19d5553561ed25"
        const val SIZE = 28281139L
        const val DIM = 192
        /** 下载地址（同一文件，按顺序试；国内优先 hf-mirror） */
        val URLS = listOf(
            "https://hf-mirror.com/csukuangfj/speaker-embedding-models/resolve/main/$MODEL",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$MODEL",
            "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/$MODEL",
        )
        /** 少于这么多帧（约 0.3 s）不算 */
        const val MIN_FRAMES = 30
    }

    private val env = OrtEnvironment.getEnvironment()
    private val sess: OrtSession = env.createSession(file.absolutePath, OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(threads.coerceIn(1, 2))
        setInterOpNumThreads(1)
    })
    private val inName = sess.inputNames.first()

    /** [x16]：16 kHz、[-1,1]。返回嵌入（未归一化）；太短返回 null */
    fun embed(x16: FloatArray): FloatArray? {
        val t = SpeakerFbank.frames(x16.size)
        if (t < MIN_FRAMES) return null
        val feat = SpeakerFbank.compute(x16)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(feat), longArrayOf(1, t.toLong(), SpeakerFbank.MELS.toLong())).use { inp ->
            sess.run(mapOf(inName to inp)).use { r ->
                val v = r.get(0).value
                @Suppress("UNCHECKED_CAST")
                return (v as Array<FloatArray>)[0].copyOf()
            }
        }
    }

    override fun close() { runCatching { sess.close() } }
}
