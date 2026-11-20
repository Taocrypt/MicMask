package com.taocrypt.micmask.ui2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 关于页彩蛋：满屏烟花。约 4.5 秒后自动结束（[onDone]）。
 * 用不抢焦点的 Popup 盖在整个窗口上，纯 Canvas 粒子，无额外资源。
 */
private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Color, var life: Float, val maxLife: Float, val trail: Boolean)
private class Rocket(var x: Float, var y: Float, val vy: Float, val targetY: Float, val color: Color, var done: Boolean = false)

private val PALETTE = listOf(0xFFFF4D6D, 0xFFFFC145, 0xFF4DD4FF, 0xFF7CFF6B, 0xFFB57BFF, 0xFFFF8A3D, 0xFFFFFFFF, 0xFF3482FF).map { Color(it) }

@Composable
fun Fireworks(key: Int, onDone: () -> Unit) {
    val sparks = remember(key) { ArrayList<Spark>() }
    val rockets = remember(key) { ArrayList<Rocket>() }
    var tick by remember(key) { mutableLongStateOf(0L) }
    var size by remember(key) { mutableLongStateOf(0L) }   // packed w/h，Canvas 首帧写入
    LaunchedEffect(key) {
        val rnd = Random(System.nanoTime())
        var last = withFrameNanos { it }; val start = last
        var nextLaunch = 0f
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceAtMost(0.05f); last = now
            val t = (now - start) / 1e9f
            val w = (size shr 32).toFloat(); val h = (size and 0xFFFFFFFF).toFloat()
            if (w > 0 && h > 0) {
                // 前 3 秒持续发射，越往后越密
                if (t < 3f && t >= nextLaunch) {
                    repeat(if (t < 0.3f) 3 else 1 + rnd.nextInt(2)) {
                        rockets += Rocket(w * (0.1f + rnd.nextFloat() * 0.8f), h + 10f, -(h * (1.1f + rnd.nextFloat() * 0.35f)),
                            h * (0.12f + rnd.nextFloat() * 0.38f), PALETTE[rnd.nextInt(PALETTE.size)])
                    }
                    nextLaunch = t + 0.25f + rnd.nextFloat() * 0.3f
                }
                val g = h * 0.35f
                rockets.forEach { r ->
                    r.y += r.vy * dt
                    sparks += Spark(r.x + (rnd.nextFloat() - .5f) * 3f, r.y, 0f, h * 0.05f, Color(0xFFFFE9B0), 0.35f, 0.35f, true)
                    if (r.y <= r.targetY) {
                        r.done = true
                        val n = 70 + rnd.nextInt(40); val sp = h * (0.22f + rnd.nextFloat() * 0.12f)
                        val c2 = PALETTE[rnd.nextInt(PALETTE.size)]
                        for (i in 0 until n) {
                            val a = (i.toFloat() / n) * 6.2832f + rnd.nextFloat() * 0.1f
                            val v = sp * (0.55f + rnd.nextFloat() * 0.45f)
                            val life = 1.1f + rnd.nextFloat() * 0.7f
                            sparks += Spark(r.x, r.y, cos(a) * v, sin(a) * v, if (i % 3 == 0) c2 else r.color, life, life, false)
                        }
                    }
                }
                rockets.removeAll { it.done }
                val it = sparks.iterator()
                while (it.hasNext()) {
                    val s = it.next()
                    s.life -= dt
                    if (s.life <= 0) { it.remove(); continue }
                    s.vx *= (1f - 1.6f * dt); s.vy = s.vy * (1f - 1.6f * dt) + g * dt
                    s.x += s.vx * dt; s.y += s.vy * dt
                }
                if (t > 3f && rockets.isEmpty() && sparks.isEmpty()) { onDone(); break }
                if (t > 8f) { onDone(); break }
            }
            tick = now
        }
    }
    val atOrigin = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        }
    }
    Popup(popupPositionProvider = atOrigin, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        Canvas(Modifier.fillMaxSize()) {
            size = (this.size.width.toLong() shl 32) or this.size.height.toLong()
            tick  // 读一下，触发每帧重绘
            sparks.forEach { s ->
                val a = (s.life / s.maxLife).coerceIn(0f, 1f)
                val r = if (s.trail) 2.2.dp.toPx() * a else 2.6.dp.toPx() * (0.4f + 0.6f * a)
                drawCircle(s.color.copy(alpha = a * 0.35f), r * 2.4f, Offset(s.x, s.y), blendMode = BlendMode.Plus)
                drawCircle(s.color.copy(alpha = a), r, Offset(s.x, s.y))
            }
            rockets.forEach { r -> drawCircle(Color.White, 2.5.dp.toPx(), Offset(r.x, r.y)) }
        }
    }
}
