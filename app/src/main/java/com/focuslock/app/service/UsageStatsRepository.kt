package com.focuslock.app.service

import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

data class AppUsageEntry(
    val packageName: String,
    val appName: String,
    val foregroundMinutes: Long,
    val lastUsedTimestamp: Long = 0L
)

data class DailyUsageSummary(
    val totalScreenMinutes: Long,
    val topApps: List<AppUsageEntry>,
    val appCount: Int
)

object UsageStatsRepository {

    suspend fun getTodaySummary(
        context: Context,
        maxApps: Int = 15
    ): DailyUsageSummary = withContext(Dispatchers.IO) {
        if (!UsageTrackerHelper.hasUsageStatsPermission(context)) {
            return@withContext DailyUsageSummary(0L, emptyList(), 0)
        }
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) ?: emptyList()

            var totalMs = 0L
            val entries = mutableListOf<AppUsageEntry>()
            for (s in stats) {
                val fg = s.totalTimeInForeground
                if (fg <= 0) continue
                // Skip our own app and system launcher noise under 30s
                if (s.packageName == context.packageName) continue
                if (fg < 30_000L) continue
                totalMs += fg
                entries.add(
                    AppUsageEntry(
                        packageName = s.packageName,
                        appName = InstalledAppsRepository.getAppLabel(context, s.packageName),
                        foregroundMinutes = fg / 60_000L,
                        lastUsedTimestamp = s.lastTimeUsed
                    )
                )
            }
            val sorted = entries.sortedByDescending { it.foregroundMinutes }.take(maxApps)
            DailyUsageSummary(
                totalScreenMinutes = totalMs / 60_000L,
                topApps = sorted,
                appCount = entries.size
            )
        } catch (_: Exception) {
            DailyUsageSummary(0L, emptyList(), 0)
        }
    }

    suspend fun getMinutesForPackage(context: Context, packageName: String): Long = withContext(Dispatchers.IO) {
        try {
            UsageTrackerHelper.getAppUsageTodayMinutes(context, packageName)
        } catch (_: Exception) {
            0L
        }
    }

    fun formatDuration(totalMinutes: Long): String {
        if (totalMinutes <= 0) return "0m"
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
