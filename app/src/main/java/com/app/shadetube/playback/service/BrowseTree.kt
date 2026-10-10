package com.app.shadetube.playback.service

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.pick
import com.app.shadetube.playback.PlayContext

/**
 * What Android Auto (and other browsing controllers) can browse; implemented by AppGraph over the library / history.
 * Main-safe suspend functions.
 */
interface BrowseSource {
    /** Recently played songs, newest first. */
    suspend fun recentlyPlayed(limit: Int): List<Track>

    /** The library's playlists (Liked Songs first). */
    suspend fun playlists(): List<Playlist>

    suspend fun playlistTracks(playlistId: String): List<Track>
}

/** Display names of the browse tree's folders (the UI installs strings.xml texts). */
data class BrowseLabels(val recent: String = "Recently played", val playlists: String = "Playlists")

/**
 * A minimal browse tree for Android Auto:
 *
 *   root
 *    ├─ recent                   (playable songs)
 *    └─ playlists                (browsable)
 *        └─ pl|<playlist id>     (playable songs)
 *
 * Song ids are "tr|<list key>|<index>|<track id>" ("recent" or "pl:<playlist id>"): playing one plays its list from it
 * (the lists are cached as they are browsed, so the session can expand a lone id into the whole list).
 */
class BrowseTree(val source: BrowseSource?, var labels: BrowseLabels = BrowseLabels()) {
    companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val PLAYLISTS = "playlists"
        const val RECENT_LIMIT = 50

        fun songId(listKey: String, index: Int, t: Track) = "tr|$listKey|$index|${t.id}"

        /** The list key and index of a song id. */
        fun parseSongId(id: String): Pair<String, Int>? {
            if (!id.startsWith("tr|")) return null
            val parts = id.split('|', limit = 4)
            if (parts.size < 4) return null
            val index = parts[2].toIntOrNull() ?: return null
            return parts[1] to index
        }

        /** Contexts of the lists [adopt] cached (voice / Android Auto search results), by list key; the last few. */
        private val adoptedContexts = object : LinkedHashMap<String, PlayContext>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlayContext>?) = size > ADOPTED_MAX
        }
        private const val ADOPTED_MAX = 8
        private var adoptedSeq = 0

        /** The play context of a browse item ("liked" for Liked Songs, "playlist:<id>", "history"). */
        fun contextOf(mediaId: String?): PlayContext? {
            val (key, _) = parseSongId(mediaId ?: return null) ?: return null
            synchronized(adoptedContexts) { adoptedContexts[key] }?.let { return it }
            return when {
                key == RECENT -> PlayContext("history", "")
                key.startsWith("pl:") -> {
                    val id = key.removePrefix("pl:")
                    if (id == com.app.shadetube.catalog.LIKED_SONGS_URI) PlayContext("liked", "") else PlayContext("playlist:$id", "")
                }
                else -> null
            }
        }
    }

    private val lists = HashMap<String, List<Track>>()
    private val playlistNames = HashMap<String, String>()

    fun root(): MediaItem = MediaItems.folder(ROOT, "ShadeTube")

    suspend fun children(parentId: String): List<MediaItem> {
        val src = source ?: return emptyList()
        return when {
            parentId == ROOT -> listOf(
                MediaItems.folder(RECENT, labels.recent, mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST),
                MediaItems.folder(PLAYLISTS, labels.playlists, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            )
            parentId == RECENT -> songs(RECENT, src.recentlyPlayed(RECENT_LIMIT))
            parentId == PLAYLISTS -> src.playlists().map { p ->
                playlistNames[p.id] = p.name
                MediaItems.folder("pl|${p.id}", p.name, p.images.pick(300)?.url, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
            parentId.startsWith("pl|") -> {
                val id = parentId.removePrefix("pl|")
                songs("pl:$id", src.playlistTracks(id))
            }
            else -> emptyList()
        }
    }

    private fun songs(key: String, tracks: List<Track>): List<MediaItem> {
        val playable = tracks.filter { it.playable && it.name.isNotEmpty() }
        lists[key] = playable
        return playable.mapIndexed { i, t -> MediaItems.of(t, songId(key, i, t)) }
    }

    /**
     * Caches [tracks] (already filtered to playable ones) as a list of its own bound to [context] — voice search and
     * Android Auto search results — so its items can be expanded / resolved like browsed songs and play with that
     * context. Returns the items.
     */
    fun adopt(tracks: List<Track>, context: PlayContext): List<MediaItem> {
        val key = synchronized(adoptedContexts) {
            val k = "vs${++adoptedSeq}"
            adoptedContexts[k] = context
            lists.keys.removeAll { it.startsWith("vs") && it !in adoptedContexts }
            k
        }
        lists[key] = tracks
        return tracks.mapIndexed { i, t -> MediaItems.of(t, songId(key, i, t)) }
    }

    /** A song id -> its whole cached list as items, and the index to start at; null when unknown. */
    fun expand(mediaId: String): Pair<List<MediaItem>, Int>? {
        val (key, index) = parseSongId(mediaId) ?: return null
        val list = lists[key] ?: return null
        if (index !in list.indices) return null
        return list.mapIndexed { i, t -> MediaItems.of(t, songId(key, i, t)) } to index
    }

    /** A known item by id (browse folders are rebuilt; songs come from the cache). */
    fun item(mediaId: String): MediaItem? {
        if (mediaId == ROOT) return root()
        val (key, index) = parseSongId(mediaId) ?: return null
        val t = lists[key]?.getOrNull(index) ?: return null
        return MediaItems.of(t, mediaId)
    }
}
