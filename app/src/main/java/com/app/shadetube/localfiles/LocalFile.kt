package com.app.shadetube.localfiles

import com.app.shadetube.lyrics.LocalAudioFile
import com.app.shadetube.lyrics.LocalLyrics
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.SeekableByteChannel

/**
 * The file of a "local:" track, for the player (MediaItem from [uri] + [mimeType]), lyrics ([LocalFileOpener.audio])
 * and export (the playlist file location: [path]).
 */
data class LocalFile(
    val trackId: String,
    /** The file's canonical location: an absolute path, or "saf://..." / a content URI for other providers. */
    val path: String,
    /** What the player opens: content:// (MediaStore item / SAF document) or file:// URI. */
    val uri: String,
    /** File name with extension. */
    val name: String,
    /** By extension (the C++ Player::localMimeType); a generic audio type when unknown (ExoPlayer sniffs the container). */
    val mimeType: String,
) {
    /** An absolute file path when the file has one (exports write it), else null. */
    val filePath: String? get() = if (path.startsWith("/")) path else null

    companion object {
        fun of(e: LocalEntry): LocalFile = LocalFile(
            trackId = LocalFormats.trackIdFor(e.path),
            path = e.path,
            uri = e.uri.ifEmpty { if (e.path.startsWith("/")) LocalFormats.fileUrl(e.path) else e.path },
            name = e.fileName,
            mimeType = LocalFormats.mimeFor(e.fileName),
        )
    }
}

/** Opens local files for reading (a file path, or a content URI through the ContentResolver on Android). */
interface LocalFileOpener {
    /** The file's bytes; null when it is gone / unreadable. */
    fun open(file: LocalFile): SeekableByteChannel?

    /** `<stem>.lrc` next to it; null when there is none (or it can't be reached). */
    fun openSidecar(file: LocalFile): SeekableByteChannel? = null

    /** The file can still be opened. */
    fun exists(file: LocalFile): Boolean = open(file)?.let { it.close(); true } ?: false

    /** The file as the lyrics module reads it (embedded lyrics + sidecar .lrc). */
    fun audio(file: LocalFile): LocalAudioFile = object : LocalAudioFile {
        override val name: String get() = file.name
        override fun open(): SeekableByteChannel? = this@LocalFileOpener.open(file)
        override fun openSidecar(): SeekableByteChannel? = this@LocalFileOpener.openSidecar(file)
    }

    /** ReplayGain track gain of the file in dB (for the player's loudness normalisation); null = none. Blocking. */
    fun replayGainDb(file: LocalFile): Float? =
        runCatching { open(file)?.use { ReplayGain.readTrackGainDb(ChannelSource(it)) } }.getOrNull()
}

/** Plain files only (the JVM tests; app-private folders). */
open class PathFileOpener : LocalFileOpener {
    protected fun fileOf(f: LocalFile): File? = when {
        f.path.startsWith("/") || (f.path.length > 2 && f.path[1] == ':') -> File(f.path)
        f.uri.startsWith("file://") -> File(LocalPaths.canonical(f.uri))
        else -> null
    }

    override fun open(file: LocalFile): SeekableByteChannel? {
        val f = fileOf(file) ?: return null
        return if (f.isFile) runCatching { RandomAccessFile(f, "r").channel }.getOrNull() else null
    }

    override fun openSidecar(file: LocalFile): SeekableByteChannel? {
        val f = fileOf(file) ?: return null
        val lrc = LocalLyrics.sidecarPath(f)
        return if (lrc.isFile) runCatching { RandomAccessFile(lrc, "r").channel }.getOrNull() else null
    }
}
