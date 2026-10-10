package com.app.shadetube.podcasts

import com.app.shadetube.BuildConfig
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.Time
import com.app.shadetube.core.appScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** What the player needs to stream an episode (port of Player::DirectStream for podcasts). */
data class PodcastStream(
    /** Where the audio really is (redirects followed). */
    val url: String,
    /** "" = let the player sniff. */
    val mimeType: String,
    /** 0 = unknown. */
    val contentLength: Long,
    /** The show was added by its RSS address: its audio may be on the local network. */
    val allowLocalNetwork: Boolean,
)

/** An episode's show notes: [text] always (when the feed had any), [html] only when it was published as HTML. */
data class EpisodeNotes(val text: String, val html: String)

/** The player's view of what it is doing, sampled by [Podcasts.onPlayerSample]. */
enum class PlayerStatus { Idle, Loading, Playing, Paused, Error }

/** Why queued episode downloads do not start (the UI words it, like downloads.QueueWait for songs). */
enum class PodcastDownloadWait {
    /** No connection: they start once there is one. */
    Offline,

    /** Settings.downloadWifiOnly on a metered connection (mobile data, a metered hotspot): they start on Wi-Fi. */
    WifiOnly,
}

/** The connection as episode downloads see it (AppGraph feeds it from downloads.NetworkMonitor). */
data class PodcastConnection(val connected: Boolean = true, val metered: Boolean = false)

/** Episode downloads: one at a time, in the background. */
data class PodcastDownloads(
    /** Episode ids waiting, in order. */
    val waiting: List<String> = emptyList(),
    /** The episode downloading now. */
    val active: String? = null,
    /** 0..1 of [active]. */
    val progress: Float = 0f,
    /**
     * Why [waiting] episodes do not start (null = they do / nothing waits). An episode paused by the connection turning
     * metered goes back to the front of [waiting]; its partial file is kept and the download resumes from there.
     */
    val wait: PodcastDownloadWait? = null,
) {
    /** null = neither waiting nor downloading; else 0..1. */
    fun progressOf(id: String): Float? = when {
        active == id -> progress
        id in waiting -> 0f
        else -> null
    }
}

sealed class PodcastEvent {
    /** A refresh found [count] new episodes across subscriptions. */
    data class NewEpisodes(val count: Int, val manual: Boolean) : PodcastEvent()

    /** A manual refresh found nothing new. */
    data object UpToDate : PodcastEvent()
    data class DownloadFinished(val episode: Episode) : PodcastEvent()
    data class DownloadFailed(val episode: Episode, val kind: PodcastErrorKind) : PodcastEvent()
    data class DownloadDeleted(val episode: Episode) : PodcastEvent()
}

/** Outcome of [Podcasts.refreshAll]. */
sealed class RefreshResult {
    data object AlreadyRunning : RefreshResult()
    data object NoSubscriptions : RefreshResult()

    /** [refreshed] feeds read, [failed] ones that could not be, [added] newly published episodes. */
    data class Done(val refreshed: Int, val failed: Int, val added: Int) : RefreshResult()
}

/**
 * The podcasts service (port of the wiring in app/PodcastsPage.cpp + initPodcasts): the player's media lookup and
 * resume position, progress / played tracking, downloads, the refresh of subscriptions and the operations the
 * Podcasts screens run. Open sources only (Apple's directory + RSS), no Spotify podcasts.
 *
 * Call [init] once at startup (after Paths / SettingsStore). Every network operation is `suspend` and main-safe.
 */
object Podcasts {
    /** Subscriptions are refreshed when older than this (C++ kRefreshEverySec). */
    const val REFRESH_EVERY_SEC = 3 * 3600L
    private const val FIRST_CHECK_AFTER_MS = 60_000L
    private const val CHECK_EVERY_MS = 10 * 60_000L
    private const val SAVE_EVERY_MS = 30_000L

    val client = PodcastClient()
    val store = PodcastStore()

    private val initialized = AtomicBoolean(false)
    private val _events = MutableSharedFlow<PodcastEvent>(extraBufferCapacity = 16)

    /** Notices for the UI (toasts / snackbars) and the refresh worker. */
    val events: SharedFlow<PodcastEvent> = _events.asSharedFlow()

    /** "ShadeTube/<version> (+https://github.com/shadesofdeath/ShadeTubeAndroid)". */
    val userAgent: String get() = "ShadeTube/${BuildConfig.VERSION_NAME} (+https://github.com/shadesofdeath/ShadeTubeAndroid)"

    /**
     * Loads podcasts.json (blocking: one small file) and starts the in-process housekeeping (periodic saves, the
     * subscription refresh a minute after startup and then every 10 minutes). Idempotent: the refresh worker calls it
     * too.
     */
    fun init(startHousekeeping: Boolean = true) {
        if (initialized.compareAndSet(false, true)) {
            client.userAgent = userAgent
            client.cacheDir = File(Paths.cacheDir, "podcasts")
            store.load(Paths.file("podcasts.json"))
        }
        if (startHousekeeping) startHousekeeping()
    }

    private var housekeeping: Job? = null

    @Synchronized
    private fun startHousekeeping() {
        if (housekeeping != null) return
        housekeeping = appScope.launch(AppDispatchers.io) {
            launch {
                while (true) {
                    delay(SAVE_EVERY_MS) // saved at most every 30 s while playing
                    store.saveIfDirty()
                }
            }
            delay(FIRST_CHECK_AFTER_MS)
            while (true) {
                runCatching { refreshAll(manual = false) }.onFailure { if (it is CancellationException) throw it }
                delay(CHECK_EVERY_MS)
            }
        }
    }

    /** The app is going away (C++ persistHooks): the last position and the queue are written now. */
    suspend fun flush() = withContext(AppDispatchers.io) {
        finishTracked()
        store.save()
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    /** The directory's store for charts and search: the device's region, else the app region, else US. */
    fun homeCountry(): String {
        val c = Locale.getDefault().country
        if (c.length == 2 && c.all { it.isLetter() }) return c.uppercase(Locale.ROOT)
        val r = SettingsStore.current.region
        if (r.length == 2) return r
        return "US"
    }

    /** A feed the user added by its URL may live on the local network, and so may its audio. */
    fun allowLocal(feedUrl: String): Boolean = store.subscription(feedUrl)?.manual == true

    // ---- directory / shows (the Podcasts screens) ------------------------------------------------------------------------

    suspend fun search(term: String, country: String = homeCountry(), limit: Int = 30): List<DirectoryEntry> =
        client.search(term, country, limit)

    suspend fun topCharts(country: String = homeCountry(), limit: Int = 30): List<DirectoryEntry> =
        client.topCharts(country, limit)

    /**
     * A show page: by its feed URL, or by its directory id (its feed looked up first). Offline or with the host down,
     * the copy on disk however old. The store learns its episodes; a subscribed show's new episodes count as seen.
     */
    suspend fun loadShow(feedUrl: String = "", appleId: String = ""): Show {
        var url = feedUrl
        if (url.isEmpty()) url = client.lookupFeed(appleId)
        if (url.isEmpty()) throw PodcastError("not an RSS feed (the directory has none)")
        val show = try {
            client.feed(url, false, allowLocal(url)).let { if (it.appleId.isEmpty()) it.copy(appleId = appleId) else it }
        } catch (e: PodcastError) {
            val cached = client.cachedFeed(url) ?: throw e
            Log.w("podcasts", "feed failed (${e.message}); showing the cached copy")
            cached
        }
        showSeen(show)
        return show
    }

    /** The show page is on screen: the store learns its episodes and a subscription's new ones count as seen. */
    fun showSeen(show: Show) {
        store.remember(show.episodes)
        if (store.isSubscribed(show.feedUrl)) {
            store.refreshed(show, Time.nowUnix())
            store.markSeen(show.feedUrl, show.episodes.maxOfOrNull { it.published } ?: 0)
        }
    }

    /**
     * An episode page: the store's copy, with its notes HTML taken from the show's cached feed when the store's copy
     * has none (the HTML is not kept in podcasts.json). null = an episode the store does not know.
     */
    suspend fun loadEpisode(id: String): Episode? {
        val e = store.find(id) ?: return null
        if (e.notesHtml.isNotEmpty() || e.feedUrl.isEmpty()) return e
        val cached = runCatching { client.cachedFeed(e.feedUrl) }.getOrNull() ?: return e
        val fresh = cached.episodes.firstOrNull { it.id == id } ?: return e
        store.remember(fresh)
        return fresh
    }

    /** "Refresh" on a show page: the cached copy is dropped and the feed read again. */
    suspend fun reloadShow(feedUrl: String): Show {
        client.dropCachedFeed(feedUrl)
        return loadShow(feedUrl)
    }

    /**
     * Adds a podcast by its RSS address (a self-hosted server may be on the local network): fetched fresh and
     * subscribed as "manual". Throws [PodcastError] (see [PodcastErrorKind.of]).
     */
    suspend fun addByUrl(input: String): Show {
        val url = input.trim(' ')
        if (!PodcastText.isHttpUrl(url)) throw PodcastError("not an http(s) address")
        val show = try {
            client.feed(url, true, true)
        } catch (e: PodcastError) {
            Log.w("podcasts", "adding $url failed: ${e.message}")
            throw e
        }
        store.subscribe(show, true, Time.nowUnix())
        return show
    }

    fun subscribe(show: Show) = store.subscribe(show, false, Time.nowUnix())
    fun unsubscribe(feedUrl: String) = store.unsubscribe(feedUrl)

    /** A show from a subscription (for its card before the feed loads). */
    fun showOf(s: Subscription) = Show(feedUrl = s.feedUrl, title = s.title, author = s.author, image = s.image, appleId = s.appleId)

    /**
     * Newest first across subscriptions, from the feeds on disk: at most [perShow] of each show and [max] in all
     * (home: 3 / 6 unplayed only; the "new" page: 20 / 200).
     */
    suspend fun newEpisodes(perShow: Int, max: Int, unplayedOnly: Boolean): List<Episode> = withContext(AppDispatchers.io) {
        val all = ArrayList<Episode>()
        for (s in store.subscriptionsNow()) {
            val show = client.cachedFeedBlocking(s.feedUrl) ?: continue
            var taken = 0
            for (e in show.episodes) {
                if (taken >= perShow) break
                if (unplayedOnly && store.isPlayed(e.id)) continue
                all.add(e)
                ++taken
            }
        }
        all.sortedByDescending { it.published }.take(max)
    }

    /** The show's play button: the episode in progress, else the newest one not heard yet, else the newest. */
    fun pickEpisodeToPlay(show: Show): Episode? {
        if (show.episodes.isEmpty()) return null
        store.inProgress(50).firstOrNull { it.feedUrl == show.feedUrl }?.let { return it }
        return show.episodes.firstOrNull { !store.isPlayed(it.id) } ?: show.episodes.first()
    }

    /** A subscription card's play button: [pickEpisodeToPlay] of the (cached or fresh) feed. */
    suspend fun latestToPlay(feedUrl: String): Episode? {
        val show = client.feed(feedUrl, false, allowLocal(feedUrl))
        store.remember(show.episodes)
        return pickEpisodeToPlay(show)
    }

    // ---- refresh -------------------------------------------------------------------------------------------------------

    private val refreshMutex = Mutex()

    /**
     * Refreshes the subscriptions one at a time ([manual]: all of them, else those older than [REFRESH_EVERY_SEC]).
     * A feed that fails is skipped (logged). Emits [PodcastEvent.NewEpisodes] / [PodcastEvent.UpToDate].
     */
    suspend fun refreshAll(manual: Boolean = true): RefreshResult {
        if (!refreshMutex.tryLock()) return RefreshResult.AlreadyRunning
        try {
            val now = Time.nowUnix()
            val due = store.subscriptionsNow().filter { manual || now - it.lastRefresh >= REFRESH_EVERY_SEC }
            if (due.isEmpty()) {
                return if (store.subscriptionsNow().isEmpty()) RefreshResult.NoSubscriptions else RefreshResult.Done(0, 0, 0)
            }
            var added = 0
            var failed = 0
            for (sub in due) {
                try {
                    val show = withContext(AppDispatchers.low) { client.feed(sub.feedUrl, true, sub.manual) }
                    added += store.refreshed(show, Time.nowUnix())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ++failed
                    Log.w("podcasts", "refresh of ${sub.feedUrl} failed: ${e.message}")
                }
            }
            if (added > 0) _events.tryEmit(PodcastEvent.NewEpisodes(added, manual))
            else if (manual) _events.tryEmit(PodcastEvent.UpToDate)
            withContext(AppDispatchers.io) { store.saveIfDirty() }
            return RefreshResult.Done(due.size - failed, failed, added)
        } finally {
            refreshMutex.unlock()
        }
    }

    // ---- player wiring ---------------------------------------------------------------------------------------------------

    fun isEpisode(trackId: String) = isPodcastId(trackId)

    fun episodeFor(trackId: String): Episode? = if (isPodcastId(trackId)) store.find(trackId) else null

    /** The enclosure URL as published (null = not an episode the store knows). */
    fun mediaUrlFor(trackId: String): String? = episodeFor(trackId)?.url

    /** Media type hint ("" = sniff; null = unknown episode). */
    fun mimeTypeFor(trackId: String): String? = episodeFor(trackId)?.let { mimeFor(it) }

    /** Whether the episode's audio may be on the local network (its show was added by URL). */
    fun allowsLocalNetwork(trackId: String): Boolean = episodeFor(trackId)?.let { allowLocal(it.feedUrl) } ?: false

    /** Downloaded episodes play from disk while the file is still there. Blocking (a file check). */
    fun localFileFor(trackId: String): File? {
        if (!isPodcastId(trackId)) return null
        val path = store.downloadedFile(trackId)
        if (path.isEmpty()) return null
        return File(path).takeIf { it.exists() }
    }

    /**
     * The stream to open for an episode: the analytics redirects are followed once with a two-byte range request, so
     * the progressive loader talks straight to the audio host and knows the length and type. An episode the store no
     * longer knows fails (PodcastError "unknown episode") instead of being matched on YouTube by its title.
     */
    suspend fun resolveStream(trackId: String): PodcastStream {
        val e = episodeFor(trackId) ?: run {
            Log.w("podcasts", "unknown episode $trackId")
            throw PodcastError("unknown episode")
        }
        val local = allowLocal(e.feedUrl)
        val mime = mimeFor(e)
        val m = client.resolveMedia(e.url, local)
        return PodcastStream(m.url, mime.ifEmpty { mimeFor(m.mimeType, m.url) }, m.length, local)
    }

    /** Where to start an episode: 3 s before where it was left, 0 = from the start. */
    fun startPositionFor(trackId: String): Long = if (isPodcastId(trackId)) store.resumePosition(trackId) else 0

    /** Before playing [list] from [index]: the store keeps the list (a restored queue resolves) -> the player's tracks. */
    fun prepareQueue(list: List<Episode>, index: Int): List<Track> {
        if (list.isEmpty()) return emptyList()
        store.rememberQueue(list, index.coerceIn(0, list.size - 1))
        return list.toTracks()
    }

    /** The play context of an episode list (C++ PlayContext uri). */
    fun contextUri(e: Episode) = "podcast:" + e.feedUrl

    /**
     * The episodes waiting in the player's queue are kept with the store's queue, so a restored session still finds
     * them. [trackIds] in play order, [playingIndex] the current one.
     */
    fun syncQueue(trackIds: List<String>, playingIndex: Int) {
        val list = ArrayList<Episode>()
        var playing = 0
        for ((i, id) in trackIds.withIndex()) {
            if (!isPodcastId(id)) continue
            val e = store.find(id) ?: continue
            if (i == playingIndex) playing = list.size
            list.add(e)
        }
        if (list.isNotEmpty()) store.rememberQueue(list, playing)
    }

    private class Tracking {
        var id = ""
        var pos = 0L
        var dur = 0L
    }

    private val tracking = Tracking()

    /** The tracked episode ended (another item started, or the queue ran out): heard up to its last seconds = played. */
    private fun finishTracked() = synchronized(tracking) {
        val t = tracking
        if (t.id.isEmpty()) return@synchronized
        if (t.dur > 0 && t.pos >= t.dur - 4_000) store.find(t.id)?.let { store.setPosition(it, t.dur, t.dur, Time.nowUnix()) }
        t.id = ""
        t.pos = 0
        t.dur = 0
    }

    /**
     * The player calls this on every change (track, state, seek) and every couple of seconds while playing: progress
     * is recorded, an episode heard to 95 % or into its last 30 s becomes played.
     */
    fun onPlayerSample(trackId: String?, status: PlayerStatus, positionMs: Long, durationMs: Long) {
        if (trackId == null || !isPodcastId(trackId)) {
            finishTracked()
            return
        }
        synchronized(tracking) {
            if (trackId != tracking.id) {
                finishTracked()
                tracking.id = trackId
            }
            if (status == PlayerStatus.Idle) { // the queue ran out after this episode
                finishTracked()
                tracking.id = trackId
                return
            }
            if (status != PlayerStatus.Playing && status != PlayerStatus.Paused) return
            if (positionMs <= 0) return
            tracking.pos = positionMs
            tracking.dur = durationMs
        }
        store.find(trackId)?.let { store.setPosition(it, positionMs, durationMs, Time.nowUnix()) }
    }

    fun setPlayed(e: Episode, played: Boolean) = store.setPlayed(e, played, Time.nowUnix())

    /**
     * Now Playing shows an episode's notes in the lyrics' place: the HTML as published when the feed had markup (and
     * the episode came from a feed this session), else the plain text. null = not an episode the store knows; both
     * empty = no notes.
     */
    fun showNotesFor(trackId: String): EpisodeNotes? = episodeFor(trackId)?.let { EpisodeNotes(it.description, it.notesHtml) }

    // ---- downloads ---------------------------------------------------------------------------------------------------

    private val _downloads = MutableStateFlow(PodcastDownloads())

    /** The download queue (progress as 0..1). */
    val downloads: StateFlow<PodcastDownloads> = _downloads.asStateFlow()

    private val waitingEpisodes = ArrayDeque<Episode>()
    private var activeJob: Job? = null
    private var userCanceled = false

    /** The active download was stopped because the connection may no longer be used (it goes back in the queue). */
    private var pausedByNetwork = false
    private val connection = MutableStateFlow(PodcastConnection())
    private val networkAttached = AtomicBoolean(false)

    /**
     * Gates episode downloads on the connection like song downloads (Settings.downloadWifiOnly: wait while metered,
     * resume once not; always wait while offline). Called once by AppGraph with downloads.NetworkMonitor's state;
     * without it downloads are never gated.
     */
    fun attachNetwork(state: Flow<PodcastConnection>) {
        if (!networkAttached.compareAndSet(false, true)) return
        appScope.launch {
            combine(state, SettingsStore.flow.map { it.downloadWifiOnly }.distinctUntilChanged()) { c, _ -> c }.collect { c ->
                connection.value = c
                regate()
            }
        }
    }

    /** Why downloads may not run now (null = they may). */
    private fun gate(): PodcastDownloadWait? {
        val c = connection.value
        if (!c.connected) return PodcastDownloadWait.Offline
        if (SettingsStore.current.downloadWifiOnly && c.metered) return PodcastDownloadWait.WifiOnly
        return null
    }

    /** The connection / setting changed: pause the active download if it may not run, else start the queue. */
    private fun regate() {
        val wait = gate()
        synchronized(waitingEpisodes) {
            if (wait != null && _downloads.value.active != null && activeJob != null) {
                Log.i("podcasts", "download paused ($wait)")
                pausedByNetwork = true
                activeJob?.cancel()
            }
            _downloads.update { it.copy(wait = if (waitingEpisodes.isEmpty() && it.active == null) null else wait) }
        }
        if (wait == null) pumpDownloads()
    }

    fun isDownloaded(id: String) = store.downloadedFile(id).isNotEmpty()

    /** Queues an episode (as published, no re-encoding) to <downloads>/Podcasts/<show>/<date> <title>.<ext>. */
    fun startDownload(e: Episode) {
        synchronized(waitingEpisodes) {
            if (isDownloaded(e.id) || _downloads.value.progressOf(e.id) != null) return
            store.remember(e)
            waitingEpisodes.addLast(e)
            _downloads.update { it.copy(waiting = waitingEpisodes.map { w -> w.id }) }
        }
        pumpDownloads()
    }

    fun cancelDownload(id: String) {
        synchronized(waitingEpisodes) {
            waitingEpisodes.firstOrNull { it.id == id }?.let { e ->
                // A download paused by the connection left its partial file: canceling drops it.
                appScope.launch(AppDispatchers.io) { File(downloadPath(Paths.downloadsDir, e).path + ".part").delete() }
            }
            waitingEpisodes.removeAll { it.id == id }
            _downloads.update {
                it.copy(waiting = waitingEpisodes.map { w -> w.id }, wait = if (waitingEpisodes.isEmpty() && it.active == null) null else it.wait)
            }
            if (_downloads.value.active == id) {
                userCanceled = true
                activeJob?.cancel()
            }
        }
    }

    /** Deletes the file (and the show folder once empty, never anything else). */
    suspend fun deleteDownload(e: Episode) {
        val path = store.downloadedFile(e.id)
        if (path.isEmpty()) return
        withContext(AppDispatchers.io) {
            val f = File(path)
            f.delete()
            val dir = f.parentFile
            if (dir != null && dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete()
        }
        store.clearDownload(e.id)
        _events.tryEmit(PodcastEvent.DownloadDeleted(e))
    }

    private fun pumpDownloads() {
        synchronized(waitingEpisodes) {
            if (_downloads.value.active != null) return
            if (waitingEpisodes.isEmpty()) {
                _downloads.update { if (it.wait != null) it.copy(wait = null) else it }
                return
            }
            gate()?.let { wait ->
                _downloads.update { it.copy(waiting = waitingEpisodes.map { w -> w.id }, wait = wait) }
                return
            }
            val e = waitingEpisodes.removeFirst()
            userCanceled = false
            pausedByNetwork = false
            _downloads.value = PodcastDownloads(waitingEpisodes.map { it.id }, e.id, 0f)
            val target = downloadPath(Paths.downloadsDir, e)
            val local = allowLocal(e.feedUrl)
            Log.i("podcasts", "download: ${e.title}")
            activeJob = appScope.launch(AppDispatchers.low) {
                var last = 0L
                val result = runCatching {
                    client.download(e.url, target, local) { done, total ->
                        if (total <= 0) return@download
                        val now = System.nanoTime()
                        if (done < total && now - last < 200_000_000L) return@download
                        last = now
                        val f = (done.toDouble() / total).toFloat()
                        _downloads.update { if (it.active == e.id) it.copy(progress = f) else it }
                    }
                }
                val canceled = synchronized(waitingEpisodes) { userCanceled }
                val paused = synchronized(waitingEpisodes) { pausedByNetwork && !userCanceled }
                result.onSuccess { bytes ->
                    store.setDownloaded(e, target.absolutePath, bytes, Time.nowUnix())
                    store.saveIfDirty()
                    _events.tryEmit(PodcastEvent.DownloadFinished(e))
                }.onFailure { err ->
                    if (canceled) {
                        File(target.path + ".part").delete()
                    } else if (paused) {
                        // The connection may not be used now: back to the front of the queue; the .part resumes later.
                        synchronized(waitingEpisodes) { waitingEpisodes.addFirst(e) }
                    } else if (err !is CancellationException) {
                        Log.w("podcasts", "download of \"${e.title}\" failed: ${err.message}")
                        _events.tryEmit(PodcastEvent.DownloadFailed(e, PodcastErrorKind.of(err)))
                    } // else: the process / scope is going away: the .part stays, downloading again resumes it
                }
                synchronized(waitingEpisodes) {
                    activeJob = null
                    pausedByNetwork = false
                    _downloads.update { it.copy(waiting = waitingEpisodes.map { w -> w.id }, active = null, progress = 0f) }
                }
                pumpDownloads()
            }
        }
    }

    /** All downloaded episodes, newest download first. */
    fun downloaded(): List<Episode> = store.downloaded()
}
