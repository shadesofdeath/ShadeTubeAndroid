package com.app.shadetube.ui.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.unit.Dp
import com.app.shadetube.ui.components.AdaptiveWidths
import com.app.shadetube.ui.components.CenteredContent
import com.app.shadetube.ui.components.LocalWindowLayout
import com.app.shadetube.ui.artist.ArtistScreen
import com.app.shadetube.ui.broadcast.BroadcastScreen
import com.app.shadetube.ui.broadcast.PodcastEpisodeScreen
import com.app.shadetube.ui.broadcast.PodcastShowScreen
import com.app.shadetube.ui.broadcast.RadioListScreen
import com.app.shadetube.ui.collection.CollectionScreen
import com.app.shadetube.ui.collection.SmartListScreen
import com.app.shadetube.ui.downloads.DownloadsScreen
import com.app.shadetube.ui.home.HomeScreen
import com.app.shadetube.ui.home.HomeSectionScreen
import com.app.shadetube.ui.library.LibraryScreen
import com.app.shadetube.ui.localfiles.LocalFilesScreen
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.onboarding.WelcomeScreen
import com.app.shadetube.ui.profile.ProfileScreen
import com.app.shadetube.ui.search.SearchScreen
import com.app.shadetube.ui.settings.BlocklistScreen
import com.app.shadetube.ui.settings.EqualizerScreen
import com.app.shadetube.ui.settings.SettingsScreen
import com.app.shadetube.ui.stats.StatsScreen
import com.app.shadetube.ui.stats.YearInReviewScreen
import com.app.shadetube.ui.transfer.ImportScreen

/**
 * [Screen] -> the composable another package implements (exact stub signatures, see tools/ui-stubs). On medium /
 * expanded windows pages are centered at their [maxWidthOf] (Home and Library adapt their own layouts instead).
 */
@Composable
internal fun ScreenContent(screen: Screen, contentPadding: PaddingValues, onWelcomeDone: () -> Unit = {}) {
    // Always the same tree (rotating / unfolding must not reset the page's state): compact = no limit.
    val max = maxWidthOf(screen).takeIf { !LocalWindowLayout.current.isCompact && it != Dp.Unspecified } ?: Dp.Infinity
    CenteredContent(max) { ScreenBody(screen, contentPadding, onWelcomeDone) }
}

/** Content width limit of a page on large windows (Unspecified = full width; the page adapts itself). */
private fun maxWidthOf(screen: Screen): Dp = when (screen) {
    Screen.Home, is Screen.HomeSection, Screen.Welcome -> Dp.Unspecified
    is Screen.Library -> AdaptiveWidths.Home
    Screen.Search, is Screen.Broadcast, is Screen.RadioList -> AdaptiveWidths.Browse
    else -> AdaptiveWidths.Detail
}

@Composable
private fun ScreenBody(screen: Screen, contentPadding: PaddingValues, onWelcomeDone: () -> Unit) {
    when (screen) {
        Screen.Home -> HomeScreen(contentPadding)
        Screen.Search -> SearchScreen(contentPadding)
        is Screen.Library -> LibraryScreen(contentPadding, screen.folderId)
        is Screen.Broadcast -> key(screen.tab) { BroadcastScreen(contentPadding, screen.tab) }
        is Screen.Collection -> CollectionScreen(contentPadding, screen.kind, screen.id, screen.autoplay)
        is Screen.SmartList -> SmartListScreen(contentPadding, screen.id)
        is Screen.Artist -> ArtistScreen(contentPadding, screen.id)
        is Screen.HomeSection -> HomeSectionScreen(contentPadding, screen.key)
        is Screen.RadioList -> RadioListScreen(contentPadding, screen.kind, screen.value, screen.title)
        is Screen.PodcastShow -> PodcastShowScreen(contentPadding, screen.feedOrAppleId)
        is Screen.PodcastEpisode -> PodcastEpisodeScreen(contentPadding, screen.episodeId)
        Screen.Profile -> ProfileScreen(contentPadding)
        Screen.Stats -> StatsScreen(contentPadding)
        is Screen.YearInReview -> YearInReviewScreen(contentPadding, screen.year)
        is Screen.Settings -> SettingsScreen(contentPadding, screen.section)
        Screen.Equalizer -> EqualizerScreen(contentPadding)
        Screen.Blocklist -> BlocklistScreen(contentPadding)
        Screen.Downloads -> DownloadsScreen(contentPadding)
        Screen.LocalFiles -> LocalFilesScreen(contentPadding)
        Screen.Welcome -> WelcomeScreen(contentPadding, onWelcomeDone)
        is Screen.Import -> ImportScreen(contentPadding, screen.pendingFromIntent, screen.youtubeUrl)
    }
}
