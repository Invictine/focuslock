package com.focuslock.app

import android.graphics.Bitmap
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.reminder.RemovalReminderActivity
import com.focuslock.app.reminder.RemovalReminderController
import com.focuslock.app.reminder.RemovalReminderStore
import com.focuslock.app.service.RemovalAttemptPolicy
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking

/** Device smoke check for the reminder preview, including a saved review screenshot. */
@RunWith(AndroidJUnit4::class)
class RemovalReminderUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun deactivateFirstSettingsAlertFlowsThroughPolicyAndController() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = RemovalReminderStore(context)
        val wasEnabled = store.enabled
        val policyPrefs = context.getSharedPreferences("removal_reminder_launch_policy", Context.MODE_PRIVATE)
        val policyBackup = policyPrefs.all.toMap()
        val automation = instrumentation.uiAutomation
        var launchRequested = false
        try {
            store.enabled = true
            policyPrefs.edit().clear().commit()
            val reason = RemovalAttemptPolicy.reason(
                packageName = "com.android.settings",
                windowClassName = "android.app.Dialog",
                texts = listOf("FocusLock", "Deactivate first", "Cancel"),
                deviceAdminActive = true,
            )
            assertEquals(RemovalAttemptPolicy.REASON_ADMIN_REQUESTED, reason)

            // Some OEMs expose this same modal as the App info activity's root class.
            assertEquals(
                RemovalAttemptPolicy.REASON_ADMIN_REQUESTED,
                RemovalAttemptPolicy.reason(
                    packageName = "com.android.settings",
                    windowClassName = "com.android.settings.applications.InstalledAppDetailsTop",
                    texts = listOf("FocusLock", "Deactivate first", "Cancel"),
                    deviceAdminActive = true,
                ),
            )

            // Wait out a reminder dismissed by an earlier test in this process.
            SystemClock.sleep(1_600)
            assertTrue(
                "The production launch controller should accept the detected removal reason",
                RemovalReminderController.show(context, reason!!, fromForeground = true),
            )
            launchRequested = true
            awaitVisibleText(automation, "Do you really want to do this?")
            awaitVisibleText(automation, "Keep FocusLock")
        } finally {
            val currentRoot = automation.rootInActiveWindow
            val reminderOwnsForeground = try {
                currentRoot?.packageName?.toString() == context.packageName
            } finally { currentRoot?.recycle() }
            if (launchRequested && reminderOwnsForeground) {
                automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            }
            store.enabled = wasEnabled
            val restore = policyPrefs.edit().clear()
            policyBackup.forEach { (key, value) ->
                when (value) {
                    is Boolean -> restore.putBoolean(key, value)
                    is Int -> restore.putInt(key, value)
                    is Long -> restore.putLong(key, value)
                    is String -> restore.putString(key, value)
                    is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            restore.commit()
        }
    }

    @Test
    fun previewShowsDialogAndCloseActionAndSavesScreenshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adminBefore = isDeviceAdminActive(context)
        val scenario = ActivityScenario.launch<RemovalReminderActivity>(
            RemovalReminderActivity.intent(context, "preview")
        )
        try {
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Do you really want to do this?").assertIsDisplayed()
            val directory = context.getExternalFilesDir(null) ?: context.filesDir
            val screenshot = directory.resolve("removal-reminder-preview.png")
            screenshot.outputStream().use { output ->
                assertTrue(
                    composeRule.onRoot().captureToImage().asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, output)
                )
            }
            assertTrue(screenshot.length() > 0L)

            composeRule.onNodeWithText("Close preview").assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) { scenario.state == Lifecycle.State.DESTROYED }
            assertTrue(adminBefore == isDeviceAdminActive(context))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun previewAutoplaysMediaWhileReminderIsDisabledAndControlsWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RemovalReminderStore(context)
        val preferences = context.getSharedPreferences("removal_reminder_media", Context.MODE_PRIVATE)
        val priorEnabled = store.enabled
        val priorMime = store.mediaMimeType()
        val priorName = store.mediaName()
        val priorFile = store.mediaFile()
        val backup = priorFile?.let { source ->
            File.createTempFile("reminder-media-backup", ".bin", context.cacheDir).also { target ->
                source.copyTo(target, overwrite = true)
            }
        }
        val fixture = File.createTempFile("reminder-preview", ".wav", context.cacheDir)
        val adminBefore = isDeviceAdminActive(context)
        var scenario: ActivityScenario<RemovalReminderActivity>? = null
        try {
            writeShortWave(fixture)
            store.enabled = false
            assertFalse(store.enabled)
            runBlocking { store.importMedia(Uri.fromFile(fixture)) }
            val activeScenario = ActivityScenario.launch<RemovalReminderActivity>(
                RemovalReminderActivity.intent(context, "preview")
            )
            scenario = activeScenario
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithContentDescription("Pause reminder media")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Do you really want to do this?").assertIsDisplayed()
            composeRule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                val directory = context.getExternalFilesDir(null) ?: context.filesDir
                val screenshot = directory.resolve("removal-reminder-preview-audio.png")
                screenshot.outputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                assertTrue(screenshot.length() > 0L)
            }

            composeRule.onAllNodesWithContentDescription("Pause reminder media").onFirst().performClick()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithContentDescription("Play reminder media").onFirst().assertIsDisplayed()

            composeRule.onAllNodesWithContentDescription("Replay reminder media").onFirst().performClick()
            composeRule.waitUntil(timeoutMillis = 2_000) {
                composeRule.onAllNodesWithContentDescription("Pause reminder media")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithContentDescription("Pause reminder media").onFirst().assertIsDisplayed()

            composeRule.onNodeWithText("Close preview").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) { activeScenario.state == Lifecycle.State.DESTROYED }
            assertTrue(adminBefore == isDeviceAdminActive(context))
        } finally {
            scenario?.close()
            try {
                store.clearMedia()
                if (backup != null) {
                    runBlocking { store.importMedia(Uri.fromFile(backup)) }
                    preferences.edit()
                        .putString("mime_type", priorMime)
                        .putString("display_name", priorName)
                        .commit()
                }
            } finally {
                store.enabled = priorEnabled
                backup?.delete()
                fixture.delete()
            }
        }
    }

    private fun isDeviceAdminActive(context: Context): Boolean {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val receiver = android.content.ComponentName(context, com.focuslock.app.service.FocusLockDeviceAdminReceiver::class.java)
        return manager.isAdminActive(receiver)
    }

    private fun awaitVisibleText(automation: android.app.UiAutomation, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val found = if (root == null) false else try {
                fun contains(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
                    if (node.text?.toString() == expected || node.contentDescription?.toString() == expected) return true
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        try { if (contains(child)) return true }
                        finally { child.recycle() }
                    }
                    return false
                }
                contains(root)
            } finally { root.recycle() }
            if (found) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for reminder text '$expected'")
    }

    private fun writeShortWave(file: File) {
        val sampleRate = 8_000
        val sampleCount = sampleRate * 6
        val dataBytes = sampleCount * 2
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.writeBytes("RIFF")
            output.writeLittleEndian(dataBytes + 36, 4)
            output.writeBytes("WAVEfmt ")
            output.writeLittleEndian(16, 4)
            output.writeLittleEndian(1, 2)
            output.writeLittleEndian(1, 2)
            output.writeLittleEndian(sampleRate, 4)
            output.writeLittleEndian(sampleRate * 2, 4)
            output.writeLittleEndian(2, 2)
            output.writeLittleEndian(16, 2)
            output.writeBytes("data")
            output.writeLittleEndian(dataBytes, 4)
            repeat(sampleCount) { index ->
                val value = (8_000 * kotlin.math.sin(2.0 * Math.PI * 440.0 * index / sampleRate)).toInt()
                output.writeLittleEndian(value and 0xffff, 2)
            }
        }
    }

    private fun DataOutputStream.writeLittleEndian(value: Int, byteCount: Int) {
        repeat(byteCount) { shift -> write((value ushr (8 * shift)) and 0xff) }
    }
}
