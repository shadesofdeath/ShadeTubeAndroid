package com.app.shadetube.ui.search

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.library.CollectionKind
import com.app.shadetube.library.Genre
import com.app.shadetube.library.LinkHint
import com.app.shadetube.library.SearchInput
import com.app.shadetube.library.SearchRepository
import com.app.shadetube.library.SearchSubmit
import com.app.shadetube.library.TagResults
import com.app.shadetube.links.Link
import com.app.shadetube.links.LinkAction
import com.app.shadetube.links.LinkOpener
import com.app.shadetube.links.Links
import com.app.shadetube.podcasts.DirectoryEntry
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.youtube.Match
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.app.shadetube.ui.home.NowPlayingState
import com.app.shadetube.ui.home.nowPlayingFlow

enum class SearchResultTab { All, Songs, Albums, Artists, Playlists, Podcasts }

sealed interface ResultsState {
    data object Idle : ResultsState
    data class Loading(val query: String) : ResultsState
    data class Error(val query: String, val message: String) : ResultsState
    data class Ready(val query: String, val results: SearchResults, val podcasts: List<DirectoryEntry>) : ResultsState {
        val isEmpty: Boolean
            get() = results.tracks.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty() &&
                results.playlists.isEmpty() && podcasts.isEmpty()
    }
}

sealed interface GenreResults {
    data object Loading : GenreResults
    data object Error : GenreResults
    data class Ready(val results: TagResults) : GenreResults
}

data class GenreState(val genre: Genre, val results: GenreResults)

/** A link the clipboard holds, offered when the empty search box gets focus. */
data class ClipboardLink(val text: String, val hint: LinkHint)

data class SearchUiState(
    val text: String = "",
    val input: SearchInput = SearchInput.Browse,
    val results: ResultsState = ResultsState.Idle,
    val tab: SearchResultTab = SearchResultTab.All,
    val genre: GenreState? = null,
    val clipboard: ClipboardLink? = null,
    /** A link is being looked up (opening a Spotify track / YouTube video). */
    val linkBusy: Boolean = false,
)

sealed interface SearchEvent {
    data class Navigate(val screen: Screen) : SearchEvent
    data object Login : SearchEvent
    data class Message(@param:StringRes val text: Int) : SearchEvent
}

@OptIn(FlowPreview::class)
class SearchViewModel : ViewModel() {
    private val repo: SearchRepository = AppGraph.search
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    val recent: StateFlow<List<String>> = repo.recent.items
    val nowPlaying: StateFlow<NowPlayingState> = nowPlayingFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingState())

    private val _events = Channel<SearchEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The query to search after the debounce ("" = none). */
    private val pending = MutableStateFlow("")
    private var searchJob: Job? = null
    private var genreJob: Job? = null
    private var linkJob: Job? = null

    init {
        viewModelScope.launch { repo.recent.load() }
        viewModelScope.launch {
            pending.debounce(SearchRepository.DEBOUNCE_MS).collectLatest { q ->
                if (q.isNotEmpty()) runSearch(q, force = false)
            }
        }
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    fun onTextChange(text: String) {
        val input = repo.classify(text)
        _state.value = _state.value.copy(text = text, input = input, clipboard = if (text.isEmpty()) _state.value.clipboard else null)
        when (input) {
            SearchInput.Browse -> {
                pending.value = ""
                searchJob?.cancel()
                _state.value = _state.value.copy(results = ResultsState.Idle, tab = SearchResultTab.All)
            }
            SearchInput.TooShort -> Unit
            is SearchInput.Query -> pending.value = input.query.trim()
            is SearchInput.LinkFound -> {
                pending.value = ""
                searchJob?.cancel()
                _state.value = _state.value.copy(results = ResultsState.Idle)
            }
        }
    }

    /** IME search / Enter: a real search (recorded), or the link opened. */
    fun submit() {
        val text = _state.value.text.trim()
        viewModelScope.launch {
            when (val s = repo.submit(text)) {
                SearchSubmit.Nothing -> Unit
                is SearchSubmit.OpenLink -> openLink(s.link)
                is SearchSubmit.Connect -> _events.send(SearchEvent.Login)
                is SearchSubmit.Search -> runSearch(s.query, force = true)
            }
        }
    }

    /** A "Son aramalar" row: search for it again at once. */
    fun searchRecent(query: String) {
        onTextChange(query)
        submit()
    }

    fun removeRecent(query: String) {
        viewModelScope.launch { repo.recent.remove(query) }
    }

    fun clearRecent() {
        viewModelScope.launch { repo.recent.clear() }
    }

    fun selectTab(tab: SearchResultTab) {
        _state.value = _state.value.copy(tab = tab)
    }

    /** The current query, remembered once the user acts on its results. */
    fun rememberQuery() {
        val q = (state.value.results as? ResultsState.Ready)?.query ?: return
        viewModelScope.launch { repo.remember(q) }
    }

    fun retry() {
        val q = when (val r = _state.value.results) {
            is ResultsState.Error -> r.query
            else -> return
        }
        runSearch(q, force = true)
    }

    private fun runSearch(query: String, force: Boolean) {
        val cur = _state.value.results
        if (!force && (cur is ResultsState.Loading && cur.query == query || cur is ResultsState.Ready && cur.query == query)) return
        searchJob?.cancel()
        _state.value = _state.value.copy(results = ResultsState.Loading(query))
        searchJob = viewModelScope.launch {
            val podcasts = async {
                try {
                    AppGraph.podcasts.search(query)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val r = try {
                Result.success(repo.search(query))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            val pods = podcasts.await()
            _state.value = _state.value.copy(
                results = r.fold(
                    { ResultsState.Ready(query, it, pods) },
                    {
                        // The catalog failed but podcasts came back: show those.
                        if (pods.isNotEmpty()) ResultsState.Ready(query, SearchResults(), pods)
                        else ResultsState.Error(query, it.message ?: it.toString())
                    },
                ),
            )
        }
    }

    // ---- playback -------------------------------------------------------------------------------------------------

    /** A song of the results: the result songs play as the "search" context. */
    fun playTrack(index: Int) {
        val r = _state.value.results as? ResultsState.Ready ?: return
        rememberQuery()
        AppGraph.playerControls.playContext(r.results.tracks, index, "search", r.query)
    }

    // ---- genres ---------------------------------------------------------------------------------------------------

    fun openGenre(g: Genre) {
        genreJob?.cancel()
        _state.value = _state.value.copy(genre = GenreState(g, GenreResults.Loading))
        genreJob = viewModelScope.launch {
            val r = try {
                GenreResults.Ready(repo.tagSearch(g.tag))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                GenreResults.Error
            }
            if (_state.value.genre?.genre == g) _state.value = _state.value.copy(genre = GenreState(g, r))
        }
    }

    fun closeGenre() {
        genreJob?.cancel()
        _state.value = _state.value.copy(genre = null)
    }

    // ---- links ----------------------------------------------------------------------------------------------------

    /** The search box got focus while empty: offer a link the clipboard holds. */
    fun onClipboard(text: String?) {
        if (_state.value.text.isNotEmpty()) return
        val link = Links.parseClipboard(text?.trim())
        if (!link.isLink || link.kind == com.app.shadetube.links.LinkKind.Unsupported) {
            _state.value = _state.value.copy(clipboard = null)
            return
        }
        _state.value = _state.value.copy(clipboard = ClipboardLink(text!!.trim(), repo.linkHint(link)))
    }

    fun dismissClipboard() {
        _state.value = _state.value.copy(clipboard = null)
    }

    /** The link card's button (or Enter on a link). */
    fun activate(hint: LinkHint) {
        when (hint.state) {
            LinkHint.State.Unsupported -> Unit
            LinkHint.State.Connect -> _events.trySend(SearchEvent.Login)
            LinkHint.State.Open -> openLink(hint.link)
        }
    }

    private fun openLink(link: Link) {
        linkJob?.cancel()
        _state.value = _state.value.copy(linkBusy = true)
        linkJob = viewModelScope.launch {
            try {
                handle(LinkOpener.open(link, AppGraph.linkLookups))
            } finally {
                _state.value = _state.value.copy(linkBusy = false)
            }
        }
    }

    private suspend fun handle(a: LinkAction) {
        when (a) {
            LinkAction.Search -> Unit
            LinkAction.Unsupported -> _events.send(SearchEvent.Message(R.string.search_link_unsupported_title))
            LinkAction.NeedsSpotifyLogin -> _events.send(SearchEvent.Login)
            is LinkAction.OpenAlbum -> navigate(Screen.Collection(CollectionKind.Album, a.albumId))
            is LinkAction.OpenPlaylist -> navigate(Screen.Collection(CollectionKind.Playlist, a.playlistId))
            is LinkAction.OpenArtist -> navigate(Screen.Artist(a.artistId))
            is LinkAction.ImportYouTubePlaylist ->
                navigate(Screen.Import(youtubeUrl = "https://www.youtube.com/playlist?list=" + a.listId))
            is LinkAction.PlayInAlbum -> {
                val album = a.album
                AppGraph.playerControls.playContext(
                    a.tracks, a.index,
                    if (album != null && album.id.isNotEmpty()) "album:" + album.id else "link",
                    album?.name?.takeIf { it.isNotEmpty() } ?: a.track.name,
                )
                if (a.navigateAlbumId.isNotEmpty()) navigate(Screen.Collection(CollectionKind.Album, a.navigateAlbumId))
            }
            is LinkAction.PlayYouTubeVideo -> {
                val p = a.pin
                AppGraph.matchService.pin(
                    a.track.id,
                    Match(
                        videoId = p.videoId, title = p.title, channel = p.channel, durationSec = p.durationSec,
                        score = p.score.toDouble(), manual = p.manual, thumbnailUrl = p.thumbnailUrl,
                    ),
                )
                AppGraph.playerControls.playContext(listOf(a.track), 0, "link", a.track.name)
            }
            is LinkAction.Failed -> _events.send(
                SearchEvent.Message(
                    when (a.reason) {
                        LinkAction.FailReason.SpotifyTrackNotFound -> R.string.search_link_fail_spotify_track
                        LinkAction.FailReason.MbRecordingNotFound -> R.string.search_link_fail_mb_recording
                        LinkAction.FailReason.MbReleaseNotFound -> R.string.search_link_fail_mb_release
                        LinkAction.FailReason.YouTubeVideoFailed -> R.string.search_link_fail_youtube
                        LinkAction.FailReason.LiveStream -> R.string.search_link_fail_live
                    },
                ),
            )
        }
    }

    private suspend fun navigate(s: Screen) = _events.send(SearchEvent.Navigate(s))
}
