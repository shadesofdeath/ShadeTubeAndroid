package com.app.shadetube.library

import com.app.shadetube.catalog.Playlist
import com.app.shadetube.spotify.CreatedPlaylist
import com.app.shadetube.spotify.LibrarySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistActionsTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private val own = Playlist(id = "spotify:playlist:own", name = "Mine", editable = true, owned = true)
    private val collab = Playlist(id = "spotify:playlist:collab", name = "Ours", editable = true, owned = false)
    private val foreign = Playlist(id = "spotify:playlist:theirs", name = "Theirs")
    private val liked = Playlist(id = "spotify:collection:tracks", name = "Liked", editable = true)

    private fun setup(loggedIn: Boolean): Triple<FakeAccount, UserLibrary, PlaylistActions> {
        val acc = FakeAccount(loggedIn)
        acc.library.value = LibrarySnapshot(playlists = listOf(liked, own, collab, foreign), loaded = true)
        val lib = UserLibrary(acc, { File(tmp.root, "library.json") }, scope, saveDelayMs = 60_000)
        return Triple(acc, lib, PlaylistActions(lib, acc))
    }

    @Test
    fun spotifyTrackUrisKeepsOnlySpotifyTracks() {
        assertEquals(listOf("spotify:track:1"), spotifyTrackUris(listOf(track("spotify:track:1"), track("local:x"), track("mbid"), track("spotify:album:1"))))
    }

    @Test
    fun editableSpotifyPlaylistNeedsSessionAndRights() {
        val (acc, _, a) = setup(loggedIn = true)
        assertTrue(a.isEditableSpotifyPlaylist(own.id))
        assertTrue(a.isEditableSpotifyPlaylist(collab.id))
        assertFalse(a.isEditableSpotifyPlaylist(foreign.id))
        assertFalse(a.isEditableSpotifyPlaylist(liked.id)) // not a spotify:playlist: URI
        assertFalse(a.isEditableSpotifyPlaylist("spotify:playlist:unknown"))
        assertEquals(listOf(own.id, collab.id), a.addTargets().map { it.id })
        acc.logout()
        assertFalse(a.isEditableSpotifyPlaylist(own.id))
    }

    @Test
    fun createLoggedInWritesSpotifyTracksOnly() = runBlocking {
        val (acc, lib, a) = setup(loggedIn = true)
        lib.labels = LibraryLabels("Yeni")
        assertEquals(NewPlaylistCheck.Spotify, a.newPlaylistCheck())
        assertEquals(NewPlaylistCheck.NotOnSpotify, a.newPlaylistCheck(listOf(track("local:1"))))
        assertEquals(CreatePlaylistResult.NotOnSpotify, a.createPlaylist("x", listOf(track("local:1"))))
        assertNull(acc.created)

        acc.createResult = CreatedPlaylist("spotify:playlist:new", 1)
        val r = a.createPlaylist("  Road  ", listOf(track("spotify:track:1"), track("local:2")))
        assertEquals(CreatePlaylistResult.Created("spotify:playlist:new", true, 1, false, 1), r)
        assertEquals("Road" to listOf("spotify:track:1"), acc.created)
        assertTrue(lib.playlists.isEmpty())

        // Created, but adding the songs failed: say so.
        acc.createResult = CreatedPlaylist("spotify:playlist:n2", 0)
        assertEquals(CreatePlaylistResult.Created("spotify:playlist:n2", true, 0, true, 0), a.createPlaylist("", listOf(track("spotify:track:9"))))
        assertEquals("Yeni", acc.created!!.first)

        acc.createResult = CreatedPlaylist("", 0)
        assertEquals(CreatePlaylistResult.Failed, a.createPlaylist("x"))
    }

    @Test
    fun createLoggedOutMakesALocalPlaylist() = runBlocking {
        val (_, lib, a) = setup(loggedIn = false)
        assertEquals(NewPlaylistCheck.Local, a.newPlaylistCheck(listOf(track("local:1"))))
        val r = a.createPlaylist("Local", listOf(track("local:1"), track("mbid")))
        r as CreatePlaylistResult.Created
        assertFalse(r.spotify)
        assertEquals(2, r.added)
        assertEquals("Local", lib.playlist(r.id)!!.name)
        assertEquals(2, lib.playlist(r.id)!!.totalTracks)
    }

    @Test
    fun addToPlaylistRules() = runBlocking {
        val (acc, lib, a) = setup(loggedIn = true)
        assertEquals(AddToPlaylistResult.NotFound, a.addToPlaylist("spotify:playlist:nope", listOf(track("spotify:track:1"))))
        assertEquals(AddToPlaylistResult.OnlySpotifyTracks, a.addToPlaylist(own.id, listOf(track("local:1"))))
        assertEquals(AddToPlaylistResult.Added("Mine", 1, 2),
            a.addToPlaylist(own.id, listOf(track("spotify:track:1"), track("local:1"), track("mbid"))))
        assertEquals(listOf(own.id to listOf("spotify:track:1")), acc.added)
        acc.writeOk = false
        assertEquals(AddToPlaylistResult.Failed("Mine"), a.addToPlaylist(own.id, listOf(track("spotify:track:2"))))

        acc.logout()
        val id = lib.createPlaylist("L")
        assertEquals(AddToPlaylistResult.Added("L", 1, 0), a.addToPlaylist(id, listOf(track("x"))))
        assertEquals(AddToPlaylistResult.AlreadyInList("L"), a.addToPlaylist(id, listOf(track("x"))))
        assertEquals(AddToPlaylistResult.NotFound, a.addToPlaylist(own.id, listOf(track("x"))))
    }

    @Test
    fun renameRules() = runBlocking {
        val (acc, lib, a) = setup(loggedIn = true)
        assertEquals(RenameResult.Unchanged, a.renamePlaylist(own.id, "  ", "Mine"))
        assertEquals(RenameResult.Unchanged, a.renamePlaylist(own.id, "Mine ", "Mine"))
        assertEquals(RenameResult.Renamed, a.renamePlaylist(own.id, " New ", "Mine"))
        assertEquals(own.id to "New", acc.renamed)
        acc.writeOk = false
        assertEquals(RenameResult.Failed, a.renamePlaylist(own.id, "Other", "Mine"))
        acc.logout()
        assertEquals(RenameResult.NoSpotifyConnection, a.renamePlaylist(own.id, "Other", "Mine"))
        val id = lib.createPlaylist("L")
        assertEquals(RenameResult.Renamed, a.renamePlaylist(id, "L2", "L"))
        assertEquals("L2", lib.playlist(id)!!.name)
    }

    @Test
    fun deleteRules() = runBlocking {
        val (acc, lib, a) = setup(loggedIn = true)
        assertEquals(DeleteKind.DeleteSpotify, a.deleteKind(own.id))
        assertEquals(DeleteKind.RemoveFromLibrary, a.deleteKind(collab.id))
        assertEquals(DeleteKind.RemoveFromLibrary, a.deleteKind(foreign.id))
        assertEquals(DeleteResult(DeleteKind.DeleteSpotify, true), a.deletePlaylist(own.id))
        assertEquals(own.id, acc.deleted)
        acc.writeOk = false
        assertEquals(DeleteResult(DeleteKind.RemoveFromLibrary, false), a.deletePlaylist(foreign.id))
        acc.logout()
        assertNull(a.deleteKind(own.id))
        assertNull(a.deletePlaylist(own.id))
        val id = lib.createPlaylist("L")
        assertEquals(DeleteResult(DeleteKind.DeleteLocal, true), a.deletePlaylist(id))
        assertNull(lib.playlist(id))
    }

    @Test
    fun removeRowsByUidOrId() = runBlocking {
        val (acc, lib, a) = setup(loggedIn = true)
        val rows = listOf(track("spotify:track:1", uid = "u1"), track("spotify:track:2", uid = ""),
            track("spotify:track:3", uid = "u3").copy(recommended = true))
        assertEquals(RemoveTracksResult.Removed, a.removeFromPlaylist(own.id, rows))
        assertEquals(listOf(own.id to listOf("u1")), acc.removed) // recommendations are not the list's rows
        assertEquals(RemoveTracksResult.Nothing, a.removeFromPlaylist(own.id, listOf(rows[2])))
        assertEquals(RemoveTracksResult.Failed, a.removeFromPlaylist(own.id, listOf(rows[1]))) // no uid
        acc.logout()
        assertEquals(RemoveTracksResult.Failed, a.removeFromPlaylist(own.id, listOf(rows[0])))
        val id = lib.createPlaylist("L")
        lib.addToPlaylist(id, listOf(track("a"), track("b")))
        assertEquals(RemoveTracksResult.Removed, a.removeFromPlaylist(id, listOf(track("a"))))
        assertEquals(listOf("b"), lib.playlistTracks(id)!!.map { it.id })
    }
}
