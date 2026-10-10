package com.app.shadetube.stats

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track

/** Last 7 days / last 30 days / all time. */
enum class StatsPeriod { Week, Month, All }

data class StatsTrack(
    val track: Track,
    val streams: Int,
    /** Over the counted streams. */
    val listenedMs: Long,
)

data class StatsArtist(
    /** id may be empty (plain-text credit). */
    val artist: ArtistRef,
    val streams: Int,
    val listenedMs: Long,
)

data class StatsAlbum(
    /** id may be empty. */
    val album: AlbumRef,
    /** First artist of the album's first track. */
    val artist: String,
    /** Its tracks have different first artists (compilation, soundtrack). */
    val variousArtists: Boolean,
    val streams: Int,
    val listenedMs: Long,
)

data class StatsPlay(
    val track: Track,
    /** Unix seconds. */
    val startedAt: Long,
    val listenedMs: Long,
)

/** Listening time per local hour of the week (every play, split across the hours it spans). */
class StatsHeatmap {
    /** [weekday * 24 + hour], weekday 0 = Monday. */
    val ms = LongArray(7 * 24)
    var maxMs = 0L
        internal set
    var totalMs = 0L
        internal set

    fun at(weekday: Int, hour: Int): Long = ms[weekday * 24 + hour]
}

class StatsSummary(
    /** Unix seconds: now - 7/30 days, or the first play (all time; 0 = nothing yet). */
    val periodStart: Long,
    /** Every play in the period. */
    val listenedMs: Long,
    val streams: Int,
    val distinctTracks: Int,
    val distinctArtists: Int,
    /** Most streamed first (ties: more time, then more recent). */
    val topTracks: List<StatsTrack>,
    val topArtists: List<StatsArtist>,
    val topAlbums: List<StatsAlbum>,
    /** Newest first (streams only). */
    val recent: List<StatsPlay>,
)

/** "Yıl özeti": one local calendar year. Time totals count every play, everything else streams only. */
class YearSummary(val year: Int) {
    var listenedMs = 0L; internal set
    var streams = 0; internal set
    var distinctTracks = 0; internal set
    var distinctArtists = 0; internal set
    /** Artists streamed this year and in no earlier year. */
    var newArtists = 0; internal set
    /** Days with a stream. */
    var activeDays = 0; internal set
    /** Most consecutive days with a stream (the earliest such run). */
    var longestStreak = 0; internal set
    /** Unix seconds of a stream on the streak's first / last day (0 = none). */
    var streakFrom = 0L; internal set
    var streakTo = 0L; internal set
    /** Listening per month (January first). */
    val monthMs = LongArray(12)
    /** Listening per weekday (0 = Monday). */
    val weekdayMs = LongArray(7)
    /** 0..11, -1 = no listening. */
    var topMonth = -1; internal set
    /** 0 = Monday, -1 = no listening. */
    var topWeekday = -1; internal set
    /** The year's first stream. */
    var firstStream: StatsPlay? = null; internal set
    var topTracks: List<StatsTrack> = emptyList(); internal set
    var topArtists: List<StatsArtist> = emptyList(); internal set
    var topAlbums: List<StatsAlbum> = emptyList(); internal set
    val heatmap = StatsHeatmap()
}

/** A distinct track as stored (one per name + first artist). Port of ListenStats::Track. */
data class ListenTrack(
    var id: String = "",
    var name: String = "",
    var artists: List<ArtistRef> = emptyList(),
    var albumId: String = "",
    var albumName: String = "",
    /** One cover url (~300 px), may be empty. */
    var image: String = "",
    /** 0 = unknown. */
    var durationMs: Long = 0,
)

/** One play (port of ListenStats::Play); the store keeps them in [PlayList]'s primitive arrays. */
data class Play(
    /** Index into the store's tracks. */
    val track: Int,
    /** Unix seconds. */
    val startedAt: Long,
    val listenedMs: Long,
    /** From listening-imported.json (Spotify's history), not heard in ShadeTube. */
    val imported: Boolean = false,
)

/**
 * Plays in parallel primitive arrays (what the C++ std::vector<Play> is for the aggregations: no object per play).
 * Not thread-safe.
 */
class PlayList(capacity: Int = 16) {
    var size = 0
        private set
    private var tracks = IntArray(capacity.coerceAtLeast(4))
    private var starts = LongArray(capacity.coerceAtLeast(4))
    private var lengths = LongArray(capacity.coerceAtLeast(4))
    private var importedFlags = BooleanArray(capacity.coerceAtLeast(4))

    fun isEmpty() = size == 0
    fun isNotEmpty() = size > 0

    fun track(i: Int) = tracks[i]
    fun startedAt(i: Int) = starts[i]
    fun listenedMs(i: Int) = lengths[i]
    fun imported(i: Int) = importedFlags[i]
    operator fun get(i: Int) = Play(tracks[i], starts[i], lengths[i], importedFlags[i])
    fun last(): Play = get(size - 1)
    fun first(): Play = get(0)
    fun toList(): List<Play> = List(size) { get(it) }

    internal fun setTrack(i: Int, t: Int) {
        tracks[i] = t
    }

    private fun grow(min: Int) {
        if (min <= tracks.size) return
        val n = maxOf(min, tracks.size * 2)
        tracks = tracks.copyOf(n)
        starts = starts.copyOf(n)
        lengths = lengths.copyOf(n)
        importedFlags = importedFlags.copyOf(n)
    }

    fun ensureCapacity(n: Int) = grow(n)

    fun add(track: Int, startedAt: Long, listenedMs: Long, imported: Boolean = false) {
        grow(size + 1)
        tracks[size] = track
        starts[size] = startedAt
        lengths[size] = listenedMs
        importedFlags[size] = imported
        size++
    }

    fun add(p: Play) = add(p.track, p.startedAt, p.listenedMs, p.imported)

    fun addAll(o: PlayList) {
        grow(size + o.size)
        System.arraycopy(o.tracks, 0, tracks, size, o.size)
        System.arraycopy(o.starts, 0, starts, size, o.size)
        System.arraycopy(o.lengths, 0, lengths, size, o.size)
        System.arraycopy(o.importedFlags, 0, importedFlags, size, o.size)
        size += o.size
    }

    /** Inserts after every play that started at or before it (std::upper_bound): appends in the common case. */
    fun insertSorted(track: Int, startedAt: Long, listenedMs: Long, imported: Boolean) {
        if (size == 0 || starts[size - 1] <= startedAt) {
            add(track, startedAt, listenedMs, imported)
            return
        }
        val at = upperBound(startedAt)
        grow(size + 1)
        val n = size - at
        System.arraycopy(tracks, at, tracks, at + 1, n)
        System.arraycopy(starts, at, starts, at + 1, n)
        System.arraycopy(lengths, at, lengths, at + 1, n)
        System.arraycopy(importedFlags, at, importedFlags, at + 1, n)
        tracks[at] = track
        starts[at] = startedAt
        lengths[at] = listenedMs
        importedFlags[at] = imported
        size++
    }

    /** First index whose start is >= t (std::lower_bound). */
    fun lowerBound(t: Long, from: Int = 0, to: Int = size): Int {
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index whose start is > t (std::upper_bound). */
    fun upperBound(t: Long, from: Int = 0, to: Int = size): Int {
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Keeps the plays [keep] accepts, in order (std::erase_if). */
    inline fun retain(keep: (Int) -> Boolean) {
        var w = 0
        for (r in 0 until size) {
            if (!keep(r)) continue
            if (w != r) moveTo(r, w)
            w++
        }
        truncate(w)
    }

    @PublishedApi
    internal fun moveTo(from: Int, to: Int) {
        tracks[to] = tracks[from]
        starts[to] = starts[from]
        lengths[to] = lengths[from]
        importedFlags[to] = importedFlags[from]
    }

    fun truncate(n: Int) {
        if (n < size) size = n
    }

    /** Drops the first [n] plays. */
    fun dropFirst(n: Int) {
        if (n <= 0) return
        if (n >= size) {
            size = 0
            return
        }
        val left = size - n
        System.arraycopy(tracks, n, tracks, 0, left)
        System.arraycopy(starts, n, starts, 0, left)
        System.arraycopy(lengths, n, lengths, 0, left)
        System.arraycopy(importedFlags, n, importedFlags, 0, left)
        size = left
    }

    fun isSortedByStart(): Boolean {
        for (i in 1 until size) if (starts[i - 1] > starts[i]) return false
        return true
    }

    /** Stable sort by start time (no-op when already in order, the usual case). */
    fun sortByStart() {
        if (isSortedByStart()) return
        val order = (0 until size).sortedWith { a, b -> starts[a].compareTo(starts[b]) } // TimSort: stable
        val t = IntArray(size) { tracks[order[it]] }
        val s = LongArray(size) { starts[order[it]] }
        val l = LongArray(size) { lengths[order[it]] }
        val f = BooleanArray(size) { importedFlags[order[it]] }
        System.arraycopy(t, 0, tracks, 0, size)
        System.arraycopy(s, 0, starts, 0, size)
        System.arraycopy(l, 0, lengths, 0, size)
        System.arraycopy(f, 0, importedFlags, 0, size)
    }

    fun reverse() {
        var i = 0
        var j = size - 1
        while (i < j) {
            val t = tracks[i]; tracks[i] = tracks[j]; tracks[j] = t
            val s = starts[i]; starts[i] = starts[j]; starts[j] = s
            val l = lengths[i]; lengths[i] = lengths[j]; lengths[j] = l
            val f = importedFlags[i]; importedFlags[i] = importedFlags[j]; importedFlags[j] = f
            i++; j--
        }
    }

    fun copy(): PlayList {
        val c = PlayList(size)
        c.addAll(this)
        return c
    }

    fun countImported(): Int {
        var n = 0
        for (i in 0 until size) if (importedFlags[i]) n++
        return n
    }

    companion object {
        fun of(plays: Collection<Play>): PlayList = PlayList(plays.size).also { l -> plays.forEach { l.add(it) } }
    }
}
