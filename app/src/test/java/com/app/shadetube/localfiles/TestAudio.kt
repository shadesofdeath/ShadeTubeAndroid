package com.app.shadetube.localfiles

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Builds audio files and tags byte by byte for the tests (like the C++ tests' Bytes helpers). */
object TestAudio {
    class B {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun be16(v: Int) = apply { u8(v shr 8); u8(v) }
        fun be24(v: Int) = apply { u8(v shr 16); u8(v shr 8); u8(v) }
        fun be32(v: Long) = apply { u8((v shr 24).toInt()); u8((v shr 16).toInt()); u8((v shr 8).toInt()); u8(v.toInt()) }
        fun le16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun le32(v: Long) = apply { u8(v.toInt()); u8((v shr 8).toInt()); u8((v shr 16).toInt()); u8((v shr 24).toInt()) }
        fun le64(v: Long) = apply { for (i in 0 until 8) u8((v shr (8 * i)).toInt()) }
        fun syncsafe(v: Int) = apply { u8((v shr 21) and 0x7F); u8((v shr 14) and 0x7F); u8((v shr 7) and 0x7F); u8(v and 0x7F) }
        fun ascii(s: String) = apply { out.write(s.toByteArray(Charsets.ISO_8859_1)) }
        fun utf8(s: String) = apply { out.write(s.toByteArray(Charsets.UTF_8)) }
        fun bytes(b: ByteArray) = apply { out.write(b) }
        fun get(): ByteArray = out.toByteArray()
    }

    fun b() = B()

    /** A fake JPEG / PNG (the signatures are all the cover code checks). */
    fun jpeg(fill: Int = 0x11, size: Int = 300): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(size) { fill.toByte() }
    fun png(size: Int = 200): ByteArray = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(size) { 7 }

    // ---- ID3 ------------------------------------------------------------------------------------------------------
    /** A text frame body: encoding + text (UTF-16 with a BOM for 1). Several values separated by NUL. */
    fun text(enc: Int, vararg values: String): ByteArray {
        val b = b().u8(enc)
        values.forEachIndexed { i, v ->
            if (i > 0) if (enc == 1 || enc == 2) b.u8(0).u8(0) else b.u8(0)
            when (enc) {
                0 -> b.bytes(v.toByteArray(Charsets.ISO_8859_1))
                1 -> b.u8(0xFF).u8(0xFE).bytes(v.toByteArray(Charsets.UTF_16LE))
                2 -> b.bytes(v.toByteArray(Charsets.UTF_16BE))
                else -> b.utf8(v)
            }
        }
        return b.get()
    }

    fun txxx(enc: Int, desc: String, value: String): ByteArray {
        val b = b().u8(enc)
        fun s(x: String) = when (enc) {
            1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + x.toByteArray(Charsets.UTF_16LE)
            2 -> x.toByteArray(Charsets.UTF_16BE)
            0 -> x.toByteArray(Charsets.ISO_8859_1)
            else -> x.toByteArray(Charsets.UTF_8)
        }
        b.bytes(s(desc))
        if (enc == 1 || enc == 2) b.u8(0).u8(0) else b.u8(0)
        b.bytes(s(value))
        return b.get()
    }

    /** APIC body: encoding, mime, picture type, description, data. */
    fun apic(enc: Int, type: Int, data: ByteArray, mime: String = "image/jpeg", desc: String = ""): ByteArray =
        b().u8(enc).ascii(mime).u8(0).u8(type).bytes(if (enc == 1) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + desc.toByteArray(Charsets.UTF_16LE) else desc.toByteArray(Charsets.UTF_8))
            .apply { if (enc == 1 || enc == 2) u8(0).u8(0) else u8(0) }.bytes(data).get()

    /** An ID3v2.<version> tag of the frames (v2.2: 3-letter ids). */
    fun id3(version: Int, frames: List<Pair<String, ByteArray>>, padding: Int = 16): ByteArray {
        val body = b()
        for ((id, data) in frames) {
            if (version == 2) {
                body.ascii(id).be24(data.size)
            } else {
                body.ascii(id)
                if (version == 4) body.syncsafe(data.size) else body.be32(data.size.toLong())
                body.u8(0).u8(0)
            }
            body.bytes(data)
        }
        body.bytes(ByteArray(padding))
        val bytes = body.get()
        return b().ascii("ID3").u8(version).u8(0).u8(0).syncsafe(bytes.size).bytes(bytes).get()
    }

    fun id3v1(title: String, artist: String, album: String, year: String, track: Int): ByteArray {
        fun f(s: String, n: Int) = s.toByteArray(Charsets.ISO_8859_1).copyOf(n)
        return b().ascii("TAG").bytes(f(title, 30)).bytes(f(artist, 30)).bytes(f(album, 30)).bytes(f(year, 4))
            .bytes(ByteArray(28)).u8(0).u8(track).u8(255).get()
    }

    /** [frames] MPEG-1 Layer III 128 kbit/s 44.1 kHz stereo frames (417 bytes each); the first may carry a Xing header. */
    fun mp3Frames(frames: Int, xingFrames: Long = 0): ByteArray {
        val b = b()
        for (i in 0 until frames) {
            val f = ByteArray(417)
            f[0] = 0xFF.toByte(); f[1] = 0xFB.toByte(); f[2] = 0x90.toByte(); f[3] = 0x00
            if (i == 0 && xingFrames > 0) {
                val x = 4 + 32
                "Xing".forEachIndexed { k, c -> f[x + k] = c.code.toByte() }
                f[x + 7] = 1 // frames field present
                f[x + 8] = (xingFrames shr 24).toByte(); f[x + 9] = (xingFrames shr 16).toByte()
                f[x + 10] = (xingFrames shr 8).toByte(); f[x + 11] = xingFrames.toByte()
            }
            b.bytes(f)
        }
        return b.get()
    }

    // ---- FLAC -----------------------------------------------------------------------------------------------------
    fun vorbisBlock(comments: List<String>): ByteArray {
        val b = b().le32(6).ascii("vendor").le32(comments.size.toLong())
        for (c in comments) {
            val u = c.toByteArray(Charsets.UTF_8)
            b.le32(u.size.toLong()).bytes(u)
        }
        return b.get()
    }

    fun pictureBlock(type: Int, data: ByteArray, mime: String = "image/png"): ByteArray =
        b().be32(type.toLong()).be32(mime.length.toLong()).ascii(mime).be32(0).be32(0).be32(0).be32(0).be32(0)
            .be32(data.size.toLong()).bytes(data).get()

    fun flac(comments: List<String>, sampleRate: Int = 44100, totalSamples: Long = 0, picture: ByteArray? = null, audio: Int = 2000): ByteArray {
        val streaminfo = b().be16(4096).be16(4096).be24(0).be24(0)
            .u8(sampleRate shr 12).u8(sampleRate shr 4).u8(((sampleRate and 0xF) shl 4) or (1 shl 1)) // 2 channels
            .u8((15 shl 4) or ((totalSamples shr 32).toInt() and 0xF)) // 16 bits per sample
            .be32(totalSamples and 0xFFFFFFFFL).bytes(ByteArray(16)).get()
        val blocks = ArrayList<Pair<Int, ByteArray>>()
        blocks += 0 to streaminfo
        blocks += 4 to vorbisBlock(comments)
        if (picture != null) blocks += 6 to pictureBlock(3, picture)
        val b = b().ascii("fLaC")
        blocks.forEachIndexed { i, (type, data) ->
            b.u8(type or if (i == blocks.size - 1) 0x80 else 0).be24(data.size).bytes(data)
        }
        return b.bytes(ByteArray(audio) { 0x55 }).get()
    }

    // ---- MP4 ------------------------------------------------------------------------------------------------------
    fun box(type: String, content: ByteArray): ByteArray = b().be32((8 + content.size).toLong()).ascii(type).bytes(content).get()

    fun dataBox(type: Int, payload: ByteArray) = box("data", b().be32(type.toLong()).be32(0).bytes(payload).get())

    fun mp4(items: List<Pair<String, ByteArray>>, durationMs: Long = 0, freeform: Pair<String, String>? = null): ByteArray {
        val ilst = b()
        for ((type, payload) in items) ilst.bytes(box(type, payload))
        if (freeform != null) {
            val ff = b().bytes(box("mean", b().be32(0).ascii("com.apple.iTunes").get()))
                .bytes(box("name", b().be32(0).ascii(freeform.first).get()))
                .bytes(dataBox(1, freeform.second.toByteArray()))
            ilst.bytes(box("----", ff.get()))
        }
        val meta = box("meta", b().be32(0).bytes(box("hdlr", ByteArray(25))).bytes(box("ilst", ilst.get())).get())
        val mvhd = box("mvhd", b().u8(0).be24(0).be32(0).be32(0).be32(1000).be32(durationMs).bytes(ByteArray(80)).get())
        val moov = box("moov", mvhd + box("udta", meta))
        return box("ftyp", "M4A \u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)) + box("mdat", ByteArray(3000) { 0x55 }) + moov
    }

    fun mp4Text(s: String) = dataBox(1, s.toByteArray(Charsets.UTF_8))

    // ---- Ogg ------------------------------------------------------------------------------------------------------
    private fun oggPage(packet: ByteArray, granule: Long, seq: Int, flags: Int = 0): ByteArray {
        val segs = ArrayList<Int>()
        var left = packet.size
        while (left >= 255) { segs += 255; left -= 255 }
        segs += left
        val b = b().ascii("OggS").u8(0).u8(flags).le64(granule).le32(1).le32(seq.toLong()).le32(0).u8(segs.size)
        for (s in segs) b.u8(s)
        return b.bytes(packet).get()
    }

    fun oggVorbis(comments: List<String>, rate: Int, samples: Long): ByteArray {
        val id = b().ascii("\u0001vorbis").le32(0).u8(2).le32(rate.toLong()).le32(0).le32(128000).le32(0).u8(0xB8).u8(1).get()
        val tags = b().ascii("\u0003vorbis").bytes(vorbisBlock(comments)).u8(1).get()
        return oggPage(id, 0, 0, 2) + oggPage(tags, 0, 1) + oggPage(ByteArray(600) { 3 }, samples, 2, 4)
    }

    fun oggOpus(comments: List<String>, preSkip: Int, samples48k: Long): ByteArray {
        val id = b().ascii("OpusHead").u8(1).u8(2).le16(preSkip).le32(48000).le16(0).u8(0).get()
        val tags = b().ascii("OpusTags").bytes(vorbisBlock(comments)).get()
        return oggPage(id, 0, 0, 2) + oggPage(tags, 0, 1) + oggPage(ByteArray(300) { 3 }, samples48k + preSkip, 2, 4)
    }

    // ---- WAV ------------------------------------------------------------------------------------------------------
    fun wavBytes(seconds: Double, freq: Double = 440.0, info: Map<String, String> = emptyMap()): ByteArray {
        val rate = 44100
        val frames = (seconds * rate).toInt()
        val pcm = b()
        for (i in 0 until frames) {
            val v = (sin(2 * PI * freq * i / rate) * 9000).toInt()
            pcm.le16(v).le16(v)
        }
        val data = pcm.get()
        val b = b().ascii("RIFF").le32((36 + data.size).toLong()).ascii("WAVE")
            .ascii("fmt ").le32(16).le16(1).le16(2).le32(rate.toLong()).le32((rate * 4).toLong()).le16(4).le16(16)
        if (info.isNotEmpty()) {
            val list = b().ascii("INFO")
            for ((k, v) in info) {
                val u = v.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
                list.ascii(k).le32(u.size.toLong()).bytes(u)
                if (u.size % 2 == 1) list.u8(0)
            }
            val l = list.get()
            b.ascii("LIST").le32(l.size.toLong()).bytes(l)
        }
        return b.ascii("data").le32(data.size.toLong()).bytes(data).get()
    }

    fun writeWav(file: File, seconds: Double, freq: Double = 440.0) {
        file.parentFile?.mkdirs()
        file.writeBytes(wavBytes(seconds, freq))
    }
}
