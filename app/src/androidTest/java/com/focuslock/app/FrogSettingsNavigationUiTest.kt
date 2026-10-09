package com.focuslock.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Main-app navigation only. Run on the emulator; restores its offline setting. */
@RunWith(AndroidJUnit4::class)
class FrogSettingsNavigationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun settingsOpensEssentialAppsAndBackReturnsToFrogSettings() {
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
            compose.onNodeWithText("Daily priority routine").performClick()
            compose.onNodeWithText("Essential apps").performScrollTo().performClick()
            compose.onNodeWithText("Choose apps available for every frog.").assertIsDisplayed()
            compose.onNodeWithText("Save").assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Choose what's available each morning").performScrollTo().assertIsDisplayed()
        } finally {
            scenario?.close()
            fixture.restore()
            runBlocking { settings.setOfflineMode(previousOffline) }
        }
    }
}
