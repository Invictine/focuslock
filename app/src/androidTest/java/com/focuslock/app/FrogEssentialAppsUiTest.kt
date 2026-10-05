package com.focuslock.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.data.repository.FrogRepository
import com.focuslock.app.service.FrogAppPolicy
import com.focuslock.app.ui.blocker.FrogFocusScreen
import com.focuslock.app.ui.settings.FrogEssentialAppsScreen
import com.focuslock.app.ui.theme.FocusLockTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** All preference edits use a unique injected store, on the emulator only. */
@RunWith(AndroidJUnit4::class)
class FrogEssentialAppsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "frog-essentials-${UUID.randomUUID()}")
    private val job = SupervisorJob()
    private val store = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(job + Dispatchers.IO),
        produceFile = { File(directory, "choices.preferences_pb").apply { parentFile?.mkdirs() } },
    )
    private val repository = FrogRepository(context, store)

    @After fun cleanup(): Unit = runBlocking { job.cancelAndJoin(); directory.deleteRecursively(); Unit }

    @Test fun savedChoicesUpdateLauncherAndBlockingTogether() = runBlocking {
        repository.setWakeHour(0)
        repository.armIfDue()
        repository.selectFrog(FrogTask("study", "Study chemistry"))
        var selecting by mutableStateOf(true)
        compose.setContent {
            FocusLockTheme {
                if (selecting) FrogEssentialAppsScreen(onBack = { selecting = false }, repository = repository)
                else FrogFocusScreen(onOpenFocusLock = {}, onFrogComplete = {}, repository = repository)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Spotify") and isToggleable()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("TickTick") and isToggleable()).assertIsOn().assertIsNotEnabled()
        compose.onNode(hasText("Google Pay") and isToggleable()).performScrollTo().assertIsOn()
        compose.onNode(hasText("Spotify") and isToggleable()).performScrollTo().assertIsOn().performClick().assertIsOff()
        val search = compose.onNodeWithText("Search installed apps")
        search.performTextInput("Chrome")
        compose.onNode(hasText("Chrome") and isToggleable()).assertIsOff().performClick().assertIsOn()
        search.performTextClearance()
        search.performTextInput("Settings")
        compose.onNode(hasText("Settings") and isToggleable()).assertIsOn().assertIsNotEnabled()
        search.performTextClearance()
        search.performImeAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitForIdle()
        capture("frog-essential-apps-preview.png")
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Open FocusLock").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Chrome").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Spotify").assertDoesNotExist()
        compose.onNodeWithContentDescription("Google Pay").assertExists()
        compose.onNodeWithText("Open FocusLock").assertIsDisplayed()
        val state = repository.currentState()
        assertFalse(state.essentialAppPackages!!.contains("com.spotify.music"))
        assertTrue(state.essentialAppPackages!!.contains("com.android.chrome"))
        assertFalse(state.essentialAppPackages!!.contains("com.android.settings"))
        assertTrue(FrogAppPolicy.isBlocked(context, "com.spotify.music", state))
        assertFalse(FrogAppPolicy.isBlocked(context, "com.android.chrome", state))
        assertFalse(FrogAppPolicy.isBlocked(context, FrogAppPolicy.GPAY_PACKAGE, state))
        assertEquals(state.essentialAppPackages, FrogRepository(context, store).currentState().essentialAppPackages)
    }

    @Test fun leavingWithoutSavingKeepsPreviousChoices() = runBlocking {
        assertTrue(repository.setEssentialApps(emptySet()))
        var closed = false
        compose.setContent { FocusLockTheme { FrogEssentialAppsScreen(onBack = { closed = true }, repository = repository) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("WhatsApp") and isToggleable()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("WhatsApp") and isToggleable()).performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertTrue(closed) }
        assertEquals(emptySet<String>(), repository.currentState().essentialAppPackages)
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
