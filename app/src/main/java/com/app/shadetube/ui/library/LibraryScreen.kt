package com.app.shadetube.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.app.shadetube.ui.components.gridColumns
import com.app.shadetube.ui.nav.ScrollToTopEffect
import com.app.shadetube.ui.nav.Tab
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.library.Card
import com.app.shadetube.library.CardPlaceholder
import com.app.shadetube.library.CardSubtitle
import com.app.shadetube.library.CollectionKind
import com.app.shadetube.library.Destination
import com.app.shadetube.library.LibraryEmpty
import com.app.shadetube.library.LibrarySort
import com.app.shadetube.library.LibraryTab
import com.app.shadetube.library.LibraryView
import com.app.shadetube.stats.SmartList
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.MadeForYouCard
import com.app.shadetube.ui.components.ShadeBottomSheet
import com.app.shadetube.ui.components.ShadeFilterChip
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.SheetHeader
import com.app.shadetube.ui.components.SheetMenuRow
import com.app.shadetube.ui.components.SkeletonBox
import com.app.shadetube.ui.components.SkeletonLine
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.home.BrowseTile
import com.app.shadetube.ui.home.CardCover
import com.app.shadetube.ui.home.CardListRow
import com.app.shadetube.ui.home.NowPlayingState
import com.app.shadetube.ui.home.cardSubtitle
import com.app.shadetube.ui.home.cardTitle
import com.app.shadetube.ui.home.smartListOverline
import com.app.shadetube.ui.home.smartListTint
import com.app.shadetube.ui.home.smartListTitle
import com.app.shadetube.ui.home.stripKey
import com.app.shadetube.ui.home.uiLocale
import com.app.shadetube.ui.nav.BroadcastTab
import com.app.shadetube.ui.nav.ExportRequest
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.nav.TrackMenuContext
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

private enum class LibrarySheet { None, Add, Sort }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(contentPadding: PaddingValues, folderId: String) {
    val vm: LibraryViewModel = viewModel(key = "library:$folderId") { LibraryViewModel(folderId) }
    val nav = LocalNavigator.current
    val view by vm.view.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val grid by vm.grid.collectAsStateWithLifecycle()
    val smart by vm.smartLists.collectAsStateWithLifecycle()
    val synced by vm.syncedIds.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val menu by vm.menu.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(LibrarySheet.None) }
    val historyName = stringResource(R.string.library_tab_history)

    PlaylistOpsHost(vm.ops, nav)

    LibraryContent(
        LibraryUi(view, loading && view?.cards.isNullOrEmpty(), tab, sort, grid, smart, synced, now, folderId.isNotEmpty()),
        LibraryActions(
            onTab = vm::selectTab,
            onToggleGrid = vm::toggleGrid,
            onSort = { sheet = LibrarySheet.Sort },
            onAdd = { sheet = LibrarySheet.Add },
            onBack = nav::back,
            onOpen = { nav.open(it.destination) },
            onLongPress = { c, title -> vm.openMenu(c, title) },
            onSmart = { nav.navigate(Screen.SmartList(it.id)) },
            onDownloads = { nav.navigate(Screen.Downloads) },
            onLocal = { nav.navigate(Screen.LocalFiles) },
            onPodcasts = { nav.navigate(Screen.Broadcast(BroadcastTab.Podcasts)) },
            onPlayHistory = { tracks, i -> vm.playHistory(tracks, i, historyName) },
            onTrackMore = { nav.showTrackMenu(listOf(it), TrackMenuContext()) },
        ),
        contentPadding,
    )

    when (sheet) {
        LibrarySheet.None -> Unit
        LibrarySheet.Add -> ShadeBottomSheet(onDismissRequest = { sheet = LibrarySheet.None }) {
            SheetMenuRow(R.drawable.ic_plus, stringResource(R.string.library_new_playlist), {
                sheet = LibrarySheet.None
                nav.showNewPlaylist()
            })
            SheetMenuRow(R.drawable.ic_import, stringResource(R.string.library_import), {
                sheet = LibrarySheet.None
                nav.navigate(Screen.Import())
            })
            Spacer(Modifier.height(16.dp))
        }
        LibrarySheet.Sort -> ShadeBottomSheet(onDismissRequest = { sheet = LibrarySheet.None }) {
            SheetHeader(stringResource(R.string.library_sort))
            LibrarySort.entries.forEach { s ->
                SheetMenuRow(
                    if (s == LibrarySort.Recent) R.drawable.ic_clock else R.drawable.ic_sort,
                    stringResource(if (s == LibrarySort.Recent) R.string.library_sort_recent else R.string.library_sort_name),
                    {
                        vm.setSort(s)
                        sheet = LibrarySheet.None
                    },
                    trailing = if (s == sort) ({ ShadeIcon(R.drawable.ic_check, null, tint = ShadeTheme.accent.accent) }) else null,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    menu?.let { m ->
        ShadeBottomSheet(onDismissRequest = vm::closeMenu) {
            SheetHeader(m.title)
            m.offline?.let { off ->
                SheetMenuRow(
                    if (m.offlineOn) R.drawable.ic_downloaded else R.drawable.ic_download,
                    stringResource(if (m.offlineOn) R.string.library_offline_remove else R.string.library_offline_make),
                    {
                        vm.closeMenu()
                        vm.ops.toggleOffline(off)
                    },
                )
                SheetMenuRow(R.drawable.ic_export, stringResource(R.string.library_export), {
                    vm.closeMenu()
                    nav.showExport(ExportRequest.Collection(off.kind, if (off.kind == CollectionKind.Liked) off.ruleId else off.id, m.title))
                })
            }
            val id = (m.card.destination as? Destination.Playlist)?.id.orEmpty()
            if (m.canRename) {
                SheetMenuRow(R.drawable.ic_edit, stringResource(R.string.library_rename), {
                    vm.closeMenu()
                    vm.ops.askRename(id, m.title)
                })
            }
            if (m.canDelete) {
                SheetMenuRow(R.drawable.ic_trash, stringResource(R.string.library_delete), {
                    vm.closeMenu()
                    vm.ops.askDelete(id, m.title)
                }, destructive = true)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

data class LibraryUi(
    val view: LibraryView?,
    val loading: Boolean,
    val tab: LibraryTab,
    val sort: LibrarySort,
    val grid: Boolean,
    val smartLists: List<SmartList> = emptyList(),
    val syncedIds: Set<String> = emptySet(),
    val now: NowPlayingState = NowPlayingState(),
    val inFolder: Boolean = false,
)

class LibraryActions(
    val onTab: (LibraryTab) -> Unit = {},
    val onToggleGrid: () -> Unit = {},
    val onSort: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpen: (Card) -> Unit = {},
    val onLongPress: (Card, String) -> Unit = { _, _ -> },
    val onSmart: (SmartList) -> Unit = {},
    val onDownloads: () -> Unit = {},
    val onLocal: () -> Unit = {},
    val onPodcasts: () -> Unit = {},
    val onPlayHistory: (List<Track>, Int) -> Unit = { _, _ -> },
    val onTrackMore: (Track) -> Unit = {},
)

@Composable
fun LibraryContent(ui: LibraryUi, a: LibraryActions, contentPadding: PaddingValues) {
    val x = ShadeTheme.extras
    val view = ui.view
    val folder = view?.folder
    val bottom = contentPadding.calculateBottomPadding() + 24.dp
    Column(Modifier.fillMaxSize().background(x.surface0).statusBarsPadding()) {
        // Header: title (or the folder with its way back) + grid/list 48 + sort 48 + "+" 48.
        Row(
            Modifier.fillMaxWidth().padding(start = if (ui.inFolder) 4.dp else 20.dp, end = 4.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ui.inFolder) {
                ShadeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.common_back_cd), a.onBack, tint = x.text1, size = 48.dp)
            }
            Column(Modifier.weight(1f)) {
                val title = when {
                    folder != null -> folder.folder.name.ifEmpty { stringResource(R.string.home_untitled_folder) }
                    ui.inFolder -> ""
                    else -> stringResource(R.string.nav_library)
                }
                Text(
                    title, style = if (ui.inFolder) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                    color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (folder != null) {
                    Text(
                        pluralStringResource(R.plurals.home_playlists_count, folder.folder.playlistCount, folder.folder.playlistCount)
                            .uppercase(uiLocale()),
                        style = ShadeMono.overline, color = x.text3, maxLines = 1,
                    )
                }
            }
            ShadeIconButton(
                if (ui.grid) R.drawable.ic_list else R.drawable.ic_grid,
                stringResource(if (ui.grid) R.string.library_view_list_cd else R.string.library_view_grid_cd),
                a.onToggleGrid, tint = x.text1, size = 48.dp,
            )
            ShadeIconButton(R.drawable.ic_sort, stringResource(R.string.library_sort), a.onSort, tint = x.text1, size = 48.dp)
            if (!ui.inFolder) {
                ShadeIconButton(R.drawable.ic_plus, stringResource(R.string.library_add_cd), a.onAdd, tint = x.text1, size = 48.dp)
            }
        }
        if (!ui.inFolder) FilterChips(ui.tab, a)
        Spacer(Modifier.height(8.dp))
        when {
            view == null || ui.loading -> LibrarySkeleton()
            view.tab == LibraryTab.History -> HistoryList(view.history, view.empty, ui.now, a, bottom)
            else -> CardsList(view, ui, a, bottom)
        }
    }
}

@Composable
private fun FilterChips(tab: LibraryTab, a: LibraryActions) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tabs = listOf(
            LibraryTab.Playlists to R.string.library_filter_playlists,
            LibraryTab.Albums to R.string.library_filter_albums,
            LibraryTab.Artists to R.string.library_filter_artists,
            LibraryTab.History to R.string.library_tab_history,
        )
        items(tabs, key = { it.first.name }) { (t, label) ->
            ShadeFilterChip(stringResource(label), t == tab, { a.onTab(t) })
        }
        item(key = "downloads") {
            ShadeFilterChip(
                stringResource(R.string.library_filter_downloads), false, a.onDownloads,
                leadingIcon = R.drawable.ic_downloaded, trailingIcon = R.drawable.ic_chevron_right,
            )
        }
        item(key = "local") {
            ShadeFilterChip(
                stringResource(R.string.library_filter_local), false, a.onLocal,
                leadingIcon = R.drawable.ic_folder, trailingIcon = R.drawable.ic_chevron_right,
            )
        }
        item(key = "podcasts") {
            ShadeFilterChip(
                stringResource(R.string.library_filter_podcasts), false, a.onPodcasts,
                leadingIcon = R.drawable.ic_podcast, trailingIcon = R.drawable.ic_chevron_right,
            )
        }
    }
}

@Composable
private fun CardsList(view: LibraryView, ui: LibraryUi, a: LibraryActions, bottom: Dp) {
    val liked = view.cards.firstOrNull { it.destination == Destination.Liked }
    val cards = view.cards.filter { it.destination != Destination.Liked }
    val showSmart = view.showSmartLists && ui.smartLists.isNotEmpty()
    val listState = rememberLazyListState()
    ScrollToTopEffect(Tab.Library, listState)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Phones 2 columns; medium windows 3–4, expanded up to 6 (~164dp tiles).
        val columns = gridColumns(maxWidth)
        val tile = (maxWidth - 40.dp - 12.dp * (columns - 1)) / columns
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottom)) {
            if (liked != null) {
                item(key = "liked") { LikedRow(liked, ui.syncedIds, a) }
            }
            if (showSmart) {
                item(key = "smart-h") {
                    Text(
                        stringResource(R.string.home_made_for_you), Modifier.padding(start = 20.dp, top = 16.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.titleMedium, color = ShadeTheme.extras.text1,
                    )
                }
                item(key = "smart") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ui.smartLists, key = { it.id }) { l ->
                            MadeForYouCard(smartListOverline(l), smartListTitle(l), smartListTint(l), { a.onSmart(l) })
                        }
                    }
                }
                item(key = "smart-gap") { Spacer(Modifier.height(16.dp)) }
            }
            if (cards.isEmpty()) {
                val empty = view.empty
                if (empty != null || liked == null) item(key = "empty") { LibraryEmptyState(empty) }
            } else if (ui.grid) {
                val rows = cards.chunked(columns)
                items(rows.size, key = { r -> "g$r:" + stripKey(rows[r].first(), r) }) { r ->
                    val row = rows[r]
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { c ->
                            val title = cardTitle(c)
                            BrowseTile(c, { a.onOpen(c) }, size = tile, onLongClick = { a.onLongPress(c, title) })
                        }
                    }
                }
            } else {
                items(cards.size, key = { stripKey(cards[it], it) }) { i ->
                    val c = cards[i]
                    val title = cardTitle(c)
                    CardListRow(
                        c, { a.onOpen(c) }, onLongClick = { a.onLongPress(c, title) },
                        trailing = if (offlineId(c) in ui.syncedIds) ({ OfflineMark() }) else null,
                    )
                }
            }
        }
    }
}

private fun offlineId(c: Card): String = when (val d = c.destination) {
    is Destination.Playlist -> d.id
    is Destination.Album -> d.id
    else -> ""
}

@Composable
private fun OfflineMark() {
    ShadeIcon(
        R.drawable.ic_downloaded, stringResource(R.string.track_downloaded_cd), Modifier.padding(start = 8.dp),
        tint = ShadeTheme.accent.accent, size = 16.dp,
    )
}

/** The pinned "Beğenilen Şarkılar" row (SCREENS 5): 72 high, gradient cover with a heart, "%d şarkı". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LikedRow(card: Card, synced: Set<String>, a: LibraryActions) {
    val x = ShadeTheme.extras
    val title = cardTitle(card)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .combinedClickable(role = Role.Button, onLongClick = { a.onLongPress(card, title) }, onClick = { a.onOpen(card) })
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardCover(card, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            cardSubtitle(card.subtitle)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 1)
            }
        }
        if (synced.any { it == com.app.shadetube.downloads.SyncRules.SPOTIFY_LIKED_ID || it == com.app.shadetube.downloads.SyncRules.LOCAL_LIKED_ID }) {
            OfflineMark()
        }
    }
}

@Composable
private fun LibraryEmptyState(empty: LibraryEmpty?) {
    val (title, body) = when (empty) {
        LibraryEmpty.NoAlbums -> R.string.library_empty_albums_title to R.string.library_empty_albums_body
        LibraryEmpty.NoArtists -> R.string.library_empty_artists_title to R.string.library_empty_artists_body
        LibraryEmpty.NoHistory -> R.string.library_empty_history_title to R.string.library_empty_history_body
        LibraryEmpty.EmptyFolder -> R.string.library_empty_folder_title to R.string.library_empty_folder_body
        null -> R.string.library_empty_playlists_title to R.string.library_empty_playlists_body
    }
    val icon = when (empty) {
        LibraryEmpty.NoAlbums -> R.drawable.ic_album
        LibraryEmpty.NoArtists -> R.drawable.ic_artist
        LibraryEmpty.NoHistory -> R.drawable.ic_history
        LibraryEmpty.EmptyFolder -> R.drawable.ic_folder
        null -> R.drawable.ic_playlist
    }
    EmptyState(icon, stringResource(title), stringResource(body), Modifier.padding(top = 24.dp))
}

@Composable
private fun HistoryList(tracks: List<Track>, empty: LibraryEmpty?, now: NowPlayingState, a: LibraryActions, bottom: Dp) {
    if (tracks.isEmpty()) {
        LibraryEmptyState(empty ?: LibraryEmpty.NoHistory)
        return
    }
    val listState = rememberLazyListState()
    ScrollToTopEffect(Tab.Library, listState)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottom)) {
        itemsIndexed(tracks, key = { i, t -> "h$i:${t.id}" }) { i, t ->
            TrackRow(
                t,
                isCurrent = t.id.isNotEmpty() && t.id == now.trackId && now.contextUri == "history",
                isPlaying = now.playing,
                onClick = { a.onPlayHistory(tracks, i) },
                onMore = { a.onTrackMore(t) },
                onLongClick = { a.onTrackMore(t) },
            )
        }
    }
}

@Composable
private fun LibrarySkeleton() {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        repeat(7) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBox(Modifier.width(56.dp).height(56.dp), com.app.shadetube.ui.theme.ShadeRadius.cover)
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonLine(160.dp)
                    SkeletonLine(90.dp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ previews

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun LibraryPreview() {
    val cards = listOf(
        Card("", CardSubtitle.Songs(312), destination = Destination.Liked, placeholder = CardPlaceholder.Playlist),
        Card("Kış", CardSubtitle.Playlists(3), destination = Destination.Library("f1"), placeholder = CardPlaceholder.Folder, isFolder = true),
    ) + List(6) { i ->
        Card("Gece Sürüşü $i", CardSubtitle.Songs(42 + i), destination = Destination.Playlist("p$i"), placeholder = CardPlaceholder.Playlist)
    }
    val view = LibraryView(null, LibraryTab.Playlists, false, cards, emptyList(), null, true)
    ShadeTubeThemeOf {
        LibraryContent(LibraryUi(view, false, LibraryTab.Playlists, LibrarySort.Recent, grid = false), LibraryActions(), PaddingValues())
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun LibraryGridPreview() {
    val cards = List(6) { i ->
        Card("Albüm $i", CardSubtitle.ArtistYear("Mira Tan", "2024"), destination = Destination.Album("a$i"))
    }
    val view = LibraryView(null, LibraryTab.Albums, false, cards, listOf(Track(name = "x", artists = listOf(ArtistRef("", "y")))), null, true)
    ShadeTubeThemeOf {
        LibraryContent(LibraryUi(view, false, LibraryTab.Albums, LibrarySort.Name, grid = true), LibraryActions(), PaddingValues())
    }
}
