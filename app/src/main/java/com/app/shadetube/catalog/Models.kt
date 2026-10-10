package com.app.shadetube.catalog

/**
 * Source-independent catalog models shared by every module (port of catalog/Models.h).
 *
 * Ids: Spotify URIs ("spotify:track:<id>", "spotify:album:<id>", "spotify:artist:<id>", "spotify:playlist:<id>",
 * Liked Songs = "spotify:collection:tracks"), MusicBrainz MBIDs (UUID strings), local files "local:<hex>",
 * local playlists "local:<hex>", podcast episodes "podcast:<...>", radio stations (see InternetRadio), YouTube-only
 * songs "yt:<videoId>", imported history rows "import:<hash>".
 *
 * Audio always comes from YouTube (or disk): a Track may carry a cached videoId; otherwise the MatchService finds one
 * from title / artists / duration.
 */
data class Image(
    val url: String,
    /** 0 = unknown. */
    val width: Int = 0,
    val height: Int = 0,
)

/** Smallest image whose width >= [minWidth], else the largest; unknown widths count as "good enough". */
fun List<Image>.pick(minWidth: Int): Image? {
    var best: Image? = null
    var largest: Image? = null
    for (img in this) {
        if (img.url.isEmpty()) continue
        if (largest == null || img.width > largest.width) largest = img
        if (img.width >= minWidth && (best == null || img.width < best.width)) best = img
    }
    return best ?: largest
}

/** Cover Art Archive front cover of a release group in the standard thumbnail sizes. */
fun coverArt(releaseGroupId: String): List<Image> {
    if (releaseGroupId.isEmpty()) return emptyList()
    val base = "https://coverartarchive.org/release-group/$releaseGroupId/front-"
    return listOf(Image(base + "250", 250, 250), Image(base + "500", 500, 500), Image(base + "1200", 1200, 1200))
}

data class ArtistRef(
    /** May be empty for plain-text credits. */
    val id: String = "",
    val name: String = "",
)

data class AlbumRef(
    val id: String = "",
    val name: String = "",
    val images: List<Image> = emptyList(),
)

data class Track(
    val id: String = "",
    /** Cached YouTube video; empty -> MatchService resolves. */
    val videoId: String = "",
    val name: String = "",
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef = AlbumRef(),
    val durationMs: Int = 0,
    val explicitContent: Boolean = false,
    val playable: Boolean = true,
    /** Unix seconds (library / local playlists), 0 = unknown. */
    val addedAt: Long = 0,
    val trackNumber: Int = 0,
    /** ListenBrainz popularity (0 = unknown). */
    val listenCount: Long = 0,
    /** Spotify playlist row uid (needed to remove that row); not persisted. */
    val uid: String = "",
    /** Mixed in by smart shuffle / "Geliştir"; not part of the list it plays with. Not persisted by catalogs. */
    val recommended: Boolean = false,
) {
    /** "Artist A, Artist B". */
    val artistLine: String get() = artists.filter { it.name.isNotEmpty() }.joinToString(", ") { it.name }
    val isPodcastEpisode: Boolean get() = isPodcastId(id)
}

data class Artist(
    val id: String = "",
    val name: String = "",
    /** "Person" | "Group" | ... */
    val type: String = "",
    /** ISO code, e.g. "TR". */
    val country: String = "",
    val disambiguation: String = "",
    val beginYear: String = "",
    val tags: List<String> = emptyList(),
    val images: List<Image> = emptyList(),
    val listenCount: Long = 0,
)

data class Album(
    val id: String = "",
    /** MusicBrainz: representative release used for the tracklist. */
    val releaseId: String = "",
    val name: String = "",
    /** "Album" | "Single" | "EP" | "Broadcast" | "Other" (Spotify: "ALBUM" / "SINGLE" / "EP" / "COMPILATION"). */
    val primaryType: String = "",
    val secondaryTypes: List<String> = emptyList(),
    val artists: List<ArtistRef> = emptyList(),
    val images: List<Image> = emptyList(),
    /** "1997", "1997-06", "1997-06-14". */
    val firstReleaseDate: String = "",
    val label: String = "",
    val totalTracks: Int = 0,
    val tracks: List<Track> = emptyList(),
) {
    val year: String get() = firstReleaseDate.take(4)
    fun ref() = AlbumRef(id, name, images)
}

data class Playlist(
    /** "local:<hex>" or "spotify:playlist:<id>" (Liked Songs: [LIKED_SONGS_URI]). */
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val images: List<Image> = emptyList(),
    val totalTracks: Int = 0,
    val createdAt: Long = 0,
    /** false when the source gave no track count (hide it instead of showing 0). */
    val countKnown: Boolean = true,
    val ownerId: String = "",
    val ownerName: String = "",
    /** Spotify: the logged-in user may add / remove items (owner or collaborator). */
    val editable: Boolean = false,
    /** Spotify: owned by the logged-in user (rename / delete allowed). */
    val owned: Boolean = false,
)

/** A tappable item in a shelf (home, artist page, search). */
data class CardItem(
    val kind: Kind = Kind.Album,
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val images: List<Image> = emptyList(),
    /** When kind == Track. */
    val track: Track? = null,
) {
    enum class Kind { Track, Album, Artist, Playlist }
}

data class Shelf(
    val title: String = "",
    /** Small mono label, e.g. "LISTENBRAINZ · BU HAFTA". */
    val label: String = "",
    val items: List<CardItem> = emptyList(),
)

data class ArtistPage(
    val artist: Artist = Artist(),
    val topTracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    /** Singles + EPs. */
    val singles: List<Album> = emptyList(),
    /** Compilations, live, appears-on... */
    val other: List<Album> = emptyList(),
    val similar: List<Artist> = emptyList(),
)

data class SearchResults(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    /** Spotify only. */
    val playlists: List<Playlist> = emptyList(),
)

data class Page<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val hasMore: Boolean = false,
    /** Where the next page starts (skipped rows still count); -1 = offset + items.size. */
    val nextOffset: Int = -1,
) {
    val next: Int get() = if (nextOffset >= 0) nextOffset else offset + items.size
}

const val LIKED_SONGS_URI = "spotify:collection:tracks"
const val PODCAST_ID_PREFIX = "podcast:"
const val LOCAL_ID_PREFIX = "local:"
const val YOUTUBE_ID_PREFIX = "yt:"

fun isPodcastId(id: String) = id.length > PODCAST_ID_PREFIX.length && id.startsWith(PODCAST_ID_PREFIX)
fun isSpotifyId(id: String) = id.startsWith("spotify:")
fun isLocalId(id: String) = id.startsWith(LOCAL_ID_PREFIX)
