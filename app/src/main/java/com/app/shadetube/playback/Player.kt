package com.app.shadetube.playback

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.AudioQuality
import com.app.shadetube.core.Files
import com.app.shadetube.core.Log
import com.app.shadetube.core.RepeatMode
import com.app.shadetube.core.Settings
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.appScope
import com.app.shadetube.playback.dsp.EqSettings
import com.app.shadetube.youtube.Match
import com.app.shadetube.youtube.MatchFailure
import com.app.shadetube.youtube.Resolved
import com.app.shadetube.youtube.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Playback orchestration (port of player::Player, "the Spotube logic"): queue, shuffle, repeat, YouTube resolution,
 * prefetch of the next track, gapless handoff / crossfade and error recovery. The single source of truth for what
 * plays; the [Engine] (ExoPlayer) only renders the current item and the prepared next one, and the media session
 * ([com.app.shadetube.playback.service.SessionPlayer]) mirrors this object.
 *
 * Lives on the main thread (every public function must be called there); heavy work (matching, manifest fetches) runs
 * in coroutines and is cancelled / ignored when superseded (each load has a tag; events of an older tag are dropped).
 *
 * State is exposed as StateFlows ([status], [current], [queue], [position], [liveTitle], [lastError], [source],
 * [speed], [volume]); [events] feeds stats / scrobbling / podcast progress, [notices] the toasts.
 */
class Player(
    private val engine: Engine,
    private val resolver: TrackStreamResolver,
    /** Main-thread scope; its dispatcher must post (not run immediately), like the C++ Dispatcher::post. */
    private val scope: CoroutineScope,
    /** Prefetch lane (C++ Priority::Low). */
    private val lowDispatcher: CoroutineDispatcher = AppDispatchers.low,
    private val settings: () -> Settings = { SettingsStore.current },
    private val updateSettings: ((Settings) -> Settings) -> Unit = { SettingsStore.update(it) },
    /** session.json; null = no persistence (tests). */
    private val sessionFile: (() -> File)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Writes session.json off the main thread (tests: inline). */
    private val sessionWriter: (block: () -> Unit) -> Unit = { block -> appScope.launch(AppDispatchers.io) { block() } },
) : EngineListener {

    companion object {
        /** Preload the next item this long before the end (C++ kPrefetchWindowMs). */
        const val PREFETCH_WINDOW_MS = 25_000L

        /** A terminal failure moves on after this pause, so the user sees what happened. */
        const val ERROR_ADVANCE_MS = 1500L

        /** previous(): later than this into a track restarts it instead of going back. */
        const val RESTART_THRESHOLD_MS = 3000L

        /** Live: reconnect backoff (0.5 / 1 / 2 / 4 / 8 s). */
        val LIVE_BACKOFF_MS = longArrayOf(500, 1000, 2000, 4000, 8000)

        /** Live: consecutive failures before the station fails for good ... */
        const val LIVE_MAX_FAILURES = 6

        /** ... or this many when nothing ever played. */
        const val LIVE_MAX_FAILURES_NEVER_PLAYED = 3

        /** Live: this much audio resets the failure count. */
        const val LIVE_HEALTHY_MS = 10_000L

        /** Live: paused longer than this releases the connection; play resumes live. */
        const val LIVE_RELEASE_AFTER_PAUSE_MS = 60_000L

        /**
         * Media type hint for a local file, by extension (mp3, m4a/mp4/aac, flac, wav, wma, ogg/opus, webm, mka). "" =
         * unknown (the extractor sniffs the content).
         */
        fun localMimeType(path: String): String {
            val dot = path.lastIndexOf('.')
            if (dot < 0 || path.indexOfAny(charArrayOf('\\', '/'), dot) >= 0) return ""
            return when (path.substring(dot + 1).lowercase(Locale.ROOT)) {
                "mp3" -> "audio/mpeg"
                "m4a", "mp4", "m4b" -> "audio/mp4"
                "aac", "adts" -> "audio/aac"
                "flac" -> "audio/flac"
                "wav", "wave" -> "audio/wav"
                "wma" -> "audio/x-ms-wma"
                "ogg", "oga", "opus" -> "audio/ogg"
                "webm", "weba" -> "audio/webm"
                "mka" -> "audio/x-matroska"
                else -> ""
            }
        }
    }

    // ---- hooks (set by AppGraph before use) --------------------------------------------------------------------------

    /**
     * Kara liste: asked whenever the player picks an item by itself — auto-advance, next / previous, the start of a
     * context played without a start index (header play / shuffle), the repeat wrap and the prefetch. true = pass over
     * it. An item the user picks directly (playContext's start index, jumpTo) still plays.
     */
    var shouldSkip: ((Track) -> Boolean)?
        get() = q.shouldSkip
        set(v) {
            q.shouldSkip = v
        }

    /**
     * Endless playback: fired (posted) when a track starts with at most one playable item left after it and repeat
     * off, and again when the queue runs out. The handler may append later with [extend].
     */
    var onQueueLow: (() -> Unit)? = null

    /** Downloads, local files, downloaded episodes: asked in order before each resolve (C++ localFileResolvers). */
    val localSources: MutableList<LocalSourceResolver> = ArrayList()
    var directStreams: DirectStreamProvider? = null
    var liveStreams: LiveStreamProvider? = null
    var startPositions: StartPositionProvider? = null
    var qualityProvider: QualityProvider = QualityProvider { settings().quality }
    var serviceLink: ServiceLink? = null

    /**
     * A live item failed for good (before the error notice and the move to the next item): kind UnsupportedFormat for a
     * codec / container the device can't play, Network when the station could not be reached or kept dropping.
     */
    var onLiveFailed: ((station: Track, kind: EngineErrorKind) -> Unit)? = null

    // ---- state -------------------------------------------------------------------------------------------------------

    private val q = PlayQueue()
    private var context = PlayContext()
    private var queueGen = 0L

    private var statusValue = Status.Idle
    private var tagCounter = 0L
    private var currentTag = 0L
    private var match: Match? = null
    private var stream: ResolvedStream? = null

    private class Prepared(
        val tag: Long,
        var orderIndex: Int,
        /** The item prepared (the gapless handoff re-checks its slot). */
        val trackId: String,
        /** null for a local file. */
        val resolved: Resolved?,
        val source: EngineSource,
    )

    private var prepared: Prepared? = null
    private var prefetchTag = 0L
    private var prefetchJob: Job? = null
    private var resolveJob: Job? = null

    /** The track whose preload the engine rejected (not preloaded again). */
    private var preloadFailed = ""
    private var retries = 0
    private var pendingSeekMs = -1L

    /** Opus / WebM allowed; an unsupported-format error falls back to AAC for the rest of the session. */
    private var allowWebm = true

    /** The current item plays from a local file (no YouTube fallbacks). */
    private var currentLocal = false

    /** Consecutive tracks that failed to start (stops a queue that can't play). */
    private var failStreak = 0

    /** The load scheduleErrorAdvance() is for. */
    private var errorTag = 0L

    /** Stopped because the queue ran out (extend() continues). */
    private var endedAtEnd = false
    private var lastErrorText = ""

    /** The current item plays through [liveStreams] (internet radio) / [directStreams] (podcast episode). */
    private var live = false
    private var direct = false
    private var liveTitleValue = ""

    // Live reconnect bookkeeping.
    private var liveAttempt = 0
    private var liveFailures = 0
    private var liveEverPlayed = false
    private var liveHealthyMs = 0L
    private var liveListenedMs = 0L
    private var liveReleased = false
    private var liveReconnectJob: Job? = null
    private var liveReleaseJob: Job? = null

    private var tickerJob: Job? = null
    private var lastTickMs = 0L
    private var sinceProgressMs = 0L

    /** The item TrackStarted was emitted for (TrackEnded pairs with it). */
    private var startedTrack: Track? = null

    // ---- flows -------------------------------------------------------------------------------------------------------

    private val _status = MutableStateFlow(Status.Idle)
    private val _current = MutableStateFlow<Track?>(null)
    private val _queue = MutableStateFlow(QueueSnapshot())
    private val _position = MutableStateFlow(PositionState())
    private val _liveTitle = MutableStateFlow("")
    private val _lastError = MutableStateFlow<PlaybackError?>(null)
    private val _source = MutableStateFlow(CurrentSource())
    private val _speed = MutableStateFlow(1f)
    private val _volume = MutableStateFlow(settings().volume)
    private val _isLive = MutableStateFlow(false)
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _notices = MutableSharedFlow<PlayerNotice>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val status: StateFlow<Status> = _status.asStateFlow()
    val current: StateFlow<Track?> = _current.asStateFlow()
    val queue: StateFlow<QueueSnapshot> = _queue.asStateFlow()

    /** Updated 4x per second while playing and on every change. */
    val position: StateFlow<PositionState> = _position.asStateFlow()

    /** The station's current song (ICY StreamTitle, e.g. "Artist - Title"), "" when it sends none / not live. */
    val liveTitle: StateFlow<String> = _liveTitle.asStateFlow()
    val lastError: StateFlow<PlaybackError?> = _lastError.asStateFlow()
    val source: StateFlow<CurrentSource> = _source.asStateFlow()
    val speed: StateFlow<Float> = _speed.asStateFlow()
    val volume: StateFlow<Float> = _volume.asStateFlow()
    val isLiveFlow: StateFlow<Boolean> = _isLive.asStateFlow()
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()
    val notices: SharedFlow<PlayerNotice> = _notices.asSharedFlow()

    /** Fires (conflated) after every state / track / queue change (C++ onChanged; not for position ticks). */
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    init {
        engine.listener = this
        val s = settings()
        engine.setVolume(s.volume)
        q.shuffle = s.shuffle
        q.repeat = s.repeat
        applyAudioSettings()
    }

    /** Follows the equalizer settings (the settings page edits them at any time). Call once (AppGraph). */
    fun observeSettings(flow: kotlinx.coroutines.flow.Flow<Settings> = SettingsStore.flow) {
        scope.launch {
            flow.map { EqSettings.of(it.eqEnabled, it.eqPreampDb, it.eqGains) }.distinctUntilChanged().collect { engine.setEqualizer(it) }
        }
    }

    // ---- read-only state ---------------------------------------------------------------------------------------------

    val currentTrack: Track? get() = q.current
    val statusNow: Status get() = statusValue
    val isPlaying: Boolean get() = statusValue == Status.Playing || statusValue == Status.Buffering
    val items: List<Track> get() = q.items
    val order: List<Int> get() = q.order
    val currentOrderIndex: Int get() = q.pos
    val playContext: PlayContext get() = context
    val shuffle: Boolean get() = q.shuffle
    val repeat: RepeatMode get() = q.repeat
    val currentMatch: Match? get() = match
    val currentStream: ResolvedStream? get() = stream
    val isLive: Boolean get() = live
    val isDirect: Boolean get() = direct
    val isLocal: Boolean get() = currentLocal

    /** Bumped by playContext() / replaceUpcoming(): a new queue (features keep per-queue state against it). */
    val queueGeneration: Long get() = queueGen
    val lastErrorMessage: String get() = lastErrorText

    fun positionMs(): Long {
        if (pendingSeekMs >= 0) return pendingSeekMs
        if (live) return liveListenedMs
        if (engine.currentTag != currentTag || currentTag == 0L) return 0
        return engine.positionMs()
    }

    fun durationMs(): Long {
        if (live) return 0
        val d = if (engine.currentTag == currentTag && currentTag != 0L) engine.durationMs() else 0
        if (d > 0) return d
        return q.current?.durationMs?.toLong() ?: 0
    }

    fun bufferedFraction(): Float = if (engine.currentTag == currentTag && currentTag != 0L) engine.bufferedFraction() else 0f

    /** Playable (not skipped) items after the current slot, counting at most [limit]. */
    fun remainingPlayable(limit: Int = Int.MAX_VALUE): Int = q.remainingPlayable(limit)

    /** playable and not skipped, or -1. */
    fun firstPlayable(tracks: List<Track>): Int = q.firstPlayable(tracks)

    // ---- notifications -----------------------------------------------------------------------------------------------

    private fun setStatus(s: Status) {
        statusValue = s
    }

    private fun notifyChanged() {
        val cur = q.current
        val snap = q.snapshot(context, queueGen)
        if (snap != _queue.value) {
            _queue.value = snap
            _events.tryEmit(PlayerEvent.QueueChanged(snap))
        }
        _current.value = cur
        if (_status.value != statusValue) {
            _status.value = statusValue
            _events.tryEmit(PlayerEvent.StatusChanged(cur, statusValue))
        }
        _isLive.value = live
        _speed.value = speed()
        _source.value = CurrentSource(
            match = match,
            codec = stream?.codec.orEmpty(),
            mimeType = stream?.mimeType.orEmpty(),
            bitrateKbps = stream?.bitrateKbps ?: 0,
            source = stream?.source.orEmpty(),
            local = currentLocal,
            live = live,
            direct = direct,
        )
        updatePosition()
        if (cur != null) emitProgress(cur)
        updateTicker()
        _changed.tryEmit(Unit)
    }

    private fun updatePosition() {
        _position.value = PositionState(positionMs(), durationMs(), bufferedFraction())
    }

    private fun emitProgress(cur: Track) {
        _events.tryEmit(PlayerEvent.Progress(cur, statusValue, positionMs(), durationMs(), isPlaying, liveTitleValue))
    }

    private fun notice(n: PlayerNotice) {
        _notices.tryEmit(n)
    }

    /** Features (radio, sleep timer, SponsorBlock, smart shuffle) post their toasts through the player's stream. */
    fun emitNotice(n: PlayerNotice) = notice(n)

    private fun error(e: PlaybackError, track: Track? = null, failure: MatchFailure? = null) {
        _lastError.value = e
        notice(PlayerNotice.Error(e, track, failure))
    }

    /** onTrackChanged: the item that just became current. */
    private fun trackChanged(t: Track) {
        startedTrack?.let { prev ->
            _events.tryEmit(PlayerEvent.TrackEnded(prev, _position.value.positionMs, _position.value.durationMs, EndReason.Replaced))
        }
        startedTrack = t
        _lastError.value = null
        _events.tryEmit(PlayerEvent.TrackStarted(t, context, live || liveStreams?.isLive(t) == true, isPodcastId(t.id), nowMs()))
        saveSession()
    }

    private fun post(block: () -> Unit) {
        scope.launch { block() }
    }

    // ---- ticker: position, prefetch, live health ----------------------------------------------------------------------

    private fun updateTicker() {
        val want = q.current != null && (statusValue == Status.Playing || statusValue == Status.Buffering)
        if (want && tickerJob?.isActive != true) {
            lastTickMs = nowMs()
            tickerJob = scope.launch {
                var n = 0
                while (isActive) {
                    delay(250)
                    tick(++n % 4 == 0)
                }
            }
        } else if (!want && tickerJob != null) {
            tickerJob?.cancel()
            tickerJob = null
        }
    }

    private fun tick(everySecond: Boolean) {
        val now = nowMs()
        val dt = (now - lastTickMs).coerceIn(0, 2000)
        lastTickMs = now
        if (live && statusValue == Status.Playing) {
            liveListenedMs += dt
            liveHealthyMs += dt
            liveEverPlayed = true
            if (liveHealthyMs >= LIVE_HEALTHY_MS) liveFailures = 0 // 10 s of audio resets the count
        }
        updatePosition()
        if (everySecond) {
            q.current?.let { emitProgress(it) }
            maybePrefetch()
        }
    }

    // ---- queue management ----------------------------------------------------------------------------------------------

    /**
     * Starts [tracks]. [startIndex] = the row the user picked: it plays even when shouldSkip says no (kara liste). -1 =
     * no row picked (a header / card play button): a random track the player may pick by itself when shuffle is on,
     * else the first one; if there is none, nothing starts and an [PlaybackError.AllBlocked] notice says so. A caller
     * that always wants the first such track, even with shuffle on, passes [firstPlayable].
     */
    fun playContext(tracks: List<Track>, startIndex: Int, ctx: PlayContext) {
        when (val start = q.selectStart(tracks, startIndex)) {
            PlayQueue.Start.Empty -> return
            PlayQueue.Start.AllBlocked -> {
                error(PlaybackError.AllBlocked)
                return
            }
            is PlayQueue.Start.Ok -> {
                q.items.clear()
                q.items.addAll(start.tracks)
                context = ctx
                queueGen++
                failStreak = 0
                dropPrepared()
                q.rebuildOrder(start.itemIndex)
                loadCurrent(0, true)
            }
        }
    }

    /**
     * Appends tracks (unplayable ones dropped) at the end of the play order, after a shuffled remainder too. When
     * playback had stopped at the end of the queue it continues with the first appended track. Returns #appended.
     */
    fun extend(tracks: List<Track>): Int {
        val added = q.extend(tracks)
        if (added == 0) return 0
        // The queue had already run out: go on with the first new track.
        if (endedAtEnd && statusValue == Status.Idle) {
            val next = q.playableFrom(q.pos + 1, 1)
            if (next >= 0) {
                q.pos = next
                loadCurrent(0, true)
                return added
            }
        }
        notifyChanged()
        return added
    }

    /** The skip rule changed (kara liste edit): drops a prepared next track that is now skipped and notifies. */
    fun skipRulesChanged() {
        // Blocking the prepared track, or unblocking one before it, changes what plays next: prepare again (the 1 s
        // prefetch tick does). A prefetch in flight is dropped too.
        val p = prepared
        if (p == null || p.orderIndex != q.upcomingSlot()) dropPrepared()
        notifyChanged()
    }

    /** Smart shuffle / "Geliştir": removes the upcoming recommended items for good (see [PlayQueue.dropRecommendations]). */
    fun dropRecommendations(): Int {
        val dropped = q.dropRecommendations()
        if (dropped == 0) return 0
        dropPrepared()
        notifyChanged()
        return dropped
    }

    /** A recommendation the user added to the collection: its queue items become ordinary ones. */
    fun clearRecommended(trackId: String) {
        if (q.clearRecommended(trackId)) notifyChanged()
    }

    /** Keeps the current track playing and replaces the rest of the queue with [tracks] (a radio of the song). */
    fun replaceUpcoming(tracks: List<Track>, ctx: PlayContext) {
        if (q.current == null) {
            playContext(tracks, 0, ctx)
            return
        }
        q.replaceUpcoming(tracks) // the current track (item 0) stays at the play slot; shuffle mixes the rest
        context = ctx
        queueGen++
        failStreak = 0
        dropPrepared()
        notifyChanged()
        checkQueueLow()
    }

    /** Inserts [track] right after the current item. */
    fun playNext(track: Track) {
        if (q.isEmpty) {
            playContext(listOf(track), 0, PlayContext.QUEUE)
            return
        }
        q.insertNext(track)
        dropPrepared()
        notifyChanged()
    }

    fun enqueue(tracks: List<Track>) {
        if (q.isEmpty) {
            playContext(tracks, 0, PlayContext.QUEUE)
            return
        }
        q.enqueue(tracks)
        notifyChanged()
    }

    /**
     * Inserts tracks (unplayable ones dropped) into the play order at [orderIndex], clamped between the slot after the
     * current one and the end (drag and drop onto the queue). An empty queue starts playing them; a queue that had run
     * out continues with the first of them. Returns #inserted.
     */
    fun insertAt(orderIndex: Int, tracks: List<Track>): Int {
        if (q.isEmpty) {
            val n = tracks.count { q.usable(it) }
            if (n > 0) playContext(tracks, 0, PlayContext.QUEUE)
            return n
        }
        val (at, added) = q.insertAt(orderIndex, tracks)
        if (added == 0) return 0
        // What plays next may have changed: prepare again (the prefetch tick does).
        prepared?.let { if (it.orderIndex >= at) dropPrepared() }
        // The queue had already run out: go on with the first new track (as extend()).
        if (endedAtEnd && statusValue == Status.Idle) {
            val next = q.playableFrom(q.pos + 1, 1)
            if (next >= 0) {
                q.pos = next
                loadCurrent(0, true)
                return added
            }
        }
        notifyChanged()
        return added
    }

    /** Index in the play order (the queue list); plays even a skipped item. */
    fun jumpTo(orderIndex: Int) {
        if (orderIndex < 0 || orderIndex >= q.order.size) return
        failStreak = 0
        q.pos = orderIndex
        loadCurrent(0, true)
    }

    fun removeAt(orderIndex: Int) {
        if (!q.removeAt(orderIndex)) return
        // The prepared slot can lie beyond skipped rows: keep it pointing at the same item (a prefetch in flight
        // re-checks its slot when it lands).
        prepared?.let {
            if (it.orderIndex == orderIndex) dropPrepared() else if (orderIndex < it.orderIndex) it.orderIndex--
        }
        notifyChanged()
    }

    fun move(from: Int, to: Int) {
        if (!q.move(from, to)) return
        dropPrepared()
        notifyChanged()
    }

    /** Keeps the current track. */
    fun clearQueue() {
        if (!q.clearKeepCurrent()) return
        dropPrepared()
        notifyChanged()
    }

    fun setShuffle(on: Boolean) {
        if (q.shuffle == on) return
        q.setShuffle(on)
        updateSettings { it.copy(shuffle = on) }
        if (!q.isEmpty) dropPrepared()
        notifyChanged()
    }

    fun setRepeat(mode: RepeatMode) {
        q.repeat = mode
        updateSettings { it.copy(repeat = mode) }
        dropPrepared()
        notifyChanged()
    }

    fun cycleRepeat() = setRepeat(
        when (q.repeat) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        },
    )

    fun setVolume(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        engine.setVolume(vol)
        _volume.value = vol
        updateSettings { it.copy(volume = vol) }
    }

    /**
     * Pushes Ayarlar › SES to the engine (equalizer). Crossfade and loudness normalisation are read from Settings for
     * every track as it is loaded / preloaded.
     */
    fun applyAudioSettings() {
        val s = settings()
        engine.setEqualizer(EqSettings.of(s.eqEnabled, s.eqPreampDb, s.eqGains))
    }

    private fun speedFor(t: Track): Float {
        val s = settings()
        return if (isPodcastId(t.id)) s.podcastSpeed else s.musicSpeed
    }

    /** Speed of the current kind of item (songs and podcast episodes each remember their own); live always 1. */
    fun speed(): Float {
        val t = q.current
        return if (t == null || live) 1f else speedFor(t)
    }

    /** Playback speed (0.5 .. 3, pitch kept) of the current kind of item. Applies at once; live radio stays at 1. */
    fun setSpeed(speedIn: Float) {
        val t = q.current ?: return
        if (live) return
        val sp = speedIn.coerceIn(0.5f, 3f)
        if (isPodcastId(t.id)) updateSettings { it.copy(podcastSpeed = sp) } else updateSettings { it.copy(musicSpeed = sp) }
        engine.setSpeed(sp)
        // A prepared next item carries the old speed (and a crossfade that only works at 1x): prepare it again.
        if (prepared != null) dropPrepared()
        notifyChanged()
    }

    // ---- transport -----------------------------------------------------------------------------------------------------

    fun togglePause() {
        if (statusValue == Status.Playing || statusValue == Status.Buffering) pause() else play()
    }

    fun play() {
        if (q.current == null) return
        if (statusValue == Status.Resolving) { // the load in flight starts playing when it lands
            resolveAutoplay = true
            serviceLink?.ensureStarted()
            return
        }
        // Nothing loaded in the engine (restored session, error, end of queue, a released station): resolve and start.
        if (statusValue == Status.Error || statusValue == Status.Idle || currentTag == 0L || engine.currentTag != currentTag || liveReleased) {
            loadCurrent(maxOf(0L, if (live) 0 else positionMs()), true)
            return
        }
        serviceLink?.ensureStarted()
        liveReleaseJob?.cancel()
        engine.play()
    }

    fun pause() {
        if (statusValue == Status.Resolving) {
            // Nothing to pause yet: the load in flight starts paused.
            resolveAutoplay = false
        }
        engine.pause()
        if (live) {
            // A station paused for long releases its connection; play resumes live.
            liveReleaseJob?.cancel()
            val tag = currentTag
            liveReleaseJob = scope.launch {
                delay(LIVE_RELEASE_AFTER_PAUSE_MS)
                if (live && currentTag == tag && statusValue == Status.Paused) {
                    Log.i("player", "station paused for a minute: connection released")
                    engine.stop()
                    liveReleased = true
                }
            }
        }
    }

    private var resolveAutoplay = true

    fun seek(msIn: Long) {
        if (q.current == null || live) return
        val ms = msIn.coerceIn(0, maxOf(0L, durationMs() - 250))
        if (statusValue == Status.Resolving) {
            pendingSeekMs = ms
            return
        }
        engine.seek(ms)
        pendingSeekMs = ms // shown until the engine reports the new position
        post { pendingSeekMs = -1 }
        notifyChanged()
    }

    fun next() {
        failStreak = 0
        advance(true)
    }

    fun previous() {
        if (q.current == null) return
        failStreak = 0
        val prev = q.previousSlot()
        if (live && prev < 0) return // a station has no start to go back to
        if (!live && (positionMs() > RESTART_THRESHOLD_MS || prev < 0)) {
            seek(0)
            if (statusValue == Status.Paused) play()
            return
        }
        q.pos = prev
        loadCurrent(0, true)
    }

    private fun advance(userInitiated: Boolean) {
        when (q.advance(userInitiated)) {
            null -> return
            PlayQueue.Advance.Replay, PlayQueue.Advance.Moved -> loadCurrent(0, true)
            PlayQueue.Advance.Stop -> stopAtEnd()
        }
    }

    private fun stopAtEnd() {
        setStatus(Status.Idle)
        engine.stop()
        endedAtEnd = true
        startedTrack?.let {
            _events.tryEmit(PlayerEvent.TrackEnded(it, _position.value.positionMs, _position.value.durationMs, EndReason.QueueEnded))
        }
        startedTrack = null
        notifyChanged()
        checkQueueLow()
    }

    /** After a terminal failure: [advanceAfterError] in 1.5 s, if the failed load is still the current one. */
    private fun scheduleErrorAdvance() {
        errorTag = currentTag
        val tag = errorTag
        scope.launch {
            delay(ERROR_ADVANCE_MS)
            // Only if the failed load is still the current one (the user may have picked another track meanwhile).
            if (statusValue == Status.Error && currentTag == tag) advanceAfterError()
        }
    }

    /**
     * A track failed for good (no YouTube match, unreadable file, stream error): go on with the next track the player
     * may pick. Never back onto the failed slot, and a failure streak as long as the playable queue stops instead of
     * looping.
     */
    private fun advanceAfterError() {
        if (q.order.isEmpty()) return
        failStreak++
        when (q.advanceAfterError(failStreak)) {
            null -> return
            PlayQueue.ErrorAdvance.QueueEnded -> { // the queue ran out (endless playback may still extend it)
                failStreak = 0
                stopAtEnd()
            }
            PlayQueue.ErrorAdvance.GiveUp -> {
                Log.w("player", "$failStreak failures in a row: stopping")
                failStreak = 0
                setStatus(Status.Idle)
                engine.stop()
                error(PlaybackError.NothingPlayable)
                notifyChanged()
            }
            PlayQueue.ErrorAdvance.Moved -> loadCurrent(0, true)
        }
    }

    /** "Yanlış eşleşme?": the user picked another YouTube video: pin it and continue at the same position. */
    fun useMatch(m: Match) {
        val t = q.current ?: return
        if (live || direct) return
        val at = positionMs()
        val autoplay = statusValue != Status.Paused
        val tag = currentTag
        scope.launch {
            resolver.pin(t.id, m)
            if (currentTag == tag && q.current?.id == t.id) loadCurrent(at, autoplay)
        }
    }

    // ---- loading ---------------------------------------------------------------------------------------------------------

    private fun youtubeSource(r: Resolved, tag: Long, track: Track): EngineSource = EngineSource(
        tag = tag,
        kind = EngineSource.Kind.Youtube,
        url = r.stream.url,
        mimeType = r.stream.mimeType,
        contentLength = r.stream.contentLength,
        requestHeaders = r.stream.requestHeaders,
        googleVideo = r.stream.isGoogleVideo,
        durationMsHint = track.durationMs.toLong(),
        gainDb = Loudness.youtubeGainDb(settings(), r.stream.loudnessDb),
        speed = speedFor(track),
        track = track,
    )

    private fun localSourceOf(local: LocalSource, tag: Long, track: Track): EngineSource {
        val base = Loudness.localBase(settings())
        return EngineSource(
            tag = tag,
            kind = EngineSource.Kind.Local,
            url = local.uri,
            mimeType = local.mimeType,
            durationMsHint = track.durationMs.toLong(),
            gainDb = base?.first ?: 0f,
            replayGain = base != null,
            maxBoostDb = base?.second ?: 0f,
            speed = speedFor(track),
            track = track,
        )
    }

    private fun localSourceFor(trackId: String): LocalSource? {
        for (r in localSources) {
            val s = runCatching { r.localSourceFor(trackId) }.getOrNull()
            if (s != null) return s
        }
        return null
    }

    private fun crossfadeInto(orderIndex: Int): Int {
        val a = q.current
        if (orderIndex < 0 || orderIndex >= q.order.size) return 0
        val b = q.trackAt(orderIndex)
        return Crossfade.lengthMs(settings().crossfadeSec, a, b, orderIndex == q.pos, ::speedFor)
    }

    private fun clearLiveTitle() {
        if (liveTitleValue.isEmpty()) return
        liveTitleValue = ""
        _liveTitle.value = ""
    }

    private fun loadCurrent(startMsIn: Long, autoplay: Boolean) {
        val t = q.current ?: return
        var startMs = startMsIn
        retries = 0
        endedAtEnd = false
        preloadFailed = ""
        resolveAutoplay = autoplay
        liveReconnectJob?.cancel()
        liveReleaseJob?.cancel()
        liveReleased = false
        clearLiveTitle()
        if (autoplay) serviceLink?.ensureStarted()
        // Gapless handoff already prepared for exactly this slot?
        val p = prepared
        if (p != null && p.orderIndex == q.pos && startMs == 0L) {
            currentTag = p.tag
            currentLocal = p.resolved == null
            live = false
            direct = false
            match = p.resolved?.match
            stream = p.resolved?.stream
            engine.open(p.source, 0, autoplay)
            prepared = null
            setStatus(Status.Buffering)
            trackChanged(t)
            notifyChanged()
            checkQueueLow()
            return
        }
        dropPrepared()
        // A podcast episode continues where it was left (asked only for a start from the beginning).
        if (startMs == 0L) startPositions?.let { startMs = maxOf(0L, it.startPositionFor(t)) }
        val isLiveItem = liveStreams?.isLive(t) == true
        if (isLiveItem) {
            liveAttempt = 0
            liveFailures = 0
            liveEverPlayed = false
            liveHealthyMs = 0
            liveListenedMs = 0
        }
        startResolve(q.pos, startMs, autoplay, false)
        trackChanged(t)
        notifyChanged()
        checkQueueLow()
    }

    private fun startResolve(orderIndex: Int, startMs: Long, autoplay: Boolean, bypassStreamCache: Boolean) {
        resolveJob?.cancel()
        val tag = ++tagCounter
        currentTag = tag
        setStatus(Status.Resolving)
        match = null
        pendingSeekMs = if (startMs > 0) startMs else -1
        resolveAutoplay = autoplay
        engine.stop() // release the previous track's buffers right away

        val track = q.trackAt(orderIndex)

        // Internet radio: an endless stream straight from the station (no match, no duration; a start position only
        // matters to tracks, so a restored station resumes live).
        val liveProvider = liveStreams?.takeIf { it.isLive(track) }
        live = liveProvider != null
        direct = false
        if (liveProvider != null) {
            currentLocal = false
            stream = null
            pendingSeekMs = -1
            setStatus(Status.Buffering)
            openLive(liveProvider, track, tag)
            notifyChanged()
            return
        }

        // Offline: a downloaded track plays straight from its file — no YouTube match / stream.
        val local = localSourceFor(track.id)
        currentLocal = local != null
        if (local != null) {
            match = null
            stream = null
            setStatus(Status.Buffering)
            engine.open(localSourceOf(local, tag, track), startMs, resolveAutoplay)
            notifyChanged()
            return
        }

        // Podcast episode: its own media URL (redirects resolved first), no YouTube match.
        val directProvider = directStreams?.takeIf { it.handles(track) }
        if (directProvider != null) {
            direct = true
            match = null
            stream = null
            resolveJob = scope.launch {
                val r = try {
                    Result.success(directProvider.resolve(track))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                if (tag != currentTag) return@launch // superseded
                r.onSuccess { d ->
                    setStatus(Status.Buffering)
                    engine.open(
                        EngineSource(
                            tag = tag, kind = EngineSource.Kind.Direct, url = d.url, mimeType = d.mimeType,
                            contentLength = d.contentLength, allowLocalNetwork = d.allowLocalNetwork,
                            durationMsHint = track.durationMs.toLong(), speed = speedFor(track), track = track,
                        ),
                        startMs, resolveAutoplay,
                    )
                    notifyChanged()
                }.onFailure { e ->
                    lastErrorText = e.message ?: e.javaClass.simpleName
                    setStatus(Status.Error)
                    pendingSeekMs = -1
                    Log.w("player", "episode \"${track.name}\" unavailable: $lastErrorText")
                    error(PlaybackError.EpisodeFailed, track)
                    notifyChanged()
                    scheduleErrorAdvance()
                }
            }
            notifyChanged()
            return
        }

        val quality = qualityProvider.quality()
        val webm = allowWebm && quality == AudioQuality.High
        val low = quality == AudioQuality.Normal
        resolveJob = scope.launch {
            val r: Result<Resolved> = if (bypassStreamCache) {
                val cached = resolver.cachedMatch(track.id)
                if (cached != null) resolver.stream(cached.videoId, webm, low, true).map { Resolved(cached, it) }
                else resolver.resolve(track, webm, low)
            } else {
                resolver.resolve(track, webm, low)
            }
            if (tag != currentTag) return@launch // superseded
            r.onFailure { e ->
                if (e is CancellationException) return@launch
                lastErrorText = e.message ?: e.javaClass.simpleName
                setStatus(Status.Error)
                pendingSeekMs = -1
                Log.w("player", "resolve failed for \"${track.name}\": $lastErrorText")
                error(PlaybackError.NoMatch, track, resolver.failureOf(e))
                notifyChanged()
                scheduleErrorAdvance() // skip ahead after a short pause so the user sees what happened
                return@launch
            }
            val res = r.getOrThrow()
            match = res.match
            stream = res.stream
            setStatus(Status.Buffering)
            engine.open(youtubeSource(res, tag, track), startMs, resolveAutoplay)
            notifyChanged()
        }
    }

    /** Resolves the station (entry [liveAttempt] of its playlist) and opens it. */
    private fun openLive(provider: LiveStreamProvider, track: Track, tag: Long) {
        val attempt = liveAttempt
        resolveJob = scope.launch {
            val r = try {
                Result.success(provider.resolve(track, attempt))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (tag != currentTag) return@launch
            r.onSuccess { l ->
                liveHealthyMs = 0
                engine.open(
                    EngineSource(
                        tag = tag, kind = EngineSource.Kind.Live, url = l.url, mimeType = l.mimeType, isHls = l.isHls,
                        allowLocalNetwork = l.allowLocalNetwork, track = track,
                    ),
                    0, resolveAutoplay,
                )
            }.onFailure { e ->
                Log.w("player", "station \"${track.name}\" unavailable: ${e.message}")
                liveFailed(EngineErrorKind.Network, e.message ?: "unreachable")
            }
        }
    }

    private fun maybePrefetch() {
        // Nothing is preloaded for or after a live item: a station never ends, and opening one early would stream it.
        // A crossfade needs the next track ready, so it implies preloading.
        val s = settings()
        val preload = s.preloadNext || s.crossfadeSec > 0
        if (!preload || statusValue != Status.Playing || prepared != null || prefetchTag != 0L || live) return
        val dur = durationMs()
        val posMs = positionMs()
        if (dur <= 0 || dur - posMs > PREFETCH_WINDOW_MS) return
        val nextPos = q.upcomingSlot() // blocked items are passed over, like advance()
        if (nextPos < 0) return
        val track = q.trackAt(nextPos)
        if (liveStreams?.isLive(track) == true) return
        if (directStreams?.handles(track) == true) return // an episode opens when it starts
        if (track.id == preloadFailed) return
        // Downloads and local files: nothing to resolve, the engine prepares them straight from disk.
        val local = localSourceFor(track.id)
        if (local != null) {
            val tag = ++tagCounter
            val src = localSourceOf(local, tag, track).copy(crossfadeMs = crossfadeInto(nextPos))
            prepared = Prepared(tag, nextPos, track.id, null, src)
            engine.preload(src)
            return
        }
        val tag = ++tagCounter
        prefetchTag = tag
        prefetchJob?.cancel()
        val quality = qualityProvider.quality()
        val webm = allowWebm && quality == AudioQuality.High
        val low = quality == AudioQuality.Normal
        // Tied to the track it was issued for: any track change or slot move meanwhile rejects the result.
        val forTag = currentTag
        val forPos = q.pos
        prefetchJob = scope.launch {
            val r = try {
                withContext(lowDispatcher) { resolver.resolve(track, webm, low) }
            } catch (e: CancellationException) {
                throw e
            }
            if (prefetchTag != tag) return@launch
            prefetchTag = 0
            prefetchJob = null
            val res = r.getOrNull() ?: return@launch // resolved again when it becomes current
            if (currentTag != forTag || q.pos != forPos) return@launch
            // The queue may have changed meanwhile: keep it only if the same track still plays next.
            if (q.upcomingSlot() != nextPos || q.trackAt(nextPos).id != track.id) return@launch
            val src = youtubeSource(res, tag, track).copy(crossfadeMs = crossfadeInto(nextPos))
            prepared = Prepared(tag, nextPos, track.id, res, src)
            engine.preload(src)
            Log.d("player", "preloaded next: ${track.name}")
        }
    }

    /** Forget the prepared next track + any prefetch in flight. */
    private fun dropPrepared() {
        prepared = null
        prefetchTag = 0
        prefetchJob?.cancel()
        prefetchJob = null
        engine.clearPreload()
    }

    /** Asked after a track starts and when the queue runs out; posted so the handler never runs inside a load. */
    private fun checkQueueLow() {
        if (onQueueLow == null || q.repeat != RepeatMode.Off || live) return
        post {
            val h = onQueueLow ?: return@post
            if (q.repeat != RepeatMode.Off || q.current == null) return@post
            if (q.remainingPlayable(2) <= 1) h()
        }
    }

    // ---- engine events -------------------------------------------------------------------------------------------------

    override fun onEngineState(state: EngineState, tag: Long) {
        if (tag != currentTag) return
        when (state) {
            EngineState.Loading -> setStatus(Status.Buffering)
            EngineState.Playing -> {
                setStatus(Status.Playing)
                // Catalog items without a duration (manual / restored) learn it from the decoder.
                q.learnDuration(engine.durationMs())
                pendingSeekMs = -1
                retries = 0
                failStreak = 0
                liveReleaseJob?.cancel()
            }
            EngineState.Paused -> setStatus(Status.Paused)
            EngineState.Error -> setStatus(Status.Error)
            EngineState.Ended, EngineState.Idle -> Unit
        }
        notifyChanged()
    }

    override fun onEngineEnded(finished: Long, next: Long) {
        if (finished != currentTag) return
        val p = prepared
        if (next != 0L && p != null && p.tag == next) {
            // Gapless / crossfade: the engine is already playing the prepared track. Its slot is re-checked.
            var slot = p.orderIndex
            val n = q.order.size
            if (slot < 0 || slot >= n || q.trackAt(slot).id != p.trackId) {
                slot = -1
                var i = maxOf(q.pos + 1, 0)
                while (i < n && slot < 0) {
                    if (q.trackAt(i).id == p.trackId) slot = i
                    i++
                }
            }
            if (slot < 0) { // the prepared item left the queue: load what really comes next
                prepared = null
                advance(false)
                return
            }
            q.pos = slot
            currentTag = next
            currentLocal = p.resolved == null
            match = p.resolved?.match
            stream = p.resolved?.stream
            prepared = null
            setStatus(Status.Playing)
            live = false
            direct = false
            retries = 0
            preloadFailed = ""
            clearLiveTitle()
            q.current?.let { trackChanged(it) }
            notifyChanged()
            checkQueueLow()
            return
        }
        advance(false)
    }

    override fun onEngineError(kind: EngineErrorKind, message: String, tag: Long) {
        val p = prepared
        if (p != null && tag == p.tag) { // preload failed: resolve / open normally when it becomes current
            preloadFailed = p.trackId
            prepared = null
            return
        }
        if (tag != currentTag) return
        Log.w("player", "engine error ($kind): $message")
        if (live) {
            liveError(kind, message)
            return
        }
        if (currentLocal) {
            // A local file: no YouTube stream to refresh or fall back from (and a file the device can't decode must not
            // turn off Opus for the YouTube streams of this session).
            lastErrorText = message
            setStatus(Status.Error)
            error(if (kind == EngineErrorKind.UnsupportedFormat) PlaybackError.FileUnsupported else PlaybackError.FileUnreadable, q.current)
            notifyChanged()
            scheduleErrorAdvance()
            return
        }
        val at = engine.positionMs()
        if (direct) {
            // A podcast episode: a dropped / expired media URL is resolved again (it may have moved) and continues where
            // it was; anything else, or a third failure, moves on.
            if (kind == EngineErrorKind.Network && retries < 2) {
                val keep = retries + 1
                startResolve(q.pos, at, true, true)
                retries = keep
                return
            }
            lastErrorText = message
            setStatus(Status.Error)
            error(if (kind == EngineErrorKind.UnsupportedFormat) PlaybackError.EpisodeFormatUnsupported else PlaybackError.EpisodeFailed, q.current)
            notifyChanged()
            scheduleErrorAdvance()
            return
        }
        if (kind == EngineErrorKind.UnsupportedFormat && allowWebm && stream?.mimeType?.contains("webm") == true) {
            allowWebm = false // fall back to AAC for the rest of the session
            Log.w("player", "opus / webm failed to play: AAC from now on")
            startResolve(q.pos, at, true, true)
            return
        }
        if (kind == EngineErrorKind.Network && retries < 2) {
            // Most likely an expired / throttled URL: fetch a fresh manifest and continue where we were.
            val keep = retries + 1
            startResolve(q.pos, at, true, true)
            retries = keep
            return
        }
        lastErrorText = message
        setStatus(Status.Error)
        error(PlaybackError.PlaybackFailed, q.current)
        notifyChanged()
        scheduleErrorAdvance()
    }

    /**
     * A station's stream failed: reconnect with backoff (0.5 / 1 / 2 / 4 / 8 s) and the playlist's next entry; it fails
     * for good after 6 consecutive failures (3 if nothing ever played); 10 s of audio resets the count; a failure while
     * paused doesn't count (the connection is simply released and play reconnects). A format the device can't play
     * fails at once.
     */
    private fun liveError(kind: EngineErrorKind, message: String) {
        if (kind == EngineErrorKind.UnsupportedFormat) {
            liveFailed(kind, message)
            return
        }
        if (statusValue == Status.Paused || !resolveAutoplay && statusValue != Status.Playing) {
            engine.stop()
            liveReleased = true
            return
        }
        liveFailures++
        val limit = if (liveEverPlayed) LIVE_MAX_FAILURES else LIVE_MAX_FAILURES_NEVER_PLAYED
        if (liveFailures >= limit) {
            liveFailed(EngineErrorKind.Network, message)
            return
        }
        val wait = LIVE_BACKOFF_MS[minOf(liveFailures - 1, LIVE_BACKOFF_MS.size - 1)]
        Log.i("player", "station dropped ($message): reconnect $liveFailures/$limit in $wait ms")
        setStatus(Status.Buffering)
        notifyChanged()
        val provider = liveStreams ?: return
        val track = q.current ?: return
        val tag = currentTag
        liveReconnectJob?.cancel()
        liveReconnectJob = scope.launch {
            delay(wait)
            if (currentTag != tag || !live) return@launch
            liveAttempt++
            openLive(provider, track, tag)
        }
    }

    /** The engine already reconnected as often as makes sense (or the format can't play): no retry loop here. */
    private fun liveFailed(kind: EngineErrorKind, message: String) {
        lastErrorText = message
        setStatus(Status.Error)
        q.current?.let { station -> onLiveFailed?.invoke(station, kind) }
        error(if (kind == EngineErrorKind.UnsupportedFormat) PlaybackError.StationUnsupported else PlaybackError.StationUnreachable, q.current)
        notifyChanged()
        scheduleErrorAdvance()
    }

    override fun onEngineTitle(title: String?, raw: ByteArray?, tag: Long) {
        if (tag != currentTag || !live) return
        val text = (raw?.let { liveStreams?.titleFromIcy(it) } ?: title).orEmpty().trim()
        if (text == liveTitleValue) return
        liveTitleValue = text
        _liveTitle.value = text
        notifyChanged()
    }

    // ---- session persistence -------------------------------------------------------------------------------------------

    /** Writes session.json (off the main thread). */
    fun saveSession() {
        val f = sessionFile ?: return
        val json = SessionFile.encode(q.order.map { q.items[it] }, q.pos, if (live) 0 else positionMs(), context) // a station resumes live
        sessionWriter { Files.writeJson(f(), json) }
    }

    /** Resume support (last session). Set [liveStreams] first, so a restored station shows as live. */
    fun restoreSession() {
        val f = sessionFile ?: return
        val r = try {
            SessionFile.decode(Files.readJson(f()))
        } catch (e: Exception) { // a hand-edited / foreign session.json: start empty
            Log.w("player", "session.json ignored: ${e.message}")
            null
        } ?: return
        restore(r)
    }

    /** Installs a restored queue (paused at its position; pressing play resolves and resumes). */
    fun restore(r: SessionFile.Restored) {
        q.items.clear()
        q.items.addAll(r.items)
        q.order.clear()
        for (i in r.items.indices) q.order.add(i)
        q.pos = r.pos
        context = r.context
        pendingSeekMs = r.positionMs
        live = liveStreams?.isLive(q.items[q.pos]) == true // a station: shown live, resumes live
        if (live) pendingSeekMs = -1
        setStatus(Status.Paused) // shown in the player bar; pressing play resolves and resumes
        notifyChanged()
    }

    /** Stops playback and forgets the queue (the notification goes away). */
    fun stopAndClear() {
        resolveJob?.cancel()
        dropPrepared()
        engine.stop()
        startedTrack?.let { _events.tryEmit(PlayerEvent.TrackEnded(it, _position.value.positionMs, _position.value.durationMs, EndReason.Stopped)) }
        startedTrack = null
        q.items.clear()
        q.order.clear()
        q.pos = -1
        currentTag = 0
        live = false
        direct = false
        match = null
        stream = null
        context = PlayContext()
        queueGen++
        setStatus(Status.Idle)
        notifyChanged()
    }
}
