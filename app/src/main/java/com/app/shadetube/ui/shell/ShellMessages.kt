package com.app.shadetube.ui.shell

import android.content.res.Resources
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.app.PendingOpen
import com.app.shadetube.playback.PlaybackError
import com.app.shadetube.playback.PlayerNotice
import com.app.shadetube.youtube.MatchFailure

/** Backend toasts (enums / data) -> localized snackbar text. null = nothing to show. */
internal object ShellMessages {
    fun error(res: Resources, e: PlaybackError, trackName: String? = null, failure: MatchFailure? = null): String = when (e) {
        PlaybackError.AllBlocked -> res.getString(R.string.shell_err_all_blocked)
        PlaybackError.NoMatch -> {
            val base = if (!trackName.isNullOrEmpty()) res.getString(R.string.shell_err_no_match, trackName)
            else res.getString(R.string.shell_err_no_match_generic)
            val reason = failure?.let { matchFailure(res, it) }
            if (reason != null) "$base · $reason" else base
        }
        PlaybackError.NothingPlayable -> res.getString(R.string.shell_err_nothing_playable)
        PlaybackError.EpisodeFailed -> res.getString(R.string.shell_err_episode_failed)
        PlaybackError.EpisodeFormatUnsupported -> res.getString(R.string.shell_err_episode_format)
        PlaybackError.StationUnsupported -> res.getString(R.string.shell_err_station_unsupported)
        PlaybackError.StationUnreachable -> res.getString(R.string.shell_err_station_unreachable)
        PlaybackError.FileUnsupported -> res.getString(R.string.shell_err_file_unsupported)
        PlaybackError.FileUnreadable -> res.getString(R.string.shell_err_file_unreadable)
        PlaybackError.PlaybackFailed -> res.getString(R.string.shell_err_playback_failed)
    }

    fun matchFailure(res: Resources, f: MatchFailure): String? = when (f) {
        MatchFailure.NoMatch, MatchFailure.Other -> null
        MatchFailure.Unavailable -> res.getString(R.string.shell_mf_unavailable)
        MatchFailure.AgeRestricted -> res.getString(R.string.shell_mf_age_restricted)
        MatchFailure.RequiresPurchase -> res.getString(R.string.shell_mf_purchase)
        MatchFailure.Unplayable -> res.getString(R.string.shell_mf_unplayable)
        MatchFailure.RateLimited -> res.getString(R.string.shell_mf_rate_limited)
        MatchFailure.Network -> res.getString(R.string.shell_mf_network)
        MatchFailure.Cipher -> res.getString(R.string.shell_mf_cipher)
        MatchFailure.BackupSource -> res.getString(R.string.shell_mf_backup)
    }

    fun notice(res: Resources, n: PlayerNotice): String? = when (n) {
        is PlayerNotice.Error -> error(res, n.error, n.track?.name, n.matchFailure)
        is PlayerNotice.SegmentSkipped -> res.getString(R.string.shell_segment_skipped, segmentName(res, n.category))
        PlayerNotice.RadioPreparing -> res.getString(R.string.shell_radio_preparing)
        is PlayerNotice.RadioReady ->
            if (n.seedName.isNotEmpty()) res.getString(R.string.shell_radio_ready, n.seedName)
            else res.getString(R.string.shell_radio_ready_generic)
        PlayerNotice.RadioUnavailable -> res.getString(R.string.shell_radio_unavailable)
        PlayerNotice.RadioNeedsSpotify -> res.getString(R.string.shell_radio_needs_spotify)
        PlayerNotice.RadioNotOffered -> res.getString(R.string.shell_radio_not_offered)
        PlayerNotice.RadioEmpty -> res.getString(R.string.shell_radio_empty)
        is PlayerNotice.RadioFailed -> when (n.httpStatus) {
            429 -> res.getString(R.string.shell_radio_rate_limited)
            401, 403 -> res.getString(R.string.shell_radio_auth)
            else -> res.getString(R.string.shell_radio_failed)
        }
        is PlayerNotice.EndlessAppended -> res.getQuantityString(R.plurals.shell_endless_appended, n.count, n.count)
        is PlayerNotice.SleepTimerSet -> res.getQuantityString(R.plurals.shell_sleep_set, n.minutes, n.minutes)
        PlayerNotice.SleepAtTrackEndSet -> res.getString(R.string.shell_sleep_track_end)
        PlayerNotice.SleepTimerOff -> res.getString(R.string.shell_sleep_off)
        PlayerNotice.SleepTimerStopped -> res.getString(R.string.shell_sleep_stopped)
        PlayerNotice.SmartShuffleOn -> res.getString(R.string.shell_smart_shuffle_on)
        PlayerNotice.RecommendationsOff -> res.getString(R.string.shell_recommendations_off)
        PlayerNotice.AddedToLiked -> res.getString(R.string.snackbar_liked)
        is PlayerNotice.AddedToPlaylist -> {
            val name = runCatching { AppGraph.library.playlist(n.playlistId)?.name }.getOrNull()
                ?: runCatching { AppGraph.playlistActions.addTargets().firstOrNull { it.id == n.playlistId }?.name }.getOrNull()
            if (name.isNullOrEmpty()) res.getString(R.string.shell_added_to_playlist_generic)
            else res.getString(R.string.snackbar_added_to_playlist, name)
        }
    }

    private fun segmentName(res: Resources, category: String): String = when (category) {
        "sponsor" -> res.getString(R.string.shell_segment_sponsor)
        "selfpromo" -> res.getString(R.string.shell_segment_selfpromo)
        "interaction" -> res.getString(R.string.shell_segment_interaction)
        "music_offtopic" -> res.getString(R.string.shell_segment_offtopic)
        "intro" -> res.getString(R.string.shell_segment_intro)
        "outro" -> res.getString(R.string.shell_segment_outro)
        "preview" -> res.getString(R.string.shell_segment_preview)
        "filler" -> res.getString(R.string.shell_segment_filler)
        else -> res.getString(R.string.shell_segment_other)
    }

    fun openFailed(res: Resources, f: PendingOpen.Failed): String = when (f.reason) {
        PendingOpen.Failed.Reason.NothingPlayable -> res.getString(R.string.shell_open_nothing_playable)
        PendingOpen.Failed.Reason.PlaylistUnreadable -> res.getString(R.string.shell_open_playlist_unreadable)
    }
}
