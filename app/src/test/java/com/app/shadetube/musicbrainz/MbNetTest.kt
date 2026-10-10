package com.app.shadetube.musicbrainz

import com.app.shadetube.core.str
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The transport's cache (memory + disk), stale fallback and error mapping, against a local MockWebServer. */
class MbNetTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("mbnet").toFile()
        MbNet.cacheRootProvider = { dir }
        MbNet.clearMemoryCache()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    // Wikidata: no rate limiting, so the tests don't wait.
    private fun req(path: String, ttl: Long = MbNet.Ttl.LOOKUP, cacheOnly: Boolean = false) =
        MbNet.Request(MbNet.Service.Wikidata, server.url(path).toString(), ttlSec = ttl, cacheOnly = cacheOnly)

    @Test
    fun cachesInMemoryAndOnDisk() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"a":"1"}"""))
        assertNull(MbNet.fetch(req("/x", cacheOnly = true)))
        assertEquals("1", MbNet.fetch(req("/x")).str("a"))
        val s0 = MbNet.stats()
        assertEquals("1", MbNet.fetch(req("/x")).str("a"))
        assertEquals(s0.memoryHits + 1, MbNet.stats().memoryHits)
        MbNet.clearMemoryCache()
        assertEquals("1", MbNet.fetch(req("/x", cacheOnly = true)).str("a"))
        assertEquals(s0.diskHits + 1, MbNet.stats().diskHits)
        assertEquals(1, server.requestCount)
        // Disk format: "<key>\n<body>" in <fnv1a hex>.json.
        val files = dir.listFiles()!!.filter { it.name.endsWith(".json") }
        assertEquals(1, files.size)
        assertTrue(files[0].readText().startsWith("GET " + server.url("/x") + "\n"))
        assertEquals("User-Agent", "ShadeTube/0.1 ( https://github.com/shadesofdeath/ShadeTubeAndroid )", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun servesStaleOnInvalidJson() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"v":"old"}"""))
        MbNet.fetch(req("/s"))
        MbNet.clearMemoryCache()
        dir.listFiles()!!.forEach { it.setLastModified(System.currentTimeMillis() - 3_600_000) }
        server.enqueue(MockResponse().setBody("<html>maintenance</html>"))
        assertEquals("old", MbNet.fetch(req("/s", ttl = 60)).str("v"))
    }

    @Test
    fun errorsCarryStatusAndMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Not Found"}"""))
        try {
            MbNet.fetch(req("/missing"))
            fail("expected MbApiError")
        } catch (e: MbApiError) {
            assertEquals(404, e.status)
            assertTrue(e.message!!.endsWith(": Not Found"))
        }
    }

    @Test
    fun derivedValues() = runBlocking {
        assertNull(MbNet.cacheGet("wikidata-p18 Q1", MbNet.Ttl.WIKIDATA))
        MbNet.cachePut("wikidata-p18 Q1", """{"file":"x.jpg"}""")
        MbNet.clearMemoryCache()
        assertNotNull(MbNet.cacheGet("wikidata-p18 Q1", MbNet.Ttl.WIKIDATA))
    }

    @Test
    fun fnv1aMatchesTheReference() {
        // 64-bit FNV-1a test vectors.
        assertEquals("cbf29ce484222325", "%016x".format(MbNet.fnv1a("")))
        assertEquals("af63dc4c8601ec8c", "%016x".format(MbNet.fnv1a("a")))
        assertEquals("85944171f73967e8", "%016x".format(MbNet.fnv1a("foobar")))
    }
}
