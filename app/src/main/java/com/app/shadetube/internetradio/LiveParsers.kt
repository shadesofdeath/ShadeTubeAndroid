package com.app.shadetube.internetradio

import java.util.Locale

/**
 * Pure parsers for live internet-radio streams (the parts of audio/LiveParsers the Android player still needs; the
 * frame / container parsers are ExoPlayer's job):
 *  - ICY (Shoutcast / Icecast) StreamTitle text (UTF-8, else the legacy code page) and the "now on air" split;
 *  - station playlists (.pls / .m3u / .asx) -> stream URLs, and telling them apart from audio and HLS.
 * All input comes from untrusted network data: every parser is bounded.
 */
object LiveParsers {
    private const val MAX_PLAYLIST_ENTRIES = 64

    // ---- small helpers --------------------------------------------------------------------------------------------------

    private fun lower(c: Char) = if (c in 'A'..'Z') c + 32 else c

    private fun istartsWith(s: CharSequence, prefix: String, at: Int = 0): Boolean {
        if (s.length - at < prefix.length) return false
        for (i in prefix.indices) if (lower(s[at + i]) != lower(prefix[i])) return false
        return true
    }

    private fun ifind(s: CharSequence, needle: String, from: Int = 0): Int {
        if (needle.isEmpty() || s.length < needle.length) return -1
        for (i in from..s.length - needle.length) if (istartsWith(s, needle, i)) return i
        return -1
    }

    private fun trimCtl(s: String): String {
        var b = 0
        var e = s.length
        while (b < e && s[b] <= ' ') ++b
        while (e > b && s[e - 1] <= ' ') --e
        return s.substring(b, e)
    }

    /** Titles: control characters become spaces, runs of spaces collapse, the ends are trimmed. */
    internal fun cleanText(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            if (c.code < 0x20 || c == ' ') {
                if (out.isNotEmpty() && out[out.length - 1] != ' ') out.append(' ')
            } else {
                out.append(c)
            }
        }
        return trimCtl(out.toString())
    }

    private fun latin1(b: ByteArray, from: Int = 0, to: Int = b.size) = String(b, from, to - from, Charsets.ISO_8859_1)

    internal fun validUtf8(b: ByteArray, from: Int = 0, to: Int = b.size): Boolean {
        var i = from
        while (i < to) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x80) {
                ++i
                continue
            }
            val extra = when {
                (c and 0xE0) == 0xC0 && c >= 0xC2 -> 1
                (c and 0xF0) == 0xE0 -> 2
                (c and 0xF8) == 0xF0 && c <= 0xF4 -> 3
                else -> return false
            }
            if (i + extra >= to) return false
            for (k in 1..extra) if ((b[i + k].toInt() and 0xC0) != 0x80) return false
            i += extra + 1
        }
        return true
    }

    private val CP1252_HIGH = intArrayOf(
        0x20AC, 0x81, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160,
        0x2039, 0x0152, 0x8D, 0x017D, 0x8F, 0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022,
        0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x9D, 0x017E, 0x0178,
    )

    /** Windows-1254 differs from 1252 in six letters (Ğ ğ İ ı Ş ş) and three C1 slots. */
    private val CP1254_DIFF = mapOf(
        0xD0 to 0x011E, 0xDD to 0x0130, 0xDE to 0x015E, 0xF0 to 0x011F, 0xFD to 0x0131, 0xFE to 0x015F,
        0x8A to 0x0160, 0x8E to 0x8E, 0x9A to 0x0161, 0x9E to 0x9E, 0x9F to 0x0178,
    )

    private fun decodeWindows(b: ByteArray, turkish: Boolean): String {
        val sb = StringBuilder(b.size)
        for (x in b) {
            val c = x.toInt() and 0xFF
            val cp = (if (turkish) CP1254_DIFF[c] else null) ?: if (c in 0x80..0x9F) CP1252_HIGH[c - 0x80] else c
            sb.append(cp.toChar())
        }
        return sb.toString()
    }

    /** Whether legacy (non-UTF-8) titles are read as Windows-1254: on a Turkish system, like the C++ GetACP() check. */
    fun defaultTurkish(): Boolean = Locale.getDefault().language == "tr"

    /** UTF-8 when the bytes are valid UTF-8, else Windows-1254 (Turkish) on a Turkish system and Windows-1252 otherwise. */
    fun decodeLegacyText(bytes: ByteArray, turkish: Boolean = defaultTurkish()): String {
        if (bytes.isEmpty()) return ""
        val n = minOf(bytes.size, 1 shl 20)
        val b = if (n == bytes.size) bytes else bytes.copyOf(n)
        if (validUtf8(b)) return String(b, Charsets.UTF_8)
        return decodeWindows(b, turkish)
    }

    // ---- ICY ------------------------------------------------------------------------------------------------------------

    /**
     * StreamTitle of an ICY metadata block (decoded, trimmed); null if the block has none. ExoPlayer's IcyInfo gives the
     * raw block (`rawMetadata`): pass that, so legacy code pages are read like the desktop app reads them.
     */
    fun icyStreamTitle(metadata: ByteArray, turkish: Boolean = defaultTurkish()): String? {
        var end0 = metadata.indexOf(0.toByte())
        if (end0 < 0) end0 = metadata.size
        val meta = latin1(metadata, 0, end0) // one char per byte: positions are byte offsets
        val key = ifind(meta, "StreamTitle='")
        if (key < 0) return null
        val start = key + 13
        // The title may contain quotes: it ends at the first "';" followed by the end of the block or by the next key
        // ("StreamUrl=", "json=", ...), else at the last quote.
        var end = -1
        var p = meta.indexOf("';", start)
        while (p >= 0) {
            var k = p + 2
            while (k < meta.length && (meta[k].isLetterOrDigit() && meta[k].code < 0x80 || meta[k] == '_')) ++k
            if (trimCtl(meta.substring(p + 2)).isEmpty() || (k > p + 2 && k < meta.length && meta[k] == '=')) {
                end = p
                break
            }
            p = meta.indexOf("';", p + 1)
        }
        if (end < 0) {
            end = meta.lastIndexOf('\'')
            if (end < 0 || end < start) end = meta.length
        }
        return cleanText(decodeLegacyText(metadata.copyOfRange(start, end), turkish))
    }

    /** Same for a block that is already text (UTF-8 metadata, tests). */
    fun icyStreamTitle(metadata: String): String? = icyStreamTitle(metadata.toByteArray(Charsets.UTF_8), false)

    /** "Now on air": a stream title split into artist and title. */
    data class NowOnAir(
        /** "" when the title names no artist. */
        val artist: String,
        val title: String,
    ) {
        /** The title as the station sent it (cleaned). */
        val full: String get() = if (artist.isEmpty()) title else "$artist - $title"
    }

    private val SEPARATORS = listOf(" - ", " – ", " — ", " ~ ")

    /**
     * "Artist - Title" -> artist / title (the convention almost every station follows). No separator, or an empty side,
     * gives the whole text as the title. An empty title is null (stations clear the title between songs / in ads).
     */
    fun splitStreamTitle(raw: String?): NowOnAir? {
        val text = cleanText(raw.orEmpty())
        if (text.isEmpty()) return null
        var best = -1
        var sepLen = 0
        for (sep in SEPARATORS) {
            val at = text.indexOf(sep)
            if (at > 0 && (best < 0 || at < best)) {
                best = at
                sepLen = sep.length
            }
        }
        if (best < 0) return NowOnAir("", text)
        val artist = text.substring(0, best).trim()
        val title = text.substring(best + sepLen).trim()
        if (artist.isEmpty() || title.isEmpty()) return NowOnAir("", text)
        return NowOnAir(artist, title)
    }

    // ---- playlists ----------------------------------------------------------------------------------------------------

    enum class PlaylistType { None, Pls, M3u, Hls, Asx }

    fun isHttpUrl(url: String) = istartsWith(url, "http://") || istartsWith(url, "https://")

    private fun stripBom(s: String): String = when {
        s.startsWith("ï»¿") -> s.substring(3) // a UTF-8 BOM in a Latin-1 view
        s.startsWith("\uFEFF") -> s.substring(1)
        else -> s
    }

    private fun looksLikeAudio(head: String): Boolean {
        if (head.length >= 2 && head[0].code == 0xFF && (head[1].code and 0xE0) == 0xE0) return true
        return head.startsWith("ID3") || head.startsWith("OggS") || head.startsWith("fLaC")
    }

    private fun looksLikeText(head: String): Boolean {
        var printable = 0
        for (c in head) {
            val u = c.code
            if (u == 0) return false
            if (u >= 0x20 || c == '\r' || c == '\n' || c == '\t') ++printable
        }
        return head.isNotEmpty() && printable * 10 >= head.length * 9
    }

    internal fun extensionOf(urlIn: String): String {
        var url = urlIn
        val q = url.indexOfFirst { it == '?' || it == '#' }
        if (q >= 0) url = url.substring(0, q)
        val slash = url.lastIndexOf('/')
        val dot = url.lastIndexOf('.')
        if (dot < 0 || (slash >= 0 && dot < slash)) return ""
        return url.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** By content type, then by the first bytes of the body, then by the URL's extension. */
    fun playlistType(contentType: String, head: ByteArray, url: String): PlaylistType {
        val raw = latin1(head)
        val h = trimCtl(stripBom(raw))
        if (h.startsWith("#EXTM3U")) return if (h.contains("#EXT-X-")) PlaylistType.Hls else PlaylistType.M3u
        if (istartsWith(h, "[playlist]")) return PlaylistType.Pls
        if (istartsWith(h, "<asx")) return PlaylistType.Asx
        if (looksLikeAudio(raw)) return PlaylistType.None
        val semi = contentType.indexOf(';')
        val ct = trimCtl(if (semi >= 0) contentType.substring(0, semi) else contentType).lowercase(Locale.ROOT)
        val text = looksLikeText(raw)
        if (ct == "audio/x-scpls" || ct == "application/pls+xml" || ct == "audio/scpls") return PlaylistType.Pls
        if (ct == "application/vnd.apple.mpegurl" || ct == "application/x-mpegurl" || ct == "audio/x-mpegurl" ||
            ct == "audio/mpegurl" || ct == "vnd.apple.mpegurl"
        ) return if (h.contains("#EXT-X-")) PlaylistType.Hls else PlaylistType.M3u
        if ((ct == "video/x-ms-asf" || ct == "video/x-ms-asx" || ct == "audio/x-ms-wax" || ct == "application/x-ms-asx") &&
            h.startsWith("<")
        ) return PlaylistType.Asx
        if (!text) return PlaylistType.None
        when (extensionOf(url)) {
            "pls" -> return PlaylistType.Pls
            "m3u", "m3u8" -> return if (h.contains("#EXT-X-")) PlaylistType.Hls else PlaylistType.M3u
            "asx", "wax" -> return PlaylistType.Asx
        }
        // A plain list of stream URLs served as text.
        if (ct.startsWith("text/") && (istartsWith(h, "http://") || istartsWith(h, "https://"))) return PlaylistType.M3u
        return PlaylistType.None
    }

    fun playlistType(contentType: String, head: String, url: String): PlaylistType =
        playlistType(contentType, head.toByteArray(Charsets.ISO_8859_1), url)

    /** RFC 3986 reference resolution (dot segments removed, the query left alone). */
    fun resolveUrl(base: String, refIn: String): String {
        val ref = trimCtl(refIn)
        if (ref.isEmpty()) return ""
        val colon = ref.indexOf(':')
        val firstSep = ref.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (colon >= 0 && (firstSep < 0 || colon < firstSep)) return ref // absolute
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return ref
        if (ref.startsWith("//")) return base.substring(0, schemeEnd + 1) + ref
        val pathStart = base.indexOf('/', schemeEnd + 3)
        val origin = if (pathStart < 0) {
            val q = base.indexOfAny(charArrayOf('?', '#'), schemeEnd + 3)
            if (q < 0) base else base.substring(0, q)
        } else {
            base.substring(0, pathStart)
        }
        val path: String
        if (ref.startsWith("/")) {
            path = ref
        } else {
            var basePath = if (pathStart < 0) "/" else base.substring(pathStart)
            val q = basePath.indexOfAny(charArrayOf('?', '#'))
            if (q >= 0) basePath = basePath.substring(0, q)
            if (ref.startsWith("?")) return origin + basePath + ref
            path = basePath.substring(0, basePath.lastIndexOf('/') + 1) + ref
        }
        val q = path.indexOfAny(charArrayOf('?', '#'))
        val query = if (q < 0) "" else path.substring(q)
        val rest = if (q < 0) path else path.substring(0, q)
        val trailing = rest.endsWith("/") || rest.endsWith("/.") || rest.endsWith("/..")
        val parts = ArrayList<String>()
        for (seg in rest.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") {
                if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                continue
            }
            parts.add(seg)
        }
        val out = StringBuilder(origin)
        for (s in parts) out.append('/').append(s)
        if (parts.isEmpty() || trailing) out.append('/')
        return out.append(query).toString()
    }

    private fun lines(body: String): List<String> = body.split('\r', '\n').map { trimCtl(it) }

    /** Absolute http(s) stream URLs of a .pls / .m3u / .asx playlist, in order (at most 64, no repeats). */
    fun parsePlaylist(type: PlaylistType, bodyIn: String, baseUrl: String): List<String> {
        val out = ArrayList<String>()
        fun add(ref: String) {
            val url = resolveUrl(baseUrl, ref)
            if (isHttpUrl(url) && out.size < MAX_PLAYLIST_ENTRIES && url !in out) out.add(url)
        }
        val body = stripBom(bodyIn)
        when (type) {
            PlaylistType.Pls -> for (line in lines(body)) {
                val eq = line.indexOf('=')
                if (eq >= 0 && istartsWith(line, "file")) add(line.substring(eq + 1))
            }
            PlaylistType.M3u -> for (line in lines(body)) if (line.isNotEmpty() && line[0] != '#') add(line)
            PlaylistType.Asx -> for (tag in listOf("<ref", "<entryref")) {
                var pos = 0
                while (true) {
                    pos = ifind(body, tag, pos)
                    if (pos < 0) break
                    val close = body.indexOf('>', pos)
                    val element = body.substring(pos, if (close < 0) body.length else close)
                    pos += tag.length
                    val href = ifind(element, "href")
                    if (href < 0) continue
                    val qpos = element.indexOfAny(charArrayOf('"', '\''), href)
                    if (qpos < 0) continue
                    val endq = element.indexOf(element[qpos], qpos + 1)
                    if (endq < 0) continue
                    add(element.substring(qpos + 1, endq).replace("&amp;", "&"))
                }
            }
            PlaylistType.Hls, PlaylistType.None -> Unit
        }
        return out
    }

    /** A playlist body as text: UTF-8 when valid, else Windows-1252. */
    fun playlistText(body: ByteArray): String = if (validUtf8(body)) String(body, Charsets.UTF_8) else decodeWindows(body, false)
}
