package com.app.shadetube.localfiles

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.SeekableByteChannel
import java.util.Locale

/**
 * The Android side of local files (kept thin: the logic is in the pure classes): MediaStore and SAF listing,
 * ContentResolver access, MediaMetadataRetriever as the last tag fallback, and the factories AppGraph calls.
 *
 * Permissions: MediaStore folders need READ_MEDIA_AUDIO (API 33+) or READ_EXTERNAL_STORAGE (API 28-32); picked SAF
 * folders need only the persistable grant taken with [takeTreePermission].
 */
object LocalPlatform {
    /** The runtime permission MediaStore folders need on this device. */
    val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

    /** Call once at startup (before the library starts): the primary storage root for SAF -> path mapping. */
    @Suppress("DEPRECATION")
    fun init() {
        runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()?.let { LocalPaths.primaryRoot = it.trimEnd('/') }
    }

    /** local-library.json + cache/local-covers, Settings.localFolders. */
    fun createLibrary(context: Context): LocalLibrary {
        init()
        val app = context.applicationContext
        return LocalLibrary(
            indexFile = Paths.file("local-library.json"),
            coverDir = File(Paths.cacheDir, "local-covers"),
            listerFactory = { AndroidFolderLister(app) },
            tagSource = AndroidTagSource(app),
            opener = ContentFileOpener(app),
        )
    }

    /** dropped-files.json + cache/dropped-covers. */
    fun createOpenedFiles(context: Context): OpenedFiles {
        init()
        val app = context.applicationContext
        return OpenedFiles(
            storeFile = Paths.file("dropped-files.json"),
            coverDir = File(Paths.cacheDir, "dropped-covers"),
            source = ContentOpenedFileSource(app),
            listerFactory = { AndroidFolderLister(app) },
            tagSource = AndroidTagSource(app),
            opener = ContentFileOpener(app),
        )
    }

    /** After ACTION_OPEN_DOCUMENT_TREE: keeps read access to the picked folder across restarts. */
    fun takeTreePermission(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrElse {
        Log.w("local", "no persistable grant for $treeUri: ${it.message}")
        false
    }

    /** After a folder was removed from the list. */
    fun releaseTreePermission(context: Context, treeUri: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** The content URIs of an ACTION_VIEW / ACTION_SEND / ACTION_SEND_MULTIPLE intent (for [OpenedFiles.collect]). */
    @Suppress("DEPRECATION")
    fun urisOf(intent: Intent): List<String> {
        val out = ArrayList<String>()
        intent.data?.let { out += it.toString() }
        when (intent.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { out += it.toString() }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { out += it.toString() }
        }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out += it.toString() } }
        return out.distinct()
    }

    // ---- shared helpers -------------------------------------------------------------------------------------------
    internal fun openChannel(resolver: ContentResolver, uri: String, path: String): SeekableByteChannel? = runCatching {
        if (uri.startsWith("content://")) {
            val pfd = resolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
            val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            val ch = stream.channel
            ch.size() // a pipe (not seekable) fails here
            ch
        } else {
            val f = File(if (uri.startsWith("file://")) LocalPaths.canonical(uri) else path)
            if (f.isFile) RandomAccessFile(f, "r").channel else null
        }
    }.getOrNull()
}

/** Opens local files through the ContentResolver (content URIs) or the file system. */
class ContentFileOpener(private val context: Context) : LocalFileOpener {
    override fun open(file: LocalFile): SeekableByteChannel? = LocalPlatform.openChannel(context.contentResolver, file.uri, file.path)

    override fun openSidecar(file: LocalFile): SeekableByteChannel? {
        val stem = file.name.substringBeforeLast('.', file.name).takeIf { file.name.lastIndexOf('.') > 0 } ?: file.name
        // A SAF document of a path-like provider (external storage): the sibling document "<parent>/<stem>.lrc".
        if (LocalPaths.isTreeUri(file.uri)) {
            val docId = LocalPaths.documentIdOf(file.uri) ?: return null
            if (docId.lastIndexOf('/') < 0) return null
            val sibling = docId.substringBeforeLast('/') + "/" + stem + ".lrc"
            val uri = runCatching { DocumentsContract.buildDocumentUriUsingTree(Uri.parse(file.uri), sibling) }.getOrNull() ?: return null
            return LocalPlatform.openChannel(context.contentResolver, uri.toString(), "")
        }
        // A file with a path (MediaStore): readable when the storage permission model lets us.
        val path = file.filePath ?: return null
        val lrc = File(File(path).parentFile, "$stem.lrc")
        return if (lrc.canRead()) runCatching { RandomAccessFile(lrc, "r").channel }.getOrNull() else null
    }
}

/** Stats opened content URIs / paths for [OpenedFiles]. */
class ContentOpenedFileSource(private val context: Context) : OpenedFileSource {
    override fun stat(item: String): FoundFile? = runCatching {
        if (!item.startsWith("content://")) {
            val f = File(if (item.startsWith("file://")) LocalPaths.canonical(item) else item)
            if (!f.isFile) return null
            return FoundFile(LocalPaths.normalizePath(f.absolutePath), "", f.name, f.length(), f.lastModified())
        }
        val uri = Uri.parse(item)
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        var mtime = 0L
        var data: String? = null
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: "" }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }?.let { mtime = c.getLong(it) }
                if (mtime == 0L) c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { mtime = c.getLong(it) * 1000 }
                @Suppress("DEPRECATION")
                c.getColumnIndex(MediaStore.MediaColumns.DATA).takeIf { it >= 0 }?.let { data = runCatching { c.getString(it) }.getOrNull() }
            }
        }
        if (size < 0) size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
        if (name.isEmpty()) name = LocalPaths.nameOf(item)
        val mime = resolver.getType(uri) ?: ""
        // A MediaStore item has a real path: the same id as in the library.
        val path = data?.takeIf { it.startsWith("/") }?.let { LocalPaths.normalizePath(it) } ?: LocalPaths.canonical(item)
        FoundFile(path, item, name, size, mtime, mime = mime)
    }.getOrNull()

    override fun retain(item: String) {
        if (!item.startsWith("content://")) return
        runCatching { context.contentResolver.takePersistableUriPermission(Uri.parse(item), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

/**
 * Tags of a found file: the own reader over its bytes, MediaStore's columns for what it leaves out, and
 * MediaMetadataRetriever as the last fallback (no duration, or a format the reader doesn't parse).
 */
class AndroidTagSource(private val context: Context) : TagSource {
    override fun read(file: FoundFile, wantPicture: Boolean): LocalTags? {
        val ch = LocalPlatform.openChannel(context.contentResolver, file.uri, file.path) ?: return null
        val own = ch.use { runCatching { TagReader.read(ChannelSource(it), wantPicture) }.getOrDefault(LocalTags()) }
        var t = file.store?.let { own.merge(it) } ?: own
        if (t.durationMs == 0 || (t.title.isEmpty() && file.store == null)) t = t.merge(retrieve(file, wantPicture && t.picture == null))
        return t
    }

    private fun retrieve(file: FoundFile, wantPicture: Boolean): LocalTags {
        val r = MediaMetadataRetriever()
        return try {
            if (file.uri.isNotEmpty()) r.setDataSource(context, Uri.parse(file.uri)) else r.setDataSource(file.path)
            fun s(key: Int) = r.extractMetadata(key)?.trim().orEmpty()
            val artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            LocalTags(
                title = s(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artists = if (artist.isEmpty()) emptyList() else TagReader.splitValues(listOf(artist)),
                albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                discNumber = TagReader.leadingNumber(s(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                trackNumber = TagReader.leadingNumber(s(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER), 4),
                year = s(MediaMetadataRetriever.METADATA_KEY_YEAR).take(4).toIntOrNull() ?: 0,
                durationMs = s(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull()?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                picture = if (wantPicture) runCatching { r.embeddedPicture }.getOrNull() else null,
            )
        } catch (e: Exception) {
            LocalTags()
        } finally {
            runCatching { r.release() }
        }
    }
}

/**
 * Lists music folders: SAF trees through DocumentsContract (one query per folder, every column at once), the others
 * (absolute / relative paths) through MediaStore.Audio (one query per scan, filtered per folder). Blocking.
 */
class AndroidFolderLister(private val context: Context) : FolderLister {
    private val resolver = context.contentResolver
    private val media: List<FoundFile>? by lazy { queryMediaStore() }

    override fun walk(folder: String, onFile: (FoundFile) -> Boolean): WalkResult =
        if (LocalPaths.isTreeUri(folder)) walkTree(folder, onFile) else walkMediaStore(folder, onFile)

    // ---- MediaStore -----------------------------------------------------------------------------------------------
    private fun walkMediaStore(folder: String, onFile: (FoundFile) -> Boolean): WalkResult {
        val rows = media ?: return WalkResult(listOf(folder), rootMissing = true, permissionDenied = true)
        // An absolute folder on a volume that isn't mounted (SD card removed): unreachable, its tracks are kept.
        if (LocalPaths.isAbsolute(folder) && !File(LocalPaths.volumeRoot(folder)).exists())
            return WalkResult(listOf(folder), rootMissing = true)
        val art = HashMap<String, String>()
        val artChecked = HashSet<String>()
        for (f in rows) {
            val rest = LocalPaths.remainderBelow(f.path, folder) ?: continue
            // Folders the walk never enters: "." folders (Android's hidden ones).
            if (rest.split('/').dropLast(1).any { it.startsWith(".") }) continue
            val dir = LocalPaths.dirOf(f.path)
            if (artChecked.add(dir.lowercase(Locale.ROOT))) folderArtOf(dir)?.let { art[dir.lowercase(Locale.ROOT)] = it }
            if (!onFile(f)) break
        }
        return WalkResult(folderArt = art)
    }

    /** cover.jpg / folder.jpg ... next to the files, when the storage permission model lets us see it. */
    private fun folderArtOf(dir: String): String? {
        for (n in FileFolderLister.ART_NAMES) for (x in FileFolderLister.ART_EXTS) {
            val f = File(dir, n + x)
            if (runCatching { f.isFile && f.canRead() }.getOrDefault(false)) return f.absolutePath
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun queryMediaStore(): List<FoundFile>? {
        if (!LocalPlatform.hasAudioPermission(context)) return null
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<FoundFile>()
        return runCatching {
            resolver.query(collection, null, null, null, null)?.use { c ->
                fun idx(name: String) = c.getColumnIndex(name)
                val cId = idx(MediaStore.Audio.Media._ID)
                val cData = idx(MediaStore.Audio.Media.DATA)
                val cName = idx(MediaStore.Audio.Media.DISPLAY_NAME)
                val cSize = idx(MediaStore.Audio.Media.SIZE)
                val cModified = idx(MediaStore.Audio.Media.DATE_MODIFIED)
                val cTitle = idx(MediaStore.Audio.Media.TITLE)
                val cArtist = idx(MediaStore.Audio.Media.ARTIST)
                val cAlbum = idx(MediaStore.Audio.Media.ALBUM)
                val cAlbumArtist = idx("album_artist")
                val cTrack = idx(MediaStore.Audio.Media.TRACK)
                val cDisc = idx("disc_number")
                val cYear = idx(MediaStore.Audio.Media.YEAR)
                val cDuration = idx(MediaStore.Audio.Media.DURATION)
                val cRelative = idx("relative_path")
                val cVolume = idx("volume_name")
                val cMime = idx(MediaStore.Audio.Media.MIME_TYPE)
                fun str(i: Int) = if (i >= 0 && !c.isNull(i)) c.getString(i)?.trim().orEmpty() else ""
                fun num(i: Int) = if (i >= 0 && !c.isNull(i)) runCatching { c.getLong(i) }.getOrDefault(0L) else 0L
                while (c.moveToNext()) {
                    val id = num(cId)
                    val name = str(cName)
                    val volume = str(cVolume)
                    var path = str(cData)
                    if (!path.startsWith("/")) {
                        val rel = str(cRelative)
                        if (rel.isEmpty() || name.isEmpty()) continue
                        val root = if (volume.isEmpty() || volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) LocalPaths.primaryRoot
                        else "/storage/" + volume.uppercase(Locale.ROOT)
                        path = "$root/$rel$name"
                    }
                    path = LocalPaths.normalizePath(path)
                    val fileName = name.ifEmpty { LocalPaths.nameOf(path) }
                    val base = if (Build.VERSION.SDK_INT >= 29 && volume.isNotEmpty()) MediaStore.Audio.Media.getContentUri(volume) else collection
                    val uri = ContentUris.withAppendedId(base, id).toString()
                    // MediaStore's own values: its TITLE falls back to the file name and its ALBUM to the folder name
                    // when the file has no tags, so those count only when they are real tags.
                    val stem = fileName.substringBeforeLast('.', fileName)
                    val title = str(cTitle).takeIf { it.isNotEmpty() && it != stem }.orEmpty()
                    val artist = str(cArtist).takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING }.orEmpty()
                    val track = num(cTrack).toInt()
                    val store = LocalTags(
                        title = title,
                        artists = if (artist.isEmpty()) emptyList() else TagReader.splitValues(listOf(artist)),
                        albumArtist = str(cAlbumArtist),
                        album = if (title.isNotEmpty()) str(cAlbum) else "",
                        discNumber = TagReader.leadingNumber(str(cDisc)).takeIf { it > 0 } ?: (track / 1000).coerceIn(0, 999),
                        trackNumber = (track % 1000).coerceAtLeast(0),
                        year = num(cYear).toInt().coerceIn(0, 9999),
                        durationMs = num(cDuration).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                    )
                    out += FoundFile(path, uri, fileName, num(cSize), num(cModified) * 1000, store, str(cMime))
                }
            }
            out
        }.getOrElse {
            Log.w("local", "MediaStore query failed: ${it.message}")
            null
        }
    }

    // ---- SAF ------------------------------------------------------------------------------------------------------
    private fun walkTree(folder: String, onFile: (FoundFile) -> Boolean): WalkResult {
        val tree = Uri.parse(folder)
        val granted = runCatching { resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission } }.getOrDefault(false)
        if (!granted) return WalkResult(listOf(folder), rootMissing = true)
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return WalkResult(listOf(folder), rootMissing = true)
        val unlisted = ArrayList<String>()
        val art = HashMap<String, String>()
        var rootMissing = false
        val dirs = ArrayDeque<String>()
        dirs.addLast(rootId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (dirs.isNotEmpty()) {
            val dirId = dirs.removeLast()
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirId).toString()
            val images = HashMap<String, String>()
            val cursor = runCatching { resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId), projection, null, null, null) }.getOrNull()
            if (cursor == null) {
                // The provider can't list it right now (SD card removed, provider gone): its indexed tracks are kept.
                unlisted += if (dirId == rootId) folder else dirUri
                if (dirId == rootId) rootMissing = true
                continue
            }
            var stop = false
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name.isNotEmpty() && !name.startsWith(".") && LocalPaths.validUtf16(name)) dirs.addLast(id)
                        continue
                    }
                    if (name.startsWith(".")) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    val lower = name.lowercase(Locale.ROOT)
                    if (lower.substringBeforeLast('.') in FileFolderLister.ART_NAMES && LocalFormats.extensionOf(lower) in FileFolderLister.ART_EXTS) {
                        images[lower] = docUri
                        continue
                    }
                    if (!LocalFormats.isAudioFile(name)) continue
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val mtime = if (c.isNull(4)) 0L else c.getLong(4)
                    if (!onFile(FoundFile(LocalPaths.canonical(docUri), docUri, name, size, mtime, mime = mime))) {
                        stop = true
                        break
                    }
                }
            }
            FileFolderLister.pickFolderArt(images)?.let { art[LocalPaths.canonical(dirUri).lowercase(Locale.ROOT)] = it }
            if (stop) break
        }
        return WalkResult(unlisted, rootMissing, folderArt = art)
    }
}
