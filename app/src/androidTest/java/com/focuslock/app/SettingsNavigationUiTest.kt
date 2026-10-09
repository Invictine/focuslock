package com.focuslock.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun settingsCategoriesBackPermissionAccountAndEssentials() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val settings = FocusLockApplication.instance.settingsRepository
        val previousOffline = runBlocking { settings.offlineModeFlow.first() }
        val fixture = NavigationOnboardingFixture(context)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            runBlocking { settings.setOfflineMode(true) }
            fixture.prepare()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Account & devices").assertIsDisplayed()

            compose.onNodeWithText("Connections").performClick()
            compose.onNodeWithText("Connections").assertIsDisplayed()
            compose.onNodeWithText("Or paste a personal token (tp_...) instead").assertDoesNotExist()
            compose.onNodeWithText("Advanced provider options").performClick()
            compose.onNodeWithText("Or paste a personal token (tp_...) instead").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back to Settings").performClick()
            compose.onNodeWithText("Preferences").performClick()
            compose.onNodeWithText("Preferences").assertIsDisplayed()
            compose.onNodeWithText("Focus home style").performScrollTo().assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Account & devices").assertIsDisplayed()

            compose.onNodeWithText("Permissions & protection").performClick()
            compose.onNodeWithText("Boundaries Lock").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("System Protection Status").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Back to Settings").performClick()

            compose.onNodeWithText("Account & devices").performClick()
            compose.onNodeWithText("Use email instead").assertIsDisplayed().performClick()
            compose.onNodeWithText("Continue with email").assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Sync across your devices").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back to Settings").performClick()
            compose.onNodeWithText("Account & devices").assertIsDisplayed()

            compose.onNodeWithText("Daily priority routine").performClick()
            compose.onNodeWithText("Essential apps").performScrollTo().performClick()
            compose.onNodeWithText("Choose apps available for every frog.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back to Settings").performClick()
            compose.onNodeWithText("Choose what's available each morning").performScrollTo().assertIsDisplayed()
        } finally {
            scenario?.close()
            fixture.restore()
            runBlocking { settings.setOfflineMode(previousOffline) }
        }
    }
}
