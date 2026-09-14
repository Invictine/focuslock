package com.focuslock.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focuslock.app.service.UsageStatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.appLimitsDataStore by preferencesDataStore(name = "focuslock_app_limits")

/**
 * A per-app daily screen-time limit.
 *
 * @param dailyMinutes 0 (or negative) disables enforcement for this app.
 * @param enabled lets callers temporarily park a limit without deleting it.
 */
@Serializable
data class AppLimit(
    val packageName: String,
    val dailyMinutes: Int,
    val enabled: Boolean = true
)

/**
 * Persists per-app daily limits.
 *
 * Hot-path note: the accessibility service checks limits on every foreground app
 * change. [limitsFlow] mirrors DataStore into an in-memory cache ([MutableStateFlow])
 * so suspend reads ([getLimit]) only touch DataStore once on first use and then
 * serve from memory. Writes keep the cache warm.
 */
class AppLimitsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    object Keys {
        val LIMITS_JSON = stringPreferencesKey("app_limits_json")
    }

    private val _limits = MutableStateFlow<Map<String, AppLimit>>(emptyMap())
    private val loaded = MutableStateFlow(false)

    val limitsFlow: Flow<Map<String, AppLimit>> = context.appLimitsDataStore.data
        .map { prefs -> decodeLimits(prefs[Keys.LIMITS_JSON]) }
        .onEach { map ->
            _limits.value = map
            loaded.value = true
        }

    /**
     * Adds or replaces the daily limit for [packageName]. When [enabled] is null the
     * existing `enabled` flag is preserved so a parked limit is not silently
     * re-enabled; pass an explicit value (e.g. config import) to override it.
     */
    suspend fun setLimit(packageName: String, dailyMinutes: Int, enabled: Boolean? = null) {
        if (packageName.isBlank()) return
        val minutes = dailyMinutes.coerceAtLeast(0)
        var updated: Map<String, AppLimit> = emptyMap()
        context.appLimitsDataStore.edit { prefs ->
            val current = decodeLimits(prefs[Keys.LIMITS_JSON]).toMutableMap()
            val existing = current[packageName]
            current[packageName] = AppLimit(
                packageName = packageName,
                dailyMinutes = minutes,
                enabled = enabled ?: existing?.enabled ?: true
            )
            updated = current
            prefs[Keys.LIMITS_JSON] = json.encodeToString(updated)
        }
        _limits.value = updated
        loaded.value = true
    }

    suspend fun removeLimit(packageName: String) {
        var updated: Map<String, AppLimit> = emptyMap()
        context.appLimitsDataStore.edit { prefs ->
            val current = decodeLimits(prefs[Keys.LIMITS_JSON]).toMutableMap()
            current.remove(packageName)
            updated = current
            prefs[Keys.LIMITS_JSON] = json.encodeToString(updated)
        }
        _limits.value = updated
        loaded.value = true
    }

    suspend fun getLimit(packageName: String): AppLimit? = currentLimits()[packageName]

    /**
     * Today's foreground minutes for [packageName] from UsageStats.
     *
     * Suspends and runs the UsageStats query on [kotlinx.coroutines.Dispatchers.IO]
     * (via [UsageStatsRepository]) — never call it directly on the main thread.
     */
    suspend fun getUsedMinutesToday(packageName: String): Int =
        UsageStatsRepository.getMinutesForPackage(context, packageName).toInt()

    /** True when [packageName] has an enabled limit and today's usage has reached it. */
    suspend fun isLimitExceeded(packageName: String): Boolean {
        val limit = getLimit(packageName) ?: return false
        if (!limit.enabled || limit.dailyMinutes <= 0) return false
        val used = getUsedMinutesToday(packageName)
        return used >= limit.dailyMinutes
    }

    private suspend fun currentLimits(): Map<String, AppLimit> =
        if (loaded.value) _limits.value else limitsFlow.first()

    private fun decodeLimits(raw: String?): Map<String, AppLimit> = try {
        if (raw.isNullOrBlank()) emptyMap() else json.decodeFromString(raw)
    } catch (_: Exception) {
        emptyMap()
    }
}
