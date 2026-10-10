package com.app.shadetube.youtube

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.dump
import com.app.shadetube.core.parseJsonOrNull
import com.app.shadetube.youtube.explode.AudioOnlyStreamInfo
import com.app.shadetube.youtube.explode.Author
import com.app.shadetube.youtube.explode.Bitrate
import com.app.shadetube.youtube.explode.Container
import com.app.shadetube.youtube.explode.FileSize
import com.app.shadetube.youtube.explode.MuxedStreamInfo
import com.app.shadetube.youtube.explode.Resolution
import com.app.shadetube.youtube.explode.Thumbnail
import com.app.shadetube.youtube.explode.TrackCandidate
import com.app.shadetube.youtube.explode.VideoId
import com.app.shadetube.youtube.explode.VideoQuality
import com.app.shadetube.youtube.explode.VideoSearchResult
import com.app.shadetube.youtube.explode.VideoUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchServiceTest {
    private fun audio(itag: Int, container: String, kbps: Long, default: Boolean? = null, drc: Boolean = false) = AudioOnlyStreamInfo(
        url = "https://rr1.googlevideo.com/videoplayback?itag=$itag", itag = itag, mimeType = "audio/$container",
        container = Container(container), size = FileSize(1000), bitrate = Bitrate(kbps * 1000),
        audioCodec = if (container == "webm") "opus" else "mp4a.40.2", isAudioLanguageDefault = default, isDrc = drc,
    )

    private val muxed18 = MuxedStreamInfo(
        url = "u18", itag = 18, mimeType = "video/mp4", container = Container("mp4"), size = FileSize(1000), bitrate = Bitrate(500_000),
        audioCodec = "mp4a.40.2", videoCodec = "avc1", videoQuality = VideoQuality(360, 30), videoResolution = Resolution(640, 360),
    )

    @Test
    fun highQualityPicksTheBestBitrateOfAnyContainer() {
        val streams = listOf(audio(140, "mp4", 130), audio(251, "webm", 160), audio(250, "webm", 70))
        assertEquals(251, MatchService.selectYoutubeStream(streams, emptyList(), allowWebm = true, lowQuality = false)?.itag)
        assertEquals(140, MatchService.selectYoutubeStream(streams, emptyList(), allowWebm = false, lowQuality = false)?.itag)
    }

    @Test
    fun normalQualityCapsAt140KbpsElseTheSmallestMp4() {
        val streams = listOf(audio(141, "mp4", 256), audio(140, "mp4", 130), audio(139, "mp4", 48))
        assertEquals(140, MatchService.selectYoutubeStream(streams, emptyList(), allowWebm = false, lowQuality = true)?.itag)
        val onlyHigh = listOf(audio(141, "mp4", 256), audio(145, "mp4", 200))
        assertEquals(145, MatchService.selectYoutubeStream(onlyHigh, emptyList(), allowWebm = false, lowQuality = true)?.itag)
    }

    @Test
    fun drcAndDubbedVariantsOnlyWhenNothingElse() {
        val streams = listOf(audio(251, "webm", 160, drc = true), audio(140, "mp4", 130), audio(141, "mp4", 256, default = false))
        assertEquals(140, MatchService.selectYoutubeStream(streams, emptyList(), allowWebm = true, lowQuality = false)?.itag)
        val onlyDrc = listOf(audio(251, "webm", 160, drc = true), audio(140, "mp4", 130, drc = true))
        assertEquals(251, MatchService.selectYoutubeStream(onlyDrc, emptyList(), allowWebm = true, lowQuality = false)?.itag)
    }

    @Test
    fun muxedMp4WhenThereIsNoAudioOnlyStream() {
        assertEquals(18, MatchService.selectYoutubeStream(emptyList(), listOf(muxed18), allowWebm = true, lowQuality = false)?.itag)
        // webm only and webm not allowed: muxed mp4 again
        assertEquals(18, MatchService.selectYoutubeStream(listOf(audio(251, "webm", 160)), listOf(muxed18), allowWebm = false, lowQuality = false)?.itag)
        assertNull(MatchService.selectYoutubeStream(emptyList(), emptyList(), allowWebm = true, lowQuality = false))
    }

    @Test
    fun urlExpiry() {
        val now = 1_700_000_000_000L
        assertEquals(1_800_000_000_000L - 600_000, MatchService.urlExpiry("https://x.googlevideo.com/videoplayback?expire=1800000000&ei=1", now))
        assertEquals(now + 5 * 3600_000L, MatchService.urlExpiry("https://example.org/stream", now))
        assertEquals(now + 5 * 3600_000L, MatchService.urlExpiry("https://example.org/?expire=x", now))
    }

    @Test
    fun streamKeys() {
        assertEquals("abc|wh", MatchService.streamKey("abc", allowWebm = true, lowQuality = false))
        assertEquals("abc|ml", MatchService.streamKey("abc", allowWebm = false, lowQuality = true))
    }

    @Test
    fun matchesJsonKeepsTheDesktopShape() {
        val m = Match("dQw4w9WgXcQ", "Never Gonna", "Rick Astley", 213, 87.5, false, "https://i.ytimg.com/x.jpg")
        val pinned = Match("yIVRs6YSbOM", manual = true, score = 100.0)
        val json = MatchService.matchesToJson(linkedMapOf("spotify:track:1" to m, "spotify:track:2" to pinned))
        val text = json.dump()
        assertEquals(
            """{"spotify:track:1":{"v":"dQw4w9WgXcQ","t":"Never Gonna","c":"Rick Astley","d":213,"s":87.5,"m":false,"th":"https://i.ytimg.com/x.jpg"},""" +
                """"spotify:track:2":{"v":"yIVRs6YSbOM","t":"","c":"","d":0,"s":100.0,"m":true,"th":""}}""",
            text,
        )
        val back = MatchService.matchesFromJson(parseJsonOrNull(text))
        assertEquals(m, back["spotify:track:1"])
        assertEquals(pinned, back["spotify:track:2"])
        // Entries without a video and non-objects are skipped.
        assertEquals(emptyMap<String, Match>(), MatchService.matchesFromJson(parseJsonOrNull("""{"a":{"v":""},"b":3}""")))
        assertEquals(emptyMap<String, Match>(), MatchService.matchesFromJson(parseJsonOrNull("[]")))
    }

    @Test
    fun queriesAndMatchesFromCatalogTracks() {
        val t = Track(
            id = "spotify:track:x", name = "Despacito", artists = listOf(ArtistRef("", "Luis Fonsi"), ArtistRef("", "Daddy Yankee")),
            album = AlbumRef(name = "VIDA"), durationMs = 229360,
        )
        val q = MatchService.toQuery(t)
        assertEquals(listOf("Luis Fonsi", "Daddy Yankee"), q.artists)
        assertEquals(229360L, q.duration)
        assertEquals("VIDA", q.album)
        assertNull(MatchService.toQuery(t.copy(durationMs = 0)).duration)

        val v = VideoSearchResult(
            VideoId.parse("kJQP7kiw5Fk"), "Despacito", Author("UCx", "Luis Fonsi"), 229_000,
            listOf(Thumbnail("big", Resolution(1280, 720))) + Thumbnail.defaultSet("kJQP7kiw5Fk"),
        )
        val m = MatchService.toMatch(TrackCandidate(v, 91.0))
        assertEquals(Match("kJQP7kiw5Fk", "Despacito", "Luis Fonsi", 229, 91.0, false, "https://img.youtube.com/vi/kJQP7kiw5Fk/hqdefault.jpg"), m)
    }

    @Test
    fun failuresAreClassified() {
        assertEquals(MatchFailure.NoMatch, matchFailureOf(NoMatchException("x")))
        assertEquals(MatchFailure.Unavailable, matchFailureOf(VideoUnavailableException("x")))
        assertEquals(MatchFailure.BackupSource, matchFailureOf(AltSourceException("x")))
        assertEquals(MatchFailure.Network, matchFailureOf(java.io.IOException("x")))
    }

    @Test
    fun clearCacheKeepsOnlyManualPicks() = kotlinx.coroutines.runBlocking {
        val file = java.io.File.createTempFile("matches", ".json").apply { deleteOnExit() }
        file.writeText(
            MatchService.matchesToJson(
                mapOf("auto" to Match("v1", score = 80.0), "pinned" to Match("v2", manual = true, score = 100.0)),
            ).dump(),
        )
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val service = MatchService(cacheFileProvider = { file }, scope = scope, observeSettings = false)
        assertEquals(1, service.clearCache())
        assertNull(service.cachedMatch("auto"))
        assertEquals("v2", service.cachedMatch("pinned")?.videoId)
        val onDisk = MatchService.matchesFromJson(parseJsonOrNull(file.readText()))
        assertEquals(setOf("pinned"), onDisk.keys)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }
}
