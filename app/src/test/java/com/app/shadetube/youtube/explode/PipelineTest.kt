package com.app.shadetube.youtube.explode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Collections

/** End-to-end tests of the public API against a fake transport (port of tests/PipelineTests.cpp). */
class PipelineTest {
    private class FakeHttp : YtHttpClient {
        val routes = ArrayList<Pair<String, (YtHttpRequest) -> YtHttpResponse>>()
        val requests: MutableList<YtHttpRequest> = Collections.synchronizedList(ArrayList())

        fun on(fragment: String, handler: (YtHttpRequest) -> YtHttpResponse) {
            routes.add(fragment to handler)
        }

        override suspend fun send(request: YtHttpRequest): YtHttpResponse {
            requests.add(request)
            for ((fragment, handler) in routes) if (request.url.contains(fragment)) return handler(request)
            return YtHttpResponse(404, emptyList(), "not found".toByteArray())
        }
    }

    private fun ok(body: String) = YtHttpResponse(200, emptyList(), body.toByteArray())
    private fun ok(body: ByteArray) = YtHttpResponse(200, emptyList(), body)

    private val videoDetails =
        """"videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Test Song","lengthSeconds":"213","channelId":"UCuAXFkgsw1L7xaCfnd5JJOw","author":"Test Artist","shortDescription":"desc","viewCount":"1234","keywords":["a","b"],"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/x/hq.jpg","width":480,"height":360}]}},"microformat":{"playerMicroformatRenderer":{"uploadDate":"2009-10-24T23:57:33-07:00"}}"""

    private fun playerResponse(status: String, reason: String, streamingData: String): String {
        var json = """{"playabilityStatus":{"status":"$status""""
        if (reason.isNotEmpty()) json += ""","reason":"$reason""""
        json += "},"
        json += videoDetails
        if (streamingData.isNotEmpty()) json += ""","streamingData":$streamingData"""
        return "$json}"
    }

    private val plainStreams = """{"formats":[{"itag":18,"url":"https://rr1.googlevideo.com/videoplayback?itag=18&ratebypass=yes","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","bitrate":500000,"width":640,"height":360,"contentLength":"1000000","qualityLabel":"360p","fps":30,"audioSampleRate":"44100","audioChannels":2}],
"adaptiveFormats":[
 {"itag":137,"url":"https://rr1.googlevideo.com/videoplayback?itag=137","mimeType":"video/mp4; codecs=\"avc1.640028\"","bitrate":4000000,"width":1920,"height":1080,"contentLength":"9000000","qualityLabel":"1080p60","fps":60},
 {"itag":140,"url":"https://rr1.googlevideo.com/videoplayback?itag=140","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000,"contentLength":"3000000","audioSampleRate":"44100","audioChannels":2},
 {"itag":251,"url":"https://rr1.googlevideo.com/videoplayback?itag=251","mimeType":"audio/webm; codecs=\"opus\"","bitrate":160000,"contentLength":"3500000","audioSampleRate":"48000","audioChannels":2},
 {"itag":249,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":50000}
]}"""

    private fun cipheredStreams(scrambled: String): String {
        val url = Url.encode("https://rr2.googlevideo.com/videoplayback?itag=251&n=abc")
        return """{"adaptiveFormats":[{"itag":251,"signatureCipher":"s=${Url.encode(scrambled)}&sp=sig&url=$url","mimeType":"audio/webm; codecs=\"opus\"","bitrate":160000,"contentLength":"3500000","audioSampleRate":"48000","audioChannels":2}]}"""
    }

    private fun servePlayer(http: FakeHttp) {
        http.on("/iframe_api") {
            ok("""var scriptUrl = 'https:\/\/www.youtube.com\/s\/player\/abcdef12\/www-widgetapi.vflset\/www-widgetapi.js';""")
        }
        http.on("/s/player/abcdef12/player_ias.vflset/en_US/base.js") { ok(SyntheticPlayer.SOURCE) }
    }

    private fun clientNameOf(r: YtHttpRequest): String {
        val body = r.body?.toString(Charsets.UTF_8) ?: return ""
        var pos = body.indexOf("\"clientName\":\"")
        if (pos < 0) return ""
        pos += 14
        return body.substring(pos, body.indexOf('"', pos))
    }

    private fun bodyOf(r: YtHttpRequest) = r.body?.toString(Charsets.UTF_8) ?: ""

    @Test
    fun plainManifestFromPrimaryClient() = runBlocking {
        val http = FakeHttp()
        http.on("/sw.js_data") {
            ok(""")]}'[[null,null,[[[null,null,null,null,null,null,null,null,null,null,null,null,null,"VISITOR123"]]]]]""")
        }
        http.on("/youtubei/v1/player") { ok(playerResponse("OK", "", plainStreams)) }

        val youtube = YoutubeClient(http)
        val manifest = youtube.videos.streams.getManifest(VideoId.parse("https://youtu.be/dQw4w9WgXcQ"))

        assertEquals(4, manifest.streams.size) // itag 249 has no URL (SABR) and is skipped
        assertEquals(2, manifest.audioOnlyStreams.size)
        assertEquals(1, manifest.videoOnlyStreams.size)
        assertEquals(1, manifest.muxedStreams.size)
        assertEquals(3, manifest.audioStreams.size)

        val best = manifest.tryGetBestAudioOnlyStream()
        assertNotNull(best)
        assertEquals(251, best!!.itag)
        assertEquals("opus", best.audioCodec)
        assertEquals("webm", best.container.name)
        assertEquals(3500000L, best.size.bytes)
        assertEquals(48000, best.audioSampleRate)
        assertEquals(VideoController.VISIONOS_USER_AGENT, best.userAgent)
        assertEquals(140, manifest.tryGetBestAudioOnlyStream("mp4")!!.itag)

        val video = manifest.videoOnlyStreams.withHighestVideoQuality()!!
        assertEquals("1080p60", video.videoQuality.label)
        assertEquals("1920x1080", video.videoResolution.toString())

        // Innertube requests carry the API key, hl, visitor data and the VISIONOS client.
        var sawPlayer = false
        for (r in http.requests) {
            if (!r.url.contains("/youtubei/v1/player")) continue
            sawPlayer = true
            assertTrue(r.url.contains("key="))
            assertTrue(r.url.contains("hl=en"))
            assertEquals("VISIONOS", clientNameOf(r))
            assertTrue(bodyOf(r).contains("VISITOR123"))
            assertTrue((r.header("Cookie") ?: "").contains("SOCS="))
            assertEquals("VISITOR123", r.header("X-Goog-Visitor-Id"))
        }
        assertTrue(sawPlayer)
    }

    @Test
    fun ageRestrictedFallsBackToTvClientAndDeciphers() = runBlocking {
        val http = FakeHttp()
        servePlayer(http)
        http.on("/youtubei/v1/player") { r ->
            if (clientNameOf(r) == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
                assertTrue(bodyOf(r).contains("\"signatureTimestamp\":20668"))
                ok(playerResponse("OK", "", cipheredStreams(SyntheticPlayer.SIGNATURE)))
            } else {
                ok(playerResponse("LOGIN_REQUIRED", "Sign in to confirm your age", ""))
            }
        }
        val youtube = YoutubeClient(http)
        val audio = youtube.videos.streams.getManifest(VideoId.parse("dQw4w9WgXcQ")).audioOnlyStreams
        assertEquals(1, audio.size)
        val url = audio[0].url
        assertEquals(SyntheticPlayer.reference(SyntheticPlayer.SIGNATURE), Url.tryGetQueryParameter(url, "sig"))
        // Without a JS engine, n is left untouched.
        assertEquals("abc", Url.tryGetQueryParameter(url, "n"))
        assertEquals(VideoController.TV_USER_AGENT, audio[0].userAgent)
    }

    @Test
    fun errorsAreMapped() = runBlocking {
        val http = FakeHttp()
        servePlayer(http)
        var mode = ""
        http.on("/youtubei/v1/player") {
            when (mode) {
                "unavailable" -> ok("""{"playabilityStatus":{"status":"ERROR","reason":"Video unavailable"}}""")
                "age" -> ok(playerResponse("LOGIN_REQUIRED", "Sign in to confirm your age", ""))
                "purchase" -> ok(
                    """{"playabilityStatus":{"status":"UNPLAYABLE","errorScreen":{"playerLegacyDesktopYpcTrailerRenderer":{"trailerVideoId":"trailer1234"}}},$videoDetails}""",
                )
                else -> YtHttpResponse(429)
            }
        }
        val streams = YoutubeClient(http).videos.streams
        val id = VideoId.parse("dQw4w9WgXcQ")

        suspend fun failure(): Throwable? = try {
            streams.getManifest(id)
            null
        } catch (e: Exception) {
            e
        }

        mode = "unavailable"
        assertTrue(failure() is VideoUnavailableException)
        mode = "age"
        assertTrue(failure() is VideoAgeRestrictedException)
        mode = "purchase"
        val purchase = failure()
        assertTrue(purchase is VideoRequiresPurchaseException)
        assertEquals("trailer1234", (purchase as VideoRequiresPurchaseException).previewVideoId)
        mode = "ratelimit"
        assertTrue(failure() is RequestLimitExceededException)
    }

    @Test
    fun videoMetadata() = runBlocking {
        val http = FakeHttp()
        http.on("/watch?v=dQw4w9WgXcQ") {
            ok(
                """<html><head><meta property="og:url" content="https://www.youtube.com/watch?v=dQw4w9WgXcQ"></head><body><div id="player"></div><script>var ytInitialPlayerResponse = """ +
                    playerResponse("OK", "", "") + """;</script><script>x={"a":"17,000,000 likes"}</script></body></html>""",
            )
        }
        val video = YoutubeClient(http).getVideo(VideoId.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("Test Song", video.title)
        assertEquals("Test Artist", video.author.channelTitle)
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", video.author.channelId)
        assertEquals(213000L, video.duration)
        assertEquals("2009-10-25T06:57:33Z", Iso8601.format(video.uploadDate))
        assertEquals(1234L, video.engagement.viewCount)
        assertEquals(17000000L, video.engagement.likeCount)
        assertEquals(2, video.keywords.size)
        assertEquals(4, video.thumbnails.size) // 1 from the response + 3 defaults
    }

    @Test
    fun searchWithPagination() = runBlocking {
        val http = FakeHttp()
        http.on("/youtubei/v1/search") { r ->
            if (!bodyOf(r).contains("\"continuation\"")) {
                assertTrue(bodyOf(r).contains("EgIQAQ%3D%3D")) // video filter
                ok(
                    """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"videoRenderer":{"videoId":"dQw4w9WgXcQ","title":{"runs":[{"text":"Never Gonna "},{"text":"Give You Up"}]},
                  "longBylineText":{"runs":[{"text":"Rick Astley","navigationEndpoint":{"browseEndpoint":{"browseId":"UCuAXFkgsw1L7xaCfnd5JJOw"}}}]},
                  "lengthText":{"simpleText":"3:33"},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/a.jpg","width":360,"height":202}]}}},
                {"videoRenderer":{"videoId":"liveLIVE123","title":{"simpleText":"Live now"},
                  "shortBylineText":{"runs":[{"text":"Channel","navigationEndpoint":{"browseEndpoint":{"browseId":"UC1234567890123456789012"}}}]}}}
              ]}},
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"PAGE2"}}}}]}}}}}""",
                )
            } else {
                ok(
                    """{"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[
          {"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"yIVRs6YSbOM","title":{"simpleText":"Second page"},
            "longBylineText":{"runs":[{"text":"Other","navigationEndpoint":{"browseEndpoint":{"browseId":"UC1234567890123456789012"}}}]},
            "lengthText":{"simpleText":"1:02:03"}}},
            {"videoRenderer":{"videoId":"dQw4w9WgXcQ","title":{"simpleText":"Duplicate"},"longBylineText":{"runs":[{"text":"x","navigationEndpoint":{"browseEndpoint":{"browseId":"UC1234567890123456789012"}}}]}}}]}}]}}]}""",
                )
            }
        }
        val youtube = YoutubeClient(http)
        val results = youtube.search.getVideos("never gonna give you up", 10)
        assertEquals(3, results.size)
        assertEquals("dQw4w9WgXcQ", results[0].id.value)
        assertEquals("Never Gonna Give You Up", results[0].title)
        assertEquals("Rick Astley", results[0].author.channelTitle)
        assertEquals(213000L, results[0].duration)
        assertNull(results[1].duration)
        assertEquals("Second page", results[2].title)
        assertEquals(3723000L, results[2].duration)

        assertEquals(1, youtube.search.getVideos("never gonna give you up", 1).size)
    }

    @Test
    fun playlistVideos() = runBlocking {
        val http = FakeHttp()
        http.on("/youtubei/v1/next") { r ->
            val firstPage = bodyOf(r).contains("\"playlistIndex\":0")
            val two = """{"playlistPanelVideoRenderer":{"videoId":"yIVRs6YSbOM","title":{"simpleText":"Two"},"navigationEndpoint":{"watchEndpoint":{"index":1}},"shortBylineText":{"runs":[{"text":"B","navigationEndpoint":{"browseEndpoint":{"browseId":"UC1234567890123456789012"}}}]}}}"""
            val videos = if (firstPage)
                """{"playlistPanelVideoRenderer":{"videoId":"dQw4w9WgXcQ","title":{"simpleText":"One"},"lengthText":{"simpleText":"3:33"},"navigationEndpoint":{"watchEndpoint":{"index":0}},"shortBylineText":{"runs":[{"text":"A","navigationEndpoint":{"browseEndpoint":{"browseId":"UC1234567890123456789012"}}}]}}},$two"""
            else two
            ok("""{"responseContext":{"visitorData":"VD"},"contents":{"twoColumnWatchNextResults":{"playlist":{"playlist":{"title":"My list","contents":[$videos]}}}}}""")
        }
        val videos = YoutubeClient(http).playlists.getVideos(PlaylistId.parse("PLOU2XLYxmsIJGErt5rrCqaSGTMyyqNt2H"))
        assertEquals(2, videos.size)
        assertEquals("One", videos[0].title)
        assertEquals(213000L, videos[0].duration)
        assertEquals("https://www.youtube.com/watch?v=yIVRs6YSbOM&list=PLOU2XLYxmsIJGErt5rrCqaSGTMyyqNt2H", videos[1].url)
    }

    @Test
    fun playlistMetadataFallsBackToNext() = runBlocking {
        val http = FakeHttp()
        http.on("/youtubei/v1/browse") { ok("""{"alerts":[]}""") } // not a user playlist: no sidebar
        http.on("/youtubei/v1/next") {
            ok("""{"contents":{"twoColumnWatchNextResults":{"playlist":{"playlist":{"title":"Mix","ownerName":{"simpleText":"YouTube"},"totalVideosText":{"runs":[{"text":"1,234 videos"}]},"contents":[]}}}}}""")
        }
        val playlist = YoutubeClient(http).playlists.get(PlaylistId.parse("RDdQw4w9WgXcQ"))
        assertEquals("Mix", playlist.title)
        assertEquals(1234, playlist.count)
        assertNull(playlist.author) // system playlists have no author
    }

    @Test
    fun mediaStreamDownloadUsesRangeSegments() = runBlocking {
        val http = FakeHttp()
        val content = ByteArray(25_000_000) { i -> (i.toLong() * 31 % 251).toByte() }
        http.on("googlevideo.com/videoplayback") { r ->
            val range = Url.tryGetQueryParameter(r.url, "range") ?: ""
            val dash = range.indexOf('-')
            val from = range.substring(0, dash).toInt()
            val to = range.substring(dash + 1).toInt()
            ok(content.copyOfRange(from, to + 1))
        }
        val info = AudioOnlyStreamInfo(
            url = "https://rr1.googlevideo.com/videoplayback?itag=251", itag = 251, mimeType = "audio/webm",
            container = Container("webm"), size = FileSize(content.size.toLong()), bitrate = Bitrate(0), audioCodec = "opus",
        )
        val youtube = YoutubeClient(http)
        val out = ByteArrayOutputStream()
        var lastProgress = 0.0
        youtube.videos.streams.copyTo(info, out) { lastProgress = it }
        assertTrue(out.toByteArray().contentEquals(content))
        assertEquals(1.0, lastProgress, 0.0)
        assertEquals(3, http.requests.size) // 25 MB in ~9.9 MB segments

        val stream = youtube.videos.streams.get(info)
        stream.seek(20_000_000)
        val buffer = ByteArray(16)
        assertEquals(16, stream.read(buffer, 0, 16))
        assertTrue(buffer.contentEquals(content.copyOfRange(20_000_000, 20_000_016)))
    }

    @Test
    fun cancellationStopsTheManifest() {
        val http = FakeHttp()
        http.on("/") { throw kotlinx.coroutines.CancellationException("cancelled") }
        try {
            runBlocking { YoutubeClient(http).videos.streams.getManifest(VideoId.parse("dQw4w9WgXcQ")) }
            fail("expected cancellation")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // ok
        }
    }
}
