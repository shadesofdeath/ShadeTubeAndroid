package com.app.shadetube.playback

import com.app.shadetube.catalog.Track
import com.app.shadetube.core.RepeatMode
import com.app.shadetube.youtube.Match
import com.app.shadetube.youtube.MatchFailure

/** What the player is doing (port of player::Status). */
enum class Status { Idle, Resolving, Buffering, Playing, Paused, Error }

/** The list a queue was started from (port of player::PlayContext). */
data class PlayContext(
    /** "album:<id>", "artist:<id>", "playlist:<id>", "liked", "search", "queue", "podcast:<feed>", ... */
    val uri: String = "",
    /** Shown in the queue header ("Gece Sürüşü"); the UI may localize well-known uris ("queue"). */
    val name: String = "",
) {
    companion object {
        /** The context of tracks queued with nothing playing (C++ {"queue", tr(L"Sıra")}). */
        val QUEUE = PlayContext("queue", "")
    }
}

/**
 * Why the current item failed / the queue stopped (the C++ onError toasts as an enum; the UI maps them to
 * strings.xml). [Player.lastError] keeps the latest; [PlayerNotice.Error] carries each occurrence.
 */
enum class PlaybackError {
    /** "Listedeki tüm şarkılar kara listede": a header play found nothing the player may pick by itself. */
    AllBlocked,

    /** "\"{}\" için YouTube eşleşmesi bulunamadı" (detail: [PlayerNotice.Error.matchFailure]). */
    NoMatch,

    /** "Çalınabilir şarkı bulunamadı": a failure streak as long as the playable queue stopped it. */
    NothingPlayable,

    /** "Bölüm çalınamadı — sonrakine geçiliyor". */
    EpisodeFailed,

    /** "Bu bölümün ses biçimi çalınamıyor — sonrakine geçiliyor". */
    EpisodeFormatUnsupported,

    /** "Bu radyo yayınının biçimi desteklenmiyor". */
    StationUnsupported,

    /** "Radyo yayınına bağlanılamadı". */
    StationUnreachable,

    /** "Bu dosya biçimi çalınamıyor — sonraki şarkıya geçiliyor". */
    FileUnsupported,

    /** "Dosya okunamadı — sonraki şarkıya geçiliyor". */
    FileUnreadable,

    /** "Oynatma hatası — sonraki şarkıya geçiliyor". */
    PlaybackFailed,
}

/** One-off messages for toasts (the backend never builds user-facing text). */
sealed interface PlayerNotice {
    data class Error(val error: PlaybackError, val track: Track? = null, val matchFailure: MatchFailure? = null) : PlayerNotice

    /** SponsorBlock skipped a segment of [category] ("music_offtopic", "sponsor", "selfpromo", "interaction", ...). */
    data class SegmentSkipped(val category: String) : PlayerNotice

    // Radio (app/Radio)
    data object RadioPreparing : PlayerNotice
    data class RadioReady(val seedName: String) : PlayerNotice
    data object RadioUnavailable : PlayerNotice // "Bunun için Spotify radyosu yok"
    data object RadioNeedsSpotify : PlayerNotice // "Radyo için Spotify bağlantısı gerekir"
    data object RadioNotOffered : PlayerNotice // "Spotify bunun için radyo sunmuyor"
    data object RadioEmpty : PlayerNotice // "Radyoda çalınabilecek şarkı yok"
    data class RadioFailed(val httpStatus: Int) : PlayerNotice // 429 / 401 / 403 / other
    data class EndlessAppended(val count: Int) : PlayerNotice

    // Sleep timer
    data class SleepTimerSet(val minutes: Int) : PlayerNotice
    data object SleepAtTrackEndSet : PlayerNotice
    data object SleepTimerOff : PlayerNotice
    data object SleepTimerStopped : PlayerNotice

    // Smart shuffle / Geliştir
    data object SmartShuffleOn : PlayerNotice
    data object RecommendationsOff : PlayerNotice
    data object AddedToLiked : PlayerNotice
    data class AddedToPlaylist(val playlistId: String, val added: Int) : PlayerNotice
}

/**
 * The queue as the UI sees it: [order] indexes into [items]; [currentOrderIndex] is the playing slot (-1 = none).
 * Immutable snapshot, replaced on every change.
 */
data class QueueSnapshot(
    val items: List<Track> = emptyList(),
    val order: List<Int> = emptyList(),
    val currentOrderIndex: Int = -1,
    val context: PlayContext = PlayContext(),
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    /** Bumped by playContext / replaceUpcoming (C++ queueGeneration). */
    val generation: Long = 0,
) {
    val current: Track? get() = order.getOrNull(currentOrderIndex)?.let { items.getOrNull(it) }

    /** Tracks in play order. */
    val inOrder: List<Track> get() = order.map { items[it] }

    /** The tracks after the current one, in play order (order index = currentOrderIndex + 1 + i). */
    val upcoming: List<Track> get() = if (currentOrderIndex < 0) inOrder else order.drop(currentOrderIndex + 1).map { items[it] }
}

/** Position of the current item; [durationMs] 0 = unknown / live. */
data class PositionState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedFraction: Float = 0f,
)

/** The resolved YouTube source of the current item (null for local files, episodes and stations). */
data class CurrentSource(
    val match: Match? = null,
    val codec: String = "",
    val mimeType: String = "",
    val bitrateKbps: Int = 0,
    val source: String = "",
    val local: Boolean = false,
    val live: Boolean = false,
    val direct: Boolean = false,
)

/**
 * Events for listening stats, scrobbling, podcast progress and the radio registry (AppGraph subscribes them).
 * Emitted on the main thread.
 */
sealed interface PlayerEvent {
    /** A new item became current (C++ onTrackChanged). */
    data class TrackStarted(
        val track: Track,
        val context: PlayContext,
        val isLive: Boolean,
        val isEpisode: Boolean,
        /** Unix ms. */
        val atMs: Long,
    ) : PlayerEvent

    /** About every second while an item is current, and on every state change / seek. */
    data class Progress(
        val track: Track,
        val status: Status,
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
        /** ICY / HLS title of a station ("" = none). */
        val liveTitle: String,
    ) : PlayerEvent

    /**
     * The item stopped being current: another one started ([reason] Replaced), it played to its end and nothing
     * followed (QueueEnded), or it failed.
     */
    data class TrackEnded(
        val track: Track,
        val positionMs: Long,
        val durationMs: Long,
        val reason: EndReason,
    ) : PlayerEvent

    /** Items / order / current slot / context changed. */
    data class QueueChanged(val snapshot: QueueSnapshot) : PlayerEvent

    /** Status changed (Playing / Paused / Idle ...). */
    data class StatusChanged(val track: Track?, val status: Status) : PlayerEvent
}

enum class EndReason { Replaced, QueueEnded, Failed, Stopped }
