package com.app.shadetube.musicbrainz

import com.app.shadetube.core.parseStrictJson
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.HttpResponse
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A non-2xx answer of MusicBrainz / ListenBrainz / Wikidata after retries (port of `mb::ApiError`). */
class MbApiError(val status: Int, message: String) : IOException(message)

/**
 * Internal transport for the open-catalog client (port of musicbrainz/Net): polite rate limiting per service,
 * retries, and a two-level response cache (in-memory LRU of body strings + disk under `Paths.cacheDir/mb`).
 * Not part of the public API ([MusicBrainz] is).
 *
 * Etiquette:
 *  - MusicBrainz: max 1 request / second per client (one global slot shared by every coroutine), a meaningful
 *    User-Agent "ShadeTube/<ver> ( <contact> )", 5xx / 429 -> back off (1 s, 2 s, 4 s, shared) and retry.
 *  - ListenBrainz: honours X-RateLimit-Remaining / X-RateLimit-Reset-In.
 *  - Responses are cached on disk (`cache/mb/<fnv1a hex>.json`, "<key>\n<body>", mtime = stored time) with TTLs
 *    (lookups 7 days, searches 1 day, ListenBrainz stats 12 hours, Wikidata 30 days) so the app feels instant and
 *    stays far below the limits. When the network fails (transport error, 5xx, 429, invalid JSON) a stale entry is
 *    served instead.
 */
internal object MbNet {
    enum class Service { MusicBrainz, ListenBrainz, Wikidata }

    /** Cache lifetimes in seconds. */
    object Ttl {
        const val LOOKUP = 7L * 24 * 3600
        const val SEARCH = 24L * 3600
        const val LISTENBRAINZ = 12L * 3600
        const val WIKIDATA = 30L * 24 * 3600
    }

    class Request(
        val service: Service = Service.MusicBrainz,
        val url: String,
        val method: String = "GET",
        /** POST body (JSON). */
        val body: String = "",
        val ttlSec: Long = Ttl.LOOKUP,
        /** Never touch the network; null on a miss. */
        val cacheOnly: Boolean = false,
        /** ListenBrainz: add "Authorization: Token <t>" when a token is configured. */
        val sendToken: Boolean = false,
        /** false: don't cache the raw body (the caller caches a derived value). */
        val store: Boolean = true,
    )

    data class Stats(val networkRequests: Int, val memoryHits: Int, val diskHits: Int)

    const val USER_AGENT = "ShadeTube/0.1 ( https://github.com/shadesofdeath/ShadeTubeAndroid )"
    private const val LRU_MAX_ENTRIES = 200
    /** Approximated in UTF-16 chars (the C++ counts bytes). */
    private const val LRU_MAX_BYTES = 24L * 1024 * 1024
    private const val MAX_RETRIES = 3

    private val netRequests = AtomicInteger()
    private val memHits = AtomicInteger()
    private val diskHits = AtomicInteger()
    private val tmpCounter = AtomicLong()

    /** Where the disk cache lives; tests point it at a temp folder. */
    @Volatile internal var cacheRootProvider: () -> File = { File(Paths.cacheDir, "mb") }


    private fun steadyMs(): Long = System.nanoTime() / 1_000_000

    // ---- MusicBrainz: global 1 request / second -----------------------------------------------------------------
    private val mbLock = Any()
    private var mbNext = 0L

    private suspend fun mbAcquireSlot() {
        val slot: Long
        synchronized(mbLock) {
            slot = maxOf(steadyMs(), mbNext)
            mbNext = slot + 1000
        }
        sleepUntil(slot)
    }

    private fun mbPushBack(ms: Long) {
        synchronized(mbLock) { mbNext = maxOf(mbNext, steadyMs() + ms) }
    }

    // ---- ListenBrainz: honour X-RateLimit-* ---------------------------------------------------------------------
    private val lbLock = Any()
    private var lbNotBefore = 0L
    private var lbToken = ""

    private suspend fun lbWait() {
        val t = synchronized(lbLock) { lbNotBefore }
        sleepUntil(t)
    }

    private fun headerInt(r: HttpResponse, name: String, fallback: Int): Int =
        r.header(name)?.trim()?.toIntOrNull() ?: fallback

    private fun lbObserve(r: HttpResponse) {
        val remaining = headerInt(r, "X-RateLimit-Remaining", -1)
        val resetIn = headerInt(r, "X-RateLimit-Reset-In", 1).coerceIn(0, 60)
        if (r.status == 429 || remaining == 0) {
            synchronized(lbLock) { lbNotBefore = maxOf(lbNotBefore, steadyMs() + maxOf(resetIn, 1) * 1000L) }
        }
    }

    /** Cancellation-aware sleep (delay throws CancellationException when the caller is cancelled). */
    private suspend fun sleepUntil(t: Long) {
        currentCoroutineContext().ensureActive()
        val wait = t - steadyMs()
        if (wait > 0) delay(wait)
    }

    // ---- cache ----------------------------------------------------------------------------------------------------
    private class LruEntry(val body: String, val storedAt: Long)

    private val lruLock = Any()
    /** Insertion order = recency (oldest first); a hit is moved to the end. */
    private val lru = LinkedHashMap<String, LruEntry>()
    private var lruBytes = 0L

    private fun lruPut(key: String, body: String, storedAt: Long) = synchronized(lruLock) {
        lru.remove(key)?.let { lruBytes -= it.body.length }
        if (body.length > LRU_MAX_BYTES / 4) return@synchronized // don't let one huge body evict everything
        lruBytes += body.length
        lru[key] = LruEntry(body, storedAt)
        val it = lru.entries.iterator()
        while ((lru.size > LRU_MAX_ENTRIES || lruBytes > LRU_MAX_BYTES) && it.hasNext()) {
            val e = it.next()
            lruBytes -= e.value.body.length
            it.remove()
        }
    }

    private fun lruGet(key: String, ttlSec: Long): String? = synchronized(lruLock) {
        val e = lru[key] ?: return null
        if (System.currentTimeMillis() - e.storedAt > ttlSec * 1000) return null
        lru.remove(key)
        lru[key] = e
        e.body
    }

    private fun cacheRoot(): File = cacheRootProvider().also { if (!it.exists()) it.mkdirs() }

    private fun cacheFile(key: String): File = File(cacheRoot(), "%016x.json".format(fnv1a(key)))

    private class DiskEntry(val body: String, val storedAt: Long)

    /** File format: "<key>\n<body>" (the key line guards against hash collisions). */
    private fun diskGet(key: String): DiskEntry? {
        val f = cacheFile(key)
        if (!f.isFile) return null
        val storedAt = f.lastModified()
        val text = runCatching { f.readBytes().toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val nl = text.indexOf('\n')
        if (nl < 0 || text.substring(0, nl) != key) return null
        return DiskEntry(text.substring(nl + 1), storedAt)
    }

    private fun diskPut(key: String, body: String) {
        val target = cacheFile(key)
        val tmp = File(target.path + ".%x.%d.tmp".format(System.identityHashCode(Thread.currentThread()), tmpCounter.getAndIncrement()))
        try {
            tmp.writeBytes((key + "\n" + body).toByteArray(Charsets.UTF_8))
            // rename(2) replaces atomically on Linux.
            if (!tmp.renameTo(target)) {
                Log.w("mb", "cache write failed: rename ${tmp.name}")
                tmp.delete()
            }
        } catch (e: IOException) {
            Log.w("mb", "cache write failed: ${e.message}")
            tmp.delete()
        }
    }

    private fun requestKey(r: Request): String {
        var k = r.method + " " + r.url
        if (r.body.isNotEmpty()) k += " " + "%016x".format(fnv1a(r.body))
        return k
    }

    private fun parseBody(body: String): JsonElement? = parseStrictJson(body)

    private fun isRetryableStatus(s: Int) = s == 503 || s == 502 || s == 504 || s == 429 || s == 500

    /** One network round trip with service etiquette. Returns the final response (possibly non-2xx). */
    private suspend fun sendWithPolicy(r: Request): HttpResponse {
        val headers = mutableListOf("User-Agent" to USER_AGENT, "Accept" to "application/json")
        if (r.service == Service.ListenBrainz && r.sendToken) {
            val token = synchronized(lbLock) { lbToken }
            if (token.isNotEmpty()) headers += "Authorization" to "Token $token"
        }
        val req = HttpRequest(
            url = r.url,
            method = r.method,
            headers = headers,
            body = r.body.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
            contentType = if (r.body.isNotEmpty()) "application/json" else null,
        )
        var attempt = 0
        while (true) {
            when (r.service) {
                Service.MusicBrainz -> mbAcquireSlot()
                Service.ListenBrainz -> lbWait()
                Service.Wikidata -> currentCoroutineContext().ensureActive()
            }
            netRequests.incrementAndGet()
            val resp = Http.await(req)
            if (r.service == Service.ListenBrainz) lbObserve(resp)
            if (!isRetryableStatus(resp.status) || attempt >= MAX_RETRIES) return resp

            // Back off: MusicBrainz 1 s, 2 s, 4 s (shared with every caller); ListenBrainz waits Reset-In on 429.
            val backoffMs = (1L shl attempt) * 1000
            Log.w("mb", "HTTP ${resp.status} for ${r.url} (attempt ${attempt + 1}), backing off")
            attempt++
            when {
                r.service == Service.MusicBrainz -> mbPushBack(backoffMs)
                // lbObserve already scheduled lbNotBefore; lbWait honours it on the next attempt.
                r.service == Service.ListenBrainz && resp.status == 429 -> Unit
                else -> sleepUntil(steadyMs() + backoffMs)
            }
        }
    }

    fun fnv1a(s: String): Long {
        var h = -0x340d631b7bdddcdbL // 1469598103934665603
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h *= 1099511628211L
        }
        return h
    }

    /**
     * Parsed JSON response. Throws [MbApiError] on non-2xx after retries, IOException on transport failures,
     * CancellationException on cancellation. Returns null only for `cacheOnly` misses. Main-safe.
     */
    suspend fun fetch(r: Request): JsonElement? = withContext(AppDispatchers.io) {
        currentCoroutineContext().ensureActive()
        val key = requestKey(r)

        lruGet(key, r.ttlSec)?.let { body ->
            parseBody(body)?.let {
                memHits.incrementAndGet()
                return@withContext it
            }
        }

        var stale: DiskEntry? = null
        diskGet(key)?.let { disk ->
            if (System.currentTimeMillis() - disk.storedAt <= r.ttlSec * 1000) {
                parseBody(disk.body)?.let {
                    diskHits.incrementAndGet()
                    lruPut(key, disk.body, disk.storedAt)
                    return@withContext it
                }
            } else {
                stale = disk
            }
        }
        if (r.cacheOnly) return@withContext null

        fun useStale(): JsonElement? {
            val s = stale ?: return null
            Log.w("mb", "serving stale cache for ${r.url}")
            return parseBody(s.body)
        }

        val resp = try {
            sendWithPolicy(r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            useStale()?.let { return@withContext it }
            throw e
        }

        if (!resp.isSuccess) {
            if (resp.status >= 500 || resp.status == 429) useStale()?.let { return@withContext it }
            var msg = "HTTP ${resp.status} from ${r.url}"
            val err = (parseBody(resp.body) as? JsonObject)?.get("error") as? JsonPrimitive
            if (err != null && err.isString) msg += ": " + err.content
            throw MbApiError(resp.status, msg)
        }

        val j = parseBody(resp.body)
            ?: return@withContext useStale() ?: throw MbApiError(resp.status, "invalid JSON from ${r.url}")
        if (!r.store) return@withContext j
        diskPut(key, resp.body)
        lruPut(key, resp.body, System.currentTimeMillis())
        j
    }

    /** Raw cache access for derived values (e.g. the Commons file name extracted from a large Wikidata entity). */
    suspend fun cacheGet(key: String, ttlSec: Long): String? = withContext(AppDispatchers.io) {
        lruGet(key, ttlSec)?.let { return@withContext it }
        val disk = diskGet(key)
        if (disk != null && System.currentTimeMillis() - disk.storedAt <= ttlSec * 1000) {
            lruPut(key, disk.body, disk.storedAt)
            return@withContext disk.body
        }
        null
    }

    suspend fun cachePut(key: String, value: String) = withContext(AppDispatchers.io) {
        diskPut(key, value)
        lruPut(key, value, System.currentTimeMillis())
    }

    fun setListenBrainzToken(token: String) = synchronized(lbLock) { lbToken = token }

    fun hasListenBrainzToken(): Boolean = synchronized(lbLock) { lbToken.isNotEmpty() }

    /** Diagnostics for tests. */
    fun stats() = Stats(netRequests.get(), memHits.get(), diskHits.get())

    fun clearMemoryCache() = synchronized(lruLock) {
        lru.clear()
        lruBytes = 0
    }
}
