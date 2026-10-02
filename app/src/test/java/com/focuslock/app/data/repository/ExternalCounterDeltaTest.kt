package com.focuslock.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalCounterDeltaTest {
    @Test
    fun `merges only positive lifetime and same day deltas`() {
        val baseline = CreditBankRepository.ExternalStateCounters(
            earnedSeconds = 300,
            spentSeconds = 120,
            date = "2026-09-30",
            workSecondsToday = 600,
            scrollSecondsToday = 180,
            tasksCompletedToday = 2,
        )
        val current = baseline.copy(
            earnedSeconds = 420,
            spentSeconds = 160,
            workSecondsToday = 900,
            scrollSecondsToday = 240,
            tasksCompletedToday = 3,
        )

        val delta = CreditBankRepository.externalCounterDelta(baseline, current, "2026-09-30")

        assertEquals(120L, delta.earnedSeconds)
        assertEquals(40L, delta.spentSeconds)
        assertEquals(300L, delta.workSecondsToday)
        assertEquals(60L, delta.scrollSecondsToday)
        assertEquals(1L, delta.tasksCompletedToday)
    }

    @Test
    fun `new external date uses whole day counters while local rollover gates old date`() {
        val baseline = CreditBankRepository.ExternalStateCounters(
            earnedSeconds = 100,
            spentSeconds = 20,
            date = "2026-09-30",
            workSecondsToday = 1_000,
            scrollSecondsToday = 500,
            tasksCompletedToday = 4,
        )
        val nextDay = baseline.copy(
            earnedSeconds = 160,
            spentSeconds = 40,
            date = "2026-10-01",
            workSecondsToday = 90,
            scrollSecondsToday = 30,
            tasksCompletedToday = 1,
        )

        val appliedNextDay = CreditBankRepository.externalCounterDelta(baseline, nextDay, "2026-10-01")
        assertEquals(60L, appliedNextDay.earnedSeconds)
        assertEquals(20L, appliedNextDay.spentSeconds)
        assertEquals(90L, appliedNextDay.workSecondsToday)
        assertEquals(30L, appliedNextDay.scrollSecondsToday)
        assertEquals(1L, appliedNextDay.tasksCompletedToday)

        val delayedOldDay = CreditBankRepository.externalCounterDelta(baseline, nextDay, "2026-09-30")
        assertEquals(60L, delayedOldDay.earnedSeconds)
        assertEquals(20L, delayedOldDay.spentSeconds)
        assertEquals(0L, delayedOldDay.workSecondsToday)
        assertEquals(0L, delayedOldDay.scrollSecondsToday)
        assertEquals(0L, delayedOldDay.tasksCompletedToday)
    }

    @Test
    fun `counter rollback never creates negative adjustment`() {
        val baseline = CreditBankRepository.ExternalStateCounters(
            earnedSeconds = 500,
            spentSeconds = 300,
            date = "2026-09-30",
            workSecondsToday = 800,
            scrollSecondsToday = 200,
            tasksCompletedToday = 5,
        )
        val rolledBack = baseline.copy(
            earnedSeconds = 100,
            spentSeconds = 20,
            workSecondsToday = 100,
            scrollSecondsToday = 0,
            tasksCompletedToday = 0,
        )

        val delta = CreditBankRepository.externalCounterDelta(baseline, rolledBack, "2026-09-30")

        assertEquals(0L, delta.earnedSeconds)
        assertEquals(0L, delta.spentSeconds)
        assertEquals(0L, delta.workSecondsToday)
        assertEquals(0L, delta.scrollSecondsToday)
        assertEquals(0L, delta.tasksCompletedToday)
    }
}
