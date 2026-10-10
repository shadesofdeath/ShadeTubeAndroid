package com.app.shadetube.internetradio

import com.app.shadetube.BuildConfig
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.Time
import com.app.shadetube.core.appScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A title the playing station announced (Now Playing's "heard on this station" list). */
data class HeardTitle(
    /** Unix seconds. */
    val at: Long,
    val title: String,
) {
    val nowOnAir: LiveParsers.NowOnAir? get() = LiveParsers.splitStreamTitle(title)
}

/** The station URL and type hint the player starts from (C++ Player::LiveStream); resolve it with [InternetRadio.resolveStream]. */
data class StationStream(val url: String, val mimeType: String)

/**
 * The internet radio service (port of the wiring in app/RadioPage.cpp + initInternetRadio): the player's stream lookup,
 * the click counting radio-browser.info asks for, recently played, favorites and the song titles a station announces.
 *
 * Call [init] once at startup (after Paths / SettingsStore). Network operations are `suspend` and main-safe.
 */
object InternetRadio {
    val client = RadioBrowserClient()
    val store = RadioStore()

    /** Display texts (country names, genre labels): the UI installs a strings.xml-backed implementation. */
    @Volatile
    var labels: RadioLabels = DefaultRadioLabels

    private val initialized = AtomicBoolean(false)
    private var housekeeping: Job? = null

    val userAgent: String get() = "ShadeTube/${BuildConfig.VERSION_NAME} (+https://github.com/shadesofdeath/ShadeTubeAndroid)"

    /** Loads radio.json (blocking: one small file) and starts saving changes in the background. Idempotent. */
    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        client.userAgent = userAgent
        store.load(Paths.file("radio.json"))
        housekeeping = appScope.launch(AppDispatchers.io) {
            while (true) {
                delay(2_000) // the C++ housekeeping tick
                store.saveIfDirty()
            }
        }
    }

    /** The app is going away: unsaved changes are written now. */
    suspend fun flush() = withContext(AppDispatchers.io) { store.saveIfDirty() }

    /** The country of "Popular": the one picked on the page, else the device's region, else the app region, else TR. */
    fun homeCountry(): String {
        val picked = store.countryNow()
        if (picked.length == 2) return picked
        val c = Locale.getDefault().country
        if (c.length == 2 && c.all { it.isLetter() }) return c.uppercase(Locale.ROOT)
        val r = SettingsStore.current.region
        if (r.length == 2) return r
        return "TR"
    }

    // ---- directory (the Radio screens; routes "", tag:<tag>, country:<CC>, top, votes, search:<name>) --------------

    suspend fun topClick(limit: Int = 50, offset: Int = 0) = client.topClick(limit, offset).also { store.remember(it.stations) }
    suspend fun topVote(limit: Int = 50, offset: Int = 0) = client.topVote(limit, offset).also { store.remember(it.stations) }
    suspend fun search(q: RadioQuery) = client.search(q).also { store.remember(it.stations) }

    suspend fun byTag(tag: String, limit: Int = 50, offset: Int = 0, byVotes: Boolean = false) =
        search(RadioQuery(tag = tag, limit = limit, offset = offset, order = if (byVotes) RadioQuery.Order.Votes else RadioQuery.Order.ClickCount))

    suspend fun byCountry(code: String, limit: Int = 50, offset: Int = 0, byVotes: Boolean = false) =
        search(RadioQuery(countryCode = code, limit = limit, offset = offset, order = if (byVotes) RadioQuery.Order.Votes else RadioQuery.Order.ClickCount))

    suspend fun byName(name: String, limit: Int = 50, offset: Int = 0) = search(RadioQuery(name = name, limit = limit, offset = offset))

    suspend fun tags(limit: Int = 200) = client.tags(limit)
    suspend fun countries() = client.countries()

    private val favoritesRefreshed = AtomicBoolean(false)

    /** Favorites carry the data they had when saved: refreshed from the server once per app session. */
    suspend fun refreshFavorites() {
        val favs = store.favoritesNow()
        if (favs.isEmpty() || !favoritesRefreshed.compareAndSet(false, true)) return
        runCatching { client.byUuids(favs.map { it.uuid }) }
            .onSuccess { store.refresh(it) }
            .onFailure { Log.w("iradio", "favorites refresh failed: ${it.message}") }
    }

    // ---- player wiring -------------------------------------------------------------------------------------------------

    fun isStation(trackId: String) = RadioModel.isStationId(trackId)

    fun stationFor(trackId: String): Station? = if (isStation(trackId)) store.find(RadioModel.uuidOf(trackId)) else null

    /**
     * Stations play as live streams. The registry knows every station the UI listed and the stations of the last played
     * list (radio.json), so a restored queue resolves too. An unknown station gets an empty URL: it fails like a dead
     * stream instead of being matched on YouTube by its name. null = not a station id.
     */
    fun liveStreamFor(trackId: String): StationStream? {
        if (!isStation(trackId)) return null
        val s = store.find(RadioModel.uuidOf(trackId))
        if (s == null) {
            Log.w("iradio", "unknown station $trackId")
            return StationStream("", "")
        }
        return StationStream(RadioModel.streamUrl(s), RadioModel.mimeType(s))
    }

    /**
     * The URL the player opens for a station: playlists resolved (entry [attempt] % n, so a reconnect after a failure
     * tries the next one), HLS recognised. Public internet only. Throws [RadioError].
     */
    suspend fun resolveStream(trackId: String, attempt: Int = 0): LiveStreamSource {
        val s = liveStreamFor(trackId) ?: throw RadioError("not a station")
        if (s.url.isEmpty()) throw RadioError("unknown station")
        return StationStreamResolver.resolve(s.url, s.mimeType, attempt)
    }

    /** Before playing [list] from [index]: the store keeps the list (a restored queue resolves) -> the player's tracks. */
    fun prepareQueue(list: List<Station>, index: Int): List<Track> {
        if (list.isEmpty()) return emptyList()
        store.rememberQueue(list, index.coerceIn(0, list.size - 1))
        return RadioModel.toTracks(list, labels)
    }

    // ---- live state ------------------------------------------------------------------------------------------------------

    private class LiveState {
        var trackId = "" // the station the heard titles belong to
        var lastTitle = ""
        var startedId = "" // station whose start was handled (recent + click), "" after another track
    }

    private val live = LiveState()
    private val _heard = MutableStateFlow<List<HeardTitle>>(emptyList())
    private val _nowOnAir = MutableStateFlow<LiveParsers.NowOnAir?>(null)

    /** Titles the playing station announced, newest first (at most 12). */
    val heardTitles: StateFlow<List<HeardTitle>> = _heard.asStateFlow()

    /** What the playing station says is on air now (null = nothing announced / not a station). */
    val nowOnAir: StateFlow<LiveParsers.NowOnAir?> = _nowOnAir.asStateFlow()

    /**
     * The player calls this on every change: [trackId] playing (or paused), [isPlaying], and the live title it has
     * from the stream's ICY / HLS metadata ("" = none). A station that starts playing goes to recently played and gets
     * radio-browser.info's click, once per start (a pause / resume or a reconnect is not a new start).
     */
    fun onPlayerChanged(trackId: String?, isPlaying: Boolean, liveTitle: String) {
        var startUuid: String? = null
        synchronized(live) {
            if (trackId == null || !isStation(trackId)) {
                live.startedId = ""
                _nowOnAir.value = null
                return
            }
            if (trackId != live.trackId) {
                live.trackId = trackId
                live.lastTitle = ""
                _heard.value = emptyList()
                _nowOnAir.value = null
            }
            val title = LiveParsers.cleanText(liveTitle)
            if (title.isNotEmpty() && title != live.lastTitle) {
                live.lastTitle = title
                _heard.value = (listOf(HeardTitle(Time.nowUnix(), title)) + _heard.value).take(12)
                _nowOnAir.value = LiveParsers.splitStreamTitle(title)
            }
            if (!isPlaying || live.startedId == trackId) return
            live.startedId = trackId
            startUuid = RadioModel.uuidOf(trackId)
        }
        val uuid = startUuid ?: return
        store.find(uuid)?.let {
            Log.i("iradio", "station playing: ${it.name}")
            store.recordPlayed(it)
        }
        appScope.launch(AppDispatchers.low) { client.countClick(uuid) }
    }

    /** Convenience for the player: a raw ICY metadata block (ExoPlayer IcyInfo.rawMetadata) -> the title, if any. */
    fun titleFromIcy(rawMetadata: ByteArray): String? = LiveParsers.icyStreamTitle(rawMetadata)
}
