package com.focuslock.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.ui.blocker.StrictSettingsBlockerScreen
import com.focuslock.app.ui.theme.FocusLockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrictSettingsBlockerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dedicatedStrictScreenOffersOnlyFocusLockAndHomeActions() {
        var openFocusLockCalls = 0
        var homeCalls = 0
        composeRule.setContent {
            FocusLockTheme {
                StrictSettingsBlockerScreen(
                    onOpenFocusLock = { openFocusLockCalls++ },
                    onGoHome = { homeCalls++ },
                )
            }
        }

        composeRule.onNodeWithText("Device settings are locked").assertIsDisplayed()
        composeRule.onNodeWithText("Strict Mode is active. Settings will be available again when this commitment ends.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Open FocusLock").assertIsDisplayed()
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Close app").assertDoesNotExist()
        composeRule.onNodeWithText("Emergency unlock").assertDoesNotExist()
        composeRule.onNodeWithText("Verify TickTick work").assertDoesNotExist()
        composeRule.onNodeWithText("Unlock with credits").assertDoesNotExist()

        composeRule.onNodeWithText("Open FocusLock").performClick()
        composeRule.onNodeWithText("Home").performClick()
        assertEquals(1, openFocusLockCalls)
        assertEquals(1, homeCalls)
    }
}
