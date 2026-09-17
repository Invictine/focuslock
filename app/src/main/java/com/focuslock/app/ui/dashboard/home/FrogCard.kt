package com.focuslock.app.ui.dashboard.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.service.FrogCoordinator
import com.focuslock.app.ui.components.IconBadge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Shared "eat the frog" picker: the cached open TickTick tasks (tap to select), a
 * forced refresh, and a manual-entry field. Selection stays with the caller so the
 * dashboard dialog and the blocker's hard-lock screen can reuse it verbatim.
 */
@Composable
fun FrogPickerBody(
    openTasks: List<FrogTask>,
    onPick: (FrogTask) -> Unit,
    onManual: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var manualTitle by remember { mutableStateOf("") }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Open tasks",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    if (refreshing) return@TextButton
                    refreshing = true
                    fetchError = null
                    scope.launch {
                        val ok = try {
                            FrogCoordinator.refreshOpenTasks(context, force = true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        refreshing = false
                        if (!ok) fetchError = "Couldn't refresh from TickTick — showing cached tasks."
                    }
                },
                enabled = !refreshing,
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (refreshing) "Refreshing…" else "Refresh from TickTick",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (openTasks.isEmpty()) {
            Text(
                text = fetchError ?: "No cached open tasks yet — tap Refresh from TickTick.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                openTasks.forEach { task ->
                    Surface(
                        onClick = { onPick(task) },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val meta = listOf(task.projectName, task.dueDate)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            if (fetchError != null) {
                Text(
                    text = fetchError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        OutlinedTextField(
            value = manualTitle,
            onValueChange = { manualTitle = it },
            label = { Text("Or type your own frog") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = {
                onManual(manualTitle.trim())
                manualTitle = ""
            },
            enabled = manualTitle.isNotBlank(),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("Use this frog", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** One frog card item for every Focus-tab front page (wired from `HeaderItem`). */
fun LazyListScope.FrogCardItem(
    entranceIndex: Int = 1,
    key: String = "frog",
) {
    item(key = key) {
        // "frog" screen key: the entrance plays once per process, not on every
        // Focus-tab return (the legacy null-key path replays it each time).
        HomeEntrance(index = entranceIndex, screenKey = "frog") {
            FrogCard()
        }
    }
}

/**
 * Compact "eat the frog" status card: title/phase, tracked-vs-required progress,
 * tick-off affordance and tap-through to the picker dialog. Self-contained — it
 * collects the frog flows directly, matching how the other home cards are wired.
 */
@Composable
fun FrogCard(modifier: Modifier = Modifier) {
    val frogRepo = FocusLockApplication.instance.frogRepository
    val scope = rememberCoroutineScope()
    val state by frogRepo.frogStateFlow.collectAsStateWithLifecycle(initialValue = null)
    var showPicker by remember { mutableStateOf(false) }

    val frogState = state
    // Feature off: no card at all (the toggle lives in Settings).
    if (frogState?.enabled == false) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable { showPicker = true },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = Icons.Rounded.CheckCircle,
                    size = 40.dp,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Eat the frog",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (frogState?.phase) {
                            null -> "Loading…"
                            FrogPhase.NOT_ARMED -> "Not armed yet — arms on the first unlock or app open after your wake hour"
                            FrogPhase.PICK_FROG -> "Pick today's frog"
                            FrogPhase.WORKING -> frogState.frog?.title ?: "Pick today's frog"
                            FrogPhase.COMPLETE -> frogState.frog?.title ?: "Done"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val frog = frogState?.frog
                when {
                    frogState?.tickedOff == true -> Text(
                        text = "Ticked off",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    frog != null -> FilledTonalButton(
                        onClick = { scope.launch { frogRepo.tickOffFrog(true) } },
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text("Tick off", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (frogState == null) return@Column

            val requiredSeconds = frogState.requiredSeconds
            val trackedSeconds = frogState.trackedSeconds
            val progress = if (requiredSeconds > 0) {
                (trackedSeconds / requiredSeconds.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatFrogClock(trackedSeconds.toLong())} / " +
                        "${formatFrogClock(requiredSeconds.toLong())} · " +
                        when (frogState.phase) {
                            FrogPhase.NOT_ARMED -> "Not armed"
                            FrogPhase.PICK_FROG -> "Pick a frog"
                            FrogPhase.WORKING -> "In progress"
                            FrogPhase.COMPLETE -> "Complete"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (frogState.frog == null) "Pick" else "Change",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = {
                Text(
                    text = if (frogState?.frog == null) "Pick today's frog" else "Change today's frog",
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    FrogPickerBody(
                        openTasks = frogState?.openTasks.orEmpty(),
                        onPick = { task ->
                            scope.launch { frogRepo.selectFrog(task) }
                            showPicker = false
                        },
                        onManual = { title ->
                            if (title.isNotBlank()) {
                                scope.launch { frogRepo.selectFrog(FrogCoordinator.manualFrog(title)) }
                            }
                            showPicker = false
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("Close") }
            },
            shape = MaterialTheme.shapes.large,
        )
    }
}

/** mm:ss clock used by the frog card and the blocker's live tracked time. */
private fun formatFrogClock(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    return "%02d:%02d".format(safe / 60, safe % 60)
}
