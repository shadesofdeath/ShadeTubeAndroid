package com.app.shadetube.downloads

import com.app.shadetube.localfiles.ArraySource
import com.app.shadetube.localfiles.ReplayGain
import com.app.shadetube.localfiles.TagReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ID3v2.3 writer: header (version 3, no flags, syncsafe size), frame layout (plain 32-bit sizes), UTF-16 text with a
 * BOM, APIC, TXXX ReplayGain, USLT and SYLT (ms, UTF-16) — parsed back here and by the app's own readers
 * (localfiles TagReader / ReplayGain).
 */
class Id3WriterTest {
    private val title = "Şarkı ğüşıöç — 日本語 🎵" // Turkish, CJK and a surrogate pair
    private val cover = ByteArray(300_000) { (it * 31).toByte() }.also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte()
    }
    private val synced = listOf(0L to "İlk satır", 12_345L to "second line", 4_000_000_000L to "far")

    private fun tags() = Id3Writer.Tags(
        title = title,
        artist = "Artist A, Artist B",
        album = "Album",
        albumArtist = "Artist A",
        trackNumber = 7,
        year = "2021-05-01",
        cover = cover,
        lyricsText = "line one\nline two",
        syncedLyrics = synced,
        replayGainDb = -5.5f,
    )

    private class Frame(val id: String, val data: ByteArray)

    private fun parse(tag: ByteArray): List<Frame> {
        assertEquals("ID3", String(tag, 0, 3, Charsets.ISO_8859_1))
        assertEquals(3, tag[3].toInt()) // v2.3
        assertEquals(0, tag[4].toInt())
        assertEquals(0, tag[5].toInt()) // no flags
        for (i in 6..9) assertTrue("syncsafe byte $i", tag[i].toInt() and 0x80 == 0)
        val size = ((tag[6].toInt() and 0x7F) shl 21) or ((tag[7].toInt() and 0x7F) shl 14) or ((tag[8].toInt() and 0x7F) shl 7) or (tag[9].toInt() and 0x7F)
        assertEquals(tag.size - 10, size)
        val frames = ArrayList<Frame>()
        var p = 10
        while (p < tag.size) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            val len = ((tag[p + 4].toInt() and 0xFF) shl 24) or ((tag[p + 5].toInt() and 0xFF) shl 16) or ((tag[p + 6].toInt() and 0xFF) shl 8) or (tag[p + 7].toInt() and 0xFF)
            assertEquals(0, tag[p + 8].toInt())
            assertEquals(0, tag[p + 9].toInt())
            frames.add(Frame(id, tag.copyOfRange(p + 10, p + 10 + len)))
            p += 10 + len
        }
        assertEquals(tag.size, p)
        return frames
    }

    /** UTF-16 with BOM (FF FE), little endian; returns (text, bytes consumed incl. a terminator when present). */
    private fun utf16(b: ByteArray, from: Int, terminated: Boolean): Pair<String, Int> {
        assertEquals(0xFF, b[from].toInt() and 0xFF)
        assertEquals(0xFE, b[from + 1].toInt() and 0xFF)
        val sb = StringBuilder()
        var i = from + 2
        while (i + 1 < b.size) {
            val cu = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            if (terminated && cu == 0) return sb.toString() to (i - from)
            sb.append(cu.toChar())
        }
        assertTrue("unterminated", !terminated)
        return sb.toString() to (i - from)
    }

    private fun text(f: Frame): String {
        assertEquals("encoding of ${f.id}", 1, f.data[0].toInt())
        return utf16(f.data, 1, false).first
    }

    @Test
    fun framesParseBack() {
        val tag = Id3Writer.build(tags())
        val frames = parse(tag)
        assertEquals(listOf("TIT2", "TPE1", "TALB", "TPE2", "TRCK", "TYER", "TSSE", "APIC", "TXXX", "USLT", "SYLT"), frames.map { it.id })
        val byId = frames.associateBy { it.id }
        assertEquals(title, text(byId.getValue("TIT2")))
        assertEquals("Artist A, Artist B", text(byId.getValue("TPE1")))
        assertEquals("Album", text(byId.getValue("TALB")))
        assertEquals("Artist A", text(byId.getValue("TPE2")))
        assertEquals("7", text(byId.getValue("TRCK")))
        assertEquals("2021", text(byId.getValue("TYER")))
        assertEquals("ShadeTube", text(byId.getValue("TSSE")))

        // APIC: enc 0, "image/jpeg\0", type 3 (front cover), empty description, picture.
        val apic = byId.getValue("APIC").data
        val head = byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0)
        assertArrayEquals(head, apic.copyOfRange(0, head.size))
        assertArrayEquals(cover, apic.copyOfRange(head.size, apic.size))

        // TXXX: ISO-8859-1 description + value.
        val txxx = byId.getValue("TXXX").data
        assertEquals("\u0000REPLAYGAIN_TRACK_GAIN\u0000-5.50 dB", String(txxx, Charsets.ISO_8859_1))

        // USLT: enc 1, "eng", empty UTF-16 descriptor, text.
        val uslt = byId.getValue("USLT").data
        assertEquals(1, uslt[0].toInt())
        assertEquals("eng", String(uslt, 1, 3, Charsets.ISO_8859_1))
        val (desc, dn) = utf16(uslt, 4, true)
        assertEquals("", desc)
        assertEquals("line one\nline two", utf16(uslt, 4 + dn, false).first)

        // SYLT: enc 1, "eng", format 2 (ms), type 1 (lyrics), empty descriptor, then (text\0\0, BE32 ms) pairs.
        val sylt = byId.getValue("SYLT").data
        assertEquals(1, sylt[0].toInt())
        assertEquals("eng", String(sylt, 1, 3, Charsets.ISO_8859_1))
        assertEquals(2, sylt[4].toInt())
        assertEquals(1, sylt[5].toInt())
        var p = 6
        val (d2, n2) = utf16(sylt, p, true)
        assertEquals("", d2)
        p += n2
        val lines = ArrayList<Pair<Long, String>>()
        while (p < sylt.size) {
            val (line, n) = utf16(sylt, p, true)
            p += n
            val ms = ((sylt[p].toLong() and 0xFF) shl 24) or ((sylt[p + 1].toLong() and 0xFF) shl 16) or
                ((sylt[p + 2].toLong() and 0xFF) shl 8) or (sylt[p + 3].toLong() and 0xFF)
            p += 4
            lines.add(ms to line)
        }
        assertEquals(sylt.size, p)
        assertEquals(listOf(0L to "İlk satır", 12_345L to "second line", 4_000_000_000L to "far"), lines)
    }

    @Test
    fun appReadersUnderstandIt() {
        val tag = Id3Writer.build(tags())
        assertEquals(-5.5f, ReplayGain.readTrackGainDb(ArraySource(tag))!!, 1e-4f)
        val t = TagReader.read(ArraySource(tag), wantPicture = true)
        assertEquals(title, t.title)
        assertEquals("Album", t.album)
        assertEquals("Artist A", t.albumArtist)
        assertEquals(7, t.trackNumber)
        assertEquals(2021, t.year)
        assertArrayEquals(cover, t.picture)
    }

    @Test
    fun emptyAndMinimalTags() {
        // TSSE is always there; nothing else when nothing is given.
        val minimal = parse(Id3Writer.build(Id3Writer.Tags()))
        assertEquals(listOf("TSSE"), minimal.map { it.id })
        // PNG covers say so; a positive gain keeps its sign.
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)
        val frames = parse(Id3Writer.build(Id3Writer.Tags(cover = png, replayGainDb = 3f))).associateBy { it.id }
        assertEquals("image/png", String(frames.getValue("APIC").data, 1, 9, Charsets.ISO_8859_1))
        assertTrue(String(frames.getValue("TXXX").data, Charsets.ISO_8859_1).endsWith("+3.00 dB"))
        assertEquals(0, Id3Writer.lyricsFrames("", emptyList()).size)
    }
}
