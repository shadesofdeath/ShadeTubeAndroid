package com.app.shadetube.downloads

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The SponsorBlock splicer (port of the C++ Splicer): kept / dropped frame counts, fade lengths and shape (raised cosine
 * over 8 ms on both sides of every splice), continuity of the output, independence from the block size, cut
 * normalization.
 */
class PcmSplicerTest {
    private val rate = 48000
    private val fade = (PcmSplicer.FADE_MS * rate / 1000).toInt() // 384

    /** Runs [input] (interleaved, [ch] channels) through a splicer in blocks of [block] frames. */
    private fun splice(cuts: List<Pair<Long, Long>>, input: FloatArray, ch: Int, block: Int): Pair<FloatArray, PcmSplicer> {
        val sp = PcmSplicer(PcmSplicer.normalizeCuts(cuts), rate)
        val frames = input.size / ch
        val out = FloatArray(input.size)
        var o = 0
        var pos = 0
        val tmp = FloatArray(block * ch)
        while (pos < frames) {
            val n = minOf(block, frames - pos)
            System.arraycopy(input, pos * ch, tmp, 0, n * ch) // the splicer reads from an offset-0 buffer, like decoders give
            val kept = sp.process(tmp, 0, n, ch, pos.toLong(), out, o)
            o += kept * ch
            pos += n
        }
        return out.copyOf(o) to sp
    }

    @Test
    fun curveShape() {
        assertEquals(0f, PcmSplicer.curve(0f), 1e-6f)
        assertEquals(0.5f, PcmSplicer.curve(0.5f), 1e-6f)
        assertEquals(1f, PcmSplicer.curve(1f), 1e-6f)
        assertEquals(1f, PcmSplicer.curve(2f), 1e-6f) // clamped
        var prev = -1f
        for (i in 0..100) {
            val v = PcmSplicer.curve(i / 100f)
            assertTrue(v >= prev)
            prev = v
        }
    }

    @Test
    fun noCutsIsIdentity() {
        val input = FloatArray(10_000) { sin(it * 0.01).toFloat() }
        val (out, sp) = splice(emptyList(), input, 2, 777)
        assertArrayEquals(input, out, 0f)
        assertEquals(0L, sp.droppedFrames)
        assertEquals(0, sp.segmentsHit)
    }

    @Test
    fun cutRemovesExactFramesWithFadesOnBothSides() {
        val frames = 5 * rate
        val input = FloatArray(frames) { 1f } // mono DC: the output IS the gain curve
        val (out, sp) = splice(listOf(1000L to 2000L), input, 1, 1024)
        assertEquals((frames - rate).toLong(), out.size.toLong())
        assertEquals(rate.toLong(), sp.droppedFrames)
        assertEquals(1, sp.segmentsHit)

        val start = rate // cut [48000, 96000): the splice sits between out[start - 1] and out[start]
        // Untouched before the fade-out and after the fade-in.
        for (i in 0 until start - fade) assertEquals("before fade, $i", 1f, out[i], 0f)
        for (i in start + fade - 1 until out.size) assertEquals("after fade, $i", 1f, out[i], 0f)
        // Fade-out: frame q in [start - fade, start) gets curve((start - q) / fade): 1 at the far end, ~0 at the splice.
        for (q in start - fade until start) {
            assertEquals("fade-out $q", PcmSplicer.curve((start - q).toFloat() / fade), out[q], 1e-6f)
        }
        // Fade-in: source frame e + k (k = 0..fade-1, e = cut end) gets curve((k + 1) / fade).
        for (k in 0 until fade) {
            assertEquals("fade-in $k", PcmSplicer.curve((k + 1).toFloat() / fade), out[start + k], 1e-6f)
        }
        // Attenuated frames: fade - 1 on each side (8 ms).
        val attenuated = out.count { it < 1f }
        assertEquals(2 * (fade - 1), attenuated)
        // Next to the splice, both sides are almost silent.
        assertTrue(out[start - 1] < 1e-4f && out[start] < 1e-4f)
    }

    @Test
    fun outputIsContinuousAtSplices() {
        // A loud 1 kHz stereo sine cut twice at arbitrary (non-zero-crossing) points.
        val frames = 6 * rate
        val input = FloatArray(frames * 2)
        for (i in 0 until frames) {
            input[2 * i] = (0.9 * sin(2 * PI * 1000 * i / rate + 0.3)).toFloat()
            input[2 * i + 1] = (0.9 * sin(2 * PI * 1000 * i / rate + 1.3)).toFloat()
        }
        val maxStep = 0.9 * 2 * PI * 1000 / rate * 1.01 // the sine's own steepest step
        val (out, sp) = splice(listOf(1234L to 2345L, 3001L to 4567L), input, 2, 999)
        assertEquals(2, sp.segmentsHit)
        val expectedDropped = (2345L * rate / 1000 - 1234L * rate / 1000) + (4567L * rate / 1000 - 3001L * rate / 1000)
        assertEquals(expectedDropped, sp.droppedFrames)
        assertEquals((frames - expectedDropped) * 2, out.size.toLong())
        for (c in 0..1) {
            var worst = 0.0
            for (i in 1 until out.size / 2) worst = maxOf(worst, abs(out[2 * i + c] - out[2 * (i - 1) + c]).toDouble())
            assertTrue("channel $c: max step $worst > $maxStep (a click)", worst <= maxStep)
        }
    }

    @Test
    fun blockSizeDoesNotMatter() {
        val frames = 4 * rate
        val input = FloatArray(frames * 2) { sin(it * 0.0137).toFloat() }
        val cuts = listOf(500L to 501L, 999L to 1700L, 3990L to 9000L)
        val (ref, _) = splice(cuts, input, 2, frames)
        for (block in intArrayOf(1, 7, 384, 1024, 4801)) {
            val (out, _) = splice(cuts, input, 2, block)
            assertArrayEquals("block $block", ref, out, 0f)
        }
    }

    @Test
    fun cutAtStartAndPastEnd() {
        val frames = 3 * rate
        val input = FloatArray(frames) { 1f }
        val (out, sp) = splice(listOf(-500L to 1000L, 2500L to 99_000L), input, 1, 4096)
        // [0, 1 s) and [2.5 s, end) removed; no fade-in at the very start of the file, a fade-in after the first cut.
        assertEquals(frames - rate - rate / 2, out.size)
        assertEquals(PcmSplicer.curve(1f / fade), out[0], 1e-6f)
        // The last kept frame is next to the second cut: faded out to ~0.
        assertEquals(PcmSplicer.curve(1f / fade), out[out.size - 1], 1e-6f)
        assertEquals(2, sp.segmentsHit)
        // A range entirely past the decoded audio never hits.
        val (_, sp2) = splice(listOf(10_000L to 11_000L), input, 1, 4096)
        assertEquals(0, sp2.segmentsHit)
        assertEquals(0L, sp2.droppedFrames)
    }

    @Test
    fun coveredFrames() {
        val sp = PcmSplicer(listOf(0L to 1000L, 2000L to 5000L), rate)
        assertEquals(rate.toLong() + 3L * rate, sp.coveredFrames(10L * rate))
        assertEquals(rate.toLong() + rate, sp.coveredFrames(3L * rate)) // the second range clamped to the length
    }

    @Test
    fun normalizeCuts() {
        val n = PcmSplicer.normalizeCuts(listOf(5000L to 6000L, -10L to 100L, 50L to 200L, 300L to 300L, 200L to 250L, 900L to 800L))
        assertEquals(listOf(0L to 250L, 5000L to 6000L), n)
        assertEquals(emptyList<Pair<Long, Long>>(), PcmSplicer.normalizeCuts(emptyList()))
    }
}
