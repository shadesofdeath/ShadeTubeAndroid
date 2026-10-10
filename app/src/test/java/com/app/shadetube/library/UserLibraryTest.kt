package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.core.isObject
import com.app.shadetube.core.arr
import com.app.shadetube.core.get
import com.app.shadetube.core.int
import com.app.shadetube.core.parseJsonOrNull
import com.app.shadetube.core.str
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

class UserLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000_000L

    @After
    fun tearDown() = scope.cancel()

    private fun lib(account: FakeAccount = FakeAccount(), file: File = File(tmp.root, "library.json")) =
        UserLibrary(account, { file }, scope, saveDelayMs = 60_000, nowUnix = { now })

    @Test
    fun localLikesNewestFirstAndDeduped() {
        val l = lib()
        assertEquals(LikeResult.Liked, l.setLiked(track("a"), true))
        now += 10
        assertEquals(LikeResult.Liked, l.setLiked(track("b"), true))
        assertEquals(LikeResult.Unchanged, l.setLiked(track("b"), true))
        assertEquals(listOf("b", "a"), l.liked.map { it.id })
        assertEquals(1_000_010L, l.liked[0].addedAt)
        assertTrue(l.isLiked("a"))
        assertEquals(LikeResult.Unliked, l.toggleLiked(track("a")))
        assertFalse(l.isLiked("a"))
        assertEquals(listOf("b"), l.liked.map { it.id })
    }

    @Test
    fun podcastsAndStationsCannotBeLiked() {
        val l = lib()
        assertFalse(l.canLike("podcast:feed#ep"))
        assertFalse(l.canLike("radio:1234"))
        assertFalse(l.canLike(""))
        assertTrue(l.canLike("local:abc"))
        assertTrue(l.canLike("0f2c3e4a-mbid"))
        assertEquals(LikeResult.Unchanged, l.setLiked(track("podcast:x"), true))
        assertTrue(l.liked.isEmpty())
    }

    @Test
    fun loggedInHeartsGoToSpotifyOnly() {
        val acc = FakeAccount(loggedIn = true)
        val l = lib(acc)
        assertTrue(l.canLike("spotify:track:1"))
        assertFalse(l.canLike("local:abc"))
        assertFalse(l.canLike("some-mbid"))
        val rev = l.revision.value
        assertEquals(LikeResult.Liked, l.setLiked(track("spotify:track:1"), true))
        assertEquals(listOf("spotify:track:1" to true), acc.likedWrites)
        assertTrue(l.isLiked("spotify:track:1"))
        assertTrue(l.liked.isEmpty()) // never mirrored into the local list
        assertTrue(l.revision.value > rev)
        assertEquals(LikeResult.OnlySpotifyTracks, l.setLiked(track("local:abc"), true))
        assertEquals(1, acc.likedWrites.size)
        // Logged out again: the local hearts apply.
        acc.logout()
        assertFalse(l.isLiked("spotify:track:1"))
    }

    @Test
    fun likeAllSkipsDuplicatesAndKeepsDropOrder() {
        val l = lib()
        l.setLiked(track("x"), true)
        val n = l.likeAll(listOf(track("a"), track("x"), track("b"), track("a"), track("podcast:p")))
        assertEquals(2, n)
        assertEquals(listOf("a", "b", "x"), l.liked.map { it.id })
        assertEquals(0, l.likeAll(listOf(track("a"))))

        val acc = FakeAccount(loggedIn = true)
        val sp = lib(acc, File(tmp.root, "other.json"))
        assertEquals(1, sp.likeAll(listOf(track("spotify:track:1"), track("local:2"), track("spotify:track:1"))))
        assertEquals(listOf("spotify:track:1" to true), acc.likedWrites)
    }

    @Test
    fun savedAlbumsAndFollowsFollowTheSource() {
        val l = lib()
        val album = Album(id = "rg-1", name = "Album", tracks = listOf(track("t")))
        assertEquals(SaveAlbumResult.Saved, l.setSavedAlbum(album, true))
        assertTrue(l.isSavedAlbum("rg-1"))
        assertTrue(l.albums[0].tracks.isEmpty()) // tracklists come from the catalog
        assertEquals(SaveAlbumResult.Unchanged, l.setSavedAlbum(album, true))
        assertEquals(FollowResult.Followed, l.setFollow(Artist(id = "ar-1", tags = listOf("rock")), true))
        assertTrue(l.artists[0].tags.isEmpty())
        assertEquals(FollowResult.Unfollowed, l.setFollow(Artist(id = "ar-1"), false))

        val acc = FakeAccount(loggedIn = true)
        val sp = lib(acc, File(tmp.root, "sp.json"))
        assertEquals(SaveAlbumResult.OnlySpotifyAlbums, sp.setSavedAlbum(album, true))
        assertEquals(SaveAlbumResult.Saved, sp.setSavedAlbum(Album(id = "spotify:album:1"), true))
        assertEquals(listOf("spotify:album:1" to true), acc.albumWrites)
        assertTrue(sp.isSavedAlbum("spotify:album:1"))
        assertEquals(FollowResult.OnlySpotifyArtists, sp.setFollow(Artist(id = "mbid"), true))
        assertEquals(FollowResult.Followed, sp.setFollow(Artist(id = "spotify:artist:9"), true))
        assertTrue(sp.isFollowing("spotify:artist:9"))
        assertTrue(sp.albums.isEmpty() && sp.artists.isEmpty())
    }

    @Test
    fun localPlaylistCrud() {
        val l = lib()
        l.labels = LibraryLabels(newPlaylist = "Yeni çalma listesi")
        val id = l.createPlaylist("")
        assertTrue(id.matches(Regex("local:[0-9a-f]{16}")))
        assertEquals("Yeni çalma listesi", l.playlist(id)!!.name)
        val cover = listOf(Image("u", 300, 300))
        assertEquals(2, l.addToPlaylist(id, listOf(track("a"), track("b", images = cover), track("a"), track(""))))
        assertEquals(0, l.addToPlaylist(id, listOf(track("b"))))
        assertEquals(0, l.addToPlaylist("local:missing", listOf(track("c"))))
        val p = l.playlist(id)!!
        assertEquals(2, p.totalTracks)
        assertEquals(cover, p.images)
        l.renamePlaylist(id, "Yol")
        assertEquals("Yol", l.playlist(id)!!.name)
        l.removeFromPlaylist(id, "b")
        assertEquals(listOf("a"), l.playlistTracks(id)!!.map { it.id })
        assertTrue(l.playlist(id)!!.images.isEmpty())
        val second = l.createPlaylist("Second")
        assertEquals(listOf(second, id), l.playlists.map { it.id }) // newest first
        l.deletePlaylist(id)
        assertNull(l.playlist(id))
        assertNull(l.playlistTracks(id))
    }

    @Test
    fun historyIsCappedAndRepeatOneIsOneEntry() {
        val l = lib()
        l.recordPlay(track("x", name = "")) // nameless: ignored
        assertTrue(l.history.value.isEmpty())
        val rev = l.revision.value
        l.recordPlay(track("a"))
        now += 5
        l.recordPlay(track("a"))
        assertEquals(1, l.history.value.size)
        assertEquals(now, l.history.value[0].playedAt)
        assertEquals(rev, l.revision.value) // a play doesn't rebuild pages
        for (i in 0 until 400) l.recordPlay(track("t$i"))
        assertEquals(UserLibrary.HISTORY_CAP, l.history.value.size)
        assertEquals("t399", l.history.value[0].track.id)
    }

    @Test
    fun topArtistsAndRecentAlbums() {
        val l = lib()
        l.recordPlay(track("1", artist = "X", artistId = "x", album = "Al1", albumId = "al1"))
        l.recordPlay(track("2", artist = "Y", artistId = "y", album = "Al2", albumId = "al2", images = listOf(Image("img"))))
        l.recordPlay(track("3", artist = "Y", artistId = "y", album = "Al1", albumId = "al1"))
        l.recordPlay(track("4", artist = "Nameless", artistId = ""))
        assertEquals(listOf(ArtistRef("y", "Y"), ArtistRef("x", "X")), l.topArtists(5))
        assertEquals(1, l.topArtists(1).size)
        val recent = l.recentAlbums(12)
        assertEquals(listOf("al1", "al2"), recent.map { it.id })
        // No cover on the play: the Cover Art Archive's.
        assertEquals("https://coverartarchive.org/release-group/al1/front-250", recent[0].images[0].url)
        assertEquals("img", recent[1].images[0].url)
        assertEquals(1, l.recentAlbums(1).size)
    }

    @Test
    fun saveAndLoadRoundTripInTheCppShape() = runBlocking {
        val file = File(tmp.root, "library.json")
        val l = lib(file = file)
        l.setLiked(track("a", name = "Song", album = "Al", albumId = "al"), true)
        l.setSavedAlbum(Album(id = "rg", name = "R", primaryType = "Album", firstReleaseDate = "1997", totalTracks = 9), true)
        l.setFollow(Artist(id = "ar", name = "Ar", country = "TR"), true)
        val pid = l.createPlaylist("P")
        l.addToPlaylist(pid, listOf(track("t1"), track("t2")))
        l.recordPlay(track("h"))
        l.flush()

        val j = parseJsonOrNull(file.readText())!!
        assertEquals(1, j.int("version"))
        assertEquals("Song", j["liked"].arr[0].str("n"))
        assertEquals("al", j["liked"].arr[0]["al"].str("id"))
        assertEquals("rg", j["albums"].arr[0].str("id"))
        assertEquals("TR", j["artists"].arr[0].str("c"))
        val pl = j["playlists"].arr[0]
        assertEquals(pid, pl.str("id"))
        assertEquals("P", pl.str("n"))
        assertEquals(2, pl["tracks"].arr.size)
        assertEquals("h", j["history"].arr[0]["t"].str("id"))

        val again = lib(file = file)
        again.load()
        val s = again.state.value
        assertTrue(s.loaded)
        assertEquals(listOf("a"), s.liked.map { it.id })
        assertEquals("1997", s.albums[0].firstReleaseDate)
        assertEquals(2, again.playlist(pid)!!.totalTracks)
        assertEquals(1, again.history.value.size)
        assertTrue(again.isLiked("a"))
    }

    @Test
    fun loadSkipsDuplicateAndEmptyIdsAndPlaylistsWithoutId() = runBlocking {
        val file = File(tmp.root, "library.json")
        file.writeText(
            """{"version":1,"liked":[{"id":"a","n":"A"},{"id":"a","n":"A2"},{"id":"","n":"none"}],
               |"playlists":[{"n":"no id"},{"id":"local:1","n":"P","c":5,"tracks":[{"id":"t","n":"T"}]}],
               |"history":[{"t":{"id":"h","n":"H"},"at":7}]}""".trimMargin(),
        )
        val l = lib(file = file)
        l.load()
        assertEquals(listOf("A"), l.liked.map { it.name })
        assertEquals(listOf("local:1"), l.playlists.map { it.id })
        assertEquals(5L, l.playlist("local:1")!!.createdAt)
        assertEquals(7L, l.history.value[0].playedAt)
    }

    @Test
    fun corruptFileIsKeptAsBak() = runBlocking {
        val file = File(tmp.root, "library.json")
        file.writeText("[1,2,") // not an object
        val l = lib(file = file)
        l.load()
        assertTrue(l.state.value.loaded)
        assertTrue(l.liked.isEmpty())
        assertEquals("[1,2,", File(tmp.root, "library.json.bak").readText())

        file.writeText("[1,2]") // valid JSON, but not an object: corrupt too
        lib(file = file).load()
        assertEquals("[1,2]", File(tmp.root, "library.json.bak").readText())
    }

    @Test
    fun missingFileStartsEmpty() = runBlocking {
        val l = lib(file = File(tmp.root, "nope.json"))
        l.load()
        assertTrue(l.state.value.loaded)
        assertFalse(File(tmp.root, "nope.json.bak").exists())
        l.flush() // nothing dirty: nothing written
        assertFalse(File(tmp.root, "nope.json").exists())
    }

    @Test
    fun debouncedSaveWritesOnce() {
        val file = File(tmp.root, "library.json")
        val l = UserLibrary(FakeAccount(), { file }, scope, saveDelayMs = 50, nowUnix = { now })
        l.setLiked(track("a"), true)
        l.setLiked(track("b"), true)
        assertFalse(file.exists())
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(50)
        val j = parseJsonOrNull(file.readText())!!
        assertTrue(j.isObject)
        assertEquals(2, j["liked"].arr.size)
    }
}
