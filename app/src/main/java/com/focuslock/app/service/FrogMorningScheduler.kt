package com.focuslock.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.focuslock.app.data.repository.FrogRepository
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Schedules the daily Frog wake trigger without requiring exact-alarm access. */
object FrogMorningScheduler {
    const val ACTION_FROG_MORNING_ALARM = "com.focuslock.app.action.FROG_MORNING_ALARM"
    const val ACTION_FROG_GRACE_EXPIRED = "com.focuslock.app.action.FROG_GRACE_EXPIRED"
    private const val REQUEST_CODE = 17421
    private const val GRACE_REQUEST_CODE = 17422

    fun scheduleGraceExpiry(context: Context, deadlineMillis: Long?) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = PendingIntent.getBroadcast(
            context, GRACE_REQUEST_CODE,
            Intent(context, FrogWakeReceiver::class.java).setAction(ACTION_FROG_GRACE_EXPIRED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            if (deadlineMillis == null) alarmManager.cancel(pending)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineMillis, pending)
        } catch (e: Exception) {
            android.util.Log.w("FrogMorningScheduler", "Grace expiry alarm unavailable", e)
        }
    }

    /** Schedule the next local wake-hour alarm, or cancel it while Frog is disabled. */
    fun schedule(context: Context, wakeHour: Int, enabled: Boolean) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = pendingIntent(context)
        try {
            if (!enabled) {
                alarmManager.cancel(pendingIntent)
                return
            }
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextWakeMillis(System.currentTimeMillis(), wakeHour),
                pendingIntent,
            )
        } catch (e: Exception) {
            android.util.Log.w("FrogMorningScheduler", "Morning alarm unavailable; unlock monitoring remains active", e)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, FrogWakeReceiver::class.java).setAction(ACTION_FROG_MORNING_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Next future local occurrence of [wakeHour], resolving DST gaps/overlaps by zone rules. */
    fun nextWakeMillis(
        nowMillis: Long,
        wakeHour: Int,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val hour = wakeHour.coerceIn(FrogRepository.MIN_WAKE_HOUR, FrogRepository.MAX_WAKE_HOUR)
        var candidate = now.toLocalDate().atTime(hour, 0).atZone(zoneId)
        if (!candidate.toInstant().isAfter(now.toInstant())) {
            candidate = candidate.plusDays(1).toLocalDate().atTime(hour, 0).atZone(zoneId)
        }
        return candidate.toInstant().toEpochMilli()
    }
}
