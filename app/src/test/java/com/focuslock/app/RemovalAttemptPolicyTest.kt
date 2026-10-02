package com.focuslock.app

import com.focuslock.app.service.RemovalAttemptPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalAttemptPolicyTest {
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
