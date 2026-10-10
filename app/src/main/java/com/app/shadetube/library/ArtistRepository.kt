package com.app.shadetube.library

import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistPage
import com.app.shadetube.catalog.Track

/** The hero's mono label parts: "GRUP|SANATÇI · TR · 1990 · <FIRST TAG>" (+ " · ENGELLİ" from the blocklist). */
data class ArtistHeroInfo(
    val group: Boolean,
    val country: String,
    val beginYear: String,
    val firstTag: String,
    /** "{} DİNLEME · LISTENBRAINZ" (compactCount) when > 0, else [disambiguation] upper-cased when not empty. */
    val listenCount: Long,
    val disambiguation: String,
    /** "Radyo": Spotify's artist radio (needs a Spotify session and a Spotify artist; MusicBrainz artists have none). */
    val canRadio: Boolean,
    /** "Spotify bağlantısını kopyala" / "MusicBrainz bağlantısını kopyala". */
    val webLink: String,
    val webLinkIsSpotify: Boolean,
)

/** Artist page sections in order (empty ones are left out). */
sealed interface ArtistSection {
    /** "Popüler" (LISTENBRAINZ): the first [ArtistRepository.TOP_SHOWN] songs; playing one plays all top tracks. */
    data class Popular(val shown: List<Track>) : ArtistSection

    /** "Albümler" / "Tekliler ve EP'ler" / "Derlemeler ve canlı kayıtlar", with the count; cards show the year only. */
    data class Releases(val kind: Kind, val cards: List<Card>) : ArtistSection {
        enum class Kind { Albums, SinglesAndEps, Other }
    }

    /** "Benzer sanatçılar" (LISTENBRAINZ). */
    data class Similar(val cards: List<Card>) : ArtistSection
}

enum class ArtistPlayResult {
    Played,
    Toggled,

    /** "Bu sanatçı için popüler şarkı verisi yok". */
    NoPopularTracks,
    NoPlayer,
}

/** Artist page data (port of the non-UI part of ArtistView in BrowsePages.cpp): Spotify for spotify: ids, else MusicBrainz. */
class ArtistRepository(
    private val source: CatalogSource,
    private val library: UserLibrary,
    private val player: PlayerControls? = null,
) {
    /** Captures the active source now (like the C++ Api* captured on the UI thread). Throws on failure (error + retry). */
    suspend fun load(id: String): ArtistPage = source.artistPage(source.activeApi(), id)

    fun hero(a: Artist) = ArtistHeroInfo(
        group = a.type == "Group",
        country = a.country,
        beginYear = a.beginYear,
        firstTag = a.tags.firstOrNull().orEmpty(),
        listenCount = a.listenCount,
        disambiguation = a.disambiguation,
        canRadio = source.loggedIn && a.id.startsWith("spotify:artist:"),
        webLink = WebLinks.artist(a.id),
        webLinkIsSpotify = CatalogSource.isSpotifyId(a.id),
    )

    fun sections(d: ArtistPage): List<ArtistSection> {
        val out = ArrayList<ArtistSection>()
        if (d.topTracks.isNotEmpty()) out.add(ArtistSection.Popular(d.topTracks.take(TOP_SHOWN)))
        fun releases(kind: ArtistSection.Releases.Kind, list: List<com.app.shadetube.catalog.Album>) {
            if (list.isNotEmpty()) out.add(ArtistSection.Releases(kind, list.map { LibraryBrowser.browseAlbumCard(it, showArtist = false) }))
        }
        releases(ArtistSection.Releases.Kind.Albums, d.albums)
        releases(ArtistSection.Releases.Kind.SinglesAndEps, d.singles)
        releases(ArtistSection.Releases.Kind.Other, d.other)
        if (d.similar.isNotEmpty()) out.add(ArtistSection.Similar(d.similar.map { LibraryBrowser.browseArtistCard(it) }))
        return out
    }

    fun isFollowing(id: String) = library.isFollowing(id)
    fun toggleFollow(a: Artist): FollowResult = library.setFollow(a, !library.isFollowing(a.id))

    /** The player context of the artist's songs. */
    fun contextUri(id: String) = "artist:$id"

    /**
     * The hero's play button: pauses / resumes when the artist is playing, else plays the top tracks with no row
     * picked (the player then skips kara liste tracks); [index] >= 0 plays from a row of "Popüler".
     */
    fun play(d: ArtistPage, index: Int = -1): ArtistPlayResult {
        val p = player ?: return ArtistPlayResult.NoPlayer
        val ctx = contextUri(d.artist.id)
        if (index < 0 && p.contextUri == ctx) {
            p.togglePause()
            return ArtistPlayResult.Toggled
        }
        if (d.topTracks.isEmpty()) return ArtistPlayResult.NoPopularTracks
        p.playContext(d.topTracks, index, ctx, d.artist.name)
        return ArtistPlayResult.Played
    }

    companion object {
        const val TOP_SHOWN = 10
    }
}
