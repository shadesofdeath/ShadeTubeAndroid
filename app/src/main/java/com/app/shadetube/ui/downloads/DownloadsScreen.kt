package com.app.shadetube.ui.downloads

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.catalog.pick
import com.app.shadetube.downloads.DlState
import com.app.shadetube.downloads.DownloadFailure
import com.app.shadetube.downloads.DownloadItem
import com.app.shadetube.downloads.DownloadSync
import com.app.shadetube.downloads.QueueWait
import com.app.shadetube.downloads.SyncRules
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.DownloadIndicator
import com.app.shadetube.ui.components.DownloadUiState
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.LinearProgress
import com.app.shadetube.ui.components.SectionHeader
import com.app.shadetube.ui.components.ShadeBottomSheet
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.components.SheetDivider
import com.app.shadetube.ui.components.SheetHeader
import com.app.shadetube.ui.components.SheetMenuRow
import com.app.shadetube.ui.components.SwipeToDeleteBox
import com.app.shadetube.ui.components.TrackListSkeleton
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.nav.SettingsSection
import com.app.shadetube.ui.settings.SettingsCard
import com.app.shadetube.ui.settings.SettingsScaffold
import com.app.shadetube.ui.settings.formatBytes
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

/** Active rows shown at most (a synced Liked Songs list can queue thousands). */
private const val MAX_ACTIVE_ROWS = 50

/** İndirilenler (SCREENS 18). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(contentPadding: PaddingValues) {
    val vm: DownloadsViewModel = viewModel { DownloadsViewModel() }
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val free by vm.freeBytes.collectAsStateWithLifecycle()
    val currentId by vm.currentTrackId.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var ruleMenu by remember { mutableStateOf<DownloadSync.RuleView?>(null) }
    var excludedFor by remember { mutableStateOf<DownloadSync.RuleView?>(null) }
    var disableFor by remember { mutableStateOf<DownloadSync.RuleView?>(null) }
    var showAllActive by rememberSaveable { mutableStateOf(false) }
    val x = ShadeTheme.extras
    val title = stringResource(R.string.downloads_title)
    val likedName = stringResource(R.string.library_liked_songs)
    fun ruleName(r: SyncRules.Rule) = if (r.kind == SyncRules.Kind.Liked) likedName else r.name.ifEmpty { r.id }
    LaunchedEffect(state?.totalBytes) { vm.refreshFreeSpace() }

    SettingsScaffold(
        title, contentPadding,
        actions = {
            ShadeIconButton(
                R.drawable.ic_settings, stringResource(R.string.settings_title),
                { nav.navigate(Screen.Settings(SettingsSection.Downloads)) }, tint = x.text1, size = 48.dp,
            )
        },
    ) {
        val s = state
        if (s == null) {
            item(key = "loading") { TrackListSkeleton() }
            return@SettingsScaffold
        }
        waitBanner(context, s)?.let { banner ->
            item(key = "banner") {
                WaitBanner(banner.first, banner.second, onAction = {
                    if (banner.third) vm.resumeSync() else nav.navigate(Screen.Settings(SettingsSection.Downloads))
                }, actionLabel = stringResource(if (banner.third) R.string.playlist_sync_resume else R.string.settings_title))
            }
        }
        item(key = "storage") { StorageCard(s.totalBytes, s.syncedBytes, s.capBytes, free) }
        if (s.isEmpty) {
            item(key = "empty") {
                EmptyState(
                    R.drawable.ic_download, stringResource(R.string.downloads_empty_title), stringResource(R.string.downloads_empty_body),
                )
            }
            return@SettingsScaffold
        }

        // Active downloads.
        if (s.active.isNotEmpty()) {
            item(key = "h_active") {
                Spacer(Modifier.height(16.dp))
                SectionHeader(stringResource(R.string.downloads_active), subtitle = pluralStringResource(R.plurals.downloads_songs, s.active.size, s.active.size))
            }
            val shown = if (showAllActive) s.active else s.active.take(MAX_ACTIVE_ROWS)
            items(shown, key = { "act:" + it.track.id }) { d ->
                TrackRow(
                    title = d.track.name,
                    subtitle = if (d.state == DlState.Queued) stringResource(R.string.downloads_queued) + " · " + d.track.artistLine else d.track.artistLine,
                    artworkUrl = d.track.album.images.pick(132)?.url,
                    contentPadding = PaddingValues(start = 20.dp, end = 8.dp),
                    trailing = {
                        DownloadIndicator(if (d.state == DlState.Downloading) DownloadUiState.Downloading(d.progress.takeIf { it > 0f }) else DownloadUiState.Queued)
                        ShadeIconButton(R.drawable.ic_close, stringResource(R.string.downloads_cancel_cd), { vm.cancel(d) }, tint = x.text2)
                    },
                )
            }
            if (!showAllActive && s.active.size > MAX_ACTIVE_ROWS) {
                item(key = "more_active") {
                    ShadeTextButton(
                        stringResource(R.string.downloads_more_queued, s.active.size - MAX_ACTIVE_ROWS),
                        { showAllActive = true }, Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }

        // Failed downloads.
        if (s.failed.isNotEmpty()) {
            item(key = "h_failed") {
                Spacer(Modifier.height(16.dp))
                SectionHeader(
                    stringResource(R.string.downloads_failed),
                    subtitle = pluralStringResource(R.plurals.downloads_songs, s.failed.size, s.failed.size),
                    actionLabel = stringResource(R.string.downloads_retry_all), onAction = { vm.retryAll(s.failed) },
                )
            }
            items(s.failed, key = { "fail:" + it.track.id }) { d ->
                TrackRow(
                    title = d.track.name,
                    subtitle = failureText(d.failure),
                    artworkUrl = d.track.album.images.pick(132)?.url,
                    contentPadding = PaddingValues(start = 20.dp, end = 8.dp),
                    trailing = {
                        ShadeIconButton(R.drawable.ic_refresh, stringResource(R.string.downloads_retry), { vm.retry(d) }, tint = x.error)
                        ShadeIconButton(R.drawable.ic_close, stringResource(R.string.downloads_cancel_cd), { vm.cancel(d) }, tint = x.text2)
                    },
                )
            }
        }

        // Synced collections.
        if (s.rules.isNotEmpty()) {
            item(key = "h_rules") {
                Spacer(Modifier.height(16.dp))
                SectionHeader(
                    stringResource(R.string.downloads_collections),
                    subtitle = if (s.syncRunning) stringResource(R.string.downloads_syncing) else null,
                    actionLabel = stringResource(if (s.syncPaused) R.string.playlist_sync_resume else R.string.downloads_pause),
                    onAction = { if (s.syncPaused) vm.resumeSync() else vm.pauseSync() },
                )
            }
            items(s.rules, key = { "rule:" + it.rule.id }) { rv ->
                RuleRow(
                    rv, ruleName(rv.rule), s.syncPaused,
                    onSyncNow = { vm.syncNow(rv.rule.id) },
                    onRetry = { vm.retryFailed(rv.rule.id) },
                    onMore = { ruleMenu = rv },
                )
            }
        }

        // Finished.
        if (s.done.isNotEmpty()) {
            item(key = "h_done") {
                Spacer(Modifier.height(16.dp))
                SectionHeader(
                    stringResource(R.string.downloads_finished),
                    subtitle = pluralStringResource(R.plurals.downloads_songs, s.done.size, s.done.size),
                    actionLabel = stringResource(R.string.action_play),
                    onAction = { vm.play(s.done.map { it.track }, 0, title) },
                )
            }
            itemsIndexed(s.done, key = { _, it -> "done:" + it.track.id }) { i, d ->
                SwipeToDeleteBox(onDelete = { confirmDelete = d }, Modifier.animateItem()) {
                    TrackRow(
                        track = d.track,
                        isCurrent = currentId == d.track.id,
                        isPlaying = playing,
                        downloaded = true,
                        onClick = { vm.play(s.done.map { it.track }, i, title) },
                        onMore = { nav.showTrackMenu(listOf(d.track)) },
                    )
                }
            }
            item(key = "done_note") {
                Text(
                    stringResource(R.string.downloads_swipe_hint),
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = x.text3,
                )
            }
        }
    }

    confirmDelete?.let { d ->
        ShadeDialog(
            onDismissRequest = { confirmDelete = null },
            title = stringResource(R.string.downloads_delete_title),
            text = stringResource(R.string.downloads_delete_body, d.track.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = null; vm.delete(d) },
            destructive = true,
        )
    }
    ruleMenu?.let { rv ->
        val excludedCount = rv.rule.excluded.size
        ShadeBottomSheet(onDismissRequest = { ruleMenu = null }) {
            SheetHeader(ruleName(rv.rule), subtitle = stringResource(R.string.playlist_sync_count, rv.status.progress.done, rv.status.progress.total))
            SheetMenuRow(R.drawable.ic_offline_sync, stringResource(R.string.downloads_sync_now), { ruleMenu = null; vm.syncNow(rv.rule.id) })
            if (rv.status.progress.failed > 0 || rv.status.error != SyncRules.ListError.None) {
                SheetMenuRow(R.drawable.ic_refresh, stringResource(R.string.downloads_retry), { ruleMenu = null; vm.retryFailed(rv.rule.id) })
            }
            SheetMenuRow(
                R.drawable.ic_block, stringResource(R.string.downloads_excluded) + if (excludedCount > 0) " ($excludedCount)" else "",
                { ruleMenu = null; excludedFor = rv }, enabled = excludedCount > 0,
            )
            SheetDivider()
            SheetMenuRow(R.drawable.ic_trash, stringResource(R.string.downloads_stop_sync), { ruleMenu = null; disableFor = rv }, destructive = true)
        }
    }
    excludedFor?.let { rv ->
        ExcludedSheet(
            ruleName = ruleName(rv.rule),
            items = remember(rv) { vm.excluded(rv.rule.id) },
            onRestore = { ids -> vm.include(ids, rv.rule.id) },
            onDismiss = { excludedFor = null },
        )
    }
    disableFor?.let { rv ->
        val needsChoice = remember(rv) { vm.disableNeedsChoice(rv.rule.id) }
        val name = ruleName(rv.rule)
        if (needsChoice) {
            ShadeDialog(
                onDismissRequest = { disableFor = null },
                title = stringResource(R.string.downloads_stop_sync_title, name),
                text = stringResource(R.string.downloads_stop_sync_choice),
                confirmLabel = stringResource(R.string.downloads_stop_sync_delete),
                onConfirm = { disableFor = null; vm.disable(rv.rule.id, deleteFiles = true) },
                dismissLabel = stringResource(R.string.downloads_stop_sync_keep),
                onDismiss = { disableFor = null; vm.disable(rv.rule.id, deleteFiles = false) },
                destructive = true,
            )
        } else {
            ShadeDialog(
                onDismissRequest = { disableFor = null },
                title = stringResource(R.string.downloads_stop_sync_title, name),
                text = stringResource(R.string.downloads_stop_sync_body),
                confirmLabel = stringResource(R.string.downloads_stop_sync),
                onConfirm = { disableFor = null; vm.disable(rv.rule.id, deleteFiles = false) },
                destructive = true,
            )
        }
    }
}

/** (text, is error, the action resumes sync) — or null when nothing waits. */
private fun waitBanner(context: Context, s: DownloadsUi): Triple<String, Boolean, Boolean>? {
    when (s.queueWait) {
        QueueWait.Offline -> return Triple(context.getString(R.string.downloads_wait_offline), false, false)
        QueueWait.WifiOnly -> return Triple(context.getString(R.string.downloads_wait_wifi), false, false)
        else -> Unit
    }
    if (s.rules.isEmpty()) return null
    return when (val w = s.syncWait) {
        DownloadSync.WaitReason.Paused -> Triple(context.getString(R.string.downloads_wait_paused), false, true)
        DownloadSync.WaitReason.Metered -> Triple(context.getString(R.string.downloads_wait_metered), false, false)
        is DownloadSync.WaitReason.StorageCap -> Triple(
            context.getString(R.string.downloads_wait_cap, formatBytes(context, w.usedBytes), formatBytes(context, w.capBytes)), true, false,
        )
        DownloadSync.WaitReason.Offline, DownloadSync.WaitReason.WifiOnly, null -> null
    }
}

@Composable
private fun WaitBanner(text: String, error: Boolean, onAction: () -> Unit, actionLabel: String) {
    val x = ShadeTheme.extras
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(x.surface3).padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShadeIcon(if (error) R.drawable.ic_warning else R.drawable.ic_offline, null, tint = if (error) x.warning else x.text2, size = 16.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = x.text1)
        ShadeTextButton(actionLabel, onAction)
    }
}

/** Storage card (96): used / cap (or used / free) in mono 18 and a 4dp bar. */
@Composable
internal fun StorageCard(totalBytes: Long, syncedBytes: Long, capBytes: Long, freeBytes: Long) {
    val context = LocalContext.current
    val localRes = LocalResources.current
    val x = ShadeTheme.extras
    val limit = when {
        capBytes > 0 -> capBytes
        freeBytes >= 0 -> totalBytes + freeBytes
        else -> 0L
    }
    SettingsCard(Modifier.padding(top = 12.dp).heightIn(min = 96.dp)) {
        Text(stringResource(R.string.downloads_storage), style = MaterialTheme.typography.bodySmall, color = x.text2)
        Spacer(Modifier.height(4.dp))
        Text(
            if (limit > 0) stringResource(R.string.downloads_storage_value, formatBytes(context, totalBytes), formatBytes(context, limit))
            else formatBytes(context, totalBytes),
            style = ShadeMono.large, color = x.text1, maxLines = 1,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgress(if (limit > 0) (totalBytes.toFloat() / limit).coerceIn(0f, 1f) else 0f, height = 4.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            buildList {
                if (syncedBytes > 0) add(localRes.getString(R.string.downloads_storage_synced, formatBytes(context, syncedBytes)))
                if (capBytes > 0) add(localRes.getString(R.string.downloads_storage_cap))
                if (freeBytes >= 0) add(localRes.getString(R.string.downloads_storage_free, formatBytes(context, freeBytes)))
            }.joinToString(" · "),
            style = ShadeMono.small, color = x.text3, maxLines = 2,
        )
    }
}

@Composable
private fun failureText(f: DownloadFailure?): String = stringResource(
    when (f) {
        DownloadFailure.NoMatch -> R.string.downloads_err_no_match
        DownloadFailure.Unavailable -> R.string.downloads_err_unavailable
        DownloadFailure.RateLimited -> R.string.downloads_err_rate_limited
        DownloadFailure.Network -> R.string.downloads_err_network
        DownloadFailure.Io -> R.string.downloads_err_io
        DownloadFailure.Other, null -> R.string.downloads_err_other
    },
)

@Composable
private fun ruleStatus(rv: DownloadSync.RuleView): Pair<String, Boolean> {
    val st = rv.status
    val p = st.progress
    return when {
        st.listing -> stringResource(R.string.downloads_syncing) to false
        !st.sourceReady -> stringResource(R.string.downloads_rule_source_missing) to false
        st.error == SyncRules.ListError.NotFound -> stringResource(R.string.downloads_rule_not_found) to true
        st.error == SyncRules.ListError.RateLimited -> stringResource(R.string.downloads_rule_rate_limited) to true
        st.error == SyncRules.ListError.Network -> stringResource(R.string.downloads_rule_network) to true
        p.failed > 0 -> pluralStringResource(R.plurals.downloads_rule_failed, p.failed, p.failed) to true
        p.active > 0 -> pluralStringResource(R.plurals.downloads_rule_active, p.active, p.active) to false
        st.lastSync == 0L -> stringResource(R.string.downloads_rule_never) to false
        else -> stringResource(
            R.string.downloads_rule_synced,
            DateUtils.getRelativeTimeSpanString(st.lastSync * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        ) to false
    }
}

/** Synced collection row (72): cover 56 + name + "%d / %d" + 3dp progress + status, text buttons. */
@Composable
private fun RuleRow(
    rv: DownloadSync.RuleView,
    name: String,
    paused: Boolean,
    onSyncNow: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
) {
    val x = ShadeTheme.extras
    val p = rv.status.progress
    val (status, isError) = ruleStatus(rv)
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onMore).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
        Row(Modifier.heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                rv.rule.images.pick(168)?.url, size = 56.dp,
                kind = if (rv.rule.kind == SyncRules.Kind.Album) com.app.shadetube.ui.components.ArtworkKind.Album else com.app.shadetube.ui.components.ArtworkKind.Playlist,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.playlist_sync_count, p.done, p.total), style = ShadeMono.small, color = x.text2)
                }
                Spacer(Modifier.height(6.dp))
                LinearProgress(
                    if (rv.status.listing && p.total == 0) null else if (p.total > 0) p.done.toFloat() / p.total else 0f,
                    height = 3.dp,
                    color = if (isError) x.error else ShadeTheme.accent.accent,
                )
                Spacer(Modifier.height(6.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (isError) x.error else x.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ShadeIconButton(R.drawable.ic_more_vertical, stringResource(R.string.common_more_cd), onMore, tint = x.text2)
        }
        Row(Modifier.padding(start = 60.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!rv.status.listing) ShadeTextButton(stringResource(R.string.downloads_sync_now), onSyncNow, enabled = rv.status.sourceReady)
            if (isError) ShadeTextButton(stringResource(R.string.downloads_retry), onRetry, destructive = true)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ExcludedSheet(
    ruleName: String,
    items: List<SyncRules.Excluded>,
    onRestore: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val x = ShadeTheme.extras
    var restored by remember { mutableStateOf(emptySet<String>()) }
    val left = items.filter { it.id !in restored }
    ShadeBottomSheet(onDismissRequest = onDismiss) {
        SheetHeader(stringResource(R.string.downloads_excluded), subtitle = ruleName)
        if (left.isNotEmpty()) {
            ShadeTextButton(
                stringResource(R.string.downloads_restore_all),
                { onRestore(left.map { it.id }); restored = restored + left.map { it.id } },
                Modifier.padding(horizontal = 12.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(left, key = { it.id }) { e ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name.ifEmpty { e.id }, style = MaterialTheme.typography.bodyLarge, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.artists.isNotEmpty()) Text(e.artists, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ShadeTextButton(stringResource(R.string.downloads_restore), { onRestore(listOf(e.id)); restored = restored + e.id })
                }
            }
        }
        if (left.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(stringResource(R.string.downloads_excluded_empty), style = MaterialTheme.typography.bodyMedium, color = x.text2)
            }
        }
    }
}

@Preview(widthDp = 412)
@Composable
private fun StorageCardPreview() {
    ShadeTubeThemeOf {
        Box(Modifier.background(ShadeTheme.extras.surface0).padding(vertical = 12.dp)) {
            StorageCard(1_300_000_000L, 900_000_000L, 10L * 1024 * 1024 * 1024, 24_000_000_000L)
        }
    }
}
