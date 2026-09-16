package com.focuslock.app.ui.apps

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.service.InstalledAppsRepository
import com.focuslock.app.ui.components.AppIconTileForPackage
import com.focuslock.app.ui.components.IconBadge
import com.focuslock.app.ui.components.MotionTokens
import com.focuslock.app.ui.components.ScreenHeader
import com.focuslock.app.ui.components.SectionHeader
import com.focuslock.app.ui.components.StaggeredFadeSlide
import com.focuslock.app.ui.components.UiTokens
import com.focuslock.app.ui.components.pressScaleModifier
import com.focuslock.app.ui.strict.formatLockdownRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One row in the overview's "Blocked apps" list. [isInstalled] is false only once the
 * device inventory is known and the package is missing — it drives the metadata suffix.
 */
private data class BlockedAppItem(
    val packageName: String,
    val appName: String,
    val category: String,
    val isInstalled: Boolean
)

/**
 * Refusal copy for a frozen boundary change. Strict Mode outranks Boundaries Lock:
 * while it is active, its remaining cooldown is the reason the action is refused.
 */
internal fun boundariesFrozenMessage(
    lockdownActive: Boolean,
    lockdownRemainingMs: Long,
    boundariesLockSuffix: String
): String = if (lockdownActive) {
    "Strict Mode locks boundaries — ${formatLockdownRemaining(lockdownRemainingMs)} left"
} else {
    "Boundaries Lock is ON — $boundariesLockSuffix"
}

/**
 * Root of the Boundaries tab.
 *
 * Renders the lightweight overview (real blocked counts + drill-down rows) and swaps
 * to [AppPickerScreen] in place for the applications/websites pickers. One nullable
 * state instead of a nested NavHost keeps system back and tab switching predictable
 * inside the bottom-nav scaffold; MainActivity owns the Scaffold insets, so this
 * screen adds no status-bar padding of its own.
 */
@Composable
fun BoundariesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as FocusLockApplication
    val settings = app.settingsRepository
    val appLimits = app.appLimitsRepository

    val storedApps by settings.blockedAppsFlow.collectAsStateWithLifecycle(initialValue = null)
    val storedWebsites by settings.blockedWebsitesFlow.collectAsStateWithLifecycle(initialValue = null)
    // Combined freeze: Boundaries Lock OR Strict Mode (single source of truth).
    val boundariesFrozen by settings.boundariesFrozenFlow.collectAsStateWithLifecycle(initialValue = false)
    val lockdownMode by settings.lockdownModeFlow.collectAsStateWithLifecycle(initialValue = false)
    val limits by appLimits.limitsFlow.collectAsStateWithLifecycle(initialValue = emptyMap())

    // Live Strict Mode cooldown for accurate refusal copy; polls only while it is active.
    var lockdownRemainingMs by remember { mutableStateOf(0L) }
    LaunchedEffect(lockdownMode) {
        if (!lockdownMode) {
            lockdownRemainingMs = 0L
        } else {
            while (true) {
                lockdownRemainingMs = try {
                    settings.lockdownCooldownRemainingMs()
                } catch (_: Exception) {
                    0L
                }
                delay(30_000)
            }
        }
    }

    // Shared repository cache means this is usually a memory hit; the IO load only warms it.
    // Null only while the PackageManager inventory is unknown, so blocked rows never flash
    // "Not installed" before the device's app list has actually been read.
    val installedAppsState by produceState(
        initialValue = InstalledAppsRepository.getCachedAppsSnapshot(),
        context
    ) {
        value = withContext(Dispatchers.IO) {
            try {
                InstalledAppsRepository.getInstalledLaunchableApps(context)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    val installedApps = installedAppsState.orEmpty()

    // null = overview; a value = that picker tab is open.
    var pickerTab by rememberSaveable { mutableStateOf<PickerTab?>(null) }
    BackHandler(enabled = pickerTab != null) { pickerTab = null }

    // Optimistic unblock overrides, same shape/semantics as the picker's appOverrides:
    // the row leaves the list immediately, then the override is dropped once the
    // repository write settles (the settings flow then carries the persisted state).
    val appOverrides = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val storedAppsList = storedApps.orEmpty()
    val appOverrideMap = appOverrides.value
    val blockedApps: List<BlockedApp> = remember(storedAppsList, appOverrideMap) {
        storedAppsList.mapNotNull { stored ->
            val blocked = appOverrideMap[stored.packageName] ?: stored.isBlocked
            stored.takeIf { blocked }
        }
    }

    // Subtitle count matches the "Blocked apps" list exactly: every blocked entry,
    // installed or not, so the Applications row and the list below never disagree.
    val blockedAppCount = blockedApps.size
    val blockedWebsiteCount = storedWebsites?.count { it.isBlocked } ?: 0
    val activeLimitCount = limits.count { it.value.enabled && it.value.dailyMinutes > 0 }

    // Rows for the overview list: installed metadata wins (it can't be stale); stored
    // metadata covers blocked apps that are no longer installed ("Not installed").
    val blockedAppRows: List<BlockedAppItem> = remember(blockedApps, installedApps, installedAppsState) {
        val installedByPackage = installedApps.associateBy { it.packageName }
        val inventoryLoaded = installedAppsState != null
        blockedApps
            .map { stored ->
                val installed = installedByPackage[stored.packageName]
                BlockedAppItem(
                    packageName = stored.packageName,
                    appName = installed?.appName ?: stored.appName,
                    category = stored.category.ifBlank { installed?.category ?: "Other" },
                    isInstalled = installed != null || !inventoryLoaded
                )
            }
            .sortedBy { it.appName.lowercase() }
    }

    // Persists through the exact same repository path as the picker's switch
    // (SettingsRepository.setAppBlockedFull), so overview and picker never diverge.
    val onBlockedAppToggle: (BlockedAppItem, Boolean) -> Unit = remember(settings, scope, context) {
        { blockedApp, checked ->
            appOverrides.value = appOverrides.value + (blockedApp.packageName to checked)
            scope.launch {
                val ok = try {
                    settings.setAppBlockedFull(
                        blockedApp.packageName,
                        blockedApp.appName,
                        blockedApp.category,
                        checked
                    )
                    true
                } catch (_: Exception) {
                    false
                }
                appOverrides.value = appOverrides.value - blockedApp.packageName
                if (!ok) {
                    Toast.makeText(
                        context,
                        "Couldn't update ${blockedApp.appName}. Change reverted.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    when (val tab = pickerTab) {
        null -> BoundariesOverview(
            appsStorageLoaded = storedApps != null,
            websitesStorageLoaded = storedWebsites != null,
            blockedAppCount = blockedAppCount,
            blockedWebsiteCount = blockedWebsiteCount,
            activeLimitCount = activeLimitCount,
            boundariesFrozen = boundariesFrozen,
            lockdownMode = lockdownMode,
            lockdownRemainingMs = lockdownRemainingMs,
            blockedApps = blockedAppRows,
            onBlockedAppToggle = onBlockedAppToggle,
            onOpenApplications = { pickerTab = PickerTab.APPLICATIONS },
            onOpenWebsites = { pickerTab = PickerTab.WEBSITES }
        )

        else -> AppPickerScreen(
            selectedTab = tab,
            onTabChange = { pickerTab = it },
            onBack = { pickerTab = null }
        )
    }
}

@Composable
private fun BoundariesOverview(
    appsStorageLoaded: Boolean,
    websitesStorageLoaded: Boolean,
    blockedAppCount: Int,
    blockedWebsiteCount: Int,
    activeLimitCount: Int,
    boundariesFrozen: Boolean,
    lockdownMode: Boolean,
    lockdownRemainingMs: Long,
    blockedApps: List<BlockedAppItem>,
    onBlockedAppToggle: (BlockedAppItem, Boolean) -> Unit,
    onOpenApplications: () -> Unit,
    onOpenWebsites: () -> Unit
) {
    val lockedMessage = boundariesFrozenMessage(
        lockdownActive = lockdownMode,
        lockdownRemainingMs = lockdownRemainingMs,
        boundariesLockSuffix = "turn it off in Settings to remove"
    )
    // One-shot entrance cascade (header -> sections -> rows); remembered so it
    // runs on first composition only and never replays on scroll or state flips.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UiTokens.ScreenPadding)
    ) {
        StaggeredFadeSlide(visible = entered, index = 0) {
            ScreenHeader(
                title = "Your boundaries",
                subtitle = "Choose what waits until after your work."
            )
        }

        if (boundariesFrozen) {
            Spacer(Modifier.height(12.dp))
            StaggeredFadeSlide(visible = entered, index = 1) {
                Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp)
                    )
                    Text(
                        text = when {
                            !lockdownMode -> "Boundaries Lock is ON — blocked apps can't be removed"
                            lockdownRemainingMs > 0L ->
                                "Strict Mode locks boundaries — blocked apps and websites can't be " +
                                    "removed for ${formatLockdownRemaining(lockdownRemainingMs)}"
                            else ->
                                "Strict Mode locks boundaries — blocked apps and websites can't be " +
                                    "removed while it's on"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                }
            }
        }

        StaggeredFadeSlide(visible = entered, index = 1) {
            SectionHeader("Blocking")
        }
        Spacer(Modifier.height(8.dp))

        StaggeredFadeSlide(visible = entered, index = 2) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(animationSpec = MotionTokens.SpatialIntSize)
            ) {
            Column {
                BoundaryNavRow(
                    title = "Applications",
                    metadata = when {
                        !appsStorageLoaded -> "Loading…"
                        blockedAppCount == 1 -> "1 blocked"
                        else -> "$blockedAppCount blocked"
                    },
                    icon = Icons.Rounded.Apps,
                    iconContainer = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onOpenApplications
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(start = 76.dp)
                )
                BoundaryNavRow(
                    title = "Websites",
                    metadata = when {
                        !websitesStorageLoaded -> "Loading…"
                        blockedWebsiteCount == 1 -> "1 blocked domain"
                        else -> "$blockedWebsiteCount blocked domains"
                    },
                    icon = Icons.Rounded.Language,
                    iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                    iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = onOpenWebsites
                )
            }
        }
        }

        // Every currently blocked app (installed or not), directly under Blocking.
        // Hidden entirely when nothing is blocked — no empty header or card.
        // Row entrances are staggered but capped (~6 items) so long lists settle fast.
        if (blockedApps.isNotEmpty()) {
            StaggeredFadeSlide(visible = entered, index = 3) {
                SectionHeader("Blocked apps")
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.animateContentSize(animationSpec = MotionTokens.SpatialIntSize),
                verticalArrangement = Arrangement.spacedBy(UiTokens.ItemGap)
            ) {
                blockedApps.forEachIndexed { rowIndex, blockedApp ->
                    StaggeredFadeSlide(
                        visible = entered,
                        index = 4 + minOf(rowIndex, 4)
                    ) {
                        BlockedAppToggleRow(
                            app = blockedApp,
                            frozen = boundariesFrozen,
                            lockedMessage = lockedMessage,
                            onToggle = onBlockedAppToggle
                        )
                    }
                }
            }
        }

        // Real existing feature: per-app daily limits live inside the applications picker,
        // so the row only appears when at least one limit is configured.
        if (activeLimitCount > 0) {
            StaggeredFadeSlide(visible = entered, index = 4) {
                SectionHeader("Limits")
            }
            Spacer(Modifier.height(8.dp))
            StaggeredFadeSlide(visible = entered, index = 5) {
                Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                BoundaryNavRow(
                    title = "Daily limits",
                    metadata = if (activeLimitCount == 1) {
                        "1 app has a daily limit"
                    } else {
                        "$activeLimitCount apps have a daily limit"
                    },
                    icon = Icons.Rounded.Timer,
                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onOpenApplications
                )
                }
            }
        }

        Spacer(Modifier.height(UiTokens.SectionGap))
    }
}

@Composable
private fun BoundaryNavRow(
    title: String,
    metadata: String,
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    onClick: () -> Unit
) {
    val pressInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(pressScaleModifier(pressInteraction))
            .clickable(
                interactionSource = pressInteraction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconBadge(
            icon = icon,
            size = UiTokens.BadgeSize,
            containerColor = iconContainer,
            contentColor = iconTint
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * One blocked app on the overview, mirroring the picker's switch row: seamless 40dp
 * [AppIconTileForPackage] squircle (shared [InstalledAppsRepository] icon cache), name +
 * category metadata on a single ellipsized line, and a display-only switch. The whole
 * card is [Modifier.toggleable] with [Role.Switch], so a tap anywhere unblocks through
 * the same optimistic repository path as the picker. The switch sits in a plain
 * (non-clickable) Box so taps on the track/thumb fall through to the row toggleable
 * exactly once; the Box only becomes clickable while frozen, to surface [lockedMessage].
 * While boundaries are frozen (Boundaries Lock or Strict Mode) the row is disabled.
 * The picker's limit chip / always-block lock are intentionally absent here.
 */
@Composable
private fun BlockedAppToggleRow(
    app: BlockedAppItem,
    frozen: Boolean,
    lockedMessage: String,
    onToggle: (BlockedAppItem, Boolean) -> Unit
) {
    val context = LocalContext.current
    val rowEnabled = !frozen
    Card(
        colors = CardDefaults.cardColors(
            // Rows here are always blocked, so they match the picker's blocked container.
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = true,
                enabled = rowEnabled,
                role = Role.Switch,
                onValueChange = { checked -> onToggle(app, checked) }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconTileForPackage(
                packageName = app.packageName,
                name = app.appName,
                size = UiTokens.IconTileSize
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(app.category)
                        if (!app.isInstalled) append(" · Not installed")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Plain Box while unfrozen so switch-area taps fall through to the row
            // toggleable exactly once (a disabled clickable would still swallow them).
            // Only while frozen does the Box become clickable, to surface the refusal.
            Box(
                modifier = if (rowEnabled) {
                    Modifier
                } else {
                    Modifier.clickable {
                        Toast.makeText(context, lockedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Switch(
                    checked = true,
                    onCheckedChange = null,
                    enabled = rowEnabled,
                    thumbContent = {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = if (app.isInstalled) {
                            "${app.appName} block toggle"
                        } else {
                            "${app.appName} block toggle, app not installed"
                        }
                    }
                )
            }
        }
    }
}
