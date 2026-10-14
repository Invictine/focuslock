package com.focuslock.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.reminder.RemovalReminderStore
import com.focuslock.app.service.AppMonitorAccessibilityService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Settings integration check. Run only on a disposable emulator with admin enabled. */
@RunWith(AndroidJUnit4::class)
class RemovalReminderSystemTest {
    private lateinit var automation: android.app.UiAutomation

    @Test
    fun deviceAdminAndUninstallFlowsShowReminderAndPreserveNativeProtection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val admin = ComponentName(context, com.focuslock.app.service.FocusLockDeviceAdminReceiver::class.java)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        assumeTrue("This scenario requires the user-approved FocusLock admin", dpm.isAdminActive(admin))

        // Instrumentation suppresses third-party services by default. This flag is essential:
        // the production accessibility monitor must stay connected while native Settings opens.
        automation = instrumentation.getUiAutomation(
            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        )
        val serviceId = adminServiceId(context)
        val oldServiceSetting = setting("settings get secure enabled_accessibility_services")
        val oldAccessibilityEnabled = setting("settings get secure accessibility_enabled")
        val store = RemovalReminderStore(context)
        val wasReminderEnabled = store.enabled
        val policyPrefs = context.getSharedPreferences("removal_reminder_launch_policy", Context.MODE_PRIVATE)
        val policyBackup = policyPrefs.all.toMap()
        val frog = FocusLockApplication.instance.frogRepository
        val wasFrogEnabled = runBlocking { frog.enabledFlow.first() }

        try {
            // This test exercises removal, not daily Home enforcement. Preserve all
            // task/progress state and restore only the device's enabled flag afterward.
            runBlocking { frog.setEnabled(false) }
            store.enabled = true
            policyPrefs.edit().clear().commit()
            enableService(serviceId, oldServiceSetting)
            await("FocusLock accessibility service bound", 12_000) {
                // Configured/enabled is not proof of binding after instrumentation
                // force-stops the target. This job starts only in onServiceConnected.
                shell("dumpsys activity service ${context.packageName}/.service.AppMonitorAccessibilityService")
                    .contains("policyBoundary=true")
            }

            // App info itself is intentionally quiet. Selecting Uninstall leads Android to
            // DeviceAdminAdd; the live Settings observer must launch the reminder over that
            // native confirmation step before the administrator state can change.
            // A prior canceled test can leave a pending admin activity in Settings' task.
            // Use a fresh Settings task for the ordinary App info negative case.
            shell("am force-stop com.android.settings")
            openAppInfo(context)
            awaitText("Uninstall", 10_000)
            SystemClock.sleep(1_000)
            assertFalse("Ordinary App info must not show the reminder before a removal action",
                visibleTexts().any { it == "Do you really want to do this?" })
            clickText("Uninstall")
            awaitText("Do you really want to do this?", 12_000)
            assertTextVisible("Android is asking whether to turn off FocusLock's uninstall protection")
            saveScreenshot(context, "app-info-admin-reminder.png")
            assertTrue("The reminder must not deactivate admin", dpm.isAdminActive(admin))
            clickText("Keep FocusLock")
            await("Keep returns away from removal", 8_000) {
                val focus = activeWindowClass()
                focus.isNotBlank() && !focus.contains("RemovalReminderActivity") && !focus.contains("com.android.settings")
            }
            SystemClock.sleep(250)
            // Keep returns to Home. Starting the same native removal attempt again should
            // show the reminder again immediately rather than leaving a stale dismissal delay.
            openAppInfo(context)
            await("App info or resumed removal reminder", 10_000) {
                visibleTexts().any { it == "Do you really want to do this?" } ||
                    findNode { it.equals("Uninstall", true) } != null
            }
            // Android can resume the still-pending admin screen in the Settings task
            // before the App info trampoline runs. That must also show the reminder.
            if (visibleTexts().none { it == "Do you really want to do this?" }) {
                clickText("Uninstall")
            }
            awaitText("Do you really want to do this?", 12_000)
            assertTextVisible("Android is asking whether to turn off FocusLock's uninstall protection")
            assertTrue("Repeated reminder must leave admin active", dpm.isAdminActive(admin))
            clickText("Continue")
            awaitCurrentFocus("DeviceAdminAdd", 10_000)
            assertTrue("Continue returns to Android's native admin screen", dpm.isAdminActive(admin))

            // Disable only the accessibility observer, preserving all other installed services.
            // Android's native warning must still protect the user when the observer is absent.
            // The uninstall route can combine removal with administrator deactivation on
            // some Android versions. Open the dedicated administrator list and select
            // FocusLock there so this action exercises onDisableRequested independently.
            shell("input keyevent KEYCODE_HOME")
            await("left the previous admin screen", 8_000) {
                val focus = activeWindowClass()
                focus.isNotBlank() && !focus.contains("DeviceAdminAdd") && !focus.contains("RemovalReminderActivity")
            }
            shell("am force-stop com.android.settings")
            policyPrefs.edit().clear().commit()
            openDeviceAdminList(context)
            awaitCurrentFocus("DeviceAdminSettingsActivity", 10_000)
            await("FocusLock admin list entry", 8_000) {
                findNode { it.equals("FocusLock", true) } != null
            }
            clickTextContaining("FocusLock")
            awaitText("Do you really want to do this?", 12_000)
            assertTextVisible("Android is asking whether to turn off FocusLock's uninstall protection")
            saveScreenshot(context, "device-admin-reminder.png")
            assertTrue("Direct admin settings must show the reminder before deactivation", dpm.isAdminActive(admin))
            clickText("Continue")
            awaitCurrentFocus("DeviceAdminAdd", 10_000)
            disableService(serviceId, oldServiceSetting)
            await("FocusLock accessibility service disabled", 8_000) {
                shell("dumpsys accessibility").lineSequence().filter { it.contains("Bound services:") }
                    .none { it.contains("label=FocusLock,") }
            }
            clickNativeAdminAction()
            awaitText("Do you really want to do this?", 8_000)
            val nativeWarning = visibleTexts().joinToString(" ")
            assertTrue("Android must show the receiver's native disable warning even without accessibility: $nativeWarning",
                nativeWarning.contains("Disabling device admin lets you uninstall FocusLock", ignoreCase = true))
            // Cancel the platform confirmation; this test never removes the active admin.
            clickFirstAvailable("Cancel", "No")
            assertTrue("Cancel leaves device admin active", dpm.isAdminActive(admin))

            assertTrue("Uninstall route must leave admin active", dpm.isAdminActive(admin))
        } finally {
            // Restore the exact pre-test user settings and transient suppression state.
            store.enabled = wasReminderEnabled
            val restorePolicy = policyPrefs.edit().clear()
            policyBackup.forEach { (key, value) ->
                when (value) {
                    is Boolean -> restorePolicy.putBoolean(key, value)
                    is Int -> restorePolicy.putInt(key, value)
                    is Long -> restorePolicy.putLong(key, value)
                    is String -> restorePolicy.putString(key, value)
                    is Set<*> -> restorePolicy.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            restorePolicy.commit()
            restoreSetting("settings put secure enabled_accessibility_services", oldServiceSetting)
            restoreSetting("settings put secure accessibility_enabled", oldAccessibilityEnabled)
            automation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            runBlocking { frog.setEnabled(wasFrogEnabled) }
        }
    }

    private fun openAppInfo(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openDeviceAdminList(context: Context) {
        context.startActivity(Intent().setClassName("com.android.settings",
            "com.android.settings.Settings\$DeviceAdminSettingsActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun clickNativeAdminAction() {
        val node = findNode { text -> text.equals("Deactivate", true) ||
            text.equals("Deactivate this device admin app", true) }
        assertNotNull("DeviceAdminAdd should expose its Deactivate action", node)
        node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickText(text: String) {
        await("clickable '$text'", 5_000) { findNode { it.equals(text, true) } != null }
        val node = findNode { it.equals(text, ignoreCase = true) }
        assertNotNull("Expected clickable text '$text'; visible=${visibleTexts()}", node)
        assertTrue("Could not click '$text'", node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun clickTextContaining(text: String) {
        val node = findNode { it.contains(text, ignoreCase = true) }
        assertNotNull("Expected clickable text containing '$text'; visible=${visibleTexts()}", node)
        assertTrue("Could not click '$text'", node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun clickFirstAvailable(vararg options: String) {
        val node = findNode { candidate -> options.any { candidate.equals(it, true) } }
        assertNotNull("Expected one of ${options.toList()}", node)
        node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun assertTextVisible(text: String) = awaitText(text, 8_000)

    private fun saveScreenshot(context: Context, name: String) {
        val screenshot = automation.takeScreenshot()
        assertNotNull("Expected a Settings-flow screenshot", screenshot)
        val directory = java.io.File(context.getExternalFilesDir(null), "removal-verification").apply { mkdirs() }
        java.io.File(directory, name).outputStream().use {
            assertTrue(screenshot!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot!!.recycle()
    }

    private fun awaitText(text: String, timeoutMs: Long) = await("text '$text'", timeoutMs) {
        visibleTexts().any { it.contains(text, true) }
    }

    private fun awaitCurrentFocus(name: String, timeoutMs: Long) = await("focused window $name", timeoutMs) {
        activeWindowClass().contains(name, true) &&
            automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
    }

    private fun await(description: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        assertTrue("Timed out waiting for $description; class=${activeWindowClass()}, visible=${visibleTexts()}", condition())
    }

    private fun activeWindowClass(): String =
        shell("dumpsys window").lineSequence()
            .filter { it.contains("mCurrentFocus") }.joinToString(" ").trim()

    private fun visibleTexts(): List<String> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return result
    }

    private fun findNode(predicate: (String) -> Boolean): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val label = node.text?.toString() ?: node.contentDescription?.toString()
            if (label != null && predicate(label)) {
                var clickable: AccessibilityNodeInfo? = node
                repeat(5) {
                    if (clickable?.isClickable == true) return clickable
                    clickable = clickable?.parent
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                visit(child)?.let { return it }
            }
            return null
        }
        return visit(root)
    }

    private fun adminServiceId(context: Context) =
        ComponentName(context, AppMonitorAccessibilityService::class.java).flattenToString()

    private fun setting(command: String): String? = shell(command).trim().takeUnless { it.isBlank() || it == "null" }

    private fun enableService(serviceId: String, previous: String?) {
        val others = previous.orEmpty().split(':').filter { it.isNotBlank() && it != serviceId }
        // Replacing an identical setting does not recover a crashed/dead binding
        // left by the test runner. Toggle only this component before re-enabling it.
        disableService(serviceId, previous)
        SystemClock.sleep(400)
        shell("settings put secure enabled_accessibility_services ${(others + serviceId).joinToString(":")}")
        shell("settings put secure accessibility_enabled 1")
    }

    private fun disableService(serviceId: String, previous: String?) {
        val remaining = previous.orEmpty().split(':').filter { it.isNotBlank() && it != serviceId }
        if (remaining.isEmpty()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services ${remaining.joinToString(":")}")
    }

    private fun restoreSetting(command: String, value: String?) {
        if (value == null) shell(command.substringBefore(" put ") + " delete secure " + command.substringAfter("secure ").substringBefore(' '))
        else shell("$command $value")
    }

    private fun shell(command: String): String {
        val descriptor = automation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

}
