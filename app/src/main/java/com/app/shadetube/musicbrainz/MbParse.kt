package com.app.shadetube.musicbrainz

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.coverArt
import com.app.shadetube.core.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.log2

/**
 * Pure JSON -> catalog model mapping and Lucene query building for MusicBrainz / ListenBrainz / Wikidata (port of
 * musicbrainz/Parse, the C++ `mb::detail` namespace). No I/O; exposed for offline tests.
 *
 * Every accessor tolerates missing keys / wrong types / nulls, with nlohmann's strictness: [str] is "" unless the
 * value really is a string (a number is not stringified), [num] accepts integers, floats and numeric strings.
 */
object MbParse {
    // ---- safe accessors -------------------------------------------------------------------------------------------
    private val EMPTY = JsonArray(emptyList())

    /** Child of an object, null if missing / not an object. */
    fun get(j: JsonElement?, key: String): JsonElement? = (j as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }

    /** "" unless a string. */
    fun str(j: JsonElement?, key: String): String = strOf(get(j, key))

    private fun strOf(v: JsonElement?): String = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    fun num(j: JsonElement?, key: String, def: Long = 0): Long {
        val v = get(j, key) as? JsonPrimitive ?: return def
        if (v.isString) return stoll(v.content) ?: def
        v.content.toLongOrNull()?.let { return it }
        return v.content.toDoubleOrNull()?.toLong() ?: def
    }

    /** std::stoll: optional leading whitespace and sign, then at least one digit; the rest is ignored. */
    private fun stoll(s: String): Long? {
        var i = 0
        while (i < s.length && s[i].isAsciiSpace()) i++
        val start = i
        if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
        val digits = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == digits) return null
        return s.substring(start, i).toLongOrNull()
    }

    /** Empty array unless an array. */
    fun arr(j: JsonElement?, key: String): JsonArray = get(j, key) as? JsonArray ?: EMPTY

    // ---- small string helpers (ASCII semantics like the C++) ------------------------------------------------------
    private fun Char.isAsciiSpace() = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000B' || this == '\u000C'

    internal fun asciiLower(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
        return sb.toString()
    }

    internal fun trim(s: String): String {
        var b = 0
        var e = s.length
        while (b < e && s[b].isAsciiSpace()) b++
        while (e > b && s[e - 1].isAsciiSpace()) e--
        return s.substring(b, e)
    }

    private fun isAsciiAlpha(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** Versions that usually aren't what someone means when they search for / play "the song". */
    private val ALTERNATE_MARKERS = listOf(
        "remix", " mix)", " mix]", "karaoke", "instrumental", "live", "acoustic", "akustik",
        "cover", "version)", "edit)", "remaster",
    )

    private fun isAlternateVersion(titleLower: String) = ALTERNATE_MARKERS.any { titleLower.contains(it) }

    /** %XX decoding over the UTF-8 bytes. */
    private fun percentDecode(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        fun hex(b: Byte): Int = Character.digit(b.toInt().toChar(), 16)
        while (i < bytes.size) {
            if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size && hex(bytes[i + 1]) >= 0 && hex(bytes[i + 2]) >= 0) {
                out.write(hex(bytes[i + 1]) * 16 + hex(bytes[i + 2]))
                i += 3
            } else {
                out.write(bytes[i].toInt())
                i++
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun strArray(a: JsonElement?): List<String> =
        (a as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    private fun isExcludedSecondary(t: String): Boolean {
        val l = asciiLower(t)
        return l == "compilation" || l == "live" || l == "remix" || l == "dj-mix" || l == "demo"
    }

    /** LB rows credit artists as {artist_mbids: [...], artist_name: "A feat. B"}. */
    private fun lbArtists(row: JsonElement?, nameKey: String): List<ArtistRef> {
        val mbids = strArray(get(row, "artist_mbids"))
        var name = str(row, nameKey)
        if (name.isEmpty()) name = str(row, "artist_name")
        val id = if (mbids.isEmpty()) str(row, "artist_mbid") else mbids.first()
        return if (name.isNotEmpty() || id.isNotEmpty()) listOf(ArtistRef(id, name)) else emptyList()
    }

    private fun lbPayloadList(root: JsonElement?, key: String): JsonArray {
        if (root is JsonArray) return root
        val p = get(root, "payload")
        return get(p ?: root, key) as? JsonArray ?: EMPTY
    }

    private fun primaryRank(rg: JsonElement?): Int {
        val pt = asciiLower(str(rg, "primary-type"))
        val clean = arr(rg, "secondary-types").isEmpty()
        if (pt == "album" && clean) return 0
        if ((pt == "single" || pt == "ep") && clean) return 1
        if (pt == "album") return 2
        return 3
    }

    // ---- Lucene queries -------------------------------------------------------------------------------------------
    private val FIELDS = setOf(
        "tag", "artist", "artistname", "arid", "alias", "country", "releasegroup", "release", "recording",
        "rgid", "reid", "rid", "primarytype", "secondarytype", "type", "status", "date", "firstreleasedate",
        "creditname", "isrc", "dur", "qdur", "tnum", "tracks", "gender", "area", "begin", "end", "label",
        "format", "comment", "sortname", "ipi", "isni", "lang", "script", "video", "position", "barcode",
        "catno", "tid", "mbid", "releaseaccent", "recordingaccent", "artistaccent", "releasegroupaccent",
    )

    /** "tag:rock", "artist:\"x\"" ... -> the query is passed through as Lucene. "Re:Cognition" is not a field. */
    fun hasFieldPrefix(q: String): Boolean {
        for (i in q.indices) {
            val boundary = i == 0 || q[i - 1].isAsciiSpace() || q[i - 1] == '(' || q[i - 1] == '+' ||
                q[i - 1] == '-' || q[i - 1] == '!'
            if (!boundary || !isAsciiAlpha(q[i])) continue
            var j = i
            while (j < q.length && (isAsciiAlpha(q[j]) || q[j] == '-' || q[j] == '_')) j++
            if (j < q.length && q[j] == ':' && asciiLower(q.substring(i, j)) in FIELDS) return true
        }
        return false
    }

    fun luceneEscape(text: String): String {
        val special = "+-&|!(){}[]^\"~*?:\\/"
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            if (special.indexOf(c) >= 0) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /** Lowercase ASCII so user-typed AND/OR/NOT are plain words, then escape. */
    private fun prepared(q: String) = luceneEscape(asciiLower(trim(q)))

    fun artistQuery(q: String): String {
        if (hasFieldPrefix(q)) return q
        val e = prepared(q)
        if (e.isEmpty()) return ""
        return "artist:\"$e\"^3 OR artist:($e) OR alias:($e)"
    }

    fun releaseGroupQuery(q: String): String {
        if (hasFieldPrefix(q)) return q
        val e = prepared(q)
        if (e.isEmpty()) return ""
        return "(releasegroup:($e) OR artist:($e)) AND status:official AND (primarytype:album OR primarytype:ep OR primarytype:single)"
    }

    fun recordingQuery(q: String): String {
        if (hasFieldPrefix(q)) return q
        val e = prepared(q)
        if (e.isEmpty()) return ""
        return "(recording:($e) OR artist:($e)) AND video:false"
    }

    // ---- MusicBrainz entities -------------------------------------------------------------------------------------
    fun parseArtistCredit(credit: JsonElement?): List<ArtistRef> {
        if (credit !is JsonArray) return emptyList()
        val out = ArrayList<ArtistRef>()
        for (c in credit) {
            val a = get(c, "artist")
            var name = str(c, "name")
            if (name.isEmpty()) name = str(a, "name")
            val id = str(a, "id")
            if (name.isNotEmpty() || id.isNotEmpty()) out += ArtistRef(id, name)
        }
        return out
    }

    /** Tag names sorted by vote count, highest first. */
    fun parseTags(entity: JsonElement?, max: Int = 8): List<String> {
        val tags = ArrayList<Pair<Long, String>>()
        for (t in arr(entity, "tags")) {
            val name = str(t, "name")
            val count = num(t, "count")
            if (name.isNotEmpty() && count >= 0) tags += count to name
        }
        return tags.sortedByDescending { it.first }.take(max).map { it.second }
    }

    /** A search hit or a lookup. */
    fun parseArtist(a: JsonElement?): Artist {
        var country = str(a, "country")
        if (country.isEmpty()) strArray(get(get(a, "area"), "iso-3166-1-codes")).firstOrNull()?.let { country = it }
        return Artist(
            id = str(a, "id"),
            name = str(a, "name"),
            type = str(a, "type"),
            country = country,
            disambiguation = str(a, "disambiguation"),
            beginYear = str(get(a, "life-span"), "begin").take(4),
            tags = parseTags(a),
        )
    }

    fun parseArtistSearch(root: JsonElement?, limit: Int): List<Artist> {
        val out = ArrayList<Artist>()
        for (a in arr(root, "artists")) {
            if (out.size >= limit) break
            val artist = parseArtist(a)
            if (artist.id.isNotEmpty() && artist.name.isNotEmpty()) out += artist
        }
        return out
    }

    /** "Q485771" from the url-rels of an artist lookup, or "". */
    fun wikidataId(artistLookup: JsonElement?): String {
        for (rel in arr(artistLookup, "relations")) {
            if (asciiLower(str(rel, "type")) != "wikidata") continue
            val url = str(get(rel, "url"), "resource")
            val id = url.substring(url.lastIndexOf('/') + 1)
            if (id.length > 1 && (id[0] == 'Q' || id[0] == 'q')) return id
        }
        return ""
    }

    /** The file name of an "image" rel pointing at Wikimedia Commons, or "". */
    fun commonsFileFromRels(artistLookup: JsonElement?): String {
        for (rel in arr(artistLookup, "relations")) {
            if (asciiLower(str(rel, "type")) != "image") continue
            val url = str(get(rel, "url"), "resource")
            if (!url.contains("commons.wikimedia.org")) continue
            val pos = url.indexOf("File:")
            if (pos >= 0) return percentDecode(url.substring(pos + 5))
        }
        return ""
    }

    /** Claim P18 (image) of a Wikidata entity: the preferred one, else the first normal one; "" if none. */
    fun wikidataImageFile(entityData: JsonElement?): String {
        val entities = get(entityData, "entities") as? JsonObject ?: return ""
        if (entities.isEmpty()) return ""
        val claims = arr(get(entities.values.first(), "claims"), "P18")
        var normal = ""
        for (c in claims) {
            val rank = str(c, "rank")
            if (rank == "deprecated") continue
            val value = get(get(get(c, "mainsnak"), "datavalue"), "value") as? JsonPrimitive ?: continue
            if (!value.isString) continue
            if (rank == "preferred") return value.content
            if (normal.isEmpty()) normal = value.content
        }
        return normal
    }

    /** Special:FilePath thumbnails 250 / 500 / 1000 px wide (height unknown). */
    fun commonsImages(fileName: String): List<Image> {
        if (fileName.isEmpty()) return emptyList()
        val base = "https://commons.wikimedia.org/wiki/Special:FilePath/" + Http.urlEncode(fileName.replace(' ', '_')) + "?width="
        return listOf(Image(base + "250", 250, 0), Image(base + "500", 500, 0), Image(base + "1000", 1000, 0))
    }

    /** A release group search hit / browse row / lookup. */
    fun parseReleaseGroup(rg: JsonElement?): Album {
        val id = str(rg, "id")
        return Album(
            id = id,
            name = str(rg, "title"),
            primaryType = str(rg, "primary-type"),
            secondaryTypes = strArray(get(rg, "secondary-types")),
            artists = parseArtistCredit(get(rg, "artist-credit")),
            firstReleaseDate = str(rg, "first-release-date"),
            images = coverArt(id),
        )
    }

    /**
     * Release group search ranked by score + popularity (log of the release count), clean albums before ones with
     * secondary types. Free text keeps only Album / EP / Single.
     */
    fun parseReleaseGroupSearch(root: JsonElement?, freeText: Boolean, limit: Int): List<Album> {
        val ranked = ArrayList<Pair<Album, Double>>()
        for (rg in arr(root, "release-groups")) {
            val a = parseReleaseGroup(rg)
            if (a.id.isEmpty()) continue
            val pt = asciiLower(a.primaryType)
            if (freeText && pt != "album" && pt != "ep" && pt != "single") continue
            var rank = num(rg, "score").toDouble()
            rank += 6.0 * log2(1.0 + num(rg, "count").toDouble())
            if (a.secondaryTypes.isNotEmpty()) rank -= 8.0
            ranked += a to rank
        }
        return ranked.sortedByDescending { it.second }.take(maxOf(0, limit)).map { it.first }
    }

    fun parseReleaseGroupBrowse(root: JsonElement?): List<Album> =
        arr(root, "release-groups").map { parseReleaseGroup(it) }.filter { it.id.isNotEmpty() }

    /** Secondary type Compilation / Live / Remix / DJ-mix / Demo: shown under "other" on the artist page. */
    fun isOtherRelease(a: Album): Boolean = a.secondaryTypes.any { isExcludedSecondary(it) }

    /** Newest first; undated last (ISO dates compare lexicographically). Stable. */
    fun sortNewestFirst(albums: List<Album>): List<Album> = albums.sortedWith { x, y ->
        when {
            x.firstReleaseDate.isEmpty() != y.firstReleaseDate.isEmpty() -> if (y.firstReleaseDate.isEmpty()) -1 else 1
            else -> y.firstReleaseDate.compareTo(x.firstReleaseDate)
        }
    }

    // ---- recordings -----------------------------------------------------------------------------------------------
    data class RecordingHit(
        val track: Track,
        val releaseCount: Int = 0,
        val official: Boolean = false,
        /** MusicBrainz search score. */
        val score: Double = 0.0,
    )

    /** Recording search hits (or one lookup wrapped as {"recordings":[rec]}); the album is the best release group. */
    fun parseRecordingHits(root: JsonElement?): List<RecordingHit> {
        val out = ArrayList<RecordingHit>()
        for (r in arr(root, "recordings")) {
            val id = str(r, "id")
            val name = str(r, "title")
            if (id.isEmpty() || name.isEmpty()) continue
            val releases = arr(r, "releases")
            var official = false
            var best: JsonElement? = null
            var bestRank = 1 shl 20
            for (rel in releases) {
                val off = asciiLower(str(rel, "status")) == "official"
                official = official || off
                val rg = get(rel, "release-group")
                if (str(rg, "id").isEmpty()) continue
                val rank = primaryRank(rg) + if (off) 0 else 10
                if (rank < bestRank) {
                    bestRank = rank
                    best = rg
                }
            }
            val album = if (best != null) {
                val albumId = str(best, "id")
                AlbumRef(albumId, str(best, "title"), coverArt(albumId))
            } else AlbumRef()
            val track = Track(
                id = id,
                name = name,
                durationMs = num(r, "length").toInt(),
                artists = parseArtistCredit(get(r, "artist-credit")),
                album = album,
            )
            out += RecordingHit(track, releases.size, official, num(r, "score").toDouble())
        }
        return out
    }

    fun rankRecordingSearch(hitsIn: List<RecordingHit>, query: String, freeText: Boolean, limit: Int): List<Track> {
        // Official-only when that leaves anything.
        val hits = if (hitsIn.any { it.official }) hitsIn.filter { it.official } else hitsIn
        val q = asciiLower(trim(query))

        class Ranked(val hit: RecordingHit, val rank: Double, val key: String)
        val ranked = hits.map { h ->
            val titleLower = asciiLower(h.track.name)
            val artistLower = asciiLower(h.track.artistLine)
            var rank = h.score + 8.0 * log2(1.0 + h.releaseCount)
            if (freeText) {
                for (a in h.track.artists) if (asciiLower(a.name) == q) rank += 25.0
                if (isAlternateVersion(titleLower) && !isAlternateVersion("$q)")) rank -= 15.0
            }
            if (h.track.durationMs <= 0) rank -= 5.0
            Ranked(h, rank, titleLower + "\u001f" + artistLower)
        }.sortedByDescending { it.rank }

        val out = ArrayList<Track>()
        val seen = HashMap<String, Int>()
        for (r in ranked) {
            val at = seen[r.key]
            if (at != null) {
                var kept = out[at]
                if (kept.durationMs <= 0 && r.hit.track.durationMs > 0) kept = kept.copy(durationMs = r.hit.track.durationMs)
                if (kept.album.id.isEmpty() && r.hit.track.album.id.isNotEmpty()) kept = kept.copy(album = r.hit.track.album)
                out[at] = kept
                continue
            }
            if (out.size >= limit) continue // keep scanning to fill gaps of kept rows
            seen[r.key] = out.size
            out += r.hit.track
        }
        return out
    }

    /** ListenBrainz /popularity/recording: recording_mbid -> total_listen_count. */
    fun parsePopularity(root: JsonElement?): Map<String, Long> {
        val out = HashMap<String, Long>()
        val list = root as? JsonArray ?: arr(root, "payload")
        for (row in list) {
            val id = str(row, "recording_mbid")
            if (id.isNotEmpty()) out[id] = maxOf(0L, num(row, "total_listen_count"))
        }
        return out
    }

    /**
     * Top tracks without ListenBrainz' auth-only endpoint: the artist's recordings + bulk popularity
     * (recording_mbid -> total_listen_count). Groups versions of the same title, ranks by listens, then plain versions
     * before remixes / live ones, then by release count.
     */
    fun rankTopTracks(hitsIn: List<RecordingHit>, listens: Map<String, Long>, limit: Int): List<Track> {
        val hits = if (hitsIn.any { it.official }) hitsIn.filter { it.official } else hitsIn

        class Group {
            var best: RecordingHit? = null
            var bestListens = -1L
            var totalListens = 0L
            var releases = 0
            var alternate = false
        }
        val groups = LinkedHashMap<String, Group>()
        for (h in hits) {
            val key = asciiLower(trim(h.track.name))
            val l = listens[h.track.id] ?: 0L
            val g = groups.getOrPut(key) { Group() }
            g.totalListens += l
            g.releases += h.releaseCount
            val cur = g.best
            val better = cur == null || l > g.bestListens ||
                (l == g.bestListens && (h.releaseCount > cur.releaseCount ||
                    (cur.track.album.id.isEmpty() && h.track.album.id.isNotEmpty())))
            if (better) {
                g.best = h
                g.bestListens = l
            }
            g.alternate = isAlternateVersion(key)
        }
        val sorted = groups.values.sortedWith { a, b ->
            when {
                a.totalListens != b.totalListens -> b.totalListens.compareTo(a.totalListens)
                a.alternate != b.alternate -> if (!a.alternate) -1 else 1
                else -> b.releases.compareTo(a.releases)
            }
        }
        return sorted.take(maxOf(0, limit)).map { it.best!!.track.copy(listenCount = it.totalListens) }
    }

    // ---- releases -------------------------------------------------------------------------------------------------
    /**
     * Representative release of a release group lookup (inc=releases+media), "" if none: official only (when any),
     * worldwide "XW" (+20) / Turkey "TR" (+15), CD / Digital media (+10) over vinyl & co (-5), fewest bonus tracks
     * (-2 per track over the shortest), the earliest date on ties.
     */
    fun pickRepresentativeRelease(rgLookup: JsonElement?): String {
        val releases = arr(rgLookup, "releases")
        if (releases.isEmpty()) return ""
        fun isOfficial(r: JsonElement) = asciiLower(str(r, "status")) == "official"
        val anyOfficial = releases.any { isOfficial(it) }
        fun trackCount(r: JsonElement): Long = arr(r, "media").sumOf { num(it, "track-count") }

        var minTracks = -1L
        for (r in releases) {
            if (anyOfficial && !isOfficial(r)) continue
            val n = trackCount(r)
            if (n > 0 && (minTracks < 0 || n < minTracks)) minTracks = n
        }

        var best: JsonElement? = null
        var bestScore = -1e18
        var bestDate = ""
        for (r in releases) {
            if (str(r, "id").isEmpty()) continue
            if (anyOfficial && !isOfficial(r)) continue
            var score = 0.0
            when (str(r, "country")) {
                "XW" -> score += 20
                "TR" -> score += 15
            }
            var good = 0
            var bad = 0
            for (m in arr(r, "media")) {
                val f = asciiLower(str(m, "format"))
                if (f.contains("cd") || f.contains("digital")) good++ else if (f.isNotEmpty()) bad++
            }
            if (good > 0 && bad == 0) score += 10 else if (bad > 0 && good == 0) score -= 5
            val n = trackCount(r)
            if (minTracks > 0 && n > minTracks) score -= 2.0 * (n - minTracks)
            if (n == 0L) score -= 3
            val date = str(r, "date")
            val earlier = date.isNotEmpty() && (bestDate.isEmpty() || date < bestDate)
            if (best == null || score > bestScore || (score == bestScore && earlier)) {
                best = r
                bestScore = score
                bestDate = date
            }
        }
        return if (best != null) str(best, "id") else ""
    }

    /** The album with the release's tracks, label, totalTracks and releaseId filled in. */
    fun applyRelease(album: Album, release: JsonElement?): Album {
        val artists = album.artists.ifEmpty { parseArtistCredit(get(release, "artist-credit")) }
        val date = album.firstReleaseDate.ifEmpty { str(release, "date") }
        var label = album.label
        for (li in arr(release, "label-info")) {
            val name = str(get(li, "label"), "name")
            if (name.isNotEmpty() && name != "[no label]") {
                label = name
                break
            }
        }
        val ref = AlbumRef(album.id, album.name, album.images)
        val tracks = ArrayList<Track>()
        for (medium in arr(release, "media")) {
            val list = if (get(medium, "tracks") is JsonArray) arr(medium, "tracks") else arr(medium, "track")
            for (t in list) {
                val rec = get(t, "recording")
                val id = str(rec, "id").ifEmpty { str(t, "id") }
                val name = str(t, "title").ifEmpty { str(rec, "title") }
                var len = num(t, "length")
                if (len <= 0) len = num(rec, "length")
                val credit = parseArtistCredit(get(t, "artist-credit"))
                    .ifEmpty { parseArtistCredit(get(rec, "artist-credit")) }
                    .ifEmpty { artists }
                if (id.isNotEmpty()) {
                    tracks += Track(
                        id = id, name = name, durationMs = len.toInt(), trackNumber = num(t, "position").toInt(),
                        artists = credit, album = ref,
                    )
                }
            }
        }
        return album.copy(
            releaseId = str(release, "id"),
            artists = artists,
            firstReleaseDate = date,
            label = label,
            tracks = tracks,
            totalTracks = tracks.size,
        )
    }

    // ---- ListenBrainz ---------------------------------------------------------------------------------------------
    /** Cover Art Archive images of one release: a specific image id (caa_id) or its front cover. */
    fun caaReleaseImages(releaseMbid: String, caaId: Long): List<Image> {
        if (releaseMbid.isEmpty()) return emptyList()
        val base = "https://coverartarchive.org/release/$releaseMbid/"
        if (caaId > 0) {
            return listOf(
                Image("$base$caaId-250.jpg", 250, 250), Image("$base$caaId-500.jpg", 500, 500),
                Image("$base$caaId-1200.jpg", 1200, 1200),
            )
        }
        return listOf(Image(base + "front-250", 250, 250), Image(base + "front-500", 500, 500), Image(base + "front-1200", 1200, 1200))
    }

    /** stats/sitewide/artists. */
    fun parseLbArtists(root: JsonElement?, limit: Int): List<Artist> {
        val out = ArrayList<Artist>()
        val seen = HashSet<String>()
        for (row in lbPayloadList(root, "artists")) {
            if (out.size >= limit) break
            val id = str(row, "artist_mbid").ifEmpty { strArray(get(row, "artist_mbids")).firstOrNull() ?: "" }
            val name = str(row, "artist_name")
            if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue
            out += Artist(id = id, name = name, listenCount = num(row, "listen_count"))
        }
        return out
    }

    /** stats/sitewide/release-groups. */
    fun parseLbReleaseGroups(root: JsonElement?, limit: Int): List<Album> {
        val out = ArrayList<Album>()
        val seen = HashSet<String>()
        for (row in lbPayloadList(root, "release_groups")) {
            if (out.size >= limit) break
            val id = str(row, "release_group_mbid")
            if (id.isEmpty() || !seen.add(id)) continue
            out += Album(
                id = id,
                name = str(row, "release_group_name"),
                releaseId = str(row, "caa_release_mbid"),
                artists = lbArtists(row, "artist_name"),
                images = coverArt(id),
            )
        }
        return out
    }

    /** stats/sitewide/recordings and popularity/top-recordings-for-artist. */
    fun parseLbRecordings(root: JsonElement?, limit: Int): List<Track> {
        val out = ArrayList<Track>()
        val seen = HashSet<String>()
        for (row in lbPayloadList(root, "recordings")) {
            if (out.size >= limit) break
            val id = str(row, "recording_mbid")
            if (id.isEmpty() || !seen.add(id)) continue
            val albumId = str(row, "release_group_mbid")
            val albumName = str(row, "release_group_name").ifEmpty { str(row, "release_name") }
            var images = if (albumId.isNotEmpty()) coverArt(albumId)
            else caaReleaseImages(str(row, "caa_release_mbid"), num(row, "caa_id"))
            if (images.isEmpty() && str(row, "release_mbid").isNotEmpty() && num(row, "caa_id") > 0)
                images = caaReleaseImages(str(row, "release_mbid"), 0)
            out += Track(
                id = id,
                name = str(row, "track_name").ifEmpty { str(row, "recording_name") },
                artists = lbArtists(row, "artist_name"),
                durationMs = num(row, "length").toInt(),
                listenCount = num(row, "listen_count", num(row, "total_listen_count")),
                album = AlbumRef(albumId, albumName, images),
            )
        }
        return out
    }

    /** explore/fresh-releases: most listened first, then with cover, then albums / EPs, then newest. */
    fun parseFreshReleases(root: JsonElement?, limit: Int): List<Album> {
        class Row(val album: Album, val cover: Boolean, val longForm: Boolean, val listens: Long)
        val rows = ArrayList<Row>()
        val seen = HashSet<String>()
        for (r in lbPayloadList(root, "releases")) {
            val id = str(r, "release_group_mbid")
            if (id.isEmpty() || !seen.add(id)) continue
            val cover = num(r, "caa_id") > 0
            val pt = str(r, "release_group_primary_type")
            val secondary = str(r, "release_group_secondary_type")
            val a = Album(
                id = id,
                releaseId = str(r, "release_mbid"),
                name = str(r, "release_name"),
                primaryType = pt,
                secondaryTypes = if (secondary.isNotEmpty()) listOf(secondary) else emptyList(),
                artists = lbArtists(r, "artist_credit_name"),
                firstReleaseDate = str(r, "release_date"),
                images = if (cover) coverArt(id) else emptyList(),
            )
            val lpt = asciiLower(pt)
            rows += Row(a, cover, lpt == "album" || lpt == "ep", num(r, "listen_count"))
        }
        return rows.sortedWith { x, y ->
            when {
                x.listens != y.listens -> y.listens.compareTo(x.listens)
                x.cover != y.cover -> if (x.cover) -1 else 1
                x.longForm != y.longForm -> if (x.longForm) -1 else 1
                else -> y.album.firstReleaseDate.compareTo(x.album.firstReleaseDate)
            }
        }.take(maxOf(0, limit)).map { it.album }
    }

    /** labs similar-artists; highest score first, the artist itself left out. */
    fun parseSimilarArtists(root: JsonElement?, selfId: String, limit: Int): List<Artist> {
        // Labs returns a flat array; older deployments wrapped it as [{ "data": [...] }, ...].
        var list: JsonElement? = root
        if (root is JsonArray && root.isNotEmpty() && get(root[0], "data") is JsonArray) list = get(root[0], "data")
        if (list !is JsonArray) list = lbPayloadList(root, "artists")
        val rows = ArrayList<Pair<Long, Artist>>()
        val seen = HashSet<String>()
        for (row in list as JsonArray) {
            val id = str(row, "artist_mbid")
            val name = str(row, "name").ifEmpty { str(row, "artist_name") }
            if (id.isEmpty() || name.isEmpty() || id == selfId || !seen.add(id)) continue
            rows += num(row, "score") to Artist(id = id, name = name, type = str(row, "type"), disambiguation = str(row, "comment"))
        }
        return rows.sortedByDescending { it.first }.take(maxOf(0, limit)).map { it.second }
    }
}
