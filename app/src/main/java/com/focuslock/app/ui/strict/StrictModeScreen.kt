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

/** Shared duration and automation editors for the Strict Mode and location screens. */
@Composable
fun StrictModeScreen(onOpenPermissions: () -> Unit = {}) {
    StrictAccessScreen(onOpenPermissions)
}

@Composable
internal fun StrictScheduleEditor() {
    val automation = FocusLockApplication.instance.strictModeAutomationRepository
    val windows by automation.recurringWindowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    StrictAutomationCard(
        mode = StrictAutomationMode.SCHEDULE, places = emptyList(), windows = windows,
        onSavePlace = {}, onDeletePlace = {},
        onSaveWindow = { scope.launch { automation.upsertRecurringWindow(it) } },
        onDeleteWindow = { scope.launch { automation.deleteRecurringWindow(it) } },
    )
}

@Composable
internal fun StrictLocationAutomationEditor() {
    val automation = FocusLockApplication.instance.strictModeAutomationRepository
    val places by automation.placesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    StrictAutomationCard(
        mode = StrictAutomationMode.LOCATION, places = places, windows = emptyList(),
        onSavePlace = { scope.launch { automation.upsertPlace(it) } },
        onDeletePlace = { scope.launch { automation.deletePlace(it) } },
        onSaveWindow = {}, onDeleteWindow = {},
    )
}

@Composable
internal fun StrictEnableOptions(
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    }
}

private enum class StrictAutomationMode { SCHEDULE, LOCATION }

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

private fun formatDurationHours(hours: Int): String = when {
    hours >= 24 && hours % 24 == 0 -> "${hours / 24} ${if (hours == 24) "day" else "days"}"
    hours == 1 -> "1 hour"
    else -> "$hours hours"
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
