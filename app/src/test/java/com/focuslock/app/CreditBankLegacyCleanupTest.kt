package com.focuslock.app

import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.data.repository.CreditBankRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class CreditBankLegacyCleanupTest {

    @Test
    fun removesKnownLegacyNotificationAwardsOnceAndKeepsManualFocusHistory() {
        val now = System.currentTimeMillis()
        val notifications = (0 until 15).map { index ->
            TickTickWorkRecord(
                id = "notif_$index",
                title = "Focus",
                durationMinutes = 15,
                timestamp = now,
                source = WorkRecordSource.TICKTICK_NOTIFICATION,
                earnedMinutesCredited = 8,
            )
        }
        val manual = TickTickWorkRecord(
            id = "manual",
            title = "Logged focus",
            durationMinutes = 25,
            timestamp = now,
            source = WorkRecordSource.MANUAL_ENTRY,
            earnedMinutesCredited = 25,
        )

        val first = CreditBankRepository.cleanLegacyNotificationCredits(
            history = notifications + manual,
            balanceSeconds = 6_802L,
            workSecondsToday = 13_500L,
            lastResetDate = "",
            today = today(now),
        )

        assertEquals(0L, first.balanceSeconds) // 6802 - 15 * 8 * 60, clamped at zero.
        assertEquals(0L, first.workSecondsToday) // Blank legacy date: remove the known 15 x 15m batch.
        assertEquals(15, first.history.count { it.source == WorkRecordSource.TICKTICK_NOTIFICATION && it.earnedMinutesCredited == 0 })
        assertTrue(CreditBankRepository.isFocusRecord(manual.source, manual.durationMinutes))
        assertEquals(manual, first.history.last())
        assertTrue(first.changed)

        val second = CreditBankRepository.cleanLegacyNotificationCredits(
            history = first.history,
            balanceSeconds = first.balanceSeconds,
            workSecondsToday = first.workSecondsToday,
            lastResetDate = "",
            today = today(now),
        )
        assertFalse(second.changed)
        assertEquals(first.balanceSeconds, second.balanceSeconds)
        assertEquals(first.workSecondsToday, second.workSecondsToday)
    }

    @Test
    fun notificationAndTickTickTaskSourcesCannotEarnFocusCredit() {
        assertFalse(CreditBankRepository.isFocusRecord(WorkRecordSource.TICKTICK_NOTIFICATION, 15))
        assertFalse(CreditBankRepository.isFocusRecord(WorkRecordSource.TICKTICK_APP_FOCUS, 25))
        assertFalse(CreditBankRepository.isFocusRecord(WorkRecordSource.TICKTICK_API, 60))
        assertFalse(CreditBankRepository.isFocusRecord(WorkRecordSource.MANUAL_ENTRY, 0))
        assertTrue(CreditBankRepository.isFocusRecord(WorkRecordSource.MANUAL_ENTRY, 1))
        assertTrue(CreditBankRepository.isFocusRecord(WorkRecordSource.TICKTICK_FOCUS_API, 25))
        assertFalse(CreditBankRepository.isFocusRecord(WorkRecordSource.TICKTICK_FOCUS_API, 0))
    }

    @Test
    fun datedDailyWorkCleanupOnlySubtractsSameDayLegacySessions() {
        val todayMillis = System.currentTimeMillis()
        val yesterdayMillis = todayMillis - 2L * 24L * 60L * 60L * 1000L
        val history = listOf(
            record("today", todayMillis),
            record("old", yesterdayMillis),
        )
        val result = CreditBankRepository.cleanLegacyNotificationCredits(
            history = history,
            balanceSeconds = 1_000L,
            workSecondsToday = 1_800L,
            lastResetDate = today(todayMillis),
            today = today(todayMillis),
        )

        assertEquals(40L, result.balanceSeconds) // Both known awards are removed from carryover.
        assertEquals(900L, result.workSecondsToday) // Only today's 15 minutes leave today's counter.
    }

    private fun record(id: String, timestamp: Long) = TickTickWorkRecord(
        id = id,
        title = "Focus",
        durationMinutes = 15,
        timestamp = timestamp,
        source = WorkRecordSource.TICKTICK_NOTIFICATION,
        earnedMinutesCredited = 8,
    )

    private fun today(timestamp: Long): String =
        java.time.Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))
}
