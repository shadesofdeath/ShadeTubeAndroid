package com.app.shadetube.downloads

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Files
import com.app.shadetube.core.Settings
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The download queue (order, own vs synced, gating, cancel / remove, collections), downloads.json (C++ shape + the
 * Android "queued" records), folder layout, SponsorBlock cut ranges (port of tests/downloads normalizeCuts) and the
 * ranged stream fetcher (googlevideo `&range=` vs Range header, 1 MB chunks, resume, expired URLs) on a local server.
 */
class DownloadsTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("downloads").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun track(id: String, name: String = "Song $id", artist: String = "Artist", album: String = "Album") =
        Track(id = id, name = name, artists = listOf(ArtistRef("", artist)), album = AlbumRef(name = album), durationMs = 200_000)

    private class FakeNet(@Volatile override var connected: Boolean = true, @Volatile override var metered: Boolean = false) : NetworkState

    /** Each download waits until the test releases it with an outcome (or fails it); records the start order. */
    private inner class FakeDownloader : TrackDownloader {
        val started: MutableList<String> = Collections.synchronizedList(ArrayList())
        val gates = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

        fun gate(id: String) = gates.getOrPut(id) { CompletableDeferred() }

        override suspend fun download(track: Track, targetFor: (DownloadFormat) -> File, progress: (Float) -> Unit): DownloadOutcome {
            started.add(track.id)
            progress(0.5f)
            val ok = gate(track.id).await()
            if (!ok) return DownloadOutcome.Failed(DownloadFailure.Network, "boom")
            val f = targetFor(DownloadFormat.M4a)
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(1234))
            File(f.parentFile, f.nameWithoutExtension + ".lrc").writeText("[00:01.00]x")
            return DownloadOutcome.Ok(f, f.length(), DownloadFormat.M4a, cutSegments = 2, cutMs = 54_000, tagged = true)
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun manager(dl: TrackDownloader, net: NetworkState? = null, settings: Settings = Settings()) =
        DownloadManager(
            downloader = dl,
            storeFile = { File(dir, "downloads.json") },
            rootDir = { File(dir, "Music") },
            scope = scope,
            network = net,
            settings = { settings },
            now = { 1_700_000_000L },
        ).also { it.load() }

    @Test
    fun queueOrderOwnBeforeSyncedOneAtATime() {
        val dl = FakeDownloader()
        val net = FakeNet(connected = false)
        val m = manager(dl, net)
        m.enqueue(track("a"))
        m.enqueue(track("b"))
        m.enqueueSynced(listOf(track("s1"), track("s2"), track("a")))
        assertEquals(listOf("b", "a", "s1", "s2"), m.items().map { it.track.id }) // own at the front, synced after
        assertTrue(m.items().first { it.track.id == "s1" }.synced)
        assertFalse(m.items().first { it.track.id == "a" }.synced) // already known: left as it is
        assertEquals(QueueWait.Offline, m.queueWait())
        assertTrue(dl.started.isEmpty())

        net.connected = true
        m.tick()
        waitUntil("b starts") { dl.started.size == 1 }
        assertEquals("b", dl.started[0])
        assertEquals(DlState.Downloading, m.item("b")!!.state)
        waitUntil("progress") { m.item("b")!!.progress >= 0.5f }
        m.tick() // one at a time
        Thread.sleep(50)
        assertEquals(1, dl.started.size)
        dl.gate("b").complete(true)
        waitUntil("a starts") { dl.started.size == 2 }
        assertEquals("a", dl.started[1])
        val b = m.item("b")!!
        assertEquals(DlState.Done, b.state)
        assertEquals(2, b.cutSegments); assertEquals(54_000, b.cutMs)
        assertEquals(File(dir, "Music/Artist/Album/Song b.m4a").absolutePath, b.filePath)
        assertEquals(File(b.filePath), m.localFileFor("b"))
        assertTrue(m.isDownloaded("b")); assertTrue(m.isActive("a"))
        assertEquals(1234L, m.totalBytes())
        assertEquals(3, m.activeCount())
    }

    @Test
    fun syncGateAndWifiOnly() {
        val dl = FakeDownloader()
        val net = FakeNet()
        val m = manager(dl, net, Settings(downloadWifiOnly = true))
        m.syncAllowed = { false }
        m.enqueueSynced(listOf(track("s1")))
        Thread.sleep(50)
        assertTrue(dl.started.isEmpty())
        assertEquals(QueueWait.SyncGate, m.queueWait())
        assertFalse(m.hasRunnableWork())
        // The user's own download still runs.
        m.enqueue(track("own"))
        waitUntil("own starts") { dl.started == listOf("own") }
        dl.gate("own").complete(true)
        waitUntil("own done") { m.isDownloaded("own") }
        Thread.sleep(50)
        assertEquals(listOf("own"), dl.started)
        // Metered + Wi-Fi only: nothing at all.
        net.metered = true
        m.syncAllowed = { true }
        m.enqueue(track("own2"))
        Thread.sleep(50)
        assertEquals(QueueWait.WifiOnly, m.queueWait())
        net.metered = false
        m.tick()
        waitUntil("own2 starts") { dl.started.contains("own2") }
    }

    @Test
    fun cancelRemoveRetryAndCollections() {
        val dl = FakeDownloader()
        val net = FakeNet(connected = false)
        val m = manager(dl, net)
        val col = m.createCollection("Yolda")
        assertTrue(col.matches(Regex("col:[0-9a-f]{16}")))
        m.enqueue(track("x"), col)
        m.enqueue(track("y"), col)
        net.connected = true
        m.tick()
        waitUntil("y starts") { dl.started.contains("y") }
        assertEquals(listOf("x", "y"), m.collection(col)!!.trackIds)
        // Cancel the active one: removed once the worker stops (and from the folder); the queued one starts.
        m.cancel("y")
        waitUntil("y gone") { m.item("y") == null }
        assertEquals(listOf("x"), m.collection(col)!!.trackIds)
        waitUntil("x starts") { dl.started.contains("x") }
        // Fail x: retried by enqueue, and by enqueueSynced keeping its origin.
        dl.gate("x").complete(false)
        waitUntil("x failed") { m.item("x")?.state == DlState.Failed }
        assertEquals(DownloadFailure.Network, m.item("x")!!.failure)
        dl.gates.remove("x")
        m.enqueueSynced(listOf(track("x")))
        waitUntil("x restarted") { dl.started.count { it == "x" } == 2 }
        assertFalse(m.item("x")!!.synced)
        dl.gate("x").complete(true)
        waitUntil("x done") { m.isDownloaded("x") }
        // Remove: file, sidecar and empty folders go.
        val f = File(m.item("x")!!.filePath)
        assertTrue(f.isFile)
        m.remove("x")
        assertFalse(f.exists())
        assertFalse(File(f.parentFile, "Song x.lrc").exists())
        assertFalse(f.parentFile!!.exists())
        assertTrue(File(dir, "Music").exists())
        assertTrue(m.collection(col)!!.trackIds.isEmpty())
        m.renameCollection(col, "Yeni")
        assertEquals("Yeni", m.collection(col)!!.name)
        m.deleteCollection(col)
        assertNull(m.collection(col))
    }

    @Test
    fun syncedBecomeOwnAndBytes() {
        val dl = FakeDownloader()
        val m = manager(dl)
        m.enqueueSynced(listOf(track("s1"), track("s2")))
        waitUntil("s1 starts") { dl.started.contains("s1") }
        dl.gate("s1").complete(true)
        waitUntil("s2 starts") { dl.started.contains("s2") }
        assertEquals(1234L, m.syncedBytes())
        m.keepAsManual(listOf("s1"))
        assertFalse(m.item("s1")!!.synced)
        assertEquals(0L, m.syncedBytes())
        // A folder add makes it the user's own too.
        val col = m.createCollection("c")
        m.addToCollection(col, "s2")
        assertFalse(m.item("s2")!!.synced)
        // Radio stations and podcast episodes are never queued.
        m.enqueue(track("radio:abc"))
        m.enqueue(track("podcast:1"))
        assertNull(m.item("radio:abc")); assertNull(m.item("podcast:1"))
        val infos = m.itemInfos()
        assertEquals(SyncRules.ItemInfo.St.Done, infos["s1"]!!.state)
        assertEquals(SyncRules.ItemInfo.St.Downloading, infos["s2"]!!.state)
    }

    @Test
    fun downloadsJsonRoundTrip() = runBlocking {
        val music = File(dir, "Music").apply { mkdirs() }
        val done = File(music, "a.m4a").apply { writeBytes(ByteArray(10)) }
        val items = listOf(
            DownloadItem(track("a"), DlState.Done, 1f, done.absolutePath, "", null, 10, 1700000000, 2, 54000, synced = true),
            DownloadItem(track("b"), DlState.Failed, 0f, "", "HTTP 403", DownloadFailure.Network, 0, 1700000001),
            DownloadItem(track("c"), DlState.Queued, 0f, "", "", null, 0, 1700000002),
            DownloadItem(track("d"), DlState.Done, 1f, File(music, "gone.m4a").absolutePath, "", null, 5, 1),
        )
        val cols = listOf(DownloadCollection("col:00000000000000ff", "Yolda", listOf("a", "c"), 1700000003))
        val j = DownloadManager.toJson(items, cols)
        // The C++ shape.
        val o = j.jsonObject
        assertEquals("1", o["version"].toString())
        val a = o["items"]!!.jsonArray[0].jsonObject
        assertEquals("\"done\"", a["s"].toString()); assertEquals("2", a["cs"].toString()); assertEquals("54000", a["cm"].toString())
        assertEquals("true", a["sy"].toString())
        assertEquals("\"a\"", a["t"]!!.jsonObject["id"].toString())
        val b = o["items"]!!.jsonArray[1].jsonObject
        assertFalse(b.containsKey("cs")); assertFalse(b.containsKey("sy")); assertEquals("\"Network\"", b["ek"].toString())
        assertEquals("\"queued\"", o["items"]!!.jsonArray[2].jsonObject["s"].toString())
        assertEquals("[\"a\",\"c\"]", o["collections"]!!.jsonArray[0].jsonObject["tracks"].toString())

        val (back, backCols) = DownloadManager.fromJson(j)
        assertEquals(listOf("a", "b", "c"), back.map { it.track.id }) // "d" lost its file
        assertEquals(items[0], back[0])
        assertEquals(items[1], back[1])
        assertEquals(items[2], back[2])
        assertEquals(cols, backCols)

        // A desktop file: no "sy" / "cs", a state the Android app does not write.
        val desktop = parseJsonOrNull(
            """{"version":1,"items":[{"t":{"id":"x","n":"X"},"s":"done","f":"${done.absolutePath.replace("\\", "\\\\")}","z":10,"at":5,"e":""},
               {"t":{"id":""},"s":"done"},{"t":{"id":"y"},"s":"failed","e":"network"}],"collections":[{"id":"","n":"bad"},{"id":"col:1","n":"ok","tracks":["x",3]}]}""",
        )
        val (dItems, dCols) = DownloadManager.fromJson(desktop)
        assertEquals(listOf("x", "y"), dItems.map { it.track.id })
        assertFalse(dItems[0].synced); assertEquals(0, dItems[0].cutSegments); assertEquals(1f, dItems[0].progress)
        assertEquals(DlState.Failed, dItems[1].state); assertNull(dItems[1].failure)
        assertEquals(listOf(DownloadCollection("col:1", "ok", listOf("x"), 0)), dCols)

        // Through the manager: save, load into a new one.
        val dl = FakeDownloader()
        val m = manager(dl, FakeNet(connected = false))
        m.enqueue(track("q1"))
        m.enqueueSynced(listOf(track("q2")))
        m.flush()
        assertTrue(File(dir, "downloads.json").isFile)
        val m2 = manager(dl, FakeNet(connected = false))
        assertEquals(listOf("q1", "q2"), m2.items().map { it.track.id })
        assertTrue(m2.items().all { it.state == DlState.Queued })
        assertTrue(m2.item("q2")!!.synced)
        assertTrue(Files.readJson(File(dir, "downloads.json")) != null)
    }

    @Test
    fun folderLayout() {
        val root = File(dir, "root")
        assertEquals(File(root, "Artist/Album/Song.m4a"), DownloadManager.downloadTargetPath(track("1", "Song"), DownloadFormat.M4a, root))
        assertEquals(File(root, "A_B/Title_ x_.webm"), DownloadManager.downloadTargetPath(track("1", "Title: x?", "A/B", ""), DownloadFormat.Webm, root))
        val noArtist = Track(id = "spotify:track:1", name = "")
        assertEquals(File(root, "Bilinmeyen Sanatçı/spotify_track_1.m4a"), DownloadManager.downloadTargetPath(noArtist, DownloadFormat.M4a, root))
        assertEquals("_", DownloadManager.sanitizeName(" . "))
        assertEquals("abc", DownloadManager.sanitizeName("abc.. "))
        assertEquals(80, DownloadManager.sanitizeName("x".repeat(200)).length)
        assertEquals("a_b", DownloadManager.sanitizeName("a\u0001b"))
    }

    @Test
    fun cuts() {
        // Port of tests/downloads [normalizeCuts].
        val n = Mp4Trimmer.normalizeCuts(listOf(5000L to 6000L, -300L to 1000L, 900L to 1500L, 7000L to 7000L, 5500L to 5800L, 9000L to 8000L))
        assertEquals(listOf(0L to 1500L, 5000L to 6000L), n)
        assertTrue(Mp4Trimmer.normalizeCuts(emptyList()).isEmpty())
        // The "would leave < 10 s" guard (20 s source).
        assertFalse(Mp4Trimmer.cutsAllowed(listOf(0L to 15000L), 20000))
        assertTrue(Mp4Trimmer.cutsAllowed(listOf(0L to 1000L, 18000L to 30000L), 20000))
        assertEquals(3000L, Mp4Trimmer.coveredMs(Mp4Trimmer.normalizeCuts(listOf(-500L to 1000L, 18000L to 30000L)), 20000))
        // Frames are dropped by their middle.
        val c = listOf(1000L to 2000L)
        assertEquals(-1, Mp4Trimmer.cutIndexOf(c, 970_000, 23_000)) // middle 981.5 ms
        assertEquals(-1, Mp4Trimmer.cutIndexOf(c, 980_000, 23_000)) // middle 991.5 ms
        assertEquals(0, Mp4Trimmer.cutIndexOf(c, 990_000, 23_000)) // middle 1001.5 ms
        assertEquals(0, Mp4Trimmer.cutIndexOf(c, 1_500_000, 23_000))
        assertEquals(-1, Mp4Trimmer.cutIndexOf(c, 1_995_000, 23_000)) // middle 2006 ms
    }

    // ---- the ranged fetcher on a local server -------------------------------------------------------------------------

    private val blob = ByteArray(2_500_000) { (it * 31 + 7).toByte() }

    private fun serve(byParam: Boolean, expireAt: Long = -1, requests: MutableList<String>): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val (first, last) = if (byParam) {
                    val r = url.queryParameter("range") ?: return MockResponse().setResponseCode(400)
                    r.substringBefore('-').toLong() to r.substringAfter('-').toLong()
                } else {
                    val h = request.getHeader("Range") ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(blob))
                    val spec = h.removePrefix("bytes=")
                    spec.substringBefore('-').toLong() to spec.substringAfter('-').toLong()
                }
                requests.add("$first-$last")
                if (expireAt in 0..first) return MockResponse().setResponseCode(403)
                val end = minOf(last, blob.size - 1L)
                val body = blob.copyOfRange(first.toInt(), end.toInt() + 1)
                return if (byParam) MockResponse().setResponseCode(200).setBody(Buffer().write(body))
                else MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $first-$end/${blob.size}").setBody(Buffer().write(body))
            }
        }
        server.start()
        return server
    }

    @Test
    fun fetcherGooglevideoRangeParamAndResume() = runBlocking {
        val requests = Collections.synchronizedList(ArrayList<String>())
        val server = serve(byParam = true, requests = requests)
        try {
            val url = server.url("/videoplayback?itag=140&clen=${blob.size}&range=0-1").toString()
            val out = File(dir, "a.part")
            var last = 0L
            val total = StreamFetcher().fetch(url, mapOf("User-Agent" to "test"), 0, out, 0, googleVideo = true) { d, _ -> last = d }
            assertEquals(blob.size.toLong(), total)
            assertEquals(blob.size.toLong(), last)
            assertArrayEquals(blob, out.readBytes())
            assertEquals(listOf("0-1048575", "1048576-2097151", "2097152-2499999"), requests)
            // Resume: a partial file continues where it stopped.
            requests.clear()
            out.writeBytes(blob.copyOf(1_500_000))
            StreamFetcher().fetch(url, emptyMap(), blob.size.toLong(), out, 1_500_000, googleVideo = true)
            assertArrayEquals(blob, out.readBytes())
            assertEquals(listOf("1500000-2499999"), requests)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fetcherRangeHeaderProbeAndExpiry() = runBlocking {
        val requests = Collections.synchronizedList(ArrayList<String>())
        val server = serve(byParam = false, requests = requests)
        try {
            val out = File(dir, "b.part")
            // Unknown length: the first request probes it (Content-Range).
            StreamFetcher().fetch(server.url("/media/1").toString(), emptyMap(), 0, out, 0, googleVideo = false)
            assertArrayEquals(blob, out.readBytes())
            assertEquals("0-1048575", requests[0])
        } finally {
            server.shutdown()
        }
        val expiring = serve(byParam = true, expireAt = 1_048_576, requests = requests)
        try {
            val out = File(dir, "c.part")
            try {
                StreamFetcher().fetch(expiring.url("/videoplayback?clen=${blob.size}").toString(), emptyMap(), 0, out, 0, googleVideo = true)
                fail("expected ExpiredException")
            } catch (e: StreamFetcher.ExpiredException) {
                assertEquals(403, e.status)
            }
            assertEquals(1_048_576L, out.length()) // kept for the resume after re-resolving
        } finally {
            expiring.shutdown()
        }
    }

    @Test
    fun fetcherHelpers() {
        assertEquals("https://x.googlevideo.com/v?a=1&b=2&range=5-9", StreamFetcher.withRangeParam("https://x.googlevideo.com/v?a=1&range=0-1&b=2", 5, 9))
        assertEquals("https://h/v?range=0-1", StreamFetcher.withRangeParam("https://h/v", 0, 1))
        assertEquals(12345L, StreamFetcher.contentLengthFromUrl("https://h/v?x=1&clen=12345&y"))
        assertEquals(-1L, StreamFetcher.contentLengthFromUrl("https://h/v?xclen=5"))
        assertTrue(StreamFetcher.isGoogleVideo("https://rr1---sn-abc.googlevideo.com/videoplayback?x"))
        assertFalse(StreamFetcher.isGoogleVideo("https://piped.example/videoplayback?host=googlevideo.com"))
        // Resume bookkeeping of the downloader.
        val part = File(dir, "p.part").apply { writeBytes(ByteArray(100)) }
        val info = File(dir, "p.part.json")
        assertEquals(0L, YoutubeTrackDownloader.resumeOffset(part, info, "v", 140, 1000))
        Files.writeAtomic(info, """{"v":"v","itag":140,"len":1000}""")
        assertEquals(100L, YoutubeTrackDownloader.resumeOffset(part, info, "v", 140, 1000))
        assertEquals(0L, YoutubeTrackDownloader.resumeOffset(part, info, "v", 251, 1000))
        assertEquals(0L, YoutubeTrackDownloader.resumeOffset(part, info, "v", 140, 999))
    }
}
