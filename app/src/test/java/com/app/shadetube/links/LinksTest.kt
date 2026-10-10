package com.app.shadetube.links

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Port of tests/links (the Spotify base62 <-> gid and metadata checks belong to the spotify package). */
class LinksTest {
    private val failures = ArrayList<String>()

    private fun expect(text: String, kind: LinkKind, id: String? = null) {
        val l = Links.parse(text)
        if (l.kind != kind || (id != null && l.id != id)) failures += "parse(\"$text\"): ${l.kind} \"${l.id}\" (want $kind \"${id ?: ""}\")"
    }

    private fun check() = assertTrue(failures.joinToString("\n"), failures.isEmpty())

    @Test
    fun spotify() {
        val id = "5sWHDYs0csV6RS48xBl0tH"
        expect("https://open.spotify.com/track/5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyTrack, id)
        expect("https://open.spotify.com/track/5sWHDYs0csV6RS48xBl0tH?si=abc123def456", LinkKind.SpotifyTrack, id)
        expect("https://open.spotify.com/intl-tr/track/5sWHDYs0csV6RS48xBl0tH?si=x&context=y", LinkKind.SpotifyTrack, id)
        expect("https://open.spotify.com/intl-pt-br/album/5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyAlbum, id)
        expect("https://open.spotify.com/embed/playlist/5sWHDYs0csV6RS48xBl0tH?utm_source=generator", LinkKind.SpotifyPlaylist, id)
        expect("https://open.spotify.com/artist/5sWHDYs0csV6RS48xBl0tH#section", LinkKind.SpotifyArtist, id)
        expect("https://open.spotify.com/user/spotify/playlist/5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyPlaylist, id)
        expect("http://open.spotify.com/album/5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyAlbum, id)
        expect("open.spotify.com/album/5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyAlbum, id)
        expect("HTTPS://OPEN.SPOTIFY.COM/album/5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyAlbum, id)
        expect("https://play.spotify.com/track/5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyTrack, id)
        expect("https://www.open.spotify.com/track/5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyTrack, id)
        expect("  https://open.spotify.com/track/5sWHDYs0csV6RS48xBl0tH  \r\n", LinkKind.SpotifyTrack, id)
        expect("<https://open.spotify.com/track/5sWHDYs0csV6RS48xBl0tH>", LinkKind.SpotifyTrack, id)
        expect("\"spotify:track:5sWHDYs0csV6RS48xBl0tH\"", LinkKind.SpotifyTrack, id)
        expect("spotify:track:5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyTrack, id)
        expect("spotify:album:5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyAlbum, id)
        expect("SPOTIFY:Playlist:5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyPlaylist, id)
        expect("spotify:artist:5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyArtist, id)
        expect("spotify:user:someone:playlist:5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyPlaylist, id)
        // Known service, nothing to open.
        expect("https://open.spotify.com/episode/5sWHDYs0csV6RS48xBl0tH", LinkKind.Unsupported)
        expect("https://open.spotify.com/show/5sWHDYs0csV6RS48xBl0tH", LinkKind.Unsupported)
        expect("https://open.spotify.com/user/someone", LinkKind.Unsupported)
        expect("https://open.spotify.com/", LinkKind.Unsupported)
        expect("https://open.spotify.com/track/tooShort", LinkKind.Unsupported)
        expect("https://open.spotify.com/track/5sWHDYs0csV6RS48xBl0t!", LinkKind.Unsupported)
        expect("https://spotify.link/AbCdEf123", LinkKind.Unsupported)
        expect("spotify:episode:5sWHDYs0csV6RS48xBl0tH", LinkKind.Unsupported)
        expect("spotify:track:", LinkKind.Unsupported)
        expect("spotify:track:5sWHDYs0csV6RS48xBl0tHXX", LinkKind.Unsupported)
        check()

        val l = Links.parse("https://open.spotify.com/album/5sWHDYs0csV6RS48xBl0tH")
        assertEquals(LinkService.Spotify, l.service)
        assertEquals("spotify:album:5sWHDYs0csV6RS48xBl0tH", l.spotifyUri)
        assertTrue(l.isLink)
        assertEquals("spotify:track:5sWHDYs0csV6RS48xBl0tH", Links.parse("spotify:track:5sWHDYs0csV6RS48xBl0tH").spotifyUri)
        assertTrue(Links.parse("https://youtu.be/dQw4w9WgXcQ").spotifyUri.isEmpty())
        assertEquals(LinkService.Spotify, Links.parse("https://open.spotify.com/show/5sWHDYs0csV6RS48xBl0tH").service)
    }

    @Test
    fun youtube() {
        val id = "dQw4w9WgXcQ"
        expect("https://www.youtube.com/watch?v=dQw4w9WgXcQ", LinkKind.YouTubeVideo, id)
        expect("https://youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890&index=3", LinkKind.YouTubeVideo, id)
        expect("https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42s", LinkKind.YouTubeVideo, id)
        expect("https://m.youtube.com/watch?v=dQw4w9WgXcQ", LinkKind.YouTubeVideo, id)
        expect("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=abc", LinkKind.YouTubeVideo, id)
        expect("https://youtu.be/dQw4w9WgXcQ", LinkKind.YouTubeVideo, id)
        expect("https://youtu.be/dQw4w9WgXcQ?si=Share1234&t=10", LinkKind.YouTubeVideo, id)
        expect("youtu.be/dQw4w9WgXcQ", LinkKind.YouTubeVideo, id)
        expect("https://www.youtube.com/shorts/dQw4w9WgXcQ", LinkKind.YouTubeVideo, id)
        expect("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1", LinkKind.YouTubeVideo, id)
        expect("https://www.youtube.com/live/dQw4w9WgXcQ?feature=share", LinkKind.YouTubeVideo, id)
        expect("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", LinkKind.YouTubeVideo, id)
        expect("https://www.youtube.com/watch?v=a-b_c-D_e1F", LinkKind.YouTubeVideo, "a-b_c-D_e1F")
        expect("https://www.youtube.com/watch?v=dQw4w9WgXcQ#t=30", LinkKind.YouTubeVideo, id)
        // Playlists (imported as local playlists).
        expect("https://www.youtube.com/playlist?list=PL1234567890", LinkKind.YouTubePlaylist, "PL1234567890")
        expect(
            "https://music.youtube.com/playlist?list=OLAK5uy_kIhNTSEGnAyPZqWKFy0Li1Jrk0tyk0yAo&si=x",
            LinkKind.YouTubePlaylist, "OLAK5uy_kIhNTSEGnAyPZqWKFy0Li1Jrk0tyk0yAo",
        )
        assertTrue(Links.isPlaylistId("PL1234567890") && !Links.isPlaylistId("PL12") && !Links.isPlaylistId("PL12345678!0"))
        // Known service, nothing to open.
        expect("https://www.youtube.com/playlist?list=PL1", LinkKind.Unsupported)
        expect("https://www.youtube.com/@someone", LinkKind.Unsupported)
        expect("https://www.youtube.com/channel/UC1234567890", LinkKind.Unsupported)
        expect("https://www.youtube.com/", LinkKind.Unsupported)
        expect("https://www.youtube.com/watch?v=short", LinkKind.Unsupported)
        expect("https://www.youtube.com/watch?list=PL1234567890", LinkKind.Unsupported)
        expect("https://youtu.be/", LinkKind.Unsupported)
        expect("https://youtu.be/dQw4w9WgXc!", LinkKind.Unsupported)
        check()
        assertEquals(LinkService.YouTube, Links.parse("https://youtu.be/dQw4w9WgXcQ").service)
    }

    @Test
    fun musicBrainz() {
        val id = "a1b2c3d4-e5f6-4789-8abc-def012345678"
        expect("https://musicbrainz.org/release-group/a1b2c3d4-e5f6-4789-8abc-def012345678", LinkKind.MbReleaseGroup, id)
        expect("https://musicbrainz.org/release/a1b2c3d4-e5f6-4789-8abc-def012345678/cover-art", LinkKind.MbRelease, id)
        expect("https://beta.musicbrainz.org/artist/A1B2C3D4-E5F6-4789-8ABC-DEF012345678", LinkKind.MbArtist, id)
        expect("musicbrainz.org/recording/a1b2c3d4-e5f6-4789-8abc-def012345678?tab=x", LinkKind.MbRecording, id)
        expect("https://musicbrainz.org/label/a1b2c3d4-e5f6-4789-8abc-def012345678", LinkKind.Unsupported)
        expect("https://musicbrainz.org/release-group/not-an-mbid", LinkKind.Unsupported)
        expect("https://musicbrainz.org/", LinkKind.Unsupported)
        check()
        assertTrue(Links.isMbid("a1b2c3d4-e5f6-4789-8abc-def012345678"))
        assertFalse(Links.isMbid("a1b2c3d4e5f64789-8abc-def012345678x"))
        assertFalse(Links.isMbid("g1b2c3d4-e5f6-4789-8abc-def012345678"))
    }

    @Test
    fun notLinks() {
        expect("", LinkKind.None)
        expect("   ", LinkKind.None)
        expect("Sezen Aksu", LinkKind.None)
        expect("tarkan şımarık", LinkKind.None)
        expect("spotify", LinkKind.None)
        expect("https://example.com/track/5sWHDYs0csV6RS48xBl0tH", LinkKind.None)
        expect("https://notyoutube.com/watch?v=dQw4w9WgXcQ", LinkKind.None)
        expect("https://open.spotify.com.evil.example/track/5sWHDYs0csV6RS48xBl0tH", LinkKind.None)
        expect("listen https://open.spotify.com/track/5sWHDYs0csV6RS48xBl0tH now", LinkKind.None)
        expect("ftp", LinkKind.None)
        expect("youtube", LinkKind.None)
        // user@host / port are not part of the host name.
        expect("https://user@open.spotify.com:443/track/5sWHDYs0csV6RS48xBl0tH", LinkKind.SpotifyTrack, "5sWHDYs0csV6RS48xBl0tH")
        // Non-ASCII text (the C++ UTF-16 overload).
        expect("  spotify:album:5sWHDYs0csV6RS48xBl0tH ", LinkKind.SpotifyAlbum)
        expect("şarkı", LinkKind.None)
        expect("https://youtu.be/dQw4w9WgXcş", LinkKind.Unsupported)
        check()
        assertTrue(Links.isSpotifyId("5sWHDYs0csV6RS48xBl0tH"))
        assertFalse(Links.isSpotifyId("5sWHDYs0csV6RS48xBl0t"))
        assertTrue(Links.isVideoId("dQw4w9WgXcQ"))
        assertFalse(Links.isVideoId("dQw4w9WgXc"))
        // Clipboard: a document is not a link.
        assertEquals(LinkKind.YouTubeVideo, Links.parseClipboard("https://youtu.be/dQw4w9WgXcQ").kind)
        assertEquals(LinkKind.None, Links.parseClipboard("https://youtu.be/dQw4w9WgXcQ" + " ".repeat(5000)).kind)
        assertEquals(LinkKind.None, Links.parseClipboard(null).kind)
    }

    private fun song(title: String, channel: String, artist: String, name: String) {
        val s = Links.videoSong(title, channel)
        if (s.artist != artist || s.title != name)
            failures += "videoSong(\"$title\", \"$channel\") = {\"${s.artist}\", \"${s.title}\"} (want {\"$artist\", \"$name\"})"
    }

    @Test
    fun videoSong() {
        song("Tarkan - Şımarık (Official Video)", "TarkanVEVO", "Tarkan", "Şımarık")
        song("Şımarık", "Tarkan - Topic", "Tarkan", "Şımarık")
        song("Never Gonna Give You Up", "Rick Astley", "Rick Astley", "Never Gonna Give You Up")
        song("Rick Astley - Never Gonna Give You Up (Official Music Video) [HD]", "Rick Astley", "Rick Astley", "Never Gonna Give You Up")
        song("Daft Punk - Get Lucky (feat. Pharrell Williams) [Official Audio]", "Daft Punk", "Daft Punk", "Get Lucky (feat. Pharrell Williams)")
        song("Artist - Song (Live)", "Channel", "Artist", "Song (Live)")
        song("Eclipse (Remix)", "Some Channel", "Some Channel", "Eclipse (Remix)")
        song("Artist – Song [Lyrics]", "Lyrics Channel", "Artist", "Song")
        song("Artist — Song (Lyric Video)", "Lyrics Channel", "Artist", "Song")
        song("Sezen Aksu - Gidiyorum (Klip)", "Sezen Aksu Official", "Sezen Aksu", "Gidiyorum")
        song("Müslüm Gürses - Nilüfer (Sözleri)", "Arşiv", "Müslüm Gürses", "Nilüfer")
        song("Song - With - Dashes (Official Video)", "Band", "Song", "With - Dashes")
        song("(Official Video)", "Band", "Band", "(Official Video)")
        song("Artist - 'Quoted Song' (Official Audio)", "Label", "Artist", "Quoted Song")
        song("Song Title - Topic Style", "Real Artist - Topic", "Real Artist", "Song Title - Topic Style")
        song("  Spaced   Out  -  Song  ", "  Chan  ", "Spaced Out", "Song")
        song("Artist - Title 【MV】", "Label", "Artist", "Title")
        song("Artist - Title [4K Remaster]", "Label", "Artist", "Title [4K Remaster]")
        song("-Leading", "Chan", "Chan", "-Leading")
        check()
    }

    // ---- LinkOpener -----------------------------------------------------------------------------------------------
    private class FakeLookups(override val spotifyLoggedIn: Boolean = true) : LinkLookups {
        val album = Album(
            id = "spotify:album:A", name = "Album",
            tracks = listOf(Track(id = "spotify:track:1"), Track(id = "spotify:track:T"), Track(id = "spotify:track:3")),
        )
        override suspend fun spotifyTrack(uri: String) =
            if (uri.endsWith("5sWHDYs0csV6RS48xBl0tH")) Track(id = "spotify:track:T", album = AlbumRef("spotify:album:A"))
            else throw IOException("404")
        override suspend fun spotifyAlbum(albumId: String) = album
        override suspend fun mbRecording(recordingId: String) =
            Track(id = recordingId, album = AlbumRef("rg-1"))
        override suspend fun mbAlbum(releaseGroupId: String): Album = throw IOException("album down")
        override suspend fun mbReleaseGroupOfRelease(releaseId: String) = if (releaseId.startsWith("a")) "rg-9" else ""
        override suspend fun youtubeVideo(videoId: String) = YouTubeVideoInfo(
            title = "Tarkan - Şımarık (Official Video)", channel = "TarkanVEVO", durationMs = 245_000, isLive = videoId == "liveliveliv",
            thumbnails = listOf(Image("4x3.jpg", 480, 360), Image("16x9.jpg", 1280, 720)),
        )
    }

    @Test
    fun openerDecisions() = runBlocking {
        val lookups = FakeLookups()
        suspend fun open(text: String, l: LinkLookups = lookups) = LinkOpener.open(Links.parse(text), l)

        assertEquals(LinkAction.Search, open("Sezen Aksu"))
        assertEquals(LinkAction.Unsupported, open("https://open.spotify.com/show/5sWHDYs0csV6RS48xBl0tH"))
        assertEquals(LinkAction.NeedsSpotifyLogin, open("spotify:album:5sWHDYs0csV6RS48xBl0tH", FakeLookups(false)))
        assertEquals(LinkAction.Unsupported, open("https://spotify.link/x", FakeLookups(false)))
        assertEquals(LinkAction.OpenAlbum("spotify:album:5sWHDYs0csV6RS48xBl0tH"), open("spotify:album:5sWHDYs0csV6RS48xBl0tH"))
        assertEquals(LinkAction.OpenPlaylist("spotify:playlist:5sWHDYs0csV6RS48xBl0tH"), open("spotify:playlist:5sWHDYs0csV6RS48xBl0tH"))
        assertEquals(LinkAction.OpenArtist("spotify:artist:5sWHDYs0csV6RS48xBl0tH"), open("spotify:artist:5sWHDYs0csV6RS48xBl0tH"))
        assertEquals(LinkAction.ImportYouTubePlaylist("PL1234567890"), open("https://www.youtube.com/playlist?list=PL1234567890"))
        val mbid = "a1b2c3d4-e5f6-4789-8abc-def012345678"
        assertEquals(LinkAction.OpenAlbum(mbid), open("https://musicbrainz.org/release-group/$mbid"))
        assertEquals(LinkAction.OpenArtist(mbid), open("https://musicbrainz.org/artist/$mbid"))
        assertEquals(LinkAction.OpenAlbum("rg-9"), open("https://musicbrainz.org/release/$mbid"))
        assertEquals(
            LinkAction.Failed(LinkAction.FailReason.MbReleaseNotFound),
            open("https://musicbrainz.org/release/b1b2c3d4-e5f6-4789-8abc-def012345678"),
        )

        // A Spotify track plays in its album from that song and shows the album.
        val play = open("https://open.spotify.com/track/5sWHDYs0csV6RS48xBl0tH") as LinkAction.PlayInAlbum
        assertEquals(1, play.index)
        assertEquals(3, play.tracks.size)
        assertEquals("spotify:album:A", play.navigateAlbumId)
        assertEquals(
            LinkAction.Failed(LinkAction.FailReason.SpotifyTrackNotFound),
            open("https://open.spotify.com/track/0000000000000000000000"),
        )
        // A recording whose album can't be loaded plays alone; the album page still opens.
        val rec = open("https://musicbrainz.org/recording/$mbid") as LinkAction.PlayInAlbum
        assertNull(rec.album)
        assertEquals(listOf(mbid), rec.tracks.map { it.id })
        assertEquals("rg-1", rec.navigateAlbumId)

        // A YouTube video becomes a yt: song pinned to that video, with a 16:9 cover.
        val yt = open("https://youtu.be/dQw4w9WgXcQ") as LinkAction.PlayYouTubeVideo
        assertEquals("yt:dQw4w9WgXcQ", yt.track.id)
        assertEquals("dQw4w9WgXcQ", yt.track.videoId)
        assertEquals("Şımarık", yt.track.name)
        assertEquals("Tarkan", yt.track.artistLine)
        assertEquals(245_000, yt.track.durationMs)
        assertEquals(listOf("16x9.jpg"), yt.track.album.images.map { it.url })
        assertEquals(PinnedVideo("dQw4w9WgXcQ", "Tarkan - Şımarık (Official Video)", "TarkanVEVO", 245, "16x9.jpg"), yt.pin)
        assertEquals(LinkAction.Failed(LinkAction.FailReason.LiveStream), open("https://youtu.be/liveliveliv"))
        // No 16:9 thumbnail: mqdefault.
        val noThumbs = LinkOpener.youtubeVideoAction("abcdefghijk", YouTubeVideoInfo("T", "C", null, false, emptyList()))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", noThumbs.track.album.images.single().url)
    }

    @Test
    fun verbsAndIcons() {
        assertEquals(LinkVerb.PlaySong, LinkOpener.verb(Links.parse("spotify:track:5sWHDYs0csV6RS48xBl0tH")))
        assertEquals(LinkVerb.OpenAlbum, LinkOpener.verb(Links.parse("https://musicbrainz.org/release/a1b2c3d4-e5f6-4789-8abc-def012345678")))
        assertNull(LinkOpener.verb(Links.parse("https://open.spotify.com/show/5sWHDYs0csV6RS48xBl0tH")))
        assertEquals(LinkIcon.YouTubeSource, LinkOpener.icon(Links.parse("https://youtu.be/dQw4w9WgXcQ")))
        assertEquals(LinkIcon.Link, LinkOpener.icon(Links.parse("https://musicbrainz.org/")))
        assertTrue(LinkOpener.needsSpotify(Links.parse("spotify:track:5sWHDYs0csV6RS48xBl0tH"), false))
        assertFalse(LinkOpener.needsSpotify(Links.parse("spotify:show:5sWHDYs0csV6RS48xBl0tH"), false))
    }
}
