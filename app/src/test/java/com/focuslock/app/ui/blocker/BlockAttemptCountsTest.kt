package com.focuslock.app.ui.blocker

import com.focuslock.app.data.repository.BlockEvent
import com.focuslock.app.data.repository.BlockLogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BlockAttemptCountsTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 1) // Thursday

    @Test
    fun countsOnlyExactTargetWithinTodayAndCurrentWeek() {
        val events = listOf(
            event("app.a", today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()),
            event("app.a", today.atTime(0, 0).atZone(zone).toInstant().toEpochMilli()),
            event("app.a", today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()),
            event("app.a", today.minusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()),
            event("app.b", today.atTime(13, 0).atZone(zone).toInstant().toEpochMilli())
        )

        val result = blockAttemptCounts(events, "app.a", today, zone)

        assertEquals(2, result.today)
        assertEquals(3, result.week)
        assertFalse(result.todayIsLowerBound)
        assertFalse(result.weekIsLowerBound)
    }

    @Test
    fun weekStartsMondayAndHandlesDaylightSavingMidnightBoundaries() {
        val dstSunday = LocalDate.of(2026, 3, 8)
        val events = listOf(
            event("app.a", dstSunday.atStartOfDay(zone).toInstant().toEpochMilli()),
            event("app.a", dstSunday.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()),
            event("app.a", dstSunday.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        )

        val result = blockAttemptCounts(events, "app.a", dstSunday, zone)

        assertEquals(1, result.today)
        // Both the Monday week boundary and DST Sunday are within this week.
        assertEquals(2, result.week)
    }

    @Test
    fun fullLogMarksPeriodsAsLowerBoundsWhenOldestEntryIsWithinPeriod() {
        val fullLog = (0 until BlockLogRepository.MAX_EVENTS).map { index ->
            event(if (index % 2 == 0) "app.a" else "app.b", today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() - index)
        }

        val result = blockAttemptCounts(fullLog, "missing.app", today, zone)

        assertEquals(0, result.today)
        assertEquals(0, result.week)
        assertTrue(result.todayIsLowerBound)
        assertTrue(result.weekIsLowerBound)
    }

    @Test
    fun oldEnoughEntriesAvoidLowerBoundAndMissingTargetReturnsZero() {
        val events = listOf(event("app.b", today.minusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()))

        val result = blockAttemptCounts(events, "missing.app", today, zone)

        assertEquals(0, result.today)
        assertEquals(0, result.week)
        assertFalse(result.todayIsLowerBound)
        assertFalse(result.weekIsLowerBound)
    }

    private fun event(packageName: String, timestampMillis: Long) =
        BlockEvent(packageName = packageName, timestampMillis = timestampMillis, reason = "manual")
}
