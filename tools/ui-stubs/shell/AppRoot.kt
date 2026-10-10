package com.app.shadetube.ui.shell

import androidx.compose.runtime.Composable
import com.app.shadetube.ui.debug.DebugScreen

// STUB: the real shell (navigation host, tabs, mini player, Now Playing, sheets) replaces this.
@Composable
fun AppRoot(onSpotifyLogin: () -> Unit) {
    DebugScreen(onSpotifyLogin = onSpotifyLogin)
}
