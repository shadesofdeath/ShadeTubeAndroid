package com.app.shadetube.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.core.AccentMode
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.ThemeMode

/**
 * Accent tones Material has no slot for (tokens.json → accentRule.derived). The accent never fills large areas:
 * only the primary button, active icons, progress, the selected nav indicator, badges and the glow.
 */
@Immutable
data class ShadeAccent(
    val accent: Color,
    val onAccent: Color,
    /** accent @18 % over surface1 — selected chip / nav indicator / suggested badge background. */
    val container: Color,
    /** accent @35 % — cover glow / colored shadows. */
    val glow: Color,
    /** accent @24 % — the buffered band of progress bars, ring tracks. */
    val progressTrack: Color,
    /** accent @50 % — focus rings, selected chip border (used at 25 % / 50 % via copy(alpha)). */
    val outline: Color,
) {
    companion object {
        fun from(accent: Color, surface1: Color): ShadeAccent = ShadeAccent(
            accent = accent,
            onAccent = onAccentFor(accent),
            container = accent.copy(alpha = 0.18f).compositeOver(surface1),
            glow = accent.copy(alpha = 0.35f),
            progressTrack = accent.copy(alpha = 0.24f),
            outline = accent.copy(alpha = 0.50f),
        )
    }
}

/** Surface / text tones outside the Material ColorScheme. Text is always fully opaque (text1/2/3), never alpha-faded. */
@Immutable
data class ShadeExtras(
    val isDark: Boolean,
    val surface0: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val outline: Color,
    val outlineVariant: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val scrim: Color,
    val error: Color,
    val onError: Color,
    val warning: Color,
    val success: Color,
    val accentFallback: Color,
    /**
     * Mini player background: surface3 dark / surface1 light at 96 %. The hi-fi uses 90 % over a 20dp backdrop blur;
     * Compose has no backdrop blur, and 90 % without it lets busy covers show through, so it is closer to the design
     * fallback (plain surface3).
     */
    val miniPlayerSurface: Color,
) {
    companion object {
        fun dark() = ShadeExtras(
            true, ShadeDark.surface0, ShadeDark.surface1, ShadeDark.surface2, ShadeDark.surface3,
            ShadeDark.outline, ShadeDark.outlineVariant, ShadeDark.text1, ShadeDark.text2, ShadeDark.text3,
            ShadeDark.scrim, ShadeDark.error, ShadeDark.onError, ShadeDark.warning, ShadeDark.success,
            ShadeDark.accentFallback, ShadeDark.surface3.copy(alpha = 0.96f),
        )

        fun light() = ShadeExtras(
            false, ShadeLight.surface0, ShadeLight.surface1, ShadeLight.surface2, ShadeLight.surface3,
            ShadeLight.outline, ShadeLight.outlineVariant, ShadeLight.text1, ShadeLight.text2, ShadeLight.text3,
            ShadeLight.scrim, ShadeLight.error, ShadeLight.onError, ShadeLight.warning, ShadeLight.success,
            ShadeLight.accentFallback, ShadeLight.surface1.copy(alpha = 0.96f),
        )
    }
}

val LocalShadeAccent = staticCompositionLocalOf { ShadeAccent.from(clampAccent(BrandAmber, true, ShadeDark.accentFallback), ShadeDark.surface1) }
val LocalShadeExtras = staticCompositionLocalOf { ShadeExtras.dark() }

/** Accessors: `ShadeTheme.accent.accent`, `ShadeTheme.extras.text2`, `ShadeTheme.reduceMotion`. */
object ShadeTheme {
    val accent: ShadeAccent
        @Composable @ReadOnlyComposable get() = LocalShadeAccent.current
    val extras: ShadeExtras
        @Composable @ReadOnlyComposable get() = LocalShadeExtras.current
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/** Material You primary (Android 12+ wallpaper colors), or null below API 31. */
@Composable
fun dynamicAccentOrNull(dark: Boolean = true): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return remember(context, dark) {
        (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    }
}

/**
 * The app theme, driven by [SettingsStore.flow]: theme mode (Dark / Light / System), accent mode (Dynamic = from the
 * playing track's artwork, Fixed = [com.app.shadetube.core.Settings.fixedAccent], System = Material You), reduce
 * motion. [artworkAccent] is the raw color extracted from the current cover — feed it from
 * [rememberArtworkAccent] with the playing track's cover URL at the app root.
 */
@Composable
fun ShadeTubeTheme(
    artworkAccent: Color? = null,
    content: @Composable () -> Unit,
) {
    val settings by SettingsStore.flow.collectAsStateWithLifecycle()
    val dark = when (settings.theme) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    ShadeTubeThemeOf(
        darkTheme = dark,
        accentMode = settings.accentMode,
        artworkAccent = artworkAccent,
        fixedAccent = parseHexColor(settings.fixedAccent) ?: BrandAmber,
        reduceMotion = settings.reduceMotion,
        content = content,
    )
}

/** Explicit-parameter variant (previews, tests, screenshot pages). */
@Composable
fun ShadeTubeThemeOf(
    darkTheme: Boolean = true,
    accentMode: AccentMode = AccentMode.Dynamic,
    artworkAccent: Color? = null,
    fixedAccent: Color = BrandAmber,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val extras = if (darkTheme) ShadeExtras.dark() else ShadeExtras.light()
    val fallback = extras.accentFallback
    val dynamic = if (accentMode == AccentMode.System) dynamicAccentOrNull(darkTheme) else null
    val target = when (accentMode) {
        AccentMode.Dynamic -> clampAccent(artworkAccent, darkTheme, fallback)
        AccentMode.Fixed -> clampAccent(fixedAccent, darkTheme, fallback)
        AccentMode.System -> clampAccent(dynamic, darkTheme, fallback)
    }
    // accentRule.transitionMs 600, fastOutSlowIn (interpolated in OKLab by animateColorAsState).
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) snap() else tween(ShadeMotion.ACCENT_SHIFT, easing = FastOutSlowInEasing),
        label = "accent",
    )
    val accent = remember(animated, extras) { ShadeAccent.from(animated, extras.surface1) }
    val scheme = remember(accent, extras) { colorScheme(accent, extras) }

    SystemBarsAppearance(darkTheme)

    CompositionLocalProvider(
        LocalShadeAccent provides accent,
        LocalShadeExtras provides extras,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = scheme, typography = ShadeTypography, shapes = ShadeShapes, content = content)
    }
}

/**
 * Status / navigation bar icon colors follow the in-app theme (light icons on the dark stage, dark icons in light
 * mode) — enableEdgeToEdge() alone only follows the system night mode, not Settings › Theme.
 */
@Composable
private fun SystemBarsAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun colorScheme(accent: ShadeAccent, x: ShadeExtras) = if (x.isDark) {
    darkColorScheme(
        primary = accent.accent, onPrimary = accent.onAccent,
        primaryContainer = accent.container, onPrimaryContainer = x.text1,
        inversePrimary = accent.accent,
        secondary = x.text2, onSecondary = x.surface0,
        secondaryContainer = accent.container, onSecondaryContainer = x.text1,
        tertiary = x.text2, onTertiary = x.surface0,
        background = x.surface0, onBackground = x.text1,
        surface = x.surface0, onSurface = x.text1,
        surfaceVariant = x.surface2, onSurfaceVariant = x.text2,
        surfaceTint = Color.Transparent,
        inverseSurface = x.text1, inverseOnSurface = x.surface0,
        surfaceBright = x.surface3, surfaceDim = x.surface0,
        surfaceContainerLowest = x.surface0, surfaceContainerLow = x.surface1,
        surfaceContainer = x.surface2, surfaceContainerHigh = x.surface3, surfaceContainerHighest = x.outline,
        outline = x.outline, outlineVariant = x.outlineVariant,
        error = x.error, onError = x.onError, errorContainer = x.error.copy(alpha = .18f).compositeOver(x.surface1),
        onErrorContainer = x.error,
        scrim = Color.Black,
    )
} else {
    lightColorScheme(
        primary = accent.accent, onPrimary = accent.onAccent,
        primaryContainer = accent.container, onPrimaryContainer = x.text1,
        inversePrimary = accent.accent,
        secondary = x.text2, onSecondary = x.surface1,
        secondaryContainer = accent.container, onSecondaryContainer = x.text1,
        tertiary = x.text2, onTertiary = x.surface1,
        background = x.surface0, onBackground = x.text1,
        surface = x.surface0, onSurface = x.text1,
        surfaceVariant = x.surface2, onSurfaceVariant = x.text2,
        surfaceTint = Color.Transparent,
        inverseSurface = x.text1, inverseOnSurface = x.surface0,
        surfaceBright = x.surface1, surfaceDim = x.surface2,
        surfaceContainerLowest = x.surface1, surfaceContainerLow = x.surface0,
        surfaceContainer = x.surface2, surfaceContainerHigh = x.surface3, surfaceContainerHighest = x.outline,
        outline = x.outline, outlineVariant = x.outlineVariant,
        error = x.error, onError = x.onError, errorContainer = x.error.copy(alpha = .14f).compositeOver(x.surface1),
        onErrorContainer = x.error,
        scrim = Color.Black,
    )
}
