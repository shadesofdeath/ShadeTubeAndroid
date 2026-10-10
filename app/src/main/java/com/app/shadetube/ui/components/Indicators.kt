package com.app.shadetube.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.shadetube.R
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme

/**
 * "Now playing" bars (README › Çalıyor göstergesi): 3 bars, scaleY .35 ↔ 1 with 1000 / 800 / 1100 ms cycles,
 * bottom-anchored; when [playing] turns false they sink to .35 in 250 ms. Bars 3dp wide with 2dp gaps (16dp box).
 */
@Composable
fun NowPlayingBars(
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = ShadeTheme.accent.accent,
    size: Dp = 16.dp,
    barWidth: Dp = 3.dp,
    gap: Dp = 2.dp,
) {
    // initial phases as in avd_now_playing_bars: .35 / 1 / .6
    val bars = remember { listOf(Animatable(0.35f), Animatable(1f), Animatable(0.6f)) }
    val cycles = intArrayOf(1000, 800, 1100)
    bars.forEachIndexed { i, anim ->
        LaunchedEffect(playing) {
            if (playing) {
                var up = anim.value < 0.7f
                while (true) {
                    val target = if (up) 1f else 0.35f
                    val half = cycles[i] / 2
                    val dur = (half * kotlin.math.abs(target - anim.value) / 0.65f).toInt().coerceAtLeast(60)
                    anim.animateTo(target, tween(dur, easing = FastOutSlowInEasing))
                    up = !up
                }
            } else {
                anim.animateTo(0.35f, tween(ShadeMotion.STANDARD, easing = ShadeMotion.standard))
            }
        }
    }
    Canvas(modifier.size(size)) {
        val w = barWidth.toPx()
        val g = gap.toPx()
        val total = w * 3 + g * 2
        val x0 = (this.size.width - total) / 2
        val r = CornerRadius(w / 3, w / 3)
        bars.forEachIndexed { i, a ->
            val h = this.size.height * a.value
            drawRoundRect(color, Offset(x0 + i * (w + g), this.size.height - h), Size(w, h), r)
        }
    }
}

/**
 * Circular progress ring (nav Library download ring, download indicator): [stroke] wide, track
 * accent.progressTrack, arc accent from −90°. [progress] null = indeterminate (270° arc spinning, 1200 ms linear).
 */
@Composable
fun ProgressRing(
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    stroke: Dp = 2.dp,
    color: Color = ShadeTheme.accent.accent,
    trackColor: Color = ShadeTheme.accent.progressTrack,
) {
    val rotation = if (progress == null) {
        val t = rememberInfiniteTransition(label = "ring")
        val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "ringRot")
        r
    } else 0f
    val animated by animateFloatAsState(
        (progress ?: 0f).coerceIn(0f, 1f), tween(ShadeMotion.STANDARD, easing = ShadeMotion.standard), label = "ringP",
    )
    Canvas(
        modifier
            .size(size)
            .then(if (progress != null) Modifier.progressSemantics(animated) else Modifier.progressSemantics()),
    ) {
        val s = stroke.toPx()
        val inset = s / 2
        val arcSize = Size(this.size.width - s, this.size.height - s)
        drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(s))
        if (progress == null) {
            drawArc(color, rotation - 90f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
        } else if (animated > 0f) {
            drawArc(color, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
    }
}

/** Download state of a row / episode (animations/download_progress.md). */
sealed interface DownloadUiState {
    data object None : DownloadUiState
    data object Queued : DownloadUiState
    /** [progress] null = size unknown (indeterminate). */
    data class Downloading(val progress: Float?) : DownloadUiState
    data object Done : DownloadUiState
    data object Failed : DownloadUiState
}

/**
 * Download indicator (animations/download_progress.md): Queued = ic_download text3; Downloading = 20dp ring with an
 * 8dp stop square; Done = ic_downloaded in accent popping 1 → 1.15 → 1; Failed = error ring + ic_refresh.
 * 24dp; wrap in a clickable / icon button for actions.
 */
@Composable
fun DownloadIndicator(state: DownloadUiState, modifier: Modifier = Modifier) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val cd = when (state) {
        is DownloadUiState.Downloading -> stringResource(R.string.download_progress_cd, ((state.progress ?: 0f) * 100).toInt())
        DownloadUiState.Done -> stringResource(R.string.track_downloaded_cd)
        else -> null
    }
    Box(
        modifier.size(24.dp).then(if (cd != null) Modifier.semantics { contentDescription = cd } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state::class,
            transitionSpec = { fadeIn(tween(ShadeMotion.SHORT)) togetherWith fadeOut(tween(ShadeMotion.SHORT)) },
            label = "download",
        ) { kind ->
            when (kind) {
                DownloadUiState.Queued::class -> ShadeIcon(R.drawable.ic_download, null, tint = x.text3)
                DownloadUiState.Downloading::class -> Box(contentAlignment = Alignment.Center) {
                    ProgressRing((state as? DownloadUiState.Downloading)?.progress, size = 20.dp)
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(1.dp)).background(accent.accent))
                }
                DownloadUiState.Done::class -> {
                    val pop = remember { Animatable(1f) }
                    val reduce = ShadeTheme.reduceMotion
                    LaunchedEffect(Unit) {
                        if (!reduce) {
                            pop.animateTo(1.15f, tween(100))
                            pop.animateTo(1f, ShadeMotion.heartPop())
                        }
                    }
                    ShadeIcon(
                        R.drawable.ic_downloaded, null, tint = accent.accent,
                        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                    )
                }
                DownloadUiState.Failed::class -> Box(contentAlignment = Alignment.Center) {
                    val ring by animateColorAsState(x.error, tween(ShadeMotion.SHORT), label = "err")
                    ProgressRing(1f, size = 20.dp, color = ring, trackColor = Color.Transparent)
                    ShadeIcon(R.drawable.ic_refresh, null, tint = x.error, size = 12.dp)
                }
                else -> Box(Modifier.size(24.dp))
            }
        }
    }
}

/**
 * Thin linear progress (mini player 2dp, sync card 3dp, episode rows): track text1 @12 %, played accent.
 * [progress] null = indeterminate (a 30 % segment sliding, used while buffering / connecting).
 */
@Composable
fun LinearProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 2.dp,
    color: Color = ShadeTheme.accent.accent,
    trackColor: Color = ShadeTheme.extras.text1.copy(alpha = 0.12f),
) {
    val slide = if (progress == null) {
        val t = rememberInfiniteTransition(label = "linear")
        val v by t.animateFloat(
            0f, 1f, infiniteRepeatable(tween(1200, easing = ShadeMotion.standard), RepeatMode.Restart), label = "slide",
        )
        v
    } else 0f
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .then(if (progress != null) Modifier.progressSemantics(progress.coerceIn(0f, 1f)) else Modifier.progressSemantics())
            .drawBehind {
                drawRect(trackColor)
                val r = CornerRadius(size.height / 2, size.height / 2)
                if (progress == null) {
                    val segW = size.width * 0.3f
                    val x0 = -segW + (size.width + segW) * slide
                    drawRoundRect(color, Offset(x0, 0f), Size(segW, size.height), r)
                } else {
                    drawRoundRect(color, Offset.Zero, Size(size.width * progress.coerceIn(0f, 1f), size.height), r)
                }
            },
    )
}
