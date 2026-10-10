package com.app.shadetube.localfiles

import java.io.File
import java.io.RandomAccessFile

/**
 * ReplayGain track gain of a local audio file, for loudness normalisation (port of audio/ReplayGain; the player's
 * loudness stage adds it for local files: no tag = no gain).
 *
 * Only the tag areas are read, seeking over everything else (covers, audio): ID3v2 at the start of the file (TXXX
 * "REPLAYGAIN_TRACK_GAIN"; MP3, and AAC / FLAC files with a leading ID3 tag), FLAC Vorbis comments
 * ("REPLAYGAIN_TRACK_GAIN="), Ogg Vorbis / Opus comments (Android addition: they play here) and MP4 / M4A iTunes
 * freeform atoms (----:com.apple.iTunes:replaygain_track_gain). ReplayGain 2 gains take a track to -18 LUFS.
 * Blocking file I/O: call from a worker.
 */
object ReplayGain {
    private const val KEY = "replaygain_track_gain"
    /** A tag read in one piece (unsynchronised ID3). */
    private const val MAX_TAG_BYTES = 16 shl 20
    /** One TXXX frame / Vorbis comment block / ilst item worth reading. */
    private const val MAX_FIELD_BYTES = 1 shl 16

    /** The file's track gain in dB, null when it has none (or the file can't be read). */
    fun readTrackGainDb(file: File): Float? =
        runCatching { RandomAccessFile(file, "r").channel.use { lookup(ChannelSource(it)) } }.getOrNull()

    fun readTrackGainDb(src: ByteSource): Float? = runCatching { lookup(src) }.getOrNull()

    private fun lookup(r: ByteSource): Float? = id3(r) ?: flac(r) ?: ogg(r) ?: mp4(r)

    // ---- helpers --------------------------------------------------------------------------------------------------
    /** Exactly [n] bytes at [offset]; null when they aren't all there. */
    private fun ByteSource.exact(offset: Long, n: Int): ByteArray? {
        if (offset < 0 || n < 0 || offset > size || n > size - offset) return null
        val b = read(offset, n)
        return if (b.size == n) b else null
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
    private fun be24(b: ByteArray, i: Int): Long = (u8(b, i).toLong() shl 16) or (u8(b, i + 1).toLong() shl 8) or u8(b, i + 2).toLong()
    private fun le32(b: ByteArray, i: Int): Long =
        (u8(b, i + 3).toLong() shl 24) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 1).toLong() shl 8) or u8(b, i).toLong()
    private fun syncsafe(b: ByteArray, i: Int): Long =
        ((u8(b, i) and 0x7F).toLong() shl 21) or ((u8(b, i + 1) and 0x7F).toLong() shl 14) or
            ((u8(b, i + 2) and 0x7F).toLong() shl 7) or (u8(b, i + 3) and 0x7F).toLong()

    private fun equalsKey(s: String): Boolean = s.length == KEY.length && s.lowercase(java.util.Locale.ROOT) == KEY

    /**
     * "-6.54 dB", "+1.20 dB", "-6,54dB", " 3 " -> dB; null for anything else or implausible values (|x| > 64).
     */
    fun parseGain(s: String): Float? {
        var i = 0
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        val num = StringBuilder()
        if (i < s.length && (s[i] == '+' || s[i] == '-')) num.append(s[i++])
        var digits = false
        var point = false
        while (i < s.length) {
            val c = s[i]
            if (c in '0'..'9') {
                num.append(c); digits = true
            } else if ((c == '.' || c == ',') && !point) {
                num.append('.'); point = true
            } else {
                break
            }
            i++
        }
        if (!digits) return null
        while (i < s.length && s[i] == ' ') i++
        val rest = s.substring(i)
        if (rest.isNotEmpty()) {
            val db = rest.length >= 2 && (rest[0] == 'd' || rest[0] == 'D') && (rest[1] == 'b' || rest[1] == 'B')
            if (!db) return null
            if (rest.substring(2).any { it != ' ' && it != '\u0000' }) return null
        }
        val v = num.toString().let { if (it.endsWith(".")) it + "0" else it }.toFloatOrNull() ?: return null
        if (!(v >= -64f && v <= 64f)) return null
        return v
    }

    /** ID3 text in `enc` to plain ASCII-ish text (only digits, signs and "dB" matter): UTF-16 units < 128 are kept. */
    private fun id3Text(p: ByteArray, from: Int, n: Int, enc: Int): String {
        val out = StringBuilder()
        if (enc == 1 || enc == 2) {
            var be = enc == 2
            var i = 0
            if (enc == 1 && n >= 2) { // BOM
                if (u8(p, from) == 0xFE && u8(p, from + 1) == 0xFF) be = true
                i = 2
            }
            while (i + 1 < n) {
                val a = u8(p, from + i)
                val b = u8(p, from + i + 1)
                val cu = if (be) (a shl 8) or b else (b shl 8) or a
                if (cu == 0) break
                out.append(if (cu < 128) cu.toChar() else '?')
                i += 2
            }
        } else {
            var i = 0
            while (i < n && p[from + i].toInt() != 0) {
                out.append((p[from + i].toInt() and 0xFF).toChar()); i++
            }
        }
        return out.toString()
    }

    /** TXXX body: encoding, description (terminated), value. */
    private fun txxx(p: ByteArray, from: Int, n: Int): Float? {
        if (n < 2) return null
        val enc = u8(p, from)
        val wide = enc == 1 || enc == 2
        val i = 1
        // Find the description terminator (one zero byte, or an aligned zero code unit for UTF-16).
        var end = i
        if (wide) {
            while (end + 1 < n && !(p[from + end].toInt() == 0 && p[from + end + 1].toInt() == 0)) end += 2
        } else {
            while (end < n && p[from + end].toInt() != 0) end++
        }
        if (end >= n) return null
        val desc = id3Text(p, from + i, end - i, enc)
        if (!equalsKey(desc)) return null
        val value = end + if (wide) 2 else 1
        if (value > n) return null
        return parseGain(id3Text(p, from + value, n - value, enc))
    }

    private fun id3(r: ByteSource): Float? {
        val h = r.exact(0, 10) ?: return null
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return null
        val major = u8(h, 3)
        val flags = u8(h, 5)
        if (major < 2 || major > 4) return null
        val tagEnd = 10 + syncsafe(h, 6)
        // A tag-wide unsynchronisation (v2.2 / v2.3) scrambles frame boundaries: read the tag in one piece and undo it.
        if ((flags and 0x80) != 0 && major < 4) {
            val n = minOf(tagEnd - 10, MAX_TAG_BYTES.toLong(), if (r.size > 10) r.size - 10 else 0).toInt()
            val raw = r.exact(10, n) ?: return null
            val body = TagReader.deunsync(raw)
            val tag = h.copyOf()
            tag[5] = (tag[5].toInt() and 0x7F).toByte()
            val len = body.size // the tag shrank: fix its (syncsafe) size
            for (k in 0 until 4) tag[6 + k] = ((len shr (7 * (3 - k))) and 0x7F).toByte()
            return id3(ArraySource(tag + body))
        }
        var pos = 10L
        if ((flags and 0x40) != 0 && major >= 3) { // extended header
            val e = r.exact(pos, 4) ?: return null
            pos += if (major == 4) syncsafe(e, 0) else 4 + be32(e, 0)
        }
        val headerLen = if (major == 2) 6 else 10
        while (pos + headerLen <= tagEnd) {
            val fh = r.exact(pos, headerLen) ?: break
            if (fh[0].toInt() == 0) break // padding
            val size = when (major) { 2 -> be24(fh, 3); 4 -> syncsafe(fh, 4); else -> be32(fh, 4) }
            val data = pos + headerLen
            if (size == 0L || data + size > tagEnd) break
            val id = String(fh, 0, if (major == 2) 3 else 4, Charsets.ISO_8859_1)
            val isTxxx = if (major == 2) id == "TXX" else id == "TXXX"
            // v2.4 per-frame flags: compression / encryption can't be read here; unsynchronisation is undone below.
            val format = if (major == 4) u8(fh, 9) else 0
            if (isTxxx && size <= MAX_FIELD_BYTES && (format and 0x0C) == 0) {
                var body = r.exact(data, size.toInt()) ?: break
                val skip = if ((format and 0x01) != 0) 4 else 0 // data length indicator
                if ((format and 0x02) != 0) body = TagReader.deunsync(body) // frame unsynchronisation
                if (skip < body.size) txxx(body, skip, body.size - skip)?.let { return it }
            }
            pos = data + size
        }
        return null
    }

    private fun id3Size(r: ByteSource): Long {
        val h = r.exact(0, 10) ?: return 0
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return 0
        return 10 + syncsafe(h, 6) + if ((u8(h, 5) and 0x10) != 0) 10 else 0 // + footer
    }

    /** A Vorbis comment block (little-endian lengths) starting at [from]: the gain, or null. */
    private fun vorbisComments(p: ByteArray, from: Int): Float? {
        val n = p.size
        if (n - from < 8) return null
        var i = from + 4 + le32(p, from).toInt() // vendor string
        if (i < from || i + 4 > n) return null
        val count = le32(p, i)
        i += 4
        var k = 0L
        while (k < count && i + 4 <= n) {
            k++
            val cl = le32(p, i)
            i += 4
            if (cl > n - i) break
            val comment = String(p, i, cl.toInt(), Charsets.UTF_8)
            i += cl.toInt()
            val eq = comment.indexOf('=')
            if (eq >= 0 && equalsKey(comment.substring(0, eq))) return parseGain(comment.substring(eq + 1))
        }
        return null
    }

    private fun flac(r: ByteSource): Float? {
        var pos = id3Size(r) // a (non-standard) ID3 tag in front
        val magic = r.exact(pos, 4) ?: return null
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null
        pos += 4
        for (guard in 0 until 64) {
            val h = r.exact(pos, 4) ?: return null
            val last = (u8(h, 0) and 0x80) != 0
            val type = u8(h, 0) and 0x7F
            val len = be24(h, 1)
            if (type == 4 && len <= MAX_TAG_BYTES) { // VORBIS_COMMENT
                val block = r.exact(pos + 4, len.toInt()) ?: return null
                return vorbisComments(block, 0)
            }
            if (last) break
            pos += 4 + len
        }
        return null
    }

    /** Ogg Vorbis / Opus: the comment packet (the second one), reassembled from the first pages. */
    private fun ogg(r: ByteSource): Float? {
        val start = id3Size(r)
        val magic = r.exact(start, 4) ?: return null
        if (String(magic, Charsets.ISO_8859_1) != "OggS") return null
        val packets = ArrayList<ByteArray>()
        val cur = java.io.ByteArrayOutputStream()
        var pos = start
        var total = 0L
        while (packets.size < 2 && total < MAX_TAG_BYTES) {
            val h = r.exact(pos, 27) ?: break
            if (String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") break
            val segs = u8(h, 26)
            val table = r.exact(pos + 27, segs) ?: break
            var dataPos = pos + 27 + segs
            var run = 0
            for (k in 0 until segs) {
                val l = u8(table, k)
                run += l
                if (l < 255) {
                    cur.write(r.read(dataPos, run))
                    dataPos += run; total += run; run = 0
                    packets += cur.toByteArray()
                    cur.reset()
                    if (packets.size >= 2) break
                }
            }
            if (run > 0) { cur.write(r.read(dataPos, run)); dataPos += run; total += run }
            pos = dataPos
        }
        if (packets.size < 2) return null
        val c = packets[1]
        fun startsWith(s: String) = c.size >= s.length && String(c, 0, s.length, Charsets.ISO_8859_1) == s
        return when {
            startsWith("\u0003vorbis") -> vorbisComments(c, 7)
            startsWith("OpusTags") -> vorbisComments(c, 8)
            else -> null
        }
    }

    // ---- MP4 ------------------------------------------------------------------------------------------------------
    private class Box(val content: Long, val end: Long, val type: String)

    /** Box header at `pos` inside [.., limit): null when there is none. */
    private fun boxAt(r: ByteSource, pos: Long, limit: Long): Box? {
        if (pos + 8 > limit) return null
        val h = r.exact(pos, 8) ?: return null
        var size = be32(h, 0)
        var header = 8L
        if (size == 1L) {
            if (pos + 16 > limit) return null
            val x = r.exact(pos + 8, 8) ?: return null
            size = (be32(x, 0) shl 32) or be32(x, 4)
            header = 16
        } else if (size == 0L) {
            size = limit - pos
        }
        if (size < header || pos + size > limit) return null
        return Box(pos + header, pos + size, String(h, 4, 4, Charsets.ISO_8859_1))
    }

    private fun findChild(r: ByteSource, from: Long, to: Long, type: String): Box? {
        var pos = from
        while (true) {
            val b = boxAt(r, pos, to) ?: return null
            if (b.type == type) return b
            pos = b.end
        }
    }

    private fun mp4(r: ByteSource): Float? {
        val ftyp = boxAt(r, 0, r.size) ?: return null
        if (ftyp.type != "ftyp") return null
        val moov = findChild(r, 0, r.size, "moov") ?: return null
        val udta = findChild(r, moov.content, moov.end, "udta") ?: return null
        val meta = findChild(r, udta.content, udta.end, "meta") ?: return null
        // "meta" is a full box (4 bytes version / flags) in iTunes files; QuickTime-style files omit them.
        val probe = boxAt(r, meta.content, meta.end)
        val metaContent = if (probe != null && probe.type == "hdlr") meta.content else meta.content + 4
        val ilst = findChild(r, metaContent, meta.end, "ilst") ?: return null
        var pos = ilst.content
        while (true) {
            val item = boxAt(r, pos, ilst.end) ?: break
            pos = item.end
            if (item.type != "----") continue
            var name = ""
            var value: Float? = null
            var sp = item.content
            while (true) {
                val sub = boxAt(r, sp, item.end) ?: break
                sp = sub.end
                val len = sub.end - sub.content
                if (len < 4 || len > MAX_FIELD_BYTES) continue
                if (sub.type == "name") {
                    val buf = r.exact(sub.content, len.toInt()) ?: continue
                    name = String(buf, 4, buf.size - 4, Charsets.UTF_8) // version / flags
                } else if (sub.type == "data" && len > 8) {
                    val buf = r.exact(sub.content, len.toInt()) ?: continue
                    value = parseGain(String(buf, 8, buf.size - 8, Charsets.UTF_8))
                }
            }
            if (equalsKey(name) && value != null) return value
        }
        return null
    }

    // ---- exposed for tests: the lookups on in-memory data, each starting at the beginning of a file ----------------
    fun fromId3(data: ByteArray): Float? = id3(ArraySource(data))
    fun fromFlac(data: ByteArray): Float? = flac(ArraySource(data))
    fun fromMp4(data: ByteArray): Float? = mp4(ArraySource(data))
    fun fromOgg(data: ByteArray): Float? = ogg(ArraySource(data))
}
