package com.app.shadetube.youtube.explode

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.coroutineContext

private fun webClient(options: YoutubeClientOptions, visitorData: String? = null): JsonObject = buildJsonObject {
    put("clientName", "WEB")
    put("clientVersion", "2.20210408.08.00")
    put("hl", options.language)
    put("gl", options.region)
    put("utcOffsetMinutes", 0)
    if (visitorData != null) put("visitorData", visitorData)
}

// ---- Search ----------------------------------------------------------------------------------------------------------

/** Operations related to YouTube search (uses the internal /youtubei/v1/search endpoint). Port of SearchClient. */
class SearchClient internal constructor(private val context: ClientContext) {
    private fun filterParams(filter: SearchFilter): String? = when (filter) {
        SearchFilter.Video -> "EgIQAQ%3D%3D"
        SearchFilter.Playlist -> "EgIQAw%3D%3D"
        SearchFilter.Channel -> "EgIQAg%3D%3D"
        SearchFilter.None -> null
    }

    private suspend fun getSearchResponse(query: String, filter: SearchFilter, continuation: String?): SearchResponse {
        val body = buildJsonObject {
            put("query", query)
            putJsonObject("context") { put("client", webClient(context.options)) }
            filterParams(filter)?.let { put("params", it) }
            if (continuation != null) put("continuation", continuation)
        }
        return SearchResponse.parse(context.postJson("https://www.youtube.com/youtubei/v1/search", body.toString()))
    }

    /** Enumerates batches (pages) of search results. [handler] returns false to stop early. */
    suspend fun getResultBatches(query: String, filter: SearchFilter, handler: suspend (List<SearchResult>) -> Boolean) {
        val encounteredIds = HashSet<String>()
        var continuationToken: String? = null
        do {
            coroutineContext.ensureActive()
            val results = ArrayList<SearchResult>()
            val response = getSearchResponse(query, filter, continuationToken)

            if (filter == SearchFilter.None || filter == SearchFilter.Video) {
                for (v in response.videos) {
                    val id = v.id ?: continue
                    // Don't yield the same result twice
                    if (!encounteredIds.add(id)) continue
                    val videoId = VideoId.tryParse(id)
                    // Some videos have invalid channel IDs (e.g. just "UC"); they are generally unplayable anyway.
                    val channelId = v.channelId?.let { ChannelId.tryParse(it) }
                    if (videoId == null || v.title == null || v.author == null || channelId == null) continue
                    val thumbnails = toThumbnails(v.thumbnails) + Thumbnail.defaultSet(id)
                    results.add(VideoSearchResult(videoId, v.title, Author(channelId.value, v.author), v.duration, thumbnails))
                }
            }

            if (filter == SearchFilter.None || filter == SearchFilter.Playlist) {
                for (p in response.playlists) {
                    val id = p.id ?: continue
                    val title = p.title ?: continue
                    if (!encounteredIds.add(id)) continue
                    val playlistId = PlaylistId.tryParse(id) ?: continue
                    // System playlists have no author
                    val author = if (!p.channelId.isNullOrEmpty() && !p.author.isNullOrEmpty()) Author(p.channelId, p.author) else null
                    results.add(PlaylistSearchResult(playlistId, title, author, toThumbnails(p.thumbnails)))
                }
            }

            if (filter == SearchFilter.None || filter == SearchFilter.Channel) {
                for (c in response.channels) {
                    val id = c.id ?: continue
                    val title = c.title ?: continue
                    if (!encounteredIds.add(id)) continue
                    val channelId = ChannelId.tryParse(id) ?: continue
                    results.add(ChannelSearchResult(channelId, title, toThumbnails(c.thumbnails)))
                }
            }

            if (!handler(results)) return
            continuationToken = response.continuationToken
        } while (!continuationToken.isNullOrEmpty())
    }

    private suspend inline fun <reified T : SearchResult> collect(query: String, filter: SearchFilter, maxCount: Int): List<T> {
        val result = ArrayList<T>()
        if (maxCount <= 0) return result
        var emptyBatches = 0
        getResultBatches(query, filter) { batch ->
            val before = result.size
            for (item in batch) {
                if (item is T) {
                    result.add(item)
                    if (result.size >= maxCount) return@getResultBatches false
                }
            }
            // Guard against endless pagination that yields nothing new.
            emptyBatches = if (result.size == before) emptyBatches + 1 else 0
            emptyBatches < 3
        }
        return result
    }

    /** Gets results (videos, playlists and channels) returned by the specified query. */
    suspend fun getResults(query: String, maxCount: Int = 20): List<SearchResult> {
        val result = ArrayList<SearchResult>()
        if (maxCount <= 0) return result
        var emptyBatches = 0
        getResultBatches(query, SearchFilter.None) { batch ->
            for (item in batch) {
                result.add(item)
                if (result.size >= maxCount) return@getResultBatches false
            }
            emptyBatches = if (batch.isEmpty()) emptyBatches + 1 else 0
            emptyBatches < 3
        }
        return result
    }

    suspend fun getVideos(query: String, maxCount: Int = 20): List<VideoSearchResult> =
        collect(query, SearchFilter.Video, maxCount)

    suspend fun getPlaylists(query: String, maxCount: Int = 20): List<PlaylistSearchResult> =
        collect(query, SearchFilter.Playlist, maxCount)

    suspend fun getChannels(query: String, maxCount: Int = 20): List<ChannelSearchResult> =
        collect(query, SearchFilter.Channel, maxCount)
}

// ---- Playlists -------------------------------------------------------------------------------------------------------

internal class PlaylistController(private val context: ClientContext) {
    /** Works only with user-made playlists, but provides rich metadata. */
    suspend fun getPlaylistBrowseResponse(playlistId: String): PlaylistData {
        val body = buildJsonObject {
            put("browseId", "VL$playlistId")
            putJsonObject("context") { put("client", webClient(context.options)) }
        }
        val data = parsePlaylistBrowseResponse(context.postJson("https://www.youtube.com/youtubei/v1/browse", body.toString()))
        if (!data.isAvailable) throw PlaylistUnavailableException("Playlist '$playlistId' is not available.")
        return data
    }

    /** Works with all playlists (including mixes), but contains limited metadata. */
    suspend fun getPlaylistNextResponse(playlistId: String, videoId: String?, index: Int, visitorData: String?): PlaylistNextResponse {
        val retriesCount = 5
        var retriesRemaining = retriesCount
        while (true) {
            val body = buildJsonObject {
                put("playlistId", playlistId)
                put("playlistIndex", index)
                putJsonObject("context") { put("client", webClient(context.options, visitorData)) }
                if (videoId != null) put("videoId", videoId)
            }
            val response = PlaylistNextResponse.parse(context.postJson("https://www.youtube.com/youtubei/v1/next", body.toString()))

            if (!response.playlist.isAvailable) {
                // Some system playlists only materialize after someone opens them once.
                if (index <= 0 && visitorData == null && retriesRemaining >= retriesCount) {
                    context.send(YtHttpRequest("https://www.youtube.com/playlist?list=$playlistId"))
                    --retriesRemaining
                    continue
                }
                // Not the first page and previous pages worked: most likely a transient error.
                if (index > 0 && visitorData != null && retriesRemaining > 0) {
                    --retriesRemaining
                    continue
                }
                // The target video may be unavailable while the playlist itself is fine.
                if (retriesRemaining <= 0 && response.videos.isNotEmpty()) return response
                throw PlaylistUnavailableException("Playlist '$playlistId' is not available.")
            }
            return response
        }
    }

    suspend fun getPlaylistResponse(playlistId: String): PlaylistData = try {
        getPlaylistBrowseResponse(playlistId)
    } catch (e: PlaylistUnavailableException) {
        getPlaylistNextResponse(playlistId, null, 0, null).playlist
    }
}

/** Operations related to YouTube playlists (port of Playlists::PlaylistClient). */
class PlaylistClient internal constructor(private val context: ClientContext) {
    /** Gets the metadata associated with the specified playlist. */
    suspend fun get(playlistId: PlaylistId): Playlist {
        val data = PlaylistController(context).getPlaylistResponse(playlistId.value)
        val title = data.title ?: throw YoutubeExplodeException("Failed to extract the playlist title.")
        // System playlists have no author
        val author = if (data.channelId != null && data.author != null) Author(data.channelId, data.author) else null
        return Playlist(playlistId, title, author, data.description ?: "", data.count, toThumbnails(data.thumbnails))
    }

    /** Enumerates batches (pages) of videos included in the playlist. [handler] returns false to stop early. */
    suspend fun getVideoBatches(playlistId: PlaylistId, handler: suspend (List<PlaylistVideo>) -> Boolean) {
        val controller = PlaylistController(context)
        val encounteredIds = HashSet<String>()
        var lastVideoId: String? = null
        var lastVideoIndex = 0
        var visitorData: String? = null

        while (true) {
            coroutineContext.ensureActive()
            val videos = ArrayList<PlaylistVideo>()
            try {
                val response = controller.getPlaylistNextResponse(playlistId.value, lastVideoId, lastVideoIndex, visitorData)
                for (v in response.videos) {
                    val id = v.id ?: throw YoutubeExplodeException("Failed to extract the video ID.")
                    val index = v.index ?: throw YoutubeExplodeException("Failed to extract the video index.")
                    lastVideoId = id
                    lastVideoIndex = index

                    // Don't yield the same video twice
                    if (!encounteredIds.add(id)) continue

                    val videoId = VideoId.tryParse(id)
                    if (videoId == null || v.author == null || v.channelId == null) continue // Deleted/private entries have no author

                    val thumbnails = toThumbnails(v.thumbnails) + Thumbnail.defaultSet(id)
                    videos.add(PlaylistVideo(playlistId, videoId, v.title ?: "", Author(v.channelId, v.author), v.duration, thumbnails))
                }

                // Stop if there are no new videos
                if (videos.isEmpty()) break
                if (visitorData == null) visitorData = response.visitorData
            } catch (e: PlaylistUnavailableException) {
                // Playlist became unavailable after some videos were extracted: treat as the end.
                if (lastVideoIndex > 0) break
                throw e
            }
            if (!handler(videos)) break
        }
    }

    /** Gets videos included in the specified playlist (up to [maxCount]). */
    suspend fun getVideos(playlistId: PlaylistId, maxCount: Int = Int.MAX_VALUE): List<PlaylistVideo> {
        val result = ArrayList<PlaylistVideo>()
        if (maxCount <= 0) return result
        getVideoBatches(playlistId) { batch ->
            for (v in batch) {
                result.add(v)
                if (result.size >= maxCount) return@getVideoBatches false
            }
            true
        }
        return result
    }
}

// ---- Channels --------------------------------------------------------------------------------------------------------

/** Operations related to YouTube channels (port of Channels::ChannelClient). */
class ChannelClient internal constructor(private val context: ClientContext) {
    private suspend fun getChannelPage(route: String): ChannelPage {
        var retriesRemaining = 5
        while (true) {
            val page = ChannelPage.tryParse(context.getString("https://www.youtube.com/$route"))
            if (page == null) {
                if (retriesRemaining-- > 0) continue
                throw YoutubeExplodeException("Channel page is broken. Please try again in a few minutes.")
            }
            return page
        }
    }

    private fun toChannel(page: ChannelPage): Channel {
        val id = page.id ?: throw YoutubeExplodeException("Failed to extract the channel ID.")
        val title = page.title ?: throw YoutubeExplodeException("Failed to extract the channel title.")
        val logoUrl = page.logoUrl ?: throw YoutubeExplodeException("Failed to extract the channel logo URL.")
        // Logo URLs look like ...=s900-c-k-c0x00ffffff-no-rj: the size is the last "s<digits>" token.
        var logoSize = 100
        for (m in LOGO_SIZE.findAll(logoUrl)) Str.tryParseInt(m.groupValues[1])?.let { logoSize = it }
        val channelId = ChannelId.tryParse(id) ?: throw YoutubeExplodeException("Failed to extract the channel ID.")
        return Channel(channelId, title, listOf(Thumbnail(logoUrl, Resolution(logoSize, logoSize))))
    }

    /** Gets the metadata associated with the specified channel. */
    suspend fun get(channelId: ChannelId): Channel {
        // Special case for the "Movies & TV" channel, which has a custom page
        if (channelId.value == "UCuVPpxrm2VAgpH3Ktln4HXg") {
            return Channel(
                channelId, "Movies & TV",
                listOf(Thumbnail("https://www.gstatic.com/youtube/img/tvfilm/clapperboard_profile.png", Resolution(1024, 1024))),
            )
        }
        return toChannel(getChannelPage("channel/${channelId.value}"))
    }

    suspend fun getByUser(userName: UserName): Channel = toChannel(getChannelPage("user/${userName.value}"))
    suspend fun getBySlug(slug: ChannelSlug): Channel = toChannel(getChannelPage("c/${slug.value}"))
    suspend fun getByHandle(handle: ChannelHandle): Channel = toChannel(getChannelPage("@${handle.value}"))

    /** The uploads playlist ID is the channel ID with "UC" replaced by "UU". */
    private fun uploadsOf(channelId: ChannelId) = PlaylistId.parse("UU" + channelId.value.substring(2))

    suspend fun getUploadBatches(channelId: ChannelId, handler: suspend (List<PlaylistVideo>) -> Boolean) =
        PlaylistClient(context).getVideoBatches(uploadsOf(channelId), handler)

    /** Videos uploaded by the specified channel (newest first, up to [maxCount]). */
    suspend fun getUploads(channelId: ChannelId, maxCount: Int = Int.MAX_VALUE): List<PlaylistVideo> =
        PlaylistClient(context).getVideos(uploadsOf(channelId), maxCount)

    private companion object {
        val LOGO_SIZE = Regex("""\bs(\d+)\b""")
    }
}
