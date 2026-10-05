package com.focuslock.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.focuslock.app.FocusLockApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.regex.Pattern

/**
 * TickTick notifications do not provide a trustworthy record that focus time was
 * logged. Keep the listener registered for compatibility, but never award credit
 * from notification text. A real focus-session data source is required first.
 */
class TickTickNotificationListener : NotificationListenerService() {

    private var listenerScope: CoroutineScope? = null
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CREDIT_UPDATED) refreshBlockedNotifications()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerScope?.cancel()
        runCatching { unregisterReceiver(refreshReceiver) }
        listenerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        runCatching { ContextCompat.registerReceiver(this, refreshReceiver, IntentFilter(ACTION_CREDIT_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED) }
        refreshBlockedNotifications()
        val app = FocusLockApplication.instance
        listenerScope!!.launch {
            merge(
                app.frogRepository.frogStateFlow.map { Unit },
                app.settingsRepository.blockedAppsFlow.map { Unit },
                app.settingsRepository.strictModeFlow.map { Unit },
                app.blockSchedulesRepository.schedulesFlow.map { Unit },
                app.permanentBlocksRepository.packagesFlow.map { Unit },
                app.creditBankRepository.liveBalanceSeconds.map { Unit },
                app.settingsRepository.nukeActiveFlow.map { Unit },
                app.homeLocationRepository.homeOnlyFlow.map { Unit },
                app.targetGroupsRepository.groups.map { Unit },
            ).collect { refreshBlockedNotifications() }
        }
        listenerScope!!.launch {
            while (isActive) { delay(30_000); refreshBlockedNotifications() }
        }
    }

    override fun onListenerDisconnected() {
        runCatching { unregisterReceiver(refreshReceiver) }
        listenerScope?.cancel()
        listenerScope = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) listenerScope?.launch { cancelIfBlocked(sbn) }
        // Notification titles and bodies can describe reminders, tasks, timers, or
        // breaks. None of these prove that focus time was actually logged.
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(refreshReceiver) }
        listenerScope?.cancel()
        listenerScope = null
        super.onDestroy()
    }

    private fun refreshBlockedNotifications() {
        listenerScope?.launch {
            val state = runCatching { FocusLockApplication.instance.frogRepository.currentState() }.getOrNull() ?: return@launch
            runCatching { activeNotifications.orEmpty() }.getOrDefault(emptyArray()).forEach { cancelIfBlocked(it, state) }
        }
    }

    private suspend fun cancelIfBlocked(sbn: StatusBarNotification, state: com.focuslock.app.data.model.FrogState? = null) {
        val frogState = state ?: runCatching { FocusLockApplication.instance.frogRepository.currentState() }.getOrNull() ?: return
        if (BlockedNotificationPolicy.shouldCancel(this, sbn.packageName, frogState)) {
            runCatching { cancelNotification(sbn.key) }.onSuccess {
                android.util.Log.i("FocusLockNotifications", "Requested dismissal of blocked notification from ${sbn.packageName}")
            }
        }
    }

    companion object {
        const val ACTION_CREDIT_UPDATED = "com.focuslock.app.CREDIT_UPDATED"

        private val COMPLETED_FOCUS_PATTERN = Pattern.compile(
            "\\b(?:(?:focus|pomodoro|deep\\s+work)(?:\\s+(?:focus\\s+)?session)?\\s+(?:is\\s+)?(?:completed|complete|finished|ended)|(?:completed|finished|ended)\\s+(?:focus|pomodoro|deep\\s+work)(?:\\s+(?:focus\\s+)?session)?)\\b"
        )
        private val HOUR_MIN_PATTERN = Pattern.compile(
            "(?<![\\w.])(\\d+)\\s*hours?\\s+(\\d+)\\s*min(?:s|ute|utes)?\\b"
        )
        private val HOUR_PATTERN = Pattern.compile(
            "(?<![\\w.])(\\d+(?:\\.\\d+)?)\\s*(?:h|hr|hrs|hour|hours)\\b"
        )
        private val MIN_PATTERN = Pattern.compile(
            "(?<![\\w.])(\\d+)\\s*(?:m|min|mins|minute|minutes)\\b"
        )
        private val BREAK_CLAUSE_PATTERN = Pattern.compile(
            "\\b(?:take\\s+a\\s+)?\\d+\\s*-?\\s*(?:m|min|mins|minute|minutes)\\s*(?:break|rest)\\b[^.]*"
        )
        private val NON_COMPLETION_PATTERN = Pattern.compile(
            "\\b(?:starting|started|ongoing|in\\s+progress|remaining|countdown|break|rest)\\b"
        )

        /** Classifies text only; this result must not be used to award credit. */
        fun isFocusCompletionNotification(content: String): Boolean {
            val lower = content.lowercase()
            return COMPLETED_FOCUS_PATTERN.matcher(lower).find() &&
                !NON_COMPLETION_PATTERN.matcher(lower).find() &&
                !isTaskCompletionNotification(lower)
        }

        fun deterministicRecordId(key: String, text: String): String {
            return "notif_${key.hashCode()}_${text.hashCode()}"
        }

        /**
         * Extracts an explicit duration from text that also describes a completed
         * focus session. It is a parser helper, not evidence suitable for credit.
         */
        fun parseDurationFromNotification(content: String): Int {
            if (!isFocusCompletionNotification(content)) return 0
            val lower = BREAK_CLAUSE_PATTERN.matcher(content.lowercase()).replaceAll(" ")

            val hourMinMatcher = HOUR_MIN_PATTERN.matcher(lower)
            if (hourMinMatcher.find()) {
                val hours = hourMinMatcher.group(1)?.toIntOrNull() ?: 0
                val mins = hourMinMatcher.group(2)?.toIntOrNull() ?: 0
                val total = hours * 60 + mins
                if (total in 1..480) return total
            }

            val hourMatcher = HOUR_PATTERN.matcher(lower)
            if (hourMatcher.find()) {
                val hours = hourMatcher.group(1)?.toDoubleOrNull() ?: 0.0
                val minutes = (hours * 60).toInt()
                if (minutes in 1..480) return minutes
            }

            val minMatcher = MIN_PATTERN.matcher(lower)
            if (minMatcher.find()) {
                val minutes = minMatcher.group(1)?.toIntOrNull() ?: 0
                if (minutes in 1..480) return minutes
            }
            return 0
        }

        fun isTaskCompletionNotification(content: String): Boolean {
            val lower = content.lowercase()
            val completionWord = lower.contains("task complet") ||
                lower.contains("task done") ||
                lower.contains("checked off") ||
                lower.contains("marked complete") ||
                (lower.contains("completed") && (lower.contains("task") || lower.contains("ticktick") || lower.contains("todo") || lower.contains("checklist")))
            return completionWord
        }
    }
}
