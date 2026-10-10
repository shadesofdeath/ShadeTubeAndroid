package com.app.shadetube.stats

import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Files
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.Text
import com.app.shadetube.core.appScope
import com.app.shadetube.core.arr
import com.app.shadetube.core.bool
import com.app.shadetube.core.get
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.long
import com.app.shadetube.core.obj
import com.app.shadetube.core.str
import com.app.shadetube.musicbrainz.MusicBrainz
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** The catalog search used for artwork lookups (the logged-in Spotify session's search; AppGraph wires it). */
fun interface StatsCatalogSearch {
    suspend fun search(query: String, limit: Int): SearchResults
}

/** Artist pictures from sources outside this package (AppGraph wires them). */
interface StatsArtistImages {
    /** A followed artist's pictures from the library (Spotify's or the local one); null when not there. */
    fun libraryImages(artist: ArtistRef): List<Image>?

    /** A Spotify artist's pictures (artist overview); only called while [StatsArtwork] has a Spotify search. */
    suspend fun spotifyArtistImages(uri: String): List<Image>
}

/**
 * Artwork and ids for entries that have none (the Stats page's caches; port of the artwork part of
 * app/StatsPage.cpp + enrichTrackArtwork).
 *
 * Spotify's history has names (and track URIs) but no covers and no artist / album ids; plain-text credits have no
 * ids either. The top entries on screen are looked up once through the catalog search (Spotify while logged in, else
 * MusicBrainz), one request at a time, and the answers kept in `cache/stats-artwork.json` (misses too: asked again
 * after [ART_RETRY_DAYS]).
 *
 * Artist pictures: tracks carry none; a followed artist's come from the library, the others are looked up (an MBID
 * through MusicBrainz, a Spotify URI through the artist overview while logged in). Answers are cached for the
 * session (empty = the source has none); a failed lookup (offline, 429, timeout) waits [RETRY_MS] and is asked
 * again by a later build.
 *
 * Landed answers bump [version]: pages re-run their enrich calls then. Thread-safe.
 *
 * @param spotify the logged-in session's search, null while logged out / connecting.
 */
class StatsArtwork(
    private val spotify: () -> StatsCatalogSearch?,
    private val artistImageSource: StatsArtistImages? = null,
    private val file: () -> File = { File(Paths.cacheDir, "stats-artwork.json") },
    private val scope: CoroutineScope = appScope,
    private val musicBrainz: MusicBrainzSearch = MusicBrainzSearch.Default,
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
    private val steadyMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** The MusicBrainz side of the lookups (an interface so tests run without network). */
    interface MusicBrainzSearch {
        suspend fun tracks(query: String, limit: Int): List<Track>
        suspend fun albums(query: String, limit: Int): List<com.app.shadetube.catalog.Album>
        suspend fun artists(query: String, limit: Int): List<com.app.shadetube.catalog.Artist>
        suspend fun artistImages(mbid: String): List<Image>

        object Default : MusicBrainzSearch {
            override suspend fun tracks(query: String, limit: Int) = MusicBrainz.searchTracks(query, limit)
            override suspend fun albums(query: String, limit: Int) = MusicBrainz.searchAlbums(query, limit)
            override suspend fun artists(query: String, limit: Int) = MusicBrainz.searchArtists(query, limit)
            override suspend fun artistImages(mbid: String) = MusicBrainz.artist(mbid).images
        }
    }

    enum class Kind { Track, Album, Artist }

    class Art(
        val images: List<Image> = emptyList(),
        /** Track: its album's id; album / artist: its own id. */
        val id: String = "",
        /** Track: its first artist's id. */
        val artistId: String = "",
        /** Unix seconds of the lookup. */
        val at: Long = 0,
        val found: Boolean = false,
    )

    class Want(val key: String, val kind: Kind, val name: String, val artist: String)

    private val lock = Any()
    private val art = HashMap<String, Art>()
    private val lookups = HashSet<String>() // in flight
    private val retryAt = HashMap<String, Long>() // failed (offline, 503, 429): steadyMs() of the next attempt
    private var loaded = false
    private val loadMutex = Mutex()

    private val artistImagesById = HashMap<String, List<Image>>() // id -> images
    private val artistRetryAt = HashMap<String, Long>()
    private val artistLookups = HashSet<String>()

    private val versionFlow = MutableStateFlow(0L)

    /** Bumped when artwork or artist pictures landed. */
    val version: StateFlow<Long> = versionFlow.asStateFlow()

    private fun bump() {
        versionFlow.value = versionFlow.value + 1
    }

    // --- keys / cache -----------------------------------------------------------------------------------------------

    fun foundArt(key: String): Art? = synchronized(lock) { art[key]?.takeIf { it.found } }

    /** Known: found, or a miss that is not due for another look yet. */
    fun artKnown(key: String): Boolean = synchronized(lock) {
        val a = art[key] ?: return false
        a.found || nowUnix() - a.at < ART_RETRY_DAYS * 86400
    }

    private suspend fun ensureLoaded() {
        if (synchronized(lock) { loaded }) return
        loadMutex.withLock {
            if (synchronized(lock) { loaded }) return
            val m = withContext(AppDispatchers.io) { readCache(file()) }
            synchronized(lock) {
                for ((k, a) in m) art.putIfAbsent(k, a) // this session's answers win
                loaded = true
            }
            bump()
        }
    }

    private fun saveCache() {
        // Newest entries first when trimming (the cache only ever grows by what the top lists showed).
        val items = synchronized(lock) { art.entries.map { it.key to it.value } }
            .sortedByDescending { it.second.at }
            .take(ART_MAX_ENTRIES)
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, a) in items) {
            val e = LinkedHashMap<String, JsonElement>()
            e["at"] = JsonPrimitive(a.at)
            e["f"] = JsonPrimitive(a.found)
            if (a.id.isNotEmpty()) e["id"] = JsonPrimitive(a.id)
            if (a.artistId.isNotEmpty()) e["aid"] = JsonPrimitive(a.artistId)
            if (a.images.isNotEmpty())
                e["img"] = JsonArray(a.images.take(3).map { JsonArray(listOf(JsonPrimitive(it.url), JsonPrimitive(it.width), JsonPrimitive(it.height))) })
            out[k] = JsonObject(e)
        }
        val j = jsonOf("v" to 1, "items" to JsonObject(out))
        scope.launch(AppDispatchers.io) { Files.writeJson(file(), j) }
    }

    // --- enrich ------------------------------------------------------------------------------------------------------

    /** A top entry filled from the artwork cache, or added to [wants]. */
    fun enrich(t: Track, wants: MutableList<Want>): Track {
        if (t.album.images.isNotEmpty() || t.name.isEmpty()) return t
        val artist = if (t.artists.isEmpty()) "" else t.artists[0].name
        val key = artKey(Kind.Track, t.name, artist)
        val a = foundArt(key)
        if (a != null) {
            val artists = if (t.artists.isNotEmpty() && t.artists[0].id.isEmpty())
                listOf(t.artists[0].copy(id = a.artistId)) + t.artists.drop(1) else t.artists
            return t.copy(
                album = t.album.copy(images = a.images, id = t.album.id.ifEmpty { a.id }),
                artists = artists,
            )
        }
        if (!artKnown(key)) wants.add(Want(key, Kind.Track, t.name, artist))
        return t
    }

    fun enrich(al: StatsAlbum, wants: MutableList<Want>): StatsAlbum {
        if (al.album.images.isNotEmpty() || al.album.name.isEmpty()) return al
        val artist = if (al.variousArtists) "" else al.artist
        val key = artKey(Kind.Album, al.album.name, artist)
        val a = foundArt(key)
        if (a != null) return al.copy(album = al.album.copy(images = a.images, id = al.album.id.ifEmpty { a.id }))
        if (!artKnown(key)) wants.add(Want(key, Kind.Album, al.album.name, artist))
        return al
    }

    fun enrich(ar: StatsArtist, wants: MutableList<Want>): StatsArtist {
        if (ar.artist.id.isNotEmpty() || ar.artist.name.isEmpty()) return ar
        val key = artKey(Kind.Artist, "", ar.artist.name)
        val a = foundArt(key)
        if (a != null) return ar.copy(artist = ar.artist.copy(id = a.id))
        if (!artKnown(key)) wants.add(Want(key, Kind.Artist, "", ar.artist.name))
        return ar
    }

    /**
     * Covers for songs that have none (the imported history): filled from the cache, and up to [lookups] of the
     * missing ones looked up (one request at a time); [version] bumps as answers arrive. Port of enrichTrackArtwork.
     */
    fun enrichTracks(tracks: List<Track>, lookups: Int): List<Track> {
        val wants = ArrayList<Want>()
        val out = tracks.map { enrich(it, wants) }
        fetch(wants.take(lookups))
        return out
    }

    /**
     * Looks [wants] up on a worker, one request at a time (a short pause between Spotify requests). Each answer
     * lands as it comes ([version] bumps); the cache file is written when the batch is done. The cache file is read
     * first.
     */
    fun fetch(wants: List<Want>) {
        if (wants.isEmpty() && synchronized(lock) { loaded }) return
        scope.launch {
            ensureLoaded()
            val now = steadyMs()
            val todo = synchronized(lock) {
                wants.filter { w ->
                    val retry = retryAt[w.key]
                    if (retry != null && now < retry) false
                    else !(art[w.key]?.let { it.found || nowUnix() - it.at < ART_RETRY_DAYS * 86400 } ?: false) &&
                        !lookups.contains(w.key)
                }.distinctBy { it.key }.also { list -> list.forEach { lookups.add(it.key) } }
            }
            if (todo.isEmpty()) return@launch
            val api = spotify()
            try {
                for ((i, w) in todo.withIndex()) {
                    if (i > 0 && api != null) delay(250)
                    try {
                        val a = withContext(AppDispatchers.io) { lookup(api, w) }
                        synchronized(lock) {
                            lookups.remove(w.key)
                            if (a.found && a.images.isNotEmpty() && a.id.isNotEmpty() && w.kind == Kind.Artist)
                                artistImagesById.putIfAbsent(a.id, a.images) // artist pictures also serve artistImages()
                            art[w.key] = a
                        }
                        if (a.found) bump()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d("stats", "artwork lookup: ${e.message}") // not remembered: asked again in a while
                        synchronized(lock) {
                            lookups.remove(w.key)
                            retryAt[w.key] = steadyMs() + RETRY_MS
                        }
                    }
                }
            } finally {
                synchronized(lock) { todo.forEach { lookups.remove(it.key) } } // the unanswered may be asked again
                saveCache()
            }
        }
    }

    /**
     * One lookup: the first search result with the same (folded) name and, when known, the same artist. Spotify
     * answers all kinds in one request; MusicBrainz is asked for just the kind needed (it allows one request a
     * second).
     */
    private suspend fun lookup(api: StatsCatalogSearch?, w: Want): Art {
        val at = nowUnix()
        val name = artFold(w.name)
        val artist = artFold(w.artist)
        fun sameArtist(list: List<ArtistRef>) = artist.isEmpty() || list.any { artFold(it.name) == artist }
        val query = when {
            w.kind == Kind.Artist -> w.artist
            w.artist.isEmpty() -> w.name
            else -> w.name + " " + w.artist
        }
        val res = when {
            api != null -> api.search(query, 8)
            w.kind == Kind.Track -> SearchResults(tracks = musicBrainz.tracks(query, 8))
            w.kind == Kind.Album -> SearchResults(albums = musicBrainz.albums(query, 8))
            else -> SearchResults(artists = musicBrainz.artists(query, 8))
        }
        when (w.kind) {
            Kind.Track -> for (t in res.tracks) if (artFold(t.name) == name && sameArtist(t.artists))
                return Art(t.album.images, t.album.id, if (t.artists.isEmpty()) "" else t.artists[0].id, at, true)
            Kind.Album -> for (a in res.albums) if (artFold(a.name) == name && sameArtist(a.artists))
                return Art(a.images, a.id, "", at, true)
            Kind.Artist -> for (a in res.artists) if (artFold(a.name) == artist)
                return Art(a.images, a.id, "", at, true)
        }
        return Art(at = at)
    }

    // --- artist pictures ---------------------------------------------------------------------------------------------

    /** The library's pictures, else what a lookup found this session (empty = none known yet). */
    fun artistImages(a: ArtistRef): List<Image> {
        artistImageSource?.libraryImages(a)?.let { if (it.isNotEmpty()) return it }
        return synchronized(lock) { artistImagesById[a.id] } ?: emptyList()
    }

    /**
     * Looks the pictures of [ids] up on a worker, one request at a time (MusicBrainz is rate limited, Spotify 429s
     * on bursts). [version] bumps when pictures arrive or a batch stopped early.
     */
    fun fetchArtistImages(ids: List<String>) {
        val api = spotify()
        val now = steadyMs()
        val todo = synchronized(lock) {
            ids.distinct().filter { id ->
                if (artistImagesById.containsKey(id)) return@filter false
                val retry = artistRetryAt[id]
                if (retry != null && now < retry) return@filter false
                if (api == null && id.startsWith("spotify:")) return@filter false // logged out: asked again after a login
                if (id.startsWith("spotify:") && artistImageSource == null) return@filter false
                !artistLookups.contains(id) // on their way already
            }.also { list -> artistLookups.addAll(list) }
        }
        if (todo.isEmpty()) return
        scope.launch {
            var any = false
            var complete = true
            try {
                for (id in todo) {
                    try {
                        val images = withContext(AppDispatchers.io) {
                            if (id.startsWith("spotify:")) artistImageSource!!.spotifyArtistImages(id)
                            else musicBrainz.artistImages(id)
                        }
                        synchronized(lock) { artistImagesById[id] = images }
                        any = any || images.isNotEmpty()
                    } catch (e: CancellationException) {
                        complete = false
                        throw e
                    } catch (e: Exception) {
                        Log.d("stats", "artist picture for $id: ${e.message}")
                        complete = false
                        synchronized(lock) { artistRetryAt[id] = steadyMs() + RETRY_MS }
                    }
                }
            } finally {
                synchronized(lock) { artistLookups.removeAll(todo.toSet()) } // the unfinished ones may be asked again
                if (any || !complete) bump()
            }
        }
    }

    companion object {
        const val ART_RETRY_DAYS = 30L
        const val ART_MAX_ENTRIES = 5_000
        const val RETRY_MS = 2 * 60_000L

        fun artFold(s: String): String = Text.fold(s)

        fun artKey(kind: Kind, name: String, artist: String): String {
            val prefix = when (kind) {
                Kind.Track -> "t\u001f"
                Kind.Album -> "al\u001f"
                Kind.Artist -> "ar\u001f"
            }
            return prefix + artFold(name) + '\u001f' + artFold(artist)
        }

        internal fun readCache(f: File): Map<String, Art> {
            val m = HashMap<String, Art>()
            val text = runCatching { if (f.exists()) f.readText() else null }.getOrNull() ?: return m
            val j = com.app.shadetube.core.parseJsonOrNull(text) ?: return m
            val items = j["items"].obj ?: return m
            for ((k, e) in items) {
                if (e !is JsonObject) continue
                val images = e["img"].arr.mapNotNull { i ->
                    val a = i as? JsonArray ?: return@mapNotNull null
                    if (a.size != 3) return@mapNotNull null
                    val u = (a[0] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                    val w = ListenJson.integerOf(a[1]) ?: return@mapNotNull null
                    val h = ListenJson.integerOf(a[2]) ?: return@mapNotNull null
                    Image(u, w.toInt(), h.toInt())
                }
                m[k] = Art(images, e.str("id"), e.str("aid"), e.long("at"), e.bool("f"))
            }
            return m
        }
    }
}
