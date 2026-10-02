package com.focuslock.app.ui.dashboard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The balanced, momentum, and editorial homes share one honest data model. */
fun LazyListScope.HomeBalanceContent(state: FocusHomeState, callbacks: FocusHomeCallbacks) {
    HomeScaffold(state, callbacks, "balance") { HomeBalanceHero(state) }
}

fun LazyListScope.HomeMomentumContent(state: FocusHomeState, callbacks: FocusHomeCallbacks) {
    HomeScaffold(state, callbacks, "momentum") { HomeMomentumHero(state) }
}

fun LazyListScope.HomeTodayContent(state: FocusHomeState, callbacks: FocusHomeCallbacks) {
    HomeScaffold(state, callbacks, "today") { HomeTodayHero(state) }
}

private fun LazyListScope.HomeScaffold(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
    key: String,
    hero: @Composable () -> Unit,
) {
    HeaderItem(state, callbacks, screenKey = key, includeFrog = false)
    SetupBannerItem(state, callbacks, entranceIndex = 1, screenKey = key)
    item(key = "$key-hero") { HomeEntrance(2, screenKey = key, content = hero) }
    item(key = "$key-actions") {
        HomeEntrance(3, screenKey = key) {
            HomePrimaryActions(callbacks)
        }
    }
    item(key = "$key-next") {
        HomeEntrance(4, screenKey = key) { NextTaskCard(state, callbacks) }
    }
    FrogCardItem(
        entranceIndex = 5,
        key = "$key-frog",
        onOpenSettings = callbacks.onOpenSettings,
    )
    item(key = "$key-tasks") { TasksSummaryCard(state, callbacks) }
    item(key = "$key-chart") { FocusDayChart(state) }
    item(key = "$key-history-toggle") { HistoryToggleButton(expanded = state.showAllHistory, onToggle = callbacks.onToggleHistory) }
    HistoryItems(state, entranceIndex = 7, screenKey = key)
    BankItem(state)
}

@Composable
private fun HomePrimaryActions(callbacks: FocusHomeCallbacks) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      if (maxWidth < 350.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = callbacks.onOpenTimer, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.large) {
            Icon(Icons.Rounded.Timer, contentDescription = null, Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Text("Start focus")
          }
          FilledTonalButton(onClick = callbacks.onOpenLog, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.large) { Text("Log work") }
        }
      } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = callbacks.onOpenTimer,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Icon(Icons.Rounded.Timer, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(7.dp))
            Text("Start focus")
        }
        FilledTonalButton(
            onClick = callbacks.onOpenLog,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text("Log work")
        }
      }
    }
}

private data class HomeMetrics(
    val focusSeconds: Long,
    val leisureSeconds: Long?,
    val ratio: HomeRatioSummary,
)

private fun homeMetrics(state: FocusHomeState): HomeMetrics = HomeMetrics(
    focusSeconds = state.focusMinutes.toLong() * 60L,
    leisureSeconds = state.leisureSeconds,
    ratio = evaluateHomeRatio(
        focusMinutes = state.focusMinutes,
        leisureSeconds = state.leisureSeconds,
        focusLoaded = state.focusMinutesLoaded,
        target = state.targetFocusPerLeisure,
    ),
)

@Composable
private fun HomeBalanceHero(state: FocusHomeState) {
    val metrics = homeMetrics(state)
    val largeText = LocalDensity.current.fontScale > 1.15f
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Today's balance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 260.dp || largeText) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricValue("Focus", if (state.focusMinutesLoaded) formatHomeDuration(metrics.focusSeconds) else "Loading…", MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
                    MetricValue("Leisure", metrics.leisureSeconds?.let(::formatHomeDuration) ?: "Loading…", MaterialTheme.colorScheme.tertiary, Modifier.fillMaxWidth())
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricValue("Focus", if (state.focusMinutesLoaded) formatHomeDuration(metrics.focusSeconds) else "Loading…", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    MetricValue("Leisure", metrics.leisureSeconds?.let(::formatHomeDuration) ?: "Loading…", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                }
            }
            val measured = state.focusMinutesLoaded && metrics.leisureSeconds != null
            ProportionBar(if (measured) state.focusMinutes.toFloat() else 0f, if (measured) metrics.leisureSeconds!!.div(60f) else 0f)
            RatioVerdict(metrics.ratio)
        }
    }
}

@Composable
private fun HomeMomentumHero(state: FocusHomeState) {
    val metrics = homeMetrics(state)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Keep the day moving", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        MomentumRow("Focus", if (state.focusMinutesLoaded) formatHomeDuration(metrics.focusSeconds) else "Loading…", state.focusProgress, MaterialTheme.colorScheme.primary, "goal ${state.focusGoalMinutes}m")
        val leisureProgress = if (metrics.leisureSeconds != null) {
            val focus = metrics.focusSeconds.coerceAtLeast(1L)
            (metrics.leisureSeconds / 60f / (focus / 60f + metrics.leisureSeconds / 60f)).coerceIn(0f, 1f)
        } else 0f
        MomentumRow("Leisure", metrics.leisureSeconds?.let(::formatHomeDuration) ?: "Loading…", leisureProgress, MaterialTheme.colorScheme.tertiary, "of tracked time")
        Surface(modifier = Modifier.fillMaxWidth(), color = if (metrics.ratio.behind) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(metrics.ratio.ratioLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (metrics.ratio.behind) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
                RatioVerdict(metrics.ratio, titleColor = if (metrics.ratio.behind) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer, detailColor = if (metrics.ratio.behind) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer, showRatioLabel = false)
            }
        }
    }
}

@Composable
private fun HomeTodayHero(state: FocusHomeState) {
    val metrics = homeMetrics(state)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("A clear record of where your time went.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(top = 6.dp))
        LedgerRow("Focus", state.focusMinutesLoaded.then(metrics.focusSeconds)?.let(::formatHomeDuration) ?: "Loading…", MaterialTheme.colorScheme.primary)
        LedgerRow("Leisure", metrics.leisureSeconds?.let(::formatHomeDuration) ?: "Loading…", MaterialTheme.colorScheme.tertiary)
        Text(metrics.ratio.ratioLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
        Text(metrics.ratio.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(metrics.ratio.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Boolean.then(value: Long): Long? = if (this) value else null

@Composable
private fun MetricValue(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun ProportionBar(focus: Float, leisure: Float) {
    val total = (focus + leisure).coerceAtLeast(1f)
    Row(Modifier.fillMaxWidth().heightIn(min = 12.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (focus > 0f) Box(Modifier.weight(focus / total).heightIn(min = 12.dp).background(MaterialTheme.colorScheme.primary))
        if (leisure > 0f) Box(Modifier.weight(leisure / total).heightIn(min = 12.dp).background(MaterialTheme.colorScheme.tertiaryContainer))
    }
}

@Composable
private fun MomentumRow(label: String, value: String, progress: Float, color: androidx.compose.ui.graphics.Color, supporting: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(supporting, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp).clip(RoundedCornerShape(8.dp)), color = color)
    }
}

@Composable
private fun LedgerRow(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RatioVerdict(
    summary: HomeRatioSummary,
    modifier: Modifier = Modifier,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    detailColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showRatioLabel: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(summary.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = titleColor)
        Text(summary.detail, style = MaterialTheme.typography.bodySmall, color = detailColor)
        if (showRatioLabel) Text(summary.ratioLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = titleColor)
    }
}

@Composable
private fun NextTaskCard(state: FocusHomeState, callbacks: FocusHomeCallbacks) {
    val title = state.nextTaskTitle
    val detail = state.nextTaskDetail
    Card(onClick = when (state.tasksState) {
        FocusHomeTasksState.NoAccount -> callbacks.onOpenSettings
        FocusHomeTasksState.Error -> callbacks.onRetryTasks
        else -> callbacks.onOpenTickTick
    }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Checklist, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Next task", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (state.tasksState) {
                    FocusHomeTasksState.Loading -> Text("Finding your next task…", style = MaterialTheme.typography.bodyLarge)
                    FocusHomeTasksState.NoAccount -> Text("Connect TickTick to choose a next task", style = MaterialTheme.typography.bodyLarge)
                    FocusHomeTasksState.Error -> Text("Couldn't load tasks", style = MaterialTheme.typography.bodyLarge)
                    FocusHomeTasksState.Loaded -> {
                        Text(title ?: "No open task yet", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when (state.tasksState) {
                FocusHomeTasksState.NoAccount -> TextButtonAction("Connect", callbacks.onOpenSettings)
                FocusHomeTasksState.Error -> TextButtonAction("Retry", callbacks.onRetryTasks)
                else -> Icon(Icons.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TextButtonAction(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}
