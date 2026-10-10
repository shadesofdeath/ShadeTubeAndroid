package com.app.shadetube.playback

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.RepeatMode
import com.app.shadetube.core.Settings

/**
 * Loudness normalisation (Settings.normalizeVolume) brings every track to Settings.loudnessTarget: YouTube reports a
 * stream's loudness against its -14 LUFS reference, local files carry ReplayGain (a gain to -18 LUFS). Without the
 * peak levels boosts stay modest (+4 dB, +8 dB at the loud target) and the engine's limiter catches their peaks.
 */
object Loudness {
    /** Cuts go down to this (YouTube streams). */
    const val MAX_CUT_DB = -20.0

    /** +8 dB at the loud target (>= -12 LUFS), else +4 dB. */
    fun maxBoostDb(s: Settings): Double = if (s.loudnessTarget >= -12) 8.0 else 4.0

    /** A YouTube stream: `target + 14 - loudnessDb`, clamped to [-20, maxBoost]; 0 without normalisation / loudness. */
    fun youtubeGainDb(s: Settings, loudnessDb: Double?): Float {
        if (!s.normalizeVolume || loudnessDb == null) return 0f
        return (s.loudnessTarget + 14 - loudnessDb).coerceIn(MAX_CUT_DB, maxBoostDb(s)).toFloat()
    }

    /**
     * A local file / download: the gain added to its ReplayGain track gain (`target + 18`), with the boost cap; null
     * without normalisation (no ReplayGain is read then).
     */
    fun localBase(s: Settings): Pair<Float, Float>? =
        if (s.normalizeVolume) (s.loudnessTarget + 18).toFloat() to maxBoostDb(s).toFloat() else null

    /** audio::Track::gainDb for a ReplayGain source: min(rg + base, maxBoost); no tag = no gain. */
    fun replayGainDb(trackGainDb: Float?, baseDb: Float, maxBoostDb: Float): Float =
        if (trackGainDb == null || trackGainDb.isNaN()) 0f else minOf(trackGainDb + baseDb, maxBoostDb)
}

/**
 * Crossfade decision (Player::crossfadeInto): the crossfade length (ms) from [from] into [to], 0 = gapless.
 * Settings.crossfadeSec (0-12). An album playing in order stays gapless (live records, DJ mixes and concept albums
 * flow into the next track); spoken word never fades into music or the next episode; nor does anything play faster or
 * slower. Repeat one replays the same slot ([sameSlot]) and never fades either.
 */
object Crossfade {
    /** Smart crossfade (AudioEngine): under this peak level (-50 dBFS) audio counts as silence. */
    const val SILENCE_LEVEL = 0.00316f

    /** A song whose last seconds went silent hands over at once after this much of it ... */
    const val SILENT_TAIL_MS = 1500L

    /** ... within this distance of its end, with a short fade ([SILENT_TAIL_FADE_MS]). */
    const val SILENT_TAIL_WINDOW_MS = 20_000L
    const val SILENT_TAIL_FADE_MS = 500L

    /** The incoming song starts at its first sound: up to this much leading silence is dropped. */
    const val SKIP_LEAD_MS = 5000L

    /** Less than this left when the mix could start: too late to be worth it, gapless instead. */
    const val MIN_REMAINING_MS = 200L

    /** When the outgoing track runs out (or stalls) early, the incoming one rises to full level in this long. */
    const val RISE_MS = 50L

    fun lengthMs(
        crossfadeSec: Int,
        from: Track?,
        to: Track?,
        sameSlot: Boolean,
        speedOf: (Track) -> Float,
    ): Int {
        if (crossfadeSec <= 0 || from == null || to == null || sameSlot) return 0
        if (from.album.id.isNotEmpty() && from.album.id == to.album.id &&
            (from.trackNumber <= 0 || to.trackNumber <= 0 || to.trackNumber == from.trackNumber + 1)
        ) return 0
        if (isPodcastId(from.id) || isPodcastId(to.id)) return 0
        if (speedOf(from) != 1f || speedOf(to) != 1f) return 0
        return crossfadeSec * 1000
    }

    /** Equal-power curves at progress [t] (0..1): (incoming, outgoing) gains. */
    fun equalPower(t: Double): Pair<Float, Float> {
        val x = t.coerceIn(0.0, 1.0) * Math.PI / 2
        return kotlin.math.sin(x).toFloat() to kotlin.math.cos(x).toFloat()
    }

    sealed interface Start {
        data object NotYet : Start

        /** Too late for a mix (or no mix possible): hand over gaplessly at the end. */
        data object Gapless : Start

        /** Start the mix now, over [lengthMs]; [silentEnd]: the outgoing track only plays silence any more. */
        data class Now(val lengthMs: Long, val silentEnd: Boolean) : Start
    }

    /**
     * AudioEngine::maybeStartCrossfade's timing: [remainingMs] of the current track left, [silentRunMs] of trailing
     * silence heard so far (0 = not silent), [fadeMs] the asked length.
     */
    fun startDecision(remainingMs: Long, silentRunMs: Long, fadeMs: Long): Start {
        val silentEnd = silentRunMs >= SILENT_TAIL_MS && remainingMs <= SILENT_TAIL_WINDOW_MS
        if (silentEnd) return Start.Now(minOf(remainingMs, SILENT_TAIL_FADE_MS).coerceAtLeast(1), true)
        if (remainingMs > fadeMs) return Start.NotYet
        if (remainingMs < MIN_REMAINING_MS) return Start.Gapless
        return Start.Now(remainingMs, false)
    }

    /** Repeat-one never crossfades (same slot); kept for callers that only know the mode. */
    fun allowedFor(repeat: RepeatMode) = repeat != RepeatMode.One
}
