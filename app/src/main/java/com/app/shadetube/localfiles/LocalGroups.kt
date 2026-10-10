package com.app.shadetube.localfiles

import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Text
import java.util.Locale

/**
 * An album or artist of the local library (the Albümler / Sanatçılar tabs of LocalFilesPage). [trackIndices] point
 * into the library's tracks (= entries, same order).
 */
data class LocalGroup(
    /** Stable identity (survives rescans): lower-cased album + U+001F + album artist, or the artist name. */
    val key: String,
    val title: String,
    /** Albums: the year of any of its tracks (0 = none); artists: 0. */
    val year: Int = 0,
    /** Albums: the album artist (else the first artist); artists: "". */
    val artist: String = "",
    val images: List<Image> = emptyList(),
    val trackIndices: List<Int> = emptyList(),
)

/** Grouping, searching and play contexts of the local library's album / artist grids (non-UI part of LocalFilesPage). */
object LocalGroups {
    /** Album / artist grids show at most this many cards: beyond it, the search box narrows them down. */
    const val MAX_CARDS = 300

    private val TR = Locale.forLanguageTag("tr")

    /** Turkish-aware lower case (the C++ toLowerTr): identity keys only. */
    private fun lowerTr(s: String) = s.lowercase(TR)

    /** Albums: album tag + album artist (else the first artist), in library order (artist, album). */
    fun albums(entries: List<LocalEntry>, tracks: List<Track>): List<LocalGroup> {
        class B(val key: String, val title: String, val artist: String) {
            var year = 0
            var images: List<Image> = emptyList()
            val idx = ArrayList<Int>()
        }
        val out = ArrayList<B>()
        val index = HashMap<String, Int>()
        for (i in 0 until minOf(entries.size, tracks.size)) {
            val e = entries[i]
            if (e.album.isEmpty()) continue
            val artist = e.albumArtist.ifEmpty { e.artists.firstOrNull() ?: "" }
            val key = lowerTr(e.album) + "\u001f" + lowerTr(artist)
            val k = index.getOrPut(key) { out += B(key, e.album, artist); out.size - 1 }
            val g = out[k]
            if (g.images.isEmpty()) g.images = tracks[i].album.images
            if (g.year == 0) g.year = e.year
            g.idx += i
        }
        return out.map { LocalGroup(it.key, it.title, it.year, it.artist, it.images, it.idx) }
    }

    /** Artists: every credited artist of a track, alphabetical (case-insensitive, the device language). */
    fun artists(tracks: List<Track>): List<LocalGroup> {
        class B(val key: String, val title: String) {
            var images: List<Image> = emptyList()
            val idx = ArrayList<Int>()
        }
        val out = ArrayList<B>()
        val index = HashMap<String, Int>()
        for ((i, t) in tracks.withIndex()) {
            for (a in t.artists) {
                if (a.name.isEmpty()) continue
                val key = lowerTr(a.name)
                val k = index.getOrPut(key) { out += B(key, a.name); out.size - 1 }
                val g = out[k]
                if (g.images.isEmpty()) g.images = t.album.images
                g.idx += i
            }
        }
        val col = LocalScanner.collator()
        return out.sortedWith { a, b -> col.compare(a.title, b.title) }.map { LocalGroup(it.key, it.title, 0, "", it.images, it.idx) }
    }

    /** The groups a grid search shows: title, artist and year folded (case / accent / Turkish-I insensitive). */
    fun filter(groups: List<LocalGroup>, query: String): List<LocalGroup> {
        val needle = Text.fold(query.trim())
        if (needle.isEmpty()) return groups
        return groups.filter { g ->
            val hay = buildString {
                append(g.title)
                if (g.year > 0) append(' ').append(g.year)
                if (g.artist.isNotEmpty()) append(' ').append(g.artist)
            }
            Text.fold(hay).contains(needle)
        }
    }

    /** Songs of a track list whose title, artists or album match [query] (the Şarkılar tab's search box). */
    fun filterTracks(tracks: List<Track>, query: String): List<Track> {
        val needle = Text.fold(query.trim())
        if (needle.isEmpty()) return tracks
        return tracks.filter { Text.fold(it.name + " " + it.artistLine + " " + it.album.name).contains(needle) }
    }

    /** Play / Karıştır on a grid: every track of the shown groups, in card order (each once). */
    fun tracksOf(groups: List<LocalGroup>, tracks: List<Track>): List<Track> {
        val taken = BooleanArray(tracks.size)
        val out = ArrayList<Track>()
        for (g in groups) for (i in g.trackIndices) {
            if (i < tracks.size && !taken[i]) {
                taken[i] = true
                out += tracks[i]
            }
        }
        return out
    }

    /** The player context of an opened album / artist ("local:album:<key>" / "local:artist:<key>"). */
    fun contextUri(album: Boolean, key: String): String = (if (album) "local:album:" else "local:artist:") + key

    /**
     * Each list plays under its own context, so the page's big Play button only pauses / resumes what this view
     * started: the Şarkılar tab, the album grid, the artist grid.
     */
    const val SONGS_CONTEXT = "local"
    const val ALBUMS_CONTEXT = "local:albums"
    const val ARTISTS_CONTEXT = "local:artists"
}
