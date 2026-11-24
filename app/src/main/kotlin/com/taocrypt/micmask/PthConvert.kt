package com.taocrypt.micmask

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.net.Uri
import micmask.core.PthImport
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 手机上把 RVC .pth 转成 MicMask 流式模型（标准档 0.35 s + 省电档 0.55 s）。
 * 模板包 assets/pthkit.zip：<v1|v2>_<32|40|48>k_L<35|55>_{f32,qdq}.onnx + .plan，cal.bin 为 8 段校准特征。
 * 只支持带音高（f0=1）的 v1/v2 推理模型；不含 index 检索。
 */
object PthConvert {

    /** @return 音色 id（生成标准档+省电档两个文件） */
    fun run(ctx: Context, uri: Uri, displayName: String, progress: (String) -> Unit): String {
        val work = File(ctx.cacheDir, "pthimport").also { it.deleteRecursively(); it.mkdirs() }
        try {
            progress(tr("读取 .pth"))
            val pthFile = File(work, "in.pth")
            ctx.contentResolver.openInputStream(uri)!!.use { i -> pthFile.outputStream().use { i.copyTo(it, 1 shl 20) } }
            PthImport.openPth(pthFile).use { p ->
                val inf = PthImport.info(p)
                L.i("pth 导入 $displayName：version=${inf.version} sr=${inf.sr} f0=${inf.f0} ups=${inf.upsample}")
                if (inf.f0 != 1) throw IllegalArgumentException(tr("不支持无音高（f0=0）模型"))
                if (inf.version != "v1" && inf.version != "v2") throw IllegalArgumentException(tr("不支持的版本 {0}", inf.version))
                if (inf.sr !in listOf(32000, 40000, 48000)) throw IllegalArgumentException(tr("不支持的采样率 {0}", inf.sr))
                val base = "${inf.version}_${inf.sr / 1000}k"
                val need = setOf("cal.bin") + listOf("L35", "L55").flatMap { l -> listOf("${base}_$l.plan", "${base}_${l}_f32.onnx", "${base}_${l}_qdq.onnx") }
                ctx.assets.open("pthkit.zip").use { a -> ZipInputStream(a.buffered()).use { z ->
                    while (true) { val e = z.nextEntry ?: break
                        if (e.name in need) File(work, e.name).outputStream().use { z.copyTo(it) } } } }
                val miss = need.filter { !File(work, it).exists() }
                if (miss.isNotEmpty()) throw IllegalStateException(tr("模板缺失：{0}", miss.joinToString()))
                val emb = if (inf.version == "v2") 768 else 256
                val cal = PthImport.readCal(File(work, "cal.bin"), emb)
                val vid = displayName.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}-]+"), "_").trim('_').ifEmpty { "pth" }
                val dir = ModelStore.internalDir(ctx).also { it.mkdirs() }
                val env = OrtEnvironment.getEnvironment()
                val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
                var n = 0
                for ((l, suffix) in listOf("L35" to "_stream_qdq.onnx", "L55" to "_stream_b50_qdq.onnx")) {
                    val tier = if (l == "L35") tr("标准档") else tr("省电档")
                    val plan = PthImport.parsePlan(File(work, "${base}_$l.plan").readText())
                    val w = PthImport.weights(p, plan)
                    val dst = File(dir, vid + suffix); val part = File(dir, dst.name + ".part")
                    val t0 = System.currentTimeMillis()
                    PthImport.build(env, plan, w, File(work, "${base}_${l}_f32.onnx"), File(work, "${base}_${l}_qdq.onnx"), cal, emb,
                        File(work, "tmp_f32.onnx"), part, threads) { s, _ -> progress("$tier · ${trStep(s)}") }
                    dst.delete()
                    if (!part.renameTo(dst)) throw java.io.IOException(tr("改名失败：{0}", dst.name))
                    ModelStore.externalDir(ctx).let { File(it, dst.name).delete() }
                    L.i("pth 导入 → ${dst.name} ${dst.length() shr 20}MB，用时 ${System.currentTimeMillis() - t0}ms")
                    n++
                }
                return vid
            }
        } finally { work.deleteRecursively() }
    }

    private fun trStep(s: String): String = when {
        s.startsWith("校准 ") -> tr("校准 {0}", s.removePrefix("校准 "))
        s.startsWith("拼装") -> tr("拼装模型")
        s == "量化" -> tr("量化")
        else -> tr("完成")
    }
}

/** RVC .index → <音色>_stream_index.bin */
object IndexConvert {
    fun run(ctx: Context, uri: Uri, vid: String): micmask.core.FaissImport.Info {
        val dir = ModelStore.internalDir(ctx).also { it.mkdirs() }
        val dst = File(dir, ModelStore.voiceFile(vid, ModelStore.LIVE_INDEX)); val part = File(dir, dst.name + ".part")
        val inf = ctx.contentResolver.openInputStream(uri)!!.use { micmask.core.FaissImport.convert(it, part) }
        dst.delete()
        if (!part.renameTo(dst)) throw java.io.IOException(tr("改名失败：{0}", dst.name))
        L.i("index 导入 → ${dst.name}：原 ${inf.ntotal} 条，保留 ${inf.kept} 条 × ${inf.d} 维，${inf.nlist} 簇，${dst.length() shr 20}MB")
        return inf
    }

    /** 给 .index 找音色：同批导入的 .pth 只有一个就用它；否则找文件名里包含的音色名（取最长） */
    fun pickVoice(ctx: Context, fileName: String, batch: List<String>): String? {
        if (batch.size == 1) return batch[0]
        val n = fileName.lowercase()
        return (batch + ModelStore.voices(ctx)).distinct().filter { it.length >= 2 && n.contains(it.lowercase()) }.maxByOrNull { it.length }
    }
}
