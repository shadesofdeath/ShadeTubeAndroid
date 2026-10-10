package com.app.shadetube.podcasts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.shadetube.core.Log
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Background refresh of the podcast subscriptions (the C++ housekeeping tick checked every 10 minutes and refreshed
 * feeds older than 3 hours; on Android the process may not be running, so WorkManager runs it every
 * [Podcasts.REFRESH_EVERY_SEC] on any network). New episodes are reported through [Podcasts.events] and the
 * subscriptions' new-episode counts (the badge); a notification, if wanted, is the UI layer's job.
 *
 * Setup (central): call [schedule] once at startup (e.g. AppGraph.init). WorkManager's default initializer (androidx
 * startup provider, merged from the library manifest) is enough; no manifest entry is needed.
 */
class PodcastRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            Podcasts.init(startHousekeeping = false)
            when (val r = Podcasts.refreshAll(manual = false)) {
                is RefreshResult.Done -> Log.i("podcasts", "background refresh: ${r.refreshed} feed(s), ${r.added} new, ${r.failed} failed")
                else -> Unit
            }
            Podcasts.store.saveIfDirty()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("podcasts", "background refresh failed", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "podcast-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PodcastRefreshWorker>(Podcasts.REFRESH_EVERY_SEC, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
