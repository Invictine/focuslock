package com.focuslock.app.ui.dashboard.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.model.FrogState
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.service.FrogCoordinator
import com.focuslock.app.service.FrogAppPolicy
import com.focuslock.app.service.FrogHomeLauncher
import com.focuslock.app.service.InstalledApp
import com.focuslock.app.service.InstalledAppsRepository
import com.focuslock.app.ui.components.IconBadge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var manualTitle by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Open tasks (${openTasks.size})",
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
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (refreshing) "Refreshing…" else "Refresh",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (openTasks.isEmpty()) {
            Text(
                text = fetchError ?: "No open tasks loaded. Refresh from TickTick or enter one below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (LocalDensity.current.fontScale >= 1.3f) 320.dp else 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                openTasks.forEach { task ->
                    Surface(
                        onClick = { onPick(task) },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
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
                                    maxLines = 2,
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val title = manualTitle.trim()
                if (title.isNotEmpty()) {
                    onManual(title)
                    manualTitle = ""
                }
            }),
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
    onOpenSettings: (() -> Unit)? = null,
) {
    item(key = key) {
        // "frog" screen key: the entrance plays once per process, not on every
        // Focus-tab return (the legacy null-key path replays it each time).
        HomeEntrance(index = entranceIndex, screenKey = "frog") {
            FrogCard(onOpenSettings = onOpenSettings)
        }
    }
}

/**
 * Compact "eat the frog" status card: title/phase, tracked-vs-required progress,
 * tick-off affordance and tap-through to the picker dialog. Self-contained — it
 * collects the frog flows directly, matching how the other home cards are wired.
 */
@Composable
fun FrogCard(modifier: Modifier = Modifier, onOpenSettings: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val frogRepo = FocusLockApplication.instance.frogRepository
    val scope = rememberCoroutineScope()
    val state by frogRepo.frogStateFlow.collectAsStateWithLifecycle(initialValue = null)
    var showPicker by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var isDefaultHome by remember(context) { mutableStateOf(FrogHomeLauncher.isDefault(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isDefaultHome = FrogHomeLauncher.isDefault(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state?.phase) {
        if (state?.phase == FrogPhase.PICK_TOOLS) showTools = true
    }

    val frogState = state
    if (frogState?.enabled == false) {
        if (onOpenSettings != null) Surface(
            onClick = onOpenSettings,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Daily priority", style = MaterialTheme.typography.titleSmall)
                Text("Eat the Frog is off · Enable in settings", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable {
                if (frogState?.phase == FrogPhase.PICK_TOOLS) showTools = true else showPicker = true
            },
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
                        text = "Daily priority",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (frogState?.phase) {
                            null -> "Eat the Frog · Loading…"
                            FrogPhase.NOT_ARMED -> "Starts at your wake hour; opens when you unlock"
                            FrogPhase.PICK_FROG -> "Pick today's frog"
                            FrogPhase.PICK_TOOLS -> "Choose the tools for today's frog"
                            FrogPhase.WORKING -> frogState.frog?.title ?: "Pick today's frog"
                            FrogPhase.COMPLETE -> frogState.frog?.title ?: "Done"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
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
                        modifier = Modifier.heightIn(min = 48.dp),
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
                            FrogPhase.PICK_TOOLS -> "Choose tools"
                            FrogPhase.WORKING -> "In progress"
                            FrogPhase.COMPLETE -> "Complete"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    text = when (frogState.phase) {
                        FrogPhase.PICK_TOOLS -> "Choose tools"
                        FrogPhase.PICK_FROG, FrogPhase.NOT_ARMED -> "Pick"
                        else -> "Change"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (frogState.locked && !isDefaultHome) {
                TextButton(
                    onClick = {
                        context.findActivity()?.let { activity ->
                            FrogHomeLauncher.captureFallback(context)
                            FrogHomeLauncher.requestDefault(activity)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Prevent launcher escape", style = MaterialTheme.typography.labelMedium)
                }
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
                Box(modifier = Modifier.fillMaxWidth()) {
                    FrogPickerBody(
                        openTasks = frogState?.openTasks.orEmpty(),
                        onPick = { task ->
                            scope.launch {
                                frogRepo.selectFrog(task)
                                showPicker = false
                                showTools = frogRepo.currentState().phase == FrogPhase.PICK_TOOLS
                            }
                        },
                        onManual = { title ->
                            if (title.isNotBlank()) {
                                scope.launch {
                                    frogRepo.selectFrog(FrogCoordinator.manualFrog(title))
                                    showPicker = false
                                    showTools = frogRepo.currentState().phase == FrogPhase.PICK_TOOLS
                                }
                            } else {
                                showPicker = false
                            }
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

    if (showTools && frogState?.phase == FrogPhase.PICK_TOOLS && frogState.frog != null) {
        FrogToolsDialog(
            state = frogState,
            onConfirm = { packages ->
                scope.launch {
                    if (frogRepo.confirmTools(packages)) showTools = false
                }
            },
            onDismiss = { showTools = false },
        )
    }
}

/** mm:ss clock used by the frog card and the blocker's live tracked time. */
private fun formatFrogClock(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    return "%02d:%02d".format(safe / 60, safe % 60)
}

@Composable
private fun FrogToolsDialog(
    state: FrogState,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val defaultPackages = remember(context, state.essentialAppPackages) {
        FrogAppPolicy.essentialLaunchPackages(context, state)
    }
    var selected by rememberSaveable(
        state.cycleDate,
        state.frog?.id,
        stateSaver = androidx.compose.runtime.saveable.Saver<Set<String>, ArrayList<String>>(
            save = { ArrayList(it) },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(state.allowedToolPackages - defaultPackages) }

    LaunchedEffect(state.cycleDate, state.frog?.id) {
        apps = withContext(Dispatchers.IO) {
            InstalledAppsRepository.getInstalledLaunchableApps(context)
                .filterNot { it.packageName in defaultPackages || FrogAppPolicy.isSafetyEssential(context, it.packageName) }
                .sortedBy { it.appName.lowercase() }
        }
        val launchablePackages = apps.mapTo(mutableSetOf()) { it.packageName }
        selected = (state.allowedToolPackages - defaultPackages).intersect(launchablePackages)
        loaded = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Tools for this task", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Your essential apps stay available. Add any extra tools you need for this task.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (!loaded) {
                        Text("Loading apps…", style = MaterialTheme.typography.bodyMedium)
                    } else if (apps.isEmpty()) {
                        Text("No extra apps found.", style = MaterialTheme.typography.bodyMedium)
                    }
                    apps.forEach { app ->
                        val checked = app.packageName in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    selected = if (checked) selected - app.packageName
                                    else selected + app.packageName
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { value ->
                                    selected = if (value) selected + app.packageName
                                    else selected - app.packageName
                                },
                            )
                            Text(
                                app.appName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = loaded) { Text("Confirm tools") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        },
        shape = MaterialTheme.shapes.large,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
