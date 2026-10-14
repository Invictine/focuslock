package com.focuslock.app.service

import com.focuslock.app.strict.StrictAccessConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictProtectionPolicyTest {
    private val ownPackage = "com.focuslock.app"
    private val imePackages = setOf("com.example.keyboard")

    @Test
    fun settingsProtectionIsNarrowAndKeepsRecoveryFlowsAvailable() {
        val config = StrictAccessConfig(blockDeviceSettings = true)

        assertEquals(
            StrictProtectionPolicy.BLOCK_SETTINGS,
            StrictProtectionPolicy.windowBlockReason("com.android.settings", "Settings", config, ownPackage, imePackages),
        )
        assertNull(StrictProtectionPolicy.windowBlockReason(ownPackage, "MainActivity", config, ownPackage, imePackages))
        assertNull(StrictProtectionPolicy.windowBlockReason("com.example.keyboard", "Keyboard", config, ownPackage, imePackages))
        assertNull(StrictProtectionPolicy.windowBlockReason("com.android.emergency", "Emergency", config, ownPackage, imePackages))
        assertNull(StrictProtectionPolicy.windowBlockReason("com.android.permissioncontroller", "GrantPermissionsActivity", config, ownPackage, imePackages))
        assertNull(StrictProtectionPolicy.windowBlockReason("com.android.settings", "com.android.settings.inputmethod.InputMethodAndLanguageSettings", config, ownPackage, imePackages))
    }

    @Test
    fun recentsProtectionMatchesOnlyKnownOverviewWindows() {
        val config = StrictAccessConfig(blockRecents = true)
        assertEquals(
            StrictProtectionPolicy.BLOCK_RECENTS,
            StrictProtectionPolicy.windowBlockReason(
                "com.android.systemui", "com.android.quickstep.RecentsActivity", config, ownPackage, imePackages,
            ),
        )
        assertNull(StrictProtectionPolicy.windowBlockReason("com.android.systemui", "com.android.systemui.SystemUIService", config, ownPackage, imePackages))
        assertNull(StrictProtectionPolicy.windowBlockReason("com.example.launcher", "com.example.launcher.RecentsActivity", config, ownPackage, imePackages))
        assertNull(StrictProtectionPolicy.windowBlockReason("com.android.systemui", "com.example.RecentsActivity", config, ownPackage, imePackages))

        val launcherRecents = listOf(
            "com.android.launcher3" to "com.android.quickstep.RecentsActivity",
            "com.android.quickstep" to "com.android.quickstep.RecentsActivity",
            "com.google.android.apps.nexuslauncher" to "com.android.quickstep.RecentsActivity",
            "com.sec.android.app.launcher" to "com.android.quickstep.RecentsActivity",
            "com.miui.home" to "com.android.quickstep.RecentsActivity",
        )
        launcherRecents.forEach { (packageName, activityClass) ->
            assertEquals(
                packageName,
                StrictProtectionPolicy.BLOCK_RECENTS,
                StrictProtectionPolicy.windowBlockReason(
                    packageName, activityClass, config, ownPackage, imePackages,
                ),
            )
        }

        assertEquals(
            StrictProtectionPolicy.BLOCK_RECENTS,
            StrictProtectionPolicy.windowBlockReason(
                "com.google.android.apps.nexuslauncher",
                "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
                config,
                ownPackage,
                imePackages,
                visibleOverviewViewIds = setOf("com.google.android.apps.nexuslauncher:id/overview_panel"),
            ),
        )
        assertEquals(
            StrictProtectionPolicy.BLOCK_RECENTS,
            StrictProtectionPolicy.windowBlockReason(
                "com.android.launcher3",
                "com.android.launcher3.Launcher",
                config,
                ownPackage,
                imePackages,
                visibleOverviewViewIds = setOf("clear_all"),
            ),
        )

        assertNull(StrictProtectionPolicy.windowBlockReason(
            "com.android.launcher3", "com.android.launcher3.Launcher", config, ownPackage, imePackages,
        ))
        assertNull(StrictProtectionPolicy.windowBlockReason(
            "com.android.launcher3", "com.android.launcher3.Launcher", config, ownPackage, imePackages,
            visibleOverviewViewIds = setOf("search_bar"),
        ))
        assertNull(StrictProtectionPolicy.windowBlockReason(
            "com.example.launcher", "com.android.quickstep.RecentsActivity", config, ownPackage, imePackages,
            visibleOverviewViewIds = setOf("overview_panel"),
        ))
        assertNull(StrictProtectionPolicy.windowBlockReason(
            "com.android.launcher3", "com.android.launcher3.Launcher", StrictAccessConfig(blockRecents = false),
            ownPackage, imePackages, visibleOverviewViewIds = setOf("overview_panel"),
        ))
    }

    @Test
    fun splitDetectionRequiresTwoSubstantialTiledApplicationWindowsOnOneDisplay() {
        val split = listOf(
            appWindow("com.example.study", 0, 0, 0, 1000, 500),
            appWindow("com.example.social", 0, 0, 500, 1000, 1000),
        )
        assertTrue(StrictProtectionPolicy.isSplitScreen(split, { StrictDisplayBounds(1000, 1000) }, ownPackage, imePackages))

        val sameAppSplit = listOf(
            appWindow("com.example.reader", 0, 0, 0, 1000, 500),
            appWindow("com.example.reader", 0, 0, 500, 1000, 1000),
        )
        assertTrue(StrictProtectionPolicy.isSplitScreen(sameAppSplit, { StrictDisplayBounds(1000, 1000) }, ownPackage, imePackages))

        val overlappingPopup = listOf(
            appWindow("com.example.study", 0, 0, 0, 1000, 1000),
            appWindow("com.example.social", 0, 250, 250, 750, 750),
        )
        assertFalse(StrictProtectionPolicy.isSplitScreen(overlappingPopup, { StrictDisplayBounds(1000, 1000) }, ownPackage, imePackages))

        val samePackagePopup = listOf(
            appWindow("com.example.study", 0, 0, 0, 1000, 1000),
            appWindow("com.example.study", 0, 0, 0, 400, 400),
        )
        assertFalse(StrictProtectionPolicy.isSplitScreen(samePackagePopup, { StrictDisplayBounds(1000, 1000) }, ownPackage, imePackages))

        val differentDisplays = listOf(
            appWindow("com.example.study", 0, 0, 0, 1000, 1000),
            appWindow("com.example.social", 1, 0, 0, 1000, 1000),
        )
        assertFalse(StrictProtectionPolicy.isSplitScreen(differentDisplays, { StrictDisplayBounds(1000, 1000) }, ownPackage, imePackages))
    }

    @Test
    fun targetedRemovalPreventionOnlyMatchesFocusLockSystemPrompts() {
        val enabled = StrictAccessConfig(preventUninstall = true)
        val disabled = StrictAccessConfig(preventUninstall = false)

        assertEquals(
            StrictProtectionPolicy.PREVENT_REMOVAL,
            StrictProtectionPolicy.targetedRemovalReason(
                "com.android.settings",
                "android.app.AlertDialog",
                listOf("FocusLock", "Do you want to uninstall FocusLock?", "Uninstall"),
                enabled,
            ),
        )
        assertNull(StrictProtectionPolicy.targetedRemovalReason(
            "com.android.settings", "android.app.AlertDialog",
            listOf("Other app", "Uninstall"), enabled,
        ))
        assertNull(StrictProtectionPolicy.targetedRemovalReason(
            "com.android.settings", "com.android.settings.applications.InstalledAppDetails",
            listOf("FocusLock", "Uninstall"), disabled,
        ))
    }

    private fun appWindow(
        packageName: String,
        displayId: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = StrictWindow(packageName, StrictWindow.Kind.APPLICATION, displayId, left, top, right, bottom)
}
