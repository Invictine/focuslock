package com.focuslock.app

import android.util.Log
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.ui.MainActivity
import com.focuslock.app.service.AppMonitorForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live check that the foreground service syncs after MainActivity stops. */
@RunWith(AndroidJUnit4::class)
class TickTickBackgroundSyncLiveTest {
    @Test
    fun foregroundServiceContinuesTickTickSyncWhileMainActivityIsStopped(): Unit = runBlocking {
        assumeTrue(
            "Pass liveTickTick=true to run the connected-account check",
            InstrumentationRegistry.getArguments().getString("liveTickTick") == "true",
        )
        val app = InstrumentationRegistry.getInstrumentation().targetContext
            .applicationContext as FocusLockApplication
        val token = app.settingsRepository.tickTickTokenFlow.first().trim()
        assumeTrue("Connect TickTick in FocusLock before the live check", token.isNotBlank())

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // Instrumentation force-stops the target before launch, which can mark
            // its enabled accessibility service as crashed. Start the monitor from
            // the visible activity so this test exercises the background sync job
            // independently of that permission-binding side effect.
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, AppMonitorForegroundService::class.java))
            }
            // Let the resumed activity and the foreground service complete their startup sync.
            withTimeout(INITIAL_SYNC_TIMEOUT_MS) {
                while (app.creditBankRepository.getLastTickTickSyncTimestamp() <= 0L) {
                    delay(POLL_INTERVAL_MS)
                }
            }

            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(Lifecycle.State.CREATED, scenario.state)
            delay(SETTLE_AFTER_STOP_MS)
            val baseline = app.creditBankRepository.getLastTickTickSyncTimestamp()
            Log.i(TAG, "Background TickTick sync baseline=$baseline activityState=${scenario.state}")

            val laterTimestamp = withTimeout(BACKGROUND_SYNC_TIMEOUT_MS) {
                var timestamp = baseline
                while (timestamp <= baseline) {
                    delay(POLL_INTERVAL_MS)
                    timestamp = app.creditBankRepository.getLastTickTickSyncTimestamp()
                }
                timestamp
            }

            assertTrue("TickTick's sync timestamp should advance while MainActivity is stopped", laterTimestamp > baseline)
            assertEquals("The test must leave MainActivity stopped", Lifecycle.State.CREATED, scenario.state)
            Log.i(TAG, "Background TickTick sync observed baseline=$baseline later=$laterTimestamp activityState=${scenario.state}")
        } finally {
            scenario.close()
        }
    }

    private companion object {
        const val TAG = "TickTickBackgroundLiveTest"
        const val INITIAL_SYNC_TIMEOUT_MS = 75_000L
        const val BACKGROUND_SYNC_TIMEOUT_MS = 75_000L
        const val SETTLE_AFTER_STOP_MS = 5_000L
        const val POLL_INTERVAL_MS = 1_000L
    }
}
