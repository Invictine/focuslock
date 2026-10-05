package com.focuslock.app

import com.focuslock.app.service.WebsiteBlockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebsiteBlockPolicyTest {
    @Test
    fun sameDomainIsReevaluatedWhenBoundaryPolicyChanges() {
        assertNull(policy(blocked = true, balanceSeconds = 30))
        assertEquals("schedule", policy(blocked = true, scheduleActive = true, balanceSeconds = 30))
    }

    @Test
    fun permanentBlockCannotBeBypassedBySuppression() {
        assertEquals("permanent", policy(blocked = true, permanent = true, suppressed = true, balanceSeconds = 60))
    }

    @Test
    fun scheduleBlocksWithPositiveCreditButStrictDoesNotChangeAccess() {
        assertNull(policy(blocked = true, balanceSeconds = 60))
        assertEquals("schedule", policy(blocked = true, scheduleActive = true, balanceSeconds = 60))
    }

    @Test
    fun ordinaryBlockedSiteAllowsPositiveCreditAndBlocksAtZero() {
        assertNull(policy(blocked = true, balanceSeconds = 1))
        assertEquals("manual", policy(blocked = true, balanceSeconds = 0))
    }

    @Test
    fun unblockedSiteHasNoReasonUnlessGroupLimitExceeded() {
        assertNull(policy(blocked = false, balanceSeconds = 0))
        assertEquals("limit", policy(blocked = false, groupLimitExceeded = true, balanceSeconds = 60))
    }

    @Test
    fun groupLimitPrecedesRegularBlockReasons() {
        assertEquals("limit", policy(blocked = true, groupLimitExceeded = true, balanceSeconds = 0))
    }

    private fun policy(
        blocked: Boolean,
        permanent: Boolean = false,
        groupLimitExceeded: Boolean = false,
        scheduleActive: Boolean = false,
        suppressed: Boolean = false,
        balanceSeconds: Long,
    ): String? = WebsiteBlockPolicy.blockReason(
        blocked = blocked,
        permanent = permanent,
        groupLimitExceeded = groupLimitExceeded,
        scheduleActive = scheduleActive,
        suppressed = suppressed,
        balanceSeconds = balanceSeconds,
    )
}
