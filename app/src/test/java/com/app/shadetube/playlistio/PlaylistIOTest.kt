package com.app.shadetube.playlistio

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Port of tests/collections (app/PlaylistIO): round trips in every format, the CSV reader and dialects, M3U paths and
 * links, helpers (durations, ISO times, file URIs, encodings, formats), reading from streams, and 10 000 songs per
 * format. Paths use forward slashes on Android.
 */
class PlaylistIOTest {
    private val sp1 = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
    private val mbid = "0f5c1d3e-1111-2222-3333-444455556666"

    private fun sampleRows(): List<ExportRow> = listOf(
        ExportRow(
            Track(
                id = sp1, name = "Hello, \"World\"",
                artists = listOf(ArtistRef("spotify:artist:1", "Artist One"), ArtistRef("spotify:artist:2", "Artist Two")),
                album = AlbumRef("spotify:album:9", "Album, the First", listOf(Image("https://i.scdn.co/image/abc", 640, 640))),
                durationMs = 213000, addedAt = 1700000000, explicitContent = true,
            ),
            videoId = "dQw4w9WgXcQ",
        ),
        ExportRow(
            Track(id = mbid, name = "=SUM(A1)", artists = listOf(ArtistRef("", "Şebnem Ferah")), album = AlbumRef(name = "Kelimeler Yetseydi"), durationMs = 245500),
        ),
        ExportRow(Track(id = "yt:aqz-KE-bpKQ", videoId = "aqz-KE-bpKQ", name = "日本の歌", artists = listOf(ArtistRef("", "歌手")), durationMs = 180000)),
        ExportRow(Track(id = "local:00112233", name = "Local Song", artists = listOf(ArtistRef("", "Me"))), filePath = "/storage/emulated/0/Music/My Songs/Local Song.flac"),
        ExportRow(Track(id = PlaylistIO.importId("Only A Name", "Someone"), name = "Only A Name", artists = listOf(ArtistRef("", "Someone")))),
    )

    private fun byName(p: ParsedPlaylist, name: String) = p.entries.firstOrNull { it.track.name == name }

    @Test
    fun roundTrip() {
        val rows = sampleRows()
        for (f in listOf(PlaylistFormat.JSON, PlaylistFormat.CSV, PlaylistFormat.M3U, PlaylistFormat.XSPF)) {
            val text = PlaylistIO.write(f, "Liste, adı", rows)
            val p = PlaylistIO.parse(text, f)
            assertTrue("$f unresolved ${p.unresolved}", p.unresolved.isEmpty())
            assertEquals("$f", rows.size, p.entries.size)
            if (f != PlaylistFormat.CSV) assertEquals("Liste, adı", p.name)
            val a = byName(p, "Hello, \"World\"")
            assertNotNull("$f", a)
            a!!
            assertEquals(sp1, a.track.id)
            assertEquals(213, a.track.durationMs / 1000)
            assertTrue(a.track.artists.first().name.startsWith("Artist One"))
            assertEquals("Album, the First", a.track.album.name)
            if (f == PlaylistFormat.JSON || f == PlaylistFormat.CSV) {
                assertEquals(2, a.track.artists.size)
                assertEquals("Artist Two", a.track.artists[1].name)
                assertEquals("dQw4w9WgXcQ", a.track.videoId)
                assertEquals(1700000000L, a.track.addedAt)
                assertEquals(213000, a.track.durationMs)
            }
            if (f == PlaylistFormat.JSON) {
                assertEquals("spotify:artist:1", a.track.artists[0].id)
                assertEquals("spotify:album:9", a.track.album.id)
                assertEquals(1, a.track.album.images.size)
                assertTrue(a.track.explicitContent)
            }
            val b = byName(p, "=SUM(A1)") // the formula guard of the CSV comes off again
            assertEquals(mbid, b?.track?.id)
            assertEquals("Şebnem Ferah", b!!.track.artists.first().name)
            val c = byName(p, "日本の歌")
            assertEquals("yt:aqz-KE-bpKQ", c?.track?.id)
            assertEquals("aqz-KE-bpKQ", c!!.track.videoId)
            val d = p.entries.lastOrNull { it.localPath.isNotEmpty() }
            assertEquals("$f", "/storage/emulated/0/Music/My Songs/Local Song.flac", d?.localPath)
            assertEquals("", d!!.track.id)
            assertEquals(PlaylistIO.importId("Only A Name", "Someone"), byName(p, "Only A Name")?.track?.id)
            if (f == PlaylistFormat.CSV) assertEquals("shadetube", p.dialect)
        }
        // CSV specifics: BOM, CRLF, header, the formula guard, quoting.
        val csv = PlaylistIO.writeCsv(rows)
        assertTrue(csv.startsWith("\uFEFFTitle,Artists,Album,"))
        assertTrue(csv.contains("'=SUM(A1)"))
        assertTrue(csv.contains("\"Hello, \"\"World\"\"\""))
        assertTrue(csv.contains("Artist One; Artist Two"))
        assertTrue(csv.contains("\r\n"))
        // M3U8: extended info and locations.
        val m3u = PlaylistIO.writeM3u8("X", rows)
        assertTrue(m3u.startsWith("#EXTM3U\r\n#PLAYLIST:X\r\n"))
        assertTrue(m3u.contains("#EXTINF:213,Artist One, Artist Two - Hello, \"World\""))
        assertTrue(m3u.contains("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertTrue(m3u.contains("https://musicbrainz.org/recording/$mbid"))
        assertTrue(m3u.contains("https://www.youtube.com/watch?v=aqz-KE-bpKQ"))
        assertTrue(m3u.contains("/storage/emulated/0/Music/My Songs/Local Song.flac"))
        assertTrue(m3u.contains("https://music.youtube.com/search?q=Someone%20Only%20A%20Name"))
        // XSPF: file URI, escaping.
        val xspf = PlaylistIO.writeXspf("A & B", rows)
        assertTrue(xspf.contains("<title>A &amp; B</title>"))
        assertTrue(xspf.contains("file:///storage/emulated/0/Music/My%20Songs/Local%20Song.flac"))
        assertTrue(xspf.contains("Hello, &quot;World&quot;"))
        // JSON: the format marker.
        val json = PlaylistIO.writeJson("N", rows)
        assertTrue(json.contains("\"format\": \"shadetube.playlist\""))
        assertTrue(json.contains("\"version\": 1"))
    }

    @Test
    fun csvReader() {
        assertEquals(',', PlaylistIO.detectDelimiter("a,\"b,c\",\"d \"\"e\"\"\"\r\n"))
        var rows = PlaylistIO.readCsv("a,\"b,c\",\"d \"\"e\"\"\"\r\n\"multi\nline\",x,\r\n\r\n")
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b,c", "d \"e\""), rows[0])
        assertEquals(listOf("multi\nline", "x", ""), rows[1])
        val semi = "\uFEFFTitle;Artist;Album\n\"A;1\";B;C\n"
        assertEquals(';', PlaylistIO.detectDelimiter(semi.substring(1)))
        rows = PlaylistIO.readCsv(semi)
        assertEquals(2, rows.size)
        assertEquals("Title", rows[0][0])
        assertEquals("A;1", rows[1][0])
        assertEquals('\t', PlaylistIO.detectDelimiter("a\tb\tc\n1\t2\t3"))
        rows = PlaylistIO.readCsv("a\tb\tc\n1\t2\t3")
        assertEquals(2, rows.size)
        assertEquals("3", rows[1][2])
    }

    @Test
    fun csvDialects() {
        // Exportify.
        val exportify =
            "Track URI,Track Name,Artist Name(s),Album Name,Disc Number,Track Number,Track Duration (ms),Explicit," +
                "Popularity,ISRC,Added By,Added At\n" +
                "spotify:track:6rqhFgbbKwnb9MLmUQDhG6,Speak to Me,\"Pink Floyd,Someone Else\",The Dark Side of the Moon,1,1," +
                "65000,false,60,GBN9Y1100088,spotify:user:x,2023-05-01T10:20:30Z\n" +
                "spotify:local:::Local+Thing:200,Local Thing,Me,,0,0,200000,false,0,,spotify:user:x,2023-05-02T00:00:00Z\n"
        var p = PlaylistIO.parse(exportify, PlaylistFormat.CSV)
        assertEquals("exportify", p.dialect)
        assertEquals(2, p.entries.size)
        val t = p.entries[0].track
        assertEquals("spotify:track:6rqhFgbbKwnb9MLmUQDhG6", t.id)
        assertEquals("Speak to Me", t.name)
        assertEquals(listOf("Pink Floyd", "Someone Else"), t.artists.map { it.name })
        assertEquals("The Dark Side of the Moon", t.album.name)
        assertEquals(65000, t.durationMs)
        assertEquals(1682936430L, t.addedAt)
        // A Spotify local file has no usable id: kept by its name.
        assertEquals(PlaylistIO.importId("Local Thing", "Me"), p.entries[1].track.id)
        assertEquals(3, p.entries[1].line)

        // TuneMyMusic (bare Spotify ids).
        p = PlaylistIO.parse(
            "Track name,Artist name,Album,Playlist name,Type,ISRC,Spotify - id\n" +
                "Yalnızlık Senfonisi,Sezen Aksu,Deli Kızın Türküsü,Mix,Playlist,TRA,3Fcfwhm8oRrBvBZ8KGhtea\n",
            PlaylistFormat.CSV,
        )
        assertEquals("tunemymusic", p.dialect)
        assertEquals(1, p.entries.size)
        assertEquals("spotify:track:3Fcfwhm8oRrBvBZ8KGhtea", p.entries[0].track.id)
        assertEquals(listOf("Sezen Aksu"), p.entries[0].track.artists.map { it.name })

        // Soundiiz (names only).
        p = PlaylistIO.parse("title,artist,album,isrc\nSong,Band,Record,\n", PlaylistFormat.CSV)
        assertEquals("soundiiz", p.dialect)
        assertEquals(PlaylistIO.importId("Song", "Band"), p.entries.single().track.id)

        // Generic, semicolons, m:ss durations, a YouTube link column, an empty row and a row with no song.
        p = PlaylistIO.parse(
            "\uFEFFName;Artist;Duration;Link\r\n" +
                "Şarkı;Sanatçı;3:25;\r\n" +
                "Video;Kanal;;https://youtu.be/aqz-KE-bpKQ?t=3\r\n" +
                ";;;\r\n" +
                ";;2:00;\r\n",
            PlaylistFormat.CSV,
        )
        assertEquals("generic", p.dialect)
        assertEquals(2, p.entries.size)
        assertEquals("Şarkı", p.entries[0].track.name)
        assertEquals(205000, p.entries[0].track.durationMs)
        assertEquals("yt:aqz-KE-bpKQ", p.entries[1].track.id)
        assertEquals("aqz-KE-bpKQ", p.entries[1].track.videoId)
        assertEquals(1, p.unresolved.size)
        assertEquals(5, p.unresolved[0].line)
        assertEquals("2:00", p.unresolved[0].text)

        // No header we know: plain "Artist - Title" lines.
        p = PlaylistIO.parse("Tarkan - Şımarık\nKiss Me\n", PlaylistFormat.CSV)
        assertEquals("lines", p.dialect)
        assertEquals(2, p.entries.size)
        assertEquals("Şımarık", p.entries[0].track.name)
        assertEquals("Tarkan", p.entries[0].track.artists[0].name)
        assertEquals("Kiss Me", p.entries[1].track.name)
        assertTrue(p.entries[1].track.artists.isEmpty())
    }

    @Test
    fun m3u() {
        val base = "C:\\Lists\\Party"
        val text = "#EXTM3U\n" +
            "#PLAYLIST:Parti\n" +
            "#EXTINF:210 tvg-id=\"x\",Artist - Song\n" +
            "songs\\a.mp3\n" +
            "#EXTINF:-1,Other\n" +
            "..\\b.flac\n" +
            "D:\\Abs\\c.m4a\n" +
            "file:///E:/With%20Space/d%C3%BC.mp3\n" +
            "#EXTINF:100,Band - Track\n" +
            "https://open.spotify.com/intl-tr/track/4uLU6hMCjMI75M1A2tKUQC?si=abc\n" +
            "spotify:track:6rqhFgbbKwnb9MLmUQDhG6\n" +
            "https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv\n" +
            "https://example.com/stream.mp3\n" +
            "#EXTINF:50,Named - Stream\n" +
            "https://example.com/other.mp3\n" +
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fe.mp3\n" +
            "/storage/emulated/0/Music/f.opus\n"
        val p = PlaylistIO.parse(text, PlaylistFormat.M3U, base)
        assertEquals("Parti", p.name)
        assertEquals(9, p.entries.size)
        val e = p.entries
        assertEquals("C:/Lists/Party/songs/a.mp3", e[0].localPath)
        assertEquals("Song", e[0].track.name)
        assertEquals("Artist", e[0].track.artists[0].name)
        assertEquals(210000, e[0].track.durationMs)
        assertEquals("C:/Lists/b.flac", e[1].localPath)
        assertEquals("Other", e[1].track.name)
        assertEquals(0, e[1].track.durationMs)
        assertEquals("D:/Abs/c.m4a", e[2].localPath)
        assertEquals("c", e[2].track.name)
        assertEquals("E:/With Space/dü.mp3", e[3].localPath)
        assertEquals(sp1, e[4].track.id)
        assertEquals("Track", e[4].track.name)
        assertEquals(100000, e[4].track.durationMs)
        assertEquals("spotify:track:6rqhFgbbKwnb9MLmUQDhG6", e[5].track.id)
        assertEquals("", e[5].track.name)
        assertEquals(PlaylistIO.importId("Stream", "Named"), e[6].track.id)
        assertTrue(e[7].localPath.startsWith("content://"))
        assertEquals("e", e[7].track.name)
        assertEquals("/storage/emulated/0/Music/f.opus", e[8].localPath)
        assertEquals(2, p.unresolved.size) // the album link, the unnamed unknown URL
        assertEquals(12, p.unresolved[0].line)
        assertEquals(13, p.unresolved[1].line)
    }

    @Test
    fun xspfAndJsonDetails() {
        val xspf = """<?xml version="1.0"?><playlist version="1" xmlns="http://xspf.org/ns/0/"><title>A &amp; B &#x15F;</title>
            <trackList><track><title><![CDATA[Cd & Ata]]></title><creator>X</creator><duration>1000</duration></track>
            <track><location>https://example.com/x</location></track>
            <track><location>file:///storage/emulated/0/Music/z.mp3</location></track></trackList></playlist>"""
        val p = PlaylistIO.parse(xspf, PlaylistFormat.XSPF)
        assertEquals("A & B ş", p.name)
        assertEquals(2, p.entries.size)
        assertEquals("Cd & Ata", p.entries[0].track.name)
        assertEquals(PlaylistIO.importId("Cd & Ata", "X"), p.entries[0].track.id)
        assertEquals(1000, p.entries[0].track.durationMs)
        assertEquals("/storage/emulated/0/Music/z.mp3", p.entries[1].localPath)
        assertEquals("z", p.entries[1].track.name)
        assertEquals(listOf(ImportProblem(2, "https://example.com/x")), p.unresolved)

        // JSON: a bare array, string artists, a string album, a local id with its file, a row with nothing.
        val j = PlaylistIO.parse(
            """[{"title":"T","artist":"A","album":"Al"},{"id":"local:abc","name":"L","file":"/sdcard/Music/l.mp3"},{"x":1},
               {"id":"yt:aqz-KE-bpKQ","name":"V","artists":["Ch"]}]""",
            PlaylistFormat.JSON,
        )
        assertEquals(3, j.entries.size)
        assertEquals("T", j.entries[0].track.name)
        assertEquals("A", j.entries[0].track.artists[0].name)
        assertEquals("Al", j.entries[0].track.album.name)
        assertEquals("", j.entries[1].track.id)
        assertEquals("/sdcard/Music/l.mp3", j.entries[1].localPath)
        assertEquals("aqz-KE-bpKQ", j.entries[2].track.videoId)
        assertEquals(1, j.unresolved.size)
        assertEquals(3, j.unresolved[0].line)
        // Not JSON at all: nothing.
        assertTrue(PlaylistIO.parse("<html>", PlaylistFormat.JSON).entries.isEmpty())
    }

    @Test
    fun helpers() {
        assertEquals(205000, PlaylistIO.parseDurationMs("3:25", false))
        assertEquals(3723000, PlaylistIO.parseDurationMs("1:02:03", false))
        assertEquals(205000, PlaylistIO.parseDurationMs("205", false))
        assertEquals(205000, PlaylistIO.parseDurationMs("205000", false)) // too long for seconds: milliseconds
        assertEquals(205000, PlaylistIO.parseDurationMs("205000", true))
        assertEquals(3500, PlaylistIO.parseDurationMs("3,5", false))
        assertEquals(0, PlaylistIO.parseDurationMs("", false))
        assertEquals(0, PlaylistIO.parseDurationMs("abc", false))
        assertEquals(0, PlaylistIO.parseDurationMs("-3", false))
        assertEquals(1682936430L, PlaylistIO.parseIsoTime("2023-05-01T10:20:30Z"))
        assertEquals(1682936430L, PlaylistIO.parseIsoTime("2023-05-01 10:20:30"))
        assertEquals(1682936430L, PlaylistIO.parseIsoTime("2023-05-01T13:20:30+03:00"))
        assertEquals(1682936430L, PlaylistIO.parseIsoTime("2023-05-01T05:20:30-0500"))
        assertEquals(1682899200L, PlaylistIO.parseIsoTime("2023-05-01"))
        assertEquals(0L, PlaylistIO.parseIsoTime("yesterday"))
        assertEquals(0L, PlaylistIO.parseIsoTime(""))
        assertEquals("file:///C:/A%20B/%C5%9F.mp3", PlaylistIO.fileUri("C:\\A B\\ş.mp3"))
        assertEquals("C:/A B/ş.mp3", PlaylistIO.pathFromFileUri("file:///C:/A%20B/%C5%9F.mp3"))
        assertEquals("file:///storage/emulated/0/A%20B/%C5%9F.mp3", PlaylistIO.fileUri("/storage/emulated/0/A B/ş.mp3"))
        assertEquals("/storage/emulated/0/A B/ş.mp3", PlaylistIO.pathFromFileUri("file:///storage/emulated/0/A%20B/%C5%9F.mp3"))
        assertEquals("file://nas/music/x.mp3", PlaylistIO.fileUri("\\\\nas\\music\\x.mp3"))
        assertEquals("//nas/music/x.mp3", PlaylistIO.pathFromFileUri("file://nas/music/x.mp3"))
        assertEquals("C:/x.mp3", PlaylistIO.pathFromFileUri("file://localhost/C:/x.mp3"))
        assertEquals("", PlaylistIO.pathFromFileUri("http://x"))
        assertEquals(PlaylistFormat.M3U, PlaylistIO.formatFromName("a.M3U"))
        assertEquals(PlaylistFormat.M3U, PlaylistIO.formatFromName("a.m3u8"))
        assertEquals(PlaylistFormat.CSV, PlaylistIO.formatFromName("a.csv"))
        assertEquals(PlaylistFormat.CSV, PlaylistIO.formatFromName("a.tsv"))
        assertEquals(PlaylistFormat.XSPF, PlaylistIO.formatFromName("a.xspf"))
        assertEquals(PlaylistFormat.JSON, PlaylistIO.formatFromName("a.json"))
        assertNull(PlaylistIO.formatFromName("a.txt"))
        assertEquals(PlaylistFormat.M3U, PlaylistIO.sniff("#EXTM3U\n"))
        assertEquals(PlaylistFormat.JSON, PlaylistIO.sniff("  {\"a\":1}"))
        assertEquals(PlaylistFormat.XSPF, PlaylistIO.sniff("<?xml version=\"1.0\"?><playlist>"))
        assertEquals(PlaylistFormat.CSV, PlaylistIO.sniff("a,b"))
        // Encodings.
        assertEquals("abc", PlaylistIO.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())))
        assertEquals("şa", PlaylistIO.decodeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x5F, 0x01, 0x61, 0x00))) // UTF-16 LE
        assertEquals("şa", PlaylistIO.decodeText(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x01, 0x5F, 0x00, 0x61))) // UTF-16 BE
        assertEquals("düz", PlaylistIO.decodeText("düz".toByteArray()))
        val ansi = PlaylistIO.decodeText(byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte()))
        assertTrue(ansi.startsWith("caf") && ansi.length == 4)
        // importId: ASCII lower case only, '|' between.
        assertEquals(PlaylistIO.importId("song", "artist"), PlaylistIO.importId("SONG", "Artist"))
        assertTrue(PlaylistIO.importId("a", "b").matches(Regex("import:[0-9a-f]{16}")))
    }

    @Test
    fun readStreams() {
        // UTF-16 LE M3U without #EXTM3U, the name from the file name.
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "/x.mp3".toByteArray(Charsets.UTF_16LE)
        var p = PlaylistIO.read(ByteArrayInputStream(utf16), "Yolculuk.m3u")
        assertEquals(PlaylistFormat.M3U, p.format)
        assertEquals("Yolculuk", p.name)
        assertEquals("/x.mp3", p.entries.single().localPath)
        // Unknown extension: by content.
        p = PlaylistIO.read(ByteArrayInputStream("#EXTM3U\nhttps://youtu.be/aqz-KE-bpKQ\n".toByteArray()), "list.txt")
        assertEquals(PlaylistFormat.M3U, p.format)
        assertEquals("yt:aqz-KE-bpKQ", p.entries.single().track.id)
        // A failing stream throws.
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("gone")
        }
        var threw = false
        try {
            PlaylistIO.read(broken, "a.m3u")
        } catch (_: IOException) {
            threw = true
        }
        assertTrue(threw)
        // Writing to a stream: UTF-8 bytes.
        val out = ByteArrayOutputStream()
        PlaylistIO.write(PlaylistFormat.CSV, "x", sampleRows(), out)
        assertTrue(out.toByteArray().size > 3 && out.toByteArray()[0] == 0xEF.toByte())
        assertFalse(PlaylistIO.isPlaylistFile("a.mp3"))
        assertTrue(PlaylistIO.isPlaylistFile("content://x/document/primary%3AList.m3u8"))
    }

    @Test
    fun performance() {
        val rows = (0 until 10000).map { i ->
            ExportRow(
                Track(
                    id = if (i % 2 == 1) PlaylistIO.importId("Song $i", "Artist") else sp1,
                    name = "Song $i", artists = listOf(ArtistRef("", "Artist")), durationMs = 200000 + i,
                ),
            )
        }
        for (f in listOf(PlaylistFormat.CSV, PlaylistFormat.M3U, PlaylistFormat.XSPF, PlaylistFormat.JSON)) {
            val t0 = System.nanoTime()
            val text = PlaylistIO.write(f, "Big", rows)
            val p = PlaylistIO.parse(text, f)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("  ${PlaylistIO.extension(f)}: 10000 songs written + read in $ms ms")
            assertEquals(10000, p.entries.size)
            assertTrue(ms < 5000)
        }
    }
}
