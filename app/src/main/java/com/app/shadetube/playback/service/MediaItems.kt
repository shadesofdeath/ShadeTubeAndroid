package com.app.shadetube.playback.service

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.app.shadetube.catalog.ModelJson
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.pick
import com.app.shadetube.core.dump
import com.app.shadetube.core.parseJsonOrNull

/** Track <-> Media3 MediaItem for the media session, the notification and Android Auto. */
object MediaItems {
    const val EXTRA_TRACK = "st.track"
    const val EXTRA_RECOMMENDED = "st.rec"

    /**
     * A playable item. [mediaId] identifies it for the session (queue items: "q|<order index>|<track id>"; browse items:
     * see [BrowseTree]); the track itself travels in the extras (controllers that only send the id back are resolved
     * through [BrowseTree]'s cache).
     */
    fun of(track: Track, mediaId: String, liveTitle: String = ""): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_TRACK, ModelJson.track(track).dump())
            if (track.recommended) putBoolean(EXTRA_RECOMMENDED, true)
        }
        val art = track.album.images.pick(500)?.url
        val meta = MediaMetadata.Builder()
            .setTitle(liveTitle.ifEmpty { track.name })
            .setArtist(if (liveTitle.isNotEmpty()) track.name else track.artistLine)
            .setAlbumTitle(track.album.name)
            .setDisplayTitle(liveTitle.ifEmpty { track.name })
            .setSubtitle(track.artistLine)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .apply {
                if (!art.isNullOrEmpty()) setArtworkUri(Uri.parse(art))
                if (track.durationMs > 0) setDurationMs(track.durationMs.toLong())
                if (track.trackNumber > 0) setTrackNumber(track.trackNumber)
            }
            .build()
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(meta).build()
    }

    /** A browsable folder (Android Auto). */
    fun folder(mediaId: String, title: String, artUrl: String? = null, mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .apply { if (!artUrl.isNullOrEmpty()) setArtworkUri(Uri.parse(artUrl)) }
            .build()
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(meta).build()
    }

    /** The track an item carries (extras), else a minimal one from its id / title. */
    fun trackOf(item: MediaItem): Track? {
        item.mediaMetadata.extras?.getString(EXTRA_TRACK)?.let { json ->
            val t = ModelJson.trackFrom(parseJsonOrNull(json))
            if (t.id.isNotEmpty() || t.name.isNotEmpty()) {
                return t.copy(recommended = item.mediaMetadata.extras?.getBoolean(EXTRA_RECOMMENDED) == true)
            }
        }
        val title = item.mediaMetadata.title?.toString().orEmpty()
        if (title.isEmpty()) return null
        return Track(id = item.mediaId, name = title)
    }
}
