package com.app.shadetube.musicbrainz

import com.app.shadetube.core.parseStrictJson
import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistPage
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.coverArt
import com.app.shadetube.core.Http
import com.app.shadetube.core.Log
import com.app.shadetube.core.dump
import com.app.shadetube.core.jsonArrayOf
import com.app.shadetube.core.jsonOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Open music catalog client (port of musicbrainz/MusicBrainz): MusicBrainz (metadata), Cover Art Archive (covers),
 * ListenBrainz (popularity, similar artists, fresh releases) and Wikidata / Wikimedia Commons (artist photos). All
 * public APIs, no keys. Rate limits and the disk cache are enforced by [MbNet] (MusicBrainz 1 request / second,
 * lookups cached 7 days, searches 1 day, ListenBrainz 12 hours, Wikidata 30 days).
 *
 * Every function is `suspend` and main-safe; it throws [MbApiError] (HTTP status), IOException (transport) or
 * CancellationException.
 */
object MusicBrainz {
    private const val MB = "https://musicbrainz.org/ws/2/"
    private const val LB = "https://api.listenbrainz.org/1/"
    private const val LB_LABS = "https://labs.api.listenbrainz.org/"
    private const val SIMILAR_ALGORITHM =
        "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"

    /** Set after a 401 / 403 of top-recordings-for-artist without a token: go straight to the fallback after that. */
    private val topRecordingsNeedsAuth = AtomicBoolean(false)

    private fun mbUrl(pathAndQuery: String) = MB + pathAndQuery + (if ('?' in pathAndQuery) "&" else "?") + "fmt=json"

    private suspend fun mbGet(pathAndQuery: String, ttlSec: Long): JsonElement =
        MbNet.fetch(MbNet.Request(MbNet.Service.MusicBrainz, mbUrl(pathAndQuery), ttlSec = ttlSec))!!

    private suspend fun mbCached(pathAndQuery: String, ttlSec: Long): JsonElement? =
        MbNet.fetch(MbNet.Request(MbNet.Service.MusicBrainz, mbUrl(pathAndQuery), ttlSec = ttlSec, cacheOnly = true))

    private suspend fun lbGet(url: String, sendToken: Boolean = false): JsonElement =
        MbNet.fetch(MbNet.Request(MbNet.Service.ListenBrainz, url, ttlSec = MbNet.Ttl.LISTENBRAINZ, sendToken = sendToken))!!

    private suspend fun lbPost(url: String, body: JsonElement): JsonElement =
        MbNet.fetch(
            MbNet.Request(MbNet.Service.ListenBrainz, url, method = "POST", body = body.dump(), ttlSec = MbNet.Ttl.LISTENBRAINZ),
        )!!

    private fun enc(s: String) = Http.urlEncode(s)

    private fun artistLookupPath(id: String) = "artist/$id?inc=tags+url-rels"

    /** Runs [block]; failures other than cancellation are logged and give null. */
    private suspend inline fun <T> bestEffort(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Log.w("mb", "$what failed: ${e.message}")
        null
    }

    /** Commons photo of an artist lookup (url-rels): a direct "image" rel, else Wikidata P18. Best effort. */
    private suspend fun artistImages(lookup: JsonElement, cacheOnly: Boolean): List<Image> {
        var file = MbParse.commonsFileFromRels(lookup)
        if (file.isEmpty()) {
            val q = MbParse.wikidataId(lookup)
            if (q.isEmpty()) return emptyList()
            val key = "wikidata-p18 $q"
            val cached = MbNet.cacheGet(key, MbNet.Ttl.WIKIDATA)
            if (cached != null) {
                file = MbParse.str(parseStrictJson(cached), "file")
            } else {
                if (cacheOnly) return emptyList()
                file = bestEffort("wikidata $q") {
                    val entity = MbNet.fetch(
                        MbNet.Request(
                            MbNet.Service.Wikidata,
                            "https://www.wikidata.org/wiki/Special:EntityData/$q.json",
                            ttlSec = MbNet.Ttl.WIKIDATA,
                            store = false, // entities are ~100 KB; only the file name is cached
                        ),
                    )
                    MbParse.wikidataImageFile(entity).also { MbNet.cachePut(key, jsonOf("file" to it).dump()) }
                } ?: return emptyList()
            }
        }
        return MbParse.commonsImages(file)
    }

    /** Fills photos (and tags / country) only from what is already cached (no network): for shelves of many artists. */
    private suspend fun fillCachedImages(artists: List<Artist>): List<Artist> = artists.map { a ->
        if (a.images.isNotEmpty() || a.id.isEmpty()) return@map a
        try {
            val lookup = mbCached(artistLookupPath(a.id), MbNet.Ttl.LOOKUP) ?: return@map a
            a.copy(
                images = artistImages(lookup, cacheOnly = true),
                tags = a.tags.ifEmpty { MbParse.parseTags(lookup) },
                country = a.country.ifEmpty { MbParse.str(lookup, "country") },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            a
        }
    }

    private suspend fun topTracksFromMusicBrainz(artistId: String, limit: Int): List<Track> {
        // Candidate set: the artist's official recordings (search returns releases + release groups per hit).
        val hits = ArrayList<MbParse.RecordingHit>()
        val q = enc("arid:$artistId AND status:official AND video:false")
        val page = 100
        val maxPages = 3
        for (p in 0 until maxPages) {
            val root = mbGet("recording?query=$q&limit=$page&offset=${p * page}", MbNet.Ttl.LOOKUP)
            val more = MbParse.parseRecordingHits(root)
            val last = more.size < page || MbParse.num(root, "count") <= (p + 1L) * page
            hits += more
            if (last) break
        }
        // Popularity for all candidates in bulk (public, no token).
        val listens = HashMap<String, Long>()
        bestEffort("popularity lookup") {
            val chunk = 250
            for (i in hits.indices step chunk) {
                val ids = JsonArray(hits.subList(i, minOf(hits.size, i + chunk)).map { JsonPrimitive(it.track.id) })
                listens += MbParse.parsePopularity(lbPost(LB + "popularity/recording", jsonOf("recording_mbids" to ids)))
            }
        }
        return MbParse.rankTopTracks(hits, listens, limit)
    }

    private suspend fun similarByTags(artistId: String, limit: Int): List<Artist> {
        val self = artist(artistId)
        if (self.tags.isEmpty()) return emptyList()
        var q = self.tags.take(2).joinToString(" AND ") { "tag:\"" + MbParse.luceneEscape(it) + "\"" }
        if (self.country.isNotEmpty()) q += " AND country:" + self.country
        q += " AND NOT arid:$artistId"
        val out = MbParse.parseArtistSearch(
            mbGet("artist?query=${enc(q)}&limit=${limit + 1}", MbNet.Ttl.SEARCH), limit + 1,
        ).filter { it.id != artistId }
        return out.take(maxOf(0, limit))
    }

    /**
     * Optional ListenBrainz user token (https://listenbrainz.org/settings/). Since 2026 LB requires a token for
     * /1/popularity/top-recordings-for-artist; without one [topTracksForArtist] ranks the artist's MusicBrainz
     * recordings by LB's public bulk popularity endpoint instead.
     */
    fun setListenBrainzToken(token: String) {
        MbNet.setListenBrainzToken(token)
        topRecordingsNeedsAuth.set(false)
    }

    // ---- search ---------------------------------------------------------------------------------------------------
    /** Lucene query built from free text (a query with field prefixes such as `tag:"anadolu rock"` passes through). */
    suspend fun searchArtists(query: String, limit: Int = 20): List<Artist> {
        val q = MbParse.artistQuery(query)
        if (q.isEmpty() || limit <= 0) return emptyList()
        val n = minOf(limit, 100)
        return MbParse.parseArtistSearch(mbGet("artist?query=${enc(q)}&limit=$n", MbNet.Ttl.SEARCH), n)
    }

    /** Albums = release groups (official Album / EP / Single for free text). */
    suspend fun searchAlbums(query: String, limit: Int = 20): List<Album> {
        val q = MbParse.releaseGroupQuery(query)
        if (q.isEmpty() || limit <= 0) return emptyList()
        val freeText = !MbParse.hasFieldPrefix(query)
        val fetch = minOf(100, limit * 2)
        return MbParse.parseReleaseGroupSearch(
            mbGet("release-group?query=${enc(q)}&limit=$fetch", MbNet.Ttl.SEARCH), freeText, limit,
        )
    }

    suspend fun searchTracks(query: String, limit: Int = 30): List<Track> {
        val q = MbParse.recordingQuery(query)
        if (q.isEmpty() || limit <= 0) return emptyList()
        val freeText = !MbParse.hasFieldPrefix(query)
        val fetch = minOf(100, limit * 3)
        val hits = MbParse.parseRecordingHits(mbGet("recording?query=${enc(q)}&limit=$fetch", MbNet.Ttl.SEARCH))
        return MbParse.rankRecordingSearch(hits, query, freeText, limit)
    }

    suspend fun search(query: String, limit: Int = 12): SearchResults = SearchResults(
        artists = searchArtists(query, maxOf(1, limit / 2)),
        albums = searchAlbums(query, limit),
        tracks = searchTracks(query, limit),
    )

    // ---- lookups --------------------------------------------------------------------------------------------------
    /** Artist + tags + Commons image. */
    suspend fun artist(artistId: String): Artist {
        val lookup = mbGet(artistLookupPath(artistId), MbNet.Ttl.LOOKUP)
        val a = MbParse.parseArtist(lookup)
        return a.copy(id = a.id.ifEmpty { artistId }, images = artistImages(lookup, cacheOnly = false))
    }

    /** All release groups of an artist (album / single / EP, paged internally, at most 1000), newest first. */
    suspend fun artistReleaseGroups(artistId: String): List<Album> {
        val all = ArrayList<Album>()
        val page = 100
        val maxPages = 10
        for (p in 0 until maxPages) {
            val root = mbGet(
                "release-group?artist=$artistId&type=album%7Csingle%7Cep&inc=artist-credits&limit=$page&offset=${p * page}",
                MbNet.Ttl.LOOKUP,
            )
            val items = MbParse.parseReleaseGroupBrowse(root)
            val total = MbParse.num(root, "release-group-count")
            val last = items.size < page || total <= (p + 1L) * page
            all += items
            if (last) break
        }
        return MbParse.sortNewestFirst(all)
    }

    /**
     * Release group + tracklist of its most representative official release (prefers the earliest official release
     * in "XW" / "TR" / any, CD / Digital over vinyl, fewest bonus tracks).
     */
    suspend fun album(releaseGroupId: String): Album {
        val rg = mbGet("release-group/$releaseGroupId?inc=artist-credits+releases+tags+media", MbNet.Ttl.LOOKUP)
        var a = MbParse.parseReleaseGroup(rg)
        if (a.id.isEmpty()) a = a.copy(id = releaseGroupId, images = coverArt(releaseGroupId))
        val releaseId = MbParse.pickRepresentativeRelease(rg)
        if (releaseId.isEmpty()) return a
        val release = mbGet("release/$releaseId?inc=recordings+artist-credits+labels+media", MbNet.Ttl.LOOKUP)
        return MbParse.applyRelease(a, release)
    }

    /** The release group of one release (a pasted musicbrainz.org/release/ link). "" when MusicBrainz names none. */
    suspend fun releaseGroupOfRelease(releaseId: String): String {
        val release = mbGet("release/$releaseId?inc=release-groups", MbNet.Ttl.LOOKUP)
        return MbParse.str(MbParse.get(release, "release-group"), "id")
    }

    /**
     * One recording as a Track: title, artist credit, length and its best (official, album first) release group as
     * the album. Throws [MbApiError] (404 for an unknown MBID).
     */
    suspend fun recording(recordingId: String): Track {
        val rec = mbGet("recording/$recordingId?inc=artist-credits+releases+release-groups", MbNet.Ttl.LOOKUP)
        // A lookup is one search hit without the score: the same parser picks the album.
        val hits = MbParse.parseRecordingHits(jsonOf("recordings" to jsonArrayOf(rec)))
        if (hits.isEmpty()) throw MbApiError(404, "MusicBrainz: recording $recordingId not found")
        return hits.first().track
    }

    // ---- ListenBrainz ---------------------------------------------------------------------------------------------
    suspend fun topTracksForArtist(artistId: String, limit: Int = 10): List<Track> {
        if (!topRecordingsNeedsAuth.get() || MbNet.hasListenBrainzToken()) {
            try {
                val tracks = MbParse.parseLbRecordings(
                    lbGet(LB + "popularity/top-recordings-for-artist/" + artistId, sendToken = true), limit,
                )
                if (tracks.isNotEmpty()) return tracks
            } catch (e: MbApiError) {
                if (e.status == 401 || e.status == 403) {
                    if (!MbNet.hasListenBrainzToken()) topRecordingsNeedsAuth.set(true)
                } else if (e.status != 404) {
                    Log.w("mb", "top-recordings-for-artist: ${e.message}")
                }
            }
        }
        return topTracksFromMusicBrainz(artistId, limit)
    }

    /** ListenBrainz labs similar artists; falls back to MusicBrainz tag overlap. Photos only from the cache. */
    suspend fun similarArtists(artistId: String, limit: Int = 12): List<Artist> {
        val labs = bestEffort("labs similar-artists (falling back to tag overlap)") {
            MbParse.parseSimilarArtists(
                lbGet("${LB_LABS}similar-artists/json?artist_mbids=$artistId&algorithm=$SIMILAR_ALGORITHM"),
                artistId, limit,
            )
        }
        if (!labs.isNullOrEmpty()) return fillCachedImages(labs)
        return fillCachedImages(similarByTags(artistId, limit))
    }

    /** range: "week" | "month" | "year" | "all_time" ... */
    suspend fun trendingArtists(range: String = "week", limit: Int = 20): List<Artist> =
        fillCachedImages(
            MbParse.parseLbArtists(
                lbGet("${LB}stats/sitewide/artists?range=${enc(range)}&count=${limit.coerceIn(1, 100)}"), limit,
            ),
        )

    suspend fun trendingAlbums(range: String = "week", limit: Int = 20): List<Album> =
        MbParse.parseLbReleaseGroups(
            lbGet("${LB}stats/sitewide/release-groups?range=${enc(range)}&count=${(limit * 2).coerceIn(1, 100)}"), limit,
        )

    suspend fun trendingTracks(range: String = "week", limit: Int = 30): List<Track> =
        MbParse.parseLbRecordings(
            lbGet("${LB}stats/sitewide/recordings?range=${enc(range)}&count=${(limit * 2).coerceIn(1, 100)}"), limit,
        )

    suspend fun freshReleases(days: Int = 14, limit: Int = 30): List<Album> =
        MbParse.parseFreshReleases(
            lbGet("${LB}explore/fresh-releases/?days=${days.coerceIn(1, 90)}&past=true&future=false&sort=release_date"),
            limit,
        )

    // ---- composite ------------------------------------------------------------------------------------------------
    /** Artist, release groups split into albums / singles+EPs / other, top tracks and similar artists (optional). */
    suspend fun artistPage(artistId: String): ArtistPage {
        val artist = artist(artistId)
        val albums = ArrayList<Album>()
        val singles = ArrayList<Album>()
        val other = ArrayList<Album>()
        for (rg in artistReleaseGroups(artistId)) {
            val pt = rg.primaryType
            when {
                MbParse.isOtherRelease(rg) -> other += rg
                pt == "Album" -> albums += rg
                pt == "Single" || pt == "EP" -> singles += rg
                else -> other += rg
            }
        }
        val top = bestEffort("artistPage $artistId: top tracks") { topTracksForArtist(artistId, 10) } ?: emptyList()
        val similar = bestEffort("artistPage $artistId: similar artists") { similarArtists(artistId, 12) } ?: emptyList()
        return ArtistPage(artist = artist, topTracks = top, albums = albums, singles = singles, other = other, similar = similar)
    }
}
