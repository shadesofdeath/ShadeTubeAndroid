package com.app.shadetube.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.shadetube.R
import com.app.shadetube.ui.theme.PillShape
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.bricolage
import java.util.Locale

/**
 * Text field in the ShadeTube look: 52 high, 12 corners, [containerColor] (default surface1; surface3 on a surface2
 * dialog) with a 1dp border ([borderColor], default outlineVariant) that turns accent.outline when focused
 * ([focusHighlight]); optional 24 leading icon text2, hint text3, clear button when not empty ([clearable]).
 * [mono] = ShadeMono.medium text (keys, URLs), [password] hides the text, [keyboardType] Uri also turns autocorrect off.
 */
@Composable
fun ShadeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    @DrawableRes leadingIcon: Int? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null,
    password: Boolean = false,
    mono: Boolean = false,
    singleLine: Boolean = true,
    clearable: Boolean = true,
    containerColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    focusHighlight: Boolean = true,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val idleBorder = if (borderColor != Color.Unspecified) borderColor else x.outlineVariant
    val border by animateColorAsState(
        if (focused && focusHighlight) accent.outline else idleBorder, tween(ShadeMotion.STANDARD), label = "fieldBorder",
    )
    val shape = RoundedCornerShape(12.dp)
    val textStyle = (if (mono) ShadeMono.medium else MaterialTheme.typography.bodyLarge)
        .copy(color = if (enabled) x.text1 else x.text3)
    val showClear = clearable && value.isNotEmpty() && enabled
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (containerColor != Color.Unspecified) containerColor else x.surface1)
            .border(1.dp, border, shape)
            .padding(start = if (leadingIcon != null || !clearable) 14.dp else 16.dp, end = if (clearable) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            ShadeIcon(leadingIcon, null, tint = x.text2)
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f).padding(vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(hint, style = textStyle, color = x.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().semantics { if (value.isEmpty()) contentDescription = hint },
                enabled = enabled,
                singleLine = singleLine,
                textStyle = textStyle,
                cursorBrush = SolidColor(accent.accent),
                interactionSource = interaction,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                    autoCorrectEnabled = !password && keyboardType != KeyboardType.Uri,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            )
        }
        if (showClear) {
            ShadeIconButton(R.drawable.ic_close, stringResource(R.string.search_clear_cd), { onValueChange("") }, tint = x.text2)
        } else if (clearable) {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/**
 * Step indicator (SCREENS 23, onboarding): [count] dots, the current one a 24×8 accent pill, done ones accent, later
 * ones outline. [current] is 0-based.
 */
@Composable
fun StepDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val cd = stringResource(R.string.common_step_cd, current + 1, count)
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = cd },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 24.dp else 8.dp, ShadeMotion.orReduced(ShadeMotion.standardTween()), label = "dot")
            Box(
                Modifier
                    .height(8.dp)
                    .width(w)
                    .clip(PillShape)
                    .background(if (i <= current) accent.accent else x.outline),
            )
        }
    }
}

/**
 * Round profile avatar: the user's picture, else their initial on the accent, else a user icon on surface3.
 * With [onClick] (Home's top-right avatar → Profile) it gets a 48 hit target and the "Profil" description.
 */
@Composable
fun ProfileAvatar(
    imageUrl: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    onClick: (() -> Unit)? = null,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val cd = if (onClick != null) stringResource(R.string.nav_profile_cd) else null
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val content: @Composable () -> Unit = {
        when {
            imageUrl.isNotEmpty() -> Artwork(imageUrl, kind = ArtworkKind.Artist, size = size, shape = CircleShape, contentDescription = cd)
            name.isNotEmpty() -> Box(
                Modifier.size(size).clip(CircleShape).background(accent.accent),
                contentAlignment = Alignment.Center,
            ) {
                // Small (top bar) avatars use the title style; large ones (Profile, Settings card) a scaled Bricolage.
                val style = if (size < 48.dp) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = bricolage(size.value * 0.45f), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold,
                    )
                }
                Text(name.take(1).uppercase(locale), style = style, color = accent.onAccent)
            }
            else -> Box(
                Modifier.size(size).clip(CircleShape).background(x.surface3),
                contentAlignment = Alignment.Center,
            ) { ShadeIcon(R.drawable.ic_user, cd, tint = x.text2, size = if (size < 48.dp) 22.dp else size / 2) }
        }
    }
    if (onClick != null) {
        Box(
            modifier
                .size(maxOf(size, 48.dp))
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = cd, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { content() }
    }
}
