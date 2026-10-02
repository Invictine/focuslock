package com.focuslock.app.service

import com.focuslock.app.data.model.BlockedApp

/** Pure calculation of today's foreground milliseconds for apps selected in Boundaries. */
object BoundaryLeisureUsage {
    fun selectedPackageNames(boundaryApps: Collection<BlockedApp>): Set<String> =
        boundaryApps.asSequence()
            .filter { it.isBlocked || it.isPermanent }
            .map { it.packageName }
            .filter(String::isNotBlank)
            .toSet()

    fun foregroundMillis(
        boundaryApps: Collection<BlockedApp>,
        rawPerPackageMillis: Map<String, Long>
    ): Long {
        val selected = selectedPackageNames(boundaryApps)
        return selected.sumOf { rawPerPackageMillis[it]?.coerceAtLeast(0L) ?: 0L }
    }
}
