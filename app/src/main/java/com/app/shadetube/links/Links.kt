package com.app.shadetube.links

/**
 * Links pasted into ShadeTube (port of app/Links): Spotify (open.spotify.com URLs and spotify: URIs), YouTube /
 * YouTube Music videos and playlists, and MusicBrainz pages. [Links.parse] only recognizes the text; what opening it
 * does is [LinkOpener].
 *
 * Accepted (the whole trimmed text must be the link; scheme and "www." optional, query and fragment ignored):
 *  - open.spotify.com/[intl-xx/][embed/]{track,album,playlist,artist}/<22-char base62 id>, the legacy
 *    /user/<name>/playlist/<id>, and spotify:{track,album,playlist,artist}:<id> (also spotify:user:<name>:playlist:<id>);
 *  - youtube.com / m. / music. / youtube-nocookie.com: /watch?v=<id>, /shorts/<id>, /embed/<id>, /live/<id>, /v/<id>,
 *    and youtu.be/<id> (11-char video ids); youtube.com / music.youtube.com /playlist?list=<id> (imported as a local
 *    playlist);
 *  - musicbrainz.org/{release-group,release,artist,recording}/<mbid>[/...].
 *
 * Links of those services to things ShadeTube can't open (podcasts, users, YouTube channels, spotify.link short
 * links...) parse as [LinkKind.Unsupported], so the caller can say so instead of searching for a URL.
 */
enum class LinkService { None, Spotify, YouTube, MusicBrainz }

enum class LinkKind {
    /** Not a link of a known service: search for the text as usual. */
    None,
    SpotifyTrack,
    SpotifyAlbum,
    SpotifyPlaylist,
    SpotifyArtist,
    YouTubeVideo,
    /** Imported as a local playlist. */
    YouTubePlaylist,
    /** What ShadeTube calls an album in MusicBrainz mode. */
    MbReleaseGroup,
    /** One edition: opened as its release group. */
    MbRelease,
    MbArtist,
    /** A song: played. */
    MbRecording,
    /** A known service, but nothing ShadeTube opens. */
    Unsupported,
}

data class Link(
    val kind: LinkKind = LinkKind.None,
    val service: LinkService = LinkService.None,
    /**
     * base62 (Spotify), 11-char video id / playlist id (YouTube), lower-case MBID (MusicBrainz); "" for Unsupported.
     */
    val id: String = "",
) {
    /** False only for [LinkKind.None] (C++ `explicit operator bool`). */
    val isLink: Boolean get() = kind != LinkKind.None

    /** "spotify:album:<id>" for the Spotify kinds (the catalog id the app uses), "" otherwise. */
    val spotifyUri: String
        get() = when (kind) {
            LinkKind.SpotifyTrack -> "spotify:track:$id"
            LinkKind.SpotifyAlbum -> "spotify:album:$id"
            LinkKind.SpotifyPlaylist -> "spotify:playlist:$id"
            LinkKind.SpotifyArtist -> "spotify:artist:$id"
            else -> ""
        }
}

/** A YouTube video shown as a song. */
data class VideoSong(val artist: String, val title: String)

object Links {
    private const val SPACE = " \t\r\n\u000C\u000B"

    private fun lower(c: Char) = if (c in 'A'..'Z') c + 32 else c
    private fun toLower(s: String) = buildString(s.length) { for (c in s) append(lower(c)) }
    private fun startsWithNoCase(s: String, prefix: String) =
        s.length >= prefix.length && prefix.indices.all { lower(s[it]) == lower(prefix[it]) }

    private fun isAlnum(c: Char) = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'
    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun trim(s: String): String {
        var b = 0
        var e = s.length
        while (b < e && SPACE.indexOf(s[b]) >= 0) b++
        while (e > b && SPACE.indexOf(s[e - 1]) >= 0) e--
        return s.substring(b, e)
    }

    /** Non-empty parts between [sep]. */
    private fun split(s: String, sep: Char): List<String> = s.split(sep).filter { it.isNotEmpty() }

    private fun make(kind: LinkKind, service: LinkService, id: String = "") = Link(kind, service, id)
    private fun unsupported(service: LinkService) = make(LinkKind.Unsupported, service)

    private fun spotifyKind(type: String) = when (type) {
        "track" -> LinkKind.SpotifyTrack
        "album" -> LinkKind.SpotifyAlbum
        "playlist" -> LinkKind.SpotifyPlaylist
        "artist" -> LinkKind.SpotifyArtist
        else -> LinkKind.None
    }

    private fun spotifyItem(type: String, id: String): Link {
        val k = spotifyKind(toLower(type))
        if (k == LinkKind.None || !isSpotifyId(id)) return unsupported(LinkService.Spotify)
        return make(k, LinkService.Spotify, id)
    }

    /** spotify:track:<id>, spotify:user:<name>:playlist:<id>. */
    private fun parseSpotifyUri(s: String): Link {
        val parts = split(s.substring(8), ':')
        if (parts.size == 2) return spotifyItem(parts[0], parts[1])
        if (parts.size == 4 && toLower(parts[0]) == "user" && toLower(parts[2]) == "playlist") return spotifyItem(parts[2], parts[3])
        return unsupported(LinkService.Spotify)
    }

    /** open.spotify.com path segments: [intl-xx/][embed/]<type>/<id>, or user/<name>/playlist/<id>. */
    private fun parseSpotifyPath(segIn: List<String>): Link {
        val seg = segIn.toMutableList()
        fun dropFront(pred: (String) -> Boolean) {
            if (seg.isNotEmpty() && pred(toLower(seg.first()))) seg.removeAt(0)
        }
        dropFront { it.startsWith("intl-") && it.length in 7..10 }
        dropFront { it == "embed" || it == "embed-podcast" }
        if (seg.size >= 2 && spotifyKind(toLower(seg[0])) != LinkKind.None) return spotifyItem(seg[0], seg[1])
        if (seg.size >= 4 && toLower(seg[0]) == "user" && toLower(seg[2]) == "playlist") return spotifyItem(seg[2], seg[3])
        return unsupported(LinkService.Spotify)
    }

    private fun queryValue(query: String, key: String): String {
        for (part in split(query, '&')) {
            val eq = part.indexOf('=')
            if (eq >= 0 && part.substring(0, eq) == key) return part.substring(eq + 1)
        }
        return ""
    }

    private fun video(id: String): Link {
        if (!isVideoId(id)) return unsupported(LinkService.YouTube)
        return make(LinkKind.YouTubeVideo, LinkService.YouTube, id)
    }

    private fun parseYouTube(seg: List<String>, query: String): Link {
        if (seg.isEmpty()) return unsupported(LinkService.YouTube)
        val first = toLower(seg[0])
        if (first == "watch") return video(queryValue(query, "v"))
        if ((first == "shorts" || first == "embed" || first == "live" || first == "v" || first == "e") && seg.size >= 2)
            return video(seg[1])
        if (first == "playlist" && seg.size == 1) { // /playlist?list=<id> (a watch URL with &list= plays its video)
            val list = queryValue(query, "list")
            if (isPlaylistId(list)) return make(LinkKind.YouTubePlaylist, LinkService.YouTube, list)
        }
        return unsupported(LinkService.YouTube) // channels, @handles, search results...
    }

    private fun parseMusicBrainz(seg: List<String>): Link {
        if (seg.size < 2 || !isMbid(seg[1])) return unsupported(LinkService.MusicBrainz)
        val id = toLower(seg[1])
        return when (toLower(seg[0])) {
            "release-group" -> make(LinkKind.MbReleaseGroup, LinkService.MusicBrainz, id)
            "release" -> make(LinkKind.MbRelease, LinkService.MusicBrainz, id)
            "artist" -> make(LinkKind.MbArtist, LinkService.MusicBrainz, id)
            "recording" -> make(LinkKind.MbRecording, LinkService.MusicBrainz, id)
            else -> unsupported(LinkService.MusicBrainz)
        }
    }

    /** 22 base62 characters. */
    fun isSpotifyId(s: String) = s.length == 22 && s.all { isAlnum(it) }

    /** 11 characters of [A-Za-z0-9_-]. */
    fun isVideoId(s: String) = s.length == 11 && s.all { isAlnum(it) || it == '_' || it == '-' }

    /** 12..64 characters of [A-Za-z0-9_-] (PL..., OLAK5uy_..., RD...). */
    fun isPlaylistId(s: String) = s.length in 12..64 && s.all { isAlnum(it) || it == '_' || it == '-' }

    /** 8-4-4-4-12 hex (either case). */
    fun isMbid(s: String): Boolean {
        if (s.length != 36) return false
        for (i in s.indices) {
            val dash = i == 8 || i == 13 || i == 18 || i == 23
            if (if (dash) s[i] != '-' else !isHex(s[i])) return false
        }
        return true
    }

    /**
     * Recognizes [text] (the search box / clipboard). Every link we recognize is ASCII: other characters only have
     * to stay "not ASCII" (they become U+007F, like the C++ UTF-16 overload).
     */
    fun parse(text: String): Link {
        val narrow = buildString(text.length) { for (c in text) append(if (c.code < 0x80) c else '\u007F') }
        var s = trim(narrow)
        // Pasted between <...> or quotes (chat apps, Markdown).
        while (s.length >= 2 && ((s.first() == '<' && s.last() == '>') || (s.first() == '"' && s.last() == '"') ||
                (s.first() == '\'' && s.last() == '\''))
        ) {
            s = trim(s.substring(1, s.length - 1))
        }
        if (s.isEmpty() || s.any { SPACE.indexOf(it) >= 0 }) return Link()
        if (startsWithNoCase(s, "spotify:")) return parseSpotifyUri(s)

        if (startsWithNoCase(s, "https://")) s = s.substring(8)
        else if (startsWithNoCase(s, "http://")) s = s.substring(7)
        val hostEnd = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        var authority = if (hostEnd < 0) s else s.substring(0, hostEnd)
        authority.lastIndexOf('@').let { if (it >= 0) authority = authority.substring(it + 1) }
        authority.indexOf(':').let { if (it >= 0) authority = authority.substring(0, it) }
        var host = toLower(authority)
        if (host.startsWith("www.")) host = host.substring(4)
        if (!host.contains('.')) return Link()

        var rest = if (hostEnd < 0) "" else s.substring(hostEnd)
        rest.indexOf('#').let { if (it >= 0) rest = rest.substring(0, it) }
        var path = rest
        var query = ""
        rest.indexOf('?').let {
            if (it >= 0) {
                path = rest.substring(0, it)
                query = rest.substring(it + 1)
            }
        }
        val seg = split(path, '/')

        return when (host) {
            "open.spotify.com", "play.spotify.com" -> parseSpotifyPath(seg)
            "spotify.link", "spotify.app.link" -> unsupported(LinkService.Spotify) // short links
            "youtu.be" -> if (seg.isEmpty()) unsupported(LinkService.YouTube) else video(seg[0])
            "youtube.com", "m.youtube.com", "music.youtube.com", "youtube-nocookie.com" -> parseYouTube(seg, query)
            "musicbrainz.org", "beta.musicbrainz.org" -> parseMusicBrainz(seg)
            else -> Link()
        }
    }

    /** A link is short: clipboard text this long is a document, not something to open (C++ clipboardText). */
    const val MAX_CLIPBOARD_CHARS = 4096

    /** The clipboard's text as a link (Kind.None when it is empty, too long or not a link). */
    fun parseClipboard(text: String?): Link =
        if (text.isNullOrEmpty() || text.length >= MAX_CLIPBOARD_CHARS) Link() else parse(text)

    // ---- YouTube video -> song ------------------------------------------------------------------------------------
    /**
     * Words of a bracket group, ASCII lower-cased ("Official Music Video" -> official, music, video). Non-ASCII
     * characters stay inside their word ("Sözleri" -> "sözleri").
     */
    private fun words(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in s) {
            if (isAlnum(c) || c.code >= 0x80) cur.append(lower(c))
            else if (cur.isNotEmpty()) {
                out += cur.toString(); cur.setLength(0)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private val KEEP = setOf("feat", "ft", "featuring", "live", "remix", "mix", "edit", "version", "acoustic", "cover", "remaster", "remastered")
    private val NOISE = setOf(
        "official", "video", "audio", "lyric", "lyrics", "visualizer", "visualiser", "klip", "clip",
        "videoclip", "videoklip", "mv", "hd", "hq", "4k", "1080p", "sözleri",
    )

    /**
     * A bracket group that only says what kind of upload this is. "(Official Live Video)" stays (live); "[4K
     * Remaster]" stays too: a remaster is a different recording.
     */
    private fun noiseGroup(inner: String): Boolean {
        var isNoise = false
        for (w in words(inner)) {
            if (w in KEEP) return false
            if (w in NOISE) isNoise = true
        }
        return isNoise
    }

    private fun collapse(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            if ((c == ' ' || c == '\t') && (out.isEmpty() || out.last() == ' ')) continue
            out.append(if (c == '\t') ' ' else c)
        }
        while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        return out.toString()
    }

    /** Drops noise groups: (...), [...] and the CJK 【...】. */
    private fun stripNoise(title: String): String {
        val pairs = listOf("(" to ")", "[" to "]", "【" to "】")
        var s = title
        var changed = true
        while (changed) {
            changed = false
            for ((open, close) in pairs) {
                var from = 0
                while (true) {
                    val a = s.indexOf(open, from)
                    if (a < 0) break
                    val b = s.indexOf(close, a + open.length)
                    if (b < 0) break
                    val inner = s.substring(a + open.length, b)
                    if (noiseGroup(inner)) {
                        s = s.removeRange(a, b + close.length)
                        changed = true
                    } else {
                        from = b + close.length
                    }
                }
            }
        }
        return collapse(s)
    }

    private fun cleanChannel(channel: String): String {
        var c = collapse(trim(channel))
        for (suffix in listOf(" - Topic", "VEVO", " Official")) {
            if (c.length > suffix.length + 1 && c.endsWith(suffix)) c = collapse(c.substring(0, c.length - suffix.length))
        }
        return c
    }

    /**
     * A YouTube video shown as a song: "Artist - Title (Official Video)" by "ArtistVEVO" -> ("Artist", "Title").
     * Without an "Artist - Title" title the artist is the channel ("<name> - Topic" and "<name>VEVO" trimmed to the
     * name). Bracketed noise ("(Official Music Video)", "[HD]", "(Lyrics)", "(Klip)"...) is dropped; "(feat. ...)",
     * "(Live)" and "(Remix)" stay.
     */
    fun videoSong(title: String, channel: String): VideoSong {
        val topic = channel.length > 8 && channel.endsWith(" - Topic")
        var t = stripNoise(trim(title))
        if (t.isEmpty()) t = collapse(trim(title))
        var artist = cleanChannel(channel)
        if (!topic) {
            // "Artist - Title" (hyphen, en or em dash between spaces)
            for (sep in listOf(" - ", " – ", " — ")) {
                val p = t.indexOf(sep)
                if (p <= 0 || p + sep.length >= t.length) continue
                val a = collapse(trim(t.substring(0, p)))
                val song = collapse(trim(t.substring(p + sep.length)))
                if (a.isEmpty() || song.isEmpty()) continue
                artist = a
                t = song
                break
            }
        }
        // Leftover quotes around the song ("Artist - 'Title'").
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\'')))
            t = collapse(trim(t.substring(1, t.length - 1)))
        return VideoSong(artist, t)
    }
}
