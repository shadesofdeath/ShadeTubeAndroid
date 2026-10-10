package com.app.shadetube.ui.shell

import androidx.compose.foundation.layout.add
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.app.shadetube.AppGraph
import com.app.shadetube.BuildConfig
import com.app.shadetube.R
import com.app.shadetube.app.PendingOpen
import com.app.shadetube.spotify.SessionState
import com.app.shadetube.playback.PlayContext
import com.app.shadetube.ui.components.LocalWindowLayout
import com.app.shadetube.ui.components.ProfileAvatar
import com.app.shadetube.ui.components.ShadeNavigationBar
import com.app.shadetube.ui.components.ShadeNavigationRail
import com.app.shadetube.ui.components.WindowLayout
import com.app.shadetube.ui.components.ShadeSnackbarHost
import com.app.shadetube.ui.components.ShadeTab
import com.app.shadetube.ui.debug.DebugScreen
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.nav.Tab
import com.app.shadetube.ui.nowplaying.NowPlayingScreen
import com.app.shadetube.ui.onboarding.WelcomeScreen
import com.app.shadetube.ui.sheets.AddToPlaylistSheet
import com.app.shadetube.ui.sheets.NewPlaylistDialog
import com.app.shadetube.ui.sheets.SourcePickerSheet
import com.app.shadetube.ui.sheets.TrackMenuSheet
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.transfer.ExportSheet


/**
 * The app shell: 4 tabs with their own back stacks (navigation-compose), the mini player above the nav bar, Now Playing
 * expanding from it (shared cover transition), the modal sheets, the snackbar host (Navigator.message + player /
 * sync notices), first-launch onboarding and files / playlists opened from other apps.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(onSpotifyLogin: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val shell = remember(nav) { ShellState(nav, snackbar, scope, onSpotifyLogin) }
    val context = LocalContext.current
    val x = ShadeTheme.extras
    val reduce = ShadeTheme.reduceMotion
    val prefs = remember { ShellPrefs(context) }

    // First launch: onboarding until WelcomeScreen's onDone (an already connected install skips it).
    LaunchedEffect(Unit) {
        if (!prefs.onboardingDone) {
            if (AppGraph.session.loggedIn) prefs.onboardingDone = true else shell.welcome = true
        }
    }

    // Selected tab follows the back stack (system back across tabs, restored state).
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(entry) { Routes.tabOf(entry?.destination?.route)?.let { shell.currentTab = it } }

    ShellEffects(shell)

    val current by AppGraph.player.current.collectAsStateWithLifecycle()
    val hasTrack = current != null

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val layout = remember(maxWidth, maxHeight) { WindowLayout.of(maxWidth, maxHeight) }
    val rail = layout.useRail
    CompositionLocalProvider(LocalNavigator provides shell, LocalWindowLayout provides layout) {
        SharedTransitionLayout(Modifier.fillMaxSize().background(x.surface0)) {
            Box(Modifier.fillMaxSize()) {
                val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val bottom = when {
                    rail -> navInset
                    else -> navInset + 80.dp + if (hasTrack) 64.dp + 24.dp else 0.dp
                }
                // The nav host keeps its place in the tree whatever the layout (rotating / unfolding keeps every stack).
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (rail) Modifier
                                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal))
                                .padding(start = 80.dp)
                            else Modifier,
                        ),
                ) {
                    ShellNavHost(shell, PaddingValues(bottom = bottom), reduce)
                    // Lists scroll edge-to-edge under the status bar: a surface0 scrim keeps its icons readable.
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars.add(WindowInsets(top = 12.dp)))
                            .background(
                                Brush.verticalGradient(
                                    0f to x.surface0.copy(alpha = 0.92f),
                                    0.65f to x.surface0.copy(alpha = 0.78f),
                                    1f to x.surface0.copy(alpha = 0f),
                                ),
                            ),
                    )
                }

                if (rail) {
                    NavRail(
                        shell, layout.compactHeight,
                        footer = {
                            AnimatedVisibility(
                                visible = hasTrack && !shell.nowPlayingOpen,
                                enter = fadeIn(tween(ShadeMotion.STANDARD)) + expandVertically(expandFrom = Alignment.Bottom),
                                exit = fadeOut(tween(ShadeMotion.SHORT)) + shrinkVertically(shrinkTowards = Alignment.Bottom),
                            ) {
                                MiniPlayerBar(
                                    onOpen = { shell.openNowPlaying() },
                                    coverModifier = sharedCover(this@SharedTransitionLayout, this, reduce),
                                    rail = true,
                                    compact = layout.compactHeight,
                                )
                            }
                        },
                    )
                } else {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedVisibility(
                            visible = hasTrack && !shell.nowPlayingOpen,
                            enter = fadeIn(tween(ShadeMotion.STANDARD)) + expandVertically(expandFrom = Alignment.Top),
                            exit = fadeOut(tween(ShadeMotion.SHORT)) + shrinkVertically(shrinkTowards = Alignment.Top),
                        ) {
                            MiniPlayerBar(
                                onOpen = { shell.openNowPlaying() },
                                // Medium windows: the bar stays; the mini player does not stretch across the whole width.
                                modifier = Modifier
                                    .widthIn(max = 720.dp)
                                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                coverModifier = sharedCover(this@SharedTransitionLayout, this, reduce),
                            )
                        }
                        NavBar(shell)
                    }
                }

                AnimatedVisibility(
                    visible = shell.nowPlayingOpen && hasTrack,
                    enter = if (reduce) fadeIn(tween(ShadeMotion.SHORT))
                    else slideInVertically(ShadeMotion.emphasizedTween()) { it / 3 } + fadeIn(tween(ShadeMotion.STANDARD)),
                    exit = if (reduce) fadeOut(tween(ShadeMotion.SHORT))
                    else slideOutVertically(ShadeMotion.standardTween()) { it / 3 } + fadeOut(tween(ShadeMotion.STANDARD)),
                ) {
                    NowPlayingScreen(
                        panel = shell.nowPlayingPanel,
                        panelRequest = shell.panelRequest,
                        onClose = { shell.closeNowPlaying() },
                        coverModifier = sharedCover(this@SharedTransitionLayout, this, reduce),
                    )
                }

                ShellSheets(shell)

                if (shell.welcome) {
                    Box(Modifier.fillMaxSize().background(x.surface0)) {
                        WelcomeScreen(PaddingValues(bottom = navInset)) {
                            prefs.onboardingDone = true
                            shell.welcome = false
                        }
                    }
                }
                if (BuildConfig.DEBUG && shell.debug) {
                    BackHandler { shell.debug = false }
                    DebugScreen(onSpotifyLogin = onSpotifyLogin)
                }

                val snackBottom = when {
                    shell.nowPlayingOpen -> navInset + 72.dp
                    rail -> navInset + 16.dp
                    hasTrack -> navInset + 80.dp + 64.dp + 24.dp
                    else -> navInset + 80.dp + 8.dp
                }
                ShadeSnackbarHost(
                    snackbar,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = if (rail && !shell.nowPlayingOpen) 80.dp else 0.dp, bottom = snackBottom)
                        .widthIn(max = 640.dp),
                )
            }
        }
    }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun sharedCover(shared: SharedTransitionScope, visibility: AnimatedVisibilityScope, reduce: Boolean): Modifier {
    if (reduce) return Modifier
    return with(shared) {
        Modifier.sharedElement(rememberSharedContentState(key = "cover"), animatedVisibilityScope = visibility)
    }
}

/** Notices, sync events, opened files and intent requests -> snackbars / navigation. */
@Composable
private fun ShellEffects(shell: ShellState) {
    val res = LocalContext.current.resources
    LaunchedEffect(Unit) {
        AppGraph.player.notices.collect { n -> ShellMessages.notice(res, n)?.let { shell.message(it) } }
    }
    LaunchedEffect(Unit) {
        ShellRequests.openNowPlaying.collect { if (it) { ShellRequests.openNowPlaying.value = false; if (AppGraph.player.currentTrack != null) shell.openNowPlaying() } }
    }
    LaunchedEffect(Unit) {
        ShellRequests.openDebug.collect { if (it) { ShellRequests.openDebug.value = false; shell.debug = true } }
    }
    val pending by AppGraph.pendingOpen.collectAsStateWithLifecycle()
    // Loading / a playlist file: the import screen (it consumes pendingOpen); audio files play directly (the import
    // screen pushed while they were read is popped again).
    var importPushed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pending) {
        when (val p = pending) {
            null -> importPushed = false
            PendingOpen.Loading, is PendingOpen.PlaylistImport -> if (!importPushed) {
                importPushed = true
                shell.welcome = false
                shell.navigate(Screen.Import(pendingFromIntent = true))
            }
            is PendingOpen.AudioFiles -> {
                if (importPushed && Routes.isImport(shell.nav.currentBackStackEntry?.arguments?.getString(Routes.ARG))) shell.nav.popBackStack()
                importPushed = false
                AppGraph.player.playContext(p.tracks, 0, PlayContext("files", res.getString(R.string.shell_open_files_context)))
                AppGraph.consumePendingOpen()
                if (p.skipped > 0) shell.message(res.getQuantityString(R.plurals.shell_open_skipped, p.skipped, p.skipped))
                shell.openNowPlaying()
            }
            is PendingOpen.Failed -> {
                if (importPushed && Routes.isImport(shell.nav.currentBackStackEntry?.arguments?.getString(Routes.ARG))) shell.nav.popBackStack()
                importPushed = false
                shell.message(ShellMessages.openFailed(res, p))
                AppGraph.consumePendingOpen()
            }
        }
    }
}

@Composable
private fun ShellNavHost(shell: ShellState, contentPadding: PaddingValues, reduce: Boolean) {
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        when {
            reduce -> fadeIn(tween(ShadeMotion.SHORT))
            sameTab() -> slideInHorizontally(ShadeMotion.emphasizedTween()) { it / 6 } + fadeIn(tween(ShadeMotion.STANDARD))
            else -> fadeIn(tween(ShadeMotion.STANDARD))
        }
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        when {
            reduce -> fadeOut(tween(ShadeMotion.SHORT))
            sameTab() -> fadeOut(tween(ShadeMotion.STANDARD)) + slideOutHorizontally(ShadeMotion.standardTween()) { -it / 12 }
            else -> fadeOut(tween(ShadeMotion.SHORT))
        }
    }
    // Back (incl. predictive back, which seeks these): the page shrinks away towards its cover, the previous one returns.
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduce) fadeIn(tween(ShadeMotion.SHORT))
        else fadeIn(tween(ShadeMotion.STANDARD)) + scaleIn(tween(ShadeMotion.STANDARD), initialScale = 0.97f)
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduce) fadeOut(tween(ShadeMotion.SHORT))
        else scaleOut(tween(ShadeMotion.STANDARD), targetScale = 0.88f) + fadeOut(tween(ShadeMotion.STANDARD))
    }
    NavHost(
        navController = shell.nav,
        startDestination = Routes.graph(Tab.Home),
        modifier = Modifier.fillMaxSize(),
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit,
    ) {
        for (tab in Tab.entries) {
            navigation(startDestination = Routes.root(tab), route = Routes.graph(tab)) {
                composable(Routes.root(tab)) { TabRoot(tab, shell, contentPadding) }
                composable(
                    Routes.detailPattern(tab),
                    arguments = listOf(navArgument(Routes.ARG) { type = NavType.StringType }),
                ) { e ->
                    val code = e.arguments?.getString(Routes.ARG).orEmpty()
                    val screen = remember(code) { ScreenCodec.decode(code) }
                    if (screen != null) ScreenContent(screen, contentPadding) { shell.welcome = false }
                }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.sameTab(): Boolean =
    Routes.tabOf(initialState.destination.route) == Routes.tabOf(targetState.destination.route)

@Composable
private fun TabRoot(tab: Tab, shell: ShellState, contentPadding: PaddingValues) {
    when (tab) {
        Tab.Home -> ScreenContent(Screen.Home, contentPadding)
        Tab.Search -> ScreenContent(Screen.Search, contentPadding)
        Tab.Library -> ScreenContent(Screen.Library(), contentPadding)
        Tab.Broadcast -> ScreenContent(Screen.Broadcast(shell.broadcastTab), contentPadding)
    }
}

/** Nav bar: Library shows the active download ring / a dot for downloads finished elsewhere; long-press = debug screen (debug builds). */
@Composable
private fun NavBar(shell: ShellState) {
    val (badge, progress) = libraryBadge(shell)
    ShadeNavigationBar(
        selected = ShadeTab.entries[shell.currentTab.ordinal],
        onSelect = { shell.onTabSelected(Tab.entries[it.ordinal]) },
        modifier = debugLongPress(shell),
        libraryBadge = badge,
        libraryProgress = progress,
    )
}

/** Debug builds: a long press on the nav bar / rail opens the backend debug screen. */
@Composable
private fun debugLongPress(shell: ShellState): Modifier {
    if (!BuildConfig.DEBUG) return Modifier
    val longPress = LocalViewConfiguration.current.longPressTimeoutMillis
    return Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = withTimeoutOrNull(longPress * 2) { waitForUpOrCancellation(PointerEventPass.Initial) }
            if (up == null) shell.debug = true
        }
    }
}

/** Library tab badge shared by the bar and the rail: (new-download dot, active download progress or null). */
@Composable
private fun libraryBadge(shell: ShellState): Pair<Boolean, Float?> {
    val dl by AppGraph.downloads.state.collectAsStateWithLifecycle()
    val progress = if (dl.activeCount > 0) dl.items.firstOrNull { it.track.id == dl.activeId }?.progress ?: 0f else null
    var badge by rememberSaveable { mutableStateOf(false) }
    var lastDone by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(dl.doneCount) {
        if (lastDone >= 0 && dl.doneCount > lastDone && shell.currentTab != Tab.Library) badge = true
        lastDone = dl.doneCount
    }
    LaunchedEffect(shell.currentTab) { if (shell.currentTab == Tab.Library) badge = false }
    return badge to progress
}

/** Expanded windows: the navigation rail with the profile avatar on top and the vertical mini player at the bottom. */
@Composable
private fun NavRail(shell: ShellState, compact: Boolean, footer: @Composable () -> Unit) {
    val (badge, progress) = libraryBadge(shell)
    val profile by AppGraph.account.profile.collectAsStateWithLifecycle()
    val session by AppGraph.account.state.collectAsStateWithLifecycle()
    val loggedIn = session == SessionState.LoggedIn
    ShadeNavigationRail(
        selected = ShadeTab.entries[shell.currentTab.ordinal],
        onSelect = { shell.onTabSelected(Tab.entries[it.ordinal]) },
        modifier = debugLongPress(shell),
        libraryBadge = badge,
        libraryProgress = progress,
        compact = compact,
        // Short windows (landscape phones) drop the avatar: Home's header has it too.
        header = if (compact) null else ({
            ProfileAvatar(
                if (loggedIn) profile.imageUrl else "", if (loggedIn) profile.name else "",
                onClick = { shell.navigate(Screen.Profile) },
            )
        }),
        footer = footer,
    )
}

@Composable
private fun ShellSheets(shell: ShellState) {
    when (val s = shell.sheet) {
        null -> {}
        is ShellSheet.TrackMenu -> TrackMenuSheet(s.tracks, s.context, onDismiss = shell::dismissSheet)
        is ShellSheet.AddToPlaylist -> AddToPlaylistSheet(s.tracks, onDismiss = shell::dismissSheet)
        is ShellSheet.NewPlaylist -> NewPlaylistDialog(s.tracks, onDismiss = shell::dismissSheet)
        is ShellSheet.Export -> ExportSheet(s.request, onDismiss = shell::dismissSheet)
        is ShellSheet.SourcePicker -> SourcePickerSheet(s.track, onDismiss = shell::dismissSheet)
    }
}
