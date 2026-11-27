package com.taocrypt.micmask.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.taocrypt.micmask.I18n
import com.taocrypt.micmask.L
import com.taocrypt.micmask.MainActivity
import com.taocrypt.micmask.R
import com.taocrypt.micmask.tr

/**
 * 传音前台服务：网络收发在 App 进程里，切到 QQ/微信/游戏后要靠它不被系统冻结。
 * 类型 mediaPlayback（接收外放 / 注入）+ microphone（未开变声时 App 自己录原始麦克风发送，有录音权限才加）。
 */
class LinkService : Service() {
    companion object {
        private const val CH = "micmask_link"
        private const val NID = 43
        fun start(ctx: Context) { runCatching { ctx.startForegroundService(Intent(ctx, LinkService::class.java)) }.onFailure { L.w("传音：前台服务启动失败 ${it.message}") } }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, LinkService::class.java)) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        L.init(applicationContext); I18n.init(applicationContext)
        if (intent?.action == "stop") { LinkHub.stopSend(); LinkHub.stopReceive(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, tr("传音"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 21, Intent(this, LinkService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val st = LinkHub.state.value
        val what = listOfNotNull(if (st.txOn) tr("发送中") else null, if (st.rxOn) tr("接收中") else null).joinToString(" · ").ifEmpty { tr("传音") }
        val n: Notification = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(tr("传音 {0}", what))
            .setContentText(tr("局域网明文传输，请只在可信网络使用"))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, tr("停止"), stop).build())
            .build()
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 30 && mic) {
            runCatching { startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
                .onFailure { runCatching { startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) } }
        } else if (Build.VERSION.SDK_INT >= 29) runCatching { startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        else startForeground(NID, n)
        return START_NOT_STICKY
    }
}
