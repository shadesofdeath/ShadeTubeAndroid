package com.app.shadetube.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.app.shadetube.AppGraph
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.AppLocale
import com.app.shadetube.core.Files
import com.app.shadetube.core.Paths
import com.app.shadetube.core.Settings
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.musicbrainz.MbNet
import com.app.shadetube.scrobble.ScrobbleError
import com.app.shadetube.scrobble.ScrobbleResult
import com.app.shadetube.scrobble.ScrobbleState
import com.app.shadetube.spotify.SessionState
import com.app.shadetube.spotify.UserProfile
import com.app.shadetube.update.UpdateState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Sizes of the caches Settings › Depolama can clear (bytes; -1 = not measured yet). */
data class CacheSizes(
    val images: Long = -1,
    val matches: Long = -1,
    val lyrics: Long = -1,
    val musicBrainz: Long = -1,
    val crashLogs: Int = 0,
    val downloadsDir: String = "",
)

enum class CacheKind { Images, Matches, Lyrics, MusicBrainz, CrashLogs }

/** One-shot texts of the settings screens (the screen words them). */
sealed interface SettingsMessage {
    data class CacheCleared(val kind: CacheKind) : SettingsMessage
    data class LastfmConnected(val user: String) : SettingsMessage
    data class ListenBrainzConnected(val user: String) : SettingsMessage
    data object KeysSaved : SettingsMessage
    /** Below Android 13: the activity must be recreated to show the new language. */
    data object LanguageChanged : SettingsMessage
}

/**
 * State of Settings (all sections) and the Equalizer / profile header: settings, Spotify session, scrobbling,
 * updater, cache sizes. Writes go through [SettingsStore.update]; the services follow the settings themselves.
 */
class SettingsViewModel : ViewModel() {
    private val context: Context get() = AppGraph.context

    val settings: StateFlow<Settings> = SettingsStore.flow
    val sessionState: StateFlow<SessionState> = AppGraph.session.state
    val profile: StateFlow<UserProfile> = AppGraph.session.profile
    val scrobble: StateFlow<ScrobbleState> = AppGraph.scrobbler.state
    val update: StateFlow<UpdateState> = AppGraph.updater.state
    val downloads = AppGraph.downloads.state
    val local = AppGraph.localLibrary.flow

    val currentVersion: String get() = AppGraph.updater.currentVersion

    private val _caches = MutableStateFlow(CacheSizes())
    val caches: StateFlow<CacheSizes> = _caches.asStateFlow()

    /** A Last.fm / ListenBrainz request is running. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _lastfmError = MutableStateFlow<ScrobbleError?>(null)
    val lastfmError: StateFlow<ScrobbleError?> = _lastfmError.asStateFlow()
    private val _listenbrainzError = MutableStateFlow<ScrobbleError?>(null)
    val listenbrainzError: StateFlow<ScrobbleError?> = _listenbrainzError.asStateFlow()

    private val _messages = MutableSharedFlow<SettingsMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<SettingsMessage> = _messages.asSharedFlow()

    fun change(block: (Settings) -> Settings) = SettingsStore.update(block)

    // ---- account ------------------------------------------------------------------------------------------------

    fun logout() = AppGraph.session.logout()

    fun setScrobbleEnabled(on: Boolean) = change { it.copy(scrobbleEnabled = on) }

    fun saveLastfmKeys(apiKey: String, secret: String) {
        AppGraph.scrobbler.lastfmSetKeys(apiKey, secret)
        _lastfmError.value = null
        _messages.tryEmit(SettingsMessage.KeysSaved)
    }

    /** auth.getToken; [openUrl] gets the approval page (opened in the browser). */
    fun beginLastfm(openUrl: (String) -> Unit) {
        if (_busy.value != null) return
        _busy.value = BUSY_LASTFM
        _lastfmError.value = null
        viewModelScope.launch {
            when (val r = AppGraph.scrobbler.lastfmBeginAuth()) {
                is ScrobbleResult.Ok -> openUrl(r.value)
                is ScrobbleResult.Failed -> _lastfmError.value = r.error
            }
            _busy.value = null
        }
    }

    fun finishLastfm() {
        if (_busy.value != null) return
        _busy.value = BUSY_LASTFM
        _lastfmError.value = null
        viewModelScope.launch {
            when (val r = AppGraph.scrobbler.lastfmFinishAuth()) {
                is ScrobbleResult.Ok -> _messages.tryEmit(SettingsMessage.LastfmConnected(r.value))
                is ScrobbleResult.Failed -> _lastfmError.value = r.error
            }
            _busy.value = null
        }
    }

    fun disconnectLastfm() {
        AppGraph.scrobbler.lastfmDisconnect()
        _lastfmError.value = null
    }

    fun setListenBrainzToken(token: String) {
        if (_busy.value != null) return
        _busy.value = BUSY_LISTENBRAINZ
        _listenbrainzError.value = null
        viewModelScope.launch {
            when (val r = AppGraph.scrobbler.listenbrainzSetToken(token)) {
                is ScrobbleResult.Ok -> _messages.tryEmit(SettingsMessage.ListenBrainzConnected(r.value))
                is ScrobbleResult.Failed -> _listenbrainzError.value = r.error
            }
            _busy.value = null
        }
    }

    fun disconnectListenBrainz() {
        AppGraph.scrobbler.listenbrainzDisconnect()
        _listenbrainzError.value = null
    }

    // ---- appearance -----------------------------------------------------------------------------------------------

    /**
     * UI language ("" = system): stored in Settings.language and, on Android 13+, applied as the per-app locale
     * (the system recreates the activity). Below 13 [AppLocale] updates the application resources and the backend
     * labels, and the screen recreates the activity ([SettingsMessage.LanguageChanged]; MainActivity wraps its context).
     */
    fun setLanguage(code: String) {
        change { it.copy(language = code) }
        AppGraph.home.shelfCache.invalidate() // shelf titles come localized from Spotify
        com.app.shadetube.ui.home.HomeFeedHub.reload()
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                val lm = context.getSystemService(LocaleManager::class.java)
                lm.applicationLocales = if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
            }
        } else {
            AppLocale.applyToApp(context.applicationContext, code)
            AppGraph.applyLabels(context.applicationContext)
            _messages.tryEmit(SettingsMessage.LanguageChanged)
        }
    }

    // ---- storage --------------------------------------------------------------------------------------------------

    private fun coilDiskBytes(): Long = runCatching { SingletonImageLoader.get(context).diskCache?.size ?: 0L }.getOrDefault(0L)

    private fun crashDir() = File(Paths.appData, "crashes")

    fun refreshCaches() {
        viewModelScope.launch {
            _caches.value = withContext(AppDispatchers.io) {
                CacheSizes(
                    images = Files.sizeOf(Paths.imageCacheDir) + coilDiskBytes(),
                    matches = Paths.cache("matches.json").let { if (it.exists()) it.length() else 0L },
                    lyrics = File(Paths.cacheDir, "lyrics").let { if (it.exists()) Files.sizeOf(it) else 0L },
                    musicBrainz = File(Paths.cacheDir, "mb").let { if (it.exists()) Files.sizeOf(it) else 0L },
                    crashLogs = crashDir().listFiles()?.count { it.isFile } ?: 0,
                    downloadsDir = runCatching { Paths.downloadsDir.absolutePath }.getOrDefault(""),
                )
            }
        }
    }

    fun clearCache(kind: CacheKind) {
        viewModelScope.launch {
            withContext(AppDispatchers.io) {
                when (kind) {
                    CacheKind.Images -> {
                        runCatching {
                            val loader = SingletonImageLoader.get(context)
                            loader.memoryCache?.clear()
                            loader.diskCache?.clear()
                        }
                        Paths.imageCacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    }
                    // Automatic matches (memory + matches.json); manual "Yanlış eşleşme?" picks stay pinned.
                    CacheKind.Matches -> AppGraph.matchService.clearCache()
                    CacheKind.Lyrics -> File(Paths.cacheDir, "lyrics").listFiles()?.forEach { it.deleteRecursively() }
                    CacheKind.MusicBrainz -> {
                        MbNet.clearMemoryCache()
                        File(Paths.cacheDir, "mb").listFiles()?.forEach { it.deleteRecursively() }
                    }
                    CacheKind.CrashLogs -> crashDir().listFiles()?.forEach { it.delete() }
                }
            }
            _messages.tryEmit(SettingsMessage.CacheCleared(kind))
            refreshCaches()
        }
    }

    // ---- updates --------------------------------------------------------------------------------------------------

    fun checkUpdate() = AppGraph.updater.check(manual = true)
    fun downloadUpdate() = AppGraph.updater.downloadAndInstall()
    fun cancelUpdate() = AppGraph.updater.cancel()
    fun skipUpdate() = AppGraph.updater.skip()
    fun installUpdate() = AppGraph.updater.install()
    fun canInstall(): Boolean = AppGraph.updater.canInstall()
    fun installPermissionIntent() = AppGraph.updater.installPermissionIntent()

    // ---- local music ----------------------------------------------------------------------------------------------

    fun rescanLocal() = AppGraph.localLibrary.requestRescan()

    companion object {
        const val BUSY_LASTFM = "lastfm"
        const val BUSY_LISTENBRAINZ = "listenbrainz"
    }
}
