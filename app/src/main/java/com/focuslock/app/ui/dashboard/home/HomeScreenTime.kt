package com.focuslock.app.ui.dashboard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focuslock.app.data.repository.TargetGroup
import com.focuslock.app.service.AppUsageEntry
import com.focuslock.app.service.UsageStatsRepository
import com.focuslock.app.ui.components.AppIconTileForPackage
import com.focuslock.app.ui.components.IconBadge
import com.focuslock.app.ui.components.SectionHeader
import com.focuslock.app.ui.components.formatUsageSeconds

/**
 * SCREEN_TIME ("Screen Time", iOS Settings proven pattern): today's real total
 * screen time as the hero number, an hourly focus chart (the repo exposes no
 * hourly screen-time buckets — only day totals + per-app foreground minutes, so
 * the hourly chart below is real focus-record buckets, honestly labeled), a
 * "Most used" list with per-app usage bars from real UsageStats data, then the
 * tasks card + Log/Timer/Tasks actions. No fabricated numbers anywhere.
 */
fun LazyListScope.HomeScreenTimeContent(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
) {
    HeaderItem(state, callbacks, screenKey = "home_screen_time")
    SetupBannerItem(state, callbacks, screenKey = "home_screen_time")

    item(key = "usage-hero") {
        HomeEntrance(index = 2, screenKey = "home_screen_time") {
            ScreenTimeHero(state = state, callbacks = callbacks)
        }
    }

    DayChartItem(state, entranceIndex = 3, screenKey = "home_screen_time")

    // Cheap cross-device snapshot straight from the 30s sync cache (no network here).
    item(key = "cross-device-today") {
        HomeEntrance(index = 4, screenKey = "home_screen_time") {
            CrossDeviceTodayCard(state = state, onSeeAll = callbacks.onOpenAccount)
        }
    }

    item(key = "most-used-header") {
        HomeEntrance(index = 5, screenKey = "home_screen_time") {
            val count = state.usageSummary?.topApps?.size ?: 0
            SectionHeader(
                title = "Most used",
                trailing = {
                    if (count > 0) {
                        Text(
                            text = "$count apps",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }

    val topApps = state.usageSummary?.topApps.orEmpty()
    // Merged-group awareness for the local rows: "app:key" -> group, mirroring the
    // repository's lowercase member keys (the group list itself is a local snapshot).
    val groupByAppKey = state.crossDeviceGroups
        .flatMap { group ->
            group.members.mapNotNull { member ->
                if (member.targetKind == "app") member.targetKey to group else null
            }
        }
        .toMap()
    val mostUsedEntries = topApps.map { app ->
        val group = groupByAppKey[app.packageName.lowercase()]
        MostUsedEntry(
            app = app,
            group = group,
            groupSeconds = group?.let { state.groupUsageTodaySeconds[it.groupId] ?: 0L } ?: 0L,
        )
    }
    // Bars scale against whichever value each row actually shows (local minutes or the
    // bigger cumulative group total), so a merged row never overflows its bar.
    val maxDisplaySeconds = (mostUsedEntries.maxOfOrNull { it.displaySeconds } ?: 0L)
        .coerceAtLeast(1L)

    if (state.usageSummary != null && topApps.isEmpty()) {
        item(key = "most-used-empty") {
            Box(Modifier.animateItem()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (state.isUsageAccessGranted != true) {
                            "Grant Usage Access to see per-app screen time."
                        } else {
                            "No app usage recorded yet today."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    } else {
        items(mostUsedEntries, key = { "usage-${it.app.packageName}" }) { entry ->
            Box(Modifier.animateItem()) {
                MostUsedRow(
                    entry = entry,
                    maxSeconds = maxDisplaySeconds,
                )
            }
        }
    }

    item(key = "st-tasks") {
        HomeEntrance(index = 5, screenKey = "home_screen_time") {
            TasksSummaryCard(state = state, callbacks = callbacks)
        }
    }

    ActionsItem(callbacks, entranceIndex = 6, key = "st-actions", screenKey = "home_screen_time")
    HistoryItems(state, entranceIndex = 7, screenKey = "home_screen_time")
    BankItem(state)
}

@Composable
private fun ScreenTimeHero(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Today",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            val summary = state.usageSummary
            when {
                state.isUsageAccessGranted != true -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(
                            icon = Icons.Rounded.PhoneAndroid,
                            size = 44.dp,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Screen time hidden",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "Grant Usage Access to see today's total.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = callbacks.onNavigatePermissions,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("Grant Usage Access", style = MaterialTheme.typography.labelLarge)
                    }
                }
                summary == null -> {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Reading today's screen time…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                else -> {
                    Text(
                        text = UsageStatsRepository.formatDuration(summary.totalScreenMinutes),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (summary.appCount > 0) {
                            "Screen time today · across ${summary.appCount} apps"
                        } else {
                            "Screen time today · no app usage yet"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * One "Most used" row: the local UsageStats entry plus its merged group when the package
 * belongs to one. [groupSeconds] is the cached cross-device total (0 = not synced yet).
 */
private data class MostUsedEntry(
    val app: AppUsageEntry,
    val group: TargetGroup?,
    val groupSeconds: Long,
) {
    /** Cumulative cross-device seconds when available, else the local minutes. */
    val displaySeconds: Long
        get() = if (group != null && groupSeconds > 0L) groupSeconds else app.foregroundMinutes * 60L
}

/**
 * Compact "today across devices" card fed entirely by the sync cache
 * ([FocusHomeState.groupUsageTodaySeconds] + the merged groups). Hidden when there is no
 * synced data, so it never renders a fabricated zero; the Account tab owns the sign-in
 * hint and the full range selector.
 */
@Composable
private fun CrossDeviceTodayCard(
    state: FocusHomeState,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val topGroups = remember(state.crossDeviceGroups, state.groupUsageTodaySeconds) {
        state.crossDeviceGroups
            .map { it to (state.groupUsageTodaySeconds[it.groupId] ?: 0L) }
            .filter { it.second > 0L }
            .sortedByDescending { it.second }
            .take(3)
    }
    val hasData = state.totalCrossDeviceSecondsToday > 0L || topGroups.isNotEmpty()
    if (!hasData) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconBadge(
                        icon = Icons.Rounded.Group,
                        size = 32.dp,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "Across devices",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = formatUsageSeconds(state.totalCrossDeviceSecondsToday),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "Phone, desktop and browser time combined today.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            topGroups.forEach { (group, seconds) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatUsageSeconds(seconds),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            TextButton(
                onClick = onSeeAll,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(48.dp),
            ) {
                Text("See all ranges", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun MostUsedRow(
    entry: MostUsedEntry,
    maxSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val app = entry.app
    val group = entry.group
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconTileForPackage(
                packageName = app.packageName,
                name = group?.name ?: app.appName,
                size = 40.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = group?.name ?: app.appName,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (group != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            MergedBadge(memberCount = group.members.size)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatUsageSeconds(entry.displaySeconds),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val fraction = (entry.displaySeconds / maxSeconds.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction.coerceAtLeast(0.04f))
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

/** Small tonal badge marking a row whose app is merged with others into one bucket. */
@Composable
private fun MergedBadge(memberCount: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = "merged · $memberCount",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
}
