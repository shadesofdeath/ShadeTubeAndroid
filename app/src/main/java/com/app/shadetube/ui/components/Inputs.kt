package com.app.shadetube.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.shadetube.R
import com.app.shadetube.ui.theme.PillShape
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.launch

/**
 * Search field (COMPONENTS: Arama kutusu): 52 high pill, surface2, 1dp outlineVariant (accent.outline when focused);
 * leading 24 icon text2, hint bodyLarge text3, clear button 40 when not empty. IME action = search.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.search_hint),
    onSearch: (String) -> Unit = {},
    @DrawableRes leadingIcon: Int = R.drawable.ic_search,
    onLeadingClick: (() -> Unit)? = null,
    leadingDescription: String? = null,
    autoFocus: Boolean = false,
    enabled: Boolean = true,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val interaction = rememberInteractionSource()
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        if (focused) accent.outline else x.outlineVariant, tween(ShadeMotion.STANDARD), label = "searchBorder",
    )
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    if (autoFocus) LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = x.text1)

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(PillShape)
            .background(x.surface2)
            .border(1.dp, border, PillShape)
            .padding(start = if (onLeadingClick != null) 4.dp else 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onLeadingClick != null) {
            ShadeIconButton(leadingIcon, leadingDescription, onLeadingClick, tint = x.text2)
        } else {
            ShadeIcon(leadingIcon, null, tint = x.text2)
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(hint, style = textStyle, color = x.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { if (query.isEmpty()) contentDescription = hint },
                enabled = enabled,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(accent.accent),
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query); focusManager.clearFocus() }),
            )
        }
        AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            ShadeIconButton(R.drawable.ic_close, stringResource(R.string.search_clear_cd), { onQueryChange("") }, tint = x.text2)
        }
        if (query.isEmpty()) Spacer(Modifier.width(10.dp))
    }
}

/** Switch (COMPONENTS: Switch / Slider): M3 geometry; on = accent track + onAccent thumb, off = surface3 / text3. */
@Composable
fun ShadeSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = accent.accent, checkedThumbColor = accent.onAccent, checkedBorderColor = accent.accent,
            checkedIconColor = accent.accent,
            uncheckedTrackColor = x.surface3, uncheckedThumbColor = x.text3, uncheckedBorderColor = x.outline,
            uncheckedIconColor = x.surface3,
            disabledCheckedTrackColor = x.surface3, disabledCheckedThumbColor = x.text3,
            disabledUncheckedTrackColor = x.surface2, disabledUncheckedThumbColor = x.text3,
            disabledUncheckedBorderColor = x.outlineVariant,
        ),
    )
}

/** Slider: active accent, inactive surface3, thumb accent. Show the value next to it in mono (`ShadeMono.small`). */
@Composable
fun ShadeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, valueRange = valueRange,
        steps = steps, onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = accent.accent, activeTrackColor = accent.accent, inactiveTrackColor = x.surface3,
            activeTickColor = accent.onAccent, inactiveTickColor = x.text3,
            disabledThumbColor = x.text3, disabledActiveTrackColor = x.text3, disabledInactiveTrackColor = x.surface2,
        ),
    )
}

/**
 * Like (heart) button: line ↔ filled heart in accent, popping 1 → 1.25 → 1 with the heartPop spring when liked
 * (animations/avd_heart_like.xml timing; no pop with reduce motion). 48 hit, icon [iconSize].
 */
@Composable
fun LikeButton(liked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, iconSize: Int = 26, size: Int = 48) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val reduce = ShadeTheme.reduceMotion
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    ShadeIconButton(
        icon = if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart,
        contentDescription = stringResource(if (liked) R.string.player_unlike_cd else R.string.player_like_cd),
        onClick = {
            if (!liked && !reduce) scope.launch {
                scale.animateTo(1.25f, tween(160))
                scale.animateTo(1f, ShadeMotion.heartPop())
            }
            onToggle()
        },
        modifier = modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        tint = if (liked) accent.accent else x.text2,
        size = size.dp, iconSize = iconSize.dp,
    )
}
