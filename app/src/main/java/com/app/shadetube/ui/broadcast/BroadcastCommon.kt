package com.app.shadetube.ui.broadcast

import com.app.shadetube.AppGraph
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.internetradio.RadioModel
import com.app.shadetube.internetradio.Station
import com.app.shadetube.playback.PlayContext
import com.app.shadetube.playback.Status
import com.app.shadetube.podcasts.Episode
import com.app.shadetube.podcasts.Podcasts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What the player is doing, as the broadcast lists need it (highlight + live title of the playing item). */
data class NowPlayingInfo(
    val trackId: String? = null,
    val playing: Boolean = false,
    /** Resolving / buffering: "Bağlanıyor…" for a station. */
    val connecting: Boolean = false,
    /** What the playing station says is on air ("Artist — Title"), "" = nothing announced. */
    val liveTitle: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** A section that loads from the network. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

/** Playback helpers shared by the radio and podcast screens (main thread: the player is confined to it). */
internal object BroadcastPlayback {
    fun nowPlaying(): Flow<NowPlayingInfo> {
        val p = AppGraph.player
        return combine(p.current, p.status, InternetRadio.nowOnAir, p.position) { t, s, air, pos ->
            val live = air?.let { if (it.artist.isEmpty()) it.title else "${it.artist} — ${it.title}" }.orEmpty()
            NowPlayingInfo(
                trackId = t?.id,
                playing = s == Status.Playing || s == Status.Buffering,
                connecting = s == Status.Resolving || s == Status.Buffering,
                liveTitle = live.ifEmpty { p.liveTitle.value },
                positionMs = if (t != null && Podcasts.isEpisode(t.id)) pos.positionMs / 5_000 * 5_000 else 0,
                durationMs = if (t != null && Podcasts.isEpisode(t.id)) pos.durationMs else 0,
            )
        }.distinctUntilChanged()
    }

    /** Plays [list] as the queue from [index]; the station already playing toggles pause instead. */
    fun playStations(list: List<Station>, index: Int, contextUri: String, contextName: String) {
        val target = list.getOrNull(index) ?: return
        val p = AppGraph.player
        if (p.currentTrack?.id == RadioModel.stationId(target.uuid)) {
            p.togglePause()
            return
        }
        val tracks = InternetRadio.prepareQueue(list, index)
        p.playContext(tracks, index, PlayContext(contextUri, contextName))
    }

    /**
     * Plays the episodes of [list] from [index] (resume position applied by the player); the episode already playing
     * toggles pause when [toggleIfCurrent].
     */
    fun playEpisodes(list: List<Episode>, index: Int, contextName: String, toggleIfCurrent: Boolean = true) {
        val target = list.getOrNull(index) ?: return
        val p = AppGraph.player
        if (toggleIfCurrent && p.currentTrack?.id == target.id) {
            p.togglePause()
            return
        }
        val tracks = Podcasts.prepareQueue(list, index)
        p.playContext(tracks, index, PlayContext(Podcasts.contextUri(target), contextName))
    }
}

/** Episode dates: "12 Eki 2026" in the display language (mono 11 on rows). */
internal fun formatEpisodeDate(unixSeconds: Long, locale: Locale = Locale.getDefault()): String {
    if (unixSeconds <= 0) return ""
    return runCatching {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault()))
    }.getOrDefault("")
}

/** Minutes left of an episode (rounded up, at least 1). */
internal fun minutesLeft(positionMs: Long, durationMs: Long): Int =
    (((durationMs - positionMs).coerceAtLeast(0) + 59_999) / 60_000).toInt().coerceAtLeast(1)
