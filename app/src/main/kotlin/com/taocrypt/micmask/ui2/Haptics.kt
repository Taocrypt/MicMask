package com.taocrypt.micmask.ui2

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 旋钮/推子用的触觉反馈，按机器能力分三级：
 * - Android 14+：系统的 SEGMENT_FREQUENT_TICK / SEGMENT_TICK（专为滑块设计，线性马达上是细颗粒的「咔哒」，跟随系统触感开关）
 * - Android 11–13 且马达支持原语：VibrationEffect 组合原语 LOW_TICK / TICK / CLICK，按强度缩放
 * - 其他（多为转子马达）：只在刻度和端点给 CLOCK_TICK，不做细刻度，免得一路嗡嗡
 * 细刻度限速 12 ms 一次，快速拖动时不会糊成连续震动。
 */
class Haptics(private val view: View) {
    private val vib: Vibrator? = runCatching {
        val c = view.context
        if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    }.getOrNull()
    private val prims: Boolean = Build.VERSION.SDK_INT >= 30 && runCatching {
        vib?.hasVibrator() == true && vib.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK)
    }.getOrDefault(false)
    private var lastFine = 0L

    @Suppress("DEPRECATION")
    private fun sysOn(): Boolean = runCatching {
        Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }.getOrDefault(true)

    private fun prim(p: Int, scale: Float) {
        if (!sysOn()) return
        runCatching { vib?.vibrate(VibrationEffect.startComposition().addPrimitive(p, scale).compose()) }
    }

    /** 每走一小格（1%）轻轻一下 */
    fun fine() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFine < 12) return
        lastFine = now
        when {
            Build.VERSION.SDK_INT >= 34 -> runCatching { view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK) }
            prims -> prim(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.35f)
        }
    }

    /** 大刻度（每 10%）、越过默认值 */
    fun detent() {
        lastFine = SystemClock.uptimeMillis()
        when {
            Build.VERSION.SDK_INT >= 34 -> runCatching { view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK) }
            prims -> prim(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f)
            else -> runCatching { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        }
    }

    /** 顶到头 / 双击复位 */
    fun edge() {
        lastFine = SystemClock.uptimeMillis()
        when {
            prims -> prim(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
            Build.VERSION.SDK_INT >= 30 -> runCatching { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
            else -> runCatching { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        }
    }

    /**
     * 值从 old 变到 new（0..1）时该怎么震：越过 10% 刻度或默认值 → detent，碰到 0/1 → edge，其余每 1% → fine。
     */
    fun onValue(old: Float, new: Float, home: Float?) {
        if (old == new) return
        val o = (old * 100).toInt(); val n = (new * 100).toInt()
        when {
            (new <= 0f || new >= 1f) && old != new && (old > 0f && old < 1f) -> edge()
            home != null && (old - home) * (new - home) < 0f -> detent()
            o / 10 != n / 10 -> detent()
            o != n -> fine()
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val v = LocalView.current
    return remember(v) { Haptics(v) }
}
