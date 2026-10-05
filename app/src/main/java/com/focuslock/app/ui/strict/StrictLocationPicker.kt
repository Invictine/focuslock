package com.focuslock.app.ui.strict

import android.content.Intent
import android.provider.Settings
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.focuslock.app.location.DeviceLocationSource
import java.util.Locale

data class StrictLocationSelection(val label: String, val latitude: Double, val longitude: Double, val radiusMeters: Float)

private data class GeocodedPlace(val label: String, val latitude: Double, val longitude: Double)

/** A real place picker: search results are selectable, and the map action opens a geo preview. */
@Composable
fun StrictLocationPickerDialog(
    initialLabel: String,
    initialRadiusMeters: Float = 150f,
    initialSelection: StrictLocationSelection? = null,
    onDismiss: () -> Unit,
    onSelected: (StrictLocationSelection) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialLabel) }
    var results by remember { mutableStateOf<List<GeocodedPlace>>(emptyList()) }
    var selected by remember { mutableStateOf(initialSelection?.let { GeocodedPlace(it.label, it.latitude, it.longitude) }) }
    var radius by remember { mutableStateOf((initialSelection?.radiusMeters ?: initialRadiusMeters).coerceIn(50f, 1000f)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var providersOff by remember { mutableStateOf(false) }
    var retryCurrent by remember { mutableStateOf(false) }
    var saveAfterPermission by remember { mutableStateOf(false) }

    fun saveSelection() {
        selected?.let { onSelected(StrictLocationSelection(it.label, it.latitude, it.longitude, radius)); onDismiss() }
    }

    fun search() {
        if (loading) return
        val text = query.trim()
        if (text.length < 3) { error = "Enter at least 3 characters to search"; return }
        loading = true; error = null; selected = null
        scope.launch {
            try {
                check(Geocoder.isPresent()) { "Address search is unavailable on this device. Use current location instead." }
                results = withContext(Dispatchers.IO) {
                    Geocoder(context, Locale.getDefault()).getFromLocationName(text, 8).orEmpty()
                        .filter { it.hasLatitude() && it.hasLongitude() }
                        .map { GeocodedPlace(it.getAddressLine(0) ?: text, it.latitude, it.longitude) }
                }
                if (results.isEmpty()) error = "No places found. Try a fuller address or postcode."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                results = emptyList()
                error = "Address search is unavailable. Check your connection or use current location."
            } finally {
                loading = false
            }
        }
    }

    fun currentPlace() {
        if (loading) return
        loading = true; error = null
        scope.launch {
            try {
                val location = DeviceLocationSource(context).currentLocation(maxAgeMs = 30_000L, timeoutMs = 15_000L)
                if (location == null) {
                    val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
                    providersOff = manager?.let { !it.isProviderEnabled(LocationManager.GPS_PROVIDER) && !it.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } ?: false
                    error = if (providersOff) "Turn on device location to use your current position." else "Could not get a precise position. Move near a window and try again."
                }
                else {
                    providersOff = false
                    results = emptyList()
                    selected = GeocodedPlace("Current location", location.latitude, location.longitude)
                    if (location.hasAccuracy() && location.accuracy > radius) {
                        radius = location.accuracy.coerceIn(50f, 1000f)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not get your location. Check location permission and try again." }
            finally { loading = false }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (saveAfterPermission) saveSelection() else retryCurrent = true
        }
        else error = "Precise location permission is needed for place rules."
        saveAfterPermission = false
    }
    LaunchedEffect(retryCurrent) {
        if (retryCurrent) { retryCurrent = false; currentPlace() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a place") },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text("Search address") }, enabled = !loading, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::search, enabled = !loading) { Text("Search") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(enabled = !loading, onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                            currentPlace()
                        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Text("Use current location") }
                    selected?.let { place ->
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${place.latitude},${place.longitude}?q=${place.latitude},${place.longitude}(${Uri.encode(place.label)})"))) }
                                .onFailure { error = "No map app is available for preview." }
                        }) { Text("Preview map") }
                    }
                }
                if (loading) CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                if (providersOff) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }) { Text("Open location settings") }
                results.forEach { place ->
                    TextButton(onClick = { selected = place }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text(place.label, modifier = Modifier.fillMaxWidth())
                    }
                }
                selected?.let { place ->
                    Text("Selected: ${place.label}", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                    Text("%.5f, %.5f".format(Locale.US, place.latitude, place.longitude), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    Text("Radius: ${radius.toInt()} m")
                    Slider(value = radius, onValueChange = { radius = it }, valueRange = 50f..1000f, steps = 18)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) saveSelection()
                else {
                    saveAfterPermission = true
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }, enabled = selected != null && !loading) { Text("Save place") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
