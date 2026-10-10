package com.app.shadetube.ui.nowplaying

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.shadetube.R
import com.app.shadetube.core.RepeatMode
import com.app.shadetube.playback.features.SmartShuffle
import com.app.shadetube.ui.components.PlayPauseButton
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import java.util.Locale

/** Top bar 48: chevron-down 48 · centered overline 11 + 13/600 context name · more 48. */
@Composable
internal fun NowPlayingTopBar(overline: String, name: String, onCollapse: () -> Unit, onMore: (() -> Unit)?, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    Row(modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ShadeIconButton(R.drawable.ic_chevron_down, stringResource(R.string.player_collapse_cd), onCollapse, tint = x.text1, size = 48.dp)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                overline.uppercase(LocalConfiguration.current.locales[0] ?: Locale.ROOT),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.8.sp),
                color = x.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (name.isNotEmpty()) {
                Text(
                    name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp), color = x.text1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
        if (onMore != null) {
            ShadeIconButton(R.drawable.ic_more_vertical, stringResource(R.string.common_more_cd), onMore, tint = x.text1, size = 48.dp)
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

/**
 * Transport (REDLINES y=586): shuffle(-smart) 48 · prev 56 (icon 30) · play 76 accent pill (icon 34) · next 56 · repeat
 * 48, space-between with 24 side padding.
 */
@Composable
internal fun TransportRow(
    isPlaying: Boolean,
    shuffle: SmartShuffle.Mode,
    repeat: RepeatMode,
    live: Boolean,
    onShuffle: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val x = ShadeTheme.extras
    val on = stringResource(R.string.np_state_on)
    val off = stringResource(R.string.np_state_off)
    Row(
        modifier.fillMaxWidth().height(76.dp).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shuffleIcon = if (shuffle == SmartShuffle.Mode.Smart) R.drawable.ic_shuffle_smart else R.drawable.ic_shuffle
        val shuffleCd = stringResource(if (shuffle == SmartShuffle.Mode.Smart) R.string.player_shuffle_smart_cd else R.string.player_shuffle_cd)
        ToggleIcon(shuffleIcon, shuffleCd, if (shuffle == SmartShuffle.Mode.Off) off else on, shuffle != SmartShuffle.Mode.Off, !live, onShuffle)
        ShadeIconButton(R.drawable.ic_prev, stringResource(R.string.player_prev_cd), onPrev, tint = x.text1, size = 56.dp, iconSize = 30.dp)
        PlayPauseButton(isPlaying, onPlayPause, size = 76, iconSize = 34, filled = true)
        ShadeIconButton(R.drawable.ic_next, stringResource(R.string.player_next_cd), onNext, tint = x.text1, size = 56.dp, iconSize = 30.dp)
        val repeatIcon = if (repeat == RepeatMode.One) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        val repeatState = when (repeat) {
            RepeatMode.Off -> off
            RepeatMode.All -> stringResource(R.string.np_repeat_all)
            RepeatMode.One -> stringResource(R.string.np_repeat_one)
        }
        ToggleIcon(
            repeatIcon, stringResource(if (repeat == RepeatMode.One) R.string.player_repeat_one_cd else R.string.player_repeat_cd),
            repeatState, repeat != RepeatMode.Off, !live, onRepeat,
        )
    }
}

@Composable
private fun ToggleIcon(@DrawableRes icon: Int, cd: String, state: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    Box(contentAlignment = Alignment.Center) {
        ShadeIconButton(
            icon, cd, onClick,
            modifier = Modifier.semantics { stateDescription = state },
            tint = when { !enabled -> x.text3; active -> accent.accent; else -> x.text2 },
            size = 48.dp, enabled = enabled,
        )
        // Active dot under the icon: the colour alone does not tell on/off apart when the accent is the neutral
        // fallback (a grey cover) or for colour-blind users.
        if (active && enabled) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).clip(CircleShape)
                    .background(accent.accent),
            )
        }
    }
}

/** Secondary tool (REDLINES y=714): 56 wide, 22 icon + 10 label (mono for values), accent when active. */
@Composable
internal fun ToolButton(
    @DrawableRes icon: Int,
    label: String,
    contentDescription: String,
    active: Boolean,
    mono: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val color = if (active) accent.accent else x.text2
    Column(
        modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShadeIcon(icon, null, tint = color, size = 22.dp)
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = if (mono) ShadeMono.small.copy(fontSize = 10.sp) else MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Yanlış eşleşme?" text button (12/600 accent, 48 hit). */
@Composable
internal fun WrongMatchButton(onClick: () -> Unit) {
    Box(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.player_wrong_match),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = ShadeTheme.accent.accent, maxLines = 1,
        )
    }
}
