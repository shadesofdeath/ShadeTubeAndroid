package com.app.shadetube.playback

import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AudioQuality
import com.app.shadetube.youtube.Match
import com.app.shadetube.youtube.MatchFailure
import com.app.shadetube.youtube.Resolved
import com.app.shadetube.youtube.ResolvedStream
import java.io.File

/*
 * The small interfaces the player integrates other packages through (AppGraph wires the implementations; adapters for
 * the packages that are already stable live in PlaybackAdapters.kt).
 */

/** A file the player plays from disk instead of YouTube: a download, a local-library file or a downloaded episode. */
data class LocalSource(
    /** file:// or content:// */
    val uri: String,
    /** "" = sniff (see [Player.localMimeType]). */
    val mimeType: String = "",
    /** The file when it is one (ReplayGain is read from it); null for a content Uri. */
    val file: File? = null,
) {
    companion object {
        /** The engine opens [file] itself (Uri.fromFile encodes '#', '%' ...); [uri] is for identity / logs. */
        fun of(file: File) = LocalSource("file://" + file.absolutePath, Player.localMimeType(file.name), file)
    }
}

/**
 * Offline playback (the C++ localFileFor + localFileResolvers): asked (main thread) before each resolve and for the
 * prefetch; the first resolver that knows the track wins. May do a quick file check; nothing slower.
 */
fun interface LocalSourceResolver {
    fun localSourceFor(trackId: String): LocalSource?
}

/** YouTube matching + streams (youtube.MatchService; see [MatchServiceResolver]). Main-safe suspend functions. */
interface TrackStreamResolver {
    suspend fun resolve(track: Track, allowWebm: Boolean, lowQuality: Boolean): Result<Resolved>
    suspend fun stream(videoId: String, allowWebm: Boolean, lowQuality: Boolean, bypassCache: Boolean): Result<ResolvedStream>
    suspend fun cachedMatch(trackId: String): Match?
    suspend fun pin(trackId: String, match: Match)
    fun failureOf(t: Throwable): MatchFailure
}

/** A podcast episode's own media (port of Player::DirectStream). */
data class DirectStream(
    val url: String,
    val mimeType: String = "",
    val contentLength: Long = 0,
    val allowLocalNetwork: Boolean = false,
)

/**
 * Direct media (podcast episodes): asked after the live and local lookups. A handled item plays from its URL —
 * seekable, with a duration — instead of a YouTube match: no prefetch / gapless handoff and no match to change.
 * [resolve] runs first (redirects, length) and may throw to fail the item; a network error during playback resolves
 * it again (twice) before the item fails.
 */
interface DirectStreamProvider {
    fun handles(track: Track): Boolean
    suspend fun resolve(track: Track): DirectStream
}

/** Where a station's audio is (internetradio.LiveStreamSource). */
data class LiveStream(
    val url: String,
    val isHls: Boolean = false,
    val mimeType: String = "",
    /** Tests with a local server; stations reach only the public internet. */
    val allowLocalNetwork: Boolean = false,
)

/**
 * Internet radio (the C++ liveStreamFor): a handled item is a live, endless stream: no duration, no seeking, no
 * prefetch / gapless handoff / crossfade. [resolve] is asked again with the next [attempt] after a failure (a
 * playlist's next entry).
 */
interface LiveStreamProvider {
    fun isLive(track: Track): Boolean
    suspend fun resolve(track: Track, attempt: Int): LiveStream

    /** A raw ICY metadata block -> its StreamTitle, if any. */
    fun titleFromIcy(raw: ByteArray): String?
}

/**
 * Resume: asked when an item starts from its beginning (not a seek, re-resolve or restored position); a position > 0
 * starts it there (podcast episodes continue where they were left).
 */
fun interface StartPositionProvider {
    fun startPositionFor(track: Track): Long
}

/** ReplayGain track gain of a local file (localfiles.ReplayGain); blocking (worker threads only); null = none. */
fun interface ReplayGainReader {
    fun trackGainDb(source: LocalSource): Float?
}

/** Settings.meteredQuality on a metered network, else Settings.quality (see [NetworkQuality]). */
fun interface QualityProvider {
    fun quality(): AudioQuality
}

/** Makes sure the media session service runs before playback starts (see PlaybackServiceConnector). */
fun interface ServiceLink {
    fun ensureStarted()
}
