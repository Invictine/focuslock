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
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focuslock.app.service.AppUsageEntry
import com.focuslock.app.service.UsageStatsRepository
import com.focuslock.app.ui.components.AppIconTileForPackage
import com.focuslock.app.ui.components.IconBadge
import com.focuslock.app.ui.components.SectionHeader

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
    HeaderItem(state, callbacks)
    SetupBannerItem(state, callbacks)

    item(key = "usage-hero") {
        HomeEntrance(index = 2, modifier = Modifier.animateItem()) {
            ScreenTimeHero(state = state, callbacks = callbacks)
        }
    }

    DayChartItem(state, entranceIndex = 3)

    item(key = "most-used-header") {
        HomeEntrance(index = 4, modifier = Modifier.animateItem()) {
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
        items(topApps, key = { "usage-${it.packageName}" }) { app ->
            Box(Modifier.animateItem()) {
                MostUsedRow(
                    app = app,
                    maxMinutes = topApps.firstOrNull()?.foregroundMinutes?.coerceAtLeast(1L) ?: 1L,
                )
            }
        }
    }

    item(key = "st-tasks") {
        HomeEntrance(index = 5, modifier = Modifier.animateItem()) {
            TasksSummaryCard(state = state, callbacks = callbacks)
        }
    }

    ActionsItem(callbacks, entranceIndex = 6, key = "st-actions")
    HistoryItems(state, entranceIndex = 7)
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

@Composable
private fun MostUsedRow(
    app: AppUsageEntry,
    maxMinutes: Long,
    modifier: Modifier = Modifier,
) {
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
                name = app.appName,
                size = 40.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = UsageStatsRepository.formatDuration(app.foregroundMinutes),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val fraction = (app.foregroundMinutes / maxMinutes.toFloat()).coerceIn(0f, 1f)
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
