package com.focuslock.app.location

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.homeLocationStore by preferencesDataStore(name = "focuslock_home_location")

/** Separate location preference store; saving a place enables home-only mode atomically. */
class HomeLocationRepository(
    context: Context,
    private val locationSource: DeviceLocationSource = DeviceLocationSource(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val placeKey = stringPreferencesKey("home_place")
    private val enabledKey = booleanPreferencesKey("home_only_enabled")

    /** Safe, in-memory diagnostic summary. Never contains saved or device coordinates. */
    data class DiagnosticSnapshot(
        val status: String,
        val unavailableReason: String?,
        val provider: String?,
        val accuracyMeters: Float?,
        val fixAgeMs: Long?,
        val distanceFromHomeMeters: Float?,
    ) {
        fun asDiagnosticLine(): String = buildString {
            append("status=").append(status)
            append(" unavailableReason=").append(unavailableReason ?: "none")
            append(" provider=").append(provider ?: "unknown")
            append(" accuracyMeters=").append(accuracyMeters?.let { "%.1f".format(java.util.Locale.US, it) } ?: "unknown")
            append(" fixAgeMs=").append(fixAgeMs?.toString() ?: "unknown")
            append(" distanceFromHomeMeters=").append(distanceFromHomeMeters?.let { "%.1f".format(java.util.Locale.US, it) } ?: "unknown")
        }
    }

    @Volatile
    private var diagnosticSnapshot = DiagnosticSnapshot("unknown", "not_checked", null, null, null, null)

    /** Returns the last completed location-policy check without any I/O. */
    fun diagnosticSnapshot(): DiagnosticSnapshot = diagnosticSnapshot

    val homePlaceFlow: Flow<HomePlace?> = appContext.homeLocationStore.data
        .map { prefs -> prefs[placeKey]?.let(::decodePlace) }
        .catch { error -> if (error is CancellationException) throw error else { Log.w("HomeLocation", "Could not read home place", error); emit(null) } }

    val homeOnlyFlow: Flow<Boolean> = appContext.homeLocationStore.data.map { it[enabledKey] ?: false }
        .catch { error -> if (error is CancellationException) throw error else { Log.w("HomeLocation", "Could not read home-only setting", error); emit(false) } }

    suspend fun saveHome(place: HomePlace) {
        require(validPlace(place)) { "Home location is invalid" }
        appContext.homeLocationStore.edit { prefs ->
            prefs[placeKey] = json.encodeToString(place)
            prefs[enabledKey] = true
        }
    }

    suspend fun setHomeOnly(enabled: Boolean) {
        appContext.homeLocationStore.edit { prefs ->
            require(!enabled || decodePlace(prefs[placeKey]) != null) { "Choose a home location before enabling home-only mode" }
            prefs[enabledKey] = enabled
        }
    }

    /** Permanent blocks bypass location; other rules pause only when reliably away. */
    suspend fun shouldEnforceNow(permanent: Boolean = false): Boolean {
        if (permanent) return HomeLocationPolicy.shouldEnforceBlock(true, false)
        return try {
            checkEnforcementNow()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("HomeLocation", "Could not check home location; keeping blocking active", error)
            diagnosticSnapshot = DiagnosticSnapshot("UNAVAILABLE", "location_check_failed", null, null, null, null)
            true
        }
    }

    private suspend fun checkEnforcementNow(): Boolean {
        val prefs = appContext.homeLocationStore.data.first()
        val enabled = prefs[enabledKey] == true
        if (!enabled) {
            diagnosticSnapshot = DiagnosticSnapshot("DISABLED", "home_only_disabled", null, null, null, null)
            return HomeLocationPolicy.shouldEnforce(false, false, false, HomeLocationStatus.DISABLED)
        }
        val precise = locationSource.hasPrecisePermission()
        val background = locationSource.hasBackgroundPermission()
        if (!precise || !background) {
            val reason = when {
                !precise -> "precise_location_permission_missing"
                else -> "background_location_permission_missing"
            }
            diagnosticSnapshot = DiagnosticSnapshot("UNAVAILABLE", reason, null, null, null, null)
        }
        val status = if (precise && background) statusFor(decodePlace(prefs[placeKey]), requireBackgroundPermission = true)
            else HomeLocationStatus.UNAVAILABLE
        // Recheck permissions after an asynchronous provider request before allowing an away exemption.
        return HomeLocationPolicy.shouldEnforce(enabled, locationSource.hasPrecisePermission(),
            locationSource.hasBackgroundPermission(), status)
    }

    /** UI callers can inspect location while foregrounded without requiring background access. */
    suspend fun statusNow(requireBackgroundPermission: Boolean = false): HomeLocationStatus {
        val prefs = appContext.homeLocationStore.data.first()
        if (prefs[enabledKey] != true) {
            diagnosticSnapshot = DiagnosticSnapshot("DISABLED", "home_only_disabled", null, null, null, null)
            return HomeLocationStatus.DISABLED
        }
        return statusFor(decodePlace(prefs[placeKey]), requireBackgroundPermission)
    }

    private suspend fun statusFor(place: HomePlace?, requireBackgroundPermission: Boolean): HomeLocationStatus {
        if (place == null) {
            diagnosticSnapshot = DiagnosticSnapshot("UNAVAILABLE", "home_place_missing_or_invalid", null, null, null, null)
            return HomeLocationStatus.UNAVAILABLE
        }
        if (!locationSource.hasPrecisePermission()) {
            diagnosticSnapshot = DiagnosticSnapshot("UNAVAILABLE", "precise_location_permission_missing", null, null, null, null)
            return HomeLocationStatus.UNAVAILABLE
        }
        if (requireBackgroundPermission && !locationSource.hasBackgroundPermission()) {
            diagnosticSnapshot = DiagnosticSnapshot("UNAVAILABLE", "background_location_permission_missing", null, null, null, null)
            return HomeLocationStatus.UNAVAILABLE
        }
        if (!locationSource.isLocationEnabled()) {
            diagnosticSnapshot = DiagnosticSnapshot("UNAVAILABLE", "location_services_disabled_or_unavailable", null, null, null, null)
            return HomeLocationStatus.UNAVAILABLE
        }
        val maxAccuracyMeters = minOf(100f, place.radiusMeters)
        // A fresh fix that straddles the boundary is not a usable cached result:
        // keep looking for a more accurate provider rather than repeatedly retaining
        // enforcement until that ambiguous fix expires.
        val classifiesBoundary: (Location) -> Boolean = { HomeLocationPolicy.isInside(place, it) != null }
        val location = locationSource.currentLocation(maxAgeMs = 30_000L, timeoutMs = 12_000L,
            maxAccuracyMeters = maxAccuracyMeters, acceptLocation = classifiesBoundary)
            ?: locationSource.recentLocation(maxAgeMs = 60_000L, maxAccuracyMeters = maxAccuracyMeters,
                acceptLocation = classifiesBoundary)
            ?: run {
                val ambiguous = locationSource.recentLocation(maxAgeMs = 60_000L, maxAccuracyMeters = maxAccuracyMeters)
                diagnosticSnapshot = if (ambiguous != null) snapshot("UNAVAILABLE",
                    "accuracy_circle_overlaps_home_boundary", ambiguous,
                    (System.currentTimeMillis() - ambiguous.time).coerceAtLeast(0L), ambiguous.accuracy,
                    distanceFromHomeMeters(place, ambiguous))
                else DiagnosticSnapshot("UNAVAILABLE", "no_fresh_accurate_fix_or_provider_unavailable", null, null, null, null)
                return HomeLocationStatus.UNAVAILABLE
            }
        if (!locationSource.isLocationEnabled()) {
            diagnosticSnapshot = DiagnosticSnapshot("UNAVAILABLE", "location_services_disabled_or_unavailable", null, null, null, null)
            return HomeLocationStatus.UNAVAILABLE
        }
        val ageMs = (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.NaN
        val distance = distanceFromHomeMeters(place, location)
        if (!HomeLocationPolicy.isUsable(location.latitude, location.longitude,
                accuracy, location.time, System.currentTimeMillis(), 60_000L)) {
            diagnosticSnapshot = snapshot("UNAVAILABLE", "fix_stale_or_invalid", location, ageMs, accuracy, distance)
            return HomeLocationStatus.UNAVAILABLE
        }
        val status = when (HomeLocationPolicy.isInside(place, location)) {
            true -> HomeLocationStatus.AT_HOME
            false -> HomeLocationStatus.AWAY
            null -> HomeLocationStatus.UNAVAILABLE
        }
        val reason = when {
            status == HomeLocationStatus.UNAVAILABLE -> "accuracy_circle_overlaps_home_boundary"
            accuracy > maxAccuracyMeters -> "fix_accuracy_exceeds_limit"
            else -> null
        }
        diagnosticSnapshot = snapshot(status.name, reason, location, ageMs, accuracy, distance)
        return status
    }

    private fun snapshot(
        status: String,
        reason: String?,
        location: Location,
        ageMs: Long,
        accuracy: Float,
        distanceMeters: Float?,
    ) = DiagnosticSnapshot(
        status = status,
        unavailableReason = reason,
        provider = location.provider?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,32}")) },
        accuracyMeters = accuracy.takeIf { it.isFinite() },
        fixAgeMs = ageMs,
        distanceFromHomeMeters = distanceMeters?.takeIf { it.isFinite() },
    )

    private fun distanceFromHomeMeters(place: HomePlace, location: Location): Float {
        val results = FloatArray(1)
        Location.distanceBetween(place.latitude, place.longitude, location.latitude, location.longitude, results)
        return results[0]
    }

    private fun decodePlace(raw: String?): HomePlace? = try {
        raw?.let { json.decodeFromString<HomePlace>(it) }?.takeIf(::validPlace)
    } catch (_: Exception) { null }

    private fun validPlace(place: HomePlace): Boolean = place.label.isNotBlank() &&
        place.latitude.isFinite() && place.latitude in -90.0..90.0 &&
        place.longitude.isFinite() && place.longitude in -180.0..180.0 &&
        place.radiusMeters.isFinite() && place.radiusMeters in 25f..5_000f
}
