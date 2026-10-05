package com.focuslock.app

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.Criteria
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.location.HomeLocationStatus
import com.focuslock.app.location.HomePlace
import com.focuslock.app.service.FrogHomeLauncher
import com.focuslock.app.ui.blocker.FrogHomeActivity
import com.focuslock.app.ui.blocker.BlockerActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in disposable emulator fixture. Host runner backs up and restores app preference files. */
@RunWith(AndroidJUnit4::class)
class FrogAwayHomeInstrumentationTest {
    @Suppress("DEPRECATION")
    @Test fun leavingHomeClosesBothFrogSurfacesAndOpensOrdinaryLauncher() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("frogAwayFixture") == "true")
        assumeTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic"))
        val app = FocusLockApplication.instance
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ordinaryHome = app.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        )!!.activityInfo.packageName
        assertNotEquals(app.packageName, ordinaryHome)
        FrogHomeLauncher.captureFallback(app)
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false,
            true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        fun move(latitude: Double, longitude: Double) {
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
                this.latitude = latitude
                this.longitude = longitude
                accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            SystemClock.sleep(250)
        }
        fun waitForDestroyed(scenario: ActivityScenario<*>) {
            val deadline = SystemClock.elapsedRealtime() + 25_000
            while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250)
            }
            assertEquals("Frog page must leave the activity stack while away", Lifecycle.State.DESTROYED, scenario.state)
            val homeDeadline = SystemClock.elapsedRealtime() + 5_000
            var ordinaryHomeResumed = false
            while (!ordinaryHomeResumed && SystemClock.elapsedRealtime() < homeDeadline) {
                val activityDump = InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("dumpsys activity activities").let { descriptor ->
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                    }
                ordinaryHomeResumed = activityDump.lineSequence().any {
                    it.contains("ResumedActivity", ignoreCase = true) && it.contains(ordinaryHome)
                }
                if (!ordinaryHomeResumed) SystemClock.sleep(250)
            }
            assertTrue("Ordinary launcher should be resumed", ordinaryHomeResumed)
        }
        try {
            app.frogRepository.setEnabled(true)
            app.frogRepository.setWakeHour(0)
            app.frogRepository.armIfDue()
            app.frogRepository.selectFrog(FrogTask("emulator-away-test", "Emulator study task"))
            app.homeLocationRepository.saveHome(HomePlace("Emulator home", 0.0, 0.0, 150f))
            move(0.0, 0.0)
            assertEquals(HomeLocationStatus.AT_HOME, app.homeLocationRepository.statusNow(true))
            assertTrue(app.homeLocationRepository.shouldEnforceNow())
            ActivityScenario.launch(FrogHomeActivity::class.java).use { scenario ->
                SystemClock.sleep(1_000)
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                move(1.0, 1.0)
                assertEquals(HomeLocationStatus.AWAY, app.homeLocationRepository.statusNow(true))
                assertFalse(app.homeLocationRepository.shouldEnforceNow())
                waitForDestroyed(scenario)
            }
            // A subsequent Home request while away must immediately hand off as well.
            ActivityScenario.launch(FrogHomeActivity::class.java).use(::waitForDestroyed)
            val blockerIntent = Intent(app, BlockerActivity::class.java)
                .putExtra(BlockerActivity.EXTRA_BLOCKED_PACKAGE, "com.android.chrome")
                .putExtra(BlockerActivity.EXTRA_BLOCK_REASON, "frog")
            ActivityScenario.launch<BlockerActivity>(blockerIntent).use(::waitForDestroyed)
            assertTrue("Leaving home pauses Frog without completing the task", app.frogRepository.currentState().locked)
        } finally {
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        }
    }
}
