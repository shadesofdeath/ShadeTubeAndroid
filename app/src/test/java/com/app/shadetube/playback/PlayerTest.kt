package com.app.shadetube.playback

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.RepeatMode
import com.app.shadetube.core.Settings
import com.app.shadetube.youtube.Match
import com.app.shadetube.youtube.MatchFailure
import com.app.shadetube.youtube.Resolved
import com.app.shadetube.youtube.ResolvedStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * The Player on a fake engine (port of tests/playback part 4 and tests/smartshuffle part 2, which ran a real Player on
 * generated WAVs): skips, endless hooks, extend, header play / explicit picks, error advance, failure streaks, prefetch
 * + gapless handoff + crossfade length, speed, YouTube retries, the session window, recommendations in the queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var settings = Settings()
    private lateinit var engine: FakeEngine
    private lateinit var resolver: FakeResolver
    private lateinit var player: Player
    private val started = ArrayList<String>()
    private var low = 0
    private val blocked = HashSet<String>()

    /** Ids served from "disk" (everything but the "yt" ones); "x" is a missing file. */
    private val local = LocalSourceResolver { id -> if (id.startsWith("yt")) null else LocalSource("file:///music/$id.wav", "audio/wav") }

    private fun T(id: String, durationMs: Int = 1200, rec: Boolean = false) =
        Track(id = id, name = "Track $id", artists = listOf(ArtistRef("", "Tester")), durationMs = durationMs, recommended = rec)

    private fun newPlayer(): Player {
        val p = Player(
            engine = engine,
            resolver = resolver,
            scope = scope,
            lowDispatcher = dispatcher,
            settings = { settings },
            updateSettings = { settings = it(settings) },
            sessionFile = { tmp.root.resolve("session.json") },
            nowMs = { scheduler.currentTime },
            sessionWriter = { it() },
        )
        p.localSources.add(local)
        p.shouldSkip = { it.id in blocked }
        p.onQueueLow = { low++ }
        scope.launch { p.events.collect { if (it is PlayerEvent.TrackStarted) started.add(it.track.id) } }
        return p
    }

    @Before
    fun setUp() {
        engine = FakeEngine(scope)
        resolver = FakeResolver()
        player = newPlayer()
        run()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun run() = scheduler.runCurrent()
    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    @Test
    fun skipsEndlessHooksAndExtend() {
        blocked.add("b")
        // Auto-advance passes over "b"; the queue runs out -> onQueueLow; extend() continues with the new tracks.
        player.playContext(listOf(T("a"), T("b"), T("c")), 0, PlayContext("test", "Test"))
        run()
        val gen = player.queueGeneration
        assertEquals(Status.Playing, player.statusNow)
        engine.finish()
        run()
        engine.finish()
        run()
        assertEquals(listOf("a", "c"), started)
        assertEquals(Status.Idle, player.statusNow)
        assertTrue(low >= 2) // "a" started with one playable item left, then "c" (none left) / the end
        assertEquals(0, player.remainingPlayable())
        assertEquals(3, player.extend(listOf(T("b"), T("d"), T("e"))))
        run()
        assertEquals("d", started[2]) // the blocked "b" at the front of the new tracks is passed over
        engine.finish()
        run()
        engine.finish()
        run()
        assertEquals(4, started.size)
        assertEquals("e", started[3])
        assertEquals(gen, player.queueGeneration) // extend() keeps the queue

        // Header play (no start index) starts on a track the player may pick; an explicit pick plays a blocked one.
        started.clear()
        player.playContext(listOf(T("b"), T("c")), -1, PlayContext("test2", "Test"))
        run()
        assertEquals(listOf("c"), started)
        assertTrue(player.queueGeneration > gen)
        player.playContext(listOf(T("a"), T("b"), T("c")), 1, PlayContext("test3", "Test"))
        run()
        assertEquals("b", started.last())
        player.next()
        run()
        assertEquals("c", started.last())
        player.previous() // just started: goes back, over "b"
        run()
        assertEquals("a", started.last())
        assertEquals(1, player.remainingPlayable())
        val n = started.size
        player.playContext(listOf(T("b")), -1, PlayContext("test4", "Test")) // nothing the player may pick by itself
        run()
        assertEquals(n, started.size)
        assertEquals(PlaybackError.AllBlocked, player.lastError.value)

        // replaceUpcoming: the current track keeps playing, the rest of the queue is replaced.
        player.replaceUpcoming(listOf(T("d"), T("e")), PlayContext("radio", "Radyo"))
        run()
        assertEquals("a", player.currentTrack!!.id)
        assertEquals(3, player.items.size)
        assertEquals("radio", player.playContext.uri)
        assertEquals(n, started.size) // no track change

        assertEquals(1, player.firstPlayable(listOf(T("b"), T("c"))))
        assertEquals(-1, player.firstPlayable(listOf(T("b"))))
    }

    @Test
    fun failuresAdvanceAndStop() {
        blocked.add("b")
        engine.failing.add("x")
        // A missing file: an error, then (1.5 s later) the next track.
        player.playContext(listOf(T("x"), T("c")), 0, PlayContext("test5", "Test"))
        run()
        assertEquals(Status.Error, player.statusNow)
        assertEquals(PlaybackError.FileUnreadable, player.lastError.value)
        advance(1400)
        assertEquals(listOf("x"), started)
        advance(200)
        assertEquals(listOf("x", "c"), started)
        engine.finish()
        run()
        assertEquals(Status.Idle, player.statusNow)
        // Its only playable track fails with repeat on: one stop and a message, never a loop back onto it.
        player.setRepeat(RepeatMode.All)
        started.clear()
        player.playContext(listOf(T("x"), T("b")), 0, PlayContext("test6", "Test"))
        run()
        advance(1600)
        advance(5000)
        assertEquals(1, started.size)
        assertEquals(Status.Idle, player.statusNow)
        assertEquals(PlaybackError.NothingPlayable, player.lastError.value)
        assertEquals(RepeatMode.All, settings.repeat)
    }

    @Test
    fun prefetchGaplessAndCrossfade() {
        settings = settings.copy(crossfadeSec = 2)
        player.playContext(listOf(T("f", 5000), T("g", 5000)), 0, PlayContext("xfade", "Test"))
        run()
        assertEquals(1, engine.opened.size)
        engine.pos = 4000 // within the 25 s preload window
        advance(1100)
        val pre = engine.preloaded
        assertNotNull(pre)
        assertEquals("g", pre!!.track.id)
        assertEquals(2000, pre.crossfadeMs)
        // The engine hands over by itself: no new open, the player follows.
        engine.finish()
        run()
        assertEquals(listOf("f", "g"), started)
        assertEquals(1, engine.opened.size)
        assertEquals(Status.Playing, player.statusNow)

        // Two consecutive tracks of one album stay gapless.
        val album = AlbumRef("album-1", "Album")
        val a = T("f", 5000).copy(album = album, trackNumber = 1)
        val b = T("g", 5000).copy(album = album, trackNumber = 2)
        player.playContext(listOf(a, b), 0, PlayContext("album", "Test"))
        run()
        engine.pos = 4000
        advance(1100)
        assertEquals(0, engine.preloaded!!.crossfadeMs)

        // Any speed but 1x never crossfades; the speed is remembered per kind.
        settings = settings.copy(musicSpeed = 2f)
        player.playContext(listOf(T("f", 5000), T("g", 5000)), 0, PlayContext("speed", "Test"))
        run()
        assertEquals(2f, engine.opened.last().speed)
        engine.pos = 4000
        advance(1100)
        assertEquals(0, engine.preloaded!!.crossfadeMs)
        assertEquals(2f, player.speed())
        player.setSpeed(1f)
        assertEquals(1f, settings.musicSpeed)
        assertEquals(1f, engine.curSpeed)
        assertNull(engine.preloaded) // the prepared item carried the old speed: prepared again
    }

    @Test
    fun skippedPreparedItemIsDropped() {
        player.playContext(listOf(T("a", 5000), T("b", 5000), T("c", 5000)), 0, PlayContext("t", "T"))
        run()
        engine.pos = 4000
        advance(1100)
        assertEquals("b", engine.preloaded!!.track.id)
        blocked.add("b")
        player.skipRulesChanged()
        assertNull(engine.preloaded)
        advance(1100)
        assertEquals("c", engine.preloaded!!.track.id)
    }

    @Test
    fun youtubeRetriesAndLoudness() {
        settings = settings.copy(loudnessTarget = -14, normalizeVolume = true)
        resolver.loudnessDb = -10.0
        player.playContext(listOf(T("yt1", 200_000)), 0, PlayContext("t", "T"))
        run()
        val src = engine.opened.last()
        assertEquals(EngineSource.Kind.Youtube, src.kind)
        assertEquals(4f, src.gainDb) // +10 dB capped at +4 (normal target)
        assertTrue(src.googleVideo)
        assertEquals("UA", src.requestHeaders["User-Agent"])
        assertEquals("v-yt1", player.currentMatch!!.videoId)
        // An expired URL: re-resolved with the stream cache bypassed, twice, then the track fails (a reload that
        // reaches Playing would reset the count, so the fake engine stays loading here).
        engine.statePlaying = false
        engine.pos = 50_000
        engine.fail(EngineErrorKind.Network)
        run()
        assertEquals(1, resolver.bypassCalls)
        assertEquals(50_000L, engine.lastStartMs)
        engine.fail(EngineErrorKind.Network)
        run()
        assertEquals(2, resolver.bypassCalls)
        engine.fail(EngineErrorKind.Network)
        run()
        assertEquals(Status.Error, player.statusNow)
        assertEquals(PlaybackError.PlaybackFailed, player.lastError.value)

        // No match: an error notice with the failure kind, then the next track.
        resolver.failing.add("yt2")
        player.playContext(listOf(T("yt2"), T("yt3")), 0, PlayContext("t", "T"))
        run()
        assertEquals(PlaybackError.NoMatch, player.lastError.value)
        advance(1600)
        assertEquals("yt3", player.currentTrack!!.id)
    }

    @Test
    fun webmFallsBackToAac() {
        resolver.mime = "audio/webm"
        player.playContext(listOf(T("yt1", 200_000)), 0, PlayContext("t", "T"))
        run()
        assertTrue(resolver.lastAllowWebm)
        engine.fail(EngineErrorKind.UnsupportedFormat)
        run()
        assertFalse(resolver.lastAllowWebm) // AAC for the rest of the session
        assertEquals(Status.Playing, player.statusNow)
    }

    @Test
    fun useMatchPinsAndContinues() {
        player.playContext(listOf(T("yt1", 200_000)), 0, PlayContext("t", "T"))
        run()
        engine.pos = 30_000
        player.useMatch(Match("other", "Other"))
        run()
        assertEquals("other", resolver.pinned["yt1"]!!.videoId)
        assertEquals(30_000L, engine.lastStartMs)
    }

    @Test
    fun sessionWindowAroundCurrent() {
        // Session: a window of the play order around a current track past the 500th.
        val big = (0 until 700).map { T("q$it") }
        player.playContext(big, 650, PlayContext("big", "Big"))
        run()
        player.pause()
        run()
        player.saveSession()
        val restored = newPlayer()
        restored.restoreSession()
        assertEquals("q650", restored.currentTrack!!.id)
        assertEquals(500, restored.items.size)
        assertEquals(450, restored.currentOrderIndex) // slots 200..699
        assertEquals(Status.Paused, restored.statusNow)
        assertEquals("big", restored.playContext.uri)
    }

    @Test
    fun recommendationsInTheQueue() {
        // What smart shuffle does: plan, then insert from the last point.
        val own = (0 until 12).map { T("own$it") }
        player.playContext(own, 0, PlayContext("playlist:local:test", "Test"))
        run()
        val flags = player.order.map { player.items[it].recommended }
        val points = com.app.shadetube.playback.features.QueueMix.insertionPoints(flags, player.currentOrderIndex, 17)
        assertTrue(points.size >= 2)
        for (k in points.indices.reversed()) assertEquals(1, player.insertAt(points[k], listOf(T("rec$k", rec = true))))
        assertEquals(own.size + points.size, player.order.size)
        // Session round trip keeps the flag.
        player.pause()
        run()
        player.saveSession()
        val restored = newPlayer()
        restored.restoreSession()
        assertEquals(points.size, restored.items.count { it.recommended })
        // One becomes the user's own.
        player.clearRecommended("rec0")
        assertFalse(player.items.first { it.id == "rec0" }.recommended)
        // Dropping: only the (still flagged) upcoming recommendations go, for good.
        assertEquals(points.size - 1, player.dropRecommendations())
        assertEquals("own0", player.currentTrack!!.id)
        assertEquals(own.size + 1, player.items.size) // compacted: rec0 (now own) stays
        player.setShuffle(true) // a reshuffle brings nothing back
        assertEquals(own.size + 1, player.order.size)
        assertEquals(0, player.dropRecommendations())
        player.setShuffle(false)
    }

    @Test
    fun liveReconnectPolicy() {
        val live = FakeLive()
        player.liveStreams = live
        player.playContext(listOf(T("station:1")), 0, PlayContext("radio", "R"))
        run()
        assertTrue(player.isLive)
        assertEquals(1, live.attempts.size)
        assertEquals(0L, player.durationMs())
        // Nothing ever played: fails for good after 3 consecutive failures, reconnecting with the next attempt.
        engine.statePlaying = false
        engine.fail(EngineErrorKind.Network)
        run()
        advance(600)
        assertEquals(listOf(0, 1), live.attempts)
        engine.fail(EngineErrorKind.Network)
        run()
        advance(1100)
        assertEquals(listOf(0, 1, 2), live.attempts)
        engine.fail(EngineErrorKind.Network)
        run()
        assertEquals(PlaybackError.StationUnreachable, player.lastError.value)
        assertEquals(Status.Error, player.statusNow)
    }

    @Test
    fun liveFormatFailsAtOnceAndTitles() {
        val live = FakeLive()
        player.liveStreams = live
        player.playContext(listOf(T("station:1")), 0, PlayContext("radio", "R"))
        run()
        engine.title("ICY", "Artist - Song")
        assertEquals("Artist - Song", player.liveTitle.value)
        engine.fail(EngineErrorKind.UnsupportedFormat)
        run()
        assertEquals(PlaybackError.StationUnsupported, player.lastError.value)
    }

    @Test
    fun endlessPlaybackAppendsTheRadio() {
        val fetches = ArrayList<String>()
        val src = object : com.app.shadetube.playback.features.RadioSource {
            override val available = true
            override suspend fun radioPlaylist(seedUri: String): String {
                fetches.add(seedUri)
                return "spotify:playlist:radio-${seedUri.substringAfterLast(':')}"
            }

            override suspend fun playlistTracks(uri: String, offset: Int, limit: Int): List<Track> =
                listOf(T("spotify:track:A"), T("spotify:track:R1"), T("spotify:track:BLK"), T("spotify:track:R2"))
        }
        blocked.add("spotify:track:BLK")
        val radio = com.app.shadetube.playback.features.Radio(player, src, scope, { it.id in blocked }, endlessEnabled = { true })
        val notices = ArrayList<PlayerNotice>()
        scope.launch { player.notices.collect { notices.add(it) } }
        player.onQueueLow = { radio.endlessCheck(fromPlayer = true) }
        player.playContext(listOf(T("spotify:track:A"), T("spotify:track:B")), 0, PlayContext("album:x", "X"))
        run()
        run()
        // One fetch for the current track's radio; only new, unblocked tracks are appended.
        assertEquals(listOf("spotify:track:A"), fetches)
        assertEquals(listOf("spotify:track:A", "spotify:track:B", "spotify:track:R1", "spotify:track:R2"), player.items.map { it.id })
        assertTrue(notices.contains(PlayerNotice.EndlessAppended(2)))
        // Enough left now: no new fetch; each seed once per queue.
        radio.endlessCheck()
        run()
        assertEquals(1, fetches.size)
        // Repeat on: never.
        player.setRepeat(RepeatMode.All)
        player.jumpTo(3)
        run()
        assertEquals(1, fetches.size)
    }

    @Test
    fun localMimeTypes() {
        assertEquals("audio/mpeg", Player.localMimeType("/Music/a.mp3"))
        assertEquals("audio/mpeg", Player.localMimeType("C:\\Music\\a.MP3"))
        assertEquals("audio/mp4", Player.localMimeType("a.m4a"))
        assertEquals("audio/mp4", Player.localMimeType("a.mp4"))
        assertEquals("audio/aac", Player.localMimeType("a.aac"))
        assertEquals("audio/flac", Player.localMimeType("a.flac"))
        assertEquals("audio/wav", Player.localMimeType("a.Wav"))
        assertEquals("audio/x-ms-wma", Player.localMimeType("a.wma"))
        assertEquals("audio/ogg", Player.localMimeType("a.ogg"))
        assertEquals("audio/ogg", Player.localMimeType("a.opus"))
        assertEquals("audio/webm", Player.localMimeType("a.webm"))
        assertEquals("audio/x-matroska", Player.localMimeType("a.mka"))
        assertEquals("", Player.localMimeType("a.xyz"))
        assertEquals("", Player.localMimeType("C:\\Mu.sic\\noext"))
        assertEquals("", Player.localMimeType(""))
    }

    // ---- fakes -----------------------------------------------------------------------------------------------------

    private class FakeEngine(val scope: CoroutineScope) : Engine {
        override var listener: EngineListener? = null
        var curTag = 0L
        var pos = 0L
        var dur = 0L
        var curSpeed = 1f
        var lastStartMs = -1L
        var statePlaying = true
        val opened = ArrayList<EngineSource>()
        var preloaded: EngineSource? = null
        val failing = HashSet<String>()

        override fun open(source: EngineSource, startMs: Long, autoplay: Boolean) {
            curTag = source.tag
            pos = startMs
            lastStartMs = startMs
            dur = source.durationMsHint
            curSpeed = source.speed
            opened.add(source)
            preloaded = null
            val tag = source.tag
            val fail = source.track.id in failing
            scope.launch {
                listener?.onEngineState(EngineState.Loading, tag)
                if (fail) listener?.onEngineError(EngineErrorKind.FileNotFound, "missing", tag)
                else if (statePlaying || !autoplay) listener?.onEngineState(if (autoplay) EngineState.Playing else EngineState.Paused, tag)
            }
        }

        override fun preload(source: EngineSource) {
            preloaded = source
        }

        override fun clearPreload() {
            preloaded = null
        }

        override fun play() {
            val t = curTag
            scope.launch { listener?.onEngineState(EngineState.Playing, t) }
        }

        override fun pause() {
            val t = curTag
            scope.launch { listener?.onEngineState(EngineState.Paused, t) }
        }

        override fun stop() {
            curTag = 0
            preloaded = null
        }

        override fun seek(ms: Long) {
            pos = ms
        }

        override fun setSpeed(speed: Float) {
            curSpeed = speed
        }

        override fun setVolume(volume: Float) = Unit
        override fun setEqualizer(eq: com.app.shadetube.playback.dsp.EqSettings) = Unit
        override val currentTag: Long get() = curTag
        override fun positionMs() = pos
        override fun durationMs() = dur
        override fun bufferedFraction() = 0f

        /** The current item played to its end (gapless handoff when something was preloaded). */
        fun finish() {
            val f = curTag
            val n = preloaded
            if (n != null) {
                curTag = n.tag
                pos = 0
                dur = n.durationMsHint
                preloaded = null
                listener?.onEngineEnded(f, n.tag)
            } else {
                listener?.onEngineState(EngineState.Ended, f)
                listener?.onEngineEnded(f, 0)
            }
        }

        fun fail(kind: EngineErrorKind) {
            listener?.onEngineError(kind, "boom", curTag)
        }

        fun title(t: String, title: String) {
            listener?.onEngineTitle(title, null, curTag)
        }
    }

    private class FakeResolver : TrackStreamResolver {
        var loudnessDb: Double? = null
        var mime = "audio/mp4"
        var bypassCalls = 0
        var lastAllowWebm = false
        val failing = HashSet<String>()
        val pinned = HashMap<String, Match>()

        private fun stream(videoId: String) = ResolvedStream(
            videoId = videoId, url = "https://rr1.googlevideo.com/videoplayback?id=$videoId&clen=1000", contentLength = 1000,
            mimeType = mime, codec = if (mime.contains("webm")) "opus" else "mp4a.40.2", bitrateKbps = 128, itag = 140,
            loudnessDb = loudnessDb, expiresAtMs = Long.MAX_VALUE, source = "youtube", fetchedAtMs = 0,
            requestHeaders = mapOf("User-Agent" to "UA"),
        )

        override suspend fun resolve(track: Track, allowWebm: Boolean, lowQuality: Boolean): Result<Resolved> {
            lastAllowWebm = allowWebm
            if (track.id in failing) return Result.failure(IOException("no match"))
            val m = pinned[track.id] ?: Match("v-${track.id}", track.name)
            return Result.success(Resolved(m, stream(m.videoId)))
        }

        override suspend fun stream(videoId: String, allowWebm: Boolean, lowQuality: Boolean, bypassCache: Boolean): Result<ResolvedStream> {
            lastAllowWebm = allowWebm
            if (bypassCache) bypassCalls++
            return Result.success(stream(videoId))
        }

        override suspend fun cachedMatch(trackId: String): Match? = pinned[trackId] ?: Match("v-$trackId", trackId)
        override suspend fun pin(trackId: String, match: Match) {
            pinned[trackId] = match
        }

        override fun failureOf(t: Throwable) = MatchFailure.NoMatch
    }

    private class FakeLive : LiveStreamProvider {
        val attempts = ArrayList<Int>()
        override fun isLive(track: Track) = track.id.startsWith("station:")
        override suspend fun resolve(track: Track, attempt: Int): LiveStream {
            attempts.add(attempt)
            return LiveStream("https://radio.example/stream", false, "audio/mpeg")
        }

        override fun titleFromIcy(raw: ByteArray): String? = null
    }
}
