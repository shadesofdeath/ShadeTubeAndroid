package com.app.shadetube.playback

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Settings
import com.app.shadetube.playback.dsp.EQ_BANDS
import com.app.shadetube.playback.dsp.EQ_FREQUENCIES
import com.app.shadetube.playback.dsp.EQ_MAX_GAIN_DB
import com.app.shadetube.playback.dsp.EqMath
import com.app.shadetube.playback.dsp.EqSettings
import com.app.shadetube.playback.dsp.Equalizer
import com.app.shadetube.playback.dsp.Limiter
import com.app.shadetube.playback.engine.GoogleVideoDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

/**
 * Port of tests/audiodsp (equalizer, limiter) plus the loudness gains, the crossfade decision and the googlevideo
 * range helpers. (ReplayGain tag parsing lives in localfiles; time stretch and output devices are ExoPlayer's.)
 */
class AudioDspTest {
    private fun approx(a: Double, b: Double, tol: Double) = abs(a - b) <= tol

    /** RMS in dB of a stereo buffer after skipping the first [skip] frames (filter settling). */
    private fun rmsDb(buf: FloatArray, skip: Int): Double {
        var sum = 0.0
        var n = 0
        for (i in skip * 2 until buf.size) {
            sum += buf[i].toDouble() * buf[i]
            n++
        }
        return 10 * log10(sum / maxOf(1, n))
    }

    private fun sine(freq: Double, rate: Int, frames: Int, amp: Float = 0.25f) = FloatArray(frames * 2).also { v ->
        for (i in 0 until frames) {
            val x = (amp * sin(2 * PI * freq * i / rate)).toFloat()
            v[i * 2] = x
            v[i * 2 + 1] = x
        }
    }

    private fun gains(vararg pairs: Pair<Int, Float>) = MutableList(EQ_BANDS) { 0f }.also { g -> for ((b, v) in pairs) g[b] = v }

    @Test
    fun equalizerResponse() {
        val g = gains(5 to 6f) // 1 kHz
        assertTrue(approx(EqMath.responseDb(g, 1000.0, 48000.0), 6.0, 0.05))
        assertTrue(approx(EqMath.responseDb(g, 100.0, 48000.0), 0.0, 0.3)) // far from the band
        assertTrue(approx(EqMath.headroomDb(g, 48000.0), 6.0, 0.1))
        val cut = gains(2 to -9f)
        assertTrue(approx(EqMath.responseDb(cut, 125.0, 44100.0), -9.0, 0.05))
        assertTrue(EqMath.headroomDb(cut, 44100.0) < 0.1) // cuts need no headroom
        val two = gains(3 to 6f, 4 to 6f) // neighbours add up between them
        assertTrue(EqMath.headroomDb(two, 48000.0) > 6.5)
        for (b in 0 until EQ_BANDS) { // each band centre, alone
            val r = EqMath.responseDb(gains(b to 4f), EQ_FREQUENCIES[b].toDouble(), 44100.0)
            assertTrue("band $b centre $r dB", approx(r, 4.0, 0.1))
        }
    }

    @Test
    fun equalizerProcessing() {
        // A +6 dB band on a 1 kHz sine, with the automatic headroom (-6 dB): the level comes out unchanged.
        val eq = Equalizer()
        val s = EqSettings(enabled = true, gainsDb = gains(5 to 6f))
        eq.configure(s, 48000, 2)
        assertTrue(eq.active)
        var buf = sine(1000.0, 48000, 48000)
        val before = rmsDb(buf, 4800)
        eq.process(buf, 48000)
        assertTrue(approx(rmsDb(buf, 4800) - before, 0.0, 0.2))
        // ... and a sine far away drops by the headroom.
        eq.reset()
        buf = sine(60.0, 48000, 48000)
        val lowBefore = rmsDb(buf, 9600)
        eq.process(buf, 48000)
        assertTrue(approx(rmsDb(buf, 9600) - lowBefore, -6.0, 0.4))

        // Flat / disabled: inactive, untouched samples.
        val flat = Equalizer()
        flat.configure(EqSettings(enabled = true), 44100, 2)
        assertFalse(flat.active)
        val same = sine(440.0, 44100, 1000)
        val copy = same.copyOf()
        flat.process(same, 1000)
        assertTrue(same.contentEquals(copy))
        flat.configure(s.copy(enabled = false), 44100, 2)
        assertFalse(flat.active)

        // Extremes on noise stay finite; a sample-rate change resets cleanly.
        val wild = EqSettings(enabled = true, preampDb = 12f, gainsDb = List(EQ_BANDS) { if (it % 2 == 1) 12f else -12f })
        val w = Equalizer()
        val rng = Random(7)
        var finite = true
        for (rate in intArrayOf(44100, 48000, 32000)) {
            w.configure(wild, rate, 2)
            val n = FloatArray(rate * 2) { rng.nextFloat() * 2 - 1 }
            w.process(n, rate)
            for (x in n) finite = finite && x.isFinite() && abs(x) < 100
        }
        assertTrue(finite)
    }

    @Test
    fun presets() {
        assertTrue(EqMath.presets.all { p -> p.gainsDb.size == EQ_BANDS && p.gainsDb.all { it >= -EQ_MAX_GAIN_DB && it <= EQ_MAX_GAIN_DB } })
        assertEquals(EqMath.presets.size, EqMath.presets.map { it.id }.toSet().size)
        assertNotNull(EqMath.findPreset("flat"))
        assertNotNull(EqMath.findPreset("rock"))
        assertNull(EqMath.findPreset("nope"))
        assertTrue(EqSettings(gainsDb = EqMath.findPreset("flat")!!.gainsDb).flat)
        // Settings without gains = flat.
        assertTrue(EqSettings.of(true, 0f, emptyList()).flat)
    }

    @Test
    fun limiter() {
        val rate = 48000
        val lim = Limiter()
        lim.configure(rate, 2)
        val l = lim.latencyFrames
        assertEquals(240, l) // 5 ms
        // Under the ceiling: bit-exact, L frames late.
        val quiet = sine(440.0, rate, 4800, 0.5f)
        val original = quiet.copyOf()
        lim.process(quiet, 4800)
        var exact = true
        for (i in l until 4800) exact = exact && quiet[i * 2] == original[(i - l) * 2]
        assertTrue(exact)
        assertEquals(0f, lim.gainReduction)
        // A sine at 2x full scale: never above the ceiling (+ float slack), processed in odd-sized blocks.
        lim.reset()
        val loud = sine(1000.0, rate, rate, 2f)
        var off = 0
        while (off < loud.size / 2) {
            val n = minOf(441, loud.size / 2 - off)
            lim.process(loud, n, off * 2)
            off += n
        }
        var peak = 0f
        for (x in loud) peak = maxOf(peak, abs(x))
        assertTrue(peak <= Limiter.CEILING + 1e-4f)
        assertTrue(peak > Limiter.CEILING - 0.02f) // it limits, it doesn't just attenuate
        // A lone spike in quiet audio: capped.
        lim.reset()
        val spiky = sine(200.0, rate, 9600, 0.2f)
        spiky[4000 * 2] = 3f
        spiky[4000 * 2 + 1] = 3f
        lim.process(spiky, 9600)
        peak = 0f
        for (x in spiky) peak = maxOf(peak, abs(x))
        assertTrue(peak <= Limiter.CEILING + 1e-4f)
        // After the loud part, silence: the gain has recovered.
        val after = FloatArray(rate * 2)
        lim.process(after, rate)
        assertTrue(lim.gainReduction < 0.01f)
    }

    @Test
    fun loudnessGains() {
        val normal = Settings(normalizeVolume = true, loudnessTarget = -14)
        assertEquals(4f, Loudness.youtubeGainDb(normal, -10.0)) // +10 capped at +4
        assertEquals(-6f, Loudness.youtubeGainDb(normal, 6.0))
        assertEquals(-20f, Loudness.youtubeGainDb(normal, 40.0)) // cuts down to -20
        assertEquals(0f, Loudness.youtubeGainDb(normal, null)) // unknown loudness: no gain
        assertEquals(0f, Loudness.youtubeGainDb(normal.copy(normalizeVolume = false), -10.0))
        val loud = Settings(normalizeVolume = true, loudnessTarget = -11)
        assertEquals(8f, Loudness.youtubeGainDb(loud, -10.0)) // +13 capped at +8 at the loud target
        val quiet = Settings(normalizeVolume = true, loudnessTarget = -19)
        assertEquals(-7f, Loudness.youtubeGainDb(quiet, 2.0))
        // Local files: ReplayGain (to -18 LUFS) + target + 18, boost capped; no tag = no gain.
        val (base, cap) = Loudness.localBase(normal)!!
        assertEquals(4f, base)
        assertEquals(4f, cap)
        assertEquals(-3.25f, Loudness.replayGainDb(-7.25f, base, cap))
        assertEquals(4f, Loudness.replayGainDb(2f, base, cap))
        assertEquals(0f, Loudness.replayGainDb(null, base, cap))
        assertNull(Loudness.localBase(normal.copy(normalizeVolume = false)))
    }

    @Test
    fun crossfadeDecision() {
        val one: (Track) -> Float = { 1f }
        val a = Track(id = "spotify:track:a", name = "A")
        val b = Track(id = "spotify:track:b", name = "B")
        assertEquals(5000, Crossfade.lengthMs(5, a, b, false, one))
        assertEquals(0, Crossfade.lengthMs(0, a, b, false, one))
        assertEquals(0, Crossfade.lengthMs(5, a, b, true, one)) // repeat one
        assertEquals(0, Crossfade.lengthMs(5, null, b, false, one))
        // An album playing in order stays gapless; out of order it fades.
        val album = AlbumRef("al", "Album")
        val a1 = a.copy(album = album, trackNumber = 1)
        val a2 = b.copy(album = album, trackNumber = 2)
        val a5 = b.copy(album = album, trackNumber = 5)
        assertEquals(0, Crossfade.lengthMs(5, a1, a2, false, one))
        assertEquals(5000, Crossfade.lengthMs(5, a1, a5, false, one))
        assertEquals(0, Crossfade.lengthMs(5, a1, a5.copy(trackNumber = 0), false, one)) // unknown numbers: same album
        // Podcasts never; any speed but 1x never.
        assertEquals(0, Crossfade.lengthMs(5, a, Track(id = "podcast:x", name = "E"), false, one))
        assertEquals(0, Crossfade.lengthMs(5, a, b, false) { if (it.id == b.id) 1.5f else 1f })

        // Timing (AudioEngine::maybeStartCrossfade).
        assertEquals(Crossfade.Start.NotYet, Crossfade.startDecision(10_000, 0, 5000))
        assertEquals(Crossfade.Start.Now(4000, false), Crossfade.startDecision(4000, 0, 5000))
        assertEquals(Crossfade.Start.Gapless, Crossfade.startDecision(150, 0, 5000))
        // A silent ending hands over at once with a 0.5 s fade (within the last 20 s, after 1.5 s of silence).
        assertEquals(Crossfade.Start.Now(500, true), Crossfade.startDecision(15_000, 1600, 5000))
        assertEquals(Crossfade.Start.NotYet, Crossfade.startDecision(25_000, 5000, 5000))
        assertEquals(Crossfade.Start.NotYet, Crossfade.startDecision(15_000, 1000, 5000))
        assertEquals(Crossfade.Start.Now(300, true), Crossfade.startDecision(300, 2000, 5000))
        val (gIn, gOut) = Crossfade.equalPower(0.5)
        assertTrue(approx((gIn * gIn + gOut * gOut).toDouble(), 1.0, 1e-6)) // equal power
        assertEquals(0f, Crossfade.equalPower(0.0).first)
        assertEquals(1f, Crossfade.equalPower(1.0).first)
    }

    @Test
    fun googleVideoRanges() {
        assertEquals(
            "https://x.googlevideo.com/videoplayback?id=1&clen=99&range=0-1048575",
            GoogleVideoDataSource.withRangeParam("https://x.googlevideo.com/videoplayback?id=1&range=5-9&clen=99", 0, 1048575),
        )
        assertEquals("https://h/p?range=10-20", GoogleVideoDataSource.withRangeParam("https://h/p", 10, 20))
        assertEquals("https://h/p?a=1&range=1-2", GoogleVideoDataSource.withRangeParam("https://h/p?a=1&", 1, 2))
        assertEquals(12345L, GoogleVideoDataSource.contentLengthFromUrl("https://h/p?id=1&clen=12345&x=2"))
        assertEquals(77L, GoogleVideoDataSource.contentLengthFromUrl("https://h/p?clen=77"))
        assertEquals(-1L, GoogleVideoDataSource.contentLengthFromUrl("https://h/p?id=1"))
        assertEquals(1L shl 20, GoogleVideoDataSource.CHUNK)
    }
}
