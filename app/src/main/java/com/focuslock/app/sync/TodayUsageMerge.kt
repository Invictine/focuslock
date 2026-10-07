package com.focuslock.app.sync

/** Immutable, date-stamped summary exposed to the dashboard. */
data class TodayUsageSnapshot(val date: String, val accountId: String, val summary: UsageSummary)
data class AccountPermanentTargetsSnapshot(val accountId: String, val targets: List<RemotePermanentBlock>)

internal fun TodayUsageSnapshot?.summaryForAccountDate(accountId: String?, date: String): UsageSummary? =
    this?.takeIf { !accountId.isNullOrBlank() && it.accountId == accountId && it.date == date }?.summary

/**
 * Adds today's locally measured, account-attributed Android usage to the matching
 * account-wide boundary totals. Per-device slices let us subtract the part already
 * uploaded by this Android device, preventing repeated syncs from double-counting it.
 */
internal fun mergeTodayBoundaryUsageSeconds(
    localBuckets: List<UsageBucket>?,
    summary: UsageSummary?,
    deviceId: String?,
    boundaryApps: Set<String>,
    boundaryDomains: Set<String>,
    date: String,
): Long? {
    if (localBuckets == null && summary == null) return null
    val localRows = localBuckets.orEmpty()
    val apps = boundaryApps.mapTo(HashSet()) { it.trim().lowercase() }
    val domains = boundaryDomains.mapNotNullTo(HashSet()) { normalizeUsageDomain(it) }
    fun matches(kind: String, key: String): Boolean {
        val normalizedKey = key.trim().lowercase()
        return when (kind.trim().lowercase()) {
            "app" -> normalizedKey in apps
            "website", "domain" -> {
                val domain = normalizeUsageDomain(normalizedKey) ?: return false
                domains.any { domain == it || domain.endsWith(".$it") }
            }
            else -> false
        }
    }

    val local = localRows.asSequence()
        .filter { it.date == date }
        .filter { matches(it.targetKind, it.targetKey) }
        .sumOf { it.trackedSeconds.coerceAtLeast(0L) }
    if (summary == null) return local

    // A summary target is one account-level row. Filter it once against the union of
    // selected boundary keys so overlapping domains/settings cannot count it twice.
    val cloudByTarget = summary.targets.asSequence()
        .filter { matches(it.targetKind, it.targetKey) }
        .groupBy { usageTargetIdentity(it.targetKind, it.targetKey) }
        .mapValues { (_, rows) -> rows.maxOfOrNull { it.trackedSeconds.coerceAtLeast(0L) } ?: 0L }
    val deviceTargets = summary.deviceTargets
    if (deviceTargets == null || deviceId.isNullOrBlank()) {
        // Older servers omit device provenance. max is the conservative safe merge:
        // summing could duplicate all of today's Android usage after every refresh.
        return maxOf(local, cloudByTarget.values.sum())
    }
    val localByTarget = localRows.asSequence()
        .filter { it.date == date && matches(it.targetKind, it.targetKey) }
        .groupBy { usageTargetIdentity(it.targetKind, it.targetKey) }
        .mapValues { (_, rows) -> rows.maxOfOrNull { it.trackedSeconds.coerceAtLeast(0L) } ?: 0L }
    val uploadedByTarget = deviceTargets.asSequence()
        .filter { it.deviceId == deviceId && matches(it.targetKind, it.targetKey) }
        .groupBy { usageTargetIdentity(it.targetKind, it.targetKey) }
        .mapValues { (_, rows) -> rows.maxOfOrNull { it.trackedSeconds.coerceAtLeast(0L) } ?: 0L }
    val keys = cloudByTarget.keys + localByTarget.keys
    return keys.sumOf { key ->
        (cloudByTarget[key] ?: 0L) +
            ((localByTarget[key] ?: 0L) - (uploadedByTarget[key] ?: 0L)).coerceAtLeast(0L)
    }
}

private fun usageTargetIdentity(kind: String, key: String): String =
    "${kind.trim().lowercase()}:${key.trim().lowercase().removePrefix("www.")}"

private fun normalizeUsageDomain(value: String): String? = value.trim().lowercase()
    .removePrefix("https://").removePrefix("http://").substringBefore('/')
    .substringBefore(':').removePrefix("www.").takeIf(String::isNotBlank)
