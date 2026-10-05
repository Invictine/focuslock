package com.focuslock.app

import android.content.Context
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.location.DeviceLocationSource
import com.focuslock.app.location.HomeLocationPolicy
import com.focuslock.app.location.HomeLocationRepository
import com.focuslock.app.location.HomeLocationStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only snapshot of the phone's real home-only enforcement state. */
@RunWith(AndroidJUnit4::class)
class HomeEnforcementDiagnosticsTest {
    @Test
    fun reportsActualHomeEnforcementStateWithoutChangingSettings(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e homeDiagnostics true to run this device diagnostic",
            arguments.getString("homeDiagnostics") == "true")

        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = DeviceLocationSource(context)
        val repository = HomeLocationRepository(context, source)
        val homeOnly = repository.homeOnlyFlow.first()
        val home = repository.homePlaceFlow.first()
        val status = repository.statusNow(requireBackgroundPermission = true)
        val shouldEnforce = repository.shouldEnforceNow()
        val precise = source.hasPrecisePermission()
        val background = source.hasBackgroundPermission()
        val policyResult = HomeLocationPolicy.shouldEnforce(homeOnly, precise, background, status)

        // A broad diagnostic read reports fix quality even when the enforcement radius
        // rejects it. Only accuracy, age, provider, and distance are emitted.
        val fix = source.currentLocation(maxAgeMs = 60_000L, timeoutMs = 12_000L,
            maxAccuracyMeters = 1_000f)
            ?: source.recentLocation(maxAgeMs = 60_000L, maxAccuracyMeters = 1_000f)
        val fixSummary = fix?.let { describeFix(it, home?.latitude, home?.longitude) } ?: "fix=none"
        val report = "homeOnly=$homeOnly status=$status shouldEnforceNow=$shouldEnforce " +
            "precisePermission=$precise backgroundPermission=$background $fixSummary"
        Log.i(TAG, report)
        instrumentation.sendStatus(0, Bundle().apply { putString("homeEnforcement", report) })

        assertEquals("Repository result must agree with the named status and permissions",
            policyResult, shouldEnforce)
        arguments.getString("expectedHomeStatus")?.let { expectedName ->
            val expectedStatus = runCatching { HomeLocationStatus.valueOf(expectedName) }.getOrNull()
            assertEquals("expectedHomeStatus must name the observed enum value", expectedStatus?.name, expectedName)
            assertEquals("Observed home status differs from the requested device check",
                expectedStatus, status)
        }
        arguments.getString("expectedHomeEnforcement")?.let { expectedValue ->
            val expectedEnforcement = expectedValue.toBooleanStrictOrNull()
            assertEquals("expectedHomeEnforcement must be true or false",
                expectedValue.toBooleanStrictOrNull()?.toString(), expectedValue.lowercase())
            assertEquals("Observed home enforcement differs from the requested device check",
                expectedEnforcement, shouldEnforce)
        }
        if (arguments.getString("locationServicesDisabled") == "true") {
            assertTrue("The disabled-services diagnostic requires home-only enforcement", homeOnly)
            assertEquals("Location services must be disabled for this diagnostic",
                false, source.isLocationEnabled())
            assertNull("Current location must not use a cached fix while services are disabled",
                source.currentLocation(maxAgeMs = 60_000L, timeoutMs = 1_000L,
                    maxAccuracyMeters = 1_000f))
            assertNull("Recent location must not use a cached fix while services are disabled",
                source.recentLocation(maxAgeMs = 60_000L, maxAccuracyMeters = 1_000f))
            assertEquals("Disabled services must report unavailable status",
                HomeLocationStatus.UNAVAILABLE, status)
            assertEquals("Unavailable location must keep home-only rules enforced", true, shouldEnforce)
            val automation = FocusLockApplication.instance.strictModeAutomationRepository
            val enabledPlaces = automation.placesFlow.first().count { it.enabled && it.radiusMeters > 0f }
            if (enabledPlaces > 0) {
                assertTrue("Enabled Strict location rules must stay active with services disabled",
                    automation.isActivationActiveNow())
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("disabledLocation", "cacheBlocked=true homeEnforcement=true strictEnabledPlaces=$enabledPlaces")
            })
        }
    }

    private fun describeFix(fix: Location, homeLatitude: Double?, homeLongitude: Double?): String {
        val ageMs = (System.currentTimeMillis() - fix.time).coerceAtLeast(0L)
        val accuracy = if (fix.hasAccuracy()) "${fix.accuracy}m" else "unknown"
        val distance = if (homeLatitude != null && homeLongitude != null) {
            val savedHome = Location("saved-home").apply {
                latitude = homeLatitude
                longitude = homeLongitude
            }
            "${fix.distanceTo(savedHome)}m"
        } else "unavailable"
        return "fixProvider=${fix.provider ?: "unknown"} fixAccuracy=$accuracy fixAge=${ageMs}ms " +
            "distanceFromSavedHome=$distance"
    }

    private companion object {
        const val TAG = "HomeEnforcementDiag"
    }
}
