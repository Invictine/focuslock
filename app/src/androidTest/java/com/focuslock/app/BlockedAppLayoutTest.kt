package com.focuslock.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.ui.blocker.BlockAttemptCounts
import com.focuslock.app.ui.blocker.BlockedAppScreen
import com.focuslock.app.ui.theme.FocusLockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory layout and callback coverage for the unified blocked-app screen. */
@RunWith(AndroidJUnit4::class)
class BlockedAppLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private data class Fixture(
        val appName: String,
        val isWebsite: Boolean,
        val isPermanentBlock: Boolean,
        val attempts: BlockAttemptCounts?,
        val focusMinutes: Int?,
        val leisureSeconds: Long?,
        val unlockSummary: String?,
        val frogPending: Boolean,
        val darkTheme: Boolean,
        val fontScale: Float,
    )

    @Test
    fun blockedStatesRemainClearAndActionsStayReachable() {
        val fixture = mutableStateOf(strictFrogFixture())
        var closeCalls = 0
        var focusLockCalls = 0

        composeRule.setContent {
            val density = LocalDensity.current
            val value = fixture.value
            CompositionLocalProvider(LocalDensity provides Density(density.density, value.fontScale)) {
                FocusLockTheme(darkTheme = value.darkTheme, dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) {
                        BlockedAppScreen(
                            appName = value.appName,
                            isWebsite = value.isWebsite,
                            isPermanentBlock = value.isPermanentBlock,
                            attempts = value.attempts,
                            focusMinutes = value.focusMinutes,
                            leisureSeconds = value.leisureSeconds,
                            unlockSummary = value.unlockSummary,
                            frogPending = value.frogPending,
                            appIcon = null,
                            onCloseApp = { closeCalls++ },
                            onOpenFocusLock = { focusLockCalls++ },
                        )
                    }
                }
            }
        }

        assertHeroAndPrimaryActions()
        composeRule.onNodeWithText("Your boundary is active").assertIsDisplayed()
        composeRule.onNodeWithText("Eat the Frog pending").assertIsDisplayed()
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("This week").assertIsDisplayed()
        saveScreenshot(composeRule, "blocked-app-strict.png")

        // Ordinary boundary blocks retain the same hero and actions.
        composeRule.runOnIdle { fixture.value = normalFixture() }
        composeRule.waitForIdle()
        assertHeroAndPrimaryActions()
        composeRule.onNodeWithText("Strict mode is on").assertDoesNotExist()
        composeRule.onNodeWithText("Eat the Frog pending").assertDoesNotExist()
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        saveScreenshot(composeRule, "blocked-app-normal.png")

        // At enlarged text, locate both bottom controls through the screen's scroll container.
        composeRule.runOnIdle {
            fixture.value = strictFrogFixture().copy(fontScale = 1.3f)
        }
        composeRule.waitForIdle()
        assertHeroAndPrimaryActions()
        composeRule.onNodeWithText("Close app").assertIsDisplayed()
        composeRule.onNodeWithText("Open FocusLock").assertIsDisplayed()
        saveScreenshot(composeRule, "blocked-app-large-font.png")

        // Light usage fixture checks all data rows and the task-specific unlock explanation.
        composeRule.runOnIdle { fixture.value = lightUsageFixture() }
        composeRule.waitForIdle()
        assertHeroAndPrimaryActions()
        composeRule.onNodeWithText("Focused today").assertIsDisplayed()
        composeRule.onNodeWithText("Leisure today").assertIsDisplayed()
        composeRule.onNodeWithText("12 min more focus on your frog").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("11").assertIsDisplayed()
        saveScreenshot(composeRule, "blocked-app-light.png")

        composeRule.onNodeWithText("Close app").performClick()
        composeRule.onNodeWithText("Open FocusLock").performClick()
        assertEquals(1, closeCalls)
        assertEquals(1, focusLockCalls)
    }

    private fun assertHeroAndPrimaryActions() {
        composeRule.onNodeWithText("App is blocked").assertIsDisplayed()
        composeRule.onNodeWithText("Close app").assertIsDisplayed()
        composeRule.onNodeWithText("Open FocusLock").assertIsDisplayed()
    }

    private fun strictFrogFixture() = Fixture(
        appName = "YouTube",
        isWebsite = false,
        isPermanentBlock = false,
        attempts = BlockAttemptCounts(today = 3, week = 11),
        focusMinutes = 40,
        leisureSeconds = 20 * 60L,
        unlockSummary = "12 min more focus on your frog. Tick it off when complete. Strict mode must end before this app unlocks.",
        frogPending = true,
        darkTheme = true,
        fontScale = 1f,
    )

    private fun normalFixture() = Fixture(
        appName = "Instagram",
        isWebsite = false,
        isPermanentBlock = false,
        attempts = BlockAttemptCounts(today = 2, week = 8),
        focusMinutes = 40,
        leisureSeconds = 20 * 60L,
        unlockSummary = "Log 1 min more focus to earn leisure time.",
        frogPending = false,
        darkTheme = true,
        fontScale = 1f,
    )

    private fun lightUsageFixture() = Fixture(
        appName = "YouTube",
        isWebsite = false,
        isPermanentBlock = false,
        attempts = BlockAttemptCounts(today = 3, week = 11),
        focusMinutes = 40,
        leisureSeconds = 20 * 60L,
        unlockSummary = "12 min more focus on your frog",
        frogPending = true,
        darkTheme = false,
        fontScale = 1f,
    )

    private fun saveScreenshot(rule: ComposeTestRule, fileName: String) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = target.getExternalFilesDir(null) ?: target.filesDir
        val output = directory.resolve(fileName)
        output.outputStream().use { stream ->
            assertTrue(
                rule.onRoot().captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, stream)
            )
        }
        assertTrue(output.exists())
        assertTrue(output.length() > 0L)
    }
}
