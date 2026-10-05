package com.focuslock.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.repository.FrogRepository
import com.focuslock.app.service.FrogAppPolicy
import com.focuslock.app.service.InstalledAppsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FROG_DEFAULT_LABELS = listOf("TickTick", "Phone", "Clock", "Messages", "WhatsApp", "ChatGPT", "Spotify", "Google Pay")

private data class EssentialAppRow(
    val packageName: String,
    val label: String,
    val isCore: Boolean,
    val isDefault: Boolean,
    val installed: Boolean,
    val isSafetyEssential: Boolean,
)

private data class FrogEssentialAppsData(
    val defaultPackages: List<String>,
    val installedApps: List<com.focuslock.app.service.InstalledApp>,
    val safetyPackages: Set<String>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FrogEssentialAppsScreen(
    onBack: () -> Unit,
    repository: FrogRepository = FocusLockApplication.instance.frogRepository,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val appData by produceState<FrogEssentialAppsData?>(null, context) {
        value = withContext(Dispatchers.IO) {
            val defaults = FrogAppPolicy.defaultLaunchPackages(context)
            val installed = InstalledAppsRepository.getInstalledLaunchableApps(context)
            val safety = (defaults + installed.map { it.packageName }).distinct()
                .filterTo(mutableSetOf()) { FrogAppPolicy.isSafetyEssential(context, it) }
            FrogEssentialAppsData(defaults, installed, safety)
        }
    }
    val defaultPackages = appData?.defaultPackages.orEmpty()
    val corePackages = defaultPackages.take(4).toSet()
    val defaultPackageSet = defaultPackages.toSet()
    var selected by rememberSaveable(
        stateSaver = Saver<Set<String>, ArrayList<String>>(
            save = { ArrayList(it) },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(emptySet()) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(defaultPackages, repository) {
        if (!initialized && defaultPackages.isNotEmpty()) {
            val storedPackages = repository.essentialAppPackagesFlow.first()
            selected = storedPackages ?: defaultPackageSet
            selected = selected + corePackages
            initialized = true
        }
    }

    val installedApps = appData?.installedApps.orEmpty()
    val safetyPackages = appData?.safetyPackages.orEmpty()
    val rows = remember(installedApps, defaultPackages, selected, corePackages, safetyPackages) {
        val byPackage = installedApps.associateBy { it.packageName }
        val defaultLabels = defaultPackages.zip(FROG_DEFAULT_LABELS).toMap().toMutableMap().apply {
            putIfAbsent(FrogAppPolicy.GPAY_PACKAGE, "Google Pay")
            putIfAbsent(FrogAppPolicy.GPAY_WALLET_PACKAGE, "Google Pay")
        }
        fun displayLabel(packageName: String): String =
            defaultLabels[packageName] ?: byPackage[packageName]?.appName ?: packageName.substringAfterLast('.')
        val optionalExtras = (selected + installedApps.map { it.packageName })
            .filterNot { it in defaultPackageSet }
            .distinct()
            .sortedWith(compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { displayLabel(it) }.thenBy { it })
        val orderedPackages = defaultPackages + optionalExtras
        orderedPackages.map { packageName ->
            val app = byPackage[packageName]
            EssentialAppRow(
                packageName = packageName,
                label = displayLabel(packageName),
                isCore = packageName in corePackages,
                isDefault = packageName in defaultPackageSet,
                installed = app != null,
                isSafetyEssential = packageName in safetyPackages,
            )
        }
    }
    val filteredRows = remember(rows, search) {
        val query = search.trim()
        if (query.isEmpty()) rows else rows.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Essential apps", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    saveError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Button(
                        onClick = {
                            if (saving || !initialized) return@Button
                            saving = true
                            saveError = null
                            scope.launch {
                                val saved = try {
                                    repository.setEssentialApps(selected + corePackages)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    false
                                }
                                saving = false
                                if (saved) onBack() else saveError = "Couldn't save your essential apps. Please try again."
                            }
                        },
                        enabled = initialized && !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text(if (saving) "Saving…" else "Save")
                    }
                }
            }
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).padding(horizontal = 20.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Calls, messages, clock and TickTick stay available. Safety and recovery apps remain available too.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Choose apps available for every frog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboardController?.hide() }),
                label = { Text("Search installed apps") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(14.dp),
            )
            if (!initialized || appData == null) {
                Text("Loading your app choices…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredRows, key = { it.packageName }) { row ->
                        val checked = row.isCore || row.isSafetyEssential || row.packageName in selected
                        EssentialAppRowView(
                            row = row,
                            checked = checked,
                            onToggle = {
                                if (row.isCore || row.isSafetyEssential) return@EssentialAppRowView
                                selected = if (checked) selected - row.packageName else selected + row.packageName
                                saveError = null
                            },
                        )
                    }
                    if (filteredRows.isEmpty()) item { Text("No matching apps.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Spacer(Modifier.size(4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EssentialAppRowView(
    row: EssentialAppRow,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, row.packageName, row.installed) {
        value = if (row.installed) withContext(Dispatchers.IO) {
            InstalledAppsRepository.getAppIconBitmap(context, row.packageName)
        } else null
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(
                    value = checked,
                    enabled = !row.isCore && !row.isSafetyEssential,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(icon!!, contentDescription = null, modifier = Modifier.size(40.dp))
            } else {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(row.label.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = when {
                        !row.installed && (row.isCore || row.isSafetyEssential) -> "Always available · Not installed"
                        !row.installed -> "Not installed"
                        row.isCore || row.isSafetyEssential -> "Always available"
                        row.isDefault -> "Default suggestion"
                        checked -> "Available during Frog"
                        else -> "Not available during Frog"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                enabled = !row.isCore && !row.isSafetyEssential,
                modifier = Modifier.size(48.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}
