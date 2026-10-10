package com.app.shadetube.internetradio

import com.app.shadetube.core.executeCancellable
import com.app.shadetube.core.PublicOnlyInterceptor
import com.app.shadetube.core.readCapped
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where a station's audio is, as the player should open it. */
data class LiveStreamSource(
    /** The stream (redirects followed) or, for HLS, the playlist URL. */
    val url: String,
    /** HTTP Live Streaming: open with an HLS media source. */
    val isHls: Boolean,
    /** Media type hint ("" = sniff). */
    val mimeType: String,
    /** icy-name, decoded ("" = none). */
    val stationName: String = "",
    /** icy-br (kbps, 0 = unknown). */
    val bitrateKbps: Int = 0,
)

/**
 * Station URL -> the stream the player opens (the session start of audio/LiveStream): playlists (.pls / .m3u / .asx,
 * nested up to [MAX_PLAYLIST_DEPTH], redirects followed) are read and their entry [attempt] % entries taken (after a
 * failure, the next entry); an HLS playlist is handed over as such; anything else is the stream itself. Radio servers
 * often send wrong content types: the first bytes decide.
 *
 * Unless [allowLocalNetwork] (tests), every request goes to the public internet only: a station's URL, playlist
 * entries and redirects come from a public, user-edited directory.
 */
object StationStreamResolver {
    const val MAX_PLAYLIST_DEPTH = 4
    private const val MAX_PLAYLIST_BYTES = 512L * 1024
    private const val HEAD_BYTES = 16L

    private val publicClient: OkHttpClient by lazy { probeClient(Http.client.newBuilder().addNetworkInterceptor(PublicOnlyInterceptor)) }
    private val anyClient: OkHttpClient by lazy { probeClient(Http.client.newBuilder()) }

    private fun probeClient(b: OkHttpClient.Builder) = b
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private class Probe(
        val finalUrl: String,
        val contentType: String,
        val type: LiveParsers.PlaylistType,
        val body: ByteArray,
        val htmlPage: Boolean,
        val icyName: String,
        val icyBr: Int,
    )

    suspend fun resolve(stationUrl: String, mimeHint: String, attempt: Int = 0, allowLocalNetwork: Boolean = false): LiveStreamSource {
        var url = stationUrl
        // e.g. an unknown station
        if (!LiveParsers.isHttpUrl(url)) throw RadioError("not an http(s) stream URL")
        var depth = 0
        while (true) {
            if (depth > MAX_PLAYLIST_DEPTH) throw RadioError("too many nested playlists")
            val probe = probe(url, depth, mimeHint, allowLocalNetwork) ?: return LiveStreamSource(url, false, mimeHint)
            if (probe.type != LiveParsers.PlaylistType.None) {
                val text = LiveParsers.playlistText(probe.body)
                if (probe.type == LiveParsers.PlaylistType.Hls || text.contains("#EXT-X-")) {
                    return LiveStreamSource(probe.finalUrl, true, "application/vnd.apple.mpegurl")
                }
                val urls = LiveParsers.parsePlaylist(probe.type, text, probe.finalUrl)
                if (urls.isEmpty()) throw RadioError("playlist without a stream URL")
                url = urls[Math.floorMod(attempt, urls.size)]
                ++depth
                continue
            }
            if (probe.htmlPage) throw RadioError("not an audio stream (HTML page)")
            val mime = probe.contentType.takeIf { it.startsWith("audio/") || it.startsWith("application/ogg") } ?: mimeHint
            return LiveStreamSource(probe.finalUrl, false, mime, probe.icyName, probe.icyBr)
        }
    }

    /** A header value as text: bytes OkHttp kept as Latin-1 are read like ICY titles (UTF-8, else the legacy code page). */
    private fun headerText(raw: String): String =
        if (raw.all { it.code <= 0xFF }) LiveParsers.cleanText(LiveParsers.decodeLegacyText(raw.toByteArray(Charsets.ISO_8859_1))) else raw.trim()

    /** null: the server answered with a Shoutcast v1 "ICY 200 OK" status line (OkHttp can't read it): a plain stream. */
    private suspend fun probe(url: String, depth: Int, mimeHint: String, allowLocal: Boolean): Probe? = withContext(AppDispatchers.io) {
        if (!allowLocal && Http.isLocalUrl(url, true)) throw RadioError("an address on the local network")
        val client = if (allowLocal) anyClient else publicClient
        val call = client.newCall(Http.buildRequest(HttpRequest(url)))
        try {
            call.executeCancellable { r ->
                if (!r.isSuccessful) throw RadioError("HTTP ${r.code}")
                val source = r.body.source()
                source.request(HEAD_BYTES) // the first bytes decide what this is
                val head = source.buffer.snapshot().toByteArray().let { if (it.size > 4096) it.copyOf(4096) else it }
                val finalUrl = r.request.url.toString()
                var contentType = r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                // The caller's MIME type stands in when the station's server sends none.
                if (contentType.isEmpty() && depth == 0) contentType = mimeHint
                val type = LiveParsers.playlistType(contentType, head, finalUrl)
                val body = if (type != LiveParsers.PlaylistType.None) {
                    r.readCapped(MAX_PLAYLIST_BYTES) { RadioError("playlist too large") }
                } else {
                    head
                }
                val start = String(head, Charsets.ISO_8859_1).trimStart()
                Probe(
                    finalUrl, contentType, type, body, start.startsWith("<"),
                    headerText(r.header("icy-name").orEmpty()),
                    (r.header("icy-br")?.substringBefore(',')?.trim()?.toIntOrNull() ?: 0).coerceIn(0, 10000),
                )
            }
        } catch (e: RadioError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            if (e.message?.contains("ICY", ignoreCase = true) == true) {
                Log.i("iradio", "ICY status line from $url: a plain stream")
                return@withContext null
            }
            throw RadioError(e.message ?: "stream unavailable")
        }
    }
}
