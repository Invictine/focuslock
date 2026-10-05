package com.focuslock.app.service

import android.content.Context
import com.focuslock.app.FocusLockApplication
import kotlinx.coroutines.CancellationException

/** Shared merged-group verdict for foreground apps and notification filtering. */
object GroupLimitPolicy {
    suspend fun isExceeded(context: Context, app: FocusLockApplication, kind: String, key: String): Boolean {
        val groups = if (kind == "website") app.targetGroupsRepository.groupsForWebsiteHost(key)
            else app.targetGroupsRepository.groupsForTarget(kind, key)
        val serverUsage = app.syncManager.groupUsageTodaySeconds.value
        for (group in groups) {
            val limit = group.dailyLimitMinutes ?: continue
            if (!group.limitEnabled || limit <= 0) continue
            var localSeconds = 0L
            for (member in group.members) {
                if (member.targetKind != "app") continue
                localSeconds += try {
                    UsageStatsRepository.getMinutesForPackage(context, member.targetKey) * 60L
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { 0L }
            }
            if (maxOf(localSeconds, serverUsage[group.groupId] ?: 0L) >= limit * 60L) return true
        }
        return false
    }
}
