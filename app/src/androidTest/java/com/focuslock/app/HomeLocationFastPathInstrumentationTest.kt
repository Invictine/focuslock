package com.focuslock.app

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.location.DeviceLocationSource
import com.focuslock.app.location.HomeLocationRepository
import com.focuslock.app.location.HomePlace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in disposable-emulator fixture. The host runner must back up and restore
 * app preferences around this test because it deliberately changes home settings.
 */
@RunWith(AndroidJUnit4::class)
class HomeLocationFastPathInstrumentationTest {
    @Suppress("DEPRECATION")
    @Test
    fun appSwitchCheckUsesOnlyFreshCachedLocationAndFailsClosedQuickly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass -e homeLocationFastPath true to run this disposable fixture",
            InstrumentationRegistry.getArguments().getString("homeLocationFastPath") == "true")
        val context = instrumentation.targetContext
        assumeTrue(Build.MODEL.contains("sdk", ignoreCase = true) || Build.FINGERPRINT.contains("generic", ignoreCase = true))

        // Host grants location and mock-location app access on the disposable emulator.
        // Keep the app identity: adopting shell identity changes mock-location app ops.
        assertTrue(DeviceLocationSource(context).hasPrecisePermission())
        assertTrue(DeviceLocationSource(context).hasBackgroundPermission())
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val repo = HomeLocationRepository(context, DeviceLocationSource(context))
        val provider = LocationManager.GPS_PROVIDER
        var providerAdded = false
        try {
            assumeTrue("Location services must be enabled", DeviceLocationSource(context).isLocationEnabled())
            try {
                manager.addTestProvider(provider, false, false, false, false,
                    true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                providerAdded = true
                manager.setTestProviderEnabled(provider, true)
            } catch (error: Exception) {
                assumeNoException("Emulator must permit a disposable GPS test provider", error)
            }

            repo.setHomeOnly(false)
            assertTrue("Disabled home-only mode keeps normal enforcement", repo.shouldEnforceOnAppSwitch())

            repo.saveHome(HomePlace("Fast path fixture", 0.0, 0.0, 150f))
            putLocation(manager, provider, 0.0, 0.0)
            assertTrue("A recent unambiguous home fix keeps enforcement active", repo.shouldEnforceOnAppSwitch())

            putLocation(manager, provider, 1.0, 1.0)
            assertFalse("A recent unambiguous away fix pauses ordinary enforcement", repo.shouldEnforceOnAppSwitch())
            assertTrue("Permanent enforcement bypasses location", repo.shouldEnforceOnAppSwitch(permanent = true))

            manager.setTestProviderEnabled(provider, false)
            val sourceWithoutSeededCache = DeviceLocationSource(context)
            assumeTrue("Run timing assertion only when the emulator has no fresh system fix",
                sourceWithoutSeededCache.recentLocation(60_000L, 100f) == null)
            val repoWithoutSeededCache = HomeLocationRepository(context, sourceWithoutSeededCache)
            val startedAt = SystemClock.elapsedRealtime()
            assertTrue("Missing fresh location fails closed", repoWithoutSeededCache.shouldEnforceOnAppSwitch())
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            assertTrue("No-fix app-switch check must finish under 500ms (was ${elapsed}ms)", elapsed < 500L)
            instrumentation.sendStatus(0, Bundle().apply { putLong("noFixAppSwitchMs", elapsed) })
        } finally {
            if (providerAdded) runCatching { manager.removeTestProvider(provider) }
        }
    }

    @Suppress("DEPRECATION")
    private fun putLocation(manager: LocationManager, provider: String, latitude: Double, longitude: Double) {
        manager.setTestProviderLocation(provider, Location(provider).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        SystemClock.sleep(100L)
    }
}
