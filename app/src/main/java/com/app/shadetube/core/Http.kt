package com.app.shadetube.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Port of core/Http: one shared OkHttp transport for the whole app (connection / TLS reuse, HTTP/2, transparent gzip).
 *
 * Blocking calls ([Http.send], [Http.get], ...) are for worker threads (Dispatchers.IO); [Http.await] is the
 * cancellable suspend variant (cancelling the coroutine cancels the call).
 *
 * Never set "Accept-Encoding" yourself: OkHttp then stops decompressing transparently.
 */
class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
    val contentType: String? = null,
) {
    fun hasHeader(name: String) = headers.any { it.first.equals(name, ignoreCase = true) }
}

class HttpResponse(
    val status: Int,
    val headers: Headers,
    val bytes: ByteArray,
    /** The URL after redirects. */
    val url: String,
) {
    val body: String by lazy { bytes.toString(Charsets.UTF_8) }
    val isSuccess: Boolean get() = status in 200..299
    fun header(name: String): String? = headers[name]

    /** Throws [HttpError] for a non-2xx status. */
    fun requireSuccess(what: String = "HTTP"): HttpResponse {
        if (!isSuccess) throw HttpError(status, body, "$what failed: HTTP $status")
        return this
    }
}

/** A non-2xx response turned into an exception by helpers that require success. */
class HttpError(val status: Int, val body: String, message: String) : IOException(message)

object Http {
    /** Generic desktop Chrome UA (services that need a specific one pass their own header). */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun buildRequest(r: HttpRequest): Request {
        val b = Request.Builder().url(r.url)
        for ((k, v) in r.headers) b.addHeader(k, v)
        if (!r.hasHeader("User-Agent")) b.header("User-Agent", USER_AGENT)
        val media = (r.contentType ?: r.headers.firstOrNull { it.first.equals("Content-Type", true) }?.second)
            ?.toMediaTypeOrNull()
        val body = when {
            r.body != null -> r.body.toRequestBody(media)
            r.method == "POST" || r.method == "PUT" || r.method == "PATCH" -> ByteArray(0).toRequestBody(media)
            else -> null
        }
        b.method(r.method, body)
        return b.build()
    }

    private fun Response.toHttpResponse(): HttpResponse =
        HttpResponse(code, headers, body.bytes(), request.url.toString())

    /** Blocking. Throws IOException on transport failure; any HTTP status comes back in the response. */
    fun send(r: HttpRequest, client: OkHttpClient = this.client): HttpResponse =
        client.newCall(buildRequest(r)).execute().use { it.toHttpResponse() }

    /** Suspending, cancellable variant of [send]. */
    suspend fun await(r: HttpRequest, client: OkHttpClient = this.client): HttpResponse =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(buildRequest(r))
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { it.toHttpResponse() } }
                    if (!cont.isActive) return
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            })
        }

    fun get(url: String, headers: List<Pair<String, String>> = emptyList()): HttpResponse =
        send(HttpRequest(url, headers = headers))

    fun postForm(url: String, form: List<Pair<String, String>>, headers: List<Pair<String, String>> = emptyList()) =
        send(HttpRequest(url, "POST", headers, formEncode(form).toByteArray(), "application/x-www-form-urlencoded"))

    fun postJson(url: String, json: String, headers: List<Pair<String, String>> = emptyList()) =
        send(HttpRequest(url, "POST", headers, json.toByteArray(), "application/json"))

    /** RFC 3986 unreserved characters stay, everything else is %XX (UTF-8). Same as the C++ urlEncode. */
    fun urlEncode(s: String): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(s.length * 3)
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(hex[c shr 4]).append(hex[c and 15])
            }
        }
        return sb.toString()
    }

    fun formEncode(form: List<Pair<String, String>>): String =
        form.joinToString("&") { (k, v) -> urlEncode(k) + "=" + urlEncode(v) }

    // ---- URLs from untrusted sources (internet-radio directory entries, podcast feeds) -------------------------------

    /**
     * The host is this device or its local network: "localhost", or an address that is loopback, private (RFC 1918,
     * ULA), link-local, shared (CGNAT), multicast, unspecified or reserved. With [resolve] a name counts by every
     * address it resolves to (blocking: worker threads only); a name that does not resolve is not local.
     */
    fun isLocalHost(hostIn: String, resolve: Boolean = true): Boolean {
        var host = hostIn.lowercase()
        if (host.length >= 2 && host.first() == '[' && host.last() == ']') host = host.substring(1, host.length - 1)
        host = host.trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.contains('%')) return true
        val literal = host.contains(':') || host.matches(Regex("""[0-9.]+"""))
        if (!literal && !resolve) return false
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addrs.any { localAddress(it) }
    }

    fun isLocalUrl(url: String, resolve: Boolean = true): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = u.host ?: return false
        return isLocalHost(host, resolve)
    }

    private fun localIpv4(a: Long): Boolean {
        val b0 = (a shr 24) and 0xFF
        val b1 = (a shr 16) and 0xFF
        val b2 = (a shr 8) and 0xFF
        return b0 == 0L || b0 == 10L || b0 == 127L ||
            (b0 == 100L && (b1 and 0xC0) == 64L) ||
            (b0 == 169L && b1 == 254L) ||
            (b0 == 172L && (b1 and 0xF0) == 16L) ||
            (b0 == 192L && b1 == 168L) ||
            (b0 == 192L && b1 == 0L && b2 == 0L) ||
            (b0 == 198L && (b1 and 0xFE) == 18L) ||
            b0 >= 224L
    }

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun localAddress(a: InetAddress): Boolean {
        val b = a.address
        if (a is Inet4Address) return localIpv4(be32(b, 0))
        if (a !is Inet6Address) return true
        fun zero(from: Int, to: Int) = (from until to).all { b[it].toInt() == 0 }
        if (zero(0, 12)) return localIpv4(be32(b, 12))
        if (zero(0, 10) && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()) return localIpv4(be32(b, 12))
        if (b[0].toInt() == 0x00 && b[1].toInt() == 0x64 && b[2] == 0xFF.toByte() && b[3] == 0x9B.toByte() && zero(4, 12))
            return localIpv4(be32(b, 12))
        if (b[0].toInt() == 0x20 && b[1].toInt() == 0x02) return localIpv4(be32(b, 2))
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return (b0 and 0xFE) == 0xFC || (b0 == 0xFE && (b1 and 0x80) == 0x80) || b0 == 0xFF
    }

    /**
     * A GET that gives up early: at most [maxBytes] of (decompressed) body, [timeoutMs] for the whole request, and only
     * a response whose Content-Type [accept] takes (null = any). Redirects are followed. Blocking. Throws IOException
     * when a limit is hit or the transfer fails; a non-2xx status comes back in the response.
     */
    fun getLimited(
        url: String,
        maxBytes: Long,
        timeoutMs: Long,
        accept: ((contentType: String) -> Boolean)? = null,
        headers: List<Pair<String, String>> = emptyList(),
    ): HttpResponse {
        val limited = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        limited.newCall(buildRequest(HttpRequest(url, headers = headers))).execute().use { resp ->
            val type = resp.header("Content-Type").orEmpty()
            if (resp.code !in 200..299) return HttpResponse(resp.code, resp.headers, ByteArray(0), resp.request.url.toString())
            if (accept != null && !accept(type)) throw IOException("unexpected content type \"$type\"")
            val declared = resp.body.contentLength()
            if (declared > maxBytes) throw IOException("response too large")
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16384)
            resp.body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw IOException("response too large")
                    out.write(buf, 0, n)
                }
            }
            return HttpResponse(resp.code, resp.headers, out.toByteArray(), resp.request.url.toString())
        }
    }
}
