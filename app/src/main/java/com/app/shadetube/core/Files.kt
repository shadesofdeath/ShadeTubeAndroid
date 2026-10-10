package com.app.shadetube.core

import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.FileOutputStream

/**
 * Crash-safe file persistence used by every *.json store (port of the C++ write-then-rename pattern).
 *
 * [writeAtomic] writes `<name>.tmp`, fsyncs it and renames it over the target; with `keepOld` the previous file is
 * kept as `<name>.old` first (ListenStats style), and [readJson] falls back to `.old` when the main file is missing or
 * corrupt.
 */
object Files {
    fun writeAtomic(target: File, bytes: ByteArray, keepOld: Boolean = false): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (keepOld && target.exists()) {
            val old = File(target.path + ".old")
            old.delete()
            target.renameTo(old)
        }
        if (!tmp.renameTo(target)) {
            // rename over an existing file can fail on some file systems: replace explicitly.
            target.delete()
            check(tmp.renameTo(target)) { "rename failed" }
        }
        true
    }.onFailure { Log.w("files", "write ${target.name} failed", it) }.getOrDefault(false)

    fun writeAtomic(target: File, text: String, keepOld: Boolean = false) =
        writeAtomic(target, text.toByteArray(Charsets.UTF_8), keepOld)

    fun writeJson(target: File, json: JsonElement, pretty: Boolean = false, keepOld: Boolean = false) =
        writeAtomic(target, if (pretty) json.dumpPretty() else json.dump(), keepOld)

    /**
     * Reads a JSON file; null when it does not exist. A corrupt file is copied to `<name>.bak` (so nothing is lost)
     * and, if there is one, `<name>.old` is read instead.
     */
    fun readJson(file: File): JsonElement? {
        if (file.exists()) {
            val text = runCatching { file.readText() }.getOrNull()
            val parsed = text?.let { parseJsonOrNull(it) }
            if (parsed != null) return parsed
            Log.w("files", "${file.name} is corrupt; kept as .bak")
            runCatching { file.copyTo(File(file.path + ".bak"), overwrite = true) }
        }
        val old = File(file.path + ".old")
        if (old.exists()) return runCatching { parseJsonOrNull(old.readText()) }.getOrNull()
        return null
    }

    /** Size of a file or of everything under a folder. */
    fun sizeOf(f: File): Long = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else f.length()

    /** Characters Windows / FAT / Android's MediaStore refuse in a file name, replaced like the C++ app does. */
    fun safeFileName(name: String, maxLength: Int = 120): String {
        val cleaned = buildString {
            for (c in name) append(if (c in "<>:\"/\\|?*" || c.code < 32) '_' else c)
        }.trim().trimEnd('.').ifEmpty { "_" }
        return if (cleaned.length > maxLength) cleaned.take(maxLength).trimEnd() else cleaned
    }
}
