package com.app.shadetube.playback.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.app.shadetube.core.Log
import com.app.shadetube.playback.PlayContext
import com.app.shadetube.playback.PlaybackModule
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * The media session's side of voice search (used by [PlaybackService]'s callback, main thread):
 *  - "play from search" (Assistant / Android Auto voice): Media3 turns playFromSearch into setMediaItems with one item
 *    whose RequestMetadata carries the query; it is resolved by [VoiceSearch] into the list to play, cached in the
 *    browse tree under a context of its own so [SessionPlayer] starts it with the album / artist / search context.
 *  - Android Auto's search screen: [search] / [searchResult] list the catalog's songs for the query.
 */
@OptIn(UnstableApi::class)
internal class VoiceSession(private val module: PlaybackModule) {
    private companion object {
        const val TAG = "player"
        const val SEARCH_TIMEOUT_MS = 12_000L
        const val CACHED_SEARCHES = 4
    }

    private val tree get() = module.browseTree
    private val searches = object : LinkedHashMap<String, List<MediaItem>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaItem>>?) = size > CACHED_SEARCHES
    }

    /**
     * The voice request a setMediaItems / addMediaItems call carries: one item with a search query, or one empty item
     * (playFromSearch without a query: "play some music"). null = ordinary items.
     */
    fun requestOf(items: List<MediaItem>): VoiceRequest? {
        if (items.size != 1) return null
        val item = items[0]
        val rm = item.requestMetadata
        if (rm.searchQuery != null) return VoiceRequest.of(rm.searchQuery, rm.extras)
        val blank = item.mediaId.isEmpty() && rm.mediaUri == null && item.mediaMetadata.title == null &&
            item.mediaMetadata.extras?.containsKey(MediaItems.EXTRA_TRACK) != true
        return if (blank) VoiceRequest.of(null, rm.extras) else null
    }

    /** setMediaItems for a voice request; an empty one resumes the last session ([resume]) when there is one. */
    fun setMediaItems(
        req: VoiceRequest,
        resume: () -> MediaSession.MediaItemsWithStartPosition?,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (req.isEmpty) resume()?.let { return Futures.immediateFuture(it) }
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        module.scope.launch {
            val play = VoiceSearch.resolve(req, tree.source)
            if (play == null || play.tracks.isEmpty()) {
                Log.i(TAG, "voice search: nothing for \"${req.searchText}\"")
                future.setException(NoSuchElementException("nothing to play for the request"))
                return@launch
            }
            Log.i(TAG, "voice search \"${req.searchText}\" -> ${play.context.uri} (${play.tracks.size})")
            val items = tree.adopt(play.tracks, play.context)
            future.set(MediaSession.MediaItemsWithStartPosition(items, startIndex(play), C.TIME_UNSET))
        }
        return future
    }

    /** addMediaItems for a voice request: the resolved list. */
    fun addMediaItems(req: VoiceRequest): ListenableFuture<MutableList<MediaItem>> {
        val future = SettableFuture.create<MutableList<MediaItem>>()
        module.scope.launch {
            val play = VoiceSearch.resolve(req, tree.source)
            future.set(if (play == null) ArrayList() else tree.adopt(play.tracks, play.context).toMutableList())
        }
        return future
    }

    /** -1 (no particular song): a random one with shuffle on, else the first the player may pick by itself. */
    private fun startIndex(play: VoicePlay): Int {
        if (play.startIndex >= 0) return play.startIndex
        if (module.player.shuffle) return Random.nextInt(play.tracks.size)
        return module.player.firstPlayable(play.tracks).coerceAtLeast(0)
    }

    /** Android Auto's search: runs the search, then tells the browser how many results there are. */
    fun search(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        module.scope.launch {
            val items = searchItems(query)
            session.notifySearchResultChanged(browser, query, items.size, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid(params))
    }

    fun searchResult(query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        module.scope.launch {
            val all = searchItems(query)
            val from = (page * pageSize).coerceIn(0, all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params))
        }
        return future
    }

    /** The catalog's songs for [query] as playable items (cached for the last few queries). */
    private suspend fun searchItems(query: String): List<MediaItem> {
        searches[query]?.let { return it }
        val cat = VoiceSearch.catalog ?: return emptyList()
        val text = VoiceRequest.cleanQuery(query)
        if (text.isEmpty()) return emptyList()
        val tracks = try {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) { cat.search(text).tracks }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Auto search \"$text\" failed: ${e.message}")
            return emptyList()
        }.filter { it.playable && it.name.isNotEmpty() }
        val items = tree.adopt(tracks, PlayContext("search", text))
        searches[query] = items
        return items
    }
}
