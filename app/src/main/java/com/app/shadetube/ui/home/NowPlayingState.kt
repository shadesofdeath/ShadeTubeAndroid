package com.app.shadetube.ui.home

import com.app.shadetube.AppGraph
import com.app.shadetube.playback.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the browse pages highlight: the current song / its album / the playing context. */
data class NowPlayingState(
    val trackId: String = "",
    val albumId: String = "",
    val contextUri: String = "",
    /** Playing or buffering (the bars move). */
    val playing: Boolean = false,
    /** Paused on this context (play button shows "play" but the list is the current one). */
    val paused: Boolean = false,
) {
    fun isContext(uri: String) = uri.isNotEmpty() && contextUri == uri
    fun isPlayingContext(uri: String) = playing && isContext(uri)
}

/** The player's state as the browse pages need it (AppGraph.player flows). */
fun nowPlayingFlow(): Flow<NowPlayingState> {
    val p = AppGraph.player
    return combine(p.current, p.status, p.queue) { t, s, q ->
        NowPlayingState(
            trackId = t?.id.orEmpty(),
            albumId = t?.album?.id.orEmpty(),
            contextUri = q.context.uri,
            playing = t != null && (s == Status.Playing || s == Status.Buffering || s == Status.Resolving),
            paused = t != null && s == Status.Paused,
        )
    }.distinctUntilChanged()
}
