package com.app.shadetube.ui.transfer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.AppGraph
import com.app.shadetube.app.OpenRequests
import com.app.shadetube.app.PendingOpen
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.links.LinkKind
import com.app.shadetube.links.Links
import com.app.shadetube.playlistio.ImportPreview
import com.app.shadetube.playlistio.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Why step 1 could not produce a preview. */
enum class ImportError { Unreadable, NotYouTubeList, YouTubeFailed }

/** Playlist import (SCREENS 23): 1) source, 2) preview, 3) name + create. */
class ImportViewModel(pendingFromIntent: Boolean, youtubeUrl: String) : ViewModel() {
    data class State(
        /** 0 source, 1 preview, 2 name. */
        val step: Int = 0,
        val loading: Boolean = false,
        val error: ImportError? = null,
        val preview: ImportPreview? = null,
        val link: String = "",
        val name: String = "",
        val alsoOnSpotify: Boolean = false,
        val committing: Boolean = false,
    ) {
        val canSpotify: Boolean get() = AppGraph.session.loggedIn && (preview?.spotifyCount ?: 0) > 0
    }

    private val _state = MutableStateFlow(State(link = youtubeUrl))
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    init {
        if (pendingFromIntent) {
            viewModelScope.launch {
                AppGraph.pendingOpen.collect { p ->
                    when (p) {
                        PendingOpen.Loading -> _state.update { it.copy(loading = true, error = null) }
                        is PendingOpen.PlaylistImport -> {
                            AppGraph.consumePendingOpen()
                            showPreview(p.preview)
                        }
                        is PendingOpen.Failed -> {
                            AppGraph.consumePendingOpen()
                            _state.update { it.copy(loading = false, error = ImportError.Unreadable) }
                        }
                        is PendingOpen.AudioFiles, null -> Unit
                    }
                }
            }
        } else if (youtubeUrl.isNotBlank()) {
            previewLink()
        }
    }

    private fun showPreview(p: ImportPreview) {
        _state.update { it.copy(step = 1, loading = false, error = null, preview = p, name = p.name) }
    }

    fun setLink(text: String) = _state.update { it.copy(link = text, error = if (it.error == ImportError.NotYouTubeList) null else it.error) }

    /** A playlist file picked with the SAF picker. */
    fun pickFile(uri: Uri) {
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            val resolver = AppGraph.context.contentResolver
            val (name, mime) = withContext(AppDispatchers.io) {
                OpenRequests.displayName(resolver, uri.toString()) to runCatching { resolver.getType(uri) }.getOrNull()
            }
            val display = OpenRequests.playlistName(name, mime)
            AppGraph.transfer.previewFile({ resolver.openInputStream(uri) ?: throw IOException("cannot open $uri") }, display)
                .onSuccess { showPreview(it) }
                .onFailure { _state.update { s -> s.copy(loading = false, error = ImportError.Unreadable) } }
        }
    }

    /** The YouTube / YouTube Music playlist link in the field. */
    fun previewLink() {
        val link = Links.parse(_state.value.link)
        if (link.kind != LinkKind.YouTubePlaylist) {
            _state.update { it.copy(error = ImportError.NotYouTubeList) }
            return
        }
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            AppGraph.transfer.previewYouTubePlaylist(link.id)
                .onSuccess { showPreview(it) }
                .onFailure { _state.update { s -> s.copy(loading = false, error = ImportError.YouTubeFailed) } }
        }
    }

    fun cancelLoading() {
        job?.cancel()
        _state.update { it.copy(loading = false) }
    }

    fun toName() = _state.update { if (it.preview != null && it.preview.tracks.isNotEmpty()) it.copy(step = 2) else it }

    /** Back one step; false when already at the first one (the screen closes). */
    fun back(): Boolean {
        val s = _state.value
        if (s.committing) return true
        if (s.step == 0) return false
        _state.update { it.copy(step = it.step - 1, error = null) }
        return true
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }
    fun setAlsoOnSpotify(on: Boolean) = _state.update { it.copy(alsoOnSpotify = on) }

    fun commit(onDone: (ImportResult, String) -> Unit, onFailed: () -> Unit) {
        val s = _state.value
        val p = s.preview ?: return
        if (s.committing) return
        _state.update { it.copy(committing = true) }
        viewModelScope.launch {
            try {
                val name = s.name.trim().ifEmpty { p.name }
                val r = AppGraph.transfer.commit(p, name, s.alsoOnSpotify && s.canSpotify)
                _state.update { it.copy(committing = false) }
                onDone(r, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(committing = false) }
                onFailed()
            }
        }
    }
}
