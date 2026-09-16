package com.focuslock.app.ui.dashboard.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * RINGS ("Activity rings", Apple Watch / fitness proven pattern): the current
 * dashboard content, moved as-is — header, setup banner, dual progress ring hero
 * + top-app/tasks cards, Log/Timer/Tasks row, history toggle, focus-through-day,
 * recent work, scroll-bank caption. Nothing functional removed.
 */
fun LazyListScope.HomeRingsContent(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
) {
    HeaderItem(state, callbacks)
    SetupBannerItem(state, callbacks)

    item(key = "hero") {
        HomeEntrance(index = 2, modifier = Modifier.animateItem()) {
            RingsHero(state = state, callbacks = callbacks)
        }
    }

    item(key = "history-toggle") {
        HomeEntrance(index = 3, modifier = Modifier.animateItem()) {
            HistoryToggleButton(
                expanded = state.showAllHistory,
                onToggle = callbacks.onToggleHistory,
            )
        }
    }

    DayChartItem(state)
    HistoryItems(state)
    BankItem(state)
}

@Composable
private fun RingsHero(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
) {
    val focusSweep by animateFloatAsState(
        targetValue = state.focusProgress,
        animationSpec = tween(800),
        label = "focus-sweep",
    )
    val tasksSweep by animateFloatAsState(
        targetValue = state.tasksProgress,
        animationSpec = tween(800),
        label = "tasks-sweep",
    )
    val ringPink = MaterialTheme.colorScheme.primary
    val ringCyan = MaterialTheme.colorScheme.tertiary
    val ringTrack = MaterialTheme.colorScheme.surfaceVariant
    val subtitleGray = MaterialTheme.colorScheme.onSurfaceVariant
    val googleBlue = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Ring variant: fixed Canvas — proper stroke/inset proportions.
            Box(
                modifier = Modifier.size(140.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val gap = 8.dp.toPx()
                    val cx = size.width / 2
                    val cy = size.height / 2
                    val outerR = size.minDimension / 2 - stroke / 2
                    val innerR = outerR - stroke - gap
                    fun topLeft(r: Float) = Offset(cx - r, cy - r)
                    fun arcSize(r: Float) = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                    // Tracks
                    drawArc(
                        color = ringTrack,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft(outerR),
                        size = arcSize(outerR),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = ringTrack,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft(innerR),
                        size = arcSize(innerR),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    // Progress arcs — skipped at zero so idle rings stay perfectly clean.
                    if (focusSweep > 0.001f) {
                        drawArc(
                            color = ringPink,
                            startAngle = -90f,
                            sweepAngle = 360f * focusSweep,
                            useCenter = false,
                            topLeft = topLeft(outerR),
                            size = arcSize(outerR),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    if (tasksSweep > 0.001f) {
                        drawArc(
                            color = ringCyan,
                            startAngle = -90f,
                            sweepAngle = 360f * tasksSweep,
                            useCenter = false,
                            topLeft = topLeft(innerR),
                            size = arcSize(innerR),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (state.focusMinutesLoaded) "${state.focusPercent}%" else "…",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Focus",
                        style = MaterialTheme.typography.labelMedium,
                        color = subtitleGray,
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 10.dp)
                        .size(24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(googleBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state.tasksLoaded) "+${state.tasksDone}" else "—",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TopAppCard(state = state, callbacks = callbacks)
                TasksSummaryCard(state = state, callbacks = callbacks)
            }
        }
        LogTimerTasksRow(callbacks = callbacks)
    }
}
