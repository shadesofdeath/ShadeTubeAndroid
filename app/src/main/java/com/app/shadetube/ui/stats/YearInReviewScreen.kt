package com.app.shadetube.ui.stats

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.pick
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.library.Destination
import com.app.shadetube.stats.StatsAlbum
import com.app.shadetube.stats.StatsArtwork
import com.app.shadetube.stats.StatsHeatmap
import com.app.shadetube.stats.StatsPlay
import com.app.shadetube.stats.StatsTrack
import com.app.shadetube.stats.systemLocalClock
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.SectionHeader
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.SkeletonBox
import com.app.shadetube.ui.components.TrackListSkeleton
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.components.coverGlow
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.settings.SettingsCard
import com.app.shadetube.ui.settings.SettingsScaffold
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Month
import java.time.format.TextStyle
import java.util.Date

data class YearUi(
    val loaded: Boolean = false,
    val year: Int = 0,
    val listenedMs: Long = 0,
    val streams: Int = 0,
    val distinctTracks: Int = 0,
    val distinctArtists: Int = 0,
    val newArtists: Int = 0,
    val activeDays: Int = 0,
    val longestStreak: Int = 0,
    val streakFrom: Long = 0,
    val streakTo: Long = 0,
    val monthMs: List<Long> = List(12) { 0L },
    val weekdayMs: List<Long> = List(7) { 0L },
    val topMonth: Int = -1,
    val topWeekday: Int = -1,
    val firstStream: StatsPlay? = null,
    val topTracks: List<StatsTrack> = emptyList(),
    val topArtists: List<ArtistEntry> = emptyList(),
    val topAlbums: List<StatsAlbum> = emptyList(),
    val heatmap: StatsHeatmap? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class YearInReviewViewModel(private val year: Int) : ViewModel() {
    val state: StateFlow<YearUi> = combine(AppGraph.stats.version, AppGraph.statsArtwork.version) { _, _ -> }
        .mapLatest { withContext(AppDispatchers.cpu) { compute() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), YearUi(year = year))

    private fun compute(): YearUi {
        val stats = AppGraph.stats
        if (!stats.store.isLoaded) return YearUi(year = year)
        val art = AppGraph.statsArtwork
        val y = stats.summarizeYear(year, systemLocalClock(), topN = 5)
        val wants = ArrayList<StatsArtwork.Want>()
        val tracks = y.topTracks.map { it.copy(track = art.enrich(it.track, wants)) }
        val albums = y.topAlbums.map { art.enrich(it, wants) }
        val artists = y.topArtists.map { art.enrich(it, wants) }
        val first = y.firstStream?.let { it.copy(track = art.enrich(it.track, wants)) }
        art.fetch(wants)
        art.fetchArtistImages(artists.mapNotNull { a -> a.artist.id.takeIf { it.isNotEmpty() } })
        return YearUi(
            loaded = true, year = year,
            listenedMs = y.listenedMs, streams = y.streams, distinctTracks = y.distinctTracks, distinctArtists = y.distinctArtists,
            newArtists = y.newArtists, activeDays = y.activeDays, longestStreak = y.longestStreak,
            streakFrom = y.streakFrom, streakTo = y.streakTo,
            monthMs = y.monthMs.toList(), weekdayMs = y.weekdayMs.toList(), topMonth = y.topMonth, topWeekday = y.topWeekday,
            firstStream = first, topTracks = tracks, topArtists = artists.map { ArtistEntry(it, art.artistImages(it.artist)) },
            topAlbums = albums, heatmap = y.heatmap,
        )
    }

    fun play(index: Int, name: String) {
        val t = state.value.topTracks.map { it.track }
        if (t.isNotEmpty()) AppGraph.playerControls.playContext(t, index, "stats:year:$year", name)
    }
}

private fun javaLocale() = java.util.Locale.forLanguageTag(Locale.current.toLanguageTag())

/** Yıl özeti: one year's summary, charts, top 5 lists and the shareable 9:16 story card. */
@Composable
fun YearInReviewScreen(contentPadding: PaddingValues, year: Int) {
    val vm: YearInReviewViewModel = viewModel(key = "year-$year") { YearInReviewViewModel(year) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent.accent
    val px44 = with(LocalDensity.current) { 44.dp.roundToPx() }
    val title = stringResource(R.string.stats_year_title, year)
    val locale = javaLocale()
    val scope = rememberCoroutineScope()

    // Story texts (localized here, drawn by StoryCard).
    val totalMinutes = state.listenedMs / 60_000
    val story = StoryData(
        year = year,
        overline = stringResource(R.string.stats_year_review),
        minutes = "%,d".format(locale, totalMinutes),
        minutesLabel = stringResource(R.string.stats_story_minutes),
        countsLine = stringResource(R.string.stats_story_counts, state.streams, state.distinctTracks, state.distinctArtists),
        topSongsTitle = stringResource(R.string.stats_top_songs),
        topSongs = state.topTracks.map { it.track.name to it.track.artistLine },
        topArtistTitle = stringResource(R.string.stats_story_top_artist),
        topArtist = state.topArtists.firstOrNull()?.stat?.artist?.name.orEmpty(),
        footer = "SHADETUBE · $year",
    )
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val accentArgb = accent.toArgb()
    LaunchedEffect(story, accentArgb, state.loaded) {
        if (!state.loaded || state.streams == 0) return@LaunchedEffect
        bitmap = withContext(AppDispatchers.cpu) { StoryCard.render(context, story, accentArgb) }
    }
    val shareFailed = stringResource(R.string.stats_share_unavailable)
    val shareTitle = stringResource(R.string.stats_share_story)
    fun share() {
        val b = bitmap ?: return
        scope.launch {
            val uri = withContext(AppDispatchers.io) { StoryCard.saveForSharing(context, b, year) }
            if (uri == null || !StoryCard.share(context, uri, shareTitle)) nav.message(shareFailed)
        }
    }

    SettingsScaffold(
        title, contentPadding,
        actions = {
            if (bitmap != null) ShadeIconButton(R.drawable.ic_share, shareTitle, ::share, tint = x.text1, size = 48.dp)
        },
    ) {
        if (!state.loaded) {
            item(key = "loading") {
                SkeletonBox(Modifier.padding(20.dp).fillMaxWidth().height(160.dp), ShadeRadius.card)
                TrackListSkeleton()
            }
            return@SettingsScaffold
        }
        if (state.streams == 0) {
            item(key = "empty") {
                EmptyState(R.drawable.ic_calendar, stringResource(R.string.stats_year_empty_title, year), stringResource(R.string.stats_year_empty_body))
            }
            return@SettingsScaffold
        }
        item(key = "hero") { YearHero(state) }
        item(key = "counters") { YearCounters(state, locale) }
        state.firstStream?.let { first ->
            item(key = "first") {
                Spacer(Modifier.height(24.dp))
                SectionHeader(stringResource(R.string.stats_first_stream), subtitle = DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(first.startedAt * 1000)))
                TrackRow(
                    title = first.track.name, subtitle = first.track.artistLine,
                    artworkUrl = first.track.album.images.pick(px44)?.url,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                )
            }
        }
        item(key = "h_songs") {
            Spacer(Modifier.height(24.dp))
            SectionHeader(stringResource(R.string.stats_top_songs))
        }
        state.topTracks.forEachIndexed { i, st ->
            item(key = "s$i") {
                TrackRow(
                    title = st.track.name, subtitle = st.track.artistLine, artworkUrl = st.track.album.images.pick(px44)?.url,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    trailing = { Text(stringResource(R.string.stats_plays, st.streams), style = ShadeMono.small, color = x.text2) },
                    onClick = { vm.play(i, title) },
                )
            }
        }
        item(key = "h_artists") {
            Spacer(Modifier.height(24.dp))
            SectionHeader(stringResource(R.string.stats_top_artists))
        }
        state.topArtists.forEachIndexed { i, a ->
            item(key = "a$i") {
                TrackRow(
                    title = a.stat.artist.name, subtitle = com.app.shadetube.ui.settings.durationText(a.stat.listenedMs),
                    artworkUrl = a.images.pick(px44)?.url, artworkKind = ArtworkKind.Artist,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    trailing = { Text(stringResource(R.string.stats_plays, a.stat.streams), style = ShadeMono.small, color = x.text2) },
                    onClick = { if (a.stat.artist.id.isNotEmpty()) nav.open(Destination.Artist(a.stat.artist.id)) },
                )
            }
        }
        if (state.topAlbums.isNotEmpty()) {
            item(key = "h_albums") {
                Spacer(Modifier.height(24.dp))
                SectionHeader(stringResource(R.string.stats_top_albums))
            }
            state.topAlbums.forEachIndexed { i, al ->
                item(key = "al$i") {
                    TrackRow(
                        title = al.album.name,
                        subtitle = if (al.variousArtists) stringResource(R.string.stats_various_artists) else al.artist,
                        artworkUrl = al.album.images.pick(px44)?.url, artworkKind = ArtworkKind.Album,
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        trailing = { Text(stringResource(R.string.stats_plays, al.streams), style = ShadeMono.small, color = x.text2) },
                        onClick = { if (al.album.id.isNotEmpty()) nav.open(Destination.Album(al.album.id)) },
                    )
                }
            }
        }
        item(key = "months") {
            Spacer(Modifier.height(24.dp))
            SettingsCard {
                Text(stringResource(R.string.stats_by_month), style = MaterialTheme.typography.titleMedium, color = x.text1)
                if (state.topMonth >= 0) {
                    Text(
                        stringResource(R.string.stats_top_month, Month.of(state.topMonth + 1).getDisplayName(TextStyle.FULL_STANDALONE, locale)),
                        style = MaterialTheme.typography.bodySmall, color = x.text2,
                    )
                }
                Spacer(Modifier.height(12.dp))
                BarChart(
                    values = state.monthMs,
                    labels = (1..12).map { Month.of(it).getDisplayName(TextStyle.NARROW_STANDALONE, locale) },
                    highlight = state.topMonth,
                )
            }
        }
        item(key = "weekdays") {
            Spacer(Modifier.height(16.dp))
            val days = weekdayNames()
            SettingsCard {
                Text(stringResource(R.string.stats_by_weekday), style = MaterialTheme.typography.titleMedium, color = x.text1)
                if (state.topWeekday >= 0) {
                    Text(
                        stringResource(R.string.stats_top_weekday, weekdayNames(short = false)[state.topWeekday]),
                        style = MaterialTheme.typography.bodySmall, color = x.text2,
                    )
                }
                Spacer(Modifier.height(12.dp))
                BarChart(values = state.weekdayMs, labels = days.map { it.take(2) }, highlight = state.topWeekday)
            }
        }
        state.heatmap?.takeIf { it.totalMs > 0 }?.let { map ->
            item(key = "heatmap") {
                Spacer(Modifier.height(16.dp))
                SettingsCard {
                    Text(stringResource(R.string.stats_heatmap), style = MaterialTheme.typography.titleMedium, color = x.text1)
                    Spacer(Modifier.height(12.dp))
                    Heatmap(map)
                }
            }
        }
        item(key = "story") {
            Spacer(Modifier.height(24.dp))
            SectionHeader(stringResource(R.string.stats_share_story))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val b = bitmap
                val cd = stringResource(R.string.stats_story_cd, year)
                Box(
                    Modifier
                        .width(200.dp)
                        .aspectRatio(9f / 16f)
                        .clip(ShadeRadius.card)
                        .background(x.surface2)
                        .border(1.dp, x.surface3, ShadeRadius.card)
                        .semantics { contentDescription = cd },
                ) {
                    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    else SkeletonBox(Modifier.fillMaxWidth().aspectRatio(9f / 16f))
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(shareTitle, ::share, icon = R.drawable.ic_share, enabled = b != null)
            }
        }
    }
}

@Composable
private fun YearHero(state: YearUi) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    val locale = javaLocale()
    Box(Modifier.fillMaxWidth().coverGlow(spread = 1.4f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                stringResource(R.string.stats_year_review).uppercase(locale) + " · " + state.year,
                style = ShadeMono.overline, color = accent.accent,
            )
            Spacer(Modifier.height(8.dp))
            Text("%,d".format(locale, state.listenedMs / 60_000), style = ShadeMono.display, color = x.text1, maxLines = 1)
            Text(stringResource(R.string.stats_story_minutes), style = MaterialTheme.typography.titleMedium, color = x.text2)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YearCounters(state: YearUi, locale: java.util.Locale) {
    val df = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 2,
    ) {
        val mod = Modifier.weight(1f)
        YearTile("%,d".format(locale, state.streams), stringResource(R.string.stats_streams), mod)
        YearTile("%,d".format(locale, state.distinctTracks), stringResource(R.string.stats_songs), mod)
        YearTile("%,d".format(locale, state.distinctArtists), stringResource(R.string.stats_artists), mod)
        YearTile("%,d".format(locale, state.newArtists), stringResource(R.string.stats_new_artists), mod)
        YearTile("%,d".format(locale, state.activeDays), stringResource(R.string.stats_active_days), mod)
        YearTile(
            pluralStringResource(R.plurals.stats_days, state.longestStreak, state.longestStreak),
            stringResource(R.string.stats_longest_streak) +
                if (state.streakFrom > 0) "\n" + df.format(Date(state.streakFrom * 1000)) + " – " + df.format(Date(state.streakTo * 1000)) else "",
            mod,
        )
    }
}

@Composable
private fun YearTile(value: String, label: String, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    Column(
        modifier
            .clip(ShadeRadius.card)
            .background(x.surface2)
            .border(1.dp, x.surface3, ShadeRadius.card)
            .padding(14.dp),
    ) {
        Text(value, style = ShadeMono.large, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 3)
    }
}

/** Vertical bars (months / weekdays): accent for [highlight], accent.progressTrack for the rest; mono labels. */
@Composable
internal fun BarChart(values: List<Long>, labels: List<String>, highlight: Int, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val n = values.size
            val gap = 6.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            values.forEachIndexed { i, v ->
                val h = (v / max) * size.height
                val left = i * (w + gap)
                drawRoundRect(x.surface3, Offset(left, 0f), Size(w, size.height), CornerRadius(3.dp.toPx()))
                if (h > 0f) {
                    drawRoundRect(
                        if (i == highlight) accent.accent else accent.progressTrack,
                        Offset(left, size.height - h), Size(w, h), CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, l ->
                Text(
                    l, Modifier.weight(1f), style = ShadeMono.small.copy(fontSize = ShadeMono.overline.fontSize),
                    color = if (i == highlight) accent.accent else x.text3, maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

