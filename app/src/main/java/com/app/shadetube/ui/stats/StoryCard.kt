package com.app.shadetube.ui.stats

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.app.shadetube.R
import java.io.File

/** What the 9:16 "Yıl özeti" story shows (texts already localized). */
data class StoryData(
    val year: Int,
    val overline: String,
    val minutes: String,
    val minutesLabel: String,
    val countsLine: String,
    val topSongsTitle: String,
    val topSongs: List<Pair<String, String>>,
    val topArtistTitle: String,
    val topArtist: String,
    val footer: String,
)

/**
 * Renders the shareable story card (SCREENS 20: surface0 background, accent glow, crescent mark, displayLarge
 * numbers, top 5 list) to a 1080×1920 bitmap with the app's fonts (variable TTFs, weight set per paint).
 */
object StoryCard {
    const val WIDTH = 1080
    const val HEIGHT = 1920

    private const val SURFACE0 = 0xFF0B0C10.toInt()
    private const val TEXT1 = 0xFFF3F1EA.toInt()
    private const val TEXT2 = 0xFFA6AAB6.toInt()
    private const val TEXT3 = 0xFF6E7382.toInt()

    private fun font(context: Context, res: Int): Typeface = runCatching { ResourcesCompat.getFont(context, res) }.getOrNull() ?: Typeface.DEFAULT

    private fun paint(tf: Typeface, sizePx: Float, color: Int, weight: Int, extra: String = ""): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = sizePx
            this.color = color
            setFontVariationSettings("'wght' $weight" + if (extra.isNotEmpty()) ", $extra" else "")
        }

    fun render(context: Context, d: StoryData, accent: Int): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val brico = font(context, R.font.bricolage_grotesque)
        val sans = font(context, R.font.instrument_sans)
        val mono = font(context, R.font.jetbrains_mono)
        c.drawColor(SURFACE0)
        // Accent glow (accent @35 % -> transparent).
        val glow = (accent and 0x00FFFFFF) or (0x59 shl 24)
        val g = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(WIDTH * 0.62f, 700f, 900f, glow, accent and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), g)

        val left = 96f
        val right = WIDTH - 96f
        // Crescent mark (logo_mark geometry: circle(32,32,26) − circle(41,26,21) − dot(24,38,5) in a 64 box).
        val k = 96f / 64f
        val ox = left
        val oy = 120f
        val crescent = Path().apply { addCircle(ox + 32 * k, oy + 32 * k, 26 * k, Path.Direction.CW) }
        crescent.op(Path().apply { addCircle(ox + 41 * k, oy + 26 * k, 21 * k, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        crescent.op(Path().apply { addCircle(ox + 24 * k, oy + 38 * k, 5 * k, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        c.drawPath(crescent, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        val shade = paint(brico, 64f, TEXT1, 800, "'opsz' 64")
        val tube = paint(brico, 64f, TEXT2, 400, "'opsz' 64")
        val wordY = oy + 48 * k + 22f
        c.drawText("Shade", left + 120f, wordY, shade)
        c.drawText("Tube", left + 120f + shade.measureText("Shade"), wordY, tube)

        // Overline + year.
        var y = 470f
        val over = paint(mono, 34f, accent, 600).apply { letterSpacing = 0.12f }
        c.drawText(d.overline.uppercase(), left, y, over)
        y += 210f
        c.drawText(d.year.toString(), left - 8f, y, paint(brico, 230f, TEXT1, 800, "'opsz' 96").apply { letterSpacing = -0.03f })

        // Minutes.
        y += 200f
        c.drawText(d.minutes, left, y, paint(mono, 150f, accent, 700).apply { letterSpacing = -0.04f })
        y += 70f
        c.drawText(d.minutesLabel, left, y, paint(sans, 46f, TEXT2, 500))
        y += 64f
        c.drawText(TextUtils.ellipsize(d.countsLine, paint(mono, 36f, TEXT2, 500), right - left, TextUtils.TruncateAt.END).toString(), left, y, paint(mono, 36f, TEXT2, 500))

        // Top 5 songs.
        y += 130f
        c.drawText(d.topSongsTitle, left, y, paint(brico, 56f, TEXT1, 700, "'opsz' 56"))
        y += 30f
        val num = paint(mono, 44f, accent, 600)
        val titleP = paint(sans, 46f, TEXT1, 600)
        val artistP = paint(sans, 34f, TEXT2, 400)
        val textLeft = left + 90f
        for ((i, s) in d.topSongs.take(5).withIndex()) {
            y += 100f
            c.drawText("%02d".format(i + 1), left, y, num)
            c.drawText(TextUtils.ellipsize(s.first, titleP, right - textLeft, TextUtils.TruncateAt.END).toString(), textLeft, y - 8f, titleP)
            c.drawText(TextUtils.ellipsize(s.second, artistP, right - textLeft, TextUtils.TruncateAt.END).toString(), textLeft, y + 38f, artistP)
        }

        // Artist of the year.
        if (d.topArtist.isNotEmpty()) {
            y += 150f
            c.drawText(d.topArtistTitle.uppercase(), left, y, over)
            y += 80f
            val ap = paint(brico, 72f, TEXT1, 800, "'opsz' 72")
            c.drawText(TextUtils.ellipsize(d.topArtist, ap, right - left, TextUtils.TruncateAt.END).toString(), left, y, ap)
        }

        // Footer.
        c.drawText(d.footer, left, HEIGHT - 96f, paint(mono, 30f, TEXT3, 500).apply { letterSpacing = 0.1f })
        return bmp
    }

    /**
     * Writes [bmp] as a PNG into `cacheDir/shared` (older story images there are removed) and returns its FileProvider
     * content Uri (`${applicationId}.fileprovider`, res/xml/file_paths.xml); null on failure. Nothing is written to
     * the gallery. Call off the main thread.
     */
    fun saveForSharing(context: Context, bmp: Bitmap, year: Int): Uri? = runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("ShadeTube-") && f.name.endsWith(".png") }?.forEach { it.delete() }
        val file = File(dir, "ShadeTube-$year.png")
        file.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) error("compress failed") }
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull()

    /** ACTION_SEND chooser for the saved image (main thread). */
    fun share(context: Context, uri: Uri, chooserTitle: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri) // grants the chooser preview read access too
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(send, chooserTitle))
            true
        }.getOrDefault(false)
    }
}
