package com.focuslock.app

import com.focuslock.app.service.AppBlockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppBlockPolicyTest {
    @Test
    fun selectedAppWithPositiveCreditRemainsAccessibleDuringStrictCommitment() {
        // Strict commitments are intentionally absent from the access inputs.
        assertNull(AppBlockPolicy.blockReason(blocked = true, scheduleActive = false, balanceSeconds = 60))
    }

    @Test
    fun ordinaryBoundaryStillBlocksWithoutCreditAndScheduleOverridesCredit() {
        assertEquals("manual", AppBlockPolicy.blockReason(blocked = true, scheduleActive = false, balanceSeconds = 0))
        assertEquals("schedule", AppBlockPolicy.blockReason(blocked = true, scheduleActive = true, balanceSeconds = 60))
        assertNull(AppBlockPolicy.blockReason(blocked = false, scheduleActive = true, balanceSeconds = 0))
    }
}
