package com.app.shadetube.playback.service

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import com.app.shadetube.R
import com.app.shadetube.core.Log
import com.app.shadetube.playback.PlaybackModule
import kotlinx.coroutines.launch

/**
 * The target of `android.media.action.MEDIA_PLAY_FROM_SEARCH` ("Ok Google, play Tarkan on ShadeTube", other apps'
 * play-from-search intents): an invisible trampoline that resolves the query through [VoiceSearch], starts it on the
 * player and opens Now Playing (staying in front until playback is requested keeps the foreground-service start of
 * the media session allowed). Nothing found: a short toast and the app opens as it was.
 */
class PlayFromSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val module = PlaybackModule.instance
        if (module == null || intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            finish()
            return
        }
        val req = VoiceRequest.of(intent.getStringExtra(SearchManager.QUERY), intent.extras)
        Log.i("player", "play from search: \"${req.searchText}\" (${req.focus})")
        // Leaving early (back pressed) does not cancel it: the result still plays, like any voice command.
        module.scope.launch {
            val started = VoiceSearch.play(module, req)
            if (!started) Toast.makeText(applicationContext, R.string.voice_search_nothing_found, Toast.LENGTH_SHORT).show()
            openApp(nowPlaying = started)
            finish()
        }
    }

    private fun openApp(nowPlaying: Boolean) {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // The shell opens Now Playing for this extra (ui/shell/ShellRequests.EXTRA_OPEN_NOW_PLAYING).
        if (nowPlaying) launch.putExtra("com.app.shadetube.extra.OPEN_NOW_PLAYING", true)
        runCatching { startActivity(launch) }
    }
}
