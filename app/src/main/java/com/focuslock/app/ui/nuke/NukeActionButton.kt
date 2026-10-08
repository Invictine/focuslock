package com.focuslock.app.ui.nuke

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focuslock.app.BuildConfig
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.sync.ConvexSyncClient
import kotlinx.coroutines.launch

/** Nuke entry point for the app bar, with its activation flow kept self-contained. */
@Composable
fun NukeActionButton(
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { FocusLockApplication.instance.settingsRepository }
    val nukeActive by settings.nukeActiveFlow.collectAsStateWithLifecycle(initialValue = false)
    var showConfirmation by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var activating by remember { mutableStateOf(false) }

    fun launchNuke() {
        runCatching { context.startActivity(Intent(context, NukeActivity::class.java)) }
    }

    IconButton(
        onClick = { if (nukeActive) launchNuke() else showConfirmation = true },
        modifier = modifier.semantics { contentDescription = if (nukeActive) "Resume Nuke" else "Nuke" },
    ) {
        Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.Center) {
            Text("☢", fontSize = 20.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.clearAndSetSemantics { })
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!activating) showConfirmation = false },
            title = { Text("Detonate the Nuke?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Full phone lockdown until you finish a 10-minute meditation + check-in.",
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { showInfo = true }, enabled = !activating) { Text("About Nuke") }
                }
            },
            confirmButton = {
                Button(
                    enabled = !activating,
                    onClick = {
                        scope.launch {
                            activating = true
                            try {
                                settings.setNukeActive(true)
                                try {
                                    val url = BuildConfig.CONVEX_URL.trim()
                                    if (url.startsWith("http") && authViewModel.isConfigured()) {
                                        ConvexSyncClient(url, authViewModel::getConvexToken).activateNuke()
                                    }
                                } catch (_: Exception) { }
                                launchNuke()
                            } finally {
                                activating = false
                                showConfirmation = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = MaterialTheme.shapes.large,
                ) { Text(if (activating) "Detonating…" else "Detonate") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }, enabled = !activating) { Text("Cancel") }
            },
            shape = MaterialTheme.shapes.large,
        )
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("What is the Nuke?") },
            text = {
                Text(
                    "The Nuke locks your phone to one screen: 10 minutes of guided breathing, " +
                        "then an AI check-in that only lifts when you commit to a real plan. " +
                        "Once active, use the app bar button to resume it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it") } },
            shape = MaterialTheme.shapes.large,
        )
    }
}
