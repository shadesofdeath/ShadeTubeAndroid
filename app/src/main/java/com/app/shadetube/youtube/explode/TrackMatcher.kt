package com.app.shadetube.youtube.explode

import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max

/** A track described by an external catalog (e.g. Spotify): what the user sees on screen. */
data class TrackQuery(
    /** Track title as shown by the catalog, e.g. "Around the World - Radio Edit". */
    val title: String,
    /** Artist names; the first one is treated as the primary artist. */
    val artists: List<String> = emptyList(),
    /** Track duration in ms. Strongly recommended: it is the most reliable matching signal. */
    val duration: Long? = null,
    /** Album name (optional, only used as a weak signal). */
    val album: String = "",
)

/** A YouTube video scored against a [TrackQuery]. */
data class TrackCandidate(
    val video: VideoSearchResult,
    /** Match score (higher is better). Roughly 0..100; anything above ~60 is a confident match. */
    val score: Double = 0.0,
    /** Human-readable explanation of the score, useful for debugging and "why this video?" UI. */
    val reasons: List<String> = emptyList(),
)

/** Result of a match: the best candidate plus the other candidates (best first). */
data class TrackMatch(val best: TrackCandidate, val alternatives: List<TrackCandidate>)

/** A matched video together with its playable audio stream. */
data class ResolvedTrack(
    val match: TrackCandidate,
    /**
     * Best audio-only stream; falls back to a muxed stream (audio + video, e.g. itag 18) when YouTube offers no
     * audio-only stream for the video. Either can be played as audio.
     */
    val audio: AudioStreamInfo,
    /** Remaining candidates, best first (excludes [match]). */
    val alternatives: List<TrackCandidate>,
)

data class TrackMatcherOptions(
    /** Number of search results to consider. */
    val maxSearchResults: Int = 15,
    /** Candidates scoring below this are not considered a match. */
    val minimumScore: Double = 40.0,
    /** Run a second, differently phrased search when the first one yields no confident match. */
    val enableFallbackSearch: Boolean = true,
    /** How many candidates resolve() tries before giving up (unplayable / region-locked videos). */
    val maxResolveAttempts: Int = 3,
)

/**
 * Finds the YouTube video that corresponds to a catalog track (the "Spotube" approach): search YouTube, then rank
 * results by title/artist similarity, duration difference, channel type (official "Artist - Topic" / VEVO uploads) and
 * unwanted variants (live, cover, remix, sped up, 1 hour loop, ...) that are not part of the requested track.
 *
 * Cache "catalog track id -> match.video.id" on your side: it makes subsequent plays cost a single request
 * (getManifest) and keeps you well below YouTube's rate limits. Port of Music::TrackMatcher.
 */
class TrackMatcher(private val youtube: YoutubeClient?, private val options: TrackMatcherOptions = TrackMatcherOptions()) {

    /** Searches YouTube and returns the best match, or null if no candidate is good enough. */
    suspend fun find(query: TrackQuery): TrackMatch? {
        val yt = requireNotNull(youtube) { "TrackMatcher without a client can only rank()" }
        val results = ArrayList(yt.search.getVideos(buildQuery(query), options.maxSearchResults))
        var ranked = rank(query, results)

        if (options.enableFallbackSearch && (ranked.isEmpty() || ranked[0].score < options.minimumScore)) {
            var alternative = coreTitle(query.title)
            for (artist in query.artists) alternative += " $artist"
            alternative += " audio"
            results.addAll(yt.search.getVideos(alternative, options.maxSearchResults))
            ranked = rank(query, results)
        }

        if (ranked.isEmpty() || ranked[0].score < options.minimumScore) return null
        return TrackMatch(ranked[0], ranked.drop(1))
    }

    /**
     * Like [find], but also resolves the audio stream. If the best video turns out to be unplayable, the next
     * candidates are tried. [container] restricts the audio container ("webm" = opus, "mp4" = AAC); empty = highest
     * bitrate of any container.
     */
    suspend fun resolve(query: TrackQuery, container: String = ""): ResolvedTrack? {
        val yt = requireNotNull(youtube) { "TrackMatcher without a client can only rank()" }
        val match = find(query) ?: return null
        val candidates = listOf(match.best) + match.alternatives

        var attempts = 0
        for ((i, candidate) in candidates.withIndex()) {
            if (attempts >= options.maxResolveAttempts) break
            if (candidate.score < options.minimumScore) break
            ++attempts
            try {
                val manifest = yt.videos.streams.getManifest(candidate.video.id)
                var audio: AudioStreamInfo? = manifest.tryGetBestAudioOnlyStream(container)
                if (audio == null && container.isNotEmpty()) audio = manifest.tryGetBestAudioOnlyStream()
                if (audio == null) audio = manifest.muxedStreams.withHighestBitrate()
                if (audio == null) continue
                return ResolvedTrack(candidate, audio, candidates.filterIndexed { j, _ -> j != i })
            } catch (e: CancellationException) {
                throw e
            } catch (e: RequestLimitExceededException) {
                throw e // retrying other candidates would only make it worse
            } catch (e: YoutubeExplodeException) {
                // Region-locked, removed, age-restricted, cipher/player problems, a flaky request...: try the next one.
            }
        }
        return null
    }

    /**
     * Scores and sorts arbitrary search results (no network access). Exposed for testing and for re-ranking results
     * from another source (the Piped / Invidious backup).
     */
    fun rank(query: TrackQuery, results: List<VideoSearchResult>): List<TrackCandidate> {
        val core = coreTitle(query.title)
        val coreTokens = tokens(core)
        val fullQueryText = normalize(query.title + " " + query.album)
        val primaryArtist = query.artists.firstOrNull() ?: ""
        val primaryArtistTokens = tokens(primaryArtist)
        val primaryArtistCompact = compact(primaryArtist)

        val candidates = ArrayList<TrackCandidate>()
        val seen = HashSet<String>()
        var index = 0

        for (video in results) {
            if (!seen.add(video.id.value)) continue
            var score = 0.0
            val reasons = ArrayList<String>()
            val titleText = normalize(video.title)
            val channelText = normalize(video.author.channelTitle)
            val titleAndChannel = "$titleText $channelText"
            fun note(reason: String, delta: Double) {
                score += delta
                reasons.add(reason + String.format(Locale.US, " (%+.0f)", delta))
            }

            // Title similarity: share of the catalog title's words present in the video title.
            if (coreTokens.isNotEmpty()) {
                val matched = coreTokens.count { containsPhrase(titleText, it) }
                val ratio = matched.toDouble() / coreTokens.size.toDouble()
                note("title ${(ratio * 100).toInt()}%", 30.0 * ratio)
                // Same artist and length but another song is the most dangerous false positive.
                if (ratio < 0.5) note("title mismatch", -40.0)
            }

            // Primary artist in the title or channel name ("Daft Punk", "Daft Punk - Topic", "DaftPunkVEVO").
            var artistMatched = false
            if (primaryArtistTokens.isNotEmpty()) {
                val matched = primaryArtistTokens.count { containsPhrase(titleAndChannel, it) }
                var ratio = matched.toDouble() / primaryArtistTokens.size.toDouble()
                if (primaryArtistCompact.isNotEmpty() && compact(video.author.channelTitle).contains(primaryArtistCompact))
                    ratio = 1.0
                artistMatched = ratio >= 0.99
                note("artist ${(ratio * 100).toInt()}%", 25.0 * ratio)
            }
            // Featured artists: small bonus each.
            var featured = 0.0
            var i = 1
            while (i < query.artists.size && featured < 6) {
                val artist = normalize(query.artists[i])
                if (artist.isNotEmpty() && containsPhrase(titleAndChannel, artist)) featured += 3
                ++i
            }
            if (featured > 0) note("featured artists", featured)

            // Duration: the strongest signal to reject music videos with intros, loops and compilations.
            val videoDuration = video.duration
            if (videoDuration == null) {
                note("no duration (live stream)", -40.0)
            } else if (query.duration != null) {
                val diff = abs((videoDuration - query.duration) / 1000.0)
                val s = when {
                    diff <= 3 -> 1.0
                    diff <= 10 -> 1.0 - (diff - 3) / 14.0 // 1.0 .. 0.5
                    diff <= 30 -> 0.5 * (30 - diff) / 20.0 // 0.5 .. 0
                    else -> 0.0
                }
                note("duration diff ${diff.toInt()}s", 30.0 * s)
                if (diff > 30) note("duration far off", -25.0)
            } else {
                note("duration unknown", 15.0)
            }

            // Channel and title hints.
            val channelLower = Str.toLower(video.author.channelTitle)
            if (artistMatched && channelLower.endsWith(" - topic")) note("official Topic channel", 12.0)
            else if (artistMatched && channelLower.contains("vevo")) note("VEVO channel", 6.0)
            else if (artistMatched && primaryArtistCompact.isNotEmpty() && compact(video.author.channelTitle) == primaryArtistCompact)
                note("artist's own channel", 8.0)
            if (containsPhrase(titleText, "official audio")) note("official audio", 8.0)
            else if (containsPhrase(titleText, "audio")) note("audio", 3.0)
            if (containsPhrase(titleText, "official video") || containsPhrase(titleText, "official music video"))
                note("official video", 3.0)
            if (containsPhrase(titleText, "lyrics") || containsPhrase(titleText, "lyric video")) note("lyrics", 2.0)

            // Unwanted variants not requested by the catalog title.
            var unwanted = 0
            for (phrase in UNWANTED_PHRASES) {
                if (containsPhrase(titleText, phrase) && !containsPhrase(fullQueryText, phrase)) {
                    note("unwanted '$phrase'", if (unwanted == 0) -25.0 else -10.0)
                    ++unwanted
                }
            }

            // YouTube's own ranking is a weak signal too.
            val rankBonus = max(0.0, 4.0 - 0.5 * index)
            if (rankBonus > 0) note("search rank #${index + 1}", rankBonus)

            ++index
            candidates.add(TrackCandidate(video, score, reasons))
        }

        // Stable sort, best first.
        return candidates.sortedByDescending { it.score }
    }

    companion object {
        /** Search query used for a track, e.g. "Daft Punk - Around the World". */
        fun buildQuery(query: TrackQuery): String {
            val title = coreTitle(query.title)
            val first = query.artists.firstOrNull()
            if (first == null || Str.trim(first).isEmpty()) return title
            return Str.trim(first) + " - " + title
        }

        /**
         * Lower-cases ASCII, turns ASCII punctuation into spaces and collapses whitespace. Non-ASCII characters
         * (ç, ş, é, 日) are kept as part of words.
         */
        internal fun normalize(s: String): String {
            val out = StringBuilder(s.length)
            var space = true
            for (c in s) {
                val word = c.code >= 0x80 || Str.isAsciiAlnum(c)
                if (word) {
                    out.append(if (c in 'A'..'Z') c + 32 else c)
                    space = false
                } else if (!space) {
                    out.append(' ')
                    space = true
                }
            }
            if (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
            return out.toString()
        }

        private fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

        private fun compact(s: String) = normalize(s).replace(" ", "")

        /** Whether [phrase] (normalized) occurs as whole words in [text] (normalized). */
        private fun containsPhrase(text: String, phrase: String) = " $text ".contains(" $phrase ")

        /** Title without decorations: "Around the World (feat. X) [Remastered] - Radio Edit" -> "Around the World". */
        fun coreTitle(title: String): String {
            val out = StringBuilder()
            var depth = 0
            for (c in title) {
                if (c == '(' || c == '[') { ++depth; continue }
                if (c == ')' || c == ']') { if (depth > 0) --depth; continue }
                if (depth == 0) out.append(c)
            }
            var s = out.toString()
            // " - Remastered 2011", " - Radio Edit", " - From \"Movie\""
            val dash = s.indexOf(" - ")
            if (dash > 0) s = s.substring(0, dash)
            // "Song feat. Someone" / "Song ft. Someone"
            val lower = Str.toLower(s)
            for (marker in listOf(" feat. ", " feat ", " ft. ", " ft ", " featuring ")) {
                val pos = lower.indexOf(marker)
                if (pos >= 0) {
                    s = s.substring(0, pos)
                    break
                }
            }
            val trimmed = Str.trim(s)
            return trimmed.ifEmpty { Str.trim(title) }
        }

        /**
         * Words/phrases that indicate a different version than the catalog track, unless the catalog title itself
         * contains them (e.g. a Spotify track that *is* a live recording or a remix).
         */
        private val UNWANTED_PHRASES = listOf(
            "live", "live at", "concert", "cover", "remix", "karaoke", "instrumental", "sped up", "speed up",
            "slowed", "reverb", "8d", "8d audio", "nightcore", "acoustic", "reaction", "tutorial", "lesson",
            "1 hour", "10 hours", "1 saat", "loop", "bass boosted", "extended", "mashup", "piano version",
            "drum cover", "guitar cover", "parody", "teaser", "trailer", "behind the scenes", "fan made",
            "shorts", "edit audio", "mix", "megamix", "medley",
        )
    }
}
