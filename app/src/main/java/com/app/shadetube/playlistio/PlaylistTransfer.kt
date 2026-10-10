package com.app.shadetube.playlistio

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Page
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isLocalId
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Log
import com.app.shadetube.links.Links
import com.app.shadetube.localfiles.LocalEntry
import com.app.shadetube.localfiles.LocalFile
import com.app.shadetube.localfiles.LocalFormats
import com.app.shadetube.localfiles.LocalLibrary
import com.app.shadetube.localfiles.LocalPaths
import com.app.shadetube.localfiles.OpenedFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

// ---- What AppGraph wires in -----------------------------------------------------------------------------------------

/** Spotify for the transfer flows (AppGraph: the session / SpotifyApi). */
interface SpotifyTransfer {
    /** Logged in: Spotify lists can be exported and "Spotify'da da oluştur" is offered. */
    val loggedIn: Boolean

    /** One page of a playlist or Liked Songs ("spotify:collection:tracks"). */
    suspend fun playlistTracks(uri: String, offset: Int, limit: Int): Page<Track>

    /** A "spotify:track:" song in full (import of id-only rows). */
    suspend fun track(uri: String): Track

    /** Creates a playlist of the user with the first (at most 100) songs; null when it could not be created. */
    suspend fun createPlaylist(name: String, trackUris: List<String>): CreatedPlaylist?

    /** Adds up to 100 songs; false on failure. */
    suspend fun addToPlaylist(playlistUri: String, trackUris: List<String>): Boolean
}

data class CreatedPlaylist(val uri: String, val added: Int)

/** A YouTube video as the YouTube module reports it. */
data class VideoInfo(val id: String, val title: String, val channel: String, val durationMs: Int = 0)

data class YouTubePlaylist(val title: String, val videos: List<VideoInfo>)

/** YouTube for the transfer flows (AppGraph: YoutubeExplode client + MatchService). */
interface YouTubeTransfer {
    suspend fun video(videoId: String): VideoInfo

    /** A playlist's title and (at most [maxVideos]) videos. */
    suspend fun playlist(playlistId: String, maxVideos: Int): YouTubePlaylist

    /** The matched video of a catalog song when the match cache knows it (export); null otherwise. */
    fun cachedVideoId(trackId: String): String?
}

/** MusicBrainz recording lookup (AppGraph: musicbrainz.MusicBrainz). */
fun interface MusicBrainzTransfer {
    suspend fun recording(mbid: String): Track
}

/** Albums for export (AppGraph: the active source - Spotify or MusicBrainz). */
fun interface AlbumLoader {
    suspend fun album(id: String): Album
}

/** The local playlists of the library (AppGraph: library.Library). */
interface LocalPlaylistTarget {
    /** Creates a local playlist; its id ("local:<hex>"). */
    fun createPlaylist(name: String): String

    /** Adds songs; how many were added. */
    fun addToPlaylist(playlistId: String, tracks: List<Track>): Int
}

/** A finished download's file of a song (AppGraph: downloads); null when there is none. */
fun interface DownloadedFiles {
    fun filePathFor(trackId: String): String?
}

/**
 * The local files the flows use: the library's entries (matching a playlist's paths), files by id (export) and
 * reading / remembering files outside the library (import). [of] wires the localfiles services.
 */
interface LocalFilesAccess {
    fun libraryEntries(): List<LocalEntry>
    fun fileFor(trackId: String): LocalFile?

    /** Reads files outside the library (paths / content URIs), like opened files; unreadable ones are left out. */
    suspend fun read(paths: List<String>, maxFiles: Int): List<LocalEntry>

    /** Remembers imported files so they keep playing (dropped-files.json). */
    fun remember(entries: List<LocalEntry>)

    companion object {
        fun of(library: LocalLibrary?, opened: OpenedFiles?): LocalFilesAccess = object : LocalFilesAccess {
            override fun libraryEntries(): List<LocalEntry> = library?.current?.entries ?: emptyList()
            override fun fileFor(trackId: String): LocalFile? = library?.fileFor(trackId) ?: opened?.fileFor(trackId)
            override suspend fun read(paths: List<String>, maxFiles: Int): List<LocalEntry> =
                opened?.collect(paths, maxFiles)?.entries ?: emptyList()
            override fun remember(entries: List<LocalEntry>) {
                opened?.remember(entries)
            }
        }
    }
}

// ---- Results ----------------------------------------------------------------------------------------------------------

/** What to export (the Liked / Playlist / Album / Queue choices of the C++ exportList). */
sealed interface ExportSource {
    /** Songs the caller already has: the queue, a local playlist, the local Liked Songs. */
    data class Tracks(val tracks: List<Track>) : ExportSource

    /** A Spotify playlist or Liked Songs ("spotify:collection:tracks"), fetched in full (pages 250 ms apart). */
    data class SpotifyList(val uri: String) : ExportSource

    /** An album (Spotify URI or MBID) through [AlbumLoader]. */
    data class AlbumOf(val id: String) : ExportSource
}

/** The import preview: the list's name, the songs found, and the rows that name no song. */
data class ImportPreview(
    val name: String,
    val tracks: List<Track>,
    val unresolved: List<ImportProblem>,
    /** Files read for the import: remembered on commit, so they keep playing. */
    val localEntries: List<LocalEntry> = emptyList(),
) {
    val spotifyCount: Int get() = tracks.count { it.id.startsWith("spotify:track:") }
    val localCount: Int get() = tracks.count { isLocalId(it.id) }
    val youtubeCount: Int get() = tracks.count { it.id.startsWith("yt:") }

    /** The first rows the preview lists ("Satır 12: ..."); the rest is a count. */
    val shownProblems: List<ImportProblem> get() = unresolved.take(PlaylistTransfer.SHOWN_PROBLEMS)
}

sealed interface SpotifyOutcome {
    /** Not asked for, not logged in, or no song with a Spotify id. */
    data object Skipped : SpotifyOutcome
    data object Failed : SpotifyOutcome
    data class Created(val uri: String, val added: Int) : SpotifyOutcome
    /** A batch failed: only [added] songs made it. */
    data class Partial(val uri: String, val added: Int) : SpotifyOutcome
}

data class ImportResult(val playlistId: String, val added: Int, val spotify: SpotifyOutcome)

/**
 * Playlist import and export (port of app/PlaylistTransfer, without the dialogs: the UI shows the SAF pickers, the
 * preview and the toasts).
 *
 * Export: the whole list is fetched (Spotify pages throttled; albums, local lists and the queue as they are), radio
 * stations and podcast episodes are left out, each song gets its local file (a download or a local / opened file)
 * and its matched YouTube video when known, and the file is written in the chosen format.
 *
 * Import: a playlist file (M3U / M3U8 / CSV / XSPF / JSON) or a YouTube / YouTube Music playlist becomes a LOCAL
 * playlist, after a preview (name, songs found, rows that name no song). Local files are matched to the library or
 * read through its tag reader and remembered like opened files (so they keep playing); songs named only by an id get
 * their title from Spotify / YouTube / MusicBrainz (at most [MAX_LOOKUPS]); YouTube videos keep exactly that video
 * as their source. Logged in, "Spotify'da da oluştur" also creates a Spotify playlist with the songs that have
 * Spotify ids (100 per request).
 *
 * Android: a list written on another device names files by paths that don't exist here; such a path is matched to a
 * library file by its folder + file name (else its file name alone, when only one file has it).
 */
class PlaylistTransfer(
    private val local: LocalFilesAccess,
    private val playlists: LocalPlaylistTarget,
    private val spotify: SpotifyTransfer? = null,
    private val youtube: YouTubeTransfer? = null,
    private val musicBrainz: MusicBrainzTransfer? = null,
    private val albums: AlbumLoader? = null,
    private val downloads: DownloadedFiles? = null,
    /** Pause between Spotify pages (rate limits) and after a Spotify track lookup. */
    private val spotifyPageDelayMs: Long = 250,
    private val spotifyLookupDelayMs: Long = 120,
) {
    companion object {
        /** Id-only songs named through the network, per import. */
        const val MAX_LOOKUPS = 300
        /** Videos of a YouTube playlist import. */
        const val MAX_YOUTUBE_VIDEOS = 5000
        /** Local files read per import. */
        const val MAX_LOCAL_FILES = 20_000
        /** Rows the preview lists by line. */
        const val SHOWN_PROBLEMS = 6
        /** Spotify playlist pages fetched for an export (at most 20 000 rows). */
        const val MAX_SPOTIFY_PAGES = 200
        private const val TAG = "transfer"

        /** Songs only: no radio stations or podcast episodes in a playlist file. */
        fun songsOnly(tracks: List<Track>): List<Track> =
            tracks.filter { it.id.isNotEmpty() && !(it.id.length > 6 && it.id.startsWith("radio:")) && !isPodcastId(it.id) }

        /** A YouTube video as a song (title / artist from the video, its 16:9 thumbnail, exactly that video as source). */
        fun videoTrack(videoId: String, title: String, channel: String, durationMs: Int): Track {
            val song = Links.videoSong(title, channel)
            return Track(
                id = "yt:$videoId",
                videoId = videoId,
                name = song.title.ifEmpty { title },
                artists = if (song.artist.isEmpty()) emptyList() else listOf(ArtistRef("", song.artist)),
                durationMs = durationMs,
                album = com.app.shadetube.catalog.AlbumRef(images = listOf(Image("https://i.ytimg.com/vi/$videoId/mqdefault.jpg", 320, 180))),
            )
        }

        /** A default file name from a list name: no characters file systems refuse; "" falls back to the caller's. */
        fun safeFileName(name: String): String {
            val w = buildString { for (c in name) append(if (c.code < 32 || c in "<>:\"/\\|?*") '_' else c) }.trimEnd(' ', '.')
            return if (w.length > 120) w.substring(0, 120) else w
        }
    }

    // ---- export ---------------------------------------------------------------------------------------------------
    /** Every song of [source] (network for Spotify lists and albums). Throws on failure. */
    suspend fun tracksFor(source: ExportSource): List<Track> = when (source) {
        is ExportSource.Tracks -> source.tracks
        is ExportSource.SpotifyList -> spotifyTracks(source.uri)
        is ExportSource.AlbumOf -> {
            val loader = albums ?: throw IllegalStateException("no album source")
            val album = loader.album(source.id)
            album.tracks.map { t -> if (t.album.name.isEmpty()) t.copy(album = album.ref()) else t }
        }
    }

    /** Every page of a Spotify playlist / Liked Songs (a short pause between pages: rate limits). */
    private suspend fun spotifyTracks(uri: String): List<Track> {
        val api = spotify?.takeIf { it.loggedIn } ?: throw IllegalStateException("not logged in to Spotify")
        val out = ArrayList<Track>()
        var offset = 0
        for (page in 0 until MAX_SPOTIFY_PAGES) {
            val p = api.playlistTracks(uri, offset, 100)
            val next = p.next
            out += p.items
            if (next <= offset || next >= p.total) break
            offset = next
            delay(spotifyPageDelayMs)
        }
        return out
    }

    /** The export rows: each song with its local file (a download, a local / opened file) and its matched video. */
    fun rowsFor(tracks: List<Track>): List<ExportRow> = songsOnly(tracks).map { t ->
        var file = downloads?.filePathFor(t.id).orEmpty()
        if (file.isEmpty() && isLocalId(t.id)) file = local.fileFor(t.id)?.let { it.filePath ?: it.uri }.orEmpty()
        val video = if (!isLocalId(t.id)) youtube?.cachedVideoId(t.id).orEmpty() else ""
        ExportRow(t, file, video)
    }

    /**
     * Writes [tracks] as [format] to [out] (the stream of the document the SAF "create" picker returned). The number
     * of songs written; 0 = nothing to export (the UI says so).
     */
    suspend fun export(name: String, tracks: List<Track>, format: PlaylistFormat, out: OutputStream): Result<Int> =
        withContext(AppDispatchers.io) {
            try {
                val rows = rowsFor(tracks)
                if (rows.isEmpty()) return@withContext Result.success(0)
                PlaylistIO.write(format, name, rows, out)
                Log.i(TAG, "exported ${rows.size} song(s) as ${PlaylistIO.extension(format)}")
                Result.success(rows.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "export failed: ${e.message}")
                Result.failure(e)
            }
        }

    // ---- import ---------------------------------------------------------------------------------------------------
    /** Reads and prepares a playlist file for the preview. [baseDir]: its folder when known (relative paths). */
    suspend fun previewFile(open: () -> InputStream, displayName: String, baseDir: String = ""): Result<ImportPreview> =
        boundary { prepare(withContext(AppDispatchers.io) { open().use { PlaylistIO.read(it, displayName, baseDir) } }) }

    /** A YouTube / YouTube Music playlist (links.LinkKind.YouTubePlaylist id) as a list of exactly those videos. */
    suspend fun previewYouTubePlaylist(playlistId: String): Result<ImportPreview> = boundary {
        val yt = youtube ?: throw IllegalStateException("no YouTube client")
        val list = yt.playlist(playlistId, MAX_YOUTUBE_VIDEOS)
        val entries = list.videos.mapIndexed { i, v -> ImportEntry(videoTrack(v.id, v.title, v.channel, v.durationMs), "", i + 1) }
        prepare(ParsedPlaylist(PlaylistFormat.JSON, "", list.title, entries))
    }

    private suspend fun boundary(block: suspend () -> ImportPreview): Result<ImportPreview> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "import failed: ${e.message}")
        Result.failure(e)
    }

    /** Local files matched / read, id-only songs named (Spotify / YouTube / MusicBrainz, at most [MAX_LOOKUPS]). */
    suspend fun prepare(parsed: ParsedPlaylist): ImportPreview = withContext(AppDispatchers.io) {
        val tracks = ArrayList<Track>()
        val unresolved = ArrayList(parsed.unresolved)
        // Local files: the library's own entry when it has the file, else read once each (tags, embedded covers).
        val matcher = LocalPathMatcher(local.libraryEntries())
        val byPath = HashMap<String, LocalEntry>()
        val toRead = ArrayList<String>()
        val seen = HashSet<String>()
        for (e in parsed.entries) {
            if (e.localPath.isEmpty()) continue
            val key = LocalPathMatcher.key(e.localPath)
            if (!seen.add(key)) continue
            val exact = matcher.exact(e.localPath)
            if (exact != null) byPath[key] = exact else toRead += e.localPath
        }
        val read = ArrayList<LocalEntry>()
        if (toRead.isNotEmpty()) {
            for (le in local.read(toRead, MAX_LOCAL_FILES)) {
                read += le
                byPath[LocalPathMatcher.key(le.path)] = le
                if (le.uri.isNotEmpty()) byPath[LocalPathMatcher.key(le.uri)] = le
            }
        }
        var lookups = 0
        for (e in parsed.entries) {
            ensureActive()
            if (e.localPath.isNotEmpty()) {
                val le = byPath[LocalPathMatcher.key(e.localPath)]
                    ?: byPath[LocalPathMatcher.key(LocalPaths.canonical(e.localPath))]
                    ?: matcher.byName(e.localPath)
                if (le != null) {
                    tracks += LocalFormats.toTrack(le).copy(addedAt = e.track.addedAt)
                    continue
                }
                // The file is gone (or unreadable): a song the list names ("Artist - Title") is still matched on YouTube.
                val t = e.track
                if (t.artists.isNotEmpty() && t.name.isNotEmpty()) tracks += t.copy(id = PlaylistIO.importId(t.name, t.artists[0].name))
                else unresolved += ImportProblem(e.line, e.localPath)
                continue
            }
            var t = e.track
            if (t.name.isNotEmpty()) {
                tracks += t
                continue
            }
            // Only an id: ask its service for the title (a few hundred at most; the rest are reported).
            var named = false
            if (lookups < MAX_LOOKUPS) {
                lookups++
                try {
                    if (t.id.startsWith("spotify:track:") && spotify != null) {
                        t = spotify.track(t.id).copy(addedAt = t.addedAt)
                        delay(spotifyLookupDelayMs)
                    } else if (t.id.startsWith("yt:") && youtube != null) {
                        val v = youtube.video(t.videoId.ifEmpty { t.id.substring(3) })
                        t = videoTrack(v.id.ifEmpty { t.id.substring(3) }, v.title, v.channel, v.durationMs)
                    } else if (Links.isMbid(t.id) && musicBrainz != null) {
                        t = musicBrainz.recording(t.id).copy(addedAt = t.addedAt)
                    }
                    named = t.name.isNotEmpty()
                } catch (c: CancellationException) {
                    throw c
                } catch (ex: Exception) {
                    Log.w(TAG, "${t.id}: not found (${ex.message})")
                }
            }
            if (named) tracks += t else unresolved += ImportProblem(e.line, t.id)
        }
        ImportPreview(parsed.name, tracks, unresolved, read)
    }

    /**
     * "İçe aktar": creates the local playlist (named [name], or the preview's), remembers the files read so they keep
     * playing, and - when [alsoOnSpotify] and logged in - a Spotify playlist with the songs that have Spotify ids.
     */
    suspend fun commit(preview: ImportPreview, name: String, alsoOnSpotify: Boolean): ImportResult {
        val listName = name.trim().ifEmpty { preview.name }
        if (preview.localEntries.isNotEmpty()) local.remember(preview.localEntries)
        val id = playlists.createPlaylist(listName)
        val added = playlists.addToPlaylist(id, preview.tracks)
        Log.i(TAG, "imported $added song(s) into $id")
        val outcome = if (alsoOnSpotify) createOnSpotify(listName, preview.tracks) else SpotifyOutcome.Skipped
        return ImportResult(id, added, outcome)
    }

    /** The Spotify copy: created with the first 100 songs, the rest added 100 at a time. */
    suspend fun createOnSpotify(name: String, tracks: List<Track>): SpotifyOutcome {
        val api = spotify?.takeIf { it.loggedIn } ?: return SpotifyOutcome.Skipped
        val uris = tracks.map { it.id }.filter { it.startsWith("spotify:track:") }.distinct()
        if (uris.isEmpty()) return SpotifyOutcome.Skipped
        val created = try {
            api.createPlaylist(name, uris.take(100))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            null
        } ?: return SpotifyOutcome.Failed
        var added = created.added
        var from = minOf(100, uris.size)
        while (from < uris.size) {
            val batch = uris.subList(from, minOf(uris.size, from + 100))
            val ok = try {
                api.addToPlaylist(created.uri, batch)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                false
            }
            if (!ok) return SpotifyOutcome.Partial(created.uri, added)
            added += batch.size
            from += batch.size
        }
        return SpotifyOutcome.Created(created.uri, added)
    }
}

/**
 * Finds the library file a playlist line names (pure): the same location, else - for a list written elsewhere (a
 * Windows path, another phone's storage) - the file whose folder + file name match, else the only file with that
 * name.
 */
class LocalPathMatcher(entries: List<LocalEntry>) {
    private val byKey = HashMap<String, LocalEntry>()
    private val byTail = HashMap<String, MutableList<LocalEntry>>()
    private val byFileName = HashMap<String, MutableList<LocalEntry>>()

    init {
        for (e in entries) {
            byKey.putIfAbsent(key(e.path), e)
            if (e.uri.isNotEmpty()) byKey.putIfAbsent(key(e.uri), e)
            byTail.getOrPut(tail(e.path)) { ArrayList() } += e
            byFileName.getOrPut(e.fileName.lowercase(Locale.ROOT)) { ArrayList() } += e
        }
    }

    fun exact(path: String): LocalEntry? = byKey[key(path)] ?: byKey[key(LocalPaths.canonical(path))]

    fun byName(path: String): LocalEntry? {
        exact(path)?.let { return it }
        byTail[tail(path)]?.firstOrNull()?.let { return it }
        val name = LocalPaths.nameOf(path.replace('\\', '/')).lowercase(Locale.ROOT)
        return byFileName[name]?.singleOrNull()
    }

    companion object {
        fun key(path: String): String =
            (if (path.startsWith("content://")) path else LocalPaths.normalizePath(path)).lowercase(Locale.ROOT)

        /** "rock/a.mp3": the folder and file name. */
        private fun tail(path: String): String {
            val seg = LocalPaths.canonical(path.replace('\\', '/')).split('/').filter { it.isNotEmpty() }
            return seg.takeLast(2).joinToString("/").lowercase(Locale.ROOT)
        }
    }
}
