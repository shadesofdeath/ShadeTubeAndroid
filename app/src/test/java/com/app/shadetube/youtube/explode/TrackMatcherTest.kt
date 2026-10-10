package com.app.shadetube.youtube.explode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of external/YoutubeExplode/tests/MusicTests.cpp. */
class TrackMatcherTest {
    private fun video(id: String, title: String, channel: String, durationSec: Long?) =
        VideoSearchResult(VideoId.parse(id), title, Author("UC1234567890123456789012", channel), durationSec?.let { it * 1000 }, emptyList())

    private val matcher = TrackMatcher(null)

    @Test
    fun buildsQueriesFromCatalogTitles() {
        assertEquals("Queen - Bohemian Rhapsody", TrackMatcher.buildQuery(TrackQuery("Bohemian Rhapsody - Remastered 2011", listOf("Queen"), 355_000)))
        assertEquals("Mark Ronson - Uptown Funk", TrackMatcher.buildQuery(TrackQuery("Uptown Funk (feat. Bruno Mars)", listOf("Mark Ronson", "Bruno Mars"))))
        assertEquals("Song", TrackMatcher.buildQuery(TrackQuery("Song ft. Someone [Live]")))
    }

    @Test
    fun prefersOfficialAudioWithMatchingDuration() {
        val ranked = matcher.rank(
            TrackQuery("Around the World", listOf("Daft Punk"), 429_000),
            listOf(
                video("aaaaaaaaaa1", "Daft Punk - Around The World (Official Music Video)", "Daft Punk", 240),
                video("aaaaaaaaaa2", "Daft Punk - Around the World (Live at Coachella)", "Fan", 431),
                video("aaaaaaaaaa3", "Daft Punk - Around the World (Official Audio)", "Daft Punk", 430),
                video("aaaaaaaaaa4", "Around the World 1 Hour Loop", "Loops", 3600),
                video("aaaaaaaaaa5", "Around the World (sped up)", "Edits", 300),
                video("aaaaaaaaaa6", "Daft Punk - Around the World LIVE", "Stream", null),
            ),
        )
        assertEquals("aaaaaaaaaa3", ranked[0].video.id.value)
        assertTrue(ranked[0].score > 90)
        // Live, loop and sped-up variants all end up far below the correct match.
        for (i in 1 until ranked.size) assertTrue(ranked[i].toString(), ranked[i].score < ranked[0].score - 30)
    }

    @Test
    fun topicChannelAndUnicodeTitles() {
        val ranked = matcher.rank(
            TrackQuery("Şımarık", listOf("Tarkan"), 234_000),
            listOf(
                video("bbbbbbbbbb1", "Tarkan - Şımarık (Remix 2024)", "DJ", 236),
                video("bbbbbbbbbb2", "Şımarık", "Tarkan - Topic", 235),
                video("bbbbbbbbbb3", "Tarkan - Kuzu Kuzu", "Tarkan", 234),
            ),
        )
        assertEquals("bbbbbbbbbb2", ranked[0].video.id.value)
        assertEquals("bbbbbbbbbb3", ranked.last().video.id.value) // different song
    }

    @Test
    fun keepsVariantsTheCatalogAskedFor() {
        val ranked = matcher.rank(
            TrackQuery("Hotel California - Live on MTV, 1994", listOf("Eagles"), 427_000),
            listOf(
                video("cccccccccc1", "Eagles - Hotel California (Official Audio)", "Eagles", 391),
                video("cccccccccc2", "Eagles - Hotel California (Live 1994)", "Eagles", 428),
            ),
        )
        assertEquals("cccccccccc2", ranked[0].video.id.value) // "live" is not penalized when requested
    }

    @Test
    fun scoringReasonsAndDuplicates() {
        val v = video("dddddddddd1", "Artist - Song (Official Audio)", "Artist - Topic", 200)
        val ranked = matcher.rank(TrackQuery("Song", listOf("Artist"), 200_000), listOf(v, v))
        assertEquals(1, ranked.size) // duplicates are scored once
        // title 30 + artist 25 + duration 30 + Topic 12 + official audio 8 + rank #1 4
        assertEquals(109.0, ranked[0].score, 1e-9)
        assertTrue(ranked[0].reasons.contains("official Topic channel (+12)"))
    }
}
