package com.app.shadetube.ui.home

import com.app.shadetube.ui.components.ProfileAvatar
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.app.shadetube.ui.components.AdaptiveWidths
import com.app.shadetube.ui.components.gridColumns
import com.app.shadetube.ui.components.LocalWindowLayout
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.nav.ScrollToTopEffect
import com.app.shadetube.ui.theme.ShadeMono
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.library.AlbumType
import com.app.shadetube.library.Card
import com.app.shadetube.library.CardPlaceholder
import com.app.shadetube.library.CardShape
import com.app.shadetube.library.CardSubtitle
import com.app.shadetube.library.Destination
import com.app.shadetube.library.FreshRelease
import com.app.shadetube.library.Greeting
import com.app.shadetube.library.HomeFeed
import com.app.shadetube.library.HomeRepository
import com.app.shadetube.library.HomeSection
import com.app.shadetube.library.SectionLink
import com.app.shadetube.library.greeting
import com.app.shadetube.stats.SmartList
import com.app.shadetube.stats.SmartLists
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.ErrorState
import com.app.shadetube.ui.components.MadeForYouCard
import com.app.shadetube.ui.components.RecentTile
import com.app.shadetube.ui.components.SectionHeader
import com.app.shadetube.ui.components.ShadeTopBar
import com.app.shadetube.ui.components.SkeletonBox
import com.app.shadetube.ui.components.SkeletonLine
import com.app.shadetube.ui.components.CoverStripSkeleton
import com.app.shadetube.ui.components.urlFor
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.nav.Tab
import com.app.shadetube.ui.theme.ShadeTextStyles
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Strips longer than this get "Tümünü gör". */
private const val SEE_ALL_MIN = 7

@Immutable
data class HomeUi(
    val feed: HomeFeed,
    val profileName: String = "",
    val avatarUrl: String = "",
    val smartLists: List<SmartList> = emptyList(),
    val smartReady: Boolean = true,
    val nowPlaying: NowPlayingState = NowPlayingState(),
    val refreshing: Boolean = false,
    /** "Bu hafta" card (null = history not loaded / nothing to show). */
    val week: WeekStats? = null,
)

class HomeActions(
    val onOpen: (Card) -> Unit = {},
    val onOpenSmart: (SmartList) -> Unit = {},
    val onSeeAll: (String) -> Unit = {},
    val onLibrary: () -> Unit = {},
    val onProfile: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onStats: () -> Unit = {},
)

@Composable
fun HomeScreen(contentPadding: PaddingValues) {
    val vm: HomeViewModel = viewModel()
    val nav = LocalNavigator.current
    val feed by vm.feed.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val smart by vm.smartLists.collectAsStateWithLifecycle()
    val smartReady by vm.smartReady.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val actions = remember(nav, vm) {
        HomeActions(
            onOpen = { nav.open(it.destination) },
            onOpenSmart = { nav.navigate(Screen.SmartList(it.id)) },
            onSeeAll = { nav.navigate(Screen.HomeSection(it)) },
            onLibrary = { nav.switchTab(Tab.Library) },
            onProfile = { nav.navigate(Screen.Profile) },
            onRefresh = vm::refresh,
            onRetry = vm::retry,
            onStats = { nav.navigate(Screen.Stats) },
        )
    }
    HomeContent(
        HomeUi(
            feed = feed,
            profileName = if (loggedIn) profile.name else "",
            avatarUrl = if (loggedIn) profile.imageUrl else "",
            smartLists = smart,
            smartReady = smartReady,
            nowPlaying = now,
            refreshing = refreshing,
            week = week,
        ),
        actions,
        contentPadding,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(ui: HomeUi, actions: HomeActions, contentPadding: PaddingValues) {
    val x = ShadeTheme.extras
    val listState = rememberLazyListState()
    ScrollToTopEffect(Tab.Home, listState)
    Box(Modifier.fillMaxSize().background(x.surface0)) {
        AccentLightLeak(Modifier.fillMaxWidth().height(360.dp))
        val ptr = rememberPullToRefreshState()
        androidx.compose.runtime.CompositionLocalProvider(LocalHomeOpen provides actions.onOpen) {
        PullToRefreshBox(
            isRefreshing = ui.refreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.fillMaxSize(),
            state = ptr,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = ptr,
                    isRefreshing = ui.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                    containerColor = x.surface2,
                    color = ShadeTheme.accent.accent,
                )
            },
        ) {
            // Large windows: content at most 1200 wide, centered; the shelves stay horizontal inside it.
            LazyColumn(
                Modifier.align(Alignment.TopCenter).fillMaxHeight().widthIn(max = AdaptiveWidths.Home).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            ) {
                item(key = "header", contentType = "header") {
                    HomeHeader(ui.profileName, ui.avatarUrl, actions.onProfile, showAvatar = !LocalWindowLayout.current.useRail || LocalWindowLayout.current.compactHeight)
                }
                when (val feed = ui.feed) {
                    HomeFeed.Loading -> item(key = "skeleton") { HomeSkeleton() }
                    is HomeFeed.Error -> item(key = "error") {
                        ErrorState(onRetry = actions.onRetry, modifier = Modifier.padding(top = 48.dp))
                    }
                    is HomeFeed.Spotify -> homeSections(feed.sections, ui, actions)
                    is HomeFeed.Open -> {
                        homeSections(feed.sections, ui, actions)
                        if (feed.sections.none { it !is HomeSection.SmartLists } && ui.smartLists.isEmpty()) {
                            item(key = "empty") {
                                EmptyState(
                                    R.drawable.ic_music_note,
                                    stringResource(R.string.home_empty_title),
                                    stringResource(R.string.home_empty_body),
                                    Modifier.padding(top = 32.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/** The accent's light leak at the top of Home (hi-fi: the playing song's color falling on the stage). */
@Composable
private fun AccentLightLeak(modifier: Modifier) {
    val glow = ShadeTheme.accent.accent.copy(alpha = if (ShadeTheme.extras.isDark) 0.22f else 0.14f)
    Box(
        modifier.drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(glow, Color.Transparent),
                    center = Offset(size.width * 0.2f, 0f),
                    // Fades out inside the box (a radius past its height left a hard edge at the bottom).
                    radius = minOf(size.width * 1.1f, size.height),
                ),
            )
        },
    )
}

@Composable
private fun HomeHeader(name: String, avatarUrl: String, onProfile: () -> Unit, showAvatar: Boolean = true) {
    val x = ShadeTheme.extras
    val locale = uiLocale()
    val day = remember(locale) {
        LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
    }
    val g = remember { greeting(LocalTime.now().hour) }
    val text = if (name.isNotEmpty()) {
        stringResource(
            when (g) {
                Greeting.Morning -> R.string.home_greeting_morning
                Greeting.Afternoon -> R.string.home_greeting_day
                Greeting.Evening -> R.string.home_greeting_evening
                Greeting.Night -> R.string.home_greeting_night
            },
            name.substringBefore(' '),
        )
    } else {
        stringResource(
            when (g) {
                Greeting.Morning -> R.string.home_greeting_morning_plain
                Greeting.Afternoon -> R.string.home_greeting_day_plain
                Greeting.Evening -> R.string.home_greeting_evening_plain
                Greeting.Night -> R.string.home_greeting_night_plain
            },
        )
    }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(day, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = x.text2, maxLines = 1)
            Text(
                text, style = MaterialTheme.typography.headlineLarge, color = x.text1, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showAvatar) {
            Spacer(Modifier.width(8.dp))
            ProfileAvatar(avatarUrl, name, onClick = onProfile)
        }
    }
}

private fun LazyListScope.homeSections(sections: List<HomeSection>, ui: HomeUi, a: HomeActions) {
    var first = true
    for (s in sections) {
        val top = if (first) 18.dp else 24.dp
        val shown = when (s) {
            is HomeSection.SpotifyShelf -> if (HomeKeys.isRecentShelf(s.title)) {
                val key = HomeKeys.shelf(s.title)
                if (s.cards.isNotEmpty()) {
                    item(key = "$key-grid", contentType = "recent") {
                        RecentGrid(s.title, s.cards, ui.nowPlaying, top) { a.onSeeAll(key) }
                    }
                }
                s.cards.isNotEmpty()
            } else {
                val key = HomeKeys.shelf(s.title)
                cardStripSection(
                    key, s.cards, 132.dp, a.onOpen,
                    title = { s.title }, subtitle = { s.label.ifEmpty { null } },
                    seeAll = if (s.cards.size >= SEE_ALL_MIN) ({ a.onSeeAll(key) }) else null, topSpacing = top,
                )
                s.cards.isNotEmpty()
            }
            is HomeSection.RecentlyPlayed -> {
                if (s.cards.isNotEmpty()) {
                    item(key = "recent", contentType = "recent") {
                        RecentGrid(stringResource(R.string.home_recently_played), s.cards, ui.nowPlaying, top) { a.onSeeAll(HomeKeys.RECENT) }
                    }
                }
                s.cards.isNotEmpty()
            }
            HomeSection.SmartLists -> {
                val week = ui.week?.takeIf { it.listenedMs >= 60_000 }
                val show = ui.smartLists.isNotEmpty() || !ui.smartReady || week != null
                if (show) item(key = "smart") { SmartListsRow(ui.smartLists, ui.smartReady, week, top, a) }
                show
            }
            is HomeSection.PopularAndFresh -> {
                cardStripSection(
                    HomeKeys.TRENDING, s.trending.map { HomeRepository.albumCard(it) }, 132.dp, a.onOpen,
                    title = { stringResource(R.string.home_trending_week) },
                    subtitle = { stringResource(R.string.home_trending_week_sub) },
                    seeAll = { a.onSeeAll(HomeKeys.TRENDING) }, topSpacing = top,
                )
                if (s.fresh.isNotEmpty()) {
                    item(key = "fresh-header") {
                        SectionHeader(
                            stringResource(R.string.home_new_releases),
                            Modifier.padding(top = if (s.trending.isEmpty()) top else 24.dp),
                            subtitle = stringResource(R.string.home_new_releases_sub),
                        )
                    }
                    item(key = "fresh-strip") { FreshStrip(s.fresh, a.onOpen) }
                }
                s.trending.isNotEmpty() || s.fresh.isNotEmpty()
            }
            is HomeSection.YourPlaylists -> {
                cardStripSection(
                    HomeKeys.PLAYLISTS, s.cards, 116.dp, a.onOpen,
                    title = { stringResource(R.string.home_your_playlists) },
                    seeAll = when (s.link) {
                        SectionLink.Library -> a.onLibrary
                        SectionLink.SeeAll -> ({ a.onSeeAll(HomeKeys.PLAYLISTS) })
                        SectionLink.None -> null
                    },
                    topSpacing = top,
                )
                s.cards.isNotEmpty()
            }
            is HomeSection.SavedAlbums -> {
                cardStripSection(
                    HomeKeys.ALBUMS, s.cards, 116.dp, a.onOpen,
                    title = { stringResource(R.string.home_saved_albums) },
                    seeAll = if (s.cards.size >= SEE_ALL_MIN) ({ a.onSeeAll(HomeKeys.ALBUMS) }) else null, topSpacing = top,
                )
                s.cards.isNotEmpty()
            }
            is HomeSection.FollowedArtists -> {
                cardStripSection(
                    HomeKeys.ARTISTS, s.cards, 96.dp, a.onOpen,
                    title = { stringResource(R.string.home_followed_artists) },
                    seeAll = if (s.cards.size >= SEE_ALL_MIN) ({ a.onSeeAll(HomeKeys.ARTISTS) }) else null, topSpacing = top,
                )
                s.cards.isNotEmpty()
            }
            is HomeSection.ForYou -> {
                cardStripSection(
                    HomeKeys.FOR_YOU, s.cards, 116.dp, a.onOpen,
                    title = { stringResource(R.string.home_for_you) },
                    subtitle = { if (s.seedName.isEmpty()) null else stringResource(R.string.home_fans_also_like, s.seedName) },
                    seeAll = if (s.cards.size >= SEE_ALL_MIN) ({ a.onSeeAll(HomeKeys.FOR_YOU) }) else null, topSpacing = top,
                )
                s.cards.isNotEmpty()
            }
            is HomeSection.PopularArtists -> {
                cardStripSection(
                    HomeKeys.POPULAR_ARTISTS, s.cards, 96.dp, a.onOpen,
                    title = { stringResource(R.string.home_popular_artists) },
                    subtitle = { stringResource(R.string.home_popular_artists_sub) },
                    seeAll = if (s.cards.size >= SEE_ALL_MIN) ({ a.onSeeAll(HomeKeys.POPULAR_ARTISTS) }) else null,
                    topSpacing = top,
                )
                s.cards.isNotEmpty()
            }
            is HomeSection.MoreTrendingAlbums -> {
                cardStripSection(
                    HomeKeys.MORE_TRENDING, s.cards, 116.dp, a.onOpen,
                    title = { stringResource(R.string.home_more_trending) },
                    seeAll = if (s.cards.size >= SEE_ALL_MIN) ({ a.onSeeAll(HomeKeys.MORE_TRENDING) }) else null,
                    topSpacing = top,
                )
                s.cards.isNotEmpty()
            }
        }
        if (shown) first = false
    }
}

/** Tiles shown by a recent grid (2×2 … 2×3 on phones, 3×2 on wider windows). */
private const val RECENT_MAX = 6

/**
 * "Son çalınanlar" (ours) and Spotify's "Yakınlarda Çalınanlar" shelf: RecentTile grid (56 high, 8 gaps), 2 columns on
 * phones (up to 2×3), 3 on medium / expanded windows; "Tümünü gör" past [RECENT_MAX].
 */
@Composable
private fun RecentGrid(title: String, cards: List<Card>, now: NowPlayingState, top: androidx.compose.ui.unit.Dp, onSeeAll: () -> Unit) {
    val a = LocalHomeOpen.current
    val columns = if (LocalWindowLayout.current.isCompact) 2 else 3
    // Full rows: 3 / 5 cards on a 2-column grid would leave a hole, so an odd tail moves to "Tümünü gör".
    val n = cards.size.coerceAtMost(RECENT_MAX).let { if (it > columns && it % columns != 0) it - it % columns else it }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            title, Modifier.padding(top = top),
            actionLabel = if (cards.size > n) stringResource(R.string.home_see_all) else null,
            onAction = onSeeAll,
        )
        val shown = cards.take(n)
        val px = with(LocalDensity.current) { 44.dp.roundToPx() }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c ->
                        val playing = now.playing && (c.destination as? Destination.Album)?.id == now.albumId && now.albumId.isNotEmpty()
                        if (c.destination == Destination.Liked) {
                            LikedRecentTile(cardTitle(c), { a(c) }, Modifier.weight(1f))
                        } else {
                            RecentTile(
                                cardTitle(c), remember(c.images) { c.images.urlFor(px) }, { a(c) }, Modifier.weight(1f),
                                kind = c.artworkKind(), isPlaying = playing,
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Card taps of the recent grids (provided by [HomeContent], so the grid items need not capture the actions). */
private val LocalHomeOpen = androidx.compose.runtime.staticCompositionLocalOf<(Card) -> Unit> { {} }

/** [RecentTile] for Liked Songs (gradient cover instead of artwork). */
@Composable
private fun LikedRecentTile(title: String, onClick: () -> Unit, modifier: Modifier) {
    val x = ShadeTheme.extras
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(x.surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LikedCover(44.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            title, Modifier.weight(1f), style = ShadeTextStyles.recentTitle, color = x.text1,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Senin için yapıldı": 168×104 cards built on this device (stats SmartListsService). */
@Composable
private fun SmartListsRow(lists: List<SmartList>, ready: Boolean, week: WeekStats?, top: androidx.compose.ui.unit.Dp, a: HomeActions) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            stringResource(R.string.home_made_for_you), Modifier.padding(top = top),
            subtitle = stringResource(R.string.home_made_for_you_sub),
            actionLabel = if (lists.size > 3) stringResource(R.string.home_see_all) else null,
            onAction = { a.onSeeAll(HomeKeys.SMART) },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (week != null) item(key = "week") { WeekStatsCard(week, a.onStats) }
            if (lists.isEmpty() && !ready) {
                items(3) { SkeletonBox(Modifier.size(168.dp, 104.dp), RoundedCornerShape(12.dp)) }
            } else {
                items(lists, key = { it.id }) { l ->
                    MadeForYouCard(smartListOverline(l), smartListTitle(l), smartListTint(l), { a.onOpenSmart(l) })
                }
            }
        }
    }
}

/**
 * "Bu hafta" card (SCREENS #3): 168×104 like the made-for-you cards — mono overline in accent, the week's listening
 * time big in mono (units small), the play count under it; tap = İstatistikler.
 */
@Composable
private fun WeekStatsCard(week: WeekStats, onClick: () -> Unit) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent.accent
    val shape = RoundedCornerShape(12.dp)
    val totalMin = week.listenedMs / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    val hUnit = stringResource(R.string.profile_unit_h)
    val mUnit = stringResource(R.string.profile_unit_min)
    val plain = if (h > 0) "$h $hUnit $m $mUnit" else "$m $mUnit"
    val cd = stringResource(R.string.home_week_card_cd, plain)
    val unit = SpanStyle(fontSize = 13.sp, color = x.text2, fontWeight = FontWeight.Medium)
    Box(
        Modifier
            .size(168.dp, 104.dp)
            .clip(shape)
            .background(x.surface2)
            .border(1.dp, x.surface3, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = cd },
    ) {
        ShadeIcon(R.drawable.ic_stats, null, Modifier.align(Alignment.TopEnd).padding(10.dp), tint = accent, size = 20.dp)
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.home_this_week_stats).uppercase(uiLocale()),
                style = ShadeMono.overline, color = accent, maxLines = 1,
            )
            Column {
                Text(
                    buildAnnotatedString {
                        if (h > 0) {
                            append(h.toString())
                            withStyle(unit) { append(" $hUnit ") }
                        }
                        append(m.toString())
                        withStyle(unit) { append(" $mUnit") }
                    },
                    style = ShadeMono.large.copy(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
                    color = x.text1, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                )
                Text(
                    stringResource(R.string.home_week_streams, week.streams),
                    style = ShadeMono.small, color = x.text3, maxLines = 1,
                )
            }
        }
    }
}

/** "Yeni çıkanlar": 116 strip, "<artist> · Tekli · 27 Eyl". */
@Composable
private fun FreshStrip(fresh: List<FreshRelease>, onOpen: (Card) -> Unit) {
    val locale = uiLocale()
    val fmt = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(fresh.size, key = { fresh[it].album.id.ifEmpty { "f$it" } }) { i ->
            val f = fresh[i]
            val card = remember(f) { HomeRepository.albumCard(f.album) }
            val date = remember(f.releaseDate, fmt) {
                runCatching { LocalDate.parse(f.releaseDate).format(fmt) }.getOrDefault("")
            }
            val sub = listOf(f.album.artists.firstOrNull()?.name.orEmpty(), albumTypeText(f.type), date)
                .filter { it.isNotEmpty() }.joinToString(" · ")
            BrowseTile(card, { onOpen(card) }, size = 116.dp, subtitleOverride = sub)
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        SkeletonLine(120.dp, Modifier.padding(horizontal = 20.dp), height = 14.dp)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(2) { SkeletonBox(Modifier.weight(1f).height(56.dp), RoundedCornerShape(10.dp)) }
                }
            }
        }
        repeat(2) {
            Spacer(Modifier.height(28.dp))
            SkeletonLine(140.dp, Modifier.padding(horizontal = 20.dp), height = 14.dp)
            Spacer(Modifier.height(12.dp))
            CoverStripSkeleton(Modifier.padding(start = 20.dp))
        }
    }
}

// ------------------------------------------------------------------ "Tümünü gör"

@Composable
fun HomeSectionScreen(contentPadding: PaddingValues, key: String) {
    val vm: HomeSectionViewModel = viewModel()
    val nav = LocalNavigator.current
    val feed by vm.feed.collectAsStateWithLifecycle()
    val smart by vm.smartLists.collectAsStateWithLifecycle()
    val smartReady by vm.smartReady.collectAsStateWithLifecycle()
    val title = when {
        HomeKeys.isShelf(key) -> HomeKeys.shelfTitle(key)
        key == HomeKeys.RECENT -> stringResource(R.string.home_recently_played)
        key == HomeKeys.SMART -> stringResource(R.string.home_made_for_you)
        key == HomeKeys.TRENDING -> stringResource(R.string.home_trending_week)
        key == HomeKeys.FRESH -> stringResource(R.string.home_new_releases)
        key == HomeKeys.PLAYLISTS -> stringResource(R.string.home_your_playlists)
        key == HomeKeys.ALBUMS -> stringResource(R.string.home_saved_albums)
        key == HomeKeys.ARTISTS -> stringResource(R.string.home_followed_artists)
        key == HomeKeys.FOR_YOU -> stringResource(R.string.home_for_you)
        key == HomeKeys.POPULAR_ARTISTS -> stringResource(R.string.home_popular_artists)
        key == HomeKeys.MORE_TRENDING -> stringResource(R.string.home_more_trending)
        else -> ""
    }
    val cards = remember(feed, key) { HomeKeys.cards(feed, key) }
    HomeSectionContent(
        title = title,
        loading = if (key == HomeKeys.SMART) !smartReady && smart.isEmpty() else feed is HomeFeed.Loading,
        cards = if (key == HomeKeys.SMART) null else cards.orEmpty(),
        smartLists = if (key == HomeKeys.SMART) smart else null,
        onBack = nav::back,
        onOpen = { nav.open(it.destination) },
        onOpenSmart = { nav.navigate(Screen.SmartList(it.id)) },
        contentPadding = contentPadding,
    )
}

@Composable
fun HomeSectionContent(
    title: String,
    loading: Boolean,
    cards: List<Card>?,
    smartLists: List<SmartList>?,
    onBack: () -> Unit,
    onOpen: (Card) -> Unit,
    onOpenSmart: (SmartList) -> Unit,
    contentPadding: PaddingValues,
) {
    val x = ShadeTheme.extras
    Column(Modifier.fillMaxSize().background(x.surface0).statusBarsPadding()) {
        ShadeTopBar(onNavigate = onBack, title = title)
        val artists = cards?.isNotEmpty() == true && cards.all { it.shape == CardShape.Circle }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Phones: 2 columns (artists 3); wider windows fit as many ~164dp tiles (artists ~104dp) as there is room for.
            val columns = if (artists) gridColumns(maxWidth, minTile = 104.dp, min = 3, max = 8) else gridColumns(maxWidth)
            val tile = (maxWidth - 40.dp - 12.dp * (columns - 1)) / columns
            val bottom = contentPadding.calculateBottomPadding() + 24.dp
            when {
                loading -> Column(Modifier.padding(top = 8.dp)) { repeat(3) { CoverStripSkeleton(Modifier.padding(start = 20.dp, bottom = 24.dp), size = tile) } }
                smartLists != null && smartLists.isEmpty() || cards != null && cards.isEmpty() -> EmptyState(
                    R.drawable.ic_music_note, stringResource(R.string.home_section_empty_title), null,
                    Modifier.padding(top = 48.dp),
                )
                else -> LazyVerticalGrid(
                    GridCells.Fixed(columns),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottom),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (smartLists != null) {
                        items(smartLists.size, key = { smartLists[it].id }) { i ->
                            val l = smartLists[i]
                            MadeForYouCard(smartListOverline(l), smartListTitle(l), smartListTint(l), { onOpenSmart(l) }, width = tile)
                        }
                    } else if (cards != null) {
                        items(cards.size, key = { stripKey(cards[it], it) }) { i ->
                            val c = cards[i]
                            BrowseTile(c, { onOpen(c) }, size = tile)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ previews

private fun previewCards(n: Int, prefix: String, circle: Boolean = false) = List(n) { i ->
    Card(
        "$prefix ${i + 1}",
        if (circle) CardSubtitle.ArtistKind(false, "TR") else CardSubtitle.ArtistYear("Kül & Neon", "2024"),
        destination = if (circle) Destination.Artist("a$i") else Destination.Album("al$i"),
        shape = if (circle) CardShape.Circle else CardShape.Square,
        placeholder = if (circle) CardPlaceholder.Artist else CardPlaceholder.Album,
    )
}

internal fun previewHomeFeed(): HomeFeed = HomeFeed.Spotify(
    12, 4,
    listOf(
        HomeSection.RecentlyPlayed(
            listOf(
                Card("Gece Sürüşü", destination = Destination.Album("1")),
                Card("", CardSubtitle.Songs(42), destination = Destination.Liked),
                Card("Kül & Neon", destination = Destination.Album("2")),
                Card("Daily Mix 2", destination = Destination.Album("3")),
            ),
            SectionLink.None,
        ),
        HomeSection.SpotifyShelf("Sana özel", "", previewCards(6, "Daily Mix")),
        HomeSection.SmartLists,
        HomeSection.PopularAndFresh(
            emptyList(),
            List(4) { i ->
                FreshRelease(
                    Album(id = "f$i", name = "Yeni albüm ${i + 1}", artists = listOf(ArtistRef("x", "Mira Tan"))),
                    AlbumType.Single, "2026-09-2$i",
                )
            },
        ),
        HomeSection.PopularArtists(previewCards(5, "Sanatçı", circle = true)),
    ),
)

internal fun previewSmartLists() = listOf(
    SmartList("month", SmartLists.Kind.Month, tracks = List(24) { Track(id = "t$it") }),
    SmartList("forgotten", SmartLists.Kind.Forgotten, tracks = List(48) { Track(id = "t$it") }),
    SmartList("mix:1", SmartLists.Kind.Mix, number = 1, artists = listOf("Kül & Neon", "Mira Tan")),
)

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun HomePreview() {
    ShadeTubeThemeOf {
        HomeContent(
            HomeUi(previewHomeFeed(), profileName = "Deniz", smartLists = previewSmartLists(), week = WeekStats(12 * 3_600_000L + 34 * 60_000L, 214)),
            HomeActions(),
            PaddingValues(bottom = 160.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 600)
@Composable
private fun HomeLoadingPreview() {
    ShadeTubeThemeOf { HomeContent(HomeUi(HomeFeed.Loading), HomeActions(), PaddingValues()) }
}
