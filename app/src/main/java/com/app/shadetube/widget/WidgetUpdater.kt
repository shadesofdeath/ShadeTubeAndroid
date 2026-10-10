package com.app.shadetube.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.PowerManager
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import com.app.shadetube.AppGraph
import com.app.shadetube.core.Log
import com.app.shadetube.playback.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The current cover, downscaled and rounded for each widget size. */
class WidgetCover(val url: String, val small: Bitmap, val large: Bitmap)

/**
 * Keeps the home-screen widgets in step with the player (started once by AppGraph, main thread): the track, play
 * state, like and shuffle are pushed when they change; the 4×2 widget's progress bar every [PROGRESS_EVERY_MS] while
 * something plays and the screen is on (never while nothing plays). The cover is loaded once per track as small
 * bitmaps (RemoteViews carry them; the last one is kept). Nothing is pushed while no widget is placed.
 */
@SuppressLint("StaticFieldLeak") // the application context
object WidgetUpdater {
    private const val TAG = "widget"
    const val PROGRESS_EVERY_MS = 10_000L
    private const val COALESCE_MS = 150L
    private const val COVER_WAIT_MS = 2_500L
    private const val SMALL_PX = 144 // 48 dp at xxhdpi
    private const val LARGE_PX = 192 // 96 dp at xhdpi; the RemoteViews bitmap budget stays small

    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    private val _state = MutableStateFlow(WidgetState())
    val state: StateFlow<WidgetState> = _state.asStateFlow()

    private val _cover = MutableStateFlow<WidgetCover?>(null)
    val cover: StateFlow<WidgetCover?> = _cover.asStateFlow()
    private var coverJob: Job? = null

    /** Which widgets to update: true = only the large one (a progress tick). */
    private val pushes = Channel<Boolean>(Channel.CONFLATED)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start(app: Context) {
        if (started) return
        started = true
        context = app.applicationContext
        val p = AppGraph.player
        refresh()
        scope.launch {
            merge(p.changed, p.current.map { }, p.status.map { }, p.liveTitle.map { }, AppGraph.playback.mediaButtonsChanged)
                .debounce(COALESCE_MS)
                .collect { refresh() }
        }
        // Progress of the 4×2 widget: every 10 s while playing and the screen is on.
        scope.launch {
            p.status.collectLatest { st ->
                if (st != Status.Playing) return@collectLatest
                while (true) {
                    delay(PROGRESS_EVERY_MS)
                    if (!screenOn() || !hasWidgets(large = true)) continue
                    val s = snapshot()
                    if (s.progress != _state.value.progress) {
                        _state.value = s
                        pushes.trySend(true)
                    }
                }
            }
        }
        scope.launch(Dispatchers.Default) {
            pushes.receiveAsFlow().collect { largeOnly -> push(largeOnly) }
        }
    }

    /** The player's state now (main thread). */
    private fun snapshot(): WidgetState {
        val m = AppGraph.playback
        val p = m.player
        val t = p.currentTrack
        val likes = m.likes
        val pos = p.position.value
        return WidgetState.of(
            track = t,
            status = p.statusNow,
            isLive = p.isLive,
            liveTitle = p.liveTitle.value,
            positionMs = if (t != null) p.positionMs() else 0,
            durationMs = if (t != null) p.durationMs().takeIf { it > 0 } ?: pos.durationMs else 0,
            liked = t != null && likes != null && likes.isLiked(t),
            canLike = t != null && likes != null && likes.canLike(t),
            shuffle = m.smartShuffle.mode,
        )
    }

    private fun refresh() {
        val s = snapshot()
        val changed = s.content != _state.value.content
        _state.value = s
        if (!changed) return
        val url = s.coverUrl
        if (url == null) {
            coverJob?.cancel()
        } else if (_cover.value?.url != url) {
            loadCover(url)
        }
        pushes.trySend(false)
    }

    private fun loadCover(url: String) {
        coverJob?.cancel()
        coverJob = scope.launch {
            if (!hasWidgets(large = false)) return@launch // loaded on demand by a widget (prepare)
            fetchCover(url)?.let {
                _cover.value = it
                pushes.trySend(false)
            }
        }
    }

    /**
     * Called by a widget before it renders (also in a process started just for the widget): waits briefly for the
     * current cover so the first frame has it.
     */
    suspend fun prepare(context: Context) {
        if (!started) withContext(Dispatchers.Main) { start(context) }
        val url = _state.value.coverUrl ?: return
        if (_cover.value?.url == url) return
        withContext(Dispatchers.Main) { if (coverJob?.isActive != true) coverJob = scope.launch { fetchCover(url)?.let { _cover.value = it } } }
        withTimeoutOrNull(COVER_WAIT_MS) { _cover.first { it?.url == url } }
    }

    private suspend fun fetchCover(url: String): WidgetCover? = try {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(LARGE_PX)
            .precision(Precision.INEXACT)
            .allowHardware(false) // drawn into our own (software) bitmaps
            .build()
        val bmp = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        if (bmp == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                // Covers stay sharp: 6 dp corners at both sizes (48 dp and 96 dp covers).
                WidgetCover(url, rounded(bmp, SMALL_PX, SMALL_PX * 6f / 48f), rounded(bmp, LARGE_PX, LARGE_PX * 6f / 96f))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "cover failed: ${e.message}")
        null
    }

    /** A [size]² center crop of [src] with rounded corners. */
    private fun rounded(src: Bitmap, size: Int, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = size / minOf(src.width, src.height).toFloat()
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((size - src.width * scale) / 2f, (size - src.height * scale) / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { it.setLocalMatrix(m) }
        }
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        return out
    }

    private suspend fun push(largeOnly: Boolean) {
        val widgets: List<GlanceAppWidget> = if (largeOnly) listOf(PlayerWidgetLarge()) else listOf(PlayerWidgetSmall(), PlayerWidgetLarge())
        for (w in widgets) {
            runCatching { if (hasWidgets(w)) w.updateAll(context) }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "update failed: ${it.message}")
            }
        }
    }

    private suspend fun hasWidgets(large: Boolean): Boolean =
        if (large) hasWidgets(PlayerWidgetLarge()) else hasWidgets(PlayerWidgetSmall()) || hasWidgets(PlayerWidgetLarge())

    private suspend fun hasWidgets(w: GlanceAppWidget): Boolean =
        runCatching { GlanceAppWidgetManager(context).getGlanceIds(w.javaClass).isNotEmpty() }.getOrDefault(false)

    private fun screenOn(): Boolean = context.getSystemService(PowerManager::class.java)?.isInteractive != false
}
