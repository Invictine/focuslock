package com.focuslock.app.auth

import com.clerk.api.session.Session.SessionStatus
import com.clerk.api.session.SessionTaskKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeSignInDecisionTest {
    @Test
    fun googleCancellationIsSilentButUnavailableAccountAndRealFailureAreDistinct() {
        assertEquals(
            NativeGoogleFailureDisposition.SILENT,
            nativeGoogleFailureDisposition(cancelled = true, accountUnavailable = false, suppress = false),
        )
        assertEquals(
            NativeGoogleFailureDisposition.ACCOUNT_UNAVAILABLE,
            nativeGoogleFailureDisposition(cancelled = false, accountUnavailable = true, suppress = false),
        )
        assertEquals(
            NativeGoogleFailureDisposition.SHOW_ERROR,
            nativeGoogleFailureDisposition(cancelled = false, accountUnavailable = false, suppress = false),
        )
        assertEquals(
            NativeGoogleFailureDisposition.SILENT,
            nativeGoogleFailureDisposition(cancelled = false, accountUnavailable = false, suppress = true),
        )
    }

    @Test
    fun onlyActiveSessionsWithoutPendingTasksMayReachComplete() {
        assertNull(nativeSessionCompletionIssue(SessionStatus.ACTIVE, null))
        assertEquals(
            "Your account requires two-step security setup before continuing.",
            nativeSessionCompletionIssue(SessionStatus.ACTIVE, SessionTaskKey.MFA_REQUIRED),
        )
        assertEquals(
            "Your account needs one more verification step before FocusLock can continue.",
            nativeSessionCompletionIssue(SessionStatus.PENDING, null),
        )
    }
}
