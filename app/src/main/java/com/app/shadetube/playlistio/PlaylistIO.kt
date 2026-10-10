package com.app.shadetube.playlistio

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.arr
import com.app.shadetube.core.asString
import com.app.shadetube.core.bool
import com.app.shadetube.core.dump
import com.app.shadetube.core.get
import com.app.shadetube.core.has
import com.app.shadetube.core.int
import com.app.shadetube.core.isArray
import com.app.shadetube.core.isObject
import com.app.shadetube.core.isString
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.long
import com.app.shadetube.core.parseJsonOrNull
import com.app.shadetube.core.str
import com.app.shadetube.links.LinkKind
import com.app.shadetube.links.Links
import com.app.shadetube.localfiles.LocalPaths
import com.app.shadetube.lyrics.LocalLyrics
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class PlaylistFormat { M3U, CSV, XSPF, JSON }

/** A song to write: its local file (a download / local file: path or URI, "" = none) and matched video when known. */
data class ExportRow(
    val track: Track,
    val filePath: String = "",
    val videoId: String = "",
)

/** A song a playlist file names. */
data class ImportEntry(
    /** Id per the file (see [PlaylistIO]); "" while [localPath] still has to be read. */
    val track: Track,
    /** A local file to read (absolute path, a path written on another PC, or a content URI); its tags then replace the names. */
    val localPath: String = "",
    /** 1-based line (M3U) / row (CSV, counting the header) / track number (XSPF, JSON). */
    val line: Int = 0,
)

/** A row that names no song (no title, no id, a link to an album...), for the preview's list. */
data class ImportProblem(val line: Int, val text: String)

data class ParsedPlaylist(
    val format: PlaylistFormat = PlaylistFormat.CSV,
    /** CSV: "shadetube" | "exportify" | "tunemymusic" | "soundiiz" | "generic" | "lines" (no header we know). */
    val dialect: String = "",
    /** The list's own name (#PLAYLIST, <title>, JSON "name"), "" = none. */
    val name: String = "",
    val entries: List<ImportEntry> = emptyList(),
    val unresolved: List<ImportProblem> = emptyList(),
)

/**
 * Playlist files (port of app/PlaylistIO): writing a list of songs as M3U8 / CSV / XSPF / ShadeTube JSON, and reading
 * those formats (plus the CSV of common Spotify exporters) back into songs. Pure (no UI, no network): the flows and
 * the resolving of what a file names are [PlaylistTransfer]. Input / output are streams (the UI uses SAF pickers).
 *
 * Writing
 *  - M3U8: #EXTM3U, #PLAYLIST, per song #EXTINF:<seconds>,<artists> - <title> (+ #EXTALB) and its location: the local
 *    file when there is one, else its Spotify / MusicBrainz / YouTube URL (a YouTube Music search URL when nothing
 *    better is known).
 *  - CSV: UTF-8 with a BOM, comma separated, CRLF (Excel opens it as is): Title, Artists (joined with "; "), Album,
 *    Duration (m:ss), Duration (ms), Spotify URI, MusicBrainz ID, YouTube video ID, Added at (ISO 8601 UTC), File.
 *    Cells a spreadsheet would run as a formula (= + - @ first) get a leading apostrophe.
 *  - XSPF: location (file:/// URI or URL), identifier, title, creator, album, duration.
 *  - JSON: ShadeTube's own format ("shadetube.playlist" v1): every field a song has, so a list round-trips exactly.
 *
 * Reading (UTF-8 with or without a BOM, UTF-16 with a BOM, else the ANSI code page of the device language)
 *  - M3U / M3U8: local paths (absolute, relative to the playlist's folder, file:// URIs; Android: content:// URIs)
 *    and Spotify / YouTube / MusicBrainz links; #EXTINF gives the title / artists / duration.
 *  - CSV: delimiter auto-detected (, ; tab), quotes, a header row mapped by name: ShadeTube's, Exportify's ("Track
 *    URI", "Track Name", "Artist Name(s)", "Album Name", "Duration (ms)" / "Track Duration (ms)", "Added At"),
 *    TuneMyMusic's ("Track name", "Artist name", "Album", "Spotify - id"), Soundiiz's ("title", "artist", "album")
 *    and similar generic headers; without one, "Artist - Title" lines.
 *  - XSPF, ShadeTube JSON.
 * A row becomes a song with the best id it names: spotify:track:<id>, a MusicBrainz recording, "yt:<video>" (the
 * video pinned as the source), a local file (read later), else "import:<hash of title + artist>" (matched on YouTube
 * by name, like the stats history import). Rows that name no song are reported with their line number.
 *
 * Paths keep forward slashes on Android ("C:\Music\a.mp3" from a Windows list reads as "C:/Music/a.mp3").
 */
object PlaylistIO {
    private const val SPOTIFY_TRACK = "spotify:track:"
    private const val MAX_FILE_BYTES = 64 shl 20

    // ---- small helpers --------------------------------------------------------------------------------------------
    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'
    private fun trim(s: String) = s.trim { isSpace(it) }
    private fun lowerAscii(s: String) = buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') c + 32 else c) }
    private fun iStartsWith(s: String, p: String) = s.length >= p.length && s.regionMatches(0, p, 0, p.length, ignoreCase = true)

    private fun artistNames(t: Track, sep: String) = t.artists.filter { it.name.isNotEmpty() }.joinToString(sep) { it.name }

    /** "Artist A, Artist B - Title" (the usual #EXTINF text), "Title" without artists. */
    private fun displayLine(t: Track): String {
        val a = artistNames(t, ", ")
        return if (a.isEmpty()) t.name else "$a - ${t.name}"
    }

    private fun mmss(ms: Int): String {
        if (ms <= 0) return ""
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    private fun isoTime(unix: Long): String = if (unix <= 0) "" else runCatching { ISO.format(Instant.ofEpochSecond(unix)) }.getOrDefault("")

    private fun xmlEscape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            // XML 1.0 has no place for most control characters.
            else -> if (c.code >= 0x20 || c == '\t' || c == '\n' || c == '\r') append(c)
        }
    }

    private fun percentEncode(s: String, keepSlash: Boolean): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '-' || ch == '_' || ch == '.' || ch == '~' ||
                (keepSlash && (ch == '/' || ch == ':'))
            ) sb.append(ch) else sb.append('%').append(hex[c shr 4]).append(hex[c and 15])
        }
        return sb.toString()
    }

    private fun videoOf(r: ExportRow): String = when {
        r.videoId.isNotEmpty() -> r.videoId
        r.track.videoId.isNotEmpty() -> r.track.videoId
        r.track.id.startsWith("yt:") -> r.track.id.substring(3)
        else -> ""
    }

    /** A song known by name only (no id at all). */
    private fun nameOnly(title: String, artist: String): Track =
        Track(id = importId(title, artist), name = title, artists = if (artist.isEmpty()) emptyList() else listOf(ArtistRef("", artist)))

    /** "Artist - Title" (M3U #EXTINF, a bare text line) -> artist, title. Without " - " the whole text is the title. */
    private fun splitDisplay(text: String): Pair<String, String> {
        val s = trim(text)
        val dash = s.indexOf(" - ")
        if (dash < 0) return "" to s
        return trim(s.substring(0, dash)) to trim(s.substring(dash + 3))
    }

    /** Applies what a link names to `t` (a song only). null for links to something else (an album, a playlist...). */
    private fun applyLink(text: String, t: Track): Track? {
        val l = Links.parse(text)
        return when (l.kind) {
            LinkKind.SpotifyTrack -> t.copy(id = SPOTIFY_TRACK + l.id)
            LinkKind.YouTubeVideo -> t.copy(id = "yt:" + l.id, videoId = l.id)
            LinkKind.MbRecording -> t.copy(id = l.id)
            else -> null
        }
    }

    /** A path relative to the playlist's folder made absolute (when the folder is known), normalized. */
    private fun resolvePath(text: String, baseDir: String): String {
        if (text.startsWith("content://")) return text
        val p = text.replace('\\', '/')
        return if (LocalPaths.isAbsolute(p) || baseDir.isEmpty()) LocalPaths.normalizePath(p) else LocalPaths.normalizePath("$baseDir/$p")
    }

    private fun stemOf(path: String): String {
        val n = LocalPaths.nameOf(path)
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) else n
    }

    private fun shorten(s: String): String {
        if (s.length <= 120) return s
        var cut = 117
        if (Character.isHighSurrogate(s[cut - 1])) cut-- // whole characters
        return s.substring(0, cut) + "..."
    }

    /** C atoi: leading spaces, a sign, digits ("-1 tvg-id=..." -> -1); 0 when there are none. */
    private fun atoi(s: String): Int {
        var i = 0
        while (i < s.length && isSpace(s[i])) i++
        var neg = false
        if (i < s.length && (s[i] == '+' || s[i] == '-')) neg = s[i++] == '-'
        var v = 0L
        while (i < s.length && s[i] in '0'..'9' && v < Int.MAX_VALUE) v = v * 10 + (s[i++] - '0')
        return (if (neg) -v else v).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    // ---- M3U ------------------------------------------------------------------------------------------------------
    private fun parseM3u(text: String, baseDir: String): ParsedPlaylist {
        var name = ""
        val entries = ArrayList<ImportEntry>()
        val unresolved = ArrayList<ImportProblem>()
        var seconds = -1
        var display = ""
        var album = ""
        var infoArtist = ""
        fun reset() { seconds = -1; display = ""; album = ""; infoArtist = "" }
        var lineNo = 0
        var pos = 0
        while (pos <= text.length) {
            var nl = text.indexOf('\n', pos)
            if (nl < 0) nl = text.length
            val line = trim(text.substring(pos, nl))
            pos = nl + 1
            lineNo++
            if (line.isEmpty()) continue
            if (line[0] == '#') {
                when {
                    iStartsWith(line, "#EXTINF:") -> {
                        val rest = line.substring(8)
                        val comma = rest.indexOf(',')
                        seconds = atoi(if (comma < 0) rest else rest.substring(0, comma)) // "-1 tvg-id=..." -> -1
                        display = if (comma < 0) "" else trim(rest.substring(comma + 1))
                    }
                    iStartsWith(line, "#PLAYLIST:") -> name = trim(line.substring(10))
                    iStartsWith(line, "#EXTALB:") -> album = trim(line.substring(8))
                    iStartsWith(line, "#EXTART:") -> infoArtist = trim(line.substring(8))
                }
                continue
            }
            var (artist, title) = splitDisplay(display)
            if (infoArtist.isNotEmpty()) artist = infoArtist
            var t = Track(
                name = title,
                artists = if (artist.isEmpty()) emptyList() else listOf(ArtistRef("", artist)),
                album = AlbumRef(name = album),
                durationMs = if (seconds > 0) seconds * 1000 else 0,
            )
            var localPath = ""
            val uri = line.contains("://") || iStartsWith(line, "spotify:")
            when {
                iStartsWith(line, "file:") -> localPath = pathFromFileUri(line)
                line.startsWith("content://") -> localPath = line // Android: a document another app listed
                uri -> {
                    val linked = applyLink(line, t)
                    if (linked != null) {
                        t = linked
                    } else {
                        // A link to an album / playlist / artist is no song; any other URL (a stream, a search) is,
                        // by the #EXTINF name.
                        val link = Links.parse(line)
                        val other = link.isLink && link.kind != LinkKind.Unsupported
                        if (other || t.name.isEmpty()) {
                            unresolved += ImportProblem(lineNo, shorten(line))
                            reset()
                            continue
                        }
                        t = nameOnly(t.name, artist).copy(album = AlbumRef(name = album), durationMs = if (seconds > 0) seconds * 1000 else 0)
                    }
                }
                else -> localPath = resolvePath(line, baseDir)
            }
            if (localPath.isNotEmpty() && t.name.isEmpty()) t = t.copy(name = stemOf(localPath))
            entries += ImportEntry(t, localPath, lineNo)
            reset()
        }
        return ParsedPlaylist(PlaylistFormat.M3U, "", name, entries, unresolved)
    }

    // ---- CSV ------------------------------------------------------------------------------------------------------
    private fun findColumn(header: List<String>, vararg names: String): Int {
        for (n in names) for ((i, h) in header.withIndex()) if (h == n) return i
        return -1
    }

    /** A cell as written: ShadeTube's formula guard ("'=...") comes off. */
    private fun cellText(row: List<String>, col: Int): String {
        if (col < 0 || col >= row.size) return ""
        val v = trim(row[col])
        if (v.length >= 2 && v[0] == '\'' && (v[1] == '=' || v[1] == '+' || v[1] == '-' || v[1] == '@')) return v.substring(1)
        return v
    }

    private fun splitArtists(s: String, sep: Char): List<String> = s.split(sep).map { trim(it) }.filter { it.isNotEmpty() }

    private fun parseCsv(text: String, baseDir: String): ParsedPlaylist {
        val rows = readCsv(text)
        if (rows.isEmpty()) return ParsedPlaylist(PlaylistFormat.CSV)
        val header = rows[0].map { lowerAscii(trim(it)) }
        val cTitle = findColumn(header, "track name", "title", "name", "song", "song name", "track title", "track")
        val cArtist = findColumn(header, "artist name(s)", "artist name", "artists", "artist", "artist(s)", "creator")
        val cAlbum = findColumn(header, "album name", "album", "album title", "release")
        val cDurMs = findColumn(header, "duration (ms)", "track duration (ms)", "duration_ms", "length (ms)")
        val cDur = findColumn(header, "duration", "length", "time")
        val cSpotify = findColumn(
            header, "spotify uri", "track uri", "uri", "spotify - id", "spotify id", "spotify track id", "spotify url", "spotify link",
        )
        val cMbid = findColumn(header, "musicbrainz id", "mbid", "recording mbid", "musicbrainz recording id")
        val cYouTube = findColumn(header, "youtube video id", "youtube id", "video id", "youtube - id", "youtube")
        val cUrl = findColumn(header, "url", "link")
        val cAdded = findColumn(header, "added at", "added", "date added")
        val cFile = findColumn(header, "file", "path", "location")
        val hasHeader = cTitle >= 0 || cSpotify >= 0 || cYouTube >= 0 || cUrl >= 0 || cFile >= 0 || cMbid >= 0
        var artistSep = ';'
        val dialect = when {
            !hasHeader -> "lines"
            cSpotify >= 0 && cYouTube >= 0 && header[cSpotify] == "spotify uri" -> "shadetube"
            header[maxOf(cArtist, 0)] == "artist name(s)" || findColumn(header, "track uri") >= 0 -> {
                artistSep = ',' // Exportify joins the credits with commas
                "exportify"
            }
            findColumn(header, "spotify - id", "playlist name") >= 0 || (cTitle >= 0 && header[cTitle] == "track name") -> "tunemymusic"
            cTitle >= 0 && header[cTitle] == "title" && cArtist >= 0 && header[cArtist] == "artist" -> "soundiiz"
            else -> "generic"
        }
        val entries = ArrayList<ImportEntry>()
        val unresolved = ArrayList<ImportProblem>()
        for (r in (if (hasHeader) 1 else 0) until rows.size) {
            val row = rows[r]
            val lineNo = r + 1
            var t = Track()
            var artists: List<String>
            if (!hasHeader) { // no header we know: each row "Artist - Title" (a plain list of songs)
                val (artist, title) = splitDisplay(row.firstOrNull() ?: "")
                t = t.copy(name = title)
                artists = if (artist.isEmpty()) emptyList() else listOf(artist)
            } else {
                val ms = cellText(row, cDurMs)
                t = t.copy(
                    name = cellText(row, cTitle),
                    album = AlbumRef(name = cellText(row, cAlbum)),
                    durationMs = if (ms.isNotEmpty()) parseDurationMs(ms, true) else parseDurationMs(cellText(row, cDur), false),
                    addedAt = parseIsoTime(cellText(row, cAdded)),
                )
                artists = splitArtists(cellText(row, cArtist), artistSep)
            }
            t = t.copy(artists = artists.map { ArtistRef("", it) })
            // The best id the row names: Spotify, MusicBrainz, a YouTube video, a link, a file, else the name.
            var id = ""
            val sp = cellText(row, cSpotify)
            if (sp.isNotEmpty()) {
                if (sp.startsWith(SPOTIFY_TRACK) && Links.isSpotifyId(sp.substring(SPOTIFY_TRACK.length))) id = sp
                else if (Links.isSpotifyId(sp)) id = SPOTIFY_TRACK + sp
                else Links.parse(sp).takeIf { it.kind == LinkKind.SpotifyTrack }?.let { id = SPOTIFY_TRACK + it.id }
            }
            if (id.isEmpty()) cellText(row, cMbid).takeIf { Links.isMbid(it) }?.let { id = lowerAscii(it) }
            if (id.isEmpty()) {
                val yt = cellText(row, cYouTube)
                if (Links.isVideoId(yt)) {
                    id = "yt:$yt"
                    t = t.copy(videoId = yt)
                } else if (yt.isNotEmpty()) {
                    applyLink(yt, Track())?.let { probe ->
                        id = probe.id
                        t = t.copy(videoId = probe.videoId)
                    }
                }
            } else {
                val yt = cellText(row, cYouTube)
                if (Links.isVideoId(yt)) t = t.copy(videoId = yt) // keeps the matched video of a Spotify / MusicBrainz song
            }
            if (id.isEmpty()) {
                val url = cellText(row, cUrl)
                if (url.isNotEmpty()) applyLink(url, Track())?.let { probe ->
                    id = probe.id
                    if (probe.videoId.isNotEmpty()) t = t.copy(videoId = probe.videoId)
                }
            }
            var localPath = ""
            if (id.isEmpty()) {
                val file = cellText(row, cFile)
                if (file.isNotEmpty()) {
                    localPath = if (iStartsWith(file, "file:")) pathFromFileUri(file) else resolvePath(file, baseDir)
                    if (t.name.isEmpty()) t = t.copy(name = stemOf(localPath))
                }
            }
            if (id.isEmpty() && localPath.isEmpty()) {
                if (t.name.isEmpty()) {
                    val raw = row.filter { it.isNotEmpty() }.joinToString(", ")
                    if (raw.isNotEmpty()) unresolved += ImportProblem(lineNo, shorten(raw))
                    continue
                }
                id = importId(t.name, t.artists.firstOrNull()?.name ?: "")
            }
            entries += ImportEntry(t.copy(id = id), localPath, lineNo)
        }
        return ParsedPlaylist(PlaylistFormat.CSV, dialect, "", entries, unresolved)
    }

    // ---- XSPF -----------------------------------------------------------------------------------------------------
    private fun xmlDecode(s: String): String {
        val r = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s.startsWith("<![CDATA[", i)) {
                val end = s.indexOf("]]>", i + 9)
                val stop = if (end < 0) s.length else end
                r.append(s, i + 9, stop)
                i = if (end < 0) s.length else end + 3
                continue
            }
            if (s[i] == '&') {
                val semi = s.indexOf(';', i)
                if (semi >= 0 && semi - i <= 10) {
                    val ent = s.substring(i + 1, semi)
                    var out = when (ent) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos" -> "'"
                        else -> ""
                    }
                    if (out.isEmpty() && ent.startsWith("#")) {
                        val cp = if (ent.length > 1 && (ent[1] == 'x' || ent[1] == 'X')) ent.substring(2).toLongOrNull(16) else ent.substring(1).toLongOrNull()
                        if (cp != null && cp > 0 && cp < 0x110000) out = String(Character.toChars(cp.toInt()))
                    }
                    if (out.isNotEmpty()) {
                        r.append(out)
                        i = semi + 1
                        continue
                    }
                }
            }
            r.append(s[i++])
        }
        return trim(r.toString())
    }

    /** The text of the first <tag>...</tag> in `block` ("" when absent or empty). */
    private fun xmlChild(block: String, tag: String): String {
        val open = "<$tag"
        var at = 0
        while (true) {
            at = block.indexOf(open, at)
            if (at < 0) return ""
            val after = at + open.length
            if (after < block.length && (block[after] == '>' || block[after] == ' ' || block[after] == '/' || block[after] == '\t')) break
            at = after
        }
        val gt = block.indexOf('>', at)
        if (gt < 0 || block[gt - 1] == '/') return ""
        val close = block.indexOf("</$tag", gt)
        if (close < 0) return ""
        return xmlDecode(block.substring(gt + 1, close))
    }

    private fun parseXspf(text: String): ParsedPlaylist {
        val list = text.indexOf("<trackList")
        val name = xmlChild(if (list < 0) text else text.substring(0, list), "title")
        val entries = ArrayList<ImportEntry>()
        val unresolved = ArrayList<ImportProblem>()
        var at = if (list < 0) 0 else list
        var n = 0
        while (true) {
            at = text.indexOf("<track", at)
            if (at < 0) break
            val after = at + 6
            if (after >= text.length || (text[after] != '>' && text[after] != ' ')) { // <trackList>, <trackNum>
                at = after
                continue
            }
            val end = text.indexOf("</track>", after)
            if (end < 0) break
            val block = text.substring(after, end)
            at = end + 8
            n++
            val creator = xmlChild(block, "creator")
            var t = Track(
                name = xmlChild(block, "title"),
                artists = if (creator.isEmpty()) emptyList() else listOf(ArtistRef("", creator)),
                album = AlbumRef(name = xmlChild(block, "album")),
                durationMs = parseDurationMs(xmlChild(block, "duration"), true),
            )
            val location = xmlChild(block, "location")
            val identifier = xmlChild(block, "identifier")
            var named = false
            for (ref in listOf(identifier, location)) {
                if (ref.isEmpty() || named) continue
                if (iStartsWith(ref, "file:")) continue
                applyLink(ref, t)?.let { t = it; named = true }
            }
            var localPath = ""
            if (!named && iStartsWith(location, "file:")) localPath = pathFromFileUri(location)
            else if (!named && location.startsWith("content://")) localPath = location
            else if (!named && location.isNotEmpty() && !location.contains("://")) localPath = location.replace('\\', '/')
            if (!named && localPath.isEmpty()) {
                if (t.name.isEmpty()) {
                    unresolved += ImportProblem(n, shorten(location.ifEmpty { identifier }))
                    continue
                }
                t = t.copy(id = importId(t.name, t.artists.firstOrNull()?.name ?: ""))
            }
            if (localPath.isNotEmpty() && t.name.isEmpty()) t = t.copy(name = stemOf(localPath))
            entries += ImportEntry(t, localPath, n)
        }
        return ParsedPlaylist(PlaylistFormat.XSPF, "", name, entries, unresolved)
    }

    // ---- JSON -----------------------------------------------------------------------------------------------------
    private fun parseJson(text: String): ParsedPlaylist {
        val j = parseJsonOrNull(text)
        var name = ""
        val tracks: List<JsonElement> = when {
            j.isObject -> {
                name = j.str("name")
                if (j["tracks"].isArray) j["tracks"].arr else return ParsedPlaylist(PlaylistFormat.JSON, "", name)
            }
            j.isArray -> j.arr
            else -> return ParsedPlaylist(PlaylistFormat.JSON)
        }
        val entries = ArrayList<ImportEntry>()
        val unresolved = ArrayList<ImportProblem>()
        var n = 0
        for (o in tracks) {
            n++
            if (!o.isObject) continue
            val artists = ArrayList<ArtistRef>()
            val a = o["artists"]
            if (a.isArray) {
                for (x in a.arr) {
                    if (x.isObject && x.str("name").isNotEmpty()) artists += ArtistRef(x.str("id"), x.str("name"))
                    else if (x.isString) artists += ArtistRef("", x.asString())
                }
            } else if (o.str("artist").isNotEmpty()) {
                artists += ArtistRef("", o.str("artist"))
            }
            val al = o["album"]
            val album = when {
                al.isObject -> AlbumRef(
                    al.str("id"), al.str("name"),
                    al["images"].arr.filter { it.isObject && it.str("u").isNotEmpty() }.map { Image(it.str("u"), it.int("w"), it.int("h")) },
                )
                al.isString -> AlbumRef(name = al.asString())
                else -> AlbumRef()
            }
            var t = Track(
                id = o.str("id"),
                videoId = o.str("videoId"),
                name = if (o.has("name")) o.str("name") else o.str("title"),
                artists = artists,
                album = album,
                durationMs = o.int("durationMs"),
                explicitContent = o.bool("explicit"),
                trackNumber = o.int("trackNumber"),
                addedAt = o.long("addedAt"),
            )
            val file = o.str("file")
            var localPath = ""
            // A local file is known by its path (its id is a hash of the path on the device that wrote the list).
            if (t.id.isEmpty() || t.id.startsWith("local:")) {
                t = t.copy(id = "")
                if (file.isNotEmpty()) localPath = file
            }
            if (t.id.isEmpty() && localPath.isEmpty()) {
                if (t.name.isEmpty()) {
                    unresolved += ImportProblem(n, shorten(o.dump()))
                    continue
                }
                t = t.copy(id = importId(t.name, t.artists.firstOrNull()?.name ?: ""))
            }
            if (t.id.startsWith("yt:") && t.videoId.isEmpty()) t = t.copy(videoId = t.id.substring(3))
            entries += ImportEntry(t, localPath, n)
        }
        return ParsedPlaylist(PlaylistFormat.JSON, "", name, entries, unresolved)
    }

    // ---- formats --------------------------------------------------------------------------------------------------
    /** ".m3u8", ".csv", ".xspf", ".json". */
    fun extension(f: PlaylistFormat): String = when (f) {
        PlaylistFormat.M3U -> ".m3u8"
        PlaylistFormat.CSV -> ".csv"
        PlaylistFormat.XSPF -> ".xspf"
        PlaylistFormat.JSON -> ".json"
    }

    /** The mime type a SAF "create document" picker gets for the format. */
    fun mimeType(f: PlaylistFormat): String = when (f) {
        PlaylistFormat.M3U -> "audio/x-mpegurl"
        PlaylistFormat.CSV -> "text/csv"
        PlaylistFormat.XSPF -> "application/xspf+xml"
        PlaylistFormat.JSON -> "application/json"
    }

    /** By the file name's extension: .m3u .m3u8 .csv .tsv .xspf .json; null otherwise. */
    fun formatFromName(name: String): PlaylistFormat? {
        val n = LocalPaths.nameOf(name)
        val dot = n.lastIndexOf('.')
        return when (if (dot <= 0) "" else lowerAscii(n.substring(dot))) {
            ".m3u", ".m3u8" -> PlaylistFormat.M3U
            ".csv", ".tsv" -> PlaylistFormat.CSV
            ".xspf" -> PlaylistFormat.XSPF
            ".json" -> PlaylistFormat.JSON
            else -> null
        }
    }

    /** A playlist file by its name (opened from another app, picked in the file picker). */
    fun isPlaylistFile(name: String): Boolean = formatFromName(name) != null

    /** By content (a file without a known extension). */
    fun sniff(text: String): PlaylistFormat {
        val head = trim(text.take(512))
        if (iStartsWith(head, "#EXTM3U")) return PlaylistFormat.M3U
        if (head.isNotEmpty() && (head[0] == '{' || head[0] == '[')) return PlaylistFormat.JSON
        if (head.contains("<playlist")) return PlaylistFormat.XSPF
        return PlaylistFormat.CSV
    }

    // ---- writing --------------------------------------------------------------------------------------------------
    /** Where other apps find the song: open.spotify.com / musicbrainz.org / youtube.com; "" when none of them. */
    fun trackUrl(r: ExportRow): String {
        val id = r.track.id
        if (id.startsWith(SPOTIFY_TRACK)) return "https://open.spotify.com/track/" + id.substring(SPOTIFY_TRACK.length)
        if (Links.isMbid(id)) return "https://musicbrainz.org/recording/$id"
        val v = videoOf(r)
        if (v.isNotEmpty()) return "https://www.youtube.com/watch?v=$v"
        return ""
    }

    /** "file:///storage/emulated/0/A%20B.mp3" (UTF-8 percent-encoded); a content URI stays as it is. */
    fun fileUri(path: String): String {
        if (path.startsWith("content://")) return path
        val p = path.replace('\\', '/')
        if (p.startsWith("//")) return "file:" + percentEncode(p, true) // UNC: file://server/share/...
        if (p.startsWith("/")) return "file://" + percentEncode(p, true)
        return "file:///" + percentEncode(p, true)
    }

    /** The path of a file URI ("" when `uri` isn't one): "/storage/x.mp3", "C:/x.mp3" (a Windows list), "//nas/x.mp3". */
    fun pathFromFileUri(uri: String): String {
        if (!iStartsWith(uri, "file:")) return ""
        val rest = uri.substring(5)
        fun local(p: String) = if (p.length >= 2 && p[1] == ':' && p[0].isLetter()) p else "/$p"
        val path = when {
            rest.startsWith("///") -> local(LocalPaths.percentDecode(rest.substring(3))) // file:///C:/x, file:///storage/x
            rest.startsWith("//") -> {
                val hostPath = rest.substring(2)
                if (iStartsWith(hostPath, "localhost/")) local(LocalPaths.percentDecode(hostPath.substring(10)))
                else "//" + LocalPaths.percentDecode(hostPath) // file://server/share/x (UNC)
            }
            else -> LocalPaths.percentDecode(rest)
        }
        return path.replace('\\', '/')
    }

    fun writeM3u8(name: String, rows: List<ExportRow>): String {
        val out = StringBuilder("#EXTM3U\r\n")
        if (name.isNotEmpty()) out.append("#PLAYLIST:").append(name).append("\r\n")
        for (r in rows) {
            val sec = if (r.track.durationMs > 0) (r.track.durationMs + 500) / 1000 else -1
            out.append("#EXTINF:").append(sec).append(',').append(displayLine(r.track)).append("\r\n")
            if (r.track.album.name.isNotEmpty()) out.append("#EXTALB:").append(r.track.album.name).append("\r\n")
            var where = r.filePath.ifEmpty { trackUrl(r) }
            if (where.isEmpty()) // a song known by name only: a search, so the line still leads somewhere
                where = "https://music.youtube.com/search?q=" + percentEncode(artistNames(r.track, " ") + " " + r.track.name, false)
            out.append(where).append("\r\n")
        }
        return out.toString()
    }

    fun writeCsv(rows: List<ExportRow>): String {
        fun cell(value: String): String {
            var v = value
            // A spreadsheet would run "=...", "+...", "-..." and "@..." as a formula (a crafted playlist title...).
            if (v.isNotEmpty() && (v[0] == '=' || v[0] == '+' || v[0] == '-' || v[0] == '@')) v = "'$v"
            val quote = v.any { it == ',' || it == '"' || it == '\r' || it == '\n' } || (v.isNotEmpty() && (v.first() == ' ' || v.last() == ' '))
            return if (!quote) v else "\"" + v.replace("\"", "\"\"") + "\""
        }
        val out = StringBuilder("\uFEFF")
        out.append("Title,Artists,Album,Duration,Duration (ms),Spotify URI,MusicBrainz ID,YouTube video ID,Added at,File\r\n")
        for (r in rows) {
            val t = r.track
            val cells = listOf(
                t.name, artistNames(t, "; "), t.album.name, mmss(t.durationMs),
                if (t.durationMs > 0) t.durationMs.toString() else "", if (t.id.startsWith(SPOTIFY_TRACK)) t.id else "",
                if (Links.isMbid(t.id)) t.id else "", videoOf(r), isoTime(t.addedAt), r.filePath,
            )
            out.append(cells.joinToString(",") { cell(it) }).append("\r\n")
        }
        return out.toString()
    }

    fun writeXspf(name: String, rows: List<ExportRow>): String {
        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\">\n")
        if (name.isNotEmpty()) out.append("  <title>").append(xmlEscape(name)).append("</title>\n")
        out.append("  <creator>ShadeTube</creator>\n  <trackList>\n")
        for (r in rows) {
            val t = r.track
            out.append("    <track>\n")
            val url = trackUrl(r)
            if (r.filePath.isNotEmpty()) out.append("      <location>").append(xmlEscape(fileUri(r.filePath))).append("</location>\n")
            if (url.isNotEmpty()) out.append("      <location>").append(xmlEscape(url)).append("</location>\n")
            if (t.id.startsWith(SPOTIFY_TRACK) || Links.isMbid(t.id)) out.append("      <identifier>").append(xmlEscape(url)).append("</identifier>\n")
            out.append("      <title>").append(xmlEscape(t.name)).append("</title>\n")
            val a = artistNames(t, ", ")
            if (a.isNotEmpty()) out.append("      <creator>").append(xmlEscape(a)).append("</creator>\n")
            if (t.album.name.isNotEmpty()) out.append("      <album>").append(xmlEscape(t.album.name)).append("</album>\n")
            if (t.durationMs > 0) out.append("      <duration>").append(t.durationMs).append("</duration>\n")
            out.append("    </track>\n")
        }
        out.append("  </trackList>\n</playlist>\n")
        return out.toString()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val PrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun writeJson(name: String, rows: List<ExportRow>): String {
        val tracks = JsonArray(
            rows.map { r ->
                val t = r.track
                val fields = LinkedHashMap<String, Any?>()
                fields["id"] = t.id
                fields["name"] = t.name
                fields["artists"] = t.artists.map { jsonOf("id" to it.id, "name" to it.name) }
                fields["album"] = jsonOf(
                    "id" to t.album.id, "name" to t.album.name,
                    "images" to t.album.images.map { jsonOf("u" to it.url, "w" to it.width, "h" to it.height) },
                )
                fields["durationMs"] = t.durationMs
                if (t.explicitContent) fields["explicit"] = true
                if (t.trackNumber > 0) fields["trackNumber"] = t.trackNumber
                if (t.addedAt > 0) fields["addedAt"] = t.addedAt
                videoOf(r).takeIf { it.isNotEmpty() }?.let { fields["videoId"] = it }
                if (r.filePath.isNotEmpty()) fields["file"] = r.filePath
                jsonOf(*fields.map { it.key to it.value }.toTypedArray())
            },
        )
        val doc: JsonObject = jsonOf("format" to "shadetube.playlist", "version" to 1, "name" to name, "tracks" to tracks)
        return PrettyJson.encodeToString(JsonElement.serializer(), doc) + "\n"
    }

    fun write(f: PlaylistFormat, name: String, rows: List<ExportRow>): String = when (f) {
        PlaylistFormat.M3U -> writeM3u8(name, rows)
        PlaylistFormat.CSV -> writeCsv(rows)
        PlaylistFormat.XSPF -> writeXspf(name, rows)
        PlaylistFormat.JSON -> writeJson(name, rows)
    }

    /** Writes the list to [out] (UTF-8; the CSV carries its BOM). Blocking. */
    fun write(f: PlaylistFormat, name: String, rows: List<ExportRow>, out: OutputStream) {
        out.write(write(f, name, rows).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // ---- reading --------------------------------------------------------------------------------------------------
    /** `baseDir`: the playlist file's folder (relative M3U / CSV paths), "" when unknown (a SAF document). */
    fun parse(text: String, f: PlaylistFormat, baseDir: String = ""): ParsedPlaylist {
        val t = if (text.startsWith("\uFEFF")) text.substring(1) else text
        return when (f) {
            PlaylistFormat.M3U -> parseM3u(t, baseDir)
            PlaylistFormat.CSV -> parseCsv(t, baseDir)
            PlaylistFormat.XSPF -> parseXspf(t)
            PlaylistFormat.JSON -> parseJson(t)
        }
    }

    /**
     * Reads a playlist file (any of the encodings above): the format by [displayName]'s extension, else by content;
     * the list's name falls back to the file name. Throws IOException when it can't be read (or is over 64 MB).
     * Blocking.
     */
    fun read(input: InputStream, displayName: String, baseDir: String = ""): ParsedPlaylist {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > MAX_FILE_BYTES) throw IOException("file too large")
        }
        val text = decodeText(buf.toByteArray())
        val f = formatFromName(displayName) ?: sniff(text)
        val p = parse(text, f, baseDir)
        return if (p.name.isEmpty()) p.copy(name = stemOf(displayName)) else p
    }

    // ---- helpers (exposed for tests) ------------------------------------------------------------------------------
    /** -> text without a BOM: UTF-8 (with / without BOM), UTF-16 LE / BE with a BOM, else the ANSI code page. */
    fun decodeText(bytes: ByteArray): String = LocalLyrics.decodeText(bytes).removePrefix("\uFEFF")

    /** The separator the first record uses most (outside quotes): comma, semicolon (European Excel) or tab. */
    fun detectDelimiter(text: String): Char {
        var comma = 0
        var semi = 0
        var tab = 0
        var q = false
        for (c in text) {
            if (c == '"') q = !q
            else if (!q && (c == '\n' || c == '\r')) break
            else if (!q && c == ',') comma++
            else if (!q && c == ';') semi++
            else if (!q && c == '\t') tab++
        }
        return if (tab > comma && tab >= semi) '\t' else if (semi > comma) ';' else ','
    }

    /** RFC 4180 records; [delimiter] null = detect from the first line. The BOM is skipped; blank lines dropped. */
    fun readCsv(input: String, delimiter: Char? = null): List<List<String>> {
        val text = if (input.startsWith("\uFEFF")) input.substring(1) else input
        val delim = delimiter ?: detectDelimiter(text)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var any = false
        fun endRow() {
            row += field.toString()
            field.setLength(0)
            val blank = row.size == 1 && row[0].isEmpty()
            if (!blank) rows += row
            row = ArrayList()
            any = false
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
                i++
                continue
            }
            if (c == '"' && field.isEmpty()) {
                quoted = true; any = true
            } else if (c == delim) {
                row += field.toString(); field.setLength(0); any = true
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                endRow()
            } else {
                field.append(c); any = true
            }
            i++
        }
        if (any || field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** "import:<FNV-1a 64 of lower(name) + '|' + lower(artist)>" (the stats history import's scheme; ASCII lower case). */
    fun importId(name: String, artist: String): String {
        var h = -0x340d631b7bdddcdbL
        fun mix(s: String) {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                var c = b.toInt() and 0xFF
                if (c in 'A'.code..'Z'.code) c += 32
                h = h xor c.toLong()
                h *= 0x100000001b3L
            }
        }
        mix(name)
        mix("|")
        mix(artist)
        return "import:" + java.lang.Long.toUnsignedString(h, 16).padStart(16, '0')
    }

    private fun strtod(s: String): Double? {
        val t = s.replace(',', '.')
        if (t.isEmpty() || t.any { !(it in '0'..'9' || it == '.' || it == '-' || it == '+' || it == 'e' || it == 'E') }) return null
        return t.toDoubleOrNull()
    }

    /** "3:25", "1:02:03", "205", "205000" (millis) -> ms; 0 = none. */
    fun parseDurationMs(text: String, millis: Boolean): Int {
        val s = trim(text)
        if (s.isEmpty()) return 0
        if (s.contains(':')) {
            var total = 0.0
            var parts = 0
            for (part in s.split(':')) {
                if (parts >= 3) break
                val v = strtod(part) ?: return 0
                total = total * 60 + v
                parts++
            }
            return (total * 1000 + 0.5).toInt()
        }
        val v = strtod(s) ?: return 0
        if (v <= 0) return 0
        // A plain number: milliseconds when the column says so; else seconds, unless it is too long to be seconds of
        // a song (a "duration" column in milliseconds).
        if (millis || v > 7200) return (v + 0.5).toInt()
        return (v * 1000 + 0.5).toInt()
    }

    private val ISO_IN = Regex("""^\s*(\d+)-(\d+)-(\d+)(?:[T ]+(\d+)(?::(\d+)(?::(\d+))?)?)?""")

    /** "2024-01-31T12:34:56Z" (also " " / no zone / date only / "+03:00" / "-0500") -> unix seconds; 0 = none. */
    fun parseIsoTime(text: String): Long {
        val s = trim(text)
        val m = ISO_IN.find(s) ?: return 0
        val g = m.groupValues
        val y = g[1].toIntOrNull() ?: return 0
        val mo = g[2].toIntOrNull() ?: return 0
        val d = g[3].toIntOrNull() ?: return 0
        if (y < 1970 || mo < 1 || mo > 12 || d < 1 || d > 31) return 0
        val h = g[4].toIntOrNull() ?: 0
        val mi = g[5].toIntOrNull() ?: 0
        val sec = g[6].toIntOrNull() ?: 0
        // Out-of-range days roll over like _mkgmtime ("2023-02-31" -> March 3rd).
        var t = LocalDate.of(y, mo, 1).plusDays((d - 1).toLong()).atStartOfDay().toEpochSecond(ZoneOffset.UTC) + h * 3600L + mi * 60L + sec
        if (t < 0) return 0
        // A zone offset after the time ("+03:00", "-0500"); "Z" or none = UTC.
        val tpos = s.indexOfFirst { it == 'T' || it == ' ' }
        if (tpos >= 0) {
            val z = s.indexOfAny(charArrayOf('+', '-'), tpos)
            if (z >= 0) {
                val zone = Regex("""^(\d{1,2})(?::?(\d{2}))?""").find(s.substring(z + 1))
                if (zone != null) {
                    val off = ((zone.groupValues[1].toIntOrNull() ?: 0) * 60 + (zone.groupValues[2].toIntOrNull() ?: 0)) * 60L
                    t += if (s[z] == '+') -off else off
                }
            }
        }
        return t
    }
}
