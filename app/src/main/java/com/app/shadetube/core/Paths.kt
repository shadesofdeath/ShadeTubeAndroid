package com.app.shadetube.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where user data lives (port of core/Paths). The desktop app's %LOCALAPPDATA%\ShadeTube maps to the app's private
 * files dir; file names inside it are kept identical to the C++ app (settings.json, library.json, session.json, ...).
 *
 *  - [appData]   persistent private data (settings, library, caches that must survive: matches.json, hashes)
 *  - [cacheDir]  persistent-ish caches the app manages and prunes itself (images, lyrics, mb, podcasts)
 *  - [tempDir]   system cache dir: the OS may delete it at any time (stream buffers, scratch files)
 *  - [downloadsDir] default download root (app-specific external Music dir, no permission needed)
 */
object Paths {
    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
        Log.attach(logDir)
    }

    val isInitialized: Boolean get() = ::ctx.isInitialized

    val appData: File get() = ctx.filesDir.ensure()
    val logDir: File get() = File(appData, "logs").ensure()
    val cacheDir: File get() = File(appData, "cache").ensure()
    val imageCacheDir: File get() = File(cacheDir, "images").ensure()
    val tempDir: File get() = ctx.cacheDir.ensure()
    val settingsFile: File get() = File(appData, "settings.json")

    /** Default download root: <external app files>/Music/ShadeTube, or a private folder when no external storage. */
    val defaultDownloadsDir: File
        get() = (ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { File(it, "ShadeTube") }
            ?: File(appData, "Music/ShadeTube")).ensure()

    /** The download root in effect ([Settings.downloadsDir] when set and usable). */
    val downloadsDir: File
        get() {
            val custom = SettingsStore.current.downloadsDir
            if (custom.isNotEmpty()) {
                val f = File(custom)
                if (f.ensure().isDirectory && f.canWrite()) return f
            }
            return defaultDownloadsDir
        }

    fun file(name: String): File = File(appData, name)
    fun cache(name: String): File = File(cacheDir, name)

    private fun File.ensure(): File = also { if (!it.exists()) it.mkdirs() }
}
