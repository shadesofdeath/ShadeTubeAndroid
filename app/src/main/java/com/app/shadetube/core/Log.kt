package com.app.shadetube.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * App log: Logcat plus a small rolling file (logs/shadetube.log, rotated at 1 MB to shadetube.old.log).
 * Port of core/Log. Thread-safe; file writes happen on a single background thread.
 */
object Log {
    private const val MAX_BYTES = 1L shl 20
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "st-log").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    /** Called once by [Paths.init]. */
    internal fun attach(dir: File) {
        file = File(dir, "shadetube.log")
    }

    fun d(tag: String, msg: String) = write(android.util.Log.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = write(android.util.Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write(android.util.Log.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write(android.util.Log.ERROR, tag, msg, t)

    private fun write(level: Int, tag: String, msg: String, t: Throwable?) {
        val fullTag = "ST/$tag"
        runCatching {
            when (level) {
                android.util.Log.DEBUG -> android.util.Log.d(fullTag, msg, t)
                android.util.Log.INFO -> android.util.Log.i(fullTag, msg, t)
                android.util.Log.WARN -> android.util.Log.w(fullTag, msg, t)
                else -> android.util.Log.e(fullTag, msg, t)
            }
        }
        if (level == android.util.Log.DEBUG) return
        val f = file ?: return
        val letter = when (level) { android.util.Log.INFO -> 'I'; android.util.Log.WARN -> 'W'; else -> 'E' }
        val line = buildString {
            synchronized(stamp) { append(stamp.format(Date())) }
            append(' ').append(letter).append(" [").append(tag).append("] ").append(msg)
            if (t != null) append(" — ").append(t.javaClass.simpleName).append(": ").append(t.message)
            append('\n')
        }
        writer.execute {
            runCatching {
                if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "shadetube.old.log"))
                f.appendText(line)
            }
        }
    }
}
