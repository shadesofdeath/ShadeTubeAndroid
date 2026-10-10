package com.app.shadetube.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.shadetube.ui.theme.PillShape
import com.app.shadetube.ui.theme.ShadeTheme

/**
 * Primary pill (COMPONENTS: Birincil buton): 48 high, 24 horizontal padding, accent fill, onAccent titleMedium
 * 15/700, optional 20dp icon + 8. Pressed: scale .97 in 100 ms. Disabled: surface3 / text3. Loading: a 20dp
 * spinner replaces the label (the button keeps its width).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val active = enabled && !loading
    PillButton(
        text = text, onClick = onClick, modifier = modifier, icon = icon, enabled = active, loading = loading,
        container = if (enabled) accent.accent else x.surface3,
        content = if (enabled) accent.onAccent else x.text3,
        weight = FontWeight.Bold, scaleOnPress = true,
    )
}

/** Secondary pill: 48, surface3 fill, text1 15/600; pressed = 12 % ripple. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    horizontalPadding: Int = 24,
) {
    val x = ShadeTheme.extras
    PillButton(
        text = text, onClick = onClick, modifier = modifier, icon = icon, enabled = enabled && !loading, loading = loading,
        container = x.surface3, content = if (enabled) x.text1 else x.text3,
        weight = FontWeight.SemiBold, scaleOnPress = false, horizontalPadding = horizontalPadding,
    )
}

/** Small pill (36 high) used by link cards / "Önizle" / "Aç" actions (SCREENS 4, 14). */
@Composable
fun SmallPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    PillButton(
        text = text, onClick = onClick, modifier = modifier, icon = icon, enabled = enabled, loading = false,
        container = if (primary && enabled) accent.accent else x.surface3,
        content = when { !enabled -> x.text3; primary -> accent.onAccent; else -> x.text1 },
        weight = FontWeight.SemiBold, scaleOnPress = primary, height = 36, horizontalPadding = 16,
    )
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: Int?,
    enabled: Boolean,
    loading: Boolean,
    container: Color,
    content: Color,
    weight: FontWeight,
    scaleOnPress: Boolean,
    height: Int = 48,
    horizontalPadding: Int = 24,
) {
    val interaction = rememberInteractionSource()
    Box(
        modifier
            .then(if (scaleOnPress) Modifier.pressScale(interaction) else Modifier)
            .heightIn(min = height.dp)
            .defaultMinSize(minWidth = height.dp)
            .clip(PillShape)
            .background(container)
            .clickable(interactionSource = interaction, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(PaddingValues(horizontal = horizontalPadding.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.alpha(if (loading) 0f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                ShadeIcon(icon, null, tint = content, size = 20.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = weight), color = content,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = content, strokeWidth = 2.dp, trackColor = Color.Transparent)
        }
    }
}

/** Text button (dialog actions, "Tümünü gör", "Duraklat"): labelLarge in accent (or error when [destructive]), 48 hit. */
@Composable
fun ShadeTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val x = ShadeTheme.extras
    val c = when {
        !enabled -> x.text3
        destructive -> x.error
        color != Color.Unspecified -> color
        else -> ShadeTheme.accent.accent
    }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(PillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = textStyle, color = c, maxLines = 1)
    }
}
