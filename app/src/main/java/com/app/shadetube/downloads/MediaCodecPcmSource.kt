package com.app.shadetube.downloads

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes the audio track of a downloaded stream file (AAC in MP4, Opus in WebM — whatever the platform decoders
 * handle) to float PCM with MediaExtractor + MediaCodec (synchronous mode). The output format is taken from the
 * decoder's output format changes (HE-AAC reports the core rate in the container and the SBR rate in the decoder
 * output), 16-bit and float output both work. Kept thin on purpose: all the logic that can be tested on the JVM lives
 * in [Mp3Transcoder]. Not thread-safe; [close] releases the codec.
 */
class MediaCodecPcmSource(file: File) : PcmSource {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var rate: Int
    private var channels: Int
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var samples = FloatArray(0)
    private var closed = false

    override val durationUs: Long

    /** The source's MIME type ("audio/mp4a-latm", "audio/opus", ...). */
    val mime: String

    init {
        val format: MediaFormat
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: throw TranscodeException("no audio track in ${file.name}", TranscodeException.Kind.Decode)
            extractor.selectTrack(track)
            format = extractor.getTrackFormat(track)
            mime = format.getString(MediaFormat.KEY_MIME)!!
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime)
        } catch (e: TranscodeException) {
            extractor.release()
            throw e
        } catch (e: Exception) {
            extractor.release()
            throw TranscodeException("cannot open ${file.name}: ${e.message}", TranscodeException.Kind.Decode, e)
        }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            extractor.release()
            throw TranscodeException("cannot start the $mime decoder: ${e.message}", TranscodeException.Kind.Decode, e)
        }
    }

    override fun read(): PcmBlock? {
        if (outputDone) return null
        try {
            while (true) {
                // Feed every input buffer the codec has free (without waiting: output is what we wait for).
                while (!inputDone) {
                    val idx = codec.dequeueInputBuffer(0)
                    if (idx < 0) break
                    val buf = codec.getInputBuffer(idx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(idx, 0, n, extractor.sampleTime.coerceAtLeast(0), 0)
                        extractor.advance()
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else AudioFormat.ENCODING_PCM_16BIT
                    }
                    out >= 0 -> {
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        var block: PcmBlock? = null
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(out)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val ordered = buf.slice().order(ByteOrder.nativeOrder())
                            val n = when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val fb = ordered.asFloatBuffer()
                                    val k = fb.remaining()
                                    ensure(k)
                                    fb.get(samples, 0, k)
                                    k
                                }
                                AudioFormat.ENCODING_PCM_16BIT -> {
                                    val sb = ordered.asShortBuffer()
                                    val k = sb.remaining()
                                    ensure(k)
                                    for (i in 0 until k) samples[i] = sb.get(i) / 32768f
                                    k
                                }
                                else -> throw TranscodeException("unsupported PCM encoding $encoding", TranscodeException.Kind.Decode)
                            }
                            val frames = n / channels
                            if (frames > 0) block = PcmBlock(samples, frames, channels, rate, info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (eos) outputDone = true
                        if (block != null) return block
                        if (eos) return null
                    }
                    // INFO_TRY_AGAIN_LATER / (deprecated) output buffers changed: loop.
                }
            }
        } catch (e: TranscodeException) {
            throw e
        } catch (e: IllegalStateException) {
            // MediaCodec.CodecException is an IllegalStateException.
            throw TranscodeException("$mime decoder failed: ${e.message}", TranscodeException.Kind.Decode, e)
        }
    }

    private fun ensure(n: Int) {
        if (samples.size < n) samples = FloatArray(n)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
