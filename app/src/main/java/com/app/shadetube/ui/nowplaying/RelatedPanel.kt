package com.app.shadetube.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isLocalId
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.library.Destination
import com.app.shadetube.library.radioSeed
import com.app.shadetube.playback.PlayContext
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.SecondaryButton
import com.app.shadetube.ui.components.TrackListSkeleton
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.nav.LocalNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

sealed interface RelatedUi {
    data object Loading : RelatedUi
    /** Nothing related known (local file, episode, station, no artist id). */
    data class Empty(val canRadio: Boolean) : RelatedUi
    data class Loaded(val artistId: String, val artistName: String, val tracks: List<Track>, val canRadio: Boolean) : RelatedUi
}

/** İlgili: the current artist's popular songs ([ArtistRepository.load]) and the song's radio. */
class RelatedViewModel : ViewModel() {
    private val _state = MutableStateFlow<RelatedUi>(RelatedUi.Loading)
    val state: StateFlow<RelatedUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            AppGraph.player.current.distinctUntilChangedBy { it?.artists?.firstOrNull()?.id to (it?.id ?: "") }.collectLatest { t ->
                val canRadio = t != null && radioSeed(t.id).isNotEmpty()
                val artist = t?.artists?.firstOrNull { it.id.isNotEmpty() }
                if (t == null || artist == null || isLocalId(t.id) || isPodcastId(t.id) || InternetRadio.isStation(t.id) || isLocalId(artist.id)) {
                    _state.value = RelatedUi.Empty(canRadio)
                    return@collectLatest
                }
                val prev = _state.value
                if (prev is RelatedUi.Loaded && prev.artistId == artist.id) {
                    _state.value = prev.copy(canRadio = canRadio)
                    return@collectLatest
                }
                _state.value = RelatedUi.Loading
                val page = runCatching { AppGraph.artists.load(artist.id) }.getOrNull()
                val tracks = page?.topTracks.orEmpty().filter { it.id != t.id }.take(10)
                _state.value = if (tracks.isEmpty()) RelatedUi.Empty(canRadio)
                else RelatedUi.Loaded(artist.id, page?.artist?.name?.ifEmpty { null } ?: artist.name, tracks, canRadio)
            }
        }
    }
}

@Composable
internal fun RelatedPanel(nestedScroll: NestedScrollConnection, bottomPadding: Dp, onClose: () -> Unit) {
    val vm: RelatedViewModel = viewModel()
    val nav = LocalNavigator.current
    val player = AppGraph.player
    val state by vm.state.collectAsStateWithLifecycle()
    val current by player.current.collectAsStateWithLifecycle()
    val startRadio = {
        current?.let { AppGraph.radio.startRadio(it.id, it.name) }
        Unit
    }
    LazyColumn(Modifier.fillMaxSize().nestedScroll(nestedScroll), contentPadding = PaddingValues(bottom = bottomPadding)) {
        when (val s = state) {
            RelatedUi.Loading -> item("sk") { TrackListSkeleton(rows = 5) }
            is RelatedUi.Empty -> item("empty") {
                EmptyState(
                    R.drawable.ic_sparkle, stringResource(R.string.np_related_empty_title), stringResource(R.string.np_related_empty_body),
                    actionLabel = if (s.canRadio) stringResource(R.string.menu_start_radio) else null,
                    onAction = if (s.canRadio) startRadio else null,
                    actionIcon = R.drawable.ic_radio,
                )
            }
            is RelatedUi.Loaded -> {
                item("actions") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (s.canRadio) PrimaryButton(stringResource(R.string.menu_start_radio), startRadio, Modifier.weight(1f), icon = R.drawable.ic_radio)
                        SecondaryButton(
                            stringResource(R.string.menu_go_to_artist),
                            { onClose(); nav.open(Destination.Artist(s.artistId)) },
                            Modifier.weight(1f), icon = R.drawable.ic_artist,
                        )
                    }
                }
                item("h") { SectionLabel(stringResource(R.string.np_related_popular, s.artistName)) }
                itemsIndexed(s.tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
                    TrackRow(
                        t,
                        isCurrent = t.id == current?.id,
                        onClick = { player.playContext(s.tracks, i, PlayContext("artist:${s.artistId}", s.artistName)) },
                        onMore = { nav.showTrackMenu(listOf(t)) },
                    )
                }
            }
        }
    }
}
