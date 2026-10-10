package com.app.shadetube.ui.transfer

import androidx.compose.ui.platform.LocalConfiguration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.library.CollectionKind
import com.app.shadetube.playlistio.ExportSource
import com.app.shadetube.playlistio.PlaylistFormat
import com.app.shadetube.playlistio.PlaylistIO
import com.app.shadetube.playlistio.PlaylistTransfer
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.ShadeBottomSheet
import com.app.shadetube.ui.components.SheetHeader
import com.app.shadetube.ui.nav.ExportRequest
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Export sheet: the chosen format, the export running. */
class ExportViewModel(private val request: ExportRequest) : ViewModel() {
    private val _format = MutableStateFlow(PlaylistFormat.M3U)
    val format: StateFlow<PlaylistFormat> = _format.asStateFlow()
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    fun setFormat(f: PlaylistFormat) {
        _format.value = f
    }

    /** What the request names, for the transfer service. */
    private fun source(): ExportSource = when (val r = request) {
        ExportRequest.Queue -> ExportSource.Tracks(AppGraph.player.queue.value.inOrder)
        is ExportRequest.Tracks -> ExportSource.Tracks(r.tracks)
        is ExportRequest.Collection -> when (r.kind) {
            CollectionKind.Album -> ExportSource.AlbumOf(r.id)
            CollectionKind.Liked ->
                if (AppGraph.session.loggedIn) ExportSource.SpotifyList(LIKED_SONGS_URI) else ExportSource.Tracks(AppGraph.library.liked)
            CollectionKind.Playlist ->
                if (r.id.startsWith("spotify:") && AppGraph.session.loggedIn) ExportSource.SpotifyList(r.id)
                else ExportSource.Tracks(AppGraph.library.playlistTracks(r.id).orEmpty())
        }
    }

    /** Fetches the songs and writes them to [uri] (the SAF document). [onDone]: songs written (0 = none), or failure. */
    fun export(uri: Uri, name: String, onDone: (Result<Int>) -> Unit) {
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            val result = try {
                val tracks = AppGraph.transfer.tracksFor(source())
                withContext(AppDispatchers.io) {
                    AppGraph.context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open $uri")
                }.use { out -> AppGraph.transfer.export(name, tracks, _format.value, out) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _exporting.value = false
            onDone(result)
        }
    }
}

private val FORMATS = listOf(
    PlaylistFormat.M3U to R.string.transfer_format_m3u,
    PlaylistFormat.CSV to R.string.transfer_format_csv,
    PlaylistFormat.XSPF to R.string.transfer_format_xspf,
    PlaylistFormat.JSON to R.string.transfer_format_json,
)

private fun PlaylistFormat.label(): String = when (this) {
    PlaylistFormat.M3U -> "M3U8"
    else -> name
}

/** Shown by the shell for Navigator.showExport: format radio (M3U8, CSV, XSPF, JSON) + "Dışa aktar" → SAF create. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(request: ExportRequest, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val res = LocalContext.current.resources
    val vm: ExportViewModel = viewModel(key = "export:${System.identityHashCode(request)}") { ExportViewModel(request) }
    val format by vm.format.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val name = when (request) {
        is ExportRequest.Collection -> request.name
        is ExportRequest.Tracks -> request.name
        ExportRequest.Queue -> stringResource(R.string.transfer_queue_name)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PlaylistIO.mimeType(format))) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.export(uri, name) { r ->
            r.onSuccess { n ->
                nav.message(
                    if (n == 0) res.getString(R.string.transfer_export_nothing)
                    else res.getQuantityString(R.plurals.transfer_export_done, n, n),
                )
                onDismiss()
            }.onFailure { nav.message(res.getString(R.string.transfer_export_failed)) }
        }
    }
    val x = ShadeTheme.extras
    ShadeBottomSheet(onDismissRequest = { if (!exporting) onDismiss() }) {
        SheetHeader(stringResource(R.string.export_title), subtitle = name.ifEmpty { null })
        Text(
            stringResource(R.string.export_format).uppercase(LocalConfiguration.current.locales[0]),
            Modifier.padding(horizontal = 24.dp),
            style = ShadeMono.overline, color = x.text3,
        )
        Spacer(Modifier.height(4.dp))
        Column(Modifier.selectableGroup()) {
            for ((f, desc) in FORMATS) {
                FormatRow(f.label(), desc, selected = f == format, enabled = !exporting, onSelect = { vm.setFormat(f) })
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            stringResource(R.string.export_title),
            onClick = {
                val base = PlaylistTransfer.safeFileName(name).ifEmpty { res.getString(R.string.transfer_default_name) }
                launcher.launch(base + PlaylistIO.extension(format))
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            icon = R.drawable.ic_export,
            loading = exporting,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FormatRow(label: String, @StringRes desc: Int, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected, onClick = null, enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = accent.accent, unselectedColor = x.text2, disabledSelectedColor = x.text3, disabledUnselectedColor = x.text3),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = ShadeMono.medium, color = x.text1)
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
