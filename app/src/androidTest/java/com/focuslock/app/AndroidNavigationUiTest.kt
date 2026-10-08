package com.focuslock.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production navigation shell, with account/network setup kept offline. */
@RunWith(AndroidJUnit4::class)
class AndroidNavigationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun topBarRestoresNukeSettingsAndProfileAcrossPrimaryTabs() {
        val settings = FocusLockApplication.instance.settingsRepository
        val priorNuke = runBlocking { settings.nukeActiveFlow.first() }
        try {
            runBlocking { settings.setNukeActive(false) }
            withApp { scenario ->
                for (tab in listOf("Today", "Boundaries", "Activity")) {
                    compose.onNode(hasText(tab) and hasClickAction()).performClick()
                    val nuke = compose.onNodeWithContentDescription("Nuke").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val gear = compose.onNodeWithContentDescription("Settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val profile = compose.onNodeWithContentDescription("Profile").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    org.junit.Assert.assertTrue("Top bar order", nuke.left < gear.left && gear.left < profile.left)
                }
                compose.onNodeWithContentDescription("Nuke").performClick()
                compose.onNodeWithText("Detonate the Nuke?").assertIsDisplayed()
                scenario.recreate()
                compose.onNodeWithText("Detonate the Nuke?").assertIsDisplayed()
                compose.onNodeWithText("About Nuke").performClick()
                compose.onNodeWithText("What is the Nuke?").assertIsDisplayed()
                compose.onNodeWithText("Got it").performClick()
                compose.onNodeWithText("Cancel").performClick()
                org.junit.Assert.assertFalse(runBlocking { settings.nukeActiveFlow.first() })
                compose.onNodeWithContentDescription("Profile").performClick()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNode(hasText("Activity") and hasClickAction()).assertIsSelected()
            }
        } finally {
            runBlocking { settings.setNukeActive(priorNuke) }
        }
    }

    @Test fun settingsReturnToTheirOriginAndNestedPagesReturnToSettings() = withApp { scenario ->
        compose.onNode(hasText("Boundaries") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Account & devices").assertIsDisplayed()
        compose.onNodeWithText("Connections").performClick()
        compose.onNodeWithText("Account & devices").assertDoesNotExist()
        scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Account & devices").assertDoesNotExist()
        compose.onNodeWithText("Connections").assertIsDisplayed()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Account & devices").assertIsDisplayed()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Apps").assertIsDisplayed()
        compose.onNode(hasText("Activity") and hasClickAction()).assertIsDisplayed()
        capture("android-boundaries-redesign")
    }

    @Test fun strictIsReachedThroughBoundariesAndBackReturnsThere() = withApp { scenario ->
        compose.onNode(hasText("Boundaries") and hasClickAction()).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Lock boundary changes"))
        compose.onNodeWithText("Lock boundary changes").performClick()
        compose.onNodeWithText("Manual", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("Activity") and hasClickAction()).assertDoesNotExist()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasText("Activity") and hasClickAction()).assertIsDisplayed()
    }

    @Test fun todayAndActivityAreSeparateReviewableDestinations() = withApp {
        compose.onNode(hasText("Today") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Strict") and hasClickAction()).assertDoesNotExist()
        capture("android-today-redesign")
        compose.onNode(hasText("Activity") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        capture("android-activity-redesign")
        compose.onNode(hasText("Today") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test fun runningTimerSurvivesActivityAndCanBeResumedFromToday() {
        val frog = FocusLockApplication.instance.frogRepository
        val wasEnabled = runBlocking { frog.enabledFlow.first() }
        try {
            runBlocking { frog.setEnabled(false) }
            withApp {
                compose.onNodeWithText("Start focus timer").performClick()
                compose.onNodeWithText("Start", useUnmergedTree = true).performClick()
                compose.onNodeWithText("Hide timer").performClick()
                compose.onNode(hasText("Activity") and hasClickAction()).performClick()
                compose.onNode(hasText("Today") and hasClickAction()).performClick()
                compose.onNodeWithText("Resume focus timer").performClick()
                compose.onNodeWithText("Hide timer").assertIsDisplayed()
                compose.onNodeWithText("Finish Early").assertIsDisplayed()
                compose.onNodeWithText("Finish Early").performClick()
            }
        } finally {
            runBlocking { frog.setEnabled(wasEnabled) }
        }
    }

    private fun withApp(test: (ActivityScenario<MainActivity>) -> Unit) {
        val app = FocusLockApplication.instance
        val previousOffline = runBlocking { app.settingsRepository.offlineModeFlow.first() }
        val onboarding = NavigationOnboardingFixture(app)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            onboarding.prepare()
            runBlocking { app.settingsRepository.setOfflineMode(true) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitForIdle()
            test(scenario)
        } finally {
            scenario?.close()
            onboarding.restore()
            runBlocking { app.settingsRepository.setOfflineMode(previousOffline) }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        directory.resolve("$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
