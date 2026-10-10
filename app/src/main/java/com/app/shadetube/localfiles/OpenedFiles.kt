package com.app.shadetube.localfiles

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isLocalId
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Log
import com.app.shadetube.core.Time
import com.app.shadetube.core.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.floor

/** What [OpenedFiles.collect] found (port of dropped::Collected). */
data class Collected(
    /** Sorted like the library. */
    val entries: List<LocalEntry> = emptyList(),
    /** The opened folders (SAF trees, normalized), in the order given. */
    val folders: List<String> = emptyList(),
    /** Items that aren't audio or couldn't be read. */
    val skipped: Int = 0,
    /** Stopped at maxFiles. */
    val capped: Boolean = false,
)

/** How opened files are reached on the platform (ContentResolver on Android, plain files in tests). */
interface OpenedFileSource {
    /** Name, size, last modification and mime of a content URI / file URI / path; null when it can't be reached. */
    fun stat(item: String): FoundFile?

    /** Keeps read access to a content URI across restarts where the provider allows it (persistable grants). */
    fun retain(item: String) {}
}

/** The rules of opened / dropped songs (pure; port of the dropped:: helpers). */
object OpenedFilesRules {
    /** Per open / share / drop. */
    const val MAX_FILES = 2_000
    /** Files remembered across opens. */
    const val REMEMBER = 5_000

    /** An audio file by its extension, or a folder (a SAF tree). No disk access. */
    fun droppable(item: String): Boolean = LocalPaths.isTreeUri(item) || LocalFormats.isAudioFile(LocalPaths.nameOf(item))

    /** Adds [added] to [list] (a file already listed moves to the end with its new tags) and keeps the newest [cap]. */
    fun mergeRemembered(list: List<LocalEntry>, added: List<LocalEntry>, cap: Int = REMEMBER): List<LocalEntry> {
        // A file listed twice in `added` keeps its last occurrence.
        val last = HashMap<String, Int>()
        for ((i, e) in added.withIndex()) last[e.path.lowercase(Locale.ROOT)] = i
        val out = list.filterNot { it.path.lowercase(Locale.ROOT) in last }.toMutableList()
        for ((i, e) in added.withIndex()) if (last[e.path.lowercase(Locale.ROOT)] == i) out += e
        return if (out.size > cap) out.subList(out.size - cap, out.size).toList() else out
    }

    enum class DropRow { Liked, SpotifyPlaylist, LocalPlaylist }

    /**
     * The songs of [tracks] a sidebar row / playlist takes when they are dropped on it: Liked Songs the ones
     * [likeable] accepts, a Spotify playlist its Spotify songs, a local playlist every song with an id. Never radio
     * stations.
     */
    fun droppableOn(row: DropRow, tracks: List<Track>, likeable: ((String) -> Boolean)?): List<Track> = tracks.filter { t ->
        if (t.id.isEmpty() || t.id.startsWith("radio:")) return@filter false
        when (row) {
            DropRow.Liked -> likeable != null && likeable(t.id)
            DropRow.SpotifyPlaylist -> t.id.startsWith("spotify:track:")
            DropRow.LocalPlaylist -> true
        }
    }

    /**
     * Drop slot in the queue: the order index the songs go in front of, from the pointer's [y] below the top of the
     * upcoming list (scrolling included) with rows of [rowH]: the nearest row boundary, clamped to
     * [currentOrderIndex] + 1 .. [orderSize].
     */
    fun queueDropIndex(y: Float, rowH: Float, currentOrderIndex: Int, orderSize: Int): Int {
        val first = currentOrderIndex + 1
        if (rowH <= 0) return maxOf(first, orderSize)
        val k = floor(y / rowH + 0.5f).toInt()
        return (first + k).coerceIn(minOf(first, orderSize), orderSize)
    }
}

/**
 * Songs opened from other apps (ACTION_VIEW / share intents) - the Android form of the C++ "files dropped from
 * Explorer" (app/DroppedFiles): they become tracks the player can play, even outside the Yerel dosyalar folders.
 *
 * [collect] reads them on a worker: folders (SAF trees) through the library's scanner (same walk rules, tags and
 * cover extraction), single files through the same tag reader, at most [OpenedFilesRules.MAX_FILES] per call; sorted
 * like the library. Ids are the library's ("local:<hash of the path>"), so an opened file that is also in the
 * library is the same track.
 *
 * The store remembers them (dropped-files.json, the local index format, the newest [OpenedFilesRules.REMEMBER]),
 * so a restored queue, the history and the stats keep playing them after a restart; their covers live in
 * cache/dropped-covers and are pruned with the list. (A content URI another app shared only temporarily may become
 * unreadable after a restart: [fileFor] then returns null.)
 */
class OpenedFiles(
    private val storeFile: File,
    val coverDir: File,
    private val source: OpenedFileSource,
    private val listerFactory: () -> FolderLister,
    private val tagSource: TagSource,
    val opener: LocalFileOpener,
    private val scope: CoroutineScope = appScope,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var entries: List<LocalEntry> = emptyList() // oldest first
    /** Track id -> entry. Keeps what was forgotten by the cap until the next start: harmless, the queue may hold them. */
    @Volatile private var ids: Map<String, LocalEntry> = emptyMap()

    /** Reads the list (once); call at startup so restored queues resolve. */
    suspend fun load() = mutex.withLock { ensureLoaded() }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val list = withContext(AppDispatchers.io) { runCatching { LocalIndex.load(storeFile).first }.getOrDefault(emptyList()) }
        loaded = true
        entries = list
        ids = ids + list.associateBy { LocalFormats.trackIdFor(it.path) }
        Log.i(TAG, "${list.size} opened files remembered")
    }

    /** Remembers [added] (then saves and prunes covers on a worker). */
    fun remember(added: List<LocalEntry>) {
        if (added.isEmpty()) return
        ids = ids + added.associateBy { LocalFormats.trackIdFor(it.path) }
        scope.launch {
            mutex.withLock {
                ensureLoaded()
                entries = OpenedFilesRules.mergeRemembered(entries, added)
                val snapshot = entries
                withContext(AppDispatchers.io) {
                    if (!LocalIndex.save(storeFile, snapshot, IndexMeta(scannedAt = Time.nowUnix())))
                        Log.w(TAG, "saving ${storeFile.name} failed")
                    LocalIndex.pruneCovers(coverDir, snapshot)
                }
            }
        }
    }

    /** The file of a remembered "local:" id; null when unknown. */
    fun fileFor(trackId: String): LocalFile? {
        if (!isLocalId(trackId)) return null
        return ids[trackId]?.let { LocalFile.of(it) }
    }

    /** [fileFor], and only while the file can still be opened (blocking: call from a worker). */
    fun reachableFileFor(trackId: String): LocalFile? = fileFor(trackId)?.takeIf { opener.exists(it) }

    /**
     * Reads opened items: content / file URIs, paths, or SAF tree URIs (folders). Loose files first (in the order
     * given), then the folders' contents, all under one cap; a file given both on its own and inside a folder is
     * listed once.
     */
    suspend fun collect(items: List<String>, maxFiles: Int = OpenedFilesRules.MAX_FILES, coverDir: File? = this.coverDir): Collected =
        withContext(AppDispatchers.io) {
            var skipped = 0
            var capped = false
            val folders = ArrayList<String>()
            val files = ArrayList<FoundFile>()
            for (item in items) {
                if (LocalPaths.isTreeUri(item)) {
                    LocalPaths.normalizeFolder(item).takeIf { it.isNotEmpty() }?.let { folders += it }
                    continue
                }
                val f = source.stat(item)
                if (f == null || !LocalScanner.acceptable(f)) {
                    skipped++
                    continue
                }
                files += f
            }
            val seen = HashSet<String>()
            val out = ArrayList<LocalEntry>()
            for (f in files) {
                if (out.size >= maxFiles) {
                    capped = true
                    break
                }
                if (!seen.add(f.path.lowercase(Locale.ROOT))) continue
                val e = runCatching { LocalScanner.readEntry(f, tagSource, coverDir) }.getOrNull()
                if (e == null) {
                    skipped++
                    Log.i(TAG, "opened file unreadable, skipped: ${f.path}")
                    continue
                }
                if (f.uri.isNotEmpty()) source.retain(f.uri)
                out += e
            }
            if (folders.isNotEmpty() && out.size < maxFiles) {
                val (found, st) = LocalScanner.scan(ScanOptions(folders, coverDir, maxFiles = maxFiles - out.size), emptyList(), listerFactory(), tagSource)
                capped = capped || st.capped
                skipped += st.failed
                for (e in found) if (seen.add(e.path.lowercase(Locale.ROOT))) out += e
            } else if (folders.isNotEmpty()) {
                capped = true
            }
            val sorted = LocalScanner.sortEntries(out)
            Log.i(TAG, "opened: ${items.size} items -> ${sorted.size} tracks (${folders.size} folders, $skipped skipped${if (capped) ", capped" else ""})")
            Collected(sorted, folders, skipped, capped)
        }

    companion object {
        private const val TAG = "opened"
    }
}

/**
 * Resolves "local:" ids for the player (the C++ localFileResolvers): opened files first (only while they are
 * reachable), then the local library. null: the player reports an error and moves on - a local file never falls
 * back to a YouTube search on its file name.
 */
class LocalFileResolver(private val library: LocalLibrary?, private val opened: OpenedFiles?) {
    /** Blocking (it may touch the file): call from a worker. */
    fun fileFor(trackId: String): LocalFile? {
        if (!isLocalId(trackId)) return null
        return opened?.reachableFileFor(trackId) ?: library?.fileFor(trackId) ?: opened?.fileFor(trackId)
    }

    suspend fun resolve(trackId: String): LocalFile? = withContext(AppDispatchers.io) { fileFor(trackId) }

    /** The file's opener (library and opened files share the platform's). */
    fun openerFor(trackId: String): LocalFileOpener? = when {
        library?.fileFor(trackId) != null -> library.opener
        opened?.fileFor(trackId) != null -> opened.opener
        else -> null
    }
}
