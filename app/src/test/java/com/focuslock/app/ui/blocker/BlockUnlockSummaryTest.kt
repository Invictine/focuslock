package com.focuslock.app.ui.blocker

import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.model.FrogState
import com.focuslock.app.data.model.FrogTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockUnlockSummaryTest {
    @Test
    fun permanentBlocksTakePriorityOverAvailableBalance() {
        assertEquals(
            "Focus time cannot unlock a permanent block.",
            blockUnlockSummary("permanent", frog = frog(locked = true), balanceSeconds = 500)
        )
    }

    @Test
    fun strictModeDoesNotHideAvailableCredits() {
        assertEquals("Leisure time is available.", blockUnlockSummary(null, frog = null, balanceSeconds = 500))
    }

    @Test
    fun dailyLimitScheduleAndNuclearReasonsStayTruthful() {
        assertEquals("Your daily limit resets tomorrow.", blockUnlockSummary("limit", null, 10))
        assertEquals("Your block schedule must end first.", blockUnlockSummary("schedule", null, 10))
        assertEquals("The nuclear block must end first.", blockUnlockSummary("nuke", null, 10))
    }

    @Test
    fun frogRequirementUsesCeilingAndStillRequiresTickOff() {
        assertEquals(
            "2 min more focus on your frog. Tick it off when complete.",
            blockUnlockSummary("frog", frog(locked = true, trackedSeconds = 61, requiredSeconds = 122), 60)
        )
        assertEquals(
            "Focus target met. Tick off your frog to unlock.",
            blockUnlockSummary("frog", frog(locked = true, trackedSeconds = 240, requiredSeconds = 180), 60)
        )
        assertEquals("Frog complete.", blockUnlockSummary("frog", frog(locked = true, trackedSeconds = 180, requiredSeconds = 180, tickedOff = true), 60))
    }

    @Test
    fun missingFrogStateIsLoadingAndMissingTaskPromptsSelection() {
        assertNull(blockUnlockSummary("frog", null, 0))
        assertEquals(
            "Choose your frog, then focus for 2 min and tick it off.",
            blockUnlockSummary("frog", frog(locked = true, task = null, requiredSeconds = 61), 0)
        )
    }

    @Test
    fun normalCreditBalanceExplainsAvailabilityOrOneMinuteNeeded() {
        assertNull(blockUnlockSummary(null, null, null))
        assertEquals("Leisure time is available.", blockUnlockSummary(null, null, 1))
        assertEquals("Log 1 min more focus to earn leisure time.", blockUnlockSummary(null, null, 0))
    }

    private fun frog(
        locked: Boolean,
        trackedSeconds: Int = 0,
        requiredSeconds: Int = 120,
        tickedOff: Boolean = false,
        task: FrogTask? = FrogTask(id = "task", title = "Task")
    ) = FrogState(
        cycleDate = "2026-10-01",
        enabled = true,
        armed = true,
        phase = FrogPhase.WORKING,
        frog = task,
        tickedOff = tickedOff,
        trackedSeconds = trackedSeconds,
        requiredSeconds = requiredSeconds,
        locked = locked,
        openTasks = emptyList()
    )
}
