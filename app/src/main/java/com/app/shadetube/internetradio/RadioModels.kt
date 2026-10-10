package com.app.shadetube.internetradio

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Http
import com.app.shadetube.core.arr
import com.app.shadetube.core.get
import com.app.shadetube.core.isArray
import com.app.shadetube.core.isObject
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Locale

/**
 * Internet radio (port of app/InternetRadio.h): a radio-browser.info client (https://api.radio-browser.info), the
 * station model and the radio.json store (favorites, recently played and the stations of the playing list).
 *
 * Stations play as [Track]s with the id "radio:<stationuuid>" (no duration, no seeking, no prefetch or crossfade).
 */

/** Every server failed, bad JSON, an HTTP error. */
open class RadioError(message: String) : IOException(message)

data class Station(
    /** stationuuid (stable across edits). */
    val uuid: String = "",
    val name: String = "",
    /** As submitted (may be a .pls / .m3u playlist). */
    val url: String = "",
    /** The direct stream radio-browser resolved from it (preferred). */
    val urlResolved: String = "",
    val homepage: String = "",
    /** "" = none (the UI draws a radio placeholder). Only http(s) images. */
    val favicon: String = "",
    /** Lower-case, in the submitted order ("pop", "news", ...). */
    val tags: List<String> = emptyList(),
    /** English name from the API ("Turkey"). */
    val country: String = "",
    /** ISO 3166-1 alpha-2 ("TR"). */
    val countryCode: String = "",
    val state: String = "",
    /** First language ("turkish"). */
    val language: String = "",
    /** "MP3", "AAC", "AAC+", "OGG", "UNKNOWN", ... */
    val codec: String = "",
    /** kbps, 0 = unknown. */
    val bitrate: Int = 0,
    /** HTTP Live Streaming playlist. */
    val hls: Boolean = false,
    val votes: Int = 0,
    /** Clicks in the last 24 h. */
    val clickCount: Int = 0,
)

data class RadioTag(val name: String, val stationCount: Int = 0)

data class RadioCountry(
    /** ISO 3166-1 alpha-2. */
    val code: String,
    /** English name from the API. */
    val name: String,
    val stationCount: Int = 0,
)

data class StationPage(
    /** Playable ones only (see [RadioModel.playable]). */
    val stations: List<Station> = emptyList(),
    /** The server returned a full page: there may be another one. */
    val more: Boolean = false,
    /** Offset of the next page (raw rows, before the codec filter). */
    val nextOffset: Int = 0,
)

data class RadioQuery(
    /** Part of the station name. */
    val name: String = "",
    /** Exact tag. */
    val tag: String = "",
    /** ISO code. */
    val countryCode: String = "",
    /** Exact language ("turkish"). */
    val language: String = "",
    val order: Order = Order.ClickCount,
    /** Most first. */
    val reverse: Boolean = true,
    val limit: Int = 50,
    val offset: Int = 0,
) {
    enum class Order { ClickCount, Votes }
}

/**
 * Display texts of the station model. The backend has no user-facing strings: the UI installs an implementation
 * backed by strings.xml ([InternetRadio.labels]); [DefaultRadioLabels] gives English.
 */
interface RadioLabels {
    /** Country name for an ISO code in the display language ("TR" -> "Türkiye"), else [fallback]. */
    fun countryName(code: String, fallback: String): String

    /** Label of a known genre ([RadioGenres.isGenre]); null = not a known genre. */
    fun knownGenreLabel(tag: String): String?

    /** "Internet radio": the artist line of a station with neither country nor tags. */
    val internetRadio: String
}

object DefaultRadioLabels : RadioLabels {
    override fun countryName(code: String, fallback: String): String {
        if (code.length == 2) {
            val name = runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault()) }.getOrNull().orEmpty()
            if (name.isNotEmpty() && !name.equals(code, ignoreCase = true)) return name
        }
        return fallback
    }

    override fun knownGenreLabel(tag: String): String? = RadioGenres.englishLabel(tag)
    override val internetRadio: String = "Internet radio"
}

/** radio-browser tags that get a translated genre label (C++ genres()). */
object RadioGenres {
    /** tag -> English label. Synonyms ("lofi" / "lo-fi") share a [key]. */
    private val GENRES: Map<String, String> = linkedMapOf(
        "pop" to "Pop", "rock" to "Rock", "news" to "News", "jazz" to "Jazz", "classical" to "Classical",
        "lofi" to "Lo-fi", "lo-fi" to "Lo-fi", "dance" to "Dance", "electronic" to "Electronic", "80s" to "80s",
        "90s" to "90s", "70s" to "70s", "60s" to "60s", "hiphop" to "Hip hop", "hip hop" to "Hip hop",
        "hip-hop" to "Hip hop", "chillout" to "Chillout", "ambient" to "Ambient", "oldies" to "Oldies", "talk" to "Talk",
        "metal" to "Metal", "blues" to "Blues", "soul" to "Soul", "reggae" to "Reggae", "country" to "Country",
        "sports" to "Sports", "sport" to "Sports", "house" to "House", "top 40" to "Top 40", "hits" to "Hits",
        "classic rock" to "Classic rock", "alternative" to "Alternative", "indie" to "Indie", "folk" to "Folk",
        "turkish" to "Turkish", "arabesk" to "Arabesk", "religious" to "Religious", "christian" to "Christian",
        "kids" to "Kids", "latin" to "Latin", "techno" to "Techno", "trance" to "Trance", "rnb" to "R&B", "funk" to "Funk",
        "disco" to "Disco", "world music" to "World music", "public radio" to "Public radio",
        "community radio" to "Community radio", "instrumental" to "Instrumental", "lounge" to "Lounge",
        "soundtrack" to "Soundtrack", "rap" to "Rap", "punk" to "Punk",
    )

    private val SYNONYMS = mapOf("lo-fi" to "lofi", "hip hop" to "hiphop", "hip-hop" to "hiphop", "sport" to "sports")

    /** The genre chips of the Radio page (tags with a translated label), most popular first. */
    val featured: List<String> = listOf(
        "pop", "rock", "news", "jazz", "classical", "lofi", "dance", "electronic", "80s", "90s", "hiphop", "chillout",
        "ambient", "oldies", "talk", "metal", "blues", "soul",
    )

    /** Every known genre tag (synonyms included). */
    val all: Set<String> get() = GENRES.keys

    fun isGenre(tag: String) = tag in GENRES

    /** The stable key the UI maps to a string resource ("lo-fi" -> "lofi"); null = not a known genre. */
    fun key(tag: String): String? = if (tag in GENRES) SYNONYMS[tag] ?: tag else null

    fun englishLabel(tag: String): String? = GENRES[tag]
}

// ---- model functions ----------------------------------------------------------------------------------------------------

object RadioModel {
    /**
     * What the player plays: MP3 and AAC / AAC+ (HE-AAC) Icecast / Shoutcast streams, and HLS playlists. The switch
     * hides HLS stations if that ever regresses.
     */
    const val PLAY_HLS = true

    const val ID_PREFIX = "radio:"

    internal fun isHttpUrl(u: String): Boolean {
        val l = u.take(8).lowercase(Locale.ROOT)
        return l.startsWith("http://") || l.startsWith("https://")
    }

    /** The URL the player opens (url_resolved, else url; "" = none). */
    fun streamUrl(s: Station): String = when {
        isHttpUrl(s.urlResolved) -> s.urlResolved
        isHttpUrl(s.url) -> s.url
        else -> ""
    }

    fun playable(s: Station): Boolean {
        if (s.uuid.isEmpty() || streamUrl(s).isEmpty()) return false
        val c = s.codec
        val known = c == "MP3" || c == "AAC" || c == "AAC+"
        if (s.hls) return PLAY_HLS && (known || c.isEmpty() || c == "UNKNOWN")
        return known
    }

    fun filterPlayable(stations: List<Station>): List<Station> = stations.filter { playable(it) }

    /** Small mono badge: "MP3 · 128", "AAC+ · 64", "AAC" (bitrate unknown), "HLS". */
    fun codecBadge(s: Station): String {
        var c = if (s.codec.isEmpty() || s.codec == "UNKNOWN") (if (s.hls) "HLS" else "") else s.codec
        if (s.bitrate > 0) c += (if (c.isEmpty()) "" else " · ") + s.bitrate
        return c
    }

    /** Media type hint for the player ("" = let it sniff). */
    fun mimeType(s: Station): String = when {
        s.hls -> "application/vnd.apple.mpegurl"
        s.codec == "MP3" -> "audio/mpeg"
        s.codec == "AAC" || s.codec == "AAC+" -> "audio/aac"
        else -> ""
    }

    /** "/json/stations/search?..." (hidebroken=true). */
    fun searchPath(q: RadioQuery): String {
        val p = StringBuilder("/json/stations/search?")
        if (q.name.isNotEmpty()) p.append("name=").append(Http.urlEncode(q.name)).append('&')
        if (q.tag.isNotEmpty()) p.append("tag=").append(Http.urlEncode(q.tag)).append("&tagExact=true&")
        if (q.countryCode.isNotEmpty()) p.append("countrycode=").append(Http.urlEncode(q.countryCode)).append('&')
        if (q.language.isNotEmpty()) p.append("language=").append(Http.urlEncode(q.language)).append("&languageExact=true&")
        p.append("order=").append(if (q.order == RadioQuery.Order.Votes) "votes" else "clickcount")
            .append("&reverse=").append(if (q.reverse) "true" else "false")
            .append("&hidebroken=true&limit=").append(q.limit).append("&offset=").append(q.offset)
        return p.toString()
    }

    // ---- player model ----------------------------------------------------------------------------------------------------

    fun isStationId(trackId: String) = trackId.length > ID_PREFIX.length && trackId.startsWith(ID_PREFIX)

    /** "" when not a station id. */
    fun uuidOf(trackId: String): String = if (isStationId(trackId)) trackId.substring(ID_PREFIX.length) else ""

    fun stationId(uuid: String) = ID_PREFIX + uuid

    /** Genre label for a tag: a known genre's label, else the tag capitalized (not with Turkish casing rules). */
    fun genreLabel(tag: String, labels: RadioLabels = InternetRadio.labels): String {
        labels.knownGenreLabel(tag)?.let { return it }
        if (tag.isEmpty()) return tag
        // Tags are mostly English / Spanish: capitalized without the Turkish casing rules ("information" -> "Information").
        val first = tag.codePointAt(0)
        return String(Character.toChars(Character.toUpperCase(first))) + tag.substring(Character.charCount(first))
    }

    /** "Türkiye · Pop, News" (localized country, two genres: known ones first); "Internet radio" when both are unknown. */
    fun subtitle(s: Station, labels: RadioLabels = InternetRadio.labels): String {
        var out = labels.countryName(s.countryCode, s.country)
        val chosen = ArrayList<String>()
        for (pass in 0 until 2) {
            if (chosen.size >= 2) break
            for (t in s.tags) {
                if (chosen.size >= 2) break
                if ((labels.knownGenreLabel(t) != null) != (pass == 0)) continue
                val l = genreLabel(t, labels)
                if (l.isNotEmpty() && l !in chosen) chosen.add(l)
            }
        }
        val tags = chosen.joinToString(", ")
        if (tags.isNotEmpty()) out += (if (out.isEmpty()) "" else " · ") + tags
        return out.ifEmpty { labels.internetRadio }
    }

    /** Station name as title, "Country · genres" as the artist line, the favicon as the album image, no duration. */
    fun toTrack(s: Station, labels: RadioLabels = InternetRadio.labels): Track = Track(
        id = ID_PREFIX + s.uuid,
        name = s.name,
        artists = listOf(ArtistRef("", subtitle(s, labels))),
        album = AlbumRef("", "", if (s.favicon.isNotEmpty()) listOf(Image(s.favicon, 0, 0)) else emptyList()),
        durationMs = 0,
    )

    fun toTracks(stations: List<Station>, labels: RadioLabels = InternetRadio.labels): List<Track> = stations.map { toTrack(it, labels) }
}

// ---- JSON ------------------------------------------------------------------------------------------------------------------

/** radio-browser.info responses and the radio.json form. Malformed JSON throws [RadioError]; bad entries are skipped. */
object RadioJson {
    private fun parse(body: String): JsonElement =
        parseJsonOrNull(body.removePrefix("\uFEFF")) ?: throw RadioError("radio-browser.info: bad JSON")

    /** A string value, else "" (C++ str()). */
    internal fun str(j: JsonElement?, key: String): String {
        val v = j[key] as? JsonPrimitive ?: return ""
        return if (v !is JsonNull && v.isString) v.content else ""
    }

    /** A number (int / float / bool / numeric string), clamped to Int (C++ num()). */
    internal fun num(j: JsonElement?, key: String): Int {
        val v = j[key] as? JsonPrimitive ?: return 0
        if (v is JsonNull) return 0
        if (v.isString) return atoi(v.content)
        v.longOrNull?.let { return it.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt() }
        v.doubleOrNull?.let { return it.coerceIn(-2147483648.0, 2147483647.0).toInt() }
        v.booleanOrNull?.let { return if (it) 1 else 0 }
        return 0
    }

    private fun atoi(s: String): Int {
        var i = 0
        while (i < s.length && s[i].isWhitespace()) ++i
        var neg = false
        if (i < s.length && (s[i] == '+' || s[i] == '-')) {
            neg = s[i] == '-'
            ++i
        }
        var v = 0L
        while (i < s.length && s[i] in '0'..'9' && v < Int.MAX_VALUE) v = v * 10 + (s[i++] - '0')
        return (if (neg) -v else v).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun trimWs(s: String) = s.trim(' ', '\t', '\r', '\n')

    private fun splitList(s: String): List<String> = s.split(',').map { trimWs(it) }.filter { it.isNotEmpty() }

    /** Collapses runs of whitespace (names sometimes carry tabs / double spaces) and trims. */
    private fun cleanName(s: String): String {
        val out = StringBuilder(s.length)
        var space = false
        for (c in s) {
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                space = out.isNotEmpty()
                continue
            }
            if (space) out.append(' ')
            space = false
            out.append(c)
        }
        return out.toString()
    }

    private fun lowerAscii(s: String) = buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') c + 32 else c) }
    private fun upperAscii(s: String) = buildString(s.length) { for (c in s) append(if (c in 'a'..'z') c - 32 else c) }

    internal fun stationsFrom(j: JsonElement): List<Station> {
        if (!j.isArray) throw RadioError("radio-browser.info: expected a station list")
        val out = ArrayList<Station>()
        val seen = HashSet<String>()
        for (o in j.arr) {
            if (!o.isObject) continue
            val uuid = trimWs(str(o, "stationuuid"))
            val name = cleanName(str(o, "name"))
            val url = trimWs(str(o, "url"))
            val urlResolved = trimWs(str(o, "url_resolved"))
            if (uuid.isEmpty() || name.isEmpty() || (url.isEmpty() && urlResolved.isEmpty())) continue
            if (!seen.add(uuid)) continue
            val tags = ArrayList<String>()
            for (t in splitList(str(o, "tags"))) {
                val l = lowerAscii(t)
                if (l !in tags && tags.size < 32) tags.add(l)
            }
            val favicon = trimWs(str(o, "favicon"))
            out.add(
                Station(
                    uuid = uuid,
                    name = name,
                    url = url,
                    urlResolved = urlResolved,
                    homepage = trimWs(str(o, "homepage")),
                    favicon = if (RadioModel.isHttpUrl(favicon)) favicon else "",
                    tags = tags,
                    country = trimWs(str(o, "country")),
                    countryCode = upperAscii(trimWs(str(o, "countrycode"))),
                    state = trimWs(str(o, "state")),
                    language = splitList(str(o, "language")).firstOrNull()?.let { lowerAscii(it) } ?: "",
                    codec = upperAscii(trimWs(str(o, "codec"))),
                    bitrate = maxOf(0, num(o, "bitrate")),
                    hls = num(o, "hls") != 0,
                    votes = maxOf(0, num(o, "votes")),
                    clickCount = maxOf(0, num(o, "clickcount")),
                ),
            )
        }
        return out
    }

    fun parseStations(body: String): List<Station> = stationsFrom(parse(body))

    fun parseTags(body: String): List<RadioTag> {
        val j = parse(body)
        if (!j.isArray) throw RadioError("radio-browser.info: expected a tag list")
        return j.arr.filter { it.isObject }
            .map { RadioTag(lowerAscii(trimWs(str(it, "name"))), maxOf(0, num(it, "stationcount"))) }
            .filter { it.name.isNotEmpty() }
    }

    fun parseCountries(body: String): List<RadioCountry> {
        val j = parse(body)
        if (!j.isArray) throw RadioError("radio-browser.info: expected a country list")
        val out = ArrayList<RadioCountry>()
        val seen = HashSet<String>()
        for (o in j.arr) {
            if (!o.isObject) continue
            val c = RadioCountry(upperAscii(trimWs(str(o, "iso_3166_1"))), trimWs(str(o, "name")), maxOf(0, num(o, "stationcount")))
            if (c.code.length != 2 || !seen.add(c.code)) continue
            out.add(c)
        }
        return out
    }

    /** json/servers -> unique host names; only radio-browser's own API hosts (the name ends up in request URLs). */
    fun parseServers(body: String): List<String> {
        val j = parse(body)
        if (!j.isArray) throw RadioError("radio-browser.info: expected a server list")
        val out = ArrayList<String>()
        for (o in j.arr) {
            if (!o.isObject) continue
            val name = lowerAscii(trimWs(str(o, "name")))
            if (name.length <= 23 || !name.endsWith(".api.radio-browser.info")) continue
            if (name !in out) out.add(name)
        }
        return out
    }

    /** radio.json form. */
    fun station(s: Station): JsonObject = buildJsonObject {
        put("uuid", s.uuid)
        put("name", s.name)
        if (s.url.isNotEmpty()) put("url", s.url)
        if (s.urlResolved.isNotEmpty()) put("urlResolved", s.urlResolved)
        if (s.homepage.isNotEmpty()) put("homepage", s.homepage)
        if (s.favicon.isNotEmpty()) put("favicon", s.favicon)
        if (s.tags.isNotEmpty()) put("tags", JsonArray(s.tags.map { JsonPrimitive(it) }))
        if (s.country.isNotEmpty()) put("country", s.country)
        if (s.countryCode.isNotEmpty()) put("countryCode", s.countryCode)
        if (s.state.isNotEmpty()) put("state", s.state)
        if (s.language.isNotEmpty()) put("language", s.language)
        if (s.codec.isNotEmpty()) put("codec", s.codec)
        if (s.bitrate > 0) put("bitrate", s.bitrate)
        if (s.hls) put("hls", true)
        if (s.votes > 0) put("votes", s.votes)
        if (s.clickCount > 0) put("clicks", s.clickCount)
    }

    /** Throws [RadioError] on a wrong shape. */
    fun stationFrom(j: JsonElement?): Station {
        if (!j.isObject) throw RadioError("station: not an object")
        val uuid = str(j, "uuid")
        val name = str(j, "name")
        if (uuid.isEmpty() || name.isEmpty()) throw RadioError("station: no uuid / name")
        val favicon = str(j, "favicon")
        return Station(
            uuid = uuid,
            name = name,
            url = str(j, "url"),
            urlResolved = str(j, "urlResolved"),
            homepage = str(j, "homepage"),
            favicon = if (RadioModel.isHttpUrl(favicon)) favicon else "",
            tags = j["tags"].arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            country = str(j, "country"),
            countryCode = str(j, "countryCode"),
            state = str(j, "state"),
            language = str(j, "language"),
            codec = str(j, "codec"),
            bitrate = maxOf(0, num(j, "bitrate")),
            hls = num(j, "hls") != 0,
            votes = maxOf(0, num(j, "votes")),
            clickCount = maxOf(0, num(j, "clicks")),
        )
    }
}
