package com.taocrypt.micmask

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.taocrypt.micmask.inject.MicInjector

/** 前台服务：在 App 进程里跑流式 RVC，保证切到 QQ/微信后不被系统冻结。 */
class LiveService : Service() {

    companion object {
        private var pending: Pair<Triple<String, Int, (String) -> Unit>, Int>? = null
        private const val CH = "micmask_live"

        fun start(ctx: Context, target: String, semis: Int, source: Int, status: (String) -> Unit) {
            pending = Triple(target, semis, status) to source
            ctx.startForegroundService(Intent(ctx, LiveService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LiveService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        L.init(applicationContext)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "实时变声", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MicMask 实时变声运行中")
            .setContentText("回到 App 点「停止实时变声」结束")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(42, n)

        val p = pending ?: return START_NOT_STICKY
        pending = null
        val (target, semis, status) = p.first
        val source = p.second
        Thread {
            val main = android.os.Handler(mainLooper)
            try {
                if (!MicInjector.shizukuGranted()) { main.post { status("Shizuku 未授权") }; stopSelf(); return@Thread }
                main.post { status("把模型交给注入服务…") }
                val names = ModelStore.liveNames(applicationContext)
                val err = MicInjector.sendLiveModels(applicationContext, names)
                if (err != null) throw IllegalStateException("传模型失败：$err")
                // 推理放在 shell 进程里跑：App 切到后台会被系统降频/冻结（日志里出现过 11~28 s 停顿），shell 进程不会
                MicInjector.startLive(applicationContext, target, semis.toFloat(),
                    source, external = true,
                    primeMs = 380, rvcInShell = true) { msg ->
                    status(msg)
                    if (!MicInjector.liveOn) stopSelf()
                }
            } catch (t: Throwable) {
                L.e("LiveService", t)
                main.post { status("启动失败：${t.message}") }
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
