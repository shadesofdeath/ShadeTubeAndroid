package com.app.shadetube.downloads

import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Log
import com.app.shadetube.core.arr
import com.app.shadetube.core.dump
import com.app.shadetube.core.get
import com.app.shadetube.core.int
import com.app.shadetube.core.isArray
import com.app.shadetube.core.isObject
import com.app.shadetube.core.isString
import com.app.shadetube.core.jsonArrayOf
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.long
import com.app.shadetube.core.obj
import com.app.shadetube.core.parseJsonOrNull
import com.app.shadetube.core.str
import com.app.shadetube.core.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream

/**
 * Download sync ("Çevrimdışı kullanılabilir"): the collections the user keeps downloaded, and the pure planning logic
 * behind it (port of app/SyncRules). The rules live in `sync.json` (their own file; downloads.json only marks a
 * download that sync queued with `"sy"`). Standalone (core + catalog only) so tests use it directly; the engine that
 * lists the collections and feeds the download queue is [DownloadSync].
 *
 * A rule is one collection: Liked Songs (Spotify's while logged in, else the local ones), a playlist (Spotify or
 * local) or an album (Spotify or MusicBrainz). Each sync of a rule lists the collection and remembers its track ids;
 * the planner then decides what to queue (not downloaded yet, not blocked, the retry policy for failed downloads, the
 * storage cap) and which sync downloads no rule wants any more. Downloads the user made themselves (or put in one of
 * their download folders) are never cancelled or deleted by sync.
 */
object SyncRules {
    enum class Kind { Liked, Playlist, Album }

    /** Why the last listing of a rule failed (the UI maps it to text). Ordinals are persisted ("err": 1..3). */
    enum class ListError { None, NotFound, RateLimited, Network }

    /** Rule id of the local Liked Songs (logged out). Spotify's Liked Songs use their collection URI. */
    const val LOCAL_LIKED_ID = "liked"
    const val SPOTIFY_LIKED_ID = "spotify:collection:tracks"

    /**
     * A song the user took out of a rule ("Senkrondan çıkar", or deleting its download): the rule's listings leave it
     * out, so it is not downloaded again, until the user lets it back in. Name and artists are kept for the list in
     * Downloads.
     */
    data class Excluded(
        val id: String,
        val name: String = "",
        val artists: String = "",
        /** Unix seconds. */
        val at: Long = 0,
    )

    data class Rule(
        /** [SPOTIFY_LIKED_ID] | [LOCAL_LIKED_ID] | playlist id | album id (spotify: URI / MBID). */
        val id: String,
        val kind: Kind = Kind.Playlist,
        /** Shown in the Downloads page (refreshed by each listing). */
        val name: String = "",
        val images: List<Image> = emptyList(),
        /** Unix seconds. */
        val addedAt: Long = 0,
        // Last listing.
        /** Unix seconds of the last successful listing (0 = never listed). */
        val lastSync: Long = 0,
        /** The collection's downloadable tracks at that listing, in order. */
        val trackIds: List<String> = emptyList(),
        /** The last listing attempt failed (trackIds are kept from before). */
        val error: ListError = ListError.None,
        /** Technical detail for the log / tooltip. */
        val errorDetail: String = "",
        /** Consecutive failed listings. */
        val failures: Int = 0,
        /** Backoff: no listing before this (unix seconds). */
        val retryAt: Long = 0,
        /** The collection changed here after lastSync (not persisted). */
        val changedAt: Long = 0,
        /** Songs the user took out (never in trackIds), oldest first. */
        val excluded: List<Excluded> = emptyList(),
    )

    /** Automatic download attempts of one track by sync (the retry policy for failures). */
    data class Attempt(val count: Int = 0, /** unix seconds of the last attempt */ val last: Long = 0)

    data class State(
        /** In the order the user added them (queue order). */
        val rules: List<Rule> = emptyList(),
        /** track id -> attempts (only tracks that failed / are pending). */
        val attempts: Map<String, Attempt> = emptyMap(),
    )

    /** Needs a Spotify session to list. */
    fun isSpotifyRule(r: Rule): Boolean = r.id.startsWith("spotify:")

    fun findRule(s: State, id: String): Rule? = s.rules.firstOrNull { it.id == id }

    // ---- Excluded songs ----------------------------------------------------------------------------------------------

    fun isExcluded(r: Rule, trackId: String): Boolean = r.excluded.any { it.id == trackId }

    /** Takes [t] out of the rule: listed in `excluded` (once) and dropped from its track ids. Null when it already was. */
    fun exclude(r: Rule, t: Track, now: Long): Rule? {
        if (t.id.isEmpty() || isExcluded(r, t.id)) return null
        return r.copy(excluded = r.excluded + Excluded(t.id, t.name, t.artistLine, now), trackIds = r.trackIds - t.id)
    }

    /** Lets a song back in (the next listing puts it in its place). Null when it wasn't excluded. */
    fun include(r: Rule, trackId: String): Rule? {
        if (!isExcluded(r, trackId)) return null
        return r.copy(excluded = r.excluded.filter { it.id != trackId })
    }

    /** A listing without the rule's excluded songs. */
    fun withoutExcluded(r: Rule, tracks: List<Track>): List<Track> =
        if (r.excluded.isEmpty()) tracks else tracks.filter { !isExcluded(r, it.id) }

    // ---- Persistence -------------------------------------------------------------------------------------------------

    private fun kindName(k: Kind) = when (k) {
        Kind.Liked -> "liked"
        Kind.Album -> "album"
        Kind.Playlist -> "playlist"
    }

    private fun kindFrom(s: String) = when (s) {
        "liked" -> Kind.Liked
        "album" -> Kind.Album
        else -> Kind.Playlist
    }

    fun toJson(s: State): JsonElement {
        val rules = s.rules.map { r ->
            val e = LinkedHashMap<String, JsonElement>()
            e["id"] = JsonPrimitive(r.id)
            e["k"] = JsonPrimitive(kindName(r.kind))
            e["n"] = JsonPrimitive(r.name)
            e["img"] = JsonArray(r.images.map { jsonOf("u" to it.url, "w" to it.width, "h" to it.height) })
            e["at"] = JsonPrimitive(r.addedAt)
            e["ls"] = JsonPrimitive(r.lastSync)
            e["tracks"] = JsonArray(r.trackIds.map { JsonPrimitive(it) })
            if (r.excluded.isNotEmpty()) e["ex"] = JsonArray(r.excluded.map { jsonArrayOf(it.id, it.name, it.artists, it.at) })
            if (r.error != ListError.None) {
                e["err"] = JsonPrimitive(r.error.ordinal)
                e["ed"] = JsonPrimitive(r.errorDetail)
                e["fails"] = JsonPrimitive(r.failures)
                e["retry"] = JsonPrimitive(r.retryAt)
            }
            JsonObject(e)
        }
        val attempts = JsonObject(s.attempts.mapValues { (_, a) -> jsonArrayOf(a.count, a.last) })
        return jsonOf("version" to 1, "rules" to JsonArray(rules), "attempts" to attempts)
    }

    /** A JSON integer (not a string, not a fraction): nlohmann's is_number_integer(). */
    private fun JsonElement?.integerOrNull(): Long? {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString) return null
        val text = p.content
        if (text.contains('.') || text.contains('e') || text.contains('E')) return null
        return p.longOrNull
    }

    /** Tolerant: unknown / broken entries are skipped. */
    fun stateFromJson(j: JsonElement?): State {
        if (!j.isObject) return State()
        val rules = ArrayList<Rule>()
        val rulesJson = j["rules"]
        if (rulesJson.isArray) {
            for (e in rulesJson.arr) {
                if (!e.isObject) continue
                val id = e.str("id")
                if (id.isEmpty() || rules.any { it.id == id }) continue
                val images = e["img"].arr.filter { it.isObject && it.str("u").isNotEmpty() }
                    .map { Image(it.str("u"), it.int("w"), it.int("h")) }
                val trackIds = e["tracks"].arr.mapNotNull { if (it.isString) it.stringOrNull else null }
                val err = e.int("err")
                val excluded = ArrayList<Excluded>()
                for (x in e["ex"].arr) { // absent before 0.6
                    val a = x as? JsonArray ?: continue
                    if (a.size != 4 || !a[0].isString || a[0].stringOrNull.isNullOrEmpty()) continue
                    val xid = a[0].stringOrNull!!
                    if (excluded.any { it.id == xid }) continue
                    excluded.add(
                        Excluded(
                            id = xid,
                            name = if (a[1].isString) a[1].stringOrNull.orEmpty() else "",
                            artists = if (a[2].isString) a[2].stringOrNull.orEmpty() else "",
                            at = a[3].integerOrNull() ?: 0,
                        ),
                    )
                }
                val r = Rule(
                    id = id,
                    kind = kindFrom(e.str("k", "playlist")),
                    name = e.str("n"),
                    images = images,
                    addedAt = e.long("at"),
                    lastSync = e.long("ls"),
                    trackIds = trackIds.filter { tid -> excluded.none { it.id == tid } },
                    error = if (err in 1..3) ListError.entries[err] else ListError.None,
                    errorDetail = e.str("ed"),
                    failures = maxOf(0, e.int("fails")),
                    retryAt = e.long("retry"),
                    excluded = excluded,
                )
                rules.add(r)
            }
        }
        val attempts = LinkedHashMap<String, Attempt>()
        j["attempts"].obj?.let { o ->
            for ((id, v) in o) {
                val a = v as? JsonArray ?: continue
                if (a.size != 2) continue
                val c = a[0].integerOrNull() ?: continue
                val l = a[1].integerOrNull() ?: continue
                attempts[id] = Attempt(maxOf(0, c.toInt()), l)
            }
        }
        return State(rules, attempts)
    }

    /** Missing file = empty state. A damaged file is kept as `sync.json.bad` and an empty state returned. */
    fun loadState(file: File): State {
        if (!file.exists()) return State()
        val text = runCatching { file.readText() }.getOrNull() ?: return State()
        val j = parseJsonOrNull(text)
        if (!j.isObject) {
            Log.w("sync", "sync.json is damaged; starting empty (kept as sync.json.bad)")
            runCatching { file.copyTo(File(file.path + ".bad"), overwrite = true) }
            return State()
        }
        return stateFromJson(j)
    }

    /** Write-then-rename. */
    fun saveState(s: State, file: File): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(toJson(s).dump().toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "rename failed" }
        }
        true
    }.getOrDefault(false)

    // ---- What the download queue knows about a track -----------------------------------------------------------------

    data class ItemInfo(
        val state: St = St.None,
        /** Queued / downloaded by sync (false = the user's own download). */
        val synced: Boolean = false,
        /** Done: the file size. */
        val sizeBytes: Long = 0,
        /** For size estimates of queued items. */
        val durationMs: Int = 0,
    ) {
        enum class St { None, Queued, Downloading, Done, Failed }
    }

    fun interface ItemLookup {
        fun lookup(trackId: String): ItemInfo
    }

    /**
     * Sync never queues these: no id, a radio station, a local file, a podcast episode, or a track the catalog marks as
     * unplayable.
     */
    fun downloadable(t: Track): Boolean {
        if (t.id.isEmpty() || !t.playable) return false
        return !t.id.startsWith("radio:") && !t.id.startsWith("local:") && !t.id.startsWith("podcast:")
    }

    /** Expected file size of a download (MP3 at [mp3Kbps], or the original ~128 kbps AAC for 0) incl. cover + tag. */
    fun estimateBytes(durationMs: Int, mp3Kbps: Int): Long {
        val ms: Long = if (durationMs > 0) durationMs.toLong() else 210_000 // unknown: a typical 3.5 min song
        val kbps: Long = if (mp3Kbps > 0) mp3Kbps.toLong() else 130 // original: ~128 kbps AAC + container
        return ms * kbps / 8 + 64 * 1024 // + cover art and the tag
    }

    /**
     * Retry policy: a failed sync download is queued again after 1 h, then 6 h; after [MAX_ATTEMPTS] it waits for the
     * user ("Hataları yeniden dene" clears the attempts).
     */
    const val MAX_ATTEMPTS = 3

    fun retryDue(a: Attempt, now: Long): Boolean {
        if (a.count <= 0) return true
        if (a.count >= MAX_ATTEMPTS) return false
        val wait = if (a.count == 1) 3600L else 6 * 3600L
        return now >= a.last + wait
    }

    data class Limits(
        /** 0 = no cap. */
        val capBytes: Long = 0,
        /** Sync downloads on disk + the estimate of the queued ones. */
        val usedBytes: Long = 0,
        val mp3Kbps: Int = 320,
    )

    data class Plan(
        /** New or retryable tracks, in collection order, each once. */
        val toQueue: List<Track> = emptyList(),
        /** Would be queued, but the storage cap is reached. */
        val skippedCap: Int = 0,
        /** Failed, retried later. */
        val waitingRetry: Int = 0,
        /** Failed [MAX_ATTEMPTS] times. */
        val gaveUp: Int = 0,
        /** Estimate of toQueue. */
        val queuedBytes: Long = 0,
    )

    /** [tracks] = the listed collection. Already downloaded / queued (by anyone) and blocked tracks are left out. */
    fun plan(
        tracks: List<Track>,
        lookup: ItemLookup?,
        blocked: ((Track) -> Boolean)?,
        attempts: Map<String, Attempt>,
        limits: Limits,
        now: Long,
    ): Plan {
        val toQueue = ArrayList<Track>()
        var skippedCap = 0
        var waitingRetry = 0
        var gaveUp = 0
        var queuedBytes = 0L
        val seen = HashSet<String>()
        var used = limits.usedBytes
        for (t in tracks) {
            if (!downloadable(t) || !seen.add(t.id)) continue
            val info = lookup?.lookup(t.id) ?: ItemInfo()
            if (info.state == ItemInfo.St.Done || info.state == ItemInfo.St.Queued || info.state == ItemInfo.St.Downloading) continue
            if (blocked != null && blocked(t)) continue
            if (info.state == ItemInfo.St.Failed) {
                val a = attempts[t.id] ?: Attempt(1, 0) // unknown: one attempt, long ago
                if (a.count >= MAX_ATTEMPTS) {
                    ++gaveUp
                    continue
                }
                if (!retryDue(a, now)) {
                    ++waitingRetry
                    continue
                }
            }
            val est = estimateBytes(t.durationMs, limits.mp3Kbps)
            if (limits.capBytes > 0 && used + est > limits.capBytes) {
                ++skippedCap
                continue
            }
            used += est
            queuedBytes += est
            toQueue.add(t)
        }
        return Plan(toQueue, skippedCap, waitingRetry, gaveUp, queuedBytes)
    }

    /** The ids a listing keeps for a rule: downloadable tracks, each once, in order. */
    fun ruleTrackIds(tracks: List<Track>): List<String> {
        val seen = HashSet<String>()
        return tracks.filter { downloadable(it) && seen.add(it.id) }.map { it.id }
    }

    /** Every track id some rule wants (the union of their last listings). */
    fun wantedIds(rules: List<Rule>): Set<String> = rules.flatMapTo(HashSet()) { it.trackIds }

    /**
     * Sync downloads no rule wants any more. [cancel]: queued / downloading / failed ones (just dropped from the
     * queue); [remove]: downloaded files, only when `deleteFiles` (Ayarlar: "Listeden çıkan şarkıları sil"). The user's
     * own downloads are never listed.
     */
    data class Drop(val cancel: List<String> = emptyList(), val remove: List<String> = emptyList())

    fun dropped(items: List<Pair<String, ItemInfo>>, wanted: Set<String>, deleteFiles: Boolean): Drop {
        val cancel = ArrayList<String>()
        val remove = ArrayList<String>()
        for ((id, info) in items) {
            if (!info.synced || id in wanted) continue
            if (info.state == ItemInfo.St.Done) {
                if (deleteFiles) remove.add(id)
            } else if (info.state != ItemInfo.St.None) {
                cancel.add(id)
            }
        }
        return Drop(cancel, remove)
    }

    /**
     * A listing that suddenly came back empty after holding tracks is not trusted for deleting files (an API hiccup
     * must never wipe the user's offline music); the next listing decides.
     */
    fun suspiciousShrink(before: Int, after: Int): Boolean = after == 0 && before > 0

    // ---- Progress of a rule ------------------------------------------------------------------------------------------

    data class Progress(
        /** Tracks in the last listing. */
        val total: Int = 0,
        /** Downloaded (by sync or by the user). */
        val done: Int = 0,
        /** Queued or downloading. */
        val active: Int = 0,
        /** Failed downloads. */
        val failed: Int = 0,
    )

    fun progress(r: Rule, lookup: ItemLookup?): Progress {
        if (lookup == null) return Progress(total = r.trackIds.size)
        var done = 0
        var active = 0
        var failed = 0
        for (id in r.trackIds) {
            when (lookup.lookup(id).state) {
                ItemInfo.St.Done -> ++done
                ItemInfo.St.Queued, ItemInfo.St.Downloading -> ++active
                ItemInfo.St.Failed -> ++failed
                ItemInfo.St.None -> Unit
            }
        }
        return Progress(r.trackIds.size, done, active, failed)
    }

    // ---- Scheduling --------------------------------------------------------------------------------------------------

    /** First pass after the app started. */
    const val STARTUP_DELAY_SEC = 45L
    /** A like / playlist edit is synced this long after it happened. */
    const val CHANGE_DEBOUNCE_SEC = 5L
    /** Tracks still missing: list again after this. */
    const val PENDING_RELIST_SEC = 30 * 60L

    /**
     * How often a rule is listed again with no change seen: playlists 45 min, Spotify Liked Songs 6 h (likes made here
     * are seen at once through the library snapshot), albums 24 h, local collections 45 min (free: no network).
     */
    fun relistInterval(r: Rule): Long {
        if (!isSpotifyRule(r)) return if (r.kind == Kind.Album) 24 * 3600L else 45 * 60L
        if (r.kind == Kind.Liked) return 6 * 3600L
        if (r.kind == Kind.Album) return 24 * 3600L
        return 45 * 60L
    }

    /**
     * Least time between two listings when changes keep coming (Spotify Liked Songs: 5 min, as a listing pages them
     * all; other Spotify lists: 1 min; local: none).
     */
    fun minRelistGap(r: Rule): Long {
        if (!isSpotifyRule(r)) return 0
        return if (r.kind == Kind.Liked) 5 * 60L else 60L
    }

    /** Whether a rule should be listed now. [pending] = some of its tracks are neither downloaded nor queued and could be. */
    fun ruleDue(r: Rule, now: Long, pending: Boolean): Boolean {
        if (now < r.retryAt) return false
        if (r.lastSync == 0L) return true
        val since = now - r.lastSync
        if (since < 0) return true // the clock went back: list again rather than wait for ever
        if (r.changedAt > r.lastSync && now - r.changedAt >= CHANGE_DEBOUNCE_SEC && since >= minRelistGap(r)) return true
        if (pending && since >= PENDING_RELIST_SEC) return true
        return since >= relistInterval(r)
    }

    private val BACKOFF_STEPS = longArrayOf(60, 5 * 60, 15 * 60, 60 * 60)

    /**
     * Backoff after [failures] consecutive failed listings: 1, 5, 15, 60 min (capped); rate limited (HTTP 429): at
     * least 10 min.
     */
    fun backoffSeconds(failures: Int, rateLimited: Boolean): Long {
        val s = BACKOFF_STEPS[failures.coerceIn(1, 4) - 1]
        return if (rateLimited) maxOf(s, 10 * 60L) else s
    }
}
