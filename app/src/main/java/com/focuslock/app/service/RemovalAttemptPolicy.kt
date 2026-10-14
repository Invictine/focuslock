package com.focuslock.app.service

/**
 * Conservative matcher for system package-removal prompts targeting FocusLock.
 *
 * OEM and localized wording varies. Exact app identity is always required. Settings
 * is handled more narrowly than installer apps because its ordinary App info page
 * also contains an Uninstall action.
 */
object RemovalAttemptPolicy {
    const val TARGET_PACKAGE = "com.focuslock.app"
    const val TARGET_TITLE = "FocusLock"

    val installerPackages: Set<String> = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
    )

    /** Settings-hosted prompts need stricter confirmation checks than installer dialogs. */
    val promptPackages: Set<String> = installerPackages + "com.android.settings"

    private val uninstallConfirmation = Regex(
        """^(?:(?:do you want to uninstall|are you sure you want to uninstall)(?: the app| this app)?(?: [\"']?focuslock[\"']?)?[?.]?|uninstall(?: this app| app)?[?.]?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val explicitUninstallQuestion = Regex(
        """^(?:do you want to uninstall|are you sure you want to uninstall)(?: the app| this app)?(?: [\"']?focuslock[\"']?)?[?.]?$""",
        RegexOption.IGNORE_CASE,
    )

    fun matches(packageName: String, windowClassName: String?, texts: List<String>): Boolean {
        val sourcePackage = packageName.trim()
        if (sourcePackage !in promptPackages) return false

        val normalizedTexts = texts.map(String::trim).filter(String::isNotEmpty)
        val exactTargetIdentity = normalizedTexts.any { text ->
            text.equals(TARGET_TITLE, ignoreCase = true) || text == TARGET_PACKAGE
        }
        val targetNamedInQuestion = normalizedTexts.any { text ->
            explicitUninstallQuestion.matches(text) &&
                text.contains(TARGET_TITLE, ignoreCase = true)
        }
        val targetsFocusLock = exactTargetIdentity || targetNamedInQuestion
        if (!targetsFocusLock) return false

        val exactUninstallAction = normalizedTexts.any { it.equals("uninstall", ignoreCase = true) }
        val explicitQuestion = normalizedTexts.any(explicitUninstallQuestion::matches)

        if (sourcePackage == "com.android.settings") {
            // App info itself has an Uninstall button. Require either a clear
            // confirmation question or a dialog window with the exact action.
            val confirmationDialog = windowClassName
                ?.contains("dialog", ignoreCase = true) == true && exactUninstallAction
            return explicitQuestion || confirmationDialog
        }

        val uninstallActivity = windowClassName
            ?.contains("uninstall", ignoreCase = true) == true
        val clearUninstallText = exactUninstallAction || normalizedTexts.any(uninstallConfirmation::matches)
        return uninstallActivity || clearUninstallText
    }
}
