package com.app.shadetube.app

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.catalog.Page
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isLocalId
import com.app.shadetube.core.HttpError
import com.app.shadetube.core.Log
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.Text
import com.app.shadetube.downloads.DownloadManager
import com.app.shadetube.downloads.LocalCollections
import com.app.shadetube.downloads.LocalPlaylist
import com.app.shadetube.downloads.SponsorCutSource
import com.app.shadetube.downloads.SyncBlocklist
import com.app.shadetube.library.AddToPlaylistResult
import com.app.shadetube.library.CollectionEnhancer
import com.app.shadetube.library.LikeResult
import com.app.shadetube.library.PlayerControls
import com.app.shadetube.library.PlaylistActions
import com.app.shadetube.library.UserLibrary
import com.app.shadetube.links.LinkLookups
import com.app.shadetube.links.YouTubeVideoInfo
import com.app.shadetube.localfiles.LocalFile
import com.app.shadetube.localfiles.LocalFileOpener
import com.app.shadetube.localfiles.LocalFileResolver
import com.app.shadetube.localfiles.ReplayGain
import com.app.shadetube.lyrics.SpotifyLyricsSource
import com.app.shadetube.musicbrainz.MusicBrainz
import com.app.shadetube.playback.LocalSource
import com.app.shadetube.playback.LocalSourceResolver
import com.app.shadetube.playback.PlayContext
import com.app.shadetube.playback.Player
import com.app.shadetube.playback.ReplayGainReader
import com.app.shadetube.playback.Status
import com.app.shadetube.playback.features.Blacklist
import com.app.shadetube.playback.features.CollectionWriter
import com.app.shadetube.playback.features.RadioSource
import com.app.shadetube.playback.features.RadioSourceError
import com.app.shadetube.playback.features.Recommender
import com.app.shadetube.playback.features.SmartShuffle
import com.app.shadetube.playback.features.SponsorBlock
import com.app.shadetube.playback.service.BrowseSource
import com.app.shadetube.playback.service.LikeHandler
import com.app.shadetube.playlistio.AlbumLoader
import com.app.shadetube.playlistio.DownloadedFiles
import com.app.shadetube.playlistio.LocalPlaylistTarget
import com.app.shadetube.playlistio.MusicBrainzTransfer
import com.app.shadetube.playlistio.SpotifyTransfer
import com.app.shadetube.playlistio.VideoInfo
import com.app.shadetube.playlistio.YouTubePlaylist
import com.app.shadetube.playlistio.YouTubeTransfer
import com.app.shadetube.spotify.ApiError
import com.app.shadetube.spotify.Session
import com.app.shadetube.spotify.SessionState
import com.app.shadetube.stats.LocalTracksSource
import com.app.shadetube.stats.PlayerPhase
import com.app.shadetube.stats.PlayerSample
import com.app.shadetube.stats.RateLimitedException
import com.app.shadetube.stats.SmartListsSources
import com.app.shadetube.stats.SmartListsSpotify
import com.app.shadetube.stats.SmartMix
import com.app.shadetube.stats.SmartMixRecommender
import com.app.shadetube.stats.StatsArtistImages
import com.app.shadetube.stats.StatsCatalogSearch
import com.app.shadetube.youtube.MatchService
import com.app.shadetube.youtube.explode.PlaylistId
import com.app.shadetube.youtube.explode.VideoId
import com.app.shadetube.youtube.explode.YoutubeClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import com.app.shadetube.catalog.ArtistRef as CatalogArtistRef
import com.app.shadetube.playlistio.CreatedPlaylist as TransferCreatedPlaylist

/*
 * Adapters from the packages' small interfaces to the real services (the composition root's glue). Each one is a thin
 * translation: no logic of its own beyond mapping errors and ids. AppGraph constructs them.
 */

private const val TAG = "app"

// ---- Spotify ---------------------------------------------------------------------------------------------------------

/** Spotify lyrics for the lyrics module: null logged out; HTTP failures as core [HttpError] (429 / 403 pause logic). */
class SessionLyricsSource(private val session: Session) : SpotifyLyricsSource {
    override suspend fun fetch(trackUri: String): String? {
        if (!session.loggedIn) return null
        return try {
            session.api.trackLyrics(trackUri)
        } catch (e: ApiError) {
            if (e.status > 0) throw HttpError(e.status, "", e.message ?: "Spotify lyrics HTTP ${e.status}") else throw e
        }
    }
}

/** Spotify radios for the player (song radio / endless playback). */
class SessionRadioSource(private val session: Session) : RadioSource {
    override val available: Boolean get() = session.loggedIn

    override suspend fun radioPlaylist(seedUri: String): String = mapApiError { session.api.radioPlaylist(seedUri) }

    override suspend fun playlistTracks(uri: String, offset: Int, limit: Int): List<Track> =
        mapApiError { session.api.playlistTracks(uri, offset, limit).page.items }

    private inline fun <T> mapApiError(block: () -> T): T = try {
        block()
    } catch (e: ApiError) {
        throw RadioSourceError(e.status, e.message ?: "Spotify HTTP ${e.status}")
    }
}

/** Spotify radios + Liked Songs for stats (recommendations, smart lists); a 429 becomes [RateLimitedException]. */
class SessionStatsSpotify(private val session: Session) : SmartListsSpotify {
    override suspend fun radioPlaylist(seedTrackUri: String): String = rateLimited { session.api.radioPlaylist(seedTrackUri) }

    override suspend fun playlistTracks(uri: String, offset: Int, limit: Int): List<Track> =
        rateLimited { session.api.playlistTracks(uri, offset, limit).page.items }

    override suspend fun likedPage(offset: Int, limit: Int): Page<Track> =
        rateLimited { session.api.playlistTracks(LIKED_SONGS_URI, offset, limit).page }

    private inline fun <T> rateLimited(block: () -> T): T = try {
        block()
    } catch (e: ApiError) {
        if (e.status == 429) throw RateLimitedException("Spotify 429: ${e.message}") else throw e
    }
}

/** What the smart lists read from the session and the local library. */
class AppSmartListsSources(private val session: Session, private val library: UserLibrary) : SmartListsSources {
    private val spotify = SessionStatsSpotify(session)
    override fun currentUser(): String = if (session.loggedIn) session.api.username().ifEmpty { session.profile.value.username } else ""
    override fun sessionConnecting(): Boolean = session.state.value == SessionState.Connecting
    override fun spotify(): SmartListsSpotify? = if (session.loggedIn) spotify else null
    override fun localLiked(): List<Track> = library.liked
}

/** Artist pictures for the stats page: followed artists of the library, Spotify's artist overview. */
class AppStatsArtistImages(private val session: Session, private val library: UserLibrary) : StatsArtistImages {
    override fun libraryImages(artist: CatalogArtistRef): List<Image>? {
        val list = if (session.loggedIn) session.library.value.artists else library.artists
        val hit = if (artist.id.isNotEmpty()) list.firstOrNull { it.id == artist.id }
        else list.firstOrNull { Text.fold(it.name) == Text.fold(artist.name) }
        return hit?.images?.takeIf { it.isNotEmpty() }
    }

    override suspend fun spotifyArtistImages(uri: String): List<Image> = session.api.artistPage(uri).artist.images
}

/** The stats artwork's catalog search: the logged-in session's search, null otherwise. */
fun statsCatalogSearch(session: Session): () -> StatsCatalogSearch? {
    val search = StatsCatalogSearch { q, limit -> session.api.search(q, limit) }
    return { if (session.loggedIn) search else null }
}

// ---- Links --------------------------------------------------------------------------------------------------------------

class AppLinkLookups(private val session: Session, private val youtube: () -> YoutubeClient) : LinkLookups {
    override val spotifyLoggedIn: Boolean get() = session.loggedIn
    override suspend fun spotifyTrack(uri: String): Track = session.api.track(uri)
    override suspend fun spotifyAlbum(albumId: String): Album = session.api.album(albumId)
    override suspend fun mbRecording(recordingId: String): Track = MusicBrainz.recording(recordingId)
    override suspend fun mbAlbum(releaseGroupId: String): Album = MusicBrainz.album(releaseGroupId)
    override suspend fun mbReleaseGroupOfRelease(releaseId: String): String = MusicBrainz.releaseGroupOfRelease(releaseId)
    override suspend fun youtubeVideo(videoId: String): YouTubeVideoInfo {
        val v = youtube().videos.get(VideoId.parse(videoId))
        return YouTubeVideoInfo(
            title = v.title,
            channel = v.author.channelTitle,
            durationMs = v.duration,
            isLive = v.isLive,
            thumbnails = v.thumbnails.map { Image(it.url, it.resolution.width, it.resolution.height) },
        )
    }
}

// ---- Player <-> library ------------------------------------------------------------------------------------------------

/** The library pages' view of the player. */
class AppPlayerControls(private val player: () -> Player) : PlayerControls {
    override val contextUri: String get() = player().playContext.uri
    override val isPlaying: Boolean get() = player().isPlaying
    override val isPaused: Boolean get() = player().statusNow == Status.Paused
    override fun togglePause() = player().togglePause()
    override fun play() = player().play()
    override fun setShuffle(on: Boolean) = player().setShuffle(on)
    override fun playContext(tracks: List<Track>, index: Int, contextUri: String, contextName: String) =
        player().playContext(tracks, index, PlayContext(contextUri, contextName))

    override fun enqueue(tracks: List<Track>) = player().enqueue(tracks)
}

/** "Geliştir" for the collection pages, over playback's smart shuffle and the stats recommender. */
class AppCollectionEnhancer(
    private val smartShuffle: () -> SmartShuffle,
    private val recommender: SmartMixRecommender,
) : CollectionEnhancer {
    override val changes: Flow<Unit> = merge(
        SettingsStore.flow.map { it.enhancedCollections }.distinctUntilChanged().drop(1).map { },
        recommender.version.drop(1).map { },
    )

    override fun isEnhanced(key: String): Boolean = smartShuffle().enhanced(key)
    override fun setEnhanced(key: String, on: Boolean) = smartShuffle().setEnhanced(key, on)
    override suspend fun recommend(key: String, own: List<Track>): List<Track> = smartShuffle().recommend(key, own)

    override fun prune(key: String, own: List<Track>, recs: List<Track>): List<Track> {
        val excl = SmartMix.Exclusions()
        own.forEach { excl.add(it) }
        val s = smartShuffle()
        return recs.filter { it !in excl && !s.isHidden(key, it) }
    }

    override fun interleave(key: String, own: List<Track>, recs: List<Track>): List<Track> =
        SmartMix.interleave(own, recs, SmartMix.hash(key))

    override fun add(key: String, track: Track) = smartShuffle().add(key, track)
    override fun hide(key: String, track: Track) {
        smartShuffle().hide(key, track)
    }
}

/** Smart shuffle's recommendations (stats.SmartMixRecommender). */
class AppRecommender(private val recommender: SmartMixRecommender) : Recommender {
    override suspend fun recommend(key: String, collection: List<Track>): List<Track> = recommender.recommend(key, collection)
    override fun hide(key: String, t: Track) = recommender.hide(key, t)
    override fun isHidden(key: String, t: Track): Boolean = recommender.isHidden(key, t)
}

/** "+" on a recommendation: Liked Songs ("liked") or a playlist (Spotify while logged in, else local). */
class AppCollectionWriter(
    private val session: Session,
    private val library: UserLibrary,
    private val actions: PlaylistActions,
) : CollectionWriter {
    override fun canAdd(key: String, t: Track): Boolean {
        if (key == "liked") return library.canLike(t.id) && !library.isLiked(t.id)
        if (session.loggedIn) return actions.isEditableSpotifyPlaylist(key) && t.id.startsWith("spotify:track:")
        val tracks = library.playlistTracks(key) ?: return false
        return t.id.isNotEmpty() && tracks.none { it.id == t.id }
    }

    override suspend fun add(key: String, t: Track): Int {
        if (key == "liked") return if (library.setLiked(t, true) == LikeResult.Liked) 1 else 0
        return when (val r = actions.addToPlaylist(key, listOf(t))) {
            is AddToPlaylistResult.Added -> r.added
            else -> 0
        }
    }
}

/** The notification / Android Auto heart: the library's likes (Spotify while logged in). */
class AppLikeHandler(private val library: UserLibrary) : LikeHandler {
    override fun isLiked(track: Track): Boolean = library.isLiked(track.id)
    override fun canLike(track: Track): Boolean = library.canLike(track.id)
    override fun setLiked(track: Track, liked: Boolean) {
        library.setLiked(track, liked)
    }
}

/** Android Auto's browse tree: history and playlists (Spotify's while logged in, else the local ones). */
class AppBrowseSource(
    private val session: Session,
    private val library: UserLibrary,
    private val likedName: () -> String,
) : BrowseSource {
    override suspend fun recentlyPlayed(limit: Int): List<Track> =
        library.history.value.map { it.track }.distinctBy { it.id }.take(limit)

    override suspend fun playlists(): List<Playlist> =
        if (session.loggedIn) session.library.value.playlists
        else listOf(Playlist(id = LOCAL_LIKED, name = likedName(), totalTracks = library.liked.size)) + library.playlists

    override suspend fun playlistTracks(playlistId: String): List<Track> = when {
        playlistId == LOCAL_LIKED -> library.liked
        playlistId.startsWith("spotify:") -> {
            val out = ArrayList<Track>()
            var off = 0
            while (out.size < MAX_TRACKS) {
                val pg = session.api.playlistTracks(playlistId, off, 100).page
                out.addAll(pg.items)
                if (!pg.hasMore || pg.next <= off) break
                off = pg.next
            }
            out
        }
        else -> library.playlistTracks(playlistId) ?: emptyList()
    }

    companion object {
        const val LOCAL_LIKED = "liked"
        private const val MAX_TRACKS = 500
    }
}

// ---- Offline sources ---------------------------------------------------------------------------------------------------

/** Finished downloads play from disk. */
class DownloadsLocalSource(private val downloads: () -> DownloadManager?) : LocalSourceResolver {
    override fun localSourceFor(trackId: String): LocalSource? = downloads()?.localFileFor(trackId)?.let { LocalSource.of(it) }
}

/** "local:" tracks (local library + opened files); null makes the player report an error (never a YouTube search). */
class LocalFilesSource(private val resolver: LocalFileResolver) : LocalSourceResolver {
    override fun localSourceFor(trackId: String): LocalSource? {
        if (!isLocalId(trackId)) return null
        val f = resolver.fileFor(trackId) ?: return null
        return LocalSource(f.uri, f.mimeType, f.filePath?.let { File(it) })
    }
}

/** ReplayGain of local files and downloads (content Uris through the platform opener). */
class AppReplayGain(private val opener: LocalFileOpener) : ReplayGainReader {
    override fun trackGainDb(source: LocalSource): Float? {
        source.file?.let { return ReplayGain.readTrackGainDb(it) }
        if (!source.uri.startsWith("content://")) return null
        return opener.replayGainDb(LocalFile(trackId = "", path = source.uri, uri = source.uri, name = "", mimeType = source.mimeType))
    }
}

// ---- Downloads ---------------------------------------------------------------------------------------------------------

/** SponsorBlock segments to cut out of a download; empty on any failure (the download is just not trimmed). */
class AppSponsorCuts(private val categories: () -> List<String>) : SponsorCutSource {
    override suspend fun cuts(videoId: String, durationMs: Long): List<Pair<Long, Long>> = try {
        SponsorBlock.normalize(SponsorBlock.fetchSegments(videoId, categories()), durationMs).map { it.startMs to it.endMs }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "SponsorBlock lookup for a download failed: ${e.message}")
        emptyList()
    }
}

/** The local library's Liked Songs and playlists for download sync. */
class LibraryCollections(private val library: UserLibrary) : LocalCollections {
    override fun liked(): List<Track> = library.liked
    override fun playlist(id: String): LocalPlaylist? {
        val p = library.playlist(id) ?: return null
        return LocalPlaylist(p.name, p.images, library.playlistTracks(id) ?: emptyList())
    }

    override val changes: Flow<Unit> = library.revision.drop(1).map { }
}

class BlacklistSyncBlocklist(private val blacklist: () -> Blacklist) : SyncBlocklist {
    override fun isBlocked(t: Track): Boolean = blacklist().isBlocked(t)
    override fun isTrackBlocked(trackId: String): Boolean = blacklist().isTrackBlocked(trackId)
}

// ---- Playlist import / export ----------------------------------------------------------------------------------------

class SessionTransfer(private val session: Session) : SpotifyTransfer {
    override val loggedIn: Boolean get() = session.loggedIn
    override suspend fun playlistTracks(uri: String, offset: Int, limit: Int): Page<Track> =
        session.api.playlistTracks(uri, offset, limit).page

    override suspend fun track(uri: String): Track = session.api.track(uri)

    override suspend fun createPlaylist(name: String, trackUris: List<String>): TransferCreatedPlaylist? {
        val c = session.createPlaylist(name, trackUris)
        return if (c.uri.isEmpty()) null else TransferCreatedPlaylist(c.uri, c.added)
    }

    override suspend fun addToPlaylist(playlistUri: String, trackUris: List<String>): Boolean =
        session.addToPlaylist(playlistUri, trackUris)
}

class AppYouTubeTransfer(private val match: MatchService) : YouTubeTransfer {
    override suspend fun video(videoId: String): VideoInfo {
        val v = match.client.videos.get(VideoId.parse(videoId))
        return VideoInfo(v.id.value, v.title, v.author.channelTitle, (v.duration ?: 0L).toInt())
    }

    override suspend fun playlist(playlistId: String, maxVideos: Int): YouTubePlaylist {
        val id = PlaylistId.parse(playlistId)
        val meta = match.client.playlists.get(id)
        val videos = ArrayList<VideoInfo>()
        match.client.playlists.getVideoBatches(id) { batch ->
            for (v in batch) {
                if (videos.size >= maxVideos) break
                videos.add(VideoInfo(v.id.value, v.title, v.author.channelTitle, (v.duration ?: 0L).toInt()))
            }
            videos.size < maxVideos
        }
        return YouTubePlaylist(meta.title, videos)
    }

    override fun cachedVideoId(trackId: String): String? = match.cachedMatchNow(trackId)?.videoId
}

val musicBrainzTransfer = MusicBrainzTransfer { MusicBrainz.recording(it) }

fun albumLoader(album: suspend (String) -> Album) = AlbumLoader { album(it) }

fun downloadedFiles(downloads: () -> DownloadManager?) = DownloadedFiles { downloads()?.localFileFor(it)?.absolutePath }

class LibraryPlaylistTarget(private val library: UserLibrary) : LocalPlaylistTarget {
    override fun createPlaylist(name: String): String = library.createPlaylist(name)
    override fun addToPlaylist(playlistId: String, tracks: List<Track>): Int = library.addToPlaylist(playlistId, tracks)
}

// ---- Stats probe ---------------------------------------------------------------------------------------------------------

/** One player sample for the listening statistics (main thread). */
fun samplePlayer(p: Player): PlayerSample {
    val phase = when (p.statusNow) {
        Status.Idle, Status.Error -> PlayerPhase.Idle
        Status.Resolving -> PlayerPhase.Resolving
        Status.Playing -> PlayerPhase.Playing
        Status.Buffering, Status.Paused -> PlayerPhase.Stalled
    }
    return PlayerSample(p.currentTrack, phase, p.positionMs(), p.durationMs())
}

/** Local-library songs for recommendations. */
fun localTracksSource(tracks: () -> List<Track>) = LocalTracksSource { tracks() }
