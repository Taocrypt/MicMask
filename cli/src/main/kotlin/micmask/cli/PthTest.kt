package micmask.cli

import ai.onnxruntime.OrtEnvironment
import micmask.core.PthImport
import java.io.File

/** PC 上验证手机导入 .pth：PthTest <pth> <kit 目录> <k，如 v2_40k_L35> <输出.onnx> */
fun main(a: Array<String>) {
    val env = OrtEnvironment.getEnvironment()
    val kit = File(a[1]); val k = a[2]
    PthImport.openPth(File(a[0])).use { p ->
        val inf = PthImport.info(p)
        println("version=${inf.version} sr=${inf.sr} f0=${inf.f0} ups=${inf.upsample}")
        val plan = PthImport.parsePlan(File(kit, "$k.plan").readText())
        val t0 = System.currentTimeMillis()
        val w = PthImport.weights(p, plan)
        println("weights ${w.size} in ${System.currentTimeMillis() - t0}ms")
        val emb = if (inf.version == "v2") 768 else 256
        val cal = PthImport.readCal(File(kit, "cal.bin"), emb)
        PthImport.build(env, plan, w, File(kit, "${k}_f32.onnx"), File(kit, "${k}_qdq.onnx"), cal, emb,
            File(a[3] + ".f32tmp"), File(a[3])) { s, f -> println("  $s ${(f * 100).toInt()}%") }
        println("done in ${System.currentTimeMillis() - t0}ms size=${File(a[3]).length() shr 20}MB")
    }
}
