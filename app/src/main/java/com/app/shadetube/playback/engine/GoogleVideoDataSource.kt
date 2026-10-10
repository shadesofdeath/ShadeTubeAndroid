package com.app.shadetube.playback.engine

import androidx.annotation.OptIn
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.app.shadetube.core.Http
import com.app.shadetube.core.Log
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * googlevideo streams fetched like YouTube's own players do (port of the HTTP side of audio/ProgressiveBuffer):
 * ~1 MB pieces addressed with the `&range=a-b` query parameter instead of a Range header — googlevideo servers throttle
 * long single connections (to about real time) and block plain long GETs, while short ranged requests are served at
 * full speed. Every request carries [headers] (the User-Agent of the client the URL was issued to).
 *
 * Reads continue piece by piece from the requested position; a seek is a new open() at the new offset. A piece that
 * fails is retried [MAX_RETRIES] times with backoff (400 / 1200 / 3000 ms; a piece that delivered bytes resets the
 * count). HTTP 403 / 404 / 410 mean the URL expired: thrown as [HttpDataSource.InvalidResponseCodeException] at once,
 * so the player re-resolves the stream (bypassing the stream cache) and continues where it was.
 *
 * The total length comes from the manifest ([contentLength]) or the URL's `clen=` parameter; else the first request
 * probes it with a Range header (Content-Range / Content-Length).
 *
 * Not ported: the parallel tail request at start-up (an optimisation for Media Foundation's demuxers, which read an
 * MP4's / WebM's index at the end first; ExoPlayer's extractors read YouTube's fragmented MP4 / WebM front to back).
 */
@OptIn(UnstableApi::class)
class GoogleVideoDataSource(
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
    private val contentLength: Long,
) : BaseDataSource(/* isNetwork = */ true) {
    companion object {
        /** Bytes per range request. */
        const val CHUNK = 1L shl 20
        const val MAX_RETRIES = 3
        val BACKOFF_MS = longArrayOf(400, 1200, 3000)

        /** `&range=a-b` replaces any range= parameter already there (what YouTube's own players use). */
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
                    i++
                }
                if (v > 0) return v
            }
            return -1
        }
    }

    class Factory(
        private val headers: Map<String, String>,
        private val contentLength: Long,
        private val client: OkHttpClient = Http.client,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = GoogleVideoDataSource(client, headers, contentLength)
    }

    private var dataSpec: DataSpec? = null
    private var url = ""
    private var total = -1L

    /** Next byte to deliver, and the end (exclusive) of what open() asked for. */
    private var pos = 0L
    private var end = 0L
    private var opened = false

    @Volatile private var call: Call? = null
    private var response: Response? = null
    private var body: InputStream? = null

    /** End (exclusive) of the piece being read. */
    private var pieceEnd = 0L
    private var attempt = 0

    override fun getUri(): Uri? = dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: emptyMap()

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        url = dataSpec.uri.toString()
        transferInitializing(dataSpec)
        total = if (contentLength > 0) contentLength else contentLengthFromUrl(url)
        if (total <= 0) total = probeLength()
        if (dataSpec.position > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(total, pos + dataSpec.length) else total
        attempt = 0
        opened = true
        transferStarted(dataSpec)
        return end - pos
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pos >= end) return C.RESULT_END_OF_INPUT
        while (true) {
            if (body == null) openPiece()
            val want = minOf(length.toLong(), pieceEnd - pos).toInt()
            val n = try {
                body!!.read(buffer, offset, want)
            } catch (e: InterruptedIOException) {
                closePiece()
                throw e
            } catch (e: IOException) {
                closePiece()
                retryOrThrow(e)
                continue
            }
            if (n < 0) {
                closePiece()
                if (pos < pieceEnd) { // connection closed early
                    retryOrThrow(IOException("connection closed early"))
                }
                if (pos >= end) return C.RESULT_END_OF_INPUT
                continue
            }
            pos += n
            attempt = 0 // a piece that delivered bytes resets the count
            if (pos >= pieceEnd) closePiece()
            bytesTransferred(n)
            return n
        }
    }

    private fun retryOrThrow(e: IOException) {
        if (++attempt > MAX_RETRIES) {
            throw HttpDataSource.HttpDataSourceException(e, dataSpec!!, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_READ)
        }
        Log.w("audio", "range $pos-$pieceEnd failed (${e.message}), retry $attempt/$MAX_RETRIES")
        try {
            Thread.sleep(BACKOFF_MS[attempt - 1])
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        }
    }

    /** Opens the next ~1 MB piece at [pos] (retrying transient failures). */
    private fun openPiece() {
        while (true) {
            val last = minOf(pos + CHUNK, end) - 1
            val request = Request.Builder().url(withRangeParam(url, pos, last)).also { b ->
                for ((k, v) in headers) b.header(k, v)
                if (headers.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", Http.USER_AGENT)
            }.build()
            val c = client.newCall(request)
            call = c
            val resp = try {
                c.execute()
            } catch (e: IOException) {
                if (c.isCanceled()) throw InterruptedIOException("cancelled")
                retryOrThrow(e)
                continue
            }
            val status = resp.code
            if (status == 403 || status == 404 || status == 410) {
                // The URL expired (or was blocked): no retry here, the player resolves a fresh one.
                val headersMap = resp.headers.toMultimap()
                resp.close()
                throw HttpDataSource.InvalidResponseCodeException(status, "HTTP $status (stream URL expired?)", null, headersMap, dataSpec!!, ByteArray(0))
            }
            if (status != 200 && status != 206) {
                resp.close()
                if (status == 416) {
                    throw HttpDataSource.InvalidResponseCodeException(status, "HTTP 416", null, emptyMap(), dataSpec!!, ByteArray(0))
                }
                retryOrThrow(IOException("HTTP $status"))
                continue
            }
            response = resp
            body = resp.body.byteStream()
            pieceEnd = last + 1
            return
        }
    }

    /** First request without a known length: a Range header, the total from Content-Range / Content-Length. */
    private fun probeLength(): Long {
        var tries = 0
        while (true) {
            val request = Request.Builder().url(url).header("Range", "bytes=0-${CHUNK - 1}").also { b ->
                for ((k, v) in headers) b.header(k, v)
            }.build()
            val c = client.newCall(request)
            call = c
            try {
                c.execute().use { resp ->
                    val status = resp.code
                    if (status == 403 || status == 404 || status == 410) {
                        throw HttpDataSource.InvalidResponseCodeException(status, "HTTP $status (stream URL expired?)", null, resp.headers.toMultimap(), dataSpec!!, ByteArray(0))
                    }
                    if (status == 200 || status == 206) {
                        val range = resp.header("Content-Range").orEmpty() // "bytes 0-1023/12345"
                        val slash = range.lastIndexOf('/')
                        var t = if (status == 206 && slash >= 0) range.substring(slash + 1).trim().toLongOrNull() ?: -1 else -1
                        if (t <= 0) t = resp.header("Content-Length")?.toLongOrNull() ?: -1
                        if (t > 0) return t
                        throw HttpDataSource.HttpDataSourceException("unknown content length", dataSpec!!, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
                    }
                    throw IOException("HTTP $status")
                }
            } catch (e: HttpDataSource.HttpDataSourceException) {
                throw e
            } catch (e: IOException) {
                if (c.isCanceled()) throw InterruptedIOException("cancelled")
                if (++tries > MAX_RETRIES) {
                    throw HttpDataSource.HttpDataSourceException(e, dataSpec!!, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
                }
                try {
                    Thread.sleep(BACKOFF_MS[tries - 1])
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
        }
    }

    private fun closePiece() {
        runCatching { body?.close() }
        runCatching { response?.close() }
        body = null
        response = null
        call = null
    }

    override fun close() {
        call?.cancel()
        closePiece()
        if (opened) {
            opened = false
            transferEnded()
        }
        dataSpec = null
    }
}
