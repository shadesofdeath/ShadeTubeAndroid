package com.app.shadetube.ui.shell

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.app.shadetube.catalog.Track
import com.app.shadetube.library.Destination
import com.app.shadetube.ui.nav.BroadcastTab
import com.app.shadetube.ui.nav.ExportRequest
import com.app.shadetube.ui.nav.Navigator
import com.app.shadetube.ui.nav.NowPlayingPanel
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.nav.ScrollToTopRequests
import com.app.shadetube.ui.nav.Tab
import com.app.shadetube.ui.nav.TrackMenuContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A modal surface the shell hosts on top of everything (one at a time; opening one replaces the previous). */
sealed interface ShellSheet {
    data class TrackMenu(val tracks: List<Track>, val context: TrackMenuContext) : ShellSheet
    data class AddToPlaylist(val tracks: List<Track>) : ShellSheet
    data class NewPlaylist(val tracks: List<Track>) : ShellSheet
    data class Export(val request: ExportRequest) : ShellSheet
    data class SourcePicker(val track: Track) : ShellSheet
}

/**
 * The shell's [Navigator]: tabs + per-tab back stacks on a navigation-compose [NavHostController], the Now Playing
 * overlay, the modal sheets and the snackbar queue (the newest message replaces the one showing).
 */
@Stable
class ShellState(
    val nav: NavHostController,
    val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val onSpotifyLogin: () -> Unit,
) : Navigator {
    var nowPlayingOpen by mutableStateOf(false)
        private set

    /** The panel Now Playing should show (consumed by it); [NowPlayingPanel.None] = collapsed. */
    var nowPlayingPanel by mutableStateOf(NowPlayingPanel.None)

    /** Bumped on every openNowPlaying(panel) so Now Playing re-applies the same panel request. */
    var panelRequest by mutableStateOf(0)
        private set

    var sheet by mutableStateOf<ShellSheet?>(null)

    /** Welcome / onboarding flow shown over everything (first launch, or navigate(Screen.Welcome)). */
    var welcome by mutableStateOf(false)

    /** Debug builds: the backend debug screen (long-press on the nav bar). */
    var debug by mutableStateOf(false)

    /** Broadcast root's segment (navigate(Screen.Broadcast(tab))). */
    var broadcastTab by mutableStateOf(BroadcastTab.Radio)

    /** The selected tab (follows the back stack). */
    var currentTab by mutableStateOf(Tab.Home)
        internal set

    private var snackJob: Job? = null

    // ---- tabs & screens ----------------------------------------------------------------------------------------------

    override fun navigate(screen: Screen) {
        closeNowPlaying()
        when (screen) {
            Screen.Home -> selectTab(Tab.Home, popToRoot = true)
            Screen.Search -> selectTab(Tab.Search, popToRoot = false)
            is Screen.Library -> if (screen.folderId.isEmpty()) selectTab(Tab.Library, popToRoot = false) else push(screen)
            is Screen.Broadcast -> {
                broadcastTab = screen.tab
                selectTab(Tab.Broadcast, popToRoot = true)
            }
            Screen.Welcome -> welcome = true
            else -> push(screen)
        }
    }

    private fun push(screen: Screen) {
        val tab = currentTab
        nav.navigate(Routes.detail(tab, screen))
    }

    override fun open(destination: Destination, autoplay: Boolean) = navigate(destination.toScreen(autoplay))

    override fun back() {
        when {
            sheet != null -> sheet = null
            nowPlayingOpen -> closeNowPlaying()
            else -> nav.popBackStack()
        }
    }

    override fun switchTab(tab: Tab) {
        closeNowPlaying()
        selectTab(tab, popToRoot = false)
    }

    /**
     * Bottom bar / rail tap: another tab restores its stack; the current tab pops to its root, or — already at the
     * root — scrolls the root screen to the top ([ScrollToTopRequests]).
     */
    fun onTabSelected(tab: Tab) {
        when {
            tab != currentTab -> selectTab(tab, popToRoot = false)
            nav.currentBackStackEntry?.destination?.route == Routes.root(tab) -> ScrollToTopRequests.request(tab)
            else -> popToRoot(tab)
        }
    }

    private fun popToRoot(tab: Tab) {
        nav.popBackStack(Routes.root(tab), inclusive = false)
    }

    private fun selectTab(tab: Tab, popToRoot: Boolean) {
        if (tab != currentTab) {
            nav.navigate(Routes.graph(tab)) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            currentTab = tab
        }
        if (popToRoot) popToRoot(tab)
    }

    // ---- Now Playing -----------------------------------------------------------------------------------------------

    override fun openNowPlaying(panel: NowPlayingPanel) {
        nowPlayingPanel = panel
        panelRequest++
        nowPlayingOpen = true
    }

    override fun closeNowPlaying() {
        nowPlayingOpen = false
        nowPlayingPanel = NowPlayingPanel.None
    }

    // ---- sheets ----------------------------------------------------------------------------------------------------

    override fun showTrackMenu(tracks: List<Track>, context: TrackMenuContext) {
        if (tracks.isNotEmpty()) sheet = ShellSheet.TrackMenu(tracks, context)
    }

    override fun showAddToPlaylist(tracks: List<Track>) {
        sheet = ShellSheet.AddToPlaylist(tracks)
    }

    override fun showNewPlaylist(tracks: List<Track>) {
        sheet = ShellSheet.NewPlaylist(tracks)
    }

    override fun showExport(request: ExportRequest) {
        sheet = ShellSheet.Export(request)
    }

    override fun showSourcePicker(track: Track) {
        sheet = ShellSheet.SourcePicker(track)
    }

    fun dismissSheet() {
        sheet = null
    }

    // ---- snackbar --------------------------------------------------------------------------------------------------

    override fun message(text: String, actionLabel: String?, onAction: (() -> Unit)?) {
        if (text.isEmpty()) return
        snackJob?.cancel()
        snackbar.currentSnackbarData?.dismiss()
        snackJob = scope.launch {
            val r = snackbar.showSnackbar(
                message = text,
                actionLabel = actionLabel,
                withDismissAction = false,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    override fun startSpotifyLogin() = onSpotifyLogin()
}
