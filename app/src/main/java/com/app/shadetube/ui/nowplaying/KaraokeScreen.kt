package com.app.shadetube.ui.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.Track
import com.app.shadetube.lyrics.LyricLine
import com.app.shadetube.lyrics.Lyrics
import com.app.shadetube.lyrics.LyricsTimeline
import com.app.shadetube.lyrics.activeLine
import com.app.shadetube.playback.Status
import com.app.shadetube.ui.components.PlayPauseButton
import com.app.shadetube.ui.components.SeekBar
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.delay
import kotlin.math.floor

/**
 * Full-screen karaoke (SCREENS #12, README › Karaoke dolum efekti): blurred cover backdrop; the active line is drawn
 * twice — text3 underneath, accent on top clipped to the sung part (word timing when the lyrics have it, else the
 * line's elapsed fraction; wrapped lines fill in reading order). Controls hide after 3 s without a touch (250 ms fade).
 */
@Composable
internal fun KaraokeScreen(vm: LyricsViewModel, track: Track, artworkUrl: String?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val state by vm.state.collectAsStateWithLifecycle()
    val offset by vm.offsetMs.collectAsStateWithLifecycle()
    val x = ShadeTheme.extras
    var controls by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(touches) {
        controls = true
        delay(3000)
        controls = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { touches++ } },
    ) {
        NowPlayingBackdrop(artworkUrl, strong = true)
        val s = state
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
            when {
                s is LyricsUi.Loaded && s.lyrics.synced -> KaraokeLines(s.lyrics, offset)
                s is LyricsUi.Loaded -> Text(
                    s.lyrics.lines.joinToString("\n") { it.text },
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 96.dp),
                    style = MaterialTheme.typography.headlineSmall, color = x.text1, textAlign = TextAlign.Center,
                )
                else -> LyricsStateView(s, vm::reload)
            }
        }
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn(tween(ShadeMotion.STANDARD)),
            exit = fadeOut(tween(ShadeMotion.STANDARD)),
        ) {
            KaraokeControls(track, onClose)
        }
    }
}

@Composable
private fun KaraokeLines(lyrics: Lyrics, offset: Int) {
    val x = ShadeTheme.extras
    val extra by AppGraph.sponsorBlock.extra.collectAsStateWithLifecycle()
    val smooth = rememberSmoothPositionMs()
    val clock by remember(offset, extra) { derivedStateOf { LyricsTimeline.clockMs(smooth.value, extra, offset) } }
    val active by remember(lyrics) { derivedStateOf { activeLine(lyrics, clock) } }
    val lines = lyrics.lines
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val prev = lines.getOrNull(active - 1)
        Text(
            prev?.text.orEmpty(), style = MaterialTheme.typography.titleLarge, color = x.text3, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        AnimatedContent(
            targetState = active,
            transitionSpec = { fadeIn(tween(ShadeMotion.STANDARD)) togetherWith fadeOut(tween(ShadeMotion.SHORT)) },
            label = "karaokeLine",
        ) { i ->
            val line = lines.getOrNull(i)
            if (line == null) {
                Text("♪", style = KARAOKE_STYLE, color = x.text3, textAlign = TextAlign.Center)
            } else {
                KaraokeLine(line, endMs = lineEndMs(lines, i), clock = { clock })
            }
        }
        for (k in 1..2) {
            Text(
                lines.getOrNull(active + k)?.text.orEmpty(), style = MaterialTheme.typography.titleLarge, color = x.text3,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val KARAOKE_STYLE = TextStyle(fontSize = 32.sp, lineHeight = 40.sp)

/** The sung part of [line] at [clock] in characters (fractional). */
private fun sungChars(line: LyricLine, endMs: Int, clock: Int): Float {
    val text = line.text
    if (text.isEmpty()) return 0f
    if (line.words.isNotEmpty()) {
        var before = 0
        for ((w, word) in line.words.withIndex()) {
            val wEnd = line.words.getOrNull(w + 1)?.timeMs ?: endMs
            if (clock < word.timeMs) return before.toFloat()
            if (clock < wEnd) {
                val f = ((clock - word.timeMs).toFloat() / (wEnd - word.timeMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                return before + word.text.trimEnd().length * f
            }
            before += word.text.length
        }
        return text.length.toFloat()
    }
    val f = ((clock - line.timeMs).toFloat() / (endMs - line.timeMs).coerceAtLeast(1)).coerceIn(0f, 1f)
    return text.length * f
}

@Composable
private fun KaraokeLine(line: LyricLine, endMs: Int, clock: () -> Int) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    val style = MaterialTheme.typography.displaySmall.merge(KARAOKE_STYLE)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val text = line.text.ifBlank { "♪" }
    Box {
        Text(text, style = style, color = x.text3, textAlign = TextAlign.Center, onTextLayout = { layout = it })
        Text(
            text, style = style, color = accent.accent, textAlign = TextAlign.Center,
            modifier = Modifier.drawWithContent {
                val l = layout ?: return@drawWithContent
                val chars = sungChars(line, endMs, clock()).coerceIn(0f, text.length.toFloat())
                if (chars >= text.length) {
                    drawContent()
                    return@drawWithContent
                }
                if (chars <= 0f) return@drawWithContent
                val idx = floor(chars).toInt().coerceIn(0, text.length - 1)
                val ln = l.getLineForOffset(idx)
                val x0 = l.getHorizontalPosition(idx, true)
                val x1 = if (idx + 1 < l.getLineEnd(ln)) l.getHorizontalPosition(idx + 1, true) else l.getLineRight(ln)
                val cut = x0 + (x1 - x0) * (chars - idx)
                val path = Path().apply {
                    if (ln > 0) addRect(Rect(0f, 0f, size.width, l.getLineTop(ln)))
                    addRect(Rect(0f, l.getLineTop(ln), cut, l.getLineBottom(ln)))
                }
                clipPath(path) { this@drawWithContent.drawContent() }
            },
        )
    }
}

@Composable
private fun KaraokeControls(track: Track, onClose: () -> Unit) {
    val player = AppGraph.player
    val x = ShadeTheme.extras
    val status by player.status.collectAsStateWithLifecycle()
    val pos by player.position.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ShadeIconButton(R.drawable.ic_close, stringResource(R.string.np_karaoke_close_cd), onClose, tint = x.text1, size = 48.dp)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(track.name, style = MaterialTheme.typography.titleSmall, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artistLine, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.weight(1f))
        SeekBar(pos.positionMs, pos.durationMs, onSeek = player::seek, modifier = Modifier.padding(horizontal = 32.dp),
            bufferedMs = (pos.bufferedFraction * pos.durationMs).toLong())
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShadeIconButton(R.drawable.ic_prev, stringResource(R.string.player_prev_cd), player::previous, tint = x.text1, size = 56.dp, iconSize = 30.dp)
            PlayPauseButton(status == Status.Playing || status == Status.Buffering, player::togglePause, size = 64, iconSize = 30, filled = true)
            ShadeIconButton(R.drawable.ic_next, stringResource(R.string.player_next_cd), player::next, tint = x.text1, size = 56.dp, iconSize = 30.dp)
        }
        Spacer(Modifier.height(16.dp))
    }
}
