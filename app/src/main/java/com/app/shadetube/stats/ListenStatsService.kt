package com.app.shadetube.stats

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What the player reports to the statistics (mapped by AppGraph from the playback service's state). */
enum class PlayerPhase {
    /** Nothing current, or the end of the queue (the current track is kept but the play is over). */
    Idle,

    /** Finding / loading the audio: the position is 0 or a pending seek target, not a sample. */
    Resolving,

    /** Audio actually advancing. */
    Playing,

    /** Paused or buffering: the wall clock runs, the position does not. */
    Stalled,
}

/** One player sample. */
data class PlayerSample(
    val track: Track?,
    val phase: PlayerPhase,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * The app's listening history (port of the initListenStats part of app/StatsPage.cpp + the import flow): owns the
 * [ListenStats] store of `listening.json` (+ `listening-imported.json`), feeds it from the player and saves it.
 *
 * Wiring (AppGraph):
 *  - [start] once at startup with a [probe] that samples the player on the main thread;
 *  - [onTrackChanged] for every new current track (a radio station or podcast episode stops the play: hours of a
 *    station would count as one very long "song");
 *  - [onPlayerChanged] on every pause / resume / seek / stop, so a seek or a pause splits the listened time exactly;
 *  - [persist] when the process may go away (ON_STOP / onDestroy of the playback service).
 *
 * While a play is running the store is sampled every 2 s, so the stretch credited at a track change (capped at
 * [Listen.GAP_CREDIT_MS]) never has to cover more than ~2 s; something stored is saved at most every 30 s on a
 * worker (the main thread only snapshots the plays).
 */
class ListenStatsService(
    file: File? = null,
    private val scope: CoroutineScope = appScope,
    private val steadyMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val store: ListenStats = ListenStats(file ?: File(Paths.appData, "listening.json"))

    /** Samples the player (called on the main thread). Set by AppGraph before [start]. */
    @Volatile
    var probe: (() -> PlayerSample?)? = null

    private var lastSaveMs = 0L
    private var ticker: Job? = null

    /** Changes of the history (loaded / cleared / a play stored / an import landed). */
    val version: StateFlow<Long> get() = store.version

    data class ImportProgress(
        val active: Boolean = false,
        /** Files read: deduplicating and saving. */
        val merging: Boolean = false,
        /** Documents. */
        val done: Int = 0,
        val total: Int = 0,
    )

    private val importFlow = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = importFlow.asStateFlow()

    /** Reads the history on a worker (a big file takes a moment; plays recorded meanwhile merge on top). */
    fun start() {
        scope.launch {
            val snap = withContext(AppDispatchers.io) {
                runCatching { ListenStats.readFile(store.file) }
                    .onFailure { Log.w(TAG, "reading listening.json failed", it) }
                    .getOrElse { ListenStats.Snapshot() }
            }
            store.adopt(snap)
        }
        ticker?.cancel()
        ticker = scope.launch(AppDispatchers.main) {
            while (isActive) {
                delay(2000)
                housekeeping()
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * Every ~2 s: accumulate the listened time; save when something was stored, at most every 30 s. Serializing and
     * writing the file (flushed, the previous one kept) runs on a worker.
     */
    fun housekeeping() {
        sample()
        if (store.isDirty && store.isLoaded && steadyMs() - lastSaveMs >= 30_000) {
            lastSaveMs = steadyMs()
            store.saveAsync(scope)
        }
    }

    /**
     * A new track: store the previous play, start the next (or continue it: re-resolve / "Yanlış eşleşme?").
     * A radio station is not recorded, nor is a podcast episode.
     */
    fun onTrackChanged(t: Track, positionMs: Long) {
        if (isStationId(t.id) || isPodcastId(t.id)) {
            store.stop(steadyMs())
            return
        }
        store.trackStarted(t, positionMs, nowUnix(), steadyMs())
    }

    /** Pause / resume / seek / stop: sample right away. */
    fun onPlayerChanged() = sample()

    /** Samples the player through [probe] (nothing without one). */
    fun sample() {
        val p = probe ?: return
        if (!store.isActive) return
        feed(p())
    }

    /** Feeds one player sample (the same rules as the C++ sample()); null = nothing current. */
    fun feed(s: PlayerSample?) {
        if (!store.isActive) return
        val now = steadyMs()
        if (s == null || s.track == null || s.phase == PlayerPhase.Idle) { // the play is over
            store.stop(now)
            return
        }
        // Another track is already current: its track-changed hook comes next and closes this play. While
        // resolving, the position is 0 or the pending seek target, not a sample.
        if (s.track.id != store.currentTrackId || s.track.name != store.currentTrackName || s.phase == PlayerPhase.Resolving) return
        store.progress(s.positionMs, s.phase == PlayerPhase.Playing, s.durationMs, now, nowUnix())
    }

    /**
     * The process may go away: the current play is written as it stands (it keeps counting if the app goes on).
     * Synchronous; an older worker save landing later is skipped.
     */
    fun persist() {
        sample()
        if (store.isLoaded && (store.isDirty || store.currentListenedMs >= Listen.MIN_PLAY_MS)) store.save()
    }

    // --- history management (Stats page) -----------------------------------------------------------------------------

    /** Forgets the imported plays and empties listening-imported.json. */
    fun removeImported() {
        store.removeImported()
        store.saveAsync(scope)
        lastSaveMs = steadyMs()
    }

    /** Forgets every play (both histories). */
    fun clear() {
        store.clear(nowUnix())
        store.saveAsync(scope)
        lastSaveMs = steadyMs()
    }

    sealed interface ImportOutcome {
        /** An import is already running. */
        data object Busy : ImportOutcome

        /** The history is not loaded or the imported file is unreadable this session. */
        data object Unavailable : ImportOutcome

        data class Done(
            val added: Int,
            val duplicates: Int,
            val rows: Int,
            /** listening-imported.json was written (false: an I/O failure, nothing changed). */
            val ok: Boolean,
            /** Inputs that couldn't be read. */
            val unreadableFiles: Int,
            /** JSON files / ZIPs without a listening history. */
            val unrecognized: Int,
        ) : ImportOutcome {
            /** No new play though rows were offered: this history was imported already. */
            val nothingNew: Boolean get() = ok && added == 0 && rows > 0

            /** The picked files held no Spotify listening history. */
            val noHistory: Boolean get() = ok && rows == 0
        }

        data class Failed(val message: String) : ImportOutcome
    }

    /**
     * Parses [sources] and merges them into the history on a worker; [importProgress] follows it. The result always
     * lands (ListenStats.finishImport installs it and bumps [version]).
     */
    suspend fun importHistory(sources: List<HistoryImport.Source>, cancel: AtomicBoolean? = null): ImportOutcome {
        if (sources.isEmpty()) return ImportOutcome.Done(0, 0, 0, true, 0, 0)
        if (!store.canImport) return if (store.isImporting) ImportOutcome.Busy else ImportOutcome.Unavailable
        val base = store.beginImport()
        if (base.importedFile == null) return ImportOutcome.Unavailable
        importFlow.value = ImportProgress(active = true)
        try {
            val (parsed, result) = withContext(AppDispatchers.io) {
                val parsed = HistoryImport.read(sources, { done, total ->
                    importFlow.value = ImportProgress(true, false, done, total)
                }, cancel)
                importFlow.value = importFlow.value.copy(merging = true)
                val rows = parsed.rows.toList()
                parsed.rows.clear()
                parsed to ListenStats.buildImport(base, rows)
            }
            store.finishImport(result)
            if (result.ok && result.added > 0) {
                store.saveAsync(scope) // the imported file is written already; this device's plays may be pending
                lastSaveMs = steadyMs()
            }
            if (!result.ok && result.error.isNotEmpty()) Log.w(TAG, "import: ${result.error}")
            return ImportOutcome.Done(result.added, result.duplicates, result.rows, result.ok, parsed.errors.size, parsed.unrecognized)
        } catch (e: Exception) {
            Log.e(TAG, "import failed", e)
            store.cancelImport()
            return ImportOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            importFlow.value = ImportProgress()
        }
    }

    // --- queries (cheap: a few ms for 300 000 plays) -----------------------------------------------------------------

    fun summarize(period: StatsPeriod, topN: Int = 10, recentN: Int = 20) = store.summarize(period, nowUnix(), topN, recentN)
    fun years(clock: LocalClock) = store.years(clock)
    fun summarizeYear(year: Int, clock: LocalClock, topN: Int = 5) = store.summarizeYear(year, clock, topN)
    fun heatmap(period: StatsPeriod, clock: LocalClock) = store.heatmap(period, nowUnix(), clock)

    companion object {
        private const val TAG = "stats"

        /** Internet-radio station ids ("radio:<stationuuid>", see internetradio.RadioModels). */
        fun isStationId(id: String) = id.length > 6 && id.startsWith("radio:")
    }
}
