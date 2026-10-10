package com.app.shadetube.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class OklchTest {

    private fun close(expected: Float, actual: Float, eps: Float, msg: String = "") =
        assertTrue("$msg expected $expected got $actual", abs(expected - actual) <= eps)

    @Test
    fun knownValues() {
        // Reference values from Björn Ottosson's OKLab definition (also what CSS oklch() reports).
        Color.White.toOklch().let { close(1f, it.l, 1e-3f); close(0f, it.c, 1e-3f) }
        Color.Black.toOklch().let { close(0f, it.l, 1e-3f); close(0f, it.c, 1e-3f) }
        Color(0xFFFF0000).toOklch().let { close(0.62796f, it.l, 1e-3f); close(0.25768f, it.c, 1e-3f); close(29.234f, it.h, 0.1f) }
        Color(0xFF00FF00).toOklch().let { close(0.86644f, it.l, 1e-3f); close(0.29483f, it.c, 1e-3f); close(142.495f, it.h, 0.1f) }
        Color(0xFF0000FF).toOklch().let { close(0.45201f, it.l, 1e-3f); close(0.31321f, it.c, 1e-3f); close(264.052f, it.h, 0.1f) }
    }

    @Test
    fun roundTripIsLossless() {
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val c = Color(r / 255f, g / 255f, b / 255f)
            val back = c.toOklch().toColor()
            val msg = "rgb($r,$g,$b)"
            close(c.red, back.red, 1.5f / 255, msg)
            close(c.green, back.green, 1.5f / 255, msg)
            close(c.blue, back.blue, 1.5f / 255, msg)
        }
    }

    @Test
    fun outOfGamutIsMappedByChroma() {
        val o = Oklch(0.8f, 0.4f, 264f) // far outside sRGB
        val mapped = o.gamutMapped()
        close(0.8f, mapped.l, 1e-6f)
        close(264f, mapped.h, 1e-6f)
        assertTrue(mapped.c < 0.4f && mapped.c > 0f)
        val c = o.toColor()
        close(0.8f, c.toOklch().l, 0.01f)
    }

    @Test
    fun clampAccentKeepsLightnessInRange() {
        val rnd = Random(42)
        repeat(4000) {
            val raw = Color(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
            for (dark in listOf(true, false)) {
                val fallback = if (dark) ShadeDark.accentFallback else ShadeLight.accentFallback
                val out = clampAccent(raw, dark, fallback)
                if (raw.toOklch().c < ACCENT_MIN_CHROMA) {
                    assertEquals(fallback, out)
                    continue
                }
                val range = if (dark) ACCENT_L_DARK else ACCENT_L_LIGHT
                val l = out.toOklch().l
                assertTrue("L $l out of $range for $raw dark=$dark", l >= range.start - 0.005f && l <= range.endInclusive + 0.005f)
            }
        }
    }

    @Test
    fun clampAccentKeepsHue() {
        val raw = Color(0xFF5FD4C4) // turquoise preset
        val inH = raw.toOklch().h
        close(inH, clampAccent(raw, dark = true, fallback = ShadeDark.accentFallback).toOklch().h, 2f)
        close(inH, clampAccent(raw, dark = false, fallback = ShadeLight.accentFallback).toOklch().h, 2f)
    }

    @Test
    fun neutralAndNullFallBack() {
        assertEquals(ShadeDark.accentFallback, clampAccent(null, true, ShadeDark.accentFallback))
        assertEquals(ShadeDark.accentFallback, clampAccent(Color(0xFF808080), true, ShadeDark.accentFallback))
        assertEquals(ShadeLight.accentFallback, clampAccent(Color(0xFFB8BCC8), false, ShadeLight.accentFallback))
    }

    @Test
    fun onAccentContrastIsAtLeast4_5() {
        val rnd = Random(7)
        val samples = List(4000) { Color(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()) } +
            AccentPresets.all.map { it.second } + listOf(BrandAmber, Color(0xFFDDFF47))
        for (raw in samples) for (dark in listOf(true, false)) {
            val fallback = if (dark) ShadeDark.accentFallback else ShadeLight.accentFallback
            val accent = clampAccent(raw, dark, fallback)
            val on = onAccentFor(accent)
            val ratio = contrastRatio(accent, on)
            assertTrue("contrast $ratio for $raw → $accent (dark=$dark)", ratio >= ACCENT_MIN_CONTRAST)
            // tokens: L > 0.60 → ink, else white; dark accents are light (ink), light-theme accents dark (white)
            assertEquals(if (dark) OnAccentInk else OnAccentWhite, on)
        }
    }

    @Test
    fun derivedTones() {
        val a = ShadeAccent.from(Color(0xFFF0B35A), ShadeDark.surface1)
        close(0.35f, a.glow.alpha, 0.01f)
        close(0.24f, a.progressTrack.alpha, 0.01f)
        close(0.5f, a.outline.alpha, 0.01f)
        close(1f, a.container.alpha, 0.001f) // composited over surface1
    }

    @Test
    fun contrastRatioReference() {
        close(21f, contrastRatio(Color.Black, Color.White).toFloat(), 0.01f)
        close(1f, contrastRatio(Color.White, Color.White).toFloat(), 0.001f)
    }

    @Test
    fun parseHex() {
        assertEquals(Color(0xFFDDFF47), parseHexColor("#DDFF47"))
        assertEquals(Color(0x80DDFF47), parseHexColor("80DDFF47"))
        assertNull(parseHexColor("#XYZ"))
        assertNull(parseHexColor(""))
        assertEquals("#DDFF47", Color(0xFFDDFF47).toHex())
    }
}
