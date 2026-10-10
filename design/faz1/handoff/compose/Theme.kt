package app.shadetube.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import app.shadetube.R

// ---------- Nötr paletler (tokens.json → color.dark / color.light) ----------
object ShadeDark {
    val surface0 = Color(0xFF0B0C10); val surface1 = Color(0xFF12141A)
    val surface2 = Color(0xFF1A1D25); val surface3 = Color(0xFF232732)
    val outline = Color(0xFF2B303C); val outlineVariant = Color(0xFF1F232D)
    val text1 = Color(0xFFF3F1EA); val text2 = Color(0xFFA6AAB6); val text3 = Color(0xFF6E7382)
    val error = Color(0xFFFF6B6B); val onError = Color(0xFF1A0B0B)
    val warning = Color(0xFFF0B35A); val success = Color(0xFF6FCF97)
    val accentFallback = Color(0xFFD8D6CF)
}
object ShadeLight {
    val surface0 = Color(0xFFF7F6F1); val surface1 = Color(0xFFFFFFFF)
    val surface2 = Color(0xFFEEEDE7); val surface3 = Color(0xFFE3E1DA)
    val outline = Color(0xFFD3D1C9); val outlineVariant = Color(0xFFE6E4DD)
    val text1 = Color(0xFF15171D); val text2 = Color(0xFF5B606D); val text3 = Color(0xFF8A8F9C)
    val error = Color(0xFFB3261E); val onError = Color.White
    val warning = Color(0xFF8A5A00); val success = Color(0xFF2E7D4F)
    val accentFallback = Color(0xFF4A4F5C)
}

// ---------- Vurgu modu ----------
enum class AccentMode { FromArtwork, Fixed, MaterialYou }

/** Material'de karşılığı olmayan türetilmiş vurgu tonları (tokens.json → accentRule.derived). */
@Immutable
data class ShadeAccent(
    val accent: Color,
    val onAccent: Color,
    val container: Color,   // accent @18% over surface1
    val glow: Color,        // accent @35%
    val progressTrack: Color, // accent @24% (tampon bandı)
    val outline: Color,     // accent @50%
) {
    companion object {
        fun from(accent: Color, surface1: Color, dark: Boolean): ShadeAccent {
            val l = accent.luminance()
            val on = if (l > 0.36f) Color(0xFF0E0F13) else Color.White // ~OKLCH L 0.60 eşiği
            return ShadeAccent(
                accent = accent,
                onAccent = on,
                container = accent.copy(alpha = 0.18f).compositeOver(surface1),
                glow = accent.copy(alpha = 0.35f),
                progressTrack = accent.copy(alpha = 0.24f),
                outline = accent.copy(alpha = 0.50f),
            )
        }
    }
}

/** Kapaktan çıkan rengi sınırlara çeker (tokens.json → accentRule). OKLCH dönüşümü için ör. `com.github.ajalt.colormath`. */
fun clampAccent(raw: Color?, dark: Boolean, fallback: Color): Color {
    if (raw == null) return fallback
    val ok = raw.toOklch()                   // (L, C, h) — projede colormath ile uygulanır
    if (ok.c < 0.06f) return fallback
    val (minL, maxL) = if (dark) 0.72f to 0.84f else 0.45f to 0.58f
    return ok.copy(l = ok.l.coerceIn(minL, maxL)).toColor()
}

val LocalShadeAccent = staticCompositionLocalOf { ShadeAccent.from(Color(0xFFF0B35A), ShadeDark.surface1, true) }
val LocalShadeExtras = staticCompositionLocalOf { ShadeExtras.dark() }

/** Material ColorScheme dışındaki yüzey/metin tonları. */
@Immutable
data class ShadeExtras(
    val surface0: Color, val surface1: Color, val surface2: Color, val surface3: Color,
    val text1: Color, val text2: Color, val text3: Color,
    val warning: Color, val success: Color,
    val miniPlayerSurface: Color,
) {
    companion object {
        fun dark() = ShadeExtras(ShadeDark.surface0, ShadeDark.surface1, ShadeDark.surface2, ShadeDark.surface3,
            ShadeDark.text1, ShadeDark.text2, ShadeDark.text3, ShadeDark.warning, ShadeDark.success,
            ShadeDark.surface3.copy(alpha = 0.90f))
        fun light() = ShadeExtras(ShadeLight.surface0, ShadeLight.surface1, ShadeLight.surface2, ShadeLight.surface3,
            ShadeLight.text1, ShadeLight.text2, ShadeLight.text3, ShadeLight.warning, ShadeLight.success,
            ShadeLight.surface1.copy(alpha = 0.92f))
    }
}

// ---------- Tipografi ----------
val Bricolage = FontFamily(
    Font(R.font.bricolage_grotesque_regular, FontWeight.Normal),
    Font(R.font.bricolage_grotesque_medium, FontWeight.Medium),
    Font(R.font.bricolage_grotesque_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold),
    Font(R.font.bricolage_grotesque_extrabold, FontWeight.ExtraBold),
)
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
    Font(R.font.instrument_sans_bold, FontWeight.Bold),
)
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

private val noPad = PlatformTextStyle(includeFontPadding = false)
private val lhs = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
private fun st(f: FontFamily, size: Int, w: FontWeight, lh: Int, ls: Float = 0f) =
    TextStyle(fontFamily = f, fontSize = size.sp, fontWeight = w, lineHeight = lh.sp, letterSpacing = ls.sp,
        platformStyle = noPad, lineHeightStyle = lhs)

val ShadeTypography = Typography(
    displayLarge = st(Bricolage, 44, FontWeight.ExtraBold, 48, -1.5f),
    displayMedium = st(Bricolage, 34, FontWeight.ExtraBold, 38, -1.0f),
    headlineLarge = st(Bricolage, 28, FontWeight.Bold, 32, -0.6f),
    headlineMedium = st(Bricolage, 24, FontWeight.Bold, 28, -0.5f),
    headlineSmall = st(Bricolage, 20, FontWeight.Bold, 24, -0.3f),
    titleLarge = st(InstrumentSans, 17, FontWeight.SemiBold, 24),
    titleMedium = st(InstrumentSans, 15, FontWeight.SemiBold, 20),
    titleSmall = st(InstrumentSans, 14, FontWeight.SemiBold, 20),
    bodyLarge = st(InstrumentSans, 16, FontWeight.Normal, 24),
    bodyMedium = st(InstrumentSans, 14, FontWeight.Normal, 20),
    bodySmall = st(InstrumentSans, 12, FontWeight.Normal, 16),
    labelLarge = st(InstrumentSans, 14, FontWeight.SemiBold, 20),
    labelMedium = st(InstrumentSans, 12, FontWeight.SemiBold, 16, 0.1f),
    labelSmall = st(InstrumentSans, 11, FontWeight.SemiBold, 16, 0.2f),
)
/** Teknik metin: süre, bitrate, sayaç. fontFeatureSettings = "tnum". */
object ShadeMono {
    val large = st(JetBrainsMono, 18, FontWeight.Medium, 24).copy(fontFeatureSettings = "tnum")
    val medium = st(JetBrainsMono, 13, FontWeight.Normal, 16).copy(fontFeatureSettings = "tnum")
    val small = st(JetBrainsMono, 11, FontWeight.Medium, 14, 0.4f).copy(fontFeatureSettings = "tnum")
    val overline = st(JetBrainsMono, 10, FontWeight.Medium, 14, 1.2f)
}

// ---------- Şekiller ----------
val ShadeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),   // kapak
    small = RoundedCornerShape(10.dp),       // çip
    medium = RoundedCornerShape(12.dp),      // kart
    large = RoundedCornerShape(20.dp),       // panel
    extraLarge = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), // bottom sheet
)
val PillShape = RoundedCornerShape(50)

// ---------- Hareket ----------
object ShadeMotion {
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val SHORT = 150; const val STANDARD = 250; const val MEDIUM = 400; const val LONG = 600
    fun <T> dragRelease() = spring<T>(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> sheetSnap() = spring<T>(dampingRatio = 0.85f, stiffness = 300f)
    fun <T> heartPop() = spring<T>(dampingRatio = 0.45f, stiffness = 600f)
}

// ---------- Tema ----------
@Composable
fun ShadeTubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentMode: AccentMode = AccentMode.FromArtwork,
    artworkAccent: Color? = null,      // Palette'ten gelen ham renk
    fixedAccent: Color = Color(0xFFF0B35A),
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val extras = if (darkTheme) ShadeExtras.dark() else ShadeExtras.light()
    val fallback = if (darkTheme) ShadeDark.accentFallback else ShadeLight.accentFallback
    val target = when (accentMode) {
        AccentMode.FromArtwork -> clampAccent(artworkAccent, darkTheme, fallback)
        AccentMode.Fixed -> clampAccent(fixedAccent, darkTheme, fallback)
        AccentMode.MaterialYou -> dynamicAccentOrNull()?.let { clampAccent(it, darkTheme, fallback) } ?: fallback
    }
    val animated by animateColorAsState(target, tween(if (reduceMotion) 0 else ShadeMotion.LONG, easing = ShadeMotion.standard), label = "accent")
    val accent = ShadeAccent.from(animated, extras.surface1, darkTheme)

    val scheme = if (darkTheme) darkColorScheme(
        primary = accent.accent, onPrimary = accent.onAccent,
        primaryContainer = accent.container, onPrimaryContainer = extras.text1,
        secondary = extras.text2, onSecondary = extras.surface0,
        background = extras.surface0, onBackground = extras.text1,
        surface = extras.surface0, onSurface = extras.text1,
        surfaceVariant = extras.surface2, onSurfaceVariant = extras.text2,
        surfaceContainerLowest = extras.surface0, surfaceContainerLow = extras.surface1,
        surfaceContainer = extras.surface2, surfaceContainerHigh = extras.surface3, surfaceContainerHighest = ShadeDark.outline,
        outline = ShadeDark.outline, outlineVariant = ShadeDark.outlineVariant,
        error = ShadeDark.error, onError = ShadeDark.onError, scrim = Color.Black,
    ) else lightColorScheme(
        primary = accent.accent, onPrimary = accent.onAccent,
        primaryContainer = accent.container, onPrimaryContainer = extras.text1,
        secondary = extras.text2, onSecondary = extras.surface1,
        background = extras.surface0, onBackground = extras.text1,
        surface = extras.surface0, onSurface = extras.text1,
        surfaceVariant = extras.surface2, onSurfaceVariant = extras.text2,
        surfaceContainerLowest = extras.surface1, surfaceContainerLow = extras.surface0,
        surfaceContainer = extras.surface2, surfaceContainerHigh = extras.surface3, surfaceContainerHighest = ShadeLight.outline,
        outline = ShadeLight.outline, outlineVariant = ShadeLight.outlineVariant,
        error = ShadeLight.error, onError = ShadeLight.onError, scrim = Color.Black,
    )

    CompositionLocalProvider(LocalShadeAccent provides accent, LocalShadeExtras provides extras) {
        MaterialTheme(colorScheme = scheme, typography = ShadeTypography, shapes = ShadeShapes, content = content)
    }
}

object ShadeTheme {
    val accent: ShadeAccent @Composable get() = LocalShadeAccent.current
    val extras: ShadeExtras @Composable get() = LocalShadeExtras.current
}

// Projede uygulanacak yardımcılar (imza sabit, gövde ekip tercihine göre):
// fun Color.toOklch(): Oklch ; fun Oklch.toColor(): Color ; data class Oklch(val l: Float, val c: Float, val h: Float)
// @Composable fun dynamicAccentOrNull(): Color? = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(LocalContext.current).primary else null
