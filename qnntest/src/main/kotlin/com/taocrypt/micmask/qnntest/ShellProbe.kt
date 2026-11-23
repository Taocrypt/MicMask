package com.taocrypt.micmask.qnntest

import java.io.File

/**
 * shell 进程入口（每个 模型×档位 单独起一个进程）：
 *   app_process ... com.taocrypt.micmask.qnntest.ShellProbe header
 *   app_process ... com.taocrypt.micmask.qnntest.ShellProbe <模型文件> <档位>
 */
object ShellProbe {
    @JvmStatic fun main(args: Array<String>) {
        val out: (String) -> Unit = { println(it); System.out.flush() }
        try {
            if (args.getOrNull(0) == "header") Probe.header(out, "shell 进程")
            else Probe.runOne(File(args[0]), args[1], out)
        } catch (t: Throwable) {
            out("shell 测试异常：$t"); t.stackTrace.take(8).forEach { out("  at $it") }
        }
        System.exit(0)
    }
}
