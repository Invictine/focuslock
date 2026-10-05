package com.focuslock.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.data.repository.CreditBankRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TickTickBackfillBankTest {
    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun cancelIsolatedDataStoreScope() {
        dataStoreScope.cancel()
    }

    @Test
    fun historicalBackfillEarnsCreditWithoutChangingTodayStatsOrSyncMarker() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val isolatedDir = File(appContext.cacheDir, "ticktick-backfill-${UUID.randomUUID()}").apply {
            check(mkdirs() || isDirectory)
        }
        val isolatedDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(isolatedDir, "focuslock_bank.preferences_pb") },
        )
        val bank = CreditBankRepository(appContext, isolatedDataStore)
        val syncMarker = 1_728_000_000_000L
        bank.setLastSyncTimestamp(syncMarker)

        val zone = ZoneId.systemDefault()
        val yesterday = LocalDate.now(zone).minusDays(1)
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val session = TickTickWorkRecord(
            id = "ticktick_focus_0_historical-backfill",
            title = "Historical focus",
            durationMinutes = 25,
            timestamp = yesterday,
            source = WorkRecordSource.TICKTICK_FOCUS_API,
        )

        val initialBalance = bank.getBalanceSeconds()
        val firstBatch = bank.recordWorkCreditsDeduped(
            records = listOf(session),
            workRatio = 4,
            taskBonusMinutes = 0,
        )

        assertEquals(0L, initialBalance)
        assertEquals(1 to 6, firstBatch)
        assertEquals(syncMarker, bank.getLastSyncTimestamp())
        assertEquals(6 * 60L, bank.getBalanceSeconds())
        assertEquals(0, bank.statsFlow.first().totalWorkMinutesToday)
        assertEquals(0, bank.focusMinutesTodayFlow.first())
        assertTrue(bank.fullHistoryFlow.first().any { it.id == session.id })

        val retryBatch = bank.recordWorkCreditsDeduped(
            records = listOf(session),
            workRatio = 4,
            taskBonusMinutes = 0,
        )

        assertEquals(0 to 0, retryBatch)
        assertEquals(6 * 60L, bank.getBalanceSeconds())
        assertEquals(syncMarker, bank.getLastSyncTimestamp())
        val tickTickSyncMarker = syncMarker + 1_000L
        bank.setLastTickTickSyncTimestamp(tickTickSyncMarker)
        assertEquals(tickTickSyncMarker, bank.getLastTickTickSyncTimestamp())
        assertEquals(syncMarker, bank.getLastSyncTimestamp())
    }
}
