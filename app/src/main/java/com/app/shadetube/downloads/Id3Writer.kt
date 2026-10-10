package com.app.shadetube.downloads

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * ID3v2.3 tag of MP3 downloads (port of the C++ Downloader's tag builder): TIT2 / TPE1 / TALB / TPE2 / TRCK / TYER /
 * TSSE "ShadeTube", an APIC front cover, a TXXX "REPLAYGAIN_TRACK_GAIN", USLT plain lyrics and SYLT synced lyrics (ms).
 *
 * ID3v2.3 knows only ISO-8859-1 (0) and UTF-16 with a BOM (1); UTF-8 (3) is v2.4 and strict readers (the Windows
 * property system, some car stereos) ignore such frames, so text is UTF-16LE with a BOM. Frame sizes are plain 32-bit
 * big endian (v2.3), the tag size is syncsafe; no unsynchronisation, no padding.
 */
object Id3Writer {
    data class Tags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArtist: String = "",
        /** 0 = none. */
        val trackNumber: Int = 0,
        /** "2024" (only the first 4 characters are written); empty = none. */
        val year: String = "",
        /** JPEG or PNG front cover; null = none. */
        val cover: ByteArray? = null,
        /** USLT text; empty = none. */
        val lyricsText: String = "",
        /** SYLT lines (ms, text); empty = none. Timed to the song, which is what a trimmed file holds: no mapping. */
        val syncedLyrics: List<Pair<Long, String>> = emptyList(),
        /** TXXX REPLAYGAIN_TRACK_GAIN in dB; null = none. */
        val replayGainDb: Float? = null,
    )

    /** The whole tag ("ID3" header + frames); empty when there is nothing to write. */
    fun build(t: Tags): ByteArray {
        val frames = ByteArrayOutputStream()
        textFrame(frames, "TIT2", t.title)
        textFrame(frames, "TPE1", t.artist)
        textFrame(frames, "TALB", t.album)
        textFrame(frames, "TPE2", t.albumArtist)
        if (t.trackNumber > 0) textFrame(frames, "TRCK", t.trackNumber.toString())
        textFrame(frames, "TYER", if (t.year.length >= 4) t.year.substring(0, 4) else t.year)
        textFrame(frames, "TSSE", "ShadeTube")
        t.cover?.let { apicFrame(frames, it) }
        t.replayGainDb?.let { replayGainFrame(frames, it) }
        frames.write(lyricsFrames(t.lyricsText, t.syncedLyrics))
        val body = frames.toByteArray()
        if (body.isEmpty()) return body
        val out = ByteArrayOutputStream(10 + body.size)
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        putSyncsafe(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    /**
     * The lyrics frames for [text] (USLT) and [synced] (SYLT, ms): UTF-16 with a BOM, language "eng", empty
     * descriptors (C++ id3LyricsFrames).
     */
    fun lyricsFrames(text: String, synced: List<Pair<Long, String>>): ByteArray {
        val frames = ByteArrayOutputStream()
        if (text.isNotEmpty()) {
            // USLT: encoding, language, descriptor (empty), text.
            val d = ByteArrayOutputStream()
            d.write(byteArrayOf(1, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte()))
            utf16Bom(d, "", true)
            utf16Bom(d, text, false)
            rawFrame(frames, "USLT", d.toByteArray())
        }
        if (synced.isNotEmpty()) {
            // SYLT: encoding, language, time stamp format (2 = ms), content type (1 = lyrics), descriptor, then each line
            // as a terminated string + a 32-bit time stamp.
            val d = ByteArrayOutputStream()
            d.write(byteArrayOf(1, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 2, 1))
            utf16Bom(d, "", true)
            for ((ms, line) in synced) {
                utf16Bom(d, line, true)
                putBE32(d, ms.coerceIn(0L, 0xFFFF_FFFFL).toInt())
            }
            rawFrame(frames, "SYLT", d.toByteArray())
        }
        return frames.toByteArray()
    }

    /** TXXX value: "%+.2f dB" with '.' as the decimal separator. */
    fun replayGainText(db: Float): String = String.format(Locale.US, "%+.2f dB", db)

    // ---- frames -------------------------------------------------------------------------------------------------------

    private fun textFrame(frames: ByteArrayOutputStream, id: String, text: String) {
        if (text.isEmpty()) return
        val d = ByteArrayOutputStream()
        d.write(1) // UTF-16 with BOM
        utf16Bom(d, text, false)
        rawFrame(frames, id, d.toByteArray())
    }

    private fun apicFrame(frames: ByteArrayOutputStream, image: ByteArray) {
        if (image.isEmpty()) return
        val mime = if (Mp4Tags.imageType(image) == 14) "image/png" else "image/jpeg"
        val d = ByteArrayOutputStream(image.size + 32)
        d.write(0) // description encoding: ISO-8859-1 (the description is empty)
        d.write(mime.toByteArray(Charsets.ISO_8859_1))
        d.write(0) // mime terminator
        d.write(3) // picture type: front cover
        d.write(0) // empty description terminator
        d.write(image)
        rawFrame(frames, "APIC", d.toByteArray())
    }

    /** TXXX (user text): ISO-8859-1 description + value; ReplayGain readers match the description case-insensitively. */
    private fun replayGainFrame(frames: ByteArrayOutputStream, db: Float) {
        val d = ByteArrayOutputStream()
        d.write(0)
        d.write("REPLAYGAIN_TRACK_GAIN".toByteArray(Charsets.ISO_8859_1))
        d.write(0)
        d.write(replayGainText(db).toByteArray(Charsets.ISO_8859_1))
        rawFrame(frames, "TXXX", d.toByteArray())
    }

    private fun utf16Bom(d: ByteArrayOutputStream, s: String, terminate: Boolean) {
        d.write(0xFF)
        d.write(0xFE)
        for (c in s) {
            d.write(c.code and 0xFF)
            d.write((c.code shr 8) and 0xFF)
        }
        if (terminate) {
            d.write(0)
            d.write(0)
        }
    }

    private fun rawFrame(frames: ByteArrayOutputStream, id: String, data: ByteArray) {
        frames.write(id.toByteArray(Charsets.ISO_8859_1))
        putBE32(frames, data.size)
        frames.write(0)
        frames.write(0) // frame flags
        frames.write(data)
    }

    private fun putBE32(o: ByteArrayOutputStream, n: Int) {
        o.write((n ushr 24) and 0xFF)
        o.write((n ushr 16) and 0xFF)
        o.write((n ushr 8) and 0xFF)
        o.write(n and 0xFF)
    }

    private fun putSyncsafe(o: ByteArrayOutputStream, n: Int) {
        require(n < (1 shl 28)) { "ID3 tag too large: $n bytes" }
        o.write((n ushr 21) and 0x7F)
        o.write((n ushr 14) and 0x7F)
        o.write((n ushr 7) and 0x7F)
        o.write(n and 0x7F)
    }
}
