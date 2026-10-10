package com.app.shadetube.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.lyrics.LyricLine
import com.app.shadetube.lyrics.Lyrics
import com.app.shadetube.lyrics.LyricsTimeline
import com.app.shadetube.lyrics.SOURCE_FILE
import com.app.shadetube.lyrics.SOURCE_LRCLIB
import com.app.shadetube.lyrics.SOURCE_SPOTIFY
import com.app.shadetube.lyrics.SOURCE_TAG
import com.app.shadetube.lyrics.activeLine
import com.app.shadetube.playback.Status
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.SkeletonLine
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The player position interpolated every frame between the player's 4 Hz ticks (karaoke fill, active line), capped to
 * one second past the last tick.
 */
@Composable
internal fun rememberSmoothPositionMs(): State<Long> {
    val player = AppGraph.player
    val pos by player.position.collectAsStateWithLifecycle()
    val status by player.status.collectAsStateWithLifecycle()
    val speed by player.speed.collectAsStateWithLifecycle()
    val out = remember { mutableLongStateOf(pos.positionMs) }
    LaunchedEffect(pos, status, speed) {
        val base = pos.positionMs
        out.longValue = base
        if (status != Status.Playing) return@LaunchedEffect
        var t0 = -1L
        while (isActive) {
            withFrameNanos { now ->
                if (t0 < 0) t0 = now
                val dt = ((now - t0) / 1_000_000.0 * speed).toLong().coerceAtMost(1000)
                out.longValue = base + dt
            }
        }
    }
    return out
}

/** Human name of a lyrics source. */
@Composable
internal fun lyricsSourceName(source: String): String = when (source) {
    SOURCE_SPOTIFY -> stringResource(R.string.np_lyrics_src_spotify)
    SOURCE_FILE -> stringResource(R.string.np_lyrics_src_file)
    SOURCE_TAG -> stringResource(R.string.np_lyrics_src_tag)
    SOURCE_LRCLIB, "" -> stringResource(R.string.np_lyrics_src_lrclib)
    else -> source
}

/** Lyric states other than loaded lyrics (loading skeleton, not found, instrumental, off, station). */
@Composable
internal fun LyricsStateView(state: LyricsUi, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        LyricsUi.Loading -> Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(220, 180, 250, 140, 200, 160).forEach { SkeletonLine(it.dp, height = 18.dp) }
        }
        LyricsUi.Disabled -> EmptyState(R.drawable.ic_lyrics, stringResource(R.string.np_lyrics_disabled_title), stringResource(R.string.np_lyrics_disabled_body), modifier)
        LyricsUi.Station -> EmptyState(R.drawable.ic_radio, stringResource(R.string.np_lyrics_station_title), null, modifier)
        LyricsUi.NotFound -> EmptyState(
            R.drawable.ic_lyrics, stringResource(R.string.lyrics_not_found_title), stringResource(R.string.lyrics_not_found_body), modifier,
            actionLabel = stringResource(R.string.action_retry), onAction = onRetry,
        )
        LyricsUi.Instrumental -> EmptyState(R.drawable.ic_music_note, stringResource(R.string.lyrics_instrumental), stringResource(R.string.np_lyrics_instrumental_body), modifier)
        is LyricsUi.Loaded -> {}
    }
}

/**
 * Şarkı sözleri (SCREENS #12): lines bodyLarge 18/500 text3, the active line headlineSmall 22/700 text1 scaled 1→1.08
 * (250 ms) and kept centered; a tap seeks there (through the SponsorBlock-cut song timeline and the offset) with a
 * 150 ms accent flash. Footer: source (mono 11), timing stepper ±0.25 s, karaoke button. Unsynced lyrics are plain text.
 */
@Composable
internal fun LyricsPanel(vm: LyricsViewModel, nestedScroll: NestedScrollConnection, bottomPadding: Dp, onKaraoke: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state
    if (s !is LyricsUi.Loaded) {
        Box(Modifier.fillMaxSize().nestedScroll(nestedScroll).verticalScroll(rememberScrollState()).padding(bottom = bottomPadding)) {
            LyricsStateView(s, vm::reload)
        }
        return
    }
    val offset by vm.offsetMs.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        LyricsLines(s.lyrics, offset, nestedScroll, Modifier.weight(1f))
        LyricsFooter(s.lyrics, offset, onShift = vm::shift, onReset = vm::resetOffset, onKaraoke = onKaraoke, Modifier.padding(bottom = bottomPadding))
    }
}

@Composable
private fun LyricsLines(lyrics: Lyrics, offset: Int, nestedScroll: NestedScrollConnection, modifier: Modifier) {
    val player = AppGraph.player
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    val extra by AppGraph.sponsorBlock.extra.collectAsStateWithLifecycle()
    val smooth = rememberSmoothPositionMs()
    val active by remember(lyrics, offset, extra) {
        derivedStateOf { activeLine(lyrics, LyricsTimeline.clockMs(smooth.value, extra, offset)) }
    }
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var pausedUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) { if (dragged) pausedUntil = Long.MAX_VALUE else if (pausedUntil == Long.MAX_VALUE) pausedUntil = System.currentTimeMillis() + 3000 }
    val reduce = ShadeTheme.reduceMotion
    LaunchedEffect(active, pausedUntil) {
        if (!lyrics.synced || active < 0) return@LaunchedEffect
        val wait = pausedUntil - System.currentTimeMillis()
        if (wait > 0) {
            if (pausedUntil == Long.MAX_VALUE) return@LaunchedEffect
            delay(wait)
        }
        val viewport = list.layoutInfo.viewportSize.height
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }?.size ?: 120
        val off = -(viewport / 2 - item / 2)
        if (reduce) list.scrollToItem(active, off) else list.animateScrollToItem(active, off)
    }
    val scope = rememberCoroutineScope()
    var flash by remember { mutableIntStateOf(-1) }
    val flashAnim = remember { Animatable(0f) }
    LazyColumn(
        modifier.fillMaxWidth().nestedScroll(nestedScroll),
        state = list,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(lyrics.lines, key = { i, _ -> i }) { i, line ->
            val isActive = lyrics.synced && i == active
            val scale by animateFloatAsState(if (isActive) 1.08f else 1f, tween(if (reduce) 0 else ShadeMotion.STANDARD), label = "lyricScale")
            val base = when {
                !lyrics.synced -> x.text2
                isActive -> x.text1
                else -> x.text3
            }
            val color = if (i == flash) lerp(base, accent.accent, flashAnim.value) else base
            val text = line.text.ifBlank { "♪" }
            Text(
                text,
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }
                    .then(
                        if (lyrics.synced && line.timeMs >= 0) Modifier.clickable {
                            player.seek(LyricsTimeline.seekTarget(line.timeMs, extra, offset))
                            flash = i
                            scope.launch {
                                flashAnim.snapTo(1f)
                                flashAnim.animateTo(0f, tween(ShadeMotion.SHORT))
                                flash = -1
                            }
                        } else Modifier,
                    )
                    .padding(vertical = 4.dp),
                style = if (isActive) MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp)
                else MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
                color = color,
            )
        }
    }
}

@Composable
private fun LyricsFooter(
    lyrics: Lyrics,
    offset: Int,
    onShift: (Int) -> Unit,
    onReset: () -> Unit,
    onKaraoke: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val x = ShadeTheme.extras
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.lyrics_source, lyricsSourceName(lyrics.source)),
            Modifier.weight(1f), style = ShadeMono.small, color = x.text3, maxLines = 1,
        )
        if (lyrics.synced) {
            ShadeIconButton(R.drawable.ic_minus, stringResource(R.string.np_lyrics_earlier_cd), { onShift(-1) }, tint = x.text2)
            Text(
                stringResource(R.string.lyrics_offset_value, offset / 1000f),
                Modifier.width(72.dp).clickable(onClickLabel = stringResource(R.string.np_lyrics_reset_cd)) { onReset() },
                style = ShadeMono.medium, color = if (offset != 0) ShadeTheme.accent.accent else x.text2, textAlign = TextAlign.Center,
            )
            ShadeIconButton(R.drawable.ic_plus, stringResource(R.string.np_lyrics_later_cd), { onShift(1) }, tint = x.text2)
            Spacer(Modifier.width(4.dp))
            ShadeIconButton(R.drawable.ic_fullscreen, stringResource(R.string.lyrics_fullscreen_cd), onKaraoke, tint = x.text1)
        }
    }
}

/** Index of the line after [i] with a time, for line end times. */
internal fun lineEndMs(lines: List<LyricLine>, i: Int): Int {
    val start = lines[i].timeMs
    for (j in i + 1 until lines.size) if (lines[j].timeMs > start) return lines[j].timeMs
    return start + 5000
}
