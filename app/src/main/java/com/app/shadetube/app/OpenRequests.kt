package com.app.shadetube.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.app.shadetube.catalog.Track
import com.app.shadetube.playlistio.ImportPreview

/**
 * Something handed to the app from outside (a VIEW / SEND intent: audio files or a playlist file), prepared for the UI
 * to act on: [AppGraph.pendingOpen]. The UI shows it (play / import preview) and then calls
 * [AppGraph.consumePendingOpen].
 */
sealed interface PendingOpen {
    /** Reading the files / the playlist. */
    data object Loading : PendingOpen

    /** Audio files, remembered as opened files (they keep playing after a restart): play them as a queue. */
    data class AudioFiles(val tracks: List<Track>, val skipped: Int) : PendingOpen

    /** A playlist file: show the import preview, then `AppGraph.transfer.commit(preview, name, alsoOnSpotify)`. */
    data class PlaylistImport(val preview: ImportPreview) : PendingOpen

    data class Failed(val reason: Reason) : PendingOpen {
        enum class Reason { NothingPlayable, PlaylistUnreadable }
    }
}

/** How an incoming Uri is treated. */
object OpenRequests {
    private val PLAYLIST_MIMES = setOf(
        "audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl",
        "text/csv", "text/comma-separated-values", "application/xspf+xml", "application/json",
    )
    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "csv", "xspf", "json")

    /** A playlist file (by MIME type or the name's extension); else the items are opened as audio files. */
    fun isPlaylist(mimeType: String?, displayName: String): Boolean {
        val mime = mimeType?.lowercase().orEmpty()
        if (mime in PLAYLIST_MIMES) return true
        return displayName.substringAfterLast('.', "").lowercase() in PLAYLIST_EXTENSIONS
    }

    /** The document's display name ("" when the provider has none), or the path's last segment. */
    fun displayName(resolver: ContentResolver, uri: String): String {
        val u = Uri.parse(uri)
        if (u.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                resolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getString(0).orEmpty()
                }
            }
        }
        return u.lastPathSegment.orEmpty()
    }

    /** A name the playlist reader can detect the format from (the MIME type's extension when the name has none). */
    fun playlistName(displayName: String, mimeType: String?): String {
        if (displayName.substringAfterLast('.', "").lowercase() in PLAYLIST_EXTENSIONS) return displayName
        val ext = when (mimeType?.lowercase()) {
            "text/csv", "text/comma-separated-values" -> "csv"
            "application/xspf+xml" -> "xspf"
            "application/json" -> "json"
            null, "" -> return displayName
            else -> "m3u8"
        }
        return displayName.ifEmpty { "playlist" } + "." + ext
    }
}
