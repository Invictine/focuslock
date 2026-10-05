package com.focuslock.app.ui.strict

import android.app.TimePickerDialog
import android.app.DatePickerDialog
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focuslock.app.ui.components.UiTokens
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.data.repository.StrictPlaceRule
import com.focuslock.app.data.repository.StrictRecurringWindow
import com.focuslock.app.ui.components.IconBadge
import com.focuslock.app.ui.components.ScreenHeader
import com.focuslock.app.ui.components.StaggeredFadeSlide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

/**
 * Strict Mode tab. Owns the timed no-unlock commitment UI that used to live
 * inside SettingsScreen: the enable/disable confirmation dialogs, the error-container
 * status hero with a live cooldown countdown, and the consequences list.
 *
 * Persistence is unchanged — everything goes through [SettingsRepository.setLockdownMode],
 * and the cooldown is enforced by [SettingsRepository.canDisableLockdownMode].
 */
@Composable
fun StrictModeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = FocusLockApplication.instance.settingsRepository
    val strictMode by settings.lockdownModeFlow.collectAsStateWithLifecycle(initialValue = false)
    val boundariesLock by settings.boundariesLockFlow.collectAsStateWithLifecycle(initialValue = false)
    val blockedApps by settings.blockedAppsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val blockedWebsites by settings.blockedWebsitesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val endsAt by settings.lockdownEndsAtFlow.collectAsStateWithLifecycle(initialValue = 0L)
    val attempts by settings.lockdownAttemptCountFlow.collectAsStateWithLifecycle(initialValue = 0)
    val nukeAfterFive by settings.lockdownNukeAfterFiveFlow.collectAsStateWithLifecycle(initialValue = false)
    val preset by settings.lockdownPresetFlow.collectAsStateWithLifecycle(initialValue = "deep_work")
    val automation = FocusLockApplication.instance.strictModeAutomationRepository
    val places by automation.placesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val windows by automation.recurringWindowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showEnableDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var activationMode by rememberSaveable { mutableStateOf("manual") }
    var durationHours by rememberSaveable { mutableStateOf(24) }
    var enableUntilTime by rememberSaveable { mutableStateOf(false) }
    var untilHour by rememberSaveable { mutableStateOf(22) }
    var untilMinute by rememberSaveable { mutableStateOf(30) }
    var untilYear by rememberSaveable { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var untilMonth by rememberSaveable { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH)) }
    var untilDay by rememberSaveable { mutableStateOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH)) }
    // One-shot entrance cascade (header -> intro -> hero -> rules); remembered so it
    // runs on first composition only and never replays on scroll or state flips.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UiTokens.ScreenPadding)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StaggeredFadeSlide(visible = entered, index = 0, screenKey = "strict") {
            ScreenHeader(title = "Strict Mode", subtitle = "Protect your existing boundaries with a timed commitment.")
        }
        if (strictMode) {
            StaggeredFadeSlide(visible = entered, index = 1, modifier = Modifier.fillMaxWidth(), screenKey = "strict") {
                StrictActiveCard(
                    settings = settings,
                    endsAt = endsAt,
                    attempts = attempts,
                    nukeAfterFive = nukeAfterFive,
                    onExtend = { scope.launch { settings.extendLockdown(maxOf(endsAt, System.currentTimeMillis()) + 60 * 60 * 1000L) } },
                    onNukeAfterFiveChange = { enabled -> scope.launch { settings.setLockdownNukeAfterFive(enabled) } },
                    onDisableClick = {
                        scope.launch {
                            val canDisable = try { settings.canDisableLockdownMode() } catch (_: Exception) { true }
                            if (!canDisable) {
                                val remaining = try { settings.lockdownCooldownRemainingMs() } catch (_: Exception) { 0L }
                                Toast.makeText(context, "Strict Mode locked: ${formatLockdownRemaining(remaining)} remaining", Toast.LENGTH_LONG).show()
                            } else showDisableDialog = true
                        }
                    }
                )
            }
        }

        StaggeredFadeSlide(visible = entered, index = 2, modifier = Modifier.fillMaxWidth(), screenKey = "strict") {
            StrictActivationSwitcher(selected = activationMode, onSelect = { activationMode = it })
        }

        StaggeredFadeSlide(visible = entered, index = 3, modifier = Modifier.fillMaxWidth(), screenKey = "strict") {
            when (activationMode) {
                "schedule" -> StrictAutomationCard(
                    mode = StrictAutomationMode.SCHEDULE,
                    places = places,
                    windows = windows,
                    onSavePlace = { scope.launch { automation.upsertPlace(it) } },
                    onDeletePlace = { scope.launch { automation.deletePlace(it) } },
                    onSaveWindow = { scope.launch { automation.upsertRecurringWindow(it) } },
                    onDeleteWindow = { scope.launch { automation.deleteRecurringWindow(it) } }
                )
                "location" -> StrictAutomationCard(
                    mode = StrictAutomationMode.LOCATION,
                    places = places,
                    windows = windows,
                    onSavePlace = { scope.launch { automation.upsertPlace(it) } },
                    onDeletePlace = { scope.launch { automation.deletePlace(it) } },
                    onSaveWindow = { scope.launch { automation.upsertRecurringWindow(it) } },
                    onDeleteWindow = { scope.launch { automation.deleteRecurringWindow(it) } }
                )
                else -> StrictManualActivation(
                    strictMode = strictMode,
                    attempts = attempts,
                    preset = preset,
                    onPresetChange = { selected ->
                        durationHours = when (selected) { "deep_work" -> 2; "exam" -> 4; "sleep" -> 8; else -> 24 }
                        enableUntilTime = false
                        scope.launch {
                            settings.setLockdownPreset(selected)
                            if (selected == "exam") settings.setLockdownNukeAfterFive(true)
                        }
                    },
                    onEnableClick = { showEnableDialog = true }
                )
            }
        }

        StaggeredFadeSlide(visible = entered, index = 4, modifier = Modifier.fillMaxWidth(), screenKey = "strict") {
            MoreProtectionOptions {
              StrictProtectionCard(
                appCount = blockedApps.count { it.isBlocked },
                websiteCount = blockedWebsites.count { it.isBlocked },
                boundariesLock = boundariesLock,
                strictMode = strictMode,
                onBoundariesLockChange = { enabled ->
                    scope.launch { settings.setBoundariesLock(enabled) }
                }
            )
              StrictRulesCard()
              ApprovalUnlockCard(settings)
            }
        }

        if (showEnableDialog) {
            AlertDialog(
                onDismissRequest = { showEnableDialog = false },
                title = { Text("Enable Strict Mode?") },
                text = {
                    StrictEnableOptions(
                        durationHours = durationHours,
                        onDurationChange = { durationHours = it },
                        untilTime = enableUntilTime,
                        onUntilChange = { enableUntilTime = it },
                        hour = untilHour,
                        minute = untilMinute,
                        onPickTime = {
                            val picker = TimePickerDialog(
                                context,
                                { _, h, m -> untilHour = h; untilMinute = m },
                                untilHour,
                                untilMinute,
                                android.text.format.DateFormat.is24HourFormat(context)
                            )
                            picker.show()
                        },
                        year = untilYear,
                        month = untilMonth,
                        day = untilDay,
                        onPickDate = {
                            val picker = DatePickerDialog(context, { _, y, m, d -> untilYear = y; untilMonth = m; untilDay = d }, untilYear, untilMonth, untilDay)
                            picker.datePicker.minDate = System.currentTimeMillis()
                            picker.datePicker.maxDate = System.currentTimeMillis() + SettingsRepository.MAX_LOCKDOWN_DURATION_HOURS * 60 * 60 * 1000L
                            picker.show()
                        },
                        nukeAfterFive = nukeAfterFive,
                        onNukeAfterFiveChange = { enabled ->
                            scope.launch { settings.setLockdownNukeAfterFive(enabled) }
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val endAt = if (enableUntilTime) {
                            Calendar.getInstance().apply { set(untilYear, untilMonth, untilDay, untilHour, untilMinute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                        } else {
                            System.currentTimeMillis() + durationHours * 60 * 60 * 1000L
                        }
                        val now = System.currentTimeMillis()
                        if (endAt <= now || endAt > now + SettingsRepository.MAX_LOCKDOWN_DURATION_HOURS * 60 * 60 * 1000L) {
                            Toast.makeText(context, "Choose an end within the next 30 days", Toast.LENGTH_LONG).show()
                        } else {
                            showEnableDialog = false
                            scope.launch { settings.setLockdownMode(true, endAt) }
                        }
                    }) { Text("Enable") }
                },
                dismissButton = {
                    TextButton(onClick = { showEnableDialog = false }) { Text("Cancel") }
                },
                shape = MaterialTheme.shapes.large
            )
        }
        if (showDisableDialog) {
            AlertDialog(
                onDismissRequest = { showDisableDialog = false },
                title = { Text("Disable Strict Mode?") },
                text = { Text("This re-enables unlock options.") },
                confirmButton = {
                    TextButton(onClick = {
                        showDisableDialog = false
                        scope.launch { settings.setLockdownMode(false) }
                    }) { Text("Disable") }
                },
                dismissButton = {
                    TextButton(onClick = { showDisableDialog = false }) { Text("Cancel") }
                },
                shape = MaterialTheme.shapes.large
            )
        }
    }
}

@Composable
private fun StrictEnableOptions(
    durationHours: Int,
    onDurationChange: (Int) -> Unit,
    untilTime: Boolean,
    onUntilChange: (Boolean) -> Unit,
    hour: Int,
    minute: Int,
    onPickTime: () -> Unit,
    year: Int,
    month: Int,
    day: Int,
    onPickDate: () -> Unit,
    nukeAfterFive: Boolean,
    onNukeAfterFiveChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose how long", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (untilTime) "Until a date and time" else formatDurationHours(durationHours), modifier = Modifier.weight(1f))
            Switch(checked = untilTime, onCheckedChange = onUntilChange)
        }
        if (untilTime) {
            TextButton(onClick = onPickDate, modifier = Modifier.fillMaxWidth()) {
                Text("End on %04d-%02d-%02d".format(year, month + 1, day))
            }
            TextButton(onClick = onPickTime, modifier = Modifier.fillMaxWidth()) {
                Text("End at ${"%02d:%02d".format(hour, minute)}")
            }
        } else {
            val days = durationHours >= 24
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(selected = !days, onClick = { if (days) onDurationChange(1) },
                    label = { Text("Hours") }, modifier = Modifier.weight(1f).height(48.dp))
                FilterChip(selected = days, onClick = { if (!days) onDurationChange(24) },
                    label = { Text("Days") }, modifier = Modifier.weight(1f).height(48.dp))
            }
            Slider(
                value = if (days) (durationHours / 24f).coerceIn(1f, 30f) else durationHours.toFloat().coerceIn(1f, 23f),
                onValueChange = { onDurationChange(if (days) kotlin.math.round(it).toInt().coerceIn(1, 30) * 24 else kotlin.math.round(it).toInt().coerceIn(1, 23)) },
                valueRange = if (days) 1f..30f else 1f..23f,
                steps = if (days) 28 else 21
            )
            Text("Choose from 1 hour to 30 days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Strict Mode ends automatically when this commitment expires.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nuke after five blocked launches")
                Text("Start the 10-minute reset on the fifth attempt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(nukeAfterFive, onCheckedChange = onNukeAfterFiveChange)
        }
    }
}

private enum class StrictAutomationMode { SCHEDULE, LOCATION }

@Composable
private fun StrictActivationSwitcher(selected: String, onSelect: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Activation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Choose how you want to set up Strict Mode.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StrictChoiceSelector(
                options = listOf("manual" to "Manual", "schedule" to "Schedule", "location" to "Location"),
                selected = selected,
                onSelect = onSelect
            )
        }
    }
}

@Composable
private fun StrictManualActivation(
    strictMode: Boolean,
    attempts: Int,
    preset: String,
    onPresetChange: (String) -> Unit,
    onEnableClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!strictMode) {
            StrictOffCard(onEnableClick = onEnableClick)
            if (attempts > 0) {
                Text("Last commitment: $attempts blocked ${if (attempts == 1) "attempt" else "attempts"}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        StrictPreferencesCard(preset = preset, onPresetChange = onPresetChange)
    }
}

@Composable
private fun StrictPreferencesCard(preset: String, onPresetChange: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Commitment style", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Choose the kind of focus session. This preset is saved with your commitment.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StrictChoiceSelector(
                options = listOf("deep_work" to "Deep work", "exam" to "Exam", "sleep" to "Sleep"),
                selected = preset,
                onSelect = onPresetChange
            )
        }
    }
}

@Composable
private fun StrictChoiceSelector(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEach { (key, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected == key, onClick = { onSelect(key) }, role = Role.RadioButton)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == key, onClick = null)
                    Text(label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    } else {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (key, label) ->
                SegmentedButton(
                    selected = selected == key,
                    onClick = { onSelect(key) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(label, maxLines = 1) }
                )
            }
        }
    }
}

@Composable
private fun StrictAutomationCard(
    mode: StrictAutomationMode,
    places: List<StrictPlaceRule>,
    windows: List<StrictRecurringWindow>,
    onSavePlace: (StrictPlaceRule) -> Unit,
    onDeletePlace: (String) -> Unit,
    onSaveWindow: (StrictRecurringWindow) -> Unit,
    onDeleteWindow: (String) -> Unit,
) {
    val context = LocalContext.current
    var placeName by rememberSaveable { mutableStateOf("") }
    var windowName by rememberSaveable { mutableStateOf("") }
    var showPlacePicker by remember { mutableStateOf(false) }
    var editingPlace by remember { mutableStateOf<StrictPlaceRule?>(null) }
    var days by rememberSaveable { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var startMinute by rememberSaveable { mutableStateOf(9 * 60) }
    var endMinute by rememberSaveable { mutableStateOf(17 * 60) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (mode == StrictAutomationMode.LOCATION) {
                Text("Activate at a place", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("With Location activation selected, Strict Mode stays active unless a fresh, precise fix confirms you are outside every enabled place. An uncertain boundary reading keeps Strict Mode active.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("If location, required permissions, or background access is unavailable, or no fresh reliable fix is available, Strict Mode stays active. Home-only blocking is configured separately under Boundaries.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Precise location and background access let FocusLock confirm when you are outside all enabled places.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { showPlacePicker = true }, modifier = Modifier.fillMaxWidth()) { Text("Choose place") }
                places.forEach { place ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = place.enabled, role = Role.Switch) { onSavePlace(place.copy(enabled = it)) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(place.label, fontWeight = FontWeight.Medium)
                            Text("Saved place", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = place.enabled,
                            onCheckedChange = null,
                            modifier = Modifier.semantics { contentDescription = "Activate Strict Mode at ${place.label}" }
                        )
                        TextButton(onClick = {
                            editingPlace = place
                            placeName = place.label
                            showPlacePicker = true
                        }) { Text("Edit") }
                        TextButton(onClick = { onDeletePlace(place.id) }) { Text("Remove") }
                    }
                }
                if (places.isEmpty()) Text("No places yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Activate on a schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Strict Mode activates during enabled weekly windows.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = windowName, onValueChange = { windowName = it },
                    label = { Text("Schedule name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                listOf(
                    listOf("Mon" to 1, "Tue" to 2, "Wed" to 3, "Thu" to 4),
                    listOf("Fri" to 5, "Sat" to 6, "Sun" to 7)
                ).forEach { rowDays ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowDays.forEach { (label, day) ->
                            FilterChip(
                                selected = day in days,
                                onClick = { days = if (day in days) days - day else days + day },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f).height(48.dp)
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        TimePickerDialog(context, { _, h, m -> startMinute = h * 60 + m },
                            startMinute / 60, startMinute % 60, true).show()
                    }) { Text("From %02d:%02d".format(startMinute / 60, startMinute % 60)) }
                    TextButton(onClick = {
                        TimePickerDialog(context, { _, h, m -> endMinute = h * 60 + m },
                            endMinute / 60, endMinute % 60, true).show()
                    }) { Text("To %02d:%02d".format(endMinute / 60, endMinute % 60)) }
                }
                TextButton(onClick = {
                    if (days.isNotEmpty() && startMinute != endMinute) {
                        onSaveWindow(StrictRecurringWindow(UUID.randomUUID().toString(),
                            windowName.ifBlank { "Weekly focus" }, days, startMinute, endMinute))
                        windowName = ""
                    }
                }, enabled = days.isNotEmpty() && startMinute != endMinute) { Text("Add weekly window") }
                windows.forEach { window ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = window.enabled, role = Role.Switch) { onSaveWindow(window.copy(enabled = it)) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(window.label, fontWeight = FontWeight.Medium)
                            Text("${window.daysOfWeek.size} days · %02d:%02d–%02d:%02d".format(window.startMinuteOfDay / 60, window.startMinuteOfDay % 60, window.endMinuteOfDay / 60, window.endMinuteOfDay % 60),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = window.enabled, onCheckedChange = null,
                            modifier = Modifier.semantics { contentDescription = "Enable schedule ${window.label}" })
                        TextButton(onClick = { onDeleteWindow(window.id) }) { Text("Remove") }
                    }
                }
                if (windows.isEmpty()) Text("No weekly windows yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showPlacePicker) {
        StrictLocationPickerDialog(
            initialLabel = editingPlace?.label ?: placeName,
            initialSelection = editingPlace?.let { StrictLocationSelection(it.label, it.latitude, it.longitude, it.radiusMeters) },
            onDismiss = { showPlacePicker = false; editingPlace = null }
        ) { selection ->
            onSavePlace(StrictPlaceRule(editingPlace?.id ?: UUID.randomUUID().toString(), placeName.ifBlank { selection.label }, selection.latitude, selection.longitude, selection.radiusMeters, editingPlace?.enabled ?: true))
            placeName = ""
            editingPlace = null
        }
    }
}

private fun nextOccurrenceMillis(hour: Int, minute: Int): Long {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (target.timeInMillis <= now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 1)
    return target.timeInMillis
}

private fun formatDurationHours(hours: Int): String = when {
    hours >= 24 && hours % 24 == 0 -> "${hours / 24} ${if (hours == 24) "day" else "days"}"
    hours == 1 -> "1 hour"
    else -> "$hours hours"
}

@Composable
private fun StrictProtectionCard(
    appCount: Int,
    websiteCount: Int,
    boundariesLock: Boolean,
    strictMode: Boolean,
    onBoundariesLockChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Your protection",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                "$appCount blocked ${if (appCount == 1) "app" else "apps"} · " +
                    "$websiteCount blocked ${if (websiteCount == 1) "website" else "websites"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = boundariesLock, role = Role.Switch, onValueChange = onBoundariesLockChange),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Boundaries Lock",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        if (strictMode) {
                            "Keep removals locked after Strict Mode ends. You can still add blocks."
                        } else {
                            "Prevent removing blocked apps and websites. You can still add blocks."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = boundariesLock,
                    onCheckedChange = null,
                    modifier = Modifier.semantics { contentDescription = "Boundaries Lock" }
                )
            }
        }
    }
}

@Composable
private fun MoreProtectionOptions(content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("More protection options", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse protection options" else "Expand protection options"
                )
            }
            if (expanded) content()
        }
    }
}

/** Neutral tonal hero shown while Strict Mode is off, with the primary enable action. */
@Composable
private fun StrictOffCard(onEnableClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IconBadge(
                    icon = Icons.Rounded.Shield,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Strict Mode is off",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Ready when you need a hard reset",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "Choose 1 hour to 30 days or a date and time. Unlocks remain unavailable until your commitment ends.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onEnableClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Enable Strict Mode", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Prominent secondary-container hero with a live countdown while Strict Mode is active. */
@Composable
private fun StrictActiveCard(
    settings: SettingsRepository,
    endsAt: Long,
    attempts: Int,
    nukeAfterFive: Boolean,
    onExtend: () -> Unit,
    onNukeAfterFiveChange: (Boolean) -> Unit,
    onDisableClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IconBadge(
                    icon = Icons.Rounded.Lock,
                    containerColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Strict Mode is active",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    StrictCooldownText(settings, endsAt)
                }
            }
            Text(
                "${attempts} blocked app ${if (attempts == 1) "attempt" else "attempts"} this session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (attempts in 3..4) {
                Text(if (attempts == 3) "You're at three attempts. Take a short pause before trying again."
                    else "One more blocked attempt will start the 10-minute reset if Nuke is enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Nuke after 5 attempts", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Start the 10-minute reset if you keep trying blocked apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Switch(checked = nukeAfterFive, onCheckedChange = onNukeAfterFiveChange)
            }
            TextButton(onClick = onExtend, modifier = Modifier.fillMaxWidth()) {
                Text("Extend by 1 hour")
            }
            Button(
                onClick = onDisableClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    contentColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = MaterialTheme.shapes.large
            ) {
                    Text("Disable Strict Mode", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Short list of consequences, each stated from actual enforcement code paths. */
@Composable
private fun StrictRulesCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "How Strict Mode works",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "For the time you choose, blocked apps stay blocked and unlocks are unavailable.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(if (expanded) "Hide rules" else "Read all rules") }
            // Show detail on request so the active state and primary action stay visible.
            val rules = listOf(
                Triple(
                    Icons.Rounded.Lock,
                    "A commitment with accountability",
                    "The Disable button stays locked until your selected end time. " +
                        "A trusted person configured before the commitment can approve an early exit by email."
                ),
                Triple(
                    Icons.Rounded.Block,
                    "No unlock paths",
                    "Emergency unlocks and earned-credit unlocks are refused. Credits are " +
                        "still banked, they just can't open a blocked app."
                ),
                Triple(
                    Icons.Rounded.Shield,
                    "Boundaries stay locked",
                    "Blocked apps and websites can't be unblocked until Strict Mode ends — " +
                        "you can still add new blocks."
                ),
                Triple(
                    Icons.Rounded.Timer,
                    "No grace period",
                    "Blocked apps and sites open the blocker immediately, even while you " +
                        "still have leisure balance."
                ),
                Triple(
                    Icons.Rounded.Sync,
                    "Carries across devices",
                    "When you're signed in, the commitment syncs so switching devices " +
                        "won't dodge the lock."
                )
            )
            if (expanded) {
                // This section is revealed by a deliberate tap. Render its content
                // immediately instead of stacking entrance animations inside a card.
                rules.forEach { (icon, title, body) ->
                    StrictRuleRow(icon = icon, title = title, body = body)
                }
            }
        }
    }
}

@Composable
private fun StrictRuleRow(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(
            icon = icon,
            size = 36.dp,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StrictCooldownText(settings: SettingsRepository, endsAt: Long) {
    var cooldownText by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Repeat only while RESUMED so a backgrounded screen stops refreshing its state.
    LaunchedEffect(endsAt) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val remaining = if (endsAt > 0L) {
                    (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
                } else try { settings.lockdownCooldownRemainingMs() } catch (_: Exception) { 0L }
                cooldownText = if (remaining > 0L) {
                    "Ends in ${formatLockdownRemaining(remaining)}"
                } else {
                    "Disable available now"
                }
                if (remaining == 0L) {
                    if (endsAt > 0L) settings.expireLockdownIfNeeded()
                    break
                }
                // The label displays whole minutes. Wake at the next displayed
                // minute boundary or expiry, rather than recomposing every second.
                delay(if (remaining < 60_000L) remaining else (remaining % 60_000L + 1L).coerceAtMost(60_000L))
            }
        }
    }
    if (cooldownText.isNotBlank()) {
        Text(
            text = cooldownText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/**
 * Formats a cooldown with days, hours and minutes. Internal so the Boundaries screens can
 * reuse the exact same formatter in Strict Mode refusal copy.
 */
internal fun formatLockdownRemaining(ms: Long): String {
    if (ms in 1L..59_999L) return "less than a minute"
    val days = ms / 86_400_000L
    val h = (ms % 86_400_000L) / 3_600_000L
    val m = (ms % 3_600_000L) / 60_000L
    return if (days > 0) "${days}d ${h}h ${m}m" else "${h}h ${m}m"
}
