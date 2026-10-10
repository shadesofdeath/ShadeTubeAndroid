package com.app.shadetube.spotify

import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.core.parseJsonOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ports tests/spotify testPlaylistTree (rootlist.json fixture). */
class PlaylistTreeTest {
    private fun fixture(name: String) = parseJsonOrNull(File("src/test/java/com/app/shadetube/spotify/fixtures/$name").readText())!!

    @Test
    fun decodeNames() {
        assertEquals("Chill & Focus", decodeGroupName("Chill+%26+Focus"))
        assertEquals("a%2", decodeGroupName("a%2")) // broken escapes kept as is
        assertEquals("100%zz ", decodeGroupName("100%zz+"))
        assertEquals("ü", decodeGroupName("%C3%BC")) // UTF-8 bytes pass through
        assertEquals("Yol Müzikleri", decodeGroupName("Yol+M%C3%BCzikleri"))
        assertEquals("ğ ok", decodeGroupName("ğ+ok")) // raw non-ASCII text survives
    }

    @Test
    fun garbageIsEmpty() {
        assertTrue(parsePlaylistTree(null).isEmpty)
        assertTrue(parsePlaylistTree(parseJsonOrNull("""{"contents":{"items":[1,"x",{"uri":5},{}]}}""")).isEmpty)
    }

    @Test
    fun rootlistFixture() {
        val tree = parsePlaylistTree(fixture("rootlist.json"))
        val road = "a1b2c3d4e5f60718"
        val night = "b2c3d4e5f6071829"
        val empty = "c3d4e5f607182930"
        assertEquals(3, tree.folders.size)
        assertEquals(PlaylistFolder(road, "Yol Müzikleri", "", 3), tree.folders[0]) // 2, 3 (nested) and 4
        assertEquals(PlaylistFolder(night, "Gece & Gündüz", road, 1), tree.folders[1])
        assertEquals(PlaylistFolder(empty, "Boş klasör", "", 0), tree.folders[2])
        fun pl(n: Int) = "spotify:playlist:0TestPlaylist00000000$n"
        assertFalse(tree.folderOf.containsKey(pl(1)))
        assertEquals(road, tree.folderOf[pl(2)]) // listed again at the top level: the first place wins
        assertEquals(night, tree.folderOf[pl(3)])
        assertEquals(road, tree.folderOf[pl(4)])
        assertFalse(tree.folderOf.containsKey(pl(5))) // after a stray end marker: still top level
        assertNotNull(tree.folder(night))
        assertNull(tree.folder("nope"))

        // libraryV3 order (recents): Liked Songs, 4, 1, 5, 3, 2.
        val lib = listOf(LIKED_SONGS_URI, pl(4), pl(1), pl(5), pl(3), pl(2)).map { Playlist(id = it) }
        fun describe(rows: List<TreeRow>) = rows.joinToString(" ") { r ->
            ">".repeat(r.depth) + if (r.folder) "[" + tree.folders[r.index].id.take(2) + "]" else lib[r.index].id.takeLast(1)
        }
        val none: (String) -> Boolean = { false }
        val all: (String) -> Boolean = { true }
        // The road folder takes playlist 4's place (its most recent playlist); the empty folder goes last.
        assertEquals("s [a1] 1 5 [c3]", describe(treeRows(lib, tree, none)))
        assertEquals("s [a1] 1 5 [c3]", describe(treeRows(lib, tree, null)))
        assertEquals("s [a1] >4 >[b2] >>3 >2 1 5 [c3]", describe(treeRows(lib, tree, all)))
        assertEquals("s [a1] >4 >[b2] >2 1 5 [c3]", describe(treeRows(lib, tree, { it == road })))
        assertEquals("4 [b2] >3 2", describe(treeRows(lib, tree, all, road))) // a folder's own view
        assertTrue(treeRows(lib, tree, all, "unknown").isEmpty())
        assertEquals(lib.size, treeRows(lib, PlaylistTree(), all).size) // no folders: the flat order
    }

    @Test
    fun unbalancedInput() {
        // A group left open ends with the list; a duplicate group id keeps its items in the parent.
        val odd = parsePlaylistTree(
            parseJsonOrNull(
                """{"contents":{"items":[
                {"uri":"spotify:start-group:11:A"},{"uri":"spotify:playlist:p1"},
                {"uri":"spotify:start-group:11:A+again"},{"uri":"spotify:playlist:p2"},{"uri":"spotify:end-group:11"},
                {"uri":"spotify:start-group:22"},{"uri":"spotify:playlist:p3"}]}}""",
            ),
        )
        assertEquals(2, odd.folders.size)
        assertTrue(odd.folders[1].name.isEmpty())
        assertEquals("11", odd.folders[1].parentId)
        assertEquals("11", odd.folderOf["spotify:playlist:p2"])
        assertEquals("22", odd.folderOf["spotify:playlist:p3"])
        assertEquals(3, odd.folders[0].playlistCount)
    }

    @Test
    fun rootlistLengthsAndUris() {
        val root = fixture("rootlist.json")
        val lengths = SpotifyParse.parseRootlistLengths(root)
        fun pl(n: Int) = "spotify:playlist:0TestPlaylist00000000$n"
        assertEquals(12, lengths[pl(1)])
        assertEquals(3, lengths[pl(2)])
        assertEquals(0, lengths[pl(3)]) // proto3 JSON omits a zero length
        assertEquals(40, lengths[pl(4)])
        assertEquals(7, lengths[pl(5)])
        assertEquals(listOf(pl(1), pl(2), pl(3), pl(4), pl(5), pl(2)), SpotifyParse.rootlistPlaylists(root))
        // metaItems only for the playlists (no folder markers): paired with the playlist rows.
        val sparse = parseJsonOrNull(
            """{"contents":{"items":[{"uri":"spotify:start-group:1:A"},{"uri":"spotify:playlist:x"},{"uri":"spotify:end-group:1"},
               {"uri":"spotify:playlist:y"}],"metaItems":[{"length":4},{"length":9}]}}""",
        )
        assertEquals(mapOf("spotify:playlist:x" to 4, "spotify:playlist:y" to 9), SpotifyParse.parseRootlistLengths(sparse))
        // A count mismatch pairs nothing.
        val off = parseJsonOrNull("""{"contents":{"items":[{"uri":"spotify:playlist:x"}],"metaItems":[{"length":4},{"length":9}]}}""")
        assertTrue(SpotifyParse.parseRootlistLengths(off).isEmpty())
    }
}
