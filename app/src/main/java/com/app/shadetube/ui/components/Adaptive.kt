package com.app.shadetube.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window size classes (SCREENS #26 / README "Tablet"), the Material breakpoints: Compact < 600dp ≤ Medium < 840dp ≤
 * Expanded. Computed by the shell from the window it is laid out in (multi-window aware) and provided through
 * [LocalWindowLayout], so screens never read `LocalConfiguration` themselves. Not the material3-adaptive library: the
 * three thresholds are all we need and it would be one more dependency.
 */
enum class WidthClass { Compact, Medium, Expanded }

@Immutable
data class WindowLayout(
    val width: Dp = 412.dp,
    val height: Dp = 915.dp,
) {
    val widthClass: WidthClass
        get() = when {
            width >= 840.dp -> WidthClass.Expanded
            width >= 600.dp -> WidthClass.Medium
            else -> WidthClass.Compact
        }

    /** Phones in landscape, split screen halves: too short for the tall layouts (two-column Now Playing, rail avatar). */
    val compactHeight: Boolean get() = height < 480.dp

    /** NavigationRail instead of the bottom bar (expanded width). */
    val useRail: Boolean get() = widthClass == WidthClass.Expanded

    /** Two-column Now Playing (expanded width with room for the 480 cover + controls). */
    val twoPaneNowPlaying: Boolean get() = widthClass == WidthClass.Expanded && height >= 560.dp

    val isCompact: Boolean get() = widthClass == WidthClass.Compact

    companion object {
        fun of(width: Dp, height: Dp) = WindowLayout(width, height)
    }
}

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout() }

/** Content width limits of the large layouts. */
object AdaptiveWidths {
    /** Home: shelves stay horizontal inside it (SCREENS #26 "içerik max 1200"). */
    val Home = 1200.dp

    /** Tab roots made of rows and grids (Search, Broadcast). */
    val Browse = 960.dp

    /** Detail pages (playlist / album / artist, settings, profile, stats...): one readable centered column. */
    val Detail = 840.dp
}

/** Columns of a cover grid that fit [width] with [minTile] tiles (at least [min]). */
fun gridColumns(width: Dp, minTile: Dp = 164.dp, min: Int = 2, max: Int = 6, gutter: Dp = 12.dp, sidePadding: Dp = 40.dp): Int {
    val usable = width - sidePadding + gutter
    val n = (usable / (minTile + gutter)).toInt()
    return n.coerceIn(min, max)
}

/**
 * Centers [content] in a column at most [maxWidth] wide (full height). On compact windows it is a plain full-size box.
 * The page's own background paints inside the column; the shell's surface0 shows on the sides.
 */
@Composable
fun CenteredContent(maxWidth: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxWidth().fillMaxHeight(), content = content)
    }
}
