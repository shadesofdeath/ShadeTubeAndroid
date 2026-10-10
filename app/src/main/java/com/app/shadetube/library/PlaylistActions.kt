package com.app.shadetube.library

import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.Track

/** spotify:track: ids only (what may be sent to a Spotify playlist). */
fun spotifyTrackUris(tracks: List<Track>): List<String> = tracks.map { it.id }.filter { it.startsWith("spotify:track:") }

/** Before asking for a name (C++ promptNewPlaylist's first check and its dialog text). */
enum class NewPlaylistCheck {
    /** Logged out: a local playlist ("Bir isim ver. Sonradan değiştirebilirsin."). */
    Local,

    /** Logged in: created on the Spotify account ("Spotify hesabında oluşturulur..."). */
    Spotify,

    /**
     * Logged in, and none of the songs is on Spotify (local files, MusicBrainz rows): they cannot go there, so no
     * empty playlist is created for them ("Bu şarkılar Spotify'da olmadığı için Spotify çalma listesine kaydedilemez").
     */
    NotOnSpotify,
}

sealed interface CreatePlaylistResult {
    /**
     * The playlist exists ([id]: local id or spotify:playlist: URI). [added] songs went in; [addFailed]: the
     * playlist was created but adding the songs failed (say so instead of claiming they were added); [skipped]: songs
     * left out because they are not on Spotify.
     */
    data class Created(val id: String, val spotify: Boolean, val added: Int, val addFailed: Boolean, val skipped: Int) :
        CreatePlaylistResult

    /** "Çalma listesi oluşturulamadı". */
    data object Failed : CreatePlaylistResult
    data object NotOnSpotify : CreatePlaylistResult
}

sealed interface AddToPlaylistResult {
    /** "\"{name}\" listesine eklendi" (+ "(Spotify'da olmayan {skipped} şarkı eklenmedi)" when [skipped] > 0). */
    data class Added(val name: String, val added: Int, val skipped: Int) : AddToPlaylistResult

    /** "\"{name}\" listesine eklenemedi". */
    data class Failed(val name: String) : AddToPlaylistResult

    /** "Zaten listede" (local playlist: every song was in it already). */
    data class AlreadyInList(val name: String) : AddToPlaylistResult

    /** "Yalnızca Spotify şarkıları eklenebilir". */
    data object OnlySpotifyTracks : AddToPlaylistResult

    /** No such playlist (in the active source): nothing happens. */
    data object NotFound : AddToPlaylistResult
}

enum class RenameResult {
    /** Empty or the same name: nothing to do. */
    Unchanged,
    Renamed,

    /** "Çalma listesi yeniden adlandırılamadı". */
    Failed,

    /** A Spotify playlist while logged out ("Spotify bağlantısı yok"). */
    NoSpotifyConnection,
}

/** Which confirmation the delete action asks for. */
enum class DeleteKind {
    /** Owned Spotify playlist: "Çalma listesi silinsin mi?" / "\"{}\" Spotify hesabından silinecek." / "Sil". */
    DeleteSpotify,

    /** Someone else's Spotify playlist: "Kitaplıktan kaldırılsın mı?" / "...artık kitaplığında görünmeyecek." / "Kaldır". */
    RemoveFromLibrary,

    /** Local playlist: "Çalma listesi silinsin mi?" / "\"{}\" kalıcı olarak silinecek." / "Sil". */
    DeleteLocal,
}

/** [ok]: deleted / removed; the UI then leaves the playlist's page if it is open (-> Library). */
data class DeleteResult(val kind: DeleteKind, val ok: Boolean)

enum class RemoveTracksResult {
    /** "Çalma listesinden kaldırıldı". */
    Removed,

    /** "Çalma listesinden kaldırılamadı". */
    Failed,

    /** No row of the list was among the songs (recommendations only). */
    Nothing,
}

/**
 * The playlist decisions of the C++ shared helpers (app/AppContext: promptNewPlaylist, promptRenamePlaylist,
 * confirmDeletePlaylist, addToPlaylistWithToast, the add-to-playlist menu and "Bu çalma listesinden kaldır") without
 * the dialogs and toasts: logged in, these write to the user's Spotify account; logged out they edit the local
 * playlists. `id` is a local id or a spotify:playlist: URI. Results are typed; the UI shows the dialogs / toasts.
 */
class PlaylistActions(
    private val library: UserLibrary,
    private val account: SpotifyAccount,
) {
    private fun session(): SpotifyAccount? = if (account.loggedIn) account else null

    /** Logged in, in the library, and the user may edit it. */
    fun isEditableSpotifyPlaylist(id: String): Boolean {
        val s = session() ?: return false
        if (!id.startsWith("spotify:playlist:")) return false
        return s.findPlaylist(id)?.editable == true
    }

    /**
     * Playlists the add-to-playlist menu offers (after "Yeni çalma listesi…"): logged in, the user's Spotify playlists
     * they can add to (owned or collaborative) in library order, the most recent first; else the local playlists.
     * (The desktop menu kept only as many rows as fit the window; the UI may scroll instead.)
     */
    fun addTargets(): List<Playlist> = session()?.editablePlaylists() ?: library.playlists

    fun newPlaylistCheck(tracks: List<Track> = emptyList()): NewPlaylistCheck = when {
        session() == null -> NewPlaylistCheck.Local
        tracks.isNotEmpty() && spotifyTrackUris(tracks).isEmpty() -> NewPlaylistCheck.NotOnSpotify
        else -> NewPlaylistCheck.Spotify
    }

    /**
     * Creates the playlist named [name] (trimmed; empty = the default name) and adds [tracks] (Spotify: only Spotify
     * tracks). Spotify: returns once the playlist exists.
     */
    suspend fun createPlaylist(name: String, tracks: List<Track> = emptyList()): CreatePlaylistResult {
        val clean = name.trim().ifEmpty { library.labels.newPlaylist }
        val s = session()
        if (s != null) {
            val uris = spotifyTrackUris(tracks)
            if (tracks.isNotEmpty() && uris.isEmpty()) return CreatePlaylistResult.NotOnSpotify
            val skipped = tracks.size - uris.size
            val created = s.createPlaylist(clean, uris)
            if (created.uri.isEmpty()) return CreatePlaylistResult.Failed
            // The playlist exists even if adding the songs failed.
            val addFailed = uris.isNotEmpty() && created.added == 0
            return CreatePlaylistResult.Created(created.uri, true, created.added, addFailed, skipped)
        }
        val id = library.createPlaylist(clean)
        val added = if (tracks.isNotEmpty()) library.addToPlaylist(id, tracks) else 0
        return CreatePlaylistResult.Created(id, false, added, false, 0)
    }

    /**
     * Adds songs to the playlist [id]: an editable Spotify playlist (logged in; only its Spotify songs, the others are
     * counted in `skipped`) or a local playlist. Used by the add-to-playlist menu and by drag and drop.
     */
    suspend fun addToPlaylist(id: String, tracks: List<Track>): AddToPlaylistResult {
        val s = session()
        if (s != null) {
            val p = s.findPlaylist(id) ?: return AddToPlaylistResult.NotFound
            val uris = spotifyTrackUris(tracks)
            if (uris.isEmpty()) return AddToPlaylistResult.OnlySpotifyTracks
            val skipped = tracks.size - uris.size // local files / MusicBrainz rows never go to Spotify
            return if (s.addToPlaylist(id, uris)) AddToPlaylistResult.Added(p.name, uris.size, skipped)
            else AddToPlaylistResult.Failed(p.name)
        }
        val p = library.playlist(id) ?: return AddToPlaylistResult.NotFound
        val n = library.addToPlaylist(id, tracks)
        return if (n > 0) AddToPlaylistResult.Added(p.name, n, 0) else AddToPlaylistResult.AlreadyInList(p.name)
    }

    suspend fun renamePlaylist(id: String, newName: String, currentName: String): RenameResult {
        val name = newName.trim()
        if (name.isEmpty() || name == currentName) return RenameResult.Unchanged
        if (CatalogSource.isSpotifyId(id)) {
            val s = session() ?: return RenameResult.NoSpotifyConnection
            return if (s.renamePlaylist(id, name)) RenameResult.Renamed else RenameResult.Failed
        }
        library.renamePlaylist(id, name)
        return RenameResult.Renamed
    }

    /**
     * "Yeniden adlandır" in a card / sidebar menu: local playlists always, Spotify playlists the logged-in user owns.
     * (Liked Songs and folders: never.)
     */
    fun canRename(id: String): Boolean {
        if (id.isEmpty() || id == com.app.shadetube.catalog.LIKED_SONGS_URI) return false
        if (!CatalogSource.isSpotifyId(id)) return library.playlist(id) != null
        return session()?.findPlaylist(id)?.owned == true
    }

    /** The confirmation to show; null when there is nothing to delete (a Spotify playlist while logged out). */
    fun deleteKind(id: String): DeleteKind? {
        if (!CatalogSource.isSpotifyId(id)) return DeleteKind.DeleteLocal
        val s = session() ?: return null
        return if (s.findPlaylist(id)?.owned == true) DeleteKind.DeleteSpotify else DeleteKind.RemoveFromLibrary
    }

    /**
     * After the confirmation: Spotify removes it from the library (Spotify's "delete" for an owned playlist, unfollow
     * otherwise); a local playlist is deleted. Null when [deleteKind] is null.
     */
    suspend fun deletePlaylist(id: String): DeleteResult? {
        val kind = deleteKind(id) ?: return null
        if (kind == DeleteKind.DeleteLocal) {
            library.deletePlaylist(id)
            return DeleteResult(kind, true)
        }
        val s = session() ?: return null
        return DeleteResult(kind, s.deletePlaylist(id))
    }

    /**
     * "Bu çalma listesinden kaldır" for the list's own rows (recommended rows are not part of it). Spotify removes
     * playlist ROWS (uids), so a duplicate elsewhere in the list stays; a local playlist drops the songs by id.
     */
    suspend fun removeFromPlaylist(playlistId: String, tracks: List<Track>): RemoveTracksResult {
        val own = tracks.filter { !it.recommended }
        if (own.isEmpty()) return RemoveTracksResult.Nothing
        if (CatalogSource.isSpotifyId(playlistId)) {
            val uids = own.map { it.uid }.filter { it.isNotEmpty() }
            val s = session()
            if (s == null || uids.isEmpty()) return RemoveTracksResult.Failed
            return if (s.removeFromPlaylist(playlistId, uids)) RemoveTracksResult.Removed else RemoveTracksResult.Failed
        }
        library.removeFromPlaylist(playlistId, own.map { it.id })
        return RemoveTracksResult.Removed
    }
}
