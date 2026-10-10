package com.app.shadetube.ui.theme

import kotlin.math.abs
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Raw accent color of a cover (tokens.json → color.accentRule.extraction): Palette's Vibrant → DarkVibrant → Muted
 * swatch, skipping swatches that cover less than 8 % of the image. The result is *raw*: [ShadeTubeTheme] clamps it
 * ([clampAccent]: OKLCH lightness range per theme, neutral fallback below chroma 0.06).
 *
 * Covers are decoded small (112 px, software bitmap) through Coil's singleton loader (so its memory/disk caches are
 * shared with the artwork on screen), off the main thread; results are memoized per URL in an LRU cache.
 */
object ArtworkAccent {
    const val MIN_COVERAGE = 0.08f
    private const val SAMPLE_PX = 112
    private const val NONE = 0 // cache marker for "cover has no usable swatch" (alpha 0 never comes out of Palette)

    private val cache = LruCache<String, Int>(128)

    /** Cached result without loading: null = not cached, Color.Unspecified = cached "no accent". */
    fun cached(url: String): Color? = cache.get(url)?.let { if (it == NONE) Color.Unspecified else Color(it) }

    /** Loads [url] and extracts its accent; null when the cover fails to load or has no qualifying swatch. Main-safe. */
    suspend fun extract(context: Context, url: String): Color? {
        cache.get(url)?.let { return if (it == NONE) null else Color(it) }
        val bitmap = try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(SAMPLE_PX)
                .precision(Precision.INEXACT)
                .allowHardware(false) // Palette reads pixels
                .build()
            val result = SingletonImageLoader.get(context).execute(request)
            (result as? SuccessResult)?.image?.toBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        val argb = withContext(Dispatchers.Default) { pick(bitmap) }
        cache.put(url, argb ?: NONE)
        return argb?.let { Color(it) }
    }

    /** Palette rule on an already decoded bitmap (ARGB int or null). */
    fun pick(bitmap: Bitmap): Int? {
        val area = bitmap.width * bitmap.height
        if (area <= 0) return null
        val palette = Palette.from(bitmap)
            .resizeBitmapArea(area) // keep the sample as is, so populations are fractions of `area`
            .maximumColorCount(16)
            .generate()
        val total = area.toFloat()
        val all = palette.swatches
        // Coverage of a swatch = its hue family: Palette splits one colour area of the cover into several quantized
        // boxes (16 here), so a single box rarely reaches 8 % even when that colour dominates the cover.
        fun coverage(target: Palette.Swatch): Float {
            val h = target.hsl
            val family = all.filter { s ->
                val o = s.hsl
                val dh = abs(o[0] - h[0]).let { minOf(it, 360f - it) }
                dh <= HUE_FAMILY && o[1] >= MIN_FAMILY_SAT
            }
            return (family.sumOf { it.population } .coerceAtLeast(target.population)) / total
        }
        return listOf(palette.vibrantSwatch, palette.darkVibrantSwatch, palette.mutedSwatch)
            .firstOrNull { it != null && coverage(it) >= MIN_COVERAGE }
            ?.rgb
    }

    /** Hue distance (degrees) and minimum saturation of the swatches counted as the same colour as the candidate. */
    private const val HUE_FAMILY = 24f
    private const val MIN_FAMILY_SAT = 0.15f

    fun clear() = cache.evictAll()
}

/**
 * The raw accent of the cover at [url] for [ShadeTubeTheme]'s `artworkAccent`. Keeps the previous color while a new
 * cover loads (so the accent glides from one track to the next instead of flashing the neutral fallback); null when
 * [url] is null/empty or the cover has no usable color.
 *
 * ```
 * val accent by rememberArtworkAccent(nowPlaying?.coverUrl)
 * ShadeTubeTheme(artworkAccent = accent) { AppRoot() }
 * ```
 */
@Composable
fun rememberArtworkAccent(url: String?): State<Color?> {
    val context = LocalContext.current.applicationContext
    val state: MutableState<Color?> = remember {
        mutableStateOf(url?.let { ArtworkAccent.cached(it) }?.takeIf { it != Color.Unspecified })
    }
    LaunchedEffect(url) {
        state.value = if (url.isNullOrEmpty()) null else ArtworkAccent.extract(context, url)
    }
    return state
}
