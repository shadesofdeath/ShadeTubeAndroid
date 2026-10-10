package com.app.shadetube.spotify

import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.Log
import com.app.shadetube.core.arr
import com.app.shadetube.core.get
import com.app.shadetube.core.isObject
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** A realistic desktop Chrome UA, reused for the token request and every GraphQL / spclient call. */
const val SPOTIFY_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/137.0.0.0 Safari/537.36"

/** Token minting failed (bad / expired cookie, network, Spotify changed the flow). */
class SpotifyAuthException(message: String) : IOException(message)

data class TokenBundle(
    val accessToken: String = "",
    /** accessTokenExpirationTimestampMs (unix ms). */
    val expiresAtMs: Long = 0,
    val anonymous: Boolean = false,
) {
    val valid: Boolean get() = accessToken.isNotEmpty()

    /** Treat as expired a minute early to avoid racing the boundary. */
    fun expired(nowMs: Long): Boolean = expiresAtMs != 0L && nowMs >= expiresAtMs - 60_000
}

/** The rotating TOTP secret + its version, from the maintainers' gist (with a compiled-in fallback). */
data class Nuance(val version: Int = 0, val secret: String = "")

/**
 * Spotube-style Spotify web-player token acquisition (port of spotify/Auth).
 *
 * Given the user's `sp_dc` cookie (captured by the WebView login), mint a short-lived Bearer access token that
 * authorizes the internal Pathfinder GraphQL API. The request is signed with a TOTP whose secret ("nuance") is
 * fetched at runtime (Spotify rotates it) with a baked-in fallback. See [Totp].
 */
object Auth {
    private const val TAG = "spotify"

    /** Baked-in fallback secret, used if the gist can't be reached. Kept current from the maintainers' list. */
    const val FALLBACK_NUANCE_VERSION = 61
    const val FALLBACK_NUANCE_SECRET =
        "GM3TMMJTGYZTQNZVGM4DINJZHA4TGOBYGMZTCMRTGEYDSMJRHE4TEOBUG4YTCMRUGQ4DQOJUGQYTAMRRGA2TCMJSHE3TCMBY"

    /** The maintainers publish the rotating secret list here; we pick the entry with the highest version. */
    const val NUANCE_GIST = "https://gist.githubusercontent.com/raw/22ed9c6ba463899e933427f7de1f0eef/nuances.json"

    const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    const val TOKEN_URL = "https://open.spotify.com/api/token"

    private val nuanceLock = Any()
    private var cachedNuance: Nuance? = null

    private fun browserHeaders(): List<Pair<String, String>> = listOf(
        "User-Agent" to SPOTIFY_USER_AGENT,
        "Accept" to "application/json",
        "Referer" to "https://open.spotify.com/",
        "App-Platform" to "WebPlayer",
    )

    private fun nowMs() = System.currentTimeMillis()

    /** Picks the highest-version entry with a secret from the gist's `[{v, s}, ...]`; null when there is none. */
    internal fun pickNuance(body: String): Nuance? {
        val j = parseJsonOrNull(body) as? JsonArray ?: return null
        var best: Nuance? = null
        var bestV = -1
        for (e in j.arr) {
            val v = (e["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt() ?: -1
            val s = (e["s"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            if (v > bestV && s.isNotEmpty()) {
                bestV = v
                best = Nuance(v, s)
            }
        }
        return best
    }

    /** Exposed for tests / diagnostics. Never throws (falls back to the baked-in secret). */
    suspend fun fetchNuance(bustCache: Boolean): Nuance = withContext(AppDispatchers.io) {
        if (!bustCache) synchronized(nuanceLock) { cachedNuance }?.let { return@withContext it }

        var best = Nuance(FALLBACK_NUANCE_VERSION, FALLBACK_NUANCE_SECRET)
        try {
            var url = NUANCE_GIST
            if (bustCache) url += "?t=" + nowMs()
            val resp = Http.await(
                HttpRequest(url, headers = listOf("User-Agent" to SPOTIFY_USER_AGENT, "Accept" to "application/json")),
            )
            if (resp.isSuccess) pickNuance(resp.body)?.let { best = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "nuance fetch failed (${e.message}); using baked-in v$FALLBACK_NUANCE_VERSION")
        }
        synchronized(nuanceLock) { cachedNuance = best }
        best
    }

    /** Spotify's clock (unix seconds); the local clock when server-time can't be read. */
    suspend fun fetchServerTimeSeconds(): Long = withContext(AppDispatchers.io) {
        try {
            val resp = Http.await(HttpRequest(SERVER_TIME_URL, headers = browserHeaders()))
            if (resp.isSuccess) {
                val j = parseJsonOrNull(resp.body)
                if (j.isObject && j["serverTime"] != null) {
                    val t = (j["serverTime"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                    if (t != null) return@withContext t
                    throw IOException("serverTime is not a number")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "server-time fetch failed (${e.message}); using local clock")
        }
        nowMs() / 1000
    }

    /** The token URL with the exact query the web player sends. */
    internal fun tokenUrl(code: String, nuanceVersion: Int): String =
        TOKEN_URL + "?reason=transport&productType=web-player" +
            "&totp=" + code + "&totpServer=" + code + "&totpVer=" + nuanceVersion

    private suspend fun requestToken(spDc: String, nuance: Nuance): TokenBundle {
        val serverTime = fetchServerTimeSeconds()
        val code = Totp.totp(nuance.secret, serverTime)
        val headers = browserHeaders() + ("Cookie" to "sp_dc=$spDc;")
        val resp = Http.await(HttpRequest(tokenUrl(code, nuance.version), headers = headers))
        if (!resp.isSuccess) throw SpotifyAuthException("token endpoint HTTP ${resp.status}")
        val j = parseJsonOrNull(resp.body)
        if (!j.isObject || j["accessToken"] == null) throw SpotifyAuthException("token response missing accessToken")
        val t = TokenBundle(
            accessToken = (j["accessToken"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            expiresAtMs = (j["accessTokenExpirationTimestampMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0,
            anonymous = (j["isAnonymous"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true",
        )
        if (t.accessToken.isEmpty()) throw SpotifyAuthException("empty accessToken")
        return t
    }

    /**
     * Runs the full flow: nuance -> server-time -> TOTP -> GET /api/token. Retries once with a cache-busted nuance if
     * the first attempt is rejected (covers a freshly rotated secret). Main-safe. Throws [SpotifyAuthException] /
     * IOException on failure (bad cookie, network, etc.).
     */
    suspend fun fetchAccessToken(spDc: String): TokenBundle = withContext(AppDispatchers.io) {
        if (spDc.isEmpty()) throw SpotifyAuthException("sp_dc cookie is empty")
        try {
            val t = requestToken(spDc, fetchNuance(false))
            if (t.anonymous) throw SpotifyAuthException("anonymous token (sp_dc rejected)")
            t
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "token attempt 1 failed (${e.message}); retrying with fresh nuance")
            val t = requestToken(spDc, fetchNuance(true))
            if (t.anonymous) throw SpotifyAuthException("anonymous token (sp_dc rejected or expired) — re-login needed")
            t
        }
    }
}
