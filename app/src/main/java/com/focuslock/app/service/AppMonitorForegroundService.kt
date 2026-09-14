package com.focuslock.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.R
import com.focuslock.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AppMonitorForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Only one collector may be alive at a time: onStartCommand can fire repeatedly
    // (every MainActivity.onCreate) and must not stack duplicate collectors.
    private var updateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("FocusLock Active: Monitoring doomscroll apps"))

        updateJob?.cancel()
        updateJob = scope.launch {
            var lastNotifiedMinuteBucket = Long.MIN_VALUE
            var lastLockedState: Boolean? = null
            var lastNotifyAt = 0L
            FocusLockApplication.instance.creditBankRepository.liveBalanceSeconds.collectLatest { seconds ->
                val now = System.currentTimeMillis()
                val locked = seconds <= 0
                // Throttle: notify only on minute-bucket change, locked/unlocked flip,
                // or at most every 10s — never on every per-second tick.
                val minuteBucket = if (locked) Long.MIN_VALUE else seconds / 60
                val minuteChanged = minuteBucket != lastNotifiedMinuteBucket
                val lockFlipped = lastLockedState == null || locked != lastLockedState
                val timeElapsed = now - lastNotifyAt >= NOTIFY_THROTTLE_MS
                if (!minuteChanged && !lockFlipped && !timeElapsed && lastNotifyAt != 0L) {
                    return@collectLatest
                }
                lastNotifiedMinuteBucket = minuteBucket
                lastLockedState = locked
                lastNotifyAt = now
                val minutes = (seconds.coerceAtLeast(0)) / 60
                val secRem = (seconds.coerceAtLeast(0)) % 60
                val text = if (!locked) {
                    "Available Screen Time: ${minutes}m ${secRem}s"
                } else {
                    "Screen Time Locked! Complete work in TickTick to unlock."
                }
                val notification = buildNotification(text)
                val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
        }

        return START_STICKY
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FocusLockApplication.CHANNEL_MONITOR)
            .setContentTitle("FocusLock Protection")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        updateJob = null
        scope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFY_THROTTLE_MS = 10_000L
    }
}
