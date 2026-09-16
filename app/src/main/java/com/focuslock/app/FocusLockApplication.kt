package com.focuslock.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.focuslock.app.data.repository.AppLimitsRepository
import com.focuslock.app.data.repository.BlockLogRepository
import com.focuslock.app.data.repository.BlockSchedulesRepository
import com.focuslock.app.data.repository.CreditBankRepository
import com.focuslock.app.data.repository.FrogRepository
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.sync.FocusSyncManager
import com.focuslock.app.work.DailyReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FocusLockApplication : Application() {

    lateinit var creditBankRepository: CreditBankRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var appLimitsRepository: AppLimitsRepository
        private set

    lateinit var blockSchedulesRepository: BlockSchedulesRepository
        private set

    lateinit var blockLogRepository: BlockLogRepository
        private set

    lateinit var frogRepository: FrogRepository
        private set

    lateinit var syncManager: FocusSyncManager
        private set

    /** Process-lifetime scope for lightweight startup reconciliation work. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        creditBankRepository = CreditBankRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        appLimitsRepository = AppLimitsRepository(applicationContext)
        blockSchedulesRepository = BlockSchedulesRepository(applicationContext)
        blockLogRepository = BlockLogRepository(applicationContext)
        frogRepository = FrogRepository(applicationContext)
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

        // WorkManager entries don't survive restore/reinstall; reconcile the daily
        // reminder's scheduled worker with the persisted preference at process start.
        appScope.launch {
            try {
                if (settingsRepository.dailyReminderEnabledFlow.first()) {
                    DailyReminderScheduler.schedule(
                        this@FocusLockApplication,
                        settingsRepository.dailyReminderMinuteOfDayFlow.first(),
                    )
                } else {
                    DailyReminderScheduler.cancel(this@FocusLockApplication)
                }
            } catch (e: Exception) {
                android.util.Log.w("FocusLockApplication", "daily reminder reconcile failed", e)
            }
        }
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
