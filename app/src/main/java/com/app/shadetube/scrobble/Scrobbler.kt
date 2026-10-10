package com.app.shadetube.scrobble

import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Hash
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.HttpResponse
import com.app.shadetube.core.Log
import com.app.shadetube.core.SecureStore
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.appScope
import com.app.shadetube.core.dump
import com.app.shadetube.core.get
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/**
 * Scrobbling to Last.fm and ListenBrainz (port of app/Scrobbler).
 *
 *  - Last.fm: the user brings their own API key + secret (last.fm/api/account/create). Desktop web-auth:
 *    auth.getToken -> the user approves https://www.last.fm/api/auth/?api_key=..&token=.. in the browser ->
 *    auth.getSession -> session key. Calls are signed (MD5) and POSTed.
 *  - ListenBrainz: a user token (listenbrainz.org/settings), validated with /1/validate-token.
 *
 * Scrobble rule (both services): the track is longer than 30 s and the REAL listened time (accumulated only while
 * playing, from position deltas; seeks are ignored) reaches min(50% of the duration, 4 minutes). Each play is
 * scrobbled once, stamped with the unix time the track started playing. Radio stations and podcast episodes are not
 * songs: never scrobbled ([onTrackChanged] stops the play).
 *
 * Credentials persist encrypted ([SecureStore], Android Keystore) as JSON in `scrobble.dat` (the same content as the
 * C++ plaintext before DPAPI). They are never logged. All network runs on workers and nothing throws out of this
 * class. Failed scrobbles stay in an in-memory retry queue per service and are re-sent on the next scrobble / after
 * the next successful submission. Thread-safe.
 */
data class ScrobbleTrack(
    /** Stable track id: onTrackStarted() with the same id may continue the same play. */
    val id: String = "",
    val title: String = "",
    /** Primary artist (Last.fm matches best on a single artist). */
    val artist: String = "",
    /** Full credit "A, B" for ListenBrainz; empty = artist. */
    val artistCredit: String = "",
    val album: String = "",
    /** 0 = unknown (then learned from onProgress's duration argument). */
    val durationMs: Long = 0,
) {
    companion object {
        /** First artist as `artist`, artistLine as `artistCredit`. */
        fun from(t: Track): ScrobbleTrack {
            val credit = t.artistLine
            val first = t.artists.firstOrNull()?.name.orEmpty()
            return ScrobbleTrack(t.id, t.name, first.ifEmpty { credit }, credit, t.album.name, t.durationMs.toLong())
        }
    }
}

object ScrobbleSign {
    /** Lowercase hex MD5 of the UTF-8 bytes. */
    fun md5Hex(text: String): String = Hash.md5Hex(text)

    /**
     * Last.fm api_sig: md5(concat(name + value for every param sorted by name, excluding "format" and "callback") +
     * secret), lowercase hex. Strings are hashed as their UTF-8 bytes.
     */
    fun lastfmSignature(params: List<Pair<String, String>>, secret: String): String {
        val sorted = params.filter { it.first != "format" && it.first != "callback" }
            .sortedWith { a, b -> compareBytes(a.first, b.first) } // stable, byte order like std::string <
        val sb = StringBuilder()
        for ((k, v) in sorted) sb.append(k).append(v)
        sb.append(secret)
        return md5Hex(sb.toString())
    }

    private fun compareBytes(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return x.size - y.size
    }
}

/** Pure listened-time accounting (no I/O, no clock of its own). */
class ListenClock {
    var durationMs = 0L
        private set
    var listenedMs = 0L
        private set
    private var lastPosMs = 0L
    private var lastWallMs = 0L
    private var lastPlaying = false
    var fired = false
        private set

    /** Starts a new play. wallMs: the monotonic clock "now"; the position baseline is 0. */
    fun reset(durationMs: Long, wallMs: Long) {
        this.durationMs = maxOf(0L, durationMs)
        listenedMs = 0
        lastPosMs = 0
        lastWallMs = wallMs
        lastPlaying = false
        fired = false
    }

    /** Only fills in an unknown duration (the catalog duration wins once known). */
    fun learnDuration(durationMs: Long) {
        if (this.durationMs <= 0 && durationMs > 0) this.durationMs = durationMs
    }

    /** min(duration / 2, 4 min); 4 min when the duration is unknown; -1 when the track is too short. */
    fun thresholdMs(): Long {
        if (durationMs <= 0) return MAX_THRESHOLD_MS // unknown length: the 4-minute rule alone
        if (durationMs <= MIN_TRACK_MS) return -1
        return minOf(durationMs / 2, MAX_THRESHOLD_MS)
    }

    /**
     * Feeds one sample. A forward jump larger than the elapsed wall time (+tolerance) or any backward jump is a seek
     * and is not counted. Returns true exactly once: on the sample that crosses the threshold.
     */
    fun update(positionMs: Long, playing: Boolean, wallMs: Long): Boolean {
        val delta = positionMs - lastPosMs
        val elapsed = maxOf(0L, wallMs - lastWallMs)
        // Count only real playback: the position advanced while playing, by no more than the wall clock did.
        if ((playing || lastPlaying) && delta > 0 && delta <= elapsed + SEEK_TOLERANCE_MS) listenedMs += delta
        lastPosMs = positionMs
        lastWallMs = wallMs
        lastPlaying = playing
        val threshold = thresholdMs()
        if (!fired && threshold >= 0 && listenedMs >= threshold) {
            fired = true
            return true
        }
        return false
    }

    companion object {
        /** Tracks <= 30 s never scrobble. */
        const val MIN_TRACK_MS = 30_000L
        /** 4 minutes. */
        const val MAX_THRESHOLD_MS = 240_000L
        /** Position may run this far ahead of the wall clock. */
        const val SEEK_TOLERANCE_MS = 1_500L
    }
}

/** Where the credentials JSON lives (encrypted on the device; tests use their own). */
interface CredentialStore {
    /** The plaintext, "" when there is none or it can't be decrypted. */
    fun load(): String
    fun save(plain: String): Boolean
    fun clear()

    /** `scrobble.dat` through [SecureStore] (Android Keystore AES-GCM). */
    class Secure(private val name: String = "scrobble.dat") : CredentialStore {
        override fun load() = SecureStore.load(name)
        override fun save(plain: String) = SecureStore.save(name, plain)
        override fun clear() = SecureStore.clear(name)
    }
}

/** Why a connect step failed (the UI maps it to a message). */
sealed interface ScrobbleError {
    /** Enter the Last.fm API key and shared secret first. */
    data object NoKeys : ScrobbleError

    /** Open the approval page with "Bağlan" first (no pending token). */
    data object NoPendingAuth : ScrobbleError

    /** The service could not be reached. */
    data object Network : ScrobbleError

    /** A reply without the expected fields. */
    data object UnexpectedReply : ScrobbleError

    /** The API key changed while the request ran. */
    data object KeyChanged : ScrobbleError

    /** Last.fm's own error ([code] -1 = an unreadable reply or an HTTP failure without a Last.fm error). */
    data class Lastfm(val code: Int, val message: String, val http: Int) : ScrobbleError {
        val kind: LastfmErrorKind
            get() = when (code) {
                10, 26 -> LastfmErrorKind.InvalidApiKey
                13 -> LastfmErrorKind.BadSignature
                14 -> LastfmErrorKind.NotAuthorized
                4, 15 -> LastfmErrorKind.TokenExpired
                11, 16 -> LastfmErrorKind.Unavailable
                29 -> LastfmErrorKind.RateLimited
                else -> LastfmErrorKind.Other
            }
    }

    /** Paste the ListenBrainz token (listenbrainz.org/settings). */
    data object EmptyToken : ScrobbleError

    /** The token was rejected. */
    data object InvalidToken : ScrobbleError

    /** ListenBrainz answered 5xx / 429. */
    data class ListenBrainzUnavailable(val http: Int) : ScrobbleError
}

enum class LastfmErrorKind {
    /** 10 / 26: check the key from last.fm/api/account/create. */
    InvalidApiKey,
    /** 13: check the shared secret. */
    BadSignature,
    /** 14: "Yes, allow access" not clicked yet. */
    NotAuthorized,
    /** 4 / 15: the approval expired; start over. */
    TokenExpired,
    /** 11 / 16: try again later. */
    Unavailable,
    /** 29. */
    RateLimited,
    /** Last.fm's message (English) or the HTTP status. */
    Other,
}

sealed interface ScrobbleResult<out T> {
    data class Ok<T>(val value: T) : ScrobbleResult<T>
    data class Failed(val error: ScrobbleError) : ScrobbleResult<Nothing>
}

/** What the settings screen shows. */
data class ScrobbleState(
    val lastfmHasKeys: Boolean = false,
    val lastfmConnected: Boolean = false,
    val lastfmUser: String = "",
    /** For pre-filling the settings field (the secret is never exposed). */
    val lastfmApiKey: String = "",
    val lastfmHasSecret: Boolean = false,
    /** beginAuth succeeded; waiting for finishAuth. */
    val lastfmAuthPending: Boolean = false,
    val listenbrainzConnected: Boolean = false,
    val listenbrainzUser: String = "",
    val pendingLastfm: Int = 0,
    val pendingListenbrainz: Int = 0,
)

class Scrobbler(
    private val store: CredentialStore = CredentialStore.Secure(),
    private val scope: CoroutineScope = appScope,
    /** Master switch (Settings "scrobbleEnabled"): false = send nothing. */
    private val isEnabled: () -> Boolean = { SettingsStore.current.scrobbleEnabled },
    /** Monotonic milliseconds used for seek detection. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val unixNow: () -> Long = { System.currentTimeMillis() / 1000 },
    private val lastfmApi: String = LASTFM_API,
    private val listenbrainzApi: String = LB_API,
    private val client: OkHttpClient = Http.client,
) {
    private class Pending(val seq: Long, val track: ScrobbleTrack, val timestamp: Long)

    private class LfmReply(val http: Int, var error: Int, var message: String, val body: JsonElement?)

    private val lock = Any()

    // Credentials (never logged).
    private var lfmKey = ""
    private var lfmSecret = ""
    private var lfmSession = ""
    private var lfmUser = ""
    private var lfmPendingToken = ""
    private var lbToken = ""
    private var lbUser = ""

    // Current play.
    private var active = false
    /** First playing sample seen ("now playing" sent). */
    private var playStarted = false
    private var track = ScrobbleTrack()
    private var startedAtUnix = 0L
    private val listen = ListenClock()
    /** Last sampled position of the current play. */
    private var lastPositionMs = 0L
    /** Same id started again: the next sample tells a repeat from a re-resolve. */
    private var restartCheck = false
    /** Monotonic time of that onTrackStarted (start of a repeat). */
    private var restartWallMs = 0L

    // Retry queues (oldest first) + in-flight guards.
    private val lfmQueue = ArrayDeque<Pending>()
    private val lbQueue = ArrayDeque<Pending>()
    private var lfmBusy = false
    private var lbBusy = false
    private var seq = 0L
    /** Bumped on (dis)connect so stale continuations are ignored. */
    private var lfmGen = 0L
    private var lbGen = 0L

    private val stateFlow = MutableStateFlow(ScrobbleState())

    /** Connections and queues (replaces the C++ onChanged). */
    val state: StateFlow<ScrobbleState> = stateFlow.asStateFlow()

    /** A play qualified (while enabled): the track and its start (unix seconds). Called on the caller's thread. */
    @Volatile
    var onScrobble: ((ScrobbleTrack, Long) -> Unit)? = null

    private fun changed() {
        stateFlow.value = ScrobbleState(
            lastfmHasKeys = lastfmHasKeys(), lastfmConnected = lastfmConnected(), lastfmUser = lfmUser, lastfmApiKey = lfmKey,
            lastfmHasSecret = lfmSecret.isNotEmpty(), lastfmAuthPending = lfmPendingToken.isNotEmpty(),
            listenbrainzConnected = lbToken.isNotEmpty(), listenbrainzUser = lbUser,
            pendingLastfm = lfmQueue.size, pendingListenbrainz = lbQueue.size,
        )
    }

    /** Reads scrobble.dat (tiny). Call once at startup. */
    fun load() = synchronized(lock) {
        try {
            val plain = store.load()
            if (plain.isEmpty()) return@synchronized
            val j = parseJsonOrNull(plain)
            if (j !is JsonObject) {
                Log.w(TAG, "scrobble.dat is unreadable; ignoring it")
                return@synchronized
            }
            (j["lastfm"] as? JsonObject)?.let { l ->
                lfmKey = jstr(l, "apiKey")
                lfmSecret = jstr(l, "secret")
                lfmSession = jstr(l, "sessionKey")
                lfmUser = jstr(l, "user")
            }
            (j["listenbrainz"] as? JsonObject)?.let { b ->
                lbToken = jstr(b, "token")
                lbUser = jstr(b, "user")
            }
            ++lfmGen
            ++lbGen
            Log.i(TAG, "credentials loaded (last.fm: ${if (lastfmConnected()) "connected" else if (lastfmHasKeys()) "keys only" else "off"}, " +
                "listenbrainz: ${if (lbToken.isNotEmpty()) "connected" else "off"})")
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
        } finally {
            changed()
        }
    }

    private fun save() {
        try {
            if (lfmKey.isEmpty() && lfmSecret.isEmpty() && lfmSession.isEmpty() && lbToken.isEmpty()) {
                store.clear()
                return
            }
            val j = JsonObject(
                linkedMapOf(
                    "v" to JsonPrimitive(1),
                    "lastfm" to JsonObject(
                        linkedMapOf(
                            "apiKey" to JsonPrimitive(lfmKey), "secret" to JsonPrimitive(lfmSecret),
                            "sessionKey" to JsonPrimitive(lfmSession), "user" to JsonPrimitive(lfmUser),
                        ),
                    ),
                    "listenbrainz" to JsonObject(linkedMapOf("token" to JsonPrimitive(lbToken), "user" to JsonPrimitive(lbUser))),
                ),
            )
            if (!store.save(j.dump())) Log.w(TAG, "could not write scrobble.dat")
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    // --- Last.fm ---------------------------------------------------------------------------------------------------

    fun lastfmHasKeys(): Boolean = synchronized(lock) { lfmKey.isNotEmpty() && lfmSecret.isNotEmpty() }
    fun lastfmConnected(): Boolean = synchronized(lock) { lastfmHasKeys() && lfmSession.isNotEmpty() }
    fun lastfmUser(): String = synchronized(lock) { lfmUser }
    fun lastfmApiKey(): String = synchronized(lock) { lfmKey }
    fun lastfmHasSecret(): Boolean = synchronized(lock) { lfmSecret.isNotEmpty() }
    fun lastfmAuthPending(): Boolean = synchronized(lock) { lfmPendingToken.isNotEmpty() }

    /**
     * Stores the user's API key + shared secret (trimmed). An empty secret with an unchanged key keeps the stored
     * secret (so a settings form can leave the secret field blank). Changing the key drops the session.
     */
    fun lastfmSetKeys(apiKeyIn: String, secretIn: String) = synchronized(lock) {
        val apiKey = trimWs(apiKeyIn)
        var secret = trimWs(secretIn)
        if (secret.isEmpty() && apiKey == lfmKey) secret = lfmSecret // blank field = keep the stored secret
        if (apiKey == lfmKey && secret == lfmSecret) return@synchronized
        lfmKey = apiKey
        lfmSecret = secret
        // A session key belongs to the API account that issued it.
        val hadSession = lfmSession.isNotEmpty()
        lfmSession = ""
        lfmUser = ""
        lfmPendingToken = ""
        lfmQueue.clear()
        ++lfmGen
        save()
        Log.i(TAG, "last.fm keys updated${if (hadSession) " (previous session dropped)" else ""}")
        changed()
    }

    /** auth.getToken -> the approval URL to open in the browser. */
    suspend fun lastfmBeginAuth(): ScrobbleResult<String> {
        val (key, secret) = synchronized(lock) {
            if (!lastfmHasKeys()) return ScrobbleResult.Failed(ScrobbleError.NoKeys)
            lfmKey to lfmSecret
        }
        val r = try {
            lastfmCall(listOf("method" to "auth.getToken", "api_key" to key), secret)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "last.fm auth.getToken failed: ${e.message}")
            return ScrobbleResult.Failed(ScrobbleError.Network)
        }
        if (r.error != 0) {
            Log.w(TAG, "last.fm auth.getToken error ${r.error}: ${r.message}")
            return ScrobbleResult.Failed(ScrobbleError.Lastfm(r.error, r.message, r.http))
        }
        val token = jstr(r.body, "token")
        if (token.isEmpty()) return ScrobbleResult.Failed(ScrobbleError.UnexpectedReply)
        synchronized(lock) {
            if (key != lfmKey) return ScrobbleResult.Failed(ScrobbleError.KeyChanged)
            lfmPendingToken = token
            Log.i(TAG, "last.fm auth token issued; waiting for browser approval")
            changed()
        }
        return ScrobbleResult.Ok(LASTFM_AUTH_PAGE + "?api_key=" + Http.urlEncode(key) + "&token=" + Http.urlEncode(token))
    }

    /** auth.getSession with the pending token: Ok(user name) once the session key is stored. */
    suspend fun lastfmFinishAuth(): ScrobbleResult<String> {
        val (key, secret, token) = synchronized(lock) {
            if (!lastfmHasKeys() || lfmPendingToken.isEmpty()) return ScrobbleResult.Failed(ScrobbleError.NoPendingAuth)
            Triple(lfmKey, lfmSecret, lfmPendingToken)
        }
        val r = try {
            lastfmCall(listOf("method" to "auth.getSession", "api_key" to key, "token" to token), secret)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "last.fm auth.getSession failed: ${e.message}")
            return ScrobbleResult.Failed(ScrobbleError.Network)
        }
        if (r.error != 0) {
            Log.w(TAG, "last.fm auth.getSession error ${r.error}: ${r.message}")
            synchronized(lock) {
                if ((r.error == 4 || r.error == 15) && lfmPendingToken == token) {
                    lfmPendingToken = "" // dead token: the user has to start over
                    changed()
                }
            }
            return ScrobbleResult.Failed(ScrobbleError.Lastfm(r.error, r.message, r.http))
        }
        val s = r.body["session"]
        val sk = jstr(s, "key")
        val name = jstr(s, "name")
        if (sk.isEmpty()) return ScrobbleResult.Failed(ScrobbleError.UnexpectedReply)
        synchronized(lock) {
            if (key != lfmKey) return ScrobbleResult.Failed(ScrobbleError.KeyChanged)
            lfmSession = sk
            lfmUser = name
            lfmPendingToken = ""
            ++lfmGen
            save()
            Log.i(TAG, "last.fm connected as $name")
            changed()
            flushLastfm()
        }
        return ScrobbleResult.Ok(name)
    }

    /** Forgets the session (keeps the API key/secret). */
    fun lastfmDisconnect() = synchronized(lock) {
        lfmSession = ""
        lfmUser = ""
        lfmPendingToken = ""
        lfmQueue.clear()
        ++lfmGen
        save()
        Log.i(TAG, "last.fm disconnected")
        changed()
    }

    private fun dropLastfmSession(why: String) {
        Log.w(TAG, "last.fm: $why; session dropped, reconnect in Settings")
        lfmSession = ""
        lfmUser = ""
        ++lfmGen
        save()
        changed()
    }

    // --- ListenBrainz ----------------------------------------------------------------------------------------------

    fun listenbrainzConnected(): Boolean = synchronized(lock) { lbToken.isNotEmpty() }
    fun listenbrainzUser(): String = synchronized(lock) { lbUser }

    /** The ListenBrainz user token ("" when not connected); AppGraph hands it to MusicBrainz for authenticated reads. */
    fun listenbrainzToken(): String = synchronized(lock) { lbToken }

    /** Validates the token; on success stores it: Ok(user name). */
    suspend fun listenbrainzSetToken(tokenIn: String): ScrobbleResult<String> {
        val token = trimWs(tokenIn)
        if (token.isEmpty()) return ScrobbleResult.Failed(ScrobbleError.EmptyToken)
        val v = try {
            withContext(AppDispatchers.io) {
                val r = send(HttpRequest(listenbrainzApi + "validate-token", headers = lbHeaders(token)))
                val j = parseJsonOrNull(r.body)
                var valid = (j?.get("valid") as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
                if (!r.isSuccess) valid = false
                val message = jstr(j, "message").ifEmpty { jstr(j, "error") }
                Triple(r.status, valid, jstr(j, "user_name") to message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "listenbrainz validate-token failed: ${e.message}")
            return ScrobbleResult.Failed(ScrobbleError.Network)
        }
        val (http, valid, userAndMessage) = v
        if (!valid) {
            Log.i(TAG, "listenbrainz token rejected (HTTP $http): ${userAndMessage.second}")
            if (http >= 500 || http == 429) return ScrobbleResult.Failed(ScrobbleError.ListenBrainzUnavailable(http))
            return ScrobbleResult.Failed(ScrobbleError.InvalidToken)
        }
        val user = userAndMessage.first
        synchronized(lock) {
            lbToken = token
            lbUser = user
            ++lbGen
            save()
            Log.i(TAG, "listenbrainz connected as $user")
            changed()
            flushListenbrainz()
        }
        return ScrobbleResult.Ok(user)
    }

    fun listenbrainzDisconnect() = synchronized(lock) {
        lbToken = ""
        lbUser = ""
        lbQueue.clear()
        ++lbGen
        save()
        Log.i(TAG, "listenbrainz disconnected")
        changed()
    }

    private fun dropListenbrainzToken(why: String) {
        Log.w(TAG, "listenbrainz: $why; token dropped, reconnect in Settings")
        lbToken = ""
        lbUser = ""
        ++lbGen
        save()
        changed()
    }

    // --- Playback ----------------------------------------------------------------------------------------------------

    /**
     * The player's current track changed. Radio stations and podcast episodes are no songs: the play stops and
     * nothing is scrobbled (hours of a station would count as one play).
     */
    fun onTrackChanged(t: Track) {
        if (isStationId(t.id) || isPodcastId(t.id)) onStopped() else onTrackStarted(ScrobbleTrack.from(t))
    }

    /**
     * A (new) track became current. Same id as the current play: the next progress sample decides - continuing at
     * about the same position (re-resolve, "Yanlış eşleşme?" switch, resume after an error) is the same play
     * (listened time and "already scrobbled" are kept); restarting from the top (repeat) is a new play.
     */
    fun onTrackStarted(t: ScrobbleTrack) = synchronized(lock) {
        if (active && t.id.isNotEmpty() && t.id == track.id) {
            if (track.durationMs <= 0 && t.durationMs > 0) {
                track = track.copy(durationMs = t.durationMs)
                listen.learnDuration(t.durationMs)
            }
            restartCheck = true
            restartWallMs = clock()
            return@synchronized
        }
        startPlay(t, clock())
    }

    private fun startPlay(t: ScrobbleTrack, wallMs: Long) {
        active = true
        playStarted = false
        restartCheck = false
        track = t
        startedAtUnix = 0
        lastPositionMs = 0
        listen.reset(t.durationMs, wallMs)
    }

    /** Playback stopped / queue cleared / logout: forget the current play (nothing is scrobbled). */
    fun onStopped() = synchronized(lock) {
        active = false
        playStarted = false
        restartCheck = false
    }

    /**
     * Call periodically (every 1-2 s) with the player position. playing = audio is actually advancing (not paused /
     * buffering). durationMs (optional) fills in an unknown catalog duration. "Now playing" is sent on the first
     * playing sample of a play; the scrobble when the rule is met.
     */
    fun onProgress(positionMs: Long, playing: Boolean, durationMs: Long = 0) {
        var scrobbled: Pair<ScrobbleTrack, Long>? = null
        synchronized(lock) {
            if (!active) return
            try {
                if (restartCheck) {
                    restartCheck = false
                    // Jumped back to (near) the top after the same id started again: a repeat -> a new play.
                    if (positionMs + 10_000 < lastPositionMs) startPlay(track, restartWallMs)
                }
                lastPositionMs = positionMs
                if (durationMs > 0 && track.durationMs <= 0) {
                    track = track.copy(durationMs = durationMs)
                    listen.learnDuration(durationMs)
                }
                val enabled = isEnabled()
                if (playing && !playStarted) {
                    playStarted = true
                    // Started from (near) the top: back-date to the real start; otherwise (resumed session) "now".
                    startedAtUnix = unixNow() - (if (positionMs in 1 until 10_000) positionMs / 1000 else 0)
                    if (enabled) sendNowPlaying()
                }
                if (listen.update(positionMs, playing, clock()) && enabled) {
                    if (startedAtUnix == 0L) startedAtUnix = unixNow()
                    Log.i(TAG, "scrobble: ${track.artist} - ${track.title} (listened ${listen.listenedMs / 1000} s)")
                    submitScrobble()
                    scrobbled = track to startedAtUnix
                }
            } catch (e: Exception) {
                Log.w(TAG, "progress: ${e.message}")
            }
        }
        scrobbled?.let { (t, ts) -> onScrobble?.invoke(t, ts) }
    }

    val pendingLastfm: Int get() = synchronized(lock) { lfmQueue.size }
    val pendingListenbrainz: Int get() = synchronized(lock) { lbQueue.size }

    // --- sending (under lock; the requests run on workers) ------------------------------------------------------

    private fun sendNowPlaying() {
        if (!validTrack(track)) return
        if (lastfmConnected()) {
            val p = ArrayList(listOf("method" to "track.updateNowPlaying", "api_key" to lfmKey, "sk" to lfmSession))
            addTrackParams(p, track, "")
            val secret = lfmSecret
            val gen = lfmGen
            scope.launch {
                val r = try {
                    lastfmCall(p, secret)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "last.fm now playing failed: ${e.message}")
                    return@launch
                }
                synchronized(lock) {
                    if (gen != lfmGen) return@launch
                    if (r.error != 0) {
                        Log.w(TAG, "last.fm now playing error ${r.error}: ${r.message}")
                        if (r.error == 9) dropLastfmSession("session key rejected")
                        return@launch
                    }
                    flushLastfm() // a submission went through: retry anything still queued
                }
            }
        }
        if (lbToken.isNotEmpty()) {
            val body = JsonObject(linkedMapOf("listen_type" to JsonPrimitive("playing_now"), "payload" to JsonArray(listOf(lbListen(track, 0)))))
            val token = lbToken
            val gen = lbGen
            scope.launch {
                val r = try {
                    lbSubmit(token, body.dump())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "listenbrainz playing_now failed: ${e.message}")
                    return@launch
                }
                synchronized(lock) {
                    if (gen != lbGen) return@launch
                    if (r.first == 401) {
                        dropListenbrainzToken("token rejected (401)")
                        return@launch
                    }
                    if (r.first !in 200..299) {
                        Log.w(TAG, "listenbrainz playing_now HTTP ${r.first}: ${r.second}")
                        return@launch
                    }
                    flushListenbrainz()
                }
            }
        }
    }

    private fun enqueue(q: ArrayDeque<Pending>, p: Pending) {
        q.addLast(p)
        while (q.size > MAX_QUEUE) {
            q.removeFirst()
            Log.w(TAG, "retry queue full; dropped the oldest scrobble")
        }
    }

    private fun submitScrobble() {
        if (!validTrack(track)) {
            Log.d(TAG, "not scrobbling a track without title/artist")
            return
        }
        if (lastfmConnected()) {
            enqueue(lfmQueue, Pending(++seq, track, startedAtUnix))
            flushLastfm()
        }
        if (lbToken.isNotEmpty()) {
            enqueue(lbQueue, Pending(++seq, track, startedAtUnix))
            flushListenbrainz()
        }
        changed()
    }

    private fun flushLastfm() {
        if (lfmBusy || lfmQueue.isEmpty() || !lastfmConnected()) return
        val n = minOf(lfmQueue.size, LASTFM_BATCH)
        val p = ArrayList(listOf("method" to "track.scrobble", "api_key" to lfmKey, "sk" to lfmSession))
        val seqs = HashSet<Long>()
        for (i in 0 until n) {
            val e = lfmQueue[i]
            val sfx = if (n == 1) "" else "[$i]"
            addTrackParams(p, e.track, sfx)
            p.add("timestamp$sfx" to e.timestamp.toString())
            seqs.add(e.seq)
        }
        lfmBusy = true
        val secret = lfmSecret
        val gen = lfmGen
        scope.launch {
            val r: LfmReply? = try {
                lastfmCall(p, secret)
            } catch (e: CancellationException) {
                synchronized(lock) { lfmBusy = false }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "last.fm scrobble failed (queued for retry): ${e.message}")
                null
            }
            synchronized(lock) {
                lfmBusy = false
                if (gen != lfmGen) { // account changed meanwhile: the queue was reset
                    flushLastfm()
                    return@launch
                }
                var remove = false
                var ok = false
                if (r != null) {
                    if (r.error == 0) {
                        ok = true
                        remove = true
                        val attr = r.body["scrobbles"]["@attr"]
                        Log.i(TAG, "last.fm scrobbled ${seqs.size} (accepted ${attr["accepted"].intOrMinus()}, ignored ${attr["ignored"].intOrMinus()})")
                    } else {
                        Log.w(TAG, "last.fm scrobble error ${r.error}: ${r.message}")
                        if (r.error == 9) dropLastfmSession("session key rejected")
                        else if (r.error == 6) remove = true // invalid parameters: retrying cannot help
                    }
                }
                if (remove) lfmQueue.removeAll { it.seq in seqs }
                changed()
                if (ok) flushLastfm() // more than one batch was waiting
            }
        }
    }

    private fun flushListenbrainz() {
        if (lbBusy || lbQueue.isEmpty() || lbToken.isEmpty()) return
        val n = minOf(lbQueue.size, LB_BATCH)
        val payload = ArrayList<JsonElement>()
        val seqs = HashSet<Long>()
        for (i in 0 until n) {
            payload.add(lbListen(lbQueue[i].track, lbQueue[i].timestamp))
            seqs.add(lbQueue[i].seq)
        }
        val body = JsonObject(linkedMapOf("listen_type" to JsonPrimitive(if (n == 1) "single" else "import"), "payload" to JsonArray(payload)))
        lbBusy = true
        val token = lbToken
        val gen = lbGen
        scope.launch {
            val r: Pair<Int, String>? = try {
                lbSubmit(token, body.dump())
            } catch (e: CancellationException) {
                synchronized(lock) { lbBusy = false }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "listenbrainz submit failed (queued for retry): ${e.message}")
                null
            }
            synchronized(lock) {
                lbBusy = false
                if (gen != lbGen) {
                    flushListenbrainz()
                    return@launch
                }
                var remove = false
                var ok = false
                if (r != null) {
                    if (r.first in 200..299) {
                        ok = true
                        remove = true
                        Log.i(TAG, "listenbrainz submitted ${seqs.size} listen(s)")
                    } else {
                        Log.w(TAG, "listenbrainz submit HTTP ${r.first}: ${r.second}")
                        if (r.first == 401) dropListenbrainzToken("token rejected (401)")
                        else if (r.first == 400) remove = true // malformed listen: retrying cannot help
                    }
                }
                if (remove) lbQueue.removeAll { it.seq in seqs }
                changed()
                if (ok) flushListenbrainz()
            }
        }
    }

    // --- HTTP ------------------------------------------------------------------------------------------------------

    private fun send(r: HttpRequest): HttpResponse = Http.send(r, client)

    /** Signs, POSTs (form-encoded, format=json) and parses. Throws only on transport failure. */
    private suspend fun lastfmCall(paramsIn: List<Pair<String, String>>, secret: String): LfmReply = withContext(AppDispatchers.io) {
        val params = ArrayList(paramsIn)
        params.add("api_sig" to ScrobbleSign.lastfmSignature(params, secret))
        params.add("format" to "json")
        val r = send(
            HttpRequest(lastfmApi, "POST", listOf("User-Agent" to USER_AGENT), Http.formEncode(params).toByteArray(),
                "application/x-www-form-urlencoded"),
        )
        val j = parseJsonOrNull(r.body) as? JsonObject
        val out = LfmReply(r.status, 0, "", j)
        if (j != null && j.containsKey("error")) {
            val e = j["error"] as? JsonPrimitive
            out.error = e?.takeIf { !it.isString }?.content?.toIntOrNull() ?: -1
            out.message = jstr(j, "message")
        }
        if (out.error == 0 && !r.isSuccess) {
            out.error = -1
            out.message = "HTTP ${r.status}"
        } else if (out.error == 0 && j == null) {
            // Every format=json reply is an object: an empty/HTML 200 (outage page) is not a success - keep the
            // scrobbles queued instead of dropping them.
            out.error = -1
            out.message = "unreadable reply (HTTP ${r.status})"
        }
        out
    }

    private fun lbHeaders(token: String) = listOf("Authorization" to "Token $token", "User-Agent" to USER_AGENT)

    /** (HTTP status, server message). */
    private suspend fun lbSubmit(token: String, body: String): Pair<Int, String> = withContext(AppDispatchers.io) {
        val r = send(HttpRequest(listenbrainzApi + "submit-listens", "POST", lbHeaders(token), body.toByteArray(), "application/json"))
        val j = parseJsonOrNull(r.body)
        r.status to jstr(j, "error").ifEmpty { jstr(j, "message") }
    }

    companion object {
        private const val TAG = "scrobble"
        const val LASTFM_API = "https://ws.audioscrobbler.com/2.0/"
        const val LASTFM_AUTH_PAGE = "https://www.last.fm/api/auth/"
        const val LB_API = "https://api.listenbrainz.org/1/"
        const val USER_AGENT = "ShadeTube/0.2 (Android; scrobbler)"
        /** Per service; oldest dropped beyond this. */
        const val MAX_QUEUE = 500
        /** track.scrobble accepts up to 50 per call. */
        const val LASTFM_BATCH = 50
        const val LB_BATCH = 100

        /** Internet-radio station ids ("radio:<stationuuid>"). */
        fun isStationId(id: String) = id.length > 6 && id.startsWith("radio:")

        private fun trimWs(s: String) = s.trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' }

        private fun jstr(o: JsonElement?, key: String): String {
            val v = (o as? JsonObject)?.get(key) as? JsonPrimitive ?: return ""
            return if (v.isString) v.content else ""
        }

        private fun JsonElement?.intOrMinus(): Int =
            (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toIntOrNull() ?: -1

        private fun validTrack(t: ScrobbleTrack) = t.title.isNotEmpty() && t.artist.isNotEmpty()

        internal fun addTrackParams(p: MutableList<Pair<String, String>>, t: ScrobbleTrack, suffix: String) {
            p.add("artist$suffix" to t.artist)
            p.add("track$suffix" to t.title)
            if (t.album.isNotEmpty()) p.add("album$suffix" to t.album)
            if (t.durationMs >= 1000) p.add("duration$suffix" to (t.durationMs / 1000).toString())
        }

        /** One ListenBrainz listen; [listenedAt] 0 = playing_now. */
        internal fun lbListen(t: ScrobbleTrack, listenedAt: Long): JsonObject {
            val info = linkedMapOf<String, JsonElement>(
                "submission_client" to JsonPrimitive("ShadeTube"),
                "submission_client_version" to JsonPrimitive("0.2"),
                "media_player" to JsonPrimitive("ShadeTube"),
            )
            if (t.durationMs > 0) info["duration_ms"] = JsonPrimitive(t.durationMs)
            val meta = linkedMapOf<String, JsonElement>(
                "artist_name" to JsonPrimitive(t.artistCredit.ifEmpty { t.artist }),
                "track_name" to JsonPrimitive(t.title),
                "additional_info" to JsonObject(info),
            )
            if (t.album.isNotEmpty()) meta["release_name"] = JsonPrimitive(t.album)
            val item = linkedMapOf<String, JsonElement>("track_metadata" to JsonObject(meta))
            if (listenedAt > 0) item["listened_at"] = JsonPrimitive(listenedAt)
            return JsonObject(item)
        }
    }
}
