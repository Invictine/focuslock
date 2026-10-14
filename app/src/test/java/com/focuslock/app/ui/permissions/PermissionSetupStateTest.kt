package com.focuslock.app.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSetupStateTest {
    private val originalSteps = listOf(
        PermissionKind.ACCESSIBILITY,
        PermissionKind.USAGE,
        PermissionKind.OVERLAY,
        PermissionKind.NOTIFICATION_LISTENER,
    )

    @Test
    fun closingAndReopeningResumesTheOriginalStepSequence() {
        val dismissed = PermissionSetupState().begin(originalSteps).advance().close()
        val inProgress = restore(dismissed)
        val reopened = inProgress.begin(listOf(PermissionKind.OVERLAY))

        assertEquals(originalSteps, reopened.steps)
        assertEquals(1, reopened.currentIndex)
        assertEquals(PermissionKind.USAGE, reopened.currentStep)
        assertTrue(reopened.visible)
    }

    @Test
    fun visibleSetupSurvivesStateRecreationAndSystemSettingsReturn() {
        val beforeSettings = PermissionSetupState().begin(originalSteps).advance()
        val recreated = restore(beforeSettings)

        assertEquals(originalSteps, recreated.steps)
        assertEquals(1, recreated.currentIndex)
        assertTrue(recreated.visible)
        assertEquals(PermissionKind.USAGE, recreated.currentStep)
    }

    private fun restore(state: PermissionSetupState) = PermissionSetupState.fromStored(
        encodedSteps = state.encodeSteps(),
        currentIndex = state.currentIndex,
        active = state.active,
        visible = state.visible,
        skipped = state.skipped,
    )

    @Test
    fun skippingAOptionalStepKeepsLaterStepsAndCompletionPosition() {
        val state = PermissionSetupState(steps = originalSteps, currentIndex = 3, active = true, visible = true)
        val afterSkip = state.advance()

        assertEquals(originalSteps, afterSkip.steps)
        assertEquals(originalSteps.size, afterSkip.currentIndex)
        assertFalse(afterSkip.active)
        assertFalse(afterSkip.visible)
        assertEquals(null, afterSkip.currentStep)
    }

    @Test
    fun skipAllPersistsItsChoiceUntilTheUserStartsSetupAgain() {
        val skipped = PermissionSetupState().begin(originalSteps).skipAll()
        val resumed = skipped.begin(originalSteps)

        assertTrue(skipped.skipped)
        assertTrue(resumed.visible)
        assertFalse(resumed.skipped)
        assertEquals(originalSteps, resumed.steps)
    }

    @Test
    fun startingANewFlowAfterCompletionStartsAtItsFirstMissingPermission() {
        val completed = (0 until originalSteps.size).fold(PermissionSetupState().begin(originalSteps)) { state, _ ->
            state.advance()
        }
        val newMissing = listOf(PermissionKind.OVERLAY, PermissionKind.NOTIFICATION_LISTENER)

        val restarted = completed.begin(newMissing)

        assertEquals(newMissing, restarted.steps)
        assertEquals(0, restarted.currentIndex)
        assertEquals(PermissionKind.OVERLAY, restarted.currentStep)
        assertTrue(restarted.visible)
    }

    @Test
    fun freshInstallStartsFromCurrentMissingPermissionsAndHonorsLegacySkip() {
        val fresh = PermissionSetupState.firstRun(originalSteps, previouslySkipped = false)
        val skipped = PermissionSetupState.firstRun(originalSteps, previouslySkipped = true)

        assertEquals(originalSteps, fresh.steps)
        assertTrue(fresh.visible)
        assertFalse(skipped.visible)
        assertTrue(skipped.skipped)
    }
}
