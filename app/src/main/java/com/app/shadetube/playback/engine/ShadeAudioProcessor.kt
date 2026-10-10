package com.app.shadetube.playback.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.app.shadetube.playback.Crossfade
import com.app.shadetube.playback.dsp.EqSettings
import com.app.shadetube.playback.dsp.Equalizer
import com.app.shadetube.playback.dsp.Limiter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow

/**
 * Settings shared by every deck's processor: the equalizer as a versioned snapshot (picked up at the next block, like
 * the C++ engine's setEqualizer) and the loudness gain of each load (by engine tag).
 */
class DspControl {
    class EqSnapshot(val version: Long, val settings: EqSettings)

    val eq = AtomicReference(EqSnapshot(0, EqSettings()))

    /** tag -> loudness gain (dB). A tag without an entry plays at 0 dB until its gain arrives (ReplayGain read). */
    val gains = ConcurrentHashMap<Long, Float>()

    fun setEqualizer(s: EqSettings) {
        val cur = eq.get()
        if (cur.settings == s) return
        eq.set(EqSnapshot(cur.version + 1, s))
    }
}

/**
 * The engine's DSP as one Media3 audio processor, first in the sink's chain (before the speed stage, so it sees media
 * frames): loudness gain of the playing item (with a ramp at item changes), equalizer, look-ahead limiter; plus the
 * smart-crossfade measurements (trailing silence) and the incoming track's leading-silence skip.
 *
 * Order per block as in the C++ engine: equalizer -> loudness gain -> limiter (the AudioTrack volume comes after).
 * Works on 16-bit and float PCM (output = input encoding); anything else passes through untouched.
 *
 * Threading: the audio methods run on the playback thread; [setActiveItem] / [onPositionReset] come from the renderer
 * (same thread); the control side ([armLeadingSkip], silence queries) is any thread (volatile fields).
 */
@OptIn(UnstableApi::class)
class ShadeAudioProcessor(private val control: DspControl) : BaseAudioProcessor() {
    private val eq = Equalizer()
    private val limiter = Limiter()
    private var eqVersion = -1L
    private var scratch = FloatArray(0)

    private var rate = 0
    private var channels = 0
    private var float = false

    // Item gain with a ramp at changes (C++ kVolumeRampSeconds-like slew; the sink runs ahead of the audible position,
    // so the switch is tied to the stream boundary the renderer reports, not to a clock).
    @Volatile private var activeTag = 0L
    private var gainLinear = 1f
    private var gainStep = 0f

    // Media position bookkeeping of the active stream (frames since its start; for silence positions).
    @Volatile private var baseMs = 0L
    private var streamFrames = 0L

    // Smart crossfade: trailing silence.
    @Volatile private var silentStartFrame = -1L
    @Volatile private var silentRunFrames = 0L

    // Leading silence skip of a crossfade's incoming track (frames dropped since the last flush count as "skipped
    // output", so the reported position stays in track time).
    @Volatile private var leadSkipTag = 0L
    private var leadSkipping = false
    private var leadSkipLeft = 0L

    @Volatile var skippedFrames = 0L
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        eqVersion = -1
        eq.reset()
        limiter.configure(rate, channels)
        limiter.reset()
        gainLinear = targetGain()
        gainStep = 0f
        skippedFrames = 0
        streamFrames = 0
        silentStartFrame = -1
        silentRunFrames = 0
    }

    override fun onReset() {
        rate = 0
        channels = 0
        scratch = FloatArray(0)
        eqVersion = -1
    }

    /** A new stream (item) starts feeding the sink now: its gain ramps in, its media time starts at [startMs]. */
    fun setActiveItem(tag: Long, startMs: Long) {
        activeTag = tag
        baseMs = startMs
        streamFrames = 0
        silentStartFrame = -1
        silentRunFrames = 0
        armLeadIfDue(startMs)
    }

    private fun armLeadIfDue(startMs: Long) {
        leadSkipping = activeTag != 0L && activeTag == leadSkipTag && startMs == 0L
        leadSkipLeft = -1 // set from the sample rate at the first block
    }

    /** A seek inside the active stream. */
    fun onPositionReset(mediaMs: Long) {
        baseMs = mediaMs
        streamFrames = 0
        silentStartFrame = -1
        silentRunFrames = 0
        armLeadIfDue(mediaMs)
    }

    /** The crossfade's incoming item [tag] starts at its first sound (up to 5 s of leading silence dropped). */
    fun armLeadingSkip(tag: Long) {
        leadSkipTag = tag
    }

    val currentTag: Long get() = activeTag

    /** Media ms where the trailing silence of the active stream began, -1 when the last frames weren't silent. */
    fun silentSinceMs(): Long {
        val start = silentStartFrame
        val r = rate
        if (start < 0 || r <= 0) return -1
        return baseMs + start * 1000 / r
    }

    private fun targetGain(): Float {
        val db = control.gains[activeTag] ?: 0f
        return if (db == 0f) 1f else 10f.pow(db.coerceIn(-30f, 15f) / 20f)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val ch = channels.coerceAtLeast(1)
        val bytesPerSample = if (float) 4 else 2
        val frames = remaining / (bytesPerSample * ch)
        val samples = frames * ch
        if (scratch.size < samples) scratch = FloatArray(samples)
        val p = scratch
        val inOrder = inputBuffer.order(ByteOrder.nativeOrder())
        if (float) {
            val fb = inOrder.asFloatBuffer()
            fb.get(p, 0, samples)
        } else {
            val sb = inOrder.asShortBuffer()
            for (i in 0 until samples) p[i] = sb.get(i) / 32768f
        }
        inputBuffer.position(inputBuffer.position() + frames * bytesPerSample * ch)

        // Leading silence of a crossfade's incoming track: dropped (counted as skipped output).
        var first = 0
        if (leadSkipping) {
            if (leadSkipLeft < 0) leadSkipLeft = Crossfade.SKIP_LEAD_MS * maxOf(rate, 1) / 1000
            while (first < frames && leadSkipLeft > 0) {
                var quiet = true
                var c = 0
                while (c < ch && quiet) {
                    quiet = abs(p[first * ch + c]) < Crossfade.SILENCE_LEVEL
                    c++
                }
                if (!quiet) break
                first++
                leadSkipLeft--
            }
            if (first < frames || leadSkipLeft <= 0) {
                leadSkipping = false
                leadSkipTag = 0 // once: a later seek to 0 plays the track as it is
            }
            skippedFrames += first
            streamFrames += first
        }
        val n = frames - first
        val off = first * ch

        // Trailing silence (measured on the decoded audio, before EQ and gain, like the C++ silentRun).
        for (i in 0 until n) {
            var peak = 0f
            val s = off + i * ch
            for (c in 0 until ch) {
                val v = abs(p[s + c])
                if (v > peak) peak = v
            }
            if (peak < Crossfade.SILENCE_LEVEL) {
                if (silentRunFrames == 0L) silentStartFrame = streamFrames + i
                silentRunFrames++
            } else {
                silentRunFrames = 0
                silentStartFrame = -1
            }
        }
        streamFrames += n

        // Equalizer (versioned snapshot: coefficients follow the output format).
        val snap = control.eq.get()
        if (snap.version != eqVersion) {
            eq.configure(snap.settings, rate, ch)
            eqVersion = snap.version
        }
        eq.process(p, n, off)

        // Loudness gain, ramped over ~50 ms toward the active item's target.
        val target = targetGain()
        if (target != gainLinear || gainStep != 0f) {
            val rampFrames = maxOf(1, rate / 20)
            if (gainStep == 0f || (target - gainLinear) * gainStep < 0f) gainStep = (target - gainLinear) / rampFrames
            for (i in 0 until n) {
                if (gainStep != 0f) {
                    gainLinear += gainStep
                    if ((gainStep > 0f && gainLinear >= target) || (gainStep < 0f && gainLinear <= target)) {
                        gainLinear = target
                        gainStep = 0f
                    }
                }
                val s = off + i * ch
                for (c in 0 until ch) p[s + c] *= gainLinear
            }
        } else if (gainLinear != 1f) {
            for (i in off until off + n * ch) p[i] *= gainLinear
        }

        // Look-ahead limiter: -0.3 dBFS ceiling instead of hard clipping.
        limiter.process(p, n, off)

        val out = replaceOutputBuffer(n * ch * bytesPerSample)
        if (float) {
            for (i in off until off + n * ch) out.putFloat(p[i])
        } else {
            for (i in off until off + n * ch) {
                val v = (p[i] * 32767f).coerceIn(-32768f, 32767f)
                out.putShort(v.toInt().toShort())
            }
        }
        out.flip()
    }
}

/**
 * The sink's processor chain: our DSP first, then Media3's silence skipping and Sonic speed change (what
 * DefaultAudioProcessorChain does, with our processor's leading-silence skip counted as skipped output so positions
 * stay in track time).
 */
@OptIn(UnstableApi::class)
class ShadeProcessorChain(val shade: ShadeAudioProcessor) : AudioProcessorChain {
    private val silenceSkipping = SilenceSkippingAudioProcessor()
    private val sonic = SonicAudioProcessor()

    override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(shade, silenceSkipping, sonic)

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkipping.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = silenceSkipping.skippedFrames + shade.skippedFrames
}
