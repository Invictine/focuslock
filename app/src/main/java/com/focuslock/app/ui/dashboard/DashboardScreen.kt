package com.focuslock.app.ui.dashboard

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.UserStats
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.service.DailyUsageSummary
import com.focuslock.app.service.UsageStatsRepository
import com.focuslock.app.ui.permissions.PermissionHelper
import com.focuslock.app.ui.permissions.PermissionKind
import com.focuslock.app.ui.permissions.PermissionOnboardingDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun DashboardScreen(
    onOpenTickTick: () -> Unit,
    onNavigatePermissions: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val bank = FocusLockApplication.instance.creditBankRepository
    val settings = FocusLockApplication.instance.settingsRepository

    val stats by bank.statsFlow.collectAsState(initial = UserStats())
    val liveBalanceSeconds by bank.liveBalanceSeconds.collectAsState()
    val history by bank.workHistoryFlow.collectAsState(initial = emptyList())

    // Refresh permission + usage state on every resume (fixes stale "Setup needed" pill)
    var permissionTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isAccessibilityOn = remember(context, permissionTick) { PermissionHelper.isAccessibilityServiceEnabled(context) }
    val isUsageAccessOn = remember(context, permissionTick) { PermissionHelper.isUsageAccessGranted(context) }
    val isNotificationOn = remember(context, permissionTick) { PermissionHelper.isNotificationListenerGranted(context) }
    val hasAllPermissions = isAccessibilityOn && isUsageAccessOn && isNotificationOn

    // Step-through onboarding: auto-show once per session on foreground while anything is missing.
    val missing = remember(context, permissionTick) { PermissionHelper.getMissingPermissions(context) }
    var shownThisSession by rememberSaveable { mutableStateOf(false) }
    var dialogIndex by rememberSaveable { mutableIntStateOf(0) }
    var showOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(permissionTick) {
        val current = PermissionHelper.getMissingPermissions(context)
        if (current.isNotEmpty() && !shownThisSession) {
            dialogIndex = dialogIndex.coerceIn(0, current.size - 1)
            showOnboarding = true
        } else if (current.isEmpty()) {
            showOnboarding = false
        }
    }

    // StayFree-style screen-time summary
    var usageSummary by remember { mutableStateOf(DailyUsageSummary(0L, emptyList(), 0)) }
    LaunchedEffect(permissionTick) {
        usageSummary = UsageStatsRepository.getTodaySummary(context, maxApps = 8)
    }

    var showManualLogDialog by remember { mutableStateOf(false) }
    var showFocusTimerDialog by remember { mutableStateOf(false) }

    val todayFormatted = remember {
        val sdf = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        sdf.format(Date())
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. At-a-glance header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = todayFormatted,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Text(
                        text = "Make time.",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                Surface(
                    color = if (hasAllPermissions) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.clip(RoundedCornerShape(50))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (hasAllPermissions) Icons.Outlined.Shield else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (hasAllPermissions) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (hasAllPermissions) "Protected" else "Setup needed",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (hasAllPermissions) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // 2. Missing Permissions Warning Card
        if (!hasAllPermissions) {
            item {
                val setupPulse = rememberInfiniteTransition(label = "setup-pulse")
                val setupAlpha by setupPulse.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "setup-pulse-alpha"
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(2.dp, MaterialTheme.colorScheme.error.copy(alpha = setupAlpha)),
                            MaterialTheme.shapes.large
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Finish setting up",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                buildString {
                                    append("Missing: ")
                                    val missing = mutableListOf<String>()
                                    if (!isAccessibilityOn) missing.add("Accessibility")
                                    if (!isUsageAccessOn) missing.add("Usage Access")
                                    if (!isNotificationOn) missing.add("Notifications")
                                    append(missing.joinToString(", "))
                                    append(". Blocking + screen-time stats need these.")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        }
                        Button(
                            onClick = onNavigatePermissions,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Setup", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // Balance is the primary reading; actions follow in order of emphasis.
        item {
            val minutes = liveBalanceSeconds / 60
            val seconds = liveBalanceSeconds % 60
            val available = liveBalanceSeconds > 0
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Time to unwind", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${minutes}m ${seconds.toString().padStart(2, '0')}s",
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontWeight = FontWeight.Medium, letterSpacing = (-2).sp,
                            fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (available) Icons.Outlined.CheckCircle else Icons.Outlined.Shield,
                            contentDescription = null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text(if (available) "Ready when you are" else "Earn a little breathing room",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        if (available) "Your selected apps unlock while you have time available."
                        else "A little focused work now makes room for a break later.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onOpenTickTick,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = MaterialTheme.shapes.large) {
                        Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open TickTick")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { showFocusTimerDialog = true }) { Text("Focus timer") }
                        TextButton(onClick = { showManualLogDialog = true }) { Text("Log work manually") }
                    }
                }
            }
        }

        // 3b. NUKE — total lock on phone+PC until 10-min reset + coach check-in.
        item {
            val nukeActive by settings.nukeActiveFlow.collectAsState(initial = false)
            var nuking by remember { mutableStateOf(false) }
            var showNukeConfirm by remember { mutableStateOf(false) }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (nukeActive) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (nukeActive) "☢ NUKE ACTIVE — phone + PC locked"
                        else "☢ Nuke it",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (nukeActive) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (nukeActive) "Finish the 10-minute reset + coach check-in to lift it on both devices."
                        else "Completely blocks phone + PC until you finish a 10-minute breathing reset and talk through your plan with the coach.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (nukeActive) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            if (nukeActive) {
                                context.startActivity(android.content.Intent(context, com.focuslock.app.ui.nuke.NukeActivity::class.java).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } else showNukeConfirm = true
                        },
                        enabled = !nuking,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(if (nuking) "Arming…" else if (nukeActive) "Return to reset" else "NUKE everything")
                    }
                }
            }
            if (showNukeConfirm) {
                AlertDialog(
                    onDismissRequest = { showNukeConfirm = false },
                    title = { Text("Nuke phone + PC?") },
                    text = { Text("This locks EVERYTHING on both devices. The only way out is the 10-minute breathing reset + an honest check-in with the coach about what you will do next. No bypass.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showNukeConfirm = false
                            nuking = true
                            scope.launch {
                                try {
                                    settings.setNukeActive(true)
                                    // Push to Convex so PC locks too (~30s sync + instant on open).
                                    try {
                                        val authVm = com.focuslock.app.auth.AuthViewModel()
                                        val url = try { com.focuslock.app.BuildConfig.CONVEX_URL.trim() } catch (_: Exception) { "" }
                                        if (url.startsWith("http")) {
                                            val client = com.focuslock.app.sync.ConvexSyncClient(url, authVm::getConvexToken)
                                            client.activateNuke()
                                        }
                                    } catch (_: Exception) { }
                                    context.startActivity(android.content.Intent(context, com.focuslock.app.ui.nuke.NukeActivity::class.java).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                } finally { nuking = false }
                            }
                        }) { Text("NUKE it", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { showNukeConfirm = false }) { Text("Cancel") } }
                )
            }
        }

        // 4. Digital Wellbeing / StayFree: Screen Time Today
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Screen time",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (isUsageAccessOn) "${usageSummary.appCount} apps used"
                                else "Grant Usage Access to see stats",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            UsageStatsRepository.formatDuration(usageSummary.totalScreenMinutes),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (!isUsageAccessOn) {
                        Button(
                            onClick = onNavigatePermissions,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Grant Usage Access") }
                    } else if (usageSummary.topApps.isEmpty()) {
                        Text(
                            "No screen-time data yet. This updates when you return to FocusLock.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val maxMins = (usageSummary.topApps.maxOfOrNull { it.foregroundMinutes } ?: 1L).coerceAtLeast(1L)
                        usageSummary.topApps.take(5).forEach { entry ->
                            ScreenTimeBar(
                                appName = entry.appName,
                                minutes = entry.foregroundMinutes,
                                fraction = entry.foregroundMinutes / maxMins.toFloat()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }
        }

        item {
            Text("Today, so far", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            SummaryRow("Focused work", "${stats.totalWorkMinutesToday} min", Icons.Outlined.Timer)
            SummaryRow("Tasks completed", "${stats.tasksCompletedToday}", Icons.Outlined.CheckCircle)
            SummaryRow("Leisure used", "${stats.totalDoomscrollMinutesToday} min", Icons.Outlined.PhoneAndroid)
        }

        // 6. Work history
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent work",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                if (history.isNotEmpty()) {
                    Text(
                        text = "${history.size} total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "No work logged yet today",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Use the Focus Timer, Log Work, or complete tasks in TickTick to earn screen time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        } else {
            items(history, key = { it.id }) { record ->
                PixelWorkRecordItem(record = record)
            }
        }
    }

    // Manual work log dialog (fallback when TickTick API fails — core USP reliability fix)
    if (showManualLogDialog) {
        var title by remember { mutableStateOf("") }
        var minutesText by remember { mutableStateOf("25") }
        AlertDialog(
            onDismissRequest = { showManualLogDialog = false },
            title = { Text("Log Productive Work", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "What did you work on? This earns leisure time at your work-to-scroll ratio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Task (e.g. Studied Kotlin)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Minutes focused") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mins = minutesText.toIntOrNull()?.coerceIn(1, 480) ?: 25
                        scope.launch {
                            val ratio = settings.workRatioFlow.first()
                            val bonus = settings.taskBonusFlow.first()
                            val record = TickTickWorkRecord(
                                id = "manual_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                                title = title.ifBlank { "Focused Work" },
                                durationMinutes = mins,
                                source = WorkRecordSource.MANUAL_ENTRY,
                                projectName = "Manual"
                            )
                            // Titled entries count as a completed task (earn bonus);
                            // untitled time-only entries earn time-based credit only.
                            val effectiveBonus = if (title.isNotBlank()) bonus else 0
                            val earned = bank.recordWorkCredit(record, ratio, effectiveBonus)
                            showManualLogDialog = false
                            permissionTick++
                            Toast.makeText(context, "Logged $mins min → +$earned min leisure", Toast.LENGTH_LONG).show()
                        }
                    },
                    shape = RoundedCornerShape(20.dp)
                ) { Text("Earn Time") }
            },
            dismissButton = {
                TextButton(onClick = { showManualLogDialog = false }) { Text("Cancel") }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    // Built-in focus timer dialog (Pomodoro fallback — works without TickTick)
    if (showFocusTimerDialog) {
        FocusTimerDialog(
            onDismiss = { showFocusTimerDialog = false },
            onComplete = { focusedMinutes ->
                scope.launch {
                    val ratio = settings.workRatioFlow.first()
                    val record = TickTickWorkRecord(
                        id = "timer_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                        title = "Focus Timer Session",
                        durationMinutes = focusedMinutes,
                        source = WorkRecordSource.MANUAL_ENTRY,
                        projectName = "Focus Timer"
                    )
                    val earned = bank.recordWorkCredit(record, ratio, 0)
                    showFocusTimerDialog = false
                    permissionTick++
                    Toast.makeText(context, "Focus done! +$earned min leisure earned", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // Step-through permission onboarding dialogs (one per missing permission)
    if (showOnboarding && missing.isNotEmpty()) {
        PermissionOnboardingDialog(
            missing = missing,
            currentIndex = dialogIndex.coerceIn(0, missing.size - 1),
            onGrant = { kind ->
                PermissionHelper.openPermissionWithHighlight(context, kind)
                if (dialogIndex < missing.size - 1) dialogIndex++ else {
                    showOnboarding = false
                    shownThisSession = true
                }
            },
            onDismiss = {
                if (dialogIndex < missing.size - 1) dialogIndex++ else {
                    showOnboarding = false
                    shownThisSession = true
                }
            },
            onSkipAll = {
                showOnboarding = false
                shownThisSession = true
            }
        )
    }
}

@Composable
private fun ScreenTimeBar(appName: String, minutes: Long, fraction: Float) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                appName,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            Text(
                UsageStatsRepository.formatDuration(minutes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0.02f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

@Composable
private fun FocusTimerDialog(
    onDismiss: () -> Unit,
    onComplete: (Int) -> Unit
) {
    var selectedMinutes by remember { mutableIntStateOf(25) }
    var isRunning by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableIntStateOf(25 * 60) }

    LaunchedEffect(selectedMinutes) {
        if (!isRunning) remainingSeconds = selectedMinutes * 60
    }
    LaunchedEffect(isRunning) {
        while (isRunning && remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        if (isRunning && remainingSeconds <= 0) {
            isRunning = false
            onComplete(selectedMinutes)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = { Text("Focus Timer", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!isRunning) {
                    Text("Pick a focus block. Finishing earns leisure time — no TickTick needed.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 25, 50).forEach { mins ->
                            FilterChip(
                                selected = selectedMinutes == mins,
                                onClick = { selectedMinutes = mins },
                                label = { Text("${mins}m") }
                            )
                        }
                    }
                } else {
                    val mm = remainingSeconds / 60
                    val ss = remainingSeconds % 60
                    Text(
                        "%02d:%02d".format(mm, ss),
                        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    LinearProgressIndicator(
                        progress = { 1f - remainingSeconds / (selectedMinutes * 60f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Stay focused — leaving keeps the timer running here.")
                }
            }
        },
        confirmButton = {
            if (!isRunning) {
                Button(onClick = { isRunning = true }, shape = RoundedCornerShape(20.dp)) { Text("Start") }
            } else {
                TextButton(onClick = {
                    isRunning = false
                    // Partial credit for >= 5 min of focus
                    val doneMinutes = selectedMinutes - (remainingSeconds / 60)
                    if (doneMinutes >= 5) onComplete(doneMinutes) else onDismiss()
                }) { Text("Finish Early") }
            }
        },
        dismissButton = {
            if (!isRunning) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        shape = MaterialTheme.shapes.large
    )
}

@Composable
fun PixelStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(containerColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
fun PixelWorkRecordItem(record: TickTickWorkRecord) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val formattedTime = timeFormat.format(Date(record.timestamp))

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = "${record.durationMinutes} min focus • $formattedTime • ${record.source.name.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = "+${record.earnedMinutesCredited}m",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, icon: ImageVector) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
