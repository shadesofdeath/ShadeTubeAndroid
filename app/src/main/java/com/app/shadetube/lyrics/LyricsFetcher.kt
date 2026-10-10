package com.app.shadetube.lyrics

import com.app.shadetube.core.parseStrictJson
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Files
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.HttpResponse
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.dump
import com.app.shadetube.core.jsonArrayOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException

/** What to fetch lyrics for (port of `lyrics::Query`). */
data class LyricsQuery(
    /** Catalog track id (MBID / spotify: URI / local: id). */
    val cacheKey: String,
    val title: String,
    /** Primary artist. */
    val artist: String = "",
    val album: String = "",
    val durationSec: Int = 0,
    /**
     * The file this item plays from (a local file or a download), null = streamed. Its synced sidecar .lrc or
     * embedded synced lyrics win over every online source; unsynced ones are kept when the online sources have
     * nothing synced.
     */
    val audio: LocalAudioFile? = null,
    /** Asked after LRCLIB when it has nothing, or only unsynced lyrics (once per track and provider name). */
    val fallbackName: String = "",
    val fallback: LyricsProvider? = null,
)

/**
 * The provider chain and its disk cache (port of `lyrics::fetch` and the LRCLIB client of lyrics/Lyrics.cpp).
 *
 * Cache entry (`<cacheDir>/<safe id>.json`): {"v":2, "found":bool, "at":unix, "synced", "instrumental",
 * "lines":[[ms,text]...], "words":{"<line>":[[ms,text]...]}, "src":"lrclib"|"spotify", "tried":["lrclib","spotify"]}.
 * Entries without "tried" were written by LRCLIB alone. A definitive "none" is kept 7 days; a transient failure is
 * never cached; a provider that wasn't asked yet (the user logged in later) still is.
 *
 * @param cacheDir where entries live (`Paths.cacheDir/lyrics`).
 * @param lrclib the LRCLIB provider (tests stub it).
 */
class LyricsFetcher(
    private val cacheDir: () -> File = { File(Paths.cacheDir, "lyrics") },
    private val lrclib: suspend (LyricsQuery) -> ProviderResult = { Lrclib.fetch(it) },
    private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private class CacheEntry(
        var found: Boolean = false,
        var lyrics: Lyrics = Lyrics(),
        val tried: MutableList<String> = ArrayList(),
    ) {
        fun triedBy(provider: String) = provider in tried
    }

    /**
     * Lyrics for [q]: the file's own synced lyrics, else the online chain (cached answer, LRCLIB when it wasn't asked
     * yet, then the fallback when lyrics are still missing or unsynced); the file's unsynced text beats an unsynced
     * online one. null = none (or a network error: errors are not negatively cached). Main-safe.
     */
    suspend fun fetch(q: LyricsQuery): Lyrics? = withContext(AppDispatchers.io) {
        var local: Lyrics? = null
        if (q.audio != null) {
            local = LocalLyrics.readLocal(q.audio)
            if (local != null && local.synced) return@withContext local
        }
        if (q.title.isEmpty()) return@withContext local
        val online = fetchOnline(q)
        if (online != null && (online.synced || online.instrumental)) return@withContext online
        local ?: online
    }

    // ---- cache ----------------------------------------------------------------------------------------------------
    /** Same file name as the C++: every byte outside [0-9A-Za-z_-] of the UTF-8 id becomes '_'. */
    internal fun cacheFile(key: String): File? {
        if (key.isEmpty()) return null
        val safe = StringBuilder()
        for (b in key.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            safe.append(if (c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '-' || c == '_') c else '_')
        }
        val dir = cacheDir().also { it.mkdirs() }
        return File(dir, "$safe.json")
    }

    private fun isInt(e: JsonElement?) = e is JsonPrimitive && !e.isString && e.content.toLongOrNull() != null
    private fun isStr(e: JsonElement?) = e is JsonPrimitive && e.isString
    private fun bool(j: JsonObject, key: String) = (j[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"

    /** null: no usable cache entry (missing, corrupt, other version, expired negative). */
    private fun readCache(f: File?): CacheEntry? {
        if (f == null || !f.isFile) return null
        val j = runCatching { parseStrictJson(f.readText()) }.getOrNull() as? JsonObject ?: return null
        val v = j["v"]
        if (!isInt(v) || (v as JsonPrimitive).content.toLong() != CACHE_VERSION.toLong()) return null
        val e = CacheEntry()
        val tried = j["tried"]
        if (tried is JsonArray) {
            for (t in tried) if (isStr(t)) e.tried += (t as JsonPrimitive).content
        } else {
            e.tried += SOURCE_LRCLIB
        }
        e.found = bool(j, "found")
        if (!e.found) {
            val at = j["at"].let { if (isInt(it)) (it as JsonPrimitive).content.toLong() else 0L }
            if (nowUnix() - at > NEGATIVE_TTL_SEC) return null // expired negative
            return e
        }
        val lines = ArrayList<LyricLine>()
        (j["lines"] as? JsonArray)?.forEach { x ->
            if (x !is JsonArray || x.size != 2 || !isInt(x[0]) || !isStr(x[1])) return@forEach
            lines += LyricLine((x[0] as JsonPrimitive).content.toLong().toInt(), (x[1] as JsonPrimitive).content)
        }
        (j["words"] as? JsonObject)?.forEach { (idx, list) ->
            val i = idx.takeWhile { it in '0'..'9' }.toIntOrNull() ?: 0 // strtoul
            if (i >= lines.size || list !is JsonArray) return@forEach
            val words = lines[i].words.toMutableList()
            for (w in list) {
                if (w is JsonArray && w.size == 2 && isInt(w[0]) && isStr(w[1]))
                    words += LyricWord((w[0] as JsonPrimitive).content.toLong().toInt(), (w[1] as JsonPrimitive).content)
            }
            lines[i] = lines[i].copy(words = words)
        }
        e.lyrics = Lyrics(
            synced = bool(j, "synced"),
            instrumental = bool(j, "instrumental"),
            lines = lines,
            source = (j["src"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: SOURCE_LRCLIB,
        )
        return e
    }

    /** Write to a temp file then rename, so a crash never leaves a truncated cache entry. Keys in nlohmann's order. */
    private fun writeCache(f: File?, e: CacheEntry) {
        if (f == null) return
        val m = sortedMapOf<String, Any?>("v" to CACHE_VERSION, "found" to e.found, "at" to nowUnix(), "tried" to e.tried.toList())
        if (e.found) {
            val l = e.lyrics
            m["synced"] = l.synced
            m["instrumental"] = l.instrumental
            m["src"] = l.source.ifEmpty { SOURCE_LRCLIB }
            m["lines"] = l.lines.map { jsonArrayOf(it.timeMs, it.text) }
            val words = sortedMapOf<String, Any?>()
            l.lines.forEachIndexed { i, x ->
                if (x.words.isNotEmpty()) words[i.toString()] = x.words.map { jsonArrayOf(it.timeMs, it.text) }
            }
            if (words.isNotEmpty()) m["words"] = words
        }
        Files.writeAtomic(f, com.app.shadetube.core.toJsonElement(m).dump())
    }

    private fun better(candidate: Lyrics, current: Lyrics?): Boolean {
        if (current == null) return true
        return candidate.synced && !current.synced && !current.instrumental
    }

    /**
     * The online chain for one track: the cached answer, LRCLIB when it wasn't asked yet, then the fallback provider
     * when that is still missing or unsynced. What was asked is remembered with the result.
     */
    private suspend fun fetchOnline(q: LyricsQuery): Lyrics? {
        val cache = cacheFile(q.cacheKey)
        val entry = readCache(cache) ?: CacheEntry()
        var best: Lyrics? = if (entry.found) entry.lyrics else null
        var changed = false

        if (!entry.triedBy(SOURCE_LRCLIB)) {
            val r = lrclib(q)
            if (r.kind != ProviderResult.Kind.Failed) {
                entry.tried += SOURCE_LRCLIB
                changed = true
            }
            if (r.kind == ProviderResult.Kind.Found && better(r.lyrics, best)) best = r.lyrics
        }
        val wantMore = best == null || (!best.synced && !best.instrumental)
        val fallback = q.fallback
        if (wantMore && fallback != null && q.fallbackName.isNotEmpty() && !entry.triedBy(q.fallbackName)) {
            val r = fallback.fetch()
            if (r.kind != ProviderResult.Kind.Failed) {
                entry.tried += q.fallbackName
                changed = true
            }
            if (r.kind == ProviderResult.Kind.Found && r.lyrics.lines.isNotEmpty() && better(r.lyrics, best)) {
                best = if (r.lyrics.source.isEmpty()) r.lyrics.copy(source = q.fallbackName) else r.lyrics
            }
        }
        if (changed) {
            entry.found = best != null
            if (best != null) entry.lyrics = best
            else Log.i("lyrics", "no lyrics for '${q.artist}' - '${q.title}'")
            writeCache(cache, entry)
        }
        return best
    }

    companion object {
        const val NEGATIVE_TTL_SEC = 7L * 24 * 3600
        /** 2: junk records (Lrclib.plausible) are no longer cached as lyrics. */
        const val CACHE_VERSION = 2
    }
}

/** LRCLIB (https://lrclib.net): exact lookup, then fuzzy search. Port of the C++ `lrclib()` provider. */
object Lrclib {
    private const val USER_AGENT = "ShadeTube/0.1 (https://github.com/shadesofdeath)"
    private const val DURATION_TOLERANCE_SEC = 5

    private fun jstr(j: JsonElement?, key: String): String =
        ((j as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    /**
     * LRCLIB is user-edited: some records are junk (Rick Astley "Never Gonna Give You Up", id 1009665, is
     * "[00:00.00]probe"). Real lyrics have at least two lines or a sentence (24 UTF-8 bytes).
     */
    internal fun plausible(l: Lyrics): Boolean {
        var lines = 0
        var chars = 0
        for (x in l.lines) {
            if (Lrc.trim(x.text).isEmpty()) continue
            lines++
            chars += x.text.toByteArray(Charsets.UTF_8).size
        }
        return lines >= 2 || chars >= 24
    }

    /** An LRCLIB record as Lyrics; null if it carries nothing usable. */
    internal fun fromRecord(r: JsonElement?): Lyrics? {
        if (r !is JsonObject) return null
        val instr = r["instrumental"] as? JsonPrimitive
        if (instr != null && !instr.isString && instr.content == "true") {
            return Lyrics(instrumental = true, source = SOURCE_LRCLIB)
        }
        val synced = jstr(r, "syncedLyrics")
        if (Lrc.trim(synced).isNotEmpty()) {
            val l = Lrc.parse(synced).copy(source = SOURCE_LRCLIB)
            if (l.lines.isNotEmpty() && plausible(l)) return l
        }
        val plain = jstr(r, "plainLyrics")
        if (Lrc.trim(plain).isNotEmpty()) {
            val l = Lrc.plainToLyrics(plain).copy(source = SOURCE_LRCLIB)
            if (l.lines.isNotEmpty() && plausible(l)) return l
        }
        return null
    }

    private fun recordDuration(r: JsonObject): Double {
        val d = r["duration"] as? JsonPrimitive ?: return -1.0
        if (d.isString) return -1.0
        return d.content.toDoubleOrNull() ?: -1.0
    }

    /**
     * Picks the best search result: synced within tolerance (closest duration) > instrumental / plain within
     * tolerance. Without a known duration, the first synced (else first usable) result wins.
     */
    internal fun pickFromSearch(results: JsonElement?, durationSec: Int): Lyrics? {
        if (results !is JsonArray) return null
        var bestSynced: JsonObject? = null
        var bestOther: JsonObject? = null
        var bestSyncedDiff = 1e9
        var bestOtherDiff = 1e9
        for (r in results) {
            if (r !is JsonObject) continue
            var diff = 0.0
            if (durationSec > 0) {
                val d = recordDuration(r)
                if (d < 0) continue
                diff = kotlin.math.abs(d - durationSec)
                if (diff > DURATION_TOLERANCE_SEC) continue
            }
            if (fromRecord(r) == null) continue // junk / empty record
            val hasSynced = Lrc.trim(jstr(r, "syncedLyrics")).isNotEmpty()
            if (hasSynced && diff < bestSyncedDiff) {
                bestSynced = r; bestSyncedDiff = diff
            } else if (!hasSynced && diff < bestOtherDiff) {
                bestOther = r; bestOtherDiff = diff
            }
        }
        if (bestSynced != null) return fromRecord(bestSynced)
        if (bestOther != null) return fromRecord(bestOther)
        return null
    }

    private suspend fun get(url: String): HttpResponse =
        Http.await(HttpRequest(url, headers = listOf("User-Agent" to USER_AGENT, "Accept" to "application/json")))

    suspend fun fetch(q: LyricsQuery): ProviderResult {
        try {
            // 1) exact lookup
            var url = "https://lrclib.net/api/get?track_name=" + Http.urlEncode(q.title) +
                "&artist_name=" + Http.urlEncode(q.artist)
            if (q.album.isNotEmpty()) url += "&album_name=" + Http.urlEncode(q.album)
            if (q.durationSec > 0) url += "&duration=" + q.durationSec
            var resp = get(url)
            if (resp.status == 200) {
                fromRecord(parseStrictJson(resp.body))?.let { return ProviderResult.found(it) }
                // Record exists but is empty: fall through to search.
            } else if (resp.status != 404 && resp.status != 400) { // 400: incomplete query -> search
                Log.w("lyrics", "lrclib get HTTP ${resp.status} for '${q.title}'")
                return ProviderResult.failed // transient: don't cache
            }

            // 2) fuzzy search
            var surl = "https://lrclib.net/api/search?track_name=" + Http.urlEncode(q.title)
            if (q.artist.isNotEmpty()) surl += "&artist_name=" + Http.urlEncode(q.artist)
            resp = get(surl)
            return when (resp.status) {
                200 -> {
                    val j = parseStrictJson(resp.body)
                    pickFromSearch(j, q.durationSec)?.let { ProviderResult.found(it) }
                        ?: if (j != null) ProviderResult.notFound else ProviderResult.failed
                }
                404 -> ProviderResult.notFound
                else -> {
                    Log.w("lyrics", "lrclib search HTTP ${resp.status} for '${q.title}'")
                    ProviderResult.failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w("lyrics", "lrclib request failed: ${e.message}")
            return ProviderResult.failed
        }
    }
}
