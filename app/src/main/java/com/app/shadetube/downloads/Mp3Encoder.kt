package com.app.shadetube.downloads

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.Closeable

/**
 * CBR MP3 encoder on jump3r (a pure-Java port of LAME 3.98; the C++ app used Media Foundation's MP3 encoder, which
 * Android lacks). Only the low-level `de.sciss.jump3r.mp3` / `mpg` classes are used — the modules are wired by hand
 * here exactly like `de.sciss.jump3r.lowlevel.LameEncoder` does, because that class (and `Main`) reference
 * `javax.sound.sampled` / `java.beans`, which Android doesn't have.
 *
 * LAME's defaults otherwise: quality preset 3 (`-q 3`, what `lame -b 320` uses), joint stereo, no ID3 tag of its own,
 * and a LAME/Xing "Info" frame at the start of the stream (frame count, encoder delay / padding for gapless players,
 * so duration and seeking are exact): the encoder emits a placeholder for it, [lameTagFrame] gives the final bytes to
 * write over it once everything is encoded.
 *
 * Input: interleaved float PCM in [-1, 1] at [sampleRate] with [channels] (1 or 2). Not thread-safe.
 */
class Mp3Encoder(val sampleRate: Int, val channels: Int, val kbps: Int) : Closeable {
    private val lame = Lame()
    private val vbrTag = VBRTag()
    private val gfp: LameGlobalFlags
    private var left = IntArray(0)
    private var right = IntArray(0)
    private var buf = ByteArray(0)
    private var closed = false

    /** The MP3 stream's sample rate (= [sampleRate] for 32 / 44.1 / 48 kHz input; LAME resamples anything else). */
    val outSampleRate: Int

    init {
        require(channels == 1 || channels == 2) { "channels must be 1 or 2, not $channels" }
        require(sampleRate in 8000..192000) { "unsupported sample rate $sampleRate" }
        val ga = GainAnalysis()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        lame.setModules(ga, bs, presets, qupvt, qu, vbrTag, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbrTag)
        id3.setModules(bs, ver)
        presets.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbrTag.setModules(lame, bs, ver)
        mpg.setModules(intf, common)
        intf.setModules(vbrTag, common)

        gfp = lame.lame_init()
        gfp.num_channels = channels
        gfp.in_samplerate = sampleRate
        gfp.out_samplerate = mp3SampleRate(sampleRate)
        gfp.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        gfp.VBR = VbrMode.vbr_off
        gfp.brate = kbps
        // gfp.quality stays -1: LAME's default (3).
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false // ShadeTube writes its own ID3v2.3 tag (Id3Writer)
        gfp.findReplayGain = false // the tag gets YouTube's loudness instead; the analysis would cost ~10 % more time
        gfp.bWriteVbrTag = true // LAME / Info frame
        val r = lame.lame_init_params(gfp)
        if (r < 0) throw IllegalArgumentException("LAME rejected $sampleRate Hz / $channels ch / $kbps kbps ($r)")
        outSampleRate = gfp.out_samplerate
    }

    /**
     * Encodes [frames] frames of interleaved [pcm] (from [offset], in samples) and returns the MP3 bytes produced so far
     * (valid in the returned array's first [Chunk.size] bytes until the next call).
     */
    fun encode(pcm: FloatArray, offset: Int, frames: Int): Chunk {
        check(!closed) { "encoder closed" }
        if (frames <= 0) return Chunk(buf, 0)
        if (left.size < frames) {
            left = IntArray(frames)
            right = IntArray(frames)
        }
        var i = offset
        for (k in 0 until frames) {
            left[k] = toInt(pcm[i++])
            if (channels == 2) right[k] = toInt(pcm[i++])
        }
        ensureBuf(frames + frames / 4 + 7200) // LAME's worst case: 1.25 * samples + 7200
        val n = lame.lame_encode_buffer_int(gfp, left, if (channels == 2) right else left, frames, buf, 0, buf.size)
        if (n < 0) throw IllegalStateException("LAME encode failed ($n)")
        return Chunk(buf, n)
    }

    /** Flushes the last frames (padding the final one). Call once, after the last [encode]. */
    fun flush(): Chunk {
        check(!closed) { "encoder closed" }
        ensureBuf(7200 * 2)
        val n = lame.lame_encode_flush(gfp, buf, 0, buf.size)
        if (n < 0) throw IllegalStateException("LAME flush failed ($n)")
        return Chunk(buf, n)
    }

    /**
     * The LAME / Info frame describing the whole stream (frame count, bytes, TOC, delay / padding, CRCs), to write over
     * the placeholder frame at the start of the MP3 data. Call after [flush]. Empty when LAME wrote none.
     */
    fun lameTagFrame(): ByteArray {
        val out = ByteArray(2880) // the largest MPEG-1 layer III frame (320 kbps @ 32 kHz + padding is 1441)
        val n = vbrTag.getLameTagFrame(gfp, out)
        return if (n in 1..out.size) out.copyOf(n) else ByteArray(0)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { lame.lame_close(gfp) }
    }

    private fun ensureBuf(n: Int) {
        if (buf.size < n) buf = ByteArray(n)
    }

    /** A view of the encoder's output buffer. */
    class Chunk(val bytes: ByteArray, val size: Int)

    companion object {
        /** The bitrates the app offers (Settings › Downloads); others are rounded to the nearest of these. */
        val BITRATES = intArrayOf(320, 256, 192)

        /** [kbps] clamped to an offered bitrate (0 stays 0 = passthrough). */
        fun supportedKbps(kbps: Int): Int = if (kbps <= 0) 0 else BITRATES.minBy { kotlin.math.abs(it - kbps) }

        /** MPEG-1 layer III sample rates (the only ones that allow 192-320 kbps); others are resampled to 44.1 kHz. */
        fun mp3SampleRate(rate: Int): Int = if (rate == 32000 || rate == 44100 || rate == 48000) rate else 44100

        /** Float [-1, 1] to LAME's 32-bit integer scale (it divides by 65536 internally), clamped. */
        private fun toInt(f: Float): Int {
            val v = f * 2147483647f
            return when {
                v >= 2147483647f -> Int.MAX_VALUE
                v <= -2147483648f -> Int.MIN_VALUE
                v.isNaN() -> 0
                else -> v.toInt()
            }
        }
    }
}
