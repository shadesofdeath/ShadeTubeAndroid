package com.app.shadetube.downloads

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.Settings
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.Time
import com.app.shadetube.core.appScope
import com.app.shadetube.downloads.SyncRules.Attempt
import com.app.shadetube.downloads.SyncRules.ItemInfo
import com.app.shadetube.downloads.SyncRules.Kind
import com.app.shadetube.downloads.SyncRules.ListError
import com.app.shadetube.downloads.SyncRules.Rule
import com.app.shadetube.musicbrainz.MusicBrainz
import com.app.shadetube.spotify.ApiError
import com.app.shadetube.spotify.PlaylistTracks
import com.app.shadetube.spotify.Session
import com.app.shadetube.spotify.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

// ---- What sync needs from other packages (AppGraph wires the implementations) -----------------------------------------

/** A local playlist as the library holds it. */
data class LocalPlaylist(val name: String, val images: List<Image>, val tracks: List<Track>)

/** The local library (library.UserLibrary): Liked Songs and playlists on this device. */
interface LocalCollections {
    fun liked(): List<Track>
    /** Null when there is no such local playlist. */
    fun playlist(id: String): LocalPlaylist?
    /** Fires when the library changed (likes, playlist edits). */
    val changes: Flow<Unit>
}

/** The blocklist (playback package: app/Blacklist). */
interface SyncBlocklist {
    fun isBlocked(t: Track): Boolean
    fun isTrackBlocked(trackId: String): Boolean
}

/** Spotify as sync uses it; [SessionSyncSource] adapts the real [Session]. Reads throw spotify.ApiError / IOException. */
interface SpotifySyncSource {
    val loggedIn: Boolean
    /** Session state or library snapshot changed. */
    val changes: Flow<Unit>
    /** Uri of a playlist the user just edited here. */
    val playlistEdits: Flow<String>
    /** Liked Songs ids of the library snapshot; null until it loaded (or logged out). */
    fun likedIds(): Set<String>?
    suspend fun playlistTracks(uri: String, offset: Int, limit: Int): PlaylistTracks
    suspend fun album(uri: String): Album
}

class SessionSyncSource(private val session: Session) : SpotifySyncSource {
    override val loggedIn: Boolean get() = session.loggedIn
    override val changes: Flow<Unit> = merge(session.state.map { 0 }, session.library.map { 1 }).map { }
    override val playlistEdits: Flow<String> = session.edits.map { it.uri }
    override fun likedIds(): Set<String>? {
        val lib = session.library.value
        return if (session.state.value == SessionState.LoggedIn && lib.loaded) lib.likedIds else null
    }
    override suspend fun playlistTracks(uri: String, offset: Int, limit: Int) = session.api.playlistTracks(uri, offset, limit)
    override suspend fun album(uri: String) = session.api.album(uri)
}

/** Waits between Spotify requests (tests pass zeros). */
data class SyncPacing(
    /** Between two pages of one Spotify list (rate limits). */
    val pageMs: Long = 300,
    /** Before every Spotify listing after the first in a pass: one collection at a time, gently. */
    val collectionMs: Long = 1500,
)

/**
 * Download sync engine ("Çevrimdışı kullanılabilir"; port of the non-UI part of app/DownloadSync). Liked Songs,
 * playlists and albums the user marked are kept downloaded. The rules and the pure planning live in [SyncRules]
 * (sync.json).
 *
 * A pass runs 45 s after startup, whenever a rule is due ([SyncRules.ruleDue]: periodic relisting, a change seen here —
 * a like, a playlist edit, a local playlist change — or tracks still missing) and on [syncNow]. It lists the rules one
 * at a time (Spotify pages 300 ms apart, collections 1.5 s apart; a 429 stops every Spotify rule for at least 10 min),
 * stores each listing's track ids, queues what is missing through the normal download queue
 * ([DownloadManager.enqueueSynced]: after the user's own downloads, marked "sy") within the storage cap, and drops sync
 * downloads no rule wants any more (their files only when "Listeden çıkan şarkıları sil" is on). Sync downloads wait
 * while sync is paused or the connection is metered (unless allowed). Rules of an unavailable source (Spotify while
 * logged out) keep their track ids, so nothing is dropped.
 *
 * Android: thread-safe (one lock); listings run in [scope] on IO; [state] is what the Downloads page shows and
 * [events] replaces the C++ toasts. [SyncWorker] runs passes while the app is not open.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DownloadSync(
    private val downloads: DownloadManager,
    private val spotify: SpotifySyncSource?,
    private val local: LocalCollections?,
    private val blocklist: SyncBlocklist?,
    private val openAlbum: suspend (String) -> Album = { MusicBrainz.album(it) },
    private val network: NetworkState? = null,
    private val file: () -> File = { Paths.file("sync.json") },
    private val scope: CoroutineScope = appScope,
    private val settings: () -> Settings = { SettingsStore.current },
    private val now: () -> Long = { Time.nowUnix() },
    private val pacing: SyncPacing = SyncPacing(),
) {
    /** A collection that can be kept offline. */
    data class Target(
        val kind: Kind = Kind.Playlist,
        /** Rule id (see [Rule.id]). */
        val id: String = "",
        /** Empty for Liked Songs (the UI shows its localized name). */
        val name: String = "",
        val images: List<Image> = emptyList(),
    )

    data class Status(
        /** A rule exists. */
        val synced: Boolean = false,
        /** Being listed right now. */
        val listing: Boolean = false,
        /** False: a Spotify rule while logged out. */
        val sourceReady: Boolean = true,
        val progress: SyncRules.Progress = SyncRules.Progress(),
        val error: ListError = ListError.None,
        val lastSync: Long = 0,
    ) {
        /** Downloaded everything of its last listing (the header toggle's check mark). */
        val complete: Boolean get() = synced && lastSync != 0L && progress.done >= progress.total
    }

    /** A row of "Senkronize edilenler": the rule (name, images, kind, excluded songs) and its status. */
    data class RuleView(val rule: Rule, val status: Status)

    /** Why sync downloads wait (the UI maps it to text). */
    sealed interface WaitReason {
        data object Paused : WaitReason
        /** Android: no connection. */
        data object Offline : WaitReason
        /** Android: Settings.downloadWifiOnly on a metered connection (all downloads wait). */
        data object WifiOnly : WaitReason
        /** Metered connection and syncOnMetered off. */
        data object Metered : WaitReason
        data class StorageCap(val usedBytes: Long, val capBytes: Long) : WaitReason
    }

    data class UiState(
        val rules: List<RuleView> = emptyList(),
        /** A pass is running. */
        val running: Boolean = false,
        val waitReason: WaitReason? = null,
        /** Sync downloads on disk. */
        val syncedBytes: Long = 0,
    )

    /** What the C++ toasted; the UI words them. */
    sealed interface Event {
        data class Enabled(val kind: Kind, val name: String, val paused: Boolean) : Event
        data class Disabled(val kind: Kind, val name: String, val deletedSongs: Int) : Event
        /** "Senkron depolama sınırına ulaştı; yeni şarkılar indirilmiyor" (once until the cap is no longer hit). */
        data object StorageCapReached : Event
        /** "Spotify istekleri sınırladı; birazdan yeniden denenecek". */
        data object SpotifyRateLimited : Event
        data class SongsExcluded(val count: Int) : Event
        data class SongsIncluded(val count: Int) : Event
        data class DownloadsDeleted(val count: Int, val alsoExcluded: Boolean) : Event
    }

    /** Which sync entries a track menu offers (C++ trackMenuItems). */
    data class TrackActions(
        /** "Senkrondan çıkar": songs some rule keeps. */
        val wanted: List<Track> = emptyList(),
        /** "Senkrona geri al": songs some rule has excluded. */
        val excludedIds: List<String> = emptyList(),
        /** "İndirileni sil": downloaded songs. */
        val downloaded: List<Track> = emptyList(),
    )

    private class Listing(val tracks: List<Track>, val name: String = "", val images: List<Image> = emptyList())

    private val lock = Any()
    private var state = SyncRules.State()
    private var loaded = false
    // The pass in progress.
    private var running = false
    private val pass = ArrayDeque<String>()
    private var listing = ""
    private var spotifyListed = 0
    private var capHit = false
    private var passJob: Job? = null
    private var listingJob: Deferred<Listing>? = null
    /** After a 429: no Spotify listing before this (unix seconds). */
    private var spotifyRetryAt = 0L
    // Conditions.
    private var capReached = false
    private var capToasted = false
    private var startedAt = 0L
    private var saveJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // ---- startup ------------------------------------------------------------------------------------------------------

    /** Reads sync.json (off the main thread) and installs the sync gate into the download queue. */
    fun load() {
        val s = SyncRules.loadState(file())
        synchronized(lock) {
            state = s
            loaded = true
            startedAt = now()
        }
        Log.i("sync", "${s.rules.size} rule(s)")
        downloads.syncAllowed = { allowedNow() }
        notifyUi()
    }

    /** Starts the housekeeping tick (every 2 s) and follows the library / Spotify / downloads / settings. */
    fun start() {
        scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
        local?.let { l -> scope.launch { l.changes.collect { onLibraryChanged() } } }
        spotify?.let { sp ->
            scope.launch {
                sp.changes.collect {
                    // Logged out while a Spotify list was being fetched: stop it (the rule keeps its last listing).
                    val cancel = synchronized(lock) {
                        if (!sp.loggedIn && listing.startsWith("spotify:")) listingJob else null
                    }
                    cancel?.cancel()
                    onLibraryChanged()
                    notifyUi()
                }
            }
            scope.launch { sp.playlistEdits.collect { markChanged(it) } }
        }
        scope.launch {
            downloads.state.collect { // StateFlow: conflated, a slow collector only sees the latest
                notifyUi()
                delay(250)
            }
        }
        scope.launch {
            SettingsStore.flow.map { Triple(it.syncPaused, it.syncOnMetered, it.downloadWifiOnly) }.distinctUntilChanged().drop(1).collect {
                notifyUi()
                downloads.tick()
            }
        }
        // A new storage cap is re-evaluated by the next pass.
        scope.launch { SettingsStore.flow.map { it.syncCapGb }.distinctUntilChanged().drop(1).collect { onCapChanged() } }
        (network as? NetworkMonitor)?.let { m ->
            scope.launch {
                m.state.drop(1).collect {
                    notifyUi()
                    downloads.tick() // resume waiting sync downloads right away
                }
            }
        }
    }

    // ---- queue snapshot -----------------------------------------------------------------------------------------------

    private class Snapshot(val items: Map<String, ItemInfo>) {
        val lookup = SyncRules.ItemLookup { items[it] ?: ItemInfo() }
    }

    private fun snapshot() = Snapshot(downloads.itemInfos())

    /** Sync downloads on disk + the estimate of the queued ones (what the storage cap counts). */
    private fun usedBytes(s: Snapshot): Long {
        val kbps = effectiveKbps()
        var n = 0L
        for (i in s.items.values) {
            if (!i.synced) continue
            if (i.state == ItemInfo.St.Done) n += i.sizeBytes
            else if (i.state == ItemInfo.St.Queued || i.state == ItemInfo.St.Downloading) n += SyncRules.estimateBytes(i.durationMs, kbps)
        }
        return n
    }

    /** The MP3 bitrate downloads are made at (0 = the original stream, ~130 kbps). */
    private fun effectiveKbps() = Mp3Encoder.supportedKbps(settings().downloadMp3Kbps)

    /** Sync downloads may run: not paused, and not on a metered connection unless allowed. */
    fun allowedNow(): Boolean {
        val s = settings()
        return !s.syncPaused && (network?.metered != true || s.syncOnMetered)
    }

    // ---- persistence --------------------------------------------------------------------------------------------------

    /** Attempts are kept only for tracks some rule wants and that are not downloaded yet. */
    private fun prunedLocked(snap: Snapshot): SyncRules.State {
        val wanted = SyncRules.wantedIds(state.rules)
        val attempts = state.attempts.filter { (id, _) ->
            id in wanted && snap.items[id]?.state != ItemInfo.St.Done
        }
        if (attempts.size != state.attempts.size) state = state.copy(attempts = attempts)
        return state
    }

    private fun saveLater() {
        synchronized(lock) {
            if (!loaded) return
            saveJob?.cancel()
            saveJob = scope.launch(AppDispatchers.io) {
                delay(SAVE_DELAY_MS)
                saveNow()
            }
        }
    }

    /** Writes sync.json now. */
    fun saveNow() {
        val snap = snapshot()
        val s = synchronized(lock) {
            if (!loaded) return
            prunedLocked(snap)
        }
        if (!SyncRules.saveState(s, file())) Log.w("sync", "could not write sync.json")
    }

    fun flush() {
        saveJob?.cancel()
        saveNow()
    }

    // ---- status -------------------------------------------------------------------------------------------------------

    private fun statusOfLocked(r: Rule, snap: Snapshot) = Status(
        synced = true,
        listing = listing == r.id,
        sourceReady = !SyncRules.isSpotifyRule(r) || spotify?.loggedIn == true,
        progress = SyncRules.progress(r, snap.lookup),
        error = r.error,
        lastSync = r.lastSync,
    )

    fun status(ruleId: String): Status {
        val snap = snapshot()
        return synchronized(lock) { SyncRules.findRule(state, ruleId)?.let { statusOfLocked(it, snap) } ?: Status() }
    }

    fun rules(): List<Rule> = synchronized(lock) { state.rules }

    fun isSynced(ruleId: String): Boolean = ruleId.isNotEmpty() && synchronized(lock) { SyncRules.findRule(state, ruleId) != null }

    /** Liked Songs as the active source sees them: Spotify's while logged in, else the local ones. */
    fun likedTarget() = Target(
        kind = Kind.Liked,
        id = if (spotify?.loggedIn == true) SyncRules.SPOTIFY_LIKED_ID else SyncRules.LOCAL_LIKED_ID,
    )

    /** Why sync downloads wait (null = they don't). */
    fun waitReason(): WaitReason? {
        val s = settings()
        if (s.syncPaused) return WaitReason.Paused
        val n = network
        if (n != null && !n.connected) return WaitReason.Offline
        if (n != null && n.metered && s.downloadWifiOnly) return WaitReason.WifiOnly
        if (n?.metered == true && !s.syncOnMetered) return WaitReason.Metered
        if (synchronized(lock) { capReached }) return WaitReason.StorageCap(syncedBytes(), s.syncCapGb.toLong() * GIB)
        return null
    }

    /** Sync downloads on disk. */
    fun syncedBytes(): Long = downloads.syncedBytes()

    private fun notifyUi() {
        val snap = snapshot()
        val (views, isRunning) = synchronized(lock) {
            state.rules.map { RuleView(it, statusOfLocked(it, snap)) } to running
        }
        _state.value = UiState(views, isRunning, waitReason(), downloads.syncedBytes())
    }

    // ---- housekeeping -------------------------------------------------------------------------------------------------

    /**
     * Some of the rule's tracks are neither downloaded nor on their way, and could be (not waiting for a retry, not
     * given up, and the storage cap has room).
     */
    private fun pendingLocked(r: Rule, snap: Snapshot, now: Long): Boolean {
        if (capReached) return false
        for (id in r.trackIds) {
            val it = snap.items[id] ?: return true
            if (it.state == ItemInfo.St.Failed) {
                val a = state.attempts[id]
                if (a == null || SyncRules.retryDue(a, now)) return true
            }
        }
        return false
    }

    private fun spotifyBlockedLocked(r: Rule, now: Long) =
        SyncRules.isSpotifyRule(r) && (spotify?.loggedIn != true || now < spotifyRetryAt)

    /** The housekeeping tick (every 2 s while the process runs). */
    fun tick(ignoreStartupDelay: Boolean = false) {
        synchronized(lock) {
            if (!loaded || state.rules.isEmpty()) return
            if (running || !allowedNow()) return
            if (network?.connected == false) return // Android: offline, a listing could only fail and back off
            val now = now()
            if (!ignoreStartupDelay && now - startedAt < SyncRules.STARTUP_DELAY_SEC) return
            var snap: Snapshot? = null
            val due = ArrayList<String>()
            for (r in state.rules) {
                if (spotifyBlockedLocked(r, now)) continue
                var isPending = false
                if (r.lastSync != 0L && now - r.lastSync >= SyncRules.PENDING_RELIST_SEC && now >= r.retryAt) {
                    if (snap == null) snap = snapshot()
                    isPending = pendingLocked(r, snap, now)
                }
                if (SyncRules.ruleDue(r, now, isPending)) due.add(r.id)
            }
            startPassLocked(due)
        }
    }

    /**
     * For [SyncWorker] (the app is not open): a pass over the due rules without the startup delay; returns once it
     * finished (the downloads it queued run in the download worker).
     */
    suspend fun runBackgroundPass() {
        tick(ignoreStartupDelay = true)
        val job = synchronized(lock) { passJob }
        job?.join()
        flush()
    }

    // ---- passes -------------------------------------------------------------------------------------------------------

    private fun startPassLocked(ids: List<String>) {
        if (running || ids.isEmpty()) return
        running = true
        pass.clear()
        pass.addAll(ids)
        spotifyListed = 0
        capHit = false
        Log.i("sync", "pass over ${ids.size} rule(s)")
        passJob = scope.launch(AppDispatchers.io) { runPass() }
        scope.launch { notifyUi() }
    }

    private suspend fun runPass() {
        try {
            while (true) {
                val next = synchronized(lock) { nextLocked() } ?: break
                notifyUi()
                listOne(next.first, next.second)
            }
        } finally {
            finishPass()
        }
    }

    /** The next rule to list (and whether a pause comes first), or null when the pass is over. */
    private fun nextLocked(): Pair<Rule, Boolean>? {
        if (!running) return null
        val now = now()
        while (pass.isNotEmpty()) {
            val id = pass.removeFirst()
            val r = SyncRules.findRule(state, id) ?: continue
            if (spotifyBlockedLocked(r, now)) continue
            listing = id
            val pause = SyncRules.isSpotifyRule(r) && spotifyListed++ > 0
            return r to pause
        }
        return null
    }

    private suspend fun listOne(r: Rule, pause: Boolean) {
        val id = r.id
        val startedAt = now()
        // Local collections: already in memory.
        if (id == SyncRules.LOCAL_LIKED_ID) {
            onListed(id, Listing(local?.liked().orEmpty()), startedAt)
            return
        }
        if (r.kind == Kind.Playlist && !SyncRules.isSpotifyRule(r)) {
            val p = local?.playlist(id)
            if (p == null) onListFailed(id, ListError.NotFound, "local playlist not found")
            else onListed(id, Listing(p.tracks, p.name, p.images), startedAt)
            return
        }
        // Spotify lists and albums (Spotify / MusicBrainz).
        val deferred = scope.async(AppDispatchers.io) {
            if (pause) delay(pacing.collectionMs) // one collection at a time, gently
            if (r.kind == Kind.Album) listAlbum(id)
            else {
                val sp = spotify?.takeIf { it.loggedIn } ?: throw ApiError(401, "not logged in")
                listSpotifyPlaylist(sp, id, r.kind == Kind.Liked)
            }
        }
        synchronized(lock) { listingJob = deferred }
        try {
            val l = deferred.await()
            onListed(id, l, startedAt)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            // Logged out / removed meanwhile: not the rule's fault; the rest of the pass goes on.
            synchronized(lock) { listing = "" }
            notifyUi()
        } catch (e: Exception) {
            val (kind, detail) = classify(e)
            onListFailed(id, kind, detail)
        } finally {
            synchronized(lock) { if (listingJob === deferred) listingJob = null }
        }
    }

    /** Every page of a Spotify playlist / Liked Songs, a short pause between pages (rate limits). */
    private suspend fun listSpotifyPlaylist(sp: SpotifySyncSource, uri: String, liked: Boolean): Listing {
        val tracks = ArrayList<Track>()
        var name = ""
        var images: List<Image> = emptyList()
        var offset = 0
        var total = 0
        for (page in 0 until 200) { // at most 20 000 rows
            currentCoroutineContext().let { if (!it.isActive) throw CancellationException() }
            val p = sp.playlistTracks(uri, offset, 100)
            if (page == 0) {
                total = p.page.total
                if (!liked) p.meta?.let { name = it.name; images = it.images }
            }
            val next = p.page.next
            tracks.addAll(p.page.items)
            if (next <= offset || next >= total) break
            offset = next
            delay(pacing.pageMs)
        }
        return Listing(tracks, name, images)
    }

    /** An album (Spotify or MusicBrainz) with its tracks pointing at it. */
    private suspend fun listAlbum(id: String): Listing {
        val sp = spotify
        val a = if (sp != null && sp.loggedIn && id.startsWith("spotify:")) sp.album(id) else openAlbum(id)
        val tracks = a.tracks.map { t0 ->
            var t = t0
            if (t.album.id.isEmpty() && t.album.name.isEmpty()) t = t.copy(album = AlbumRef(a.id, a.name, a.images))
            if (t.album.images.isEmpty()) t = t.copy(album = t.album.copy(images = a.images))
            t
        }
        return Listing(tracks, a.name, a.images)
    }

    private fun classify(e: Exception): Pair<ListError, String> {
        val detail = e.message ?: e.javaClass.simpleName
        if (e is ApiError) {
            if (e.status == 429) return ListError.RateLimited to detail
            if (e.status == 404 || e.status == 403) return ListError.NotFound to detail
        }
        return ListError.Network to detail
    }

    private fun onListed(id: String, l: Listing, startedAt: Long) {
        synchronized(lock) {
            listing = ""
            val r = SyncRules.findRule(state, id)
            if (r != null) {
                // Blocked tracks and the songs the user took out are not part of what the rule keeps (and so never count
                // as missing).
                val tracks = l.tracks.filter { blocklist?.isBlocked(it) != true && !SyncRules.isExcluded(r, it.id) }
                val ids = SyncRules.ruleTrackIds(tracks)
                var protect: List<String> = emptyList()
                if (SyncRules.suspiciousShrink(r.trackIds.size, ids.size)) {
                    Log.w("sync", "$id: listing came back empty (had ${r.trackIds.size}); not dropping anything this time")
                    protect = r.trackIds
                }
                Log.i("sync", "$id: ${ids.size} track(s)")
                replaceRuleLocked(
                    r.copy(
                        trackIds = ids,
                        // The listing's start: a change made while it ran is newer and triggers the next listing.
                        lastSync = startedAt,
                        error = ListError.None,
                        errorDetail = "",
                        failures = 0,
                        retryAt = 0,
                        name = l.name.ifEmpty { r.name },
                        images = l.images.ifEmpty { r.images },
                    ),
                )
                queueMissingLocked(tracks)
                applyDropsLocked(protect)
            }
        }
        saveLater()
        notifyUi()
    }

    private fun onListFailed(id: String, kind: ListError, detail: String) {
        synchronized(lock) {
            listing = ""
            val r = SyncRules.findRule(state, id)
            if (r != null) {
                val failures = r.failures + 1
                val now = now()
                val retryAt = now + SyncRules.backoffSeconds(failures, kind == ListError.RateLimited)
                replaceRuleLocked(r.copy(failures = failures, error = kind, errorDetail = detail, retryAt = retryAt))
                Log.w("sync", "$id: listing failed ($detail), retry in ${retryAt - now} s")
                if (kind == ListError.RateLimited) spotifyRetryAt = retryAt // every Spotify rule waits
            }
        }
        saveLater()
        notifyUi()
    }

    private fun finishPass() {
        var toast = false
        synchronized(lock) {
            running = false
            listing = ""
            pass.clear()
            passJob = null
            capReached = capHit
            if (capHit && !capToasted) {
                capToasted = true
                toast = true
            }
            if (!capHit) capToasted = false
            capHit = false
        }
        if (toast) _events.tryEmit(Event.StorageCapReached)
        saveLater()
        notifyUi()
    }

    private fun replaceRuleLocked(r: Rule) {
        state = state.copy(rules = state.rules.map { if (it.id == r.id) r else it })
    }

    /** Queues what the rule is missing (within the storage cap). Records one attempt per queued track. */
    private fun queueMissingLocked(tracks: List<Track>) {
        val s = settings()
        val snap = snapshot()
        val limits = SyncRules.Limits(capBytes = s.syncCapGb.toLong() * GIB, usedBytes = usedBytes(snap), mp3Kbps = effectiveKbps())
        val now = now()
        val p = SyncRules.plan(tracks, snap.lookup, { t -> blocklist?.isBlocked(t) == true }, state.attempts, limits, now)
        if (p.skippedCap > 0) capHit = true
        if (p.toQueue.isEmpty()) return
        val attempts = HashMap(state.attempts)
        for (t in p.toQueue) {
            val a = attempts[t.id] ?: Attempt()
            attempts[t.id] = Attempt(a.count + 1, now)
        }
        state = state.copy(attempts = attempts)
        Log.i("sync", "queueing ${p.toQueue.size} track(s) (~${p.queuedBytes / 1048576} MB)" + if (p.skippedCap > 0) " - storage cap reached" else "")
        downloads.enqueueSynced(p.toQueue)
    }

    /**
     * Drops sync downloads no rule wants any more (their files only with "Listeden çıkan şarkıları sil"). [protect]:
     * ids a suspicious listing would have dropped (kept until the next listing confirms).
     */
    private fun applyDropsLocked(protect: List<String>) {
        val wanted = HashSet(SyncRules.wantedIds(state.rules))
        wanted.addAll(protect)
        val items = snapshot().items.entries.map { it.key to it.value }
        val d = SyncRules.dropped(items, wanted, settings().syncRemoveDropped)
        for (id in d.cancel) {
            val it = downloads.item(id) ?: continue
            if (it.state == DlState.Failed) downloads.remove(id) else downloads.cancel(id)
        }
        for (id in d.remove) downloads.remove(id)
        if (d.cancel.isNotEmpty() || d.remove.isNotEmpty())
            Log.i("sync", "dropped ${d.cancel.size} queued and ${d.remove.size} downloaded sync item(s)")
    }

    // ---- changes seen here --------------------------------------------------------------------------------------------

    /** A change here (a Spotify playlist edit): mark the rule it concerns. */
    fun markChanged(id: String) = synchronized(lock) {
        SyncRules.findRule(state, id)?.let { replaceRuleLocked(it.copy(changedAt = now())) }
    }

    /** The library changed (likes, local playlists, the Spotify snapshot): mark the rules it may concern. */
    fun onLibraryChanged() = synchronized(lock) {
        val now = now()
        // Local lists: listing them is free.
        state = state.copy(rules = state.rules.map { if (!SyncRules.isSpotifyRule(it) && it.kind != Kind.Album) it.copy(changedAt = now) else it })
        // Spotify Liked Songs: a like / unlike shows in the snapshot's ids at once.
        val liked = SyncRules.findRule(state, SyncRules.SPOTIFY_LIKED_ID) ?: return
        val sp = spotify ?: return
        if (!sp.loggedIn || liked.lastSync == 0L) return
        val ids = sp.likedIds() ?: return
        val have = liked.trackIds.toHashSet()
        var changed = liked.trackIds.any { it !in ids }
        if (!changed) {
            changed = ids.any { it !in have && blocklist?.isTrackBlocked(it) != true && !SyncRules.isExcluded(liked, it) }
        }
        if (changed && liked.changedAt <= liked.lastSync) replaceRuleLocked(liked.copy(changedAt = now))
    }

    // ---- public actions -----------------------------------------------------------------------------------------------

    /** Adds the rule and syncs it right away (unless sync is paused). */
    fun enable(t: Target) {
        if (t.id.isEmpty()) return
        val paused = settings().syncPaused
        synchronized(lock) {
            if (SyncRules.findRule(state, t.id) != null) return
            state = state.copy(rules = state.rules + Rule(id = t.id, kind = t.kind, name = t.name, images = t.images, addedAt = now()))
            Log.i("sync", "rule ${t.id} added")
            if (!paused) {
                if (!running) startPassLocked(listOf(t.id)) else pass.addLast(t.id)
            }
        }
        saveLater()
        notifyUi()
        _events.tryEmit(Event.Enabled(t.kind, t.name, paused))
    }

    /**
     * Whether turning the rule off should ask "keep or delete the songs?" (the C++ confirmDisable asks only when sync
     * downloaded files of it exist; otherwise it removes the rule at once).
     */
    fun disableNeedsChoice(ruleId: String): Boolean {
        val snap = snapshot()
        val r = synchronized(lock) { SyncRules.findRule(state, ruleId) } ?: return false
        return r.trackIds.any { id -> snap.items[id]?.let { it.synced && it.state == ItemInfo.St.Done } == true }
    }

    /**
     * Removes the rule. [deleteFiles] = false: its downloaded songs become the user's own downloads; true: their files
     * go, unless another rule or the user wants them.
     */
    fun disable(ruleId: String, deleteFiles: Boolean) {
        var event: Event? = null
        var cancelListing: Deferred<Listing>? = null
        synchronized(lock) {
            val r = SyncRules.findRule(state, ruleId) ?: return
            val ids = r.trackIds
            state = state.copy(rules = state.rules.filter { it.id != ruleId })
            pass.remove(ruleId)
            if (listing == ruleId) cancelListing = listingJob
            val wanted = SyncRules.wantedIds(state.rules)
            val keep = ArrayList<String>()
            var deleted = 0
            for (tid in ids) {
                val it = downloads.item(tid) ?: continue
                if (!it.synced || tid in wanted) continue
                when (it.state) {
                    DlState.Queued, DlState.Downloading -> downloads.cancel(tid)
                    DlState.Failed -> downloads.remove(tid)
                    DlState.Done -> if (deleteFiles) {
                        downloads.remove(tid)
                        ++deleted
                    } else {
                        keep.add(tid)
                    }
                }
            }
            downloads.keepAsManual(keep)
            Log.i("sync", "rule $ruleId removed: $deleted file(s) deleted, ${keep.size} kept")
            event = Event.Disabled(r.kind, r.name, if (deleteFiles) deleted else 0)
        }
        cancelListing?.cancel()
        saveLater()
        notifyUi()
        event?.let { _events.tryEmit(it) }
    }

    /** A forced pass over one rule ("" = all). An explicit "sync now" also resumes a paused sync. */
    fun syncNow(ruleId: String = "") {
        if (settings().syncPaused) SettingsStore.update { it.copy(syncPaused = false) }
        var rateLimited = false
        synchronized(lock) {
            val now = now()
            val ids = ArrayList<String>()
            state = state.copy(
                rules = state.rules.map { r ->
                    if ((ruleId.isNotEmpty() && r.id != ruleId) || (SyncRules.isSpotifyRule(r) && now < spotifyRetryAt)) r // Spotify asked us to wait
                    else {
                        ids.add(r.id)
                        r.copy(retryAt = 0)
                    }
                },
            )
            if (ids.isEmpty()) {
                rateLimited = now < spotifyRetryAt
            } else if (running) {
                for (id in ids) if (id != listing && id !in pass) pass.addLast(id)
            } else {
                startPassLocked(ids)
            }
        }
        if (rateLimited) _events.tryEmit(Event.SpotifyRateLimited)
        downloads.tick()
    }

    /** "Hataları yeniden dene": clears the failure count of the rule's tracks and syncs it. */
    fun retryFailed(ruleId: String) {
        synchronized(lock) {
            SyncRules.findRule(state, ruleId)?.let { r ->
                val ids = r.trackIds.toHashSet()
                state = state.copy(attempts = state.attempts.filterKeys { it !in ids })
            }
            capReached = false
        }
        syncNow(ruleId)
    }

    /** The storage cap changed in Settings: re-evaluated by the next pass. */
    fun onCapChanged() {
        synchronized(lock) { capReached = false }
        notifyUi()
        downloads.tick()
    }

    // ---- songs taken out of synced collections ------------------------------------------------------------------------

    /** Some rule keeps it offline. */
    fun isWanted(trackId: String): Boolean = synchronized(lock) { state.rules.any { trackId in it.trackIds } }

    /** Some rule has it excluded. */
    fun isExcludedAnywhere(trackId: String): Boolean = synchronized(lock) { state.rules.any { SyncRules.isExcluded(it, trackId) } }

    /** The songs taken out of a rule, newest first. */
    fun excluded(ruleId: String): List<SyncRules.Excluded> =
        synchronized(lock) { SyncRules.findRule(state, ruleId)?.excluded?.asReversed()?.toList().orEmpty() }

    /**
     * Sync downloads of [ids] no rule wants any more: queued / downloading ones are dropped, downloaded files deleted
     * (the user asked for them to go). The user's own downloads are left alone.
     */
    private fun dropSyncDownloadsLocked(ids: List<String>) {
        state = state.copy(attempts = state.attempts - ids.toSet())
        val wanted = SyncRules.wantedIds(state.rules)
        for (id in ids) {
            val it = downloads.item(id) ?: continue
            if (!it.synced || id in wanted) continue
            if (it.isActive) downloads.cancel(id) else downloads.remove(id)
        }
    }

    /** Takes the songs out of every rule that keeps them. Returns how many songs that concerned. */
    private fun excludeEverywhere(tracks: List<Track>): Int {
        val n = synchronized(lock) {
            val now = now()
            val ids = ArrayList<String>()
            for (t in tracks) {
                var any = false
                state = state.copy(
                    rules = state.rules.map { r ->
                        if (t.id in r.trackIds) SyncRules.exclude(r, t, now)?.also { any = true } ?: r else r
                    },
                )
                if (any) ids.add(t.id)
            }
            if (ids.isEmpty()) return 0
            dropSyncDownloadsLocked(ids)
            Log.i("sync", "${ids.size} song(s) excluded")
            ids.size
        }
        saveLater()
        notifyUi()
        return n
    }

    /**
     * Lets songs back into the rules they were taken out of (all rules, or [onlyRule]); those rules are listed again
     * soon. Returns how many rules changed.
     */
    private fun includeEverywhere(ids: List<String>, onlyRule: String = ""): Int {
        val n = synchronized(lock) {
            var n = 0
            val now = now()
            state = state.copy(
                rules = state.rules.map { r0 ->
                    if (onlyRule.isNotEmpty() && r0.id != onlyRule) return@map r0
                    var r = r0
                    var any = false
                    for (id in ids) SyncRules.include(r, id)?.let { r = it; any = true }
                    if (any) {
                        ++n
                        r.copy(changedAt = now) // the next listing puts them back in their place (and queues them)
                    } else {
                        r
                    }
                },
            )
            n
        }
        if (n == 0) return 0
        saveLater()
        notifyUi()
        return n
    }

    /**
     * "Senkrondan çıkar": the songs leave every rule that keeps them (never downloaded again by them); their sync
     * downloads go (queued ones dropped, files deleted) unless another rule still wants them. The user's own downloads
     * stay.
     */
    fun excludeTracks(tracks: List<Track>) {
        val n = excludeEverywhere(tracks)
        if (n > 0) _events.tryEmit(Event.SongsExcluded(n))
    }

    /** "Senkrona geri al" / "Geri al" / "Hepsini geri al": back into the rules (downloaded with the next listing). */
    fun includeTracks(trackIds: List<String>, onlyRule: String = "") {
        if (includeEverywhere(trackIds, onlyRule) > 0) _events.tryEmit(Event.SongsIncluded(trackIds.size))
    }

    /**
     * Deletes the downloads of the songs (file + record, or drops them from the queue); this is also what cancelling a
     * sync download on the Downloads page does. Songs a synced collection keeps are excluded from it first, so they
     * are not downloaded again.
     */
    fun deleteDownloads(tracks: List<Track>) {
        val had = tracks.filter { downloads.item(it.id) != null }
        if (had.isEmpty()) return
        val excluded = excludeEverywhere(had)
        for (t in had) {
            val it = downloads.item(t.id) ?: continue // a sync download excludeEverywhere already dropped
            if (it.isActive) downloads.cancel(t.id) else downloads.remove(t.id)
        }
        _events.tryEmit(Event.DownloadsDeleted(had.size, excluded > 0))
    }

    /** The sync entries of a track context menu (as they apply; may be empty). */
    fun trackActions(tracks: List<Track>): TrackActions {
        val downloaded = ArrayList<Track>()
        val wanted = ArrayList<Track>()
        val excluded = ArrayList<String>()
        for (t in tracks) {
            if (downloads.isDownloaded(t.id)) downloaded.add(t)
            if (isWanted(t.id)) wanted.add(t) else if (isExcludedAnywhere(t.id)) excluded.add(t.id)
        }
        return TrackActions(wanted, excluded, downloaded)
    }

    companion object {
        const val GIB = 1024L * 1024 * 1024
        private const val TICK_MS = 2000L
        private const val SAVE_DELAY_MS = 500L
    }
}
