package com.app.shadetube.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.shadetube.R
import com.app.shadetube.catalog.Track
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Opacity of blocked / unplayable covers (COMPONENTS: devre dışı içerik @38 %). */
private const val DISABLED_ALPHA = 0.38f

/**
 * Song row (COMPONENTS: Liste satırı): 60 high (grows with font scale), cover 44 · 12 · title titleSmall 14/600
 * + subtitle bodySmall 12 text2 · trailing: downloaded 16 accent · duration mono 12 text3 · more 40 (48 hit).
 *
 * Variants:
 * - [isCurrent]: title in accent, bars over the cover on a 50 % scrim ([isPlaying] animates them);
 * - [suggested]: ÖNERİLEN badge after the title, + and hide (40 each) instead of duration / more;
 * - [dragging] (queue reorder): surface3, 20dp corners, elevation 6, scale 1.02;
 * - ![enabled] (blocked / unplayable): text3 texts, cover at 38 %, not clickable;
 * - swipe-to-delete: wrap in [SwipeToDeleteBox]; loading: [TrackRowSkeleton].
 * [leading] replaces the cover (album track numbers: `TrackNumber(n)`); [trailing] is appended at the end (queue drag
 * handle).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    duration: String? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    suggested: Boolean = false,
    downloaded: Boolean = false,
    explicit: Boolean = false,
    enabled: Boolean = true,
    dragging: Boolean = false,
    artworkKind: ArtworkKind = ArtworkKind.Track,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 8.dp),
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onAddSuggested: (() -> Unit)? = null,
    onHideSuggested: (() -> Unit)? = null,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val reduce = ShadeTheme.reduceMotion
    val scale by animateFloatAsState(
        if (dragging) 1.02f else 1f, ShadeMotion.orReduced(ShadeMotion.dragRelease()), label = "dragScale",
    )
    val elevation by animateDpAsState(if (dragging) 6.dp else 0.dp, tween(ShadeMotion.STANDARD), label = "dragElev")
    val rowShape = RoundedCornerShape(if (dragging) 20.dp else 0.dp)
    val moreCd = stringResource(R.string.common_more_cd)
    val addCd = stringResource(R.string.playlist_suggested_add_cd)
    val hideCd = stringResource(R.string.playlist_suggested_hide_cd)
    val actions = buildList {
        if (onMore != null && !suggested) add(CustomAccessibilityAction(moreCd) { onMore(); true })
        if (suggested && onAddSuggested != null) add(CustomAccessibilityAction(addCd) { onAddSuggested(); true })
        if (suggested && onHideSuggested != null) add(CustomAccessibilityAction(hideCd) { onHideSuggested(); true })
    }
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (elevation > 0.dp && !reduce) Modifier.shadow(elevation, rowShape) else Modifier)
            .clip(rowShape)
            .background(if (dragging) x.surface3 else Color.Transparent)
            .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)
            .semantics { if (actions.isNotEmpty()) customActions = actions }
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
        } else {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Artwork(artworkUrl, kind = artworkKind, size = 44.dp, alpha = if (enabled) 1f else DISABLED_ALPHA)
                if (isCurrent) {
                    Box(Modifier.matchParentSize().clip(RoundedCornerShape(6.dp)).background(x.scrim.copy(alpha = 0.5f)))
                    NowPlayingBars(isPlaying)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    color = when { !enabled -> x.text3; isCurrent -> accent.accent; else -> x.text1 },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (suggested) {
                    Spacer(Modifier.width(8.dp))
                    SuggestedBadge()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (explicit) {
                    ShadeIcon(
                        R.drawable.ic_explicit, stringResource(R.string.track_explicit_cd),
                        tint = x.text3, size = 14.dp,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) x.text2 else x.text3,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (suggested) {
            Spacer(Modifier.width(8.dp))
            if (onAddSuggested != null) {
                ShadeIconButton(R.drawable.ic_plus, addCd, onAddSuggested, tint = accent.accent)
            }
            if (onHideSuggested != null) {
                ShadeIconButton(R.drawable.ic_block, hideCd, onHideSuggested, tint = x.text2)
            }
        } else {
            if (downloaded) {
                Spacer(Modifier.width(8.dp))
                ShadeIcon(
                    R.drawable.ic_downloaded, stringResource(R.string.track_downloaded_cd),
                    tint = if (enabled) accent.accent else x.text3, size = 16.dp,
                )
            }
            if (duration != null) {
                Spacer(Modifier.width(6.dp))
                Text(duration, style = ShadeMono.medium.copy(fontSize = 12.sp), color = x.text3, maxLines = 1)
            }
            if (onMore != null) {
                Spacer(Modifier.width(2.dp))
                ShadeIconButton(R.drawable.ic_more_vertical, moreCd, onMore, tint = x.text2)
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
        trailing?.invoke(this)
    }
}

/** [TrackRow] for a catalog [Track] (cover from the album images, duration "m:ss", explicit / playable / recommended). */
@Composable
fun TrackRow(
    track: Track,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    downloaded: Boolean = false,
    blocked: Boolean = false,
    dragging: Boolean = false,
    showArtwork: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 8.dp),
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onAddSuggested: (() -> Unit)? = null,
    onHideSuggested: (() -> Unit)? = null,
) {
    val px = with(LocalDensity.current) { 44.dp.roundToPx() }
    val url = remember(track.album.images, px) { track.album.images.urlFor(px) }
    TrackRow(
        title = track.name,
        subtitle = track.artistLine,
        artworkUrl = url,
        modifier = modifier,
        duration = if (track.durationMs > 0) formatDuration(track.durationMs.toLong()) else null,
        isCurrent = isCurrent,
        isPlaying = isPlaying,
        suggested = track.recommended,
        downloaded = downloaded,
        explicit = track.explicitContent,
        enabled = track.playable && !blocked,
        dragging = dragging,
        contentPadding = contentPadding,
        leading = if (showArtwork) null else ({ TrackNumber(track.trackNumber, isCurrent, isPlaying) }),
        trailing = trailing,
        onClick = onClick,
        onLongClick = onLongClick,
        onMore = onMore,
        onAddSuggested = onAddSuggested,
        onHideSuggested = onHideSuggested,
    )
}

/** Album rows show a 2-digit mono number (28 wide) instead of the cover; bars when it is the current track. */
@Composable
fun TrackNumber(number: Int, isCurrent: Boolean = false, isPlaying: Boolean = false) {
    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
        if (isCurrent) NowPlayingBars(isPlaying)
        else Text(
            if (number > 0) String.format(java.util.Locale.ROOT, "%02d", number) else "–", style = ShadeMono.medium, color = ShadeTheme.extras.text3,
        )
    }
}

/**
 * Swipe-to-delete (COMPONENTS: kaydırılan): the row follows the finger right-to-left over an `error` background
 * with a trash icon; past 40 % of the width it slides out (250 ms) and calls [onDelete], otherwise springs back.
 * The caller removes the item (and shows the undo snackbar).
 */
@Composable
fun SwipeToDeleteBox(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val x = ShadeTheme.extras
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val release = ShadeMotion.orReduced(ShadeMotion.dragRelease<Float>())
    val removeCd = stringResource(R.string.track_remove_cd)
    BoxWithConstraints(
        modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(removeCd) { onDelete(); true })
        },
    ) {
        val width = constraints.maxWidth.toFloat()
        if (offset.value < 0f) {
            Box(
                Modifier.matchParentSize().background(x.error).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { ShadeIcon(R.drawable.ic_trash, null, tint = x.onError) }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(if (offset.value < 0f) x.surface0 else Color.Transparent)
                .draggable(
                    enabled = enabled,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(-width, 0f)) } },
                    onDragStopped = { velocity ->
                        if (-offset.value > width * 0.4f || velocity < -2000f) {
                            offset.animateTo(-width, tween(ShadeMotion.STANDARD, easing = ShadeMotion.standard))
                            onDelete()
                            offset.snapTo(0f)
                        } else {
                            offset.animateTo(0f, release)
                        }
                    },
                ),
        ) { content() }
    }
}
