package com.app.shadetube.podcasts

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.PODCAST_ID_PREFIX
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Hash
import com.app.shadetube.core.arr
import com.app.shadetube.core.get
import com.app.shadetube.core.isObject
import com.app.shadetube.core.isString
import com.app.shadetube.core.stringOrNull
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
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * Podcasts (port of app/Podcasts.h): Apple's podcast directory (search, a show's feed URL, a country's top shows), RSS
 * feeds, the episode model the player plays, episode downloads and the podcasts.json store.
 *
 * Episodes play as [Track] with id "podcast:<16 hex>" (FNV-1a of the feed URL and the item's guid, see
 * catalog isPodcastId): the episode title as the name, the show as artist and album, the episode's (else the show's)
 * artwork and itunes:duration. They skip YouTube matching, SponsorBlock, lyrics, scrobbling, the play history, listening
 * stats, the song downloads, likes and radio / autoplay seeds.
 */

/** Network, HTTP status or bad data. [message] is technical (logged); map it to UI text with [PodcastErrorKind.of]. */
class PodcastError(message: String) : IOException(message)

/** What the UI tells the user about a failed podcast operation (port of PodcastsPage's podcastError()). */
enum class PodcastErrorKind {
    /** The address is on the local network: only podcasts added by their RSS address may play from there. */
    LocalNetwork,

    /** No podcast feed at this address. */
    NotAFeed,

    /** The feed is no longer published (HTTP 404 / 410). */
    Gone,

    /** Anything else: check the connection and try again. */
    Network;

    companion object {
        fun of(t: Throwable): PodcastErrorKind {
            val raw = t.message.orEmpty()
            return when {
                raw.contains("local network") -> LocalNetwork
                raw.contains("not an RSS") || raw.contains("Atom") || raw.contains("XML") -> NotAFeed
                raw.contains("HTTP 404") || raw.contains("HTTP 410") -> Gone
                else -> Network
            }
        }
    }
}

data class Episode(
    /** The item's guid, else its media URL. */
    val guid: String = "",
    /** The show it belongs to. */
    val feedUrl: String = "",
    val title: String = "",
    /** Show title. */
    val show: String = "",
    /** Show author. */
    val author: String = "",
    /** Plain text (HTML turned into lines), capped. */
    val description: String = "",
    /**
     * The show notes as published (HTML when the feed has markup, else ""), capped; for a rich notes view. Not stored
     * in podcasts.json (only in the feed cache), so an episode known only from the store has just [description].
     */
    val notesHtml: String = "",
    /** Episode artwork, else the show's. */
    val image: String = "",
    /** Enclosure (the audio). */
    val url: String = "",
    /** Enclosure type as given ("audio/mpeg"; may be empty or wrong). */
    val mimeType: String = "",
    /** Enclosure length in bytes as given (often wrong; 0 = unknown). */
    val length: Long = 0,
    /** itunes:duration (0 = unknown). */
    val durationMs: Long = 0,
    /** Unix seconds (0 = unknown). */
    val published: Long = 0,
    /** itunes:season (0 = none). */
    val season: Int = 0,
    /** itunes:episode (0 = none). */
    val number: Int = 0,
    val explicitContent: Boolean = false,
) {
    /** "podcast:<16 hex>". */
    val id: String get() = episodeId(feedUrl, guid)
}

data class Show(
    val feedUrl: String = "",
    val title: String = "",
    val author: String = "",
    /** Plain text. */
    val description: String = "",
    val image: String = "",
    /** Website. */
    val link: String = "",
    /** Directory id when known. */
    val appleId: String = "",
    val categories: List<String> = emptyList(),
    /** Newest first. */
    val episodes: List<Episode> = emptyList(),
)

/** A show in the directory (search result, chart entry). */
data class DirectoryEntry(
    val appleId: String = "",
    val title: String = "",
    val author: String = "",
    /** 600 px artwork when the directory has it. */
    val image: String = "",
    /** "" in charts: [PodcastClient.lookupFeed] finds it. */
    val feedUrl: String = "",
    val genre: String = "",
)

data class Subscription(
    val feedUrl: String = "",
    val title: String = "",
    val author: String = "",
    val image: String = "",
    val appleId: String = "",
    val subscribedAt: Long = 0,
    /** Unix seconds of the last successful feed refresh. */
    val lastRefresh: Long = 0,
    /** Newest episode date the user has seen: later episodes are new. */
    val seenUntil: Long = 0,
    /** Episodes published after seenUntil (as of the last refresh). */
    val newEpisodes: Int = 0,
    /** Added by its feed URL (may live on the local network). */
    val manual: Boolean = false,
)

/** What the user did with an episode. Only episodes with a state are kept (with their metadata). */
data class EpisodeState(
    /** Where to continue (0 = from the start). */
    val positionMs: Long = 0,
    /** As measured while playing (0 = unknown). */
    val durationMs: Long = 0,
    val played: Boolean = false,
    /** Downloaded audio (absolute path), "" = not downloaded. */
    val file: String = "",
    val fileBytes: Long = 0,
    /** Unix seconds. */
    val downloadedAt: Long = 0,
    /** Unix seconds of the last change. */
    val updatedAt: Long = 0,
)

/** The audio as the server really has it (after the analytics redirects). */
data class PodcastMedia(
    val url: String,
    /** audio/x from the server, else "". */
    val mimeType: String = "",
    /** 0 = unknown. */
    val length: Long = 0,
)

// ---- JSON helpers (nlohmann-tolerant like the C++ str / num64 / flag) -------------------------------------------------------

/** String of a string or integer value, else "". */
internal fun jstr(j: JsonElement?, key: String): String {
    val v = j[key] as? JsonPrimitive ?: return ""
    if (v is JsonNull) return ""
    if (v.isString) return v.content
    return v.longOrNull?.toString() ?: ""
}

internal fun jnum64(j: JsonElement?, key: String): Long {
    val v = j[key] as? JsonPrimitive ?: return 0
    if (v is JsonNull) return 0
    if (v.isString) return atoi64(v.content)
    v.longOrNull?.let { return it }
    v.doubleOrNull?.let { return it.toLong() }
    v.booleanOrNull?.let { return if (it) 1 else 0 }
    return 0
}

internal fun jflag(j: JsonElement?, key: String) = jnum64(j, key) != 0L

// ---- JSON (store / cache) -----------------------------------------------------------------------------------------------

object PodcastJson {
    /** The cache / store form of an episode; [withHtml] = false leaves the raw notes out (podcasts.json). */
    fun episode(e: Episode, withHtml: Boolean = true): JsonObject = buildJsonObject {
        put("guid", e.guid)
        put("feed", e.feedUrl)
        put("title", e.title)
        put("show", e.show)
        put("url", e.url)
        if (e.author.isNotEmpty()) put("author", e.author)
        if (e.description.isNotEmpty()) put("desc", e.description)
        if (withHtml && e.notesHtml.isNotEmpty()) put("html", e.notesHtml)
        if (e.image.isNotEmpty()) put("img", e.image)
        if (e.mimeType.isNotEmpty()) put("type", e.mimeType)
        if (e.length > 0) put("len", e.length)
        if (e.durationMs > 0) put("dur", e.durationMs)
        if (e.published > 0) put("pub", e.published)
        if (e.season > 0) put("season", e.season)
        if (e.number > 0) put("ep", e.number)
        if (e.explicitContent) put("explicit", true)
    }

    /** Throws [PodcastError] on a wrong shape. */
    fun episodeFrom(j: JsonElement?): Episode {
        if (!j.isObject) throw PodcastError("episode: not an object")
        val e = Episode(
            guid = jstr(j, "guid"),
            feedUrl = jstr(j, "feed"),
            title = jstr(j, "title"),
            show = jstr(j, "show"),
            author = jstr(j, "author"),
            description = jstr(j, "desc"),
            notesHtml = jstr(j, "html"),
            image = jstr(j, "img"),
            url = jstr(j, "url"),
            mimeType = jstr(j, "type"),
            length = maxOf(0, jnum64(j, "len")),
            durationMs = maxOf(0, jnum64(j, "dur")),
            published = maxOf(0, jnum64(j, "pub")),
            season = jnum64(j, "season").coerceIn(0, 100000).toInt(),
            number = jnum64(j, "ep").coerceIn(0, 1000000).toInt(),
            explicitContent = jflag(j, "explicit"),
        )
        if (e.guid.isEmpty() || e.feedUrl.isEmpty() || !PodcastText.isHttpUrl(e.url)) {
            throw PodcastError("episode: missing guid / feed / url")
        }
        return if (PodcastText.isHttpUrl(e.image)) e else e.copy(image = "")
    }

    /** With its episodes. */
    fun show(s: Show): JsonObject = buildJsonObject {
        put("feed", s.feedUrl)
        put("title", s.title)
        put("author", s.author)
        put("desc", s.description)
        put("img", s.image)
        put("link", s.link)
        put("apple", s.appleId)
        put("categories", JsonArray(s.categories.map { JsonPrimitive(it) }))
        put("episodes", JsonArray(s.episodes.map { episode(it) }))
    }

    fun showFrom(j: JsonElement?): Show {
        if (!j.isObject) throw PodcastError("show: not an object")
        val s = Show(
            feedUrl = jstr(j, "feed"),
            title = jstr(j, "title"),
            author = jstr(j, "author"),
            description = jstr(j, "desc"),
            image = jstr(j, "img"),
            link = jstr(j, "link"),
            appleId = jstr(j, "apple"),
            categories = j["categories"].arr.filter { it.isString }.mapNotNull { it.stringOrNull },
            episodes = j["episodes"].arr.mapNotNull { runCatching { episodeFrom(it) }.getOrNull() },
        )
        if (s.feedUrl.isEmpty()) throw PodcastError("show: no feed URL")
        return s
    }
}

// ---- player model ----------------------------------------------------------------------------------------------------------

/** "podcast:" + 16 hex digits of FNV-1a over feedUrl, "\n" and guid (byte-compatible with the C++ ids). */
fun episodeId(feedUrl: String, guid: String): String = PODCAST_ID_PREFIX + Hash.fnv1a64Hex(feedUrl + "\n" + guid)

fun Episode.toTrack(): Track = Track(
    id = id,
    name = title.ifEmpty { show },
    artists = listOf(ArtistRef("", show.ifEmpty { author })),
    album = AlbumRef("", show, if (image.isNotEmpty()) listOf(Image(image, 0, 0)) else emptyList()),
    durationMs = durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
    explicitContent = explicitContent,
    addedAt = published,
)

fun List<Episode>.toTracks(): List<Track> = map { it.toTrack() }

/** Heard to the end: at least 95 % or within the last 30 seconds. */
fun playedAt(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0 || positionMs <= 0) return false
    return positionMs * 100 >= durationMs * 95 || durationMs - positionMs <= 30_000
}

private val TYPES = listOf(
    "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "mp4" to "audio/mp4", "m4b" to "audio/mp4",
    "aac" to "audio/aac", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg",
    "wav" to "audio/wav", "flac" to "audio/flac",
)

internal fun extensionOf(url: String): String {
    var path = url
    val q = path.indexOfFirst { it == '?' || it == '#' }
    if (q >= 0) path = path.substring(0, q)
    val slash = path.lastIndexOf('/')
    val dot = path.lastIndexOf('.')
    if (dot < 0 || (slash >= 0 && dot < slash)) return ""
    return asciiLower(path.substring(dot + 1))
}

/**
 * Media type hint for the player: the enclosure type when it is audio, else by the URL's extension ("" = let the
 * player sniff).
 */
fun mimeFor(mimeType: String, url: String): String {
    var type = asciiLower(trimXml(mimeType))
    val semi = type.indexOf(';')
    if (semi >= 0) type = trimXml(type.substring(0, semi))
    when (type) {
        "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3", "audio/x-mpeg" -> return "audio/mpeg"
        "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/x-m4b", "video/mp4", "audio/aacp", "video/x-m4v" -> return "audio/mp4"
        "audio/aac", "audio/x-aac" -> return "audio/aac"
        "audio/ogg", "audio/opus", "audio/x-ogg" -> return "audio/ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> return "audio/wav"
    }
    val ext = extensionOf(url)
    return TYPES.firstOrNull { it.first == ext }?.second ?: ""
}

fun mimeFor(e: Episode): String = mimeFor(e.mimeType, e.url)

private val RESERVED_NAMES = setOf(
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
)

/**
 * A file / folder name from any text: no reserved characters, names or trailing dots / spaces, at most [maxChars]
 * UTF-16 units. (Windows' device names stay reserved: the files may be copied to a PC or a FAT card.)
 */
fun sanitizeFileName(name: String, maxChars: Int = 100): String {
    val sb = StringBuilder(oneLine(name))
    for (k in sb.indices) {
        val c = sb[k]
        if (c.code < 32 || c in "\\/:*?\"<>|") sb.setCharAt(k, '_')
    }
    if (sb.length > maxChars) sb.setLength(maxChars)
    while (sb.isNotEmpty() && (sb[sb.length - 1] == ' ' || sb[sb.length - 1] == '.')) sb.setLength(sb.length - 1)
    while (sb.isNotEmpty() && sb[0] == ' ') sb.deleteCharAt(0)
    var w = sb.toString()
    val dot = w.indexOf('.')
    val base = (if (dot < 0) w else w.substring(0, dot)).uppercase(java.util.Locale.ROOT)
    if (base in RESERVED_NAMES) w = "_$w"
    if (w.isEmpty()) w = "_"
    return w
}

/**
 * <root>/Podcasts/<show>/<yyyy-mm-dd> <title>.<ext> (sanitized; the date is left out when unknown; ext by the media
 * type / URL, .mp3 by default).
 */
fun downloadPath(root: File, e: Episode): File {
    var ext = ""
    val mime = mimeFor(e)
    val urlExt = extensionOf(e.url)
    for ((x, m) in TYPES) if (x == urlExt && (mime.isEmpty() || m == mime)) ext = urlExt
    if (ext.isEmpty() && mime.isNotEmpty()) ext = TYPES.firstOrNull { it.second == mime }?.first ?: ""
    if (ext.isEmpty()) ext = "mp3"
    val sb = StringBuilder()
    if (e.published > 0) {
        val d = LocalDate.ofEpochDay(Math.floorDiv(e.published, 86400L))
        sb.append("%04d-%02d-%02d ".format(java.util.Locale.ROOT, d.year, d.monthValue, d.dayOfMonth))
    }
    sb.append(sanitizeFileName(e.title.ifEmpty { e.guid }, 100))
    val showDir = File(File(root, "Podcasts"), sanitizeFileName(e.show.ifEmpty { "Podcast" }, 80))
    return File(showDir, "$sb.$ext")
}
