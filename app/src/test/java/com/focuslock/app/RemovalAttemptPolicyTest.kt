package com.focuslock.app

import com.focuslock.app.service.RemovalAttemptPolicy
import com.focuslock.app.service.RemovalWindowSelection
import com.focuslock.app.reminder.RemovalLaunchAcknowledgment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalAttemptPolicyTest {
    @Test
    fun clickedSettingsWindowWinsOverNewActiveModalWindow() {
        data class Window(val id: Int, val label: String)
        val exposedWindows = listOf(Window(14, "App info"), Window(27, "Deactivate first dialog"))

        assertTrue(
            RemovalWindowSelection.clickedWindow(exposedWindows, 14) { it.id }?.label == "App info"
        )
        assertTrue(RemovalWindowSelection.clickedWindow(exposedWindows, 99) { it.id } == null)
        assertTrue(RemovalWindowSelection.clickedWindow(exposedWindows, null) { it.id } == null)
    }

    @Test
    fun backgroundLaunchRequiresActivityResumeAcknowledgmentOrCurrentFallback() {
        assertTrue(RemovalLaunchAcknowledgment.shouldPostFallback(4, 3, 4))
        assertFalse(RemovalLaunchAcknowledgment.shouldPostFallback(4, 4, 4))
        assertFalse(RemovalLaunchAcknowledgment.shouldPostFallback(4, 3, 5))
    }

    @Test
    fun clickedSettingsRemovalActionRequiresLiveFocusLockIdentityAndActiveAdmin() {
        val root = listOf("FocusLock", "Open", "Uninstall", "Force stop")
        assertTrue(
            RemovalAttemptPolicy.clickedActionReason(
                "com.android.settings", root, listOf("Uninstall"), deviceAdminActive = true,
            ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
        assertTrue(
            RemovalAttemptPolicy.clickedActionReason(
                "com.android.settings", root, listOf("Open"), deviceAdminActive = true,
            ) == null
        )
        assertTrue(
            RemovalAttemptPolicy.clickedActionReason(
                "com.android.settings", listOf("FocusLock Helper"), listOf("Uninstall"),
                deviceAdminActive = true,
            ) == null
        )
        assertTrue(
            RemovalAttemptPolicy.clickedActionReason(
                "com.android.settings", root, listOf("Uninstall"), deviceAdminActive = false,
            ) == null
        )
        assertTrue(
            RemovalAttemptPolicy.clickedActionReason(
                "com.android.chrome", root, listOf("Uninstall"), deviceAdminActive = true,
            ) == null
        )
    }

    @Test
    fun clickedAdminDeactivationActionsAreRecognizedWithoutBroadAppInfoMatching() {
        assertTrue(RemovalAttemptPolicy.isPossibleRemovalActionClick(listOf("Deactivate")))
        assertTrue(RemovalAttemptPolicy.isPossibleRemovalActionClick(listOf("Deactivate & uninstall")))
        assertFalse(RemovalAttemptPolicy.isPossibleRemovalActionClick(listOf("Open")))
        assertFalse(RemovalAttemptPolicy.isPossibleRemovalActionClick(listOf("Force stop")))
    }

    @Test
    fun matchesKnownInstallerUninstallActivityWithLocalizedConfirmation() {
        assertTrue(
            RemovalAttemptPolicy.matches(
                packageName = "com.google.android.packageinstaller",
                windowClassName = "com.android.packageinstaller.UninstallAppProgress",
                texts = listOf("FocusLock", "Apkaarein"),
            )
        )
    }

    @Test
    fun matchesExactEnglishUninstallActionForExactTargetTitle() {
        assertTrue(
            RemovalAttemptPolicy.matches(
                packageName = "com.samsung.android.packageinstaller",
                windowClassName = "com.samsung.android.packageinstaller.PackageInstallerActivity",
                texts = listOf("FocusLock", "Uninstall"),
            )
        )
    }

    @Test
    fun matchesExactPackageTargetAndExplicitConfirmation() {
        assertTrue(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.permissioncontroller",
                windowClassName = "com.android.permissioncontroller.permission.ui.AppPermissionActivity",
                texts = listOf("com.focuslock.app", "Do you want to uninstall FocusLock?"),
            )
        )
    }

    @Test
    fun matchesInstallerConfirmationWithGenericOkButton() {
        assertTrue(
            RemovalAttemptPolicy.matches(
                packageName = "com.google.android.packageinstaller",
                windowClassName = "android.app.AlertDialog",
                texts = listOf("FocusLock", "Do you want to uninstall this app?", "Cancel", "OK"),
            )
        )
    }

    @Test
    fun matchesSettingsHostedConfirmationQuestionForExactTarget() {
        assertTrue(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = listOf("FocusLock", "Are you sure you want to uninstall this app?", "Cancel", "Uninstall"),
            )
        )
    }

    @Test
    fun matchesSettingsQuestionThatNamesExactTargetWithoutSeparateTitleNode() {
        assertTrue(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = listOf("Do you want to uninstall FocusLock?", "Cancel", "Uninstall"),
            )
        )
    }

    @Test
    fun matchesDeviceAdminAddDeactivateActionForFocusLock() {
        assertTrue(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.DeviceAdminAdd",
                texts = listOf("FocusLock", "Deactivate", "This app may perform actions"),
            )
        )
        assertTrue(
            RemovalAttemptPolicy.reason(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.DeviceAdminAdd",
                texts = listOf("FocusLock", "Deactivate"),
            ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
        for (action in listOf("Deactivate & uninstall", "Deactivate and uninstall")) {
            assertTrue(
                RemovalAttemptPolicy.reason(
                    packageName = "com.android.settings",
                    windowClassName = "com.android.settings\$DeviceAdminAdd",
                    texts = listOf("FocusLock", action),
                ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
            )
        }
        assertTrue(
            RemovalAttemptPolicy.reason(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.DeviceAdminAdd",
                texts = listOf("FocusLock uninstall protection", "Deactivate & uninstall"),
            ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
    }

    @Test
    fun matchesDeviceAdminAddWithHiddenDeactivateActionOnlyWhenFocusLockAdminIsActive() {
        val packageName = "com.android.settings"
        val windowClass = "com.android.settings.DeviceAdminAdd"
        val texts = listOf("FocusLock", "This app is an administrator")

        assertTrue(
            RemovalAttemptPolicy.reason(packageName, windowClass, texts, deviceAdminActive = true) ==
                RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
        assertFalse(RemovalAttemptPolicy.matches(packageName, windowClass, texts))
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName,
                windowClass,
                listOf("FocusLock Helper", "This app is an administrator"),
                deviceAdminActive = true,
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName,
                windowClass,
                listOf("FocusLock", "Activate this device admin app"),
                deviceAdminActive = true,
            )
        )
    }

    @Test
    fun matchesSamsungActiveAdminDialogWhenDeactivateButtonIsNotExposed() {
        val observedTexts = listOf(
            "Device admin app",
            "FocusLock",
            "This admin app is active and allows FocusLock to perform the following actions:",
            "Lock the screen",
            "Cancel",
        )
        assertTrue(
            RemovalAttemptPolicy.reason(
                "com.android.settings", "androidx.appcompat.app.AlertDialog",
                observedTexts, deviceAdminActive = true,
            ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
        assertTrue(
            RemovalAttemptPolicy.reason(
                "com.android.settings",
                "com.samsung.android.settings.applications.specialaccess.SecDeviceAdminAdd",
                observedTexts, deviceAdminActive = true,
            ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                "com.android.settings", "androidx.appcompat.app.AlertDialog",
                listOf("Device admin app", "Other App", "This admin app is active and allows Other App to perform the following actions:"),
                deviceAdminActive = true,
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                "com.android.settings", "com.samsung.android.settings.applications.specialaccess.SecDeviceAdminAdd",
                listOf("FocusLock", "Activate this device admin app"), deviceAdminActive = true,
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                "com.android.settings", "com.android.settings.DeviceAdminSettings",
                listOf("Device admin apps", "FocusLock", "Other App"), deviceAdminActive = true,
            )
        )
    }

    @Test
    fun matchesExplicitFocusLockAdminDeactivationFromAppInfoOrSettingsDialog() {
        assertTrue(
            RemovalAttemptPolicy.reason(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = listOf("FocusLock", "Deactivate this device admin app"),
            ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
        assertTrue(
            RemovalAttemptPolicy.reason(
                packageName = "com.android.settings",
                windowClassName = "android.widget.FrameLayout",
                texts = listOf("FocusLock", "Deactivate this device admin app"),
            ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
        assertTrue(
            RemovalAttemptPolicy.reason(
                packageName = "com.android.settings",
                windowClassName = "android.app.AlertDialog",
                texts = listOf("FocusLock", "Do you want to deactivate this device admin app?", "Cancel", "Deactivate"),
            ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
        assertTrue(
            RemovalAttemptPolicy.reason(
                packageName = "com.android.settings",
                windowClassName = "android.app.AlertDialog",
                texts = listOf("Do you want to deactivate device admin for FocusLock?", "Cancel", "OK"),
            ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
    }

    @Test
    fun matchesOemAppInfoUninstallDialogThatRequiresDeactivationFirst() {
        assertTrue(
            RemovalAttemptPolicy.reason(
                packageName = "com.android.settings",
                windowClassName = "android.app.AlertDialog",
                texts = listOf("FocusLock", "Deactivate first", "Cancel"),
                deviceAdminActive = true,
            ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
        assertTrue(
            RemovalAttemptPolicy.reason(
                packageName = "com.android.settings",
                // OEM builds can report the app-info root class even while showing a modal.
                windowClassName = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = listOf("FocusLock uninstall protection", "Turn off device admin first", "Cancel"),
                deviceAdminActive = true,
            ) == RemovalAttemptPolicy.REASON_ADMIN_REQUESTED
        )
    }

    @Test
    fun ignoresDeactivateFirstUnlessFocusLockAdminConfirmationIsActive() {
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "android.app.AlertDialog",
                texts = listOf("FocusLock", "Deactivate first", "Cancel"),
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = listOf("FocusLock", "Uninstall"),
                deviceAdminActive = true,
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "android.app.AlertDialog",
                texts = listOf("Other App", "Deactivate first", "Cancel"),
                deviceAdminActive = true,
            )
        )
    }

    @Test
    fun ignoresActivationAdminListsAndOtherAppsAdminDetails() {
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.DeviceAdminAdd",
                texts = listOf("FocusLock", "Activate this device admin app", "Activate"),
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.DeviceAdminSettings",
                texts = listOf("Device admin apps", "FocusLock", "Other App"),
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.DeviceAdminAdd",
                texts = listOf("Other App", "Deactivate"),
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.DeviceAdminAdd",
                texts = listOf("FocusLock Helper", "Deactivate and uninstall"),
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = listOf("FocusLock", "Disable", "Force stop", "Uninstall"),
            )
        )
    }

    @Test
    fun matchesLocalizedSettingsDialogWithTargetAndExactAction() {
        assertTrue(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "android.app.AlertDialog",
                texts = listOf("FocusLock", "Kya aap is app ko uninstall karna chahte hain?", "Cancel", "Uninstall"),
            )
        )
    }

    @Test
    fun ignoresSettingsAppInfoUninstallButtonWithoutConfirmationDialog() {
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = listOf("FocusLock", "Open", "Uninstall", "Force stop"),
            )
        )
    }

    @Test
    fun ignoresSettingsPromptForAnotherAppAndBrowserSpoof() {
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "android.app.AlertDialog",
                texts = listOf("Other App", "Are you sure you want to uninstall this app?", "Uninstall"),
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.chrome",
                windowClassName = "android.app.AlertDialog",
                texts = listOf("FocusLock", "Are you sure you want to uninstall this app?", "Uninstall"),
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.settings",
                windowClassName = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = listOf("Do you want to uninstall FocusLock Helper?", "Cancel", "Uninstall"),
            )
        )
    }

    @Test
    fun ignoresInstallPromptForFocusLock() {
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.google.android.packageinstaller",
                windowClassName = "com.google.android.packageinstaller.InstallStart",
                texts = listOf("FocusLock", "Install"),
            )
        )
    }

    @Test
    fun ignoresPromptForAnotherAppAndSimilarAppTitle() {
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.packageinstaller",
                windowClassName = "com.android.packageinstaller.UninstallAppProgress",
                texts = listOf("Other App", "Uninstall"),
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.packageinstaller",
                windowClassName = "com.android.packageinstaller.UninstallAppProgress",
                texts = listOf("FocusLock Helper", "Uninstall"),
            )
        )
    }

    @Test
    fun ignoresGenericBrowserSpoofAndUnrelatedSettingsOrPermissionScreens() {
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.chrome",
                windowClassName = "com.android.chrome.WebDialog",
                texts = listOf("FocusLock", "Uninstall"),
            )
        )
        assertFalse(
            RemovalAttemptPolicy.matches(
                packageName = "com.android.permissioncontroller",
                windowClassName = "com.android.permissioncontroller.permission.ui.AppPermissionActivity",
                texts = listOf("FocusLock", "Allow access", "Uninstall updates"),
            )
        )
    }
}
