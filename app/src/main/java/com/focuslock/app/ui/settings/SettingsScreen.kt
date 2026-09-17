package com.focuslock.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.focuslock.app.ui.components.rememberDecorativePulse
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.data.backup.ConfigBackupManager
import com.focuslock.app.data.repository.BlockSchedule
import com.focuslock.app.data.repository.FrogRepository
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.ui.dashboard.home.FocusHomeStyle
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.service.TickTickAuthConfig
import com.focuslock.app.service.TickTickOAuthLoopbackServer
import com.focuslock.app.ui.permissions.PermissionHelper
import com.focuslock.app.ui.permissions.PermissionKind
import com.focuslock.app.work.DailyReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun SettingsScreen(highlightKind: PermissionKind? = null, onOpenDebug: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val settings = FocusLockApplication.instance.settingsRepository
    val bank = FocusLockApplication.instance.creditBankRepository
    val authViewModel: AuthViewModel = viewModel()

    // Only flows still needed by the screen root are collected here. Ratio sliders, daily
    // goals and block schedules read their own flows inside their card composables and are
    // collected with lifecycle awareness, so DataStore writes stay scoped to those cards.
    val tickTickToken by settings.tickTickTokenFlow.collectAsStateWithLifecycle(initialValue = "")
    val tickTickUserName by settings.tickTickUserNameFlow.collectAsStateWithLifecycle(initialValue = "")
    val tickTickClientId by settings.tickTickClientIdFlow.collectAsStateWithLifecycle(initialValue = "")
    val tickTickClientSecret by settings.tickTickClientSecretFlow.collectAsStateWithLifecycle(initialValue = "")
    val notificationEnabled by settings.tickTickNotificationEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val boundariesLock by settings.boundariesLockFlow.collectAsStateWithLifecycle(initialValue = false)
    val focusHomeStyleKey by settings.focusHomeStyleFlow.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_FOCUS_HOME_STYLE
    )
    var showHomeStyleDialog by remember { mutableStateOf(false) }

    var showOAuthCredentialsDialog by remember { mutableStateOf(false) }
    var showTokenField by remember { mutableStateOf(false) }
    var showManualCallback by remember { mutableStateOf(false) }
    var editManualCallback by remember { mutableStateOf("") }
    var isCompletingManual by remember { mutableStateOf(false) }
    var editClientId by remember(tickTickClientId) { mutableStateOf(tickTickClientId) }
    var editClientSecret by remember(tickTickClientSecret) { mutableStateOf(tickTickClientSecret) }
    var editPersonalToken by remember(tickTickToken) { mutableStateOf(tickTickToken) }
    var isSyncing by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var showDisconnectTickTickDialog by remember { mutableStateOf(false) }
    var showDeactivateAdminDialog by remember { mutableStateOf(false) }

    // Declared before the login helpers so they can bump it after a successful connect.
    var refreshTick by remember { mutableIntStateOf(0) }

    var isOpeningLogin by remember { mutableStateOf(false) }
    fun openLogin(saveOverride: Boolean = false) {
        if (isOpeningLogin) return
        isOpeningLogin = true
        scope.launch {
            try {
                if (saveOverride) settings.setTickTickOAuthCredentials(editClientId, editClientSecret)
                if (!TickTickAuthConfig.hasUsableCredentials(settings)) {
                    syncMessage = "Enter both a client ID and client secret to connect."
                    return@launch
                }
                val state = settings.beginTickTickLogin()

                // Bind the loopback server FIRST so the authorize URL's redirect_uri
                // matches the port we actually listen on (port-fallback fix, item 8).
                // bind() returns quickly; awaitCode (started below) does the waiting.
                val boundPort = TickTickOAuthLoopbackServer.bind()
                if (boundPort == null) {
                    syncMessage = "Could not open a local port for the login redirect. Close apps using port 8080–8085 and try again, or paste the redirect link manually."
                    showManualCallback = true
                    return@launch
                }
                val port = boundPort

                // Intercept the 127.0.0.1 redirect in the background.
                scope.launch {
                    TickTickOAuthLoopbackServer.awaitCode { code, stateParam ->
                        scope.launch {
                            try {
                                val stateOk = if (stateParam != null) {
                                    settings.consumeTickTickState(stateParam)
                                } else {
                                    settings.consumePendingTickTickLogin()
                                }
                                val clientId = TickTickAuthConfig.effectiveClientId(settings)
                                val clientSecret = TickTickAuthConfig.effectiveClientSecret(settings)
                                if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
                                    val api = TickTickApiClient()
                                    // Token exchange redirect_uri must match the authorize one exactly.
                                    val tokenResp = api.exchangeCodeForToken(clientId, clientSecret, code, port)
                                    if (tokenResp != null && tokenResp.accessToken.isNotBlank()) {
                                        val profile = api.fetchUserProfile(tokenResp.accessToken)
                                        val name = profile?.nickname ?: profile?.username ?: profile?.email ?: "TickTick User"
                                        settings.setTickTickAuthSuccess(
                                            tokenResp.accessToken,
                                            name,
                                            refreshToken = tokenResp.refreshToken,
                                            expiresInSec = tokenResp.expiresIn ?: 0L
                                        )
                                        syncMessage = "Connected to TickTick as $name!"
                                        showManualCallback = false
                                        refreshTick++
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("SettingsScreen", "Loopback exchange error", e)
                            }
                        }
                    }
                }

                val url = TickTickApiClient.buildAuthorizeUrl(TickTickAuthConfig.effectiveClientId(settings), state, port)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                showOAuthCredentialsDialog = false
                showManualCallback = true
                syncMessage = "Browser opened. Approve TickTick — FocusLock catches the redirect automatically, or paste the link below."
            } catch (_: android.content.ActivityNotFoundException) {
                syncMessage = "Install a browser to connect to TickTick."
            } finally {
                isOpeningLogin = false
            }
        }
    }

    fun completeManualLogin() {
        if (isCompletingManual) return
        isCompletingManual = true
        scope.launch {
            try {
                val parsed = TickTickApiClient.parseManualCallback(editManualCallback)
                if (parsed == null) {
                    syncMessage = "Paste the full redirect address (…?code=…) or the code itself."
                    return@launch
                }
                val (code, state) = parsed
                val stateOk = if (state != null) {
                    settings.consumeTickTickState(state)
                } else {
                    settings.consumePendingTickTickLogin()
                }
                if (!stateOk) {
                    syncMessage = "Login expired or could not be verified. Tap Connect again, then paste the fresh address."
                    return@launch
                }
                val clientId = TickTickAuthConfig.effectiveClientId(settings)
                val clientSecret = TickTickAuthConfig.effectiveClientSecret(settings)
                if (clientId.isBlank() || clientSecret.isBlank()) {
                    syncMessage = "Login not configured on this build."
                    return@launch
                }
                val api = TickTickApiClient()
                // Match the port the authorize URL used (8080 when the canonical port
                // bound; the loopback server's last bound port otherwise).
                val tokenResponse = api.exchangeCodeForToken(
                    clientId, clientSecret, code, TickTickOAuthLoopbackServer.boundPort
                )
                if (tokenResponse != null && tokenResponse.accessToken.isNotBlank()) {
                    val profile = api.fetchUserProfile(tokenResponse.accessToken)
                    val name = profile?.nickname ?: profile?.username ?: profile?.email ?: "TickTick User"
                    settings.setTickTickAuthSuccess(
                        tokenResponse.accessToken,
                        name,
                        refreshToken = tokenResponse.refreshToken,
                        expiresInSec = tokenResponse.expiresIn
                    )
                    editManualCallback = ""
                    showManualCallback = false
                    syncMessage = "Connected as $name!"
                    Toast.makeText(context, "Connected as $name!", Toast.LENGTH_LONG).show()
                    refreshTick++
                } else {
                    syncMessage = "That code didn't work — it may be used or expired. Tap Connect again for a fresh one."
                }
            } finally {
                isCompletingManual = false
            }
        }
    }

    // Refresh permission + sync state on resume (fixes stale checklist)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Binder-backed permission checks are computed off the main thread, only when the resume
    // tick changes, so composition never blocks on PackageManager/AppOps IPC. Null = not
    // checked yet; rows treat null the same as not-granted (Grant button), exactly like
    // the old pre-check frame.
    val isAccessibilityOn by produceState<Boolean?>(initialValue = null, key1 = refreshTick) {
        value = withContext(Dispatchers.IO) { PermissionHelper.isAccessibilityServiceEnabled(context) }
    }
    val isUsageOn by produceState<Boolean?>(initialValue = null, key1 = refreshTick) {
        value = withContext(Dispatchers.IO) { PermissionHelper.isUsageAccessGranted(context) }
    }
    val isOverlayOn by produceState<Boolean?>(initialValue = null, key1 = refreshTick) {
        value = withContext(Dispatchers.IO) { PermissionHelper.isOverlayGranted(context) }
    }
    val isNotifOn by produceState<Boolean?>(initialValue = null, key1 = refreshTick) {
        value = withContext(Dispatchers.IO) { PermissionHelper.isNotificationListenerGranted(context) }
    }
    val isBatteryIgnored by produceState<Boolean?>(initialValue = null, key1 = refreshTick) {
        value = withContext(Dispatchers.IO) { PermissionHelper.isBatteryOptimizationIgnored(context) }
    }
    val isDeviceAdminOn by produceState<Boolean?>(initialValue = null, key1 = refreshTick) {
        value = withContext(Dispatchers.IO) { PermissionHelper.isDeviceAdminActive(context) }
    }

    // Auto-scroll to the highlighted (next missing) permission row. The first missing
    // permission is derived from the per-permission booleans already collected above —
    // no extra getNextMissingPermission binder sweep per resume. POST_NOTIFICATIONS is
    // the only kind without a collected boolean and its check is in-process (no IPC).
    // Order matches PermissionHelper.getMissingPermissions (PermissionKind.entries).
    val scrollState = rememberScrollState()
    val resolvedHighlight: PermissionKind? = if (
        isAccessibilityOn == null || isUsageOn == null || isOverlayOn == null ||
        isNotifOn == null || isBatteryIgnored == null || isDeviceAdminOn == null
    ) {
        null // wait for the real checks (same behavior as the old off-main sweep)
    } else when {
        isAccessibilityOn != true -> PermissionKind.ACCESSIBILITY
        isUsageOn != true -> PermissionKind.USAGE
        isOverlayOn != true -> PermissionKind.OVERLAY
        isNotifOn != true -> PermissionKind.NOTIFICATION_LISTENER
        isBatteryIgnored != true -> PermissionKind.BATTERY
        isDeviceAdminOn != true -> PermissionKind.DEVICE_ADMIN
        !PermissionHelper.isPostNotificationsGranted(context) -> PermissionKind.POST_NOTIFICATIONS
        else -> null
    }
    val effectiveHighlight = highlightKind ?: resolvedHighlight
    LaunchedEffect(effectiveHighlight) {
        if (effectiveHighlight == PermissionKind.BATTERY || effectiveHighlight == PermissionKind.DEVICE_ADMIN) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // One formatter instance shared by both sync paths instead of allocating per sync.
    val syncDateFormatter = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    var lastSyncText by remember(refreshTick) { mutableStateOf("") }
    LaunchedEffect(refreshTick) {
        val ts = bank.getLastSyncTimestamp()
        lastSyncText = if (ts > 0) {
            "Last synced ${syncDateFormatter.format(Date(ts))}"
        } else "Never synced"
    }

    val isTickTickConnected = tickTickToken.isNotBlank()

    suspend fun doSync(showToast: Boolean = true, tokenOverride: String? = null) {
        val effectiveToken = tokenOverride ?: tickTickToken
        if (effectiveToken.isBlank()) {
            syncMessage = "Connect TickTick first — or use Focus Timer / Log Work on the Focus tab (works offline)."
            if (showToast) Toast.makeText(context, "No TickTick token. Use built-in Log Work instead.", Toast.LENGTH_LONG).show()
            return
        }
        isSyncing = true
        try {
            val api = TickTickApiClient()
            // Auto-refresh expired OAuth tokens; personal tokens pass through unchanged.
            val validToken = TickTickAuthConfig.getValidAccessToken(settings, api)
            if (validToken.isNullOrBlank()) {
                syncMessage = "TickTick session expired — reconnect with TickTick login below."
                if (showToast) Toast.makeText(context, syncMessage, Toast.LENGTH_LONG).show()
                return
            }
            val records = api.fetchCompletedTasksToday(validToken)
            val ratio = settings.workRatioFlow.first()
            val bonus = settings.taskBonusFlow.first()
            val (newCount, earned) = bank.recordWorkCreditsDeduped(records, ratio, bonus)
            val ts = bank.getLastSyncTimestamp()
            lastSyncText = if (ts > 0) {
                "Last synced ${syncDateFormatter.format(Date(ts))}"
            } else lastSyncText
            syncMessage = if (records.isEmpty()) {
                "No focus sessions — TickTick tasks don't count as focus"
            } else if (newCount == 0) {
                "Already up to date — ${records.size} completed task(s) already credited."
            } else {
                "Synced! $newCount new task(s) → +$earned min leisure."
            }
            if (showToast) Toast.makeText(context, syncMessage, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            syncMessage = "Sync failed: ${e.message}. Check connection or use Log Work fallback."
            if (showToast) Toast.makeText(context, syncMessage, Toast.LENGTH_LONG).show()
        } finally {
            isSyncing = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )
        Text(
            text = "Work first, scroll later. TickTick is optional — the built-in Focus Timer always works.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 1. Productivity Conversion Rules Card — the ratio/bonus controls live in their own
        // composable so slider drag frames don't recompose the rest of this screen.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                RatioSliderCard(authViewModel)

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                // Boundaries Lock
                if (boundariesLock) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Boundaries list is locked — removals disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Boundaries Lock",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "When ON, blocked apps/websites can't be removed from Boundaries",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = boundariesLock,
                        onCheckedChange = { checked ->
                            scope.launch { settings.setBoundariesLock(checked) }
                        }
                    )
                }
            }
        }

        // 2. Daily Goals Card — collects its own flows and draft state inside.
        DailyGoalsCard()

        // 2b. Appearance Card — Focus home style switcher (which front page the
        // Focus tab shows). Owns only the dialog flag; the current key is collected above.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Appearance",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                val currentStyle = FocusHomeStyle.entries.firstOrNull { it.key == focusHomeStyleKey }
                    ?: FocusHomeStyle.RINGS
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { showHomeStyleDialog = true })
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Focus home style",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${currentStyle.title} — ${currentStyle.blurb}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 2c. Eat the Frog Card — hard-lock toggle, required focus minutes, wake hour.
        FrogSettingsCard()

        // 3. Block Schedules Card — owns its flow, editor state and dialogs.
        BlockSchedulesCard()

        // 4. Data Card — JSON export/import of boundaries, limits, schedules, goals.
        ConfigBackupCard(snackbarHostState)

        // 5. Daily Reminder Card — schedules a WorkManager notification.
        DailyReminderCard()

        // 6. TickTick Integration Card — simplified: token-first, OAuth advanced
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TickTick Account & Sync",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = lastSyncText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isTickTickConnected) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Connected Account",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = if (tickTickUserName.isNotBlank()) tickTickUserName else "TickTick User",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }

                            FilledTonalButton(
                                onClick = { showDisconnectTickTickDialog = true },
                                shape = MaterialTheme.shapes.medium,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Disconnect", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Button(
                        onClick = { scope.launch { doSync(true) } },
                        enabled = !isSyncing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isSyncing) "Syncing..." else "Sync Tasks Now")
                    }

                    syncMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    // Browser OAuth is primary (one tap, no copy-paste). Personal token is fallback.
                    val canOAuth = TickTickAuthConfig.hasBakedCredentials ||
                        (tickTickClientId.isNotBlank() && tickTickClientSecret.isNotBlank())

                    Text(
                        "Connect with your TickTick account — login happens in the browser, no copy-paste needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = { openLogin() },
                        enabled = canOAuth && !isOpeningLogin,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect with TickTick")
                    }
                    // TickTick only allows http(s) redirects, so the browser lands on a
                    // loopback address that can't load on-device. Paste it back here.
                    if (showManualCallback) {
                        Text(
                            "After approving in the browser you'll land on http://127.0.0.1:8080/?code=… which can't load on the phone — copy that full address and paste it here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = editManualCallback,
                            onValueChange = { editManualCallback = it.trim() },
                            label = { Text("Pasted redirect address or code") },
                            placeholder = { Text("http://127.0.0.1:8080/?code=…&state=…") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { completeManualLogin() },
                            enabled = editManualCallback.isNotBlank() && !isCompletingManual,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isCompletingManual) "Connecting..." else "Complete connection")
                        }
                    } else {
                        TextButton(onClick = { showManualCallback = true }) {
                            Text("Already approved? Paste the redirect address", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (!canOAuth) {
                        Text(
                            "Login not configured on this build — paste a personal token below instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (!showTokenField) {
                        TextButton(onClick = { showTokenField = true }) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Or paste a personal token (tp_...) instead")
                        }
                        TextButton(onClick = { showOAuthCredentialsDialog = true }) {
                            Text("Advanced: override OAuth credentials", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        OutlinedTextField(
                            value = editPersonalToken,
                            onValueChange = { editPersonalToken = it.trim() },
                            label = { Text("Personal Access Token (tp_...)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        isVerifying = true
                                        val token = editPersonalToken.trim()
                                        if (token.isBlank()) {
                                            Toast.makeText(context, "Paste your token first", Toast.LENGTH_SHORT).show()
                                            isVerifying = false
                                            return@launch
                                        }
                                        val valid = TickTickApiClient().verifyToken(token)
                                        if (valid) {
                                            val profile = TickTickApiClient().fetchUserProfile(token)
                                            val name = profile?.nickname ?: profile?.username ?: "TickTick User"
                                            settings.setTickTickAuthSuccess(token, name)
                                            Toast.makeText(context, "Connected as $name!", Toast.LENGTH_LONG).show()
                                            showTokenField = false
                                            refreshTick++
                                            // Pass the fresh token explicitly: the collected flow state is
                                            // still stale on this frame and would toast "Connect TickTick first".
                                            doSync(showToast = false, tokenOverride = token)
                                        } else {
                                            Toast.makeText(context, "Token invalid — check it starts with tp_ and try again.", Toast.LENGTH_LONG).show()
                                        }
                                        isVerifying = false
                                    }
                                },
                                enabled = !isVerifying,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isVerifying) "Verifying..." else "Save & Verify")
                            }
                            TextButton(onClick = {
                                showTokenField = false
                                editPersonalToken = tickTickToken
                            }) { Text("Cancel") }
                        }
                        TextButton(onClick = { showOAuthCredentialsDialog = true }) {
                            Text("Or configure OAuth instead", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    syncMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "On-Device Notification Sync",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Catches Pomodoros & tasks as they finish. Needs Notification access below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = notificationEnabled,
                        onCheckedChange = { scope.launch { settings.setTickTickNotificationEnabled(it) } },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        // 5. System Permissions Checklist
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "System Protection Status",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                PixelPermissionItem(
                    title = "Accessibility Service",
                    subtitle = "Intercepts blocked apps & websites",
                    isGranted = isAccessibilityOn == true,
                    highlighted = effectiveHighlight == PermissionKind.ACCESSIBILITY,
                    onClick = { PermissionHelper.openAccessibilitySettings(context) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                PixelPermissionItem(
                    title = "Usage Access",
                    subtitle = "Powers screen-time dashboard accuracy",
                    isGranted = isUsageOn == true,
                    highlighted = effectiveHighlight == PermissionKind.USAGE,
                    onClick = { PermissionHelper.openUsageAccessSettings(context) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                PixelPermissionItem(
                    title = "Notification Listener",
                    subtitle = "Captures TickTick Pomodoros automatically",
                    isGranted = isNotifOn == true,
                    highlighted = effectiveHighlight == PermissionKind.NOTIFICATION_LISTENER,
                    onClick = { PermissionHelper.openNotificationListenerSettings(context) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                PixelPermissionItem(
                    title = "Display Over Other Apps",
                    subtitle = "Shows fullscreen lockout over target apps",
                    isGranted = isOverlayOn == true,
                    highlighted = effectiveHighlight == PermissionKind.OVERLAY,
                    onClick = { PermissionHelper.openOverlaySettings(context) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                PixelPermissionItem(
                    title = "Ignore Battery Optimizations",
                    subtitle = "Keeps protection alive in background",
                    isGranted = isBatteryIgnored == true,
                    highlighted = effectiveHighlight == PermissionKind.BATTERY,
                    onClick = { PermissionHelper.openBatteryOptimizationSettings(context) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                PixelPermissionItem(
                    title = "Prevent Uninstall (Device Admin)",
                    subtitle = if (isDeviceAdminOn == true) "Protected against impulsive uninstallation" else "Blocks impulsive uninstalls during a binge",
                    isGranted = isDeviceAdminOn == true,
                    highlighted = effectiveHighlight == PermissionKind.DEVICE_ADMIN,
                    onClick = {
                        if (isDeviceAdminOn != true) {
                            PermissionHelper.openDeviceAdminSettings(context)
                        }
                    }
                )
                if (isDeviceAdminOn == true) {
                    TextButton(
                        onClick = { showDeactivateAdminDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Deactivate admin (allow uninstall)",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // Debug entry (totals, records, sync state)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDebug)
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Debug data",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Totals, records, TickTick + sync state",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "›",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    if (showHomeStyleDialog) {
        AlertDialog(
            onDismissRequest = { showHomeStyleDialog = false },
            title = {
                Text(
                    "Focus home style",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FocusHomeStyle.entries.forEach { style ->
                        val selected = style.key == focusHomeStyleKey
                        fun pick() {
                            scope.launch { settings.setFocusHomeStyle(style.key) }
                            showHomeStyleDialog = false
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = ::pick)
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = ::pick
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = style.title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = style.blurb,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHomeStyleDialog = false }) { Text("Done") }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    if (showOAuthCredentialsDialog) {        AlertDialog(
            onDismissRequest = { showOAuthCredentialsDialog = false },
            title = {
                Text(
                    "TickTick OAuth Override (Advanced)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This build already has TickTick login baked in — you don't need to touch this. Only override if you run your own TickTick OAuth app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "If TickTick shows \"at least one redirect_url must be registered\", open https://developer.ticktick.com/manage → your app → OAuth redirect URL, enter exactly (TickTick only accepts http(s)) and Save:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        TickTickApiClient.REDIRECT_URI,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Character-for-character, including the trailing slash. Save in the portal, then retry Connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val effectiveIdPreview =
                        if (tickTickClientId.isNotBlank() && tickTickClientSecret.isNotBlank()) tickTickClientId.trim()
                        else TickTickAuthConfig.bakedClientId
                    Text(
                        "This device authorizes as client ID:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        effectiveIdPreview.ifBlank { "(none configured — rebuild with local.properties or paste an override below)" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "That ID must belong to the SAME TickTick app where you saved the redirect URL. If the portal shows a different client ID, either rebuild with its credentials or paste its ID + secret below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = editClientId,
                        onValueChange = { editClientId = it },
                        label = { Text("Client ID") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editClientSecret,
                        onValueChange = { editClientSecret = it },
                        label = { Text("Client Secret") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { openLogin(saveOverride = true) },
                        enabled = editClientId.isNotBlank() && editClientSecret.isNotBlank() && !isOpeningLogin,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open TickTick OAuth Login")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            settings.setTickTickOAuthCredentials(editClientId, editClientSecret)
                            showOAuthCredentialsDialog = false
                            Toast.makeText(context, "OAuth credentials saved", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = MaterialTheme.shapes.medium
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showOAuthCredentialsDialog = false }) { Text("Close") }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    if (showDisconnectTickTickDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectTickTickDialog = false },
            title = { Text("Disconnect TickTick?") },
            text = { Text("FocusLock will stop syncing your TickTick tasks until you reconnect.") },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectTickTickDialog = false
                    scope.launch {
                        settings.clearTickTickAuth()
                        syncMessage = null
                        Toast.makeText(context, "TickTick disconnected", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectTickTickDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeactivateAdminDialog) {
        AlertDialog(
            onDismissRequest = { showDeactivateAdminDialog = false },
            title = { Text("Deactivate device admin?") },
            text = { Text("This allows FocusLock to be uninstalled without your confirmation.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeactivateAdminDialog = false
                    PermissionHelper.disableDeviceAdmin(context)
                    refreshTick++
                    Toast.makeText(context, "Uninstall protection disabled", Toast.LENGTH_SHORT).show()
                }) { Text("Deactivate", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeactivateAdminDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Work-to-scroll ratio + completed-task bonus sliders. Owns its own DataStore collection
 * and draft state so drag frames only recompose this card, not the whole Settings tree.
 */
@Composable
private fun RatioSliderCard(authViewModel: AuthViewModel) {
    val settings = FocusLockApplication.instance.settingsRepository
    val scope = rememberCoroutineScope()
    val workRatio by settings.workRatioFlow.collectAsStateWithLifecycle(initialValue = 4)
    val taskBonus by settings.taskBonusFlow.collectAsStateWithLifecycle(initialValue = 5)
    var workRatioDraft by remember(workRatio) { mutableFloatStateOf(workRatio.toFloat()) }
    var taskBonusDraft by remember(taskBonus) { mutableFloatStateOf(taskBonus.toFloat()) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Work-to-Scroll Ratio",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Current Ratio: $workRatio to 1",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "60 min work = ${60 / workRatio.coerceAtLeast(1)} min screen time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Slider(
            value = workRatioDraft,
            onValueChange = { workRatioDraft = it },
            onValueChangeFinished = {
                val value = workRatioDraft.toInt()
                scope.launch {
                    settings.setWorkRatio(value)
                    FocusLockApplication.instance.syncManager.syncNowAsync(authViewModel)
                }
            },
            valueRange = 1f..10f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

        Text(
            text = "Completed Task Bonus",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "+$taskBonus extra minutes awarded for every TickTick task checked off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Slider(
            value = taskBonusDraft,
            onValueChange = { taskBonusDraft = it },
            onValueChangeFinished = {
                val value = taskBonusDraft.toInt()
                scope.launch {
                    settings.setTaskBonus(value)
                    FocusLockApplication.instance.syncManager.syncNowAsync(authViewModel)
                }
            },
            valueRange = 0f..20f,
            steps = 19,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.tertiary,
                activeTrackColor = MaterialTheme.colorScheme.tertiary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )
    }
}

/** Daily goals card. Owns its flows and draft strings so typing stays scoped to the card. */
@Composable
private fun DailyGoalsCard() {
    val settings = FocusLockApplication.instance.settingsRepository
    val scope = rememberCoroutineScope()
    val focusGoalMinutes by settings.focusGoalMinutesFlow.collectAsStateWithLifecycle(initialValue = 120)
    val dailyTasksGoal by settings.dailyTasksGoalFlow.collectAsStateWithLifecycle(initialValue = 7)
    var editFocusGoal by remember(focusGoalMinutes) { mutableStateOf(focusGoalMinutes.toString()) }
    var editTasksGoal by remember(dailyTasksGoal) { mutableStateOf(dailyTasksGoal.toString()) }

    fun commitFocusGoal() {
        val parsed = editFocusGoal.toIntOrNull()
        val value = (parsed ?: focusGoalMinutes)
            .coerceIn(SettingsRepository.MIN_FOCUS_GOAL_MINUTES, SettingsRepository.MAX_FOCUS_GOAL_MINUTES)
        editFocusGoal = value.toString()
        scope.launch { settings.setFocusGoalMinutes(value) }
    }

    fun commitTasksGoal() {
        val parsed = editTasksGoal.toIntOrNull()
        val value = (parsed ?: dailyTasksGoal)
            .coerceIn(SettingsRepository.MIN_DAILY_TASKS_GOAL, SettingsRepository.MAX_DAILY_TASKS_GOAL)
        editTasksGoal = value.toString()
        scope.launch { settings.setDailyTasksGoal(value) }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Daily Goals",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            OutlinedTextField(
                value = editFocusGoal,
                onValueChange = { input -> editFocusGoal = input.filter { it.isDigit() }.take(4) },
                label = { Text("Daily focus goal (minutes)") },
                supportingText = { Text("Target for the Focus ring on the dashboard") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitFocusGoal() }),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) commitFocusGoal() }
            )

            OutlinedTextField(
                value = editTasksGoal,
                onValueChange = { input -> editTasksGoal = input.filter { it.isDigit() }.take(3) },
                label = { Text("Daily tasks goal") },
                supportingText = { Text("Completed tasks needed to fill the Tasks ring") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitTasksGoal() }),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) commitTasksGoal() }
            )
        }
    }
}

/** Saves the schedule editor's day set as a compact CSV string across rotation/process death. */
private val ScheduleDaysSaver: Saver<Set<Int>, String> = Saver(
    save = { days -> days.sorted().joinToString(",") },
    restore = { raw -> raw.split(',').mapNotNull { it.toIntOrNull() }.toSet() }
)

/** Block schedules card. Owns schedule collection, editor state and confirm dialogs. */
@Composable
private fun BlockSchedulesCard() {
    val scheduleRepo = FocusLockApplication.instance.blockSchedulesRepository
    val scope = rememberCoroutineScope()
    val blockSchedules by scheduleRepo.schedulesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingScheduleId by rememberSaveable { mutableStateOf<String?>(null) }
    var labelInput by rememberSaveable { mutableStateOf("") }
    var daysInput by rememberSaveable(stateSaver = ScheduleDaysSaver) { mutableStateOf<Set<Int>>(emptySet()) }
    var startInput by rememberSaveable { mutableStateOf(9 * 60) }
    var endInput by rememberSaveable { mutableStateOf(17 * 60) }
    var enabledInput by rememberSaveable { mutableStateOf(true) }
    var editorError by rememberSaveable { mutableStateOf<String?>(null) }
    var timePickerTarget by remember { mutableStateOf<ScheduleTimeField?>(null) }
    var pendingDelete by remember { mutableStateOf<BlockSchedule?>(null) }

    fun beginAddSchedule() {
        editingScheduleId = null
        labelInput = ""
        daysInput = emptySet()
        startInput = 9 * 60
        endInput = 17 * 60
        enabledInput = true
        editorError = null
        showEditor = true
    }

    fun beginEditSchedule(schedule: BlockSchedule) {
        editingScheduleId = schedule.id
        labelInput = schedule.label
        daysInput = schedule.daysOfWeek
        startInput = schedule.startMinuteOfDay
        endInput = schedule.endMinuteOfDay
        enabledInput = schedule.enabled
        editorError = null
        showEditor = true
    }

    fun toggleScheduleDay(day: Int) {
        daysInput = if (day in daysInput) daysInput - day else daysInput + day
    }

    fun saveSchedule() {
        val label = labelInput.trim().ifBlank { "Schedule" }
        if (daysInput.isEmpty()) {
            editorError = "Pick at least one day"
            return
        }
        if (startInput == endInput) {
            editorError = "Start and end time can't be the same"
            return
        }
        val schedule = BlockSchedule(
            id = editingScheduleId ?: UUID.randomUUID().toString(),
            label = label,
            daysOfWeek = daysInput,
            startMinuteOfDay = startInput,
            endMinuteOfDay = endInput,
            enabled = enabledInput
        )
        editorError = null
        scope.launch {
            try {
                scheduleRepo.upsert(schedule)
                showEditor = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                editorError = "Could not save schedule: ${e.message?.take(120) ?: "unknown error"}"
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Block Schedules",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Recurring windows when blocked apps stay locked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (blockSchedules.isEmpty()) {
                Text(
                    text = "No schedules yet — add one to block apps during set hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                blockSchedules.forEach { schedule ->
                    BlockScheduleRow(
                        schedule = schedule,
                        onEdit = { beginEditSchedule(schedule) },
                        onToggle = { checked ->
                            scope.launch { scheduleRepo.upsert(schedule.copy(enabled = checked)) }
                        },
                        onDeleteRequest = { pendingDelete = schedule }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

            OutlinedButton(
                onClick = { beginAddSchedule() },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add schedule")
            }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = {
                Text(
                    if (editingScheduleId == null) "Add block schedule" else "Edit block schedule",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it.take(40) },
                        label = { Text("Label") },
                        placeholder = { Text("Schedule") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Days",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (1..4).forEach { day ->
                            ScheduleDayChip(
                                day = day,
                                selected = day in daysInput,
                                onToggle = { toggleScheduleDay(day) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (5..7).forEach { day ->
                            ScheduleDayChip(
                                day = day,
                                selected = day in daysInput,
                                onToggle = { toggleScheduleDay(day) }
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Text(
                        "Time window",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { timePickerTarget = ScheduleTimeField.START },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f)
                        ) { Text("Start ${formatMinuteOfDay(startInput)}") }
                        OutlinedButton(
                            onClick = { timePickerTarget = ScheduleTimeField.END },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f)
                        ) { Text("End ${formatMinuteOfDay(endInput)}") }
                    }
                    if (endInput < startInput) {
                        Text(
                            "Overnight window — blocks until ${formatMinuteOfDay(endInput)} the next day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Enabled",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = enabledInput,
                            onCheckedChange = { enabledInput = it }
                        )
                    }
                    editorError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { saveSchedule() }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) { Text("Cancel") }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    pendingDelete?.let { schedule ->
        val scheduleLabel = schedule.label.ifBlank { "Schedule" }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete schedule?") },
            text = { Text("Remove \"$scheduleLabel\" from your block schedules?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { scheduleRepo.delete(schedule.id) }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    timePickerTarget?.let { target ->
        ScheduleTimePickerDialog(
            title = if (target == ScheduleTimeField.START) "Start time" else "End time",
            initialMinutes = if (target == ScheduleTimeField.START) startInput else endInput,
            onDismiss = { timePickerTarget = null },
            onConfirm = { minutes ->
                if (target == ScheduleTimeField.START) startInput = minutes else endInput = minutes
                timePickerTarget = null
            }
        )
    }
}

@Composable
private fun BlockScheduleRow(
    schedule: BlockSchedule,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDeleteRequest: () -> Unit
) {
    // Formatting walks the day set; only recompute when this schedule's window changes.
    val windowSummary = remember(schedule.daysOfWeek, schedule.startMinuteOfDay, schedule.endMinuteOfDay) {
        "${formatDaySummary(schedule.daysOfWeek)} · ${formatScheduleWindow(schedule)}"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onEdit)
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = schedule.label.ifBlank { "Schedule" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = windowSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = onToggle
            )
            IconButton(onClick = onDeleteRequest) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete schedule",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun PixelPermissionItem(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    var rowModifier: Modifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .clickable(onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 6.dp)

    if (highlighted) {
        // Shared lifecycle and reduced-motion gating; alpha is read only while drawing.
        val highlightAlpha = rememberDecorativePulse(
            initialValue = 0.45f,
            targetValue = 1f,
            staticValue = 1f,
            label = "perm-pulse",
        )
        val borderColor = MaterialTheme.colorScheme.primary
        rowModifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = borderColor.copy(alpha = highlightAlpha.value),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(10.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        if (isGranted) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(50)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "Active",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        } else {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("Grant", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun RowScope.ScheduleDayChip(day: Int, selected: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = {
            Text(
                SCHEDULE_DAY_LABELS[day - 1],
                style = MaterialTheme.typography.labelSmall
            )
        },
        modifier = Modifier.weight(1f)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePickerDialog(
    title: String,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = (initialMinutes / 60).coerceIn(0, 23),
        initialMinute = (initialMinutes % 60).coerceIn(0, 59),
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        shape = MaterialTheme.shapes.large
    )
}

private enum class ScheduleTimeField { START, END }

private val SCHEDULE_DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val minute = minuteOfDay.coerceIn(0, 1439)
    return String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)
}

private fun formatScheduleWindow(schedule: BlockSchedule): String {
    val window = "${formatMinuteOfDay(schedule.startMinuteOfDay)}–${formatMinuteOfDay(schedule.endMinuteOfDay)}"
    return if (schedule.endMinuteOfDay < schedule.startMinuteOfDay) "$window (+1 day)" else window
}

private fun formatDaySummary(days: Set<Int>): String {
    if (days.isEmpty()) return "No days"
    if (days.size == 7) return "Every day"
    fun rangeLabel(start: Int, end: Int): String =
        if (start == end) SCHEDULE_DAY_LABELS[start - 1]
        else "${SCHEDULE_DAY_LABELS[start - 1]}–${SCHEDULE_DAY_LABELS[end - 1]}"
    val sorted = days.sorted()
    val parts = mutableListOf<String>()
    var rangeStart = sorted.first()
    var rangeEnd = rangeStart
    for (day in sorted.drop(1)) {
        if (day == rangeEnd + 1) {
            rangeEnd = day
        } else {
            parts += rangeLabel(rangeStart, rangeEnd)
            rangeStart = day
            rangeEnd = day
        }
    }
    parts += rangeLabel(rangeStart, rangeEnd)
    return parts.joinToString(", ")
}

/** JSON export/import of boundaries, limits, schedules and goals. */
@Composable
private fun ConfigBackupCard(snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = FocusLockApplication.instance
    val manager = remember {
        ConfigBackupManager(app.settingsRepository, app.appLimitsRepository, app.blockSchedulesRepository)
    }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { manager.exportToJson() }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                        output.flush()
                    } ?: throw IllegalStateException("Could not open the selected file")
                }
                snackbarHostState.showSnackbar("Config exported")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                snackbarHostState.showSnackbar("Export failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("Could not read the selected file")
                }
                pendingImportJson = raw
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                snackbarHostState.showSnackbar("Import failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Data",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Back up or restore boundaries, limits, schedules and goals as JSON.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("focuslock-config.json") },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export config")
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import config")
                }
            }
        }
    }

    pendingImportJson?.let { raw ->
        AlertDialog(
            onDismissRequest = { pendingImportJson = null },
            title = { Text("Import config?") },
            text = { Text("This will overwrite your boundaries/limits/schedules with the selected file.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportJson = null
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { manager.importFromJson(raw) }
                            snackbarHostState.showSnackbar(
                                "Imported ${result.blockedApps} apps, ${result.blockedWebsites} sites, " +
                                    "${result.appLimits} limits, ${result.blockSchedules} schedules"
                            )
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            snackbarHostState.showSnackbar("Import failed: ${e.message ?: "invalid file"}")
                        }
                    }
                }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportJson = null }) { Text("Cancel") }
            },
            shape = MaterialTheme.shapes.large
        )
    }
}

/** Daily reminder toggle + time picker; schedules the WorkManager worker on change. */
@Composable
private fun DailyReminderCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = FocusLockApplication.instance.settingsRepository
    val reminderEnabled by settings.dailyReminderEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val reminderMinute by settings.dailyReminderMinuteOfDayFlow.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_DAILY_REMINDER_MINUTE_OF_DAY
    )
    var showTimePicker by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Daily Reminder",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "A once-a-day nudge to log your focus work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = reminderEnabled,
                    onCheckedChange = { checked ->
                        scope.launch {
                            settings.setDailyReminderEnabled(checked)
                            if (checked) {
                                DailyReminderScheduler.schedule(context, reminderMinute)
                            } else {
                                DailyReminderScheduler.cancel(context)
                            }
                        }
                    }
                )
            }

            if (reminderEnabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Reminder time",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Fires around this time every day",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(formatMinuteOfDay(reminderMinute))
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        ScheduleTimePickerDialog(
            title = "Reminder time",
            initialMinutes = reminderMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = { minutes ->
                showTimePicker = false
                scope.launch {
                    settings.setDailyReminderMinuteOfDay(minutes)
                    if (reminderEnabled) DailyReminderScheduler.schedule(context, minutes)
                }
            }
        )
    }
}

/** "Eat the Frog" hard-lock settings: toggle, required focus minutes and wake hour. */
@Composable
private fun FrogSettingsCard() {
    val frog = FocusLockApplication.instance.frogRepository
    val scope = rememberCoroutineScope()
    val enabled by frog.enabledFlow.collectAsStateWithLifecycle(initialValue = FrogRepository.DEFAULT_ENABLED)
    val requiredMinutes by frog.requiredMinutesFlow.collectAsStateWithLifecycle(
        initialValue = FrogRepository.DEFAULT_REQUIRED_MINUTES
    )
    val wakeHour by frog.wakeHourFlow.collectAsStateWithLifecycle(initialValue = FrogRepository.DEFAULT_WAKE_HOUR)
    var editMinutes by remember(requiredMinutes) { mutableStateOf(requiredMinutes.toString()) }
    var editWakeHour by remember(wakeHour) { mutableStateOf(wakeHour.toString()) }

    fun commitMinutes() {
        val parsed = editMinutes.toIntOrNull()
        val value = (parsed ?: requiredMinutes)
            .coerceIn(FrogRepository.MIN_REQUIRED_MINUTES, FrogRepository.MAX_REQUIRED_MINUTES)
        editMinutes = value.toString()
        scope.launch { frog.setRequiredMinutes(value) }
    }

    fun commitWakeHour() {
        val parsed = editWakeHour.toIntOrNull()
        val value = (parsed ?: wakeHour)
            .coerceIn(FrogRepository.MIN_WAKE_HOUR, FrogRepository.MAX_WAKE_HOUR)
        editWakeHour = value.toString()
        scope.launch { frog.setWakeHour(value) }
    }

    val wakeLabel = wakeHour.coerceIn(0, 23).toString().padStart(2, '0') + ":00"

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Eat the Frog",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Hard-lock boundary apps until today's frog is done.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        scope.launch { frog.setEnabled(checked) }
                    }
                )
            }

            if (enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                OutlinedTextField(
                    value = editMinutes,
                    onValueChange = { input -> editMinutes = input.filter { it.isDigit() }.take(3) },
                    label = { Text("Focus minutes required") },
                    supportingText = { Text("Tracked focus on the selected frog (1–480)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitMinutes() }),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) commitMinutes() }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wake hour",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "24-hour clock (5 = 05:00). The lock arms on the first unlock at/after it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedTextField(
                        value = editWakeHour,
                        onValueChange = { input -> editWakeHour = input.filter { it.isDigit() }.take(2) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commitWakeHour() }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .width(96.dp)
                            .onFocusChanged { if (!it.isFocused) commitWakeHour() }
                    )
                }

                Text(
                    text = "On the first unlock after $wakeLabel, every boundary app locks " +
                        "until today's frog is ticked off and $requiredMinutes minutes of focus " +
                        "are tracked. Progress resets at the next $wakeLabel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
