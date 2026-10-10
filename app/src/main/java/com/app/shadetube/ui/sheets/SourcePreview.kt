package com.app.shadetube.ui.sheets

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.app.shadetube.AppGraph
import com.app.shadetube.core.Http
import com.app.shadetube.core.Log
import com.app.shadetube.playback.Status
import com.app.shadetube.playback.engine.GoogleVideoDataSource
import com.app.shadetube.youtube.Match
import com.app.shadetube.youtube.ResolvedStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Önizle" of the source picker (SCREENS #14): 30 seconds of a candidate video on a tiny ExoPlayer of its own, created
 * on first use and released with the sheet — the queue and the main engine are never touched. While a preview runs
 * the main player is paused (if it was playing) and resumed when the preview stops, ends, fails or the sheet closes.
 *
 * Audio focus is left to the main engine (it already holds it); the preview player does not request its own, which
 * would make the engine treat it as a permanent loss.
 */
@OptIn(UnstableApi::class)
@Stable
internal class SourcePreview(private val context: Context, private val scope: CoroutineScope) {
    enum class Phase { Idle, Loading, Playing }

    /** The candidate being previewed (null = none). */
    var videoId by mutableStateOf<String?>(null)
        private set
    var phase by mutableStateOf(Phase.Idle)
        private set

    /** Called on the main thread when a preview could not be played. */
    var onFailed: () -> Unit = {}

    private var exo: ExoPlayer? = null
    private var job: Job? = null
    private var mainPaused = false
    private var resumeMain = false
    private var current: Match? = null
    private var retried = false

    fun isActive(id: String) = videoId == id && phase != Phase.Idle

    /** Play button of a row: starts [m]'s preview (stopping another one) or stops it when it is the one running. */
    fun toggle(m: Match) {
        if (isActive(m.videoId)) stop() else start(m)
    }

    private fun start(m: Match) {
        job?.cancel()
        exo?.stop()
        videoId = m.videoId
        current = m
        retried = false
        phase = Phase.Loading
        pauseMain()
        // Skip the intro of a full song: start 30 s in when the video is long enough.
        val startMs = if (m.durationSec >= 75) START_OFFSET_MS else 0L
        job = scope.launch {
            if (!load(m, startMs, bypassCache = false)) return@launch
            val player = exo ?: return@launch
            // Stop after PREVIEW_MS of playback (ticks only advance while it actually plays).
            while (isActive) {
                delay(250)
                if (player.playbackState == Player.STATE_ENDED || player.currentPosition >= startMs + PREVIEW_MS) {
                    stop()
                    break
                }
            }
        }
    }

    /**
     * Resolves [m]'s stream (AAC at the low quality: small, and its MP4 index sits at the front so the 30 s seek is
     * instant) and starts it at [positionMs]. False (after [fail]) when there is no stream.
     */
    private suspend fun load(m: Match, positionMs: Long, bypassCache: Boolean): Boolean {
        val stream = AppGraph.matchService.stream(m.videoId, allowWebm = false, lowQuality = true, bypassCache = bypassCache).getOrNull()
        if (stream == null || videoId != m.videoId) {
            if (videoId == m.videoId) fail("no stream")
            return false
        }
        val player = exo ?: build().also { exo = it }
        player.setMediaSource(mediaSourceOf(stream), positionMs)
        player.prepare()
        player.playWhenReady = true
        return true
    }

    /** An expired / refused URL (HTTP 403 …): resolve it again once, bypassing the stream cache, like the main engine. */
    private fun onError(error: PlaybackException) {
        val m = current ?: return
        if (videoId == null) return
        if (!retried && error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            retried = true
            val at = exo?.currentPosition ?: 0L
            Log.i(TAG, "preview stream refused (${error.errorCodeName}): resolving again")
            scope.launch { load(m, at, bypassCache = true) }
            return
        }
        fail(error.errorCodeName)
    }

    /** Stops the preview (if any) and resumes the main player when the preview paused it. */
    fun stop() {
        job?.cancel()
        job = null
        exo?.stop()
        exo?.clearMediaItems()
        videoId = null
        current = null
        phase = Phase.Idle
        resumeMainIfPaused()
    }

    fun release() {
        stop()
        exo?.release()
        exo = null
    }

    private fun fail(why: String) {
        Log.w(TAG, "preview failed: $why")
        stop()
        onFailed()
    }

    private fun pauseMain() {
        if (mainPaused) return
        val player = AppGraph.player
        val st = player.statusNow
        resumeMain = st == Status.Playing || st == Status.Buffering || st == Status.Resolving
        if (resumeMain) player.pause()
        mainPaused = true
    }

    private fun resumeMainIfPaused() {
        if (!mainPaused) return
        mainPaused = false
        if (resumeMain) AppGraph.player.play()
        resumeMain = false
    }

    private fun build(): ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ false,
        )
        .setLooper(Looper.getMainLooper())
        .build()
        .also { p ->
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && videoId != null) phase = Phase.Playing
                }

                override fun onPlayerError(error: PlaybackException) = onError(error)
            })
        }

    /** Same data sources as the main engine (ExoEngine): googlevideo in ranged pieces, backup proxies with Range headers. */
    private fun mediaSourceOf(s: ResolvedStream) = run {
        val ds: DataSource.Factory = if (s.isGoogleVideo) {
            GoogleVideoDataSource.Factory(s.requestHeaders, s.contentLength)
        } else {
            OkHttpDataSource.Factory(Http.client)
                .setDefaultRequestProperties(s.requestHeaders)
                .setUserAgent(s.requestHeaders["User-Agent"] ?: Http.USER_AGENT)
        }
        val item = MediaItem.Builder()
            .setUri(s.url)
            .apply { if (s.mimeType.isNotEmpty()) setMimeType(s.mimeType) }
            .build()
        ProgressiveMediaSource.Factory(ds).createMediaSource(item)
    }

    companion object {
        private const val TAG = "preview"
        const val PREVIEW_MS = 30_000L
        private const val START_OFFSET_MS = 30_000L
    }
}

/** A [SourcePreview] that lives as long as the calling sheet: released (and the main player resumed) when it leaves. */
@Composable
internal fun rememberSourcePreview(onFailed: () -> Unit): SourcePreview {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember { SourcePreview(context, scope) }
    preview.onFailed = onFailed
    DisposableEffect(preview) { onDispose { preview.release() } }
    return preview
}
