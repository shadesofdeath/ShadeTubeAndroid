package com.app.shadetube.downloads

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.app.shadetube.core.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * SponsorBlock trimming of a downloaded AAC (.m4a) file without re-encoding: the file is remuxed with
 * [MediaExtractor] + [MediaMuxer] and every AAC frame whose middle falls inside a cut range is dropped; the frames
 * after a cut move back by the time removed so the output timeline is continuous (no gaps).
 *
 * The C++ app cut MP3 transcodes sample-accurately from the decoded PCM with 8 ms raised-cosine fades at every splice
 * (audio/Downloader Splicer). Without an encoder this is frame-accurate instead (an AAC frame is 1024 samples,
 * ~23 ms at 44.1 kHz) and a splice has no fade: the decoder's overlap-add joins the frames on both sides of a cut,
 * which is usually inaudible but can be a soft click inside a song. Most SponsorBlock music segments are the intro /
 * outro of a music video, i.e. at the start or the end, where there is no splice at all.
 *
 * Same guards as the C++: cut ranges are normalized ([normalizeCuts]); cuts that would leave less than
 * [MIN_KEPT_MS] are bogus data and ignored. Any failure returns null and the caller keeps the untrimmed file.
 */
object Mp4Trimmer {
    /** Cuts that would leave less than this are bogus data: ignored. */
    const val MIN_KEPT_MS = 10_000L

    data class Stats(
        /** Cut ranges that removed at least one frame. */
        val segmentsCut: Int,
        /** Audio removed, ms. */
        val cutMs: Long,
        /** Source length, ms. */
        val sourceMs: Long,
        /** Output length, ms = sourceMs - cutMs. */
        val outputMs: Long,
    )

    /** Sorts, clamps (start >= 0) and merges overlapping / touching ranges; drops empty ones. */
    fun normalizeCuts(cuts: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val sorted = cuts.map { maxOf(0L, it.first) to it.second }.filter { it.second > it.first }
            .sortedWith(compareBy({ it.first }, { it.second }))
        val out = ArrayList<Pair<Long, Long>>()
        for (c in sorted) {
            val last = out.lastOrNull()
            if (last != null && c.first <= last.second) out[out.size - 1] = last.first to maxOf(last.second, c.second)
            else out.add(c)
        }
        return out
    }

    /** Milliseconds the (normalized) ranges cover of a source of [durationMs]. */
    fun coveredMs(cuts: List<Pair<Long, Long>>, durationMs: Long): Long =
        cuts.sumOf { maxOf(0L, minOf(it.second, durationMs) - maxOf(0L, it.first)) }

    /** Whether cutting [cuts] from [durationMs] of audio is sane (would leave at least [MIN_KEPT_MS]). */
    fun cutsAllowed(cuts: List<Pair<Long, Long>>, durationMs: Long): Boolean =
        durationMs <= 0 || durationMs - coveredMs(cuts, durationMs) >= MIN_KEPT_MS

    /**
     * Frame decision (pure, tested): a frame [startUs, startUs + durUs) is dropped when its middle lies inside one of
     * the (normalized, ms) cut ranges. Returns the index of that range, or -1 to keep the frame.
     */
    fun cutIndexOf(cuts: List<Pair<Long, Long>>, startUs: Long, durUs: Long): Int {
        val midMs = (startUs + durUs / 2) / 1000
        for ((i, c) in cuts.withIndex()) if (midMs >= c.first && midMs < c.second) return i
        return -1
    }

    /**
     * Writes [input] without the [cutsMs] ranges to [output] (an MPEG-4 file). Null when nothing was cut (no ranges, the
     * guard, a non-AAC track) or on any failure; [output] is deleted then. [isCancelled] is polled per frame.
     */
    fun trim(input: File, output: File, cutsMs: List<Pair<Long, Long>>, isCancelled: () -> Boolean): Stats? {
        val cuts = normalizeCuts(cutsMs)
        if (cuts.isEmpty()) return null
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(input.path)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) return null
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            if (!cutsAllowed(cuts, durationMs)) {
                Log.w("download", "cut ranges would leave < ${MIN_KEPT_MS / 1000} s of $durationMs ms: not cutting")
                return null
            }
            extractor.selectTrack(track)
            val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val frameUs = 1024L * 1_000_000L / maxOf(1, rate) // AAC-LC frame length (fallback for the last frame)
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val cap = maxOf(maxSize, 64 * 1024)

            output.delete()
            val mux = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val outTrack = mux.addTrack(format)
            mux.start()
            started = true

            var cur = ByteBuffer.allocate(cap)
            var next = ByteBuffer.allocate(cap)
            val hit = BooleanArray(cuts.size)
            var droppedUs = 0L
            var endUs = 0L
            var written = 0

            var curSize = extractor.readSampleData(cur, 0)
            var curTime = extractor.sampleTime
            var curFlags = extractor.sampleFlags
            val info = MediaCodec.BufferInfo()
            while (curSize >= 0) {
                if (isCancelled()) return null
                extractor.advance()
                val nextSize = extractor.readSampleData(next, 0)
                val nextTime = if (nextSize >= 0) extractor.sampleTime else -1L
                val nextFlags = if (nextSize >= 0) extractor.sampleFlags else 0
                val dur = if (nextTime > curTime) nextTime - curTime else frameUs
                endUs = curTime + dur
                val ci = cutIndexOf(cuts, curTime, dur)
                if (ci >= 0) {
                    droppedUs += dur
                    hit[ci] = true
                } else {
                    info.set(0, curSize, maxOf(0L, curTime - droppedUs), if (curFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    cur.position(0)
                    cur.limit(curSize)
                    mux.writeSampleData(outTrack, cur, info)
                    ++written
                }
                val t = cur
                cur = next
                next = t
                cur.clear()
                next.clear()
                curSize = nextSize
                curTime = nextTime
                curFlags = nextFlags
                // readSampleData wrote into what is now `cur`; ByteBuffer.clear() keeps its contents.
            }
            if (written == 0) return null
            mux.stop()
            started = false
            val segments = hit.count { it }
            if (segments == 0) {
                output.delete()
                return null
            }
            val sourceMs = (endUs + 500) / 1000
            val cutMs = (droppedUs + 500) / 1000
            Log.i("download", "cut $segments segment(s): $cutMs ms removed, $sourceMs ms -> ${sourceMs - cutMs} ms")
            return Stats(segments, cutMs, sourceMs, sourceMs - cutMs)
        } catch (e: Exception) {
            Log.w("download", "trimming failed, keeping the untrimmed file", e)
            return null
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            if (started) output.delete()
        }
    }
}
