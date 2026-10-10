package com.app.shadetube.downloads

import com.app.shadetube.localfiles.ReplayGain
import com.app.shadetube.localfiles.TagReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The MP3 pipeline with a fake decoder ([FakeSource]; MediaCodec can't run on the JVM): ID3 tag + valid MP3 frames +
 * Info frame in the file, the exact output length after SponsorBlock cuts, the < 10 s guard, timestamp re-sync,
 * mid-stream format changes, downmixing, progress and cancellation.
 */
class Mp3TranscoderTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("mp3").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** One segment of fake decoder output. */
    data class Seg(val frames: Int, val rate: Int, val channels: Int, val timeOffsetUs: Long = 0)

    /**
     * Emits [segs] in random block sizes (like a decoder's 1024 / 2048 / 960-frame buffers) of a sine, with
     * presentation timestamps unless [timestamps] is off. A segment's [Seg.timeOffsetUs] shifts its timestamps (a gap).
     */
    class FakeSource(
        private val segs: List<Seg>,
        override val durationUs: Long,
        private val timestamps: Boolean = true,
        seed: Long = 1,
    ) : PcmSource {
        private val rnd = Random(seed)
        private var seg = 0
        private var inSeg = 0
        private var timeUs = 0L
        private var buf = FloatArray(0)
        var reads = 0
        var closed = false

        override fun read(): PcmBlock? {
            while (seg < segs.size && inSeg >= segs[seg].frames) {
                seg++
                inSeg = 0
                if (seg < segs.size) timeUs += segs[seg].timeOffsetUs
            }
            if (seg >= segs.size) return null
            reads++
            val s = segs[seg]
            val n = minOf(intArrayOf(960, 1024, 2048, 333)[rnd.nextInt(4)], s.frames - inSeg)
            if (buf.size < n * s.channels) buf = FloatArray(n * s.channels)
            for (k in 0 until n) {
                val t = timeUs / 1e6 + k.toDouble() / s.rate
                for (c in 0 until s.channels) buf[k * s.channels + c] = (0.3 * sin(2 * PI * (330 + 110 * c) * t)).toFloat()
            }
            val block = PcmBlock(buf, n, s.channels, s.rate, if (timestamps) timeUs else -1)
            inSeg += n
            timeUs += n * 1_000_000L / s.rate
            return block
        }

        override fun close() {
            closed = true
        }
    }

    private val tag = Id3Writer.build(Id3Writer.Tags(title = "T", artist = "A", replayGainDb = -6.25f))

    private fun mp3Of(file: File): Pair<Int, Mp3Parse.Info> {
        val bytes = file.readBytes()
        assertEquals("ID3", String(bytes, 0, 3, Charsets.ISO_8859_1))
        val size = ((bytes[6].toInt() and 0x7F) shl 21) or ((bytes[7].toInt() and 0x7F) shl 14) or ((bytes[8].toInt() and 0x7F) shl 7) or (bytes[9].toInt() and 0x7F)
        val audio = 10 + size
        return audio to Mp3Parse.parse(bytes, audio)
    }

    /** Samples the MP3 decodes to (gapless: Info frame count and delay / padding). */
    private fun decodedFrames(i: Mp3Parse.Info) = i.audioFrames.toLong() * 1152 - i.delay - i.padding

    @Test
    fun plainTranscodeIsExactAndTagged() {
        val frames = 44100 * 12 + 77
        val out = File(dir, "a.mp3.part")
        val progress = ArrayList<Float>()
        val src = FakeSource(listOf(Seg(frames, 44100, 2)), durationUs = frames * 1_000_000L / 44100)
        val st = Mp3Transcoder.transcode(src, out, 320, emptyList(), tag, progress = { progress.add(it) })
        assertEquals(0, st.segmentsCut)
        assertEquals(0L, st.cutMs)
        assertEquals(st.sourceMs, st.outputMs)
        assertEquals(out.length(), st.bytes)
        val (audioStart, info) = mp3Of(out)
        assertEquals(tag.size, audioStart)
        assertEquals(44100, info.sampleRate)
        assertEquals(setOf(320), info.bitrates)
        assertTrue(info.infoTag)
        assertEquals(info.audioFrames, info.infoFrames)
        assertEquals(frames.toLong(), decodedFrames(info))
        // Progress: increasing, reaches the end.
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(progress.last() > 0.99f)
        // The app's readers: ReplayGain from TXXX, duration from the Info frame.
        assertEquals(-6.25f, ReplayGain.readTrackGainDb(out)!!, 1e-4f)
        val t = TagReader.read(out)!!
        assertEquals("T", t.title)
        // TagReader counts whole frames (encoder delay / padding included, ~40 ms); the player trims them (gapless).
        assertTrue("duration ${t.durationMs}", abs(t.durationMs - 12_002) <= 60)
    }

    @Test
    fun cutsAreSampleAccurate() {
        val rate = 48000
        val frames = rate * 30
        val out = File(dir, "b.mp3.part")
        val cuts = listOf(2_000L to 5_500L, 20_000L to 21_000L, 29_000L to 40_000L) // the last one runs past the end
        val src = FakeSource(listOf(Seg(frames, rate, 2)), durationUs = 30_000_000)
        val st = Mp3Transcoder.transcode(src, out, 256, cuts, tag)
        val dropped = (3500L + 1000L + 1000L) * rate / 1000
        assertEquals(3, st.segmentsCut)
        assertEquals(5_500L, st.cutMs)
        assertEquals(30_000L, st.sourceMs)
        assertEquals(24_500L, st.outputMs)
        val (_, info) = mp3Of(out)
        assertEquals(setOf(256), info.bitrates)
        assertEquals(frames - dropped, decodedFrames(info))
    }

    @Test
    fun cutsLeavingUnderTenSecondsAreIgnored() {
        val rate = 44100
        val frames = rate * 15
        // Leaves 9 s: ignored.
        val st = Mp3Transcoder.transcode(FakeSource(listOf(Seg(frames, rate, 2)), 15_000_000), File(dir, "c.part"), 192, listOf(0L to 6_000L), tag)
        assertEquals(0, st.segmentsCut)
        assertEquals(0L, st.cutMs)
        assertEquals(15_000L, st.outputMs)
        // Leaves 11 s: applied.
        val st2 = Mp3Transcoder.transcode(FakeSource(listOf(Seg(frames, rate, 2)), 15_000_000), File(dir, "d.part"), 192, listOf(0L to 4_000L), tag)
        assertEquals(1, st2.segmentsCut)
        assertEquals(11_000L, st2.outputMs)
        // Unknown container duration: the hint is used for the guard.
        val st3 = Mp3Transcoder.transcode(FakeSource(listOf(Seg(frames, rate, 2)), 0), File(dir, "e.part"), 192, listOf(0L to 6_000L), tag, durationHintMs = 15_000)
        assertEquals(0, st3.segmentsCut)
    }

    @Test
    fun timestampGapResyncsTheCutPositions() {
        val rate = 44100
        // 10 s, then the stream jumps 5 s ahead (a gap in the source), then 10 s more. A cut at [16 s, 18 s) of the
        // source timeline falls 1 s into the second segment.
        val segs = listOf(Seg(rate * 10, rate, 2), Seg(rate * 10, rate, 2, timeOffsetUs = 5_000_000))
        val st = Mp3Transcoder.transcode(FakeSource(segs, 25_000_000), File(dir, "f.part"), 192, listOf(16_000L to 18_000L), tag)
        assertEquals(1, st.segmentsCut)
        assertEquals(2_000L, st.cutMs)
        assertEquals(18_000L, st.outputMs)
        // Without timestamps the frame counter is the timeline: the same cut lands at 16 s of the decoded audio.
        val st2 = Mp3Transcoder.transcode(FakeSource(segs, 25_000_000, timestamps = false), File(dir, "g.part"), 192, listOf(16_000L to 18_000L), tag)
        assertEquals(2_000L, st2.cutMs)
    }

    @Test
    fun formatChangesAreConvertedToTheFirstFormat() {
        // 44.1 kHz stereo, then 22.05 kHz mono (resampled + upmixed), then 48 kHz 6-channel (resampled + downmixed).
        val segs = listOf(Seg(44100 * 4, 44100, 2), Seg(22050 * 4, 22050, 1), Seg(48000 * 4, 48000, 6))
        val out = File(dir, "h.part")
        val st = Mp3Transcoder.transcode(FakeSource(segs, 12_000_000), out, 320, emptyList(), tag)
        assertEquals(44100, st.sampleRate)
        assertEquals(2, st.channels)
        assertTrue("output ${st.outputMs} ms", abs(st.outputMs - 12_000) <= 5)
        val (_, info) = mp3Of(out)
        assertEquals(44100, info.sampleRate)
        assertEquals(2, info.channels)
        assertTrue(abs(decodedFrames(info) - 44100L * 12) <= 10)
    }

    @Test
    fun monoAndMultichannelSources() {
        val mono = Mp3Transcoder.transcode(FakeSource(listOf(Seg(48000 * 3, 48000, 1)), 3_000_000), File(dir, "m.part"), 192, emptyList(), tag)
        assertEquals(1, mono.channels)
        assertEquals(1, mp3Of(File(dir, "m.part")).second.channels)
        val surround = Mp3Transcoder.transcode(FakeSource(listOf(Seg(48000 * 3, 48000, 6)), 3_000_000), File(dir, "s.part"), 192, emptyList(), tag)
        assertEquals(2, surround.channels)
    }

    @Test
    fun cancellationStopsPromptly() {
        val src = FakeSource(listOf(Seg(44100 * 600, 44100, 2)), 600_000_000)
        try {
            Mp3Transcoder.transcode(src, File(dir, "x.part"), 320, emptyList(), tag, cancelled = { src.reads >= 20 })
            fail("not cancelled")
        } catch (e: CancellationException) {
            assertEquals(20, src.reads)
        }
    }

    @Test
    fun emptySourceIsADecodeError() {
        try {
            Mp3Transcoder.transcode(FakeSource(emptyList(), 0), File(dir, "y.part"), 320, emptyList(), tag)
            fail("no error")
        } catch (e: TranscodeException) {
            assertEquals(TranscodeException.Kind.Decode, e.kind)
        }
    }

    @Test
    fun formatAdapterResamplesSmoothly() {
        val a = Mp3Transcoder.FormatAdapter(48000, 1)
        // A 1 kHz sine at 24 kHz in uneven blocks → 48 kHz: twice the frames, still a clean sine (no seams).
        val total = 24000
        var t = 0
        val outAll = ArrayList<Float>()
        val rnd = Random(3)
        while (t < total) {
            val n = minOf(1 + rnd.nextInt(700), total - t)
            val s = FloatArray(n) { sin(2 * PI * 1000 * (t + it) / 24000.0).toFloat() }
            val got = a.convert(PcmBlock(s, n, 1, 24000))
            for (i in 0 until got) outAll.add(a.data[i])
            t += n
        }
        assertTrue("frames ${outAll.size}", abs(outAll.size - 48000) <= 2)
        for (i in 0 until outAll.size) {
            val expect = sin(2 * PI * 1000 * i / 48000.0)
            assertTrue("sample $i: ${outAll[i]} vs $expect", abs(outAll[i] - expect) < 0.02)
        }
    }

    @Test
    fun frameLength() {
        // MPEG-1 L3 320 kbps 44.1 kHz, no padding: 1044 bytes; 48 kHz: 960; 128 kbps 44.1 kHz with padding: 418.
        assertEquals(1044, Mp3Transcoder.frameLength(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xE0.toByte(), 0)))
        assertEquals(960, Mp3Transcoder.frameLength(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xE4.toByte(), 0)))
        assertEquals(418, Mp3Transcoder.frameLength(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x92.toByte(), 0)))
        assertEquals(0, Mp3Transcoder.frameLength(byteArrayOf(0x49, 0x44, 0x33, 3)))
    }
}
