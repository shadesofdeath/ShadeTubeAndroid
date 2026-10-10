package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistPage
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.musicbrainz.MusicBrainz

/**
 * MusicBrainz / ListenBrainz reads the library layer uses (the open catalog shown while logged out).
 * [MusicBrainzCatalog] delegates to the [MusicBrainz] object; unit tests use a fake.
 */
interface OpenCatalog {
    suspend fun search(query: String, limit: Int): SearchResults
    suspend fun album(id: String): Album
    suspend fun artistPage(id: String): ArtistPage
    suspend fun searchArtists(query: String, limit: Int): List<Artist>
    suspend fun searchAlbums(query: String, limit: Int): List<Album>
    suspend fun trendingAlbums(range: String, limit: Int): List<Album>
    suspend fun trendingArtists(range: String, limit: Int): List<Artist>
    suspend fun freshReleases(days: Int, limit: Int): List<Album>
    suspend fun similarArtists(artistId: String, limit: Int): List<Artist>
}

object MusicBrainzCatalog : OpenCatalog {
    override suspend fun search(query: String, limit: Int) = MusicBrainz.search(query, limit)
    override suspend fun album(id: String) = MusicBrainz.album(id)
    override suspend fun artistPage(id: String) = MusicBrainz.artistPage(id)
    override suspend fun searchArtists(query: String, limit: Int) = MusicBrainz.searchArtists(query, limit)
    override suspend fun searchAlbums(query: String, limit: Int) = MusicBrainz.searchAlbums(query, limit)
    override suspend fun trendingAlbums(range: String, limit: Int) = MusicBrainz.trendingAlbums(range, limit)
    override suspend fun trendingArtists(range: String, limit: Int) = MusicBrainz.trendingArtists(range, limit)
    override suspend fun freshReleases(days: Int, limit: Int) = MusicBrainz.freshReleases(days, limit)
    override suspend fun similarArtists(artistId: String, limit: Int) = MusicBrainz.similarArtists(artistId, limit)
}

/** Titlebar tag (C++ sourceLabel): "SPOTIFY · <name>" or "AÇIK KATALOG · MUSICBRAINZ"; the UI formats it. */
sealed interface SourceLabel {
    /** [name] = display name, else the username; "" = just "SPOTIFY". */
    data class Spotify(val name: String) : SourceLabel
    data object OpenCatalog : SourceLabel
}

/**
 * The catalog "source" seam (port of app/Source). ShadeTube shows Spotify data when the user is logged in and
 * MusicBrainz's open catalog otherwise. Pages don't branch on this themselves: they capture the active Spotify account
 * when they start loading ([activeApi]) and call these dispatchers. Detail lookups route by id — a "spotify:*" URI
 * goes to Spotify, an MBID goes to MusicBrainz — so mixed histories keep working.
 */
class CatalogSource(
    private val account: SpotifyAccount,
    private val open: OpenCatalog = MusicBrainzCatalog,
) {
    /** A Spotify session is active. */
    val loggedIn: Boolean get() = account.loggedIn

    /** The logged-in account, or null; capture this before starting a load (like the C++ Api* captured on the UI thread). */
    fun activeApi(): SpotifyAccount? = if (account.loggedIn) account else null

    fun sourceLabel(): SourceLabel {
        if (!account.loggedIn) return SourceLabel.OpenCatalog
        val p = account.profile.value
        return SourceLabel.Spotify(p.name.ifEmpty { p.username })
    }

    /** `api` = the value captured by the page (null = logged out => MusicBrainz). */
    suspend fun search(api: SpotifyAccount?, query: String, limit: Int = 12): SearchResults =
        api?.search(query, limit) ?: open.search(query, limit)

    suspend fun album(api: SpotifyAccount?, id: String): Album =
        if (api != null && isSpotifyId(id)) api.album(id) else open.album(id)

    suspend fun artistPage(api: SpotifyAccount?, id: String): ArtistPage =
        if (api != null && isSpotifyId(id)) api.artistPage(id) else open.artistPage(id)

    suspend fun search(query: String, limit: Int = 12) = search(activeApi(), query, limit)
    suspend fun album(id: String) = album(activeApi(), id)
    suspend fun artistPage(id: String) = artistPage(activeApi(), id)

    /** The open catalog itself (tag search, trending shelves). */
    val openCatalog: OpenCatalog get() = open

    companion object {
        /** True if this id belongs to Spotify (a "spotify:" URI) rather than a MusicBrainz MBID. */
        fun isSpotifyId(id: String): Boolean = id.startsWith("spotify:")
    }
}
