package com.focuslock.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.selects.select

/** Shared Android location access, with concurrent provider requests and bounded fallbacks. */
class DeviceLocationSource(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val mutex = Mutex()
    @Volatile private var cached: Location? = null
    @Volatile private var unavailableAt: Long = 0L

    fun hasPrecisePermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(): Boolean = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(): Boolean = manager?.let { lm ->
        try { LocationManagerCompat.isLocationEnabled(lm) } catch (_: Exception) { false }
    } ?: false

    fun recentLocation(
        maxAgeMs: Long,
        maxAccuracyMeters: Float = 1_000f,
        acceptLocation: (Location) -> Boolean = { true },
    ): Location? {
        if (!hasPrecisePermission() || !isLocationEnabled()) return null
        val lm = manager ?: return null
        return enabledProviders(lm).mapNotNull { provider ->
            try { lm.getLastKnownLocation(provider) } catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null }
        }.filter { validFix(it, maxAgeMs, maxAccuracyMeters) && acceptLocation(it) }.maxByOrNull { it.time }
    }

    suspend fun currentLocation(
        maxAgeMs: Long = 30_000L,
        timeoutMs: Long = 12_000L,
        maxAccuracyMeters: Float = 100f,
        acceptLocation: (Location) -> Boolean = { true },
    ): Location? = mutex.withLock {
        // Permission/provider state is checked on every call, including cache hits.
        if (!hasPrecisePermission() || !isLocationEnabled()) return@withLock null
        val lm = manager ?: return@withLock null
        val now = System.currentTimeMillis()
        val providers = enabledProviders(lm)
        if (providers.isEmpty()) return@withLock null
        val osRecent = recentLocation(maxAgeMs, maxAccuracyMeters, acceptLocation)
        val recent = listOfNotNull(cached?.takeIf { validFix(it, maxAgeMs, maxAccuracyMeters) && acceptLocation(it) }, osRecent)
            .maxByOrNull { it.time }?.let(::Location)
        if (recent != null) {
            cached = Location(recent)
            return@withLock recent
        }
        if (unavailableAt != 0L && now - unavailableAt in 0..10_000L) return@withLock recent
        try {
            val fix = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                coroutineScope {
                    val requests = providers.map { provider -> async { request(lm, provider, timeoutMs) } }.toMutableList()
                    try {
                        // Consume whichever provider returns first with a valid fresh fix.
                        while (requests.isNotEmpty()) {
                            val completed = select<Pair<kotlinx.coroutines.Deferred<Location?>, Location?>> {
                                requests.forEach { deferred -> deferred.onAwait { deferred to it } }
                            }
                            requests.remove(completed.first)
                            val result = completed.second
                            if (result != null && validFix(result, maxAgeMs, maxAccuracyMeters) && acceptLocation(result)) {
                                val best = listOfNotNull(result, recent).maxWithOrNull(
                                    compareBy<Location> { it.time }.thenByDescending { it.accuracy }
                                )
                                return@coroutineScope best
                            }
                        }
                        null
                    } finally { requests.forEach { it.cancel() } }
                }
            }
            // Settings can change while a provider request is in flight.
            if (!hasPrecisePermission() || !isLocationEnabled()) return@withLock null
            if (fix != null && validFix(fix, maxAgeMs, maxAccuracyMeters)) {
                cached = Location(fix)
                unavailableAt = 0L
                Location(fix)
            } else {
                unavailableAt = System.currentTimeMillis()
                recent?.takeIf { validFix(it, maxAgeMs, maxAccuracyMeters) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { null }
        catch (_: IllegalArgumentException) { null }
    }

    private suspend fun request(lm: LocationManager, provider: String, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            suspendCancellableCoroutine { cont ->
                val cancellation = CancellationSignal()
                cont.invokeOnCancellation { cancellation.cancel() }
                try {
                    val executor = ContextCompat.getMainExecutor(appContext)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val request = LocationRequest.Builder(1_000L)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                            .build()
                        lm.getCurrentLocation(provider, request, cancellation, executor) { location ->
                            if (cont.isActive) cont.resume(location)
                        }
                    } else {
                        LocationManagerCompat.getCurrentLocation(lm, provider, cancellation, executor) { location ->
                            if (cont.isActive) cont.resume(location)
                        }
                    }
                } catch (_: SecurityException) { if (cont.isActive) cont.resume(null) }
                catch (_: IllegalArgumentException) { if (cont.isActive) cont.resume(null) }
            }
        }

    private fun enabledProviders(lm: LocationManager): List<String> = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else "fused",
    ).distinct()
        .filter { provider -> try { lm.isProviderEnabled(provider) } catch (_: SecurityException) { false } catch (_: IllegalArgumentException) { false } }

    private fun validFix(location: Location, maxAgeMs: Long, maxAccuracyMeters: Float): Boolean =
        HomeLocationPolicy.isUsable(location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy else Float.NaN,
            location.time, System.currentTimeMillis(), maxAgeMs) &&
            maxAccuracyMeters.isFinite() && maxAccuracyMeters > 0f && location.accuracy <= maxAccuracyMeters
}
