package com.app.shadetube.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.shadetube.R
import com.app.shadetube.ui.components.CrescentMark
import com.app.shadetube.ui.components.LinearProgress
import com.app.shadetube.ui.components.MonoTag
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.SecondaryButton
import com.app.shadetube.ui.components.ShadeFilterChip
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.components.SkeletonBox
import com.app.shadetube.ui.components.SkeletonLine
import com.app.shadetube.ui.components.coverGlow
import com.app.shadetube.ui.components.coverShadow
import com.app.shadetube.ui.home.uiLocale
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTextStyles
import com.app.shadetube.ui.theme.ShadeTheme

/** The accent wash behind a detail page's top (hi-fi Playlist detail: the cover's color falling from the top). */
@Composable
fun AccentWash(modifier: Modifier = Modifier, color: Color = ShadeTheme.accent.accent) {
    val alpha = if (ShadeTheme.extras.isDark) 0.26f else 0.16f
    Box(modifier.background(Brush.verticalGradient(listOf(color.copy(alpha = alpha), Color.Transparent))))
}

/**
 * Header block (REDLINES: Çalma listesi detayı): cover 148 with glow · 16 · overline (optional) · title 26/700 ·
 * 10 · owner line · 8 · mono meta. Bottom-aligned next to the cover.
 */
@Composable
fun CollectionHero(
    cover: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    owner: (@Composable () -> Unit)? = null,
    meta: String? = null,
    description: String? = null,
) {
    val x = ShadeTheme.extras
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(148.dp).coverGlow(spread = 0.9f).coverShadow(24.dp, ShadeRadius.cover)) { cover() }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (!overline.isNullOrEmpty()) {
                    Text(overline.uppercase(uiLocale()), style = ShadeMono.overline, color = x.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                }
                Text(title, style = ShadeTextStyles.heroTitle, color = x.text1, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (owner != null) {
                    Spacer(Modifier.height(10.dp))
                    owner()
                }
                if (!meta.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(meta, style = ShadeMono.medium.copy(fontSize = 12.sp), color = x.text2, maxLines = 2)
                }
            }
        }
        if (!description.isNullOrEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Owner line: avatar 20 (initial) + 13/600 name + optional mono badge ("Spotify"). */
@Composable
fun OwnerLine(name: String, badge: String?, onClick: (() -> Unit)? = null) {
    val x = ShadeTheme.extras
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (name.isNotEmpty()) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(ShadeTheme.accent.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.take(1).uppercase(uiLocale()), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = ShadeTheme.accent.onAccent,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (!badge.isNullOrEmpty()) MonoTag(badge)
    }
}

/** Çal (accent pill) · 10 · Karıştır (surface3 pill), 1:1. */
@Composable
fun PlayShuffleRow(playing: Boolean, enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(
            stringResource(if (playing) R.string.player_pause_cd else R.string.action_play), onPlay, Modifier.weight(1f),
            enabled = enabled, icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play,
        )
        SecondaryButton(
            stringResource(R.string.action_shuffle), onShuffle, Modifier.weight(1f), enabled = enabled,
            icon = R.drawable.ic_shuffle,
        )
    }
}

/** A chip of the collection chip row. */
data class ChipSpec(val label: String, val icon: Int?, val selected: Boolean, val onClick: () -> Unit)

/** Chip row 36 (spacing 8, scrolls horizontally). */
@Composable
fun ChipRow(chips: List<ChipSpec>, modifier: Modifier = Modifier) {
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips.size) { i ->
            val c = chips[i]
            ShadeFilterChip(c.label, c.selected, c.onClick, leadingIcon = c.icon)
        }
    }
}

/**
 * Sync card (REDLINES: Senkron kartı 52): surface1, 10 corners, 10×12 padding; "Çevrimdışı senkron" 12 + mono
 * counter; 3dp accent progress; "Duraklat" / "Devam et" 12/600 accent.
 */
@Composable
fun SyncCard(sync: SyncUi, onPause: () -> Unit, onResume: () -> Unit, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(x.surface1)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.playlist_sync_progress), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.playlist_sync_count, sync.done, sync.total),
                    style = ShadeMono.medium.copy(fontSize = 12.sp), color = x.text1,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgress(if (sync.listing && sync.total == 0) null else sync.fraction ?: 0f, Modifier.fillMaxWidth(), height = 3.dp)
        }
        Spacer(Modifier.width(4.dp))
        ShadeTextButton(
            stringResource(if (sync.paused) R.string.playlist_sync_resume else R.string.playlist_sync_pause),
            if (sync.paused) onResume else onPause,
        )
    }
}

/** Cover of a generated ("Senin için yapıldı") list: its tint over surface3 and the crescent. */
@Composable
fun SmartCover(tint: Color, size: Dp = 148.dp) {
    val x = ShadeTheme.extras
    Box(
        Modifier
            .size(size)
            .clip(ShadeRadius.cover)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.85f), x.surface3))),
        contentAlignment = Alignment.Center,
    ) {
        CrescentMark(size = size * 0.55f, color = x.surface0.copy(alpha = 0.85f))
    }
}

@Composable
fun CollectionHeroSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            SkeletonBox(Modifier.size(148.dp), ShadeRadius.cover)
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonLine(150.dp, height = 22.dp)
                SkeletonLine(100.dp)
                SkeletonLine(120.dp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBox(Modifier.weight(1f).height(48.dp), CircleShape)
            SkeletonBox(Modifier.weight(1f).height(48.dp), CircleShape)
        }
    }
}

/** Spotify playlist descriptions carry HTML (links, entities): plain text for the header. */
fun plainDescription(html: String): String {
    if (html.isEmpty()) return ""
    return html.replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .trim()
}
