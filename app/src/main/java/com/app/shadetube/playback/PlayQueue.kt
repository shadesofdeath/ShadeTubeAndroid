package com.app.shadetube.playback

import com.app.shadetube.catalog.Track
import com.app.shadetube.core.RepeatMode
import kotlin.random.Random

/**
 * The queue state machine of player::Player, without any playback: items, the play order, the current slot, shuffle
 * (with its reshuffle rules), repeat and the kara liste skip rule. Pure and single-threaded (the [Player] owns it on
 * the main thread); unit-tested on its own.
 *
 * [order] indexes into [items]; [pos] is the playing slot (-1 = none). Items are never reordered: moves, shuffles and
 * insertions only touch [order], so an item index stays valid (except in [dropRecommendations], which compacts).
 *
 * [shouldSkip] (kara liste) is asked whenever the player picks an item by itself — auto-advance, next / previous, the
 * start of a context played without a start index (header play / shuffle), the repeat wrap and the prefetch. true =
 * pass over it. An item the user picks directly (playContext's start index, jumpTo) still plays.
 */
class PlayQueue(
    var shouldSkip: ((Track) -> Boolean)? = null,
    private val random: Random = Random.Default,
) {
    val items = ArrayList<Track>()
    val order = ArrayList<Int>()
    var pos = -1
    var shuffle = false
    var repeat = RepeatMode.Off

    val current: Track? get() = if (pos in order.indices) items[order[pos]] else null
    val isEmpty: Boolean get() = items.isEmpty()

    fun trackAt(orderIndex: Int): Track = items[order[orderIndex]]

    /** Plays (playable, titled): the items a queue may hold at all. */
    fun usable(t: Track) = t.playable && t.name.isNotEmpty()

    // ---- order -------------------------------------------------------------------------------------------------------

    /**
     * Identity order, or a shuffled one when [shuffle] is on: then the kept (current) item goes first so "next" walks
     * the shuffled remainder, and [pos] becomes 0 (-1 without a kept item).
     */
    fun rebuildOrder(keepItemIndex: Int) {
        val n = items.size
        order.clear()
        for (i in 0 until n) order.add(i)
        if (shuffle && n > 1) {
            order.shuffle(random)
            if (keepItemIndex >= 0) {
                // std::rotate(begin, it, it + 1): the kept item moves to the front, the ones before it shift right.
                val at = order.indexOf(keepItemIndex)
                order.removeAt(at)
                order.add(0, keepItemIndex)
            }
            pos = if (keepItemIndex >= 0) 0 else -1
        } else {
            pos = keepItemIndex
        }
    }

    fun skipped(orderIndex: Int): Boolean = shouldSkip?.invoke(items[order[orderIndex]]) == true

    /** First non-skipped slot walking by [step] from [orderIndex] (inclusive), or -1. */
    fun playableFrom(orderIndex: Int, step: Int): Int {
        var i = orderIndex
        while (i >= 0 && i < order.size) {
            if (!skipped(i)) return i
            i += step
        }
        return -1
    }

    /** Non-skipped slots in the whole play order. */
    fun countPlayable(): Int = order.indices.count { !skipped(it) }

    /** The slot that plays after the current one, or -1 (blocked items passed over, like advance()). */
    fun upcomingSlot(): Int {
        if (pos < 0 || pos >= order.size) return -1
        if (repeat == RepeatMode.One) return pos
        val next = playableFrom(pos + 1, 1)
        if (next >= 0) return next
        return if (repeat == RepeatMode.All && !shuffle) playableFrom(0, 1) else -1 // shuffle reorders on wrap
    }

    /** Playable (not skipped) items after the current slot, counting at most [limit]. */
    fun remainingPlayable(limit: Int = Int.MAX_VALUE): Int {
        var n = 0
        var i = maxOf(pos + 1, 0)
        while (i < order.size && n < limit) {
            if (!skipped(i)) n++
            i++
        }
        return n
    }

    // ---- starting a context ------------------------------------------------------------------------------------------

    sealed interface Start {
        /** Nothing playable (unplayable / untitled only): nothing happens. */
        data object Empty : Start

        /** No start index and everything is blocked ("Listedeki tüm şarkılar kara listede"). */
        data object AllBlocked : Start

        data class Ok(val tracks: List<Track>, val itemIndex: Int) : Start
    }

    /**
     * playContext's start selection. Unplayable (local / region-locked) and untitled items are dropped; [startIndex] is
     * the row the user picked: it plays even when skipped. -1 = no row picked (a header / card play button): a random
     * track the player may pick by itself when shuffle is on, else the first one.
     */
    fun selectStart(tracks: List<Track>, startIndex: Int): Start {
        val playable = ArrayList<Track>(tracks.size)
        var start = 0
        for ((i, t) in tracks.withIndex()) {
            if (!usable(t)) continue
            if (i <= startIndex) start = playable.size
            playable.add(t)
        }
        if (playable.isEmpty()) return Start.Empty
        if (startIndex < 0) {
            val candidates = playable.indices.filter { shouldSkip?.invoke(playable[it]) != true }
            if (candidates.isEmpty()) return Start.AllBlocked
            start = if (shuffle) candidates[random.nextInt(candidates.size)] else candidates.first()
        }
        return Start.Ok(playable, start)
    }

    /** Installs a new queue starting at item [startItem] (order rebuilt; shuffle puts it first). */
    fun reset(tracks: List<Track>, startItem: Int) {
        items.clear()
        items.addAll(tracks)
        rebuildOrder(startItem)
    }

    /** playable and not skipped, or -1. */
    fun firstPlayable(tracks: List<Track>): Int =
        tracks.indexOfFirst { usable(it) && shouldSkip?.invoke(it) != true }

    /**
     * replaceUpcoming: the current track stays (as item 0, at the play slot); `tracks` (usable ones) follow, shuffled
     * when shuffle is on. False when nothing is current.
     */
    fun replaceUpcoming(tracks: List<Track>): Boolean {
        val cur = current ?: return false
        val list = ArrayList<Track>(tracks.size + 1)
        list.add(cur)
        for (t in tracks) if (usable(t)) list.add(t)
        items.clear()
        items.addAll(list)
        rebuildOrder(0)
        return true
    }

    // ---- edits -------------------------------------------------------------------------------------------------------

    /** Appends usable tracks at the end of the play order (after a shuffled remainder too). Returns #appended. */
    fun extend(tracks: List<Track>): Int {
        if (items.isEmpty()) return 0 // nothing to continue: callers start a context instead
        var added = 0
        for (t in tracks) {
            if (!usable(t)) continue
            items.add(t)
            order.add(items.size - 1)
            added++
        }
        return added
    }

    /** Inserts [track] right after the current slot (C++ playNext; the queue must not be empty). */
    fun insertNext(track: Track) {
        items.add(track)
        order.add(pos + 1, items.size - 1)
    }

    /** C++ enqueue on a non-empty queue: playable tracks appended (the title is not checked there). */
    fun enqueue(tracks: List<Track>) {
        for (t in tracks) {
            if (!t.playable) continue
            items.add(t)
            order.add(items.size - 1)
        }
    }

    /**
     * Inserts usable tracks into the play order at [orderIndex], clamped between the slot after the current one and
     * the end. Returns (where, how many).
     */
    fun insertAt(orderIndex: Int, tracks: List<Track>): Pair<Int, Int> {
        val at = orderIndex.coerceIn(pos + 1, order.size)
        var added = 0
        for (t in tracks) {
            if (!usable(t)) continue
            items.add(t)
            order.add(at + added, items.size - 1)
            added++
        }
        return at to added
    }

    /** Removes a slot (not the current one). False when nothing changed. */
    fun removeAt(orderIndex: Int): Boolean {
        if (orderIndex < 0 || orderIndex >= order.size || orderIndex == pos) return false
        order.removeAt(orderIndex)
        if (orderIndex < pos) pos--
        return true
    }

    fun move(from: Int, to: Int): Boolean {
        val n = order.size
        if (from < 0 || from >= n || to < 0 || to >= n || from == to) return false
        val item = order.removeAt(from)
        order.add(to, item)
        if (from == pos) {
            pos = to
        } else if (from < pos && to >= pos) {
            pos--
        } else if (from > pos && to <= pos) {
            pos++
        }
        return true
    }

    /** Keeps only the current track. False when nothing is current. */
    fun clearKeepCurrent(): Boolean {
        if (pos < 0) return false
        val cur = order[pos]
        order.clear()
        order.add(cur)
        pos = 0
        return true
    }

    /** Shuffle on / off: the order is rebuilt around the current item. False when unchanged. */
    fun setShuffle(on: Boolean): Boolean {
        if (shuffle == on) return false
        shuffle = on
        if (items.isNotEmpty()) rebuildOrder(if (pos >= 0) order[pos] else -1)
        return true
    }

    /**
     * Smart shuffle / "Geliştir": removes the recommended items that haven't played yet (after the current slot) from
     * the queue for good, so a later reshuffle doesn't bring them back. The user's own items and the playing item stay.
     * Returns how many went.
     */
    fun dropRecommendations(): Int {
        val drop = BooleanArray(items.size)
        var dropped = 0
        for (i in pos + 1 until order.size) {
            val item = order[i]
            if (items[item].recommended && !drop[item]) {
                drop[item] = true
                dropped++
            }
        }
        if (dropped == 0) return 0
        // Compact items too (the slots before the current one never hold a dropped item: only upcoming ones go).
        val newIndex = IntArray(items.size) { -1 }
        val kept = ArrayList<Track>(items.size - dropped)
        for (i in items.indices) {
            if (drop[i]) continue
            newIndex[i] = kept.size
            kept.add(items[i])
        }
        val newOrder = ArrayList<Int>(order.size)
        for (item in order) if (!drop[item]) newOrder.add(newIndex[item])
        items.clear()
        items.addAll(kept)
        order.clear()
        order.addAll(newOrder) // pos is unchanged: everything removed lay after it
        return dropped
    }

    /** A recommendation the user added to the collection: its queue items become ordinary ones. */
    fun clearRecommended(trackId: String): Boolean {
        var changed = false
        for (i in items.indices) {
            val t = items[i]
            if (t.recommended && t.id == trackId) {
                items[i] = t.copy(recommended = false)
                changed = true
            }
        }
        return changed
    }

    /** Catalog items without a duration (manual / restored) learn it from the decoder. */
    fun learnDuration(durationMs: Long) {
        if (pos !in order.indices || durationMs <= 0) return
        val item = order[pos]
        if (items[item].durationMs <= 0) items[item] = items[item].copy(durationMs = durationMs.toInt())
    }

    // ---- navigation decisions ----------------------------------------------------------------------------------------

    sealed interface Advance {
        /** Repeat one replays the current slot. */
        data object Replay : Advance

        /** Go to slot [pos] (already set). */
        data object Moved : Advance

        /** The queue ran out (or everything is blocked): stop on the finished item. */
        data object Stop : Advance
    }

    /**
     * advance(): blocked items (shouldSkip) are passed over; the wrap (repeat all / "next" on the last track) restarts
     * at the first playable slot, reshuffled when shuffle is on. Moves [pos] itself.
     */
    fun advance(userInitiated: Boolean): Advance? {
        if (order.isEmpty()) return null
        if (!userInitiated && repeat == RepeatMode.One) return Advance.Replay
        val next = playableFrom(pos + 1, 1)
        if (next >= 0) {
            pos = next
        } else if (repeat != RepeatMode.Off || userInitiated) {
            if (playableFrom(0, 1) < 0) { // everything is blocked: stay on the finished item (Play replays it)
                if (pos < 0) pos = 0
                return Advance.Stop
            }
            if (shuffle) rebuildOrder(-1)
            pos = playableFrom(0, 1)
        } else {
            return Advance.Stop
        }
        return Advance.Moved
    }

    sealed interface ErrorAdvance {
        data object QueueEnded : ErrorAdvance
        data object GiveUp : ErrorAdvance
        data object Moved : ErrorAdvance
    }

    /**
     * advanceAfterError(): go on with the next track the player may pick. Never back onto the failed slot, and a
     * failure streak ([failStreak], already incremented) as long as the playable queue stops instead of looping.
     */
    fun advanceAfterError(failStreak: Int): ErrorAdvance? {
        if (order.isEmpty()) return null
        val failed = pos
        var next = playableFrom(pos + 1, 1)
        if (next < 0) {
            if (repeat == RepeatMode.Off) return ErrorAdvance.QueueEnded // endless playback may still extend it
            next = playableFrom(0, 1) // repeat one / all: wrap, without reshuffling
        }
        if (next < 0 || next == failed || failStreak >= maxOf(1, countPlayable())) return ErrorAdvance.GiveUp
        pos = next
        return ErrorAdvance.Moved
    }

    /** previous(): the playable slot before the current one, or -1. */
    fun previousSlot(): Int = if (pos > 0) playableFrom(pos - 1, -1) else -1

    fun snapshot(context: PlayContext, generation: Long) = QueueSnapshot(
        items = items.toList(),
        order = order.toList(),
        currentOrderIndex = pos,
        context = context,
        shuffle = shuffle,
        repeat = repeat,
        generation = generation,
    )
}
