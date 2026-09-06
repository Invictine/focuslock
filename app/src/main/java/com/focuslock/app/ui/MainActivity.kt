package com.focuslock.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.auth.AccountScreen
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.auth.FocusAuthGate
import com.focuslock.app.auth.FocusAuthState
import com.focuslock.app.service.AppMonitorForegroundService
import com.focuslock.app.sync.SyncStatus
import com.focuslock.app.ui.apps.AppSelectorScreen
import com.focuslock.app.ui.dashboard.DashboardScreen
import com.focuslock.app.ui.permissions.PermissionHelper
import com.focuslock.app.ui.settings.SettingsScreen
import com.focuslock.app.ui.theme.FocusLockTheme

enum class NavigationItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD("Focus", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    APPS("Boundaries", Icons.Filled.Apps, Icons.Outlined.Apps),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
    ACCOUNT("Account", Icons.Filled.Person, Icons.Outlined.Person)
}

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Foreground-service notification is best-effort; dashboard works without it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()

        // Start ongoing protection service
        startMonitorService()

        setContent {
            FocusLockTheme {
                val authViewModel: AuthViewModel by viewModels()
                val authState by authViewModel.state.collectAsStateWithLifecycle()
                var offlineMode by rememberSaveable { mutableStateOf(false) }

                // Auto-sync lifecycle: start when signed in, stop otherwise.
                val app = application as FocusLockApplication
                LaunchedEffect(authState) {
                    if (authState == FocusAuthState.SignedIn) {
                        app.syncManager.startAutoSync(authViewModel)
                        app.syncManager.syncNowAsync(authViewModel)
                    } else {
                        app.syncManager.stopAutoSync()
                    }
                }

                if (authState == FocusAuthState.SignedOut && !offlineMode) {
                    FocusAuthGate(state = authState, onContinueOffline = { offlineMode = true }) { }
                } else {
                    var currentTab by rememberSaveable { mutableStateOf(NavigationItem.DASHBOARD) }
                    val syncStatus by app.syncManager.status.collectAsStateWithLifecycle()
                    val syncText = when (val s = syncStatus) {
                        SyncStatus.Idle -> "Auto-sync idle"
                        SyncStatus.Syncing -> "Syncing…"
                        is SyncStatus.Done -> s.detail
                        is SyncStatus.Skipped -> s.reason
                        is SyncStatus.Error -> s.message
                    }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 0.dp
                        ) {
                            NavigationItem.entries.forEach { item ->
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
                        when (currentTab) {
                            NavigationItem.DASHBOARD -> DashboardScreen(
                                onOpenTickTick = { openTickTick() },
                                onNavigatePermissions = { currentTab = NavigationItem.SETTINGS }
                            )
                            NavigationItem.APPS -> AppSelectorScreen()
                            NavigationItem.SETTINGS -> SettingsScreen(
                                highlightKind = PermissionHelper.getNextMissingPermission(applicationContext)
                            )
                            NavigationItem.ACCOUNT -> AccountScreen(
                                syncStatus = syncText,
                                onSyncNow = { app.syncManager.syncNowAsync(authViewModel) },
                                onSignOut = { authViewModel.signOut() },
                            )
                        }
                        }
                    }
                }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                try {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: Exception) { }
            }
        }
    }

    private fun startMonitorService() {
        try {
            val intent = Intent(this, AppMonitorForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // Handled safely
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
}
