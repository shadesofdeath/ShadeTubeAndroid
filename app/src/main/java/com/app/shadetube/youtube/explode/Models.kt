package com.app.shadetube.youtube.explode

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

// Public models of the library (ports of YoutubeExplode/include/Common, Videos, Playlists, Channels, Search).
// Durations are milliseconds (the C++ TimeSpan), instants epoch milliseconds (the C++ DateTimeOffset).

/** Reference to a channel that owns a specific YouTube video or playlist. */
data class Author(val channelId: String = "", val channelTitle: String = "") {
    val channelUrl: String get() = "https://www.youtube.com/channel/$channelId"
    val title: String get() = channelTitle
    override fun toString() = channelTitle
}

/** Resolution of an image or a video. */
data class Resolution(val width: Int = 0, val height: Int = 0) {
    val area: Long get() = width.toLong() * height
    override fun toString() = "${width}x$height"
}

data class Thumbnail(val url: String, val resolution: Resolution) {
    override fun toString() = "Thumbnail ($resolution)"

    companion object {
        /** Default set of thumbnails that every video has (img.youtube.com). */
        fun defaultSet(videoId: String) = listOf(
            Thumbnail("https://img.youtube.com/vi/$videoId/default.jpg", Resolution(120, 90)),
            Thumbnail("https://img.youtube.com/vi/$videoId/mqdefault.jpg", Resolution(320, 180)),
            Thumbnail("https://img.youtube.com/vi/$videoId/hqdefault.jpg", Resolution(480, 360)),
        )
    }
}

/** The thumbnail with the highest resolution, or null if the collection is empty. */
fun List<Thumbnail>.withHighestResolution(): Thumbnail? = maxByOrNull { it.resolution.area }

/** Language information. Compared by code, case-insensitively. */
class Language(val code: String, val name: String) {
    override fun equals(other: Any?) = other is Language && Str.iequals(code, other.code)
    override fun hashCode() = Str.toLower(code).hashCode()
    override fun toString() = "$code ($name)"
}

// ---- Videos ----------------------------------------------------------------------------------------------------------

/** Properties shared by video metadata resolved from different sources (Video, PlaylistVideo, VideoSearchResult). */
interface IVideo {
    val id: VideoId
    val url: String
    val title: String
    val author: Author
    /** Milliseconds; null for live streams. */
    val duration: Long?
    val thumbnails: List<Thumbnail>
}

/** User activity statistics. */
data class Engagement(val viewCount: Long = 0, val likeCount: Long = 0, val dislikeCount: Long = 0) {
    /** Average rating on a 1..5 scale (0 when unavailable). */
    val averageRating: Double
        get() {
            val total = likeCount + dislikeCount
            return if (total != 0L) 1.0 + 4.0 * likeCount.toDouble() / total.toDouble() else 0.0
        }
}

/** Metadata associated with a YouTube video. */
data class Video(
    override val id: VideoId,
    override val title: String,
    override val author: Author,
    /** Epoch milliseconds. */
    val uploadDate: Long,
    val description: String,
    override val duration: Long?,
    override val thumbnails: List<Thumbnail>,
    val keywords: List<String>,
    val engagement: Engagement,
    /** True if the video is a live stream (currently live or upcoming). */
    val isLive: Boolean = false,
) : IVideo {
    override val url: String get() = "https://www.youtube.com/watch?v=${id.value}"
    override fun toString() = "Video ($title)"
}

// ---- Playlists -------------------------------------------------------------------------------------------------------

interface IPlaylist {
    val id: PlaylistId
    val url: String
    val title: String
    /** Null for system playlists (e.g. mixes, "Liked videos"). */
    val author: Author?
    val thumbnails: List<Thumbnail>
}

data class Playlist(
    override val id: PlaylistId,
    override val title: String,
    override val author: Author?,
    val description: String,
    /** Total number of videos (may be unavailable for some system playlists). */
    val count: Int?,
    override val thumbnails: List<Thumbnail>,
) : IPlaylist {
    override val url: String get() = "https://www.youtube.com/playlist?list=${id.value}"
    override fun toString() = "Playlist ($title)"
}

/** Metadata associated with a YouTube video included in a playlist. */
data class PlaylistVideo(
    val playlistId: PlaylistId,
    override val id: VideoId,
    override val title: String,
    override val author: Author,
    override val duration: Long?,
    override val thumbnails: List<Thumbnail>,
) : IVideo {
    override val url: String get() = "https://www.youtube.com/watch?v=${id.value}&list=${playlistId.value}"
    override fun toString() = "Video ($title)"
}

// ---- Channels --------------------------------------------------------------------------------------------------------

interface IChannel {
    val id: ChannelId
    val url: String
    val title: String
    val thumbnails: List<Thumbnail>
}

data class Channel(override val id: ChannelId, override val title: String, override val thumbnails: List<Thumbnail>) :
    IChannel {
    override val url: String get() = "https://www.youtube.com/channel/${id.value}"
    override fun toString() = "Channel ($title)"
}

// ---- Search ----------------------------------------------------------------------------------------------------------

/** Filter applied to a YouTube search query. */
enum class SearchFilter { None, Video, Playlist, Channel }

/** Abstract result returned by a search query. */
sealed interface SearchResult {
    val url: String
    val title: String
}

data class VideoSearchResult(
    override val id: VideoId,
    override val title: String,
    override val author: Author,
    /** Milliseconds; null for live streams. */
    override val duration: Long?,
    override val thumbnails: List<Thumbnail>,
) : SearchResult, IVideo {
    override val url: String get() = "https://www.youtube.com/watch?v=${id.value}"
    override fun toString() = "Video ($title)"
}

data class PlaylistSearchResult(
    override val id: PlaylistId,
    override val title: String,
    override val author: Author?,
    override val thumbnails: List<Thumbnail>,
) : SearchResult, IPlaylist {
    override val url: String get() = "https://www.youtube.com/playlist?list=${id.value}"
    override fun toString() = "Playlist ($title)"
}

data class ChannelSearchResult(
    override val id: ChannelId,
    override val title: String,
    override val thumbnails: List<Thumbnail>,
) : SearchResult, IChannel {
    override val url: String get() = "https://www.youtube.com/channel/${id.value}"
    override fun toString() = "Channel ($title)"
}

// ---- Streams ---------------------------------------------------------------------------------------------------------

private fun formatScaled(value: Double, unit: String): String {
    // "0.##" formatting: up to two decimals, trailing zeros trimmed.
    var s = String.format(Locale.US, "%.2f", value)
    s = s.trimEnd('0').trimEnd('.')
    return "$s $unit"
}

/** Stream container (file format), e.g. "mp4", "webm", "m4a". Case-insensitive equality. */
class Container(val name: String) {
    /** Whether this container is a known audio-only format. YouTube serves audio in "mp4" and "webm" too. */
    val isAudioOnly: Boolean
        get() = listOf("mp3", "m4a", "wav", "wma", "ogg", "aac", "opus").any { Str.iequals(name, it) }

    override fun equals(other: Any?) = other is Container && Str.iequals(name, other.name)
    override fun hashCode() = Str.toLower(name).hashCode()
    override fun toString() = name

    companion object {
        val Mp3 = Container("mp3")
        val Mp4 = Container("mp4")
        val WebM = Container("webm")
        val Tgpp = Container("3gpp")
    }
}

/** Encoded bitrate of a stream (bits per second). */
@JvmInline
value class Bitrate(val bitsPerSecond: Long) : Comparable<Bitrate> {
    val kiloBitsPerSecond: Double get() = bitsPerSecond / 1024.0
    val megaBitsPerSecond: Double get() = kiloBitsPerSecond / 1024.0
    val gigaBitsPerSecond: Double get() = megaBitsPerSecond / 1024.0
    override fun compareTo(other: Bitrate) = bitsPerSecond.compareTo(other.bitsPerSecond)

    /** E.g. "128.5 Kbit/s". */
    override fun toString(): String = when {
        abs(gigaBitsPerSecond) >= 1 -> formatScaled(gigaBitsPerSecond, "Gbit/s")
        abs(megaBitsPerSecond) >= 1 -> formatScaled(megaBitsPerSecond, "Mbit/s")
        abs(kiloBitsPerSecond) >= 1 -> formatScaled(kiloBitsPerSecond, "Kbit/s")
        else -> formatScaled(bitsPerSecond.toDouble(), "Bit/s")
    }
}

/** File size of a stream. */
@JvmInline
value class FileSize(val bytes: Long) : Comparable<FileSize> {
    val kiloBytes: Double get() = bytes / 1024.0
    val megaBytes: Double get() = kiloBytes / 1024.0
    val gigaBytes: Double get() = megaBytes / 1024.0
    override fun compareTo(other: FileSize) = bytes.compareTo(other.bytes)

    /** E.g. "3.42 MB". */
    override fun toString(): String = when {
        abs(gigaBytes) >= 1 -> formatScaled(gigaBytes, "GB")
        abs(megaBytes) >= 1 -> formatScaled(megaBytes, "MB")
        abs(kiloBytes) >= 1 -> formatScaled(kiloBytes, "KB")
        else -> formatScaled(bytes.toDouble(), "B")
    }
}

/** Video quality, e.g. "1080p60". */
class VideoQuality(val label: String, val maxHeight: Int, val framerate: Int) : Comparable<VideoQuality> {
    constructor(maxHeight: Int, framerate: Int) : this(formatLabel(maxHeight, framerate), maxHeight, framerate)

    val isHighDefinition: Boolean get() = maxHeight >= 1080

    /** Typical resolution for this quality (used when the stream does not report one). */
    val defaultVideoResolution: Resolution
        get() = when (maxHeight) {
            144 -> Resolution(256, 144)
            240 -> Resolution(426, 240)
            360 -> Resolution(640, 360)
            480 -> Resolution(854, 480)
            720 -> Resolution(1280, 720)
            1080 -> Resolution(1920, 1080)
            1440 -> Resolution(2560, 1440)
            2160 -> Resolution(3840, 2160)
            2880 -> Resolution(5120, 2880)
            3072 -> Resolution(4096, 3072)
            4320 -> Resolution(7680, 4320)
            else -> Resolution(16 * maxHeight / 9, maxHeight)
        }

    override fun compareTo(other: VideoQuality): Int {
        if (maxHeight != other.maxHeight) return if (maxHeight < other.maxHeight) -1 else 1
        if (framerate != other.framerate) return if (framerate < other.framerate) -1 else 1
        return Str.toLower(label).compareTo(Str.toLower(other.label)).coerceIn(-1, 1)
    }

    override fun equals(other: Any?) = other is VideoQuality && compareTo(other) == 0
    override fun hashCode() = maxHeight * 31 + framerate
    override fun toString() = label

    companion object {
        // Framerate appears only if it's above 30; YouTube rounds it up to the nearest 10.
        private fun formatLabel(maxHeight: Int, framerate: Int): String {
            if (framerate <= 30) return "${maxHeight}p"
            val rounded = ceil(framerate / 10.0).toInt() * 10
            return "${maxHeight}p$rounded"
        }

        private val labelPattern = Regex("""^(\d+)\D(\d+)?""")

        /** Parses labels such as "1080p", "1080p60", "1080s60", "2160p60 HDR". */
        fun tryFromLabel(label: String, framerateFallback: Int): VideoQuality? {
            val m = labelPattern.find(label) ?: return null
            val height = Str.tryParseInt(m.groupValues[1]) ?: return null
            val framerate = m.groups[2]?.value?.let { Str.tryParseInt(it) }
            return VideoQuality(label, height, framerate ?: framerateFallback)
        }

        /** Resolves quality from a well-known itag. Returns null for unknown itags. */
        fun tryFromItag(itag: Int, framerate: Int): VideoQuality? {
            val h = when (itag) {
                5, 13, 17, 91, 151, 160, 161, 278, 330, 394 -> 144
                6, 36, 92, 132, 133, 142, 242, 331, 395 -> 240
                18, 34, 43, 82, 93, 100, 134, 143, 167, 243, 332, 396 -> 360
                35, 44, 59, 78, 83, 94, 101, 135, 144, 168, 212, 213, 218, 219, 222, 223, 244, 245, 246, 333, 397 -> 480
                22, 45, 84, 95, 102, 136, 145, 169, 214, 215, 224, 225, 247, 298, 302, 334, 398 -> 720
                37, 46, 85, 96, 137, 146, 170, 216, 217, 226, 227, 248, 299, 303, 335, 399 -> 1080
                264, 271, 308, 336 -> 1440
                266, 272, 313, 315, 337 -> 2160
                38 -> 3072
                138 -> 4320
                else -> return null
            }
            return VideoQuality(h, framerate)
        }
    }
}

/** Metadata associated with a media stream of a YouTube video. */
sealed interface StreamInfo {
    /** Directly playable/downloadable URL (signature and n-parameter already resolved). */
    val url: String
    val container: Container
    val size: FileSize
    val bitrate: Bitrate
    /** YouTube format identifier. */
    val itag: Int
    /** Raw MIME type, e.g. `audio/webm; codecs="opus"`. */
    val mimeType: String
    /**
     * Android addition: User-Agent of the innertube client whose player response issued this URL. googlevideo may
     * check it against the client the URL was signed for, so the player should send it when fetching the stream.
     */
    val userAgent: String
}

/** Metadata associated with a media stream that contains audio. */
sealed interface AudioStreamInfo : StreamInfo {
    /** E.g. "opus", "mp4a.40.2". */
    val audioCodec: String
    /** Language of the audio track (multi-language videos only). */
    val audioLanguage: Language?
    /** Whether this is the default audio track (multi-language videos only). */
    val isAudioLanguageDefault: Boolean?
    val audioSampleRate: Int?
    val audioChannels: Int?
    /**
     * Integrated loudness relative to YouTube's reference level in dB (positive = louder than the reference; YouTube's
     * own player turns it down by this much). Null when the response doesn't carry it.
     */
    val loudnessDb: Double?
    /** Whether this is a DRC ("stable volume") variant with compressed dynamics. */
    val isDrc: Boolean
}

/** Metadata associated with a media stream that contains video. */
sealed interface VideoStreamInfo : StreamInfo {
    /** E.g. "avc1.640028", "vp9", "av01.0.08M.08". */
    val videoCodec: String
    val videoQuality: VideoQuality
    val videoResolution: Resolution
    /** Whether the stream was upscaled with YouTube's "Super Resolution" feature. */
    val isVideoUpscaled: Boolean
}

/** Audio-only stream (the typical choice for music playback: opus/webm or mp4a/m4a). */
data class AudioOnlyStreamInfo(
    override val url: String,
    override val itag: Int,
    override val mimeType: String,
    override val container: Container,
    override val size: FileSize,
    override val bitrate: Bitrate,
    override val audioCodec: String,
    override val audioLanguage: Language? = null,
    override val isAudioLanguageDefault: Boolean? = null,
    override val audioSampleRate: Int? = null,
    override val audioChannels: Int? = null,
    override val loudnessDb: Double? = null,
    override val isDrc: Boolean = false,
    override val userAgent: String = "",
) : AudioStreamInfo {
    override fun toString() = "Audio-only ($itag | ${container.name} | $audioCodec | $bitrate)"
}

/** Video-only stream (must be combined with an audio stream for playback with sound). */
data class VideoOnlyStreamInfo(
    override val url: String,
    override val itag: Int,
    override val mimeType: String,
    override val container: Container,
    override val size: FileSize,
    override val bitrate: Bitrate,
    override val videoCodec: String,
    override val videoQuality: VideoQuality,
    override val videoResolution: Resolution,
    override val isVideoUpscaled: Boolean = false,
    override val userAgent: String = "",
) : VideoStreamInfo {
    override fun toString() = "Video-only ($itag | ${videoQuality.label} | ${container.name})"
}

/** Stream that contains both audio and video. */
data class MuxedStreamInfo(
    override val url: String,
    override val itag: Int,
    override val mimeType: String,
    override val container: Container,
    override val size: FileSize,
    override val bitrate: Bitrate,
    override val audioCodec: String,
    override val videoCodec: String,
    override val videoQuality: VideoQuality,
    override val videoResolution: Resolution,
    override val audioLanguage: Language? = null,
    override val isAudioLanguageDefault: Boolean? = null,
    override val audioSampleRate: Int? = null,
    override val audioChannels: Int? = null,
    override val loudnessDb: Double? = null,
    override val isDrc: Boolean = false,
    override val isVideoUpscaled: Boolean = false,
    override val userAgent: String = "",
) : AudioStreamInfo, VideoStreamInfo {
    override fun toString() = "Muxed ($itag | ${videoQuality.label} | ${container.name})"
}

/** Describes media streams available for a YouTube video. */
class StreamManifest(val streams: List<StreamInfo>) {
    /** Streams that contain audio (audio-only and muxed). */
    val audioStreams: List<AudioStreamInfo> get() = streams.filterIsInstance<AudioStreamInfo>()
    /** Streams that contain video (video-only and muxed). */
    val videoStreams: List<VideoStreamInfo> get() = streams.filterIsInstance<VideoStreamInfo>()
    val muxedStreams: List<MuxedStreamInfo> get() = streams.filterIsInstance<MuxedStreamInfo>()
    val audioOnlyStreams: List<AudioOnlyStreamInfo> get() = streams.filterIsInstance<AudioOnlyStreamInfo>()
    val videoOnlyStreams: List<VideoOnlyStreamInfo> get() = streams.filterIsInstance<VideoOnlyStreamInfo>()
    val isEmpty: Boolean get() = streams.isEmpty()

    /**
     * Highest-bitrate audio-only stream, optionally restricted to a container ("webm" = opus, "mp4" = m4a/aac).
     * Prefers the default audio track on multi-language videos.
     */
    fun tryGetBestAudioOnlyStream(container: String = ""): AudioOnlyStreamInfo? {
        var best: AudioOnlyStreamInfo? = null
        // Prefer the default audio track (or tracks without language info) over dubs.
        fun rank(s: AudioOnlyStreamInfo) = if (s.isAudioLanguageDefault ?: true) 1 else 0
        for (s in audioOnlyStreams) {
            if (container.isNotEmpty() && !Str.iequals(s.container.name, container)) continue
            val b = best
            if (b == null || rank(s) > rank(b) || (rank(s) == rank(b) && s.bitrate > b.bitrate)) best = s
        }
        return best
    }
}

/** The stream with the highest bitrate, or null if the collection is empty (first one wins ties). */
fun <T : StreamInfo> List<T>.withHighestBitrate(): T? {
    var best: T? = null
    for (s in this) if (best == null || best.bitrate < s.bitrate) best = s
    return best
}

/** The video stream with the highest video quality, or null if the collection is empty. */
fun <T : VideoStreamInfo> List<T>.withHighestVideoQuality(): T? {
    var best: T? = null
    for (s in this) if (best == null || best.videoQuality < s.videoQuality) best = s
    return best
}
