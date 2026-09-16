package com.focuslock.app.ui.dashboard

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clerk.api.Clerk
import com.focuslock.app.BuildConfig
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.data.repository.CreditBankRepository
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.service.DailyUsageSummary
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.service.TickTickAuthConfig
import com.focuslock.app.service.UsageStatsRepository
import com.focuslock.app.sync.ConvexSyncClient
import com.focuslock.app.ui.dashboard.home.FocusHome
import com.focuslock.app.ui.dashboard.home.FocusHomeCallbacks
import com.focuslock.app.ui.dashboard.home.FocusHomeState
import com.focuslock.app.ui.dashboard.home.FocusHomeStyle
import com.focuslock.app.ui.dashboard.home.FocusHomeTasksState
import com.focuslock.app.ui.nuke.NukeActivity
import com.focuslock.app.ui.permissions.PermissionHelper
import com.focuslock.app.ui.permissions.PermissionKind
import com.focuslock.app.ui.permissions.PermissionOnboardingDialog
import com.focuslock.app.ui.permissions.PermissionReturnWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Load state for the dashboard TickTick tasks ring. */
private enum class TickTickTasksState { Loading, NoAccount, Loaded, Error }

/** Resume-driven TickTick refetch throttle; matches the client's display-only TTL window. */
private const val TICKTICK_SOFT_REFETCH_MS = 3L * 60L * 1000L

/** Pull-to-refresh spinner stays up at least this long for UX (real work may take longer). */
private const val MIN_REFRESH_SPINNER_MS = 1_500L

/**
 * Process-lifetime latch for the permission onboarding dialog. DashboardScreen leaves
 * composition on every tab switch, so a rememberSaveable "shown" flag alone re-arms the
 * dialog on each Focus return. Dismissal paths write through to this flag so Skip all /
 * Skip step / completing the flow suppresses re-showing until process restart.
 */
private var permissionOnboardingDismissedForProcess = false

/**
 * One off-main binder pass over every permission kind (each probed exactly once per
 * resume tick). `missing` is derived from these booleans in PermissionKind.entries
 * order — identical content to PermissionHelper.getMissingPermissions without a
 * second redundant 7-check sweep.
 */
private data class PermissionSnapshot(
    val accessibilityOn: Boolean,
    val usageOn: Boolean,
    val notificationOn: Boolean,
    val missing: List<PermissionKind>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenTickTick: () -> Unit,
    onNavigatePermissions: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onOpenAccount: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val app = FocusLockApplication.instance
    val bank = app.creditBankRepository
    val settings = app.settingsRepository
    // Activity-scoped ViewModel: reused by the activity's own `by viewModels()` instance.
    val authViewModel: AuthViewModel = viewModel()

    // Flow snapshots are held as State (not read) at the root so a DataStore write only
    // invalidates the subtree that consumes it. Focus minutes derive from this snapshot.
    val liveBalanceState = bank.liveBalanceSeconds.collectAsStateWithLifecycle()
    val historyState = bank.workHistoryFlow.collectAsStateWithLifecycle(initialValue = null)

    // Goals come from Settings → Daily Goals (focus minutes / TickTick tasks goal).
    val focusGoalMinutes by settings.focusGoalMinutesFlow
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_FOCUS_GOAL_MINUTES)
    val dailyTasksGoalSetting by settings.dailyTasksGoalFlow
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_DAILY_TASKS_GOAL)

    // Selected Focus-tab front page (see FocusHomeStyle; unknown keys fall back to rings).
    val homeStyleKey by settings.focusHomeStyleFlow
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_FOCUS_HOME_STYLE)
    val nukeActive by settings.nukeActiveFlow.collectAsStateWithLifecycle(initialValue = false)
    val clerkUser by Clerk.userFlow.collectAsState(initial = null)

    // Refresh permission + usage state on every resume (fixes stale "Setup needed" pill)
    var permissionTick by remember { mutableIntStateOf(0) }

    // Tasks ring counts ONLY TickTick tasks actually completed today (never overdue, never focus records).
    var tickTickTasksDone by remember { mutableIntStateOf(0) }
    var tickTickTasksState by remember { mutableStateOf(TickTickTasksState.Loading) }
    var tickTickFetchJob by remember { mutableStateOf<Job?>(null) }
    var tickTickLastFetchMs by remember { mutableLongStateOf(0L) }

    /**
     * Fetches the completed-today TickTick count. [bypassCache] = true (pull-to-refresh,
     * Retry) skips the client's 3-minute display TTL; resume refetches are throttled to
     * one per TTL window, so a quick app switch costs zero network calls.
     */
    fun startTickTickFetch(bypassCache: Boolean): Job {
        tickTickFetchJob?.cancel()
        return scope.launch {
            tickTickLastFetchMs = System.currentTimeMillis()
            tickTickTasksState = TickTickTasksState.Loading
            tickTickTasksState = try {
                val token = TickTickAuthConfig.getValidAccessToken(settings)
                if (token.isNullOrBlank()) {
                    tickTickTasksDone = 0
                    TickTickTasksState.NoAccount
                } else {
                    tickTickTasksDone = TickTickApiClient()
                        .fetchCompletedTaskTitlesToday(token, bypassCache = bypassCache).size
                    TickTickTasksState.Loaded
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                TickTickTasksState.Error
            }
        }.also { tickTickFetchJob = it }
    }

    LaunchedEffect(Unit) { startTickTickFetch(bypassCache = false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionTick++
                // TickTick ring: refetch on resume at most once per TTL window.
                if (System.currentTimeMillis() - tickTickLastFetchMs > TICKTICK_SOFT_REFETCH_MS) {
                    startTickTickFetch(bypassCache = false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Binder-backed permission checks are computed off the main thread, only when the resume
    // tick changes, so recomposition never blocks on PackageManager IPC. Null = not checked
    // yet, so the setup card waits for a real result instead of flashing on the first frame.
    // One single pass probes each kind exactly once; the onboarding `missing` list below is
    // derived from these booleans (no redundant getMissingPermissions re-sweep per resume).
    val permSnapshot by produceState<PermissionSnapshot?>(initialValue = null, key1 = permissionTick) {
        value = withContext(Dispatchers.IO) {
            val accessibility = PermissionHelper.isAccessibilityServiceEnabled(context)
            val usage = PermissionHelper.isUsageAccessGranted(context)
            val overlay = PermissionHelper.isOverlayGranted(context)
            val notification = PermissionHelper.isNotificationListenerGranted(context)
            val battery = PermissionHelper.isBatteryOptimizationIgnored(context)
            val deviceAdmin = PermissionHelper.isDeviceAdminActive(context)
            val postNotifications = PermissionHelper.isPostNotificationsGranted(context)
            PermissionSnapshot(
                accessibilityOn = accessibility,
                usageOn = usage,
                notificationOn = notification,
                missing = buildList {
                    if (!accessibility) add(PermissionKind.ACCESSIBILITY)
                    if (!usage) add(PermissionKind.USAGE)
                    if (!overlay) add(PermissionKind.OVERLAY)
                    if (!notification) add(PermissionKind.NOTIFICATION_LISTENER)
                    if (!battery) add(PermissionKind.BATTERY)
                    if (!deviceAdmin) add(PermissionKind.DEVICE_ADMIN)
                    if (!postNotifications) add(PermissionKind.POST_NOTIFICATIONS)
                }
            )
        }
    }
    val isAccessibilityOn: Boolean? = permSnapshot?.accessibilityOn
    val isUsageAccessOn: Boolean? = permSnapshot?.usageOn
    val isNotificationOn: Boolean? = permSnapshot?.notificationOn
    val permissionsChecked = permSnapshot != null
    val hasAllPermissions = permissionsChecked &&
        isAccessibilityOn == true && isUsageAccessOn == true && isNotificationOn == true

    // Step-through onboarding: auto-show once per session on foreground while anything is missing.
    // Derived from the same single off-main permission pass above — zero extra binder sweeps.
    val missing: List<PermissionKind> = permSnapshot?.missing ?: emptyList()
    // Seeded from the process latch so a dismissal survives tab switches (which tear
    // down this composition); every set-to-true below writes the latch back through.
    var shownThisSession by rememberSaveable { mutableStateOf(permissionOnboardingDismissedForProcess) }
    var dialogIndex by rememberSaveable { mutableIntStateOf(0) }
    var showOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(missing) {
        if (missing.isEmpty()) {
            showOnboarding = false
        } else {
            // Re-derive the step from live grant state on every resume so the dialog
            // never points at a stale/out-of-range index after returning from Settings.
            dialogIndex = dialogIndex.coerceIn(0, missing.size - 1)
            if (!shownThisSession && !permissionOnboardingDismissedForProcess) showOnboarding = true
        }
    }

    // StayFree-style screen-time summary. Null until the first query resolves so the UI can
    // distinguish "loading" from a real empty result (no zero-state flash).
    var usageSummary by remember { mutableStateOf<DailyUsageSummary?>(null) }
    LaunchedEffect(permissionTick) {
        usageSummary = UsageStatsRepository.getTodaySummary(context, maxApps = 8)
    }

    // In-app fallback auto-return: polls the permission the user just opened Settings for
    // and brings the app forward once granted, even before the accessibility service is
    // connected. Intentionally an infinite cancellable loop — dies with this composable.
    LaunchedEffect(Unit) {
        PermissionReturnWatcher.fallbackWatch(context)
    }

    var showManualLogDialog by remember { mutableStateOf(false) }
    var showFocusTimerDialog by rememberSaveable { mutableStateOf(false) }
    var showAllHistory by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Playful Nuke launcher states (header button); nukeActive is collected above.
    var showNukeConfirm by remember { mutableStateOf(false) }
    var showNukeInfo by remember { mutableStateOf(false) }
    var nuking by remember { mutableStateOf(false) }
    fun launchNukeActivity() {
        try {
            context.startActivity(Intent(context, NukeActivity::class.java))
        } catch (_: Exception) { }
    }

    val todayFormatted = remember {
        val sdf = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        sdf.format(Date())
    }

    // ---- Shared home state: built once from the collected values above, then dispatched
    // to the selected variation. Variations render only; all behavior stays hoisted here.
    val historySnapshot = historyState.value
    val focusMinutes = remember(historySnapshot) {
        historySnapshot
            ?.filter { CreditBankRepository.isFocusRecord(it.source, it.durationMinutes) }
            ?.sumOf { it.durationMinutes } ?: 0
    }
    // Top used app today (exclude launcher noise); null while loading / off / empty.
    val topApp = usageSummary?.topApps?.firstOrNull {
        !it.packageName.contains("launcher", ignoreCase = true) &&
            !it.appName.contains("launcher", ignoreCase = true)
    }
    val accountName = listOfNotNull(clerkUser?.firstName, clerkUser?.lastName)
        .joinToString(" ").trim().ifBlank { null }
        ?: clerkUser?.primaryEmailAddress?.emailAddress
    val homeState = FocusHomeState(
        todayFormatted = todayFormatted,
        permissionsChecked = permissionsChecked,
        hasAllPermissions = hasAllPermissions,
        missingLabels = buildList {
            if (isAccessibilityOn != true) add("Accessibility")
            if (isUsageAccessOn != true) add("Usage Access")
            if (isNotificationOn != true) add("Notifications")
        },
        isUsageAccessGranted = isUsageAccessOn,
        focusMinutes = focusMinutes,
        focusMinutesLoaded = historySnapshot != null,
        focusGoalMinutes = focusGoalMinutes,
        tasksDone = tickTickTasksDone,
        tasksLoaded = tickTickTasksState == TickTickTasksState.Loaded,
        tasksGoal = dailyTasksGoalSetting.coerceAtLeast(1),
        tasksState = when (tickTickTasksState) {
            TickTickTasksState.Loading -> FocusHomeTasksState.Loading
            TickTickTasksState.NoAccount -> FocusHomeTasksState.NoAccount
            TickTickTasksState.Loaded -> FocusHomeTasksState.Loaded
            TickTickTasksState.Error -> FocusHomeTasksState.Error
        },
        usageSummary = usageSummary,
        topApp = topApp,
        history = historySnapshot,
        showAllHistory = showAllHistory,
        liveBalanceSeconds = liveBalanceState.value,
        nukeActive = nukeActive,
        accountInitial = accountName?.firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString() ?: "",
    )
    val openSettings: () -> Unit = onOpenSettings ?: onNavigatePermissions
    val openAccount: () -> Unit = onOpenAccount ?: openSettings
    val homeCallbacks = FocusHomeCallbacks(
        onOpenTickTick = onOpenTickTick,
        onNavigatePermissions = onNavigatePermissions,
        onOpenSettings = openSettings,
        onOpenAccount = openAccount,
        onOpenLog = { showManualLogDialog = true },
        onOpenTimer = { showFocusTimerDialog = true },
        onToggleHistory = { showAllHistory = !showAllHistory },
        onRetryTasks = { startTickTickFetch(bypassCache = true) },
        onShowNukeConfirm = { showNukeConfirm = true },
        onShowNukeInfo = { showNukeInfo = true },
        onLaunchNuke = { launchNukeActivity() },
    )

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            if (!isRefreshing) {
                isRefreshing = true
                val startedAtMs = System.currentTimeMillis()
                permissionTick++ // refresh permission checks + usage summary
                // Force refetch bypasses the TickTick TTL cache; the spinner waits for the
                // real fetch to settle (min 1.5s for UX) instead of a fixed 700ms timer.
                val fetchJob = startTickTickFetch(bypassCache = true)
                scope.launch {
                    fetchJob.join()
                    val elapsedMs = System.currentTimeMillis() - startedAtMs
                    if (elapsedMs < MIN_REFRESH_SPINNER_MS) delay(MIN_REFRESH_SPINNER_MS - elapsedMs)
                    isRefreshing = false
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FocusHome(
                style = FocusHomeStyle.fromKey(homeStyleKey),
                state = homeState,
                callbacks = homeCallbacks,
            )
        }
    }

    // Nuke confirm dialog: arm locally + sync to Convex, then launch NukeActivity.
    if (showNukeConfirm) {
        AlertDialog(
            onDismissRequest = { if (!nuking) showNukeConfirm = false },
            title = { Text("Detonate the Nuke?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "Full phone lockdown until you finish a 10-minute meditation + check-in. " +
                        "No escape hatch — long-press the Nuke button anytime to learn more.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    enabled = !nuking,
                    onClick = {
                        scope.launch {
                            nuking = true
                            try {
                                settings.setNukeActive(true)
                                try {
                                    val url = try { BuildConfig.CONVEX_URL.trim() } catch (_: Exception) { "" }
                                    if (url.startsWith("http") && authViewModel.isConfigured()) {
                                        ConvexSyncClient(url, authViewModel::getConvexToken).activateNuke()
                                    }
                                } catch (_: Exception) { }
                                launchNukeActivity()
                            } finally {
                                nuking = false
                                showNukeConfirm = false
                            }
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) { Text(if (nuking) "Detonating…" else "Detonate") }
            },
            dismissButton = {
                TextButton(onClick = { showNukeConfirm = false }) { Text("Cancel") }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    // Nuke explainer dialog (long-press).
    if (showNukeInfo) {
        AlertDialog(
            onDismissRequest = { showNukeInfo = false },
            title = { Text("What is the Nuke?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "The Nuke locks your phone to one screen: 10 minutes of guided breathing, " +
                        "then an AI check-in that only lifts when you commit to a real plan. " +
                        "Tap the Nuke button to arm it; if a Nuke is already active, tapping jumps straight back in.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showNukeInfo = false }) { Text("Got it") }
            },
            shape = MaterialTheme.shapes.large
        )
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

    // Built-in focus timer dialog (single session — works without TickTick)
    if (showFocusTimerDialog) {
        FocusTimerDialog(
            onDismiss = { showFocusTimerDialog = false },
            // Same record path as before: ONE work record per session, then the
            // dialog closes.
            onRecordWork = { focusedMinutes ->
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
                // Do not advance just for opening Settings: PermissionReturnWatcher pulls the
                // app back automatically once the grant lands, and the resume re-check then
                // re-derives `missing` and flips the dialog action to Next/Done.
                // Only skip ahead if it was already granted when tapped.
                if (PermissionHelper.isGranted(context, kind) && dialogIndex < missing.size - 1) {
                    dialogIndex++
                }
            },
            onDismiss = {
                if (dialogIndex < missing.size - 1) dialogIndex++ else {
                    showOnboarding = false
                    shownThisSession = true
                    permissionOnboardingDismissedForProcess = true
                }
            },
            onSkipAll = {
                showOnboarding = false
                shownThisSession = true
                permissionOnboardingDismissedForProcess = true
            }
        )
    }
}

@Composable
private fun FocusTimerDialog(
    onDismiss: () -> Unit,
    onRecordWork: (Int) -> Unit
) {
    var selectedMinutes by rememberSaveable { mutableIntStateOf(25) }
    var isRunning by rememberSaveable { mutableStateOf(false) }
    var remainingSeconds by rememberSaveable { mutableIntStateOf(25 * 60) }
    // Wall-clock anchor of the running session (0 = none). Each tick recomputes the
    // remaining time from this anchor instead of accumulating delay() jitter, so the
    // countdown never drifts long. Reset when a run stops or completes.
    var endAtMs by remember { mutableLongStateOf(0L) }

    // Picking a different preset while idle resets the countdown.
    LaunchedEffect(selectedMinutes) {
        if (!isRunning) remainingSeconds = selectedMinutes * 60
    }
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        if (endAtMs <= 0L) endAtMs = System.currentTimeMillis() + remainingSeconds * 1000L
        var finishedNaturally = false
        while (true) {
            val remainingMs = endAtMs - System.currentTimeMillis()
            if (remainingMs <= 0L) {
                finishedNaturally = true
                break
            }
            // Display ceil(remaining) — recomputed from the anchor every tick (no
            // += accumulation), so the timer honors the real wall-clock end time.
            val nextSeconds = ((remainingMs + 999L) / 1000L).toInt()
            if (nextSeconds != remainingSeconds) remainingSeconds = nextSeconds
            // Sleep exactly until the next displayed second flips (never a busy loop).
            val tickDelay = remainingMs % 1000L
            delay(if (tickDelay == 0L) 1000L else tickDelay)
        }
        if (finishedNaturally) {
            endAtMs = 0L
            remainingSeconds = 0
            isRunning = false
            onRecordWork(selectedMinutes)
            onDismiss()
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
                    Text(
                        "Pick a focus block. Finishing earns leisure time — no TickTick needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
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
                    Text(
                        "Stay focused — leaving keeps the timer running here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (!isRunning) {
                Button(onClick = { isRunning = true }, shape = RoundedCornerShape(20.dp)) { Text("Start") }
            } else {
                TextButton(onClick = {
                    isRunning = false
                    // Partial credit for >= 5 min of focus, then close. Ceil on the
                    // remaining seconds (= floor of elapsed minutes) so the credited
                    // minutes can never exceed the full-completion path (selectedMinutes).
                    val doneMinutes = selectedMinutes - ((remainingSeconds + 59) / 60)
                    endAtMs = 0L
                    if (doneMinutes >= 5) onRecordWork(doneMinutes)
                    onDismiss()
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
fun PixelWorkRecordItem(record: TickTickWorkRecord) {
    // Format per timestamp instead of allocating a formatter on every row recomposition.
    val formattedTime = remember(record.timestamp) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(record.timestamp))
    }

    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.focuslock.app.ui.components.IconBadge(
                icon = androidx.compose.material.icons.Icons.Rounded.Check,
                size = 44.dp,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${record.durationMinutes} min focus • $formattedTime • ${record.source.name.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "+${record.earnedMinutesCredited}m",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}
