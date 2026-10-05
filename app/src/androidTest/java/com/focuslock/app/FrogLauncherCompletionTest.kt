package com.focuslock.app

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.service.FrogHomeLauncher
import com.focuslock.app.ui.MainActivity
import com.focuslock.app.ui.blocker.BlockerActivity
import com.focuslock.app.ui.blocker.FrogHomeActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable emulator fixture; host backs up and restores files and preferences. */
@RunWith(AndroidJUnit4::class)
class FrogLauncherCompletionTest {
    @Test
    fun bothFocusSurfacesReturnToCapturedHomeWhenCompletedOrEnded(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("frogLauncherFixture") == "true")
        assumeTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic"))
        val app = FocusLockApplication.instance
        val home = app.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        )!!.activityInfo.packageName
        assertNotEquals(app.packageName, home)
        FrogHomeLauncher.captureFallback(app)
        app.homeLocationRepository.setHomeOnly(false)

        suspend fun prepareFocus() {
            app.frogRepository.setEnabled(true)
            app.frogRepository.setWakeHour(0)
            app.frogRepository.setRequiredMinutes(1)
            app.frogRepository.armIfDue()
            app.frogRepository.clearFrog()
            app.frogRepository.selectFrog(FrogTask("launcher-fixture", "Launcher fixture", source = FrogTask.SOURCE_MANUAL))
            app.frogRepository.confirmTools(emptySet())
            assertTrue(app.frogRepository.currentState().locked)
        }

        fun assertReturnedHome(scenario: ActivityScenario<*>) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var homeResumed = false
            while (SystemClock.elapsedRealtime() < deadline) {
                val dump = InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("dumpsys activity activities").let { descriptor ->
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                    }
                homeResumed = dump.lineSequence().any {
                    it.contains("ResumedActivity", ignoreCase = true) && it.contains(home)
                }
                if (homeResumed && scenario.state == Lifecycle.State.DESTROYED) break
                SystemClock.sleep(200)
            }
            assertEquals("Focus page must close", Lifecycle.State.DESTROYED, scenario.state)
            assertTrue("Captured ordinary launcher must be resumed", homeResumed)
        }

        for (complete in listOf(true, false)) {
            for (homeSurface in listOf(false, true)) {
                prepareFocus()
                // Seed a non-launcher activity underneath so merely finishing the
                // blocker cannot accidentally satisfy the Home handoff assertion.
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                SystemClock.sleep(500)
                val intent = if (homeSurface) Intent(app, FrogHomeActivity::class.java) else {
                    Intent(app, BlockerActivity::class.java)
                        .putExtra(BlockerActivity.EXTRA_BLOCK_REASON, "frog")
                        .putExtra(BlockerActivity.EXTRA_BLOCKED_PACKAGE, "com.example.distraction")
                }
                ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
                    SystemClock.sleep(700)
                    assertEquals(Lifecycle.State.RESUMED, scenario.state)
                    if (complete) {
                        app.frogRepository.setTrackedSeconds(60)
                        app.frogRepository.tickOffFrog()
                    } else app.frogRepository.setEnabled(false)
                    assertReturnedHome(scenario)
                }
            }
        }
    }
}
