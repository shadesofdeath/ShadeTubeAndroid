package com.app.shadetube.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf
import com.app.shadetube.catalog.Track
import com.app.shadetube.library.CollectionKind
import com.app.shadetube.library.Destination

/**
 * Navigation contract shared by every screen package. The shell (`ui/shell`) implements [Navigator] and hosts the
 * screens; screens only talk to [LocalNavigator] and never to each other.
 *
 * Every screen composable takes `contentPadding: PaddingValues` as its first parameter: the space the shell reserves
 * at the bottom for the mini player + navigation bar (and the status bar at the top for screens without a top bar).
 */
sealed interface Screen {
    // ---- tab roots ----
    data object Home : Screen
    data object Search : Screen
    data class Library(val folderId: String = "") : Screen
    data class Broadcast(val tab: BroadcastTab = BroadcastTab.Radio) : Screen

    // ---- browse (ui/home, ui/search, ui/library, ui/collection, ui/artist) ----
    /** Playlist / album / Liked Songs page. [autoplay] = opened from a card's play button (C++ "#play"). */
    data class Collection(val kind: CollectionKind, val id: String, val autoplay: Boolean = false) : Screen
    data class SmartList(val id: String) : Screen
    data class Artist(val id: String) : Screen
    /** "Tümünü gör" of a Home section, identified by the section's key. */
    data class HomeSection(val key: String) : Screen

    // ---- broadcast (ui/broadcast) ----
    data class RadioList(val kind: RadioListKind, val value: String = "", val title: String = "") : Screen
    /** A podcast show by feed URL or Apple id. */
    data class PodcastShow(val feedOrAppleId: String) : Screen
    data class PodcastEpisode(val episodeId: String) : Screen

    // ---- profile / settings (ui/profile, ui/stats, ui/settings, ui/downloads, ui/localfiles) ----
    data object Profile : Screen
    data object Stats : Screen
    data class YearInReview(val year: Int) : Screen
    data class Settings(val section: SettingsSection = SettingsSection.Root) : Screen
    data object Equalizer : Screen
    data object Blocklist : Screen
    data object Downloads : Screen
    data object LocalFiles : Screen

    // ---- flows (ui/onboarding, ui/transfer) ----
    data object Welcome : Screen
    /** Playlist import; [pendingFromIntent] = a playlist file another app opened with us (AppGraph.pendingOpen). */
    data class Import(val pendingFromIntent: Boolean = false, val youtubeUrl: String = "") : Screen
}

enum class Tab { Home, Search, Library, Broadcast }
enum class BroadcastTab { Radio, Podcasts }
enum class RadioListKind { Top, Tag, Country, Favorites, Recent, Search }

enum class SettingsSection {
    Root, Account, Connections, Playback, Sound, Appearance, Downloads, LocalMusic, Storage, About, Licenses,
}

/** Which Now Playing panel to open with it. */
enum class NowPlayingPanel { None, Queue, Lyrics, Related, Karaoke }

/**
 * Where a track menu was opened from (C++ showTrackMenu's playlistId / recommendKey): [playlistId] enables "remove from
 * this playlist"; [recommendKey] is the "Geliştir" collection whose recommended rows get add / hide.
 */
data class TrackMenuContext(
    val playlistId: String = "",
    val recommendKey: String = "",
    /** The row is in the queue (Now Playing › Sıradaki): offers "remove from queue". */
    val queueIndex: Int = -1,
)

/** What to export (playlist export sheet). */
sealed interface ExportRequest {
    data class Collection(val kind: CollectionKind, val id: String, val name: String) : ExportRequest
    data object Queue : ExportRequest
    data class Tracks(val name: String, val tracks: List<Track>) : ExportRequest
}

interface Navigator {
    fun navigate(screen: Screen)
    /** Library-layer destinations (cards, rows) mapped to screens. */
    fun open(destination: Destination, autoplay: Boolean = false)
    fun back()
    fun switchTab(tab: Tab)

    fun openNowPlaying(panel: NowPlayingPanel = NowPlayingPanel.None)
    fun closeNowPlaying()

    fun showTrackMenu(tracks: List<Track>, context: TrackMenuContext = TrackMenuContext())
    fun showAddToPlaylist(tracks: List<Track>)
    fun showNewPlaylist(tracks: List<Track> = emptyList())
    fun showExport(request: ExportRequest)
    /** "Yanlış eşleşme?" / "Kaynağı değiştir" for a track (the playing one when it is current). */
    fun showSourcePicker(track: Track)

    /** Snackbar. */
    fun message(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null)

    fun startSpotifyLogin()
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("LocalNavigator not provided") }
