package com.taocrypt.micmask

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import micmask.core.VocoderSplit
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin

/**
 * 原生 NEON fp16 声码器（NSF-HiFiGAN dec，内核移植自 nasuiyile/mobile-rvc，MIT）。
 *
 * 拆分方式：ORT 跑流式合成器的前半（enc_p / flow / 正弦源，约占 1/10 算力），dec 交给 libmicmask_vocoder.so。
 * 拆分产物在手机上从音色自带的 ONNX 里提取，放在模型目录 .voccache/：
 *   <模型名>__<大小>_<修改时间>_f<格式>.pre.onnx   前半图（ORT 再按 OrtLoad 生成 .ort 缓存）
 *   <模型名>__<大小>_<修改时间>_f<格式>.mmvoc      dec 权重（fp16）+ 48k 重采样尾巴
 *   <模型名>__<大小>_<修改时间>_f<格式>.json       张量名、摘要、首次校验结果
 * 首次校验：原图追加 z/har 输出跑一次，原生结果与 ORT 原输出比 SNR，低于阈值就记为不可用（回退 ORT 整图）。
 */
object NeonVocoder {
    private const val DIR = ".voccache"
    /** 校验阈值：原生 fp32 参考 vs ORT QDQ 实测 7–23 dB（QDQ 激活量化噪声），结构错时 ≤0 dB */
    private const val CHECK_SNR_DB = 3.0

    @Volatile private var lib: Boolean? = null
    @Volatile var loadError: String? = null; private set

    /** so 能否加载（只有 arm64 包里有） */
    fun available(): Boolean {
        lib?.let { return it }
        synchronized(this) {
            lib?.let { return it }
            val ok = runCatching { System.loadLibrary("micmask_vocoder"); true }
                .onFailure { loadError = it.toString(); L.w("原生声码器库加载失败：$it") }.getOrDefault(false)
            lib = ok
            return ok
        }
    }

    /** CPU 支持 ARMv8.2 fp16 运算（HWCAP asimdhp + fphp） */
    fun fp16Supported(): Boolean = available() && runCatching { nFp16Supported() }.getOrDefault(false)

    @JvmStatic private external fun nFp16Supported(): Boolean
    @JvmStatic private external fun nLastError(): String
    @JvmStatic private external fun nCreate(path: String, threads: Int, fp16: Boolean): Long
    @JvmStatic private external fun nDescribe(h: Long): String
    @JvmStatic private external fun nInfo(h: Long, what: Int): Int
    @JvmStatic private external fun nRun(h: Long, z: FloatArray, t: Int, har: FloatArray, cond: FloatArray?, out: FloatArray): Int
    @JvmStatic private external fun nFree(h: Long)

    /** 拆分产物 */
    class Split(val pre: File, val blob: File, val z: String, val har: String, val cond: String?, val summary: String, val meta: File)

    private fun stem(model: File) = "${model.nameWithoutExtension}__${model.length()}_${model.lastModified()}_f${VocoderSplit.FORMAT}"

    /** 删掉本模型的旧拆分，以及原模型已不存在的拆分 */
    private fun cleanup(model: File, dir: File) = runCatching {
        val keep = stem(model)
        dir.listFiles()?.forEach { x ->
            val base = x.name.substringBefore("__", "")
            val gone = base.isEmpty() || dir.parentFile?.listFiles()?.none { it.isFile && it.nameWithoutExtension == base } != false
            val stale = x.name.startsWith(model.nameWithoutExtension + "__") && !x.name.startsWith(keep + ".")
            if (x.isFile && (gone || stale)) x.delete()
        }
    }

    /** 删模型时调用 */
    fun dropCache(model: File) { File(model.parentFile, DIR).listFiles()?.filter { it.name.startsWith(model.nameWithoutExtension + "__") }?.forEach { it.deleteRecursively() } }
    fun size(modelDir: File) = File(modelDir, DIR).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clear(modelDir: File) = File(modelDir, DIR).deleteRecursively()

    /**
     * 取（必要时生成）拆分产物。首次约 1–3 s（解析 + 写文件），之后直接用。
     * 返回 null = 本模型不支持或首次校验没通过（原因写日志 / json）。
     */
    fun prepare(env: OrtEnvironment, model: File, threads: Int): Split? {
        val dir = File(model.parentFile, DIR)
        val s = stem(model)
        val pre = File(dir, "$s.pre.onnx"); val blob = File(dir, "$s.mmvoc"); val meta = File(dir, "$s.json")
        if (meta.isFile) {
            val j = runCatching { org.json.JSONObject(meta.readText()) }.getOrNull()
            if (j != null) {
                if (!j.optBoolean("ok", false)) { L.w("声码器 ${model.name}：之前判定不可用（${j.optString("why")}），用 ORT 整图"); return null }
                if (pre.isFile && blob.isFile)
                    return Split(pre, blob, j.getString("z"), j.getString("har"), j.optString("cond").ifEmpty { null }, j.optString("summary"), meta)
            }
        }
        if (!runCatching { dir.mkdirs(); dir.isDirectory && dir.canWrite() }.getOrDefault(false)) { L.w("声码器：${dir} 不可写"); return null }
        cleanup(model, dir)
        val t0 = System.nanoTime()
        val plan = try { VocoderSplit.plan(VocoderSplit.map(model)) } catch (e: VocoderSplit.Unsupported) {
            L.w("声码器 ${model.name} 不支持原生拆分：${e.message}")
            writeMeta(meta, false, e.message ?: "", null); return null
        } catch (e: Throwable) { L.e("声码器 ${model.name} 拆分失败", e); return null }
        blob.writeBytes(plan.blob)
        VocoderSplit.write(plan, pre, pruned = true)
        val tSplit = (System.nanoTime() - t0) / 1_000_000
        L.i("声码器拆分 ${model.name} 用时 ${tSplit}ms：${plan.summary}")
        // 首次校验：原图 + 额外输出，同一次运行比原生与 ORT
        val check = File(dir, "$s.check.onnx")
        val snr = try {
            VocoderSplit.write(plan, check, pruned = false)
            selfCheck(env, check, blob, plan.z, plan.har, plan.cond, threads)
        } catch (e: Throwable) { L.e("声码器校验出错（下次加载再试）", e); null } finally { check.delete() }
        if (snr == null || snr.isNaN()) { pre.delete(); blob.delete(); return null }   // 出错不记结论，下次重试
        val ok = snr >= CHECK_SNR_DB
        val why = "校验 SNR ${"%.1f".format(snr)} dB（阈值 $CHECK_SNR_DB）"
        L.i("声码器校验 ${model.name}：$why → ${if (ok) "通过" else "不通过，回退 ORT"}")
        writeMeta(meta, ok, why, plan)
        if (!ok) { pre.delete(); blob.delete(); return null }
        return Split(pre, blob, plan.z, plan.har, plan.cond, plan.summary, meta)
    }

    private fun writeMeta(meta: File, ok: Boolean, why: String, plan: VocoderSplit.Plan?) = runCatching {
        val j = org.json.JSONObject().put("ok", ok).put("why", why).put("fmt", VocoderSplit.FORMAT)
        if (plan != null) j.put("z", plan.z).put("har", plan.har).put("cond", plan.cond ?: "").put("summary", plan.summary)
        meta.writeText(j.toString())
    }

    /** 合成器测试输入：随机内容特征 + 起伏的 f0（含清音段） */
    fun testInput(t: Int, emb: Int, seed: Int): Triple<FloatArray, LongArray, FloatArray> {
        val r = java.util.Random(seed.toLong())
        val ph = FloatArray(t * emb) { r.nextGaussian().toFloat() }
        val f0 = FloatArray(t) { i -> if ((i / 17 + seed) % 4 == 0) 0f else (180 + 60 * sin(i / 9.0 + seed)).toFloat() }
        val melMin = 1127 * ln(1 + 50 / 700.0); val melMax = 1127 * ln(1 + 1100 / 700.0)
        val pc = LongArray(t) { i ->
            val m = 1127 * ln(1 + f0[i] / 700.0)
            if (f0[i] <= 0) 1L else ((m - melMin) * 254 / (melMax - melMin) + 1).coerceIn(1.0, 255.0).toLong()
        }
        return Triple(ph, pc, f0)
    }

    private fun selfCheck(env: OrtEnvironment, check: File, blob: File, zN: String, harN: String, condN: String?, threads: Int): Double {
        val so = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads); setInterOpNumThreads(1) }
        env.createSession(check.absolutePath, so).use { s ->
            val emb = ((s.inputInfo["phone"]?.info as? ai.onnxruntime.TensorInfo)?.shape?.getOrNull(2)?.toInt() ?: 768).let { if (it > 0) it else 768 }
            val audioName = s.outputNames.first { it != zN && it != harN && it != condN }
            val dec = open(blob, threads, true)
            try {
                var sig = 0.0; var err = 0.0
                for (seed in 0 until 2) {
                    val (ph, pc, pf) = testInput(LiveRvc.T, emb, seed)
                    val o = runFull(env, s, ph, pc, pf, LiveRvc.T)
                    val ref = o[audioName]!!.first
                    val (z, zs) = o[zN]!!
                    val har = o[harN]!!.first
                    val cond = condN?.let { o[it]!!.first }
                    val y = dec.run(z, zs[2].toInt(), har, cond)
                    val n = minOf(ref.size, y.size)
                    for (i in 0 until n) { val d = (ref[i] - y[i]).toDouble(); sig += ref[i].toDouble() * ref[i]; err += d * d }
                }
                return 10 * log10(sig / maxOf(err, 1e-30))
            } finally { dec.close() }
        }
    }

    private fun runFull(env: OrtEnvironment, s: OrtSession, ph: FloatArray, pc: LongArray, pf: FloatArray, t: Int): Map<String, Pair<FloatArray, LongArray>> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(ph), longArrayOf(1, t.toLong(), (ph.size / t).toLong())).use { a ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(pc), longArrayOf(1, t.toLong())).use { b ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(pf), longArrayOf(1, t.toLong())).use { c ->
                    s.run(mapOf("phone" to a, "pitch" to b, "pitchf" to c)).use { res ->
                        val m = HashMap<String, Pair<FloatArray, LongArray>>()
                        for ((k, v) in res) {
                            val tv = v as OnnxTensor; val fb = tv.floatBuffer; val y = FloatArray(fb.remaining()); fb.get(y)
                            m[k] = y to (tv.info as ai.onnxruntime.TensorInfo).shape
                        }
                        return m
                    }
                }
            }
        }
    }

    fun open(blob: File, threads: Int, fp16: Boolean): Dec {
        if (!available()) throw IllegalStateException("原生声码器库不可用：$loadError")
        val h = nCreate(blob.absolutePath, threads, fp16)
        if (h == 0L) throw IllegalStateException("原生声码器创建失败：${nLastError()}")
        return Dec(h)
    }

    class Dec internal constructor(private var h: Long) : java.io.Closeable {
        val inChannels = nInfo(h, 0)
        val upp = nInfo(h, 1)
        val perFrame = nInfo(h, 2)
        val fp16 = nInfo(h, 3) == 1
        val preChannels = nInfo(h, 4)
        val desc: String = nDescribe(h)
        private var out = FloatArray(0)

        /** z[C*T] har[T*upp] cond[c0]? → 48k 波形 [T*480]（返回内部复用数组的拷贝） */
        @Synchronized fun run(z: FloatArray, t: Int, har: FloatArray, cond: FloatArray?): FloatArray {
            check(h != 0L) { "声码器已释放" }
            val n = t * perFrame
            if (out.size != n) out = FloatArray(n)
            val r = nRun(h, z, t, har, cond, out)
            if (r < 0) throw IllegalStateException("原生声码器运行失败：${nLastError()}")
            return out.copyOf()
        }

        @Synchronized override fun close() { if (h != 0L) { nFree(h); h = 0L } }
    }
}
