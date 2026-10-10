package com.app.shadetube.ui.search

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.app.shadetube.ui.components.gridColumns
import com.app.shadetube.ui.nav.ScrollToTopEffect
import com.app.shadetube.ui.nav.Tab as NavTab
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.catalog.Track
import com.app.shadetube.library.Card
import com.app.shadetube.library.CardPlaceholder
import com.app.shadetube.library.Destination
import com.app.shadetube.library.Genre
import com.app.shadetube.library.HomeRepository
import com.app.shadetube.library.LibraryBrowser
import com.app.shadetube.library.LinkHint
import com.app.shadetube.library.SearchInput
import com.app.shadetube.library.SearchRepository
import com.app.shadetube.links.LinkService
import com.app.shadetube.links.LinkVerb
import com.app.shadetube.podcasts.DirectoryEntry
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.ErrorState
import com.app.shadetube.ui.components.SearchField
import com.app.shadetube.ui.components.SectionHeader
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.components.ShadeTopBar
import com.app.shadetube.ui.components.SmallPillButton
import com.app.shadetube.ui.components.SourceBadge
import com.app.shadetube.ui.components.TrackListSkeleton
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.home.CardListRow
import com.app.shadetube.ui.home.CardStrip
import com.app.shadetube.ui.home.NowPlayingState
import com.app.shadetube.ui.home.stripKey
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.nav.TrackMenuContext
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

@Composable
fun SearchScreen(contentPadding: PaddingValues) {
    val vm: SearchViewModel = viewModel()
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val localRes = LocalResources.current
    val state by vm.state.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()

    LaunchedEffect(vm, nav) {
        vm.events.collect { e ->
            when (e) {
                is SearchEvent.Navigate -> nav.navigate(e.screen)
                SearchEvent.Login -> nav.startSpotifyLogin()
                is SearchEvent.Message -> nav.message(localRes.getString(e.text))
            }
        }
    }
    BackHandler(enabled = state.genre != null) { vm.closeGenre() }

    val actions = remember(vm, nav) {
        SearchActions(
            onText = vm::onTextChange,
            onSubmit = vm::submit,
            onFocus = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
                vm.onClipboard(text)
            },
            onRecent = vm::searchRecent,
            onRemoveRecent = vm::removeRecent,
            onClearRecent = vm::clearRecent,
            onGenre = vm::openGenre,
            onCloseGenre = vm::closeGenre,
            onTab = vm::selectTab,
            onRetry = vm::retry,
            onActivateLink = vm::activate,
            onClipboardUse = { vm.onTextChange(it.text) },
            onDismissClipboard = vm::dismissClipboard,
            onPlayTrack = vm::playTrack,
            onTrackMore = { nav.showTrackMenu(listOf(it), TrackMenuContext()) },
            onOpen = { d ->
                vm.rememberQuery()
                nav.open(d)
            },
            onOpenGenreCard = { nav.open(it) },
            onPodcast = { p ->
                vm.rememberQuery()
                nav.navigate(Screen.PodcastShow(p.feedUrl.ifEmpty { p.appleId }))
            },
            onProfile = { nav.navigate(Screen.Profile) },
        )
    }
    SearchContent(state, recent, now, actions, contentPadding)
}

class SearchActions(
    val onText: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onFocus: () -> Unit = {},
    val onRecent: (String) -> Unit = {},
    val onRemoveRecent: (String) -> Unit = {},
    val onClearRecent: () -> Unit = {},
    val onGenre: (Genre) -> Unit = {},
    val onCloseGenre: () -> Unit = {},
    val onTab: (SearchResultTab) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onActivateLink: (LinkHint) -> Unit = {},
    val onClipboardUse: (ClipboardLink) -> Unit = {},
    val onDismissClipboard: () -> Unit = {},
    val onPlayTrack: (Int) -> Unit = {},
    val onTrackMore: (Track) -> Unit = {},
    val onOpen: (Destination) -> Unit = {},
    val onOpenGenreCard: (Destination) -> Unit = {},
    val onPodcast: (DirectoryEntry) -> Unit = {},
    val onProfile: () -> Unit = {},
)

@Composable
fun SearchContent(
    state: SearchUiState,
    recent: List<String>,
    now: NowPlayingState,
    a: SearchActions,
    contentPadding: PaddingValues,
) {
    val x = ShadeTheme.extras
    val bottom = contentPadding.calculateBottomPadding() + 24.dp
    val genre = state.genre
    if (genre != null) {
        GenreContent(genre, a, bottom)
        return
    }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize().background(x.surface0).statusBarsPadding()) {
        Text(
            stringResource(R.string.nav_search),
            Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp),
            style = MaterialTheme.typography.headlineLarge, color = x.text1, maxLines = 1,
        )
        // Sticky 52dp search field.
        SearchField(
            query = state.text,
            onQueryChange = a.onText,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .onFocusChanged { if (it.hasFocus && state.text.isEmpty()) a.onFocus() },
            onSearch = {
                a.onSubmit()
                focus.clearFocus()
            },
        )
        Spacer(Modifier.height(12.dp))
        when (val input = state.input) {
            is SearchInput.LinkFound -> LazyColumn(state = rememberTopScrollingList(), contentPadding = PaddingValues(bottom = bottom)) {
                item { LinkCard(input.hint, state.linkBusy, a.onActivateLink, Modifier.padding(horizontal = 20.dp)) }
            }
            SearchInput.Browse -> BrowseContent(state, recent, a, bottom)
            else -> ResultsContent(state, now, a, bottom)
        }
    }
}

/** A list state of the Search tab root that scrolls to the top when the selected Search tab is tapped again. */
@Composable
private fun rememberTopScrollingList(): androidx.compose.foundation.lazy.LazyListState {
    val state = rememberLazyListState()
    ScrollToTopEffect(NavTab.Search, state)
    return state
}

// ------------------------------------------------------------------ browse (empty box)

@Composable
private fun BrowseContent(state: SearchUiState, recent: List<String>, a: SearchActions, bottom: androidx.compose.ui.unit.Dp) {
    val x = ShadeTheme.extras
    val genres = SearchRepository.genres
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 2 genre columns on phones, up to 4 on wide windows.
        val columns = gridColumns(maxWidth, minTile = 200.dp, max = 4)
        val tileW = (maxWidth - 40.dp - 12.dp * (columns - 1)) / columns
        LazyColumn(Modifier.fillMaxSize(), state = rememberTopScrollingList(), contentPadding = PaddingValues(bottom = bottom)) {
            state.clipboard?.let { clip ->
                item(key = "clipboard") {
                    LinkCard(
                        clip.hint, state.linkBusy, a.onActivateLink, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        header = stringResource(R.string.search_clipboard_link), onDismiss = a.onDismissClipboard,
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent-h") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.search_recent), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, color = x.text1,
                        )
                        ShadeTextButton(stringResource(R.string.search_clear_recent), a.onClearRecent, color = x.text2)
                    }
                }
                items(recent, key = { "r:$it" }) { q ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { a.onRecent(q) }
                            .padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ShadeIcon(R.drawable.ic_history, null, tint = x.text2, size = 20.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(q, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ShadeIconButton(
                            R.drawable.ic_close, stringResource(R.string.search_remove_recent_cd, q), { a.onRemoveRecent(q) },
                            iconSize = 20.dp,
                        )
                    }
                }
            }
            item(key = "genres-h") {
                SectionHeader(
                    stringResource(R.string.search_browse), Modifier.padding(top = 20.dp),
                    subtitle = stringResource(R.string.search_browse_sub),
                )
            }
            items(genres.chunked(columns), key = { "g:" + it.first().key.name }) { row ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { g -> GenreTile(g, { a.onGenre(g) }, Modifier.width(tileW)) }
                }
            }
        }
    }
}

@Composable
fun genreTitle(key: Genre.Key): String = stringResource(
    when (key) {
        Genre.Key.TurkishPop -> R.string.search_genre_turkish_pop
        Genre.Key.AnatolianRock -> R.string.search_genre_anatolian_rock
        Genre.Key.Arabesque -> R.string.search_genre_arabesque
        Genre.Key.TurkishFolk -> R.string.search_genre_turkish_folk
        Genre.Key.Rock -> R.string.search_genre_rock
        Genre.Key.HipHop -> R.string.search_genre_hiphop
        Genre.Key.Electronic -> R.string.search_genre_electronic
        Genre.Key.Jazz -> R.string.search_genre_jazz
        Genre.Key.Classical -> R.string.search_genre_classical
        Genre.Key.Metal -> R.string.search_genre_metal
        Genre.Key.Indie -> R.string.search_genre_indie
        Genre.Key.RnB -> R.string.search_genre_rnb
        Genre.Key.LoFi -> R.string.search_genre_lofi
        Genre.Key.Ambient -> R.string.search_genre_ambient
        Genre.Key.Soul -> R.string.search_genre_soul
        Genre.Key.Blues -> R.string.search_genre_blues
    },
)

/** Genre tile (SCREENS 4): 96 high, title top-left, a cover placeholder turned 40° in the bottom-right corner. */
@Composable
private fun GenreTile(g: Genre, onClick: () -> Unit, modifier: Modifier) {
    val x = ShadeTheme.extras
    val tint = Color(0xFF000000 or g.color.toLong())
    val bg = if (x.isDark) tint else x.surface2
    Box(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, x.surface3, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 14.dp, y = 10.dp)
                .rotate(40f)
                .size(64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (x.isDark) x.surface3 else tint.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) { ShadeIcon(R.drawable.ic_music_note, null, tint = x.text3, size = 28.dp) }
        Text(
            genreTitle(g.key), Modifier.padding(12.dp).fillMaxWidth(0.8f),
            style = MaterialTheme.typography.titleMedium, color = x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GenreContent(g: GenreState, a: SearchActions, bottom: androidx.compose.ui.unit.Dp) {
    val x = ShadeTheme.extras
    Column(Modifier.fillMaxSize().background(x.surface0).statusBarsPadding()) {
        ShadeTopBar(onNavigate = a.onCloseGenre, title = genreTitle(g.genre.key))
        when (val r = g.results) {
            GenreResults.Loading -> TrackListSkeleton(Modifier.padding(top = 8.dp))
            GenreResults.Error -> ErrorState(onRetry = { a.onGenre(g.genre) }, modifier = Modifier.padding(top = 48.dp))
            is GenreResults.Ready -> {
                val artists = remember(r) { r.results.artists.map { LibraryBrowser.browseArtistCard(it) } }
                val albums = remember(r) { r.results.albums.map { LibraryBrowser.browseAlbumCard(it) } }
                if (artists.isEmpty() && albums.isEmpty()) {
                    EmptyState(R.drawable.ic_search, stringResource(R.string.search_genre_empty), null, Modifier.padding(top = 48.dp))
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = rememberTopScrollingList(), contentPadding = PaddingValues(bottom = bottom)) {
                        if (artists.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.search_tab_artists), Modifier.padding(top = 8.dp)) }
                            item { CardStrip(artists, 96.dp, { a.onOpenGenreCard(it.destination) }) }
                        }
                        if (albums.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.search_tab_albums), Modifier.padding(top = 24.dp)) }
                            items(albums.size, key = { stripKey(albums[it], it) }) { i ->
                                CardListRow(albums[i], { a.onOpenGenreCard(albums[i].destination) })
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ link card

@Composable
private fun verbText(v: LinkVerb?): String = when (v) {
    LinkVerb.PlaySong -> stringResource(R.string.search_link_verb_play_song)
    LinkVerb.PlayVideoAsSong -> stringResource(R.string.search_link_verb_play_video)
    LinkVerb.ImportPlaylist -> stringResource(R.string.search_link_verb_import)
    LinkVerb.OpenAlbum -> stringResource(R.string.search_link_verb_open_album)
    LinkVerb.OpenPlaylist -> stringResource(R.string.search_link_verb_open_playlist)
    LinkVerb.OpenArtist -> stringResource(R.string.search_link_verb_open_artist)
    null -> stringResource(R.string.search_link_unsupported_title)
}

/** "Link algılandı" card (SCREENS 4): surface2, 12 corners; source badge + what opening does + "Aç" / "Çal" pill 36. */
@Composable
fun LinkCard(
    hint: LinkHint,
    busy: Boolean,
    onActivate: (LinkHint) -> Unit,
    modifier: Modifier = Modifier,
    header: String = stringResource(R.string.search_link_detected),
    onDismiss: (() -> Unit)? = null,
) {
    val x = ShadeTheme.extras
    val service = when (hint.link.service) {
        LinkService.Spotify -> "Spotify"
        LinkService.YouTube -> "YouTube"
        LinkService.MusicBrainz -> "MusicBrainz"
        LinkService.None -> stringResource(R.string.search_link_generic)
    }
    val (title, body) = when (hint.state) {
        LinkHint.State.Unsupported -> stringResource(R.string.search_link_unsupported_title) to stringResource(R.string.search_link_unsupported_body)
        LinkHint.State.Connect -> stringResource(R.string.search_link_connect_title) to stringResource(R.string.search_link_connect_body, verbText(hint.verb))
        LinkHint.State.Open -> verbText(hint.verb) to null
    }
    val button = when {
        hint.state == LinkHint.State.Connect -> stringResource(R.string.search_link_connect)
        hint.verb == LinkVerb.PlaySong || hint.verb == LinkVerb.PlayVideoAsSong -> stringResource(R.string.search_link_play)
        hint.verb == LinkVerb.ImportPlaylist -> stringResource(R.string.library_import)
        else -> stringResource(R.string.search_link_open)
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(x.surface2)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                header.uppercase(com.app.shadetube.ui.home.uiLocale()), Modifier.weight(1f),
                style = ShadeMono.overline, color = x.text3, maxLines = 1,
            )
            if (onDismiss != null) {
                ShadeIconButton(R.drawable.ic_close, stringResource(R.string.common_close_cd), onDismiss, iconSize = 18.dp, size = 32.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SourceBadge(service)
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (hint.activatable) {
                Spacer(Modifier.width(12.dp))
                SmallPillButton(button, { onActivate(hint) }, Modifier.padding(end = 8.dp), primary = true, enabled = !busy)
            }
        }
    }
}

// ------------------------------------------------------------------ results

@Composable
private fun ResultsContent(state: SearchUiState, now: NowPlayingState, a: SearchActions, bottom: androidx.compose.ui.unit.Dp) {
    val x = ShadeTheme.extras
    when (val r = state.results) {
        ResultsState.Idle -> Unit
        is ResultsState.Loading -> TrackListSkeleton(Modifier.padding(top = 8.dp))
        is ResultsState.Error -> ErrorState(onRetry = a.onRetry, modifier = Modifier.padding(top = 48.dp))
        is ResultsState.Ready -> {
            if (r.isEmpty) {
                EmptyState(
                    R.drawable.ic_search, stringResource(R.string.search_no_results, r.query),
                    stringResource(R.string.search_no_results_body), Modifier.padding(top = 32.dp),
                )
                return
            }
            val tabs = SearchResultTab.entries
            Column(Modifier.fillMaxSize()) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = state.tab.ordinal,
                    containerColor = x.surface0,
                    contentColor = x.text1,
                    edgePadding = 12.dp,
                    indicator = {
                        TabRowDefaults.PrimaryIndicator(
                            Modifier.tabIndicatorOffset(state.tab.ordinal, matchContentSize = true),
                            width = androidx.compose.ui.unit.Dp.Unspecified,
                            height = 2.dp,
                            color = ShadeTheme.accent.accent,
                        )
                    },
                    divider = { HorizontalDivider(thickness = 1.dp, color = x.outlineVariant) },
                ) {
                    tabs.forEach { t ->
                        val selected = t == state.tab
                        Tab(
                            selected = selected,
                            onClick = { a.onTab(t) },
                            selectedContentColor = x.text1,
                            unselectedContentColor = x.text2,
                            text = {
                                Text(
                                    tabTitle(t), style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                                )
                            },
                        )
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = rememberTopScrollingList(), contentPadding = PaddingValues(top = 8.dp, bottom = bottom)) {
                    when (state.tab) {
                        SearchResultTab.All -> allTab(r, now, a)
                        SearchResultTab.Songs -> songs(r.results.tracks, Int.MAX_VALUE, now, a)
                        SearchResultTab.Albums -> cardRows(albumCards(r.results.albums), a)
                        SearchResultTab.Artists -> cardRows(artistCards(r.results.artists), a)
                        SearchResultTab.Playlists -> cardRows(playlistCards(r.results.playlists), a)
                        SearchResultTab.Podcasts -> podcasts(r.podcasts, a)
                    }
                }
            }
        }
    }
}

@Composable
private fun tabTitle(t: SearchResultTab): String = stringResource(
    when (t) {
        SearchResultTab.All -> R.string.search_tab_all
        SearchResultTab.Songs -> R.string.search_tab_songs
        SearchResultTab.Albums -> R.string.search_tab_albums
        SearchResultTab.Artists -> R.string.search_tab_artists
        SearchResultTab.Playlists -> R.string.search_tab_playlists
        SearchResultTab.Podcasts -> R.string.search_tab_podcasts
    },
)

private fun albumCards(list: List<Album>) = list.map { LibraryBrowser.browseAlbumCard(it) }
private fun artistCards(list: List<Artist>) = list.map { LibraryBrowser.browseArtistCard(it) }
private fun playlistCards(list: List<Playlist>) = list.map { p ->
    Card(
        p.name, HomeRepository.playlistSubtitle(p.countKnown, p.totalTracks, p.ownerName), p.images,
        Destination.playlist(p.id), playable = true, placeholder = CardPlaceholder.Playlist,
    )
}

private fun LazyListScope.allTab(r: ResultsState.Ready, now: NowPlayingState, a: SearchActions) {
    val res = r.results
    if (res.tracks.isNotEmpty()) {
        item(key = "songs-h") {
            SectionHeader(
                stringResource(R.string.search_tab_songs),
                actionLabel = if (res.tracks.size > SearchRepository.ALL_TAB_TRACKS) stringResource(R.string.home_see_all) else null,
                onAction = { a.onTab(SearchResultTab.Songs) },
            )
        }
        songs(res.tracks, SearchRepository.ALL_TAB_TRACKS, now, a)
    }
    strip("artists", artistCards(res.artists), 96, R.string.search_tab_artists, SearchResultTab.Artists, a)
    strip("albums", albumCards(res.albums), 132, R.string.search_tab_albums, SearchResultTab.Albums, a)
    strip("playlists", playlistCards(res.playlists), 132, R.string.search_tab_playlists, SearchResultTab.Playlists, a)
    if (r.podcasts.isNotEmpty()) {
        item(key = "pods-h") {
            SectionHeader(
                stringResource(R.string.search_tab_podcasts), Modifier.padding(top = 24.dp),
                actionLabel = stringResource(R.string.home_see_all), onAction = { a.onTab(SearchResultTab.Podcasts) },
            )
        }
        item(key = "pods-s") {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(r.podcasts.take(12), key = { it.appleId.ifEmpty { it.feedUrl } }) { p -> PodcastTile(p, { a.onPodcast(p) }) }
            }
        }
    }
}

private fun LazyListScope.strip(key: String, cards: List<Card>, size: Int, title: Int, tab: SearchResultTab, a: SearchActions) {
    if (cards.isEmpty()) return
    item(key = "$key-h") {
        SectionHeader(
            stringResource(title), Modifier.padding(top = 24.dp),
            actionLabel = stringResource(R.string.home_see_all), onAction = { a.onTab(tab) },
        )
    }
    item(key = "$key-s") { CardStrip(cards, size.dp, { a.onOpen(it.destination) }) }
}

private fun LazyListScope.songs(tracks: List<Track>, max: Int, now: NowPlayingState, a: SearchActions) {
    if (tracks.isEmpty()) {
        item { EmptyState(R.drawable.ic_music_note, stringResource(R.string.search_tab_empty), null) }
        return
    }
    itemsIndexed(tracks.take(max), key = { i, t -> "t$i:${t.id}" }) { i, t ->
        TrackRow(
            t,
            isCurrent = t.id.isNotEmpty() && t.id == now.trackId,
            isPlaying = now.playing,
            onClick = { a.onPlayTrack(i) },
            onMore = { a.onTrackMore(t) },
            onLongClick = { a.onTrackMore(t) },
        )
    }
}

private fun LazyListScope.cardRows(cards: List<Card>, a: SearchActions) {
    if (cards.isEmpty()) {
        item { EmptyState(R.drawable.ic_search, stringResource(R.string.search_tab_empty), null) }
        return
    }
    items(cards.size, key = { stripKey(cards[it], it) }) { i -> CardListRow(cards[i], { a.onOpen(cards[i].destination) }) }
}

private fun LazyListScope.podcasts(list: List<DirectoryEntry>, a: SearchActions) {
    if (list.isEmpty()) {
        item { EmptyState(R.drawable.ic_podcast, stringResource(R.string.search_tab_empty), null) }
        return
    }
    items(list, key = { it.appleId.ifEmpty { it.feedUrl } }) { p ->
        val x = ShadeTheme.extras
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(role = Role.Button) { a.onPodcast(p) }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(p.image.ifEmpty { null }, kind = ArtworkKind.Podcast, size = 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(p.author, p.genre).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PodcastTile(p: DirectoryEntry, onClick: () -> Unit) {
    val x = ShadeTheme.extras
    Column(Modifier.width(116.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)) {
        Artwork(p.image.ifEmpty { null }, kind = ArtworkKind.Podcast, size = 116.dp)
        Spacer(Modifier.height(8.dp))
        Text(p.title, style = com.app.shadetube.ui.theme.ShadeTextStyles.tileTitle, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (p.author.isNotEmpty()) {
            Text(p.author, style = com.app.shadetube.ui.theme.ShadeTextStyles.tileSubtitle, color = x.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ previews

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun SearchBrowsePreview() {
    ShadeTubeThemeOf {
        SearchContent(SearchUiState(), listOf("Kül & Neon", "gece sürüşü"), NowPlayingState(), SearchActions(), PaddingValues())
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun SearchResultsPreview() {
    val tracks = List(7) { i ->
        Track(id = "t$i", name = "Şarkı ${i + 1}", artists = listOf(ArtistRef("a", "Mira Tan")), durationMs = 200_000 + i * 7_000)
    }
    val results = SearchResults(
        artists = List(4) { Artist(id = "a$it", name = "Sanatçı $it", type = "Group") },
        albums = List(4) { Album(id = "al$it", name = "Albüm $it", firstReleaseDate = "2024") },
        tracks = tracks,
    )
    ShadeTubeThemeOf {
        SearchContent(
            SearchUiState(text = "mira", input = SearchInput.Query("mira"), results = ResultsState.Ready("mira", results, emptyList())),
            emptyList(), NowPlayingState(trackId = "t1", playing = true), SearchActions(), PaddingValues(),
        )
    }
}
