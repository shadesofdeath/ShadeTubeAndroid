package com.app.shadetube.downloads

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

/**
 * iTunes-style MP4 metadata (moov/udta/meta/ilst) for downloaded `.m4a` files. The C++ app tagged its MP3 transcodes
 * with ID3v2.3 (audio/Downloader buildId3); Android has no MP3 encoder, so downloads keep the original AAC stream and
 * get the same information as MP4 atoms instead:
 *
 *   ©nam title · ©ART artist · ©alb album · aART album artist · trkn track number · ©day year · ©too "ShadeTube"
 *   (TSSE) · covr front cover (JPEG / PNG) · ©lyr plain lyrics (USLT) ·
 *   ----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN "%+.2f dB" (the C++ TXXX frame: ShadeTube's loudness normalisation and
 *   other players treat the file like the stream it came from).
 *
 * [write] rewrites the file once: everything before `moov` is copied, `moov` is rebuilt with a fresh `udta/meta`
 * (other `udta` children are kept), and the rest is copied. When `moov` precedes the media (YouTube's DASH files, and
 * "fast start" files), every absolute file offset that points behind it moves by the size change and is patched:
 * `stco` / `co64` chunk offsets, `tfhd` base data offsets (fragments with base-data-offset-present) and `tfra`
 * moof offsets. Offsets relative to their box (`trun` with default-base-is-moof, `sidx`) need nothing.
 */
object Mp4Tags {
    class Tags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArtist: String = "",
        val trackNumber: Int = 0,
        val trackTotal: Int = 0,
        val year: String = "",
        /** Front cover: JPEG or PNG bytes (the type is taken from the magic bytes; anything else is skipped). */
        val cover: ByteArray? = null,
        /** Plain lyrics, one line per line (©lyr). */
        val lyrics: String = "",
        /** ReplayGain 2 track gain in dB. */
        val replayGainDb: Float? = null,
        val encoder: String = "ShadeTube",
    )

    /** Atom names with the © (0xA9) byte, which is not one UTF-8 byte. */
    private fun atom(name: String): ByteArray = ByteArray(4) { i ->
        val c = name[i]
        if (c == '©') 0xA9.toByte() else c.code.toByte()
    }

    // ---- building -----------------------------------------------------------------------------------------------------

    private fun ByteArrayOutputStream.u32(v: Long) {
        write((v ushr 24).toInt() and 0xFF); write((v ushr 16).toInt() and 0xFF); write((v ushr 8).toInt() and 0xFF); write(v.toInt() and 0xFF)
    }

    private fun box(type: ByteArray, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        out.u32(payload.size + 8L)
        out.write(type)
        out.write(payload)
        return out.toByteArray()
    }

    private fun box(type: String, payload: ByteArray) = box(atom(type), payload)

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 4)
        out.u32(((version.toLong() and 0xFF) shl 24) or (flags.toLong() and 0xFFFFFF))
        out.write(payload)
        return box(type, out.toByteArray())
    }

    /** `data` atom: well-known type (1 = UTF-8, 0 = implicit, 13 = JPEG, 14 = PNG), locale 0, payload. */
    private fun dataAtom(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        out.u32(type.toLong())
        out.u32(0)
        out.write(payload)
        return box("data", out.toByteArray())
    }

    private fun textItem(name: String, value: String): ByteArray? =
        if (value.isEmpty()) null else box(name, dataAtom(1, value.toByteArray(Charsets.UTF_8)))

    private fun freeform(mean: String, name: String, value: String): ByteArray = box(
        "----",
        fullBox("mean", 0, 0, mean.toByteArray(Charsets.UTF_8)) +
            fullBox("name", 0, 0, name.toByteArray(Charsets.UTF_8)) +
            dataAtom(1, value.toByteArray(Charsets.UTF_8)),
    )

    /** 13 = JPEG, 14 = PNG, 0 = neither (not written). */
    fun imageType(b: ByteArray): Int = when {
        b.size > 3 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8 && (b[2].toInt() and 0xFF) == 0xFF -> 13
        b.size > 8 && (b[0].toInt() and 0xFF) == 0x89 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> 14
        else -> 0
    }

    /** "%+.2f dB" like the C++ TXXX value (always '.' as the decimal separator). */
    fun replayGainText(db: Float): String = String.format(Locale.US, "%+.2f dB", db)

    /** The `ilst` box for [tags]. */
    fun buildIlst(tags: Tags): ByteArray {
        val items = ArrayList<ByteArray>()
        textItem("©nam", tags.title)?.let(items::add)
        textItem("©ART", tags.artist)?.let(items::add)
        textItem("©alb", tags.album)?.let(items::add)
        textItem("aART", tags.albumArtist)?.let(items::add)
        if (tags.trackNumber > 0) {
            val p = ByteArray(8)
            p[2] = (tags.trackNumber shr 8).toByte(); p[3] = tags.trackNumber.toByte()
            p[4] = (tags.trackTotal shr 8).toByte(); p[5] = tags.trackTotal.toByte()
            items.add(box("trkn", dataAtom(0, p)))
        }
        textItem("©day", if (tags.year.length >= 4) tags.year.substring(0, 4) else tags.year)?.let(items::add)
        textItem("©too", tags.encoder)?.let(items::add)
        tags.cover?.let { c ->
            val type = imageType(c)
            if (type != 0) items.add(box("covr", dataAtom(type, c)))
        }
        textItem("©lyr", tags.lyrics)?.let(items::add)
        tags.replayGainDb?.let { items.add(freeform("com.apple.iTunes", "REPLAYGAIN_TRACK_GAIN", replayGainText(it))) }
        val out = ByteArrayOutputStream()
        for (i in items) out.write(i)
        return box("ilst", out.toByteArray())
    }

    /** `meta` (full box) with the iTunes handler (`mdir` / `appl`) and the `ilst`. */
    fun buildMeta(tags: Tags): ByteArray {
        val hdlr = ByteArrayOutputStream()
        hdlr.u32(0) // pre_defined
        hdlr.write(atom("mdir"))
        hdlr.write(atom("appl"))
        hdlr.u32(0)
        hdlr.u32(0)
        hdlr.write(0) // empty name
        return fullBox("meta", 0, 0, fullBox("hdlr", 0, 0, hdlr.toByteArray()) + buildIlst(tags))
    }

    // ---- parsing ------------------------------------------------------------------------------------------------------

    /** One box inside a byte array: [start] = header start, [dataStart] = payload start, [end] exclusive. */
    class Box(val type: String, val start: Int, val dataStart: Int, val end: Int)

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun be64(b: ByteArray, at: Int): Long = (be32(b, at) shl 32) or be32(b, at + 4)

    private fun put32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun put64(b: ByteArray, at: Int, v: Long) {
        put32(b, at, v ushr 32)
        put32(b, at + 4, v and 0xFFFFFFFFL)
    }

    private fun typeAt(b: ByteArray, at: Int): String = buildString(4) {
        for (i in 0 until 4) {
            val c = b[at + i].toInt() and 0xFF
            append(if (c == 0xA9) '©' else c.toChar())
        }
    }

    /** The child boxes of b[from, to). Throws IOException on a malformed box. */
    fun children(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        while (pos + 8 <= to) {
            var size = be32(b, pos)
            var header = 8
            if (size == 1L) {
                if (pos + 16 > to) throw IOException("mp4: truncated box header")
                size = be64(b, pos + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - pos).toLong()
            }
            if (size < header || pos + size > to) throw IOException("mp4: box ${typeAt(b, pos + 4)} overruns its parent")
            out.add(Box(typeAt(b, pos + 4), pos, pos + header, (pos + size).toInt()))
            pos += size.toInt()
        }
        return out
    }

    /** A top-level box of a file. */
    class FileBox(val type: String, val offset: Long, val headerSize: Int, val size: Long) {
        val end: Long get() = offset + size
    }

    fun topLevel(f: RandomAccessFile): List<FileBox> {
        val out = ArrayList<FileBox>()
        val len = f.length()
        var pos = 0L
        val h = ByteArray(16)
        while (pos + 8 <= len) {
            f.seek(pos)
            f.readFully(h, 0, 8)
            var size = be32(h, 0)
            var header = 8
            if (size == 1L) {
                if (pos + 16 > len) throw IOException("mp4: truncated box header")
                f.readFully(h, 8, 8)
                size = be64(h, 8)
                header = 16
            } else if (size == 0L) {
                size = len - pos
            }
            if (size < header || pos + size > len) throw IOException("mp4: top-level box ${typeAt(h, 4)} overruns the file")
            out.add(FileBox(typeAt(h, 4), pos, header, size))
            pos += size
        }
        return out
    }

    // ---- patching -----------------------------------------------------------------------------------------------------

    private val MOOV_CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

    /**
     * Adds [delta] to every chunk offset (stco / co64) under the container b[from, to) that is >= [threshold] (points
     * behind the moved box). Throws IOException when a 32-bit offset would overflow.
     */
    fun patchChunkOffsets(b: ByteArray, from: Int, to: Int, threshold: Long, delta: Long): Int {
        var patched = 0
        for (c in children(b, from, to)) {
            when (c.type) {
                in MOOV_CONTAINERS -> patched += patchChunkOffsets(b, c.dataStart, c.end, threshold, delta)
                "stco", "co64" -> {
                    val wide = c.type == "co64"
                    val count = be32(b, c.dataStart + 4)
                    val entrySize = if (wide) 8 else 4
                    if (c.dataStart + 8 + count * entrySize > c.end) throw IOException("mp4: ${c.type} overruns its box")
                    for (i in 0 until count.toInt()) {
                        val at = c.dataStart + 8 + i * entrySize
                        val v = if (wide) be64(b, at) else be32(b, at)
                        if (v < threshold) continue
                        val nv = v + delta
                        if (wide) put64(b, at, nv) else {
                            if (nv > 0xFFFFFFFFL || nv < 0) throw IOException("mp4: chunk offset overflows stco")
                            put32(b, at, nv)
                        }
                        ++patched
                    }
                }
            }
        }
        return patched
    }

    /** moof: tfhd base_data_offset (flag 0x000001) >= threshold moves by delta. */
    fun patchMoof(b: ByteArray, threshold: Long, delta: Long): Int {
        var patched = 0
        for (traf in children(b, 8, b.size).filter { it.type == "traf" }) {
            for (c in children(b, traf.dataStart, traf.end)) {
                if (c.type != "tfhd") continue
                val flags = be32(b, c.dataStart) and 0xFFFFFF
                if (flags and 0x1L == 0L) continue
                val at = c.dataStart + 8
                if (at + 8 > c.end) throw IOException("mp4: tfhd too short")
                val v = be64(b, at)
                if (v >= threshold) {
                    put64(b, at, v + delta)
                    ++patched
                }
            }
        }
        return patched
    }

    /** mfra: tfra moof_offset entries >= threshold move by delta. */
    fun patchMfra(b: ByteArray, threshold: Long, delta: Long): Int {
        var patched = 0
        for (c in children(b, 8, b.size)) {
            if (c.type != "tfra") continue
            val version = b[c.dataStart].toInt() and 0xFF
            val sizes = be32(b, c.dataStart + 8)
            val trafLen = ((sizes shr 4) and 3).toInt() + 1
            val trunLen = ((sizes shr 2) and 3).toInt() + 1
            val sampleLen = (sizes and 3).toInt() + 1
            val count = be32(b, c.dataStart + 12)
            val wide = if (version == 1) 8 else 4
            var at = c.dataStart + 16
            for (i in 0 until count) {
                if (at + 2 * wide + trafLen + trunLen + sampleLen > c.end) throw IOException("mp4: tfra overruns its box")
                val off = at + wide
                val v = if (wide == 8) be64(b, off) else be32(b, off)
                if (v >= threshold) {
                    val nv = v + delta
                    if (wide == 8) put64(b, off, nv) else {
                        if (nv > 0xFFFFFFFFL) throw IOException("mp4: tfra offset overflows")
                        put32(b, off, nv)
                    }
                    ++patched
                }
                at += 2 * wide + trafLen + trunLen + sampleLen
            }
        }
        return patched
    }

    /** moov with its `udta/meta` replaced by [meta] (other udta children kept; a udta is added when missing). */
    fun rebuildMoov(moov: ByteArray, meta: ByteArray): ByteArray {
        val header = if (be32(moov, 0) == 1L) 16 else 8
        val kids = children(moov, header, moov.size)
        val body = ByteArrayOutputStream(moov.size + meta.size)
        var hadUdta = false
        for (k in kids) {
            if (k.type == "udta") {
                hadUdta = true
                val udta = ByteArrayOutputStream()
                for (u in children(moov, k.dataStart, k.end)) if (u.type != "meta") udta.write(moov, u.start, u.end - u.start)
                udta.write(meta)
                body.write(box("udta", udta.toByteArray()))
            } else {
                body.write(moov, k.start, k.end - k.start)
            }
        }
        if (!hadUdta) body.write(box("udta", meta))
        val payload = body.toByteArray()
        return if (payload.size + 8L > 0xFFFFFFFFL) throw IOException("mp4: moov too large") else box("moov", payload)
    }

    /** Result of [write] (for logs / tests). */
    data class WriteResult(val moovDelta: Long, val patchedOffsets: Int, val moovBeforeMedia: Boolean)

    /**
     * Rewrites [file] with [tags] (via `<file>.tagtmp`, renamed over it). Throws IOException for a file that is not a
     * parsable MP4 (no moov, malformed boxes); the original file is then left untouched.
     */
    fun write(file: File, tags: Tags): WriteResult {
        val tmp = File(file.path + ".tagtmp")
        val result: WriteResult
        RandomAccessFile(file, "r").use { f ->
            val boxes = topLevel(f)
            val moovBox = boxes.singleOrNull { it.type == "moov" } ?: throw IOException("mp4: no (single) moov box")
            if (boxes.none { it.type == "ftyp" }) throw IOException("mp4: no ftyp box")
            if (moovBox.size > 64L * 1024 * 1024) throw IOException("mp4: moov too large")
            val oldMoov = ByteArray(moovBox.size.toInt())
            f.seek(moovBox.offset)
            f.readFully(oldMoov)
            val newMoov = rebuildMoov(oldMoov, buildMeta(tags))
            val delta = newMoov.size.toLong() - oldMoov.size
            val mediaAfter = boxes.any { (it.type == "mdat" || it.type == "moof") && it.offset > moovBox.offset }
            var patched = 0
            // Everything that pointed behind the old moov moves by delta.
            val threshold = moovBox.end
            if (delta != 0L && mediaAfter) patched += patchChunkOffsets(newMoov, 8, newMoov.size, threshold, delta)
            tmp.delete()
            RandomAccessFile(tmp, "rw").use { out ->
                out.setLength(0)
                copyRange(f, 0, moovBox.offset, out)
                out.write(newMoov)
                for (b in boxes) {
                    if (b.offset <= moovBox.offset) continue
                    if (delta != 0L && (b.type == "moof" || b.type == "mfra") && b.size <= 16L * 1024 * 1024) {
                        val bytes = ByteArray(b.size.toInt())
                        f.seek(b.offset)
                        f.readFully(bytes)
                        // A moof / mfra with a 64-bit size header is not something we produce or see: copy it verbatim.
                        if (be32(bytes, 0) != 1L) {
                            patched += if (b.type == "moof") patchMoof(bytes, threshold, delta) else patchMfra(bytes, threshold, delta)
                        }
                        out.write(bytes)
                    } else {
                        copyRange(f, b.offset, b.end, out)
                    }
                }
                out.fd.sync()
            }
            result = WriteResult(delta, patched, mediaAfter)
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("mp4: could not replace ${file.name}")
            }
        }
        return result
    }

    private fun copyRange(f: RandomAccessFile, from: Long, to: Long, out: RandomAccessFile) {
        val buf = ByteArray(256 * 1024)
        var pos = from
        f.seek(from)
        while (pos < to) {
            val n = f.read(buf, 0, minOf(buf.size.toLong(), to - pos).toInt())
            if (n < 0) throw IOException("mp4: unexpected end of file")
            out.write(buf, 0, n)
            pos += n
        }
    }

    // ---- reading (verification, tests) --------------------------------------------------------------------------------

    /** The ilst items of a file: atom name ("©nam", "trkn", "covr", "----:com.apple.iTunes:NAME") -> data payload. */
    fun readItems(file: File): Map<String, Pair<Int, ByteArray>> {
        RandomAccessFile(file, "r").use { f ->
            val moovBox = topLevel(f).firstOrNull { it.type == "moov" } ?: return emptyMap()
            val moov = ByteArray(moovBox.size.toInt())
            f.seek(moovBox.offset)
            f.readFully(moov)
            return readItems(moov)
        }
    }

    fun readItems(moov: ByteArray): Map<String, Pair<Int, ByteArray>> {
        val out = LinkedHashMap<String, Pair<Int, ByteArray>>()
        val header = if (be32(moov, 0) == 1L) 16 else 8
        val udta = children(moov, header, moov.size).firstOrNull { it.type == "udta" } ?: return out
        val meta = children(moov, udta.dataStart, udta.end).firstOrNull { it.type == "meta" } ?: return out
        val ilst = children(moov, meta.dataStart + 4, meta.end).firstOrNull { it.type == "ilst" } ?: return out
        for (item in children(moov, ilst.dataStart, ilst.end)) {
            var name = item.type
            var data: Pair<Int, ByteArray>? = null
            for (c in children(moov, item.dataStart, item.end)) {
                when (c.type) {
                    "mean" -> name = "----:" + String(moov, c.dataStart + 4, c.end - c.dataStart - 4, Charsets.UTF_8)
                    "name" -> name += ":" + String(moov, c.dataStart + 4, c.end - c.dataStart - 4, Charsets.UTF_8)
                    "data" -> data = (be32(moov, c.dataStart) and 0xFFFFFF).toInt() to moov.copyOfRange(c.dataStart + 8, c.end)
                }
            }
            if (data != null) out[name] = data
        }
        return out
    }

    /** The text value of an item, or null. */
    fun text(items: Map<String, Pair<Int, ByteArray>>, name: String): String? =
        items[name]?.takeIf { it.first == 1 }?.second?.toString(Charsets.UTF_8)

    /** Every chunk offset (stco / co64) of every track in a moov box, in order (tests). */
    fun chunkOffsets(moov: ByteArray): List<Long> {
        val out = ArrayList<Long>()
        fun walk(from: Int, to: Int) {
            for (c in children(moov, from, to)) {
                when (c.type) {
                    in MOOV_CONTAINERS -> walk(c.dataStart, c.end)
                    "stco", "co64" -> {
                        val wide = c.type == "co64"
                        val count = be32(moov, c.dataStart + 4).toInt()
                        for (i in 0 until count) {
                            val at = c.dataStart + 8 + i * (if (wide) 8 else 4)
                            out.add(if (wide) be64(moov, at) else be32(moov, at))
                        }
                    }
                }
            }
        }
        val header = if (be32(moov, 0) == 1L) 16 else 8
        walk(header, moov.size)
        return out
    }

    /** Reads a whole top-level box of [file] (tests / tools). */
    fun readBox(file: File, type: String): ByteArray? = RandomAccessFile(file, "r").use { f ->
        val b = topLevel(f).firstOrNull { it.type == type } ?: return null
        ByteArray(b.size.toInt()).also { f.seek(b.offset); f.readFully(it) }
    }
}
