package com.app.shadetube.spotify

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.SecureStore
import com.app.shadetube.core.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

enum class SessionState { LoggedOut, Connecting, LoggedIn }

/**
 * The user's Spotify library, fetched once after login and refreshed on demand. The sidebar, Home and Library pages
 * read it directly (no per-navigation refetch). "Liked Songs" is the pseudo-playlist that libraryV3 returns first
 * among the playlists.
 */
data class LibrarySnapshot(
    /** Flat, in libraryV3 order (folders flattened away). */
    val playlists: List<Playlist> = emptyList(),
    /** The user's folders and which playlists they hold (rootlist). */
    val tree: PlaylistTree = PlaylistTree(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    /** Liked Songs track ids (for heart state). */
    val likedIds: Set<String> = emptySet(),
    /** True once the first fetch completes (even if some lists are empty). */
    val loaded: Boolean = false,
)

/** A successful write to one of the user's Spotify playlists, broadcast so open pages can follow it. */
data class PlaylistEdit(
    val kind: Kind = Kind.Added,
    val uri: String = "",
    /** Created / Renamed: the (new) name. */
    val name: String = "",
    /** Removed: the playlist rows that are gone. */
    val removedUids: List<String> = emptyList(),
    /** Created / Added / Removed: rows added or removed. */
    val count: Int = 0,
) {
    enum class Kind { Created, Added, Removed, Renamed, Deleted }
}

/** Where the sp_dc cookie persists (replaces the C++ DPAPI store::saveCookie / loadCookie / clearCookie). */
interface SpotifyCookieStore {
    fun save(spDc: String)

    /** "" on a miss / failure. */
    fun load(): String
    fun clear()

    /** Cheap check (no decryption) whether a cookie was saved. */
    fun exists(): Boolean
}

/** spotify.dat in the app data dir, encrypted with the Android Keystore ([SecureStore]). */
object SecureCookieStore : SpotifyCookieStore {
    private const val FILE = "spotify.dat"
    override fun save(spDc: String) {
        if (!SecureStore.save(FILE, spDc)) Log.w("spotify", "saving the cookie failed")
    }
    override fun load(): String = SecureStore.load(FILE)
    override fun clear() = SecureStore.clear(FILE)
    override fun exists(): Boolean = runCatching { File(Paths.appData, FILE).exists() }.getOrDefault(false)
}

/**
 * The user's Spotify session (port of spotify/Session): owns the persisted sp_dc cookie, the current web-player access
 * token (auto-refreshed), the profile, the library snapshot and the [api]. The WebView login only needs to hand us the
 * sp_dc cookie via [loginWithCookie]; everything else is here.
 *
 * Constructed once (AppGraph owns the instance; it registers itself as [current] for the login activity). State is
 * exposed as StateFlows; every network / disk operation runs on the io dispatcher. [logout] drops every in-flight
 * continuation (the C++ Lifetime renew): nothing a request started before it applies afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Session(
    val api: SpotifyApi = SpotifyApi(),
    private val cookieStore: SpotifyCookieStore = SecureCookieStore,
    private val parentScope: CoroutineScope = appScope,
    private val fetchToken: suspend (spDc: String) -> TokenBundle = { Auth.fetchAccessToken(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(SessionState.LoggedOut)
    private val _profile = MutableStateFlow(UserProfile())
    private val _library = MutableStateFlow(LibrarySnapshot())
    private val _edits = MutableSharedFlow<PlaylistEdit>(extraBufferCapacity = 64)

    val state: StateFlow<SessionState> = _state.asStateFlow()
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()
    val library: StateFlow<LibrarySnapshot> = _library.asStateFlow()

    /** Successful playlist writes (after the snapshot was updated), for open pages to follow. */
    val edits: SharedFlow<PlaylistEdit> = _edits.asSharedFlow()

    val loggedIn: Boolean get() = _state.value == SessionState.LoggedIn

    private val lock = Any()
    private var spDc = ""
    private var token = TokenBundle()
    private var lastRefreshFailMs = 0L

    /** Bumped by logout(): continuations of an older generation are dropped. */
    @Volatile
    private var generation = 0

    /** Bumped by every applied edit: a library load that overlapped one re-fetches the playlists. */
    private val editSeq = AtomicInteger(0)
    private val refreshMutex = Mutex()
    private var refreshJob: Job? = null

    /** Cookie file writes in call order (a logout's clear must not overtake the next login's save). */
    private val cookieDispatcher = AppDispatchers.io.limitedParallelism(1)

    @Volatile
    private var scope = newScope()

    private fun newScope() = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    init {
        api.beforeRequest = { ensureFreshToken() }
        current = this
    }

    private fun isCurrent(gen: Int) = gen == generation

    /** Runs [block] under the lock when [gen] is still the live generation (so logout() can't interleave). */
    private inline fun ifCurrent(gen: Int, block: () -> Unit): Boolean = synchronized(lock) {
        if (gen != generation) return false
        block()
        true
    }

    // ---- Login / logout -------------------------------------------------------------------------------------------

    /** Loads a previously saved sp_dc (if any) and starts minting a token in the background. */
    fun restore() {
        if (!cookieStore.exists()) return
        val gen = generation
        _state.value = SessionState.Connecting
        scope.launch(cookieDispatcher) {
            val cookie = cookieStore.load()
            if (cookie.isEmpty()) {
                ifCurrent(gen) { if (_state.value == SessionState.Connecting) _state.value = SessionState.LoggedOut }
                return@launch
            }
            if (ifCurrent(gen) { spDc = cookie }) beginTokenFetch(gen, alsoProfile = true)
        }
    }

    /** Called by the WebView login once it captures the cookie. Fetches a token + profile, then LoggedIn. */
    fun loginWithCookie(cookie: String) {
        if (cookie.isEmpty()) return
        val gen = synchronized(lock) {
            spDc = cookie
            _state.value = SessionState.Connecting
            generation
        }
        appScope.launch(cookieDispatcher) { cookieStore.save(cookie) }
        beginTokenFetch(gen, alsoProfile = true)
    }

    fun logout() {
        synchronized(lock) {
            generation++
            spDc = ""
            token = TokenBundle()
            scope.cancel() // drop any in-flight continuations
            scope = newScope()
            refreshJob = null
            _profile.value = UserProfile()
            _library.value = LibrarySnapshot()
            api.setCredentials("", "")
            api.setUsername("")
            _state.value = SessionState.LoggedOut
        }
        appScope.launch(cookieDispatcher) { cookieStore.clear() }
    }

    private fun beginTokenFetch(gen: Int, alsoProfile: Boolean, attempt: Int = 0) {
        val cookie = synchronized(lock) { spDc }
        scope.launch(AppDispatchers.io) {
            val t = try {
                fetchToken(cookie)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "token fetch failed (attempt ${attempt + 1}): ${e.message}")
                // Android addition: a phone often starts (or comes back from an update / Doze) before the network is up,
                // and the desktop's "fail = logged out" left the session logged out until the next app start. A
                // transient failure (no network, DNS, timeout, 5xx / 429) keeps the cookie and retries with backoff;
                // the session shows Connecting for the first few tries, then the guest UI while it keeps retrying.
                // Only Spotify rejecting the cookie (anonymous / 4xx token answer) ends the session.
                val transient = isTransientTokenFailure(e)
                ifCurrent(gen) {
                    if (_state.value == SessionState.Connecting && (!transient || attempt >= CONNECTING_TRIES - 1)) {
                        _state.value = SessionState.LoggedOut
                    }
                }
                if (transient && isCurrent(gen)) {
                    delay(TOKEN_RETRY_MS[attempt.coerceAtMost(TOKEN_RETRY_MS.size - 1)])
                    if (isCurrent(gen) && synchronized(lock) { spDc == cookie } && _state.value != SessionState.LoggedIn) {
                        beginTokenFetch(gen, alsoProfile, attempt + 1)
                    }
                }
                return@launch
            }
            val live = ifCurrent(gen) {
                token = t
                api.setCredentials(t.accessToken, cookie)
            }
            if (!live) return@launch
            startRefreshLoop()
            if (!alsoProfile) {
                ifCurrent(gen) { if (_state.value != SessionState.LoggedIn) _state.value = SessionState.LoggedIn }
                return@launch
            }
            val pr = try {
                api.me()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "profile fetch failed: ${e.message}")
                null
            }
            val loggedIn = ifCurrent(gen) {
                if (pr != null && pr.valid) _profile.value = pr
                _state.value = SessionState.LoggedIn
            }
            if (loggedIn) reloadLibrary()
        }
    }

    // ---- Token refresh --------------------------------------------------------------------------------------------

    private fun needsRefresh(): Boolean = synchronized(lock) {
        spDc.isNotEmpty() &&
            (_state.value == SessionState.LoggedIn || _state.value == SessionState.Connecting) &&
            token.expired(nowMs())
    }

    /**
     * Refreshes the token shortly before it expires (the C++ maybeRefresh from the 2 s housekeeping tick). Runs from
     * the refresh loop and before every API request; one refresh at a time, other callers wait for it. A failed
     * refresh is retried at the earliest one tick (2 s) later.
     */
    internal suspend fun ensureFreshToken() {
        if (!needsRefresh()) return
        refreshMutex.withLock {
            if (!needsRefresh()) return
            val (cookie, gen) = synchronized(lock) {
                if (nowMs() - lastRefreshFailMs < TICK_MS) return
                spDc to generation
            }
            try {
                val t = fetchToken(cookie)
                synchronized(lock) {
                    if (isCurrent(gen)) {
                        token = t
                        api.setCredentials(t.accessToken, cookie)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) { lastRefreshFailMs = nowMs() }
                Log.w(TAG, "token refresh failed: ${e.message}")
            }
        }
    }

    /**
     * Replaces the housekeeping tick: sleeps until a minute before expiry (at most 60 s at a time, so a clock change or
     * a long deep sleep is noticed soon after wake-up), at least one tick (2 s).
     */
    private fun startRefreshLoop() {
        synchronized(lock) {
            if (refreshJob?.isActive == true || spDc.isEmpty()) return
            refreshJob = scope.launch(AppDispatchers.io) {
                while (isActive) {
                    val expiresAt = synchronized(lock) { token.expiresAtMs }
                    val wait = if (expiresAt == 0L) MAX_SLEEP_MS else expiresAt - 60_000 - nowMs()
                    delay(wait.coerceIn(TICK_MS, MAX_SLEEP_MS))
                    ensureFreshToken()
                }
            }
        }
    }

    // ---- Library --------------------------------------------------------------------------------------------------

    /** (Re)fetches playlists / saved albums / followed artists / Liked Songs ids in the background. */
    fun reloadLibrary() {
        if (!api.hasCredentials()) return
        val seq = editSeq.get()
        val gen = generation
        scope.launch(AppDispatchers.io) {
            // Each list is independent; a failure in one shouldn't blank the others.
            suspend fun <T> tryFetch(fallback: T, fn: suspend () -> T): T = try {
                fn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "library fetch failed: ${e.message}")
                fallback
            }
            val lp = tryFetch(LibraryPlaylists(emptyList(), PlaylistTree())) { api.libraryPlaylists() }
            val albums = tryFetch(emptyList()) { api.libraryAlbums() }
            val artists = tryFetch(emptyList()) { api.libraryArtists() }
            // Liked Songs ids for heart state (paged; capped so a huge library can't stall login).
            val liked = LinkedHashSet<String>()
            tryFetch(Unit) {
                var off = 0
                while (off < 10_000) {
                    val pg = api.playlistTracks(LIKED_SONGS_URI, off, 100).page
                    for (t in pg.items) if (t.id.isNotEmpty()) liked.add(t.id)
                    val next = pg.next
                    if (!pg.hasMore || next <= off) break
                    off = next
                }
            }
            val s = LibrarySnapshot(lp.playlists, lp.tree, albums, artists, liked, loaded = true)
            if (!ifCurrent(gen) { _library.value = s }) return@launch
            Log.i(TAG, "library: ${s.playlists.size} playlists, ${s.albums.size} albums, ${s.artists.size} artists")
            // A playlist edit applied while this load was in flight may be missing from what it fetched (e.g. a
            // playlist created right after login): re-read the playlists, which now include it.
            if (editSeq.get() != seq) reloadPlaylists()
        }
    }

    /** Playlists only (libraryV3 + rootlist counts) — much cheaper than [reloadLibrary] (no Liked Songs paging). */
    fun reloadPlaylists() {
        if (!api.hasCredentials()) return
        val gen = generation
        scope.launch(AppDispatchers.low) {
            val lp = try {
                api.libraryPlaylists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "playlists reload failed: ${e.message}")
                return@launch
            }
            ifCurrent(gen) { _library.update { it.copy(playlists = lp.playlists, tree = lp.tree) } }
        }
    }

    // ---- Heart / saved / following state ----------------------------------------------------------------------------
    // Optimistic local update; the write to Spotify is fired separately (setLiked & co. do both, like AppContext's
    // Library::setLiked: Spotify is the source of truth, the heart updates at once and the write is mirrored).

    fun isLiked(trackId: String): Boolean = trackId in _library.value.likedIds
    fun isSavedAlbum(id: String): Boolean = _library.value.albums.any { it.id == id }
    fun isFollowing(id: String): Boolean = _library.value.artists.any { it.id == id }

    fun markLiked(trackId: String, liked: Boolean) =
        _library.update { it.copy(likedIds = if (liked) it.likedIds + trackId else it.likedIds - trackId) }

    fun markAlbumSaved(a: Album, saved: Boolean) = _library.update { lib ->
        if (saved) {
            if (lib.albums.any { it.id == a.id }) lib else lib.copy(albums = listOf(a) + lib.albums)
        } else {
            lib.copy(albums = lib.albums.filter { it.id != a.id })
        }
    }

    fun markArtistFollowed(a: Artist, follow: Boolean) = _library.update { lib ->
        if (follow) {
            if (lib.artists.any { it.id == a.id }) lib else lib.copy(artists = listOf(a) + lib.artists)
        } else {
            lib.copy(artists = lib.artists.filter { it.id != a.id })
        }
    }

    /** Heart + mirrored write (fire and forget; a failed write is logged). */
    fun setLiked(trackId: String, liked: Boolean) {
        if (trackId.isEmpty()) return
        markLiked(trackId, liked)
        appScope.launch(AppDispatchers.low) { api.setTracksSaved(listOf(trackId), liked) }
    }

    /** Likes every not-yet-liked id at once (one write); returns how many were new. */
    fun likeAll(trackIds: List<String>): Int {
        val fresh = trackIds.filter { it.isNotEmpty() && !isLiked(it) }.distinct()
        if (fresh.isEmpty()) return 0
        _library.update { it.copy(likedIds = it.likedIds + fresh) }
        appScope.launch(AppDispatchers.low) { api.setTracksSaved(fresh, true) }
        return fresh.size
    }

    fun setAlbumSaved(a: Album, saved: Boolean) {
        if (a.id.isEmpty()) return
        markAlbumSaved(a, saved)
        appScope.launch(AppDispatchers.low) { api.setAlbumsSaved(listOf(a.id), saved) }
    }

    fun setArtistFollowed(a: Artist, follow: Boolean) {
        if (a.id.isEmpty()) return
        markArtistFollowed(a, follow)
        appScope.launch(AppDispatchers.low) { api.setArtistsFollowed(listOf(a.id), follow) }
    }

    // ---- Playlist editing (writes to the user's Spotify account) ------------------------------------------------------
    // Each call writes on the io dispatcher inside the session's scope (it completes even when the caller goes away).
    // On success the snapshot is updated in place (exact, no refetch: the library views update at once and can't
    // flicker back to stale server data) and the edit is emitted on [edits]. Nothing is applied after logout() (the
    // call then reports failure).

    fun findPlaylist(uri: String): Playlist? = _library.value.playlists.firstOrNull { it.id == uri }

    /** Playlists the user can add songs to (owned or collaborative), in library order. Excludes Liked Songs. */
    fun editablePlaylists(): List<Playlist> =
        _library.value.playlists.filter { it.editable && it.id.startsWith("spotify:playlist:") }

    private suspend fun <T> inSession(failure: T, work: suspend (gen: Int) -> T): T {
        val gen = generation
        val d = scope.async(AppDispatchers.io) { work(gen) }
        return try {
            d.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the caller itself was cancelled: propagate
            failure // logged out meanwhile
        }
    }

    /** Creates a playlist (top of the library) and adds [trackUris] to it. uri = "" on failure. */
    suspend fun createPlaylist(name: String, trackUris: List<String> = emptyList()): CreatedPlaylist {
        val none = CreatedPlaylist("", 0)
        if (!api.hasCredentials() || name.isEmpty()) return none
        return inSession(none) { gen ->
            val uri = api.createPlaylist(name)
            var added = 0
            if (uri.isNotEmpty() && trackUris.isNotEmpty() && api.addToPlaylist(uri, trackUris)) added = trackUris.size
            val live = ifCurrent(gen) {
                if (uri.isNotEmpty()) applyEdit(PlaylistEdit(PlaylistEdit.Kind.Created, uri, name = name, count = added))
            }
            if (live) CreatedPlaylist(uri, added) else none
        }
    }

    suspend fun addToPlaylist(uri: String, trackUris: List<String>): Boolean {
        if (!api.hasCredentials() || uri.isEmpty() || trackUris.isEmpty()) return false
        val n = trackUris.size
        return inSession(false) { gen ->
            val ok = api.addToPlaylist(uri, trackUris)
            ok && ifCurrent(gen) { applyEdit(PlaylistEdit(PlaylistEdit.Kind.Added, uri, count = n)) }
        }
    }

    /** Rows by Track.uid (from [SpotifyApi.playlistTracks]). */
    suspend fun removeFromPlaylist(uri: String, uids: List<String>): Boolean {
        val clean = uids.filter { it.isNotEmpty() }
        if (!api.hasCredentials() || uri.isEmpty() || clean.isEmpty()) return false
        return inSession(false) { gen ->
            val ok = api.removeFromPlaylist(uri, clean)
            ok && ifCurrent(gen) {
                applyEdit(PlaylistEdit(PlaylistEdit.Kind.Removed, uri, removedUids = clean, count = clean.size))
            }
        }
    }

    suspend fun renamePlaylist(uri: String, name: String): Boolean {
        if (!api.hasCredentials() || uri.isEmpty() || name.isEmpty()) return false
        return inSession(false) { gen ->
            val ok = api.renamePlaylist(uri, name)
            ok && ifCurrent(gen) { applyEdit(PlaylistEdit(PlaylistEdit.Kind.Renamed, uri, name = name)) }
        }
    }

    /** Owned: Spotify's "delete"; someone else's: unfollow. Either way it leaves the library. */
    suspend fun deletePlaylist(uri: String): Boolean {
        if (!api.hasCredentials() || !uri.startsWith("spotify:playlist:")) return false
        return inSession(false) { gen ->
            val ok = api.deletePlaylist(uri)
            ok && ifCurrent(gen) { applyEdit(PlaylistEdit(PlaylistEdit.Kind.Deleted, uri)) }
        }
    }

    /** Tests only: replaces the snapshot. */
    internal fun replaceLibraryForTest(s: LibrarySnapshot) {
        _library.value = s
    }

    /** Snapshot update + notification of a successful edit. */
    internal fun applyEdit(e: PlaylistEdit) {
        _library.update { lib -> lib.copy(playlists = applyEditTo(lib.playlists, e)) }
        editSeq.incrementAndGet()
        if (!_edits.tryEmit(e)) Log.w(TAG, "playlist edit dropped (no collector keeping up)")
    }

    private fun applyEditTo(playlists: List<Playlist>, e: PlaylistEdit): List<Playlist> {
        val pls = playlists.toMutableList()
        val i = pls.indexOfFirst { it.id == e.uri }
        when (e.kind) {
            PlaylistEdit.Kind.Created -> {
                if (i >= 0) return playlists
                val p = Playlist(
                    id = e.uri,
                    name = e.name,
                    totalTracks = e.count,
                    countKnown = true,
                    ownerId = api.username(),
                    ownerName = _profile.value.name,
                    owned = true,
                    editable = true,
                    createdAt = nowMs() / 1000,
                )
                // The web player adds new playlists to the top of the rootlist; keep pinned pseudo-playlists (Liked
                // Songs) first.
                val pos = pls.indexOfFirst { it.id.startsWith("spotify:playlist:") }
                pls.add(if (pos < 0) pls.size else pos, p)
            }
            PlaylistEdit.Kind.Added ->
                if (i >= 0 && pls[i].countKnown) pls[i] = pls[i].copy(totalTracks = pls[i].totalTracks + e.count)
            PlaylistEdit.Kind.Removed ->
                if (i >= 0 && pls[i].countKnown) pls[i] = pls[i].copy(totalTracks = maxOf(0, pls[i].totalTracks - e.count))
            PlaylistEdit.Kind.Renamed -> if (i >= 0) pls[i] = pls[i].copy(name = e.name)
            PlaylistEdit.Kind.Deleted -> if (i >= 0) pls.removeAt(i)
        }
        return pls
    }

    companion object {
        private const val TAG = "spotify"

        /** The C++ housekeeping tick. */
        private const val TICK_MS = 2_000L
        private const val MAX_SLEEP_MS = 60_000L

        /** The live session (the last one constructed; AppGraph builds exactly one). Used by [SpotifyLoginActivity]. */
        @Volatile
        var current: Session? = null
            private set
    }
}

/** Backoff between token attempts after a transient failure (ms); the last value repeats. */
private val TOKEN_RETRY_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000, 60_000)

/** Token attempts that keep the session in Connecting before the guest UI shows (it keeps retrying behind it). */
private const val CONNECTING_TRIES = 3

/** Network / server trouble (retry) vs Spotify rejecting the cookie (log out). */
internal fun isTransientTokenFailure(e: Throwable): Boolean {
    if (e !is SpotifyAuthException) return true
    val status = Regex("""HTTP (\d{3})""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return status >= 500 || status == 429
}
