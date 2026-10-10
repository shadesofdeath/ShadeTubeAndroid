package com.app.shadetube.playback.service

import android.os.Bundle
import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Log
import com.app.shadetube.playback.PlayContext
import com.app.shadetube.playback.PlaybackModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer

/**
 * What voice search reads from the catalog; AppGraph wires library.CatalogSource (Spotify while logged in, else
 * MusicBrainz). Main-safe suspend functions that throw on failure.
 */
interface VoiceCatalog {
    suspend fun search(query: String): SearchResults
    suspend fun album(id: String): Album
    suspend fun artistTopTracks(artistId: String): List<Track>

    companion object {
        fun of(
            searchFn: suspend (String) -> SearchResults,
            albumFn: suspend (String) -> Album,
            topTracksFn: suspend (String) -> List<Track>,
        ): VoiceCatalog = object : VoiceCatalog {
            override suspend fun search(query: String) = searchFn(query)
            override suspend fun album(id: String) = albumFn(id)
            override suspend fun artistTopTracks(artistId: String) = topTracksFn(artistId)
        }
    }
}

/**
 * A "play X" request: Google Assistant / Android Auto voice (MediaSession playFromSearch -> a MediaItem whose
 * RequestMetadata carries the query and extras) or a MEDIA_PLAY_FROM_SEARCH intent. The structured extras are the
 * MediaStore ones (EXTRA_MEDIA_FOCUS, EXTRA_MEDIA_TITLE / ARTIST / ALBUM / PLAYLIST / GENRE).
 */
data class VoiceRequest(
    /** The spoken text, cleaned ("on ShadeTube" removed). Empty = "play some music": resume / something sensible. */
    val query: String,
    val focus: Focus = Focus.Any,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playlist: String = "",
    val genre: String = "",
) {
    enum class Focus { Any, Song, Album, Artist, Playlist, Genre }

    val isEmpty: Boolean
        get() = query.isBlank() && title.isBlank() && artist.isBlank() && album.isBlank() && playlist.isBlank() && genre.isBlank()

    /** The text to search the catalog with (the structured fields when the focus names them). */
    val searchText: String
        get() {
            fun join(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(" ")
            val structured = when (focus) {
                Focus.Song -> join(title, artist)
                Focus.Album -> join(album, artist)
                Focus.Artist -> artist
                Focus.Playlist -> playlist
                Focus.Genre -> genre
                Focus.Any -> ""
            }
            return structured.ifBlank { query.ifBlank { join(title, artist, album, playlist, genre) } }
        }

    companion object {
        // android.provider.MediaStore constants (literals: unit tests run without the framework).
        const val EXTRA_FOCUS = "android.intent.extra.focus"
        const val EXTRA_TITLE = "android.intent.extra.title"
        const val EXTRA_ARTIST = "android.intent.extra.artist"
        const val EXTRA_ALBUM = "android.intent.extra.album"
        const val EXTRA_PLAYLIST = "android.intent.extra.playlist"
        const val EXTRA_GENRE = "android.intent.extra.genre"

        /** The search intent's query extra (SearchManager.QUERY). */
        const val EXTRA_QUERY = "query"

        fun of(query: String?, extras: Bundle?): VoiceRequest = of(query) { key -> extras?.getString(key) }

        fun of(query: String?, extra: (String) -> String?): VoiceRequest {
            val focus = when (extra(EXTRA_FOCUS)) {
                "vnd.android.cursor.item/audio" -> Focus.Song
                "vnd.android.cursor.item/album" -> Focus.Album
                "vnd.android.cursor.item/artist" -> Focus.Artist
                "vnd.android.cursor.item/playlist" -> Focus.Playlist
                "audio/genre", "vnd.android.cursor.item/genre" -> Focus.Genre
                else -> Focus.Any
            }
            return VoiceRequest(
                query = cleanQuery(query.orEmpty()),
                focus = focus,
                title = extra(EXTRA_TITLE).orEmpty().trim(),
                artist = extra(EXTRA_ARTIST).orEmpty().trim(),
                album = extra(EXTRA_ALBUM).orEmpty().trim(),
                playlist = extra(EXTRA_PLAYLIST).orEmpty().trim(),
                genre = extra(EXTRA_GENRE).orEmpty().trim(),
            )
        }

        private val appSuffix = Regex(
            """\s+(?:(?:on|in|with|from|using)\s+shade\s*tube|shade\s*tube(?:['’]?(?:da|de|ta|te|dan|den|tan|ten|la|le|yla|yle))?(?:\s+ile)?)\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val verbs = Regex("""^(?:play|listen to)\s+|\s+(?:çal|oynat|dinle|aç)$""", RegexOption.IGNORE_CASE)

        /** "Tarkan on ShadeTube" / "ShadeTube'da Tarkan çal" leftovers removed; whitespace collapsed. */
        fun cleanQuery(raw: String): String {
            var q = raw.trim().replace(Regex("""\s+"""), " ")
            q = q.replace(appSuffix, "")
            q = q.replace(Regex("""^shade\s*tube(?:['’]?(?:da|de|ta|te))?\s+""", RegexOption.IGNORE_CASE), "")
            q = q.replace(verbs, "")
            return q.trim()
        }
    }
}

/**
 * Picks what a voice request plays from catalog search results (pure, unit tested). "Tarkan" plays the artist,
 * "Şımarık Tarkan" the song, an album's exact name the album; otherwise the best scoring song (title / artist words of
 * the query, the catalog's own order as the tiebreak, live / karaoke / remix versions only when asked for).
 */
object VoiceSearchPicker {
    sealed interface Pick {
        /** [index] into the results' tracks. */
        data class Song(val index: Int) : Pick
        data class AlbumPick(val album: Album) : Pick
        data class ArtistPick(val artist: Artist) : Pick
        data class PlaylistPick(val playlist: Playlist) : Pick
    }

    private val versionWords = setOf(
        "live", "canli", "karaoke", "instrumental", "enstrumantal", "remix", "cover", "nightcore", "sped", "slowed",
        "reverb", "8d", "demo",
    )

    fun pick(req: VoiceRequest, r: SearchResults): Pick? {
        val q = tokens(req.searchText)
        if (q.isEmpty()) return null
        val qJoined = q.joinToString(" ")
        when (req.focus) {
            VoiceRequest.Focus.Artist ->
                best(tokens(req.artist.ifBlank { req.searchText }), r.artists, 0.0) { it.name }?.let { return Pick.ArtistPick(it) }
            VoiceRequest.Focus.Album -> bestAlbum(req, r.albums)?.let { return Pick.AlbumPick(it) }
            VoiceRequest.Focus.Playlist ->
                best(tokens(req.playlist.ifBlank { req.searchText }), r.playlists, 0.5) { it.name }?.let { return Pick.PlaylistPick(it) }
            else -> Unit
        }
        val bestTrack = bestTrack(req, r.tracks)
        val exactTitle = bestTrack >= 0 && normalize(r.tracks[bestTrack].name) == qJoined
        if (req.focus == VoiceRequest.Focus.Any) {
            // The query is an artist's name: that artist (unless a song is called exactly that by someone else).
            val artist = r.artists.firstOrNull { normalize(it.name) == qJoined }
            if (artist != null && (!exactTitle || r.tracks[bestTrack].artists.any { normalize(it.name) == qJoined })) {
                return Pick.ArtistPick(artist)
            }
            val album = r.albums.firstOrNull { a ->
                val name = normalize(a.name)
                val by = normalize(a.artists.joinToString(" ") { it.name })
                name == qJoined || (by.isNotEmpty() && ("$name $by" == qJoined || "$by $name" == qJoined))
            }
            if (album != null && !exactTitle) return Pick.AlbumPick(album)
        }
        if (bestTrack >= 0) return Pick.Song(bestTrack)
        r.artists.firstOrNull()?.let { return Pick.ArtistPick(it) }
        r.albums.firstOrNull()?.let { return Pick.AlbumPick(it) }
        return null
    }

    /** Index of the best playable track for [req] in [tracks]; -1 = none. */
    fun bestTrack(req: VoiceRequest, tracks: List<Track>): Int {
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        tracks.forEachIndexed { i, t ->
            if (!t.playable || t.name.isEmpty()) return@forEachIndexed
            val s = trackScore(req, t, i)
            if (s > bestScore) {
                bestScore = s
                best = i
            }
        }
        return best
    }

    fun trackScore(req: VoiceRequest, t: Track, rank: Int): Double {
        val name = tokens(t.name)
        val artist = tokens(t.artistLine)
        val q = tokens(req.searchText)
        var s = if (req.title.isNotBlank() || (req.artist.isNotBlank() && req.focus == VoiceRequest.Focus.Song)) {
            val ts = if (req.title.isNotBlank()) similarity(tokens(req.title), name) else 0.5
            val ars = if (req.artist.isNotBlank()) similarity(tokens(req.artist), artist) else 0.5
            0.65 * ts + 0.35 * ars
        } else {
            val coverage = covered(q, name + artist) // the song explains the words of the query ...
            val title = covered(name, q) // ... and its title is in the query
            var v = 0.55 * coverage + 0.45 * title
            if (name.joinToString(" ") == q.joinToString(" ")) v += 0.1
            v
        }
        if (name.any { it in versionWords && it !in q }) s -= 0.15
        return s - rank * 0.01
    }

    private fun bestAlbum(req: VoiceRequest, albums: List<Album>): Album? {
        if (albums.isEmpty()) return null
        val want = tokens(req.album.ifBlank { req.searchText })
        val by = tokens(req.artist)
        return albums.withIndex().maxByOrNull { (i, a) ->
            val s = similarity(want, tokens(a.name))
            val a2 = if (by.isEmpty()) 0.0 else similarity(by, tokens(a.artists.joinToString(" ") { it.name }))
            s + 0.5 * a2 - i * 0.01
        }?.value
    }

    /** The item whose name is most like [want] (catalog order breaks ties); null below [minScore]. */
    private fun <T> best(want: List<String>, items: List<T>, minScore: Double, name: (T) -> String): T? =
        items.withIndex()
            .map { (i, it) -> it to (similarity(want, tokens(name(it))) - i * 0.01) }
            .maxByOrNull { it.second }
            ?.takeIf { minScore <= 0.0 || it.second >= minScore - 0.05 }
            ?.first

    /** Average of both coverages: 1 = same words, 0 = nothing in common. */
    fun similarity(a: List<String>, b: List<String>): Double = (covered(a, b) + covered(b, a)) / 2

    /** Fraction of [a]'s words that appear in [b]. */
    fun covered(a: List<String>, b: List<String>): Double {
        if (a.isEmpty()) return 0.0
        val set = b.toHashSet()
        return a.count { it in set }.toDouble() / a.size
    }

    fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /**
     * Lower case, Turkish dotted / dotless i folded to i, accents removed (ş -> s, ö -> o, é -> e), apostrophes dropped,
     * everything else that is not a letter or digit turned into single spaces. Speech recognizers and catalogs disagree
     * on all of these.
     */
    fun normalize(s: String): String {
        val folded = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                'İ', 'I', 'ı' -> folded.append('i')
                else -> folded.append(c.lowercaseChar())
            }
        }
        val decomposed = Normalizer.normalize(folded, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var space = true
        for (c in decomposed) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c == '\'' || c == '’' -> Unit
                c.isLetterOrDigit() -> {
                    out.append(c)
                    space = false
                }
                !space -> {
                    out.append(' ')
                    space = true
                }
            }
        }
        return out.toString().trim()
    }
}

/** What to play for a voice request: [startIndex] -1 = no particular song (the player's header-play rule). */
data class VoicePlay(val tracks: List<Track>, val startIndex: Int, val context: PlayContext)

/**
 * Voice search / "play from search" (Assistant, Android Auto, MEDIA_PLAY_FROM_SEARCH): resolves a [VoiceRequest]
 * through [catalog] into a list to play with its context — the best song with its album (else the search results),
 * an artist's top songs, an album, or one of the user's own playlists named like the query. An empty request resumes
 * the last session, else plays Liked Songs, else the recently played songs.
 */
object VoiceSearch {
    private const val TAG = "player"
    private const val SEARCH_TIMEOUT_MS = 12_000L
    private const val DETAIL_TIMEOUT_MS = 6_000L
    private const val LOCAL_LIKED = "liked" // app.AppBrowseSource.LOCAL_LIKED (Liked Songs while logged out)

    /** Installed by AppGraph (null = no catalog: only the user's own playlists / the fallback work). */
    @Volatile
    var catalog: VoiceCatalog? = null

    /** Plays [req] on the player (main thread): true when something started. Used by [PlayFromSearchActivity]. */
    suspend fun play(module: PlaybackModule, req: VoiceRequest): Boolean {
        val p = module.player
        if (req.isEmpty) {
            if (p.currentTrack == null) p.restoreSession()
            if (p.currentTrack != null) {
                p.play()
                return true
            }
        }
        val play = resolve(req, module.browseTree.source) ?: return false
        p.playContext(play.tracks, play.startIndex, play.context)
        return true
    }

    /** The list to play for [req]; null = nothing found (or the search failed). */
    suspend fun resolve(req: VoiceRequest, browse: BrowseSource?): VoicePlay? = try {
        if (req.isEmpty) {
            fallback(browse)
        } else {
            ownPlaylist(req, browse) ?: withTimeoutOrNull(SEARCH_TIMEOUT_MS) { searchPlay(req) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "voice search \"${req.searchText}\" failed: ${e.message}")
        null
    }

    /** Liked Songs, else the recently played songs. */
    suspend fun fallback(browse: BrowseSource?): VoicePlay? {
        val src = browse ?: return null
        val liked = runCatching { src.playlists() }.getOrDefault(emptyList()).firstOrNull { it.id == LIKED_SONGS_URI || it.id == LOCAL_LIKED }
        if (liked != null) {
            val tracks = playable(runCatching { src.playlistTracks(liked.id) }.getOrDefault(emptyList()))
            if (tracks.isNotEmpty()) return VoicePlay(tracks, -1, PlayContext("liked", ""))
        }
        val recent = playable(runCatching { src.recentlyPlayed(BrowseTree.RECENT_LIMIT) }.getOrDefault(emptyList()))
        return if (recent.isEmpty()) null else VoicePlay(recent, 0, PlayContext("history", ""))
    }

    /** One of the user's playlists (Liked Songs included) named exactly like the request. */
    private suspend fun ownPlaylist(req: VoiceRequest, browse: BrowseSource?): VoicePlay? {
        val src = browse ?: return null
        if (req.focus != VoiceRequest.Focus.Any && req.focus != VoiceRequest.Focus.Playlist) return null
        val want = VoiceSearchPicker.normalize(req.playlist.ifBlank { req.query })
        if (want.isEmpty()) return null
        val pl = runCatching { src.playlists() }.getOrDefault(emptyList()).firstOrNull { VoiceSearchPicker.normalize(it.name) == want }
            ?: return null
        val tracks = playable(src.playlistTracks(pl.id))
        if (tracks.isEmpty()) return null
        val ctx = if (pl.id == LIKED_SONGS_URI || pl.id == LOCAL_LIKED) PlayContext("liked", "") else PlayContext("playlist:${pl.id}", pl.name)
        return VoicePlay(tracks, -1, ctx)
    }

    private suspend fun searchPlay(req: VoiceRequest): VoicePlay? {
        val cat = catalog ?: return null
        val text = req.searchText
        val t0 = System.currentTimeMillis()
        val results = cat.search(text)
        val searchCtx = PlayContext("search", text)
        fun fromResults(index: Int): VoicePlay? {
            val chosen = results.tracks.getOrNull(index)
            val list = playable(results.tracks)
            if (list.isEmpty()) return null
            return VoicePlay(list, chosen?.let { list.indexOf(it) } ?: 0, searchCtx)
        }
        val pick = VoiceSearchPicker.pick(req, results)
        Log.i(TAG, "voice search \"$text\": ${results.tracks.size} songs, ${results.artists.size} artists in ${System.currentTimeMillis() - t0} ms -> $pick")
        return when (pick) {
            null -> null
            is VoiceSearchPicker.Pick.Song -> withAlbum(cat, results.tracks[pick.index]) ?: fromResults(pick.index)
            is VoiceSearchPicker.Pick.AlbumPick -> albumPlay(cat, pick.album.id, null) ?: fromResults(VoiceSearchPicker.bestTrack(req, results.tracks))
            is VoiceSearchPicker.Pick.ArtistPick -> {
                val top = playable(detail { cat.artistTopTracks(pick.artist.id) }.orEmpty())
                if (top.isNotEmpty()) {
                    VoicePlay(top, -1, PlayContext("artist:${pick.artist.id}", pick.artist.name))
                } else {
                    val byArtist = playable(results.tracks.filter { t -> t.artists.any { it.id == pick.artist.id || it.name == pick.artist.name } })
                    if (byArtist.isNotEmpty()) VoicePlay(byArtist, 0, searchCtx) else fromResults(VoiceSearchPicker.bestTrack(req, results.tracks))
                }
            }
            is VoiceSearchPicker.Pick.PlaylistPick -> fromResults(VoiceSearchPicker.bestTrack(req, results.tracks))
        }
    }

    /** The song in its album (album context), null when the album can't be read or doesn't list it. */
    private suspend fun withAlbum(cat: VoiceCatalog, t: Track): VoicePlay? {
        if (t.album.id.isEmpty()) return null
        return albumPlay(cat, t.album.id, t)
    }

    private suspend fun albumPlay(cat: VoiceCatalog, albumId: String, start: Track?): VoicePlay? {
        if (albumId.isEmpty()) return null
        val album = detail { cat.album(albumId) } ?: return null
        val ref = album.ref()
        val tracks = playable(album.tracks).map { if (it.album.id.isEmpty() && it.album.name.isEmpty()) it.copy(album = ref) else it }
        if (tracks.isEmpty()) return null
        val ctx = PlayContext("album:${album.id.ifEmpty { albumId }}", album.name)
        if (start == null) return VoicePlay(tracks, -1, ctx)
        var index = tracks.indexOfFirst { it.id.isNotEmpty() && it.id == start.id }
        if (index < 0) {
            val name = VoiceSearchPicker.normalize(start.name)
            index = tracks.indexOfFirst { VoiceSearchPicker.normalize(it.name) == name }
        }
        if (index < 0) return null
        // Keep the searched song's own data (its cached video / artwork) at its slot.
        val list = tracks.toMutableList().also { it[index] = start.copy(album = if (start.album.images.isEmpty()) tracks[index].album else start.album) }
        return VoicePlay(list, index, ctx)
    }

    private suspend fun <T> detail(block: suspend () -> T): T? = try {
        val t0 = System.currentTimeMillis()
        withTimeoutOrNull(DETAIL_TIMEOUT_MS) { block() }.also {
            Log.i(TAG, "voice search detail ${if (it == null) "timed out" else "read"} in ${System.currentTimeMillis() - t0} ms")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "voice search detail failed: ${e.message}")
        null
    }

    private fun playable(tracks: List<Track>) = tracks.filter { it.playable && it.name.isNotEmpty() }
}
