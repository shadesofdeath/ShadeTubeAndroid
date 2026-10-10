package com.app.shadetube.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.AppGraph
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isLocalId
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.lyrics.FileAudio
import com.app.shadetube.lyrics.LocalAudioFile
import com.app.shadetube.lyrics.Lyrics
import com.app.shadetube.lyrics.LyricsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LyricsUi {
    data object Loading : LyricsUi
    /** Settings › lyrics off. */
    data object Disabled : LyricsUi
    /** Radio stations have no lyrics. */
    data object Station : LyricsUi
    data object NotFound : LyricsUi
    data object Instrumental : LyricsUi
    data class Loaded(val trackId: String, val lyrics: Lyrics) : LyricsUi
}

/**
 * Lyrics of the playing track ([LyricsService]: the file's own lyrics for disk items — local files and downloads —
 * then LRCLIB / Spotify) and its timing offset (±0.25 s steps, stored per track).
 */
class LyricsViewModel : ViewModel() {
    private val service = AppGraph.lyrics
    private val _state = MutableStateFlow<LyricsUi>(LyricsUi.Loading)
    val state: StateFlow<LyricsUi> = _state.asStateFlow()
    private val _offset = MutableStateFlow(0)
    val offsetMs: StateFlow<Int> = _offset.asStateFlow()
    private var trackId = ""
    private var attempt = 0
    private val retry = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            AppGraph.player.current.distinctUntilChangedBy { it?.id }.collectLatest { t -> load(t) }
        }
        viewModelScope.launch {
            service.offsetChanges.collect { c -> if (c != null && c.trackId == trackId) _offset.value = c.offsetMs }
        }
        viewModelScope.launch {
            retry.collect { if (it > 0) load(AppGraph.player.currentTrack) }
        }
    }

    fun reload() {
        retry.value = ++attempt
    }

    private suspend fun load(t: Track?) {
        trackId = t?.id.orEmpty()
        if (t == null) {
            _state.value = LyricsUi.NotFound
            return
        }
        if (InternetRadio.isStation(t.id)) {
            _state.value = LyricsUi.Station
            return
        }
        if (!SettingsStore.current.lyricsEnabled) {
            _state.value = LyricsUi.Disabled
            return
        }
        _state.value = LyricsUi.Loading
        _offset.value = runCatching { service.offsetMs(t.id) }.getOrDefault(0)
        val audio = withContext(AppDispatchers.io) { localAudio(t) }
        val l = runCatching { service.lyricsFor(t, audio) }.getOrNull()
        if (trackId != t.id) return
        _state.value = when {
            l == null -> LyricsUi.NotFound
            l.instrumental -> LyricsUi.Instrumental
            l.lines.none { it.text.isNotBlank() } -> LyricsUi.NotFound
            else -> LyricsUi.Loaded(t.id, l)
        }
    }

    /** The file the item plays from: a local / opened file, or a finished download. Blocking. */
    private fun localAudio(t: Track): LocalAudioFile? = runCatching {
        if (isLocalId(t.id)) {
            val file = AppGraph.localFiles.fileFor(t.id) ?: return@runCatching null
            AppGraph.localFiles.openerFor(t.id)?.audio(file)
        } else {
            AppGraph.downloads.localFileFor(t.id)?.takeIf { it.exists() }?.let { FileAudio(it) }
        }
    }.getOrNull()

    fun shift(steps: Int) {
        val id = trackId.ifEmpty { return }
        viewModelScope.launch { _offset.value = service.offsetBy(id, steps * LyricsService.OFFSET_STEP_MS) }
    }

    fun resetOffset() {
        val id = trackId.ifEmpty { return }
        viewModelScope.launch { _offset.value = service.setOffset(id, 0) }
    }
}
