@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.app.shadetube.ui.sheets

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isLocalId
import com.app.shadetube.catalog.isPodcastId
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.library.CatalogSource
import com.app.shadetube.library.Destination
import com.app.shadetube.library.radioSeed
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.ShadeBottomSheet
import com.app.shadetube.ui.components.SheetDivider
import com.app.shadetube.ui.components.SheetMenuRow
import com.app.shadetube.ui.components.urlFor
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.TrackMenuContext
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.launch

private class MenuItem(@param:DrawableRes val icon: Int, val label: String, val destructive: Boolean = false, val action: () -> Unit)

/**
 * Song menu (SCREENS #15): header (cover 56 + title / artist) and 56dp rows — play next, add to queue, add to playlist,
 * like, download, start radio, go to artist / album, change source, share, copy link; context rows (remove from queue /
 * this playlist, suggestion add / hide); a divider; block song / artist, sync and download removal in `error`.
 * Several [tracks]: only the rows that apply to a selection.
 */
@Composable
fun TrackMenuSheet(tracks: List<Track>, context: TrackMenuContext, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val res = ctx.resources
    val single = tracks.singleOrNull()
    val t = tracks.first()
    val player = AppGraph.player

    val items = remember(tracks, context) {
        val top = ArrayList<MenuItem>()
        val bottom = ArrayList<MenuItem>()
        val station = tracks.any { InternetRadio.isStation(it.id) }
        val podcast = tracks.any { isPodcastId(it.id) }
        val local = tracks.all { isLocalId(it.id) }

        if (!station) {
            top += MenuItem(R.drawable.ic_play_next, res.getString(R.string.menu_play_next)) {
                if (single != null) player.playNext(single) else player.insertAt(player.currentOrderIndex + 1, tracks)
                nav.message(res.getString(R.string.sheets_play_next_done))
            }
        }
        top += MenuItem(R.drawable.ic_add_to_queue, res.getString(R.string.menu_add_to_queue)) {
            player.enqueue(tracks)
            nav.message(res.getString(R.string.snackbar_added_to_queue))
        }
        if (context.queueIndex >= 0 && context.queueIndex != player.currentOrderIndex) {
            top += MenuItem(R.drawable.ic_close, res.getString(R.string.sheets_remove_from_queue)) {
                val at = context.queueIndex
                player.removeAt(at)
                nav.message(res.getString(R.string.queue_removed), res.getString(R.string.action_undo)) { player.insertAt(at, tracks) }
            }
        }
        if (!station && !podcast) {
            top += MenuItem(R.drawable.ic_playlist, res.getString(R.string.menu_add_to_playlist)) { nav.showAddToPlaylist(tracks) }
        }
        if (single != null && AppGraph.library.canLike(single.id)) {
            val liked = AppGraph.library.isLiked(single.id)
            top += MenuItem(
                if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart,
                res.getString(if (liked) R.string.player_unlike_cd else R.string.menu_like),
            ) {
                val r = AppGraph.library.toggleLiked(single)
                AppGraph.playback.invalidateMediaButtons()
                SheetMessages.like(res, r)?.let { nav.message(it) }
            }
        }
        val downloadable = tracks.filter {
            !InternetRadio.isStation(it.id) && !isPodcastId(it.id) && !isLocalId(it.id) &&
                !AppGraph.downloads.isDownloaded(it.id) && !AppGraph.downloads.isActive(it.id)
        }
        if (downloadable.isNotEmpty()) {
            top += MenuItem(R.drawable.ic_download, res.getString(R.string.menu_download)) {
                AppGraph.downloads.enqueue(downloadable)
                nav.message(res.getString(R.string.snackbar_download_started))
            }
        }
        if (single != null && radioSeed(single.id).isNotEmpty()) {
            top += MenuItem(R.drawable.ic_radio, res.getString(R.string.menu_start_radio)) {
                AppGraph.radio.startRadio(single.id, single.name)
            }
        }
        val artist = single?.artists?.firstOrNull { it.id.isNotEmpty() }
        if (artist != null) {
            top += MenuItem(R.drawable.ic_artist, res.getString(R.string.menu_go_to_artist)) { nav.open(Destination.Artist(artist.id)) }
        }
        if (single != null && single.album.id.isNotEmpty() && !isLocalId(single.album.id)) {
            top += MenuItem(R.drawable.ic_album, res.getString(R.string.menu_go_to_album)) { nav.open(Destination.Album(single.album.id)) }
        }
        if (single != null && !station && !podcast && !local) {
            top += MenuItem(R.drawable.ic_swap_source, res.getString(R.string.menu_change_source)) { nav.showSourcePicker(single) }
        }
        if (single != null) {
            val url = TrackLinks.url(single)
            if (url.isNotEmpty()) {
                top += MenuItem(R.drawable.ic_share, res.getString(R.string.menu_share)) { TrackLinks.share(ctx, single, url) }
                top += MenuItem(R.drawable.ic_link, res.getString(R.string.menu_copy_link)) {
                    if (TrackLinks.copy(ctx, url)) nav.message(res.getString(R.string.snackbar_link_copied))
                }
            }
        }

        // ---- context rows
        val playlistId = context.playlistId
        if (playlistId.isNotEmpty() && tracks.any { !it.recommended } && playlistEditable(playlistId)) {
            top += MenuItem(R.drawable.ic_minus, res.getString(R.string.sheets_remove_from_playlist)) {
                sheetScope.launch {
                    val r = AppGraph.playlistActions.removeFromPlaylist(playlistId, tracks)
                    SheetMessages.remove(res, r)?.let { nav.message(it) }
                }
            }
        }
        val key = context.recommendKey
        val recs = tracks.filter { it.recommended }
        if (key.isNotEmpty() && recs.isNotEmpty()) {
            val smart = AppGraph.smartShuffle
            if (recs.any { smart.canAdd(key, it) }) {
                top += MenuItem(R.drawable.ic_plus, res.getString(R.string.sheets_suggestion_add)) {
                    recs.forEach { smart.add(key, it) }
                }
            }
            top += MenuItem(R.drawable.ic_block, res.getString(R.string.sheets_suggestion_hide)) {
                recs.forEach { smart.hide(key, it) }
                nav.message(res.getString(R.string.sheets_suggestion_hidden))
            }
            top += MenuItem(R.drawable.ic_sparkle, res.getString(R.string.sheets_suggestions_off)) { smart.stop(key) }
        }

        // ---- destructive
        val bl = AppGraph.blacklist
        if (single != null && !station && !podcast) {
            val blocked = bl.isTrackBlocked(single.id) || bl.trackMatches(single)
            bottom += MenuItem(R.drawable.ic_block, res.getString(if (blocked) R.string.sheets_unblock_song else R.string.menu_block_song), destructive = !blocked) {
                bl.setTrackBlocked(single, !blocked)
                nav.message(res.getString(if (blocked) R.string.sheets_unblocked else R.string.snackbar_blocked))
            }
            val a = single.artists.firstOrNull { it.name.isNotEmpty() }
            if (a != null) {
                val aBlocked = bl.artistMatches(a)
                bottom += MenuItem(
                    R.drawable.ic_block,
                    if (aBlocked) res.getString(R.string.sheets_unblock_artist) else res.getString(R.string.sheets_block_artist_named, a.name),
                    destructive = !aBlocked,
                ) {
                    bl.setArtistBlocked(a.id, a.name, !aBlocked)
                    nav.message(res.getString(if (aBlocked) R.string.sheets_unblocked else R.string.snackbar_blocked))
                }
            }
        }
        val sync = runCatching { AppGraph.sync.trackActions(tracks) }.getOrNull()
        if (sync != null) {
            if (sync.wanted.isNotEmpty()) {
                bottom += MenuItem(R.drawable.ic_offline, res.getString(R.string.menu_remove_offline), destructive = true) {
                    AppGraph.sync.excludeTracks(sync.wanted)
                    nav.message(res.getQuantityString(R.plurals.sheets_sync_excluded, sync.wanted.size, sync.wanted.size))
                }
            }
            if (sync.excludedIds.isNotEmpty()) {
                bottom += MenuItem(R.drawable.ic_offline_sync, res.getString(R.string.sheets_include_sync)) {
                    AppGraph.sync.includeTracks(sync.excludedIds)
                    nav.message(res.getQuantityString(R.plurals.sheets_sync_included, sync.excludedIds.size, sync.excludedIds.size))
                }
            }
            if (sync.downloaded.isNotEmpty()) {
                bottom += MenuItem(R.drawable.ic_trash, res.getString(R.string.menu_delete_download), destructive = true) {
                    AppGraph.sync.deleteDownloads(sync.downloaded)
                    nav.message(res.getQuantityString(R.plurals.sheets_sync_deleted, sync.downloaded.size, sync.downloaded.size))
                }
            }
        }
        top to bottom
    }

    ShadeBottomSheet(onDismissRequest = onDismiss) {
        MenuHeader(tracks)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (item in items.first) {
                SheetMenuRow(item.icon, item.label, onClick = { onDismiss(); item.action() }, destructive = item.destructive)
            }
            if (items.second.isNotEmpty()) {
                SheetDivider()
                for (item in items.second) {
                    SheetMenuRow(item.icon, item.label, onClick = { onDismiss(); item.action() }, destructive = item.destructive)
                }
            }
        }
    }
}

/** "Remove from this playlist": a playlist the user may edit (own / collaborative Spotify list, or a local one). */
private fun playlistEditable(id: String): Boolean =
    if (CatalogSource.isSpotifyId(id)) AppGraph.playlistActions.isEditableSpotifyPlaylist(id)
    else AppGraph.library.playlist(id) != null

/** 72dp header: cover 56 + title / artist (a selection: "N şarkı" + the first titles). */
@Composable
private fun MenuHeader(tracks: List<Track>) {
    val x = ShadeTheme.extras
    val t = tracks.first()
    val px = with(LocalDensity.current) { 56.dp.roundToPx() }
    val url = remember(t.album.images, px) { t.album.images.urlFor(px) }
    val res = LocalContext.current.resources
    val kind = when {
        InternetRadio.isStation(t.id) -> ArtworkKind.Radio
        isPodcastId(t.id) -> ArtworkKind.Podcast
        else -> ArtworkKind.Track
    }
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(url, kind = kind, size = 56.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            val title = if (tracks.size == 1) t.name else res.getQuantityString(R.plurals.sheets_track_count, tracks.size, tracks.size)
            val sub = if (tracks.size == 1) t.artistLine else tracks.take(3).joinToString(", ") { it.name }
            Text(title, style = MaterialTheme.typography.titleMedium, color = x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = x.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    SheetDivider()
}
