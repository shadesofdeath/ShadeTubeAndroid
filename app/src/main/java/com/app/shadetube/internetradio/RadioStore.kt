package com.app.shadetube.internetradio

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The radio.json store (port of radio::Store): favorites, recently played (newest first) and the stations of the list
 * that is playing (so a restored queue can still play after a restart), plus an in-memory registry of every station the
 * UI has shown (player lookups).
 *
 * Thread-safe (one lock; the C++ was UI-thread only). Saved with write-then-rename; a damaged file is kept as
 * radio.json.bad and never stops the app. Observe [favorites], [recent], [country] and [revision] (bumps on every
 * change of favorites / recent / country; not on the queue, which is not shown anywhere).
 */
class RadioStore {
    companion object {
        /** MAX_QUEUE: the most queue items the player's session keeps. */
        const val MAX_RECENT = 30
        const val MAX_QUEUE = 500
        const val MAX_KNOWN = 4000
    }

    private val lock = Any()
    private var file: File? = null
    private var dirty = false
    private var favs: List<Station> = emptyList()
    private var recents: List<Station> = emptyList()
    private var queueList: List<Station> = emptyList()
    private val known = LinkedHashMap<String, Station>() // insertion order (oldest first) for the MAX_KNOWN bound
    private var countryCode = ""

    private val _favorites = MutableStateFlow<List<Station>>(emptyList())
    private val _recent = MutableStateFlow<List<Station>>(emptyList())
    private val _country = MutableStateFlow("")
    private val _revision = MutableStateFlow(0L)

    val favorites: StateFlow<List<Station>> = _favorites.asStateFlow()
    val recent: StateFlow<List<Station>> = _recent.asStateFlow()

    /** The country of "Popular" on the Radio page ("" = not picked: the caller's default). */
    val country: StateFlow<String> = _country.asStateFlow()
    val revision: StateFlow<Long> = _revision.asStateFlow()

    val isDirty: Boolean get() = synchronized(lock) { dirty }
    val queue: List<Station> get() = synchronized(lock) { queueList }

    private fun publish() {
        _favorites.value = favs
        _recent.value = recents
        _country.value = countryCode
    }

    private fun changed() {
        dirty = true
        publish()
        _revision.value++
    }

    // ---- persistence ------------------------------------------------------------------------------------------------------

    private fun stationList(j: JsonElement?, key: String): List<Station> {
        val out = ArrayList<Station>()
        val seen = HashSet<String>()
        for (o in j[key].arr) {
            // one bad entry never loses the others
            val s = runCatching { RadioJson.stationFrom(o) }.getOrNull() ?: continue
            if (seen.add(s.uuid)) out.add(s)
        }
        return out
    }

    /** Replaces the state (missing file = empty). Blocking (disk). */
    fun load(f: File) = synchronized(lock) {
        file = f
        favs = emptyList()
        recents = emptyList()
        queueList = emptyList()
        known.clear()
        countryCode = ""
        dirty = false
        if (f.exists()) {
            try {
                val j = parseJsonOrNull(f.readText()) ?: throw RadioError("not JSON")
                if (!j.isObject) throw RadioError("not an object")
                favs = stationList(j, "favorites")
                recents = stationList(j, "recent").take(MAX_RECENT)
                queueList = stationList(j, "queue").take(MAX_QUEUE)
                val c = RadioJson.str(j, "country").uppercase(java.util.Locale.ROOT)
                countryCode = if (c.length == 2) c else ""
            } catch (e: Exception) {
                // Kept as radio.json.bad, so the next save (which writes only what could be read) loses nothing.
                val copied = runCatching { f.copyTo(File(f.path + ".bad"), overwrite = true) }.isSuccess
                Log.w("iradio", "radio.json is damaged (${e.message}): kept as radio.json.bad${if (copied) "" else " (copy failed)"}")
            }
        }
        for (list in listOf(queueList, recents, favs)) for (s in list) rememberOne(s)
        Log.i("iradio", "${favs.size} favorite(s), ${recents.size} recent station(s)")
        publish()
        _revision.value++
    }

    /** Writes now; false on failure. Blocking (disk). */
    fun save(): Boolean {
        val (target, text) = synchronized(lock) {
            val f = file ?: return false
            val j = buildJsonObject {
                put("version", 1)
                put("country", countryCode)
                put("favorites", JsonArray(favs.map { RadioJson.station(it) }))
                put("recent", JsonArray(recents.map { RadioJson.station(it) }))
                put("queue", JsonArray(queueList.map { RadioJson.station(it) }))
            }
            dirty = false
            f to j.dump()
        }
        // On the disk before the rename (Files.writeAtomic fsyncs): a power loss right after a save must not leave a
        // radio.json of zeros. A full disk shows up here too, before the good file is replaced.
        val ok = Files.writeAtomic(target, text)
        if (!ok) {
            Log.e("iradio", "failed to write radio.json")
            synchronized(lock) { dirty = true }
        }
        return ok
    }

    fun saveIfDirty() {
        if (isDirty) save()
    }

    // ---- favorites / recent / queue -------------------------------------------------------------------------------------

    fun isFavorite(uuid: String): Boolean = synchronized(lock) { favs.any { it.uuid == uuid } }

    /** New favorites go first. */
    fun setFavorite(s: Station, favorite: Boolean) = synchronized(lock) {
        if (s.uuid.isEmpty() || favorite == favs.any { it.uuid == s.uuid }) return@synchronized
        favs = if (favorite) listOf(s) + favs else favs.filter { it.uuid != s.uuid }
        rememberOne(s)
        changed()
    }

    fun toggleFavorite(s: Station) = setFavorite(s, !isFavorite(s.uuid))

    fun recordPlayed(s: Station) = synchronized(lock) {
        if (s.uuid.isEmpty()) return@synchronized
        if (recents.firstOrNull()?.uuid == s.uuid) return@synchronized // already the newest
        recents = (listOf(s) + recents.filter { it.uuid != s.uuid }).take(MAX_RECENT)
        rememberOne(s)
        changed()
    }

    fun removeRecent(uuid: String) = synchronized(lock) {
        val before = recents.size
        recents = recents.filter { it.uuid != uuid }
        if (recents.size != before) changed()
    }

    /**
     * The list that starts playing at [playing]: a longer one keeps the window around it that the player's session
     * keeps (100 back, [MAX_QUEUE] in all), so every station of a restored queue still resolves.
     */
    fun rememberQueue(stations: List<Station>, playing: Int = 0) = synchronized(lock) {
        val total = stations.size
        val first = if (total <= MAX_QUEUE) 0 else minOf(if (playing > 100) playing - 100 else 0, total - MAX_QUEUE)
        val last = minOf(total, first + MAX_QUEUE)
        queueList = stations.subList(first, last).toList()
        for (s in stations) rememberOne(s)
        dirty = true // not shown anywhere: no notification
    }

    // ---- registry ----------------------------------------------------------------------------------------------------

    private fun rememberOne(s: Station) {
        if (s.uuid.isEmpty()) return
        val existed = known.containsKey(s.uuid)
        known[s.uuid] = s
        if (existed) return
        // Bounded: the oldest entries go (favorites / recent / queue keep their own copies, see find()).
        while (known.size > MAX_KNOWN) {
            val it = known.entries.iterator()
            it.next()
            it.remove()
        }
    }

    fun remember(stations: List<Station>) = synchronized(lock) { for (s in stations) rememberOne(s) }
    fun remember(s: Station) = remember(listOf(s))

    /** The freshest copy of a station by uuid (favorites / recent / queue / anything remembered). */
    fun find(uuid: String): Station? = synchronized(lock) {
        known[uuid] ?: favs.firstOrNull { it.uuid == uuid } ?: recents.firstOrNull { it.uuid == uuid }
            ?: queueList.firstOrNull { it.uuid == uuid }
    }

    /** Fresher data from the server (byuuid): updates the stored copies in place. */
    fun refresh(fresh: List<Station>) = synchronized(lock) {
        var touched = false
        for (f in fresh) {
            fun update(list: List<Station>): List<Station> = list.map { s ->
                if (s.uuid == f.uuid && RadioJson.station(s) != RadioJson.station(f)) {
                    touched = true
                    f
                } else {
                    s
                }
            }
            favs = update(favs)
            recents = update(recents)
            queueList = update(queueList)
            rememberOne(f)
        }
        if (touched) changed()
    }

    fun setCountry(code: String) = synchronized(lock) {
        val c = code.uppercase(java.util.Locale.ROOT)
        if (c == countryCode) return@synchronized
        countryCode = c
        changed()
    }

    fun favoritesNow(): List<Station> = synchronized(lock) { favs }
    fun recentNow(): List<Station> = synchronized(lock) { recents }
    fun countryNow(): String = synchronized(lock) { countryCode }
}
