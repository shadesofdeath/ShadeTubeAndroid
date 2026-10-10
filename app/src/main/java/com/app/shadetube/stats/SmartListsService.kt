package com.app.shadetube.stats

import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.ModelJson
import com.app.shadetube.catalog.Page
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Files
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.appScope
import com.app.shadetube.core.arr
import com.app.shadetube.core.get
import com.app.shadetube.core.int
import com.app.shadetube.core.isObject
import com.app.shadetube.core.long
import com.app.shadetube.core.obj
import com.app.shadetube.core.str
import com.app.shadetube.core.stringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Spotify for the smart lists (the logged-in session; AppGraph wires it). */
interface SmartListsSpotify : SpotifyRadioSource {
    /** One page of Liked Songs (newest first). */
    suspend fun likedPage(offset: Int, limit: Int): Page<Track>
}

/** What the smart lists need from outside this package (AppGraph implements it). */
interface SmartListsSources {
    /** Spotify user id of the session while logged in, "" logged out. */
    fun currentUser(): String

    /** A saved Spotify session still connecting: build once it has (its change calls [SmartListsService.ensure]). */
    fun sessionConnecting(): Boolean

    /** The logged-in session, null logged out. */
    fun spotify(): SmartListsSpotify?

    /** Logged out: the local library's liked songs. */
    fun localLiked(): List<Track>
}

/**
 * "Senin için listeler" in the app (port of the non-UI part of app/SmartListsPage): keeping the lists current, the
 * fresh songs of the daily mixes, the cache file and the covers.
 *
 * When: the lists are built on a worker from a copy of the listening history ([ListenStats]) and the liked songs, at
 * the first use of the day, after the history changed (at most every 20 minutes), after a login / logout (AppGraph
 * calls [ensure] on session changes), and the blocklist is applied again whenever it changes
 * ([onBlocklistChanged]). The newest build is kept in `smart-lists.json` (with the day it was built for), so a
 * restart shows the same lists at once.
 *
 * Fresh songs for the daily mixes: logged in, Spotify's radio of the mix's lead song (read-only, two requests a
 * mix); logged out, ListenBrainz's similar artists of the lead artist and their popular songs. Fetched once a day per
 * lead and kept in the cache file. Logged in, the liked songs are Spotify's (the newest 500, fetched once a day);
 * logged out, the local library's.
 *
 * Covers: songs without artwork (the imported history) take it from [StatsArtwork]'s cache; the first songs of every
 * list are looked up there ([COVER_LOOKUPS] per build).
 */
class SmartListsService(
    private val stats: ListenStats,
    private val sources: SmartListsSources,
    /** The blocklist (the playback package's): blocked songs are left out. */
    private val blocked: (Track) -> Boolean = { false },
    private val artwork: StatsArtwork? = null,
    private val similar: SimilarArtistsSource = SimilarArtistsSource.ListenBrainz,
    private val cacheFile: () -> File = { File(Paths.appData, "smart-lists.json") },
    private val scope: CoroutineScope = appScope,
    private val steadyMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val lock = Any()
    private var wired = false
    private var cacheTried = false
    private var cacheLoading = false
    private var building = false
    /** As built (no blocklist). */
    private var raw: List<SmartList> = emptyList()
    private var builtDay = -1L
    /** Spotify user id of the build ("" = logged out). */
    private var builtUser = ""
    /** History size at the build (change detection). */
    private var builtPlays = 0
    private var builtAtMs = 0L
    /** A build or the cache has landed (else: still loading). */
    private var builtOnce = false
    // Kept for the day: Spotify's liked songs and the fresh songs of each mix lead.
    private var liked: List<Track> = emptyList()
    private var likedDay = -1L
    private var likedUser = ""
    private var fresh: Map<String, List<Track>> = emptyMap()
    private var freshDay = -1L
    private var freshUser = ""

    private val shownFlow = MutableStateFlow<List<SmartList>>(emptyList())

    /** The lists as last built, blocklist applied, in display order (empty until the first build / cache load). */
    val lists: StateFlow<List<SmartList>> = shownFlow.asStateFlow()

    private val loadedFlow = MutableStateFlow(false)

    /** A build or the cache has landed (false: still loading, show a skeleton). */
    val ready: StateFlow<Boolean> = loadedFlow.asStateFlow()

    fun find(id: String): SmartList? = shownFlow.value.firstOrNull { it.id == id }

    private fun today(): Long = Listen.dayNumber(systemLocalClock().toLocal(nowUnix()))

    private fun wire() {
        wired = true
        scope.launch { stats.version.drop(1).collect { ensure() } }
        artwork?.let { a ->
            scope.launch {
                a.version.drop(1).collect {
                    synchronized(lock) { enrichCovers(0) }
                }
            }
        }
    }

    /** Loads the cache / rebuilds when due (see the class comment). Cheap to call often. */
    fun ensure() {
        synchronized(lock) {
            if (!wired) wire()
            if (!cacheTried) {
                loadCache() // then ensure() again
                return
            }
            if (cacheLoading || building || !stats.isLoaded) return
            // A saved Spotify session still connecting: build once it has (its change calls ensure() again), not twice.
            if (sources.sessionConnecting()) return
            val user = sources.currentUser()
            val plays = stats.playCount
            val due = !builtOnce || builtDay != today() || builtUser != user ||
                (plays != builtPlays && steadyMs() - builtAtMs >= REBUILD_AFTER_MS)
            if (due) startBuild()
        }
    }

    /** The blocklist changed: applied again to the built lists. */
    fun onBlocklistChanged() {
        synchronized(lock) {
            applyBlocklist()
            enrichCovers(0)
        }
    }

    // --- blocklist / covers ------------------------------------------------------------------------------------------

    /** The shown lists: the built ones minus blocked songs, and without the lists that fell below their minimum. */
    private fun applyBlocklist() {
        shownFlow.value = raw.mapNotNull { SmartLists.withoutBlocked(it, blocked) }
    }

    private fun artKey(t: Track) = t.name + '\u001f' + (if (t.artists.isEmpty()) "" else t.artists[0].name)

    /**
     * Covers: songs without artwork (the imported history) take it from the Stats artwork cache. The first songs of
     * every list go in one batch (one request at a time), [lookups] of them looked up; what is found fills every list.
     */
    private fun enrichCovers(lookups: Int) {
        val art = artwork ?: return
        val shown = shownFlow.value
        val heads = ArrayList<Track>()
        val seen = HashSet<String>()
        for (l in shown)
            for (t in l.tracks.take(8))
                if (t.album.images.isEmpty() && seen.add(artKey(t))) heads.add(t)
        if (heads.isEmpty()) return
        val found = HashMap<String, List<Image>>()
        for (t in art.enrichTracks(heads, lookups)) if (t.album.images.isNotEmpty()) found[artKey(t)] = t.album.images
        if (found.isEmpty()) return
        shownFlow.value = shown.map { l ->
            if (l.tracks.none { it.album.images.isEmpty() && found.containsKey(artKey(it)) }) l
            else l.copy(tracks = l.tracks.map { t ->
                if (t.album.images.isNotEmpty()) t
                else found[artKey(t)]?.let { t.copy(album = t.album.copy(images = it)) } ?: t
            })
        }
    }

    // --- cache file --------------------------------------------------------------------------------------------------

    private fun loadCache() {
        cacheTried = true
        cacheLoading = true
        scope.launch {
            val j = withContext(AppDispatchers.io) { runCatching { Files.readJson(cacheFile()) }.getOrNull() }
            synchronized(lock) {
                cacheLoading = false
                if (j != null && j.isObject && j.int("v") == CACHE_VERSION && !builtOnce) {
                    val c = cacheFromJson(j)
                    raw = c.lists
                    builtDay = c.day
                    builtUser = c.user
                    builtPlays = c.plays
                    builtAtMs = steadyMs()
                    builtOnce = true
                    if (c.fresh != null) {
                        fresh = c.fresh
                        freshDay = c.freshDay
                        freshUser = c.freshUser
                    }
                    if (c.liked != null) {
                        liked = c.liked
                        likedDay = c.likedDay
                        likedUser = c.likedUser
                    }
                    applyBlocklist()
                    enrichCovers(0)
                    loadedFlow.value = true
                }
            }
            ensure()
        }
    }

    private fun saveCache() {
        val j = cacheToJson(
            Cache(raw, builtDay, builtUser, builtPlays,
                if (freshDay == builtDay) fresh else null, freshDay, freshUser,
                if (liked.isNotEmpty() && likedDay == builtDay) liked else null, likedDay, likedUser),
        )
        scope.launch(AppDispatchers.io) { Files.writeJson(cacheFile(), j) }
    }

    // --- building ----------------------------------------------------------------------------------------------------

    private class Job(
        val input: SmartLists.Input,
        /** Logged in: Spotify's liked songs and radios. */
        val api: SmartListsSpotify?,
        val user: String,
        val day: Long,
        /** What is known already (the local library's, or Spotify's of today). */
        var liked: List<Track>,
        val fetchLiked: Boolean,
        /** Today's, per mix lead. */
        val fresh: Map<String, List<Track>>,
    )

    private class Built(
        val lists: List<SmartList>,
        val liked: List<Track>,
        val likedFetched: Boolean,
        val fresh: Map<String, List<Track>>,
        val day: Long,
        val user: String,
        val plays: Int,
    )

    private suspend fun spotifyLiked(api: SmartListsSpotify): List<Track> {
        val out = ArrayList<Track>()
        var offset = 0
        for (page in 0 until LIKED_PAGES) {
            if (page > 0) delay(300)
            val p = api.likedPage(offset, 100)
            val next = if (p.nextOffset >= 0) p.nextOffset else offset + p.items.size
            out.addAll(p.items)
            if (next <= offset || next >= p.total) break
            offset = next
        }
        return out
    }

    /** Fresh songs for one mix lead: Spotify's radio of its song (logged in), else ListenBrainz's similar artists' hits. */
    private suspend fun freshFor(api: SmartListsSpotify?, mix: SmartList): List<Track> {
        if (api != null) {
            if (!mix.seedTrackId.startsWith("spotify:track:")) return emptyList()
            val uri = api.radioPlaylist(mix.seedTrackId)
            if (uri.isEmpty()) return emptyList()
            delay(300)
            return api.playlistTracks(uri, 0, 50)
        }
        if (!isMbid(mix.seedArtistId)) return emptyList()
        val out = ArrayList<Track>()
        for (id in similar.similarArtistIds(mix.seedArtistId, 4).take(2)) out.addAll(similar.topTracks(id, 8))
        return out
    }

    private suspend fun runJob(job: Job): Built {
        var liked = job.liked
        var likedFetched = false
        if (job.fetchLiked && job.api != null) {
            try {
                liked = spotifyLiked(job.api)
                likedFetched = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "liked songs: ${e.message}") // keep the previous ones
            }
        }
        val input = SmartLists.Input(job.input.tracks, job.input.plays, liked, job.input.now, systemLocalClock())
        var lists = SmartLists.build(input)
        // Fresh songs of the mixes (those of today are reused), then blended in.
        val known = input.tracks.map { ListenKeys.of(it).track }
        val fresh = HashMap(job.fresh)
        var first = true
        lists = lists.map { l ->
            if (l.kind != SmartLists.Kind.Mix) return@map l
            val lead = leadKey(l)
            if (lead.isEmpty()) return@map l
            if (!fresh.containsKey(lead)) {
                try {
                    if (!first) delay(300)
                    first = false
                    fresh[lead] = freshFor(job.api, l)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "fresh songs for $lead: ${e.message}") // asked again at the next build
                }
            }
            fresh[lead]?.let { SmartLists.mixFresh(l, it, known) } ?: l
        }
        return Built(lists, liked, likedFetched, fresh, job.day, job.user, input.plays.size)
    }

    private fun startBuild() {
        building = true
        val (tracks, plays) = stats.historyCopy()
        val day = today()
        val user = sources.currentUser()
        val api = if (user.isEmpty()) null else sources.spotify()
        var jobLiked: List<Track>
        var fetchLiked = false
        when {
            api == null -> jobLiked = runCatching { sources.localLiked() }.getOrDefault(emptyList())
            likedDay == day && likedUser == user -> jobLiked = liked
            else -> {
                fetchLiked = true
                jobLiked = if (likedUser == user) liked else emptyList() // yesterday's, if today's fetch fails
            }
        }
        val job = Job(
            SmartLists.Input(tracks, plays, emptyList(), nowUnix()), api, user, day, jobLiked, fetchLiked,
            if (freshDay == day && freshUser == user) fresh else emptyMap(),
        )
        scope.launch {
            val r = try {
                withContext(AppDispatchers.low) { runJob(job) }
            } catch (e: CancellationException) {
                synchronized(lock) { building = false }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "building the lists failed: ${e.message}")
                synchronized(lock) { building = false }
                return@launch
            }
            synchronized(lock) {
                building = false
                raw = r.lists
                builtDay = r.day
                builtUser = r.user
                builtPlays = r.plays
                builtAtMs = steadyMs()
                builtOnce = true
                if (r.likedFetched) {
                    liked = r.liked
                    likedDay = r.day
                    likedUser = r.user
                }
                fresh = r.fresh
                freshDay = r.day
                freshUser = r.user
                Log.i(TAG, "${raw.size} list(s) built from $builtPlays plays")
                applyBlocklist()
                enrichCovers(COVER_LOOKUPS)
                saveCache()
                loadedFlow.value = true
            }
        }
    }

    /** The cache file's content (smart-lists.json). */
    class Cache(
        val lists: List<SmartList>,
        val day: Long,
        val user: String,
        val plays: Int,
        val fresh: Map<String, List<Track>>?,
        val freshDay: Long,
        val freshUser: String,
        val liked: List<Track>?,
        val likedDay: Long,
        val likedUser: String,
    )

    companion object {
        private const val TAG = "smart"

        /** The history changed: rebuild at most this often. */
        const val REBUILD_AFTER_MS = 20 * 60 * 1000L

        /** Spotify's liked songs: the newest 5 x 100. */
        const val LIKED_PAGES = 5

        /** Artwork lookups per build for the covers. */
        const val COVER_LOOKUPS = 16

        const val CACHE_VERSION = 1

        fun isMbid(id: String) = id.length == 36 && id[8] == '-' && id[13] == '-' && id[18] == '-' && id[23] == '-'

        fun leadKey(mix: SmartList): String = when {
            mix.seedTrackId.isNotEmpty() -> mix.seedTrackId
            mix.seedArtistId.isNotEmpty() -> mix.seedArtistId
            mix.artists.isEmpty() -> ""
            else -> "name:" + mix.artists[0]
        }

        private fun tracksJson(tracks: List<Track>) = JsonArray(tracks.map { ModelJson.track(it) })
        private fun tracksFrom(a: JsonElement?): List<Track> = a.arr.filter { it.isObject }.map { ModelJson.trackFrom(it) }

        fun listJson(l: SmartList): JsonObject {
            val m = LinkedHashMap<String, JsonElement>()
            m["id"] = JsonPrimitive(l.id)
            m["k"] = JsonPrimitive(l.kind.ordinal)
            m["n"] = JsonPrimitive(l.number)
            m["s"] = JsonPrimitive(l.streams)
            m["t"] = tracksJson(l.tracks)
            val f = l.fresh.indices.filter { l.fresh[it] }
            if (f.isNotEmpty()) m["f"] = JsonArray(f.map { JsonPrimitive(it) })
            if (l.artists.isNotEmpty()) m["a"] = JsonArray(l.artists.map { JsonPrimitive(it) })
            if (l.seedTrackId.isNotEmpty()) m["st"] = JsonPrimitive(l.seedTrackId)
            if (l.seedArtistId.isNotEmpty()) m["sa"] = JsonPrimitive(l.seedArtistId)
            return JsonObject(m)
        }

        fun listFrom(j: JsonElement?, day: Long): SmartList? {
            if (j !is JsonObject) return null
            val id = j["id"].takeIf { it is JsonPrimitive && it.isString }?.stringOrNull ?: return null
            val k = j.int("k", -1)
            if (k < 0 || k > SmartLists.Kind.Year.ordinal) return null
            val tracks = tracksFrom(j["t"])
            val fresh = BooleanArray(tracks.size)
            for (i in j["f"].arr) {
                val v = ListenJson.integerOf(i) ?: continue
                if (v >= 0 && v < fresh.size) fresh[v.toInt()] = true
            }
            return SmartList(
                id = id, kind = SmartLists.Kind.entries[k], number = j.int("n"), tracks = tracks, fresh = fresh.toList(),
                artists = j["a"].arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                seedTrackId = j.str("st"), seedArtistId = j.str("sa"), streams = j.int("s"), day = day,
            )
        }

        fun cacheToJson(c: Cache): JsonElement {
            val m = LinkedHashMap<String, JsonElement>()
            m["v"] = JsonPrimitive(CACHE_VERSION)
            m["day"] = JsonPrimitive(c.day)
            m["user"] = JsonPrimitive(c.user)
            m["plays"] = JsonPrimitive(c.plays)
            m["lists"] = JsonArray(c.lists.map { listJson(it) })
            if (c.fresh != null) {
                m["fresh"] = JsonObject(c.fresh.mapValues { tracksJson(it.value) })
                m["freshDay"] = JsonPrimitive(c.freshDay)
                m["freshUser"] = JsonPrimitive(c.freshUser)
            }
            if (c.liked != null) {
                m["liked"] = tracksJson(c.liked)
                m["likedDay"] = JsonPrimitive(c.likedDay)
                m["likedUser"] = JsonPrimitive(c.likedUser)
            }
            return JsonObject(m)
        }

        fun cacheFromJson(j: JsonElement): Cache {
            val day = j.long("day", -1)
            val freshObj = j["fresh"].obj
            return Cache(
                lists = j["lists"].arr.mapNotNull { listFrom(it, day) },
                day = day,
                user = j.str("user"),
                plays = j.int("plays"),
                fresh = freshObj?.mapValues { tracksFrom(it.value) },
                freshDay = if (freshObj != null) j.long("freshDay", -1) else -1,
                freshUser = if (freshObj != null) j.str("freshUser") else "",
                liked = if (j.obj?.containsKey("liked") == true) tracksFrom(j["liked"]) else null,
                likedDay = j.long("likedDay", -1),
                likedUser = j.str("likedUser"),
            )
        }
    }
}
