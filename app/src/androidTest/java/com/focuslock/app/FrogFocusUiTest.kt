package com.focuslock.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.repository.FrogRepository
import com.focuslock.app.ui.blocker.FrogFocusScreen
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Render the Frog launcher against an isolated private DataStore, never production state. */
@RunWith(AndroidJUnit4::class)
class FrogFocusUiTest {
    @get:Rule val compose = createComposeRule()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val isolatedRoot = File(appContext.cacheDir, "frog-ui-${UUID.randomUUID()}")
    private val isolatedJob = SupervisorJob()
    private val isolatedScope = CoroutineScope(isolatedJob + Dispatchers.IO)
    private val isolatedDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = isolatedScope,
        produceFile = {
            File(isolatedRoot, "datastore/focuslock_frog.preferences_pb").apply {
                parentFile?.mkdirs()
            }
        },
    )
    private val repository by lazy { FrogRepository(appContext, isolatedDataStore) }

    @After
    fun cleanIsolatedStore(): Unit = runBlocking {
        isolatedJob.cancelAndJoin()
        isolatedRoot.deleteRecursively()
        Unit
    }

    @Test
    fun showsBlackLauncherDefaultIconsExtraToolsAndOpenFocusLock() = runBlocking {
        repository.setWakeHour(0)
        assertTrue(repository.armIfDue())
        repository.selectFrog(FrogTask(id = "chemistry", title = "Finish chemistry questions"))

        var openFocusLockCalls = 0
        compose.setContent {
            FrogFocusScreen(
                onOpenFocusLock = { openFocusLockCalls++ },
                onFrogComplete = {},
                onFrogEnded = {},
                repository = repository,
            )
        }

        compose.onNodeWithText("Finish your frog to unlock your phone.").assertExists()
        compose.onNodeWithText("Open FocusLock").assertExists()
        try { compose.waitUntil(10_000) {
            DEFAULT_ICON_DESCRIPTIONS.all { label ->
                compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
            }
        } } catch (error: Throwable) {
            throw AssertionError("Defaults: ${com.focuslock.app.service.FrogAppPolicy.defaultLaunchPackages(appContext)}\n${compose.onRoot().printToString()}", error)
        }

        val preview = compose.onRoot().captureToImage().asAndroidBitmap()
        val previewDirectory = appContext.getExternalFilesDir(null)
        checkNotNull(previewDirectory) { "App external files directory is unavailable" }
        File(previewDirectory, "frog-preview.png").outputStream().use { stream ->
            assertTrue(preview.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }

        assertTrue(repository.confirmTools(setOf("com.android.settings")))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Open FocusLock").performClick()
        compose.runOnIdle { assertEquals(1, openFocusLockCalls) }
    }

    @Test
    fun returnToAppCallbackDoesNotUnlockOrCompleteFrog() = runBlocking {
        repository.setWakeHour(0)
        assertTrue(repository.armIfDue())
        repository.selectFrog(FrogTask(id = "chemistry-return", title = "Finish chemistry questions"))

        var returnCalls = 0
        compose.setContent {
            FrogFocusScreen(
                onOpenFocusLock = {},
                onFrogComplete = {},
                onFrogEnded = {},
                repository = repository,
                returnAppLabel = "TickTick",
                onReturnToApp = { returnCalls++ },
            )
        }

        compose.onNodeWithText("Return to TickTick").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, returnCalls) }
        val stateAfterReturn = repository.currentState()
        assertTrue("Frog should remain locked", stateAfterReturn.locked)
        assertTrue("Frog should remain incomplete", stateAfterReturn.phase != FrogPhase.COMPLETE)
        assertTrue("Frog should remain unticked", !stateAfterReturn.tickedOff)
    }

    companion object {
        private val DEFAULT_ICON_DESCRIPTIONS = listOf("TickTick", "Phone", "Clock", "Messages", "WhatsApp", "ChatGPT", "Spotify", "Google Pay")
    }
}
