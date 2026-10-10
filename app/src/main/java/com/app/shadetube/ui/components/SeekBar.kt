package com.app.shadetube.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.shadetube.R
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme

/**
 * Seek bar (COMPONENTS: İlerleme çubuğu, README): custom Canvas instead of Slider. 4dp bar: track text1 @12 % →
 * buffered accent.progressTrack → played accent; thumb 12dp, 16dp while dragging (heartPop spring). Times below
 * (8dp gap) in mono 12 text2. Tap or drag to seek; [onSeek] fires once on release (the bar shows the dragged
 * position meanwhile; [onScrub] reports it live, e.g. for a lyrics preview). Accessible via progress semantics with
 * a setProgress action.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    bufferedMs: Long = 0,
    enabled: Boolean = true,
    showTimes: Boolean = true,
    onScrub: ((Long?) -> Unit)? = null,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val dur = durationMs.coerceAtLeast(0)
    val played = dragFraction ?: if (dur > 0) (positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val buffered = if (dur > 0) (bufferedMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val thumb by animateDpAsState(
        if (dragFraction != null) 16.dp else 12.dp, ShadeMotion.orReduced(ShadeMotion.heartPop()), label = "thumb",
    )
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val cd = stringResource(R.string.seek_bar_cd)
    val shownPos = (played * dur).toLong()
    val state = "${formatDuration(shownPos)} / ${formatDuration(dur)}"

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .progressSemantics(played)
                .semantics {
                    contentDescription = cd
                    stateDescription = state
                    if (enabled && dur > 0) setProgress { v -> currentOnSeek((v.coerceIn(0f, 1f) * dur).toLong()); true }
                }
                .then(
                    if (!enabled || dur <= 0) Modifier else Modifier
                        .pointerInput(dur) {
                            detectTapGestures { o ->
                                currentOnSeek(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong())
                            }
                        }
                        .pointerInput(dur) {
                            detectHorizontalDragGestures(
                                onDragStart = { o ->
                                    dragFraction = (o.x / size.width).coerceIn(0f, 1f)
                                    currentOnScrub?.invoke((dragFraction!! * dur).toLong())
                                },
                                onDragEnd = {
                                    dragFraction?.let { currentOnSeek((it * dur).toLong()) }
                                    dragFraction = null
                                    currentOnScrub?.invoke(null)
                                },
                                onDragCancel = {
                                    dragFraction = null
                                    currentOnScrub?.invoke(null)
                                },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                    currentOnScrub?.invoke((dragFraction!! * dur).toLong())
                                },
                            )
                        },
                ),
        ) {
            val h = 4.dp.toPx()
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(x.text1.copy(alpha = 0.12f), Offset(0f, y), Size(size.width, h), r)
            if (buffered > 0f) drawRoundRect(accent.progressTrack, Offset(0f, y), Size(size.width * buffered, h), r)
            if (played > 0f) drawRoundRect(accent.accent, Offset(0f, y), Size(size.width * played, h), r)
            if (enabled) {
                val t = thumb.toPx()
                drawCircle(accent.accent, t / 2, Offset((size.width * played).coerceIn(t / 2, size.width - t / 2), size.height / 2))
            }
        }
        if (showTimes) {
            // the 24dp touch strip already leaves the 8dp gap below the 4dp bar
            Row(Modifier.fillMaxWidth()) {
                val style = ShadeMono.medium.copy(fontSize = 12.sp)
                Text(formatDuration(shownPos), style = style, color = x.text2)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(dur), style = style, color = x.text2)
            }
        }
    }
}
