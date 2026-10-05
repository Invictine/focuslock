package com.focuslock.app

import com.focuslock.app.service.FrogAppPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrogAppPolicyTest {
    @Test
    fun unknownAppsAreDeniedBeforeAndAfterToolConfirmation() {
        val unknown = "com.example.distraction"
        assertTrue(FrogAppPolicy.shouldBlock(unknown, true, false, emptySet(), emptySet()))
        assertTrue(FrogAppPolicy.shouldBlock(unknown, true, true, setOf("com.example.notes"), emptySet()))
    }

    @Test
    fun onlyConfirmedSelectedToolIsAllowed() {
        assertTrue(FrogAppPolicy.shouldBlock("com.example.notes", true, false, setOf("com.example.notes"), emptySet()))
        assertFalse(FrogAppPolicy.shouldBlock("com.example.notes", true, true, setOf("com.example.notes"), emptySet()))
        assertTrue(FrogAppPolicy.shouldBlock("com.example.browser", true, true, setOf("com.example.notes"), emptySet()))
    }

    @Test
    fun essentialPhoneAppRemainsAllowedWithoutToolConfirmation() {
        assertFalse(
            FrogAppPolicy.shouldBlock(
                packageName = "com.android.phone",
                locked = true,
                toolsConfirmed = false,
                allowedTools = emptySet(),
                essentials = setOf("com.android.phone"),
            )
        )
    }

    @Test
    fun unlockedStateDoesNotBlockAppsRegardlessOfToolState() {
        assertFalse(FrogAppPolicy.shouldBlock("com.example.unknown", false, false, emptySet(), emptySet()))
        assertFalse(FrogAppPolicy.shouldBlock("com.example.unknown", false, true, emptySet(), emptySet()))
    }

    @Test
    fun defaultShortcutsAreAllowedBeforeToolsAreConfirmed() {
        val defaults = setOf("com.ticktick.task", "com.whatsapp", "com.openai.chatgpt", "com.spotify.music")
        defaults.forEach { pkg ->
            assertFalse(FrogAppPolicy.shouldBlock(pkg, true, false, emptySet(), defaults))
        }
        assertTrue(FrogAppPolicy.shouldBlock("com.example.unknown", true, false, emptySet(), defaults))
    }

    @Test
    fun defaultShortcutOrderAndExplicitSelectionKeepCorePinned() {
        val defaults = listOf(
            "com.ticktick.task",
            "com.phone",
            "com.clock",
            "com.messages",
            "com.whatsapp",
            "com.openai.chatgpt",
            "com.spotify.music",
            FrogAppPolicy.GPAY_PACKAGE,
        )

        // No saved preference keeps every default shortcut, including Google Pay.
        assertEquals(8, defaults.size)
        assertEquals(defaults, FrogAppPolicy.configuredLaunchPackages(defaults, null))

        // Saving an explicit selection may remove optional defaults, while the core four stay pinned.
        assertEquals(defaults.take(4), FrogAppPolicy.configuredLaunchPackages(defaults, emptySet()))
        assertEquals(
            defaults.take(4) + "com.spotify.music",
            FrogAppPolicy.configuredLaunchPackages(defaults, setOf("com.spotify.music")),
        )
        assertEquals(
            defaults.take(4) + FrogAppPolicy.GPAY_PACKAGE,
            FrogAppPolicy.configuredLaunchPackages(defaults, setOf(FrogAppPolicy.GPAY_PACKAGE)),
        )
        assertTrue(
            FrogAppPolicy.shouldBlock(
                packageName = "com.whatsapp",
                locked = true,
                toolsConfirmed = true,
                allowedTools = emptySet(),
                essentials = FrogAppPolicy.configuredLaunchPackages(defaults, emptySet()).toSet(),
            )
        )
        assertTrue(
            FrogAppPolicy.shouldBlock(
                packageName = FrogAppPolicy.GPAY_PACKAGE,
                locked = true,
                toolsConfirmed = false,
                allowedTools = emptySet(),
                essentials = FrogAppPolicy.configuredLaunchPackages(defaults, emptySet()).toSet(),
            )
        )
    }

    @Test
    fun googlePayPackagesExemptWhenConfiguredInEssentials() {
        val defaults = listOf(
            "com.ticktick.task",
            "com.phone",
            "com.clock",
            "com.messages",
            "com.whatsapp",
            "com.openai.chatgpt",
            "com.spotify.music",
            FrogAppPolicy.GPAY_PACKAGE,
        )
        val essentials = FrogAppPolicy.configuredLaunchPackages(defaults, setOf(FrogAppPolicy.GPAY_PACKAGE)).toSet()
        assertFalse(FrogAppPolicy.shouldBlock(FrogAppPolicy.GPAY_PACKAGE, true, false, emptySet(), essentials))
        assertTrue(FrogAppPolicy.GOOGLE_PAY_PACKAGES.contains(FrogAppPolicy.GPAY_PACKAGE))
        assertTrue(FrogAppPolicy.GOOGLE_PAY_PACKAGES.contains(FrogAppPolicy.GPAY_WALLET_PACKAGE))
    }
}
