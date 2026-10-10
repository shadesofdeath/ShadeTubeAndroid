package com.app.shadetube.lyrics

import com.app.shadetube.core.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * An audio file that plays from disk (a local file or a download), as the caller knows it: a plain [File]
 * ([FileAudio]) or a SAF document (the caller implements this over its content resolver).
 */
interface LocalAudioFile {
    /** The file name with its extension ("Song.mp3"). */
    val name: String

    /** Opens the audio file for reading; null when it is missing / unreadable. */
    fun open(): SeekableByteChannel?

    /** Opens `<stem>.lrc` next to it; null when there is none. */
    fun openSidecar(): SeekableByteChannel?
}

/** A [LocalAudioFile] on the file system. */
class FileAudio(val file: File) : LocalAudioFile {
    override val name: String get() = file.name
    override fun open(): SeekableByteChannel? =
        if (file.isFile) runCatching { RandomAccessFile(file, "r").channel }.getOrNull() else null

    override fun openSidecar(): SeekableByteChannel? {
        val lrc = LocalLyrics.sidecarPath(file)
        return if (lrc.isFile) runCatching { RandomAccessFile(lrc, "r").channel }.getOrNull() else null
    }
}

/**
 * Lyrics stored with the user's own audio files (port of lyrics/LocalLyrics.cpp): a sidecar .lrc, ID3v2 SYLT / USLT
 * frames and - in place of the Windows property system's System.Music.Lyrics for the other formats - the FLAC
 * Vorbis comment LYRICS / UNSYNCEDLYRICS and the MP4 `©lyr` atom, read here. Blocking: call from a worker.
 */
object LocalLyrics {
    /** A .lrc is a few KB; anything this big is not one. */
    private const val MAX_SIDECAR_BYTES = 2 shl 20
    /** ID3 tags carry cover art; lyrics frames are read out of them. */
    private const val MAX_TAG_BYTES = 64 shl 20
    /** A lyrics tag of another container (FLAC comment block, MP4 atom). */
    private const val MAX_CONTAINER_LYRICS_BYTES = 4 shl 20

    /** `<dir>/<stem>.lrc` (C++ replace_extension: a name without a stem, like ".mp3", gets ".lrc" appended). */
    fun sidecarPath(audio: File): File = File(audio.parentFile, sidecarName(audio.name))

    fun sidecarName(name: String): String {
        val dot = name.lastIndexOf('.')
        return (if (dot > 0) name.substring(0, dot) else name) + ".lrc"
    }

    // ---- reading helpers ------------------------------------------------------------------------------------------
    private fun readAt(ch: SeekableByteChannel, pos: Long, maxBytes: Int): ByteArray {
        val size = ch.size()
        if (pos >= size || maxBytes <= 0) return ByteArray(0)
        val n = minOf(maxBytes.toLong(), size - pos).toInt()
        val buf = ByteBuffer.allocate(n)
        ch.position(pos)
        while (buf.hasRemaining()) if (ch.read(buf) < 0) break
        return buf.array().copyOf(buf.position())
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun synchsafe(b: ByteArray, i: Int): Long =
        ((u8(b, i) and 0x7F).toLong() shl 21) or ((u8(b, i + 1) and 0x7F).toLong() shl 14) or
            ((u8(b, i + 2) and 0x7F).toLong() shl 7) or (u8(b, i + 3) and 0x7F).toLong()

    private fun be32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    private fun le32(b: ByteArray, i: Int): Long =
        (u8(b, i + 3).toLong() shl 24) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 1).toLong() shl 8) or u8(b, i).toLong()

    private fun utf16(b: ByteArray, from: Int, bytes: Int, bigEndian: Boolean): String {
        val sb = StringBuilder(bytes / 2)
        var i = from
        while (i + 1 < from + bytes) {
            sb.append((if (bigEndian) (u8(b, i) shl 8) or u8(b, i + 1) else u8(b, i) or (u8(b, i + 1) shl 8)).toChar())
            i += 2
        }
        return sb.toString()
    }

    /** Reverses the unsynchronisation scheme (every 0xFF 0x00 pair was written for a 0xFF). */
    private fun deunsync(s: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.size)
        var i = 0
        while (i < s.size) {
            out.write(s[i].toInt())
            if (s[i] == 0xFF.toByte() && i + 1 < s.size && s[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // ---- text decoding --------------------------------------------------------------------------------------------
    /**
     * Reads a text file of unknown encoding: UTF-8 with / without BOM, UTF-16 LE / BE with BOM, else the "ANSI code
     * page" of an older tool. Android has no ANSI code page: the Windows code page of the device language stands in
     * (Turkish -> windows-1254, Cyrillic languages -> 1251, ...; windows-1252 otherwise).
     */
    fun decodeText(bytes: ByteArray): String {
        val n = bytes.size
        if (n >= 3 && u8(bytes, 0) == 0xEF && u8(bytes, 1) == 0xBB && u8(bytes, 2) == 0xBF)
            return String(bytes, 3, n - 3, Charsets.UTF_8)
        if (n >= 2 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xFE) return utf16(bytes, 2, n - 2, false)
        if (n >= 2 && u8(bytes, 0) == 0xFE && u8(bytes, 1) == 0xFF) return utf16(bytes, 2, n - 2, true)
        if (n == 0) return ""
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
        }
        // Not UTF-8: an older tool's ANSI file (e.g. Windows-1254 for Turkish).
        return String(bytes, ansiCharset())
    }

    private fun ansiCharset(): Charset {
        val name = when (Locale.getDefault().language) {
            "tr", "az" -> "windows-1254"
            "ru", "uk", "be", "bg", "sr", "mk", "kk" -> "windows-1251"
            "pl", "cs", "sk", "hu", "ro", "hr", "sl", "bs" -> "windows-1250"
            "el" -> "windows-1253"
            "he", "iw" -> "windows-1255"
            "ar", "fa", "ur" -> "windows-1256"
            "lt", "lv", "et" -> "windows-1257"
            "vi" -> "windows-1258"
            else -> "windows-1252"
        }
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1)
    }

    // ---- ID3v2 ----------------------------------------------------------------------------------------------------
    /** A null-terminated string in frame encoding [enc] starting at pos[0]; advances pos[0] past its terminator. */
    private fun readString(d: ByteArray, pos: IntArray, enc: Int): String {
        val start = pos[0]
        val wide = enc == 1 || enc == 2
        var end = start
        if (wide) {
            while (end + 1 < d.size && !(d[end].toInt() == 0 && d[end + 1].toInt() == 0)) end += 2
        } else {
            while (end < d.size && d[end].toInt() != 0) end++
        }
        val len = minOf(end, d.size) - start
        val out = when (enc) {
            0 -> String(d, start, len, Charsets.ISO_8859_1) // ISO-8859-1 bytes are their code points
            3 -> String(d, start, len, Charsets.UTF_8)
            else -> {
                var be = enc == 2
                var from = start
                if (enc == 1 && len >= 2) {
                    if (u8(d, start) == 0xFE && u8(d, start + 1) == 0xFF) {
                        be = true; from += 2
                    } else if (u8(d, start) == 0xFF && u8(d, start + 1) == 0xFE) {
                        from += 2
                    }
                }
                utf16(d, from, start + len - from, be)
            }
        }
        pos[0] = minOf(d.size, end + if (wide) 2 else 1)
        return out
    }

    private fun parseUslt(d: ByteArray): Lyrics? {
        if (d.size < 5) return null
        val enc = u8(d, 0)
        if (enc > 3) return null
        val pos = intArrayOf(4)
        readString(d, pos, enc) // content descriptor
        // The text runs to the end of the frame (a terminator is optional).
        val text = readString(d, intArrayOf(pos[0]), enc)
        if (Lrc.trim(text).isEmpty()) return null
        val l = Lrc.parse(text) // taggers often store LRC here
        if (l.lines.isEmpty()) return null
        return l.copy(source = SOURCE_TAG)
    }

    private fun parseSylt(d: ByteArray): Lyrics? {
        if (d.size < 7) return null
        val enc = u8(d, 0)
        val stampFormat = u8(d, 4)
        if (enc > 3 || stampFormat != 2) return null // 1 = MPEG frames: needs the frame rate, not supported
        val pos = intArrayOf(6)
        readString(d, pos, enc) // content descriptor
        val entries = ArrayList<Pair<Int, String>>()
        while (pos[0] < d.size) {
            val text = readString(d, pos, enc)
            if (pos[0] + 4 > d.size) break
            val ms = be32(d, pos[0]).toInt()
            pos[0] += 4
            entries += ms to text
        }
        if (entries.isEmpty()) return null
        fun startsLine(t: String) = t.isNotEmpty() && (t[0] == '\n' || t[0] == '\r')
        // Karaoke taggers store syllables and mark a new line with a leading line break; otherwise an entry is a line.
        val syllables = entries.drop(1).any { startsLine(it.second) }

        class Building(val timeMs: Int, var text: String = "", val words: MutableList<LyricWord> = ArrayList())
        val lines = ArrayList<Building>()
        for ((ms, raw) in entries) {
            val newLine = startsLine(raw)
            val text = raw.trimStart('\r', '\n')
            if (!syllables || newLine || lines.isEmpty()) {
                lines += Building(ms)
                if (!syllables) {
                    lines.last().text = Lrc.trim(text)
                    continue
                }
            }
            lines.last().words += LyricWord(ms, text)
        }
        val out = lines.map { line ->
            if (!syllables) return@map LyricLine(line.timeMs, line.text)
            val joined = line.words.joinToString("") { it.text }
            if (joined.trimStart(' ', '\t').isEmpty()) return@map LyricLine(line.timeMs, "")
            var words: List<LyricWord> = line.words
            if (words.size < 2) {
                words = emptyList()
            } else {
                val w = words.toMutableList()
                w[0] = w[0].copy(text = w[0].text.trimStart(' ', '\t'))
                w[w.size - 1] = w.last().copy(text = w.last().text.trimEnd(' ', '\t'))
                words = w.filter { it.text.isNotEmpty() }
            }
            LyricLine(line.timeMs, Lrc.trim(joined), words)
        }.sortedBy { it.timeMs }
        if (out.none { it.text.isNotEmpty() }) return null
        return Lyrics(synced = true, lines = out, source = SOURCE_TAG)
    }

    /**
     * The lyrics frames of an ID3v2 tag at the start of [bytes] (v2.3 / v2.4; whole-tag and per-frame
     * unsynchronisation). SYLT (synced, ms timestamps) wins over USLT; an USLT text that is LRC counts as synced.
     */
    fun readId3Lyrics(bytes: ByteArray): Lyrics? {
        if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte())
            return null
        val version = u8(bytes, 3)
        val flags = u8(bytes, 5)
        if (version != 3 && version != 4) return null
        val size = minOf(synchsafe(bytes, 6), (bytes.size - 10).toLong()).toInt()
        var body = bytes.copyOfRange(10, 10 + size)
        if (version == 3 && (flags and 0x80) != 0) body = deunsync(body) // v2.3: the whole tag is unsynchronised
        var pos = 0L
        if ((flags and 0x40) != 0 && body.size >= 4) { // extended header
            pos = if (version == 3) 4 + be32(body, 0) else synchsafe(body, 0)
        }
        var sylt: Lyrics? = null
        var uslt: Lyrics? = null
        var syltSeen = false
        var usltSeen = false
        while (pos + 10 <= body.size) {
            val p = pos.toInt()
            if (body[p].toInt() == 0) break // padding
            val len = if (version == 4) synchsafe(body, p + 4) else be32(body, p + 4)
            val fmt = u8(body, p + 9)
            if (len > body.size - pos - 10) break // malformed
            val id = String(body, p, 4, Charsets.ISO_8859_1)
            var data = body.copyOfRange(p + 10, p + 10 + len.toInt())
            pos += 10 + len
            if (id != "SYLT" && id != "USLT") continue
            if (version == 3) {
                if ((fmt and 0xC0) != 0) continue // compressed / encrypted
                if ((fmt and 0x20) != 0) data = data.copyOfRange(minOf(1, data.size), data.size) // group id
            } else {
                if ((fmt and 0x0C) != 0) continue // compressed / encrypted
                if ((fmt and 0x40) != 0) data = data.copyOfRange(minOf(1, data.size), data.size) // group id
                if ((fmt and 0x02) != 0 || (flags and 0x80) != 0) data = deunsync(data)
                if ((fmt and 0x01) != 0) data = data.copyOfRange(minOf(4, data.size), data.size) // data length indicator
            }
            // The first frame of each kind decides (like the C++ "!sylt" / "!uslt" on an optional).
            if (id == "SYLT" && !syltSeen) {
                sylt = parseSylt(data); syltSeen = sylt != null
            } else if (id == "USLT" && !usltSeen) {
                uslt = parseUslt(data); usltSeen = uslt != null
            }
        }
        return sylt ?: uslt
    }

    // ---- other containers (replacement for System.Music.Lyrics) ---------------------------------------------------
    /** FLAC Vorbis comment LYRICS (else UNSYNCEDLYRICS), starting at [start] ("fLaC"). "" when there is none. */
    private fun flacLyrics(ch: SeekableByteChannel, start: Long): String {
        var pos = start + 4
        val size = ch.size()
        while (pos + 4 <= size) {
            val h = readAt(ch, pos, 4)
            if (h.size < 4) return ""
            val last = (u8(h, 0) and 0x80) != 0
            val type = u8(h, 0) and 0x7F
            val len = (u8(h, 1).toLong() shl 16) or (u8(h, 2).toLong() shl 8) or u8(h, 3).toLong()
            pos += 4
            if (type == 4) {
                if (len > MAX_CONTAINER_LYRICS_BYTES) return ""
                val b = readAt(ch, pos, len.toInt())
                var i = 0
                fun u32(): Long = if (i + 4 <= b.size) le32(b, i).also { i += 4 } else -1
                val vendor = u32()
                if (vendor < 0 || i + vendor > b.size) return ""
                i += vendor.toInt()
                val count = u32()
                var lyrics = ""
                var unsynced = ""
                for (k in 0 until count) {
                    val n = u32()
                    if (n < 0 || i + n > b.size) break
                    val c = String(b, i, n.toInt(), Charsets.UTF_8)
                    i += n.toInt()
                    val eq = c.indexOf('=')
                    if (eq <= 0) continue
                    when (c.substring(0, eq).uppercase(Locale.ROOT)) {
                        "LYRICS" -> if (lyrics.isEmpty()) lyrics = c.substring(eq + 1)
                        "UNSYNCEDLYRICS" -> if (unsynced.isEmpty()) unsynced = c.substring(eq + 1)
                    }
                }
                return lyrics.ifEmpty { unsynced }
            }
            pos += len
            if (last) break
        }
        return ""
    }

    /** MP4 `moov/udta/meta/ilst/©lyr/data` text. "" when there is none. */
    private fun mp4Lyrics(ch: SeekableByteChannel): String {
        val size = ch.size()
        // Children of a box: (type, payload start, payload end).
        fun children(from: Long, to: Long): List<Triple<String, Long, Long>> {
            val out = ArrayList<Triple<String, Long, Long>>()
            var p = from
            while (p + 8 <= to) {
                val h = readAt(ch, p, 16)
                if (h.size < 8) break
                var boxSize = be32(h, 0)
                val type = String(h, 4, 4, Charsets.ISO_8859_1)
                var header = 8L
                if (boxSize == 1L) {
                    if (h.size < 16) break
                    boxSize = (be32(h, 8) shl 32) or be32(h, 12)
                    header = 16
                } else if (boxSize == 0L) {
                    boxSize = to - p
                }
                if (boxSize < header || p + boxSize > to) break
                out += Triple(type, p + header, p + boxSize)
                p += boxSize
            }
            return out
        }
        fun child(list: List<Triple<String, Long, Long>>, type: String) = list.firstOrNull { it.first == type }
        val moov = child(children(0, size), "moov") ?: return ""
        val udta = child(children(moov.second, moov.third), "udta") ?: return ""
        val meta = child(children(udta.second, udta.third), "meta") ?: return ""
        // ISO "meta" is a full box (4 bytes version / flags); QuickTime's is not: then "hdlr" follows at once.
        val peek = readAt(ch, meta.second + 4, 4)
        val metaStart = if (peek.size == 4 && String(peek, Charsets.ISO_8859_1) == "hdlr") meta.second else meta.second + 4
        val ilst = child(children(metaStart, meta.third), "ilst") ?: return ""
        val lyr = child(children(ilst.second, ilst.third), "©lyr") ?: return ""
        val data = child(children(lyr.second, lyr.third), "data") ?: return ""
        val len = data.third - data.second - 8 // type indicator + locale
        if (len <= 0 || len > MAX_CONTAINER_LYRICS_BYTES) return ""
        return String(readAt(ch, data.second + 8, len.toInt()), Charsets.UTF_8)
    }

    private fun embedded(audio: LocalAudioFile): Lyrics? {
        val ext = audio.name.substringAfterLast('.', "").lowercase(Locale.ROOT).let { if (it.isEmpty()) "" else ".$it" }
        val ch = audio.open() ?: return null
        ch.use {
            val head = readAt(ch, 0, 10)
            var afterId3 = 0L
            if (head.size == 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                val size = synchsafe(head, 6)
                afterId3 = size + 10
                val tag = readAt(ch, 0, minOf(MAX_TAG_BYTES.toLong(), size + 10).toInt())
                readId3Lyrics(tag)?.let { return it }
                if (ext == ".mp3") return null
            }
            val magic = readAt(ch, afterId3, 8)
            val text = when {
                magic.size >= 4 && String(magic, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> flacLyrics(ch, afterId3)
                magic.size >= 8 && String(magic, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4Lyrics(ch)
                else -> ""
            }
            if (Lrc.trim(text).isEmpty()) return null
            val l = Lrc.parse(text)
            if (l.lines.isEmpty()) return null
            return l.copy(source = SOURCE_TAG)
        }
    }

    /**
     * Lyrics stored with an audio file: `<stem>.lrc` next to it, else its tags. A synced sidecar wins, then synced
     * tag lyrics, then the unsynced sidecar, then unsynced tag lyrics. null when there are none. Blocking.
     */
    fun readLocal(audio: LocalAudioFile): Lyrics? {
        audio.open()?.close() ?: return null
        var side: Lyrics? = null
        try {
            audio.openSidecar()?.use { ch ->
                val l = Lrc.parse(decodeText(readAt(ch, 0, MAX_SIDECAR_BYTES)))
                if (l.lines.isNotEmpty()) {
                    val withSource = l.copy(source = SOURCE_FILE)
                    if (withSource.synced) return withSource
                    side = withSource
                }
            }
        } catch (e: IOException) {
            Log.w("lyrics", "reading the .lrc of ${audio.name} failed: ${e.message}")
        }
        var tag: Lyrics? = null
        try {
            tag = embedded(audio)
        } catch (e: Exception) {
            Log.w("lyrics", "reading embedded lyrics failed: ${e.message}")
        }
        if (tag != null && tag.synced) return tag
        return side ?: tag
    }

    fun readLocal(file: File): Lyrics? = readLocal(FileAudio(file))
}
