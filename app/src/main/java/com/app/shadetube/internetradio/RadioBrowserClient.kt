package com.app.shadetube.internetradio

import com.app.shadetube.core.executeCancellable
import com.app.shadetube.core.readCapped
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.Log
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * radio-browser.info client (port of radio::Client), following the etiquette their API docs ask for:
 * - servers: the list at all.api.radio-browser.info (json/servers, else a reverse DNS lookup of that name, else a
 *   built-in list), shuffled once; a failing server is skipped for a few minutes and the request goes to the next;
 * - a descriptive User-Agent ("ShadeTube/<version> (+repository)", see [userAgent]);
 * - json/url/<stationuuid> once when a station starts playing (their click counter, [countClick]).
 *
 * Requests are `suspend`, main-safe and cancellable, with per-request deadlines (8 s, 20 s per request with failover),
 * capped bodies (4 MB) and a 10-minute cache of the list endpoints.
 */
class RadioBrowserClient {
    companion object {
        private const val REQUEST_TIMEOUT_MS = 8_000L
        private const val DISCOVERY_TIMEOUT_MS = 5_000L

        /** One request, failover included. */
        private const val BUDGET_MS = 20_000L
        private const val CACHE_TTL_MS = 10 * 60_000L
        private const val CACHE_MAX = 64

        /** 100 stations ~ 120 KB, countries ~ 15 KB. */
        private const val MAX_BODY = 4L shl 20
        private const val SERVER_DOWN_MS = 5 * 60_000L
        private const val SERVERS_FRESH_MS = 6 * 3600_000L
        private const val DISCOVERY_HOST = "all.api.radio-browser.info"
        private val FALLBACK_SERVERS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info")

        /** Keeps the byuuid URL short. */
        private const val UUID_CHUNK = 40

        private fun nowMs() = System.nanoTime() / 1_000_000

        /**
         * An empty body, an HTML maintenance page or an error object in a 2xx answer is the server failing, not data: it
         * is never cached. [list]: the endpoint returns a JSON array.
         */
        internal fun validBody(bodyIn: String, list: Boolean): Boolean {
            val body = bodyIn.removePrefix("\uFEFF")
            val first = body.indexOfFirst { it != ' ' && it != '\t' && it != '\r' && it != '\n' }
            if (first < 0 || (list && body[first] != '[')) return false
            return parseJsonOrNull(body) != null
        }
    }

    /**
     * Transport failure (DNS, refused, reset, deadline), a server error (5xx / 429) or a 2xx body that is not the JSON
     * the endpoint returns: try another server.
     */
    private class Unreachable(message: String) : RadioError(message)

    /** "ShadeTube/<version> (+https://github.com/shadesofdeath/ShadeTubeAndroid)"; set once at startup. */
    @Volatile
    var userAgent: String = "ShadeTube (+https://github.com/shadesofdeath/ShadeTubeAndroid)"

    private val lock = Any()
    private val discovery = Mutex() // serializes server discovery
    private var hosts: List<String> = emptyList() // shuffled once per discovery; hosts[current] is tried first
    private var current = 0
    private var pinned = false
    private var hostsAt = 0L
    private val downUntil = HashMap<String, Long>()

    private class Entry(val body: String, val at: Long)

    private val cache = HashMap<String, Entry>()

    /** Tests / dev: use these servers instead of the discovery (empty = discover again). */
    fun setServers(list: List<String>) = synchronized(lock) {
        pinned = list.isNotEmpty()
        hosts = list
        current = 0
        hostsAt = if (pinned) nowMs() else 0
        downUntil.clear()
    }

    /** "" before the first request. */
    fun currentServer(): String = synchronized(lock) { if (hosts.isEmpty()) "" else hosts[current % hosts.size] }

    fun clearCache() = synchronized(lock) { cache.clear() }
    fun cacheSize(): Int = synchronized(lock) { cache.size }

    // ---- transport ------------------------------------------------------------------------------------------------------

    private suspend fun perform(url: String, deadlineMs: Long): String {
        val left = deadlineMs - nowMs()
        if (left <= 0) throw Unreachable("timed out (connect)")
        val client = Http.client.newBuilder()
            .callTimeout(minOf(left, 60_000L), TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(left, 60_000L), TimeUnit.MILLISECONDS)
            .readTimeout(minOf(left, 60_000L), TimeUnit.MILLISECONDS)
            .build()
        // The agent string is set per request: the version is known only after startup.
        val call = client.newCall(
            Http.buildRequest(HttpRequest(url, headers = listOf("User-Agent" to userAgent, "Accept" to "application/json"))),
        )
        return try {
            call.executeCancellable { r ->
                if (r.code >= 500 || r.code == 429) throw Unreachable("HTTP ${r.code}")
                if (r.code !in 200..299) throw RadioError("radio-browser.info: HTTP ${r.code}")
                r.readCapped(MAX_BODY) { RadioError("radio-browser.info: response too large") }.decodeToString()
            }
        } catch (e: RadioError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Reverse DNS of all.api.radio-browser.info: the documented way to list the servers when json/servers fails. */
    private fun dnsServers(): List<String> {
        val addrs = runCatching { InetAddress.getAllByName(DISCOVERY_HOST) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        for (a in addrs) {
            val name = runCatching { InetAddress.getByAddress(a.address).canonicalHostName }.getOrNull()?.lowercase() ?: continue
            if (name.endsWith(".api.radio-browser.info") && name !in out) out.add(name)
        }
        return out
    }

    private fun fresh(): Boolean = hosts.isNotEmpty() && (pinned || nowMs() - hostsAt < SERVERS_FRESH_MS)

    private suspend fun servers(): List<String> {
        synchronized(lock) { if (fresh()) return hosts }
        // One discovery at a time: the requests of a page opening together wait for it instead of each asking.
        return discovery.withLock {
            synchronized(lock) { if (fresh()) return@withLock hosts }
            var list: List<String> = emptyList()
            try {
                list = RadioJson.parseServers(perform("https://$DISCOVERY_HOST/json/servers", nowMs() + DISCOVERY_TIMEOUT_MS))
            } catch (e: RadioError) {
                Log.w("iradio", "server list unavailable (${e.message}); asking DNS")
            }
            if (list.isEmpty()) list = withContext(AppDispatchers.io) { dnsServers() }
            if (list.isEmpty()) {
                Log.w("iradio", "no servers discovered; using the built-in list")
                list = FALLBACK_SERVERS
            }
            list = list.shuffled() // spread the load (API docs)
            synchronized(lock) {
                if (!pinned) {
                    hosts = list
                    current = 0
                    hostsAt = nowMs()
                    Log.i("iradio", "${list.size} server(s), using ${list.first()}")
                }
                hosts
            }
        }
    }

    private suspend fun get(pathAndQuery: String, useCache: Boolean): String {
        if (useCache) {
            synchronized(lock) {
                val hit = cache[pathAndQuery]
                if (hit != null && nowMs() - hit.at < CACHE_TTL_MS) return hit.body
            }
        }
        val hosts = servers()
        val end = nowMs() + BUDGET_MS
        val start = synchronized(lock) { current }
        var lastError = "no servers"
        // Servers marked down are skipped unless every server is (then they are all tried again).
        for (round in 0 until 2) {
            for (k in hosts.indices) {
                val i = (start + k) % hosts.size
                val host = hosts[i]
                val down = synchronized(lock) { downUntil[host] }
                if (round == 0 && down != null && nowMs() < down) continue
                currentCoroutineContext().ensureActive()
                if (nowMs() >= end) throw RadioError("radio-browser.info: $lastError")
                try {
                    val body = perform("https://$host$pathAndQuery", minOf(nowMs() + REQUEST_TIMEOUT_MS, end))
                    // The cached requests are the list endpoints (stations, tags, countries).
                    if (!validBody(body, useCache)) throw Unreachable(if (body.isEmpty()) "empty response" else "not the expected JSON")
                    synchronized(lock) {
                        downUntil.remove(host)
                        current = i
                        if (useCache) {
                            if (cache.size >= CACHE_MAX) cache.minByOrNull { it.value.at }?.let { cache.remove(it.key) }
                            cache[pathAndQuery] = Entry(body, nowMs())
                        }
                    }
                    return body
                } catch (e: Unreachable) {
                    lastError = "$host: ${e.message}"
                    Log.w("iradio", "$host failed: ${e.message}")
                    synchronized(lock) { downUntil[host] = nowMs() + SERVER_DOWN_MS }
                }
            }
        }
        throw RadioError("radio-browser.info: $lastError")
    }

    private suspend fun stationPage(pathAndQuery: String, limit: Int, offset: Int): StationPage {
        val body = get(pathAndQuery, true)
        return withContext(AppDispatchers.cpu) {
            val j = parseJsonOrNull(body) ?: throw RadioError("radio-browser.info: bad JSON")
            val all = RadioJson.stationsFrom(j)
            val rows = (j as? kotlinx.serialization.json.JsonArray)?.size ?: 0
            StationPage(RadioModel.filterPlayable(all), rows >= limit, offset + limit)
        }
    }

    // ---- API ------------------------------------------------------------------------------------------------------------

    /** Most listened in the last 24 h. */
    suspend fun topClick(limit: Int, offset: Int = 0): StationPage =
        stationPage("/json/stations/topclick?limit=$limit&offset=$offset&hidebroken=true", limit, offset)

    /** Most voted. */
    suspend fun topVote(limit: Int, offset: Int = 0): StationPage =
        stationPage("/json/stations/topvote?limit=$limit&offset=$offset&hidebroken=true", limit, offset)

    /** By name / tag (genre) / country / language. */
    suspend fun search(q: RadioQuery): StationPage = stationPage(RadioModel.searchPath(q), q.limit, q.offset)

    suspend fun tags(limit: Int): List<RadioTag> =
        RadioJson.parseTags(get("/json/tags?order=stationcount&reverse=true&hidebroken=true&limit=$limit", true))

    suspend fun countries(): List<RadioCountry> =
        RadioJson.parseCountries(get("/json/countries?order=stationcount&reverse=true&hidebroken=true", true))

    /** Fresh data of known stations (unfiltered). */
    suspend fun byUuids(uuids: List<String>): List<Station> {
        val out = ArrayList<Station>()
        for (chunk in uuids.chunked(UUID_CHUNK)) {
            val q = chunk.joinToString(",") { Http.urlEncode(it) }
            out += RadioJson.parseStations(get("/json/stations/byuuid?uuids=$q", true))
        }
        return out
    }

    /** Click counting (json/url/<uuid>) when a station starts playing: never cached; false on failure (logged). */
    suspend fun countClick(uuid: String): Boolean {
        if (uuid.isEmpty()) return false
        return try {
            get("/json/url/" + Http.urlEncode(uuid), false)
            Log.d("iradio", "click counted for $uuid")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("iradio", "click count for $uuid failed: ${e.message}")
            false
        }
    }
}
