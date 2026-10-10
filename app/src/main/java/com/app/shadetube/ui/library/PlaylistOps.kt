package com.app.shadetube.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.downloads.DownloadSync
import com.app.shadetube.downloads.SyncRules
import com.app.shadetube.library.CollectionKind
import com.app.shadetube.library.DeleteKind
import com.app.shadetube.library.RenameResult
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.nav.Navigator
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** A confirmation / input the playlist actions ask for. */
sealed interface OpsDialog {
    data class Rename(val id: String, val current: String) : OpsDialog
    data class Delete(val id: String, val name: String, val kind: DeleteKind) : OpsDialog

    /** Turning offline sync off while its songs are on the device: keep or delete them. */
    data class DisableSync(val ruleId: String, val name: String) : OpsDialog
}

sealed interface OpsEvent {
    data class Message(@param:StringRes val text: Int, val arg: String = "") : OpsEvent

    /** The playlist [id] is gone (a page showing it should close). */
    data class Deleted(val id: String) : OpsEvent
}

/** What can be kept offline (download sync target of a playlist / album / Liked Songs card). */
data class OfflineCandidate(val kind: CollectionKind, val id: String, val name: String, val images: List<Image>) {
    /** The sync rule id (Liked Songs by the active source). */
    val ruleId: String
        get() = if (kind == CollectionKind.Liked) {
            if (AppGraph.account.loggedIn) SyncRules.SPOTIFY_LIKED_ID else SyncRules.LOCAL_LIKED_ID
        } else id

    fun target() = DownloadSync.Target(
        kind = when (kind) {
            CollectionKind.Liked -> SyncRules.Kind.Liked
            CollectionKind.Album -> SyncRules.Kind.Album
            CollectionKind.Playlist -> SyncRules.Kind.Playlist
        },
        id = ruleId,
        name = if (kind == CollectionKind.Liked) "" else name,
        images = images,
    )
}

/**
 * Rename / delete / offline of playlists, shared by the Library card menu and the collection page (the C++
 * promptRenamePlaylist / confirmDeletePlaylist / sync toggle). One per ViewModel, running in its scope.
 */
class PlaylistOps(private val scope: CoroutineScope) {
    private val _dialog = MutableStateFlow<OpsDialog?>(null)
    val dialog: StateFlow<OpsDialog?> = _dialog.asStateFlow()
    private val _events = Channel<OpsEvent>(Channel.BUFFERED)
    val events: Flow<OpsEvent> = _events.receiveAsFlow()

    fun canRename(id: String) = AppGraph.playlistActions.canRename(id)
    fun deleteKind(id: String): DeleteKind? =
        if (id.isEmpty() || id == LIKED_SONGS_URI) null else AppGraph.playlistActions.deleteKind(id)

    fun askRename(id: String, current: String) {
        _dialog.value = OpsDialog.Rename(id, current)
    }

    fun askDelete(id: String, name: String) {
        val kind = deleteKind(id) ?: return
        _dialog.value = OpsDialog.Delete(id, name, kind)
    }

    fun dismiss() {
        _dialog.value = null
    }

    fun confirmRename(newName: String) {
        val d = _dialog.value as? OpsDialog.Rename ?: return
        _dialog.value = null
        scope.launch {
            when (AppGraph.playlistActions.renamePlaylist(d.id, newName, d.current)) {
                RenameResult.Unchanged, RenameResult.Renamed -> Unit
                RenameResult.Failed -> _events.send(OpsEvent.Message(R.string.library_rename_failed))
                RenameResult.NoSpotifyConnection -> _events.send(OpsEvent.Message(R.string.library_no_spotify_connection))
            }
        }
    }

    fun confirmDelete() {
        val d = _dialog.value as? OpsDialog.Delete ?: return
        _dialog.value = null
        scope.launch {
            val r = AppGraph.playlistActions.deletePlaylist(d.id) ?: return@launch
            if (r.ok) {
                _events.send(OpsEvent.Deleted(d.id))
                _events.send(
                    OpsEvent.Message(
                        if (r.kind == DeleteKind.RemoveFromLibrary) R.string.library_removed_from_library else R.string.library_deleted,
                        d.name,
                    ),
                )
            } else {
                _events.send(OpsEvent.Message(R.string.library_delete_failed, d.name))
            }
        }
    }

    fun isOffline(c: OfflineCandidate) = AppGraph.sync.isSynced(c.ruleId)

    /** The "Çevrimdışı" toggle: on = a sync rule (downloads start); off asks first when its songs are downloaded. */
    fun toggleOffline(c: OfflineCandidate) {
        val sync = AppGraph.sync
        val rule = c.ruleId
        if (sync.isSynced(rule)) {
            if (sync.disableNeedsChoice(rule)) {
                _dialog.value = OpsDialog.DisableSync(rule, c.name)
            } else {
                sync.disable(rule, deleteFiles = false)
                _events.trySend(OpsEvent.Message(R.string.library_offline_off))
            }
        } else {
            sync.enable(c.target())
            _events.trySend(OpsEvent.Message(R.string.library_offline_on))
        }
    }

    fun confirmDisable(deleteFiles: Boolean) {
        val d = _dialog.value as? OpsDialog.DisableSync ?: return
        _dialog.value = null
        AppGraph.sync.disable(d.ruleId, deleteFiles)
        _events.trySend(OpsEvent.Message(R.string.library_offline_off))
    }
}

/** The dialogs of [PlaylistOps] and its messages as snackbars; [onDeleted] e.g. leaves the deleted list's page. */
@Composable
fun PlaylistOpsHost(ops: PlaylistOps, nav: Navigator, onDeleted: (String) -> Unit = {}) {
    val context = LocalContext.current
    val localRes = LocalResources.current
    LaunchedEffect(ops, nav) {
        ops.events.collect { e ->
            when (e) {
                is OpsEvent.Message -> nav.message(localRes.getString(e.text, e.arg))
                is OpsEvent.Deleted -> onDeleted(e.id)
            }
        }
    }
    val dialog by ops.dialog.collectAsStateWithLifecycle()
    when (val d = dialog) {
        null -> Unit
        is OpsDialog.Rename -> RenameDialog(d.current, ops::confirmRename, ops::dismiss)
        is OpsDialog.Delete -> ShadeDialog(
            onDismissRequest = ops::dismiss,
            title = stringResource(
                if (d.kind == DeleteKind.RemoveFromLibrary) R.string.library_remove_title else R.string.library_delete_title,
            ),
            text = stringResource(
                when (d.kind) {
                    DeleteKind.DeleteSpotify -> R.string.library_delete_spotify_body
                    DeleteKind.RemoveFromLibrary -> R.string.library_remove_body
                    DeleteKind.DeleteLocal -> R.string.library_delete_local_body
                },
                d.name,
            ),
            confirmLabel = stringResource(if (d.kind == DeleteKind.RemoveFromLibrary) R.string.library_remove else R.string.action_delete),
            onConfirm = ops::confirmDelete,
            destructive = true,
        )
        is OpsDialog.DisableSync -> ShadeDialog(
            onDismissRequest = ops::dismiss,
            title = stringResource(R.string.library_offline_disable_title),
            text = stringResource(R.string.library_offline_disable_body),
            confirmLabel = stringResource(R.string.library_offline_delete_songs),
            onConfirm = { ops.confirmDisable(true) },
            dismissLabel = stringResource(R.string.library_offline_keep_songs),
            onDismiss = { ops.confirmDisable(false) },
            destructive = true,
        )
    }
}

/** "Yeniden adlandır": a text field prefilled with the current name (selected). */
@Composable
fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val x = ShadeTheme.extras
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    ShadeDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.library_rename_title),
        text = null,
        confirmLabel = stringResource(R.string.library_rename_save),
        onConfirm = { onConfirm(value.text) },
    ) {
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { androidx.compose.material3.Text(stringResource(R.string.library_rename_hint)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ShadeTheme.accent.accent,
                unfocusedBorderColor = x.outline,
                cursorColor = ShadeTheme.accent.accent,
                focusedTextColor = x.text1,
                unfocusedTextColor = x.text1,
                focusedPlaceholderColor = x.text3,
                unfocusedPlaceholderColor = x.text3,
            ),
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
