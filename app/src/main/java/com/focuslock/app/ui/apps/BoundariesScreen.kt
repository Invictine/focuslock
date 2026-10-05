package com.focuslock.app.ui.apps

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.LocationOn
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.model.BlockedWebsite
import com.focuslock.app.service.InstalledAppsRepository
import com.focuslock.app.ui.components.AppIconTileForPackage
import com.focuslock.app.ui.components.IconBadge
import com.focuslock.app.ui.components.PendingMergeTarget
import com.focuslock.app.ui.components.ScreenHeader
import com.focuslock.app.ui.components.SectionHeader
import com.focuslock.app.ui.components.StaggeredFadeSlide
import com.focuslock.app.ui.components.UiTokens
import com.focuslock.app.ui.components.pressScaleModifier
import com.focuslock.app.ui.strict.formatLockdownRemaining
import com.focuslock.app.ui.permalock.PermalockScreen
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
    strictAutomationActive: Boolean = false,
    lockdownRemainingMs: Long,
    boundariesLockSuffix: String
): String = when {
    lockdownActive -> "Strict Mode locks boundaries — ${formatLockdownRemaining(lockdownRemainingMs)} left"
    strictAutomationActive -> "Scheduled Strict Mode locks boundary changes while active"
    else -> "Boundaries Lock is ON — $boundariesLockSuffix"
}

/**
 * Root of the Boundaries tab.
 *
 * Renders the lightweight overview (real blocked counts + drill-down rows) and swaps
 * to [AppPickerScreen] in place for the applications/websites pickers. One nullable
 * state instead of a nested NavHost keeps system back and tab switching predictable
 * inside the bottom-nav scaffold; MainActivity owns the Scaffold insets, so this
 * screen adds no status-bar padding of its own.
 *
 * [pendingMergeTarget] is the cross-device "New bucket…" hand-off: a usage row asks
 * MainActivity to open this tab with one target, this screen opens the picker for it,
 * and [AppPickerScreen] opens the merge editor pre-filled (then reports consumption).
 */
@Composable
fun BoundariesScreen(
    pendingMergeTarget: PendingMergeTarget? = null,
    onPendingMergeConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as FocusLockApplication
    val settings = app.settingsRepository
    val appLimits = app.appLimitsRepository

    val storedApps by settings.blockedAppsFlow.collectAsStateWithLifecycle(initialValue = null)
    val storedWebsites by settings.blockedWebsitesFlow.collectAsStateWithLifecycle(initialValue = null)
    val permanentPackagesState by app.permanentBlocksRepository.packagesFlow.collectAsStateWithLifecycle(initialValue = null)
    val permanentDomainsState by app.permanentBlocksRepository.domainsFlow.collectAsStateWithLifecycle(initialValue = null)
    val permanentStoreLoaded = permanentPackagesState != null && permanentDomainsState != null
    val permanentPackages = permanentPackagesState.orEmpty()
    val permanentDomains = permanentDomainsState.orEmpty()
    // Strict manual and automatic activations freeze boundary configuration only.
    val boundariesFrozenBase by settings.boundariesFrozenFlow.collectAsStateWithLifecycle(initialValue = false)
    val strictAutomationActive by app.strictModeAutomationRepository.activationActiveFlow.collectAsStateWithLifecycle(initialValue = false)
    val boundariesFrozen = boundariesFrozenBase || strictAutomationActive
    val lockdownMode by settings.lockdownModeFlow.collectAsStateWithLifecycle(initialValue = false)
    val limits by appLimits.limitsFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    // null = overview; a value = that picker tab is open.
    var pickerTab by rememberSaveable { mutableStateOf<PickerTab?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    val overviewListState = rememberLazyListState()
    val homeOnly by app.homeLocationRepository.homeOnlyFlow.collectAsStateWithLifecycle(initialValue = false)
    val homePlace by app.homeLocationRepository.homePlaceFlow.collectAsStateWithLifecycle(initialValue = null)

    // Live Strict Mode cooldown for accurate refusal copy; polls only while it is active.
    var lockdownRemainingMs by remember { mutableStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lockdownMode, pickerTab, lifecycleOwner) {
        if (!lockdownMode) {
            lockdownRemainingMs = 0L
        } else if (pickerTab == null) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val remainingMs = try {
                        settings.lockdownCooldownRemainingMs()
                    } catch (_: Exception) {
                        null
                    }
                    lockdownRemainingMs = remainingMs ?: 0L
                    if (remainingMs == 0L) break
                    delay(minOf(remainingMs ?: 30_000L, 30_000L))
                }
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

    BackHandler(enabled = pickerTab != null || detail != null) {
        if (pickerTab != null) pickerTab = null else detail = null
    }

    // Cross-device "New bucket…" hand-off: keep the target locally (so it survives the
    // tab switch inside this screen), open the Applications picker, and clear it in
    // MainActivity immediately. AppPickerScreen consumes the local copy by opening the
    // editor and clearing it here — including after cancel/back, so it never reopens.
    var pendingMerge by remember { mutableStateOf<PendingMergeTarget?>(null) }
    LaunchedEffect(pendingMergeTarget) {
        val target = pendingMergeTarget ?: return@LaunchedEffect
        pendingMerge = target
        detail = null
        pickerTab = PickerTab.APPLICATIONS
        onPendingMergeConsumed()
    }

    // Optimistic unblock overrides, same shape/semantics as the picker's appOverrides:
    // the row leaves the list immediately, then the override is dropped once the
    // repository write settles (the settings flow then carries the persisted state).
    val appOverrides = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val websiteOverrides = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val storedAppsList = storedApps.orEmpty()
    val storedWebsitesList = storedWebsites.orEmpty()
    val effectivePermanentDomains = permanentDomains + storedWebsitesList.filter { it.isPermanent }.map { it.domain }
    val appOverrideMap = appOverrides.value
    val blockedApps: List<BlockedApp> = remember(
        storedAppsList, appOverrideMap, permanentPackages, permanentStoreLoaded, storedApps != null
    ) {
            if (!permanentStoreLoaded || storedApps == null) emptyList() else storedAppsList.mapNotNull { stored ->
            val blocked = appOverrideMap[stored.packageName] ?: stored.isBlocked
            stored.takeIf {
                blocked && !it.isPermanent && !isPermanentPackage(it.packageName, permanentPackages)
            }
        }
    }

    // Subtitle count matches the "Blocked apps" list exactly: every blocked entry,
    // installed or not, so the Applications row and the list below never disagree.
    val blockedAppCount = blockedApps.size
    val blockedWebsites = if (!permanentStoreLoaded || storedWebsites == null) emptyList() else storedWebsitesList.filter {
        (websiteOverrides.value[it.domain] ?: it.isBlocked) &&
            !it.isPermanent && !isPermanentDomain(it.domain, effectivePermanentDomains)
    }.sortedBy { it.displayName.lowercase() }
    val blockedWebsiteCount = blockedWebsites.size
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
    val onBlockedWebsiteToggle: (BlockedWebsite, Boolean) -> Unit = remember(settings, scope, context) {
        { website, checked ->
            websiteOverrides.value = websiteOverrides.value + (website.domain to checked)
            scope.launch {
                val ok = try {
                    settings.setWebsiteBlocked(website.domain, checked)
                    true
                } catch (_: Exception) {
                    false
                }
                websiteOverrides.value = websiteOverrides.value - website.domain
                if (!ok) Toast.makeText(context, "Couldn't update ${website.displayName}.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (detail == "permanent") {
        PermalockScreen(onBack = { detail = null })
        return
    }
    if (detail == "location") {
        HomeBlockingScreen(onBack = { detail = null })
        return
    }
    when (val tab = pickerTab) {
        null -> BoundariesOverview(
            listState = overviewListState,
            appsStorageLoaded = storedApps != null,
            websitesStorageLoaded = storedWebsites != null,
            blockedAppCount = blockedAppCount,
            blockedWebsiteCount = blockedWebsiteCount,
            activeLimitCount = activeLimitCount,
            permanentCount = permanentPackages.size + permanentDomains.size,
            permanentStoreLoaded = permanentStoreLoaded,
            locationSummary = when {
                homePlace == null -> "Everywhere · Home location not set"
                homeOnly -> "Home only · ${homePlace?.label ?: "Home"}"
                else -> "Everywhere · Home-only blocking is off"
            },
            onOpenPermanent = { detail = "permanent" },
            onOpenLocation = { detail = "location" },
            boundariesFrozen = boundariesFrozen,
            lockdownMode = lockdownMode,
            strictAutomationActive = strictAutomationActive,
            lockdownRemainingMs = lockdownRemainingMs,
            blockedApps = blockedAppRows,
            blockedWebsites = blockedWebsites,
            onBlockedAppToggle = onBlockedAppToggle,
            onBlockedWebsiteToggle = onBlockedWebsiteToggle,
            onOpenApplications = { pickerTab = PickerTab.APPLICATIONS },
            onOpenWebsites = { pickerTab = PickerTab.WEBSITES }
        )

        else -> AppPickerScreen(
            selectedTab = tab,
            onTabChange = { pickerTab = it },
            onBack = { pickerTab = null },
            pendingMerge = pendingMerge,
            onPendingMergeConsumed = { pendingMerge = null },
        )
    }
}

@Composable
private fun BoundariesOverview(
    listState: LazyListState,
    appsStorageLoaded: Boolean,
    websitesStorageLoaded: Boolean,
    blockedAppCount: Int,
    blockedWebsiteCount: Int,
    activeLimitCount: Int,
    permanentCount: Int,
    permanentStoreLoaded: Boolean,
    locationSummary: String,
    onOpenPermanent: () -> Unit,
    onOpenLocation: () -> Unit,
    boundariesFrozen: Boolean,
    lockdownMode: Boolean,
    strictAutomationActive: Boolean,
    lockdownRemainingMs: Long,
    blockedApps: List<BlockedAppItem>,
    blockedWebsites: List<BlockedWebsite>,
    onBlockedAppToggle: (BlockedAppItem, Boolean) -> Unit,
    onBlockedWebsiteToggle: (BlockedWebsite, Boolean) -> Unit,
    onOpenApplications: () -> Unit,
    onOpenWebsites: () -> Unit
) {
    val lockedMessage = boundariesFrozenMessage(
        lockdownActive = lockdownMode,
        strictAutomationActive = strictAutomationActive,
        lockdownRemainingMs = lockdownRemainingMs,
        boundariesLockSuffix = "turn it off in Settings to remove"
    )
    // One-shot entrance cascade (header -> sections -> rows); remembered so it
    // runs on first composition only and never replays on scroll or state flips.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = UiTokens.ScreenPadding,
            end = UiTokens.ScreenPadding,
            bottom = UiTokens.SectionGap
        )
    ) {
        item(key = "boundaries-header", contentType = "screenHeader") {
            StaggeredFadeSlide(visible = entered, index = 0, screenKey = "boundaries_overview") {
                ScreenHeader(
                    title = "Boundaries",
                    subtitle = "Manage distractions and choose where blocking applies."
                )
            }
        }

        if (boundariesFrozen) {
            item(key = "boundaries-frozen-spacer", contentType = "spacer") {
                Spacer(Modifier.height(12.dp))
            }
            item(key = "boundaries-frozen-notice", contentType = "statusCard") {
                StaggeredFadeSlide(visible = entered, index = 1, screenKey = "boundaries_overview") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (lockdownMode) "Strict Mode is active" else "Boundaries Lock is on",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = when {
                                        !lockdownMode -> "Turn it off in Settings to remove blocks."
                                        lockdownRemainingMs > 0L -> "Edits locked for ${formatLockdownRemaining(lockdownRemainingMs)}."
                                        else -> "Edits stay locked until it's off."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "boundaries-blocking-header", contentType = "sectionHeader") {
            StaggeredFadeSlide(visible = entered, index = 1, screenKey = "boundaries_overview") {
                SectionHeader("Everyday boundaries")
            }
        }
        item(key = "boundaries-blocking-spacer", contentType = "spacer") {
            Spacer(Modifier.height(8.dp))
        }

        item(key = "boundaries-navigation", contentType = "navigationCard") {
            StaggeredFadeSlide(visible = entered, index = 2, screenKey = "boundaries_overview") {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
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
        }

        item(key = "boundaries-permanent", contentType = "navigationCard") {
            SectionHeader("Permanent commitments")
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                BoundaryNavRow(
                    title = "Permanent blocks",
                    metadata = if (!permanentStoreLoaded) "Loading…" else if (permanentCount == 0) "Block apps or websites permanently" else "$permanentCount saved · No in-app removal",
                    icon = Icons.Rounded.Block,
                    iconContainer = MaterialTheme.colorScheme.errorContainer,
                    iconTint = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = onOpenPermanent
                )
            }
        }
        item(key = "boundaries-location", contentType = "navigationCard") {
            SectionHeader("Where blocking applies")
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                BoundaryNavRow(
                    title = "Blocking location",
                    metadata = locationSummary,
                    icon = Icons.Rounded.LocationOn,
                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onOpenLocation
                )
            }
        }

        // Every currently blocked app (installed or not), directly under Blocking.
        // Hidden entirely when nothing is blocked — no empty header or card.
        // Row entrances are staggered but capped (~6 items) so long lists settle fast.
        if (blockedApps.isNotEmpty()) {
            item(key = "boundaries-apps-header", contentType = "sectionHeader") {
                StaggeredFadeSlide(visible = entered, index = 3, screenKey = "boundaries_overview") {
                    SectionHeader("Blocked apps")
                }
            }
            item(key = "boundaries-apps-spacer", contentType = "spacer") {
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(
                items = blockedApps,
                key = { _, app -> "blocked-app-${app.packageName}" },
                contentType = { _, _ -> "blockedAppRow" }
            ) { rowIndex, blockedApp ->
                Surface(
                    shape = boundariesRowShape(rowIndex, blockedApps.lastIndex),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.animateItem()
                ) {
                    StaggeredFadeSlide(
                        visible = entered,
                        index = 4 + minOf(rowIndex, 4),
                        screenKey = "boundaries_overview"
                    ) {
                        BlockedAppToggleRow(
                            app = blockedApp,
                            frozen = boundariesFrozen,
                            lockedMessage = lockedMessage,
                            onToggle = onBlockedAppToggle,
                            showDivider = rowIndex < blockedApps.lastIndex
                        )
                    }
                }
            }
        }

        if (blockedWebsites.isNotEmpty()) {
            item(key = "boundaries-websites-header", contentType = "sectionHeader") {
                StaggeredFadeSlide(visible = entered, index = 4, screenKey = "boundaries_overview") {
                    SectionHeader("Blocked websites")
                }
            }
            item(key = "boundaries-websites-spacer", contentType = "spacer") {
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(
                items = blockedWebsites,
                key = { _, website -> "blocked-website-${website.domain}" },
                contentType = { _, _ -> "blockedWebsiteRow" }
            ) { index, website ->
                Surface(
                    shape = boundariesRowShape(index, blockedWebsites.lastIndex),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.animateItem()
                ) {
                    Column {
                        BlockedWebsiteToggleRow(
                            website = website,
                            frozen = boundariesFrozen,
                            lockedMessage = lockedMessage,
                            onToggle = onBlockedWebsiteToggle
                        )
                        if (index < blockedWebsites.lastIndex) HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )
                    }
                }
            }
        }

        // Real existing feature: per-app daily limits live inside the applications picker,
        // so the row only appears when at least one limit is configured.
        if (activeLimitCount > 0) {
            item(key = "boundaries-limits-header", contentType = "sectionHeader") {
                StaggeredFadeSlide(visible = entered, index = 4, screenKey = "boundaries_overview") {
                    SectionHeader("Limits")
                }
            }
            item(key = "boundaries-limits-spacer", contentType = "spacer") {
                Spacer(Modifier.height(8.dp))
            }
            item(key = "boundaries-limits-card", contentType = "navigationCard") {
                StaggeredFadeSlide(
                    visible = entered,
                    index = 5,
                    modifier = Modifier.fillMaxWidth(),
                    screenKey = "boundaries_overview"
                ) {
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
        }

        item(key = "boundaries-bottom-spacer", contentType = "spacer") {
            Spacer(Modifier.height(UiTokens.SectionGap))
        }
    }
}

private fun boundariesRowShape(index: Int, lastIndex: Int) = RoundedCornerShape(
    topStart = if (index == 0) 20.dp else 0.dp,
    topEnd = if (index == 0) 20.dp else 0.dp,
    bottomStart = if (index == lastIndex) 20.dp else 0.dp,
    bottomEnd = if (index == lastIndex) 20.dp else 0.dp
)

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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
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
    onToggle: (BlockedAppItem, Boolean) -> Unit,
    showDivider: Boolean = false
) {
    val context = LocalContext.current
    val rowEnabled = !frozen
    Column(
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
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 68.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun BlockedWebsiteToggleRow(
    website: BlockedWebsite,
    frozen: Boolean,
    lockedMessage: String,
    onToggle: (BlockedWebsite, Boolean) -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (frozen) Toast.makeText(context, lockedMessage, Toast.LENGTH_SHORT).show()
                else onToggle(website, false)
            }
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconBadge(
            icon = Icons.Rounded.Language,
            size = UiTokens.IconTileSize,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = website.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = website.domain,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = true,
            onCheckedChange = if (frozen) null else { checked -> onToggle(website, checked) },
            enabled = !frozen,
            modifier = Modifier.semantics { contentDescription = "${website.displayName} website block toggle" }
        )
    }
}
