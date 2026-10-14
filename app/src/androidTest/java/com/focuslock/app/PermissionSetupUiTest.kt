package com.focuslock.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.ui.MainActivity
import com.focuslock.app.ui.onboarding.ProductOnboardingStore
import com.focuslock.app.ui.permissions.PermissionHelper
import java.io.FileInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionSetupUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun settingsSetUpReopensTheSameWizardAtItsSavedStep() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = FocusLockApplication.instance
        val settings = app.settingsRepository
        val oldOffline = runBlocking { settings.offlineModeFlow.first() }
        val permissionPrefs = context.getSharedPreferences(PERMISSION_PREFS, 0)
        val oldPermissionPrefs = permissionPrefs.all.toMap()
        val productPrefs = context.getSharedPreferences(PRODUCT_PREFS, 0)
        val oldProductPrefs = productPrefs.all.toMap()
        val usageMode = readAppOpMode("GET_USAGE_STATS")
        val overlayMode = readAppOpMode("SYSTEM_ALERT_WINDOW")
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            setAppOpMode("GET_USAGE_STATS", "deny")
            setAppOpMode("SYSTEM_ALERT_WINDOW", "deny")
            val missingCount = PermissionHelper.getMissingPermissions(context).size
            check(missingCount >= 2) { "Test fixture did not create two missing access steps: $missingCount" }

            runBlocking { settings.setOfflineMode(true) }
            ProductOnboardingStore(context).pause()
            permissionPrefs.edit().clear().putBoolean("permissions_skipped", true).commit()

            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Permissions & protection").performClick()
            compose.onNodeWithText("Set up permissions").performScrollTo().performClick()

            compose.onNodeWithText("Step 1 of $missingCount").assertIsDisplayed()
            compose.onNodeWithText("Skip this step").performClick()
            compose.onNodeWithText("Step 2 of $missingCount").assertIsDisplayed()

            scenario.recreate()
            compose.onNodeWithText("Step 2 of $missingCount").assertIsDisplayed()
            compose.onNode(isDialog()).performTouchInput { click(Offset(2f, 2f)) }
            compose.onNodeWithText("Step 2 of $missingCount").assertDoesNotExist()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Permissions & protection").performClick()
            compose.onNodeWithText("Set up permissions").performScrollTo().performClick()
            compose.onNodeWithText("Step 2 of $missingCount").assertIsDisplayed()
        } finally {
            scenario?.close()
            setAppOpMode("GET_USAGE_STATS", usageMode)
            setAppOpMode("SYSTEM_ALERT_WINDOW", overlayMode)
            restorePreferences(productPrefs, oldProductPrefs)
            restorePreferences(permissionPrefs, oldPermissionPrefs)
            runBlocking { settings.setOfflineMode(oldOffline) }
        }
    }

    private fun readAppOpMode(operation: String): String {
        val output = runShell("appops get ${InstrumentationRegistry.getInstrumentation().targetContext.packageName} $operation")
        return Regex("(?:^|\\s)${Regex.escape(operation)}:\\s*(allow|deny|ignore|default|foreground|ask)")
            .find(output)?.groupValues?.get(1)
            ?: error("Could not read original $operation mode: $output")
    }

    private fun setAppOpMode(operation: String, mode: String) {
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        runShell("appops set $packageName $operation $mode")
    }

    private fun runShell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
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
        const val PERMISSION_PREFS = "focuslock_onboarding"
        const val PRODUCT_PREFS = "focuslock_product_onboarding"
    }
}
