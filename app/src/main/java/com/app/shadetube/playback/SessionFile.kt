package com.app.shadetube.playback

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.pick
import com.app.shadetube.core.arr
import com.app.shadetube.core.bool
import com.app.shadetube.core.get
import com.app.shadetube.core.int
import com.app.shadetube.core.isArray
import com.app.shadetube.core.isObject
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.long
import com.app.shadetube.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * session.json (the queue restored at startup), byte-compatible with the desktop app's Player::saveSession /
 * restoreSession. Kept tiny on purpose: ids + minimal display data of up to [MAX_ITEMS] queue items — a window of the
 * play order around the current track (endless playback grows queues past 500), with the current track's id to
 * re-find it.
 *
 *   {"pos":450,"curId":"...","positionMs":12345,"contextUri":"...","contextName":"...",
 *    "items":[{"id","n","d","a":[{"id","n"}],"al","ali","img":[{"u","w"}],"e","rec"?}]}
 */
object SessionFile {
    const val MAX_ITEMS = 500
    const val BACK_ITEMS = 100

    data class Restored(
        val items: List<Track>,
        val pos: Int,
        val context: PlayContext,
        val positionMs: Long,
    )

    /** The window of play-order slots saved: 100 back, up to 500 in all. */
    fun window(total: Int, pos: Int): IntRange {
        val first = if (pos < 0) 0 else maxOf(0, minOf(pos - BACK_ITEMS, total - MAX_ITEMS))
        val last = minOf(total, first + MAX_ITEMS)
        return first until last
    }

    /** [inOrder] = the tracks in play order; [pos] the current slot (-1 = none). */
    fun encode(inOrder: List<Track>, pos: Int, positionMs: Long, context: PlayContext): JsonObject {
        val w = window(inOrder.size, pos)
        val items = JsonArray(w.map { item(inOrder[it]) })
        val fields = ArrayList<Pair<String, Any?>>()
        fields.add("pos" to (if (pos < 0) 0 else pos - w.first))
        inOrder.getOrNull(pos)?.let { fields.add("curId" to it.id) }
        fields.add("positionMs" to positionMs)
        fields.add("contextUri" to context.uri)
        fields.add("contextName" to context.name)
        fields.add("items" to items)
        return jsonOf(*fields.toTypedArray())
    }

    private fun item(t: Track): JsonObject {
        val a = JsonArray(t.artists.map { jsonOf("id" to it.id, "n" to it.name) })
        val img = t.album.images.pick(300)
        val imgs = JsonArray(listOfNotNull(img?.let { jsonOf("u" to it.url, "w" to it.width) }))
        val m = LinkedHashMap<String, JsonElement>()
        m["id"] = JsonPrimitive(t.id)
        m["n"] = JsonPrimitive(t.name)
        m["d"] = JsonPrimitive(t.durationMs)
        m["a"] = a
        m["al"] = JsonPrimitive(t.album.name)
        m["ali"] = JsonPrimitive(t.album.id)
        m["img"] = imgs
        m["e"] = JsonPrimitive(t.explicitContent)
        if (t.recommended) m["rec"] = JsonPrimitive(true)
        return JsonObject(m)
    }

    /**
     * Parses a saved session; null when there is nothing to restore. A hand-edited / foreign file (wrong field types)
     * reads as empty fields; items without a name are dropped and the current track is re-found by its id (the nearest
     * copy).
     */
    fun decode(j: JsonElement?): Restored? {
        if (!j.isObject || !j["items"].isArray) return null
        val tracks = ArrayList<Track>()
        for (it in j["items"].arr) {
            if (!it.isObject) continue
            val t = Track(
                id = it.str("id"),
                name = it.str("n"),
                durationMs = it.int("d"),
                album = AlbumRef(
                    id = it.str("ali"),
                    name = it.str("al"),
                    images = it["img"].arr.map { im -> Image(im.str("u"), im.int("w"), im.int("w")) },
                ),
                explicitContent = it.bool("e"),
                recommended = it.bool("rec"),
                artists = it["a"].arr.map { a -> ArtistRef(a.str("id"), a.str("n")) },
            )
            if (t.name.isNotEmpty()) tracks.add(t)
        }
        if (tracks.isEmpty()) return null
        val n = tracks.size
        var pos = j.int("pos").coerceIn(0, n - 1)
        val curId = j.str("curId")
        if (curId.isNotEmpty() && tracks[pos].id != curId) {
            for (d in 1 until n) {
                if (pos - d >= 0 && tracks[pos - d].id == curId) {
                    pos -= d
                    break
                }
                if (pos + d < n && tracks[pos + d].id == curId) {
                    pos += d
                    break
                }
            }
        }
        return Restored(tracks, pos, PlayContext(j.str("contextUri"), j.str("contextName")), j.long("positionMs"))
    }
}
