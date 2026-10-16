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

    const val REASON_ADMIN_REQUESTED = "admin_requested"
    const val REASON_UNINSTALL = "uninstall"

    private val uninstallConfirmation = Regex(
        """^(?:(?:do you want to uninstall|are you sure you want to uninstall)(?: the app| this app)?(?: [\"']?focuslock[\"']?)?[?.]?|uninstall(?: this app| app)?[?.]?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val explicitUninstallQuestion = Regex(
        """^(?:do you want to uninstall|are you sure you want to uninstall)(?: the app| this app)?(?: [\"']?focuslock[\"']?)?[?.]?$""",
        RegexOption.IGNORE_CASE,
    )

    private val explicitAdminQuestion = Regex(
        """^(?:(?:do you want to|are you sure you want to) )?(?:deactivate|disable|turn off)(?: this)? (?:device )?admin(?: app)?(?: for| of)?(?: [\"']?focuslock[\"']?)?[?.]?$""",
        RegexOption.IGNORE_CASE,
    )
    /** OEM App info flows may stop at a confirmation saying the active admin must be disabled first. */
    private val deactivateFirst = Regex(
        """^(?:deactivate|disable|turn off)(?: this)?(?:(?: device)? admin| app)? first[?.!]*$""",
        RegexOption.IGNORE_CASE,
    )
    private val adminDeactivationAction = Regex(
        """^(?:deactivate|disable|turn off)(?: this)? (?:device )?admin(?: app)?[?.]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val bareDeactivationAction = Regex(
        """^(?:deactivate|disable)[?.]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val activationAction = Regex(
        """^(?:activate|activate this (?:device )?admin(?: app)?)[?.]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val deactivateAndUninstallAction = Regex(
        """^deactivate (?:&|and) uninstall[?.]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val targetAdminLabel = Regex(
        """^FocusLock uninstall protection$""",
        RegexOption.IGNORE_CASE,
    )
    private val deviceAdminScreen = Regex(
        """(?:^|[.$])DeviceAdminAdd$""",
        RegexOption.IGNORE_CASE,
    )

    fun matches(
        packageName: String,
        windowClassName: String?,
        texts: List<String>,
        deviceAdminActive: Boolean = false,
    ): Boolean = reason(packageName, windowClassName, texts, deviceAdminActive) != null

    /** Cheap prefilter for a Settings click; ordinary app-info interactions do not scan the root. */
    fun isPossibleRemovalActionClick(clickedTexts: List<String>): Boolean {
        val normalized = clickedTexts.map(String::trim).filter(String::isNotEmpty)
        return normalized.any { text ->
            text.equals("uninstall", ignoreCase = true) ||
                adminDeactivationAction.matches(text) ||
                bareDeactivationAction.matches(text) ||
                deactivateAndUninstallAction.matches(text) ||
                deactivateFirst.matches(text)
        }
    }

    /**
     * Recognizes the first App info/Admin click before OEMs replace the page with a
     * confirmation that omits the app name. Require live exact identity in that root
     * and an active admin; never use a previously observed app identity.
     */
    fun clickedActionReason(
        packageName: String,
        rootTexts: List<String>,
        clickedTexts: List<String>,
        deviceAdminActive: Boolean,
    ): String? {
        if (packageName != "com.android.settings" || !deviceAdminActive) return null
        val normalizedRoot = rootTexts.map(String::trim).filter(String::isNotEmpty)
        val exactTargetIdentity = normalizedRoot.any { text ->
            text.equals(TARGET_TITLE, ignoreCase = true) || text == TARGET_PACKAGE ||
                targetAdminLabel.matches(text)
        }
        if (!exactTargetIdentity || !isPossibleRemovalActionClick(clickedTexts)) return null
        return REASON_ADMIN_REQUESTED
    }

    /** Returns the reminder reason only when a FocusLock-specific system action is visible. */
    fun reason(
        packageName: String,
        windowClassName: String?,
        texts: List<String>,
        deviceAdminActive: Boolean = false,
    ): String? {
        val sourcePackage = packageName.trim()
        if (sourcePackage !in promptPackages) return null

        val normalizedTexts = texts.map(String::trim).filter(String::isNotEmpty)
        val exactTargetIdentity = normalizedTexts.any { text ->
            text.equals(TARGET_TITLE, ignoreCase = true) || text == TARGET_PACKAGE ||
                targetAdminLabel.matches(text)
        }
        val targetNamedInQuestion = normalizedTexts.any { text ->
            (explicitUninstallQuestion.matches(text) || explicitAdminQuestion.matches(text)) &&
                text.contains(TARGET_TITLE, ignoreCase = true)
        }
        val targetsFocusLock = exactTargetIdentity || targetNamedInQuestion
        if (!targetsFocusLock) return null

        if (sourcePackage == "com.android.settings") {
            val windowClass = windowClassName.orEmpty()
            val isDeviceAdminDetail = deviceAdminScreen.containsMatchIn(windowClass)
            val activationScreen = normalizedTexts.any(activationAction::matches)
            val deactivationAction = normalizedTexts.any(adminDeactivationAction::matches)
            val deviceAdminButton = isDeviceAdminDetail &&
                normalizedTexts.any { text ->
                    bareDeactivationAction.matches(text) || deactivateAndUninstallAction.matches(text)
                }
            val explicitAdminQuestionVisible = normalizedTexts.any(explicitAdminQuestion::matches)
            val deactivateFirstVisible = normalizedTexts.any(deactivateFirst::matches)
            val namesAdmin = normalizedTexts.any { text ->
                text.contains("device admin", ignoreCase = true) ||
                    text.contains("admin app", ignoreCase = true) ||
                    text.contains("administrator", ignoreCase = true)
            }

            if (isDeviceAdminDetail && activationScreen) return null
            if (deactivationAction || deviceAdminButton ||
                (isDeviceAdminDetail && deviceAdminActive)
            ) {
                return REASON_ADMIN_REQUESTED
            }
            val confirmationDialog = windowClass.contains("dialog", ignoreCase = true) ||
                windowClass.contains("alert", ignoreCase = true)
            if (confirmationDialog && namesAdmin && explicitAdminQuestionVisible) {
                return REASON_ADMIN_REQUESTED
            }
            // On some OEM tablets, App info → Uninstall opens a modal that only says
            // “Deactivate first”. It has no uninstall wording and does not enter
            // DeviceAdminAdd until the user accepts, so the normal admin button and
            // DeviceAdminReceiver callbacks never see this attempt. Require the exact
            // FocusLock identity and an active admin avoid treating unrelated Settings
            // copy as a removal attempt. Do not require a dialog class: some OEMs expose
            // the modal under the App info activity's root class.
            if (deviceAdminActive && deactivateFirstVisible) {
                return REASON_ADMIN_REQUESTED
            }
        }

        val exactUninstallAction = normalizedTexts.any { it.equals("uninstall", ignoreCase = true) }
        val explicitQuestion = normalizedTexts.any(explicitUninstallQuestion::matches)

        if (sourcePackage == "com.android.settings") {
            // App info itself has an Uninstall button. Require either a clear
            // confirmation question or a dialog window with the exact action.
            val confirmationDialog = windowClassName
                ?.contains("dialog", ignoreCase = true) == true && exactUninstallAction
            return if (explicitQuestion || confirmationDialog) REASON_UNINSTALL else null
        }

        val uninstallActivity = windowClassName
            ?.contains("uninstall", ignoreCase = true) == true
        val clearUninstallText = exactUninstallAction || normalizedTexts.any(uninstallConfirmation::matches)
        return if (uninstallActivity || clearUninstallText) REASON_UNINSTALL else null
    }
}
