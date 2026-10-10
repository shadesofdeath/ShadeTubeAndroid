package com.app.shadetube.ui.localfiles

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.catalog.pick
import com.app.shadetube.localfiles.LocalGroup
import com.app.shadetube.localfiles.LocalGroups
import com.app.shadetube.localfiles.LocalPlatform
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.LinearProgress
import com.app.shadetube.ui.components.MonoTag
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.SearchField
import com.app.shadetube.ui.components.SecondaryButton
import com.app.shadetube.ui.components.SegmentedPill
import com.app.shadetube.ui.components.ShadeBottomSheet
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.components.SheetHeader
import com.app.shadetube.ui.components.TrackListSkeleton
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.settings.SettingsGroupHeader
import com.app.shadetube.ui.settings.SettingsRow
import com.app.shadetube.ui.settings.SettingsScaffold
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme

private enum class LocalTab { Songs, Albums, Artists }

/** Yerel dosyalar (SCREENS 19). */
@Composable
fun LocalFilesScreen(contentPadding: PaddingValues) {
    val vm: LocalFilesViewModel = viewModel { LocalFilesViewModel() }
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val lib by vm.libraryState.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val currentId by vm.currentTrackId.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmRemove by remember { mutableStateOf<LocalFolderRow?>(null) }
    var openGroup by remember { mutableStateOf<Pair<LocalGroup, Boolean>?>(null) }
    var hasPermission by remember { mutableStateOf(LocalPlatform.hasAudioPermission(context)) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val x = ShadeTheme.extras
    val px44 = with(LocalDensity.current) { 44.dp.roundToPx() }
    val title = stringResource(R.string.local_title)
    val alreadyCovered = stringResource(R.string.localfiles_folder_covered)

    LifecycleResumeEffect(Unit) {
        hasPermission = LocalPlatform.hasAudioPermission(context)
        onPauseOrDispose { }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && !vm.addFolder(uri)) nav.message(alreadyCovered)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionDenied = !granted
        if (granted) vm.rescan()
    }
    fun addFolder() {
        runCatching { treePicker.launch(null) }
    }

    SettingsScaffold(
        title, contentPadding,
        actions = {
            ShadeIconButton(
                R.drawable.ic_refresh, stringResource(R.string.localfiles_rescan),
                { vm.rescan() }, tint = x.text1, size = 48.dp, enabled = !lib.scanning && folders.isNotEmpty(),
            )
        },
    ) {
        // Add folder + permission.
        item(key = "add") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.local_add_folder), ::addFolder, icon = R.drawable.ic_plus)
            }
        }
        if (!hasPermission) {
            item(key = "permission") {
                SettingsRow(
                    stringResource(R.string.perm_audio_title), icon = R.drawable.ic_lock,
                    subtitle = stringResource(if (permissionDenied) R.string.perm_denied_body else R.string.localfiles_permission_sub),
                    trailing = {
                        if (permissionDenied) {
                            ShadeTextButton(stringResource(R.string.perm_open_settings), {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            })
                        } else {
                            ShadeTextButton(stringResource(R.string.perm_allow), { permissionLauncher.launch(LocalPlatform.audioPermission) })
                        }
                    },
                )
            }
        }
        // Folders.
        if (folders.isNotEmpty()) {
            item(key = "g_folders") { SettingsGroupHeader(stringResource(R.string.localfiles_folders, folders.size)) }
            items(folders, key = { "f:" + it.folder }) { f ->
                SettingsRow(
                    f.name, icon = if (f.unavailable) R.drawable.ic_warning else R.drawable.ic_folder,
                    subtitle = if (f.unavailable) stringResource(R.string.localfiles_folder_unavailable) else f.location,
                    value = pluralStringResource(R.plurals.localfiles_songs, f.songs, f.songs),
                    trailing = { ShadeIconButton(R.drawable.ic_close, stringResource(R.string.localfiles_remove_folder_cd), { confirmRemove = f }, tint = x.text2) },
                )
            }
        }
        // Scanning.
        if (lib.scanning) {
            item(key = "scanning") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.local_scanning), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = x.text1)
                        if (lib.scanTotal > 0) Text("${lib.scanDone} / ${lib.scanTotal}", style = ShadeMono.small, color = x.text2)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgress(if (lib.scanTotal > 0) lib.scanDone.toFloat() / lib.scanTotal else null, height = 3.dp)
                }
            }
        }
        if (!lib.loaded) {
            item(key = "loading") { TrackListSkeleton() }
            return@SettingsScaffold
        }
        if (folders.isEmpty() && lib.tracks.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    R.drawable.ic_folder, stringResource(R.string.localfiles_empty_title), stringResource(R.string.localfiles_empty_body),
                    actionLabel = stringResource(R.string.local_add_folder), onAction = ::addFolder, actionIcon = R.drawable.ic_plus,
                )
            }
            return@SettingsScaffold
        }
        if (lib.tracks.isEmpty()) {
            item(key = "none") {
                if (lib.scanning) TrackListSkeleton()
                else EmptyState(R.drawable.ic_music_note, stringResource(R.string.localfiles_no_songs_title), stringResource(R.string.localfiles_no_songs_body))
            }
            return@SettingsScaffold
        }
        if (lib.capped) item(key = "capped") { com.app.shadetube.ui.settings.SettingsNote(stringResource(R.string.localfiles_capped), icon = R.drawable.ic_warning) }
        // Tabs + search.
        item(key = "tabs") {
            Spacer(Modifier.height(8.dp))
            SegmentedPill(
                listOf(stringResource(R.string.local_tab_songs), stringResource(R.string.local_tab_albums), stringResource(R.string.local_tab_artists)),
                tab, { tab = it }, Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            SearchField(query, vm::setQuery, Modifier.padding(horizontal = 20.dp), hint = stringResource(R.string.localfiles_search_hint))
            Spacer(Modifier.height(8.dp))
        }
        when (LocalTab.entries[tab]) {
            LocalTab.Songs -> {
                item(key = "songs_head") {
                    ListHead(pluralStringResource(R.plurals.localfiles_songs, content.songs.size, content.songs.size)) {
                        if (content.songs.isNotEmpty()) vm.playSongs(content.songs, 0, title)
                    }
                }
                itemsIndexed(content.songs, key = { _, s -> "s:" + s.track.id }) { i, s ->
                    TrackRow(
                        track = s.track,
                        isCurrent = currentId == s.track.id,
                        isPlaying = playing,
                        trailing = { if (s.format.isNotEmpty()) MonoTag(s.format, Modifier.padding(end = 4.dp)) },
                        onClick = { vm.playSongs(content.songs, i, title) },
                        onMore = { nav.showTrackMenu(listOf(s.track)) },
                    )
                }
            }
            LocalTab.Albums, LocalTab.Artists -> {
                val album = tab == LocalTab.Albums.ordinal
                val groups = if (album) content.albums else content.artists
                val shown = groups.take(LocalGroups.MAX_CARDS)
                item(key = "groups_head") {
                    ListHead(
                        pluralStringResource(if (album) R.plurals.localfiles_albums else R.plurals.localfiles_artists, groups.size, groups.size),
                    ) { vm.playGroups(shown, album, title) }
                }
                items(shown, key = { (if (album) "al:" else "ar:") + it.key }) { g ->
                    val count = g.trackIndices.size
                    TrackRow(
                        title = g.title,
                        subtitle = listOfNotNull(
                            g.artist.takeIf { it.isNotEmpty() },
                            g.year.takeIf { it > 0 }?.toString(),
                            pluralStringResource(R.plurals.localfiles_songs, count, count),
                        ).joinToString(" · "),
                        artworkUrl = g.images.pick(px44)?.url,
                        artworkKind = if (album) ArtworkKind.Album else ArtworkKind.Artist,
                        onClick = { openGroup = g to album },
                    )
                }
                if (groups.size > shown.size) {
                    item(key = "more") { com.app.shadetube.ui.settings.SettingsNote(stringResource(R.string.localfiles_narrow_search, groups.size - shown.size)) }
                }
            }
        }
        val empty = when (LocalTab.entries[tab]) {
            LocalTab.Songs -> content.songs.isEmpty()
            LocalTab.Albums -> content.albums.isEmpty()
            LocalTab.Artists -> content.artists.isEmpty()
        }
        if (empty && query.isNotBlank()) {
            item(key = "noresult") {
                Text(
                    stringResource(R.string.search_no_results, query),
                    Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = x.text2,
                )
            }
        }
    }

    confirmRemove?.let { f ->
        ShadeDialog(
            onDismissRequest = { confirmRemove = null },
            title = stringResource(R.string.localfiles_remove_title),
            text = stringResource(R.string.localfiles_remove_body, f.name),
            confirmLabel = stringResource(R.string.localfiles_remove),
            onConfirm = { confirmRemove = null; vm.removeFolder(f.folder) },
            destructive = true,
        )
    }
    openGroup?.let { (g, album) ->
        GroupSheet(
            group = g, album = album,
            tracks = remember(g) { vm.groupTracks(g) },
            currentId = currentId, playing = playing,
            onPlay = { i, shuffle -> vm.playGroup(g, album, i, shuffle) },
            onMore = { t -> nav.showTrackMenu(listOf(t)) },
            onDismiss = { openGroup = null },
        )
    }
}

@Composable
private fun ListHead(text: String, onPlay: () -> Unit) {
    val x = ShadeTheme.extras
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = ShadeMono.small, color = x.text2)
        ShadeTextButton(stringResource(R.string.action_play), onPlay)
    }
}

/** An album / artist of the local library: Play / Shuffle and its songs. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GroupSheet(
    group: LocalGroup,
    album: Boolean,
    tracks: List<com.app.shadetube.catalog.Track>,
    currentId: String?,
    playing: Boolean,
    onPlay: (Int, Boolean) -> Unit,
    onMore: (com.app.shadetube.catalog.Track) -> Unit,
    onDismiss: () -> Unit,
) {
    val sub = listOfNotNull(
        group.artist.takeIf { it.isNotEmpty() },
        group.year.takeIf { it > 0 }?.toString(),
        pluralStringResource(R.plurals.localfiles_songs, tracks.size, tracks.size),
    ).joinToString(" · ")
    ShadeBottomSheet(onDismissRequest = onDismiss) {
        SheetHeader(group.title, subtitle = sub)
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(stringResource(R.string.action_play), { onPlay(0, false); onDismiss() }, Modifier.weight(1f), icon = R.drawable.ic_play)
            SecondaryButton(stringResource(R.string.action_shuffle), { onPlay(0, true); onDismiss() }, Modifier.weight(1f), icon = R.drawable.ic_shuffle)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(
                    track = t,
                    isCurrent = currentId == t.id, isPlaying = playing,
                    showArtwork = !album,
                    onClick = { onPlay(i, false); onDismiss() },
                    onMore = { onMore(t) },
                )
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}
