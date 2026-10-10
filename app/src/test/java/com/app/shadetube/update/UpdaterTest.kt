package com.app.shadetube.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * Port of tests/updater (offline parts): versions, release JSON (asset digest, notes sha256, asset choice — for the
 * APK), plus the SHA-256 checks, the signing-certificate rule and the ranged / resumed download on a local server.
 */
class UpdaterTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("update").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun throwsUpdate(error: UpdateError, needle: String = "", block: () -> Unit) {
        try {
            block()
            fail("expected $error")
        } catch (e: UpdateException) {
            assertEquals(error, e.error)
            assertTrue("'${e.message}' should mention '$needle'", e.message.orEmpty().contains(needle))
        }
    }

    @Test
    fun versions() {
        val v = Versions.parse("v0.4.0")!!
        assertEquals(Version(0, 4, 0, ""), v)
        assertEquals(Version(1, 2, 3, "beta.1"), Versions.parse(" 1.2.3-beta.1+build.7 "))
        assertEquals(0, Versions.parse("0.4")!!.patch)
        for (bad in listOf("", "v", "abc", "1..2", "1.2.", "1.2.3.4", "1.2.3-", "-1.2", "1.x")) assertNull("\"$bad\"", Versions.parse(bad))
        assertTrue(Versions.compare("0.4.0", "0.3.0") > 0)
        assertEquals(0, Versions.compare("v0.3.0", "0.3.0"))
        assertEquals(0, Versions.compare("0.3", "0.3.0"))
        assertTrue(Versions.compare("v0.10.0", "0.9.9") > 0)
        assertTrue(Versions.compare("1.0.0", "0.99.99") > 0)
        assertTrue(Versions.compare("0.3.1", "0.3.0") > 0)
        assertEquals(0, Versions.compare("1.0.0+abc", "1.0.0"))
        // semver.org §11 example chain.
        val chain = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        for (i in 0 until chain.size - 1) {
            assertTrue("${chain[i]} < ${chain[i + 1]}", Versions.compare(chain[i], chain[i + 1]) < 0)
            assertTrue("${chain[i + 1]} > ${chain[i]}", Versions.compare(chain[i + 1], chain[i]) > 0)
        }
        assertTrue(Versions.compare("garbage", "0.0.1") < 0)
        assertEquals("0.4.0", Versions.normalize("v0.4"))
        assertEquals("1.2.3-rc.1", Versions.normalize("V1.2.3-rc.1+x"))
        assertEquals("next", Versions.normalize("vnext"))
    }

    /** tests/updater/fixtures/release_latest.json with the Android package added. */
    private val releaseJson = """{
  "url": "https://api.github.com/repos/shadesofdeath/ShadeTubeAndroid/releases/250000001",
  "html_url": "https://github.com/shadesofdeath/ShadeTubeAndroid/releases/tag/v0.4.0",
  "id": 250000001,
  "tag_name": "v0.4.0",
  "name": "ShadeTube 0.4.0",
  "draft": false,
  "prerelease": false,
  "published_at": "2026-10-05T09:20:01Z",
  "assets": [
    { "name": "ShadeTube-0.4.0-android.apk.sha256", "size": 101,
      "digest": "sha256:1111111111111111111111111111111111111111111111111111111111111111",
      "browser_download_url": "https://github.com/shadesofdeath/ShadeTubeAndroid/releases/download/v0.4.0/ShadeTube-0.4.0-android.apk.sha256" },
    { "name": "ShadeTube-0.4.0-win64.zip", "size": 2031874, "digest": null,
      "browser_download_url": "https://github.com/shadesofdeath/ShadeTubeAndroid/releases/download/v0.4.0/ShadeTube-0.4.0-win64.zip" },
    { "name": "ShadeTube-0.4.0-android.apk", "label": "", "content_type": "application/vnd.android.package-archive", "size": 7340032,
      "digest": "sha256:9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08",
      "browser_download_url": "https://github.com/shadesofdeath/ShadeTubeAndroid/releases/download/v0.4.0/ShadeTube-0.4.0-android.apk" },
    { "id": 300000004, "name": "broken-asset-without-url", "size": 1 }
  ],
  "body": "## Yenilikler\r\n- Uygulama içinden güncelleme ve kurulum.\r\n\r\nsha256: 3A7BD3E2360A3D29EEA436FCFB7E44C735D117C42D1C1835420B6B9942DD4F1B\r\nSHA256 (eski paket): kısa-değil\r\nBaşka bir satır: 0000000000000000000000000000000000000000000000000000000000000000\r\n"
}"""

    @Test
    fun releaseJson() {
        val r = Releases.parse(releaseJson)
        assertEquals("v0.4.0", r.tag)
        assertEquals("0.4.0", r.version)
        assertEquals("ShadeTube 0.4.0", r.name)
        assertEquals("https://github.com/shadesofdeath/ShadeTubeAndroid/releases/tag/v0.4.0", r.htmlUrl)
        assertEquals("2026-10-05T09:20:01Z", r.publishedAt)
        assertEquals(3, r.assets.size) // the asset without a download URL is dropped
        val a = Releases.pickAsset(r)!!
        assertEquals("ShadeTube-0.4.0-android.apk", a.name)
        assertEquals(7340032L, a.size)
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", a.sha256)
        assertEquals("https://github.com/shadesofdeath/ShadeTubeAndroid/releases/download/v0.4.0/ShadeTube-0.4.0-android.apk", a.url)
        assertEquals(listOf("3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b"), r.notesSha256)
        assertTrue(r.body.contains("Yenilikler"))
        assertEquals("", r.assets[1].sha256) // "digest": null

        // Asset choice without the exact name: an .apk naming android, else any .apk; never zips / checksum files.
        var other = Release("v0.5.0", "0.5.0", assets = listOf(Asset("ShadeTube-win64.zip", "u1", 1), Asset("ShadeTube.apk", "u2", 1), Asset("checksums.txt", "u3", 1)))
        assertEquals("ShadeTube.apk", Releases.pickAsset(other)!!.name)
        other = other.copy(assets = other.assets + Asset("ShadeTube-android-universal.apk", "u4", 1) + Asset("ShadeTube-android-2.apk", "u5", 1))
        assertEquals("ShadeTube-android-universal.apk", Releases.pickAsset(other)!!.name)
        other = other.copy(assets = other.assets + Asset("ShadeTube-0.5.0-Android.APK", "u6", 1))
        assertEquals("ShadeTube-0.5.0-Android.APK", Releases.pickAsset(other)!!.name)
        assertNull(Releases.pickAsset(other.copy(assets = listOf(Asset("notes.txt", "u", 1), Asset("ShadeTube-0.5.0-win64.zip", "u", 1)))))

        assertEquals(listOf("a".repeat(64)), Releases.findSha256("SHA256: " + "A".repeat(64) + "\nfoo " + "b".repeat(64)))
        assertEquals(listOf("c".repeat(64)), Releases.findSha256("sha256 (ShadeTube-0.4.0-android.apk) = " + "c".repeat(64) + " " + "d".repeat(65)))
        throwsUpdate(UpdateError.BadRelease, "parse") { Releases.parse("{not json") }
        throwsUpdate(UpdateError.BadRelease, "tag_name") { Releases.parse("""{"name":"x"}""") }
        throwsUpdate(UpdateError.BadRelease, "tag_name") { Releases.parse("[1,2]") }
        assertEquals("https://api.github.com/repos/shadesofdeath/ShadeTubeAndroid/releases/latest", Releases.LATEST_URL)
    }

    @Test
    fun digests() {
        val f = File(dir, "x.apk")
        f.writeText("test")
        val hash = ApkCheck.sha256File(f)
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", hash) // sha256("test")
        val r = Releases.parse(releaseJson)
        val asset = Releases.pickAsset(r)!!
        // The asset digest matches, the notes name another file.
        throwsUpdate(UpdateError.NotesDigestMismatch) { ApkCheck.checkDigest(hash, asset, r) }
        ApkCheck.checkDigest(hash, asset, r.copy(notesSha256 = listOf(hash)))
        ApkCheck.checkDigest(hash, asset, r.copy(notesSha256 = emptyList()))
        throwsUpdate(UpdateError.AssetDigestMismatch) { ApkCheck.checkDigest("0".repeat(64), asset, r.copy(notesSha256 = emptyList())) }
        // No digests at all: nothing to compare against (the APK checks still apply).
        ApkCheck.checkDigest("0".repeat(64), asset.copy(sha256 = ""), r.copy(notesSha256 = emptyList()))
    }

    @Test
    fun signers() {
        val a = "cert-A".toByteArray()
        val b = "cert-B".toByteArray()
        val c = "cert-C".toByteArray()
        assertTrue(ApkCheck.signersMatch(listOf(a), listOf(a), listOf(a)))
        assertFalse(ApkCheck.signersMatch(listOf(a), listOf(b), listOf(b)))
        // Key rotation: the new APK's history contains the installed certificate.
        assertTrue(ApkCheck.signersMatch(listOf(a), listOf(b), listOf(a, b)))
        assertFalse(ApkCheck.signersMatch(listOf(a), listOf(c), listOf(b, c)))
        // Several signers must be the same set.
        assertTrue(ApkCheck.signersMatch(listOf(a, b), listOf(b, a), emptyList()))
        assertFalse(ApkCheck.signersMatch(listOf(a, b), listOf(a), emptyList()))
        assertFalse(ApkCheck.signersMatch(emptyList(), listOf(a), emptyList()))
        assertFalse(ApkCheck.signersMatch(listOf(a), emptyList(), emptyList()))
    }

    // ---- ranged download ----------------------------------------------------------------------------------------------

    private val apk = ByteArray(2_600_000) { (it * 7 + 3).toByte() }

    private fun server(mode: String, requests: MutableList<String>): MockWebServer {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range").orEmpty()
                requests.add(range)
                assertEquals("identity", request.getHeader("Accept-Encoding"))
                assertTrue(request.getHeader("User-Agent")!!.startsWith("ShadeTube/"))
                if (mode == "whole") return MockResponse().setBody(Buffer().write(apk))
                val first = range.removePrefix("bytes=").substringBefore('-').toLong()
                val last = minOf(range.substringAfter('-').toLong(), apk.size - 1L)
                if (first >= apk.size) return MockResponse().setResponseCode(416)
                val sentFirst = if (mode == "wrong") first + 1 else first
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $sentFirst-$last/${apk.size}")
                    .setBody(Buffer().write(apk.copyOfRange(first.toInt(), last.toInt() + 1)))
            }
        }
        s.start()
        return s
    }

    @Test
    fun rangedDownloadResumesAndHandlesWholeBodies() = runBlocking {
        val client = OkHttpClient()
        val requests = Collections.synchronizedList(ArrayList<String>())
        var s = server("ranged", requests)
        try {
            val out = File(dir, "a.apk.part")
            var last = 0L to 0L
            RangedDownload.download(client, s.url("/a.apk").toString(), out, 0, "ShadeTube/0.1.0") { d, t -> last = d to t }
            assertArrayEquals(apk, out.readBytes())
            assertEquals(listOf("bytes=0-1048575", "bytes=1048576-2097151", "bytes=2097152-3145727"), requests)
            assertEquals(apk.size.toLong() to apk.size.toLong(), last)
            // Resume from a partial file.
            requests.clear()
            out.writeBytes(apk.copyOf(2_000_000))
            RangedDownload.download(client, s.url("/a.apk").toString(), out, 2_000_000, "ShadeTube/0.1.0") { _, _ -> }
            assertArrayEquals(apk, out.readBytes())
            assertEquals(listOf("bytes=2000000-3048575"), requests)
            // An already complete file: the server answers 416.
            requests.clear()
            RangedDownload.download(client, s.url("/a.apk").toString(), out, apk.size.toLong(), "ShadeTube/0.1.0") { _, _ -> }
            assertArrayEquals(apk, out.readBytes())
        } finally {
            s.shutdown()
        }
        // A server that ignores Range: the 200 body is the whole file (even when resuming).
        s = server("whole", requests)
        try {
            val out = File(dir, "b.apk.part")
            out.writeBytes(ByteArray(10))
            RangedDownload.download(client, s.url("/b.apk").toString(), out, 10, "ShadeTube/0.1.0") { _, _ -> }
            assertArrayEquals(apk, out.readBytes())
        } finally {
            s.shutdown()
        }
        // A chunk that does not start where we asked: refused.
        s = server("wrong", requests)
        try {
            throwsUpdate(UpdateError.Download, "unexpected range") {
                runBlocking { RangedDownload.download(client, s.url("/c.apk").toString(), File(dir, "c.part"), 0, "ShadeTube/0.1.0") { _, _ -> } }
            }
        } finally {
            s.shutdown()
        }
        assertNotNull(client)
    }
}
