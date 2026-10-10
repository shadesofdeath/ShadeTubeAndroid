package com.app.shadetube.ui.downloads

import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.AppGraph
import com.app.shadetube.catalog.Track
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Paths
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.downloads.DlState
import com.app.shadetube.downloads.DownloadItem
import com.app.shadetube.downloads.DownloadSync
import com.app.shadetube.downloads.QueueWait
import com.app.shadetube.downloads.SyncRules
import com.app.shadetube.playback.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DownloadsUi(
    val active: List<DownloadItem> = emptyList(),
    val failed: List<DownloadItem> = emptyList(),
    /** Newest first. */
    val done: List<DownloadItem> = emptyList(),
    val rules: List<DownloadSync.RuleView> = emptyList(),
    val totalBytes: Long = 0,
    val syncedBytes: Long = 0,
    /** Settings.syncCapGb in bytes; 0 = no cap. */
    val capBytes: Long = 0,
    val queueWait: QueueWait? = null,
    val syncWait: DownloadSync.WaitReason? = null,
    val syncRunning: Boolean = false,
    val syncPaused: Boolean = false,
) {
    val isEmpty: Boolean get() = active.isEmpty() && failed.isEmpty() && done.isEmpty() && rules.isEmpty()
}

/**
 * İndirilenler: the download queue ([com.app.shadetube.downloads.DownloadManager]) and the synced collections
 * ([DownloadSync]): storage, why the queue waits, active / failed / finished downloads, rules with their actions.
 */
class DownloadsViewModel : ViewModel() {
    private val downloads get() = AppGraph.downloads
    private val sync get() = AppGraph.sync

    val state: StateFlow<DownloadsUi?> = combine(
        AppGraph.downloads.state, AppGraph.sync.uiState, AppGraph.network.state, SettingsStore.flow,
    ) { snap, sync, _, settings ->
        val active = snap.items.filter { it.isActive }.sortedBy { if (it.state == DlState.Downloading) 0 else 1 }
        DownloadsUi(
            active = active,
            failed = snap.items.filter { it.state == DlState.Failed },
            done = snap.items.filter { it.state == DlState.Done }.sortedByDescending { it.addedAt },
            rules = sync.rules,
            totalBytes = snap.totalBytes,
            syncedBytes = snap.syncedBytes,
            capBytes = settings.syncCapGb.toLong() * DownloadSync.GIB,
            queueWait = downloads.queueWait(),
            syncWait = sync.waitReason,
            syncRunning = sync.running,
            syncPaused = settings.syncPaused,
        )
    }.flowOn(AppDispatchers.cpu).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Free space on the downloads volume (bytes; -1 = unknown). */
    val freeBytes = MutableStateFlow(-1L)

    val currentTrackId: StateFlow<String?> = AppGraph.player.current.map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val playing: StateFlow<Boolean> = AppGraph.player.status.map { it == Status.Playing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun refreshFreeSpace() {
        viewModelScope.launch {
            freeBytes.value = withContext(AppDispatchers.io) {
                runCatching { StatFs(Paths.downloadsDir.absolutePath).availableBytes }.getOrDefault(-1L)
            }
        }
    }

    // ---- downloads ------------------------------------------------------------------------------------------------

    /** Stops a queued / running download; a sync download is also taken out of its collections (not fetched again). */
    fun cancel(item: DownloadItem) {
        if (item.synced || sync.isWanted(item.track.id)) sync.deleteDownloads(listOf(item.track)) else downloads.cancel(item.track.id)
    }

    /** Deletes a finished download (file + record); songs a synced collection keeps are excluded from it first. */
    fun delete(item: DownloadItem) = sync.deleteDownloads(listOf(item.track))

    fun retry(item: DownloadItem) = downloads.retry(item.track.id)

    fun retryAll(items: List<DownloadItem>) = items.forEach { downloads.retry(it.track.id) }

    fun play(tracks: List<Track>, index: Int, name: String) =
        AppGraph.playerControls.playContext(tracks, index, "downloads", name)

    // ---- sync -----------------------------------------------------------------------------------------------------

    fun syncNow(ruleId: String) = sync.syncNow(ruleId)
    fun pauseSync() = SettingsStore.update { it.copy(syncPaused = true) }
    fun resumeSync() = sync.syncNow()
    fun retryFailed(ruleId: String) = sync.retryFailed(ruleId)
    fun disableNeedsChoice(ruleId: String) = sync.disableNeedsChoice(ruleId)
    fun disable(ruleId: String, deleteFiles: Boolean) = sync.disable(ruleId, deleteFiles)
    fun excluded(ruleId: String): List<SyncRules.Excluded> = sync.excluded(ruleId)
    fun include(ids: List<String>, ruleId: String) = sync.includeTracks(ids, ruleId)
}
