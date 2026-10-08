package com.focuslock.app.ui.dashboard.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focuslock.app.ui.components.SectionHeader
import com.focuslock.app.ui.dashboard.PixelWorkRecordItem

/** Today's practical dashboard: one priority, one useful balance, then quick actions. */
fun LazyListScope.TodayDestination(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
    frogEnabled: Boolean?,
    timerIsRunning: Boolean,
    timerRemainingSeconds: Int,
) {
    item(key = "today-heading") {
        TodayHeading(state.todayFormatted)
    }

    if (frogEnabled == true) {
        FrogCardItem(entranceIndex = 1, key = "today-daily-priority", onOpenSettings = callbacks.onOpenSettings)
    } else if (frogEnabled == null) {
        item(key = "today-daily-priority-loading") {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Daily priority", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    item(key = "today-balance") { ScrollBalanceRow(state) }

    item(key = "today-actions") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (frogEnabled == false) {
                Button(
                    onClick = callbacks.onOpenTimer,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (timerIsRunning) "Resume focus timer" else "Start focus timer")
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stackActions = maxWidth < 380.dp || LocalDensity.current.fontScale >= 1.3f
                if (stackActions) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (frogEnabled != false) FilledTonalButton(
                            onClick = callbacks.onOpenTimer,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (timerIsRunning) "Resume focus timer" else "Quick timer")
                        }
                        FilledTonalButton(
                            onClick = callbacks.onOpenLog,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = MaterialTheme.shapes.large,
                        ) { Text("Log work") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (frogEnabled != false) {
                            FilledTonalButton(
                                onClick = callbacks.onOpenTimer,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                shape = MaterialTheme.shapes.large,
                                contentPadding = PaddingValues(horizontal = 10.dp),
                            ) {
                                Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (timerIsRunning) "Resume timer" else "Quick timer", maxLines = 1)
                            }
                        }
                        FilledTonalButton(
                            onClick = callbacks.onOpenLog,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            shape = MaterialTheme.shapes.large,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) { Text("Log work", maxLines = 1) }
                    }
                }
            }
            if (timerIsRunning) Text(
                "Focus timer running · ${formatTimerDuration(timerRemainingSeconds)} remaining",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (frogEnabled == false) {
        item(key = "today-priority-link") {
            Surface(
                onClick = callbacks.onOpenSettings,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Daily priority (Eat the Frog) is off · Set it up",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    SetupBannerItem(state, callbacks, entranceIndex = 5, screenKey = "today")
    item(key = "today-next-task") { NextTaskItem(state, callbacks) }
}

/** Activity is the detailed, real-data destination. */
fun LazyListScope.ActivityDestination(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
) {
    item(key = "activity-heading") {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Text(state.todayFormatted, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
    SetupBannerItem(state, callbacks, entranceIndex = 1, screenKey = "activity")
    item(key = "activity-ratio") { DailyRatioCard(state) }
    item(key = "activity-chart") { FocusDayChart(state) }
    item(key = "activity-usage") { UsageDetailsCard(state) }
    item(key = "activity-log") {
        FilledTonalButton(
            onClick = callbacks.onOpenLog,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.large,
        ) { Text("Log work") }
    }
    item(key = "activity-history-heading") { SectionHeader(title = "Work history") }
    when {
        state.history == null -> item(key = "activity-history-loading") {
            Text("Loading work history…", modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.history.isEmpty() -> item(key = "activity-history-empty") {
            Text("No work logged today yet.", modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> items(state.history, key = { it.id }) { record -> PixelWorkRecordItem(record = record) }
    }
}

@Composable
private fun TodayHeading(date: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ScrollBalanceRow(state: FocusHomeState) {
    val balanceSeconds = state.liveBalanceSeconds
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Leisure time available",
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(formatHomeDuration(balanceSeconds), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DailyRatioCard(state: FocusHomeState) {
    val ratio = evaluateHomeRatio(
        state.focusMinutes,
        state.leisureSeconds,
        state.focusMinutesLoaded,
        state.targetFocusPerLeisure,
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Today's focus and leisure", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            RatioMetric("Focus", if (state.focusMinutesLoaded) formatHomeDuration(state.focusMinutes * 60L) else "Loading…", MaterialTheme.colorScheme.primary)
            RatioMetric(
                "Boundary leisure",
                state.leisureSeconds?.let(::formatHomeDuration)
                    ?: if (state.usageAccessGranted == false) "Usage Access needed" else "Loading…",
                MaterialTheme.colorScheme.tertiary,
            )
            Text(ratio.ratioLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(ratio.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(ratio.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RatioMetric(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun UsageDetailsCard(state: FocusHomeState) {
    val summary = state.usageSummary
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App usage today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when {
                state.usageAccessGranted == false -> Text("Grant Usage Access to see app details.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.localUsageDataAvailable == false -> Text("App usage is unavailable on this phone right now.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                summary == null -> Text("Loading app usage…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                summary.topApps.isEmpty() -> Text("No app usage recorded today.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val appLabel = if (summary.appCount == 1) "app" else "apps"
                    Text("${summary.totalScreenMinutes} min across ${summary.appCount} $appLabel", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    summary.topApps.forEach { app ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(app.appName, modifier = Modifier.weight(1f).padding(end = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatHomeDuration(app.foregroundMinutes * 60L), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextTaskItem(state: FocusHomeState, callbacks: FocusHomeCallbacks) {
    Card(
        onClick = when (state.tasksState) {
            FocusHomeTasksState.NoAccount -> callbacks.onOpenConnections
            FocusHomeTasksState.Error -> callbacks.onRetryTasks
            else -> callbacks.onOpenTickTick
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("TickTick", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state.tasksState) {
                FocusHomeTasksState.Loading -> Text("Finding your next task…", style = MaterialTheme.typography.bodyLarge)
                FocusHomeTasksState.NoAccount -> Text("Connect TickTick to choose a priority", style = MaterialTheme.typography.bodyLarge)
                FocusHomeTasksState.Error -> Text("Couldn't load tasks · Tap to retry", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                FocusHomeTasksState.Loaded -> {
                    Text(state.nextTaskTitle ?: "No open task yet", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    if (!state.nextTaskDetail.isNullOrBlank()) Text(state.nextTaskDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatTimerDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}
