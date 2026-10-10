package com.app.shadetube.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

enum class RepeatMode { Off, All, One }

/** High = best bitrate (opus / aac), Normal = <= 128 kbps AAC (saves mobile data). */
enum class AudioQuality { High, Normal }

/** Accent: from the album art, a fixed color, or Material You (Android 12+ wallpaper colors). */
enum class AccentMode { Dynamic, Fixed, System }

enum class ThemeMode { Dark, Light, System }

/**
 * User settings (port of core/Settings), persisted as settings.json with the same keys as the desktop app.
 * Windows-only options (window placement, tray, autostart, taskbar, keyboard shortcuts, Discord) are dropped;
 * Android-only ones are marked.
 *
 * Immutable: read [SettingsStore.current] / observe [SettingsStore.flow]; change with [SettingsStore.update].
 */
data class Settings(
    // Playback
    val volume: Float = 1f,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val quality: AudioQuality = AudioQuality.High,
    /** Android: quality used on a metered (mobile) connection. */
    val meteredQuality: AudioQuality = AudioQuality.Normal,
    val normalizeVolume: Boolean = true,
    /** LUFS target of the normalisation: -19 quiet, -14 normal, -11 loud. */
    val loudnessTarget: Int = -14,
    val preloadNext: Boolean = true,
    // Equalizer: 10 peaking bands 31 Hz .. 16 kHz in dB, a preamp, and the preset they came from ("custom" once edited)
    val eqEnabled: Boolean = false,
    val eqPreset: String = "flat",
    val eqGains: List<Float> = emptyList(),
    val eqPreampDb: Float = 0f,
    /** 0 = gapless handoff, 1..12 = crossfade between tracks. */
    val crossfadeSec: Int = 0,
    val musicSpeed: Float = 1f,
    val podcastSpeed: Float = 1f,

    // Appearance
    val accentMode: AccentMode = AccentMode.Dynamic,
    val fixedAccent: String = "#DDFF47",
    val theme: ThemeMode = ThemeMode.Dark,
    val reduceMotion: Boolean = false,
    /** Android: Library shows covers in a 2-column grid instead of the list. */
    val libraryGrid: Boolean = false,

    // Downloads
    /** Empty = default ([Paths.defaultDownloadsDir]). */
    val downloadsDir: String = "",
    /** MP3 transcode bitrate (320 / 256 / 192, like the desktop); 0 = keep the original stream (m4a / webm). */
    val downloadMp3Kbps: Int = 320,
    /** Android: downloads (manual and sync) only on Wi-Fi / unmetered networks. */
    val downloadWifiOnly: Boolean = true,
    val syncPaused: Boolean = false,
    val syncOnMetered: Boolean = false,
    val syncRemoveDropped: Boolean = false,
    /** Storage cap for synced downloads in GB; 0 = no limit. */
    val syncCapGb: Int = 0,
    val lyricsEnabled: Boolean = true,
    val lyricsInDownloads: Boolean = true,

    // Integrations
    val scrobbleEnabled: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val endlessPlayback: Boolean = true,
    val smartShuffle: Boolean = false,
    /** "Geliştir": collections ("liked" or a playlist id) whose page shows recommendations in the list. */
    val enhancedCollections: List<String> = emptyList(),
    /** Backup audio source when YouTube fails: "off" | "piped" | "invidious". */
    val altSource: String = "off",
    val altSourceInstance: String = "",
    /** Local music folders (Android: SAF tree URIs or absolute paths). */
    val localFolders: List<String> = emptyList(),
    val expandedFolders: List<String> = emptyList(),
    // Updates (GitHub releases)
    val updateCheck: Boolean = true,
    val lastUpdateCheck: Long = 0,
    val skippedVersion: String = "",
    /** UI language code; "" = the system language. */
    val language: String = "",
    val region: String = "TR",
) {
    fun toJson(): JsonElement = jsonOf(
        "settingsVersion" to CURRENT_VERSION,
        "volume" to volume,
        "shuffle" to shuffle,
        "repeat" to repeat.ordinal,
        "quality" to quality.ordinal,
        "meteredQuality" to meteredQuality.ordinal,
        "normalizeVolume" to normalizeVolume,
        "loudnessTarget" to loudnessTarget,
        "preloadNext" to preloadNext,
        "eqEnabled" to eqEnabled,
        "eqPreset" to eqPreset,
        "eqGains" to eqGains,
        "eqPreampDb" to eqPreampDb,
        "crossfadeSec" to crossfadeSec,
        "musicSpeed" to musicSpeed,
        "podcastSpeed" to podcastSpeed,
        "accentMode" to accentMode.ordinal,
        "fixedAccent" to fixedAccent,
        "theme" to when (theme) { ThemeMode.Light -> "light"; ThemeMode.System -> "system"; else -> "dark" },
        "reduceMotion" to reduceMotion,
        "libraryGrid" to libraryGrid,
        "downloadsDir" to downloadsDir,
        "downloadMp3Kbps" to downloadMp3Kbps,
        "downloadWifiOnly" to downloadWifiOnly,
        "syncPaused" to syncPaused,
        "syncOnMetered" to syncOnMetered,
        "syncRemoveDropped" to syncRemoveDropped,
        "syncCapGb" to syncCapGb,
        "lyricsEnabled" to lyricsEnabled,
        "lyricsInDownloads" to lyricsInDownloads,
        "scrobbleEnabled" to scrobbleEnabled,
        "sponsorBlockEnabled" to sponsorBlockEnabled,
        "endlessPlayback" to endlessPlayback,
        "smartShuffle" to smartShuffle,
        "enhancedCollections" to enhancedCollections,
        "altSource" to altSource,
        "altSourceInstance" to altSourceInstance,
        "localFolders" to localFolders,
        "expandedFolders" to expandedFolders,
        "updateCheck" to updateCheck,
        "lastUpdateCheck" to lastUpdateCheck,
        "skippedVersion" to skippedVersion,
        "language" to language,
        "region" to region,
    )

    companion object {
        /**
         * Schema version written with every save. 2: MP3 downloads exist (0.7.0). Before that the app could only keep
         * the original stream, so a stored downloadMp3Kbps of 0 was a forced default, not the user's choice.
         */
        const val CURRENT_VERSION = 2

        fun fromJson(j: JsonElement?): Settings {
            val d = Settings()
            if (!j.isObject) return d
            val version = j.int("settingsVersion", 1)
            fun <T : Enum<T>> enumAt(key: String, values: Array<T>, def: T) =
                values.getOrElse(j.int(key, def.ordinal).coerceIn(0, values.size - 1)) { def }
            val theme = j["theme"].stringOrNull?.let {
                when (it) { "light" -> ThemeMode.Light; "system" -> ThemeMode.System; else -> ThemeMode.Dark }
            } ?: if (j.bool("lightTheme")) ThemeMode.Light else d.theme
            val gains = j["eqGains"].arr.mapNotNull { it.doubleOrNull?.toFloat() }.takeIf { it.size == 10 } ?: emptyList()
            return Settings(
                volume = j.float("volume", d.volume).coerceIn(0f, 1f),
                shuffle = j.bool("shuffle", d.shuffle),
                repeat = enumAt("repeat", RepeatMode.entries.toTypedArray(), d.repeat),
                quality = enumAt("quality", AudioQuality.entries.toTypedArray(), d.quality),
                meteredQuality = enumAt("meteredQuality", AudioQuality.entries.toTypedArray(), d.meteredQuality),
                normalizeVolume = j.bool("normalizeVolume", d.normalizeVolume),
                loudnessTarget = j.int("loudnessTarget", d.loudnessTarget).coerceIn(-23, -8),
                preloadNext = j.bool("preloadNext", d.preloadNext),
                eqEnabled = j.bool("eqEnabled", d.eqEnabled),
                eqPreset = j.str("eqPreset", d.eqPreset),
                eqGains = gains,
                eqPreampDb = j.float("eqPreampDb", d.eqPreampDb),
                crossfadeSec = j.int("crossfadeSec", d.crossfadeSec).coerceIn(0, 12),
                musicSpeed = j.float("musicSpeed", d.musicSpeed).coerceIn(0.5f, 3f),
                podcastSpeed = j.float("podcastSpeed", d.podcastSpeed).coerceIn(0.5f, 3f),
                accentMode = enumAt("accentMode", AccentMode.entries.toTypedArray(), d.accentMode),
                fixedAccent = j.str("fixedAccent", d.fixedAccent),
                theme = theme,
                reduceMotion = j.bool("reduceMotion", d.reduceMotion),
                libraryGrid = j.bool("libraryGrid", d.libraryGrid),
                downloadsDir = j.str("downloadsDir", d.downloadsDir),
                downloadMp3Kbps = j.int("downloadMp3Kbps", d.downloadMp3Kbps)
                    .let { if (version < 2 && it == 0) d.downloadMp3Kbps else it },
                downloadWifiOnly = j.bool("downloadWifiOnly", d.downloadWifiOnly),
                syncPaused = j.bool("syncPaused", d.syncPaused),
                syncOnMetered = j.bool("syncOnMetered", d.syncOnMetered),
                syncRemoveDropped = j.bool("syncRemoveDropped", d.syncRemoveDropped),
                syncCapGb = j.int("syncCapGb", d.syncCapGb).coerceIn(0, 1024),
                lyricsEnabled = j.bool("lyricsEnabled", d.lyricsEnabled),
                lyricsInDownloads = j.bool("lyricsInDownloads", d.lyricsInDownloads),
                scrobbleEnabled = j.bool("scrobbleEnabled", d.scrobbleEnabled),
                sponsorBlockEnabled = j.bool("sponsorBlockEnabled", d.sponsorBlockEnabled),
                endlessPlayback = j.bool("endlessPlayback", d.endlessPlayback),
                smartShuffle = j.bool("smartShuffle", d.smartShuffle),
                enhancedCollections = j.strings("enhancedCollections"),
                altSource = j.str("altSource", d.altSource),
                altSourceInstance = j.str("altSourceInstance", d.altSourceInstance),
                localFolders = j.strings("localFolders"),
                expandedFolders = j.strings("expandedFolders"),
                updateCheck = j.bool("updateCheck", d.updateCheck),
                lastUpdateCheck = j.long("lastUpdateCheck", d.lastUpdateCheck),
                skippedVersion = j.str("skippedVersion", d.skippedVersion),
                language = j.str("language", d.language),
                region = j.str("region", d.region),
            )
        }
    }
}

/**
 * Owner of the live [Settings]. Thread-safe; every change is saved (debounced 500 ms, atomic write).
 * Call [load] once at startup (ShadeTubeApp) before anything reads settings.
 */
object SettingsStore {
    private val state = MutableStateFlow(Settings())
    private var saveJob: Job? = null

    val flow: StateFlow<Settings> = state.asStateFlow()
    val current: Settings get() = state.value

    fun load() {
        state.value = Settings.fromJson(Files.readJson(Paths.settingsFile))
    }

    fun update(change: (Settings) -> Settings) {
        state.update(change)
        scheduleSave()
    }

    /** Writes at once (process going away). */
    fun flush() {
        saveJob?.cancel()
        Files.writeJson(Paths.settingsFile, state.value.toJson(), pretty = true)
    }

    @Synchronized
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = appScope.launch(AppDispatchers.io) {
            delay(500)
            Files.writeJson(Paths.settingsFile, state.value.toJson(), pretty = true)
        }
    }
}
