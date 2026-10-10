package com.app.shadetube.youtube.explode

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Optional hook for a real JavaScript engine (port of JavaScript::IJsEngine).
 *
 * The library itself never needs a JS engine for the default code path:
 *  - the primary innertube clients return plain, directly playable URLs;
 *  - the signature cipher of the fallback client is solved by a built-in minimal interpreter that understands the
 *    reverse / splice / swap transform plan.
 *
 * The only thing that *requires* real JavaScript is the "n" throttling parameter on URLs returned by web/TV clients,
 * because YouTube's n-transform is a large obfuscated function with loops, arrays and closures. When an engine is
 * supplied, the library extracts that function from the player source and asks the engine to evaluate it. It is also
 * used as a fallback for the signature cipher when the transform plan cannot be extracted.
 *
 * Without an engine (the app's configuration, like the desktop app), such URLs are returned with the original n
 * value: they usually still play, but may be throttled to real-time speed or, on some players, rejected.
 */
fun interface JsEngine {
    /** Evaluates a self-contained script and returns the value of its last expression as a string. Throw on failure. */
    fun evaluate(script: String): String
}

/** Configuration of a [YoutubeClient]. Every field is optional. */
data class YoutubeClientOptions(
    /** Transport. Defaults to [OkHttpYtClient] over the app's shared OkHttp client. */
    val httpClient: YtHttpClient? = null,
    /**
     * Cookies of a signed-in account. They enable access to age-restricted and private content and raise rate
     * limits. The SAPISID / __Secure-3PAPISID cookie is used to generate the SAPISIDHASH authorization header.
     */
    val initialCookies: List<YtCookie> = emptyList(),
    /** Optional JavaScript engine used to resolve the "n" throttling parameter (and as a cipher fallback). */
    val jsEngine: JsEngine? = null,
    /**
     * When true, each stream URL is probed (last byte range request) and dropped from the manifest if YouTube rejects
     * it, exactly like the C# library does. One extra request per stream; disabled by default.
     */
    val validateStreamUrls: Boolean = false,
    /** Interface language ("hl") and region ("gl") sent to YouTube. */
    val language: String = "en",
    val region: String = "US",
)

/**
 * Shared per-client state (port of detail::ClientContext = YoutubeHttpHandler + the caches kept by the controllers in
 * the C# library): the YouTube request pipeline, cookies, visitor data and the cached player source. Thread-safe.
 */
class ClientContext(val options: YoutubeClientOptions) {
    private class StoredCookie(
        val name: String,
        val value: String,
        /** lower-case, without leading dot */
        val domain: String,
        val hostOnly: Boolean = false,
        val path: String = "/",
    )

    private val http: YtHttpClient = options.httpClient ?: OkHttpYtClient()
    private val cookiesLock = Any()
    private val cookies = ArrayList<StoredCookie>()

    private val cacheLock = Any()
    private var visitorData: String? = null
    private var visitorDataResolved = false
    private var playerSource: PlayerSource? = null
    private val playerSourceMutex = Mutex()

    init {
        cookies.add(StoredCookie("SOCS", CONSENT_COOKIE_VALUE, "youtube.com", false, "/"))
        for (c in options.initialCookies) {
            var domain = Str.toLower(c.domain).trimStart('.')
            if (domain.isEmpty()) domain = "youtube.com"
            // Replace an existing cookie with the same name/domain.
            cookies.removeAll { it.name == c.name && it.domain == domain }
            cookies.add(StoredCookie(c.name, c.value, domain, false, c.path.ifEmpty { "/" }))
        }
    }

    private fun cookieHeaderFor(host: String, path: String): String = synchronized(cookiesLock) {
        cookies.filter { domainMatches(host, it.domain, it.hostOnly) && path.startsWith(it.path) }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun authorizationFor(origin: String): String? {
        val sessionId = synchronized(cookiesLock) {
            cookies.lastOrNull { it.name == "__Secure-3PAPISID" }?.value
                ?: cookies.lastOrNull { it.name == "SAPISID" }?.value
        }
        if (sessionId.isNullOrEmpty()) return null
        val timestamp = System.currentTimeMillis() / 1000
        val token = "$timestamp $sessionId $origin"
        return "SAPISIDHASH ${timestamp}_${Crypto.sha1Hex(token)}"
    }

    private fun storeCookie(setCookie: String, requestHost: String) {
        val attributes = Str.split(setCookie, ';')
        if (attributes.isEmpty()) return
        val pair = attributes[0]
        val eq = pair.indexOf('=')
        if (eq < 0) return
        val name = Str.trim(pair.substring(0, eq))
        val value = Str.trim(pair.substring(eq + 1))
        var domain = requestHost
        var hostOnly = true
        var path = "/"
        var expired = false
        for (i in 1 until attributes.size) {
            val attr = Str.trim(attributes[i])
            val aeq = attr.indexOf('=')
            val key = Str.toLower(Str.trim(if (aeq < 0) attr else attr.substring(0, aeq)))
            val v = if (aeq < 0) "" else Str.trim(attr.substring(aeq + 1))
            if (key == "domain" && v.isNotEmpty()) {
                val d = Str.toLower(v).trimStart('.')
                // Ignore cookies for foreign domains (like CookieContainer does).
                if (!domainMatches(requestHost, d, false)) return
                domain = d
                hostOnly = false
            } else if (key == "path" && v.isNotEmpty()) {
                path = v
            } else if (key == "max-age") {
                val n = Str.tryParseInt64(v)
                if (n != null && n <= 0) expired = true
            }
        }
        if (name.isEmpty()) return
        synchronized(cookiesLock) {
            cookies.removeAll { it.name == name && it.domain == domain && it.path == path }
            if (!expired) cookies.add(StoredCookie(name, value, domain, hostOnly, path))
        }
    }

    private fun prepare(request: YtHttpRequest) {
        val host = Url.getHost(request.url)
        val path = Url.getPath(request.url)
        val origin = Url.getOrigin(request.url)
        if (isYoutubeHost(host)) {
            if (path.startsWith("/youtubei/") && !Url.containsQueryParameter(request.url, "key"))
                request.url = Url.setQueryParameter(request.url, "key", INNERTUBE_API_KEY)
            if (!Url.containsQueryParameter(request.url, "hl"))
                request.url = Url.setQueryParameter(request.url, "hl", options.language)
            if (!request.hasHeader("Origin")) request.headers.add("Origin" to origin)
            if (!request.hasHeader("Authorization")) authorizationFor(origin)?.let { request.headers.add("Authorization" to it) }
        }
        if (!request.hasHeader("User-Agent")) request.headers.add("User-Agent" to DEFAULT_USER_AGENT)
        if (!request.hasHeader("Cookie")) {
            val cookie = cookieHeaderFor(host, path)
            if (cookie.isNotEmpty()) request.headers.add("Cookie" to cookie)
        }
    }

    private fun handleResponse(request: YtHttpRequest, response: YtHttpResponse) {
        if (response.statusCode == 429) {
            throw RequestLimitExceededException(
                "Exceeded request rate limit. Please try again in a few hours. Alternatively, inject cookies " +
                    "corresponding to a pre-authenticated user when initializing an instance of YoutubeClient.",
            )
        }
        val host = Url.getHost(request.url)
        for (value in response.headerValues("Set-Cookie")) storeCookie(value, host)
    }

    /**
     * Sends a request through the YouTube pipeline: adds the innertube API key and `hl` to youtube.com requests, adds
     * Origin, User-Agent, cookies and SAPISIDHASH authorization, stores cookies from Set-Cookie, retries 5xx responses
     * (5 times) and maps 429 to [RequestLimitExceededException].
     */
    suspend fun send(request: YtHttpRequest): YtHttpResponse {
        prepare(request)
        var retriesRemaining = 5
        while (true) {
            val response = http.send(request)
            handleResponse(request, response)
            if (response.statusCode >= 500 && retriesRemaining > 0) {
                --retriesRemaining
                continue
            }
            return response
        }
    }

    /** GET and return the body; throws HttpRequestException on non-2xx. */
    suspend fun getString(url: String, headers: List<Pair<String, String>> = emptyList()): String {
        val response = send(YtHttpRequest(url, "GET", headers.toMutableList()))
        ensureSuccess(response, url)
        return response.body
    }

    /** POST a JSON body and return the response body; throws HttpRequestException on non-2xx. */
    suspend fun postJson(url: String, json: String, headers: List<Pair<String, String>> = emptyList()): String {
        val h = headers.toMutableList()
        h.add("Content-Type" to "application/json")
        val response = send(YtHttpRequest(url, "POST", h, json.toByteArray(Charsets.UTF_8)))
        ensureSuccess(response, url)
        return response.body
    }

    /** Visitor data identifier (cached for the lifetime of the client, failures too). */
    suspend fun visitorData(): String? {
        synchronized(cacheLock) { if (visitorDataResolved) return visitorData }

        var value: String? = null
        try {
            var raw = getString(
                "https://www.youtube.com/sw.js_data",
                listOf("Accept" to "application/json", "User-Agent" to "com.google.android.youtube/20.10.38 (Linux; U; ANDROID 11) gzip"),
            )
            if (raw.startsWith(")]}'")) raw = raw.substring(4)
            // This is an ordered (but unstructured) blob of data.
            tryParseJson(raw)?.let { value = jsonString(dig(it, 0, 2, 0, 0, 13)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RequestLimitExceededException) {
            throw e
        } catch (e: Exception) {
            // Fall through to the innertube endpoint below.
        }

        // Fallback: the innertube visitor_id endpoint (tiny response). Without visitor data, YouTube answers player
        // requests from some networks with "Sign in to confirm you're not a bot".
        if (value == null || Str.isBlank(value!!)) {
            try {
                val raw = postJson(
                    "https://www.youtube.com/youtubei/v1/visitor_id",
                    """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20210408.08.00","hl":"en","gl":"US"}}}""",
                )
                tryParseJson(raw)?.let { value = jsonString(dig(it, "responseContext", "visitorData")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RequestLimitExceededException) {
                throw e
            } catch (e: Exception) {
                // Visitor data is helpful but not mandatory: continue without it.
            }
        }

        // Cache failures too: visitor data is optional and retrying on every request would only add latency.
        synchronized(cacheLock) {
            if (value != null && Str.isBlank(value!!)) value = null
            visitorData = value
            visitorDataResolved = true
            return value
        }
    }

    /**
     * Current player JavaScript (cached; the player version is resolved from /iframe_api). Only one download runs at
     * a time (the player is several MB).
     */
    suspend fun playerSource(): PlayerSource {
        synchronized(cacheLock) { playerSource?.let { return it } }
        return playerSourceMutex.withLock {
            synchronized(cacheLock) { playerSource?.let { return@withLock it } }
            // Locate the current player version through the iframe API loader, which is tiny and stable:
            //   ... "https:\/\/www.youtube.com\/s\/player\/0004de42\/www-widgetapi.vflset\/www-widgetapi.js" ...
            val iframe = getString("https://www.youtube.com/iframe_api")
            val match = PLAYER_VERSION_PATTERN.find(iframe)
                ?: throw CipherExtractionException("Failed to extract the player version from /iframe_api.")
            val version = match.groupValues[1]
            val playerUrl = "https://www.youtube.com/s/player/$version/player_ias.vflset/en_US/base.js"
            // The whole-player solver is only useful to a JS engine; without one it would just hold several MB.
            val source = PlayerSource(playerUrl, getString(playerUrl), buildSolver = options.jsEngine != null)
            synchronized(cacheLock) {
                if (playerSource == null) playerSource = source
                playerSource!!
            }
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        /** Public innertube key used by the web client; it has not changed in years. */
        const val INNERTUBE_API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"

        /**
         * Consent cookie ("accept all") so that EU requests are not redirected to consent.youtube.com. Supposed to be
         * valid for 13 months; see https://policies.google.com/technologies/cookies/embedded
         */
        const val CONSENT_COOKIE_VALUE = "CAISEwgDEgk4MTM4MzYzNTIaAmVuIAEaBgiApPzGBg"

        private val PLAYER_VERSION_PATTERN = Regex("""player\\?/([0-9a-fA-F]{8})\\?/""")

        private fun isYoutubeHost(host: String) = host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"

        private fun domainMatches(host: String, domain: String, hostOnly: Boolean) =
            if (hostOnly) host == domain else host == domain || host.endsWith(".$domain")

        fun ensureSuccess(response: YtHttpResponse, url: String) {
            if (!response.isSuccessStatusCode) {
                throw HttpRequestException(
                    "Response status code does not indicate success: ${response.statusCode}. Request URL: " +
                        Url.removeQueryParameter(url, "key") + ".",
                    response.statusCode,
                )
            }
        }
    }
}

/**
 * Client for interacting with YouTube (port of YoutubeClient). Cheap to share; all operations share the same session
 * (cookies, visitor data, cached player cipher). Thread-safe; every network operation is a cancellable suspend
 * function (call it from a background dispatcher: parsing the player source is CPU-heavy).
 */
class YoutubeClient(val options: YoutubeClientOptions = YoutubeClientOptions()) {
    val context = ClientContext(options)
    /** Operations related to YouTube videos (and, through it, streams). */
    val videos = VideoClient(context)
    val playlists = PlaylistClient(context)
    val channels = ChannelClient(context)
    val search = SearchClient(context)

    constructor(httpClient: YtHttpClient) : this(YoutubeClientOptions(httpClient = httpClient))

    suspend fun getVideo(videoId: VideoId): Video = videos.get(videoId)
    suspend fun getStreamManifest(videoId: VideoId): StreamManifest = videos.streams.getManifest(videoId)
    suspend fun searchVideos(query: String, maxCount: Int = 20): List<VideoSearchResult> = search.getVideos(query, maxCount)
}
