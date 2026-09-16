package com.focuslock.app.ui.apps

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.model.BlockedWebsite
import com.focuslock.app.data.repository.AppLimit
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.service.InstalledApp
import com.focuslock.app.service.InstalledAppsRepository
import com.focuslock.app.service.UsageStatsRepository
import com.focuslock.app.ui.components.AppIconTileForPackage
import com.focuslock.app.ui.components.IconBadge
import com.focuslock.app.ui.components.MotionTokens
import com.focuslock.app.ui.components.StaggeredFadeSlide
import com.focuslock.app.ui.components.UiTokens
import com.focuslock.app.ui.components.pressScaleModifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PickerTab {
    APPLICATIONS,
    WEBSITES
}

private data class AppRowItem(
    val packageName: String,
    val appName: String,
    val category: String,
    val isBlocked: Boolean,
    val isInstalled: Boolean,
    val todayMinutes: Long = 0L,
    val isPermanent: Boolean = false
)

/** A category section in the picker: its visible rows plus pre-filter totals for the header. */
private data class CategoryGroup(
    val category: String,
    val apps: List<AppRowItem>,
    val totalCount: Int,
    val blockedCount: Int
)

/** Load lifecycle for the PackageManager query — distinct from "loaded but empty". */
private sealed interface AppsLoadState {
    data object Loading : AppsLoadState
    data object Ready : AppsLoadState
    data class Error(val message: String) : AppsLoadState
}

/** A bulk preset awaiting confirmation. */
private data class BulkAction(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val isDestructive: Boolean = false,
    val onConfirm: () -> Unit
)

private val DOMAIN_REGEX =
    Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

/** Trim a pasted URL down to a bare hostname, or null when it can't be a domain. */
private fun normalizeDomainInput(raw: String): String? {
    var value = raw.trim().lowercase()
    if (value.isBlank()) return null
    value = value.substringAfter("://", value)
    value = value.substringBefore('/')
    value = value.substringBefore('?')
    value = value.substringBefore('#')
    value = value.substringAfter('@')
    value = value.substringBefore(':')
    value = value.removePrefix("www.").removePrefix("m.")
    value = value.trim('.', '-', '_', ' ')
    return if (DOMAIN_REGEX.matches(value)) value else null
}

private fun summarizeNames(names: List<String>): String {
    if (names.isEmpty()) return ""
    val shown = names.take(5)
    val suffix = if (names.size > shown.size) " and ${names.size - shown.size} more" else ""
    return shown.joinToString(", ") + suffix
}

/** Package -> default doomscroll entry, built once instead of rescanning defaults per app per merge. */
private val DEFAULT_DOOMSCROLL_BY_PACKAGE: Map<String, BlockedApp> =
    BlockedApp.DEFAULT_DOOMSCROLL_APPS.associateBy { it.packageName }

/**
 * Search relevance tier for one row:
 * 0 = exact app-name match, 1 = app name starts with the query, 2 = a word inside the
 * app name starts with the query, 3 = query appears anywhere in the name or package,
 * -1 = no match. The old pure `contains` filter returned an app named "X" in the middle
 * of the alphabet; tiers make exact matches rank first.
 */
private fun matchTier(row: AppRowItem, rawQuery: String): Int {
    val query = rawQuery.trim()
    if (query.isEmpty()) return 0
    val name = row.appName
    return when {
        name.equals(query, ignoreCase = true) -> 0
        name.startsWith(query, ignoreCase = true) -> 1
        hasWordStartMatch(name, query) -> 2
        name.contains(query, ignoreCase = true) ||
            row.packageName.contains(query, ignoreCase = true) -> 3
        else -> -1
    }
}

/** True when [query] occurs in [text] at a word boundary (start of string or after a non-letter/digit). */
private fun hasWordStartMatch(text: String, query: String): Boolean {
    var index = text.indexOf(query, ignoreCase = true)
    while (index >= 0) {
        val startsWord = index == 0 || !text[index - 1].isLetterOrDigit()
        if (startsWord) return true
        index = text.indexOf(query, startIndex = index + 1, ignoreCase = true)
    }
    return false
}

/**
 * Persists per-category expand/collapse across config changes for the session.
 * Saved as "category|true/false" strings because Bundle can't hold a Map directly.
 */
private val CategoryExpansionSaver = listSaver<MutableState<Map<String, Boolean>>, String>(
    save = { state -> state.value.map { entry -> "${entry.key}|${entry.value}" } },
    restore = { saved ->
        mutableStateOf(
            saved.mapNotNull { entry ->
                val separator = entry.lastIndexOf('|')
                if (separator <= 0 || separator == entry.lastIndex) {
                    null
                } else {
                    entry.substring(0, separator) to (entry.substring(separator + 1) == "true")
                }
            }.toMap()
        )
    }
)

/**
 * Back-compat entry point. [BoundariesScreen] calls [AppPickerScreen] directly with the
 * selected tab; this no-arg overload keeps any older caller compiling (back is a no-op).
 */
@Composable
fun AppSelectorScreen() {
    AppPickerScreen(
        selectedTab = PickerTab.APPLICATIONS,
        onTabChange = {},
        onBack = {}
    )
}

@Composable
internal fun AppPickerScreen(
    selectedTab: PickerTab,
    onTabChange: (PickerTab) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = FocusLockApplication.instance.settingsRepository
    val appLimits = (context.applicationContext as FocusLockApplication).appLimitsRepository

    // null initial = DataStore has not emitted yet: never flash DEFAULT_DOOMSCROLL_APPS
    // as if it were the user's saved state.
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
    val removalLockedMessage = boundariesFrozenMessage(
        lockdownActive = lockdownMode,
        lockdownRemainingMs = lockdownRemainingMs,
        boundariesLockSuffix = "turn it off in Settings to remove"
    )
    val limitLockedMessage = boundariesFrozenMessage(
        lockdownActive = lockdownMode,
        lockdownRemainingMs = lockdownRemainingMs,
        boundariesLockSuffix = "turn it off in Settings to change limits."
    )
    val deleteLockedMessage = boundariesFrozenMessage(
        lockdownActive = lockdownMode,
        lockdownRemainingMs = lockdownRemainingMs,
        boundariesLockSuffix = "turn it off in Settings to remove websites."
    )

    val blockedApps = storedApps.orEmpty()
    val blockedWebsites = storedWebsites.orEmpty()
    val appsStorageLoaded = storedApps != null
    val websitesStorageLoaded = storedWebsites != null

    // State object handed to SearchField: the text is only read inside that composable,
    // so keystrokes never invalidate this screen or the 300+ item list.
    val searchQuery = rememberSaveable { mutableStateOf("") }
    // Debounced query drives filtering so typing doesn't recompose lists per keystroke.
    var debouncedQuery by remember { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var showAddWebsiteDialog by remember { mutableStateOf(false) }
    var newWebsiteInput by remember { mutableStateOf("") }
    var websiteInputError by remember { mutableStateOf<String?>(null) }
    var pendingDeleteSite by remember { mutableStateOf<BlockedWebsite?>(null) }
    var pendingBulk by remember { mutableStateOf<BulkAction?>(null) }
    var limitTarget by remember { mutableStateOf<AppRowItem?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    // One-shot entrance cascade for the persistent chrome (top bar -> tabs -> presets);
    // remembered so it runs on first composition only. Tab bodies animate via
    // AnimatedContent + animateItem instead, so tab switches never replay the cascade.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val appOverrides = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val websiteOverrides = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val appPermanentOverrides = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val websitePermanentOverrides = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    // Per-category expand/collapse. Absent = default (expanded when the category has a blocked app).
    val categoryExpansion = rememberSaveable(saver = CategoryExpansionSaver) {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }

    // Hoisted list states — never recreated, so toggles preserve scroll position.
    val appsListState = rememberLazyListState()
    val websitesListState = rememberLazyListState()

    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchActive) {
        if (searchActive) searchFocusRequester.requestFocus()
    }

    // Same behavior as the old pill selector: switching tabs clears the search.
    LaunchedEffect(selectedTab) {
        searchQuery.value = ""
        debouncedQuery = ""
    }

    // snapshotFlow keeps the read out of composition: only debouncedQuery updates below
    // trigger a screen recomposition, and only after the typing pause.
    LaunchedEffect(Unit) {
        snapshotFlow { searchQuery.value }
            .collectLatest {
                delay(250)
                debouncedQuery = it
            }
    }

    // Real installed apps from PackageManager, with explicit loading/ready/error states.
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var appsLoadState by remember { mutableStateOf<AppsLoadState>(AppsLoadState.Loading) }
    var usageMinutes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var loadAttempt by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(loadAttempt) {
        appsLoadState = AppsLoadState.Loading
        val loaded = try {
            withContext(Dispatchers.IO) {
                // First load may use the memory cache; Retry forces a fresh PM query so a
                // cached empty/partial result doesn't stick for the 30s TTL.
                InstalledAppsRepository.getInstalledLaunchableApps(
                    context,
                    forceRefresh = loadAttempt > 0
                )
            }
        } catch (e: Exception) {
            installedApps = emptyList()
            appsLoadState = AppsLoadState.Error(e.message ?: "Could not read the installed-app list")
            return@LaunchedEffect
        }
        installedApps = loaded
        appsLoadState = AppsLoadState.Ready
        // Usage stats are best-effort: missing usage access must not fail the app list.
        usageMinutes = try {
            withContext(Dispatchers.IO) {
                UsageStatsRepository.getTodaySummary(context, maxApps = 200).topApps
                    .associate { it.packageName to it.foregroundMinutes }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // Warm the shared icon LRU for the first screenfuls so badges render without a per-row
    // PM/toBitmap fetch on first scroll. Bounded (64) so the shared cache doesn't thrash.
    LaunchedEffect(installedApps) {
        installedApps.take(64).forEach { app ->
            if (InstalledAppsRepository.getCachedIconBitmap(app.packageName) == null) {
                InstalledAppsRepository.getAppIconBitmap(context, app.packageName)
            }
        }
    }

    // Hoisted stable toggle lambdas — same instance for every row, keeps rows skippable.
    // Optimistic: the row state flips immediately; on persistence failure the override is
    // dropped (revert) and a snackbar explains why.
    val onAppToggle: (AppRowItem, Boolean) -> Unit = remember(settings, scope, snackbarHostState) {
        { app, checked ->
            appOverrides.value = appOverrides.value + (app.packageName to checked)
            scope.launch {
                val ok = try {
                    settings.setAppBlockedFull(app.packageName, app.appName, app.category, checked)
                    true
                } catch (_: Exception) {
                    false
                }
                appOverrides.value = appOverrides.value - app.packageName
                if (!ok) {
                    snackbarHostState.showSnackbar("Couldn't update ${app.appName}. Change reverted.")
                }
            }
        }
    }
    val onWebsiteToggle: (String, Boolean) -> Unit = remember(settings, scope, snackbarHostState) {
        { domain, checked ->
            websiteOverrides.value = websiteOverrides.value + (domain to checked)
            scope.launch {
                val ok = try {
                    settings.setWebsiteBlocked(domain, checked)
                    true
                } catch (_: Exception) {
                    false
                }
                websiteOverrides.value = websiteOverrides.value - domain
                if (!ok) {
                    snackbarHostState.showSnackbar("Couldn't update $domain. Change reverted.")
                }
            }
        }
    }
    // Optimistic permanent-block toggles — mirror the block overrides above.
    val onAppPermanentToggle: (AppRowItem, Boolean) -> Unit = remember(settings, scope, snackbarHostState) {
        { app, permanent ->
            appPermanentOverrides.value = appPermanentOverrides.value + (app.packageName to permanent)
            scope.launch {
                val ok = try {
                    settings.setAppPermanent(app.packageName, permanent)
                    true
                } catch (_: Exception) {
                    false
                }
                appPermanentOverrides.value = appPermanentOverrides.value - app.packageName
                if (!ok) {
                    snackbarHostState.showSnackbar("Couldn't update ${app.appName}. Change reverted.")
                }
            }
        }
    }
    val onWebsitePermanentToggle: (String, Boolean) -> Unit = remember(settings, scope, snackbarHostState) {
        { domain, permanent ->
            websitePermanentOverrides.value = websitePermanentOverrides.value + (domain to permanent)
            scope.launch {
                val ok = try {
                    settings.setWebsitePermanent(domain, permanent)
                    true
                } catch (_: Exception) {
                    false
                }
                websitePermanentOverrides.value = websitePermanentOverrides.value - domain
                if (!ok) {
                    snackbarHostState.showSnackbar("Couldn't update $domain. Change reverted.")
                }
            }
        }
    }
    val onWebsiteDeleteRequest: (BlockedWebsite) -> Unit = remember(
        boundariesFrozen, deleteLockedMessage, scope, snackbarHostState
    ) {
        { site ->
            if (boundariesFrozen) {
                scope.launch { snackbarHostState.showSnackbar(deleteLockedMessage) }
            } else {
                pendingDeleteSite = site
            }
        }
    }
    val onLimitClick: (AppRowItem) -> Unit = remember(
        boundariesFrozen, limitLockedMessage, scope, snackbarHostState
    ) {
        { app ->
            if (boundariesFrozen && app.isBlocked) {
                scope.launch {
                    snackbarHostState.showSnackbar(limitLockedMessage)
                }
            } else {
                limitTarget = app
            }
        }
    }

    // Merge stored block-state with installed apps + defaults for not-yet-installed known apps.
    // Stable order: installed first (alphabetical), uninstalled at the bottom (alphabetical).
    // Optimistic overrides are deliberately NOT merged here: toggling one switch must not
    // rebuild all 300+ rows. They are applied per item in the LazyColumn lambda below instead.
    val mergedAppRows: List<AppRowItem> = remember(
        blockedApps, installedApps, usageMinutes, appsStorageLoaded, appsLoadState
    ) {
        val blockedByPkg = blockedApps.associateBy { it.packageName }
        val installedByPkg = installedApps.associateBy { it.packageName }
        val appsReady = appsLoadState is AppsLoadState.Ready
        val rows = mutableListOf<AppRowItem>()

        // 1. All installed apps (the real picker)
        for (inst in installedApps) {
            val stored = blockedByPkg[inst.packageName]
            val defaultBlocked = DEFAULT_DOOMSCROLL_BY_PACKAGE[inst.packageName]?.isBlocked ?: false
            rows.add(
                AppRowItem(
                    packageName = inst.packageName,
                    appName = inst.appName,
                    category = stored?.category ?: inst.category,
                    isBlocked = stored?.isBlocked
                        ?: (if (appsStorageLoaded) defaultBlocked else false),
                    isInstalled = true,
                    todayMinutes = usageMinutes[inst.packageName] ?: 0L,
                    isPermanent = stored?.isPermanent ?: false
                )
            )
        }
        // 2. Known doomscroll apps not installed (so user sees what's missing) at the bottom.
        // Only after storage + PackageManager have both reported, so nothing flickers.
        if (appsStorageLoaded && appsReady) {
            for (def in BlockedApp.DEFAULT_DOOMSCROLL_APPS) {
                if (!installedByPkg.containsKey(def.packageName)) {
                    val stored = blockedByPkg[def.packageName]
                    rows.add(
                        AppRowItem(
                            packageName = def.packageName,
                            appName = def.appName,
                            category = stored?.category ?: def.category,
                            isBlocked = stored?.isBlocked ?: def.isBlocked,
                            isInstalled = false,
                            todayMinutes = 0L,
                            isPermanent = stored?.isPermanent ?: def.isPermanent
                        )
                    )
                }
            }
        }
        // Stable alphabetical sort only: installed first, then label. Blocked/usage
        // must NOT affect order so toggles never reshuffle or reset scroll.
        rows.sortedWith(
            compareBy<AppRowItem> { !it.isInstalled }
                .thenBy { it.appName.lowercase() }
        )
    }

    // Stale blocked entries (uninstalled, not in defaults) are hidden from the main
    // list — surfaced only as a count note so they never show as installed.
    val staleUninstalledBlockedCount = remember(blockedApps, installedApps, appsLoadState, appsStorageLoaded) {
        if (!appsStorageLoaded || appsLoadState !is AppsLoadState.Ready) 0 else {
            val installedSet = installedApps.map { it.packageName }.toSet()
            val defaultPkgs = DEFAULT_DOOMSCROLL_BY_PACKAGE.keys
            blockedApps.count { it.isBlocked && it.packageName !in installedSet && it.packageName !in defaultPkgs }
        }
    }

    // Installed/system sets derived once per load — reused by filters and rows.
    val systemSet = remember(installedApps) {
        installedApps.filter { it.isSystem }.map { it.packageName }.toSet()
    }

    // Search ranking: exact > startsWith > word-start > contains, then installed-first,
    // then alphabetical. derivedStateOf so the list recomputes only when inputs change.
    val rankedApps: List<AppRowItem> by remember(
        mergedAppRows, debouncedQuery, showSystemApps, systemSet
    ) {
        derivedStateOf {
            val query = debouncedQuery.trim()
            mergedAppRows
                .asSequence()
                .filter { row ->
                    showSystemApps || row.packageName !in systemSet || row.isBlocked
                }
                .mapNotNull { row ->
                    val tier = matchTier(row, query)
                    if (tier < 0) null else row to tier
                }
                .sortedWith(
                    compareBy<Pair<AppRowItem, Int>>(
                        { it.second },
                        { !it.first.isInstalled },
                        { it.first.appName.lowercase() }
                    )
                )
                .map { it.first }
                .toList()
        }
    }

    // Per-category totals ignoring the search query (so headers can say "12 of 34").
    val totalByCategory = remember(mergedAppRows, showSystemApps, systemSet) {
        mergedAppRows
            .filter { showSystemApps || it.packageName !in systemSet || it.isBlocked }
            .groupingBy { it.category }
            .eachCount()
    }

    // Visible categories only: empty groups disappear during search, "Other" sorts last.
    val groups: List<CategoryGroup> = remember(rankedApps, totalByCategory, debouncedQuery) {
        val built = rankedApps
            .groupBy { it.category } // LinkedHashMap: preserves first-encounter order
            .map { (category, apps) ->
                CategoryGroup(
                    category = category,
                    apps = apps,
                    totalCount = totalByCategory[category] ?: apps.size,
                    blockedCount = apps.count { it.isBlocked }
                )
            }
        if (debouncedQuery.isBlank()) {
            built.sortedWith(
                compareBy<CategoryGroup>(
                    { it.category == "Other" },
                    { it.category.lowercase() }
                )
            )
        } else {
            // While searching, keep first-encounter order: the category holding the
            // best-ranked match (e.g. an app literally named "X") renders first
            // instead of being buried under alphabetically earlier categories.
            built
        }
    }

    // Apply optimistic website overrides before filtering so the switch/lock flips instantly.
    val websiteOverrideMap = websiteOverrides.value
    val websitePermanentOverrideMap = websitePermanentOverrides.value
    val visibleWebsites: List<BlockedWebsite> = remember(
        blockedWebsites, websiteOverrideMap, websitePermanentOverrideMap
    ) {
        if (websiteOverrideMap.isEmpty() && websitePermanentOverrideMap.isEmpty()) blockedWebsites
        else blockedWebsites.map { site ->
            val override = websiteOverrideMap[site.domain]
            val permanentOverride = websitePermanentOverrideMap[site.domain]
            val blockChanged = override != null && override != site.isBlocked
            val permanentChanged = permanentOverride != null && permanentOverride != site.isPermanent
            if (blockChanged || permanentChanged) {
                site.copy(
                    isBlocked = override ?: site.isBlocked,
                    isPermanent = permanentOverride ?: site.isPermanent
                )
            } else site
        }
    }
    val filteredWebsites: List<BlockedWebsite> by remember(visibleWebsites, debouncedQuery) {
        derivedStateOf {
            if (debouncedQuery.isBlank()) visibleWebsites
            else visibleWebsites.filter {
                it.domain.contains(debouncedQuery, ignoreCase = true) ||
                    it.displayName.contains(debouncedQuery, ignoreCase = true)
            }
        }
    }

    val clearAppSearch: () -> Unit = {
        searchQuery.value = ""
        debouncedQuery = ""
    }

    // ---- Bulk presets (confirmation required; 0 matches = explanatory message) ----

    val blockAllSocialApps: () -> Unit = {
        val inCategory = mergedAppRows.filter {
            it.isInstalled && (it.category == "Social" || it.category == "Social Media")
        }
        val targets = inCategory.filter { !it.isBlocked }
        when {
            inCategory.isEmpty() -> scope.launch {
                snackbarHostState.showSnackbar("No Social apps installed on this device.")
            }
            targets.isEmpty() -> scope.launch {
                snackbarHostState.showSnackbar("All ${inCategory.size} Social app(s) are already blocked.")
            }
            else -> pendingBulk = BulkAction(
                title = "Block Social apps?",
                message = "This blocks ${targets.size} app(s): ${summarizeNames(targets.map { it.appName })}.",
                confirmLabel = "Block ${targets.size}",
                onConfirm = {
                    scope.launch {
                        val ok = try {
                            settings.setAppsBlockedFullBatch(
                                targets.map {
                                    SettingsRepository.AppBlockUpdate(
                                        it.packageName, it.appName, it.category, true
                                    )
                                }
                            )
                            true
                        } catch (_: Exception) {
                            false
                        }
                        snackbarHostState.showSnackbar(
                            if (ok) "Blocked ${targets.size} Social app(s)."
                            else "Couldn't block those apps. Nothing changed."
                        )
                    }
                }
            )
        }
    }

    val blockAllVideoApps: () -> Unit = {
        val inCategory = mergedAppRows.filter { it.isInstalled && it.category == "Entertainment" }
        val targets = inCategory.filter { !it.isBlocked }
        when {
            inCategory.isEmpty() -> scope.launch {
                snackbarHostState.showSnackbar("No video/entertainment apps installed on this device.")
            }
            targets.isEmpty() -> scope.launch {
                snackbarHostState.showSnackbar("All ${inCategory.size} entertainment app(s) are already blocked.")
            }
            else -> pendingBulk = BulkAction(
                title = "Block Video apps?",
                message = "This blocks ${targets.size} app(s): ${summarizeNames(targets.map { it.appName })}.",
                confirmLabel = "Block ${targets.size}",
                onConfirm = {
                    scope.launch {
                        val ok = try {
                            settings.setAppsBlockedFullBatch(
                                targets.map {
                                    SettingsRepository.AppBlockUpdate(
                                        it.packageName, it.appName, it.category, true
                                    )
                                }
                            )
                            true
                        } catch (_: Exception) {
                            false
                        }
                        snackbarHostState.showSnackbar(
                            if (ok) "Blocked ${targets.size} entertainment app(s)."
                            else "Couldn't block those apps. Nothing changed."
                        )
                    }
                }
            )
        }
    }

    val unblockAllApps: () -> Unit = {
        if (boundariesFrozen) {
            // Chip is disabled while frozen; this is defense-in-depth for programmatic calls.
            scope.launch { snackbarHostState.showSnackbar(removalLockedMessage) }
        } else {
            val targets = blockedApps.filter { it.isBlocked }
            if (targets.isEmpty()) {
                scope.launch { snackbarHostState.showSnackbar("No blocked apps to unblock.") }
            } else {
                pendingBulk = BulkAction(
                    title = "Unblock all apps?",
                    message = "This unblocks ${targets.size} app(s): ${summarizeNames(targets.map { it.appName })}. They will be usable immediately.",
                    confirmLabel = "Unblock ${targets.size}",
                    isDestructive = true,
                    onConfirm = {
                        scope.launch {
                            val ok = try {
                                settings.setAppsBlockedBatch(
                                    targets.associate { it.packageName to false }
                                )
                                true
                            } catch (_: Exception) {
                                false
                            }
                            snackbarHostState.showSnackbar(
                                if (ok) "Unblocked ${targets.size} app(s)."
                                else "Couldn't unblock those apps. Nothing changed."
                            )
                        }
                    }
                )
            }
        }
    }

    val blockSocialSites: () -> Unit = {
        val inCategory = blockedWebsites.filter {
            it.category == "Social" || it.category == "Social Media"
        }
        val targets = inCategory.filter { !it.isBlocked }
        when {
            inCategory.isEmpty() -> scope.launch {
                snackbarHostState.showSnackbar("No Social websites in your list.")
            }
            targets.isEmpty() -> scope.launch {
                snackbarHostState.showSnackbar("All ${inCategory.size} Social site(s) are already blocked.")
            }
            else -> pendingBulk = BulkAction(
                title = "Block Social sites?",
                message = "This blocks ${targets.size} site(s): ${summarizeNames(targets.map { it.domain })}.",
                confirmLabel = "Block ${targets.size}",
                onConfirm = {
                    scope.launch {
                        val ok = try {
                            settings.setWebsitesBlockedBatch(targets.associate { it.domain to true })
                            true
                        } catch (_: Exception) {
                            false
                        }
                        snackbarHostState.showSnackbar(
                            if (ok) "Blocked ${targets.size} Social site(s)."
                            else "Couldn't block those sites. Nothing changed."
                        )
                    }
                }
            )
        }
    }

    val blockVideoSites: () -> Unit = {
        val inCategory = blockedWebsites.filter { it.category == "Entertainment" }
        val targets = inCategory.filter { !it.isBlocked }
        when {
            inCategory.isEmpty() -> scope.launch {
                snackbarHostState.showSnackbar("No entertainment websites in your list.")
            }
            targets.isEmpty() -> scope.launch {
                snackbarHostState.showSnackbar("All ${inCategory.size} entertainment site(s) are already blocked.")
            }
            else -> pendingBulk = BulkAction(
                title = "Block Video sites?",
                message = "This blocks ${targets.size} site(s): ${summarizeNames(targets.map { it.domain })}.",
                confirmLabel = "Block ${targets.size}",
                onConfirm = {
                    scope.launch {
                        val ok = try {
                            settings.setWebsitesBlockedBatch(targets.associate { it.domain to true })
                            true
                        } catch (_: Exception) {
                            false
                        }
                        snackbarHostState.showSnackbar(
                            if (ok) "Blocked ${targets.size} video site(s)."
                            else "Couldn't block those sites. Nothing changed."
                        )
                    }
                }
            )
        }
    }

    val unblockAllSites: () -> Unit = {
        if (boundariesFrozen) {
            // Chip is disabled while frozen; this is defense-in-depth for programmatic calls.
            scope.launch { snackbarHostState.showSnackbar(removalLockedMessage) }
        } else {
            val targets = blockedWebsites.filter { it.isBlocked }
            if (targets.isEmpty()) {
                scope.launch { snackbarHostState.showSnackbar("No blocked websites to unblock.") }
            } else {
                pendingBulk = BulkAction(
                    title = "Unblock all websites?",
                    message = "This unblocks ${targets.size} site(s): ${summarizeNames(targets.map { it.domain })}.",
                    confirmLabel = "Unblock ${targets.size}",
                    isDestructive = true,
                    onConfirm = {
                        scope.launch {
                            val ok = try {
                                settings.setWebsitesBlockedBatch(targets.associate { it.domain to false })
                                true
                            } catch (_: Exception) {
                                false
                            }
                            snackbarHostState.showSnackbar(
                                if (ok) "Unblocked ${targets.size} site(s)."
                                else "Couldn't unblock those sites. Nothing changed."
                            )
                        }
                    }
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = UiTokens.ScreenPadding)
        ) {
            Spacer(Modifier.height(4.dp))

            // TopAppBar-style header: back arrow + title + search action.
            StaggeredFadeSlide(visible = entered, index = 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to boundaries",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = if (selectedTab == PickerTab.APPLICATIONS) "Applications" else "Websites",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            searchActive = !searchActive
                            if (!searchActive) clearAppSearch()
                        }
                    ) {
                        Icon(
                            imageVector = if (searchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = if (searchActive) "Close search" else "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (searchActive) {
                // Search field owns its state read so typing doesn't recompose the list.
                SearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery.value = it },
                    placeholder = if (selectedTab == PickerTab.APPLICATIONS) {
                        "Search installed apps…"
                    } else {
                        "Search websites…"
                    },
                    focusRequester = searchFocusRequester
                )
                Spacer(Modifier.height(8.dp))
            }

            // Seamless M3 tabs (no segmented pills, no divider line).
            StaggeredFadeSlide(visible = entered, index = 1) {
                PrimaryTabRow(
                    selectedTabIndex = if (selectedTab == PickerTab.APPLICATIONS) 0 else 1,
                    containerColor = Color.Transparent,
                    divider = {}
                ) {
                    Tab(
                        selected = selectedTab == PickerTab.APPLICATIONS,
                        onClick = { onTabChange(PickerTab.APPLICATIONS) },
                        text = { Text("Applications") }
                    )
                    Tab(
                        selected = selectedTab == PickerTab.WEBSITES,
                        onClick = { onTabChange(PickerTab.WEBSITES) },
                        text = { Text("Websites") }
                    )
                }
            }

            // Tab bodies crossfade with a subtle slide; hoisted list states survive
            // the transition, so scroll position is preserved across tab switches.
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(animationSpec = MotionTokens.FadeFloat) +
                        slideInVertically(
                            animationSpec = MotionTokens.SpatialOffset,
                            initialOffsetY = { it / 12 }
                        )) togetherWith fadeOut(animationSpec = MotionTokens.FadeFloat)
                },
                label = "pickerTabs"
            ) { tab ->
                when (tab) {
                PickerTab.APPLICATIONS -> {
                    // Single Column child: AnimatedContent stacks multiple top-level children
                    // like a Box, so presets + count + list must share one Column. The chips
                    // strip stays pinned above the list with an opaque background.
                    Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(12.dp))

                    // Quick presets in a horizontally scrollable row (filled tonal, no borders).
                    // The parent owns the 16dp start alignment; the end padding lets the last
                    // chip scroll fully into view instead of vanishing under the parent edge.
                    // Opaque background so scrolling content never shows through the strip.
                    LazyRow(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background),
                        contentPadding = PaddingValues(end = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            TonalActionChip(
                                label = "Block social",
                                icon = Icons.Rounded.Share,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                onClick = blockAllSocialApps,
                                modifier = Modifier.animateItem()
                            )
                        }
                        item {
                            TonalActionChip(
                                label = "Block video",
                                icon = Icons.Rounded.PlayArrow,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                onClick = blockAllVideoApps,
                                modifier = Modifier.animateItem()
                            )
                        }
                        item {
                            TonalActionChip(
                                label = "Unblock all",
                                icon = Icons.Rounded.LockOpen,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                enabled = !boundariesFrozen,
                                onClick = unblockAllApps,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    val loadState = appsLoadState
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = MotionTokens.SpatialIntSize),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                loadState is AppsLoadState.Loading -> "Loading installed apps…"
                                loadState is AppsLoadState.Error -> "Couldn't load installed apps"
                                appsStorageLoaded -> if (rankedApps.size == mergedAppRows.size) {
                                    "Showing ${mergedAppRows.size} apps"
                                } else {
                                    "Showing ${rankedApps.size} of ${mergedAppRows.size} apps"
                                }
                                else -> "Loading boundaries…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "System",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                            modifier = Modifier.semantics { contentDescription = "Show system apps" }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Weighted box owns the remaining space: loading/error/empty states and
                    // the list all fill exactly this region, never the chips strip above.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                    when {
                        loadState is AppsLoadState.Loading -> {
                            LoadingState("Loading installed apps…")
                        }
                        loadState is AppsLoadState.Error -> {
                            AppsErrorState(message = loadState.message, onRetry = { loadAttempt++ })
                        }
                        installedApps.isEmpty() -> {
                            ListStateMessage(
                                message = "No launchable apps found on this device. If apps just finished installing, retry to refresh the list.",
                                actionLabel = "Retry",
                                onAction = { loadAttempt++ }
                            )
                        }
                        else -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                            if (staleUninstalledBlockedCount > 0) {
                                Text(
                                    text = "$staleUninstalledBlockedCount blocked app(s) not currently installed — hidden from this list.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            if (rankedApps.isEmpty()) {
                                ListStateMessage(
                                    message = "No apps match. Try a different search.",
                                    actionLabel = "Clear search",
                                    onAction = clearAppSearch
                                )
                            } else {
                                val isSearching = debouncedQuery.isNotBlank()
                                LazyColumn(
                                    state = appsListState,
                                    verticalArrangement = Arrangement.spacedBy(UiTokens.ItemGap),
                                    contentPadding = PaddingValues(bottom = 24.dp),
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                ) {
                                    groups.forEach { group ->
                                        // During search every matching category is expanded;
                                        // otherwise the remembered override wins, defaulting to
                                        // expanded when the category holds a blocked app.
                                        val expanded = isSearching ||
                                            (categoryExpansion.value[group.category]
                                                ?: (group.blockedCount > 0))
                                        item(
                                            key = "category-${group.category}",
                                            contentType = "categoryHeader"
                                        ) {
                                            CategoryHeader(
                                                group = group,
                                                expanded = expanded,
                                                isSearching = isSearching,
                                                modifier = Modifier.animateItem(),
                                                onToggle = {
                                                    categoryExpansion.value =
                                                        categoryExpansion.value +
                                                            (group.category to !expanded)
                                                }
                                            )
                                        }
                                        if (expanded) {
                                            items(
                                                items = group.apps,
                                                key = { it.packageName },
                                                contentType = { "app" }
                                            ) { app ->
                                                // Optimistic overrides are applied per item and
                                                // remembered here, so one toggle invalidates only
                                                // that row.
                                                val override = appOverrides.value[app.packageName]
                                                val permanentOverride =
                                                    appPermanentOverrides.value[app.packageName]
                                                val effectiveApp = remember(app, override, permanentOverride) {
                                                    app.copy(
                                                        isBlocked = override ?: app.isBlocked,
                                                        isPermanent = permanentOverride ?: app.isPermanent
                                                    )
                                                }
                                                InstalledAppRow(
                                                    app = effectiveApp,
                                                    limitMinutes = limits[app.packageName]?.dailyMinutes
                                                        ?.takeIf { it > 0 },
                                                    onToggle = onAppToggle,
                                                    onPermanentToggle = {
                                                        onAppPermanentToggle(
                                                            effectiveApp,
                                                            !effectiveApp.isPermanent
                                                        )
                                                    },
                                                    onLimitClick = onLimitClick,
                                                    modifier = Modifier.animateItem(),
                                                    boundariesFrozen = boundariesFrozen,
                                                    lockedMessage = removalLockedMessage
                                                )
                                            }
                                        }
                                    }
                                }
                            } // else (list)
                            } // Column (stale note + list)
                        } // else -> (content ready)
                    } // when (load state)
                    } // Box (weighted content region)
                    } // Column (tab body)
                }

                PickerTab.WEBSITES -> {
                    // Same single-Column treatment as APPLICATIONS: AnimatedContent stacks
                    // siblings, so the header/presets/list share one Column here too.
                    Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = MotionTokens.SpatialIntSize),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (websitesStorageLoaded) {
                                if (filteredWebsites.size == visibleWebsites.size) {
                                    "Showing ${visibleWebsites.size} sites"
                                } else {
                                    "Showing ${filteredWebsites.size} of ${visibleWebsites.size} sites"
                                }
                            } else {
                                "Loading website boundaries…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TonalActionChip(
                            label = "Add website",
                            icon = Icons.Rounded.Add,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            onClick = {
                                websiteInputError = null
                                showAddWebsiteDialog = true
                            }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Website presets (filled tonal, no borders). Same end padding as the
                    // apps preset row so the trailing chip scrolls fully into view.
                    // Opaque background so scrolling content never shows through the strip.
                    LazyRow(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background),
                        contentPadding = PaddingValues(end = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            TonalActionChip(
                                label = "Block social",
                                icon = Icons.Rounded.Share,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                onClick = blockSocialSites,
                                modifier = Modifier.animateItem()
                            )
                        }
                        item {
                            TonalActionChip(
                                label = "Block video",
                                icon = Icons.Rounded.PlayArrow,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                onClick = blockVideoSites,
                                modifier = Modifier.animateItem()
                            )
                        }
                        item {
                            TonalActionChip(
                                label = "Unblock all",
                                icon = Icons.Rounded.LockOpen,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                enabled = !boundariesFrozen,
                                onClick = unblockAllSites,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Weighted box owns the remaining space (same as APPLICATIONS above).
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                    when {
                        !websitesStorageLoaded -> LoadingState("Loading website boundaries…")
                        visibleWebsites.isEmpty() -> ListStateMessage(
                            message = "No websites yet. Add a domain or use a preset above.",
                            actionLabel = "Add Website",
                            onAction = {
                                websiteInputError = null
                                showAddWebsiteDialog = true
                            }
                        )
                        filteredWebsites.isEmpty() -> ListStateMessage(
                            message = "No websites match \"$debouncedQuery\".",
                            actionLabel = "Clear search",
                            onAction = clearAppSearch
                        )
                        else -> LazyColumn(
                            state = websitesListState,
                            verticalArrangement = Arrangement.spacedBy(UiTokens.ItemGap),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filteredWebsites, key = { it.domain }) { site ->
                                WebsiteRow(
                                    site = site,
                                    onToggle = onWebsiteToggle,
                                    onPermanentToggle = {
                                        onWebsitePermanentToggle(site.domain, !site.isPermanent)
                                    },
                                    onDeleteRequest = onWebsiteDeleteRequest,
                                    modifier = Modifier.animateItem(),
                                    boundariesFrozen = boundariesFrozen,
                                    lockedMessage = removalLockedMessage
                                )
                            }
                        }
                    } // when (websites load state)
                    } // Box (weighted content region)
                    } // Column (tab body)
                }
            } // when(tab)
        } // AnimatedContent
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    // Dialog: Add Custom Website
    if (showAddWebsiteDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddWebsiteDialog = false
                websiteInputError = null
            },
            title = {
                Text(
                    "Block Website",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the domain or URL you want to block in Chrome, Brave, Samsung Internet, and other browsers:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newWebsiteInput,
                        onValueChange = {
                            newWebsiteInput = it
                            websiteInputError = null
                        },
                        placeholder = { Text("e.g. news.ycombinator.com") },
                        singleLine = true,
                        isError = websiteInputError != null,
                        supportingText = {
                            websiteInputError?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Only the hostname is saved — schemes, paths, and ports are trimmed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleaned = normalizeDomainInput(newWebsiteInput)
                        when {
                            cleaned == null -> websiteInputError = "Enter a valid domain, like example.com"
                            blockedWebsites.any { it.domain.equals(cleaned, ignoreCase = true) } ->
                                websiteInputError = "$cleaned is already in your list"
                            else -> scope.launch {
                                val ok = try {
                                    settings.addCustomWebsite(cleaned)
                                } catch (_: Exception) {
                                    false
                                }
                                if (ok) {
                                    newWebsiteInput = ""
                                    websiteInputError = null
                                    showAddWebsiteDialog = false
                                    snackbarHostState.showSnackbar("Added $cleaned to blocked websites")
                                } else {
                                    websiteInputError = "Couldn't add that domain — try another"
                                }
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddWebsiteDialog = false
                    websiteInputError = null
                }) {
                    Text("Cancel")
                }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    // Dialog: confirm custom website delete (destructive action)
    pendingDeleteSite?.let { site ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSite = null },
            title = {
                Text(
                    "Remove ${site.domain}?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = { Text("This removes it from your website boundaries. You can add it again anytime.") },
            confirmButton = {
                TextButton(onClick = {
                    val domain = site.domain
                    pendingDeleteSite = null
                    scope.launch {
                        val ok = try {
                            settings.removeCustomWebsite(domain)
                            true
                        } catch (_: Exception) {
                            false
                        }
                        snackbarHostState.showSnackbar(
                            if (ok) "Removed $domain" else "Couldn't remove $domain."
                        )
                    }
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSite = null }) { Text("Cancel") }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    // Dialog: bulk preset confirmation (shows exactly how many rows are affected)
    pendingBulk?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingBulk = null },
            title = {
                Text(
                    action.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(onClick = {
                    pendingBulk = null
                    action.onConfirm()
                }) {
                    Text(
                        action.confirmLabel,
                        color = if (action.isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulk = null }) { Text("Cancel") }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    // Dialog: per-app daily limit
    limitTarget?.let { app ->
        AppLimitDialog(
            app = app,
            existingLimit = limits[app.packageName],
            onDismiss = { limitTarget = null },
            onSave = { minutes ->
                limitTarget = null
                scope.launch {
                    val ok = try {
                        appLimits.setLimit(app.packageName, minutes)
                        true
                    } catch (_: Exception) {
                        false
                    }
                    snackbarHostState.showSnackbar(
                        if (ok) "Daily limit set: ${minutes}m for ${app.appName}"
                        else "Couldn't save the limit for ${app.appName}."
                    )
                }
            },
            onRemove = {
                limitTarget = null
                scope.launch {
                    val ok = try {
                        appLimits.removeLimit(app.packageName)
                        true
                    } catch (_: Exception) {
                        false
                    }
                    snackbarHostState.showSnackbar(
                        if (ok) "Daily limit removed for ${app.appName}"
                        else "Couldn't remove the limit for ${app.appName}."
                    )
                }
            }
        )
    }
}

/**
 * Search input extracted from [AppPickerScreen]. [query] is passed as a state object
 * rather than a String so the value read happens here — only this field recomposes per
 * keystroke, not the screen root or the filtered app list. Filled container, no outline.
 */
@Composable
private fun SearchField(
    query: MutableState<String>,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester
) {
    TextField(
        value = query.value,
        onValueChange = onQueryChange,
        placeholder = {
            Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingIcon = {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.value.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
    )
}

@Composable
private fun LoadingState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppsErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
            Text(
                "Couldn't load installed apps",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onRetry, shape = MaterialTheme.shapes.medium) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun ListStateMessage(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * Collapsible category section header. Styled like the shared SectionHeader (small
 * semibold onSurfaceVariant label) but it owns a 48dp tap target, a rotating chevron
 * and the count that shows "X of Y" while searching or "N blocked" when the category
 * has blocked apps. The extra 16dp top padding spaces groups apart without inflating
 * the 10dp gap between rows inside a group.
 */
@Composable
private fun CategoryHeader(
    group: CategoryGroup,
    expanded: Boolean,
    isSearching: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "categoryChevron"
    )
    val pressInteraction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(pressScaleModifier(pressInteraction))
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = pressInteraction,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onToggle
                )
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) {
                    "Collapse ${group.category}"
                } else {
                    "Expand ${group.category}"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevronRotation)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = group.category,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.1.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = when {
                    isSearching -> "${group.apps.size} of ${group.totalCount}"
                    group.blockedCount == 1 -> "1 blocked"
                    group.blockedCount > 1 -> "${group.blockedCount} blocked"
                    group.totalCount == 1 -> "1 app"
                    else -> "${group.totalCount} apps"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (group.blockedCount > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1
            )
        }
    }
}

/**
 * Website row: same treatment as app rows — toggleable tonal card, [IconBadge] squircle
 * for the domain glyph, single-line ellipsized metadata, a quiet middle cluster (delete
 * for customs, permanent lock) and one strong trailing control (the Switch). The switch
 * sits in a plain Box while unfrozen so taps fall through to the row toggleable exactly
 * once; the Box only becomes clickable while frozen, to surface the refusal message.
 * Inner icon buttons keep their own onClick and consume the tap without toggling.
 */
@Composable
private fun WebsiteRow(
    site: BlockedWebsite,
    onToggle: (String, Boolean) -> Unit,
    onPermanentToggle: () -> Unit,
    onDeleteRequest: (BlockedWebsite) -> Unit,
    modifier: Modifier = Modifier,
    boundariesFrozen: Boolean = false,
    lockedMessage: String = ""
) {
    val context = LocalContext.current
    val switchEnabled = !(boundariesFrozen && site.isBlocked)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (site.isBlocked) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = site.isBlocked,
                enabled = switchEnabled,
                role = Role.Switch,
                onValueChange = { checked -> onToggle(site.domain, checked) }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(
                icon = Icons.Rounded.Language,
                size = UiTokens.IconTileSize,
                containerColor = if (site.isBlocked) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (site.isBlocked) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = site.displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        if (site.isPermanent) append("Always blocked · ")
                        append(site.domain)
                        append(" · ")
                        append(if (site.isBlocked) "Blocked" else "Allowed")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (site.isCustom) {
                IconButton(onClick = { onDeleteRequest(site) }) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Remove ${site.domain}",
                        // Quiet row affordance; the confirmation dialog carries the
                        // destructive weight. Strict-mode gating is unchanged.
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            PermanentLockButton(
                isPermanent = site.isPermanent,
                label = site.displayName,
                onClick = onPermanentToggle,
                enabled = !boundariesFrozen
            )

            // Plain Box while unfrozen so switch-area taps fall through to the row
            // toggleable exactly once (a disabled clickable would still swallow them).
            // Only while frozen does the Box become clickable, to surface the refusal.
            Box(
                modifier = if (switchEnabled) {
                    Modifier
                } else {
                    Modifier.clickable {
                        Toast.makeText(
                            context,
                            lockedMessage,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            ) {
                Switch(
                    checked = site.isBlocked,
                    onCheckedChange = null,
                    enabled = switchEnabled,
                    thumbContent = if (site.isBlocked) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    } else null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "${site.displayName} website block toggle"
                    }
                )
            }
        }
    }
}

/**
 * Compact always-block lock toggle used by both app and website rows. Deliberately
 * low-emphasis: no container, just an onSurfaceVariant 20dp glyph inside a 48dp touch
 * target (primary tint only when the lock is active). Disabled while boundaries are
 * frozen (Boundaries Lock or Strict Mode).
 */
@Composable
private fun PermanentLockButton(
    isPermanent: Boolean,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        isPermanent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .semantics {
                contentDescription = if (isPermanent) {
                    "Stop always blocking $label"
                } else {
                    "Always block $label"
                }
            }
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * App row: the whole card is a switch ([toggleable] with [Role.Switch]) so a tap anywhere
 * toggles blocking; the trailing [Switch] is the row's one strong control. The switch
 * sits in a plain Box while unfrozen so taps on the track/thumb fall through to the row
 * toggleable exactly once; the Box only becomes clickable while frozen, to surface the
 * refusal message. Leading [AppIconTileForPackage] is a seamless 40dp squircle (no
 * container/ring); the middle cluster ("+ Limit" text action and the always-block lock)
 * stays quiet and aligned so it never competes with the switch. Inner icon buttons keep
 * their own onClick and consume the tap without toggling. Metadata is a single
 * ellipsized line.
 */
@Composable
private fun InstalledAppRow(
    app: AppRowItem,
    limitMinutes: Int?,
    onToggle: (AppRowItem, Boolean) -> Unit,
    onPermanentToggle: () -> Unit,
    onLimitClick: (AppRowItem) -> Unit,
    modifier: Modifier = Modifier,
    boundariesFrozen: Boolean = false,
    lockedMessage: String = ""
) {
    val context = LocalContext.current
    val switchEnabled = !(boundariesFrozen && app.isBlocked)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (app.isBlocked) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = app.isBlocked,
                enabled = switchEnabled,
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
                    // Status beats category: an uninstalled row shows "Not installed"
                    // alone so the status word can never be elided mid-word.
                    text = if (!app.isInstalled) {
                        "Not installed"
                    } else buildString {
                        if (app.isPermanent) append("Always blocked · ")
                        append(app.category)
                        if (app.todayMinutes > 0) append(" · ${app.todayMinutes}m today")
                        if (limitMinutes != null) append(" · ${limitMinutes}m limit")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            LimitTextButton(
                appName = app.appName,
                limitMinutes = limitMinutes,
                usedMinutes = app.todayMinutes,
                onClick = { onLimitClick(app) }
            )

            PermanentLockButton(
                isPermanent = app.isPermanent,
                label = app.appName,
                onClick = onPermanentToggle,
                enabled = !boundariesFrozen
            )

            // Plain Box while unfrozen so switch-area taps fall through to the row
            // toggleable exactly once (a disabled clickable would still swallow them).
            // Only while frozen does the Box become clickable, to surface the refusal.
            Box(
                modifier = if (switchEnabled) {
                    Modifier
                } else {
                    Modifier.clickable {
                        Toast.makeText(
                            context,
                            lockedMessage,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            ) {
                Switch(
                    checked = app.isBlocked,
                    onCheckedChange = null,
                    enabled = switchEnabled,
                    thumbContent = if (app.isBlocked) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    } else null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = buildString {
                            append(app.appName)
                            append(" block toggle")
                            if (!app.isInstalled) append(", app not installed")
                        }
                    }
                )
            }
        }
    }
}

/**
 * Quiet daily-limit action in the row's middle cluster: a borderless text button
 * (onSurfaceVariant, 14sp) that reads "+ Limit" when unset, "30m" when set, and turns
 * error-tinted once the limit is reached. 48dp touch target, aligned with the lock
 * toggle next to it.
 */
@Composable
private fun LimitTextButton(
    appName: String,
    limitMinutes: Int?,
    usedMinutes: Long,
    onClick: () -> Unit
) {
    val overLimit = limitMinutes != null && usedMinutes >= limitMinutes
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (overLimit) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = when {
                    limitMinutes == null -> "Set a daily limit for $appName"
                    overLimit -> "$appName daily limit ${limitMinutes}m, limit reached"
                    else -> "$appName daily limit ${limitMinutes}m"
                }
            }
    ) {
        Text(
            text = if (limitMinutes == null) "+ Limit" else "${limitMinutes}m",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

/**
 * Preset action chip: AssistChip with an explicitly null border and tonal colors so it
 * never picks up the default outlined FilterChip/SuggestionChip border.
 */
@Composable
private fun TonalActionChip(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = contentColor,
            leadingIconContentColor = contentColor
        ),
        border = null,
        shape = RoundedCornerShape(50)
    )
}

/** Filled tonal choice chip used by the limit dialog presets (replaces bordered FilterChip). */
@Composable
private fun TonalChoiceChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = if (selected) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        } else {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        border = null,
        shape = RoundedCornerShape(50)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppLimitDialog(
    app: AppRowItem,
    existingLimit: AppLimit?,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onRemove: () -> Unit
) {
    var input by remember(app.packageName) {
        mutableStateOf(existingLimit?.dailyMinutes?.takeIf { it > 0 }?.toString() ?: "")
    }
    val parsed = input.trim().toIntOrNull()
    val isValid = parsed != null && parsed in 1..1440

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Daily limit — ${app.appName}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    buildString {
                        append(
                            if (app.isInstalled) {
                                "FocusLock blocks ${app.appName} after this much use each day."
                            } else {
                                "${app.appName} isn't installed right now — the limit applies when you reinstall it."
                            }
                        )
                        append(" Used today: ${app.todayMinutes}m.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 60, 120).forEach { preset ->
                        TonalChoiceChip(
                            selected = parsed == preset,
                            label = "${preset}m",
                            onClick = { input = preset.toString() }
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { new -> input = new.filter(Char::isDigit).take(4) },
                    label = { Text("Daily minutes") },
                    singleLine = true,
                    isError = input.isNotBlank() && !isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        if (input.isNotBlank() && !isValid) {
                            Text(
                                "Enter a number from 1 to 1440",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Once the limit is reached the app is blocked for the rest of the day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { parsed?.let(onSave) },
                enabled = isValid,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(if (existingLimit != null) "Update limit" else "Set limit")
            }
        },
        dismissButton = {
            Row {
                if (existingLimit != null) {
                    TextButton(onClick = onRemove) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        shape = MaterialTheme.shapes.large
    )
}
