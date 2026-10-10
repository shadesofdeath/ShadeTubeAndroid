package com.app.shadetube.localfiles

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.LOCAL_ID_PREFIX
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Files
import com.app.shadetube.core.Hash
import com.app.shadetube.core.arr
import com.app.shadetube.core.asBool
import com.app.shadetube.core.asInt
import com.app.shadetube.core.asLong
import com.app.shadetube.core.bool
import com.app.shadetube.core.get
import com.app.shadetube.core.int
import com.app.shadetube.core.isObject
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.long
import com.app.shadetube.core.stringOrNull
import com.app.shadetube.core.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale

/**
 * One audio file of the local library (port of local::Entry).
 *
 * [path] is the file's identity: an absolute path ("/storage/emulated/0/Music/a.mp3", also for a SAF document of the
 * external storage provider, whose document ids are paths) or, for other document providers, the document URI. The
 * id is derived from it, so the same file found through MediaStore and through a picked folder is one track.
 * [uri] is how it is opened: a content:// URI (MediaStore item / SAF document), "" = open [path] as a file.
 */
data class LocalEntry(
    val path: String = "",
    val size: Long = 0,
    /** Last modification, unix milliseconds (the C++ index stores FILETIME ticks; Android has no such clock). */
    val mtime: Long = 0,
    /** Tag, else from the file name. */
    val title: String = "",
    val artists: List<String> = emptyList(),
    val albumArtist: String = "",
    val album: String = "",
    /** 0 = untagged (sorts with disc 1). */
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val year: Int = 0,
    /** 0 = unknown (the player learns it from the decoder). */
    val durationMs: Int = 0,
    /** The title came from the file's tags. */
    val tagged: Boolean = false,
    /** Image file / URI, "" = none. */
    val cover: String = "",
    /** [cover] is our extracted copy (else the user's own folder image). */
    val embeddedCover: Boolean = false,
    /** Android: the file name with its extension (a content URI need not show it). "" = the last path segment. */
    val name: String = "",
    /** Android: content URI to open, "" = [path] is a plain file. */
    val uri: String = "",
) {
    /** File name with extension. */
    val fileName: String get() = name.ifEmpty { LocalPaths.nameOf(path) }
}

/** What local-library.json stores next to the files (port of local::IndexMeta). */
data class IndexMeta(
    /** Unix seconds of the scan that wrote it. */
    val scannedAt: Long = 0,
    /** The folders that scan covered. */
    val folders: List<String> = emptyList(),
    /** It stopped at [LocalFormats.MAX_FILES]. */
    val capped: Boolean = false,
)

/** Scanned extensions, ids, mime types (port of local::audioExtensions / trackIdFor / fileUrl). */
object LocalFormats {
    /** Per scan over all folders; the rest is skipped (logged). */
    const val MAX_FILES = 50_000

    /** A file bigger than this is not a song (the C++ player read the whole file into memory). */
    const val MAX_FILE_BYTES = 1L shl 30

    /**
     * Extensions scanned (lower case, with the dot). The C++ list is "what Media Foundation decodes": .mp3 .m4a .aac
     * .flac .wav .wma, plus Ogg / Opus when a handler is installed. On Android ExoPlayer always plays Ogg / Opus,
     * and never WMA (no decoder), so .wma is left out: it would only list songs that can't play.
     */
    val AUDIO_EXTENSIONS: List<String> = listOf(".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".oga", ".opus")

    fun extensionOf(name: String): String {
        val n = LocalPaths.nameOf(name)
        val dot = n.lastIndexOf('.')
        // std::filesystem: ".mp3" alone is a stem without an extension.
        return if (dot <= 0) "" else n.substring(dot).lowercase(Locale.ROOT)
    }

    fun isAudioFile(name: String): Boolean = extensionOf(name) in AUDIO_EXTENSIONS

    /** "local:0123456789abcdef": FNV-1a 64 of the lower-cased path (stable across rescans). */
    fun trackIdFor(path: String): String = LOCAL_ID_PREFIX + Hash.fnv1a64Hex(path.lowercase(Locale.ROOT))

    /** Same values as the C++ Player::localMimeType for the scanned extensions. */
    fun mimeFor(name: String): String = when (extensionOf(name)) {
        ".mp3" -> "audio/mpeg"
        ".m4a" -> "audio/mp4"
        ".aac" -> "audio/aac"
        ".flac" -> "audio/flac"
        ".wav" -> "audio/wav"
        ".wma" -> "audio/x-ms-wma"
        ".ogg", ".oga", ".opus" -> "audio/ogg"
        else -> "audio/*"
    }

    /**
     * "file:///storage/emulated/0/Kapak%20klas%C3%B6r%C3%BC/a%20b.jpg" (what the image loader opens). A content URI
     * (a folder image found through SAF) is returned as it is.
     */
    fun fileUrl(path: String): String {
        if (path.startsWith("content://") || path.startsWith("file://")) return path
        val p = path.replace('\\', '/')
        val sb = StringBuilder(if (p.startsWith("/")) "file://" else "file:///")
        val hex = "0123456789ABCDEF"
        for (b in p.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '/' || ch == ':' || ch == '-' || ch == '_' ||
                ch == '.' || ch == '~'
            ) {
                sb.append(ch)
            } else {
                sb.append('%').append(hex[c shr 4]).append(hex[c and 15])
            }
        }
        return sb.toString()
    }

    /**
     * The catalog track of an entry: no artist / album ids (local artists open nothing online), the cover as a
     * file:/// (or content://) image, the file's date standing in for "added".
     */
    fun toTrack(e: LocalEntry): Track = Track(
        id = trackIdFor(e.path),
        name = e.title,
        artists = e.artists.map { ArtistRef("", it) },
        album = AlbumRef("", e.album, if (e.cover.isNotEmpty()) listOf(Image(fileUrl(e.cover), 0, 0)) else emptyList()),
        durationMs = e.durationMs,
        trackNumber = e.trackNumber,
        addedAt = if (e.mtime > 0) e.mtime / 1000 else 0,
    )
}

/**
 * local-library.json / dropped-files.json (port of local::loadIndex / saveIndex):
 * {"version":2,"scannedAt":<unix>,"folders":[...],"capped":false,"files":[[path,size,mtime,title,[artists],
 *  albumArtist,album,disc,track,year,durMs,tagged,cover,embeddedCover,name,uri], ...]}
 * (arrays keep a large library's index small to parse). Android appends two columns (file name, content URI); a
 * reader of the 14-column C++ shape ignores them, and 14-column rows still load here. Version 1 (no disc column) is
 * not read: every file is read once again.
 */
object LocalIndex {
    fun load(file: File): Pair<List<LocalEntry>, IndexMeta> {
        val j = Files.readJson(file) ?: return emptyList<LocalEntry>() to IndexMeta()
        if (!j.isObject || j.int("version") != 2) return emptyList<LocalEntry>() to IndexMeta()
        val meta = IndexMeta(j.long("scannedAt"), j.strings("folders"), j.bool("capped"))
        val out = ArrayList<LocalEntry>()
        for (a in j["files"].arr) {
            val row = a as? JsonArray ?: continue
            if (row.size < 14) continue
            val path = row[0].stringOrNull ?: continue
            if (path.isEmpty()) continue
            // A malformed row is dropped (re-read by the next scan).
            runCatching {
                fun s(i: Int) = (row[i] as JsonPrimitive).also { require(it.isString) }.content
                out += LocalEntry(
                    path = path,
                    size = row[1].asLong(),
                    mtime = row[2].asLong(),
                    title = s(3),
                    artists = (row[4] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        ?: emptyList(),
                    albumArtist = s(5),
                    album = s(6),
                    discNumber = row[7].asInt(),
                    trackNumber = row[8].asInt(),
                    year = row[9].asInt(),
                    durationMs = row[10].asInt(),
                    tagged = row[11].asBool(),
                    cover = s(12),
                    embeddedCover = row[13].asBool(),
                    name = if (row.size > 14) row[14].stringOrNull ?: "" else "",
                    uri = if (row.size > 15) row[15].stringOrNull ?: "" else "",
                )
            }
        }
        return out to meta
    }

    fun save(file: File, entries: List<LocalEntry>, meta: IndexMeta): Boolean {
        val files = JsonArray(
            entries.map { e ->
                JsonArray(
                    listOf(
                        JsonPrimitive(e.path), JsonPrimitive(e.size), JsonPrimitive(e.mtime), JsonPrimitive(e.title),
                        JsonArray(e.artists.map { JsonPrimitive(it) }), JsonPrimitive(e.albumArtist), JsonPrimitive(e.album),
                        JsonPrimitive(e.discNumber), JsonPrimitive(e.trackNumber), JsonPrimitive(e.year),
                        JsonPrimitive(e.durationMs), JsonPrimitive(e.tagged), JsonPrimitive(e.cover),
                        JsonPrimitive(e.embeddedCover), JsonPrimitive(e.name), JsonPrimitive(e.uri),
                    ),
                )
            },
        )
        val j = jsonOf(
            "version" to 2,
            "scannedAt" to meta.scannedAt,
            "folders" to meta.folders,
            "capped" to meta.capped,
            "files" to files,
        )
        return Files.writeJson(file, j)
    }

    /** Deletes extracted covers that no entry references any more. Returns the number removed. */
    fun pruneCovers(coverDir: File, entries: List<LocalEntry>): Int {
        val used = entries.filter { it.embeddedCover }.map { File(it.cover).absolutePath.lowercase(Locale.ROOT) }.toHashSet()
        var removed = 0
        for (f in coverDir.listFiles() ?: return 0) {
            if (!f.isFile || f.absolutePath.lowercase(Locale.ROOT) in used) continue
            if (f.delete()) removed++
        }
        return removed
    }
}
