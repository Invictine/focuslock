package com.focuslock.app.service

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.Calendar

object UsageTrackerHelper {

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getAppUsageTodayMinutes(context: Context, packageName: String): Long {
        if (!hasUsageStatsPermission(context)) return 0L

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startTime = startOfTodayMillis()
        val endTime = System.currentTimeMillis()

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: emptyList()

        return (aggregateTodayForeground(stats, startTime, endTime)[packageName]?.foregroundMillis ?: 0L) /
            (1000 * 60)
    }

    /** Local-midnight instant, shared by every today-window usage query. */
    fun startOfTodayMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Per-package aggregated foreground usage for a today window. */
    data class PackageUsage(
        val foregroundMillis: Long,
        val lastUsedTimestamp: Long
    )

    /**
     * Aggregates per-package foreground time from raw UsageStats buckets, corrected for
     * INTERVAL_DAILY bucket misalignment:
     *  - Daily buckets are anchored to device-midnight and can extend beyond the
     *    requested window (span midnight / overlap the edges), so each bucket is
     *    intersected with [start, end]; buckets that don't intersect are skipped and
     *    partially-covered buckets contribute foreground time scaled to the overlap.
     *  - The system can return overlapping buckets for the same package; only the
     *    not-yet-covered portion of each bucket is counted (coverage tracked per
     *    package, buckets processed in begin-time order), so no time is double-counted.
     *
     * Buckets carry their time in [UsageStats.firstTimeStamp]/[lastTimeStamp];
     * [totalTimeInForeground] is distributed proportionally over the bucket length.
     */
    fun aggregateTodayForeground(
        stats: List<UsageStats>,
        start: Long,
        end: Long,
        excludePackage: String? = null
    ): Map<String, PackageUsage> {
        if (end <= start) return emptyMap()

        // Per-package bucket list: (windowStart, windowEnd, rawFgMillis, bucketLen, lastUsed).
        class ClampedBucket(
            val lo: Long,
            val hi: Long,
            val fgMillis: Long,
            val bucketLen: Long,
            val lastUsed: Long
        )

        val perPackage = HashMap<String, MutableList<ClampedBucket>>()
        for (s in stats) {
            val pkg = s.packageName ?: continue
            if (pkg == excludePackage) continue
            val bucketBegin = s.firstTimeStamp
            val bucketEnd = s.lastTimeStamp
            if (bucketEnd <= bucketBegin) continue
            val lo = maxOf(bucketBegin, start)
            val hi = minOf(bucketEnd, end)
            if (hi <= lo) continue // bucket entirely outside the requested window
            val totalFg = s.totalTimeInForeground
            if (totalFg <= 0L) continue
            perPackage.getOrPut(pkg) { mutableListOf() }
                .add(ClampedBucket(lo, hi, totalFg, bucketEnd - bucketBegin, s.lastTimeUsed))
        }

        val result = HashMap<String, PackageUsage>(perPackage.size)
        for ((pkg, buckets) in perPackage) {
            buckets.sortWith(compareBy({ it.lo }, { -it.hi }))
            var coveredUntil = start
            var sumMillis = 0L
            var lastUsed = 0L
            for (b in buckets) {
                val segStart = maxOf(b.lo, coveredUntil)
                val segEnd = b.hi
                if (segEnd <= segStart) continue // already covered by an earlier bucket
                sumMillis += if (b.bucketLen <= 0L) {
                    b.fgMillis
                } else if (segEnd - segStart >= b.bucketLen) {
                    b.fgMillis
                } else {
                    b.fgMillis * (segEnd - segStart) / b.bucketLen
                }
                if (segEnd > coveredUntil) coveredUntil = segEnd
                if (b.lastUsed > lastUsed) lastUsed = b.lastUsed
            }
            if (sumMillis <= 0L) continue
            result[pkg] = PackageUsage(foregroundMillis = sumMillis, lastUsedTimestamp = lastUsed)
        }
        return result
    }
}
