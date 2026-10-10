package com.app.shadetube.localfiles

import com.app.shadetube.lyrics.LocalLyrics
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.Base64
import java.util.Locale

/** Random access to a file's bytes (a file channel, a content URI's descriptor, or memory in tests). */
interface ByteSource {
    val size: Long

    /** Up to [n] bytes at [pos] (fewer at the end of the file). */
    fun read(pos: Long, n: Int): ByteArray
}

class ArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(pos: Long, n: Int): ByteArray {
        if (pos < 0 || pos >= bytes.size || n <= 0) return ByteArray(0)
        return bytes.copyOfRange(pos.toInt(), minOf(bytes.size.toLong(), pos + n).toInt())
    }
}

class ChannelSource(private val ch: SeekableByteChannel) : ByteSource {
    override val size: Long = ch.size()
    override fun read(pos: Long, n: Int): ByteArray {
        if (pos < 0 || pos >= size || n <= 0) return ByteArray(0)
        val buf = ByteBuffer.allocate(minOf(n.toLong(), size - pos).toInt())
        ch.position(pos)
        while (buf.hasRemaining()) if (ch.read(buf) < 0) break
        return if (buf.position() == buf.capacity()) buf.array() else buf.array().copyOf(buf.position())
    }
}

/** What a file's tags say (the Windows property system's System.Title, Music.Artist, ... in the C++ app). */
data class LocalTags(
    val title: String = "",
    val artists: List<String> = emptyList(),
    val albumArtist: String = "",
    val album: String = "",
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val durationMs: Int = 0,
    /** Embedded picture (front cover preferred), null = none. */
    val picture: ByteArray? = null,
) {
    fun merge(other: LocalTags): LocalTags = LocalTags(
        title = title.ifEmpty { other.title },
        artists = artists.ifEmpty { other.artists },
        albumArtist = albumArtist.ifEmpty { other.albumArtist },
        album = album.ifEmpty { other.album },
        discNumber = if (discNumber > 0) discNumber else other.discNumber,
        trackNumber = if (trackNumber > 0) trackNumber else other.trackNumber,
        year = if (year > 0) year else other.year,
        durationMs = if (durationMs > 0) durationMs else other.durationMs,
        picture = picture ?: other.picture,
    )

    override fun equals(other: Any?): Boolean = other is LocalTags && title == other.title && artists == other.artists &&
        albumArtist == other.albumArtist && album == other.album && discNumber == other.discNumber &&
        trackNumber == other.trackNumber && year == other.year && durationMs == other.durationMs &&
        (picture?.contentEquals(other.picture ?: ByteArray(0)) ?: (other.picture == null))

    override fun hashCode(): Int = title.hashCode() * 31 + artists.hashCode()
}

/**
 * Reads the tags of an audio file from its bytes - the replacement for the Windows property system the C++ scanner
 * used (System.Title, Music.Artist, Music.AlbumArtist, Music.AlbumTitle, Music.TrackNumber, Music.DiscNumber,
 * Media.Duration, Media.Year, System.ThumbnailStream). Only the tag areas are read, seeking over the audio:
 *  - ID3v2.2 / 2.3 / 2.4 (text frames, APIC / PIC; whole-tag and per-frame unsynchronisation) and ID3v1 at the end;
 *  - FLAC STREAMINFO (duration), VORBIS_COMMENT, PICTURE;
 *  - Ogg Vorbis / Opus comments (incl. METADATA_BLOCK_PICTURE) and the last page's granule (duration);
 *  - MP4 / M4A `moov/mvhd` (duration) and `moov/udta/meta/ilst` (©nam ©ART aART ©alb trkn disk ©day covr);
 *  - WAV `fmt ` / `data` (duration), LIST/INFO and an embedded "id3 " chunk;
 *  - MP3 duration from the Xing / Info / VBRI header, else the bitrate (CBR).
 * Multi-valued artists ("A; B", or v2.4 / Vorbis multiple values) are split and de-duplicated like the C++
 * propStrings. Blocking: call from a worker.
 */
object TagReader {
    /** An ID3 tag read in one piece (unsynchronised tags); the C++ id3Picture reader refused anything bigger. */
    private const val MAX_TAG_BYTES = 32 shl 20
    /** A cover picture (the C++ took System.ThumbnailStream up to 16 MB). */
    const val MAX_PICTURE_BYTES = 16 shl 20
    /** One text frame / comment block / ilst item worth reading. */
    private const val MAX_FIELD_BYTES = 1 shl 20

    fun read(file: File, wantPicture: Boolean = true): LocalTags? =
        runCatching { java.io.RandomAccessFile(file, "r").channel.use { read(ChannelSource(it), wantPicture) } }.getOrNull()

    /** The tags of the file in [src]; an empty [LocalTags] when it has none. Throws IOException on read errors. */
    fun read(src: ByteSource, wantPicture: Boolean = true): LocalTags {
        val head = src.read(0, 12)
        var tags = LocalTags()
        var afterId3 = 0L
        if (head.size >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            tags = id3v2(src, wantPicture)
            afterId3 = id3Size(src)
        }
        val magic = src.read(afterId3, 12)
        fun at(i: Int, s: String) = magic.size >= i + s.length && String(magic, i, s.length, Charsets.ISO_8859_1) == s
        val more = when {
            at(0, "fLaC") -> flac(src, afterId3, wantPicture)
            at(0, "OggS") -> ogg(src, afterId3, wantPicture)
            afterId3 == 0L && at(4, "ftyp") -> mp4(src, wantPicture)
            afterId3 == 0L && at(0, "RIFF") && at(8, "WAVE") -> wav(src, wantPicture)
            else -> null
        }
        if (more != null) tags = tags.merge(more)
        else {
            if (tags.durationMs == 0) mp3Duration(src, afterId3)?.let { tags = tags.copy(durationMs = it) }
            id3v1(src)?.let { tags = tags.merge(it) }
        }
        return tags
    }

    // ---- byte helpers ---------------------------------------------------------------------------------------------
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be24(b: ByteArray, i: Int) = (u8(b, i) shl 16) or (u8(b, i + 1) shl 8) or u8(b, i + 2)
    private fun be32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
    private fun le32(b: ByteArray, i: Int): Long =
        (u8(b, i + 3).toLong() shl 24) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 1).toLong() shl 8) or u8(b, i).toLong()
    private fun le64(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or u8(b, i + k).toLong()
        return v
    }
    private fun syncsafe(b: ByteArray, i: Int): Long =
        ((u8(b, i) and 0x7F).toLong() shl 21) or ((u8(b, i + 1) and 0x7F).toLong() shl 14) or
            ((u8(b, i + 2) and 0x7F).toLong() shl 7) or (u8(b, i + 3) and 0x7F).toLong()

    /** 0xFF 0x00 -> 0xFF (the unsynchronisation scheme). */
    internal fun deunsync(s: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.size)
        var i = 0
        while (i < s.size) {
            out.write(s[i].toInt())
            if (s[i] == 0xFF.toByte() && i + 1 < s.size && s[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun trimTag(s: String): String = s.trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' || it == '\u0000' }

    /** Up to 3 ASCII digits after optional spaces ("2/2" -> 2); 0 when there are none. */
    fun leadingNumber(s: String, maxDigits: Int = 3): Int {
        var i = 0
        while (i < s.length && s[i] == ' ') i++
        var n = 0
        var d = 0
        while (i < s.length && d < maxDigits && s[i] in '0'..'9') {
            n = n * 10 + (s[i] - '0'); i++; d++
        }
        return n
    }

    /** "2024-05-01", "2024" -> 2024; 0 when it does not start with a year. */
    private fun yearOf(s: String): Int {
        val t = trimTag(s)
        return if (t.length >= 4 && t.substring(0, 4).all { it in '0'..'9' }) t.substring(0, 4).toInt() else 0
    }

    /** Multi-valued strings: values, each possibly "A; B" -> trimmed, de-duplicated, in order. */
    fun splitValues(values: List<String>): List<String> {
        val out = ArrayList<String>()
        for (v in values) for (part in v.split(';')) {
            val p = trimTag(part)
            if (p.isNotEmpty() && p !in out) out += p
        }
        return out
    }

    // ---- ID3v2 ----------------------------------------------------------------------------------------------------
    internal fun id3Size(src: ByteSource): Long {
        val h = src.read(0, 10)
        if (h.size < 10 || h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return 0
        return 10 + syncsafe(h, 6) + if ((u8(h, 5) and 0x10) != 0) 10 else 0 // + footer
    }

    private fun utf16(d: ByteArray, from: Int, to: Int, bigEndianDefault: Boolean, bom: Boolean): String {
        var be = bigEndianDefault
        var i = from
        if (bom && to - from >= 2) {
            if (u8(d, i) == 0xFE && u8(d, i + 1) == 0xFF) { be = true; i += 2 } else if (u8(d, i) == 0xFF && u8(d, i + 1) == 0xFE) { be = false; i += 2 }
        }
        val sb = StringBuilder((to - i) / 2)
        while (i + 1 < to) {
            sb.append((if (be) (u8(d, i) shl 8) or u8(d, i + 1) else u8(d, i) or (u8(d, i + 1) shl 8)).toChar())
            i += 2
        }
        return sb.toString()
    }

    /** ID3 text in encoding [enc] from [from] to [to]. */
    private fun id3Text(d: ByteArray, from: Int, to: Int, enc: Int): String = when (enc) {
        0 -> LocalLyrics.decodeText(d.copyOfRange(from, to)) // ISO-8859-1 by the spec; older taggers wrote the ANSI code page
        1 -> utf16(d, from, to, bigEndianDefault = false, bom = true)
        2 -> utf16(d, from, to, bigEndianDefault = true, bom = false)
        else -> String(d, from, to - from, Charsets.UTF_8)
    }

    /** The values of a text frame body (v2.4 separates several with NUL; every value of UTF-16 has its own BOM). */
    private fun textValues(d: ByteArray): List<String> {
        if (d.isEmpty()) return emptyList()
        val enc = u8(d, 0)
        if (enc > 3) return emptyList()
        val wide = enc == 1 || enc == 2
        val step = if (wide) 2 else 1
        val out = ArrayList<String>()
        var start = 1
        var i = 1
        while (i < d.size) {
            val nul = if (wide) i + 1 < d.size && d[i].toInt() == 0 && d[i + 1].toInt() == 0 else d[i].toInt() == 0
            if (nul) {
                if (i > start) out += trimTag(id3Text(d, start, i, enc))
                start = i + step
            }
            i += step
        }
        if (start < d.size) out += trimTag(id3Text(d, start, d.size, enc))
        return out.filter { it.isNotEmpty() }
    }

    /** APIC (v2.3 / 2.4) or PIC (v2.2): (picture type, image bytes). */
    private fun pictureFrame(d: ByteArray, v22: Boolean): Pair<Int, ByteArray>? {
        if (d.size < 4) return null
        val enc = u8(d, 0)
        var p = 1
        if (v22) {
            p += 3 // "JPG" / "PNG"
        } else {
            while (p < d.size && d[p].toInt() != 0) p++ // mime
            p++
        }
        if (p >= d.size) return null
        val type = u8(d, p)
        p++
        if (enc == 1 || enc == 2) { // UTF-16 description: double-NUL terminated
            while (p + 1 < d.size && !(d[p].toInt() == 0 && d[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < d.size && d[p].toInt() != 0) p++
            p++
        }
        if (p >= d.size) return null
        return type to d.copyOfRange(p, d.size)
    }

    private fun id3v2(file: ByteSource, wantPicture: Boolean): LocalTags {
        val h = file.read(0, 10)
        val ver = u8(h, 3)
        if (ver < 2 || ver > 4) return LocalTags()
        val flags = u8(h, 5)
        val tagSize = syncsafe(h, 6)
        if (tagSize <= 0) return LocalTags()
        var src: ByteSource = file
        var tagEnd = 10 + tagSize
        // A tag-wide unsynchronisation (v2.2 / v2.3) scrambles frame boundaries: read the tag in one piece and undo it.
        if ((flags and 0x80) != 0 && ver < 4) {
            if (tagSize > MAX_TAG_BYTES) return LocalTags()
            val body = deunsync(file.read(10, tagSize.toInt()))
            src = ArraySource(h + body)
            tagEnd = 10L + body.size
        }
        var pos = 10L
        if ((flags and 0x40) != 0 && ver >= 3) { // extended header
            val e = src.read(pos, 4)
            if (e.size < 4) return LocalTags()
            pos += if (ver == 4) syncsafe(e, 0) else 4 + be32(e, 0)
        }
        val headLen = if (ver == 2) 6 else 10
        var title = ""
        var artists = emptyList<String>()
        var albumArtist = ""
        var album = ""
        var track = 0
        var disc = 0
        var year = 0
        var lengthMs = 0
        var picture: ByteArray? = null
        var pictureType = -1
        while (pos + headLen <= tagEnd) {
            val fh = src.read(pos, headLen)
            if (fh.size < headLen || fh[0].toInt() == 0) break // padding
            val id = String(fh, 0, if (ver == 2) 3 else 4, Charsets.ISO_8859_1)
            val size = when (ver) { 2 -> be24(fh, 3).toLong(); 4 -> syncsafe(fh, 4); else -> be32(fh, 4) }
            val fmt = if (ver == 2) 0 else u8(fh, 9)
            val data = pos + headLen
            if (size <= 0 || data + size > tagEnd) break
            pos = data + size
            val isPicture = id == "APIC" || id == "PIC"
            val wanted = isPicture && wantPicture || id in TEXT_FRAMES
            if (!wanted) continue
            if (size > (if (isPicture) MAX_PICTURE_BYTES + 1024 else MAX_FIELD_BYTES).toLong()) continue
            var d = src.read(data, size.toInt())
            if (ver == 3) {
                if ((fmt and 0xC0) != 0) continue // compressed / encrypted
                if ((fmt and 0x20) != 0) d = d.copyOfRange(minOf(1, d.size), d.size) // group id
            } else if (ver == 4) {
                if ((fmt and 0x0C) != 0) continue // compressed / encrypted: not supported
                if ((fmt and 0x40) != 0) d = d.copyOfRange(minOf(1, d.size), d.size) // group id
                if ((fmt and 0x02) != 0 || (flags and 0x80) != 0) d = deunsync(d)
                if ((fmt and 0x01) != 0) d = d.copyOfRange(minOf(4, d.size), d.size) // data length indicator
            }
            if (isPicture) {
                val pic = pictureFrame(d, ver == 2) ?: continue
                // Front cover (type 3) preferred, else the first picture.
                if (picture == null || (pic.first == 3 && pictureType != 3)) {
                    picture = pic.second
                    pictureType = pic.first
                }
                continue
            }
            val values = textValues(d)
            if (values.isEmpty()) continue
            when (id) {
                "TIT2", "TT2" -> if (title.isEmpty()) title = values[0]
                "TPE1", "TP1" -> if (artists.isEmpty()) artists = splitValues(values)
                "TPE2", "TP2" -> if (albumArtist.isEmpty()) albumArtist = values[0]
                "TALB", "TAL" -> if (album.isEmpty()) album = values[0]
                "TRCK", "TRK" -> if (track == 0) track = minOf(leadingNumber(values[0], 4), 9999)
                "TPOS", "TPA" -> if (disc == 0) disc = minOf(leadingNumber(values[0]), 999)
                "TYER", "TYE", "TDRC", "TDOR", "TORY" -> if (year == 0) year = yearOf(values[0])
                "TLEN", "TLE" -> if (lengthMs == 0) lengthMs = values[0].trim().toLongOrNull()?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
            }
        }
        return LocalTags(title, artists, albumArtist, album, disc, track, year, lengthMs, picture)
    }

    private val TEXT_FRAMES = setOf(
        "TIT2", "TPE1", "TPE2", "TALB", "TRCK", "TPOS", "TYER", "TDRC", "TDOR", "TORY", "TLEN",
        "TT2", "TP1", "TP2", "TAL", "TRK", "TPA", "TYE", "TLE",
    )

    /** ID3v1 at the end of the file ("TAG" + 30 title, 30 artist, 30 album, 4 year, 30 comment / v1.1 track). */
    internal fun id3v1(src: ByteSource): LocalTags? {
        if (src.size < 128) return null
        val b = src.read(src.size - 128, 128)
        if (b.size < 128 || b[0] != 'T'.code.toByte() || b[1] != 'A'.code.toByte() || b[2] != 'G'.code.toByte()) return null
        fun field(from: Int, len: Int): String {
            var end = from
            while (end < from + len && b[end].toInt() != 0) end++
            return trimTag(LocalLyrics.decodeText(b.copyOfRange(from, end)))
        }
        val artist = field(33, 30)
        return LocalTags(
            title = field(3, 30),
            artists = if (artist.isEmpty()) emptyList() else splitValues(listOf(artist)),
            album = field(63, 30),
            year = yearOf(field(93, 4)),
            trackNumber = if (b[125].toInt() == 0 && b[126].toInt() != 0) u8(b, 126) else 0,
        )
    }

    // ---- Vorbis comments (FLAC, Ogg) ------------------------------------------------------------------------------
    private class Comments {
        val values = LinkedHashMap<String, MutableList<String>>()
        var picture: ByteArray? = null
        var pictureType = -1
        fun first(vararg keys: String): String {
            for (k in keys) values[k]?.firstOrNull { it.isNotEmpty() }?.let { return it }
            return ""
        }
        fun all(key: String): List<String> = values[key] ?: emptyList()
        fun offerPicture(type: Int, data: ByteArray) {
            if (picture == null || (type == 3 && pictureType != 3)) {
                picture = data; pictureType = type
            }
        }
        fun toTags(durationMs: Int): LocalTags {
            val artists = splitValues(all("ARTIST"))
            return LocalTags(
                title = trimTag(first("TITLE")),
                artists = artists,
                albumArtist = trimTag(first("ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST")),
                album = trimTag(first("ALBUM")),
                discNumber = minOf(leadingNumber(first("DISCNUMBER", "DISC")), 999),
                trackNumber = minOf(leadingNumber(first("TRACKNUMBER", "TRACK"), 4), 9999),
                year = yearOf(first("DATE", "YEAR", "ORIGINALDATE")),
                durationMs = durationMs,
                picture = picture,
            )
        }
    }

    /** A Vorbis comment block (vendor + count + "KEY=value" fields, little-endian lengths). */
    private fun parseComments(b: ByteArray, from: Int, into: Comments, wantPicture: Boolean) {
        var i = from
        fun u32(): Long = if (i + 4 <= b.size) le32(b, i).also { i += 4 } else -1
        val vendor = u32()
        if (vendor < 0 || i + vendor > b.size) return
        i += vendor.toInt()
        val count = u32()
        var k = 0L
        while (k < count) {
            k++
            val n = u32()
            if (n < 0 || i + n > b.size) break
            val c = String(b, i, n.toInt(), Charsets.UTF_8)
            i += n.toInt()
            val eq = c.indexOf('=')
            if (eq <= 0) continue
            val key = c.substring(0, eq).uppercase(Locale.ROOT)
            if (key == "METADATA_BLOCK_PICTURE") {
                if (wantPicture) runCatching { Base64.getDecoder().decode(c.substring(eq + 1).trim()) }.getOrNull()
                    ?.let { flacPicture(it)?.let { p -> into.offerPicture(p.first, p.second) } }
                continue
            }
            into.values.getOrPut(key) { ArrayList() } += c.substring(eq + 1)
        }
    }

    /** A FLAC PICTURE block: (picture type, image bytes). */
    private fun flacPicture(b: ByteArray): Pair<Int, ByteArray>? {
        if (b.size < 32) return null
        val type = be32(b, 0).toInt()
        var i = 4
        val mimeLen = be32(b, i).toInt(); i += 4
        if (mimeLen < 0 || i + mimeLen > b.size) return null
        i += mimeLen
        if (i + 4 > b.size) return null
        val descLen = be32(b, i).toInt(); i += 4
        if (descLen < 0 || i + descLen > b.size) return null
        i += descLen + 16 // width, height, depth, colors
        if (i + 4 > b.size) return null
        val len = be32(b, i).toInt(); i += 4
        if (len <= 0 || i + len > b.size) return null
        return type to b.copyOfRange(i, i + len)
    }

    private fun flac(src: ByteSource, start: Long, wantPicture: Boolean): LocalTags {
        var pos = start + 4
        val c = Comments()
        var durationMs = 0
        for (guard in 0 until 128) {
            val h = src.read(pos, 4)
            if (h.size < 4) break
            val last = (u8(h, 0) and 0x80) != 0
            val type = u8(h, 0) and 0x7F
            val len = be24(h, 1)
            pos += 4
            when (type) {
                0 -> { // STREAMINFO: sample rate (20 bits) at 10, total samples (36 bits) at 13
                    val s = src.read(pos, 18)
                    if (s.size >= 18) {
                        val rate = (u8(s, 10) shl 12) or (u8(s, 11) shl 4) or (u8(s, 12) shr 4)
                        val total = ((u8(s, 13) and 0x0F).toLong() shl 32) or be32(s, 14)
                        if (rate > 0 && total > 0) durationMs = (total * 1000 / rate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    }
                }
                4 -> if (len <= MAX_FIELD_BYTES * 16) parseComments(src.read(pos, len), 0, c, wantPicture)
                6 -> if (wantPicture && len <= MAX_PICTURE_BYTES + 4096) flacPicture(src.read(pos, len))?.let { c.offerPicture(it.first, it.second) }
            }
            pos += len
            if (last) break
        }
        return c.toTags(durationMs)
    }

    // ---- Ogg ------------------------------------------------------------------------------------------------------
    private fun ogg(src: ByteSource, start: Long, wantPicture: Boolean): LocalTags {
        // Reassemble the first packets (identification, comments) from the first pages.
        val packets = ArrayList<ByteArray>()
        val cur = java.io.ByteArrayOutputStream()
        var pos = start
        var total = 0L
        while (packets.size < 2 && total < MAX_PICTURE_BYTES + MAX_FIELD_BYTES) {
            val h = src.read(pos, 27)
            if (h.size < 27 || String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") break
            val segs = u8(h, 26)
            val table = src.read(pos + 27, segs)
            if (table.size < segs) break
            var dataPos = pos + 27 + segs
            var run = 0
            for (k in 0 until segs) {
                val l = u8(table, k)
                run += l
                if (l < 255) {
                    cur.write(src.read(dataPos, run))
                    dataPos += run
                    total += run
                    run = 0
                    packets += cur.toByteArray()
                    cur.reset()
                    if (packets.size >= 2) break
                }
            }
            if (run > 0) { cur.write(src.read(dataPos, run)); dataPos += run; total += run }
            pos = dataPos
        }
        if (packets.isEmpty()) return LocalTags()
        val id = packets[0]
        fun startsWith(b: ByteArray, s: String) = b.size >= s.length && String(b, 0, s.length, Charsets.ISO_8859_1) == s
        var rate = 0L
        var preSkip = 0L
        val c = Comments()
        when {
            startsWith(id, "\u0001vorbis") && id.size >= 16 -> {
                rate = le32(id, 12)
                if (packets.size > 1 && startsWith(packets[1], "\u0003vorbis")) parseComments(packets[1], 7, c, wantPicture)
            }
            startsWith(id, "OpusHead") && id.size >= 12 -> {
                rate = 48000 // Opus granules always count 48 kHz samples
                preSkip = (u8(id, 10) or (u8(id, 11) shl 8)).toLong()
                if (packets.size > 1 && startsWith(packets[1], "OpusTags")) parseComments(packets[1], 8, c, wantPicture)
            }
            else -> return LocalTags()
        }
        // Duration: the granule position of the last page.
        var durationMs = 0
        val tailLen = minOf(src.size - start, 65536L).toInt()
        val tail = src.read(src.size - tailLen, tailLen)
        var i = tail.size - 27
        while (i >= 0) {
            if (tail[i] == 'O'.code.toByte() && tail[i + 1] == 'g'.code.toByte() && tail[i + 2] == 'g'.code.toByte() && tail[i + 3] == 'S'.code.toByte()) {
                val granule = le64(tail, i + 6)
                if (granule > 0 && rate > 0) durationMs = ((granule - preSkip).coerceAtLeast(0) * 1000 / rate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                break
            }
            i--
        }
        return c.toTags(durationMs)
    }

    // ---- MP4 ------------------------------------------------------------------------------------------------------
    private class Box(val type: String, val content: Long, val end: Long)

    private fun boxAt(src: ByteSource, pos: Long, limit: Long): Box? {
        if (pos + 8 > limit) return null
        val h = src.read(pos, 16)
        if (h.size < 8) return null
        var size = be32(h, 0)
        var header = 8L
        if (size == 1L) {
            if (h.size < 16 || pos + 16 > limit) return null
            size = (be32(h, 8) shl 32) or be32(h, 12)
            header = 16
        } else if (size == 0L) {
            size = limit - pos
        }
        if (size < header || pos + size > limit) return null
        return Box(String(h, 4, 4, Charsets.ISO_8859_1), pos + header, pos + size)
    }

    private fun children(src: ByteSource, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (true) {
            val b = boxAt(src, p, to) ?: break
            out += b
            p = b.end
        }
        return out
    }

    private fun mp4(src: ByteSource, wantPicture: Boolean): LocalTags {
        val top = children(src, 0, src.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: return LocalTags()
        val moovKids = children(src, moov.content, moov.end)
        var durationMs = 0
        moovKids.firstOrNull { it.type == "mvhd" }?.let { mvhd ->
            val d = src.read(mvhd.content, 32)
            if (d.isNotEmpty()) {
                val (scale, dur) = if (u8(d, 0) == 1 && d.size >= 32) be32(d, 20) to ((be32(d, 24) shl 32) or be32(d, 28))
                else if (d.size >= 20) be32(d, 12) to be32(d, 16) else 0L to 0L
                if (scale > 0 && dur > 0 && dur != 0xFFFFFFFFL) durationMs = (dur * 1000 / scale).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
        val udta = moovKids.firstOrNull { it.type == "udta" } ?: return LocalTags(durationMs = durationMs)
        val meta = children(src, udta.content, udta.end).firstOrNull { it.type == "meta" } ?: return LocalTags(durationMs = durationMs)
        // "meta" is a full box (4 bytes version / flags) in iTunes files; QuickTime-style files omit them.
        val probe = boxAt(src, meta.content, meta.end)
        val metaContent = if (probe != null && probe.type == "hdlr") meta.content else meta.content + 4
        val ilst = children(src, metaContent, meta.end).firstOrNull { it.type == "ilst" } ?: return LocalTags(durationMs = durationMs)
        var title = ""
        val artists = ArrayList<String>()
        var albumArtist = ""
        var album = ""
        var track = 0
        var disc = 0
        var year = 0
        var picture: ByteArray? = null
        for (item in children(src, ilst.content, ilst.end)) {
            val isCover = item.type == "covr"
            if (isCover && !wantPicture) continue
            if (item.type !in MP4_ITEMS) continue
            for (data in children(src, item.content, item.end)) {
                if (data.type != "data") continue
                val len = data.end - data.content - 8 // type indicator + locale
                if (len <= 0 || len > (if (isCover) MAX_PICTURE_BYTES else MAX_FIELD_BYTES)) continue
                val v = src.read(data.content + 8, len.toInt())
                fun text() = trimTag(String(v, Charsets.UTF_8))
                when (item.type) {
                    "©nam" -> if (title.isEmpty()) title = text()
                    "©ART" -> artists += text()
                    "aART" -> if (albumArtist.isEmpty()) albumArtist = text()
                    "©alb" -> if (album.isEmpty()) album = text()
                    "trkn" -> if (track == 0 && v.size >= 4) track = minOf(be16(v, 2), 9999)
                    "disk" -> if (disc == 0 && v.size >= 4) disc = minOf(be16(v, 2), 999)
                    "©day" -> if (year == 0) year = yearOf(text())
                    "covr" -> if (picture == null) picture = v
                }
            }
        }
        return LocalTags(title, splitValues(artists), albumArtist, album, disc, track, year, durationMs, picture)
    }

    private val MP4_ITEMS = setOf("©nam", "©ART", "aART", "©alb", "trkn", "disk", "©day", "covr")

    // ---- WAV ------------------------------------------------------------------------------------------------------
    private fun wav(src: ByteSource, wantPicture: Boolean): LocalTags {
        var pos = 12L
        var byteRate = 0L
        var dataSize = 0L
        var info = LocalTags()
        var id3 = LocalTags()
        for (guard in 0 until 1024) {
            val h = src.read(pos, 8)
            if (h.size < 8) break
            val id = String(h, 0, 4, Charsets.ISO_8859_1)
            val len = le32(h, 4)
            val body = pos + 8
            when (id) {
                "fmt " -> { val f = src.read(body, 16); if (f.size >= 12) byteRate = le32(f, 8) }
                "data" -> dataSize = minOf(len, src.size - body)
                "LIST" -> if (len in 4..MAX_FIELD_BYTES.toLong()) info = wavInfo(src.read(body, len.toInt()))
                "id3 ", "ID3 " -> if (len in 10..(MAX_TAG_BYTES.toLong())) id3 = id3v2(ArraySource(src.read(body, len.toInt())), wantPicture)
            }
            pos = body + len + (len and 1) // chunks are word-aligned
        }
        val dur = if (byteRate > 0 && dataSize > 0) (dataSize * 1000 / byteRate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
        return id3.merge(info).copy(durationMs = dur.takeIf { it > 0 } ?: id3.durationMs)
    }

    private fun wavInfo(b: ByteArray): LocalTags {
        if (b.size < 4 || String(b, 0, 4, Charsets.ISO_8859_1) != "INFO") return LocalTags()
        var i = 4
        val v = HashMap<String, String>()
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.ISO_8859_1)
            val len = le32(b, i + 4).toInt()
            if (len < 0 || i + 8 + len > b.size) break
            var end = i + 8 + len
            while (end > i + 8 && b[end - 1].toInt() == 0) end--
            v[id] = trimTag(LocalLyrics.decodeText(b.copyOfRange(i + 8, end)))
            i += 8 + len + (len and 1)
        }
        return LocalTags(
            title = v["INAM"] ?: "",
            artists = v["IART"]?.let { splitValues(listOf(it)) } ?: emptyList(),
            album = v["IPRD"] ?: "",
            trackNumber = leadingNumber(v["ITRK"] ?: v["IPRT"] ?: "", 4),
            year = yearOf(v["ICRD"] ?: ""),
        )
    }

    // ---- MP3 duration ---------------------------------------------------------------------------------------------
    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BITRATES_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
    private val BITRATES_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
    private val BITRATES_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0)
    private val BITRATES_V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val RATES = arrayOf(intArrayOf(44100, 48000, 32000), intArrayOf(22050, 24000, 16000), intArrayOf(11025, 12000, 8000))

    private class FrameHeader(val mpeg1: Boolean, val layer: Int, val bitrateK: Int, val rate: Int, val mono: Boolean, val length: Int, val samples: Int)

    private fun frameHeader(b: ByteArray, i: Int): FrameHeader? {
        if (i + 4 > b.size || u8(b, i) != 0xFF || (u8(b, i + 1) and 0xE0) != 0xE0) return null
        val ver = (u8(b, i + 1) shr 3) and 3 // 0 = 2.5, 2 = 2, 3 = 1
        val layerBits = (u8(b, i + 1) shr 1) and 3 // 1 = III, 2 = II, 3 = I
        if (ver == 1 || layerBits == 0) return null
        val brIdx = u8(b, i + 2) shr 4
        val srIdx = (u8(b, i + 2) shr 2) and 3
        if (brIdx == 0 || brIdx == 15 || srIdx == 3) return null
        val padding = (u8(b, i + 2) shr 1) and 1
        val mono = (u8(b, i + 3) shr 6) == 3
        val layer = 4 - layerBits
        val mpeg1 = ver == 3
        val bitrate = when {
            mpeg1 && layer == 1 -> BITRATES_V1_L1[brIdx]
            mpeg1 && layer == 2 -> BITRATES_V1_L2[brIdx]
            mpeg1 -> BITRATES_V1_L3[brIdx]
            layer == 1 -> BITRATES_V2_L1[brIdx]
            else -> BITRATES_V2_L23[brIdx]
        }
        val rate = RATES[when (ver) { 3 -> 0; 2 -> 1; else -> 2 }][srIdx]
        val samples = when { layer == 1 -> 384; layer == 2 || mpeg1 -> 1152; else -> 576 }
        val length = if (layer == 1) (12 * bitrate * 1000 / rate + padding) * 4 else samples / 8 * bitrate * 1000 / rate + padding
        if (length < 24) return null
        return FrameHeader(mpeg1, layer, bitrate, rate, mono, length, samples)
    }

    /** MP3 duration: Xing / Info / VBRI frame count, else the first frame's bitrate over the audio bytes. */
    internal fun mp3Duration(src: ByteSource, audioStart: Long): Int? {
        val buf = src.read(audioStart, 64 * 1024)
        var i = 0
        var h: FrameHeader? = null
        while (i + 4 <= buf.size) {
            val f = frameHeader(buf, i)
            // A real frame is followed by another one (or ends the buffer).
            if (f != null && (i + f.length + 4 > buf.size || frameHeader(buf, i + f.length) != null)) { h = f; break }
            i++
        }
        h ?: return null
        val sideInfo = if (h.mpeg1) (if (h.mono) 17 else 32) else (if (h.mono) 9 else 17)
        val x = i + 4 + sideInfo
        var frames = 0L
        if (x + 12 <= buf.size) {
            val tag = String(buf, x, 4, Charsets.ISO_8859_1)
            if ((tag == "Xing" || tag == "Info") && (u8(buf, x + 7) and 1) != 0) frames = be32(buf, x + 8)
        }
        val v = i + 4 + 32
        if (frames == 0L && v + 18 <= buf.size && String(buf, v, 4, Charsets.ISO_8859_1) == "VBRI") frames = be32(buf, v + 14)
        if (frames > 0) return (frames * h.samples * 1000 / h.rate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var audioBytes = src.size - audioStart - i
        if (src.size >= 128) {
            val t = src.read(src.size - 128, 3)
            if (t.size == 3 && String(t, Charsets.ISO_8859_1) == "TAG") audioBytes -= 128
        }
        if (audioBytes <= 0) return null
        return (audioBytes * 8 / h.bitrateK).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() // kbit/s -> ms
    }

    // ---- covers ---------------------------------------------------------------------------------------------------
    /** The extension of an image WIC / BitmapFactory decodes (".jpg", ".png", ".gif", ".bmp", ".webp"); null otherwise. */
    fun imageExt(b: ByteArray?): String? {
        if (b == null) return null
        fun starts(sig: IntArray, at: Int = 0) = b.size >= at + sig.size && sig.indices.all { u8(b, at + it) == sig[it] }
        fun startsS(sig: String, at: Int = 0) = starts(sig.map { it.code }.toIntArray(), at)
        return when {
            starts(intArrayOf(0xFF, 0xD8, 0xFF)) -> ".jpg"
            starts(intArrayOf(0x89, 'P'.code, 'N'.code, 'G'.code)) -> ".png"
            startsS("GIF8") -> ".gif"
            startsS("BM") -> ".bmp"
            startsS("RIFF") && startsS("WEBP", 8) -> ".webp"
            else -> null // not an image we decode: ignore it
        }
    }

    private fun fnv1a64(b: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (x in b) {
            h = h xor (x.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    /**
     * Written once as <hash of the bytes>.<ext>: an album's tracks share one file. The absolute path, "" when [bytes]
     * isn't an image (or can't be written).
     */
    fun saveCover(bytes: ByteArray?, coverDir: File): String {
        val ext = imageExt(bytes) ?: return ""
        val name = java.lang.Long.toUnsignedString(fnv1a64(bytes!!), 16).padStart(16, '0') + ext
        val file = File(coverDir, name)
        if (file.exists()) return file.absolutePath
        coverDir.mkdirs()
        // Another reader thread may write the same cover: private temp name, then rename.
        val tmp = File(coverDir, "$name.${System.nanoTime()}.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                if (!file.exists()) return ""
            }
        } catch (_: IOException) {
            tmp.delete()
            return ""
        }
        return file.absolutePath
    }
}
