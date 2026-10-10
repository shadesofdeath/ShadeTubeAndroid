package com.app.shadetube.lyrics

/**
 * Lyrics are timed to the song. A YouTube music video carries parts the song doesn't have (an intro skit, a sponsor
 * spot: the SponsorBlock segments the player skips): [songTime] takes the ones before a media position out (a position
 * inside one maps to its start), [mediaTime] is the inverse. `extra`: sorted, non-overlapping [start, end) in ms
 * (empty for audio uploads, local files and downloads, whose cut file already is the song).
 *
 * Then the per-track offset applies (+ = lyrics later): [clockMs] is where the lyrics are now, [seekTarget] the media
 * position for a tap on a line (ports of app/LyricsService lyricsClockMs / seekToLyricsTime, as pure functions).
 */
object LyricsTimeline {
    /** Ranges as (start, end) pairs, end exclusive. */
    fun songTime(mediaMs: Long, extra: List<Pair<Long, Long>>): Long {
        var removed = 0L
        for ((start, end) in extra) {
            if (mediaMs <= start) break
            removed += minOf(mediaMs, end) - start
        }
        return maxOf(0L, mediaMs - removed)
    }

    /** Ranges as (start, end) pairs, end exclusive. */
    fun mediaTime(songMs: Long, extra: List<Pair<Long, Long>>): Long {
        var t = maxOf(0L, songMs)
        for ((start, end) in extra) {
            if (start > t) break
            t += end - start
        }
        return t
    }

    /** The lyrics' clock: the player position on the song's timeline, minus the track's offset. */
    fun clockMs(positionMs: Long, extra: List<Pair<Long, Long>>, offsetMs: Int): Int =
        (songTime(positionMs, extra) - offsetMs).toInt()

    /** The media position to seek to for a tap on a line at [lyricsMs]. */
    fun seekTarget(lyricsMs: Int, extra: List<Pair<Long, Long>>, offsetMs: Int): Long =
        mediaTime(maxOf(0L, lyricsMs.toLong() + offsetMs), extra)
}
