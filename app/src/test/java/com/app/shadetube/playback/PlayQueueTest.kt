package com.app.shadetube.playback

import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The queue state machine of player::Player (order, shuffle, repeat, kara liste skips, edits). */
class PlayQueueTest {
    private fun T(id: String, rec: Boolean = false, playable: Boolean = true, name: String = "Track $id") =
        Track(id = id, name = name, artists = listOf(ArtistRef("", "Tester")), durationMs = 1200, recommended = rec, playable = playable)

    private fun queue(blocked: Set<String> = emptySet(), seed: Int = 1) =
        PlayQueue(shouldSkip = { it.id in blocked }, random = Random(seed))

    private fun PlayQueue.ids() = order.map { items[it].id }

    @Test
    fun startSelection() {
        val q = queue(setOf("b"))
        // Unplayable / untitled rows are dropped; the picked row maps onto the playable list.
        val s = q.selectStart(listOf(T("x", playable = false), T("a"), T("y", name = ""), T("b"), T("c")), 3) as PlayQueue.Start.Ok
        assertEquals(listOf("a", "b", "c"), s.tracks.map { it.id })
        assertEquals(1, s.itemIndex) // the explicitly picked blocked "b" still plays
        // Header play: the first one the player may pick by itself.
        val h = q.selectStart(listOf(T("b"), T("c")), -1) as PlayQueue.Start.Ok
        assertEquals(1, h.itemIndex)
        assertEquals(PlayQueue.Start.AllBlocked, q.selectStart(listOf(T("b")), -1))
        assertEquals(PlayQueue.Start.Empty, q.selectStart(listOf(T("z", playable = false)), 0))
        // firstPlayable
        assertEquals(1, q.firstPlayable(listOf(T("b"), T("c"))))
        assertEquals(-1, q.firstPlayable(listOf(T("b"))))
    }

    @Test
    fun autoAdvanceSkipsBlocked() {
        val q = queue(setOf("b"))
        q.reset(listOf(T("a"), T("b"), T("c")), 0)
        assertEquals(1, q.remainingPlayable())
        assertEquals(PlayQueue.Advance.Moved, q.advance(false))
        assertEquals("c", q.current!!.id)
        assertEquals(PlayQueue.Advance.Stop, q.advance(false)) // the queue ran out
        assertEquals("c", q.current!!.id)
        // "next" by the user on the last track wraps to the first playable slot.
        assertEquals(PlayQueue.Advance.Moved, q.advance(true))
        assertEquals("a", q.current!!.id)
        // previous passes over blocked items too
        q.pos = 2
        assertEquals(0, q.previousSlot())
    }

    @Test
    fun repeatModes() {
        val q = queue()
        q.reset(listOf(T("a"), T("b")), 1)
        q.repeat = RepeatMode.One
        assertEquals(PlayQueue.Advance.Replay, q.advance(false))
        assertEquals(1, q.upcomingSlot())
        assertEquals(PlayQueue.Advance.Moved, q.advance(true)) // the user's next still moves
        q.repeat = RepeatMode.All
        q.pos = 1
        assertEquals(0, q.upcomingSlot())
        assertEquals(PlayQueue.Advance.Moved, q.advance(false))
        assertEquals(0, q.pos)
        // Shuffle: the wrap reshuffles, so nothing is "upcoming" at the end.
        q.shuffle = true
        q.pos = 1
        assertEquals(-1, q.upcomingSlot())
        // Everything blocked: stay on the finished item.
        val all = queue(setOf("a", "b"))
        all.reset(listOf(T("a"), T("b")), 0)
        all.repeat = RepeatMode.All
        assertEquals(PlayQueue.Advance.Stop, all.advance(false))
        assertEquals(0, all.pos)
    }

    @Test
    fun shuffleKeepsCurrentFirst() {
        val q = queue(seed = 42)
        val tracks = (0 until 20).map { T("t$it") }
        q.reset(tracks, 0)
        q.pos = 7
        val current = q.current
        assertTrue(q.setShuffle(true))
        assertEquals(0, q.pos)
        assertEquals(current, q.current)
        assertEquals(20, q.order.toSet().size)
        assertFalse(q.setShuffle(true))
        q.pos = 5
        val cur2 = q.current
        q.setShuffle(false)
        assertEquals(cur2, q.current) // identity order, pos at the kept item
        assertEquals((0 until 20).toList(), q.order.toList())
    }

    @Test
    fun errorAdvance() {
        val q = queue(setOf("b"))
        q.reset(listOf(T("x"), T("b")), 0)
        // Its only playable track failed with repeat off: the queue ended.
        assertEquals(PlayQueue.ErrorAdvance.QueueEnded, q.advanceAfterError(1))
        q.repeat = RepeatMode.All
        // With repeat on it would wrap back onto the failed slot: give up instead of looping.
        assertEquals(PlayQueue.ErrorAdvance.GiveUp, q.advanceAfterError(1))
        val q2 = queue()
        q2.reset(listOf(T("x"), T("c")), 0)
        assertEquals(PlayQueue.ErrorAdvance.Moved, q2.advanceAfterError(1))
        assertEquals("c", q2.current!!.id)
        q2.repeat = RepeatMode.All
        // A failure streak as long as the playable queue stops.
        assertEquals(PlayQueue.ErrorAdvance.GiveUp, q2.advanceAfterError(2))
    }

    @Test
    fun editsKeepTheCurrentSlot() {
        val q = queue()
        q.reset(listOf(T("a"), T("b"), T("c"), T("d")), 0)
        q.pos = 2
        assertFalse(q.removeAt(2)) // never the current one
        assertTrue(q.removeAt(0))
        assertEquals(1, q.pos)
        assertEquals("c", q.current!!.id)
        assertEquals(listOf("b", "c", "d"), q.ids())
        assertTrue(q.move(2, 0)) // d before the current one
        assertEquals(listOf("d", "b", "c"), q.ids())
        assertEquals(2, q.pos)
        assertTrue(q.move(2, 0)) // the current one moves itself
        assertEquals(0, q.pos)
        assertEquals("c", q.current!!.id)
        q.insertNext(T("n"))
        assertEquals("n", q.trackAt(1).id)
        // insertAt clamps between the slot after the current one and the end, drops unplayable tracks.
        val (at, added) = q.insertAt(-5, listOf(T("i1"), T("bad", playable = false), T("i2")))
        assertEquals(1, at)
        assertEquals(2, added)
        assertEquals(listOf("c", "i1", "i2", "n", "d", "b"), q.ids())
        assertEquals(2, q.extend(listOf(T("e1"), T("e2", name = ""), T("e3"))))
        assertEquals("e3", q.ids().last())
        assertTrue(q.clearKeepCurrent())
        assertEquals(listOf("c"), q.ids())
        assertEquals(0, q.pos)
    }

    @Test
    fun replaceUpcomingKeepsCurrent() {
        val q = queue()
        q.reset(listOf(T("a"), T("b"), T("c")), 0)
        q.pos = 1
        assertTrue(q.replaceUpcoming(listOf(T("d"), T("e"))))
        assertEquals("b", q.current!!.id)
        assertEquals(listOf("b", "d", "e"), q.ids())
        assertEquals(0, q.pos)
    }

    @Test
    fun recommendations() {
        val q = queue()
        q.reset(listOf(T("own0"), T("own1"), T("rec0", rec = true), T("own2"), T("rec1", rec = true)), 0)
        assertTrue(q.clearRecommended("rec0"))
        assertFalse(q.items[2].recommended)
        assertEquals(1, q.dropRecommendations()) // only rec1 (still flagged) goes, for good
        assertEquals(listOf("own0", "own1", "rec0", "own2"), q.ids())
        assertEquals(4, q.items.size) // compacted
        q.setShuffle(true) // a reshuffle brings nothing back
        assertEquals(4, q.order.size)
        assertEquals(0, q.dropRecommendations())
        // The playing recommendation stays.
        val p = queue()
        p.reset(listOf(T("r", rec = true), T("o"), T("r2", rec = true)), 0)
        assertEquals(1, p.dropRecommendations())
        assertEquals("r", p.current!!.id)
    }

    @Test
    fun learnsDuration() {
        val q = queue()
        q.reset(listOf(T("a").copy(durationMs = 0)), 0)
        q.learnDuration(5000)
        assertEquals(5000, q.current!!.durationMs)
        assertNull(queue().current)
    }
}
