package com.app.shadetube.playback.service

import com.app.shadetube.R
import androidx.annotation.OptIn
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Log
import com.app.shadetube.core.RepeatMode
import com.app.shadetube.playback.PlaybackModule
import com.app.shadetube.playback.ServiceLink
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** The notification's heart (AppGraph: library likes). */
interface LikeHandler {
    fun isLiked(track: Track): Boolean
    fun canLike(track: Track): Boolean = true
    fun setLiked(track: Track, liked: Boolean)
}

/**
 * The media session service: notification, lock screen, Bluetooth / headset buttons, Android Auto / Wear. It owns
 * the MediaLibrarySession around [SessionPlayer] (which mirrors our Player) — the Player itself and its ExoPlayer
 * engine are created by AppGraph ([PlaybackModule.create]) at process start, so a process started for a media button
 * or Android Auto finds them ready here.
 *
 * Foreground: Media3 promotes the service when the session's player starts playing (startForegroundService from a
 * visible app, a media button or a controller is allowed for the mediaPlayback type on Android 14+) and demotes it when
 * playback pauses. [PlaybackServiceConnector] binds the service before playback starts ([ServiceLink]).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    companion object {
        const val CMD_LIKE = "st.like"
        const val CMD_SHUFFLE = "st.shuffle"
        const val CMD_REPEAT = "st.repeat"
    }

    private var session: MediaLibrarySession? = null
    private var sessionPlayer: SessionPlayer? = null
    private var buttonsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val module = PlaybackModule.instance
        if (module == null) { // the app graph wasn't built (should not happen: Application.onCreate builds it)
            Log.e("player", "PlaybackService without a PlaybackModule")
            stopSelf()
            return
        }
        // The media notification: our small icon and a localized channel name.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.notification_channel_playback)
                .build()
                .also { it.setSmallIcon(R.drawable.ic_notification) },
        )
        val sp = SessionPlayer(module.player, module.scope) { _ -> module.smartShuffle.cycleMode() }
        sessionPlayer = sp
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            // The shell opens Now Playing for this extra (ui/shell/ShellRequests.EXTRA_OPEN_NOW_PLAYING).
            it.putExtra("com.app.shadetube.extra.OPEN_NOW_PLAYING", true)
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val s = MediaLibrarySession.Builder(this, sp, Callback(module))
            .apply { if (launch != null) setSessionActivity(launch) }
            .setMediaButtonPreferences(buttons(module))
            .build()
        session = s
        // Heart / shuffle / repeat buttons follow the player.
        buttonsJob = module.scope.launch {
            var last: List<Int>? = null
            merge(module.player.changed, module.player.current, module.mediaButtonsChanged).collect {
                val b = buttons(module)
                val key = b.map { it.icon }
                if (key != last) {
                    last = key
                    session?.setMediaButtonPreferences(b)
                }
            }
        }
    }

    private fun buttons(module: PlaybackModule): List<CommandButton> {
        val p = module.player
        val out = ArrayList<CommandButton>()
        val t = p.currentTrack
        val likes = module.likes
        if (likes != null && t != null && !p.isLive && likes.canLike(t)) {
            val liked = likes.isLiked(t)
            out.add(
                CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setDisplayName(getString(if (liked) R.string.player_unlike_cd else R.string.player_like_cd))
                    .setSessionCommand(SessionCommand(CMD_LIKE, Bundle.EMPTY))
                    .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
        }
        val shuffleIcon = when (module.smartShuffle.mode) {
            com.app.shadetube.playback.features.SmartShuffle.Mode.Off -> CommandButton.ICON_SHUFFLE_OFF
            com.app.shadetube.playback.features.SmartShuffle.Mode.Shuffle -> CommandButton.ICON_SHUFFLE_ON
            com.app.shadetube.playback.features.SmartShuffle.Mode.Smart -> CommandButton.ICON_SHUFFLE_STAR
        }
        out.add(
            CommandButton.Builder(shuffleIcon)
                .setDisplayName(getString(R.string.player_shuffle_cd))
                .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build(),
        )
        val repeatIcon = when (p.repeat) {
            RepeatMode.Off -> CommandButton.ICON_REPEAT_OFF
            RepeatMode.All -> CommandButton.ICON_REPEAT_ALL
            RepeatMode.One -> CommandButton.ICON_REPEAT_ONE
        }
        out.add(
            CommandButton.Builder(repeatIcon)
                .setDisplayName(getString(R.string.player_repeat_cd))
                .setSessionCommand(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
        return out
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away while nothing plays: stop (Media3's default keeps a paused session around).
        val p = PlaybackModule.instance?.player
        if (p == null || !p.isPlaying) {
            p?.saveSession()
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        buttonsJob?.cancel()
        PlaybackModule.instance?.let { m ->
            m.player.saveSession()
            runCatching { m.onServiceDestroyed?.invoke() }.onFailure { Log.w("player", "flush on service destroy failed: ${it.message}") }
        }
        session?.release()
        sessionPlayer?.release()
        session = null
        sessionPlayer = null
        super.onDestroy()
    }

    private inner class Callback(private val module: PlaybackModule) : MediaLibrarySession.Callback {
        private val tree get() = module.browseTree
        private val voice = VoiceSession(module)

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_LIKE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(buttons(module))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val p = module.player
            when (customCommand.customAction) {
                CMD_LIKE -> {
                    val t = p.currentTrack
                    val likes = module.likes
                    if (t != null && likes != null) likes.setLiked(t, !likes.isLiked(t))
                }
                CMD_SHUFFLE -> module.smartShuffle.cycleMode()
                CMD_REPEAT -> p.cycleRepeat()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            session.setMediaButtonPreferences(buttons(module))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = tree.item(mediaId) ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            module.scope.launch {
                val all = runCatching { tree.children(parentId) }.getOrElse {
                    Log.w("player", "browse $parentId failed: ${it.message}")
                    emptyList()
                }
                val from = (page * pageSize).coerceAtMost(all.size)
                val to = (from + pageSize).coerceAtMost(all.size)
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params))
            }
            return future
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = voice.search(session, browser, query, params)

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = voice.searchResult(query, page, pageSize, params)

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            voice.requestOf(mediaItems)?.let { return voice.addMediaItems(it) } // voice search ("play X")
            return Futures.immediateFuture(mediaItems.map { tree.item(it.mediaId) ?: it }.toMutableList())
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Assistant / Android Auto voice ("play X on ShadeTube"): an item carrying only a search query.
            voice.requestOf(mediaItems)?.let { req ->
                return voice.setMediaItems(req) { runCatching { onPlaybackResumption(mediaSession, controller).get() }.getOrNull() }
            }
            // A lone song picked in Android Auto plays its whole list from it.
            if (mediaItems.size == 1) {
                tree.expand(mediaItems[0].mediaId)?.let { (items, index) ->
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, index, startPositionMs))
                }
            }
            val resolved = mediaItems.map { tree.item(it.mediaId) ?: it }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // A media button / Auto with nothing loaded: the last session (session.json), paused at its position.
            val p = module.player
            if (p.currentTrack == null) p.restoreSession()
            val snap = p.queue.value
            if (snap.current == null) return Futures.immediateFailedFuture(UnsupportedOperationException("nothing to resume"))
            val first = maxOf(0, snap.currentOrderIndex - SessionPlayer.WINDOW_BACK)
            val last = minOf(snap.order.size, first + SessionPlayer.WINDOW_SIZE)
            val items = (first until last).map { i -> MediaItems.of(snap.items[snap.order[i]], "q|$i|${snap.items[snap.order[i]].id}") }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, snap.currentOrderIndex - first, p.positionMs()))
        }
    }
}

/**
 * [ServiceLink]: binds [PlaybackService] (through a MediaController) before playback starts, so the session exists and
 * Media3 can put the service in the foreground as soon as the player plays. The controller is kept while the app
 * plays; Media3 keeps the started service alive on its own while playback is ongoing.
 */
@OptIn(UnstableApi::class)
class PlaybackServiceConnector(private val context: Context) : ServiceLink {
    private var future: ListenableFuture<MediaController>? = null

    override fun ensureStarted() {
        val f = future
        if (f != null && (!f.isDone || runCatching { f.get().isConnected }.getOrDefault(false))) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        future = MediaController.Builder(context, token).buildAsync()
    }

    /** Drops the connection (e.g. the queue was cleared); the service stops once nothing plays. */
    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }
}
