package com.app.shadetube.youtube.explode

/**
 * Base exception for all errors raised by the library (port of YoutubeExplode::Exceptions).
 *
 * Hierarchy:
 * ```
 *   YoutubeExplodeException
 *   ├── VideoUnplayableException
 *   │   ├── VideoUnavailableException        (deleted / private / nonexistent)
 *   │   ├── VideoRequiresPurchaseException   (paid content, exposes a preview video ID)
 *   │   └── VideoAgeRestrictedException      (age gate could not be bypassed)
 *   ├── PlaylistUnavailableException
 *   ├── RequestLimitExceededException        (HTTP 429)
 *   ├── CipherExtractionException            (player JS changed; cipher/n extraction failed)
 *   └── HttpRequestException                 (transport error or unexpected HTTP status)
 * ```
 * Cancellation is the coroutine's own CancellationException (the C++ OperationCanceledException).
 */
open class YoutubeExplodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Video cannot be played (for any reason). */
open class VideoUnplayableException(message: String) : YoutubeExplodeException(message)

/** Video does not exist, is private, or was deleted. */
class VideoUnavailableException(message: String) : VideoUnplayableException(message)

/** Video requires purchase and cannot be played. A free preview may be available. */
class VideoRequiresPurchaseException(message: String, val previewVideoId: String) : VideoUnplayableException(message)

/**
 * Video is age-restricted and none of the available clients could bypass the age gate.
 * Supplying cookies of a signed-in, age-verified account usually resolves this.
 */
class VideoAgeRestrictedException(message: String) : VideoUnplayableException(message)

/** Playlist does not exist, is private, or was deleted. */
class PlaylistUnavailableException(message: String) : YoutubeExplodeException(message)

/** YouTube responded with HTTP 429 (Too Many Requests). */
class RequestLimitExceededException(message: String) : YoutubeExplodeException(message)

/**
 * The signature cipher or the n-parameter transform could not be extracted from the player JavaScript. This almost
 * always means YouTube changed its player and the extraction heuristics in PlayerSource / JsScanner need updating.
 */
class CipherExtractionException(message: String) : YoutubeExplodeException(message)

/** Transport-level failure, or a response with an unexpected HTTP status code. [statusCode] 0 = no response. */
class HttpRequestException(message: String, val statusCode: Int = 0, cause: Throwable? = null) :
    YoutubeExplodeException(message, cause)
