package com.focuslock.app.ui.dashboard

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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clerk.api.Clerk
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.ui.components.UiTokens
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.model.BlockedWebsite
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.data.repository.CreditBankRepository
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.service.DailyUsageSummary
import com.focuslock.app.service.UsageStatsRepository
import com.focuslock.app.sync.AccountPermanentTargetsSnapshot
import com.focuslock.app.sync.SyncStatus
import com.focuslock.app.sync.mergeTodayBoundaryUsageSeconds
import com.focuslock.app.sync.summaryForAccountDate
import com.focuslock.app.ui.dashboard.home.FocusHomeCallbacks
import com.focuslock.app.ui.dashboard.home.FocusHomeState
import com.focuslock.app.ui.dashboard.home.FocusHomeTasksState
import com.focuslock.app.ui.dashboard.home.TodayDestination
import com.focuslock.app.ui.dashboard.home.ActivityDestination
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

/**
 * Process-lifetime latch for the permission onboarding dialog. DashboardScreen leaves
 * composition on every tab switch, so a rememberSaveable "shown" flag alone re-arms the
 * dialog on each Focus return. Every user advance (skip step, skip all, completing the
 * flow — and advancing past a step via Grant) writes through to this flag, so mid-flow
 * tab switches never resurrect the dialog until process restart.
 */
private var permissionOnboardingDismissedForProcess = false

/**
 * Process-lifetime resume index for the onboarding flow: survives tab switches (which
 * tear down composition and may not restore saveable state), so if the dialog is ever
 * re-shown it resumes at the step the user actually reached.
 */
private var permissionOnboardingLastIndex = 0

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
    onOpenAccount: (() -> Unit)? = null,
    showActivity: Boolean = false,
    onOpenConnections: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val app = FocusLockApplication.instance
    val bank = app.creditBankRepository
    val settings = app.settingsRepository
    val nukeActive by settings.nukeActiveFlow.collectAsStateWithLifecycle(initialValue = false)
    // Activity-scoped ViewModel: reused by the activity's own `by viewModels()` instance.
    val authViewModel: AuthViewModel = viewModel()

    // Flow snapshots are held as State (not read) at the root so a DataStore write only
    // invalidates the subtree that consumes it. Focus minutes derive from this snapshot.
    val liveBalanceState = bank.liveBalanceSeconds.collectAsStateWithLifecycle()
    val historyState = bank.workHistoryFlow.collectAsStateWithLifecycle(initialValue = null)
    val bankStatsState = bank.statsFlow.collectAsStateWithLifecycle(initialValue = null)
    val frogState by app.frogRepository.frogStateFlow.collectAsStateWithLifecycle(initialValue = null)

    // Goals come from Settings → Daily Goals (focus minutes / TickTick tasks goal).
    val focusGoalMinutes by settings.focusGoalMinutesFlow
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_FOCUS_GOAL_MINUTES)
    val dailyTasksGoalSetting by settings.dailyTasksGoalFlow
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_DAILY_TASKS_GOAL)
    // Leisure time is today's foreground use of apps currently selected in Boundaries.
    // Null until settings and UsageStats have been read, then zero is a real empty result.
    val boundaryApps by settings.blockedAppsFlow
        .collectAsStateWithLifecycle<List<BlockedApp>?>(initialValue = null)
    val boundaryWebsites by settings.blockedWebsitesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var boundaryLeisureSeconds by remember { mutableStateOf<Long?>(null) }
    var localUsageAvailable by remember { mutableStateOf<Boolean?>(null) }
    val workRatio by settings.workRatioFlow.collectAsStateWithLifecycle(initialValue = 4)

    val clerkUser by Clerk.userFlow.collectAsStateWithLifecycle(initialValue = null)
    val signedInAccountId = clerkUser?.id
    val todayUsageSnapshot by app.syncManager.todayUsageSnapshot.collectAsStateWithLifecycle()
    val syncStatus by app.syncManager.status.collectAsStateWithLifecycle()
    val remotePermanentSnapshot by app.syncManager.accountPermanentTargets.collectAsStateWithLifecycle()
    val localPermanentTargets by produceState(
        initialValue = AccountPermanentTargetsSnapshot("", emptyList()),
        key1 = signedInAccountId,
        key2 = syncStatus,
        key3 = todayUsageSnapshot,
    ) {
        val accountId = signedInAccountId
        value = if (accountId.isNullOrBlank()) AccountPermanentTargetsSnapshot("", emptyList())
        else withContext(Dispatchers.IO) {
            AccountPermanentTargetsSnapshot(accountId, app.permanentBlocksRepository.remoteTargets(accountId))
        }
    }
    val accountPermanentTargets = remember(remotePermanentSnapshot, localPermanentTargets, signedInAccountId) {
        buildList {
            addAll(remotePermanentSnapshot?.takeIf { it.accountId == signedInAccountId }?.targets.orEmpty())
            addAll(localPermanentTargets.takeIf { it.accountId == signedInAccountId }?.targets.orEmpty())
        }.distinctBy { "${it.targetKind.lowercase()}:${it.targetKey.lowercase()}" }
    }

    // Merged cross-device groups + today's synced per-group usage. Both come from the
    // already-running sync cycle (no network call on the Focus tab); the home styles
    // only read this snapshot.
    val targetGroups by app.targetGroupsRepository.groups
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val groupUsageTodaySeconds by app.syncManager.groupUsageTodaySeconds
        .collectAsStateWithLifecycle(initialValue = emptyMap())

    // Refresh permission + usage state on every resume (fixes stale "Setup needed" pill)
    var permissionTick by remember { mutableIntStateOf(0) }
    var usageRefreshTick by remember { mutableIntStateOf(0) }
    val todayDateKey = remember(usageRefreshTick, permissionTick) {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    // Activity-owned task state stays warm across tab changes instead of restarting
    // the network fan-out and showing a loading ring on every Focus return.
    val tasksViewModel: DashboardTasksViewModel = viewModel()
    val tasksSnapshot by tasksViewModel.tasks.collectAsStateWithLifecycle()
    val tickTickTasksDone = tasksSnapshot.completed
    val tickTickTasksState = tasksSnapshot.status
    val nextTaskTitle = tasksSnapshot.nextTitle
    val nextTaskDetail = tasksSnapshot.nextDetail
    fun startTickTickFetch(bypassCache: Boolean): Job =
        tasksViewModel.refresh(bypassCache)

    LaunchedEffect(Unit) { startTickTickFetch(bypassCache = false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionTick++
                startTickTickFetch(bypassCache = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Re-read selected-app usage every 30 seconds only while the screen is resumed.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(30_000L)
                usageRefreshTick++
            }
        }
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

    // Respect an explicit skip across launches. The compact setup card remains available.
    val onboardingPrefs = remember(context) {
        context.getSharedPreferences("focuslock_onboarding", android.content.Context.MODE_PRIVATE)
    }
    var onboardingSkipped by rememberSaveable {
        mutableStateOf(onboardingPrefs.getBoolean("permissions_skipped", false))
    }
    // Step-through onboarding: auto-show only until the user explicitly skips it.
    // Derived from the same single off-main permission pass above — zero extra binder sweeps.
    val missing: List<PermissionKind> = permSnapshot?.missing ?: emptyList()
    // Seeded from the process latch so a dismissal survives tab switches (which tear
    // down this composition); every set-to-true below writes the latch back through.
    var shownThisSession by rememberSaveable { mutableStateOf(permissionOnboardingDismissedForProcess) }
    // Index persists across tab switches via rememberSaveable, and is additionally
    // mirrored into the process-level [permissionOnboardingLastIndex] on every advance
    // so a torn-down composition resumes at the right step.
    var dialogIndex by rememberSaveable { mutableIntStateOf(permissionOnboardingLastIndex) }
    var showOnboarding by remember { mutableStateOf(false) }
    // Keep the original steps for this onboarding run. The live `missing` list shrinks
    // after each grant; using it as the dialog's sequence resets the counter to 1/N.
    var onboardingSteps by remember { mutableStateOf<List<PermissionKind>>(emptyList()) }
    LaunchedEffect(missing, onboardingSkipped) {
        if (missing.isEmpty()) {
            showOnboarding = false
        } else if (!onboardingSkipped && !shownThisSession && !permissionOnboardingDismissedForProcess) {
            if (onboardingSteps.isEmpty()) {
                onboardingSteps = missing
                dialogIndex = 0
            }
            showOnboarding = true
        }
    }

    // StayFree-style screen-time summary. Null until the first query resolves so the UI can
    // distinguish "loading" from a real empty result (no zero-state flash).
    var usageSummary by remember { mutableStateOf<DailyUsageSummary?>(null) }
    LaunchedEffect(permissionTick, usageRefreshTick, boundaryApps, boundaryWebsites, todayUsageSnapshot, clerkUser?.id, todayDateKey, accountPermanentTargets) {
        // Tab returns and boundary edits reuse the shared aggregate. Explicit refresh
        // invalidates it; routine reads expire naturally after 30 seconds.
        usageSummary = withContext(Dispatchers.IO) {
            UsageStatsRepository.getTodaySummary(context, maxApps = 8)
        }
        val expectedAccountId = signedInAccountId
        val localBuckets = app.syncManager.getTodayAccountLocalUsageBuckets(expectedAccountId)
        localUsageAvailable = localBuckets != null
        val dayKey = todayDateKey
        val remoteSummary = todayUsageSnapshot.summaryForAccountDate(expectedAccountId, dayKey)
        val apps = boundaryApps.orEmpty().filter { it.isBlocked || it.isPermanent }
        val websites = boundaryWebsites.filter { it.isBlocked || it.isPermanent }
        val accountPermanentApps = accountPermanentTargets.filter {
            it.targetKind.equals("android", true) || it.targetKind.equals("app", true) || it.targetKind.equals("windows", true)
        }
        val accountPermanentSites = accountPermanentTargets.filter { it.targetKind.equals("website", true) }
        val merged = mergeTodayBoundaryUsageSeconds(
            localBuckets = localBuckets.orEmpty(),
            summary = remoteSummary,
            deviceId = if (expectedAccountId != null) app.syncManager.currentDeviceIdForUsage() else null,
            boundaryApps = (apps.map { it.packageName } + accountPermanentApps.map { it.targetKey }).toHashSet(),
            boundaryDomains = if (expectedAccountId != null)
                (websites.map { it.domain } + accountPermanentSites.map { it.targetKey }).toHashSet()
                else emptySet(),
            date = dayKey,
        )
        if (clerkUser?.id != expectedAccountId || SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) != dayKey) return@LaunchedEffect
        boundaryLeisureSeconds = merged
    }

    // In-app fallback auto-return: polls the permission the user just opened Settings for
    // and brings the app forward once granted, even before the accessibility service is
    // connected. Intentionally an infinite cancellable loop — dies with this composable.
    LaunchedEffect(Unit) {
        PermissionReturnWatcher.fallbackWatch(context)
    }

    var showManualLogDialog by rememberSaveable { mutableStateOf(false) }
    var showFocusTimerDialog by rememberSaveable { mutableStateOf(false) }
    var showAllHistory by rememberSaveable { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    val todayListState = rememberLazyListState()
    val activityListState = rememberLazyListState()
    val activeListState = if (showActivity) activityListState else todayListState

    // ---- Focus timer state, hoisted to screen level so a running countdown survives
    // tab switches (the dialog tears down with the Focus tab, but this state does not).
    // endAtMs is a wall-clock Long anchor (0 = idle); remainingSeconds recomputes from it
    // every tick, so the countdown never drifts and survives process death via saveable.
    var timerSelectedMinutes by rememberSaveable { mutableIntStateOf(25) }
    var timerIsRunning by rememberSaveable { mutableStateOf(false) }
    var timerRemainingSeconds by rememberSaveable { mutableIntStateOf(25 * 60) }
    var timerEndAtMs by rememberSaveable { mutableLongStateOf(0L) }
    var timerJustCompleted by remember { mutableStateOf(false) }

    // Completion handling lives here — NOT inside the dialog — so the record is credited
    // even if the dialog (or the whole Focus tab) left composition mid-countdown.
    LaunchedEffect(timerJustCompleted) {
        if (!timerJustCompleted) return@LaunchedEffect
        timerJustCompleted = false
        val focusedMinutes = timerSelectedMinutes
        timerEndAtMs = 0L
        timerRemainingSeconds = 0
        timerIsRunning = false
        showFocusTimerDialog = false
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

    // The ticking loop also lives at screen level: it keeps counting while the user is on
    // another tab and fires the completion flag when the wall-clock anchor elapses.
    LaunchedEffect(timerIsRunning) {
        if (!timerIsRunning) return@LaunchedEffect
        if (timerEndAtMs <= 0L) {
            timerEndAtMs = System.currentTimeMillis() + timerRemainingSeconds * 1000L
        }
        var finishedNaturally = false
        while (true) {
            val remainingMs = timerEndAtMs - System.currentTimeMillis()
            if (remainingMs <= 0L) {
                finishedNaturally = true
                break
            }
            // Display ceil(remaining) — recomputed from the anchor every tick (no
            // += accumulation), so the timer honors the real wall-clock end time.
            val nextSeconds = ((remainingMs + 999L) / 1000L).toInt()
            if (nextSeconds != timerRemainingSeconds) timerRemainingSeconds = nextSeconds
            // Sleep exactly until the next displayed second flips (never a busy loop).
            val tickDelay = remainingMs % 1000L
            delay(if (tickDelay == 0L) 1000L else tickDelay)
        }
        if (finishedNaturally) {
            timerJustCompleted = true
        }
    }

    val todayFormatted = remember(todayDateKey) {
        SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date())
    }

    // ---- Shared home state: built once from the collected values above, then dispatched
    // to the selected variation. Variations render only; all behavior stays hoisted here.
    val historySnapshot = historyState.value
    val bankStats = bankStatsState.value
    val focusMinutes = if (bankStats?.lastResetDate == todayDateKey) bankStats.totalWorkMinutesToday else 0
    val focusMinutesLoaded = bankStats != null
    val accountTodayUsage = todayUsageSnapshot.summaryForAccountDate(signedInAccountId, todayDateKey)
    // Top used app today (exclude launcher noise); null while loading / off / empty.
    val topApp = usageSummary?.topApps?.firstOrNull {
        !it.packageName.contains("launcher", ignoreCase = true) &&
            !it.appName.contains("launcher", ignoreCase = true)
    }
    val accountName = listOfNotNull(clerkUser?.firstName, clerkUser?.lastName)
        .joinToString(" ").trim().ifBlank { null }
        ?: clerkUser?.primaryEmailAddress?.emailAddress
    // Remembered on every value it embeds: identity only changes when real inputs change,
    // so unrelated recompositions (e.g. the 1-second balance tick) don't hand the home
    // tree a new FocusHomeState and defeat skipping.
    val homeState = remember(
        todayFormatted,
        permissionsChecked,
        hasAllPermissions,
        isAccessibilityOn,
        isUsageAccessOn,
        isNotificationOn,
        focusMinutes,
        focusMinutesLoaded,
        historySnapshot,
        focusGoalMinutes,
        tickTickTasksDone,
        tickTickTasksState,
        nextTaskTitle,
        nextTaskDetail,
        dailyTasksGoalSetting,
        boundaryLeisureSeconds,
        workRatio,
        usageSummary,
        topApp,
        showAllHistory,
        liveBalanceState,
        nukeActive,
        accountName,
        targetGroups,
        groupUsageTodaySeconds,
        accountPermanentTargets,
        todayUsageSnapshot,
        syncStatus,
        localUsageAvailable,
        signedInAccountId,
    ) {
        FocusHomeState(
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
            focusMinutesLoaded = focusMinutesLoaded,
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
            leisureSeconds = boundaryLeisureSeconds,
            targetFocusPerLeisure = workRatio.toDouble().coerceAtLeast(1.0),
            nextTaskTitle = nextTaskTitle,
            nextTaskDetail = nextTaskDetail,
            usageSummary = usageSummary,
            topApp = topApp,
            history = historySnapshot,
            showAllHistory = showAllHistory,
            liveBalanceState = liveBalanceState,
            nukeActive = nukeActive,
            accountInitial = accountName?.firstOrNull { it.isLetterOrDigit() }
                ?.uppercaseChar()?.toString() ?: "",
            crossDeviceGroups = targetGroups,
            groupUsageTodaySeconds = groupUsageTodaySeconds,
            totalCrossDeviceSecondsToday = accountTodayUsage?.totalTrackedSeconds ?: 0L,
            focusMetricCaption = if (clerkUser == null) "Logged work on this phone" else "Logged work across devices",
            leisureMetricCaption = when {
                clerkUser == null -> "Boundary apps measured on this phone"
                accountTodayUsage == null -> "Boundary apps and websites · Waiting for account usage"
                isUsageAccessOn != true -> "Other devices only · Usage Access needed here"
                localUsageAvailable == false -> "Phone usage unavailable · showing available account data"
                accountTodayUsage.deviceTargets == null -> "Available measurements · leisure is a safe minimum"
                else -> "Boundary apps and websites across devices"
            },
            usageStatusCaption = when (syncStatus) {
                SyncStatus.Syncing -> "Refreshing account usage…"
                is SyncStatus.Error -> "Account usage sync needs attention"
                is SyncStatus.Skipped -> "Account usage may be out of date"
                is SyncStatus.Done -> "Account usage synced"
                else -> if (clerkUser == null) "Sign in to include other devices" else "Waiting for account usage"
            },
            usageAccessGranted = isUsageAccessOn,
            localUsageDataAvailable = localUsageAvailable,
        )
    }
    val openSettings: () -> Unit = onOpenSettings ?: onNavigatePermissions
    val openAccount: () -> Unit = onOpenAccount ?: openSettings
    val openConnections: () -> Unit = onOpenConnections ?: openSettings
    // Stable callback object: remembered on the values the lambdas actually capture so a
    // 1-second tick (or any unrelated recomposition) doesn't rebuild fresh lambdas and
    // invalidate the whole home tree. The state-mutating lambdas close over `by remember`
    // delegates (stable across recompositions), so they never need to be keys.
    val homeCallbacks = remember(
        onOpenTickTick,
        onNavigatePermissions,
        openSettings,
        openConnections,
        openAccount,
    ) {
        FocusHomeCallbacks(
            onOpenTickTick = onOpenTickTick,
            onNavigatePermissions = onNavigatePermissions,
            onOpenSettings = openSettings,
            onOpenConnections = openConnections,
            onOpenAccount = openAccount,
            onOpenLog = { showManualLogDialog = true },
            onOpenTimer = { showFocusTimerDialog = true },
            onToggleHistory = { showAllHistory = !showAllHistory },
            onRetryTasks = { startTickTickFetch(bypassCache = true) },
            // Legacy home-header affordances remain wired for API compatibility; the
            // actual Nuke action and dialogs live in the app bar's NukeActionButton.
            onShowNukeConfirm = {},
            onShowNukeInfo = {},
            onLaunchNuke = {},
        )
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            if (!isRefreshing) {
                isRefreshing = true
                scope.launch {
                    try {
                        UsageStatsRepository.invalidatePackageUsage()
                        permissionTick++
                        // Fetch providers together, then sync the account so newly
                        // imported focus credits can reach the other devices.
                        val failures = refreshDashboardSources(
                            refreshTasks = {
                                tasksViewModel.refreshAndAwait(bypassCache = true).status.let {
                                    it == TickTickTasksState.Loaded || it == TickTickTasksState.NoAccount
                                }
                            },
                            refreshFocus = {
                                if (settings.tickTickTokenFlow.first().isBlank()) true
                                else app.tickTickFocusSync.sync() != null
                            },
                            refreshAccount = {
                                if (authViewModel.getAccountId() == null) true
                                else {
                                    app.syncManager.syncNow(authViewModel, forceUsageRefresh = true)
                                    app.syncManager.status.value is SyncStatus.Done
                                }
                            },
                        )
                        usageRefreshTick++
                        if (failures.isNotEmpty()) {
                            Toast.makeText(
                                context,
                                "Couldn't refresh ${failures.joinToString(", ")}. Try swiping down again.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    } finally {
                        isRefreshing = false
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            state = activeListState,
            contentPadding = PaddingValues(
                start = UiTokens.ScreenPadding,
                end = UiTokens.ScreenPadding,
                top = 12.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showActivity) {
                ActivityDestination(state = homeState, callbacks = homeCallbacks)
            } else {
                TodayDestination(
                    state = homeState,
                    callbacks = homeCallbacks,
                    frogEnabled = frogState?.enabled,
                    timerIsRunning = timerIsRunning,
                    timerRemainingSeconds = timerRemainingSeconds,
                )
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

    // Built-in focus timer dialog (single session — works without TickTick). All timer
    // state is hoisted above; the dialog is a pure view over it, so dismissing it (or
    // tab-switching it away) never kills a running countdown.
    if (showFocusTimerDialog) {
        FocusTimerDialog(
            selectedMinutes = timerSelectedMinutes,
            onSelectedMinutesChange = { timerSelectedMinutes = it },
            isRunning = timerIsRunning,
            remainingSeconds = timerRemainingSeconds,
            onStart = {
                timerRemainingSeconds = timerSelectedMinutes * 60
                timerEndAtMs = 0L
                timerIsRunning = true
            },
            onFinishEarly = {
                // Partial credit for >= 5 min of focus, then close. Ceil on the
                // remaining seconds (= floor of elapsed minutes) so the credited
                // minutes can never exceed the full-completion path (selectedMinutes).
                val doneMinutes = timerSelectedMinutes - ((timerRemainingSeconds + 59) / 60)
                timerIsRunning = false
                timerEndAtMs = 0L
                if (doneMinutes >= 5) {
                    scope.launch {
                        val ratio = settings.workRatioFlow.first()
                        val record = TickTickWorkRecord(
                            id = "timer_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                            title = "Focus Timer Session",
                            durationMinutes = doneMinutes,
                            source = WorkRecordSource.MANUAL_ENTRY,
                            projectName = "Focus Timer"
                        )
                        val earned = bank.recordWorkCredit(record, ratio, 0)
                        permissionTick++
                        Toast.makeText(context, "Focus done! +$earned min leisure earned", Toast.LENGTH_LONG).show()
                    }
                }
                showFocusTimerDialog = false
            },
            onDismiss = { showFocusTimerDialog = false },
        )
    }

    // Step-through permission onboarding dialogs (one per missing permission)
    if (showOnboarding && onboardingSteps.isNotEmpty()) {
        PermissionOnboardingDialog(
            missing = onboardingSteps,
            currentIndex = dialogIndex.coerceIn(0, onboardingSteps.size - 1),
            onGrant = { kind ->
                PermissionHelper.openPermissionWithHighlight(context, kind)
                // Do not advance just for opening Settings: PermissionReturnWatcher pulls the
                // app back automatically once the grant lands, and the resume re-check then
                // re-derives `missing` and flips the dialog action to Next/Done.
                // Only skip ahead if it was already granted when tapped.
                if (PermissionHelper.isGranted(context, kind)) {
                    if (dialogIndex < onboardingSteps.size - 1) dialogIndex++ else showOnboarding = false
                    // Persist progress on every advance so a mid-flow tab switch never
                    // re-arms the dialog from step 0 (and never re-shows after the user
                    // has already moved past a step).
                    permissionOnboardingLastIndex = dialogIndex
                    shownThisSession = true
                    permissionOnboardingDismissedForProcess = true
                }
            },
            onDismiss = {
                if (dialogIndex < onboardingSteps.size - 1) {
                    dialogIndex++
                    permissionOnboardingLastIndex = dialogIndex
                    // Mid-flow skip: latch the dialog off for this process too, so
                    // tab-switching away and back doesn't resurrect the flow.
                    shownThisSession = true
                    permissionOnboardingDismissedForProcess = true
                } else {
                    showOnboarding = false
                    shownThisSession = true
                    permissionOnboardingDismissedForProcess = true
                    onboardingSkipped = true
                    onboardingPrefs.edit().putBoolean("permissions_skipped", true).apply()
                }
            },
            onSkipAll = {
                showOnboarding = false
                shownThisSession = true
                permissionOnboardingDismissedForProcess = true
                onboardingSkipped = true
                onboardingPrefs.edit().putBoolean("permissions_skipped", true).apply()
            }
        )
    }
}

/**
 * Focus timer dialog — a pure view over screen-hoisted timer state (see
 * DashboardScreen). Owns no countdown state itself: [isRunning]/[remainingSeconds]
 * and the wall-clock anchor live at the DashboardScreen level so a running session
 * survives tab switches and still credits the record when it completes.
 */
@Composable
private fun FocusTimerDialog(
    selectedMinutes: Int,
    onSelectedMinutesChange: (Int) -> Unit,
    isRunning: Boolean,
    remainingSeconds: Int,
    onStart: () -> Unit,
    onFinishEarly: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
                                onClick = { onSelectedMinutesChange(mins) },
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
                        "Hide the timer dialog whenever you like; the countdown will keep running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (!isRunning) {
                Button(onClick = onStart, shape = RoundedCornerShape(20.dp)) { Text("Start") }
            } else {
                TextButton(onClick = onFinishEarly) { Text("Finish Early") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isRunning) "Hide timer" else "Cancel") }
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
