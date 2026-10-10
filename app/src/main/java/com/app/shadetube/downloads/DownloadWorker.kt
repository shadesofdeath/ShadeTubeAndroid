package com.app.shadetube.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.shadetube.core.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Process-wide handles the workers use (AppGraph owns the instances; a worker may start the process, in which case
 * Application.onCreate -> AppGraph builds and loads them before [ready] completes).
 *
 * Setup (AppGraph, after `manager.load()` / `sync.load()`): `DownloadsRuntime.install(context, manager, sync)`, then
 * `manager.start()`, `sync.start()`.
 */
object DownloadsRuntime {
    @Volatile var manager: DownloadManager? = null
        private set
    @Volatile var sync: DownloadSync? = null
        private set
    private val readySignal = CompletableDeferred<Unit>()

    fun install(context: Context, manager: DownloadManager, sync: DownloadSync?) {
        val app = context.applicationContext
        this.manager = manager
        this.sync = sync
        manager.onWorkAvailable = { DownloadWorker.ensure(app) }
        if (sync != null) SyncWorker.schedule(app)
        readySignal.complete(Unit)
    }

    /** Waits (at most [timeoutMs]) until AppGraph installed the services. */
    suspend fun awaitReady(timeoutMs: Long = 15_000): Boolean = withTimeoutOrNull(timeoutMs) { readySignal.await() } != null
}

/**
 * Texts of the download notification. The backend has no user-facing strings: the UI layer replaces these with its
 * strings.xml values at startup ([DownloadNotifications.labels]); the English defaults only show if it does not.
 */
data class DownloadNotificationLabels(
    val channelName: String = "Downloads",
    val title: String = "Downloading music",
    /** "%d more in the queue". */
    val queuedFormat: String = "%d more in the queue",
)

object DownloadNotifications {
    const val CHANNEL_ID = "downloads"
    const val NOTIFICATION_ID = 0x5D01

    @Volatile var labels = DownloadNotificationLabels()

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, labels.channelName, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    fun build(context: Context, s: DownloadsSnapshot?): Notification {
        ensureChannel(context)
        val l = labels
        val active = s?.items?.firstOrNull { it.track.id == s.activeId }
        val queued = s?.items?.count { it.state == DlState.Queued } ?: 0
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (active != null) {
            b.setContentTitle(active.track.name.ifEmpty { l.title })
            b.setContentText(active.track.artistLine)
            b.setProgress(100, (active.progress * 100).toInt().coerceIn(0, 100), active.progress <= 0f)
        } else {
            b.setContentTitle(l.title)
            b.setProgress(0, 0, true)
        }
        if (queued > 0) b.setSubText(runCatching { l.queuedFormat.format(queued) }.getOrDefault(queued.toString()))
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            b.setContentIntent(
                android.app.PendingIntent.getActivity(context, 0, launch, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        return b.build()
    }

    fun foregroundInfo(context: Context, s: DownloadsSnapshot?): ForegroundInfo {
        val n = build(context, s)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }
}

/**
 * Keeps the process alive (a `dataSync` foreground service with a progress notification) while the download queue has
 * work, and restarts it after the process was killed.
 *
 * Design: the queue itself is a coroutine of [DownloadManager] in appScope (so downloads run the same with or without
 * a worker, e.g. while the app is open); this worker only elevates the process while work runs: it is enqueued
 * (expedited when quota allows) whenever a download starts, watches the queue, updates the notification and returns
 * once nothing can run. When downloads wait for the network (offline, or Wi-Fi only / sync on a metered connection)
 * it leaves a constrained worker behind that wakes the app when the right network appears; the queue is persisted, so
 * that works after the process died too.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        DownloadNotifications.foregroundInfo(applicationContext, DownloadsRuntime.manager?.state?.value)

    override suspend fun doWork(): Result {
        pending = false
        if (!DownloadsRuntime.awaitReady()) return Result.success()
        val m = DownloadsRuntime.manager ?: return Result.success()
        active = true
        try {
            m.tick()
            if (!m.hasRunnableWork()) return Result.success().also { scheduleWaiter(m) }
            try {
                setForeground(getForegroundInfo())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Android 12+ may refuse a foreground service from the background (no expedited quota left): the
                // downloads still run while the process lives.
                Log.w("downloads", "could not go foreground: ${e.message}")
            }
            var idleSince = 0L
            var shown: Triple<String?, Int, Int>? = null
            while (true) {
                val now = System.currentTimeMillis()
                if (m.hasRunnableWork()) {
                    idleSince = 0
                } else {
                    if (idleSince == 0L) idleSince = now
                    if (now - idleSince >= IDLE_GRACE_MS) {
                        active = false
                        if (!m.hasRunnableWork()) break // re-checked after clearing the flag (see ensure)
                        active = true
                        idleSince = 0
                    }
                }
                val s = m.state.value
                val key = Triple(s.activeId, (s.items.firstOrNull { it.track.id == s.activeId }?.progress?.times(100))?.toInt() ?: -1, s.activeCount)
                if (key != shown) {
                    shown = key
                    runCatching { setForeground(DownloadNotifications.foregroundInfo(applicationContext, s)) }
                }
                delay(1000)
            }
            scheduleWaiter(m)
            return Result.success()
        } finally {
            active = false
        }
    }

    private fun scheduleWaiter(m: DownloadManager) {
        val wait = m.queueWait() ?: return
        val type = when (wait) {
            QueueWait.Offline -> NetworkType.CONNECTED
            QueueWait.WifiOnly -> NetworkType.UNMETERED
            // Sync downloads waiting on a metered connection (a paused sync needs the user: the gate re-pumps then).
            QueueWait.SyncGate -> if (DownloadsRuntime.sync?.allowedNow() == false && !com.app.shadetube.core.SettingsStore.current.syncPaused) NetworkType.UNMETERED else return
        }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(type).build())
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(WAITER_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val WORK_NAME = "downloads"
        const val WAITER_NAME = "downloads-network"
        private const val IDLE_GRACE_MS = 3000L

        /** A worker is watching the queue in this process. */
        @Volatile private var active = false
        /** One was enqueued and has not started yet. */
        @Volatile private var pending = false

        /** Called by the queue when a download starts: makes sure a worker keeps the process alive. */
        fun ensure(context: Context) {
            if (active || pending) return
            pending = true
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // APPEND_OR_REPLACE: a worker that is just finishing runs this one right after it.
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }.onFailure {
                pending = false
                Log.w("downloads", "could not enqueue the download worker", it)
            }
        }
    }
}

/**
 * Download sync while the app is not open: every hour (any network) the due rules are listed (no startup delay);
 * what they queue is downloaded by [DownloadWorker]. While the app runs, [DownloadSync]'s own 2 s tick does this.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!DownloadsRuntime.awaitReady()) return Result.success()
        val sync = DownloadsRuntime.sync ?: return Result.success()
        return try {
            sync.runBackgroundPass()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("sync", "background pass failed", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "download-sync"
        private const val EVERY_MINUTES = 60L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(EVERY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }.onFailure { Log.w("sync", "could not schedule the sync worker", it) }
        }
    }
}
