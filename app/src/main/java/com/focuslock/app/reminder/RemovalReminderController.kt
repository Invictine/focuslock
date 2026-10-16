package com.focuslock.app.reminder

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.focuslock.app.R

/** Launch policy shared by Android settings, accessibility, and device-admin callbacks. */
object RemovalReminderController {
    private const val CHANNEL_ID = "removal_reminder"
    private const val NOTIFICATION_ID = 0x524D
    private const val PREFS = "removal_reminder_launch_policy"
    private const val KEY_UNTIL = "suppressed_until_elapsed"
    private const val KEY_BOOT = "suppressed_boot_count"
    private const val SUPPRESSION_MS = 60_000L
    private const val REPEAT_THROTTLE_MS = 1_500L

    private val lock = Any()
    @Volatile private var activityVisible = false
    @Volatile private var lastLaunchElapsed = 0L
    @Volatile private var launchGeneration = 0L
    @Volatile private var acknowledgedGeneration = 0L

    internal val isShowing: Boolean get() = activityVisible

    /**
     * Opens the popup for an explicit preview or an enabled reminder. A true result
     * means a direct launch was requested. Android can silently deny a background
     * activity launch, so background requests also get a delayed fallback unless the
     * Activity confirms that it reached onResume().
     */
    fun show(context: Context, reason: String, fromForeground: Boolean = false): Boolean {
        val app = context.applicationContext
        val preview = reason == RemovalReminderActivity.REASON_PREVIEW
        if (!preview && !RemovalReminderStore(app).enabled) return false
        val explicitAction = reason == RemovalReminderActivity.REASON_DEACTIVATE
        val isAdminDisabled = reason == RemovalReminderActivity.REASON_ADMIN_DISABLED
        if (com.focuslock.app.BuildConfig.DEBUG) {
            android.util.Log.d("RemovalReminder", "Request reason=$reason visible=$activityVisible " +
                "suppressed=${isSuppressed(app)} sinceLastMs=${SystemClock.elapsedRealtime() - lastLaunchElapsed}")
        }
        if (!preview && !explicitAction && isSuppressed(app)) return false

        val now = SystemClock.elapsedRealtime()
        val requestedGeneration = synchronized(lock) {
            if (activityVisible) return false
            if (!preview && !explicitAction && !isAdminDisabled && now - lastLaunchElapsed < REPEAT_THROTTLE_MS) return false
            lastLaunchElapsed = now
            launchGeneration++
            launchGeneration
        }

        // Post first when the caller is a background component. Overlay permission can
        // allow the activity attempt; if Android silently blocks it, the notification stays.
        val notifyFirst = context !is Activity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (notifyFirst) postFallbackNotification(app, reason)
        val mayStartFromBackground = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(app)
        if (context !is Activity && !fromForeground && !mayStartFromBackground) return false

        return try {
            val intent = RemovalReminderActivity.intent(app, reason).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            if (context !is Activity) scheduleLaunchAcknowledgmentFallback(app, reason, requestedGeneration)
            true
        } catch (_: Exception) {
            if (!notifyFirst) postFallbackNotification(app, reason)
            false
        }
    }

    /** Call after the user explicitly chooses Continue or to turn protection back on. */
    fun allowTemporarily(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_UNTIL, SystemClock.elapsedRealtime() + SUPPRESSION_MS)
            .putInt(KEY_BOOT, bootCount(context))
            .apply()
    }

    fun isSuppressed(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = bootCount(context)
        val now = SystemClock.elapsedRealtime()
        return prefs.getInt(KEY_BOOT, -1) == boot && now < prefs.getLong(KEY_UNTIL, 0L)
    }

    /** Activity lifecycle hooks keep duplicate callbacks from stacking popup windows. */
    internal fun onActivityVisible(context: Context) {
        synchronized(lock) {
            activityVisible = true
            acknowledgedGeneration = launchGeneration
        }
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    internal fun onActivityPaused() {
        synchronized(lock) { activityVisible = false }
    }

    internal fun onActivityDismissed() {
        // Back, Keep, and outside taps never authorize a later removal attempt.
        // Only allowTemporarily(), called by an explicit Continue, grants a window.
        synchronized(lock) {
            activityVisible = false
            lastLaunchElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun postFallbackNotification(context: Context, reason: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Removal reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Open your personal reminder before changing FocusLock protection" }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                reason.hashCode(),
                RemovalReminderActivity.intent(context, reason).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Pause before changing FocusLock")
                .setContentText("Tap to view your personal reminder.")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Notification delivery can be denied by the OS, OEM policy, or user settings.
        }
    }

    private fun scheduleLaunchAcknowledgmentFallback(context: Context, reason: String, generation: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            val shouldPostFallback = synchronized(lock) {
                RemovalLaunchAcknowledgment.shouldPostFallback(
                    generation, acknowledgedGeneration, launchGeneration,
                )
            }
            if (shouldPostFallback) postFallbackNotification(context, reason)
        }, 1_500L)
    }

    private fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    } catch (_: Exception) { 0 }
}

/** A launch only counts as displayed after the Activity reaches onResume(). */
internal object RemovalLaunchAcknowledgment {
    fun shouldPostFallback(
        requestedGeneration: Long,
        acknowledgedGeneration: Long,
        currentGeneration: Long,
    ): Boolean = acknowledgedGeneration < requestedGeneration && currentGeneration == requestedGeneration
}
