package com.app.shadetube.catalog

import com.app.shadetube.core.arr
import com.app.shadetube.core.bool
import com.app.shadetube.core.get
import com.app.shadetube.core.int
import com.app.shadetube.core.isObject
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.long
import com.app.shadetube.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Compact JSON of catalog models, byte-compatible with the C++ app (app/AppContext.cpp toJson / *FromJson):
 * library.json, session.json, downloads.json, sync.json etc. all store tracks in this shape.
 */
object ModelJson {
    fun images(list: List<Image>): JsonArray = JsonArray(list.map { jsonOf("u" to it.url, "w" to it.width, "h" to it.height) })
    fun imagesFrom(j: JsonElement?): List<Image> = j.arr.map { Image(it.str("u"), it.int("w"), it.int("h")) }

    fun artists(list: List<ArtistRef>): JsonArray = JsonArray(list.map { jsonOf("id" to it.id, "n" to it.name) })
    fun artistsFrom(j: JsonElement?): List<ArtistRef> = j.arr.map { ArtistRef(it.str("id"), it.str("n")) }

    fun track(t: Track): JsonObject = jsonOf(
        "id" to t.id,
        "v" to t.videoId,
        "n" to t.name,
        "a" to artists(t.artists),
        "al" to jsonOf("id" to t.album.id, "n" to t.album.name, "img" to images(t.album.images)),
        "d" to t.durationMs,
        "e" to t.explicitContent,
        "t" to t.addedAt,
        "no" to t.trackNumber,
    )

    fun trackFrom(j: JsonElement?): Track {
        if (!j.isObject) return Track()
        val al = j["al"]
        return Track(
            id = j.str("id"),
            videoId = j.str("v"),
            name = j.str("n"),
            artists = artistsFrom(j["a"]),
            album = if (al.isObject) AlbumRef(al.str("id"), al.str("n"), imagesFrom(al["img"])) else AlbumRef(),
            durationMs = j.int("d"),
            explicitContent = j.bool("e"),
            addedAt = j.long("t"),
            trackNumber = j.int("no"),
        )
    }

    fun album(a: Album): JsonObject = jsonOf(
        "id" to a.id,
        "n" to a.name,
        "pt" to a.primaryType,
        "a" to artists(a.artists),
        "img" to images(a.images),
        "date" to a.firstReleaseDate,
        "tt" to a.totalTracks,
    )

    fun albumFrom(j: JsonElement?): Album {
        if (!j.isObject) return Album()
        return Album(
            id = j.str("id"),
            name = j.str("n"),
            primaryType = j.str("pt"),
            artists = artistsFrom(j["a"]),
            images = imagesFrom(j["img"]),
            firstReleaseDate = j.str("date"),
            totalTracks = j.int("tt"),
        )
    }

    fun artist(a: Artist): JsonObject = jsonOf("id" to a.id, "n" to a.name, "img" to images(a.images), "c" to a.country)

    fun artistFrom(j: JsonElement?): Artist {
        if (!j.isObject) return Artist()
        return Artist(id = j.str("id"), name = j.str("n"), images = imagesFrom(j["img"]), country = j.str("c"))
    }

    fun playlist(p: Playlist): JsonObject = jsonOf(
        "id" to p.id, "n" to p.name, "desc" to p.description, "img" to images(p.images), "tt" to p.totalTracks,
        "c" to p.createdAt, "ck" to p.countKnown, "oid" to p.ownerId, "on" to p.ownerName, "ed" to p.editable,
        "ow" to p.owned,
    )

    fun playlistFrom(j: JsonElement?): Playlist {
        if (!j.isObject) return Playlist()
        return Playlist(
            id = j.str("id"), name = j.str("n"), description = j.str("desc"), images = imagesFrom(j["img"]),
            totalTracks = j.int("tt"), createdAt = j.long("c"), countKnown = j.bool("ck", true), ownerId = j.str("oid"),
            ownerName = j.str("on"), editable = j.bool("ed"), owned = j.bool("ow"),
        )
    }
}
