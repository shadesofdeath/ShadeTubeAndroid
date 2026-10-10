package com.app.shadetube.playback.features

import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Hash
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpError
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.Log
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.get
import com.app.shadetube.playback.Player
import com.app.shadetube.playback.PlayerNotice
import com.app.shadetube.playback.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * SponsorBlock (port of app/SponsorBlock): skips the non-music / sponsor parts of the YouTube video that plays the
 * current track, using the community database at sponsor.ajay.app.
 *
 * Main-thread object: [setVideo] / [check] are called on the main thread, the lookup runs on the IO pool and its
 * result is applied on the main thread (a late result for a previous video is dropped: each setVideo renews the job).
 *
 * Privacy: the lookup uses the k-anonymity endpoint /api/skipSegments/<first 4 hex of sha256(videoId)>, so the server
 * never learns which video is playing; the response (all videos in that hash bucket, ~8 KB gzipped) is filtered
 * locally. There is deliberately no fallback to the plain /api/skipSegments?videoID=... lookup (it would leak the id);
 * a failed lookup is retried after 30 s.
 *
 * Skip semantics (per segment "armed" flag):
 *   - A segment is skipped when the playhead is inside it while it is armed; skipping disarms it, so the stale
 *     positions reported while the seek is in flight (or a seek landing slightly early) never loop.
 *   - It re-arms when the playhead is seen before its start (the user seeked back / the track restarted), or on a
 *     backward jump that lands within its first second (restart: "previous" -> seek(0), repeat-one). A deliberate seek
 *     into the MIDDLE of an already-skipped segment plays it (like SponsorBlock's "unskip").
 *   - No skip at position <= 0 (the player reports 0 while tracks switch; the first real tick is ~200 ms later).
 */
class SponsorBlock(
    private val scope: CoroutineScope,
    private val fetcher: suspend (videoId: String, categories: List<String>) -> List<Segment> = { id, cats -> fetchSegments(id, cats, true) },
    private val steadyMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class Segment(
        val startMs: Long,
        val endMs: Long,
        /** "music_offtopic", "sponsor", ... (first one of a merged run). */
        val category: String = "",
        /** Video length when submitted (0 = unknown). */
        val videoDurationMs: Long = 0,
    )

    enum class State { Idle, Loading, Ready, Failed }

    companion object {
        private const val API = "https://sponsor.ajay.app/api/skipSegments"

        /** Shorter: the seek's rebuffer blip costs more than it saves. */
        const val MIN_SEGMENT_MS = 1000L

        /** Neighbours this close are skipped in one seek. */
        const val MERGE_GAP_MS = 500L

        /** videoDuration vs. real length: more = outdated submission. */
        const val DURATION_TOLERANCE_MS = 3000L

        /** Playhead seen this far before a segment -> re-arm it. */
        const val REARM_LEAD_MS = 250L

        /** Backward jump landing within a segment's first second. */
        const val RESTART_WINDOW_MS = 1000L

        /** Position went back by more than this -> user seek / restart. */
        const val BACK_JUMP_MS = 1500L

        /** Don't seek for the last half second of a segment. */
        const val MIN_REMAINING_MS = 500L

        /** Failed lookup: retry from check() after this long. */
        const val RETRY_AFTER_MS = 30_000L
        const val CACHE_SIZE = 32

        /**
         * music_offtopic, sponsor, selfpromo, interaction: never music by definition. intro / outro / preview / filler
         * are left out because in music videos they often overlap real music (song intro under a logo, outro over the
         * credits, a chorus teaser, cinematic scenes with score).
         */
        fun defaultCategories() = listOf("music_offtopic", "sponsor", "selfpromo", "interaction")

        fun sha256Hex(data: String): String = Hash.sha256Hex(data)

        private fun categoriesKey(cats: List<String>): String = "c:" + cats.joinToString("") { "$it," } // never empty

        private fun secondsToMs(s: Double): Long = (s * 1000.0).roundToLong()

        private fun parseSegment(e: JsonElement, out: MutableList<Segment>) {
            if (e !is JsonObject) return
            // actionType is always present today; very old responses omitted it and meant "skip".
            val action = e["actionType"]
            if (action != null && !(action is JsonPrimitive && action.isString && action.content == "skip")) return
            val seg = e["segment"] as? JsonArray ?: return
            if (seg.size < 2) return
            val a = (seg[0] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return
            val b = (seg[1] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return
            val cat = (e["category"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            val d = (e["videoDuration"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            out.add(Segment(secondsToMs(a), secondsToMs(b), cat, if (d != null && d > 0) secondsToMs(d) else 0))
        }

        /**
         * Parses either response shape: [{segment,category,actionType,...}] (direct) or [{videoID, segments:[...]}]
         * (hash prefix; only [videoId]'s entry is used). Keeps actionType "skip" only. Throws on malformed JSON.
         */
        fun parseResponse(json: String, videoId: String): List<Segment> {
            val j = try {
                Json.parseToJsonElement(json)
            } catch (e: Exception) {
                throw IOException("SponsorBlock: malformed response")
            }
            if (j !is JsonArray) throw IOException("SponsorBlock: unexpected response shape")
            val out = ArrayList<Segment>()
            for (e in j) {
                if (e !is JsonObject) continue
                val v = e["videoID"]
                if (v != null) { // hash-prefix shape: one entry per video
                    if (!(v is JsonPrimitive && v.isString && v.content == videoId)) continue
                    (e["segments"] as? JsonArray)?.forEach { parseSegment(it, out) }
                    continue
                }
                parseSegment(e, out) // direct shape
            }
            return out
        }

        /**
         * Sorts, drops outdated (duration mismatch) / invalid ones, merges overlapping or near-adjacent (gap <= 500 ms)
         * ones and finally drops those shorter than 1 s (a seek costs more than it saves).
         */
        fun normalize(segments: List<Segment>, videoDurationMs: Long = 0): List<Segment> {
            val valid = ArrayList<Segment>()
            for (s0 in segments) {
                val s = s0.copy(startMs = maxOf(0L, s0.startMs))
                if (s.endMs <= s.startMs) continue
                if (videoDurationMs > 0 && s.videoDurationMs > 0 && abs(s.videoDurationMs - videoDurationMs) > DURATION_TOLERANCE_MS) {
                    continue // submitted for a different cut of the video: its timestamps can't be trusted
                }
                valid.add(s)
            }
            valid.sortWith(compareBy<Segment> { it.startMs }.thenByDescending { it.endMs })
            val merged = ArrayList<Segment>()
            for (s in valid) {
                val last = merged.lastOrNull()
                if (last != null && s.startMs <= last.endMs + MERGE_GAP_MS) {
                    merged[merged.size - 1] = last.copy(
                        endMs = maxOf(last.endMs, s.endMs),
                        videoDurationMs = if (last.videoDurationMs == 0L) s.videoDurationMs else last.videoDurationMs,
                    )
                } else {
                    merged.add(s)
                }
            }
            merged.removeAll { it.endMs - it.startMs < MIN_SEGMENT_MS }
            return merged
        }

        /**
         * Network lookup (main-safe). The "skip" segments of [videoId] in [categories], un-normalised. 404 = no
         * segments = empty. Throws on network / HTTP / JSON errors. [hashPrefix] = true (the app): k-anonymity endpoint
         * only; false: direct ?videoID= lookup (tests).
         */
        suspend fun fetchSegments(videoId: String, categories: List<String>, hashPrefix: Boolean = true): List<Segment> {
            if (videoId.isEmpty() || categories.isEmpty()) return emptyList()
            val cats = "[" + categories.joinToString(",") { "\"$it\"" } + "]"
            val query = "categories=" + Http.urlEncode(cats) + "&actionTypes=" + Http.urlEncode("[\"skip\"]")
            val headers = listOf("Accept" to "application/json")
            // k-anonymity: only 4 hex chars of the hash leave the device; ~0.002% of all videos share them. No fallback
            // to the direct lookup on errors: that would send the full video id. A failed lookup is retried later.
            val url = if (hashPrefix) "$API/${sha256Hex(videoId).substring(0, 4)}?$query" else "$API?videoID=${Http.urlEncode(videoId)}&$query"
            val r = withContext(AppDispatchers.io) { Http.await(HttpRequest(url, headers = headers)) }
            if (r.status == 404) return emptyList() // nothing in this bucket for these categories
            if (!r.isSuccess) throw HttpError(r.status, r.body, "SponsorBlock HTTP ${r.status}")
            return parseResponse(r.body, videoId).filter { it.category in categories }
        }
    }

    private class CacheEntry(val key: String, val raw: List<Segment>)

    var enabled = true
    var categories: List<String> = defaultCategories()

    var videoId = ""
        private set
    private var videoDurationMs = 0L

    /** Categories the current segments / lookup are for ("" = none yet). */
    private var requestKey = ""
    private var segmentsList: List<Segment> = emptyList()
    private var armed = BooleanArray(0)
    private var lastSkipped = -1
    private var lastPos = -1L
    var state = State.Idle
        private set
    private var failedAtMs = 0L
    private val cache = ArrayList<CacheEntry>()
    private var job: Job? = null
    private val _segments = MutableStateFlow<List<Segment>>(emptyList())

    /** Normalised, sorted by start (e.g. seek-bar markers, and the lyrics' "extra" ranges). */
    val segments: StateFlow<List<Segment>> = _segments.asStateFlow()
    val segmentCount: Int get() = segmentsList.size

    /** The segment returned by the last successful [check] (for a toast), or null. */
    val lastSkippedSegment: Segment? get() = segmentsList.getOrNull(lastSkipped)

    /**
     * Switches to another YouTube video; "" = none (offline / local file, not resolved yet). A different id drops the
     * old segments and (when enabled) looks up the new ones in the background. The same id again is a no-op, so this
     * may be called on every player change. [durationMs] (0 = unknown) is the real length of the video: segments
     * submitted for a length that differs by > 3 s (re-edited upload) are ignored.
     */
    fun setVideo(id: String, durationMs: Long = 0) {
        if (id.isNotEmpty() && id == videoId) return // same video: keep segments + armed state
        job?.cancel() // drop the previous video's lookup result
        videoId = id
        videoDurationMs = maxOf(0L, durationMs)
        requestKey = ""
        setSegments(emptyList(), keepKey = true)
        lastPos = -1
        state = State.Idle
        ensureFetched()
    }

    private fun setSegments(list: List<Segment>, keepKey: Boolean) {
        segmentsList = list
        armed = BooleanArray(list.size) { true }
        lastSkipped = -1
        _segments.value = list
        if (!keepKey) requestKey = categoriesKey(categories)
    }

    private fun ensureFetched() {
        if (!enabled || videoId.isEmpty()) return
        val cats = categoriesKey(categories)
        if (cats == requestKey) return // loaded, in flight or failed for exactly this configuration
        job?.cancel()
        requestKey = cats
        setSegments(emptyList(), keepKey = true)
        if (categories.isEmpty()) {
            state = State.Ready
            return
        }
        val key = videoId + '\n' + cats
        cache.firstOrNull { it.key == key }?.let {
            apply(it.raw)
            state = State.Ready
            return
        }
        state = State.Loading
        val id = videoId
        val wanted = categories
        job = scope.launch {
            val r = runCatching { fetcher(id, wanted) }
            if (id != videoId || !isActive) return@launch
            r.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@launch
                state = State.Failed
                failedAtMs = steadyMs()
                Log.w("sponsorblock", "lookup failed for $id: ${e.message}")
            }.onSuccess { raw ->
                apply(raw)
                cache.removeAll { it.key == key }
                cache.add(CacheEntry(key, raw))
                if (cache.size > CACHE_SIZE) cache.removeAt(0)
                state = State.Ready
                Log.i("sponsorblock", "$id: ${segmentsList.size} segment(s) to skip")
            }
        }
    }

    private fun apply(raw: List<Segment>) {
        setSegments(normalize(raw, videoDurationMs), keepKey = true)
    }

    /** Replaces the current video's segments (normalised, all armed) without a lookup: tests / injection. */
    fun injectSegments(raw: List<Segment>) {
        job?.cancel()
        requestKey = categoriesKey(categories) // counts as loaded: check() won't start a lookup
        apply(raw)
        lastPos = -1
        state = State.Ready
    }

    /**
     * Call every ~250 ms while playing. Returns the position (ms) to seek to when [positionMs] is inside an armed
     * segment (its end; overlapping / adjacent segments are merged into one), else null. Also starts the lookup lazily
     * when [enabled] or [categories] changed since [setVideo].
     */
    fun check(positionMs: Long): Long? {
        val last = lastPos
        lastPos = positionMs
        if (!enabled) return null
        if (state == State.Failed && steadyMs() - failedAtMs >= RETRY_AFTER_MS) requestKey = ""
        ensureFetched()
        val segs = segmentsList
        if (segs.isEmpty()) return null

        // Re-arm skipped segments the playhead has come back in front of (seek back, restart, repeat-one).
        val jumpedBack = last >= 0 && positionMs < last - BACK_JUMP_MS
        for (i in segs.indices) {
            if (armed[i]) continue
            val s = segs[i]
            if (positionMs < s.startMs - REARM_LEAD_MS || (jumpedBack && positionMs <= s.startMs + RESTART_WINDOW_MS)) armed[i] = true
        }
        // 0 is what the player reports while tracks switch (possibly for the NEXT video): never act on it.
        if (positionMs <= 0) return null

        for (i in segs.indices) {
            val s = segs[i]
            if (positionMs < s.startMs) break // sorted and non-overlapping
            if (positionMs >= s.endMs || !armed[i]) continue
            armed[i] = false
            if (s.endMs - positionMs < MIN_REMAINING_MS) return null
            lastSkipped = i
            Log.i("sponsorblock", "skipping ${s.category} $positionMs -> ${s.endMs} ms")
            return s.endMs
        }
        return null
    }
}

/**
 * Wires [SponsorBlock] to the [Player] (the C++ App::wirePlayer / sponsorTick): it follows the YouTube video that is
 * actually playing ("" while resolving or for an offline file), checks 4x per second while playing, seeks over armed
 * segments and posts a [PlayerNotice.SegmentSkipped]. [extra] is what the lyrics timeline takes out of the song
 * (LyricsTimeline.songTime / mediaTime): the segments of the matched video while SponsorBlock is on.
 */
class SponsorBlockController(
    private val player: Player,
    private val scope: CoroutineScope,
    val sponsor: SponsorBlock = SponsorBlock(scope),
    private val enabled: () -> Boolean = { SettingsStore.current.sponsorBlockEnabled },
    private val onSkipped: (PlayerNotice) -> Unit = {},
) {
    private val _extra = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())

    /** Sorted, non-overlapping [start, end) ms ranges of the playing video that aren't in the song. */
    val extra: StateFlow<List<Pair<Long, Long>>> = _extra.asStateFlow()

    fun start() {
        scope.launch { player.changed.collect { sync() } }
        scope.launch { sponsor.segments.collect { updateExtra() } }
        scope.launch {
            while (true) {
                delay(250)
                tick()
            }
        }
    }

    private fun sync() {
        // `enabled` is synced first so a user who turned it off never triggers a lookup.
        sponsor.enabled = enabled()
        val m = player.currentMatch
        sponsor.setVideo(m?.videoId ?: "", (m?.durationSec ?: 0) * 1000L)
        updateExtra()
    }

    private fun updateExtra() {
        val m = player.currentMatch
        _extra.value = if (m == null || m.videoId != sponsor.videoId || !enabled()) emptyList()
        else sponsor.segments.value.map { it.startMs to it.endMs }
    }

    private fun tick() {
        sponsor.enabled = enabled() // toggle takes effect immediately
        if (player.statusNow != Status.Playing) return
        val m = player.currentMatch ?: return
        if (m.videoId != sponsor.videoId) return // offline file / resolving / not synced yet
        val pos = player.positionMs()
        val target = sponsor.check(pos) ?: return
        // seek clamps to duration - 250. Never seek backwards: landing before the segment would re-arm it and replay
        // the same stretch forever (the engine duration may still be the shorter catalog hint).
        if (minOf(target + 50, player.durationMs() - 250) <= pos) return
        val cat = sponsor.lastSkippedSegment?.category ?: ""
        player.seek(target + 50)
        onSkipped(PlayerNotice.SegmentSkipped(cat))
    }
}
