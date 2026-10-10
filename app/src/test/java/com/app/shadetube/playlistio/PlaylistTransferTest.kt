package com.app.shadetube.playlistio

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Page
import com.app.shadetube.catalog.Track
import com.app.shadetube.localfiles.LocalEntry
import com.app.shadetube.localfiles.LocalFile
import com.app.shadetube.localfiles.LocalFormats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The import / export flows of PlaylistTransfer over fakes (no network). */
class PlaylistTransferTest {
    private val libEntry = LocalEntry(path = "/storage/emulated/0/Music/Rock/song.mp3", title = "Song", artists = listOf("Band"), name = "song.mp3")
    private val outside = LocalEntry(path = "/storage/emulated/0/Download/x.flac", title = "Outside", artists = listOf("Who"), name = "x.flac")

    private inner class FakeLocal : LocalFilesAccess {
        val remembered = ArrayList<LocalEntry>()
        val readRequests = ArrayList<String>()
        override fun libraryEntries() = listOf(libEntry)
        override fun fileFor(trackId: String): LocalFile? =
            listOf(libEntry, outside).firstOrNull { LocalFormats.trackIdFor(it.path) == trackId }?.let { LocalFile.of(it) }
        override suspend fun read(paths: List<String>, maxFiles: Int): List<LocalEntry> {
            readRequests += paths
            return paths.filter { it == outside.path }.map { outside }
        }
        override fun remember(entries: List<LocalEntry>) {
            remembered += entries
        }
    }

    private class FakePlaylists : LocalPlaylistTarget {
        val created = LinkedHashMap<String, MutableList<Track>>()
        override fun createPlaylist(name: String): String = "local:pl${created.size}".also { created[it] = ArrayList() }
        override fun addToPlaylist(playlistId: String, tracks: List<Track>): Int {
            created[playlistId]!! += tracks
            return tracks.size
        }
    }

    private class FakeSpotify(var failBatch: Int = -1) : SpotifyTransfer {
        override val loggedIn = true
        val pages = ArrayList<Int>()
        val batches = ArrayList<List<String>>()
        override suspend fun playlistTracks(uri: String, offset: Int, limit: Int): Page<Track> {
            pages += offset
            val total = 250
            val items = (offset until minOf(total, offset + limit)).map { Track(id = "spotify:track:${"%022d".format(it)}", name = "S$it") }
            return Page(items, total, offset, offset + items.size < total)
        }
        override suspend fun track(uri: String) = Track(id = uri, name = "Named $uri", artists = listOf(ArtistRef("", "Sp")))
        override suspend fun createPlaylist(name: String, trackUris: List<String>): CreatedPlaylist {
            batches += trackUris
            return CreatedPlaylist("spotify:playlist:new", trackUris.size)
        }
        override suspend fun addToPlaylist(playlistUri: String, trackUris: List<String>): Boolean {
            batches += trackUris
            return batches.size - 1 != failBatch
        }
    }

    private class FakeYouTube : YouTubeTransfer {
        override suspend fun video(videoId: String) = VideoInfo(videoId, "Artist - Video Song (Official Video)", "Channel", 200000)
        override suspend fun playlist(playlistId: String, maxVideos: Int) =
            YouTubePlaylist("YT List", listOf(VideoInfo("aqz-KE-bpKQ", "Singer - Tune", "Singer", 1000), VideoInfo("dQw4w9WgXcQ", "Song", "Someone - Topic", 2000)))
        override fun cachedVideoId(trackId: String) = if (trackId == "spotify:track:4uLU6hMCjMI75M1A2tKUQC") "dQw4w9WgXcQ" else null
    }

    private fun transfer(local: LocalFilesAccess = FakeLocal(), playlists: LocalPlaylistTarget = FakePlaylists(), spotify: SpotifyTransfer? = FakeSpotify()) =
        PlaylistTransfer(
            local, playlists, spotify, FakeYouTube(),
            musicBrainz = { id -> Track(id = id, name = "MB Song") },
            albums = { id -> Album(id = id, name = "The Album", tracks = listOf(Track(id = "spotify:track:a", name = "A1"))) },
            downloads = { id -> if (id == "spotify:track:4uLU6hMCjMI75M1A2tKUQC") "/storage/emulated/0/Music/ShadeTube/dl.m4a" else null },
            spotifyPageDelayMs = 0, spotifyLookupDelayMs = 0,
        )

    @Test
    fun importFile() = runBlocking {
        val m3u = "#EXTM3U\n#PLAYLIST:Karışık\n" +
            "C:\\Users\\me\\Music\\Rock\\song.mp3\n" + // a Windows list: matched to the library by folder + name
            "/storage/emulated/0/Download/x.flac\n" + // outside the library: read
            "#EXTINF:100,Gone - Away\n/storage/emulated/0/nothing.mp3\n" + // gone, but named: matched by name
            "/storage/emulated/0/nameless.mp3\n" + // gone and nameless... named by its file name
            "spotify:track:6rqhFgbbKwnb9MLmUQDhG6\n" + // id only: looked up
            "https://youtu.be/aqz-KE-bpKQ\n" +
            "https://musicbrainz.org/recording/0f5c1d3e-1111-2222-3333-444455556666\n" +
            "https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv\n" // no song
        val local = FakeLocal()
        val playlists = FakePlaylists()
        val t = transfer(local, playlists)
        val preview = t.previewFile({ m3u.byteInputStream() }, "list.m3u8").getOrThrow()
        assertEquals("Karışık", preview.name)
        val names = preview.tracks.map { it.name }
        assertEquals(listOf("Song", "Outside", "Away", "Named spotify:track:6rqhFgbbKwnb9MLmUQDhG6", "Video Song", "MB Song"), names)
        assertEquals(LocalFormats.trackIdFor(libEntry.path), preview.tracks[0].id)
        assertEquals(PlaylistIO.importId("Away", "Gone"), preview.tracks[2].id)
        assertEquals("yt:aqz-KE-bpKQ", preview.tracks[4].id)
        // The nameless gone file and the album link are reported.
        assertEquals(listOf(11, 7), preview.unresolved.map { it.line })
        assertEquals(2, preview.localCount)
        assertEquals(1, preview.spotifyCount)
        assertEquals(1, preview.youtubeCount)
        assertEquals(listOf(outside), preview.localEntries)
        assertTrue(local.readRequests.contains("/storage/emulated/0/Download/x.flac"))

        val result = t.commit(preview, "", alsoOnSpotify = true)
        assertEquals("local:pl0", result.playlistId)
        assertEquals(preview.tracks.size, result.added)
        assertEquals(preview.tracks, playlists.created["local:pl0"])
        assertEquals(listOf(outside), local.remembered)
        assertEquals(SpotifyOutcome.Created("spotify:playlist:new", 1), result.spotify)
    }

    @Test
    fun youtubePlaylist() = runBlocking {
        val p = transfer().previewYouTubePlaylist("PLx").getOrThrow()
        assertEquals("YT List", p.name)
        assertEquals(listOf("yt:aqz-KE-bpKQ", "yt:dQw4w9WgXcQ"), p.tracks.map { it.id })
        assertEquals("Tune", p.tracks[0].name)
        assertEquals("Singer", p.tracks[0].artists.single().name)
        assertEquals("Someone", p.tracks[1].artists.single().name) // "- Topic" channel
        assertEquals("aqz-KE-bpKQ", p.tracks[0].videoId)
        assertTrue(p.tracks[0].album.images.single().url.endsWith("/aqz-KE-bpKQ/mqdefault.jpg"))
    }

    @Test
    fun spotifyBatches() = runBlocking {
        val tracks = (0 until 250).map { Track(id = "spotify:track:${"%022d".format(it)}", name = "S$it") } + Track(id = "yt:x", name = "v")
        val sp = FakeSpotify()
        val t = transfer(spotify = sp)
        assertEquals(SpotifyOutcome.Created("spotify:playlist:new", 250), t.createOnSpotify("L", tracks))
        assertEquals(listOf(100, 100, 50), sp.batches.map { it.size })
        val failing = FakeSpotify(failBatch = 2)
        assertEquals(SpotifyOutcome.Partial("spotify:playlist:new", 200), transfer(spotify = failing).createOnSpotify("L", tracks))
        assertEquals(SpotifyOutcome.Skipped, t.createOnSpotify("L", listOf(Track(id = "yt:x"))))
        assertEquals(SpotifyOutcome.Skipped, transfer(spotify = null).createOnSpotify("L", tracks))
    }

    @Test
    fun export() = runBlocking {
        val t = transfer()
        // Spotify lists are paged in full.
        val sp = FakeSpotify()
        val all = transfer(spotify = sp).tracksFor(ExportSource.SpotifyList("spotify:playlist:x"))
        assertEquals(250, all.size)
        assertEquals(listOf(0, 100, 200), sp.pages)
        val album = t.tracksFor(ExportSource.AlbumOf("spotify:album:1"))
        assertEquals("The Album", album.single().album.name) // the album filled in
        // Rows: downloads, local files, matched videos; stations and episodes left out.
        val tracks = listOf(
            Track(id = "spotify:track:4uLU6hMCjMI75M1A2tKUQC", name = "Down"),
            Track(id = LocalFormats.trackIdFor(libEntry.path), name = "Song"),
            Track(id = "radio:abc", name = "Station"),
            Track(id = "podcast:feed:1", name = "Episode"),
            Track(id = "", name = "No id"),
        )
        val rows = t.rowsFor(tracks)
        assertEquals(2, rows.size)
        assertEquals("/storage/emulated/0/Music/ShadeTube/dl.m4a", rows[0].filePath)
        assertEquals("dQw4w9WgXcQ", rows[0].videoId)
        assertEquals(libEntry.path, rows[1].filePath)
        val out = ByteArrayOutputStream()
        assertEquals(2, t.export("Liste", tracks, PlaylistFormat.M3U, out).getOrThrow())
        val text = out.toString(Charsets.UTF_8.name())
        assertTrue(text.contains("/storage/emulated/0/Music/Rock/song.mp3"))
        assertTrue(text.contains("#PLAYLIST:Liste"))
        assertEquals(0, t.export("x", listOf(Track(id = "radio:x")), PlaylistFormat.CSV, ByteArrayOutputStream()).getOrThrow())
        assertNotNull(PlaylistTransfer.safeFileName("a/b:c?").takeIf { it == "a_b_c_" })
    }

    @Test
    fun pathMatcher() {
        val m = LocalPathMatcher(listOf(libEntry, LocalEntry(path = "/storage/1234-ABCD/Other/song.mp3", name = "song.mp3"), LocalEntry(path = "/x/unique.ogg", name = "unique.ogg")))
        assertEquals(libEntry, m.exact("/storage/emulated/0/Music/Rock/song.mp3"))
        assertEquals(libEntry, m.exact("file:///storage/emulated/0/Music/Rock/song.mp3"))
        assertEquals(libEntry, m.byName("D:\\Müzik\\Rock\\Song.MP3")) // folder + name
        assertEquals(null, m.byName("D:\\Elsewhere\\song.mp3")) // two files named song.mp3: ambiguous
        assertEquals("/x/unique.ogg", m.byName("E:/a/b/unique.ogg")?.path)
    }
}
