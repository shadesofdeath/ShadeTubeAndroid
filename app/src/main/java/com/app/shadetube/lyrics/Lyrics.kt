package com.app.shadetube.lyrics

/**
 * Song lyrics (port of lyrics/Lyrics.h): synced lyrics from LRCLIB (https://lrclib.net, free, no key) - the same
 * source Spotube uses - plus an optional second provider the app plugs in (Spotify's own lyrics when logged in), and
 * the user's own files for items that play from disk (a sidecar "<same name>.lrc", else lyrics embedded in the file's
 * tags). Online results are cached on disk (`cache/lyrics/<trackId>.json`) together with the providers already asked,
 * including negative results (for 7 days), so a track without lyrics is never re-queried and a provider added later
 * (the user logs in to Spotify) is still asked once. See [LyricsFetcher] and [LyricsService].
 */

/** A timed piece of a line (enhanced LRC "<mm:ss.xx>word"). A line's words concatenate to its text. */
data class LyricWord(
    val timeMs: Int = 0,
    /** With its trailing space. */
    val text: String = "",
)

data class LyricLine(
    /** -1 for unsynced lines. */
    val timeMs: Int = 0,
    /** Empty text = instrumental gap. */
    val text: String = "",
    /** Empty = timed per line only. */
    val words: List<LyricWord> = emptyList(),
)

data class Lyrics(
    val synced: Boolean = false,
    val instrumental: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
    /** One of the `SOURCE_*` constants; "" = unknown (old cache entries: LRCLIB). */
    val source: String = "",
)

/** Where lyrics came from ([Lyrics.source]; also the provider names in the cache's "tried" list). */
const val SOURCE_LRCLIB = "lrclib"
const val SOURCE_SPOTIFY = "spotify"
/** A sidecar .lrc next to the audio file. */
const val SOURCE_FILE = "file"
/** Embedded in the audio file (ID3 SYLT / USLT, FLAC / MP4 lyrics tags). */
const val SOURCE_TAG = "tag"

/** What a provider answered: lyrics, a definitive "none" (cached), or a failure worth retrying later (not cached). */
data class ProviderResult(val kind: Kind = Kind.Failed, val lyrics: Lyrics = Lyrics()) {
    enum class Kind { Found, NotFound, Failed }

    companion object {
        val failed = ProviderResult(Kind.Failed)
        val notFound = ProviderResult(Kind.NotFound)
        fun found(l: Lyrics) = ProviderResult(Kind.Found, l)
    }
}

/** A second online provider asked after LRCLIB (Spotify). Called on a worker; may suspend. */
fun interface LyricsProvider {
    suspend fun fetch(): ProviderResult
}

/** Index of the line active at [positionMs] (last line with timeMs <= position), -1 before the first. */
fun activeLine(l: Lyrics, positionMs: Int): Int {
    if (!l.synced || l.lines.isEmpty()) return -1
    var lo = 0
    var hi = l.lines.size // first index with positionMs < timeMs (upper_bound)
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (positionMs < l.lines[mid].timeMs) hi = mid else lo = mid + 1
    }
    return lo - 1
}
