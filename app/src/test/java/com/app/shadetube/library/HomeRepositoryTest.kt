package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.CardItem
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.Shelf
import com.app.shadetube.spotify.ApiError
import com.app.shadetube.spotify.LibrarySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HomeRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private var clock = 1_000_000L

    private fun setup(loggedIn: Boolean): Triple<FakeAccount, UserLibrary, FakeOpenCatalog> {
        val acc = FakeAccount(loggedIn)
        val lib = UserLibrary(acc, { File(tmp.root, "library.json") }, scope, saveDelayMs = 60_000)
        return Triple(acc, lib, FakeOpenCatalog())
    }

    private fun repo(acc: FakeAccount, lib: UserLibrary, mb: FakeOpenCatalog) =
        HomeRepository(acc, lib, mb, ShelfCache(acc, scope, { clock }, Dispatchers.Unconfined)).apply {
            ownSectionTitles = listOf("Çalma listelerin", "Kaydettiğin albümler", "Takip ettiğin sanatçılar", "Son çalınanlar")
        }

    private val shelves = listOf(
        Shelf("ÇALMA LİSTELERİN", "", listOf(CardItem(CardItem.Kind.Playlist, "spotify:playlist:x"))),
        Shelf(
            "Made For Me", "",
            listOf(
                CardItem(CardItem.Kind.Playlist, "spotify:collection:tracks", "Liked"),
                CardItem(CardItem.Kind.Album, "spotify:album:1", "Al"),
                CardItem(CardItem.Kind.Artist, "spotify:artist:1", "Ar"),
                CardItem(CardItem.Kind.Track, "spotify:track:1", "Tr", track = track("spotify:track:1", albumId = "spotify:album:2")),
                CardItem(CardItem.Kind.Track, "spotify:track:2", "No album", track = track("spotify:track:2")),
            ),
        ),
    )

    @Test
    fun spotifyHomeOrderAndDuplicateShelves() {
        val (acc, lib, mb) = setup(loggedIn = true)
        val r = repo(acc, lib, mb)
        lib.recordPlay(track("spotify:track:9", albumId = "spotify:album:9", album = "Nine"))
        assertEquals(HomeFeed.Loading, r.buildSpotify(LibrarySnapshot(loaded = false), null))

        val partial = r.buildSpotify(LibrarySnapshot(loaded = false), shelves) as HomeFeed.Spotify
        assertNull(partial.playlistCount)
        assertEquals(1, partial.sections.size) // the duplicate "Çalma listelerin" shelf is left out

        val snap = LibrarySnapshot(
            playlists = listOf(
                Playlist(id = "spotify:collection:tracks", name = "Liked", totalTracks = 3),
                Playlist(id = "spotify:playlist:a", name = "A", countKnown = false, ownerName = "Bob"),
                Playlist(id = "spotify:playlist:b", name = "B", countKnown = false),
            ),
            albums = listOf(Album(id = "spotify:album:s", name = "S", firstReleaseDate = "2020-01-01", artists = listOf(ArtistRef("x", "X")))),
            artists = listOf(Artist(id = "spotify:artist:f")),
            loaded = true,
        )
        val feed = r.buildSpotify(snap, shelves) as HomeFeed.Spotify
        assertEquals(3, feed.playlistCount)
        assertEquals(1, feed.albumCount)
        val kinds = feed.sections.map { it::class.simpleName }
        assertEquals(listOf("SpotifyShelf", "RecentlyPlayed", "SmartLists", "YourPlaylists", "SavedAlbums", "FollowedArtists"), kinds)
        val shelf = feed.sections[0] as HomeSection.SpotifyShelf
        assertEquals(4, shelf.cards.size) // the track without an album is skipped
        assertEquals(Destination.Liked, shelf.cards[0].destination)
        assertTrue(shelf.cards[0].playable)
        assertEquals(CardShape.Circle, shelf.cards[2].shape)
        assertEquals(Destination.Album("spotify:album:2"), shelf.cards[3].destination)
        val pls = (feed.sections[3] as HomeSection.YourPlaylists).cards
        assertEquals(CardSubtitle.Songs(3), pls[0].subtitle)
        assertEquals(CardSubtitle.Text("Bob"), pls[1].subtitle)
        assertEquals(CardSubtitle.PlaylistWord, pls[2].subtitle)
        assertEquals(CardSubtitle.ArtistYear("X", "2020"), (feed.sections[4] as HomeSection.SavedAlbums).cards[0].subtitle)
    }

    @Test
    fun openHomeSections() = runBlocking {
        val (acc, lib, mb) = setup(loggedIn = false)
        val r = repo(acc, lib, mb)
        lib.recordPlay(track("r1", artist = "Sezen", artistId = "sezen", albumId = "al1", album = "Al1"))
        lib.recordPlay(track("r2", artist = "Spot", artistId = "spotify:artist:x", albumId = "al2"))
        lib.recordPlay(track("r3", artist = "Sezen", artistId = "sezen", albumId = "al1"))
        lib.setLiked(track("l"), true)
        mb.trendingAlbums = { (1..7).map { Album(id = "t$it", primaryType = "Album") } }
        mb.trendingArtists = { listOf(Artist(id = "pop")) }
        mb.fresh = { listOf(Album(id = "f1", primaryType = "Single", firstReleaseDate = "2026-09-30"), Album(id = "f2", primaryType = "EP", firstReleaseDate = "2026")) }
        mb.similar = { listOf(Artist(id = "sim-$it"), Artist(id = "sezen")) }
        val d = r.loadOpenData()
        // Spotify ids are never sent to ListenBrainz; the seed itself is not "similar".
        assertEquals(listOf("sezen"), mb.similarAsked)
        assertEquals(listOf("sim-sezen"), d.forYou.map { it.id })
        assertEquals("Sezen", d.forYouSeed)
        val feed = r.buildOpen(d)
        assertEquals(0, feed.playlistCount)
        assertEquals(1, feed.likedCount)
        val kinds = feed.sections.map { it::class.simpleName }
        assertEquals(listOf("RecentlyPlayed", "SmartLists", "PopularAndFresh", "YourPlaylists", "ForYou", "PopularArtists", "MoreTrendingAlbums"), kinds)
        val recent = feed.sections[0] as HomeSection.RecentlyPlayed
        assertEquals(listOf(Destination.Album("al1"), Destination.Album("al2")), recent.cards.map { it.destination })
        assertEquals(CardSubtitle.Text("Sezen"), recent.cards[0].subtitle)
        assertEquals(SectionLink.Library, recent.link)
        val pf = feed.sections[2] as HomeSection.PopularAndFresh
        assertEquals(5, pf.trending.size)
        assertEquals(FreshRelease(pf.fresh[0].album, AlbumType.Single, "2026-09-30"), pf.fresh[0])
        assertEquals("", pf.fresh[1].releaseDate)
        val mine = feed.sections[3] as HomeSection.YourPlaylists
        assertEquals(Destination.Liked, mine.cards.single().destination)
        assertEquals(SectionLink.SeeAll, mine.link)
        assertEquals(2, (feed.sections[6] as HomeSection.MoreTrendingAlbums).cards.size)
    }

    @Test
    fun openHomeFailsOnlyWhenNothingCameBack() = runBlocking {
        val (acc, lib, mb) = setup(loggedIn = false)
        val r = repo(acc, lib, mb)
        mb.trendingAlbums = { throw java.io.IOException("offline") }
        mb.trendingArtists = { throw java.io.IOException("offline 2") }
        mb.fresh = { throw java.io.IOException("offline 3") }
        val d = r.loadOpenData()
        assertTrue(d.failed)
        assertEquals("offline", d.firstError)
        val flow = r.feed().take(2).toList()
        assertEquals(HomeFeed.Loading, flow[0])
        assertEquals(HomeFeed.Error("offline"), flow[1])

        mb.fresh = { listOf(Album(id = "f")) }
        val ok = r.loadOpenData()
        assertEquals(2, ok.failures)
        assertTrue(!ok.failed)
        assertTrue(r.feed().take(2).toList()[1] is HomeFeed.Open)
    }

    @Test
    fun shelfCacheFetchesOncePerUserAndBacksOff() {
        val (acc, lib, mb) = setup(loggedIn = true)
        val cache = repo(acc, lib, mb).shelfCache
        acc.homeResult = { shelves }
        cache.ensure("u1")
        assertEquals(1, acc.homeCalls)
        assertEquals(2, cache.shelvesFor("u1")!!.size)
        assertNull(cache.shelvesFor("u2"))
        assertEquals(1L, cache.revision.value)
        cache.ensure("u1") // fresh
        assertEquals(1, acc.homeCalls)
        clock += ShelfCache.FRESH_FOR_MS
        cache.ensure("u1") // stale: refreshed
        assertEquals(2, acc.homeCalls)

        // Another user: the old feed is forgotten; a 429 pauses retries for 10 minutes.
        acc.homeResult = { throw ApiError(429, "rate limited") }
        cache.ensure("u2")
        assertEquals(3, acc.homeCalls)
        assertNull(cache.shelvesFor("u1"))
        clock += ShelfCache.RETRY_MS
        cache.ensure("u2")
        assertEquals(3, acc.homeCalls)
        clock += ShelfCache.RETRY_429_MS - ShelfCache.RETRY_MS
        acc.homeResult = { throw ApiError(412, "hash") }
        cache.ensure("u2")
        assertEquals(4, acc.homeCalls)
        clock += ShelfCache.RETRY_MS - 1
        cache.ensure("u2")
        assertEquals(4, acc.homeCalls)
        clock += 1
        cache.ensure("u2")
        assertEquals(5, acc.homeCalls)

        // Logged out: nothing is fetched; reset forgets the back-off.
        acc.logout()
        cache.reset()
        cache.ensure("u2")
        assertEquals(5, acc.homeCalls)
    }

    @Test
    fun loggedInFeedShowsTheLibraryThenTheShelves() = runBlocking {
        val (acc, lib, mb) = setup(loggedIn = true)
        acc.homeResult = { shelves }
        acc.library.value = LibrarySnapshot(playlists = listOf(Playlist(id = "spotify:playlist:a")), loaded = true)
        val r = repo(acc, lib, mb)
        val first = r.feed().take(1).toList()[0] as HomeFeed.Spotify
        // The fetch ran synchronously here (Unconfined), so the shelf is already in.
        assertTrue(first.sections.any { it is HomeSection.SpotifyShelf } || r.shelfCache.shelvesFor("spotify:user:me") != null)
        assertEquals(1, first.playlistCount)
    }
}
