package com.app.shadetube.downloads

import kotlin.math.PI
import kotlin.math.cos

/**
 * PCM splicer of the MP3 transcode (port of the C++ Downloader's `Splicer`): drops the frames of the cut ranges
 * (SponsorBlock segments) and fades around every splice, so the joins don't click and the output timeline is
 * continuous.
 *
 * Works on SOURCE frame positions (decoder timeline), so a cut boundary can fall anywhere inside a decoded buffer:
 * every frame is kept or dropped individually. The gain of a kept frame depends only on its distance to the
 * neighbouring cuts — a raised-cosine fade-out over the [FADE_MS] before a cut and a fade-in over the [FADE_MS] after
 * it — so no look-ahead is needed across buffers.
 *
 * [cutsMs] must be normalized ([normalizeCuts]: sorted, disjoint); they are converted to frames at [rate].
 */
class PcmSplicer(cutsMs: List<Pair<Long, Long>>, rate: Int) {
    private class Range(val start: Long, val end: Long) {
        /** Removed at least one frame. */
        var hit = false
    }

    private val ranges = ArrayList<Range>()

    /** Fade length in frames (8 ms at [rate], at least 1). */
    val fade: Long = maxOf(1L, FADE_MS * rate / 1000)

    /** Frames dropped so far. */
    var droppedFrames = 0L
        private set

    init {
        for ((s, e) in cutsMs) {
            val r = Range(s * rate / 1000, e * rate / 1000)
            if (r.end > r.start) ranges.add(r)
        }
    }

    val isEmpty: Boolean get() = ranges.isEmpty()

    fun clear() = ranges.clear()

    /** Cut ranges that removed at least one frame. */
    val segmentsHit: Int get() = ranges.count { it.hit }

    /** Frames the ranges would remove from a source of [frames] frames. */
    fun coveredFrames(frames: Long): Long {
        var n = 0L
        for (r in ranges) n += maxOf(0L, minOf(r.end, frames) - maxOf(0L, r.start))
        return n
    }

    /**
     * Writes the kept frames of `input[inOffset until inOffset + frames * ch]` — source frames `[pos, pos + frames)` —
     * to [out] from [outOffset] (room for `frames * ch` samples needed). Returns the number of frames kept.
     */
    fun process(input: FloatArray, inOffset: Int, frames: Int, ch: Int, pos: Long, out: FloatArray, outOffset: Int): Int {
        if (ranges.isEmpty()) {
            System.arraycopy(input, inOffset, out, outOffset, frames * ch)
            return frames
        }
        val n = ranges.size
        val end = pos + frames
        // First range that ends after `pos` (ranges are sorted and disjoint).
        var ci = partitionPoint { it.end <= pos }
        var kept = 0
        var o = outOffset
        var p = pos
        while (p < end) {
            while (ci < n && ranges[ci].end <= p) ++ci
            if (ci < n && p >= ranges[ci].start) {
                // Inside a cut: skip to its end (or the end of this buffer).
                val stop = minOf(ranges[ci].end, end)
                droppedFrames += stop - p
                ranges[ci].hit = true
                p = stop
                continue
            }
            // Kept frame: the run up to the next cut (or the buffer end) is copied in one go; only the frames within
            // `fade` of a splice get a per-frame gain.
            val runEnd = if (ci < n) minOf(ranges[ci].start, end) else end
            val fadeOutFrom = if (ci < n) ranges[ci].start - fade else Long.MAX_VALUE
            val fadeInUntil = if (ci > 0) ranges[ci - 1].end + fade else Long.MIN_VALUE
            var q = p
            while (q < runEnd) {
                if (q >= fadeInUntil && q < fadeOutFrom) {
                    // Unfaded stretch: bulk copy up to the fade-out start.
                    val to = minOf(runEnd, fadeOutFrom)
                    val count = ((to - q) * ch).toInt()
                    System.arraycopy(input, inOffset + ((q - pos) * ch).toInt(), out, o, count)
                    o += count
                    q = to
                    continue
                }
                var g = 1f
                if (ci < n && q >= fadeOutFrom) g *= curve((ranges[ci].start - q).toFloat() / fade)
                if (ci > 0 && q < fadeInUntil) g *= curve((q - ranges[ci - 1].end + 1).toFloat() / fade)
                val src = inOffset + ((q - pos) * ch).toInt()
                for (c in 0 until ch) out[o++] = input[src + c] * g
                ++q
            }
            kept += (runEnd - p).toInt()
            p = runEnd
        }
        return kept
    }

    private inline fun partitionPoint(pred: (Range) -> Boolean): Int {
        var lo = 0
        var hi = ranges.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (pred(ranges[mid])) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Raised-cosine fade-out before / fade-in after every splice. */
        const val FADE_MS = 8L

        /** Cuts that would leave less than this are bogus data: ignored. */
        const val MIN_KEPT_MS = 10_000L

        /** Raised cosine, x in (0, 1]: ~0 next to the splice, 1 at the far end of the fade. */
        fun curve(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return (0.5 - 0.5 * cos(PI * c)).toFloat()
        }

        /** Sorts, clamps (start >= 0) and merges overlapping / touching ranges; drops empty ones. */
        fun normalizeCuts(cuts: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
            val sorted = cuts.map { maxOf(0L, it.first) to it.second }.filter { it.second > it.first }
                .sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
            val out = ArrayList<Pair<Long, Long>>()
            for (c in sorted) {
                val last = out.lastOrNull()
                if (last != null && c.first <= last.second) out[out.size - 1] = last.first to maxOf(last.second, c.second)
                else out.add(c)
            }
            return out
        }
    }
}
