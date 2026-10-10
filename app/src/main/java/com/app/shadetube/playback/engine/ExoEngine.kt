package com.app.shadetube.playback.engine

import androidx.annotation.OptIn
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player as ExoPlayerApi
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Http
import com.app.shadetube.core.Log
import com.app.shadetube.core.appScope
import com.app.shadetube.playback.Crossfade
import com.app.shadetube.playback.Engine
import com.app.shadetube.playback.EngineErrorKind
import com.app.shadetube.playback.EngineListener
import com.app.shadetube.playback.EngineSource
import com.app.shadetube.playback.EngineState
import com.app.shadetube.playback.LocalSource
import com.app.shadetube.playback.Loudness
import com.app.shadetube.playback.ReplayGainReader
import com.app.shadetube.playback.dsp.EqSettings
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The renderer behind [com.app.shadetube.playback.Player] on Android (the C++ AudioEngine's role), on two ExoPlayer
 * "decks" that each run our DSP ([ShadeAudioProcessor]: loudness gain, equalizer, look-ahead limiter):
 *
 * - Gapless: the preloaded next item is appended to the playing deck's playlist, so ExoPlayer hands over without a
 *   gap; the transition is reported as onEnded(previous, next) and the finished item is dropped from the playlist.
 * - Crossfade ([EngineSource.crossfadeMs] > 0 on the preloaded item): ExoPlayer can't mix two items, so the next one is
 *   prepared (paused, silent) on the other deck. When the current one has that much left — or has gone silent near its
 *   end (smart crossfade, measured by the processor) — the other deck starts and the two are mixed with equal-power
 *   volume curves; the incoming track starts at its first sound (its leading silence, up to 5 s, is dropped by its
 *   processor). Positions and the transition event follow the incoming track from the start of the mix (like the C++
 *   engine). A pause / seek / open / stop during the mix drops the outgoing track. When a mix can't start in time the
 *   other deck simply starts when the current item ends (a short gap instead of a fade).
 * - Live streams (stations): progressive or HLS, public internet only; titles from ICY metadata / HLS ID3 frames. A
 *   stream that ends or fails is an error: the player's reconnect policy decides what follows.
 *
 * Audio focus and "becoming noisy" are handled here for both decks ([AudioFocus]). The decks hold a network wake lock
 * while playing (WAKE_LOCK permission). Main thread only.
 */
@OptIn(UnstableApi::class)
class ExoEngine(
    context: Context,
    private val replayGain: ReplayGainReader? = null,
    /** User-Agent for stations (InternetRadio.userAgent). */
    private val stationUserAgent: String = Http.USER_AGENT,
) : Engine {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val dsp = DspControl()

    override var listener: EngineListener? = null

    private inner class Deck(val name: String) {
        val processor = ShadeAudioProcessor(dsp)
        val exo: ExoPlayer = ExoPlayer.Builder(app, ShadeRenderersFactory(app, processor))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ false,
            )
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLooper(Looper.getMainLooper())
            .build()

        init {
            exo.addListener(DeckListener(this))
        }

        fun tagOfCurrent(): Long = tagOfMediaId(exo.currentMediaItem?.mediaId)

        fun clear() {
            exo.stop()
            exo.clearMediaItems()
        }
    }

    private var decksCreated = false
    private lateinit var deckA: Deck
    private lateinit var deckB: Deck

    /** The deck whose current item is audible / reported. */
    private lateinit var active: Deck

    /** The other deck: a crossfade's incoming item waits here, or the outgoing one fades out here. */
    private lateinit var other: Deck

    private enum class PreloadMode { None, InPlaylist, OtherDeck }

    private var curTag = 0L
    private var curSource: EngineSource? = null
    private var preloaded: EngineSource? = null
    private var preloadMode = PreloadMode.None
    private var lastReported: Pair<EngineState, Long>? = null

    private var fading: Deck? = null
    private var fadeStartMs = 0L
    private var fadeLengthMs = 0L
    private var fadeInLevel = 1f
    private var masterVolume = 1f
    private var userPaused = false

    private val focus by lazy {
        AudioFocus(
            app,
            onPause = { _ -> pauseForFocus() },
            onResume = { if (!userPaused) playInternal() },
            onNoisy = { pause() },
        )
    }

    private fun ensureDecks() {
        if (decksCreated) return
        deckA = Deck("A")
        deckB = Deck("B")
        active = deckA
        other = deckB
        decksCreated = true
        applyVolumes()
    }

    // ---- media sources -------------------------------------------------------------------------------------------------

    private val directClientPublic: OkHttpClient by lazy { Http.client.newBuilder().addNetworkInterceptor(PublicOnly).readTimeout(30, TimeUnit.SECONDS).build() }
    private val directClientAny: OkHttpClient by lazy { Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }

    /** Refuses connections to this device or its local network (every hop, redirects included). */
    private object PublicOnly : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val route = chain.connection()?.route()
            if (route != null && route.proxy.type() == Proxy.Type.DIRECT) {
                val addr = route.socketAddress.address
                if (addr != null && Http.isLocalHost(addr.hostAddress ?: "", resolve = false)) throw IOException("an address on the local network")
            }
            return chain.proceed(chain.request())
        }
    }

    private val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)

    private fun mediaSourceFor(s: EngineSource, file: File?): MediaSource {
        val item = MediaItem.Builder()
            .setMediaId(mediaIdOf(s.tag))
            .setUri(if (file != null) Uri.fromFile(file) else Uri.parse(s.url))
            .apply {
                if (s.isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
                else if (s.mimeType.isNotEmpty() && s.kind != EngineSource.Kind.Live) setMimeType(s.mimeType)
            }
            .build()
        return when (s.kind) {
            EngineSource.Kind.Youtube -> {
                val ds: DataSource.Factory = if (s.googleVideo) {
                    GoogleVideoDataSource.Factory(s.requestHeaders, s.contentLength)
                } else { // backup-source proxies take a normal Range header
                    OkHttpDataSource.Factory(Http.client).setDefaultRequestProperties(s.requestHeaders).setUserAgent(s.requestHeaders["User-Agent"] ?: Http.USER_AGENT)
                }
                ProgressiveMediaSource.Factory(ds, extractors).createMediaSource(item)
            }
            EngineSource.Kind.Local -> ProgressiveMediaSource.Factory(DefaultDataSource.Factory(app), extractors).createMediaSource(item)
            EngineSource.Kind.Direct -> {
                val ds = OkHttpDataSource.Factory(if (s.allowLocalNetwork) directClientAny else directClientPublic)
                    .setDefaultRequestProperties(s.requestHeaders)
                ProgressiveMediaSource.Factory(ds, extractors).createMediaSource(item)
            }
            EngineSource.Kind.Live -> {
                // No retries here: the player's reconnect policy (backoff, failure count) handles a dropped stream.
                val ds = StationDataSource.Factory(stationUserAgent, s.allowLocalNetwork)
                if (s.isHls) {
                    HlsMediaSource.Factory(ds).setAllowChunklessPreparation(true)
                        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1)).createMediaSource(item)
                } else {
                    ProgressiveMediaSource.Factory(ds, extractors)
                        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)).createMediaSource(item)
                }
            }
        }
    }

    /** Registers the loudness gain of a load; a ReplayGain source reads its tag on a worker first (0 dB meanwhile). */
    private fun registerGain(s: EngineSource, file: File?) {
        if (!s.replayGain) {
            dsp.gains[s.tag] = s.gainDb
            return
        }
        val reader = replayGain ?: return
        val local = LocalSource(s.url, s.mimeType, file)
        appScope.launch(AppDispatchers.io) {
            val rg = runCatching { reader.trackGainDb(local) }.getOrNull()
            dsp.gains[s.tag] = Loudness.replayGainDb(rg, s.gainDb, s.maxBoostDb)
        }
    }

    private fun fileOf(s: EngineSource): File? =
        if (s.kind == EngineSource.Kind.Local && s.url.startsWith("file://")) File(s.url.removePrefix("file://")) else null

    private fun forgetGainsExcept(vararg keep: Long) {
        dsp.gains.keys.removeAll { it !in keep }
    }

    // ---- Engine ----------------------------------------------------------------------------------------------------------

    override fun open(source: EngineSource, startMs: Long, autoplay: Boolean) {
        ensureDecks()
        endCrossfade()
        clearPreloadInternal()
        other.clear()
        val file = fileOf(source)
        curTag = source.tag
        curSource = source
        lastReported = null
        forgetGainsExcept(source.tag)
        registerGain(source, file)
        val exo = active.exo
        exo.setMediaSource(mediaSourceFor(source, file), if (source.live) C.TIME_UNSET else startMs.coerceAtLeast(0))
        exo.playbackParameters = PlaybackParameters(if (source.live) 1f else source.speed, 1f)
        exo.prepare()
        userPaused = !autoplay
        if (autoplay) playInternal() else exo.playWhenReady = false
        report(EngineState.Loading, source.tag)
    }

    override fun preload(source: EngineSource) {
        ensureDecks()
        if (curTag == 0L || source.live) return
        clearPreloadInternal()
        val file = fileOf(source)
        registerGain(source, file)
        preloaded = source
        if (source.crossfadeMs > 0) {
            preloadMode = PreloadMode.OtherDeck
            if (fading != null) endCrossfade()
            val exo = other.exo
            other.processor.armLeadingSkip(source.tag)
            exo.volume = 0f
            exo.setMediaSource(mediaSourceFor(source, file), 0)
            exo.playbackParameters = PlaybackParameters(1f, 1f)
            exo.playWhenReady = false
            exo.prepare()
            if (active.exo.isPlaying) scheduleCrossfadeTick()
        } else {
            preloadMode = PreloadMode.InPlaylist
            active.exo.addMediaSource(mediaSourceFor(source, file))
        }
    }

    override fun clearPreload() {
        if (!decksCreated) return
        clearPreloadInternal()
    }

    private fun clearPreloadInternal() {
        if (!decksCreated) return
        when (preloadMode) {
            PreloadMode.InPlaylist -> {
                val exo = active.exo
                val keep = exo.currentMediaItemIndex
                if (exo.mediaItemCount > keep + 1) exo.removeMediaItems(keep + 1, exo.mediaItemCount)
            }
            PreloadMode.OtherDeck -> if (fading !== other) other.clear()
            PreloadMode.None -> Unit
        }
        preloaded?.let { p -> if (p.tag != curTag) dsp.gains.remove(p.tag) }
        preloaded = null
        preloadMode = PreloadMode.None
    }

    override fun play() {
        if (!decksCreated || curTag == 0L) return
        userPaused = false
        playInternal()
    }

    /**
     * stop() / the end of an item give focus back only if nothing plays again shortly: the player stops the engine
     * between two tracks, and abandoning focus there would let another app resume for a moment.
     */
    private val releaseFocusRunnable = Runnable { focus.release() }

    private fun releaseFocusSoon() {
        handler.removeCallbacks(releaseFocusRunnable)
        handler.postDelayed(releaseFocusRunnable, 3000)
    }

    private fun playInternal() {
        if (!decksCreated || curTag == 0L) return
        handler.removeCallbacks(releaseFocusRunnable)
        if (!focus.acquire()) {
            Log.i("player", "audio focus not granted: staying paused")
            return
        }
        val exo = active.exo
        if (exo.playbackState == ExoPlayerApi.STATE_IDLE) exo.prepare()
        exo.playWhenReady = true
        fading?.exo?.playWhenReady = true
    }

    override fun pause() {
        if (!decksCreated) return
        userPaused = true
        endCrossfade()
        active.exo.playWhenReady = false
        focus.release()
    }

    private fun pauseForFocus() {
        if (!decksCreated) return
        endCrossfade()
        active.exo.playWhenReady = false
    }

    override fun stop() {
        if (!decksCreated) return
        endCrossfade()
        clearPreloadInternal()
        active.clear()
        other.clear()
        curTag = 0
        curSource = null
        lastReported = null
        dsp.gains.clear()
        releaseFocusSoon()
    }

    override fun seek(ms: Long) {
        if (!decksCreated || curTag == 0L) return
        endCrossfade()
        val exo = active.exo
        exo.seekTo(exo.currentMediaItemIndex, ms.coerceAtLeast(0))
    }

    override fun setSpeed(speed: Float) {
        if (!decksCreated) return
        if (curSource?.live == true) return
        active.exo.playbackParameters = PlaybackParameters(speed, 1f)
    }

    override fun setVolume(volume: Float) {
        masterVolume = volume.coerceIn(0f, 1f)
        if (decksCreated) applyVolumes()
    }

    override fun setEqualizer(eq: EqSettings) = dsp.setEqualizer(eq)

    override val currentTag: Long get() = if (decksCreated && active.tagOfCurrent() == curTag) curTag else 0L

    override fun positionMs(): Long {
        if (!decksCreated || active.tagOfCurrent() != curTag) return 0
        return active.exo.currentPosition.coerceAtLeast(0)
    }

    override fun durationMs(): Long {
        if (!decksCreated || active.tagOfCurrent() != curTag) return 0
        val d = active.exo.duration
        return if (d == C.TIME_UNSET) 0 else d
    }

    override fun bufferedFraction(): Float {
        val d = durationMs()
        if (d <= 0) return 0f
        return (active.exo.bufferedPosition.toFloat() / d).coerceIn(0f, 1f)
    }

    override fun silentSinceMs(): Long {
        if (!decksCreated || active.processor.currentTag != curTag) return -1
        return active.processor.silentSinceMs()
    }

    override fun release() {
        if (!decksCreated) return
        handler.removeCallbacksAndMessages(null)
        focus.release()
        deckA.exo.release()
        deckB.exo.release()
        decksCreated = false
    }

    // ---- volume / crossfade ----------------------------------------------------------------------------------------------

    /** Perceptual volume curve: cubic (~ -18 dB at 50 %, close to perceived loudness), as in the C++ engine. */
    private fun perceptual(v: Float) = v * v * v

    private fun applyVolumes() {
        val v = perceptual(masterVolume)
        val f = fading
        if (f == null) {
            active.exo.volume = v * fadeInLevel
            if (preloadMode != PreloadMode.OtherDeck) other.exo.volume = 0f
            return
        }
        val t = if (fadeLengthMs > 0) (System.currentTimeMillis() - fadeStartMs).toDouble() / fadeLengthMs else 1.0
        val (gIn, gOut) = Crossfade.equalPower(t)
        active.exo.volume = v * gIn
        f.exo.volume = v * gOut
    }

    private val crossfadeTick = object : Runnable {
        override fun run() {
            if (!decksCreated) return
            maybeStartCrossfade()
            stepFade()
            if (preloadMode == PreloadMode.OtherDeck || fading != null || fadeInLevel < 1f) handler.postDelayed(this, if (fading != null) 20 else 100)
        }
    }

    private fun scheduleCrossfadeTick() {
        handler.removeCallbacks(crossfadeTick)
        handler.post(crossfadeTick)
    }

    /** AudioEngine::maybeStartCrossfade on ExoPlayer: the timing rules are [Crossfade.startDecision]. */
    private fun maybeStartCrossfade() {
        val next = preloaded ?: return
        if (preloadMode != PreloadMode.OtherDeck || fading != null) return
        val exo = active.exo
        if (!exo.isPlaying || active.tagOfCurrent() != curTag) return
        val dur = exo.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        val pos = exo.currentPosition
        val remaining = dur - pos
        val silentSince = active.processor.silentSinceMs()
        val silentRun = if (silentSince in 0..pos) pos - silentSince else 0
        val decision = Crossfade.startDecision(remaining, silentRun, next.crossfadeMs.toLong())
        if (decision !is Crossfade.Start.Now) return // NotYet; Gapless = handover when the current item ends
        // The incoming track must be ready, or the mix would stall right away.
        if (other.exo.playbackState != ExoPlayerApi.STATE_READY) return
        startMix(decision.lengthMs, decision.silentEnd)
    }

    private fun startMix(lengthMs: Long, silentEnd: Boolean) {
        val next = preloaded ?: return
        val outgoing = active
        val finished = curTag
        active = other
        other = outgoing
        fading = outgoing
        fadeStartMs = System.currentTimeMillis()
        fadeLengthMs = lengthMs.coerceAtLeast(1)
        fadeInLevel = 1f
        curTag = next.tag
        curSource = next
        preloaded = null
        preloadMode = PreloadMode.None
        lastReported = null
        active.exo.volume = 0f
        active.exo.playWhenReady = true
        Log.d("audio", "crossfade $finished -> ${next.tag} over $lengthMs ms${if (silentEnd) " (silent ending)" else ""}")
        listener?.onEngineEnded(finished, next.tag)
        reportActiveState()
        scheduleCrossfadeTick()
    }

    private fun stepFade() {
        val f = fading
        if (f == null) {
            if (fadeInLevel < 1f) { // the outgoing track ran out early: the incoming one rises to full level in 50 ms
                fadeInLevel = minOf(1f, fadeInLevel + 20f / Crossfade.RISE_MS)
                applyVolumes()
            }
            return
        }
        val t = (System.currentTimeMillis() - fadeStartMs).toDouble() / fadeLengthMs
        if (t >= 1.0 || f.exo.playbackState == ExoPlayerApi.STATE_ENDED || f.exo.playbackState == ExoPlayerApi.STATE_IDLE) {
            if (t < 1.0) fadeInLevel = Crossfade.equalPower(t).first
            retireFading()
            applyVolumes()
            return
        }
        applyVolumes()
    }

    private fun retireFading() {
        val f = fading ?: return
        fading = null
        f.clear()
        f.exo.volume = 0f
    }

    /** Pause / seek / open / stop during a mix: the outgoing track goes at once, the incoming one at full level. */
    private fun endCrossfade() {
        if (!decksCreated) return
        if (fading != null) retireFading()
        fadeInLevel = 1f
        applyVolumes()
    }

    /** No mix was possible in time: the waiting deck takes over when the current item ends. */
    private fun handOverToOtherDeck() {
        val next = preloaded ?: return
        val finished = curTag
        val outgoing = active
        active = other
        other = outgoing
        curTag = next.tag
        curSource = next
        preloaded = null
        preloadMode = PreloadMode.None
        lastReported = null
        outgoing.clear()
        fadeInLevel = 1f
        applyVolumes()
        active.exo.playWhenReady = true
        listener?.onEngineEnded(finished, next.tag)
        reportActiveState()
    }

    // ---- ExoPlayer events ------------------------------------------------------------------------------------------------

    private fun report(state: EngineState, tag: Long) {
        if (tag == 0L) return
        val r = state to tag
        if (r == lastReported) return
        lastReported = r
        listener?.onEngineState(state, tag)
    }

    private fun reportActiveState() {
        val exo = active.exo
        if (active.tagOfCurrent() != curTag) return
        val state = when (exo.playbackState) {
            ExoPlayerApi.STATE_BUFFERING -> if (exo.playWhenReady) EngineState.Loading else EngineState.Paused
            ExoPlayerApi.STATE_READY -> if (exo.playWhenReady) {
                if (exo.playbackSuppressionReason == ExoPlayerApi.PLAYBACK_SUPPRESSION_REASON_NONE) EngineState.Playing else EngineState.Paused
            } else EngineState.Paused
            else -> return
        }
        report(state, curTag)
        if (state == EngineState.Playing && preloadMode == PreloadMode.OtherDeck) scheduleCrossfadeTick()
    }

    private fun classify(error: PlaybackException, local: Boolean): EngineErrorKind {
        val code = error.errorCode
        return when {
            code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                if (local) EngineErrorKind.FileNotFound else EngineErrorKind.Network
            code in 2000..2999 -> if (local) EngineErrorKind.FileNotFound else EngineErrorKind.Network // IO
            code in 3000..3999 -> EngineErrorKind.UnsupportedFormat // parsing
            code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> EngineErrorKind.UnsupportedFormat
            else -> EngineErrorKind.Other
        }
    }

    private fun describe(error: PlaybackException): String {
        val cause = error.cause
        if (cause is HttpDataSource.InvalidResponseCodeException) return "HTTP ${cause.responseCode} (${error.errorCodeName})"
        return "${error.errorCodeName}: ${cause?.message ?: error.message}"
    }

    private fun tagOfError(deck: Deck, error: PlaybackException): Long {
        val id = (error as? ExoPlaybackException)?.mediaPeriodId ?: return deck.tagOfCurrent()
        return runCatching {
            val tl = deck.exo.currentTimeline
            val idx = tl.getPeriodByUid(id.periodUid, Timeline.Period()).windowIndex
            tagOfMediaId(tl.getWindow(idx, Timeline.Window()).mediaItem.mediaId)
        }.getOrDefault(deck.tagOfCurrent())
    }

    private inner class DeckListener(val deck: Deck) : ExoPlayerApi.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (deck === fading) {
                if (playbackState == ExoPlayerApi.STATE_ENDED) {
                    stepFade()
                }
                return
            }
            if (deck !== active) return
            when (playbackState) {
                ExoPlayerApi.STATE_ENDED -> {
                    if (curSource?.live == true) { // a station that ends dropped its connection
                        listener?.onEngineError(EngineErrorKind.Network, "stream ended", curTag)
                        return
                    }
                    if (preloadMode == PreloadMode.OtherDeck && preloaded != null) {
                        handOverToOtherDeck()
                        return
                    }
                    val finished = curTag
                    report(EngineState.Ended, finished)
                    releaseFocusSoon()
                    listener?.onEngineEnded(finished, 0)
                }
                else -> reportActiveState()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (deck === active) reportActiveState()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (deck === active) reportActiveState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (deck !== active || reason != ExoPlayerApi.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            val newTag = tagOfMediaId(mediaItem?.mediaId)
            val finished = curTag
            if (newTag == 0L || newTag == finished) return
            val next = preloaded
            curTag = newTag
            curSource = next?.takeIf { it.tag == newTag } ?: curSource
            preloaded = null
            preloadMode = PreloadMode.None
            lastReported = null
            dsp.gains.remove(finished)
            if (next != null && next.tag == newTag) deck.exo.playbackParameters = PlaybackParameters(next.speed, 1f)
            // Drop the finished item from the playlist (posted: not from inside ExoPlayer's callback).
            handler.post {
                val exo = deck.exo
                if (deck === active && exo.currentMediaItemIndex > 0) exo.removeMediaItems(0, exo.currentMediaItemIndex)
            }
            listener?.onEngineEnded(finished, newTag)
            reportActiveState()
        }

        override fun onPlayerError(error: PlaybackException) {
            val tag = tagOfError(deck, error)
            val local = (if (tag == preloaded?.tag) preloaded else curSource)?.kind == EngineSource.Kind.Local
            val kind = classify(error, local)
            val message = describe(error)
            Log.w("audio", "deck ${deck.name}: ${error.errorCodeName} (${error.cause?.javaClass?.simpleName}: ${error.cause?.message})")
            if (deck === fading) {
                retireFading()
                applyVolumes()
                return
            }
            if (deck !== active) { // the crossfade's incoming item failed while waiting
                val p = preloaded
                deck.clear()
                preloaded = null
                preloadMode = PreloadMode.None
                if (p != null) listener?.onEngineError(kind, message, p.tag)
                return
            }
            val p = preloaded
            if (p != null && tag == p.tag) {
                // The preloaded item failed (ExoPlayer reports it when playback reaches it): the current one is over;
                // the player opens the next one normally.
                preloaded = null
                preloadMode = PreloadMode.None
                val finished = curTag
                listener?.onEngineError(kind, message, p.tag)
                listener?.onEngineEnded(finished, 0)
                return
            }
            report(EngineState.Error, curTag)
            listener?.onEngineError(kind, message, curTag)
        }

        override fun onMetadata(metadata: Metadata) {
            if (deck !== active) return
            var title: String? = null
            var raw: ByteArray? = null
            var artist: String? = null
            for (i in 0 until metadata.length()) {
                when (val e = metadata.get(i)) {
                    is IcyInfo -> {
                        raw = e.rawMetadata
                        title = e.title
                    }
                    is TextInformationFrame -> when (e.id) {
                        "TIT2" -> title = e.values.firstOrNull()
                        "TPE1" -> artist = e.values.firstOrNull()
                    }
                }
            }
            if (raw == null && title == null) return
            val shown = if (artist != null && title != null && raw == null) "$artist - $title" else title
            listener?.onEngineTitle(shown, raw, curTag)
        }
    }
}
