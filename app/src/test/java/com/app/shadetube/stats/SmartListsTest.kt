package com.app.shadetube.stats

import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of tests/smartlists (smartlists_test.cpp) + SmartMix planning checks. */
class SmartListsTest {
    private val day = 86_400L
    private val now = 1_790_000_000L // 2026-09-21 (UTC), well after the plays below

    private inner class History {
        val tracks = ArrayList<ListenTrack>()
        val plays = ArrayList<Play>()
        val liked = ArrayList<Track>()
        var at = now

        fun song(artist: String, name: String, id: String = ""): Int {
            tracks.add(
                ListenTrack(
                    id = id.ifEmpty { "mb-$artist-$name" }, name = name, artists = listOf(ArtistRef("a-$artist", artist)),
                    albumName = "$artist album", durationMs = 200_000,
                ),
            )
            return tracks.size - 1
        }

        /** `times` streams of 3 minutes, one a day going back from `daysAgo`. */
        fun play(track: Int, times: Int, daysAgo: Long, spacingDays: Long = 1) {
            for (i in 0 until times) plays.add(Play(track, now - (daysAgo + i * spacingDays) * day, 180_000))
        }

        fun input(): SmartLists.Input =
            SmartLists.Input(tracks, PlayList.of(plays.sortedBy { it.startedAt }), liked, at)
    }

    private fun byId(lists: List<SmartList>, id: String) = lists.firstOrNull { it.id == id }
    private fun has(l: SmartList, name: String) = l.tracks.any { it.name == name }

    @Test
    fun rules() {
        assertTrue(SmartLists.build(SmartLists.Input()).isEmpty())
        val h = History()
        // This month: 10 songs streamed 2+ times (the more the higher; each also once long ago, so not a new
        // discovery), one heard only once, one only skipped.
        for (i in 0 until 10) {
            val s = h.song("Month", "m$i")
            h.play(s, 2 + i, 1)
            h.play(s, 1, 200)
        }
        h.play(h.song("Month", "once"), 1, 2)
        val skipped = h.song("Month", "skipped")
        for (i in 0 until 5) h.plays.add(Play(skipped, now - (i + 1) * day, 10_000))
        // New discoveries: first heard 10 days ago, replayed (6 of them).
        for (i in 0 until 6) h.play(h.song("New", "n$i"), 2, 3, 4)
        // Old favorites: 5+ streams, all more than 100 days ago (12 of them).
        for (i in 0 until 12) h.play(h.song("Old", "o$i"), 5 + i, 120)
        // Not songs.
        h.play(h.song("Pod", "Episode", "podcast:1234567890abcdef"), 20, 1)
        h.play(h.song("Radio", "Station", "radio:abc"), 20, 1)
        // Liked: 9 never played, one played a lot (not forgotten), a duplicate and an unplayable one.
        for (i in 0 until 9) h.liked.add(Track(id = "spotify:track:liked$i", name = "liked $i", artists = listOf(ArtistRef("", "Likes"))))
        h.liked.add(h.liked[0])
        h.liked.add(Track(id = "spotify:track:loved", name = "m9", artists = listOf(ArtistRef("spotify:artist:x", "Month"))))
        h.liked.add(h.liked[1].copy(name = "unplayable", playable = false))

        val lists = SmartLists.build(h.input())
        val month = byId(lists, "month")
        assertNotNull(month)
        assertEquals(16, month!!.tracks.size) // + the new discoveries (streamed twice lately)
        assertEquals("m9", month.tracks.first().name) // most streamed first
        assertTrue(!has(month, "once") && !has(month, "skipped"))
        val disc = byId(lists, "discoveries")!!
        assertEquals(6, disc.tracks.size)
        assertFalse(has(disc, "m0")) // first heard 200 days ago
        val red = byId(lists, "rediscover")!!
        assertEquals(12, red.tracks.size)
        assertTrue(red.tracks.all { it.name[0] == 'o' })
        val forgot = byId(lists, "forgotten")!!
        assertEquals(9, forgot.tracks.size) // the duplicate once, the streamed one and the unplayable out
        assertTrue(!has(forgot, "m9") && !has(forgot, "unplayable"))
        val best = byId(lists, "best")
        assertNotNull(best)
        assertFalse(has(best!!, "once"))
        assertTrue(lists.all { l -> l.tracks.all { !it.id.startsWith("podcast:") && !it.id.startsWith("radio:") } })
        // Recency: two songs with the same streams, the recent one ranks higher among the best.
        val r = History()
        val oldSong = r.song("A", "old")
        val newSong = r.song("A", "new")
        r.play(oldSong, 10, 700)
        r.play(newSong, 10, 5)
        for (i in 0 until 10) r.play(r.song("B", "filler$i"), 3, 300)
        val rb = byId(SmartLists.build(r.input()), "best")!!
        assertTrue(rb.tracks.size >= 2)
        assertEquals("new", rb.tracks[0].name)
        assertEquals("old", rb.tracks[1].name)
        // Too little history: nothing but what reaches its minimum.
        val few = History()
        few.play(few.song("X", "a"), 3, 1)
        assertTrue(SmartLists.build(few.input()).isEmpty())
    }

    @Test
    fun mixes() {
        val h = History()
        // Two listening worlds: A, B, C always in the same sessions; D, E, F in other sessions.
        val world1 = ArrayList<Int>()
        val world2 = ArrayList<Int>()
        for (a in listOf("A", "B", "C")) for (i in 0 until 6) world1.add(h.song(a, "$a$i"))
        for (a in listOf("D", "E", "F")) for (i in 0 until 6) world2.add(h.song(a, "$a$i"))
        for (d in 1..60) {
            val w = if (d % 2 == 1) world1 else world2
            var t = now - d * day
            for (k in 0 until 8) {
                val s = w[(d * 7 + k * 5) % w.size]
                h.plays.add(Play(s, t, 180_000))
                t += 190
            }
        }
        val lead = world1[0] // A0: A's top song, heard more (inside world 1's sessions: odd days)
        h.play(lead, 15, 1, 2)
        val lists = SmartLists.build(h.input())
        val m1 = byId(lists, "mix:1")
        val m2 = byId(lists, "mix:2")
        assertNotNull(m1)
        assertNotNull(m2)
        assertEquals(3, m1!!.artists.size)
        assertEquals("A", m1.artists[0])
        assertEquals(setOf("A", "B", "C"), m1.artists.toSet())
        assertEquals(setOf("D", "E", "F"), m2!!.artists.toSet())
        assertTrue(m1.tracks.size in SmartLists.MIX_MIN_FAMILIAR..SmartLists.MIX_FAMILIAR)
        assertEquals(h.tracks[lead].id, m1.seedTrackId)
        assertEquals("a-A", m1.seedArtistId)
        // Same artist rarely twice in a row.
        var repeats = 0
        for (i in 1 until m1.tracks.size) if (m1.tracks[i].artists[0].name == m1.tracks[i - 1].artists[0].name) repeats++
        assertTrue("repeats $repeats", repeats <= 2)
        assertNull(byId(lists, "mix:3")) // no third world
    }

    @Test
    fun seeds() {
        val h = History()
        for (i in 0 until 40) h.play(h.song("Old", "o$i"), 5 + i % 7, 150)
        fun order(ls: List<SmartList>) = byId(ls, "rediscover")?.tracks?.map { it.name } ?: emptyList()
        val a = order(SmartLists.build(h.input()))
        val b = order(SmartLists.build(h.input()))
        assertTrue(a.isNotEmpty() && a == b) // same day: same list
        h.at += day
        val c = order(SmartLists.build(h.input()))
        assertTrue(c.isNotEmpty() && c != a) // the next day: another pick
        assertNotEquals(SmartLists.seedFor("mix:1", 1), SmartLists.seedFor("mix:2", 1))
        assertNotEquals(SmartLists.seedFor("mix:1", 1), SmartLists.seedFor("mix:1", 2))
    }

    @Test
    fun years() {
        val h = History()
        // 2025 (Jan-Jun): 12 songs; 2024: 11 songs; 2023: 3 songs (too few). Timestamps in UTC.
        val y2025 = 1_735_689_600L // 2025-01-01
        val y2024 = 1_704_067_200L // 2024-01-01
        val y2023 = 1_672_531_200L // 2023-01-01
        for (i in 0 until 12) {
            val s = h.song("Y", "y25-$i")
            for (k in 0..i) h.plays.add(Play(s, y2025 + (i * 10 + k) * day, 180_000))
        }
        for (i in 0 until 11) h.plays.add(Play(h.song("Y", "y24-$i"), y2024 + i * day, 180_000))
        for (i in 0 until 3) h.plays.add(Play(h.song("Y", "y23-$i"), y2023 + i * day, 180_000))
        val lists = SmartLists.build(h.input())
        val l25 = byId(lists, "year:2025")!!
        val l24 = byId(lists, "year:2024")!!
        assertEquals(2025, l25.number)
        assertEquals(12, l25.tracks.size)
        assertEquals("y25-11", l25.tracks[0].name)
        assertEquals(11, l24.tracks.size)
        assertNull(byId(lists, "year:2023"))
        // Newest year first among the year lists.
        assertTrue(lists.indexOfFirst { it.id == "year:2025" } < lists.indexOfFirst { it.id == "year:2024" })
    }

    @Test
    fun freshAndTrim() {
        var mix = SmartList(
            id = "mix:1", kind = SmartLists.Kind.Mix, day = 20000,
            tracks = (0 until 12).map { Track(id = "f$it", name = "familiar $it", artists = listOf(ArtistRef("", "A"))) },
            fresh = List(12) { false },
        )
        val cands = ArrayList<Track>()
        fun cand(name: String, artist: String, id: String = "sp") = cands.add(Track(id = id, name = name, artists = listOf(ArtistRef("", artist))))
        cand("familiar 3", "A") // already in the mix
        cand("Known song", "B") // in the history
        for (i in 0 until 20) cand("new $i", "C")
        cand("new 0", "C") // duplicate
        cand("Episode", "P", "podcast:1111111111111111")
        val known = listOf(SmartLists.songKey(cands[1]))
        mix = SmartLists.mixFresh(mix, cands, known)
        assertEquals(mix.tracks.size, mix.fresh.size)
        var freshCount = 0
        for (i in mix.tracks.indices) if (mix.fresh[i]) {
            freshCount++
            assertTrue(mix.tracks[i].name.startsWith("new "))
        }
        assertEquals(SmartLists.MIX_FRESH, freshCount)
        val names = mix.tracks.map { it.name }.toSet()
        assertEquals(mix.tracks.size, names.size) // no duplicates
        assertTrue("Known song" !in names && "Episode" !in names)
        assertTrue(!mix.fresh[0] && !mix.fresh[1] && mix.fresh[2]) // two familiar, then a new one
        // Trim: a mix needs its familiar songs; a list below its minimum is hidden.
        val small = mix.copy(tracks = mix.tracks.take(20), fresh = mix.fresh.take(20))
        assertNotNull(SmartLists.trim(small))
        val tiny = SmartList("month", SmartLists.Kind.Month, tracks = List(SmartLists.MIN_SONGS - 1) { Track() })
        assertNull(SmartLists.trim(tiny))
        val big = SmartLists.trim(SmartList("month", SmartLists.Kind.Month, tracks = List(80) { Track() }))
        assertNotNull(big)
        assertEquals(SmartLists.MAX_SONGS, big!!.tracks.size)
        assertEquals(SmartLists.MAX_SONGS, big.fresh.size)
        // The blocklist: a list emptied below its minimum is hidden.
        assertNull(SmartLists.withoutBlocked(big) { true })
    }

    @Test
    fun timing() {
        val h = History()
        val songs = 20_000
        val playsN = 300_000
        for (i in 0 until songs) h.song("Artist${i % 1500}", "Song $i")
        var x = 88172645463325252L
        for (i in 0 until playsN) {
            x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
            val s = (if (java.lang.Long.remainderUnsigned(x, 7) == 0L) java.lang.Long.remainderUnsigned(x, songs.toLong())
            else java.lang.Long.remainderUnsigned(x, 400)).toInt() // a popular head + a long tail
            h.plays.add(Play(s, now - (playsN - i).toLong() * 300, 150_000))
        }
        val input = h.input()
        val t0 = System.nanoTime()
        val lists = SmartLists.build(input)
        val ms = (System.nanoTime() - t0) / 1e6
        println("        ${lists.size} lists from $playsN plays in %.0f ms".format(ms))
        assertTrue(lists.isNotEmpty())
        assertTrue("$ms ms", ms < 8000) // the C++ Debug budget
    }

    // --- SmartMix (planning) ---------------------------------------------------------------------------------------

    @Test
    fun smartMixKeysAndCandidates() {
        val t = Track(id = "1", name = "Şımarık - 2011 Remaster", artists = listOf(ArtistRef("", "TARKAN")))
        assertEquals("şımarık|tarkan".replace('ı', 'i'), SmartMix.trackKey(t).replace('ı', 'i'))
        assertEquals(SmartMix.trackKey(t), SmartMix.trackKey(t.copy(name = "Şımarık (Live) [HD]")))
        assertEquals("istanbul", SmartMix.foldName("İSTANBUL"))
        val ex = SmartMix.Exclusions().apply { add(Track(id = "x", name = "Şımarık", artists = listOf(ArtistRef("", "Tarkan")))) }
        val pool = listOf(
            t, // same song as the excluded one
            Track(id = "2", name = "A", artists = listOf(ArtistRef("", "P"))),
            Track(id = "3", name = "B", artists = listOf(ArtistRef("", "P"))),
            Track(id = "4", name = "C", artists = listOf(ArtistRef("", "Q"))),
            Track(id = "5", name = "D", artists = listOf(ArtistRef("", "Q")), playable = false),
            Track(id = "6", name = "E", artists = listOf(ArtistRef("", "R"))),
            Track(id = "2", name = "A", artists = listOf(ArtistRef("", "P"))), // twice
        )
        val c = SmartMix.candidates(pool, ex, { it.id == "6" }, 42)
        assertEquals(setOf("2", "3", "4"), c.map { it.id }.toSet())
        assertTrue(c.all { it.recommended })
        assertEquals(c, SmartMix.candidates(pool, ex, { it.id == "6" }, 42)) // seeded
    }

    @Test
    fun smartMixPlacement() {
        // Every 3-4 own songs, never right after the playing one, never two in a row, idempotent.
        val isRec = MutableList(40) { false }
        val pts = SmartMix.insertionPoints(isRec, 0, 7)
        assertTrue(pts.isNotEmpty())
        assertTrue(pts.all { it >= 2 && it <= 1 + SmartMix.WINDOW })
        for (k in 1 until pts.size) assertTrue(pts[k] - pts[k - 1] in SmartMix.MIN_GAP..SmartMix.MAX_GAP)
        val mixed = isRec.toMutableList()
        for (p in pts.reversed()) mixed.add(p, true)
        val again = SmartMix.insertionPoints(mixed, 0, 7)
        // Planned stretches stay as they are (at most the open tail after the last recommendation gets one).
        assertTrue("again $again", again.size <= 1)
        for (p in again) assertTrue(!mixed[p - 1] && (p >= mixed.size || !mixed[p]))
        // Interleave: one recommendation after 3-4 own songs while there are some.
        val own = (0 until 10).map { Track(id = "o$it", name = "o$it") }
        val recs = (0 until 10).map { Track(id = "r$it", name = "r$it") }
        val out = SmartMix.interleave(own, recs, 3)
        assertEquals(own, out.filter { !it.recommended })
        val firstRec = out.indexOfFirst { it.recommended }
        assertTrue(firstRec in 3..4)
        assertTrue(SmartMix.interleave(own.take(2), recs, 3).none { it.recommended })
    }

    @Test
    fun recommenderPoolsAndSeeds() = runBlocking {
        val collection = (0 until 10).map {
            Track(id = "spotify:track:$it", name = "Song $it", artists = listOf(ArtistRef("11111111-2222-3333-4444-55555555555${it % 2}", "Artist ${it % 2}")))
        }
        val seeds = SmartMixRecommender.seedsOf(collection, 5)
        assertEquals(3, seeds.spotify.size)
        assertEquals(2, seeds.artists.size)
        var radios = 0
        val rec = SmartMixRecommender(
            spotify = {
                object : SpotifyRadioSource {
                    override suspend fun radioPlaylist(seedTrackUri: String): String {
                        radios++
                        return "spotify:playlist:radio-$seedTrackUri"
                    }

                    override suspend fun playlistTracks(uri: String, offset: Int, limit: Int) =
                        (0 until 10).map { Track(id = "$uri:$it", name = "R $uri $it", artists = listOf(ArtistRef("", "X$it"))) } +
                            collection.take(1) // a song of the collection: never recommended
                }
            },
            similar = object : SimilarArtistsSource {
                override suspend fun similarArtistIds(artistMbid: String, limit: Int) = listOf("s1")
                override suspend fun topTracks(artistMbid: String, limit: Int) = emptyList<Track>()
            },
            blocked = { it.name.endsWith(" 0") },
        )
        val first = rec.recommend("liked", collection)
        assertTrue(first.isNotEmpty())
        assertTrue(first.none { it.id in collection.map { c -> c.id }.toSet() })
        assertTrue(first.none { it.name.endsWith(" 0") })
        assertEquals(3, radios)
        val second = rec.recommend("liked", collection) // cached for 30 minutes
        assertEquals(3, radios)
        assertEquals(first, second)
        rec.hide("liked", first[0])
        assertTrue(rec.recommend("liked", collection).none { it.id == first[0].id })
    }

    @Test
    fun cacheRoundTrip() {
        val l = SmartList(
            id = "mix:1", kind = SmartLists.Kind.Mix, number = 1, streams = 42, day = 20_000,
            tracks = listOf(Track(id = "a", name = "A", artists = listOf(ArtistRef("x", "X"))), Track(id = "b", name = "B")),
            fresh = listOf(false, true), artists = listOf("X", "Y"), seedTrackId = "spotify:track:1", seedArtistId = "mbid",
        )
        val c = SmartListsService.Cache(listOf(l), 20_000, "user", 123, mapOf("lead" to listOf(Track(id = "f", name = "F"))), 20_000, "user",
            null, -1, "")
        val back = SmartListsService.cacheFromJson(SmartListsService.cacheToJson(c))
        assertEquals(listOf(l), back.lists)
        assertEquals(20_000, back.day)
        assertEquals("user", back.user)
        assertEquals(123, back.plays)
        assertEquals("F", back.fresh!!.getValue("lead")[0].name)
        assertNull(back.liked)
        assertEquals("spotify:track:1", SmartListsService.leadKey(l))
    }
}
