package com.app.shadetube.playback

import com.app.shadetube.catalog.Track
import com.app.shadetube.playback.dsp.EqSettings

/**
 * The renderer behind [Player] (the C++ audio::AudioEngine contract). The Android implementation is
 * [com.app.shadetube.playback.engine.ExoEngine] (ExoPlayer + our DSP); tests use a fake.
 *
 * Every load carries a tag; state / end / error events name the tag they are about, and the player ignores events of a
 * load it has superseded. Main thread only; events are delivered on the main thread.
 */
interface Engine {
    var listener: EngineListener?

    /** Opens [source] (stops whatever played) at [startMs]; plays at once with [autoplay]. */
    fun open(source: EngineSource, startMs: Long, autoplay: Boolean)

    /**
     * The next item, prepared for a gapless handoff (or, with [EngineSource.crossfadeMs] > 0, a crossfade). When the
     * current item ends the engine moves on to it by itself and reports onEnded(current, next).
     */
    fun preload(source: EngineSource)
    fun clearPreload()

    fun play()
    fun pause()

    /** Stops and releases the current (and preloaded) item. */
    fun stop()
    fun seek(ms: Long)

    /** Playback speed of the current item (0.5 .. 3, pitch kept). */
    fun setSpeed(speed: Float)

    /** Settings.volume (0..1, linear slider; the engine applies its perceptual curve). */
    fun setVolume(volume: Float)

    /** Ayarlar › SES equalizer; picked up at the next audio block. */
    fun setEqualizer(eq: EqSettings)

    /** The tag of the item that is audible (0 = none). */
    val currentTag: Long
    fun positionMs(): Long
    fun durationMs(): Long
    fun bufferedFraction(): Float

    /**
     * Smart crossfade: where (media ms of the current item) the trailing silence it is in now began, -1 when it is not
     * silent. Engines without level analysis return -1.
     */
    fun silentSinceMs(): Long = -1

    /** Releases everything (process going away). */
    fun release() {}
}

enum class EngineState { Idle, Loading, Playing, Paused, Ended, Error }

/** Why a load failed: what the player's recovery depends on. */
enum class EngineErrorKind {
    /** Connection / read failure, or an expired stream URL (HTTP 403 / 404 / 410). */
    Network,

    /** A codec / container this device can't play. */
    UnsupportedFormat,

    /** A local file that can't be opened / read. */
    FileNotFound,
    Other,
}

interface EngineListener {
    fun onEngineState(state: EngineState, tag: Long)

    /** [finished] ended; [next] (0 = none) took over gaplessly / by crossfade. */
    fun onEngineEnded(finished: Long, next: Long)
    fun onEngineError(kind: EngineErrorKind, message: String, tag: Long)

    /** Live streams: the ICY / HLS title of [tag] ([raw] = the ICY metadata block when there is one). */
    fun onEngineTitle(title: String?, raw: ByteArray?, tag: Long)
}

/** What the engine opens (the C++ audio::StreamSource). */
data class EngineSource(
    val tag: Long,
    val kind: Kind,
    /** http(s), file:// or content:// */
    val url: String,
    /** "" = sniff. */
    val mimeType: String = "",
    /** bytes; 0 = unknown. */
    val contentLength: Long = 0,
    /** Sent with every request (YouTube streams: the User-Agent of the client the URL was issued to). */
    val requestHeaders: Map<String, String> = emptyMap(),
    /** googlevideo: fetched in ~1 MB `&range=a-b` chunks (see GoogleVideoDataSource). */
    val googleVideo: Boolean = false,
    /** Live HLS playlist (internet radio). */
    val isHls: Boolean = false,
    /** Stations / podcast feeds reach only the public internet unless this is set. */
    val allowLocalNetwork: Boolean = false,
    val durationMsHint: Long = 0,
    /** Loudness normalisation gain (dB); with [replayGain] it is added to the file's ReplayGain track gain. */
    val gainDb: Float = 0f,
    /** Local file: read its ReplayGain track gain, play at min(rg + gainDb, maxBoostDb); no tag = no gain. */
    val replayGain: Boolean = false,
    val maxBoostDb: Float = 0f,
    val speed: Float = 1f,
    /** As a preloaded next item: crossfade into it over this long (0 = gapless). */
    val crossfadeMs: Int = 0,
    /** For the notification / lock screen. */
    val track: Track = Track(),
) {
    enum class Kind { Youtube, Local, Direct, Live }

    val live: Boolean get() = kind == Kind.Live
}
