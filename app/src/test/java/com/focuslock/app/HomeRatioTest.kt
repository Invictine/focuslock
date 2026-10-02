package com.focuslock.app

import com.focuslock.app.ui.dashboard.home.evaluateHomeRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM coverage for the Focus home ratio's boundary and missing-data states. */
class HomeRatioTest {

    @Test
    fun missingFocusOrLeisureDataIsUnavailable() {
        assertEquals("Ratio unavailable", evaluateHomeRatio(30, 600, false, 2.0).title)
        assertEquals("Ratio unavailable", evaluateHomeRatio(30, null, true, 2.0).title)
        assertEquals("—", evaluateHomeRatio(30, null, true, 2.0).ratioLabel)
    }

    @Test
    fun zeroFocusAndLeisureIsFreshStart() {
        val result = evaluateHomeRatio(0, 0, true, 2.0)
        assertEquals("A fresh start", result.title)
        assertEquals("—", result.ratioLabel)
        assertFalse(result.behind)
    }

    @Test
    fun noLeisureIsReportedWithoutInventingRatio() {
        val result = evaluateHomeRatio(45, 0, true, 2.0)
        assertEquals("Focus is ahead", result.title)
        assertEquals("No leisure yet", result.ratioLabel)
        assertFalse(result.behind)
    }

    @Test
    fun exactTargetIsOnTrack() {
        val result = evaluateHomeRatio(20, 600, true, 2.0)
        assertEquals("On track", result.title)
        assertEquals("2.0:1", result.ratioLabel)
        assertFalse(result.behind)
    }

    @Test
    fun belowTargetUsesCeiledRecoveryMinutes() {
        // 10 focus minutes / 7m01s leisure = 1.4:1; five whole minutes reaches 2:1.
        val result = evaluateHomeRatio(10, 421L, true, 2.0)
        assertEquals("Leisure is ahead", result.title)
        assertEquals("1.4:1", result.ratioLabel)
        assertTrue(result.behind)
        assertTrue(result.detail.contains("5m more focus"))
    }

    @Test
    fun negativeValuesAreClampedToSafeZeroes() {
        val result = evaluateHomeRatio(-10, -30, true, 2.0)
        assertEquals("A fresh start", result.title)
        assertEquals("—", result.ratioLabel)
    }

    @Test
    fun invalidTargetFallsBackToTwoToOneGoal() {
        val result = evaluateHomeRatio(10, 600, true, 0.0)
        assertEquals("1.0:1", result.ratioLabel)
        assertTrue(result.detail.contains("2.0:1"))
    }
}
