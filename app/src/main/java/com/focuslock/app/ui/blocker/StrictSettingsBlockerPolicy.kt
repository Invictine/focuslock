package com.focuslock.app.ui.blocker

import com.focuslock.app.strict.StrictAccessConfig

/** Guards the dedicated Strict Settings blocker from stale or malformed intents. */
internal object StrictSettingsBlockerPolicy {
    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val REASON = "strict_settings"

    fun isTarget(reason: String?, packageName: String?, website: String?): Boolean =
        reason == REASON && packageName == SETTINGS_PACKAGE && website.isNullOrBlank()

    fun shouldRemainVisible(strictActive: Boolean, config: StrictAccessConfig): Boolean =
        strictActive && config.blockDeviceSettings
}
