package com.app.shadetube.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.playback.features.Blacklist
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.TrackListSkeleton
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class BlocklistViewModel : ViewModel() {
    data class State(
        val loaded: Boolean = false,
        val tracks: List<Blacklist.Entry> = emptyList(),
        val artists: List<Blacklist.Entry> = emptyList(),
    )

    private val blacklist get() = AppGraph.blacklist

    val state: StateFlow<State> = AppGraph.blacklist.revision
        .map { withContext(AppDispatchers.io) { State(true, blacklist.blockedTracks(), blacklist.blockedArtists()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), State())

    fun remove(e: Blacklist.Entry) = blacklist.remove(e)

    /** Undo of [remove] for a song: blocked again from what the entry kept. */
    fun restoreTrack(e: Blacklist.Entry) {
        val names = e.artists.split(", ").filter { it.isNotEmpty() }.ifEmpty { listOf(e.artist0) }
        blacklist.setTrackBlocked(Track(id = e.id, name = e.name, artists = names.map { ArtistRef("", it) }, durationMs = e.durationMs), true)
    }

    fun restoreArtist(e: Blacklist.Entry) = blacklist.setArtistBlocked(e.id, e.name, true)
}

/** Engellenenler: blocked songs and artists, each with "remove" (+ undo snackbar); empty state. */
@Composable
fun BlocklistScreen(contentPadding: PaddingValues) {
    val vm: BlocklistViewModel = viewModel { BlocklistViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val localRes = LocalResources.current
    val x = ShadeTheme.extras
    val unblockCd = stringResource(R.string.settings_unblock_cd)
    fun removed(e: Blacklist.Entry, artist: Boolean) {
        vm.remove(e)
        nav.message(
            localRes.getString(R.string.settings_unblocked, e.name),
            localRes.getString(R.string.action_undo),
        ) { if (artist) vm.restoreArtist(e) else vm.restoreTrack(e) }
    }
    SettingsScaffold(stringResource(R.string.settings_blocked), contentPadding) {
        when {
            !state.loaded -> item(key = "loading") { TrackListSkeleton() }
            state.tracks.isEmpty() && state.artists.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = R.drawable.ic_block,
                    title = stringResource(R.string.settings_blocked_empty_title),
                    body = stringResource(R.string.settings_blocked_empty_body),
                )
            }
            else -> {
                if (state.tracks.isNotEmpty()) {
                    item(key = "g_tracks") { SettingsGroupHeader(stringResource(R.string.settings_blocked_songs, state.tracks.size)) }
                    state.tracks.forEach { e ->
                        item(key = "t:${e.id}:${e.name}:${e.addedAt}") {
                            SettingsRow(
                                e.name.ifEmpty { e.id }, Modifier.animateItem(), icon = R.drawable.ic_music_note,
                                subtitle = e.artists.ifEmpty { e.artist0 },
                                trailing = { ShadeIconButton(R.drawable.ic_close, unblockCd, { removed(e, false) }, tint = x.text2) },
                            )
                        }
                    }
                }
                if (state.artists.isNotEmpty()) {
                    item(key = "g_artists") { SettingsGroupHeader(stringResource(R.string.settings_blocked_artists, state.artists.size)) }
                    state.artists.forEach { e ->
                        item(key = "a:${e.id}:${e.name}:${e.addedAt}") {
                            SettingsRow(
                                e.name.ifEmpty { e.id }, Modifier.animateItem(), icon = R.drawable.ic_artist,
                                trailing = { ShadeIconButton(R.drawable.ic_close, unblockCd, { removed(e, true) }, tint = x.text2) },
                            )
                        }
                    }
                }
                item(key = "note") { SettingsNote(stringResource(R.string.settings_blocked_note)) }
            }
        }
    }
}
