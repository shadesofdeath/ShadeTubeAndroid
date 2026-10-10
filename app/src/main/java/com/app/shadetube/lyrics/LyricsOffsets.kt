package com.app.shadetube.lyrics

import com.app.shadetube.core.parseStrictJson
import com.app.shadetube.core.Files
import com.app.shadetube.core.Log
import com.app.shadetube.core.dump
import com.app.shadetube.core.jsonArrayOf
import com.app.shadetube.core.jsonOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.TreeMap

/**
 * Per-track lyrics timing corrections (+ = lyrics later), `lyrics-offsets.json` in the app data dir (port of
 * `lyrics::Offsets`): {"offsets":{"<trackId>":[ms, unixSecondsOfLastChange]}, "v":1}. Thread-safe; blocking (small
 * file, loaded on first use): call from a worker ([LyricsService] does).
 */
class LyricsOffsets(private val file: File, private val nowUnix: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private class Entry(val ms: Int, val at: Long)

    private val entries = TreeMap<String, Entry>()
    private var loaded = false

    private fun isInt(e: Any?) = e is JsonPrimitive && !e.isString && e.content.toLongOrNull() != null

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        val j = runCatching { parseStrictJson(file.readText()) }.getOrNull() as? JsonObject ?: return
        val offsets = j["offsets"] as? JsonObject ?: return
        for ((id, v) in offsets) {
            if (v !is JsonArray || v.size != 2 || !isInt(v[0]) || !isInt(v[1])) continue
            val ms = (v[0] as JsonPrimitive).content.toLong().coerceIn(-MAX_MS.toLong(), MAX_MS.toLong()).toInt()
            if (ms != 0) entries[id] = Entry(ms, (v[1] as JsonPrimitive).content.toLong())
        }
    }

    private fun saveLocked() {
        val offsets = JsonObject(entries.mapValues { (_, e) -> jsonArrayOf(e.ms, e.at) })
        if (!Files.writeAtomic(file, jsonOf("offsets" to offsets, "v" to 1).dump()))
            Log.w("lyrics", "could not write lyrics offsets")
    }

    /** 0 when none. */
    @Synchronized
    fun offsetMs(trackId: String): Int {
        loadLocked()
        return entries[trackId]?.ms ?: 0
    }

    /** Stores (0 removes) and writes the file. The value is clamped to ±[MAX_MS]. Returns the stored value. */
    @Synchronized
    fun set(trackId: String, msIn: Int): Int {
        if (trackId.isEmpty()) return 0
        loadLocked()
        val ms = msIn.coerceIn(-MAX_MS, MAX_MS)
        if (ms == 0) entries.remove(trackId) else entries[trackId] = Entry(ms, nowUnix())
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.entries.minByOrNull { it.value.at } ?: break
            entries.remove(oldest.key)
        }
        saveLocked()
        return ms
    }

    companion object {
        const val MAX_MS = 30000
        /** Oldest changes dropped beyond this. */
        const val MAX_ENTRIES = 5000
    }
}
