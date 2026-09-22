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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
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
import androidx.compose.ui.platform.LocalContext
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
            ScreenHeader(title = "Strict Mode")
        }
        StaggeredFadeSlide(visible = entered, index = 1, modifier = Modifier.fillMaxWidth(), screenKey = "strict") {
            // Instant hero switch (direct if/else): the previous AnimatedContent kept
            // both hero cards composed during the fade and read as views opening/closing.
            if (strictMode) {
                StrictActiveCard(
                    settings = settings,
                    endsAt = endsAt,
                    attempts = attempts,
                    nukeAfterFive = nukeAfterFive,
                    onExtend = { scope.launch { settings.extendLockdown(maxOf(endsAt, System.currentTimeMillis()) + 60 * 60 * 1000L) } },
                    onNukeAfterFiveChange = { enabled -> scope.launch { settings.setLockdownNukeAfterFive(enabled) } },
                    onDisableClick = {
                        scope.launch {
                            val canDisable = try {
                                settings.canDisableLockdownMode()
                            } catch (_: Exception) {
                                true
                            }
                            if (!canDisable) {
                                val remaining = try {
                                    settings.lockdownCooldownRemainingMs()
                                } catch (_: Exception) {
                                    0L
                                }
                                Toast.makeText(
                                    context,
                                    "Strict Mode locked: ${formatLockdownRemaining(remaining)} remaining",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                showDisableDialog = true
                            }
                        }
                    }
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StrictOffCard(onEnableClick = { showEnableDialog = true })
                    if (attempts > 0) {
                        Text("Last commitment: $attempts blocked ${if (attempts == 1) "attempt" else "attempts"}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        StaggeredFadeSlide(visible = entered, index = 2, modifier = Modifier.fillMaxWidth(), screenKey = "strict") {
            StrictProtectionCard(
                appCount = blockedApps.count { it.isBlocked },
                websiteCount = blockedWebsites.count { it.isBlocked },
                boundariesLock = boundariesLock,
                strictMode = strictMode,
                onBoundariesLockChange = { enabled ->
                    scope.launch { settings.setBoundariesLock(enabled) }
                }
            )
        }

        StaggeredFadeSlide(visible = entered, index = 3, modifier = Modifier.fillMaxWidth(), screenKey = "strict") {
            StrictRulesCard()
        }

        StaggeredFadeSlide(visible = entered, index = 4, modifier = Modifier.fillMaxWidth(), screenKey = "strict") {
            StrictPreferencesCard(
                preset = preset,
                onPresetChange = { selected ->
                    durationHours = when (selected) { "deep_work" -> 2; "exam" -> 4; "sleep" -> 8; else -> 24 }
                    enableUntilTime = false
                    scope.launch {
                        settings.setLockdownPreset(selected)
                        if (selected == "exam") settings.setLockdownNukeAfterFive(true)
                    }
                }
            )
        }
            StrictAutomationCard(places, windows,
            onSavePlace = { scope.launch { automation.upsertPlace(it) } },
            onDeletePlace = { scope.launch { automation.deletePlace(it) } },
            onSaveWindow = { scope.launch { automation.upsertRecurringWindow(it) } },
            onDeleteWindow = { scope.launch { automation.deleteRecurringWindow(it) } }
        )

        ApprovalUnlockCard(settings)

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

@Composable
private fun StrictPreferencesCard(preset: String, onPresetChange: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Session preset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Choose the kind of commitment you are making.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("deep_work" to "Deep work", "exam" to "Exam", "sleep" to "Sleep").forEach { (key, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(label, modifier = Modifier.weight(1f))
                    Switch(checked = preset == key, onCheckedChange = { if (it) onPresetChange(key) })
                }
            }
            Text("This preset is saved with your commitment.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StrictAutomationCard(
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
    var days by rememberSaveable { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var startMinute by rememberSaveable { mutableStateOf(9 * 60) }
    var endMinute by rememberSaveable { mutableStateOf(17 * 60) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Automatic activation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Strict Mode also blocks during these local times or when this phone detects a saved place.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("At a place", fontWeight = FontWeight.SemiBold)
            Text("Search an address or use a fresh device location, then set a radius.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { showPlacePicker = true }, modifier = Modifier.fillMaxWidth()) { Text("Choose place") }
            places.forEach { place ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(place.label, Modifier.weight(1f))
                    Switch(place.enabled, onCheckedChange = { onSavePlace(place.copy(enabled = it)) })
                    TextButton(onClick = { onDeletePlace(place.id) }) { Text("Remove") }
                }
            }
            Text("Every week", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = windowName, onValueChange = { windowName = it },
                label = { Text("Schedule name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { index, label ->
                    val day = index + 1
                    Text(label, modifier = Modifier.weight(1f).clickable {
                        days = if (day in days) days - day else days + day
                    }.padding(vertical = 8.dp),
                        color = if (day in days) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
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
            }) { Text("Add weekly window") }
            windows.forEach { window ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(window.label, Modifier.weight(1f))
                    Switch(window.enabled, onCheckedChange = { onSaveWindow(window.copy(enabled = it)) })
                    TextButton(onClick = { onDeleteWindow(window.id) }) { Text("Remove") }
                }
            }
            Text("Place rules are checked when Android observes an app launch. Location access and a recent position are required.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showPlacePicker) {
        StrictLocationPickerDialog(initialLabel = placeName, onDismiss = { showPlacePicker = false }) { selection ->
            onSavePlace(StrictPlaceRule(UUID.randomUUID().toString(), placeName.ifBlank { selection.label }, selection.latitude, selection.longitude, selection.radiusMeters))
            placeName = ""
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
                modifier = Modifier.fillMaxWidth(),
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
                    onCheckedChange = onBoundariesLockChange
                )
            }
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
                text = "Choose 1–24 hours or a clock time. Unlocks remain unavailable until your commitment ends.",
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

/** Prominent error-container hero with a live countdown while Strict Mode is active. */
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
            containerColor = MaterialTheme.colorScheme.errorContainer
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
                    containerColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Strict Mode is active",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    StrictCooldownText(settings, endsAt)
                }
            }
            Text(
                "${attempts} blocked app ${if (attempts == 1) "attempt" else "attempts"} this session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (attempts in 3..4) {
                Text(if (attempts == 3) "You're at three attempts. Take a short pause before trying again."
                    else "One more blocked attempt will start the 10-minute reset if Nuke is enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Nuke after 5 attempts", fontWeight = FontWeight.SemiBold)
                    Text("Start the 10-minute reset if you keep trying blocked apps.", style = MaterialTheme.typography.bodySmall)
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
                    containerColor = MaterialTheme.colorScheme.onErrorContainer,
                    contentColor = MaterialTheme.colorScheme.errorContainer
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
            color = MaterialTheme.colorScheme.onErrorContainer
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
