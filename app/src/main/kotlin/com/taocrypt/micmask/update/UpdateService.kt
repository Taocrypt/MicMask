package com.taocrypt.micmask.update

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * 下载更新期间的前台服务：保证通知常驻、切到后台不被系统杀掉。
 * 通知内容由 Updater 持续用同一个 id 刷新进度；下载结束（完成/失败/取消）时停止。
 */
class UpdateService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        com.taocrypt.micmask.I18n.init(applicationContext)
        if (intent?.action == ACTION_CANCEL) {
            Updater.cancelDownload(applicationContext); return START_NOT_STICKY
        }
        val n = Updater.progressNotification(applicationContext)
        runCatching {
            ServiceCompat.startForeground(this, Updater.NID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.taocrypt.micmask.update.CANCEL"
        fun start(ctx: Context) = runCatching {
            val i = Intent(ctx, UpdateService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, UpdateService::class.java)) }
    }
}
