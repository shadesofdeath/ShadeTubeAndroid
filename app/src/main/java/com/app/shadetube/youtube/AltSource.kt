package com.app.shadetube.youtube

import com.app.shadetube.core.Http
import com.app.shadetube.core.Log
import com.app.shadetube.youtube.explode.executeCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/*
 * Backup audio source: Piped / Invidious instances (Spotube's "Piped / Invidious" audio source options).
 * Port of src/youtube/AltSource.
 *
 * Used by MatchService only when the user enabled it (Settings.altSource != "off") and our YoutubeExplode path failed.
 * The chosen server sees which videos are played, so it is off by default, and a change of the setting takes effect at
 * once: a running instance walk stops at its next step (configuration generation), and only the user's current
 * instance or a built-in one is ever contacted.
 *
 *   Piped      GET <api>/streams/<id>               -> audioStreams[] (url via the instance's proxy)
 *              GET <api>/search?q=<q>&filter=music_songs|videos
 *   Invidious  GET <base>/api/v1/videos/<id>?local=true -> adaptiveFormats[] (local = proxied by the instance)
 *              GET <base>/api/v1/search?q=<q>&type=video
 *
 * Instances are tried in order: the user's own instance (if set), the one that worked last (in memory), then the
 * built-in list. Instances that failed in the last 5 minutes go last; unreachable ones (DNS / refused / timeout) are
 * skipped for a minute. Requests go through AltSource's own OkHttp configuration: a hard wall-clock deadline per
 * request (callTimeout: a server dripping bytes cannot hold a worker), a 25 s budget per walk, capped bodies (JSON
 * 4 MB, errors 16 KB, the Range probe 16 bytes) and no redirects for stream URLs. A stream URL must be https (http only
 * for an http instance) on the instance's own host or the proxy host a Piped instance declares; it is only returned
 * after a 16-byte Range probe proved it answers ranged requests (206) with the promised container (ftyp / EBML), i.e.
 * that the player's progressive download can play and seek it. Stream and search health are tracked separately.
 *
 * Thread-safe; network functions are cancellable suspend functions (call them from AppDispatchers.io).
 */

enum class AltKind {
    Off, Piped, Invidious;

    /** "off" | "piped" | "invidious" (Settings.altSource values, logs, StreamInfo.source). */
    val settingsName: String get() = name.lowercase(Locale.ROOT)

    companion object {
        /** "piped" | "invidious" | anything else = Off */
        fun parse(settingsValue: String): AltKind = when (settingsValue) {
            "piped" -> Piped
            "invidious" -> Invidious
            else -> Off
        }
    }
}

/** One stream offered by an instance. */
data class AltAudioStream(
    val url: String,
    /** container: "audio/mp4" | "audio/webm" (muxed itag 18: "audio/mp4" too) */
    val mimeType: String,
    /** "mp4a.40.2" | "opus" | ... */
    val codec: String,
    /** bits per second */
    val bitrate: Int,
    /** 0 = unknown */
    val contentLength: Long,
    val itag: Int,
    /** audio + video (itag 18): last resort, exactly like the YouTube path */
    val muxed: Boolean = false,
    /** not the original audio track (auto-dubbed / descriptive) */
    val dubbed: Boolean = false,
)

data class AltVideoInfo(
    val title: String = "",
    val uploader: String = "",
    val durationSec: Int = 0,
    /** Piped: the media proxy the instance declares (its stream URLs live there) */
    val proxyUrl: String = "",
    val streams: List<AltAudioStream> = emptyList(),
)

data class AltSearchItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val channelId: String,
    /** -1 = unknown / live */
    val durationSec: Int = -1,
)

/** A stream an instance delivered and that passed the Range probe. */
data class AltServed(
    val stream: AltAudioStream,
    val kind: AltKind,
    val instance: String,
    /** title / uploader / duration (streams cleared) */
    val video: AltVideoInfo,
    /** AltSource configuration it was fetched under (0 = streamFrom) */
    val generation: Long = 0,
)

enum class AltContainer { Unknown, Mp4, Webm }

/** A backup-source failure (error answers, unusable streams, configuration changes, nothing delivered...). */
open class AltSourceException(message: String) : Exception(message)

/** Transport failure (DNS, refused, reset, deadline): the instance counts as down. */
internal class AltUnreachableException(message: String) : AltSourceException(message)

// ---- Pure parsing and selection (no network; unit-tested with saved responses) ------------------------------------

object AltParse {
    // Public instances change often (YouTube blocks their IPs, operators shut them down); ~90 known ones were probed on
    // 2026-09-28. Search worked on all of these but kavin.rocks; streams only for some videos (dQw4w9WgXcQ yes,
    // kJQP7kiw5Fk "SignInConfirmNotBot" / companion errors everywhere). Hence the user's own instance goes first.
    // Order = most reliable first.
    val PIPED_INSTANCES = listOf(
        "https://api.piped.private.coffee",   // streams: muxed itag 18 only
        "https://pipedapi.ducks.party",       // streams: muxed itag 18 only
        "https://pipedapi.kavin.rocks",       // official; Cloudflare 403 / 502 on 2026-09-28
    )
    val INVIDIOUS_INSTANCES = listOf(
        "https://invidious.ducks.party",      // streams: audio-only via its proxy
        "https://invidious.f5.si",            // search only (companion errors)
        "https://invidious.materialio.us",    // search only
    )

    private fun lower(s: String) = s.lowercase(Locale.ROOT)
    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'
    private fun trim(s: String) = s.trim { isSpace(it) }
    private fun isAsciiAlnum(c: Char) = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'

    /** "https://pipedapi.example.org/x" -> "pipedapi.example.org" (with ":port" when given; logs / URL checks). */
    fun hostOf(url: String): String {
        val i = url.indexOf("://")
        val p = if (i < 0) 0 else i + 3
        var end = url.length
        for (k in p until url.length) if (url[k] == '/' || url[k] == '?' || url[k] == '#') { end = k; break }
        return url.substring(p, end)
    }

    /**
     * "example.org/" -> "https://example.org"; keeps http:// (a local instance). Null when it is not a usable URL:
     * another scheme, whitespace, '?' / '#', or a bad host.
     */
    fun normalizeInstance(input: String): String? {
        var s = trim(input)
        if (s.isEmpty()) return null
        val l = lower(s)
        val hostStart: Int
        if (l.startsWith("https://")) hostStart = 8
        else if (l.startsWith("http://")) hostStart = 7
        else if (l.contains("://")) return null
        else {
            s = "https://$s"
            hostStart = 8
        }
        s = lower(s.substring(0, hostStart)) + s.substring(hostStart)
        while (s.length > hostStart && s.endsWith('/')) s = s.dropLast(1)
        val slash = s.indexOf('/', hostStart)
        val host = if (slash < 0) s.substring(hostStart) else s.substring(hostStart, slash)
        if (host.isEmpty() || host[0] == '.' || host[0] == ':') return null
        for (c in host) if (!isAsciiAlnum(c) && c != '.' && c != '-' && c != '_' && c != ':' && c != '[' && c != ']') return null
        for (c in s) if (c <= ' ' || c == '?' || c == '#') return null
        return s
    }

    /**
     * Loopback / private / link-local address literals ("host" or "host:port", IPv6 in brackets). DNS names that
     * resolve to such addresses are out of scope.
     */
    internal fun privateHost(hostPort: String): Boolean {
        var h = lower(hostPort)
        if (h.startsWith("[")) {
            val close = h.indexOf(']')
            h = if (close < 0) h else h.substring(0, close + 1)
            return h == "[::1]" || h == "[::]" || h.startsWith("[fc") || h.startsWith("[fd") || h.startsWith("[fe8") ||
                h.startsWith("[fe9") || h.startsWith("[fea") || h.startsWith("[feb") || h.startsWith("[::ffff:")
        }
        val colon = h.indexOf(':')
        if (colon >= 0) h = h.substring(0, colon)
        if (h == "localhost" || h.endsWith(".localhost")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val o = IntArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return false
            o[i] = part.toInt()
        }
        return o[0] == 0 || o[0] == 10 || o[0] == 127 || (o[0] == 169 && o[1] == 254) || (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) || (o[0] == 100 && o[1] in 64..127)
    }

    /**
     * May the player fetch [url] for this instance? https (http only when the instance itself is http), and the host
     * (with port) is the instance's own or the proxy a Piped instance declares; a declared proxy on a loopback / private
     * address literal is only accepted for an instance that is itself on one (a LAN instance).
     */
    fun acceptableStreamUrl(url: String, instance: String, declaredProxy: String = ""): Boolean {
        val idx = url.indexOf("://")
        val scheme = lower(if (idx < 0) url.take(2) else url.substring(0, idx + 3))
        val httpInstance = lower(instance).startsWith("http://")
        if (scheme != "https://" && !(scheme == "http://" && httpInstance)) return false
        val host = lower(hostOf(url))
        if (host.isEmpty() || host.contains('@')) return false
        val instanceHost = lower(hostOf(instance))
        if (host == instanceHost) return true
        if (declaredProxy.isEmpty() || host != lower(hostOf(declaredProxy))) return false
        return !privateHost(host) || privateHost(instanceHost)
    }

    /** "....ftyp" = MP4, 1A 45 DF A3 = WebM (EBML). */
    fun sniffContainer(b: ByteArray): AltContainer {
        if (b.size >= 8 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() &&
            b[7] == 'p'.code.toByte()
        ) return AltContainer.Mp4
        if (b.size >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte())
            return AltContainer.Webm
        return AltContainer.Unknown
    }

    /** "audio/webm; codecs=\"opus\"" -> "audio/webm" */
    private fun mimeOf(type: String): String {
        val semi = type.indexOf(';')
        return lower(trim(if (semi < 0) type else type.substring(0, semi)))
    }

    /** "audio/webm; codecs=\"opus\"" -> "opus" */
    private fun codecsOf(type: String): String {
        val p = type.indexOf("codecs=\"")
        if (p < 0) return ""
        val b = p + 8
        val e = type.indexOf('"', b)
        return if (e < 0) type.substring(b) else type.substring(b, e)
    }

    fun containerOf(mimeType: String): AltContainer = when (mimeOf(mimeType)) {
        "audio/mp4", "video/mp4" -> AltContainer.Mp4
        "audio/webm", "video/webm" -> AltContainer.Webm
        else -> AltContainer.Unknown
    }

    // ---- JSON helpers: Invidious sends numbers as strings ("bitrate": "130516"), Piped uses null for "unknown".

    private fun jstr(o: JsonElement?, key: String): String {
        val v = (o as? JsonObject)?.get(key) as? JsonPrimitive ?: return ""
        return if (v.isString) v.content else ""
    }

    private fun jint(o: JsonElement?, key: String, fallback: Long = 0): Long {
        val v = (o as? JsonObject)?.get(key) ?: return fallback
        if (v !is JsonPrimitive || v is JsonNull) return fallback
        if (v.isString) {
            // strtoll: leading whitespace, optional sign, digits; no digits -> fallback.
            val s = v.content.trimStart { isSpace(it) }
            var i = 0
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            val digitsStart = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == digitsStart) return fallback
            return s.substring(0, i).toLongOrNull() ?: fallback
        }
        if (v.booleanOrNull != null) return fallback
        v.content.toLongOrNull()?.let { return it }
        return v.doubleOrNull?.toLong() ?: fallback
    }

    private fun jbool(o: JsonElement?, key: String): Boolean {
        val v = (o as? JsonObject)?.get(key) as? JsonPrimitive ?: return false
        return !v.isString && v.booleanOrNull == true
    }

    private fun headOf(body: String, n: Int): String =
        trim(body.take(n)).replace('\n', ' ')

    private fun parseBody(body: String): JsonElement =
        runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: throw AltSourceException("not a JSON response (" + headOf(body, 60).ifEmpty { "empty" } + ")")

    /** {"error": "...", "message": "..."} (Piped puts a Java stack trace there: keep its first line). */
    private fun errorText(j: JsonElement): String {
        if (j !is JsonObject) return ""
        var e = jstr(j, "error").ifEmpty { jstr(j, "message") }
        val nl = e.indexOf('\n')
        if (nl >= 0) e = e.substring(0, nl)
        if (e.length > 160) e = e.substring(0, 157) + "..."
        return e
    }

    /** body <= MAX_ERROR_BODY */
    internal fun errorOfBody(body: String): String {
        runCatching { Json.parseToJsonElement(body) }.getOrNull()?.let { return errorText(it) }
        return trim(body.take(80)).replace('\n', ' ').replace('\r', ' ')
    }

    /**
     * The instance says the video itself is gone: no other instance will have it, and it says nothing about the
     * instance's health (NewPipe / Invidious wording).
     */
    internal fun videoGone(message: String): Boolean {
        val m = lower(message)
        return listOf(
            "video is unavailable", "video unavailable", "video is private", "private video", "has been removed",
            "account terminated", "accountterminated", "video does not exist",
        ).any { m.contains(it) }
    }

    private fun absolute(url: String, base: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> base + url
        else -> url
    }

    /** googlevideo `xtags` names the audio content of multi-language videos: acont=original | dubbed(-auto) | descriptive. */
    private fun dubbedByUrl(url: String): Boolean {
        var p = url.indexOf("acont")
        if (p < 0) return false
        p += 5
        p += when {
            url.startsWith("=", p) -> 1
            url.startsWith("%3D", p) || url.startsWith("%3d", p) -> 3
            else -> return false
        }
        return !url.startsWith("original", p)
    }

    internal fun validVideoId(id: String) =
        id.length == 11 && id.all { isAsciiAlnum(it) || it == '-' || it == '_' }

    /**
     * Muxed mp4 (itag 18) plays as audio: the YouTube path's last resort too. Its audio track is AAC-LC 96 kbps; Piped
     * reports bitrate 0 for it.
     */
    private fun muxed(url: String, bitrate: Int, length: Long) = AltAudioStream(
        url = url, mimeType = "audio/mp4", codec = "mp4a.40.2", bitrate = if (bitrate > 0) bitrate else 96000,
        contentLength = maxOf(0L, length), itag = 18, muxed = true,
    )

    /** Throws AltSourceException for an error body ({"error": ...}) or non-JSON. [base] resolves relative URLs. */
    fun parsePipedStreams(body: String, base: String): AltVideoInfo {
        val j = parseBody(body)
        if (j !is JsonObject) throw AltSourceException("unexpected streams response")
        if (!j.containsKey("audioStreams")) {
            val e = errorText(j)
            throw AltSourceException(e.ifEmpty { "no audioStreams in the response" })
        }
        val streams = ArrayList<AltAudioStream>()
        (j["audioStreams"] as? JsonArray)?.forEach { s ->
            if (s !is JsonObject || jbool(s, "videoOnly")) return@forEach
            // NewPipe's AudioTrackType: ORIGINAL | DUBBED | DESCRIPTIVE | SECONDARY; null = the only track.
            val track = jstr(s, "audioTrackType")
            val a = AltAudioStream(
                url = absolute(jstr(s, "url"), base),
                mimeType = mimeOf(jstr(s, "mimeType")),
                codec = jstr(s, "codec"),
                bitrate = jint(s, "bitrate").toInt(),
                contentLength = maxOf(0L, jint(s, "contentLength")),
                itag = jint(s, "itag").toInt(),
                dubbed = track.isNotEmpty() && track != "ORIGINAL",
            )
            if (a.url.isEmpty() || containerOf(a.mimeType) == AltContainer.Unknown) return@forEach
            streams.add(a)
        }
        (j["videoStreams"] as? JsonArray)?.forEach { s ->
            if (s !is JsonObject || jbool(s, "videoOnly") || jint(s, "itag") != 18L) return@forEach
            val url = absolute(jstr(s, "url"), base)
            if (url.isNotEmpty()) streams.add(muxed(url, jint(s, "bitrate").toInt(), jint(s, "contentLength")))
        }
        return AltVideoInfo(jstr(j, "title"), jstr(j, "uploader"), jint(j, "duration").toInt(), jstr(j, "proxyUrl"), streams)
    }

    fun parseInvidiousVideo(body: String, base: String): AltVideoInfo {
        val j = parseBody(body)
        if (j !is JsonObject) throw AltSourceException("unexpected video response")
        if (!j.containsKey("adaptiveFormats")) {
            val e = errorText(j)
            throw AltSourceException(e.ifEmpty { "no adaptiveFormats in the response" })
        }
        val streams = ArrayList<AltAudioStream>()
        (j["adaptiveFormats"] as? JsonArray)?.forEach { f ->
            if (f !is JsonObject) return@forEach
            val type = jstr(f, "type")
            val mime = mimeOf(type)
            if (!mime.startsWith("audio/") || containerOf(mime) == AltContainer.Unknown) return@forEach
            val url = absolute(jstr(f, "url"), base)
            val track = f["audioTrack"]
            val dubbed = if (track is JsonObject && track.containsKey("isDefault")) !jbool(track, "isDefault") else dubbedByUrl(url)
            if (url.isEmpty()) return@forEach
            streams.add(
                AltAudioStream(
                    url = url,
                    mimeType = mime,
                    codec = codecsOf(type).ifEmpty { jstr(f, "encoding") },
                    bitrate = jint(f, "bitrate").toInt(),
                    contentLength = maxOf(0L, jint(f, "clen")),
                    itag = jint(f, "itag").toInt(),
                    dubbed = dubbed,
                ),
            )
        }
        (j["formatStreams"] as? JsonArray)?.forEach { f ->
            if (f !is JsonObject || jint(f, "itag") != 18L) return@forEach
            val url = absolute(jstr(f, "url"), base)
            if (url.isNotEmpty()) streams.add(muxed(url, jint(f, "bitrate").toInt(), jint(f, "clen")))
        }
        return AltVideoInfo(jstr(j, "title"), jstr(j, "author"), jint(j, "lengthSeconds").toInt(), "", streams)
    }

    fun parsePipedSearch(body: String): List<AltSearchItem> {
        val j = parseBody(body)
        val items = (j as? JsonObject)?.get("items")
        if (j !is JsonObject || items !is JsonArray) {
            val e = errorText(j)
            throw AltSourceException(e.ifEmpty { "no items in the search response" })
        }
        val out = ArrayList<AltSearchItem>()
        for (it in items) {
            if (it !is JsonObject || jstr(it, "type") != "stream" || jbool(it, "isShort")) continue
            val url = jstr(it, "url") // "/watch?v=kJQP7kiw5Fk"
            val p = url.indexOf("v=")
            val videoId = if (p >= 0) url.substring(p + 2).take(11) else ""
            if (!validVideoId(videoId)) continue
            val ch = jstr(it, "uploaderUrl") // "/channel/UC..."
            val slash = ch.lastIndexOf('/')
            val d = jint(it, "duration", -1)
            out.add(
                AltSearchItem(
                    videoId = videoId,
                    title = jstr(it, "title"),
                    channel = jstr(it, "uploaderName"),
                    channelId = if (slash >= 0) ch.substring(slash + 1) else "",
                    durationSec = if (d > 0) d.toInt() else -1,
                ),
            )
        }
        return out
    }

    fun parseInvidiousSearch(body: String): List<AltSearchItem> {
        val j = parseBody(body)
        if (j !is JsonArray) {
            val e = errorText(j)
            throw AltSourceException(e.ifEmpty { "unexpected search response" })
        }
        val out = ArrayList<AltSearchItem>()
        for (it in j) {
            if (it !is JsonObject || jstr(it, "type") != "video") continue
            val videoId = jstr(it, "videoId")
            if (!validVideoId(videoId)) continue
            val d = jint(it, "lengthSeconds", -1)
            out.add(
                AltSearchItem(
                    videoId = videoId,
                    title = jstr(it, "title"),
                    channel = jstr(it, "author"),
                    channelId = jstr(it, "authorId"),
                    durationSec = if (d > 0 && !jbool(it, "liveNow")) d.toInt() else -1,
                ),
            )
        }
        return out
    }

    /**
     * Same rules as MatchService.stream for YouTube manifests: original-language audio only; Opus/WebM only when
     * [allowWebm]; [lowQuality] caps at 140 kbps (else the smallest mp4); no audio-only stream -> muxed mp4 (itag 18).
     */
    fun pickStream(streams: List<AltAudioStream>, allowWebm: Boolean, lowQuality: Boolean): AltAudioStream? {
        var best: AltAudioStream? = null
        fun audioOnly(s: AltAudioStream) = !s.muxed && !s.dubbed
        for (s in streams) {
            if (!audioOnly(s)) continue
            if (containerOf(s.mimeType) == AltContainer.Webm && !allowWebm) continue
            if (lowQuality && s.bitrate / 1000.0 > 140) continue
            if (best == null || s.bitrate > best.bitrate) best = s
        }
        if (best == null && lowQuality) // nothing under the cap: take the smallest mp4
            for (s in streams)
                if (audioOnly(s) && containerOf(s.mimeType) == AltContainer.Mp4 && (best == null || s.bitrate < best.bitrate)) best = s
        if (best == null) // no audio-only stream at all: muxed mp4 (itag 18)
            for (s in streams)
                if (s.muxed && containerOf(s.mimeType) == AltContainer.Mp4 && (best == null || s.bitrate < best.bitrate)) best = s
        return best
    }
}

// ---- AltSource ------------------------------------------------------------------------------------------------------

class AltSource(baseClient: OkHttpClient = Http.client) {
    internal enum class Op { Stream, Search }
    /** Down = unreachable / timed out: skipped for a minute */
    internal enum class Outcome { Ok, Failed, Down }
    private class Health(val atNanos: Long, val down: Boolean)
    internal class Snapshot(val kind: AltKind, val generation: Long, val order: List<String>)

    private val lock = Any()
    private var kind = AltKind.Off
    /** normalized, "" = none */
    private var custom = ""
    private var generation = 1L
    /** tests: only the given instance, no public ones */
    @Volatile internal var useBuiltins = true
    /** op|kind -> instance that worked last */
    private val lastGood = HashMap<String, String>()
    /** op|instance -> last failure */
    private val failed = HashMap<String, Health>()

    private val transport = AltTransport(baseClient)

    /** [customInstance]: raw user text ("" = built-in list). Bumps the generation: walks in flight stop. */
    fun configure(kind: AltKind, customInstance: String) {
        val normalized = AltParse.normalizeInstance(customInstance)
        if (normalized == null && customInstance.isNotBlank())
            Log.w("altsource", "ignoring invalid instance address \"$customInstance\"")
        val next = normalized ?: ""
        synchronized(lock) {
            if (kind == this.kind && next == custom) return
            Log.i("altsource", "backup source: ${kind.settingsName}" + if (next.isEmpty()) "" else " via $next")
            // A replaced own instance must never be contacted again: forget what was learned about it.
            if (custom.isNotEmpty() && next != custom) {
                val old = custom
                lastGood.entries.removeAll { it.value == old }
                failed.keys.removeAll { it.substring(2) == old }
            }
            this.kind = kind
            custom = next
            ++generation // walks in flight stop at their next step
        }
    }

    fun kind(): AltKind = synchronized(lock) { kind }
    val enabled: Boolean get() = kind() != AltKind.Off
    /** "piped" / "invidious" for logs */
    fun label(): String = synchronized(lock) { kind.settingsName }
    /** Bumped whenever the kind or the custom instance changes. */
    fun generation(): Long = synchronized(lock) { generation }

    /** Try order for the configured kind. */
    fun instanceOrder(): List<String> = synchronized(lock) { orderLocked(Op.Stream) }

    internal fun snapshot(op: Op): Snapshot = synchronized(lock) { Snapshot(kind, generation, orderLocked(op)) }

    private fun orderLocked(op: Op): List<String> {
        val order = ArrayList<String>()
        val demoted = ArrayList<String>()
        fun push(s: String) {
            if (s.isNotEmpty() && s !in order) order.add(s)
        }
        val builtin = if (useBuiltins) builtinInstances(kind) else emptyList()
        push(custom)
        // The last good instance only while it is still a current choice (never a replaced own instance).
        lastGood[healthKey(op, kind.settingsName)]?.let { if (it == custom || it in builtin) push(it) }
        val now = System.nanoTime()
        for (b in builtin) {
            val f = failed[healthKey(op, b)]
            if (f == null || now - f.atNanos >= DEMOTE_FOR_NANOS) push(b)
            else if (!f.down || now - f.atNanos >= SKIP_DOWN_FOR_NANOS) demoted.add(b)
        }
        for (d in demoted) push(d)
        // The user's own / last good instance is skipped too while it is unreachable (its timeout would be paid again).
        order.removeAll { s ->
            val f = failed[healthKey(op, s)]
            f != null && f.down && now - f.atNanos < SKIP_DOWN_FOR_NANOS
        }
        return order
    }

    internal fun noteResult(op: Op, snap: Snapshot, instance: String, outcome: Outcome) {
        synchronized(lock) {
            if (snap.generation != generation) return // reconfigured meanwhile: this result is about an old choice
            val key = healthKey(op, snap.kind.settingsName)
            if (outcome == Outcome.Ok) {
                lastGood[key] = instance
                failed.remove(healthKey(op, instance))
            } else {
                failed[healthKey(op, instance)] = Health(System.nanoTime(), outcome == Outcome.Down)
                if (lastGood[key] == instance) lastGood[key] = ""
            }
        }
    }

    /**
     * Tries the instances in order. Throws AltSourceException (summary of every attempt) when none delivers, when an
     * instance reports the video itself unavailable, or when the configuration changes during the walk.
     */
    suspend fun stream(videoId: String, allowWebm: Boolean, lowQuality: Boolean): AltServed {
        val snap = snapshot(Op.Stream)
        if (snap.kind == AltKind.Off) throw AltSourceException("backup source is off")
        val walkEnd = System.nanoTime() + BUDGET_NANOS
        // Between any two requests: the user may have cancelled, or turned the source off / changed it.
        val checkpoint: suspend () -> Unit = {
            coroutineContext.ensureActive()
            if (generation() != snap.generation) throw AltSourceException("backup source changed")
        }
        val errors = StringBuilder()
        for (instance in snap.order) {
            checkpoint()
            if (errors.isNotEmpty() && System.nanoTime() >= walkEnd) {
                errors.append("; out of time")
                break
            }
            try {
                val s = transport.fetchStream(snap.kind, instance, videoId, allowWebm, lowQuality, walkEnd, checkpoint)
                noteResult(Op.Stream, snap, instance, Outcome.Ok)
                return s.copy(generation = snap.generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation() != snap.generation) throw AltSourceException("backup source changed")
                val msg = e.message ?: e.javaClass.simpleName
                Log.w("altsource", "${snap.kind.settingsName} stream $videoId via ${AltParse.hostOf(instance)}: $msg")
                // about the video, not the instance: no demotion, no other instance
                if (AltParse.videoGone(msg)) throw AltSourceException("${snap.kind.settingsName}: video unavailable ($msg)")
                noteResult(Op.Stream, snap, instance, failureOf(e))
                if (errors.isNotEmpty()) errors.append("; ")
                errors.append(AltParse.hostOf(instance)).append(": ").append(msg)
            }
        }
        throw AltSourceException(
            "${snap.kind.settingsName}: no instance delivered $videoId (" + errors.ifEmpty { "no instances" } + ")",
        )
    }

    /** [music]: Piped's "music_songs" filter (else "videos"); ignored by Invidious. */
    suspend fun search(query: String, music: Boolean): List<AltSearchItem> {
        val snap = snapshot(Op.Search)
        if (snap.kind == AltKind.Off) throw AltSourceException("backup source is off")
        val walkEnd = System.nanoTime() + BUDGET_NANOS
        val errors = StringBuilder()
        for (instance in snap.order) {
            coroutineContext.ensureActive()
            if (generation() != snap.generation) throw AltSourceException("backup source changed")
            if (errors.isNotEmpty() && System.nanoTime() >= walkEnd) {
                errors.append("; out of time")
                break
            }
            try {
                val items = transport.fetchSearch(snap.kind, instance, query, music, walkEnd)
                noteResult(Op.Search, snap, instance, Outcome.Ok)
                Log.i("altsource", "${snap.kind.settingsName} search \"$query\" via ${AltParse.hostOf(instance)}: ${items.size} result(s)")
                return items
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation() != snap.generation) throw AltSourceException("backup source changed")
                noteResult(Op.Search, snap, instance, failureOf(e))
                val msg = e.message ?: e.javaClass.simpleName
                Log.w("altsource", "${snap.kind.settingsName} search via ${AltParse.hostOf(instance)}: $msg")
                if (errors.isNotEmpty()) errors.append("; ")
                errors.append(AltParse.hostOf(instance)).append(": ").append(msg)
            }
        }
        throw AltSourceException("${snap.kind.settingsName} search failed (" + errors.ifEmpty { "no instances" } + ")")
    }

    /** One instance, no fallback (tests / diagnostics). Throws AltSourceException. */
    suspend fun streamFrom(kind: AltKind, instance: String, videoId: String, allowWebm: Boolean, lowQuality: Boolean): AltServed =
        transport.fetchStream(kind, instance, videoId, allowWebm, lowQuality, System.nanoTime() + BUDGET_NANOS, null)

    suspend fun searchOn(kind: AltKind, instance: String, query: String, music: Boolean): List<AltSearchItem> =
        transport.fetchSearch(kind, instance, query, music, System.nanoTime() + BUDGET_NANOS)

    companion object {
        /** wall clock per request, body included */
        const val REQUEST_TIMEOUT_MS = 8_000L
        /** a whole walk, the attempt in flight included */
        const val BUDGET_MS = 25_000L
        /** a failed instance is tried last for a while */
        const val DEMOTE_FOR_MS = 5 * 60_000L
        /** an unreachable one is not tried at all */
        const val SKIP_DOWN_FOR_MS = 60_000L
        /** decompressed API answers */
        const val MAX_JSON = 4L shl 20
        /** only the error text is used */
        const val MAX_ERROR_BODY = 16L shl 10
        const val PROBE_BYTES = 16L
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36 " +
                "ShadeTube/0.1"

        private const val BUDGET_NANOS = BUDGET_MS * 1_000_000
        private const val DEMOTE_FOR_NANOS = DEMOTE_FOR_MS * 1_000_000
        private const val SKIP_DOWN_FOR_NANOS = SKIP_DOWN_FOR_MS * 1_000_000

        fun builtinInstances(kind: AltKind): List<String> = when (kind) {
            AltKind.Piped -> AltParse.PIPED_INSTANCES
            AltKind.Invidious -> AltParse.INVIDIOUS_INSTANCES
            AltKind.Off -> emptyList()
        }

        /** Health is kept per operation: an instance can answer searches while YouTube blocks its stream extraction. */
        private fun healthKey(op: Op, instance: String) = (if (op == Op.Stream) "s|" else "q|") + instance

        /** Transport failures (DNS, refused, timeout) mean the instance is down: HTTP error answers come back as responses. */
        private fun failureOf(e: Exception) = if (e is AltUnreachableException) Outcome.Down else Outcome.Failed
    }
}

/**
 * AltSource's own transport over OkHttp (core/Http's shared pool): a wall-clock deadline for the whole request
 * (callTimeout, body included), capped bodies, optional redirects.
 */
private class AltTransport(private val base: OkHttpClient) {
    class Rq(
        val url: String,
        val method: String = "GET",
        val headers: List<Pair<String, String>> = emptyList(),
        val deadlineNanos: Long,
        /** 2xx body cap (error bodies: MAX_ERROR_BODY, truncated) */
        val maxBody: Long = AltSource.MAX_JSON,
        /** at the cap: stop reading (probe) instead of failing */
        val truncate: Boolean = false,
        /** stream URLs: off (a redirect could lead anywhere) */
        val followRedirects: Boolean = true,
        val decompress: Boolean = false,
    )

    class Rs(val status: Int, val body: ByteArray, val contentRange: String, val contentLength: Long) {
        val ok: Boolean get() = status in 200..299
        val text: String get() = body.toString(Charsets.UTF_8)
    }

    private fun requestDeadline(walkEnd: Long) = minOf(System.nanoTime() + AltSource.REQUEST_TIMEOUT_MS * 1_000_000, walkEnd)

    suspend fun perform(rq: Rq): Rs {
        coroutineContext.ensureActive()
        // The whole call (connect, headers, body) gets the time that is left, so the deadline also holds against a
        // slow drip.
        val leftMs = (rq.deadlineNanos - System.nanoTime()) / 1_000_000
        if (leftMs <= 0) throw AltUnreachableException("timed out (connect)")
        val client = base.newBuilder()
            .callTimeout(minOf(leftMs, 60_000L), TimeUnit.MILLISECONDS)
            .followRedirects(rq.followRedirects)
            .followSslRedirects(rq.followRedirects)
            .build()
        val request = try {
            Request.Builder().url(rq.url).also { b ->
                b.header("User-Agent", AltSource.USER_AGENT)
                // Without decompression the bytes must arrive as sent (Range probe): ask for no content coding, which
                // also stops OkHttp's transparent gzip. With it, OkHttp negotiates gzip and the cap counts decompressed
                // bytes.
                if (!rq.decompress) b.header("Accept-Encoding", "identity")
                for ((k, v) in rq.headers) b.header(k, v)
                b.method(rq.method, null)
            }.build()
        } catch (e: IllegalArgumentException) {
            throw AltSourceException("invalid URL")
        }
        try {
            return executeCancellable(client, request) { resp ->
                val ok = resp.code in 200..299
                val contentRange = resp.header("Content-Range").orEmpty()
                val contentLength = resp.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                val cap = if (ok) rq.maxBody else AltSource.MAX_ERROR_BODY
                val stopAtCap = rq.truncate || !ok
                if (!stopAtCap && contentLength > cap) throw AltSourceException("response too large")
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                if (rq.method != "HEAD") {
                    resp.body.byteStream().use { input ->
                        while (!stopAtCap || out.size() < cap) {
                            var want = buf.size
                            if (stopAtCap) want = minOf(want.toLong(), cap - out.size()).toInt()
                            val got = input.read(buf, 0, want)
                            if (got <= 0) break
                            if (!stopAtCap && out.size() + got > cap) throw AltSourceException("response too large")
                            out.write(buf, 0, got)
                        }
                    }
                }
                // closing the response aborts whatever the server still wanted to send
                Rs(resp.code, out.toByteArray(), contentRange, contentLength)
            }
        } catch (e: InterruptedIOException) {
            throw AltUnreachableException("timed out")
        } catch (e: IOException) {
            throw AltUnreachableException("request failed (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private suspend fun getJson(url: String, walkEnd: Long): Rs =
        perform(Rq(url, headers = listOf("Accept" to "application/json"), deadlineNanos = requestDeadline(walkEnd), decompress = true))

    private fun httpError(r: Rs): AltSourceException {
        val e = AltParse.errorOfBody(r.text)
        return AltSourceException("HTTP ${r.status}" + if (e.isEmpty()) "" else " ($e)")
    }

    /**
     * Proves the URL answers ranged requests with the promised container (what the player's progressive download
     * needs to play and seek) and makes sure the total length is known: a player would otherwise take it from its
     * first Content-Range, and Piped's proxy reports the chunk size there ("bytes 0-15/16", "bytes 1000000-15/16").
     * A server that ignores Range costs at most 16 bytes: the transfer is closed after them.
     */
    private suspend fun probeRange(s: AltAudioStream, walkEnd: Long): AltAudioStream {
        val r = perform(
            Rq(
                s.url, headers = listOf("Range" to "bytes=0-${AltSource.PROBE_BYTES - 1}"), deadlineNanos = requestDeadline(walkEnd),
                maxBody = AltSource.PROBE_BYTES, truncate = true, followRedirects = false,
            ),
        )
        if (r.status != 206)
            throw AltSourceException(if (r.status == 200) "stream ignores range requests" else "stream answered HTTP ${r.status}")
        val want = AltParse.containerOf(s.mimeType)
        if (AltParse.sniffContainer(r.body) != want)
            throw AltSourceException("stream is not ${if (want == AltContainer.Webm) "WebM" else "MP4"} data")
        if (s.contentLength > 0) return s // from the API (clen / contentLength)
        var total = 0L
        val slash = r.contentRange.lastIndexOf('/') // "bytes 0-15/3433514"
        if (slash >= 0) total = parseLeadingLong(r.contentRange.substring(slash + 1))
        if (total <= AltSource.PROBE_BYTES) { // missing, "*" or the chunk size: HEAD has the real length
            val h = perform(
                Rq(s.url, method = "HEAD", deadlineNanos = requestDeadline(walkEnd), maxBody = 0, truncate = true, followRedirects = false),
            )
            total = if (h.ok) h.contentLength else 0
        }
        if (total <= AltSource.PROBE_BYTES) throw AltSourceException("stream length unknown")
        return s.copy(contentLength = total)
    }

    /** _wtoi64: leading digits (after optional whitespace / sign); 0 when there are none. */
    private fun parseLeadingLong(s: String): Long {
        val t = s.trimStart()
        var i = 0
        if (i < t.length && (t[i] == '+' || t[i] == '-')) i++
        while (i < t.length && t[i] in '0'..'9') i++
        return t.substring(0, i).toLongOrNull() ?: 0
    }

    /**
     * One instance: API answer -> acceptable stream URLs -> pick -> Range probe. [checkpoint] runs before the probe
     * (the walk's configuration / cancellation check).
     */
    suspend fun fetchStream(
        kind: AltKind,
        instance: String,
        videoId: String,
        allowWebm: Boolean,
        lowQuality: Boolean,
        walkEnd: Long,
        checkpoint: (suspend () -> Unit)?,
    ): AltServed {
        if (kind == AltKind.Off) throw AltSourceException("backup source is off")
        val url = if (kind == AltKind.Piped) "$instance/streams/$videoId" else "$instance/api/v1/videos/$videoId?local=true"
        val r = getJson(url, walkEnd)
        if (!r.ok) throw httpError(r)
        val v = if (kind == AltKind.Piped) AltParse.parsePipedStreams(r.text, instance) else AltParse.parseInvidiousVideo(r.text, instance)
        val offered = v.streams.size
        val acceptable = v.streams.filter { AltParse.acceptableStreamUrl(it.url, instance, v.proxyUrl) }
        val pick = AltParse.pickStream(acceptable, allowWebm, lowQuality) ?: throw AltSourceException(
            when {
                offered == 0 -> "no audio streams"
                acceptable.isEmpty() -> "stream URLs outside the instance"
                else -> "no usable audio stream"
            },
        )
        checkpoint?.invoke()
        val probed = probeRange(pick, walkEnd)
        return AltServed(probed, kind, instance, v.copy(streams = emptyList()))
    }

    suspend fun fetchSearch(kind: AltKind, instance: String, query: String, music: Boolean, walkEnd: Long): List<AltSearchItem> {
        if (kind == AltKind.Off) throw AltSourceException("backup source is off")
        val q = Http.urlEncode(query)
        val url = if (kind == AltKind.Piped) "$instance/search?q=$q&filter=" + (if (music) "music_songs" else "videos")
        else "$instance/api/v1/search?q=$q&type=video"
        val r = getJson(url, walkEnd)
        if (!r.ok) throw httpError(r)
        return if (kind == AltKind.Piped) AltParse.parsePipedSearch(r.text) else AltParse.parseInvidiousSearch(r.text)
    }
}
