package com.app.shadetube.downloads

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.pick
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.AudioQuality
import com.app.shadetube.core.Files
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.Log
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.dump
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.long
import com.app.shadetube.core.int
import com.app.shadetube.core.str
import com.app.shadetube.lyrics.LyricsService
import com.app.shadetube.youtube.MatchFailure
import com.app.shadetube.youtube.MatchService
import com.app.shadetube.youtube.Resolved
import com.app.shadetube.youtube.matchFailureOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Why a download failed (persisted as "ek" in downloads.json; the UI maps it to text). */
enum class DownloadFailure {
    /** No YouTube video matched the track. */
    NoMatch,
    /** The video is unavailable / unplayable / age restricted / paid. */
    Unavailable,
    /** YouTube rate-limited us. */
    RateLimited,
    /** Transfer failed (also an expired stream URL that re-resolving did not fix). */
    Network,
    /** The file could not be written. */
    Io,
    Other,
}

/** Container the file was saved in: MP3 (transcoded, `downloadMp3Kbps` > 0) or, passthrough, whatever YouTube served. */
enum class DownloadFormat(val ext: String) { M4a("m4a"), Webm("webm"), Mp3("mp3") }

sealed interface DownloadOutcome {
    data class Ok(
        val file: File,
        val sizeBytes: Long,
        val format: DownloadFormat,
        /** SponsorBlock segments removed from the file (0 = none / not trimmed). */
        val cutSegments: Int = 0,
        /** Audio removed by those cuts, ms. */
        val cutMs: Long = 0,
        /** Tags were written into the file (MP3: ID3v2.3; m4a: MP4 tags; false for .webm or when tagging failed). */
        val tagged: Boolean = false,
    ) : DownloadOutcome

    data class Failed(val failure: DownloadFailure, val detail: String) : DownloadOutcome
}

/**
 * Downloads one track (the worker half of C++ DownloadManager::startNext + audio::downloadTrack). [targetFor] gives the
 * final path for a container (the queue decides the folder layout); [progress] gets 0..1. Cancellation is coroutine
 * cancellation (CancellationException propagates; partial files are removed).
 */
fun interface TrackDownloader {
    suspend fun download(track: Track, targetFor: (DownloadFormat) -> File, progress: (Float) -> Unit): DownloadOutcome
}

/**
 * SponsorBlock segments of the matched video to cut out of a download, as [startMs, endMs) ranges (k-anonymity
 * hash-prefix lookup, normalized to the video's duration). The playback package owns SponsorBlock; AppGraph wires it.
 * A failed lookup returns an empty list (the download is just not trimmed).
 */
fun interface SponsorCutSource {
    suspend fun cuts(videoId: String, durationMs: Long): List<Pair<Long, Long>>
}

/**
 * The production [TrackDownloader] (C++ DownloadManager::startNext + audio::downloadTrack). Two modes, like the C++:
 *
 *  - `Settings.downloadMp3Kbps` > 0 (default 320): MatchService stream (best bitrate, Opus or AAC), throttle-safe
 *    ranged download ([StreamFetcher]) into `<target>.src.part` (resumed after an interruption), then decode
 *    ([MediaCodecPcmSource]) → SponsorBlock cuts, sample-accurate with 8 ms raised-cosine fades ([PcmSplicer]) → CBR MP3
 *    ([Mp3Encoder], jump3r) behind an ID3v2.3 tag ([Id3Writer]: title / artist / album / album artist / track / cover /
 *    USLT + SYLT lyrics / ReplayGain) into `<target>.part` ([Mp3Transcoder]), renamed to the `.mp3` when complete.
 *  - 0 = passthrough: the original stream (AAC preferred) into `<target>.part`, optional SponsorBlock trimming
 *    ([Mp4Trimmer], frame-accurate), MP4 tags ([Mp4Tags]). A WebM/Opus-only source is saved as `.webm` without tags or
 *    trimming.
 *
 * Both write the synced `.lrc` sidecar ([LyricsService.writeLyricsSidecar]).
 */
class YoutubeTrackDownloader(
    private val matcher: MatchService,
    private val lyrics: LyricsService?,
    private val sponsor: SponsorCutSource?,
    private val fetcher: StreamFetcher = StreamFetcher(),
) : TrackDownloader {

    override suspend fun download(track: Track, targetFor: (DownloadFormat) -> File, progress: (Float) -> Unit): DownloadOutcome {
        val settings = SettingsStore.current
        val trim = settings.sponsorBlockEnabled && sponsor != null
        val lowQ = settings.quality == AudioQuality.Normal
        val kbps = Mp3Encoder.supportedKbps(settings.downloadMp3Kbps)
        val mp3 = kbps > 0
        // Lyrics in downloads (null: off). Asked before the transfer like the C++ (3.15).
        val lyricsQuery = lyrics?.downloadQuery(track)

        // MP3: any codec decodes, so the best stream (C++: allowWebm = mp3). Passthrough needs AAC for tags / trimming
        // (the MatchService still falls back to what exists).
        var resolved = matcher.resolve(track, allowWebm = mp3, lowQuality = lowQ).getOrElse { e ->
            if (e is CancellationException) throw e
            return failureOf(e)
        }
        val format = when {
            mp3 -> DownloadFormat.Mp3
            isWebm(resolved) -> DownloadFormat.Webm
            else -> DownloadFormat.M4a
        }
        val target = targetFor(format)
        target.parentFile?.mkdirs()
        // The stream's bytes (MP3: the transcode's input; passthrough: the file itself) and what they belong to.
        val part = File(target.path + if (mp3) SOURCE_PART else ".part")
        val partInfo = File(part.path + ".json")
        // MP3: the transcode's output, renamed to the target when complete.
        val mp3Part = File(target.path + ".part")

        try {
            // Cover (album image >= 300 px), only where it can be stored.
            val cover = if (format != DownloadFormat.Webm) fetchCover(track) else null
            currentCoroutineContext().ensureActive()
            val lyr = if (lyricsQuery != null) lyrics?.lyricsForDownload(lyricsQuery) else null
            currentCoroutineContext().ensureActive()

            // Transfer, resuming a partial file of the same stream (process killed mid-download).
            val transferShare = if (mp3) MP3_DOWNLOAD_SHARE else 0.97f
            var expiredRetries = 0
            while (true) {
                val s = resolved.stream
                val resumeFrom = resumeOffset(part, partInfo, s.videoId, s.itag, s.contentLength)
                Files.writeAtomic(partInfo, jsonOf("v" to s.videoId, "itag" to s.itag, "len" to s.contentLength).dump())
                try {
                    fetcher.fetch(
                        url = s.url,
                        headers = s.requestHeaders,
                        knownLength = s.contentLength,
                        out = part,
                        resumeFrom = resumeFrom,
                        googleVideo = s.isGoogleVideo,
                        progress = { done, total -> if (total > 0) progress(transferShare * done / total) },
                    )
                    break
                } catch (e: StreamFetcher.ExpiredException) {
                    // The signed URL expired (a long queue, a paused download): resolve the stream again and resume.
                    if (++expiredRetries > 2) throw e
                    Log.w(TAG, "stream of ${s.videoId} expired (${e.status}); resolving again")
                    matcher.forgetStream(s.videoId)
                    resolved = resolve(track, lowQ, allowWebm = mp3) ?: throw e
                    if (!mp3 && isWebm(resolved) != (format == DownloadFormat.Webm)) throw e
                }
            }
            currentCoroutineContext().ensureActive()

            var cutSegments = 0
            var cutMs = 0L
            var tagged = false
            if (mp3) {
                // SponsorBlock: cut from the decoded PCM (sample-accurate). A failed lookup never fails the download.
                val cuts = if (trim) sponsorCuts(resolved) else emptyList()
                val tag = Id3Writer.build(id3TagsFor(track, cover, lyr?.text.orEmpty(), lyr?.synced.orEmpty(), resolved.stream.loudnessDb))
                val ctx = currentCoroutineContext()
                val stats = try {
                    withContext(AppDispatchers.cpu) {
                        MediaCodecPcmSource(part).use { src ->
                            Mp3Transcoder.transcode(
                                source = src,
                                out = mp3Part,
                                kbps = kbps,
                                cutsMs = cuts,
                                id3Tag = tag,
                                durationHintMs = resolved.match.durationSec * 1000L,
                                progress = { f -> progress(MP3_DOWNLOAD_SHARE + (0.99f - MP3_DOWNLOAD_SHARE) * f) },
                                cancelled = { !ctx.isActive },
                            )
                        }
                    }
                } catch (e: TranscodeException) {
                    // A source that doesn't decode is bad data: the retry downloads it again.
                    mp3Part.delete()
                    if (e.kind == TranscodeException.Kind.Decode) {
                        part.delete()
                        partInfo.delete()
                    }
                    Log.w(TAG, "transcode of \"${track.name}\" failed: ${e.message}")
                    return DownloadOutcome.Failed(DownloadFailure.Other, "${e.kind.name.lowercase()}: ${e.message}")
                }
                currentCoroutineContext().ensureActive()
                cutSegments = stats.segmentsCut
                cutMs = stats.cutMs
                tagged = tag.isNotEmpty()
                if (cutSegments > 0) {
                    Log.i(TAG, "cut $cutSegments segment(s): $cutMs ms removed, ${stats.sourceMs} ms -> ${stats.outputMs} ms")
                }
                if (target.exists()) target.delete()
                if (!mp3Part.renameTo(target)) throw IOException("could not move the download to ${target.path}")
                part.delete()
                partInfo.delete()
            } else {
                if (format == DownloadFormat.M4a) {
                    // SponsorBlock: remux without the segments (frame-accurate, see Mp4Trimmer). Never fails the download.
                    val cuts = if (trim) sponsorCuts(resolved) else emptyList()
                    if (cuts.isNotEmpty()) {
                        val trimmed = File(target.path + ".cut.part")
                        val ctx = currentCoroutineContext()
                        val stats = Mp4Trimmer.trim(part, trimmed, cuts) { !ctx.isActive }
                        currentCoroutineContext().ensureActive()
                        if (stats != null && trimmed.length() > 0) {
                            part.delete()
                            if (trimmed.renameTo(part)) {
                                cutSegments = stats.segmentsCut
                                cutMs = stats.cutMs
                            } else {
                                throw IOException("could not replace the download with its trimmed copy")
                            }
                        }
                        trimmed.delete()
                    }
                    progress(0.98f)
                    tagged = try {
                        Mp4Tags.write(part, tagsFor(track, cover, lyr?.text.orEmpty(), resolved.stream.loudnessDb))
                        true
                    } catch (e: IOException) {
                        Log.w(TAG, "could not tag ${target.name}: ${e.message}")
                        false
                    }
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("could not move the download to ${target.path}")
                partInfo.delete()
            }

            if (lyr != null) lyrics?.writeLyricsSidecar(target, lyr.lyrics, track)
            progress(1f)
            return DownloadOutcome.Ok(target, target.length(), format, cutSegments, cutMs, tagged)
        } catch (e: CancellationException) {
            part.delete()
            partInfo.delete()
            if (mp3) mp3Part.delete()
            File(target.path + ".cut.part").delete()
            throw e
        } catch (e: StreamFetcher.ExpiredException) {
            return DownloadOutcome.Failed(DownloadFailure.Network, e.message ?: "expired")
        } catch (e: IOException) {
            // Keep the partial source: the retry resumes it (a half-written MP3 is redone from it).
            if (mp3) mp3Part.delete()
            val io = e is java.io.FileNotFoundException || e.message?.startsWith("could not") == true || e.message?.contains("ENOSPC") == true
            return DownloadOutcome.Failed(if (io) DownloadFailure.Io else DownloadFailure.Network, e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            if (mp3) mp3Part.delete()
            return DownloadOutcome.Failed(DownloadFailure.Other, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun isWebm(r: Resolved) = r.stream.mimeType.contains("webm")

    /** The SponsorBlock segments of the matched video (empty: none / lookup failed — the download is just not cut). */
    private suspend fun sponsorCuts(resolved: Resolved): List<Pair<Long, Long>> {
        val videoId = resolved.match.videoId
        if (videoId.isEmpty()) return emptyList()
        val cuts = try {
            sponsor!!.cuts(videoId, resolved.match.durationSec * 1000L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sponsorblock lookup failed, downloading untrimmed: ${e.message}")
            emptyList()
        }
        if (cuts.isNotEmpty()) Log.i(TAG, "sponsorblock: ${cuts.size} segment(s) to cut from $videoId")
        return cuts
    }

    private suspend fun resolve(track: Track, lowQ: Boolean, allowWebm: Boolean): Resolved? =
        matcher.resolve(track, allowWebm = allowWebm, lowQuality = lowQ).getOrNull()

    private fun failureOf(err: Throwable): DownloadOutcome.Failed {
        val failure = when (matchFailureOf(err)) {
            MatchFailure.NoMatch -> DownloadFailure.NoMatch
            MatchFailure.Unavailable, MatchFailure.AgeRestricted, MatchFailure.RequiresPurchase, MatchFailure.Unplayable -> DownloadFailure.Unavailable
            MatchFailure.RateLimited -> DownloadFailure.RateLimited
            MatchFailure.Network -> DownloadFailure.Network
            else -> DownloadFailure.Other
        }
        return DownloadOutcome.Failed(failure, err.message ?: err.javaClass.simpleName)
    }

    private suspend fun fetchCover(track: Track): ByteArray? {
        val img = track.album.images.pick(300) ?: return null
        return try {
            val resp = Http.await(HttpRequest(img.url))
            if (resp.isSuccess && resp.bytes.isNotEmpty() && Mp4Tags.imageType(resp.bytes) != 0) resp.bytes else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "downloads"

        /** MP3 mode: the transfer's share of the progress bar (the transcode takes the rest up to 0.99). */
        const val MP3_DOWNLOAD_SHARE = 0.4f

        /** MP3 mode: suffix of the downloaded source stream next to the target (`<name>.mp3.src.part`). */
        const val SOURCE_PART = ".src.part"

        /** The ID3 tags of an MP3 download (same values as [tagsFor], plus the synced lyrics). */
        fun id3TagsFor(track: Track, cover: ByteArray?, lyricsText: String, synced: List<Pair<Long, String>>, loudnessDb: Double?) =
            Id3Writer.Tags(
                title = track.name,
                artist = track.artistLine,
                album = track.album.name,
                albumArtist = track.artists.firstOrNull()?.name.orEmpty(),
                trackNumber = track.trackNumber,
                cover = cover,
                lyricsText = lyricsText,
                syncedLyrics = synced,
                replayGainDb = loudnessDb?.let { (-4.0 - it).toFloat() },
            )

        /**
         * The MP4 tags of a download. ReplayGain: YouTube's loudness (vs. -14 LUFS) as a ReplayGain 2 track gain
         * (to -18 LUFS): `-4 - loudnessDb`.
         */
        fun tagsFor(track: Track, cover: ByteArray?, lyricsText: String, loudnessDb: Double?) = Mp4Tags.Tags(
            title = track.name,
            artist = track.artistLine,
            album = track.album.name,
            albumArtist = track.artists.firstOrNull()?.name.orEmpty(),
            trackNumber = track.trackNumber,
            cover = cover,
            lyrics = lyricsText,
            replayGainDb = loudnessDb?.let { (-4.0 - it).toFloat() },
        )

        /** Where to resume a partial download: its length when it belongs to the same stream, else 0. */
        fun resumeOffset(part: File, info: File, videoId: String, itag: Int, length: Long): Long {
            if (!part.exists()) return 0
            val j = Files.readJson(info)
            val same = j != null && j.str("v") == videoId && j.int("itag") == itag && j.long("len") == length && length > 0
            return if (same && part.length() < length) part.length() else 0
        }
    }
}
