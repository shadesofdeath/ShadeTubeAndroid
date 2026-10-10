package com.app.shadetube.podcasts

import com.app.shadetube.catalog.isPodcastId
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Port of tests/podcasts/podcasts_test.cpp: model, store. */
class PodcastModelStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun episode(guid: String, published: Long = 1_700_000_000, durationMs: Long = 600_000) = Episode(
        guid = guid,
        feedUrl = "https://feeds.example/show",
        title = "Episode $guid",
        show = "Show",
        url = "https://cdn.example/$guid.mp3",
        published = published,
        durationMs = durationMs,
        image = "https://img.example/x.jpg",
        description = "d".repeat(3000),
    )

    private fun throwsPodcastError(f: () -> Unit): Boolean = try {
        f()
        false
    } catch (e: PodcastError) {
        true
    }

    @Test
    fun model() {
        val e = episode("g1")
        val id = e.id
        assertEquals(episodeId(e.feedUrl, "g1"), id)
        assertTrue(id.startsWith("podcast:") && id.length == 8 + 16)
        assertTrue(isPodcastId(id))
        assertFalse(isPodcastId("podcast:"))
        assertFalse(isPodcastId("spotify:track:x"))
        assertTrue(episodeId("https://a", "g") != episodeId("https://b", "g"))
        // Byte-compatible with the C++ ids: FNV-1a 64 over "feed\nguid".
        var h = -0x340d631b7bdddcdbL
        for (b in "https://a\ng".toByteArray()) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        assertEquals("podcast:" + java.lang.Long.toUnsignedString(h, 16).padStart(16, '0'), episodeId("https://a", "g"))

        val t = e.toTrack()
        assertTrue(t.id == id && t.name == "Episode g1" && t.artistLine == "Show" && t.album.name == "Show")
        assertTrue(t.durationMs == 600_000 && t.album.images.size == 1 && t.addedAt == e.published)
        assertTrue(t.isPodcastEpisode)

        assertFalse(playedAt(0, 600_000))
        assertFalse(playedAt(500_000, 600_000))
        assertTrue(playedAt(570_000, 600_000)) // 95 %
        assertTrue(playedAt(3_571_000, 3_600_000)) // last 30 s of an hour
        assertFalse(playedAt(3_000_000, 3_600_000))
        assertFalse(playedAt(100, 0))

        assertEquals("audio/mpeg", mimeFor("audio/mpeg", "https://a/x"))
        assertEquals("audio/mp4", mimeFor("audio/x-m4a", "https://a/x"))
        assertEquals("audio/mpeg", mimeFor("audio/mpeg; charset=binary", "https://a/x"))
        assertEquals("audio/mp4", mimeFor("", "https://a/x.m4a?t=1"))
        assertEquals("audio/mpeg", mimeFor("application/octet-stream", "https://a/x.mp3"))
        assertEquals("", mimeFor("", "https://a/stream"))

        assertEquals("a_b_c______d", sanitizeFileName("a/b:c*?\"<>|d"))
        assertEquals("trailing dots", sanitizeFileName("  trailing dots... "))
        assertEquals("_CON", sanitizeFileName("CON"))
        assertEquals("_nul.txt", sanitizeFileName("nul.txt"))
        assertEquals("_", sanitizeFileName(""))
        assertEquals(100, sanitizeFileName("x".repeat(300)).length)

        val root = File("music-root")
        val named = e.copy(published = 1704103200, title = "Part 1: Start?", show = "My/Show", mimeType = "audio/x-m4a", url = "https://cdn.example/file")
        assertEquals(File(File(File(root, "Podcasts"), "My_Show"), "2024-01-01 Part 1_ Start_.m4a"), downloadPath(root, named))
        val named2 = named.copy(published = 0, mimeType = "", url = "https://cdn.example/file.mp3?x")
        assertEquals(File(File(File(root, "Podcasts"), "My_Show"), "Part 1_ Start_.mp3"), downloadPath(root, named2))
        assertTrue(downloadPath(root, named2.copy(url = "https://cdn.example/file")).name.endsWith(".mp3"))

        // JSON round trip
        val back = PodcastJson.episodeFrom(PodcastJson.episode(named))
        assertTrue(back.id == named.id && back.title == named.title && back.url == named.url && back.description == named.description)
        assertTrue(throwsPodcastError { PodcastJson.episodeFrom(JsonObject(emptyMap())) })
        val show = PodcastParsers.parseFeed(PodcastParsingTest.FEED, "https://feeds.example/show.xml")
        val show2 = PodcastJson.showFrom(PodcastJson.show(show))
        assertTrue(show2.title == show.title && show2.episodes.size == show.episodes.size && show2.categories == show.categories)
        assertEquals(show.episodes, show2.episodes) // notes HTML included in the feed cache
    }

    @Test
    fun store() {
        val file = File(tmp.root, "podcasts.json")
        val s = PodcastStore()
        s.load(file)
        assertTrue(s.subscriptionsNow().isEmpty() && !s.isDirty)
        val rev0 = s.revision.value

        val show = Show(
            feedUrl = "https://feeds.example/show",
            title = "Show",
            author = "Author",
            image = "https://img.example/show.jpg",
            episodes = listOf(episode("b", 1_700_000_200), episode("a", 1_700_000_100)),
        )
        s.subscribe(show, false, 1_700_001_000)
        assertTrue(s.isSubscribed(show.feedUrl))
        assertEquals(rev0 + 1, s.revision.value)
        assertEquals(1_700_000_200L, s.subscription(show.feedUrl)!!.seenUntil)
        assertEquals(0, s.newEpisodeCountNow())
        assertNotNull(s.find(show.episodes[0].id))
        s.subscribe(show, false, 1) // again: no-op
        assertEquals(1, s.subscriptionsNow().size)
        assertEquals(1, s.subscriptions.value.size)

        // two new episodes arrive, then the show page is seen
        val fresh = show.copy(title = "Show (renamed)", episodes = listOf(episode("d", 1_700_000_400), episode("c", 1_700_000_300)) + show.episodes)
        assertEquals(2, s.refreshed(fresh, 1_700_002_000))
        assertEquals(2, s.newEpisodeCountNow())
        assertEquals(2, s.newEpisodeCount.value)
        assertEquals("Show (renamed)", s.subscription(show.feedUrl)!!.title)
        assertEquals(0, s.refreshed(fresh, 1_700_003_000)) // nothing more
        s.setPlayed(fresh.episodes[0], true, 1_700_003_100)
        assertEquals(1, s.newEpisodeCountNow())
        s.markSeen(show.feedUrl, 1_700_000_400)
        assertEquals(0, s.newEpisodeCountNow())
        assertEquals(0, s.newEpisodeCount.value)
        assertEquals(0, s.refreshed(fresh, 1_700_004_000))
        assertEquals(0, s.newEpisodeCountNow())

        // positions, played rule, resume, continue listening
        val a = show.episodes[1]
        val b = show.episodes[0]
        s.setPosition(a, 0, 600_000, 10) // nothing to remember
        assertNull(s.state(a.id))
        s.setPosition(a, 120_000, 600_000, 20)
        assertEquals(120_000L, s.state(a.id)!!.positionMs)
        assertEquals(117_000L, s.resumePosition(a.id))
        s.setPosition(b, 5_000, 600_000, 30)
        assertEquals(0L, s.resumePosition(b.id)) // under 10 s: from the start
        val progress = s.inProgress(10)
        assertTrue(progress.size == 2 && progress[0].id == b.id)
        s.setPosition(a, 590_000, 600_000, 40) // the end: played, position cleared
        assertTrue(s.isPlayed(a.id) && s.resumePosition(a.id) == 0L)
        assertEquals(1, s.inProgress(10).size)
        s.setPosition(a, 60_000, 600_000, 50) // listening again
        assertTrue(!s.isPlayed(a.id) && s.state(a.id)!!.positionMs == 60_000L)
        s.setPlayed(a, false, 60)
        assertEquals(0L, s.state(a.id)!!.positionMs)

        // downloads
        s.setDownloaded(b, "/x/b.mp3", 1234, 70)
        s.setDownloaded(a, "/x/a.mp3", 99, 80)
        assertEquals("/x/b.mp3", s.downloadedFile(b.id))
        val dl = s.downloaded()
        assertTrue(dl.size == 2 && dl[0].id == a.id)
        s.clearDownload(a.id)
        assertTrue(s.downloadedFile(a.id).isEmpty() && s.downloaded().size == 1)

        // queue window + persistence
        val many = (0 until 700).map { episode("q$it") }
        s.rememberQueue(many, 650)
        assertTrue(s.save())
        assertFalse(File(file.path + ".tmp").exists())
        val r = PodcastStore()
        r.load(file)
        assertTrue(r.subscriptionsNow().size == 1 && r.subscription(show.feedUrl)!!.title == "Show (renamed)")
        assertTrue(r.state(b.id)!!.positionMs == 5_000L && r.downloadedFile(b.id) == "/x/b.mp3")
        assertNull(r.find("podcast:0000000000000000"))
        assertNotNull(r.find(many[650].id)) // kept: in the window around the playing one
        assertNotNull(r.find(many[699].id))
        assertNull(r.find(many[10].id)) // dropped: more than 100 before it
        assertTrue(r.find(b.id)!!.description.toByteArray().size < 1600) // stored notes are capped
        r.unsubscribe(show.feedUrl)
        assertTrue(r.subscriptionsNow().isEmpty())

        // the state bound: the oldest go first, downloads stay
        val big = PodcastStore()
        big.load(File(tmp.root, "big.json"))
        big.setDownloaded(episode("keep"), "/keep.mp3", 1, 0)
        for (i in 0 until PodcastStore.MAX_STATES + 20) big.setPosition(episode("s$i"), 60_000, 600_000, 100L + i)
        assertTrue(big.inProgress(100000).size <= PodcastStore.MAX_STATES)
        assertTrue(big.downloadedFile(episode("keep").id).isNotEmpty())
        assertNull(big.state(episode("s0").id))

        // damaged file
        val badFile = File(tmp.root, "bad.json")
        badFile.writeText("{ not json")
        val bad = PodcastStore()
        bad.load(badFile)
        assertTrue(bad.subscriptionsNow().isEmpty())
        assertTrue(File(tmp.root, "bad.json.bad").exists())
    }

    @Test
    fun storeJsonShape() {
        val file = File(tmp.root, "podcasts.json")
        val s = PodcastStore()
        s.load(file)
        val e = episode("x").copy(notesHtml = "<p>html</p>")
        s.subscribe(Show(feedUrl = e.feedUrl, title = "Show", episodes = listOf(e)), true, 5)
        s.setPosition(e, 42_000, 0, 6)
        assertTrue(s.save())
        val text = file.readText()
        // The C++ keys, the raw notes HTML left out of podcasts.json.
        for (key in listOf("\"version\":1", "\"subscriptions\"", "\"episodes\"", "\"queue\"", "\"feed\"", "\"since\":5", "\"manual\":true",
            "\"pos\":42000", "\"updated\":6", "\"guid\":\"x\"")) assertTrue(key, text.contains(key))
        assertFalse(text.contains("\"html\""))
    }
}
