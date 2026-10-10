package com.app.shadetube.playback.features

import com.app.shadetube.playback.Player
import com.app.shadetube.playback.PlayerEvent
import com.app.shadetube.playback.PlayerNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sleep timer (port of the AppContext sleep timer): pause at a deadline ([set] minutes; the menu offers 15 / 30 / 45 /
 * 60 / 90) or when the current track ends ([setAtTrackEnd]: "Parça bitince"; a live station never ends, so the UI
 * offers it only for songs and episodes unless it is already set). At the deadline the volume fades out over
 * [FADE_MS] before the pause (then it is restored for the next play).
 */
class SleepTimer(
    private val player: Player,
    private val scope: CoroutineScope,
    private val steadyMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object {
        /** The desktop app checked every 2 s ("2 s resolution is plenty"). */
        const val CHECK_MS = 2000L
        const val FADE_MS = 5000L
        val MENU_MINUTES = listOf(15, 30, 45, 60, 90)
    }

    data class State(
        /** Steady-clock deadline (0 = off). */
        val deadlineMs: Long = 0,
        val atTrackEnd: Boolean = false,
    ) {
        val active: Boolean get() = deadlineMs > 0 || atTrackEnd
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var started = false

    /** The volume before a running fade-out (restored when the timer is changed meanwhile). */
    private var fadingFrom: Float? = null

    private fun stopJob() {
        job?.cancel()
        job = null
        fadingFrom?.let { player.setVolume(it) }
        fadingFrom = null
    }

    /** Minutes left, rounded up ("23 dk kaldı"); 0 when off / at track end. */
    fun minutesLeft(): Long {
        val d = _state.value.deadlineMs
        if (d <= 0) return 0
        return (maxOf(0L, d - steadyMs()) + 59_999) / 60_000
    }

    /** Call once: follows track changes for "at track end". */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            player.events.collect { e ->
                // "Parça bitince": the previous track just finished -> pause the new one at its start.
                if (e is PlayerEvent.TrackStarted && _state.value.atTrackEnd) {
                    _state.value = State()
                    scope.launch {
                        player.pause()
                        player.emitNotice(PlayerNotice.SleepTimerStopped)
                    }
                }
            }
        }
    }

    /** 0 = off. */
    fun set(minutes: Int) {
        stopJob()
        _state.value = State(deadlineMs = if (minutes > 0) steadyMs() + minutes * 60_000L else 0)
        player.emitNotice(if (minutes > 0) PlayerNotice.SleepTimerSet(minutes) else PlayerNotice.SleepTimerOff)
        if (minutes > 0) job = scope.launch { run() }
    }

    fun setAtTrackEnd() {
        stopJob()
        _state.value = State(atTrackEnd = true)
        player.emitNotice(PlayerNotice.SleepAtTrackEndSet)
    }

    fun cancel() = set(0)

    private suspend fun run() {
        while (true) {
            val d = _state.value.deadlineMs
            if (d <= 0) return
            val left = d - steadyMs()
            if (left <= FADE_MS) break
            delay(minOf(CHECK_MS, left - FADE_MS))
        }
        // Fade out, then pause (only when something plays) and restore the volume for the next play.
        val volume = player.volume.value
        if (player.isPlaying) {
            fadingFrom = volume
            val steps = 25
            for (i in 1..steps) {
                player.setVolume(volume * (1f - i.toFloat() / steps))
                delay(FADE_MS / steps)
            }
            player.pause()
            player.setVolume(volume)
            fadingFrom = null
        }
        _state.value = State()
        player.emitNotice(PlayerNotice.SleepTimerStopped)
    }
}
