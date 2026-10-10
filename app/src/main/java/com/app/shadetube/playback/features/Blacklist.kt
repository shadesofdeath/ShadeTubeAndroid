package com.app.shadetube.playback.features

import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Files
import com.app.shadetube.core.Log
import com.app.shadetube.core.Text
import com.app.shadetube.core.get
import com.app.shadetube.core.isObject
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.math.abs

/**
 * Kara liste (port of app/Blacklist): tracks and artists the user never wants to hear. Stored in blacklist.json
 * (loaded on first use, rewritten on every change; a damaged file is kept as blacklist.json.bad and never stops the
 * app). Main thread (or any single thread at a time; calls are synchronized).
 *
 * Effects (wired by [PlaybackFeatures]): the player never picks a blocked track by itself — auto-advance, next /
 * previous, the start of a header-played or shuffled list, the repeat wrap and the prefetch pass over it — while a
 * track the user picks directly (a tap on its row, a tap in the queue) still plays. Song radio and endless playback
 * drop blocked tracks; track lists dim them with an "ENGELLİ" badge.
 *
 * Matching rules:
 * - a track: its id; or another release of the same recording — the same title + first artist (case / accent folded)
 *   AND, when both durations are known, durations within 3 s (Spotify single vs album); when a duration is unknown,
 *   only across catalogs (a local file / MusicBrainz copy of a blocked Spotify song). So a different song that shares
 *   a generic title ("Intro", "I. Allegro") with a blocked one is not blocked;
 * - an artist: its id, or the same name when the ids come from different catalogs or the credit has no id at all (two
 *   Spotify artists who share a name stay apart).
 */
class Blacklist(private val fileProvider: () -> File, private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) {
    data class Entry(
        val id: String = "",
        /** Track title / artist name. */
        val name: String = "",
        /** Tracks: the artist line ("A, B"); artists: "". */
        val artists: String = "",
        /** Tracks: the first artist (half of the title + artist match key). */
        val artist0: String = "",
        /** Unix seconds. */
        val addedAt: Long = 0,
        /** Tracks: 0 = unknown. */
        val durationMs: Int = 0,
    )

    private class KeyEntry(val durationMs: Int, val catalog: String)

    companion object {
        /** Title + first artist of another release counts as the same recording within this duration difference. */
        const val SAME_RECORDING_MS = 3000

        private fun fold(s: String): String = Text.fold(s).trim { it == ' ' || it == '\t' }

        /** "spotify", "mb" (a bare MBID), the prefix of other ids ("local", "import", "preview"...), "" for no id. */
        fun catalogOf(id: String): String {
            if (id.isEmpty()) return ""
            if (id.startsWith("spotify:")) return "spotify"
            val colon = id.indexOf(':')
            return if (colon < 0) "mb" else id.substring(0, colon)
        }

        private fun trackKey(name: String, artist0: String): String {
            if (name.isEmpty() || artist0.isEmpty()) return ""
            val n = fold(name)
            val a = fold(artist0)
            return if (n.isEmpty() || a.isEmpty()) "" else n + '\u001f' + a
        }

        private fun trackKey(t: Track) = trackKey(t.name, t.artists.firstOrNull()?.name ?: "")

        /**
         * The title + first artist fallback is for another release of the same recording, not for a different song
         * that shares a generic title: both durations known -> they must agree within [SAME_RECORDING_MS]; a duration
         * unknown (local files, MusicBrainz, entries without one) -> only across catalogs.
         */
        private fun sameRecording(durationA: Int, catalogA: String, durationB: Int, catalogB: String): Boolean {
            if (durationA > 0 && durationB > 0) return abs(durationA - durationB) <= SAME_RECORDING_MS
            return catalogA != catalogB
        }
    }

    private val lock = Any()
    private var loaded = false
    private val tracks = ArrayList<Entry>()
    private val artists = ArrayList<Entry>()
    private val trackIds = HashSet<String>()
    private val artistIds = HashSet<String>()
    private val trackKeys = HashMap<String, MutableList<KeyEntry>>()
    private val artistNames = HashMap<String, MutableList<String>>()
    private val listeners = ArrayList<() -> Unit>()
    private val _revision = MutableStateFlow(1L)

    /** Bumps on every change (views that cache the blocked state compare it). */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun reindex() {
        trackIds.clear()
        trackKeys.clear()
        artistIds.clear()
        artistNames.clear()
        for (e in tracks) {
            if (e.id.isNotEmpty()) trackIds.add(e.id)
            val k = trackKey(e.name, e.artist0)
            if (k.isNotEmpty()) trackKeys.getOrPut(k) { ArrayList() }.add(KeyEntry(e.durationMs, catalogOf(e.id)))
        }
        for (e in artists) {
            if (e.id.isNotEmpty()) artistIds.add(e.id)
            if (e.name.isNotEmpty()) artistNames.getOrPut(fold(e.name)) { ArrayList() }.add(catalogOf(e.id))
        }
    }

    // Type-checked readers: a hand-edited field of the wrong type reads as empty / 0 instead of throwing.
    private fun str(o: JsonElement?, key: String): String {
        val v = o[key] as? JsonPrimitive ?: return ""
        return if (v.isString) v.content else ""
    }

    private fun num(o: JsonElement?, key: String): Long {
        val v = o[key] as? JsonPrimitive ?: return 0
        if (v.isString || v.booleanOrNull != null) return 0
        return v.longOrNull ?: v.doubleOrNull?.toLong() ?: 0
    }

    /** Keeps a damaged file as blacklist.json.bad, so the next save (which writes only what could be read) loses nothing. */
    private fun keepAside(file: File) {
        val ok = runCatching { file.copyTo(File(file.path + ".bad"), overwrite = true) }.isSuccess
        Log.w("blacklist", "blacklist.json is damaged: kept as blacklist.json.bad${if (ok) "" else " (copy failed)"}")
    }

    private fun readFile() {
        tracks.clear()
        artists.clear()
        var damaged = false
        val file = fileProvider()
        try {
            if (file.exists()) {
                val j = parseJsonOrNull(file.readText())
                if (!j.isObject) {
                    damaged = true
                } else {
                    fun readList(key: String, isTracks: Boolean) {
                        val list = j[key] ?: return
                        if (list !is JsonArray) {
                            damaged = true
                            return
                        }
                        for (o in list) {
                            if (o !is JsonObject) {
                                damaged = true
                                continue
                            }
                            val e = Entry(
                                id = str(o, "id"),
                                name = str(o, "n"),
                                artists = if (isTracks) str(o, "a") else "",
                                artist0 = if (isTracks) str(o, "a0") else "",
                                addedAt = num(o, "t"),
                                durationMs = if (isTracks) num(o, "d").toInt() else 0,
                            )
                            if (e.id.isEmpty() && e.name.isEmpty()) {
                                damaged = true
                                continue
                            }
                            (if (isTracks) tracks else artists).add(e)
                        }
                    }
                    readList("tracks", true)
                    readList("artists", false)
                }
            }
        } catch (e: Exception) { // never let this file stop the app from starting
            Log.w("blacklist", "blacklist.json unreadable: ${e.message}")
            damaged = true
        }
        if (damaged) keepAside(file)
        reindex()
        loaded = true
        if (tracks.isNotEmpty() || artists.isNotEmpty()) Log.i("blacklist", "loaded: ${tracks.size} tracks, ${artists.size} artists")
    }

    private fun save() {
        val t = JsonArray(tracks.map { jsonOf("id" to it.id, "n" to it.name, "a" to it.artists, "a0" to it.artist0, "t" to it.addedAt, "d" to it.durationMs) })
        val a = JsonArray(artists.map { jsonOf("id" to it.id, "n" to it.name, "t" to it.addedAt) })
        // Write, check, then an atomic replace: a failed write never replaces a good file.
        if (!Files.writeJson(fileProvider(), jsonOf("version" to 1, "tracks" to t, "artists" to a))) {
            Log.e("blacklist", "failed to write blacklist.json")
        }
    }

    private fun changed() {
        _revision.value = _revision.value + 1
        reindex()
        save()
        val ls = ArrayList(listeners) // a listener may register another one
        for (fn in ls) fn()
    }

    private fun ensureLoaded() {
        if (!loaded) readFile()
    }

    private fun artistEntryMatches(e: Entry, a: ArtistRef): Boolean {
        if (a.id.isNotEmpty() && e.id == a.id) return true
        if (a.name.isEmpty() || e.name.isEmpty() || fold(e.name) != fold(a.name)) return false
        return a.id.isEmpty() || e.id.isEmpty() || catalogOf(e.id) != catalogOf(a.id)
    }

    private fun trackEntryMatches(e: Entry, t: Track): Boolean {
        if (t.id.isNotEmpty() && e.id == t.id) return true
        val k = trackKey(t)
        return k.isNotEmpty() && k == trackKey(e.name, e.artist0) && sameRecording(e.durationMs, catalogOf(e.id), t.durationMs, catalogOf(t.id))
    }

    /** The track credit itself (rules above), without looking at the artist half of the list. */
    fun trackMatches(t: Track): Boolean = synchronized(lock) {
        ensureLoaded()
        if (tracks.isEmpty()) return false
        if (t.id.isNotEmpty() && t.id in trackIds) return true
        val k = trackKey(t)
        if (k.isEmpty()) return false
        val list = trackKeys[k] ?: return false
        val mine = catalogOf(t.id)
        return list.any { sameRecording(it.durationMs, it.catalog, t.durationMs, mine) }
    }

    fun artistMatches(a: ArtistRef): Boolean = synchronized(lock) {
        ensureLoaded()
        if (artists.isEmpty()) return false
        if (a.id.isNotEmpty() && a.id in artistIds) return true
        if (a.name.isEmpty()) return false
        val list = artistNames[fold(a.name)] ?: return false
        if (a.id.isEmpty()) return true
        val mine = catalogOf(a.id)
        return list.any { it.isEmpty() || it != mine }
    }

    /** The track itself or any of its artists. */
    fun isBlocked(t: Track): Boolean {
        if (isEmpty()) return false
        if (trackMatches(t)) return true
        return t.artists.any { artistMatches(it) }
    }

    /** By id only. */
    fun isTrackBlocked(trackId: String): Boolean = synchronized(lock) {
        ensureLoaded()
        trackId.isNotEmpty() && trackId in trackIds
    }

    fun isArtistBlocked(artistId: String): Boolean = synchronized(lock) {
        ensureLoaded()
        artistId.isNotEmpty() && artistId in artistIds
    }

    fun setTrackBlocked(t: Track, blocked: Boolean) {
        synchronized(lock) {
            ensureLoaded()
            if (blocked) {
                if ((t.id.isEmpty() && t.name.isEmpty()) || trackMatches(t)) return
                tracks.add(0, Entry(t.id, t.name, t.artistLine, t.artists.firstOrNull()?.name ?: "", nowSeconds(), t.durationMs))
                Log.i("blacklist", "track blocked: ${t.name}")
            } else {
                // Every entry that blocks this track (its id, or another release of the same recording).
                if (!tracks.removeAll { trackEntryMatches(it, t) }) return
                Log.i("blacklist", "track unblocked: ${t.name}")
            }
        }
        changed()
    }

    fun setArtistBlocked(artistId: String, artistName: String, blocked: Boolean) {
        synchronized(lock) {
            ensureLoaded()
            val ref = ArtistRef(artistId, artistName)
            if (blocked) {
                if ((artistId.isEmpty() && artistName.isEmpty()) || artistMatches(ref)) return
                artists.add(0, Entry(artistId, artistName, "", "", nowSeconds(), 0))
                Log.i("blacklist", "artist blocked: $artistName")
            } else {
                if (!artists.removeAll { artistEntryMatches(it, ref) }) return
                Log.i("blacklist", "artist unblocked: $artistName")
            }
        }
        changed()
    }

    /** Exactly this entry (the "Kaldır" button in the settings), no matching. */
    fun remove(entry: Entry) {
        synchronized(lock) {
            ensureLoaded()
            val same = { e: Entry -> e.id == entry.id && e.name == entry.name && e.addedAt == entry.addedAt }
            val a = tracks.removeAll(same)
            val b = artists.removeAll(same)
            if (!a && !b) return
            Log.i("blacklist", "entry removed: ${entry.name}")
        }
        changed()
    }

    /** Newest first. */
    fun blockedTracks(): List<Entry> = synchronized(lock) {
        ensureLoaded()
        tracks.toList()
    }

    /** Newest first. */
    fun blockedArtists(): List<Entry> = synchronized(lock) {
        ensureLoaded()
        artists.toList()
    }

    fun isEmpty(): Boolean = synchronized(lock) {
        ensureLoaded()
        tracks.isEmpty() && artists.isEmpty()
    }

    /** Loads blacklist.json now (once; later calls no-op). */
    fun load() = synchronized(lock) { ensureLoaded() }

    /** Tests: re-read the file (drops the state). */
    fun reload() {
        synchronized(lock) { readFile() }
        _revision.value = _revision.value + 1
    }

    /** After every change; never unregistered. */
    fun onChanged(fn: () -> Unit) {
        listeners.add(fn)
    }
}
