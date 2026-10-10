package com.app.shadetube.stats

import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.core.Log
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Spotify listening-history import (İstatistikler › "Spotify geçmişini içe aktar"). Port of app/HistoryImport.
 * Reads the files of Spotify's data download (spotify.com › Account › Privacy › Download your data) into
 * [ListenStats.ImportRow] plays:
 *  - extended streaming history ("Spotify Extended Streaming History.zip"): Streaming_History_Audio_<years>_<n>.json,
 *    older endsong_<n>.json - rows {"ts" (UTC end time, ISO 8601), "ms_played", "master_metadata_track_name",
 *    "master_metadata_album_artist_name", "master_metadata_album_album_name", "spotify_track_uri", ...};
 *  - account data ("my_spotify_data.zip"): StreamingHistory_music_<n>.json, older StreamingHistory<n>.json - rows
 *    {"endTime" ("YYYY-MM-DD HH:MM" UTC), "artistName", "trackName", "msPlayed"}.
 *
 * Either as the JSON files themselves or straight from the ZIP (java.util.zip: stored / deflate, ZIP64 too).
 * Podcast, audiobook and video rows and rows without a song are skipped. Deduplication happens later
 * ([ListenStats.buildImport]), so the same files can be offered twice.
 *
 * The UI hands the picked documents over as [Source]s (a display name + a way to open the content, e.g. through the
 * ContentResolver for a SAF Uri). Blocking: workers only.
 */
object HistoryImport {
    private const val TAG = "stats"

    /** A history JSON is ~12 MB; anything this big is not one. */
    private const val MAX_DOCUMENT = 512L shl 20

    enum class Format { Unknown, Extended, Account }

    /**
     * One picked file: its display name (the extension decides .json / .zip) and an opener that may be called more
     * than once (a ZIP is read twice: its entry list first, so progress has a total, then the history entries).
     */
    class Source(val name: String, val open: () -> InputStream) {
        companion object {
            fun of(file: File) = Source(file.name) { FileInputStream(file) }
        }
    }

    class Result {
        val rows = ArrayList<ListenStats.ImportRow>()
        /** History documents read (JSON files and ZIP entries). */
        var documents = 0
        /** Podcast / audiobook / video rows, rows without a song or a valid time. */
        var skippedRows = 0
        /** JSON files that are no Spotify listening history. */
        var unrecognized = 0
        /** "<file>: <reason>" per input that failed (for the log). */
        val errors = ArrayList<String>()
        var cancelled = false
    }

    /** Documents read / found. */
    fun interface Progress {
        fun update(done: Int, total: Int)
    }

    class NotJsonException : IOException("not a JSON file")

    private fun baseName(name: String): String {
        val slash = maxOf(name.lastIndexOf('/'), name.lastIndexOf('\\'))
        return if (slash < 0) name else name.substring(slash + 1)
    }

    private fun lower(s: String): String = buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') c + 32 else c) }

    private fun extension(name: String): String {
        val b = baseName(name)
        val dot = b.lastIndexOf('.')
        return if (dot < 0) "" else lower(b.substring(dot))
    }

    /** A string member, "" when missing or null. */
    private fun text(row: JsonObject, key: String): String {
        val v = row[key] as? JsonPrimitive ?: return ""
        return if (v.isString) v.content else ""
    }

    private fun present(row: JsonObject, key: String): Boolean {
        val v = row[key] ?: return false
        return v !is JsonNull
    }

    private fun integer(row: JsonObject, key: String): Long {
        val v = row[key] as? JsonPrimitive ?: return -1
        if (v.isString || v is JsonNull) return -1
        val c = v.content
        c.toLongOrNull()?.let { return it }
        if (c == "true" || c == "false") return -1
        return c.toDoubleOrNull()?.toLong() ?: -1
    }

    private fun digits(s: String, at: Int, n: Int): Int {
        if (at < 0 || at + n > s.length) return -1
        var v = 0
        for (i in at until at + n) {
            val c = s[i]
            if (c !in '0'..'9') return -1
            v = v * 10 + (c - '0')
        }
        return v
    }

    /**
     * A stable id for a song known by name only (account data, a row without a URI):
     * "import:<FNV-1a of name + "|" + artist, ASCII-lower-cased bytes>".
     */
    fun importId(name: String, artist: String): String {
        var h = -0x340d631b7bdddcdbL // 14695981039346656037
        fun mix(s: String) {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                var c = b.toInt() and 0xFF
                if (c in 'A'.code..'Z'.code) c += 32
                h = h xor c.toLong()
                h *= 1099511628211L
            }
        }
        mix(name)
        mix("|")
        mix(artist)
        return "import:" + java.lang.Long.toUnsignedString(h, 16).padStart(16, '0')
    }

    /**
     * Unix seconds of "2023-04-05T12:34:56Z" (fraction / offset allowed) or "2023-04-05 12:34" (UTC); -1 when
     * malformed.
     */
    fun parseUtc(s: String): Long {
        // YYYY-MM-DD, then 'T' or ' ', HH:MM, optional :SS(.fff), optional Z / +hh:mm / -hh:mm.
        val y = digits(s, 0, 4)
        val mo = digits(s, 5, 2)
        val d = digits(s, 8, 2)
        if (y < 1970 || mo < 1 || mo > 12 || d < 1 || d > 31 || s.length < 16 || s[4] != '-' || s[7] != '-' ||
            (s[10] != 'T' && s[10] != ' ') || s[13] != ':'
        ) return -1
        val h = digits(s, 11, 2)
        val mi = digits(s, 14, 2)
        if (h < 0 || h > 23 || mi < 0 || mi > 59) return -1
        var sec = 0
        var at = 16
        if (at < s.length && s[at] == ':') {
            sec = digits(s, at + 1, 2)
            if (sec < 0 || sec > 60) return -1
            at += 3
            if (at < s.length && s[at] == '.') {
                at++
                while (at < s.length && s[at] in '0'..'9') at++
            }
        }
        var offset = 0L
        if (at < s.length) {
            if (s[at] == 'Z' || s[at] == 'z') {
                at++
            } else if (s[at] == '+' || s[at] == '-') {
                val oh = digits(s, at + 1, 2)
                val mAt = if (at + 3 < s.length && s[at + 3] == ':') at + 4 else at + 3
                val om = digits(s, mAt, 2)
                if (oh < 0 || om < 0) return -1
                offset = (if (s[at] == '+') 1 else -1) * (oh * 3600L + om * 60L)
                at = mAt + 2
            }
        }
        if (at != s.length) return -1
        return Listen.daysFromCivil(y, mo, d) * 86400 + h * 3600L + mi * 60L + sec - offset
    }

    /**
     * Whether a file / ZIP entry name (any folder) holds song history: Streaming_History_Audio*, endsong*,
     * StreamingHistory_music*, StreamingHistory<n> (.json). Video and podcast files are not.
     */
    fun isHistoryEntry(name: String): Boolean {
        val b = lower(baseName(name))
        if (b.length < 6 || !b.endsWith(".json")) return false
        if (b.contains("video") || b.contains("podcast")) return false
        if (b.startsWith("streaming_history_audio") || b.startsWith("endsong") || b.startsWith("streaminghistory_music"))
            return true
        // Account data before 2023: StreamingHistory0.json, StreamingHistory1.json ...
        return b.startsWith("streaminghistory") && b.length > 16 && b[16] in '0'..'9'
    }

    /**
     * One JSON document (an array of rows): appends its songs to [out] and counts what it skipped (returned with the
     * format). Unknown = not a listening history (nothing appended); throws [NotJsonException] when it isn't JSON.
     */
    fun parse(document: String, out: MutableList<ListenStats.ImportRow>, skipped: IntArray): Format {
        val j: JsonElement = parseJsonOrNull(document) ?: throw NotJsonException()
        if (j !is JsonArray) return Format.Unknown
        var format = Format.Unknown
        val before = out.size
        val skippedBefore = skipped[0]
        for (r in j) {
            if (r !is JsonObject) continue
            if (r.containsKey("ts")) { // extended streaming history
                format = Format.Extended
                val name = text(r, "master_metadata_track_name")
                if (present(r, "episode_name") || present(r, "spotify_episode_uri") || present(r, "audiobook_title") ||
                    present(r, "audiobook_uri") || name.isEmpty()
                ) {
                    skipped[0]++
                    continue
                }
                val ended = parseUtc(text(r, "ts"))
                val ms = integer(r, "ms_played")
                if (ended < 0 || ms < 0) {
                    skipped[0]++
                    continue
                }
                val artist = text(r, "master_metadata_album_artist_name")
                val uri = text(r, "spotify_track_uri")
                val track = ListenTrack(
                    id = if (uri.startsWith("spotify:track:")) uri else importId(name, artist),
                    name = name,
                    artists = if (artist.isNotEmpty()) listOf(ArtistRef("", artist)) else emptyList(),
                    albumName = text(r, "master_metadata_album_album_name"),
                )
                out.add(ListenStats.ImportRow(track, ended, ms))
            } else if (r.containsKey("endTime")) { // account data
                format = Format.Account
                val name = text(r, "trackName")
                if (present(r, "episodeName") || present(r, "podcastName") || name.isEmpty()) {
                    skipped[0]++
                    continue
                }
                val ended = parseUtc(text(r, "endTime"))
                val ms = integer(r, "msPlayed")
                if (ended < 0 || ms < 0) {
                    skipped[0]++
                    continue
                }
                val artist = text(r, "artistName")
                val track = ListenTrack(
                    id = importId(name, artist),
                    name = name,
                    artists = if (artist.isNotEmpty()) listOf(ArtistRef("", artist)) else emptyList(),
                )
                out.add(ListenStats.ImportRow(track, ended, ms))
            }
        }
        if (format == Format.Unknown) { // no row looked like a history row: leave everything as it was
            while (out.size > before) out.removeAt(out.size - 1)
            skipped[0] = skippedBefore
        }
        return format
    }

    /** Reads at most [MAX_DOCUMENT] bytes. */
    private fun readCapped(input: InputStream, what: String): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_DOCUMENT) throw IOException("$what too large")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** History entry names of a ZIP (the first pass: no content is kept). */
    private fun zipHistoryEntries(source: Source): List<String> {
        val names = ArrayList<String>()
        source.open().use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var any = false
                while (true) {
                    val e = zip.nextEntry ?: break
                    any = true
                    // PowerShell 5.1's Compress-Archive writes '\'.
                    val name = e.name.replace('\\', '/')
                    if (!e.isDirectory && isHistoryEntry(name)) names.add(name)
                }
                if (!any) throw IOException("not a ZIP file")
            }
        }
        return names
    }

    /**
     * Reads the given .json / .zip sources (other files are reported as errors). [cancel] stops between documents.
     */
    fun read(sources: List<Source>, progress: Progress? = null, cancel: AtomicBoolean? = null): Result {
        val res = Result()
        // First the plan: every JSON file, and the history entries of every ZIP (so progress has a total).
        class Doc(val source: Source, val entries: List<String>?)

        val docs = ArrayList<Doc>()
        var total = 0
        for (src in sources) {
            try {
                when (extension(src.name)) {
                    ".json" -> {
                        docs.add(Doc(src, null))
                        total++
                    }
                    ".zip" -> {
                        val entries = zipHistoryEntries(src)
                        if (entries.isEmpty()) res.unrecognized++
                        else {
                            docs.add(Doc(src, entries))
                            total += entries.size
                        }
                    }
                    else -> throw IOException("not a .json or .zip file")
                }
            } catch (e: Exception) {
                res.errors.add("${src.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        progress?.update(0, total)
        var done = 0
        val skipped = IntArray(1)
        fun parseDoc(label: String, content: String) {
            val before = res.rows.size
            skipped[0] = res.skippedRows
            val format = parse(content, res.rows, skipped)
            res.skippedRows = skipped[0]
            if (format == Format.Unknown) res.unrecognized++ else res.documents++
            Log.i(TAG, "import: $label -> ${res.rows.size - before} rows")
        }
        outer@ for (d in docs) {
            if (cancel?.get() == true) {
                res.cancelled = true
                break
            }
            if (d.entries == null) {
                try {
                    parseDoc(d.source.name, d.source.open().use { readCapped(it, "file") })
                } catch (e: Exception) {
                    res.errors.add("${d.source.name}: ${e.message ?: e.javaClass.simpleName}")
                }
                progress?.update(++done, total)
                continue
            }
            // A ZIP: walk it once more and parse its history entries as they come.
            val wanted = d.entries.toMutableSet()
            try {
                d.source.open().use { raw ->
                    ZipInputStream(raw.buffered()).use { zip ->
                        while (wanted.isNotEmpty()) {
                            if (cancel?.get() == true) {
                                res.cancelled = true
                                break
                            }
                            val e = zip.nextEntry ?: break
                            val name = e.name.replace('\\', '/')
                            if (!wanted.remove(name)) continue
                            val label = d.source.name + "/" + baseName(name)
                            try {
                                if (e.size > MAX_DOCUMENT || e.compressedSize > MAX_DOCUMENT) throw IOException("entry too large")
                                // Reading through the entry checks its CRC (ZipException on a mismatch).
                                parseDoc(label, readCapped(zip, "entry"))
                            } catch (ex: Exception) {
                                res.errors.add("$label: ${ex.message ?: ex.javaClass.simpleName}")
                                if (ex is java.util.zip.ZipException) throw ex // the stream is unusable from here
                            }
                            progress?.update(++done, total)
                        }
                    }
                }
            } catch (e: Exception) {
                res.errors.add("${d.source.name}: ${e.message ?: e.javaClass.simpleName}")
            }
            // Entries the second walk never reached (a broken archive) still count as done for the progress.
            for (n in wanted) progress?.update(++done, total)
            if (res.cancelled) break@outer
        }
        for (e in res.errors) Log.w(TAG, "import: $e")
        return res
    }

    /** Files on disk (tests, dev imports). */
    fun readFiles(files: List<File>, progress: Progress? = null, cancel: AtomicBoolean? = null): Result =
        read(files.map { Source.of(it) }, progress, cancel)
}
