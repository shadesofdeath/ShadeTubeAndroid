package com.app.shadetube.youtube.explode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Ports of YoutubeExplode/src/Bridge: raw response wrappers (player response, watch / channel pages, DASH manifest,
// search / playlist browse responses). Internal to the library.

// ---- Common ------------------------------------------------------------------------------------------------------

internal class ThumbnailData(val url: String?, val width: Int?, val height: Int?)

/** Reads a {"thumbnails": [...]} style array (or a bare array) of {url,width,height}. */
internal fun parseThumbnails(node: JsonElement?): List<ThumbnailData> {
    if (node == null) return emptyList()
    val array = if (node is JsonArray) node else dig(node, "thumbnails")
    if (array !is JsonArray) return emptyList()
    return array.map { ThumbnailData(jsonString(dig(it, "url")), jsonInt(dig(it, "width")), jsonInt(dig(it, "height"))) }
}

/**
 * Converts thumbnail data into public objects; entries missing url/size are skipped. Protocol-relative URLs
 * ("//i.ytimg.com/...") are made absolute.
 */
internal fun toThumbnails(data: List<ThumbnailData>): List<Thumbnail> = data.mapNotNull { t ->
    if (t.url.isNullOrEmpty() || t.width == null || t.height == null) return@mapNotNull null
    val url = if (t.url.startsWith("//")) "https:" + t.url else t.url
    Thumbnail(url, Resolution(t.width, t.height))
}

/** Parses "m:ss" / "h:mm:ss" text into milliseconds. */
internal fun parseClockText(text: String?): Long? {
    if (text == null) return null
    val seconds = Str.tryParseClockDuration(text) ?: return null
    return seconds * 1000
}

private val META_ATTRIBUTE = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

/** The `content` attribute of the first <meta> tag whose [attribute] equals [value]. */
internal fun findMetaContent(html: String, attribute: String, value: String): String? {
    var from = 0
    while (true) {
        val pos = html.indexOf("<meta", from)
        if (pos < 0) return null
        val end = html.indexOf('>', pos)
        if (end < 0) return null
        from = end
        val tag = html.substring(pos, end)
        if (!tag.contains(value)) continue
        var matchedValue: String? = null
        var content: String? = null
        for (m in META_ATTRIBUTE.findAll(tag)) {
            val name = Str.toLower(m.groupValues[1])
            val v = m.groups[2]?.value ?: m.groupValues[3]
            if (name == attribute) matchedValue = v
            else if (name == "content") content = Str.htmlDecode(v)
        }
        if (matchedValue == value && content != null) return content
    }
}

// ---- Stream data ---------------------------------------------------------------------------------------------------

/** Raw stream description, from a player response format or a DASH representation. */
internal class StreamData {
    var itag: Int? = null
    var url: String? = null
    /** "s" of signatureCipher */
    var signature: String? = null
    /** "sp" of signatureCipher (default "sig") */
    var signatureParameter: String? = null
    var contentLength: Long? = null
    var bitrate: Long? = null
    var mimeType: String? = null
    var container: String? = null
    var audioCodec: String? = null
    var videoCodec: String? = null
    var audioLanguageCode: String? = null
    var audioLanguageName: String? = null
    var isAudioLanguageDefault: Boolean? = null
    var audioSampleRate: Int? = null
    var audioChannels: Int? = null
    /** Integrated loudness relative to YouTube's reference level (dB; positive = louder). */
    var loudnessDb: Double? = null
    var videoQualityLabel: String? = null
    var videoWidth: Int? = null
    var videoHeight: Int? = null
    var videoFramerate: Int? = null
    var isVideoUpscaled = false
    /** True for DRC ("stable volume") audio variants. */
    var isDrc = false
}

/** Decodes a protobuf-encoded map<string,string> (used by the "xtags" field). */
internal fun tryDeserializeProtobufMap(data: ByteArray): Map<String, String>? {
    fun readVarint(pos: IntArray): Long? {
        var value = 0L
        var shift = 0
        while (pos[0] < data.size) {
            val b = data[pos[0]++].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
            if (shift >= 64) break
        }
        return null
    }

    val result = HashMap<String, String>()
    val i = intArrayOf(0)
    while (i[0] < data.size) {
        val tag = readVarint(i)
        if (tag == null || (tag and 0x7) != 2L) return null
        val length = readVarint(i)
        if (length == null || length < 0 || i[0] + length > data.size) return null
        val entryEnd = i[0] + length.toInt()
        var key: String? = null
        var value: String? = null
        val j = intArrayOf(i[0])
        while (j[0] < entryEnd) {
            val fieldTag = readVarint(j)
            if (fieldTag == null || (fieldTag and 0x7) != 2L) break
            val strLength = readVarint(j)
            if (strLength == null || strLength < 0 || j[0] + strLength > entryEnd) break
            val str = String(data, j[0], strLength.toInt(), Charsets.UTF_8)
            j[0] += strLength.toInt()
            when (fieldTag shr 3) {
                1L -> key = str
                2L -> value = str
            }
        }
        if (key != null) result[key] = value ?: ""
        i[0] = entryEnd
    }
    return result
}

internal fun parseStreamData(format: JsonElement): StreamData {
    val d = StreamData()
    d.itag = jsonInt(dig(format, "itag"))

    val cipher = jsonString(dig(format, "signatureCipher")) ?: jsonString(dig(format, "cipher"))
    d.url = jsonString(dig(format, "url"))
    if (cipher != null) {
        if (d.url == null) d.url = Url.tryGetQueryParameter(cipher, "url")
        d.signature = Url.tryGetQueryParameter(cipher, "s")
        d.signatureParameter = Url.tryGetQueryParameter(cipher, "sp")
    }

    d.contentLength = jsonInt64(dig(format, "contentLength"))
    if (d.contentLength == null && d.url != null)
        d.contentLength = Str.tryParseInt64(Url.tryGetQueryParameter(d.url!!, "clen") ?: "")

    d.bitrate = jsonInt64(dig(format, "bitrate"))
    d.mimeType = jsonString(dig(format, "mimeType"))
    d.mimeType?.let { mime ->
        d.container = Str.substringAfter(Str.substringUntil(mime, ";"), "/")
        val isAudioOnly = Str.toLower(mime).startsWith("audio/")
        val codecs = Str.substringUntil(Str.substringAfter(mime, "codecs=\""), "\"")
        if (isAudioOnly) {
            if (codecs.isNotEmpty()) d.audioCodec = codecs
        } else {
            var video = Str.trim(Str.substringUntil(codecs, ","))
            val audio = Str.trim(Str.substringAfter(codecs, ","))
            if (Str.iequals(video, "unknown")) video = "av01.0.05M.08" // "unknown" indicates av01
            if (video.isNotEmpty()) d.videoCodec = video
            if (audio.isNotEmpty()) d.audioCodec = audio
        }
    }

    jsonString(dig(format, "audioTrack", "id"))?.let { d.audioLanguageCode = Str.substringUntil(it, ".") }
    d.audioLanguageName = jsonString(dig(format, "audioTrack", "displayName"))
    d.isAudioLanguageDefault = jsonBool(dig(format, "audioTrack", "audioIsDefault"))
    d.audioSampleRate = jsonInt(dig(format, "audioSampleRate"))
    d.audioChannels = jsonInt(dig(format, "audioChannels"))
    d.loudnessDb = jsonDouble(dig(format, "loudnessDb"))
    d.isDrc = jsonBool(dig(format, "isDrc")) ?: false

    d.videoQualityLabel = jsonString(dig(format, "qualityLabel"))
    d.videoWidth = jsonInt(dig(format, "width"))
    d.videoHeight = jsonInt(dig(format, "height"))
    d.videoFramerate = jsonInt(dig(format, "fps"))

    // xtags is a base64 protobuf map<string,string>; Super Resolution streams carry {"sr": "1"}.
    val xtags = jsonString(dig(format, "xtags"))
    if (!xtags.isNullOrEmpty()) {
        Crypto.base64Decode(xtags)?.let { bytes ->
            tryDeserializeProtobufMap(bytes)?.let { map ->
                d.isVideoUpscaled = map["sr"] == "1"
                if (map["drc"] == "1") d.isDrc = true
            }
        }
    }
    return d
}

// ---- Player response -----------------------------------------------------------------------------------------------

/** Wrapper over the JSON returned by /youtubei/v1/player (or embedded in the watch page). */
internal class PlayerResponse(val json: JsonElement) {
    private val playability: JsonElement? get() = dig(json, "playabilityStatus")
    private val details: JsonElement? get() = dig(json, "videoDetails")

    val playabilityStatus: String? get() = jsonString(dig(playability, "status"))

    val playabilityError: String?
        get() = jsonString(dig(playability, "reason"))
            ?: jsonText(dig(playability, "errorScreen", "playerErrorMessageRenderer", "reason"))
            ?: jsonText(dig(playability, "errorScreen", "playerErrorMessageRenderer", "subreason"))

    val isAvailable: Boolean
        get() {
            val status = playabilityStatus
            return !(status != null && Str.iequals(status, "error")) && details != null
        }

    val isPlayable: Boolean get() = playabilityStatus?.let { Str.iequals(it, "ok") } ?: false

    val isAgeRestricted: Boolean
        get() {
            val status = Str.toLower(playabilityStatus ?: "")
            if (status == "age_check_required" || status == "age_verification_required") return true
            if (status != "login_required" && status != "unplayable" && status != "content_check_required") return false
            val reason = Str.toLower(playabilityError ?: "")
            if (reason.contains("your age") || reason.contains("age-restricted") || reason.contains("age restricted") ||
                reason.contains("inappropriate")
            ) return true
            val p = playability ?: return false
            return findFirstDescendant(p, "desktopLegacyAgeGateReason") != null
        }

    val title: String? get() = jsonString(dig(details, "title"))
    val channelId: String? get() = jsonString(dig(details, "channelId"))
    val author: String? get() = jsonString(dig(details, "author"))

    val uploadDate: Long?
        get() {
            for (key in listOf("uploadDate", "publishDate")) {
                val s = jsonString(dig(json, "microformat", "playerMicroformatRenderer", key)) ?: continue
                Iso8601.tryParse(s)?.let { return it }
            }
            return null
        }

    /** Milliseconds; null for live streams. */
    val duration: Long?
        get() {
            val seconds = jsonInt64(dig(details, "lengthSeconds"))
            if (seconds == null || isLive) return null
            return seconds * 1000
        }

    val thumbnails: List<ThumbnailData> get() = parseThumbnails(dig(details, "thumbnail"))

    val keywords: List<String>
        get() = (dig(details, "keywords") as? JsonArray)?.mapNotNull { jsonString(it) } ?: emptyList()

    val description: String? get() = jsonString(dig(details, "shortDescription"))
    val viewCount: Long? get() = jsonInt64(dig(details, "viewCount"))

    /** Currently live, or an upcoming premiere/stream. Finished streams (isLiveContent only) are VODs. */
    val isLive: Boolean
        get() = (jsonBool(dig(details, "isLive")) ?: false) || (jsonBool(dig(details, "isUpcoming")) ?: false)

    val previewVideoId: String?
        get() {
            jsonString(dig(playability, "errorScreen", "playerLegacyDesktopYpcTrailerRenderer", "trailerVideoId"))
                ?.let { return it }
            jsonString(dig(playability, "errorScreen", "ypcTrailerRenderer", "playerVars"))?.let { vars ->
                Url.tryGetQueryParameter(vars, "video_id")?.let { return it }
            }
            jsonString(dig(playability, "errorScreen", "ypcTrailerRenderer", "playerResponse"))?.let { encoded ->
                // Base64-like blob; enough of it decodes correctly to find the ID.
                Crypto.base64Decode(encoded)?.let { bytes ->
                    val text = String(bytes, Charsets.ISO_8859_1)
                    Regex("""video_id=(.{11})""").find(text)?.let { return it.groupValues[1] }
                }
            }
            return null
        }

    val dashManifestUrl: String? get() = jsonString(dig(json, "streamingData", "dashManifestUrl"))
    val hlsManifestUrl: String? get() = jsonString(dig(json, "streamingData", "hlsManifestUrl"))

    fun streams(): List<StreamData> {
        val result = ArrayList<StreamData>()
        for (key in listOf("formats", "adaptiveFormats")) {
            val array = dig(json, "streamingData", key) as? JsonArray ?: continue
            for (format in array) result.add(parseStreamData(format))
        }
        // Older / other clients only report the loudness once, for the whole video.
        jsonDouble(dig(json, "playerConfig", "audioConfig", "loudnessDb"))?.let { loudness ->
            for (d in result) if (d.loudnessDb == null) d.loudnessDb = loudness
        }
        return result
    }

    companion object {
        fun parse(raw: String) = PlayerResponse(parseJson(raw))
    }
}

// ---- Pages ---------------------------------------------------------------------------------------------------------

/**
 * Finds `<number> <suffix>` where the number is preceded by [before] (e.g. `"123,456 likes"`). Returns digits only.
 */
private fun findCountBefore(html: String, suffix: String, before: String): Long? {
    var from = 0
    while (true) {
        val pos = html.indexOf(suffix, from)
        if (pos < 0) return null
        from = pos + 1
        var start = pos
        while (start > 0 && (Str.isAsciiDigit(html[start - 1]) || html[start - 1] == ',' || html[start - 1] == '.')) --start
        if (start == pos) continue
        if (before.isNotEmpty() && (start < before.length || !html.startsWith(before, start - before.length))) continue
        Str.tryParseInt64(Str.stripNonDigits(html.substring(start, pos)))?.let { return it }
    }
}

/** youtube.com/watch?v=... HTML page. */
internal class VideoWatchPage private constructor(private val html: String) {
    val isAvailable: Boolean get() = findMetaContent(html, "property", "og:url") != null

    val uploadDate: Long?
        get() {
            for (key in listOf("uploadDate", "datePublished")) {
                val v = findMetaContent(html, "itemprop", key) ?: continue
                Iso8601.tryParse(v)?.let { return it }
            }
            return null
        }

    val likeCount: Long?
        get() = findCountBefore(html, " likes\"", "\"") ?: findCountBefore(html, " other people\"", "along with ")

    val dislikeCount: Long? get() = findCountBefore(html, " dislikes\"", "\"")

    val playerResponse: PlayerResponse?
        get() {
            var from = 0
            while (true) {
                val pos = html.indexOf("ytInitialPlayerResponse", from)
                if (pos < 0) return null
                from = pos + 1
                var k = pos + "ytInitialPlayerResponse".length
                while (k < html.length && html[k] == ' ') ++k
                if (k >= html.length || html[k] != '=') continue
                ++k
                while (k < html.length && html[k] == ' ') ++k
                if (k >= html.length || html[k] != '{') continue
                val json = tryParseJson(extractJson(html.substring(k)))
                if (json is JsonObject) return PlayerResponse(json)
            }
        }

    companion object {
        fun tryParse(html: String): VideoWatchPage? {
            if (!html.contains("id=\"player\"") && !html.contains("id=\"player-api\"") &&
                !html.contains("ytInitialPlayerResponse")
            ) return null
            return VideoWatchPage(html)
        }
    }
}

/** youtube.com/channel/..., /@handle, /c/..., /user/... HTML page. */
internal class ChannelPage private constructor(private val html: String) {
    val url: String? get() = findMetaContent(html, "property", "og:url")

    val id: String?
        get() {
            val u = url ?: return null
            val pos = Str.toLower(u).indexOf("channel/")
            if (pos < 0) return null
            return Str.substringUntil(u.substring(pos + 8), "?")
        }

    val title: String? get() = findMetaContent(html, "property", "og:title")
    val logoUrl: String? get() = findMetaContent(html, "property", "og:image")

    companion object {
        fun tryParse(html: String): ChannelPage? =
            if (findMetaContent(html, "property", "og:url") == null) null else ChannelPage(html)
    }
}

// ---- DASH manifest -------------------------------------------------------------------------------------------------

private val CLEN_PATTERN = Regex("""[/?]clen[/=](\d+)""")
private val MIME_PATTERN = Regex("""mime[/=]\w*%2F([\w\d]*)""")

/** Streams listed in a DASH (MPD) manifest. */
internal fun parseDashManifest(xml: String): List<StreamData> {
    val result = ArrayList<StreamData>()
    val root = Xml.parse(xml)
    for (rep in root.descendants("Representation")) {
        val id = rep.attribute("id") ?: ""
        // Skip non-media representations (like "rawcc")
        if (id.isEmpty() || !id.all { Str.isAsciiDigit(it) }) continue
        // Skip segmented streams
        val inits = rep.descendants("Initialization")
        if (inits.isNotEmpty() && (inits[0].attribute("sourceURL") ?: "").contains("sq/")) continue
        val codecs = rep.attribute("codecs")
        if (codecs == null || Str.isBlank(codecs)) continue

        val d = StreamData()
        d.itag = Str.tryParseInt(id)
        rep.element("BaseURL")?.let { d.url = Str.trim(it.innerText()) }
        d.contentLength = Str.tryParseInt64(rep.attribute("contentLength") ?: "")
        val url = d.url
        if (d.contentLength == null && url != null) CLEN_PATTERN.find(url)?.let { d.contentLength = Str.tryParseInt64(it.groupValues[1]) }
        d.bitrate = Str.tryParseInt64(rep.attribute("bandwidth") ?: "")
        if (url != null) MIME_PATTERN.find(url)?.let { d.container = Url.decode(it.groupValues[1]) }
        val channelConfig = rep.element("AudioChannelConfiguration")
        val isAudioOnly = channelConfig != null
        if (isAudioOnly) {
            d.audioCodec = codecs
            d.audioSampleRate = Str.tryParseInt(rep.attribute("audioSamplingRate") ?: "")
            d.audioChannels = Str.tryParseInt(channelConfig!!.attribute("value") ?: "")
        } else {
            d.videoCodec = codecs
        }
        d.videoWidth = Str.tryParseInt(rep.attribute("width") ?: "")
        d.videoHeight = Str.tryParseInt(rep.attribute("height") ?: "")
        d.videoFramerate = Str.tryParseInt(rep.attribute("frameRate") ?: "")
        d.container?.let { d.mimeType = (if (isAudioOnly) "audio/" else "video/") + it + "; codecs=\"" + codecs + "\"" }
        result.add(d)
    }
    return result
}

// ---- Search / playlist browse responses ----------------------------------------------------------------------------

internal class SearchVideoData(
    val id: String?, val title: String?, val author: String?, val channelId: String?,
    val duration: Long?, val thumbnails: List<ThumbnailData>,
)

internal class SearchPlaylistData(
    val id: String?, val title: String?, val author: String?, val channelId: String?, val thumbnails: List<ThumbnailData>,
)

internal class SearchChannelData(val id: String?, val title: String?, val thumbnails: List<ThumbnailData>)

private fun firstRun(text: JsonElement?) = dig(text, "runs", 0)
private fun browseId(node: JsonElement?) = jsonString(dig(node, "navigationEndpoint", "browseEndpoint", "browseId"))

private fun parseCount(text: String?): Int? {
    if (text == null) return null
    val first = Str.substringUntil(Str.trim(text), " ")
    val digits = Str.stripNonDigits(first)
    if (digits.isEmpty() || digits.length != first.replace(",", "").replace(".", "").length) return null
    return Str.tryParseInt(digits)
}

internal class SearchResponse(
    val videos: List<SearchVideoData>,
    val playlists: List<SearchPlaylistData>,
    val channels: List<SearchChannelData>,
    val continuationToken: String?,
) {
    companion object {
        fun parse(raw: String): SearchResponse {
            val json = parseJson(raw)
            // The search response is incredibly inconsistent (at least 5 layouts), so rely on descendant searches,
            // which are slower but resilient.
            val root = dig(json, "contents") ?: dig(json, "onResponseReceivedCommands")
                ?: return SearchResponse(emptyList(), emptyList(), emptyList(), null)

            val videos = findDescendants(root, "videoRenderer").map { v ->
                val authorRun = firstRun(dig(v, "longBylineText")) ?: firstRun(dig(v, "shortBylineText"))
                SearchVideoData(
                    id = jsonString(dig(v, "videoId")),
                    title = jsonText(dig(v, "title")),
                    author = jsonString(dig(authorRun, "text")),
                    channelId = browseId(authorRun)
                        ?: browseId(dig(v, "channelThumbnailSupportedRenderers", "channelThumbnailWithLinkRenderer")),
                    duration = parseClockText(jsonText(dig(v, "lengthText"))),
                    thumbnails = parseThumbnails(dig(v, "thumbnail")),
                )
            }

            val playlists = ArrayList<SearchPlaylistData>()
            for (p in findDescendants(root, "lockupViewModel")) {
                val contentType = jsonString(dig(p, "contentType")) ?: ""
                if (contentType.isNotEmpty() && !contentType.contains("PLAYLIST")) continue
                val metadata = dig(p, "metadata", "lockupMetadataViewModel")
                var author: String? = null
                var channelId: String? = null
                if (metadata != null) {
                    findFirstDescendant(metadata, "metadataParts")?.let { parts ->
                        val text = dig(parts, 0, "text")
                        author = jsonString(dig(text, "content"))
                        channelId = jsonString(dig(text, "commandRuns", 0, "onTap", "innertubeCommand", "browseEndpoint", "browseId"))
                    }
                }
                var thumbs = parseThumbnails(
                    dig(p, "contentImage", "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel", "image", "sources"),
                )
                if (thumbs.isEmpty()) thumbs = parseThumbnails(dig(p, "contentImage", "thumbnailViewModel", "image", "sources"))
                playlists.add(
                    SearchPlaylistData(jsonString(dig(p, "contentId")), jsonString(dig(metadata, "title", "content")), author, channelId, thumbs),
                )
            }

            for (p in findDescendants(root, "playlistRenderer")) {
                val authorRun = firstRun(dig(p, "longBylineText"))
                val thumbs = ArrayList<ThumbnailData>()
                dig(p, "thumbnails")?.let { t -> for (x in findDescendants(t, "thumbnails")) thumbs.addAll(parseThumbnails(x)) }
                playlists.add(
                    SearchPlaylistData(
                        jsonString(dig(p, "playlistId")), jsonText(dig(p, "title")),
                        jsonString(dig(authorRun, "text")), browseId(authorRun), thumbs,
                    ),
                )
            }

            val channels = findDescendants(root, "channelRenderer").map { c ->
                SearchChannelData(jsonString(dig(c, "channelId")), jsonText(dig(c, "title")), parseThumbnails(dig(c, "thumbnail")))
            }

            val token = findFirstDescendant(root, "continuationCommand")?.let { jsonString(dig(it, "token")) }
            return SearchResponse(videos, playlists, channels, token)
        }
    }
}

internal class PlaylistVideoData(
    val index: Int?, val id: String?, val title: String?, val author: String?, val channelId: String?,
    val duration: Long?, val thumbnails: List<ThumbnailData>,
)

/** Common view over /browse (rich metadata, user playlists only) and /next (all playlists). */
internal class PlaylistData(
    val isAvailable: Boolean = false,
    val title: String? = null,
    val author: String? = null,
    val channelId: String? = null,
    val description: String? = null,
    val count: Int? = null,
    val thumbnails: List<ThumbnailData> = emptyList(),
)

internal fun parsePlaylistBrowseResponse(raw: String): PlaylistData {
    val json = parseJson(raw)
    val sidebar = dig(json, "sidebar", "playlistSidebarRenderer", "items") ?: return PlaylistData(isAvailable = false)
    val primary = dig(sidebar, 0, "playlistSidebarPrimaryInfoRenderer")
    val secondary = dig(sidebar, 1, "playlistSidebarSecondaryInfoRenderer")

    val title = jsonText(dig(primary, "title"))
        ?: jsonString(dig(primary, "titleForm", "inlineFormRenderer", "formField", "textInputFormFieldRenderer", "value"))
    val owner = dig(secondary, "videoOwner", "videoOwnerRenderer")
    val author = jsonText(dig(owner, "title"))
    val channelId = browseId(owner) ?: browseId(firstRun(dig(owner, "title")))
    val description = jsonText(dig(primary, "description"))
        ?: jsonString(dig(primary, "descriptionForm", "inlineFormRenderer", "formField", "textInputFormFieldRenderer", "value"))
    val count = parseCount(jsonText(dig(primary, "stats", 0)))
    var thumbs = parseThumbnails(dig(primary, "thumbnailRenderer", "playlistVideoThumbnailRenderer", "thumbnail"))
    if (thumbs.isEmpty()) thumbs = parseThumbnails(dig(primary, "thumbnailRenderer", "playlistCustomThumbnailRenderer", "thumbnail"))
    return PlaylistData(true, title, author, channelId, description, count, thumbs)
}

internal class PlaylistNextResponse(
    val playlist: PlaylistData,
    val videos: List<PlaylistVideoData>,
    val visitorData: String?,
) {
    companion object {
        fun parse(raw: String): PlaylistNextResponse {
            val json = parseJson(raw)
            val root = dig(json, "contents", "twoColumnWatchNextResults", "playlist", "playlist")
            val visitorData = jsonString(dig(json, "responseContext", "visitorData"))
            if (root == null) return PlaylistNextResponse(PlaylistData(isAvailable = false), emptyList(), visitorData)

            val videos = ArrayList<PlaylistVideoData>()
            (dig(root, "contents") as? JsonArray)?.forEach { item ->
                val v = dig(item, "playlistPanelVideoRenderer") ?: return@forEach
                val authorRun = firstRun(dig(v, "longBylineText")) ?: firstRun(dig(v, "shortBylineText"))
                // Videos with multiple authors: take the uploader (first entry of the dialog).
                val channelId = browseId(authorRun) ?: jsonString(
                    dig(
                        authorRun, "navigationEndpoint", "showDialogCommand", "panelLoadingStrategy", "inlineContent",
                        "dialogViewModel", "customContent", "listViewModel", "listItems", 0,
                        "listItemViewModel", "rendererContext", "commandContext", "onTap",
                        "innertubeCommand", "browseEndpoint", "browseId",
                    ),
                )
                val duration = jsonInt64(dig(v, "lengthSeconds"))?.let { it * 1000 }
                    ?: parseClockText(jsonText(dig(v, "lengthText")))
                videos.add(
                    PlaylistVideoData(
                        index = jsonInt(dig(v, "navigationEndpoint", "watchEndpoint", "index")),
                        id = jsonString(dig(v, "videoId")),
                        title = jsonText(dig(v, "title")),
                        author = jsonString(dig(authorRun, "text")),
                        channelId = channelId,
                        duration = duration,
                        thumbnails = parseThumbnails(dig(v, "thumbnail")),
                    ),
                )
            }
            val count = parseCount(jsonString(dig(root, "totalVideosText", "runs", 0, "text")))
                ?: parseCount(jsonString(dig(root, "videoCountText", "runs", 2, "text")))
            val playlist = PlaylistData(
                isAvailable = true,
                title = jsonString(dig(root, "title")),
                author = jsonText(dig(root, "ownerName")),
                count = count,
                thumbnails = videos.firstOrNull()?.thumbnails ?: emptyList(),
            )
            return PlaylistNextResponse(playlist, videos, visitorData)
        }
    }
}
