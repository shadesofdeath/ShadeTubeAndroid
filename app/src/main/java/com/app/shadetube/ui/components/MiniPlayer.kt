package com.app.shadetube.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.os.Build
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.shadetube.R
import com.app.shadetube.catalog.Track
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fraction of the player width a horizontal swipe must travel to skip (COMPONENTS: eşik 40 %). */
const val MINI_PLAYER_SWIPE_THRESHOLD = 0.4f

/**
 * Mini player (COMPONENTS / REDLINES): 64 high, corner 14, surface3 @90 % (light: surface1 @92 %), 1dp accent @25 %
 * border, accent glow shadow (none with reduce motion). Inside: cover 48 · 12 · title 14/600 + artist 12 text2 ·
 * playing bars 16 · play/pause 48 (icon 26); 2dp progress along the bottom edge, 8dp in from the sides.
 *
 * Place it 12dp from the screen edges and 12dp above the nav bar. States: playing / paused (bars sink) / buffering
 * (indeterminate progress) / live ([isLive]: Live badge instead of bars, no progress).
 *
 * Gestures: tap → [onClick] (open Now Playing); swipe left → [onSkipNext], right → [onSkipPrevious] (cover + text
 * follow the finger; past 40 % of the width they slide out in 250 ms, otherwise spring back); swipe up → [onExpand].
 * [coverModifier] is applied to the 48dp cover (e.g. `sharedElement(key = "cover")`).
 */
@Composable
fun MiniPlayer(
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    progress: Float,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    isLive: Boolean = false,
    artworkKind: ArtworkKind = ArtworkKind.Track,
    onSkipNext: (() -> Unit)? = null,
    onSkipPrevious: (() -> Unit)? = null,
    onExpand: (() -> Unit)? = null,
    coverModifier: Modifier = Modifier,
    backdrop: Boolean = true,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val reduce = ShadeTheme.reduceMotion
    val shape = ShadeRadius.miniPlayer
    val blurred = backdrop && miniPlayerBackdropSupported && !artworkUrl.isNullOrEmpty()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val verticalDrag = remember { mutableFloatStateOf(0f) }
    val expandThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    val release = ShadeMotion.orReduced(ShadeMotion.dragRelease<Float>())
    val openCd = stringResource(R.string.player_open_cd)
    val nextCd = stringResource(R.string.player_next_cd)
    val prevCd = stringResource(R.string.player_prev_cd)
    val glowColor = accent.accent.copy(alpha = 0.45f)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .then(
                if (reduce) Modifier
                else Modifier.shadow(16.dp, shape, clip = false, ambientColor = glowColor, spotColor = glowColor),
            )
            .clip(shape)
            .then(if (blurred) Modifier else Modifier.background(x.miniPlayerSurface))
            .border(1.dp, accent.accent.copy(alpha = 0.25f), shape)
            .semantics {
                customActions = buildList {
                    onSkipNext?.let { add(CustomAccessibilityAction(nextCd) { it(); true }) }
                    onSkipPrevious?.let { add(CustomAccessibilityAction(prevCd) { it(); true }) }
                }
            }
            .clickable(onClickLabel = openCd, role = Role.Button, onClick = onClick)
            .draggable(
                orientation = Orientation.Vertical,
                enabled = onExpand != null,
                state = rememberDraggableState { d -> verticalDrag.floatValue += d },
                onDragStarted = { verticalDrag.floatValue = 0f },
                onDragStopped = { v ->
                    if (verticalDrag.floatValue < -expandThreshold || v < -1500f) onExpand?.invoke()
                    verticalDrag.floatValue = 0f
                },
            ),
    ) {
        if (blurred) MiniPlayerBackdrop(artworkUrl)
        val width = constraints.maxWidth.toFloat()
        val canSwipe = onSkipNext != null || onSkipPrevious != null
        Row(
            Modifier.fillMaxSize().padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        enabled = canSwipe,
                        state = rememberDraggableState { d ->
                            scope.launch {
                                val min = if (onSkipNext != null) -width else 0f
                                val max = if (onSkipPrevious != null) width else 0f
                                offset.snapTo((offset.value + d).coerceIn(min, max))
                            }
                        },
                        onDragStopped = { v ->
                            val o = offset.value
                            val next = o < 0 && (-o > width * MINI_PLAYER_SWIPE_THRESHOLD || v < -2500f)
                            val prev = o > 0 && (o > width * MINI_PLAYER_SWIPE_THRESHOLD || v > 2500f)
                            if (next || prev) {
                                val dir = if (next) -1f else 1f
                                if (reduce) {
                                    offset.snapTo(0f)
                                    if (next) onSkipNext?.invoke() else onSkipPrevious?.invoke()
                                } else {
                                    offset.animateTo(dir * width, tween(ShadeMotion.STANDARD, easing = ShadeMotion.standard))
                                    if (next) onSkipNext?.invoke() else onSkipPrevious?.invoke()
                                    offset.snapTo(-dir * width * 0.3f)
                                    offset.animateTo(0f, tween(ShadeMotion.STANDARD, easing = ShadeMotion.standard))
                                }
                            } else {
                                offset.animateTo(0f, release)
                            }
                        },
                    )
                    .offset { IntOffset(offset.value.roundToInt(), 0) }
                    .graphicsLayer { alpha = 1f - (abs(offset.value) / width).coerceIn(0f, 1f) * 0.8f },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(artworkUrl, coverModifier, kind = artworkKind, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title, style = MaterialTheme.typography.titleSmall.copy(lineHeight = 18.sp), color = x.text1,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artist, style = MaterialTheme.typography.bodySmall, color = x.text2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isLive) {
                Spacer(Modifier.width(8.dp))
                LiveBadge()
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(8.dp))
                NowPlayingBars(isPlaying && !isBuffering, Modifier.padding(end = 4.dp))
            }
            PlayPauseButton(isPlaying, onPlayPause, size = 48, iconSize = 26)
        }
        if (!isLive || isBuffering) {
            LinearProgress(
                progress = if (isBuffering) null else progress,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp),
                height = 2.dp,
            )
        }
    }
}

/** Backdrop blur needs RenderEffect (API 31+); below it the mini player keeps its solid 96 % surface. */
val miniPlayerBackdropSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The design's "surface3 @90 % + blur 20" (COMPONENTS: Mini oynatıcı). Compose cannot blur what is behind a view, so
 * the backdrop is the playing cover itself: scaled up, blurred 20dp, under the 90 % surface — an opaque, cover-tinted
 * frosted panel (the list scrolling underneath no longer shows through, which was why the solid fallback was 96 %).
 */
@Composable
private fun MiniPlayerBackdrop(url: String?) {
    val x = ShadeTheme.extras
    Box(Modifier.fillMaxSize().background(if (x.isDark) x.surface3 else x.surface1)) {
        Artwork(
            url,
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 1.6f; scaleY = 1.6f }
                .blur(20.dp, BlurredEdgeTreatment.Rectangle),
            kind = ArtworkKind.Track,
            shape = RectangleShape,
        )
        Box(Modifier.fillMaxSize().background((if (x.isDark) x.surface3 else x.surface1).copy(alpha = 0.90f)))
    }
}

/**
 * Vertical mini player of the navigation rail (SCREENS #26): cover 56 (tap = Now Playing, swipe up too) with a 2dp
 * progress line under it and play / pause 48, on the same frosted surface as [MiniPlayer]. 64 wide.
 */
@Composable
fun RailMiniPlayer(
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    progress: Float,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    isLive: Boolean = false,
    artworkKind: ArtworkKind = ArtworkKind.Track,
    coverModifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val reduce = ShadeTheme.reduceMotion
    val shape = ShadeRadius.miniPlayer
    val blurred = miniPlayerBackdropSupported && !artworkUrl.isNullOrEmpty()
    val glowColor = accent.accent.copy(alpha = 0.45f)
    val openCd = stringResource(R.string.player_open_cd)
    val coverSize = if (compact) 44.dp else 56.dp
    Box(
        modifier
            .width(coverSize + 8.dp)
            .then(if (reduce) Modifier else Modifier.shadow(12.dp, shape, clip = false, ambientColor = glowColor, spotColor = glowColor))
            .clip(shape)
            .then(if (blurred) Modifier else Modifier.background(x.miniPlayerSurface))
            .border(1.dp, accent.accent.copy(alpha = 0.25f), shape)
            .semantics(mergeDescendants = false) { contentDescription = "$title, $artist" },
    ) {
        if (blurred) Box(Modifier.matchParentSize()) { MiniPlayerBackdrop(artworkUrl) }
        Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(
                artworkUrl,
                coverModifier
                    .size(coverSize)
                    .clip(ShadeRadius.cover)
                    .clickable(onClickLabel = openCd, role = Role.Button, onClick = onClick),
                kind = artworkKind,
                size = coverSize,
            )
            Spacer(Modifier.height(4.dp))
            if (isLive && !isBuffering) {
                LiveBadge(Modifier.padding(vertical = 1.dp))
            } else {
                LinearProgress(if (isBuffering) null else progress, Modifier.width(coverSize - 8.dp), height = 2.dp)
            }
            PlayPauseButton(isPlaying, onPlayPause, size = if (compact) 40 else 48, iconSize = 24)
        }
    }
}

/** [MiniPlayer] for a catalog [Track]. */
@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    progress: Float,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    isLive: Boolean = false,
    onSkipNext: (() -> Unit)? = null,
    onSkipPrevious: (() -> Unit)? = null,
    onExpand: (() -> Unit)? = null,
    coverModifier: Modifier = Modifier,
) {
    val px = with(LocalDensity.current) { 48.dp.roundToPx() }
    val url = remember(track.album.images, px) { track.album.images.urlFor(px) }
    MiniPlayer(
        track.name, track.artistLine, url, isPlaying, progress, onClick, onPlayPause, modifier, isBuffering, isLive,
        ArtworkKind.Track, onSkipNext, onSkipPrevious, onExpand, coverModifier,
    )
}

/**
 * Play / pause icon button (icon crossfades between ic_play and ic_pause). [filled] = the Now Playing 76dp accent
 * pill (icon onAccent); otherwise a plain text1 icon.
 */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 48,
    iconSize: Int = 26,
    filled: Boolean = false,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val cd = stringResource(if (isPlaying) R.string.player_pause_cd else R.string.player_play_cd)
    val interaction = rememberInteractionSource()
    Box(
        modifier
            .then(if (filled) Modifier.pressScale(interaction, 0.95f) else Modifier)
            .size(size.dp)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(accent.accent) else Modifier)
            .clickable(
                interactionSource = interaction, indication = ripple(), role = Role.Button,
                onClickLabel = null, onClick = onClick,
            )
            .semantics { this.contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(isPlaying, animationSpec = tween(ShadeMotion.SHORT), label = "playPause") { playing ->
            ShadeIcon(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play, null,
                tint = if (filled) accent.onAccent else x.text1, size = iconSize.dp,
            )
        }
    }
}
