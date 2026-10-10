package com.app.shadetube.playback.features

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.Log
import com.app.shadetube.core.Settings
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.playback.Player
import com.app.shadetube.playback.PlayerNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Recommendations for a collection (AppGraph: stats.SmartMixRecommender — Spotify song radios, ListenBrainz similar
 * artists, local files; cached per collection for 30 minutes). [recommend] returns songs that fit [collection] and are
 * not in it, not hidden for [key], unblocked and playable, in a stable order for the key, marked recommended; empty =
 * nothing found / the fetch failed. Main-safe.
 */
interface Recommender {
    suspend fun recommend(key: String, collection: List<Track>): List<Track>

    /** "−": not recommended again this session. */
    fun hide(key: String, t: Track)
    fun isHidden(key: String, t: Track): Boolean
}

/** Adds a recommendation to its collection for real ("liked" or a playlist id); AppGraph implements it over the library. */
interface CollectionWriter {
    fun canAdd(key: String, t: Track): Boolean

    /** Returns how many were added (0 = already there / failed). */
    suspend fun add(key: String, t: Track): Int
}

/**
 * Smart shuffle and "Geliştir" (port of app/SmartShuffle, the player side).
 *
 * Smart shuffle (Settings.shuffle + Settings.smartShuffle; the shuffle button cycles off -> shuffle -> smart shuffle):
 * while it is on, the upcoming part of the queue gets a recommendation after every 3-4 songs, topped up as the queue
 * plays (a player hook plans the next [QueueMix.WINDOW] slots). Leaving smart shuffle drops the recommendations that
 * haven't played yet ([Player.dropRecommendations]).
 *
 * "Geliştir" (Settings.enhancedCollections, per playlist / Liked Songs): the collection page shows recommendations
 * interleaved in its track list ([enhancedList]), marked; "+" adds one to the collection for real ([add]), "−" hides it
 * for this session ([hide]). Playing the list plays them in order.
 */
class SmartShuffle(
    private val player: Player,
    private val scope: CoroutineScope,
    private val recommender: Recommender?,
    private val writer: CollectionWriter? = null,
    private val isBlocked: (Track) -> Boolean = { false },
    private val isStation: (String) -> Boolean = { false },
    private val settings: () -> Settings = { SettingsStore.current },
    private val updateSettings: ((Settings) -> Settings) -> Unit = { SettingsStore.update(it) },
) {
    enum class Mode { Off, Shuffle, Smart }

    private var syncPosted = false
    private var applying = false
    private var syncJob: Job? = null

    /** The queue as it was last planned (nothing changed: nothing to plan). */
    private data class Planned(val gen: Long = 0, val pos: Int = -1, val size: Int = 0)

    private var planned = Planned()

    /** The player hook: plan once the current event is over. Call once (AppGraph / PlaybackFeatures). */
    fun start() {
        scope.launch { player.changed.collect { scheduleSync() } }
    }

    private fun notASong(t: Track) = isStation(t.id) || isPodcastId(t.id)

    val mode: Mode
        get() {
            if (!player.shuffle) return Mode.Off
            return if (settings().smartShuffle) Mode.Smart else Mode.Shuffle
        }

    fun setMode(m: Mode) {
        val before = mode
        if (m == before) return
        if (before == Mode.Smart) player.dropRecommendations()
        updateSettings { it.copy(smartShuffle = m == Mode.Smart) }
        planned = Planned()
        player.setShuffle(m != Mode.Off)
        if (m == Mode.Smart) scheduleSync()
    }

    /** The shuffle button: off -> shuffle -> smart shuffle -> off. */
    fun cycleMode() {
        val next = when (mode) {
            Mode.Off -> Mode.Shuffle
            Mode.Shuffle -> Mode.Smart
            Mode.Smart -> Mode.Off
        }
        setMode(next)
        if (next == Mode.Smart) player.emitNotice(PlayerNotice.SmartShuffleOn)
    }

    // ---- "Geliştir" -----------------------------------------------------------------------------------------------------

    /** `key` = "liked" or the playlist id. */
    fun enhanced(key: String): Boolean = key.isNotEmpty() && key in settings().enhancedCollections

    fun setEnhanced(key: String, on: Boolean) {
        if (key.isEmpty() || enhanced(key) == on) return
        updateSettings { s -> s.copy(enhancedCollections = if (on) s.enhancedCollections + key else s.enhancedCollections - key) }
    }

    /** Recommendations for the collection [key] whose songs are [collection] (see [Recommender.recommend]). */
    suspend fun recommend(key: String, collection: List<Track>): List<Track> =
        recommender?.recommend(key, collection.filter { !it.recommended })?.filter { !isBlocked(it) && !notASong(it) } ?: emptyList()

    /** The collection page of an enhanced list: its songs with up to 30 recommendations interleaved. */
    suspend fun enhancedList(key: String, own: List<Track>): List<Track> {
        if (!enhanced(key)) return own
        val recs = recommend(key, own).take(30)
        return QueueMix.interleave(own, recs, QueueMix.hash(key))
    }

    fun hide(key: String, t: Track) = recommender?.hide(key, t)
    fun isHidden(key: String, t: Track) = recommender?.isHidden(key, t) == true

    /** The collection the queue plays ("liked" or a playlist id), "" for anything else (an album, search results...). */
    fun queueCollectionKey(): String = contextKey(player.playContext.uri)

    private fun contextKey(uri: String): String = when {
        uri == "liked" -> "liked"
        uri.startsWith("playlist:") -> uri.removePrefix("playlist:")
        else -> ""
    }

    /** Menus: whether [t] can be added to the collection [key] ("" = the queue's collection). */
    fun canAdd(key: String, t: Track): Boolean {
        val k = key.ifEmpty { queueCollectionKey() }
        if (k.isEmpty() || notASong(t)) return false
        return writer?.canAdd(k, t) == true
    }

    /** Adds [t] to the collection [key] ("" = the queue's collection); its queue items lose the badge. */
    fun add(key: String, t: Track) {
        val k = key.ifEmpty { queueCollectionKey() }
        if (!canAdd(k, t)) return
        val w = writer ?: return
        scope.launch {
            val n = w.add(k, t.copy(recommended = false))
            player.emitNotice(if (k == "liked") PlayerNotice.AddedToLiked else PlayerNotice.AddedToPlaylist(k, n))
        }
        player.clearRecommended(t.id)
        recommender?.hide(k, t) // in the collection now: never offered again (a Spotify list reloads a moment later)
    }

    /**
     * "Önerileri gösterme": Enhance off for [key] ("" = the queue's collection), and smart shuffle back to plain shuffle
     * (or the queue's recommendations dropped) when the queue plays that collection.
     */
    fun stop(key: String) {
        val queueKey = queueCollectionKey()
        val k = key.ifEmpty { queueKey }
        if (k.isNotEmpty() && enhanced(k)) setEnhanced(k, false)
        if (key.isEmpty() || k == queueKey) {
            if (mode == Mode.Smart) setMode(Mode.Shuffle) else player.dropRecommendations()
        }
        player.emitNotice(PlayerNotice.RecommendationsOff)
    }

    // ---- the queue top-up ---------------------------------------------------------------------------------------------

    private fun scheduleSync() {
        if (syncPosted || applying) return
        syncPosted = true
        scope.launch {
            syncPosted = false
            syncQueue()
        }
    }

    private fun syncQueue() {
        if (mode != Mode.Smart) return
        val cur = player.currentTrack ?: return
        if (notASong(cur)) return
        val gen = player.queueGeneration
        if (planned.gen == gen && planned.pos == player.currentOrderIndex && planned.size == player.order.size) return
        val own = player.items.filter { !it.recommended && !notASong(it) }
        if (own.size < 2) return // a lone song: nothing to fit around
        val key = contextKey(player.playContext.uri).ifEmpty { "queue:" + player.playContext.uri }
        syncJob?.cancel()
        syncJob = scope.launch {
            val recs = recommend(key, own)
            applyToQueue(gen, recs)
        }
    }

    private fun applyToQueue(gen: Long, recsIn: List<Track>) {
        if (player.queueGeneration != gen || mode != Mode.Smart || player.currentOrderIndex < 0) return
        val queued = QueueMix.Exclusions()
        for (t in player.items) queued.add(t)
        val recs = recsIn.filter { it !in queued }
        val isRec = player.order.map { player.items[it].recommended }
        val pos = player.currentOrderIndex
        val points = QueueMix.insertionPoints(isRec, pos, QueueMix.hash(player.playContext.uri, gen * 1000003L + pos))
        val n = minOf(points.size, recs.size)
        applying = true
        try {
            for (k in n - 1 downTo 0) player.insertAt(points[k], listOf(recs[k].copy(recommended = true))) // from the last
        } finally {
            applying = false
        }
        planned = Planned(gen, player.currentOrderIndex, player.order.size)
        if (n > 0) Log.d("smartshuffle", "$n recommendation(s) mixed into the queue")
    }
}
