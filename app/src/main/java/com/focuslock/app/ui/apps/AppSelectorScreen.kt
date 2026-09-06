package com.focuslock.app.ui.apps

import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.model.BlockedWebsite
import com.focuslock.app.service.InstalledApp
import com.focuslock.app.service.InstalledAppsRepository
import com.focuslock.app.service.UsageStatsRepository
import kotlinx.coroutines.Dispatchers
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
    val todayMinutes: Long = 0L
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = FocusLockApplication.instance.settingsRepository

    val blockedApps by settings.blockedAppsFlow.collectAsState(initial = BlockedApp.DEFAULT_DOOMSCROLL_APPS)
    val blockedWebsites by settings.blockedWebsitesFlow.collectAsState(initial = BlockedWebsite.DEFAULT_BLOCKED_WEBSITES)

    var currentTab by remember { mutableStateOf(PickerTab.APPLICATIONS) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var showAddWebsiteDialog by remember { mutableStateOf(false) }
    var newWebsiteInput by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    // Real installed apps from PackageManager (fixes "very broken app picker")
    var installedApps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var usageMinutes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    LaunchedEffect(Unit) {
        val apps = withContext(Dispatchers.IO) {
            InstalledAppsRepository.getInstalledLaunchableApps(context)
        }
        installedApps = apps
        // Load per-app usage in background (StayFree-style context)
        withContext(Dispatchers.IO) {
            val summary = UsageStatsRepository.getTodaySummary(context, maxApps = 200)
            val map = summary.topApps.associate { it.packageName to it.foregroundMinutes }
            withContext(Dispatchers.Main) { usageMinutes = map }
        }
    }

    // Merge stored block-state with installed apps + defaults for not-yet-installed known apps
    val mergedAppRows: List<AppRowItem> = remember(blockedApps, installedApps, usageMinutes) {
        val blockedByPkg = blockedApps.associateBy { it.packageName }
        val installedByPkg = installedApps?.associateBy { it.packageName } ?: emptyMap()
        val rows = mutableListOf<AppRowItem>()

        // 1. All installed apps (the real picker)
        for (inst in installedApps.orEmpty()) {
            val stored = blockedByPkg[inst.packageName]
            val defaultBlocked = BlockedApp.DEFAULT_DOOMSCROLL_APPS
                .firstOrNull { it.packageName == inst.packageName }?.isBlocked ?: false
            rows.add(
                AppRowItem(
                    packageName = inst.packageName,
                    appName = inst.appName,
                    category = stored?.category ?: inst.category,
                    isBlocked = stored?.isBlocked ?: defaultBlocked,
                    isInstalled = true,
                    todayMinutes = usageMinutes[inst.packageName] ?: 0L
                )
            )
        }
        // 2. Known doomscroll apps not installed (so user sees what's missing) at the bottom
        for (def in BlockedApp.DEFAULT_DOOMSCROLL_APPS) {
            if (!installedByPkg.containsKey(def.packageName) && installedApps != null) {
                val stored = blockedByPkg[def.packageName]
                rows.add(
                    AppRowItem(
                        packageName = def.packageName,
                        appName = def.appName + if (InstalledAppsRepository.isInstalled(context, def.packageName)) "" else " • not installed",
                        category = stored?.category ?: def.category,
                        isBlocked = stored?.isBlocked ?: def.isBlocked,
                        isInstalled = InstalledAppsRepository.isInstalled(context, def.packageName),
                        todayMinutes = 0L
                    )
                )
            }
        }
        // Blocked-first, then by usage, then alphabetical (StayFree convention)
        rows.sortedWith(
            compareByDescending<AppRowItem> { it.isBlocked }
                .thenByDescending { it.todayMinutes }
                .thenBy { it.appName.lowercase() }
        )
    }

    val blockedAppCount = remember(blockedApps, installedApps) {
        mergedAppRows.count { it.isBlocked && (installedApps == null || it.isInstalled) }
    }
    val blockedWebsiteCount = remember(blockedWebsites) { blockedWebsites.count { it.isBlocked } }

    val categories = remember(mergedAppRows) {
        val cats = mergedAppRows.map { it.category }.distinct().sorted()
        listOf("All", "Blocked") + cats
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Text("Your boundaries", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text("Choose what waits until after your work.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        // AppBlock Style Primary Tab Selector
        PrimaryTabRow(
            selectedTabIndex = currentTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
        ) {
            Tab(
                selected = currentTab == PickerTab.APPLICATIONS,
                onClick = {
                    currentTab = PickerTab.APPLICATIONS
                    searchQuery = ""
                    selectedCategory = "All"
                },
                text = {
                    Text(
                        "Applications ($blockedAppCount)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            )
            Tab(
                selected = currentTab == PickerTab.WEBSITES,
                onClick = {
                    currentTab = PickerTab.WEBSITES
                    searchQuery = ""
                    selectedCategory = "All"
                },
                text = {
                    Text(
                        "Websites ($blockedWebsiteCount)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search Field
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    if (currentTab == PickerTab.APPLICATIONS) "Search installed apps..." else "Search websites...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        when (currentTab) {
            PickerTab.APPLICATIONS -> {
                // Quick Presets (AppBlock style) — now operate on real installed apps
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuggestionChip(
                        onClick = {
                            scope.launch {
                                val socialPkgs = mergedAppRows
                                    .filter { it.isInstalled && it.category == "Social" }
                                    .map { it.packageName }.toSet()
                                for (row in mergedAppRows.filter { it.packageName in socialPkgs }) {
                                    settings.setAppBlockedFull(row.packageName, row.appName, row.category, true)
                                }
                                Toast.makeText(context, "Blocked ${socialPkgs.size} Social apps", Toast.LENGTH_SHORT).show()
                            }
                        },
                        label = { Text("Block All Social") },
                        shape = RoundedCornerShape(50),
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )

                    SuggestionChip(
                        onClick = {
                            scope.launch {
                                val videoPkgs = mergedAppRows
                                    .filter { it.isInstalled && it.category == "Entertainment" }
                                    .map { it.packageName }.toSet()
                                for (row in mergedAppRows.filter { it.packageName in videoPkgs }) {
                                    settings.setAppBlockedFull(row.packageName, row.appName, row.category, true)
                                }
                                Toast.makeText(context, "Blocked ${videoPkgs.size} Entertainment apps", Toast.LENGTH_SHORT).show()
                            }
                        },
                        label = { Text("Block All Video") },
                        shape = RoundedCornerShape(50),
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )

                    SuggestionChip(
                        onClick = {
                            scope.launch {
                                val updated = blockedApps.map { it.copy(isBlocked = false) }
                                settings.updateBlockedApps(updated)
                                Toast.makeText(context, "Cleared app blocks", Toast.LENGTH_SHORT).show()
                            }
                        },
                        label = { Text("Unblock All") },
                        shape = RoundedCornerShape(50)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Category filter chips (StayFree-style grouping)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(categories) { cat ->
                        val selected = selectedCategory == cat
                        FilterChip(
                            selected = selected,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) },
                            shape = RoundedCornerShape(50)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (installedApps == null) "Loading installed apps..."
                        else "${mergedAppRows.count { it.isInstalled }} installed apps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "System",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Applications List
                if (installedApps == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    val installedSet = installedApps!!.map { it.packageName }.toSet()
                    val systemSet = installedApps!!.filter { it.isSystem }.map { it.packageName }.toSet()
                    val filteredApps = mergedAppRows.filter { row ->
                        val matchesSearch = searchQuery.isBlank() ||
                            row.appName.contains(searchQuery, ignoreCase = true) ||
                            row.packageName.contains(searchQuery, ignoreCase = true)
                        val matchesCategory = when (selectedCategory) {
                            "All" -> true
                            "Blocked" -> row.isBlocked
                            else -> row.category == selectedCategory
                        }
                        val matchesSystem = showSystemApps || row.packageName !in systemSet || row.isBlocked
                        matchesSearch && matchesCategory && matchesSystem
                    }

                    if (filteredApps.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text(
                                "No apps match. Try a different search or category.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                InstalledAppCard(
                                    app = app,
                                    onToggle = { isChecked ->
                                        scope.launch {
                                            settings.setAppBlockedFull(
                                                app.packageName, app.appName, app.category, isChecked
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            PickerTab.WEBSITES -> {
                // Websites Tab Header with "Add Custom Website" action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Browser Domain Filter",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Button(
                        onClick = { showAddWebsiteDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Website", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                val filteredWebsites = blockedWebsites.filter {
                    it.domain.contains(searchQuery, ignoreCase = true) || it.displayName.contains(searchQuery, ignoreCase = true)
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredWebsites, key = { it.domain }) { site ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (site.isBlocked) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer
                            ),
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(
                                            if (site.isBlocked) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Language,
                                        contentDescription = null,
                                        tint = if (site.isBlocked) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = site.displayName,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                    Text(
                                        text = "${site.domain} • ${if (site.isBlocked) "Blocked" else "Allowed"}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (site.isBlocked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }

                                if (site.isCustom) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                settings.removeCustomWebsite(site.domain)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "Remove",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Switch(
                                    checked = site.isBlocked,
                                    onCheckedChange = { isChecked ->
                                        scope.launch {
                                            settings.setWebsiteBlocked(site.domain, isChecked)
                                        }
                                    },
                                    thumbContent = if (site.isBlocked) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize)
                                            )
                                        }
                                    } else null,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog: Add Custom Website
    if (showAddWebsiteDialog) {
        AlertDialog(
            onDismissRequest = { showAddWebsiteDialog = false },
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newWebsiteInput,
                        onValueChange = { newWebsiteInput = it },
                        placeholder = { Text("e.g. news.ycombinator.com") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newWebsiteInput.isNotBlank()) {
                            scope.launch {
                                val success = settings.addCustomWebsite(newWebsiteInput)
                                if (success) {
                                    Toast.makeText(context, "Added $newWebsiteInput to blocked websites", Toast.LENGTH_SHORT).show()
                                    newWebsiteInput = ""
                                    showAddWebsiteDialog = false
                                } else {
                                    Toast.makeText(context, "Domain is invalid or already added", Toast.LENGTH_SHORT).show()
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
                TextButton(onClick = { showAddWebsiteDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = MaterialTheme.shapes.large
        )
    }
}

@Composable
private fun InstalledAppCard(
    app: AppRowItem,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (app.isBlocked) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconBadge(packageName = app.packageName, appName = app.appName, isBlocked = app.isBlocked)

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1
                )
                Text(
                    text = buildString {
                        append(app.category)
                        append(" • ")
                        append(if (app.isBlocked) "Blocked" else "Allowed")
                        if (app.todayMinutes > 0) append(" • ${app.todayMinutes}m today")
                        if (!app.isInstalled) append(" • tap to keep blocked")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (app.isBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    maxLines = 2
                )
            }

            Switch(
                checked = app.isBlocked,
                onCheckedChange = onToggle,
                thumbContent = if (app.isBlocked) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                } else null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun AppIconBadge(packageName: String, appName: String, isBlocked: Boolean) {
    val context = LocalContext.current
    var iconBitmap by remember(packageName) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                val drawable: Drawable? = InstalledAppsRepository.getAppIcon(context, packageName)
                val bmp = drawable?.toBitmap(width = 96, height = 96)?.asImageBitmap()
                withContext(Dispatchers.Main) { iconBitmap = bmp }
            } catch (_: Exception) { }
        }
    }

    Box(
        modifier = Modifier
            .size(42.dp)
            .background(
                if (isBlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        val bmp = iconBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.size(30.dp)
            )
        } else {
            Text(
                text = appName.firstOrNull()?.toString()?.uppercase() ?: "A",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (isBlocked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
