package com.app.shadetube.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.AppGraph
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isLocalId
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.library.LikeResult
import com.app.shadetube.playback.features.SmartShuffle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What kind of item plays (decides the seek bar, source badge, tools). */
enum class ItemKind { Song, LocalFile, Episode, Station }

fun itemKindOf(t: Track?, live: Boolean = false): ItemKind = when {
    t == null -> ItemKind.Song
    live || InternetRadio.isStation(t.id) -> ItemKind.Station
    isPodcastId(t.id) -> ItemKind.Episode
    isLocalId(t.id) -> ItemKind.LocalFile
    else -> ItemKind.Song
}

/** Now Playing's derived state (everything else is read straight from [AppGraph.player]'s flows). */
class NowPlayingViewModel : ViewModel() {
    private val player = AppGraph.player
    private val started = SharingStarted.WhileSubscribed(5_000)

    val liked: StateFlow<Boolean> = combine(player.current, AppGraph.library.revision) { t, _ ->
        t != null && AppGraph.library.isLiked(t.id)
    }.stateIn(viewModelScope, started, false)

    val canLike: StateFlow<Boolean> = combine(player.current, AppGraph.session.state) { t, _ ->
        t != null && AppGraph.library.canLike(t.id)
    }.stateIn(viewModelScope, started, false)

    /** Off / shuffle / smart shuffle (player shuffle + Settings.smartShuffle). */
    val shuffleMode: StateFlow<SmartShuffle.Mode> = combine(player.queue, SettingsStore.flow) { q, s ->
        when {
            !q.shuffle -> SmartShuffle.Mode.Off
            s.smartShuffle -> SmartShuffle.Mode.Smart
            else -> SmartShuffle.Mode.Shuffle
        }
    }.stateIn(viewModelScope, started, SmartShuffle.Mode.Off)

    /** Sleep timer: minutes left (re-evaluated every 15 s), -1 = "at track end", 0 = off. */
    val sleepMinutes: StateFlow<Long> = combine(AppGraph.sleepTimer.state, ticker(15_000)) { st, _ ->
        when {
            st.atTrackEnd -> -1L
            st.deadlineMs > 0 -> AppGraph.sleepTimer.minutesLeft().coerceAtLeast(1)
            else -> 0L
        }
    }.stateIn(viewModelScope, started, 0L)

    /** The playing item is a finished download (source badge "İndirildi"). */
    val downloaded: StateFlow<Boolean> = combine(player.current, AppGraph.downloads.state) { t, _ ->
        t != null && AppGraph.downloads.isDownloaded(t.id)
    }.stateIn(viewModelScope, started, false)

    val currentId: StateFlow<String> = player.current.map { it?.id.orEmpty() }.stateIn(viewModelScope, started, "")

    fun toggleLike(): LikeResult {
        val t = player.currentTrack ?: return LikeResult.Unchanged
        val r = AppGraph.library.toggleLiked(t)
        AppGraph.playback.invalidateMediaButtons()
        return r
    }

    private fun ticker(ms: Long) = flow {
        while (true) {
            emit(Unit)
            delay(ms)
        }
    }
}
