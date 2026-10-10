package com.app.shadetube.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.shadetube.R
import com.app.shadetube.ui.theme.PillShape
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTextStyles
import com.app.shadetube.ui.theme.ShadeTheme

/**
 * Filter chip (COMPONENTS: Çip): 36 high, 12 horizontal, corner 10, labelLarge 14/500. Unselected: surface2 +
 * 1dp outlineVariant. Selected: accent.container + 1dp accent.outline, text and icon accent at 600. Optional 16dp
 * leading icon. Put chip rows in a horizontally scrolling Row/LazyRow (spacing 8) so 200 % font scale still fits.
 */
@Composable
fun ShadeFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes leadingIcon: Int? = null,
    @DrawableRes trailingIcon: Int? = null,
    enabled: Boolean = true,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val shape = RoundedCornerShape(10.dp)
    val content = when {
        !enabled -> x.text3
        selected -> accent.accent
        else -> x.text1
    }
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) accent.container else x.surface2)
            .border(1.dp, if (selected) accent.outline else x.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            ShadeIcon(leadingIcon, null, tint = content, size = 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = content, maxLines = 1,
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(6.dp))
            ShadeIcon(trailingIcon, null, tint = content, size = 16.dp)
        }
    }
}

/**
 * Source badge — plain text, no brand logos (README §1): monoSmall 11, padding 5×10, corner 6, surface2 + 1dp
 * surface3 border, text2. E.g. "YouTube · 320 kbps · %94 eşleşme" (R.string.player_source_badge).
 */
@Composable
fun SourceBadge(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val x = ShadeTheme.extras
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        modifier
            .clip(shape)
            .background(x.surface2)
            .border(1.dp, x.surface3, shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = ShadeMono.small,
        color = if (color != Color.Unspecified) color else x.text2,
        maxLines = 1,
    )
}

/** Small mono tag (owner "Spotify" badge on playlist hero, file format FLAC/MP3): mono 10, padding 2×6, corner 4. */
@Composable
fun MonoTag(text: String, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    val shape = RoundedCornerShape(4.dp)
    Text(
        text,
        modifier.clip(shape).background(x.surface2).border(1.dp, x.surface3, shape).padding(horizontal = 6.dp, vertical = 2.dp),
        style = ShadeMono.overline.copy(letterSpacing = ShadeMono.small.letterSpacing),
        color = x.text2, maxLines = 1,
    )
}

/** Live badge: 20dp pill, error fill, white label + 6dp dot blinking once a second (static with reduce motion). */
@Composable
fun LiveBadge(modifier: Modifier = Modifier, text: String = stringResource(R.string.radio_live)) {
    val x = ShadeTheme.extras
    val dotAlpha = if (ShadeTheme.reduceMotion) 1f else {
        val t = rememberInfiniteTransition(label = "live")
        val a by t.animateFloat(
            1f, 0.2f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "liveDot",
        )
        a
    }
    Row(
        modifier
            .height(20.dp)
            .clip(PillShape)
            .background(x.error)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).alpha(dotAlpha).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
    }
}

/** Count badge (podcast new episodes): min 18, pill, accent / onAccent, labelSmall. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val accent = ShadeTheme.accent
    Box(
        modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(PillShape)
            .background(accent.accent)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall, color = accent.onAccent, textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** "ÖNERİLEN" badge of suggested rows: overline 9.5 mono, padding 2×6, corner 4, accent.container / accent. */
@Composable
fun SuggestedBadge(modifier: Modifier = Modifier, text: String = stringResource(R.string.playlist_suggested_badge)) {
    val accent = ShadeTheme.accent
    val locale = androidx.compose.ui.text.intl.Locale.current
    Text(
        text.uppercase(java.util.Locale.forLanguageTag(locale.toLanguageTag())),
        modifier.clip(RoundedCornerShape(4.dp)).background(accent.container).padding(horizontal = 6.dp, vertical = 2.dp),
        style = ShadeTextStyles.badge, color = accent.accent, maxLines = 1,
    )
}

/** Accent dot (nav "new download" badge): 6dp. */
@Composable
fun BadgeDot(modifier: Modifier = Modifier, size: Dp = 6.dp, color: Color = ShadeTheme.accent.accent) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}
