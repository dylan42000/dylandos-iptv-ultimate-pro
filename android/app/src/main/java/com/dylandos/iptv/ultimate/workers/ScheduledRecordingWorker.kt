package com.dylandos.iptv.ultimate.workers

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.dylandos.iptv.ultimate.data.repository.DvrRecordingRepository
import com.dylandos.iptv.ultimate.service.RecordingService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * DYLANDOS IPTV ULTIMATE — Scheduled Recording Worker
 *
 * Triggered by WorkManager at the scheduled recording start time.
 * Checks that a recording slot is available, then starts RecordingService.
 *
 * Schedule a recording:
 *   ScheduledRecordingWorker.schedule(
 *       context,
 *       recordingId   = uuid,
 *       streamUrl     = "http://...",
 *       channelName   = "CNN HD",
 *       channelId     = 1234,
 *       startTimeMs   = epochMs,
 *       durationMs    = 60 * 60 * 1000  // 1 hour
 *   )
 *
 * Cancel a scheduled recording:
 *   WorkManager.getInstance(context).cancelUniqueWork("scheduled_$recordingId")
 */
@HiltWorker
class ScheduledRecordingWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val dvrRepository: DvrRecordingRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: run {
            Timber.e("ScheduledRecordingWorker: missing recording_id — aborting")
            return Result.failure()
        }
        val streamUrl   = inputData.getString(KEY_STREAM_URL) ?: run {
            Timber.e("ScheduledRecordingWorker: missing stream_url for $recordingId — aborting")
            return Result.failure()
        }
        val channelName   = inputData.getString(KEY_CHANNEL_NAME) ?: "Channel"
        val channelId     = inputData.getInt(KEY_CHANNEL_ID, -1)
        val stopAtMs      = inputData.getLong(KEY_STOP_AT_MS, 0L)
        val storageUri    = inputData.getString(KEY_STORAGE_URI)
        val providerName  = inputData.getString(KEY_PROVIDER_NAME)

        if (stopAtMs in 1..System.currentTimeMillis()) {
            Timber.w("ScheduledRecordingWorker: $recordingId is already past its stop time")
            dvrRepository.removeScheduled(recordingId)
            return Result.success()
        }

        // Check slot availability
        val active = dvrRepository.activeCount()
        if (active >= RecordingService.MAX_CONCURRENT_RECORDINGS) {
            Timber.w("ScheduledRecordingWorker: all $active recording slots occupied — cannot start $channelName")
            notifySlotsFull(channelName)
            return Result.failure()
        }

        Timber.i("ScheduledRecordingWorker: starting $channelName ($recordingId)")

        // Start the foreground recording service
        val startIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
            putExtra(RecordingService.EXTRA_STREAM_URL,   streamUrl)
            putExtra(RecordingService.EXTRA_CHANNEL_NAME, channelName)
            storageUri?.let { putExtra(RecordingService.EXTRA_STORAGE_URI, it) }
            providerName?.let { putExtra(RecordingService.EXTRA_PROVIDER_NAME, it) }
            if (stopAtMs > 0L) putExtra(RecordingService.EXTRA_STOP_AT_MS, stopAtMs)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }

        // Durable fallback for the service's exact AlarmManager auto-stop.
        val remainingMs = (stopAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val stopRequest = OneTimeWorkRequestBuilder<StopRecordingWorker>()
            .setInitialDelay(remainingMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(StopRecordingWorker.KEY_RECORDING_ID to recordingId))
            .addTag("stop_$recordingId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "stop_$recordingId",
            ExistingWorkPolicy.REPLACE,
            stopRequest
        )

        return Result.success()
    }

    private fun notifySlotsFull(channelName: String) {
        // Simple Timber log — UI already handles "slots full" feedback via DvrViewModel
        Timber.w("ScheduledRecording: All 3 recording slots are in use. Could not record $channelName.")
    }

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
        const val KEY_STREAM_URL   = "stream_url"
        const val KEY_CHANNEL_NAME = "channel_name"
        const val KEY_CHANNEL_ID   = "channel_id"
        const val KEY_STOP_AT_MS   = "stop_at_ms"
        const val KEY_STORAGE_URI  = "storage_uri"
        const val KEY_PROVIDER_NAME = "provider_name"

        /**
         * Schedule a one-time recording that starts at [startTimeMs].
         * Runs shortly after the exact alarm as a durable backup. The service
         * ignores duplicate recording IDs, so this cannot create a second writer.
         */
        fun schedule(
            context: Context,
            recordingId: String = UUID.randomUUID().toString(),
            streamUrl: String,
            channelName: String,
            channelId: Int,
            triggerAtMs: Long,
            stopAtMs: Long,
            storageUri: String? = null,
            providerName: String? = null
        ): String {
            val delayMs = (triggerAtMs + 45_000L - System.currentTimeMillis())
                .coerceAtLeast(0L)

            val request = OneTimeWorkRequestBuilder<ScheduledRecordingWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(
                    KEY_RECORDING_ID to recordingId,
                    KEY_STREAM_URL   to streamUrl,
                    KEY_CHANNEL_NAME to channelName,
                    KEY_CHANNEL_ID   to channelId,
                    KEY_STOP_AT_MS   to stopAtMs,
                    KEY_STORAGE_URI  to storageUri,
                    KEY_PROVIDER_NAME to providerName
                ))
                .addTag("scheduled_recording")
                .addTag("scheduled_$recordingId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "scheduled_$recordingId",
                ExistingWorkPolicy.REPLACE,
                request
            )

            Timber.i("Scheduled recording '$channelName' in ${delayMs / 60_000}min (id=$recordingId)")
            return recordingId
        }

        /** Cancel a scheduled recording before it starts. */
        fun cancel(context: Context, recordingId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("scheduled_$recordingId")
            WorkManager.getInstance(context).cancelUniqueWork("stop_$recordingId")
            Timber.i("Cancelled scheduled recording $recordingId")
        }
    }
}

/**
 * StopRecordingWorker — sends STOP_RECORDING to RecordingService after the scheduled duration.
 * Enqueued by ScheduledRecordingWorker immediately after starting the service.
 */
@HiltWorker
class StopRecordingWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        Timber.i("StopRecordingWorker: stopping recording $recordingId")

        val stopIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
            putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
        }
        context.startService(stopIntent)

        return Result.success()
    }

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
    }
}
