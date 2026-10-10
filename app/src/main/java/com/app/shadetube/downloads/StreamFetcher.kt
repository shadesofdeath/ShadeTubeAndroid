package com.app.shadetube.downloads

import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Http
import com.app.shadetube.core.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * Throttle-safe ranged download of one audio stream into a file (the download half of audio/ProgressiveBuffer, which the
 * C++ Downloader reused). YouTube limits a single long GET to roughly the playback rate, so the file is fetched in
 * [CHUNK] pieces: googlevideo URLs with the `&range=a-b` query parameter (what YouTube's own players use), other
 * servers (Piped / Invidious proxies) with a `Range` header. A failed piece is retried [MAX_RETRIES] times with
 * 400 / 1200 / 3000 ms backoff (a piece that delivered bytes resets the count); 403 / 404 / 410 mean the signed URL
 * expired ([ExpiredException]: the caller re-resolves and resumes).
 *
 * Android addition: the download resumes from an existing partial file ([fetch]'s `resumeFrom`), so a download that
 * was interrupted by the process being killed does not start over.
 */
class StreamFetcher(private val client: OkHttpClient = Http.client) {
    /** The stream URL expired / was refused (HTTP 403 / 404 / 410): re-resolve the stream and resume. */
    class ExpiredException(val status: Int) : IOException("HTTP $status (stream URL expired?)")

    /** A failure retrying does not fix (416, a server without range support past the start, no length). */
    class FatalException(message: String) : IOException(message)

    /**
     * Downloads [url] into [out] from byte [resumeFrom] on (the file is truncated there). [knownLength] <= 0: taken from
     * googlevideo's `clen=` parameter, else probed by the first request (Content-Range / Content-Length).
     * [progress] gets (done, total). Returns the total length. Cancellable (the in-flight request is aborted).
     */
    suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        knownLength: Long,
        out: File,
        resumeFrom: Long = 0,
        googleVideo: Boolean = isGoogleVideo(url),
        progress: ((Long, Long) -> Unit)? = null,
    ): Long = withContext(AppDispatchers.io) {
        var length = if (knownLength > 0) knownLength else contentLengthFromUrl(url)
        RandomAccessFile(out, "rw").use { file ->
            var done = resumeFrom.coerceIn(0, maxOf(0, file.length()))
            if (length in 1..done) done = 0 // inconsistent partial file: start over
            file.setLength(done)
            file.seek(done)
            var attempt = 0
            while (length <= 0 || done < length) {
                coroutineContext.ensureActive()
                val probe = length <= 0
                val end = if (probe) done + CHUNK else minOf(done + CHUNK, length) // exclusive
                val useParam = googleVideo && !probe
                val reqUrl = if (useParam) withRangeParam(url, done, end - 1) else url
                val b = Request.Builder().url(reqUrl)
                for ((k, v) in headers) b.header(k, v)
                if (!headers.keys.any { it.equals("User-Agent", true) }) b.header("User-Agent", USER_AGENT)
                if (!useParam) b.header("Range", "bytes=$done-${end - 1}")
                val call = client.newCall(b.build())
                val job = coroutineContext[Job]
                var got = 0L
                try {
                    executeCancellable(call) { resp ->
                        val status = resp.code
                        if (status == 403 || status == 404 || status == 410) throw ExpiredException(status)
                        if (status != 200 && status != 206) {
                            if (status == 416) throw FatalException("HTTP 416")
                            throw IOException("HTTP $status")
                        }
                        val wholeBody = status == 200 && !useParam // server ignored the Range header
                        if (wholeBody && done > 0) throw FatalException("server does not support range requests")
                        if (length <= 0) {
                            var total = -1L
                            val range = resp.header("Content-Range").orEmpty() // "bytes 0-1023/12345"
                            val slash = range.lastIndexOf('/')
                            if (status == 206 && slash >= 0) total = range.substring(slash + 1).trim().toLongOrNull() ?: -1
                            if (total <= 0) total = resp.body.contentLength()
                            if (total <= 0) throw FatalException("unknown content length")
                            length = total
                        }
                        val limit = if (wholeBody) length else minOf(end, length)
                        val buf = ByteArray(PIECE)
                        resp.body.byteStream().use { input ->
                            while (done < limit) {
                                if (job?.isActive == false) throw CancellationException("download cancelled")
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - done).toInt())
                                if (n < 0) break
                                file.write(buf, 0, n)
                                done += n
                                got += n
                                progress?.invoke(done, length)
                            }
                        }
                        if (done < limit) throw IOException("connection closed early at $done of $limit")
                    }
                    attempt = 0
                } catch (e: ExpiredException) {
                    throw e
                } catch (e: FatalException) {
                    throw e
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    if (got > 0) attempt = 0
                    if (++attempt > MAX_RETRIES) throw e
                    Log.w("download", "range $done-$end failed (${e.message}), retry $attempt/$MAX_RETRIES")
                    delay(BACKOFF_MS[(attempt - 1).coerceIn(0, MAX_RETRIES - 1)])
                }
            }
            file.fd.sync()
            length
        }
    }

    /**
     * Runs a blocking OkHttp call that is aborted as soon as the calling coroutine is cancelled (a watcher child
     * cancels the call, which makes the blocked read throw).
     */
    private suspend fun <T> executeCancellable(call: Call, block: (Response) -> T): T = coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use(block)
        } finally {
            watcher.cancel()
        }
    }

    companion object {
        /** Bytes per range request. */
        const val CHUNK = 1L shl 20
        private const val PIECE = 64 * 1024
        const val MAX_RETRIES = 3
        private val BACKOFF_MS = longArrayOf(400, 1200, 3000)
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36 ShadeTube/0.1"

        fun isGoogleVideo(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
            return host.lowercase().contains("googlevideo.com")
        }

        /** googlevideo: the URL with its `range=` parameter replaced by `range=first-last`. */
        fun withRangeParam(url: String, first: Long, last: Long): String {
            val q = url.indexOf('?')
            val sb = StringBuilder(if (q < 0) "$url?" else url.substring(0, q + 1))
            var any = false
            if (q >= 0) {
                for (param in url.substring(q + 1).split('&')) {
                    if (param.isEmpty() || param.startsWith("range=")) continue
                    if (any) sb.append('&')
                    sb.append(param)
                    any = true
                }
            }
            if (any) sb.append('&')
            sb.append("range=").append(first).append('-').append(last)
            return sb.toString()
        }

        /** `clen=` query parameter of googlevideo URLs = total content length; -1 when absent. */
        fun contentLengthFromUrl(url: String): Long {
            for (key in arrayOf("?clen=", "&clen=")) {
                val p = url.indexOf(key)
                if (p < 0) continue
                var v = 0L
                var i = p + 6
                while (i < url.length && url[i] in '0'..'9') {
                    v = v * 10 + (url[i] - '0')
                    ++i
                }
                if (v > 0) return v
            }
            return -1
        }
    }
}
