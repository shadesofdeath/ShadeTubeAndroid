package com.app.shadetube.downloads

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The MP4 tag writer on minimal synthetic files built here: ilst atoms, moov rebuilt with udta/meta, chunk offsets
 * (stco / co64) patched when moov precedes mdat, fragment offsets (tfhd base offset, tfra) patched, files re-parse and
 * every chunk still points at its bytes.
 */
class Mp4TagsTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("mp4").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---- a tiny MP4 builder -------------------------------------------------------------------------------------------

    private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be64(v: Long) = be32(v ushr 32) + be32(v and 0xFFFFFFFFL)
    private fun cat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val p = cat(*payload)
        return cat(be32(p.size + 8L), type.toByteArray(Charsets.ISO_8859_1), p)
    }
    private fun full(type: String, version: Int, flags: Int, vararg payload: ByteArray) =
        box(type, be32((version.toLong() shl 24) or flags.toLong()), *payload)

    private val ftyp = box("ftyp", "M4A ".toByteArray(), be32(0), "M4A isom".toByteArray())

    private fun chunkData(i: Int) = ByteArray(100) { ('A' + i).code.toByte() }

    private fun moov(offsets: List<Long>, wide: Boolean, extraUdta: ByteArray? = null): ByteArray {
        val table = if (wide) full("co64", 0, 0, be32(offsets.size.toLong()), *offsets.map { be64(it) }.toTypedArray())
        else full("stco", 0, 0, be32(offsets.size.toLong()), *offsets.map { be32(it) }.toTypedArray())
        val stbl = box(
            "stbl",
            full("stsd", 0, 0, be32(0)),
            full("stts", 0, 0, be32(0)),
            full("stsc", 0, 0, be32(0)),
            full("stsz", 0, 0, be32(100), be32(offsets.size.toLong())),
            table,
        )
        val trak = box(
            "trak",
            full("tkhd", 0, 7, ByteArray(80)),
            box("mdia", full("mdhd", 0, 0, ByteArray(20)), full("hdlr", 0, 0, be32(0), "soun".toByteArray(), ByteArray(13)), box("minf", full("smhd", 0, 0, ByteArray(4)), stbl)),
        )
        val parts = mutableListOf(full("mvhd", 0, 0, ByteArray(96)), trak)
        if (extraUdta != null) parts.add(extraUdta)
        return box("moov", *parts.toTypedArray())
    }

    /** ftyp, moov, mdat (moov first: "fast start" / YouTube DASH layout). Returns the file and the chunk offsets. */
    private fun fastStart(name: String, wide: Boolean, extraUdta: ByteArray? = null, largeMdat: Boolean = false): File {
        val chunks = (0 until 3).map { chunkData(it) }
        val moovSize = moov(listOf(0, 0, 0), wide, extraUdta).size
        val mdatHeader = if (largeMdat) 16 else 8
        val dataStart = ftyp.size + moovSize + mdatHeader.toLong()
        val offsets = (0 until 3).map { dataStart + it * 100L }
        val payload = cat(*chunks.toTypedArray())
        val mdat = if (largeMdat) cat(be32(1), "mdat".toByteArray(), be64(payload.size + 16L), payload) else box("mdat", payload)
        val f = File(dir, name)
        f.writeBytes(cat(ftyp, moov(offsets, wide, extraUdta), mdat))
        return f
    }

    private fun assertChunksIntact(f: File) {
        val moov = Mp4Tags.readBox(f, "moov")!!
        val offsets = Mp4Tags.chunkOffsets(moov)
        assertEquals(3, offsets.size)
        RandomAccessFile(f, "r").use { raf ->
            for ((i, off) in offsets.withIndex()) {
                val b = ByteArray(100)
                raf.seek(off)
                raf.readFully(b)
                assertArrayEquals("chunk $i at $off", chunkData(i), b)
            }
        }
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())

    private fun tags() = Mp4Tags.Tags(
        title = "Deneme Şarkısı", artist = "Sanatçı A, Sanatçı B", album = "Kesim Albümü", albumArtist = "Sanatçı A",
        trackNumber = 7, cover = jpeg, lyrics = "satır bir\nsatır iki", replayGainDb = -3.25f,
    )

    private fun assertTags(f: File) {
        val items = Mp4Tags.readItems(f)
        assertEquals("Deneme Şarkısı", Mp4Tags.text(items, "©nam"))
        assertEquals("Sanatçı A, Sanatçı B", Mp4Tags.text(items, "©ART"))
        assertEquals("Kesim Albümü", Mp4Tags.text(items, "©alb"))
        assertEquals("Sanatçı A", Mp4Tags.text(items, "aART"))
        assertEquals("ShadeTube", Mp4Tags.text(items, "©too"))
        assertEquals("satır bir\nsatır iki", Mp4Tags.text(items, "©lyr"))
        assertEquals("-3.25 dB", Mp4Tags.text(items, "----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN"))
        val trkn = items["trkn"]!!
        assertEquals(0, trkn.first)
        assertArrayEquals(byteArrayOf(0, 0, 0, 7, 0, 0, 0, 0), trkn.second)
        val covr = items["covr"]!!
        assertEquals(13, covr.first)
        assertArrayEquals(jpeg, covr.second)
    }

    // ---- tests --------------------------------------------------------------------------------------------------------

    @Test
    fun moovBeforeMdatPatchesStco() {
        val f = fastStart("a.m4a", wide = false)
        assertChunksIntact(f)
        val before = f.length()
        val r = Mp4Tags.write(f, tags())
        assertTrue(r.moovBeforeMedia)
        assertTrue(r.moovDelta > 0)
        assertEquals(3, r.patchedOffsets)
        assertEquals(before + r.moovDelta, f.length())
        assertChunksIntact(f)
        assertTags(f)
        // The top level still parses: ftyp, moov, mdat.
        RandomAccessFile(f, "r").use { assertEquals(listOf("ftyp", "moov", "mdat"), Mp4Tags.topLevel(it).map { b -> b.type }) }
        assertFalse(File(f.path + ".tagtmp").exists())
    }

    @Test
    fun co64AndLargeMdat() {
        val f = fastStart("b.m4a", wide = true, largeMdat = true)
        assertChunksIntact(f)
        val r = Mp4Tags.write(f, tags())
        assertEquals(3, r.patchedOffsets)
        assertChunksIntact(f)
        assertTags(f)
    }

    @Test
    fun retaggingReplacesMetaAndKeepsOtherUdtaChildren() {
        val oldMeta = full("meta", 0, 0, full("hdlr", 0, 0, be32(0), "mdir".toByteArray(), ByteArray(13)), box("ilst", box("©nam", box("data", be32(1), be32(0), "Old".toByteArray()))))
        val loc = box("©xyz", "+41.0+029.0/".toByteArray())
        val f = fastStart("c.m4a", wide = false, extraUdta = box("udta", loc, oldMeta))
        assertEquals("Old", Mp4Tags.text(Mp4Tags.readItems(f), "©nam"))
        Mp4Tags.write(f, tags())
        assertChunksIntact(f)
        assertTags(f)
        val sizeOnce = f.length()
        // Again: same size (the meta is replaced, not stacked), still intact.
        Mp4Tags.write(f, tags())
        assertEquals(sizeOnce, f.length())
        assertChunksIntact(f)
        assertTags(f)
        // The other udta child survived.
        val moov = Mp4Tags.readBox(f, "moov")!!
        val udta = Mp4Tags.children(moov, 8, moov.size).single { it.type == "udta" }
        val kids = Mp4Tags.children(moov, udta.dataStart, udta.end).map { it.type }
        assertEquals(listOf("©xyz", "meta"), kids)
    }

    @Test
    fun moovAfterMdatNeedsNoPatch() {
        // ftyp, mdat, moov (what MediaMuxer writes): chunk offsets point before moov and stay.
        val chunks = cat(*(0 until 3).map { chunkData(it) }.toTypedArray())
        val mdat = box("mdat", chunks)
        val offsets = (0 until 3).map { ftyp.size + 8L + it * 100 }
        val f = File(dir, "d.m4a")
        f.writeBytes(cat(ftyp, mdat, moov(offsets, wide = false)))
        val r = Mp4Tags.write(f, tags())
        assertFalse(r.moovBeforeMedia)
        assertEquals(0, r.patchedOffsets)
        assertEquals(offsets, Mp4Tags.chunkOffsets(Mp4Tags.readBox(f, "moov")!!))
        assertChunksIntact(f)
        assertTags(f)
    }

    @Test
    fun fragmentedFilePatchesBaseOffsetsAndTfra() {
        // ftyp, moov (with mvex, empty stco), moof (tfhd with an absolute base_data_offset), mdat, mfra (tfra -> moof).
        val moovBox = box("moov", full("mvhd", 0, 0, ByteArray(96)), box("mvex", full("trex", 0, 0, ByteArray(20))), box("trak", box("mdia", box("minf", box("stbl", full("stco", 0, 0, be32(0)))))))
        val payload = ByteArray(64) { (it * 3).toByte() }
        fun moof(base: Long) = box(
            "moof",
            full("mfhd", 0, 0, be32(1)),
            box("traf", full("tfhd", 0, 0x000001, be32(1), be64(base)), full("trun", 0, 0x000201, be32(1), be32(64))),
        )
        val moofSize = moof(0).size
        val moofOffset = (ftyp.size + moovBox.size).toLong()
        val dataOffset = moofOffset + moofSize + 8
        // tfra v1: track_ID, sizes (all 1 byte), 1 entry: time(8) moof_offset(8) traf/trun/sample numbers (1 byte each).
        val tfra = full("tfra", 1, 0, be32(1), be32(0), be32(1), be64(0), be64(moofOffset), byteArrayOf(1, 1, 1))
        val mfra = box("mfra", tfra, full("mfro", 0, 0, be32(0)))
        val f = File(dir, "e.m4a")
        f.writeBytes(cat(ftyp, moovBox, moof(dataOffset), box("mdat", payload), mfra))

        val r = Mp4Tags.write(f, tags())
        assertTrue(r.moovBeforeMedia)
        assertEquals(2, r.patchedOffsets) // tfhd + tfra
        val boxes = RandomAccessFile(f, "r").use { Mp4Tags.topLevel(it) }
        assertEquals(listOf("ftyp", "moov", "moof", "mdat", "mfra"), boxes.map { it.type })
        val newMoof = boxes.single { it.type == "moof" }
        val moofBytes = Mp4Tags.readBox(f, "moof")!!
        // tfhd base offset -> the mdat payload.
        val traf = Mp4Tags.children(moofBytes, 8, moofBytes.size).single { it.type == "traf" }
        val tfhd = Mp4Tags.children(moofBytes, traf.dataStart, traf.end).single { it.type == "tfhd" }
        val base = java.nio.ByteBuffer.wrap(moofBytes, tfhd.dataStart + 8, 8).long
        val back = ByteArray(64)
        RandomAccessFile(f, "r").use { it.seek(base); it.readFully(back) }
        assertArrayEquals(payload, back)
        // tfra -> the moof box.
        val mfraBytes = Mp4Tags.readBox(f, "mfra")!!
        val tfraBox = Mp4Tags.children(mfraBytes, 8, mfraBytes.size).single { it.type == "tfra" }
        val moofOff = java.nio.ByteBuffer.wrap(mfraBytes, tfraBox.dataStart + 16 + 8, 8).long
        assertEquals(newMoof.offset, moofOff)
        assertTags(f)
    }

    @Test
    fun notAnMp4IsLeftAlone() {
        val f = File(dir, "x.webm")
        val bytes = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2, 3, 4, 5, 6, 7, 8)
        f.writeBytes(bytes)
        try {
            Mp4Tags.write(f, tags())
            fail("expected IOException")
        } catch (e: IOException) {
            // ok
        }
        assertArrayEquals(bytes, f.readBytes())
        assertFalse(File(f.path + ".tagtmp").exists())
        // No moov at all.
        val g = File(dir, "y.m4a")
        g.writeBytes(cat(ftyp, box("mdat", ByteArray(10))))
        try {
            Mp4Tags.write(g, tags())
            fail("expected IOException")
        } catch (e: IOException) {
            // ok
        }
    }

    @Test
    fun ilstDetails() {
        assertEquals(13, Mp4Tags.imageType(jpeg))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)
        assertEquals(14, Mp4Tags.imageType(png))
        assertEquals(0, Mp4Tags.imageType("RIFF....WEBP".toByteArray()))
        assertEquals("+1.50 dB", Mp4Tags.replayGainText(1.5f))
        assertEquals("-12.00 dB", Mp4Tags.replayGainText(-12f))
        // Empty values and an unknown image type are left out; the © atoms are the 0xA9 byte.
        val ilst = Mp4Tags.buildIlst(Mp4Tags.Tags(title = "x", cover = "nope".toByteArray()))
        val kids = Mp4Tags.children(ilst, 8, ilst.size).map { it.type }
        assertEquals(listOf("©nam", "©too"), kids)
        assertEquals(0xA9, ilst[12].toInt() and 0xFF)
        // The download's tags: ReplayGain = -4 - YouTube loudness (C++ TXXX), album artist = the first artist.
        val t = Track(id = "t", name = "N", artists = listOf(ArtistRef("", "A"), ArtistRef("", "B")), album = AlbumRef(name = "Al"), trackNumber = 3)
        val tags = YoutubeTrackDownloader.tagsFor(t, null, "", -0.75)
        assertEquals(-3.25f, tags.replayGainDb!!, 0.0001f)
        assertEquals("A, B", tags.artist); assertEquals("A", tags.albumArtist); assertEquals(3, tags.trackNumber)
        assertNull(YoutubeTrackDownloader.tagsFor(t, null, "", null).replayGainDb)
        assertNotNull(Mp4Tags.buildMeta(tags))
    }
}
