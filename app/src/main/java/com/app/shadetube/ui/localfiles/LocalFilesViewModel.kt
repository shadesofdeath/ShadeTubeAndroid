package com.app.shadetube.ui.localfiles

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.AppGraph
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.localfiles.LocalFormats
import com.app.shadetube.localfiles.LocalGroup
import com.app.shadetube.localfiles.LocalGroups
import com.app.shadetube.localfiles.LocalLibraryState
import com.app.shadetube.localfiles.LocalPaths
import com.app.shadetube.localfiles.LocalPlatform
import com.app.shadetube.playback.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A song row: the track, its index in the library and the file format tag ("FLAC"). */
data class LocalSong(val track: Track, val index: Int, val format: String)

/** A listed folder: the stored value (tree URI / path), a readable name and location, its song count. */
data class LocalFolderRow(val folder: String, val name: String, val location: String, val songs: Int, val unavailable: Boolean)

data class LocalContent(
    val generation: Long = -1,
    val songs: List<LocalSong> = emptyList(),
    val albums: List<LocalGroup> = emptyList(),
    val artists: List<LocalGroup> = emptyList(),
)

/** Yerel dosyalar: folders (SAF trees), scanning, the songs / albums / artists of the local library with search. */
class LocalFilesViewModel : ViewModel() {
    private val library get() = AppGraph.localLibrary

    val libraryState: StateFlow<LocalLibraryState> = AppGraph.localLibrary.flow

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun setQuery(q: String) {
        _query.value = q
    }

    val folders: StateFlow<List<LocalFolderRow>> = combine(SettingsStore.flow.map { it.localFolders }, AppGraph.localLibrary.flow) { list, st ->
        list.map { f ->
            val location = runCatching { LocalPaths.canonical(f) }.getOrDefault(f).ifEmpty { f }
            LocalFolderRow(
                folder = f,
                name = LocalPaths.nameOf(location).ifEmpty { location },
                location = location,
                songs = st.entries.count { LocalPaths.isUnder(it.path, f) },
                unavailable = library.folderUnavailable(f),
            )
        }
    }.flowOn(AppDispatchers.cpu).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Grouping happens once per library generation; the search filters it. */
    private val grouped: StateFlow<LocalContent> = AppGraph.localLibrary.flow
        .distinctUntilChangedBy { it.generation }
        .map { st ->
            val songs = st.tracks.mapIndexed { i, t ->
                val name = st.entries.getOrNull(i)?.fileName.orEmpty()
                LocalSong(t, i, LocalFormats.extensionOf(name).removePrefix(".").uppercase())
            }
            LocalContent(st.generation, songs, LocalGroups.albums(st.entries, st.tracks), LocalGroups.artists(st.tracks))
        }
        .flowOn(AppDispatchers.cpu)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalContent())

    val content: StateFlow<LocalContent> = combine(grouped, _query) { g, q ->
        if (q.isBlank()) g else {
            val tracks = LocalGroups.filterTracks(g.songs.map { it.track }, q).map { it.id }.toHashSet()
            g.copy(
                songs = g.songs.filter { it.track.id in tracks },
                albums = LocalGroups.filter(g.albums, q),
                artists = LocalGroups.filter(g.artists, q),
            )
        }
    }.flowOn(AppDispatchers.cpu).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalContent())

    val currentTrackId: StateFlow<String?> = AppGraph.player.current.map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val playing: StateFlow<Boolean> = AppGraph.player.status.map { it == Status.Playing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ---- folders --------------------------------------------------------------------------------------------------

    /** After ACTION_OPEN_DOCUMENT_TREE: keeps the grant and adds the folder (false = already covered / not stored). */
    fun addFolder(uri: Uri): Boolean {
        LocalPlatform.takeTreePermission(AppGraph.context, uri)
        // Not added (a listed folder covers it): the grant is kept, the same tree may be listed already.
        return library.addFolder(uri.toString())
    }

    fun removeFolder(folder: String) {
        library.removeFolder(folder)
        if (LocalPaths.isTreeUri(folder)) LocalPlatform.releaseTreePermission(AppGraph.context, folder)
    }

    fun rescan() = library.requestRescan()

    // ---- playback -------------------------------------------------------------------------------------------------

    fun playSongs(songs: List<LocalSong>, index: Int, name: String) =
        AppGraph.playerControls.playContext(songs.map { it.track }, index, LocalGroups.SONGS_CONTEXT, name)

    fun groupTracks(group: LocalGroup): List<Track> = LocalGroups.tracksOf(listOf(group), libraryState.value.tracks)

    fun playGroup(group: LocalGroup, album: Boolean, index: Int, shuffle: Boolean = false) {
        val tracks = groupTracks(group)
        if (tracks.isEmpty()) return
        AppGraph.playerControls.setShuffle(shuffle)
        AppGraph.playerControls.playContext(tracks, if (shuffle) -1 else index, LocalGroups.contextUri(album, group.key), group.title)
    }

    /** Every song of the shown album / artist cards (Play on a grid). */
    fun playGroups(groups: List<LocalGroup>, album: Boolean, name: String) {
        val tracks = LocalGroups.tracksOf(groups, libraryState.value.tracks)
        if (tracks.isEmpty()) return
        AppGraph.playerControls.playContext(tracks, 0, if (album) LocalGroups.ALBUMS_CONTEXT else LocalGroups.ARTISTS_CONTEXT, name)
    }
}
