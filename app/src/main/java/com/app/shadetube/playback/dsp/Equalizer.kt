package com.app.shadetube.playback.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 10-band graphic equalizer (port of audio/Equalizer; also used by the settings page for its response curve).
 *
 * Bands: peaking biquads (RBJ cookbook, Q ~ one octave) at 31 Hz .. 16 kHz, run in double precision on interleaved
 * float PCM. A preamp is applied first, together with an automatic headroom cut equal to the largest boost of the
 * combined response, so a boosted curve never pushes a full-scale master into the limiter. Flat bands cost nothing.
 *
 * Threading: an [Equalizer] instance belongs to one thread (the audio processing thread). The functions of
 * [EqMath] are pure.
 */
const val EQ_BANDS = 10
val EQ_FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

/** Per band, +/-. */
const val EQ_MAX_GAIN_DB = 12f

data class EqSettings(
    val enabled: Boolean = false,
    /** User preamp (-12 .. +12), before the automatic headroom. */
    val preampDb: Float = 0f,
    /** Per band, -12 .. +12 (always [EQ_BANDS] values). */
    val gainsDb: List<Float> = List(EQ_BANDS) { 0f },
) {
    /** Every band and the preamp at 0. */
    val flat: Boolean get() = preampDb == 0f && gainsDb.all { it == 0f }

    companion object {
        /** From Settings (eqGains may be empty = flat). */
        fun of(enabled: Boolean, preampDb: Float, gains: List<Float>) =
            EqSettings(enabled, preampDb, List(EQ_BANDS) { gains.getOrElse(it) { 0f } })
    }
}

/**
 * [id] is the stable id stored in Settings.eqPreset ("flat", "bass", ...); the settings page owns the (translated)
 * names.
 */
data class EqPreset(val id: String, val gainsDb: List<Float>)

object EqMath {
    /** ~1 octave between the -3 dB points: neighbouring bands blend smoothly. */
    const val Q = 1.41

    //                                31   62  125  250  500   1k   2k   4k   8k  16k
    val presets: List<EqPreset> = listOf(
        EqPreset("flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("bass", listOf(6f, 5f, 4f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("bass-cut", listOf(-6f, -5f, -4f, -2f, -0.5f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("treble", listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 5f, 6f)),
        EqPreset("treble-cut", listOf(0f, 0f, 0f, 0f, 0f, -0.5f, -1.5f, -3f, -5f, -6f)),
        EqPreset("vocal", listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3.5f, 2.5f, 1f, 0f)),
        EqPreset("pop", listOf(-1f, 0f, 1.5f, 3f, 3.5f, 2f, 0f, -0.5f, -0.5f, -1f)),
        EqPreset("rock", listOf(4.5f, 3.5f, 2f, 0f, -1f, -1f, 0.5f, 2f, 3f, 3.5f)),
        EqPreset("hiphop", listOf(5f, 4.5f, 2f, 3f, -0.5f, -1f, 1f, -0.5f, 2f, 3f)),
        EqPreset("electronic", listOf(5f, 4f, 1.5f, 0f, -2f, 1.5f, 0.5f, 1.5f, 4f, 5f)),
        EqPreset("jazz", listOf(3f, 2f, 1f, 2f, -1.5f, -1.5f, 0f, 1f, 2f, 3f)),
        EqPreset("classical", listOf(3f, 2.5f, 1.5f, 1f, 0f, 0f, 0f, 1f, 2f, 2.5f)),
        EqPreset("acoustic", listOf(3f, 3f, 2f, 1f, 1.5f, 1f, 2f, 2.5f, 2f, 1f)),
        EqPreset("loudness", listOf(4f, 3f, 1f, 0f, -1f, -0.5f, 0f, 1f, 3f, 3.5f)),
        EqPreset("speech", listOf(-4f, -3f, -1f, 1f, 3f, 4f, 4f, 3f, 1f, -1f)),
    )

    fun findPreset(id: String): EqPreset? = presets.firstOrNull { it.id == id }

    /** Normalised (a0 = 1) biquad: b0, b1, b2, a1, a2. */
    class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    fun peaking(freq: Double, gainDb: Double, rate: Double): Biquad {
        val f = min(freq, rate * 0.45) // stay clear of Nyquist (16 kHz at 32 kHz output)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f / rate
        val alpha = sin(w0) / (2 * Q)
        val cosw = cos(w0)
        val a0 = 1 + alpha / a
        return Biquad((1 + alpha * a) / a0, (-2 * cosw) / a0, (1 - alpha * a) / a0, (-2 * cosw) / a0, (1 - alpha / a) / a0)
    }

    fun magnitudeDb(q: Biquad, freq: Double, rate: Double): Double {
        val w = 2 * PI * freq / rate
        // z^-1 = e^{-jw}, z^-2 = e^{-2jw}
        val c1 = cos(-w)
        val s1 = sin(-w)
        val c2 = cos(-2 * w)
        val s2 = sin(-2 * w)
        val nr = q.b0 + q.b1 * c1 + q.b2 * c2
        val ni = q.b1 * s1 + q.b2 * s2
        val dr = 1.0 + q.a1 * c1 + q.a2 * c2
        val di = q.a1 * s1 + q.a2 * s2
        val mag = hypot(nr, ni) / hypot(dr, di)
        return 20 * log10(max(mag, 1e-9))
    }

    /** Magnitude response of the band filters at [freqHz] in dB (preamp and headroom not included). */
    fun responseDb(gainsDb: List<Float>, freqHz: Double, sampleRate: Double = 48000.0): Double {
        var db = 0.0
        for (b in 0 until EQ_BANDS) {
            val g = gainsDb.getOrElse(b) { 0f }
            if (g != 0f) db += magnitudeDb(peaking(EQ_FREQUENCIES[b].toDouble(), g.toDouble(), sampleRate), freqHz, sampleRate)
        }
        return db
    }

    /** The automatic headroom: the largest boost of the combined response over the audible range (>= 0 dB). */
    fun headroomDb(gainsDb: List<Float>, sampleRate: Double = 48000.0): Double {
        var peak = 0.0
        // 1/12-octave grid from 20 Hz to 20 kHz (or Nyquist): fine enough to catch the summit of two neighbouring boosts.
        val top = min(20000.0, sampleRate * 0.49)
        var f = 20.0
        while (f <= top) {
            peak = max(peak, responseDb(gainsDb, f, sampleRate))
            f *= 1.0594630943592953
        }
        return peak
    }

}

class Equalizer {
    private val b0 = DoubleArray(EQ_BANDS)
    private val b1 = DoubleArray(EQ_BANDS)
    private val b2 = DoubleArray(EQ_BANDS)
    private val a1 = DoubleArray(EQ_BANDS)
    private val a2 = DoubleArray(EQ_BANDS)
    private val bandOn = BooleanArray(EQ_BANDS)

    /** Transposed direct form II state: [band * channels + channel] * 2 (+0 = z1, +1 = z2). */
    private var state = DoubleArray(0)
    private var rate = 0
    private var channels = 0

    /** Preamp - headroom, linear. */
    private var gain = 1f

    var active = false
        private set

    /**
     * Recomputes the coefficients; filter state is kept when only the gains change (no click on a slider drag), reset
     * when the format changes.
     */
    fun configure(s: EqSettings, sampleRate: Int, channelCount: Int) {
        val formatChanged = sampleRate != rate || channelCount != channels
        rate = sampleRate
        channels = channelCount
        active = s.enabled && !s.flat && sampleRate > 0 && channelCount > 0
        if (!active) {
            reset()
            return
        }
        val fresh = formatChanged || state.size != EQ_BANDS * channels * 2
        if (fresh) state = DoubleArray(EQ_BANDS * channels * 2)
        for (b in 0 until EQ_BANDS) {
            val g = s.gainsDb.getOrElse(b) { 0f }.coerceIn(-EQ_MAX_GAIN_DB, EQ_MAX_GAIN_DB)
            val on = g != 0f
            if (!on && bandOn[b] && !fresh) { // a band switched off: forget its memory
                for (c in 0 until channels) {
                    state[(b * channels + c) * 2] = 0.0
                    state[(b * channels + c) * 2 + 1] = 0.0
                }
            }
            bandOn[b] = on
            val q = EqMath.peaking(EQ_FREQUENCIES[b].toDouble(), g.toDouble(), sampleRate.toDouble())
            b0[b] = q.b0
            b1[b] = q.b1
            b2[b] = q.b2
            a1[b] = q.a1
            a2[b] = q.a2
        }
        val headroom = EqMath.headroomDb(s.gainsDb, sampleRate.toDouble())
        gain = 10.0.pow((s.preampDb.coerceIn(-EQ_MAX_GAIN_DB, EQ_MAX_GAIN_DB) - headroom) / 20.0).toFloat()
    }

    /** Clears the filter memory. */
    fun reset() {
        state.fill(0.0)
    }

    /** In place on interleaved float PCM; no-op when inactive. */
    fun process(p: FloatArray, frames: Int, offset: Int = 0) {
        if (!active || frames == 0) return
        val ch = channels
        for (b in 0 until EQ_BANDS) {
            if (!bandOn[b]) continue
            val kb0 = b0[b]
            val kb1 = b1[b]
            val kb2 = b2[b]
            val ka1 = a1[b]
            val ka2 = a2[b]
            for (c in 0 until ch) {
                val si = (b * ch + c) * 2
                var z1 = state[si]
                var z2 = state[si + 1]
                var x = offset + c
                for (i in 0 until frames) {
                    val input = p[x].toDouble()
                    val out = kb0 * input + z1
                    z1 = kb1 * input - ka1 * out + z2
                    z2 = kb2 * input - ka2 * out
                    p[x] = out.toFloat()
                    x += ch
                }
                // Denormals after long silence cost a lot of CPU: flush the tiny tail.
                if (abs(z1) < 1e-20) z1 = 0.0
                if (abs(z2) < 1e-20) z2 = 0.0
                state[si] = z1
                state[si + 1] = z2
            }
        }
        if (gain != 1f) {
            val end = offset + frames * ch
            for (i in offset until end) p[i] *= gain
        }
    }
}
