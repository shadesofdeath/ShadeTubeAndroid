package com.app.shadetube.localfiles

import com.app.shadetube.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.CollationKey
import java.text.Collator
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** An audio file a [FolderLister] found. */
data class FoundFile(
    /** Canonical location (see [LocalPaths]): the entry's path and id. */
    val path: String,
    /** content:// URI to open it, "" = [path] is a plain file. */
    val uri: String = "",
    /** File name with extension. */
    val name: String = LocalPaths.nameOf(path),
    val size: Long = 0,
    /** Unix milliseconds. */
    val mtime: Long = 0,
    /** MediaStore's columns for it, when it came from MediaStore (they fill what the file's own tags leave out). */
    val store: LocalTags? = null,
    /** The mime type the provider reports ("" = unknown); an opened file without an extension is audio by it. */
    val mime: String = "",
)

/** How listing one music folder went. */
data class WalkResult(
    /** Folders (canonical locations / URIs) that couldn't be listed (completely or partly): their tracks are kept. */
    val unlisted: List<String> = emptyList(),
    /** The music folder itself couldn't be reached (removed SD card, revoked folder access, no permission). */
    val rootMissing: Boolean = false,
    /** The media permission is missing (READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE): the UI should ask for it. */
    val permissionDenied: Boolean = false,
    /** Folder images (cover.jpg, folder.jpg, ...) by lower-cased canonical folder. */
    val folderArt: Map<String, String> = emptyMap(),
)

/** Lists the audio files of a music folder (MediaStore, a SAF tree, or the file system). Blocking. */
interface FolderLister {
    /** Calls [onFile] for each audio file under [folder]; [onFile] returns false to stop the walk (the cap). */
    fun walk(folder: String, onFile: (FoundFile) -> Boolean): WalkResult
}

/** Reads the tags of a found file; null when it can't be opened at all. Blocking. */
fun interface TagSource {
    fun read(file: FoundFile, wantPicture: Boolean): LocalTags?
}

/** Port of local::ScanStats. */
data class ScanStats(
    /** Audio files in the result. */
    val files: Int = 0,
    /** Unchanged since the previous index (not re-read). */
    val reused: Int = 0,
    /** New or changed: tags read. */
    val read: Int = 0,
    /** Unreadable, skipped. */
    val failed: Int = 0,
    /** In the previous index, gone now. */
    val removed: Int = 0,
    /** Kept from the previous index because their folder couldn't be listed. */
    val carried: Int = 0,
    /** Stopped at maxFiles. */
    val capped: Boolean = false,
    /** Some folder couldn't be listed (its entries were carried over). */
    val incomplete: Boolean = false,
    /** Listed folders that couldn't be reached at all. */
    val missingRoots: List<String> = emptyList(),
    /** Android: the media permission is missing (MediaStore folders could not be read). */
    val permissionDenied: Boolean = false,
)

class ScanOptions(
    val folders: List<String>,
    /** Extracted covers go here (null = no covers). */
    val coverDir: File?,
    val maxFiles: Int = LocalFormats.MAX_FILES,
    /** Tag readers for the new / changed files. */
    val readThreads: Int = 3,
    /** Any thread; total = files to read. */
    val progress: ((done: Int, total: Int) -> Unit)? = null,
)

/**
 * The folder scan (port of local::scan / readFile): walks the folders, keeps `previous` entries whose size + mtime
 * still match and reads the rest; sorted by artist, album, disc, track number, title.
 *
 * A folder that can't be listed (removed SD card, revoked folder access, missing permission) is not "empty": its
 * indexed tracks are carried over unchanged, so they come back as soon as it is reachable again. Only a folder that
 * is really gone from a reachable volume drops its tracks. The user's files are only ever read.
 *
 * Cancellation is the coroutine's: a cancelled scan throws and its partial result is never used.
 */
object LocalScanner {
    private const val TAG = "local"

    suspend fun scan(
        o: ScanOptions,
        previous: List<LocalEntry>,
        lister: FolderLister,
        tags: TagSource,
    ): Pair<List<LocalEntry>, ScanStats> = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        // 1. Walk the folders.
        val found = ArrayList<FoundFile>()
        val carried = HashSet<Int>()
        val unlisted = ArrayList<String>()
        val missingRoots = ArrayList<String>()
        val seen = HashSet<String>() // lower-cased paths (nested / duplicate folders)
        val art = HashMap<String, String>()
        var capped = false
        var permissionDenied = false
        for (folder in o.folders) {
            if (capped) break
            ensureActive()
            val root = LocalPaths.normalizeFolder(folder)
            if (root.isEmpty()) continue
            val r = lister.walk(root) { f ->
                if (!isActive) return@walk false
                if (!acceptable(f)) return@walk true
                if (!seen.add(f.path.lowercase(Locale.ROOT))) return@walk true
                if (found.size >= o.maxFiles) {
                    capped = true
                    return@walk false
                }
                found += f
                true
            }
            unlisted += r.unlisted
            if (r.rootMissing) missingRoots += root
            permissionDenied = permissionDenied || r.permissionDenied
            art.putAll(r.folderArt)
            for (u in r.unlisted) Log.w(TAG, "can't list $u: its indexed tracks are kept")
        }
        if (capped) Log.w(TAG, "more than ${o.maxFiles} audio files in the music folders: the rest is skipped")
        ensureActive()

        // A folder that couldn't be listed is not empty: its indexed files stay as they were.
        var carriedCount = 0
        if (unlisted.isNotEmpty()) {
            for (e in previous) {
                val key = e.path.lowercase(Locale.ROOT)
                if (key in seen || unlisted.none { LocalPaths.isUnder(e.path, it) }) continue
                seen += key
                carried += found.size
                found += FoundFile(e.path, e.uri, e.fileName, e.size, e.mtime)
                carriedCount++
            }
        }

        // 2. Unchanged files keep their index entry; the rest is read.
        val prev = HashMap<String, LocalEntry>(previous.size * 2)
        for (e in previous) prev[e.path.lowercase(Locale.ROOT)] = e
        val entries = arrayOfNulls<LocalEntry>(found.size)
        val todo = ArrayList<Int>()
        var reused = 0
        for ((i, f) in found.withIndex()) {
            val p = prev[f.path.lowercase(Locale.ROOT)]
            if (i in carried && p != null) {
                entries[i] = if (p.embeddedCover && !File(p.cover).exists()) p.copy(cover = "", embeddedCover = false) else p // can't re-extract it now
            } else if (p != null && p.size == f.size && p.mtime == f.mtime && (!p.embeddedCover || File(p.cover).exists())) {
                entries[i] = p.copy(path = f.path, uri = f.uri, name = f.name.ifEmpty { p.name })
                reused++
            } else {
                todo += i
            }
        }
        val removed = previous.count { it.path.lowercase(Locale.ROOT) !in seen }

        // 3. Read tags (+ covers) of new / changed files on a few workers.
        val ok = BooleanArray(found.size) { true }
        var read = 0
        if (todo.isNotEmpty()) {
            val total = todo.size
            val next = AtomicInteger(0)
            val done = AtomicInteger(0)
            o.progress?.invoke(0, total)
            val workers = (minOf(o.readThreads, total / 32 + 1)).coerceIn(1, 8)
            coroutineScope {
                repeat(workers) {
                    launch {
                        while (true) {
                            val k = next.getAndIncrement()
                            if (k >= todo.size) break
                            ensureActive()
                            val i = todo[k]
                            val e = try {
                                readEntry(found[i], tags, o.coverDir)
                            } catch (c: kotlinx.coroutines.CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                null
                            }
                            if (e == null) ok[i] = false else entries[i] = e
                            val d = done.incrementAndGet()
                            o.progress?.invoke(d, total)
                        }
                    }
                }
            }
            ensureActive()
            read = total
        }
        val out = ArrayList<LocalEntry>(found.size)
        var failed = 0
        for (i in found.indices) {
            val e = entries[i]
            if (!ok[i] || e == null) {
                failed++
                Log.i(TAG, "unreadable, skipped: ${found[i].path}")
                continue
            }
            out += if (!e.embeddedCover && i !in carried) e.copy(cover = art[LocalPaths.dirOf(e.path).lowercase(Locale.ROOT)] ?: "") else e
        }
        read -= minOf(read, failed)

        // 4. Artist (album artist first, so compilations stay together), album, disc, track, title.
        val sorted = sortEntries(out)
        val stats = ScanStats(
            files = sorted.size, reused = reused, read = read, failed = failed, removed = removed, carried = carriedCount,
            capped = capped, incomplete = unlisted.isNotEmpty(), missingRoots = missingRoots,
            permissionDenied = permissionDenied,
        )
        Log.i(
            TAG,
            "scan: ${stats.files} files (${stats.reused} unchanged, ${stats.read} read, ${stats.failed} unreadable, " +
                "${stats.removed} removed, ${stats.carried} kept from unreachable folders) in ${(System.nanoTime() - started) / 1_000_000} ms",
        )
        sorted to stats
    }

    /** The walk rules for files: audio by extension (or the provider's mime), no "._" resource forks, a sane size. */
    fun acceptable(f: FoundFile): Boolean {
        val name = f.name
        if (name.startsWith("._") || !LocalPaths.validUtf16(name)) return false // macOS resource forks / unstorable names
        if (!LocalFormats.isAudioFile(name) && !(LocalFormats.extensionOf(name).isEmpty() && f.mime.startsWith("audio/"))) return false
        return f.size > 0 && f.size <= LocalFormats.MAX_FILE_BYTES
    }

    /**
     * Tags + embedded cover of one file (port of local::readFile): null when it can't be opened at all. Covers are
     * extracted once into [coverDir]; the file name gives what the tags leave out.
     */
    fun readEntry(f: FoundFile, tags: TagSource, coverDir: File?): LocalEntry? {
        val t = tags.read(f, coverDir != null) ?: return null
        return entryFrom(f, t, coverDir)
    }

    /** Builds the entry of [f] from what its tags say. */
    fun entryFrom(f: FoundFile, t: LocalTags, coverDir: File?): LocalEntry {
        val cover = if (coverDir != null) TagReader.saveCover(t.picture, coverDir) else ""
        var e = LocalEntry(
            path = f.path, size = f.size, mtime = f.mtime,
            title = t.title, artists = t.artists, albumArtist = t.albumArtist, album = t.album,
            discNumber = t.discNumber.coerceIn(0, 999), trackNumber = t.trackNumber.coerceIn(0, 9999),
            year = t.year.coerceIn(0, 9999), durationMs = t.durationMs.coerceAtLeast(0),
            tagged = t.title.isNotEmpty(), cover = cover, embeddedCover = cover.isNotEmpty(),
            name = f.name, uri = f.uri,
        )
        if (e.discNumber == 0) e = e.copy(discNumber = discFromFolder(LocalPaths.parentName(f.path)))
        if (e.artists.isEmpty() && e.albumArtist.isNotEmpty()) e = e.copy(artists = listOf(e.albumArtist))
        return fromFileName(e)
    }

    // ---- file name fallback ("01 - Artist - Title.mp3") -----------------------------------------------------------
    private fun isDash(c: Char) = c == '-' || c == '–' || c == '—'

    private fun trim(s: String) = s.trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' || it == '\u0000' }

    /** Title (and artist, track number) from the file name, for what the tags leave empty. */
    fun fromFileName(entry: LocalEntry): LocalEntry {
        val n = entry.fileName
        val dot = n.lastIndexOf('.')
        var stem = if (dot > 0) n.substring(0, dot) else n
        var e = entry
        if (stem.indexOf(' ') < 0) stem = stem.replace('_', ' ')
        // Leading track number: "01 - ", "01 – ", "1. ", "01) ", "07_", or a zero-padded "01 " ("50 Cent" stays).
        var d = 0
        var number = 0
        while (d < stem.length && d < 3 && stem[d] in '0'..'9') number = number * 10 + (stem[d++] - '0')
        if (d > 0 && d < stem.length) {
            var rest = -1
            val c = stem[d]
            if (c == '.' || c == ')' || c == '_' || isDash(c)) rest = d + 1
            else if (c == ' ' && d + 2 < stem.length && isDash(stem[d + 1]) && stem[d + 2] == ' ') rest = d + 3
            else if (c == ' ' && stem[0] == '0') rest = d + 1
            if (rest >= 0) {
                while (rest < stem.length && (stem[rest] == ' ' || isDash(stem[rest]))) rest++
                if (rest < stem.length) {
                    if (e.trackNumber == 0) e = e.copy(trackNumber = number)
                    stem = stem.substring(rest)
                }
            }
        }
        // "Artist - Title": split at the earliest " - " / " – " / " — ".
        var artist = ""
        var title = stem
        var at = -1
        var sepLen = 0
        for (sep in listOf(" - ", " – ", " — ")) {
            val pos = stem.indexOf(sep)
            if (pos > 0 && (at < 0 || pos < at)) {
                at = pos
                sepLen = sep.length
            }
        }
        if (at >= 0) {
            artist = stem.substring(0, at)
            title = stem.substring(at + sepLen)
        }
        if (e.title.isEmpty()) e = e.copy(title = trim(title.ifEmpty { stem }))
        if (e.artists.isEmpty() && e.albumArtist.isEmpty() && artist.isNotEmpty()) e = e.copy(artists = listOf(trim(artist)))
        return e
    }

    /** Disc from the album's folder name: "CD2", "CD 2", "Disc 2", "Disk 2 - Bonus" -> 2; 0 otherwise. */
    fun discFromFolder(folderName: String): Int {
        val name = folderName.lowercase(Locale.ROOT)
        for (prefix in listOf("cd", "disc", "disk"))
            if (name.startsWith(prefix)) return TagReader.leadingNumber(name.substring(prefix.length))
        return 0
    }

    // ---- sorting --------------------------------------------------------------------------------------------------
    /**
     * Sort key of a text, as Windows' CompareStringEx(LINGUISTIC_IGNORECASE | SORT_DIGITSASNUMBERS): case ignored,
     * runs of digits compared as numbers. Empty ("untagged") sorts last.
     */
    class TextKey(val empty: Boolean, private val parts: List<Any>) : Comparable<TextKey> {
        override fun compareTo(other: TextKey): Int {
            if (empty != other.empty) return if (empty) 1 else -1
            for (i in 0 until minOf(parts.size, other.parts.size)) {
                val a = parts[i]
                val b = other.parts[i]
                val c = when {
                    a is Long && b is Long -> a.compareTo(b)
                    a is Long -> -1 // numbers before letters
                    b is Long -> 1
                    else -> (a as CollationKey).compareTo(b as CollationKey)
                }
                if (c != 0) return c
            }
            return parts.size.compareTo(other.parts.size)
        }
    }

    fun textKey(collator: Collator, s: String): TextKey {
        if (s.isEmpty()) return TextKey(true, emptyList())
        val parts = ArrayList<Any>()
        var i = 0
        while (i < s.length) {
            val start = i
            if (s[i] in '0'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
                val digits = s.substring(start, i).trimStart('0')
                parts += if (digits.length <= 18) (digits.toLongOrNull() ?: 0L) else collator.getCollationKey(digits)
            } else {
                while (i < s.length && s[i] !in '0'..'9') i++
                parts += collator.getCollationKey(s.substring(start, i))
            }
        }
        return TextKey(false, parts)
    }

    /** A case-insensitive collator of the device language (LOCALE_NAME_USER_DEFAULT). */
    fun collator(): Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }

    /** Artist (album artist first), album, disc (untagged = 1), track number, title, path. Stable. */
    fun sortEntries(entries: List<LocalEntry>): List<LocalEntry> {
        val col = collator()
        class K(val e: LocalEntry, val artist: TextKey, val album: TextKey, val title: TextKey, val lowerPath: String)
        val keys = entries.map { e ->
            K(
                e,
                textKey(col, e.albumArtist.ifEmpty { e.artists.firstOrNull() ?: "" }),
                textKey(col, e.album),
                textKey(col, e.title),
                e.path.lowercase(Locale.ROOT),
            )
        }
        return keys.sortedWith { x, y ->
            var c = x.artist.compareTo(y.artist)
            if (c == 0) c = x.album.compareTo(y.album)
            if (c == 0) c = maxOf(x.e.discNumber, 1).compareTo(maxOf(y.e.discNumber, 1))
            if (c == 0) c = x.e.trackNumber.compareTo(y.e.trackNumber)
            if (c == 0) c = x.title.compareTo(y.title)
            if (c == 0) c = x.lowerPath.compareTo(y.lowerPath)
            c
        }.map { it.e }
    }
}

/**
 * Lists a folder of the file system (java.io.File): app-private folders, and the JVM tests. The walk rules of the
 * C++ scanner: "." folders and symbolic links are not entered; a folder missing from a reachable volume is gone,
 * one whose volume is missing (or that can't be listed) is unreachable.
 */
class FileFolderLister : FolderLister {
    override fun walk(folder: String, onFile: (FoundFile) -> Boolean): WalkResult {
        if (!LocalPaths.isAbsolute(folder) || folder.startsWith("saf://")) return WalkResult(listOf(folder), rootMissing = true)
        val root = File(folder)
        val unlisted = ArrayList<String>()
        val art = HashMap<String, String>()
        var rootMissing = false
        val dirs = ArrayDeque<File>()
        dirs.addLast(root)
        while (dirs.isNotEmpty()) {
            val dir = dirs.removeLast()
            val children = dir.listFiles()
            val dirPath = LocalPaths.normalizePath(dir.invariantSeparatorsPath)
            if (children == null) {
                // Gone from a reachable volume (or not a folder): empty. Otherwise only unreachable right now.
                val volume = File(LocalPaths.volumeRoot(dirPath))
                if (dir.exists() || !volume.exists()) {
                    unlisted += dirPath
                    if (dir == root) rootMissing = true
                }
                continue
            }
            val images = HashMap<String, String>()
            for (c in children.sortedBy { it.name }) {
                val name = c.name
                if (c.isDirectory) {
                    val link = runCatching { java.nio.file.Files.isSymbolicLink(c.toPath()) }.getOrDefault(true)
                    if (!link && name.isNotEmpty() && !name.startsWith(".") && LocalPaths.validUtf16(name)) dirs.addLast(c)
                    continue
                }
                if (name.startsWith(".")) continue // hidden files
                val lower = name.lowercase(Locale.ROOT)
                if (lower.substringBeforeLast('.') in ART_NAMES && LocalFormats.extensionOf(lower) in ART_EXTS) {
                    images[lower] = LocalPaths.normalizePath(c.invariantSeparatorsPath)
                    continue
                }
                if (!LocalFormats.isAudioFile(name)) continue
                val f = FoundFile(LocalPaths.normalizePath(c.invariantSeparatorsPath), "", name, c.length(), c.lastModified())
                if (!onFile(f)) return WalkResult(unlisted, rootMissing, folderArt = art)
            }
            pickFolderArt(images)?.let { art[dirPath.lowercase(Locale.ROOT)] = it }
        }
        return WalkResult(unlisted, rootMissing, folderArt = art)
    }

    companion object {
        val ART_NAMES = listOf("cover", "folder", "front", "album")
        val ART_EXTS = listOf(".jpg", ".jpeg", ".png")

        /** cover.jpg before folder.jpg before front / album; .jpg before .jpeg before .png. Keys lower-cased names. */
        fun pickFolderArt(images: Map<String, String>): String? {
            for (n in ART_NAMES) for (x in ART_EXTS) images["$n$x"]?.let { return it }
            return null
        }
    }
}

/** Tags of plain files (app-private folders, tests): the own tag reader. */
class FileTagSource : TagSource {
    override fun read(file: FoundFile, wantPicture: Boolean): LocalTags? {
        val t = TagReader.read(File(file.path), wantPicture) ?: return null
        return if (file.store != null) t.merge(file.store) else t
    }
}
