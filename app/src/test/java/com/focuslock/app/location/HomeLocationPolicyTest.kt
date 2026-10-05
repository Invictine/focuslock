package com.focuslock.app.location

import org.junit.Assert.*
import org.junit.Test

class HomeLocationPolicyTest {
    private val home = HomePlace("Home", 12.9716, 77.5946, 150f)

    @Test fun fixMustBeFreshAndPlausible() {
        assertTrue(HomeLocationPolicy.isUsable(12.9, 77.5, 20f, 9_970L, 10_000L, 60_000L))
        assertFalse(HomeLocationPolicy.isUsable(12.9, 77.5, 20f, 9_000L, 10_000L, 500L))
        assertFalse(HomeLocationPolicy.isUsable(12.9, 77.5, 20f, 10_001L, 10_000L, 60_000L))
        assertFalse(HomeLocationPolicy.isUsable(91.0, 77.5, 20f, 9_900L, 10_000L, 60_000L))
        assertFalse(HomeLocationPolicy.isUsable(12.9, 77.5, Float.NaN, 9_900L, 10_000L, 60_000L))
        assertFalse(HomeLocationPolicy.isUsable(12.9, 77.5, -1f, 9_900L, 10_000L, 60_000L))
    }

    @Test fun radiusNeedsAccuracyCircleFullyWithinOrOutside() {
        assertEquals(true, HomeLocationPolicy.isInside(home, home.latitude, home.longitude, 40f))
        // About 2.2 km north of the saved point.
        assertEquals(false, HomeLocationPolicy.isInside(home, home.latitude + 0.02, home.longitude, 20f))
        // The uncertainty circle crosses the 150m boundary, so classification is unknown.
        assertNull(HomeLocationPolicy.isInside(home, home.latitude + 0.0012, home.longitude, 50f))
        assertNull(HomeLocationPolicy.isInside(home, home.latitude, home.longitude, Float.NaN))
        assertNull(HomeLocationPolicy.isInside(home, home.latitude, home.longitude, -1f))
        assertNull(HomeLocationPolicy.isInside(home.copy(radiusMeters = 0f), home.latitude, home.longitude, 1f))
        assertNull(HomeLocationPolicy.isInside(home, home.latitude + 0.02, home.longitude, 3_000f))
        // Longitude wraps across the date line; a point on either side is close, not thousands of km away.
        val dateLineHome = HomePlace("Date line", 0.0, 179.999, 500f)
        assertEquals(true, HomeLocationPolicy.isInside(dateLineHome, 0.0, -179.999, 100f))
    }

    @Test fun smallHomeRadiusAllowsFixWhoseUncertaintyFitsInside() {
        val smallHome = home.copy(radiusMeters = 50f)
        // An accuracy worse than half the radius can still establish home reliably.
        assertEquals(true, HomeLocationPolicy.isInside(smallHome, home.latitude, home.longitude, 35f))
        // About 33m from the center with 35m uncertainty overlaps the boundary.
        assertNull(HomeLocationPolicy.isInside(smallHome, home.latitude + 0.0003, home.longitude, 35f))
    }

    @Test fun homeRadiusAccountsForMeasuredIndoorDriftAndAccuracy() {
        // Physical phone: approximately 40m from the saved point, accuracy 68-100m.
        val latitude = home.latitude + 0.00036
        assertNull(HomeLocationPolicy.isInside(home.copy(radiusMeters = 100f), latitude, home.longitude, 68f))
        assertEquals(true, HomeLocationPolicy.isInside(home.copy(radiusMeters = 150f), latitude, home.longitude, 100f))
        assertEquals(false, HomeLocationPolicy.isInside(home, home.latitude + 0.003, home.longitude, 100f))
    }

    @Test fun unavailableOrMissingPermissionKeepsHomeOnlyBlockingActive() {
        assertTrue(HomeLocationPolicy.shouldEnforce(false, false, false, HomeLocationStatus.DISABLED))
        assertTrue(HomeLocationPolicy.shouldEnforce(false, false, false, HomeLocationStatus.UNAVAILABLE))
        assertTrue(HomeLocationPolicy.shouldEnforce(true, true, true, HomeLocationStatus.AT_HOME))
        assertFalse(HomeLocationPolicy.shouldEnforce(true, true, true, HomeLocationStatus.AWAY))
        assertTrue(HomeLocationPolicy.shouldEnforce(true, true, true, HomeLocationStatus.UNAVAILABLE))
        assertTrue(HomeLocationPolicy.shouldEnforce(true, false, true, HomeLocationStatus.AT_HOME))
        assertTrue(HomeLocationPolicy.shouldEnforce(true, true, false, HomeLocationStatus.AT_HOME))
    }

    @Test fun onlyConfirmedAwayWithAllPermissionsPausesBlocking() {
        for (homeOnly in listOf(false, true)) {
            for (precise in listOf(false, true)) {
                for (background in listOf(false, true)) {
                    for (status in HomeLocationStatus.entries) {
                        val confirmedAway = homeOnly && precise && background && status == HomeLocationStatus.AWAY
                        assertEquals("homeOnly=$homeOnly precise=$precise background=$background status=$status",
                            !confirmedAway, HomeLocationPolicy.shouldEnforce(homeOnly, precise, background, status))
                    }
                }
            }
        }
    }

    @Test fun staleInaccurateAndBoundaryOverlappingFixesKeepBlockingActive() {
        assertFalse(HomeLocationPolicy.isUsable(home.latitude + 0.02, home.longitude, 20f, 1_000L, 100_000L, 60_000L))
        assertNull(HomeLocationPolicy.isInside(home, home.latitude + 0.02, home.longitude, Float.NaN))
        assertNull(HomeLocationPolicy.isInside(home, home.latitude + 0.0012, home.longitude, 50f))
        assertTrue(HomeLocationPolicy.shouldEnforce(true, true, true, HomeLocationStatus.UNAVAILABLE))
    }

    @Test fun permanentBlocksOverrideEveryLocationAndPermissionState() {
        for (homeOnly in listOf(false, true)) {
            for (precise in listOf(false, true)) {
                for (background in listOf(false, true)) {
                    for (status in HomeLocationStatus.entries) {
                        val homeAllowed = HomeLocationPolicy.shouldEnforce(homeOnly, precise, background, status)
                        assertTrue(HomeLocationPolicy.shouldEnforceBlock(true, homeAllowed))
                        assertEquals(homeAllowed, HomeLocationPolicy.shouldEnforceBlock(false, homeAllowed))
                    }
                }
            }
        }
    }
}
