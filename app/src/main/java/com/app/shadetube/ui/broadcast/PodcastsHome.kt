package com.app.shadetube.ui.broadcast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.app.shadetube.ui.nav.ScrollToTopEffect
import com.app.shadetube.ui.nav.Tab
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.podcasts.DirectoryEntry
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.CoverStripSkeleton
import com.app.shadetube.ui.components.CoverTile
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.ErrorState
import com.app.shadetube.ui.components.SearchField
import com.app.shadetube.ui.components.SectionHeader
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTheme

/** Where a directory entry opens: its feed when the directory gave one, else its id (the feed is looked up). */
internal fun DirectoryEntry.showKey(): String = feedUrl.ifEmpty { appleId }

/** Podcasts page (SCREENS 17). Pull to refresh reads every subscription again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PodcastsHome(contentPadding: PaddingValues) {
    val nav = LocalNavigator.current
    val vm: PodcastsHomeViewModel = viewModel()
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val charts by vm.charts.collectAsStateWithLifecycle()
    val newRows by vm.newEpisodeRows.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val add by vm.add.collectAsStateWithLifecycle()
    val downloads by com.app.shadetube.podcasts.Podcasts.downloads.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val newTitle = stringResource(R.string.broadcast_podcast_new_episodes)
    val x = ShadeTheme.extras

    PodcastEventMessages()

    val listState = rememberLazyListState()
    ScrollToTopEffect(Tab.Broadcast, listState)
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        EpisodeInteractions(vm, showShowLink = true) { onLongClick, onDownload ->
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            ) {
                // Episode downloads waiting for a connection / Wi-Fi (Settings › Downloads › Wi-Fi only).
                val wait = downloads.wait
                if (wait != null && (downloads.waiting.isNotEmpty() || downloads.active != null)) {
                    item("dl-wait") {
                        com.app.shadetube.ui.components.OfflineBanner(
                            text = stringResource(
                                if (wait == com.app.shadetube.podcasts.PodcastDownloadWait.WifiOnly) R.string.broadcast_podcast_wait_wifi
                                else R.string.broadcast_podcast_wait_offline,
                            ),
                        )
                    }
                }
                item("search") {
                    SearchField(
                        query = query,
                        onQueryChange = vm::setQuery,
                        hint = stringResource(R.string.broadcast_podcast_search_hint),
                        onSearch = vm::submit,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }

                val r = results
                if (r != null) {
                    when (r) {
                        Load.Loading -> skeletonItems("res", 6) { EpisodeRowSkeleton(showCover = true) }
                        is Load.Failed -> item("res-err") { ErrorState(onRetry = { vm.submit(query) }) }
                        is Load.Ready -> if (r.value.isEmpty()) {
                            item("res-empty") {
                                EmptyState(
                                    R.drawable.ic_search, stringResource(R.string.broadcast_podcast_no_results_title),
                                    stringResource(R.string.broadcast_podcast_no_results_body),
                                )
                            }
                        } else {
                            items(r.value, key = { "res:" + it.appleId + it.feedUrl }) { e ->
                                DirectoryRow(e, onClick = { nav.navigate(Screen.PodcastShow(e.showKey())) })
                            }
                        }
                    }
                    return@LazyColumn
                }

                // Subscriptions: 3 columns with new-episode badges.
                item("subs-h") {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(stringResource(R.string.podcast_subscriptions))
                }
                if (subs.isEmpty()) {
                    item("subs-empty") {
                        Text(
                            stringResource(R.string.broadcast_podcast_subscriptions_empty),
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(ShadeRadius.card)
                                .background(x.surface2).padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium, color = x.text2,
                        )
                    }
                } else {
                    itemsIndexed(subs.chunked(3), key = { _, row -> "subs:" + row.joinToString("|") { it.feedUrl } }) { _, row ->
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                            val tile = min((maxWidth - 24.dp) / 3, 140.dp)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                for (s in row) SubscriptionTile(s, onClick = { nav.navigate(Screen.PodcastShow(s.feedUrl)) }, size = tile)
                            }
                        }
                    }
                }
                item("rss") {
                    Spacer(Modifier.height(4.dp))
                    LinkRow(R.drawable.ic_rss, stringResource(R.string.podcast_add_rss), { showAdd = true })
                }

                // The country's charts.
                item("charts-h") {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(stringResource(R.string.podcast_popular_in_country))
                }
                item("charts") {
                    when (val c = charts) {
                        Load.Loading -> CoverStripSkeleton()
                        is Load.Failed -> Row(Modifier.padding(horizontal = 8.dp)) {
                            ShadeTextButton(stringResource(R.string.action_retry), vm::loadCharts)
                        }
                        is Load.Ready -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(c.value, key = { "chart:" + it.appleId + it.feedUrl }) { e ->
                                CoverTile(
                                    title = e.title, subtitle = e.author, artworkUrl = e.image.ifEmpty { null },
                                    onClick = { nav.navigate(Screen.PodcastShow(e.showKey())) }, kind = ArtworkKind.Podcast,
                                )
                            }
                        }
                    }
                }

                // New episodes of the subscriptions.
                if (subs.isNotEmpty()) {
                    item("new-h") {
                        Spacer(Modifier.height(20.dp))
                        SectionHeader(newTitle)
                    }
                    when (val n = newRows) {
                        Load.Loading -> skeletonItems("new", 4) { EpisodeRowSkeleton(showCover = true) }
                        is Load.Failed -> item("new-err") { ErrorState(onRetry = vm::refresh) }
                        is Load.Ready -> if (n.value.isEmpty()) {
                            item("new-empty") {
                                Text(
                                    stringResource(R.string.broadcast_podcast_new_empty),
                                    Modifier.padding(horizontal = 20.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = x.text2,
                                )
                            }
                        } else {
                            itemsIndexed(n.value, key = { _, u -> "new:" + u.episode.id }) { i, u ->
                                EpisodeRow(
                                    u, showCover = true,
                                    onClick = { vm.play(n.value, i, newTitle) },
                                    onLongClick = { onLongClick(u) },
                                    onDownload = { onDownload(u) },
                                )
                            }
                        }
                    }
                }
                item("end") { Box(Modifier.height(8.dp)) }
            }
        }
    }

    if (showAdd) {
        AddRssDialog(
            adding = add.adding,
            error = add.error,
            onDismiss = { showAdd = false; vm.clearAddError() },
            onAdd = { url ->
                vm.addByUrl(url) { show ->
                    showAdd = false
                    nav.navigate(Screen.PodcastShow(show.feedUrl))
                }
            },
            onEdited = vm::clearAddError,
        )
    }
}
