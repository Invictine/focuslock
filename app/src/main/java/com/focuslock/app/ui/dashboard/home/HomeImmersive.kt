package com.focuslock.app.ui.dashboard.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focuslock.app.service.UsageStatsRepository
import com.focuslock.app.ui.components.IconBadge
import com.focuslock.app.ui.components.SectionHeader
import com.focuslock.app.ui.dashboard.PixelWorkRecordItem

/**
 * IMMERSIVE ("Forest/Flow timer-first"): a big session-timer hero. The running
 * timer lives inside the existing focus-timer dialog (dialog-local state, not
 * exposed), so the hero is an honest idle poster — session-length chips open
 * that same dialog via the timer callback — followed by today's real sessions
 * and quick Log/Tasks actions.
 */
fun LazyListScope.HomeImmersiveContent(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
) {
    HeaderItem(state, callbacks)
    SetupBannerItem(state, callbacks)

    item(key = "immersive-hero") {
        HomeEntrance(index = 2, modifier = Modifier.animateItem()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconBadge(
                        icon = Icons.Rounded.Timer,
                        size = 64.dp,
                        containerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Ready to focus?",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (state.focusMinutesLoaded && state.focusMinutes > 0) {
                            "${UsageStatsRepository.formatDuration(state.focusMinutes.toLong())} banked today — keep going."
                        } else {
                            "Pick a block. Finishing earns scroll time."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    // Session-length chips jump into the existing timer dialog.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 25, 50).forEach { mins ->
                            FilterChip(
                                selected = false,
                                onClick = callbacks.onOpenTimer,
                                label = { Text("${mins}m") },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = callbacks.onOpenTimer,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start session", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    item(key = "immersive-quick") {
        HomeEntrance(index = 3, modifier = Modifier.animateItem()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = callbacks.onOpenLog,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Log", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = callbacks.onOpenTickTick,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Rounded.Checklist, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Tasks", maxLines = 1)
                }
            }
        }
    }

    item(key = "immersive-sessions-header") {
        HomeEntrance(index = 4, modifier = Modifier.animateItem()) {
            val sessions = state.focusRecords
            SectionHeader(
                title = "Today's sessions",
                trailing = {
                    if (sessions.isNotEmpty()) {
                        Text(
                            text = "${sessions.size} · " +
                                UsageStatsRepository.formatDuration(sessions.sumOf { it.durationMinutes }.toLong()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }

    val sessions = state.focusRecords
    if (state.history != null && sessions.isEmpty()) {
        item(key = "immersive-empty") {
            Box(Modifier.animateItem()) { EmptyHistoryCard() }
        }
    } else {
        items(sessions, key = { "immersive-${it.id}" }) { record ->
            Box(Modifier.animateItem()) {
                PixelWorkRecordItem(record = record)
            }
        }
    }

    item(key = "immersive-toggle") {
        HomeEntrance(index = 5, modifier = Modifier.animateItem()) {
            HistoryToggleButton(
                expanded = state.showAllHistory,
                onToggle = callbacks.onToggleHistory,
            )
        }
    }

    DayChartItem(state, entranceIndex = 6, key = "immersive-day")
    BankItem(state)
}
