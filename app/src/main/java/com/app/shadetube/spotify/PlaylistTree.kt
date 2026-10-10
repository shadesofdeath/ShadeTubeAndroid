package com.app.shadetube.spotify

import com.app.shadetube.catalog.Playlist
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream

/*
 * Folders of the user's Spotify library (port of spotify/PlaylistTree). The rootlist (SpotifyApi.rootlist) is a flat
 * list in which folders are brackets:
 *   spotify:start-group:<group id>:<form-encoded name>   ...playlists and nested groups...   spotify:end-group:<group id>
 * ("+" for spaces, %XX for the rest), nested to any depth. libraryV3, which gives the sidebar its order, carries no
 * membership, so the tree comes from the rootlist and the order from libraryV3 (treeRows).
 */

data class PlaylistFolder(
    /** Group id from the rootlist (stable; the web API's spotify:user:<u>:folder:<id>). */
    val id: String = "",
    /** Decoded; may be empty. */
    val name: String = "",
    /** "" = top level. */
    val parentId: String = "",
    /** Playlists inside, nested folders included. */
    val playlistCount: Int = 0,
)

data class PlaylistTree(
    /** Rootlist order (a parent before its children). */
    val folders: List<PlaylistFolder> = emptyList(),
    /** Playlist URI -> folder id (top level: absent). */
    val folderOf: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = folders.isEmpty()
    fun folder(id: String): PlaylistFolder? = folders.firstOrNull { it.id == id }
}

data class TreeRow(
    val folder: Boolean = false,
    /** 0 = directly under `root`. */
    val depth: Int = 0,
    /** Into `playlists` (folder == false) or tree.folders (folder == true). */
    val index: Int = 0,
)

private const val START_GROUP = "spotify:start-group:"
private const val END_GROUP = "spotify:end-group:"
private const val PLAYLIST_PREFIX = "spotify:playlist:"

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** "Chill+%26+Focus" -> "Chill & Focus" (form decoding over UTF-8 bytes; a broken escape is kept as is). */
fun decodeGroupName(s: String): String {
    val out = ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '+') {
            out.write(' '.code)
        } else if (c == '%' && i + 2 < s.length && hexValue(s[i + 1]) >= 0 && hexValue(s[i + 2]) >= 0) {
            out.write(hexValue(s[i + 1]) * 16 + hexValue(s[i + 2]))
            i += 2
        } else {
            // Non-ASCII text passes through as its UTF-8 bytes (surrogate pairs kept together).
            val end = if (Character.isHighSurrogate(c) && i + 1 < s.length) i + 2 else i + 1
            out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
            i = end - 1
        }
        ++i
    }
    return out.toByteArray().toString(Charsets.UTF_8)
}

/**
 * Parses a rootlist response (contents.items[].uri). Unbalanced markers are tolerated: an end without its start
 * closes nothing, groups left open end with the list. A playlist listed twice keeps its first folder.
 */
fun parsePlaylistTree(rootlist: JsonElement?): PlaylistTree {
    val contents = (rootlist as? JsonObject)?.get("contents") as? JsonObject ?: return PlaylistTree()
    val items = contents["items"] as? JsonArray ?: return PlaylistTree()

    data class F(val id: String, var name: String, val parentId: String, var count: Int = 0)
    val folders = ArrayList<F>()
    val folderOf = LinkedHashMap<String, String>()
    // Open groups: indexes into folders; NONE for a group that isn't a folder of its own (no id, or an id seen before),
    // whose contents stay in the enclosing folder.
    val none = -1
    val stack = ArrayList<Int>()
    fun innermost(): String {
        for (k in stack.indices.reversed()) if (stack[k] != none) return folders[stack[k]].id
        return ""
    }
    for (it in items) {
        val uriEl = (it as? JsonObject)?.get("uri") as? JsonPrimitive ?: continue
        if (!uriEl.isString) continue
        val uri = uriEl.content
        if (uri.startsWith(START_GROUP)) {
            val rest = uri.substring(START_GROUP.length)
            val colon = rest.indexOf(':')
            val id = if (colon < 0) rest else rest.substring(0, colon)
            if (id.isEmpty() || folders.any { f -> f.id == id }) {
                stack.add(none)
                continue
            }
            val name = if (colon >= 0) decodeGroupName(rest.substring(colon + 1)) else ""
            folders.add(F(id, name, innermost()))
            stack.add(folders.size - 1)
        } else if (uri.startsWith(END_GROUP)) {
            if (stack.isNotEmpty() && stack.last() == none) { // closes the innermost placeholder group
                stack.removeAt(stack.size - 1)
                continue
            }
            // Close up to the matching group; an end marker nobody opened closes nothing.
            val id = uri.substring(END_GROUP.length)
            for (k in stack.indices.reversed()) {
                if (stack[k] != none && folders[stack[k]].id == id) {
                    while (stack.size > k) stack.removeAt(stack.size - 1)
                    break
                }
            }
        } else if (uri.startsWith(PLAYLIST_PREFIX)) {
            if (uri in folderOf) continue // listed twice: the first place wins
            folderOf[uri] = innermost()
            for (s in stack) if (s != none) folders[s].count++
        }
    }
    return PlaylistTree(
        folders = folders.map { PlaylistFolder(it.id, it.name, it.parentId, it.count) },
        folderOf = folderOf.filterValues { it.isNotEmpty() },
    )
}

/**
 * The rows under folder [root] ("" = the whole library) in display order. Playlists keep the order of [playlists]
 * (libraryV3's) inside their folder; a folder sits where its first playlist in that order would, so a recently used
 * folder rises like a recently used playlist; empty folders go last at their level, in rootlist order. A folder's
 * contents follow it only when [expanded] (folder id) is true (null: nothing is expanded). Playlists whose folder is
 * unknown to the tree are listed at the top level.
 */
fun treeRows(
    playlists: List<Playlist>,
    tree: PlaylistTree,
    expanded: ((folderId: String) -> Boolean)?,
    root: String = "",
): List<TreeRow> {
    val folderIndex = HashMap<String, Int>()
    tree.folders.forEachIndexed { i, f -> folderIndex.putIfAbsent(f.id, i) }
    fun parentKey(folderId: String) = if (folderId in folderIndex) folderId else ""

    data class Child(val folder: Boolean, val index: Int)
    val children = HashMap<String, MutableList<Child>>() // folder id ("" = top) -> children
    playlists.forEachIndexed { i, p ->
        val f = tree.folderOf[p.id]
        children.getOrPut(if (f == null) "" else parentKey(f)) { ArrayList() }.add(Child(false, i))
    }
    tree.folders.forEachIndexed { i, f ->
        var parent = parentKey(f.parentId)
        if (parent == f.id) parent = "" // a folder can't contain itself
        children.getOrPut(parent) { ArrayList() }.add(Child(true, i))
    }

    // Sort key: a playlist's position; a folder's is the smallest key inside it, empty folders after every playlist
    // (in rootlist order). Memoized; a cycle (malformed parents) is cut by the in-progress mark.
    val empty = playlists.size.toLong()
    val folderKey = LongArray(tree.folders.size) { -1 }
    val visiting = BooleanArray(tree.folders.size)
    fun keyOf(fi: Int): Long {
        if (folderKey[fi] >= 0) return folderKey[fi]
        if (visiting[fi]) return Long.MAX_VALUE
        visiting[fi] = true
        var best = Long.MAX_VALUE
        children[tree.folders[fi].id]?.forEach { c -> best = minOf(best, if (c.folder) keyOf(c.index) else c.index.toLong()) }
        visiting[fi] = false
        folderKey[fi] = if (best < empty) best else empty + fi
        return folderKey[fi]
    }
    for (fi in tree.folders.indices) keyOf(fi) // all keys before any list is reordered
    for (list in children.values) {
        list.sortWith(compareBy { c -> if (c.folder) folderKey[c.index] else c.index.toLong() }) // stable
    }

    val rows = ArrayList<TreeRow>()
    val emitted = BooleanArray(tree.folders.size) // guards against malformed cycles
    fun emit(id: String, depth: Int) {
        val list = children[id] ?: return
        for (c in list) {
            if (!c.folder) {
                rows.add(TreeRow(false, depth, c.index))
                continue
            }
            if (emitted[c.index]) continue
            emitted[c.index] = true
            rows.add(TreeRow(true, depth, c.index))
            if (expanded != null && expanded(tree.folders[c.index].id)) emit(tree.folders[c.index].id, depth + 1)
        }
    }
    if (root.isEmpty() || root in folderIndex) emit(root, 0) // an unknown folder has no rows
    return rows
}
