package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.ModelJson
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.coverArt
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Files
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.Time
import com.app.shadetube.core.appScope
import com.app.shadetube.core.arr
import com.app.shadetube.core.get
import com.app.shadetube.core.isObject
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.long
import com.app.shadetube.core.parseJsonOrNull
import com.app.shadetube.core.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.security.SecureRandom

/** One play in the local listening history. */
data class HistoryItem(val track: Track, val playedAt: Long)

/** The local part of library.json (everything but the history, which has its own flow). */
data class LibraryData(
    /** Liked songs, newest first. */
    val liked: List<Track> = emptyList(),
    /** Saved albums, newest first. */
    val albums: List<Album> = emptyList(),
    /** Followed artists, newest first. */
    val artists: List<Artist> = emptyList(),
    /** Local playlists, newest first; totalTracks / images derived from their tracks. */
    val playlists: List<Playlist> = emptyList(),
    val playlistTracks: Map<String, List<Track>> = emptyMap(),
    /** library.json has been read (or found missing / corrupt). */
    val loaded: Boolean = false,
) {
    fun playlist(id: String): Playlist? = playlists.firstOrNull { it.id == id }
}

/** Localized defaults the backend needs (set by the UI from strings.xml). */
data class LibraryLabels(
    /** Name of a playlist created without one ("Yeni çalma listesi"). */
    val newPlaylist: String = "New playlist",
)

enum class LikeResult {
    /** Nothing changed (empty / podcast id, or already in that state). */
    Unchanged,
    Liked,
    Unliked,

    /** Logged in, only Spotify tracks can be hearted ("Yalnızca Spotify şarkıları beğenilebilir"). */
    OnlySpotifyTracks,
}

enum class SaveAlbumResult { Unchanged, Saved, Removed, OnlySpotifyAlbums }

enum class FollowResult { Unchanged, Followed, Unfollowed, OnlySpotifyArtists }

/**
 * The user's library, stored on this device (library.json; no account needed) — port of the C++ `Library`
 * (app/AppContext): local liked songs, saved albums, followed artists, local playlists with their tracks, and the
 * listening history (newest first, capped at [HISTORY_CAP]).
 *
 * The heart / saved / follow state follows the "Spotify when logged in, else local" rule: logged in, Spotify is the
 * source of truth (the heart updates at once and the write is mirrored to Spotify), and only Spotify ids can be sent
 * there (a local file / MusicBrainz id never is).
 *
 * Thread-safe; mutations are cheap in-memory updates that publish a new [state] and schedule a debounced atomic save
 * (write-then-rename, the C++ housekeeping save). [flush] writes at once (process going away).
 */
class UserLibrary(
    private val account: SpotifyAccount,
    private val file: () -> File = { Paths.file(FILE_NAME) },
    private val scope: CoroutineScope = appScope,
    private val saveDelayMs: Long = SAVE_DELAY_MS,
    private val nowUnix: () -> Long = { Time.nowUnix() },
    /** Internet radio stations have their own favorites (internetradio), not Liked Songs. */
    private val isStationId: (String) -> Boolean = { it.length > STATION_PREFIX.length && it.startsWith(STATION_PREFIX) },
) {
    private val lock = Any()
    private val writeLock = Any()
    private val _state = MutableStateFlow(LibraryData())
    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    private val _revision = MutableStateFlow(0L)
    private var likedIds = HashSet<String>()
    private var dirty = false
    private var saveJob: Job? = null

    @Volatile
    var labels: LibraryLabels = LibraryLabels()

    val state: StateFlow<LibraryData> = _state.asStateFlow()

    /** Newest first. Separate from [state]: a play must not rebuild the visible pages (C++: no notify on recordPlay). */
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    /**
     * Bumped on every change notification of the C++ Library (local edits, and the Spotify heart / save / follow
     * writes made through this class), so pages showing heart state can refresh.
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val loggedIn: Boolean get() = account.loggedIn

    // ---- Load / save ----------------------------------------------------------------------------------------------

    /** Reads library.json (once at startup). A corrupt file is kept as library.json.bak and the library starts empty. */
    suspend fun load() = withContext(AppDispatchers.io) { loadNow() }

    private fun loadNow() {
        val f = file()
        val j: JsonElement? = if (f.exists()) runCatching { f.readText() }.getOrNull()?.let { parseJsonOrNull(it) } else null
        if (f.exists() && !j.isObject) {
            Log.w(TAG, "library.json is corrupt; starting empty (file kept as .bak)")
            runCatching { f.copyTo(File(f.path + ".bak"), overwrite = true) }
            synchronized(lock) { _state.value = _state.value.copy(loaded = true) }
            return
        }
        val ids = HashSet<String>()
        val liked = ArrayList<Track>()
        for (t in j["liked"].arr) {
            val tr = ModelJson.trackFrom(t)
            if (tr.id.isEmpty() || !ids.add(tr.id)) continue
            liked.add(tr)
        }
        val albums = j["albums"].arr.map { ModelJson.albumFrom(it) }
        val artists = j["artists"].arr.map { ModelJson.artistFrom(it) }
        val playlists = ArrayList<Playlist>()
        val tracks = LinkedHashMap<String, List<Track>>()
        for (p in j["playlists"].arr) {
            val id = p.str("id")
            if (id.isEmpty()) continue
            val list = p["tracks"].arr.map { ModelJson.trackFrom(it) }
            tracks[id] = list
            playlists.add(withMeta(Playlist(id = id, name = p.str("n"), description = p.str("desc"), createdAt = p.long("c")), list))
        }
        val history = j["history"].arr.map { HistoryItem(ModelJson.trackFrom(it["t"]), it.long("at")) }
        synchronized(lock) {
            likedIds = ids
            _state.value = LibraryData(liked, albums, artists, playlists, tracks, loaded = true)
            _history.value = history
        }
        Log.i(TAG, "loaded: ${liked.size} liked, ${albums.size} albums, ${artists.size} artists, ${playlists.size} playlists")
    }

    /** The library.json document (C++ saveIfDirty's shape: version, liked, albums, artists, playlists, history). */
    fun toJson(): JsonElement {
        val (s, h) = synchronized(lock) { _state.value to _history.value }
        return jsonOf(
            "version" to 1,
            "liked" to JsonArray(s.liked.map { ModelJson.track(it) }),
            "albums" to JsonArray(s.albums.map { ModelJson.album(it) }),
            "artists" to JsonArray(s.artists.map { ModelJson.artist(it) }),
            "playlists" to JsonArray(
                s.playlists.map { p ->
                    jsonOf(
                        "id" to p.id, "n" to p.name, "desc" to p.description, "c" to p.createdAt,
                        "tracks" to JsonArray((s.playlistTracks[p.id] ?: emptyList()).map { ModelJson.track(it) }),
                    )
                },
            ),
            "history" to JsonArray(h.map { jsonOf("t" to ModelJson.track(it.track), "at" to it.playedAt) }),
        )
    }

    /** Writes library.json now if anything changed (process going away / tests). */
    fun flush() {
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = null
        }
        saveIfDirty()
    }

    private fun saveIfDirty() {
        synchronized(writeLock) {
            synchronized(lock) {
                if (!dirty) return
                dirty = false
            }
            if (!Files.writeJson(file(), toJson())) Log.e(TAG, "failed to write library.json")
        }
    }

    /** Dirty + one save [saveDelayMs] later (changes meanwhile ride along, like the C++ housekeeping tick). */
    private fun markDirty() {
        synchronized(lock) {
            dirty = true
            if (saveJob?.isActive == true) return
            saveJob = scope.launch(AppDispatchers.io) {
                delay(saveDelayMs)
                synchronized(lock) { saveJob = null }
                saveIfDirty()
            }
        }
    }

    /** C++ touch(): dirty + notify. */
    private fun touch() {
        markDirty()
        notifyChanged()
    }

    private fun notifyChanged() {
        _revision.value = _revision.value + 1
    }

    private inline fun edit(change: (LibraryData) -> LibraryData) {
        synchronized(lock) { _state.value = change(_state.value) }
    }

    // ---- Liked songs ----------------------------------------------------------------------------------------------

    val liked: List<Track> get() = _state.value.liked

    /** Spotify heart when logged in, else local. */
    fun isLiked(trackId: String): Boolean =
        if (loggedIn) account.isLiked(trackId) else synchronized(lock) { trackId in likedIds }

    /**
     * Radio stations have their own favorites (internetradio), not Liked Songs; podcast episodes have none. Logged in
     * only Spotify tracks can be hearted (a local file / MusicBrainz id is never sent to Spotify).
     */
    fun canLike(trackId: String): Boolean =
        trackId.isNotEmpty() && !isStationId(trackId) && !isPodcastId(trackId) &&
            (!loggedIn || trackId.startsWith("spotify:track:"))

    fun setLiked(t: Track, liked: Boolean): LikeResult {
        if (t.id.isEmpty() || isPodcastId(t.id) || isLiked(t.id) == liked) return LikeResult.Unchanged
        if (loggedIn) {
            if (!canLike(t.id)) return LikeResult.OnlySpotifyTracks
            // Spotify is the source of truth: update the heart optimistically and mirror the write.
            account.setLiked(t.id, liked)
            notifyChanged()
            return if (liked) LikeResult.Liked else LikeResult.Unliked
        }
        edit { s ->
            if (liked) {
                likedIds.add(t.id)
                s.copy(liked = listOf(t.copy(addedAt = nowUnix())) + s.liked)
            } else {
                likedIds.remove(t.id)
                s.copy(liked = s.liked.filter { it.id != t.id })
            }
        }
        touch()
        return if (liked) LikeResult.Liked else LikeResult.Unliked
    }

    fun toggleLiked(t: Track): LikeResult = setLiked(t, !isLiked(t.id))

    /**
     * Likes several songs at once (drag and drop onto Liked Songs): one write, no toast. Songs that can't be liked or
     * are liked already are passed over. Returns how many were newly liked.
     */
    fun likeAll(tracks: List<Track>): Int {
        val seen = HashSet<String>()
        val fresh = tracks.filter { canLike(it.id) && !isLiked(it.id) && seen.add(it.id) }
        if (fresh.isEmpty()) return 0
        if (loggedIn) {
            account.likeAll(fresh.map { it.id })
            notifyChanged()
            return fresh.size
        }
        // Newest first: the first of the dropped songs ends up on top.
        val now = nowUnix()
        edit { s ->
            fresh.forEach { likedIds.add(it.id) }
            s.copy(liked = fresh.map { it.copy(addedAt = now) } + s.liked)
        }
        touch()
        return fresh.size
    }

    // ---- Saved albums / followed artists ------------------------------------------------------------------------------

    val albums: List<Album> get() = _state.value.albums
    val artists: List<Artist> get() = _state.value.artists

    fun isSavedAlbum(id: String): Boolean =
        if (loggedIn) account.isSavedAlbum(id) else _state.value.albums.any { it.id == id }

    fun setSavedAlbum(a: Album, saved: Boolean): SaveAlbumResult {
        if (a.id.isEmpty() || isSavedAlbum(a.id) == saved) return SaveAlbumResult.Unchanged
        if (loggedIn) {
            // Never send a MusicBrainz / local id to Spotify.
            if (!a.id.startsWith("spotify:album:")) return SaveAlbumResult.OnlySpotifyAlbums
            account.setAlbumSaved(a, saved)
            notifyChanged()
            return if (saved) SaveAlbumResult.Saved else SaveAlbumResult.Removed
        }
        edit { s ->
            // Tracklists come from the (cached) catalog.
            if (saved) s.copy(albums = listOf(a.copy(tracks = emptyList())) + s.albums)
            else s.copy(albums = s.albums.filter { it.id != a.id })
        }
        touch()
        return if (saved) SaveAlbumResult.Saved else SaveAlbumResult.Removed
    }

    fun isFollowing(id: String): Boolean =
        if (loggedIn) account.isFollowing(id) else _state.value.artists.any { it.id == id }

    fun setFollow(a: Artist, follow: Boolean): FollowResult {
        if (a.id.isEmpty() || isFollowing(a.id) == follow) return FollowResult.Unchanged
        if (loggedIn) {
            // Never send a MusicBrainz / local id to Spotify.
            if (!a.id.startsWith("spotify:artist:")) return FollowResult.OnlySpotifyArtists
            account.setArtistFollowed(a, follow)
            notifyChanged()
            return if (follow) FollowResult.Followed else FollowResult.Unfollowed
        }
        edit { s ->
            if (follow) s.copy(artists = listOf(a.copy(tags = emptyList())) + s.artists)
            else s.copy(artists = s.artists.filter { it.id != a.id })
        }
        touch()
        return if (follow) FollowResult.Followed else FollowResult.Unfollowed
    }

    // ---- Local playlists -------------------------------------------------------------------------------------------

    val playlists: List<Playlist> get() = _state.value.playlists
    fun playlist(id: String): Playlist? = _state.value.playlist(id)
    fun playlistTracks(id: String): List<Track>? = _state.value.playlistTracks[id]

    /** Creates an empty local playlist at the top; returns its id ("local:<16 hex>"). */
    fun createPlaylist(name: String): String {
        val id = "local:%016x".format(random.nextLong())
        val p = Playlist(id = id, name = name.ifEmpty { labels.newPlaylist }, createdAt = nowUnix())
        edit { s -> s.copy(playlists = listOf(p) + s.playlists, playlistTracks = s.playlistTracks + (id to emptyList())) }
        touch()
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        edit { s -> s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(name = name) else it }) }
        touch()
    }

    fun deletePlaylist(id: String) {
        edit { s -> s.copy(playlists = s.playlists.filter { it.id != id }, playlistTracks = s.playlistTracks - id) }
        touch()
    }

    /** Appends the songs not in the playlist yet (by id); returns how many were added. */
    fun addToPlaylist(id: String, tracks: List<Track>): Int {
        var added = 0
        synchronized(lock) {
            val s = _state.value
            val existing = s.playlistTracks[id] ?: return 0
            val have = HashSet<String>(existing.size + tracks.size) // linear for large imports (thousands of songs)
            existing.forEach { have.add(it.id) }
            val now = nowUnix()
            val out = ArrayList<Track>(existing.size + tracks.size).apply { addAll(existing) }
            for (t in tracks) {
                if (t.id.isEmpty() || !have.add(t.id)) continue
                out.add(t.copy(addedAt = now))
                added++
            }
            _state.value = s.copy(
                playlistTracks = s.playlistTracks + (id to out),
                playlists = s.playlists.map { if (it.id == id) withMeta(it, out) else it },
            )
        }
        touch()
        return added
    }

    fun removeFromPlaylist(id: String, trackId: String) = removeFromPlaylist(id, listOf(trackId))

    /** Removes every row of these songs (one change notification). */
    fun removeFromPlaylist(id: String, trackIds: Collection<String>) {
        val gone = trackIds.toHashSet()
        synchronized(lock) {
            val s = _state.value
            val existing = s.playlistTracks[id] ?: return
            val out = existing.filter { it.id !in gone }
            _state.value = s.copy(
                playlistTracks = s.playlistTracks + (id to out),
                playlists = s.playlists.map { if (it.id == id) withMeta(it, out) else it },
            )
        }
        touch()
    }

    // ---- Listening history -----------------------------------------------------------------------------------------

    fun recordPlay(t: Track) {
        if (t.name.isEmpty()) return
        val now = nowUnix()
        synchronized(lock) {
            val h = _history.value
            _history.value = if (h.isNotEmpty() && h.first().track.id == t.id) {
                listOf(h.first().copy(playedAt = now)) + h.drop(1) // repeat-one: one entry
            } else {
                (listOf(HistoryItem(t, now)) + h).take(HISTORY_CAP)
            }
        }
        markDirty() // no notify: history shouldn't rebuild visible pages
    }

    /** Most played first artists of the history (ties: first seen first). */
    fun topArtists(n: Int): List<ArtistRef> = topArtists(_history.value, n)

    /** Unique albums of the history, newest first (a cover from the Cover Art Archive when the play had none). */
    fun recentAlbums(n: Int): List<AlbumRef> = recentAlbums(_history.value, n)

    companion object {
        const val FILE_NAME = "library.json"
        const val HISTORY_CAP = 300

        /** C++: saved by the ~2 s housekeeping tick when dirty. */
        const val SAVE_DELAY_MS = 2_000L
        private const val STATION_PREFIX = "radio:"
        private const val TAG = "library"
        private val random = SecureRandom()

        /** refreshPlaylistMeta: the count and the first cover among the tracks. */
        fun withMeta(p: Playlist, tracks: List<Track>): Playlist =
            p.copy(totalTracks = tracks.size, images = tracks.firstOrNull { it.album.images.isNotEmpty() }?.album?.images ?: emptyList())

        fun topArtists(history: List<HistoryItem>, n: Int): List<ArtistRef> {
            data class Count(var n: Int, var ref: ArtistRef, val order: Int)
            val counts = LinkedHashMap<String, Count>()
            for (h in history) {
                val a = h.track.artists.firstOrNull() ?: continue
                if (a.id.isEmpty()) continue
                val c = counts.getOrPut(a.id) { Count(0, a, counts.size) }
                c.n++
                c.ref = a
            }
            return counts.values.sortedWith(compareByDescending<Count> { it.n }.thenBy { it.order }).take(n).map { it.ref }
        }

        fun recentAlbums(history: List<HistoryItem>, n: Int): List<AlbumRef> {
            val out = ArrayList<AlbumRef>()
            val seen = HashSet<String>()
            for (h in history) {
                val al = h.track.album
                if (al.id.isEmpty() || !seen.add(al.id)) continue
                out.add(if (al.images.isEmpty()) al.copy(images = coverArt(al.id)) else al)
                if (out.size >= n) break
            }
            return out
        }
    }
}
