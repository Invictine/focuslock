package com.focuslock.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun bottomNavigationConnectsBoundariesLocationAndStrictRoutes() {
        val settings = FocusLockApplication.instance.settingsRepository
        val previousOfflineMode = runBlocking { settings.offlineModeFlow.first() }
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            runBlocking { settings.setOfflineMode(true) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitForIdle()

            assertBottomNavigation()
            compose.onNode(bottomNavItem("Boundaries")).performClick()
            compose.waitForIdle()
            scrollBoundariesTo("Applications")
            compose.onNodeWithText("Applications").assertIsDisplayed()
            saveScreenshot("main-boundaries")

            scrollBoundariesTo("Permanent blocks")
            compose.onNodeWithText("Permanent blocks").performClick()
            compose.onNodeWithText("Block a website").performScrollTo().assertIsDisplayed()
            saveScreenshot("main-permanent")

            compose.onNodeWithContentDescription("Back").performClick()
            scrollBoundariesTo("Blocking location")
            compose.onNodeWithText("Blocking location").performClick()
            compose.onNodeWithText("Choose home location").performScrollTo().assertIsDisplayed()
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
            }
            scrollBoundariesTo("Blocking location")
            compose.onNodeWithText("Blocking location").assertIsDisplayed()

            compose.onNode(bottomNavItem("Strict")).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Schedule", useUnmergedTree = true)
                .performScrollTo().performClick()
            compose.onNodeWithText("Activate on a schedule").performScrollTo().assertIsDisplayed()

            compose.onNodeWithText("Location", useUnmergedTree = true)
                .performScrollTo().performClick()
            compose.onNodeWithText("Activate at a place").performScrollTo().assertIsDisplayed()

            compose.onNode(bottomNavItem("Focus")).performClick()
            compose.onNode(bottomNavItem("Boundaries")).performClick()
            compose.onNode(bottomNavItem("Strict")).performClick()
            compose.onNodeWithText("Activate at a place").performScrollTo().assertIsDisplayed()
        } finally {
            try {
                scenario?.close()
            } finally {
                runBlocking { settings.setOfflineMode(previousOfflineMode) }
            }
        }
    }

    private fun assertBottomNavigation() {
        listOf("Focus", "Boundaries", "Strict").forEach { label ->
            org.junit.Assert.assertEquals(
                "Expected one clickable $label bottom navigation item",
                1,
                compose.onAllNodes(bottomNavItem(label)).fetchSemanticsNodes().size
            )
        }
        compose.onNode(bottomNavItem("Permalock")).assertDoesNotExist()
    }

    private fun bottomNavItem(label: String) = hasClickAction() and hasText(label)

    private fun scrollBoundariesTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        context.filesDir.resolve("$name.png").outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
