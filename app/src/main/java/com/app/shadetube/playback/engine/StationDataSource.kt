package com.app.shadetube.playback.engine

import androidx.annotation.OptIn
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.app.shadetube.core.Http
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP for internet radio (live MP3 / AAC / Ogg streams and HLS playlists + segments).
 *
 * - HttpURLConnection, not OkHttp: Shoutcast v1 servers answer with an "ICY 200 OK" status line, which OkHttp rejects
 *   and the platform's HTTP stack reads.
 * - Public internet only ([allowLocalNetwork] = tests): a station's URL, playlist entries and redirects come from a
 *   public, user-edited directory, so every hop (redirects are followed here, one by one) must not point at this
 *   device or its local network. The host is checked by every address it resolves to before connecting.
 * - The request headers ExoPlayer adds (Icy-MetaData: 1 for progressive streams) are sent, and the response headers
 *   (icy-metaint, icy-name, ...) handed back, so ExoPlayer's IcyDataSource extracts the stream titles.
 */
@OptIn(UnstableApi::class)
class StationDataSource(
    private val userAgent: String,
    private val allowLocalNetwork: Boolean,
) : BaseDataSource(/* isNetwork = */ true) {
    companion object {
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }

    class Factory(private val userAgent: String, private val allowLocalNetwork: Boolean) : DataSource.Factory {
        override fun createDataSource(): DataSource = StationDataSource(userAgent, allowLocalNetwork)
    }

    private var dataSpec: DataSpec? = null
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var opened = false
    private var lastUri: Uri? = null
    private var bytesLeft = C.LENGTH_UNSET.toLong()

    override fun getUri(): Uri? = lastUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        connection?.let { headersOf(it) } ?: emptyMap()

    private fun fail(message: String, spec: DataSpec, code: Int = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED): Nothing =
        throw HttpDataSource.HttpDataSourceException(message, spec, code, HttpDataSource.HttpDataSourceException.TYPE_OPEN)

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        var url = dataSpec.uri.toString()
        var hops = 0
        while (true) {
            val u = try {
                URL(url)
            } catch (e: IOException) {
                fail("bad URL", dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
            val scheme = u.protocol.lowercase()
            if (scheme != "http" && scheme != "https") fail("unsupported scheme $scheme", dataSpec)
            if (!allowLocalNetwork && Http.isLocalHost(u.host.orEmpty(), resolve = true)) fail("an address on the local network", dataSpec)
            val conn = try {
                (u.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", userAgent)
                    for ((k, v) in dataSpec.httpRequestHeaders) setRequestProperty(k, v)
                    if (dataSpec.position > 0) setRequestProperty("Range", "bytes=${dataSpec.position}-")
                    connect()
                }
            } catch (e: IOException) {
                throw HttpDataSource.HttpDataSourceException(e, dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            }
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw HttpDataSource.HttpDataSourceException(e, dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            }
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrEmpty() || ++hops > MAX_REDIRECTS) fail("too many redirects", dataSpec)
                url = URL(u, location).toString()
                continue
            }
            if (code != 200 && code != 206) {
                val headers = headersOf(conn)
                conn.disconnect()
                throw HttpDataSource.InvalidResponseCodeException(code, "HTTP $code", null, headers, dataSpec, ByteArray(0))
            }
            connection = conn
            lastUri = Uri.parse(url)
            input = try {
                conn.inputStream
            } catch (e: IOException) {
                conn.disconnect()
                throw HttpDataSource.HttpDataSourceException(e, dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            }
            val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
            bytesLeft = if (len >= 0 && dataSpec.length == C.LENGTH_UNSET.toLong()) len else dataSpec.length
            opened = true
            transferStarted(dataSpec)
            return bytesLeft
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesLeft == 0L) return C.RESULT_END_OF_INPUT
        val want = if (bytesLeft > 0) minOf(length.toLong(), bytesLeft).toInt() else length
        val n = try {
            input!!.read(buffer, offset, want)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException(e, dataSpec!!, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_READ)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesLeft > 0) bytesLeft -= n
        bytesTransferred(n)
        return n
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { connection?.disconnect() }
        input = null
        connection = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/** Response headers without the status line (HttpURLConnection reports it under a null key). */
private fun headersOf(conn: HttpURLConnection): Map<String, List<String>> {
    val out = HashMap<String, List<String>>()
    for ((k, v) in conn.headerFields) {
        val key: String? = k
        if (key != null && v != null) out[key] = v
    }
    return out
}
