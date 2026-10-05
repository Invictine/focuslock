package com.focuslock.app

import com.focuslock.app.service.FrogMorningScheduler
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FrogMorningSchedulerTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun beforeWakeHourSchedulesToday() {
        val now = millisAt(2026, 10, 4, 4, 59)
        assertEquals(millisAt(2026, 10, 4, 5, 0), FrogMorningScheduler.nextWakeMillis(now, 5, zone))
    }

    @Test
    fun atOrAfterWakeHourSchedulesTomorrow() {
        assertEquals(
            millisAt(2026, 10, 5, 5, 0),
            FrogMorningScheduler.nextWakeMillis(millisAt(2026, 10, 4, 5, 0), 5, zone),
        )
        assertEquals(
            millisAt(2026, 10, 5, 5, 0),
            FrogMorningScheduler.nextWakeMillis(millisAt(2026, 10, 4, 19, 30), 5, zone),
        )
    }

    @Test
    fun wakeHourIsClampedAndRunsOncePerLocalDate() {
        assertEquals(
            millisAt(2026, 10, 4, 23, 0),
            FrogMorningScheduler.nextWakeMillis(millisAt(2026, 10, 4, 22, 0), 99, zone),
        )
        assertEquals(
            millisAt(2026, 10, 5, 0, 0),
            FrogMorningScheduler.nextWakeMillis(millisAt(2026, 10, 4, 23, 59), 0, zone),
        )
    }

    private fun millisAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
}
