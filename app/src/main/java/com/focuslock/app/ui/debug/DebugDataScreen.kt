package com.focuslock.app.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.UserStats
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.ui.permissions.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebugDataScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bank = FocusLockApplication.instance.creditBankRepository
    val settings = FocusLockApplication.instance.settingsRepository

    val focusMinutes by bank.focusMinutesTodayFlow.collectAsStateWithLifecycle(initialValue = 0)
    val history by bank.workHistoryFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val stats by bank.statsFlow.collectAsStateWithLifecycle(initialValue = UserStats())
    val liveBalanceSeconds by bank.liveBalanceSeconds.collectAsStateWithLifecycle()
    val workRatio by settings.workRatioFlow.collectAsStateWithLifecycle(initialValue = 4)
    val taskBonus by settings.taskBonusFlow.collectAsStateWithLifecycle(initialValue = 5)
    val strictMode by settings.strictModeFlow.collectAsStateWithLifecycle(initialValue = false)
    val tickTickToken by settings.tickTickTokenFlow.collectAsStateWithLifecycle(initialValue = "")

    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateTimeFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val sorted = remember(history) { history.sortedByDescending { it.timestamp } }
    // Format each record's timestamp once per history change instead of allocating a Date
    // and formatting on every row (re)composition. timeFmt is only touched on the main
    // thread (composition + click handlers), so a single remembered formatter is safe.
    val recordTimeText = remember(sorted) {
        sorted.associate { it.id to timeFmt.format(Date(it.timestamp)) }
    }
    val totalWorkSeconds = remember(sorted) { sorted.sumOf { it.durationMinutes } * 60L }
    val breakdown = remember(sorted) {
        WorkRecordSource.entries.associateWith { src ->
            val recs = sorted.filter { it.source == src }
            recs.size to recs.sumOf { it.durationMinutes }
        }
    }

    val hasToken = tickTickToken.isNotBlank()
    // Notification-listener check is binder work (Settings.Secure read) — resolve off
    // the main thread; null until it lands.
    val notifEnabled by produceState<Boolean?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            PermissionHelper.isNotificationListenerGranted(context)
        }
    }

    var tickTitles by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var refreshingTick by remember { mutableStateOf(false) }
    var lastSyncText by remember { mutableStateOf("loading…") }

    fun refreshTickTitles() {
        if (refreshingTick) return
        refreshingTick = true
        scope.launch {
            try {
                val token = settings.tickTickTokenFlow.first()
                tickTitles = if (token.isBlank()) emptyList()
                else TickTickApiClient().fetchCompletedTaskTitlesToday(token)
            } catch (_: Exception) {
                tickTitles = emptyList()
            } finally {
                refreshingTick = false
            }
        }
    }

    suspend fun loadLastSync(): String {
        return try {
            val ts = bank.getLastSyncTimestamp()
            if (ts > 0) dateTimeFmt.format(Date(ts)) else "n/a (never synced)"
        } catch (_: Exception) {
            "n/a"
        }
    }

    // Convex last sync: FocusSyncManager exposes status only (no lastSync field) -> derive from bank timestamp.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        lastSyncText = loadLastSync()
    }

    fun buildReport(): String {
        val sb = StringBuilder()
        sb.appendLine("FocusLock debug report")
        sb.appendLine("focusMinutes=$focusMinutes totalWorkSeconds=$totalWorkSeconds credit=${liveBalanceSeconds}s tasksToday=${stats.tasksCompletedToday}")
        sb.appendLine("breakdown:")
        breakdown.forEach { (src, pair) -> sb.appendLine("  $src count=${pair.first} min=${pair.second}") }
        sb.appendLine("records(newest first):")
        sorted.forEach {
            sb.appendLine("  ${it.id} | ${it.title} | ${it.durationMinutes}min | ${it.source} | ${timeFmt.format(Date(it.timestamp))} | +${it.earnedMinutesCredited}m | ${it.projectName}")
        }
        sb.appendLine("hasToken=$hasToken notifListener=${notifEnabled ?: false} strict=$strictMode ratio=$workRatio bonus=$taskBonus lastSync=$lastSyncText")
        sb.appendLine("ticktick tasks today (${tickTitles.size}): ${tickTitles.joinToString { it.first }}")
        return sb.toString()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Debug data", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Totals", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text("focusMinutes: $focusMinutes min")
                    Text("totalWorkSeconds: $totalWorkSeconds s")
                    Text("credit balance: ${liveBalanceSeconds}s (${liveBalanceSeconds / 60}m)")
                    Text("TASKS_COMPLETED_TODAY: ${stats.tasksCompletedToday}")
                    if (liveBalanceSeconds > 0 && focusMinutes == 0) {
                        Text(
                            "⚠ inflated legacy balance (tap Reset today)",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    breakdown.forEach { (src, pair) ->
                        Text("${src.name}: count=${pair.first} min=${pair.second}")
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Raw sources", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text("has TickTick token: $hasToken")
                    Text("notification listener enabled: ${notifEnabled ?: "checking…"}")
                    Text("DataStore strict=$strictMode ratio=$workRatio bonus=$taskBonus")
                    Text("Convex last sync: $lastSyncText")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TickTick tasks today (${tickTitles.size}, 0-focus):")
                        TextButton(onClick = { refreshTickTitles() }, enabled = !refreshingTick) {
                            Text(if (refreshingTick) "Refreshing…" else "Refresh")
                        }
                    }
                    if (tickTitles.isEmpty()) Text("none", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else tickTitles.take(30).forEach { (title, project) -> Text("• $title ($project)") }
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        bank.resetTodayCounters()
                        lastSyncText = loadLastSync()
                        Toast.makeText(context, "Today counters reset", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Reset today") }
                Button(onClick = { refreshTickTitles() }, enabled = !refreshingTick) {
                    Text("Refresh TickTick")
                }
                Button(onClick = {
                    val report = buildReport()
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("focuslock-debug", report))
                        Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        Toast.makeText(context, "Copy failed", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Copy report") }
            }
        }
        item {
            Text(
                "Records today (${sorted.size}, newest first)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
        if (sorted.isEmpty()) {
            item { Text("No records today.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(sorted, key = { it.id }) { r ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text("id=${r.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("[${r.source.name}] ${r.durationMinutes}min @ ${recordTimeText[r.id] ?: ""} +${r.earnedMinutesCredited}m project=${r.projectName ?: "-"}")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)); HorizontalDivider() }
    }
}
