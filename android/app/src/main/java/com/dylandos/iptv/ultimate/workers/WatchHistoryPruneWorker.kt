package com.dylandos.iptv.ultimate.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.dylandos.iptv.ultimate.data.db.dao.WatchHistoryDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * DYLANDOS IPTV ULTIMATE — Watch History Prune Worker
 *
 * Runs once every 24 hours (PeriodicWork).
 * Deletes WatchHistory entries older than 90 days to keep the DB lean.
 *
 * Scheduled in DylandosApp.onCreate() via WorkManager.
 */
@HiltWorker
class WatchHistoryPruneWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val watchHistoryDao: WatchHistoryDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val cutoff = System.currentTimeMillis() - NINETY_DAYS_MS
            watchHistoryDao.deleteOlderThan(cutoff)
            Timber.d("WatchHistoryPruneWorker: deleted entries older than 90 days (cutoff=$cutoff)")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "WatchHistoryPruneWorker failed")
            Result.retry()
        }
    }

    companion object {
        private const val NINETY_DAYS_MS = 90L * 24 * 60 * 60 * 1000
        private const val WORK_NAME = "watch_history_prune"

        /** Schedule or replace the periodic pruning job. Call once from Application.onCreate(). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchHistoryPruneWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
