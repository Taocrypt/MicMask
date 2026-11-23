package com.taocrypt.micmask.qnntest

import java.io.File

/**
 * shell 进程入口：由 App 通过 Shizuku 以 shell 身份跑
 *   app_process -Djava.class.path=<apk> /system/bin com.taocrypt.micmask.qnntest.ShellProbe <模型目录>
 * 结果打到 stdout，App 收集显示。
 */
object ShellProbe {
    @JvmStatic fun main(args: Array<String>) {
        val out: (String) -> Unit = { println(it); System.out.flush() }
        try {
            Probe.header(out, "shell 进程")
            val dir = File(args.getOrNull(0) ?: Probe.MM_DIR)
            val models = Probe.MODEL_NAMES.map { File(dir, it) }.filter { it.isFile }
            out("模型目录 $dir：${models.joinToString { it.name }}")
            Probe.runAll(models, out)
        } catch (t: Throwable) {
            out("shell 测试异常：$t"); t.stackTrace.take(8).forEach { out("  at $it") }
        }
        System.exit(0)
    }
}
