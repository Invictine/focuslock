package com.focuslock.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.ui.MainActivity
import com.focuslock.app.ui.onboarding.ProductOnboardingStore
import com.focuslock.app.ui.onboarding.productTourPages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductOnboardingIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun offlineFirstRunPersistsPauseResumeCompletionAndReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = FocusLockApplication.instance
        val settings: SettingsRepository = app.settingsRepository
        val priorOfflineMode = runBlocking { settings.offlineModeFlow.first() }
        val onboardingPrefs = context.getSharedPreferences(ONBOARDING_PREFS, 0)
        val priorOnboardingPrefs = onboardingPrefs.all.toMap()
        val permissionPrefs = context.getSharedPreferences(PERMISSION_PREFS, 0)
        val priorPermissionPrefs = permissionPrefs.all.toMap()
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            runBlocking { settings.setOfflineMode(true) }
            ProductOnboardingStore(context).replay()
            permissionPrefs.edit().putBoolean("permissions_skipped", true).commit()

            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("FocusLock guide").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("FocusLock guide").assertIsDisplayed()
            assertCurrentPage(0)

            compose.onNodeWithText("Continue").performClick()
            compose.onNodeWithText("Continue").performClick()
            assertCurrentPage(2)

            // A sign-in activity may still cover MainActivity when the session activates.
            // Backgrounding the host hides the dialog without recording a user dismissal.
            activeScenario.moveToState(Lifecycle.State.STARTED)
            compose.onNodeWithText("FocusLock guide").assertDoesNotExist()
            assertTrue(ProductOnboardingStore(context).shouldAutoShow)
            activeScenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText("FocusLock guide").assertIsDisplayed()
            assertCurrentPage(2)

            activeScenario.recreate()
            compose.onNodeWithText("FocusLock guide").assertIsDisplayed()
            assertCurrentPage(2)

            compose.onNodeWithText("Save for later").performClick()
            assertFalse(ProductOnboardingStore(context).shouldAutoShow)
            activeScenario.recreate()
            compose.onNodeWithText("FocusLock guide").assertDoesNotExist()

            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Resume guide").performScrollTo().performClick()
            compose.onNodeWithText("FocusLock guide").assertIsDisplayed()
            assertCurrentPage(2)

            val lastIndex = productTourPages.lastIndex
            compose.onNodeWithText("Topics").performClick()
            compose.onNodeWithText(
                "${lastIndex + 1}. ${productTourPages[lastIndex].title}", substring = true
            ).performScrollTo().performClick()
            assertCurrentPage(lastIndex)
            compose.onNodeWithText("Finish guide").performClick()
            compose.onNodeWithText("FocusLock guide").assertDoesNotExist()
            assertTrue(ProductOnboardingStore(context).isComplete)
            assertFalse(ProductOnboardingStore(context).shouldAutoShow)

            compose.onNodeWithText("Replay guide").performScrollTo().performClick()
            compose.onNodeWithText("FocusLock guide").assertIsDisplayed()
            assertCurrentPage(0)
            assertFalse(ProductOnboardingStore(context).isComplete)
        } finally {
            scenario?.close()
            runBlocking { settings.setOfflineMode(priorOfflineMode) }
            onboardingPrefs.edit().clear().apply {
                priorOnboardingPrefs.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
            permissionPrefs.edit().clear().apply {
                priorPermissionPrefs.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
        }
    }

    private fun assertCurrentPage(index: Int) {
        assertEquals(index, ProductOnboardingStore(
            InstrumentationRegistry.getInstrumentation().targetContext
        ).pageIndex)
        compose.onNodeWithText(productTourPages[index].title).assertIsDisplayed()
    }

    private companion object {
        const val ONBOARDING_PREFS = "focuslock_product_onboarding"
        const val PERMISSION_PREFS = "focuslock_onboarding"
    }
}
