package com.focuslock.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue

/** Real-activity screenshots and visibility checks for wide landscape device runs. */
@RunWith(AndroidJUnit4::class)
class TabletLayoutUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun coreTabsKeepPrimaryActionsAvailableInWideWindows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = FocusLockApplication.instance.settingsRepository
        val previousOfflineMode = runBlocking { settings.offlineModeFlow.first() }
        val fixture = NavigationOnboardingFixture(context)
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            runBlocking { settings.setOfflineMode(true) }
            fixture.prepare()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitForIdle()

            compose.onNodeWithText("Focus today").assertIsDisplayed()
            compose.onNodeWithText("Start focus").assertIsDisplayed()
            saveScreenshot("tablet-layout-focus")

            compose.onNodeWithContentDescription("Settings").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Account & devices").assertIsDisplayed()
            compose.onNodeWithText("Connections").assertIsDisplayed()
            compose.onNodeWithText("Preferences").assertIsDisplayed()
            saveScreenshot("tablet-layout-settings")

            compose.onNodeWithContentDescription("Back to Focus").performClick()
            compose.onNode(hasClickAction() and hasText("Boundaries")).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Applications").assertIsDisplayed()
            compose.onNodeWithText("Websites").assertIsDisplayed()
            saveScreenshot("tablet-layout-boundaries")
        } finally {
            try {
                scenario?.close()
            } finally {
                fixture.restore()
                runBlocking { settings.setOfflineMode(previousOfflineMode) }
            }
        }
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val screenshot = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("$name.png")
        screenshot.outputStream().use { output ->
            assertTrue("Could not save $name screenshot", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
        assertTrue("Missing or empty screenshot: $screenshot", screenshot.isFile && screenshot.length() > 0L)
    }
}
