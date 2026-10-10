package com.app.shadetube

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.catalog.pick
import com.app.shadetube.core.AppLocale
import com.app.shadetube.core.Log
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.localfiles.LocalPlatform
import com.app.shadetube.spotify.SpotifyLoginContract
import com.app.shadetube.ui.shell.AppRoot
import com.app.shadetube.ui.shell.ShellRequests
import com.app.shadetube.ui.theme.ShadeTubeTheme
import com.app.shadetube.ui.theme.rememberArtworkAccent

/**
 * The single activity. Services live in [AppGraph] (built by [ShadeTubeApp]); this hosts the Compose UI, hands files /
 * playlists opened from other apps to [AppGraph.openUris] and asks for the notification permission (Android 13+).
 * The content is the shell ([AppRoot]); intents asking for Now Playing (media notification) or the debug screen (debug
 * builds) go through [ShellRequests]. The accent follows the playing track's cover.
 */
class MainActivity : ComponentActivity() {
    private val spotifyLogin = registerForActivityResult(SpotifyLoginContract()) { ok ->
        Log.i("app", "Spotify login ${if (ok) "completed" else "cancelled"}")
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i("app", "notification permission ${if (granted) "granted" else "denied"}")
    }

    /** Settings.language below Android 13 (33+ uses the system per-app locale): see [AppLocale]. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase, SettingsStore.current.language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppGraph.applyLabels(this) // the activity's (per-app) locale
        if (savedInstanceState == null) handleIntent(intent)
        requestNotificationPermission()
        setContent {
            val current by AppGraph.player.current.collectAsStateWithLifecycle()
            val accent by rememberArtworkAccent(current?.album?.images?.pick(300)?.url)
            ShadeTubeTheme(artworkAccent = accent) {
                AppRoot(onSpotifyLogin = { spotifyLogin.launch(Unit) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** VIEW / SEND / SEND_MULTIPLE of audio files or a playlist file; shell requests (open the player, debug screen). */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        ShellRequests.handle(intent)
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = LocalPlatform.urisOf(intent)
                if (uris.isNotEmpty()) AppGraph.openUris(uris, intent.type)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val perm = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(perm)
    }
}
