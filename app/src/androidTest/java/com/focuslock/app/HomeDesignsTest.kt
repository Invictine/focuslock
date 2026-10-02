package com.focuslock.app

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.ui.dashboard.home.FocusHome
import com.focuslock.app.ui.dashboard.home.FocusHomeCallbacks
import com.focuslock.app.ui.dashboard.home.FocusHomeState
import com.focuslock.app.ui.dashboard.home.FocusHomeStyle
import com.focuslock.app.ui.dashboard.home.FocusHomeTasksState
import com.focuslock.app.ui.theme.FocusLockTheme
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshot and interaction smoke coverage for all three function-first home layouts.
 * The fixture is entirely in-memory: no preferences, account, or repository state is changed.
 */
@RunWith(AndroidJUnit4::class)
class HomeDesignsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var state: FocusHomeState
    private var logClicks = 0
    private var timerClicks = 0
    private var taskClicks = 0

    @Before
    fun setUp() {
        logClicks = 0
        timerClicks = 0
        taskClicks = 0
        state = FocusHomeState(
            todayFormatted = "Tuesday, 22 September",
            permissionsChecked = true,
            hasAllPermissions = true,
            missingLabels = emptyList(),
            isUsageAccessGranted = true,
            focusMinutes = 40,
            focusMinutesLoaded = true,
            focusGoalMinutes = 90,
            tasksDone = 2,
            tasksLoaded = true,
            tasksGoal = 4,
            tasksState = FocusHomeTasksState.Loaded,
            usageSummary = null,
            topApp = null,
            history = emptyList(),
            showAllHistory = false,
            liveBalanceState = mutableStateOf(900L),
            nukeActive = false,
            accountInitial = "A",
            leisureSeconds = 20 * 60L,
            targetFocusPerLeisure = 2.0,
            nextTaskTitle = "Outline chemistry revision",
            nextTaskDetail = "Chapter 6 · 30 min",
        )
    }

    private fun callbacks() = FocusHomeCallbacks(
        onOpenTickTick = { taskClicks++ },
        onNavigatePermissions = {},
        onOpenSettings = {},
        onOpenAccount = {},
        onOpenLog = { logClicks++ },
        onOpenTimer = { timerClicks++ },
        onToggleHistory = {},
        onRetryTasks = {},
        onShowNukeConfirm = {},
        onShowNukeInfo = {},
        onLaunchNuke = {},
    )

    @Test
    fun allStylesRenderFixtureAndWriteReviewableScreenshots() {
        val selectedStyle = mutableStateOf(FocusHomeStyle.BALANCE)
        val darkTheme = mutableStateOf(false)
        val largeFont = mutableStateOf(false)
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            val density = if (largeFont.value) {
                Density(baseDensity.density, 1.25f)
            } else baseDensity
            CompositionLocalProvider(LocalDensity provides density) {
                FocusLockTheme(darkTheme = darkTheme.value, dynamicColor = false) {
                    Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                        ) {
                            FocusHome(selectedStyle.value, state, callbacks())
                        }
                    }
                }
            }
        }

        FocusHomeStyle.entries.forEachIndexed { index, style ->
            composeRule.runOnIdle {
                selectedStyle.value = style
                darkTheme.value = true
                largeFont.value = false
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Focus today").assertIsDisplayed()
            composeRule.onNodeWithText("40m").assertIsDisplayed()
            composeRule.onNodeWithText("20m").assertIsDisplayed()
            saveScreenshot(composeRule, "home-${style.key}.png")
        }

        // Keep one accessibility-oriented light, enlarged fixture alongside the comparable dark set.
        composeRule.runOnIdle {
            darkTheme.value = false
            largeFont.value = true
        }
        composeRule.waitForIdle()
        saveScreenshot(composeRule, "home-today-light-large.png")
    }

    @Test
    fun primaryActionsAndNextTaskInvokeHoistedCallbacks() {
        composeRule.setContent {
            FocusLockTheme(dynamicColor = false) {
                LazyColumn { FocusHome(FocusHomeStyle.BALANCE, state, callbacks()) }
            }
        }
        composeRule.onNodeWithText("Log work").performClick()
        composeRule.onNodeWithText("Start focus").performClick()
        composeRule.onNodeWithText("Outline chemistry revision").assertIsDisplayed().performClick()
        assertEquals(1, logClicks)
        assertEquals(1, timerClicks)
        assertEquals(1, taskClicks)
    }

    private fun saveScreenshot(rule: ComposeTestRule, fileName: String) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = target.getExternalFilesDir(null) ?: target.filesDir
        val output = directory.resolve(fileName)
        output.outputStream().use { stream ->
            assertTrue(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue(output.exists())
        assertTrue(output.length() > 0L)
    }
}
