package com.focuslock.app.ui.dashboard

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.focuslock.app.service.DailyUsageSummary
import com.focuslock.app.service.InstalledAppsRepository
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.service.TickTickAuthConfig
import com.focuslock.app.service.UsageStatsRepository
import com.focuslock.app.sync.ConvexSyncClient
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
    // invalidates the LazyColumn item that consumes it (hero Canvas/buttons no longer
    // recompose on every bank/settings write). Focus minutes derive from this snapshot.
    val liveBalanceState = bank.liveBalanceSeconds.collectAsStateWithLifecycle()
    val historyState = bank.workHistoryFlow.collectAsStateWithLifecycle(initialValue = null)

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
    var shownThisSession by rememberSaveable { mutableStateOf(false) }
    var dialogIndex by rememberSaveable { mutableIntStateOf(0) }
    var showOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(missing) {
        if (missing.isEmpty()) {
            showOnboarding = false
        } else {
            // Re-derive the step from live grant state on every resume so the dialog
            // never points at a stale/out-of-range index after returning from Settings.
            dialogIndex = dialogIndex.coerceIn(0, missing.size - 1)
            if (!shownThisSession) showOnboarding = true
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

    // Playful Nuke launcher states (header button); nukeActive is collected inside the header item.
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

    val listState = rememberLazyListState()
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
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. At-a-glance header
        item(key = "header") {
            val nukeActive by settings.nukeActiveFlow.collectAsStateWithLifecycle(initialValue = false)
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

                // Right side: Nuke (with tiny label) + account avatar (Google-ref style).
                // Avatar tap opens Settings; falls back to the permissions nav (same tab).
                val openSettings: () -> Unit = onOpenSettings ?: onNavigatePermissions
                val openAccount: () -> Unit = onOpenAccount ?: openSettings
                val clerkUser by Clerk.userFlow.collectAsState(initial = null)
                val accountBlue = MaterialTheme.colorScheme.primary
                val accountName = listOfNotNull(clerkUser?.firstName, clerkUser?.lastName)
                    .joinToString(" ").trim().ifBlank { null }
                    ?: clerkUser?.primaryEmailAddress?.emailAddress
                val accountInitial = accountName?.firstOrNull { it.isLetterOrDigit() }
                    ?.uppercaseChar()?.toString() ?: ""
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Nuke button (compact ☢️; tap to arm, long-press for details).
                    val nukeInteraction = remember { MutableInteractionSource() }
                    val nukePressed by nukeInteraction.collectIsPressedAsState()
                    val nukePressScale by animateFloatAsState(
                        targetValue = if (nukePressed) 0.88f else 1f,
                        animationSpec = tween(150),
                        label = "nuke-press"
                    )
                    val nukePulse = rememberInfiniteTransition(label = "nuke-pulse")
                    val nukeHaloAlphaState = nukePulse.animateFloat(
                        initialValue = 0.08f,
                        targetValue = 0.22f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "nuke-halo"
                    )
                    val nukeHaloColor = MaterialTheme.colorScheme.error
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .graphicsLayer {
                                // Read the press animation in the layer lambda, not composition.
                                scaleX = nukePressScale
                                scaleY = nukePressScale
                            }
                            .clip(CircleShape)
                            .drawBehind {
                                // Animated alpha is read in the draw phase so the pulse
                                // never recomposes the header.
                                drawCircle(
                                    color = nukeHaloColor,
                                    alpha = (nukeHaloAlphaState.value + 0.07f).coerceIn(0f, 1f)
                                )
                            }
                            .semantics {
                                contentDescription = "Nuke: emergency lockdown"
                            }
                            .combinedClickable(
                                interactionSource = nukeInteraction,
                                indication = null,
                                onClick = {
                                    if (nukeActive) launchNukeActivity() else showNukeConfirm = true
                                },
                                onLongClick = { showNukeInfo = true }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "☢️",
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Settings gear — 48dp touch target, opens the Settings tab.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Settings" }
                            .clickable { openSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Account avatar: 48dp touch target; initial-letter circle with blue ring.
                    if (accountInitial.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Account" }
                                .clickable { openAccount() },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(accountBlue, CircleShape)
                                    .border(2.dp, accountBlue, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = accountInitial,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Account" }
                                .clickable { openAccount() },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                                    .border(2.dp, accountBlue, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Missing Permissions Warning Card (only after all checks resolved)
        if (permissionsChecked && !hasAllPermissions) {
            item(key = "perms") {
                // Cheap pulse: only run the infinite transition while resumed; static otherwise.
                var cardResumed by remember { mutableStateOf(true) }
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> cardResumed = true
                            Lifecycle.Event.ON_PAUSE -> cardResumed = false
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val setupAlphaState: State<Float>? = if (cardResumed) {
                    val setupPulse = rememberInfiniteTransition(label = "setup-pulse")
                    setupPulse.animateFloat(
                        initialValue = 0.45f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "setup-pulse-alpha"
                    )
                } else {
                    null
                }
                val cardBorderColor = MaterialTheme.colorScheme.error
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            drawContent()
                            // Pulse alpha is read in the draw phase, not composition.
                            // Theme large = RoundedCornerShape(20.dp), stroke 2.dp.
                            drawRoundRect(
                                color = cardBorderColor,
                                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                                size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
                                cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                                style = Stroke(width = 2.dp.toPx()),
                                alpha = setupAlphaState?.value ?: 1f
                            )
                        }
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
                                    if (isAccessibilityOn != true) missing.add("Accessibility")
                                    if (isUsageAccessOn != true) missing.add("Usage Access")
                                    if (isNotificationOn != true) missing.add("Notifications")
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

        // 2. Fitbit-style hero: rings circle (left) + 2 stacked cards (right),
        // then Log / Focus-timer button row, then History outline button.
        item(key = "hero") {
            // Goals come from Settings → Daily Goals (focus minutes / TickTick tasks goal).
            val focusGoalMinutes by settings.focusGoalMinutesFlow
                .collectAsStateWithLifecycle(initialValue = 120)
            val dailyTasksGoalSetting by settings.dailyTasksGoalFlow
                .collectAsStateWithLifecycle(initialValue = 7)
            // Focus minutes derive from the single collected history (no second JSON decode).
            val historySnapshot = historyState.value
            val focusMinutes = remember(historySnapshot) {
                historySnapshot
                    ?.filter { CreditBankRepository.isFocusRecord(it.source, it.durationMinutes) }
                    ?.sumOf { it.durationMinutes } ?: 0
            }
            val focusMinutesLoaded = historySnapshot != null
            val dailyFocusGoal = focusGoalMinutes.coerceAtLeast(1)
            val dailyTasksGoal = dailyTasksGoalSetting.coerceAtLeast(1)
            // Real tasks = TickTick completed-today count (loaded above).
            val tasksDone = tickTickTasksDone
            val tasksLoaded = tickTickTasksState == TickTickTasksState.Loaded
            val focusProgress = if (focusMinutesLoaded) {
                (focusMinutes / dailyFocusGoal.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val tasksProgress = if (tasksLoaded) {
                (tasksDone / dailyTasksGoal.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val focusSweep by animateFloatAsState(
                targetValue = focusProgress,
                animationSpec = tween(800),
                label = "focus-sweep"
            )
            val tasksSweep by animateFloatAsState(
                targetValue = tasksProgress,
                animationSpec = tween(800),
                label = "tasks-sweep"
            )
            val focusPct = (focusProgress * 100).toInt()
            val ringPink = MaterialTheme.colorScheme.primary
            val ringCyan = MaterialTheme.colorScheme.tertiary
            val ringTrack = MaterialTheme.colorScheme.surfaceVariant
            val subtitleGray = MaterialTheme.colorScheme.onSurfaceVariant
            val tealCard = MaterialTheme.colorScheme.secondaryContainer
            val tealText = MaterialTheme.colorScheme.onSecondaryContainer
            val purpleCard = MaterialTheme.colorScheme.tertiaryContainer
            val purpleText = MaterialTheme.colorScheme.onTertiaryContainer
            val googleBlue = MaterialTheme.colorScheme.primary
            // Top used app today (exclude launcher noise); null while loading / off / empty.
            val topApp = usageSummary?.topApps?.firstOrNull {
                !it.packageName.contains("launcher", ignoreCase = true) &&
                    !it.appName.contains("launcher", ignoreCase = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Ring variant: fixed Canvas (option 1 of 10) — proper stroke/inset proportions.
                    Box(
                        modifier = Modifier.size(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = 12.dp.toPx()
                            val gap = 8.dp.toPx()
                            val cx = size.width / 2
                            val cy = size.height / 2
                            val outerR = size.minDimension / 2 - stroke / 2
                            val innerR = outerR - stroke - gap
                            fun topLeft(r: Float) = Offset(cx - r, cy - r)
                            fun arcSize(r: Float) = Size(r * 2, r * 2)
                            // Tracks
                            drawArc(
                                color = ringTrack,
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft(outerR),
                                size = arcSize(outerR),
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = ringTrack,
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft(innerR),
                                size = arcSize(innerR),
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                            // Progress arcs
                            drawArc(
                                color = ringPink,
                                startAngle = -90f,
                                sweepAngle = 360f * focusSweep,
                                useCenter = false,
                                topLeft = topLeft(outerR),
                                size = arcSize(outerR),
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = ringCyan,
                                startAngle = -90f,
                                sweepAngle = 360f * tasksSweep,
                                useCenter = false,
                                topLeft = topLeft(innerR),
                                size = arcSize(innerR),
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (focusMinutesLoaded) "$focusPct%" else "…",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFeatureSettings = "tnum"
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Focus",
                                style = MaterialTheme.typography.labelSmall,
                                color = subtitleGray
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = 10.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(googleBlue, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (tasksLoaded) "+$tasksDone" else "—",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = tealCard,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (topApp != null) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Real app icon from PackageManager (cached).
                                    var topAppIcon by remember(topApp.packageName) {
                                        mutableStateOf<ImageBitmap?>(null)
                                    }
                                    LaunchedEffect(topApp.packageName) {
                                        topAppIcon = InstalledAppsRepository.getCachedIconBitmap(topApp.packageName)
                                            ?: InstalledAppsRepository.getAppIconBitmap(context, topApp.packageName)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(tealText.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val bmp = topAppIcon
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                Icons.Outlined.PhoneAndroid,
                                                contentDescription = null,
                                                tint = tealText,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = topApp.appName,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${topApp.foregroundMinutes} min",
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFeatureSettings = "tnum"
                                            ),
                                            color = tealText
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(tealText.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Outlined.PhoneAndroid,
                                            contentDescription = null,
                                            tint = tealText,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = when {
                                                isUsageAccessOn != true -> "Usage Access needed"
                                                usageSummary == null -> "Checking usage…"
                                                else -> "No usage data yet"
                                            },
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            text = when {
                                                isUsageAccessOn != true -> "Turn it on to see today's screen time."
                                                usageSummary == null -> "Reading today's stats…"
                                                else -> "Open some apps and check back later."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                        )
                                        if (isUsageAccessOn != true) {
                                            TextButton(
                                                onClick = onNavigatePermissions,
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text(
                                                    "Grant Usage Access",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = tealText
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Surface(
                            color = purpleCard,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(purpleText.copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = purpleText,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Tasks",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    when (tickTickTasksState) {
                                        TickTickTasksState.Loading -> Text(
                                            text = "…",
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = purpleText
                                        )
                                        TickTickTasksState.Error -> Text(
                                            text = "Couldn't load",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = purpleText
                                        )
                                        else -> Text(
                                            text = "$tasksDone/$dailyTasksGoal done",
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFeatureSettings = "tnum"
                                            ),
                                            color = purpleText
                                        )
                                    }
                                    when (tickTickTasksState) {
                                        TickTickTasksState.Error -> TextButton(
                                            onClick = { startTickTickFetch(bypassCache = true) },
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text(
                                                "Retry",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = purpleText
                                            )
                                        }
                                        TickTickTasksState.NoAccount -> Text(
                                            text = "Connect TickTick for task tracking",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = purpleText.copy(alpha = 0.85f),
                                            modifier = Modifier.clickable {
                                                (onOpenSettings ?: onNavigatePermissions)()
                                            }
                                        )
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { showManualLogDialog = true },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = googleBlue,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Log", maxLines = 1)
                    }
                    // Focus Timer: built-in single-session timer — no TickTick needed.
                    Button(
                        onClick = { showFocusTimerDialog = true },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = googleBlue,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Timer", maxLines = 1)
                    }
                    // "Tasks" opens the TickTick task app.
                    Button(
                        onClick = onOpenTickTick,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = googleBlue,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tasks", maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = { showAllHistory = !showAllHistory },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (showAllHistory) "Show less" else "History — show all")
                }
            }
        }

        // Nuke trigger lives in the dashboard header (top-right ☢️ button).

        // 5. Focus through the day — 24 hourly buckets, focus records only.
        item(key = "day") {
            FocusThroughDayCard(history = historyState.value ?: emptyList())
        }

        // 6. Work history
        item(key = "history") {
            val history = historyState.value.orEmpty()
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

        // One item owns the history snapshot: only this subtree recomposes on history writes.
        if (showAllHistory) {
            // Expanded: emit each record as a real lazy item (same 12dp LazyColumn spacing) so
            // cards are composed and measured on demand instead of all in one giant frame.
            val expandedHistory = historyState.value
            if (expandedHistory.isNullOrEmpty()) {
                item(key = "history-list") {
                    if (expandedHistory != null) EmptyHistoryCard()
                }
            } else {
                items(expandedHistory, key = { it.id }) { record ->
                    PixelWorkRecordItem(record = record)
                }
            }
        } else {
            item(key = "history-list") {
                val historySnapshot = historyState.value
                val history = historySnapshot.orEmpty()
                if (historySnapshot != null && history.isEmpty()) {
                    EmptyHistoryCard()
                } else if (history.isNotEmpty()) {
                    // Collapsed to the 3 most recent until "History — show all" is tapped.
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        history.take(3).forEach { record ->
                            key(record.id) { PixelWorkRecordItem(record = record) }
                        }
                    }
                }
            }
        }

        // Demoted leisure: small caption card at the very bottom.
        item(key = "bank") {
            val liveBalanceSeconds = liveBalanceState.value
            val bankMinutes = liveBalanceSeconds / 60
            val bankSeconds = liveBalanceSeconds % 60
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Scroll bank (from focus): ${bankMinutes}m ${bankSeconds.toString().padStart(2, '0')}s",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

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
                    colors = ButtonDefaults.buttonColors(
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

/** Empty-state card for the work-history section (loaded but no records today). */
@Composable
private fun EmptyHistoryCard() {
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

@Composable
fun PixelWorkRecordItem(record: TickTickWorkRecord) {
    // Format per timestamp instead of allocating a formatter on every row recomposition.
    val formattedTime = remember(record.timestamp) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(record.timestamp))
    }

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
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
private fun FocusThroughDayCard(history: List<TickTickWorkRecord>) {
    // 24 hourly buckets (0-23) from workHistory timestamps — focus records only.
    val buckets = remember(history) {
        val arr = IntArray(24)
        val cal = java.util.Calendar.getInstance()
        for (r in history) {
            if (!com.focuslock.app.data.repository.CreditBankRepository.isFocusRecord(r.source, r.durationMinutes)) continue
            cal.timeInMillis = r.timestamp
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            arr[h] += r.durationMinutes
        }
        arr
    }
    val max = (buckets.maxOrNull() ?: 0).coerceAtLeast(0)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Focus through the day",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "max ${max}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (max <= 0) {
                Text(
                    "No focus yet today — start Focus Timer, a TickTick session, or a manual log.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    buckets.forEachIndexed { _, mins ->
                        val fraction = if (max > 0) mins / max.toFloat() else 0f
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(if (mins <= 0) 0.04f else fraction.coerceAtLeast(0.06f))
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            if (mins > 0) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("0", "6", "12", "18", "23").forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
