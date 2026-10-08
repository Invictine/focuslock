package com.focuslock.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.ui.onboarding.ProductOnboardingDialog
import com.focuslock.app.ui.onboarding.ProductTourDestination
import com.focuslock.app.ui.onboarding.productTourPages
import com.focuslock.app.ui.theme.FocusLockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductOnboardingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun continueBackAndTopicJumpOnlyChangeTheCurrentPage() {
        val page = mutableIntStateOf(0)
        var paused = false
        var finished = false
        var destination: ProductTourDestination? = null
        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                ProductOnboardingDialog(
                    pageIndex = page.intValue,
                    onPageChange = { page.intValue = it },
                    onPause = { paused = true },
                    onFinish = { finished = true },
                    onOpenDestination = { destination = it }
                )
            }
        }

        compose.onNodeWithText("FocusLock guide").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, page.intValue)
        compose.onNodeWithText("Back").performClick()
        assertEquals(0, page.intValue)

        compose.onNodeWithText("Topics").performClick()
        val targetIndex = productTourPages.lastIndex
        compose.onNodeWithText(
            "${targetIndex + 1}. ${productTourPages[targetIndex].title}", substring = true
        ).performScrollTo().performClick()
        assertEquals(targetIndex, page.intValue)
        assertTrue(!paused && !finished)
        assertEquals(null, destination)
    }

    @Test
    fun everyGuidePageCanBeReachedWithContinue() {
        val page = mutableIntStateOf(0)
        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                ProductOnboardingDialog(
                    pageIndex = page.intValue,
                    onPageChange = { page.intValue = it },
                    onPause = {},
                    onFinish = {},
                    onOpenDestination = {}
                )
            }
        }

        productTourPages.forEachIndexed { index, tourPage ->
            assertEquals(index, page.intValue)
            compose.onNodeWithText(tourPage.title).assertIsDisplayed()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            if (index < productTourPages.lastIndex) {
                compose.onNodeWithText("Continue").performClick()
            }
        }
        assertEquals(productTourPages.lastIndex, page.intValue)
    }

    @Test
    fun finishAndSaveForLaterUseSeparateCallbacks() {
        val page = mutableIntStateOf(productTourPages.lastIndex)
        var pausedCount = 0
        var finishedCount = 0
        var destination: ProductTourDestination? = null
        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                ProductOnboardingDialog(
                    pageIndex = page.intValue,
                    onPageChange = { page.intValue = it },
                    onPause = { pausedCount++ },
                    onFinish = { finishedCount++ },
                    onOpenDestination = { destination = it }
                )
            }
        }

        compose.onNodeWithText("Save for later").performClick()
        assertEquals(1, pausedCount)
        assertEquals(0, finishedCount)

        compose.onNodeWithText("Finish guide").performClick()
        assertEquals(1, finishedCount)
        assertEquals(1, pausedCount)
    }

    @Test
    fun destinationActionRoutesWithoutCompletingTheGuide() {
        val page = mutableIntStateOf(productTourPages.lastIndex)
        var finished = false
        var destination: ProductTourDestination? = null
        compose.setContent {
            FocusLockTheme(dynamicColor = false) {
                ProductOnboardingDialog(
                    pageIndex = page.intValue,
                    onPageChange = { page.intValue = it },
                    onPause = {},
                    onFinish = { finished = true },
                    onOpenDestination = { destination = it }
                )
            }
        }

        compose.onNodeWithText("Set up permissions").performScrollTo().performClick()
        assertEquals(ProductTourDestination.SETTINGS, destination)
        assertTrue(!finished)
    }

    @Test
    fun enlargedTextAndDarkThemeKeepDialogControlsReachable() {
        val page = mutableIntStateOf(productTourPages.lastIndex)
        var finished = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                FocusLockTheme(darkTheme = true, dynamicColor = false) {
                    ProductOnboardingDialog(
                        pageIndex = page.intValue,
                        onPageChange = { page.intValue = it },
                        onPause = {},
                        onFinish = { finished = true },
                        onOpenDestination = {}
                    )
                }
            }
        }

        compose.onNodeWithText("FocusLock guide").assertIsDisplayed()
        compose.onNodeWithText("Finish guide").assertIsDisplayed().performClick()
        assertTrue(finished)
    }

    @Test
    fun savesWelcomeAndFinalDialogScreenshotsInLightAndDarkEnlargedText() {
        val page = mutableIntStateOf(0)
        val dark = androidx.compose.runtime.mutableStateOf(false)
        val fontScale = androidx.compose.runtime.mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) {
                FocusLockTheme(darkTheme = dark.value, dynamicColor = false) {
                    ProductOnboardingDialog(
                        pageIndex = page.intValue,
                        onPageChange = { page.intValue = it },
                        onPause = {},
                        onFinish = {},
                        onOpenDestination = {}
                    )
                }
            }
        }

        saveDialogScreenshot("product-guide-welcome-light.png")
        page.intValue = productTourPages.lastIndex
        dark.value = true
        fontScale.floatValue = 1.5f
        compose.waitForIdle()
        saveDialogScreenshot("product-guide-final-dark-150.png")
    }

    private fun saveDialogScreenshot(fileName: String) {
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = context.filesDir.resolve(fileName)
        output.outputStream().use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue(output.length() > 0L)
    }
}
