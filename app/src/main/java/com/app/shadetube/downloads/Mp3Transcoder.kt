package com.app.shadetube.downloads

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlin.math.abs

/** One block of decoded audio: interleaved float PCM in [-1, 1]. */
class PcmBlock(
    /** `frames * channels` samples from index 0 (the array may be larger, and reused by the source's next read). */
    val samples: FloatArray,
    val frames: Int,
    val channels: Int,
    val sampleRate: Int,
    /** Presentation time of the first frame, µs; -1 = unknown. */
    val timeUs: Long = -1,
)

/** Decoded audio, block by block ([MediaCodecPcmSource] on the device, fakes in tests). Blocking. */
interface PcmSource : Closeable {
    /** The source's length from its container, µs; 0 = unknown. */
    val durationUs: Long

    /** The next block; null at the end of the stream. Throws [TranscodeException] (Decode) on a decoder failure. */
    fun read(): PcmBlock?
}

/** The transcode failed for a reason other than file I/O (that is an IOException). */
class TranscodeException(message: String, val kind: Kind, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { Decode, Encode }
}

/**
 * The MP3 half of a download (port of the C++ Downloader's encodeMp3 + finalizeMp3WithTag): [PcmSource] → [PcmSplicer]
 * (SponsorBlock cuts, sample-accurate, 8 ms raised-cosine fades, continuous timeline) → [Mp3Encoder] (CBR) → [out],
 * which gets the ID3v2.3 tag first ([Id3Writer]; built before the transcode, so no copy of the audio afterwards), then
 * the MP3 frames; the LAME / Info frame is written over its placeholder at the end.
 *
 * Streams block by block: memory stays bounded whatever the length (a 3-hour mix is fine). Output format = the first
 * decoded block's sample rate and channel count (more than 2 channels are downmixed to stereo); a later change of
 * either (rare: an HE-AAC stream switching, a broken file) is converted to it (channel remix + linear resampling) so
 * the MP3 stays one consistent stream.
 *
 * Frame positions come from a running counter (sample-exact); the decoder timestamp only re-syncs it when they disagree
 * by more than [RESYNC_TOLERANCE_MS] (a real gap in the stream). Cuts that would leave less than
 * [PcmSplicer.MIN_KEPT_MS] are ignored (bogus data).
 *
 * [progress] gets 0..1 along the SOURCE timeline (cut parts included); [cancelled] is polled between blocks and throws
 * [CancellationException]. Blocking and CPU-bound: call from a worker.
 */
object Mp3Transcoder {
    const val RESYNC_TOLERANCE_MS = 50L

    data class Stats(
        /** Cut ranges that removed at least one frame. */
        val segmentsCut: Int,
        /** Audio removed, ms. */
        val cutMs: Long,
        /** Decoded source length, ms. */
        val sourceMs: Long,
        /** Encoded length, ms (= sourceMs - cutMs). */
        val outputMs: Long,
        /** Size of [out] (tag included). */
        val bytes: Long,
        val sampleRate: Int,
        val channels: Int,
    )

    fun transcode(
        source: PcmSource,
        out: File,
        kbps: Int,
        cutsMs: List<Pair<Long, Long>>,
        id3Tag: ByteArray,
        durationHintMs: Long = 0,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Stats {
        val cuts = PcmSplicer.normalizeCuts(cutsMs)
        val durationUs = if (source.durationUs > 0) source.durationUs else durationHintMs * 1000
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val sink = FileSink(raf)
            sink.write(id3Tag, 0, id3Tag.size)
            val audioStart = id3Tag.size.toLong()

            var encoder: Mp3Encoder? = null
            var splicer: PcmSplicer? = null
            var adapter: FormatAdapter? = null
            var rate = 0
            var channels = 0
            var tolerance = 0L
            var srcPos = -1L // source frame position of the next decoded frame (decoder timeline)
            var srcFrames = 0L // frames decoded
            var outFrames = 0L // frames encoded: the output timeline (continuous, no gaps at the cuts)
            var spliced = FloatArray(0)
            var lastProgress = -1f
            try {
                while (true) {
                    if (cancelled()) throw CancellationException("transcode cancelled")
                    val block = source.read() ?: break
                    if (block.frames <= 0 || block.channels <= 0 || block.sampleRate <= 0) continue
                    if (encoder == null) {
                        rate = block.sampleRate
                        channels = minOf(block.channels, 2)
                        encoder = try {
                            Mp3Encoder(rate, channels, kbps)
                        } catch (e: IllegalArgumentException) {
                            throw TranscodeException(e.message ?: "encoder", TranscodeException.Kind.Encode, e)
                        }
                        adapter = FormatAdapter(rate, channels)
                        tolerance = RESYNC_TOLERANCE_MS * rate / 1000
                        splicer = PcmSplicer(cuts, rate).also { sp ->
                            if (!sp.isEmpty && durationUs > 0) {
                                val total = durationUs * rate / 1_000_000
                                if (total - sp.coveredFrames(total) < PcmSplicer.MIN_KEPT_MS * rate / 1000) sp.clear()
                            }
                        }
                    }
                    val frames = adapter!!.convert(block)
                    if (frames <= 0) continue
                    val pcm = adapter.data
                    if (block.timeUs >= 0) {
                        val tsFrames = (block.timeUs * rate + 500_000) / 1_000_000
                        if (srcPos < 0 || abs(tsFrames - srcPos) > tolerance) srcPos = tsFrames
                    } else if (srcPos < 0) {
                        srcPos = 0
                    }
                    if (spliced.size < frames * channels) spliced = FloatArray(frames * channels)
                    val kept = splicer!!.process(pcm, 0, frames, channels, srcPos, spliced, 0)
                    srcPos += frames
                    srcFrames += frames
                    if (kept > 0) {
                        val chunk = encode { encoder.encode(spliced, 0, kept) }
                        sink.write(chunk.bytes, 0, chunk.size)
                        outFrames += kept
                    }
                    if (durationUs > 0) {
                        val f = (srcPos * 1_000_000.0 / rate / durationUs).toFloat().coerceIn(0f, 1f)
                        if (f - lastProgress >= 0.002f) {
                            lastProgress = f
                            progress(f)
                        }
                    }
                }
                if (encoder == null || outFrames == 0L) throw TranscodeException("nothing decoded", TranscodeException.Kind.Decode)
                val tail = encode { encoder.flush() }
                sink.write(tail.bytes, 0, tail.size)
                sink.flush()
                val end = sink.position
                // The LAME / Info frame over its placeholder (the first frame of the MP3 data, same size).
                val tag = encoder.lameTagFrame()
                if (tag.isNotEmpty() && end - audioStart >= tag.size) {
                    val head = ByteArray(4)
                    raf.seek(audioStart)
                    raf.readFully(head)
                    if (frameLength(head) == tag.size) {
                        raf.seek(audioStart)
                        raf.write(tag)
                    }
                }
                raf.setLength(end)
                raf.fd.sync()
                val dropped = splicer!!.droppedFrames
                val toMs = { f: Long -> (f * 1000 + rate / 2) / rate }
                return Stats(
                    segmentsCut = splicer.segmentsHit,
                    cutMs = toMs(dropped),
                    sourceMs = toMs(srcFrames),
                    outputMs = toMs(outFrames),
                    bytes = end,
                    sampleRate = encoder.outSampleRate,
                    channels = channels,
                )
            } finally {
                encoder?.close()
            }
        }
    }

    private inline fun encode(block: () -> Mp3Encoder.Chunk): Mp3Encoder.Chunk = try {
        block()
    } catch (e: IllegalStateException) {
        throw TranscodeException(e.message ?: "encode", TranscodeException.Kind.Encode, e)
    } catch (e: RuntimeException) {
        // jump3r is a straight C port: a bug surfaces as an index error. Not the user's fault, not retried as I/O.
        throw TranscodeException("${e.javaClass.simpleName}: ${e.message}", TranscodeException.Kind.Encode, e)
    }

    /** Byte length of the MPEG-1/2/2.5 layer III frame with header [h] (4 bytes); 0 when it isn't one. */
    fun frameLength(h: ByteArray): Int {
        if (h.size < 4) return 0
        val b1 = h[1].toInt() and 0xFF
        val b2 = h[2].toInt() and 0xFF
        if ((h[0].toInt() and 0xFF) != 0xFF || (b1 and 0xE0) != 0xE0) return 0
        val version = (b1 shr 3) and 3 // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
        if (version == 1 || ((b1 shr 1) and 3) != 1) return 0
        val brIndex = b2 shr 4
        val srIndex = (b2 shr 2) and 3
        if (brIndex == 0 || brIndex == 15 || srIndex == 3) return 0
        val v1 = version == 3
        val kbps = (if (v1) V1_KBPS else V2_KBPS)[brIndex]
        val rate = intArrayOf(44100, 48000, 32000)[srIndex] shr (if (v1) 0 else if (version == 2) 1 else 2)
        val pad = (b2 shr 1) and 1
        return (if (v1) 144 else 72) * kbps * 1000 / rate + pad
    }

    private val V1_KBPS = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val V2_KBPS = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** Buffered sequential writes to a RandomAccessFile (which is unbuffered). */
    private class FileSink(private val raf: RandomAccessFile) {
        private val buf = ByteArray(256 * 1024)
        private var n = 0
        var position = 0L
            private set

        fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (len >= buf.size) {
                flush()
                raf.write(b, off, len)
                position += len
                return
            }
            if (n + len > buf.size) flush()
            System.arraycopy(b, off, buf, n, len)
            n += len
            position += len
        }

        fun flush() {
            if (n > 0) raf.write(buf, 0, n)
            n = 0
        }
    }

    /**
     * Converts decoded blocks to the stream's fixed output format: channels remixed ([outChannels] is 1 or 2; mono is
     * duplicated, more than two channels are downmixed with the centre at -3 dB), sample rate changes undone by linear
     * interpolation (state carried across blocks; a rare case, so simple beats perfect).
     */
    internal class FormatAdapter(private val outRate: Int, private val outChannels: Int) {
        var data = FloatArray(0)
            private set
        private var mixed = FloatArray(0)

        // Resampler state: position of the next output frame relative to the current block's first frame (in input
        // frames; -1..0 interpolates from the previous block's last frame).
        private var resRate = 0
        private var phase = 0.0
        private val last = FloatArray(2)
        private var hasLast = false

        /** Converts [b]; the result is in [data] (frames returned). */
        fun convert(b: PcmBlock): Int {
            val frames = b.frames
            // 1. Channels.
            val src: FloatArray
            if (b.channels == outChannels) {
                src = b.samples
            } else {
                if (mixed.size < frames * outChannels) mixed = FloatArray(frames * outChannels)
                remix(b.samples, frames, b.channels, mixed)
                src = mixed
            }
            // 2. Sample rate.
            if (b.sampleRate == outRate) {
                resRate = 0
                hasLast = false
                if (src === b.samples) {
                    if (data.size < frames * outChannels) data = FloatArray(frames * outChannels)
                    System.arraycopy(src, 0, data, 0, frames * outChannels)
                } else {
                    val tmp = data
                    data = mixed
                    mixed = tmp
                }
                return frames
            }
            if (b.sampleRate != resRate) {
                resRate = b.sampleRate
                phase = 0.0
                hasLast = false
            }
            val step = resRate.toDouble() / outRate
            val maxOut = (frames / step).toInt() + 2
            if (data.size < maxOut * outChannels) data = FloatArray(maxOut * outChannels)
            var o = 0
            var p = phase
            while (true) {
                val i0 = kotlin.math.floor(p).toInt()
                if (i0 + 1 >= frames) break
                val t = (p - i0).toFloat()
                for (c in 0 until outChannels) {
                    val a = if (i0 < 0) (if (hasLast) last[c] else src[c]) else src[i0 * outChannels + c]
                    val z = src[(i0 + 1) * outChannels + c]
                    data[o * outChannels + c] = a + (z - a) * t
                }
                o++
                p += step
            }
            phase = p - frames
            for (c in 0 until outChannels) last[c] = src[(frames - 1) * outChannels + c]
            hasLast = true
            return o
        }

        private fun remix(s: FloatArray, frames: Int, inCh: Int, out: FloatArray) {
            var i = 0
            var o = 0
            for (f in 0 until frames) {
                if (outChannels == 1) {
                    var sum = 0f
                    for (c in 0 until inCh) sum += s[i + c]
                    out[o++] = sum / inCh
                } else if (inCh == 1) {
                    out[o++] = s[i]
                    out[o++] = s[i]
                } else {
                    // FL FR [FC [LFE [BL BR ...]]]: L = FL + 0.707 FC, R = FR + 0.707 FC, normalised.
                    if (inCh >= 3) {
                        val c = 0.7071f * s[i + 2]
                        out[o++] = (s[i] + c) / 1.7071f
                        out[o++] = (s[i + 1] + c) / 1.7071f
                    } else {
                        out[o++] = s[i]
                        out[o++] = s[i + 1]
                    }
                }
                i += inCh
            }
        }
    }
}
