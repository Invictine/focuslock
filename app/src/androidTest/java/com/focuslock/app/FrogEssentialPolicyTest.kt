package com.focuslock.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.model.FrogState
import com.focuslock.app.service.FrogAppPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrogEssentialPolicyTest {
    @Test fun appTesterRemainsAnUpdateRecoveryPathDuringFrog(): Unit = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageName = com.focuslock.app.service.AppUpdateAccessPolicy.APP_TESTER_PACKAGE
        val locked = frogState(phase = FrogPhase.PICK_FROG, locked = true)
        assertTrue(FrogAppPolicy.isSafetyEssential(context, packageName))
        assertFalse(FrogAppPolicy.shouldShowFocusScreen(context, packageName, locked))
        assertTrue(com.focuslock.app.data.repository.PermanentBlocksRepository.isProtectedPackage(context, packageName))
        assertFalse(com.focuslock.app.service.BlockedNotificationPolicy.shouldCancel(context, packageName, locked))
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun pickFrogKeepsEveryDefaultEssentialAvailableAndNeverBlocksFocusLockItself() {
        val state = frogState(phase = FrogPhase.PICK_FROG, locked = true)

        assertTrue(state.essentialAppPackages == null)
        assertTrue(FrogAppPolicy.defaultLaunchPackages(context).isNotEmpty())
        FrogAppPolicy.defaultLaunchPackages(context).forEach { packageName ->
            assertFalse("$packageName should be in the default Frog allowlist", FrogAppPolicy.isBlocked(context, packageName, state))
            assertFalse("$packageName should not show the Frog focus screen", FrogAppPolicy.shouldShowFocusScreen(context, packageName, state))
        }

        assertFalse(FrogAppPolicy.shouldShowFocusScreen(context, context.packageName, state))
        assertFalse(FrogAppPolicy.isBlocked(context, context.packageName, state))
    }

    @Test
    fun confirmedToolsStayAvailableAndUnconfirmedOrUnknownAppsStayBlocked() {
        val confirmed = frogState(
            phase = FrogPhase.WORKING,
            locked = true,
            toolsConfirmed = true,
            allowedTools = setOf("com.example.notes"),
        )
        assertFalse(FrogAppPolicy.isBlocked(context, "com.example.notes", confirmed))
        assertFalse(FrogAppPolicy.shouldShowFocusScreen(context, "com.example.notes", confirmed))

        val unconfirmed = confirmed.copy(toolsConfirmed = false)
        assertTrue(FrogAppPolicy.isBlocked(context, "com.example.notes", unconfirmed))
        assertTrue(FrogAppPolicy.shouldShowFocusScreen(context, "com.example.notes", unconfirmed))

        assertTrue(FrogAppPolicy.isBlocked(context, "com.example.distraction", confirmed))
        assertTrue(FrogAppPolicy.shouldShowFocusScreen(context, "com.example.distraction", confirmed))
    }

    @Test
    fun ordinaryHomeGetsFocusScreenOnlyWhileFrogIsLocked() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackage = context.packageManager
            .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        assertTrue("The test device should resolve its HOME package", !homePackage.isNullOrBlank())
        assertTrue("HOME should be Frog-blocked even if it is otherwise essential", FrogAppPolicy.isHome(context, homePackage!!))

        val locked = frogState(phase = FrogPhase.PICK_FROG, locked = true)
        assertTrue(FrogAppPolicy.shouldShowFocusScreen(context, homePackage, locked))

        val unlocked = locked.copy(locked = false)
        assertFalse(FrogAppPolicy.isBlocked(context, homePackage, unlocked))
        assertFalse(FrogAppPolicy.shouldShowFocusScreen(context, homePackage, unlocked))
    }

    private fun frogState(
        phase: FrogPhase,
        locked: Boolean,
        allowedTools: Set<String> = emptySet(),
        toolsConfirmed: Boolean = false,
    ) = FrogState(
        cycleDate = "2026-10-04",
        enabled = true,
        armed = true,
        phase = phase,
        frog = null,
        tickedOff = false,
        trackedSeconds = 0,
        requiredSeconds = 60,
        locked = locked,
        openTasks = emptyList(),
        allowedToolPackages = allowedTools,
        toolsConfirmed = toolsConfirmed,
        essentialAppPackages = null,
    )
}
