package com.app.shadetube.localfiles

import com.app.shadetube.catalog.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folder / path rules (port of the "folder helpers" and "add / remove rules" parts of tests/localfiles, for Android). */
class LocalPathsTest {
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    private val treeRock = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FRock"
    private val docInRock = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FRock%2Fa.mp3"

    @Test
    fun normalize() {
        assertEquals("/storage/emulated/0/Müzik", LocalPaths.normalizeFolder("/storage/emulated/0/Müzik/"))
        assertEquals("/storage/emulated/0/Müzik/Pop", LocalPaths.normalizeFolder("/storage/emulated/0/Müzik/Rock/../Pop"))
        assertEquals("C:/Müzik", LocalPaths.normalizeFolder("C:\\Müzik\\"))
        assertEquals("D:/", LocalPaths.normalizeFolder("D:\\"))
        assertEquals("/", LocalPaths.normalizeFolder("/"))
        assertEquals("Music/Rock", LocalPaths.normalizeFolder("Music/Rock/"))
        assertEquals(tree, LocalPaths.normalizeFolder(" $tree "))
        assertEquals("", LocalPaths.normalizeFolder("/storage/K\uD800t")) // an unpaired surrogate can't be stored
        assertEquals("", LocalPaths.normalizeFolder(""))
        assertEquals("", LocalPaths.normalizeFolder("content://media/external/audio/media/5")) // not a folder
    }

    @Test
    fun canonicalAndUnder() {
        assertEquals("/storage/emulated/0/Music", LocalPaths.canonical(tree))
        assertEquals("/storage/emulated/0/Music/Rock/a.mp3", LocalPaths.canonical(docInRock))
        assertEquals("/storage/1234-ABCD/Müzik", LocalPaths.canonical("content://com.android.externalstorage.documents/tree/1234-ABCD%3AM%C3%BCzik"))
        assertEquals("saf://com.other.provider/abc/def", LocalPaths.canonical("content://com.other.provider/tree/abc%2Fdef"))
        assertEquals("/storage/emulated/0/a b.mp3", LocalPaths.canonical("file:///storage/emulated/0/a%20b.mp3"))

        assertTrue(LocalPaths.isUnder("/storage/emulated/0/Müzik/Rock/a.mp3", "/storage/emulated/0/müzik"))
        assertTrue(LocalPaths.isUnder("/storage/emulated/0/Müzik", "/storage/emulated/0/Müzik"))
        assertFalse(LocalPaths.isUnder("/storage/emulated/0/Müzik2/a.mp3", "/storage/emulated/0/Müzik"))
        assertTrue(LocalPaths.isUnder("/a.mp3", "/"))
        assertTrue(LocalPaths.isUnder("D:/a.mp3", "D:\\"))
        // Relative MediaStore folders match on every volume.
        assertTrue(LocalPaths.isUnder("/storage/emulated/0/Music/Rock/a.mp3", "Music"))
        assertTrue(LocalPaths.isUnder("/storage/1234-ABCD/Music/a.mp3", "music"))
        assertFalse(LocalPaths.isUnder("/storage/emulated/0/Musical/a.mp3", "Music"))
        // SAF documents and trees.
        assertTrue(LocalPaths.isUnder(docInRock, tree))
        assertTrue(LocalPaths.isUnder(LocalPaths.canonical(docInRock), tree))
        assertTrue(LocalPaths.isUnder(docInRock, "Music/Rock"))
        assertTrue(LocalPaths.isUnder(treeRock, tree))
        assertFalse(LocalPaths.isUnder(tree, treeRock))
        assertTrue(LocalPaths.sameFolder(tree, "/storage/emulated/0/Music/"))

        assertEquals("Music/a.mp3", LocalPaths.relativeOf("/storage/emulated/0/Music/a.mp3"))
        assertEquals("Music", LocalPaths.relativeOf("/storage/emulated/10/Music"))
        assertEquals("x", LocalPaths.relativeOf("/storage/1234-ABCD/x"))
        assertNull(LocalPaths.relativeOf("/data/user/0/x"))
        assertEquals("/storage/1234-ABCD", LocalPaths.volumeRoot("/storage/1234-ABCD/Music/a"))
        assertEquals("/storage/emulated/0", LocalPaths.volumeRoot("/storage/emulated/0/Music"))
        assertEquals("C:/", LocalPaths.volumeRoot("C:/x"))
    }

    @Test
    fun namesAndIds() {
        assertEquals("a.mp3", LocalPaths.nameOf(docInRock))
        assertEquals("a b.mp3", LocalPaths.nameOf("/x/a b.mp3"))
        assertEquals("Rock", LocalPaths.parentName(docInRock))
        assertEquals("/storage/emulated/0/Music/Rock", LocalPaths.dirOf(docInRock))
        assertEquals(LocalFormats.trackIdFor("/storage/emulated/0/Müzik/A.mp3"), LocalFormats.trackIdFor("/storage/emulated/0/müzik/a.MP3"))
        assertNotEquals(LocalFormats.trackIdFor("/x/A.mp3"), LocalFormats.trackIdFor("/x/B.mp3"))
        assertTrue(LocalFormats.trackIdFor("/x/a.mp3").matches(Regex("local:[0-9a-f]{16}")))
        assertEquals("file:///C:/Kapak%20klas%C3%B6r%C3%BC/a%20b.jpg", LocalFormats.fileUrl("C:\\Kapak klasörü\\a b.jpg"))
        assertEquals("file:///storage/emulated/0/a%20b.jpg", LocalFormats.fileUrl("/storage/emulated/0/a b.jpg"))
        assertEquals(docInRock, LocalFormats.fileUrl(docInRock))
        assertTrue(LocalFormats.isAudioFile("a.OPUS"))
        assertTrue(LocalFormats.isAudioFile("a.flac"))
        assertFalse(LocalFormats.isAudioFile("a.wma")) // no decoder on Android
        assertFalse(LocalFormats.isAudioFile(".mp3"))
        assertFalse(LocalFormats.isAudioFile("notes.txt"))
        assertEquals("audio/mpeg", LocalFormats.mimeFor("x.MP3"))
        assertEquals("audio/ogg", LocalFormats.mimeFor("x.opus"))
        val t: Track = LocalFormats.toTrack(LocalEntry(path = "/m/a.mp3", title = "T", artists = listOf("A"), album = "Al", cover = "/c/x.jpg", mtime = 1_700_000_000_000))
        assertEquals("", t.artists[0].id)
        assertEquals("", t.album.id)
        assertEquals("file:///c/x.jpg", t.album.images[0].url)
        assertEquals(1_700_000_000L, t.addedAt)
    }

    @Test
    fun walkedFromAndFolderRules() {
        assertTrue(LocalPaths.walkedFrom("/storage/emulated/0/walk", "/storage/emulated/0/walk/alt"))
        assertFalse(LocalPaths.walkedFrom("/storage/emulated/0/walk", "/storage/emulated/0/walk/.gizli"))
        assertFalse(LocalPaths.walkedFrom("/storage/emulated/0/walk", "/storage/emulated/0/walk/.gizli/alt"))
        assertFalse(LocalPaths.walkedFrom("/storage/emulated/0/walk/alt", "/storage/emulated/0/walk"))
        assertTrue(LocalPaths.walkedFrom(tree, treeRock))
        assertTrue(LocalPaths.walkedFrom("Music", "/storage/emulated/0/Music/Rock"))

        val walk = "/storage/emulated/0/walk"
        var folders = FolderRules.add(emptyList(), walk)!!
        assertEquals(listOf(walk), folders)
        assertNull(FolderRules.add(folders, "$walk/")) // the same folder
        assertNull(FolderRules.add(folders, "$walk/alt")) // its scan already covers it
        folders = FolderRules.add(folders, "$walk/.gizli")!! // the walk skips it: it may be listed on its own
        assertEquals(2, folders.size)
        folders = FolderRules.remove(folders, walk)
        assertEquals(listOf("$walk/.gizli"), folders)
        folders = FolderRules.add(folders, walk)!! // the parent again: the skipped one stays listed
        assertEquals(2, folders.size)
        folders = FolderRules.add(folders, "$walk/alt/../x").let { it ?: folders }
        assertEquals(2, folders.size)
        assertNull(FolderRules.add(folders, "/storage/K\uD800t"))
        // A parent replaces the listed folders it walks into.
        assertEquals(listOf("/storage/emulated/0"), FolderRules.add(listOf("/storage/emulated/0/a", "/storage/emulated/0/b"), "/storage/emulated/0"))
        // A tree and the same folder as a path are one folder.
        assertNull(FolderRules.add(listOf(tree), "/storage/emulated/0/Music"))

        // Removing: tracks under it go, unless another listed folder still scans them.
        val e = listOf(LocalEntry(path = "$walk/a.mp3"), LocalEntry(path = "$walk/.gizli/b.mp3"), LocalEntry(path = "/other/c.mp3"))
        assertEquals(listOf("$walk/.gizli/b.mp3", "/other/c.mp3"), FolderRules.entriesAfterRemoval(e, walk, listOf("$walk/.gizli", "/other")).map { it.path })
        // A listed parent doesn't walk into a "." folder: its tracks go.
        assertEquals(2, FolderRules.entriesAfterRemoval(e, "$walk/.gizli", listOf("/storage/emulated/0")).size)
        assertEquals(3, FolderRules.entriesAfterRemoval(e, "$walk/x", listOf("/storage")).size)
    }
}
