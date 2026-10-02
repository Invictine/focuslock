package com.focuslock.app.service

/**
 * Conservative matcher for system package-removal prompts targeting FocusLock.
 *
 * OEM and localized wording varies. Exact app identity is always required; the
 * uninstall class path supports localized dialogs, while English action wording
 * is only a fallback for installer windows whose class names are unhelpful.
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

    private val uninstallConfirmation = Regex(
        """^(?:do you want to uninstall(?: the app)?(?: [\"']?focuslock[\"']?)?[?.]?|uninstall(?: this app| app)?[?.]?)$""",
        RegexOption.IGNORE_CASE,
    )

    fun matches(packageName: String, windowClassName: String?, texts: List<String>): Boolean {
        if (packageName.trim() !in installerPackages) return false

        val normalizedTexts = texts.map(String::trim).filter(String::isNotEmpty)
        val targetsFocusLock = normalizedTexts.any { text ->
            text.equals(TARGET_TITLE, ignoreCase = true) || text == TARGET_PACKAGE
        }
        if (!targetsFocusLock) return false

        val uninstallActivity = windowClassName
            ?.contains("uninstall", ignoreCase = true) == true
        val clearUninstallText = normalizedTexts.any { text ->
            text.equals("uninstall", ignoreCase = true) || uninstallConfirmation.matches(text)
        }
        return uninstallActivity || clearUninstallText
    }
}
