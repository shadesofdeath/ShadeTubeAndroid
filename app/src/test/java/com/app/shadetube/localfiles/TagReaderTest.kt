package com.app.shadetube.localfiles

import com.app.shadetube.localfiles.TestAudio.apic
import com.app.shadetube.localfiles.TestAudio.id3
import com.app.shadetube.localfiles.TestAudio.mp4Text
import com.app.shadetube.localfiles.TestAudio.text
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The own tag reader (replacement for the Windows property system) over tags built in bytes. */
class TagReaderTest {
    private fun read(bytes: ByteArray, picture: Boolean = true) = TagReader.read(ArraySource(bytes), picture)

    @Test
    fun id3v23() {
        val back = TestAudio.jpeg(0x22)
        val front = TestAudio.jpeg(0x33)
        val tag = id3(
            3,
            listOf(
                "TIT2" to text(1, "Başlık Bir"),
                "TPE1" to text(3, "A; B"), // UTF-8 in a v2.3 tag (what the C++ downloader writes)
                "TPE2" to text(0, "Album Artist"),
                "TALB" to text(0, "Albüm"),
                "TRCK" to text(0, "3/12"),
                "TPOS" to text(0, "2/2"),
                "TYER" to text(0, "2024"),
                "TLEN" to text(0, "123456"),
                "APIC" to apic(0, 4, back),
                "APIC" to apic(3, 3, front), // front cover wins; encoding 3 in v2.3 (Windows ignored these)
            ),
        )
        val t = read(tag + TestAudio.mp3Frames(5))
        assertEquals("Başlık Bir", t.title)
        assertEquals(listOf("A", "B"), t.artists)
        assertEquals("Album Artist", t.albumArtist)
        assertEquals("Albüm", t.album)
        assertEquals(3, t.trackNumber)
        assertEquals(2, t.discNumber)
        assertEquals(2024, t.year)
        assertEquals(123456, t.durationMs) // TLEN wins over the frames
        assertArrayEquals(front, t.picture)
        // Without pictures asked: none read.
        assertNull(read(tag, picture = false).picture)
    }

    @Test
    fun id3v24MultiValuesAndUnsync() {
        val pic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x12, 0xFF.toByte(), 0xE0.toByte())
        // A frame with the per-frame unsynchronisation flag: every FF is followed by an inserted 00.
        val raw = apic(0, 3, pic)
        val unsynced = TestAudio.b().apply {
            for ((i, x) in raw.withIndex()) {
                u8(x.toInt())
                if (x == 0xFF.toByte() && (i + 1 == raw.size || raw[i + 1].toInt() and 0xE0 == 0xE0 || raw[i + 1].toInt() == 0)) u8(0)
            }
        }.get()
        val frames = TestAudio.b()
            .ascii("TPE1").syncsafe(text(3, "X", "Y", "X").size).u8(0).u8(0).bytes(text(3, "X", "Y", "X"))
            .ascii("TDRC").syncsafe(text(3, "2021-05-01").size).u8(0).u8(0).bytes(text(3, "2021-05-01"))
            .ascii("APIC").syncsafe(unsynced.size).u8(0).u8(0x02).bytes(unsynced)
            .get()
        val tag = TestAudio.b().ascii("ID3").u8(4).u8(0).u8(0).syncsafe(frames.size).bytes(frames).get()
        val t = read(tag)
        assertEquals(listOf("X", "Y"), t.artists)
        assertEquals(2021, t.year)
        assertArrayEquals(pic, t.picture)
        assertEquals("", t.title)
    }

    @Test
    fun id3v22AndV1() {
        val v22 = id3(2, listOf("TT2" to text(0, "Eski"), "TP1" to text(0, "Grup"), "TRK" to text(0, "7"), "PIC" to TestAudio.b().u8(0).ascii("PNG").u8(3).u8(0).bytes(TestAudio.png()).get()))
        val t = read(v22)
        assertEquals("Eski", t.title)
        assertEquals(listOf("Grup"), t.artists)
        assertEquals(7, t.trackNumber)
        assertEquals(".png", TagReader.imageExt(t.picture))

        // ID3v1 only (and filling what ID3v2 leaves out).
        val v1 = TestAudio.id3v1("V1 Title", "V1 Artist", "V1 Album", "1999", 4)
        val only = read(TestAudio.mp3Frames(3) + v1)
        assertEquals("V1 Title", only.title)
        assertEquals(listOf("V1 Artist"), only.artists)
        assertEquals("V1 Album", only.album)
        assertEquals(1999, only.year)
        assertEquals(4, only.trackNumber)
        val both = read(id3(3, listOf("TIT2" to text(0, "V2"))) + TestAudio.mp3Frames(3) + v1)
        assertEquals("V2", both.title)
        assertEquals("V1 Album", both.album)
    }

    @Test
    fun mp3Duration() {
        // CBR: 100 frames of 417 bytes at 128 kbit/s.
        val cbr = read(id3(3, listOf("TIT2" to text(0, "x"))) + TestAudio.mp3Frames(100))
        assertEquals(41700 * 8 / 128, cbr.durationMs)
        // Xing: 1000 frames of 1152 samples at 44.1 kHz.
        val vbr = read(TestAudio.mp3Frames(10, xingFrames = 1000))
        assertEquals((1000L * 1152 * 1000 / 44100).toInt(), vbr.durationMs)
    }

    @Test
    fun flac() {
        val pic = TestAudio.png()
        val f = TestAudio.flac(
            listOf("TITLE=Flac Şarkı", "ARTIST=Bir", "ARTIST=İki", "ALBUM=Albüm", "ALBUMARTIST=Toplu", "TRACKNUMBER=3", "DISCNUMBER=1/2", "DATE=2020-01-01"),
            sampleRate = 44100, totalSamples = 44100L * 4, picture = pic,
        )
        val t = read(f)
        assertEquals("Flac Şarkı", t.title)
        assertEquals(listOf("Bir", "İki"), t.artists)
        assertEquals("Albüm", t.album)
        assertEquals("Toplu", t.albumArtist)
        assertEquals(3, t.trackNumber)
        assertEquals(1, t.discNumber)
        assertEquals(2020, t.year)
        assertEquals(4000, t.durationMs)
        assertArrayEquals(pic, t.picture)
        // A (non-standard) ID3 tag in front of the FLAC.
        val withId3 = read(id3(3, listOf("TIT2" to text(0, "ID3 Title"))) + f)
        assertEquals("ID3 Title", withId3.title)
        assertEquals("Albüm", withId3.album)
    }

    @Test
    fun mp4() {
        val cover = TestAudio.jpeg()
        val m = TestAudio.mp4(
            listOf(
                "©nam" to mp4Text("Dalga Testi"),
                "©ART" to mp4Text("Test Sanatçı"),
                "aART" to mp4Text("Test Sanatçı"),
                "©alb" to mp4Text("Sentez Albüm"),
                "trkn" to TestAudio.dataBox(0, byteArrayOf(0, 0, 0, 3, 0, 12, 0, 0)),
                "disk" to TestAudio.dataBox(0, byteArrayOf(0, 0, 0, 2, 0, 2)),
                "©day" to mp4Text("2024"),
                "covr" to TestAudio.dataBox(13, cover),
            ),
            durationMs = 4000,
        )
        val t = read(m)
        assertEquals("Dalga Testi", t.title)
        assertEquals(listOf("Test Sanatçı"), t.artists)
        assertEquals("Test Sanatçı", t.albumArtist)
        assertEquals("Sentez Albüm", t.album)
        assertEquals(3, t.trackNumber)
        assertEquals(2, t.discNumber)
        assertEquals(2024, t.year)
        assertEquals(4000, t.durationMs)
        assertArrayEquals(cover, t.picture)
    }

    @Test
    fun ogg() {
        val v = read(TestAudio.oggVorbis(listOf("TITLE=Ogg", "ARTIST=Vorbis", "TRACKNUMBER=2"), 48000, 48000L * 3))
        assertEquals("Ogg", v.title)
        assertEquals(listOf("Vorbis"), v.artists)
        assertEquals(2, v.trackNumber)
        assertEquals(3000, v.durationMs)
        val o = read(TestAudio.oggOpus(listOf("title=Opus", "artist=Ses"), 312, 48000L * 2))
        assertEquals("Opus", o.title)
        assertEquals(listOf("Ses"), o.artists)
        assertEquals(2000, o.durationMs)
    }

    @Test
    fun wav() {
        val t = read(TestAudio.wavBytes(2.0, info = mapOf("INAM" to "Wav Başlık", "IART" to "Wav Sanatçı")))
        assertEquals("Wav Başlık", t.title)
        assertEquals(listOf("Wav Sanatçı"), t.artists)
        assertEquals(2000, t.durationMs)
        assertEquals(1500, read(TestAudio.wavBytes(1.5)).durationMs)
    }

    @Test
    fun covers() {
        assertEquals(".jpg", TagReader.imageExt(TestAudio.jpeg()))
        assertEquals(".png", TagReader.imageExt(TestAudio.png()))
        assertEquals(".gif", TagReader.imageExt("GIF89a".toByteArray()))
        assertEquals(".webp", TagReader.imageExt("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertNull(TagReader.imageExt("nope".toByteArray()))
        val dir = Files.createTempDirectory("covers").toFile()
        try {
            val a = TagReader.saveCover(TestAudio.jpeg(1), dir)
            val b = TagReader.saveCover(TestAudio.jpeg(1), dir) // same bytes: one file
            val c = TagReader.saveCover(TestAudio.jpeg(2), dir)
            assertEquals(a, b)
            assertTrue(a != c)
            assertTrue(a.endsWith(".jpg"))
            assertEquals(2, dir.listFiles()!!.size)
            assertEquals("", TagReader.saveCover("not an image".toByteArray(), dir))
            assertEquals("", TagReader.saveCover(null, dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noTags() {
        val t = read(ByteArray(100))
        assertEquals(LocalTags(), t)
    }
}
