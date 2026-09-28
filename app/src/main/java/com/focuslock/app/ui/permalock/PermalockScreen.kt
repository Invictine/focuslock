package com.focuslock.app.ui.permalock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
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
fun PermalockScreen() {
    val permanentBlocks = FocusLockApplication.instance.permanentBlocksRepository
    val settings = FocusLockApplication.instance.settingsRepository
    val permanentPackages by permanentBlocks.packagesFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    // Enforcement ORs the dedicated store with the legacy Settings mirror
    // (AppMonitorAccessibilityService), so a backup-restored permanent enforces even
    // though it never landed in the dedicated store. Display and picker exclusion use
    // the union; nothing is migrated or mutated — the mirror stays display-only.
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
                    permanentBlocks.add(picked.packageName)
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
                        "That app is protected so you can always recover your phone."
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
                hasLegacyOnlyPermanents = hasLegacyOnlyPermanents,
                onBlockApp = { pickerOpen = true },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun PermalockOverview(
    permanentPackages: Set<String>,
    hasLegacyOnlyPermanents: Boolean,
    onBlockApp: () -> Unit,
) {
    val context = LocalContext.current
    // Labels resolve once per set; getAppLabel stays memory-cached afterwards.
    val rows: List<Pair<String, String>> = remember(permanentPackages, context) {
        permanentPackages
            .map { pkg -> pkg to InstalledAppsRepository.getAppLabel(context, pkg) }
            .sortedBy { (_, label) -> label.lowercase() }
    }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UiTokens.ScreenPadding)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(UiTokens.ItemGap)
    ) {
        StaggeredFadeSlide(visible = entered, index = 0, screenKey = "permalock") {
            ScreenHeader(
                title = "Permalock",
                subtitle = "Blocks that stay, with no way back in the app."
            )
        }

        StaggeredFadeSlide(
            visible = entered,
            index = 1,
            modifier = Modifier.fillMaxWidth(),
            screenKey = "permalock"
        ) {
            PermalockExplainerCard(onBlockApp = onBlockApp)
        }

        StaggeredFadeSlide(
            visible = entered,
            index = 2,
            modifier = Modifier.fillMaxWidth(),
            screenKey = "permalock"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(UiTokens.ItemGap)) {
                SectionHeader(title = "Permanently blocked")
                if (hasLegacyOnlyPermanents) {
                    Text(
                        text = "Includes permanent blocks restored from a backup. They are enforced on this device and cannot be removed in FocusLock either.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (rows.isEmpty()) {
                    PermanentEmptyCard()
                } else {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            rows.forEachIndexed { index, (packageName, appName) ->
                                PermanentAppRow(packageName = packageName, appName = appName)
                                if (index != rows.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 68.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermalockExplainerCard(onBlockApp: () -> Unit) {
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
                        text = "Permanent",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Permalock",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Text(
                text = "Apps blocked here stay blocked. No timers, no credits, no emergency passes, and no in-app removal.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onBlockApp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
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
        }
    }
}

@Composable
private fun PermanentEmptyCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "No permanent blocks yet",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Once you block an app here, it stays blocked and FocusLock will not offer a way to remove it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
