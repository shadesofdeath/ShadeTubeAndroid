package com.app.shadetube.playback.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.app.shadetube.core.Log

/**
 * Audio focus and "becoming noisy" (headphones unplugged / Bluetooth gone) for the engine.
 *
 * Why not ExoPlayer's built-ins: a crossfade runs two ExoPlayer instances at once, and two players that each manage
 * focus take it from each other (the incoming one's request makes the outgoing one lose focus and pause). So the
 * engine holds one focus for both, with the same policy ExoPlayer uses for media: a permanent loss pauses, a
 * transient loss pauses and resumes on regain, "may duck" is left to the system's automatic ducking.
 */
class AudioFocus(
    context: Context,
    /** Pause (and remember whether to resume on regain). */
    private val onPause: (resumeOnGain: Boolean) -> Unit,
    private val onResume: () -> Unit,
    private val onNoisy: () -> Unit,
) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var held = false
    private var resumeOnGain = false
    private var noisyRegistered = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        handler.post {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (resumeOnGain) {
                        resumeOnGain = false
                        onResume()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.i("player", "audio focus lost")
                    held = false
                    resumeOnGain = false
                    onPause(false)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnGain = true
                    onPause(true)
                }
                // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system ducks media playback by itself (API 26+).
            }
        }
    }

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(listener, handler)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
        }
    }

    /** Before playback starts. False when the system refused (a call is going on): the caller stays paused. */
    fun acquire(): Boolean {
        resumeOnGain = false
        registerNoisy()
        if (held) return true
        val r = audioManager.requestAudioFocus(request)
        held = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (r == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            resumeOnGain = true
            return false
        }
        return held
    }

    /** The user paused / stopped: give focus back (a transient loss keeps its resume pending). */
    fun release(keepResume: Boolean = false) {
        if (!keepResume) resumeOnGain = false
        unregisterNoisy()
        if (!held || keepResume) return
        audioManager.abandonAudioFocusRequest(request)
        held = false
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { app.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }
}
