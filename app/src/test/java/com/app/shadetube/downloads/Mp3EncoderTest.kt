package com.app.shadetube.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * jump3r through [Mp3Encoder]: a synthetic sine becomes a valid CBR MP3 (frame sync, MPEG-1 layer III, the asked
 * bitrate and sample rate, a LAME "Info" frame whose frame count and delay / padding give the exact input length), and
 * the encode speed on the JVM (the bar for using jump3r instead of an NDK LAME: under 1/8 real time on a desktop).
 */
class Mp3EncoderTest {

    @Test
    fun sineEncodesToValidCbrMp3() {
        for ((rate, ch, kbps) in listOf(Triple(44100, 2, 320), Triple(48000, 2, 256), Triple(44100, 1, 192))) {
            val frames = rate * 5 + 123 // 5 s and a partial MP3 frame
            val mp3 = encodeSine(rate, ch, kbps, frames, chunk = 4096)
            val info = Mp3Parse.parse(mp3)
            assertEquals("sample rate", rate, info.sampleRate)
            assertTrue("all frames ${kbps} kbps: ${info.bitrates}", info.bitrates == setOf(kbps))
            assertEquals("channels", ch, info.channels)
            assertTrue("LAME Info frame", info.infoTag)
            assertEquals("Info frame count = audio frames", info.audioFrames, info.infoFrames)
            // Exact (gapless) length: frames * 1152 - delay - padding.
            val decoded = info.audioFrames.toLong() * 1152 - info.delay - info.padding
            assertEquals("duration (samples) of $rate/$ch/$kbps", frames.toLong(), decoded)
            // And the frame-based duration is within one frame of the input.
            assertTrue(abs(info.audioFrames.toLong() * 1152 - frames) <= 1152 + info.delay + info.padding)
        }
    }

    @Test
    fun oddSampleRateIsResampled() {
        assertEquals(44100, Mp3Encoder.mp3SampleRate(22050))
        val mp3 = encodeSine(22050, 2, 192, 22050 * 2, chunk = 1000)
        val info = Mp3Parse.parse(mp3)
        assertEquals(44100, info.sampleRate)
        assertEquals(setOf(192), info.bitrates)
    }

    @Test
    fun supportedKbps() {
        assertEquals(0, Mp3Encoder.supportedKbps(0))
        assertEquals(320, Mp3Encoder.supportedKbps(320))
        assertEquals(256, Mp3Encoder.supportedKbps(250))
        assertEquals(192, Mp3Encoder.supportedKbps(128))
        assertEquals(320, Mp3Encoder.supportedKbps(999))
    }

    /** 4 minutes of 44.1 kHz stereo at 320 kbps: must encode in under 1/8 of real time (30 s) on a desktop JVM. */
    @Test
    fun encodeSpeed() {
        val rate = 44100
        val seconds = 240
        // Warm the JIT up a little first (Android's ART AOT/JIT behaves differently anyway).
        encodeSine(rate, 2, 320, rate * 5, chunk = 4096, rich = true)
        val t0 = System.nanoTime()
        // Music-like load: several tones, a sweep and white noise (a pure sine encodes far faster than music).
        val mp3 = encodeSine(rate, 2, 320, rate * seconds, chunk = 4096, keep = false, rich = true)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val ratio = ms / (seconds * 1000.0)
        println("jump3r: ${seconds}s of 44.1 kHz stereo @ 320 kbps encoded in $ms ms (${"%.3f".format(ratio)} x real time, ${"%.1f".format(1 / ratio)}x faster)")
        assertTrue("too slow: $ms ms", ratio < 1.0 / 8)
        assertTrue(mp3.isEmpty())
    }

    companion object {
        /** A two-tone sine (440 Hz left, 660 Hz right; [rich]: tones + sweep + noise) encoded in [chunk]-frame blocks. */
        fun encodeSine(rate: Int, ch: Int, kbps: Int, frames: Int, chunk: Int, keep: Boolean = true, rich: Boolean = false): ByteArray {
            val rnd = java.util.Random(7)
            val out = ByteArrayOutputStream()
            val enc = Mp3Encoder(rate, ch, kbps)
            val pcm = FloatArray(chunk * ch)
            var pos = 0
            var total = 0L
            fun put(c: Mp3Encoder.Chunk) {
                total += c.size
                if (keep) out.write(c.bytes, 0, c.size)
            }
            // Placeholder for the Info frame (patched below, like Mp3Transcoder does on the file).
            while (pos < frames) {
                val n = minOf(chunk, frames - pos)
                for (k in 0 until n) {
                    val t = (pos + k).toDouble() / rate
                    if (rich) {
                        val sweep = 0.15 * sin(2 * PI * (100 + 400 * (t % 10)) * t)
                        val tones = 0.12 * (sin(2 * PI * 110 * t) + sin(2 * PI * 554 * t) + sin(2 * PI * 1760 * t) + sin(2 * PI * 7040 * t))
                        pcm[k * ch] = (sweep + tones + 0.08 * rnd.nextGaussian()).toFloat()
                        if (ch == 2) pcm[k * ch + 1] = (tones - sweep + 0.08 * rnd.nextGaussian()).toFloat()
                    } else {
                        pcm[k * ch] = (0.5 * sin(2 * PI * 440 * t)).toFloat()
                        if (ch == 2) pcm[k * ch + 1] = (0.4 * sin(2 * PI * 660 * t)).toFloat()
                    }
                }
                put(enc.encode(pcm, 0, n))
                pos += n
            }
            put(enc.flush())
            val tag = enc.lameTagFrame()
            enc.close()
            assertTrue("LAME tag frame", tag.isNotEmpty())
            if (!keep) return ByteArray(0)
            val bytes = out.toByteArray()
            tag.copyInto(bytes, 0)
            return bytes
        }
    }
}

/** A minimal MPEG audio frame walker for the tests. */
object Mp3Parse {
    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitrates: Set<Int>,
        /** Frames after the Info frame. */
        val audioFrames: Int,
        val infoTag: Boolean,
        val infoFrames: Int,
        val delay: Int,
        val padding: Int,
        /** Offset of the first frame. */
        val start: Int,
    )

    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val RATES_V1 = intArrayOf(44100, 48000, 32000, 0)

    fun parse(b: ByteArray, from: Int = 0): Info {
        var p = from
        var rate = 0
        var ch = 0
        val rates = HashSet<Int>()
        var count = 0
        var info = false
        var infoFrames = -1
        var delay = 0
        var padding = 0
        var first = true
        while (p + 4 <= b.size) {
            val h = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            require((h ushr 21) and 0x7FF == 0x7FF) { "lost frame sync at $p (frame ${count + 1})" }
            require((h ushr 19) and 3 == 3) { "not MPEG-1 at $p" }
            require((h ushr 17) and 3 == 1) { "not layer III at $p" }
            val br = BITRATES_V1_L3[(h ushr 12) and 15]
            val sr = RATES_V1[(h ushr 10) and 3]
            val pad = (h ushr 9) and 1
            val mode = (h ushr 6) and 3
            val len = 144 * br * 1000 / sr + pad
            if (first) {
                rate = sr
                ch = if (mode == 3) 1 else 2
                val side = if (mode == 3) 17 else 32
                val tagAt = p + 4 + side
                val id = String(b, tagAt, 4, Charsets.ISO_8859_1)
                if (id == "Info" || id == "Xing") {
                    info = true
                    val flags = be32(b, tagAt + 4)
                    if (flags and 1 != 0) infoFrames = be32(b, tagAt + 8)
                    val dp = tagAt + 141
                    delay = ((b[dp].toInt() and 0xFF) shl 4) or ((b[dp + 1].toInt() and 0xFF) ushr 4)
                    padding = ((b[dp + 1].toInt() and 0x0F) shl 8) or (b[dp + 2].toInt() and 0xFF)
                    first = false
                    p += len
                    continue
                }
                first = false
            }
            rates.add(br)
            count++
            p += len
        }
        require(p == b.size) { "trailing bytes: ended at $p of ${b.size}" }
        return Info(rate, ch, rates, count, info, infoFrames, delay, padding, from)
    }

    private fun be32(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
}
