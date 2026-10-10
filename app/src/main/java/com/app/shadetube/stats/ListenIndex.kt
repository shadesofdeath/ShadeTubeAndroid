package com.app.shadetube.stats

import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.core.Text

/**
 * Grouping fold: case- and accent-insensitive ("Şımarık" == "SIMARIK"). Pure ASCII (most titles) skips the Unicode
 * normalization.
 */
internal fun statsFold(s: String): String {
    var ascii = true
    for (c in s) if (c.code >= 0x80) {
        ascii = false
        break
    }
    if (!ascii) return Text.fold(s)
    var upper = false
    for (c in s) if (c in 'A'..'Z') {
        upper = true
        break
    }
    if (!upper) return s
    val out = CharArray(s.length)
    for (i in s.indices) {
        val c = s[i]
        out[i] = if (c in 'A'..'Z') c + ('a' - 'A') else c
    }
    return String(out)
}

internal fun firstArtistName(t: ListenTrack): String = if (t.artists.isEmpty()) "" else t.artists[0].name

/** Fills what [into] lacks from [from] (the first sighting wins; later ones only complete it). */
internal fun complete(into: ListenTrack, from: ListenTrack) {
    if (into.id.isEmpty()) into.id = from.id
    if (into.image.isEmpty()) into.image = from.image
    if (into.albumName.isEmpty()) {
        into.albumName = from.albumName
        into.albumId = from.albumId
    } else if (into.albumId.isEmpty() && into.albumName == from.albumName) {
        into.albumId = from.albumId
    }
    if (into.durationMs <= 0) into.durationMs = from.durationMs
    if (into.artists.any { it.id.isEmpty() }) {
        into.artists = into.artists.map { a ->
            if (a.id.isNotEmpty()) a
            else {
                var id = ""
                for (b in from.artists) if (b.id.isNotEmpty() && b.name == a.name) id = b.id // the last match wins (C++)
                if (id.isEmpty()) a else ArtistRef(id, a.name)
            }
        }
    }
}

/** Grouping keys of a track (folded names). */
class ListenKeys(
    /** name + first artist (or the id when unnamed). */
    val track: String,
    /** Parallel to the track's artists ("" = no name: not grouped). */
    val artists: List<String>,
    /** album name + first artist (or the album id); "" = no album. */
    val album: String,
    /** "id:<album id>" or "". */
    val albumId: String,
    /** Folded first artist ("various artists" detection). */
    val firstArtist: String,
) {
    companion object {
        fun of(t: ListenTrack): ListenKeys {
            val first = statsFold(firstArtistName(t))
            val track = if (t.name.isEmpty()) "id:" + t.id else statsFold(t.name) + '\u001f' + first
            val artists = t.artists.map { if (it.name.isEmpty()) "" else statsFold(it.name) }
            val albumId = if (t.albumId.isNotEmpty()) "id:" + t.albumId else ""
            val album = if (t.albumName.isNotEmpty()) statsFold(t.albumName) + '\u001f' + first else albumId
            return ListenKeys(track, artists, album, albumId, first)
        }
    }
}

/**
 * Distinct tracks and their artist / album groups (merged by folded name, albums also by id; the first id / cover
 * seen wins, later sightings only fill what is missing). Port of ListenStats::Index.
 */
class ListenIndex {
    class Group(
        var id: String = "",
        var name: String = "",
        /** Album: one cover url. */
        var image: String = "",
        /** Album: first artist of its first track. */
        var artist: String = "",
        /** Album: folded first artist of its first track. */
        var artistKey: String = "",
        /** Album: tracks with different first artists. */
        var various: Boolean = false,
    ) {
        fun copy() = Group(id, name, image, artist, artistKey, various)
    }

    val tracks = ArrayList<ListenTrack>()
    /** Per track: indexes into [artists]. */
    val trackArtists = ArrayList<IntArray>()
    /** Per track: index into [albums] or -1. */
    var trackAlbum = IntArray(16)
        private set
    val artists = ArrayList<Group>()
    val albums = ArrayList<Group>()
    val trackByKey = HashMap<String, Int>()
    val artistByKey = HashMap<String, Int>()
    val albumByKey = HashMap<String, Int>()

    val size: Int get() = tracks.size

    /** A deep copy (a half-read file must not leave tracks behind). */
    fun copy(): ListenIndex {
        val c = ListenIndex()
        tracks.forEach { c.tracks.add(it.copy()) }
        c.trackArtists.addAll(trackArtists) // IntArrays are never modified once added
        c.trackAlbum = trackAlbum.copyOf()
        artists.forEach { c.artists.add(it.copy()) }
        albums.forEach { c.albums.add(it.copy()) }
        c.trackByKey.putAll(trackByKey)
        c.artistByKey.putAll(artistByKey)
        c.albumByKey.putAll(albumByKey)
        return c
    }

    fun intern(t: ListenTrack): Int = intern(t, ListenKeys.of(t))

    /** Takes ownership of [t] when it is new. */
    fun intern(t: ListenTrack, keys: ListenKeys): Int {
        // Album group: by album id first (a compilation's tracks have different first artists), then by name + first
        // artist (a download or another source without that id). Both keys point at the group from then on.
        fun albumGroup(idx: Int) {
            if (keys.album.isEmpty() && keys.albumId.isEmpty()) return
            var found: Int? = if (keys.albumId.isEmpty()) null else albumByKey[keys.albumId]
            if (found == null && keys.album.isNotEmpty()) found = albumByKey[keys.album]
            val g: Int
            if (found != null) {
                g = found
                val group = albums[g]
                if (keys.firstArtist.isNotEmpty() && group.artistKey.isNotEmpty() && keys.firstArtist != group.artistKey)
                    group.various = true
            } else {
                g = albums.size
                albums.add(Group(t.albumId, t.albumName, t.image, firstArtistName(t), keys.firstArtist, false))
            }
            if (keys.albumId.isNotEmpty()) albumByKey.putIfAbsent(keys.albumId, g)
            if (keys.album.isNotEmpty()) albumByKey.putIfAbsent(keys.album, g)
            trackAlbum[idx] = g
        }

        val existing = trackByKey[keys.track]
        val added = existing == null
        val idx = existing ?: tracks.size
        if (added) {
            trackByKey[keys.track] = idx
            val ta = ArrayList<Int>(t.artists.size)
            for (i in 0 until minOf(t.artists.size, keys.artists.size)) {
                if (keys.artists[i].isEmpty()) continue
                var g = artistByKey[keys.artists[i]]
                if (g == null) {
                    g = artists.size
                    artistByKey[keys.artists[i]] = g
                    artists.add(Group(t.artists[i].id, t.artists[i].name))
                }
                if (!ta.contains(g)) ta.add(g)
            }
            trackArtists.add(ta.toIntArray())
            if (trackAlbum.size <= idx) trackAlbum = trackAlbum.copyOf(maxOf(idx + 1, trackAlbum.size * 2))
            trackAlbum[idx] = -1
            albumGroup(idx)
        } else {
            if (trackAlbum[idx] < 0) albumGroup(idx) // the first sighting had no album
            complete(tracks[idx], t)
        }
        // Complete the groups from this sighting (an id / cover an earlier source did not have).
        val ta = trackArtists[idx]
        for (i in 0 until minOf(t.artists.size, keys.artists.size)) {
            if (keys.artists[i].isEmpty() || t.artists[i].id.isEmpty()) continue
            val g = artistByKey[keys.artists[i]] ?: continue
            if (artists[g].id.isEmpty() && ta.contains(g)) artists[g].id = t.artists[i].id
        }
        val al = trackAlbum[idx]
        if (al >= 0) {
            val g = albums[al]
            if (g.id.isEmpty()) g.id = t.albumId
            if (g.image.isEmpty()) g.image = t.image
        }
        if (added) tracks.add(t)
        return idx
    }
}
