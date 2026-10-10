package com.app.shadetube.core

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.io.File
import java.util.Locale

/**
 * [Settings.language] below Android 13 (API 28–32), where there is no per-app locale (LocaleManager): the application
 * and the activity wrap their base context with a configuration override ([wrap]); a change at runtime updates the
 * application resources ([applyToApp]) and the activity is recreated. On 33+ everything here is a no-op and the
 * system's per-app locale (set by Settings › Görünüm) applies.
 */
object AppLocale {
    val needsOverride: Boolean get() = Build.VERSION.SDK_INT < 33

    /** settings.json's "language" read straight from disk (Application.attachBaseContext runs before [SettingsStore.load]). */
    fun storedLanguage(base: Context): String = runCatching {
        val j = parseJsonOrNull(File(base.filesDir, "settings.json").readText())
        Settings.fromJson(j).language
    }.getOrDefault("")

    /** [base] with [code] ("" = system language) as its locale; [base] itself on 33+ or for the system language. */
    fun wrap(base: Context, code: String): Context {
        if (!needsOverride || code.isEmpty()) return base
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** Applies [code] to the application's resources (after a change in Settings, or a system configuration change). */
    @Suppress("DEPRECATION")
    fun applyToApp(app: Context, code: String) {
        if (!needsOverride) return
        val locales = if (code.isEmpty()) android.content.res.Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(code)
        if (locales.isEmpty) return
        Locale.setDefault(locales[0])
        val res = app.resources
        if (res.configuration.locales == locales) return
        val config = Configuration(res.configuration)
        config.setLocales(locales)
        res.updateConfiguration(config, res.displayMetrics)
    }
}
