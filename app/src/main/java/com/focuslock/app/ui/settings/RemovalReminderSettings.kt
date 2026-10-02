package com.focuslock.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focuslock.app.reminder.RemovalReminderStore
import com.focuslock.app.reminder.RemovalReminderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun RemovalReminderSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { RemovalReminderStore(context) }
    var enabled by remember { mutableStateOf(store.enabled) }
    var mediaName by remember { mutableStateOf(store.mediaName()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun acceptMedia(uri: Uri?) {
        if (uri == null) return
        busy = true
        error = null
        scope.launch {
            try {
                store.importMedia(uri)
                mediaName = store.mediaName()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "Could not import this media file"
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> acceptMedia(uri) }
    val recorder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) acceptMedia(uri) else error = "The recorder did not return a media file. Choose a file from storage instead."
        }
    }
    val preview = {
        try {
            context.startActivity(RemovalReminderActivity.intent(context, "preview"))
        } catch (_: ActivityNotFoundException) {
            error = "The reminder preview is unavailable right now."
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Removal reminder",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Show a personal reminder when you disable device admin or try to uninstall FocusLock. Allow display over other apps for the admin popup and accessibility access for uninstall detection. Some system screens may show a notification instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show reminder", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (mediaName == null) "Confirmation only until you add a clip" else "Stored privately on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { value -> enabled = value; store.enabled = value },
                    enabled = !busy,
                    modifier = Modifier.size(48.dp)
                        .semantics { contentDescription = "Play removal reminder" }
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Reminder media", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (mediaName != null) {
                Text(
                    text = mediaName!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text("No clip selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = !busy) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Choose media")
                }
                TextButton(
                    onClick = {
                        launchRecorder(context, MediaStore.ACTION_VIDEO_CAPTURE,
                            picker = { picker.launch(arrayOf("audio/*", "video/*")) },
                            launch = { intent -> recorder.launch(intent) })
                    },
                    enabled = !busy
                ) {
                    Text("Record video")
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = {
                        launchRecorder(context, MediaStore.Audio.Media.RECORD_SOUND_ACTION,
                            picker = { picker.launch(arrayOf("audio/*", "video/*")) },
                            launch = { intent -> recorder.launch(intent) })
                    },
                    enabled = !busy
                ) { Text("Record audio") }
                TextButton(onClick = preview, enabled = !busy) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Preview")
                }
                if (mediaName != null) {
                    TextButton(
                        onClick = {
                            store.clearMedia()
                            mediaName = null
                            error = null
                        },
                        enabled = !busy
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove reminder media", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Importing and checking media…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun launchRecorder(context: Context, action: String, picker: () -> Unit, launch: (Intent) -> Unit) {
    try {
        launch(Intent(action))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No recorder is available. Choose an existing media file instead.", Toast.LENGTH_LONG).show()
        picker()
    }
}
