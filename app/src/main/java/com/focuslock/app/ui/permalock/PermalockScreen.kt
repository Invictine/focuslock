package com.focuslock.app.ui.permalock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.repository.PermanentBlocksRepository
import com.focuslock.app.data.repository.PermanentWebsitePolicy
import com.focuslock.app.service.InstalledApp
import com.focuslock.app.service.InstalledAppsRepository
import com.focuslock.app.ui.components.AppIconTileForPackage
import com.focuslock.app.ui.components.IconBadge
import com.focuslock.app.ui.components.ScreenHeader
import com.focuslock.app.ui.components.SectionHeader
import com.focuslock.app.ui.components.StaggeredFadeSlide
import com.focuslock.app.ui.components.UiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pure candidate rule for the Permalock picker: installed apps that are not already
 * permanently blocked, not protected recovery packages (dialer/settings/launcher/IME/…),
 * and — when [query] is not blank — match the app label or package name.
 *
 * Extracted so the filtering rule is JVM-testable without PackageManager or Compose.
 */
internal fun permalockCandidates(
    apps: List<InstalledApp>,
    permanentPackages: Set<String>,
    query: String,
    isProtected: (String) -> Boolean,
): List<InstalledApp> {
    val permanent = permanentPackages.mapTo(HashSet()) { it.trim().lowercase() }
    val needle = query.trim().lowercase()
    return apps.filter { app ->
        val packageName = app.packageName.trim().lowercase()
        packageName.isNotEmpty() &&
            packageName !in permanent &&
            !isProtected(packageName) &&
            (needle.isEmpty() ||
                app.appName.lowercase().contains(needle) ||
                packageName.contains(needle))
    }
}

private sealed interface PermalockLoadState {
    data object Loading : PermalockLoadState
    data object Ready : PermalockLoadState
    data class Error(val message: String) : PermalockLoadState
}

/**
 * Permalock tab: the permanent-block surface.
 *
 * Lists every package permanently blocked on this device — the append-only
 * [PermanentBlocksRepository] store plus legacy Settings-mirror entries that enforcement
 * also honors — and offers an add-only picker. There is deliberately no unlock, expiry,
 * credit, emergency or removal affordance anywhere on this screen — permanently blocked
 * means exactly that.
 */
@Composable
fun PermalockScreen(onBack: (() -> Unit)? = null) {
    val permanentBlocks = FocusLockApplication.instance.permanentBlocksRepository
    val settings = FocusLockApplication.instance.settingsRepository
    val permanentPackages by permanentBlocks.packagesFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val permanentDomains by permanentBlocks.domainsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val appNames by permanentBlocks.appNamesFlow
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val legacyWebsites by settings.blockedWebsitesFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val websiteUnion = remember(permanentDomains, legacyWebsites) {
        permanentDomains + legacyWebsites.filter { it.isPermanent }
            .mapNotNull { PermanentWebsitePolicy.normalize(it.domain) }
    }
    // Enforcement ORs the dedicated store with the legacy Settings mirror
    // (AppMonitorAccessibilityService), so a backup-restored permanent enforces even
    // before startup migration reaches the dedicated store. Display and picker
    // exclusion use the union throughout that transition.
    val legacyBlockedApps by settings.blockedAppsFlow
        .collectAsStateWithLifecycle(initialValue = emptyList<BlockedApp>())
    val legacyPermanentPackages = remember(legacyBlockedApps) {
        legacyBlockedApps
            .filter { it.isPermanent }
            .map { it.packageName.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }
    val permanentUnion = remember(permanentPackages, legacyPermanentPackages) {
        (permanentPackages.map { it.trim().lowercase() }.filter { it.isNotEmpty() } +
            legacyPermanentPackages).toSet()
    }
    // Legacy-mirror-only entries cannot be managed from any in-app surface, so the
    // overview explains where they came from when any exist.
    val hasLegacyOnlyPermanents = remember(permanentPackages, legacyPermanentPackages) {
        val dedicated = permanentPackages.mapTo(HashSet()) { it.trim().lowercase() }
        legacyPermanentPackages.any { it !in dedicated }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var websiteDialogOpen by rememberSaveable { mutableStateOf(false) }
    // Back from the picker returns to the Permalock overview instead of leaving the app.
    BackHandler(enabled = pickerOpen) { pickerOpen = false }

    val onBlockConfirmed: (InstalledApp) -> Unit = remember(
        permanentBlocks, settings, scope, snackbarHostState
    ) {
        { picked ->
            scope.launch {
                // The dedicated write is the durable operation; legacy Settings
                // metadata is only a display mirror (same order as AppSelectorScreen).
                val ok = try {
                    permanentBlocks.add(picked.packageName, picked.appName)
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    try {
                        settings.setAppPermanent(picked.packageName, true)
                    } catch (_: Exception) {
                    }
                    snackbarHostState.showSnackbar("Permanently blocked ${picked.appName}.")
                } else {
                    // add() returns false for protected packages; same refusal copy as
                    // the Boundaries permanent flow so both screens explain it alike.
                    snackbarHostState.showSnackbar(
                        "Couldn't save this block. Protected phone recovery apps cannot be permanently blocked."
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (pickerOpen) {
            PermalockPicker(
                permanentPackages = permanentUnion,
                onBack = { pickerOpen = false },
                onBlockConfirmed = onBlockConfirmed,
            )
        } else {
            PermalockOverview(
                permanentPackages = permanentUnion,
                permanentDomains = websiteUnion,
                savedAppNames = appNames + legacyBlockedApps.associate { it.packageName to it.appName }
                    .filterKeys { it !in appNames },
                hasLegacyOnlyPermanents = hasLegacyOnlyPermanents,
                onBlockApp = { pickerOpen = true },
                onBlockWebsite = { websiteDialogOpen = true },
                onBack = onBack,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
    if (websiteDialogOpen) {
        PermanentWebsiteDialog(
            permanentDomains = websiteUnion,
            onDismiss = { websiteDialogOpen = false },
            onConfirm = { domain ->
                val saved = permanentBlocks.addWebsite(domain)
                if (saved) {
                    // The append-only store is authoritative; keep the ordinary
                    // settings mirror for sync and existing website metadata.
                    try { settings.setWebsitePermanent(domain, true) } catch (_: Exception) { }
                    websiteDialogOpen = false
                    scope.launch { snackbarHostState.showSnackbar("Permanently blocked $domain.") }
                }
                saved
            }
        )
    }
}

@Composable
private fun PermanentWebsiteDialog(
    permanentDomains: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Boolean,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var confirmedDomain by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val normalized = remember(input) { PermanentWebsitePolicy.normalize(input) }
    val alreadyBlocked = normalized != null && permanentDomains.any {
        normalized == it || normalized.endsWith(".$it")
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (confirmedDomain == null) "Block a website" else "Permanently block $confirmedDomain?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (confirmedDomain == null) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; error = null },
                        label = { Text("Website or URL") },
                        placeholder = { Text("example.com") },
                        singleLine = true,
                        isError = error != null || alreadyBlocked || (input.isNotBlank() && normalized == null),
                        supportingText = {
                            Text(error ?: if (alreadyBlocked) "This website is already in PermaLock."
                                else if (input.isNotBlank() && normalized == null) "Enter a valid website, such as example.com."
                                else "The domain and its subdomains will stay blocked.")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (normalized != null && !alreadyBlocked) {
                        Text("Website: $normalized", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text("$confirmedDomain and its subdomains will stay blocked indefinitely. FocusLock will not offer credits, emergency passes, grace time, or an in-app removal control for this website.")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && (confirmedDomain != null || (normalized != null && !alreadyBlocked)),
                onClick = {
                    val domain = confirmedDomain
                    if (domain == null) {
                        confirmedDomain = normalized
                    } else {
                        saving = true
                        error = null
                        scope.launch {
                            val saved = try { onConfirm(domain) } catch (_: Exception) { false }
                            if (!saved) error = "Couldn't save the permanent block. Try again."
                            saving = false
                        }
                    }
                }
            ) { Text(if (saving) "Saving…" else if (confirmedDomain == null) "Continue" else "Block permanently") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = {
                if (confirmedDomain == null) onDismiss() else { confirmedDomain = null; error = null }
            }) { Text(if (confirmedDomain == null) "Cancel" else "Back") }
        }
    )
}

@Composable
private fun PermalockOverview(
    permanentPackages: Set<String>,
    permanentDomains: Set<String>,
    savedAppNames: Map<String, String>,
    hasLegacyOnlyPermanents: Boolean,
    onBlockApp: () -> Unit,
    onBlockWebsite: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    var rows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(permanentPackages, savedAppNames, appContext) {
        val packages = permanentPackages.toList()
        val existingLabels = rows.toMap()
        // Keep existing labels visible across store updates and show package names for
        // newly added entries until PackageManager resolves their labels on IO.
        rows = packages.map { packageName ->
            packageName to (savedAppNames[packageName] ?: existingLabels[packageName] ?: packageName)
        }
        rows = withContext(Dispatchers.IO) {
            packages
                .map { packageName ->
                    packageName to (savedAppNames[packageName]
                        ?: InstalledAppsRepository.getAppLabel(appContext, packageName))
                }
                .sortedBy { (_, label) -> label.lowercase() }
        }
    }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = UiTokens.ScreenPadding,
            end = UiTokens.ScreenPadding,
            bottom = 32.dp
        )
    ) {
        item(key = "permalock-header", contentType = "screenHeader") {
            StaggeredFadeSlide(visible = entered, index = 0, screenKey = "permalock") {
                ScreenHeader(
                    title = "Permanent blocks",
                    subtitle = "Apps and websites stay blocked indefinitely.",
                    onBack = onBack
                )
            }
        }

        item(key = "permalock-explainer", contentType = "explainerCard") {
            Spacer(Modifier.height(UiTokens.ItemGap))
            StaggeredFadeSlide(
                visible = entered,
                index = 1,
                modifier = Modifier.fillMaxWidth(),
                screenKey = "permalock"
            ) {
                PermalockExplainerCard(onBlockApp = onBlockApp, onBlockWebsite = onBlockWebsite)
            }
        }

        item(key = "permalock-list-header", contentType = "sectionHeader") {
            Spacer(Modifier.height(UiTokens.ItemGap))
            Column(verticalArrangement = Arrangement.spacedBy(UiTokens.ItemGap)) {
                SectionHeader(title = "Apps")
                if (hasLegacyOnlyPermanents) {
                    Text(
                        text = "Includes permanent blocks restored from a backup. They are enforced on this device and cannot be removed in FocusLock either.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (permanentPackages.isEmpty()) {
                    PermanentGroupEmptyState("No apps permanently blocked yet.")
                }
            }
        }

        if (rows.isNotEmpty()) {
            item(key = "permalock-list-spacer", contentType = "spacer") {
                Spacer(Modifier.height(UiTokens.ItemGap))
            }
            itemsIndexed(
                items = rows,
                key = { _, item -> item.first },
                contentType = { _, _ -> "permanentAppRow" }
            ) { index, (packageName, appName) ->
                val first = index == 0
                val last = index == rows.lastIndex
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(
                        topStart = if (first) 20.dp else 0.dp,
                        topEnd = if (first) 20.dp else 0.dp,
                        bottomStart = if (last) 20.dp else 0.dp,
                        bottomEnd = if (last) 20.dp else 0.dp
                    ),
                    modifier = Modifier.animateItem()
                ) {
                    Column(
                        modifier = Modifier.padding(
                            top = if (first) 4.dp else 0.dp,
                            bottom = if (last) 4.dp else 0.dp
                        )
                    ) {
                        PermanentAppRow(packageName = packageName, appName = appName)
                        if (!last) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 68.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
        item(key = "permalock-websites-header") {
            Spacer(Modifier.height(20.dp))
            SectionHeader(title = "Websites")
            Spacer(Modifier.height(8.dp))
            if (permanentDomains.isEmpty()) {
                PermanentGroupEmptyState("No websites permanently blocked yet.")
            }
        }
        if (permanentDomains.isNotEmpty()) {
            val websites = permanentDomains.sorted()
            itemsIndexed(websites, key = { _, domain -> "website:$domain" }) { index, domain ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(
                        topStart = if (index == 0) 20.dp else 0.dp,
                        topEnd = if (index == 0) 20.dp else 0.dp,
                        bottomStart = if (index == websites.lastIndex) 20.dp else 0.dp,
                        bottomEnd = if (index == websites.lastIndex) 20.dp else 0.dp
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.Language, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(domain, style = MaterialTheme.typography.titleSmall,
                                overflow = TextOverflow.Ellipsis, maxLines = 2)
                            Text("Includes subdomains", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp)) {
                            Text("Permanent", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermalockExplainerCard(onBlockApp: () -> Unit, onBlockWebsite: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IconBadge(
                    icon = Icons.Rounded.Block,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cannot be undone",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Text(
                text = "No credits or emergency passes. Apps stay blocked after reinstalling; websites include subdomains.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onBlockApp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(
                    imageVector = Icons.Rounded.Block,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Block an app", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onBlockWebsite,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Block a website", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PermanentGroupEmptyState(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}

@Composable
private fun PermanentAppRow(packageName: String, appName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIconTileForPackage(
            packageName = packageName,
            name = appName,
            size = UiTokens.IconTileSize
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = appName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Display-only badge: no toggle, no remove action, no long-press.
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = "Permanent",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun PermalockPicker(
    permanentPackages: Set<String>,
    onBack: () -> Unit,
    onBlockConfirmed: (InstalledApp) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var loadAttempt by rememberSaveable { mutableIntStateOf(0) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loadState by remember { mutableStateOf<PermalockLoadState>(PermalockLoadState.Loading) }
    var pendingApp by remember { mutableStateOf<InstalledApp?>(null) }

    LaunchedEffect(loadAttempt) {
        loadState = PermalockLoadState.Loading
        val loaded = try {
            withContext(Dispatchers.IO) {
                // Retry forces a fresh PackageManager query so a cached empty/partial
                // result doesn't stick for the shared 30s TTL.
                InstalledAppsRepository.getInstalledLaunchableApps(
                    context,
                    forceRefresh = loadAttempt > 0
                )
            }
        } catch (e: Exception) {
            installedApps = emptyList()
            loadState = PermalockLoadState.Error(e.message ?: "Could not read the installed-app list")
            return@LaunchedEffect
        }
        installedApps = loaded
        loadState = PermalockLoadState.Ready
    }

    // Protected recovery packages are resolved once per app-list load; the pure
    // candidate rule then runs per keystroke without touching PackageManager.
    // Names are normalized to match the rule's lowercase comparison.
    val protectedPackages = remember(installedApps, context) {
        installedApps.map { it.packageName.trim().lowercase() }
            .filterTo(HashSet()) { PermanentBlocksRepository.isProtectedPackage(context, it) }
    }
    val candidates = remember(installedApps, permanentPackages, query, protectedPackages) {
        permalockCandidates(
            apps = installedApps,
            permanentPackages = permanentPackages,
            query = query,
            isProtected = protectedPackages::contains,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = UiTokens.ScreenPadding)
    ) {
        Spacer(Modifier.height(4.dp))

        // TopAppBar-style header: back arrow + title (no search action; the field is
        // always visible because filtering is the only control this picker has).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back to Permalock",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "Block an app",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }

        PermalockSearchField(query = query, onQueryChange = { query = it })
        Spacer(Modifier.height(8.dp))

        // Weighted box owns the remaining space so loading/error/empty states and the
        // list fill exactly this region, never the header or search field.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (val state = loadState) {
                is PermalockLoadState.Loading -> PermalockLoadingState("Loading installed apps…")

                is PermalockLoadState.Error -> PermalockErrorState(
                    message = state.message,
                    onRetry = { loadAttempt++ }
                )

                is PermalockLoadState.Ready -> when {
                    installedApps.isEmpty() -> PermalockMessageState(
                        message = "No launchable apps found on this device. If apps just finished installing, retry to refresh the list.",
                        actionLabel = "Retry",
                        onAction = { loadAttempt++ }
                    )

                    candidates.isEmpty() && query.isNotBlank() -> PermalockMessageState(
                        message = "No apps match. Try a different search.",
                        actionLabel = "Clear search",
                        onAction = { query = "" }
                    )

                    candidates.isEmpty() -> PermalockMessageState(
                        message = "Every app on this device is already permanently blocked or protected.",
                        actionLabel = null,
                        onAction = null
                    )

                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(UiTokens.ItemGap),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        items(items = candidates, key = { it.packageName }) { item ->
                            PermalockCandidateRow(app = item, onClick = { pendingApp = item })
                        }
                    }
                }
            }
        }
    }

    // Exact copy of the existing permanent-block confirmation flow
    // (AppSelectorScreen.kt), so both entry points promise the same thing.
    pendingApp?.let { picked ->
        AlertDialog(
            onDismissRequest = { pendingApp = null },
            title = { Text("Permanently block ${picked.appName}") },
            text = {
                Text("This app will stay blocked indefinitely. FocusLock will not offer credits, emergency passes, grace time, or an in-app removal control for it.")
            },
            confirmButton = {
                Button(onClick = {
                    pendingApp = null
                    onBlockConfirmed(picked)
                }) { Text("Block permanently") }
            },
            dismissButton = {
                TextButton(onClick = { pendingApp = null }) { Text("Cancel") }
            },
            shape = MaterialTheme.shapes.large
        )
    }
}

/**
 * Search input matching the picker's house style: filled container, no outline.
 * Reads its own text so keystrokes only recompose this field.
 */
@Composable
private fun PermalockSearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text("Search installed apps…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingIcon = {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
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
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PermalockCandidateRow(app: InstalledApp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIconTileForPackage(
            packageName = app.packageName,
            name = app.appName,
            size = UiTokens.IconTileSize
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "Block",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun PermalockLoadingState(message: String) {
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
private fun PermalockErrorState(message: String, onRetry: () -> Unit) {
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
private fun PermalockMessageState(
    message: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
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
