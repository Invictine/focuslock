package com.focuslock.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
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

/** Checks the actual app route as well as the smaller account screen fixtures. */
@RunWith(AndroidJUnit4::class)
class MainAccountSignInRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun emailSignInReplacesAccountAndSystemBackReturnsToAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = FocusLockApplication.instance.settingsRepository
        val previousOffline = runBlocking { settings.offlineModeFlow.first() }
        val tour = context.getSharedPreferences("focuslock_product_onboarding", Context.MODE_PRIVATE)
        val hadPaused = tour.contains("paused_v2")
        val previousPaused = tour.getBoolean("paused_v2", false)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            tour.edit().putBoolean("paused_v2", true).commit()
            runBlocking { settings.setOfflineMode(true) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.onNodeWithContentDescription("Account").assertIsDisplayed().performClick()
            compose.onNodeWithText("Use email instead").assertIsDisplayed().performClick()
            compose.onNodeWithText("Continue with email").assertIsDisplayed()
            compose.onNodeWithText("Email").assertIsDisplayed()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithText("Sync across your devices").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back to Account").assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Sync across your devices").assertIsDisplayed()
            compose.onNodeWithText("Continue with email").assertDoesNotExist()
        } finally {
            scenario?.close()
            runBlocking { settings.setOfflineMode(previousOffline) }
            val edit = tour.edit()
            if (hadPaused) edit.putBoolean("paused_v2", previousPaused) else edit.remove("paused_v2")
            edit.commit()
        }
    }
}
