package com.app.shadetube.localfiles

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Locations of local files and music folders (port of local::normalizeFolder / isUnder / walkedFrom, adapted to
 * Android). Pure string logic: no disk access, so JVM tests cover it.
 *
 * A music folder (an entry of Settings.localFolders) is one of:
 *  - a SAF tree URI the user picked ("content://com.android.externalstorage.documents/tree/primary%3AMusic");
 *  - an absolute path ("/storage/emulated/0/Music", "/storage/1234-ABCD/Music"): scanned through MediaStore;
 *  - a MediaStore relative path ("Music/Rock"): that folder on every storage volume.
 *
 * Files are compared by their canonical location: a document of the external storage provider maps to its absolute
 * path (its document ids are "<volume>:<path>"), so a file reached through a picked folder and through MediaStore is
 * one file with one id; other providers' documents are "saf://<authority>/<document id>".
 */
object LocalPaths {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** The primary shared storage root (Environment.getExternalStorageDirectory); the Android layer sets it. */
    @Volatile
    var primaryRoot: String = "/storage/emulated/0"

    fun isContentUri(s: String) = s.startsWith("content://")
    fun isTreeUri(s: String) = isContentUri(s) && s.indexOf("/tree/") > 0

    /** Names the UTF-8 settings / index can hold: no unpaired UTF-16 surrogates. */
    fun validUtf16(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return false
            i++
        }
        return true
    }

    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) {
                    out.write(h)
                    i += 3
                    continue
                }
            }
            val bytes = c.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun uriPath(uri: String): String {
        val afterScheme = uri.substringAfter("://", "")
        val slash = afterScheme.indexOf('/')
        return if (slash < 0) "" else afterScheme.substring(slash).substringBefore('?').substringBefore('#')
    }

    fun authorityOf(uri: String): String = uri.substringAfter("://", "").substringBefore('/')

    /** The document id of a SAF URI: the "document" part, else the "tree" part. null for other URIs. */
    fun documentIdOf(uri: String): String? {
        if (!isContentUri(uri)) return null
        val seg = uriPath(uri).split('/').filter { it.isNotEmpty() }
        val doc = seg.indexOf("document")
        if (doc >= 0 && doc + 1 < seg.size) return percentDecode(seg[doc + 1])
        val tree = seg.indexOf("tree")
        if (tree >= 0 && tree + 1 < seg.size) return percentDecode(seg[tree + 1])
        return null
    }

    /** The tree document id of a tree URI (the folder the user picked). */
    fun treeIdOf(uri: String): String? {
        val seg = uriPath(uri).split('/').filter { it.isNotEmpty() }
        val tree = seg.indexOf("tree")
        return if (tree >= 0 && tree + 1 < seg.size) percentDecode(seg[tree + 1]) else null
    }

    /** "primary:Music/a.mp3" of the external storage provider -> "/storage/emulated/0/Music/a.mp3". */
    fun externalStoragePath(documentId: String): String? {
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val rest = documentId.substring(colon + 1).trim('/')
        val root = when (volume) {
            "primary" -> primaryRoot
            "home" -> "$primaryRoot/Documents"
            "raw" -> return normalizePath(rest.let { if (it.startsWith("/")) it else "/$it" })
            else -> "/storage/$volume"
        }
        return if (rest.isEmpty()) root else normalizePath("$root/$rest")
    }

    /** The canonical location of a file or folder (see the class comment). */
    fun canonical(pathOrUri: String): String {
        val s = pathOrUri.trim()
        if (isContentUri(s)) {
            val docId = documentIdOf(s) ?: return s
            val authority = authorityOf(s)
            if (authority == EXTERNAL_STORAGE_AUTHORITY) externalStoragePath(docId)?.let { return it }
            return "saf://$authority/" + docId.trim('/')
        }
        if (s.startsWith("file://")) {
            val p = percentDecode(s.removePrefix("file://").removePrefix("localhost"))
            return normalizePath(if (p.length >= 3 && p[0] == '/' && p[2] == ':') p.substring(1) else p)
        }
        return normalizePath(s)
    }

    /** "/x", "C:/x" (a list written on Windows), "saf://...". */
    fun isAbsolute(p: String): Boolean =
        p.startsWith("/") || p.startsWith("saf://") || p.startsWith("\\\\") ||
            (p.length >= 2 && p[1] == ':' && p[0].isLetter() && (p.length == 2 || p[2] == '/' || p[2] == '\\'))

    /**
     * Forward slashes, no "." / ".." / empty segments, no trailing separator (except a root). Windows paths keep their
     * drive ("C:\Müzik\" -> "C:/Müzik").
     */
    fun normalizePath(path: String): String {
        if (path.startsWith("saf://")) return path.trimEnd('/')
        val p = path.replace('\\', '/')
        val prefix = when {
            p.length >= 2 && p[1] == ':' && p[0].isLetter() -> p.substring(0, 2) + "/"
            p.startsWith("/") -> "/"
            else -> ""
        }
        val body = when (prefix) {
            "" -> p
            "/" -> p.substring(1)
            else -> p.substring(2) // "C:" (the separator after it is an empty segment)
        }
        val out = ArrayList<String>()
        for (seg in body.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1) else if (prefix.isEmpty()) out += ".."
                else -> out += seg
            }
        }
        val joined = out.joinToString("/")
        return if (prefix.isEmpty()) joined else prefix + joined
    }

    /**
     * Form for Settings.localFolders: a tree URI as it is (SAF permissions are keyed by it), a path normalized; ""
     * when it can't be stored (empty, or an unpaired UTF-16 surrogate the UTF-8 settings can't hold).
     */
    fun normalizeFolder(folder: String): String {
        val f = folder.trim()
        if (f.isEmpty() || !validUtf16(f)) return ""
        if (isContentUri(f)) return if (isTreeUri(f)) f else ""
        return normalizePath(f)
    }

    /** Both arguments already lower-cased canonical locations. */
    fun underLower(path: String, folder: String): Boolean {
        if (folder.isEmpty() || path.length < folder.length || !path.startsWith(folder)) return false
        return path.length == folder.length || folder.endsWith("/") || path[folder.length] == '/'
    }

    /** The volume-relative part of an absolute shared-storage path ("/storage/emulated/0/Music/a.mp3" -> "Music/a.mp3"). */
    fun relativeOf(absPath: String): String? {
        val p = normalizePath(absPath)
        val root = primaryRoot.trimEnd('/')
        if (p == root) return ""
        if (p.startsWith("$root/")) return p.substring(root.length + 1)
        Regex("^/storage/emulated/\\d+(/(.*))?$").matchEntire(p)?.let { return it.groupValues[2] }
        Regex("^/sdcard(/(.*))?$").matchEntire(p)?.let { return it.groupValues[2] }
        Regex("^/storage/(?!emulated(/|$))[^/]+(/(.*))?$").matchEntire(p)?.let { return it.groupValues[3] }
        return null
    }

    /** The volume root of an absolute path ("/storage/1234-ABCD", "/storage/emulated/0", "C:/"); "" when none. */
    fun volumeRoot(absPath: String): String {
        val p = normalizePath(absPath)
        if (p.length >= 2 && p[1] == ':') return p.substring(0, 2) + "/"
        val seg = p.split('/').filter { it.isNotEmpty() }
        return when {
            seg.size >= 3 && seg[0] == "storage" && seg[1] == "emulated" -> "/storage/emulated/${seg[2]}"
            seg.size >= 2 && seg[0] == "storage" -> "/storage/${seg[1]}"
            seg.isNotEmpty() -> "/" + seg[0]
            else -> "/"
        }
    }

    private fun lower(s: String) = s.lowercase(Locale.ROOT)

    /** The relative-to-[folder] remainder of [path] (both canonical / relative alike); null when not under it. */
    private fun remainderUnder(path: String, folder: String): String? {
        val f = lower(folder)
        val pl = lower(path)
        if (!underLower(pl, f)) return null
        return path.substring(folder.length).trimStart('/')
    }

    private fun folderLocation(folder: String): String = if (isTreeUri(folder)) canonical(folder) else normalizePath(folder)

    /**
     * The part of the file / folder [path] below the music [folder], "" when they are the same; null when [path] is
     * not inside it. A relative folder matches on every volume.
     */
    fun remainderBelow(path: String, folder: String): String? {
        val f = folderLocation(folder)
        if (f.isEmpty()) return null
        val p = if (isTreeUri(path)) canonical(path) else if (isContentUri(path) || path.startsWith("file://")) canonical(path) else normalizePath(path)
        if (isAbsolute(f)) return if (isAbsolute(p)) remainderUnder(p, f) else null
        val rel = if (isAbsolute(p)) relativeOf(p) ?: return null else p
        return remainderUnder(rel, f)
    }

    /** `path` (a file or folder) equals or lies inside the music `folder` (case-insensitive). */
    fun isUnder(path: String, folder: String): Boolean = remainderBelow(path, folder) != null

    fun sameFolder(a: String, b: String): Boolean = isUnder(a, b) && isUnder(b, a)

    /**
     * A scan of `parent` reaches the folder `child`: it lies inside `parent` and no folder on the way is one the walk
     * skips ("." prefixed - Android's hidden folders). The C++ also stops at junctions and hidden / system attributes;
     * neither exists for MediaStore / SAF.
     */
    fun walkedFrom(parent: String, child: String): Boolean {
        val p = normalizeFolder(parent)
        val c = normalizeFolder(child)
        if (p.isEmpty() || c.isEmpty()) return false
        val rest = remainderBelow(c, p) ?: return false
        return rest.split('/').none { it.isNotEmpty() && it.startsWith(".") }
    }

    /** The file name of a path or URI ("a.mp3"). */
    fun nameOf(pathOrUri: String): String {
        val p = if (isContentUri(pathOrUri)) documentIdOf(pathOrUri) ?: percentDecode(uriPath(pathOrUri)) else pathOrUri
        val s = p.replace('\\', '/').trimEnd('/')
        return s.substring(s.lastIndexOf('/') + 1)
    }

    /** The canonical folder a file lies in (folder images, "CD2" disc folders). */
    fun dirOf(path: String): String {
        val c = canonical(path)
        val slash = c.lastIndexOf('/')
        return when {
            slash < 0 -> ""
            slash == 0 -> "/"
            else -> c.substring(0, slash)
        }
    }

    /** The name of the folder a file lies in. */
    fun parentName(path: String): String = nameOf(dirOf(path))
}
