package com.app.shadetube.podcasts

import com.app.shadetube.core.executeCancellable
import com.app.shadetube.core.PublicOnlyInterceptor
import com.app.shadetube.core.readCapped
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Files
import com.app.shadetube.core.Hash
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.Log
import com.app.shadetube.core.get
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Port of podcast::Client: Apple's directory (itunes.apple.com search / lookup, rss.applemarketingtools.com top shows,
 * no key), the shows' feeds and their audio.
 *
 * Everything is `suspend`, main-safe and cancellable, with deadlines and capped bodies. Directory answers are cached in
 * memory for 10 minutes, parsed feeds on disk (cache/podcasts/<fnv>.json) until they are older than [FEED_TTL_MS] or a
 * refresh is asked for. Anything the directory or a feed supplies must be on the public internet: only a feed the
 * user added by its URL (a self-hosted server) may be on this device / its network, and so may its audio
 * (`allowLocal`).
 */
class PodcastClient {
    companion object {
        const val FEED_TTL_MS = 30 * 60_000L
        private const val DIRECTORY_TTL_MS = 10 * 60_000L
        private const val DIRECTORY_MAX = 2L * 1024 * 1024
        private const val FEED_MAX = 24L * 1024 * 1024
        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val MEM_CACHE_MAX = 48
        private const val MAX_DOWNLOAD = 4L shl 30

        /** The directory's store code: ISO 3166 alpha-2 in lower case, "us" for anything else. */
        fun countryCode(country: String): String {
            val cc = asciiLower(trimXml(country))
            return if (cc.length == 2 && isAsciiAlpha(cc[0]) && isAsciiAlpha(cc[1])) cc else "us"
        }

        private fun htmlPage(contentType: String) = contentType == "text/html" || contentType == "application/xhtml+xml"
    }

    /** "ShadeTube/<version> (+https://github.com/shadesofdeath/ShadeTubeAndroid)"; set once at startup (media requests). */
    @Volatile
    var userAgent: String = "ShadeTube"

    /** Where parsed feeds are kept (null = nowhere). Created on demand. */
    @Volatile
    var cacheDir: File? = null

    private class Entry(val body: ByteArray, val atNs: Long)

    private val cache = HashMap<String, Entry>()

    private val anyClient: OkHttpClient get() = Http.client
    private val publicClient: OkHttpClient by lazy { Http.client.newBuilder().addNetworkInterceptor(PublicOnlyInterceptor).build() }

    /** Audio: C++ WinHTTP timeouts (10 s resolve / connect, 20 s receive), redirects always followed. */
    private fun mediaClient(allowLocal: Boolean): OkHttpClient =
        (if (allowLocal) anyClient else publicClient).newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true) // podcast hosts chain analytics redirects, now and then from https to plain http
            .followSslRedirects(true)
            .build()

    /** The in-memory directory cache. */
    fun clearCache() = synchronized(cache) { cache.clear() }

    private suspend fun get(url: String, maxBytes: Long, allowLocal: Boolean, useCache: Boolean): ByteArray {
        if (useCache) {
            synchronized(cache) {
                val hit = cache[url]
                if (hit != null && System.nanoTime() - hit.atNs < DIRECTORY_TTL_MS * 1_000_000) return hit.body
            }
        }
        if (!PodcastText.isHttpUrl(url)) throw PodcastError("not an http(s) address")
        val body = withContext(AppDispatchers.io) {
            if (!allowLocal && Http.isLocalUrl(url, true)) throw PodcastError("an address on the local network")
            val client = (if (allowLocal) anyClient else publicClient).newBuilder()
                .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
            val call = client.newCall(Http.buildRequest(HttpRequest(url)))
            try {
                call.executeCancellable { r ->
                    if (!r.isSuccessful) throw PodcastError("HTTP ${r.code}")
                    r.readCapped(maxBytes) { PodcastError("response too large") }
                }
            } catch (e: PodcastError) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw PodcastError(e.message ?: e.javaClass.simpleName)
            }
        }
        if (useCache) {
            synchronized(cache) {
                if (cache.size >= MEM_CACHE_MAX) cache.minByOrNull { it.value.atNs }?.let { cache.remove(it.key) }
                cache[url] = Entry(body, System.nanoTime())
            }
        }
        return body
    }

    // ---- directory -------------------------------------------------------------------------------------------------------

    /** `country`: ISO 3166 code ("TR"); the directory falls back to the US store when it doesn't know it. */
    suspend fun search(term: String, country: String, limit: Int): List<DirectoryEntry> {
        val q = trimXml(term)
        if (q.isEmpty()) return emptyList()
        val cc = countryCode(country)
        val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&term=${Http.urlEncode(q)}" +
            "&country=$cc&limit=${limit.coerceIn(1, 200)}"
        return try {
            PodcastParsers.parseSearch(get(url, DIRECTORY_MAX, false, true).decodeToString())
        } catch (e: PodcastError) {
            if (cc == "us") throw e
            Log.w("podcasts", "search in store $cc failed (${e.message}); trying the US store")
            search(term, "US", limit)
        }
    }

    suspend fun topCharts(country: String, limit: Int): List<DirectoryEntry> {
        val cc = countryCode(country)
        val n = limit.coerceIn(1, 100)
        try {
            return PodcastParsers.parseCharts(
                get("https://rss.applemarketingtools.com/api/v2/$cc/podcasts/top/$n/podcasts.json", DIRECTORY_MAX, false, true)
                    .decodeToString(),
            )
        } catch (e: PodcastError) {
            Log.w("podcasts", "charts ($cc) failed: ${e.message}; trying the legacy list")
        }
        return PodcastParsers.parseCharts(
            get("https://itunes.apple.com/$cc/rss/toppodcasts/limit=$n/json", DIRECTORY_MAX, false, true).decodeToString(),
        )
    }

    /** A directory show's feed URL; "" = no feed. */
    suspend fun lookupFeed(appleId: String): String {
        if (appleId.isEmpty() || !appleId.all { isAsciiDigit(it) }) return ""
        val results = PodcastParsers.parseSearch(
            get("https://itunes.apple.com/lookup?entity=podcast&id=$appleId", DIRECTORY_MAX, false, true).decodeToString(),
        )
        return results.firstOrNull()?.feedUrl ?: ""
    }

    // ---- feeds -------------------------------------------------------------------------------------------------------------

    private fun cacheFile(feedUrl: String): File? = cacheDir?.let { File(it, Hash.fnv1a64Hex(feedUrl) + ".json") }

    /** The disk copy, any age; no network. */
    suspend fun cachedFeed(feedUrl: String): Show? = withContext(AppDispatchers.io) { cachedFeedBlocking(feedUrl) }

    internal fun cachedFeedBlocking(feedUrl: String): Show? {
        val file = cacheFile(feedUrl) ?: return null
        if (!file.exists()) return null
        return try {
            val j = parseJsonOrNull(file.readText()) ?: throw PodcastError("not JSON")
            val s = PodcastJson.showFrom(j["show"])
            if (s.feedUrl != feedUrl) null else s // a hash collision
        } catch (e: Exception) {
            Log.w("podcasts", "feed cache unreadable (${e.message}): dropped")
            null
        }
    }

    suspend fun dropCachedFeed(feedUrl: String) {
        withContext(AppDispatchers.io) { cacheFile(feedUrl)?.delete() }
    }

    /**
     * A show's feed: the disk copy while it is younger than [FEED_TTL_MS] (unless [refresh]), else downloaded and
     * parsed (and kept). [allowLocal]: the feed may be on this device / its network.
     */
    suspend fun feed(feedUrl: String, refresh: Boolean, allowLocal: Boolean): Show {
        val file = cacheFile(feedUrl)
        if (!refresh && file != null) {
            val fresh = withContext(AppDispatchers.io) {
                file.exists() && System.currentTimeMillis() - file.lastModified() < FEED_TTL_MS
            }
            if (fresh) cachedFeed(feedUrl)?.let { return it }
        }
        val body = get(feedUrl, FEED_MAX, allowLocal, false)
        currentCoroutineContext().ensureActive()
        return withContext(AppDispatchers.cpu) {
            val show = PodcastParsers.parseFeed(body, feedUrl)
            if (file != null) {
                withContext(AppDispatchers.io) {
                    Files.writeJson(file, jsonOf("version" to 1, "show" to PodcastJson.show(show)))
                }
            }
            show
        }
    }

    // ---- audio ---------------------------------------------------------------------------------------------------------

    /**
     * Follows the enclosure's redirects with a two-byte range request and says where the audio really is, how long and
     * of which type. Throws [PodcastError] (HTTP error, an HTML page instead of audio, a local address without
     * [allowLocal]).
     */
    suspend fun resolveMedia(url: String, allowLocal: Boolean): PodcastMedia {
        if (!PodcastText.isHttpUrl(url)) throw PodcastError("not an http(s) address")
        return withContext(AppDispatchers.io) {
            if (!allowLocal && Http.isLocalUrl(url, true)) throw PodcastError("an address on the local network")
            val call = mediaClient(allowLocal).newCall(mediaRequest(url, "bytes=0-1"))
            val info = openMedia(call) { it.mediaInfo() }
            if (!allowLocal && info.finalUrl != url && Http.isLocalUrl(info.finalUrl, true)) {
                throw PodcastError("redirected to the local network")
            }
            if (info.status >= 400 && info.status != 416) throw PodcastError("HTTP ${info.status}")
            if (htmlPage(info.contentType)) throw PodcastError("a web page, not audio")
            val length = when {
                info.status == 206 && info.rangeTotal > 0 -> info.rangeTotal
                info.status == 200 && info.contentLength > 0 -> info.contentLength
                else -> 0
            }
            PodcastMedia(info.finalUrl, if (info.contentType.startsWith("audio/")) info.contentType else "", length)
        }
    }

    /**
     * Writes the audio to [target] through "<target>.part" (continued when it exists, HTTP range resume), calling
     * [progress] (done, total) on the IO thread. Cancelling the coroutine stops it between reads (the .part is kept for
     * a later resume). Returns the file size. Throws [PodcastError].
     */
    suspend fun download(url: String, target: File, allowLocal: Boolean, progress: ((Long, Long) -> Unit)? = null): Long {
        val media = resolveMedia(url, allowLocal)
        if (media.length > MAX_DOWNLOAD) throw PodcastError("file too large")
        return withContext(AppDispatchers.io) {
            target.parentFile?.mkdirs()
            val part = File(target.path + ".part")
            var have = if (part.exists()) part.length() else 0L
            if (media.length in 1 until have) {
                part.delete()
                have = 0
            }
            fun finish(size: Long): Long {
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw PodcastError("could not move the file into place")
                return size
            }
            if (media.length > 0 && have == media.length) return@withContext finish(have)

            val client = mediaClient(allowLocal)
            suspend fun <T> attempt(from: Long, body: (okhttp3.Response) -> T): T =
                openMedia(client.newCall(mediaRequest(media.url, if (from > 0) "bytes=$from-" else null)), body)

            var restart = false
            val result = attempt(have) { r ->
                if (r.code == 416 && have > 0) { // the server doesn't know that range any more: start over
                    restart = true
                    -1L
                } else {
                    copyBody(r, part, have, media.length, progress)
                }
            }
            if (!restart) return@withContext finish(result)
            part.delete()
            have = 0
            finish(attempt(0) { r -> copyBody(r, part, 0, media.length, progress) })
        }
    }

    private fun copyBody(r: okhttp3.Response, part: File, haveIn: Long, mediaLength: Long, progress: ((Long, Long) -> Unit)?): Long {
        var have = haveIn
        if (r.code == 200) have = 0 else if (r.code != 206) throw PodcastError("HTTP ${r.code}")
        val contentType = mediaType(r.header("Content-Type"))
        if (htmlPage(contentType)) throw PodcastError("a web page, not audio")
        val contentLength = r.body.contentLength()
        val total = when {
            mediaLength > 0 -> mediaLength
            contentLength >= 0 -> have + contentLength
            else -> 0
        }
        if (total > MAX_DOWNLOAD) throw PodcastError("file too large")
        var done = have
        var reported = -1L
        val buf = ByteArray(64 * 1024)
        try {
            FileOutputStream(part, have > 0).use { out ->
                r.body.byteStream().use { input ->
                    while (true) {
                        val got = input.read(buf)
                        if (got <= 0) break
                        try {
                            out.write(buf, 0, got)
                        } catch (e: IOException) {
                            throw PodcastError("could not write the file (disk full?)")
                        }
                        done += got
                        if (done > MAX_DOWNLOAD) throw PodcastError("file too large")
                        if (progress != null && (done - reported >= 256 * 1024 || done == total)) {
                            reported = done
                            progress(done, total)
                        }
                    }
                }
                out.fd.sync()
            }
        } catch (e: PodcastError) {
            throw e
        } catch (e: IOException) {
            throw PodcastError("read failed (${e.message})")
        }
        if (total > 0 && done != total) throw PodcastError("incomplete download ($done of $total bytes)")
        if (done == 0L) throw PodcastError("empty file")
        progress?.invoke(done, if (total > 0) total else done)
        return done
    }

    private class MediaInfo(
        val status: Int,
        val finalUrl: String,
        /** Lower case, without parameters. */
        val contentType: String,
        /** -1 = unknown. */
        val contentLength: Long,
        /** "Content-Range: bytes a-b/total" (-1 = none / unknown). */
        val rangeTotal: Long,
    )

    private fun okhttp3.Response.mediaInfo(): MediaInfo {
        val range = header("Content-Range").orEmpty()
        val slash = range.lastIndexOf('/')
        val total = if (slash >= 0 && slash + 1 < range.length && range[slash + 1] != '*') atoi64(range.substring(slash + 1)) else -1
        return MediaInfo(
            code, request.url.toString(), mediaType(header("Content-Type")),
            header("Content-Length")?.let { atoi64(it) } ?: -1L, total,
        )
    }

    private fun mediaRequest(url: String, range: String?): okhttp3.Request {
        val headers = ArrayList<Pair<String, String>>()
        headers += "User-Agent" to userAgent
        // Byte-exact audio for range resume: no transparent gzip here (the only place a backend sets this header).
        headers += "Accept-Encoding" to "identity"
        if (range != null) headers += "Range" to range
        return Http.buildRequest(HttpRequest(url, headers = headers))
    }

    private suspend fun <T> openMedia(call: okhttp3.Call, block: (okhttp3.Response) -> T): T = try {
        call.executeCancellable(block)
    } catch (e: PodcastError) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw PodcastError(e.message ?: "transfer failed")
    }
}
