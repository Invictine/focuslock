package com.focuslock.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime

private val Context.blockSchedulesDataStore by preferencesDataStore(name = "focuslock_block_schedules")

/**
 * One recurring block window.
 *
 * @param daysOfWeek 1=Mon .. 7=Sun (`java.time.DayOfWeek.value`). For overnight
 * windows the set refers to the day the window STARTS.
 * @param startMinuteOfDay inclusive start minute, 0..1439 local time.
 * @param endMinuteOfDay exclusive end minute, 0..1439 local time; when lower than
 * [startMinuteOfDay] the window wraps past midnight.
 */
@Serializable
data class BlockSchedule(
    val id: String,
    val label: String,
    val daysOfWeek: Set<Int>,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val enabled: Boolean = true
)

/**
 * Persists recurring block schedules.
 *
 * Hot-path note: the accessibility service polls [isScheduleActiveNow] on a ~30s
 * cadence. The schedule list is mirrored into an in-memory cache, so repeated
 * calls only touch DataStore once on first use (or after a write).
 */
class BlockSchedulesRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    object Keys {
        val SCHEDULES_JSON = stringPreferencesKey("block_schedules_json")
    }

    private val _schedules = MutableStateFlow<List<BlockSchedule>>(emptyList())
    private val loaded = MutableStateFlow(false)

    val schedulesFlow: Flow<List<BlockSchedule>> = context.blockSchedulesDataStore.data
        .map { prefs -> decode(prefs[Keys.SCHEDULES_JSON]) }
        .onEach { list ->
            _schedules.value = list
            loaded.value = true
        }

    /** Insert when [schedule]'s id is new, otherwise replace the existing entry. */
    suspend fun upsert(schedule: BlockSchedule) {
        if (schedule.id.isBlank()) return
        var updated: List<BlockSchedule> = emptyList()
        context.blockSchedulesDataStore.edit { prefs ->
            val current = decode(prefs[Keys.SCHEDULES_JSON]).toMutableList()
            val index = current.indexOfFirst { it.id == schedule.id }
            if (index >= 0) {
                current[index] = schedule
            } else {
                current.add(schedule)
            }
            updated = current
            prefs[Keys.SCHEDULES_JSON] = json.encodeToString(updated)
        }
        _schedules.value = updated
        loaded.value = true
    }

    suspend fun delete(id: String) {
        var updated: List<BlockSchedule> = emptyList()
        context.blockSchedulesDataStore.edit { prefs ->
            updated = decode(prefs[Keys.SCHEDULES_JSON]).filterNot { it.id == id }
            prefs[Keys.SCHEDULES_JSON] = json.encodeToString(updated)
        }
        _schedules.value = updated
        loaded.value = true
    }

    /** True when at least one enabled schedule covers the current local time. */
    suspend fun isScheduleActiveNow(): Boolean =
        activeScheduleAt(currentSchedules(), ZonedDateTime.now()) != null

    private suspend fun currentSchedules(): List<BlockSchedule> =
        if (loaded.value) _schedules.value else schedulesFlow.first()

    private fun decode(raw: String?): List<BlockSchedule> = try {
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(raw)
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        /**
         * Pure lookup used by both the repository and enforcement code.
         * Returns the first matching enabled schedule, or null.
         */
        fun activeScheduleAt(schedules: List<BlockSchedule>, now: ZonedDateTime): BlockSchedule? {
            val minuteOfDay = now.hour * 60 + now.minute
            val today = now.dayOfWeek.value // 1=Mon .. 7=Sun
            val yesterday = if (today == 1) 7 else today - 1
            return schedules.firstOrNull { schedule ->
                if (!schedule.enabled || schedule.daysOfWeek.isEmpty()) return@firstOrNull false
                val start = schedule.startMinuteOfDay.coerceIn(0, 1439)
                val end = schedule.endMinuteOfDay.coerceIn(0, 1439)
                when {
                    // Zero-length window is treated as inactive to avoid all-day locks
                    // from a misconfigured schedule.
                    start == end -> false
                    start < end -> today in schedule.daysOfWeek && minuteOfDay in start until end
                    else -> (today in schedule.daysOfWeek && minuteOfDay >= start) ||
                        (yesterday in schedule.daysOfWeek && minuteOfDay < end)
                }
            }
        }
    }
}
