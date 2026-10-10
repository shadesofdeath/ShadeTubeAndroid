package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistPage
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Page
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.catalog.Shelf
import com.app.shadetube.catalog.Track
import com.app.shadetube.spotify.ApiError
import com.app.shadetube.spotify.CreatedPlaylist
import com.app.shadetube.spotify.LibrarySnapshot
import com.app.shadetube.spotify.PlaylistEdit
import com.app.shadetube.spotify.PlaylistTracks
import com.app.shadetube.spotify.SessionState
import com.app.shadetube.spotify.UserProfile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

fun track(id: String, name: String = id, artist: String = "A", artistId: String = "", album: String = "", albumId: String = "",
          ms: Int = 180_000, added: Long = 0, uid: String = "", images: List<Image> = emptyList()) =
    Track(id = id, name = name, artists = listOf(ArtistRef(artistId, artist)), album = AlbumRef(albumId, album, images),
        durationMs = ms, addedAt = added, uid = uid)

/** A [SpotifyAccount] without network: state in flows, writes recorded, reads from maps. */
class FakeAccount(loggedIn: Boolean = false) : SpotifyAccount {
    override val state = MutableStateFlow(if (loggedIn) SessionState.LoggedIn else SessionState.LoggedOut)
    override val profile = MutableStateFlow(UserProfile(id = "spotify:user:me", name = "Me", username = "me"))
    override val library = MutableStateFlow(LibrarySnapshot(loaded = true))
    override val edits = MutableSharedFlow<PlaylistEdit>(extraBufferCapacity = 16)

    val likedWrites = ArrayList<Pair<String, Boolean>>()
    val albumWrites = ArrayList<Pair<String, Boolean>>()
    val followWrites = ArrayList<Pair<String, Boolean>>()
    val added = ArrayList<Pair<String, List<String>>>()
    val removed = ArrayList<Pair<String, List<String>>>()
    var createResult = CreatedPlaylist("spotify:playlist:new", 0)
    var created: Pair<String, List<String>>? = null
    var writeOk = true
    var renamed: Pair<String, String>? = null
    var deleted: String? = null

    /** uri -> all rows; pages are cut from it. */
    val playlists = HashMap<String, List<Track>>()
    val playlistMeta = HashMap<String, Playlist>()
    val pageRequests = ArrayList<Pair<String, Int>>()
    var homeResult: () -> List<Shelf> = { emptyList() }
    var homeCalls = 0
    val albums = HashMap<String, Album>()
    var searchCalls = 0

    fun login() { state.value = SessionState.LoggedIn }
    fun logout() { state.value = SessionState.LoggedOut }

    override fun setLiked(trackId: String, liked: Boolean) {
        likedWrites.add(trackId to liked)
        library.update { it.copy(likedIds = if (liked) it.likedIds + trackId else it.likedIds - trackId) }
    }

    override fun likeAll(trackIds: List<String>): Int {
        val fresh = trackIds.filter { it !in library.value.likedIds }.distinct()
        fresh.forEach { likedWrites.add(it to true) }
        library.update { it.copy(likedIds = it.likedIds + fresh) }
        return fresh.size
    }

    override fun setAlbumSaved(album: Album, saved: Boolean) {
        albumWrites.add(album.id to saved)
        library.update { if (saved) it.copy(albums = listOf(album) + it.albums) else it.copy(albums = it.albums.filter { a -> a.id != album.id }) }
    }

    override fun setArtistFollowed(artist: Artist, follow: Boolean) {
        followWrites.add(artist.id to follow)
        library.update { if (follow) it.copy(artists = listOf(artist) + it.artists) else it.copy(artists = it.artists.filter { a -> a.id != artist.id }) }
    }

    override suspend fun createPlaylist(name: String, trackUris: List<String>): CreatedPlaylist {
        created = name to trackUris
        return createResult
    }

    override suspend fun addToPlaylist(uri: String, trackUris: List<String>): Boolean {
        added.add(uri to trackUris)
        return writeOk
    }

    override suspend fun removeFromPlaylist(uri: String, uids: List<String>): Boolean {
        removed.add(uri to uids)
        return writeOk
    }

    override suspend fun renamePlaylist(uri: String, name: String): Boolean {
        renamed = uri to name
        return writeOk
    }

    override suspend fun deletePlaylist(uri: String): Boolean {
        deleted = uri
        return writeOk
    }

    override suspend fun search(query: String, limit: Int): SearchResults {
        searchCalls++
        return SearchResults(tracks = listOf(track("spotify:track:s", query)))
    }

    override suspend fun album(uri: String): Album = albums[uri] ?: throw ApiError(404, "album not found")
    override suspend fun artistPage(uri: String) = ArtistPage(artist = Artist(id = uri, name = "Spotify artist"))

    override suspend fun playlistTracks(uri: String, offset: Int, limit: Int): PlaylistTracks {
        pageRequests.add(uri to offset)
        val all = playlists[uri] ?: throw ApiError(404, "no playlist")
        val items = all.drop(offset).take(limit)
        val page = Page(items, all.size, offset, offset + items.size < all.size)
        return PlaylistTracks(page, if (offset == 0) playlistMeta[uri] else null, playlistMeta[uri]?.ownerName.orEmpty())
    }

    override suspend fun home(): List<Shelf> {
        homeCalls++
        return homeResult()
    }
}

/** An [OpenCatalog] whose calls are scripted. */
class FakeOpenCatalog : OpenCatalog {
    var trendingAlbums: () -> List<Album> = { emptyList() }
    var trendingArtists: () -> List<Artist> = { emptyList() }
    var fresh: () -> List<Album> = { emptyList() }
    var similar: (String) -> List<Artist> = { emptyList() }
    val similarAsked = ArrayList<String>()
    val searches = ArrayList<String>()

    override suspend fun search(query: String, limit: Int): SearchResults {
        searches.add(query)
        return SearchResults(tracks = listOf(track("mbid-1", query)))
    }

    override suspend fun album(id: String) = Album(id = id, name = "MB album $id", tracks = listOf(track("rec-1"), track("rec-2")))
    override suspend fun artistPage(id: String) = ArtistPage(artist = Artist(id = id, name = "MB artist"))
    override suspend fun searchArtists(query: String, limit: Int): List<Artist> {
        searches.add("artists:$query:$limit")
        return listOf(Artist(id = "a1"))
    }

    override suspend fun searchAlbums(query: String, limit: Int): List<Album> {
        searches.add("albums:$query:$limit")
        return listOf(Album(id = "rg1"))
    }

    override suspend fun trendingAlbums(range: String, limit: Int) = trendingAlbums()
    override suspend fun trendingArtists(range: String, limit: Int) = trendingArtists()
    override suspend fun freshReleases(days: Int, limit: Int) = fresh()
    override suspend fun similarArtists(artistId: String, limit: Int): List<Artist> {
        similarAsked.add(artistId)
        return similar(artistId)
    }
}

class FakePlayer : PlayerControls {
    override var contextUri = ""
    override var isPlaying = false
    override var isPaused = false
    var toggles = 0
    var plays = 0
    var shuffleOn = false
    var lastContext: Triple<List<Track>, Int, String>? = null
    var lastName = ""

    override fun togglePause() { toggles++ }
    override fun play() { plays++ }
    override fun setShuffle(on: Boolean) { shuffleOn = on }
    override fun playContext(tracks: List<Track>, index: Int, contextUri: String, contextName: String) {
        lastContext = Triple(tracks, index, contextUri)
        lastName = contextName
        this.contextUri = contextUri
        isPlaying = true
    }
    override fun enqueue(tracks: List<Track>) {}
}
