package com.app.shadetube.podcasts

import com.app.shadetube.core.Files
import com.app.shadetube.core.Log
import com.app.shadetube.core.arr
import com.app.shadetube.core.dump
import com.app.shadetube.core.get
import com.app.shadetube.core.isObject
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The podcasts.json store (port of podcast::Store): subscriptions (newest episode date seen, the new-episode count
 * behind the badge, whether it was added by URL), per-episode state (position, measured length, played, downloaded
 * file) for at most [MAX_STATES] episodes (downloads are never dropped) and the episodes of the playing queue, so a
 * restored session resumes them.
 *
 * Thread-safe (one lock; the C++ was UI-thread only). Saved with write-then-rename; a damaged file is kept as
 * podcasts.json.bad and never stops the app. Observe [revision] (bumps on every change lists show, not on positions),
 * [subscriptions] and [newEpisodeCount].
 */
class PodcastStore {
    companion object {
        const val MAX_STATES = 4000
        const val MAX_QUEUE = 500
        const val MAX_KNOWN = 6000
        private const val STORED_DESCRIPTION = 1500
    }

    private class Entry(var episode: Episode, var state: EpisodeState)

    private val lock = Any()
    private var file: File? = null
    private var dirty = false
    private val subs = ArrayList<Subscription>()
    private val states = HashMap<String, Entry>()
    private var queue: List<Episode> = emptyList()
    private val known = LinkedHashMap<String, Episode>()

    private val _revision = MutableStateFlow(0L)
    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    private val _newEpisodeCount = MutableStateFlow(0)

    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Subscriptions, newest first. */
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    /** Episodes of the subscriptions published since the user last looked (the sidebar / nav badge). */
    val newEpisodeCount: StateFlow<Int> = _newEpisodeCount.asStateFlow()

    val isDirty: Boolean get() = synchronized(lock) { dirty }

    // ---- persistence ----------------------------------------------------------------------------------------------------

    /** Replaces the state (missing file = empty). Blocking (disk). */
    fun load(f: File) {
        synchronized(lock) {
            file = f
            subs.clear()
            states.clear()
            queue = emptyList()
            known.clear()
            dirty = false
            if (f.exists()) {
                try {
                    val j = parseJsonOrNull(f.readText()) ?: throw PodcastError("not JSON")
                    if (!j.isObject) throw PodcastError("not an object")
                    for (s in j["subscriptions"].arr) {
                        val sub = runCatching { subscriptionFrom(s) }.getOrNull() ?: continue
                        if (subs.none { it.feedUrl == sub.feedUrl }) subs.add(sub)
                    }
                    for (e in j["episodes"].arr) {
                        val ep = runCatching { PodcastJson.episodeFrom(e) }.getOrNull() ?: continue
                        states[ep.id] = Entry(
                            ep,
                            EpisodeState(
                                positionMs = maxOf(0, jnum64(e, "pos")),
                                durationMs = maxOf(0, jnum64(e, "measured")),
                                played = jflag(e, "played"),
                                file = jstr(e, "file"),
                                fileBytes = maxOf(0, jnum64(e, "bytes")),
                                updatedAt = jnum64(e, "updated"),
                                downloadedAt = jnum64(e, "downloaded"),
                            ),
                        )
                    }
                    val q = ArrayList<Episode>()
                    for (e in j["queue"].arr) {
                        runCatching { PodcastJson.episodeFrom(e) }.getOrNull()?.let { q.add(it) }
                        if (q.size >= MAX_QUEUE) break
                    }
                    queue = q
                } catch (e: Exception) {
                    // Kept as podcasts.json.bad, so the next save (which writes only what could be read) loses nothing.
                    val copied = runCatching { f.copyTo(File(f.path + ".bad"), overwrite = true) }.isSuccess
                    Log.w("podcasts", "podcasts.json is damaged (${e.message}): kept as podcasts.json.bad${if (copied) "" else " (copy failed)"}")
                }
            }
            for (e in queue) rememberOne(e)
            Log.i("podcasts", "${subs.size} subscription(s), ${states.size} episode state(s)")
            publish()
            _revision.value++
        }
    }

    /** Writes now; false on failure. Blocking (disk). */
    fun save(): Boolean {
        val (target, text) = synchronized(lock) {
            val f = file ?: return false
            val eps = states.values.map { en ->
                val e = storedEpisode(en.episode)
                val st = en.state
                JsonObject(
                    e + buildJsonObject {
                        if (st.positionMs > 0) put("pos", st.positionMs)
                        if (st.durationMs > 0) put("measured", st.durationMs)
                        if (st.played) put("played", true)
                        if (st.file.isNotEmpty()) {
                            put("file", st.file)
                            put("bytes", st.fileBytes)
                            put("downloaded", st.downloadedAt)
                        }
                        put("updated", st.updatedAt)
                    },
                )
            }
            val j = buildJsonObject {
                put("version", 1)
                put("subscriptions", JsonArray(subs.map { subscriptionJson(it) }))
                put("episodes", JsonArray(eps))
                put("queue", JsonArray(queue.map { storedEpisode(it) }))
            }
            dirty = false
            f to j.dump()
        }
        // On the disk before the rename (Files.writeAtomic fsyncs): a power loss right after a save must not leave a
        // file of zeros.
        val ok = Files.writeAtomic(target, text)
        if (!ok) {
            Log.e("podcasts", "failed to write podcasts.json")
            synchronized(lock) { dirty = true }
        }
        return ok
    }

    fun saveIfDirty() {
        if (isDirty) save()
    }

    private fun storedEpisode(e: Episode): JsonObject =
        PodcastJson.episode(e.copy(description = capUtf8(e.description, STORED_DESCRIPTION)), withHtml = false)

    private fun subscriptionJson(s: Subscription): JsonObject = buildJsonObject {
        put("feed", s.feedUrl)
        put("title", s.title)
        put("author", s.author)
        put("img", s.image)
        put("apple", s.appleId)
        put("since", s.subscribedAt)
        put("refreshed", s.lastRefresh)
        put("seen", s.seenUntil)
        put("new", s.newEpisodes)
        put("manual", s.manual)
    }

    private fun subscriptionFrom(j: kotlinx.serialization.json.JsonElement?): Subscription {
        if (!j.isObject) throw PodcastError("subscription: not an object")
        val s = Subscription(
            feedUrl = jstr(j, "feed"),
            title = jstr(j, "title"),
            author = jstr(j, "author"),
            image = jstr(j, "img"),
            appleId = jstr(j, "apple"),
            subscribedAt = jnum64(j, "since"),
            lastRefresh = jnum64(j, "refreshed"),
            seenUntil = jnum64(j, "seen"),
            newEpisodes = jnum64(j, "new").coerceIn(0, 100000).toInt(),
            manual = jflag(j, "manual"),
        )
        if (!PodcastText.isHttpUrl(s.feedUrl)) throw PodcastError("subscription: no feed URL")
        return if (PodcastText.isHttpUrl(s.image)) s else s.copy(image = "")
    }

    // ---- change tracking ------------------------------------------------------------------------------------------------

    private fun publish() {
        _subscriptions.value = subs.toList()
        _newEpisodeCount.value = subs.sumOf { it.newEpisodes }
    }

    /** A change lists show: bumps the revision (and marks the store dirty). */
    private fun changed() {
        dirty = true
        publish()
        _revision.value++
    }

    // ---- subscriptions ---------------------------------------------------------------------------------------------------

    fun subscriptionsNow(): List<Subscription> = synchronized(lock) { subs.toList() }

    fun subscription(feedUrl: String): Subscription? = synchronized(lock) { subs.firstOrNull { it.feedUrl == feedUrl } }

    fun isSubscribed(feedUrl: String): Boolean = subscription(feedUrl) != null

    /** Subscribes to [show]; the episodes out now are not "new". */
    fun subscribe(show: Show, manual: Boolean, now: Long) = synchronized(lock) {
        if (show.feedUrl.isEmpty() || subs.any { it.feedUrl == show.feedUrl }) return@synchronized
        subs.add(
            0,
            Subscription(
                feedUrl = show.feedUrl,
                title = show.title,
                author = show.author,
                image = show.image,
                appleId = show.appleId,
                subscribedAt = now,
                lastRefresh = now,
                seenUntil = maxOf(newest(show), 1L),
                manual = manual,
            ),
        )
        remember(show.episodes)
        changed()
    }

    fun unsubscribe(feedUrl: String) = synchronized(lock) {
        if (subs.removeAll { it.feedUrl == feedUrl }) changed()
    }

    /**
     * A fresh copy of a subscribed show's feed: its title / art and the count of episodes after seenUntil. Returns how
     * many more episodes are new than before (for the "new episodes" notice).
     */
    fun refreshed(show: Show, now: Long): Int = synchronized(lock) {
        val i = subs.indexOfFirst { it.feedUrl == show.feedUrl }
        if (i < 0) return@synchronized 0
        var s = subs[i]
        var visible = false
        if (show.title.isNotEmpty() && show.title != s.title) {
            s = s.copy(title = show.title)
            visible = true
        }
        if (show.author.isNotEmpty() && show.author != s.author) {
            s = s.copy(author = show.author)
            visible = true
        }
        if (show.image.isNotEmpty() && show.image != s.image) {
            s = s.copy(image = show.image)
            visible = true
        }
        val count = show.episodes.count { it.published > s.seenUntil && !isPlayed(it.id) }
        val added = maxOf(0, count - s.newEpisodes)
        if (count != s.newEpisodes) visible = true
        subs[i] = s.copy(lastRefresh = now, newEpisodes = count)
        dirty = true
        if (visible) changed() else publish()
        added
    }

    /** The show page was seen: its episodes up to the newest one are no longer new. */
    fun markSeen(feedUrl: String, newestPublished: Long) = synchronized(lock) {
        val i = subs.indexOfFirst { it.feedUrl == feedUrl }
        if (i < 0) return@synchronized
        val had = subs[i].newEpisodes > 0
        subs[i] = subs[i].copy(seenUntil = maxOf(subs[i].seenUntil, newestPublished), newEpisodes = 0)
        dirty = true
        if (had) changed() else publish()
    }

    fun newEpisodeCountNow(): Int = synchronized(lock) { subs.sumOf { it.newEpisodes } }

    // ---- registry --------------------------------------------------------------------------------------------------------

    private fun rememberOne(e: Episode) {
        val id = e.id
        val existed = known.containsKey(id)
        known[id] = e // an update keeps the insertion order
        if (existed) return
        while (known.size > MAX_KNOWN) {
            val it = known.entries.iterator()
            it.next()
            it.remove()
        }
    }

    /** The freshest copy of every episode the UI listed or the player has (queue); lookups by episode id. */
    fun remember(episodes: List<Episode>) = synchronized(lock) {
        for (e in episodes) rememberOne(e)
        // Keep the metadata of stored states fresh (a renamed episode, new art).
        for (e in episodes) states[e.id]?.let { it.episode = e }
    }

    fun remember(e: Episode) = remember(listOf(e))

    fun find(id: String): Episode? = synchronized(lock) {
        known[id] ?: states[id]?.episode ?: queue.firstOrNull { it.id == id }
    }

    /**
     * The list that starts playing at [playing] (kept around it like the radio's queue window, so a restored queue
     * still resolves after a restart).
     */
    fun rememberQueue(list: List<Episode>, playing: Int = 0) = synchronized(lock) {
        var first = 0
        var last = list.size
        if (list.size > MAX_QUEUE) {
            first = if (playing > 100) minOf(playing - 100, list.size - MAX_QUEUE) else 0
            last = first + MAX_QUEUE
        }
        queue = list.subList(first, last).toList()
        remember(list)
        dirty = true
    }

    // ---- progress --------------------------------------------------------------------------------------------------------

    private fun entry(e: Episode): Entry {
        val id = e.id
        val found = states[id]
        if (found != null) {
            found.episode = e
            return found
        }
        val en = Entry(e, EpisodeState())
        states[id] = en
        prune(id)
        return en
    }

    private fun prune(keep: String) {
        if (states.size <= MAX_STATES) return
        // Oldest first, downloads never (their file is recorded here), nor the entry being added.
        val order = states.entries
            .filter { it.value.state.file.isEmpty() && it.key != keep }
            .map { it.value.state.updatedAt to it.key }
            .sortedWith(compareBy<Pair<Long, String>> { it.first }.thenBy { it.second })
        for ((_, id) in order) {
            if (states.size <= MAX_STATES) break
            states.remove(id)
        }
    }

    fun state(id: String): EpisodeState? = synchronized(lock) { states[id]?.state }

    /** Playback reached [positionMs]: past [playedAt] it becomes played (and continues from the start next time). */
    fun setPosition(e: Episode, positionMs: Long, durationMs: Long, now: Long) = synchronized(lock) {
        if (states[e.id] == null && positionMs <= 0) return@synchronized // nothing to remember yet
        val en = entry(e)
        var st = en.state
        if (durationMs > 0) st = st.copy(durationMs = durationMs)
        val dur = if (st.durationMs > 0) st.durationMs else e.durationMs
        val wasStarted = st.positionMs > 0
        val wasPlayed = st.played
        if (playedAt(positionMs, dur)) {
            st = st.copy(played = true, positionMs = 0)
        } else if (positionMs > 0) {
            st = st.copy(positionMs = positionMs, played = false) // listening again
        }
        en.state = st.copy(updatedAt = now)
        dirty = true
        if (wasPlayed != en.state.played || wasStarted != (en.state.positionMs > 0)) changed()
    }

    /** Clears the position either way. */
    fun setPlayed(e: Episode, played: Boolean, now: Long) = synchronized(lock) {
        val en = entry(e)
        en.state = en.state.copy(played = played, positionMs = 0, updatedAt = now)
        if (played) { // no longer "new" either
            for (k in subs.indices) {
                val s = subs[k]
                if (s.feedUrl == e.feedUrl && e.published > s.seenUntil && s.newEpisodes > 0) subs[k] = s.copy(newEpisodes = s.newEpisodes - 1)
            }
        }
        changed()
    }

    /** Where to continue (0 = from the start): a few seconds back, the sentence it stopped in. */
    fun resumePosition(id: String): Long = synchronized(lock) {
        val s = states[id]?.state ?: return@synchronized 0
        if (s.played || s.positionMs < 10_000) 0 else s.positionMs - 3_000
    }

    fun isPlayed(id: String): Boolean = synchronized(lock) { states[id]?.state?.played == true }

    /** Started and not finished, most recently listened first. */
    fun inProgress(max: Int): List<Episode> = synchronized(lock) {
        states.values.filter { !it.state.played && it.state.positionMs > 0 }
            .sortedByDescending { it.state.updatedAt }
            .take(max)
            .map { it.episode }
    }

    // ---- downloads ---------------------------------------------------------------------------------------------------------

    fun setDownloaded(e: Episode, file: String, bytes: Long, now: Long) = synchronized(lock) {
        val en = entry(e)
        en.state = en.state.copy(file = file, fileBytes = bytes, downloadedAt = now, updatedAt = now)
        changed()
    }

    fun clearDownload(id: String) = synchronized(lock) {
        val en = states[id] ?: return@synchronized
        if (en.state.file.isEmpty()) return@synchronized
        en.state = en.state.copy(file = "", fileBytes = 0, downloadedAt = 0)
        changed()
    }

    /** "" = none. */
    fun downloadedFile(id: String): String = synchronized(lock) { states[id]?.state?.file ?: "" }

    /** Newest download first. */
    fun downloaded(): List<Episode> = synchronized(lock) {
        states.values.filter { it.state.file.isNotEmpty() }.sortedByDescending { it.state.downloadedAt }.map { it.episode }
    }

    private fun newest(show: Show): Long = show.episodes.maxOfOrNull { it.published } ?: 0L

    /** For JSON round-trip tests and debugging. */
    internal fun queueNow(): List<Episode> = synchronized(lock) { queue }
}
