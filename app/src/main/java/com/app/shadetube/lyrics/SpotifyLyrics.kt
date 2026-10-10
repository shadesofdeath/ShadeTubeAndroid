package com.app.shadetube.lyrics

import com.app.shadetube.core.parseStrictJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Spotify's lyrics service, as the `spotify` package provides it to this one (AppGraph wires the logged-in session's
 * implementation; it is only asked for `spotify:track:` ids).
 *
 * [fetch] gets the raw color-lyrics JSON body of `spclient /color-lyrics/v2/track/<id>` for a full
 * "spotify:track:<id>" URI; null or "" = Spotify has no lyrics for it (404). For an HTTP failure throw
 * [com.app.shadetube.core.HttpError] with its status: 429 / 403 pause the provider for 10 / 30 minutes. Any other
 * exception counts as a transient failure (not cached, asked again next time).
 */
fun interface SpotifyLyricsSource {
    suspend fun fetch(trackUri: String): String?
}

/** Port of `lyrics::parseSpotify`. Pure. */
object SpotifyLyrics {
    /**
     * Spotify's color-lyrics JSON -> Lyrics (source [SOURCE_SPOTIFY]); null when the body has no usable lines.
     * LINE_SYNCED / SYLLABLE_SYNCED lines are timed (startTimeMs as a string or a number) and sorted; "♪" is an
     * instrumental gap; unsynced lyrics lose their leading / trailing blank lines.
     */
    fun parse(body: String): Lyrics? {
        val j = parseStrictJson(body) as? JsonObject ?: return null
        val lit = j["lyrics"] as? JsonObject ?: return null
        val syncType = (lit["syncType"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        val synced = syncType == "LINE_SYNCED" || syncType == "SYLLABLE_SYNCED"
        var lines = ArrayList<LyricLine>()
        (lit["lines"] as? JsonArray)?.forEach { x ->
            if (x !is JsonObject) return@forEach
            var ms = -1
            if (synced) {
                val t = x["startTimeMs"] as? JsonPrimitive
                if (t != null) {
                    if (t.isString) ms = Lrc.atoi(t.content)
                    else t.content.toLongOrNull()?.let { ms = it.toInt() }
                }
                if (ms < 0) return@forEach
            }
            var text = Lrc.trim((x["words"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "")
            if (text == "♪") text = "" // "♪": an instrumental gap
            lines += LyricLine(ms, text)
        }
        if (synced) {
            lines = ArrayList(lines.sortedBy { it.timeMs })
        } else {
            while (lines.isNotEmpty() && lines.last().text.isEmpty()) lines.removeAt(lines.size - 1)
            val first = lines.indexOfFirst { it.text.isNotEmpty() }
            lines = if (first < 0) ArrayList() else ArrayList(lines.subList(first, lines.size))
        }
        if (lines.none { it.text.isNotEmpty() }) return null
        return Lyrics(synced = synced, lines = lines, source = SOURCE_SPOTIFY)
    }
}
