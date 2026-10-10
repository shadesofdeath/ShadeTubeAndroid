package com.app.shadetube.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * OKLCH color (Björn Ottosson's OKLab in polar form). [l] 0..1 perceptual lightness, [c] chroma (sRGB colors stay
 * below ~0.37), [h] hue in degrees 0..360. The accent rule (tokens.json → color.accentRule) works in this space
 * because equal L means equal perceived lightness across hues, unlike HSL.
 */
data class Oklch(val l: Float, val c: Float, val h: Float)

private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
private fun linearToSrgb(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

/** sRGB (0..1 gamma-encoded) → OKLab (L, a, b). */
internal fun srgbToOklab(r: Double, g: Double, b: Double): DoubleArray {
    val lr = srgbToLinear(r); val lg = srgbToLinear(g); val lb = srgbToLinear(b)
    val l = cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb)
    val m = cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb)
    val s = cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb)
    return doubleArrayOf(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}

/** OKLab → sRGB (gamma-encoded, NOT clipped: values outside 0..1 mean out of gamut). */
internal fun oklabToSrgb(lL: Double, a: Double, b: Double): DoubleArray {
    val l = (lL + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m = (lL - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s = (lL - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    return doubleArrayOf(
        linearToSrgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
        linearToSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
        linearToSrgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
    )
}

private fun oklchToSrgb(o: Oklch): DoubleArray {
    val hr = Math.toRadians(o.h.toDouble())
    return oklabToSrgb(o.l.toDouble(), o.c * cos(hr), o.c * sin(hr))
}

private const val GAMUT_EPS = 1e-4
private fun DoubleArray.inGamut() = all { it >= -GAMUT_EPS && it <= 1 + GAMUT_EPS }

fun Color.toOklch(): Oklch {
    val lab = srgbToOklab(red.toDouble(), green.toDouble(), blue.toDouble())
    val c = hypot(lab[1], lab[2])
    var h = Math.toDegrees(atan2(lab[2], lab[1]))
    if (h < 0) h += 360.0
    return Oklch(lab[0].toFloat(), c.toFloat(), if (c < 1e-6) 0f else h.toFloat())
}

/** Converts to sRGB. Out-of-gamut colors are mapped by lowering chroma at constant L and hue (CSS Color 4 style). */
fun Oklch.toColor(alpha: Float = 1f): Color {
    val mapped = gamutMapped()
    val rgb = oklchToSrgb(mapped)
    return Color(
        rgb[0].coerceIn(0.0, 1.0).toFloat(), rgb[1].coerceIn(0.0, 1.0).toFloat(), rgb[2].coerceIn(0.0, 1.0).toFloat(), alpha,
    )
}

/** Same L and hue, the largest chroma ≤ [c] that is inside sRGB. */
fun Oklch.gamutMapped(): Oklch {
    if (l <= 0f || l >= 1f) return copy(l = l.coerceIn(0f, 1f), c = 0f)
    if (oklchToSrgb(this).inGamut()) return this
    var lo = 0f
    var hi = c
    repeat(24) {
        val mid = (lo + hi) / 2
        if (oklchToSrgb(copy(c = mid)).inGamut()) lo = mid else hi = mid
    }
    return copy(c = lo)
}

/** WCAG 2 relative luminance. */
fun Color.relativeLuminance(): Double {
    fun ch(v: Float) = srgbToLinear(v.toDouble())
    return 0.2126 * ch(red) + 0.7152 * ch(green) + 0.0722 * ch(blue)
}

/** WCAG 2 contrast ratio (1..21) of two opaque colors. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** tokens.json → accentRule.onAccent: L > 0.60 → ink (#0E0F13), else white. */
fun onAccentFor(accent: Color): Color = if (accent.toOklch().l > ACCENT_ON_INK_L) OnAccentInk else OnAccentWhite

const val ACCENT_MIN_CHROMA = 0.06f
const val ACCENT_ON_INK_L = 0.60f
const val ACCENT_MIN_CONTRAST = 4.5
val ACCENT_L_DARK = 0.72f..0.84f
val ACCENT_L_LIGHT = 0.45f..0.58f

/**
 * The accent rule (tokens.json → color.accentRule, README §1):
 * 1. null or chroma < 0.06 (grey / near-neutral covers) → the neutral [fallback];
 * 2. L is clamped to 0.72–0.84 (dark theme) or 0.45–0.58 (light) — hue and chroma kept, chroma lowered only if the
 *    new lightness pushes the color out of sRGB;
 * 3. on-accent text (ink when L > 0.60, else white) must reach 4.5:1: if it does not (possible near the light range's
 *    top for some hues), L is moved away from the text color in 0.005 steps until it does.
 */
fun clampAccent(raw: Color?, dark: Boolean, fallback: Color): Color {
    if (raw == null) return fallback
    val ok = raw.toOklch()
    if (ok.c < ACCENT_MIN_CHROMA) return fallback
    val range = if (dark) ACCENT_L_DARK else ACCENT_L_LIGHT
    var o = ok.copy(l = ok.l.coerceIn(range.start, range.endInclusive)).gamutMapped()
    var color = o.toColor()
    val on = onAccentFor(color)
    val step = if (on == OnAccentInk) 0.005f else -0.005f
    var guard = 0
    while (contrastRatio(color, on) < ACCENT_MIN_CONTRAST && guard++ < 60) {
        o = o.copy(l = (o.l + step).coerceIn(0.02f, 0.98f), c = ok.c).gamutMapped()
        color = o.toColor()
    }
    return color
}
