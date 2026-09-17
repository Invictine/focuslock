@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.focuslock.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.focuslock.app.BuildConfig
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.auth.AccountScreen
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.auth.FocusAuthGate
import com.focuslock.app.auth.FocusAuthState
import com.focuslock.app.service.AppMonitorForegroundService
import com.focuslock.app.sync.ConvexSyncClient
import com.focuslock.app.sync.DeviceInfo
import com.focuslock.app.sync.SyncStatus
import com.focuslock.app.sync.UsageSummary
import com.focuslock.app.ui.apps.BoundariesScreen
import com.focuslock.app.ui.components.CrossDeviceUsageSection
import com.focuslock.app.ui.components.PendingMergeTarget
import com.focuslock.app.ui.components.SectionHeader
import com.focuslock.app.ui.components.formatUsageSeconds
import com.focuslock.app.ui.dashboard.DashboardScreen
import com.focuslock.app.ui.debug.DebugDataScreen
import com.focuslock.app.ui.permissions.PermissionHelper
import com.focuslock.app.ui.permissions.PermissionReturnWatcher
import com.focuslock.app.ui.settings.SettingsScreen
import com.focuslock.app.ui.strict.StrictModeScreen
import com.focuslock.app.ui.theme.FocusLockTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NavigationItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD("Focus", Icons.Rounded.Home, Icons.Rounded.Home),
    APPS("Boundaries", Icons.Rounded.GridView, Icons.Rounded.GridView),
    STRICT("Strict", Icons.Rounded.Lock, Icons.Rounded.Lock),
    SETTINGS("Settings", Icons.Rounded.Settings, Icons.Rounded.Settings),
    ACCOUNT("Account", Icons.Rounded.Person, Icons.Rounded.Person)
}

class MainActivity : ComponentActivity() {

    private var monitorServiceStarted = false

    /** Last time [startMonitorServiceIfPermitted] ran the binder permission sweep (main thread only). */
    private var lastMonitorPermissionCheckMs = 0L
    private var notificationPermissionAsked = false
    private var notificationPermissionBlockedForever = false
    private val showNotificationRationale = mutableStateOf(false)
    private val notificationDenied = mutableStateOf(false)
    private val snackbarHostState = SnackbarHostState()

    private val permissionPrefs by lazy { getSharedPreferences(PERM_PREFS, MODE_PRIVATE) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // First denial still shows the rationale on the next attempt; once Android stops
            // showing it (or the user picked "don't ask again") we stop asking for good.
            val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            val denials = permissionPrefs.getInt(KEY_NOTIFICATION_DENIALS, 0) + 1
            permissionPrefs.edit()
                .putInt(KEY_NOTIFICATION_DENIALS, if (canAskAgain) denials else 2)
                .apply()
            if (!canAskAgain) notificationPermissionBlockedForever = true
            notificationDenied.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            // auto() lets the system pick bar-icon appearance from the active light/dark
            // mode; the previous forced SystemBarStyle.dark() drew white (light) status
            // icons on the light app background, making the clock/battery unreadable.
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        // Start protection only once the core permissions exist. POST_NOTIFICATIONS is
        // requested separately afterwards, and only when the monitor service actually starts.
        startMonitorServiceIfPermitted()

        setContent {
            FocusLockTheme {
                val authViewModel: AuthViewModel by viewModels()
                val authState by authViewModel.state.collectAsStateWithLifecycle()

                val app = application as FocusLockApplication
                // Offline mode is a persisted preference chosen on the auth gate; the
                // nullable override only carries the in-session flip until DataStore emits.
                val persistedOfflineMode by app.settingsRepository.offlineModeFlow
                    .collectAsStateWithLifecycle(initialValue = false)
                var offlineOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
                val offlineMode = offlineOverride ?: persistedOfflineMode

                // Auto-sync lifecycle: start when signed in, stop otherwise. Signing in
                // clears the persisted offline choice so a later sign-out shows the gate;
                // Loading/SignedOut never clear it (doing so resurrected the gate on every
                // cold start of an offline user).
                LaunchedEffect(authState) {
                    if (authState == FocusAuthState.SignedIn) {
                        offlineOverride = false
                        app.settingsRepository.setOfflineMode(false)
                        app.syncManager.startAutoSync(authViewModel)
                        app.syncManager.syncNowAsync(authViewModel)
                    } else {
                        app.syncManager.stopAutoSync()
                    }
                }

                // The user may have just granted Accessibility/Usage Access in system settings;
                // retry starting the monitor on every resume instead of only on cold start.
                // The resume/pause hooks also let PermissionReturnWatcher know whether the app
                // is visible, and drop any pending auto-return we handled ourselves.
                val lifecycleOwner = LocalLifecycleOwner.current
                val appContext = applicationContext
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                PermissionReturnWatcher.onAppResumed(appContext)
                                startMonitorServiceIfPermitted()
                            }
                            Lifecycle.Event.ON_PAUSE -> PermissionReturnWatcher.onAppPaused()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                // The binder permission sweep that used to live here
                // (PermissionHelper.getNextMissingPermission on every resume) is gone:
                // its only consumer, the Settings tab, now derives the highlighted
                // "next missing permission" from the per-permission checks it already
                // collects on its own resume tick — no extra sweep per resume.

                // Never silently swallow a denied notification permission: surface a
                // non-blocking snackbar with a shortcut to the app notification settings.
                LaunchedEffect(notificationDenied.value) {
                    if (!notificationDenied.value) return@LaunchedEffect
                    notificationDenied.value = false
                    val result = snackbarHostState.showSnackbar(
                        message = "Notifications are off — the monitor notification stays hidden.",
                        actionLabel = "Open"
                    )
                    if (result == SnackbarResult.ActionPerformed) openNotificationSettings()
                }

                if (authState == FocusAuthState.SignedOut && !offlineMode) {
                    FocusAuthGate(
                        state = authState,
                        onContinueOffline = {
                            offlineOverride = true
                            lifecycleScope.launch { app.settingsRepository.setOfflineMode(true) }
                        }
                    ) { }
                } else {
                    var currentTab by rememberSaveable { mutableStateOf(NavigationItem.DASHBOARD) }
                    var showDebug by rememberSaveable { mutableStateOf(false) }
                    // Cross-device "New bucket…": a usage row asks to open the merge editor
                    // in the Boundaries picker. Plain state; consumed (cleared) by the
                    // picker as soon as it opens the editor.
                    var pendingMergeTarget by remember { mutableStateOf<PendingMergeTarget?>(null) }
                    val isSubScreen = showDebug ||
                        currentTab == NavigationItem.SETTINGS ||
                        currentTab == NavigationItem.ACCOUNT
                    val goBack = {
                        if (showDebug) showDebug = false else currentTab = NavigationItem.DASHBOARD
                    }
                    // System back from Settings/Account/Debug returns to the Focus tab
                    // instead of exiting the app.
                    BackHandler(enabled = isSubScreen) { goBack() }
                    // One-time self-heal: clamp legacy-inflated bank when today has zero work.
                    LaunchedEffect(Unit) {
                        try { app.creditBankRepository.reconcileBalanceWithFocus() } catch (_: Exception) { }
                    }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        if (isSubScreen) {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = if (showDebug) "Debug data" else currentTab.title,
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = (-0.2).sp
                                        )
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = goBack) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back to Focus"
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background,
                                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 0.dp
                        ) {
                            // Bottom nav keeps Focus + Boundaries + Strict; Settings/Account
                            // are reached via the dashboard header (gear/avatar).
                            NavigationItem.entries.filter {
                                it == NavigationItem.DASHBOARD ||
                                    it == NavigationItem.APPS ||
                                    it == NavigationItem.STRICT
                            }.forEach { item ->
                                val isSelected = currentTab == item
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = { currentTab = item },
                                    icon = {
                                        Icon(
                                            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.title
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                        if (showDebug) {
                            DebugDataScreen(onBack = { showDebug = false })
                        } else {
                        // Bottom-nav tabs switch instantly (direct when): the previous
                        // AnimatedContent crossfade kept outgoing+incoming screens
                        // composed simultaneously, which produced the "views closing /
                        // appearing weirdly" effect and extra composition cost.
                        when (currentTab) {
                            NavigationItem.DASHBOARD -> DashboardScreen(
                                onOpenTickTick = { openTickTick() },
                                onNavigatePermissions = { currentTab = NavigationItem.SETTINGS },
                                onOpenSettings = { currentTab = NavigationItem.SETTINGS },
                                onOpenAccount = { currentTab = NavigationItem.ACCOUNT }
                            )
                            NavigationItem.APPS -> BoundariesScreen(
                                pendingMergeTarget = pendingMergeTarget,
                                onPendingMergeConsumed = { pendingMergeTarget = null },
                            )
                            NavigationItem.STRICT -> StrictModeScreen()
                            NavigationItem.SETTINGS -> SettingsScreen(
                                // highlightKind omitted (defaults null): the screen derives
                                // the next missing permission from its own checks.
                                onOpenDebug = { showDebug = true }
                            )
                            NavigationItem.ACCOUNT -> AccountTab(
                                authState = authState,
                                syncStatusFlow = app.syncManager.status,
                                onSyncNow = { app.syncManager.syncNowAsync(authViewModel) },
                                onSignOut = {
                                    offlineOverride = false
                                    lifecycleScope.launch { app.settingsRepository.setOfflineMode(false) }
                                    authViewModel.signOut()
                                },
                                authViewModel = authViewModel,
                                onOpenMergeInBoundaries = { target ->
                                    pendingMergeTarget = target
                                    currentTab = NavigationItem.APPS
                                },
                            )
                        }
                        }
                        }
                    }
                }

                }

                // Rationale shown before the notification permission is requested, and only
                // once the monitor service actually needs it.
                if (showNotificationRationale.value) {
                    AlertDialog(
                        onDismissRequest = { showNotificationRationale.value = false },
                        title = { Text("Turn on notifications?", fontWeight = FontWeight.SemiBold) },
                        text = {
                            Text(
                                "FocusLock shows an ongoing notification while the monitor runs. " +
                                    "Without it Android may hide or stop the monitor in the background. " +
                                    "You can change this anytime in system settings."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showNotificationRationale.value = false
                                    launchNotificationPermissionRequest()
                                }
                            ) { Text("Continue") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showNotificationRationale.value = false }) { Text("Not now") }
                        },
                        shape = MaterialTheme.shapes.large
                    )
                }
            }
        }
    }

    /**
     * Account tab wrapper: collects sync status inside this composable so a sync-status
     * flip only recomposes the Account content, not the whole Scaffold/current tab.
     * Below the existing account content it adds a lightweight cross-device section
     * (today's usage summary + registered devices), loaded on entry/resume and after
     * a manual Sync Now — never polled.
     */
    @Composable
    private fun AccountTab(
        authState: FocusAuthState,
        syncStatusFlow: Flow<SyncStatus>,
        onSyncNow: () -> Unit,
        onSignOut: () -> Unit,
        authViewModel: AuthViewModel,
        onOpenMergeInBoundaries: (PendingMergeTarget) -> Unit,
    ) {
        val syncStatus by syncStatusFlow.collectAsStateWithLifecycle(initialValue = SyncStatus.Idle)
        val syncText = when (val s = syncStatus) {
            SyncStatus.Idle -> "Auto-sync idle"
            SyncStatus.Syncing -> "Syncing…"
            is SyncStatus.Done -> s.detail
            is SyncStatus.Skipped -> s.reason
            is SyncStatus.Error -> s.message
        }

        // One lightweight client per account tab visit; OkHttp itself is shared process-wide.
        val context = LocalContext.current
        val convexUrl = remember { runCatching { BuildConfig.CONVEX_URL.trim() }.getOrDefault("") }
        val convex = remember(authViewModel, convexUrl) {
            ConvexSyncClient(convexUrl, authViewModel::getConvexToken)
        }
        var refreshTick by remember { mutableIntStateOf(0) }
        var awaitingManualSync by remember { mutableStateOf(false) }

        // The device id can be written asynchronously after first launch (registration), so
        // re-read it on refresh/resume instead of caching a one-time null. produceState keeps
        // the last known value while the new read runs.
        val localDeviceId by produceState<String?>(null, context, refreshTick) {
            value = withContext(Dispatchers.IO) {
                context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_DEVICE_ID, null)
            }
        }

        // Re-load whenever the tab becomes visible again (covers returning from settings).
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshTick++
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        // Refresh once after a user-triggered Sync Now finishes (the status flow "Done").
        LaunchedEffect(syncStatus) {
            if (awaitingManualSync && syncStatus is SyncStatus.Done) {
                awaitingManualSync = false
                refreshTick++
            }
        }

        val crossDeviceState by produceState<CrossDeviceState>(
            CrossDeviceState.Loading,
            refreshTick,
            convexUrl
        ) {
            value = CrossDeviceState.Loading
            value = if (convexUrl.isBlank()) {
                CrossDeviceState.Failed("Sync not configured")
            } else {
                loadCrossDeviceState(convex)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // AccountScreen renders at content height (no internal scroll), so this
            // outer verticalScroll is the single scroller for the whole Account tab.
            AccountScreen(
                authState = authState,
                syncStatus = syncText,
                onSyncNow = {
                    awaitingManualSync = true
                    onSyncNow()
                },
                onSignOut = onSignOut,
            )
            CrossDeviceSection(
                state = crossDeviceState,
                localDeviceId = localDeviceId,
                client = convex.takeIf { convexUrl.isNotBlank() },
                signedIn = authState == FocusAuthState.SignedIn,
                onRetry = { refreshTick++ },
                onNewBucket = onOpenMergeInBoundaries,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            )
        }
    }

    private sealed interface CrossDeviceState {
        data object Loading : CrossDeviceState
        data class Loaded(val usage: UsageSummary?, val devices: List<DeviceInfo>) : CrossDeviceState
        data class Failed(val message: String) : CrossDeviceState
    }

    /** Today-scoped summary + device list. Null usage + empty list means the call failed. */
    private suspend fun loadCrossDeviceState(client: ConvexSyncClient): CrossDeviceState = try {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val usage = client.getUsageSummary(fromDate = today, toDate = today)
        val devices = client.listDevices()
        if (usage == null && devices.isEmpty()) {
            CrossDeviceState.Failed("Couldn't load cross-device data")
        } else {
            CrossDeviceState.Loaded(usage, devices)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        CrossDeviceState.Failed(e.message?.take(80) ?: "Couldn't load cross-device data")
    }

    @Composable
    private fun CrossDeviceSection(
        state: CrossDeviceState,
        localDeviceId: String?,
        client: ConvexSyncClient?,
        signedIn: Boolean,
        onRetry: () -> Unit,
        onNewBucket: (PendingMergeTarget) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(title = "Devices")
            when (state) {
                is CrossDeviceState.Loading -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                is CrossDeviceState.Failed -> Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            state.message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }

                is CrossDeviceState.Loaded -> {
                    CrossDeviceUsageCard(state.usage, state.devices)
                    CrossDeviceListCard(state.devices, localDeviceId)
                }
            }
            // Full cross-device view: range selector + merged buckets + group limits.
            // Fetches independently of the devices card above (its own graceful states).
            // onNewBucket opens the merge editor in the Boundaries picker pre-filled.
            CrossDeviceUsageSection(
                client = client,
                signedIn = signedIn,
                onNewBucket = onNewBucket,
            )
        }
    }

    @Composable
    private fun CrossDeviceUsageCard(usage: UsageSummary?, devices: List<DeviceInfo>) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Cross-device screen time",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Today",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val rows = usage?.devices.orEmpty()
                if (rows.isEmpty()) {
                    Text(
                        "No synced usage yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    rows.forEach { device ->
                        val platform = devices.firstOrNull { it.deviceId == device.deviceId }?.platform
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = platformIcon(platform),
                                contentDescription = platformLabel(platform),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                device.deviceName,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                formatUsageSeconds(device.trackedSeconds),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    if (rows.size > 1) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Total",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                formatUsageSeconds(
                                    usage?.totalTrackedSeconds ?: rows.sumOf { it.trackedSeconds }
                                ),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CrossDeviceListCard(devices: List<DeviceInfo>, localDeviceId: String?) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Registered devices",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (devices.isEmpty()) {
                    Text(
                        "No devices yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    devices.forEachIndexed { index, device ->
                        DeviceRow(
                            device = device,
                            isThisDevice = localDeviceId != null && device.deviceId == localDeviceId
                        )
                        if (index != devices.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceRow(device: DeviceInfo, isThisDevice: Boolean) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = platformIcon(device.platform),
                contentDescription = platformLabel(device.platform),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        device.name,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isThisDevice) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                "This device",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                val seen = if (device.lastSeen > 0L) relativeTime(device.lastSeen) else "never seen"
                Text(
                    "${platformLabel(device.platform)} · $seen",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val (statusColor, statusLabel) = when (device.trackingStatus) {
                "active" -> MaterialTheme.colorScheme.primary to "Tracking"
                "paused" -> MaterialTheme.colorScheme.tertiary to "Paused"
                "permission_required" -> MaterialTheme.colorScheme.error to "Permission"
                else -> MaterialTheme.colorScheme.error to "Error"
            }
            Text(
                statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor
            )
        }
    }

    private fun platformLabel(platform: String?): String = when (platform?.lowercase()) {
        "android" -> "Android"
        "windows" -> "Windows"
        "browser" -> "Browser"
        else -> "Device"
    }

    private fun platformIcon(platform: String?): ImageVector = when (platform?.lowercase()) {
        "windows" -> Icons.Rounded.Computer
        "browser" -> Icons.Rounded.Language
        else -> Icons.Rounded.PhoneAndroid
    }

    /** Local copy of DashboardScreen's tiny formatter (that one is file-private). */
    private fun relativeTime(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val seconds = ((nowMillis - timestampMillis) / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }

    private fun startMonitorServiceIfPermitted() {
        if (monitorServiceStarted) return
        // Resumes repeat while permissions are missing; skip a sweep that just ran so a
        // quick pause/resume cycle does not re-hit the AccessibilityManager/AppOps binders.
        val now = System.currentTimeMillis()
        if (now - lastMonitorPermissionCheckMs < PERMISSION_CHECK_THROTTLE_MS) return
        lastMonitorPermissionCheckMs = now

        // The permission checks are binder IPC: run them off the main thread, then come
        // back to the main dispatcher to start the foreground service if both are granted.
        lifecycleScope.launch {
            val (accessibility, usageAccess) = withContext(Dispatchers.IO) {
                val accessibility = try {
                    PermissionHelper.isAccessibilityServiceEnabled(this@MainActivity)
                } catch (e: Exception) {
                    Log.w(TAG, "Accessibility check failed", e)
                    false
                }
                val usageAccess = try {
                    PermissionHelper.isUsageAccessGranted(this@MainActivity)
                } catch (e: Exception) {
                    Log.w(TAG, "Usage access check failed", e)
                    false
                }
                accessibility to usageAccess
            }
            if (!accessibility || !usageAccess) {
                Log.i(
                    TAG,
                    "Monitor service not started — accessibility=$accessibility, usageAccess=$usageAccess"
                )
                return@launch
            }
            if (monitorServiceStarted) return@launch
            try {
                val intent = Intent(this@MainActivity, AppMonitorForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                monitorServiceStarted = true
                Log.i(TAG, "Monitor service started")
                maybeRequestNotificationPermission()
            } catch (e: Exception) {
                Log.w(TAG, "Monitor service start failed", e)
            }
        }
    }

    /** Requests POST_NOTIFICATIONS at most twice, with a rationale first. */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (notificationPermissionAsked || notificationPermissionBlockedForever) return
        if (permissionPrefs.getInt(KEY_NOTIFICATION_DENIALS, 0) >= 2) return

        notificationPermissionAsked = true
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            showNotificationRationale.value = true
        } else {
            launchNotificationPermissionRequest()
        }
    }

    private fun launchNotificationPermissionRequest() {
        try {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            Log.w(TAG, "Notification permission request failed", e)
        }
    }

    private fun openNotificationSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open notification settings", e)
        }
    }

    private fun openTickTick() {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.ticktick.task")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            try {
                val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.ticktick.task")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(storeIntent)
            } catch (e: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ticktick.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val PERM_PREFS = "focuslock_permissions"
        const val KEY_NOTIFICATION_DENIALS = "notification_denials"

        /** Minimum gap between binder permission sweeps in [startMonitorServiceIfPermitted]. */
        const val PERMISSION_CHECK_THROTTLE_MS = 2_000L

        /** Shared with FocusSyncManager.getOrCreateDeviceId(); read-only here. */
        const val DEVICE_PREFS = "focuslock_device"
        const val KEY_DEVICE_ID = "device_id"
    }
}
