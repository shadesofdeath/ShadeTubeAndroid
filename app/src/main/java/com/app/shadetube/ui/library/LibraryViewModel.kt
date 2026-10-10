package com.app.shadetube.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.AppGraph
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.library.Card
import com.app.shadetube.library.CardShape
import com.app.shadetube.library.CollectionKind
import com.app.shadetube.library.Destination
import com.app.shadetube.library.LibraryBrowser
import com.app.shadetube.library.LibrarySort
import com.app.shadetube.library.LibraryTab
import com.app.shadetube.library.LibraryView
import com.app.shadetube.spotify.SessionState
import com.app.shadetube.stats.SmartList
import com.app.shadetube.ui.home.NowPlayingState
import com.app.shadetube.ui.home.nowPlayingFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What a long-pressed library card offers. */
data class CardMenu(
    val card: Card,
    val title: String,
    val canRename: Boolean,
    val canDelete: Boolean,
    val offline: OfflineCandidate?,
    val offlineOn: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val folderId: String) : ViewModel() {
    private val route = LibraryBrowser.folderRoute(folderId)
    private val _tab = MutableStateFlow(LibraryTab.Playlists)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()
    private val _sort = MutableStateFlow(LibrarySort.Recent)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()
    /** Grid / list, kept in Settings.libraryGrid. */
    val grid: StateFlow<Boolean> = SettingsStore.flow.map { it.libraryGrid }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.current.libraryGrid)

    /** null until the first build. */
    val view: StateFlow<LibraryView?> = combine(_tab, _sort) { t, s -> t to s }
        .flatMapLatest { (t, s) -> AppGraph.libraryBrowser.view(route, t, "", s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Spotify library still loading (logged in, first snapshot not there): skeleton. */
    val loading: StateFlow<Boolean> = combine(AppGraph.account.state, AppGraph.account.library) { st, snap ->
        st == SessionState.Connecting || (st == SessionState.LoggedIn && !snap.loaded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val smartLists: StateFlow<List<SmartList>> = AppGraph.smartLists.lists

    /** Sync rule ids (offline marks on the cards / menu state). */
    val syncedIds: StateFlow<Set<String>> = AppGraph.sync.uiState.map { s -> s.rules.map { it.rule.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val nowPlaying: StateFlow<NowPlayingState> = nowPlayingFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingState())

    val ops = PlaylistOps(viewModelScope)

    private val _menu = MutableStateFlow<CardMenu?>(null)
    val menu: StateFlow<CardMenu?> = _menu.asStateFlow()

    init {
        AppGraph.smartLists.ensure()
    }

    fun selectTab(t: LibraryTab) {
        _tab.value = t
    }

    fun setSort(s: LibrarySort) {
        _sort.value = s
    }

    fun toggleGrid() {
        SettingsStore.update { it.copy(libraryGrid = !it.libraryGrid) }
    }

    // ---- card menu ------------------------------------------------------------------------------------------------

    fun openMenu(card: Card, title: String) {
        if (card.isFolder || card.shape == CardShape.Circle) return
        val offline = offlineCandidate(card, title)
        val id = (card.destination as? Destination.Playlist)?.id.orEmpty()
        _menu.value = CardMenu(
            card = card,
            title = title,
            canRename = id.isNotEmpty() && ops.canRename(id),
            canDelete = id.isNotEmpty() && ops.deleteKind(id) != null,
            offline = offline,
            offlineOn = offline != null && ops.isOffline(offline),
        )
    }

    fun closeMenu() {
        _menu.value = null
    }

    fun offlineCandidate(card: Card, title: String): OfflineCandidate? = when (val d = card.destination) {
        Destination.Liked -> OfflineCandidate(CollectionKind.Liked, "", title, emptyList())
        is Destination.Playlist -> OfflineCandidate(CollectionKind.Playlist, d.id, title, card.images)
        is Destination.Album -> OfflineCandidate(CollectionKind.Album, d.id, title, card.images)
        else -> null
    }

    // ---- history ----------------------------------------------------------------------------------------------------

    fun playHistory(tracks: List<Track>, index: Int, name: String) {
        AppGraph.playerControls.playContext(tracks, index, "history", name)
    }
}
