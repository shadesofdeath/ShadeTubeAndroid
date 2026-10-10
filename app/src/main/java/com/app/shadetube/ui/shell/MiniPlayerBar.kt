package com.app.shadetube.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.AppGraph
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.playback.Status
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.MiniPlayer
import com.app.shadetube.ui.components.RailMiniPlayer
import com.app.shadetube.ui.components.urlFor

/**
 * The mini player fed by [AppGraph.player]: tap / swipe up -> Now Playing, horizontal swipe -> next / previous, live
 * stations show the "Canlı" badge and the station's current song. Position ticks only recompose this composable.
 */
@Composable
internal fun MiniPlayerBar(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
    rail: Boolean = false,
    compact: Boolean = false,
) {
    val player = AppGraph.player
    val track by player.current.collectAsStateWithLifecycle()
    val status by player.status.collectAsStateWithLifecycle()
    val position by player.position.collectAsStateWithLifecycle()
    val live by player.isLiveFlow.collectAsStateWithLifecycle()
    val liveTitle by player.liveTitle.collectAsStateWithLifecycle()
    val t = track ?: return
    val px = with(LocalDensity.current) { (if (rail) 56.dp else 48.dp).roundToPx() }
    val url = remember(t.album.images, px) { t.album.images.urlFor(px) }
    val isStation = live || InternetRadio.isStation(t.id)
    val kind = when {
        isStation -> ArtworkKind.Radio
        isPodcastId(t.id) -> ArtworkKind.Podcast
        else -> ArtworkKind.Track
    }
    val playing = status == Status.Playing || status == Status.Buffering || status == Status.Resolving
    val buffering = status == Status.Buffering || status == Status.Resolving
    val progress = if (position.durationMs > 0) (position.positionMs.toFloat() / position.durationMs).coerceIn(0f, 1f) else 0f
    if (rail) {
        RailMiniPlayer(
            title = t.name,
            artist = if (isStation && liveTitle.isNotEmpty()) liveTitle else t.artistLine,
            artworkUrl = url,
            isPlaying = playing,
            progress = progress,
            onClick = onOpen,
            onPlayPause = { player.togglePause() },
            modifier = modifier,
            isBuffering = buffering,
            isLive = isStation,
            artworkKind = kind,
            coverModifier = coverModifier,
            compact = compact,
        )
        return
    }
    MiniPlayer(
        title = t.name,
        artist = if (isStation && liveTitle.isNotEmpty()) liveTitle else t.artistLine,
        artworkUrl = url,
        isPlaying = playing,
        progress = progress,
        onClick = onOpen,
        onPlayPause = { player.togglePause() },
        modifier = modifier,
        isBuffering = buffering,
        isLive = isStation,
        artworkKind = kind,
        onSkipNext = if (isStation) null else ({ player.next() }),
        onSkipPrevious = if (isStation) null else ({ player.previous() }),
        onExpand = onOpen,
        coverModifier = coverModifier,
    )
}
