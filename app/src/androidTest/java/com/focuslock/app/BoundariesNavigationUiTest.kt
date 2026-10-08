package com.focuslock.app

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.ui.apps.BoundariesScreen
import com.focuslock.app.ui.strict.StrictModeScreen
import com.focuslock.app.ui.theme.FocusLockTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoundariesNavigationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun boundariesHubOpensPermanentBlocksAndBackReturnsToHub() {
        compose.setContent { FocusLockTestSurface(fontScale = 1.5f) { BoundariesScreen() } }

        saveScreenshot("boundaries-hub")
        scrollToText("Apps")
        compose.onNodeWithText("Apps").assertIsDisplayed()
        scrollToText("Websites")
        compose.onNodeWithText("Websites").assertIsDisplayed()
        scrollToText("Permanent blocks")
        compose.onNodeWithText("Permanent blocks").assertIsDisplayed()
        scrollToText("Where blocking applies")
        compose.onNodeWithText("Where blocking applies").assertIsDisplayed()

        scrollToText("Permanent blocks")
        compose.onNodeWithText("Permanent blocks").performClick()
        compose.onNodeWithText("Block a website").performScrollTo().assertIsDisplayed()
        saveScreenshot("boundaries-permanent")

        compose.onNodeWithContentDescription("Back").performClick()
        scrollToText("Apps")
        compose.onNodeWithText("Apps").assertIsDisplayed()
        scrollToText("Permanent blocks")
        compose.onNodeWithText("Permanent blocks").assertIsDisplayed()
    }

    @Test
    fun boundariesHubOpensLocationAndBackReturnsToHub() {
        compose.setContent { FocusLockTestSurface(fontScale = 1.5f) { BoundariesScreen() } }

        scrollToText("Where blocking applies")
        compose.onNodeWithText("Where blocking applies").performClick()
        compose.onNodeWithText("Only block at home").assertIsDisplayed()
        saveScreenshot("boundaries-location")

        compose.onNodeWithContentDescription("Back").performClick()
        scrollToText("Apps")
        compose.onNodeWithText("Apps").assertIsDisplayed()
        scrollToText("Where blocking applies")
        compose.onNodeWithText("Where blocking applies").assertIsDisplayed()
    }

    @Test
    fun lockBoundaryChangesRowInvokesCallbackAtLargeFontScale() {
        var openedChanges = false
        compose.setContent {
            FocusLockTestSurface(fontScale = 1.5f) {
                BoundariesScreen(onOpenChanges = { openedChanges = true })
            }
        }

        scrollToText("Lock boundary changes")
        compose.onNodeWithText("Lock boundary changes").assertIsDisplayed().performClick()
        assertTrue("Lock boundary changes should open its supplied destination", openedChanges)
    }

    @Test
    fun strictModeKeepsHomeOnlyOutOfTheMainHierarchy() {
        compose.setContent { FocusLockTestSurface(fontScale = 1.5f) { StrictModeScreen() } }

        compose.onNodeWithText("Only block at home").assertDoesNotExist()
        compose.onNodeWithText("Manual", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Schedule", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Location", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("More about Strict Mode").performScrollTo()
        compose.onNodeWithText("More about Strict Mode").assertIsDisplayed()
        compose.waitForIdle()
        saveScreenshot("strict-hierarchy")
    }

    @Composable
    private fun FocusLockTestSurface(fontScale: Float, content: @Composable () -> Unit) {
        val currentDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(currentDensity.density, fontScale)
        ) {
            FocusLockTheme {
                Surface { content() }
            }
        }
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        context.filesDir.resolve("$name.png").outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }
}
