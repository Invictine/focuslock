package com.focuslock.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.focuslock.app.data.repository.CreditBankRepository
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.sync.FocusSyncManager

class FocusLockApplication : Application() {

    lateinit var creditBankRepository: CreditBankRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var syncManager: FocusSyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        creditBankRepository = CreditBankRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        syncManager = FocusSyncManager(applicationContext, creditBankRepository, settingsRepository)

        // Clerk auth (optional until configured). Key comes from BuildConfig via
        // local.properties `clerk.publishableKey` — see README. Empty = offline mode.
        try {
            val clerkKey = com.focuslock.app.BuildConfig.CLERK_PUBLISHABLE_KEY.trim()
            if (clerkKey.startsWith("pk_")) {
                com.clerk.api.Clerk.initialize(this, publishableKey = clerkKey)
            }
        } catch (_: Exception) { }

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR,
                "Doomscroll Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active screen time countdown and focus monitoring status"
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Focus Lock Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when doomscroll time is expired or work is verified"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(monitorChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val CHANNEL_MONITOR = "focuslock_monitor_channel"
        const val CHANNEL_ALERTS = "focuslock_alerts_channel"

        lateinit var instance: FocusLockApplication
            private set
    }
}
