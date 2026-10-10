package com.app.shadetube.scrobble

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Port of tests/scrobble (scrobble_test.cpp; the Discord parts are Windows-only) + request shapes on a fake server. */
class ScrobblerTest {
    private class MemoryStore(var plain: String = "") : CredentialStore {
        var saves = 0
        override fun load() = plain
        override fun save(plain: String): Boolean {
            this.plain = plain
            saves++
            return true
        }

        override fun clear() {
            plain = ""
        }
    }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun md5() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", ScrobbleSign.md5Hex(""))
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", ScrobbleSign.md5Hex("The quick brown fox jumps over the lazy dog"))
    }

    @Test
    fun signature() {
        // Deliberately unsorted, with "format" and "callback" (both excluded from the signature) and UTF-8 text.
        // Expected value: .NET MD5 of the identical UTF-8 string (computed independently for the C++ test).
        val p = listOf(
            "track" to "Dönence",
            "format" to "json",
            "method" to "track.scrobble",
            "timestamp" to "1700000000",
            "artist" to "Barış Manço",
            "sk" to "d580d57f32848f5dcf574d1ce18d78b2",
            "callback" to "cb",
            "api_key" to "0123456789abcdef0123456789abcdef",
            "album" to "Sahibinden İhtiyaçtan",
        )
        assertEquals("1e9e783c9054306e9da537f8a7cb685f", ScrobbleSign.lastfmSignature(p, "fedcba9876543210fedcba9876543210"))
        assertEquals(
            "122db8efff0100fb11599a7629ab9106",
            ScrobbleSign.lastfmSignature(listOf("method" to "auth.getToken", "api_key" to "0123456789abcdef0123456789abcdef"), "fedcba9876543210fedcba9876543210"),
        )
    }

    @Test
    fun listenClock() {
        run { // 200 s track -> threshold 100 s; fires exactly once, at 100 s of real listening.
            val c = ListenClock()
            var wall = 1_000_000L
            c.reset(200_000, wall)
            assertEquals(100_000, c.thresholdMs())
            var fired = 0
            var firedAt = -1
            for (s in 1..150) {
                wall += 1000
                if (c.update(s * 1000L, true, wall)) {
                    fired++
                    firedAt = s
                }
            }
            assertEquals(1, fired)
            assertEquals(100, firedAt)
        }
        run { // A forward seek is not listening: 10 s played, seek to 90 s, then it takes 90 more real seconds.
            val c = ListenClock()
            var wall = 0L
            var pos = 0L
            c.reset(200_000, wall)
            repeat(10) { pos += 1000; wall += 1000; c.update(pos, true, wall) }
            assertEquals(10_000, c.listenedMs)
            pos = 90_000; wall += 1000
            assertFalse(c.update(pos, true, wall)) // seek (+80 s in 1 s of wall time)
            assertEquals(10_000, c.listenedMs)
            var more = 0
            while (true) {
                pos += 1000; wall += 1000
                if (c.update(pos, true, wall)) break
                more++
            }
            assertEquals(90, more + 1)
            assertEquals(180_000, pos)
        }
        run { // Backward seek: not counted itself, but re-listening counts.
            val c = ListenClock()
            var wall = 0L
            var pos = 0L
            c.reset(200_000, wall)
            repeat(50) { pos += 1000; wall += 1000; c.update(pos, true, wall) }
            pos = 0; wall += 1000
            c.update(pos, true, wall) // back to the start
            assertEquals(50_000, c.listenedMs)
            var fired = false
            var s = 0
            while (s < 50 && !fired) {
                pos += 1000; wall += 1000
                fired = c.update(pos, true, wall)
                s++
            }
            assertTrue(fired)
            assertEquals(100_000, c.listenedMs)
        }
        run { // Paused: the wall clock runs, the position does not; a seek while paused is not counted.
            val c = ListenClock()
            var wall = 0L
            c.reset(200_000, wall)
            wall += 5_000
            c.update(5_000, true, wall)
            repeat(60) { wall += 1000; c.update(5_000, false, wall) }
            wall += 60_000
            c.update(40_000, false, wall) // seek while paused (wall allows it, but nothing played)
            assertEquals(5_000, c.listenedMs)
        }
        run { // Coarse 2 s ticks and a delayed tick still count fully.
            val c = ListenClock()
            var wall = 0L
            var pos = 0L
            c.reset(100_000, wall)
            repeat(20) { pos += 2000; wall += 2000; c.update(pos, true, wall) }
            pos += 7000; wall += 7000
            c.update(pos, true, wall) // the main thread was busy for 7 s
            assertEquals(47_000, c.listenedMs)
        }
        run { // Rule edges.
            val c = ListenClock()
            c.reset(30_000, 0)
            assertEquals(-1, c.thresholdMs()) // not longer than 30 s -> never
            var wall = 0L
            var fired = false
            for (s in 1..30) {
                wall += 1000
                fired = fired or c.update(s * 1000L, true, wall)
            }
            assertFalse(fired)
            c.reset(31_000, 0)
            assertEquals(15_500, c.thresholdMs())
            c.reset(20 * 60_000L, 0)
            assertEquals(240_000, c.thresholdMs()) // 4-minute cap
            c.reset(0, 0)
            assertEquals(240_000, c.thresholdMs()) // unknown duration: the 4-minute rule alone
            c.learnDuration(100_000)
            assertEquals(50_000, c.thresholdMs())
            c.learnDuration(300_000) // a known duration is not overwritten
            assertEquals(50_000, c.thresholdMs())
        }
    }

    @Test
    fun scrobblerRule() {
        var wall = 0L
        var enabled = true
        val unixStart = 1_700_000_000L
        val s = Scrobbler(MemoryStore(), isEnabled = { enabled }, clock = { wall }, unixNow = { unixStart + wall / 1000 })
        val scrobbles = ArrayList<Pair<String, Long>>()
        s.onScrobble = { t, ts -> scrobbles.add(t.title to ts) }

        val ct = Track(
            id = "spotify:track:1", name = "Dönence",
            artists = listOf(ArtistRef("a1", "Barış Manço"), ArtistRef("a2", "Kurtalan Ekspres")),
            album = AlbumRef(name = "Sahibinden İhtiyaçtan"), durationMs = 180_000,
        )
        val t = ScrobbleTrack.from(ct)
        assertEquals("Barış Manço", t.artist)
        assertEquals(ct.artistLine, t.artistCredit)
        assertEquals(180_000, t.durationMs)

        var pos = 0L
        // One second of playback: advance the injected clock and the position together.
        fun play(seconds: Int, playerDurationMs: Long = 0) {
            repeat(seconds) {
                wall += 1000
                pos += 1000
                s.onProgress(pos, true, playerDurationMs)
            }
        }

        s.onTrackStarted(t)
        play(89)
        assertTrue(scrobbles.isEmpty())
        // A re-resolve of the same track (same id) mid-play keeps the listened time.
        s.onTrackStarted(t)
        play(1) // 90 s = 50% of 180 s
        assertEquals(1, scrobbles.size)
        // Timestamp = when the play started: the first playing sample (position 1 s) is back-dated by its position.
        assertEquals(unixStart, scrobbles[0].second)
        play(60)
        assertEquals(1, scrobbles.size) // once per play

        // Same id after the scrobble = a repeat -> a new play that can scrobble again.
        s.onTrackStarted(t)
        pos = 0
        play(90)
        assertEquals(2, scrobbles.size)

        // Disabled: nothing is reported.
        enabled = false
        s.onTrackStarted(t.copy(id = "spotify:track:2"))
        pos = 0
        play(120)
        assertEquals(2, scrobbles.size)
        enabled = true

        // Unknown catalog duration learned from the player; stopped plays never scrobble.
        s.onTrackStarted(t.copy(id = "mb:3", durationMs = 0))
        pos = 0
        play(39, 80_000)
        assertEquals(2, scrobbles.size)
        play(1, 80_000) // 40 s = 50% of 80 s
        assertEquals(3, scrobbles.size)
        s.onTrackStarted(t.copy(id = "mb:4"))
        s.onStopped()
        play(200)
        assertEquals(3, scrobbles.size)

        // Long track (4-minute cap): scrobbled at 4:00. A re-resolve afterwards continues at the same position ->
        // still the same play, never a second scrobble.
        val t5 = t.copy(id = "mb:5", durationMs = 20 * 60_000L)
        s.onTrackStarted(t5)
        pos = 0
        play(240)
        assertEquals(4, scrobbles.size)
        s.onTrackStarted(t5) // same id, continues at 4:00
        play(600)
        assertEquals(4, scrobbles.size)

        // Same id restarted from the top before it qualified (clicked again / repeat): a new play, the earlier
        // partial listen does not carry over.
        val t6 = t.copy(id = "mb:6")
        s.onTrackStarted(t6)
        pos = 0
        play(60)
        s.onTrackStarted(t6)
        pos = 0
        play(60)
        assertEquals(4, scrobbles.size)
        play(30) // 90 s of the new play = 50% of 180 s
        assertEquals(5, scrobbles.size)
        assertTrue(s.pendingLastfm == 0 && s.pendingListenbrainz == 0) // nothing connected -> nothing queued

        // Radio stations and podcast episodes are no songs: the play stops.
        s.onTrackChanged(Track(id = "radio:abcdef", name = "Station"))
        play(300)
        assertEquals(5, scrobbles.size)
        s.onTrackChanged(Track(id = "podcast:feed#ep", name = "Episode", artists = listOf(ArtistRef("", "Show")), durationMs = 600_000))
        play(300)
        assertEquals(5, scrobbles.size)
    }

    @Test
    fun store() {
        val secret = "s3cr3t-value-that-must-not-appear"
        val store = MemoryStore()
        run {
            val s = Scrobbler(store)
            s.lastfmSetKeys("  0123456789abcdef0123456789abcdef \r\n", " $secret ")
            assertEquals(1, store.saves)
            assertTrue(s.lastfmHasKeys())
            assertFalse(s.lastfmConnected())
            assertEquals("0123456789abcdef0123456789abcdef", s.lastfmApiKey())
            assertEquals("0123456789abcdef0123456789abcdef", s.state.value.lastfmApiKey)
            assertTrue(s.state.value.lastfmHasKeys)
            s.lastfmSetKeys("0123456789abcdef0123456789abcdef", "") // blank secret + same key = keep
            assertEquals(1, store.saves)
            assertTrue(s.lastfmHasKeys())
        }
        // The same content shape as the C++ plaintext before DPAPI.
        val j = parseJsonOrNull(store.plain) as JsonObject
        assertEquals(1, (j["v"] as JsonPrimitive).content.toInt())
        val lf = j["lastfm"] as JsonObject
        assertEquals(setOf("apiKey", "secret", "sessionKey", "user"), lf.keys)
        assertEquals(secret, (lf["secret"] as JsonPrimitive).content)
        assertEquals(setOf("token", "user"), (j["listenbrainz"] as JsonObject).keys)
        run {
            val s = Scrobbler(store)
            s.load()
            assertTrue(s.lastfmHasKeys())
            assertTrue(s.lastfmHasSecret())
            assertEquals("0123456789abcdef0123456789abcdef", s.lastfmApiKey())
            assertFalse(s.lastfmConnected())
            assertFalse(s.listenbrainzConnected())
            s.lastfmSetKeys("", "")
            assertFalse(s.lastfmHasKeys())
        }
        assertEquals("", store.plain) // nothing left to store -> file removed
        run {
            val s = Scrobbler(MemoryStore("garbage, not JSON"))
            s.load() // must not throw
            assertFalse(s.lastfmHasKeys())
        }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun form(body: String): Map<String, String> = body.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    @Test
    fun listenBrainzRequests() = runBlocking {
        val base = server.url("/1/").toString()
        var wall = 0L
        val s = Scrobbler(MemoryStore(), scope(), isEnabled = { true }, clock = { wall }, unixNow = { 1_700_000_000L + wall / 1000 },
            listenbrainzApi = base)
        // Empty token: no request.
        assertEquals(ScrobbleResult.Failed(ScrobbleError.EmptyToken), s.listenbrainzSetToken("   "))
        // Rejected token.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401,"message":"Invalid authorization token.","valid":false}"""))
        assertEquals(ScrobbleResult.Failed(ScrobbleError.InvalidToken), s.listenbrainzSetToken("bogus"))
        assertEquals("Token bogus", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        // Service down.
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>down</html>"))
        assertEquals(ScrobbleResult.Failed(ScrobbleError.ListenBrainzUnavailable(503)), s.listenbrainzSetToken("tok"))
        server.takeRequest(5, TimeUnit.SECONDS)
        // Valid token.
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"Token valid.","valid":true,"user_name":"shades"}"""))
        assertEquals(ScrobbleResult.Ok("shades"), s.listenbrainzSetToken(" tok \n"))
        val v = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/1/validate-token", v.path)
        assertEquals("Token tok", v.getHeader("Authorization"))
        assertTrue(s.listenbrainzConnected())
        assertEquals("shades", s.state.value.listenbrainzUser)

        // playing_now on the first playing sample, then the scrobble ("single").
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        val t = ScrobbleTrack("spotify:track:1", "Dönence", "Barış Manço", "Barış Manço, Kurtalan Ekspres", "Sahibinden İhtiyaçtan", 180_000)
        s.onTrackStarted(t)
        var pos = 0L
        repeat(90) {
            wall += 1000
            pos += 1000
            s.onProgress(pos, true)
        }
        // Both requests run on workers: their order at the server is not fixed.
        val bodies = (0 until 2).map {
            val r = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/1/submit-listens", r.path)
            assertEquals("POST", r.method)
            parseJsonOrNull(r.body.readUtf8()) as JsonObject
        }
        val nj = bodies.first { (it["listen_type"] as JsonPrimitive).content == "playing_now" }
        val np = (nj["payload"] as JsonArray)[0] as JsonObject
        assertFalse(np.containsKey("listened_at"))
        val meta = np["track_metadata"] as JsonObject
        assertEquals("Barış Manço, Kurtalan Ekspres", (meta["artist_name"] as JsonPrimitive).content)
        assertEquals("Dönence", (meta["track_name"] as JsonPrimitive).content)
        assertEquals("Sahibinden İhtiyaçtan", (meta["release_name"] as JsonPrimitive).content)
        val info = meta["additional_info"] as JsonObject
        assertEquals("ShadeTube", (info["submission_client"] as JsonPrimitive).content)
        assertEquals("180000", (info["duration_ms"] as JsonPrimitive).content)
        val sj = bodies.first { (it["listen_type"] as JsonPrimitive).content == "single" }
        val listen = (sj["payload"] as JsonArray)[0] as JsonObject
        assertEquals("1700000000", (listen["listened_at"] as JsonPrimitive).content) // back-dated to the start
        val deadline = System.currentTimeMillis() + 5000
        while (s.pendingListenbrainz != 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(0, s.pendingListenbrainz)

        // A 401 on submit drops the token (reconnect in Settings); the play stays queued.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad token"}""")) // playing_now
        s.onTrackStarted(t.copy(id = "spotify:track:2"))
        wall += 1000
        s.onProgress(1000, true)
        server.takeRequest(5, TimeUnit.SECONDS)
        val d2 = System.currentTimeMillis() + 5000
        while (s.listenbrainzConnected() && System.currentTimeMillis() < d2) Thread.sleep(10)
        assertFalse(s.listenbrainzConnected())
    }

    @Test
    fun lastfmRequests() = runBlocking {
        val api = server.url("/2.0/").toString()
        val store = MemoryStore()
        var wall = 0L
        val s = Scrobbler(store, scope(), isEnabled = { true }, clock = { wall }, unixNow = { 1_700_000_000L + wall / 1000 }, lastfmApi = api)
        assertEquals(ScrobbleResult.Failed(ScrobbleError.NoKeys), s.lastfmBeginAuth())
        val key = "0123456789abcdef0123456789abcdef"
        val secret = "fedcba9876543210fedcba9876543210"
        s.lastfmSetKeys(key, secret)
        // A bogus key: Last.fm's error 10 -> InvalidApiKey.
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":10,"message":"Invalid API key - You must be granted a valid key by last.fm"}"""))
        val bad = s.lastfmBeginAuth()
        assertTrue(bad is ScrobbleResult.Failed && (bad.error as ScrobbleError.Lastfm).kind == LastfmErrorKind.InvalidApiKey)
        val r1 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val f1 = form(r1.body.readUtf8())
        assertEquals("auth.getToken", f1["method"])
        assertEquals("json", f1["format"])
        assertEquals(ScrobbleSign.lastfmSignature(listOf("method" to "auth.getToken", "api_key" to key), secret), f1["api_sig"])
        assertFalse(s.lastfmAuthPending())
        assertEquals(ScrobbleResult.Failed(ScrobbleError.NoPendingAuth), s.lastfmFinishAuth()) // no pending token
        // An HTML outage page with 200 is not a success.
        server.enqueue(MockResponse().setBody("<html>maintenance</html>"))
        val html = s.lastfmBeginAuth()
        assertTrue(html is ScrobbleResult.Failed && (html.error as ScrobbleError.Lastfm).code == -1)
        server.takeRequest(5, TimeUnit.SECONDS)
        // The real flow: token -> approval URL -> session.
        server.enqueue(MockResponse().setBody("""{"token":"tok123"}"""))
        val url = s.lastfmBeginAuth()
        assertEquals(ScrobbleResult.Ok("https://www.last.fm/api/auth/?api_key=$key&token=tok123"), url)
        server.takeRequest(5, TimeUnit.SECONDS)
        assertTrue(s.lastfmAuthPending())
        server.enqueue(MockResponse().setBody("""{"session":{"name":"shades","key":"sk-1","subscriber":0}}"""))
        assertEquals(ScrobbleResult.Ok("shades"), s.lastfmFinishAuth())
        val r2 = form(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals("auth.getSession", r2["method"])
        assertEquals("tok123", r2["token"])
        assertTrue(s.lastfmConnected())
        assertTrue(store.plain.contains("sk-1"))

        // Now playing, then track.scrobble with the play's start.
        server.enqueue(MockResponse().setBody("""{"nowplaying":{}}"""))
        server.enqueue(MockResponse().setBody("""{"scrobbles":{"@attr":{"accepted":1,"ignored":0}}}"""))
        val t = ScrobbleTrack("spotify:track:1", "Dönence", "Barış Manço", "", "Sahibinden İhtiyaçtan", 180_000)
        s.onTrackStarted(t)
        var pos = 0L
        repeat(90) {
            wall += 1000
            pos += 1000
            s.onProgress(pos, true)
        }
        // Both requests run on workers: their order at the server is not fixed.
        val forms = (0 until 2).map { form(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()) }
        val np = forms.first { it["method"] == "track.updateNowPlaying" }
        assertEquals("sk-1", np["sk"])
        assertEquals("Barış Manço", np["artist"])
        assertEquals("180", np["duration"])
        val sc = forms.first { it["method"] == "track.scrobble" }
        assertEquals("1700000000", sc["timestamp"])
        assertEquals("Dönence", sc["track"])
        assertEquals("Sahibinden İhtiyaçtan", sc["album"])
        val expected = ScrobbleSign.lastfmSignature(sc.filterKeys { it != "api_sig" }.toList(), secret)
        assertEquals(expected, sc["api_sig"])
        assertNotNull(sc["api_key"])
        val deadline = System.currentTimeMillis() + 5000
        while (s.pendingLastfm != 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(0, s.pendingLastfm)

        // Error 9 (session key rejected) drops the session.
        server.enqueue(MockResponse().setBody("""{"error":9,"message":"Invalid session key"}"""))
        s.onTrackStarted(t.copy(id = "spotify:track:2"))
        wall += 1000
        s.onProgress(1000, true)
        server.takeRequest(5, TimeUnit.SECONDS)
        val d2 = System.currentTimeMillis() + 5000
        while (s.lastfmConnected() && System.currentTimeMillis() < d2) Thread.sleep(10)
        assertFalse(s.lastfmConnected())
        assertTrue(s.lastfmHasKeys())
    }
}
