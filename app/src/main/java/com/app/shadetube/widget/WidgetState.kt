package com.app.shadetube.widget

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.pick
import com.app.shadetube.playback.Status
import com.app.shadetube.playback.features.SmartShuffle

/** What the home-screen widgets show (pure; built from the player by [WidgetUpdater]). */
data class WidgetState(
    /** false = nothing loaded: the empty state ("Bir şey çal"). */
    val hasTrack: Boolean = false,
    val title: String = "",
    val artist: String = "",
    /** The cover to show (a ~300 px image of the album), null = placeholder. */
    val coverUrl: String? = null,
    /** Playing or about to (resolving / buffering): the button shows pause. */
    val playing: Boolean = false,
    /** A radio station: no progress, no like. */
    val live: Boolean = false,
    val liked: Boolean = false,
    val canLike: Boolean = false,
    val shuffle: SmartShuffle.Mode = SmartShuffle.Mode.Off,
    /** 0..1 of the current item; 0 when unknown / live. */
    val progress: Float = 0f,
) {
    /** Everything but the progress: a change here is pushed at once, progress only every [WidgetUpdater.PROGRESS_EVERY_MS]. */
    val content: WidgetState get() = if (progress == 0f) this else copy(progress = 0f)

    companion object {
        const val COVER_MIN_WIDTH = 300

        fun of(
            track: Track?,
            status: Status,
            isLive: Boolean,
            liveTitle: String,
            positionMs: Long,
            durationMs: Long,
            liked: Boolean,
            canLike: Boolean,
            shuffle: SmartShuffle.Mode,
        ): WidgetState {
            if (track == null) return WidgetState(shuffle = shuffle)
            val showLive = isLive && liveTitle.isNotBlank()
            return WidgetState(
                hasTrack = true,
                // A station: its current song (ICY title) over the station name.
                title = if (showLive) liveTitle else track.name,
                artist = if (showLive) track.name else track.artistLine,
                coverUrl = track.album.images.pick(COVER_MIN_WIDTH)?.url?.takeIf { it.isNotEmpty() },
                playing = status == Status.Playing || status == Status.Buffering || status == Status.Resolving,
                live = isLive,
                liked = liked && !isLive,
                canLike = canLike && !isLive,
                shuffle = shuffle,
                progress = if (isLive || durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
            )
        }
    }
}
