package com.focuslock.app.ui.blocker

import com.focuslock.app.data.repository.BlockEvent
import com.focuslock.app.data.repository.BlockLogRepository
import java.time.LocalDate
import java.time.ZoneId

/** Counts blocked launch attempts for one package over the local day and week. */
data class BlockAttemptCounts(
    val today: Int,
    val week: Int,
    val todayIsLowerBound: Boolean = false,
    val weekIsLowerBound: Boolean = false
)

/**
 * Counts matching entries in [events]. The event log is capped, so a full log
 * whose oldest entry falls inside a period may omit earlier attempts.
 */
fun blockAttemptCounts(
    events: List<BlockEvent>,
    target: String,
    now: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault()
): BlockAttemptCounts {
    val startToday = now.atStartOfDay(zone).toInstant().toEpochMilli()
    val startTomorrow = now.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val startWeek = now.with(java.time.DayOfWeek.MONDAY).atStartOfDay(zone).toInstant().toEpochMilli()
    val oldestTimestamp = events.minOfOrNull { it.timestampMillis }
    val logMayBeTruncated = events.size >= BlockLogRepository.MAX_EVENTS

    val todayEvents = events.count {
        it.packageName == target && it.timestampMillis >= startToday && it.timestampMillis < startTomorrow
    }
    val weekEvents = events.count {
        it.packageName == target && it.timestampMillis >= startWeek && it.timestampMillis < startTomorrow
    }

    return BlockAttemptCounts(
        today = todayEvents,
        week = weekEvents,
        todayIsLowerBound = logMayBeTruncated && oldestTimestamp != null && oldestTimestamp >= startToday,
        weekIsLowerBound = logMayBeTruncated && oldestTimestamp != null && oldestTimestamp >= startWeek
    )
}
