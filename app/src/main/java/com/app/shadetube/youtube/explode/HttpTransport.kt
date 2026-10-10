package com.app.shadetube.youtube.explode

import com.app.shadetube.core.Http
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Request/response of the library's transport (port of YoutubeExplode::Http). Header names are case-insensitive. */
class YtHttpRequest(
    var url: String,
    val method: String = "GET",
    val headers: MutableList<Pair<String, String>> = ArrayList(),
    val body: ByteArray? = null,
) {
    /** Value of the first header with the given name (case-insensitive). */
    fun header(name: String): String? = headers.firstOrNull { Str.iequals(it.first, name) }?.second
    fun hasHeader(name: String) = header(name) != null
}

class YtHttpResponse(
    val statusCode: Int,
    val headers: List<Pair<String, String>> = emptyList(),
    val bytes: ByteArray = ByteArray(0),
) {
    val body: String by lazy { bytes.toString(Charsets.UTF_8) }
    fun header(name: String): String? = headers.firstOrNull { Str.iequals(it.first, name) }?.second
    /** All values of a header (useful for Set-Cookie). */
    fun headerValues(name: String): List<String> = headers.filter { Str.iequals(it.first, name) }.map { it.second }
    val isSuccessStatusCode: Boolean get() = statusCode in 200..299
}

/**
 * Transport abstraction (the C++ IHttpClient). Implementations must follow redirects, decompress transparently, NOT
 * throw on non-2xx statuses, throw [HttpRequestException] (status 0) on transport failures, be safe to call
 * concurrently, and honour coroutine cancellation.
 */
fun interface YtHttpClient {
    suspend fun send(request: YtHttpRequest): YtHttpResponse
}

/** Browser cookie of a signed-in account (enables age-restricted / private content and raises rate limits). */
data class YtCookie(val name: String, val value: String, val domain: String = ".youtube.com", val path: String = "/")

/**
 * Default transport over the app's shared OkHttp client (core/Http): connection reuse, HTTP/2, transparent gzip.
 * The shared client has no cookie jar: cookies are managed by [ClientContext] like the C++ library does.
 */
class OkHttpYtClient(private val client: OkHttpClient = Http.client) : YtHttpClient {
    override suspend fun send(request: YtHttpRequest): YtHttpResponse {
        val b = Request.Builder()
        try {
            b.url(request.url)
        } catch (e: IllegalArgumentException) {
            throw HttpRequestException("HTTP request to '${request.url}' failed: invalid URL.", 0, e)
        }
        for ((k, v) in request.headers) b.addHeader(k, v)
        val media = request.header("Content-Type")?.toMediaTypeOrNull()
        val body = when {
            request.body != null -> request.body.toRequestBody(media)
            request.method == "POST" || request.method == "PUT" -> ByteArray(0).toRequestBody(media)
            else -> null
        }
        b.method(request.method, body)
        return try {
            executeCancellable(client, b.build()) { resp ->
                val headers = ArrayList<Pair<String, String>>(resp.headers.size)
                for (i in 0 until resp.headers.size) headers.add(resp.headers.name(i) to resp.headers.value(i))
                YtHttpResponse(resp.code, headers, resp.body.bytes())
            }
        } catch (e: IOException) {
            throw HttpRequestException("HTTP request to '${request.url}' failed: ${e.message ?: e.javaClass.simpleName}.", 0, e)
        }
    }
}

/**
 * Runs an OkHttp call so that cancelling the coroutine cancels the call (also while [block] reads the body, which runs
 * on OkHttp's dispatcher thread and must consume what it needs; the response is closed afterwards).
 */
internal suspend fun <T> executeCancellable(client: OkHttpClient, request: Request, block: (Response) -> T): T =
    suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use(block) }
                if (!cont.isActive) return
                result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
        })
    }
