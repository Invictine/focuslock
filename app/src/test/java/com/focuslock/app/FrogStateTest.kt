package com.focuslock.app

import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.data.repository.canArmNow
import com.focuslock.app.data.repository.computeFrogLocked
import com.focuslock.app.data.repository.frogCycleDate
import com.focuslock.app.data.repository.shouldRolloverFrogCycle
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the "eat the frog" cycle/lock math and JSON shape.
 *
 * No Android APIs: the cycle functions take an explicit [ZoneId] and the JSON round trip
 * uses the same kotlinx-serialization runtime the app persists with.
 */
class FrogStateTest {

    /** DST-free zone so every assertion is deterministic regardless of the test machine TZ. */
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun millisAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ frogCycleDate

    @Test
    fun cycleDateAt0459LocalIsStillPreviousDate() {
        val now = millisAt(2026, 3, 1, 4, 59)
        assertEquals("2026-02-28", frogCycleDate(now, wakeHour = 5, zoneId = zone))
    }

    @Test
    fun cycleDateAt0500LocalStartsToday() {
        val now = millisAt(2026, 3, 1, 5, 0)
        assertEquals("2026-03-01", frogCycleDate(now, wakeHour = 5, zoneId = zone))
    }

    @Test
    fun cycleDateAt2359LocalIsToday() {
        val now = millisAt(2026, 3, 1, 23, 59)
        assertEquals("2026-03-01", frogCycleDate(now, wakeHour = 5, zoneId = zone))
    }

    @Test
    fun cycleDateBeforeWakeHourCrossesMonthBoundary() {
        val now = millisAt(2026, 3, 1, 4, 30)
        assertEquals("2026-02-28", frogCycleDate(now, wakeHour = 5, zoneId = zone))
    }

    @Test
    fun cycleDateHonorsCustomWakeHour() {
        val beforeWake = millisAt(2026, 3, 1, 6, 30)
        val atWake = millisAt(2026, 3, 1, 7, 0)
        assertEquals("2026-02-28", frogCycleDate(beforeWake, wakeHour = 7, zoneId = zone))
        assertEquals("2026-03-01", frogCycleDate(atWake, wakeHour = 7, zoneId = zone))
    }

    // ------------------------------------------------------------- computeFrogLocked

    @Test
    fun lockIsAlwaysOffWhenDisabled() {
        assertFalse(
            computeFrogLocked(
                enabled = false, armed = true, tickedOff = false,
                trackedSeconds = 0, requiredSeconds = 1800,
            )
        )
        assertFalse(
            computeFrogLocked(
                enabled = false, armed = true, tickedOff = true,
                trackedSeconds = 3600, requiredSeconds = 1800,
            )
        )
    }

    @Test
    fun lockIsOffWhenNotArmed() {
        assertFalse(
            computeFrogLocked(
                enabled = true, armed = false, tickedOff = false,
                trackedSeconds = 0, requiredSeconds = 1800,
            )
        )
        assertFalse(
            computeFrogLocked(
                enabled = true, armed = false, tickedOff = true,
                trackedSeconds = 3600, requiredSeconds = 1800,
            )
        )
    }

    @Test
    fun lockIsOnWhenArmedAndNotTickedOff() {
        assertTrue(
            computeFrogLocked(
                enabled = true, armed = true, tickedOff = false,
                trackedSeconds = 0, requiredSeconds = 1800,
            )
        )
        assertTrue(
            computeFrogLocked(
                enabled = true, armed = true, tickedOff = false,
                trackedSeconds = 5000, requiredSeconds = 1800,
            )
        )
    }

    @Test
    fun lockStaysOnAt29m59sTrackedEvenWhenTicked() {
        assertTrue(
            computeFrogLocked(
                enabled = true, armed = true, tickedOff = true,
                trackedSeconds = 1799, requiredSeconds = 1800,
            )
        )
    }

    @Test
    fun lockReleasesAtExactly30mTrackedWhenTicked() {
        assertFalse(
            computeFrogLocked(
                enabled = true, armed = true, tickedOff = true,
                trackedSeconds = 1800, requiredSeconds = 1800,
            )
        )
    }

    @Test
    fun lockWithZeroRequiredReleasesOnTickAndHoldsWithoutIt() {
        assertFalse(
            computeFrogLocked(
                enabled = true, armed = true, tickedOff = true,
                trackedSeconds = 0, requiredSeconds = 0,
            )
        )
        assertTrue(
            computeFrogLocked(
                enabled = true, armed = true, tickedOff = false,
                trackedSeconds = 0, requiredSeconds = 0,
            )
        )
    }

    // --------------------------------------------------------------------- canArmNow

    @Test
    fun cannotArmBeforeWakeHour() {
        val now = millisAt(2026, 3, 1, 4, 59)
        assertFalse(
            canArmNow(
                nowMillis = now, wakeHour = 5, cycleDate = "2026-02-28",
                enabled = true, armed = false, zoneId = zone,
            )
        )
    }

    @Test
    fun canArmAtOrAfterWakeHourWithMatchingCycleDate() {
        val now = millisAt(2026, 3, 1, 5, 0)
        assertTrue(
            canArmNow(
                nowMillis = now, wakeHour = 5, cycleDate = "2026-03-01",
                enabled = true, armed = false, zoneId = zone,
            )
        )
    }

    @Test
    fun cannotArmWhenAlreadyArmed() {
        val now = millisAt(2026, 3, 1, 9, 0)
        assertFalse(
            canArmNow(
                nowMillis = now, wakeHour = 5, cycleDate = "2026-03-01",
                enabled = true, armed = true, zoneId = zone,
            )
        )
    }

    @Test
    fun cannotArmWhenDisabled() {
        val now = millisAt(2026, 3, 1, 9, 0)
        assertFalse(
            canArmNow(
                nowMillis = now, wakeHour = 5, cycleDate = "2026-03-01",
                enabled = false, armed = false, zoneId = zone,
            )
        )
    }

    @Test
    fun cannotArmWhenStoredCycleDateIsNotToday() {
        val now = millisAt(2026, 3, 1, 9, 0)
        assertFalse(
            canArmNow(
                nowMillis = now, wakeHour = 5, cycleDate = "2026-02-28",
                enabled = true, armed = false, zoneId = zone,
            )
        )
    }

    // ------------------------------------------------------- shouldRolloverFrogCycle

    @Test
    fun rolloverDoesNothingWhenStoredCycleDateEqualsComputed() {
        assertFalse(shouldRolloverFrogCycle("2026-03-01", "2026-03-01"))
    }

    @Test
    fun rolloverWipesWhenStoredCycleDateIsOlderThanComputed() {
        assertTrue(shouldRolloverFrogCycle("2026-02-28", "2026-03-01"))
    }

    @Test
    fun noRolloverWhenClockMovedBackSoStoredDateIsNewer() {
        // Stored "tomorrow" (clock moved back): preserve, never roll backward/re-lock.
        assertFalse(shouldRolloverFrogCycle("2026-03-02", "2026-03-01"))
    }

    @Test
    fun wakeHourMovedForwardAt0600PreservesCompletion() {
        // 5 -> 9 at 06:00: computed cycle date becomes yesterday, stored date is today.
        val now = millisAt(2026, 3, 1, 6, 0)
        val stored = frogCycleDate(now, wakeHour = 5, zoneId = zone)    // 2026-03-01
        val computed = frogCycleDate(now, wakeHour = 9, zoneId = zone)  // 2026-02-28
        assertEquals("2026-03-01", stored)
        assertEquals("2026-02-28", computed)
        assertFalse(shouldRolloverFrogCycle(stored, computed))
    }

    @Test
    fun wakeHourMovedBackwardAt0600StartsFreshCycle() {
        // 9 -> 5 at 06:00: computed cycle date is today, stored date is yesterday -> wipe.
        val now = millisAt(2026, 3, 1, 6, 0)
        val stored = frogCycleDate(now, wakeHour = 9, zoneId = zone)    // 2026-02-28
        val computed = frogCycleDate(now, wakeHour = 5, zoneId = zone)  // 2026-03-01
        assertEquals("2026-02-28", stored)
        assertEquals("2026-03-01", computed)
        assertTrue(shouldRolloverFrogCycle(stored, computed))
    }

    // ------------------------------------------------------------------ FrogPhase.from

    @Test
    fun phaseIsNotArmedWhenNotArmed() {
        assertEquals(
            FrogPhase.NOT_ARMED,
            FrogPhase.from(armed = false, selected = true, tickedOff = true, trackedSeconds = 3600, requiredSeconds = 1800),
        )
    }

    @Test
    fun phaseIsPickFrogWhenArmedWithoutSelection() {
        assertEquals(
            FrogPhase.PICK_FROG,
            FrogPhase.from(armed = true, selected = false, tickedOff = false, trackedSeconds = 0, requiredSeconds = 1800),
        )
    }

    @Test
    fun phaseIsWorkingWhileSelectedAndIncomplete() {
        // Not ticked, not enough time.
        assertEquals(
            FrogPhase.WORKING,
            FrogPhase.from(armed = true, selected = true, tickedOff = false, trackedSeconds = 1799, requiredSeconds = 1800),
        )
        // Ticked but one second short.
        assertEquals(
            FrogPhase.WORKING,
            FrogPhase.from(armed = true, selected = true, tickedOff = true, trackedSeconds = 1799, requiredSeconds = 1800),
        )
        // Enough time but not ticked.
        assertEquals(
            FrogPhase.WORKING,
            FrogPhase.from(armed = true, selected = true, tickedOff = false, trackedSeconds = 3600, requiredSeconds = 1800),
        )
    }

    @Test
    fun phaseIsCompleteWhenTickedAndTrackedEnough() {
        assertEquals(
            FrogPhase.COMPLETE,
            FrogPhase.from(armed = true, selected = true, tickedOff = true, trackedSeconds = 1800, requiredSeconds = 1800),
        )
    }

    // --------------------------------------------------------------- FrogTask JSON shape

    @Test
    fun frogTaskJsonRoundTripsThroughTheSharedJsonShape() {
        val json = Json { ignoreUnknownKeys = true }
        val original = FrogTask(
            id = "manual_1789000000000",
            title = "Write the report",
            projectId = "p1",
            projectName = "Work",
            dueDate = "2026-09-15T18:00:00.000+0000",
            source = FrogTask.SOURCE_MANUAL,
        )

        val encoded = json.encodeToString(FrogTask.serializer(), original)
        val decoded = json.decodeFromString(FrogTask.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun frogTaskDefaultsToTickTickSourceAndEmptyFields() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(FrogTask.serializer(), """{"id":"t1","title":"Ship it"}""")

        assertEquals(FrogTask.SOURCE_TICKTICK, decoded.source)
        assertEquals("", decoded.projectId)
        assertEquals("", decoded.projectName)
        assertEquals("", decoded.dueDate)
    }
}
