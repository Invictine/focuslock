package com.focuslock.app

import com.focuslock.app.service.AppRedirectAttemptLimiter
import com.focuslock.app.service.AppRedirectCandidate
import com.focuslock.app.service.AppRedirectPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRedirectPolicyTest {
    @Test
    fun acceptsOnlyRecentCandidateForCurrentTarget() {
        val recent = candidate(observedAtMs = 5_000L)
        assertTrue(AppRedirectPolicy.isEligible(recent, TARGET, 10_000L, true, true, false, false))
        assertFalse(AppRedirectPolicy.isEligible(candidate(observedAtMs = 4_999L), TARGET, 10_000L, true, true, false, false))
        assertFalse(AppRedirectPolicy.isEligible(recent, "com.example.other", 10_000L, true, true, false, false))
        assertFalse(AppRedirectPolicy.isEligible(recent, null, 10_000L, true, true, false, false))
        assertFalse(AppRedirectPolicy.isEligible(candidate(observedAtMs = 10_001L), TARGET, 10_000L, true, true, false, false))
    }

    @Test
    fun requiresLockedFrogAllowedDistinctSourceAndNonWebsiteTarget() {
        val recent = candidate(observedAtMs = 9_000L)
        assertTrue(AppRedirectPolicy.isEligible(recent, TARGET, 10_000L, true, true, false, false))
        assertFalse(AppRedirectPolicy.isEligible(recent, TARGET, 10_000L, true, false, false, false))
        assertFalse(AppRedirectPolicy.isEligible(recent, TARGET, 10_000L, false, true, false, false))
        assertFalse(AppRedirectPolicy.isEligible(recent, TARGET, 10_000L, true, true, true, false))
        assertFalse(AppRedirectPolicy.isEligible(recent, TARGET, 10_000L, true, true, false, true))
        assertFalse(
            AppRedirectPolicy.isEligible(
                AppRedirectCandidate(TARGET, TARGET, 9_000L), TARGET, 10_000L, true, true, false, false,
            ),
        )
    }

    @Test
    fun limiterRejectsDuplicatePairThroughInclusiveCooldownThenAllowsRenewal() {
        val limiter = AppRedirectAttemptLimiter()
        val attempt = candidate(observedAtMs = 0L)

        assertTrue(limiter.tryAcquire(attempt, 1_000L))
        assertFalse(limiter.tryAcquire(attempt, 16_000L))
        assertTrue(limiter.tryAcquire(attempt, 16_001L))
    }

    @Test
    fun limiterTracksOtherPairsIndependentlyAndRejectsNegativeClockDelta() {
        val limiter = AppRedirectAttemptLimiter()
        val attempt = candidate(observedAtMs = 0L)

        assertTrue(limiter.tryAcquire(attempt, 10_000L))
        assertFalse(limiter.tryAcquire(attempt, 9_999L))
        assertTrue(limiter.tryAcquire(candidate(source = "com.example.other", observedAtMs = 0L), 10_000L))
    }

    private fun candidate(
        source: String = SOURCE,
        target: String = TARGET,
        observedAtMs: Long,
    ) = AppRedirectCandidate(source, target, observedAtMs)

    private companion object {
        const val SOURCE = "com.example.allowed"
        const val TARGET = "com.example.blocked"
    }
}
