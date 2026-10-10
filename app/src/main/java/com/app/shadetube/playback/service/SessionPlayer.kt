package com.app.shadetube.playback.service

import androidx.annotation.OptIn
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.RepeatMode
import com.app.shadetube.playback.PlayContext
import com.app.shadetube.playback.Player
import com.app.shadetube.playback.Status
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * What the media session (notification, lock screen, Bluetooth / headset buttons, Android Auto, Wear) sees: a Media3
 * Player that mirrors our [Player] and routes every command back to it.
 *
 * Why not a ForwardingPlayer around ExoPlayer: our Player owns the queue (ExoPlayer only ever holds the current item and
 * the prepared next one), and a crossfade moves playback between two ExoPlayer instances. So controllers see our queue
 * (a window of the play order around the current item), our status and position, and next / previous / seek / shuffle /
 * repeat / speed / queue edits go to our Player, which applies its own rules (kara liste, the 3 s restart of
 * "previous", repeat-one, smart shuffle...).
 *
 * Main thread only (the application looper).
 */
@OptIn(UnstableApi::class)
class SessionPlayer(
    private val player: Player,
    private val scope: CoroutineScope,
    /** The shuffle command of a controller (the app cycles off -> shuffle -> smart shuffle; null = plain toggle). */
    private val onShuffle: ((Boolean) -> Unit)? = null,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    companion object {
        /** Queue items shown to controllers: this many before the current one ... */
        const val WINDOW_BACK = 25

        /** ... and this many in all. */
        const val WINDOW_SIZE = 100
    }

    private var job: Job? = null

    /** Order index of the first queue item in the published playlist. */
    private var windowFirst = 0

    init {
        job = scope.launch {
            merge(player.changed, player.liveTitle).collect { invalidateState() }
        }
    }

    private fun window(total: Int, pos: Int): IntRange {
        val first = if (pos < 0) 0 else maxOf(0, minOf(pos - WINDOW_BACK, total - WINDOW_SIZE))
        return first until minOf(total, first + WINDOW_SIZE)
    }

    private fun queueId(orderIndex: Int, t: Track) = "q|$orderIndex|${t.id}"

    override fun getState(): State {
        val snap = player.queue.value
        val pos = snap.currentOrderIndex
        val w = window(snap.order.size, pos)
        windowFirst = w.first
        val live = player.isLive
        val liveTitle = player.liveTitle.value
        val playlist = w.map { i ->
            val t = snap.items[snap.order[i]]
            val isCurrent = i == pos
            val durationMs = if (isCurrent && !live) player.durationMs() else t.durationMs.toLong()
            MediaItemData.Builder("${snap.order[i]}|${t.id}")
                .setMediaItem(MediaItems.of(t, queueId(i, t), if (isCurrent && live) liveTitle else ""))
                .setDurationUs(if (live && isCurrent) C.TIME_UNSET else if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                .setIsSeekable(!(live && isCurrent))
                .setIsDynamic(live && isCurrent)
                .build()
        }
        val status = player.statusNow
        val cur = snap.current
        val playbackState = when {
            cur == null -> Media3Player.STATE_IDLE
            status == Status.Resolving || status == Status.Buffering || status == Status.Error -> Media3Player.STATE_BUFFERING
            status == Status.Playing || status == Status.Paused -> Media3Player.STATE_READY
            else -> Media3Player.STATE_ENDED // Idle with an item: the queue ran out
        }
        val playWhenReady = status == Status.Playing || status == Status.Buffering || status == Status.Resolving
        val commands = Media3Player.Commands.Builder().addAll(
            Media3Player.COMMAND_PLAY_PAUSE,
            Media3Player.COMMAND_PREPARE,
            Media3Player.COMMAND_STOP,
            Media3Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Media3Player.COMMAND_SEEK_TO_PREVIOUS,
            Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Media3Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Media3Player.COMMAND_SET_SHUFFLE_MODE,
            Media3Player.COMMAND_SET_REPEAT_MODE,
            Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Media3Player.COMMAND_GET_TIMELINE,
            Media3Player.COMMAND_GET_METADATA,
            Media3Player.COMMAND_SET_MEDIA_ITEM,
            Media3Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Media3Player.COMMAND_RELEASE,
        ).apply {
            if (cur != null && !live) {
                add(Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                add(Media3Player.COMMAND_SET_SPEED_AND_PITCH)
                add(Media3Player.COMMAND_SEEK_BACK)
                add(Media3Player.COMMAND_SEEK_FORWARD)
            }
            // "next" works whenever there is something to go to (the user's next wraps around, like the desktop app).
            if (snap.order.size > 1) {
                add(Media3Player.COMMAND_SEEK_TO_NEXT)
                add(Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
        }.build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (pos >= 0) pos - w.first else 0)
            .setContentPositionMs(PositionSupplier { player.positionMs() })
            .setContentBufferedPositionMs(PositionSupplier { (player.bufferedFraction() * player.durationMs()).toLong() })
            .setShuffleModeEnabled(snap.shuffle)
            .setRepeatMode(
                when (snap.repeat) {
                    RepeatMode.Off -> Media3Player.REPEAT_MODE_OFF
                    RepeatMode.All -> Media3Player.REPEAT_MODE_ALL
                    RepeatMode.One -> Media3Player.REPEAT_MODE_ONE
                },
            )
            .setPlaybackParameters(PlaybackParameters(player.speed()))
            .setIsLoading(status == Status.Resolving || status == Status.Buffering)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setMaxSeekToPreviousPositionMs(Player.RESTART_THRESHOLD_MS)
            .build()
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) player.play() else player.pause()
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> = done() // play() resolves what it needs

    override fun handleStop(): ListenableFuture<*> {
        player.pause()
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        job?.cancel()
        job = null
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        player.setRepeat(
            when (repeatMode) {
                Media3Player.REPEAT_MODE_ALL -> RepeatMode.All
                Media3Player.REPEAT_MODE_ONE -> RepeatMode.One
                else -> RepeatMode.Off
            },
        )
        return done()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        onShuffle?.invoke(shuffleModeEnabled) ?: player.setShuffle(shuffleModeEnabled)
        return done()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        player.setSpeed(playbackParameters.speed)
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val pos = player.currentOrderIndex
        when (seekCommand) {
            Media3Player.COMMAND_SEEK_TO_NEXT, Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> player.next()
            Media3Player.COMMAND_SEEK_TO_PREVIOUS, Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> player.previous()
            Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Media3Player.COMMAND_SEEK_BACK, Media3Player.COMMAND_SEEK_FORWARD ->
                player.seek(maxOf(0L, positionMs))
            else -> { // SEEK_TO_MEDIA_ITEM / SEEK_TO_DEFAULT_POSITION
                val target = windowFirst + mediaItemIndex
                if (mediaItemIndex < 0 || target == pos) {
                    player.seek(if (positionMs == C.TIME_UNSET) 0 else positionMs)
                } else {
                    player.jumpTo(target)
                    if (positionMs != C.TIME_UNSET && positionMs > 0) player.seek(positionMs)
                }
            }
        }
        return done()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        // Our own queue handed back (playback resumption): keep it and go to the asked item.
        val snap = player.queue.value
        val ownQueue = mediaItems.isNotEmpty() && mediaItems.all { it.mediaId.startsWith("q|") }
        if (ownQueue && snap.current != null) {
            val idx = if (startIndex == C.INDEX_UNSET) 0 else startIndex
            val orderIndex = mediaItems.getOrNull(idx)?.mediaId?.split('|')?.getOrNull(1)?.toIntOrNull()
            if (orderIndex != null && orderIndex != snap.currentOrderIndex && orderIndex in snap.order.indices) player.jumpTo(orderIndex)
            return done()
        }
        val tracks = mediaItems.mapNotNull { MediaItems.trackOf(it) }
        if (tracks.isEmpty()) return done()
        val start = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, tracks.size - 1)
        val ctx = BrowseTree.contextOf(mediaItems.getOrNull(start)?.mediaId) ?: PlayContext.QUEUE
        player.playContext(tracks, start, ctx)
        if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) player.seek(startPositionMs)
        return done()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val tracks = mediaItems.mapNotNull { MediaItems.trackOf(it) }
        if (tracks.isNotEmpty()) player.insertAt(windowFirst + index, tracks)
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        for (i in toIndex - 1 downTo fromIndex) player.removeAt(windowFirst + i)
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val count = toIndex - fromIndex
        if (count == 1) player.move(windowFirst + fromIndex, windowFirst + newIndex)
        return done()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        handleRemoveMediaItems(fromIndex, toIndex)
        return handleAddMediaItems(fromIndex, mediaItems)
    }
}
