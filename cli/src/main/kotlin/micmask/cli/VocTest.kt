package micmask.cli

import micmask.core.VocoderSplit
import java.io.File

/** PC 上验证原生声码器拆分：VocTest <流式合成器.onnx> <输出目录>  → pre.onnx / check.onnx / dec.mmvoc + 输出名 */
fun main(a: Array<String>) {
    val src = File(a[0]); val out = File(a[1]); out.mkdirs()
    val t0 = System.currentTimeMillis()
    val buf = VocoderSplit.map(src)
    val plan = VocoderSplit.plan(buf)
    val t1 = System.currentTimeMillis()
    File(out, "dec.mmvoc").writeBytes(plan.blob)
    VocoderSplit.write(plan, File(out, "pre.onnx"), pruned = true)
    val t2 = System.currentTimeMillis()
    VocoderSplit.write(plan, File(out, "check.onnx"), pruned = false)
    println(plan.summary)
    println("names z=${plan.z} har=${plan.har} cond=${plan.cond}")
    println("time plan=${t1 - t0}ms pre=${t2 - t1}ms check=${System.currentTimeMillis() - t2}ms pre.onnx=${File(out, "pre.onnx").length() shr 20}MB")
    File(out, "names.txt").writeText("${plan.z}\n${plan.har}\n${plan.cond ?: ""}\n")
}
