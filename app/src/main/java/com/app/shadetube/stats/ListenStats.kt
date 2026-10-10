package com.app.shadetube.stats

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.pick
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Log
import com.app.shadetube.core.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Listening statistics (İstatistikler): what the user REALLY listened to, from every source (Spotify, MusicBrainz,
 * downloads, local files), stored on this device in `listening.json`. Port of app/ListenStats.
 *
 * A play = one track from its start until the track changes / playback stops / it restarts from the top, with the
 * actual playback time: time is accumulated from player position deltas, never more than the wall clock ran (audio
 * can't outrun it: anything beyond is a seek), so pauses add nothing and seeks (forward or backward) are not
 * listening. A play counts as a stream when listened >= 30 s, or >= 50 % of the track for tracks shorter than 60 s.
 * The total listening time includes every play; everything else (stream count, distinct tracks / artists, top
 * lists, recent plays) counts streams only. Tracks and artists are grouped by name (+ first artist), albums by
 * album id and by name + first artist, so the same song heard from Spotify and from a download is one entry.
 *
 * listening.json (compact, the newest [Listen.MAX_PLAYS] plays of this device):
 * `{"v":1,"tracks":[{"id","n","a":[[id,name]..],"al":[id,name],"img","d"}..],"plays":[[track,startedAt,ms]..]}`
 *
 * listening-imported.json (history imported from Spotify's data download, parsed by [HistoryImport]; rewritten only
 * by an import, "İçe aktarılanları kaldır" and a clear, never by the regular saves):
 * `{"v":1,"source":"spotify","tracks":[..as above..],"plays":[track,startDelta,ms, track,startDelta,ms, ..]}`
 * (one flat array, oldest first; startDelta = startedAt minus the previous play's, the first one absolute)
 *
 * Both are written to a flushed .tmp, the previous file kept as .old (readFile falls back to it when the main file
 * is missing or broken), then renamed in place. A file that can't be read (locked, I/O error) is never overwritten.
 * In memory both histories share one index (the same song merges across them) and one time-ordered play list;
 * imported plays carry a flag.
 *
 * Beyond the rolling periods: the years with plays, a year summary ("Yıl özeti") and an hour x weekday heatmap, all
 * on the local wall clock ([LocalClock]: the device's time zone with the DST rules in force at each instant).
 *
 * Thread-safe: every member is synchronized (the C++ store was UI-thread only); [readFile], [write] and
 * [buildImport] are pure and meant for workers.
 */
class ListenStats(val file: File) {

    /** Parsed file content (readFile -> adopt). The whole index is built by readFile, i.e. on a worker. */
    class Snapshot {
        var index = ListenIndex()
        /** Oldest first; track indexes into index.tracks. */
        var plays = PlayList()
        /** Unparsable: kept as listening.json.bak (unless one exists), starting empty. */
        var corrupt = false
        /** The main file was missing / broken: loaded the previous generation (.old). */
        var fromOld = false
        /** Couldn't be read (locked, I/O error) or backed up: never overwrite it. */
        var keepFile = false
        /** The same for listening-imported.json (imports are refused this session). */
        var importedKeepFile = false
        /** listening-imported.json was broken (kept as .bak; .old loaded if usable). */
        var importedCorrupt = false
    }

    /** A save, detached from the store: the newest plays and only the tracks they use (renumbered). */
    class SaveJob(
        /** null = nothing to do. */
        val file: File? = null,
        val tracks: List<ListenTrack> = emptyList(),
        val plays: PlayList = PlayList(0),
        /** A job never overwrites a file written by a newer one. */
        val generation: Long = 0,
        /** Keep the current file as .old (not when it is known to be broken). */
        val rotate: Boolean = true,
        /** listening-imported.json (flat, delta-coded plays). */
        val imported: Boolean = false,
    )

    // --- importing (Spotify's streaming history, parsed by HistoryImport) --------------------------------------

    class ImportRow(
        /** Name, first artist, album name, id (spotify:track:...) when known. */
        val track: ListenTrack,
        /** Unix seconds (UTC) when the play ended. */
        val endedAt: Long,
        val listenedMs: Long,
    )

    /** What an import works from: this device's plays + their tracks (copied) and the imported file. */
    class ImportBase(
        /** null = refused. */
        val importedFile: File? = null,
        val tracks: List<ListenTrack> = emptyList(),
        /** This device's plays, track indexes into [tracks]. */
        val plays: PlayList = PlayList(0),
        /** finishImport() only takes the result of the latest beginImport(). */
        val token: Long = 0,
    )

    class ImportResult(val token: Long = 0) {
        /** This device's plays (as in the base) + every imported play, the new ones included. */
        var snapshot = Snapshot()
        /** Rows offered. */
        var rows = 0
        /** New plays. */
        var added = 0
        /** Already known (a re-import, overlapping files, a play recorded here). */
        var duplicates = 0
        /** Under [Listen.MIN_PLAY_MS]. */
        var tooShort = 0
        /** listening-imported.json was written (else nothing changed). */
        var ok = false
        /** Why not (I/O). */
        var error = ""
    }

    private class Current {
        var active = false
        /** As given by the player (hook-level identity). */
        var id = ""
        var name = ""
        var track = 0
        var startedAt = 0L
        var listenedMs = 0L
        var lastPosMs = 0L
        var lastWallMs = 0L
        var lastPlaying = false
    }

    private var idx = ListenIndex()
    /** Oldest first (this device's and imported ones). */
    private var plays = PlayList()
    private var cur = Current()
    private var loaded = false
    private var readOnly = false
    /** The file on disk is the broken one (kept as .bak): don't rotate it into .old. */
    private var mainBroken = false
    private var dirty = false
    /** Plays of this device in [plays]. */
    private var nativeCount = 0
    /** Imported plays in [plays]. */
    private var importedCount = 0
    /** listening-imported.json must be rewritten (a removal / clear). */
    private var importedDirty = false
    /** listening-imported.json couldn't be read: never overwrite it. */
    private var importedReadOnly = false
    /** The imported file on disk is the broken one: don't rotate it into .old. */
    private var importedBroken = false
    private var importing = false
    private var importToken = 0L
    /** Stored while an import ran: re-added by finishImport. */
    private val recordedDuringImport = ArrayList<Pair<ListenTrack, Play>>()

    private val versionFlow = MutableStateFlow(0L)

    /**
     * Change notifications (loaded / cleared / a play stored / an import landed): bumped on every change, so the UI
     * re-reads the aggregates it shows (replaces the C++ subscribe()).
     */
    val version: StateFlow<Long> = versionFlow.asStateFlow()

    // --- state -----------------------------------------------------------------------------------------------------

    @get:Synchronized val isLoaded: Boolean get() = loaded
    /** The file couldn't be read: this session's plays are not saved. */
    @get:Synchronized val isReadOnly: Boolean get() = readOnly
    @get:Synchronized val isDirty: Boolean get() = dirty || importedDirty
    @get:Synchronized val isActive: Boolean get() = cur.active
    @get:Synchronized val currentTrackId: String get() = cur.id
    @get:Synchronized val currentTrackName: String get() = cur.name
    @get:Synchronized val currentListenedMs: Long get() = if (cur.active) cur.listenedMs else 0
    /** False until the history is loaded, while an import runs, or when the imported file is unreadable. */
    @get:Synchronized val canImport: Boolean get() = loaded && !importing && !importedReadOnly
    @get:Synchronized val isImporting: Boolean get() = importing
    @get:Synchronized val importedPlayCount: Int get() = importedCount
    @get:Synchronized val playCount: Int get() = plays.size

    /** A copy of every play, oldest first (this device's and imported ones). */
    @Synchronized
    fun playsCopy(): PlayList = plays.copy()

    /** A copy of the distinct tracks (indexes as in [playsCopy]). */
    @Synchronized
    fun tracksCopy(): List<ListenTrack> = idx.tracks.map { it.copy() }

    /** Both, consistent (the smart lists' input). */
    @Synchronized
    fun historyCopy(): Pair<List<ListenTrack>, PlayList> = tracksCopy() to plays.copy()

    @Synchronized
    fun lastPlay(): Play? = if (plays.isEmpty()) null else plays.last()

    private fun notifyChanged() {
        versionFlow.value = versionFlow.value + 1
    }

    // --- persistence -----------------------------------------------------------------------------------------------

    /**
     * Installs loaded data below whatever was recorded meanwhile (plays, the current play). Only the first adopt /
     * load after construction applies (clear() also counts as loaded: a late load never resurrects it).
     */
    @Synchronized
    fun adopt(snapshot: Snapshot) {
        if (loaded) return
        loaded = true
        readOnly = snapshot.keepFile
        mainBroken = snapshot.corrupt
        importedReadOnly = snapshot.importedKeepFile
        importedBroken = snapshot.importedCorrupt
        when {
            snapshot.keepFile -> Log.w(TAG, "listening.json unreadable; this session's plays are not saved")
            snapshot.fromOld -> Log.w(TAG, "listening.json missing or broken; restored the previous copy (.old)")
            snapshot.corrupt -> Log.w(TAG, "listening.json is corrupt; starting empty (file kept as .bak)")
        }
        if (snapshot.importedKeepFile) Log.w(TAG, "listening-imported.json unreadable; imports are off this session")
        // What was recorded before the file was read (usually nothing) goes on top of the file's history.
        val recorded = idx
        val recordedPlays = plays
        idx = snapshot.index
        plays = snapshot.plays
        importedCount = plays.countImported()
        nativeCount = plays.size - importedCount
        if (recordedPlays.isNotEmpty() || cur.active) {
            val remap = IntArray(recorded.tracks.size) { -1 }
            fun remapped(i: Int): Int {
                if (remap[i] < 0) remap[i] = idx.intern(recorded.tracks[i])
                return remap[i]
            }
            for (i in 0 until recordedPlays.size)
                insertPlay(remapped(recordedPlays.track(i)), recordedPlays.startedAt(i), recordedPlays.listenedMs(i), false)
            if (cur.active) cur.track = remapped(cur.track)
            if (recordedPlays.isNotEmpty()) dirty = true
        }
        if (snapshot.fromOld) dirty = true // put the recovered history back in place
        Log.i(TAG, "listening history: ${plays.size} plays ($importedCount imported), ${idx.tracks.size} tracks")
        notifyChanged()
    }

    fun load() = adopt(readFile(file))

    /**
     * This device's history plus the current play so far, as a job for write(); clears dirty. A job without a file =
     * nothing to do (not loaded yet, or read-only).
     */
    @Synchronized
    fun makeSaveJob(): SaveJob {
        if (!loaded || readOnly) return SaveJob()
        // The newest MAX_PLAYS plays of this device, the current one (so far) included; only the tracks they use.
        val withCurrent = currentCounts()
        val keep = minOf(nativeCount, Listen.MAX_PLAYS - (if (withCurrent) 1 else 0))
        val out = PlayList(keep + 1)
        if (importedCount == 0) {
            for (i in plays.size - keep until plays.size) out.add(plays.track(i), plays.startedAt(i), plays.listenedMs(i), false)
        } else {
            var i = plays.size - 1
            while (i >= 0 && out.size < keep) {
                if (!plays.imported(i)) out.add(plays.track(i), plays.startedAt(i), plays.listenedMs(i), false)
                i--
            }
            out.reverse()
        }
        if (withCurrent) out.add(cur.track, cur.startedAt, cur.listenedMs, false)
        val tracks = usedTracks(idx.tracks, out)
        dirty = false
        return SaveJob(file, tracks, out, generation.incrementAndGet(), rotate = !mainBroken)
    }

    /** The imported history as a job, when a removal / clear left it to rewrite (no file = nothing to do). */
    @Synchronized
    fun makeImportedSaveJob(): SaveJob {
        if (!loaded || importedReadOnly || !importedDirty) return SaveJob()
        val out = PlayList(importedCount)
        for (i in 0 until plays.size) if (plays.imported(i)) out.add(plays.track(i), plays.startedAt(i), plays.listenedMs(i), true)
        val tracks = usedTracks(idx.tracks, out)
        importedDirty = false
        return SaveJob(importedFileFor(file), tracks, out, generation.incrementAndGet(), rotate = !importedBroken, imported = true)
    }

    /** Synchronous save (exit, tests): makeSaveJob + write (+ the imported file when due); dirty again on failure. */
    fun save(): Boolean {
        val imported = makeImportedSaveJob()
        if (imported.file != null) {
            val ok = write(imported)
            synchronized(this) { if (ok) importedBroken = false else importedDirty = true }
        }
        val job = makeSaveJob()
        if (job.file == null) return false
        val ok = write(job)
        synchronized(this) {
            if (ok) {
                mainBroken = false
                return !importedDirty
            }
            dirty = true
        }
        return false
    }

    /** The same on a worker (the caller's thread stays free); dirty again on failure. */
    fun saveAsync(scope: CoroutineScope = appScope) {
        val imported = makeImportedSaveJob()
        val main = makeSaveJob()
        if (main.file == null && imported.file == null) return
        scope.launch(AppDispatchers.io) {
            val importedOk = imported.file == null || write(imported)
            val mainOk = main.file == null || write(main)
            // Failures are retried by the next housekeeping save.
            synchronized(this@ListenStats) {
                if (main.file != null) {
                    if (mainOk) mainBroken = false else dirty = true
                }
                if (imported.file != null) {
                    if (importedOk) importedBroken = false else importedDirty = true
                }
            }
        }
    }

    // --- recording (wallMs = a monotonic clock in ms, unixNow = unix seconds; both passed in for tests) ---------

    /**
     * A track became current, the player being at [positionMs]. The same track again at about the same position
     * (re-resolve, "Yanlış eşleşme?", resume after an error) continues the current play; anything else (another
     * track, repeat / restart from the top) stores the current play and starts a new one.
     */
    @Synchronized
    fun trackStarted(t: Track, positionMs: Long, unixNow: Long, wallMs: Long) {
        val same = cur.active && (if (t.id.isEmpty()) t.name == cur.name else t.id == cur.id)
        if (same && positionMs > 0 && positionMs + Listen.RESTART_MS >= cur.lastPosMs) {
            // Reloaded at about the same spot: the same play goes on (the reload gap itself is not listening).
            cur.lastPosMs = positionMs
            cur.lastWallMs = wallMs
            cur.lastPlaying = false
            complete(idx.tracks[cur.track], fromCatalog(t))
            return
        }
        finishCurrent(wallMs)
        cur = Current().apply {
            active = true
            id = t.id
            name = t.name
            track = idx.intern(fromCatalog(t))
            startedAt = unixNow
            lastPosMs = positionMs
            lastWallMs = wallMs
        }
    }

    /**
     * Player sample: position, audio actually advancing (not paused / buffering), duration (fills an unknown one).
     * A jump back to the first [Listen.RESTART_MS] from further on (Previous restarts the track with a seek) is a
     * new play.
     */
    @Synchronized
    fun progress(positionMs: Long, playing: Boolean, durationMs: Long, wallMs: Long, unixNow: Long) {
        if (!cur.active) return
        val t = idx.tracks[cur.track]
        if (t.durationMs <= 0 && durationMs > 0) t.durationMs = durationMs
        val delta = positionMs - cur.lastPosMs
        val elapsed = maxOf(0L, wallMs - cur.lastWallMs)
        if (playing || cur.lastPlaying) {
            if (delta > 0) {
                // Audio can't outrun the wall clock: whatever the position jumped beyond it is a seek (the 5 s
                // arrow-key step, SponsorBlock, a lyric line), not listening. A little slack covers sampling jitter.
                cur.listenedMs += minOf(delta, elapsed + elapsed / Listen.CLOCK_JITTER_DIV)
            } else if (delta < 0 && cur.lastPlaying) {
                // A backward seek: the audio before the jump was listening (bounded; samples come every ~2 s).
                cur.listenedMs += minOf(elapsed, Listen.GAP_CREDIT_MS)
            }
        }
        if (delta < 0 && positionMs < Listen.RESTART_MS && cur.lastPosMs - positionMs > Listen.RESTART_MS) {
            // Back to the top of the same track (Previous restarts it with a seek, no track change): a new play.
            storeCurrent()
            cur.startedAt = unixNow
            cur.listenedMs = 0
        }
        cur.lastPosMs = positionMs
        cur.lastWallMs = wallMs
        cur.lastPlaying = playing
    }

    /** Playback stopped (end of queue / nothing current): stores the current play. */
    @Synchronized
    fun stop(wallMs: Long) = finishCurrent(wallMs)

    private fun finishCurrent(wallMs: Long) {
        if (!cur.active) return
        cur.active = false
        if (cur.lastPlaying) {
            // The player has already moved on: credit the stretch since the last sample (bounded, up to the end).
            var tail = minOf(maxOf(0L, wallMs - cur.lastWallMs), Listen.GAP_CREDIT_MS)
            val d = idx.tracks[cur.track].durationMs
            if (d > 0) tail = minOf(tail, maxOf(0L, d - cur.lastPosMs))
            cur.listenedMs += tail
        }
        storeCurrent()
    }

    private fun storeCurrent() {
        if (cur.listenedMs < Listen.MIN_PLAY_MS) return
        insertPlay(cur.track, cur.startedAt, cur.listenedMs, false)
        if (importing) recordedDuringImport.add(idx.tracks[cur.track].copy() to Play(cur.track, cur.startedAt, cur.listenedMs))
        dirty = true
        notifyChanged()
    }

    /** Adds a finished play directly (tests). Plays below [Listen.MIN_PLAY_MS] are ignored. */
    @Synchronized
    fun addPlay(t: Track, startedAt: Long, listenedMs: Long) {
        if (listenedMs < Listen.MIN_PLAY_MS) return
        val track = idx.intern(fromCatalog(t))
        insertPlay(track, startedAt, listenedMs, false)
        if (importing) recordedDuringImport.add(idx.tracks[track].copy() to Play(track, startedAt, listenedMs))
        dirty = true
        notifyChanged()
    }

    private fun insertPlay(track: Int, startedAt: Long, listenedMs: Long, imported: Boolean) {
        plays.insertSorted(track, startedAt, listenedMs, imported)
        if (imported) importedCount++ else nativeCount++
        // Bounded in memory as on disk; trimmed in batches so appends stay amortized O(1).
        if (nativeCount > Listen.MAX_PLAYS + 512) trimNative()
    }

    /** Drops the oldest plays of this device beyond MAX_PLAYS (imported ones have their own bound). */
    private fun trimNative() {
        var drop = if (nativeCount > Listen.MAX_PLAYS) nativeCount - Listen.MAX_PLAYS else 0
        if (drop == 0) return
        nativeCount -= drop
        val p = plays
        p.retain { i ->
            if (drop == 0 || p.imported(i)) true
            else {
                drop--
                false
            }
        }
    }

    /**
     * Forgets every play (imported ones too); the current track keeps being recorded from now on. Lifts read-only
     * (the user chose to drop the old history).
     */
    @Synchronized
    fun clear(unixNow: Long) {
        val current = if (cur.active) idx.tracks[cur.track] else null
        idx = ListenIndex()
        plays = PlayList()
        if (current != null) {
            cur.track = idx.intern(current)
            cur.startedAt = unixNow
            cur.listenedMs = 0
        }
        cancelImport() // a running import would bring its history back
        importedDirty = importedCount > 0 || importedReadOnly // the imported file is emptied too
        nativeCount = 0
        importedCount = 0
        loaded = true // a load still in flight must not bring the history back
        readOnly = false // the user chose to drop the old history
        importedReadOnly = false
        dirty = true
        Log.i(TAG, "listening history cleared")
        notifyChanged()
    }

    // --- importing ---------------------------------------------------------------------------------------------------

    /**
     * Import in three steps: beginImport() -> buildImport() (worker: dedupes the rows against everything known,
     * writes listening-imported.json, builds the new history) -> finishImport() (installs it and re-adds what was
     * recorded meanwhile). A superseded result (a later beginImport, a clear) is dropped.
     */
    @Synchronized
    fun beginImport(): ImportBase {
        if (!canImport) return ImportBase()
        // A removal still waiting for its save: the job below reads the imported file, so empty it first (tiny write).
        if (importedDirty) {
            val pending = makeImportedSaveJob()
            if (pending.file != null && !write(pending)) {
                importedDirty = true
                return ImportBase()
            }
            importedBroken = false
        }
        importing = true
        val token = ++importToken
        val base = PlayList(nativeCount)
        for (i in 0 until plays.size) if (!plays.imported(i)) base.add(plays.track(i), plays.startedAt(i), plays.listenedMs(i), false)
        val tracks = usedTracks(idx.tracks, base)
        recordedDuringImport.clear()
        return ImportBase(importedFileFor(file), tracks, base, token)
    }

    /** The job failed before buildImport() could run. */
    @Synchronized
    fun cancelImport() {
        importing = false
        ++importToken
        recordedDuringImport.clear()
    }

    /** False when dropped or nothing new (the store keeps its plays). */
    @Synchronized
    fun finishImport(result: ImportResult): Boolean {
        if (!importing || result.token != importToken) { // superseded (a clear while it ran)
            // Its worker may have written the imported file after the clear emptied it: write what the store holds.
            if (result.ok && result.added > 0 && loaded && !importedReadOnly) importedDirty = true
            return false
        }
        importing = false
        val recorded = ArrayList(recordedDuringImport)
        recordedDuringImport.clear()
        if (!result.ok || result.added == 0) return false // nothing changed; what was recorded is already in place
        val current = if (cur.active) idx.tracks[cur.track] else null
        idx = result.snapshot.index
        plays = result.snapshot.plays
        importedCount = plays.countImported()
        nativeCount = plays.size - importedCount
        for ((t, p) in recorded) insertPlay(idx.intern(t), p.startedAt, p.listenedMs, false)
        if (current != null) cur.track = idx.intern(current)
        importedBroken = false
        Log.i(TAG, "listening history: ${plays.size} plays ($importedCount imported), ${idx.tracks.size} tracks")
        notifyChanged()
        return true
    }

    /** Forgets the imported plays only; listening-imported.json is emptied by the next save. */
    @Synchronized
    fun removeImported() {
        if (importing) cancelImport()
        if (importedCount == 0) return
        val p = plays
        p.retain { !p.imported(it) }
        importedCount = 0
        importedDirty = !importedReadOnly
        Log.i(TAG, "imported listening history removed")
        notifyChanged()
    }

    // --- aggregation -------------------------------------------------------------------------------------------------

    /** Accumulators of one aggregation pass (per track / artist group / album group). */
    private class Acc(n: Int) {
        val streams = IntArray(n)
        val ms = LongArray(n)
        val last = LongArray(n)

        fun bump(i: Int, listenedMs: Long, startedAt: Long) {
            streams[i]++
            ms[i] += listenedMs
            if (startedAt > last[i]) last[i] = startedAt
        }
    }

    private inner class Tally {
        val tracks = Acc(idx.tracks.size)
        val artists = Acc(idx.artists.size)
        val albums = Acc(idx.albums.size)
        var listenedMs = 0L
        var streams = 0

        /** True when the play is a stream. */
        fun add(track: Int, startedAt: Long, listenedMs: Long): Boolean {
            this.listenedMs += listenedMs
            if (!Listen.counts(listenedMs, idx.tracks[track].durationMs)) return false
            streams++
            tracks.bump(track, listenedMs, startedAt)
            for (a in idx.trackArtists[track]) artists.bump(a, listenedMs, startedAt)
            val al = idx.trackAlbum[track]
            if (al >= 0) albums.bump(al, listenedMs, startedAt)
            return true
        }
    }

    private fun currentCounts() = cur.active && cur.listenedMs >= Listen.MIN_PLAY_MS

    /** Most streams, then more time, then the most recent: (count with a stream, the top n of them). */
    private fun top(acc: Acc, topN: Int): Pair<Int, IntArray> {
        fun better(a: Int, b: Int): Boolean {
            if (acc.streams[a] != acc.streams[b]) return acc.streams[a] > acc.streams[b]
            if (acc.ms[a] != acc.ms[b]) return acc.ms[a] > acc.ms[b]
            return acc.last[a] > acc.last[b]
        }
        var count = 0
        val n = acc.streams.size
        if (topN <= 64) {
            // Partial selection (std::partial_sort): insertion into a small sorted window.
            val best = IntArray(topN)
            var size = 0
            for (i in 0 until n) {
                if (acc.streams[i] <= 0) continue
                count++
                if (size == topN && (topN == 0 || !better(i, best[size - 1]))) continue
                var at = if (size < topN) size else size - 1
                while (at > 0 && better(i, best[at - 1])) {
                    if (at < topN) best[at] = best[at - 1]
                    at--
                }
                best[at] = i
                if (size < topN) size++
            }
            return count to best.copyOf(size)
        }
        val all = ArrayList<Int>()
        for (i in 0 until n) if (acc.streams[i] > 0) all.add(i)
        count = all.size
        all.sortWith { a, b -> if (better(a, b)) -1 else if (better(b, a)) 1 else 0 }
        return count to all.take(topN).toIntArray()
    }

    private class Tops(
        val tracks: List<StatsTrack>,
        val artists: List<StatsArtist>,
        val albums: List<StatsAlbum>,
        val distinctTracks: Int,
        val distinctArtists: Int,
    )

    private fun tops(t: Tally, topN: Int): Tops {
        val (nt, it) = top(t.tracks, topN)
        val tracks = it.map { i -> StatsTrack(toCatalog(idx.tracks[i]), t.tracks.streams[i], t.tracks.ms[i]) }
        val (na, ia) = top(t.artists, topN)
        val artists = ia.map { i ->
            val g = idx.artists[i]
            StatsArtist(ArtistRef(g.id, g.name), t.artists.streams[i], t.artists.ms[i])
        }
        val (_, ib) = top(t.albums, topN)
        val albums = ib.map { i ->
            val g = idx.albums[i]
            StatsAlbum(
                AlbumRef(g.id, g.name, if (g.image.isNotEmpty()) listOf(Image(g.image)) else emptyList()),
                g.artist, g.various, t.albums.streams[i], t.albums.ms[i],
            )
        }
        return Tops(tracks, artists, albums, nt, na)
    }

    private fun periodStart(period: StatsPeriod, unixNow: Long) = when (period) {
        StatsPeriod.Week -> unixNow - 7 * 86400
        StatsPeriod.Month -> unixNow - 30 * 86400
        StatsPeriod.All -> Long.MIN_VALUE
    }

    /** Aggregates the period ending at [unixNow]; the current play is included provisionally. */
    @Synchronized
    fun summarize(period: StatsPeriod, unixNow: Long, topN: Int = 10, recentN: Int = 20): StatsSummary {
        val start = periodStart(period, unixNow)
        val withCurrent = currentCounts()
        val periodStart = if (period == StatsPeriod.All) {
            if (plays.isNotEmpty()) plays.startedAt(0) else if (withCurrent) cur.startedAt else 0
        } else start

        val t = Tally()
        val first = plays.lowerBound(start)
        for (i in first until plays.size) t.add(plays.track(i), plays.startedAt(i), plays.listenedMs(i))
        val currentIn = withCurrent && cur.startedAt >= start
        if (currentIn) t.add(cur.track, cur.startedAt, cur.listenedMs)

        // Recent streams, newest first (the current play is the newest once it counts).
        val recent = ArrayList<StatsPlay>()
        fun pushRecent(track: Int, startedAt: Long, ms: Long) {
            if (recent.size >= recentN || !Listen.counts(ms, idx.tracks[track].durationMs)) return
            recent.add(StatsPlay(toCatalog(idx.tracks[track]), startedAt, ms))
        }
        if (currentIn) pushRecent(cur.track, cur.startedAt, cur.listenedMs)
        var i = plays.size - 1
        while (i >= first && recent.size < recentN) {
            pushRecent(plays.track(i), plays.startedAt(i), plays.listenedMs(i))
            i--
        }
        val tp = tops(t, topN)
        return StatsSummary(periodStart, t.listenedMs, t.streams, tp.distinctTracks, tp.distinctArtists, tp.tracks, tp.artists,
            tp.albums, recent)
    }

    /** Local calendar years with plays, newest first. */
    @Synchronized
    fun years(clock: LocalClock): List<Int> {
        val out = ArrayList<Int>() // ascending while collecting
        fun add(at: Long) {
            val y = localYear(clock, at)
            if (!out.contains(y)) out.add(y)
        }
        // Plays are in time order: jump to the first play of the next year instead of converting every one.
        var i = 0
        while (i < plays.size) {
            val y = localYear(clock, plays.startedAt(i))
            if (out.isEmpty() || out.last() != y) add(plays.startedAt(i))
            // Anything before (next Jan 1 local, minus the largest UTC offset) is still this year or next year's first day.
            val nextYearUtc = Listen.daysFromCivil(y + 1, 1, 1) * 86400 - 14 * 3600
            var next = plays.lowerBound(nextYearUtc, i)
            if (next == i) next++
            // Between i and next everything is year y (or earlier): walk the few plays around the boundary exactly.
            i = next
            while (i < plays.size && localYear(clock, plays.startedAt(i)) == y) i++
        }
        if (currentCounts()) add(cur.startedAt)
        out.sortDescending()
        return out
    }

    /** One local calendar year; the current play is included provisionally. */
    @Synchronized
    fun summarizeYear(year: Int, clock: LocalClock, topN: Int = 5): YearSummary {
        val y = YearSummary(year)
        // UTC range that surely contains the local year (offsets are within +-14 h), filtered exactly by the clock.
        val fromDay = Listen.daysFromCivil(year, 1, 1)
        val toDay = Listen.daysFromCivil(year + 1, 1, 1)
        val lo = fromDay * 86400 - 14 * 3600
        val hi = toDay * 86400 + 14 * 3600
        val first = plays.lowerBound(lo)

        // Artists streamed in an earlier year (for "new artists"): every stream before this year's range, plus the
        // ones in the boundary margin that the clock puts in an earlier year.
        val seenBefore = BooleanArray(idx.artists.size)
        for (i in 0 until first)
            if (Listen.counts(plays.listenedMs(i), idx.tracks[plays.track(i)].durationMs))
                for (a in idx.trackArtists[plays.track(i)]) seenBefore[a] = true

        val t = Tally()
        var streamDays = LongArray(64) // local day numbers with a stream, ascending
        var dayExample = LongArray(64) // a stream's unix time on each of those days
        var nDays = 0
        var firstStream: Long = Long.MAX_VALUE
        var firstTrack = -1
        var firstMs = 0L
        fun take(track: Int, startedAt: Long, ms: Long) {
            val local = clock.toLocal(startedAt)
            val day = Listen.dayNumber(local)
            if (day < fromDay) { // an earlier year (boundary margin)
                if (Listen.counts(ms, idx.tracks[track].durationMs)) for (a in idx.trackArtists[track]) seenBefore[a] = true
                return
            }
            if (day >= toDay) return
            val month = Listen.packedMonth(Listen.civilPacked(day))
            y.monthMs[month - 1] += ms
            y.weekdayMs[Listen.weekday(day)] += ms
            spread(y.heatmap, clock, startedAt, ms)
            if (!t.add(track, startedAt, ms)) return
            if (firstTrack < 0 || startedAt < firstStream) {
                firstStream = startedAt
                firstTrack = track
                firstMs = ms
            }
            if (nDays == 0 || streamDays[nDays - 1] != day) {
                if (nDays == streamDays.size) {
                    streamDays = streamDays.copyOf(nDays * 2)
                    dayExample = dayExample.copyOf(nDays * 2)
                }
                streamDays[nDays] = day
                dayExample[nDays] = startedAt
                nDays++
            }
        }
        var i = first
        while (i < plays.size && plays.startedAt(i) < hi) {
            take(plays.track(i), plays.startedAt(i), plays.listenedMs(i))
            i++
        }
        if (currentCounts() && cur.startedAt >= lo && cur.startedAt < hi) take(cur.track, cur.startedAt, cur.listenedMs)
        finishHeatmap(y.heatmap)
        if (firstTrack >= 0) y.firstStream = StatsPlay(toCatalog(idx.tracks[firstTrack]), firstStream, firstMs)

        y.listenedMs = t.listenedMs
        y.streams = t.streams
        for (a in 0 until t.artists.streams.size) if (t.artists.streams[a] > 0 && !seenBefore[a]) y.newArtists++
        for (m in 0 until 12)
            if (y.monthMs[m] > 0 && (y.topMonth < 0 || y.monthMs[m] > y.monthMs[y.topMonth])) y.topMonth = m
        for (d in 0 until 7)
            if (y.weekdayMs[d] > 0 && (y.topWeekday < 0 || y.weekdayMs[d] > y.weekdayMs[y.topWeekday])) y.topWeekday = d

        // The current play may be the only one out of time order: days are sorted before the streak walk.
        var sorted = true
        for (k in 1 until nDays) if (streamDays[k - 1] > streamDays[k]) {
            sorted = false
            break
        }
        if (!sorted) {
            val days = (0 until nDays).map { streamDays[it] to dayExample[it] }
                .sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
            nDays = 0
            for ((d, ex) in days) {
                if (nDays > 0 && streamDays[nDays - 1] == d) continue
                streamDays[nDays] = d
                dayExample[nDays] = ex
                nDays++
            }
        }
        y.activeDays = nDays
        var k = 0
        while (k < nDays) {
            var j = k + 1
            while (j < nDays && streamDays[j] == streamDays[j - 1] + 1) j++
            if (j - k > y.longestStreak) {
                y.longestStreak = j - k
                y.streakFrom = dayExample[k]
                y.streakTo = dayExample[j - 1]
            }
            k = j
        }
        val tp = tops(t, topN)
        y.topTracks = tp.tracks
        y.topArtists = tp.artists
        y.topAlbums = tp.albums
        y.distinctTracks = tp.distinctTracks
        y.distinctArtists = tp.distinctArtists
        return y
    }

    /** Hour x weekday listening of the period ending at [unixNow], on the local clock. */
    @Synchronized
    fun heatmap(period: StatsPeriod, unixNow: Long, clock: LocalClock): StatsHeatmap {
        val h = StatsHeatmap()
        val start = periodStart(period, unixNow)
        for (i in plays.lowerBound(start) until plays.size) spread(h, clock, plays.startedAt(i), plays.listenedMs(i))
        if (currentCounts() && cur.startedAt >= start) spread(h, clock, cur.startedAt, cur.listenedMs)
        finishHeatmap(h)
        return h
    }

    companion object {
        private const val TAG = "stats"

        /** listening-imported.json next to [file] (`<stem>-imported<ext>`). */
        @JvmStatic
        fun importedFileFor(file: File): File {
            val name = file.name
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            return File(file.parentFile, "$stem-imported$ext")
        }

        @JvmStatic
        fun fromCatalog(t: Track): ListenTrack = ListenTrack(
            id = t.id,
            name = t.name,
            artists = t.artists,
            albumId = t.album.id,
            albumName = t.album.name,
            image = t.album.images.pick(300)?.url ?: "",
            durationMs = maxOf(0, t.durationMs).toLong(),
        )

        @JvmStatic
        fun toCatalog(t: ListenTrack): Track = Track(
            id = t.id,
            name = t.name,
            artists = t.artists,
            album = AlbumRef(t.albumId, t.albumName, if (t.image.isNotEmpty()) listOf(Image(t.image, 0, 0)) else emptyList()),
            durationMs = minOf(t.durationMs, Int.MAX_VALUE.toLong()).toInt(),
        )

        /** Grouping keys of a track. */
        @JvmStatic
        fun keysFor(t: ListenTrack): ListenKeys = ListenKeys.of(t)

        // --- reading -------------------------------------------------------------------------------------------

        private enum class ReadResult { Ok, Missing, Corrupt, Unreadable }

        private fun sibling(file: File, suffix: String) = File(file.path + suffix)

        /** Reads a whole file: missing, unreadable (locked / I/O error: retried briefly) or read. */
        private fun readText(file: File): Pair<ReadResult, String?> {
            val exists = try {
                file.exists()
            } catch (_: SecurityException) {
                return ReadResult.Unreadable to null
            }
            if (!exists) return ReadResult.Missing to null
            for (attempt in 0 until 3) {
                if (attempt > 0) Thread.sleep(200) // a scanner / backup holding it
                try {
                    return ReadResult.Ok to file.readBytes().toString(Charsets.UTF_8)
                } catch (_: IOException) {
                } catch (_: SecurityException) {
                }
            }
            return ReadResult.Unreadable to null
        }

        private fun snapshotFromDoc(doc: ListenJson.Doc): Snapshot {
            val s = Snapshot()
            // file track index -> index track (the same song twice in the file merges)
            val tracks = doc.tracks
            val remap = IntArray(tracks?.size ?: 0)
            tracks?.forEachIndexed { i, t -> remap[i] = s.index.intern(ListenJson.trackFromJson(t)) }
            val raw = doc.native
            s.plays.ensureCapacity(raw.size)
            for (i in 0 until raw.size) {
                val t = raw.track(i)
                val ms = raw.listenedMs(i)
                if (t >= remap.size || ms < Listen.MIN_PLAY_MS) continue
                s.plays.add(remap[t], raw.startedAt(i), ms, false)
            }
            s.plays.sortByStart()
            if (s.plays.size > Listen.MAX_PLAYS) s.plays.dropFirst(s.plays.size - Listen.MAX_PLAYS)
            return s
        }

        /** One history file: missing, unreadable, unparsable, or parsed into a snapshot. */
        private fun readOne(file: File): Pair<ReadResult, Snapshot?> {
            val (r, text) = readText(file)
            if (r != ReadResult.Ok) return r to null
            val doc = ListenJson.parse(text!!, ListenJson.Mode.Native)
            if (doc == null || !doc.isObject || !doc.hasTracks || !doc.hasPlays) return ReadResult.Corrupt to null
            return ReadResult.Ok to snapshotFromDoc(doc)
        }

        /**
         * listening-imported.json content, interned into [s] (whose index may already hold this device's tracks).
         * Plays are appended unsorted; false when the document isn't one.
         */
        private fun importedFromDoc(doc: ListenJson.Doc, s: Snapshot): Boolean {
            if (!doc.isObject || doc.tracks == null || !doc.playsIsArray) return false
            val tracks = doc.tracks!!
            val remap = IntArray(tracks.size)
            tracks.forEachIndexed { i, t -> remap[i] = s.index.intern(ListenJson.trackFromJson(t)) }
            val n = doc.flatSize / 3
            s.plays.ensureCapacity(s.plays.size + n)
            val v = doc.flatValues
            val k = doc.flatKinds
            var at = 0L
            for (i in 0 until n) {
                val kt = k[i * 3]
                val kd = k[i * 3 + 1]
                val km = k[i * 3 + 2]
                if (kt != ListenJson.UNSIGNED || (kd != ListenJson.UNSIGNED && kd != ListenJson.NEGATIVE) ||
                    (km != ListenJson.UNSIGNED && km != ListenJson.NEGATIVE)
                ) return false
                at += v[i * 3 + 1]
                val ti = v[i * 3]
                val len = v[i * 3 + 2]
                if (ti >= remap.size || len < Listen.MIN_PLAY_MS) continue
                s.plays.add(remap[ti.toInt()], at, len, true)
            }
            return true
        }

        /** One imported file, interned into [out] (on failure [out] is left as it was). */
        private fun readImportedOne(file: File, out: Snapshot): ReadResult {
            val (r, text) = readText(file)
            if (r != ReadResult.Ok) return r
            val doc = ListenJson.parse(text!!, ListenJson.Mode.Flat)
            if (doc == null || !doc.isObject) return ReadResult.Corrupt
            val s = Snapshot()
            s.index = out.index.copy() // a copy: a half-read file must not leave tracks behind
            if (!importedFromDoc(doc, s)) return ReadResult.Corrupt
            out.index = s.index
            out.plays.addAll(s.plays)
            return ReadResult.Ok
        }

        private fun backup(file: File): Boolean = try {
            // Keep the broken file aside for a look (an existing .bak is not overwritten).
            val bak = sibling(file, ".bak")
            if (!bak.exists()) file.copyTo(bak)
            true
        } catch (_: Exception) {
            false
        }

        /** The main file with the .old / .bak fallbacks (see [readFile]). */
        private fun readMain(file: File): Snapshot {
            val (main, s) = readOne(file)
            if (main == ReadResult.Ok) return s!!
            if (main == ReadResult.Unreadable) { // there but unreadable: start empty and never replace it
                return Snapshot().apply { keepFile = true }
            }
            var keep = false
            if (main == ReadResult.Corrupt) {
                // If even keeping a copy fails, the file is never replaced this session.
                keep = !backup(file)
            }
            // Missing (a crash between the two renames of write()) or broken: the previous generation.
            val (prev, old) = readOne(sibling(file, ".old"))
            if (prev == ReadResult.Ok) {
                old!!.fromOld = true
                old.corrupt = main == ReadResult.Corrupt
                old.keepFile = keep
                return old
            }
            return Snapshot().apply {
                corrupt = main == ReadResult.Corrupt
                keepFile = keep
            }
        }

        /**
         * The imported companion into [s]: missing = none; unreadable or broken without a usable .old =
         * importedKeepFile (a broken one is kept as .bak first).
         */
        private fun readImported(file: File, s: Snapshot) {
            val r = readImportedOne(file, s)
            if (r == ReadResult.Ok || r == ReadResult.Missing) {
                if (r == ReadResult.Missing && readImportedOne(sibling(file, ".old"), s) == ReadResult.Ok)
                    Log.w(TAG, "listening-imported.json missing; restored the previous copy (.old)")
                return
            }
            if (r == ReadResult.Corrupt) {
                s.importedCorrupt = true
                backup(file)
                if (readImportedOne(sibling(file, ".old"), s) == ReadResult.Ok) {
                    Log.w(TAG, "listening-imported.json is corrupt; restored the previous copy (.old)")
                    return
                }
            }
            s.importedKeepFile = true
        }

        /**
         * Reads and validates a history file and its imported companion (each falls back to its .old; a locked file
         * is retried briefly). Pure: worker-safe.
         */
        @JvmStatic
        fun readFile(file: File): Snapshot {
            var s = try {
                readMain(file)
            } catch (e: Exception) {
                Log.w(TAG, "reading ${file.name} failed", e)
                Snapshot().apply { keepFile = true }
            }
            val native = s.plays.size
            try {
                readImported(importedFileFor(file), s)
            } catch (e: Exception) {
                Log.w(TAG, "reading the imported history failed", e)
                s.plays.truncate(native)
                s.importedKeepFile = true
            }
            if (s.plays.size > native) {
                s.plays.sortByStart()
                if (s.plays.size - native > Listen.MAX_IMPORTED_PLAYS) { // written by a build with a larger bound
                    var drop = s.plays.size - native - Listen.MAX_IMPORTED_PLAYS
                    val p = s.plays
                    p.retain { i ->
                        if (drop == 0 || !p.imported(i)) true
                        else {
                            drop--
                            false
                        }
                    }
                }
            }
            return s
        }

        // --- writing -------------------------------------------------------------------------------------------

        private val generation = AtomicLong(0)
        /** One writer per process at a time (worker or exit). */
        private val writeLock = Any()
        /** Per file, under writeLock. */
        private val writtenGeneration = HashMap<String, Long>()

        /** Only the tracks [plays] use, renumbered in first-use order (the plays are rewritten to the new numbers). */
        private fun usedTracks(all: List<ListenTrack>, plays: PlayList): List<ListenTrack> {
            val out = ArrayList<ListenTrack>()
            val newIndex = IntArray(all.size) { -1 }
            for (i in 0 until plays.size) {
                val t = plays.track(i)
                var n = newIndex[t]
                if (n < 0) {
                    n = out.size
                    newIndex[t] = n
                    out.add(all[t].copy())
                }
                plays.setTrack(i, n)
            }
            return out
        }

        /** Writes [bytes] to [tmp] and flushes it to the disk. False (and no .tmp left behind) on any failure. */
        private fun writeFlushed(tmp: File, bytes: ByteArray): Boolean = try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to write ${tmp.name}", e)
            tmp.delete()
            false
        }

        /**
         * Serializes and replaces the file (flushed .tmp, previous file kept as .old). Any thread; writers of the
         * same file are serialized and an older generation is skipped. Returns false on I/O failure.
         */
        @JvmStatic
        fun write(job: SaveJob): Boolean {
            val file = job.file ?: return false
            return try {
                val bytes = ListenJson.serialize(job.tracks, job.plays, job.imported).toByteArray(Charsets.UTF_8)
                synchronized(writeLock) {
                    val last = writtenGeneration[file.path] ?: 0L
                    if (job.generation < last) return true // a newer save already landed
                    file.parentFile?.mkdirs()
                    val tmp = sibling(file, ".tmp")
                    if (!writeFlushed(tmp, bytes)) return false
                    // Keep the previous generation (readFile falls back to it), then move the new file in. A failed
                    // rotation only costs the fallback copy.
                    if (job.rotate && file.exists()) {
                        val old = sibling(file, ".old")
                        old.delete()
                        if (!file.renameTo(old)) Log.w(TAG, "could not keep ${file.name}.old")
                    }
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        if (!tmp.renameTo(file)) {
                            Log.e(TAG, "failed to replace ${file.name}")
                            tmp.delete()
                            return false
                        }
                    }
                    writtenGeneration[file.path] = job.generation
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "save failed", e)
                false
            }
        }

        // --- importing -----------------------------------------------------------------------------------------

        /** Start ms / length ms of one song's known plays, ordered by start. */
        private class Known {
            var starts = LongArray(4)
            var lengths = LongArray(4)
            var size = 0

            fun add(start: Long, len: Long) {
                if (size == starts.size) {
                    starts = starts.copyOf(size * 2)
                    lengths = lengths.copyOf(size * 2)
                }
                starts[size] = start
                lengths[size] = len
                size++
            }

            /** Keeps the order (std::upper_bound + insert). */
            fun insert(start: Long, len: Long) {
                var lo = 0
                var hi = size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (start < starts[mid]) hi = mid else lo = mid + 1
                }
                add(0, 0)
                System.arraycopy(starts, lo, starts, lo + 1, size - 1 - lo)
                System.arraycopy(lengths, lo, lengths, lo + 1, size - 1 - lo)
                starts[lo] = start
                lengths[lo] = len
            }

            fun sort() {
                var ok = true
                for (i in 1 until size) if (starts[i - 1] > starts[i]) {
                    ok = false
                    break
                }
                if (ok) return
                val order = (0 until size).sortedWith { a, b -> starts[a].compareTo(starts[b]) }
                val s = LongArray(size) { starts[order[it]] }
                val l = LongArray(size) { lengths[order[it]] }
                starts = s
                lengths = l
            }
        }

        /** Is a new row a play already in [known] (the same song's plays, ordered by start)? See [Listen.SAME_END_S]. */
        private fun knownPlay(known: Known, startMs: Long, lenMs: Long): Boolean {
            val scanMs = 6 * 3600 * 1000L // plays that could overlap start at most this much earlier
            val endMs = startMs + lenMs
            var lo = 0
            var hi = known.size
            val from = startMs - scanMs
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (known.starts[mid] < from) lo = mid + 1 else hi = mid
            }
            var i = lo
            while (i < known.size && known.starts[i] <= endMs + Listen.SAME_END_S * 1000) {
                val kStart = known.starts[i]
                val kLen = known.lengths[i]
                val kEnd = kStart + kLen
                val overlap = minOf(endMs, kEnd) - maxOf(startMs, kStart)
                if (overlap > 0 && overlap * 2 >= minOf(lenMs, kLen)) return true
                if (Math.abs(endMs - kEnd) <= Listen.SAME_END_S * 1000 && Math.abs(lenMs - kLen) <= Listen.SAME_LENGTH_MS)
                    return true
                i++
            }
            return false
        }

        /**
         * Worker: dedupes [rows] against everything known (this device's plays in [base] and the imported file),
         * writes listening-imported.json and builds the new history.
         */
        @JvmStatic
        fun buildImport(base: ImportBase, rows: List<ImportRow>): ImportResult {
            val r = ImportResult(base.token)
            r.rows = rows.size
            val importedFile = base.importedFile
            if (importedFile == null) {
                r.error = "import refused"
                return r
            }
            try {
                val s = r.snapshot
                // This device's plays, then what was imported before.
                val remap = IntArray(base.tracks.size) { s.index.intern(base.tracks[it].copy()) }
                s.plays.ensureCapacity(base.plays.size)
                for (i in 0 until base.plays.size)
                    s.plays.add(remap[base.plays.track(i)], base.plays.startedAt(i), base.plays.listenedMs(i), false)
                readImported(importedFile, s)
                if (s.importedKeepFile) {
                    r.error = "listening-imported.json unreadable"
                    return r
                }
                s.plays.sortByStart()

                // The new rows, in time order.
                val fresh = PlayList(rows.size)
                for (row in rows) {
                    if (row.listenedMs < Listen.MIN_PLAY_MS) {
                        r.tooShort++
                        continue
                    }
                    val start = row.endedAt - (row.listenedMs + 500) / 1000
                    fresh.add(s.index.intern(row.track.copy()), start, row.listenedMs, true)
                }
                fresh.sortByStart()

                // Everything known per song (only the songs the rows mention), then each row against it and the
                // rows before.
                val known = HashMap<Int, Known>()
                for (i in 0 until fresh.size) known.getOrPut(fresh.track(i)) { Known() }
                for (i in 0 until s.plays.size) known[s.plays.track(i)]?.add(s.plays.startedAt(i) * 1000, s.plays.listenedMs(i))
                for (k in known.values) k.sort()
                val added = PlayList(fresh.size)
                for (i in 0 until fresh.size) {
                    val k = known.getValue(fresh.track(i))
                    val startMs = fresh.startedAt(i) * 1000
                    if (knownPlay(k, startMs, fresh.listenedMs(i))) {
                        r.duplicates++
                        continue
                    }
                    k.insert(startMs, fresh.listenedMs(i))
                    added.add(fresh.track(i), fresh.startedAt(i), fresh.listenedMs(i), true)
                }
                r.added = added.size
                if (added.isEmpty()) { // nothing new: nothing to write or install
                    r.snapshot = Snapshot()
                    r.ok = true
                    return r
                }
                s.plays.addAll(added)
                s.plays.sortByStart() // std::inplace_merge (stable: this device's plays first on equal starts)
                // The imported bound: the oldest go.
                val importedTotal = s.plays.countImported()
                if (importedTotal > Listen.MAX_IMPORTED_PLAYS) {
                    var drop = importedTotal - Listen.MAX_IMPORTED_PLAYS
                    val p = s.plays
                    p.retain { i ->
                        if (drop == 0 || !p.imported(i)) true
                        else {
                            drop--
                            false
                        }
                    }
                }

                val jobPlays = PlayList(importedTotal)
                for (i in 0 until s.plays.size)
                    if (s.plays.imported(i)) jobPlays.add(s.plays.track(i), s.plays.startedAt(i), s.plays.listenedMs(i), true)
                val job = SaveJob(importedFile, usedTracks(s.index.tracks, jobPlays), jobPlays, generation.incrementAndGet(),
                    rotate = !s.importedCorrupt, imported = true)
                if (!write(job)) {
                    r.error = "writing listening-imported.json failed"
                    r.snapshot = Snapshot()
                    return r
                }
                s.importedCorrupt = false
                r.ok = true
                Log.i(TAG, "import: ${r.rows} rows, ${r.added} new plays, ${r.duplicates} already known, ${r.tooShort} too short")
                return r
            } catch (e: Exception) {
                Log.e(TAG, "import failed", e)
                return ImportResult(base.token).apply {
                    error = e.message ?: e.javaClass.simpleName
                    this.rows = rows.size
                }
            }
        }

        // --- heatmap helpers -------------------------------------------------------------------------------------

        /** Adds a play's listening to the heatmap: from its start on, split at every local hour boundary it crosses. */
        private fun spread(h: StatsHeatmap, clock: LocalClock, startedAt: Long, listenedMs: Long) {
            var at = startedAt
            var left = listenedMs
            var guard = 0
            while (left > 0 && guard < 48) {
                val local = clock.toLocal(at)
                val day = Listen.dayNumber(local)
                val secOfDay = local - day * 86400
                val hour = (secOfDay / 3600).toInt()
                val toNext = (3600 - secOfDay % 3600) * 1000
                val part = minOf(left, toNext)
                h.ms[Listen.weekday(day) * 24 + hour] += part
                left -= part
                at += (part + 999) / 1000
                guard++
            }
            h.totalMs += listenedMs
        }

        private fun finishHeatmap(h: StatsHeatmap) {
            var max = 0L
            for (v in h.ms) if (v > max) max = v
            h.maxMs = max
        }

        /** Local calendar year of a unix instant. */
        private fun localYear(clock: LocalClock, unixUtc: Long): Int =
            Listen.packedYear(Listen.civilPacked(Listen.dayNumber(clock.toLocal(unixUtc))))
    }
}
