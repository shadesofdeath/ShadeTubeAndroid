package com.app.shadetube.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.library.totalDuration
import com.app.shadetube.stats.SmartList
import com.app.shadetube.stats.SmartLists
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.ShadeTopBar
import com.app.shadetube.ui.components.TrackListSkeleton
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.home.NowPlayingState
import com.app.shadetube.ui.home.smartListOverline
import com.app.shadetube.ui.home.smartListTint
import com.app.shadetube.ui.home.smartListTitle
import com.app.shadetube.ui.home.totalDurationText
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.TrackMenuContext
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

@Composable
fun SmartListScreen(contentPadding: PaddingValues, id: String) {
    val vm: SmartListViewModel = viewModel(key = "smart:$id") { SmartListViewModel(id) }
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val localRes = LocalResources.current
    val list by vm.list.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val title = list?.let { smartListTitle(it) }.orEmpty()
    SmartListContent(
        list = list,
        loading = !ready && list == null,
        now = now,
        playingThis = now.isPlayingContext(vm.contextUri),
        downloaded = downloaded,
        a = SmartListActions(
            onBack = nav::back,
            onPlay = { vm.play(-1, false, title) },
            onShuffle = { vm.play(-1, true, title) },
            onRow = { vm.play(it, false, title) },
            onRowMore = { nav.showTrackMenu(listOf(it), TrackMenuContext()) },
            onQueue = { if (vm.enqueueAll()) nav.message(localRes.getString(R.string.snackbar_added_to_queue)) },
            onSave = { list?.let { nav.showNewPlaylist(it.tracks) } },
        ),
        contentPadding = contentPadding,
    )
}

class SmartListActions(
    val onBack: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onRow: (Int) -> Unit = {},
    val onRowMore: (Track) -> Unit = {},
    val onQueue: () -> Unit = {},
    val onSave: () -> Unit = {},
)

@Composable
fun SmartListContent(
    list: SmartList?,
    loading: Boolean,
    now: NowPlayingState,
    playingThis: Boolean,
    downloaded: Set<String>,
    a: SmartListActions,
    contentPadding: PaddingValues,
) {
    val x = ShadeTheme.extras
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val tint = list?.let { smartListTint(it) } ?: ShadeTheme.accent.accent
    Box(Modifier.fillMaxSize().background(x.surface0)) {
        AccentWash(Modifier.fillMaxWidth().height(420.dp), color = tint)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ShadeTopBar(onNavigate = a.onBack, title = if (scrolled && list != null) smartListTitle(list) else null)
            when {
                loading -> Column(Modifier.padding(top = 8.dp)) {
                    CollectionHeroSkeleton()
                    Spacer(Modifier.height(24.dp))
                    TrackListSkeleton()
                }
                list == null -> EmptyState(
                    R.drawable.ic_sparkle, stringResource(R.string.collection_smart_not_found_title),
                    stringResource(R.string.collection_smart_not_found_body), Modifier.padding(top = 48.dp),
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
                ) {
                    item(key = "hero") {
                        val count = pluralStringResource(R.plurals.home_songs_count, list.tracks.size, list.tracks.size)
                        val ms = list.tracks.sumOf { it.durationMs.toLong() }
                        val meta = listOfNotNull(count, if (ms > 0) totalDurationText(totalDuration(ms)) else null).joinToString(" · ")
                        val why = if (list.streams > 0) {
                            pluralStringResource(R.plurals.collection_smart_streams, list.streams, list.streams)
                        } else {
                            null
                        }
                        CollectionHero(
                            cover = { SmartCover(tint) },
                            title = smartListTitle(list),
                            overline = smartListOverline(list),
                            owner = { OwnerLine("ShadeTube", stringResource(R.string.collection_smart_badge)) },
                            meta = meta,
                            description = why,
                        )
                    }
                    item(key = "buttons") {
                        PlayShuffleRow(playingThis, list.tracks.isNotEmpty(), a.onPlay, a.onShuffle, Modifier.padding(top = 20.dp))
                    }
                    item(key = "chips") {
                        ChipRow(
                            listOf(
                                ChipSpec(stringResource(R.string.collection_add_to_queue), R.drawable.ic_add_to_queue, false, a.onQueue),
                                ChipSpec(stringResource(R.string.collection_save_as_playlist), R.drawable.ic_playlist, false, a.onSave),
                            ),
                            Modifier.padding(top = 12.dp),
                        )
                    }
                    item(key = "gap") { Spacer(Modifier.height(10.dp)) }
                    itemsIndexed(list.tracks, key = { i, t -> "s$i:${t.id}" }) { i, t ->
                        val current = t.id.isNotEmpty() && t.id == now.trackId
                        TrackRow(
                            t,
                            isCurrent = current,
                            isPlaying = current && now.playing,
                            downloaded = t.id in downloaded,
                            onClick = { a.onRow(i) },
                            onLongClick = { a.onRowMore(t) },
                            onMore = { a.onRowMore(t) },
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun SmartListPreview() {
    val tracks = List(8) { i ->
        Track(id = "t$i", name = "Şarkı ${i + 1}", artists = listOf(ArtistRef("a", "Mira Tan")), durationMs = 210_000)
    }
    ShadeTubeThemeOf {
        SmartListContent(
            SmartList("month", SmartLists.Kind.Month, tracks = tracks, streams = 64),
            loading = false, now = NowPlayingState(), playingThis = false, downloaded = emptySet(),
            a = SmartListActions(), contentPadding = PaddingValues(),
        )
    }
}
