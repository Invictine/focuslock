package com.focuslock.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import com.focuslock.app.location.DeviceLocationSource
import com.focuslock.app.location.HomeLocationPolicy
import com.focuslock.app.location.HomePlace

private val Context.strictAutomationStore by preferencesDataStore(name = "focuslock_strict_automation")

@Serializable
data class StrictPlaceRule(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 150f,
    val enabled: Boolean = true,
)

@Serializable
data class StrictRecurringWindow(
    val id: String,
    val label: String,
    val daysOfWeek: Set<Int>,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val enabled: Boolean = true,
)

/** Persisted activation rules for Strict Mode. UI owns presentation; enforcement owns lookup. */
class StrictModeAutomationRepository(private val context: Context) {
    private val locationSource = DeviceLocationSource(context)
    private val json = Json { ignoreUnknownKeys = true }
    object Keys {
        val PLACES = stringPreferencesKey("strict_mode_places_json")
        val WINDOWS = stringPreferencesKey("strict_mode_recurring_windows_json")
    }
    private val _places = MutableStateFlow<List<StrictPlaceRule>>(emptyList())
    private val _windows = MutableStateFlow<List<StrictRecurringWindow>>(emptyList())
    private var loaded = false

    val placesFlow: Flow<List<StrictPlaceRule>> = context.strictAutomationStore.data
        .map { decodePlaces(it[Keys.PLACES]) }
        .onEach { _places.value = it; loaded = true }
    val recurringWindowsFlow: Flow<List<StrictRecurringWindow>> = context.strictAutomationStore.data
        .map { decodeWindows(it[Keys.WINDOWS]) }
        .onEach { _windows.value = it; loaded = true }

    /** Live lock state for boundary editors; access enforcement does not consume this. */
    val activationActiveFlow: Flow<Boolean> = flow {
        while (true) {
            emit(isStrictAutomationActive { isActivationActiveNow() })
            delay(15_000L)
        }
    }

    suspend fun upsertPlace(rule: StrictPlaceRule) {
        if (rule.id.isBlank()) return
        require(rule.label.isNotBlank() && rule.latitude.isFinite() && rule.latitude in -90.0..90.0 &&
            rule.longitude.isFinite() && rule.longitude in -180.0..180.0 &&
            rule.radiusMeters.isFinite() && rule.radiusMeters in 50f..1000f) { "Choose a valid place and radius" }
        val next = currentPlaces().toMutableList()
        val index = next.indexOfFirst { it.id == rule.id }
        if (index >= 0) next[index] = rule else next += rule
        context.strictAutomationStore.edit { it[Keys.PLACES] = json.encodeToString(next) }
        _places.value = next; loaded = true
    }
    suspend fun deletePlace(id: String) {
        val next = currentPlaces().filterNot { it.id == id }
        context.strictAutomationStore.edit { it[Keys.PLACES] = json.encodeToString(next) }
        _places.value = next
    }
    suspend fun upsertRecurringWindow(rule: StrictRecurringWindow) {
        if (rule.id.isBlank()) return
        val next = currentWindows().toMutableList()
        val index = next.indexOfFirst { it.id == rule.id }
        if (index >= 0) next[index] = rule else next += rule
        context.strictAutomationStore.edit { it[Keys.WINDOWS] = json.encodeToString(next) }
        _windows.value = next; loaded = true
    }
    suspend fun deleteRecurringWindow(id: String) {
        val next = currentWindows().filterNot { it.id == id }
        context.strictAutomationStore.edit { it[Keys.WINDOWS] = json.encodeToString(next) }
        _windows.value = next
    }

    /** True when the current local time is in a configured window or place. */
    suspend fun isActivationActiveNow(now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        val windowsActive = activeWindowAt(currentWindows(), now)
        if (windowsActive) return true
        currentPlaces()
        return isInsideConfiguredPlace()
    }

    private suspend fun currentPlaces(): List<StrictPlaceRule> = placesFlow.first()
    private suspend fun currentWindows(): List<StrictRecurringWindow> = recurringWindowsFlow.first()
    private fun decodePlaces(raw: String?): List<StrictPlaceRule> = try { if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(raw) } catch (_: Exception) { emptyList() }
    private fun decodeWindows(raw: String?): List<StrictRecurringWindow> = try { if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(raw) } catch (_: Exception) { emptyList() }

    private suspend fun isInsideConfiguredPlace(): Boolean {
        val configured = _places.value.filter { it.enabled && it.radiusMeters > 0f }
        if (configured.isEmpty()) return false
        // Enabled location rules stay active unless a reliable fix is outside every place.
        if (!locationSource.hasPrecisePermission() || !locationSource.hasBackgroundPermission() ||
            !locationSource.isLocationEnabled()) return true
        val last = locationSource.currentLocation() ?: return true
        if (!locationSource.hasPrecisePermission() || !locationSource.hasBackgroundPermission() ||
            !locationSource.isLocationEnabled()) return true
        return configured.any { rule ->
            HomeLocationPolicy.isInside(
                HomePlace(rule.label, rule.latitude, rule.longitude, rule.radiusMeters), last
            ) != false
        }
    }

    companion object {
        fun activeWindowAt(windows: List<StrictRecurringWindow>, now: ZonedDateTime): Boolean {
            val minute = now.hour * 60 + now.minute
            val today = now.dayOfWeek.value
            val yesterday = if (today == 1) 7 else today - 1
            return windows.any { w ->
                if (!w.enabled || w.daysOfWeek.isEmpty()) return@any false
                val start = w.startMinuteOfDay.coerceIn(0, 1439)
                val end = w.endMinuteOfDay.coerceIn(0, 1439)
                if (start == end) return@any false
                if (start < end) today in w.daysOfWeek && minute in start until end
                else (today in w.daysOfWeek && minute >= start) || (yesterday in w.daysOfWeek && minute < end)
            }
        }
    }
}
