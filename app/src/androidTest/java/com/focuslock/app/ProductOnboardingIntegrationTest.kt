package com.focuslock.app

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.focuslock.app.ui.onboarding.liveTasks
import com.focuslock.app.ui.permissions.PermissionKind
import com.focuslock.app.ui.permissions.PermissionSetupState
import com.focuslock.app.ui.permissions.PermissionSetupStore
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
    fun focusWalkthroughStaysVisibleWhenPermissionSetupWasAlreadyPending() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings: SettingsRepository = FocusLockApplication.instance.settingsRepository
        val priorOfflineMode = runBlocking { settings.offlineModeFlow.first() }
        val onboardingPrefs = context.getSharedPreferences(ONBOARDING_PREFS, 0)
        val priorOnboardingPrefs = onboardingPrefs.all.toMap()
        val permissionPrefs = context.getSharedPreferences(PERMISSION_PREFS, 0)
        val priorPermissionPrefs = permissionPrefs.all.toMap()
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            runBlocking { settings.setOfflineMode(true) }
            ProductOnboardingStore(context).replay()
            PermissionSetupStore(context).save(
                PermissionSetupState().begin(listOf(PermissionKind.ACCESSIBILITY))
            )

            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("FocusLock guide").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Open Focus").performScrollTo().performClick()

            compose.onNodeWithText(productTourPages.first().liveTasks().first()).assertIsDisplayed()
            compose.onNodeWithText("Accessibility access").assertDoesNotExist()
            compose.onNodeWithText("Continue").performClick()
            compose.onNodeWithText(productTourPages.first().liveTasks()[1]).assertIsDisplayed()

            activeScenario.close()
            scenario = null
            val resumedScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = resumedScenario
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(productTourPages.first().liveTasks()[1])
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(productTourPages.first().liveTasks()[1]).assertIsDisplayed()
            compose.onNodeWithText("FocusLock guide").assertDoesNotExist()
            compose.onNodeWithText("Accessibility access").assertDoesNotExist()
        } finally {
            scenario?.close()
            runBlocking { settings.setOfflineMode(priorOfflineMode) }
            restorePreferences(onboardingPrefs, priorOnboardingPrefs)
            restorePreferences(permissionPrefs, priorPermissionPrefs)
        }
    }

    @Test
    fun boundaryWalkthroughKeepsProgressAcrossRecreationAndCanResumeFromSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings: SettingsRepository = FocusLockApplication.instance.settingsRepository
        val priorOfflineMode = runBlocking { settings.offlineModeFlow.first() }
        val onboardingPrefs = context.getSharedPreferences(ONBOARDING_PREFS, 0)
        val priorOnboardingPrefs = onboardingPrefs.all.toMap()
        val permissionPrefs = context.getSharedPreferences(PERMISSION_PREFS, 0)
        val priorPermissionPrefs = permissionPrefs.all.toMap()
        val boundaryPage = productTourPages.first { it.id == "boundaries" }
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            runBlocking { settings.setOfflineMode(true) }
            ProductOnboardingStore(context).replay()
            ProductOnboardingStore(context).savePage(productTourPages.indexOf(boundaryPage))
            permissionPrefs.edit().putBoolean("permissions_skipped", true).commit()

            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("FocusLock guide").fetchSemanticsNodes().isNotEmpty()
            }
            assertCurrentPage(productTourPages.indexOf(boundaryPage))
            compose.onNodeWithText(boundaryPage.actionLabel!!).performScrollTo().performClick()

            compose.onNodeWithText(boundaryPage.liveTasks().first()).assertIsDisplayed()
            compose.onNodeWithText("Applications").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Websites").assertIsDisplayed()
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                context.filesDir.resolve("live-tutorial.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.onNodeWithText("Continue").performClick()
            compose.onNodeWithText(boundaryPage.liveTasks()[1]).assertIsDisplayed()
            assertEquals(1, ProductOnboardingStore(context).taskIndex(boundaryPage.id))

            activeScenario.recreate()
            compose.onNodeWithText(boundaryPage.liveTasks()[1]).assertIsDisplayed()
            compose.onNodeWithText("Applications").performScrollTo().assertIsDisplayed()
            assertEquals(1, ProductOnboardingStore(context).taskIndex(boundaryPage.id))

            compose.onNodeWithText("Pause").performClick()
            compose.onNodeWithText(boundaryPage.liveTasks()[1]).assertDoesNotExist()
            assertFalse(ProductOnboardingStore(context).shouldAutoShow)

            compose.onNodeWithContentDescription("Focus", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Preferences").performClick()
            compose.onNodeWithText("Resume guide").performScrollTo().performClick()
            compose.onNodeWithText(boundaryPage.liveTasks()[1]).assertIsDisplayed()
            assertTrue(ProductOnboardingStore(context).shouldAutoShow)
            assertTrue(ProductOnboardingStore(context).hasActiveLiveTutorial)
            assertEquals(1, ProductOnboardingStore(context).taskIndex(boundaryPage.id))
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

            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithText("Next").performClick()
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
            compose.onNodeWithText("Preferences").performClick()
            compose.onNodeWithText("Resume guide").performScrollTo().performClick()
            compose.onNodeWithText("FocusLock guide").assertIsDisplayed()
            assertCurrentPage(2)

            val lastIndex = productTourPages.lastIndex
            compose.onNodeWithText("All topics").performClick()
            compose.onNodeWithText(productTourPages[lastIndex].title).performScrollTo().performClick()
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

    private fun restorePreferences(
        preferences: android.content.SharedPreferences,
        values: Map<String, Any?>,
    ) {
        preferences.edit().clear().apply {
            values.forEach { (key, value) ->
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

    private companion object {
        const val ONBOARDING_PREFS = "focuslock_product_onboarding"
        const val PERMISSION_PREFS = "focuslock_onboarding"
    }
}
