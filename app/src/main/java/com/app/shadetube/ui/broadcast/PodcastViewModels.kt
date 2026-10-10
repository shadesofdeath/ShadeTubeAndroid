package com.app.shadetube.ui.broadcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.podcasts.DirectoryEntry
import com.app.shadetube.podcasts.Episode
import com.app.shadetube.podcasts.PodcastDownloads
import com.app.shadetube.podcasts.PodcastError
import com.app.shadetube.podcasts.PodcastErrorKind
import com.app.shadetube.podcasts.Podcasts
import com.app.shadetube.podcasts.Show
import com.app.shadetube.podcasts.Subscription
import com.app.shadetube.ui.components.DownloadUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An episode row's state: progress, played, download, playing. */
data class EpisodeUi(
    val episode: Episode,
    val played: Boolean = false,
    /** Started and not finished. */
    val inProgress: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val download: DownloadUiState = DownloadUiState.None,
    val current: Boolean = false,
    val playing: Boolean = false,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

internal fun episodeUi(e: Episode, downloads: PodcastDownloads, now: NowPlayingInfo): EpisodeUi {
    val st = Podcasts.store.state(e.id)
    val current = now.trackId == e.id
    val dur = when {
        current && now.durationMs > 0 -> now.durationMs
        (st?.durationMs ?: 0) > 0 -> st!!.durationMs
        else -> e.durationMs
    }
    val pos = if (current && now.positionMs > 0) now.positionMs else st?.positionMs ?: 0
    val played = st?.played == true && !current
    val dl = when {
        Podcasts.isDownloaded(e.id) -> DownloadUiState.Done
        downloads.active == e.id -> DownloadUiState.Downloading(downloads.progress.takeIf { it > 0f })
        e.id in downloads.waiting -> DownloadUiState.Queued
        else -> DownloadUiState.None
    }
    return EpisodeUi(e, played, !played && pos > 0, pos, dur, dl, current, current && now.playing)
}

/** Episode rows for [episodes], kept fresh with the store (progress / played / downloads) and the player. */
internal fun episodeRows(episodes: Flow<List<Episode>>, now: Flow<NowPlayingInfo>): Flow<List<EpisodeUi>> =
    combine(episodes, Podcasts.store.revision, Podcasts.downloads, now) { list, _, dl, n -> list.map { episodeUi(it, dl, n) } }

/** Shared episode actions (rows, long-press sheet, episode page). */
internal abstract class EpisodeActionsViewModel : ViewModel() {
    val nowPlaying: StateFlow<NowPlayingInfo> =
        BroadcastPlayback.nowPlaying().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingInfo())

    fun setPlayed(e: Episode, played: Boolean) = Podcasts.setPlayed(e, played)

    /** None / failed: download; waiting / downloading: cancel. A downloaded episode is deleted with [deleteDownload]. */
    fun toggleDownload(ui: EpisodeUi) {
        when (ui.download) {
            DownloadUiState.None, DownloadUiState.Failed -> Podcasts.startDownload(ui.episode)
            DownloadUiState.Queued, is DownloadUiState.Downloading -> Podcasts.cancelDownload(ui.episode.id)
            DownloadUiState.Done -> Unit
        }
    }

    fun deleteDownload(e: Episode) {
        viewModelScope.launch { Podcasts.deleteDownload(e) }
    }
}

/** Podcasts tab (SCREENS 17): subscriptions, the country's charts, search, add by RSS, new episodes. */
internal class PodcastsHomeViewModel : EpisodeActionsViewModel() {
    val subscriptions: StateFlow<List<Subscription>> = Podcasts.store.subscriptions

    private val _charts = MutableStateFlow<Load<List<DirectoryEntry>>>(Load.Loading)
    val charts: StateFlow<Load<List<DirectoryEntry>>> = _charts.asStateFlow()

    private val _newEpisodes = MutableStateFlow<Load<List<Episode>>>(Load.Loading)
    val newEpisodeRows: StateFlow<Load<List<EpisodeUi>>> = combine(_newEpisodes, Podcasts.store.revision, Podcasts.downloads, nowPlaying) { l, _, dl, n ->
        when (l) {
            is Load.Ready -> Load.Ready(l.value.map { episodeUi(it, dl, n) })
            is Load.Failed -> l
            Load.Loading -> Load.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Load.Loading)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** null = not searching. */
    private val _results = MutableStateFlow<Load<List<DirectoryEntry>>?>(null)
    val results: StateFlow<Load<List<DirectoryEntry>>?> = _results.asStateFlow()
    private var searchJob: Job? = null

    /** The RSS dialog: adding, and the last failure. */
    data class AddState(val adding: Boolean = false, val error: PodcastErrorKind? = null)

    private val _add = MutableStateFlow(AddState())
    val add: StateFlow<AddState> = _add.asStateFlow()

    init {
        loadCharts()
        loadNewEpisodes()
        // Subscribing / unsubscribing changes the new episodes list.
        viewModelScope.launch {
            var first = true
            subscriptions.map { l -> l.map { it.feedUrl }.toSet() }.collect {
                if (!first) loadNewEpisodes()
                first = false
            }
        }
    }

    fun loadCharts() {
        _charts.value = Load.Loading
        viewModelScope.launch {
            _charts.value = try {
                Load.Ready(Podcasts.topCharts(limit = 30))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e)
            }
        }
    }

    private fun loadNewEpisodes() {
        viewModelScope.launch {
            _newEpisodes.value = try {
                Load.Ready(Podcasts.newEpisodes(perShow = 5, max = 30, unplayedOnly = false).also { Podcasts.store.remember(it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e)
            }
        }
    }

    /** Pull to refresh: every subscription read again, then the charts if they had failed. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                Podcasts.refreshAll(manual = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            loadNewEpisodes()
            if (_charts.value !is Load.Ready) loadCharts()
            _refreshing.value = false
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        searchJob?.cancel()
        if (q.isBlank()) {
            _results.value = null
            return
        }
        searchJob = viewModelScope.launch {
            delay(450)
            search(q)
        }
    }

    fun submit(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) return
        searchJob = viewModelScope.launch { search(q) }
    }

    private suspend fun search(q: String) {
        _results.value = Load.Loading
        _results.value = try {
            Load.Ready(Podcasts.search(q.trim()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e)
        }
    }

    fun addByUrl(url: String, onAdded: (Show) -> Unit) {
        if (_add.value.adding) return
        _add.value = AddState(adding = true)
        viewModelScope.launch {
            try {
                val show = Podcasts.addByUrl(url)
                _add.value = AddState()
                onAdded(show)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _add.value = AddState(error = PodcastErrorKind.of(e))
            }
        }
    }

    fun clearAddError() {
        _add.value = AddState()
    }

    fun play(rows: List<EpisodeUi>, index: Int, contextName: String) =
        BroadcastPlayback.playEpisodes(rows.map { it.episode }, index, contextName)
}

/** A show page: header, subscribe, episodes (feed URL or directory id). */
internal class PodcastShowViewModel(private val feedOrAppleId: String) : EpisodeActionsViewModel() {
    private val isFeed = feedOrAppleId.startsWith("http://", true) || feedOrAppleId.startsWith("https://", true)

    private val _show = MutableStateFlow<Load<Show>>(Load.Loading)
    val show: StateFlow<Load<Show>> = _show.asStateFlow()

    /** What to draw in the header before the feed loads (a subscription's card). */
    val placeholder: Show? = if (isFeed) Podcasts.store.subscription(feedOrAppleId)?.let { Podcasts.showOf(it) } else null

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val subscribed: StateFlow<Boolean> = combine(Podcasts.store.subscriptions, _show) { subs, s ->
        val url = (s as? Load.Ready)?.value?.feedUrl ?: if (isFeed) feedOrAppleId else ""
        url.isNotEmpty() && subs.any { it.feedUrl == url }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, isFeed && Podcasts.store.isSubscribed(feedOrAppleId))

    val rows: StateFlow<List<EpisodeUi>> =
        episodeRows(_show.map { (it as? Load.Ready)?.value?.episodes.orEmpty() }, nowPlaying)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        load()
    }

    fun load() {
        if (_show.value !is Load.Ready) _show.value = Load.Loading
        viewModelScope.launch {
            try {
                val s = if (isFeed) Podcasts.loadShow(feedUrl = feedOrAppleId) else Podcasts.loadShow(appleId = feedOrAppleId)
                _show.value = Load.Ready(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_show.value !is Load.Ready) _show.value = Load.Failed(e)
            }
        }
    }

    fun refresh() {
        val url = (_show.value as? Load.Ready)?.value?.feedUrl ?: return load()
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                _show.value = Load.Ready(Podcasts.reloadShow(url))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            _refreshing.value = false
        }
    }

    fun toggleSubscribe() {
        val s = (_show.value as? Load.Ready)?.value ?: return
        if (subscribed.value) Podcasts.unsubscribe(s.feedUrl) else {
            Podcasts.subscribe(s)
            Podcasts.showSeen(s)
        }
    }

    fun play(index: Int) {
        val s = (_show.value as? Load.Ready)?.value ?: return
        BroadcastPlayback.playEpisodes(s.episodes, index, s.title)
    }

    /** The header's play button: the episode in progress, else the newest one not heard yet. */
    fun playLatest() {
        val s = (_show.value as? Load.Ready)?.value ?: return
        val e = Podcasts.pickEpisodeToPlay(s) ?: return
        play(s.episodes.indexOfFirst { it.id == e.id }.coerceAtLeast(0))
    }
}

/** An episode page: notes, continue / play from the start, played, download. */
internal class PodcastEpisodeViewModel(private val episodeId: String) : EpisodeActionsViewModel() {
    private val _episode = MutableStateFlow<Load<Episode>>(Load.Loading)
    val episode: StateFlow<Load<Episode>> = _episode.asStateFlow()

    val row: StateFlow<EpisodeUi?> =
        episodeRows(_episode.map { listOfNotNull((it as? Load.Ready)?.value) }, nowPlaying)
            .map { it.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        load()
    }

    fun load() {
        _episode.value = Load.Loading
        viewModelScope.launch {
            _episode.value = try {
                Podcasts.loadEpisode(episodeId)?.let { Load.Ready(it) } ?: Load.Failed(PodcastError("unknown episode"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e)
            }
        }
    }

    /** Continue where it was left (or start); pauses when it is the one playing. */
    fun resume() {
        val e = (_episode.value as? Load.Ready)?.value ?: return
        BroadcastPlayback.playEpisodes(listOf(e), 0, e.show)
    }

    fun playFromStart() {
        val e = (_episode.value as? Load.Ready)?.value ?: return
        Podcasts.setPlayed(e, false) // clears the position
        BroadcastPlayback.playEpisodes(listOf(e), 0, e.show, toggleIfCurrent = false)
    }
}
