package com.app.shadetube.localfiles

import com.app.shadetube.localfiles.TestAudio.id3
import com.app.shadetube.localfiles.TestAudio.txxx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Port of the ReplayGain part of tests/audiodsp: the gain parser, ID3 TXXX (v2.2-2.4, UTF-16, unsync), FLAC, MP4, Ogg. */
class ReplayGainTest {
    private fun near(expected: Double, actual: Float?) {
        assertNotNull(actual)
        assertEquals(expected, actual!!.toDouble(), 1e-4)
    }

    @Test
    fun parseGain() {
        near(-6.54, ReplayGain.parseGain("-6.54 dB"))
        near(1.2, ReplayGain.parseGain("+1.20 dB"))
        near(-6.5, ReplayGain.parseGain("-6,5dB"))
        near(3.0, ReplayGain.parseGain(" 3 "))
        assertNull(ReplayGain.parseGain(""))
        assertNull(ReplayGain.parseGain("dB"))
        assertNull(ReplayGain.parseGain("-6.5 LUFS"))
        assertNull(ReplayGain.parseGain("99 dB"))
    }

    @Test
    fun id3() {
        val cover = ByteArray(200000) { 0xCC.toByte() } // a cover before the gain frame (seeked over)
        val apic = TestAudio.b().u8(0).ascii("image/jpeg").u8(0).u8(3).u8(0).bytes(cover).get()
        val v3 = id3(
            3,
            listOf(
                "TIT2" to byteArrayOf(0, 'X'.code.toByte()), "APIC" to apic,
                "TXXX" to txxx(0, "replaygain_album_gain", "-9.00 dB"),
                "TXXX" to txxx(0, "REPLAYGAIN_TRACK_GAIN", "-7.25 dB"),
            ),
        )
        near(-7.25, ReplayGain.fromId3(v3))
        near(2.1, ReplayGain.fromId3(id3(3, listOf("TXXX" to txxx(1, "REPLAYGAIN_TRACK_GAIN", "+2.10 dB")))))
        near(-3.0, ReplayGain.fromId3(id3(4, listOf("TXXX" to txxx(3, "replaygain_track_gain", "-3.00 dB")))))
        near(-1.5, ReplayGain.fromId3(id3(2, listOf("TXX" to txxx(0, "REPLAYGAIN_TRACK_GAIN", "-1.5 dB")))))
        assertNull(ReplayGain.fromId3(id3(3, listOf("TXXX" to txxx(0, "REPLAYGAIN_ALBUM_GAIN", "-4 dB")))))

        // Tag-wide unsynchronisation (v2.3): an FF byte in a frame is followed by an inserted 00.
        val body = txxx(0, "REPLAYGAIN_TRACK_GAIN", "-5.50 dB")
        val frame = TestAudio.b().ascii("TXXX").be32(body.size.toLong()).u8(0).u8(0).bytes(body).get()
        val pre = TestAudio.b().ascii("PRIV").be32(4).u8(0).u8(0).u8(0xFF).u8(0xFF).u8(0xFF).u8(0xFF).get()
        val raw = TestAudio.b().apply {
            for (x in pre) {
                u8(x.toInt())
                if (x == 0xFF.toByte()) u8(0)
            }
            bytes(frame)
        }.get()
        val tag = TestAudio.b().ascii("ID3").u8(3).u8(0).u8(0x80).syncsafe(raw.size).bytes(raw).get()
        near(-5.5, ReplayGain.fromId3(tag))
    }

    @Test
    fun flacMp4Ogg() {
        near(-8.13, ReplayGain.fromFlac(TestAudio.flac(listOf("TITLE=x", "REPLAYGAIN_TRACK_PEAK=0.98", "REPLAYGAIN_TRACK_GAIN=-8.13 dB"))))
        assertNull(ReplayGain.fromFlac(TestAudio.flac(listOf("TITLE=x"))))
        near(-4.44, ReplayGain.fromMp4(TestAudio.mp4(listOf("©nam" to TestAudio.mp4Text("Title")), freeform = "replaygain_track_gain" to "-4.44 dB")))
        assertNull(ReplayGain.fromMp4(TestAudio.mp4(emptyList(), freeform = "iTunNORM" to " 00000A")))
        near(-2.5, ReplayGain.fromOgg(TestAudio.oggVorbis(listOf("REPLAYGAIN_TRACK_GAIN=-2.50 dB"), 44100, 44100)))
        near(1.0, ReplayGain.fromOgg(TestAudio.oggOpus(listOf("replaygain_track_gain=+1.00 dB"), 312, 48000)))
    }

    @Test
    fun filesOnDisk() {
        val dir: File = Files.createTempDirectory("rg").toFile()
        try {
            val mp3 = File(dir, "a.mp3").apply { writeBytes(id3(3, listOf("TXXX" to txxx(0, "REPLAYGAIN_TRACK_GAIN", "-7.25 dB"))) + TestAudio.mp3Frames(3)) }
            near(-7.25, ReplayGain.readTrackGainDb(mp3))
            val flac = File(dir, "b.flac").apply { writeBytes(TestAudio.flac(listOf("REPLAYGAIN_TRACK_GAIN=-8.13 dB"))) }
            near(-8.13, ReplayGain.readTrackGainDb(flac))
            assertNull(ReplayGain.readTrackGainDb(File(dir, "missing.mp3")))
            // Through the opener (what the player calls).
            val f = LocalFile.of(LocalEntry(path = mp3.invariantSeparatorsPath, name = "a.mp3"))
            near(-7.25, PathFileOpener().replayGainDb(f))
        } finally {
            dir.deleteRecursively()
        }
    }
}
