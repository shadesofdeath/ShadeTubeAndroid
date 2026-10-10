package com.app.shadetube.ui.artist

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.rememberTextMeasurer
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistPage
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.library.ArtistHeroInfo
import com.app.shadetube.library.ArtistSection
import com.app.shadetube.library.Card
import com.app.shadetube.library.LibraryBrowser
import com.app.shadetube.library.compactCount
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.CoverStripSkeleton
import com.app.shadetube.ui.components.ErrorState
import com.app.shadetube.ui.components.PlayPauseButton
import com.app.shadetube.ui.components.SecondaryButton
import com.app.shadetube.ui.components.SectionHeader
import com.app.shadetube.ui.components.SegmentedPill
import com.app.shadetube.ui.components.ShadeBottomSheet
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.components.ShadeTopBar
import com.app.shadetube.ui.components.SheetHeader
import com.app.shadetube.ui.components.SheetMenuRow
import com.app.shadetube.ui.components.SkeletonBox
import com.app.shadetube.ui.components.SkeletonLine
import com.app.shadetube.ui.components.TrackListSkeleton
import com.app.shadetube.ui.components.TrackNumber
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.components.urlFor
import com.app.shadetube.ui.home.CardStrip
import com.app.shadetube.ui.home.NowPlayingState
import com.app.shadetube.ui.home.compactCountText
import com.app.shadetube.ui.home.uiLocale
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.TrackMenuContext
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

/** "Popüler" rows shown before "Daha fazla göster". */
private const val POPULAR_COLLAPSED = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(contentPadding: PaddingValues, id: String) {
    val vm: ArtistViewModel = viewModel(key = "artist:$id") { ArtistViewModel(id) }
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val localRes = LocalResources.current
    val state by vm.state.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm, nav) { vm.messages.collect { nav.message(localRes.getString(it)) } }

    ArtistContent(
        state, following, now, vm.contextUri,
        ArtistActions(
            onBack = nav::back,
            onRetry = vm::retry,
            onPlay = { vm.play(-1) },
            onPlayRow = vm::play,
            onRowMore = { nav.showTrackMenu(listOf(it), TrackMenuContext()) },
            onFollow = vm::toggleFollow,
            onRadio = vm::startRadio,
            onMore = { menuOpen = true },
            onOpen = { nav.open(it.destination) },
        ),
        contentPadding,
    )

    val ready = state as? ArtistLoad.Ready
    if (menuOpen && ready != null) {
        ShadeBottomSheet(onDismissRequest = { menuOpen = false }) {
            SheetHeader(ready.page.artist.name)
            if (ready.hero.canRadio) {
                SheetMenuRow(R.drawable.ic_radio, stringResource(R.string.artist_start_radio), {
                    menuOpen = false
                    vm.startRadio()
                })
            }
            SheetMenuRow(
                if (following) R.drawable.ic_check else R.drawable.ic_plus,
                stringResource(if (following) R.string.artist_unfollow else R.string.artist_follow),
                {
                    menuOpen = false
                    vm.toggleFollow()
                },
            )
            if (ready.hero.webLink.isNotEmpty()) {
                SheetMenuRow(
                    R.drawable.ic_link,
                    stringResource(if (ready.hero.webLinkIsSpotify) R.string.collection_copy_spotify_link else R.string.collection_copy_mb_link),
                    {
                        menuOpen = false
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText(ready.page.artist.name, ready.hero.webLink))
                        nav.message(localRes.getString(R.string.snackbar_link_copied))
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

class ArtistActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val onPlayRow: (Int) -> Unit = {},
    val onRowMore: (Track) -> Unit = {},
    val onFollow: () -> Unit = {},
    val onRadio: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onOpen: (Card) -> Unit = {},
)

@Composable
fun ArtistContent(
    state: ArtistLoad,
    following: Boolean,
    now: NowPlayingState,
    contextUri: String,
    a: ArtistActions,
    contentPadding: PaddingValues,
) {
    val x = ShadeTheme.extras
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    Box(Modifier.fillMaxSize().background(x.surface0)) {
        when (state) {
            ArtistLoad.Loading -> ArtistSkeleton()
            ArtistLoad.Error -> Column(Modifier.statusBarsPadding().padding(top = 56.dp)) {
                ErrorState(onRetry = a.onRetry, modifier = Modifier.padding(top = 48.dp))
            }
            is ArtistLoad.Ready -> {
                var showAll by rememberSaveable { mutableStateOf(false) }
                var release by rememberSaveable { mutableIntStateOf(0) }
                val popular = state.sections.filterIsInstance<ArtistSection.Popular>().firstOrNull()
                val releases = state.sections.filterIsInstance<ArtistSection.Releases>()
                val similar = state.sections.filterIsInstance<ArtistSection.Similar>().firstOrNull()
                val playingThis = now.isPlayingContext(contextUri)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                ) {
                    item(key = "hero") { ArtistHero(state.page.artist, state.hero) }
                    item(key = "actions") {
                        val followLabel = stringResource(if (following) R.string.artist_following else R.string.artist_follow)
                        val radioLabel = stringResource(R.string.artist_start_radio)
                        val measurer = rememberTextMeasurer()
                        val labelStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        val density = LocalDensity.current
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            // Both pills + more + play must fit (360–412dp phones, long translations, big fonts): else
                            // "Radyo başlat" becomes a round icon button so the play button is never pushed off screen.
                            val textW = with(density) {
                                (measurer.measure(followLabel, style = labelStyle).size.width + measurer.measure(radioLabel, style = labelStyle).size.width).toDp()
                            }
                            val need = 32.dp + (36.dp + 28.dp) * 2 + textW + 8.dp * 3 + 48.dp + (if (popular != null) 52.dp + 8.dp else 0.dp)
                            val radioPill = state.hero.canRadio && need <= maxWidth
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SecondaryButton(
                                    followLabel, a.onFollow,
                                    icon = if (following) R.drawable.ic_check else R.drawable.ic_plus, horizontalPadding = 18,
                                )
                                if (radioPill) {
                                    SecondaryButton(radioLabel, a.onRadio, icon = R.drawable.ic_radio, horizontalPadding = 18)
                                } else if (state.hero.canRadio) {
                                    ShadeIconButton(
                                        R.drawable.ic_radio, radioLabel, a.onRadio, tint = x.text1, size = 48.dp,
                                        modifier = Modifier.clip(CircleShape).background(x.surface3),
                                    )
                                }
                                ShadeIconButton(R.drawable.ic_more_vertical, stringResource(R.string.common_more_cd), a.onMore, tint = x.text1, size = 48.dp)
                                Spacer(Modifier.weight(1f))
                                if (popular != null) {
                                    PlayPauseButton(playingThis, a.onPlay, size = 52, iconSize = 26, filled = true)
                                }
                            }
                        }
                    }
                    if (popular != null) {
                        item(key = "popular-h") { SectionHeader(stringResource(R.string.artist_popular), Modifier.padding(top = 24.dp)) }
                        val shown = if (showAll) popular.shown else popular.shown.take(POPULAR_COLLAPSED)
                        itemsIndexed(shown, key = { i, t -> "p$i:${t.id}" }) { i, t ->
                            val current = t.id.isNotEmpty() && t.id == now.trackId
                            TrackRow(
                                title = t.name,
                                subtitle = popularSubtitle(t),
                                artworkUrl = null,
                                isCurrent = current,
                                isPlaying = current && now.playing,
                                explicit = t.explicitContent,
                                enabled = t.playable,
                                duration = if (t.durationMs > 0) com.app.shadetube.ui.components.formatDuration(t.durationMs.toLong()) else null,
                                leading = { TrackNumber(i + 1, current, current && now.playing) },
                                onClick = { a.onPlayRow(i) },
                                onLongClick = { a.onRowMore(t) },
                                onMore = { a.onRowMore(t) },
                            )
                        }
                        if (popular.shown.size > POPULAR_COLLAPSED) {
                            item(key = "popular-more") {
                                ShadeTextButton(
                                    stringResource(if (showAll) R.string.artist_show_less else R.string.artist_show_more),
                                    { showAll = !showAll }, Modifier.padding(start = 8.dp), color = x.text2,
                                )
                            }
                        }
                    }
                    if (releases.isNotEmpty()) {
                        val idx = release.coerceIn(0, releases.size - 1)
                        item(key = "releases-h") { SectionHeader(stringResource(R.string.artist_releases), Modifier.padding(top = 24.dp)) }
                        if (releases.size > 1) {
                            item(key = "releases-seg") {
                                SegmentedPill(
                                    releases.map { releaseTitle(it.kind) }, idx, { release = it },
                                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                                )
                            }
                        }
                        item(key = "releases-s") { CardStrip(releases[idx].cards, 132.dp, a.onOpen) }
                    }
                    if (similar != null) {
                        item(key = "similar-h") { SectionHeader(stringResource(R.string.artist_similar), Modifier.padding(top = 24.dp)) }
                        item(key = "similar-s") { CardStrip(similar.cards, 96.dp, a.onOpen) }
                    }
                }
            }
        }
        // Top bar over the hero; the name slides in once the hero scrolled away.
        val name = (state as? ArtistLoad.Ready)?.page?.artist?.name
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (scrolled) Modifier.background(x.surface0) else Modifier)
                .statusBarsPadding(),
        ) {
            ShadeTopBar(onNavigate = a.onBack, title = if (scrolled) name else null)
        }
    }
}

@Composable
private fun popularSubtitle(t: Track): String {
    val plays = if (t.listenCount > 0) {
        stringResource(R.string.artist_plays, compactCountText(compactCount(t.listenCount, uiLocale())))
    } else {
        ""
    }
    return listOf(t.album.name, plays).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { t.artistLine }
}

@Composable
private fun releaseTitle(k: ArtistSection.Releases.Kind): String = stringResource(
    when (k) {
        ArtistSection.Releases.Kind.Albums -> R.string.artist_albums
        ArtistSection.Releases.Kind.SinglesAndEps -> R.string.artist_singles
        ArtistSection.Releases.Kind.Other -> R.string.artist_other_releases
    },
)

/** Hero 280 (SCREENS 9): blurred backdrop of the artist picture, 120 circle, name displayMedium, mono label. */
@Composable
private fun ArtistHero(artist: Artist, hero: ArtistHeroInfo) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    val px = with(LocalDensity.current) { 240.dp.roundToPx() }
    val url = remember(artist.images, px) { artist.images.urlFor(px) }
    val locale = uiLocale()
    val label = buildList {
        add(stringResource(if (hero.group) R.string.home_artist_kind_group else R.string.label_artist))
        if (hero.country.isNotEmpty()) add(hero.country)
        if (hero.beginYear.isNotEmpty()) add(hero.beginYear)
        if (hero.firstTag.isNotEmpty()) add(hero.firstTag)
        when {
            hero.listenCount > 0 -> add(stringResource(R.string.artist_listens, compactCountText(compactCount(hero.listenCount, locale))))
            hero.disambiguation.isNotEmpty() -> add(hero.disambiguation)
        }
    }.joinToString(" · ").uppercase(locale)
        .replace("ListenBrainz".uppercase(locale), "LISTENBRAINZ") // brand: no Turkish dotted İ
    Box(Modifier.fillMaxWidth()) {
        // Backdrop: the picture blurred (API 31+), else the accent glow (README › Bulanık arka plan).
        Box(Modifier.matchParentSize().clipToBounds()) { // the 1.2× blurred picture must not spill onto the buttons
            if (url != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ShadeTheme.reduceMotion) {
                Artwork(url, Modifier.fillMaxSize().scale(1.2f).blur(60.dp), kind = ArtworkKind.Artist, shape = RectangleShape)
                Box(Modifier.fillMaxSize().background(x.surface0.copy(alpha = 0.70f)))
            } else {
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                listOf(accent.glow, Color.Transparent),
                                center = Offset(size.width / 2, size.height * 0.35f), radius = size.width * 0.8f,
                            ),
                        )
                    },
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, x.surface0), startY = 300f)))
        }
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp, bottom = 16.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(url, Modifier.clip(CircleShape), kind = ArtworkKind.Artist, size = 120.dp, contentDescription = artist.name)
            Spacer(Modifier.height(16.dp))
            Text(
                artist.name, style = MaterialTheme.typography.displayMedium, color = x.text1, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            if (label.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(label, style = ShadeMono.overline, color = x.text2, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ArtistSkeleton() {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SkeletonBox(Modifier.size(120.dp), CircleShape)
        Spacer(Modifier.height(16.dp))
        SkeletonLine(180.dp, height = 28.dp)
        Spacer(Modifier.height(10.dp))
        SkeletonLine(120.dp)
        Spacer(Modifier.height(32.dp))
        TrackListSkeleton(rows = 5)
        Spacer(Modifier.height(24.dp))
        CoverStripSkeleton(Modifier.padding(start = 20.dp).fillMaxWidth())
    }
}

// ------------------------------------------------------------------ previews

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun ArtistPreview() {
    val artist = Artist(id = "a", name = "Kül & Neon", type = "Group", country = "TR", beginYear = "2014", tags = listOf("indie rock"), listenCount = 123_400)
    val page = ArtistPage(
        artist = artist,
        topTracks = List(7) { Track(id = "t$it", name = "Şarkı ${it + 1}", artists = listOf(ArtistRef("a", "Kül & Neon")), durationMs = 220_000, listenCount = 50_000L - it * 3000) },
        albums = List(4) { Album(id = "al$it", name = "Albüm $it", firstReleaseDate = "202$it") },
        singles = List(3) { Album(id = "s$it", name = "Tekli $it", firstReleaseDate = "2025") },
        similar = List(4) { Artist(id = "x$it", name = "Benzer $it") },
    )
    val hero = ArtistHeroInfo(true, "TR", "2014", "indie rock", 123_400, "", true, "", true)
    val sections = listOf(
        ArtistSection.Popular(page.topTracks),
        ArtistSection.Releases(ArtistSection.Releases.Kind.Albums, page.albums.map { LibraryBrowser.browseAlbumCard(it, false) }),
        ArtistSection.Releases(ArtistSection.Releases.Kind.SinglesAndEps, page.singles.map { LibraryBrowser.browseAlbumCard(it, false) }),
        ArtistSection.Similar(page.similar.map { LibraryBrowser.browseArtistCard(it) }),
    )
    ShadeTubeThemeOf {
        ArtistContent(ArtistLoad.Ready(page, hero, sections), false, NowPlayingState(), "", ArtistActions(), PaddingValues())
    }
}
