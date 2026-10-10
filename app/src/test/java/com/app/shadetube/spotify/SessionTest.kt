package com.app.shadetube.spotify

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.catalog.Playlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session state handling without network: snapshot edits, optimistic marks, login state transitions. */
class SessionTest {
    private class MemoryStore(var value: String = "") : SpotifyCookieStore {
        override fun save(spDc: String) { value = spDc }
        override fun load() = value
        override fun clear() { value = "" }
        override fun exists() = value.isNotEmpty()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun session(
        store: SpotifyCookieStore = MemoryStore(),
        token: suspend (String) -> TokenBundle = { throw SpotifyAuthException("offline") },
    ) = Session(SpotifyApi(), store, scope, token, nowMs = { 1_000_000L })

    private fun lib() = LibrarySnapshot(
        playlists = listOf(
            Playlist(id = LIKED_SONGS_URI, name = "Liked"),
            Playlist(id = "spotify:playlist:a", name = "A", totalTracks = 5, editable = true),
            Playlist(id = "spotify:playlist:b", name = "B", totalTracks = 3, countKnown = false),
        ),
        loaded = true,
    )

    @Test
    fun transientTokenFailureRetriesInsteadOfLoggingOut() = runBlocking {
        var calls = 0
        val s = session(MemoryStore("cookie")) {
            calls++
            if (calls == 1) throw java.io.IOException("Unable to resolve host open.spotify.com")
            TokenBundle(accessToken = "t", expiresAtMs = 9_999_999_999L)
        }
        s.restore()
        assertEquals(SessionState.Connecting, s.state.value)
        withTimeout(10_000) { while (calls < 2) delay(50) } // retried after the 2 s backoff
        assertTrue(calls >= 2)
        assertTrue(s.state.value != SessionState.LoggedOut || calls >= 2)
    }

    @Test
    fun rejectedCookieLogsOut() = runBlocking {
        val s = session(MemoryStore("cookie")) { throw SpotifyAuthException("anonymous token (sp_dc rejected)") }
        s.restore()
        withTimeout(5_000) { while (s.state.value != SessionState.LoggedOut) delay(20) }
        assertEquals(SessionState.LoggedOut, s.state.value)
    }

    @Test
    fun transientClassification() {
        assertTrue(isTransientTokenFailure(java.io.IOException("timeout")))
        assertTrue(isTransientTokenFailure(SpotifyAuthException("token endpoint HTTP 503")))
        assertTrue(isTransientTokenFailure(SpotifyAuthException("token endpoint HTTP 429")))
        assertFalse(isTransientTokenFailure(SpotifyAuthException("token endpoint HTTP 401")))
        assertFalse(isTransientTokenFailure(SpotifyAuthException("anonymous token (sp_dc rejected)")))
    }

    @Test
    fun registersAsCurrent() {
        val s = session()
        assertSame(s, Session.current)
    }

    @Test
    fun editsUpdateSnapshotInPlace() = runBlocking {
        val s = session()
        s.replaceLibraryForTest(lib())
        val edits = ArrayList<PlaylistEdit>()

        s.applyEdit(PlaylistEdit(PlaylistEdit.Kind.Created, "spotify:playlist:new", name = "New", count = 2))
        var pls = s.library.value.playlists
        assertEquals(listOf(LIKED_SONGS_URI, "spotify:playlist:new", "spotify:playlist:a", "spotify:playlist:b"), pls.map { it.id })
        val created = pls[1]
        assertTrue(created.owned && created.editable && created.countKnown)
        assertEquals(2, created.totalTracks)
        assertEquals(1000L, created.createdAt)
        // Created again: no duplicate.
        s.applyEdit(PlaylistEdit(PlaylistEdit.Kind.Created, "spotify:playlist:new", name = "New"))
        assertEquals(4, s.library.value.playlists.size)

        s.applyEdit(PlaylistEdit(PlaylistEdit.Kind.Added, "spotify:playlist:a", count = 3))
        s.applyEdit(PlaylistEdit(PlaylistEdit.Kind.Added, "spotify:playlist:b", count = 3)) // count unknown: untouched
        s.applyEdit(PlaylistEdit(PlaylistEdit.Kind.Removed, "spotify:playlist:new", removedUids = listOf("u"), count = 9))
        s.applyEdit(PlaylistEdit(PlaylistEdit.Kind.Renamed, "spotify:playlist:a", name = "A2"))
        pls = s.library.value.playlists
        assertEquals(8, pls.first { it.id == "spotify:playlist:a" }.totalTracks)
        assertEquals("A2", pls.first { it.id == "spotify:playlist:a" }.name)
        assertEquals(3, pls.first { it.id == "spotify:playlist:b" }.totalTracks)
        assertEquals(0, pls.first { it.id == "spotify:playlist:new" }.totalTracks) // clamped at 0
        s.applyEdit(PlaylistEdit(PlaylistEdit.Kind.Deleted, "spotify:playlist:a"))
        assertNull(s.findPlaylist("spotify:playlist:a"))
        assertEquals(listOf("spotify:playlist:new"), s.editablePlaylists().map { it.id })

        // Edits are broadcast after the snapshot changed.
        val collected = async(start = CoroutineStart.UNDISPATCHED) { s.edits.first() }
        s.applyEdit(PlaylistEdit(PlaylistEdit.Kind.Renamed, "spotify:playlist:new", name = "N2"))
        edits.add(withTimeout(2000) { collected.await() })
        assertEquals("N2", edits[0].name)
    }

    @Test
    fun createdGoesToTheEndWithoutRealPlaylists() {
        val s = session()
        s.replaceLibraryForTest(LibrarySnapshot(playlists = listOf(Playlist(id = LIKED_SONGS_URI))))
        s.applyEdit(PlaylistEdit(PlaylistEdit.Kind.Created, "spotify:playlist:n", name = "N"))
        assertEquals(listOf(LIKED_SONGS_URI, "spotify:playlist:n"), s.library.value.playlists.map { it.id })
    }

    @Test
    fun optimisticMarks() {
        val s = session()
        s.markLiked("spotify:track:1", true)
        assertTrue(s.isLiked("spotify:track:1"))
        s.markLiked("spotify:track:1", false)
        assertFalse(s.isLiked("spotify:track:1"))
        val a = Album(id = "spotify:album:1")
        s.markAlbumSaved(a, true)
        s.markAlbumSaved(a, true)
        assertEquals(1, s.library.value.albums.size)
        assertTrue(s.isSavedAlbum(a.id))
        s.markAlbumSaved(a, false)
        assertFalse(s.isSavedAlbum(a.id))
        val ar = Artist(id = "spotify:artist:1")
        s.markArtistFollowed(ar, true)
        assertTrue(s.isFollowing(ar.id))
        s.markArtistFollowed(Artist(id = "spotify:artist:2"), true)
        assertEquals("spotify:artist:2", s.library.value.artists[0].id) // newest first
        s.markArtistFollowed(ar, false)
        assertFalse(s.isFollowing(ar.id))
    }

    @Test
    fun loginFailureFallsBackToLoggedOut() = runBlocking {
        val store = MemoryStore()
        val s = session(store)
        assertEquals(SessionState.LoggedOut, s.state.value)
        s.loginWithCookie("cookie")
        withTimeout(5000) { s.state.first { it == SessionState.LoggedOut } }
        withTimeout(5000) { while (store.value != "cookie") delay(10) } // persisted even if the token failed
        s.logout()
        withTimeout(5000) { while (store.value.isNotEmpty()) delay(10) }
        assertEquals(SessionState.LoggedOut, s.state.value)
    }

    @Test
    fun restoreWithoutCookieStaysLoggedOut() {
        val s = session(MemoryStore(""))
        s.restore()
        assertEquals(SessionState.LoggedOut, s.state.value)
    }

    @Test
    fun restoreConnectsThenFailsOffline() = runBlocking {
        var asked = ""
        val s = session(MemoryStore("saved")) { asked = it; throw SpotifyAuthException("offline") }
        s.restore()
        withTimeout(5000) { s.state.first { it == SessionState.LoggedOut } }
        assertEquals("saved", asked)
    }

    @Test
    fun playlistWritesNeedCredentials() = runBlocking {
        val s = session()
        assertEquals(CreatedPlaylist("", 0), s.createPlaylist("x"))
        assertFalse(s.addToPlaylist("spotify:playlist:a", listOf("spotify:track:1")))
        assertFalse(s.removeFromPlaylist("spotify:playlist:a", listOf("", "")))
        assertFalse(s.renamePlaylist("spotify:playlist:a", "n"))
        assertFalse(s.deletePlaylist("spotify:playlist:a"))
    }
}
