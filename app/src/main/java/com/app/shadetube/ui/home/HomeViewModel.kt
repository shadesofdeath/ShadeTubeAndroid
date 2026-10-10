package com.app.shadetube.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.AppGraph
import com.app.shadetube.core.appScope
import com.app.shadetube.library.Card
import com.app.shadetube.library.HomeFeed
import com.app.shadetube.library.HomeRepository
import com.app.shadetube.library.HomeSection
import com.app.shadetube.spotify.SessionState
import com.app.shadetube.spotify.UserProfile
import com.app.shadetube.stats.SmartList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Text
import com.app.shadetube.stats.StatsPeriod
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keys of Home sections for "Tümünü gör" ([com.app.shadetube.ui.nav.Screen.HomeSection]); stable across rebuilds of
 * the feed (Spotify shelves by title).
 */
object HomeKeys {
    const val RECENT = "recent"
    const val SMART = "smart"
    const val TRENDING = "trending"
    const val FRESH = "fresh"
    const val PLAYLISTS = "playlists"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val FOR_YOU = "foryou"
    const val POPULAR_ARTISTS = "popular-artists"
    const val MORE_TRENDING = "more-trending"
    private const val SHELF = "shelf:"

    fun shelf(title: String) = SHELF + title

    /**
     * Spotify's "recently played" / "jump back in" shelf (shown as the 2-column tile grid like our own Son çalınanlar).
     * By title: the parsed shelf carries no section type, and the feed comes in the account's language.
     */
    fun isRecentShelf(title: String): Boolean = Text.fold(title).trim() in RECENT_SHELF_TITLES

    private val RECENT_SHELF_TITLES = setOf(
        "yakinlarda calinanlar", "son calinanlar", "kaldigin yerden devam et", "yeniden dinle",
        "recently played", "jump back in", "zuletzt gehort", "kurzlich gespielt", "ecoutes recemment",
        "reproducido recientemente", "escuchado recientemente", "ascoltati di recente", "tocadas recentemente",
        "недавно прослушано", "недавно прослушанное",
    )

    /** The cards of the section [key] in [feed] (null = no such section now). */
    fun cards(feed: HomeFeed, key: String): List<Card>? {
        val sections = when (feed) {
            is HomeFeed.Spotify -> feed.sections
            is HomeFeed.Open -> feed.sections
            else -> return null
        }
        for (s in sections) {
            val c = when (s) {
                is HomeSection.SpotifyShelf -> if (key == shelf(s.title)) s.cards else null
                is HomeSection.RecentlyPlayed -> if (key == RECENT) s.cards else null
                is HomeSection.PopularAndFresh -> when (key) {
                    TRENDING -> s.trending.map { HomeRepository.albumCard(it) }
                    FRESH -> s.fresh.map { HomeRepository.albumCard(it.album) }
                    else -> null
                }
                is HomeSection.YourPlaylists -> if (key == PLAYLISTS) s.cards else null
                is HomeSection.SavedAlbums -> if (key == ALBUMS) s.cards else null
                is HomeSection.FollowedArtists -> if (key == ARTISTS) s.cards else null
                is HomeSection.ForYou -> if (key == FOR_YOU) s.cards else null
                is HomeSection.PopularArtists -> if (key == POPULAR_ARTISTS) s.cards else null
                is HomeSection.MoreTrendingAlbums -> if (key == MORE_TRENDING) s.cards else null
                HomeSection.SmartLists -> null
            }
            if (c != null) return c
        }
        return null
    }

    fun isShelf(key: String) = key.startsWith(SHELF)
    fun shelfTitle(key: String) = key.removePrefix(SHELF)

    /** The "X dinleyenler…" seed of the For you section ("" none). */
    fun forYouSeed(feed: HomeFeed): String =
        ((feed as? HomeFeed.Open)?.sections?.firstOrNull { it is HomeSection.ForYou } as? HomeSection.ForYou)?.seedName.orEmpty()
}

/**
 * The Home feed shared by Home and its "Tümünü gör" pages (one collection of [com.app.shadetube.library.HomeRepository.feed],
 * so a section page does not reload the open catalog). Kept 60 s after the last page stops watching it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object HomeFeedHub {
    private val trigger = MutableStateFlow(0)
    val feed: StateFlow<HomeFeed> by lazy {
        trigger.flatMapLatest { AppGraph.home.feed() }
            .stateIn(appScope, SharingStarted.WhileSubscribed(60_000), HomeFeed.Loading)
    }

    fun reload() {
        trigger.value = trigger.value + 1
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel : ViewModel() {
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val feed: StateFlow<HomeFeed> = HomeFeedHub.feed

    val profile: StateFlow<UserProfile> = AppGraph.account.profile
    val loggedIn: StateFlow<Boolean> = AppGraph.account.state.map { it == SessionState.LoggedIn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppGraph.account.loggedIn)

    val smartLists: StateFlow<List<SmartList>> = AppGraph.smartLists.lists
    val smartReady: StateFlow<Boolean> = AppGraph.smartLists.ready

    val nowPlaying: StateFlow<NowPlayingState> = nowPlayingFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingState())

    /** "Bu hafta" card: the last 7 days' listening (null until the history is loaded; recomputed when it changes). */
    val week: StateFlow<WeekStats?> = AppGraph.stats.version
        .mapLatest {
            withContext(AppDispatchers.cpu) {
                if (!AppGraph.stats.store.isLoaded) null
                else AppGraph.stats.summarize(StatsPeriod.Week, topN = 0, recentN = 0).let { WeekStats(it.listenedMs, it.streams) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        AppGraph.smartLists.ensure()
    }

    /** Pull to refresh / retry: the Spotify library is fetched again; logged out the open catalog is reloaded. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            if (AppGraph.account.loggedIn) AppGraph.session.reloadLibrary()
            AppGraph.smartLists.ensure()
            HomeFeedHub.reload()
            delay(600)
            withTimeoutOrNull(15_000) { feed.first { it !is HomeFeed.Loading } }
            _refreshing.value = false
        }
    }

    fun retry() = HomeFeedHub.reload()
}

/** Last 7 days for the "Bu hafta" card. */
data class WeekStats(val listenedMs: Long, val streams: Int)

/** "Tümünü gör" page of one Home section. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSectionViewModel : ViewModel() {
    val feed: StateFlow<HomeFeed> = HomeFeedHub.feed
    val smartLists: StateFlow<List<SmartList>> = AppGraph.smartLists.lists
    val smartReady: StateFlow<Boolean> = AppGraph.smartLists.ready

    init {
        AppGraph.smartLists.ensure()
    }
}
