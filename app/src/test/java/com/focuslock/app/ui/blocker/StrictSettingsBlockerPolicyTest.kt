package com.focuslock.app.ui.blocker

import com.focuslock.app.strict.StrictAccessConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictSettingsBlockerPolicyTest {
    @Test
    fun onlyTheSettingsStrictReasonUsesDedicatedBlocker() {
        assertTrue(StrictSettingsBlockerPolicy.isTarget("strict_settings", "com.android.settings", null))
        assertFalse(StrictSettingsBlockerPolicy.isTarget("limit", "com.android.settings", null))
        assertFalse(StrictSettingsBlockerPolicy.isTarget("strict_settings", "com.android.chrome", null))
        assertFalse(StrictSettingsBlockerPolicy.isTarget("strict_settings", "com.android.settings", "example.com"))
    }

    @Test
    fun screenRequiresBothAnActiveStrictCommitmentAndSettingsProtection() {
        assertTrue(
            StrictSettingsBlockerPolicy.shouldRemainVisible(
                strictActive = true,
                config = StrictAccessConfig(blockDeviceSettings = true),
            ),
        )
        assertFalse(
            StrictSettingsBlockerPolicy.shouldRemainVisible(
                strictActive = false,
                config = StrictAccessConfig(blockDeviceSettings = true),
            ),
        )
        assertFalse(
            StrictSettingsBlockerPolicy.shouldRemainVisible(
                strictActive = true,
                config = StrictAccessConfig(blockDeviceSettings = false),
            ),
        )
    }
}
