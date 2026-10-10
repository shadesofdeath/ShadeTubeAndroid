package com.app.shadetube.spotify

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistPage
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.CardItem
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.catalog.Page
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.catalog.Shelf
import com.app.shadetube.catalog.Track
import com.app.shadetube.spotify.SpJson.arr
import com.app.shadetube.spotify.SpJson.at
import com.app.shadetube.spotify.SpJson.integer
import com.app.shadetube.spotify.SpJson.isArr
import com.app.shadetube.spotify.SpJson.isObj
import com.app.shadetube.spotify.SpJson.isStr
import com.app.shadetube.spotify.SpJson.str
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate

/**
 * The few user-visible names the Spotify layer has to put into models (the C++ used tr()): the backend carries no
 * localized text, so the UI layer sets these from strings.xml ([SpotifyApi.labels]). English defaults.
 */
data class SpotifyLabels(
    /** Name of the Liked Songs pseudo-playlist ("Beğenilen Şarkılar"). */
    val likedSongs: String = "Liked Songs",
    /** Subtitle of a Liked Songs home card ("Çalma listesi"). */
    val playlist: String = "Playlist",
    /** Subtitle of an artist home card ("Sanatçı"). */
    val artist: String = "Artist",
)

data class UserProfile(
    /** spotify:user:<name> */
    val id: String = "",
    /** Display name. */
    val name: String = "",
    val username: String = "",
    val imageUrl: String = "",
) {
    val valid: Boolean get() = id.isNotEmpty()
}

/** One page of a playlist (or Liked Songs) plus, when the response carried it, the playlist header. */
data class PlaylistTracks(
    val page: Page<Track>,
    /** Header (name, description, cover, total, owner, editable); null when the response had none. */
    val meta: Playlist? = null,
    /** Owner display name ("" when unknown / Liked Songs). */
    val ownerName: String = "",
)

/**
 * The JSON -> catalog parsers of SpotifyApi.cpp, field path for field path (pure; unit-tested on fixtures).
 */
object SpotifyParse {
    fun imagesFrom(sources: JsonElement?): List<Image> {
        if (!isArr(sources)) return emptyList()
        val out = ArrayList<Image>()
        for (s in arr(sources)) {
            val url = str(s, "url")
            val w = SpJson.numberAsInt(at(s, "width")) ?: 0
            val h = SpJson.numberAsInt(at(s, "height")) ?: 0
            if (url.isNotEmpty()) out.add(Image(url, w, h))
        }
        return out
    }

    fun artistRefsFrom(artists: JsonElement?): List<ArtistRef> {
        val items: List<JsonElement> = when {
            isObj(artists) && isArr(at(artists, "items")) -> arr(at(artists, "items"))
            isArr(artists) -> arr(artists)
            else -> return emptyList()
        }
        val out = ArrayList<ArtistRef>()
        for (a in items) {
            val d = if (isObj(at(a, "data"))) at(a, "data") else a
            val id = str(d, "uri")
            val name = str(at(d, "profile"), "name").ifEmpty { str(d, "name") }
            if (name.isNotEmpty()) out.add(ArtistRef(id, name))
        }
        return out
    }

    fun dateFrom(date: JsonElement?): String {
        if (!isObj(date)) return ""
        val iso = str(date, "isoString")
        if (iso.length >= 10) return iso.substring(0, 10)
        SpJson.numberAsInt(at(date, "year"))?.let { return it.toString() }
        return ""
    }

    fun trackFrom(d: JsonElement?, albumCtx: AlbumRef? = null): Track {
        var durationMs = 0
        if (isObj(at(d, "trackDuration"))) durationMs = integer(at(d, "trackDuration"), "totalMilliseconds")
        else if (isObj(at(d, "duration"))) durationMs = integer(at(d, "duration"), "totalMilliseconds")
        var playable = true
        if (isObj(at(d, "playability"))) playable = SpJson.bool(at(at(d, "playability"), "playable")) ?: true
        var listenCount = 0L
        at(d, "playcount")?.let { if (isStr(it)) listenCount = SpJson.strOf(it).trim().toLongOrNull() ?: 0L }
        var artists = artistRefsFrom(at(d, "artists"))
        var album = AlbumRef()
        val a = at(d, "albumOfTrack")
        if (isObj(a)) {
            album = AlbumRef(str(a, "uri"), str(a, "name"), imagesFrom(at(at(a, "coverArt"), "sources")))
            if (artists.isEmpty()) artists = artistRefsFrom(at(a, "artists"))
        } else if (albumCtx != null) {
            album = albumCtx
        }
        return Track(
            id = str(d, "uri"),
            name = str(d, "name"),
            durationMs = durationMs,
            trackNumber = integer(d, "trackNumber"),
            playable = playable,
            explicitContent = str(at(d, "contentRating"), "label") == "EXPLICIT",
            listenCount = listenCount,
            artists = artists,
            album = album,
        )
    }

    /**
     * ownerV2.data {username, name} + currentUserCapabilities.canEditItems (libraryV3 items and fetchPlaylist).
     * [me] = the logged-in username (may be empty before me() ran: then nothing counts as owned).
     */
    fun withOwnership(p: Playlist, d: JsonElement?, me: String): Playlist {
        val owner = at(at(d, "ownerV2"), "data")
        var ownerId = str(owner, "username")
        if (ownerId.isEmpty()) ownerId = str(owner, "id")
        if (ownerId.isEmpty()) { // spotify:user:<name>
            val ou = str(owner, "uri")
            if (ou.startsWith("spotify:user:")) ownerId = ou.substring(13)
        }
        val canEdit = SpJson.bool(at(at(d, "currentUserCapabilities"), "canEditItems"))
        var owned = me.isNotEmpty() && ownerId == me
        var editable = canEdit ?: owned
        // "Your Episodes" is a real playlist (format "listen-later") the user owns, but it's the podcast queue: not a
        // music playlist to add songs to, rename or delete.
        if (str(d, "format") == "listen-later") {
            owned = false
            editable = false
        }
        return p.copy(ownerId = ownerId, ownerName = str(owner, "name"), owned = owned, editable = editable)
    }

    fun playlistFrom(d: JsonElement?, me: String = ""): Playlist {
        val items = at(at(d, "images"), "items")
        val images = if (isArr(items) && arr(items).isNotEmpty()) imagesFrom(at(arr(items)[0], "sources")) else emptyList()
        val p = Playlist(id = str(d, "uri"), name = str(d, "name"), description = str(d, "description"), images = images)
        return withOwnership(p, d, me)
    }

    private val isoRegex = Regex("""^\s*([+-]?\d+)-([+-]?\d+)-([+-]?\d+)T([+-]?\d+):([+-]?\d+):([+-]?\d+)""")

    /** "2022-06-14T16:13:04.660Z" -> unix seconds (0 for the epoch placeholder / garbage). */
    fun isoToUnix(iso: String): Long {
        if (iso.length < 19) return 0
        val m = isoRegex.find(iso) ?: return 0
        val (ys, mos, ds, hs, mis, ses) = m.destructured
        val y = ys.toIntOrNull() ?: return 0
        val mo = mos.toIntOrNull() ?: return 0
        val d = ds.toIntOrNull() ?: return 0
        val h = hs.toLongOrNull() ?: return 0
        val mi = mis.toLongOrNull() ?: return 0
        val se = ses.toLongOrNull() ?: return 0
        if (y <= 1970 || mo < 1 || mo > 12 || d < 1 || d > 31) return 0
        // std::chrono::sys_days normalizes an impossible day (Feb 31) into the next month.
        val days = LocalDate.of(y, mo, 1).toEpochDay() + (d - 1)
        return days * 86400L + h * 3600L + mi * 60L + se
    }

    fun lastSegment(uri: String): String {
        val p = uri.lastIndexOf(':')
        return if (p < 0) uri else uri.substring(p + 1)
    }

    fun artistFrom(d: JsonElement?): Artist {
        val avatar = at(at(d, "visuals"), "avatarImage")
        return Artist(
            id = str(d, "uri"),
            name = str(at(d, "profile"), "name").ifEmpty { str(d, "name") },
            images = if (isObj(avatar)) imagesFrom(at(avatar, "sources")) else emptyList(),
        )
    }

    fun albumFrom(a: JsonElement?, withTracks: Boolean): Album {
        val id = str(a, "uri")
        val name = str(a, "name")
        val images = imagesFrom(at(at(a, "coverArt"), "sources"))
        val artists = artistRefsFrom(at(a, "artists"))
        var totalTracks = 0
        val tracks = ArrayList<Track>()
        if (withTracks && isObj(at(a, "tracksV2"))) {
            totalTracks = integer(at(a, "tracksV2"), "totalCount")
            val ctx = AlbumRef(id, name, images)
            val items = at(at(a, "tracksV2"), "items")
            for (it in arr(items)) {
                if (!isObj(at(it, "track"))) continue
                var t = trackFrom(at(it, "track"), ctx)
                if (t.artists.isEmpty()) t = t.copy(artists = artists)
                tracks.add(t)
            }
        }
        if (totalTracks == 0) totalTracks = tracks.size
        return Album(
            id = id,
            name = name,
            primaryType = str(a, "type"),
            label = str(a, "label"),
            firstReleaseDate = dateFrom(at(a, "date")),
            images = images,
            artists = artists,
            totalTracks = totalTracks,
            tracks = tracks,
        )
    }

    // ---- whole responses -----------------------------------------------------------------------------------------

    /** profileAttributes `data` -> profile (avatar: the last = largest source). */
    fun parseProfile(data: JsonElement?): UserProfile {
        val p = at(at(data, "me"), "profile")
        val src = at(at(p, "avatar"), "sources")
        val imageUrl = if (isArr(src) && arr(src).isNotEmpty()) str(arr(src).last(), "url") else "" // largest last
        return UserProfile(id = str(p, "uri"), name = str(p, "name"), username = str(p, "username"), imageUrl = imageUrl)
    }

    /** One libraryV3 page of the "Playlists" filter. */
    data class LibraryPage(
        val playlists: List<Playlist>,
        /** Raw item count (for paging). */
        val rawCount: Int,
        val totalCount: Int,
    )

    /**
     * libraryV3 (Playlists) page. Pseudo-playlists (Liked Songs, Your Episodes) carry their count; real playlists get
     * countKnown = false (libraryV3 carries no track count; filled from the rootlist). Folders are flattened away and
     * unknown item types skipped. Returns null when there are no items (end of paging).
     */
    fun parseLibraryPlaylistsPage(data: JsonElement?, me: String, labels: SpotifyLabels = SpotifyLabels()): LibraryPage? {
        val lib = at(at(data, "me"), "libraryV3")
        val items = at(lib, "items")
        if (!isArr(items) || arr(items).isEmpty()) return null
        val out = ArrayList<Playlist>()
        for (entry in arr(items)) {
            val wrap = at(entry, "item")
            val d = at(wrap, "data")
            if (!isObj(d)) continue
            val tn = str(wrap, "__typename")
            val p: Playlist
            if (tn.contains("PseudoPlaylist") || str(d, "__typename") == "PseudoPlaylist") {
                val id = str(d, "uri")
                p = Playlist(
                    id = id,
                    // Shown as is in the sidebar / library: the app's name for it in the UI language.
                    name = if (id == LIKED_SONGS_URI) labels.likedSongs else str(d, "name"),
                    totalTracks = integer(d, "count"),
                    images = imagesFrom(at(at(d, "image"), "sources")),
                    ownerId = me, // Liked Songs / Your Episodes: the user's, but not editable as a playlist
                )
            } else {
                if (str(d, "__typename") != "Playlist") continue // folders are flattened away; skip unknowns
                p = playlistFrom(d, me).copy(countKnown = false)
            }
            out.add(p)
        }
        return LibraryPage(out, arr(items).size, integer(lib, "totalCount"))
    }

    /**
     * Track counts from the rootlist decorated with `length`: metaItems runs parallel to items; should the folder
     * markers ever come without one, pair the metas with the other items only. proto3 JSON omits a zero length.
     */
    fun parseRootlistLengths(root: JsonElement?): Map<String, Int> {
        val items = at(at(root, "contents"), "items")
        val metas = at(at(root, "contents"), "metaItems")
        val lengths = HashMap<String, Int>()
        if (!isArr(items) || !isArr(metas)) return lengths
        val itemList = arr(items)
        val metaList = arr(metas)
        val rows = ArrayList<Int>()
        for (i in itemList.indices) {
            if (metaList.size == itemList.size || str(itemList[i], "uri").startsWith("spotify:playlist:")) rows.add(i)
        }
        if (rows.size == metaList.size) {
            for (k in rows.indices) {
                val uri = str(itemList[rows[k]], "uri")
                if (uri.startsWith("spotify:playlist:") && isObj(metaList[k])) lengths[uri] = integer(metaList[k], "length")
            }
        }
        return lengths
    }

    /** The playlist URIs in a rootlist (folders flattened), in order. */
    fun rootlistPlaylists(root: JsonElement?): List<String> =
        arr(at(at(root, "contents"), "items")).map { str(it, "uri") }.filter { it.startsWith("spotify:playlist:") }

    fun parseLibraryAlbums(data: JsonElement?): List<Album> =
        arr(at(at(at(data, "me"), "libraryV3"), "items")).mapNotNull { entry ->
            val d = at(at(entry, "item"), "data")
            if (isObj(d) && str(d, "uri").isNotEmpty()) albumFrom(d, false) else null
        }

    fun parseLibraryArtists(data: JsonElement?): List<Artist> =
        arr(at(at(at(data, "me"), "libraryV3"), "items")).mapNotNull { entry ->
            val d = at(at(entry, "item"), "data")
            if (isObj(d) && str(d, "uri").isNotEmpty()) artistFrom(d) else null
        }

    /**
     * fetchLibraryTracks `data` (data.me.library.tracks) -> Liked Songs page. Skipped rows still take an offset:
     * nextOffset = offset + raw item count.
     */
    fun parseSavedTracks(data: JsonElement?, offset: Int, labels: SpotifyLabels = SpotifyLabels()): PlaylistTracks {
        val tracks = at(at(at(data, "me"), "library"), "tracks")
        val total = integer(tracks, "totalCount")
        val items = at(tracks, "items")
        val out = ArrayList<Track>()
        for (it in arr(items)) {
            val tw = at(it, "track") // { _uri, data:{...Track} }  — uri lives on the wrapper
            val d = at(tw, "data")
            if (!isObj(d)) continue
            var t = trackFrom(d)
            if (t.id.isEmpty()) t = t.copy(id = str(tw, "_uri"))
            if (t.id.isEmpty()) continue
            out.add(t.copy(addedAt = isoToUnix(str(at(it, "addedAt"), "isoString"))))
        }
        val raw = if (isArr(items)) arr(items).size else 0 // skipped rows still take an offset
        val next = offset + raw
        val page = Page(items = out, total = total, offset = offset, hasMore = raw > 0 && next < total, nextOffset = next)
        val meta = Playlist(id = LIKED_SONGS_URI, name = labels.likedSongs, totalTracks = total)
        return PlaylistTracks(page, meta, "")
    }

    /** fetchPlaylist `data` -> page (+ header). Playlist tracks carry their row `uid` (removeFromPlaylist needs it). */
    fun parsePlaylistTracks(data: JsonElement?, uri: String, offset: Int, me: String): PlaylistTracks {
        val pv2 = at(data, "playlistV2")
        var meta: Playlist? = null
        if (isObj(pv2)) {
            val imgItems = at(at(pv2, "images"), "items")
            var p = Playlist(
                id = str(pv2, "uri").ifEmpty { uri },
                name = str(pv2, "name"),
                description = plainText(str(pv2, "description")), // the header shows text, not HTML
                images = if (isArr(imgItems) && arr(imgItems).isNotEmpty()) imagesFrom(at(arr(imgItems)[0], "sources")) else emptyList(),
            )
            p = withOwnership(p, pv2, me)
            meta = p.copy(totalTracks = integer(at(pv2, "content"), "totalCount"), countKnown = isObj(at(pv2, "content")))
        }
        val ownerName = str(at(at(pv2, "ownerV2"), "data"), "name").ifEmpty { str(at(pv2, "owner"), "name") }

        val content = at(pv2, "content")
        if (isObj(content)) {
            val total = integer(content, "totalCount")
            val items = at(content, "items")
            if (isArr(items)) {
                val out = ArrayList<Track>()
                for (it in arr(items)) {
                    val d = at(at(it, "itemV2"), "data")
                    if (!isObj(d) || str(d, "__typename") != "Track") continue
                    out.add(
                        trackFrom(d).copy(
                            uid = str(it, "uid"), // the row id removeFromPlaylist needs
                            addedAt = isoToUnix(str(at(it, "addedAt"), "isoString")),
                        ),
                    )
                }
                // Skipped rows (episodes, unavailable items) still occupy server offsets: page on from the raw count,
                // or the next page would repeat rows (and a duplicated row uid would be "removed" twice locally).
                val raw = arr(items).size
                val next = offset + raw
                return PlaylistTracks(Page(out, total, offset, raw > 0 && next < total, next), meta, ownerName)
            }
            return PlaylistTracks(Page(emptyList(), total, offset, offset < total), meta, ownerName)
        }
        if (isObj(at(at(data, "me"), "library"))) {
            // savedTracks-style shape (data.me.library.tracks)
            val tracks = at(at(at(data, "me"), "library"), "tracks")
            val total = integer(tracks, "totalCount")
            val out = ArrayList<Track>()
            for (it in arr(at(tracks, "items"))) {
                val d = at(at(it, "track"), "data")
                if (isObj(d)) out.add(trackFrom(d))
            }
            return PlaylistTracks(Page(out, total, offset, offset + out.size < total), meta, ownerName)
        }
        return PlaylistTracks(Page(emptyList(), 0, offset, false), meta, ownerName)
    }

    /** getAlbum `data` -> album + tracklist; null when there is no albumUnion. */
    fun parseAlbum(data: JsonElement?): Album? {
        val a = at(data, "albumUnion")
        return if (isObj(a)) albumFrom(a, true) else null
    }

    fun parseSearch(data: JsonElement?): SearchResults {
        val s = at(data, "searchV2")
        val artists = arr(at(at(s, "artists"), "items")).mapNotNull { if (isObj(at(it, "data"))) artistFrom(at(it, "data")) else null }
        val albums = arr(at(at(s, "albumsV2"), "items")).mapNotNull { if (isObj(at(it, "data"))) albumFrom(at(it, "data"), false) else null }
        val tracks = arr(at(at(s, "tracksV2"), "items")).mapNotNull {
            val d = at(at(it, "item"), "data")
            if (isObj(d)) trackFrom(d) else null
        }
        return SearchResults(artists = artists, albums = albums, tracks = tracks)
    }

    /** queryArtistOverview `data` -> top tracks, popular releases (albums) and related artists. */
    fun parseArtistPage(data: JsonElement?): ArtistPage {
        val a = at(data, "artistUnion")
        val stats = at(a, "stats")
        val artist = Artist(
            id = str(a, "uri"),
            name = str(at(a, "profile"), "name"),
            images = imagesFrom(at(at(at(a, "visuals"), "avatarImage"), "sources")),
            listenCount = if (isObj(stats)) integer(stats, "monthlyListeners").toLong() else 0L,
        )
        // Top tracks
        val top = arr(at(at(at(a, "discography"), "topTracks"), "items")).mapNotNull { it ->
            val d = if (isObj(at(it, "track"))) at(it, "track") else at(it, "data")
            if (isObj(d) && str(d, "uri").isNotEmpty()) trackFrom(d) else null
        }
        // Albums (popular releases)
        val albums = arr(at(at(at(a, "discography"), "popularReleasesAlbums"), "items")).mapNotNull {
            if (str(it, "uri").isNotEmpty()) albumFrom(it, false) else null
        }
        // Related artists
        val similar = arr(at(at(at(a, "relatedContent"), "relatedArtists"), "items")).mapNotNull { it ->
            val d = if (isObj(at(it, "data"))) at(it, "data") else it
            if (str(d, "uri").isNotEmpty()) artistFrom(d) else null
        }
        return ArtistPage(artist = artist, topTracks = top, albums = albums, similar = similar)
    }

    /** areEntitiesInLibrary: data.lookup[] of { _uri, data: { saved: bool } } (parsed defensively), parallel to the ids. */
    fun parseTracksSaved(data: JsonElement?, count: Int): List<Boolean> {
        val out = BooleanArray(count)
        val lookup = arr(at(data, "lookup"))
        for (i in 0 until minOf(count, lookup.size)) {
            val e = lookup[i]
            val sv = SpJson.bool(at(at(e, "data"), "saved"))
            if (sv != null) out[i] = sv
            else SpJson.bool(at(e, "saved"))?.let { out[i] = it }
        }
        return out.toList()
    }

    /** inspiredby-mix seed_to_playlist: {"mediaItems":[{"uri":"spotify:playlist:37i9dQZF1E..."}]} -> the first playlist. */
    fun parseRadio(j: JsonElement?): String =
        arr(at(j, "mediaItems")).map { str(it, "uri") }.firstOrNull { it.startsWith("spotify:playlist:") } ?: ""

    // ---- Home feed -------------------------------------------------------------------------------------------------

    private fun appendCodePoint(sb: StringBuilder, cp: Long): Boolean {
        if (cp == 0L || cp > 0x10FFFF || (cp in 0xD800..0xDFFF)) return false
        sb.appendCodePoint(cp.toInt())
        return true
    }

    /** Playlist descriptions carry HTML (<a href="spotify:artist:...">Name</a>, &amp;, &#x27;). Cards want one plain line. */
    fun plainText(html: String): String {
        val out = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val ch = html[i]
            if (ch == '<') {
                val end = html.indexOf('>', i)
                if (end >= 0) {
                    i = end + 1
                    continue
                } // an unmatched '<' ("Duman <3") is literal text, not a tag
            }
            if (ch == '&') {
                val end = html.indexOf(';', i)
                if (end >= 0 && end - i >= 2 && end - i <= 10) {
                    val ent = html.substring(i + 1, end)
                    val rep = StringBuilder()
                    when (ent) {
                        "amp" -> rep.append('&')
                        "quot" -> rep.append('"')
                        "apos" -> rep.append('\'')
                        "lt" -> rep.append('<')
                        "gt" -> rep.append('>')
                        "nbsp" -> rep.append(' ')
                        else -> if (ent.length > 1 && ent[0] == '#') {
                            val hex = ent[1] == 'x' || ent[1] == 'X'
                            val digits = ent.substring(if (hex) 2 else 1)
                            val cp = if (digits.isNotEmpty() && digits.all { Character.digit(it, if (hex) 16 else 10) >= 0 })
                                digits.toBigInteger(if (hex) 16 else 10).let { if (it.bitLength() > 62) -1L else it.toLong() }
                            else null
                            if (cp != null && cp >= 0) appendCodePoint(rep, cp)
                        }
                    }
                    if (rep.isNotEmpty()) {
                        out.append(rep)
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(ch)
            ++i
        }
        // Collapse runs of whitespace (descriptions contain newlines) and trim.
        val clean = StringBuilder(out.length)
        var space = false
        for (c in out) {
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                space = clean.isNotEmpty()
                continue
            }
            if (space) clean.append(' ')
            space = false
            clean.append(c)
        }
        return clean.toString()
    }

    /** {transformedLabel|text} or a plain string. A template-only base text ("Made For {0}") is useless alone. */
    fun labelText(j: JsonElement?): String {
        if (isStr(j)) return SpJson.strOf(j)
        var s = str(j, "transformedLabel")
        if (s.isEmpty()) s = str(j, "text")
        if (s.isEmpty()) {
            s = str(j, "translatedBaseText")
            if (s.contains('{')) s = ""
        }
        return s
    }

    /** Liked Songs shows up in the home feed as an untyped "spotify:user:<name|@>:collection" entry. */
    fun isLikedSongsUri(uri: String): Boolean {
        if (uri == LIKED_SONGS_URI) return true
        val user = "spotify:user:"
        val tail = ":collection"
        return uri.length > user.length + tail.length && uri.startsWith(user) && uri.endsWith(tail)
    }

    private fun artistNames(artists: List<ArtistRef>, max: Int = 2): String =
        artists.take(max).joinToString(", ") { it.name }

    /**
     * One section item -> card. Null for content the app can't show (podcasts, episodes, shows, audiobooks,
     * unavailable / restricted entries, unknown types).
     */
    fun homeCard(item: JsonElement?, labels: SpotifyLabels = SpotifyLabels()): CardItem? {
        val content = at(item, "content")
        val d = at(content, "data")
        val uri = str(item, "uri").ifEmpty { str(content, "uri") }
        var tn = if (isObj(d)) str(d, "__typename") else ""
        if (tn.isEmpty() && isObj(d)) { // fall back to the wrapper: "PlaylistResponseWrapper" -> "Playlist"
            tn = str(content, "__typename")
            val w = tn.indexOf("ResponseWrapper")
            tn = if (w < 0) "" else tn.substring(0, w)
        }

        if (isLikedSongsUri(uri) || isLikedSongsUri(str(d, "uri"))) {
            return CardItem(
                kind = CardItem.Kind.Playlist,
                id = LIKED_SONGS_URI,
                title = labels.likedSongs,
                images = if (isObj(d)) imagesFrom(at(at(d, "image"), "sources")) else emptyList(),
                subtitle = labels.playlist,
            )
        }
        val card = when (tn) {
            "Playlist" -> {
                val p = playlistFrom(d)
                CardItem(
                    kind = CardItem.Kind.Playlist,
                    id = p.id.ifEmpty { uri },
                    title = p.name,
                    images = p.images,
                    subtitle = plainText(p.description).ifEmpty { str(at(at(d, "ownerV2"), "data"), "name") },
                )
            }
            "Album" -> {
                val a = albumFrom(d, false)
                CardItem(CardItem.Kind.Album, a.id.ifEmpty { uri }, a.name, artistNames(a.artists), a.images)
            }
            "Artist" -> {
                val ar = artistFrom(d)
                CardItem(CardItem.Kind.Artist, ar.id.ifEmpty { uri }, ar.name, labels.artist, ar.images)
            }
            "Track" -> {
                var t = trackFrom(d)
                if (t.id.isEmpty()) t = t.copy(id = uri)
                CardItem(CardItem.Kind.Track, t.id, t.name, t.artistLine, t.album.images, t)
            }
            else -> return null // Episode, Podcast/Show, Audiobook, NotFound, RestrictedContent, UnknownType...
        }
        return if (card.id.isNotEmpty() && card.title.isNotEmpty() && card.id.startsWith("spotify:")) card else null
    }

    fun homeSections(data: JsonElement?): List<JsonElement> =
        arr(at(at(at(at(data, "home"), "sectionContainer"), "sections"), "items"))

    /**
     * Response shape (2026-09): data.home.sectionContainer.sections.items[] of
     *   { uri, data:{ __typename:"HomeGenericSectionData"|"HomeRecentlyPlayedSectionData"|"HomeShortsSectionData"...,
     *                 title:{transformedLabel}, subtitle:{transformedLabel} },
     *     sectionItems:{ totalCount, items:[ { uri, content:{ __typename:"PlaylistResponseWrapper"|..., data:{...} } } ] } }
     */
    fun parseHome(data: JsonElement?, labels: SpotifyLabels = SpotifyLabels()): List<Shelf> {
        val out = ArrayList<Shelf>()
        for (s in homeSections(data)) {
            val sd = at(s, "data")
            val title = labelText(at(sd, "title"))
            // The untitled "shorts" grid only repeats "Jump back in" / "Recently played" items.
            if (title.isEmpty()) continue
            val sub = labelText(at(sd, "subtitle"))
            val label = if (sub.codePointCount(0, sub.length) <= 26) sub else "" // longer blurbs overflow the mono header label
            val seen = HashSet<String>()
            val items = ArrayList<CardItem>()
            for (it in arr(at(at(s, "sectionItems"), "items"))) {
                val card = homeCard(it, labels) ?: continue
                if (seen.add(card.id)) items.add(card)
            }
            if (items.isNotEmpty()) out.add(Shelf(title = title, label = label, items = items))
        }
        return out
    }

    // ---- Track metadata (spclient metadata/4) ---------------------------------------------------------------------

    private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** Spotify ids: the 22-character base62 id -> the 32-hex-digit "gid" the metadata service uses. "" on invalid input. */
    fun gidFromId(base62: String): String {
        if (base62.length != 22) return ""
        val n = IntArray(16)
        for (c in base62) {
            val digit = BASE62.indexOf(c)
            if (digit < 0) return ""
            var carry = digit
            for (i in 15 downTo 0) {
                val v = n[i] * 62 + carry
                n[i] = v and 0xFF
                carry = v ushr 8
            }
            if (carry != 0) return "" // more than 128 bits
        }
        val hex = "0123456789abcdef"
        val sb = StringBuilder(32)
        for (b in n) sb.append(hex[b shr 4]).append(hex[b and 15])
        return sb.toString()
    }

    /** The 32-hex-digit gid -> the 22-character base62 id. "" on invalid input. */
    fun idFromGid(hexGid: String): String {
        if (hexGid.length != 32) return ""
        val n = IntArray(16)
        for (i in 0 until 32) {
            val v = Character.digit(hexGid[i], 16)
            if (v < 0 || hexGid[i].code > 127) return ""
            n[i / 2] = ((n[i / 2] shl 4) or v) and 0xFF
        }
        val out = CharArray(22) { '0' }
        for (pos in 21 downTo 0) {
            var rem = 0
            for (k in n.indices) {
                val cur = (rem shl 8) or n[k]
                n[k] = cur / 62
                rem = cur % 62
            }
            out[pos] = BASE62[rem]
        }
        return String(out)
    }

    /** `field(j, snake, camel)`: the snake_case member, else the camelCase one. */
    private fun field(j: JsonElement?, snake: String, camel: String): JsonElement? = at(j, snake) ?: at(j, camel)

    private fun uriFromGid(j: JsonElement?, kind: String): String {
        val id = idFromGid(str(j, "gid"))
        return if (id.isEmpty()) "" else "spotify:$kind:$id"
    }

    /**
     * The metadata service's track JSON -> Track: {gid, name, artist:[{gid,name}], album:{gid, name, artist,
     * cover_group:{image:[{file_id, size, width, height}]}}, duration, number, explicit, ...}. Field names are
     * accepted in snake_case and camelCase. Empty id when the JSON has no usable gid.
     */
    fun parseTrackMetadata(j: JsonElement?): Track {
        val id = uriFromGid(j, "track")
        if (id.isEmpty()) return Track()
        fun artists(a: JsonElement?): List<ArtistRef> =
            arr(a).filter { str(it, "name").isNotEmpty() }.map { ArtistRef(uriFromGid(it, "artist"), str(it, "name")) }
        var trackArtists = artists(at(j, "artist"))
        var album = AlbumRef()
        val al = at(j, "album")
        if (isObj(al)) {
            val images = ArrayList<Image>()
            for (im in arr(at(field(al, "cover_group", "coverGroup"), "image"))) {
                val hexId = str(im, "file_id").ifEmpty { str(im, "fileId") }
                if (hexId.isEmpty()) continue
                images.add(Image("https://i.scdn.co/image/" + hexId.lowercase(), integer(im, "width"), integer(im, "height")))
            }
            album = AlbumRef(uriFromGid(al, "album"), str(al, "name"), images)
            if (trackArtists.isEmpty()) trackArtists = artists(at(al, "artist"))
        }
        return Track(
            id = id,
            name = str(j, "name"),
            durationMs = integer(j, "duration"),
            trackNumber = integer(j, "number"),
            explicitContent = SpJson.bool(at(j, "explicit")) ?: false,
            artists = trackArtists,
            album = album,
        )
    }
}
