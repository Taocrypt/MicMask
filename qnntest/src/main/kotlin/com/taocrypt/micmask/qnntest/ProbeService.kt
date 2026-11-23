package com.taocrypt.micmask.qnntest

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/** App 进程测试：跑在独立的 :probe 进程里，结果逐行写文件；每项跑完自杀，崩溃也不影响界面 */
class ProbeService : Service() {
    // 用 bindService(BIND_IMPORTANT) 绑定：进程跟着前台 Activity 拿前台调度，不会被丢到小核
    override fun onBind(intent: Intent?): IBinder? { if (intent != null) run(intent); return android.os.Binder() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (intent != null) run(intent); return START_NOT_STICKY }
    private fun run(intent: Intent) {
        // DSP 侧按 ADSP_LIBRARY_PATH 找 skel，必须在本进程里设置
        runCatching { android.system.Os.setenv("ADSP_LIBRARY_PATH", "${applicationInfo.nativeLibraryDir};/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true) }
        val logFile = File(intent.getStringExtra("log") ?: return)
        val model = intent.getStringExtra("model"); val tier = intent.getStringExtra("tier") ?: "cpu"
        Thread {
            val w = logFile.bufferedWriter()
            val out: (String) -> Unit = { synchronized(w) { w.write(it); w.newLine(); w.flush() } }
            try {
                out("PID|${android.os.Process.myPid()}")
                if (model == null) Probe.header(out, "App 进程") else Probe.runOne(File(model), tier, out)
            } catch (t: Throwable) { out("异常：$t") }
            out("END"); w.close()
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }
}
