package com.app.shadetube.playback

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.catalog.Track
import com.app.shadetube.playback.service.VoiceCatalog
import com.app.shadetube.playback.service.VoiceRequest
import com.app.shadetube.playback.service.VoiceSearch
import com.app.shadetube.playback.service.VoiceSearchPicker
import com.app.shadetube.playback.service.VoiceSearchPicker.Pick
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSearchTest {
    private fun track(id: String, name: String, artist: String, albumId: String = "", playable: Boolean = true) =
        Track(id = id, name = name, artists = listOf(ArtistRef("a:$artist", artist)), album = AlbumRef(albumId, "Album $albumId"), playable = playable)

    private fun req(q: String) = VoiceRequest.of(q) { null }

    @After
    fun tearDown() {
        VoiceSearch.catalog = null
    }

    // ---- request parsing -------------------------------------------------------------------------------------------

    @Test
    fun cleanQueryDropsAppNameAndVerbs() {
        assertEquals("Tarkan", VoiceRequest.cleanQuery("Tarkan on ShadeTube"))
        assertEquals("Tarkan", VoiceRequest.cleanQuery("  play Tarkan  in shade tube "))
        assertEquals("Tarkan", VoiceRequest.cleanQuery("ShadeTube'da Tarkan çal"))
        assertEquals("Şımarık", VoiceRequest.cleanQuery("Şımarık ShadeTube'dan"))
        assertEquals("", VoiceRequest.cleanQuery("   "))
        assertEquals("Shade of blue", VoiceRequest.cleanQuery("Shade of blue")) // not the app name
    }

    @Test
    fun structuredExtrasPickTheSearchText() {
        val extras = mapOf(
            VoiceRequest.EXTRA_FOCUS to "vnd.android.cursor.item/audio",
            VoiceRequest.EXTRA_TITLE to "Şımarık",
            VoiceRequest.EXTRA_ARTIST to "Tarkan",
        )
        val r = VoiceRequest.of("şımarık by tarkan") { extras[it] }
        assertEquals(VoiceRequest.Focus.Song, r.focus)
        assertEquals("Şımarık Tarkan", r.searchText)
        val artist = VoiceRequest.of("tarkan") { if (it == VoiceRequest.EXTRA_FOCUS) "vnd.android.cursor.item/artist" else null }
        assertEquals(VoiceRequest.Focus.Artist, artist.focus)
        assertEquals("tarkan", artist.searchText)
        assertTrue(VoiceRequest.of(null) { null }.isEmpty)
    }

    @Test
    fun normalizeFoldsTurkishAndAccents() {
        assertEquals("simarik", VoiceSearchPicker.normalize("Şımarık"))
        assertEquals("istanbul agliyor", VoiceSearchPicker.normalize("İstanbul Ağlıyor!"))
        assertEquals("beyonce dont stop", VoiceSearchPicker.normalize("Beyoncé – Don't  Stop"))
    }

    // ---- picking ---------------------------------------------------------------------------------------------------------

    @Test
    fun artistNamePlaysTheArtist() {
        val r = SearchResults(
            artists = listOf(Artist(id = "ar1", name = "Tarkan")),
            tracks = listOf(track("t1", "Kuzu Kuzu", "Tarkan"), track("t2", "Şımarık", "Tarkan")),
        )
        assertEquals(Pick.ArtistPick(r.artists[0]), VoiceSearchPicker.pick(req("tarkan"), r))
    }

    @Test
    fun songAndArtistPlaysThatSong() {
        val r = SearchResults(
            artists = listOf(Artist(id = "ar1", name = "Tarkan")),
            tracks = listOf(
                track("t1", "Kuzu Kuzu", "Tarkan"),
                track("t2", "Şımarık - Live", "Tarkan"),
                track("t3", "Şımarık", "Tarkan"),
            ),
        )
        // The studio version wins over the live one (not asked for), though the catalog ranked it lower.
        assertEquals(Pick.Song(2), VoiceSearchPicker.pick(req("simarik tarkan"), r))
        assertEquals(Pick.Song(1), VoiceSearchPicker.pick(req("şımarık live"), r))
    }

    @Test
    fun exactSongTitleByAnotherArtistBeatsAnArtistOfThatName() {
        val r = SearchResults(
            artists = listOf(Artist(id = "x", name = "Hello")),
            tracks = listOf(track("t1", "Hello", "Adele")),
        )
        assertEquals(Pick.Song(0), VoiceSearchPicker.pick(req("hello"), r))
    }

    @Test
    fun exactAlbumNameWithoutSuchSongPlaysTheAlbum() {
        val album = Album(id = "al1", name = "Karma", artists = listOf(ArtistRef("ar1", "Tarkan")))
        val r = SearchResults(albums = listOf(album), tracks = listOf(track("t1", "Kuzu Kuzu", "Tarkan", "al1")))
        assertEquals(Pick.AlbumPick(album), VoiceSearchPicker.pick(req("karma"), r))
        assertEquals(Pick.AlbumPick(album), VoiceSearchPicker.pick(req("tarkan karma"), r))
    }

    @Test
    fun unplayableTracksAreSkippedAndNothingIsNull() {
        val r = SearchResults(tracks = listOf(track("t1", "Song", "A", playable = false), track("t2", "Song", "B")))
        assertEquals(Pick.Song(1), VoiceSearchPicker.pick(req("song"), r))
        assertNull(VoiceSearchPicker.pick(req("anything"), SearchResults()))
        assertNull(VoiceSearchPicker.pick(req(""), r))
    }

    @Test
    fun focusedPlaylistPicksTheClosestName() {
        val r = SearchResults(playlists = listOf(Playlist(id = "p1", name = "Workout"), Playlist(id = "p2", name = "Gece Sürüşü")))
        val q = VoiceRequest.of("gece surusu") { if (it == VoiceRequest.EXTRA_FOCUS) "vnd.android.cursor.item/playlist" else null }
        assertEquals(Pick.PlaylistPick(r.playlists[1]), VoiceSearchPicker.pick(q, r))
    }

    // ---- resolving -------------------------------------------------------------------------------------------------------

    @Test
    fun songPlaysWithItsAlbumContext() = runBlocking {
        val album = Album(
            id = "al1",
            name = "Karma",
            tracks = listOf(track("k1", "Kuzu Kuzu", "Tarkan", "al1"), track("k2", "Verme", "Tarkan", "al1"), track("k3", "Şımarık", "Tarkan", "al1")),
        )
        VoiceSearch.catalog = VoiceCatalog.of(
            { SearchResults(tracks = listOf(track("k3", "Şımarık", "Tarkan", "al1"))) },
            { album },
            { emptyList() },
        )
        val play = VoiceSearch.resolve(req("şımarık tarkan"), null)!!
        assertEquals(PlayContext("album:al1", "Karma"), play.context)
        assertEquals(2, play.startIndex)
        assertEquals(3, play.tracks.size)
    }

    @Test
    fun songWithoutReadableAlbumPlaysTheSearchResults() = runBlocking {
        VoiceSearch.catalog = VoiceCatalog.of(
            { SearchResults(tracks = listOf(track("a", "One", "X", "al"), track("b", "Two", "Y", "al"))) },
            { throw java.io.IOException("offline") },
            { emptyList() },
        )
        val play = VoiceSearch.resolve(req("two"), null)!!
        assertEquals("search", play.context.uri)
        assertEquals(1, play.startIndex)
    }

    @Test
    fun artistPlaysTopTracks() = runBlocking {
        val top = listOf(track("t1", "Kuzu Kuzu", "Tarkan"), track("t2", "Dudu", "Tarkan"))
        VoiceSearch.catalog = VoiceCatalog.of(
            { SearchResults(artists = listOf(Artist(id = "ar1", name = "Tarkan")), tracks = top) },
            { Album() },
            { id -> if (id == "ar1") top else emptyList() },
        )
        val play = VoiceSearch.resolve(req("Tarkan on ShadeTube"), null)!!
        assertEquals(PlayContext("artist:ar1", "Tarkan"), play.context)
        assertEquals(-1, play.startIndex)
        assertEquals(top, play.tracks)
    }

    @Test
    fun emptyRequestWithoutLibraryFindsNothing() = runBlocking {
        assertNull(VoiceSearch.resolve(req(""), null))
    }
}
