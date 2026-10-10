package com.app.shadetube.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.shadetube.R
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTheme
import kotlin.math.tan

/**
 * Shimmer (README › Shimmer iskeletleri): a surface3 band sweeping over surface2, 1200 ms linear, tilted 20°.
 * Static surface2 with reduce motion.
 */
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(4.dp)): Modifier = composed {
    val x = ShadeTheme.extras
    val base = x.surface2
    val band = x.surface3
    if (ShadeTheme.reduceMotion) return@composed clip(shape).background(base)
    val t = rememberInfiniteTransition(label = "shimmer")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "p")
    clip(shape).drawWithCache {
        val bandW = maxOf(size.width * 0.35f, 80f)
        val slope = tan(Math.toRadians(20.0)).toFloat() * size.height
        onDrawBehind {
            drawRect(base)
            val start = -bandW - slope + (size.width + 2 * bandW + slope) * p
            drawRect(
                Brush.linearGradient(
                    0f to Color.Transparent, 0.5f to band, 1f to Color.Transparent,
                    start = Offset(start, 0f), end = Offset(start + bandW + slope, size.height),
                ),
            )
        }
    }
}

/** A shimmering placeholder block. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp)) {
    Box(modifier.shimmer(shape))
}

/** Text line placeholder: 12dp high band, 4dp corners. */
@Composable
fun SkeletonLine(width: Dp, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    SkeletonBox(modifier.width(width).height(height))
}

/** Loading [TrackRow]: square cover + 2 bands. Lists show 6 of them ([TrackListSkeleton]). */
@Composable
fun TrackRowSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
) {
    Row(
        modifier.fillMaxWidth().height(60.dp).padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBox(Modifier.size(44.dp), ShadeRadius.cover)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonLine(160.dp)
            SkeletonLine(96.dp)
        }
    }
}

@Composable
fun TrackListSkeleton(modifier: Modifier = Modifier, rows: Int = 6) {
    val loading = stringResource(R.string.state_loading)
    Column(modifier.clearAndSetSemantics { contentDescription = loading }) {
        repeat(rows) { TrackRowSkeleton() }
    }
}

/** Loading [CoverTile]: cover square + 2 bands. Rows of cards show 3. */
@Composable
fun CoverTileSkeleton(modifier: Modifier = Modifier, size: Dp = 132.dp) {
    Column(modifier.width(size)) {
        SkeletonBox(Modifier.size(size), ShadeRadius.cover)
        Spacer(Modifier.height(8.dp))
        SkeletonLine(size * 0.8f)
        Spacer(Modifier.height(6.dp))
        SkeletonLine(size * 0.55f)
    }
}

@Composable
fun CoverStripSkeleton(modifier: Modifier = Modifier, size: Dp = 132.dp, count: Int = 3) {
    val loading = stringResource(R.string.state_loading)
    Row(
        modifier.padding(horizontal = 20.dp).clearAndSetSemantics { contentDescription = loading },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(count) { CoverTileSkeleton(size = size) }
    }
}

/**
 * Empty state (COMPONENTS: Boş durum): centered 64 icon text3 in a 96 surface2 box (corner 20) · 16 · headlineSmall
 * · 8 · bodyMedium text2 max 280 wide · 24 · optional secondary button.
 */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    @DrawableRes actionIcon: Int? = null,
) {
    val x = ShadeTheme.extras
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)).background(x.surface2),
            contentAlignment = Alignment.Center,
        ) { ShadeIcon(icon, null, tint = x.text3, size = 64.dp) }
        Spacer(Modifier.height(16.dp))
        Text(
            title, style = MaterialTheme.typography.headlineSmall, color = x.text1, textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                body, Modifier.widthIn(max = 280.dp), style = MaterialTheme.typography.bodyMedium, color = x.text2,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            SecondaryButton(actionLabel, onAction, icon = actionIcon)
        }
    }
}

/** Generic error state with a retry button (strings state_error_*). */
@Composable
fun ErrorState(onRetry: () -> Unit, modifier: Modifier = Modifier, title: String? = null, body: String? = null) {
    EmptyState(
        icon = R.drawable.ic_error,
        title = title ?: stringResource(R.string.state_error_title),
        body = body ?: stringResource(R.string.state_error_body),
        modifier = modifier,
        actionLabel = stringResource(R.string.action_retry),
        onAction = onRetry,
        actionIcon = R.drawable.ic_refresh,
    )
}

/** Offline banner (SCREENS 24): 36 high surface3 strip, offline icon + state_offline 12. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier, text: String = stringResource(R.string.state_offline)) {
    val x = ShadeTheme.extras
    Row(
        modifier.fillMaxWidth().heightIn(min = 36.dp).background(x.surface3).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShadeIcon(R.drawable.ic_offline, null, tint = x.text2, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = x.text1, maxLines = 2)
    }
}
