package com.app.shadetube.localfiles

import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.localfiles.OpenedFilesRules.DropRow
import com.app.shadetube.localfiles.TestAudio.writeWav
import kotlinx.coroutines.coroutineScope
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

/** Port of the "dropped from Explorer" part of tests/localfiles, as files opened from other apps. */
class OpenedFilesTest {
    private lateinit var work: File
    private lateinit var drop: File
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AAlbum"

    private fun path(f: File) = LocalPaths.normalizePath(f.invariantSeparatorsPath)

    /** Stats plain files; the fake tree URI is a folder of the work dir. */
    private val source = object : OpenedFileSource {
        val retained = ArrayList<String>()
        override fun stat(item: String): FoundFile? {
            val f = File(item)
            return if (f.isFile) FoundFile(path(f), "", f.name, f.length(), f.lastModified()) else null
        }
        override fun retain(item: String) {
            retained += item
        }
    }

    private fun lister() = object : FolderLister {
        override fun walk(folder: String, onFile: (FoundFile) -> Boolean): WalkResult =
            if (folder == treeUri) FileFolderLister().walk(path(File(drop, "Albüm")), onFile) else WalkResult(listOf(folder), true)
    }

    private fun opened(scope: kotlinx.coroutines.CoroutineScope) =
        OpenedFiles(File(work, "dropped-files.json"), File(work, "dropped-covers"), source, { lister() }, FileTagSource(), PathFileOpener(), scope)

    @Before
    fun setUp() {
        work = Files.createTempDirectory("opened").toFile()
        drop = File(work, "dropped")
        writeWav(File(File(File(drop, "Albüm"), "CD1"), "02 - İkinci.wav"), 0.3, 330.0)
        writeWav(File(File(File(drop, "Albüm"), "CD1"), "01 - Birinci.wav"), 0.3, 330.0)
        writeWav(File(drop, "Tek - Şarkı.wav"), 0.3)
        File(drop, "notlar.txt").writeText("not audio")
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    @Test
    fun collect() = runBlocking {
        assertTrue(OpenedFilesRules.droppable(File(drop, "Tek - Şarkı.wav").path))
        assertTrue(OpenedFilesRules.droppable(treeUri))
        assertFalse(OpenedFilesRules.droppable(File(drop, "notlar.txt").path))
        assertTrue(OpenedFilesRules.droppable(File(drop, "yok.wav").path)) // by its extension: the disk isn't asked

        val of = opened(this)
        val single = File(drop, "Tek - Şarkı.wav").path
        // A loose file, a folder, the same file again, something that isn't audio, a ghost.
        val r = of.collect(listOf(File(drop, "notlar.txt").path, treeUri, single, single, File(drop, "gone.wav").path))
        assertEquals(3, r.entries.size)
        assertEquals(listOf(treeUri), r.folders)
        assertEquals(2, r.skipped) // notlar.txt + gone.wav
        assertFalse(r.capped)
        // Untagged: "Tek - Şarkı" has an artist (sorts first); the album files by track number from their names.
        assertEquals("Şarkı", r.entries[0].title)
        assertEquals(listOf("Tek"), r.entries[0].artists)
        assertEquals("01 - Birinci.wav", r.entries[1].fileName)
        assertEquals("02 - İkinci.wav", r.entries[2].fileName)
        assertEquals(1, r.entries[1].discNumber) // "CD1"
        val few = of.collect(listOf(treeUri, single), maxFiles = 2)
        assertEquals(2, few.entries.size)
        assertTrue(few.capped)
        assertTrue(of.collect(listOf(File(drop, "notlar.txt").path)).entries.isEmpty())
    }

    @Test
    fun rememberedList() {
        fun entry(p: String) = LocalEntry(path = p)
        var list = OpenedFilesRules.mergeRemembered(emptyList(), listOf(entry("/m/a.mp3"), entry("/m/b.mp3"), entry("/m/c.mp3")), 3)
        list = OpenedFilesRules.mergeRemembered(list, listOf(entry("/m/A.MP3"), entry("/m/d.mp3"), entry("/m/d.mp3")), 3)
        // b fell off the end, a moved behind c (with its new spelling), d once.
        assertEquals(listOf("/m/c.mp3", "/m/A.MP3", "/m/d.mp3"), list.map { it.path })
    }

    @Test
    fun storeRoundTrip() = runBlocking {
        val single = File(drop, "Tek - Şarkı.wav").path
        var id = ""
        coroutineScope {
            val of = opened(this)
            val r = of.collect(listOf(single))
            id = LocalFormats.trackIdFor(r.entries[0].path)
            of.remember(r.entries)
            assertNotNull(of.fileFor(id)) // known at once
        }
        coroutineScope {
            val again = opened(this)
            assertNull(again.fileFor(id))
            again.load()
            val f = again.fileFor(id)
            assertNotNull(f)
            assertEquals("Tek - Şarkı.wav", f!!.name)
            assertEquals("audio/wav", f.mimeType)
            assertNotNull(again.reachableFileFor(id))
            File(single).delete()
            assertNull(again.reachableFileFor(id)) // gone: the resolver moves on
            assertNotNull(again.fileFor(id))
            val resolver = LocalFileResolver(null, again)
            assertEquals(f.uri, resolver.fileFor(id)!!.uri) // falls back to the remembered entry
            assertNull(resolver.fileFor("spotify:track:x"))
        }
    }

    @Test
    fun dropRules() {
        fun tr(id: String) = Track(id = id, name = id, artists = listOf(ArtistRef("", "a")))
        val mixed = listOf(tr("spotify:track:1"), tr("local:ab"), tr("radio:x"), tr(""), tr("11111111-1111-1111-1111-111111111111"))
        val onlySpotify = { id: String -> id.startsWith("spotify:track:") }
        assertEquals(1, OpenedFilesRules.droppableOn(DropRow.SpotifyPlaylist, mixed, onlySpotify).size)
        assertEquals(1, OpenedFilesRules.droppableOn(DropRow.Liked, mixed, onlySpotify).size)
        assertEquals(3, OpenedFilesRules.droppableOn(DropRow.Liked, mixed) { true }.size)
        assertEquals(3, OpenedFilesRules.droppableOn(DropRow.LocalPlaylist, mixed, onlySpotify).size) // never stations / no id
        assertTrue(OpenedFilesRules.droppableOn(DropRow.Liked, mixed, null).isEmpty())
        // Queue: current slot 2 of 6; rows of 52 below the list top.
        assertEquals(3, OpenedFilesRules.queueDropIndex(-40f, 52f, 2, 6)) // above the list: right after the current track
        assertEquals(3, OpenedFilesRules.queueDropIndex(20f, 52f, 2, 6))
        assertEquals(4, OpenedFilesRules.queueDropIndex(30f, 52f, 2, 6)) // past the first row's middle
        assertEquals(6, OpenedFilesRules.queueDropIndex(1000f, 52f, 2, 6)) // below everything: the end
        assertEquals(0, OpenedFilesRules.queueDropIndex(10f, 52f, -1, 0)) // empty queue
        assertEquals(6, OpenedFilesRules.queueDropIndex(10f, 0f, 2, 6))
    }
}
