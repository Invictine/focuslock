package com.focuslock.app.ui.dashboard.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focuslock.app.service.UsageStatsRepository
import com.focuslock.app.ui.components.SectionHeader
import com.focuslock.app.ui.dashboard.PixelWorkRecordItem

/**
 * MINIMAL ("Things/typographic", Swiss minimalism): the giant focus-time number
 * is the hero, ONE primary action (Timer), a quiet caption (goal progress +
 * scroll bank), then a restrained sessions list and a single tasks line. Lots of
 * whitespace, strict type hierarchy. Real data only; the hourly day chart is
 * intentionally omitted here (still one tap away in the other styles).
 */
fun LazyListScope.HomeMinimalContent(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
) {
    HeaderItem(state, callbacks)
    SetupBannerItem(state, callbacks)

    item(key = "minimal-hero") {
        HomeEntrance(index = 2, modifier = Modifier.animateItem()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (state.focusMinutesLoaded) {
                        UsageStatsRepository.formatDuration(state.focusMinutes.toLong())
                    } else {
                        "…"
                    },
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "focused today · ${state.focusPercent}% of ${state.focusGoalMinutes} min goal",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    item(key = "minimal-action") {
        HomeEntrance(index = 3, modifier = Modifier.animateItem()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = callbacks.onOpenTimer,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(Icons.Rounded.Timer, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start focusing", style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = callbacks.onOpenLog,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("Log work", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(
                        onClick = callbacks.onOpenTickTick,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("Tasks", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    item(key = "minimal-caption") {
        HomeEntrance(index = 4, modifier = Modifier.animateItem()) {
            val bankMinutes = state.liveBalanceSeconds / 60
            val bankSeconds = state.liveBalanceSeconds % 60
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(
                    progress = { state.focusProgress },
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .clip(MaterialTheme.shapes.small),
                )
                Text(
                    text = "Scroll bank ${bankMinutes}m ${bankSeconds.toString().padStart(2, '0')}s · " +
                        when (state.tasksState) {
                            FocusHomeTasksState.Loading -> "loading tasks…"
                            FocusHomeTasksState.Error -> "tasks couldn't load"
                            FocusHomeTasksState.NoAccount -> "tasks not connected"
                            FocusHomeTasksState.Loaded -> "tasks ${state.tasksDone}/${state.tasksGoal}"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.tasksState == FocusHomeTasksState.Error) {
                    TextButton(
                        onClick = callbacks.onRetryTasks,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("Retry tasks", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    item(key = "minimal-sessions-header") {
        HomeEntrance(index = 5, modifier = Modifier.animateItem()) {
            val sessions = state.focusRecords
            SectionHeader(
                title = "Today's sessions",
                trailing = {
                    if (sessions.isNotEmpty()) {
                        Text(
                            text = "${sessions.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }

    val sessions = state.focusRecords.take(5)
    if (sessions.isNotEmpty()) {
        items(sessions, key = { "minimal-${it.id}" }) { record ->
            Box(Modifier.animateItem()) {
                PixelWorkRecordItem(record = record)
            }
        }
    } else if (state.history == null) {
        // Genuine loading: history hasn't resolved yet (never the bank caption).
        item(key = "minimal-sessions-loading") {
            Box(
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    } else {
        // Loaded but no focus sessions: honest empty state, not the bank caption.
        item(key = "minimal-empty") {
            Box(Modifier.animateItem()) {
                EmptyHistoryCard(
                    title = "No sessions yet",
                    subtitle = "Tap Start focusing to log your first session.",
                )
            }
        }
    }

    BankItem(state)
}
