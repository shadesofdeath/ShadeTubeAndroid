package com.app.shadetube.youtube

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.YOUTUBE_ID_PREFIX
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.AudioQuality
import com.app.shadetube.core.Files
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.appScope
import com.app.shadetube.core.bool
import com.app.shadetube.core.double
import com.app.shadetube.core.int
import com.app.shadetube.core.isObject
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.obj
import com.app.shadetube.core.str
import com.app.shadetube.youtube.explode.AudioOnlyStreamInfo
import com.app.shadetube.youtube.explode.AudioStreamInfo
import com.app.shadetube.youtube.explode.Author
import com.app.shadetube.youtube.explode.HttpRequestException
import com.app.shadetube.youtube.explode.MuxedStreamInfo
import com.app.shadetube.youtube.explode.RequestLimitExceededException
import com.app.shadetube.youtube.explode.Thumbnail
import com.app.shadetube.youtube.explode.TrackCandidate
import com.app.shadetube.youtube.explode.TrackMatch
import com.app.shadetube.youtube.explode.TrackMatcher
import com.app.shadetube.youtube.explode.TrackMatcherOptions
import com.app.shadetube.youtube.explode.TrackQuery
import com.app.shadetube.youtube.explode.VideoAgeRestrictedException
import com.app.shadetube.youtube.explode.VideoId
import com.app.shadetube.youtube.explode.VideoRequiresPurchaseException
import com.app.shadetube.youtube.explode.VideoSearchResult
import com.app.shadetube.youtube.explode.VideoUnavailableException
import com.app.shadetube.youtube.explode.VideoUnplayableException
import com.app.shadetube.youtube.explode.CipherExtractionException
import com.app.shadetube.youtube.explode.YoutubeClient
import com.app.shadetube.youtube.explode.YoutubeClientOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** A catalog track's YouTube video (one entry of matches.json). */
data class Match(
    val videoId: String,
    val title: String = "",
    val channel: String = "",
    val durationSec: Int = 0,
    /** TrackMatcher score (0..100); 100 for manual picks */
    val score: Double = 0.0,
    /** pinned by the user via "Wrong match?" */
    val manual: Boolean = false,
    val thumbnailUrl: String = "",
)

/**
 * Everything the player needs to play one resolved audio stream (the C++ youtube::StreamInfo).
 *
 * - googlevideo URLs ([isGoogleVideo]) are fetched in ranges with the `&range=a-b` query parameter, which is what
 *   YouTube's own players use (a plain single request is throttled to about real time); backup-source proxies take a
 *   normal `Range` header.
 * - Normalisation: YouTube reports [loudnessDb] against its -14 LUFS reference, so a stream gets
 *   `target + 14 - loudnessDb` dB (null = unknown: no gain).
 */
data class ResolvedStream(
    val videoId: String,
    val url: String,
    /** bytes; 0 = unknown */
    val contentLength: Long,
    /** "audio/mp4" | "audio/webm" */
    val mimeType: String,
    /** "mp4a.40.2" | "opus" */
    val codec: String,
    val bitrateKbps: Int,
    val itag: Int,
    /** YouTube's loudness of the stream vs. its -14 LUFS reference (dB); null = unknown */
    val loudnessDb: Double?,
    /** Epoch ms after which the URL must be re-resolved (googlevideo `expire` - 10 min; backup streams 1 h at most). */
    val expiresAtMs: Long,
    /** "youtube" | "piped <host>" | "invidious <host>" (logs) */
    val source: String,
    /** Epoch ms when it was resolved. */
    val fetchedAtMs: Long,
    /** Headers to send with every request for [url] (the User-Agent of the client the URL was issued to). */
    val requestHeaders: Map<String, String> = emptyMap(),
) {
    val isGoogleVideo: Boolean get() = AltParse.hostOf(url).lowercase(Locale.ROOT).contains("googlevideo.com")
    val isFromYoutube: Boolean get() = source == "youtube"
}

data class Resolved(val match: Match, val stream: ResolvedStream)

/** No (playable) YouTube match for a track, or no playable audio stream in a video. */
class NoMatchException(message: String) : Exception(message)

/** Why a resolve / stream / candidates call failed (the UI maps it to a message). */
enum class MatchFailure { NoMatch, Unavailable, AgeRestricted, RequiresPurchase, Unplayable, RateLimited, Network, Cipher, BackupSource, Other }

fun matchFailureOf(t: Throwable): MatchFailure = when (t) {
    is NoMatchException -> MatchFailure.NoMatch
    is VideoUnavailableException -> MatchFailure.Unavailable
    is VideoAgeRestrictedException -> MatchFailure.AgeRestricted
    is VideoRequiresPurchaseException -> MatchFailure.RequiresPurchase
    is VideoUnplayableException -> MatchFailure.Unplayable
    is RequestLimitExceededException -> MatchFailure.RateLimited
    is HttpRequestException -> MatchFailure.Network
    is java.io.IOException -> MatchFailure.Network
    is CipherExtractionException -> MatchFailure.Cipher
    is AltSourceException -> MatchFailure.BackupSource
    else -> MatchFailure.Other
}

/**
 * Catalog track -> YouTube video -> playable audio stream (port of src/youtube/MatchService).
 *
 * Layers (fastest first):
 *   1. stream cache   videoId -> stream, in memory, until the googlevideo URL's `expire` - 10 min
 *   2. match cache    trackId -> Match, persistent (cache/matches.json); manual picks are pinned forever
 *   3. TrackMatcher   YouTube search + ranking (explode TrackMatcher), ~1 request
 * A cached match costs a single getManifest() request on the next play.
 *
 * Backup source (Settings.altSource, off by default): when YoutubeExplode cannot deliver a stream or a search, the
 * Piped / Invidious instance ([AltSource]) is asked instead; its search results are ranked by the same TrackMatcher
 * scorer. [ResolvedStream.source] tells which one served a stream (logs only). A YouTube 429 leaves YouTube alone for
 * 3 minutes while the backup is on. Turning the backup off / changing it drops every stream it served. A stand-in
 * found after a failure that may pass is played but not persisted as the track's match.
 *
 * Every public function is a main-safe, cancellable suspend function; failures come back as [Result] (classify them
 * with [matchFailureOf]).
 */
class MatchService(
    val client: YoutubeClient = YoutubeClient(defaultClientOptions()),
    private val cacheFileProvider: () -> File = { Paths.cache("matches.json") },
    private val scope: CoroutineScope = appScope,
    observeSettings: Boolean = true,
) {
    val altSource = AltSource()
    @Volatile private var forceAlt = false
    private val youtubeLimitedUntil = AtomicLong(Long.MIN_VALUE)

    private val lock = Any()
    private val matches = HashMap<String, Match>()
    private val streams = HashMap<String, ResolvedStream>()
    private var dirty = false
    private var saveJob: Job? = null

    private val loadMutex = Mutex()
    @Volatile private var loaded = false

    init {
        if (observeSettings) {
            val s = SettingsStore.current
            setAltSource(s.altSource, s.altSourceInstance)
            // The Settings page changes it at any time: keep our copy in sync (AltSourceSettings.cpp pushed it).
            scope.launch {
                SettingsStore.flow.map { it.altSource to it.altSourceInstance }.distinctUntilChanged()
                    .collect { (kind, instance) -> setAltSource(kind, instance) }
            }
        }
    }

    // ---- public API -------------------------------------------------------------------------------------------------

    /**
     * Best match + stream for [track]. [allowWebm]: pick Opus/WebM when it has the best bitrate; [lowQuality]: cap to
     * <= 128 kbps AAC (data saver). A track that knows its video (manual pick, `yt:<videoId>` songs) skips matching.
     */
    suspend fun resolve(track: Track, allowWebm: Boolean, lowQuality: Boolean): Result<Resolved> =
        catching { resolveImpl(track, allowWebm, lowQuality) }

    /** [resolve] with the rules of an [AudioQuality]: High = best bitrate (Opus or AAC), Normal = <= 128 kbps AAC. */
    suspend fun resolve(track: Track, quality: AudioQuality = SettingsStore.current.quality): Result<Resolved> =
        resolve(track, allowWebm = quality == AudioQuality.High, lowQuality = quality == AudioQuality.Normal)

    /**
     * Stream for an explicit video (manual pick / re-resolve after URL expiry). [bypassCache] re-resolves even when a
     * cached URL is still valid (the player's stream failed): a YouTube stream that failed this soon is retried via the
     * backup source first when one is set.
     */
    suspend fun stream(videoId: String, allowWebm: Boolean, lowQuality: Boolean, bypassCache: Boolean = false): Result<ResolvedStream> =
        catching { streamImpl(videoId, allowWebm, lowQuality, bypassCache) }

    /** Candidates for the "Wrong match?" picker (best first; includes the current match at the top). */
    suspend fun candidates(track: Track): Result<List<Match>> = catching {
        val options = TrackMatcherOptions(maxSearchResults = 20, minimumScore = -1000.0) // show everything, ranked
        val out = ArrayList<Match>()
        find(toQuery(track), options)?.let { found ->
            out.add(toMatch(found.best))
            for (other in found.alternatives) out.add(toMatch(other))
        }
        // Keep the current (possibly manual) match visible at the top.
        cachedMatch(track.id)?.let { current ->
            out.removeAll { it.videoId == current.videoId }
            out.add(0, current)
        }
        out
    }

    /** Pins [match] as the track's video (manual = true, score 100); persisted. */
    suspend fun pin(trackId: String, match: Match) {
        ensureLoaded()
        synchronized(lock) {
            matches[trackId] = match.copy(manual = true, score = 100.0)
            markDirtyLocked()
        }
    }

    suspend fun cachedMatch(trackId: String): Match? {
        ensureLoaded()
        return synchronized(lock) { matches[trackId] }
    }

    /**
     * The cached match without suspending: null when unknown or while matches.json is not loaded yet (AppGraph loads it
     * at startup). For non-suspending callers (playlist export's cachedVideoId).
     */
    fun cachedMatchNow(trackId: String): Match? = synchronized(lock) { matches[trackId] }

    /** Loads matches.json now (startup warm-up; otherwise loaded on first use). */
    suspend fun preload() = ensureLoaded()

    /** Drops the cached streams of a video (every quality variant). */
    fun forgetStream(videoId: String) {
        synchronized(lock) { streams.keys.removeAll { it.startsWith("$videoId|") } } // keys: "<id>|w|m" + "l|h"
    }

    /**
     * Settings › Depolama "Eşleşme önbelleği": forgets every automatic match (memory and matches.json) and the cached
     * stream URLs; manual picks ("Yanlış eşleşme?") stay pinned, as in the desktop app, which never dropped them.
     * Returns how many matches were removed.
     */
    suspend fun clearCache(): Int {
        ensureLoaded()
        val removed = synchronized(lock) {
            val before = matches.size
            matches.values.removeAll { !it.manual }
            streams.clear()
            saveJob?.cancel()
            dirty = true
            before - matches.size
        }
        withContext(AppDispatchers.io) {
            val empty = synchronized(lock) { matches.isEmpty() }
            if (empty) {
                synchronized(lock) { dirty = false }
                cacheFileProvider().delete()
            } else {
                flushNow()
            }
        }
        Log.i(TAG, "match cache cleared: $removed removed, ${cachedCount()} pinned kept")
        return removed
    }

    private fun cachedCount() = synchronized(lock) { matches.size }

    /** Persists the match cache if it changed (also done automatically 2 s after a change). */
    suspend fun flush() = withContext(AppDispatchers.io) { flushNow() }

    /**
     * Backup source: Settings.altSource ("off" | "piped" | "invidious") + altSourceInstance ("" = built-in list). Any
     * thread. Cached streams served by a backup server are dropped.
     */
    fun setAltSource(kind: String, instance: String) {
        altSource.configure(AltKind.parse(kind), instance) // bumps the generation: walks in flight stop
        // Streams served through a backup server must not outlive the choice (a replay would still go through it).
        synchronized(lock) { streams.values.removeAll { it.source != "youtube" } }
    }

    /**
     * Test switch (the desktop app's SHADETUBE_FORCE_ALT=1): skip YoutubeExplode entirely while a backup source is set,
     * so the fallback path runs. Off by default.
     */
    fun forceAltSource(on: Boolean) {
        forceAlt = on
        if (on) Log.w(TAG, "force alt: YoutubeExplode is bypassed while a backup source is set")
    }

    // ---- persistence ------------------------------------------------------------------------------------------------

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            withContext(AppDispatchers.io) { load() }
            loaded = true
        }
    }

    private fun load() {
        val j = Files.readJson(cacheFileProvider()) ?: return
        val parsed = matchesFromJson(j)
        synchronized(lock) {
            for ((id, m) in parsed) matches.putIfAbsent(id, m)
        }
        Log.i(TAG, "match cache: ${parsed.size} entries")
    }

    private fun flushNow() {
        val j = synchronized(lock) {
            if (!dirty) return
            dirty = false
            matchesToJson(matches)
        }
        Files.writeJson(cacheFileProvider(), j)
    }

    private fun markDirtyLocked() {
        dirty = true
        saveJob?.cancel()
        saveJob = scope.launch(AppDispatchers.io) {
            delay(SAVE_DEBOUNCE_MS)
            flushNow()
        }
    }

    // ---- rate limit -------------------------------------------------------------------------------------------------

    /** A 429 was seen in the last few minutes. */
    private fun youtubeLimited() = System.nanoTime() - youtubeLimitedUntil.get() < 0

    private fun noteYoutubeLimited() {
        val now = System.nanoTime()
        val previous = youtubeLimitedUntil.getAndSet(now + YOUTUBE_LIMITED_FOR_MS * 1_000_000)
        if (now - previous >= 0)
            Log.w(TAG, "YouTube rate limit (429)" + if (altSource.enabled) ": only the backup source is asked for 3 minutes" else "")
    }

    // ---- streams ----------------------------------------------------------------------------------------------------

    private suspend fun streamImpl(videoId: String, allowWebm: Boolean, lowQuality: Boolean, bypassCache: Boolean): ResolvedStream {
        val key = streamKey(videoId, allowWebm, lowQuality)
        val now = System.currentTimeMillis()
        val altOn = altSource.enabled
        var youtubeFailed = false
        synchronized(lock) {
            streams[key]?.let { cached ->
                // A backup server's stream only while the backup source is on (setAltSource also purges them).
                val usable = cached.source == "youtube" || altOn
                if (!bypassCache && usable && cached.expiresAtMs > now) return cached
                youtubeFailed = bypassCache && cached.source == "youtube" && now - cached.fetchedAtMs < FRESH_STREAM_MS
            }
        }

        val info: ResolvedStream
        var altGeneration = 0L // the backup configuration a backup stream was fetched under
        if (altOn && (forceAlt || youtubeLimited())) {
            // test switch / YouTube rate-limited us
            val (s, g) = altStream(videoId, allowWebm, lowQuality)
            info = s
            altGeneration = g
        } else if (altOn && youtubeFailed) {
            Log.w(TAG, "YouTube stream for $videoId failed during playback: asking ${altSource.label()} first")
            var s: ResolvedStream
            try {
                val (a, g) = altStream(videoId, allowWebm, lowQuality)
                s = a
                altGeneration = g
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${altSource.label()} fallback for $videoId failed (${e.message}): back to YouTube")
                s = youtubeStream(videoId, allowWebm, lowQuality)
            }
            info = s
        } else {
            var s: ResolvedStream
            try {
                s = youtubeStream(videoId, allowWebm, lowQuality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VideoRequiresPurchaseException) {
                throw e // paid content: no instance plays it either
            } catch (e: Exception) {
                // Includes VideoUnavailableException: YouTube's bot check ("Sign in to confirm you're not a bot")
                // reaches us as "not available" for every video, which is exactly when the backup is needed.
                if (e is RequestLimitExceededException) noteYoutubeLimited()
                if (!altSource.enabled) throw e
                Log.w(TAG, "YouTube stream for $videoId failed (${e.message}): trying ${altSource.label()}")
                try {
                    val (a, g) = altStream(videoId, allowWebm, lowQuality)
                    s = a
                    altGeneration = g
                } catch (c: CancellationException) {
                    throw c
                } catch (altError: Exception) {
                    Log.w(TAG, "${altSource.label()} fallback for $videoId failed: ${altError.message}")
                    throw e // callers react to YouTube's error type (unplayable -> next candidate)
                }
            }
            info = s
        }

        synchronized(lock) {
            // Turned off / changed while the backup was fetching: that server must not be used any more, not even now.
            // (The purge in setAltSource runs after the generation bump, so a stale entry can't be stored behind its
            // back either.)
            if (info.source != "youtube" && altSource.generation() != altGeneration) throw AltSourceException("backup source changed")
            if (streams.size > 256) streams.clear() // bounded; entries are tiny and cheap to refetch
            streams[key] = info
        }
        return info
    }

    /** Returns the stream and the AltSource configuration (generation) it was fetched under. */
    private suspend fun altStream(videoId: String, allowWebm: Boolean, lowQuality: Boolean): Pair<ResolvedStream, Long> {
        val served = withContext(AppDispatchers.io) { altSource.stream(videoId, allowWebm, lowQuality) }
        val now = System.currentTimeMillis()
        val s = served.stream
        val info = ResolvedStream(
            videoId = videoId,
            url = s.url,
            contentLength = s.contentLength,
            mimeType = s.mimeType,
            codec = s.codec,
            bitrateKbps = s.bitrate / 1000,
            itag = s.itag,
            loudnessDb = null,
            expiresAtMs = minOf(urlExpiry(s.url, now), now + ALT_STREAM_TTL_MS),
            source = served.kind.settingsName + " " + AltParse.hostOf(served.instance),
            fetchedAtMs = now,
            requestHeaders = mapOf("User-Agent" to AltSource.USER_AGENT),
        )
        Log.i(
            TAG,
            "stream $videoId served by ${info.source}: ${info.mimeType} ${info.codec} ${info.bitrateKbps} kbps, itag ${info.itag}" +
                if (s.muxed) " (muxed)" else "",
        )
        return info to served.generation
    }

    private suspend fun youtubeStream(videoId: String, allowWebm: Boolean, lowQuality: Boolean): ResolvedStream {
        val manifest = withContext(AppDispatchers.io) { client.videos.streams.getManifest(VideoId.parse(videoId)) }
        val best = selectYoutubeStream(manifest.audioOnlyStreams, manifest.muxedStreams, allowWebm, lowQuality)
            ?: throw NoMatchException("No playable audio stream for video $videoId")
        val now = System.currentTimeMillis()
        val info = ResolvedStream(
            videoId = videoId,
            url = best.url,
            contentLength = best.size.bytes,
            mimeType = if (best.container.name == "webm") "audio/webm" else "audio/mp4",
            codec = best.audioCodec,
            bitrateKbps = (best.bitrate.bitsPerSecond / 1000).toInt(),
            itag = best.itag,
            loudnessDb = best.loudnessDb,
            expiresAtMs = urlExpiry(best.url, now),
            source = "youtube",
            fetchedAtMs = now,
            requestHeaders = if (best.userAgent.isNotEmpty()) mapOf("User-Agent" to best.userAgent) else emptyMap(),
        )
        Log.i(
            TAG,
            "stream $videoId served by youtube: ${info.mimeType} ${info.codec} ${info.bitrateKbps} kbps, itag ${info.itag}, loudness " +
                (info.loudnessDb?.let { String.format(Locale.US, "%+.1f dB", it) } ?: "unknown"),
        )
        return info
    }

    // ---- matching ---------------------------------------------------------------------------------------------------

    private suspend fun resolveImpl(track: Track, allowWebm: Boolean, lowQuality: Boolean): Resolved {
        // A track that already knows its video (manual pick stored in the library, "yt:<videoId>" songs) skips matching.
        val knownVideo = track.videoId.ifEmpty {
            if (track.id.startsWith(YOUTUBE_ID_PREFIX)) track.id.substring(YOUTUBE_ID_PREFIX.length) else ""
        }
        if (knownVideo.isNotEmpty() && cachedMatch(track.id) == null) {
            val m = Match(videoId = knownVideo, title = track.name, score = 100.0, manual = true)
            return Resolved(m, streamImpl(knownVideo, allowWebm, lowQuality, false))
        }

        var failedId = "" // the cached video that just failed: not tried again as a candidate
        var cachedError: Exception? = null
        // An earlier attempt failed for a reason that may pass (rate limit, network, an instance refusing that video):
        // whatever plays now is a stand-in for this play only, the persisted match is left alone.
        var transient = false
        val cached = cachedMatch(track.id)
        if (cached != null) {
            try {
                return Resolved(cached, streamImpl(cached.videoId, allowWebm, lowQuality, false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: VideoUnplayableException) {
                // Removed / region-locked since we cached it: re-match (unless pinned). The entry is only replaced once
                // another candidate plays: YouTube's bot check calls every video unavailable and must not wipe the
                // cache.
                Log.w(TAG, "cached video ${cached.videoId} unplayable: ${e.message}")
                if (cached.manual) throw e
                failedId = cached.videoId
                cachedError = e
            } catch (e: Exception) {
                // Neither YouTube nor the backup source plays it right now. Instances are often blocked per video, so
                // look for another upload for this play.
                if (!altSource.enabled || cached.manual) throw e
                Log.w(TAG, "cached video ${cached.videoId} failed (${e.message}): trying other candidates")
                failedId = cached.videoId
                cachedError = e
                transient = true
            }
        }

        val found = find(toQuery(track), TrackMatcherOptions())
        // Try the best candidate, then the next ones if YouTube refuses to play them.
        val order = ArrayList<TrackCandidate>()
        if (found != null) {
            for (c in listOf(found.best) + found.alternatives) {
                if (order.size < 4 && c.video.id.value != failedId) order.add(c)
            }
        }
        if (order.isEmpty()) {
            cachedError?.let { throw it }
            throw NoMatchException("No YouTube match for \"${track.name}\"")
        }

        var earlierFailure = failedId.isNotEmpty()
        for (candidate in order) {
            coroutineContext.ensureActive()
            val m = toMatch(candidate)
            try {
                val s = streamImpl(m.videoId, allowWebm, lowQuality, false)
                // Persist only a match found without doubt: not after a failure that may pass, and not a
                // backup-served stand-in for a candidate YouTube refused (while YouTube is blocked, it refuses
                // everything).
                val persist = !transient && !(earlierFailure && s.source != "youtube")
                if (persist && track.id.isNotEmpty()) {
                    synchronized(lock) {
                        matches[track.id] = m
                        markDirtyLocked()
                    }
                }
                Log.i(
                    TAG,
                    "matched \"${track.name}\" -> ${m.videoId} (${String.format(Locale.US, "%.0f", m.score)})" +
                        if (persist) "" else ", this play only",
                )
                return Resolved(m, s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RequestLimitExceededException) {
                if (!altSource.enabled) throw e // retrying other candidates would only make it worse
                // stream() opened the rate-limit window: the next candidates only ask the backup source.
                transient = true
                earlierFailure = true
            } catch (e: VideoUnplayableException) {
                Log.w(TAG, "candidate ${m.videoId} unplayable: ${e.message}")
                earlierFailure = true
            } catch (e: Exception) {
                if (!altSource.enabled) throw e // with a backup source, failures can be specific to one video
                Log.w(TAG, "candidate ${m.videoId} failed: ${e.message}")
                transient = true
                earlierFailure = true
            }
        }
        throw NoMatchException("No playable YouTube match for \"${track.name}\"")
    }

    /** TrackMatcher.find with the backup source's search as the fallback (same ranking). */
    private suspend fun find(query: TrackQuery, options: TrackMatcherOptions): TrackMatch? {
        if (altSource.enabled && (forceAlt || youtubeLimited())) return altFind(query, options)
        try {
            return withContext(AppDispatchers.io) { TrackMatcher(client, options).find(query) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is RequestLimitExceededException) noteYoutubeLimited()
            if (!altSource.enabled) throw e
            Log.w(TAG, "YouTube search failed (${e.message}): searching via ${altSource.label()}")
            try {
                return altFind(query, options)
            } catch (c: CancellationException) {
                throw c
            } catch (altError: Exception) {
                Log.w(TAG, "${altSource.label()} search failed too: ${altError.message}")
                throw e
            }
        }
    }

    /**
     * The backup source's search results, ranked by TrackMatcher's own scorer (title / artist / duration / channel /
     * unwanted variants). First the same video search as TrackMatcher.find (so both paths agree on matches); without a
     * confident match, Piped's YouTube Music catalog ("music_songs") or, on Invidious, TrackMatcher's second phrasing.
     */
    private suspend fun altFind(query: TrackQuery, options: TrackMatcherOptions): TrackMatch? {
        val ranker = TrackMatcher(null, options)
        val results = ArrayList<VideoSearchResult>()
        fun add(items: List<AltSearchItem>) {
            results.addAll(altSearchResults(items, options.maxSearchResults))
        }
        val first = TrackMatcher.buildQuery(query)
        add(withContext(AppDispatchers.io) { altSource.search(first, false) })
        var ranked = ranker.rank(query, results)
        if (options.enableFallbackSearch && (ranked.isEmpty() || ranked[0].score < options.minimumScore)) {
            val piped = altSource.kind() == AltKind.Piped
            var second = query.title
            for (artist in query.artists) second += " $artist"
            try {
                add(withContext(AppDispatchers.io) { altSource.search(if (piped) first else "$second audio", piped) })
                ranked = ranker.rank(query, results)
            } catch (e: AltSourceException) {
                Log.w(TAG, "${altSource.label()} second search failed: ${e.message}")
            }
        }
        if (ranked.isEmpty() || ranked[0].score < options.minimumScore) return null
        Log.i(
            TAG,
            "${altSource.label()} search: ${ranked.size} candidate(s), best ${ranked[0].video.id.value} " +
                "(${String.format(Locale.US, "%.0f", ranked[0].score)})",
        )
        return TrackMatch(ranked[0], ranked.drop(1))
    }

    private suspend fun <T> catching(block: suspend () -> T): Result<T> = try {
        Result.success(withContext(AppDispatchers.io) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        private const val TAG = "youtube"

        /**
         * A re-resolve (bypassCache) this soon after YouTube handed out the same stream is not an expired URL:
         * YouTube's stream itself failed in the player (403 / broken n-parameter...), so the backup source is asked
         * first.
         */
        const val FRESH_STREAM_MS = 15 * 60_000L
        /** Instance proxies keep googlevideo's `expire`, but an instance may restart sooner: an hour at most. */
        const val ALT_STREAM_TTL_MS = 60 * 60_000L
        /** A YouTube 429 is about the whole service: with a backup source, YouTube is left alone for this long. */
        const val YOUTUBE_LIMITED_FOR_MS = 3 * 60_000L
        /** googlevideo URLs without `expire=`: assume this lifetime. */
        const val DEFAULT_URL_LIFETIME_MS = 5 * 60 * 60_000L
        /** The desktop app flushed the match cache from its 2 s housekeeping tick. */
        const val SAVE_DEBOUNCE_MS = 2_000L

        /** YoutubeExplode options of the app: interface language and region from the settings. */
        fun defaultClientOptions(): YoutubeClientOptions {
            val s = SettingsStore.current
            return YoutubeClientOptions(
                language = s.language.ifEmpty { Locale.getDefault().language.ifEmpty { "en" } },
                region = s.region.ifEmpty { "US" },
            )
        }

        internal fun streamKey(videoId: String, allowWebm: Boolean, lowQuality: Boolean) =
            videoId + (if (allowWebm) "|w" else "|m") + (if (lowQuality) "l" else "h")

        /** googlevideo URLs carry `expire=<unix>`; defaults to 5 h when absent. Epoch ms, 10 minutes early. */
        fun urlExpiry(url: String, nowMs: Long = System.currentTimeMillis()): Long {
            var pos = url.indexOf("expire=")
            if (pos < 0) return nowMs + DEFAULT_URL_LIFETIME_MS
            pos += 7
            var v = 0L
            while (pos < url.length && url[pos] in '0'..'9') v = v * 10 + (url[pos++] - '0')
            if (v <= 0) return nowMs + DEFAULT_URL_LIFETIME_MS
            return v * 1000 - 10 * 60_000L
        }

        /**
         * The audio stream played from a YouTube manifest:
         *  - only default-language (or unknown) audio: non-default dubbed tracks are dropped;
         *  - "stable volume" (DRC) variants squash the dynamics: music plays the original whenever there is one;
         *  - Opus/WebM only when [allowWebm]; [lowQuality] caps at 140 kbps (else the smallest mp4);
         *  - no audio-only stream at all: the muxed mp4 (itag 18) still plays as audio.
         */
        fun selectYoutubeStream(
            audioOnlyIn: List<AudioOnlyStreamInfo>,
            muxed: List<MuxedStreamInfo>,
            allowWebm: Boolean,
            lowQuality: Boolean,
        ): AudioStreamInfo? {
            var audioOnly = audioOnlyIn.filter { it.isAudioLanguageDefault ?: true }
            if (audioOnly.any { !it.isDrc }) audioOnly = audioOnly.filter { !it.isDrc }
            var best: AudioStreamInfo? = null
            for (s in audioOnly) {
                val webm = s.container.name == "webm"
                if (webm && !allowWebm) continue
                if (lowQuality && s.bitrate.kiloBitsPerSecond > 140) continue
                if (best == null || s.bitrate > best.bitrate) best = s
            }
            if (best == null && lowQuality) // nothing under the cap: take the smallest mp4
                for (s in audioOnly) if (s.container.name == "mp4" && (best == null || s.bitrate < best.bitrate)) best = s
            if (best == null) // no audio-only stream at all: muxed mp4 (itag 18) still plays as audio
                for (s in muxed) if (s.container.name == "mp4" && (best == null || s.bitrate < best.bitrate)) best = s
            return best
        }

        internal fun toQuery(t: Track) = TrackQuery(
            title = t.name,
            artists = t.artists.map { it.name },
            duration = if (t.durationMs > 0) t.durationMs.toLong() else null,
            album = t.album.name,
        )

        internal fun toMatch(c: TrackCandidate): Match {
            // Pick a mid-size thumbnail (the picker shows a small one).
            var thumb = ""
            for (th in c.video.thumbnails) {
                if (thumb.isEmpty() || th.resolution.width in 160..480) thumb = th.url
            }
            return Match(
                videoId = c.video.id.value,
                title = c.video.title,
                channel = c.video.author.channelTitle,
                durationSec = c.video.duration?.let { (it / 1000).toInt() } ?: 0,
                score = c.score,
                thumbnailUrl = thumb,
            )
        }

        /** Backup-source search items as search results for TrackMatcher (at most [max] of them). */
        internal fun altSearchResults(items: List<AltSearchItem>, max: Int): List<VideoSearchResult> {
            val out = ArrayList<VideoSearchResult>()
            var added = 0
            for (it in items) {
                if (added++ >= max) break
                val id = VideoId.tryParse(it.videoId) ?: continue
                val duration = if (it.durationSec > 0) it.durationSec * 1000L else null
                out.add(VideoSearchResult(id, it.title, Author(it.channelId, it.channel), duration, Thumbnail.defaultSet(it.videoId)))
            }
            return out
        }

        /** matches.json: {"<trackId>": {"v","t","c","d","s","m","th"}} (same shape as the desktop app). */
        fun matchesToJson(matches: Map<String, Match>): JsonElement = JsonObject(
            matches.mapValues { (_, m) ->
                jsonOf("v" to m.videoId, "t" to m.title, "c" to m.channel, "d" to m.durationSec, "s" to m.score, "m" to m.manual, "th" to m.thumbnailUrl)
            },
        )

        fun matchesFromJson(j: JsonElement?): Map<String, Match> {
            if (!j.isObject) return emptyMap()
            val out = LinkedHashMap<String, Match>()
            for ((id, v) in j.obj!!) {
                if (!v.isObject) continue
                val m = Match(
                    videoId = v.str("v"),
                    title = v.str("t"),
                    channel = v.str("c"),
                    durationSec = v.int("d"),
                    score = v.double("s"),
                    manual = v.bool("m"),
                    thumbnailUrl = v.str("th"),
                )
                if (m.videoId.isNotEmpty()) out[id] = m
            }
            return out
        }
    }
}
