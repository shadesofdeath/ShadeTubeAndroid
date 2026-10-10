package com.app.shadetube.ui.collection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.library.AlbumType
import com.app.shadetube.library.CatalogSource
import com.app.shadetube.library.CollectionEmpty
import com.app.shadetube.library.CollectionHeader
import com.app.shadetube.library.CollectionKind
import com.app.shadetube.library.CollectionMenu
import com.app.shadetube.library.CollectionState
import com.app.shadetube.library.CollectionStatus
import com.app.shadetube.library.DeleteKind
import com.app.shadetube.library.HeaderLabel
import com.app.shadetube.library.OwnerText
import com.app.shadetube.library.SaveAlbumResult
import com.app.shadetube.library.TrackListView
import com.app.shadetube.library.TrackSort
import com.app.shadetube.library.totalDuration
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.ErrorState
import com.app.shadetube.ui.components.SearchField
import com.app.shadetube.ui.components.ShadeBottomSheet
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.ShadeTopBar
import com.app.shadetube.ui.components.SheetDivider
import com.app.shadetube.ui.components.SheetHeader
import com.app.shadetube.ui.components.SheetMenuRow
import com.app.shadetube.ui.components.TrackListSkeleton
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.components.TrackRowSkeleton
import com.app.shadetube.ui.home.LikedCover
import com.app.shadetube.ui.home.NowPlayingState
import com.app.shadetube.ui.home.albumTypeText
import com.app.shadetube.ui.home.totalDurationText
import com.app.shadetube.ui.library.PlaylistOpsHost
import com.app.shadetube.ui.nav.ExportRequest
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.nav.Tab
import com.app.shadetube.ui.nav.TrackMenuContext
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

private enum class CollectionSheet { None, Menu, Sort }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(contentPadding: PaddingValues, kind: CollectionKind, id: String, autoplay: Boolean) {
    val vm: CollectionViewModel = viewModel(key = "collection:$kind:$id") { CollectionViewModel(kind, id, autoplay) }
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val localRes = LocalResources.current
    val state by vm.state.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(CollectionSheet.None) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    PlaylistOpsHost(vm.ops, nav, onDeleted = { if (it == vm.id) nav.back() })

    val noRecs = stringResource(R.string.collection_no_recommendations)
    LaunchedEffect(state.noRecommendationsNotice) {
        if (state.noRecommendationsNotice) {
            nav.message(noRecs)
            vm.consumeNotice()
        }
    }
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        vm.setFilter("")
    }

    val actions = CollectionActions(
        onBack = nav::back,
        onSearchToggle = {
            searchOpen = !searchOpen
            if (!searchOpen) vm.setFilter("")
        },
        onFilter = vm::setFilter,
        onMore = { sheet = CollectionSheet.Menu },
        onSort = { sheet = CollectionSheet.Sort },
        onPlay = { vm.play(false) },
        onShuffle = { vm.play(true) },
        onEnhance = vm::toggleEnhanced,
        onOffline = vm::toggleOffline,
        onSave = {
            when (vm.toggleSavedAlbum()) {
                SaveAlbumResult.OnlySpotifyAlbums -> nav.message(localRes.getString(R.string.collection_only_spotify_albums))
                SaveAlbumResult.Saved -> nav.message(localRes.getString(R.string.collection_album_saved))
                SaveAlbumResult.Removed -> nav.message(localRes.getString(R.string.collection_album_removed))
                SaveAlbumResult.Unchanged -> Unit
            }
        },
        onPauseSync = vm::pauseSync,
        onResumeSync = vm::resumeSync,
        onRow = vm::playRow,
        onRowMore = { t ->
            nav.showTrackMenu(
                listOf(t),
                TrackMenuContext(playlistId = state.removablePlaylistId, recommendKey = if (vm.canEnhance) vm.enhanceKey else ""),
            )
        },
        onAddRecommended = vm::addRecommended,
        onHideRecommended = vm::hideRecommended,
        onOwner = { artistId -> nav.navigate(Screen.Artist(artistId)) },
        onLoadMore = vm::loadMore,
        onRetry = vm::retry,
        onAddSongs = { nav.switchTab(Tab.Search) },
    )
    CollectionContent(
        CollectionUi(
            kind = kind,
            id = vm.id,
            state = state,
            now = now,
            contextUri = vm.controller.contextUri,
            downloaded = downloaded,
            blocked = blocked,
            sync = sync,
            canEnhance = vm.canEnhance,
            searchOpen = searchOpen,
        ),
        actions,
        contentPadding,
    )

    when (sheet) {
        CollectionSheet.None -> Unit
        CollectionSheet.Sort -> ShadeBottomSheet(onDismissRequest = { sheet = CollectionSheet.None }) {
            SheetHeader(stringResource(R.string.library_sort))
            TrackListView.sortOptions(kind).forEach { o ->
                val selected = state.sort == o.key && (o.key == TrackSort.Custom || state.descending == o.descending)
                SheetMenuRow(
                    sortIcon(o.key), sortLabel(o.key, o.descending),
                    {
                        vm.setSort(o.key, o.descending)
                        sheet = CollectionSheet.None
                    },
                    trailing = if (selected) ({ ShadeIcon(R.drawable.ic_check, null, tint = ShadeTheme.accent.accent) }) else null,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        CollectionSheet.Menu -> {
            val menu = remember(state.header, state.editable, state.owned) { vm.menu() }
            val title = state.header?.title.orEmpty()
            ShadeBottomSheet(onDismissRequest = { sheet = CollectionSheet.None }) {
                SheetHeader(title)
                CollectionMenuRows(
                    menu,
                    close = { sheet = CollectionSheet.None },
                    onRadio = { vm.startRadio(menu.radioSeed, title) },
                    onQueue = {
                        if (vm.enqueueAll()) nav.message(localRes.getString(R.string.snackbar_added_to_queue))
                    },
                    onArtist = { nav.navigate(Screen.Artist(menu.artistId)) },
                    onCopy = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText(title, menu.copyLink))
                        nav.message(localRes.getString(R.string.snackbar_link_copied))
                    },
                    onExport = { nav.showExport(ExportRequest.Collection(kind, menu.offline.id, title)) },
                    onRename = { vm.ops.askRename(vm.id, title) },
                    onDelete = { vm.ops.askDelete(vm.id, title) },
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CollectionMenuRows(
    menu: CollectionMenu,
    close: () -> Unit,
    onRadio: () -> Unit,
    onQueue: () -> Unit,
    onArtist: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    fun run(f: () -> Unit): () -> Unit = {
        close()
        f()
    }
    if (menu.radioSeed.isNotEmpty()) SheetMenuRow(R.drawable.ic_radio, stringResource(R.string.artist_start_radio), run(onRadio))
    SheetMenuRow(R.drawable.ic_add_to_queue, stringResource(R.string.collection_add_to_queue), run(onQueue))
    if (menu.artistId.isNotEmpty()) SheetMenuRow(R.drawable.ic_artist, stringResource(R.string.collection_go_to_artist), run(onArtist))
    if (menu.copyLink.isNotEmpty()) {
        SheetMenuRow(
            R.drawable.ic_link,
            stringResource(if (menu.copyLinkIsSpotify) R.string.collection_copy_spotify_link else R.string.collection_copy_mb_link),
            run(onCopy),
        )
    }
    SheetMenuRow(R.drawable.ic_export, stringResource(R.string.library_export), run(onExport))
    if (menu.canRename || menu.deleteKind != null) SheetDivider()
    if (menu.canRename) SheetMenuRow(R.drawable.ic_edit, stringResource(R.string.library_rename), run(onRename))
    menu.deleteKind?.let { k ->
        SheetMenuRow(
            R.drawable.ic_trash,
            stringResource(if (k == DeleteKind.RemoveFromLibrary) R.string.collection_remove_from_library else R.string.library_delete),
            run(onDelete), destructive = true,
        )
    }
}

private fun sortIcon(k: TrackSort) = when (k) {
    TrackSort.Custom -> R.drawable.ic_drag_handle
    TrackSort.Added -> R.drawable.ic_calendar
    TrackSort.Title -> R.drawable.ic_music_note
    TrackSort.Artist -> R.drawable.ic_artist
    TrackSort.Album -> R.drawable.ic_album
    TrackSort.Duration -> R.drawable.ic_clock
}

@Composable
private fun sortLabel(k: TrackSort, descending: Boolean): String {
    val base = stringResource(
        when (k) {
            TrackSort.Custom -> R.string.collection_sort_custom
            TrackSort.Added -> R.string.collection_sort_added
            TrackSort.Title -> R.string.collection_sort_title
            TrackSort.Artist -> R.string.collection_sort_artist
            TrackSort.Album -> R.string.collection_sort_album
            TrackSort.Duration -> R.string.collection_sort_duration
        },
    )
    return if (descending) "$base ↓" else base
}

data class CollectionUi(
    val kind: CollectionKind,
    val id: String,
    val state: CollectionState,
    val now: NowPlayingState = NowPlayingState(),
    val contextUri: String = "",
    val downloaded: Set<String> = emptySet(),
    val blocked: Set<String> = emptySet(),
    val sync: SyncUi = SyncUi(),
    val canEnhance: Boolean = false,
    val searchOpen: Boolean = false,
)

class CollectionActions(
    val onBack: () -> Unit = {},
    val onSearchToggle: () -> Unit = {},
    val onFilter: (String) -> Unit = {},
    val onMore: () -> Unit = {},
    val onSort: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onEnhance: () -> Unit = {},
    val onOffline: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onPauseSync: () -> Unit = {},
    val onResumeSync: () -> Unit = {},
    val onRow: (Int) -> Unit = {},
    val onRowMore: (Track) -> Unit = {},
    val onAddRecommended: (Track) -> Unit = {},
    val onHideRecommended: (Track) -> Unit = {},
    val onOwner: (String) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAddSongs: () -> Unit = {},
)

@Composable
fun CollectionContent(ui: CollectionUi, a: CollectionActions, contentPadding: PaddingValues) {
    val x = ShadeTheme.extras
    val s = ui.state
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    LoadMoreEffect(listState, a.onLoadMore)
    Box(Modifier.fillMaxSize().background(x.surface0)) {
        AccentWash(Modifier.fillMaxWidth().height(420.dp))
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ShadeTopBar(
                onNavigate = a.onBack,
                title = if (scrolled) s.header?.title else null,
                actions = {
                    if (s.status == CollectionStatus.Ready && s.header != null) {
                        ShadeIconButton(
                            R.drawable.ic_search, stringResource(R.string.playlist_search_hint), a.onSearchToggle,
                            tint = if (ui.searchOpen) ShadeTheme.accent.accent else x.text1, size = 48.dp,
                        )
                        ShadeIconButton(R.drawable.ic_more_vertical, stringResource(R.string.common_more_cd), a.onMore, tint = x.text1, size = 48.dp)
                    }
                },
            )
            AnimatedVisibility(ui.searchOpen) {
                SearchField(
                    s.filter, a.onFilter, Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    hint = stringResource(R.string.playlist_search_hint), autoFocus = true,
                )
            }
            when (val st = s.status) {
                CollectionStatus.Loading -> Column(Modifier.padding(top = 8.dp)) {
                    CollectionHeroSkeleton()
                    Spacer(Modifier.height(24.dp))
                    TrackListSkeleton()
                }
                is CollectionStatus.Error -> ErrorState(onRetry = a.onRetry, modifier = Modifier.padding(top = 48.dp))
                CollectionStatus.NotFound -> EmptyState(
                    R.drawable.ic_playlist, stringResource(R.string.collection_not_found_title),
                    stringResource(R.string.collection_not_found_body), Modifier.padding(top = 48.dp),
                )
                CollectionStatus.Ready -> CollectionList(ui, a, listState, contentPadding)
            }
        }
    }
}

@Composable
private fun LoadMoreEffect(listState: LazyListState, onLoadMore: () -> Unit) {
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 12) onLoadMore()
        }
    }
}

@Composable
private fun CollectionList(ui: CollectionUi, a: CollectionActions, listState: LazyListState, contentPadding: PaddingValues) {
    val s = ui.state
    val header = s.header ?: return
    val x = ShadeTheme.extras
    val album = ui.kind == CollectionKind.Album
    val playingThis = ui.now.isPlayingContext(ui.contextUri)
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item(key = "hero") { Hero(ui, header, a) }
        item(key = "buttons") {
            PlayShuffleRow(playingThis, s.displayed.isNotEmpty(), a.onPlay, a.onShuffle, Modifier.padding(top = 20.dp))
        }
        item(key = "chips") {
            val chips = buildList {
                if (ui.canEnhance) add(ChipSpec(stringResource(R.string.action_enhance), R.drawable.ic_sparkle, s.enhanced, a.onEnhance))
                if (album) {
                    add(
                        ChipSpec(
                            stringResource(if (s.savedAlbum) R.string.collection_saved else R.string.collection_save),
                            if (s.savedAlbum) R.drawable.ic_heart_filled else R.drawable.ic_heart, s.savedAlbum, a.onSave,
                        ),
                    )
                }
                add(
                    ChipSpec(
                        stringResource(R.string.action_make_offline),
                        if (ui.sync.synced) R.drawable.ic_downloaded else R.drawable.ic_download, ui.sync.synced, a.onOffline,
                    ),
                )
                add(ChipSpec(stringResource(R.string.library_sort), R.drawable.ic_sort, s.sort != TrackSort.Custom, a.onSort))
                add(ChipSpec(stringResource(R.string.action_more), R.drawable.ic_more_vertical, false, a.onMore))
            }
            ChipRow(chips, Modifier.padding(top = 12.dp))
        }
        if (ui.sync.inProgress) {
            item(key = "sync") { SyncCard(ui.sync, a.onPauseSync, a.onResumeSync, Modifier.padding(top = 12.dp)) }
        }
        item(key = "gap") { Spacer(Modifier.height(10.dp)) }
        val empty = s.empty
        if (s.displayed.isEmpty()) {
            item(key = "empty") {
                when {
                    s.filter.isNotEmpty() -> EmptyState(
                        R.drawable.ic_search, stringResource(R.string.collection_filter_empty, s.filter), null,
                    )
                    empty == CollectionEmpty.NoLikedSongs -> EmptyState(
                        R.drawable.ic_heart, stringResource(R.string.library_empty_liked_title),
                        stringResource(R.string.library_empty_liked_body),
                    )
                    empty == CollectionEmpty.EmptyPlaylist -> EmptyState(
                        R.drawable.ic_playlist, stringResource(R.string.playlist_empty_title),
                        stringResource(R.string.playlist_empty_body),
                        actionLabel = stringResource(R.string.playlist_empty_cta), onAction = a.onAddSongs,
                        actionIcon = R.drawable.ic_search,
                    )
                    s.loadingRows == 0 -> EmptyState(R.drawable.ic_music_note, stringResource(R.string.playlist_empty_title), null)
                    else -> Unit
                }
            }
        }
        itemsIndexed(s.displayed, key = { i, t -> "r$i:" + t.uid.ifEmpty { t.id } }) { i, t ->
            val current = t.id.isNotEmpty() && t.id == ui.now.trackId
            TrackRow(
                t,
                isCurrent = current,
                isPlaying = current && ui.now.playing,
                downloaded = t.id in ui.downloaded,
                blocked = t.id in ui.blocked,
                showArtwork = !album,
                onClick = { a.onRow(i) },
                onLongClick = if (t.recommended) null else ({ a.onRowMore(t) }),
                onMore = { a.onRowMore(t) },
                onAddSuggested = { a.onAddRecommended(t) },
                onHideSuggested = { a.onHideRecommended(t) },
            )
        }
        if (s.loadingRows > 0 && s.filter.isEmpty()) {
            items(s.loadingRows) { TrackRowSkeleton() }
        }
        if (album && s.displayed.isNotEmpty()) {
            val label = header.label as? HeaderLabel.Album
            if (label != null && label.label.isNotEmpty()) {
                item(key = "label") {
                    Text(
                        "℗ " + listOf(label.year, label.label).filter { it.isNotEmpty() }.joinToString(" "),
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = x.text3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(ui: CollectionUi, header: CollectionHeader, a: CollectionActions) {
    val s = ui.state
    val count = pluralStringResource(R.plurals.home_songs_count, header.total, header.total)
    val total = if (s.totalMs > 0) totalDurationText(totalDuration(s.totalMs)) else null
    val badge = when (header.label) {
        HeaderLabel.SpotifyPlaylist, HeaderLabel.SpotifyLiked -> "Spotify"
        HeaderLabel.LocalLiked, is HeaderLabel.LocalPlaylist -> stringResource(R.string.collection_badge_local)
        is HeaderLabel.Album -> if (CatalogSource.isSpotifyId(ui.id)) "Spotify" else "MusicBrainz"
    }
    val albumLabel = header.label as? HeaderLabel.Album
    val meta = when {
        albumLabel != null -> listOfNotNull(albumLabel.year.ifEmpty { null }, count, total).joinToString(" · ")
        else -> listOfNotNull(count, total).joinToString(" · ")
    }
    val ownerName = when (val o = header.owner) {
        is OwnerText.Text -> o.text
        OwnerText.You -> stringResource(R.string.collection_owner_you)
    }
    CollectionHero(
        cover = {
            if (ui.kind == CollectionKind.Liked) LikedCover(148.dp)
            else Artwork(header.images, 148.dp, kind = if (albumLabel != null) com.app.shadetube.ui.components.ArtworkKind.Album else com.app.shadetube.ui.components.ArtworkKind.Playlist)
        },
        title = header.title,
        overline = albumLabel?.let { l ->
            (listOf(albumTypeText(l.type)) + l.secondaryTypes).joinToString(" · ")
        },
        owner = {
            if (ui.kind == CollectionKind.Liked) {
                OwnerLine("", badge)
            } else {
                OwnerLine(
                    ownerName, badge,
                    onClick = if (header.ownerArtistId.isNotEmpty()) ({ a.onOwner(header.ownerArtistId) }) else null,
                )
            }
        },
        meta = meta,
        description = plainDescription(header.description).ifEmpty { null },
    )
}

// ------------------------------------------------------------------ previews

private fun previewTracks() = listOf(
    Track(id = "1", name = "Cam Kenarı", artists = listOf(ArtistRef("a", "Deniz Aslı")), durationMs = 228_000),
    Track(id = "2", name = "Gece Yarısı Otoyolu", artists = listOf(ArtistRef("b", "Kül & Neon")), durationMs = 312_000),
    Track(id = "3", name = "Sodyum Lambaları", artists = listOf(ArtistRef("c", "Kuzey Işığı")), durationMs = 201_000, recommended = true),
    Track(id = "4", name = "Islak Asfalt", artists = listOf(ArtistRef("d", "Aralık Kolektifi")), durationMs = 201_000),
    Track(id = "5", name = "Bulvar 03:00", artists = listOf(ArtistRef("e", "Mira Tan")), durationMs = 280_000),
)

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun CollectionPreview() {
    val tracks = previewTracks()
    val state = CollectionState(
        status = CollectionStatus.Ready,
        header = CollectionHeader(HeaderLabel.SpotifyPlaylist, "Gece Sürüşü", OwnerText.Text("Deniz"), total = 42),
        rows = tracks, displayed = tracks, totalMs = 10_260_000, enhanced = true,
    )
    ShadeTubeThemeOf {
        CollectionContent(
            CollectionUi(
                CollectionKind.Playlist, "spotify:playlist:x", state,
                now = NowPlayingState(trackId = "1", playing = true), downloaded = setOf("1", "2"),
                sync = SyncUi(synced = true, total = 42, done = 18, active = 2), canEnhance = true,
            ),
            CollectionActions(), PaddingValues(),
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun AlbumPreview() {
    val tracks = previewTracks().filter { !it.recommended }.mapIndexed { i, t -> t.copy(trackNumber = i + 1) }
    val state = CollectionState(
        status = CollectionStatus.Ready,
        header = CollectionHeader(
            HeaderLabel.Album(AlbumType.Album, emptyList(), "2024", "Plak Evi"), "Sis Haritası", OwnerText.Text("Kül & Neon"),
            ownerArtistId = "x", total = 4,
        ),
        rows = tracks, displayed = tracks, totalMs = 1_000_000,
    )
    ShadeTubeThemeOf {
        CollectionContent(CollectionUi(CollectionKind.Album, "mbid", state), CollectionActions(), PaddingValues())
    }
}
