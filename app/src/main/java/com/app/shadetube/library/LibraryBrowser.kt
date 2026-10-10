package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.Text
import com.app.shadetube.spotify.LibrarySnapshot
import com.app.shadetube.spotify.PlaylistFolder
import com.app.shadetube.spotify.SessionState
import com.app.shadetube.spotify.treeRows
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

enum class LibraryTab { Playlists, Albums, Artists, History }

/** Android addition (the desktop Library page has no sort): the order of the cards. */
enum class LibrarySort {
    /** The library's own order (Spotify: libraryV3's, recently used first; local: newest first). */
    Recent,

    /** By folded title. */
    Name,
}

/** Empty-state panels of the Library page. */
enum class LibraryEmpty {
    /** "Kayıtlı albüm yok" — "Albüm sayfasındaki kalp ile albümleri buraya kaydet." */
    NoAlbums,

    /** "Takip ettiğin sanatçı yok" — "Sanatçı sayfalarından takip etmeye başla." */
    NoArtists,

    /** "Henüz geçmiş yok" — "Dinlediğin şarkılar burada listelenir." */
    NoHistory,

    /** "Bu klasör boş" — "Spotify'da bu klasöre çalma listesi ekleyebilirsin." */
    EmptyFolder,
}

/**
 * One Spotify library folder's header: its name ("" = "Adsız klasör"), "{} ÇALMA LİSTESİ", and the way back up (the
 * parent folder, else the whole library).
 */
data class FolderHeader(
    val folder: PlaylistFolder,
    /** null = up goes to the whole library ("Kitaplık"). */
    val parent: PlaylistFolder?,
) {
    val up: Destination.Library get() = Destination.Library(parent?.id ?: "")
}

data class LibraryView(
    /** Non-null when the route is a folder that exists: the folder's playlists and subfolders. */
    val folder: FolderHeader?,
    val tab: LibraryTab,
    /** Playlists tab, top level: the "Senin için listeler" row (stats package) above the cards. */
    val showSmartLists: Boolean,
    val cards: List<Card>,
    /** History tab: the songs, newest first (played with context "history"). */
    val history: List<Track>,
    val empty: LibraryEmpty?,
    /** Logged in: the cards come from Spotify ("Yeni çalma listesi" then creates a Spotify playlist). */
    val spotify: Boolean,
)

/** A row of the sidebar's playlist list. */
data class SidebarRow(
    val title: String,
    val depth: Int,
    val destination: Destination,
    val folder: Boolean = false,
    val expanded: Boolean = false,
    /** Plain track count; -1 = unknown (Spotify gave none): no trailing count rather than a misleading "0". */
    val count: Int = -1,
    /** Context menu: Liked Songs. */
    val liked: Boolean = false,
    /** Editable Spotify playlist (owned or collaborative): delete (unfollow); [owned]: rename too. Local: delete. */
    val editable: Boolean = false,
    val owned: Boolean = false,
    val local: Boolean = false,
)

/**
 * The Library page and the sidebar's playlist list (port of the non-UI part of LibraryPage in BrowsePages.cpp and
 * Sidebar::refresh / setFolderOpen in Shell.cpp). Logged in it shows the user's Spotify library (playlists in their
 * folders, saved albums, followed artists); logged out the local library. History is always this device's.
 *
 * Folders (spotify.PlaylistTree): the Library page shows them as folder cards opening `{Library, "folder:<id>"}`;
 * the sidebar as collapsible rows whose open state is kept in `Settings.expandedFolders`.
 */
class LibraryBrowser(
    private val account: SpotifyAccount,
    private val library: UserLibrary,
    private val expandedFolders: Flow<List<String>> = SettingsStore.flow.map { it.expandedFolders },
    private val currentExpanded: () -> List<String> = { SettingsStore.current.expandedFolders },
    private val updateExpanded: ((List<String>) -> List<String>) -> Unit = { f ->
        SettingsStore.update { it.copy(expandedFolders = f(it.expandedFolders)) }
    },
) {
    /** The live view of the Library route ([route] = "" or "folder:<id>"). */
    fun view(route: String, tab: LibraryTab, filter: String = "", sort: LibrarySort = LibrarySort.Recent): Flow<LibraryView> =
        combine(account.state, account.library, library.state, library.revision) { _, _, _, _ -> build(route, tab, filter, sort) }

    fun build(route: String, tab: LibraryTab, filter: String = "", sort: LibrarySort = LibrarySort.Recent): LibraryView {
        val sp = account.loggedIn
        val snap = account.library.value
        // A folder that is gone (moved / deleted on Spotify, logged out): the whole library instead.
        val folderId = folderOf(route)
        val folder = if (folderId.isNotEmpty() && sp) snap.tree.folder(folderId) else null
        if (folder != null) {
            val parent = if (folder.parentId.isEmpty()) null else snap.tree.folder(folder.parentId)
            val cards = arrange(spotifyCards(snap, folder.id), filter, sort)
            return LibraryView(FolderHeader(folder, parent), LibraryTab.Playlists, false, cards, emptyList(),
                if (folder.playlistCount == 0) LibraryEmpty.EmptyFolder else null, true)
        }
        val lib = library.state.value
        return when (tab) {
            LibraryTab.Playlists -> {
                // Top level of the library: folders sit where their most recent playlist would be.
                val cards = if (sp) spotifyCards(snap, "") else localPlaylistCards()
                LibraryView(null, tab, true, arrange(cards, filter, sort), emptyList(), null, sp)
            }
            LibraryTab.Albums -> {
                val albums = if (sp) snap.albums else lib.albums
                LibraryView(null, tab, false, arrange(albums.map { browseAlbumCard(it) }, filter, sort), emptyList(),
                    if (albums.isEmpty()) LibraryEmpty.NoAlbums else null, sp)
            }
            LibraryTab.Artists -> {
                val artists = if (sp) snap.artists else lib.artists
                LibraryView(null, tab, false, arrange(artists.map { browseArtistCard(it) }, filter, sort), emptyList(),
                    if (artists.isEmpty()) LibraryEmpty.NoArtists else null, sp)
            }
            LibraryTab.History -> {
                val tracks = library.history.value.map { it.track }
                LibraryView(null, tab, false, emptyList(), tracks, if (tracks.isEmpty()) LibraryEmpty.NoHistory else null, sp)
            }
        }
    }

    private fun localPlaylistCards(): List<Card> {
        val lib = library.state.value
        val out = ArrayList<Card>()
        out.add(Card(HomeRepository.LIKED_TITLE, CardSubtitle.Songs(lib.liked.size), emptyList(), Destination.Liked, true,
            placeholder = CardPlaceholder.Playlist))
        for (p in lib.playlists) {
            out.add(Card(p.name, CardSubtitle.Songs(p.totalTracks), p.images, Destination.Playlist(p.id), true,
                placeholder = CardPlaceholder.Playlist))
        }
        return out
    }

    /** Sidebar: logged in, the user's Spotify playlists (Liked Songs first) in their folders; else Liked + local playlists. */
    fun sidebar(): Flow<List<SidebarRow>> =
        combine(account.state, account.library, library.state, expandedFolders) { state, snap, _, open ->
            sidebarRows(state == SessionState.LoggedIn, snap, open)
        }

    fun sidebarRows(loggedIn: Boolean, snap: LibrarySnapshot, open: List<String>): List<SidebarRow> {
        if (!loggedIn) {
            val lib = library.state.value
            return listOf(SidebarRow("", 0, Destination.Liked, count = lib.liked.size, liked = true, local = true)) +
                lib.playlists.map { SidebarRow(it.name, 0, Destination.Playlist(it.id), count = it.totalTracks, editable = true, owned = true, local = true) }
        }
        val expanded: (String) -> Boolean = { it in open }
        return treeRows(snap.playlists, snap.tree, expanded).map { row ->
            if (row.folder) {
                val f = snap.tree.folders[row.index]
                // Click toggles it ([setFolderOpen]); "Kitaplıkta göster" opens the folder page.
                SidebarRow(f.name, row.depth, Destination.Library(f.id), folder = true, expanded = expanded(f.id))
            } else {
                val p = snap.playlists[row.index]
                val liked = p.id == LIKED_SONGS_URI
                SidebarRow(p.name, row.depth, Destination.playlist(p.id), count = if (p.countKnown) p.totalTracks else -1,
                    liked = liked, editable = !liked && p.editable, owned = p.owned)
            }
        }
    }

    /** Opens / closes a sidebar folder (persisted in Settings.expandedFolders). */
    fun setFolderOpen(folderId: String, open: Boolean) {
        if ((folderId in currentExpanded()) == open) return
        updateExpanded { list -> if (open) (if (folderId in list) list else list + folderId) else list - folderId }
    }

    companion object {
        const val FOLDER_PREFIX = "folder:"

        /** "folder:<id>" -> "<id>"; "" for the whole library. */
        fun folderOf(route: String): String = if (route.startsWith(FOLDER_PREFIX)) route.substring(FOLDER_PREFIX.length) else ""

        fun folderRoute(folderId: String): String = if (folderId.isEmpty()) "" else FOLDER_PREFIX + folderId

        /** Spotify playlists and folders directly under [folderId] ("" = the top level), as cards in library order. */
        fun spotifyCards(snap: LibrarySnapshot, folderId: String): List<Card> =
            treeRows(snap.playlists, snap.tree, { false }, folderId).map { row ->
                if (row.folder) {
                    val f = snap.tree.folders[row.index]
                    Card(f.name, CardSubtitle.Playlists(f.playlistCount), emptyList(), Destination.Library(f.id),
                        placeholder = CardPlaceholder.Folder, isFolder = true)
                } else {
                    val p = snap.playlists[row.index]
                    Card(p.name, HomeRepository.playlistSubtitle(p.countKnown, p.totalTracks, p.ownerName), p.images,
                        Destination.playlist(p.id), playable = true, placeholder = CardPlaceholder.Playlist)
                }
            }

        /** Browse album card: "<year> · Tekli|EP|Albüm · <artist>" ([showArtist] false: just the year). */
        fun browseAlbumCard(a: Album, showArtist: Boolean = true) = Card(
            a.name,
            if (showArtist) CardSubtitle.AlbumLine(a.year, AlbumType.of(a.primaryType), a.artists.firstOrNull()?.name.orEmpty())
            else CardSubtitle.Year(a.year),
            a.images, Destination.Album(a.id), playable = true,
        )

        /** "Grup" / "Sanatçı" (+ " · <country>"). */
        fun browseArtistCard(a: Artist) = Card(a.name, CardSubtitle.ArtistKind(a.type == "Group", a.country), a.images,
            Destination.Artist(a.id), shape = CardShape.Circle, placeholder = CardPlaceholder.Artist)

        /** Android addition: filter by folded title (Liked Songs, titled by the UI, always stays) and sort. */
        fun arrange(cards: List<Card>, filter: String, sort: LibrarySort): List<Card> {
            val f = Text.fold(filter.trim())
            val shown = if (f.isEmpty()) cards else cards.filter { it.destination == Destination.Liked || Text.fold(it.title).contains(f) }
            return when (sort) {
                LibrarySort.Recent -> shown
                LibrarySort.Name -> shown.sortedBy { Text.fold(it.title) }
            }
        }
    }
}
