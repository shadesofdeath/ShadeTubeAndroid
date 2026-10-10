package com.app.shadetube.youtube.explode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Fetches raw video data (watch pages, player responses) from YouTube (port of detail::VideoController).
 *
 * Innertube client profiles. FRAGILE: YouTube regularly starts rejecting specific client names/versions (or requires
 * PO tokens for them). When stream extraction starts failing with "unplayable" errors for every video, updating these
 * profiles is usually the fix. The values below match the reference implementation (YoutubeExplode for .NET).
 */
internal open class VideoController(protected val context: ClientContext) {
    /** A player response together with the User-Agent of the client that requested it (its URLs are bound to it). */
    class ClientResponse(val response: PlayerResponse, val userAgent: String)

    suspend fun getVideoWatchPage(videoId: String): VideoWatchPage {
        var retriesRemaining = 5
        while (true) {
            val html = context.getString("https://www.youtube.com/watch?v=$videoId&bpctr=9999999999")
            val page = VideoWatchPage.tryParse(html)
            if (page == null) {
                if (retriesRemaining-- > 0) continue
                throw YoutubeExplodeException("Video watch page is broken. Please try again in a few minutes.")
            }
            if (!page.isAvailable) throw VideoUnavailableException("Video '$videoId' is not available.")
            return page
        }
    }

    private suspend fun requestPlayer(videoId: String, body: String, userAgent: String): PlayerResponse {
        val headers = ArrayList<Pair<String, String>>()
        headers.add("User-Agent" to userAgent)
        context.visitorData()?.let { headers.add("X-Goog-Visitor-Id" to it) }
        val raw = context.postJson(PLAYER_ENDPOINT, body, headers)
        val response = PlayerResponse.parse(raw)
        if (!response.isAvailable) throw VideoUnavailableException("Video '$videoId' is not available.")
        ensurePlayable(videoId, response)
        return response
    }

    private fun baseClient(visitorData: String?): MutableMap<String, Any> {
        val client = linkedMapOf<String, Any>(
            "hl" to context.options.language,
            "gl" to context.options.region,
            "utcOffsetMinutes" to 0,
        )
        if (visitorData != null) client["visitorData"] = visitorData
        return client
    }

    private fun toJson(map: Map<String, Any>): JsonObject = buildJsonObject {
        for ((k, v) in map) when (v) {
            is String -> put(k, v)
            is Int -> put(k, v)
            is Boolean -> put(k, v)
            is JsonObject -> put(k, v)
            else -> put(k, JsonPrimitive(v.toString()))
        }
    }

    private fun playerBody(videoId: String, client: Map<String, Any>) = buildJsonObject {
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
        putJsonObject("context") { put("client", toJson(client)) }
    }.toString()

    /**
     * Player response from a client that returns plain (non-ciphered) URLs. Primary: VISIONOS. Fallback: ANDROID (works
     * for some videos VISIONOS rejects, e.g. kids content).
     */
    suspend fun getPlayerResponse(videoId: String): ClientResponse {
        val visitorData = context.visitorData()
        return try {
            // VisionOS is the primary client, as it works for most videos and returns plain URLs.
            val client = baseClient(visitorData)
            client["clientName"] = "VISIONOS"
            client["clientVersion"] = "1.02"
            client["deviceMake"] = "Apple"
            client["deviceModel"] = "RealityDevice17,1"
            client["osName"] = "visionOS"
            client["osVersion"] = "26.5.23O471"
            ClientResponse(requestPlayer(videoId, playerBody(videoId, client), VISIONOS_USER_AGENT), VISIONOS_USER_AGENT)
        } catch (e: VideoUnplayableException) {
            // Android works for certain other videos, such as videos intended for kids.
            val client = baseClient(visitorData)
            client["clientName"] = "ANDROID"
            client["clientVersion"] = ANDROID_VERSION
            client["androidSdkVersion"] = 30
            client["osName"] = "Android"
            client["osVersion"] = "11"
            ClientResponse(requestPlayer(videoId, playerBody(videoId, client), ANDROID_USER_AGENT), ANDROID_USER_AGENT)
        }
    }

    /**
     * Player response from the embedded TV client, which bypasses most age gates but returns ciphered URLs (and the
     * n-parameter). [signatureTimestamp] must come from the current player JS.
     */
    suspend fun getPlayerResponseWithCipher(videoId: String, signatureTimestamp: String): ClientResponse {
        val visitorData = context.visitorData()
        val client = baseClient(visitorData)
        client["clientName"] = "TVHTML5_SIMPLY_EMBEDDED_PLAYER"
        client["clientVersion"] = "2.0"
        val body = buildJsonObject {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("context") {
                put("client", toJson(client))
                putJsonObject("thirdParty") { put("embedUrl", "https://www.youtube.com") }
            }
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("signatureTimestamp", Str.tryParseInt(signatureTimestamp) ?: 0)
                }
            }
        }.toString()
        return ClientResponse(requestPlayer(videoId, body, TV_USER_AGENT), TV_USER_AGENT)
    }

    companion object {
        const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
        const val VISIONOS_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
        const val ANDROID_VERSION = "21.26.364"
        const val ANDROID_USER_AGENT = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"
        const val TV_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.114 Safari/537.36"

        /** Throws the appropriate exception if the response is not playable. */
        fun ensurePlayable(videoId: String, response: PlayerResponse) {
            val preview = response.previewVideoId
            if (!preview.isNullOrEmpty())
                throw VideoRequiresPurchaseException("Video '$videoId' requires purchase and cannot be played.", preview)
            if (response.isPlayable) return
            val reason = response.playabilityError ?: "unknown"
            if (response.isAgeRestricted) {
                throw VideoAgeRestrictedException(
                    "Video '$videoId' is age-restricted and could not be accessed. Reason: '$reason'. " +
                        "Provide cookies of an age-verified account to access it.",
                )
            }
            throw VideoUnplayableException("Video '$videoId' is unplayable. Reason: '$reason'.")
        }
    }
}

/** Operations related to YouTube videos (port of Videos::VideoClient). */
class VideoClient internal constructor(private val context: ClientContext) {
    /** Operations related to media streams. */
    val streams = StreamClient(context)

    /** Gets the metadata associated with the specified video. */
    suspend fun get(videoId: VideoId): Video {
        val controller = VideoController(context)
        val watchPage = controller.getVideoWatchPage(videoId.value)

        var playerResponse = watchPage.playerResponse
        // The watch page may lack details (consent walls, bot checks...): fall back to the API.
        if (playerResponse == null || playerResponse.title == null || playerResponse.channelId == null) {
            try {
                playerResponse = controller.getPlayerResponse(videoId.value).response
            } catch (e: VideoUnplayableException) {
                if (playerResponse == null) throw e
            }
        }
        val pr = playerResponse!!

        val title = pr.title ?: "" // Videos without title are legal
        val author = pr.author ?: throw YoutubeExplodeException("Failed to extract the video author.")
        val channelId = pr.channelId ?: throw YoutubeExplodeException("Failed to extract the video channel ID.")
        val uploadDate = pr.uploadDate ?: watchPage.uploadDate
            ?: throw YoutubeExplodeException("Failed to extract the video upload date.")

        val thumbnails = toThumbnails(pr.thumbnails) + Thumbnail.defaultSet(videoId.value)
        return Video(
            videoId, title, Author(channelId, author), uploadDate, pr.description ?: "", pr.duration, thumbnails,
            pr.keywords,
            // Engagement statistics may be hidden
            Engagement(pr.viewCount ?: 0, watchPage.likeCount ?: 0, watchPage.dislikeCount ?: 0),
            pr.isLive,
        )
    }
}
