package com.focuslock.app.work

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.ui.MainActivity
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Posts a once-a-day nudge to log focus work on the existing alerts channel.
 * If the user has not granted POST_NOTIFICATIONS the work completes silently
 * (no crash, no retry storm) — the reminder simply does nothing.
 */
class DailyReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        // Re-anchor (DST fix, item 13) on every terminal path so the next fire stays
        // pinned to the intended wall-clock time. Best-effort: see the scheduler note.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            DailyReminderScheduler.reAnchorAfterRun(context)
            return Result.success()
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, FocusLockApplication.CHANNEL_ALERTS)
            .setContentTitle("Log your focus work for today")
            .setContentText("Open FocusLock and log today's work to earn your screen time.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        DailyReminderScheduler.reAnchorAfterRun(context)
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "focuslock_daily_reminder"
        private const val NOTIFICATION_ID = 4201
    }
}

/** Schedules/cancels the unique daily reminder periodic work. */
object DailyReminderScheduler {

    /**
     * (Re)schedules the reminder for the next local occurrence of [minuteOfDay]
     * (0..1439), repeating every 24h. Uses CANCEL_AND_REENQUEUE so a time change
     * immediately takes effect (UPDATE would preserve the old next-run time).
     */
    fun schedule(context: Context, minuteOfDay: Int) {
        val request = PeriodicWorkRequestBuilder<DailyReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(minutesUntilNextOccurrence(LocalDateTime.now(), minuteOfDay), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            DailyReminderWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(DailyReminderWorker.UNIQUE_WORK_NAME)
    }

    /**
     * DST re-anchor (item 13): PeriodicWorkRequest repeats every 24h of elapsed
     * time, so a DST shift (or timezone change) permanently moves the fire time by
     * an hour. Called at the END of each worker run: re-enqueueing with a fresh
     * delay to the next wall-clock occurrence re-anchors the reminder after every
     * shift. Best-effort — if it fails the original periodic chain keeps firing
     * (worst case: the pre-existing ≤1h drift). Never resurrects a disabled
     * reminder: when the toggle is off, cancel() owns cancellation.
     */
    suspend fun reAnchorAfterRun(context: Context) {
        try {
            val settings = SettingsRepository(context)
            if (!settings.dailyReminderEnabledFlow.first()) return
            schedule(context, settings.dailyReminderMinuteOfDayFlow.first())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DailyReminder", "reminder re-anchor failed; keeping existing schedule", e)
        }
    }

    /** Minutes from [now] to the next occurrence of [minuteOfDay] local wall-clock time. */
    internal fun minutesUntilNextOccurrence(now: LocalDateTime, minuteOfDay: Int): Long {
        val minute = minuteOfDay.coerceIn(0, 1439)
        val target = now.toLocalDate().atTime(minute / 60, minute % 60)
        val next = if (target.isAfter(now)) target else target.plusDays(1)
        return Duration.between(now, next).toMinutes().coerceAtLeast(0L)
    }
}
