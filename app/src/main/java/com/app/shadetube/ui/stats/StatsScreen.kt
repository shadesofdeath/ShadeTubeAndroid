package com.app.shadetube.ui.stats

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.catalog.pick
import com.app.shadetube.library.Destination
import com.app.shadetube.stats.ListenStatsService
import com.app.shadetube.stats.StatsPeriod
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.LinearProgress
import com.app.shadetube.ui.components.MadeForYouCard
import com.app.shadetube.ui.components.SectionHeader
import com.app.shadetube.ui.components.SegmentedPill
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.components.SkeletonBox
import com.app.shadetube.ui.components.SkeletonLine
import com.app.shadetube.ui.components.TrackListSkeleton
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.settings.SettingsCard
import com.app.shadetube.ui.settings.SettingsGroupHeader
import com.app.shadetube.ui.settings.SettingsRow
import com.app.shadetube.ui.settings.SettingsScaffold
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

private val PERIODS = listOf(StatsPeriod.Week, StatsPeriod.Month, StatsPeriod.All)
private const val COLLAPSED = 5

/** Spotify's export: JSON files, or the ZIP as downloaded (some pickers report it as octet-stream). */
internal val HISTORY_MIME_TYPES = arrayOf("application/json", "application/zip", "application/x-zip-compressed", "application/octet-stream", "text/plain")

/** İstatistikler (SCREENS 20). */
@Composable
fun StatsScreen(contentPadding: PaddingValues) {
    val vm: StatsViewModel = viewModel { StatsViewModel() }
    val nav = LocalNavigator.current
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    val result by vm.importResult.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.import(uris) }
    var expandSongs by rememberSaveable { mutableStateOf(false) }
    var expandArtists by rememberSaveable { mutableStateOf(false) }
    var expandAlbums by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val x = ShadeTheme.extras
    val px44 = with(LocalDensity.current) { 44.dp.roundToPx() }
    val title = stringResource(R.string.stats_title)
    val more = stringResource(R.string.home_see_all)
    val less = stringResource(R.string.stats_show_less)

    SettingsScaffold(title, contentPadding) {
        item(key = "period") {
            SegmentedPill(
                options = listOf(stringResource(R.string.stats_range_7d), stringResource(R.string.stats_range_30d), stringResource(R.string.stats_range_all)),
                selectedIndex = PERIODS.indexOf(state.period),
                onSelect = { vm.setPeriod(PERIODS[it]) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (!state.loaded) {
            item(key = "skeleton") {
                Column(Modifier.padding(20.dp)) {
                    SkeletonLine(120.dp)
                    Spacer(Modifier.height(12.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.7f).height(44.dp))
                }
                TrackListSkeleton()
            }
            return@SettingsScaffold
        }
        item(key = "total") { TotalCard(state) }
        if (state.streams == 0) {
            item(key = "empty") {
                EmptyState(
                    icon = R.drawable.ic_stats,
                    title = stringResource(R.string.stats_empty_title),
                    body = stringResource(R.string.stats_empty_body),
                    actionLabel = stringResource(R.string.stats_import_spotify),
                    onAction = { picker.launch(HISTORY_MIME_TYPES) },
                    actionIcon = R.drawable.ic_import,
                )
            }
        } else {
            // Top songs.
            item(key = "h_songs") {
                Spacer(Modifier.height(24.dp))
                SectionHeader(
                    stringResource(R.string.stats_top_songs),
                    actionLabel = if (state.topTracks.size > COLLAPSED) (if (expandSongs) less else more) else null,
                    onAction = { expandSongs = !expandSongs },
                )
            }
            val songs = if (expandSongs) state.topTracks else state.topTracks.take(COLLAPSED)
            songs.forEachIndexed { i, st ->
                item(key = "s:${st.track.id}:${st.track.name}:$i") {
                    TrackRow(
                        title = st.track.name,
                        subtitle = st.track.artistLine,
                        artworkUrl = st.track.album.images.pick(px44)?.url,
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp),
                        trailing = { Text(stringResource(R.string.stats_plays, st.streams), style = ShadeMono.small, color = x.text2) },
                        onClick = { vm.play(state.topTracks.map { it.track }, i, title) },
                    )
                }
            }
            // Top artists.
            item(key = "h_artists") {
                Spacer(Modifier.height(24.dp))
                SectionHeader(
                    stringResource(R.string.stats_top_artists),
                    actionLabel = if (state.topArtists.size > COLLAPSED) (if (expandArtists) less else more) else null,
                    onAction = { expandArtists = !expandArtists },
                )
            }
            val artists = if (expandArtists) state.topArtists else state.topArtists.take(COLLAPSED)
            artists.forEachIndexed { i, a ->
                item(key = "a:${a.stat.artist.id}:${a.stat.artist.name}:$i") {
                    TrackRow(
                        title = a.stat.artist.name,
                        subtitle = durationTextFor(a.stat.listenedMs),
                        artworkUrl = a.images.pick(px44)?.url,
                        artworkKind = ArtworkKind.Artist,
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp),
                        trailing = { Text(stringResource(R.string.stats_plays, a.stat.streams), style = ShadeMono.small, color = x.text2) },
                        onClick = { if (a.stat.artist.id.isNotEmpty()) nav.open(Destination.Artist(a.stat.artist.id)) },
                    )
                }
            }
            // Top albums.
            item(key = "h_albums") {
                Spacer(Modifier.height(24.dp))
                SectionHeader(
                    stringResource(R.string.stats_top_albums),
                    actionLabel = if (state.topAlbums.size > COLLAPSED) (if (expandAlbums) less else more) else null,
                    onAction = { expandAlbums = !expandAlbums },
                )
            }
            val albums = if (expandAlbums) state.topAlbums else state.topAlbums.take(COLLAPSED)
            albums.forEachIndexed { i, al ->
                item(key = "al:${al.album.id}:${al.album.name}:$i") {
                    TrackRow(
                        title = al.album.name,
                        subtitle = if (al.variousArtists) stringResource(R.string.stats_various_artists) else al.artist,
                        artworkUrl = al.album.images.pick(px44)?.url,
                        artworkKind = ArtworkKind.Album,
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp),
                        trailing = { Text(stringResource(R.string.stats_plays, al.streams), style = ShadeMono.small, color = x.text2) },
                        onClick = { if (al.album.id.isNotEmpty()) nav.open(Destination.Album(al.album.id)) },
                    )
                }
            }
            // Heat map.
            state.heatmap?.takeIf { it.totalMs > 0 }?.let { map ->
                item(key = "heatmap") {
                    Spacer(Modifier.height(24.dp))
                    SettingsCard {
                        Text(stringResource(R.string.stats_heatmap), style = MaterialTheme.typography.titleMedium, color = x.text1)
                        Text(stringResource(R.string.stats_heatmap_sub), style = MaterialTheme.typography.bodySmall, color = x.text2)
                        Spacer(Modifier.height(12.dp))
                        Heatmap(map)
                    }
                }
            }
        }
        // Years.
        if (state.years.isNotEmpty()) {
            item(key = "h_years") {
                Spacer(Modifier.height(24.dp))
                SectionHeader(stringResource(R.string.stats_year_review))
                val accent = ShadeTheme.accent.accent
                val overline = stringResource(R.string.stats_year_review)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.years, key = { it }) { year ->
                        MadeForYouCard(overline, year.toString(), accent, { nav.navigate(Screen.YearInReview(year)) })
                    }
                }
            }
        }
        // History.
        item(key = "g_history") { SettingsGroupHeader(stringResource(R.string.stats_history)) }
        item(key = "import") {
            Column {
                SettingsRow(
                    stringResource(R.string.stats_import_spotify), icon = R.drawable.ic_import,
                    subtitle = when {
                        !progress.active -> stringResource(R.string.stats_import_sub)
                        progress.merging -> stringResource(R.string.stats_import_merging)
                        else -> stringResource(R.string.stats_import_reading, progress.done, progress.total)
                    },
                    enabled = !progress.active,
                    onClick = { picker.launch(HISTORY_MIME_TYPES) },
                )
                if (progress.active) {
                    LinearProgress(
                        if (progress.total > 0 && !progress.merging) progress.done.toFloat() / progress.total else null,
                        Modifier.padding(horizontal = 20.dp),
                        height = 3.dp,
                    )
                }
            }
        }
        if (state.importedPlays > 0) {
            item(key = "remove_imported") {
                SettingsRow(
                    stringResource(R.string.stats_remove_imported), icon = R.drawable.ic_history,
                    subtitle = stringResource(R.string.stats_imported_count, state.importedPlays),
                    enabled = !progress.active, onClick = { confirm = "imported" },
                )
            }
        }
        if (state.totalPlays > 0) {
            item(key = "clear") {
                SettingsRow(
                    stringResource(R.string.stats_clear), icon = R.drawable.ic_trash, destructive = true,
                    enabled = !progress.active, onClick = { confirm = "clear" },
                )
            }
        }
    }

    when (confirm) {
        "imported" -> ShadeDialog(
            onDismissRequest = { confirm = null },
            title = stringResource(R.string.stats_remove_imported_title),
            text = stringResource(R.string.stats_remove_imported_body, state.importedPlays),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirm = null; vm.removeImported() },
            destructive = true,
        )
        "clear" -> ShadeDialog(
            onDismissRequest = { confirm = null },
            title = stringResource(R.string.stats_clear_title),
            text = stringResource(R.string.stats_clear_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirm = null; vm.clear() },
            destructive = true,
        )
    }
    result?.let { r ->
        ShadeDialog(
            onDismissRequest = vm::dismissImportResult,
            title = stringResource(R.string.stats_import_spotify),
            text = importResultText(r),
            confirmLabel = stringResource(R.string.action_ok),
            onConfirm = vm::dismissImportResult,
            dismissLabel = null,
        )
    }
}

@Composable
private fun importResultText(r: ListenStatsService.ImportOutcome): String = when (r) {
    ListenStatsService.ImportOutcome.Busy -> stringResource(R.string.stats_import_busy)
    ListenStatsService.ImportOutcome.Unavailable -> stringResource(R.string.stats_import_unavailable)
    is ListenStatsService.ImportOutcome.Failed -> stringResource(R.string.stats_import_failed, r.message)
    is ListenStatsService.ImportOutcome.Done -> buildString {
        append(
            when {
                !r.ok -> stringResource(R.string.stats_import_write_failed)
                r.noHistory -> stringResource(R.string.stats_import_no_history)
                r.nothingNew -> stringResource(R.string.stats_import_nothing_new)
                else -> stringResource(R.string.stats_import_done, r.added, r.duplicates)
            },
        )
        if (r.unreadableFiles > 0) append('\n').append(stringResource(R.string.stats_import_unreadable, r.unreadableFiles))
    }
}

@Composable
private fun durationTextFor(ms: Long): String = com.app.shadetube.ui.settings.durationText(ms)

/** Total listening (displayLarge mono 44: hours + minutes with small units) and three counters. */
@Composable
private fun TotalCard(state: StatsUiState) {
    val x = ShadeTheme.extras
    val totalMin = state.listenedMs / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    val unitStyle = SpanStyle(fontSize = MaterialTheme.typography.titleLarge.fontSize, color = x.text2)
    val hUnit = stringResource(R.string.profile_unit_h)
    val mUnit = stringResource(R.string.profile_unit_min)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.stats_total_time), style = MaterialTheme.typography.bodyMedium, color = x.text2)
        Text(
            buildAnnotatedString {
                if (h > 0) {
                    append(h.toString())
                    withStyle(unitStyle) { append(" $hUnit ") }
                }
                append(m.toString())
                withStyle(unitStyle) { append(" $mUnit") }
            },
            style = ShadeMono.display, color = x.text1, maxLines = 1, softWrap = false,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Counter(state.streams, stringResource(R.string.stats_streams))
            Counter(state.distinctTracks, stringResource(R.string.stats_songs))
            Counter(state.distinctArtists, stringResource(R.string.stats_artists))
        }
    }
}

@Composable
private fun Counter(value: Int, label: String) {
    val x = ShadeTheme.extras
    Column {
        Text("%,d".format(value), style = ShadeMono.large, color = x.text1, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 1)
    }
}

@Preview(widthDp = 412)
@Composable
private fun TotalCardPreview() {
    ShadeTubeThemeOf {
        Column(Modifier.padding(0.dp)) {
            TotalCard(StatsUiState(loaded = true, listenedMs = 123 * 3_600_000L + 45 * 60_000L, streams = 2345, distinctTracks = 812, distinctArtists = 230))
        }
    }
}
