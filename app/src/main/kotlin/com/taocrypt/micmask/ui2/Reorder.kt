package com.taocrypt.micmask.ui2

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex

/**
 * 小列表拖动排序（非 Lazy，适合几十项以内）。
 * row(item, handle, dragging)：把 handle 加到「拖动把手」上；长按把手（震一下）后上下拖，越过相邻项一半高度即交换
 * （每次交换轻震一下），松手后 onMove(原位置, 新位置) 一次性提交。
 */
@Composable
fun <T> ReorderList(items: List<T>, key: (T) -> Any, onMove: (Int, Int) -> Unit,
                    row: @Composable (item: T, handle: Modifier, dragging: Boolean) -> Unit) {
    var list by remember(items) { mutableStateOf(items) }
    var dragKey by remember { mutableStateOf<Any?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Any, Int>() }
    val cur by rememberUpdatedState(items)
    val move by rememberUpdatedState(onMove)
    val view = LocalView.current
    fun buzz(c: Int) { runCatching { view.performHapticFeedback(c) } }
    val dropFx = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK
    Column {
        for (it in list) {
            val k = key(it)
            key(k) {
                val dragging = dragKey == k
                val handle = Modifier.pointerInput(k) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragKey = k; offset = 0f; buzz(HapticFeedbackConstants.LONG_PRESS) },
                        onDragEnd = {
                            val from = cur.indexOfFirst { x -> key(x) == k }; val to = list.indexOfFirst { x -> key(x) == k }
                            dragKey = null; offset = 0f; buzz(dropFx)
                            if (from >= 0 && to >= 0 && from != to) move(from, to)
                        },
                        onDragCancel = { dragKey = null; offset = 0f; list = cur },
                    ) { ch, d ->
                        ch.consume(); offset += d.y
                        val i = list.indexOfFirst { x -> key(x) == k }
                        if (offset > 0 && i < list.lastIndex) {
                            val h = heights[key(list[i + 1])] ?: Int.MAX_VALUE
                            if (offset > h / 2f) { list = list.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }; offset -= h; buzz(HapticFeedbackConstants.CLOCK_TICK) }
                        } else if (offset < 0 && i > 0) {
                            val h = heights[key(list[i - 1])] ?: Int.MAX_VALUE
                            if (-offset > h / 2f) { list = list.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }; offset += h; buzz(HapticFeedbackConstants.CLOCK_TICK) }
                        }
                    }
                }
                androidx.compose.foundation.layout.Box(Modifier.onSizeChanged { s -> heights[k] = s.height }
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { if (dragging) { translationY = offset; scaleX = 1.02f; scaleY = 1.02f; alpha = 0.92f } }) {
                    row(it, handle, dragging)
                }
            }
        }
    }
}
