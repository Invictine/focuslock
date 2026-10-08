package com.focuslock.app

import com.focuslock.app.updates.UpdateCheckGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckGateTest {
    @Test fun manualRetryBypassesCooldownButCannotOverlapAnActiveCheck() {
        val gate = UpdateCheckGate()
        assertTrue(gate.tryStart(0))
        assertFalse(gate.tryStart(1, manual = true))
        gate.finish()
        assertFalse(gate.tryStart(2))
        assertTrue(gate.tryStart(2, manual = true))
    }

    @Test fun returningFromSignInOrRotatingDoesNotStartAnotherDownload() {
        val gate = UpdateCheckGate()
        assertTrue(gate.tryStart(0))
        assertFalse(gate.tryStart(70_000)) // The first download is still active.
        gate.finish()
        assertTrue(gate.tryStart(70_000))
    }

    @Test fun returningFromInstallerOrCancellingDoesNotImmediatelyPromptAgain() {
        val gate = UpdateCheckGate()
        assertTrue(gate.tryStart(1_000))
        gate.finish() // Success, cancellation, and failure all release the same gate.
        assertFalse(gate.tryStart(2_000))
        assertFalse(gate.tryStart(60_999))
        assertTrue(gate.tryStart(61_000))
    }
}
