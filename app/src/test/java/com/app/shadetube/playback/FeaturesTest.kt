package com.app.shadetube.playback

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.parseJsonOrNull
import com.app.shadetube.playback.features.Blacklist
import com.app.shadetube.playback.features.Mt19937_64
import com.app.shadetube.playback.features.QueueMix
import com.app.shadetube.playback.features.Radio
import com.app.shadetube.playback.features.SponsorBlock
import com.app.shadetube.playback.features.SponsorBlock.Segment
import com.app.shadetube.playback.service.BrowseTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Ports of tests/playback (kara liste), tests/sponsorblock (offline part), tests/smartshuffle (planning). */
@OptIn(ExperimentalCoroutinesApi::class)
class FeaturesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun track(id: String, name: String, artists: List<ArtistRef>, durationMs: Int = 0) =
        Track(id = id, name = name, artists = artists, durationMs = durationMs)

    private val fonsi = ArtistRef("spotify:artist:4V8Sr092TqfHkfAA5fXXqG", "Luis Fonsi")
    private val daddy = ArtistRef("spotify:artist:4VMYDCV2IEDYJArk749S6m", "Daddy Yankee")
    private val fonsiMb = ArtistRef("d68ab5a8-1c34-4c3d-9f55-1fa3e19a1b6b", "Luis Fonsi")
    private val nirvanaUs = ArtistRef("spotify:artist:6olE6TJLqED3rqDCT0FyPh", "Nirvana")
    private val nirvanaUk = ArtistRef("spotify:artist:0g4Kq4qzYh6LzlNQ6FNBF2", "Nirvana")
    private val rapper = ArtistRef("spotify:artist:1111111111111111111111", "MC Test")

    // ---- kara liste -----------------------------------------------------------------------------------------------

    @Test
    fun blacklist() {
        val file = File(tmp.root, "blacklist.json")
        val bad = File(file.path + ".bad")
        var now = 1_790_000_000L
        var bl = Blacklist({ file }, { now++ })
        assertTrue(bl.isEmpty())
        var notified = 0
        bl.onChanged { notified++ }
        val rev0 = bl.revision.value

        // Tracks: by id; another release of the same recording by title + first artist.
        val despacito = track("spotify:track:6habFhsOp2NvshLv26DqMb", "Despacito", listOf(fonsi, daddy), 229360)
        bl.setTrackBlocked(despacito, true)
        assertEquals(1, notified)
        assertTrue(bl.revision.value > rev0)
        assertTrue(bl.isBlocked(despacito))
        assertTrue(bl.isTrackBlocked(despacito.id))
        assertEquals(1, bl.blockedTracks().size)
        assertEquals("Luis Fonsi, Daddy Yankee", bl.blockedTracks()[0].artists)
        assertEquals(229360, bl.blockedTracks()[0].durationMs)
        assertTrue(bl.isBlocked(track("spotify:track:0000000000000000000001", "DESPACİTO ", listOf(ArtistRef("", "luis fonsi")), 230100))) // single
        assertFalse(bl.isBlocked(track("spotify:track:0000000000000000000007", "Despacito", listOf(fonsi), 281000))) // another version
        assertFalse(bl.isBlocked(track("spotify:track:0000000000000000000008", "Despacito", listOf(fonsi), 0))) // same catalog, ?
        assertTrue(bl.isBlocked(track("5f1a9d42-0000-0000-0000-000000000000", "Despacito", listOf(fonsiMb), 0))) // MusicBrainz
        assertTrue(bl.isBlocked(track("local:abc", "Despacito", listOf(ArtistRef("", "Luis Fonsi")), 0))) // local file
        assertTrue(bl.isBlocked(track("local:abd", "Despacito", listOf(ArtistRef("", "Luis Fonsi")), 228000)))
        assertFalse(bl.isBlocked(track("local:abe", "Despacito", listOf(ArtistRef("", "Luis Fonsi")), 200000)))
        assertFalse(bl.isBlocked(track("spotify:track:0000000000000000000002", "Despacito - Remix", listOf(fonsi), 229360)))
        assertFalse(bl.isBlocked(track("spotify:track:0000000000000000000003", "Despacito", listOf(ArtistRef("", "Pedro Capó")), 229360)))
        assertFalse(bl.isTrackBlocked("spotify:track:0000000000000000000001")) // id-only query
        assertFalse(bl.isBlocked(track("", "", emptyList())))
        bl.setTrackBlocked(despacito, true) // already blocked: no duplicate, no change
        assertEquals(1, bl.blockedTracks().size)
        assertEquals(1, notified)

        // A generic title shared by different songs of one artist: only that "Intro" (and its other releases).
        bl.setTrackBlocked(track("spotify:track:A000000000000000000000", "Intro", listOf(rapper), 60000), true)
        assertFalse(bl.isBlocked(track("spotify:track:B000000000000000000000", "Intro", listOf(rapper), 95000)))
        assertTrue(bl.isBlocked(track("spotify:track:C000000000000000000000", "intro", listOf(rapper), 61000)))
        assertTrue(bl.isBlocked(track("local:z", "Intro", listOf(ArtistRef("", "MC Test")), 0)))
        bl.setTrackBlocked(track("spotify:track:B000000000000000000000", "Intro", listOf(rapper), 95000), true) // a 2nd one
        assertEquals(3, bl.blockedTracks().size)
        bl.setTrackBlocked(track("spotify:track:C000000000000000000000", "Intro", listOf(rapper), 61000), false) // unblock #1
        assertEquals(2, bl.blockedTracks().size)
        assertTrue(bl.isBlocked(track("spotify:track:B000000000000000000000", "Intro", listOf(rapper), 95000))) // #2 stays
        bl.remove(bl.blockedTracks()[0]) // exactly that entry (the newest: the 2nd "Intro")
        assertEquals(1, bl.blockedTracks().size)
        assertEquals("Despacito", bl.blockedTracks()[0].name)

        // Artists: by id; by name across catalogs / for id-less credits; same-name Spotify artists stay apart.
        bl.setArtistBlocked(nirvanaUs.id, nirvanaUs.name, true)
        assertTrue(bl.isArtistBlocked(nirvanaUs.id))
        assertTrue(bl.artistMatches(nirvanaUs))
        assertFalse(bl.artistMatches(nirvanaUk))
        assertTrue(bl.artistMatches(ArtistRef("5b11f4ce-a62d-471e-81fc-a69a8278c7da", "Nirvana"))) // MusicBrainz credit
        assertTrue(bl.artistMatches(ArtistRef("", "NİRVANA"))) // plain-text credit
        assertTrue(bl.isBlocked(track("spotify:track:0000000000000000000004", "Lithium", listOf(nirvanaUs))))
        assertTrue(bl.isBlocked(track("spotify:track:0000000000000000000005", "Feat. song", listOf(ArtistRef("x", "Someone"), nirvanaUs))))
        assertFalse(bl.isBlocked(track("spotify:track:0000000000000000000006", "Lithium", listOf(nirvanaUk))))
        assertFalse(bl.isArtistBlocked(nirvanaUk.id))

        // Round trip through the file.
        val j = parseJsonOrNull(file.readText()) as JsonObject
        val tracks = j["tracks"] as JsonArray
        assertEquals(1, tracks.size)
        assertEquals(1, (j["artists"] as JsonArray).size)
        val t0 = tracks[0] as JsonObject
        assertEquals(despacito.id, t0["id"]!!.jsonPrimitive.content)
        assertEquals("Luis Fonsi", t0["a0"]!!.jsonPrimitive.content)
        assertTrue(t0["t"]!!.jsonPrimitive.long > 0)
        assertEquals(229360L, t0["d"]!!.jsonPrimitive.long)
        bl = Blacklist({ file })
        assertEquals(1, bl.blockedTracks().size)
        assertEquals(1, bl.blockedArtists().size)
        assertTrue(bl.isBlocked(despacito))
        assertEquals("Luis Fonsi", bl.blockedTracks()[0].artist0)
        assertTrue(bl.isArtistBlocked(nirvanaUs.id))
        assertFalse(File(file.path + ".tmp").exists())
        assertFalse(bad.exists())

        // Unblock through another release (title + artist) and through the MusicBrainz id of the artist.
        bl.setTrackBlocked(track("local:xyz", "despacito", listOf(ArtistRef("", "Luis Fonsi"))), false)
        assertFalse(bl.isBlocked(despacito))
        assertTrue(bl.blockedTracks().isEmpty())
        bl.setArtistBlocked("5b11f4ce-a62d-471e-81fc-a69a8278c7da", "Nirvana", false)
        assertFalse(bl.isArtistBlocked(nirvanaUs.id))
        assertTrue(bl.isEmpty())
        var n2 = 0
        bl.onChanged { n2++ }
        bl.setArtistBlocked(nirvanaUk.id, nirvanaUk.name, false) // nothing to remove: no change
        assertEquals(0, n2)

        // Newest first.
        bl.setArtistBlocked(fonsi.id, fonsi.name, true)
        bl.setArtistBlocked(daddy.id, daddy.name, true)
        assertEquals("Daddy Yankee", bl.blockedArtists()[0].name)
        assertTrue(bl.isBlocked(despacito)) // through its artists now

        // Wrong-typed fields never throw: they read as empty / 0; entries that still have an id or a name survive, and
        // the damaged file is kept as .bad.
        file.writeText(
            """{"version":1,"tracks":[{"id":123,"n":"Keep me","a0":"X","t":"2026-09-28","d":"long"},{"id":null},7],""" +
                """"artists":[{"id":"spotify:artist:2222222222222222222222","n":["x"],"t":1790000000}]}""",
        )
        bl = Blacklist({ file })
        assertEquals(1, bl.blockedTracks().size)
        assertEquals("Keep me", bl.blockedTracks()[0].name)
        assertEquals(0L, bl.blockedTracks()[0].addedAt)
        assertEquals(1, bl.blockedArtists().size)
        assertEquals(1790000000L, bl.blockedArtists()[0].addedAt)
        assertTrue(bad.exists())
        bad.delete()
        file.writeText("""{"tracks":"nope","artists":{}}""")
        bl = Blacklist({ file })
        assertTrue(bl.isEmpty())
        assertTrue(bad.exists())
        bad.delete()

        // A corrupt file starts empty and is kept aside before the next save could overwrite it.
        file.writeText("{not json")
        bl = Blacklist({ file })
        assertTrue(bl.isEmpty())
        assertTrue(bad.exists())
        assertEquals(9L, bad.length())
        bl.setArtistBlocked(fonsi.id, fonsi.name, true) // the next save replaces blacklist.json, not the .bad copy
        assertEquals(9L, bad.length())
    }

    // ---- SponsorBlock -----------------------------------------------------------------------------------------------

    @Test
    fun sponsorBlockHashAndParse() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SponsorBlock.sha256Hex("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SponsorBlock.sha256Hex(""))
        assertEquals("f1d9", SponsorBlock.sha256Hex("kJQP7kiw5Fk").substring(0, 4))
        assertEquals("5f6b", SponsorBlock.sha256Hex("dQw4w9WgXcQ").substring(0, 4))

        val direct = """[
            {"category":"music_offtopic","actionType":"skip","segment":[0,21.808434],"UUID":"a","videoDuration":282},
            {"category":"sponsor","actionType":"mute","segment":[30,40],"UUID":"b"},
            {"category":"poi_highlight","actionType":"poi","segment":[50,50],"UUID":"c"},
            {"category":"selfpromo","segment":[249.38,281.521],"UUID":"d","videoDuration":0},
            {"category":"sponsor","actionType":"skip","segment":["x",2],"UUID":"e"},
            {"category":"sponsor","actionType":"skip","UUID":"f"}
        ]"""
        val d = SponsorBlock.parseResponse(direct, "ignored")
        assertEquals(2, d.size)
        assertEquals(Segment(0, 21808, "music_offtopic", 282000), d[0])
        assertEquals(Segment(249380, 281521, "selfpromo", 0), d[1])

        val hashed = """[
            {"videoID":"other","segments":[{"category":"sponsor","actionType":"skip","segment":[1,5]}]},
            {"videoID":"kJQP7kiw5Fk","hash":"f1d9","segments":[
                {"category":"music_offtopic","actionType":"skip","segment":[0,21.8]},
                {"category":"exclusive_access","actionType":"full","segment":[0,0]}]}
        ]"""
        val h = SponsorBlock.parseResponse(hashed, "kJQP7kiw5Fk")
        assertEquals(1, h.size)
        assertEquals(21800L, h[0].endMs)
        assertTrue(SponsorBlock.parseResponse(hashed, "notInBucket").isEmpty())
        assertTrue(SponsorBlock.parseResponse("[]", "x").isEmpty())
        for (badJson in listOf("Not Found", """{"error":1}""")) {
            try {
                SponsorBlock.parseResponse(badJson, "x")
                fail("$badJson should throw")
            } catch (e: Exception) {
                // expected
            }
        }
    }

    @Test
    fun sponsorBlockNormalize() {
        val n = SponsorBlock.normalize(
            listOf(
                Segment(60000, 70000, "sponsor"),
                Segment(0, 20000, "music_offtopic"),
                Segment(19800, 25000, "selfpromo"), // overlaps -> merged
                Segment(25400, 30000, "interaction"), // 400 ms gap -> merged
                Segment(40000, 40800, "sponsor"), // 0.8 s -> dropped
                Segment(50000, 49000, "sponsor"), // invalid -> dropped
                Segment(70600, 80000, "selfpromo"), // 600 ms gap -> separate
            ),
        )
        assertEquals(3, n.size)
        assertEquals(Segment(0, 30000, "music_offtopic"), n[0])
        assertEquals(60000L to 70000L, n[1].startMs to n[1].endMs)
        assertEquals(70600L to 80000L, n[2].startMs to n[2].endMs)
        val c = SponsorBlock.normalize(listOf(Segment(-1000, 5000, "music_offtopic")))
        assertEquals(1, c.size)
        assertEquals(0L to 5000L, c[0].startMs to c[0].endMs)
        // Outdated submissions (video re-edited): dropped when the real length is known.
        val withDur = listOf(Segment(0, 20000, "a", 282000), Segment(30000, 40000, "b", 0), Segment(50000, 60000, "c", 200000))
        assertEquals(2, SponsorBlock.normalize(withDur, 281521).size)
        assertEquals(3, SponsorBlock.normalize(withDur, 0).size)
    }

    private fun sponsor(): SponsorBlock =
        SponsorBlock(CoroutineScope(StandardTestDispatcher()), fetcher = { _, _ -> emptyList() }, steadyMs = { 0 })

    @Test
    fun sponsorBlockCheck() {
        val sb = sponsor()
        sb.injectSegments(listOf(Segment(0, 20000, "music_offtopic"), Segment(60000, 90000, "sponsor"), Segment(200000, 240000, "music_offtopic")))
        assertEquals(3, sb.segmentCount)
        assertEquals(SponsorBlock.State.Ready, sb.state)
        val steps = listOf<Pair<Long, Long?>>(
            0L to null, // position 0 (track switching) never acts
            200L to 20000L, // intro skipped at the first real tick
            450L to null, // stale position while the seek is in flight: no second seek
            20050L to null,
            59700L to null,
            60100L to 90000L, // sponsor entered by normal playback
            60300L to null,
            90050L to null,
            70000L to null, // user seeks back INTO the skipped sponsor: it plays
            75000L to null,
            50000L to null, // user seeks back in front of it: re-armed
            60200L to 90000L,
            120000L to null,
            0L to null, // restart (previous / repeat-one) reports 0 first
            250L to 20000L, // intro skipped again after the restart
            100000L to null,
            400L to 20000L, // seek straight to 0.4 s (restart) re-arms and skips the intro
            30000L to null,
            10000L to null, // deliberate seek to the middle of the skipped intro: plays
            210000L to 240000L, // forward seek into a not-yet-skipped segment: skipped
        )
        for ((pos, want) in steps) assertEquals("check($pos)", want, sb.check(pos))
        assertEquals("music_offtopic", sb.lastSkippedSegment?.category)

        val e = sponsor()
        e.injectSegments(listOf(Segment(0, 20000, "music_offtopic")))
        assertNull(e.check(19700)) // last 0.3 s of a segment: not worth a seek
        assertNull(e.check(19900)) // ...and disarmed

        val t = sponsor() // an old video's never-entered intro must not fire on the 0 reported at a track switch
        t.injectSegments(listOf(Segment(0, 20000, "music_offtopic")))
        assertNull(t.check(150000))
        assertNull(t.check(0))

        val off = sponsor()
        off.injectSegments(listOf(Segment(10000, 20000, "sponsor")))
        off.enabled = false
        assertNull(off.check(15000))
        off.enabled = true
        assertEquals(20000L, off.check(15100))

        val none = sponsor()
        none.injectSegments(listOf(Segment(10000, 20000, "sponsor")))
        none.setVideo("")
        assertEquals(0, none.segmentCount)
        assertEquals(SponsorBlock.State.Idle, none.state)
        assertNull(none.check(15000))
    }

    // ---- smart shuffle planning ------------------------------------------------------------------------------------

    private fun T(id: String, name: String, artist: String, rec: Boolean = false) =
        Track(id = id, name = name, artists = listOf(ArtistRef("", artist)), durationMs = 1200, recommended = rec)

    @Test
    fun mixKeys() {
        assertEquals(QueueMix.trackKey(T("a", "Hello - 2011 Remaster", "Adele")), QueueMix.trackKey(T("b", "Hello", "ADELE")))
        assertEquals(QueueMix.trackKey(T("a", "Hello (Live at the BBC)", "Adele")), QueueMix.trackKey(T("b", "hello", "Adele")))
        assertEquals(QueueMix.trackKey(T("a", "Şımarık", "TARKAN")), QueueMix.trackKey(T("b", "şımarık", "Tarkan")))
        assertEquals(QueueMix.trackKey(T("a", "夜に駆ける", "YOASOBI")), QueueMix.trackKey(T("b", "夜に駆ける (Live)", "yoasobi")))
        assertNotEquals(QueueMix.trackKey(T("a", "夜に駆ける", "YOASOBI")), QueueMix.trackKey(T("b", "群青", "YOASOBI")))
        assertEquals(QueueMix.trackKey(T("a", "İstanbul", "X")), QueueMix.trackKey(T("b", "istanbul", "x")))
        assertNotEquals(QueueMix.trackKey(T("a", "Hello", "Adele")), QueueMix.trackKey(T("b", "Hello", "Lionel Richie")))
        assertTrue(QueueMix.trackKey(T("a", "", "Adele")).isEmpty())
        val ex = QueueMix.Exclusions()
        ex.add(T("spotify:track:1", "Yesterday", "The Beatles"))
        assertTrue(T("spotify:track:1", "Other", "Other") in ex) // same id
        assertTrue(T("mbid-9", "Yesterday - Remastered 2009", "The Beatles") in ex) // same song elsewhere
        assertFalse(T("mbid-8", "Yesterday", "Boyz II Men") in ex) // a cover by someone else
    }

    /** Applies insertion points to a flag list (as the feature inserts, last point first). */
    private fun applied(flags: List<Boolean>, points: List<Int>): List<Boolean> {
        val out = flags.toMutableList()
        for (p in points.reversed()) out.add(p, true)
        return out
    }

    private fun spacing(flags: List<Boolean>): Pair<Int, Boolean> {
        var maxRun = 0
        var adjacent = false
        var run = 0
        for (i in flags.indices) {
            if (flags[i]) {
                if (i > 0 && flags[i - 1]) adjacent = true
                run = 0
            } else {
                run++
                maxRun = maxOf(maxRun, run)
            }
        }
        return maxRun to adjacent
    }

    @Test
    fun insertionPoints() {
        val flags = List(20) { false }
        val points = QueueMix.insertionPoints(flags, 0, 99)
        assertTrue(points.isNotEmpty())
        assertTrue(points.first() >= 2) // never right after the playing song
        val after = applied(flags, points)
        val (maxRun, adjacent) = spacing(after)
        assertTrue(maxRun <= QueueMix.MAX_GAP)
        assertFalse(adjacent)
        var run = 0
        var minGap = true // every recommendation after at least MIN_GAP own songs (counting the playing one)
        for (f in after) {
            if (f) {
                minGap = minGap && run >= QueueMix.MIN_GAP
                run = 0
            } else {
                run++
            }
        }
        assertTrue(minGap)
        assertEquals(points, QueueMix.insertionPoints(flags, 0, 99)) // deterministic
        assertTrue(QueueMix.insertionPoints(after, 0, 99).isEmpty()) // planned again: nothing new
        assertTrue(QueueMix.insertionPoints(after, 0, 5).isEmpty()) // ... whatever the seed
        // Existing recommendations 4 songs apart are enough.
        val spaced = List(25) { it % 5 == 4 }
        assertTrue(QueueMix.insertionPoints(spaced, 0, 3).isEmpty())
        // Only the window is planned.
        val w = QueueMix.insertionPoints(List(200) { false }, 10, 1, 24)
        assertTrue(w.isNotEmpty() && w.last() <= 10 + 1 + 24)
        // The own songs before the playing one count: the first point comes right after the protected slot.
        val h = QueueMix.insertionPoints(List(30) { false }, 10, 11)
        assertEquals(12, h.first())
        // A recommendation playing now resets the count.
        val recNow = List(30) { it == 10 }
        val r = QueueMix.insertionPoints(recNow, 10, 11)
        assertTrue(r.isNotEmpty() && r.first() >= 10 + 1 + QueueMix.MIN_GAP)
        // Nothing to plan at the end of a queue / outside it.
        assertTrue(QueueMix.insertionPoints(List(5) { false }, 4, 1).isEmpty())
        assertTrue(QueueMix.insertionPoints(List(5) { false }, -1, 1).isEmpty())
    }

    @Test
    fun interleave() {
        val own = (0 until 17).map { T("o$it", "Own $it", "X") }
        val recs = (0 until 3).map { T("r$it", "Rec $it", "Y") }
        val out = QueueMix.interleave(own, recs, 5)
        assertEquals(own.size + recs.size, out.size)
        val flags = out.map { it.recommended }
        assertFalse(spacing(flags).second)
        assertFalse(flags.first())
        assertEquals(listOf("r0", "r1", "r2"), out.filter { it.recommended }.map { it.id })
        assertEquals(own.map { it.id }, out.filter { !it.recommended }.map { it.id })
        assertEquals(2, QueueMix.interleave(own.take(2), recs, 5).size) // too short
        assertEquals(own.size, QueueMix.interleave(own, emptyList(), 5).size)
    }

    @Test
    fun mt19937_64KnownAnswer() {
        // std::mt19937_64 default seed (5489): the 10000th output is 9981545732273789042 (C++ standard, [rand.predef]).
        val rng = Mt19937_64(5489)
        var x = 0L
        repeat(10000) { x = rng.next() }
        assertEquals("9981545732273789042", java.lang.Long.toUnsignedString(x))
    }

    // ---- radio ------------------------------------------------------------------------------------------------------

    @Test
    fun radioSeeds() {
        assertEquals("spotify:track:6habFhsOp2NvshLv26DqMb", Radio.radioSeed("spotify:track:6habFhsOp2NvshLv26DqMb"))
        assertEquals("spotify:album:1", Radio.radioSeed("spotify:album:1"))
        assertEquals("spotify:artist:abc", Radio.radioSeed("spotify:artist:abc"))
        assertEquals("spotify:playlist:x9", Radio.radioSeed("spotify:playlist:x9"))
        assertEquals("", Radio.radioSeed("spotify:track:"))
        assertEquals("", Radio.radioSeed("spotify:track:bad-id"))
        assertEquals("", Radio.radioSeed("spotify:collection:tracks"))
        assertEquals("", Radio.radioSeed("local:abc"))
        assertEquals("", Radio.radioSeed("d68ab5a8-1c34-4c3d-9f55-1fa3e19a1b6b"))
        val seen = hashSetOf("in-queue")
        val out = Radio.usableTracks(
            listOf(
                T("in-queue", "A", "X"), T("ok", "B", "X"), T("ok", "B again", "X"), T("blocked", "C", "X"),
                T("np", "D", "X").copy(playable = false), T("nn", "", "X"),
            ),
            seen,
        ) { it.id == "blocked" }
        assertEquals(listOf("ok"), out.map { it.id })
    }

    // ---- session.json / browse ids ------------------------------------------------------------------------------

    @Test
    fun sessionFileRoundTrip() {
        val t = Track(
            id = "spotify:track:1", name = "Song", durationMs = 1234, explicitContent = true, recommended = true,
            artists = listOf(ArtistRef("spotify:artist:a", "A"), ArtistRef("", "B")),
            album = AlbumRef("spotify:album:x", "Album", listOf(Image("u640", 640, 640), Image("u300", 300, 300), Image("u64", 64, 64))),
        )
        val j = SessionFile.encode(listOf(t, t.copy(id = "2", recommended = false)), 1, 5000, PlayContext("liked", "Beğenilenler"))
        assertEquals(
            """{"pos":1,"curId":"2","positionMs":5000,"contextUri":"liked","contextName":"Beğenilenler","items":[""" +
                """{"id":"spotify:track:1","n":"Song","d":1234,"a":[{"id":"spotify:artist:a","n":"A"},{"id":"","n":"B"}],""" +
                """"al":"Album","ali":"spotify:album:x","img":[{"u":"u300","w":300}],"e":true,"rec":true},""" +
                """{"id":"2","n":"Song","d":1234,"a":[{"id":"spotify:artist:a","n":"A"},{"id":"","n":"B"}],""" +
                """"al":"Album","ali":"spotify:album:x","img":[{"u":"u300","w":300}],"e":true}]}""",
            j.toString(),
        )
        val r = SessionFile.decode(j)!!
        assertEquals(1, r.pos)
        assertEquals(5000L, r.positionMs)
        assertEquals(PlayContext("liked", "Beğenilenler"), r.context)
        assertEquals(2, r.items.size)
        assertTrue(r.items[0].recommended)
        assertEquals(listOf(Image("u300", 300, 300)), r.items[0].album.images)
        assertEquals(t.artists, r.items[0].artists)
        // Items without a name are dropped; the current track is re-found by its id.
        val dropped = parseJsonOrNull(
            """{"pos":2,"curId":"c","items":[{"id":"a","n":"A"},{"id":"x"},{"id":"c","n":"C"},{"id":"d","n":"D"}],"positionMs":"bad"}""",
        )
        val d = SessionFile.decode(dropped)!!
        assertEquals(listOf("a", "c", "d"), d.items.map { it.id })
        assertEquals(1, d.pos)
        assertEquals(0L, d.positionMs)
        assertNull(SessionFile.decode(parseJsonOrNull("""{"items":"no"}""")))
        assertNull(SessionFile.decode(parseJsonOrNull("""{"items":[{"id":"x"}]}""")))
        // The window: 100 back, up to 500 in all.
        assertEquals(200 until 700, SessionFile.window(700, 650))
        assertEquals(0 until 500, SessionFile.window(700, 50))
        assertEquals(0 until 30, SessionFile.window(30, 10))
        assertEquals(0 until 30, SessionFile.window(30, -1))
    }

    @Test
    fun browseIds() {
        val t = Track(id = "spotify:track:1|odd", name = "S")
        val id = BrowseTree.songId("pl:spotify:playlist:abc", 7, t)
        assertEquals("pl:spotify:playlist:abc" to 7, BrowseTree.parseSongId(id))
        assertEquals(PlayContext("playlist:spotify:playlist:abc", ""), BrowseTree.contextOf(id))
        assertEquals(PlayContext("liked", ""), BrowseTree.contextOf(BrowseTree.songId("pl:spotify:collection:tracks", 0, t)))
        assertEquals(PlayContext("history", ""), BrowseTree.contextOf(BrowseTree.songId("recent", 0, t)))
        assertNull(BrowseTree.parseSongId("q|1|x"))
        assertNull(BrowseTree.contextOf(null))
    }
}
