package com.focuslock.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class TodayUsageMergeTest {
    private val day = "2026-10-07"

    private fun bucket(key: String, seconds: Long, date: String = day) = UsageBucket(
        date, "app", key, key, trackedSeconds = seconds, updatedAt = 1L,
    )

    private fun target(key: String, seconds: Long) = UsageTarget(
        "app", key, key, seconds, 0L, listOf("android-a"),
    )

    @Test fun repeatedRefreshDoesNotAddAlreadyUploadedDeviceSlice() {
        val summary = UsageSummary(600, emptyList(), targets = listOf(target("a", 600)), deviceTargets = listOf(
            UsageDeviceTarget("android-a", "app", "a", 600),
        ))
        assertEquals(600L, mergeTodayBoundaryUsageSeconds(listOf(bucket("a", 600)), summary, "android-a", setOf("a"), emptySet(), day))
        assertEquals(600L, mergeTodayBoundaryUsageSeconds(listOf(bucket("a", 600)), summary, "android-a", setOf("a"), emptySet(), day))
    }

    @Test fun addsOnlyUnuploadedDeltaPerTargetAcrossDevices() {
        val summary = UsageSummary(2_100, emptyList(), targets = listOf(target("a", 1_000), target("b", 1_100)), deviceTargets = listOf(
            UsageDeviceTarget("android-a", "app", "a", 1_000),
            UsageDeviceTarget("android-a", "app", "b", 500),
        ))
        // b has another 600 seconds on another device; it must not cancel a's 300 local delta.
        assertEquals(2_400L, mergeTodayBoundaryUsageSeconds(
            listOf(bucket("a", 1_300), bucket("b", 500)), summary, "android-a", setOf("a", "b"), emptySet(), day,
        ))
    }

    @Test fun olderBackendUsesMaximumToAvoidDoubleCounting() {
        val summary = UsageSummary(900, emptyList(), targets = listOf(target("a", 900)))
        assertEquals(900L, mergeTodayBoundaryUsageSeconds(listOf(bucket("a", 700)), summary, "android-a", setOf("a"), emptySet(), day))
    }

    @Test fun filtersYesterdayAndMatchesDomainsExactlyOrBySubdomainOnce() {
        val summary = UsageSummary(900, emptyList(), targets = listOf(
            UsageTarget("website", "news.example.com", "News", 500, 0, emptyList()),
            UsageTarget("website", "example.com", "Example", 400, 0, emptyList()),
        ), deviceTargets = emptyList())
        assertEquals(900L, mergeTodayBoundaryUsageSeconds(
            listOf(bucket("a", 700, "2026-10-06")), summary, "android-a", emptySet(), setOf("example.com"), day,
        ))
    }

    @Test fun datedSnapshotRejectsWrongAccountOrDay() {
        val snapshot = TodayUsageSnapshot(day, "account-a", UsageSummary(60, emptyList()))
        assertEquals(snapshot.summary, snapshot.summaryForAccountDate("account-a", day))
        assertEquals(null, snapshot.summaryForAccountDate("account-b", day))
        assertEquals(null, snapshot.summaryForAccountDate("account-a", "2026-10-06"))
        assertEquals(null, snapshot.summaryForAccountDate(null, day))
    }

    @Test fun emptyKnownSummaryIsZeroButNoLocalOrRemoteDataStaysUnavailable() {
        assertEquals(0L, mergeTodayBoundaryUsageSeconds(emptyList(), UsageSummary(0, emptyList()), "android-a", setOf("a"), emptySet(), day))
        assertEquals(null, mergeTodayBoundaryUsageSeconds(null, null, "android-a", setOf("a"), emptySet(), day))
    }
}
