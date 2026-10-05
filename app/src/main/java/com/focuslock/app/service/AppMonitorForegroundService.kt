package com.focuslock.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.app.NotificationManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class AppMonitorForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationPendingIntent: PendingIntent

    // Only one collector may be alive at a time: onStartCommand can fire repeatedly
    // (every MainActivity.onCreate) and must not stack duplicate collectors.
    private var updateJob: Job? = null
    private var tickTickSyncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Checking FocusLock status"))

        if (updateJob?.isActive != true) {
            updateJob = scope.launch(Dispatchers.IO) {
                while (true) {
                    val seconds = FocusLockApplication.instance.creditBankRepository.liveBalanceSeconds.first()
                    // Null means the location check failed. Keep the ordinary status then;
                    // do not claim the user is away when the device cannot tell.
                    val homeEnforcementAllowed = try {
                        FocusLockApplication.instance.homeLocationRepository.shouldEnforceNow()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val locked = seconds <= 0
                    val minutes = (seconds.coerceAtLeast(0)) / 60
                    val secRem = (seconds.coerceAtLeast(0)) % 60
                    val text = if (homeEnforcementAllowed == false) {
                        "Home-only boundaries paused · Permanent blocks active"
                    } else if (!locked) {
                        "Available Screen Time: ${minutes}m ${secRem}s"
                    } else {
                        "Screen Time Locked! Complete work in TickTick to unlock."
                    }
                    val notification = buildNotification(text)
                    notificationManager.notify(NOTIFICATION_ID, notification)
                    delay(NOTIFY_THROTTLE_MS)
                }
            }
        }

        if (tickTickSyncJob?.isActive != true) {
            tickTickSyncJob = scope.launch(Dispatchers.IO) {
                while (true) {
                    try {
                        FocusLockApplication.instance.tickTickFocusSync.sync()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Keep diagnostics useful without logging request details or credentials.
                        Log.w(TAG, "Periodic TickTick focus sync failed (${e.javaClass.simpleName})")
                    }
                    delay(TICKTICK_SYNC_INTERVAL_MS)
                }
            }
        }

        return START_STICKY
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, FocusLockApplication.CHANNEL_MONITOR)
            .setContentTitle("FocusLock Protection")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(notificationPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        updateJob = null
        tickTickSyncJob?.cancel()
        tickTickSyncJob = null
        scope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFY_THROTTLE_MS = 10_000L
        private const val TICKTICK_SYNC_INTERVAL_MS = 60_000L
        private const val TAG = "AppMonitorService"
    }
}
