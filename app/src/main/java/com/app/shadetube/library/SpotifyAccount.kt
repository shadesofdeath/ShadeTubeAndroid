package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistPage
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.catalog.Shelf
import com.app.shadetube.spotify.CreatedPlaylist
import com.app.shadetube.spotify.LibrarySnapshot
import com.app.shadetube.spotify.PlaylistEdit
import com.app.shadetube.spotify.PlaylistTracks
import com.app.shadetube.spotify.Session
import com.app.shadetube.spotify.SessionState
import com.app.shadetube.spotify.UserProfile
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The narrow view of the user's Spotify session the library layer needs (state, library snapshot, optimistic
 * heart / save / follow writes, playlist edits and the catalog reads). [SessionAccount] adapts the real
 * [Session]; unit tests use a fake.
 *
 * Catalog reads throw (spotify.ApiError / IOException) like [com.app.shadetube.spotify.SpotifyApi]; writes report
 * success.
 */
interface SpotifyAccount {
    val state: StateFlow<SessionState>
    val profile: StateFlow<UserProfile>
    val library: StateFlow<LibrarySnapshot>

    /** Successful playlist writes, for open collection pages to follow. */
    val edits: SharedFlow<PlaylistEdit>

    val loggedIn: Boolean get() = state.value == SessionState.LoggedIn

    fun isLiked(trackId: String): Boolean = trackId in library.value.likedIds
    fun isSavedAlbum(id: String): Boolean = library.value.albums.any { it.id == id }
    fun isFollowing(id: String): Boolean = library.value.artists.any { it.id == id }
    fun findPlaylist(uri: String): Playlist? = library.value.playlists.firstOrNull { it.id == uri }

    /** Playlists the user can add songs to (owned or collaborative), in library order. Excludes Liked Songs. */
    fun editablePlaylists(): List<Playlist> =
        library.value.playlists.filter { it.editable && it.id.startsWith("spotify:playlist:") }

    /** Heart (optimistic) + mirrored write to Spotify. */
    fun setLiked(trackId: String, liked: Boolean)

    /** Likes every not-yet-liked id with one write; returns how many were new. */
    fun likeAll(trackIds: List<String>): Int
    fun setAlbumSaved(album: Album, saved: Boolean)
    fun setArtistFollowed(artist: Artist, follow: Boolean)

    suspend fun createPlaylist(name: String, trackUris: List<String>): CreatedPlaylist
    suspend fun addToPlaylist(uri: String, trackUris: List<String>): Boolean
    suspend fun removeFromPlaylist(uri: String, uids: List<String>): Boolean
    suspend fun renamePlaylist(uri: String, name: String): Boolean
    suspend fun deletePlaylist(uri: String): Boolean

    suspend fun search(query: String, limit: Int): SearchResults
    suspend fun album(uri: String): Album
    suspend fun artistPage(uri: String): ArtistPage

    /** One page of a playlist / Liked Songs (with the header when the response carried one). */
    suspend fun playlistTracks(uri: String, offset: Int, limit: Int): PlaylistTracks

    /** The personalized home shelves. Throws ApiError (412 / 429 ...). */
    suspend fun home(): List<Shelf>
}

/** [SpotifyAccount] over the app's [Session] (AppGraph builds one next to the session). */
class SessionAccount(private val session: Session) : SpotifyAccount {
    override val state: StateFlow<SessionState> get() = session.state
    override val profile: StateFlow<UserProfile> get() = session.profile
    override val library: StateFlow<LibrarySnapshot> get() = session.library
    override val edits: SharedFlow<PlaylistEdit> get() = session.edits
    override val loggedIn: Boolean get() = session.loggedIn

    override fun isLiked(trackId: String) = session.isLiked(trackId)
    override fun isSavedAlbum(id: String) = session.isSavedAlbum(id)
    override fun isFollowing(id: String) = session.isFollowing(id)
    override fun findPlaylist(uri: String) = session.findPlaylist(uri)
    override fun editablePlaylists() = session.editablePlaylists()

    override fun setLiked(trackId: String, liked: Boolean) = session.setLiked(trackId, liked)
    override fun likeAll(trackIds: List<String>) = session.likeAll(trackIds)
    override fun setAlbumSaved(album: Album, saved: Boolean) = session.setAlbumSaved(album, saved)
    override fun setArtistFollowed(artist: Artist, follow: Boolean) = session.setArtistFollowed(artist, follow)

    override suspend fun createPlaylist(name: String, trackUris: List<String>) = session.createPlaylist(name, trackUris)
    override suspend fun addToPlaylist(uri: String, trackUris: List<String>) = session.addToPlaylist(uri, trackUris)
    override suspend fun removeFromPlaylist(uri: String, uids: List<String>) = session.removeFromPlaylist(uri, uids)
    override suspend fun renamePlaylist(uri: String, name: String) = session.renamePlaylist(uri, name)
    override suspend fun deletePlaylist(uri: String) = session.deletePlaylist(uri)

    override suspend fun search(query: String, limit: Int) = session.api.search(query, limit)
    override suspend fun album(uri: String) = session.api.album(uri)
    override suspend fun artistPage(uri: String) = session.api.artistPage(uri)
    override suspend fun playlistTracks(uri: String, offset: Int, limit: Int) = session.api.playlistTracks(uri, offset, limit)
    override suspend fun home() = session.api.home()
}
