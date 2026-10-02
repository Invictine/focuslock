package com.focuslock.app

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.ui.apps.BoundariesScreen
import com.focuslock.app.data.model.BlockedWebsite
import com.focuslock.app.ui.permalock.PermalockScreen
import com.focuslock.app.ui.strict.StrictModeScreen
import com.focuslock.app.ui.theme.FocusLockTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Run on a disposable emulator: exercises real repositories and irreversible fixture blocks. */
@RunWith(AndroidJUnit4::class)
class PermaLockUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun uninstalledAppStaysInPermaLockAndLeavesBoundaries() {
        val app = FocusLockApplication.instance
        val packageName = "com.focuslock.fixture.${UUID.randomUUID().toString().replace("-", "")}"
        val label = "Retained fixture ${UUID.randomUUID().toString().take(6)}"
        val domain = "retained-${UUID.randomUUID()}.example.com"
        runBlocking {
            app.permanentBlocksRepository.add(packageName, label)
            app.settingsRepository.setAppPermanent(packageName, true)
            app.permanentBlocksRepository.addWebsite(domain)
            app.settingsRepository.setWebsitePermanent(domain, true)
        }
        val screen = mutableStateOf("boundaries")
        compose.setContent {
            FocusLockTheme {
                Surface {
                    if (screen.value == "boundaries") BoundariesScreen() else PermalockScreen()
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(label).assertDoesNotExist()
        compose.onNodeWithText(domain).assertDoesNotExist()
        compose.runOnIdle { screen.value = "permalock" }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText(packageName).assertIsDisplayed()
    }

    @Test
    fun permanentTargetsSurviveOrdinaryBoundaryReplacement() = runBlocking {
        val app = FocusLockApplication.instance
        val packageName = "com.focuslock.retained.${UUID.randomUUID()}"
        val domain = "retained-${UUID.randomUUID()}.example.com"
        app.permanentBlocksRepository.add(packageName, "Durable name")
        app.permanentBlocksRepository.addWebsite(domain)
        // Remove these targets from the settings mirror, as a normal list restore
        // or app inventory refresh can do. The independent commitments must survive.
        app.settingsRepository.updateBlockedApps(emptyList())
        app.settingsRepository.updateBlockedWebsites(emptyList<BlockedWebsite>())
        val recreated = com.focuslock.app.data.repository.PermanentBlocksRepository(app)
        recreated.warm()
        org.junit.Assert.assertTrue(recreated.isPermanentlyBlocked(packageName))
        org.junit.Assert.assertTrue(recreated.isPermanentlyBlockedDomain("sub.$domain"))
    }

    @Test
    fun websiteConfirmationAddsPermanentEntry() {
        val domain = "fixture-${UUID.randomUUID()}.example.com"
        compose.setContent { FocusLockTheme { Surface { PermalockScreen() } } }
        compose.onNodeWithText("Block a website").performScrollTo().performClick()
        compose.onNodeWithText("Website or URL").performTextInput("https://$domain/path")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Permanently block $domain?").assertIsDisplayed()
        compose.onNodeWithText("Block permanently").performClick()
        compose.waitUntil(10_000) {
            FocusLockApplication.instance.permanentBlocksRepository.isPermanentlyBlockedDomain(domain)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Block permanently").fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(domain))
        compose.onNodeWithText(domain).assertIsDisplayed()
        saveScreenshot("permalock-websites")
    }

    @Test
    fun strictSwitcherShowsOneActivationPanelAtATime() {
        compose.setContent { FocusLockTheme { Surface { StrictModeScreen() } } }
        compose.onNodeWithText("Commitment style").assertExists()
        compose.onNodeWithText("Schedule", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Activate on a schedule").assertIsDisplayed()
        compose.onNodeWithText("Activate at a place").assertDoesNotExist()
        compose.onNodeWithText("Commitment style").assertDoesNotExist()
        saveScreenshot("strict-schedule")
        compose.onNodeWithText("Location", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Activate at a place").assertIsDisplayed()
        compose.onNodeWithText("Activate on a schedule").assertDoesNotExist()
        saveScreenshot("strict-location")
        compose.onNodeWithText("Manual", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Commitment style").assertExists()
        saveScreenshot("strict-manual")
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        context.filesDir.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
