package com.app.shadetube.ui.artist

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.ArtistPage
import com.app.shadetube.library.ArtistHeroInfo
import com.app.shadetube.library.ArtistPlayResult
import com.app.shadetube.library.ArtistSection
import com.app.shadetube.library.FollowResult
import com.app.shadetube.ui.home.NowPlayingState
import com.app.shadetube.ui.home.nowPlayingFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ArtistLoad {
    data object Loading : ArtistLoad
    data object Error : ArtistLoad
    data class Ready(val page: ArtistPage, val hero: ArtistHeroInfo, val sections: List<ArtistSection>) : ArtistLoad
}

class ArtistViewModel(val id: String) : ViewModel() {
    private val repo = AppGraph.artists
    private val _state = MutableStateFlow<ArtistLoad>(ArtistLoad.Loading)
    val state: StateFlow<ArtistLoad> = _state.asStateFlow()

    val following: StateFlow<Boolean> = combine(AppGraph.library.revision, AppGraph.account.library) { _, _ ->
        repo.isFollowing(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repo.isFollowing(id))

    val nowPlaying: StateFlow<NowPlayingState> = nowPlayingFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingState())

    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages: Flow<Int> = _messages.receiveAsFlow()

    val contextUri: String get() = repo.contextUri(id)

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        _state.value = ArtistLoad.Loading
        viewModelScope.launch {
            _state.value = try {
                val page = repo.load(id)
                ArtistLoad.Ready(page, repo.hero(page.artist), repo.sections(page))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ArtistLoad.Error
            }
        }
    }

    /** -1: the hero's play button (pause / resume when this artist plays); else a "Popüler" row. */
    fun play(index: Int) {
        val r = _state.value as? ArtistLoad.Ready ?: return
        when (repo.play(r.page, index)) {
            ArtistPlayResult.NoPopularTracks -> message(R.string.artist_no_popular)
            else -> Unit
        }
    }

    fun toggleFollow() {
        val r = _state.value as? ArtistLoad.Ready ?: return
        when (repo.toggleFollow(r.page.artist)) {
            FollowResult.OnlySpotifyArtists -> message(R.string.artist_only_spotify_follow)
            else -> Unit
        }
    }

    fun startRadio() {
        val r = _state.value as? ArtistLoad.Ready ?: return
        AppGraph.radio.startRadio(r.page.artist.id, r.page.artist.name)
    }

    private fun message(@StringRes text: Int) {
        _messages.trySend(text)
    }
}
