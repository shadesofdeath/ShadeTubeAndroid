package com.app.shadetube.youtube.explode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Resolves protected stream URLs: deciphers "s" into "sig" and transforms "n".
 *
 * Strategy (most to least authoritative):
 *  1. JS engine + whole-player solver: runs YouTube's own code; works on modern players.
 *  2. Built-in transform plan (reverse/splice/swap) for the signature: no JS engine needed.
 *  3. JS engine + extracted function snippets (older players).
 * All challenges of a manifest are solved in a single engine evaluation.
 */
internal class UrlResolver(private val context: ClientContext) {
    private var player: PlayerSource? = null
    private val signatures = HashMap<String, String>()
    private val ns = HashMap<String, String>()

    private suspend fun source(): PlayerSource = player ?: context.playerSource().also { player = it }

    suspend fun solve(sigs: List<String>, nValues: List<String>) {
        if (sigs.isEmpty() && nValues.isEmpty()) return
        val p = source()
        val engine = context.options.jsEngine

        val solver = p.solverScript
        if (engine != null && solver != null) {
            val script = solver + ";JSON.stringify({s:" + toJsArray(sigs) +
                ".map(function(x){try{return _yte_solve(x).sig}catch(e){return null}}),n:" + toJsArray(nValues) +
                ".map(function(x){try{return _yte_solve(undefined,x).n}catch(e){return null}})})"
            collect(evaluate(engine, script), sigs, nValues)
        }

        val pendingSignatures = sigs.filter { it !in signatures }.toMutableList()
        val pendingNs = nValues.filter { it !in ns }

        p.cipherManifest?.let { manifest ->
            for (s in pendingSignatures) signatures[s] = manifest.decipher(s)
            pendingSignatures.clear()
        }

        if (engine != null && (pendingSignatures.isNotEmpty() || pendingNs.isNotEmpty())) {
            var script = ""
            var sPart = "[]"
            var nPart = "[]"
            val sigScript = p.signatureScript
            if (pendingSignatures.isNotEmpty() && sigScript != null) {
                script += sigScript
                sPart = toJsArray(pendingSignatures) + ".map(function(x){try{return __yte_sig(x)}catch(e){return null}})"
            }
            val nScript = p.nScript
            if (pendingNs.isNotEmpty() && nScript != null) {
                script += nScript
                nPart = toJsArray(pendingNs) + ".map(function(x){try{return __yte_n(x)}catch(e){return null}})"
            }
            if (script.isNotEmpty()) {
                script += ";JSON.stringify({s:$sPart,n:$nPart})"
                collect(
                    evaluate(engine, script),
                    if (sPart == "[]") emptyList() else pendingSignatures,
                    if (nPart == "[]") emptyList() else pendingNs,
                )
            }
        }
    }

    fun decipherSignature(signature: String): String {
        signatures[signature]?.let { return it }
        val p = player
        throw CipherExtractionException(
            "Failed to decipher the stream signature using player ${p?.url}. ${p?.diagnostics ?: ""}" +
                (if (context.options.jsEngine != null) " The JavaScript engine could not solve it either."
                else " Supplying a JavaScript engine (YoutubeClientOptions.jsEngine) enables the whole-player solver.") +
                " YouTube likely changed its player; the extraction heuristics need to be updated.",
        )
    }

    /**
     * Transformed n value, or the original one when it could not be solved (the URL then still plays, but may be
     * throttled or rejected).
     */
    fun transformN(n: String): String = ns[n] ?: n

    private fun toJsArray(values: List<String>) = values.joinToString(",", "[", "]") { Js.toStringLiteral(it) }

    private fun evaluate(engine: JsEngine, script: String): String = try {
        engine.evaluate(script)
    } catch (e: Exception) {
        "" // Treated as "unsolved"; fallbacks apply.
    }

    private fun collect(raw: String, sigs: List<String>, nValues: List<String>) {
        val json = tryParseJson(raw) ?: return
        fun take(array: JsonElement?, inputs: List<String>, out: MutableMap<String, String>, isN: Boolean) {
            if (array !is JsonArray) return
            for (i in 0 until minOf(inputs.size, array.size)) {
                val v = array[i]
                if (v !is JsonPrimitive || !v.isString) continue
                val value = v.content
                // "enhanced_except_..." is the n function's own error marker.
                if (value.isEmpty() || (isN && (value.startsWith("enhanced_except") || value == inputs[i]))) continue
                out[inputs[i]] = value
            }
        }
        take(dig(json, "s"), sigs, signatures, false)
        take(dig(json, "n"), nValues, ns, true)
    }
}

internal class StreamController(context: ClientContext) : VideoController(context) {
    suspend fun getStreamInfos(videoId: String): List<StreamInfo> {
        try {
            // Try the cipher-less clients first: their URLs are directly playable.
            val response = getPlayerResponse(videoId)
            return getStreamInfos(videoId, response, false)
        } catch (e: VideoUnavailableException) {
            throw e
        } catch (e: VideoRequiresPurchaseException) {
            throw e
        } catch (e: VideoUnplayableException) {
            // Retry with the embedded TV client, which requires deciphering.
            val player = context.playerSource()
            val sts = player.signatureTimestamp ?: throw CipherExtractionException(
                "Failed to extract the signature timestamp from the player (${player.url}). ${player.diagnostics}",
            )
            val response = getPlayerResponseWithCipher(videoId, sts)
            return getStreamInfos(videoId, response, true)
        }
    }

    suspend fun getStreamInfos(videoId: String, client: ClientResponse, usesPlayerJs: Boolean): List<StreamInfo> {
        val response = client.response
        ensurePlayable(videoId, response)

        val resolver = UrlResolver(context)
        val result = ArrayList<StreamInfo>()
        append(result, response.streams(), resolver, usesPlayerJs, client.userAgent)

        response.dashManifestUrl?.let { dashUrl ->
            try {
                val xml = context.getString(dashUrl)
                append(result, parseDashManifest(xml), resolver, usesPlayerJs, client.userAgent)
            } catch (e: HttpRequestException) {
                // Some DASH manifest URLs return 404 for whatever reason.
            } catch (e: CancellationException) {
                throw e
            } catch (e: YoutubeExplodeException) {
                // Malformed manifest: ignore, the adaptive formats above are usually complete.
            }
        }

        if (result.isEmpty()) throw VideoUnplayableException("Video '$videoId' does not contain any playable streams.")
        return result
    }

    private suspend fun probeContentLength(data: StreamData, url: String): Long? {
        var contentLength = data.contentLength

        // If content length is not available in the metadata, get it from a HEAD request.
        if (contentLength == null) {
            val response = context.send(YtHttpRequest(url, "HEAD"))
            if (response.statusCode == 404 || response.statusCode == 403) return null
            ClientContext.ensureSuccess(response, url)
            contentLength = Str.tryParseInt64(response.header("Content-Length") ?: "")
        }

        if (contentLength != null && context.options.validateStreamUrls && contentLength >= 2) {
            // Streams may have mismatched content length; make sure the last byte is reachable.
            val probeUrl = Url.setQueryParameter(url, "range", "${contentLength - 2}-${contentLength - 1}")
            val response = context.send(YtHttpRequest(probeUrl))
            if (response.statusCode == 404 || response.statusCode == 403) return null
            ClientContext.ensureSuccess(response, probeUrl)
        }
        return contentLength
    }

    private suspend fun append(
        out: MutableList<StreamInfo>,
        datas: List<StreamData>,
        resolver: UrlResolver,
        usesPlayerJs: Boolean,
        userAgent: String,
    ) {
        // Pass 1: collect every challenge so they can be solved in one go.
        val signatures = ArrayList<String>()
        val ns = ArrayList<String>()
        for (data in datas) {
            val u = data.url
            if (u == null || Str.isBlank(u) || data.itag == null) continue
            val s = data.signature
            if (!s.isNullOrEmpty() && s !in signatures) signatures.add(s)
            if (usesPlayerJs) {
                val n = Url.tryGetQueryParameter(u, "n")
                if (!n.isNullOrEmpty() && n !in ns) ns.add(n)
            }
        }
        resolver.solve(signatures, ns)

        for (data in datas) {
            coroutineContext.ensureActive()

            // SABR / server-side streams have no progressive URL we can download.
            val dataUrl = data.url
            val itag = data.itag
            if (dataUrl == null || Str.isBlank(dataUrl) || itag == null) continue
            var url: String = dataUrl

            // Cipher-protected stream
            val sig = data.signature
            if (!sig.isNullOrEmpty())
                url = Url.setQueryParameter(url, data.signatureParameter ?: "sig", resolver.decipherSignature(sig))

            // Throttling parameter (only meaningful for URLs issued to player-JS based clients).
            if (usesPlayerJs) {
                val n = Url.tryGetQueryParameter(url, "n")
                if (!n.isNullOrEmpty()) {
                    val transformed = resolver.transformN(n)
                    if (transformed != n) url = Url.setQueryParameter(url, "n", transformed)
                }
            }

            val contentLength = try {
                probeContentLength(data, url)
            } catch (e: HttpRequestException) {
                // A single unreachable stream must not fail the whole manifest.
                continue
            } ?: continue

            val container = data.container ?: continue
            val bitrate = data.bitrate ?: continue

            val audioCodec = data.audioCodec ?: ""
            val languageCode = data.audioLanguageCode
            val language = if (languageCode != null && !Str.isBlank(languageCode))
                Language(languageCode, data.audioLanguageName ?: languageCode) else null

            val videoCodec = data.videoCodec
            if (!videoCodec.isNullOrEmpty()) {
                val framerate = data.videoFramerate ?: 24
                var quality: VideoQuality? = null
                val label = data.videoQualityLabel
                if (!label.isNullOrEmpty()) quality = VideoQuality.tryFromLabel(label, framerate)
                if (quality == null) quality = VideoQuality.tryFromItag(itag, framerate)
                if (quality == null && data.videoHeight != null) quality = VideoQuality(data.videoHeight!!, framerate)
                if (quality == null) continue
                val resolution = if (data.videoWidth != null && data.videoHeight != null)
                    Resolution(data.videoWidth!!, data.videoHeight!!) else quality.defaultVideoResolution

                if (audioCodec.isNotEmpty()) {
                    out.add(
                        MuxedStreamInfo(
                            url, itag, data.mimeType ?: "", Container(container), FileSize(contentLength), Bitrate(bitrate),
                            audioCodec, videoCodec, quality, resolution, language, data.isAudioLanguageDefault,
                            data.audioSampleRate, data.audioChannels, data.loudnessDb, data.isDrc, data.isVideoUpscaled,
                            userAgent,
                        ),
                    )
                } else {
                    out.add(
                        VideoOnlyStreamInfo(
                            url, itag, data.mimeType ?: "", Container(container), FileSize(contentLength), Bitrate(bitrate),
                            videoCodec, quality, resolution, data.isVideoUpscaled, userAgent,
                        ),
                    )
                }
            } else if (audioCodec.isNotEmpty()) {
                out.add(
                    AudioOnlyStreamInfo(
                        url, itag, data.mimeType ?: "", Container(container), FileSize(contentLength), Bitrate(bitrate),
                        audioCodec, language, data.isAudioLanguageDefault, data.audioSampleRate, data.audioChannels,
                        data.loudnessDb, data.isDrc, userAgent,
                    ),
                )
            }
        }
    }
}

/** Operations related to media streams of YouTube videos (port of Videos::Streams::StreamClient). */
class StreamClient internal constructor(private val context: ClientContext) {
    /**
     * Gets the manifest that lists available streams for the specified video. All returned URLs are fully resolved
     * (signature deciphered, n-parameter transformed when possible) and directly playable. Transient failures
     * (connectivity, 5xx) are retried 5 times.
     */
    suspend fun getManifest(videoId: VideoId): StreamManifest {
        val controller = StreamController(context)
        var retriesRemaining = 5
        while (true) {
            try {
                return StreamManifest(controller.getStreamInfos(videoId.value))
            } catch (e: HttpRequestException) {
                // Retry on connectivity issues
                if (!isTransient(e) || retriesRemaining <= 0) throw e
                --retriesRemaining
            }
        }
    }

    /** Gets the HTTP Live Stream (HLS) manifest URL for the specified video (live streams only). */
    suspend fun getHttpLiveStreamUrl(videoId: VideoId): String {
        val controller = StreamController(context)
        val response = controller.getPlayerResponse(videoId.value).response
        VideoController.ensurePlayable(videoId.value, response)
        val url = response.hlsManifestUrl
        if (url.isNullOrEmpty()) {
            throw YoutubeExplodeException(
                "Failed to extract the HTTP Live Stream manifest URL. Video '${videoId.value}' is likely not a live stream.",
            )
        }
        return url
    }

    /** Opens a readable, seekable stream over the specified media stream. */
    fun get(streamInfo: StreamInfo): MediaStream = MediaStream(context, streamInfo.url, streamInfo.size.bytes)

    /** Copies the specified media stream into an output stream; [progress] receives 0..1. */
    suspend fun copyTo(streamInfo: StreamInfo, destination: OutputStream, progress: ((Double) -> Unit)? = null) {
        val stream = get(streamInfo)
        val buffer = ByteArray(256 * 1024)
        val total = stream.length
        while (true) {
            coroutineContext.ensureActive()
            val read = stream.read(buffer, 0, buffer.size)
            if (read == 0) break
            destination.write(buffer, 0, read)
            if (progress != null && total > 0) progress(stream.position.toDouble() / total.toDouble())
        }
        destination.flush()
    }

    /** Downloads the specified media stream to a file. */
    suspend fun download(streamInfo: StreamInfo, file: File, progress: ((Double) -> Unit)? = null) {
        file.outputStream().use { copyTo(streamInfo, it, progress) }
    }

    private companion object {
        fun isTransient(e: HttpRequestException) = e.statusCode == 0 || e.statusCode >= 500
    }
}

/**
 * Readable, seekable stream over a YouTube media stream (port of MediaStream): the content is fetched in ~10 MB
 * segments using the `range` query parameter, which avoids YouTube's per-connection rate throttling and provides
 * seeking and automatic retries. Not thread-safe.
 */
class MediaStream internal constructor(
    private val context: ClientContext,
    private val url: String,
    val length: Long,
) {
    private val segmentLength = minOf(SEGMENT_LENGTH, maxOf(1L, length))
    var position = 0L
    private var segment = ByteArray(0)
    private var segmentStart = -1L

    private suspend fun loadSegment() {
        val from = position
        val to = minOf(position + segmentLength, length) - 1
        val segmentUrl = Url.setQueryParameter(url, "range", "$from-$to")
        var retriesRemaining = 5
        while (true) {
            try {
                val response = context.send(YtHttpRequest(segmentUrl))
                ClientContext.ensureSuccess(response, segmentUrl)
                segment = response.bytes
                segmentStart = from
                return
            } catch (e: HttpRequestException) {
                // Retry on connectivity issues and server errors
                val transient = e.statusCode == 0 || e.statusCode >= 500
                if (!transient || retriesRemaining <= 0) throw e
                --retriesRemaining
            }
        }
    }

    /** Reads up to [count] bytes. Returns 0 at the end of the stream. */
    suspend fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (position >= length || count == 0) return 0
        coroutineContext.ensureActive()
        val inSegment = segmentStart >= 0 && position >= segmentStart && position < segmentStart + segment.size
        if (!inSegment) {
            loadSegment()
            if (segment.isEmpty()) return 0 // Server returned no data: treat as end of stream.
        }
        val off = (position - segmentStart).toInt()
        val available = segment.size - off
        val n = minOf(count, available)
        System.arraycopy(segment, off, buffer, offset, n)
        position += n
        return n
    }

    enum class SeekOrigin { Begin, Current, End }

    fun seek(offset: Long, origin: SeekOrigin = SeekOrigin.Begin): Long {
        position = when (origin) {
            SeekOrigin.Begin -> offset
            SeekOrigin.Current -> position + offset
            SeekOrigin.End -> length + offset
        }
        if (position < 0) position = 0
        return position
    }

    private companion object {
        /**
         * YouTube limits the transfer speed of a single request to roughly the playback rate. Splitting the download
         * into ~10 MB ranged requests avoids that throttling (same value as the C# library).
         */
        const val SEGMENT_LENGTH = 9_898_989L
    }
}
