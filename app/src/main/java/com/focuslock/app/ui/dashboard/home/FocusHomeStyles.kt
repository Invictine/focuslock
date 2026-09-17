package com.focuslock.app.ui.dashboard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.repository.CreditBankRepository
import com.focuslock.app.data.repository.TargetGroup
import com.focuslock.app.service.AppUsageEntry
import com.focuslock.app.service.DailyUsageSummary
import com.focuslock.app.ui.components.AppIconTileForPackage
import com.focuslock.app.ui.components.IconBadge
import com.focuslock.app.ui.components.SectionHeader
import com.focuslock.app.ui.components.StaggeredFadeSlide
import com.focuslock.app.ui.components.pressScaleModifier
import com.focuslock.app.ui.components.rememberDecorativePulse
import com.focuslock.app.ui.dashboard.PixelWorkRecordItem

/**
 * The five live-testable Focus-tab front pages. Persisted as [key] in
 * SettingsRepository ("focus_home_style"); unknown keys fall back to [RINGS].
 */
enum class FocusHomeStyle(val key: String, val title: String, val blurb: String) {
    RINGS("rings", "Rings", "Activity-style progress rings"),
    SCREEN_TIME("screen_time", "Screen Time", "iOS-style usage day"),
    MINIMAL("minimal", "Minimal", "Big number, one action"),
    CARDS("cards", "Cards", "Today at a glance"),
    IMMERSIVE("immersive", "Immersive", "Timer-first focus");

    companion object {
        fun fromKey(key: String?): FocusHomeStyle =
            entries.firstOrNull { it.key == key } ?: RINGS
    }
}

/** TickTick tasks load state, mirrored from the dashboard's fetch state. */
enum class FocusHomeTasksState { Loading, NoAccount, Loaded, Error }

/**
 * Everything a home variation may render. Built by DashboardScreen from the exact
 * same flows/values the current screen uses — no new data sources. Null [history]
 * means "still loading" (distinct from loaded-but-empty).
 */
data class FocusHomeState(
    val todayFormatted: String,
    val permissionsChecked: Boolean,
    val hasAllPermissions: Boolean,
    val missingLabels: List<String>,
    val isUsageAccessGranted: Boolean?,
    val focusMinutes: Int,
    val focusMinutesLoaded: Boolean,
    val focusGoalMinutes: Int,
    val tasksDone: Int,
    val tasksLoaded: Boolean,
    val tasksGoal: Int,
    val tasksState: FocusHomeTasksState,
    val usageSummary: DailyUsageSummary?,
    val topApp: AppUsageEntry?,
    val history: List<TickTickWorkRecord>?,
    val showAllHistory: Boolean,
    val liveBalanceState: State<Long>,
    val nukeActive: Boolean,
    val accountInitial: String,
    /** Merged cross-device groups (local repository mirror), plus today's synced per-group and total seconds. */
    val crossDeviceGroups: List<TargetGroup> = emptyList(),
    val groupUsageTodaySeconds: Map<String, Long> = emptyMap(),
    val totalCrossDeviceSecondsToday: Long = 0L,
) {
    /** Read only from balance captions so a ticking balance does not rebuild the home tree. */
    val liveBalanceSeconds: Long get() = liveBalanceState.value

    val focusProgress: Float
        get() = if (focusMinutesLoaded) {
            (focusMinutes / focusGoalMinutes.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        } else 0f

    val tasksProgress: Float
        get() = if (tasksLoaded) {
            (tasksDone / tasksGoal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        } else 0f

    val focusPercent: Int get() = (focusProgress * 100).toInt()

    /** Focus records only (same filter the dashboard hero uses). Computed ONCE when the
     *  state object is built (DashboardScreen remembers it), so access is O(1). */
    val focusRecords: List<TickTickWorkRecord> =
        history.orEmpty().filter {
            CreditBankRepository.isFocusRecord(it.source, it.durationMinutes)
        }
}

/**
 * Every action a home variation can trigger. Variations never own dialog/sync
 * state — they just call these; DashboardScreen keeps all behavior hoisted.
 */
data class FocusHomeCallbacks(
    val onOpenTickTick: () -> Unit,
    val onNavigatePermissions: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenAccount: () -> Unit,
    val onOpenLog: () -> Unit,
    val onOpenTimer: () -> Unit,
    val onToggleHistory: () -> Unit,
    val onRetryTasks: () -> Unit,
    val onShowNukeConfirm: () -> Unit,
    val onShowNukeInfo: () -> Unit,
    val onLaunchNuke: () -> Unit,
)

/** Dispatches the collected state to the selected home variation. */
fun LazyListScope.FocusHome(
    style: FocusHomeStyle,
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
) {
    when (style) {
        FocusHomeStyle.RINGS -> HomeRingsContent(state, callbacks)
        FocusHomeStyle.SCREEN_TIME -> HomeScreenTimeContent(state, callbacks)
        FocusHomeStyle.MINIMAL -> HomeMinimalContent(state, callbacks)
        FocusHomeStyle.CARDS -> HomeCardsContent(state, callbacks)
        FocusHomeStyle.IMMERSIVE -> HomeImmersiveContent(state, callbacks)
    }
}

/** Shared short entrance; layout stays composed on tab returns. */
@Composable
fun HomeEntrance(
    index: Int,
    modifier: Modifier = Modifier,
    screenKey: String? = null,
    content: @Composable () -> Unit,
) {
    StaggeredFadeSlide(
        visible = true,
        index = index,
        modifier = modifier,
        screenKey = screenKey,
        content = content,
    )
}

/** At-a-glance header: date + title + Nuke/settings/account squircle actions. */
@Composable
fun FocusHomeHeader(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = state.todayFormatted,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Make time.",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Nuke button (tap to arm, long-press for details). Error tone, same
            // 40dp squircle geometry as the other header actions.
            val nukeInteraction = remember { MutableInteractionSource() }
            val nukeHaloAlphaState = rememberDecorativePulse(
                initialValue = 0.10f,
                targetValue = 0.28f,
                active = state.nukeActive,
                label = "nuke-halo",
            )
            val nukeHaloColor = MaterialTheme.colorScheme.error
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .then(pressScaleModifier(nukeInteraction))
                    .semantics {
                        contentDescription = "Nuke: emergency lockdown"
                    }
                    .combinedClickable(
                        interactionSource = nukeInteraction,
                        indication = null,
                        onClick = {
                            if (state.nukeActive) callbacks.onLaunchNuke() else callbacks.onShowNukeConfirm()
                        },
                        onLongClick = callbacks.onShowNukeInfo,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .drawBehind {
                            val inset = 1.dp.toPx()
                            val radius = 15.dp.toPx()
                            drawRoundRect(
                                color = nukeHaloColor,
                                alpha = (nukeHaloAlphaState.value + 0.05f).coerceIn(0f, 1f),
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - inset * 2, size.height - inset * 2),
                                cornerRadius = CornerRadius(radius, radius),
                            )
                        },
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "☢️",
                        fontSize = 20.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Settings gear — 48dp touch target, same squircle face.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Settings" }
                    .clickable { callbacks.onOpenSettings() },
                contentAlignment = Alignment.Center,
            ) {
                IconBadge(
                    icon = Icons.Rounded.Settings,
                    size = 40.dp,
                    modifier = Modifier.size(40.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Account avatar: 48dp touch target; initial letter in the same squircle.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Account" }
                    .clickable { callbacks.onOpenAccount() },
                contentAlignment = Alignment.Center,
            ) {
                if (state.accountInitial.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.accountInitial,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    IconBadge(
                        icon = Icons.Rounded.Person,
                        size = 40.dp,
                        modifier = Modifier.size(40.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

fun LazyListScope.HeaderItem(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
    screenKey: String? = null,
) {
    item(key = "header") {
        HomeEntrance(index = 0, screenKey = screenKey) {
            FocusHomeHeader(state = state, callbacks = callbacks)
        }
    }
    // "Eat the frog" card: one shared placement for every front page, right under the
    // header (self-contained — collects its own frog flows, see FrogCard.kt).
    FrogCardItem(entranceIndex = 1)
}

/** Missing-permissions warning card (only after all checks resolved). */
@Composable
fun SetupBannerCard(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            IconBadge(
                icon = Icons.Rounded.Warning,
                size = 44.dp,
                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
                contentColor = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Finish setting up",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "Missing: ${state.missingLabels.joinToString(", ")}. Blocking + screen-time stats need these.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = callbacks.onNavigatePermissions,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text("Setup", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

fun LazyListScope.SetupBannerItem(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
    entranceIndex: Int = 1,
    screenKey: String? = null,
) {
    if (state.permissionsChecked && !state.hasAllPermissions) {
        item(key = "perms") {
            HomeEntrance(index = entranceIndex, screenKey = screenKey) {
                SetupBannerCard(state = state, callbacks = callbacks)
            }
        }
    }
}

/** One primary CTA + two tonal siblings: Log / Timer / Tasks. */
@Composable
fun LogTimerTasksRow(
    callbacks: FocusHomeCallbacks,
    modifier: Modifier = Modifier,
) {
    val googleBlue = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = callbacks.onOpenLog,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(
                containerColor = googleBlue,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Log", maxLines = 1)
        }
        // Focus Timer: built-in single-session timer — no TickTick needed.
        FilledTonalButton(
            onClick = callbacks.onOpenTimer,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Icon(
                Icons.Rounded.Timer,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Timer", maxLines = 1)
        }
        // "Tasks" opens the TickTick task app.
        FilledTonalButton(
            onClick = callbacks.onOpenTickTick,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Icon(
                Icons.Rounded.Checklist,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Tasks", maxLines = 1)
        }
    }
}

fun LazyListScope.ActionsItem(
    callbacks: FocusHomeCallbacks,
    entranceIndex: Int = 3,
    key: String = "actions",
    screenKey: String? = null,
) {
    item(key = key) {
        HomeEntrance(index = entranceIndex, screenKey = screenKey) {
            LogTimerTasksRow(callbacks = callbacks)
        }
    }
}

/** Borderless tonal disclosure for expanding/collapsing history. */
@Composable
fun HistoryToggleButton(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(if (expanded) "Show less" else "History — show all")
    }
}

/** Tasks progress card (tertiary tonal): real TickTick completed-today count. */
@Composable
fun TasksSummaryCard(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
    modifier: Modifier = Modifier,
) {
    val purpleCard = MaterialTheme.colorScheme.tertiaryContainer
    val purpleText = MaterialTheme.colorScheme.onTertiaryContainer
    androidx.compose.material3.Surface(
        color = purpleCard,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = Icons.Rounded.Checklist,
                size = 40.dp,
                containerColor = purpleText.copy(alpha = 0.2f),
                contentColor = purpleText,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tasks",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                when (state.tasksState) {
                    FocusHomeTasksState.Loading -> Text(
                        text = "…",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = purpleText,
                    )
                    FocusHomeTasksState.Error -> Text(
                        text = "Couldn't load",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = purpleText,
                    )
                    else -> Text(
                        text = "${state.tasksDone}/${state.tasksGoal} done",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = purpleText,
                    )
                }
                when (state.tasksState) {
                    FocusHomeTasksState.Error -> androidx.compose.material3.TextButton(
                        onClick = callbacks.onRetryTasks,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(
                            "Retry",
                            style = MaterialTheme.typography.labelMedium,
                            color = purpleText,
                        )
                    }
                    FocusHomeTasksState.NoAccount -> androidx.compose.material3.TextButton(
                        onClick = callbacks.onOpenSettings,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text(
                            "Connect TickTick for task tracking",
                            style = MaterialTheme.typography.labelMedium,
                            color = purpleText.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** Top-used-app card (secondary tonal): real UsageStats top app, honest fallbacks. */
@Composable
fun TopAppCard(
    state: FocusHomeState,
    callbacks: FocusHomeCallbacks,
    modifier: Modifier = Modifier,
) {
    val tealCard = MaterialTheme.colorScheme.secondaryContainer
    val tealText = MaterialTheme.colorScheme.onSecondaryContainer
    androidx.compose.material3.Surface(
        color = tealCard,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        val topApp = state.topApp
        if (topApp != null) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIconTileForPackage(
                    packageName = topApp.packageName,
                    name = topApp.appName,
                    size = 40.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = topApp.appName,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${topApp.foregroundMinutes} min",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = tealText,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(
                    icon = Icons.Rounded.PhoneAndroid,
                    size = 40.dp,
                    containerColor = tealText.copy(alpha = 0.2f),
                    contentColor = tealText,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            state.isUsageAccessGranted != true -> "Usage Access needed"
                            state.usageSummary == null -> "Checking usage…"
                            else -> "No usage data yet"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = when {
                            state.isUsageAccessGranted != true -> "Turn it on to see today's screen time."
                            state.usageSummary == null -> "Reading today's stats…"
                            else -> "Open some apps and check back later."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                    if (state.isUsageAccessGranted != true) {
                        androidx.compose.material3.TextButton(
                            onClick = callbacks.onNavigatePermissions,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(48.dp),
                        ) {
                            Text(
                                "Grant Usage Access",
                                style = MaterialTheme.typography.labelMedium,
                                color = tealText,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Empty-state card for the work-history section (loaded but no records today). */
@Composable
fun EmptyHistoryCard(
    modifier: Modifier = Modifier,
    title: String = "No work logged yet today",
    subtitle: String = "Use the Focus Timer, Log Work, or complete tasks in TickTick to earn screen time.",
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconBadge(
                icon = Icons.Rounded.Timer,
                size = 44.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** "Recent work" header + history rows (3 collapsed, all expanded). Real data only. */
fun LazyListScope.HistoryItems(
    state: FocusHomeState,
    entranceIndex: Int = 5,
    screenKey: String? = null,
) {
    item(key = "history") {
        HomeEntrance(index = entranceIndex, screenKey = screenKey) {
            val history = state.history.orEmpty()
            SectionHeader(
                title = "Recent work",
                trailing = {
                    if (history.isNotEmpty()) {
                        Text(
                            text = "${history.size} total",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }

    if (state.showAllHistory) {
        val expandedHistory = state.history
        if (expandedHistory.isNullOrEmpty()) {
            item(key = "history-list") {
                if (expandedHistory != null) {
                    Box(Modifier.animateItem()) { EmptyHistoryCard() }
                }
            }
        } else {
            items(expandedHistory, key = { it.id }) { record ->
                Box(Modifier.animateItem()) {
                    PixelWorkRecordItem(record = record)
                }
            }
        }
    } else {
        item(key = "history-list") {
            Box(Modifier.animateItem()) {
                val historySnapshot = state.history
                val history = historySnapshot.orEmpty()
                if (historySnapshot != null && history.isEmpty()) {
                    EmptyHistoryCard()
                } else if (history.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        history.take(3).forEach { record ->
                            key(record.id) { PixelWorkRecordItem(record = record) }
                        }
                    }
                }
            }
        }
    }
}

/** 24 hourly focus buckets from work-history timestamps (focus records only). */
@Composable
fun FocusDayChart(
    state: FocusHomeState,
    modifier: Modifier = Modifier,
) {
    // 24 hourly buckets (0-23) from workHistory timestamps — focus records only.
    val buckets = remember(state.history) {
        val arr = IntArray(24)
        val cal = java.util.Calendar.getInstance()
        for (r in state.focusRecords) {
            cal.timeInMillis = r.timestamp
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            arr[h] += r.durationMinutes
        }
        arr
    }
    val max = (buckets.maxOrNull() ?: 0).coerceAtLeast(0)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Focus through the day",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "max ${max}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (max <= 0) {
                Text(
                    "No focus yet today — start Focus Timer, a TickTick session, or a manual log.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    buckets.forEachIndexed { _, mins ->
                        val fraction = if (max > 0) mins / max.toFloat() else 0f
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(if (mins <= 0) 0.04f else fraction.coerceAtLeast(0.06f))
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            if (mins > 0) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        ),
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("0", "6", "12", "18", "23").forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

fun LazyListScope.DayChartItem(
    state: FocusHomeState,
    entranceIndex: Int = 4,
    key: String = "day",
    screenKey: String? = null,
) {
    item(key = key) {
        HomeEntrance(index = entranceIndex, screenKey = screenKey) {
            FocusDayChart(state = state)
        }
    }
}

/** Demoted leisure caption at the very bottom. */
fun LazyListScope.BankItem(state: FocusHomeState) {
    item(key = "bank") {
        Box(Modifier.animateItem()) {
            val bankMinutes = state.liveBalanceSeconds / 60
            val bankSeconds = state.liveBalanceSeconds % 60
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(
                    text = "Scroll bank (from focus): ${bankMinutes}m ${bankSeconds.toString().padStart(2, '0')}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
