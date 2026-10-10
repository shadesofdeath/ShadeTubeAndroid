package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistPage
import com.app.shadetube.catalog.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FormattingAndViewTest {
    @Test
    fun totalDurationSplitsHours() {
        assertEquals(TotalDuration(0, 0), totalDuration(59_999))
        assertEquals(TotalDuration(0, 59), totalDuration(59 * 60_000L + 59_000))
        assertEquals(TotalDuration(1, 0), totalDuration(60 * 60_000L))
        assertEquals(TotalDuration(3, 12), totalDuration(192 * 60_000L))
        assertTrue(totalDuration(192 * 60_000L).hasHours)
    }

    @Test
    fun relativeTimeBuckets() {
        val now = 100_000_000L
        assertEquals(RelativeTime.None, relativeTime(0, now))
        assertEquals(RelativeTime.JustNow, relativeTime(now - 3599, now))
        assertEquals(RelativeTime.JustNow, relativeTime(now + 500, now)) // clock skew: never negative
        assertEquals(RelativeTime.HoursAgo(1), relativeTime(now - 3600, now))
        assertEquals(RelativeTime.HoursAgo(23), relativeTime(now - 86_399, now))
        assertEquals(RelativeTime.DaysAgo(3), relativeTime(now - 3 * 86_400, now))
        assertEquals(RelativeTime.WeeksAgo(1), relativeTime(now - 7 * 86_400, now))
        assertEquals(RelativeTime.WeeksAgo(4), relativeTime(now - 29 * 86_400, now))
        assertEquals(RelativeTime.MonthsAgo(1), relativeTime(now - 30 * 86_400, now))
        assertEquals(RelativeTime.MonthsAgo(12), relativeTime(now - 364 * 86_400, now))
        assertEquals(RelativeTime.Date(now - 365 * 86_400), relativeTime(now - 365 * 86_400, now))
    }

    @Test
    fun compactCountUsesTheLocaleSeparator() {
        val tr = Locale.forLanguageTag("tr-TR")
        assertEquals(CompactCount("999", CompactCount.Scale.None), compactCount(999, tr))
        assertEquals(CompactCount("12,4", CompactCount.Scale.Thousand), compactCount(12_400, tr))
        assertEquals(CompactCount("1.2", CompactCount.Scale.Million), compactCount(1_234_567, Locale.US))
        assertEquals(CompactCount("1000.0", CompactCount.Scale.Thousand), compactCount(999_999, Locale.US))
        assertEquals("1.234.567", thousands(1_234_567, tr))
    }

    @Test
    fun greetingByHour() {
        assertEquals(Greeting.Night, greeting(4))
        assertEquals(Greeting.Morning, greeting(5))
        assertEquals(Greeting.Morning, greeting(11))
        assertEquals(Greeting.Afternoon, greeting(12))
        assertEquals(Greeting.Evening, greeting(18))
        assertEquals(Greeting.Evening, greeting(22))
        assertEquals(Greeting.Night, greeting(23))
    }

    @Test
    fun radioSeedAndWebLinks() {
        assertEquals("spotify:album:4aawyAB9vmqN3uQ7FjRGTy", radioSeed("spotify:album:4aawyAB9vmqN3uQ7FjRGTy"))
        assertEquals("", radioSeed("spotify:collection:tracks"))
        assertEquals("", radioSeed("spotify:playlist:"))
        assertEquals("", radioSeed("spotify:track:bad-id"))
        assertEquals("", radioSeed("local:123"))
        assertEquals("https://open.spotify.com/artist/abc", WebLinks.artist("spotify:artist:abc"))
        assertEquals("https://musicbrainz.org/artist/m-1", WebLinks.artist("m-1"))
        assertEquals("https://musicbrainz.org/release-group/rg", WebLinks.album("rg"))
        assertEquals("https://open.spotify.com/playlist/p", WebLinks.playlist("spotify:playlist:p"))
        assertEquals("", WebLinks.recording("spotify:track:x"))
    }

    @Test
    fun collectionRouteParsesAutoplay() {
        assertEquals(CollectionRoute("spotify:album:x", true), CollectionRoute.parse("spotify:album:x#play"))
        assertEquals(CollectionRoute("local:1", false), CollectionRoute.parse("local:1"))
        assertEquals(CollectionRoute("", true), CollectionRoute.parse("#play"))
    }

    private val rows = listOf(
        track("1", name = "Şımarık", artist = "Tarkan", album = "Ölürüm Sana", ms = 200_000, added = 30),
        track("2", name = "istanbul", artist = "Sezen Aksu", album = "Gülümse", ms = 100_000, added = 10),
        track("3", name = "Ağlama", artist = "Barış Manço", album = "Ben Bilirim", ms = 300_000, added = 20),
        track("4", name = "Ağlama", artist = "Another", album = "Z", ms = 150_000, added = 20),
    )

    @Test
    fun filterIsFolded() {
        assertEquals(listOf("1"), TrackListView.apply(rows, "SIMARIK", TrackSort.Custom, false).map { it.id })
        assertEquals(listOf("2"), TrackListView.apply(rows, "İSTANBUL", TrackSort.Custom, false).map { it.id })
        assertEquals(listOf("3"), TrackListView.apply(rows, "baris", TrackSort.Custom, false).map { it.id })
        assertEquals(listOf("2"), TrackListView.apply(rows, "gulumse", TrackSort.Custom, false).map { it.id })
        assertEquals(4, TrackListView.apply(rows, "", TrackSort.Custom, false).size)
    }

    @Test
    fun sortsAreStableAndDescendingReverses() {
        fun ids(k: TrackSort, d: Boolean = false) = TrackListView.apply(rows, "", k, d).map { it.id }
        assertEquals(listOf("1", "2", "3", "4"), ids(TrackSort.Custom))
        assertEquals(listOf("3", "4", "2", "1"), ids(TrackSort.Title)) // agl.. < istanbul < simarik; ties keep order
        assertEquals(listOf("1", "2", "4", "3"), ids(TrackSort.Title, true)) // the whole order reversed (ties too)
        assertEquals(listOf("2", "3", "4", "1"), ids(TrackSort.Added))
        assertEquals(listOf("1", "4", "3", "2"), ids(TrackSort.Added, true))
        assertEquals(listOf("2", "4", "1", "3"), ids(TrackSort.Duration))
        assertEquals(listOf("4", "3", "2", "1"), ids(TrackSort.Artist))
        assertEquals(listOf("3", "2", "1", "4"), ids(TrackSort.Album))
    }

    @Test
    fun headerClickCyclesAndMenusDifferForAlbums() {
        assertEquals(SortOption(TrackSort.Title, false), TrackListView.headerClick(TrackSort.Custom, false, TrackSort.Title))
        assertEquals(SortOption(TrackSort.Title, true), TrackListView.headerClick(TrackSort.Title, false, TrackSort.Title))
        assertEquals(SortOption(TrackSort.Custom, false), TrackListView.headerClick(TrackSort.Title, true, TrackSort.Title))
        assertEquals(SortOption(TrackSort.Duration, false), TrackListView.headerClick(TrackSort.Title, true, TrackSort.Duration))
        val albumKeys = TrackListView.sortOptions(CollectionKind.Album).map { it.key }
        assertEquals(listOf(TrackSort.Custom, TrackSort.Title, TrackSort.Artist, TrackSort.Duration), albumKeys)
        val pl = TrackListView.sortOptions(CollectionKind.Playlist)
        assertEquals(SortOption(TrackSort.Added, true), pl[1])
        assertEquals(TrackSort.Album, pl.last().key)
        assertEquals(750_000L, TrackListView.totalMs(rows + rows[0].copy(id = "r", recommended = true)))
    }

    @Test
    fun sourceRoutesDetailLookupsById() = runBlocking {
        val acc = FakeAccount(loggedIn = true)
        acc.albums["spotify:album:1"] = Album(id = "spotify:album:1", name = "Sp")
        val mb = FakeOpenCatalog()
        val s = CatalogSource(acc, mb)
        assertEquals("Sp", s.album("spotify:album:1").name)
        assertEquals("MB album rg-1", s.album("rg-1").name) // an MBID in a logged-in history still opens
        assertEquals("MB artist", s.artistPage("mb-artist").artist.name)
        assertEquals("Spotify artist", s.artistPage("spotify:artist:1").artist.name)
        s.search("q")
        assertEquals(1, acc.searchCalls)
        assertEquals(SourceLabel.Spotify("Me"), s.sourceLabel())
        acc.profile.value = acc.profile.value.copy(name = "")
        assertEquals(SourceLabel.Spotify("me"), s.sourceLabel())
        acc.logout()
        assertEquals(SourceLabel.OpenCatalog, s.sourceLabel())
        // Logged out a spotify: id can't be read from Spotify: it goes to MusicBrainz (which then fails / misses).
        assertEquals("MB album spotify:album:1", s.album("spotify:album:1").name)
        s.search("q")
        assertEquals(listOf("q"), mb.searches)
    }

    @Test
    fun artistRepositoryPlayAndSections() {
        val player = FakePlayer()
        val acc = FakeAccount(loggedIn = true)
        val repo = ArtistRepository(CatalogSource(acc, FakeOpenCatalog()), UserLibrary(acc, { java.io.File("unused") }), player)
        val page = ArtistPage(
            artist = Artist(id = "spotify:artist:a", name = "A", type = "Group"),
            topTracks = (1..12).map { track("spotify:track:$it") },
            albums = listOf(Album(id = "al", firstReleaseDate = "2001")),
            similar = listOf(Artist(id = "s")),
        )
        val sections = repo.sections(page)
        assertEquals(10, (sections[0] as ArtistSection.Popular).shown.size)
        val rel = sections[1] as ArtistSection.Releases
        assertEquals(ArtistSection.Releases.Kind.Albums, rel.kind)
        assertEquals(CardSubtitle.Year("2001"), rel.cards[0].subtitle)
        assertTrue(sections[2] is ArtistSection.Similar)
        assertTrue(repo.hero(page.artist).canRadio)
        assertTrue(repo.hero(page.artist).group)

        assertEquals(ArtistPlayResult.Played, repo.play(page))
        assertEquals(-1, player.lastContext!!.second)
        assertEquals(12, player.lastContext!!.first.size) // all top tracks, not just the shown 10
        assertEquals("artist:spotify:artist:a", player.contextUri)
        assertEquals(ArtistPlayResult.Toggled, repo.play(page))
        assertEquals(1, player.toggles)
        player.contextUri = ""
        assertEquals(ArtistPlayResult.NoPopularTracks, repo.play(page.copy(topTracks = emptyList<Track>())))
    }
}
