package com.app.shadetube.downloads

import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.parseJsonOrNull
import com.app.shadetube.downloads.SyncRules.Attempt
import com.app.shadetube.downloads.SyncRules.ItemInfo
import com.app.shadetube.downloads.SyncRules.Kind
import com.app.shadetube.downloads.SyncRules.ListError
import com.app.shadetube.downloads.SyncRules.Rule
import com.app.shadetube.downloads.SyncRules.State
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Port of tests/sync (sync_test): sync.json store, downloadable filter, plan (retries, blocked, storage cap), drops,
 * progress, scheduling / backoff, excluded songs (store, filter, include again, old files).
 */
class SyncRulesTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("sync").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun track(id: String, durationMs: Int = 200_000) =
        Track(id = id, name = "Song $id", artists = listOf(ArtistRef("artist-1", "Artist")), durationMs = durationMs)

    private fun lookupOf(items: Map<String, ItemInfo>) = SyncRules.ItemLookup { items[it] ?: ItemInfo() }

    private fun info(st: ItemInfo.St, synced: Boolean = true, size: Long = 0) = ItemInfo(st, synced, size)

    private fun ids(tracks: List<Track>) = tracks.map { it.id }

    @Test
    fun store() {
        val a = Rule(
            id = "spotify:playlist:abc", kind = Kind.Playlist, name = "Gece Sürüşü",
            images = listOf(Image("https://i.example/a.jpg", 300, 300)), addedAt = 1700000000, lastSync = 1700000500,
            trackIds = listOf("spotify:track:1", "spotify:track:2"),
        )
        val b = Rule(
            id = SyncRules.LOCAL_LIKED_ID, kind = Kind.Liked, name = "Beğenilen Şarkılar", error = ListError.RateLimited,
            errorDetail = "HTTP 429", failures = 2, retryAt = 1700001000, changedAt = 1700000999, // changedAt: not persisted
        )
        val s = State(listOf(a, b), mapOf("spotify:track:2" to Attempt(2, 1700000400)))

        val file = File(dir, "sync.json")
        assertTrue(SyncRules.saveState(s, file))
        assertFalse(File(dir, "sync.json.tmp").exists())
        val r = SyncRules.loadState(file)
        assertEquals(2, r.rules.size)
        val x = r.rules[0]
        assertEquals(a.id, x.id); assertEquals(Kind.Playlist, x.kind); assertEquals(a.name, x.name)
        assertEquals(1, x.images.size); assertEquals("https://i.example/a.jpg", x.images[0].url); assertEquals(300, x.images[0].width)
        assertEquals(a.addedAt, x.addedAt); assertEquals(a.lastSync, x.lastSync); assertEquals(a.trackIds, x.trackIds)
        assertEquals(ListError.None, x.error); assertEquals(0, x.failures)
        val y = r.rules[1]
        assertEquals(SyncRules.LOCAL_LIKED_ID, y.id); assertEquals(Kind.Liked, y.kind)
        assertEquals(ListError.RateLimited, y.error); assertEquals("HTTP 429", y.errorDetail); assertEquals(2, y.failures)
        assertEquals(1700001000, y.retryAt)
        assertEquals(0, y.changedAt)
        assertEquals(1, r.attempts.size)
        assertEquals(Attempt(2, 1700000400), r.attempts["spotify:track:2"])
        assertNotNull(SyncRules.findRule(r, "liked")); assertNull(SyncRules.findRule(r, "nope"))
        assertTrue(SyncRules.isSpotifyRule(r.rules[0])); assertFalse(SyncRules.isSpotifyRule(r.rules[1]))

        // The JSON shape of the C++ app.
        val j = SyncRules.toJson(s).jsonObject
        assertEquals("1", j["version"].toString())
        val r0 = j["rules"]!!.jsonArray[0].jsonObject
        assertEquals("\"playlist\"", r0["k"].toString()); assertEquals("1700000500", r0["ls"].toString())
        assertFalse(r0.containsKey("err")); assertFalse(r0.containsKey("ex"))
        val r1 = j["rules"]!!.jsonArray[1].jsonObject
        assertEquals("2", r1["err"].toString()); assertEquals("1700001000", r1["retry"].toString())
        assertEquals("[2,1700000400]", j["attempts"]!!.jsonObject["spotify:track:2"].toString())

        // An older profile has no sync.json: no rules, nothing breaks.
        val none = SyncRules.loadState(File(dir, "missing.json"))
        assertTrue(none.rules.isEmpty() && none.attempts.isEmpty())

        // Tolerant parsing: junk entries, a duplicate id, a wrong error code, bad attempts.
        val t = SyncRules.stateFromJson(
            parseJsonOrNull(
                """{
            "version": 1,
            "rules": [ 5, {"n": "no id"}, {"id": "album-mbid", "k": "album", "tracks": ["a", 7, "b"], "err": 9},
                       {"id": "album-mbid", "k": "playlist"}, {"id": "x", "k": "weird"} ],
            "attempts": {"a": [1, 100], "b": "x", "c": [1], "d": [-3, 5]}
        }""",
            ),
        )
        assertEquals(2, t.rules.size)
        assertEquals(Kind.Album, t.rules[0].kind); assertEquals(listOf("a", "b"), t.rules[0].trackIds)
        assertEquals(ListError.None, t.rules[0].error)
        assertEquals("x", t.rules[1].id); assertEquals(Kind.Playlist, t.rules[1].kind)
        assertEquals(2, t.attempts.size); assertEquals(1, t.attempts["a"]!!.count); assertEquals(0, t.attempts["d"]!!.count)

        // A damaged file: kept as .bad, empty state.
        val broken = File(dir, "broken.json")
        broken.writeText("{ \"rules\": [ {\"id\": ")
        val d = SyncRules.loadState(broken)
        assertTrue(d.rules.isEmpty())
        assertTrue(File(dir, "broken.json.bad").exists())
    }

    @Test
    fun filter() {
        assertTrue(SyncRules.downloadable(track("spotify:track:1")))
        assertTrue(SyncRules.downloadable(track("0f5c1d3e-1111-2222-3333-444455556666")))
        assertTrue(SyncRules.downloadable(track("import:abc")))
        assertFalse(SyncRules.downloadable(track("")))
        assertFalse(SyncRules.downloadable(track("radio:9617a958-0601-11e8-ae97-52543be04c81")))
        assertFalse(SyncRules.downloadable(track("local:1234abcd")))
        assertFalse(SyncRules.downloadable(track("podcast:77")))
        assertFalse(SyncRules.downloadable(track("spotify:track:9").copy(playable = false)))

        val list = listOf(track("a"), track("local:x"), track("b"), track("a"), track("c"))
        assertEquals(listOf("a", "b", "c"), SyncRules.ruleTrackIds(list))

        // Estimates: 200 s at 320 kbps = 8 MB + 64 KB; the original stream ~130 kbps; unknown duration = 3.5 min.
        assertEquals(200000L * 320 / 8 + 65536, SyncRules.estimateBytes(200000, 320))
        assertEquals(200000L * 130 / 8 + 65536, SyncRules.estimateBytes(200000, 0))
        assertEquals(SyncRules.estimateBytes(210000, 320), SyncRules.estimateBytes(0, 320))
    }

    @Test
    fun plan() {
        val now = 1_800_000_000L
        val tracks = listOf(
            track("t1"), track("t2"), track("t3"), track("t4"), track("t5"),
            track("t6"), track("t2"), track("local:9"), track("t7"),
        )
        val items = mapOf(
            "t1" to info(ItemInfo.St.Done, false, 5_000_000), // the user downloaded it
            "t2" to info(ItemInfo.St.Queued),
            "t3" to info(ItemInfo.St.Downloading),
            "t4" to info(ItemInfo.St.Failed), // attempts below
            "t5" to info(ItemInfo.St.Failed),
            "t6" to info(ItemInfo.St.Failed),
        )
        val attempts = mapOf(
            "t4" to Attempt(1, now - 2 * 3600), // 1 attempt, 2 h ago -> retry
            "t5" to Attempt(2, now - 3600), // 2 attempts, 1 h ago -> waits (6 h)
            "t6" to Attempt(3, now - 30 * 86400), // gave up
        )
        val blocked = { t: Track -> t.id == "t7" }
        var lim = SyncRules.Limits()
        var p = SyncRules.plan(tracks, lookupOf(items), blocked, attempts, lim, now)
        assertEquals(listOf("t4"), ids(p.toQueue))
        assertEquals(1, p.waitingRetry); assertEquals(1, p.gaveUp); assertEquals(0, p.skippedCap)
        assertEquals(SyncRules.estimateBytes(200000, 320), p.queuedBytes)

        // Retry schedule.
        assertTrue(SyncRules.retryDue(Attempt(0, 0), now))
        assertTrue(SyncRules.retryDue(Attempt(1, now - 3600), now)); assertFalse(SyncRules.retryDue(Attempt(1, now - 3599), now))
        assertTrue(SyncRules.retryDue(Attempt(2, now - 6 * 3600), now)); assertFalse(SyncRules.retryDue(Attempt(2, now - 3600), now))
        assertFalse(SyncRules.retryDue(Attempt(3, 0), now))
        // A failed download sync knows nothing about (the user's own, or attempts pruned) is retried once.
        p = SyncRules.plan(listOf(track("n1")), lookupOf(mapOf("n1" to info(ItemInfo.St.Failed, false))), null, emptyMap(), lim, now)
        assertEquals(1, p.toQueue.size)

        // New tracks, in collection order, each once.
        val empty = lookupOf(emptyMap())
        p = SyncRules.plan(listOf(track("x1"), track("x2"), track("x1"), track("x3")), empty, null, emptyMap(), lim, now)
        assertEquals(listOf("x1", "x2", "x3"), ids(p.toQueue))

        // Storage cap: room for two 8 MB songs after 10 MB used.
        lim = SyncRules.Limits(capBytes = 10_000_000 + 2 * SyncRules.estimateBytes(200000, 320) + 1000, usedBytes = 10_000_000)
        p = SyncRules.plan(listOf(track("c1"), track("c2"), track("c3"), track("c4")), empty, null, emptyMap(), lim, now)
        assertEquals(listOf("c1", "c2"), ids(p.toQueue))
        assertEquals(2, p.skippedCap)
        // A short track still fits after a long one did not.
        lim = lim.copy(capBytes = 10_000_000 + SyncRules.estimateBytes(60000, 320) + 10)
        p = SyncRules.plan(listOf(track("long", 600000), track("short", 60000)), empty, null, emptyMap(), lim, now)
        assertEquals(listOf("short"), ids(p.toQueue)); assertEquals(1, p.skippedCap)
        // Cap already exceeded: nothing.
        lim = lim.copy(usedBytes = lim.capBytes + 1)
        p = SyncRules.plan(listOf(track("z")), empty, null, emptyMap(), lim, now)
        assertTrue(p.toQueue.isEmpty()); assertEquals(1, p.skippedCap)
        // Passthrough estimate is smaller than MP3 320.
        lim = SyncRules.Limits(mp3Kbps = 0)
        p = SyncRules.plan(listOf(track("m")), empty, null, emptyMap(), lim, now)
        assertEquals(SyncRules.estimateBytes(200000, 0), p.queuedBytes)
    }

    @Test
    fun drop() {
        val a = Rule(id = "A", trackIds = listOf("1", "2", "3"))
        val b = Rule(id = "B", trackIds = listOf("3", "4"))
        val wanted = SyncRules.wantedIds(listOf(a, b))
        assertEquals(4, wanted.size); assertTrue("4" in wanted); assertFalse("5" in wanted)

        val items = listOf(
            "1" to info(ItemInfo.St.Done), // wanted
            "5" to info(ItemInfo.St.Done, true, 100), // sync download no rule wants
            "6" to info(ItemInfo.St.Done, false, 100), // the user's own: never
            "7" to info(ItemInfo.St.Queued), // queued by sync, not wanted -> cancel
            "8" to info(ItemInfo.St.Failed), // failed sync item -> cancel
            "9" to info(ItemInfo.St.Downloading), // downloading -> cancel
            "10" to info(ItemInfo.St.Queued, false), // the user's queued download: never
        )
        var d = SyncRules.dropped(items, wanted, false)
        assertTrue(d.remove.isEmpty())
        assertEquals(3, d.cancel.size)
        d = SyncRules.dropped(items, wanted, true)
        assertEquals(listOf("5"), d.remove)
        assertEquals(3, d.cancel.size)
        // Removing rule B: "4" is no longer wanted, "3" still is (rule A).
        d = SyncRules.dropped(listOf("3" to info(ItemInfo.St.Done), "4" to info(ItemInfo.St.Done)), SyncRules.wantedIds(listOf(a)), true)
        assertEquals(listOf("4"), d.remove)

        assertTrue(SyncRules.suspiciousShrink(120, 0))
        assertFalse(SyncRules.suspiciousShrink(0, 0)); assertFalse(SyncRules.suspiciousShrink(120, 3)); assertFalse(SyncRules.suspiciousShrink(5, 6))
    }

    @Test
    fun progress() {
        val r = Rule(id = "r", trackIds = listOf("1", "2", "3", "4", "5", "6"))
        val items = mapOf(
            "1" to info(ItemInfo.St.Done),
            "2" to info(ItemInfo.St.Done, false),
            "3" to info(ItemInfo.St.Queued),
            "4" to info(ItemInfo.St.Downloading),
            "5" to info(ItemInfo.St.Failed),
        )
        assertEquals(SyncRules.Progress(total = 6, done = 2, active = 2, failed = 1), SyncRules.progress(r, lookupOf(items)))
    }

    @Test
    fun schedule() {
        val now = 1_800_000_000L
        var pl = Rule(id = "spotify:playlist:x", kind = Kind.Playlist)
        var liked = Rule(id = SyncRules.SPOTIFY_LIKED_ID, kind = Kind.Liked)
        val album = Rule(id = "spotify:album:y", kind = Kind.Album)
        var local = Rule(id = "local:abc", kind = Kind.Playlist)

        assertTrue(SyncRules.ruleDue(pl, now, false)) // never listed
        pl = pl.copy(retryAt = now + 10)
        assertFalse(SyncRules.ruleDue(pl, now, false)) // backing off
        pl = pl.copy(retryAt = 0)

        pl = pl.copy(lastSync = now - 10 * 60)
        assertFalse(SyncRules.ruleDue(pl, now, false))
        assertTrue(SyncRules.ruleDue(pl, now + 35 * 60, false)) // 45 min periodic
        // A change: debounced, then at least a minute after the last listing.
        pl = pl.copy(changedAt = now - 2)
        assertFalse(SyncRules.ruleDue(pl, now, false))
        pl = pl.copy(changedAt = now - 6)
        assertTrue(SyncRules.ruleDue(pl, now, false))
        pl = pl.copy(lastSync = now - 30, changedAt = now - 10)
        assertFalse(SyncRules.ruleDue(pl, now, false)) // listed 30 s ago
        assertTrue(SyncRules.ruleDue(pl, now + 31, false))
        // Pending tracks: listed again after 30 min.
        pl = pl.copy(changedAt = 0, lastSync = now - 31 * 60)
        assertTrue(SyncRules.ruleDue(pl, now, true)); assertFalse(SyncRules.ruleDue(pl, now, false))
        // The clock went back.
        pl = pl.copy(lastSync = now + 3600)
        assertTrue(SyncRules.ruleDue(pl, now, false))

        assertEquals(45 * 60L, SyncRules.relistInterval(pl)); assertEquals(6 * 3600L, SyncRules.relistInterval(liked))
        assertEquals(24 * 3600L, SyncRules.relistInterval(album))
        assertEquals(45 * 60L, SyncRules.relistInterval(local))
        assertEquals(5 * 60L, SyncRules.minRelistGap(liked)); assertEquals(60L, SyncRules.minRelistGap(pl)); assertEquals(0L, SyncRules.minRelistGap(local))
        liked = liked.copy(lastSync = now - 3600)
        assertFalse(SyncRules.ruleDue(liked, now, false))
        liked = liked.copy(changedAt = now - 60)
        assertTrue(SyncRules.ruleDue(liked, now, false))
        liked = liked.copy(lastSync = now - 120)
        assertFalse(SyncRules.ruleDue(liked, now, false)) // likes keep coming: every 5 min at most
        local = local.copy(lastSync = now - 1, changedAt = now - 5)
        assertFalse(SyncRules.ruleDue(local, now, false)) // changedAt older than the listing
        local = local.copy(changedAt = now)
        assertTrue(SyncRules.ruleDue(local, now + 5, false))

        assertEquals(60L, SyncRules.backoffSeconds(1, false)); assertEquals(300L, SyncRules.backoffSeconds(2, false))
        assertEquals(900L, SyncRules.backoffSeconds(3, false))
        assertEquals(3600L, SyncRules.backoffSeconds(4, false)); assertEquals(3600L, SyncRules.backoffSeconds(40, false))
        assertEquals(60L, SyncRules.backoffSeconds(0, false))
        assertEquals(600L, SyncRules.backoffSeconds(1, true)); assertEquals(3600L, SyncRules.backoffSeconds(4, true))
    }

    @Test
    fun exclude() {
        var r = Rule(id = "spotify:playlist:x", trackIds = listOf("a", "b", "c"))
        assertFalse(SyncRules.isExcluded(r, "b"))
        r = SyncRules.exclude(r, track("b"), 1700000000)!!
        assertTrue(SyncRules.isExcluded(r, "b")); assertEquals(listOf("a", "c"), r.trackIds)
        assertEquals(1, r.excluded.size)
        assertEquals("Song b", r.excluded[0].name); assertEquals("Artist", r.excluded[0].artists); assertEquals(1700000000, r.excluded[0].at)
        assertNull(SyncRules.exclude(r, track("b"), 1700000001)) // once
        assertEquals(1, r.excluded.size)
        assertNull(SyncRules.exclude(r, track(""), 1)) // no id
        // A listing leaves excluded songs out (so they are never planned, never counted as missing).
        val listed = SyncRules.withoutExcluded(r, listOf(track("a"), track("b"), track("c"), track("d")))
        assertEquals(listOf("a", "c", "d"), ids(listed))
        assertEquals(listOf("a", "c", "d"), SyncRules.ruleTrackIds(listed))
        val p = SyncRules.plan(listed, lookupOf(emptyMap()), null, emptyMap(), SyncRules.Limits(), 1700000000)
        assertEquals(listOf("a", "c", "d"), ids(p.toQueue))
        // Progress counts only what the rule keeps.
        val counted = r.copy(trackIds = SyncRules.ruleTrackIds(listed))
        val pr = SyncRules.progress(counted, lookupOf(mapOf("a" to info(ItemInfo.St.Done), "b" to info(ItemInfo.St.Done))))
        assertEquals(3, pr.total); assertEquals(1, pr.done)
        // Round trip; an old file (no "ex") loads without exclusions; an excluded id listed in "tracks" is dropped.
        val s = State(listOf(r))
        val file = File(dir, "exclude.json")
        assertTrue(SyncRules.saveState(s, file))
        val back = SyncRules.loadState(file)
        assertEquals(1, back.rules.size); assertEquals(1, back.rules[0].excluded.size); assertEquals("b", back.rules[0].excluded[0].id)
        assertEquals("Song b", back.rules[0].excluded[0].name); assertEquals(1700000000, back.rules[0].excluded[0].at)
        assertEquals(listOf("a", "c"), back.rules[0].trackIds)

        fun edit(j: JsonElement, change: (MutableMap<String, JsonElement>) -> Unit): JsonElement {
            val root = j.jsonObject.toMutableMap()
            val rules = root["rules"]!!.jsonArray.toMutableList()
            val rule = rules[0].jsonObject.toMutableMap()
            change(rule)
            rules[0] = JsonObject(rule)
            root["rules"] = JsonArray(rules)
            return JsonObject(root)
        }
        val tolerant = edit(SyncRules.toJson(s)) { rule ->
            rule["tracks"] = JsonArray(listOf("a", "b", "c").map { JsonPrimitive(it) })
            rule["ex"] = JsonArray(
                rule["ex"]!!.jsonArray + listOf(
                    JsonArray(listOf(JsonPrimitive(""), JsonPrimitive("x"), JsonPrimitive("y"), JsonPrimitive(1))), // no id: skipped
                    JsonArray(listOf(JsonPrimitive("b"), JsonPrimitive("again"), JsonPrimitive(""), JsonPrimitive(2))), // duplicate
                    JsonPrimitive("junk"),
                ),
            )
        }
        val tol = SyncRules.stateFromJson(tolerant)
        assertEquals(1, tol.rules.size); assertEquals(1, tol.rules[0].excluded.size)
        assertEquals(listOf("a", "c"), tol.rules[0].trackIds)
        val old = edit(SyncRules.toJson(s)) { rule -> rule.remove("ex") }
        assertTrue(SyncRules.stateFromJson(old).rules[0].excluded.isEmpty())
        // Letting it back in.
        r = SyncRules.include(r, "b")!!
        assertFalse(SyncRules.isExcluded(r, "b")); assertTrue(r.excluded.isEmpty())
        assertNull(SyncRules.include(r, "b"))
        assertEquals(listOf("a", "b"), ids(SyncRules.withoutExcluded(r, listOf(track("a"), track("b")))))
    }
}
