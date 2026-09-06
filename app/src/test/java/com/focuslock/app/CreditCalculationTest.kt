package com.focuslock.app

import com.focuslock.app.data.repository.CreditBankRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class CreditCalculationTest {

    private fun calculateEarnedMinutes(workMinutes: Int, workRatio: Int, taskBonusMinutes: Int): Int {
        return CreditBankRepository.calculateEarnedMinutes(workMinutes, workRatio, taskBonusMinutes)
    }

    @Test
    fun testOneHourWorkWithFourToOneRatio() {
        val earned = calculateEarnedMinutes(workMinutes = 60, workRatio = 4, taskBonusMinutes = 5)
        // 60 / 4 = 15 mins + 5 bonus = 20 mins
        assertEquals(20, earned)
    }

    @Test
    fun testTwentyFiveMinPomoWithFourToOneRatio() {
        val earned = calculateEarnedMinutes(workMinutes = 25, workRatio = 4, taskBonusMinutes = 0)
        // 25 / 4 = 6 mins
        assertEquals(6, earned)
    }

    @Test
    fun testHardcoreTenToOneRatio() {
        val earned = calculateEarnedMinutes(workMinutes = 120, workRatio = 10, taskBonusMinutes = 0)
        // 120 / 10 = 12 mins
        assertEquals(12, earned)
    }

    @Test
    fun testMinimumOneMinuteAlwaysEarned() {
        // 1 min of work at a harsh ratio still earns the 1-min floor (no zero-credit sessions)
        assertEquals(1, calculateEarnedMinutes(workMinutes = 1, workRatio = 10, taskBonusMinutes = 0))
    }

    @Test
    fun testZeroRatioFallsBackToFullMinutes() {
        assertEquals(30, calculateEarnedMinutes(workMinutes = 25, workRatio = 0, taskBonusMinutes = 5))
    }
}
