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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        if (fullContent.isBlank()) return

        // FOCUS ONLY pre-filter (precompiled regex, cheap): credit solely when the
        // notification carries an explicit logged focus duration AND reads like a
        // focus/pomodoro completion. Filtering BEFORE scope.launch avoids spawning a
        // coroutine for every noise notification. Plain task completions credit NOTHING.
        val parsedDuration = parseDurationFromNotification(fullContent)
        if (parsedDuration <= 0 || !isFocusCompletionNotification(fullContent)) {
            Log.d(TAG, "Ignoring non-focus TickTick notification (no creditable focus duration)")
            return
        }

        // Capture the platform key now: the coroutine below needs a stable,
        // deterministic record ID (key + content hash) so dedup actually works.
        val notifKey = try { sbn.key } catch (_: Exception) { null }

        scope.launch {
            val settings = FocusLockApplication.instance.settingsRepository
            val bank = FocusLockApplication.instance.creditBankRepository

            if (!settings.tickTickNotificationEnabledFlow.first()) {
                return@launch
            }

            val recordTitle = if (title.isNotBlank()) title else "TickTick Focus Session"

            val record = TickTickWorkRecord(
                id = deterministicRecordId(notifKey.orEmpty(), fullContent),
                title = recordTitle,
                durationMinutes = parsedDuration,
                source = WorkRecordSource.TICKTICK_NOTIFICATION,
                projectName = "TickTick"
            )

            // Re-read settings safely: notification toggle already checked above
            val workRatio = settings.workRatioFlow.first()
            val bonus = settings.taskBonusFlow.first()

            // Dedupe guard: CreditBankRepository ignores already-credited IDs
            if (bank.hasCreditedTask(record.id)) return@launch
            val earnedMinutes = bank.recordWorkCredit(record, workRatio, bonus)

            Log.i(TAG, "Credited $earnedMinutes doomscroll minutes for $parsedDuration min TickTick work!")

            // Broadcast update so active blocker or UI refreshes immediately.
            val intent = Intent(ACTION_CREDIT_UPDATED).apply {
                putExtra("earnedMinutes", earnedMinutes)
                putExtra("title", recordTitle)
                setPackage(applicationContext.packageName)
            }
            sendBroadcast(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CREDIT_UPDATED = "com.focuslock.app.CREDIT_UPDATED"

        // Precompiled once: parseDurationFromNotification runs on every TickTick notification.
        private val HOUR_MIN_PATTERN = Pattern.compile("(\\d+)\\s*hours?\\s+(\\d+)\\s*min")
        private val HOUR_PATTERN = Pattern.compile("(\\d+(\\.\\d+)?)\\s*(h|hr|hrs|hour|hours)")
        private val MIN_PATTERN = Pattern.compile("(\\d+)\\s*(m|min|mins|minute|minutes)")
        // Break clauses must never be credited as focus time, e.g.
        // "Pomodoro finished! Take a 5-minute break." — the 5 min is the break,
        // not the completed focus session.
        private val BREAK_CLAUSE_PATTERN = Pattern.compile(
            "(take\\s+a\\s+)?\\d+\\s*-?\\s*(m|min|mins|minute|minutes)\\s*(break|rest)[^.]*"
        )

        fun isFocusCompletionNotification(content: String): Boolean {
            val lower = content.lowercase()
            val hasFocusTerm = lower.contains("pomodoro") ||
                lower.contains("focus") ||
                lower.contains("deep work")
            if (!hasFocusTerm) return false
            return lower.contains("complet") ||
                lower.contains("finish") ||
                lower.contains("done") ||
                lower.contains("session") ||
                lower.contains("end")
        }

        fun deterministicRecordId(key: String, text: String): String {
            return "notif_${key.hashCode()}_${text.hashCode()}"
        }

        fun parseDurationFromNotification(content: String): Int {
            // Drop break clauses first so their minutes are never credited as focus.
            val lower = BREAK_CLAUSE_PATTERN.matcher(content.lowercase()).replaceAll(" ")

            // Combined form first, e.g. "2 hours 15 min"
            val hourMinMatcher = HOUR_MIN_PATTERN.matcher(lower)
            if (hourMinMatcher.find()) {
                val hours = hourMinMatcher.group(1)?.toIntOrNull() ?: 0
                val mins = hourMinMatcher.group(2)?.toIntOrNull() ?: 0
                val total = hours * 60 + mins
                if (total in 1..480) return total
            }

            // Explicit durations, e.g. "Focus 50 mins", "1.5 hours"
            val hourMatcher = HOUR_PATTERN.matcher(lower)
            if (hourMatcher.find()) {
                val hours = hourMatcher.group(1)?.toDoubleOrNull() ?: 0.0
                val minutes = (hours * 60).toInt()
                // Guard against absurd values from unrelated notifications
                if (minutes in 1..480) return minutes
            }

            val minMatcher = MIN_PATTERN.matcher(lower)
            if (minMatcher.find()) {
                val minutes = minMatcher.group(1)?.toIntOrNull() ?: 0
                if (minutes in 1..480) return minutes
            }

            // No explicit focus duration. A completed Pomodoro that states no
            // duration is the standard 25-minute focus block, so credit that.
            // Every other notification credits nothing rather than guessing.
            if (isFocusCompletionNotification(content) && lower.contains("pomodoro")) {
                return 25
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
