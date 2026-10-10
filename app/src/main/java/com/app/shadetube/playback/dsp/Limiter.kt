package com.app.shadetube.playback.dsp

import kotlin.math.abs
import kotlin.math.max

/**
 * Look-ahead peak limiter for the output (port of audio/Limiter).
 *
 * The last stage: whatever the equalizer, a normalisation boost or a hot master push over the ceiling (-0.3 dBFS) is
 * turned down smoothly instead of clipped. Design: the gain each frame needs is min-filtered over the look-ahead window
 * and then averaged over the same window, and the audio is delayed by that window, so the gain is already down when a
 * peak comes out (no overshoot) and never jumps (no clicks); it recovers over ~150 ms. Audio that stays under the
 * ceiling passes unchanged, 5 ms late.
 *
 * Threading: one instance per thread. O(1) per frame.
 */
class Limiter {
    companion object {
        /** -0.3 dBFS. */
        const val CEILING = 0.966f
        const val LOOKAHEAD_MS = 5f
        const val RELEASE_MS = 150f
    }

    private var rate = 0
    private var channels = 0

    /** Look-ahead length in frames. */
    var latencyFrames = 0
        private set

    /** lookahead frames, interleaved, circular. */
    private var delay = FloatArray(0)
    private var pos = 0

    // Sliding minimum: a monotonic deque of (frame index, required gain) with increasing gains, as a ring.
    private var mqFrame = LongArray(0)
    private var mqGain = FloatArray(0)
    private var mqHead = 0
    private var mqSize = 0

    /** The last lookahead min-filtered gains. */
    private var boxRing = FloatArray(0)
    private var boxSum = 0.0
    private var boxPos = 0
    private var frame = 0L

    /** Min-filtered gain after the release slew. */
    private var release = 1f
    private var releaseStep = 0f

    /** Current reduction (0 = none), for tests / meters. */
    val gainReduction: Float get() = 1f - release

    /** Sets the format; clears the delay line when it changes. */
    fun configure(sampleRate: Int, channelCount: Int) {
        if (sampleRate == rate && channelCount == channels && delay.isNotEmpty()) return
        rate = sampleRate
        channels = channelCount
        latencyFrames = max(1, (sampleRate * LOOKAHEAD_MS / 1000f).toInt())
        releaseStep = 1f / (RELEASE_MS / 1000f * max(1, sampleRate).toFloat())
        delay = FloatArray(latencyFrames * channelCount)
        boxRing = FloatArray(latencyFrames) { 1f }
        mqFrame = LongArray(latencyFrames + 2)
        mqGain = FloatArray(latencyFrames + 2)
        reset()
    }

    /** Clears the delay line and the gain state (the output restarts from silence). */
    fun reset() {
        delay.fill(0f)
        boxRing.fill(1f)
        boxSum = latencyFrames.toDouble()
        boxPos = 0
        pos = 0
        mqHead = 0
        mqSize = 0
        frame = 0
        release = 1f
    }

    private fun mqBackIndex() = (mqHead + mqSize - 1) % mqFrame.size

    /** In place: out[i] = in[i - lookahead] * gain. Frames of silence flush the delay line. */
    fun process(p: FloatArray, frames: Int, offset: Int = 0) {
        if (delay.isEmpty() || channels == 0) return
        val ch = channels
        val cap = mqFrame.size
        for (f in 0 until frames) {
            val s = offset + f * ch
            // Gain this incoming frame needs to stay under the ceiling.
            var peak = 0f
            for (c in 0 until ch) peak = max(peak, abs(p[s + c]))
            val need = if (peak > CEILING) CEILING / peak else 1f
            // Sliding minimum over the last lookahead + 1 frames (monotonic queue).
            while (mqSize > 0 && mqGain[mqBackIndex()] >= need) mqSize--
            val back = (mqHead + mqSize) % cap
            mqFrame[back] = frame
            mqGain[back] = need
            mqSize++
            while (mqFrame[mqHead] + latencyFrames < frame) {
                mqHead = (mqHead + 1) % cap
                mqSize--
            }
            val minGain = mqGain[mqHead]
            // Release: rise slowly, fall at once (the averaging below turns the fall into a ramp over the window).
            release = minOf(minGain, release + releaseStep)
            // Moving average over the window.
            boxSum += release - boxRing[boxPos]
            boxRing[boxPos] = release
            boxPos = (boxPos + 1) % latencyFrames
            val gain = (boxSum / latencyFrames).toFloat()
            // Swap the frame with the delayed one and apply the gain.
            val d = pos * ch
            for (c in 0 until ch) {
                val input = p[s + c]
                p[s + c] = (delay[d + c] * gain).coerceIn(-1f, 1f)
                delay[d + c] = input
            }
            pos = (pos + 1) % latencyFrames
            frame++
        }
    }
}
