package com.app.shadetube.downloads

import com.app.shadetube.catalog.ModelJson
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Files
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.Settings
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.Time
import com.app.shadetube.core.appScope
import com.app.shadetube.core.arr
import com.app.shadetube.core.get
import com.app.shadetube.core.int
import com.app.shadetube.core.isObject
import com.app.shadetube.core.isString
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.long
import com.app.shadetube.core.str
import com.app.shadetube.core.stringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.SecureRandom

enum class DlState { Queued, Downloading, Done, Failed }

data class DownloadItem(
    val track: Track,
    val state: DlState = DlState.Queued,
    /** 0..1 while Downloading. */
    val progress: Float = 0f,
    /** Set when Done (absolute path). */
    val filePath: String = "",
    /** Set when Failed (technical detail). */
    val error: String = "",
    /** Set when Failed (the UI maps it to text); null for records of the desktop app. */
    val failure: DownloadFailure? = null,
    val sizeBytes: Long = 0,
    /** Unix seconds. */
    val addedAt: Long = 0,
    /** SponsorBlock segments removed from the file (0 = none / not trimmed). */
    val cutSegments: Int = 0,
    /** Audio removed by those cuts, ms. */
    val cutMs: Long = 0,
    /**
     * Queued by the download sync ([DownloadSync]), not by the user; a manual download or a folder add makes it the
     * user's own (sync never deletes those).
     */
    val synced: Boolean = false,
) {
    val isActive: Boolean get() = state == DlState.Queued || state == DlState.Downloading
}

/** A user folder of downloads ("col:<hex>"). */
data class DownloadCollection(
    val id: String,
    val name: String,
    /** Ids of downloaded tracks in this collection. */
    val trackIds: List<String> = emptyList(),
    val createdAt: Long = 0,
)

/** What the Downloads page and Settings › storage observe. */
data class DownloadsSnapshot(
    val items: List<DownloadItem> = emptyList(),
    val collections: List<DownloadCollection> = emptyList(),
    /** The track downloading right now (null = idle). */
    val activeId: String? = null,
) {
    /** Queued + downloading. */
    val activeCount: Int get() = items.count { it.isActive }
    /** Every finished download on disk. */
    val totalBytes: Long get() = items.filter { it.state == DlState.Done }.sumOf { it.sizeBytes }
    /** Finished sync downloads on disk (what the sync storage cap counts, without the queued estimates). */
    val syncedBytes: Long get() = items.filter { it.synced && it.state == DlState.Done }.sumOf { it.sizeBytes }
    val ownBytes: Long get() = totalBytes - syncedBytes
    val doneCount: Int get() = items.count { it.state == DlState.Done }
}

/** The connection as the queue sees it ([NetworkMonitor] implements it). */
interface NetworkState {
    val connected: Boolean
    /** Metered (mobile data, a metered Wi-Fi hotspot). */
    val metered: Boolean
}

/** What the C++ toasted when a download finished (the UI words it; cutSummary's text is the UI's too). */
sealed interface DownloadEvent {
    /** "\"X\" indirildi · 2 bölüm kesildi (54 sn)": a download with SponsorBlock cuts finished. */
    data class Trimmed(val track: Track, val segments: Int, val cutMs: Long) : DownloadEvent
    data class Failed(val track: Track, val failure: DownloadFailure, val detail: String) : DownloadEvent
}

/** Why queued downloads do not start (null = they do / nothing is queued). */
enum class QueueWait { Offline, WifiOnly, SyncGate }

/**
 * Download manager (port of app/Downloads): a background queue that resolves each track to a YouTube stream,
 * downloads it ([TrackDownloader]), writes it under `<root>/<Artist>/<Album>/` and records it in `downloads.json`.
 * Users also group downloads into their own "collections" (virtual folders). One download at a time (gentle on
 * YouTube).
 *
 * With Settings.sponsorBlockEnabled the downloader trims the matched video's SponsorBlock segments
 * (music_offtopic / sponsor / selfpromo / interaction); a failed lookup never fails the download (it's just not
 * trimmed).
 *
 * Android: thread-safe (one lock; the work runs in [scope] on IO). The queue itself is persisted too (`"s": "queued"`,
 * which the desktop loader skips as a record without a file), so a download queue survives the process being killed;
 * [onWorkAvailable] asks for the foreground worker ([DownloadWorker]) that keeps the process alive while it runs.
 * Gating: every download waits while offline or, with Settings.downloadWifiOnly, on a metered connection; sync
 * downloads also wait while [syncAllowed] says no (sync paused, metered connection without syncOnMetered).
 */
class DownloadManager(
    private val downloader: TrackDownloader,
    private val storeFile: () -> File = { Paths.file("downloads.json") },
    private val rootDir: () -> File = { Paths.downloadsDir },
    private val scope: CoroutineScope = appScope,
    private val network: NetworkState? = null,
    private val settings: () -> Settings = { SettingsStore.current },
    private val now: () -> Long = { Time.nowUnix() },
) {
    private val lock = Any()
    private val items = ArrayList<DownloadItem>()
    private val collections = ArrayList<DownloadCollection>()
    private var activeId: String? = null
    private var activeJob: Job? = null
    /** The active download was cancelled / removed by the user (vs. the process / scope going away). */
    private var userCancelled = false
    private var dirty = false
    private var saveJob: Job? = null
    private var loaded = false

    private val _state = MutableStateFlow(DownloadsSnapshot())
    val state: StateFlow<DownloadsSnapshot> = _state.asStateFlow()
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    /** Sync downloads wait in the queue while this returns false (sync paused, metered connection). Null = allowed. */
    @Volatile var syncAllowed: (() -> Boolean)? = null

    /** Called (outside the lock) when a download starts: AppGraph / [DownloadsRuntime] enqueue the foreground worker. */
    @Volatile var onWorkAvailable: (() -> Unit)? = null

    // ---- queries --------------------------------------------------------------------------------------------------

    private fun find(id: String): Int = items.indexOfFirst { it.track.id == id }

    fun item(trackId: String): DownloadItem? = synchronized(lock) { items.getOrNull(find(trackId)) }
    fun items(): List<DownloadItem> = _state.value.items
    fun isDownloaded(trackId: String): Boolean = item(trackId)?.state == DlState.Done
    /** Queued or downloading. */
    fun isActive(trackId: String): Boolean = item(trackId)?.isActive == true
    fun activeCount(): Int = _state.value.activeCount
    fun totalBytes(): Long = _state.value.totalBytes
    fun syncedBytes(): Long = _state.value.syncedBytes

    /** The downloaded file of a track, for offline playback (null = not downloaded / the file is gone). */
    fun localFileFor(trackId: String): File? {
        val it = item(trackId) ?: return null
        if (it.state != DlState.Done || it.filePath.isEmpty()) return null
        return File(it.filePath).takeIf { f -> f.isFile }
    }

    /** What sync needs to know about every queued / downloaded track. */
    fun itemInfos(): Map<String, SyncRules.ItemInfo> = synchronized(lock) {
        items.associate { d ->
            d.track.id to SyncRules.ItemInfo(
                state = when (d.state) {
                    DlState.Queued -> SyncRules.ItemInfo.St.Queued
                    DlState.Downloading -> SyncRules.ItemInfo.St.Downloading
                    DlState.Done -> SyncRules.ItemInfo.St.Done
                    DlState.Failed -> SyncRules.ItemInfo.St.Failed
                },
                synced = d.synced,
                sizeBytes = d.sizeBytes,
                durationMs = d.track.durationMs,
            )
        }
    }

    // ---- gating ---------------------------------------------------------------------------------------------------

    private fun manualAllowed(): Boolean {
        val n = network ?: return true
        if (!n.connected) return false
        return !(settings().downloadWifiOnly && n.metered)
    }

    private fun syncOk(): Boolean = manualAllowed() && (syncAllowed?.invoke() ?: true)

    /** A download is running or one could start now. */
    fun hasRunnableWork(): Boolean = synchronized(lock) {
        if (activeId != null) return true
        val manual = manualAllowed()
        val sync = manual && (syncAllowed?.invoke() ?: true)
        items.any { it.state == DlState.Queued && (if (it.synced) sync else manual) }
    }

    /** Why queued downloads wait (null = nothing waits). */
    fun queueWait(): QueueWait? = synchronized(lock) {
        if (activeId != null) return null
        val queued = items.filter { it.state == DlState.Queued }
        if (queued.isEmpty()) return null
        val n = network
        if (n != null && !n.connected) return QueueWait.Offline
        if (n != null && settings().downloadWifiOnly && n.metered) return QueueWait.WifiOnly
        if (queued.all { it.synced } && syncAllowed?.invoke() == false) return QueueWait.SyncGate
        null
    }

    // ---- queueing -------------------------------------------------------------------------------------------------

    /**
     * Queue one track (skips tracks already downloaded or queued; a failed one is queued again). Optionally drop it into
     * a collection once finished. A live radio station has nothing to download; a podcast episode downloads as-is on
     * the podcast pages.
     */
    fun enqueue(t: Track, collectionId: String = "") {
        if (t.id.isEmpty() || t.id.startsWith("radio:") || isPodcastId(t.id)) return
        synchronized(lock) {
            val i = find(t.id)
            if (i >= 0) {
                var it = items[i].copy(synced = false) // the user asked for it: their own download from now on
                if (it.state == DlState.Failed) it = it.copy(state = DlState.Queued, error = "", failure = null, progress = 0f) // retry
                items[i] = it
            } else {
                items.add(0, DownloadItem(track = t, state = DlState.Queued, addedAt = now()))
            }
            if (collectionId.isNotEmpty()) addToCollectionLocked(collectionId, t.id)
            touchLocked()
        }
        startNext()
    }

    fun enqueue(tracks: List<Track>, collectionId: String = "") {
        for (t in tracks) enqueue(t, collectionId)
    }

    /** "Retry": a failed download is queued again (as the user's own, like the C++ enqueue). */
    fun retry(trackId: String) {
        item(trackId)?.let { enqueue(it.track) }
    }

    /**
     * Download sync: queues [tracks] after everything else, marked as sync downloads (failed ones are queued again;
     * anything else already known is left as it is). One change notification for the whole batch.
     */
    fun enqueueSynced(tracks: List<Track>) {
        var changed = false
        synchronized(lock) {
            // Index once: a synced Liked Songs list can queue thousands of tracks.
            val index = HashMap<String, Int>(items.size + tracks.size)
            for ((i, d) in items.withIndex()) index.putIfAbsent(d.track.id, i)
            for (t in tracks) {
                if (t.id.isEmpty() || t.id.startsWith("radio:")) continue
                val found = index[t.id]
                if (found != null) {
                    val it = items[found]
                    if (it.state != DlState.Failed) continue // queued, downloading or done: nothing to do
                    items[found] = it.copy(state = DlState.Queued, error = "", failure = null, progress = 0f) // retry (keeps its origin)
                } else {
                    // After the user's own downloads (startNext takes the first queued).
                    items.add(DownloadItem(track = t, state = DlState.Queued, addedAt = now(), synced = true))
                    index[t.id] = items.size - 1
                }
                changed = true
            }
            if (changed) touchLocked()
        }
        if (changed) startNext()
    }

    /** Makes sync downloads the user's own: sync cleanup never touches them again. */
    fun keepAsManual(trackIds: List<String>) = synchronized(lock) {
        var changed = false
        for (id in trackIds) {
            val i = find(id)
            if (i >= 0 && items[i].synced) {
                items[i] = items[i].copy(synced = false)
                changed = true
            }
        }
        if (changed) touchLocked()
    }

    /** Remove from the queue / stop the active one. A finished download is not touched (use [remove]). */
    fun cancel(trackId: String) {
        synchronized(lock) {
            if (trackId == activeId) {
                userCancelled = true
                activeJob?.cancel()
            }
            // Queued (not active) -> just drop it.
            val i = find(trackId)
            if (i >= 0 && items[i].state == DlState.Queued) {
                items.removeAt(i)
                touchLocked()
            }
        }
    }

    /** Deletes the file (+ its lyrics sidecar) and the record (+ from collections). */
    fun remove(trackId: String) {
        var file: File? = null
        synchronized(lock) {
            if (trackId == activeId) {
                userCancelled = true
                activeJob?.cancel()
            }
            val i = find(trackId)
            if (i >= 0 && items[i].filePath.isNotEmpty()) file = File(items[i].filePath)
            items.removeAll { it.track.id == trackId }
            removeFromAllCollectionsLocked(trackId)
            touchLocked()
        }
        file?.let { deleteDownloadFile(it, rootDir()) }
    }

    /** Pumps the queue (the C++ housekeeping tick; also called when a gate opens). */
    fun tick() = startNext()

    private fun startNext() {
        var started = false
        synchronized(lock) {
            if (activeId != null) return // one at a time
            val manual = manualAllowed()
            // Sync downloads wait while sync is paused / on a metered net.
            val sync = manual && (syncAllowed?.invoke() ?: true)
            val i = items.indexOfFirst { it.state == DlState.Queued && (if (it.synced) sync else manual) }
            if (i < 0) return
            val job = items[i]
            val track = job.track
            items[i] = job.copy(state = DlState.Downloading, progress = 0f)
            activeId = track.id
            userCancelled = false
            publishLocked()
            val root = rootDir()
            activeJob = scope.launch(AppDispatchers.io) { run(track, root) }
            started = true
        }
        if (started) onWorkAvailable?.invoke()
    }

    private suspend fun run(track: Track, root: File) {
        val id = track.id
        var outcome: DownloadOutcome? = null
        var cancelled = false
        try {
            outcome = downloader.download(track, { format -> targetFor(track, format, root) }) { f -> reportProgress(id, f) }
        } catch (e: CancellationException) {
            cancelled = true
        } catch (e: Exception) {
            outcome = DownloadOutcome.Failed(DownloadFailure.Other, e.message ?: e.javaClass.simpleName)
        }
        synchronized(lock) {
            if (activeId == id) {
                activeId = null
                activeJob = null
            }
            val i = find(id)
            if (i >= 0) {
                val it = items[i]
                when {
                    cancelled && userCancelled -> {
                        items.removeAt(i)
                        removeFromAllCollectionsLocked(id)
                    }
                    cancelled -> items[i] = it.copy(state = DlState.Queued, progress = 0f) // the process / scope went away
                    outcome is DownloadOutcome.Ok -> {
                        items[i] = it.copy(
                            state = DlState.Done, filePath = outcome.file.absolutePath, sizeBytes = outcome.sizeBytes,
                            progress = 1f, cutSegments = outcome.cutSegments, cutMs = outcome.cutMs, error = "", failure = null,
                        )
                        Log.i(TAG, "\"${track.name}\" downloaded (${outcome.format.ext}, ${outcome.sizeBytes} bytes" +
                            (if (outcome.cutSegments > 0) ", ${outcome.cutSegments} segment(s) / ${outcome.cutMs} ms cut" else "") + ")")
                    }
                    outcome is DownloadOutcome.Failed -> {
                        items[i] = it.copy(state = DlState.Failed, error = outcome.detail, failure = outcome.failure, progress = 0f)
                        Log.w(TAG, "\"${track.name}\" failed: ${outcome.failure} ${outcome.detail}")
                    }
                }
            } else if (outcome is DownloadOutcome.Ok) {
                // Removed while it finished: the file must not stay behind unrecorded.
                deleteDownloadFile(outcome.file, root)
            }
            touchLocked()
        }
        when (outcome) {
            is DownloadOutcome.Ok -> if (outcome.cutSegments > 0) _events.tryEmit(DownloadEvent.Trimmed(track, outcome.cutSegments, outcome.cutMs))
            is DownloadOutcome.Failed -> _events.tryEmit(DownloadEvent.Failed(track, outcome.failure, outcome.detail))
            null -> Unit
        }
        if (!cancelled || userCancelled) startNext() // pump the next queued item
    }

    /** Worker progress (0..1) of the active download; published at >= 1 % steps. */
    fun reportProgress(trackId: String, f: Float) = synchronized(lock) {
        val i = find(trackId)
        if (i < 0) return
        val it = items[i]
        if (it.state != DlState.Downloading) return
        if (f - it.progress >= 0.01f || f >= 1f) {
            items[i] = it.copy(progress = f)
            publishLocked()
        }
    }

    /** The path a download gets; another track's file at that path gets " (2)", " (3)"... appended instead. */
    private fun targetFor(track: Track, format: DownloadFormat, root: File): File {
        val base = downloadTargetPath(track, format, root)
        val taken = synchronized(lock) { items.filter { it.track.id != track.id && it.filePath.isNotEmpty() }.map { it.filePath }.toHashSet() }
        if (base.absolutePath !in taken) return base
        val stem = base.nameWithoutExtension
        for (n in 2..999) {
            val f = File(base.parentFile, "$stem ($n).${format.ext}")
            if (f.absolutePath !in taken && !f.exists()) return f
        }
        return base
    }

    // ---- collections ----------------------------------------------------------------------------------------------

    fun collections(): List<DownloadCollection> = _state.value.collections
    fun collection(id: String): DownloadCollection? = _state.value.collections.firstOrNull { it.id == id }

    /** New folder named [name] (the UI passes its localized default for an empty name). Returns its id. */
    fun createCollection(name: String): String = synchronized(lock) {
        val id = "col:%016x".format(random.nextLong())
        collections.add(0, DownloadCollection(id, name, emptyList(), now()))
        touchLocked()
        id
    }

    fun renameCollection(id: String, name: String) = synchronized(lock) {
        for ((i, c) in collections.withIndex()) if (c.id == id) collections[i] = c.copy(name = name)
        touchLocked()
    }

    fun deleteCollection(id: String) = synchronized(lock) {
        collections.removeAll { it.id == id }
        touchLocked()
    }

    fun addToCollection(id: String, trackId: String) = synchronized(lock) {
        addToCollectionLocked(id, trackId)
        touchLocked()
    }

    /** Queues what is neither downloaded nor queued. */
    fun addToCollection(id: String, tracks: List<Track>) {
        for (t in tracks) {
            if (!isDownloaded(t.id) && !isActive(t.id)) enqueue(t, id) else addToCollection(id, t.id)
        }
    }

    fun removeFromCollection(id: String, trackId: String) = synchronized(lock) {
        for ((i, c) in collections.withIndex()) if (c.id == id) collections[i] = c.copy(trackIds = c.trackIds - trackId)
        touchLocked()
    }

    fun collectionTracks(id: String): List<Track> = synchronized(lock) {
        val c = collections.firstOrNull { it.id == id } ?: return emptyList()
        c.trackIds.mapNotNull { tid -> items.getOrNull(find(tid))?.track }
    }

    private fun addToCollectionLocked(id: String, trackId: String) {
        val i = find(trackId)
        if (i >= 0 && items[i].synced) {
            // In one of the user's folders: their own download now (sync cleanup leaves it alone).
            items[i] = items[i].copy(synced = false)
            dirty = true
        }
        for ((ci, c) in collections.withIndex()) {
            if (c.id == id && trackId !in c.trackIds) collections[ci] = c.copy(trackIds = c.trackIds + trackId)
        }
    }

    private fun removeFromAllCollectionsLocked(trackId: String) {
        for ((i, c) in collections.withIndex()) if (trackId in c.trackIds) collections[i] = c.copy(trackIds = c.trackIds - trackId)
    }

    // ---- persistence ----------------------------------------------------------------------------------------------

    private fun publishLocked() {
        _state.value = DownloadsSnapshot(items.toList(), collections.toList(), activeId)
    }

    private fun touchLocked() {
        dirty = true
        publishLocked()
        scheduleSave()
    }

    private fun scheduleSave() {
        if (!loaded) return
        saveJob?.cancel()
        saveJob = scope.launch(AppDispatchers.io) {
            delay(SAVE_DELAY_MS)
            saveIfDirty()
        }
    }

    /** Reads downloads.json (call once at startup, off the main thread). */
    fun load() {
        val j = Files.readJson(storeFile())
        synchronized(lock) {
            loaded = true
            if (j == null) return
            val (loadedItems, loadedCols) = fromJson(j)
            items.clear()
            items.addAll(loadedItems)
            collections.clear()
            collections.addAll(loadedCols)
            publishLocked()
            Log.i(TAG, "loaded: ${items.size} items, ${collections.size} collections")
        }
    }

    /** Starts pumping: gates that open (network, settings) restart the queue; queued downloads of the last run resume. */
    fun start() {
        scope.launch {
            SettingsStore.flow.map { Triple(it.downloadWifiOnly, it.syncPaused, it.syncOnMetered) }.distinctUntilChanged()
                .collect { tick() }
        }
        (network as? NetworkMonitor)?.let { monitor ->
            scope.launch { monitor.state.collect { tick() } }
        }
        startNext()
    }

    fun saveIfDirty() {
        val json: JsonElement
        synchronized(lock) {
            if (!dirty) return
            dirty = false
            json = toJson(items, collections)
        }
        if (!Files.writeJson(storeFile(), json)) synchronized(lock) { dirty = true }
    }

    /** Writes at once (process going away). */
    fun flush() {
        saveJob?.cancel()
        saveIfDirty()
    }

    companion object {
        private const val TAG = "downloads"
        private const val SAVE_DELAY_MS = 1000L
        private val random = SecureRandom()

        /** Folder name of a track without artists. */
        const val UNKNOWN_ARTIST = "Bilinmeyen Sanatçı"

        /** Like the C++ sanitizeName: control and reserved characters -> '_', no trailing ' ' / '.', at most 80 chars. */
        fun sanitizeName(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) sb.append(if (c.code < 32 || c in "\\/:*?\"<>|") '_' else c)
            var w = sb.toString().trimEnd(' ', '.')
            if (w.isEmpty()) w = "_"
            if (w.length > 80) w = w.substring(0, 80)
            return w
        }

        /** `<root>/<Artist>/<Album>/<Title>.<ext>` (no album: `<root>/<Artist>/<Title>.<ext>`). */
        fun downloadTargetPath(t: Track, format: DownloadFormat, root: File): File {
            val artist = sanitizeName(t.artists.firstOrNull()?.name ?: UNKNOWN_ARTIST)
            val title = sanitizeName(t.name.ifEmpty { t.id })
            var dir = File(root, artist)
            if (t.album.name.isNotEmpty()) dir = File(dir, sanitizeName(t.album.name))
            return File(dir, "$title.${format.ext}")
        }

        /** Deletes a download, its `.lrc` sidecar and leftovers, and the folders it leaves empty (up to [root]). */
        fun deleteDownloadFile(f: File, root: File) {
            f.delete()
            File(f.parentFile, f.nameWithoutExtension + ".lrc").delete()
            File(f.path + ".part").delete()
            File(f.path + ".part.json").delete()
            File(f.path + YoutubeTrackDownloader.SOURCE_PART).delete() // MP3: the downloaded source stream
            File(f.path + YoutubeTrackDownloader.SOURCE_PART + ".json").delete()
            var dir = f.parentFile
            val rootPath = root.absoluteFile.normalize()
            while (dir != null && dir.absoluteFile.normalize() != rootPath && dir.absoluteFile.normalize().startsWith(rootPath)) {
                if (dir.list()?.isEmpty() != true || !dir.delete()) break
                dir = dir.parentFile
            }
        }

        // downloads.json: {"version": 1, "items": [{"t", "s", "f", "z", "at", "e", "cs"?, "cm"?, "sy"?, "ek"?}],
        // "collections": [{"id", "n", "at", "tracks"}]}

        fun toJson(items: List<DownloadItem>, collections: List<DownloadCollection>): JsonElement {
            val arr = ArrayList<JsonElement>()
            for (d in items) {
                val e = LinkedHashMap<String, JsonElement>()
                e["t"] = ModelJson.track(d.track)
                e["s"] = JsonPrimitive(
                    when (d.state) {
                        DlState.Failed -> "failed"
                        DlState.Done -> "done"
                        // Android: the queue survives the process (the desktop loader skips these: no file).
                        DlState.Queued, DlState.Downloading -> "queued"
                    },
                )
                e["f"] = JsonPrimitive(d.filePath)
                e["z"] = JsonPrimitive(d.sizeBytes)
                e["at"] = JsonPrimitive(d.addedAt)
                e["e"] = JsonPrimitive(d.error)
                if (d.cutSegments > 0) {
                    e["cs"] = JsonPrimitive(d.cutSegments)
                    e["cm"] = JsonPrimitive(d.cutMs)
                }
                if (d.synced) e["sy"] = JsonPrimitive(true)
                d.failure?.let { e["ek"] = JsonPrimitive(it.name) }
                arr.add(JsonObject(e))
            }
            val cols = collections.map { c ->
                jsonOf("id" to c.id, "n" to c.name, "at" to c.createdAt, "tracks" to c.trackIds)
            }
            return jsonOf("version" to 1, "items" to JsonArray(arr), "collections" to JsonArray(cols))
        }

        /**
         * Parses downloads.json. A "done" record whose file vanished is dropped (it becomes a fresh, re-queueable
         * download); [fileExists] is injectable for tests.
         */
        fun fromJson(
            j: JsonElement?,
            fileExists: (String) -> Boolean = { File(it).isFile },
        ): Pair<List<DownloadItem>, List<DownloadCollection>> {
            val items = ArrayList<DownloadItem>()
            val cols = ArrayList<DownloadCollection>()
            if (!j.isObject) return items to cols
            val seen = HashSet<String>()
            for (e in j["items"].arr) {
                val track = ModelJson.trackFrom(e["t"])
                if (track.id.isEmpty() || !seen.add(track.id)) continue
                val st = e.str("s", "done")
                val state = when (st) {
                    "failed" -> DlState.Failed
                    "queued" -> DlState.Queued // Android; queued/downloading aren't persisted by the desktop app
                    else -> DlState.Done
                }
                val path = e.str("f")
                if (state == DlState.Done && (path.isEmpty() || !fileExists(path))) continue
                items.add(
                    DownloadItem(
                        track = track,
                        state = state,
                        progress = if (state == DlState.Done) 1f else 0f,
                        filePath = path,
                        error = e.str("e"),
                        failure = e.str("ek").takeIf { it.isNotEmpty() }?.let { n -> DownloadFailure.entries.firstOrNull { it.name == n } },
                        sizeBytes = e.long("z"),
                        addedAt = e.long("at"),
                        cutSegments = maxOf(0, e.int("cs")), // absent in older files: not trimmed
                        cutMs = maxOf(0L, e.long("cm")),
                        synced = e["sy"].let { it is JsonPrimitive && it.content == "true" }, // absent: the user's own
                    ),
                )
            }
            for (e in j["collections"].arr) {
                val id = e.str("id")
                if (id.isEmpty()) continue
                cols.add(
                    DownloadCollection(
                        id = id,
                        name = e.str("n"),
                        trackIds = e["tracks"].arr.mapNotNull { if (it.isString) it.stringOrNull else null },
                        createdAt = e.long("at"),
                    ),
                )
            }
            return items to cols
        }
    }
}
