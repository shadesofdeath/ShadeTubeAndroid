package com.app.shadetube.lyrics

import com.app.shadetube.core.jsonArrayOf
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.dump
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Port of tests/lyrics (offline): parsers, ID3 frames, local lyrics, timeline, provider chain + cache, offsets. */
class LyricsTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("lyrics").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---- test helpers: ID3 tags as the downloads write them (audio/Downloader id3LyricsFrames) ---------------------
    private fun ByteArrayOutputStream.be32(v: Long) {
        write((v shr 24).toInt() and 0xFF); write((v shr 16).toInt() and 0xFF); write((v shr 8).toInt() and 0xFF); write(v.toInt() and 0xFF)
    }

    private fun ByteArrayOutputStream.utf16Bom(s: String, terminate: Boolean) {
        write(0xFF); write(0xFE)
        for (c in s) { write(c.code and 0xFF); write((c.code shr 8) and 0xFF) }
        if (terminate) { write(0); write(0) }
    }

    private fun rawFrame(out: ByteArrayOutputStream, id: String, data: ByteArray) {
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.be32(data.size.toLong())
        out.write(0); out.write(0)
        out.write(data)
    }

    private fun id3LyricsFrames(text: String, synced: List<Pair<Long, String>>): ByteArray {
        val frames = ByteArrayOutputStream()
        if (text.isNotEmpty()) {
            val d = ByteArrayOutputStream()
            d.write(byteArrayOf(1, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte()))
            d.utf16Bom("", true)
            d.utf16Bom(text, false)
            rawFrame(frames, "USLT", d.toByteArray())
        }
        if (synced.isNotEmpty()) {
            val d = ByteArrayOutputStream()
            d.write(byteArrayOf(1, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 2, 1))
            d.utf16Bom("", true)
            for ((ms, line) in synced) { d.utf16Bom(line, true); d.be32(ms) }
            rawFrame(frames, "SYLT", d.toByteArray())
        }
        return frames.toByteArray()
    }

    /** An ID3v2 tag around [frames] (synchsafe size) followed by a few fake MPEG bytes. */
    private fun id3Tag(frames: ByteArray, version: Int = 3, flags: Int = 0): ByteArray {
        val n = frames.size
        val head = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, flags.toByte(),
            ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
        )
        return head + frames + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0)
    }

    private fun frameV24(id: String, data: ByteArray, formatFlags: Int = 0): ByteArray {
        val n = data.size
        return id.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(
            ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
            0, formatFlags.toByte(),
        ) + data
    }

    private val mpeg = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0)

    // ---- lrc ------------------------------------------------------------------------------------------------------
    @Test
    fun lrc() {
        val l = Lrc.parse("[ti:Song]\n[ar:Artist]\n[00:01.00]One\n[00:03.50][00:10.00]Two\n\n[00:05.123]Three\n[00:07]\n")
        assertTrue(l.synced)
        assertEquals(5, l.lines.size)
        assertEquals(LyricLine(1000, "One"), l.lines[0])
        assertEquals(LyricLine(3500, "Two"), l.lines[1])
        assertEquals(LyricLine(5123, "Three"), l.lines[2])
        assertEquals(LyricLine(7000, ""), l.lines[3]) // instrumental gap
        assertEquals(LyricLine(10000, "Two"), l.lines[4])
        assertEquals(-1, activeLine(l, 0))
        assertEquals(1, activeLine(l, 3600))
        assertEquals(4, activeLine(l, 60000))

        // [offset:+500] shows lyrics 0.5 s sooner.
        val o = Lrc.parse("[offset:+500]\n[00:02.00]Hi\n[00:00.20]Zero\n")
        assertEquals(listOf(0, 1500), o.lines.map { it.timeMs })

        // Enhanced LRC: word times, words concatenate to the text.
        val w = Lrc.parse("[00:12.00]<00:12.10>Hello <00:12.60>big <00:13.00>world<00:13.80>\n[00:14.00]Plain line\n")
        assertEquals(2, w.lines.size)
        assertEquals("Hello big world", w.lines[0].text)
        assertEquals(3, w.lines[0].words.size)
        assertEquals(LyricWord(12100, "Hello "), w.lines[0].words[0])
        assertEquals(LyricWord(13000, "world"), w.lines[0].words[2])
        assertTrue(w.lines[1].words.isEmpty())
        // A piece before the first word time starts with the line.
        val w2 = Lrc.parse("[01:00.00]Oh <01:00.50>yeah\n")
        assertEquals(1, w2.lines.size)
        assertEquals(2, w2.lines[0].words.size)
        assertEquals(60000, w2.lines[0].words[0].timeMs)

        // Plain text.
        val p = Lrc.parse("﻿First\r\n\r\nSecond\r\n")
        assertFalse(p.synced)
        assertEquals(3, p.lines.size)
        assertEquals(-1, p.lines[0].timeMs)
        assertTrue(p.lines[1].text.isEmpty())
        // "[Chorus]" is text, not a tag.
        val c = Lrc.parse("[Chorus]\nLa la\n")
        assertFalse(c.synced)
        assertEquals(listOf("[Chorus]", "La la"), c.lines.map { it.text })

        // toLrc round trip (with words and metadata).
        val lrc = Lrc.toLrc(w, "T", "A", "Al")
        assertTrue(lrc.contains("[ti:T]") && lrc.contains("[by:ShadeTube]"))
        val back = Lrc.parse(lrc)
        assertTrue(back.synced)
        assertEquals(w.lines.size, back.lines.size)
        for (i in w.lines.indices) {
            assertEquals(w.lines[i].timeMs, back.lines[i].timeMs)
            assertEquals(w.lines[i].text, back.lines[i].text)
            assertEquals(w.lines[i].words.size, back.lines[i].words.size)
        }
        assertEquals("One\nTwo\nThree\n\nTwo", Lrc.toPlainText(l))
    }

    @Test
    fun timestamps() {
        assertEquals(61000, Lrc.parseTimestamp("01:01"))
        assertEquals(61500, Lrc.parseTimestamp("01:01.5"))
        assertEquals(61050, Lrc.parseTimestamp("01:01:05"))
        assertEquals(61123, Lrc.parseTimestamp("01:01.1234"))
        assertEquals(-1, Lrc.parseTimestamp("01:60"))
        assertEquals(-1, Lrc.parseTimestamp("ar:x"))
        assertEquals(-1, Lrc.parseTimestamp("01:01."))
        assertEquals("01:01.50", Lrc.formatTimestamp(61509))
        assertEquals("00:00.00", Lrc.formatTimestamp(-5))
    }

    // ---- spotify --------------------------------------------------------------------------------------------------
    @Test
    fun spotify() {
        val synced = """{"lyrics":{"syncType":"LINE_SYNCED","lines":[
            {"startTimeMs":"960","words":"First line","syllables":[],"endTimeMs":"0"},
            {"startTimeMs":"4210","words":"♪","syllables":[],"endTimeMs":"0"},
            {"startTimeMs":"2500","words":" Second line ","syllables":[],"endTimeMs":"0"},
            {"startTimeMs":"8000","words":"","syllables":[],"endTimeMs":"0"}],
            "provider":"MusixMatch","providerDisplayName":"Musixmatch","language":"en"},"colors":{"background":-1},"hasVocalRemoval":false}"""
        val s = SpotifyLyrics.parse(synced)
        assertNotNull(s)
        s!!
        assertTrue(s.synced)
        assertEquals(SOURCE_SPOTIFY, s.source)
        assertEquals(4, s.lines.size)
        assertEquals(LyricLine(960, "First line"), s.lines[0])
        assertEquals(LyricLine(2500, "Second line"), s.lines[1]) // sorted, trimmed
        assertEquals(LyricLine(4210, ""), s.lines[2]) // "♪" = gap

        val plain = """{"lyrics":{"syncType":"UNSYNCED","lines":[
            {"startTimeMs":"0","words":""},{"startTimeMs":"0","words":"Verse"},{"startTimeMs":"0","words":""},
            {"startTimeMs":"0","words":"Chorus"},{"startTimeMs":"0","words":""}]}}"""
        val u = SpotifyLyrics.parse(plain)!!
        assertFalse(u.synced)
        assertEquals(3, u.lines.size)
        assertEquals(LyricLine(-1, "Verse"), u.lines[0])
        assertNull(SpotifyLyrics.parse("""{"lyrics":{"syncType":"LINE_SYNCED","lines":[]}}"""))
        assertNull(SpotifyLyrics.parse("""{"lyrics":{"syncType":"LINE_SYNCED","lines":[{"startTimeMs":"1","words":"♪"}]}}"""))
        assertNull(SpotifyLyrics.parse("not json"))
        assertNull(SpotifyLyrics.parse("<html>busy</html>"))
        assertNull(SpotifyLyrics.parse("""{"error":{"status":404}}"""))
        // Numeric start times work too.
        assertEquals(1234, SpotifyLyrics.parse("""{"lyrics":{"syncType":"SYLLABLE_SYNCED","lines":[{"startTimeMs":1234,"words":"x"}]}}""")!!.lines[0].timeMs)
    }

    // ---- id3 ------------------------------------------------------------------------------------------------------
    @Test
    fun id3() {
        // What a download writes (UTF-16, SYLT in ms) reads back.
        val frames = id3LyricsFrames("Işık\n\nŞarkı sözü", listOf(1000L to "Işık", 2500L to "", 4000L to "Şarkı sözü"))
        val l = LocalLyrics.readId3Lyrics(id3Tag(frames))!!
        assertTrue(l.synced)
        assertEquals(SOURCE_TAG, l.source)
        assertEquals(listOf(LyricLine(1000, "Işık"), LyricLine(2500, ""), LyricLine(4000, "Şarkı sözü")), l.lines)
        // USLT only: plain.
        val u = LocalLyrics.readId3Lyrics(id3Tag(id3LyricsFrames("Line one\nLine two", emptyList())))!!
        assertFalse(u.synced)
        assertEquals(2, u.lines.size)
        assertEquals("Line two", u.lines[1].text)
        // USLT holding LRC: synced.
        val ul = LocalLyrics.readId3Lyrics(id3Tag(id3LyricsFrames("[00:01.00]A\n[00:02.00]B", emptyList())))!!
        assertTrue(ul.synced)
        assertEquals(2000, ul.lines[1].timeMs)
        // No lyrics frames / not a tag.
        assertNull(LocalLyrics.readId3Lyrics(id3Tag(byteArrayOf('T'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), '2'.code.toByte(), 0, 0, 0, 2, 0, 0, 0, 'x'.code.toByte()))))
        assertNull(LocalLyrics.readId3Lyrics("RIFF....".toByteArray()))

        // v2.4: synchsafe frame size, UTF-8 (encoding 3), per-frame unsynchronisation (0xFF 0x00 pairs).
        val uslt24 = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "Café ".toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte())
        val unsynced = ByteArrayOutputStream()
        for (b in uslt24) { unsynced.write(b.toInt()); if (b == 0xFF.toByte()) unsynced.write(0) }
        val v4 = LocalLyrics.readId3Lyrics(id3Tag(frameV24("USLT", unsynced.toByteArray(), 0x02), 4))!!
        assertEquals(1, v4.lines.size)
        assertTrue(v4.lines[0].text.startsWith("Café"))

        // SYLT karaoke syllables: a leading line break starts a new line.
        val sylt = ByteArrayOutputStream()
        sylt.write(3) // UTF-8
        sylt.write("eng".toByteArray())
        sylt.write(2) // ms
        sylt.write(1) // lyrics
        sylt.write(0) // empty descriptor
        fun entry(text: String, ms: Long) { sylt.write(text.toByteArray(Charsets.UTF_8)); sylt.write(0); sylt.be32(ms) }
        entry("Hel", 1000); entry("lo ", 1300); entry("world", 1600); entry("\nSe", 3000); entry("cond", 3400)
        val k = LocalLyrics.readId3Lyrics(id3Tag(frameV24("SYLT", sylt.toByteArray()), 4))!!
        assertTrue(k.synced)
        assertEquals(2, k.lines.size)
        assertEquals(1000, k.lines[0].timeMs)
        assertEquals("Hello world", k.lines[0].text)
        assertEquals(3, k.lines[0].words.size)
        assertEquals(3000, k.lines[1].timeMs)
        assertEquals("Second", k.lines[1].text)
        assertEquals(2, k.lines[1].words.size)
    }

    @Test
    fun id3WholeTagUnsyncV23() {
        // v2.3 with the unsynchronisation flag: the whole tag body is unsynchronised.
        val frames = id3LyricsFrames("", listOf(500L to "ÿ line", 900L to "Two"))
        val unsynced = ByteArrayOutputStream()
        for (b in frames) { unsynced.write(b.toInt()); if (b == 0xFF.toByte()) unsynced.write(0) }
        val l = LocalLyrics.readId3Lyrics(id3Tag(unsynced.toByteArray(), 3, 0x80))!!
        assertEquals(listOf("ÿ line", "Two"), l.lines.map { it.text })
    }

    // ---- local ----------------------------------------------------------------------------------------------------
    @Test
    fun local() {
        val music = File(dir, "music").also { it.mkdirs() }
        // Sidecar (UTF-8, no BOM) next to an audio file without tags.
        File(music, "Şarkı.mp3").writeBytes(mpeg)
        File(music, "Şarkı.lrc").writeText("[00:01.00]Merhaba\n[00:02.00]Dünya\n")
        assertEquals(File(music, "Şarkı.lrc"), LocalLyrics.sidecarPath(File(music, "Şarkı.mp3")))
        val s = LocalLyrics.readLocal(File(music, "Şarkı.mp3"))!!
        assertTrue(s.synced)
        assertEquals(SOURCE_FILE, s.source)
        assertEquals("Dünya", s.lines[1].text)

        // UTF-16 LE sidecar.
        val u16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "[00:03.00]Ünlü".toByteArray(Charsets.UTF_16LE)
        assertEquals("[00:03.00]Ünlü", LocalLyrics.decodeText(u16))
        val u16be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Ünlü".toByteArray(Charsets.UTF_16BE)
        assertEquals("Ünlü", LocalLyrics.decodeText(u16be))
        // ANSI (not UTF-8) decodes through a code page: only check it turns into text.
        val dec = LocalLyrics.decodeText(byteArrayOf('C'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte()))
        assertTrue(dec.length == 4 && dec.startsWith("Caf"))
        assertEquals("abc", LocalLyrics.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()))

        // Embedded synced lyrics win over an unsynced sidecar; a synced sidecar wins over embedded ones.
        File(music, "tagged.mp3").writeBytes(id3Tag(id3LyricsFrames("", listOf(500L to "Tag line", 900L to "Two"))))
        File(music, "tagged.lrc").writeText("Just text\n")
        val t = LocalLyrics.readLocal(File(music, "tagged.mp3"))!!
        assertTrue(t.synced)
        assertEquals(SOURCE_TAG, t.source)
        assertEquals("Tag line", t.lines[0].text)
        File(music, "tagged.lrc").writeText("[00:00.10]Sidecar\n")
        val t2 = LocalLyrics.readLocal(File(music, "tagged.mp3"))!!
        assertEquals(SOURCE_FILE, t2.source)
        assertEquals("Sidecar", t2.lines[0].text)
        // Unsynced sidecar, no tag lyrics: the sidecar.
        File(music, "plain.mp3").writeBytes(mpeg)
        File(music, "plain.lrc").writeText("Only words\n")
        val pl = LocalLyrics.readLocal(File(music, "plain.mp3"))!!
        assertFalse(pl.synced)
        assertEquals(SOURCE_FILE, pl.source)
        // Nothing.
        File(music, "none.mp3").writeBytes(mpeg)
        assertNull(LocalLyrics.readLocal(File(music, "none.mp3")))
        assertNull(LocalLyrics.readLocal(File(music, "missing.mp3")))
    }

    @Test
    fun flacAndMp4Tags() {
        // FLAC: "fLaC", STREAMINFO (34 bytes), VORBIS_COMMENT (last) with LYRICS=.
        fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
        val vendor = "test".toByteArray()
        val comment = "LYRICS=[00:01.00]Flac line\n[00:02.00]Two".toByteArray(Charsets.UTF_8)
        val vc = le32(vendor.size) + vendor + le32(1) + le32(comment.size) + comment
        val flac = "fLaC".toByteArray() +
            byteArrayOf(0, 0, 0, 34) + ByteArray(34) +
            byteArrayOf(0x84.toByte(), 0, ((vc.size shr 8) and 0xFF).toByte(), (vc.size and 0xFF).toByte()) + vc
        File(dir, "a.flac").writeBytes(flac)
        val f = LocalLyrics.readLocal(File(dir, "a.flac"))!!
        assertTrue(f.synced)
        assertEquals(SOURCE_TAG, f.source)
        assertEquals("Flac line", f.lines[0].text)

        // MP4: ftyp, moov/udta/meta(full box)/hdlr+ilst/©lyr/data.
        fun box(type: String, payload: ByteArray): ByteArray {
            val size = 8 + payload.size
            return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) +
                type.toByteArray(Charsets.ISO_8859_1) + payload
        }
        val data = box("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + "Plain mp4 lyrics\nSecond".toByteArray())
        val ilst = box("ilst", box("©lyr", data))
        val meta = box("meta", byteArrayOf(0, 0, 0, 0) + box("hdlr", ByteArray(25)) + ilst)
        val mp4 = box("ftyp", "M4A \u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)) + box("moov", box("udta", meta)) + box("mdat", ByteArray(16))
        File(dir, "b.m4a").writeBytes(mp4)
        val m = LocalLyrics.readLocal(File(dir, "b.m4a"))!!
        assertFalse(m.synced)
        assertEquals(listOf("Plain mp4 lyrics", "Second"), m.lines.map { it.text })
    }

    // ---- timeline -------------------------------------------------------------------------------------------------
    @Test
    fun timeline() {
        val extra = listOf(0L to 5000L, 60000L to 70000L)
        assertEquals(0, LyricsTimeline.songTime(2000, extra)) // inside the intro: the song hasn't started
        assertEquals(0, LyricsTimeline.songTime(5000, extra))
        assertEquals(25000, LyricsTimeline.songTime(30000, extra))
        assertEquals(55000, LyricsTimeline.songTime(65000, extra)) // inside the second part: where it was spliced
        assertEquals(65000, LyricsTimeline.songTime(80000, extra))
        assertEquals(1234, LyricsTimeline.songTime(1234, emptyList()))
        assertEquals(5000, LyricsTimeline.mediaTime(0, extra)) // the song starts after the intro
        assertEquals(30000, LyricsTimeline.mediaTime(25000, extra))
        assertEquals(70000, LyricsTimeline.mediaTime(55000, extra)) // exactly at the second part: after it
        assertEquals(80000, LyricsTimeline.mediaTime(65000, extra))
        assertEquals(1234, LyricsTimeline.mediaTime(1234, emptyList()))
        for (s in listOf(1000L, 25000L, 54999L, 55001L, 90000L))
            assertEquals(s, LyricsTimeline.songTime(LyricsTimeline.mediaTime(s, extra), extra))
        // Offsets: + = lyrics later.
        assertEquals(24500, LyricsTimeline.clockMs(30000, extra, 500))
        assertEquals(30000, LyricsTimeline.seekTarget(24500, extra, 500))
        assertEquals(5000, LyricsTimeline.seekTarget(100, extra, -1000))
    }

    // ---- chain ----------------------------------------------------------------------------------------------------
    private val cacheDir get() = File(dir, "cache/lyrics")

    private fun cacheFile(key: String): File {
        val safe = key.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return File(cacheDir, "$safe.json")
    }

    private fun plant(key: String, j: JsonElement) {
        cacheDir.mkdirs()
        cacheFile(key).writeText(j.dump())
    }

    private fun readCacheJson(key: String): JsonObject = Json.parseToJsonElement(cacheFile(key).readText()) as JsonObject

    private val fetcher = LyricsFetcher(
        cacheDir = { cacheDir },
        lrclib = { throw AssertionError("LRCLIB must not be reached in tests") },
    )

    private fun query(key: String, calls: IntArray, kind: ProviderResult.Kind, syncedResult: Boolean = true) = LyricsQuery(
        cacheKey = key,
        title = "Title",
        artist = "Artist",
        fallbackName = SOURCE_SPOTIFY,
        fallback = {
            calls[0]++
            if (kind == ProviderResult.Kind.Found) {
                ProviderResult.found(
                    Lrc.parse(if (syncedResult) "[00:01.00]From fallback\n[00:02.00]Two" else "Plain fallback\nTwo")
                        .copy(source = SOURCE_SPOTIFY),
                )
            } else ProviderResult(kind)
        },
    )

    @Test
    fun chain() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val unsyncedLines = jsonArrayOf(jsonArrayOf(-1, "Plain"), jsonArrayOf(-1, "Two"))
        // LRCLIB said "none" (old entry without "tried"): the fallback is asked once, its answer cached with both names.
        plant("spotify:track:a", jsonOf("v" to 2, "found" to false, "at" to now))
        val calls = intArrayOf(0)
        var r = fetcher.fetch(query("spotify:track:a", calls, ProviderResult.Kind.Found))
        assertEquals(1, calls[0])
        assertTrue(r!!.synced)
        assertEquals(SOURCE_SPOTIFY, r.source)
        assertEquals("From fallback", r.lines[0].text)
        val j = readCacheJson("spotify:track:a")
        assertEquals("true", j["found"]!!.jsonPrimitive.content)
        assertEquals("spotify", j["src"]!!.jsonPrimitive.content)
        assertEquals(2, (j["tried"] as JsonArray).size)
        val again = fetcher.fetch(query("spotify:track:a", calls, ProviderResult.Kind.Failed))
        assertEquals(1, calls[0]) // cached: not asked again
        assertEquals(SOURCE_SPOTIFY, again!!.source)

        // LRCLIB had only unsynced lyrics: synced ones from the fallback replace them.
        plant("spotify:track:b", jsonOf("v" to 2, "found" to true, "at" to now, "synced" to false, "lines" to unsyncedLines))
        calls[0] = 0
        r = fetcher.fetch(query("spotify:track:b", calls, ProviderResult.Kind.Found))
        assertEquals(1, calls[0])
        assertTrue(r!!.synced)
        assertEquals(SOURCE_SPOTIFY, r.source)
        // ... but unsynced fallback lyrics don't replace unsynced LRCLIB ones.
        plant("spotify:track:c", jsonOf("v" to 2, "found" to true, "at" to now, "synced" to false, "lines" to unsyncedLines))
        calls[0] = 0
        r = fetcher.fetch(query("spotify:track:c", calls, ProviderResult.Kind.Found, false))
        assertEquals(1, calls[0])
        assertFalse(r!!.synced)
        assertEquals(SOURCE_LRCLIB, r.source)
        assertEquals("Plain", r.lines[0].text)

        // Synced LRCLIB lyrics: the fallback is never asked.
        plant("spotify:track:d", jsonOf("v" to 2, "found" to true, "at" to now, "synced" to true, "lines" to jsonArrayOf(jsonArrayOf(1000, "Synced"))))
        calls[0] = 0
        r = fetcher.fetch(query("spotify:track:d", calls, ProviderResult.Kind.Found))
        assertEquals(0, calls[0])
        assertTrue(r!!.synced)
        assertEquals("Synced", r.lines[0].text)

        // The fallback failed (network / 429): nothing cached, it is asked again next time.
        plant("spotify:track:e", jsonOf("v" to 2, "found" to false, "at" to now, "tried" to listOf("lrclib")))
        calls[0] = 0
        r = fetcher.fetch(query("spotify:track:e", calls, ProviderResult.Kind.Failed))
        assertEquals(1, calls[0])
        assertNull(r)
        assertEquals(1, (readCacheJson("spotify:track:e")["tried"] as JsonArray).size)
        r = fetcher.fetch(query("spotify:track:e", calls, ProviderResult.Kind.NotFound))
        assertEquals(2, calls[0])
        assertNull(r)
        assertEquals(2, (readCacheJson("spotify:track:e")["tried"] as JsonArray).size) // a definitive "none" is remembered
        fetcher.fetch(query("spotify:track:e", calls, ProviderResult.Kind.Found))
        assertEquals(2, calls[0])

        // Words survive the cache.
        plant(
            "spotify:track:f",
            jsonOf(
                "v" to 2, "found" to true, "at" to now, "synced" to true, "src" to "lrclib",
                "lines" to jsonArrayOf(jsonArrayOf(1000, "Hi you")),
                "words" to jsonOf("0" to jsonArrayOf(jsonArrayOf(1000, "Hi "), jsonArrayOf(1400, "you"))),
            ),
        )
        r = fetcher.fetch(query("spotify:track:f", calls, ProviderResult.Kind.Failed))
        assertEquals(1, r!!.lines.size)
        assertEquals(2, r.lines[0].words.size)
        assertEquals(1400, r.lines[0].words[1].timeMs)

        // A file's synced lyrics come first: no provider, no cache.
        val music = File(dir, "music").also { it.mkdirs() }
        File(music, "local.mp3").writeBytes(mpeg)
        File(music, "local.lrc").writeText("[00:00.50]Mine\n")
        val q = query("local:1", calls, ProviderResult.Kind.Found).copy(audio = FileAudio(File(music, "local.mp3")))
        calls[0] = 0
        r = fetcher.fetch(q)
        assertEquals(0, calls[0])
        assertEquals(SOURCE_FILE, r!!.source)
        assertFalse(cacheFile("local:1").exists())
        // An unsynced file vs an online "none": the file.
        File(music, "local.lrc").writeText("Mine, unsynced\n")
        plant("local:1", jsonOf("v" to 2, "found" to false, "at" to now, "tried" to listOf("lrclib", "spotify")))
        r = fetcher.fetch(q)
        assertEquals(0, calls[0])
        assertFalse(r!!.synced)
        assertEquals(SOURCE_FILE, r.source)
        // An unsynced file vs synced online lyrics: the synced ones.
        plant("local:1", jsonOf("v" to 2, "found" to true, "at" to now, "synced" to true, "lines" to jsonArrayOf(jsonArrayOf(1000, "Online")), "tried" to listOf("lrclib")))
        r = fetcher.fetch(q)
        assertTrue(r!!.synced)
        assertEquals("Online", r.lines[0].text)
    }

    @Test
    fun expiredNegativeAndOtherVersionAreIgnored() = runBlocking {
        val calls = intArrayOf(0)
        val lrclibCalls = intArrayOf(0)
        val f = LyricsFetcher(cacheDir = { cacheDir }, lrclib = { lrclibCalls[0]++; ProviderResult.notFound })
        plant("k1", jsonOf("v" to 2, "found" to false, "at" to System.currentTimeMillis() / 1000 - 8 * 24 * 3600, "tried" to listOf("lrclib", "spotify")))
        f.fetch(query("k1", calls, ProviderResult.Kind.NotFound))
        assertEquals(1, lrclibCalls[0]) // a "none" older than 7 days is asked again
        assertEquals(1, calls[0])
        plant("k2", jsonOf("v" to 1, "found" to true, "synced" to true, "lines" to jsonArrayOf(jsonArrayOf(1, "x"))))
        f.fetch(query("k2", calls, ProviderResult.Kind.NotFound))
        assertEquals(2, lrclibCalls[0])
        // A transient LRCLIB failure is not remembered.
        val failing = LyricsFetcher(cacheDir = { cacheDir }, lrclib = { ProviderResult.failed })
        assertNull(failing.fetch(LyricsQuery("k3", "T")))
        assertFalse(cacheFile("k3").exists())
    }

    @Test
    fun cacheFileNamesMatchTheDesktop() {
        assertEquals("spotify_track_abc.json", fetcher.cacheFile("spotify:track:abc")!!.name)
        assertEquals("a__b.json", fetcher.cacheFile("aşb")!!.name) // one '_' per UTF-8 byte
        assertNull(fetcher.cacheFile(""))
    }

    // ---- lrclib ---------------------------------------------------------------------------------------------------
    @Test
    fun lrclibRecords() {
        // Junk records (a single short line) are not lyrics.
        assertNull(Lrclib.fromRecord(Json.parseToJsonElement("""{"syncedLyrics":"[00:00.00]probe","plainLyrics":"probe"}""")))
        assertTrue(Lrclib.fromRecord(Json.parseToJsonElement("""{"instrumental":true}"""))!!.instrumental)
        val results = Json.parseToJsonElement(
            """[{"duration":300,"syncedLyrics":"[00:01.00]Far\n[00:02.00]Away"},
                {"duration":201,"plainLyrics":"Plain one\nPlain two"},
                {"duration":203,"syncedLyrics":"[00:01.00]Close\n[00:02.00]Enough"},
                {"duration":200.5,"syncedLyrics":"[00:01.00]Closest\n[00:02.00]One"}]""",
        )
        assertEquals("Closest", Lrclib.pickFromSearch(results, 200)!!.lines[0].text)
        assertEquals("Far", Lrclib.pickFromSearch(results, 0)!!.lines[0].text) // no duration: the first synced
        val plainOnly = Json.parseToJsonElement("""[{"duration":201,"plainLyrics":"Plain one\nPlain two"}]""")
        assertFalse(Lrclib.pickFromSearch(plainOnly, 200)!!.synced)
        assertNull(Lrclib.pickFromSearch(plainOnly, 260))
    }

    // ---- offsets --------------------------------------------------------------------------------------------------
    @Test
    fun offsets() {
        val file = File(dir, "offsets.json")
        LyricsOffsets(file).run {
            assertEquals(0, offsetMs("x"))
            assertEquals(750, set("x", 750))
            assertEquals(-1250, set("y", -1250))
            assertEquals(LyricsOffsets.MAX_MS, set("z", 999999))
            assertEquals(0, set("", 100))
        }
        LyricsOffsets(file).run { // reloaded from disk
            assertEquals(750, offsetMs("x"))
            assertEquals(-1250, offsetMs("y"))
            assertEquals(LyricsOffsets.MAX_MS, offsetMs("z"))
            assertEquals(0, set("x", 0)) // 0 removes
        }
        LyricsOffsets(file).run {
            assertEquals(0, offsetMs("x"))
            assertEquals(-1250, offsetMs("y"))
        }
        file.writeText("{corrupt")
        assertEquals(0, LyricsOffsets(file).offsetMs("y"))
    }

    // ---- service --------------------------------------------------------------------------------------------------
    @Test
    fun serviceQueryAndSpotifyPause() = runBlocking {
        var loggedIn: SpotifyLyricsSource? = null
        var spotifyCalls = 0
        val service = LyricsService(
            spotify = { loggedIn },
            fetcher = LyricsFetcher(cacheDir = { cacheDir }, lrclib = { ProviderResult.notFound }),
            offsetsFile = { File(dir, "lyrics-offsets.json") },
        )
        val track = com.app.shadetube.catalog.Track(
            id = "spotify:track:x", name = "Song", durationMs = 201_900,
            artists = listOf(com.app.shadetube.catalog.ArtistRef("a", "First"), com.app.shadetube.catalog.ArtistRef("b", "Second")),
            album = com.app.shadetube.catalog.AlbumRef("al", "Album"),
        )
        val q0 = service.queryFor(track)
        assertEquals("spotify:track:x", q0.cacheKey)
        assertEquals("First", q0.artist)
        assertEquals(201, q0.durationSec)
        assertNull(q0.fallback) // logged out
        // Logged out: LRCLIB only, the "none" is cached with lrclib alone, so Spotify is asked after a login.
        assertNull(service.lyricsFor(track))
        loggedIn = SpotifyLyricsSource { spotifyCalls++; throw com.app.shadetube.core.HttpError(429, "", "HTTP 429") }
        assertNull(service.lyricsFor(track))
        assertEquals(1, spotifyCalls)
        assertNull(service.lyricsFor(track)) // paused for 10 minutes: not asked
        assertEquals(1, spotifyCalls)

        // Offsets.
        assertEquals(250, service.offsetBy("t1", LyricsService.OFFSET_STEP_MS))
        assertEquals(500, service.offsetBy("t1", LyricsService.OFFSET_STEP_MS))
        assertEquals(LyricsService.OffsetChange("t1", 500, service.offsetChanges.value!!.atMs), service.offsetChanges.value)
        assertEquals(0, service.offsetBy("radio:abc", 250))
        assertEquals(500, service.offsetMs("t1"))
    }

    @Test
    fun sidecarWrite() = runBlocking {
        val service = LyricsService(spotify = { null }, fetcher = LyricsFetcher(cacheDir = { cacheDir }, lrclib = { ProviderResult.notFound }), offsetsFile = { File(dir, "o.json") })
        val audio = File(dir, "Song.mp3").also { it.writeBytes(mpeg) }
        val l = Lrc.parse("[00:01.00]A\n[00:02.00]B")
        val t = com.app.shadetube.catalog.Track(name = "Song", artists = listOf(com.app.shadetube.catalog.ArtistRef(name = "Art")))
        assertTrue(service.writeLyricsSidecar(audio, l, t))
        val text = File(dir, "Song.lrc").readText()
        assertTrue(text.startsWith("﻿[ti:Song]\n[ar:Art]\n[by:ShadeTube]\n[00:01.00]A"))
        assertEquals(l.lines, LocalLyrics.readLocal(audio)!!.lines)
        assertFalse(service.writeLyricsSidecar(audio, Lrc.parse("plain"), t))
    }
}
