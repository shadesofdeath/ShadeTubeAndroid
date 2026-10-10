package com.app.shadetube.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.app.shadetube.R
import com.app.shadetube.core.AccentMode
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.CountBadge
import com.app.shadetube.ui.components.CoverTile
import com.app.shadetube.ui.components.CoverTileSkeleton
import com.app.shadetube.ui.components.CrescentMark
import com.app.shadetube.ui.components.DownloadIndicator
import com.app.shadetube.ui.components.DownloadUiState
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.LikeButton
import com.app.shadetube.ui.components.LiveBadge
import com.app.shadetube.ui.components.MadeForYouCard
import com.app.shadetube.ui.components.MiniPlayer
import com.app.shadetube.ui.components.MonoTag
import com.app.shadetube.ui.components.NowPlayingBars
import com.app.shadetube.ui.components.OfflineBanner
import com.app.shadetube.ui.components.PlayPauseButton
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.ProgressRing
import com.app.shadetube.ui.components.RecentTile
import com.app.shadetube.ui.components.SearchField
import com.app.shadetube.ui.components.SecondaryButton
import com.app.shadetube.ui.components.SectionHeader
import com.app.shadetube.ui.components.SeekBar
import com.app.shadetube.ui.components.SegmentedPill
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.components.ShadeFilterChip
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.ShadeLogo
import com.app.shadetube.ui.components.ShadeNavigationBar
import com.app.shadetube.ui.components.ShadeSlider
import com.app.shadetube.ui.components.ShadeSnackbar
import com.app.shadetube.ui.components.ShadeSwitch
import com.app.shadetube.ui.components.ShadeTab
import com.app.shadetube.ui.components.SheetDivider
import com.app.shadetube.ui.components.SheetDragHandle
import com.app.shadetube.ui.components.SheetHeader
import com.app.shadetube.ui.components.SheetMenuRow
import com.app.shadetube.ui.components.SourceBadge
import com.app.shadetube.ui.components.SuggestedBadge
import com.app.shadetube.ui.components.SwipeToDeleteBox
import com.app.shadetube.ui.components.TrackListSkeleton
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.components.coverGlow
import com.app.shadetube.ui.theme.AccentPresets
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf
import com.app.shadetube.ui.theme.clampAccent

/**
 * Debug component gallery: every design-system component in its states, for checking against the handoff in
 * Android Studio's preview (or by temporarily calling [ComponentGallery] from MainActivity). Not used by the app.
 */
@Composable
fun ComponentGallery(modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    Column(
        modifier
            .fillMaxSize()
            .background(x.surface0)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp),
    ) {
        Box(Modifier.padding(horizontal = 20.dp)) { ShadeLogo(height = 36.dp) }
        Gap()
        Group("Buttons") {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.action_play), {}, Modifier.weight(1f), icon = R.drawable.ic_play)
                SecondaryButton(stringResource(R.string.action_shuffle), {}, Modifier.weight(1f), icon = R.drawable.ic_shuffle)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.action_play), {}, Modifier.weight(1f), enabled = false)
                PrimaryButton(stringResource(R.string.action_play), {}, Modifier.weight(1f), loading = true)
            }
        }
        Group("Chips & badges") {
            var sel by remember { mutableIntStateOf(0) }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShadeFilterChip(stringResource(R.string.action_enhance), sel == 0, { sel = 0 }, leadingIcon = R.drawable.ic_sparkle)
                ShadeFilterChip(stringResource(R.string.action_make_offline), sel == 1, { sel = 1 }, leadingIcon = R.drawable.ic_download)
                ShadeFilterChip(stringResource(R.string.library_sort), sel == 2, { sel = 2 })
                ShadeFilterChip(stringResource(R.string.action_more), sel == 3, { sel = 3 })
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceBadge(stringResource(R.string.player_source_badge, 320, 94))
                LiveBadge()
                CountBadge(3)
                SuggestedBadge()
                MonoTag("Spotify")
            }
        }
        Group("Track rows") {
            TrackRow("Cam Kenarı", "Deniz Aslı · Kırmızı Oda", null, duration = "3:48", downloaded = true, onMore = {})
            TrackRow("Gece Yarısı Otoyolu", "Kül & Neon · Sis Haritası", null, duration = "5:12", isCurrent = true, isPlaying = true, onMore = {})
            TrackRow("Sodyum Lambaları", "Kuzey Işığı", null, suggested = true, onAddSuggested = {}, onHideSuggested = {})
            TrackRow("Islak Asfalt", "Aralık Kolektifi · Yosun", null, duration = "3:21", explicit = true, onMore = {})
            TrackRow("Engellenen şarkı", "Sanatçı", null, duration = "2:59", enabled = false, onMore = {})
            TrackRow("Sürüklenen", "Sıra", null, duration = "4:02", dragging = true, trailing = {
                ShadeIcon(R.drawable.ic_drag_handle, null, tint = x.text2, modifier = Modifier.padding(horizontal = 8.dp))
            })
            SwipeToDeleteBox(onDelete = {}) {
                TrackRow("Sola kaydır → sil", "Swipe to delete", null, duration = "1:00", onMore = {})
            }
            TrackListSkeleton(rows = 2)
        }
        Group("Tiles & cards") {
            SectionHeader(stringResource(R.string.home_recently_played))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecentTile("Gece Sürüşü", null, {}, Modifier.weight(1f), kind = ArtworkKind.Playlist)
                RecentTile(stringResource(R.string.library_liked_songs), null, {}, Modifier.weight(1f), isPlaying = true)
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.home_for_you), actionLabel = stringResource(R.string.home_see_all), onAction = {})
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoverTile("Daily Mix 1", "Mor ve Ötesi, Adamlar, Büyük Ev ve daha fazlası", null, {})
                CoverTile("Discover Weekly", "Her pazartesi yenilenir", null, {}, kind = ArtworkKind.Playlist)
                CoverTile("Sanatçı", null, null, {}, kind = ArtworkKind.Artist, size = 116.dp)
                CoverTileSkeleton(size = 116.dp)
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.home_made_for_you), subtitle = stringResource(R.string.home_made_for_you_sub))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MadeForYouCard("Ekim", stringResource(R.string.mix_month_favorites), AccentPresets.kehribar, {})
                MadeForYouCard("48 şarkı", stringResource(R.string.mix_forgotten_likes), AccentPresets.lavanta, {})
                MadeForYouCard("2025", stringResource(R.string.mix_all_time), AccentPresets.turkuaz, {})
            }
        }
        Group("Player") {
            var playing by remember { mutableStateOf(true) }
            var pos by remember { mutableFloatStateOf(143_000f) }
            Box(Modifier.padding(horizontal = 12.dp)) {
                MiniPlayer(
                    "Gece Yarısı Otoyolu", "Kül & Neon", null, playing, pos / 312_000f, {}, { playing = !playing },
                    onSkipNext = {}, onSkipPrevious = {}, onExpand = {},
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.padding(horizontal = 12.dp)) {
                MiniPlayer("Buffering", "…", null, false, 0f, {}, {}, isBuffering = true)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.padding(horizontal = 12.dp)) {
                MiniPlayer("Radyo Eksen", "Şu an çalıyor: …", null, true, 0f, {}, {}, isLive = true, artworkKind = ArtworkKind.Radio)
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.padding(horizontal = 32.dp)) {
                SeekBar(pos.toLong(), 312_000, { pos = it.toFloat() }, bufferedMs = 220_000)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LikeButton(true, {})
                PlayPauseButton(playing, { playing = !playing }, size = 76, iconSize = 34, filled = true)
                NowPlayingBars(playing)
                ProgressRing(0.6f)
                ProgressRing(null)
                DownloadIndicator(DownloadUiState.Downloading(0.4f))
                DownloadIndicator(DownloadUiState.Done)
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.padding(horizontal = 20.dp)) {
                SegmentedPill(
                    listOf(stringResource(R.string.player_tab_queue), stringResource(R.string.player_tab_lyrics), stringResource(R.string.player_tab_related)),
                    1, {},
                )
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.size(160.dp).padding(start = 40.dp).coverGlow()) {
                Box(Modifier.size(120.dp).clip(ShadeRadius.coverLarge).background(x.surface3))
            }
        }
        Group("Inputs") {
            var q by remember { mutableStateOf("") }
            Box(Modifier.padding(horizontal = 20.dp)) { SearchField(q, { q = it }) }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                var on by remember { mutableStateOf(true) }
                ShadeSwitch(on, { on = it })
                Spacer(Modifier.width(16.dp))
                var v by remember { mutableFloatStateOf(0.4f) }
                ShadeSlider(v, { v = it }, Modifier.weight(1f))
                Text("%.2f×".format(0.5f + v * 2.5f), style = ShadeMono.small, color = x.text2)
            }
        }
        Group("Sheet content") {
            Column(Modifier.fillMaxWidth().clip(ShadeRadius.sheet).background(x.surface2)) {
                SheetDragHandle(Modifier.align(Alignment.CenterHorizontally))
                SheetHeader("Gece Yarısı Otoyolu", subtitle = "Kül & Neon")
                SheetMenuRow(R.drawable.ic_play_next, stringResource(R.string.menu_play_next), {})
                SheetMenuRow(R.drawable.ic_add_to_queue, stringResource(R.string.menu_add_to_queue), {})
                SheetDivider()
                SheetMenuRow(R.drawable.ic_block, stringResource(R.string.menu_block_song), {}, destructive = true)
            }
            Spacer(Modifier.height(12.dp))
            ShadeSnackbar(stringResource(R.string.queue_removed), actionLabel = stringResource(R.string.action_undo))
            OfflineBanner()
        }
        Group("States") {
            EmptyState(
                R.drawable.ic_playlist, stringResource(R.string.playlist_empty_title), stringResource(R.string.playlist_empty_body),
                actionLabel = stringResource(R.string.playlist_empty_cta), onAction = {}, actionIcon = R.drawable.ic_plus,
            )
        }
        Group("Accent presets (clamped)") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AccentPresets.all.forEach { (_, c) ->
                    Box(Modifier.size(40.dp).clip(CircleShape).background(clampAccent(c, x.isDark, x.accentFallback)))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CrescentMark(size = 48.dp)
                CrescentMark(size = 48.dp, color = x.text1)
            }
        }
        ShadeNavigationBar(ShadeTab.Library, {}, libraryProgress = 0.45f)
        ShadeNavigationBar(ShadeTab.Home, {}, libraryBadge = true)
    }
}

/** Static preview of a dialog (dialogs render in their own window, so they are kept out of the scrolling gallery). */
@Composable
fun DialogGallery() {
    ShadeDialog(
        onDismissRequest = {},
        title = stringResource(R.string.confirm_delete_playlist_title),
        text = stringResource(R.string.confirm_delete_playlist_body, "Gece Sürüşü"),
        confirmLabel = stringResource(R.string.action_delete),
        onConfirm = {},
        destructive = true,
    )
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title.uppercase(), Modifier.padding(start = 20.dp, top = 8.dp, bottom = 12.dp),
        style = ShadeMono.overline, color = ShadeTheme.extras.text3,
    )
    Column(content = content)
    Gap()
}

@Composable
private fun Gap() = Spacer(Modifier.height(24.dp))

@Preview(name = "Dark · amber (from artwork)", widthDp = 412, heightDp = 3000, showBackground = true, backgroundColor = 0xFF0B0C10)
@Composable
private fun GalleryDarkPreview() {
    ShadeTubeThemeOf(darkTheme = true, accentMode = AccentMode.Dynamic, artworkAccent = Color(0xFFE08A2C)) { ComponentGallery() }
}

@Preview(name = "Dark · turquoise", widthDp = 412, heightDp = 3000, showBackground = true, backgroundColor = 0xFF0B0C10)
@Composable
private fun GalleryDarkTurquoisePreview() {
    ShadeTubeThemeOf(darkTheme = true, accentMode = AccentMode.Fixed, fixedAccent = AccentPresets.turkuaz) { ComponentGallery() }
}

@Preview(name = "Light · rose", widthDp = 412, heightDp = 3000, showBackground = true, backgroundColor = 0xFFF7F6F1)
@Composable
private fun GalleryLightPreview() {
    ShadeTubeThemeOf(darkTheme = false, accentMode = AccentMode.Fixed, fixedAccent = AccentPresets.gul) { ComponentGallery() }
}

@Preview(name = "Dark · neutral fallback, reduce motion", widthDp = 412, heightDp = 3000, showBackground = true, backgroundColor = 0xFF0B0C10)
@Composable
private fun GalleryNeutralPreview() {
    ShadeTubeThemeOf(darkTheme = true, accentMode = AccentMode.Dynamic, artworkAccent = null, reduceMotion = true) { ComponentGallery() }
}

@Preview(name = "Dialog", widthDp = 412, heightDp = 400)
@Composable
private fun DialogPreview() {
    ShadeTubeThemeOf(darkTheme = true, fixedAccent = AccentPresets.gul, accentMode = AccentMode.Fixed) { DialogGallery() }
}
