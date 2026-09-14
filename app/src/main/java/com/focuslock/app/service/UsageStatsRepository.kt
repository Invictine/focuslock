package com.focuslock.app.service

import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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

    private const val PACKAGE_CACHE_TTL_MS = 30_000L
    private const val SUMMARY_CACHE_TTL_MS = 30_000L

    // package -> (queriedAtMillis, usedMinutes). Prevents queryUsageStats from
    // re-running on every foreground event / limit check within the TTL window.
    // (Fallback only: the shared summary aggregate below serves the hot path.)
    private val packageUsageCache = mutableMapOf<String, Pair<Long, Long>>()
    private val packageUsageMutex = Mutex()

    // Process-wide label fallbacks resolved via PackageManager so cold-cache
    // summaries don't repeat a PM lookup per row per call.
    private val labelCache = ConcurrentHashMap<String, String>()

    /**
     * Full-day aggregate cached for [SUMMARY_CACHE_TTL_MS], keyed by day: the dashboard
     * resume, AppSelector load, 30s sync loop and per-app limit checks all share ONE
     * UsageStats query instead of re-querying on every call. [maxApps] slicing happens
     * per call against the cached full entry list. [rawPerPackageMillis] keeps the
     * unfiltered per-package foreground time (incl. our own app / sub-30s rows) so
     * limit checks see exact totals.
     */
    private class SummaryAggregate(
        val dayStart: Long,
        val queriedAtMs: Long,
        val totalScreenMinutes: Long,
        val entries: List<AppUsageEntry>,
        val appCount: Int,
        val rawPerPackageMillis: Map<String, UsageTrackerHelper.PackageUsage>
    )

    @Volatile
    private var summaryCache: SummaryAggregate? = null

    // Single-flight: concurrent getTodaySummary / getMinutesForPackage calls share one
    // query — the mutex is held only across the heavy query and the cache is re-checked
    // inside the lock, so a second caller awaits the first rather than double-querying.
    private val summaryMutex = Mutex()

    suspend fun getTodaySummary(
        context: Context,
        maxApps: Int = 15
    ): DailyUsageSummary = withContext(Dispatchers.IO) {
        val aggregate = try {
            getSummaryAggregate(context)
        } catch (_: Exception) {
            null
        } ?: return@withContext DailyUsageSummary(0L, emptyList(), 0)
        DailyUsageSummary(
            totalScreenMinutes = aggregate.totalScreenMinutes,
            topApps = aggregate.entries.take(maxApps),
            appCount = aggregate.appCount
        )
    }

    /** Cached full-day aggregate; refreshed at most once per [SUMMARY_CACHE_TTL_MS]. */
    private suspend fun getSummaryAggregate(context: Context): SummaryAggregate {
        val dayStart = UsageTrackerHelper.startOfTodayMillis()
        summaryCache?.takeIf { isFresh(it, dayStart) }?.let { return it }
        return summaryMutex.withLock {
            // Re-check: another caller may have refreshed the cache while we waited.
            summaryCache?.takeIf { isFresh(it, dayStart) }?.let { return it }
            querySummaryAggregate(context, dayStart).also { summaryCache = it }
        }
    }

    private fun isFresh(cache: SummaryAggregate, dayStart: Long): Boolean =
        cache.dayStart == dayStart &&
            System.currentTimeMillis() - cache.queriedAtMs < SUMMARY_CACHE_TTL_MS

    private suspend fun querySummaryAggregate(context: Context, dayStart: Long): SummaryAggregate {
        if (!UsageTrackerHelper.hasUsageStatsPermission(context)) {
            return SummaryAggregate(
                dayStart = dayStart,
                queriedAtMs = System.currentTimeMillis(),
                totalScreenMinutes = 0L,
                entries = emptyList(),
                appCount = 0,
                rawPerPackageMillis = emptyMap()
            )
        }
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, end) ?: emptyList()
            // Bucket-aligned + de-overlapped per-package foreground totals.
            val raw = UsageTrackerHelper.aggregateTodayForeground(stats, dayStart, end)

            var totalMs = 0L
            val entries = mutableListOf<AppUsageEntry>()
            // Hot-path fix: resolve labels from the InstalledApps memory cache first; fall
            // back to a process-wide label cache, then a single PackageManager lookup that
            // is retained for future calls. No per-row PM lookup per summary.
            val labelMap = InstalledAppsRepository.getCachedLabelMap()
            for ((pkg, usage) in raw) {
                val fg = usage.foregroundMillis
                // Skip our own app and system launcher noise under 30s (display only —
                // raw totals above stay complete for limit checks).
                if (pkg == context.packageName) continue
                if (fg < 30_000L) continue
                totalMs += fg
                val label = labelMap[pkg]
                    ?: labelCache.computeIfAbsent(pkg) {
                        InstalledAppsRepository.getAppLabel(context, it)
                    }
                entries.add(
                    AppUsageEntry(
                        packageName = pkg,
                        appName = label,
                        foregroundMinutes = fg / 60_000L,
                        lastUsedTimestamp = usage.lastUsedTimestamp
                    )
                )
            }
            SummaryAggregate(
                dayStart = dayStart,
                queriedAtMs = System.currentTimeMillis(),
                totalScreenMinutes = totalMs / 60_000L,
                entries = entries.sortedByDescending { it.foregroundMinutes },
                appCount = entries.size,
                rawPerPackageMillis = raw
            )
        } catch (_: Exception) {
            SummaryAggregate(
                dayStart = dayStart,
                queriedAtMs = System.currentTimeMillis(),
                totalScreenMinutes = 0L,
                entries = emptyList(),
                appCount = 0,
                rawPerPackageMillis = emptyMap()
            )
        }
    }

    /**
     * Today's foreground minutes for one package. Served from the shared day aggregate
     * (one UsageStats query per TTL window for ALL callers); falls back to a direct
     * per-package query only when the aggregate path fails, still cached for
     * [PACKAGE_CACHE_TTL_MS].
     */
    suspend fun getMinutesForPackage(context: Context, packageName: String): Long {
        val aggregate = try {
            getSummaryAggregate(context)
        } catch (_: Exception) {
            null
        }
        if (aggregate != null && aggregate.dayStart == UsageTrackerHelper.startOfTodayMillis()) {
            return aggregate.rawPerPackageMillis[packageName]?.foregroundMillis?.div(60_000L) ?: 0L
        }
        val cached = packageUsageMutex.withLock {
            packageUsageCache[packageName]
                ?.takeIf { System.currentTimeMillis() - it.first < PACKAGE_CACHE_TTL_MS }
                ?.second
        }
        if (cached != null) return cached
        val minutes = try {
            UsageTrackerHelper.getAppUsageTodayMinutes(context, packageName)
        } catch (_: Exception) {
            0L
        }
        packageUsageMutex.withLock {
            packageUsageCache[packageName] = System.currentTimeMillis() to minutes
        }
        return minutes
    }

    /** Drops cached usage for [packageName], or the whole cache when null. */
    suspend fun invalidatePackageUsage(packageName: String? = null) {
        packageUsageMutex.withLock {
            if (packageName == null) packageUsageCache.clear() else packageUsageCache.remove(packageName)
        }
        summaryCache = null
    }

    fun formatDuration(totalMinutes: Long): String {
        if (totalMinutes <= 0) return "0m"
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
