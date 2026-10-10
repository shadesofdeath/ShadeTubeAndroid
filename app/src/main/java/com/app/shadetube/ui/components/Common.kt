package com.app.shadetube.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme
import java.util.Locale

/** A 24dp line icon from res/drawable (ic_*), tinted (the vectors are black; Compose tints them). */
@Composable
fun ShadeIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Icon(painterResource(icon), contentDescription, modifier.size(size), tint = tint)
}

/**
 * Icon button: [size] visual (40 by default), padded to a 48dp touch target, circular ripple. Every icon button must
 * pass a `_cd` string as [contentDescription] (README › Erişilebilirlik).
 */
@Composable
fun ShadeIconButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = ShadeTheme.extras.text2,
    size: Dp = 40.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = null,
                interactionSource = null,
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), contentDescription, Modifier.size(iconSize),
            tint = if (enabled) tint else ShadeTheme.extras.text3,
        )
    }
}

/** Press feedback of pill buttons: scale to [pressedScale] in 100 ms while pressed (COMPONENTS: Birincil buton). */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f, tween(ShadeMotion.INSTANT, easing = ShadeMotion.standard), label = "pressScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun rememberInteractionSource() = remember { MutableInteractionSource() }

/** "m:ss" / "h:mm:ss" (durations, seek bar times). Negative or unknown → "--:--". */
fun formatDuration(ms: Long): String {
    if (ms < 0) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}
