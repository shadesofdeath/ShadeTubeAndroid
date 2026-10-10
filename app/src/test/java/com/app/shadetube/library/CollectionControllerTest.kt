package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.Track
import com.app.shadetube.spotify.PlaylistEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CollectionControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Unconfined: the fakes never suspend, so every load completes inside the call. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class FakeEnhancer : CollectionEnhancer {
        val on = HashSet<String>()
        var recs: List<Track> = emptyList()
        val hidden = HashSet<String>()
        val bus = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        var asked = 0
        override val changes: Flow<Unit> = bus
        override fun isEnhanced(key: String) = key in on
        override fun setEnhanced(key: String, on: Boolean) {
            if (on) this.on.add(key) else this.on.remove(key)
            bus.tryEmit(Unit)
        }
        override suspend fun recommend(key: String, own: List<Track>): List<Track> {
            asked++
            return recs
        }
        override fun prune(key: String, own: List<Track>, recs: List<Track>) =
            recs.filter { r -> own.none { it.id == r.id } && r.id !in hidden }
        /** A recommendation after every two own songs. */
        override fun interleave(key: String, own: List<Track>, recs: List<Track>): List<Track> {
            val out = ArrayList<Track>()
            var r = 0
            own.forEachIndexed { i, t ->
                out.add(t)
                if (i % 2 == 1 && r < recs.size) out.add(recs[r++])
            }
            return out
        }
        override fun add(key: String, track: Track) {}
        override fun hide(key: String, track: Track) {
            hidden.add(track.id)
            bus.tryEmit(Unit)
        }
    }

    private lateinit var acc: FakeAccount
    private lateinit var lib: UserLibrary
    private val player = FakePlayer()
    private val enhancer = FakeEnhancer()

    private fun deps(loggedIn: Boolean): CollectionDeps {
        acc = FakeAccount(loggedIn)
        lib = UserLibrary(acc, { File(tmp.root, "library.json") }, scope, saveDelayMs = 60_000)
        return CollectionDeps(acc, lib, CatalogSource(acc, FakeOpenCatalog()), player, enhancer).apply {
            labels = CollectionLabels("Beğenilen Şarkılar", "Çalma listesi")
        }
    }

    private fun rows(n: Int, prefix: String = "spotify:track:") = (0 until n).map { track("$prefix$it", uid = "u$it", ms = 1000) }

    @Test
    fun spotifyPlaylistPagesProgressively() {
        val d = deps(loggedIn = true)
        val uri = "spotify:playlist:p"
        acc.playlists[uri] = rows(250)
        acc.playlistMeta[uri] = Playlist(id = uri, name = "", description = "desc", editable = true, owned = false, ownerName = "Bob")
        val c = CollectionController(CollectionKind.Playlist, uri, d, scope)
        assertTrue(c.spotifyMode)
        c.start()
        var s = c.state.value
        assertEquals(CollectionStatus.Ready, s.status)
        assertEquals("Çalma listesi", s.header!!.title) // no name from Spotify
        assertEquals(OwnerText.Text("Bob"), s.header!!.owner)
        assertEquals(HeaderLabel.SpotifyPlaylist, s.header!!.label)
        assertEquals(250, s.header!!.total)
        assertEquals(100, s.rows.size)
        assertEquals(8, s.loadingRows)
        assertTrue(s.editable && !s.owned)
        assertEquals(uri, s.removablePlaylistId)
        assertEquals(100_000L, s.totalMs)
        c.loadMore()
        c.loadMore()
        c.loadMore() // nothing left
        s = c.state.value
        assertEquals(250, s.rows.size)
        assertEquals(0, s.loadingRows)
        assertEquals(listOf(0, 100, 200), acc.pageRequests.map { it.second })

        // Removed rows are dropped in place; the count follows.
        c.onEdit(PlaylistEdit(PlaylistEdit.Kind.Removed, uri, removedUids = listOf("u0", "u1", "zz"), count = 3))
        s = c.state.value
        assertEquals(248, s.rows.size)
        assertEquals(248, s.header!!.total)
        c.onEdit(PlaylistEdit(PlaylistEdit.Kind.Renamed, uri, name = "New"))
        assertEquals("New", c.state.value.header!!.title)
        c.onEdit(PlaylistEdit(PlaylistEdit.Kind.Renamed, "spotify:playlist:other", name = "X"))
        assertEquals("New", c.state.value.header!!.title)
        // Additions reload the first page.
        acc.pageRequests.clear()
        c.onEdit(PlaylistEdit(PlaylistEdit.Kind.Added, uri, count = 1))
        assertEquals(listOf(uri to 0), acc.pageRequests)

        val m = c.menu()
        assertEquals(DeleteKind.RemoveFromLibrary, m.deleteKind)
        assertFalse(m.canRename)
        assertEquals("https://open.spotify.com/playlist/p", m.copyLink)
        assertEquals(uri, m.radioSeed) // logged in, a base62 playlist id: Spotify has a radio for it
    }

    @Test
    fun likedSongsInSpotifyMode() {
        val d = deps(loggedIn = true)
        acc.playlists["spotify:collection:tracks"] = rows(3)
        val c = CollectionController(CollectionKind.Liked, "", d, scope)
        c.start()
        val s = c.state.value
        assertEquals(HeaderLabel.SpotifyLiked, s.header!!.label)
        assertEquals("Beğenilen Şarkılar", s.header!!.title)
        assertEquals(OwnerText.Text("Me"), s.header!!.owner)
        assertFalse(s.editable)
        assertEquals("", s.removablePlaylistId)
        assertEquals("liked", c.contextUri)
        val m = c.menu()
        assertEquals("spotify:collection:tracks", m.offline.id)
        assertEquals("", m.radioSeed)
        assertNull(m.deleteKind)
    }

    @Test
    fun emptyEditablePlaylistAndEpisodeOnlyPages() {
        val d = deps(loggedIn = true)
        val uri = "spotify:playlist:e"
        acc.playlists[uri] = emptyList()
        acc.playlistMeta[uri] = Playlist(id = uri, name = "E", editable = true, owned = true)
        val c = CollectionController(CollectionKind.Playlist, uri, d, scope)
        c.start()
        assertEquals(CollectionEmpty.EmptyPlaylist, c.state.value.empty)
        assertEquals(DeleteKind.DeleteSpotify, c.menu().deleteKind)
        assertTrue(c.menu().canRename)
    }

    @Test
    fun loadErrorAndRetry() {
        val d = deps(loggedIn = true)
        val c = CollectionController(CollectionKind.Playlist, "spotify:playlist:missing", d, scope)
        c.start()
        assertTrue(c.state.value.status is CollectionStatus.Error)
        acc.playlists["spotify:playlist:missing"] = rows(1)
        c.retry()
        assertEquals(CollectionStatus.Ready, c.state.value.status)
        assertEquals(1, c.state.value.rows.size)
    }

    @Test
    fun albumWithAutoplayAndHeart() {
        val d = deps(loggedIn = true)
        val id = "spotify:album:4aawyAB9vmqN3uQ7FjRGTy"
        acc.albums[id] = Album(
            id = id, name = "Al", primaryType = "Single", secondaryTypes = listOf("Live"), firstReleaseDate = "1997-01-01",
            label = "Lbl", artists = listOf(ArtistRef("spotify:artist:a", "A"), ArtistRef("", "B")), tracks = rows(2),
        )
        val c = CollectionController(CollectionKind.Album, "$id#play", d, scope)
        c.start()
        val s = c.state.value
        assertEquals(HeaderLabel.Album(AlbumType.Single, listOf("Live"), "1997", "Lbl"), s.header!!.label)
        assertEquals(OwnerText.Text("A, B"), s.header!!.owner)
        assertEquals("spotify:artist:a", s.header!!.ownerArtistId)
        // "#play": started from the top as the album.
        assertEquals("album:$id", player.contextUri)
        assertEquals(-1, player.lastContext!!.second)
        assertEquals("Al", player.lastName)
        // Play button again: pause toggle.
        c.playFrom(-1, false)
        assertEquals(1, player.toggles)
        player.isPlaying = false
        player.isPaused = true
        c.playFrom(-1, false)
        assertEquals(1, player.plays)
        c.playFrom(1, true)
        assertTrue(player.shuffleOn)
        assertEquals(1, player.lastContext!!.second)

        assertFalse(s.savedAlbum)
        assertEquals(SaveAlbumResult.Saved, c.toggleSavedAlbum())
        assertTrue(c.state.value.savedAlbum)
        val m = c.menu()
        assertEquals(id, m.radioSeed)
        assertEquals("spotify:artist:a", m.artistId)
        assertTrue(m.copyLinkIsSpotify)
        assertNull(m.deleteKind)
    }

    @Test
    fun musicBrainzAlbumWhileLoggedIn() {
        val d = deps(loggedIn = true)
        val c = CollectionController(CollectionKind.Album, "rg-1", d, scope)
        assertFalse(c.spotifyMode)
        c.start()
        assertEquals("MB album rg-1", c.state.value.header!!.title)
        assertEquals("https://musicbrainz.org/release-group/rg-1", c.menu().copyLink)
        assertEquals("", c.menu().radioSeed)
    }

    @Test
    fun localLikedFollowsTheLibrary() {
        val d = deps(loggedIn = false)
        val c = CollectionController(CollectionKind.Liked, "", d, scope)
        c.start()
        var s = c.state.value
        assertEquals(CollectionEmpty.NoLikedSongs, s.empty)
        assertEquals(HeaderLabel.LocalLiked, s.header!!.label)
        assertEquals(OwnerText.You, s.header!!.owner)
        lib.setLiked(track("a", name = "Zeta", ms = 2000), true)
        lib.setLiked(track("b", name = "Alpha", ms = 1000), true)
        s = c.state.value
        assertNull(s.empty)
        assertEquals(listOf("b", "a"), s.rows.map { it.id })
        assertEquals(2, s.header!!.total)
        assertEquals(3000L, s.totalMs)
        c.setSort(TrackSort.Title, false)
        assertEquals(listOf("b", "a"), c.state.value.displayed.map { it.id })
        c.headerSortClick(TrackSort.Title)
        assertEquals(listOf("a", "b"), c.state.value.displayed.map { it.id })
        c.setFilter("zet")
        assertEquals(listOf("a"), c.state.value.displayed.map { it.id })
        c.playFrom(0, false)
        assertEquals(listOf("a"), player.lastContext!!.first.map { it.id }) // what is shown is what plays
        assertEquals("liked", player.contextUri)
        assertEquals("liked", c.menu().offline.id)
    }

    @Test
    fun localPlaylistNotFoundAndEditable() {
        val d = deps(loggedIn = false)
        val missing = CollectionController(CollectionKind.Playlist, "local:nope", d, scope)
        missing.start()
        assertEquals(CollectionStatus.NotFound, missing.state.value.status)

        val id = lib.createPlaylist("Mine")
        lib.addToPlaylist(id, listOf(track("x")))
        val c = CollectionController(CollectionKind.Playlist, id, d, scope)
        c.start()
        val s = c.state.value
        assertEquals("Mine", s.header!!.title)
        assertTrue(s.header!!.label is HeaderLabel.LocalPlaylist)
        assertEquals(id, s.removablePlaylistId)
        assertEquals(DeleteKind.DeleteLocal, c.menu().deleteKind)
        assertTrue(c.menu().canRename)
        lib.renamePlaylist(id, "Renamed")
        assertEquals("Renamed", c.state.value.header!!.title)
        lib.deletePlaylist(id)
        assertEquals(CollectionStatus.NotFound, c.state.value.status)
    }

    @Test
    fun enhanceInterleavesRecommendations() {
        val d = deps(loggedIn = false)
        val id = lib.createPlaylist("Mine")
        lib.addToPlaylist(id, (1..4).map { track("own$it") })
        enhancer.recs = listOf(track("rec1").copy(recommended = true), track("own1").copy(recommended = true),
            track("rec2").copy(recommended = true))
        val c = CollectionController(CollectionKind.Playlist, id, d, scope)
        c.start()
        assertFalse(c.state.value.enhanced)
        c.toggleEnhanced()
        var s = c.state.value
        assertTrue(s.enhanced)
        // The list's own song is never recommended; recommendations don't count in the total.
        assertEquals(listOf("own1", "own2", "rec1", "own3", "own4", "rec2"), s.rows.map { it.id })
        assertEquals(4 * 180_000L, s.totalMs)
        assertEquals(1, enhancer.asked)
        c.hideRecommended(s.rows[2])
        assertEquals(listOf("own1", "own2", "rec2", "own3", "own4"), c.state.value.rows.map { it.id })
        c.toggleEnhanced()
        s = c.state.value
        assertFalse(s.enhanced)
        assertEquals((1..4).map { "own$it" }, s.rows.map { it.id })
        c.toggleEnhanced() // cached recommendations: not asked again
        assertEquals(1, enhancer.asked)
    }

    @Test
    fun enhanceWithNothingToRecommendSaysSo() {
        val d = deps(loggedIn = false)
        lib.setLiked(track("a"), true)
        enhancer.recs = emptyList()
        val c = CollectionController(CollectionKind.Liked, "", d, scope)
        c.start()
        c.toggleEnhanced()
        assertTrue(c.state.value.noRecommendationsNotice)
        c.consumeNotice()
        assertFalse(c.state.value.noRecommendationsNotice)
        assertTrue("liked" in enhancer.on)
    }
}
