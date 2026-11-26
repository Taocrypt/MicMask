package com.taocrypt.micmask.repo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.taocrypt.micmask.L
import com.taocrypt.micmask.R
import com.taocrypt.micmask.tr

/**
 * 模型/TTS/素材下载期间的前台服务：常驻进度通知 + 部分唤醒锁。
 * 小米 M332BF、2604FRK1EC、一加 PJD110 上切到后台下载就断（SocketException / 模型下载失败），
 * 推断是后台应用被限网/冻结；前台服务期间应用不算后台（alpha.7）。
 * 下载本身仍在 ViewModel 协程里，这里只负责「保活 + 显示进度」。
 */
class DownloadService : Service() {
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        com.taocrypt.micmask.I18n.init(applicationContext)
        runCatching {
            ServiceCompat.startForeground(this, NID, note(applicationContext),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.onFailure { L.w("下载前台服务启动失败：${it.message}"); stopSelf(); return START_NOT_STICKY }
        if (wake == null) runCatching {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "micmask:download").apply { setReferenceCounted(false); acquire(60 * 60_000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wake?.release() }; wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CH = "model_download"
        private const val NID = 4107
        @Volatile private var title = ""
        @Volatile private var pct = -1
        @Volatile private var sub = ""
        @Volatile private var lastNotify = 0L
        @Volatile private var running = false

        private fun nm(ctx: Context): NotificationManager {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, tr("模型下载"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            return nm
        }

        private fun note(ctx: Context): android.app.Notification {
            nm(ctx)
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            val text = (if (pct >= 0) "$pct%" else tr("准备下载…")) + if (sub.isNotEmpty()) " · $sub" else ""
            return NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title).setContentText(text)
                .setOnlyAlertOnce(true).setOngoing(true).setSilent(true).setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setProgress(100, pct.coerceAtLeast(0), pct < 0)
                .apply { if (open != null) setContentIntent(open) }.build()
        }

        /** 开始下载时调用（应用在前台时才能启动前台服务） */
        fun start(ctx: Context, what: String) {
            title = tr("正在下载 {0}", what); pct = -1; sub = ""; running = true
            runCatching {
                val i = Intent(ctx, DownloadService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }.onFailure { L.w("下载前台服务启动失败：${it.message}") }
        }

        /** 进度 0..1；最多每 0.5 s 刷新一次通知 */
        fun progress(ctx: Context, p: Float, name: String) {
            if (!running) return
            pct = (p * 100).toInt().coerceIn(0, 100); sub = name
            val now = System.currentTimeMillis()
            if (now - lastNotify < 500) return
            lastNotify = now
            runCatching { nm(ctx).notify(NID, note(ctx)) }
        }

        fun stop(ctx: Context) {
            running = false
            runCatching { ctx.stopService(Intent(ctx, DownloadService::class.java)) }
        }
    }
}
