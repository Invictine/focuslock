package com.focuslock.app.ui.strict

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.focuslock.app.location.HomeLocationStatus
import com.focuslock.app.location.HomePlace

@Composable
fun HomeLocationCard(
    homePlace: HomePlace?,
    enabled: Boolean,
    strictLocked: Boolean = false,
    status: HomeLocationStatus,
    onSaveHome: (HomePlace) -> Unit,
    onHomeOnlyChange: (Boolean) -> Unit,
    onChooseLocation: (StrictLocationSelection?, String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumedGeneration by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumedGeneration++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var pendingEnable by remember { mutableStateOf(false) }
    var showBackgroundRationale by remember { mutableStateOf(false) }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingEnable) {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine && background) onHomeOnlyChange(true)
            pendingEnable = false
        }
        resumedGeneration++
    }
    @Suppress("UNUSED_VARIABLE") val permissionRefresh = resumedGeneration
    val preciseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val backgroundGranted = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
    val deviceLocationEnabled = manager?.let { runCatching { it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) } ?: false
    var homeLabel by rememberSaveable(homePlace?.label) { mutableStateOf(homePlace?.label ?: "Home") }
    var renaming by rememberSaveable { mutableStateOf(false) }
    val backgroundPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingEnable) onHomeOnlyChange(true)
        pendingEnable = false
    }
    val foregroundPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val precise = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!precise) {
            pendingEnable = false
            return@rememberLauncherForActivityResult
        }
        if (pendingEnable) {
            if (Build.VERSION.SDK_INT == 29) backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else if (Build.VERSION.SDK_INT >= 30) showBackgroundRationale = true
            else onHomeOnlyChange(true)
        }
    }

    fun beginEnable() {
        if (strictLocked) return
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            pendingEnable = true
            foregroundPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        if (Build.VERSION.SDK_INT == 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingEnable = true
            backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (Build.VERSION.SDK_INT >= 30 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingEnable = true
            showBackgroundRationale = true
        } else onHomeOnlyChange(true)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Only block at home", style = MaterialTheme.typography.titleLarge)
            if (strictLocked) {
                Text("Strict Mode is active. Boundary settings are locked until it ends.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Only a fresh, precise location fix that confirms you are away pauses everyday blocking. If location is off, unavailable, unclear, or permission is missing, blocking stays active. Permanent blocks always stay active.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (homePlace == null) {
                Text("Home location is not set", style = MaterialTheme.typography.titleSmall)
                Button(onClick = { onChooseLocation(null, homeLabel.ifBlank { "Home" }) }, enabled = !strictLocked, modifier = Modifier.fillMaxWidth()) { Text("Choose home location") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(homePlace.label.ifBlank { "Home" }, style = MaterialTheme.typography.titleMedium)
                        Text("${homePlace.radiusMeters.toInt()} m radius", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(enabled = !strictLocked, onClick = { onChooseLocation(StrictLocationSelection(homePlace.label, homePlace.latitude, homePlace.longitude, homePlace.radiusMeters), homeLabel) }) {
                        Text("Edit")
                    }
                }
                TextButton(onClick = {
                    if (renaming) homeLabel = homePlace.label
                    renaming = !renaming
                }, enabled = !strictLocked) { Text(if (renaming) "Cancel rename" else "Rename") }
                if (renaming) {
                    OutlinedTextField(value = homeLabel, onValueChange = { homeLabel = it }, enabled = !strictLocked, label = { Text("Home label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = {
                        onSaveHome(homePlace.copy(label = homeLabel.ifBlank { "Home" }))
                        renaming = false
                    }, enabled = !strictLocked) { Text("Save label") }
                }
                Text(statusText(status, enabled), style = MaterialTheme.typography.bodyMedium)
                if (enabled && (!preciseGranted || !backgroundGranted || !deviceLocationEnabled)) {
                    Text(
                        when {
                            !preciseGranted -> "Precise location permission is missing. Blocking stays active until an away location can be confirmed."
                            !backgroundGranted -> "Background location permission is missing. Blocking stays active until an away location can be confirmed."
                            else -> "Device location is off. Blocking stays active until an away location can be confirmed."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only block at home", style = MaterialTheme.typography.titleSmall)
                        Text("Applies to blocking on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = enabled, enabled = !strictLocked, onCheckedChange = { if (it) beginEnable() else onHomeOnlyChange(false) })
                }
                if (enabled && !preciseGranted) {
                    TextButton(onClick = { beginEnable() }) { Text("Allow precise location") }
                }
                if (enabled && deviceLocationEnabled.not()) {
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } }) { Text("Open device location settings") }
                }
                if (enabled && !backgroundGranted && Build.VERSION.SDK_INT == 29) {
                    Text("Allow all the time so FocusLock can confirm when you are away, even when the app is closed. Without a fresh away fix, blocking stays active.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        pendingEnable = true
                        backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }) { Text("Allow all the time") }
                }
                if (enabled && !backgroundGranted && Build.VERSION.SDK_INT >= 30) {
                    Text("Allow all the time lets FocusLock confirm when you are away, even when the app is closed. Without a fresh away fix, blocking stays active.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { openAppDetails(context, settingsLauncher) }) { Text("Open location permission settings") }
                }
            }
        }
    }

    if (showBackgroundRationale) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBackgroundRationale = false; pendingEnable = false },
            title = { Text("Allow location all the time") },
            text = { Text("Home-only mode needs background location to confirm when you are away. Blocking stays active unless a fresh, precise fix confirms you are outside the home area. On Android 11 and later, choose Allow all the time on the next app settings screen.") },
            confirmButton = { TextButton(onClick = { showBackgroundRationale = false; openAppDetails(context, settingsLauncher) }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = { showBackgroundRationale = false; pendingEnable = false }) { Text("Not now") } }
        )
    }
}

private fun statusText(status: HomeLocationStatus, enabled: Boolean): String = when {
    !enabled -> "Home-only mode is off. Blocking can work wherever you are."
    status == HomeLocationStatus.AT_HOME -> "Active · You're at home"
    status == HomeLocationStatus.AWAY -> "Paused · A fresh location confirms you're away"
    else -> "Active · Waiting for a fresh, accurate location"
}

private fun openAppDetails(context: android.content.Context, launcher: androidx.activity.compose.ManagedActivityResultLauncher<Intent, androidx.activity.result.ActivityResult>) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    launcher.launch(intent)
}
