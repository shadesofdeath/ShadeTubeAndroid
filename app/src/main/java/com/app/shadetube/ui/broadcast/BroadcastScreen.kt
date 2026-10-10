package com.app.shadetube.ui.broadcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.internetradio.RadioModel
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.ErrorState
import com.app.shadetube.ui.components.SegmentedPill
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.components.ShadeTopBar
import com.app.shadetube.ui.components.TrackRowSkeleton
import com.app.shadetube.ui.nav.BroadcastTab
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.RadioListKind
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

/** Yayın tab (SCREENS 16 / 17): title, Radyo | Podcast segment, then the radio or podcasts page. */
@Composable
fun BroadcastScreen(contentPadding: PaddingValues, tab: BroadcastTab) {
    // Reset when the shell opens the tab on another segment; kept across recreation otherwise.
    var selected by rememberSaveable(tab) { mutableIntStateOf(tab.ordinal) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BroadcastHeader(selected) { selected = it }
        when (BroadcastTab.entries[selected]) {
            BroadcastTab.Radio -> RadioHome(contentPadding)
            BroadcastTab.Podcasts -> PodcastsHome(contentPadding)
        }
    }
}

@Composable
private fun BroadcastHeader(selected: Int, onSelect: (Int) -> Unit) {
    val x = ShadeTheme.extras
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.nav_broadcast), style = MaterialTheme.typography.headlineLarge, color = x.text1,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        SegmentedPill(
            options = listOf(stringResource(R.string.radio_title), stringResource(R.string.broadcast_tab_podcasts)),
            selectedIndex = selected,
            onSelect = onSelect,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** A full station list with paging (top / genre / country / search) or the favorites / recently played lists. */
@Composable
fun RadioListScreen(contentPadding: PaddingValues, kind: RadioListKind, value: String, title: String) {
    val nav = LocalNavigator.current
    val vm: RadioListViewModel = viewModel(key = "radio:${kind.name}:$value") { RadioListViewModel(kind, value) }
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val heading = title.ifEmpty { radioListTitle(kind, value) }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, state.more, state.stations.size) {
        if (nearEnd && state.more && !state.loading && state.error == null) vm.loadMore()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShadeTopBar(onNavigate = { nav.back() }, title = heading)
        when {
            state.stations.isEmpty() && state.loading -> Column { repeat(8) { TrackRowSkeleton() } }
            state.stations.isEmpty() && state.error != null -> ErrorState(onRetry = vm::loadMore, modifier = Modifier.padding(contentPadding))
            state.stations.isEmpty() -> RadioListEmpty(kind)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            ) {
                itemsIndexed(state.stations, key = { _, s -> s.uuid }) { i, s ->
                    StationRow(
                        station = s,
                        favorite = s.uuid in favorites,
                        now = now,
                        onClick = { vm.play(i, heading) },
                        onToggleFavorite = { vm.toggleFavorite(s) },
                    )
                }
                if (state.loading) {
                    item("loading") { Column { repeat(3) { TrackRowSkeleton() } } }
                } else if (state.error != null) {
                    item("retry") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                            ShadeTextButton(stringResource(R.string.action_retry), vm::loadMore)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioListEmpty(kind: RadioListKind) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        when (kind) {
            RadioListKind.Favorites -> EmptyState(
                R.drawable.ic_heart, stringResource(R.string.broadcast_radio_favorites_empty_title),
                stringResource(R.string.broadcast_radio_favorites_empty_body),
            )
            RadioListKind.Recent -> EmptyState(
                R.drawable.ic_history, stringResource(R.string.broadcast_radio_recent_empty_title),
                stringResource(R.string.broadcast_radio_recent_empty_body),
            )
            else -> EmptyState(
                R.drawable.ic_radio, stringResource(R.string.broadcast_radio_no_results_title),
                stringResource(R.string.broadcast_radio_no_results_body),
            )
        }
    }
}

@Composable
internal fun radioListTitle(kind: RadioListKind, value: String): String = when (kind) {
    RadioListKind.Top -> stringResource(R.string.broadcast_radio_world_popular)
    RadioListKind.Favorites -> stringResource(R.string.radio_favorites)
    RadioListKind.Recent -> stringResource(R.string.radio_recent)
    RadioListKind.Tag -> RadioModel.genreLabel(value, InternetRadio.labels)
    RadioListKind.Country -> InternetRadio.labels.countryName(value, value)
    RadioListKind.Search -> stringResource(R.string.broadcast_radio_search_title, value)
}

/** A podcast show page (feed URL or directory id). */
@Composable
fun PodcastShowScreen(contentPadding: PaddingValues, feedOrAppleId: String) {
    PodcastShowContent(contentPadding, feedOrAppleId)
}

/** An episode page: notes, continue / from the start, played, download. */
@Composable
fun PodcastEpisodeScreen(contentPadding: PaddingValues, episodeId: String) {
    PodcastEpisodeContent(contentPadding, episodeId)
}

@Preview(backgroundColor = 0xFF0B0C10, showBackground = true, widthDp = 412)
@Composable
private fun BroadcastHeaderPreview() {
    ShadeTubeThemeOf { BroadcastHeader(0) {} }
}
