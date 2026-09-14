package com.focuslock.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId

private val Context.blockLogDataStore by preferencesDataStore(name = "focuslock_block_log")

/**
 * One blocked-app entry.
 *
 * @param reason one of "manual" | "limit" | "schedule" | "nuke".
 */
@Serializable
data class BlockEvent(
    val packageName: String,
    val timestampMillis: Long,
    val reason: String
)

/**
 * Append-only log of blocked app entries (newest first, capped at [MAX_EVENTS]).
 *
 * Writes are serialized with a [Mutex] and mirrored into an in-memory
 * [MutableStateFlow] so the enforcement path never has to pre-read DataStore.
 */
class BlockLogRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val writeMutex = Mutex()

    object Keys {
        val EVENTS_JSON = stringPreferencesKey("block_events_json")
    }

    private val _events = MutableStateFlow<List<BlockEvent>>(emptyList())

    val eventsFlow: Flow<List<BlockEvent>> = context.blockLogDataStore.data
        .map { prefs -> decode(prefs[Keys.EVENTS_JSON]) }
        .onEach { list -> _events.value = list }

    /** Number of block events since local midnight. */
    val blockedTodayFlow: Flow<Int> = eventsFlow.map { events ->
        val startOfToday = startOfTodayMillis()
        events.count { it.timestampMillis >= startOfToday }
    }

    val blockedTotalFlow: Flow<Int> = eventsFlow.map { it.size }

    /** Prepends an event, trims the oldest beyond [MAX_EVENTS]. */
    suspend fun record(packageName: String, reason: String) {
        val event = BlockEvent(
            packageName = packageName.ifBlank { "unknown" },
            timestampMillis = System.currentTimeMillis(),
            reason = reason.ifBlank { "manual" }
        )
        writeMutex.withLock {
            var updated: List<BlockEvent> = emptyList()
            context.blockLogDataStore.edit { prefs ->
                val current = decode(prefs[Keys.EVENTS_JSON])
                updated = (listOf(event) + current)
                    .sortedByDescending { it.timestampMillis }
                    .take(MAX_EVENTS)
                prefs[Keys.EVENTS_JSON] = json.encodeToString(updated)
            }
            _events.value = updated
        }
    }

    private fun decode(raw: String?): List<BlockEvent> = try {
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(raw)
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val MAX_EVENTS = 500

        fun startOfTodayMillis(): Long =
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
