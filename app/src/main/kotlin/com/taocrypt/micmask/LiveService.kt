package com.taocrypt.micmask

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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

        /** 普通变声/声卡：只挂常驻通知（不在 App 进程里跑推理） */
        fun notifyOnly(ctx: Context) {
            pending = null
            ctx.startForegroundService(Intent(ctx, LiveService::class.java))
        }

        /** 通知栏「停止」回调（由 ViewModel 设置，走和 App 内一样的停止流程） */
        @Volatile var onStop: (() -> Unit)? = null
        @Volatile var running = false; private set

        /** 悬浮窗开关/模式变化后刷新通知文字与按钮 */
        fun refresh(ctx: Context) {
            if (!running) return
            ctx.getSystemService(NotificationManager::class.java).notify(NID, build(ctx))
        }

        private const val NID = 42

        private fun build(ctx: Context): Notification {
            val mode = MicInjector.modeLabel ?: "实时变声"
            val open = PendingIntent.getActivity(ctx, 10, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(ctx, 11, Intent(ctx, LiveService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val b = Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("$mode 运行中")
                .setContentText("点「停止」结束，或回到 App 点当前模式的「停止」")
                .setContentIntent(open)
                .setOngoing(true)
            if (!LiveFloatService.running) {
                val fl = PendingIntent.getService(ctx, 12, Intent(ctx, LiveService::class.java).setAction("float"), PendingIntent.FLAG_IMMUTABLE)
                b.addAction(Notification.Action.Builder(null, "开启悬浮窗", fl).build())
            }
            b.addAction(Notification.Action.Builder(null, "停止", stop).build())
            return b.build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        L.init(applicationContext)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "实时变声", NotificationManager.IMPORTANCE_LOW))
        when (intent?.action) {
            "stop" -> {
                val f = onStop
                if (f != null) android.os.Handler(mainLooper).post { f() } else MicInjector.stopLive()
                stopSelf(); return START_NOT_STICKY
            }
            "float" -> {
                if (FloatingPlayService.canDraw(this)) {
                    Prefs.main(applicationContext).edit().putBoolean("lf_on", true).apply()
                    LiveFloatService.toggle(applicationContext, true)
                } else {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                return START_NOT_STICKY
            }
        }
        val n = build(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NID, n)
        running = true

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
        running = false
        super.onDestroy()
    }
}
