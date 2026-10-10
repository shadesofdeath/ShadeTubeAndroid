package com.app.shadetube.stats

import com.app.shadetube.catalog.ArtistRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Port of tests/stats (stats_test.cpp): rule, recording, seeks, aggregation, persistence, local time, heatmap. */
class ListenStatsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): File = tmp.root

    @Test
    fun streamRule() {
        assertFalse(Listen.counts(29_999, 200_000))
        assertTrue(Listen.counts(30_000, 200_000))
        assertTrue(Listen.counts(30_000, 0)) // unknown duration: 30 s
        assertFalse(Listen.counts(29_000, 0))
        assertTrue(Listen.counts(20_000, 40_000)) // short track: 50 %
        assertFalse(Listen.counts(19_999, 40_000))
        assertTrue(Listen.counts(29_500, 59_000))
        assertFalse(Listen.counts(29_000, 59_000))
        assertFalse(Listen.counts(29_999, 60_000)) // 60 s is not "shorter than 60 s"
        assertTrue(Listen.counts(5_000, 10_000))
    }

    @Test
    fun recording() {
        val s = ListenStats(File(dir(), "rec.json"))
        s.load()
        val sim = Sim(s)
        val a = track("spotify:track:a", "Şımarık", "Tarkan", "Ölürüm Sana", 234_000, "spotify:artist:tarkan", "spotify:album:olurum")
        val b = track("spotify:track:b", "Kuzu Kuzu", "Tarkan", "Karma", 223_000, "spotify:artist:tarkan", "spotify:album:karma")
        s.trackStarted(a, 0, 1_700_000_000, sim.wall)
        assertTrue(s.isActive)
        sim.wall += 700 // resolving + buffering: no position yet
        sim.sample(false)
        sim.play(40)
        assertEquals(40_000, s.currentListenedMs)
        sim.pause(60) // a minute paused: nothing counts
        assertEquals(40_000, s.currentListenedMs)
        sim.resume()
        sim.play(10)
        assertEquals(50_000, s.currentListenedMs)
        // Forward seek +60 s right after a sample: only the 100 ms of audio before the jump counts (+1/40 jitter).
        sim.seekTo(sim.pos + 60_000)
        assertEquals(50_102, s.currentListenedMs)
        sim.play(4)
        assertEquals(54_102, s.currentListenedMs)
        // Seek detected by the housekeeping sample instead (no playerChanged): at most the elapsed 2 s count.
        sim.wall += 2000
        sim.pos += 30_000
        sim.sample(true)
        assertEquals(56_152, s.currentListenedMs)
        // Backward seek (not back to the top: the same play).
        sim.seekTo(60_000)
        assertEquals(56_252, s.currentListenedMs)
        assertEquals(0, s.playCount)
        sim.play(6)
        assertEquals(62_252, s.currentListenedMs)
        // Buffering stall: wall runs, position does not -> nothing; then it resumes normally.
        repeat(5) {
            sim.wall += 2000
            sim.sample(false)
        }
        sim.resume()
        sim.play(2)
        assertEquals(64_252, s.currentListenedMs)
        // Player re-resolves the same track at the same spot ("Yanlış eşleşme?"): same play.
        sim.wall += 300
        s.trackStarted(a, sim.pos, 1_700_000_100, sim.wall)
        assertEquals(0, s.playCount)
        sim.play(2)
        assertEquals(66_252, s.currentListenedMs)
        // Next track 1.5 s after the last sample while playing: the tail (1.5 s) is credited to A.
        sim.wall += 1500
        s.trackStarted(b, 0, 1_700_000_200, sim.wall)
        assertEquals(1, s.playCount)
        assertEquals(67_752, s.lastPlay()!!.listenedMs)
        assertEquals(1_700_000_000, s.lastPlay()!!.startedAt)
        assertTrue(s.isDirty)
        // B: 20 s, then repeat (same track from the top) -> a new play for B.
        sim.pos = 0
        sim.play(20)
        sim.pos = 0
        s.trackStarted(b, 0, 1_700_000_300, sim.wall) // restart: position 0
        assertEquals(2, s.playCount)
        assertEquals(20_000, s.lastPlay()!!.listenedMs)
        // Skipped right away (< 1 s) -> not stored.
        s.trackStarted(a, 0, 1_700_000_400, sim.wall + 300)
        assertEquals(2, s.playCount)
        // Paused then stopped: no tail credit.
        sim.wall += 300
        sim.pos = 0
        sim.play(8)
        sim.pause(4)
        s.stop(sim.wall + 10_000)
        assertFalse(s.isActive)
        assertEquals(3, s.playCount)
        assertEquals(8_000, s.lastPlay()!!.listenedMs)
        // A tail never runs past the track's end.
        val shortTrack = track("mb:short", "Intro", "Duman", "Belki Alışman Lazım", 20_000)
        s.trackStarted(shortTrack, 0, 1_700_000_500, sim.wall)
        sim.pos = 0
        sim.play(18)
        s.stop(sim.wall + 4000) // ended 4 s after the last sample, but only 2 s were left
        assertEquals(20_000, s.lastPlay()!!.listenedMs)
        // The duration learned from the decoder fills an unknown catalog duration (short track -> 50 % rule).
        val preview = track("preview:x", "Aşkın Olayım", "Simge", "", 0)
        s.trackStarted(preview, 0, 1_700_000_600, sim.wall)
        sim.wall += 2000
        s.progress(2000, true, 50_000, sim.wall, sim.unix())
        assertEquals(50_000, s.tracksCopy().first { it.id == "preview:x" }.durationMs)
    }

    @Test
    fun seeks() {
        val s = ListenStats(File(dir(), "seek.json"))
        s.load()
        val sim = Sim(s)
        val a = track("sp:a", "Kısa Kısa", "Altın Gün", "Gece", 303_000)
        s.trackStarted(a, 0, sim.unix(), sim.wall)
        sim.play(10)
        assertEquals(10_000, s.currentListenedMs)
        // Right arrow (+5 s) tapped six times ~300 ms apart: each tap credits the 300 ms (+1/40 jitter), never the 5 s.
        sim.jumps(6, 5_000, 300)
        assertEquals(10_000L + 6 * 307, s.currentListenedMs)
        // Held key: auto-repeat +5 s every 33 ms skims 200 s of the track: 33 ms each.
        var before = s.currentListenedMs
        sim.jumps(40, 5_000, 33)
        assertEquals(40L * 33, s.currentListenedMs - before)
        // SponsorBlock-style skip: 1 s of audio, then a +4 s jump inside the same sample interval: only the 1 s.
        before = s.currentListenedMs
        sim.jumps(1, 1_000 + 4_000, 1_000)
        assertEquals(1_025, s.currentListenedMs - before)
        assertFalse(Listen.counts(s.currentListenedMs, 303_000)) // ~14 s of real audio: no stream
        assertEquals(0, s.playCount)

        // Previous past 3 s restarts the track with seek(0) (no track change): that play ends, a new one starts.
        val r = ListenStats(File(dir(), "restart.json"))
        r.load()
        val rs = Sim(r)
        val song = track("sp:b", "Yolcu", "Altın Gün", "Gece", 285_000)
        r.trackStarted(song, 0, rs.unix(), rs.wall)
        rs.play(200)
        rs.wall += 500
        rs.pos = 0
        rs.sample(true) // seek(0) notifies at once
        assertEquals(1, r.playCount)
        assertEquals(200_500, r.lastPlay()!!.listenedMs) // + the 500 ms before it
        assertTrue(r.isActive && r.currentListenedMs == 0L)
        rs.play(40)
        // A seek back that is not to the top of the track stays the same play.
        rs.seekTo(20_000)
        assertEquals(1, r.playCount)
        rs.play(10)
        r.stop(rs.wall)
        assertEquals(2, r.playCount)
        assertEquals(50_100, r.lastPlay()!!.listenedMs)
        val plays = r.playsCopy()
        assertTrue(plays.startedAt(1) > plays.startedAt(0))
        val sum = r.summarize(StatsPeriod.All, rs.unix())
        assertEquals(2, sum.streams)
        assertEquals(2, sum.topTracks[0].streams)
    }

    @Test
    fun aggregation() {
        val s = ListenStats(File(dir(), "agg.json"))
        s.load()
        val now = 1_760_000_000L
        val day = 86400L
        val a = track("spotify:track:a", "Şımarık", "Tarkan", "Ölürüm Sana", 234_000, "spotify:artist:tarkan", "spotify:album:olurum")
        val aLocal = track("local:1", "ŞIMARIK", "tarkan", "Ölürüm Sana", 0) // same song from a local file
        val b = track("mb:b", "Gülpembe", "Barış Manço", "Sahibinden İhtiyar", 280_000, "11111111-2222-3333-4444-555555555555")
        val c = track("mb:c", "Bohemian Rhapsody", "Queen", "A Night at the Opera", 354_000)
        var duet = track("spotify:track:d", "Yolla", "Tarkan", "Yolla", 208_000, "spotify:artist:tarkan")
        duet = duet.copy(artists = duet.artists + ArtistRef("spotify:artist:x", "Sezen Aksu"))

        s.addPlay(a, now - 1 * day, 200_000)
        s.addPlay(aLocal, now - 2 * day, 150_000)
        s.addPlay(a, now - 3 * day, 10_000) // skipped: time counts, stream does not
        s.addPlay(b, now - 2 * day, 280_000)
        s.addPlay(c, now - 10 * day, 354_000)
        s.addPlay(c, now - 12 * day, 300_000)
        s.addPlay(c, now - 20 * day, 100_000)
        s.addPlay(b, now - 40 * day, 280_000)
        s.addPlay(duet, now - 5 * day, 60_000)
        s.addPlay(a, now - 400 * day, 60_000)
        s.addPlay(a, now - 1 * day, 500) // < 1 s: ignored
        assertEquals(10, s.playCount)
        assertEquals(4, s.tracksCopy().size) // a + aLocal merged

        val w = s.summarize(StatsPeriod.Week, now)
        assertEquals(now - 7 * day, w.periodStart)
        assertEquals(200_000L + 150_000 + 10_000 + 280_000 + 60_000, w.listenedMs)
        assertEquals(4, w.streams)
        assertEquals(3, w.distinctTracks)
        assertEquals(3, w.distinctArtists) // Tarkan, Barış Manço, Sezen Aksu
        assertEquals(3, w.topTracks.size)
        assertEquals("Şımarık", w.topTracks[0].track.name)
        assertEquals(2, w.topTracks[0].streams)
        assertEquals(350_000, w.topTracks[0].listenedMs)
        assertEquals("spotify:track:a", w.topTracks[0].track.id) // the first sighting's id
        assertEquals("Gülpembe", w.topTracks[1].track.name) // 1 stream, more time than Yolla
        assertEquals("Tarkan", w.topArtists[0].artist.name)
        assertEquals(3, w.topArtists[0].streams)
        assertEquals("spotify:artist:tarkan", w.topArtists[0].artist.id)
        assertEquals("Ölürüm Sana", w.topAlbums[0].album.name)
        assertEquals(2, w.topAlbums[0].streams)
        assertEquals("spotify:album:olurum", w.topAlbums[0].album.id)
        assertEquals("Tarkan", w.topAlbums[0].artist)
        assertEquals(4, w.recent.size)
        assertEquals(now - 1 * day, w.recent[0].startedAt)
        assertEquals(now - 5 * day, w.recent[3].startedAt)

        val m = s.summarize(StatsPeriod.Month, now)
        assertEquals(7, m.streams)
        assertEquals("Bohemian Rhapsody", m.topTracks[0].track.name)
        assertEquals(3, m.topTracks[0].streams)
        assertEquals(4, m.distinctArtists)

        val all = s.summarize(StatsPeriod.All, now)
        assertEquals(now - 400 * day, all.periodStart)
        assertEquals(9, all.streams)
        // 3 streams each: Bohemian Rhapsody wins on time (754 s vs 410 s).
        assertEquals("Bohemian Rhapsody", all.topTracks[0].track.name)
        assertEquals(3, all.topTracks[0].streams)
        assertEquals("Şımarık", all.topTracks[1].track.name)
        assertEquals(3, all.topTracks[1].streams)
        val top2 = s.summarize(StatsPeriod.All, now, 2, 3)
        assertTrue(top2.topTracks.size == 2 && top2.recent.size == 3 && top2.distinctTracks == 4)

        // The current play is included once it counts (and then it is the most recent).
        val sim = Sim(s)
        s.trackStarted(b, 0, now - 60, sim.wall)
        sim.play(20)
        assertEquals(4, s.summarize(StatsPeriod.Week, now).streams)
        assertEquals(w.listenedMs + 20_000, s.summarize(StatsPeriod.Week, now).listenedMs)
        sim.play(20)
        val live = s.summarize(StatsPeriod.Week, now)
        assertEquals(5, live.streams)
        assertEquals("Gülpembe", live.recent[0].track.name)
        assertEquals(now - 60, live.recent[0].startedAt)

        // A soundtrack: one album id, a different first artist on every track -> one album ("various artists"). A
        // download of another song of it without the id joins through name + first artist.
        val v = ListenStats(File(dir(), "various.json"))
        v.load()
        for (i in 0 until 5)
            v.addPlay(track("sp:ost$i", "Parça $i", "Sanatçı $i", "Ayla (Film Müziği)", 200_000, "", "spotify:album:ayla"), now - 100 + i, 60_000)
        v.addPlay(track("dl:9", "Parça 9", "Sanatçı 1", "Ayla (Film Müziği)", 200_000), now - 50, 60_000)
        val vs = v.summarize(StatsPeriod.All, now)
        assertEquals(1, vs.topAlbums.size)
        assertEquals(6, vs.topAlbums[0].streams)
        assertTrue(vs.topAlbums[0].variousArtists)
        assertEquals("spotify:album:ayla", vs.topAlbums[0].album.id)
        assertFalse(w.topAlbums[0].variousArtists) // a regular album stays one artist's

        // An empty store.
        val empty = ListenStats(File(dir(), "empty.json"))
        empty.load()
        val e = empty.summarize(StatsPeriod.All, now)
        assertTrue(e.periodStart == 0L && e.streams == 0 && e.listenedMs == 0L && e.topTracks.isEmpty() && e.recent.isEmpty())
    }

    @Test
    fun persistence() {
        val file = File(dir(), "listening.json")
        val now = 1_760_000_000L
        val a = track("spotify:track:a", "Şımarık", "Tarkan", "Ölürüm Sana", 234_000, "spotify:artist:tarkan", "spotify:album:olurum")
        val b = track("mb:b", "Gülpembe", "Barış Manço", "Sahibinden İhtiyar", 280_000)
        run {
            val s = ListenStats(file)
            s.load()
            assertTrue(s.isLoaded)
            assertTrue(s.save()) // nothing yet: an empty history
            s.addPlay(a, now - 100, 200_000)
            s.addPlay(b, now - 50, 40_000)
            s.addPlay(a, now - 10, 5_000)
            val sim = Sim(s)
            s.trackStarted(b, 0, now - 5, sim.wall) // the current play is saved too (so far)
            sim.play(12)
            assertTrue(s.isDirty)
            assertTrue(s.save())
            assertFalse(s.isDirty)
        }
        assertTrue(file.exists())
        assertFalse(File(file.path + ".tmp").exists())
        run {
            val snap = ListenStats.readFile(file)
            assertFalse(snap.corrupt)
            assertEquals(2, snap.index.tracks.size)
            assertEquals(4, snap.plays.size)
            val s = ListenStats(file)
            s.adopt(snap)
            assertEquals(4, s.playCount)
            assertFalse(s.isDirty)
            val sum = s.summarize(StatsPeriod.All, now)
            assertEquals(200_000L + 40_000 + 5_000 + 12_000, sum.listenedMs)
            assertEquals(2, sum.streams)
            val t = sum.topTracks[0].track
            assertEquals("Şımarık", t.name)
            assertEquals(1, t.artists.size)
            assertEquals("spotify:artist:tarkan", t.artists[0].id)
            assertEquals("spotify:album:olurum", t.album.id)
            assertEquals(234_000, t.durationMs)
            assertEquals("https://img.example/Ölürüm Sana.jpg", t.album.images[0].url)
            // A second adopt is ignored (only the first load applies).
            s.adopt(snap)
            assertEquals(4, s.playCount)
        }
        // Plays recorded before the async load finished are merged on top, and the store becomes dirty.
        run {
            val s = ListenStats(file)
            s.addPlay(a, now + 10, 60_000)
            val sim = Sim(s)
            s.trackStarted(a, 0, now + 100, sim.wall)
            sim.play(6)
            assertFalse(s.save()) // not loaded yet: never clobbers the file
            s.adopt(ListenStats.readFile(file))
            assertEquals(5, s.playCount)
            assertTrue(s.isDirty)
            assertTrue(s.isActive && s.currentListenedMs == 6_000L)
            assertEquals(2, s.tracksCopy().size) // merged, not duplicated
            assertEquals(2, s.summarize(StatsPeriod.All, now + 200).topTracks[0].streams)
        }
        // Bound: the newest MAX_PLAYS are kept.
        run {
            val big = File(dir(), "big.json")
            val s = ListenStats(big)
            s.load()
            val n = Listen.MAX_PLAYS + 10
            val tracks = (0 until 50).map { track("t$it", "Song $it", "Artist ${it % 7}", "Album", 200_000) }
            for (i in 0 until n) s.addPlay(tracks[i % 50].copy(artists = listOf(ArtistRef("", "Artist ${i % 7}"))), now - (n - i).toLong() * 60, 40_000)
            assertTrue(s.save())
            val snap = ListenStats.readFile(big)
            assertEquals(Listen.MAX_PLAYS, snap.plays.size)
            assertEquals(now - Listen.MAX_PLAYS.toLong() * 60, snap.plays.startedAt(0))
            assertEquals(now - 60, snap.plays.startedAt(snap.plays.size - 1))
        }
        // Escaping: quotes, backslashes, control characters and invalid UTF-16 still give a file that parses.
        run {
            val esc = File(dir(), "esc.json")
            val s = ListenStats(esc)
            s.load()
            s.addPlay(track("x", "A \"quoted\" \\ name\t\u0001", "Art\uD800ist", "Al\uDC00bum", 200_000), now, 40_000)
            assertTrue(s.save())
            val snap = ListenStats.readFile(esc)
            assertTrue(!snap.corrupt && snap.plays.size == 1 && snap.index.tracks.size == 1)
            val t = snap.index.tracks[0]
            assertEquals("A \"quoted\" \\ name\t\u0001", t.name)
            assertEquals("Art�ist", t.artists[0].name)
            assertEquals("Al�bum", t.albumName)
            // The same song twice in a file (e.g. written by an older version) merges into one entry.
            val dup = File(dir(), "dup.json")
            dup.writeText("""{"v":1,"tracks":[{"n":"Beni Çok Sev","a":[["","Tarkan"]]},{"n":"BENİ ÇOK SEV","id":"sp:1","a":[["sp:a","tarkan"]],"d":250000}],"plays":[[0,10,40000],[1,20,40000]]}""")
            val d = ListenStats.readFile(dup)
            assertTrue(d.index.tracks.size == 1 && d.plays.size == 2 && d.plays.track(0) == 0 && d.plays.track(1) == 0)
            assertEquals("sp:1", d.index.tracks[0].id)
            assertEquals(250_000, d.index.tracks[0].durationMs)
            assertEquals(1, d.index.artists.size)
            assertEquals("sp:a", d.index.artists[0].id)
        }
        // Generations: every save keeps the previous file as .old; a missing or broken main file falls back to it.
        run {
            val gen = File(dir(), "gen.json")
            val old = File(gen.path + ".old")
            val s = ListenStats(gen)
            s.load()
            s.addPlay(a, now - 100, 200_000)
            assertTrue(s.save())
            assertFalse(old.exists())
            s.addPlay(b, now - 50, 40_000)
            assertTrue(s.save())
            assertTrue(old.exists() && ListenStats.readFile(old).plays.size == 1)
            assertEquals(2, ListenStats.readFile(gen).plays.size)
            // Broken main (a crash while replacing it): .old is loaded, the broken file kept as .bak.
            gen.writeBytes(ByteArray(64))
            val snap = ListenStats.readFile(gen)
            assertTrue(snap.fromOld && snap.corrupt && !snap.keepFile && snap.plays.size == 1)
            assertTrue(File(gen.path + ".bak").exists())
            val r = ListenStats(gen)
            r.adopt(snap)
            assertTrue(r.playCount == 1 && r.isDirty) // put back in place by the next save
            assertTrue(r.save())
            assertEquals(1, ListenStats.readFile(gen).plays.size)
            assertFalse(ListenStats.readFile(gen).fromOld)
            // Missing main (between the two renames): .old again.
            gen.delete()
            assertTrue(ListenStats.readFile(gen).fromOld)
        }
        // A file that can't be read (locked by a scanner / backup, I/O error) is never overwritten.
        run {
            val lockedFile = File(dir(), "locked.json")
            run {
                val s = ListenStats(lockedFile)
                s.load()
                s.addPlay(a, now - 100, 200_000)
                assertTrue(s.save())
            }
            val sizeBefore = lockedFile.length()
            val snap = whileLocked(lockedFile) { ListenStats.readFile(lockedFile) } // retries ~400 ms, then gives up
            assertTrue(snap.keepFile && snap.plays.isEmpty())
            val s = ListenStats(lockedFile)
            s.adopt(snap)
            assertTrue(s.isReadOnly)
            s.addPlay(b, now - 10, 40_000)
            assertFalse(s.save())
            assertEquals(sizeBefore, lockedFile.length())
            assertEquals(1, ListenStats.readFile(lockedFile).plays.size)
            s.clear(now) // an explicit clear lifts it
            assertTrue(!s.isReadOnly && s.save())
        }
        // An older save never replaces a newer one (a worker write finishing after the exit save).
        run {
            val order = File(dir(), "order.json")
            val s = ListenStats(order)
            s.load()
            s.addPlay(a, now - 100, 200_000)
            val older = s.makeSaveJob()
            s.addPlay(b, now - 50, 40_000)
            val newer = s.makeSaveJob()
            assertTrue(ListenStats.write(newer))
            assertTrue(ListenStats.write(older)) // skipped, not an error
            assertEquals(2, ListenStats.readFile(order).plays.size)
        }
        // The async save: serialized and written on a worker, the caller only snapshots.
        run {
            val asyncFile = File(dir(), "async.json")
            val s = ListenStats(asyncFile)
            s.load()
            s.addPlay(a, now - 100, 200_000)
            s.addPlay(b, now - 50, 40_000)
            s.saveAsync(CoroutineScope(SupervisorJob() + Dispatchers.IO))
            assertFalse(s.isDirty)
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline &&
                !(asyncFile.exists() && ListenStats.readFile(asyncFile).plays.size == 2)
            ) Thread.sleep(20)
            assertEquals(2, ListenStats.readFile(asyncFile).plays.size)
            assertFalse(s.isDirty)
        }
        // Corrupt file: kept as .bak, the store starts empty.
        run {
            val bad = File(dir(), "bad.json")
            bad.writeText("{\"v\":1,\"tracks\":[{\"n\":\"x\"")
            val snap = ListenStats.readFile(bad)
            assertTrue(snap.corrupt && snap.plays.isEmpty())
            assertTrue(File(bad.path + ".bak").exists())
            // Invalid rows are skipped, valid ones kept.
            val odd = File(dir(), "odd.json")
            odd.writeText("""{"v":1,"tracks":[{"n":"A","a":[["","X"]]}],"plays":[[0,100,40000],[5,100,40000],[0,50,10],"x",[0,200,50000]]}""")
            val o = ListenStats.readFile(odd)
            assertFalse(o.corrupt)
            assertEquals(2, o.plays.size)
            assertEquals(100, o.plays.startedAt(0))
        }
        // Clear: everything goes, the current track keeps recording from now.
        run {
            val s = ListenStats(file)
            s.load()
            val sim = Sim(s)
            s.trackStarted(b, 0, now, sim.wall)
            sim.play(40)
            assertTrue(s.playCount > 0)
            s.clear(now + 40)
            assertTrue(s.playCount == 0 && s.isActive && s.currentListenedMs == 0L)
            assertTrue(s.save())
            sim.play(4)
            val sum = s.summarize(StatsPeriod.All, now + 50)
            assertEquals(4_000, sum.listenedMs)
            assertEquals(0, sum.streams)
            assertTrue(ListenStats.readFile(file).plays.isEmpty())
        }
    }

    @Test
    fun timing() {
        val file = File(dir(), "timing.json")
        val now = 1_760_000_000L
        fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6
        run {
            val s = ListenStats(file)
            s.load()
            for (i in 0 until 20_000) {
                val k = (i * 7919) % 3000
                var t = track("spotify:track:$k", "Şarkı numarası $k", "Sanatçı ${k % 400}", "Albüm ${k % 900}", 180_000 + k,
                    "spotify:artist:${k % 400}", "spotify:album:${k % 900}")
                t = t.copy(album = t.album.copy(images = listOf(com.app.shadetube.catalog.Image("https://i.scdn.co/image/ab67616d00001e02${100000000 + k}", 300, 300))))
                s.addPlay(t, now - (20_000 - i).toLong() * 120, 30_000L + (i % 200) * 1000)
            }
            var t0 = System.nanoTime()
            val job = s.makeSaveJob()
            println("  snapshot    %7.1f ms".format(ms(t0)))
            t0 = System.nanoTime()
            assertTrue(ListenStats.write(job))
            println("  write       %7.1f ms  (%.0f KB)".format(ms(t0), file.length() / 1024.0))
            t0 = System.nanoTime()
            val sum = s.summarize(StatsPeriod.All, now)
            println("  summarize   %7.1f ms  (all time)".format(ms(t0)))
            assertEquals(20_000, sum.streams)
        }
        var t0 = System.nanoTime()
        val snap = ListenStats.readFile(file)
        println("  readFile    %7.1f ms".format(ms(t0)))
        val s = ListenStats(file)
        t0 = System.nanoTime()
        s.adopt(snap)
        println("  adopt       %7.1f ms".format(ms(t0)))
        assertTrue(s.playCount == 20_000 && s.tracksCopy().size == 3000)
        t0 = System.nanoTime()
        val w = s.summarize(StatsPeriod.Week, now)
        println("  summarize   %7.1f ms  (7 days: %d streams)".format(ms(t0), w.streams))
    }

    // --- local time ------------------------------------------------------------------------------------------------

    /**
     * Europe/Berlin by hand (the EU rule since 1996): +2 from the last Sunday of March 01:00 UTC to the last Sunday
     * of October 01:00 UTC, else +1.
     */
    private val euLocal = LocalClock { u ->
        val y = Listen.civil(Listen.dayNumber(u)).year
        fun lastSunday(year: Int, month: Int): Long {
            var day = Listen.daysFromCivil(year, month, 31)
            while (Listen.weekday(day) != 6) --day
            return day * 86400 + 3600
        }
        val summer = u >= lastSunday(y, 3) && u < lastSunday(y, 10)
        u + if (summer) 7200 else 3600
    }

    @Test
    fun localTime() {
        assertEquals(0, Listen.daysFromCivil(1970, 1, 1))
        assertEquals(3, Listen.weekday(0)) // Thursday
        assertEquals(0, Listen.weekday(Listen.daysFromCivil(2024, 1, 1))) // Monday
        assertEquals(6, Listen.weekday(Listen.daysFromCivil(2024, 3, 31))) // Sunday
        assertEquals(2, Listen.weekday(-1)) // 1969-12-31: Wednesday
        for (day in longArrayOf(-800_000, -1, 0, 11_016, 19_782, 20_000, 2_932_896)) {
            val c = Listen.civil(day)
            assertEquals(day, Listen.daysFromCivil(c.year, c.month, c.day))
        }
        assertEquals(Listen.CivilDate(2000, 2, 29), Listen.civil(Listen.daysFromCivil(2000, 2, 29)))
        assertTrue(Listen.dayNumber(-1) == -1L && Listen.dayNumber(86399) == 0L && Listen.dayNumber(86400) == 1L)

        // The tz database: Berlin's DST switches, and Istanbul's history (DST until 2016, then +3 all year).
        val berlin = timeZoneClock("Europe/Berlin")
        assertEquals(3600, berlin.toLocal(utc(2024, 3, 31, 0, 30)) - utc(2024, 3, 31, 0, 30))
        assertEquals(7200, berlin.toLocal(utc(2024, 3, 31, 1, 30)) - utc(2024, 3, 31, 1, 30))
        assertEquals(7200, berlin.toLocal(utc(2024, 10, 27, 0, 30)) - utc(2024, 10, 27, 0, 30))
        assertEquals(3600, berlin.toLocal(utc(2024, 10, 27, 1, 30)) - utc(2024, 10, 27, 1, 30))
        assertEquals(7200, berlin.toLocal(utc(2019, 7, 1)) - utc(2019, 7, 1))
        var u = utc(2023, 1, 1)
        while (u < utc(2025, 1, 1)) { // agrees with the EU rule all along
            assertEquals(euLocal.toLocal(u), berlin.toLocal(u))
            u += 3 * 3600 + 17
        }
        val istanbul = timeZoneClock("Europe/Istanbul")
        assertEquals(2 * 3600L, istanbul.toLocal(utc(2015, 1, 15)) - utc(2015, 1, 15))
        assertEquals(3 * 3600L, istanbul.toLocal(utc(2015, 7, 15)) - utc(2015, 7, 15))
        assertEquals(3 * 3600L, istanbul.toLocal(utc(2020, 1, 15)) - utc(2020, 1, 15))
        // The system clock answers (whatever this machine's zone is) within +-14 h.
        val sys = systemLocalClock()
        assertTrue(Math.abs(sys.toLocal(utc(2024, 6, 1)) - utc(2024, 6, 1)) <= 14 * 3600)
    }

    @Test
    fun heatmapAndYears() {
        val clock = euLocal
        val s = ListenStats(File(dir(), "year.json"))
        s.load()
        val a = track("spotify:track:a", "Şımarık", "Tarkan", "Ölürüm Sana", 234_000, "spotify:artist:tarkan")
        val b = track("mb:b", "Gülpembe", "Barış Manço", "Sahibinden İhtiyar", 280_000)
        val c = track("mb:c", "Firuze", "Sezen Aksu", "Firuze", 215_000)
        // Spring forward: 00:30 UTC Sunday = 01:30 local; one hour of listening = 01:30-02:00 and 03:00-03:30 local.
        s.addPlay(c, utc(2024, 3, 31, 0, 30), 3_600_000)
        // Fall back: 00:30 UTC = 02:30 (+2), at 01:00 UTC the clock shows 02:00 again: the whole hour is in 02:xx.
        s.addPlay(c, utc(2024, 10, 27, 0, 30), 3_600_000)
        val h = s.heatmap(StatsPeriod.All, utc(2025, 1, 1), clock)
        assertEquals(1_800_000, h.at(6, 1))
        assertEquals(3_600_000, h.at(6, 2))
        assertEquals(1_800_000, h.at(6, 3))
        assertTrue(h.totalMs == 7_200_000L && h.maxMs == 3_600_000L)
        assertEquals(h.totalMs, h.ms.sum())

        // 2023: Tarkan and Barış Manço. 2024: Tarkan again, Sezen Aksu new (and Barış Manço, not new).
        s.addPlay(a, utc(2023, 5, 10, 18), 200_000)
        s.addPlay(b, utc(2023, 6, 1, 12), 280_000)
        // 23:30 UTC on Dec 31 is 00:30 on Jan 1 locally: 2024's first stream.
        s.addPlay(a, utc(2023, 12, 31, 23, 30), 200_000)
        s.addPlay(b, utc(2024, 1, 1, 10), 10_000) // not a stream (the time counts)
        s.addPlay(b, utc(2024, 1, 2, 10), 280_000)
        s.addPlay(a, utc(2024, 1, 3, 10), 200_000)
        s.addPlay(a, utc(2024, 1, 3, 11), 200_000)
        s.addPlay(c, utc(2024, 1, 5, 20), 215_000) // a gap on the 4th: the first streak is Jan 1-3
        s.addPlay(c, utc(2024, 1, 6, 20), 215_000)
        s.addPlay(c, utc(2024, 1, 7, 20), 215_000)
        s.addPlay(c, utc(2024, 1, 8, 20), 215_000) // Jan 5-8: 4 days, the longest
        assertEquals(listOf(2024, 2023), s.years(clock))

        val y = s.summarizeYear(2024, clock)
        assertEquals(2024, y.year)
        assertEquals(10, y.streams) // the two DST hours count too
        assertEquals(2 * 3_600_000L + 200_000 + 10_000 + 280_000 + 400_000 + 4 * 215_000, y.listenedMs)
        assertTrue(y.distinctTracks == 3 && y.distinctArtists == 3)
        assertEquals(1, y.newArtists) // Sezen Aksu
        assertNotNull(y.firstStream)
        assertEquals("Şımarık", y.firstStream!!.track.name)
        assertEquals(utc(2023, 12, 31, 23, 30), y.firstStream!!.startedAt)
        assertEquals(4, y.longestStreak)
        assertEquals(utc(2024, 1, 5, 20), y.streakFrom)
        assertEquals(utc(2024, 1, 8, 20), y.streakTo)
        assertEquals(3 + 4 + 2, y.activeDays) // Jan 1-3, Jan 5-8, Mar 31, Oct 27
        assertTrue(y.topMonth == 2 || y.topMonth == 9) // March and October: one hour each
        assertEquals(200_000L + 10_000 + 280_000 + 400_000 + 4 * 215_000, y.monthMs[0])
        assertEquals(6, y.topWeekday) // the two Sunday hours
        assertEquals("Firuze", y.topTracks[0].track.name)
        assertEquals(6, y.topTracks[0].streams)
        assertEquals(3, y.topArtists.size)
        assertEquals("Sezen Aksu", y.topArtists[0].artist.name)
        assertEquals(y.listenedMs, y.heatmap.totalMs)
        val old = s.summarizeYear(2023, clock)
        assertTrue(old.streams == 2 && old.newArtists == 2 && old.longestStreak == 1 && old.topMonth == 5)
        assertEquals(utc(2023, 5, 10, 18), old.firstStream!!.startedAt)
        val none = s.summarizeYear(2019, clock)
        assertTrue(none.streams == 0 && none.listenedMs == 0L && none.topMonth == -1 && none.longestStreak == 0)
        assertNull(none.firstStream)
        // The current play counts provisionally (Jan 4 fills the gap: Jan 1-8 in a row).
        val sim = Sim(s)
        s.trackStarted(a, 0, utc(2024, 1, 4, 9), sim.wall)
        sim.play(40)
        val live = s.summarizeYear(2024, clock)
        assertTrue(live.streams == 11 && live.longestStreak == 8 && live.activeDays == 10)
        // 7-day heatmap: from that window on (the two DST hours come later in the year).
        val week = s.heatmap(StatsPeriod.Week, utc(2024, 1, 9), clock)
        assertEquals(280_000L + 400_000 + 4 * 215_000 + 40_000 + 2 * 3_600_000, week.totalMs)
    }

    @Test
    fun mersenneTwisterMatchesStd() {
        // std::mt19937_64 with the default seed: the 10000th value is 9981545732273789042 (C++ standard).
        val rng = Mt19937(5489)
        var v = 0L
        repeat(10_000) { v = rng.next() }
        assertEquals("9981545732273789042", java.lang.Long.toUnsignedString(v))
    }
}
