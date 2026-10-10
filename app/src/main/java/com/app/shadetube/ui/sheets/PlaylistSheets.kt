@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.app.shadetube.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.Playlist
import com.app.shadetube.catalog.Track
import com.app.shadetube.library.CreatePlaylistResult
import com.app.shadetube.library.Destination
import com.app.shadetube.library.NewPlaylistCheck
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.ShadeBottomSheet
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.components.SheetHeader
import com.app.shadetube.ui.components.SheetMenuRow
import com.app.shadetube.ui.components.urlFor
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.launch

/**
 * "Listeye ekle": "Yeni çalma listesi" first, then the playlists the songs may go to ([PlaylistActions.addTargets]:
 * the editable Spotify lists while logged in, else the local ones). The result is a snackbar.
 */
@Composable
fun AddToPlaylistSheet(tracks: List<Track>, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val res = LocalContext.current.resources
    val targets = remember { runCatching { AppGraph.playlistActions.addTargets() }.getOrDefault(emptyList()) }
    ShadeBottomSheet(onDismissRequest = onDismiss) {
        SheetHeader(stringRes(R.string.menu_add_to_playlist))
        SheetMenuRow(R.drawable.ic_plus, stringRes(R.string.library_new_playlist), onClick = { nav.showNewPlaylist(tracks) })
        if (targets.isEmpty()) {
            EmptyState(
                R.drawable.ic_playlist, stringRes(R.string.sheets_no_playlists_title), stringRes(R.string.sheets_no_playlists_body),
                Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(targets, key = { it.id }) { p ->
                    PlaylistTargetRow(p) {
                        onDismiss()
                        sheetScope.launch {
                            val r = AppGraph.playlistActions.addToPlaylist(p.id, tracks)
                            SheetMessages.add(res, r)?.let { nav.message(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTargetRow(p: Playlist, onClick: () -> Unit) {
    val x = ShadeTheme.extras
    val px = with(LocalDensity.current) { 44.dp.roundToPx() }
    val url = remember(p.images, px) { p.images.urlFor(px) }
    val res = LocalContext.current.resources
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(url, kind = ArtworkKind.Playlist, size = 44.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.countKnown) {
                Text(
                    res.getQuantityString(R.plurals.sheets_track_count, p.totalTracks, p.totalTracks),
                    style = ShadeMono.medium, color = x.text3, maxLines = 1,
                )
            }
        }
    }
}

/**
 * "Yeni çalma listesi": name field (logged in: created on the Spotify account; songs not on Spotify are reported).
 * Created -> snackbar with "Aç".
 */
@Composable
fun NewPlaylistDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val res = LocalContext.current.resources
    val x = ShadeTheme.extras
    val check = remember(tracks) { AppGraph.playlistActions.newPlaylistCheck(tracks) }
    if (check == NewPlaylistCheck.NotOnSpotify) {
        ShadeDialog(
            onDismissRequest = onDismiss,
            title = stringRes(R.string.library_new_playlist),
            text = stringRes(R.string.sheets_new_not_on_spotify),
            confirmLabel = stringRes(R.string.action_ok),
            onConfirm = onDismiss,
            dismissLabel = null,
        )
        return
    }
    var name by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val create = {
        onDismiss()
        val n = name.trim().ifEmpty { res.getString(R.string.library_new_playlist) }
        sheetScope.launch {
            val r = AppGraph.playlistActions.createPlaylist(n, tracks)
            val text = SheetMessages.create(res, n, r)
            if (r is CreatePlaylistResult.Created) {
                nav.message(text, res.getString(R.string.sheets_open)) { nav.open(Destination.playlist(r.id)) }
            } else {
                nav.message(text)
            }
        }
        Unit
    }
    ShadeDialog(
        onDismissRequest = onDismiss,
        title = stringRes(R.string.library_new_playlist),
        text = stringRes(if (check == NewPlaylistCheck.Spotify) R.string.sheets_new_spotify_note else R.string.sheets_new_local_note),
        confirmLabel = stringRes(R.string.import_create),
        onConfirm = create,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            placeholder = { Text(stringRes(R.string.sheets_playlist_name_hint), color = x.text3) },
            shape = ShadeRadius.card,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { create() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ShadeTheme.accent.accent,
                unfocusedBorderColor = x.outline,
                cursorColor = ShadeTheme.accent.accent,
                focusedTextColor = x.text1,
                unfocusedTextColor = x.text1,
            ),
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
internal fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
