package com.app.shadetube.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** Settings › Reduce motion: no glow, no shared-element transition, no springs — only 150 ms fades. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** tokens.json → motion. */
object ShadeMotion {
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val linear = LinearEasing

    const val INSTANT = 100
    const val SHORT = 150
    const val STANDARD = 250
    const val MEDIUM = 400
    const val LONG = 600
    const val ACCENT_SHIFT = 600

    fun <T> dragRelease(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> sheetSnap(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 300f)
    fun <T> heartPop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.45f, stiffness = 600f)

    fun <T> standardTween(durationMs: Int = STANDARD): FiniteAnimationSpec<T> = tween(durationMs, easing = standard)
    fun <T> emphasizedTween(durationMs: Int = MEDIUM): FiniteAnimationSpec<T> = tween(durationMs, easing = emphasized)

    /** The reduce-motion replacement: a short linear-ish fade timing. */
    fun <T> reduced(): FiniteAnimationSpec<T> = tween(SHORT, easing = standard)

    /** [spec] normally, a 150 ms tween when reduce motion is on (use for springs and emphasized transitions). */
    @Composable
    @ReadOnlyComposable
    fun <T> orReduced(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) reduced() else spec

    /** Standard 250 ms fade (or 150 ms with reduce motion). */
    @Composable
    @ReadOnlyComposable
    fun fadeEnter(): EnterTransition =
        fadeIn(if (LocalReduceMotion.current) reduced() else standardTween())

    @Composable
    @ReadOnlyComposable
    fun fadeExit(): ExitTransition =
        fadeOut(if (LocalReduceMotion.current) reduced() else standardTween())
}
