package com.app.shadetube.internetradio

import com.app.shadetube.internetradio.LiveParsers.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the ICY / playlist parts of tests/liveaudio/liveaudio_test.cpp, plus the "now on air" split. */
class LiveParsersTest {
    private fun bytes(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) when (p) {
            is String -> out.write(p.toByteArray(Charsets.UTF_8))
            is Int -> out.write(p)
        }
        return out.toByteArray()
    }

    @Test
    fun icyStreamTitle() {
        assertEquals("A - B", LiveParsers.icyStreamTitle("StreamTitle='A - B';"))
        assertEquals("Rock'n'Roll Band - It's Here", LiveParsers.icyStreamTitle("StreamTitle='Rock'n'Roll Band - It's Here';StreamUrl='http://x';"))
        assertEquals("", LiveParsers.icyStreamTitle("StreamTitle='';"))
        assertEquals("", LiveParsers.icyStreamTitle("StreamTitle='';json='{}';")) // Deutschlandfunk's Opus stream
        assertEquals("It';s Here", LiveParsers.icyStreamTitle("StreamTitle='It';s Here';"))
        assertEquals("No end", LiveParsers.icyStreamTitle("StreamTitle='No end"))
        assertNull(LiveParsers.icyStreamTitle("StreamUrl='x';"))
        assertEquals("Şarkı Türkçe", LiveParsers.icyStreamTitle("StreamTitle='Şarkı  \t Türkçe';"))
        val legacy = bytes("StreamTitle='", 0xDE, "ark", 0xFD, " ", 0xF0, "';")
        assertEquals("Þarký ð", LiveParsers.icyStreamTitle(legacy, turkish = false))
        assertEquals("Şarkı ğ", LiveParsers.icyStreamTitle(legacy, turkish = true))
        // The block is NUL-padded to a multiple of 16 bytes.
        assertEquals("X - Y", LiveParsers.icyStreamTitle(bytes("StreamTitle='X - Y';", 0, 0, 0, 0), false))
        assertEquals("case", LiveParsers.icyStreamTitle("streamtitle='case';"))
    }

    @Test
    fun legacyText() {
        assertEquals("", LiveParsers.decodeLegacyText(ByteArray(0)))
        assertEquals("ç", LiveParsers.decodeLegacyText(bytes(0xC3, 0xA7), turkish = false))
        assertEquals("€", LiveParsers.decodeLegacyText(bytes(0x80), turkish = false))
    }

    @Test
    fun nowOnAir() {
        assertEquals(LiveParsers.NowOnAir("Artist", "Song"), LiveParsers.splitStreamTitle("Artist - Song"))
        assertEquals(LiveParsers.NowOnAir("A-ha", "Take On Me"), LiveParsers.splitStreamTitle(" A-ha  -  Take On Me "))
        assertEquals(LiveParsers.NowOnAir("Sezen Aksu", "Gülümse – Live"), LiveParsers.splitStreamTitle("Sezen Aksu - Gülümse – Live"))
        assertEquals(LiveParsers.NowOnAir("Artist", "Song"), LiveParsers.splitStreamTitle("Artist – Song"))
        assertEquals(LiveParsers.NowOnAir("", "Morning News"), LiveParsers.splitStreamTitle("Morning News"))
        assertEquals(LiveParsers.NowOnAir("", "- Song"), LiveParsers.splitStreamTitle("- Song"))
        assertNull(LiveParsers.splitStreamTitle(""))
        assertNull(LiveParsers.splitStreamTitle("   "))
        assertNull(LiveParsers.splitStreamTitle(null))
        assertEquals("Artist - Song", LiveParsers.splitStreamTitle("Artist – Song")?.full)
    }

    @Test
    fun playlists() {
        assertEquals(PlaylistType.Pls, LiveParsers.playlistType("audio/x-scpls", "[playlist]\nFile1=http://a/b", "http://x/a"))
        assertEquals(PlaylistType.Hls, LiveParsers.playlistType("audio/mpeg", "#EXTM3U\n#EXT-X-TARGETDURATION:6", ""))
        assertEquals(PlaylistType.None, LiveParsers.playlistType("audio/x-mpegurl", bytes(0xFF, 0xFB, 0x90, 0x64), "http://x/a.m3u"))
        assertEquals(PlaylistType.M3u, LiveParsers.playlistType("text/plain", "http://a/stream\n", "http://x/list"))
        assertEquals(PlaylistType.Pls, LiveParsers.playlistType("application/octet-stream", "[Playlist]\r\nfile1=http://a/b", ""))
        assertEquals(PlaylistType.Asx, LiveParsers.playlistType("", "<ASX version=\"3.0\"><entry><ref href=\"http://a/s\"/></entry></ASX>", ""))
        assertEquals(PlaylistType.None, LiveParsers.playlistType("audio/mpeg", bytes("ID3", 3), "http://x/a.pls"))
        assertEquals(PlaylistType.M3u, LiveParsers.playlistType("audio/x-mpegurl; charset=utf-8", "#EXTM3U\n#EXTINF:-1,R\nhttp://a/1", ""))
        assertEquals(PlaylistType.Hls, LiveParsers.playlistType("", bytes(0xEF, 0xBB, 0xBF, "#EXTM3U\n#EXT-X-VERSION:3"), ""))
        assertEquals(PlaylistType.Pls, LiveParsers.playlistType("text/plain", "whatever", "http://x/a.pls?x=1"))

        val pls = LiveParsers.parsePlaylist(
            PlaylistType.Pls, "﻿[playlist]\r\nFile1=stream\r\nFile2=mms://old\r\nfile3=https://c/d;\r\n", "http://h:8000/dir/list.pls",
        )
        assertEquals(listOf("http://h:8000/dir/stream", "https://c/d;"), pls)
        val m3u = LiveParsers.parsePlaylist(PlaylistType.M3u, "#EXTM3U\n#EXTINF:-1,Radio\nhttp://a/1\n\n# comment\n/2\n", "https://h/x.m3u")
        assertEquals(listOf("http://a/1", "https://h/2"), m3u)
        val asx = LiveParsers.parsePlaylist(PlaylistType.Asx, "<asx><entry><REF HREF='http://a/s?x=1&amp;y=2' /></entry></asx>", "http://h/")
        assertEquals(listOf("http://a/s?x=1&y=2"), asx)
        // Bounded and without repeats.
        val many = (0 until 100).joinToString("\n") { "http://a/$it" } + "\nhttp://a/0"
        assertEquals(64, LiveParsers.parsePlaylist(PlaylistType.M3u, many, "http://h/").size)
        assertTrue(LiveParsers.parsePlaylist(PlaylistType.Hls, "#EXTM3U\nseg.ts", "http://h/").isEmpty())

        assertEquals("http://h/a/d/e.ts", LiveParsers.resolveUrl("http://h/a/b/c.m3u8", "../d/e.ts"))
        assertEquals("https://h:8443/z?q=1", LiveParsers.resolveUrl("https://h:8443/x/y", "/z?q=1"))
        assertEquals("http://cdn/s", LiveParsers.resolveUrl("http://h/a/b", "//cdn/s"))
        assertEquals("http://h/seg.ts", LiveParsers.resolveUrl("http://h", "seg.ts"))
        assertEquals("http://h/a/seg1.ts", LiveParsers.resolveUrl("http://h/a/b.m3u8?token=1", "seg1.ts"))
        assertEquals("http://h/a/b.m3u8?t=2", LiveParsers.resolveUrl("http://h/a/b.m3u8?t=1", "?t=2"))
        assertEquals("mms://old", LiveParsers.resolveUrl("http://h/", "mms://old"))
        assertEquals("", LiveParsers.resolveUrl("http://h/", "  "))
    }
}
