package com.app.shadetube.ui.shell

import android.content.Context
import android.content.Intent
import com.app.shadetube.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Requests MainActivity hands to the shell from intents (the media notification's "open the player", the debug
 * screen in debug builds: `adb shell am start -n com.app.shadetube/.MainActivity --ez com.app.shadetube.extra.DEBUG true`).
 */
object ShellRequests {
    /** Same value as `PlaybackService`'s session activity extra. */
    const val EXTRA_OPEN_NOW_PLAYING = "com.app.shadetube.extra.OPEN_NOW_PLAYING"
    const val EXTRA_DEBUG = "com.app.shadetube.extra.DEBUG"

    val openNowPlaying = MutableStateFlow(false)
    val openDebug = MutableStateFlow(false)

    fun handle(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)) openNowPlaying.value = true
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG, false)) openDebug.value = true
    }
}

/** Small private prefs of the shell (first-launch onboarding). */
internal class ShellPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shell", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(v) {
            prefs.edit().putBoolean(KEY_ONBOARDING, v).apply()
        }

    private companion object {
        const val KEY_ONBOARDING = "onboarding_done"
    }
}
