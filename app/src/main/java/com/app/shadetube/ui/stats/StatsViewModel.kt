package com.app.shadetube.ui.stats

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.AppGraph
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.appScope
import com.app.shadetube.stats.HistoryImport
import com.app.shadetube.stats.ListenStatsService
import com.app.shadetube.stats.StatsAlbum
import com.app.shadetube.stats.StatsArtist
import com.app.shadetube.stats.StatsArtwork
import com.app.shadetube.stats.StatsHeatmap
import com.app.shadetube.stats.StatsPeriod
import com.app.shadetube.stats.StatsTrack
import com.app.shadetube.stats.systemLocalClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** A top artist with its picture (from the library or a lookup). */
data class ArtistEntry(val stat: StatsArtist, val images: List<Image>)

data class StatsUiState(
    val loaded: Boolean = false,
    val period: StatsPeriod = StatsPeriod.Month,
    val listenedMs: Long = 0,
    val streams: Int = 0,
    val distinctTracks: Int = 0,
    val distinctArtists: Int = 0,
    val topTracks: List<StatsTrack> = emptyList(),
    val topArtists: List<ArtistEntry> = emptyList(),
    val topAlbums: List<StatsAlbum> = emptyList(),
    val heatmap: StatsHeatmap? = null,
    val years: List<Int> = emptyList(),
    val importedPlays: Int = 0,
    val totalPlays: Int = 0,
)

/**
 * İstatistikler: the period summary (artwork filled in by [StatsArtwork] — re-run whenever artwork lands), the heat
 * map, the years with a "Yıl özeti", and the history management (Spotify import, remove imported, clear).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel : ViewModel() {
    private val stats get() = AppGraph.stats
    private val art get() = AppGraph.statsArtwork

    private val period = MutableStateFlow(StatsPeriod.Month)

    val state: StateFlow<StatsUiState> = combine(period, AppGraph.stats.version, AppGraph.statsArtwork.version) { p, _, _ -> p }
        .mapLatest { p -> withContext(AppDispatchers.cpu) { compute(p) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    val importProgress: StateFlow<ListenStatsService.ImportProgress> = AppGraph.stats.importProgress

    private val _importResult = MutableStateFlow<ListenStatsService.ImportOutcome?>(null)
    val importResult: StateFlow<ListenStatsService.ImportOutcome?> = _importResult.asStateFlow()

    fun setPeriod(p: StatsPeriod) {
        period.value = p
    }

    private fun compute(p: StatsPeriod): StatsUiState {
        val store = stats.store
        if (!store.isLoaded) return StatsUiState(loaded = false, period = p)
        val clock = systemLocalClock()
        val s = stats.summarize(p, topN = 10, recentN = 0)
        val wants = ArrayList<StatsArtwork.Want>()
        val tracks = s.topTracks.map { it.copy(track = art.enrich(it.track, wants)) }
        val albums = s.topAlbums.map { art.enrich(it, wants) }
        val artists = s.topArtists.map { art.enrich(it, wants) }
        art.fetch(wants)
        art.fetchArtistImages(artists.mapNotNull { a -> a.artist.id.takeIf { it.isNotEmpty() } })
        return StatsUiState(
            loaded = true,
            period = p,
            listenedMs = s.listenedMs,
            streams = s.streams,
            distinctTracks = s.distinctTracks,
            distinctArtists = s.distinctArtists,
            topTracks = tracks,
            topArtists = artists.map { ArtistEntry(it, art.artistImages(it.artist)) },
            topAlbums = albums,
            heatmap = stats.heatmap(p, clock),
            years = stats.years(clock).sortedDescending(),
            importedPlays = store.importedPlayCount,
            totalPlays = store.playCount,
        )
    }

    /** Plays the top songs from [index] (a stats context). */
    fun play(tracks: List<Track>, index: Int, contextName: String) =
        AppGraph.playerControls.playContext(tracks, index, "stats", contextName)

    // ---- history management ---------------------------------------------------------------------------------------

    /** Spotify's account-data / extended history (JSON files or the ZIP), picked through SAF. */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = AppGraph.context.contentResolver
        viewModelScope.launch {
            val sources = withContext(AppDispatchers.io) {
                uris.map { uri ->
                    val name = runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
                    HistoryImport.Source(name) { resolver.openInputStream(uri) ?: throw IOException("cannot open $uri") }
                }
            }
            // On the app scope: leaving the page does not stop an import.
            _importResult.value = appScope.async { stats.importHistory(sources) }.await()
        }
    }

    fun dismissImportResult() {
        _importResult.value = null
    }

    fun removeImported() = stats.removeImported()

    fun clear() = stats.clear()
}
