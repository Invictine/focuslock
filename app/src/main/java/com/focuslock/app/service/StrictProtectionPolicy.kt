package com.focuslock.app.service

import com.focuslock.app.strict.StrictAccessConfig

/** Geometry and identity for one visible accessibility window. */
internal data class StrictWindow(
    val packageName: String,
    val kind: Kind,
    val displayId: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    enum class Kind { APPLICATION, SYSTEM, INPUT_METHOD }
}

internal data class StrictDisplayBounds(val width: Int, val height: Int)

/** Pure targeting rules for optional protections that apply only during an active commitment. */
internal object StrictProtectionPolicy {
    const val BLOCK_SETTINGS = "strict_settings"
    const val BLOCK_RECENTS = "strict_recents"
    const val BLOCK_SPLIT_SCREEN = "strict_split_screen"
    const val PREVENT_REMOVAL = "strict_prevent_removal"

    private const val MIN_WINDOW_SCREEN_FRACTION = 0.22
    private const val MAX_OVERLAP_FRACTION = 0.15
    private const val MIN_COMBINED_SCREEN_FRACTION = 0.65

    private val recentsClasses = setOf(
        "com.android.quickstep.RecentsActivity",
        "com.android.systemui.recents.RecentsActivity",
    )
    private val launcherPackages = setOf(
        "com.android.launcher3",
        "com.android.quickstep",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.miui.home",
    )
    private val overviewViewIds = setOf("overview_panel", "recents_view", "clear_all", "clear_all_button")

    /**
     * Returns a strict-only reason before normal recovery-package exemptions are applied.
     * System UI is considered only for the small, known overview activity allowlist.
     */
    fun windowBlockReason(
        packageName: String,
        windowClass: String?,
        config: StrictAccessConfig,
        ownPackage: String,
        registeredImePackages: Set<String>,
        visibleOverviewViewIds: Set<String> = emptySet(),
    ): String? {
        if (packageName.isBlank()) return null

        if (config.blockRecents && isKnownOverviewWindow(packageName, windowClass, visibleOverviewViewIds)) {
            return BLOCK_RECENTS
        }

        // A third-party keyboard is an explicit recovery path even when its settings UI
        // is hosted by Android Settings. Do not confuse that with ordinary device settings.
        if (packageName in registeredImePackages || isKeyboardSettingsWindow(windowClass)) return null

        // Settings is intentionally intercepted before its broad recovery exemption.
        if (packageName == SETTINGS_PACKAGE) {
            return if (config.blockDeviceSettings) BLOCK_SETTINGS else null
        }

        // Keep FocusLock, emergency/phone, system chrome, and Android permission flows
        // operable. Targeted FocusLock removal/admin prompts are handled separately.
        if (DeviceAccessPolicy.isExempt(packageName, ownPackage, registeredImePackages)) return null
        return null
    }

    fun targetedRemovalReason(
        packageName: String,
        windowClass: String?,
        visibleTexts: List<String>,
        config: StrictAccessConfig,
    ): String? {
        if (!config.preventUninstall) return null
        return if (RemovalAttemptPolicy.matches(packageName, windowClass, visibleTexts)) {
            PREVENT_REMOVAL
        } else null
    }

    fun isKnownOverviewWindow(
        packageName: String,
        windowClass: String?,
        visibleViewIds: Set<String> = emptySet(),
    ): Boolean {
        val normalized = windowClass?.substringBefore('$')?.trim().orEmpty()
        if (packageName == SYSTEM_UI_PACKAGE && normalized in recentsClasses) return true
        if (packageName !in launcherPackages) return false

        // Match activity identities exactly. Launcher home activities are deliberately
        // excluded; modern launchers expose overview through their own RecentsActivity.
        if (normalized in recentsClasses) return true
        return visibleViewIds.any { it.substringAfterLast('/') in overviewViewIds }
    }

    fun isKnownLauncherPackage(packageName: String): Boolean = packageName in launcherPackages

    fun isSplitScreen(
        windows: List<StrictWindow>,
        displayBounds: (Int) -> StrictDisplayBounds?,
        ownPackage: String,
        registeredImePackages: Set<String>,
    ): Boolean {
        val appWindows = windows.filter { window ->
            window.kind == StrictWindow.Kind.APPLICATION &&
                window.packageName.isNotBlank() &&
                window.packageName != ownPackage &&
                window.packageName !in registeredImePackages &&
                window.packageName !in recoveryPackages
        }
        for (displayGroup in appWindows.groupBy(StrictWindow::displayId).values) {
            val bounds = displayBounds(displayGroup.first().displayId) ?: continue
            if (bounds.width <= 0 || bounds.height <= 0) continue
            val screenArea = bounds.width.toDouble() * bounds.height
            for (firstIndex in displayGroup.indices) {
                for (secondIndex in firstIndex + 1 until displayGroup.size) {
                    if (isSubstantialNonOverlappingPair(
                            displayGroup[firstIndex], displayGroup[secondIndex], screenArea,
                        )) return true
                }
            }
        }
        return false
    }

    private fun isSubstantialNonOverlappingPair(
        first: StrictWindow,
        second: StrictWindow,
        screenArea: Double,
    ): Boolean {
        val firstArea = area(first)
        val secondArea = area(second)
        if (firstArea / screenArea < MIN_WINDOW_SCREEN_FRACTION ||
            secondArea / screenArea < MIN_WINDOW_SCREEN_FRACTION
        ) return false

        val overlapWidth = (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0)
        val overlapHeight = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0)
        val overlapArea = overlapWidth.toDouble() * overlapHeight
        if (overlapArea / minOf(firstArea, secondArea) > MAX_OVERLAP_FRACTION) return false

        val unionArea = firstArea + secondArea - overlapArea
        return unionArea / screenArea >= MIN_COMBINED_SCREEN_FRACTION
    }

    private fun area(window: StrictWindow): Double =
        (window.right - window.left).coerceAtLeast(0).toDouble() *
            (window.bottom - window.top).coerceAtLeast(0)

    private fun isKeyboardSettingsWindow(windowClass: String?): Boolean {
        val normalized = windowClass.orEmpty().lowercase()
        return listOf("inputmethod", "keyboard", "languageandinput", "manageinput").any(normalized::contains)
    }

    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private val recoveryPackages = setOf(
        "android",
        SYSTEM_UI_PACKAGE,
        SETTINGS_PACKAGE,
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )
}
