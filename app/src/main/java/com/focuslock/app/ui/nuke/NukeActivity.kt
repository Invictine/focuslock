package com.focuslock.app.ui.nuke

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.ui.theme.FocusLockTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val NUKE_MEDITATION_MS = 10 * 60 * 1000L

data class NukeChatMsg(val role: String, val text: String)

class NukeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Pin to screen: user asked for complete phone block until reset is done.
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                startLockTask()
            }
        } catch (_: Exception) { }

        setContent {
            FocusLockTheme {
                NukeLockScreen(
                    onUnlocked = { plan ->
                        lifecycleScope.launch {
                            try { stopLockTask() } catch (_: Exception) { }
                            Toast.makeText(this@NukeActivity, "Nuke lifted. Plan: $plan", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                )
            }
        }
    }

    @Deprecated("Back is disabled during nuke")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        // No escape — finish the reset first.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-pin if system tries to drop us.
        try { startLockTask() } catch (_: Exception) { }
    }
}

@Composable
fun NukeLockScreen(onUnlocked: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = FocusLockApplication.instance.settingsRepository
    val app = FocusLockApplication.instance

    val startedAt by settings.nukeStartedAtFlow.collectAsState(initial = System.currentTimeMillis())
    val meditationDoneAt by settings.nukeMeditationDoneAtFlow.collectAsState(initial = 0L)

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000L); nowMs = System.currentTimeMillis() }
    }

    val effectiveStart = if (startedAt > 0L) startedAt else nowMs
    val elapsed = (nowMs - effectiveStart).coerceAtLeast(0L)
    val remaining = (NUKE_MEDITATION_MS - elapsed).coerceAtLeast(0L)
    val meditationComplete = meditationDoneAt > 0L || remaining <= 0L

    // Mark meditation done once timer hits zero (server also enforces 10 min).
    LaunchedEffect(meditationComplete) {
        if (meditationComplete && meditationDoneAt == 0L) {
            settings.setNukeMeditationDone()
            try {
                // Best-effort: tell backend (needs auth; failure is fine, checkin re-validates).
                val authVm = AuthViewModel()
                val url = try { com.focuslock.app.BuildConfig.CONVEX_URL.trim() } catch (_: Exception) { "" }
                if (url.startsWith("http") && authVm.isConfigured()) {
                    val client = com.focuslock.app.sync.ConvexSyncClient(url, authVm::getConvexToken)
                    client.completeNukeMeditation()
                }
            } catch (_: Exception) { }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Box(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!meditationComplete) {
                MeditationPhase(remainingMs = remaining, elapsedMs = elapsed)
            } else {
                CheckinPhase(
                    onUnlocked = { plan ->
                        scope.launch {
                            settings.clearNuke()
                            // Sync unlock to PC via Convex (best-effort offline-safe).
                            try {
                                val authVm = AuthViewModel()
                                val url = try { com.focuslock.app.BuildConfig.CONVEX_URL.trim() } catch (_: Exception) { "" }
                                if (url.startsWith("http") && authVm.isConfigured()) {
                                    val client = com.focuslock.app.sync.ConvexSyncClient(url, authVm::getConvexToken)
                                    // Unlock already happened server-side via checkin approval.
                                    // Push a fresh sync so PC sees it within ~30s.
                                    app.syncManager.syncNow(authVm)
                                }
                            } catch (_: Exception) { }
                            onUnlocked(plan.take(80))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MeditationPhase(remainingMs: Long, elapsedMs: Long) {
    val mins = remainingMs / 60000
    val secs = (remainingMs % 60000) / 1000
    val progress = (elapsedMs.toFloat() / NUKE_MEDITATION_MS.toFloat()).coerceIn(0f, 1f)

    // 4-7-8 breathing cycle: 19s loop
    var cycleSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000L); cycleSec = (cycleSec + 1) % 19 }
    }
    val phase = when (cycleSec) {
        in 0..3 -> "Breathe in…" to 4
        in 4..10 -> "Hold…" to 7
        else -> "Breathe out…" to 8
    }
    val targetScale = when {
        cycleSec <= 3 -> 1.25f
        cycleSec <= 10 -> 1.25f
        else -> 1.0f
    }
    val scale by animateFloatAsState(targetValue = targetScale, animationSpec = tween(1000), label = "breath")

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(88.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("NUKE ACTIVE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        Text(
            "Phone + PC are locked.\nFinish the 10-minute reset to continue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "%02d:%02d".format(mins, secs),
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.size(140.dp).scale(scale).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.SelfImprovement, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(phase.first, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("4 in · 7 hold · 8 out — eyes soft, shoulders down.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Leaving this screen does not pause the timer.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun CheckinPhase(onUnlocked: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf(NukeChatMsg("coach", "Reset complete. Well done staying with it.\n\nNow tell me — what are you going to do from here onwards? One concrete next action, for how long, and what will you NOT touch?"))) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.length < 3 || sending) return
        sending = true
        input = ""
        messages = messages + NukeChatMsg("user", text)
        scope.launch {
            try {
                val app = FocusLockApplication.instance
                val authVm = AuthViewModel()
                val url = try { com.focuslock.app.BuildConfig.CONVEX_URL.trim() } catch (_: Exception) { "" }
                var approved = false
                var reply = ""
                if (url.startsWith("http") && authVm.isConfigured()) {
                    val client = com.focuslock.app.sync.ConvexSyncClient(url, authVm::getConvexToken)
                    val hist = messages.takeLast(6).map { it.role to it.text }
                    val res = client.checkinNuke(text, hist)
                    if (res != null) { approved = res.first; reply = res.second }
                }
                if (reply.isBlank()) {
                    // Offline fallback mirrors server heuristic — never bypasses the lock.
                    val words = text.split("\\s+".toRegex()).size
                    val hasTime = Regex("(\\d+\\s*(min|minutes|hour|hours)|pomodoro|timebox|until)", RegexOption.IGNORE_CASE).containsMatchIn(text)
                    val hasAction = Regex("\\b(will|going to|start|do|work on|write|code|read|build|study|clean|exercise|call|finish)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
                    if (words >= 12 && hasTime && hasAction) {
                        approved = true
                        reply = "Locked in. One thing, time-boxed, distractions off. (Offline approval — full coach when Vertex is connected.)"
                    } else {
                        reply = "Good start — sharpen it: ONE next action + how many minutes + what you will NOT touch."
                    }
                }
                messages = messages + NukeChatMsg("coach", reply)
                if (approved) {
                    delay(800L)
                    // Server already unlocked via checkin; clear local + exit.
                    app.settingsRepository.clearNuke()
                    onUnlocked(text)
                }
            } catch (e: Exception) {
                messages = messages + NukeChatMsg("coach", "Stay with it — restate your plan: one action, minutes, and what you avoid.")
            } finally {
                sending = false
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Reset complete — check in to unlock", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Text("Talk it through. The coach unlocks both devices only when the plan is concrete.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { m ->
                val isUser = m.role == "user"
                Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(m.text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (sending) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Coach is reading…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("e.g. I will write the report intro for 25 min, phone in drawer…") },
                maxLines = 3
            )
            Button(onClick = { send() }, enabled = !sending && input.trim().length >= 3) {
                Text("Send")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("No back button. No bypass. Say the plan.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
