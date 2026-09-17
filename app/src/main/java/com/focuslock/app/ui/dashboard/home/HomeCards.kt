package com.focuslock.app.ui.dashboard.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focuslock.app.service.UsageStatsRepository
import com.focuslock.app.ui.components.IconBadge
import com.focuslock.app.ui.components.SectionHeader
import com.focuslock.app.ui.dashboard.PixelWorkRecordItem

/**
 * CARDS ("Opal/Structured feed"): a vertical card deck — "Today's focus"
 * summary, tasks progress, sessions + scroll bank, history preview — each
 * tappable where it has a destination (timer / TickTick / history toggle).
 * Strong hierarchy, no behavior changes.
 */
fun LazyListScope.HomeCardsContent(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
) {
    HeaderItem(state, callbacks, screenKey = "home_cards")
    SetupBannerItem(state, callbacks, screenKey = "home_cards")

    item(key = "cards-focus") {
        HomeEntrance(index = 2, screenKey = "home_cards") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Today's focus summary. Open focus timer." }
                    .clickable { callbacks.onOpenTimer() },
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(
                        icon = Icons.Rounded.Timer,
                        size = 48.dp,
                        containerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Today's focus",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            maxLines = 1,
                        )
                        Text(
                            text = if (state.focusMinutesLoaded) {
                                UsageStatsRepository.formatDuration(state.focusMinutes.toLong())
                            } else {
                                "…"
                            },
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.focusProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "${state.focusPercent}% of ${state.focusGoalMinutes} min goal",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    item(key = "cards-tasks") {
        HomeEntrance(index = 3, screenKey = "home_cards") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Tasks progress. Open TickTick." }
                    .clickable { callbacks.onOpenTickTick() },
            ) {
                TasksSummaryCard(state = state, callbacks = callbacks)
            }
        }
    }

    item(key = "cards-bank") {
        HomeEntrance(index = 4, screenKey = "home_cards") {
            val bankMinutes = state.liveBalanceSeconds / 60
            val bankSeconds = state.liveBalanceSeconds % 60
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(
                        icon = Icons.Rounded.CheckCircle,
                        size = 48.dp,
                        containerColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.16f),
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Sessions + scroll bank",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                            maxLines = 1,
                        )
                        Text(
                            text = "${state.focusRecords.size} sessions",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Banked ${bankMinutes}m ${bankSeconds.toString().padStart(2, '0')}s of scroll from focus",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    item(key = "cards-history") {
        HomeEntrance(index = 5, screenKey = "home_cards") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "History preview. Toggle full history." }
                    .clickable { callbacks.onToggleHistory() },
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(
                                icon = Icons.Rounded.History,
                                size = 40.dp,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "History",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        val count = state.history?.size ?: 0
                        if (count > 0) {
                            Text(
                                "$count total",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    val preview = state.history.orEmpty().take(if (state.showAllHistory) Int.MAX_VALUE else 3)
                    if (state.history != null && preview.isEmpty()) {
                        Text(
                            "No work logged yet today — tap Timer below to start.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Clicks pass through to the card toggle; rows stay display-only here.
                            preview.forEach { record ->
                                key(record.id) { PixelWorkRecordItem(record = record) }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (state.showAllHistory) "Show less" else "Show all",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    ActionsItem(callbacks, entranceIndex = 6, key = "cards-actions", screenKey = "home_cards")

    item(key = "cards-day-header") {
        HomeEntrance(index = 7, screenKey = "home_cards") {
            SectionHeader(title = "Today at a glance")
        }
    }

    DayChartItem(state, entranceIndex = 8, key = "cards-day", screenKey = "home_cards")
    BankItem(state)
}
