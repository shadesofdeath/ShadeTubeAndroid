package com.app.shadetube.library

import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.catalog.Track

/**
 * Where a card / row leads (the C++ Route kinds the library pages navigate to). A card with [Card.playable] also has
 * a play button, which opens the same destination with autoplay (the C++ "#play" suffix; see [CollectionRoute]).
 */
sealed interface Destination {
    data object Liked : Destination
    data class Playlist(val id: String) : Destination
    data class Album(val id: String) : Destination
    data class Artist(val id: String) : Destination

    /** The Library page ([folderId] "" = the whole library; else one Spotify library folder, route "folder:<id>"). */
    data class Library(val folderId: String = "") : Destination

    /** "Senin için listeler" (stats package). */
    data class SmartList(val id: String) : Destination

    companion object {
        /** Liked Songs (the Spotify pseudo-playlist or the local list) or a playlist page. */
        fun playlist(id: String): Destination = if (id == LIKED_SONGS_URI) Liked else Playlist(id)
    }
}

/** Card shapes / placeholders the UI draws. */
enum class CardShape { Square, Circle }
enum class CardPlaceholder { Album, Playlist, Artist, Folder }

/** A card's subtitle, as data (the UI formats it). */
sealed interface CardSubtitle {
    data object None : CardSubtitle

    /** Plain text from the catalog (artist name, owner name, a Spotify shelf card's subtitle). */
    data class Text(val text: String) : CardSubtitle

    /** "{} şarkı". */
    data class Songs(val count: Int) : CardSubtitle

    /** "{} çalma listesi" (a folder). */
    data class Playlists(val count: Int) : CardSubtitle

    /** "Çalma listesi" (no count and no owner known). */
    data object PlaylistWord : CardSubtitle

    /** "Sanatçı" / "Grup" (+ " · TR"). */
    data class ArtistKind(val group: Boolean, val country: String = "") : CardSubtitle

    /** "Benzer sanatçı". */
    data object SimilarArtist : CardSubtitle

    /** Home: "<artist> · <year>". */
    data class ArtistYear(val artist: String, val year: String) : CardSubtitle

    /** Browse pages: "<year> · Tekli|EP|Albüm · <artist>" ([artist] "" when not shown). */
    data class AlbumLine(val year: String, val type: AlbumType, val artist: String) : CardSubtitle

    /** Artist page: just the year. */
    data class Year(val year: String) : CardSubtitle
}

/** "Tekli" / "EP" / "Albüm" (MusicBrainz primary types; anything else counts as an album). */
enum class AlbumType {
    Single, EP, Album;

    companion object {
        fun of(primaryType: String): AlbumType = when (primaryType) {
            "Single" -> Single
            "EP" -> EP
            else -> Album
        }
    }
}

data class Card(
    val title: String,
    val subtitle: CardSubtitle = CardSubtitle.None,
    val images: List<Image> = emptyList(),
    val destination: Destination,
    val playable: Boolean = false,
    val shape: CardShape = CardShape.Square,
    val placeholder: CardPlaceholder = CardPlaceholder.Album,
    /** Untitled folder: the UI shows "Adsız klasör" when [title] is empty. */
    val isFolder: Boolean = false,
    /** For a track card (Spotify home shelves): the song itself. */
    val track: Track? = null,
)

/** A collection route id: "<id>" or "<id>#play" (open and start playing). */
data class CollectionRoute(val id: String, val autoplay: Boolean) {
    companion object {
        fun parse(raw: String): CollectionRoute {
            val hash = raw.indexOf("#play")
            return if (hash >= 0) CollectionRoute(raw.substring(0, hash), true) else CollectionRoute(raw, false)
        }
    }
}
