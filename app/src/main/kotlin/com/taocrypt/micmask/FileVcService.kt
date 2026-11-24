package com.taocrypt.micmask

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 文件变声期间的前台服务（借鉴 mobile-rvc）：切到后台不被杀，通知栏显示分段进度，可点「取消」。
 * 进度由 MainViewModel 调 update() 刷新（限 4 次/秒）。
 */
class FileVcService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        I18n.init(applicationContext)
        if (intent?.action == ACTION_CANCEL) { onCancel?.invoke(); return START_NOT_STICKY }
        runCatching {
            ServiceCompat.startForeground(this, NID, note(applicationContext),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.onFailure { L.w("文件变声前台服务启动失败：${it.message}"); stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val NID = 7311
        private const val CH = "file_vc"
        private const val ACTION_CANCEL = "com.taocrypt.micmask.FILEVC_CANCEL"
        @Volatile private var title = ""
        @Volatile private var text = ""
        @Volatile private var pct = -1f
        @Volatile private var lastT = 0L
        @Volatile private var onCancel: (() -> Unit)? = null
        @Volatile private var running = false

        private fun nm(ctx: Context): NotificationManager {
            val m = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && m.getNotificationChannel(CH) == null)
                m.createNotificationChannel(NotificationChannel(CH, tr("文件变声进度"), NotificationManager.IMPORTANCE_LOW))
            return m
        }

        private fun note(ctx: Context): android.app.Notification {
            nm(ctx)
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val cancel = PendingIntent.getService(ctx, 1, Intent(ctx, FileVcService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val p = (pct * 100).toInt()
            return NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(tr("文件变声 {0}", title)).setContentText(if (pct >= 0) "$p% · $text" else text.ifEmpty { tr("准备中…") })
                .setOnlyAlertOnce(true).setOngoing(true).setSilent(true).setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setProgress(100, p.coerceAtLeast(0), pct < 0).setContentIntent(open)
                .addAction(0, tr("取消"), cancel).build()
        }

        fun start(ctx: Context, name: String, cancel: () -> Unit) {
            title = name.take(40); text = ""; pct = -1f; onCancel = cancel; running = true
            runCatching {
                val i = Intent(ctx, FileVcService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }.onFailure { L.w("文件变声前台服务启动失败：${it.message}") }
        }

        fun update(ctx: Context, stage: String, p: Float) {
            if (!running) return
            text = stage; pct = p
            val now = System.currentTimeMillis()
            if (now - lastT < 250 && p >= 0) return
            lastT = now
            runCatching { nm(ctx).notify(NID, note(ctx)) }
        }

        fun stop(ctx: Context) {
            running = false; onCancel = null
            runCatching { ctx.stopService(Intent(ctx, FileVcService::class.java)) }
            runCatching { nm(ctx).cancel(NID) }
        }
    }
}
