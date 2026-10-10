package com.app.shadetube.ui.sheets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Log
import com.app.shadetube.library.WebLinks
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Work started from a sheet that must outlive it (the sheet leaves composition as soon as a row is tapped): main
 * thread, failures logged.
 */
internal val sheetScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> Log.e("ui", "sheet action failed", t) },
)

/** Shareable links of a track: Spotify for Spotify tracks, else its YouTube video, else MusicBrainz. */
object TrackLinks {
    fun url(track: Track, videoId: String? = null): String {
        val id = track.id
        if (id.startsWith("spotify:track:")) return "https://open.spotify.com/track/" + id.removePrefix("spotify:track:")
        val video = videoId?.takeIf { it.isNotEmpty() }
            ?: track.videoId.takeIf { it.isNotEmpty() }
            ?: id.takeIf { it.startsWith("yt:") }?.removePrefix("yt:")
            ?: runCatching { AppGraph.matchService.cachedMatchNow(id)?.videoId }.getOrNull()
        if (!video.isNullOrEmpty()) return "https://youtu.be/$video"
        return WebLinks.recording(id)
    }

    /** The system share sheet with "Title — Artist\nurl"; false when there is no link. */
    fun share(context: Context, track: Track, url: String = url(track)): Boolean {
        if (url.isEmpty()) return false
        val text = buildString {
            append(track.name)
            if (track.artistLine.isNotEmpty()) append(" — ").append(track.artistLine)
            append('\n').append(url)
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, context.getString(R.string.sheets_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    fun copy(context: Context, url: String): Boolean {
        if (url.isEmpty()) return false
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("link", url))
        return true
    }
}
