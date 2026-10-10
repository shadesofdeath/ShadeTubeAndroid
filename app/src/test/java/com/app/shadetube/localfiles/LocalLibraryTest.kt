package com.app.shadetube.localfiles

import com.app.shadetube.localfiles.TestAudio.id3
import com.app.shadetube.localfiles.TestAudio.text
import com.app.shadetube.localfiles.TestAudio.writeWav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** The LocalLibrary service over plain folders: index load, rescans, lookups, groups, folder add / remove. */
class LocalLibraryTest {
    private lateinit var work: File
    private lateinit var a: File
    private lateinit var b: File
    private var folders = listOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun path(f: File) = LocalPaths.normalizePath(f.invariantSeparatorsPath)

    private fun library() = LocalLibrary(
        indexFile = File(work, "local-library.json"),
        coverDir = File(work, "local-covers"),
        listerFactory = { FileFolderLister() },
        tagSource = FileTagSource(),
        opener = PathFileOpener(),
        readFolders = { folders },
        writeFolders = { change -> folders = change(folders) },
        scope = scope,
        startupScanDelayMs = 60_000,
    )

    @Before
    fun setUp() {
        work = Files.createTempDirectory("library").toFile()
        a = File(work, "A")
        b = File(work, "B")
        fun tagged(title: String, artist: String, album: String, track: Int, year: String, gain: String? = null) = id3(
            3,
            listOfNotNull(
                "TIT2" to text(3, title), "TPE1" to text(3, artist), "TALB" to text(3, album), "TRCK" to text(0, "$track"),
                "TYER" to text(0, year), gain?.let { "TXXX" to TestAudio.txxx(0, "REPLAYGAIN_TRACK_GAIN", it) },
            ),
        ) + TestAudio.mp3Frames(20)
        a.mkdirs()
        File(a, "1.mp3").writeBytes(tagged("Bir", "Mavi Kuzey", "Albüm", 1, "2023", "-6.50 dB"))
        File(a, "2.mp3").writeBytes(tagged("İki", "Mavi Kuzey; Konuk", "Albüm", 2, "2023"))
        writeWav(File(b, "Başka - Şarkı.wav"), 0.3)
        folders = listOf(path(a))
    }

    @After
    fun tearDown() {
        scope.cancel()
        work.deleteRecursively()
    }

    @Test
    fun lifecycle() = runBlocking {
        withTimeout(30_000) {
            val lib = library()
            lib.start()
            lib.flow.first { it.loaded }
            assertTrue(lib.current.tracks.isEmpty())
            lib.rescan()
            val s = lib.current
            assertFalse(s.scanning)
            assertEquals(2, s.tracks.size)
            assertEquals(s.tracks, lib.tracks.value)
            assertNotNull(s.lastStats)
            assertTrue(s.lastScanAt > 0)
            assertEquals(listOf("Bir", "İki"), s.tracks.map { it.name })
            assertEquals(listOf("Mavi Kuzey", "Konuk"), s.tracks[1].artists.map { it.name })
            assertTrue(lib.scannedFolder(path(a)))
            assertEquals(2, lib.countUnder(path(a)))

            // Lookups for the player, lyrics and normalisation.
            val id = s.tracks[0].id
            val f = lib.fileFor(id)!!
            assertEquals("1.mp3", f.name)
            assertEquals("audio/mpeg", f.mimeType)
            assertEquals(-6.5f, lib.replayGainDb(id)!!, 1e-4f)
            assertNull(lib.replayGainDb(s.tracks[1].id))
            assertNotNull(lib.audioFileFor(id)!!.open()?.also { it.close() })
            assertNull(lib.fileFor("local:0000000000000000"))
            assertNull(lib.fileFor("spotify:track:x"))

            // Groups.
            val albums = lib.albums()
            assertEquals(1, albums.size)
            assertEquals("Albüm", albums[0].title)
            assertEquals(2023, albums[0].year)
            assertEquals("Mavi Kuzey", albums[0].artist)
            assertEquals(listOf(0, 1), albums[0].trackIndices)
            val artists = lib.artists()
            assertEquals(listOf("Konuk", "Mavi Kuzey"), artists.map { it.title })
            assertEquals(2, artists[1].trackIndices.size)
            assertEquals(1, LocalGroups.filter(albums, "albu").size)
            assertEquals(1, LocalGroups.filter(albums, "2023").size)
            assertEquals(0, LocalGroups.filter(albums, "yok").size)
            assertEquals(2, LocalGroups.tracksOf(albums + artists, s.tracks).size) // each once
            assertEquals(1, LocalGroups.filterTracks(s.tracks, "iki").size) // Turkish İ folds to i

            // A second instance reads the index at once.
            val again = library()
            again.start()
            again.flow.first { it.loaded }
            assertEquals(s.tracks, again.current.tracks)

            // Adding a folder (it rescans); a folder inside a listed one is refused.
            assertTrue(lib.addFolder(path(b)))
            assertFalse(lib.addFolder(path(b) + "/"))
            lib.rescan()
            assertEquals(3, lib.current.tracks.size)
            assertTrue(lib.coversFolder(path(b)))
            // Removing it: its tracks leave at once.
            lib.removeFolder(path(b))
            assertEquals(listOf(path(a)), folders)
            assertEquals(2, lib.current.tracks.size)
            lib.rescan()
            assertEquals(2, lib.current.tracks.size)

            // An unreachable listed folder: reported, its tracks kept.
            folders = folders + "/zz-shadetube-missing-volume/Music"
            lib.rescan()
            assertTrue(lib.folderUnavailable("/zz-shadetube-missing-volume/Music"))
            assertEquals(2, lib.current.tracks.size)
        }
    }
}
