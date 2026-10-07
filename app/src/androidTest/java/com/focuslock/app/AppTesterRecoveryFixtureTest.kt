package com.focuslock.app

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.service.AppUpdateAccessPolicy
import com.focuslock.app.service.FrogAppPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Seeds only a disposable emulator; host backs up/restores stores before native checks. */
@RunWith(AndroidJUnit4::class)
class AppTesterRecoveryFixtureTest {
    @Test fun seedLockedFrogForNativeAppTesterCheck(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appTesterFixture") == "true")
        assumeTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic"))
        val app = FocusLockApplication.instance
        app.settingsRepository.setOfflineMode(true)
        app.homeLocationRepository.setHomeOnly(false)
        app.frogRepository.setEnabled(true)
        app.frogRepository.setWakeHour(0)
        app.frogRepository.armIfDue()
        app.frogRepository.clearFrog()
        val state = app.frogRepository.currentState()
        assertTrue(state.locked)
        assertFalse(FrogAppPolicy.isBlocked(app, AppUpdateAccessPolicy.APP_TESTER_PACKAGE, state))
    }
}
