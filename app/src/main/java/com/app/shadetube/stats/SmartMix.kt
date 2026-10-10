package com.app.shadetube.stats

import com.app.shadetube.catalog.Track
import kotlin.math.max
import kotlin.math.min

/**
 * Smart shuffle / "Geliştir" planning (port of app/SmartMix; pure). The feature itself (the player and UI wiring)
 * lives with playback; the recommendation pools are [SmartMixRecommender].
 *
 * Rules shared by both:
 * - a recommendation comes after 3 or 4 of the collection's own songs ([MIN_GAP]..[MAX_GAP], drawn from a seeded
 *   generator: the same seed places them the same way), never two in a row;
 * - a recommendation is never a song of the collection (same id, or the same folded title + first artist: a
 *   remaster or a single of a song already there), never one already queued, never blocked and always playable;
 * - in a queue, the slot right after the playing song is left alone (it may be prepared for a gapless handoff), and
 *   a stretch that already has recommendations close enough is left as it is, so planning again inserts nothing new.
 */
object SmartMix {
    /** Own songs between two recommendations, at least... */
    const val MIN_GAP = 3

    /** ... and at most. */
    const val MAX_GAP = 4

    /** Queue slots after the playing one that are kept mixed. */
    const val WINDOW = 24

    /** Lower case, letters and digits only (any script); the text stops at a " - " suffix and skips bracketed parts. */
    private fun fold(text: String, cutSuffix: Boolean): String {
        var w = text
        if (cutSuffix) {
            val dash = w.indexOf(" - ")
            if (dash > 0) w = w.substring(0, dash)
        }
        val out = StringBuilder(w.length)
        var depth = 0
        var i = 0
        while (i < w.length) {
            var cp = w.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '('.code || cp == '['.code) {
                depth++
                continue
            }
            if (cp == ')'.code || cp == ']'.code) {
                depth = max(0, depth - 1)
                continue
            }
            if (depth > 0) continue
            // Turkish dotless / dotted I: "ISTANBUL" == "İstanbul" == "istanbul".
            cp = if (cp == 'ı'.code || cp == 'İ'.code) 'i'.code else Character.toLowerCase(cp)
            if (Character.isLetterOrDigit(cp)) out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun firstArtist(t: Track): String {
        for (a in t.artists) if (a.name.isNotEmpty()) return fold(a.name, false)
        return ""
    }

    /** FNV-1a of a string (seeds from a collection key / queue generation). */
    fun hash(s: String, basis: Long = -0x340d631b7bdddcdbL): Long = fnv64(s, basis)

    /** An artist name folded the same way (matching artists across catalogs). */
    fun foldName(name: String): String = fold(name, false)

    /**
     * Folded title + first artist: lower case, letters and digits only, without "(...)", "[...]" and " - ..."
     * suffixes ("Song - 2011 Remaster" == "song"). "" for a track without a title.
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

        fun addAll(o: Exclusions) {
            ids.addAll(o.ids)
            keys.addAll(o.keys)
        }

        operator fun contains(t: Track): Boolean {
            if (t.id.isNotEmpty() && t.id in ids) return true
            val k = trackKey(t)
            return k.isNotEmpty() && k in keys
        }
    }

    /**
     * The pool's usable recommendations: playable, titled, not excluded, not [blocked], each song once, in a seeded
     * order that avoids the same first artist twice in a row when it can. All are marked recommended.
     */
    fun candidates(pool: List<Track>, exclude: Exclusions, blocked: ((Track) -> Boolean)?, seed: Long): List<Track> {
        val usable = ArrayList<Track>()
        val seen = Exclusions()
        for (t in pool) {
            if (!t.playable || t.name.isEmpty() || t.id.isEmpty()) continue
            if (t in exclude || t in seen) continue
            if (blocked != null && blocked(t)) continue
            seen.add(t)
            usable.add(t)
        }
        // std::shuffle with the seeded engine (Fisher-Yates from the back).
        val rng = Mt19937(seed)
        for (i in usable.size - 1 downTo 1) {
            val j = rng.nextBelow((i + 1).toLong()).toInt()
            val tmp = usable[i]
            usable[i] = usable[j]
            usable[j] = tmp
        }
        // Spread the artists: take the next song whose first artist differs from the last one taken, when there is one.
        val out = ArrayList<Track>(usable.size)
        var last = ""
        while (usable.isNotEmpty()) {
            var at = usable.indexOfFirst { firstArtist(it) != last }
            if (at < 0) at = 0
            val t = usable.removeAt(at)
            last = firstArtist(t)
            out.add(t.copy(recommended = true))
        }
        return out
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
        val rng = Mt19937(seed)
        fun drawGap() = MIN_GAP + java.lang.Long.remainderUnsigned(rng.next(), (MAX_GAP - MIN_GAP + 1).toLong()).toInt()
        // Own songs since the last recommendation, up to and including the playing one.
        var run = 0
        run {
            var i = pos
            while (i >= 0 && !isRec[i] && run < MAX_GAP) {
                run++
                i--
            }
        }
        var target = drawGap()
        val end = min(n, pos + 1 + max(0, window))
        for (i in pos + 1 until end) {
            if (isRec[i]) {
                run = 0
                target = drawGap()
                continue
            }
            run++
            if (run < target) continue
            // A recommendation already coming soon enough keeps this stretch as it is.
            var ownAhead = 0
            var recAhead = false
            var j = i + 1
            while (j < n && ownAhead <= MAX_GAP) {
                if (isRec[j]) {
                    recAhead = true
                    break
                }
                ownAhead++
                j++
            }
            if (recAhead && run + ownAhead <= MAX_GAP) continue
            points.add(i + 1) // after this song (i + 1 >= pos + 2: never right after the playing one)
            run = 0
            target = drawGap()
        }
        return points
    }

    /**
     * "Geliştir": [own] with one of [recs] (in their order, marked recommended) after every 3-4 songs, as long as
     * there are recommendations left. A list with fewer than [MIN_GAP] songs gets none.
     */
    fun interleave(own: List<Track>, recs: List<Track>, seed: Long): List<Track> {
        val out = ArrayList<Track>(own.size + recs.size)
        val rng = Mt19937(seed)
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
