package com.app.shadetube.playback.features

import com.app.shadetube.catalog.Track
import java.util.Locale

/**
 * Smart shuffle / "Geliştir" planning, the part the player needs (port of app/SmartMix: track keys, exclusions,
 * insertion points and interleave; the recommendation pools themselves come through [Recommender]).
 *
 * Rules:
 * - a recommendation comes after 3 or 4 of the collection's own songs ([MIN_GAP]..[MAX_GAP], drawn from a seeded
 *   generator: the same seed places them the same way), never two in a row;
 * - a recommendation is never a song of the collection (same id, or the same folded title + first artist: a remaster
 *   or a single of a song already there), never one already queued, never blocked and always playable;
 * - in a queue, the slot right after the playing song is left alone (it may be prepared for a gapless handoff /
 *   crossfade), and a stretch that already has recommendations close enough is left as it is, so planning again
 *   inserts nothing new.
 */
object QueueMix {
    /** Own songs between two recommendations, at least ... */
    const val MIN_GAP = 3

    /** ... and at most. */
    const val MAX_GAP = 4

    /** Queue slots after the playing one that are kept mixed. */
    const val WINDOW = 24

    /** Lower case, letters and digits only (any script); the text stops at a " - " suffix and skips bracketed parts. */
    private fun fold(text: String, cutSuffix: Boolean): String {
        var w = text.replace('ı', 'i').replace('İ', 'i').lowercase(Locale.ROOT)
        if (cutSuffix) {
            val dash = w.indexOf(" - ")
            if (dash > 0) w = w.substring(0, dash)
        }
        val out = StringBuilder(w.length)
        var depth = 0
        var i = 0
        while (i < w.length) {
            val cp = w.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '('.code || cp == '['.code) {
                depth++
                continue
            }
            if (cp == ')'.code || cp == ']'.code) {
                depth = maxOf(0, depth - 1)
                continue
            }
            if (depth > 0) continue
            if (Character.isLetterOrDigit(cp)) out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun firstArtist(t: Track): String = t.artists.firstOrNull { it.name.isNotEmpty() }?.let { fold(it.name, false) } ?: ""

    /** An artist name folded the same way (matching artists across catalogs). */
    fun foldName(name: String) = fold(name, false)

    /**
     * Folded title + first artist: lower case, letters and digits only, without "(...)", "[...]" and " - ..." suffixes
     * ("Song - 2011 Remaster" == "song"). "" for a track without a title.
     */
    fun trackKey(t: Track): String {
        val title = fold(t.name, true)
        if (title.isEmpty()) return ""
        return title + "|" + firstArtist(t)
    }

    class Exclusions {
        val ids = HashSet<String>()
        val keys = HashSet<String>()

        fun add(t: Track) {
            if (t.id.isNotEmpty()) ids.add(t.id)
            val k = trackKey(t)
            if (k.isNotEmpty()) keys.add(k)
        }

        operator fun contains(t: Track): Boolean {
            if (t.id.isNotEmpty() && t.id in ids) return true
            val k = trackKey(t)
            return k.isNotEmpty() && k in keys
        }
    }

    /** FNV-1a of a string (seeds from a collection key / queue generation). */
    fun hash(s: String, basis: Long = -0x340d631b7bdddcdbL /* 1469598103934665603 */): Long {
        var h = basis
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h *= 1099511628211L
        }
        return h
    }

    /**
     * Where to put recommendations in a play order: [isRec] flags every slot, [pos] is the playing one. Returns the
     * order indices (as they are before any insertion, ascending) to insert one recommendation at each; only the
     * [window] slots after [pos] are planned. Insert from the last to the first so the indices stay valid.
     */
    fun insertionPoints(isRec: List<Boolean>, pos: Int, seed: Long, window: Int = WINDOW): List<Int> {
        val points = ArrayList<Int>()
        val n = isRec.size
        if (pos < 0 || pos >= n) return points
        val rng = Mt19937_64(seed)
        fun drawGap() = MIN_GAP + java.lang.Long.remainderUnsigned(rng.next(), (MAX_GAP - MIN_GAP + 1).toLong()).toInt()
        // Own songs since the last recommendation, up to and including the playing one.
        var run = 0
        var i = pos
        while (i >= 0 && !isRec[i] && run < MAX_GAP) {
            run++
            i--
        }
        var target = drawGap()
        val end = minOf(n, pos + 1 + maxOf(0, window))
        for (k in pos + 1 until end) {
            if (isRec[k]) {
                run = 0
                target = drawGap()
                continue
            }
            run++
            if (run < target) continue
            // A recommendation already coming soon enough keeps this stretch as it is.
            var ownAhead = 0
            var recAhead = false
            var j = k + 1
            while (j < n && ownAhead <= MAX_GAP) {
                if (isRec[j]) {
                    recAhead = true
                    break
                }
                ownAhead++
                j++
            }
            if (recAhead && run + ownAhead <= MAX_GAP) continue
            points.add(k + 1) // after this song (k + 1 >= pos + 2: never right after the playing one)
            run = 0
            target = drawGap()
        }
        return points
    }

    /**
     * "Geliştir": [own] with one of [recs] (in their order, marked recommended) after every 3-4 songs, as long as there
     * are recommendations left. A list with fewer than [MIN_GAP] songs gets none.
     */
    fun interleave(own: List<Track>, recs: List<Track>, seed: Long): List<Track> {
        val out = ArrayList<Track>(own.size + recs.size)
        val rng = Mt19937_64(seed)
        fun drawGap() = MIN_GAP + java.lang.Long.remainderUnsigned(rng.next(), (MAX_GAP - MIN_GAP + 1).toLong()).toInt()
        var next = 0
        var run = 0
        var target = drawGap()
        for (t in own) {
            out.add(t)
            if (++run < target || next >= recs.size) continue
            out.add(recs[next++].copy(recommended = true))
            run = 0
            target = drawGap()
        }
        return out
    }
}

/** std::mt19937_64 (the C++ generator, so the same seed places recommendations the same way as the desktop app). */
class Mt19937_64(seed: Long) {
    private val mt = LongArray(312)
    private var index = 312

    init {
        mt[0] = seed
        for (i in 1 until 312) {
            mt[i] = 6364136223846793005L * (mt[i - 1] xor (mt[i - 1] ushr 62)) + i
        }
    }

    fun next(): Long {
        if (index >= 312) twist()
        var x = mt[index++]
        x = x xor ((x ushr 29) and 0x5555555555555555L)
        x = x xor ((x shl 17) and 0x71D67FFFEDA60000L)
        x = x xor ((x shl 37) and -0x8112000000000L) // 0xFFF7EEE000000000
        x = x xor (x ushr 43)
        return x
    }

    private fun twist() {
        val upper = -0x80000000L // 0xFFFFFFFF80000000 (upper 33 bits)
        val lower = 0x7FFFFFFFL
        for (i in 0 until 312) {
            val x = (mt[i] and upper) or (mt[(i + 1) % 312] and lower)
            var xa = x ushr 1
            if (x and 1L != 0L) xa = xa xor -0x4afd90a55699e617L // 0xB5026F5AA96619E9
            mt[i] = mt[(i + 156) % 312] xor xa
        }
        index = 0
    }
}
