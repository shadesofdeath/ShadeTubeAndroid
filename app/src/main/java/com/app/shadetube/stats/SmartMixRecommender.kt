package com.app.shadetube.stats

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.Log
import com.app.shadetube.core.appScope
import com.app.shadetube.musicbrainz.MusicBrainz
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Spotify's song radios for recommendations (the logged-in session; AppGraph wires it). */
interface SpotifyRadioSource {
    /** The radio playlist URI of a song ("" when there is none). Throws [RateLimitedException] on HTTP 429. */
    suspend fun radioPlaylist(seedTrackUri: String): String

    /** A page of a playlist's tracks. */
    suspend fun playlistTracks(uri: String, offset: Int, limit: Int): List<Track>
}

/** Thrown by [SpotifyRadioSource] implementations for HTTP 429 (radios pause for a while). */
class RateLimitedException(message: String = "rate limited") : Exception(message)

/** Songs of the local library (local files), for recommendations by the collection's artists. */
fun interface LocalTracksSource {
    fun tracks(): List<Track>
}

/** Similar artists' popular songs (ListenBrainz); an interface so tests run without network. */
interface SimilarArtistsSource {
    suspend fun similarArtistIds(artistMbid: String, limit: Int): List<String>
    suspend fun topTracks(artistMbid: String, limit: Int): List<Track>

    object ListenBrainz : SimilarArtistsSource {
        override suspend fun similarArtistIds(artistMbid: String, limit: Int) =
            MusicBrainz.similarArtists(artistMbid, limit).map { it.id }

        override suspend fun topTracks(artistMbid: String, limit: Int) = MusicBrainz.topTracksForArtist(artistMbid, limit)
    }
}

/**
 * Recommendations for a collection (the recommendation side of app/SmartShuffle: `recommend()`): a sample of its
 * songs as seeds, fetched on a worker and cached per collection for 30 minutes: Spotify's song radios of up to 3 of
 * its Spotify songs (logged in), topped up with the popular recordings of ListenBrainz's similar artists for its most
 * frequent MusicBrainz artists, then with local-library songs by its artists. A Spotify 429 pauses fetching for 10
 * minutes. Recommended tracks are ordinary [Track]s with `recommended` set.
 *
 * Thread-safe. The player / UI wiring (smart shuffle's queue top-up, the "Geliştir" pages) lives with playback.
 *
 * @param spotify the logged-in session's radios, null while logged out.
 * @param blocked the blocklist (the playback package's); radio stations and podcast episodes are never recommended.
 */
class SmartMixRecommender(
    private val spotify: () -> SpotifyRadioSource?,
    private val local: LocalTracksSource = LocalTracksSource { emptyList() },
    private val blocked: (Track) -> Boolean = { false },
    private val similar: SimilarArtistsSource = SimilarArtistsSource.ListenBrainz,
    private val scope: CoroutineScope = appScope,
    private val steadyMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Pool {
        var tracks: List<Track> = emptyList()
        var fetchedAt = 0L
        var loading: Deferred<Unit>? = null
    }

    class Seeds(
        /** spotify:track: URIs (song radios). */
        val spotify: List<String>,
        /** MusicBrainz artist ids (similar artists). */
        val artists: List<String>,
        /** Folded first artists (local library). */
        val artistNames: List<String>,
    )

    private class Fetched(val tracks: List<Track>, val rateLimited: Boolean)

    private val lock = Any()
    private val pools = HashMap<String, Pool>()
    private val hidden = HashMap<String, SmartMix.Exclusions>() // key -> "−" (and added) songs, this session
    private var backoffUntil = 0L

    private val versionFlow = MutableStateFlow(0L)

    /** Bumped when a song is hidden (the collection pages follow it). */
    val version: StateFlow<Long> = versionFlow.asStateFlow()

    /**
     * Recommendations for the collection [key] ("liked" or a playlist id) whose songs are [collection]: not in it,
     * not hidden for [key], unblocked and playable, in a stable order for the key. Empty = nothing found (or the
     * fetch failed). Cached for [POOL_TTL_MS].
     */
    suspend fun recommend(key: String, collection: List<Track>): List<Track> {
        val job: Deferred<Unit>? = synchronized(lock) {
            val pool = pools.getOrPut(key) { Pool() }
            val now = steadyMs()
            val fresh = pool.fetchedAt > 0 && now - pool.fetchedAt < POOL_TTL_MS
            if (fresh && pool.loading == null) null
            else pool.loading ?: run {
                val seeds = seedsOf(collection, SmartMix.hash(key))
                val api = if (now < backoffUntil) null else spotify()
                scope.async {
                    val f = try {
                        fetchPool(api, seeds)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Fetched(emptyList(), false)
                    }
                    val tracks = ArrayList(f.tracks)
                    tracks.addAll(localByArtists(seeds.artistNames))
                    Log.i("smartshuffle", "${tracks.size} recommendation candidate(s) for $key")
                    synchronized(lock) {
                        if (f.rateLimited) backoffUntil = steadyMs() + BACKOFF_MS
                        val p = pools.getOrPut(key) { Pool() }
                        p.tracks = tracks
                        p.fetchedAt = steadyMs()
                        p.loading = null
                        prunePools()
                    }
                }.also { pool.loading = it }
            }
        }
        job?.await()
        return deliver(key, collection)
    }

    private fun deliver(key: String, collection: List<Track>): List<Track> {
        val exclude = SmartMix.Exclusions()
        for (t in collection) if (!t.recommended) exclude.add(t)
        val pool: List<Track>
        synchronized(lock) {
            hidden[key]?.let { exclude.addAll(it) }
            pool = pools[key]?.tracks ?: emptyList()
        }
        return SmartMix.candidates(pool, exclude, { blocked(it) || notASong(it) }, SmartMix.hash(key))
    }

    /** "−": not recommended again this session. */
    fun hide(key: String, t: Track) {
        synchronized(lock) { hidden.getOrPut(key) { SmartMix.Exclusions() }.add(t) }
        versionFlow.value = versionFlow.value + 1
    }

    fun isHidden(key: String, t: Track): Boolean = synchronized(lock) { hidden[key]?.contains(t) == true }

    private fun prunePools() {
        while (pools.size > MAX_POOLS) {
            val oldest = pools.entries.filter { it.value.loading == null }.minByOrNull { it.value.fetchedAt } ?: return
            pools.remove(oldest.key)
        }
    }

    /** Fetching outside the cache (worker). */
    private suspend fun fetchPool(api: SpotifyRadioSource?, seeds: Seeds): Fetched {
        val tracks = ArrayList<Track>()
        var rateLimited = false
        if (api != null) {
            for (seed in seeds.spotify) {
                try {
                    val radio = api.radioPlaylist(seed)
                    if (radio.isEmpty()) continue
                    tracks.addAll(api.playlistTracks(radio, 0, RADIO_TRACKS))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RateLimitedException) {
                    rateLimited = true
                    break
                } catch (e: Exception) {
                    Log.w("smartshuffle", "song radio of $seed failed: ${e.message}")
                }
            }
        }
        if (tracks.size < MIN_POOL) {
            for (artist in seeds.artists) {
                try {
                    var n = 0
                    for (s in similar.similarArtistIds(artist, 8)) {
                        if (n++ >= SIMILAR_PER_ARTIST) break
                        tracks.addAll(similar.topTracks(s, TOP_PER_ARTIST))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("smartshuffle", "similar artists of $artist failed: ${e.message}")
                }
            }
        }
        return Fetched(tracks, rateLimited)
    }

    /** Songs of the local library by the collection's artists. */
    private fun localByArtists(foldedNames: List<String>): List<Track> {
        if (foldedNames.isEmpty()) return emptyList()
        val names = foldedNames.toHashSet()
        val out = ArrayList<Track>()
        for (t in runCatching { local.tracks() }.getOrDefault(emptyList())) {
            if (out.size >= LOCAL_EXTRA) break
            if (t.artists.isNotEmpty() && SmartMix.foldName(t.artists[0].name) in names) out.add(t)
        }
        return out
    }

    companion object {
        /** A collection's recommendations are fetched again after this. */
        const val POOL_TTL_MS = 30 * 60 * 1000L
        /** Spotify said 429: no radios for a while. */
        const val BACKOFF_MS = 10 * 60 * 1000L
        const val MAX_POOLS = 12
        /** Song radios per collection. */
        const val SPOTIFY_SEEDS = 3
        const val RADIO_TRACKS = 50
        /** Most frequent MusicBrainz artists of the collection. */
        const val MB_ARTISTS = 2
        const val SIMILAR_PER_ARTIST = 3
        const val TOP_PER_ARTIST = 4
        /** Local-library songs by the collection's artists. */
        const val LOCAL_EXTRA = 30
        /** Fewer from Spotify: ask ListenBrainz too. */
        const val MIN_POOL = 20

        private fun isMbid(id: String) = id.length == 36 && id[8] == '-' && ':' !in id

        fun notASong(t: Track) = ListenStatsService.isStationId(t.id) || isPodcastId(t.id)

        /** The seeds of a collection (spread over the list, starting somewhere that depends on the collection). */
        fun seedsOf(collection: List<Track>, seed: Long): Seeds {
            val spotify = ArrayList<Track>()
            val artistCount = HashMap<String, Int>()
            val names = LinkedHashSet<String>()
            for (t in collection) {
                if (t.recommended || notASong(t)) continue
                if (t.id.startsWith("spotify:track:")) spotify.add(t)
                if (t.artists.isEmpty()) continue
                if (isMbid(t.artists[0].id)) artistCount.merge(t.artists[0].id, 1, Int::plus)
                val n = SmartMix.foldName(t.artists[0].name)
                if (n.isNotEmpty() && names.size < 40) names.add(n)
            }
            val seeds = ArrayList<String>()
            if (spotify.isNotEmpty()) {
                val n = minOf(SPOTIFY_SEEDS, spotify.size)
                val start = java.lang.Long.remainderUnsigned(seed, spotify.size.toLong()).toInt()
                for (k in 0 until n) seeds.add(spotify[(start + k * spotify.size / n) % spotify.size].id)
            }
            val ranked = artistCount.entries.sortedWith { a, b ->
                if (a.value != b.value) b.value.compareTo(a.value) else a.key.compareTo(b.key)
            }
            return Seeds(seeds, ranked.take(MB_ARTISTS).map { it.key }, names.toList())
        }
    }
}
