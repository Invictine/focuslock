package com.focuslock.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.UserStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Context.bankDataStore by preferencesDataStore(name = "focuslock_bank")

class CreditBankRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    object Keys {
        val CREDIT_BALANCE_SECONDS = longPreferencesKey("credit_balance_seconds")
        val TOTAL_WORK_SECONDS_TODAY = longPreferencesKey("total_work_seconds_today")
        val TOTAL_SCROLL_SECONDS_TODAY = longPreferencesKey("total_scroll_seconds_today")
        val TASKS_COMPLETED_TODAY = intPreferencesKey("tasks_completed_today")
        val LAST_RESET_DATE = stringPreferencesKey("last_reset_date")
        val WORK_HISTORY_JSON = stringPreferencesKey("work_history_json")
        val CREDITED_IDS_JSON = stringPreferencesKey("credited_ids_json")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
    }

    // In-memory fast state for real-time countdown.
    // Synced from DataStore via onEach (no side effects inside map).
    private val _liveBalanceSeconds = MutableStateFlow(0L)
    val liveBalanceSeconds: StateFlow<Long> = _liveBalanceSeconds.asStateFlow()

    val statsFlow: Flow<UserStats> = context.bankDataStore.data
        .onEach { prefs ->
            _liveBalanceSeconds.value = prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L
        }
        .map { prefs ->
            val today = dateFormat.format(Date())
            val lastDate = prefs[Keys.LAST_RESET_DATE] ?: today
            val balanceSec = prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L

            if (lastDate != today) {
                // New day: keep unused credits, reset daily consumption.
                // Counters are persisted lazily by checkAndResetDailyStats on next write.
                UserStats(
                    creditBalanceMinutes = (balanceSec / 60).toInt(),
                    totalWorkMinutesToday = 0,
                    totalDoomscrollMinutesToday = 0,
                    tasksCompletedToday = 0,
                    lastResetDate = today
                )
            } else {
                UserStats(
                    creditBalanceMinutes = (balanceSec / 60).toInt(),
                    totalWorkMinutesToday = ((prefs[Keys.TOTAL_WORK_SECONDS_TODAY] ?: 0L) / 60).toInt(),
                    totalDoomscrollMinutesToday = ((prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] ?: 0L) / 60).toInt(),
                    tasksCompletedToday = prefs[Keys.TASKS_COMPLETED_TODAY] ?: 0,
                    lastResetDate = today
                )
            }
        }

    /** Work history filtered to today so a new day starts clean. */
    val workHistoryFlow: Flow<List<TickTickWorkRecord>> = context.bankDataStore.data.map { prefs ->
        val historyJson = prefs[Keys.WORK_HISTORY_JSON]
        val all: List<TickTickWorkRecord> = if (historyJson.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(historyJson)
            } catch (e: Exception) {
                emptyList()
            }
        }
        val startOfDay = startOfTodayMillis()
        all.filter { it.timestamp >= startOfDay }
    }

    /** Full recent history (including past days) for future stats screens. */
    val fullHistoryFlow: Flow<List<TickTickWorkRecord>> = context.bankDataStore.data.map { prefs ->
        val historyJson = prefs[Keys.WORK_HISTORY_JSON]
        if (historyJson.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(historyJson)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun getBalanceSeconds(): Long {
        checkAndResetDailyStats()
        return context.bankDataStore.data.first()[Keys.CREDIT_BALANCE_SECONDS] ?: 0L
    }

    suspend fun hasCreditedTask(taskId: String): Boolean {
        val ids = getCreditedIds()
        return ids.contains(taskId)
    }

    suspend fun getCreditedIds(): Set<String> {
        return try {
            val raw = context.bankDataStore.data.first()[Keys.CREDITED_IDS_JSON]
            if (raw.isNullOrBlank()) emptySet()
            else json.decodeFromString<Set<String>>(raw)
        } catch (e: Exception) {
            emptySet()
        }
    }

    suspend fun getLastSyncTimestamp(): Long {
        return context.bankDataStore.data.first()[Keys.LAST_SYNC_TIMESTAMP] ?: 0L
    }

    suspend fun setLastSyncTimestamp(timestamp: Long) {
        context.bankDataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_TIMESTAMP] = timestamp
        }
    }

    /**
     * Records work credit. Returns earned minutes, or 0 if this task ID was
     * already credited (prevents double-counting on repeated TickTick syncs).
     */
    suspend fun recordWorkCredit(record: TickTickWorkRecord, workRatio: Int, taskBonusMinutes: Int): Int {
        checkAndResetDailyStats()

        // Dedupe: same TickTick task ID must never credit twice
        val existingIds = getCreditedIds()
        if (existingIds.contains(record.id)) {
            return 0
        }

        val totalEarnedMinutes = calculateEarnedMinutes(record.durationMinutes, workRatio, taskBonusMinutes)
        val earnedSeconds = totalEarnedMinutes * 60L

        val enrichedRecord = record.copy(earnedMinutesCredited = totalEarnedMinutes)

        context.bankDataStore.edit { prefs ->
            val currentBalance = prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L
            val newBalance = currentBalance + earnedSeconds
            prefs[Keys.CREDIT_BALANCE_SECONDS] = newBalance
            _liveBalanceSeconds.value = newBalance

            val workSec = prefs[Keys.TOTAL_WORK_SECONDS_TODAY] ?: 0L
            prefs[Keys.TOTAL_WORK_SECONDS_TODAY] = workSec + (record.durationMinutes * 60L)

            val tasksToday = prefs[Keys.TASKS_COMPLETED_TODAY] ?: 0
            prefs[Keys.TASKS_COMPLETED_TODAY] = tasksToday + 1

            val currentHistory: List<TickTickWorkRecord> = try {
                val raw = prefs[Keys.WORK_HISTORY_JSON]
                if (!raw.isNullOrBlank()) json.decodeFromString(raw) else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val updatedHistory = listOf(enrichedRecord) + currentHistory.take(49)
            prefs[Keys.WORK_HISTORY_JSON] = json.encodeToString(updatedHistory)

            // Persist credited ID to prevent double-sync
            try {
                val rawIds = prefs[Keys.CREDITED_IDS_JSON]
                val currentIds: Set<String> = if (!rawIds.isNullOrBlank()) {
                    try { json.decodeFromString(rawIds) } catch (e: Exception) { existingIds }
                } else existingIds
                val updated = (currentIds + record.id).toList().takeLast(500).toSet()
                prefs[Keys.CREDITED_IDS_JSON] = json.encodeToString(updated)
            } catch (e: Exception) {
                // Non-fatal: history already saved
            }
        }

        return totalEarnedMinutes
    }

    /** Credits a batch of TickTick API records, skipping already-credited IDs. Returns total earned. */
    suspend fun recordWorkCreditsDeduped(
        records: List<TickTickWorkRecord>,
        workRatio: Int,
        taskBonusMinutes: Int
    ): Pair<Int, Int> {
        var totalEarned = 0
        var newCount = 0
        for (r in records) {
            if (hasCreditedTask(r.id)) continue
            val earned = recordWorkCredit(r, workRatio, taskBonusMinutes)
            if (earned > 0) {
                totalEarned += earned
                newCount++
            }
        }
        if (newCount > 0) {
            setLastSyncTimestamp(System.currentTimeMillis())
        }
        return Pair(newCount, totalEarned)
    }

    suspend fun consumeScrollTime(seconds: Long): Long {
        var remaining = 0L
        context.bankDataStore.edit { prefs ->
            val current = prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L
            remaining = maxOf(0L, current - seconds)
            prefs[Keys.CREDIT_BALANCE_SECONDS] = remaining
            _liveBalanceSeconds.value = remaining

            val currentScrollSec = prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] ?: 0L
            prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] = currentScrollSec + seconds
        }
        return remaining
    }

    suspend fun addEmergencyCredits(minutes: Int) {
        val seconds = minutes * 60L
        context.bankDataStore.edit { prefs ->
            val current = prefs[Keys.CREDIT_BALANCE_SECONDS] ?: 0L
            val updated = current + seconds
            prefs[Keys.CREDIT_BALANCE_SECONDS] = updated
            _liveBalanceSeconds.value = updated
        }
    }

    private suspend fun checkAndResetDailyStats() {
        val today = dateFormat.format(Date())
        context.bankDataStore.edit { prefs ->
            val lastReset = prefs[Keys.LAST_RESET_DATE] ?: today
            if (lastReset != today) {
                prefs[Keys.TOTAL_WORK_SECONDS_TODAY] = 0L
                prefs[Keys.TOTAL_SCROLL_SECONDS_TODAY] = 0L
                prefs[Keys.TASKS_COMPLETED_TODAY] = 0
                prefs[Keys.LAST_RESET_DATE] = today
                // Prune history older than 7 days to bound storage
                try {
                    val raw = prefs[Keys.WORK_HISTORY_JSON]
                    if (!raw.isNullOrBlank()) {
                        val all: List<TickTickWorkRecord> = json.decodeFromString(raw)
                        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                        val pruned = all.filter { it.timestamp >= cutoff }.take(50)
                        prefs[Keys.WORK_HISTORY_JSON] = json.encodeToString(pruned)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    companion object {
        /** Pure work→leisure conversion. Extracted for unit testing. */
        fun calculateEarnedMinutes(workMinutes: Int, workRatio: Int, taskBonusMinutes: Int): Int {
            val earnedFromTime = if (workRatio > 0) workMinutes / workRatio else workMinutes
            val earnedFromBonus = if (workMinutes > 0) taskBonusMinutes else 0
            return maxOf(1, earnedFromTime + earnedFromBonus)
        }

        fun startOfTodayMillis(): Long {
            return Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }
}
