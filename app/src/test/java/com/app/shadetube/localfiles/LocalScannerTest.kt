package com.app.shadetube.localfiles

import com.app.shadetube.localfiles.TestAudio.id3
import com.app.shadetube.localfiles.TestAudio.text
import com.app.shadetube.localfiles.TestAudio.writeWav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Port of tests/localfiles (offline, over a work folder of generated files): scan (tags, embedded covers extracted
 * once, ids, sorting), the index round trip, incremental rescans, removed files, nested folders, the cap,
 * cancellation, cover pruning, an unreachable folder keeping its tracks while a deleted one loses them, walk rules,
 * file-name parsing and disc order.
 */
class LocalScannerTest {
    private lateinit var work: File
    private lateinit var music: File
    private lateinit var sub: File
    private lateinit var covers: File
    private val lister = FileFolderLister()
    private val tags = FileTagSource()

    private fun path(f: File) = LocalPaths.normalizePath(f.invariantSeparatorsPath)

    @Before
    fun setUp() {
        work = Files.createTempDirectory("ShadeTube-localfiles-test").toFile()
        music = File(work, "Müzik klasörü")
        sub = File(music, "Alt klasör")
        covers = File(work, "covers")
        sub.mkdirs()
        writeWav(File(music, "Test Sanatçı - Dalga.wav"), 2.0)
        writeWav(File(sub, "03 - Sadece Başlık.wav"), 1.5, 660.0)
        writeWav(File(music, "._gizli.wav"), 0.2) // macOS resource fork name: skipped
        File(music, "notlar.txt").writeText("not audio")
        File(music, "bos.mp3").writeBytes(ByteArray(0)) // empty: skipped
        // Tagged files with an embedded cover (the same picture: one cover file).
        val cover = TestAudio.jpeg(0x42)
        File(sub, "t_mp3.mp3").writeBytes(
            id3(
                3,
                listOf(
                    "TIT2" to text(3, "Dalga Testi"), "TPE1" to text(3, "Test Sanatçı"),
                    "TALB" to text(1, "Sentez Albüm"), "TRCK" to text(0, "3"), "TYER" to text(0, "2024"),
                    "APIC" to TestAudio.apic(3, 3, cover),
                ),
            ) + TestAudio.mp3Frames(400),
        )
        File(sub, "t_flac.flac").writeBytes(
            TestAudio.flac(
                listOf("TITLE=Dalga Testi", "ARTIST=Test Sanatçı", "ALBUM=Sentez Albüm", "TRACKNUMBER=3", "DATE=2024"),
                totalSamples = 44100L * 4, picture = cover,
            ),
        )
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    private fun scan(folders: List<String>, previous: List<LocalEntry> = emptyList(), maxFiles: Int = LocalFormats.MAX_FILES, progress: ((Int, Int) -> Unit)? = null) =
        runBlocking { LocalScanner.scan(ScanOptions(folders, covers, maxFiles, progress = progress), previous, lister, tags) }

    private fun byName(v: List<LocalEntry>, name: String) = v.firstOrNull { it.fileName == name }

    @Test
    fun scanIndexAndRescans() {
        val expected = 4
        val progressCalls = AtomicInteger()
        var lastDone = 0
        var lastTotal = 0
        val (first, st) = scan(listOf(path(music))) { d, t -> progressCalls.incrementAndGet(); lastDone = d; lastTotal = t }
        assertEquals(expected, st.files)
        assertEquals(expected, first.size)
        assertEquals(expected, st.read)
        assertEquals(0, st.reused)
        assertEquals(0, st.failed)
        assertFalse(st.capped)
        assertTrue(progressCalls.get() > 0 && lastDone == lastTotal && lastTotal == expected)
        val dalga = byName(first, "Test Sanatçı - Dalga.wav")!!
        assertEquals("Dalga", dalga.title)
        assertEquals(listOf("Test Sanatçı"), dalga.artists)
        assertFalse(dalga.tagged)
        assertTrue(dalga.durationMs in 1900..2100)
        val only = byName(first, "03 - Sadece Başlık.wav")!!
        assertEquals("Sadece Başlık", only.title)
        assertEquals(3, only.trackNumber)
        assertTrue(only.artists.isEmpty())
        assertNull(byName(first, "._gizli.wav"))
        assertNull(byName(first, "bos.mp3"))
        assertNull(byName(first, "notlar.txt"))
        for (n in listOf("t_mp3.mp3", "t_flac.flac")) {
            val e = byName(first, n)!!
            assertTrue(n, e.tagged && e.title == "Dalga Testi")
            assertEquals("Sentez Albüm", e.album)
            assertEquals(3, e.trackNumber)
            assertEquals(2024, e.year)
            assertTrue(n, e.embeddedCover && File(e.cover).exists())
        }
        assertTrue(byName(first, "t_flac.flac")!!.durationMs in 3900..4100)
        // One cover file per distinct picture.
        assertEquals(1, covers.listFiles()!!.count { it.isFile })

        // ids + tracks
        for (e in first) {
            val t = LocalFormats.toTrack(e)
            assertEquals(LocalFormats.trackIdFor(e.path), t.id)
            assertEquals(6 + 16, t.id.length)
            assertTrue(t.album.id.isEmpty() && t.artists.all { it.id.isEmpty() })
            if (e.cover.isNotEmpty()) assertTrue(t.album.images[0].url.startsWith("file:///"))
        }

        // Index round trip.
        val indexFile = File(work, "local-library.json")
        assertTrue(LocalIndex.save(indexFile, first, IndexMeta(1234, listOf(path(music)), false)))
        val (loaded, meta) = LocalIndex.load(indexFile)
        assertEquals(1234L, meta.scannedAt)
        assertEquals(listOf(path(music)), meta.folders)
        assertFalse(meta.capped)
        assertEquals(first, loaded)

        // Rescan, nothing changed: nothing re-read.
        progressCalls.set(0)
        val (second, st2) = scan(listOf(path(music)), loaded) { _, _ -> progressCalls.incrementAndGet() }
        assertEquals(expected, st2.files)
        assertEquals(expected, st2.reused)
        assertEquals(0, st2.read)
        assertEquals(0, st2.removed)
        assertEquals(0, progressCalls.get())
        assertEquals(first.map { LocalFormats.toTrack(it).id }, second.map { LocalFormats.toTrack(it).id })

        // One file changed (new size + mtime).
        val changed = File(music, "Test Sanatçı - Dalga.wav")
        writeWav(changed, 3.0)
        changed.setLastModified(changed.lastModified() + 5000)
        val (third, st3) = scan(listOf(path(music)), second)
        assertEquals(1, st3.read)
        assertEquals(expected - 1, st3.reused)
        assertTrue(byName(third, changed.name)!!.durationMs in 2900..3100)

        // One file removed.
        val goneId = LocalFormats.trackIdFor(path(File(sub, "03 - Sadece Başlık.wav")))
        File(sub, "03 - Sadece Başlık.wav").delete()
        val (fourth, st4) = scan(listOf(path(music)), third)
        assertEquals(1, st4.removed)
        assertEquals(expected - 1, st4.files)
        assertEquals(0, st4.read)
        assertTrue(fourth.none { LocalFormats.trackIdFor(it.path) == goneId })

        // Nested / duplicate folders don't duplicate.
        val (fifth, st5) = scan(listOf(path(music), path(sub), path(music) + "/"), fourth)
        assertEquals(expected - 1, st5.files)

        // The cap.
        val (_, capped) = scan(listOf(path(music)), maxFiles = 2)
        assertTrue(capped.capped)
        assertEquals(2, capped.files)

        // Cover pruning: covers no entry uses go; embedded covers whose file vanished are re-read.
        val noCovers = fifth.filter { !it.embeddedCover }
        val withCover = fifth.size - noCovers.size
        assertEquals(1, LocalIndex.pruneCovers(covers, noCovers))
        assertEquals(0, covers.listFiles()!!.count { it.isFile })
        val (_, st6) = scan(listOf(path(music)), fifth)
        assertEquals(withCover, st6.read)
    }

    @Test
    fun cancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Requested while the readers run: the scan stops and its result is never used.
        var job: Job? = null
        var finished = false
        var cancelled = false
        job = scope.launch {
            try {
                LocalScanner.scan(
                    ScanOptions(listOf(path(music)), covers, progress = { d, _ -> if (d >= 1) job?.cancel() }),
                    emptyList(), lister, tags,
                )
                finished = true
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        job.join()
        assertTrue(cancelled)
        assertFalse(finished)
    }

    @Test
    fun unreachableFolderKeepsItsTracks() {
        val (indexedHere, _) = scan(listOf(path(music)))
        // A volume that doesn't exist stands in for a removed SD card.
        val offlineRoot = "/zz-shadetube-missing-volume/Müzik"
        val indexed = indexedHere.map { it.copy(path = offlineRoot + it.path.substring(path(music).length)) }
        val (kept, st) = scan(listOf(offlineRoot), indexed)
        assertTrue(st.incomplete)
        assertEquals(1, st.missingRoots.size)
        assertEquals(0, st.removed)
        assertEquals(0, st.read)
        assertEquals(indexed.size, st.carried)
        assertEquals(indexed.size, kept.size)
        for (e in indexed) assertTrue(kept.any { it.path == e.path && it.title == e.title && it.cover == e.cover })
    }

    @Test
    fun deletedFolderLosesItsTracks() {
        val doomed = File(work, "silinecek")
        writeWav(File(doomed, "A - B.wav"), 0.5)
        val (before, _) = scan(listOf(path(doomed)))
        assertEquals(1, before.size)
        doomed.deleteRecursively()
        val (after, st) = scan(listOf(path(doomed)), before)
        assertTrue(after.isEmpty())
        assertEquals(1, st.removed)
        assertFalse(st.incomplete)
        assertTrue(st.missingRoots.isEmpty())
    }

    @Test
    fun walkRulesAndFileNames() {
        val walk = File(work, "walk")
        File(walk, "alt").mkdirs()
        writeWav(File(File(walk, ".gizli"), "Gizli - Parça.wav"), 0.3)
        data class NameCase(val file: String, val title: String, val artist: String, val track: Int)
        val names = listOf(
            NameCase("Queen – Bohemian Rhapsody - Remastered 2011.wav", "Bohemian Rhapsody - Remastered 2011", "Queen", 0),
            NameCase("01 – Başlık Bir.wav", "Başlık Bir", "", 1),
            NameCase("2 – Başlık İki.wav", "Başlık İki", "", 2),
            NameCase("07_Yedi.wav", "Yedi", "", 7),
            NameCase("1. Intro.wav", "Intro", "", 1),
            NameCase("50 Cent - In Da Club.wav", "In Da Club", "50 Cent", 0),
            NameCase("³ - Outro.wav", "Outro", "³", 0), // superscript 3: not a track number, and not dropped
        )
        for (n in names) writeWav(File(File(walk, "alt"), n.file), 0.2, 330.0)
        val (walked, _) = scan(listOf(path(walk)))
        assertEquals(names.size, walked.size) // only alt/: no "." folder
        for (n in names) {
            val e = byName(walked, n.file)
            assertNotNull(n.file, e)
            assertEquals(n.file, n.title, e!!.title)
            assertEquals(n.file, if (n.artist.isEmpty()) emptyList() else listOf(n.artist), e.artists)
            assertEquals(n.file, n.track, e.trackNumber)
        }
        assertTrue(LocalPaths.walkedFrom(path(walk), path(File(walk, "alt"))))
        assertFalse(LocalPaths.walkedFrom(path(walk), path(File(walk, ".gizli"))))
    }

    @Test
    fun discOrderAndFolderArt() {
        val discs = File(work, "diskler")
        writeWav(File(File(discs, "CD2"), "05 - Klasörden.wav"), 0.2) // no tags: the disc comes from "CD2"
        File(File(discs, "CD2"), "folder.jpg").writeBytes(TestAudio.jpeg())
        File(File(discs, "CD2"), "cover.png").writeBytes(TestAudio.png())
        fun tagged(title: String, disc: String, track: String) = id3(
            3,
            listOf("TIT2" to text(3, title), "TPE1" to text(3, "Disk Test"), "TPE2" to text(3, "Disk Test"), "TALB" to text(3, "İki Disk"), "TPOS" to text(0, disc), "TRCK" to text(0, track)),
        ) + TestAudio.mp3Frames(10)
        File(discs, "a.mp3").writeBytes(tagged("Disk2 Parça1", "2/2", "1"))
        File(discs, "b.mp3").writeBytes(tagged("Disk1 Parça2", "1/2", "2"))
        File(discs, "c.mp3").writeBytes(tagged("Disk1 Parça1", "1/2", "1"))
        val (sorted, _) = scan(listOf(path(discs)))
        val folderTrack = byName(sorted, "05 - Klasörden.wav")!!
        assertEquals(2, folderTrack.discNumber)
        assertTrue(folderTrack.cover.endsWith("cover.png")) // cover.* before folder.*
        assertFalse(folderTrack.embeddedCover)
        assertEquals(listOf("c.mp3", "b.mp3", "a.mp3"), sorted.filter { it.album == "İki Disk" }.map { it.fileName })
    }

    @Test
    fun sortOrder() {
        fun e(path: String, artist: String = "", album: String = "", title: String = "", disc: Int = 0, track: Int = 0) =
            LocalEntry(path = path, artists = if (artist.isEmpty()) emptyList() else listOf(artist), album = album, title = title, discNumber = disc, trackNumber = track)
        val list = listOf(
            e("/z", title = "untagged"),
            e("/b", "beta", "Album 10", "x"),
            e("/a", "Beta", "Album 9", "y"), // case ignored, digits as numbers: 9 before 10
            e("/c", "alpha", "A", "t2", disc = 1, track = 2),
            e("/d", "alpha", "A", "t1", disc = 0, track = 1), // untagged disc = disc 1
            e("/e", "alpha", "A", "t0", disc = 2, track = 1),
        )
        assertEquals(listOf("/d", "/c", "/e", "/a", "/b", "/z"), LocalScanner.sortEntries(list).map { it.path })
    }

    @Test
    fun discFromFolderNames() {
        assertEquals(2, LocalScanner.discFromFolder("CD2"))
        assertEquals(2, LocalScanner.discFromFolder("CD 2"))
        assertEquals(2, LocalScanner.discFromFolder("Disc 2"))
        assertEquals(2, LocalScanner.discFromFolder("Disk 2 - Bonus"))
        assertEquals(0, LocalScanner.discFromFolder("Album"))
    }
}
