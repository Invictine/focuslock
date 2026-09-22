package com.focuslock.app.ui.dashboard.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
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
import com.focuslock.app.ui.components.MotionTokens

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
    HeaderItem(state, callbacks, screenKey = "home_rings", includeFrog = false)
    SetupBannerItem(state, callbacks, screenKey = "home_rings")

    item(key = "hero") {
        HomeEntrance(index = 2, screenKey = "home_rings") {
            RingsHero(state = state, callbacks = callbacks)
        }
    }

    FrogCardItem(entranceIndex = 3)

    // Keep the secondary Log / Timer / Tasks choices below the primary hero action.
    ActionsItem(callbacks, entranceIndex = 3, key = "rings-actions", screenKey = "home_rings", showTimer = false)

    item(key = "today-details") {
        HomeEntrance(index = 4, screenKey = "home_rings") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TopAppCard(state = state, callbacks = callbacks)
                TasksSummaryCard(state = state, callbacks = callbacks)
            }
        }
    }

    item(key = "history-toggle") {
        HomeEntrance(index = 3, screenKey = "home_rings") {
            HistoryToggleButton(
                expanded = state.showAllHistory,
                onToggle = callbacks.onToggleHistory,
            )
        }
    }

    DayChartItem(state, screenKey = "home_rings")
    HistoryItems(state, screenKey = "home_rings")
    BankItem(state)
}

@Composable
private fun RingsHero(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
) {
    val focusSweep by animateFloatAsState(
        targetValue = state.focusProgress,
        animationSpec = MotionTokens.ProgressFloat,
        label = "focus-sweep",
    )
    val ringPink = MaterialTheme.colorScheme.primary
    val ringTrack = MaterialTheme.colorScheme.surfaceVariant
    val subtitleGray = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Give the progress cue and summaries a comfortable reading width on phones.
        // The former side-by-side layout squeezed two cards into a narrow column.
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Ring variant: fixed Canvas — proper stroke/inset proportions.
            Box(
                modifier = Modifier.size(132.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val cx = size.width / 2
                    val cy = size.height / 2
                    val outerR = size.minDimension / 2 - stroke / 2
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
            }
            Text(
                text = "${if (state.focusMinutesLoaded) state.focusMinutes else "…"} min focused today",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.tasksLoaded) Text(
                text = "${state.tasksDone} of ${state.tasksGoal} tasks complete",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = callbacks.onOpenTimer,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Rounded.Timer, contentDescription = null)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                Text("Start focus timer", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
