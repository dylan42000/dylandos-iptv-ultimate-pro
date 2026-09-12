package com.dylandos.iptv.ultimate.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.dylandos.iptv.ultimate.ui.MainActivity
import java.util.concurrent.TimeUnit

class ProgramReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (System.currentTimeMillis() >= inputData.getLong("end", 0)) return Result.success()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "program_reminders"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, "Program reminders", NotificationManager.IMPORTANCE_HIGH))
        val pending = PendingIntent.getActivity(applicationContext, id.hashCode(), Intent(applicationContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_GUIDE
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(inputData.getString("title") ?: "Program reminder")
            .setContentText("${inputData.getString("channel")} · Open the guide to tune in")
            .setContentIntent(pending).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        return try { manager.notify(id.hashCode(), notification); Result.success() }
        catch (_: SecurityException) { Result.failure() }
    }
    companion object {
        fun key(channelId: Int, startMs: Long) = "program_reminder_${channelId}_$startMs"
        fun schedule(context: Context, channelId: Int, channel: String, title: String, startMs: Long, endMs: Long) {
            val request = OneTimeWorkRequestBuilder<ProgramReminderWorker>()
                .setInitialDelay((startMs - System.currentTimeMillis() - 120_000L).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("title" to title, "channel" to channel, "end" to endMs)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(key(channelId, startMs), ExistingWorkPolicy.REPLACE, request)
        }
    }
}
