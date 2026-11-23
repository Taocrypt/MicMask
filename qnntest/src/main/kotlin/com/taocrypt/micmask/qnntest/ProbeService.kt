package com.taocrypt.micmask.qnntest

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/** App 进程测试：跑在独立的 :probe 进程里，结果逐行写文件；每项跑完自杀，崩溃也不影响界面 */
class ProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val logFile = File(intent?.getStringExtra("log") ?: return START_NOT_STICKY)
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
        return START_NOT_STICKY
    }
}
