package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.catalog.Track
import com.app.shadetube.spotify.PlaylistEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

enum class CollectionKind { Album, Liked, Playlist }

/** The player as the library pages need it (AppGraph adapts playback's player). */
interface PlayerControls {
    /** The playing context's uri ("album:<id>", "playlist:<id>", "liked", "artist:<id>", "search", "history"...). */
    val contextUri: String
    val isPlaying: Boolean
    val isPaused: Boolean
    fun togglePause()
    fun play()
    fun setShuffle(on: Boolean)

    /** Replaces the queue with [tracks] and plays from [index] (-1: no row picked, the player skips kara liste tracks). */
    fun playContext(tracks: List<Track>, index: Int, contextUri: String, contextName: String)
    fun enqueue(tracks: List<Track>)
}

/**
 * "Geliştir" (smart shuffle's collection recommendations, `Settings.enhancedCollections`; AppGraph adapts the
 * playback / stats implementation). Keys: "liked" or a playlist id.
 */
interface CollectionEnhancer {
    /** Fires when "Geliştir" is turned on / off (also from a track menu) or recommendations were hidden / added. */
    val changes: Flow<Unit>
    fun isEnhanced(key: String): Boolean
    fun setEnhanced(key: String, on: Boolean)

    /** The collection's recommendations (cached per collection by the implementation). */
    suspend fun recommend(key: String, own: List<Track>): List<Track>

    /** Drops recommendations that are songs of the list (smartmix Exclusions: same id or same folded title + artist) or hidden. */
    fun prune(key: String, own: List<Track>, recs: List<Track>): List<Track>

    /** smartmix::interleave(own, recs, smartmix::hash(key)): a recommendation after every 3-4 own songs. */
    fun interleave(key: String, own: List<Track>, recs: List<Track>): List<Track>

    /** The recommended row's "+" (add it to the list) and "×" (hide it for this session). */
    fun add(key: String, track: Track)
    fun hide(key: String, track: Track)
}

/** Localized names the controller needs for the play context / header (set by the UI from strings.xml). */
data class CollectionLabels(
    /** "Beğenilen Şarkılar". */
    val likedSongs: String = "Liked Songs",
    /** "Çalma listesi" (a Spotify playlist without a name). */
    val playlist: String = "Playlist",
)

/** The small mono label above the title. */
sealed interface HeaderLabel {
    /** "ALBÜM · <SECONDARY TYPES> · 1997 · <LABEL>". */
    data class Album(val type: AlbumType, val secondaryTypes: List<String>, val year: String, val label: String) : HeaderLabel

    /** "ÇALMA LİSTESİ · SPOTIFY". */
    data object SpotifyPlaylist : HeaderLabel

    /** "ÇALMA LİSTESİ · SPOTIFY · BEĞENİLER". */
    data object SpotifyLiked : HeaderLabel

    /** "ÇALMA LİSTESİ · BU BİLGİSAYARDA". */
    data object LocalLiked : HeaderLabel

    /** "ÇALMA LİSTESİ · <date with year>" or "ÇALMA LİSTESİ · YEREL" ([createdAt] 0). */
    data class LocalPlaylist(val createdAt: Long) : HeaderLabel
}

/** The owner line: album artists, "Sen" (you), or a Spotify owner name. */
sealed interface OwnerText {
    data class Text(val text: String) : OwnerText
    data object You : OwnerText
}

data class CollectionHeader(
    val label: HeaderLabel,
    val title: String,
    val owner: OwnerText,
    /** Album: the first artist (the owner line opens it). */
    val ownerArtistId: String = "",
    val description: String = "",
    val images: List<Image> = emptyList(),
    /** "{} şarkı". */
    val total: Int = 0,
)

enum class CollectionEmpty {
    /** "Henüz beğendiğin şarkı yok" — "Şarkıların yanındaki kalbe dokun, burada toplansınlar." */
    NoLikedSongs,

    /** "Bu liste boş" — "Şarkılara ... "Çalma listesine ekle" ile doldur." */
    EmptyPlaylist,
}

sealed interface CollectionStatus {
    data object Loading : CollectionStatus
    data object Ready : CollectionStatus

    /** Error panel with a retry ([CollectionController.retry]). */
    data class Error(val message: String) : CollectionStatus

    /** "Çalma listesi bulunamadı" — "Silinmiş olabilir." */
    data object NotFound : CollectionStatus
}

/** Download sync / export target of the page (sync.Kind / transfer.What). */
data class OfflineTarget(val kind: CollectionKind, val id: String, val name: String, val images: List<Image>)

data class CollectionState(
    val status: CollectionStatus = CollectionStatus.Loading,
    val header: CollectionHeader? = null,
    /** The table's rows (the list's own songs, with the recommendations between them while "Geliştir" is on). */
    val rows: List<Track> = emptyList(),
    /** After the in-list filter and the sort: what is shown and what gets queued on play. */
    val displayed: List<Track> = emptyList(),
    val filter: String = "",
    val sort: TrackSort = TrackSort.Custom,
    val descending: Boolean = false,
    /** Own rows only. */
    val totalMs: Long = 0,
    /** Placeholder rows for pages still to load (Spotify paging). */
    val loadingRows: Int = 0,
    /** The user may add / remove items (Spotify: owner or collaborator; local playlists always). */
    val editable: Boolean = false,
    /** Spotify: the user owns it (rename / delete). */
    val owned: Boolean = false,
    /** The playlist rows can be removed from ("Bu çalma listesinden kaldır"); "" none. */
    val removablePlaylistId: String = "",
    val empty: CollectionEmpty? = null,
    /** "Geliştir" is on (playlists / Liked Songs only). */
    val enhanced: Boolean = false,
    /** Album: saved in the library (heart). */
    val savedAlbum: Boolean = false,
    /** One-shot: "Bu liste için öneri bulunamadı" after turning "Geliştir" on ([CollectionController.consumeNotice]). */
    val noRecommendationsNotice: Boolean = false,
)

/** The header's "more" menu entries that depend on data (the UI adds queue / export / offline). */
data class CollectionMenu(
    /** "Radyo başlat": Spotify's radio of this album / playlist ("" = none: Liked Songs, local lists, logged out). */
    val radioSeed: String,
    /** Album: "Sanatçıya git" ("" = none). */
    val artistId: String,
    /** "Spotify bağlantısını kopyala" / "MusicBrainz bağlantısını kopyala" ("" = none). */
    val copyLink: String,
    val copyLinkIsSpotify: Boolean,
    /** "Yeniden adlandır": local playlists and owned Spotify playlists. */
    val canRename: Boolean,
    /** "Çalma listesini sil" / "Kitaplıktan kaldır" (null = none). */
    val deleteKind: DeleteKind?,
    val offline: OfflineTarget,
)

/** What a collection page needs; built once by AppGraph. */
class CollectionDeps(
    val account: SpotifyAccount,
    val library: UserLibrary,
    val source: CatalogSource,
    val player: PlayerControls? = null,
    val enhancer: CollectionEnhancer? = null,
) {
    @Volatile
    var labels: CollectionLabels = CollectionLabels()
}

/**
 * Album / Liked Songs / playlist detail page (port of the non-UI part of CollectionPage.cpp). Reads Spotify when
 * logged in (albums, playlists and Liked Songs, with progressive paging: first page with the header metadata, then
 * [loadMore] as the user scrolls) and MusicBrainz / the local library otherwise (see [CatalogSource]).
 *
 * One controller per page; confined to [scope]'s thread (the UI's main scope): every public function must be called
 * there. Network calls are main-safe.
 *
 * @param routeId the page id, optionally with "#play" (open and start playing).
 */
class CollectionController(
    val kind: CollectionKind,
    routeId: String,
    private val deps: CollectionDeps,
    private val scope: CoroutineScope,
) {
    private val route = CollectionRoute.parse(routeId)
    val id: String = route.id
    private var autoplay = route.autoplay

    // Spotify sources: any album / playlist whose id is a spotify: URI, and Liked Songs while logged in.
    private val api: SpotifyAccount? = deps.source.activeApi()
    val spotifyMode: Boolean = api != null && (kind == CollectionKind.Liked || CatalogSource.isSpotifyId(id))

    private val _state = MutableStateFlow(CollectionState())
    val state: StateFlow<CollectionState> = _state.asStateFlow()

    private var album = Album()

    // Spotify paging state (playlists / Liked Songs).
    private var uri = ""
    private var total = 0
    private var loadedOffset = 0
    private var loading = false
    private var gen = 0 // bumps on every (re)load of the first page
    private var pageGen = 0 // bumps when rows are removed locally (a page in flight has a stale offset)
    private var firstPending = false // a (re)load of the first page is in flight

    // "Geliştir".
    private var recs: List<Track> = emptyList() // this list's recommendations (shown while it is on)
    private var enhanceLoading = false
    private var announce = false // turned on just now: a notice when nothing fits
    private var enhanceJob: Job? = null
    private var started = false

    private val enhanceKey: String get() = if (kind == CollectionKind.Liked) "liked" else id

    /** The player's context uri for this list ("album:<id>", "playlist:<id>", "liked"). */
    val contextUri: String
        get() = when (kind) {
            CollectionKind.Album -> "album:$id"
            CollectionKind.Playlist -> "playlist:$id"
            CollectionKind.Liked -> "liked"
        }

    /** Starts loading and following changes (once). */
    fun start() {
        if (started) return
        started = true
        when {
            kind == CollectionKind.Album -> loadAlbum()
            spotifyMode -> {
                loadSpotifyCollection()
                // Follow writes to this playlist (from its own menus, the sidebar or an "add to playlist" elsewhere).
                if (kind == CollectionKind.Playlist) scope.launch { deps.account.edits.collect { onEdit(it) } }
            }
            else -> {
                buildLocal()
                // Local collections follow library edits live (removals, renames, likes).
                scope.launch { deps.library.state.drop(1).collect { refreshLocal() } }
            }
        }
        // "Geliştir" on / off (also from a track menu), hidden and added recommendations.
        val enh = deps.enhancer
        if (kind != CollectionKind.Album && enh != null) scope.launch { enh.changes.collect { onEnhanceChanged() } }
        if (kind == CollectionKind.Album) scope.launch { deps.library.revision.collect { refreshSaved() } }
    }

    /** After an error: load again. */
    fun retry() {
        update { it.copy(status = CollectionStatus.Loading) }
        when {
            kind == CollectionKind.Album -> loadAlbum()
            spotifyMode -> loadSpotifyCollection()
            else -> buildLocal()
        }
    }

    private inline fun update(change: (CollectionState) -> CollectionState) {
        val s = change(_state.value)
        _state.value = s.copy(displayed = TrackListView.apply(s.rows, s.filter, s.sort, s.descending))
    }

    private fun setRows(rows: List<Track>) = update { it.copy(rows = rows, totalMs = TrackListView.totalMs(rows)) }

    private fun ownTracks(): List<Track> = _state.value.rows.filter { !it.recommended }

    // ---- Filter / sort ---------------------------------------------------------------------------------------------

    fun setFilter(text: String) = update { it.copy(filter = text) }

    fun setSort(key: TrackSort, descending: Boolean) = update { it.copy(sort = key, descending = descending) }

    /** A click on a column header (ascending -> descending -> custom order). */
    fun headerSortClick(key: TrackSort) {
        val s = _state.value
        val o = TrackListView.headerClick(s.sort, s.descending, key)
        setSort(o.key, o.descending)
    }

    // ---- Album ------------------------------------------------------------------------------------------------------

    private fun loadAlbum() {
        val g = ++gen
        scope.launch {
            val r = attempt { deps.source.album(api, id) }
            if (g != gen) return@launch
            r.onFailure {
                update { s -> s.copy(status = CollectionStatus.Error(it.message ?: it.toString())) }
                return@launch
            }
            album = r.getOrThrow()
            val header = CollectionHeader(
                label = HeaderLabel.Album(AlbumType.of(album.primaryType), album.secondaryTypes, album.year, album.label),
                title = album.name,
                owner = OwnerText.Text(album.artists.joinToString(", ") { it.name }),
                ownerArtistId = album.artists.firstOrNull()?.id.orEmpty(),
                images = album.images,
                total = album.tracks.size,
            )
            update { it.copy(header = header, savedAlbum = deps.library.isSavedAlbum(id)) }
            setRows(album.tracks)
            finishLoading()
        }
    }

    private fun refreshSaved() {
        if (kind != CollectionKind.Album) return
        val saved = deps.library.isSavedAlbum(id)
        if (saved != _state.value.savedAlbum) update { it.copy(savedAlbum = saved) }
    }

    /** The album's heart. */
    fun toggleSavedAlbum(): SaveAlbumResult {
        if (kind != CollectionKind.Album || album.id.isEmpty()) return SaveAlbumResult.Unchanged
        val r = deps.library.setSavedAlbum(album, !deps.library.isSavedAlbum(id))
        refreshSaved()
        return r
    }

    // ---- Spotify playlist / Liked Songs --------------------------------------------------------------------------------

    /** First page (with header metadata), then progressive paging. [uri] = the playlist, or Liked Songs' collection URI. */
    private fun loadSpotifyCollection() {
        val a = api ?: return
        uri = if (kind == CollectionKind.Liked) LIKED_SONGS_URI else id
        val u = uri
        val liked = kind == CollectionKind.Liked
        val g = ++gen // a reload supersedes pages still in flight
        firstPending = true
        scope.launch {
            val r = attempt { a.playlistTracks(u, 0, PAGE) }
            if (g != gen) return@launch
            firstPending = false
            r.onFailure {
                update { s -> s.copy(status = CollectionStatus.Error(it.message ?: it.toString())) }
                return@launch
            }
            val first = r.getOrThrow()
            val page = first.page
            val meta = first.meta
            val header = if (liked) {
                val me = deps.account.profile.value.name
                CollectionHeader(HeaderLabel.SpotifyLiked, deps.labels.likedSongs,
                    if (me.isEmpty()) OwnerText.You else OwnerText.Text(me), total = page.total)
            } else {
                CollectionHeader(
                    HeaderLabel.SpotifyPlaylist,
                    meta?.name?.takeIf { it.isNotEmpty() } ?: deps.labels.playlist,
                    OwnerText.Text(first.ownerName),
                    description = meta?.description.orEmpty(),
                    images = meta?.images ?: emptyList(),
                    total = page.total,
                )
            }
            total = page.total
            // Raw server rows consumed (episodes are skipped by the parser but still take an offset).
            loadedOffset = if (page.nextOffset >= 0) page.nextOffset else page.items.size
            loading = false
            // Edit rights come from the playlist itself (currentUserCapabilities), not from the library.
            val editable = !liked && meta?.editable == true
            val owned = !liked && meta?.owned == true
            update {
                it.copy(
                    header = header, editable = editable, owned = owned,
                    removablePlaylistId = if (editable) u else "", // enables "Bu çalma listesinden kaldır"
                    loadingRows = if (loadedOffset < total) minOf(8, total - loadedOffset) else 0,
                    empty = if (total == 0 && editable) CollectionEmpty.EmptyPlaylist else null,
                )
            }
            setRows(page.items)
            showEnhanced()
            finishLoading()
            // A page with no tracks at all (only episodes) gives the list no rows to scroll near: keep paging.
            if (_state.value.rows.isEmpty() && loadedOffset < total) loadMore()
        }
    }

    /** The list scrolled near its end: the next page (Spotify only; no-op otherwise or while one is loading). */
    fun loadMore() {
        val a = api ?: return
        if (!spotifyMode || kind == CollectionKind.Album || loading || loadedOffset >= total) return
        loading = true
        val u = uri
        val offset = loadedOffset
        val g = gen
        val pg = pageGen
        scope.launch {
            val r = attempt { a.playlistTracks(u, offset, PAGE).page }
            if (g != gen || pg != pageGen) return@launch // the list was reloaded / edited meanwhile
            loading = false
            val page = r.getOrNull() ?: return@launch
            val next = if (page.nextOffset >= 0) page.nextOffset else offset + page.items.size
            loadedOffset = if (next > offset) next else total // no progress: stop (guards against a stuck tail)
            val none = page.items.isEmpty()
            setRows(_state.value.rows + page.items)
            update { it.copy(loadingRows = if (loadedOffset < total) minOf(8, total - loadedOffset) else 0) }
            if (none && _state.value.rows.isEmpty() && loadedOffset < total) loadMore() // see the first page
        }
    }

    /**
     * A successful write to this Spotify playlist. Removals are applied in place (exact, no refetch); additions reload
     * the first page (they land at the end); a rename only touches the header.
     */
    internal fun onEdit(e: PlaylistEdit) {
        val s = _state.value
        if (e.uri != uri || s.header == null) return
        when (e.kind) {
            PlaylistEdit.Kind.Removed -> {
                val gone = e.removedUids.toHashSet()
                val tracks = s.rows.filter { it.uid.isEmpty() || it.uid !in gone }
                val removed = s.rows.size - tracks.size
                if (removed == 0) return
                // A first page still loading may predate the removal: load it again rather than resurrect the rows.
                if (firstPending) {
                    loadSpotifyCollection()
                    return
                }
                total = maxOf(0, total - removed)
                loadedOffset = maxOf(0, loadedOffset - removed) // the server's rows shifted up
                if (loading) { // a page in flight was requested at the old offset: drop it, the list asks again
                    ++pageGen
                    loading = false
                }
                update { it.copy(header = it.header?.copy(total = total)) }
                setRows(tracks)
                if (tracks.isEmpty() && loadedOffset < total) {
                    loadMore() // every loaded row is gone: no row left to scroll near, fetch the rest now
                } else if (total == 0 && s.empty == null) {
                    update { it.copy(empty = CollectionEmpty.EmptyPlaylist) }
                }
            }
            PlaylistEdit.Kind.Added -> loadSpotifyCollection()
            PlaylistEdit.Kind.Renamed -> update { it.copy(header = it.header?.copy(title = e.name)) }
            else -> Unit
        }
    }

    // ---- Local (logged out) -----------------------------------------------------------------------------------------

    private fun localHeader(): CollectionHeader? {
        val lib = deps.library
        if (kind == CollectionKind.Liked) {
            return CollectionHeader(HeaderLabel.LocalLiked, deps.labels.likedSongs, OwnerText.You, total = lib.liked.size)
        }
        val p = lib.playlist(id) ?: return null
        return CollectionHeader(HeaderLabel.LocalPlaylist(p.createdAt), p.name, OwnerText.You,
            description = p.description, images = p.images, total = p.totalTracks)
    }

    private fun localTracks(): List<Track> =
        if (kind == CollectionKind.Liked) deps.library.liked else deps.library.playlistTracks(id) ?: emptyList()

    private fun buildLocal() {
        if (kind == CollectionKind.Playlist && deps.library.playlist(id) == null) {
            update { it.copy(status = CollectionStatus.NotFound, header = null, rows = emptyList(), totalMs = 0) }
            return
        }
        val tracks = localTracks()
        update {
            it.copy(
                header = localHeader(),
                editable = kind == CollectionKind.Playlist,
                owned = kind == CollectionKind.Playlist,
                removablePlaylistId = if (kind == CollectionKind.Playlist) id else "",
                empty = when {
                    tracks.isNotEmpty() -> null
                    kind == CollectionKind.Liked -> CollectionEmpty.NoLikedSongs
                    else -> CollectionEmpty.EmptyPlaylist
                },
            )
        }
        setRows(tracks)
        showEnhanced()
        finishLoading()
    }

    private fun refreshLocal() {
        val s = _state.value
        if (s.header == null) {
            buildLocal()
            return
        }
        val tracks = localTracks()
        if (tracks.size != ownTracks().size || (s.empty != null && tracks.isNotEmpty())) {
            buildLocal()
        } else {
            val h = localHeader()
            if (h == null) buildLocal() else update { it.copy(header = h) }
        }
    }

    private fun finishLoading() {
        update { it.copy(status = CollectionStatus.Ready) }
        if (autoplay) {
            autoplay = false
            playFrom(-1, false)
        }
    }

    // ---- Playback ---------------------------------------------------------------------------------------------------

    fun isPlayingThis(): Boolean {
        val p = deps.player ?: return false
        return p.isPlaying && p.contextUri == contextUri
    }

    /**
     * Play button (-1: from the start; toggles pause when this list is playing, resumes when it is paused) or a row
     * ([displayIndex] into [CollectionState.displayed]); [shuffle] turns shuffle on first.
     */
    fun playFrom(displayIndex: Int, shuffle: Boolean) {
        val p = deps.player ?: return
        val header = _state.value.header ?: return
        // Play (not Karıştır) on the playing list pauses / resumes it; Karıştır always starts a shuffled run (the
        // desktop app paused here too, which reads as a dead button on a phone).
        if (displayIndex < 0 && !shuffle && isPlayingThis()) {
            p.togglePause()
            return
        }
        if (displayIndex < 0 && !shuffle && p.contextUri == contextUri && p.isPaused) {
            p.play()
            return
        }
        val tracks = _state.value.displayed
        if (tracks.isEmpty()) return
        if (shuffle) p.setShuffle(true)
        p.playContext(tracks, displayIndex, contextUri, header.title)
    }

    // ---- "Geliştir" ---------------------------------------------------------------------------------------------------

    /** The list's own songs, with the recommendations between them while "Geliştir" is on. */
    private fun applyEnhance() {
        val enh = deps.enhancer ?: return
        if (kind == CollectionKind.Album || _state.value.header == null) return
        val on = enh.isEnhanced(enhanceKey)
        val own = ownTracks()
        recs = enh.prune(enhanceKey, own, recs)
        val shown = own.size != _state.value.rows.size
        update { it.copy(enhanced = on) }
        if (!on && !shown) return
        setRows(if (on) enh.interleave(enhanceKey, own, recs) else own)
    }

    /** After the list (re)loaded: its recommendations, fetched once per page (cached per collection). */
    private fun showEnhanced() {
        val enh = deps.enhancer ?: return
        if (kind == CollectionKind.Album || !enh.isEnhanced(enhanceKey)) return
        if (recs.isNotEmpty() || enhanceLoading) {
            applyEnhance()
            return
        }
        enhanceLoading = true
        enhanceJob?.cancel()
        val own = ownTracks()
        enhanceJob = scope.launch {
            val r = attempt { enh.recommend(enhanceKey, own) }.getOrDefault(emptyList())
            enhanceLoading = false
            recs = r.take(ENHANCE_MAX)
            if (recs.isEmpty() && announce) update { it.copy(noRecommendationsNotice = true) }
            announce = false
            applyEnhance()
        }
    }

    private fun onEnhanceChanged() {
        val enh = deps.enhancer ?: return
        if (_state.value.header == null) return
        val on = enh.isEnhanced(enhanceKey)
        update { it.copy(enhanced = on) }
        if (on && recs.isEmpty()) showEnhanced() else applyEnhance()
    }

    /** The "Geliştir" button. */
    fun toggleEnhanced() {
        val enh = deps.enhancer ?: return
        if (kind == CollectionKind.Album) return
        val on = !enh.isEnhanced(enhanceKey)
        if (on) announce = true // say so when nothing fits
        enh.setEnhanced(enhanceKey, on)
    }

    fun addRecommended(t: Track) = deps.enhancer?.add(enhanceKey, t)
    fun hideRecommended(t: Track) = deps.enhancer?.hide(enhanceKey, t)

    fun consumeNotice() = update { it.copy(noRecommendationsNotice = false) }

    // ---- Menu -------------------------------------------------------------------------------------------------------

    fun menu(): CollectionMenu {
        val s = _state.value
        val title = s.header?.title.orEmpty()
        val seed = if (kind != CollectionKind.Liked && deps.account.loggedIn) radioSeed(id) else ""
        var copy = ""
        var copySpotify = false
        if (kind == CollectionKind.Album) {
            copy = WebLinks.album(id)
            copySpotify = CatalogSource.isSpotifyId(id)
        } else if (kind == CollectionKind.Playlist && spotifyMode) {
            copy = WebLinks.playlist(id)
            copySpotify = true
        }
        // Local playlists, and Spotify playlists the user may edit (rename: owner only). Others: no edit actions.
        val local = kind == CollectionKind.Playlist && !spotifyMode
        val spotifyEdit = kind == CollectionKind.Playlist && spotifyMode && s.editable
        val deleteKind = when {
            local -> DeleteKind.DeleteLocal
            spotifyEdit -> if (s.owned) DeleteKind.DeleteSpotify else DeleteKind.RemoveFromLibrary
            else -> null
        }
        // Download sync: Liked Songs by the active source (Spotify's in Spotify mode), else the album / playlist id.
        val offlineId = if (kind == CollectionKind.Liked) (if (spotifyMode) LIKED_SONGS_URI else LOCAL_LIKED_ID) else id
        return CollectionMenu(
            radioSeed = seed,
            artistId = if (kind == CollectionKind.Album) s.header?.ownerArtistId.orEmpty() else "",
            copyLink = copy,
            copyLinkIsSpotify = copySpotify,
            canRename = local || (spotifyEdit && s.owned),
            deleteKind = deleteKind,
            offline = OfflineTarget(kind, offlineId, title, s.header?.images ?: emptyList()),
        )
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        const val PAGE = 100

        /** Recommendations shown in a list (one every 3-4 songs). */
        const val ENHANCE_MAX = 30

        /** sync::kLocalLikedId: the local Liked Songs as a download-sync / export id. */
        const val LOCAL_LIKED_ID = "liked"
    }
}
