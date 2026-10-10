package com.focuslock.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.UserStats
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.data.repository.FrogRepository
import com.focuslock.app.data.repository.frogCycleDate
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Repository integration coverage using only isolated state and injected focus history. */
@RunWith(AndroidJUnit4::class)
class FrogDailyFocusExemptionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "frog-focus-exemption-${UUID.randomUUID()}")
    private val job = SupervisorJob()
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(job + Dispatchers.IO),
        produceFile = { File(directory, "frog.preferences_pb").apply { parentFile?.mkdirs() } },
    )
    private val now = System.currentTimeMillis()
    private val history = MutableStateFlow<List<TickTickWorkRecord>>(emptyList())
    private val stats = MutableStateFlow<UserStats?>(null)
    private val clock = MutableStateFlow(now)
    private val boundaries = MutableStateFlow<Set<String>>(emptySet())
    private val repository = FrogRepository(
        context = context,
        frogStore = store,
        boundaryPackages = boundaries,
        focusHistoryFlow = history,
        focusStatsFlow = stats,
        clockMillisFlow = clock,
    )

    @After
    fun cleanup(): Unit = runBlocking {
        job.cancelAndJoin()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun overThirtyMinutesReleasesCurrentAndFlowStateWithoutCompletingOrDisablingFrog() = runBlocking {
        seedFrog(armed = true)
        assertTrue(repository.isLockActive(now))

        history.value = listOf(focusRecord(31, now))
        assertFalse(repository.isLockActive(now))
        val current = repository.currentState(now)
        assertTrue(current.dailyExempt)
        assertFalse(current.locked)
        assertFalse(current.tickedOff)
        assertTrue(current.enabled)

        val flowing = repository.frogStateFlow.first { it.dailyExempt }
        assertFalse(flowing.locked)
        assertFalse(flowing.tickedOff)
        assertTrue(flowing.enabled)

        history.value = emptyList()
        val afterRemovingFocus = repository.currentState(now)
        assertFalse(afterRemovingFocus.dailyExempt)
        assertTrue(afterRemovingFocus.locked)

        history.value = listOf(focusRecord(31, now))
        assertTrue(repository.currentState(now).dailyExempt)
        val zone = ZoneId.systemDefault()
        val nextCalendarDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .plusDays(1).atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
        clock.value = nextCalendarDay
        val nextDayState = repository.frogStateFlow.first { !it.dailyExempt }
        assertTrue("The still-armed Frog resumes after the calendar-day exemption expires", nextDayState.locked)
        assertTrue(nextDayState.enabled)
        assertFalse(nextDayState.tickedOff)
    }

    @Test
    fun allArmPathsRefuseToArmDuringDailyExemptionIncludingExpiredGrace() = runBlocking {
        seedFrog(armed = false, graceStartedAt = now - FROG_GRACE_MILLIS)
        history.value = listOf(focusRecord(31, now))

        assertFalse(repository.startGraceIfDue(now))
        assertFalse(repository.armIfDue(now))
        assertFalse(repository.armAfterGraceIfDue(now))
        val prefs = store.data.first()
        assertFalse(prefs[FrogRepository.Keys.FROG_ARMED] ?: false)
        assertEquals(now - FROG_GRACE_MILLIS, prefs[FrogRepository.Keys.FROG_GRACE_STARTED_AT])
        assertTrue(prefs[FrogRepository.Keys.FROG_ENABLED] ?: false)
    }

    @Test
    fun sameDayAggregateCoversHistoryTruncationAndStaleAggregateDoesNotRelease() = runBlocking {
        seedFrog(armed = true)
        history.value = (0 until 20).map { index -> focusRecord(1, now - index * 60_000L) }
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        stats.value = UserStats(totalWorkMinutesToday = 51, lastResetDate = today)

        val aggregated = repository.currentState(now)
        assertTrue(aggregated.dailyExempt)
        assertFalse(aggregated.locked)

        history.value = emptyList()
        stats.value = UserStats(totalWorkMinutesToday = 51, lastResetDate = "2000-01-01")
        val stale = repository.currentState(now)
        assertFalse(stale.dailyExempt)
        assertTrue(stale.locked)
    }

    private suspend fun seedFrog(armed: Boolean, graceStartedAt: Long? = null) {
        val cycleDate = frogCycleDate(now, wakeHour = 5, zoneId = ZoneId.systemDefault())
        store.edit { prefs ->
            prefs[FrogRepository.Keys.FROG_ENABLED] = true
            prefs[FrogRepository.Keys.FROG_WAKE_HOUR] = 5
            prefs[FrogRepository.Keys.FROG_CYCLE_DATE] = cycleDate
            prefs[FrogRepository.Keys.FROG_ARMED] = armed
            prefs[FrogRepository.Keys.FROG_TICKED_OFF] = false
            prefs[FrogRepository.Keys.FROG_TRACKED_SECONDS] = 0
            if (graceStartedAt != null) prefs[FrogRepository.Keys.FROG_GRACE_STARTED_AT] = graceStartedAt
        }
    }

    private fun focusRecord(minutes: Int, timestamp: Long) = TickTickWorkRecord(
        id = "focus-$minutes-$timestamp",
        title = "Focus session",
        durationMinutes = minutes,
        timestamp = timestamp,
        source = WorkRecordSource.TICKTICK_FOCUS_API,
    )

    private companion object {
        const val FROG_GRACE_MILLIS = 10 * 60 * 1000L
    }
}
