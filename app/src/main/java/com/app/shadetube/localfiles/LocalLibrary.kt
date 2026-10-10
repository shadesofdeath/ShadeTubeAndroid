package com.app.shadetube.localfiles

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isLocalId
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Log
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.Time
import com.app.shadetube.core.appScope
import com.app.shadetube.lyrics.LocalAudioFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** What the Yerel dosyalar page and settings rows show (port of LocalLibrary's getters). */
data class LocalLibraryState(
    /** The index was read (tracks show at once; a rescan follows). */
    val loaded: Boolean = false,
    val scanning: Boolean = false,
    /** Files read so far in the running scan. */
    val scanDone: Int = 0,
    /** Files to read in the running scan (0 = still walking the folders). */
    val scanTotal: Int = 0,
    /** Unix seconds, 0 = never. */
    val lastScanAt: Long = 0,
    /** Of the last finished scan (null before one finished in this run). */
    val lastStats: ScanStats? = null,
    /** The index that was loaded says its scan stopped at the file cap. */
    val indexCapped: Boolean = false,
    /** The folders covered by a finished scan (this run, or the one that wrote the index). */
    val scannedFolders: List<String> = emptyList(),
    /** Parallel to [tracks] (same order). */
    val entries: List<LocalEntry> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val totalMs: Long = 0,
    /** Bumps whenever [tracks] changes. */
    val generation: Long = 0,
) {
    /** The library stops at [LocalFormats.MAX_FILES]. */
    val capped: Boolean get() = lastStats?.capped ?: indexCapped
}

/**
 * Yerel dosyalar: the user's own music folders (port of app/LocalLibrary + the wiring of LocalFilesPage).
 *
 * [start] loads the index (local-library.json) on a worker, so tracks show at once; an incremental rescan follows a
 * few seconds later and picks up what changed while the app was closed. A scan walks the folders
 * (Settings.localFolders: SAF trees and MediaStore folders), reuses unchanged files from the index and reads the rest
 * (tags, embedded covers extracted once into cache/local-covers). Tracks get the id "local:<FNV-1a 64 of the
 * lower-cased path>"; artist and album ids stay empty, so no Spotify / MusicBrainz action ever sees them.
 *
 * Main-safe; state is a [StateFlow]. A local file never falls back to a YouTube search on its name: [fileFor]
 * returns null for an unknown id and the player reports an error and moves on.
 */
class LocalLibrary(
    private val indexFile: File,
    private val coverDir: File,
    /** A fresh lister per scan (it may cache a MediaStore query for the scan's folders). */
    private val listerFactory: () -> FolderLister,
    private val tagSource: TagSource,
    val opener: LocalFileOpener,
    private val readFolders: () -> List<String> = { SettingsStore.current.localFolders },
    private val writeFolders: ((List<String>) -> List<String>) -> Unit = { change ->
        SettingsStore.update { it.copy(localFolders = change(it.localFolders)) }
    },
    private val scope: CoroutineScope = appScope,
    /** A little after launch: pick up what changed on disk while the app was closed. */
    private val startupScanDelayMs: Long = 4000,
) {
    private val state = MutableStateFlow(LocalLibraryState())
    private val trackFlow = MutableStateFlow<List<Track>>(emptyList())
    @Volatile private var byId: Map<String, LocalEntry> = emptyMap()

    val flow: StateFlow<LocalLibraryState> = state.asStateFlow()
    val tracks: StateFlow<List<Track>> = trackFlow.asStateFlow()
    val current: LocalLibraryState get() = state.value

    private var started = false
    private var startupJob: Job? = null
    private val scanMutex = Mutex()
    private val requested = AtomicLong(0)
    @Volatile private var completed = 0L
    @Volatile private var running: Deferred<Pair<List<LocalEntry>, ScanStats>>? = null

    // ---- lifecycle ------------------------------------------------------------------------------------------------
    /** Loads the index on a worker; an incremental rescan follows a few seconds later. Idempotent. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch(AppDispatchers.io) {
            val (entries, meta) = runCatching { LocalIndex.load(indexFile) }.getOrElse { emptyList<LocalEntry>() to IndexMeta() }
            state.update {
                it.copy(
                    lastScanAt = meta.scannedAt,
                    indexCapped = meta.capped,
                    scannedFolders = if (meta.scannedAt > 0 && it.scannedFolders.isEmpty()) meta.folders else it.scannedFolders,
                )
            }
            apply(entries, previous = null)
            state.update { it.copy(loaded = true) }
            Log.i(TAG, "index: ${state.value.tracks.size} tracks")
            if (readFolders().isNotEmpty() || state.value.tracks.isNotEmpty()) {
                startupJob = scope.launch {
                    delay(startupScanDelayMs)
                    requestRescan()
                }
            }
        }
    }

    /** App exit: stops a running scan (a partial result is never applied or saved) and drops queued ones. */
    fun cancel() {
        startupJob?.cancel()
        completed = requested.get()
        running?.cancel()
    }

    /** Incremental rescan in the background; while one runs, one more is queued. */
    fun requestRescan() {
        val ticket = requested.incrementAndGet()
        scope.launch { runQueued(ticket) }
    }

    /** Incremental rescan; suspends until a scan that started after this call has finished (or was cancelled). */
    suspend fun rescan() {
        val ticket = requested.incrementAndGet()
        scope.async { runQueued(ticket) }.await()
    }

    private suspend fun runQueued(ticket: Long) {
        start()
        state.first { it.loaded }
        scanMutex.withLock {
            if (completed >= ticket) return // a scan that started after the request covered it
            val covers = requested.get()
            if (runScan()) completed = maxOf(completed, covers)
        }
    }

    /** One scan; false when it was cancelled. */
    private suspend fun runScan(): Boolean {
        val folders = readFolders().map { LocalPaths.normalizeFolder(it) }.filter { it.isNotEmpty() }
        val before = state.value.entries
        state.update { it.copy(scanning = true, scanDone = 0, scanTotal = 0) }
        // Progress: at most ~8 UI updates per second (plus the last one).
        val lastPost = AtomicLong(0)
        val o = ScanOptions(folders, coverDir, progress = progress@{ done, total ->
            val now = System.nanoTime() / 1_000_000
            if (done > 0 && done < total && now - lastPost.get() < 120) return@progress
            lastPost.set(now)
            state.update { if (it.scanning) it.copy(scanDone = done, scanTotal = total) else it }
        })
        val job = scope.async(AppDispatchers.low) {
            val r = LocalScanner.scan(o, before, listerFactory(), tagSource)
            val meta = IndexMeta(Time.nowUnix(), folders, r.second.capped)
            if (!LocalIndex.save(indexFile, r.first, meta)) Log.w(TAG, "could not write ${indexFile.name}")
            // Covers of files the scan didn't see (capped / a folder that couldn't be listed) may still be in use.
            if (!r.second.capped && !r.second.incomplete) LocalIndex.pruneCovers(coverDir, r.first)
            r
        }
        running = job
        val result = try {
            job.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            Log.w(TAG, "scan failed: ${e.message}", e)
            null
        } finally {
            running = null
        }
        if (result == null) {
            state.update { it.copy(scanning = false) }
            return job.isCancelled.not() // a failed scan counts as done (no retry loop)
        }
        val (entries, stats) = result
        state.update { it.copy(lastStats = stats, lastScanAt = Time.nowUnix(), scannedFolders = folders) }
        apply(entries, before)
        state.update { it.copy(scanning = false) }
        return true
    }

    /**
     * Only what the folders listed NOW cover: an index or a scan from before a folder was removed must not bring its
     * tracks back.
     */
    private fun apply(newEntries: List<LocalEntry>, previous: List<LocalEntry>?) {
        val roots = readFolders().map { LocalPaths.normalizeFolder(it) }.filter { it.isNotEmpty() }
        val entries = if (newEntries.all { e -> roots.any { LocalPaths.isUnder(e.path, it) } }) newEntries
        else newEntries.filter { e -> roots.any { LocalPaths.isUnder(e.path, it) } }
        val prev = previous ?: state.value.entries
        val changed = prev.size != entries.size || entries.indices.any { i ->
            val a = entries[i]
            val b = prev[i]
            a.path != b.path || a.size != b.size || a.mtime != b.mtime || a.cover != b.cover
        } || (previous == null && !state.value.loaded)
        if (!changed) return
        val tracks = entries.map { LocalFormats.toTrack(it) }
        val ids = HashMap<String, LocalEntry>(entries.size * 2)
        for ((i, t) in tracks.withIndex()) ids[t.id] = entries[i]
        byId = ids
        state.update {
            it.copy(entries = entries, tracks = tracks, totalMs = entries.sumOf { e -> e.durationMs.toLong() }, generation = it.generation + 1)
        }
        trackFlow.value = tracks
    }

    // ---- lookups --------------------------------------------------------------------------------------------------
    /** The file of a "local:" id (for the player, lyrics and export); null when it is not in the library. */
    fun fileFor(trackId: String): LocalFile? {
        if (!isLocalId(trackId)) return null
        return byId[trackId]?.let { LocalFile.of(it) }
    }

    fun entryFor(trackId: String): LocalEntry? = byId[trackId]

    /** The file as the lyrics module reads it; null when unknown. */
    fun audioFileFor(trackId: String): LocalAudioFile? = fileFor(trackId)?.let { opener.audio(it) }

    /** ReplayGain track gain of a local track's file in dB, null when it has none / is unknown. */
    suspend fun replayGainDb(trackId: String): Float? {
        val f = fileFor(trackId) ?: return null
        return withContext(AppDispatchers.io) { opener.replayGainDb(f) }
    }

    /** Tracks inside a (listed) folder. */
    fun countUnder(folder: String): Int = state.value.entries.count { LocalPaths.isUnder(it.path, folder) }

    /** The folder was covered by a finished scan (this run, or the one that wrote the index). */
    fun scannedFolder(folder: String): Boolean = state.value.scannedFolders.any { LocalPaths.isUnder(folder, it) }

    /** The last scan couldn't reach this listed folder (its tracks are the ones indexed before). */
    fun folderUnavailable(folder: String): Boolean =
        state.value.lastStats?.missingRoots?.any { LocalPaths.sameFolder(it, folder) } == true

    /** A listed folder's scan already reaches [folder] (dropped / opened folders: offer "add to Yerel dosyalar"). */
    fun coversFolder(folder: String): Boolean {
        val n = LocalPaths.normalizeFolder(folder)
        return n.isNotEmpty() && readFolders().any { LocalPaths.sameFolder(n, it) || LocalPaths.walkedFrom(it, n) }
    }

    fun albums(): List<LocalGroup> = state.value.let { LocalGroups.albums(it.entries, it.tracks) }
    fun artists(): List<LocalGroup> = LocalGroups.artists(state.value.tracks)

    // ---- folders --------------------------------------------------------------------------------------------------
    /**
     * Adds a folder (a SAF tree URI the caller took a persistable read permission for, or a MediaStore folder) and
     * rescans. false when it can't be stored or a listed folder's scan already covers it.
     */
    fun addFolder(folder: String): Boolean {
        var added = false
        writeFolders { list ->
            val next = FolderRules.add(list, folder)
            added = next != null
            next ?: list
        }
        if (!added) return false
        Log.i(TAG, "folder added (${readFolders().size} total)")
        requestRescan()
        return true
    }

    /**
     * Removes a folder from the list (the files are never touched). A running scan may be reading it: it is stopped
     * (a cancelled scan is neither saved nor applied) and the remaining folders are scanned again. Its tracks leave
     * right away, unless another listed folder still scans them.
     */
    fun removeFolder(folder: String) {
        running?.cancel()
        val n = LocalPaths.normalizeFolder(folder).ifEmpty { folder }
        writeFolders { list -> FolderRules.remove(list, n) }
        val remaining = readFolders()
        val keep = FolderRules.entriesAfterRemoval(state.value.entries, n, remaining)
        if (keep.size != state.value.entries.size) apply(keep, previous = null)
        requestRescan()
    }

    companion object {
        private const val TAG = "local"
    }
}

/** The add / remove rules of Settings.localFolders (pure; port of LocalLibrary::addFolder / removeFolder). */
object FolderRules {
    /**
     * The list with [folder] added; null when it can't be stored or is already scanned (itself, or it is walked from
     * a listed folder). Listed folders the new one walks into are dropped (it covers them now; a "." one stays).
     */
    fun add(folders: List<String>, folder: String): List<String>? {
        val n = LocalPaths.normalizeFolder(folder)
        if (n.isEmpty()) return null
        if (folders.any { LocalPaths.sameFolder(n, it) || LocalPaths.walkedFrom(it, n) }) return null
        return folders.filterNot { LocalPaths.walkedFrom(n, it) } + n
    }

    fun remove(folders: List<String>, folder: String): List<String> = folders.filterNot { LocalPaths.sameFolder(it, folder) }

    /**
     * The entries left when [removed] leaves the list: its tracks go, unless another listed folder still scans them
     * (one it is walked from, or a listed folder inside it; the rescan confirms it and rewrites the index).
     */
    fun entriesAfterRemoval(entries: List<LocalEntry>, removed: String, remaining: List<String>): List<LocalEntry> {
        if (remaining.any { LocalPaths.walkedFrom(it, removed) }) return entries
        val inner = remaining.filter { LocalPaths.isUnder(it, removed) }
        return entries.filter { e -> !LocalPaths.isUnder(e.path, removed) || inner.any { LocalPaths.isUnder(e.path, it) } }
    }
}
