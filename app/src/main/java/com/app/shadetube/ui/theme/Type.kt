@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.app.shadetube.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.app.shadetube.R

/*
 * Fonts (all SIL OFL 1.1, license text in res/raw/ofl.txt) are the variable TTFs, instanced at runtime with
 * FontVariation (API 26+, minSdk is 28):
 *   res/font/bricolage_grotesque.ttf  axes opsz 12..96, wght 200..800, wdth 75..100   (display / headlines)
 *   res/font/instrument_sans.ttf      axes wght 400..700, wdth 75..100                (body / labels)
 *   res/font/jetbrains_mono.ttf       axis wght 100..800                              (durations, bitrate, counters)
 * Bricolage's optical size follows the text size like the web design (font-optical-sizing: auto → opsz = px size).
 */

private val bricolageCache = HashMap<Int, FontFamily>()

/** Bricolage Grotesque with its optical-size axis pinned to [opsz] (≈ the text size in sp; clamped to 12..96). */
fun bricolage(opsz: Float): FontFamily = synchronized(bricolageCache) {
    val key = opsz.coerceIn(12f, 96f).toInt()
    bricolageCache.getOrPut(key) {
        FontFamily(
            listOf(400, 500, 600, 700, 800).map { w ->
                Font(
                    R.font.bricolage_grotesque, FontWeight(w),
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(w), FontVariation.Setting("opsz", key.toFloat()),
                    ),
                )
            },
        )
    }
}

/** Bricolage at a medium optical size, for ad-hoc display text outside the type scale. */
val Bricolage: FontFamily get() = bricolage(28f)

val InstrumentSans = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.instrument_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

val JetBrainsMono = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.jetbrains_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private val noPad = PlatformTextStyle(includeFontPadding = false)
private val lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, size: Float, weight: Int, lineHeight: Float, letterSpacing: Float = 0f) =
    TextStyle(
        fontFamily = family, fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing.sp, platformStyle = noPad, lineHeightStyle = lineHeightStyle,
    )

/** tokens.json → typography.scale (sp). */
val ShadeTypography = Typography(
    displayLarge = style(bricolage(44f), 44f, 800, 48f, -1.5f),
    displayMedium = style(bricolage(34f), 34f, 800, 38f, -1.0f),
    displaySmall = style(bricolage(30f), 30f, 800, 34f, -0.8f), // not in tokens; used by episode detail (SCREENS 17)
    headlineLarge = style(bricolage(28f), 28f, 700, 32f, -0.6f),
    headlineMedium = style(bricolage(24f), 24f, 700, 28f, -0.5f),
    headlineSmall = style(bricolage(20f), 20f, 700, 24f, -0.3f),
    titleLarge = style(InstrumentSans, 17f, 600, 24f),
    titleMedium = style(InstrumentSans, 15f, 600, 20f),
    titleSmall = style(InstrumentSans, 14f, 600, 20f),
    bodyLarge = style(InstrumentSans, 16f, 400, 24f),
    bodyMedium = style(InstrumentSans, 14f, 400, 20f),
    bodySmall = style(InstrumentSans, 12f, 400, 16f),
    labelLarge = style(InstrumentSans, 14f, 600, 20f),
    labelMedium = style(InstrumentSans, 12f, 600, 16f, 0.1f),
    labelSmall = style(InstrumentSans, 11f, 600, 16f, 0.2f),
)

/** Technical text: durations, bitrate, counters (JetBrains Mono, tabular figures). `MaterialTheme` has no slot for it. */
object ShadeMono {
    val large = style(JetBrainsMono, 18f, 500, 24f).copy(fontFeatureSettings = "tnum")
    val medium = style(JetBrainsMono, 13f, 400, 16f).copy(fontFeatureSettings = "tnum")
    val small = style(JetBrainsMono, 11f, 500, 14f, 0.4f).copy(fontFeatureSettings = "tnum")

    /** Upper-case it at the call site (`text.uppercase(locale)`); tokens: overline 10/14, +1.2 tracking. */
    val overline = style(JetBrainsMono, 10f, 500, 14f, 1.2f)

    /** Statistics hero number (SCREENS 20: displayLarge in mono, 44). */
    val display = style(JetBrainsMono, 44f, 600, 48f, -1f).copy(fontFeatureSettings = "tnum")
}

/** Off-scale sizes the hi-fi screens use (REDLINES). Prefer the Material scale; these exist so screens stay exact. */
object ShadeTextStyles {
    /** Cover tile title 12.5/600 (COMPONENTS: Kapak karosu). */
    val tileTitle = style(InstrumentSans, 12.5f, 600, 16f)

    /** Cover tile subtitle 11.5 text3. */
    val tileSubtitle = style(InstrumentSans, 11.5f, 400, 15f)

    /** Recent tile title 13/600. */
    val recentTitle = style(InstrumentSans, 13f, 600, 17f)

    /** "Made for you" card title 17/700 Bricolage, 2 lines. */
    val cardTitle = style(bricolage(17f), 17f, 700, 20f, -0.3f)

    /** Now Playing title 22/700 (REDLINES: headlineSmall 22). */
    val nowPlayingTitle = style(bricolage(22f), 22f, 700, 26f, -0.3f)

    /** Playlist hero title 26/700. */
    val heroTitle = style(bricolage(26f), 26f, 700, 30f, -0.5f)

    /** Segment label 13 (selected 600). */
    val segment = style(InstrumentSans, 13f, 500, 18f)

    /** Suggested badge: overline 9.5 mono. */
    val badge = style(JetBrainsMono, 9.5f, 600, 12f, 1f)
}
