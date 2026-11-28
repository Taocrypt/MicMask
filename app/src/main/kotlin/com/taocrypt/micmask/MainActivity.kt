package com.taocrypt.micmask

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {

    private var vmRef: MainViewModel? = null

    /** 首帧画完前一直保留启动页，避免露出空白窗口 */
    @Volatile private var firstFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val t0 = SystemClock.uptimeMillis()
        // 首帧就绪或最多 0.8 秒后放行
        splash.setKeepOnScreenCondition { !firstFrame && SystemClock.uptimeMillis() - t0 < 800 }
        splash.setOnExitAnimationListener { p ->
            // 退场：背景和图标整体一起淡出（同 GitHub），图标不单独停留
            p.view.animate().alpha(0f).setDuration(180)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { p.remove() }.start()
        }
        enableEdgeToEdge()
        L.init(applicationContext)
        I18n.init(applicationContext)
        if (savedInstanceState == null) handleIntent(intent)
        // 启动检查：开关关闭时也查，只为强制更新（Updater 内部只在强制时弹框）
        com.taocrypt.micmask.update.Updater.check(applicationContext, silent = true)
        setContent {
            val vm: MainViewModel = viewModel()
            vmRef = vm
            com.taocrypt.micmask.ui2.V2App(vm)
            LaunchedEffect(Unit) { withFrameNanos { }; firstFrame = true }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: android.content.Intent?) {
        if (i?.getBooleanExtra("open_update", false) == true) com.taocrypt.micmask.update.Updater.dialog.value = true
        // 从 QQ/微信/文件管理器「分享」或「打开方式」进来（借鉴 mobile-rvc）：交给界面按扩展名分流
        val uris = when (i?.action) {
            android.content.Intent.ACTION_SEND -> listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(i, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java))
            android.content.Intent.ACTION_SEND_MULTIPLE -> androidx.core.content.IntentCompat.getParcelableArrayListExtra(i, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
            android.content.Intent.ACTION_VIEW -> listOfNotNull(i.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) { L.i("收到分享/打开 ${uris.size} 个文件 type=${i?.type}"); SharedIn.uris.value = uris; i?.action = null }
    }

    // 从系统授权页 / 无障碍设置返回时刷新状态
    override fun onResume() {
        super.onResume()
        vmRef?.refreshQqWatchState()
        vmRef?.refreshInjectState()
        vmRef?.refreshFloatingState()
        vmRef?.refreshLiveFloat()
        // 传音「自动连接」：回到前台时恢复接收 / 连上已配对的接收端（开关在传音页「高级」里，默认开）
        com.taocrypt.micmask.link.LinkHub.onForeground()
    }
}

/** 外部分享/打开方式送进来的文件，界面取走后清空 */
object SharedIn { val uris = kotlinx.coroutines.flow.MutableStateFlow<List<android.net.Uri>>(emptyList()) }
