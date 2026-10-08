package com.focuslock.app.updates

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focuslock.app.BuildConfig

@Composable
internal fun PrivateBuildUpdateCard() {
    if (!BuildConfig.PRIVATE_BUILD_UPDATES) return

    val context = LocalContext.current
    val activity = context.findActivity()
    val status by PrivateBuildUpdater.status.collectAsStateWithLifecycle()

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Private build updates", style = MaterialTheme.typography.titleMedium)
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${status.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Enable update alerts with the Google account invited to test FocusLock. Android asks you to confirm installation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { activity?.let { PrivateBuildUpdater.checkNow(it) } },
                    enabled = activity != null && !status.checking,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (status.checking) "Checking…" else "Check for updates") }
                OutlinedButton(
                    onClick = { openAppTester(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open App Tester") }
            }
        }
    }
}

private fun openAppTester(context: Context) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage("dev.firebase.appdistribution")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://appdistribution.firebase.google.com/"))
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Could not open Firebase App Tester", Toast.LENGTH_SHORT).show()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
