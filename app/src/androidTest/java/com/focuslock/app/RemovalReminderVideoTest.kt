package com.focuslock.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.reminder.RemovalReminderActivity
import com.focuslock.app.reminder.RemovalReminderStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RemovalReminderVideoTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var targetContext: Context
    private lateinit var instrumentationContext: Context
    private lateinit var store: RemovalReminderStore
    private lateinit var backup: File
    private var hadPriorMedia = false
    private var priorName: String? = null
    private var priorMime: String? = null
    private var priorEnabled = false

    @Before
    fun preserveReminderSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        targetContext = instrumentation.targetContext
        instrumentationContext = instrumentation.context
        store = RemovalReminderStore(targetContext)
        backup = File(targetContext.cacheDir, "removal-reminder-video-restore.tmp")
        backup.delete()
        priorEnabled = store.enabled
        priorName = store.mediaName()
        priorMime = store.mediaMimeType()
        store.mediaFile()?.let { it.copyTo(backup, overwrite = true); hadPriorMedia = true }
    }

    @After
    fun restoreReminderSettings() = runBlocking<Unit> {
        store.clearMedia()
        if (hadPriorMedia && backup.isFile) {
            store.importMedia(Uri.fromFile(backup))
            targetContext.getSharedPreferences("removal_reminder_media", Context.MODE_PRIVATE)
                .edit().putString("display_name", priorName).putString("mime_type", priorMime).commit()
        }
        store.enabled = priorEnabled
        backup.delete()
        targetContext.cacheDir.resolve("reminder-test-video.mp4").delete()
    }

    @Test
    fun previewPlaysVideoSupportsPauseAndLifecycleResumeAndSavesDeviceScreenshot() = runBlocking<Unit> {
        val video = targetContext.cacheDir.resolve("reminder-test-video.mp4")
        instrumentationContext.assets.open("reminder-test-video.mp4").use { input ->
            video.outputStream().use { output -> input.copyTo(output) }
        }
        store.enabled = false
        store.importMedia(Uri.fromFile(video))
        assertTrue(store.mediaMimeType().orEmpty().startsWith("video/"))

        val scenario = ActivityScenario.launch<RemovalReminderActivity>(
            RemovalReminderActivity.intent(targetContext, RemovalReminderActivity.REASON_PREVIEW)
        )
        try {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithContentDescription("Pause reminder media")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("Pause reminder media").assertIsDisplayed()

            // Capture the actual display so the SurfaceView video frame is included.
            Thread.sleep(500)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull("UI automation should capture the rendered reminder screen", screenshot)
            val screenshotFile = File(targetContext.getExternalFilesDir(null), "removal-reminder-video.png")
            screenshotFile.parentFile?.mkdirs()
            screenshotFile.outputStream().use { output ->
                assertTrue(screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            assertTrue(screenshotFile.isFile && screenshotFile.length() > 0L)

            composeRule.onNodeWithContentDescription("Pause reminder media").performClick()
            composeRule.onNodeWithContentDescription("Play reminder media").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Play reminder media").performClick()
            composeRule.onNodeWithContentDescription("Pause reminder media").assertIsDisplayed()

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.onActivity { assertFalse("Playback must stop in the background", it.isReminderPlaying) }

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithContentDescription("Pause reminder media")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("Pause reminder media").assertIsDisplayed()
            composeRule.onNodeWithText("Close preview").performClick()
        } finally {
            scenario.close()
        }
    }
}
