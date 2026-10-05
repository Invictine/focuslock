package com.focuslock.app

import android.os.Build
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.location.DeviceLocationSource
import com.focuslock.app.location.HomeLocationPolicy
import com.focuslock.app.location.HomeLocationStatus
import com.focuslock.app.location.HomePlace
import com.focuslock.app.service.TickTickNotificationListener
import com.focuslock.app.ui.blocker.BlockerActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Seeds a disposable emulator only; never run this fixture on the user's phone. */
@RunWith(AndroidJUnit4::class)
class PermanentHomeOverrideInstrumentationTest {
    @Test
    fun permanentBlockerSurvivesLocationPauseAndCreditBroadcast(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("permanentHomeFixture") == "true")
        assumeTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic"))
        val app = FocusLockApplication.instance
        val locationSource = DeviceLocationSource(app)
        app.settingsRepository.setOfflineMode(true)
        app.homeLocationRepository.saveHome(HomePlace("QA remote home", -80.0, -170.0, 50f))
        assertTrue(app.permanentBlocksRepository.add("com.google.android.youtube", "YouTube"))
        assertTrue(app.permanentBlocksRepository.addWebsite("example.com"))
        val homeOnly = app.homeLocationRepository.homeOnlyFlow.first()
        val status = app.homeLocationRepository.statusNow(requireBackgroundPermission = true)
        val shouldEnforce = app.homeLocationRepository.shouldEnforceNow()
        assertEquals(
            "Unavailable or disabled location must keep ordinary rules enforced",
            HomeLocationPolicy.shouldEnforce(
                homeOnly,
                locationSource.hasPrecisePermission(),
                locationSource.hasBackgroundPermission(),
                status,
            ),
            shouldEnforce,
        )
        // Exercise both permanent-app and permanent-website blocker lifetimes locally.
        // This does not open a browser or contact the test domain.
        for (website in listOf<String?>(null, "child.example.com")) {
            val intent = Intent(app, BlockerActivity::class.java).apply {
                putExtra(BlockerActivity.EXTRA_BLOCKED_PACKAGE,
                    if (website == null) "com.google.android.youtube" else "com.android.chrome")
                putExtra(BlockerActivity.EXTRA_BLOCK_REASON, "permanent")
                website?.let { putExtra(BlockerActivity.EXTRA_BLOCKED_WEBSITE, it) }
            }
            ActivityScenario.launch<BlockerActivity>(intent).use { scenario ->
                app.sendBroadcast(Intent(TickTickNotificationListener.ACTION_CREDIT_UPDATED)
                    .setPackage(app.packageName).putExtra("earnedMinutes", 30))
                // Covers the immediate location check and its subsequent ten-second check.
                SystemClock.sleep(12_000L)
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                scenario.onActivity { assertFalse(it.isFinishing) }
            }
        }
    }

    @Test
    fun permanentTargetsIgnorePausedHomeEnforcement(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("permanentHomeFixture") == "true")
        assumeTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic"))
        val app = FocusLockApplication.instance
        val locationSource = DeviceLocationSource(app)
        app.settingsRepository.setOfflineMode(true)
        app.homeLocationRepository.saveHome(HomePlace("QA remote home", -80.0, -170.0, 50f))
        assertTrue(app.permanentBlocksRepository.add("com.google.android.youtube", "YouTube"))
        assertTrue(app.permanentBlocksRepository.addWebsite("example.com"))
        app.permanentBlocksRepository.warm()
        val status = app.homeLocationRepository.statusNow(requireBackgroundPermission = true)
        assertTrue(status == HomeLocationStatus.AWAY || status == HomeLocationStatus.UNAVAILABLE)
        val homeOnly = app.homeLocationRepository.homeOnlyFlow.first()
        val shouldEnforce = app.homeLocationRepository.shouldEnforceNow()
        assertEquals(
            HomeLocationPolicy.shouldEnforce(
                homeOnly,
                locationSource.hasPrecisePermission(),
                locationSource.hasBackgroundPermission(),
                status,
            ),
            shouldEnforce,
        )
        assertTrue(app.homeLocationRepository.shouldEnforceNow(permanent = true))
        assertTrue(app.settingsRepository.isAppPermanent("com.google.android.youtube"))
        assertTrue(app.settingsRepository.isWebsitePermanent("child.example.com"))
    }
}
