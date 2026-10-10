package com.app.shadetube.spotify

import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Files
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/*
 * Pathfinder persisted-query hashes, with self-healing (port of spotify/HashRegistry).
 *
 * Every Pathfinder GraphQL call names an operation and sends the sha256 of its query document ("persisted query").
 * A web-player deploy that changes a document changes its hash; Spotify keeps serving retired hashes for a while,
 * then purges them, and the op fails with HTTP 412 "Invalid query hash" (verified 2026-09-28). Instead of needing a
 * rebuild, the registry:
 *   * starts from the built-in table (the hashes this build was verified with);
 *   * learns the current ones from the live web player: GET open.spotify.com -> web-player.<hash>.js -> every
 *     `new X.l("<op>","query"|"mutation","<sha256>",null)`. Ops in lazily loaded webpack chunks (searchDesktop lives
 *     in xpui-routes-search.<hash>.js) are found through the bundle's chunk map (bounded, only when needed). Last
 *     resort: the community registry github.com/Jigen-Ohtsusuki/spotify-gql-registry (hashes.json);
 *   * adopts a learned hash for an op only once that op's current hash was rejected — a working op never switches
 *     to a newer document whose required variables may differ — and persists it in
 *     Paths.appData/spotify-hashes.json {bundle, fetchedAt, hashes:{op:hash}, replaced, discovered, searched}.
 * Thread-safe: queries run concurrently. Only heal() touches the network.
 */

data class PersistedOp(
    val name: String,
    /** "query" | "mutation" */
    val kind: String,
    /** 64 lowercase hex */
    val hash: String,
)

data class ChunkRef(
    /** "xpui-routes-search", or the numeric chunk id for unnamed chunks. */
    val name: String,
    val url: String,
)

/** How Pathfinder rejected a request. */
enum class QueryFailure {
    Other,

    /** Unknown / purged hash: HTTP 412 "Invalid query hash" (or a PersistedQueryNotFound error). */
    HashRejected,

    /** HTTP 400 naming a missing / mistyped variable: the request must change, not the hash. */
    VariableError,
}

class Discovery {
    /** "web-player.da8b87c8.js" ("" when the web player was unreachable). */
    var bundle: String = ""

    /** By name: everything the web player defines + registry fill-ins. */
    val ops: MutableMap<String, PersistedOp> = LinkedHashMap()
    var chunksScanned: Int = 0

    /** The web scan looked everywhere it may (no transport error / deadline). */
    var complete: Boolean = false

    /** Wanted ops found in chunks (not in the main bundle). */
    val fromChunks: MutableList<String> = ArrayList()

    /** Wanted ops filled in from the GitHub registry (not web-player data). */
    val fromRegistry: MutableList<String> = ArrayList()
}

/** The server answered with a non-2xx status (it was reached). */
class HashFetchStatusError(val status: Int, url: String) : IOException("HTTP $status for $url")

/** The request never got an answer (DNS, connect / receive timeout, network down). */
class HashFetchTransportError(message: String) : IOException(message)

/**
 * Discovery parsers (pure, unit-tested) and the blocking-free network scan.
 */
object HashDiscovery {
    private const val TAG = "hashes"
    const val WEB_PLAYER = "https://open.spotify.com/"
    const val CDN_HOST = "https://open.spotifycdn.com"
    const val BUILD_DIR = "/cdn/build/web-player/"

    /**
     * Last resort (community-maintained, verified daily; lists ~10 common queries, no mutations):
     * {schema_version, bundle_id, last_updated, operations:{<op>:{hash, type, status, ...}}}.
     */
    const val COMMUNITY_REGISTRY = "https://raw.githubusercontent.com/Jigen-Ohtsusuki/spotify-gql-registry/main/hashes.json"
    const val MAX_REGISTRY_BYTES = 1 shl 20 // the real file is ~3 KB

    /** heal() holds its mutex for the whole scan. */
    const val CHUNK_BUDGET_MS = 45_000L

    fun isHex64(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
    internal fun isIdentChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
    private fun isFileChar(c: Char) = isIdentChar(c) || c == '.' || c == '-' || c == '~'
    private fun lower(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c
    internal fun containsNoCase(hay: String, needle: String): Boolean = hay.contains(needle, ignoreCase = true)

    /** `{4406:"xpui-routes-search","a1":"x",...}` (the braces stripped) -> pairs. Empty on anything unexpected. */
    internal fun parseFlatMap(s: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        fun quoted(): String? {
            if (i >= s.length || s[i] != '"') return null
            val end = s.indexOf('"', i + 1)
            if (end < 0) return null
            val v = s.substring(i + 1, end)
            i = end + 1
            return v
        }
        while (i < s.length) {
            val key: String
            if (s[i] == '"') {
                key = quoted() ?: return emptyList()
            } else {
                val k = i
                while (i < s.length && isIdentChar(s[i])) ++i
                key = s.substring(k, i)
            }
            if (key.isEmpty() || i >= s.length || s[i++] != ':') return emptyList()
            val value = quoted() ?: return emptyList()
            out.add(key to value)
            if (i < s.length && s[i++] != ',') return emptyList()
        }
        return out
    }

    /** Code is only ever fetched from Spotify's own hosts, whatever the page or the bundle's chunk map point at. */
    internal fun allowedHost(url: String): Boolean =
        url.startsWith("https://open.spotifycdn.com/") || url.startsWith("https://open.spotify.com/")

    private fun fileName(url: String) = url.substring(url.lastIndexOf('/') + 1)

    /** camelCase words of an op name, lowercased, 4+ letters: "searchDesktop" -> {"search", "desktop"}. */
    internal fun nameWords(op: String): List<String> {
        val words = ArrayList<String>()
        val w = StringBuilder()
        fun flush() {
            if (w.length >= 4) words.add(w.toString())
            w.setLength(0)
        }
        for (c in op) {
            if (c in 'A'..'Z') flush()
            if (isIdentChar(c)) w.append(lower(c)) else flush()
        }
        flush()
        return words
    }

    /**
     * How likely chunk [name] defines one of the ops [words] came from. Per name segment ("xpui-routes-search"):
     * equal to a word +3, overlapping one +1, unrelated -1 (a specific name beats a broad one: "desktop" alone must
     * not pull in xpui-desktop-modals), "routes" +1 (route pages own their queries). Numbered chunks come last.
     */
    internal fun chunkScore(name: String, words: List<String>): Int {
        if (name.all { it in '0'..'9' }) return -1000
        var score = 0
        var i = 0
        while (i < name.length) {
            var e = name.indexOfAny(charArrayOf('-', '~', '_', '.'), i)
            if (e < 0) e = name.length
            val seg = name.substring(i, e)
            i = e + 1
            if (seg.isEmpty() || seg == "xpui" || seg == "dwp") continue
            if (seg == "routes") {
                ++score
                continue
            }
            var best = -1
            for (w in words) {
                if (seg == w) best = 3
                else if (best < 1 && seg.length >= 4 && (seg.contains(w) || w.contains(seg))) best = 1
            }
            score += best
        }
        return score
    }

    /**
     * Every persisted operation in a JS source (`"<name>","query"|"mutation","<64 hex>"`), in source order. A name
     * that appears again (the bundle repeats some modules verbatim) keeps its first hash.
     */
    fun extractPersistedOps(js: String): List<PersistedOp> {
        val found = ArrayList<Pair<Int, PersistedOp>>()
        for (kind in listOf("query", "mutation")) {
            val needle = "\",\"$kind\",\"" // name"  ,"query","  hash
            var p = js.indexOf(needle)
            while (p >= 0) {
                run {
                    var s = p
                    while (s > 0 && isIdentChar(js[s - 1])) --s
                    if (s == p || s == 0 || js[s - 1] != '"') return@run
                    val h = p + needle.length
                    if (h + 65 > js.length || js[h + 64] != '"') return@run
                    val hash = js.substring(h, h + 64)
                    if (!isHex64(hash)) return@run
                    found.add(s to PersistedOp(js.substring(s, p), kind, hash))
                }
                p = js.indexOf(needle, p + 1)
            }
        }
        found.sortBy { it.first }
        val seen = HashSet<String>()
        return found.map { it.second }.filter { seen.add(it.name) }
    }

    /**
     * Absolute URLs of the web-player bundles open.spotify.com's HTML loads (…/cdn/build/web-player/web-player.<h>.js;
     * the vendor~ / encore~ library bundles are skipped: they define no operations). References on any host other
     * than open.spotifycdn.com / open.spotify.com are ignored; relative ones resolve to open.spotifycdn.com.
     */
    fun findBundleUrls(html: String): List<String> {
        val out = ArrayList<String>()
        var p = html.indexOf(BUILD_DIR)
        while (p >= 0) {
            run {
                var e = p + BUILD_DIR.length
                while (e < html.length && isFileChar(html[e])) ++e
                val file = html.substring(p + BUILD_DIR.length, e)
                if (!file.startsWith("web-player.") || !file.endsWith(".js")) return@run
                // Keep the page's own origin ("https://open.spotifycdn.com"); a relative src resolves to the CDN. Any
                // other host (or a protocol-relative "//host") is ignored.
                var s = p
                while (s > 0 && html[s - 1] != '"' && html[s - 1] != '\'' && html[s - 1] != '=' && html[s - 1] != ' ' &&
                    html[s - 1] != '('
                ) --s
                val origin = html.substring(s, p)
                val url = (origin.ifEmpty { CDN_HOST }) + BUILD_DIR + file
                if (!allowedHost(url)) return@run
                if (url !in out) out.add(url)
            }
            p = html.indexOf(BUILD_DIR, p + 1)
        }
        return out
    }

    /**
     * The lazily loaded chunks of the webpack runtime in [js]:
     *   u.u=e=>""+(({4406:"xpui-routes-search",...})[e]||e)+"."+({4406:"96d81afe",...})[e]+".js"
     * resolved against the runtime's public path (u.p="https://…/") or, when it has none or it is not a Spotify host
     * (open.spotifycdn.com / open.spotify.com), [baseUrl]. Source order.
     */
    fun findChunkUrls(js: String, baseUrl: String): List<ChunkRef> {
        // Public path: the runtime's absolute u.p="https://.../" (other embedded runtimes use r.p=""), Spotify hosts only.
        var base = baseUrl
        val pp = ".p=\"https://"
        var p = js.indexOf(pp)
        while (p >= 0) {
            val s = p + 4
            val e = js.indexOf('"', s)
            if (e >= 0 && e - s < 200 && js[e - 1] == '/' && allowedHost(js.substring(s, e))) {
                base = js.substring(s, e)
                break
            }
            p = js.indexOf(pp, p + 1)
        }
        if (base.isNotEmpty() && !base.endsWith('/')) base += '/'

        // "<name or id>" + "." + ({id:"hash",...})[e] + ".js"   (the ".css" twin map is skipped)
        val join = "+\".\"+({"
        p = js.indexOf(join)
        while (p >= 0) {
            val hs = p + join.length
            val he = js.indexOf('}', hs)
            if (he < 0) break
            if (!js.substring(he, minOf(js.length, he + 24)).contains("+\".js\"")) {
                p = js.indexOf(join, p + 1)
                continue
            }
            val hashes = parseFlatMap(js.substring(hs, he))
            if (hashes.isEmpty()) {
                p = js.indexOf(join, p + 1)
                continue
            }
            // Optional names map right before: (({1328:"xpui-pip-mini-player",...})[e]||e)
            var names: List<Pair<String, String>> = emptyList()
            val ne = js.lastIndexOf("})[", p)
            if (ne >= 0 && p - ne < 32 && js.substring(ne, p).contains("]||")) {
                val ns = js.lastIndexOf("({", ne)
                if (ns >= 0) names = parseFlatMap(js.substring(ns + 2, ne))
            }
            return hashes.map { (id, hash) ->
                val name = names.firstOrNull { it.first == id }?.second ?: id
                ChunkRef(name, "$base$name.$hash.js")
            }
        }
        return emptyList()
    }

    /** Result of [classifyFailure]: the kind and, for a VariableError, the variable the body names ("" if none). */
    data class Classified(val kind: QueryFailure, val variable: String = "")

    fun classifyFailure(status: Int, body: String): Classified {
        if (status == 412 || containsNoCase(body, "invalid query hash") || containsNoCase(body, "PersistedQueryNotFound") ||
            containsNoCase(body, "persisted query not found")
        ) return Classified(QueryFailure.HashRejected)
        if (status == 400 || status in 200..299) {
            // e.g. "Variable '$libraryItemUris' of required type '[String!]!' was not provided." or
            // VALIDATION_INVALID_TYPE_VARIABLE. The body names the variable: the request has to change, not the hash.
            var name = ""
            var d = body.indexOf('$')
            while (d >= 0 && name.isEmpty()) {
                var e = d + 1
                while (e < body.length && isIdentChar(body[e])) ++e
                if (e > d + 1 && body[d + 1] !in '0'..'9') name = body.substring(d + 1, e)
                d = body.indexOf('$', d + 1)
            }
            if (name.isNotEmpty() || containsNoCase(body, "variable") || body.contains("VALIDATION_"))
                return Classified(QueryFailure.VariableError, name)
        }
        return Classified(QueryFailure.Other)
    }

    /** Fetches a text resource: [HashFetchStatusError] for a non-2xx answer, [HashFetchTransportError] for no answer. */
    suspend fun fetchText(url: String): String {
        val resp = try {
            Http.await(HttpRequest(url, headers = listOf("User-Agent" to SPOTIFY_USER_AGENT, "Accept" to "*/*")))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw HashFetchTransportError("${e.message} ($url)")
        }
        if (!resp.isSuccess) throw HashFetchStatusError(resp.status, url)
        return resp.body
    }

    /**
     * Network scan: open.spotify.com + the main bundle, then — only while some op in [wanted] is missing — up to
     * [maxChunks] chunk files (the ones whose names match the missing ops first; ~45 s budget; a transport error ends
     * the loop, a chunk's HTTP 4xx only skips it), then the GitHub registry for the [wanted] ops still missing
     * (skipped after a transport error or the deadline). Only https://open.spotifycdn.com / open.spotify.com URLs are
     * fetched from the page. Throws only when nothing at all could be discovered. [fetch] is replaceable for tests.
     */
    suspend fun discoverHashes(
        wanted: List<String>,
        maxChunks: Int = 40,
        nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
        fetch: suspend (String) -> String = ::fetchText,
    ): Discovery {
        val d = Discovery()
        fun missing() = wanted.filter { it !in d.ops }
        var webError = ""
        var reachable = true // false after a transport failure or the chunk deadline: no registry fetch either
        try {
            val bundles = findBundleUrls(fetch(WEB_PLAYER))
            if (bundles.isEmpty()) throw IOException("no web-player bundle referenced by open.spotify.com")
            var mainJs = ""
            for (url in bundles) {
                val js = fetch(url)
                for (op in extractPersistedOps(js)) d.ops.putIfAbsent(op.name, op)
                if (d.bundle.isEmpty()) {
                    d.bundle = fileName(url)
                    mainJs = js
                }
            }
            d.complete = true
            // Ops of lazily loaded routes live in chunk files. Scan the ones whose names match a missing op first
            // ("searchDesktop" -> "xpui-routes-search"), then named chunks, then numbered ones; stop once all are found.
            val miss = missing()
            if (miss.isNotEmpty() && maxChunks > 0) {
                val words = miss.flatMap { nameWords(it) }
                val chunks = findChunkUrls(mainJs, bundles[0].substring(0, bundles[0].lastIndexOf('/') + 1))
                    .map { chunkScore(it.name, words) to it }
                    .sortedByDescending { it.first } // stable
                    .map { it.second }
                if (chunks.isEmpty()) Log.w(TAG, "no webpack chunk map found in ${d.bundle}")
                val deadline = nowMs() + CHUNK_BUDGET_MS
                for (c in chunks) {
                    if (d.chunksScanned >= maxChunks || missing().isEmpty()) break
                    if (nowMs() >= deadline) {
                        Log.w(TAG, "chunk scan stopped after ${d.chunksScanned} chunk(s): ${CHUNK_BUDGET_MS / 1000} s budget used")
                        d.complete = false
                        reachable = false
                        break
                    }
                    if (!allowedHost(c.url)) continue
                    ++d.chunksScanned
                    val js = try {
                        fetch(c.url)
                    } catch (e: HashFetchStatusError) {
                        if (e.status in 400..499) { // this chunk only (gone / renamed)
                            Log.d(TAG, "chunk ${c.name} skipped: ${e.message}")
                            continue
                        }
                        Log.w(TAG, "chunk scan stopped: ${e.message}")
                        d.complete = false
                        break
                    } catch (e: HashFetchTransportError) {
                        Log.w(TAG, "chunk scan stopped: ${e.message}")
                        d.complete = false
                        reachable = false
                        break
                    }
                    for (op in extractPersistedOps(js)) {
                        if (d.ops.putIfAbsent(op.name, op) == null && op.name in wanted) d.fromChunks.add(op.name)
                    }
                }
            }
        } catch (e: HashFetchTransportError) {
            webError = e.message ?: ""
            reachable = false
            d.complete = false
            Log.w(TAG, "web-player scan failed: $webError")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // an error status, or the page / bundle no longer has the expected shape
            webError = e.message ?: ""
            d.complete = false
            Log.w(TAG, "web-player scan failed: $webError")
        }

        // Last resort for the wanted ops still missing — only those: the registry is third-party data.
        val miss = missing()
        if (reachable && miss.isNotEmpty()) {
            try {
                val body = fetch(COMMUNITY_REGISTRY)
                val size = body.toByteArray(Charsets.UTF_8).size
                if (size > MAX_REGISTRY_BYTES) throw IOException("response too large ($size bytes)")
                fillFromRegistry(d, miss, parseJsonOrNull(body))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "community registry unavailable: ${e.message}")
            }
        }
        if (d.ops.isEmpty()) throw IOException("Spotify hash discovery failed: $webError")
        return d
    }

    internal fun fillFromRegistry(d: Discovery, miss: List<String>, j: JsonElement?) {
        val ops = (j as? JsonObject)?.get("operations") as? JsonObject ?: return
        for (name in miss) {
            val v = ops[name] as? JsonObject ?: continue
            val h = (v["hash"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (!isHex64(h)) continue
            val kind = (v["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "query"
            d.ops.putIfAbsent(name, PersistedOp(name, kind, h))
            d.fromRegistry.add(name)
        }
    }
}

/**
 * The live hash table (a process-wide singleton, like the C++ HashRegistry::instance()).
 */
object HashRegistry {
    private const val TAG = "hashes"
    const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L
    private const val REFRESH_INTERVAL_S = REFRESH_INTERVAL_MS / 1000

    /**
     * The hashes this build was verified with (web-player.da8b87c8.js, 2026-09-27/28). Ops that share a GraphQL
     * document share a hash: add/removeFromLibrary take ANY uri (track = save, album = save, artist = follow);
     * add/removeFromPlaylist share one. queryArtistOverview / searchDesktop are older documents than the current web
     * player's (9f8134ef... / 11483936...) that Spotify still serves; they heal to the current ones once purged.
     */
    val BUILTINS: List<Pair<String, String>> = listOf(
        "profileAttributes" to "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0",
        "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
        "fetchPlaylist" to "243c0ba2736f16da721e3a227004bbcdb8df6c846f198bd478172e00aa1faf42",
        "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
        "getAlbum" to "6a74b456cd1735c9193d9e8ec8cc5184cad7ce13572210315229db3975964361",
        "queryArtistOverview" to "7f86ff63e38c24973a2842b672abe44c910c1973978dc8a4a0cb648edef34527",
        "searchDesktop" to "d9f785900f0710b31c07818d617f4f7600c1e21217e80f5b043d1e78d74e6026",
        "home" to "76243c78b0e20ecdbe41b794dec8cbe73f75e585b0a7201b8d2e84578412847a",
        "areEntitiesInLibrary" to "134337999233cc6fdd6b1e6dbf94841409f04a946c5c7b744b09ba0dfe5a85ed",
        "addToLibrary" to "1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a",
        "removeFromLibrary" to "1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a",
        "addToPlaylist" to "47b2a1234b17748d332dd0431534f22450e9ecbb3d5ddcdacbd83368636a0990",
        "removeFromPlaylist" to "47b2a1234b17748d332dd0431534f22450e9ecbb3d5ddcdacbd83368636a0990",
    )

    fun builtin(op: String): String = BUILTINS.firstOrNull { it.first == op }?.second ?: ""

    private val lock = Any() // the maps below (held briefly; never across network I/O)
    private val healMutex = Mutex() // serializes heal(): one refresh at a time, waiters reuse its result
    private var loaded = false
    private var customPath: File? = null
    private val adopted = HashMap<String, String>() // op -> learned hash (wins over the built-in)
    private val replaced = HashMap<String, String>() // op -> the built-in it replaced

    // The last web-player scan of `bundle` (never community-registry data): what it found, and which ops it looked
    // for (every wanted op of a complete scan + everything it found). Both cleared when the bundle changes.
    private val discovered = sortedMapOf<String, String>()
    private val searched = sortedSetOf<String>()
    private val transient = HashSet<String>() // setOverride() ops: never saved
    private var bundle = ""
    private var fetchedAt = 0L // unix seconds of the last web-player scan (from disk or this process)
    private val lastAttempt = HashMap<String, Long>() // per op, this process (monotonic ms)

    /** Monotonic ms (the C++ steady_clock); replaceable in tests. */
    @Volatile
    internal var steadyMs: () -> Long = { System.nanoTime() / 1_000_000 }

    /** The network scan heal() runs; replaceable in tests. */
    @Volatile
    internal var discover: suspend (List<String>) -> Discovery = { HashDiscovery.discoverHashes(it) }

    private fun unixNow() = System.currentTimeMillis() / 1000
    private fun prefix(hash: String) = if (hash.isEmpty()) "(none)" else hash.take(8) + "..."
    private val path: File get() = customPath ?: File(Paths.appData, "spotify-hashes.json")

    /** The hash to send for [op]: an adopted (learned) one, else the built-in one, else one the last scan discovered. "" when unknown. */
    fun hash(op: String): String = synchronized(lock) {
        ensureLoaded()
        lookup(op)
    }

    /** The bundle of the last scan ("" = none yet). */
    fun bundle(): String = synchronized(lock) {
        ensureLoaded()
        bundle
    }

    /**
     * Where learned hashes persist (default: Paths.appData/spotify-hashes.json; null = back to the default). Drops the
     * in-memory state; the file is (re)read on the next use. Tests / diagnostics.
     */
    fun setStorePath(file: File?) = synchronized(lock) {
        customPath = file
        loaded = false
        adopted.clear(); replaced.clear(); discovered.clear(); searched.clear(); transient.clear()
        bundle = ""
        fetchedAt = 0
        lastAttempt.clear()
    }

    /** Makes [op] use [hash] (in memory only, like an adopted hash that was never saved). */
    fun setOverride(op: String, hash: String) = synchronized(lock) {
        ensureLoaded()
        adopted[op] = hash
        transient.add(op)
    }

    private fun lookup(op: String): String = adopted[op] ?: builtin(op).ifEmpty { discovered[op] ?: "" }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = path
        if (!f.exists()) return
        val j = Files.readJson(f) as? JsonObject
        if (j == null) {
            Log.w(TAG, "ignoring unreadable ${f.name}")
            return
        }
        SpJson.at(j, "bundle")?.let { if (SpJson.isStr(it)) bundle = SpJson.strOf(it) }
        SpJson.at(j, "fetchedAt")?.let { if (SpJson.isInt(it)) fetchedAt = (it as JsonPrimitive).content.toLong() }
        fun strings(key: String): Map<String, String> {
            val m = sortedMapOf<String, String>()
            (j[key] as? JsonObject)?.forEach { (op, h) -> if (SpJson.isStr(h)) m[op] = SpJson.strOf(h) }
            return m
        }
        for ((op, h) in strings("discovered")) if (HashDiscovery.isHex64(h)) discovered[op] = h
        (j["searched"] as? JsonArray)?.forEach { if (SpJson.isStr(it)) searched.add(SpJson.strOf(it)) }
        val rep = strings("replaced")
        for ((op, h) in strings("hashes")) {
            if (!HashDiscovery.isHex64(h)) continue
            // A learned hash only overrides the built-in it replaced: a newer build with an updated table wins.
            val b = builtin(op)
            if (b.isNotEmpty() && rep[op] != b) {
                Log.i(TAG, "$op: dropping learned ${prefix(h)} (this build's built-in hash changed)")
                continue
            }
            adopted[op] = h
            if (b.isNotEmpty()) replaced[op] = b
        }
        if (adopted.isNotEmpty()) Log.i(TAG, "${adopted.size} learned hash(es) in use (bundle ${bundle.ifEmpty { "?" }})")
    }

    private fun save() {
        val hashes = sortedMapOf<String, String>()
        for ((op, h) in adopted) if (op !in transient) hashes[op] = h
        val rep = sortedMapOf<String, String>()
        for ((op, h) in replaced) if (op in hashes) rep[op] = h
        val j = JsonObject(
            linkedMapOf(
                "bundle" to JsonPrimitive(bundle),
                "fetchedAt" to JsonPrimitive(fetchedAt),
                "hashes" to JsonObject(hashes.mapValues { JsonPrimitive(it.value) }),
                "replaced" to JsonObject(rep.mapValues { JsonPrimitive(it.value) }),
                "discovered" to JsonObject(discovered.mapValues { JsonPrimitive(it.value) }),
                "searched" to JsonArray(searched.map { JsonPrimitive(it) }),
            ),
        )
        // Write a temp file and swap it in only when it was written completely: a full disk must not cost the old file.
        if (!Files.writeJson(path, j, pretty = true)) Log.w(TAG, "cannot save ${path.name}; keeping the previous file")
    }

    private fun adopt(op: String, hash: String, source: String) {
        val before = lookup(op)
        val b = builtin(op)
        transient.remove(op)
        if (hash == b) {
            adopted.remove(op)
            replaced.remove(op)
        } else {
            adopted[op] = hash
            if (b.isEmpty()) replaced.remove(op) else replaced[op] = b
        }
        Log.i(TAG, "$op: ${prefix(before)} -> ${prefix(hash)} ($source)")
    }

    /**
     * [op] was rejected with [rejectedHash] (QueryFailure.HashRejected). Returns the hash to retry with, or "" when
     * there is no different one. Scans the web player when needed: at most once per 10 minutes per op in this
     * process, and not when a scan of the current bundle less than 10 minutes old (also from spotify-hashes.json)
     * already looked for this op. A recent scan that knows a different hash answers directly; an older scan's hash
     * is only the fallback when the new scan fails. Concurrent callers wait for one scan and share its result.
     * Main-safe (network + disk on the io dispatcher).
     */
    suspend fun heal(op: String, rejectedHash: String): String = withContext(AppDispatchers.io) {
        healMutex.withLock { healLocked(op, rejectedHash) }
    }

    private suspend fun healLocked(op: String, rejectedHash: String): String {
        var known = "" // a different hash from the last scan (maybe an old one, from disk)
        synchronized(lock) {
            ensureLoaded()
            // Another query healed this op while we waited for healMutex.
            val current = lookup(op)
            if (current.isNotEmpty() && current != rejectedHash) return current
            discovered[op]?.let { if (it != rejectedHash) known = it }
            val now = steadyMs()
            val age = unixNow() - fetchedAt
            val scanFresh = fetchedAt > 0 && age >= 0 && age < REFRESH_INTERVAL_S
            val last = lastAttempt[op]
            val opThrottled = last != null && now - last < REFRESH_INTERVAL_MS
            if (known.isNotEmpty() && (scanFresh || opThrottled)) { // a recent scan already has the answer
                adopt(op, known, "last scan of " + bundle.ifEmpty { "?" })
                save()
                return known
            }
            if (opThrottled) {
                Log.w(TAG, "$op: hash ${prefix(rejectedHash)} rejected; no new scan (scanned for it ${(now - last!!) / 1000} s ago)")
                return ""
            }
            // A recent scan only settles it when it actually looked for this op (a scan for another op may not have
            // opened the chunk this one lives in).
            if (scanFresh && op in searched) {
                Log.w(TAG, "$op: hash ${prefix(rejectedHash)} rejected; no new scan (${bundle.ifEmpty { "?" }} was searched for it $age s ago)")
                return ""
            }
            lastAttempt[op] = now
        }

        Log.i(TAG, "$op: hash ${prefix(rejectedHash)} rejected, scanning the web player for the current one")
        val t0 = steadyMs()
        val d: Discovery
        try {
            d = discover(listOf(op))
        } catch (e: CancellationException) {
            synchronized(lock) { lastAttempt.remove(op) } // an aborted scan doesn't count against the throttle
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                Log.w(TAG, "$op: refresh failed: ${e.message}")
                if (known.isEmpty()) return ""
                adopt(op, known, "older scan of " + bundle.ifEmpty { "?" }) // better than nothing
                save()
                return known
            }
        }
        val ms = steadyMs() - t0
        fun fromRegistry(name: String) = name in d.fromRegistry

        synchronized(lock) {
            // Only a web-player result describes a bundle (registry fill-ins are third-party data: adopted, never cached).
            if (d.bundle.isNotEmpty()) {
                if (d.bundle != bundle) { // a new deploy: forget the old scan
                    discovered.clear()
                    searched.clear()
                }
                for ((name, o) in d.ops) if (!fromRegistry(name)) {
                    discovered[name] = o.hash
                    searched.add(name)
                }
                if (d.complete) searched.add(op)
                bundle = d.bundle
                fetchedAt = unixNow()
            }
            val extra = StringBuilder()
            for (n in d.fromChunks) extra.append(if (extra.isEmpty()) ", in chunks: " else " ").append(n)
            for (n in d.fromRegistry) extra.append(" registry:").append(n)
            Log.i(
                TAG,
                "scanned ${d.bundle.ifEmpty { "(web player unreachable)" }}: ${d.ops.size} ops, ${d.chunksScanned} chunk(s) " +
                    "downloaded${if (d.complete) "" else " (incomplete)"}$extra in $ms ms",
            )

            var fresh = ""
            var source = ""
            val found = d.ops[op]
            if (found != null) {
                fresh = found.hash
                source = if (fromRegistry(op)) "community registry" else d.bundle
            } else if (known.isNotEmpty() && (d.bundle.isEmpty() || !d.complete)) { // the scan fell short: an older scan's answer
                fresh = known
                source = "older scan"
            }
            if (fresh.isEmpty()) {
                Log.w(TAG, "$op: not found in the web player nor the community registry")
            } else if (fresh == rejectedHash) {
                Log.w(TAG, "$op: the web player still sends ${prefix(rejectedHash)}: the rejection is not a rotated hash")
                fresh = ""
            } else {
                adopt(op, fresh, source)
            }
            save()
            return fresh
        }
    }
}
