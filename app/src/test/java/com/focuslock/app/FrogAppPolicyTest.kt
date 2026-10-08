package com.focuslock.app

import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.repository.frogBoundaryAppPackages
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

    @Test
    fun boundaryPackagesAreRemovedAfterDefaultsCoreAndExplicitSelectionsAreAssembled() {
        val defaults = listOf(
            "com.ticktick.task", "com.phone", "com.clock", "com.messages",
            "com.whatsapp", "com.openai.chatgpt", "com.spotify.music",
        )
        val boundaries = setOf("com.phone", "com.whatsapp", "com.extra.boundary")

        // Null preserves the usual defaults, except that boundary policy always wins,
        // including over the mandatory core shortcuts.
        assertEquals(
            listOf("com.ticktick.task", "com.clock", "com.messages", "com.openai.chatgpt", "com.spotify.music"),
            FrogAppPolicy.configuredLaunchPackages(defaults, null, boundaries),
        )
        assertEquals(
            listOf("com.ticktick.task", "com.clock", "com.messages"),
            FrogAppPolicy.configuredLaunchPackages(defaults, emptySet(), boundaries),
        )
        assertEquals(
            listOf("com.ticktick.task", "com.clock", "com.messages", "com.spotify.music"),
            FrogAppPolicy.configuredLaunchPackages(defaults, setOf("com.whatsapp", "com.spotify.music"), boundaries),
        )
        assertEquals(
            listOf("com.ticktick.task", "com.clock", "com.messages", "com.notes.app"),
            FrogAppPolicy.configuredLaunchPackages(
                defaults,
                setOf("com.notes.app", "com.whatsapp", "com.phone"),
                boundaries,
            ),
        )
    }

    @Test
    fun boundaryMatchingIsTrimmedAndCaseInsensitiveWithoutAffectingUnlistedApps() {
        val defaults = listOf("com.ticktick.task", "com.phone", "com.clock", "com.messages", "com.whatsapp")
        assertEquals(
            listOf("com.ticktick.task", "com.phone", "com.clock", "com.messages", "com.spotify.music"),
            FrogAppPolicy.configuredLaunchPackages(
                defaults,
                setOf("com.whatsapp", "com.spotify.music"),
                setOf(" COM.WHATSAPP ", " com.unrelated.app "),
            ),
        )
    }

    @Test
    fun boundarySourceIncludesBlockedShortsAndPermanentEntriesButNotOrdinaryApps() {
        val packages = frogBoundaryAppPackages(
            apps = listOf(
                BlockedApp(" com.example.blocked ", "Blocked", isBlocked = true),
                BlockedApp("com.example.shorts", "Shorts", isBlocked = true, specificShortsOnly = true),
                BlockedApp("com.example.legacy", "Legacy permanent", isBlocked = false, isPermanent = true),
                BlockedApp("com.example.ordinary", "Ordinary", isBlocked = false),
            ),
            permanent = setOf(" COM.EXAMPLE.PERMANENT "),
        )

        assertEquals(
            setOf("com.example.blocked", "com.example.shorts", "com.example.legacy", "com.example.permanent"),
            packages,
        )
    }
}
