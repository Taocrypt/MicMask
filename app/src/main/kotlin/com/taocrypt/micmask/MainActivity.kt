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
        // 首帧就绪或最多 1.5 秒后放行
        splash.setKeepOnScreenCondition { !firstFrame && SystemClock.uptimeMillis() - t0 < 1500 }
        splash.setOnExitAnimationListener { p ->
            // 退场：系统圆形遮罩从屏幕收拢到图标中心（原生 circular reveal），图标同时淡出
            val v = p.view
            val icon: View? = runCatching { p.iconView }.getOrNull()
            val cx = icon?.let { (it.left + it.right) / 2 } ?: (v.width / 2)
            val cy = icon?.let { (it.top + it.bottom) / 2 } ?: (v.height / 2)
            val r = Math.hypot(maxOf(cx, v.width - cx).toDouble(), maxOf(cy, v.height - cy).toDouble()).toFloat()
            val reveal = runCatching { android.view.ViewAnimationUtils.createCircularReveal(v, cx, cy, r, 0f) }.getOrNull()
            if (reveal == null || !v.isAttachedToWindow) { p.remove(); return@setOnExitAnimationListener }
            reveal.duration = 420
            reveal.interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            reveal.doOnEnd { p.remove() }
            icon?.animate()?.alpha(0f)?.setDuration(220)?.start()
            reveal.start()
        }
        enableEdgeToEdge()
        L.init(applicationContext)
        handleIntent(intent)
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
    }

    // 从系统授权页 / 无障碍设置返回时刷新状态
    override fun onResume() {
        super.onResume()
        vmRef?.refreshQqWatchState()
        vmRef?.refreshInjectState()
        vmRef?.refreshFloatingState()
        vmRef?.refreshLiveFloat()
    }
}
