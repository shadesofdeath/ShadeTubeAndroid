package com.app.shadetube.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.AppGraph
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.downloads.DlState
import com.app.shadetube.downloads.DownloadSync
import com.app.shadetube.library.CollectionController
import com.app.shadetube.library.CollectionKind
import com.app.shadetube.library.CollectionMenu
import com.app.shadetube.library.CollectionState
import com.app.shadetube.library.SaveAlbumResult
import com.app.shadetube.library.TrackSort
import com.app.shadetube.stats.SmartList
import com.app.shadetube.ui.home.NowPlayingState
import com.app.shadetube.ui.home.nowPlayingFlow
import com.app.shadetube.ui.library.OfflineCandidate
import com.app.shadetube.ui.library.PlaylistOps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The offline state of the page (the "Çevrimdışı" chip and the sync card). */
data class SyncUi(
    val synced: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val active: Int = 0,
    val listing: Boolean = false,
    val paused: Boolean = false,
) {
    /** The sync card shows while songs are still to be downloaded. */
    val inProgress: Boolean get() = synced && (listing || active > 0 || (total > 0 && done < total))
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/** Ids of the downloaded songs (the rows' check marks). */
internal fun downloadedIdsFlow(): Flow<Set<String>> = AppGraph.downloads.state
    .map { s -> s.items.asSequence().filter { it.state == DlState.Done }.map { it.track.id }.toHashSet() }
    .distinctUntilChanged()

internal fun syncUiFlow(trigger: Flow<Any?>, ruleId: () -> String): Flow<SyncUi> =
    combine(AppGraph.sync.uiState, SettingsStore.flow.map { it.syncPaused }.distinctUntilChanged(), trigger) { _, paused, _ ->
        val id = ruleId()
        if (id.isEmpty()) return@combine SyncUi()
        val st = AppGraph.sync.status(id)
        SyncUi(
            synced = st.synced,
            total = st.progress.total,
            done = st.progress.done,
            active = st.progress.active,
            listing = st.listing,
            paused = paused || AppGraph.sync.waitReason() == DownloadSync.WaitReason.Paused,
        )
    }.distinctUntilChanged()

/** Album / playlist / Liked Songs page: one [CollectionController] per page. */
class CollectionViewModel(val kind: CollectionKind, val id: String, autoplay: Boolean) : ViewModel() {
    val controller = CollectionController(kind, if (autoplay) "$id#play" else id, AppGraph.collections, viewModelScope)
    val state: StateFlow<CollectionState> = controller.state
    val ops = PlaylistOps(viewModelScope)

    /** "Geliştir" exists for playlists and Liked Songs. */
    val canEnhance: Boolean = kind != CollectionKind.Album && AppGraph.collections.enhancer != null

    /** The "Geliştir" collection key (track menus add / hide its recommendations). */
    val enhanceKey: String = if (kind == CollectionKind.Liked) "liked" else id

    val nowPlaying: StateFlow<NowPlayingState> = nowPlayingFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingState())

    val downloaded: StateFlow<Set<String>> = downloadedIdsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val sync: StateFlow<SyncUi> = syncUiFlow(state.map { it.header?.title }.distinctUntilChanged()) {
        offlineCandidate()?.ruleId.orEmpty()
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUi())

    /** Blocked songs among the shown rows (dimmed, not playable). */
    val blocked: StateFlow<Set<String>> = combine(state, AppGraph.blacklist.revision) { s, _ ->
        s.displayed.asSequence().filter { AppGraph.blacklist.isBlocked(it) }.map { it.id }.toHashSet()
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        controller.start()
    }

    fun menu(): CollectionMenu = controller.menu()

    fun offlineCandidate(): OfflineCandidate? {
        val s = state.value
        if (s.header == null) return null
        val off = controller.menu().offline
        return OfflineCandidate(kind, off.id, off.name, off.images)
    }

    fun play(shuffle: Boolean) = controller.playFrom(-1, shuffle)
    fun playRow(displayIndex: Int) = controller.playFrom(displayIndex, false)
    fun toggleEnhanced() = controller.toggleEnhanced()
    fun addRecommended(t: Track) = controller.addRecommended(t)
    fun hideRecommended(t: Track) = controller.hideRecommended(t)
    fun setFilter(text: String) = controller.setFilter(text)
    fun setSort(key: TrackSort, descending: Boolean) = controller.setSort(key, descending)
    fun loadMore() = controller.loadMore()
    fun retry() = controller.retry()
    fun consumeNotice() = controller.consumeNotice()
    fun toggleSavedAlbum(): SaveAlbumResult = controller.toggleSavedAlbum()

    fun toggleOffline() {
        offlineCandidate()?.let { ops.toggleOffline(it) }
    }

    fun pauseSync() = SettingsStore.update { it.copy(syncPaused = true) }

    fun resumeSync() {
        val id = offlineCandidate()?.ruleId ?: return
        AppGraph.sync.syncNow(id)
    }

    fun startRadio(seed: String, name: String) = AppGraph.radio.startRadio(seed, name)

    /** "Sıraya ekle": the list's own shown songs. */
    fun enqueueAll(): Boolean {
        val tracks = state.value.displayed.filter { !it.recommended }
        if (tracks.isEmpty()) return false
        AppGraph.playerControls.enqueue(tracks)
        return true
    }
}

/** "Senin için yapıldı" list page. */
class SmartListViewModel(val id: String) : ViewModel() {
    val list: StateFlow<SmartList?> = AppGraph.smartLists.lists.map { l -> l.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppGraph.smartLists.find(id))
    val ready: StateFlow<Boolean> = AppGraph.smartLists.ready
    val nowPlaying: StateFlow<NowPlayingState> = nowPlayingFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingState())
    val downloaded: StateFlow<Set<String>> = downloadedIdsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val contextUri: String get() = "smart:$id"

    init {
        AppGraph.smartLists.ensure()
    }

    /** -1 = from the start (pause / resume when this list is the playing context). */
    fun play(index: Int, shuffle: Boolean, title: String) {
        val tracks = list.value?.tracks.orEmpty()
        if (tracks.isEmpty()) return
        val p = AppGraph.playerControls
        if (index < 0 && !shuffle && p.contextUri == contextUri) {
            if (p.isPlaying) p.togglePause() else if (p.isPaused) p.play() else p.playContext(tracks, -1, contextUri, title)
            return
        }
        if (shuffle) p.setShuffle(true)
        p.playContext(tracks, index, contextUri, title)
    }

    fun enqueueAll(): Boolean {
        val tracks = list.value?.tracks.orEmpty()
        if (tracks.isEmpty()) return false
        AppGraph.playerControls.enqueue(tracks)
        return true
    }
}
