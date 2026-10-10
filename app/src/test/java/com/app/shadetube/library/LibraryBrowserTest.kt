package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.spotify.LibrarySnapshot
import com.app.shadetube.spotify.PlaylistFolder
import com.app.shadetube.spotify.PlaylistTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryBrowserTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private val expanded = MutableStateFlow(listOf<String>())

    private val snap = LibrarySnapshot(
        playlists = listOf(
            Playlist(id = "spotify:collection:tracks", name = "Liked", totalTracks = 5),
            Playlist(id = "spotify:playlist:p1", name = "P1", totalTracks = 1, editable = true, owned = true),
            Playlist(id = "spotify:playlist:p2", name = "P2", countKnown = false),
            Playlist(id = "spotify:playlist:p3", name = "P3", editable = true),
        ),
        tree = PlaylistTree(
            folders = listOf(PlaylistFolder("f1", "Chill", "", 2), PlaylistFolder("f2", "", "f1", 1), PlaylistFolder("f3", "Empty", "", 0)),
            folderOf = mapOf("spotify:playlist:p2" to "f1", "spotify:playlist:p3" to "f2"),
        ),
        albums = listOf(Album(id = "spotify:album:a", name = "A", primaryType = "EP", firstReleaseDate = "2001")),
        artists = listOf(Artist(id = "spotify:artist:b", type = "Group", country = "TR")),
        loaded = true,
    )

    private fun setup(loggedIn: Boolean): Triple<FakeAccount, UserLibrary, LibraryBrowser> {
        val acc = FakeAccount(loggedIn)
        acc.library.value = snap
        val lib = UserLibrary(acc, { File(tmp.root, "library.json") }, scope, saveDelayMs = 60_000)
        val b = LibraryBrowser(acc, lib, expanded, { expanded.value }, { f -> expanded.value = f(expanded.value) })
        return Triple(acc, lib, b)
    }

    @Test
    fun spotifyLibraryTopLevelAndFolders() {
        val (acc, _, b) = setup(loggedIn = true)
        val top = b.build("", LibraryTab.Playlists)
        assertTrue(top.spotify && top.showSmartLists)
        assertNull(top.folder)
        // A folder sits where its first (most recent) playlist would be; empty folders last.
        assertEquals(
            listOf(Destination.Liked, Destination.Playlist("spotify:playlist:p1"), Destination.Library("f1"), Destination.Library("f3")),
            top.cards.map { it.destination },
        )
        assertEquals(CardSubtitle.Playlists(2), top.cards[2].subtitle)
        assertEquals(CardPlaceholder.Folder, top.cards[2].placeholder)

        val f1 = b.build("folder:f1", LibraryTab.Albums) // the tab doesn't matter inside a folder
        assertEquals("Chill", f1.folder!!.folder.name)
        assertEquals(Destination.Library(""), f1.folder!!.up)
        assertEquals(listOf(Destination.Playlist("spotify:playlist:p2"), Destination.Library("f2")), f1.cards.map { it.destination })
        assertEquals(CardSubtitle.PlaylistWord, f1.cards[0].subtitle)
        val f2 = b.build("folder:f2", LibraryTab.Playlists)
        assertEquals(Destination.Library("f1"), f2.folder!!.up)
        assertEquals(LibraryEmpty.EmptyFolder, b.build("folder:f3", LibraryTab.Playlists).empty)

        // A folder that is gone (or logged out): the whole library.
        assertNull(b.build("folder:nope", LibraryTab.Playlists).folder)
        acc.logout()
        assertNull(b.build("folder:f1", LibraryTab.Playlists).folder)
    }

    @Test
    fun tabsFollowTheSource() {
        val (acc, lib, b) = setup(loggedIn = true)
        val albums = b.build("", LibraryTab.Albums)
        assertEquals(CardSubtitle.AlbumLine("2001", AlbumType.EP, ""), albums.cards.single().subtitle)
        assertEquals(CardSubtitle.ArtistKind(true, "TR"), b.build("", LibraryTab.Artists).cards.single().subtitle)
        acc.logout()
        assertEquals(LibraryEmpty.NoAlbums, b.build("", LibraryTab.Albums).empty)
        assertEquals(LibraryEmpty.NoArtists, b.build("", LibraryTab.Artists).empty)
        assertEquals(LibraryEmpty.NoHistory, b.build("", LibraryTab.History).empty)
        lib.recordPlay(track("h"))
        assertEquals(listOf("h"), b.build("", LibraryTab.History).history.map { it.id })
        val id = lib.createPlaylist("Zeta")
        lib.createPlaylist("alpha")
        val local = b.build("", LibraryTab.Playlists)
        assertTrue(!local.spotify)
        assertEquals(Destination.Liked, local.cards[0].destination) // always, even with no likes
        assertEquals(3, local.cards.size)
        // Android additions: filter and sort by name (Liked Songs stays).
        assertEquals(listOf(Destination.Liked, Destination.Playlist(id)), b.build("", LibraryTab.Playlists, "zet").cards.map { it.destination })
        assertEquals(listOf("", "alpha", "Zeta"), b.build("", LibraryTab.Playlists, sort = LibrarySort.Name).cards.map { it.title })
    }

    @Test
    fun sidebarRowsAndFolderState() {
        val (acc, lib, b) = setup(loggedIn = true)
        var rows = b.sidebarRows(true, snap, expanded.value)
        assertEquals(listOf("Liked", "P1", "Chill", "Empty"), rows.map { it.title })
        assertTrue(rows[0].liked && !rows[0].editable)
        assertTrue(rows[1].editable && rows[1].owned)
        b.setFolderOpen("f1", true)
        b.setFolderOpen("f1", true)
        assertEquals(listOf("f1"), expanded.value)
        rows = b.sidebarRows(true, snap, expanded.value)
        assertEquals(listOf("Liked", "P1", "Chill", "P2", "", "Empty"), rows.map { it.title })
        assertEquals(1, rows[3].depth)
        assertEquals(-1, rows[3].count) // unknown count: no "0"
        assertTrue(rows[2].folder && rows[2].expanded)
        b.setFolderOpen("f2", true)
        rows = b.sidebarRows(true, snap, expanded.value)
        assertEquals(2, rows.first { it.title == "P3" }.depth)
        b.setFolderOpen("f1", false)
        assertEquals(listOf("f2"), expanded.value)

        acc.logout()
        lib.createPlaylist("Local")
        rows = b.sidebarRows(false, snap, expanded.value)
        assertEquals(listOf(Destination.Liked, Destination.Playlist(lib.playlists[0].id)), rows.map { it.destination })
        assertTrue(rows[1].local)
        assertEquals("folder:x", LibraryBrowser.folderRoute("x"))
        assertEquals("x", LibraryBrowser.folderOf("folder:x"))
        assertEquals("", LibraryBrowser.folderOf("x"))
    }
}
