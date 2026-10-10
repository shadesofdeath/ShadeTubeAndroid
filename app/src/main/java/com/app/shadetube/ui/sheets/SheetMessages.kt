package com.app.shadetube.ui.sheets

import android.content.res.Resources
import com.app.shadetube.R
import com.app.shadetube.library.AddToPlaylistResult
import com.app.shadetube.library.CreatePlaylistResult
import com.app.shadetube.library.LikeResult
import com.app.shadetube.library.RemoveTracksResult

/** Typed results of the library actions -> snackbar text (null = say nothing). */
object SheetMessages {
    fun like(res: Resources, r: LikeResult): String? = when (r) {
        LikeResult.Unchanged -> null
        LikeResult.Liked -> res.getString(R.string.snackbar_liked)
        LikeResult.Unliked -> res.getString(R.string.sheets_unliked)
        LikeResult.OnlySpotifyTracks -> res.getString(R.string.sheets_like_only_spotify)
    }

    fun add(res: Resources, r: AddToPlaylistResult): String? = when (r) {
        is AddToPlaylistResult.Added -> {
            val base = res.getString(R.string.snackbar_added_to_playlist, r.name)
            if (r.skipped > 0) base + " " + res.getQuantityString(R.plurals.sheets_skipped_not_spotify, r.skipped, r.skipped) else base
        }
        is AddToPlaylistResult.Failed -> res.getString(R.string.sheets_add_failed, r.name)
        is AddToPlaylistResult.AlreadyInList -> res.getString(R.string.sheets_already_in_list, r.name)
        AddToPlaylistResult.OnlySpotifyTracks -> res.getString(R.string.sheets_only_spotify_tracks)
        AddToPlaylistResult.NotFound -> null
    }

    fun create(res: Resources, name: String, r: CreatePlaylistResult): String = when (r) {
        is CreatePlaylistResult.Created -> {
            val base = when {
                r.addFailed -> res.getString(R.string.sheets_created_add_failed, name)
                r.added > 0 -> res.getQuantityString(R.plurals.sheets_created_with_songs, r.added, name, r.added)
                else -> res.getString(R.string.sheets_created, name)
            }
            if (r.skipped > 0) base + " " + res.getQuantityString(R.plurals.sheets_skipped_not_spotify, r.skipped, r.skipped) else base
        }
        CreatePlaylistResult.Failed -> res.getString(R.string.sheets_create_failed)
        CreatePlaylistResult.NotOnSpotify -> res.getString(R.string.sheets_new_not_on_spotify)
    }

    fun remove(res: Resources, r: RemoveTracksResult): String? = when (r) {
        RemoveTracksResult.Removed -> res.getString(R.string.sheets_removed_from_playlist)
        RemoveTracksResult.Failed -> res.getString(R.string.sheets_remove_failed)
        RemoveTracksResult.Nothing -> null
    }
}
