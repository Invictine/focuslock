package com.focuslock.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.repository.PermanentBlocksRepository
import com.focuslock.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on a disposable emulator: permanently blocks Chrome for lifecycle QA. */
@RunWith(AndroidJUnit4::class)
class EnforcementFixtureTest {
    @Test fun seedAndVerifyMultiDayCommitment() = runBlocking {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("qaFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        settings.setOfflineMode(true)
        val end = System.currentTimeMillis() + 3 * 24 * 60 * 60 * 1000L
        settings.setLockdownMode(true, end)
        settings.setLockdownMode(false)
        settings.setLockdownMode(true, end - 60_000)
        assertEquals(end, settings.lockdownEndsAtFlow.first())
        val blocks = PermanentBlocksRepository(context)
        assertTrue(blocks.add("com.android.chrome"))
        assertTrue(blocks.add("com.android.chrome"))
        blocks.warm()
        assertTrue(blocks.isPermanentlyBlocked("com.android.chrome"))
    }
}
