package com.app.shadetube.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Now Playing panel (Sıradaki · Şarkı sözleri · İlgili) as a draggable sheet: [fraction] 1 = collapsed (only the
 * pill shows), 0 = expanded (the sheet fills the screen under the compact header). Follows the finger on its header and
 * through [nestedScroll] of its list (dragging a list that is at its top pulls the sheet down), then settles by
 * velocity / halfway point.
 */
@Stable
internal class PanelSheetState(private val scope: CoroutineScope, private val spec: () -> AnimationSpec<Float>) {
    val anim = Animatable(1f)
    val fraction: Float get() = anim.value

    /** The distance between the two positions (px), set by the layout. */
    var rangePx: Float = 1f

    val isExpanded: Boolean get() = anim.targetValue < 0.5f
    val isCollapsed: Boolean get() = anim.value >= 0.999f && anim.targetValue >= 0.999f

    fun dragBy(dy: Float) {
        if (rangePx <= 0f) return
        scope.launch { anim.snapTo((anim.value + dy / rangePx).coerceIn(0f, 1f)) }
    }

    fun settle(velocityY: Float) {
        val target = when {
            velocityY < -900f -> 0f
            velocityY > 900f -> 1f
            anim.value < 0.5f -> 0f
            else -> 1f
        }
        scope.launch { anim.animateTo(target, spec()) }
    }

    fun expand() {
        scope.launch { anim.animateTo(0f, spec()) }
    }

    fun collapse() {
        scope.launch { anim.animateTo(1f, spec()) }
    }

    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Finger moving up while the sheet is not fully open: open it first.
            if (available.y < 0f && anim.value > 0f && source == NestedScrollSource.UserInput) {
                dragBy(available.y)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // The list is at its top and the finger keeps moving down: pull the sheet down.
            if (available.y > 0f && source == NestedScrollSource.UserInput) {
                dragBy(available.y)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val v = anim.value
            if (v > 0.001f && v < 0.999f) {
                settle(available.y)
                return available
            }
            return Velocity.Zero
        }
    }
}
