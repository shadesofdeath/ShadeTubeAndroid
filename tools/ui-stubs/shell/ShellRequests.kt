package com.app.shadetube.ui.shell

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

// STUB of the shell's ShellRequests (used by MainActivity).
object ShellRequests {
    const val EXTRA_OPEN_NOW_PLAYING = "com.app.shadetube.extra.OPEN_NOW_PLAYING"
    const val EXTRA_DEBUG = "com.app.shadetube.extra.DEBUG"
    val openNowPlaying = MutableStateFlow(false)
    val openDebug = MutableStateFlow(false)
    fun handle(intent: Intent?) {}
}
