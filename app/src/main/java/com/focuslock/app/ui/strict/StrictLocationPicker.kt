package com.focuslock.app.ui.strict

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.Locale

data class StrictLocationSelection(val label: String, val latitude: Double, val longitude: Double, val radiusMeters: Float)

private data class GeocodedPlace(val label: String, val latitude: Double, val longitude: Double)

/** A real place picker: search results are selectable, and the map action opens a geo preview. */
@Composable
fun StrictLocationPickerDialog(
    initialLabel: String,
    initialRadiusMeters: Float = 150f,
    onDismiss: () -> Unit,
    onSelected: (StrictLocationSelection) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialLabel) }
    var results by remember { mutableStateOf<List<GeocodedPlace>>(emptyList()) }
    var selected by remember { mutableStateOf<GeocodedPlace?>(null) }
    var radius by remember { mutableStateOf(initialRadiusMeters.coerceIn(50f, 1000f)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
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
                val location = freshLocation(context)
                if (location == null) error = "Could not get a precise position. Turn on location, move near a window, and try again."
                else {
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
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(results) { place ->
                        TextButton(onClick = { selected = place }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                            Text(place.label, modifier = Modifier.fillMaxWidth())
                        }
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

fun recentLocation(context: Context): Location? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { provider ->
            try { manager.getLastKnownLocation(provider) }
            catch (_: SecurityException) { null }
            catch (_: IllegalArgumentException) { null }
        }
        .maxByOrNull { it.time }
        ?.takeIf { it.time > 0 && System.currentTimeMillis() - it.time in 0..120_000L && it.hasAccuracy() && it.accuracy <= 1000f }
}

private suspend fun freshLocation(context: Context): Location? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return recentLocation(context)
    val fresh = withTimeoutOrNull(15_000L) {
        suspendCancellableCoroutine<Location?> { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(manager, provider, cancellation, ContextCompat.getMainExecutor(context)) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (_: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
    return fresh?.takeIf { it.hasAccuracy() && it.accuracy <= 1000f } ?: recentLocation(context)
}
