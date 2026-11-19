package com.taocrypt.micmask

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.SystemClock
import android.view.View
import android.view.animation.AnticipateInterpolator
import android.view.animation.DecelerateInterpolator
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
            // 退场：图标轻微回缩后放大淡出，背景同步淡出露出主界面
            val icon: View? = runCatching { p.iconView }.getOrNull()
            val bg = p.view
            val set = AnimatorSet()
            val anims = mutableListOf<android.animation.Animator>(
                ObjectAnimator.ofFloat(bg, View.ALPHA, 1f, 0f).apply { duration = 320; startDelay = 120; interpolator = DecelerateInterpolator() })
            if (icon != null) {
                anims += ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.35f).apply { duration = 420; interpolator = AnticipateInterpolator(1.2f) }
                anims += ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.35f).apply { duration = 420; interpolator = AnticipateInterpolator(1.2f) }
                anims += ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0f).apply { duration = 260; startDelay = 160 }
            }
            set.playTogether(anims)
            set.doOnEnd { p.remove() }
            set.start()
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
