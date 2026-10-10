package com.focuslock.app

import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.UserStats
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.data.repository.canArmNow
import com.focuslock.app.data.repository.computeFrogLocked
import com.focuslock.app.data.repository.focusMinutesLoggedToday
import com.focuslock.app.data.repository.focusMinutesToday
import com.focuslock.app.data.repository.frogCycleDate
import com.focuslock.app.data.repository.frogDailyExempt
import com.focuslock.app.data.repository.frogDailyExemptionFlow
import com.focuslock.app.data.repository.frogGraceEndsAt
import com.focuslock.app.data.repository.frogGraceExpired
import com.focuslock.app.data.repository.pendingFrogGraceDeadline
import com.focuslock.app.data.repository.shouldRolloverFrogCycle
import com.focuslock.app.data.repository.sanitizeFrogToolPackages
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

    @Test
    fun dailyExemptionRequiresMoreThanThirtyMinutesOfFocus() {
        assertFalse(frogDailyExempt(0))
        assertFalse(frogDailyExempt(30))
        assertTrue(frogDailyExempt(31))
    }

    @Test
    fun dailyExemptionCountsTodayFocusOnlyAndIgnoresNonFocusLogs() {
        val today = millisAt(2026, 10, 9, 12, 0)
        val records = listOf(
            TickTickWorkRecord("manual", "Manual", 20, today, WorkRecordSource.MANUAL_ENTRY),
            TickTickWorkRecord("ticktick", "Focus", 11, today, WorkRecordSource.TICKTICK_FOCUS_API),
            TickTickWorkRecord("task", "Task", 60, today, WorkRecordSource.TICKTICK_API),
            TickTickWorkRecord("old", "Yesterday", 60, today - 86_400_000L, WorkRecordSource.MANUAL_ENTRY),
        )
        assertEquals(31, focusMinutesLoggedToday(records, today, zone))
        assertTrue(frogDailyExempt(focusMinutesLoggedToday(records, today, zone)))
    }

    @Test
    fun dailyFocusCountExcludesNonFocusSourcesAndFutureRecords() {
        val now = millisAt(2026, 10, 9, 12, 0)
        val records = listOf(
            TickTickWorkRecord("manual", "Manual", 15, now, WorkRecordSource.MANUAL_ENTRY),
            TickTickWorkRecord("ticktick", "Focus", 16, now, WorkRecordSource.TICKTICK_FOCUS_API),
            TickTickWorkRecord("task", "Task", 60, now, WorkRecordSource.TICKTICK_API),
            TickTickWorkRecord("notification", "Notification", 60, now, WorkRecordSource.TICKTICK_NOTIFICATION),
            TickTickWorkRecord("app", "App foreground", 60, now, WorkRecordSource.TICKTICK_APP_FOCUS),
            TickTickWorkRecord("later-today", "Not logged yet", 90, now + 60_000L, WorkRecordSource.MANUAL_ENTRY),
            TickTickWorkRecord("tomorrow", "Tomorrow", 90, now + 86_400_000L, WorkRecordSource.MANUAL_ENTRY),
        )

        assertEquals(31, focusMinutesLoggedToday(records, now, zone))
    }

    @Test
    fun matchingAggregateCoversTruncatedHistoryButStaleAggregateIsIgnored() {
        val now = millisAt(2026, 10, 9, 12, 0)
        val history = listOf(
            TickTickWorkRecord("visible", "Visible", 20, now, WorkRecordSource.MANUAL_ENTRY),
        )
        val sameDayStats = UserStats(totalWorkMinutesToday = 51, lastResetDate = "2026-10-09")
        val staleStats = UserStats(totalWorkMinutesToday = 51, lastResetDate = "2026-10-08")

        assertEquals(51, focusMinutesToday(history, sameDayStats, now, zone))
        assertEquals(20, focusMinutesToday(history, staleStats, now, zone))
    }

    @Test
    fun exemptionFlowReleasesOnNewCalendarDayWithoutHistoryWrite() = runTest {
        val records = MutableStateFlow(listOf(
            TickTickWorkRecord("manual", "Manual", 31, millisAt(2026, 10, 9, 23, 0), WorkRecordSource.MANUAL_ENTRY),
        ))
        val clock = MutableStateFlow(millisAt(2026, 10, 9, 23, 59))
        val exemption = frogDailyExemptionFlow(records, clock, zone)
        assertTrue(exemption.first())
        clock.value = millisAt(2026, 10, 10, 0, 0)
        assertFalse(exemption.first())
    }

    @Test
    fun dailyGraceStartsAtFirstInteractionAndExpiresAfterFiveMinutes() {
        val interaction = millisAt(2026, 10, 4, 7, 12)
        val deadline = frogGraceEndsAt(interaction)
        assertEquals(millisAt(2026, 10, 4, 7, 17), deadline)
        assertFalse(frogGraceExpired(interaction, deadline - 1))
        assertTrue(frogGraceExpired(interaction, deadline))
        assertTrue(frogGraceExpired(interaction, deadline + 1))
        assertFalse(frogGraceExpired(null, deadline + 1))
    }

    @Test
    fun onlyEnabledUnarmedFutureGraceDeadlinesAreScheduled() {
        val now = millisAt(2026, 10, 4, 7, 12)
        val future = now + 5 * 60_000L
        assertEquals(future, pendingFrogGraceDeadline(true, false, future, now))
        assertEquals(null, pendingFrogGraceDeadline(false, false, future, now))
        assertEquals(null, pendingFrogGraceDeadline(true, true, future, now))
        assertEquals(null, pendingFrogGraceDeadline(true, false, now, now))
        assertEquals(null, pendingFrogGraceDeadline(true, false, null, now))
    }

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
    fun phaseRequiresToolConfirmationBeforeWorking() {
        assertEquals(
            FrogPhase.PICK_TOOLS,
            FrogPhase.from(
                armed = true, selected = true, tickedOff = false,
                trackedSeconds = 0, requiredSeconds = 1800, toolsConfirmed = false,
            ),
        )
    }

    @Test
    fun toolConfirmationPhaseDoesNotReplaceCompletionRequirements() {
        assertEquals(
            FrogPhase.COMPLETE,
            FrogPhase.from(
                armed = true, selected = true, tickedOff = true,
                trackedSeconds = 1800, requiredSeconds = 1800, toolsConfirmed = false,
            ),
        )
        assertEquals(
            FrogPhase.WORKING,
            FrogPhase.from(
                armed = true, selected = true, tickedOff = false,
                trackedSeconds = 0, requiredSeconds = 1800,
            ),
        ) // Legacy callers retain their previous behavior.
    }

    @Test
    fun toolPackageSanitizerTrimsAndRejectsInvalidPackageNames() {
        assertEquals(
            setOf("com.example.notes", "org.reader_2.app"),
            sanitizeFrogToolPackages(
                setOf(" com.example.notes ", "org.reader_2.app", "", "two words", ".bad.name", "a", "bad..name"),
            ),
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
