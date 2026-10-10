package com.app.shadetube.playback.features

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.Log
import com.app.shadetube.core.RepeatMode
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.playback.PlayContext
import com.app.shadetube.playback.Player
import com.app.shadetube.playback.PlayerNotice
import com.app.shadetube.playback.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Spotify's radio for the player (AppGraph implements it over spotify.Session / SpotifyApi). Main-safe suspend
 * functions; failures throw [RadioSourceError] (with the HTTP status when there was one) or any IOException.
 */
interface RadioSource {
    /** Logged in (radios need the user's Spotify session). */
    val available: Boolean

    /** The radio playlist of [seedUri] (spotify:track: / album: / artist: / playlist:), "" when Spotify has none (404). */
    suspend fun radioPlaylist(seedUri: String): String

    /** A playlist's tracks (first page). */
    suspend fun playlistTracks(uri: String, offset: Int, limit: Int): List<Track>
}

class RadioSourceError(val status: Int, message: String) : Exception(message)

/**
 * Spotify radio + endless playback (port of app/Radio).
 *
 * Radio: [RadioSource.radioPlaylist] (the web player's "Go to song radio", spclient seed_to_playlist) -> that playlist's
 * first 100 tracks -> kara liste filtered -> replaces the queue (context "playlist:<radio uri>", name "<seed name>"; the
 * UI shows it as "<name> radyosu"). Track, album and artist seeds have radios (an album gets its artist's); Spotify
 * serves none for playlists, so a playlist is seeded with its first unblocked, playable tracks. Starting the radio of
 * the song that is playing keeps it playing and replaces only what comes after it. A result is dropped when the user
 * started another queue (or logged out) while it loaded.
 *
 * Endless playback (Settings.endlessPlayback): when a track starts with at most one playable item after it and repeat
 * is off (Player.onQueueLow), the radio of the current Spotify track (else the last Spotify track of the queue) is
 * appended — only tracks not in the queue yet and not blocked. One fetch at a time; each seed is tried once per queue
 * (Player.queueGeneration) and at most [ENDLESS_SEEDS_PER_QUEUE] seeds, so a radio that brings nothing new never loops.
 * The result is re-checked against the setting, repeat and the login before it is appended.
 */
class Radio(
    private val player: Player,
    private val source: RadioSource?,
    private val scope: CoroutineScope,
    private val isBlocked: (Track) -> Boolean,
    private val endlessEnabled: () -> Boolean = { SettingsStore.current.endlessPlayback },
    private val isStation: (String) -> Boolean = { false },
) {
    companion object {
        /** First page of the radio playlist (Spotify builds ~50). */
        const val RADIO_TRACKS = 100

        /** Radio fetches per queue before endless playback gives up. */
        const val ENDLESS_SEEDS_PER_QUEUE = 8

        /** Tracks of a playlist tried as the seed of its radio. */
        const val PLAYLIST_SEEDS = 2

        /**
         * The radio seed for a catalog id: the id itself when it is a Spotify track / album / artist / playlist URI
         * ("spotify:<kind>:<base62>"), else "" — MusicBrainz ids, "local:" files, Liked Songs, downloads of non-Spotify
         * tracks. Callers hide their radio entry for "".
         */
        fun radioSeed(id: String): String {
            for (kind in arrayOf("spotify:track:", "spotify:album:", "spotify:artist:", "spotify:playlist:")) {
                if (id.length <= kind.length || !id.startsWith(kind)) continue
                val rest = id.substring(kind.length)
                val base62 = rest.length <= 64 && rest.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
                return if (base62) id else ""
            }
            return ""
        }

        /** What the player may use from a radio: playable, not blocked, not in [seen] (track ids; updated). */
        fun usableTracks(tracks: List<Track>, seen: MutableSet<String>, isBlocked: (Track) -> Boolean): List<Track> {
            val out = ArrayList<Track>()
            for (t in tracks) {
                if (!t.playable || t.name.isEmpty() || isBlocked(t)) continue
                if (t.id.isNotEmpty() && !seen.add(t.id)) continue
                out.add(t)
            }
            return out
        }

        private fun isTrackSeed(t: Track) = t.playable && t.id.startsWith("spotify:track:") && radioSeed(t.id).isNotEmpty()
    }

    private class Fetch(
        /** The radio playlist ("" = Spotify has no radio for the seeds). */
        var uri: String = "",
        var tracks: List<Track> = emptyList(),
        /** A playlist without a radio: its first tracks (the caller picks seeds). */
        var candidates: List<Track> = emptyList(),
        var failed: Boolean = false,
        /** RadioSourceError status (0 = network / other). */
        var status: Int = 0,
        var error: String = "",
    )

    /** The radio playlist of [seed], "" when Spotify has none (empty answer or 404). */
    private suspend fun radioOf(api: RadioSource, seed: String): String = try {
        api.radioPlaylist(seed)
    } catch (e: RadioSourceError) {
        if (e.status == 404) "" else throw e
    }

    /**
     * The first of [seeds] that has a radio -> that radio's first page. Spotify serves no radio for playlists (user and
     * generated playlists answer with nothing), so a lone playlist seed without one returns its first tracks as
     * candidates instead. Cancellation (a newer request) stops it between requests.
     */
    private suspend fun fetchRadio(api: RadioSource, seeds: List<String>, limit: Int = RADIO_TRACKS): Fetch {
        val f = Fetch()
        try {
            for (seed in seeds) {
                f.uri = radioOf(api, seed)
                if (f.uri.isNotEmpty()) break
                if (seeds.size == 1 && seed.startsWith("spotify:playlist:")) {
                    f.candidates = api.playlistTracks(seed, 0, 20)
                    return f
                }
            }
            if (f.uri.isNotEmpty()) f.tracks = api.playlistTracks(f.uri, 0, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RadioSourceError) {
            f.failed = true
            f.status = e.status
            f.error = e.message ?: ""
        } catch (e: Exception) {
            f.failed = true
            f.error = e.message ?: e.javaClass.simpleName
        }
        return f
    }

    val available: Boolean get() = source?.available == true

    // ---- radio ---------------------------------------------------------------------------------------------------------

    private var radioJob: Job? = null

    /** One "Şarkı radyosu" / "Radyo başlat" request, checked again when its result arrives. */
    private class Request(
        val seed: String,
        val name: String,
        /** Player.queueGeneration when asked: another queue started meanwhile wins. */
        val generation: Long,
        /** The seed is the song that was playing: the radio follows it instead of restarting. */
        val seedPlaying: Boolean,
    )

    /** Starts the radio of [seedUri] ([seedName] = what the queue header calls it). */
    fun startRadio(seedUri: String, seedName: String) {
        val seed = radioSeed(seedUri)
        if (seed.isEmpty()) {
            player.emitNotice(PlayerNotice.RadioUnavailable)
            return
        }
        val api = source
        if (api == null || !api.available) {
            player.emitNotice(PlayerNotice.RadioNeedsSpotify)
            return
        }
        val cur = player.currentTrack
        val st = player.statusNow
        val req = Request(
            seed = seed,
            name = seedName.ifEmpty { "Spotify" },
            generation = player.queueGeneration,
            seedPlaying = cur != null && cur.id == seed && st != Status.Idle && st != Status.Error,
        )
        player.emitNotice(PlayerNotice.RadioPreparing)
        Log.i("radio", "start: $seed")
        radioJob?.cancel() // a newer request supersedes the one in flight
        radioJob = scope.launch { run(api, req, listOf(seed), firstStep = true) }
    }

    private suspend fun run(api: RadioSource, req: Request, seeds: List<String>, firstStep: Boolean) {
        val r = fetchRadio(api, seeds)
        if (player.queueGeneration != req.generation || !available) {
            Log.i("radio", "${req.seed}: result dropped (another queue started / logged out)")
            return
        }
        if (r.failed) {
            Log.w("radio", "${req.seed} failed: ${r.error}")
            player.emitNotice(PlayerNotice.RadioFailed(r.status))
            return
        }
        if (r.uri.isEmpty()) {
            // A playlist without its own radio: the radio of its first unblocked tracks.
            val next = ArrayList<String>()
            if (firstStep) {
                for (t in r.candidates) if (next.size < PLAYLIST_SEEDS && isTrackSeed(t) && !isBlocked(t)) next.add(t.id)
            }
            if (next.isNotEmpty()) {
                run(api, req, next, firstStep = false)
                return
            }
            player.emitNotice(PlayerNotice.RadioNotOffered)
            return
        }
        var tracks = usableTracks(r.tracks, HashSet(), isBlocked)
        if (tracks.isEmpty()) {
            player.emitNotice(PlayerNotice.RadioEmpty)
            return
        }
        Log.i("radio", "${req.seed} -> ${r.uri} (${tracks.size} tracks)")
        val context = PlayContext("playlist:${r.uri}", req.name)
        // The radio of the song that was playing: whatever plays now (that song, or the one after it if it ended
        // meanwhile) keeps playing and the radio becomes what comes next.
        val st = player.statusNow
        if (req.seedPlaying && player.currentTrack != null && st != Status.Idle && st != Status.Error) {
            tracks = tracks.filter { it.id != req.seed }
            player.replaceUpcoming(tracks, context)
            player.emitNotice(PlayerNotice.RadioReady(req.name))
            return
        }
        player.playContext(tracks, 0, context)
    }

    // ---- endless playback --------------------------------------------------------------------------------------------

    private var endlessJob: Job? = null
    private var endlessGeneration = -1L
    private var endlessInFlight = false
    private val endlessTried = HashSet<String>()

    /**
     * Checks now whether the queue needs more (e.g. right after the setting was turned on). Only the player's own
     * queue-low hook ([fromPlayer]) may resume a queue that has already ended.
     */
    fun endlessCheck(fromPlayer: Boolean = false) {
        val api = source ?: return
        if (!endlessEnabled() || !api.available) return
        val cur = player.currentTrack ?: return
        if (player.repeat != RepeatMode.Off) return
        if (player.isLive || isStation(cur.id)) return // internet radio: songs are never appended to a list of stations
        if (isPodcastId(cur.id)) return // nor after a podcast episode
        // A queue that already ended only resumes through the player itself (its queue-low hook), never because a
        // setting or the kara liste changed later.
        if (!fromPlayer && player.statusNow == Status.Idle) return
        if (player.queueGeneration != endlessGeneration) {
            endlessJob?.cancel()
            endlessGeneration = player.queueGeneration
            endlessInFlight = false
            endlessTried.clear()
        }
        if (endlessInFlight || player.remainingPlayable(2) > 1 || endlessTried.size >= ENDLESS_SEEDS_PER_QUEUE) return
        // Seed: the current track, else the most recent Spotify track of the queue. Blocked tracks never seed.
        var seed = ""
        fun consider(t: Track): Boolean {
            val s = if (t.id.startsWith("spotify:track:")) radioSeed(t.id) else ""
            if (s.isEmpty() || s in endlessTried || isBlocked(t)) return false
            seed = s
            return true
        }
        if (!consider(cur)) {
            val items = player.items
            val order = player.order
            for (i in order.indices.reversed()) if (consider(items[order[i]])) break
        }
        if (seed.isEmpty()) return // not a Spotify queue, or every seed was tried
        endlessTried.add(seed)
        endlessInFlight = true
        val gen = endlessGeneration
        Log.i("radio", "endless: queue low, fetching the radio of $seed")
        endlessJob = scope.launch {
            val r = fetchRadio(api, listOf(seed))
            endlessInFlight = false
            if (player.queueGeneration != gen) return@launch
            if (r.failed) { // no retry here: the next track start may try another seed
                Log.w("radio", "endless: $seed failed: ${r.error}")
                return@launch
            }
            val seen = HashSet<String>()
            for (t in player.items) if (t.id.isNotEmpty()) seen.add(t.id)
            val tracks = usableTracks(r.tracks, seen, isBlocked)
            if (tracks.isEmpty()) {
                Log.i("radio", "endless: nothing new from $seed")
                endlessCheck(fromPlayer) // another seed, bounded by `tried`
                return@launch
            }
            // The setting, repeat or the login may have changed while the radio loaded.
            if (!endlessEnabled() || player.repeat != RepeatMode.Off || !available) {
                Log.i("radio", "endless: dropped the radio of $seed (setting / repeat / login changed)")
                endlessTried.remove(seed) // turning it back on may fetch it again
                return@launch
            }
            val n = player.extend(tracks)
            Log.i("radio", "endless: appended $n tracks (radio of $seed)")
            if (n > 0) player.emitNotice(PlayerNotice.EndlessAppended(n))
        }
    }

    /** App exit / logout: radio and endless work still in flight is dropped. */
    fun cancelAll() {
        radioJob?.cancel()
        endlessJob?.cancel()
        endlessInFlight = false
    }
}
