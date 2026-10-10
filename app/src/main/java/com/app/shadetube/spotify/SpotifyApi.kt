package com.app.shadetube.spotify

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.ArtistPage
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.catalog.Shelf
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.Log
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.dump
import com.app.shadetube.core.jsonArrayOf
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.parseJsonOrNull
import com.app.shadetube.spotify.SpJson.arr
import com.app.shadetube.spotify.SpJson.at
import com.app.shadetube.spotify.SpJson.isArr
import com.app.shadetube.spotify.SpJson.isObj
import com.app.shadetube.spotify.SpJson.str
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** A Spotify request failed: [status] is the HTTP status (0 = a local precondition, e.g. an invalid id). */
class ApiError(val status: Int, message: String) : IOException(message)

/** The user's library playlists plus the folder tree from the same rootlist read. */
data class LibraryPlaylists(
    /** Liked Songs first, then libraryV3's order (folders flattened away). */
    val playlists: List<Playlist>,
    /** Empty when the rootlist read failed or the user has no folders. */
    val tree: PlaylistTree,
)

/** createPlaylist result: [uri] = the new playlist ("" on failure), [added] = how many of the tracks were added. */
data class CreatedPlaylist(val uri: String, val added: Int)

/**
 * Client for Spotify's internal Pathfinder GraphQL API (api-partner.spotify.com/pathfinder/v2/query) and the
 * spclient services the web player uses (port of spotify/SpotifyApi).
 *
 * Uses a Bearer access token minted by [Auth] (from the sp_dc cookie). Returns source-independent catalog models so
 * the rest of the app doesn't care that the data came from Spotify. Every function is `suspend` and main-safe; reads
 * throw [ApiError] (or IOException on transport failure), writes return success and log failures.
 *
 * The web-player token does NOT authorize api.spotify.com/v1 (it 429s everything), so that API is never used; a 429
 * from Pathfinder / spclient surfaces as ApiError(429) without a retry, exactly like the C++.
 */
class SpotifyApi {
    private val lock = Any()
    private var accessToken = ""
    private var spDc = ""
    private var username = ""

    /** Localized names for Liked Songs & card subtitles (the UI sets these from strings.xml). */
    @Volatile
    var labels: SpotifyLabels = SpotifyLabels()

    /**
     * Runs before every request (the Session refreshes an expired token here). Android stops the refresh timer in
     * deep sleep, so a request after a long idle period must not go out with a stale token.
     */
    @Volatile
    internal var beforeRequest: (suspend () -> Unit)? = null

    /** Called by Session whenever the token is (re)minted. Thread-safe. */
    fun setCredentials(accessToken: String, spDc: String) = synchronized(lock) {
        this.accessToken = accessToken
        this.spDc = spDc
    }

    fun hasCredentials(): Boolean = synchronized(lock) { accessToken.isNotEmpty() }

    fun username(): String = synchronized(lock) { username }
    fun setUsername(name: String) = synchronized(lock) { username = name }

    private suspend fun credentials(): Pair<String, String> {
        beforeRequest?.invoke()
        val (token, cookie) = synchronized(lock) { accessToken to spDc }
        if (token.isEmpty()) throw ApiError(401, "Spotify: not authenticated")
        return token to cookie
    }

    // ---- Pathfinder -----------------------------------------------------------------------------------------------

    /**
     * Runs a persisted GraphQL query; returns the "data" object. Throws ApiError on non-2xx / GraphQL error.
     *
     * Self-healing: when Pathfinder rejects the op's hash (HTTP 412 "Invalid query hash": Spotify purged that document
     * version), [HashRegistry.heal] learns the web player's current hash (throttled) and the request is retried ONCE
     * with it. A 400 that names a variable is a request problem, never a hash refresh. [acceptLanguage] (optional)
     * localizes server-built strings such as home shelf titles.
     */
    private suspend fun pathfinder(operationName: String, variables: JsonElement, acceptLanguage: String? = null): JsonElement {
        val (token, cookie) = credentials()
        var hash = HashRegistry.hash(operationName)
        if (hash.isEmpty()) throw ApiError(0, "Spotify $operationName: no persisted-query hash known")

        var attempt = 0
        while (true) {
            val body = jsonOf(
                "variables" to variables,
                "operationName" to operationName,
                "extensions" to jsonOf("persistedQuery" to jsonOf("version" to 1, "sha256Hash" to hash)),
            )
            val headers = mutableListOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to SPOTIFY_USER_AGENT,
                "App-Platform" to "WebPlayer",
                "Cookie" to "sp_dc=$cookie;",
            )
            if (!acceptLanguage.isNullOrEmpty()) headers.add("Accept-Language" to acceptLanguage)
            val resp = Http.await(HttpRequest(ENDPOINT, "POST", headers, body.dump().toByteArray(), "application/json"))

            val j = if (resp.isSuccess) parseJsonOrNull(resp.body) else null
            val errors = at(j, "errors")
            val gqlErrors = isArr(errors) && arr(errors).isNotEmpty()
            if (resp.isSuccess && !gqlErrors) {
                if (j == null) throw ApiError(resp.status, "Spotify $operationName: invalid JSON")
                val data = at(j, "data")
                if (!isObj(data)) throw ApiError(resp.status, "Spotify $operationName: no data")
                if (attempt > 0) Log.i(TAG, "$operationName succeeded with the refreshed hash")
                return data!!
            }

            // Classify the failure (for a 2xx, only its GraphQL errors: data may contain '$' or "variable" anywhere).
            val detail = if (gqlErrors) errors!!.dump() else resp.body
            val shortDetail = detail.take(700)
            val c = HashDiscovery.classifyFailure(resp.status, detail)
            if (c.kind == QueryFailure.HashRejected && attempt == 0) {
                Log.w(TAG, "$operationName HTTP ${resp.status}: persisted-query hash ${hash.take(8)}... rejected ($shortDetail)")
                val fresh = HashRegistry.heal(operationName, hash)
                if (fresh.isNotEmpty() && fresh != hash) {
                    Log.i(TAG, "$operationName: retrying with hash ${fresh.take(8)}... (was ${hash.take(8)}...)")
                    hash = fresh
                    ++attempt
                    continue
                }
            } else if (c.kind == QueryFailure.VariableError) {
                Log.w(
                    TAG,
                    "$operationName HTTP ${resp.status}: the query document (hash ${hash.take(8)}...) rejects our variables" +
                        (if (c.variable.isEmpty()) "" else ": $" + c.variable) +
                        ": the request needs updating, not the hash. vars=${variables.dump()} body=$shortDetail",
                )
            } else if (c.kind != QueryFailure.HashRejected) {
                Log.w(TAG, "$operationName HTTP ${resp.status} vars=${variables.dump()} body=$shortDetail")
            } else if (attempt > 0) {
                Log.w(TAG, "$operationName: the refreshed hash ${hash.take(8)}... was rejected too (HTTP ${resp.status})")
            }
            if (gqlErrors) throw ApiError(resp.status, "Spotify $operationName: " + str(arr(errors)[0], "message"))
            throw ApiError(resp.status, "Spotify $operationName HTTP ${resp.status}")
        }
    }

    // ---- spclient ---------------------------------------------------------------------------------------------------

    /**
     * Any spclient.wg.spotify.com request (absolute [url]) with the web-player headers. Throws ApiError on non-2xx;
     * returns the parsed body (JsonNull when empty / not JSON). [quietStatus]: a status that is an expected answer
     * (404 "no lyrics"), not logged as a warning.
     */
    private suspend fun spclient(method: String, url: String, body: JsonElement?, quietStatus: Int = 0): JsonElement {
        val (token, cookie) = credentials()
        val headers = mutableListOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/json",
            "User-Agent" to SPOTIFY_USER_AGENT,
            "App-Platform" to "WebPlayer",
            "Origin" to "https://open.spotify.com",
            "Referer" to "https://open.spotify.com/",
            "Cookie" to "sp_dc=$cookie;",
        )
        var bytes: ByteArray? = null
        var contentType: String? = null
        if (body != null) {
            contentType = "application/json;charset=UTF-8"
            headers.add("Content-Type" to contentType)
            bytes = body.dump().toByteArray()
        }
        val resp = Http.await(HttpRequest(url, method, headers, bytes, contentType))
        val host = "https://spclient.wg.spotify.com"
        val path = if (url.startsWith(host)) url.substring(host.length) else url
        val where = "$method " + path.substringBefore('?')
        if (!resp.isSuccess) {
            if (resp.status != quietStatus) Log.w(TAG, "spclient $where HTTP ${resp.status} body=${resp.body.take(500)}")
            throw ApiError(resp.status, "Spotify $where HTTP ${resp.status}")
        }
        if (resp.body.isEmpty()) return JsonNull
        return parseJsonOrNull(resp.body) ?: JsonNull
    }

    /**
     * spclient.wg.spotify.com/playlist/v2 request (JSON in / out). Bodies are the JSON form of Spotify's playlist4
     * protos (ts-proto toJSON: enums as strings):
     *   create:   POST /playlist                 Delta{ops:[UPDATE_LIST_ATTRIBUTES{name}], info}  -> {uri, revision}
     *   rename:   POST /playlist/<id>/changes    ListChanges{deltas:[Delta]}
     *   rootlist: GET /user/<u>/rootlist?decorate=...  and  POST /user/<u>/rootlist/changes  ListChanges (ADD / REM)
     */
    private suspend fun playlistService(method: String, path: String, body: JsonElement?): JsonElement =
        spclient(method, PLAYLIST_SERVICE + path, body)

    private suspend fun requireUsername(): String {
        var user = username()
        if (user.isEmpty()) user = me().username
        if (user.isEmpty()) throw ApiError(0, "Spotify: unknown username")
        return user
    }

    /** The raw rootlist (contents.items[] + metaItems[] with each playlist's length). */
    private suspend fun rootlist(): JsonElement {
        val user = requireUsername()
        val path = "/user/" + Http.urlEncode(user) +
            "/rootlist?decorate=revision%2Clength%2Cattributes%2Ctimestamp%2Cowner%2Ccapabilities" +
            "&bustCache=" + System.currentTimeMillis()
        return playlistService("GET", path, null)
    }

    /** POST /user/<username>/rootlist/changes with one delta of [ops]. */
    private suspend fun rootlistChanges(ops: JsonElement) {
        val user = requireUsername()
        playlistService("POST", "/user/" + Http.urlEncode(user) + "/rootlist/changes", listChanges(ops))
    }

    /** Runs a write; failures are logged and turn into [fallback] (cancellation still propagates). */
    private inline fun <T> logged(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed: ${e.message}")
        fallback
    }

    // ---- Profile ----------------------------------------------------------------------------------------------------

    /** The logged-in user; also remembers the username (needed by the playlist writes). */
    suspend fun me(): UserProfile = withContext(AppDispatchers.io) {
        val u = SpotifyParse.parseProfile(pathfinder("profileAttributes", JsonObject(emptyMap())))
        if (u.username.isNotEmpty()) setUsername(u.username)
        u
    }

    // ---- Library ----------------------------------------------------------------------------------------------------

    /**
     * The user's library (newest first). Playlists include the "Liked Songs" pseudo-playlist first. Each playlist
     * carries its owner and whether the user may edit it (currentUserCapabilities.canEditItems); track counts come from
     * the rootlist (libraryV3 has none) — `countKnown` is false when that lookup failed. Folders are flattened away
     * (their playlists are listed in libraryV3's order); the tree comes from the same rootlist read.
     */
    suspend fun libraryPlaylists(): LibraryPlaylists = withContext(AppDispatchers.io) {
        val me = username()
        val out = ArrayList<Playlist>()
        val seen = HashSet<String>()
        var libTotal = 0 // libraryV3 totalCount (logged: shows whether the pseudo-playlists count toward paging)
        // libraryV3 pages 50 at a time; follow totalCount (capped so a pathological library can't loop forever).
        var offset = 0
        for (page in 0 until 20) {
            val data = try {
                pathfinder("libraryV3", libraryVariables("Playlists", offset, flatten = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (page == 0) throw e // nothing to show; later pages: keep what we have
                Log.w(TAG, "libraryV3 playlists page at $offset failed: ${e.message}")
                break
            }
            val lp = SpotifyParse.parseLibraryPlaylistsPage(data, me, labels) ?: break
            if (!loggedShape.getAndSet(true)) {
                val firstPlaylist = arr(at(at(at(data, "me"), "libraryV3"), "items"))
                    .firstOrNull { str(at(at(it, "item"), "data"), "__typename") == "Playlist" }
                if (firstPlaylist != null) Log.d(TAG, "libraryV3 playlist item (keys for counts): ${firstPlaylist.dump().take(1200)}")
            }
            for (p in lp.playlists) if (p.id.isNotEmpty() && seen.add(p.id)) out.add(p)
            offset += lp.rawCount
            libTotal = lp.totalCount
            if (offset >= libTotal) break
        }

        // Track counts: the rootlist decorated with `length` lists every playlist the user has with its size. It also
        // brackets folders with spotify:start-group / end-group items (the folder tree).
        var tree = PlaylistTree()
        var result: List<Playlist> = out
        try {
            val root = rootlist()
            val lengths = SpotifyParse.parseRootlistLengths(root)
            tree = parsePlaylistTree(root)
            result = out.map { p -> lengths[p.id]?.let { p.copy(totalTracks = it, countKnown = true) } ?: p }
            val items = at(at(root, "contents"), "items")
            Log.i(
                TAG,
                "playlists: ${out.size} listed (libraryV3 totalCount $libTotal); rootlist: " +
                    "${if (isArr(items)) arr(items).size else 0} items, ${lengths.size} with a length, ${tree.folders.size} folders",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "rootlist (playlist counts) failed: ${e.message}")
        }
        LibraryPlaylists(result, tree)
    }

    suspend fun libraryAlbums(): List<Album> = withContext(AppDispatchers.io) {
        SpotifyParse.parseLibraryAlbums(pathfinder("libraryV3", libraryVariables("Albums")))
    }

    suspend fun libraryArtists(): List<Artist> = withContext(AppDispatchers.io) {
        SpotifyParse.parseLibraryArtists(pathfinder("libraryV3", libraryVariables("Artists")))
    }

    /** The playlist URIs in the user's rootlist (folders flattened), in order. Throws ApiError. Used for counts. */
    suspend fun rootlistPlaylists(): List<String> = withContext(AppDispatchers.io) {
        SpotifyParse.rootlistPlaylists(rootlist())
    }

    // ---- Playlists / albums / artists / search ---------------------------------------------------------------------

    /**
     * Tracks of a playlist (uri = spotify:playlist:...); Liked Songs = [LIKED_SONGS_URI] (the fetchLibraryTracks op:
     * the collection URI is not a playlist, so fetchPlaylist rejects it). The result carries the playlist header (name,
     * description, cover, total, owner, editable) and owner display name when the response has them. Playlist tracks
     * carry their row `uid` (removeFromPlaylist needs it). Page.nextOffset counts skipped (podcast / unavailable) rows.
     */
    suspend fun playlistTracks(uri: String, offset: Int = 0, limit: Int = 100): PlaylistTracks = withContext(AppDispatchers.io) {
        if (uri == LIKED_SONGS_URI) {
            val vars = jsonOf("offset" to offset, "limit" to limit, "order" to null)
            return@withContext SpotifyParse.parseSavedTracks(pathfinder("fetchLibraryTracks", vars), offset, labels)
        }
        // fetchPlaylist requires enableWatchFeedEntrypoint (Boolean!) — omitting it 400s with VALIDATION_INVALID_TYPE_VARIABLE.
        val vars = jsonOf("uri" to uri, "offset" to offset, "limit" to limit, "enableWatchFeedEntrypoint" to false)
        SpotifyParse.parsePlaylistTracks(pathfinder("fetchPlaylist", vars, acceptLanguage(SettingsStore.current.language)), uri, offset, username())
    }

    /** An album with its tracklist (first 50 tracks). Throws ApiError(404) when Spotify has no such album. */
    suspend fun album(uri: String): Album = withContext(AppDispatchers.io) {
        val data = pathfinder("getAlbum", jsonOf("uri" to uri, "offset" to 0, "limit" to 50), acceptLanguage(SettingsStore.current.language))
        SpotifyParse.parseAlbum(data) ?: throw ApiError(404, "album not found")
    }

    /** Artist overview: top tracks, popular releases, related artists, monthly listeners. */
    suspend fun artistPage(uri: String): ArtistPage = withContext(AppDispatchers.io) {
        SpotifyParse.parseArtistPage(pathfinder("queryArtistOverview", jsonOf("uri" to uri, "locale" to "", "preReleaseV2" to false)))
    }

    suspend fun search(query: String, limit: Int = 12): SearchResults = withContext(AppDispatchers.io) {
        val vars = jsonOf(
            "searchTerm" to query,
            "offset" to 0,
            "limit" to limit,
            "numberOfTopResults" to 5,
            "includeAudiobooks" to false,
            "includeArtistHasConcertsField" to false,
            "includePreReleases" to false,
            "includeLocalConcertsField" to false,
            "includeAuthors" to false,
        )
        SpotifyParse.parseSearch(pathfinder("searchDesktop", vars))
    }

    // ---- Home ---------------------------------------------------------------------------------------------------------

    /**
     * The user's personalized Spotify home feed (the web player's "home" query): shelves such as "Made For <you>",
     * "Jump back in", "Recently played", Daily Mixes, Discover Weekly, "Your top mixes"... Items are playlist / album /
     * artist / track cards whose `id` is the spotify: URI (Liked Songs maps to [LIKED_SONGS_URI]). Podcasts, episodes,
     * shows, audiobooks and shelves left empty are skipped. Titles are localized by Spotify (Accept-Language from the
     * UI language). Throws ApiError (412 when the persisted-query hash was purged and could not be healed, 429 when
     * rate-limited).
     */
    suspend fun home(): List<Shelf> = withContext(AppDispatchers.io) {
        val data = homeData(10)
        val shelves = SpotifyParse.parseHome(data, labels)
        // Once per process: a compact shape summary (section / item typenames) so schema drift is visible in the log.
        if (!loggedHome.getAndSet(true)) {
            val sections = SpotifyParse.homeSections(data)
            val summary = StringBuilder()
            for (s in sections) {
                val kinds = sortedMapOf<String, Int>()
                for (it in arr(at(at(s, "sectionItems"), "items"))) {
                    val c = at(it, "content")
                    val k = str(at(c, "data"), "__typename").ifEmpty { str(c, "__typename") }
                    kinds[k] = (kinds[k] ?: 0) + 1
                }
                summary.append("\n  ").append(str(at(s, "data"), "__typename")).append(" \"")
                    .append(SpotifyParse.labelText(at(at(s, "data"), "title"))).append("\":")
                for ((k, n) in kinds) summary.append(' ').append(k).append('=').append(n)
            }
            Log.i(TAG, "home: ${shelves.size} shelves from ${sections.size} sections$summary")
            Log.d(TAG, "home raw (truncated): ${data.dump().take(1500)}")
        }
        shelves
    }

    /** The raw `data` object of the home query (split from [SpotifyParse.parseHome] so fixtures can be dumped / replayed). */
    suspend fun homeData(sectionItemsLimit: Int = 10): JsonElement = withContext(AppDispatchers.io) {
        // Variables exactly as the web player (web-player.da8b87c8.js) sends them: homeEndUserIntegration is a
        // required enum (HomeEndUserIntegration!) — omitting it 400s; sp_t (the anonymous device cookie) may be empty
        // for a logged-in user; facet "" = the default (all) chip; includeEpisodeContentRatingsV2 is a feature flag.
        val vars = jsonOf(
            "homeEndUserIntegration" to "INTEGRATION_WEB_PLAYER",
            "timeZone" to localTimeZone(),
            "sp_t" to "",
            "facet" to "",
            "sectionItemsLimit" to sectionItemsLimit,
            "includeEpisodeContentRatingsV2" to false,
        )
        // Shelf titles in the UI language ("Berkay İçin Derlendi" / "Made For Berkay"), English as the fallback.
        pathfinder("home", vars, acceptLanguage(SettingsStore.current.language))
    }

    // ---- Mutations (Pathfinder) ---------------------------------------------------------------------------------------
    // The web-player token only authorizes api-partner (Pathfinder), NOT api.spotify.com/v1 (which 429s). So the
    // library / playlist writes go through the internal addToLibrary / removeFromLibrary / add*Playlist mutations.
    // ids / uris may be either base62 ids or spotify: URIs. Return true on success (failures are logged, never thrown).

    /** Liked Songs. */
    suspend fun setTracksSaved(ids: List<String>, saved: Boolean): Boolean = withContext(AppDispatchers.io) {
        if (ids.isEmpty()) return@withContext true
        logged("setTracksSaved", false) {
            pathfinder(if (saved) "addToLibrary" else "removeFromLibrary", jsonOf("libraryItemUris" to toUris("track", ids)))
            true
        }
    }

    /** Saved albums. */
    suspend fun setAlbumsSaved(ids: List<String>, saved: Boolean): Boolean = withContext(AppDispatchers.io) {
        if (ids.isEmpty()) return@withContext true
        logged("setAlbumsSaved", false) {
            pathfinder(if (saved) "addToLibrary" else "removeFromLibrary", jsonOf("libraryItemUris" to toUris("album", ids)))
            true
        }
    }

    suspend fun setArtistsFollowed(ids: List<String>, follow: Boolean): Boolean = withContext(AppDispatchers.io) {
        if (ids.isEmpty()) return@withContext true
        logged("setArtistsFollowed", false) {
            pathfinder(if (follow) "addToLibrary" else "removeFromLibrary", jsonOf("libraryItemUris" to toUris("artist", ids)))
            true
        }
    }

    // ---- Playlist items (Pathfinder; web-player bundle web-player.da8b87c8.js, class pj) --------------------------
    //   add:    {playlistUri, playlistItemUris:[uri...], newPosition:{moveType, fromUid}}
    //   remove: {playlistUri, uids:[uid...]}       (row uids from fetchPlaylist content.items[].uid)

    /** Appends tracks (track ids / URIs) to the end of a playlist (Pathfinder addToPlaylist). */
    suspend fun addToPlaylist(playlistUri: String, trackUris: List<String>): Boolean = withContext(AppDispatchers.io) {
        if (trackUris.isEmpty()) return@withContext true
        val vars = jsonOf(
            "playlistUri" to toUri("playlist", playlistUri),
            "playlistItemUris" to toUris("track", trackUris),
            "newPosition" to jsonOf("moveType" to "BOTTOM_OF_PLAYLIST", "fromUid" to null),
        )
        logged("addToPlaylist", false) {
            pathfinder("addToPlaylist", vars)
            true
        }
    }

    /**
     * Removes playlist ROWS by their uid (Track.uid from playlistTracks) — not by track uri: a playlist may hold the
     * same track twice, and the web player's removeFromPlaylist takes {playlistUri, uids}.
     */
    suspend fun removeFromPlaylist(playlistUri: String, uids: List<String>): Boolean = withContext(AppDispatchers.io) {
        val clean = uids.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return@withContext uids.isEmpty()
        val vars = jsonOf("playlistUri" to toUri("playlist", playlistUri), "uids" to clean)
        logged("removeFromPlaylist", false) {
            pathfinder("removeFromPlaylist", vars)
            true
        }
    }

    // ---- Playlist service (spclient /playlist/v2) — what the web player's RootlistAPI / playlist editor call -----------

    /**
     * Creates a playlist owned by the user and adds it to the top of their library (rootlist). Returns the new
     * spotify:playlist: URI, or "" on failure.
     */
    suspend fun createPlaylist(name: String, description: String = ""): String = withContext(AppDispatchers.io) {
        if (name.isEmpty()) return@withContext ""
        val uri = logged("createPlaylist", "") {
            val body = jsonOf("ops" to jsonArrayOf(nameOp(name, description))) // Ops{ops} (no info)
            val created = playlistService("POST", "/playlist", body)
            val u = str(created, "uri")
            if (!u.startsWith("spotify:playlist:")) {
                Log.w(TAG, "createPlaylist: no playlist uri in the response")
                ""
            } else u
        }
        if (uri.isEmpty()) return@withContext ""
        // A new playlist exists on its own; the web player then adds it to the top of the user's rootlist (library).
        try {
            val item = jsonOf("uri" to uri, "attributes" to jsonOf("timestamp" to System.currentTimeMillis().toString()))
            val ops = jsonArrayOf(jsonOf("kind" to "ADD", "add" to jsonOf("addFirst" to true, "items" to jsonArrayOf(item))))
            rootlistChanges(ops)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "createPlaylist: adding $uri to the library failed: ${e.message}")
            return@withContext ""
        }
        Log.i(TAG, "created playlist $uri")
        uri
    }

    suspend fun renamePlaylist(playlistUri: String, name: String): Boolean = withContext(AppDispatchers.io) {
        if (name.isEmpty() || playlistUri.isEmpty()) return@withContext false
        logged("renamePlaylist", false) {
            playlistService("POST", "/playlist/" + SpotifyParse.lastSegment(playlistUri) + "/changes", listChanges(jsonArrayOf(nameOp(name, ""))))
            true
        }
    }

    /**
     * Removes the playlist from the user's library (rootlist). For the user's own playlist this is Spotify's "Delete";
     * for someone else's it unfollows it.
     */
    suspend fun deletePlaylist(playlistUri: String): Boolean = withContext(AppDispatchers.io) {
        if (!playlistUri.startsWith("spotify:playlist:")) return@withContext false
        logged("deletePlaylist", false) {
            val rem = jsonOf("itemsAsKey" to true, "items" to jsonArrayOf(jsonOf("uri" to playlistUri)))
            rootlistChanges(jsonArrayOf(jsonOf("kind" to "REM", "rem" to rem)))
            Log.i(TAG, "removed playlist $playlistUri from the library")
            true
        }
    }

    /** Which of [ids] are already saved as Liked Songs (parallel to [ids]; all false on failure). */
    suspend fun tracksSaved(ids: List<String>): List<Boolean> = withContext(AppDispatchers.io) {
        if (ids.isEmpty()) return@withContext emptyList()
        logged("tracksSaved", List(ids.size) { false }) {
            SpotifyParse.parseTracksSaved(pathfinder("areEntitiesInLibrary", jsonOf("uris" to toUris("track", ids))), ids.size)
        }
    }

    // ---- Radio (spclient inspiredby-mix) -------------------------------------------------------------------------------

    /**
     * Spotify's radio for a seed (spotify:track: / artist: / album: / playlist: URI): the URI of the playlist the web
     * player opens for "Go to song radio" (spclient inspiredby-mix/v2/seed_to_playlist), whose tracks come from
     * [playlistTracks]. "" when Spotify has no radio for the seed. Throws ApiError.
     */
    suspend fun radioPlaylist(seedUri: String): String = withContext(AppDispatchers.io) {
        // Sent as-is like the web player does (base62 ids and colons only).
        if (!seedUri.startsWith("spotify:") || !seedUri.all { it in BASE62_COLON })
            throw ApiError(0, "Spotify radio: invalid seed $seedUri")
        val url = "https://spclient.wg.spotify.com/inspiredby-mix/v2/seed_to_playlist/$seedUri?response-format=json"
        SpotifyParse.parseRadio(spclient("GET", url, null))
    }

    // ---- Track metadata (spclient metadata/4) --------------------------------------------------------------------------

    /**
     * One track (spotify:track: URI or base62 id) from the web player's metadata service (spclient metadata/4, JSON):
     * name, artists, album (URI, name, cover) and duration, e.g. for a pasted track link. The web player has no
     * Pathfinder query for a lone track in its main bundle. Throws ApiError (404 when Spotify doesn't know the id).
     */
    suspend fun track(uri: String): Track = withContext(AppDispatchers.io) {
        val id = if (uri.startsWith("spotify:track:")) uri.substring(14) else uri
        val gid = SpotifyParse.gidFromId(id)
        if (gid.isEmpty()) throw ApiError(0, "Spotify track: invalid id $uri")
        val j = spclient("GET", "https://spclient.wg.spotify.com/metadata/4/track/$gid?market=from_token", null)
        val t = SpotifyParse.parseTrackMetadata(j)
        if (t.id.isEmpty() || t.name.isEmpty()) throw ApiError(404, "Spotify track not found: $uri")
        t
    }

    // ---- Lyrics (spclient color-lyrics) --------------------------------------------------------------------------------

    /**
     * Spotify's lyrics for a track (the web player's color-lyrics service: Musixmatch and other providers), as the raw
     * JSON body for the lyrics module's Spotify parser: {"lyrics":{"syncType":"LINE_SYNCED","lines":[{"startTimeMs":
     * "960","words":"...","syllables":[],"endTimeMs":"0"}...],"provider":"MusixMatch",...},"colors":{...}}.
     * "" when Spotify has none (404). Throws ApiError otherwise (401 / 403 / 429).
     */
    suspend fun trackLyrics(trackId: String): String = withContext(AppDispatchers.io) {
        var id = trackId
        val colon = id.lastIndexOf(':')
        if (colon >= 0) id = id.substring(colon + 1)
        if (id.isEmpty() || !id.all { it in BASE62_CHARS }) throw ApiError(0, "Spotify lyrics: invalid track id $trackId")
        val url = "https://spclient.wg.spotify.com/color-lyrics/v2/track/$id?format=json&vocalRemoval=false&market=from_token"
        try {
            val j = spclient("GET", url, null, quietStatus = 404)
            if (j is JsonNull) "" else j.dump()
        } catch (e: ApiError) {
            if (e.status == 404) "" else throw e
        }
    }

    companion object {
        private const val TAG = "spotify"
        const val ENDPOINT = "https://api-partner.spotify.com/pathfinder/v2/query"

        /** The web player's playlist service (create / rename / rootlist edits). Same Bearer token as Pathfinder. */
        const val PLAYLIST_SERVICE = "https://spclient.wg.spotify.com/playlist/v2"

        private const val BASE62_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val BASE62_COLON = "$BASE62_CHARS:"

        private val loggedShape = AtomicBoolean(false)
        private val loggedHome = AtomicBoolean(false)

        fun toUri(kind: String, idOrUri: String): String =
            if (idOrUri.startsWith("spotify:")) idOrUri else "spotify:$kind:$idOrUri"

        fun toUris(kind: String, ids: List<String>): List<String> = ids.map { toUri(kind, it) }

        /** `flatten`: playlists inside folders are listed too (folder rows themselves are skipped by the parser). */
        fun libraryVariables(filter: String, offset: Int = 0, flatten: Boolean = false): JsonObject = jsonOf(
            "order" to null,
            "textFilter" to "",
            "features" to listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "PRERELEASES_V2", "EVENTS"),
            "filters" to listOf(filter),
            "limit" to 50,
            "offset" to offset,
            "flatten" to flatten,
            "expandedFolders" to emptyList<String>(),
            "folderUri" to null,
            "includeFoldersWhenFlattening" to true,
        )

        // The server parses these strictly: send exactly what the web player's ts-proto toJSON emits — defaults (false,
        // empty lists) omitted, and no field the message doesn't have (the create body is `Ops`, which has no `info`).
        private fun webPlayerInfo() = jsonOf("source" to jsonOf("client" to "WEBPLAYER"))

        /** ListChanges{deltas:[Delta{ops, info}]} */
        fun listChanges(ops: JsonElement): JsonObject =
            jsonOf("deltas" to jsonArrayOf(jsonOf("ops" to ops, "info" to webPlayerInfo())))

        fun nameOp(name: String, description: String): JsonObject {
            val values = if (description.isNotEmpty()) jsonOf("name" to name, "description" to description) else jsonOf("name" to name)
            return jsonOf(
                "kind" to "UPDATE_LIST_ATTRIBUTES",
                "updateListAttributes" to jsonOf("newAttributes" to jsonOf("values" to values)),
            )
        }

        /**
         * IANA zone of this device ("Europe/Istanbul"): Spotify picks the daily / "today" shelves by it. Falls back to
         * Istanbul (the app's audience) when unavailable.
         */
        fun localTimeZone(): String = runCatching { TimeZone.getDefault().id }.getOrNull()
            ?.takeIf { it.contains('/') } ?: "Europe/Istanbul"

        /** The app's UI languages and their locales (core/I18n kLanguages). */
        private val LANGUAGE_LOCALES = mapOf(
            "tr" to "tr-TR", "en" to "en-US", "de" to "de-DE", "es" to "es-ES", "fr" to "fr-FR", "pt" to "pt-BR",
            "ru" to "ru-RU", "uk" to "uk-UA", "id" to "id-ID", "ja" to "ja-JP", "ko" to "ko-KR",
        )

        /**
         * "tr-TR,tr;q=0.9,en;q=0.5" for UI language [language] ("" = the system language). A code the app ships keeps
         * the C++ locale; otherwise the system locale is used as is.
         */
        fun acceptLanguage(language: String, system: Locale = Locale.getDefault()): String {
            val code = language.ifEmpty { system.language }.ifEmpty { "en" }
            val locale = LANGUAGE_LOCALES[code]
                ?: if (language.isEmpty() && system.country.isNotEmpty()) "${system.language}-${system.country}" else code
            return "$locale,$code;q=0.9,en;q=0.5"
        }
    }
}
