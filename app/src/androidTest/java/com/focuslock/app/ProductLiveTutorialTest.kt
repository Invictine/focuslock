package com.focuslock.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.focuslock.app.ui.onboarding.ProductLiveTutorial
import com.focuslock.app.ui.onboarding.ProductOnboardingStore
import com.focuslock.app.ui.onboarding.ProductTourDestination
import com.focuslock.app.ui.onboarding.liveTasks
import com.focuslock.app.ui.onboarding.productTourPages
import com.focuslock.app.ui.theme.FocusLockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductLiveTutorialTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val boundaryPage get() = productTourPages.first { it.id == "boundaries" }
    private val pageIndex get() = productTourPages.indexOf(boundaryPage)
    private val preferences get() = context.getSharedPreferences(PREFS_NAME, 0)

    @get:Rule val preferencesBackup = object : ExternalResource() {
        private var saved: Map<String, Any?> = emptyMap()

        override fun before() {
            saved = preferences.all.toMap()
            preferences.edit().clear().commit()
        }

        override fun after() {
            preferences.edit().clear().apply {
                saved.forEach { (key, value) ->
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
    fun instructionsAdvanceBackCollapseAndPersistCurrentStep() {
        val store = ProductOnboardingStore(context)
        store.saveTask("boundaries", 0)

        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                ProductLiveTutorial(pageIndex, onReview = {}, onPause = {}, onNext = {})
            }
        }

        compose.onNodeWithText(boundaryPage.liveTasks()[0]).assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText(boundaryPage.liveTasks()[1]).assertIsDisplayed()
        assertEquals(1, ProductOnboardingStore(context).taskIndex("boundaries"))

        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText(boundaryPage.liveTasks()[0]).assertIsDisplayed()
        assertEquals(0, ProductOnboardingStore(context).taskIndex("boundaries"))

        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Hide steps").performClick()
        compose.onNodeWithText(boundaryPage.liveTasks()[1]).assertDoesNotExist()
        compose.onNodeWithText("Show steps").performClick()
        compose.onNodeWithText(boundaryPage.liveTasks()[1]).assertIsDisplayed()
        assertEquals(1, ProductOnboardingStore(context).taskIndex("boundaries"))
    }

    @Test
    fun aPreviouslySavedStepIsShownAndFinalStepUsesNextFeatureCallback() {
        val store = ProductOnboardingStore(context)
        store.saveTask("boundaries", 1)
        var nextFeatureCalls = 0
        var reviewCalls = 0

        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                ProductLiveTutorial(
                    pageIndex = pageIndex,
                    onReview = { reviewCalls++ },
                    onPause = {},
                    onNext = { nextFeatureCalls++ },
                )
            }
        }

        compose.onNodeWithText(boundaryPage.liveTasks()[1]).assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText(boundaryPage.liveTasks()[2]).assertIsDisplayed()
        compose.onNodeWithText("Next feature").performClick()

        assertEquals(1, nextFeatureCalls)
        assertEquals(0, reviewCalls)
        assertEquals(2, ProductOnboardingStore(context).taskIndex("boundaries"))
    }

    @Test
    fun creditsStepsNavigateFocusThenPreferencesAndAdvanceOnlyAfterTheLastInstruction() {
        val creditsPage = productTourPages.first { it.id == "credits" }
        val tasks = creditsPage.liveTasks()
        ProductOnboardingStore(context).saveTask(creditsPage.id, 0)
        val destinations = mutableListOf<ProductTourDestination>()
        var nextFeatureCalls = 0

        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                ProductLiveTutorial(
                    pageIndex = productTourPages.indexOf(creditsPage),
                    onReview = {},
                    onPause = {},
                    onNext = { nextFeatureCalls++ },
                    onNavigate = { destinations.add(it) },
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText(tasks[0]).assertIsDisplayed()
        assertEquals(listOf(ProductTourDestination.FOCUS), destinations)

        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(tasks[1]).assertIsDisplayed()
        assertEquals(listOf(ProductTourDestination.FOCUS, ProductTourDestination.PREFERENCES), destinations)
        assertEquals(0, nextFeatureCalls)

        for (step in 2..tasks.lastIndex) {
            compose.onNodeWithText("Continue").performClick()
            compose.onNodeWithText(tasks[step]).assertIsDisplayed()
            assertEquals(0, nextFeatureCalls)
        }

        compose.onNodeWithText(tasks.last()).assertIsDisplayed()
        assertEquals(0, nextFeatureCalls)
        compose.onNodeWithText("Next feature").performClick()
        assertEquals(1, nextFeatureCalls)
    }

    private companion object {
        const val PREFS_NAME = "focuslock_product_onboarding"
    }
}
