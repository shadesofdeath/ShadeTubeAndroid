package com.app.shadetube.playback

import android.content.Context
import android.net.ConnectivityManager
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AudioQuality
import com.app.shadetube.core.Settings
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.podcasts.PlayerStatus
import com.app.shadetube.podcasts.Podcasts
import com.app.shadetube.youtube.Match
import com.app.shadetube.youtube.MatchFailure
import com.app.shadetube.youtube.MatchService
import com.app.shadetube.youtube.Resolved
import com.app.shadetube.youtube.ResolvedStream
import com.app.shadetube.youtube.matchFailureOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Adapters for the packages the player uses directly (youtube, podcasts, internetradio). Everything else reaches the
 * player through the interfaces of Ports.kt / features / service, wired by AppGraph.
 */

/** [TrackStreamResolver] over youtube.MatchService. */
class MatchServiceResolver(private val matcher: MatchService) : TrackStreamResolver {
    override suspend fun resolve(track: Track, allowWebm: Boolean, lowQuality: Boolean): Result<Resolved> =
        matcher.resolve(track, allowWebm, lowQuality)

    override suspend fun stream(videoId: String, allowWebm: Boolean, lowQuality: Boolean, bypassCache: Boolean): Result<ResolvedStream> =
        matcher.stream(videoId, allowWebm, lowQuality, bypassCache)

    override suspend fun cachedMatch(trackId: String): Match? = matcher.cachedMatch(trackId)
    override suspend fun pin(trackId: String, match: Match) = matcher.pin(trackId, match)
    override fun failureOf(t: Throwable): MatchFailure = matchFailureOf(t)
}

/** Podcast episodes: their own media URL (redirects followed), resume position, downloaded episodes from disk. */
object PodcastPlayback : DirectStreamProvider, StartPositionProvider, LocalSourceResolver {
    override fun handles(track: Track): Boolean = Podcasts.isEpisode(track.id)

    override suspend fun resolve(track: Track): DirectStream {
        val s = Podcasts.resolveStream(track.id)
        return DirectStream(s.url, s.mimeType, s.contentLength, s.allowLocalNetwork)
    }

    override fun startPositionFor(track: Track): Long = Podcasts.startPositionFor(track.id)

    override fun localSourceFor(trackId: String): LocalSource? = Podcasts.localFileFor(trackId)?.let { LocalSource.of(it) }

    /** Progress samples for the episode store ("played" marks, resume positions). */
    fun follow(player: Player, scope: CoroutineScope) {
        scope.launch {
            player.events.collect { e ->
                when (e) {
                    is PlayerEvent.Progress -> Podcasts.onPlayerSample(e.track.id, statusOf(e.status), e.positionMs, e.durationMs)
                    is PlayerEvent.TrackEnded -> if (e.reason == EndReason.QueueEnded || e.reason == EndReason.Stopped) {
                        Podcasts.onPlayerSample(e.track.id, PlayerStatus.Idle, e.positionMs, e.durationMs)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun statusOf(s: Status) = when (s) {
        Status.Idle -> PlayerStatus.Idle
        Status.Resolving, Status.Buffering -> PlayerStatus.Loading
        Status.Playing -> PlayerStatus.Playing
        Status.Paused -> PlayerStatus.Paused
        Status.Error -> PlayerStatus.Error
    }
}

/** Internet radio stations: resolved per attempt, titles from ICY blocks, the registry follows the player. */
object RadioPlayback : LiveStreamProvider {
    override fun isLive(track: Track): Boolean = InternetRadio.isStation(track.id)

    override suspend fun resolve(track: Track, attempt: Int): LiveStream {
        val s = InternetRadio.resolveStream(track.id, attempt)
        return LiveStream(s.url, s.isHls, s.mimeType)
    }

    override fun titleFromIcy(raw: ByteArray): String? = InternetRadio.titleFromIcy(raw)

    /** Recently played + click counting + heard titles (InternetRadio.onPlayerChanged on every change). */
    fun follow(player: Player, scope: CoroutineScope) {
        scope.launch {
            player.changed.collect {
                InternetRadio.onPlayerChanged(player.currentTrack?.id, player.isPlaying, player.liveTitle.value)
            }
        }
    }
}

/**
 * Settings.meteredQuality while the active network is metered (mobile data, a metered Wi-Fi), else Settings.quality.
 */
class NetworkQuality(context: Context, private val settings: () -> Settings = { SettingsStore.current }) : QualityProvider {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun quality(): AudioQuality {
        val s = settings()
        val metered = runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
        return if (metered) s.meteredQuality else s.quality
    }
}
