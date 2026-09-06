package com.focuslock.app.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.WorkRecordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.regex.Pattern

class TickTickNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val TAG = "TickTickListener"

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        // TickTick package is typically com.ticktick.task
        if (packageName != "com.ticktick.task") return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        val fullContent = "$title $text $subText $bigText".trim()
        Log.d(TAG, "Received TickTick notification: $fullContent")

        scope.launch {
            val settings = FocusLockApplication.instance.settingsRepository
            val bank = FocusLockApplication.instance.creditBankRepository

            if (!settings.tickTickNotificationEnabledFlow.first()) {
                return@launch
            }

            // Check if notification represents completed focus/pomodoro or completed task
            val parsedDuration = parseDurationFromNotification(fullContent)
            val isTaskCompleted = isTaskCompletionNotification(fullContent)

            if (parsedDuration > 0 || isTaskCompleted) {
                val effectiveDuration = if (parsedDuration > 0) parsedDuration else 25 // Default 25m pomodoro if not explicit
                val recordTitle = if (title.isNotBlank()) title else "TickTick Focus Session"

                val record = TickTickWorkRecord(
                    id = "notif_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                    title = recordTitle,
                    durationMinutes = effectiveDuration,
                    source = WorkRecordSource.TICKTICK_NOTIFICATION,
                    projectName = "TickTick"
                )

                // Re-read settings safely: notification toggle already checked above
                val workRatio = settings.workRatioFlow.first()
                val bonus = settings.taskBonusFlow.first()

                // Dedupe guard: CreditBankRepository ignores already-credited IDs
                if (bank.hasCreditedTask(record.id)) return@launch
                val earnedMinutes = bank.recordWorkCredit(record, workRatio, bonus)

                Log.i(TAG, "Credited $earnedMinutes doomscroll minutes for $effectiveDuration min TickTick work!")

                // Broadcast update so active blocker or UI refreshes immediately.
                // FIX: previously used setPackage(ticktick package) so FocusLock never received it.
                val intent = Intent(ACTION_CREDIT_UPDATED).apply {
                    putExtra("earnedMinutes", earnedMinutes)
                    putExtra("title", recordTitle)
                    setPackage(applicationContext.packageName)
                }
                sendBroadcast(intent)
            }
        }
    }

    companion object {
        const val ACTION_CREDIT_UPDATED = "com.focuslock.app.ACTION_CREDIT_UPDATED"

        fun parseDurationFromNotification(content: String): Int {
            val lower = content.lowercase()

            // Explicit durations win first, e.g. "Focus 50 mins", "1.5 hours"
            val hourPattern = Pattern.compile("(\\d+(\\.\\d+)?)\\s*(h|hr|hrs|hour|hours)")
            val hourMatcher = hourPattern.matcher(lower)
            if (hourMatcher.find()) {
                val hours = hourMatcher.group(1)?.toDoubleOrNull() ?: 0.0
                val minutes = (hours * 60).toInt()
                // Guard against absurd values from unrelated notifications
                if (minutes in 1..480) return minutes
            }

            val minPattern = Pattern.compile("(\\d+)\\s*(m|min|mins|minute|minutes)")
            val minMatcher = minPattern.matcher(lower)
            if (minMatcher.find()) {
                val minutes = minMatcher.group(1)?.toIntOrNull() ?: 0
                if (minutes in 1..480) return minutes
            }

            // Keyword triggers only when they look like TickTick focus completions,
            // not generic system notifications. Require focus/pomodoro/session wording.
            val hasFocusKeyword = lower.contains("pomodoro") ||
                lower.contains("focus") && (lower.contains("complet") || lower.contains("finish") || lower.contains("done") || lower.contains("session")) ||
                lower.contains("session complet")
            if (hasFocusKeyword) {
                return 25 // Standard default pomodoro block
            }

            return 0
        }

        fun isTaskCompletionNotification(content: String): Boolean {
            val lower = content.lowercase()
            // Tighten to avoid false positives like "Download completed" or "Update done".
            // Require task-ish context alongside completion wording.
            val completionWord = lower.contains("task complet") ||
                lower.contains("task done") ||
                lower.contains("checked off") ||
                lower.contains("marked complete") ||
                (lower.contains("completed") && (lower.contains("task") || lower.contains("ticktick") || lower.contains("todo") || lower.contains("checklist")))
            return completionWord
        }
    }
}
