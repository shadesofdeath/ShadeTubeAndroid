package com.app.shadetube

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.app.shadetube.core.AppLocale
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.SettingsStore

/**
 * Process entry point (the C++ wWinMain + App composition root). Order matters: paths and logging first, then
 * settings, then the service graph ([AppGraph]).
 */
class ShadeTubeApp : Application() {
    /** Settings.language below Android 13 (no per-app locale there): see [AppLocale]. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base, AppLocale.storedLanguage(base)))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (AppLocale.needsOverride && Paths.isInitialized) {
            AppLocale.applyToApp(this, SettingsStore.current.language)
            AppGraph.applyLabels(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Paths.init(this)
        installCrashLogger()
        SettingsStore.load()
        Log.i("app", "ShadeTube ${BuildConfig.VERSION_NAME} starting")
        AppGraph.init(this)
    }

    /** Port of core/CrashHandler: an uncaught exception is written to the log (crashes are never uploaded). */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                val dir = java.io.File(Paths.appData, "crashes").apply { mkdirs() }
                val name = "ShadeTube-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date()) + ".txt"
                java.io.File(dir, name).writeText("thread: ${thread.name}\n" + t.stackTraceToString())
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
                SettingsStore.flush()
            }
            previous?.uncaughtException(thread, t)
        }
    }
}
