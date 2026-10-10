package com.app.shadetube.stats

import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Port of the import part of tests/stats: Spotify history formats, the ZIP, dedupe, the imported file. */
class HistoryImportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun countImported(p: PlayList) = p.countImported()

    @Test
    fun parsingHelpers() {
        assertEquals(utc(2024, 1, 2, 20, 4, 40), HistoryImport.parseUtc("2024-01-02T20:04:40Z"))
        assertEquals(utc(2024, 1, 2, 20, 4), HistoryImport.parseUtc("2024-01-02 20:04"))
        assertEquals(utc(2024, 1, 2, 20, 4, 40), HistoryImport.parseUtc("2024-01-02T20:04:40.123Z"))
        assertEquals(utc(2024, 1, 2, 20, 4, 40), HistoryImport.parseUtc("2024-01-02T23:04:40+03:00"))
        assertEquals(-1, HistoryImport.parseUtc("2024-13-02 20:04"))
        assertEquals(-1, HistoryImport.parseUtc(""))
        assertEquals(-1, HistoryImport.parseUtc("2024-01-02"))
        assertTrue(HistoryImport.isHistoryEntry("Spotify Extended Streaming History/Streaming_History_Audio_2023-2024_0.json"))
        assertTrue(HistoryImport.isHistoryEntry("MyData/endsong_3.json"))
        assertTrue(HistoryImport.isHistoryEntry("Spotify Account Data\\StreamingHistory_music_0.json"))
        assertTrue(HistoryImport.isHistoryEntry("MyData/StreamingHistory0.json"))
        assertFalse(HistoryImport.isHistoryEntry("Spotify Extended Streaming History/Streaming_History_Video_2024.json"))
        assertFalse(HistoryImport.isHistoryEntry("Spotify Account Data/StreamingHistory_podcast_0.json"))
        assertFalse(HistoryImport.isHistoryEntry("Spotify Account Data/YourLibrary.json"))
        assertFalse(HistoryImport.isHistoryEntry("ReadMeFirst_ExtendedStreamingHistory.pdf"))
        // ASCII-lower-cased FNV-1a of name|artist: the same id for "X" and "x", stable across runs.
        assertEquals(HistoryImport.importId("Firuze", "Sezen Aksu"), HistoryImport.importId("FIRUZE", "sezen aksu"))
        assertTrue(HistoryImport.importId("Firuze", "Sezen Aksu").matches(Regex("import:[0-9a-f]{16}")))
    }

    @Test
    fun documents() {
        val rows = ArrayList<ListenStats.ImportRow>()
        val skipped = IntArray(1)
        assertEquals(HistoryImport.Format.Extended, HistoryImport.parse(fixture("Streaming_History_Audio_2023-2024_0.json").readText(), rows, skipped))
        assertEquals(7, rows.size)
        assertEquals(3, skipped[0]) // podcast, no song, audiobook
        val r0 = rows[0]
        assertEquals("Şımarık", r0.track.name)
        assertEquals(listOf(ArtistRef("", "Tarkan")), r0.track.artists)
        assertEquals("Ölürüm Sana", r0.track.albumName)
        assertEquals("spotify:track:0000000000000000000001", r0.track.id)
        assertEquals(utc(2023, 12, 31, 20, 3, 20), r0.endedAt)
        assertEquals(200_000, r0.listenedMs)
        val n = rows.size
        assertEquals(HistoryImport.Format.Account, HistoryImport.parse(fixture("StreamingHistory_music_0.json").readText(), rows, skipped))
        assertEquals(n + 3, rows.size)
        assertEquals("Firuze", rows.last().track.name)
        assertTrue(rows.last().track.id.startsWith("import:"))
        assertEquals(utc(2024, 1, 5, 9, 15), rows.last().endedAt)
        assertEquals(HistoryImport.Format.Account, HistoryImport.parse(fixture("StreamingHistory_podcast_0.json").readText(), rows, skipped))
        assertEquals(n + 3, rows.size)
        assertEquals(4, skipped[0])
        assertEquals(HistoryImport.Format.Unknown, HistoryImport.parse("""[{"id":1},{"name":"x"}]""", rows, skipped))
        assertEquals(HistoryImport.Format.Unknown, HistoryImport.parse("""{"ts":"x"}""", rows, skipped))
        var threw = false
        try {
            HistoryImport.parse("{not json", rows, skipped)
        } catch (_: HistoryImport.NotJsonException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(n + 3, rows.size)
    }

    private fun readZip(): HistoryImport.Result {
        // From the ZIP (deflate, written by PowerShell's Compress-Archive): the audio + music files; video, podcast
        // and the read-me are left out. Plus a file that isn't one and two that can't be read: reported, the rest
        // imported.
        val junk = File(tmp.root, "notes.json").apply { writeText("""{"hello":"world"}""") }
        var calls = 0
        var lastDone = -1
        var lastTotal = -1
        val zip = HistoryImport.readFiles(
            listOf(fixture("my_spotify_data.zip"), junk, File(tmp.root, "missing.zip"), File(tmp.root, "notes.txt")),
            { done, total ->
                calls++
                lastDone = done
                lastTotal = total
            },
        )
        assertEquals(2, zip.documents)
        assertEquals(10, zip.rows.size)
        assertEquals(3, zip.skippedRows)
        assertEquals(1, zip.unrecognized) // notes.json
        assertEquals(2, zip.errors.size) // missing.zip, notes.txt
        assertTrue(calls == 4 && lastDone == 3 && lastTotal == 3)
        return zip
    }

    @Test
    fun importFlow() {
        val zip = readZip()
        val dir = tmp.root
        val file = File(dir, "import.json")
        val importedFile = ListenStats.importedFileFor(file)
        assertEquals(File(dir, "import-imported.json"), importedFile)
        val s = ListenStats(file)
        s.load()
        assertTrue(s.canImport && s.importedPlayCount == 0)
        // A play heard here at the same time as one in the files is not doubled.
        val kuzu = track("spotify:track:0000000000000000000003", "Kuzu Kuzu", "Tarkan", "Karma", 0, "spotify:artist:tarkan", "spotify:album:karma")
        s.addPlay(kuzu, utc(2024, 1, 3, 21, 0), 180_000)
        assertTrue(s.save())
        run {
            val base = s.beginImport()
            assertTrue(s.isImporting && !s.canImport && base.plays.size == 1 && base.importedFile == importedFile)
            val r = ListenStats.buildImport(base, zip.rows)
            // 10 rows: a 0.5 s one is too short; Kuzu Kuzu (both files) = the play heard here; the account file's
            // Gülpembe ends 40 s before the extended one with the same length = the same play.
            assertTrue(r.ok)
            assertEquals(10, r.rows)
            assertEquals(1, r.tooShort)
            assertEquals(3, r.duplicates)
            assertEquals(6, r.added)
            assertTrue(importedFile.exists())
            assertTrue(s.finishImport(r))
            assertTrue(!s.isImporting && s.canImport)
            assertTrue(s.importedPlayCount == 6 && countImported(s.playsCopy()) == 6 && s.playCount == 7)
        }
        run {
            val all = s.summarize(StatsPeriod.All, utc(2024, 12, 1))
            // Şımarık x2, Gülpembe 280 s (+ a 4 s skip), Uzun Kayıt, Firuze (imported) + Kuzu Kuzu (here).
            assertEquals(6, all.streams)
            assertEquals("Şımarık", all.topTracks[0].track.name)
            assertEquals(2, all.topTracks[0].streams)
            assertEquals("Tarkan", all.topArtists[0].artist.name)
            assertEquals(3, all.topArtists[0].streams)
            assertEquals("spotify:artist:tarkan", all.topArtists[0].artist.id) // merged with the play heard here
        }
        // Importing the same data again adds nothing; the imported file stays as it was.
        run {
            val before = importedFile.readText()
            val base = s.beginImport()
            val r = ListenStats.buildImport(base, zip.rows)
            assertTrue(r.ok && r.added == 0 && r.duplicates == 9 && r.tooShort == 1)
            assertFalse(s.finishImport(r))
            assertTrue(s.importedPlayCount == 6 && !s.isImporting)
            assertEquals(before, importedFile.readText())
        }
        // Regular saves never touch the imported file; the main file holds only this device's plays.
        run {
            val before = importedFile.readText()
            s.addPlay(kuzu, utc(2024, 6, 1, 12), 180_000)
            assertTrue(s.save())
            assertEquals(before, importedFile.readText())
            val j = parseJsonOrNull(file.readText()) as JsonObject
            assertEquals(2, (j["plays"] as JsonArray).size)
            val snap = ListenStats.readFile(file)
            assertTrue(snap.plays.size == 8 && countImported(snap.plays) == 6 && !snap.importedKeepFile)
            assertTrue(snap.plays.isSortedByStart())
            val r = ListenStats(file)
            r.adopt(snap)
            assertTrue(r.importedPlayCount == 6 && r.playCount == 8 && !r.isDirty)
            assertEquals(7, r.summarize(StatsPeriod.All, utc(2024, 12, 1)).streams)
        }
        // Plays stored while an import runs survive it; a superseded import's result is dropped.
        run {
            val base = s.beginImport()
            s.addPlay(kuzu, utc(2024, 7, 1, 12), 180_000)
            fun row(endedAt: Long) = ListenStats.ImportRow(
                ListenTrack(name = "Yeni Şarkı", artists = listOf(ArtistRef("", "Yeni Sanatçı"))), endedAt, 120_000,
            )
            val r = ListenStats.buildImport(base, listOf(row(utc(2022, 3, 3, 3))))
            assertTrue(r.ok && r.added == 1)
            assertTrue(s.finishImport(r))
            assertTrue(s.importedPlayCount == 7 && s.playCount == 10)
            val plays = s.playsCopy()
            assertTrue((0 until plays.size).any { !plays.imported(it) && plays.startedAt(it) == utc(2024, 7, 1, 12) })

            // Superseded (a clear or a failure cancelled it) after its worker wrote the file: the store's state is
            // written back by the next save.
            val base2 = s.beginImport()
            val r2 = ListenStats.buildImport(base2, listOf(row(utc(2022, 4, 4, 4))))
            s.cancelImport()
            assertTrue(!s.finishImport(r2) && s.importedPlayCount == 7 && s.isDirty)
            assertEquals(8, countImported(ListenStats.readFile(file).plays))
            assertTrue(s.save())
            assertEquals(7, countImported(ListenStats.readFile(file).plays))
        }
        // Removing the imported plays empties the file on the next save; this device's plays stay.
        run {
            s.removeImported()
            assertTrue(s.importedPlayCount == 0 && countImported(s.playsCopy()) == 0 && s.playCount == 3 && s.isDirty)
            assertTrue(s.save())
            val snap = ListenStats.readFile(file)
            assertTrue(snap.plays.size == 3 && countImported(snap.plays) == 0)
            assertTrue(File(importedFile.path + ".old").exists()) // the previous one is kept once
        }
        // Clear drops both histories.
        run {
            val base = s.beginImport()
            assertTrue(s.finishImport(ListenStats.buildImport(base, zip.rows)) && s.importedPlayCount == 6)
            s.clear(utc(2024, 12, 1))
            assertTrue(s.playCount == 0 && s.importedPlayCount == 0)
            assertTrue(s.save())
            assertTrue(ListenStats.readFile(file).plays.isEmpty())
        }
        // A broken imported file: kept as .bak, the previous copy used, this device's history unaffected. A locked
        // one: nothing imported this session and never replaced.
        run {
            val f2 = File(dir, "broken.json")
            val b = ListenStats(f2)
            b.load()
            b.addPlay(kuzu, utc(2024, 1, 1), 180_000)
            val base = b.beginImport()
            assertTrue(b.finishImport(ListenStats.buildImport(base, zip.rows)))
            val base2 = b.beginImport()
            val last = zip.rows.last()
            val one = listOf(ListenStats.ImportRow(last.track, last.endedAt + 86400, last.listenedMs))
            assertTrue(b.finishImport(ListenStats.buildImport(base2, one))) // 8 imported now, the first 7 in .old
            assertTrue(b.save())
            val imp = ListenStats.importedFileFor(f2)
            imp.writeText("{\"v\":1,\"tracks\":[")
            val snap = ListenStats.readFile(f2)
            assertTrue(snap.importedCorrupt && !snap.importedKeepFile)
            assertEquals(7, countImported(snap.plays))
            assertEquals(8, snap.plays.size)
            assertTrue(File(imp.path + ".bak").exists())
            val locked = whileLocked(imp) { ListenStats.readFile(f2) }
            assertTrue(locked.importedKeepFile && locked.plays.size == 1)
            val l = ListenStats(f2)
            l.adopt(locked)
            assertFalse(l.canImport)
            assertEquals(null, l.beginImport().importedFile)
        }
    }

    /** 300 000 imported plays over 30 000 songs: import, save / load, aggregations (printed; loose budget). */
    @Test
    fun importTiming() {
        fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6
        val file = File(tmp.root, "bigimport.json")
        val rows = ArrayList<ListenStats.ImportRow>(300_000)
        val first = utc(2016, 1, 1)
        for (i in 0 until 300_000) {
            val k = ((i.toLong() * 7919) % 30_000).toInt()
            rows.add(
                ListenStats.ImportRow(
                    ListenTrack(id = "spotify:track:${1_000_000 + k}", name = "Şarkı $k", artists = listOf(ArtistRef("", "Sanatçı ${k % 3_000}")),
                        albumName = "Albüm ${k % 9_000}"),
                    first + i.toLong() * 1000 + 240, // ~86 plays a day for 9.5 years
                    20_000L + (i % 220) * 1000,
                ),
            )
        }
        // A realistic document for the parser: one Spotify file of 16 000 rows (~12 MB).
        run {
            val doc = StringBuilder("[")
            for (i in 0 until 16_000) {
                if (i > 0) doc.append(',')
                doc.append("""{"ts":"2023-05-0${1 + i % 9}T12:00:00Z","platform":"windows","ms_played":180000,"conn_country":"TR","ip_addr":"0.0.0.0","master_metadata_track_name":"Şarkı $i","master_metadata_album_artist_name":"Sanatçı","master_metadata_album_album_name":"Albüm","spotify_track_uri":"spotify:track:4uLU6hMCjMI75M1A2tKUQC","episode_name":null,"episode_show_name":null,"spotify_episode_uri":null,"audiobook_title":null,"audiobook_uri":null,"audiobook_chapter_uri":null,"audiobook_chapter_title":null,"reason_start":"trackdone","reason_end":"trackdone","shuffle":false,"skipped":false,"offline":false,"offline_timestamp":null,"incognito_mode":false}""")
            }
            doc.append("]")
            val parsed = ArrayList<ListenStats.ImportRow>()
            val t0 = System.nanoTime()
            assertEquals(HistoryImport.Format.Extended, HistoryImport.parse(doc.toString(), parsed, IntArray(1)))
            assertEquals(16_000, parsed.size)
            println("  parse       %7.1f ms  (one 16 000-row file, %.1f MB)".format(ms(t0), doc.length / 1048576.0))
        }
        val s = ListenStats(file)
        s.load()
        var t0 = System.nanoTime()
        val base = s.beginImport()
        val r = ListenStats.buildImport(base, rows)
        val build = ms(t0)
        println("  build       %7.1f ms  (dedupe + write, %.1f MB)".format(build, ListenStats.importedFileFor(file).length() / 1048576.0))
        assertTrue(r.ok)
        assertEquals(300_000, r.added)
        t0 = System.nanoTime()
        assertTrue(s.finishImport(r))
        println("  install     %7.1f ms".format(ms(t0)))
        assertEquals(300_000, s.importedPlayCount)
        t0 = System.nanoTime()
        s.addPlay(track("x", "Yeni", "Biri", "Bir", 200_000), utc(2025, 9, 1), 60_000)
        val job = s.makeSaveJob()
        println("  snapshot    %7.1f ms  (a save with 300 000 imported plays around)".format(ms(t0)))
        assertTrue(job.plays.size == 1 && ListenStats.write(job))
        t0 = System.nanoTime()
        val snap = ListenStats.readFile(file)
        val read = ms(t0)
        println("  readFile    %7.1f ms".format(read))
        assertEquals(300_001, snap.plays.size)
        val l = ListenStats(file)
        l.adopt(snap)
        val clock = systemLocalClock()
        t0 = System.nanoTime()
        val all = l.summarize(StatsPeriod.All, utc(2025, 9, 2))
        val sumAll = ms(t0)
        println("  summarize   %7.1f ms  (all time)".format(sumAll))
        assertTrue(all.streams > 0)
        assertEquals(30_001, all.distinctTracks)
        t0 = System.nanoTime()
        val ys = l.years(clock)
        println("  years       %7.1f ms  (%d years)".format(ms(t0), ys.size))
        assertTrue(ys.size >= 10)
        t0 = System.nanoTime()
        val y = l.summarizeYear(2020, clock)
        val year = ms(t0)
        println("  year        %7.1f ms  (2020: %d streams)".format(year, y.streams))
        assertTrue(y.streams > 20_000)
        t0 = System.nanoTime()
        val h = l.heatmap(StatsPeriod.All, utc(2025, 9, 2), clock)
        val heat = ms(t0)
        println("  heatmap     %7.1f ms  (all time)".format(heat))
        assertTrue(h.totalMs > 0)
        // The C++ Debug budget (JVM unit tests run unoptimized at first).
        assertTrue("build + read = ${build + read} ms", build + read < 30_000)
        assertTrue("aggregations = ${sumAll + year + heat} ms", sumAll + year + heat < 4_000)
    }
}
