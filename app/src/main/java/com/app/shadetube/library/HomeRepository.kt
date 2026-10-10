package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.CardItem
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.catalog.Shelf
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Log
import com.app.shadetube.core.Text
import com.app.shadetube.core.Time
import com.app.shadetube.core.appScope
import com.app.shadetube.spotify.ApiError
import com.app.shadetube.spotify.LibrarySnapshot
import com.app.shadetube.spotify.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Spotify's personalized home shelves ([SpotifyAccount.home]), kept for the whole app session (port of the C++
 * ShelfCache in HomePage.cpp). The Home page is rebuilt on every navigation and the session changes several times while
 * the library loads, so the feed is fetched once per user, refreshed when stale ([FRESH_FOR_MS]), and never retried
 * in a tight loop after a failure (Spotify 429s hard: [RETRY_429_MS], other failures [RETRY_MS]).
 *
 * A fetch started by a Home that is navigated away from still fills the cache (it runs in [scope]), so the next Home
 * shows the shelves immediately.
 */
class ShelfCache(
    private val account: SpotifyAccount,
    private val scope: CoroutineScope = appScope,
    private val nowMs: () -> Long = { Time.steadyMs() },
    private val context: CoroutineContext = AppDispatchers.io,
) {
    private val lock = Any()
    private var userId = ""
    private var shelves: List<Shelf> = emptyList()
    private var hasData = false
    private var inFlight = false
    private var generation = 0
    private var fetchedAt = 0L
    private var retryAt = Long.MIN_VALUE
    private val _revision = MutableStateFlow(0L)

    /** Bumped when fresh shelves arrive (or a fetch ended without data a waiting Home should know about). */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Shelves for this user (possibly stale), or null until the first fetch lands. */
    fun shelvesFor(userId: String): List<Shelf>? = synchronized(lock) { if (hasData && this.userId == userId) shelves else null }

    /** Starts a background fetch when nothing fresh is cached for this user and no retry back-off is pending. */
    fun ensure(userId: String) {
        val gen: Int
        synchronized(lock) {
            val now = nowMs()
            if (userId != this.userId) {
                resetLocked()
                this.userId = userId
            }
            if (inFlight || (hasData && now - fetchedAt < FRESH_FOR_MS) || now < retryAt) return
            if (!account.loggedIn) return
            inFlight = true
            gen = generation
        }
        scope.launch(context) {
            val result = try {
                Result.success(account.home())
            } catch (e: CancellationException) {
                synchronized(lock) { if (gen == generation) inFlight = false }
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            synchronized(lock) {
                if (gen != generation) return@launch // logged out / switched account meanwhile
                inFlight = false
                val error = result.exceptionOrNull()
                if (error != null) {
                    val status = (error as? ApiError)?.status ?: 0
                    retryAt = nowMs() + if (status == 429) RETRY_429_MS else RETRY_MS
                    Log.w(TAG, "Spotify home shelves unavailable (HTTP $status): ${error.message}; showing the library-only home")
                    return@launch
                }
                shelves = result.getOrThrow()
                hasData = true
                fetchedAt = nowMs()
                Log.i(TAG, "${shelves.size} Spotify home shelves")
            }
            _revision.value = _revision.value + 1
        }
    }

    /**
     * The UI language changed: Spotify localizes the shelf titles (Accept-Language), so the next [ensure] fetches them
     * again; the current shelves stay on screen until the new ones land.
     */
    fun invalidate() = synchronized(lock) {
        fetchedAt = 0
        retryAt = Long.MIN_VALUE
    }

    /** Logged out: forget the previous user's feed. */
    fun reset() = synchronized(lock) { resetLocked() }

    private fun resetLocked() {
        generation++
        inFlight = false
        hasData = false
        shelves = emptyList()
        userId = ""
        retryAt = Long.MIN_VALUE
    }

    companion object {
        private const val TAG = "home"
        const val FRESH_FOR_MS = 20 * 60_000L
        const val RETRY_429_MS = 10 * 60_000L
        const val RETRY_MS = 3 * 60_000L
    }
}

/** The link of a section header. */
enum class SectionLink { None, Library, SeeAll }

/** A row of the fresh releases list: "TEKLİ · 27 EYL" ([releaseDate] "yyyy-mm-dd", "" when not a full date). */
data class FreshRelease(val album: Album, val type: AlbumType, val releaseDate: String)

/** Home's sections in display order; the UI maps each to its header text ("Son çalınanlar", ...). */
sealed interface HomeSection {
    /** One of Spotify's personalized shelves (title / label as Spotify sent them, in the UI language). */
    data class SpotifyShelf(val title: String, val label: String, val cards: List<Card>) : HomeSection

    /** "Son çalınanlar" (local history, works across sources); the count is shown as "%02d". */
    data class RecentlyPlayed(val cards: List<Card>, val link: SectionLink) : HomeSection

    /** "Senin için listeler": the stats package renders its row here (hidden while there is none). */
    data object SmartLists : HomeSection

    /**
     * "Bu hafta popüler" (LISTENBRAINZ; first album as the hero tile "01 · ARTIST", then up to 4 tiles "02 · ALBÜM")
     * next to "Yeni çıkanlar" (SON 14 GÜN, up to 4 rows). Either side may be empty.
     */
    data class PopularAndFresh(val trending: List<Album>, val fresh: List<FreshRelease>) : HomeSection

    /** "Çalma listelerin" ([count] = number of playlists, Liked Songs not counted when local). */
    data class YourPlaylists(val cards: List<Card>, val count: Int, val link: SectionLink) : HomeSection

    /** "Kaydettiğin albümler" (Spotify). */
    data class SavedAlbums(val cards: List<Card>) : HomeSection

    /** "Takip ettiğin sanatçılar" (Spotify). */
    data class FollowedArtists(val cards: List<Card>) : HomeSection

    /** "Sana özel" — "{seed} DİNLEYENLER BUNLARI DA SEVİYOR": artists similar to the most played ones. */
    data class ForYou(val seedName: String, val cards: List<Card>) : HomeSection

    /** "Popüler sanatçılar" (BU HAFTA). */
    data class PopularArtists(val cards: List<Card>) : HomeSection

    /** "Öne çıkan albümler": the trending albums after the first five. */
    data class MoreTrendingAlbums(val cards: List<Card>) : HomeSection
}

sealed interface HomeFeed {
    /** Skeleton. */
    data object Loading : HomeFeed

    /**
     * Logged in: Spotify's shelves followed by the user's library. [playlistCount] / [albumCount] (greeting meta
     * "SPOTIFY · {} LİSTE · {} ALBÜM") are null while the library snapshot is still loading.
     */
    data class Spotify(val playlistCount: Int?, val albumCount: Int?, val sections: List<HomeSection>) : HomeFeed

    /** Logged out (greeting meta "{} ÇALMA LİSTESİ · {} BEĞENİ"). */
    data class Open(val playlistCount: Int, val likedCount: Int, val sections: List<HomeSection>) : HomeFeed

    /** Nothing could be loaded: an error panel with a retry (collect [HomeRepository.feed] again). */
    data class Error(val message: String) : HomeFeed
}

/** What the logged-out Home fetches from ListenBrainz / MusicBrainz. */
data class OpenHomeData(
    val trendingAlbums: List<Album> = emptyList(),
    val trendingArtists: List<Artist> = emptyList(),
    val fresh: List<Album> = emptyList(),
    /** Similar to the user's top artists. */
    val forYou: List<Artist> = emptyList(),
    val forYouSeed: String = "",
    val failures: Int = 0,
    val firstError: String = "",
) {
    val failed: Boolean get() = failures >= 3 && trendingAlbums.isEmpty() && fresh.isEmpty()
}

/**
 * Ana Sayfa's data (port of the non-UI part of HomePage.cpp). Logged in: Spotify's personalized shelves followed by
 * the user's library — playlists, saved albums, followed artists — plus what they've recently played on this device.
 * Logged out: recently played (local history), trending on ListenBrainz, fresh releases, your playlists, artists
 * similar to what you play most.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRepository(
    private val account: SpotifyAccount,
    private val library: UserLibrary,
    private val open: OpenCatalog,
    val shelfCache: ShelfCache = ShelfCache(account),
) {
    /**
     * Our own section titles as shown ("Çalma listelerin", "Kaydettiğin albümler", "Takip ettiğin sanatçılar",
     * "Son çalınanlar"), set by the UI from strings.xml: Spotify shelves with the same title are left out (Spotify
     * titles its shelves in the UI language, so they are compared with our titles as shown).
     */
    @Volatile
    var ownSectionTitles: List<String> = emptyList()

    fun duplicatesLibrarySection(shelf: Shelf): Boolean {
        val t = Text.fold(shelf.title)
        return ownSectionTitles.any { Text.fold(it) == t }
    }

    /**
     * The live feed: rebuilt when the session (and its library) changes — logging in swaps the whole feed — and when
     * the personalized shelves arrive. Never waits on the shelves: the library shows at once and the shelves slot in
     * above it when they land; if the home query fails (hash rotated, 429) the page simply stays library-only.
     * Logged out it loads the open catalog once per collection (collect again to retry after [HomeFeed.Error]).
     */
    fun feed(): Flow<HomeFeed> = account.state.map { it == SessionState.LoggedIn }.distinctUntilChanged().flatMapLatest { loggedIn ->
        if (loggedIn) {
            combine(account.library, account.profile, shelfCache.revision) { snap, profile, _ ->
                shelfCache.ensure(profile.id)
                buildSpotify(snap, shelfCache.shelvesFor(profile.id))
            }
        } else {
            flow {
                if (account.state.value == SessionState.LoggedOut) shelfCache.reset()
                emit(HomeFeed.Loading)
                val d = loadOpenData()
                if (d.failed) {
                    emit(HomeFeed.Error(d.firstError))
                    return@flow
                }
                emitAll(library.state.map { buildOpen(d) })
            }
        }
    }

    /** Logged in: built straight from the session snapshot (no per-open network call). */
    fun buildSpotify(snap: LibrarySnapshot, shelves: List<Shelf>?): HomeFeed {
        val haveShelves = !shelves.isNullOrEmpty()
        if (!snap.loaded && !haveShelves) return HomeFeed.Loading
        val sections = ArrayList<HomeSection>()
        // --- Spotify's personalized shelves.
        if (haveShelves) {
            for (shelf in shelves!!) {
                if (duplicatesLibrarySection(shelf)) continue
                sections.add(HomeSection.SpotifyShelf(shelf.title, shelf.label, shelf.items.mapNotNull { shelfCard(it) }))
            }
        }
        // Library still loading: the next snapshot rebuilds with it.
        if (!snap.loaded) return HomeFeed.Spotify(null, null, sections)

        // --- Son çalınanlar (local history, works across sources).
        val recent = library.recentAlbums(RECENT_ALBUMS)
        if (recent.isNotEmpty()) {
            sections.add(HomeSection.RecentlyPlayed(recent.map { recentCard(it, "") }, SectionLink.None))
        }
        // --- Senin için listeler (built on this device from the listening history; hidden while there is none).
        sections.add(HomeSection.SmartLists)
        // --- Çalma listelerin (Spotify).
        if (snap.playlists.isNotEmpty()) {
            val cards = snap.playlists.map { p ->
                Card(
                    title = p.name,
                    subtitle = playlistSubtitle(p.countKnown, p.totalTracks, p.ownerName),
                    images = p.images,
                    destination = Destination.playlist(p.id),
                    playable = true,
                    placeholder = CardPlaceholder.Playlist,
                )
            }
            sections.add(HomeSection.YourPlaylists(cards, snap.playlists.size, SectionLink.Library))
        }
        // --- Kaydettiğin albümler.
        if (snap.albums.isNotEmpty()) sections.add(HomeSection.SavedAlbums(snap.albums.map { albumCard(it) }))
        // --- Takip ettiğin sanatçılar.
        if (snap.artists.isNotEmpty()) {
            sections.add(HomeSection.FollowedArtists(snap.artists.map { artistCard(it, CardSubtitle.ArtistKind(false)) }))
        }
        return HomeFeed.Spotify(snap.playlists.size, snap.albums.size, sections)
    }

    /**
     * Logged out: trending albums / artists, fresh releases and "Sana özel" (similar to the two most played
     * MusicBrainz artists). Each request may fail on its own; main-safe.
     */
    suspend fun loadOpenData(): OpenHomeData = withContext(AppDispatchers.io) {
        // Seeds are MusicBrainz artists only: history played while logged in to Spotify carries "spotify:artist:"
        // URIs, which ListenBrainz/MusicBrainz reject (HTTP 400, then a wasted rate-limited tag-overlap fallback).
        val seeds = library.topArtists(4).filter { !CatalogSource.isSpotifyId(it.id) }.take(2)
        var failures = 0
        var firstError = ""
        suspend fun attempt(fn: suspend () -> Unit) {
            try {
                fn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                if (firstError.isEmpty()) firstError = e.message ?: e.toString()
            }
        }
        var trendingAlbums = emptyList<Album>()
        var trendingArtists = emptyList<Artist>()
        var fresh = emptyList<Album>()
        val forYou = ArrayList<Artist>()
        var forYouSeed = ""
        attempt { trendingAlbums = open.trendingAlbums("week", 12) }
        attempt { trendingArtists = open.trendingArtists("week", 12) }
        attempt { fresh = open.freshReleases(14, 12) }
        if (seeds.isNotEmpty()) {
            attempt {
                val seen = HashSet<String>()
                for (s in seeds) {
                    seen.add(s.id)
                    for (a in open.similarArtists(s.id, 8)) if (seen.add(a.id)) forYou.add(a)
                }
                forYouSeed = seeds[0].name
            }
        }
        OpenHomeData(trendingAlbums, trendingArtists, fresh, forYou, forYouSeed, failures, firstError)
    }

    fun buildOpen(d: OpenHomeData): HomeFeed.Open {
        val lib = library.state.value
        val history = library.history.value
        val sections = ArrayList<HomeSection>()
        // --- Son çalınanlar (local history), each with the artist of its latest play.
        val recent = UserLibrary.recentAlbums(history, RECENT_ALBUMS)
        if (recent.isNotEmpty()) {
            val cards = recent.map { al ->
                val artist = history.firstOrNull { it.track.album.id == al.id && it.track.artists.isNotEmpty() }
                    ?.track?.artists?.first()?.name ?: ""
                recentCard(al, artist)
            }
            sections.add(HomeSection.RecentlyPlayed(cards, SectionLink.Library))
        }
        // --- Senin için listeler.
        sections.add(HomeSection.SmartLists)
        // --- Bu hafta popüler (tiles) + Yeni çıkanlar (list).
        if (d.trendingAlbums.isNotEmpty() || d.fresh.isNotEmpty()) {
            val fresh = d.fresh.take(4).map { a ->
                FreshRelease(a, AlbumType.of(a.primaryType), if (a.firstReleaseDate.length >= 10) a.firstReleaseDate.take(10) else "")
            }
            sections.add(HomeSection.PopularAndFresh(d.trendingAlbums.take(5), fresh))
        }
        // --- Çalma listelerin (local).
        if (lib.playlists.isNotEmpty() || lib.liked.isNotEmpty()) {
            val cards = ArrayList<Card>()
            if (lib.liked.isNotEmpty()) {
                cards.add(Card(LIKED_TITLE, CardSubtitle.Songs(lib.liked.size), emptyList(), Destination.Liked, true,
                    placeholder = CardPlaceholder.Playlist))
            }
            for (p in lib.playlists) {
                cards.add(Card(p.name, CardSubtitle.Songs(p.totalTracks), p.images, Destination.Playlist(p.id), true,
                    placeholder = CardPlaceholder.Playlist))
            }
            sections.add(HomeSection.YourPlaylists(cards, lib.playlists.size, SectionLink.SeeAll))
        }
        // --- Sana özel: similar to your most played artist.
        if (d.forYou.isNotEmpty()) {
            sections.add(HomeSection.ForYou(d.forYouSeed, d.forYou.map { artistCard(it, CardSubtitle.SimilarArtist) }))
        }
        // --- Popüler sanatçılar.
        if (d.trendingArtists.isNotEmpty()) {
            sections.add(HomeSection.PopularArtists(d.trendingArtists.map { artistCard(it, CardSubtitle.ArtistKind(false)) }))
        }
        // --- More trending albums.
        if (d.trendingAlbums.size > 5) {
            sections.add(HomeSection.MoreTrendingAlbums(d.trendingAlbums.drop(5).map { albumCard(it) }))
        }
        return HomeFeed.Open(lib.playlists.size, lib.liked.size, sections)
    }

    companion object {
        const val RECENT_ALBUMS = 12

        /** Liked Songs card title placeholder: the UI shows "Beğenilen Şarkılar" for [Destination.Liked]. */
        const val LIKED_TITLE = ""

        /** No count from Spotify (e.g. "Your Episodes"): show the owner instead of a misleading "0 şarkı". */
        fun playlistSubtitle(countKnown: Boolean, total: Int, ownerName: String): CardSubtitle = when {
            countKnown -> CardSubtitle.Songs(total)
            ownerName.isNotEmpty() -> CardSubtitle.Text(ownerName)
            else -> CardSubtitle.PlaylistWord
        }

        /** One card of a Spotify shelf. Playlists / albums get a play button; a track opens its album. */
        fun shelfCard(it: CardItem): Card? = when (it.kind) {
            CardItem.Kind.Playlist -> Card(it.title, CardSubtitle.Text(it.subtitle), it.images, Destination.playlist(it.id),
                playable = true, placeholder = CardPlaceholder.Playlist)
            CardItem.Kind.Album -> Card(it.title, CardSubtitle.Text(it.subtitle), it.images, Destination.Album(it.id), playable = true)
            CardItem.Kind.Artist -> Card(it.title, CardSubtitle.Text(it.subtitle), it.images, Destination.Artist(it.id),
                shape = CardShape.Circle, placeholder = CardPlaceholder.Artist)
            CardItem.Kind.Track -> {
                val albumId = it.track?.album?.id.orEmpty()
                if (albumId.isEmpty()) null
                else Card(it.title, CardSubtitle.Text(it.subtitle), it.images, Destination.Album(albumId), track = it.track)
            }
        }

        fun recentCard(al: AlbumRef, artist: String) =
            Card(al.name, if (artist.isEmpty()) CardSubtitle.None else CardSubtitle.Text(artist), al.images,
                Destination.Album(al.id), playable = true)

        /** Home album card: "<first artist> · <year>". */
        fun albumCard(a: Album) =
            Card(a.name, CardSubtitle.ArtistYear(a.artists.firstOrNull()?.name.orEmpty(), a.year), a.images,
                Destination.Album(a.id), playable = true)

        fun artistCard(a: Artist, subtitle: CardSubtitle) =
            Card(a.name, subtitle, a.images, Destination.Artist(a.id), shape = CardShape.Circle, placeholder = CardPlaceholder.Artist)
    }
}
