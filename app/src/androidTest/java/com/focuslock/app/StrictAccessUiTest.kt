package com.focuslock.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.ui.strict.StrictAccessScreen
import com.focuslock.app.ui.theme.FocusLockTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrictAccessUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun deviceProtectionsRequireAccessibilityAndOfferSetupRecovery() {
        assumeStrictModeIsOff()
        var openedPermissions = false
        compose.setContent { FocusLockTheme(dynamicColor = false) {
            StrictAccessScreen(onOpenPermissions = { openedPermissions = true }, permissionCheck = { false })
        } }
        compose.onNodeWithText("Choose protections").performScrollTo().performClick()
        compose.onNodeWithText("Prevent uninstalling FocusLock").performScrollTo().performClick()
        compose.onNodeWithText("Save setup").performScrollTo().performClick()
        compose.onNodeWithText("Enable Accessibility in Permissions & protection before using device protections.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review protection permissions").performScrollTo().performClick()
        org.junit.Assert.assertTrue(openedPermissions)
        assertStillOff()
    }

    @Test
    fun allSixAccessMethodsAreReachableWithoutStartingStrictMode() {
        assumeStrictModeIsOff()
        compose.setContent { FocusLockTheme(dynamicColor = false) { StrictAccessScreen() } }

        listOf("Timer", "Follow schedules", "PIN", "Charger", "Cooldown", "Approval contact").forEach { method ->
            compose.onNodeWithText(method, useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
        }
        compose.onNodeWithText("Location is separate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Activate at a place").assertDoesNotExist()
        assertStillOff()
    }

    @Test
    fun pinValidationShowsRecoverableErrorWithoutSavingOrStarting() {
        assumeStrictModeIsOff()
        compose.setContent { FocusLockTheme(dynamicColor = false) { StrictAccessScreen() } }

        compose.onNodeWithText("PIN", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Save setup").performScrollTo().performClick()
        compose.onNodeWithText("Enter and confirm the same PIN (4 to 12 digits).")
            .performScrollTo()
            .assertIsDisplayed()
        assertStillOff()
    }

    @Test
    fun reviewCanBeOpenedAndDismissedWithoutCommitting() {
        assumeStrictModeIsOff()
        compose.setContent { FocusLockTheme(dynamicColor = false) { StrictAccessScreen() } }

        compose.onNodeWithText("Review and start").performScrollTo().performClick()
        compose.onNodeWithText("Review your commitment").assertIsDisplayed()
        compose.onNodeWithText("Start Strict Mode").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Review your commitment").assertDoesNotExist()
        assertStillOff()
    }

    @Test
    fun saveAndReviewActionsRemainReachableOnSmallLargeTextLayout() {
        assumeStrictModeIsOff()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                FocusLockTheme(dynamicColor = false) {
                    Box(Modifier.width(320.dp).height(460.dp)) {
                        StrictAccessScreen()
                    }
                }
            }
        }

        compose.onNodeWithText("Save setup").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review and start").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Review your commitment").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        assertStillOff()
    }

    private fun assumeStrictModeIsOff() {
        val app = FocusLockApplication.instance
        val active = runBlocking {
            app.settingsRepository.lockdownModeFlow.first() ||
                app.strictModeAutomationRepository.activationActiveFlow.first()
        }
        assumeFalse("UI review must not modify an existing Strict Mode commitment", active)
    }

    private fun assertStillOff() {
        val app = FocusLockApplication.instance
        val active = runBlocking {
            app.settingsRepository.lockdownModeFlow.first() ||
                app.strictModeAutomationRepository.activationActiveFlow.first()
        }
        assertFalse("UI coverage must never start Strict Mode", active)
    }
}
