package com.app.shadetube.youtube.explode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of external/YoutubeExplode/tests/UtilsTests.cpp (captions are not ported: the app does not use them). */
class UtilsTest {
    @Test
    fun videoIdParsesIdsAndUrls() {
        for (input in listOf(
            "yIVRs6YSbOM", "https://www.youtube.com/watch?v=yIVRs6YSbOM",
            "youtube.com/watch?v=yIVRs6YSbOM&t=10s", "https://youtu.be/yIVRs6YSbOM",
            "https://youtu.be/yIVRs6YSbOM?t=3", "https://www.youtube.com/embed/yIVRs6YSbOM",
            "https://www.youtube.com/shorts/yIVRs6YSbOM", "https://www.youtube.com/live/yIVRs6YSbOM",
            "https://music.youtube.com/watch?v=yIVRs6YSbOM&list=RDAMVM",
        )) {
            assertEquals(input, "yIVRs6YSbOM", VideoId.tryParse(input)?.value)
        }
        assertNull(VideoId.tryParse(""))
        assertNull(VideoId.tryParse("yIVRs6YSbO"))
        assertNull(VideoId.tryParse("https://www.youtube.com/"))
        assertTrue(runCatching { VideoId.parse("not a video") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("dQw4w9WgXcQ", VideoId.parse("https://youtu.be/dQw4w9WgXcQ").value)
    }

    @Test
    fun otherIdsParse() {
        assertEquals(
            "PLOU2XLYxmsIJGErt5rrCqaSGTMyyqNt2H",
            PlaylistId.parse("https://www.youtube.com/playlist?list=PLOU2XLYxmsIJGErt5rrCqaSGTMyyqNt2H").value,
        )
        assertEquals(
            "PL9tY0BWXOZFuFEG_GtOBZ8-8wbkH-NVAr",
            PlaylistId.parse("https://www.youtube.com/watch?v=b8m9zhNAgKs&list=PL9tY0BWXOZFuFEG_GtOBZ8-8wbkH-NVAr").value,
        )
        assertEquals("UC3xnGqlcL3y-GXz5N3wiTJQ", ChannelId.parse("https://www.youtube.com/channel/UC3xnGqlcL3y-GXz5N3wiTJQ").value)
        assertNull(ChannelId.tryParse("UC"))
        assertEquals("Tyrrrz", ChannelHandle.parse("https://www.youtube.com/@Tyrrrz").value)
        assertEquals("Tyrrrz", ChannelHandle.parse("@Tyrrrz").value)
        assertEquals("Tyrrrz", ChannelSlug.parse("https://www.youtube.com/c/Tyrrrz").value)
        assertEquals("TheTyrrr", UserName.parse("https://www.youtube.com/user/TheTyrrr").value)
    }

    @Test
    fun urlQueryParameters() {
        val url = "https://rr1.googlevideo.com/videoplayback?itag=251&n=abc%3D&sig=x"
        assertEquals("abc=", Url.tryGetQueryParameter(url, "n"))
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=251&sig=x&n=a%2Fb", Url.setQueryParameter(url, "n", "a/b"))
        assertEquals("https://rr1.googlevideo.com/videoplayback?n=abc%3D&sig=x", Url.removeQueryParameter(url, "itag"))
        assertEquals("https://a.com/p?k=v", Url.setQueryParameter("https://a.com/p", "k", "v"))
        assertEquals("www.youtube.com", Url.getHost("https://www.YouTube.com:443/x?y"))
        assertEquals("/youtubei/v1/player", Url.getPath("https://www.youtube.com/youtubei/v1/player?key=1"))
        assertEquals("a b c%zz", Url.decode("a+b%20c%zz"))
        assertEquals("ç ş", Url.decode(Url.encode("ç ş")))
    }

    @Test
    fun sha1AndBase64() {
        assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", Crypto.sha1Hex("abc"))
        assertEquals("DA39A3EE5E6B4B0D3255BFEF95601890AFD80709", Crypto.sha1Hex(""))
        val bytes = Crypto.base64Decode("aGVsbG8-_w")
        assertNotNull(bytes)
        assertEquals(7, bytes!!.size)
        assertEquals("hello", String(bytes, 0, 5))
    }

    @Test
    fun timeParsingAndFormatting() {
        val t = Iso8601.tryParse("2009-10-24T23:57:33-07:00")
        assertNotNull(t)
        assertEquals("2009-10-25T06:57:33Z", Iso8601.format(t!!))
        assertEquals("2020-02-29T00:00:00Z", Iso8601.format(Iso8601.tryParse("2020-02-29")!!))
        assertNull(Iso8601.tryParse("yesterday"))
        assertEquals("1:02:03", Iso8601.formatDuration(3723000))
        assertEquals("1:05", Iso8601.formatDuration(65000))
        assertEquals(3723L, Str.tryParseClockDuration("1:02:03"))
        assertNull(Str.tryParseClockDuration("1:99"))
    }

    @Test
    fun streamPrimitives() {
        assertEquals("128 Kbit/s", Bitrate(131072).toString())
        assertEquals("3.42 MB", FileSize(3586000).toString())
        val q = VideoQuality.tryFromLabel("1080p60 HDR", 30)
        assertNotNull(q)
        assertEquals(1080, q!!.maxHeight)
        assertEquals(60, q.framerate)
        assertTrue(q.isHighDefinition)
        assertEquals("720p50", VideoQuality(720, 50).label)
        assertEquals(1080, VideoQuality.tryFromItag(137, 30)!!.maxHeight)
        assertTrue(Container("M4A").isAudioOnly)
        assertEquals(Container.WebM, Container("webm"))
    }

    @Test
    fun dashManifest() {
        val mpd = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet mimeType="audio/webm">
<Representation id="251" codecs="opus" audioSamplingRate="48000" bandwidth="160000">
<AudioChannelConfiguration schemeIdUri="x" value="2"/>
<BaseURL>https://rr1.googlevideo.com/videoplayback/id/1/itag/251/mime/audio%2Fwebm/clen/12345/</BaseURL></Representation>
<Representation id="rawcc" codecs="x"><BaseURL>https://example.com</BaseURL></Representation>
</AdaptationSet></Period></MPD>"""
        val streams = parseDashManifest(mpd)
        assertEquals(1, streams.size)
        assertEquals(251, streams[0].itag)
        assertEquals(12345L, streams[0].contentLength)
        assertEquals("webm", streams[0].container)
        assertEquals("opus", streams[0].audioCodec)
        assertEquals(2, streams[0].audioChannels)
    }

    @Test
    fun watchPageParsing() {
        val html = """<html><head><meta property="og:url" content="https://www.youtube.com/watch?v=abc">
<meta itemprop="uploadDate" content="2021-03-04T05:06:07-08:00"></head><body><div id="player"></div>
<script>var ytInitialPlayerResponse = {"videoDetails":{"title":"T \"q\" {x}","author":"A","channelId":"UC1234567890123456789012"}};var other={};</script>
<script>{"label":"1,234 likes"}</script></body></html>"""
        val page = VideoWatchPage.tryParse(html)
        assertNotNull(page)
        assertTrue(page!!.isAvailable)
        assertEquals("2021-03-04T13:06:07Z", Iso8601.format(page.uploadDate!!))
        assertEquals(1234L, page.likeCount)
        val pr = page.playerResponse
        assertNotNull(pr)
        assertEquals("T \"q\" {x}", pr!!.title)
    }

    @Test
    fun playerResponseLoudnessFallbackAndXtags() {
        // drc=1 in the protobuf xtags marks a DRC variant; playerConfig loudness fills formats without their own.
        val xtags = java.util.Base64.getEncoder().encodeToString(
            byteArrayOf(0x0A, 0x08, 0x0A, 0x03, 'd'.code.toByte(), 'r'.code.toByte(), 'c'.code.toByte(), 0x12, 0x01, '1'.code.toByte()),
        )
        val json = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"title":"x"},
"playerConfig":{"audioConfig":{"loudnessDb":-3.5}},
"streamingData":{"adaptiveFormats":[
 {"itag":140,"url":"u1","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000,"loudnessDb":1.25},
 {"itag":251,"url":"u2","mimeType":"audio/webm; codecs=\"opus\"","bitrate":160000,"xtags":"$xtags"}]}}"""
        val streams = PlayerResponse.parse(json).streams()
        assertEquals(1.25, streams[0].loudnessDb!!, 1e-9)
        assertFalse(streams[0].isDrc)
        assertEquals(-3.5, streams[1].loudnessDb!!, 1e-9)
        assertTrue(streams[1].isDrc)
        assertEquals("opus", streams[1].audioCodec)
        assertEquals("webm", streams[1].container)
    }
}
