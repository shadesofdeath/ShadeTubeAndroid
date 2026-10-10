package com.app.shadetube.lyrics

import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Files
import com.app.shadetube.core.HttpError
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Lyrics in the app (port of the non-UI part of app/LyricsService): the provider chain for a track - its own file,
 * LRCLIB, then Spotify's lyrics when logged in - the per-track timing offset, and the lyrics written with downloads.
 *
 * @param spotify the logged-in Spotify session's lyrics source, null while logged out (AppGraph wires it).
 * @param fetcher the provider chain + cache.
 * @param offsetsFile `lyrics-offsets.json`.
 */
class LyricsService(
    private val spotify: () -> SpotifyLyricsSource?,
    private val fetcher: LyricsFetcher = LyricsFetcher(),
    offsetsFile: () -> File = { File(Paths.appData, "lyrics-offsets.json") },
) {
    /** A change of a track's offset (views flash the value for a moment: [atMs] is SystemClock-like monotonic ms). */
    data class OffsetChange(val trackId: String, val offsetMs: Int, val atMs: Long)

    /** Lyrics for a track being downloaded: what goes into the MP3 tags (USLT text, SYLT lines) and the sidecar. */
    data class DownloadLyrics(
        val lyrics: Lyrics,
        /** USLT: plain text, one line per line. */
        val text: String,
        /** SYLT: (ms, line) of synced lyrics; empty when unsynced. */
        val synced: List<Pair<Long, String>>,
    )

    private val offsets by lazy { LyricsOffsets(offsetsFile()) }
    private val offsetChange = MutableStateFlow<OffsetChange?>(null)

    /** The last offset change (replaces the C++ lyricsOffsetChangedAt + repaint); null = never. */
    val offsetChanges: StateFlow<OffsetChange?> = offsetChange.asStateFlow()

    /** Spotify answered 429 / 403: leave its lyrics alone until then (monotonic ms), instead of asking for every track. */
    private val spotifyPausedUntil = AtomicLong(0)

    private fun steadyMs() = System.nanoTime() / 1_000_000

    private suspend fun spotifyLyrics(source: SpotifyLyricsSource, trackUri: String): ProviderResult {
        val now = steadyMs()
        if (now < spotifyPausedUntil.get()) return ProviderResult.failed
        return try {
            val body = source.fetch(trackUri)
            if (body.isNullOrEmpty()) return ProviderResult.notFound
            val l = SpotifyLyrics.parse(body) ?: return ProviderResult.notFound
            ProviderResult.found(l)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpError) {
            if (e.status == 429 || e.status == 403) {
                spotifyPausedUntil.set(now + (if (e.status == 429) 10 else 30) * 60 * 1000L)
                Log.w("lyrics", "spotify lyrics paused after HTTP ${e.status}")
            }
            ProviderResult.failed
        } catch (e: Exception) {
            Log.w("lyrics", "spotify lyrics failed: ${e.message}")
            ProviderResult.failed
        }
    }

    /**
     * The query for [track] (port of `lyricsQueryFor`): its file when it plays from disk ([localFile], which the
     * player knows), and Spotify as the fallback provider for a Spotify track while logged in.
     */
    fun queryFor(track: Track, localFile: LocalAudioFile? = null): LyricsQuery {
        val source = spotify()
        // Spotify's own lyrics (Musixmatch and others) for its tracks while logged in, when LRCLIB has no synced ones.
        val useSpotify = source != null && track.id.startsWith("spotify:track:")
        return LyricsQuery(
            cacheKey = track.id.ifEmpty { track.name },
            title = track.name,
            artist = track.artists.firstOrNull()?.name ?: "",
            album = track.album.name,
            durationSec = track.durationMs / 1000,
            audio = localFile,
            fallbackName = if (useSpotify) SOURCE_SPOTIFY else "",
            fallback = if (useSpotify) LyricsProvider { spotifyLyrics(source!!, track.id) } else null,
        )
    }

    /** Lyrics for [track] (null = none). Main-safe. */
    suspend fun lyricsFor(track: Track, localFile: LocalAudioFile? = null): Lyrics? = fetcher.fetch(queryFor(track, localFile))

    suspend fun lyricsFor(query: LyricsQuery): Lyrics? = fetcher.fetch(query)

    // ---- offsets --------------------------------------------------------------------------------------------------
    /** Per-track timing offset (+ = lyrics later); 0 when none. Main-safe. */
    suspend fun offsetMs(trackId: String): Int =
        if (trackId.isEmpty()) 0 else withContext(AppDispatchers.io) { offsets.offsetMs(trackId) }

    /** Stores the offset (clamped to ±30 s, 0 removes) and publishes [offsetChanges]. Returns the stored value. */
    suspend fun setOffset(trackId: String, ms: Int): Int {
        if (trackId.isEmpty()) return 0
        val stored = withContext(AppDispatchers.io) { offsets.set(trackId, ms) }
        offsetChange.value = OffsetChange(trackId, stored, steadyMs())
        return stored
    }

    /** The −/+ buttons and keys (port of ctx().lyricsOffsetBy): shifts the track's lyrics; not for radio stations. */
    suspend fun offsetBy(trackId: String, deltaMs: Int): Int {
        if (trackId.isEmpty() || isStationId(trackId)) return 0
        return setOffset(trackId, offsetMs(trackId) + deltaMs)
    }

    // ---- downloads ------------------------------------------------------------------------------------------------
    /** The lyrics query for a track being downloaded; null when lyrics in downloads are off (or a radio station). */
    fun downloadQuery(track: Track): LyricsQuery? {
        val s = SettingsStore.current
        if (!s.lyricsEnabled || !s.lyricsInDownloads || isStationId(track.id)) return null
        return queryFor(track).copy(audio = null) // the file doesn't exist yet
    }

    /** Fetches the lyrics for a download (port of addDownloadLyrics); null when there are none / instrumental. */
    suspend fun lyricsForDownload(query: LyricsQuery?): DownloadLyrics? {
        if (query == null) return null
        val l = try {
            fetcher.fetch(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("lyrics", "lyrics for download failed: ${e.message}")
            null
        }
        if (l == null || l.instrumental || l.lines.isEmpty()) return null
        return DownloadLyrics(
            lyrics = l,
            text = Lrc.toPlainText(l),
            synced = if (l.synced) l.lines.map { it.timeMs.toLong() to it.text } else emptyList(),
        )
    }

    /**
     * After a finished download: writes "<file stem>.lrc" next to it (synced lyrics only, UTF-8 with BOM, with
     * [ti:] / [ar:] / [al:] tags). The times need no mapping: once the video's non-music parts are cut, the file is
     * the song the lyrics are timed to. False when nothing was written. Main-safe.
     */
    suspend fun writeLyricsSidecar(audioFile: File, lyrics: Lyrics, track: Track): Boolean {
        if (!lyrics.synced || lyrics.lines.isEmpty()) return false
        val text = "\uFEFF" + Lrc.toLrc(lyrics, track.name, track.artistLine, track.album.name)
        return withContext(AppDispatchers.io) { Files.writeAtomic(LocalLyrics.sidecarPath(audioFile), text) }
    }

    companion object {
        /** Offset step of the −/+ buttons and keys. */
        const val OFFSET_STEP_MS = 250

        /** internetradio's station ids ("radio:<stationuuid>"): no lyrics, no offsets. */
        private const val STATION_PREFIX = "radio:"

        internal fun isStationId(id: String) = id.length > STATION_PREFIX.length && id.startsWith(STATION_PREFIX)
    }
}
