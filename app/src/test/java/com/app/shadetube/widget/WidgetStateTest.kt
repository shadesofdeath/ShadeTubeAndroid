package com.app.shadetube.widget

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.playback.Status
import com.app.shadetube.playback.features.SmartShuffle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateTest {
    private val song = Track(
        id = "spotify:track:1",
        name = "Şımarık",
        artists = listOf(ArtistRef("a1", "Tarkan"), ArtistRef("a2", "Guest")),
        album = AlbumRef("al", "Karma", listOf(Image("small", 64, 64), Image("big", 640, 640), Image("mid", 300, 300))),
        durationMs = 200_000,
    )

    private fun of(
        track: Track? = song,
        status: Status = Status.Playing,
        live: Boolean = false,
        liveTitle: String = "",
        pos: Long = 50_000,
        dur: Long = 200_000,
        liked: Boolean = false,
        canLike: Boolean = true,
        shuffle: SmartShuffle.Mode = SmartShuffle.Mode.Off,
    ) = WidgetState.of(track, status, live, liveTitle, pos, dur, liked, canLike, shuffle)

    @Test
    fun nothingLoadedIsTheEmptyState() {
        val s = of(track = null, status = Status.Idle, shuffle = SmartShuffle.Mode.Smart)
        assertFalse(s.hasTrack)
        assertFalse(s.playing)
        assertNull(s.coverUrl)
        assertEquals(SmartShuffle.Mode.Smart, s.shuffle)
    }

    @Test
    fun songMapsTitleArtistCoverAndProgress() {
        val s = of(liked = true)
        assertTrue(s.hasTrack)
        assertEquals("Şımarık", s.title)
        assertEquals("Tarkan, Guest", s.artist)
        assertEquals("mid", s.coverUrl) // smallest image >= 300 px
        assertTrue(s.playing)
        assertTrue(s.liked && s.canLike)
        assertEquals(0.25f, s.progress, 1e-6f)
    }

    @Test
    fun playStates() {
        assertTrue(of(status = Status.Buffering).playing)
        assertTrue(of(status = Status.Resolving).playing)
        assertFalse(of(status = Status.Paused).playing)
        assertFalse(of(status = Status.Error).playing)
    }

    @Test
    fun stationShowsItsSongAndNoProgressOrLike() {
        val station = Track(id = "radio:x", name = "Radyo X")
        val s = of(track = station, live = true, liveTitle = "Artist - Song", liked = true, dur = 0)
        assertEquals("Artist - Song", s.title)
        assertEquals("Radyo X", s.artist)
        assertEquals(0f, s.progress)
        assertFalse(s.canLike)
        assertFalse(s.liked)
        val quiet = of(track = station, live = true, liveTitle = "", dur = 0)
        assertEquals("Radyo X", quiet.title)
    }

    @Test
    fun progressIsClampedAndUnknownDurationIsZero() {
        assertEquals(1f, of(pos = 300_000).progress)
        assertEquals(0f, of(dur = 0).progress)
    }

    @Test
    fun contentIgnoresProgress() {
        assertEquals(of(pos = 10_000).content, of(pos = 90_000).content)
        assertTrue(of(status = Status.Paused).content != of().content)
    }
}
