package com.app.shadetube.downloads

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Page
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Settings
import com.app.shadetube.downloads.SyncRules.Kind
import com.app.shadetube.downloads.SyncRules.ListError
import com.app.shadetube.downloads.SyncRules.Rule
import com.app.shadetube.spotify.ApiError
import com.app.shadetube.spotify.PlaylistTracks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * The sync engine with fake sources (no network): listings and paging, queueing in collection order, a 429 that stops
 * every Spotify rule, backoff, excluded songs, dropping (and the suspicious empty listing), turning a rule off (keep /
 * delete), the storage cap and Liked Songs changes.
 */
class DownloadSyncTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    @Volatile private var clock = 1_800_000_000L

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("dsync").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun track(id: String) = Track(id = id, name = "Song $id", artists = listOf(ArtistRef("", "Artist")), album = AlbumRef(name = "Al"), durationMs = 200_000)

    private class FakeNet(override val connected: Boolean, override val metered: Boolean = false) : NetworkState

    private class FakeSpotify : SpotifySyncSource {
        @Volatile override var loggedIn = true
        override val changes: Flow<Unit> = MutableSharedFlow()
        override val playlistEdits: Flow<String> = MutableSharedFlow()
        @Volatile var liked: Set<String>? = null
        val lists = HashMap<String, List<Track>>()
        val failures = HashMap<String, Exception>()
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun likedIds() = liked
        override suspend fun playlistTracks(uri: String, offset: Int, limit: Int): PlaylistTracks {
            calls.add("$uri@$offset")
            failures[uri]?.let { throw it }
            val all = lists[uri] ?: throw ApiError(404, "not found")
            val items = all.subList(minOf(offset, all.size), minOf(offset + limit, all.size))
            return PlaylistTracks(Page(items, all.size, offset), Playlist(id = uri, name = "Remote $uri"))
        }
        override suspend fun album(uri: String): Album {
            calls.add(uri)
            return Album(id = uri, name = "Spotify album", tracks = (lists[uri] ?: emptyList()).map { it.copy(album = AlbumRef()) })
        }
    }

    private class FakeLocal : LocalCollections {
        @Volatile var likedTracks: List<Track> = emptyList()
        val playlists = HashMap<String, LocalPlaylist>()
        override fun liked() = likedTracks
        override fun playlist(id: String) = playlists[id]
        override val changes: Flow<Unit> = MutableSharedFlow()
    }

    private class FakeBlocklist(val blocked: Set<String>) : SyncBlocklist {
        override fun isBlocked(t: Track) = t.id in blocked
        override fun isTrackBlocked(trackId: String) = trackId in blocked
    }

    /** Downloads finish at once when [connected] (else everything stays queued). */
    private fun manager(connected: Boolean) = DownloadManager(
        downloader = TrackDownloader { t, targetFor, _ ->
            val f = targetFor(DownloadFormat.M4a)
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(1000))
            DownloadOutcome.Ok(f, 1000, DownloadFormat.M4a)
        },
        storeFile = { File(dir, "downloads.json") },
        rootDir = { File(dir, "Music") },
        scope = scope,
        network = FakeNet(connected),
        settings = { Settings() },
        now = { clock },
    ).also { it.load() }

    private fun engine(
        m: DownloadManager,
        spotify: FakeSpotify? = FakeSpotify(),
        local: FakeLocal? = FakeLocal(),
        blocked: Set<String> = emptySet(),
        settings: Settings = Settings(),
        rules: List<Rule> = emptyList(),
    ): DownloadSync {
        if (rules.isNotEmpty()) SyncRules.saveState(SyncRules.State(rules), File(dir, "sync.json"))
        return DownloadSync(
            downloads = m,
            spotify = spotify,
            local = local,
            blocklist = FakeBlocklist(blocked),
            openAlbum = { id -> Album(id = id, name = "MB album", tracks = listOf(track("mb1"), track("mb2"))) },
            network = null,
            file = { File(dir, "sync.json") },
            scope = scope,
            settings = { settings },
            now = { clock },
            pacing = SyncPacing(pageMs = 0, collectionMs = 0),
        ).also { it.load() }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun spotifyPlaylistIsPagedAndQueuedInOrder() = runBlocking {
        val m = manager(connected = false)
        val sp = FakeSpotify()
        val all = (1..250).map { track("spotify:track:$it") } + track("local:abc") + track("spotify:track:blocked")
        sp.lists["spotify:playlist:p1"] = all
        val s = engine(m, sp, blocked = setOf("spotify:track:blocked"))
        m.enqueue(track("own"))
        s.enable(DownloadSync.Target(Kind.Playlist, "spotify:playlist:p1", "P1"))
        s.runBackgroundPass()
        assertEquals(listOf("spotify:playlist:p1@0", "spotify:playlist:p1@100", "spotify:playlist:p1@200"), sp.calls)
        val r = s.rules().single()
        assertEquals(250, r.trackIds.size)
        assertEquals("Remote spotify:playlist:p1", r.name) // refreshed by the listing
        assertEquals(clock, r.lastSync)
        assertEquals(ListError.None, r.error)
        val ids = m.items().map { it.track.id }
        assertEquals("own", ids[0]) // the user's own download first
        assertEquals((1..250).map { "spotify:track:$it" }, ids.drop(1))
        assertTrue(m.items().drop(1).all { it.synced && it.state == DlState.Queued })
        val st = s.status(r.id)
        assertEquals(SyncRules.Progress(total = 250, done = 0, active = 250, failed = 0), st.progress)
        assertTrue(st.synced && st.sourceReady && !st.listing)
        // Not due again right away; due after the 45 min relist.
        s.runBackgroundPass()
        assertEquals(3, sp.calls.size)
        clock += 45 * 60
        s.runBackgroundPass()
        assertEquals(6, sp.calls.size)
        // Logged out: the Spotify rule is skipped and keeps its ids.
        sp.loggedIn = false
        clock += 45 * 60
        s.runBackgroundPass()
        assertEquals(6, sp.calls.size)
        assertEquals(250, s.rules().single().trackIds.size)
        assertFalse(s.status(r.id).sourceReady)
    }

    @Test
    fun rateLimitStopsEverySpotifyRule() = runBlocking {
        val m = manager(connected = false)
        val sp = FakeSpotify()
        sp.failures["spotify:playlist:a"] = ApiError(429, "HTTP 429")
        sp.lists["spotify:playlist:b"] = listOf(track("spotify:track:b1"))
        val local = FakeLocal()
        local.playlists["local:p"] = LocalPlaylist("Yerel", emptyList(), listOf(track("l1"), track("l2")))
        val s = engine(
            m, sp, local,
            rules = listOf(
                Rule("spotify:playlist:a", Kind.Playlist, "A"),
                Rule("spotify:playlist:b", Kind.Playlist, "B"),
                Rule("local:p", Kind.Playlist, "P"),
            ),
        )
        s.runBackgroundPass()
        val a = s.rules()[0]
        assertEquals(ListError.RateLimited, a.error)
        assertEquals(1, a.failures)
        assertEquals(clock + 600, a.retryAt) // 429: at least 10 min
        assertEquals(0L, s.rules()[1].lastSync) // b waits too
        assertEquals(listOf("spotify:playlist:a@0"), sp.calls)
        assertEquals(listOf("l1", "l2"), s.rules()[2].trackIds) // local rules go on
        // syncNow cannot force a Spotify rule while Spotify asked us to wait.
        val events = Collections.synchronizedList(ArrayList<DownloadSync.Event>())
        val collector = scope.launchCollect(s, events)
        s.syncNow("spotify:playlist:b")
        waitUntil("rate limited event") { events.contains(DownloadSync.Event.SpotifyRateLimited) }
        collector.cancel()
        // After the backoff: both are listed; a 404 is "not found" with the 1 min backoff... of its second failure: 5 min.
        clock += 601
        sp.failures["spotify:playlist:a"] = ApiError(404, "gone")
        s.runBackgroundPass()
        assertEquals(ListError.NotFound, s.rules()[0].error)
        assertEquals(2, s.rules()[0].failures)
        assertEquals(clock + 300, s.rules()[0].retryAt)
        assertEquals(listOf("spotify:track:b1"), s.rules()[1].trackIds)
        // Saved and loaded again with the same shape.
        s.flush()
        val back = SyncRules.loadState(File(dir, "sync.json"))
        assertEquals(ListError.NotFound, back.rules[0].error)
        assertEquals(3, back.rules.size)
    }

    private fun CoroutineScope.launchCollect(s: DownloadSync, into: MutableList<DownloadSync.Event>) =
        launch(start = CoroutineStart.UNDISPATCHED) { s.events.collect { into.add(it) } }

    @Test
    fun excludeIncludeDropAndSuspiciousShrink() = runBlocking {
        val m = manager(connected = false)
        val local = FakeLocal()
        local.playlists["local:p"] = LocalPlaylist("P", emptyList(), listOf(track("t1"), track("t2"), track("t3")))
        val s = engine(m, null, local, settings = Settings(syncRemoveDropped = true))
        s.enable(DownloadSync.Target(Kind.Playlist, "local:p", "P"))
        s.runBackgroundPass()
        assertEquals(listOf("t1", "t2", "t3"), m.items().map { it.track.id })

        // "Senkrondan çıkar": out of the rule, its queued sync download dropped, never queued again.
        s.excludeTracks(listOf(track("t2")))
        assertEquals(listOf("t1", "t3"), s.rules().single().trackIds)
        assertNull(m.item("t2"))
        assertTrue(s.isExcludedAnywhere("t2")); assertFalse(s.isWanted("t2")); assertTrue(s.isWanted("t1"))
        assertEquals(listOf("t2"), s.excluded("local:p").map { it.id })
        assertEquals(DownloadSync.TrackActions(emptyList(), listOf("t2"), emptyList()), s.trackActions(listOf(track("t2"))))
        clock += 10
        s.syncNow("local:p")
        s.runBackgroundPass()
        assertNull(m.item("t2"))

        // Back in: the next listing queues it again.
        clock += 10
        s.includeTracks(listOf("t2"))
        assertTrue(s.rules().single().excluded.isEmpty())
        clock += 10
        s.runBackgroundPass() // changedAt > lastSync: due after the debounce
        assertEquals(listOf("t1", "t2", "t3"), s.rules().single().trackIds)
        assertNotNull(m.item("t2"))

        // The playlist lost t3: its queued sync download is dropped.
        local.playlists["local:p"] = LocalPlaylist("P", emptyList(), listOf(track("t1"), track("t2")))
        clock += 10
        s.syncNow("local:p")
        s.runBackgroundPass()
        assertNull(m.item("t3"))

        // An empty listing after holding tracks drops nothing (an API hiccup must never wipe offline music).
        local.playlists["local:p"] = LocalPlaylist("P", emptyList(), emptyList())
        clock += 10
        s.syncNow("local:p")
        s.runBackgroundPass()
        assertTrue(s.rules().single().trackIds.isEmpty())
        assertNotNull(m.item("t1")); assertNotNull(m.item("t2"))
        // The next empty listing confirms it.
        clock += 10
        s.syncNow("local:p")
        s.runBackgroundPass()
        assertNull(m.item("t1")); assertNull(m.item("t2"))
    }

    @Test
    fun disableKeepsOrDeletesAndOwnDownloadsAreSafe() = runBlocking {
        val m = manager(connected = true)
        val local = FakeLocal()
        local.likedTracks = listOf(track("k1"), track("k2"))
        local.playlists["local:p"] = LocalPlaylist("P", emptyList(), listOf(track("d1"), track("d2"), track("k1")))
        val s = engine(m, null, local)
        assertEquals(SyncRules.LOCAL_LIKED_ID, s.likedTarget().id)
        s.enable(s.likedTarget())
        s.runBackgroundPass()
        s.enable(DownloadSync.Target(Kind.Playlist, "local:p", "P"))
        s.runBackgroundPass()
        waitUntil("downloads done") { m.items().size == 4 && m.items().all { it.state == DlState.Done } }
        m.enqueue(track("d2")) // the user downloads d2 themselves: their own from now on
        assertFalse(m.item("d2")!!.synced)
        assertTrue(s.disableNeedsChoice("local:p"))
        assertTrue(s.status(SyncRules.LOCAL_LIKED_ID).complete)

        // Delete: d1 goes; k1 stays (Liked Songs wants it); d2 is the user's own.
        s.disable("local:p", deleteFiles = true)
        assertNull(m.item("d1"))
        assertTrue(m.isDownloaded("k1") && m.item("k1")!!.synced)
        assertTrue(m.isDownloaded("d2"))
        // Keep: the rest become the user's own downloads.
        s.disable(SyncRules.LOCAL_LIKED_ID, deleteFiles = false)
        assertTrue(s.rules().isEmpty())
        assertTrue(m.isDownloaded("k1") && !m.item("k1")!!.synced)
        assertTrue(m.isDownloaded("k2") && !m.item("k2")!!.synced)
        assertEquals(0L, m.syncedBytes())
    }

    @Test
    fun storageCapLeavesTracksOut() = runBlocking {
        val m = manager(connected = false)
        val local = FakeLocal()
        // 1 GB cap; a 200 s song at the default 320 kbps MP3 is estimated at ~8 MB: about 130 fit.
        local.playlists["local:big"] = LocalPlaylist("Big", emptyList(), (1..400).map { track("b$it") })
        val s = engine(m, null, local, settings = Settings(syncCapGb = 1))
        val events = Collections.synchronizedList(ArrayList<DownloadSync.Event>())
        val collector = scope.launchCollect(s, events)
        s.enable(DownloadSync.Target(Kind.Playlist, "local:big", "Big"))
        s.runBackgroundPass()
        val per = SyncRules.estimateBytes(200_000, Settings().downloadMp3Kbps)
        val fit = (DownloadSync.GIB / per).toInt()
        assertEquals(fit, m.items().size)
        assertEquals(400, s.rules().single().trackIds.size)
        waitUntil("cap event") { events.contains(DownloadSync.Event.StorageCapReached) }
        collector.cancel()
        assertTrue(s.waitReason() is DownloadSync.WaitReason.StorageCap)
        s.onCapChanged()
        assertNull(s.waitReason())
    }

    @Test
    fun albumsAndLikedChanges() = runBlocking {
        val m = manager(connected = false)
        val sp = FakeSpotify()
        sp.lists["spotify:album:x"] = listOf(track("spotify:track:x1"))
        sp.lists[SyncRules.SPOTIFY_LIKED_ID] = listOf(track("spotify:track:l1"), track("spotify:track:l2"))
        val s = engine(m, sp)
        assertEquals(SyncRules.SPOTIFY_LIKED_ID, s.likedTarget().id)
        s.enable(DownloadSync.Target(Kind.Album, "spotify:album:x", "X"))
        s.runBackgroundPass()
        s.enable(DownloadSync.Target(Kind.Album, "mbid-1", "MB"))
        s.runBackgroundPass()
        s.enable(s.likedTarget())
        s.runBackgroundPass()
        // Album tracks point at their album (the listing fills it in).
        assertEquals("Spotify album", m.item("spotify:track:x1")!!.track.album.name)
        assertEquals(listOf("mb1", "mb2"), s.rules()[1].trackIds)
        assertEquals("Al", m.item("mb1")!!.track.album.name) // already had one
        val liked = s.rules()[2]
        assertEquals(listOf("spotify:track:l1", "spotify:track:l2"), liked.trackIds)
        assertEquals("", liked.name) // Liked Songs: the listing has no name for it (the UI names it)

        // A like seen in the library snapshot marks the rule; it is listed after the 5 min least gap.
        sp.liked = setOf("spotify:track:l1", "spotify:track:l2")
        clock += 10
        s.onLibraryChanged()
        assertTrue(s.rules()[2].changedAt <= s.rules()[2].lastSync) // the same likes: no change
        sp.liked = setOf("spotify:track:l1", "spotify:track:l2", "spotify:track:l3")
        sp.lists[SyncRules.SPOTIFY_LIKED_ID] = sp.lists[SyncRules.SPOTIFY_LIKED_ID]!! + track("spotify:track:l3")
        s.onLibraryChanged()
        assertTrue(s.rules()[2].changedAt > s.rules()[2].lastSync)
        val callsBefore = sp.calls.size
        s.runBackgroundPass()
        assertEquals(callsBefore, sp.calls.size) // 10 s after the last listing: within the least gap
        clock += 5 * 60
        s.runBackgroundPass()
        assertEquals(3, s.rules()[2].trackIds.size)
        assertNotNull(m.item("spotify:track:l3"))
    }
}
