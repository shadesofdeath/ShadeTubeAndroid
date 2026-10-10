package com.app.shadetube.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.app.shadetube.core.Log
import com.app.shadetube.playback.PlaybackModule
import com.app.shadetube.playback.service.VoiceRequest
import com.app.shadetube.playback.service.VoiceSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A widget button: the command goes to the Player on the main thread. The widgets follow through [WidgetUpdater]
 * (player / like flows), so nothing is pushed from here. Play with nothing loaded resumes the last session, else
 * starts Liked Songs (like an empty voice request).
 */
class WidgetAction : ActionCallback {
    companion object {
        val COMMAND = ActionParameters.Key<String>("cmd")
        const val PLAY_PAUSE = "play_pause"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
        const val SHUFFLE = "shuffle"
        const val LIKE = "like"
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val cmd = parameters[COMMAND] ?: return
        withContext(Dispatchers.Main) {
            val m = PlaybackModule.instance ?: return@withContext
            val p = m.player
            when (cmd) {
                PLAY_PAUSE -> if (p.currentTrack == null) {
                    if (!VoiceSearch.play(m, VoiceRequest(""))) Log.i("widget", "play: nothing to play")
                } else {
                    p.togglePause()
                }
                NEXT -> p.next()
                PREVIOUS -> p.previous()
                SHUFFLE -> m.smartShuffle.cycleMode()
                LIKE -> {
                    val t = p.currentTrack
                    val likes = m.likes
                    if (t != null && likes != null && !p.isLive && likes.canLike(t)) likes.setLiked(t, !likes.isLiked(t))
                }
            }
        }
    }
}
