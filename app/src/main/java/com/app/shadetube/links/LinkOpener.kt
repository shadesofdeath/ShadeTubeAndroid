package com.app.shadetube.links

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.YOUTUBE_ID_PREFIX
import com.app.shadetube.core.Log
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/**
 * What opening a pasted link does (port of the decision logic of app/LinkOpener; callers navigate / play / toast):
 *  - Spotify / MusicBrainz album, playlist, artist -> that page (Spotify needs a session: logged out it asks to
 *    connect);
 *  - Spotify track / MusicBrainz recording -> plays in its album (from that song) and shows the album; alone when the
 *    album can't be loaded;
 *  - YouTube video -> plays as a single song pinned to exactly that video (never re-matched), titled from the video
 *    ("Artist - Title");
 *  - YouTube playlist -> imported as a local playlist (playlistio);
 *  - Unsupported -> a message; nothing is searched.
 */
sealed interface LinkAction {
    /** Not a link: search for the text as usual. */
    data object Search : LinkAction

    /** A link of a known service to something ShadeTube can't open (podcast, user, channel, short link...). */
    data object Unsupported : LinkAction

    /** A Spotify link while logged out: show the connect screen / ask to connect. */
    data object NeedsSpotifyLogin : LinkAction

    /** Navigate to an album page ("spotify:album:<id>" or a release group MBID). */
    data class OpenAlbum(val albumId: String) : LinkAction

    /** Navigate to a playlist page ("spotify:playlist:<id>"). */
    data class OpenPlaylist(val playlistId: String) : LinkAction

    /** Navigate to an artist page ("spotify:artist:<id>" or an artist MBID). */
    data class OpenArtist(val artistId: String) : LinkAction

    /** Import a YouTube playlist as a local playlist. */
    data class ImportYouTubePlaylist(val listId: String) : LinkAction

    /**
     * Play [tracks] from [index] as the album [album] (context "album:<id>"); when the song isn't in the album,
     * [tracks] is just the song (index 0, [album] null: context "link"). Then show the album [navigateAlbumId]
     * ("" = none).
     */
    data class PlayInAlbum(
        val track: Track,
        val album: Album?,
        val tracks: List<Track>,
        val index: Int,
        val navigateAlbumId: String,
    ) : LinkAction

    /**
     * Play [track] ("yt:<videoId>") alone (context "YouTube link") after pinning [pin] in the MatchService for
     * [Track.id], so the player, "wrong match?" and downloads use exactly this upload.
     */
    data class PlayYouTubeVideo(val track: Track, val pin: PinnedVideo) : LinkAction

    /** A lookup failed: the caller shows the message for [reason]. */
    data class Failed(val reason: FailReason) : LinkAction

    enum class FailReason {
        /** "Bu şarkı Spotify'da bulunamadı". */
        SpotifyTrackNotFound,
        /** "Bu kayıt MusicBrainz'de bulunamadı". */
        MbRecordingNotFound,
        /** "Bu albüm MusicBrainz'de bulunamadı". */
        MbReleaseNotFound,
        /** "Bu YouTube videosu açılamadı". */
        YouTubeVideoFailed,
        /** "Canlı yayınlar çalınamaz". */
        LiveStream,
    }
}

/** The match to pin for a YouTube link (fields of youtube's Match). */
data class PinnedVideo(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationSec: Int,
    val thumbnailUrl: String,
    val score: Int = 100,
    val manual: Boolean = true,
)

/** What [LinkOpener] needs to know about a YouTube video (YoutubeExplode's video metadata). */
data class YouTubeVideoInfo(
    val title: String,
    val channel: String,
    /** null = unknown (live). */
    val durationMs: Long?,
    val isLive: Boolean,
    /** Thumbnails with their resolution. */
    val thumbnails: List<Image>,
)

/**
 * Lookups the opener needs from sibling packages; AppGraph wires them (spotify session, MusicBrainz, YoutubeExplode).
 * Each throws on failure (not found, network).
 */
interface LinkLookups {
    /** Is a Spotify session there? */
    val spotifyLoggedIn: Boolean

    /** `Api::track` ("spotify:track:<id>"): the spclient metadata/4 track. */
    suspend fun spotifyTrack(uri: String): Track

    /** `Api::album` with its tracks. */
    suspend fun spotifyAlbum(albumId: String): Album

    /** `MusicBrainz.recording`. */
    suspend fun mbRecording(recordingId: String): Track

    /** `MusicBrainz.album` (release group + tracklist). */
    suspend fun mbAlbum(releaseGroupId: String): Album

    /** `MusicBrainz.releaseGroupOfRelease` ("" when none). */
    suspend fun mbReleaseGroupOfRelease(releaseId: String): String

    /** YoutubeExplode `videos().get(id)`. */
    suspend fun youtubeVideo(videoId: String): YouTubeVideoInfo
}

/** What the hint row shows for a link; the UI maps these to strings / icons. */
enum class LinkVerb { PlaySong, PlayVideoAsSong, ImportPlaylist, OpenAlbum, OpenPlaylist, OpenArtist }

enum class LinkIcon { SpotifyLink, YouTubeSource, Link }

object LinkOpener {
    /** What opening it does ("Şarkıyı çal", "Albümü aç"...); null for None / Unsupported (the C++ linkAction). */
    fun verb(link: Link): LinkVerb? = when (link.kind) {
        LinkKind.SpotifyTrack, LinkKind.MbRecording -> LinkVerb.PlaySong
        LinkKind.YouTubeVideo -> LinkVerb.PlayVideoAsSong
        LinkKind.YouTubePlaylist -> LinkVerb.ImportPlaylist
        LinkKind.SpotifyAlbum, LinkKind.MbReleaseGroup, LinkKind.MbRelease -> LinkVerb.OpenAlbum
        LinkKind.SpotifyPlaylist -> LinkVerb.OpenPlaylist
        LinkKind.SpotifyArtist, LinkKind.MbArtist -> LinkVerb.OpenArtist
        LinkKind.None, LinkKind.Unsupported -> null
    }

    /** Icon for the hint row (the C++ linkIcon: "spotify-link", "youtube-source", "link"). */
    fun icon(link: Link): LinkIcon = when (link.service) {
        LinkService.Spotify -> LinkIcon.SpotifyLink
        LinkService.YouTube -> LinkIcon.YouTubeSource
        else -> LinkIcon.Link
    }

    /** True when opening needs a Spotify session that isn't there. */
    fun needsSpotify(link: Link, spotifyLoggedIn: Boolean): Boolean =
        link.service == LinkService.Spotify && link.kind != LinkKind.Unsupported && !spotifyLoggedIn

    /**
     * The decision without lookups: everything but Spotify tracks, MusicBrainz releases / recordings and YouTube
     * videos is final here; those come back as null (use [open]).
     */
    fun decide(link: Link, spotifyLoggedIn: Boolean): LinkAction? = when {
        link.kind == LinkKind.None -> LinkAction.Search
        link.kind == LinkKind.Unsupported -> LinkAction.Unsupported
        needsSpotify(link, spotifyLoggedIn) -> LinkAction.NeedsSpotifyLogin
        else -> when (link.kind) {
            LinkKind.SpotifyAlbum -> LinkAction.OpenAlbum(link.spotifyUri)
            LinkKind.SpotifyPlaylist -> LinkAction.OpenPlaylist(link.spotifyUri)
            LinkKind.SpotifyArtist -> LinkAction.OpenArtist(link.spotifyUri)
            LinkKind.YouTubePlaylist -> LinkAction.ImportYouTubePlaylist(link.id)
            LinkKind.MbReleaseGroup -> LinkAction.OpenAlbum(link.id)
            LinkKind.MbArtist -> LinkAction.OpenArtist(link.id)
            else -> null
        }
    }

    /**
     * Opens [link]: the final [LinkAction] after any lookups (main-safe: the lookups are suspend). A newer link
     * supersedes one still being looked up: the caller cancels the coroutine of the previous call.
     */
    suspend fun open(link: Link, lookups: LinkLookups): LinkAction {
        Log.i("links", "open kind ${link.kind} id ${link.id}")
        decide(link, lookups.spotifyLoggedIn)?.let { return it }
        return when (link.kind) {
            LinkKind.SpotifyTrack -> openTrack(LinkAction.FailReason.SpotifyTrackNotFound, link.spotifyUri,
                { lookups.spotifyTrack(it) }, { lookups.spotifyAlbum(it) })
            LinkKind.MbRecording -> openTrack(LinkAction.FailReason.MbRecordingNotFound, link.id,
                { lookups.mbRecording(it) }, { lookups.mbAlbum(it) })
            LinkKind.MbRelease -> {
                val rg = guard("release ${link.id} has no release group") { lookups.mbReleaseGroupOfRelease(link.id) }
                if (rg.isNullOrEmpty()) LinkAction.Failed(LinkAction.FailReason.MbReleaseNotFound) else LinkAction.OpenAlbum(rg)
            }
            LinkKind.YouTubeVideo -> {
                val info = guard("YouTube video ${link.id} not opened") { lookups.youtubeVideo(link.id) }
                    ?: return LinkAction.Failed(LinkAction.FailReason.YouTubeVideoFailed)
                if (info.isLive) LinkAction.Failed(LinkAction.FailReason.LiveStream) else youtubeVideoAction(link.id, info)
            }
            else -> LinkAction.Search
        }
    }

    private suspend inline fun <T> guard(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("links", "$what: ${e.message}")
        null
    }

    private suspend inline fun openTrack(
        notFound: LinkAction.FailReason,
        id: String,
        track: (String) -> Track,
        album: (String) -> Album,
    ): LinkAction {
        val t = guard("$id not opened") { track(id) } ?: return LinkAction.Failed(notFound)
        val a = if (t.album.id.isNotEmpty()) guard("album of $id not loaded") { album(t.album.id) } else null
        return playInAlbum(t, a)
    }

    /** Plays the track in its album (from that song) or alone when it isn't in it; shows the album. */
    fun playInAlbum(track: Track, album: Album?): LinkAction.PlayInAlbum {
        val index = album?.tracks?.indexOfLast { it.id == track.id } ?: -1
        val albumId = album?.id?.takeIf { it.isNotEmpty() } ?: track.album.id
        return if (index >= 0) LinkAction.PlayInAlbum(track, album, album!!.tracks, index, albumId)
        else LinkAction.PlayInAlbum(track, null, listOf(track), 0, albumId)
    }

    /**
     * The video as a song: title / artist from the video ([Links.videoSong]), its 16:9 thumbnails as the cover (a
     * letterboxed 4:3 thumbnail would show its black bars in the square cover; else mqdefault), and the video pinned as
     * the match.
     */
    fun youtubeVideoAction(videoId: String, info: YouTubeVideoInfo): LinkAction.PlayYouTubeVideo {
        val song = Links.videoSong(info.title, info.channel)
        val durationMs = (info.durationMs ?: 0L).toInt()
        val images = info.thumbnails.filter { it.height > 0 && abs(it.width.toDouble() / it.height - 16.0 / 9.0) <= 0.1 }
            .ifEmpty { listOf(Image("https://i.ytimg.com/vi/$videoId/mqdefault.jpg", 320, 180)) }
        val track = Track(
            id = YOUTUBE_ID_PREFIX + videoId,
            videoId = videoId,
            name = song.title,
            artists = listOf(ArtistRef("", song.artist)),
            durationMs = durationMs,
            album = com.app.shadetube.catalog.AlbumRef(images = images),
        )
        val pin = PinnedVideo(
            videoId = videoId,
            title = info.title,
            channel = info.channel,
            durationSec = durationMs / 1000,
            thumbnailUrl = images.first().url,
        )
        return LinkAction.PlayYouTubeVideo(track, pin)
    }
}
