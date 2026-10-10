package com.app.shadetube.spotify

import com.app.shadetube.core.get
import com.app.shadetube.core.parseJsonOrNull
import com.app.shadetube.core.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** Ports tests/spotify testHashExtractor + testHashRegistryOffline, plus discoverHashes on a fake web player. */
class HashRegistryTest {
    private val h1 = "243c0ba2736f16da721e3a227004bbcdb8df6c846f198bd478172e00aa1faf42"
    private val h2 = "1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a"

    private val syntheticJs =
        """var fs=r(84989);let fo=new aJ.l("fetchPlaylist","query","$h1",null),fl=new aJ.l(""" +
            """"fetchPlaylistMetadata","query","$h1",null);let n=new i.l("addToLibrary","mutation","$h2",null);""" +
            """let d=new i.l("fetchPlaylist","query","${"f".repeat(64)}",null);""" + // repeat: first wins
            """x("shortHash","query","abc123");y("notHex","query","${"z".repeat(64)}");""" +
            """z(a,"query","$h1");w("two words","query","$h1");""" +
            """u.miniCssF=e=>""+(({4406:"xpui-routes-search"})[e]||e)+"."+({4406:"11111111"})[e]+".css",""" +
            """u.u=e=>""+(({4406:"xpui-routes-search",2706:"xpui-routes-recent-searches"})[e]||e)+"."+""" +
            """({4406:"96d81afe",2706:"b94d0057",77:"deadbeef"})[e]+".js",r.p="",""" +
            """u.p="https://open.spotifycdn.com/cdn/build/web-player/";"""

    @After
    fun tearDown() {
        HashRegistry.discover = { HashDiscovery.discoverHashes(it) }
    }

    @Test
    fun bundleUrls() {
        val html =
            """<link rel="preload" href="https://open.spotifycdn.com/cdn/build/web-player/web-player.abc12345.js">""" +
                """<script src="https://open.spotifycdn.com/cdn/build/web-player/web-player.abc12345.js"></script>""" +
                """<script src="https://open.spotifycdn.com/cdn/build/web-player/vendor~web-player.4ad2b3e0.js"></script>""" +
                """<script src="/cdn/build/web-player/web-player.lazy0001.js"></script>""" +
                // Foreign hosts are never fetched, whatever the page says.
                """<script src="https://evil.example/cdn/build/web-player/web-player.bad00001.js"></script>""" +
                """<script src="//evil.example/cdn/build/web-player/web-player.bad00002.js"></script>""" +
                """<script src="https://open.spotifycdn.com.evil.example/cdn/build/web-player/web-player.bad00003.js"></script>"""
        assertEquals(
            listOf(
                "https://open.spotifycdn.com/cdn/build/web-player/web-player.abc12345.js",
                "https://open.spotifycdn.com/cdn/build/web-player/web-player.lazy0001.js",
            ),
            HashDiscovery.findBundleUrls(html),
        )
    }

    @Test
    fun persistedOps() {
        val ops = HashDiscovery.extractPersistedOps(syntheticJs)
        assertEquals(3, ops.size)
        assertEquals(PersistedOp("fetchPlaylist", "query", h1), ops[0])
        assertEquals(PersistedOp("fetchPlaylistMetadata", "query", h1), ops[1])
        assertEquals(PersistedOp("addToLibrary", "mutation", h2), ops[2])
        assertTrue(HashDiscovery.extractPersistedOps("").isEmpty())
        assertTrue(HashDiscovery.findBundleUrls("<html></html>").isEmpty())
    }

    @Test
    fun chunkUrls() {
        val chunks = HashDiscovery.findChunkUrls(syntheticJs, "https://fallback.example/")
        assertEquals(3, chunks.size) // the .css map is not a chunk list
        assertEquals("xpui-routes-search", chunks[0].name)
        assertEquals("https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-search.96d81afe.js", chunks[0].url)
        assertEquals("https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-recent-searches.b94d0057.js", chunks[1].url)
        assertEquals(ChunkRef("77", "https://open.spotifycdn.com/cdn/build/web-player/77.deadbeef.js"), chunks[2])
        // Unnamed-only runtime, no public path: the base URL is used, and a preceding .css map is not taken as names.
        val bare = HashDiscovery.findChunkUrls("""})[e]+".css",u.u=e=>""+e+"."+({12:"aaaa1111",7:"bbbb2222"})[e]+".js"""", "https://cdn.example/x/")
        assertEquals(2, bare.size)
        assertEquals("https://cdn.example/x/12.aaaa1111.js", bare[0].url)
        assertEquals("7", bare[1].name)
        assertTrue(HashDiscovery.findChunkUrls("no runtime here", "https://cdn.example/").isEmpty())
        // A public path on a foreign host is ignored (the bundle's own directory is used instead).
        val foreign = HashDiscovery.findChunkUrls(
            """u.u=e=>""+e+"."+({5:"cccc3333"})[e]+".js",u.p="https://evil.example/js/";""",
            "https://open.spotifycdn.com/cdn/build/web-player/",
        )
        assertEquals(listOf(ChunkRef("5", "https://open.spotifycdn.com/cdn/build/web-player/5.cccc3333.js")), foreign)
    }

    @Test
    fun chunkRanking() {
        assertEquals(listOf("search", "desktop"), HashDiscovery.nameWords("searchDesktop"))
        assertEquals(listOf("query", "artist", "overview"), HashDiscovery.nameWords("queryArtistOverview"))
        val words = HashDiscovery.nameWords("searchDesktop")
        assertEquals(4, HashDiscovery.chunkScore("xpui-routes-search", words))
        assertEquals(1, HashDiscovery.chunkScore("xpui-routes-recent-searches", words))
        assertEquals(-1000, HashDiscovery.chunkScore("77", words))
        assertTrue(HashDiscovery.chunkScore("xpui-desktop-modals", words) < HashDiscovery.chunkScore("xpui-routes-search", words))
    }

    @Test
    fun failureClassifier() {
        val c = HashDiscovery::classifyFailure
        assertEquals(QueryFailure.HashRejected, c(412, "\"Invalid query hash\"").kind) // live response 2026-09-28
        assertEquals(QueryFailure.HashRejected, c(200, """[{"message":"PersistedQueryNotFound"}]""").kind)
        val v = c(400, """{"errors":[{"message":"Variable '${'$'}libraryItemUris' of required type '[String!]!' was not provided."}]}""")
        assertEquals(QueryFailure.VariableError, v.kind)
        assertEquals("libraryItemUris", v.variable)
        assertEquals(QueryFailure.VariableError, c(400, """{"errors":[{"extensions":{"code":"VALIDATION_INVALID_TYPE_VARIABLE"}}]}""").kind)
        assertEquals(QueryFailure.Other, c(400, "Bad Request").kind)
        assertEquals(QueryFailure.Other, c(429, "").kind)
        assertEquals(QueryFailure.Other, c(500, "variable").kind)
    }

    @Test
    fun discoveryScansMatchingChunkFirstThenRegistry() = runBlocking {
        val base = "https://open.spotifycdn.com/cdn/build/web-player/"
        val searchHash = "a".repeat(64)
        val recentHash = "b".repeat(64)
        val regHash = "c".repeat(64)
        val pages = mapOf(
            HashDiscovery.WEB_PLAYER to """<script src="${base}web-player.abc12345.js"></script>""",
            "${base}web-player.abc12345.js" to syntheticJs,
            "${base}xpui-routes-search.96d81afe.js" to """new x.l("searchDesktop","query","$searchHash",null)""",
            "${base}xpui-routes-recent-searches.b94d0057.js" to """new x.l("recentSearches","query","$recentHash",null)""",
            HashDiscovery.COMMUNITY_REGISTRY to
                """{"operations":{"missingOp":{"hash":"$regHash","type":"query"},"searchDesktop":{"hash":"${"d".repeat(64)}"}}}""",
        )
        val fetched = ArrayList<String>()
        val d = HashDiscovery.discoverHashes(listOf("searchDesktop", "missingOp"), nowMs = { 0L }) { url ->
            fetched.add(url)
            pages[url] ?: throw HashFetchStatusError(404, url)
        }
        assertEquals("web-player.abc12345.js", d.bundle)
        assertEquals(searchHash, d.ops["searchDesktop"]?.hash) // web player wins over the registry
        assertEquals(regHash, d.ops["missingOp"]?.hash)
        assertEquals(listOf("searchDesktop"), d.fromChunks)
        assertEquals(listOf("missingOp"), d.fromRegistry)
        assertEquals(3, d.chunksScanned) // the 77 chunk 404s and is only skipped
        assertTrue(d.complete)
        assertEquals("${base}xpui-routes-search.96d81afe.js", fetched[2]) // the matching chunk first

        // Transport failure on the page: no registry fetch, nothing discovered -> throws.
        val calls = ArrayList<String>()
        try {
            HashDiscovery.discoverHashes(listOf("x")) { url ->
                calls.add(url)
                throw HashFetchTransportError("offline")
            }
            fail("expected a failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("discovery failed"))
        }
        assertEquals(listOf(HashDiscovery.WEB_PLAYER), calls)
    }

    /** Built-in table, persisted overrides, adoption from the last scan and the refresh throttle (no network). */
    @Test
    fun registryOffline() = runBlocking {
        HashRegistry.discover = { fail("no scan expected"); throw IllegalStateException() }
        val file = File.createTempFile("shadetube_spotify_test_hashes", ".json")
        file.delete()
        try {
            HashRegistry.setStorePath(file)
            for ((op, h) in HashRegistry.BUILTINS) {
                assertEquals(64, h.length)
                assertEquals(h, HashRegistry.hash(op))
            }
            assertEquals("", HashRegistry.hash("noSuchOperation"))

            val x = "1".repeat(64); val y = "2".repeat(64); val z = "3".repeat(64); val w = "4".repeat(64); val v = "5".repeat(64)
            val bogus = "00000000000000000000000000000000000000000000000000000000deadbeef"
            val now = System.currentTimeMillis() / 1000
            fun b(op: String) = HashRegistry.builtin(op)
            file.writeText(
                """{"bundle":"web-player.test0001.js","fetchedAt":$now,
                   "hashes":{"libraryV3":"$x","searchDesktop":"$y","someNewOp":"$z"},
                   "replaced":{"libraryV3":"${b("libraryV3")}","searchDesktop":"${"0".repeat(64)}"},
                   "discovered":{"getAlbum":"$w","fetchLibraryTracks":"$v","profileAttributes":"${b("profileAttributes")}"},
                   "searched":["getAlbum","fetchLibraryTracks","profileAttributes"]}""",
            )
            HashRegistry.setStorePath(file) // reload
            assertEquals("web-player.test0001.js", HashRegistry.bundle())
            assertEquals(x, HashRegistry.hash("libraryV3")) // learned against this build's built-in
            assertEquals(b("searchDesktop"), HashRegistry.hash("searchDesktop")) // learned against another: dropped
            assertEquals(z, HashRegistry.hash("someNewOp"))
            assertEquals(b("getAlbum"), HashRegistry.hash("getAlbum")) // a scan alone never overrides
            // getAlbum rejected: the (fresh) last scan knows another hash -> adopted, no network.
            assertEquals(w, HashRegistry.heal("getAlbum", b("getAlbum")))
            assertEquals(w, HashRegistry.hash("getAlbum"))
            // ...rejected again: a scan < 10 min old (on disk) already searched for it -> no new scan, nothing to retry with.
            assertEquals("", HashRegistry.heal("getAlbum", w))
            // The web player sends the very hash that was rejected -> not a rotation, no retry.
            assertEquals("", HashRegistry.heal("profileAttributes", b("profileAttributes")))
            // A second query that failed with the old hash while the first one healed reuses the result.
            assertEquals(w, HashRegistry.heal("getAlbum", b("getAlbum")))
            // setOverride is never persisted, even when another adoption saves the file.
            HashRegistry.setOverride("home", bogus)
            assertEquals(bogus, HashRegistry.hash("home"))
            assertEquals(v, HashRegistry.heal("fetchLibraryTracks", b("fetchLibraryTracks")))

            val text = file.readText()
            val saved = parseJsonOrNull(text) as JsonObject
            assertEquals(w, saved["hashes"].str("getAlbum"))
            assertEquals(b("getAlbum"), saved["replaced"].str("getAlbum"))
            assertEquals(v, saved["hashes"].str("fetchLibraryTracks"))
            assertFalse((saved["hashes"] as JsonObject).containsKey("home"))
            assertFalse(text.contains(bogus))
            assertEquals(x, saved["hashes"].str("libraryV3"))
            assertEquals(3, (saved["searched"] as JsonArray).size)
            assertEquals(now, (saved["fetchedAt"] as JsonPrimitive).jsonPrimitive.long) // no scan ran: untouched

            HashRegistry.setStorePath(file) // reload: adoptions survive a restart
            assertEquals(w, HashRegistry.hash("getAlbum"))
            assertEquals(b("home"), HashRegistry.hash("home"))
        } finally {
            file.delete()
            HashRegistry.setStorePath(null)
        }
    }

    @Test
    fun healScansWhenNothingIsKnownAndThrottles() = runBlocking {
        val file = File.createTempFile("shadetube_spotify_test_hashes2", ".json")
        file.delete()
        var scans = 0
        val fresh = "9".repeat(64)
        try {
            HashRegistry.setStorePath(file)
            HashRegistry.discover = { wanted ->
                scans++
                Discovery().apply {
                    bundle = "web-player.new00001.js"
                    complete = true
                    ops["getAlbum"] = PersistedOp("getAlbum", "query", fresh)
                    assertEquals(listOf("getAlbum"), wanted)
                }
            }
            assertEquals(fresh, HashRegistry.heal("getAlbum", HashRegistry.builtin("getAlbum")))
            assertEquals(1, scans)
            assertEquals(fresh, HashRegistry.hash("getAlbum"))
            assertEquals("web-player.new00001.js", HashRegistry.bundle())
            // The adopted hash rejected too: the fresh scan already searched for it -> no second scan.
            assertEquals("", HashRegistry.heal("getAlbum", fresh))
            assertEquals(1, scans)
            // An op the scan did not look for (and no throttle yet) is scanned; a failing scan yields "".
            HashRegistry.discover = { scans++; throw IOException("offline") }
            assertEquals("", HashRegistry.heal("home", HashRegistry.builtin("home")))
            assertEquals(2, scans)
            // ...and within 10 minutes it is throttled (no new scan).
            assertEquals("", HashRegistry.heal("home", HashRegistry.builtin("home")))
            assertEquals(2, scans)
        } finally {
            file.delete()
            HashRegistry.setStorePath(null)
        }
    }
}
