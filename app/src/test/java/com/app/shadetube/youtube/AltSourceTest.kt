package com.app.shadetube.youtube

import com.app.shadetube.youtube.explode.TrackMatcher
import com.app.shadetube.youtube.explode.TrackQuery
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Port of the offline part of tests/altsource/altsource_test.cpp, plus transport checks against a loopback mock. */
class AltSourceTest {
    private fun fixture(name: String): String {
        for (base in listOf("src/test/java/com/app/shadetube/youtube/fixtures", "app/src/test/java/com/app/shadetube/youtube/fixtures")) {
            val f = File(base, name)
            if (f.exists()) return f.readText()
        }
        fail("fixture $name missing")
        return ""
    }

    private fun throwsAlt(mustContain: String? = null, block: () -> Unit): Boolean = try {
        block()
        false
    } catch (e: AltSourceException) {
        mustContain == null || (e.message ?: "").contains(mustContain)
    }

    @Test
    fun kindsAddressesSniffing() {
        assertEquals(AltKind.Piped, AltKind.parse("piped"))
        assertEquals(AltKind.Invidious, AltKind.parse("invidious"))
        assertEquals(AltKind.Off, AltKind.parse("off"))
        assertEquals(AltKind.Off, AltKind.parse(""))
        assertEquals(AltKind.Off, AltKind.parse("x"))
        assertEquals("invidious", AltKind.Invidious.settingsName)
        assertEquals("https://pipedapi.example.org", AltParse.normalizeInstance("pipedapi.example.org/"))
        assertEquals("https://Inv.Example.org", AltParse.normalizeInstance("  HTTPS://Inv.Example.org//  "))
        assertEquals("http://127.0.0.1:8765", AltParse.normalizeInstance("http://127.0.0.1:8765"))
        assertEquals("https://example.org/piped-api", AltParse.normalizeInstance("https://example.org/piped-api"))
        assertNull(AltParse.normalizeInstance(""))
        assertNull(AltParse.normalizeInstance("ftp://example.org"))
        assertNull(AltParse.normalizeInstance("https://exa mple.org"))
        assertNull(AltParse.normalizeInstance("https://example.org/?x=1"))
        assertNull(AltParse.normalizeInstance("https:///path"))
        assertEquals("pipedapi.example.org", AltParse.hostOf("https://pipedapi.example.org/streams/x"))
        assertEquals(AltContainer.Mp4, AltParse.sniffContainer(byteArrayOf(0, 0, 0, 0x18) + "ftypdash".toByteArray()))
        assertEquals(AltContainer.Webm, AltParse.sniffContainer(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x86.toByte(), 0x81.toByte())))
        assertEquals(AltContainer.Unknown, AltParse.sniffContainer("<!DOCTYPE html>".toByteArray()))
        assertEquals(AltContainer.Unknown, AltParse.sniffContainer(ByteArray(0)))
        assertEquals(AltContainer.Webm, AltParse.containerOf("audio/webm; codecs=\"opus\""))
        assertEquals(AltContainer.Mp4, AltParse.containerOf("audio/mp4"))
        assertEquals(AltContainer.Unknown, AltParse.containerOf("audio/3gpp"))
    }

    @Test
    fun instanceOrder() {
        val src = AltSource()
        assertTrue(!src.enabled && src.instanceOrder().isEmpty())
        src.configure(AltKind.Piped, "my.piped.example/")
        val order = src.instanceOrder()
        assertTrue(src.enabled && order.isNotEmpty() && order[0] == "https://my.piped.example")
        assertEquals(1 + AltSource.builtinInstances(AltKind.Piped).size, order.size)
        src.configure(AltKind.Invidious, "")
        assertEquals(AltSource.builtinInstances(AltKind.Invidious), src.instanceOrder())
        src.configure(AltKind.Piped, "not a url") // ignored (logged): built-in list
        assertEquals(AltSource.builtinInstances(AltKind.Piped), src.instanceOrder())
    }

    @Test
    fun replacedOwnInstanceIsNeverContactedAgain() {
        val builtins = AltSource.builtinInstances(AltKind.Piped)
        val own = AltSource()
        own.configure(AltKind.Piped, "https://a.example")
        own.noteResult(AltSource.Op.Stream, own.snapshot(AltSource.Op.Stream), "https://a.example", AltSource.Outcome.Ok)
        assertEquals("https://a.example", own.instanceOrder()[0])
        own.configure(AltKind.Piped, "")
        assertEquals(builtins, own.instanceOrder())
        own.configure(AltKind.Piped, "https://a.example")
        val before = own.snapshot(AltSource.Op.Stream) // a walk in flight...
        own.configure(AltKind.Piped, "https://b.example")
        own.noteResult(AltSource.Op.Stream, before, "https://a.example", AltSource.Outcome.Ok) // ...reporting after the change: ignored
        val ownOrder = own.instanceOrder()
        assertTrue(ownOrder[0] == "https://b.example" && "https://a.example" !in ownOrder)
        own.noteResult(AltSource.Op.Stream, own.snapshot(AltSource.Op.Stream), builtins.last(), AltSource.Outcome.Ok) // a built-in stays valid
        assertTrue(own.instanceOrder().size > 1 && own.instanceOrder()[1] == builtins.last())

        // configuration generation
        val g0 = own.generation()
        own.configure(AltKind.Piped, "https://b.example")
        assertEquals(g0, own.generation()) // unchanged settings
        own.configure(AltKind.Invidious, "https://b.example")
        assertEquals(g0 + 1, own.generation())
        own.configure(AltKind.Off, "")
        assertTrue(own.generation() == g0 + 2 && !own.enabled)
    }

    @Test
    fun failedInstancesAreDemotedAndUnreachableOnesSkipped() {
        val src = AltSource()
        val b = AltSource.builtinInstances(AltKind.Invidious)
        src.configure(AltKind.Invidious, "")
        src.noteResult(AltSource.Op.Stream, src.snapshot(AltSource.Op.Stream), b[0], AltSource.Outcome.Failed)
        assertEquals(listOf(b[1], b[2], b[0]), src.instanceOrder()) // demoted: tried last
        src.noteResult(AltSource.Op.Stream, src.snapshot(AltSource.Op.Stream), b[1], AltSource.Outcome.Down)
        assertEquals(listOf(b[2], b[0]), src.instanceOrder()) // unreachable: skipped for a minute
        // Stream and search health are separate.
        assertEquals(b, src.snapshot(AltSource.Op.Search).order)
    }

    @Test
    fun streamUrlsThePlayerMayFetch() {
        assertTrue(AltParse.acceptableStreamUrl("https://pipedapi.example.org/videoplayback?x", "https://pipedapi.example.org"))
        assertTrue(AltParse.acceptableStreamUrl("https://proxy.example.org/videoplayback?x", "https://api.example.org", "https://proxy.example.org"))
        assertFalse(AltParse.acceptableStreamUrl("https://proxy.example.org/videoplayback", "https://api.example.org")) // not declared
        assertFalse(AltParse.acceptableStreamUrl("http://pipedapi.example.org/videoplayback", "https://pipedapi.example.org")) // downgrade
        assertFalse(AltParse.acceptableStreamUrl("http://192.168.1.1/apply.cgi", "https://api.example.org", "http://192.168.1.1"))
        assertFalse(AltParse.acceptableStreamUrl("https://192.168.1.1/x", "https://api.example.org", "https://192.168.1.1")) // LAN proxy
        assertFalse(AltParse.acceptableStreamUrl("https://127.0.0.1/x", "https://api.example.org"))
        assertFalse(AltParse.acceptableStreamUrl("https://rr3---sn-x.googlevideo.com/videoplayback", "https://inv.example.org"))
        assertFalse(AltParse.acceptableStreamUrl("https://api.example.org@evil.example/x", "https://api.example.org"))
        assertFalse(AltParse.acceptableStreamUrl("ftp://api.example.org/x", "https://api.example.org"))
        assertTrue(AltParse.acceptableStreamUrl("http://127.0.0.1:8765/pmedia/1", "http://127.0.0.1:8765")) // the user's LAN instance
        assertTrue(AltParse.acceptableStreamUrl("http://127.0.0.1:8766/p", "http://127.0.0.1:8765", "http://127.0.0.1:8766"))
        assertFalse(AltParse.acceptableStreamUrl("http://127.0.0.1:8766/p", "http://127.0.0.1:8765")) // another port, not declared
    }

    @Test
    fun pipedParsing() {
        val v = AltParse.parsePipedStreams(fixture("piped_streams.json"), "https://pipedapi.example.org")
        assertTrue(v.title == "Luis Fonsi - Despacito ft. Daddy Yankee" && v.uploader == "Luis Fonsi" && v.durationSec == 282)
        assertEquals(8, v.streams.size) // 7 audio (2 dubbed) + muxed itag 18; video-only dropped
        assertEquals("https://pipedproxy.example.org", v.proxyUrl)
        assertTrue(v.streams.all { AltParse.acceptableStreamUrl(it.url, "https://pipedapi.example.org", v.proxyUrl) })
        val opus = AltParse.pickStream(v.streams, allowWebm = true, lowQuality = false)!!
        assertTrue(opus.itag == 251 && !opus.dubbed && opus.mimeType == "audio/webm" && opus.codec == "opus")
        assertTrue(opus.contentLength == 4661289L && opus.url.contains("pipedproxy.example.org"))
        val aac = AltParse.pickStream(v.streams, allowWebm = false, lowQuality = false)!!
        assertTrue(aac.itag == 140 && !aac.dubbed && aac.mimeType == "audio/mp4" && aac.codec == "mp4a.40.2")
        val low = AltParse.pickStream(v.streams, allowWebm = false, lowQuality = true)!!
        assertTrue(low.itag == 140 && low.bitrate == 130516)
        assertEquals(2, v.streams.count { it.dubbed })

        val mux = AltParse.pickStream(v.streams.filter { it.muxed }, allowWebm = true, lowQuality = false)!!
        assertTrue(mux.itag == 18 && mux.mimeType == "audio/mp4")

        // Real response: no audioStreams (YouTube blocked the instance's audio formats); videoStreams = LBRY mp4, LBRY
        // HLS and a proxied itag 18 with contentLength -1: only the itag 18 is taken, the length comes from the probe.
        val rick = AltParse.parsePipedStreams(fixture("piped_streams_muxed_only.json"), "https://api.piped.private.coffee")
        assertTrue(rick.title.contains("Never Gonna Give You Up") && rick.durationSec == 213)
        assertTrue(rick.streams.size == 1 && rick.streams[0].muxed && rick.streams[0].itag == 18 && rick.streams[0].contentLength == 0L)
        assertTrue(rick.streams[0].url.startsWith("https://proxy.piped.private.coffee/videoplayback?"))
        assertTrue(AltParse.acceptableStreamUrl(rick.streams[0].url, "https://api.piped.private.coffee", rick.proxyUrl))
        val muxedPick = AltParse.pickStream(rick.streams, allowWebm = true, lowQuality = false)!!
        assertTrue(muxedPick.itag == 18 && muxedPick.mimeType == "audio/mp4")
        assertTrue(throwsAlt("SignInConfirmNotBot") { AltParse.parsePipedStreams(fixture("piped_streams_error.json"), "x") })
        assertTrue(throwsAlt("not a JSON") { AltParse.parsePipedStreams("<!DOCTYPE html><html>", "x") })

        val songs = AltParse.parsePipedSearch(fixture("piped_search_music_songs.json"))
        assertEquals(6, songs.size)
        assertTrue(
            songs[0].videoId == "FXovf5dsRTw" && songs[0].title == "Despacito" && songs[0].channel == "Luis Fonsi" &&
                songs[0].durationSec == 229 && songs[0].channelId == "UCk91oFc2hY2CdHirU93baLg",
        )
        val videos = AltParse.parsePipedSearch(fixture("piped_search_videos.json"))
        assertTrue(videos.isNotEmpty() && videos[0].videoId == "kJQP7kiw5Fk" && videos[0].durationSec == 282)
        assertTrue(throwsAlt("boom") { AltParse.parsePipedSearch("""{"error":"boom"}""") })
    }

    @Test
    fun invidiousParsing() {
        val v = AltParse.parseInvidiousVideo(fixture("invidious_video.json"), "https://inv.example.org")
        assertTrue(v.title == "Luis Fonsi - Despacito ft. Daddy Yankee" && v.uploader == "Luis Fonsi" && v.durationSec == 282)
        assertEquals(8, v.streams.size) // 7 audio (2 dubbed by xtags) + formatStreams itag 18
        assertTrue(v.streams[0].url.startsWith("https://inv.example.org/companion/videoplayback?"))
        assertTrue(v.streams.all { AltParse.acceptableStreamUrl(it.url, "https://inv.example.org") })
        val opus = AltParse.pickStream(v.streams, allowWebm = true, lowQuality = false)!!
        assertTrue(opus.itag == 251 && opus.bitrate == 141592 && !opus.dubbed && opus.codec == "opus" && opus.contentLength == 4661289L)
        val aac = AltParse.pickStream(v.streams, allowWebm = false, lowQuality = false)!!
        assertTrue(aac.itag == 140 && aac.bitrate == 130516 && aac.codec == "mp4a.40.2")
        assertEquals(2, v.streams.count { it.dubbed })
        assertEquals(1, v.streams.count { it.muxed && it.itag == 18 })
        assertTrue(throwsAlt("companion") { AltParse.parseInvidiousVideo(fixture("invidious_video_error.json"), "x") })

        val items = AltParse.parseInvidiousSearch(fixture("invidious_search.json"))
        assertEquals(5, items.size)
        assertTrue(
            items[0].videoId == "kJQP7kiw5Fk" && items[0].channel == "Luis Fonsi" && items[0].durationSec == 282 &&
                items[0].channelId == "UCxoq-PAQeAdk_zyg8YS0JqA",
        )
        assertTrue(throwsAlt("Endpoint disabled") { AltParse.parseInvidiousSearch("""{"error":"Endpoint disabled"}""") })
    }

    /** The backup search feeds TrackMatcher's own scorer: the real Piped music results must rank the album track first. */
    @Test
    fun rankingBackupSearchResultsWithTrackMatcher() {
        val results = MatchService.altSearchResults(AltParse.parsePipedSearch(fixture("piped_search_music_songs.json")), 100)
        val q = TrackQuery("Despacito", listOf("Luis Fonsi", "Daddy Yankee"), 229360, "VIDA")
        val ranked = TrackMatcher(null).rank(q, results)
        assertTrue(ranked.isNotEmpty() && ranked[0].score >= 60)
        assertEquals("Despacito", ranked[0].video.title)
        assertTrue(ranked.size >= 2 && ranked.last().video.title != "Despacito") // the remix / other song rank lower
    }

    // ---- transport against a loopback mock instance ---------------------------------------------------------------

    private val server = MockWebServer()

    @After
    fun stopServer() {
        runCatching { server.shutdown() }
    }

    private val mp4Head = byteArrayOf(0, 0, 0, 0x18) + "ftypdash".toByteArray() + ByteArray(8)

    private fun pipedStreamsJson(base: String, path: String) =
        """{"title":"T","uploader":"U","duration":200,"proxyUrl":"$base","audioStreams":[
           {"url":"$path","mimeType":"audio/mp4","codec":"mp4a.40.2","bitrate":130000,"contentLength":-1,"itag":140,"videoOnly":false}],
           "videoStreams":[]}"""

    private fun startMock(handler: (RecordedRequest) -> MockResponse): String {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
        server.start()
        return server.url("/").toString().trimEnd('/')
    }

    @Test
    fun rangeProbeProvesTheStreamAndFindsItsLength() = runBlocking {
        var base = ""
        base = startMock { r ->
            val path = r.path ?: ""
            when {
                path.startsWith("/streams/") -> MockResponse().setBody(pipedStreamsJson(base, "$base/media/1"))
                path.startsWith("/media/1") && r.method == "HEAD" -> MockResponse().setHeader("Content-Length", "123456")
                path.startsWith("/media/1") -> {
                    assertEquals("bytes=0-15", r.getHeader("Range"))
                    // piped-proxy quirk: the Content-Range total is the chunk size, the real length only comes from HEAD
                    MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-15/16").setBody(Buffer().write(mp4Head))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val served = AltSource().streamFrom(AltKind.Piped, base, "kJQP7kiw5Fk", allowWebm = false, lowQuality = false)
        assertEquals(140, served.stream.itag)
        assertEquals(123456L, served.stream.contentLength)
        assertEquals("audio/mp4", served.stream.mimeType)
        assertEquals("T", served.video.title)
    }

    @Test
    fun streamIgnoringRangeIsRejected() = runBlocking {
        var base = ""
        base = startMock { r ->
            val path = r.path ?: ""
            when {
                path.startsWith("/streams/") -> MockResponse().setBody(pipedStreamsJson(base, "$base/media/1"))
                else -> MockResponse().setResponseCode(200).setBody(Buffer().write(mp4Head + ByteArray(1 shl 20)))
            }
        }
        val err = runCatching { AltSource().streamFrom(AltKind.Piped, base, "kJQP7kiw5Fk", false, false) }.exceptionOrNull()
        assertTrue(err.toString(), err is AltSourceException && err.message!!.contains("ignores range"))
    }

    @Test
    fun wrongContainerAndOutsideUrlsAreRejected() = runBlocking {
        var base = ""
        base = startMock { r ->
            val path = r.path ?: ""
            when {
                path.startsWith("/evil/streams/") -> MockResponse().setBody(pipedStreamsJson("", "https://evil.example/media/1"))
                path.startsWith("/streams/") -> MockResponse().setBody(pipedStreamsJson(base, "$base/media/1"))
                else -> MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-15/999").setBody("<!DOCTYPE html>.")
            }
        }
        val evil = runCatching { AltSource().streamFrom(AltKind.Piped, "$base/evil", "kJQP7kiw5Fk", false, false) }.exceptionOrNull()
        assertTrue(evil.toString(), evil is AltSourceException && evil.message!!.contains("outside the instance"))
        val html = runCatching { AltSource().streamFrom(AltKind.Piped, base, "kJQP7kiw5Fk", false, false) }.exceptionOrNull()
        assertTrue(html.toString(), html is AltSourceException && html.message!!.contains("not MP4"))
    }

    @Test
    fun oversizedAnswersAndErrorsAreCapped() = runBlocking {
        startMock { r ->
            val path = r.path ?: ""
            when {
                path.startsWith("/huge/") -> MockResponse().setBody(Buffer().write(ByteArray(6 shl 20) { ' '.code.toByte() }))
                else -> MockResponse().setResponseCode(500).setBody("""{"error":"boom\nstack"}""")
            }
        }
        val base = server.url("/").toString().trimEnd('/')
        val huge = runCatching { AltSource().searchOn(AltKind.Piped, "$base/huge", "x", false) }.exceptionOrNull()
        assertTrue(huge.toString(), huge is AltSourceException && huge.message!!.contains("too large"))
        val error = runCatching { AltSource().searchOn(AltKind.Piped, base, "x", false) }.exceptionOrNull()
        assertEquals("HTTP 500 (boom)", error?.message)
    }

    @Test
    fun walkTriesTheNextInstanceAndStopsOnVideoGone() = runBlocking {
        startMock { r ->
            val path = r.path ?: ""
            when {
                path.startsWith("/gone/") -> MockResponse().setResponseCode(500).setBody("""{"error":"This video is unavailable"}""")
                path.startsWith("/search") -> MockResponse().setBody("""{"items":[{"type":"stream","url":"/watch?v=kJQP7kiw5Fk","title":"Despacito","uploaderName":"Luis Fonsi","uploaderUrl":"/channel/UCx","duration":229}]}""")
                else -> MockResponse().setResponseCode(502)
            }
        }
        val base = server.url("/").toString().trimEnd('/')
        val src = AltSource()
        src.useBuiltins = false
        src.configure(AltKind.Piped, base)
        val items = src.search("Despacito", music = true)
        assertEquals("kJQP7kiw5Fk", items.single().videoId)
        assertTrue(server.takeRequest().path!!.contains("filter=music_songs"))

        src.configure(AltKind.Piped, "$base/gone")
        val gone = runCatching { src.stream("kJQP7kiw5Fk", false, false) }.exceptionOrNull()
        assertTrue(gone.toString(), gone is AltSourceException && gone.message!!.contains("video unavailable"))
        assertNotNull(src.instanceOrder().firstOrNull()) // not demoted: it says nothing about the instance

        val off = runCatching { AltSource().stream("kJQP7kiw5Fk", false, false) }.exceptionOrNull()
        assertEquals("backup source is off", off?.message)
    }
}
