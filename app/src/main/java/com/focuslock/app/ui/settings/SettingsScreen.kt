package com.focuslock.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.service.TickTickAuthConfig
import com.focuslock.app.ui.permissions.PermissionHelper
import com.focuslock.app.ui.permissions.PermissionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(highlightKind: PermissionKind? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings = FocusLockApplication.instance.settingsRepository
    val bank = FocusLockApplication.instance.creditBankRepository

    val workRatio by settings.workRatioFlow.collectAsState(initial = 4)
    val taskBonus by settings.taskBonusFlow.collectAsState(initial = 5)
    val tickTickToken by settings.tickTickTokenFlow.collectAsState(initial = "")
    val tickTickUserName by settings.tickTickUserNameFlow.collectAsState(initial = "")
    val tickTickClientId by settings.tickTickClientIdFlow.collectAsState(initial = "")
    val tickTickClientSecret by settings.tickTickClientSecretFlow.collectAsState(initial = "")
    val notificationEnabled by settings.tickTickNotificationEnabledFlow.collectAsState(initial = true)
    val strictMode by settings.strictModeFlow.collectAsState(initial = false)

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
                val url = TickTickApiClient.buildAuthorizeUrl(TickTickAuthConfig.effectiveClientId(settings), state)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                showOAuthCredentialsDialog = false
                showManualCallback = true
                syncMessage = "Browser opened — approve TickTick, then paste the http://127.0.0.1:8080/ address back below."
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
                val tokenResponse = api.exchangeCodeForToken(clientId, clientSecret, code)
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

    val isAccessibilityOn = remember(context, refreshTick) { PermissionHelper.isAccessibilityServiceEnabled(context) }
    val isUsageOn = remember(context, refreshTick) { PermissionHelper.isUsageAccessGranted(context) }
    val isOverlayOn = remember(context, refreshTick) { PermissionHelper.isOverlayGranted(context) }
    val isNotifOn = remember(context, refreshTick) { PermissionHelper.isNotificationListenerGranted(context) }
    val isBatteryIgnored = remember(context, refreshTick) { PermissionHelper.isBatteryOptimizationIgnored(context) }
    val isDeviceAdminOn = remember(context, refreshTick) { PermissionHelper.isDeviceAdminActive(context) }

    // Auto-scroll to the highlighted (next missing) permission row.
    val scrollState = rememberScrollState()
    val effectiveHighlight = highlightKind
        ?: remember(context, refreshTick) { PermissionHelper.getNextMissingPermission(context) }
    LaunchedEffect(effectiveHighlight) {
        if (effectiveHighlight == PermissionKind.BATTERY || effectiveHighlight == PermissionKind.DEVICE_ADMIN) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    var lastSyncText by remember(refreshTick) { mutableStateOf("") }
    LaunchedEffect(refreshTick) {
        val ts = bank.getLastSyncTimestamp()
        lastSyncText = if (ts > 0) {
            val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            "Last synced ${sdf.format(Date(ts))}"
        } else "Never synced"
    }

    val isTickTickConnected = tickTickToken.isNotBlank()

    suspend fun doSync(showToast: Boolean = true) {
        if (tickTickToken.isBlank()) {
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
                val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                "Last synced ${sdf.format(Date(ts))}"
            } else lastSyncText
            syncMessage = if (records.isEmpty()) {
                "No tasks completed today found in TickTick. Complete a task, then sync again — or use Log Work."
            } else if (newCount == 0) {
                "Already up to date — ${records.size} completed task(s) already credited."
            } else {
                "Synced! $newCount new task(s) → +$earned min leisure."
            }
            if (showToast) Toast.makeText(context, syncMessage, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            syncMessage = "Sync failed: ${e.message}. Check connection or use Log Work fallback."
            if (showToast) Toast.makeText(context, syncMessage, Toast.LENGTH_LONG).show()
        } finally {
            isSyncing = false
        }
    }

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

        // 1. Productivity Conversion Rules Card
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
                    value = workRatio.toFloat(),
                    onValueChange = { scope.launch { settings.setWorkRatio(it.toInt()) } },
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
                    value = taskBonus.toFloat(),
                    onValueChange = { scope.launch { settings.setTaskBonus(it.toInt()) } },
                    valueRange = 0f..20f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.tertiary,
                        activeTrackColor = MaterialTheme.colorScheme.tertiary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                // Strict Mode (AppBlock-style)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Strict Mode",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (strictMode) "ON: emergency 2-min pass is disabled."
                            else "OFF: blocker offers a 2-min emergency pass.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = strictMode,
                        onCheckedChange = { scope.launch { settings.setStrictMode(it) } }
                    )
                }
            }
        }

        // 2. TickTick Integration Card — simplified: token-first, OAuth advanced
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
                                onClick = {
                                    scope.launch {
                                        settings.clearTickTickAuth()
                                        syncMessage = null
                                        Toast.makeText(context, "TickTick disconnected", Toast.LENGTH_SHORT).show()
                                    }
                                },
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
                                            doSync(false)
                                            refreshTick++
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

        // 3. System Permissions Checklist
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
                    isGranted = isAccessibilityOn,
                    highlighted = effectiveHighlight == PermissionKind.ACCESSIBILITY,
                    onClick = { PermissionHelper.openAccessibilitySettings(context) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                PixelPermissionItem(
                    title = "Usage Access",
                    subtitle = "Powers screen-time dashboard accuracy",
                    isGranted = isUsageOn,
                    highlighted = effectiveHighlight == PermissionKind.USAGE,
                    onClick = { PermissionHelper.openUsageAccessSettings(context) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                PixelPermissionItem(
                    title = "Notification Listener",
                    subtitle = "Captures TickTick Pomodoros automatically",
                    isGranted = isNotifOn,
                    highlighted = effectiveHighlight == PermissionKind.NOTIFICATION_LISTENER,
                    onClick = { PermissionHelper.openNotificationListenerSettings(context) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                PixelPermissionItem(
                    title = "Display Over Other Apps",
                    subtitle = "Shows fullscreen lockout over target apps",
                    isGranted = isOverlayOn,
                    highlighted = effectiveHighlight == PermissionKind.OVERLAY,
                    onClick = { PermissionHelper.openOverlaySettings(context) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                PixelPermissionItem(
                    title = "Ignore Battery Optimizations",
                    subtitle = "Keeps protection alive in background",
                    isGranted = isBatteryIgnored,
                    highlighted = effectiveHighlight == PermissionKind.BATTERY,
                    onClick = { PermissionHelper.openBatteryOptimizationSettings(context) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                PixelPermissionItem(
                    title = "Prevent Uninstall (Device Admin)",
                    subtitle = "Blocks impulsive uninstalls during a binge",
                    isGranted = isDeviceAdminOn,
                    highlighted = effectiveHighlight == PermissionKind.DEVICE_ADMIN,
                    onClick = { PermissionHelper.openDeviceAdminSettings(context) }
                )
                if (isDeviceAdminOn) {
                    TextButton(
                        onClick = {
                            PermissionHelper.disableDeviceAdmin(context)
                            refreshTick++
                            Toast.makeText(context, "Uninstall protection disabled", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Disable admin (allow uninstall)") }
                }
            }
        }
    }

    if (showOAuthCredentialsDialog) {
        AlertDialog(
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
}

@Composable
fun PixelPermissionItem(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    var rowModifier: Modifier = Modifier.fillMaxWidth()
    if (highlighted) {
        val pulse = rememberInfiniteTransition(label = "perm-highlight")
        val alpha by pulse.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "perm-pulse"
        )
        rowModifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
                RoundedCornerShape(16.dp)
            )
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
