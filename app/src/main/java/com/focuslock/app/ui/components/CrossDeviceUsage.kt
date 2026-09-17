package com.focuslock.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.repository.TargetGroup as LocalTargetGroup
import com.focuslock.app.data.repository.TargetGroupMember as LocalTargetGroupMember
import com.focuslock.app.data.repository.TargetGroupsRepository
import com.focuslock.app.data.repository.UpsertResult
import com.focuslock.app.sync.ConvexSyncClient
import com.focuslock.app.sync.DeviceInfo
import com.focuslock.app.sync.GroupedTarget
import com.focuslock.app.sync.TargetGroupMember
import com.focuslock.app.sync.UsageSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A cross-device usage row handed to the Boundaries picker so it can open the merge
 * editor with that single target pre-selected ("New bucket…" flow). Kept deliberately
 * tiny so MainActivity can hold it as plain state across the tab switch.
 */
data class PendingMergeTarget(
    val targetKind: String,
    val targetKey: String,
    val targetLabel: String,
)

/** Repository/server member-key normalization: lowercase, websites drop a leading "www.". */
private fun normalizedMemberKey(kind: String, rawKey: String): String? {
    val normalizedKind = kind.trim().lowercase()
    val key = rawKey.trim().lowercase()
    if (normalizedKind.isEmpty() || key.isEmpty()) return null
    val normalized = if (normalizedKind == "website") key.removePrefix("www.") else key
    return normalized.ifEmpty { null }
}

/**
 * Shared seconds -> "Xm" / "Hh Mm" formatter for cross-device usage. Kept next to the
 * section that consumes it so the Account tab and any other surface format identically;
 * minutes-based callers keep using UsageStatsRepository.formatDuration.
 */
fun formatUsageSeconds(seconds: Long): String {
    val minutes = (seconds / 60L).coerceAtLeast(0L)
    return if (minutes < 60L) "${minutes}m" else "${minutes / 60L}h ${minutes % 60L}m"
}

/** Date window for the cross-device usage view. */
enum class CrossDeviceRange(val label: String) {
    TODAY("Today"),
    SEVEN_DAYS("7 days"),
    THIRTY_DAYS("30 days"),
    ALL_TIME("All time");

    /**
     * YYYY-MM-DD bounds for `usage:getUsageSummary` (same formatter MainActivity uses).
     * 7d = today-6, 30d = today-29, all time = both null (unbounded server query).
     */
    fun bounds(nowMillis: Long = System.currentTimeMillis()): Pair<String?, String?> {
        if (this == ALL_TIME) return null to null
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = format.format(Date(nowMillis))
        val from = when (this) {
            TODAY -> today
            SEVEN_DAYS -> format.format(Date(nowMillis - 6L * DAY_MS))
            THIRTY_DAYS -> format.format(Date(nowMillis - 29L * DAY_MS))
            ALL_TIME -> today
        }
        return from to today
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

/**
 * Reusable cumulative cross-device usage section: a Today / 7 days / 30 days / All time
 * range selector over `usage:getUsageSummary`, merged buckets (groupedTargets) with the
 * contributing device names, expandable group members, and combined `used / limit`
 * lines with an over-limit error tint.
 *
 * States: signed out (or no client) shows a quiet sign-in hint; loading shows a spinner;
 * a failed fetch shows a Retry button. Every remote call is wrapped, so a bad base URL,
 * offline device, or malformed payload can never crash the screen or leak an exception.
 * Results are cached per range in `remember` so switching back to a visited range renders
 * instantly while the fresh fetch is in flight.
 */
@Composable
fun CrossDeviceUsageSection(
    client: ConvexSyncClient?,
    signedIn: Boolean,
    modifier: Modifier = Modifier,
    initialRange: CrossDeviceRange = CrossDeviceRange.TODAY,
    onNewBucket: ((PendingMergeTarget) -> Unit)? = null,
) {
    var range by rememberSaveable { mutableStateOf(initialRange) }
    var retryTick by remember { mutableIntStateOf(0) }
    val summaries = remember { mutableStateMapOf<CrossDeviceRange, UsageSummary>() }
    val failedRanges = remember { mutableStateMapOf<CrossDeviceRange, Boolean>() }
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(client, signedIn, range, retryTick) {
        if (client == null || !signedIn) return@LaunchedEffect
        loading = true
        if (devices.isEmpty()) {
            devices = try {
                client.listDevices()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
        }
        val (fromDate, toDate) = range.bounds()
        val summary = try {
            client.getUsageSummary(fromDate = fromDate, toDate = toDate)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        if (summary != null) {
            summaries[range] = summary
            failedRanges.remove(range)
        } else {
            failedRanges[range] = true
        }
        loading = false
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Cross-device usage")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CrossDeviceRange.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == range,
                    onClick = { range = candidate },
                    label = {
                        Text(
                            candidate.label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        when {
            client == null -> QuietHint("Cross-device sync isn't configured")
            !signedIn -> QuietHint("Sign in to sync across devices")
            else -> {
                val summary = summaries[range]
                when {
                    summary != null -> UsageSummaryCard(
                        summary = summary,
                        devices = devices,
                        onNewBucket = onNewBucket,
                    )
                    loading -> LoadingRow()
                    failedRanges[range] == true -> FailedCard(onRetry = { retryTick++ })
                    else -> LoadingRow()
                }
            }
        }
    }
}

@Composable
private fun QuietHint(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun FailedCard(onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Couldn't load cross-device usage",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun UsageSummaryCard(
    summary: UsageSummary,
    devices: List<DeviceInfo>,
    onNewBucket: ((PendingMergeTarget) -> Unit)?,
) {
    val deviceNames = remember(devices) { devices.associate { it.deviceId to it.name } }
    val membersByGroup = remember(summary) { summary.groups.associateBy { it.groupId } }
    var expandedGroupId by remember { mutableStateOf<String?>(null) }

    // Buckets for the per-row "add to bucket" chooser. The repository is a process-wide
    // singleton; collecting here means the chooser is current the moment it opens.
    val targetGroupsRepository = remember { FocusLockApplication.instance.targetGroupsRepository }
    val groups by targetGroupsRepository.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    var addToBucketTarget by remember { mutableStateOf<GroupedTarget?>(null) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Total across devices",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatUsageSeconds(summary.totalTrackedSeconds),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            val buckets = summary.groupedTargets
            if (buckets.isEmpty()) {
                Text(
                    "No synced usage for this range yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                buckets.forEach { target ->
                    val groupMembers = if (target.targetKind == "group" && target.groupId != null) {
                        membersByGroup[target.groupId]?.members
                    } else null
                    val expandable = !groupMembers.isNullOrEmpty()
                    GroupedTargetRow(
                        target = target,
                        deviceNames = deviceNames,
                        members = groupMembers,
                        expanded = expandable && expandedGroupId == target.groupId,
                        onToggleExpand = if (expandable) {
                            {
                                expandedGroupId = if (expandedGroupId == target.groupId) {
                                    null
                                } else {
                                    target.groupId
                                }
                            }
                        } else null,
                        // A merged bucket cannot itself be a member; only raw targets can.
                        onAddToBucket = if (target.targetKind == "group") {
                            null
                        } else {
                            { addToBucketTarget = target }
                        },
                    )
                }
            }
        }
    }

    addToBucketTarget?.let { target ->
        AddToBucketDialog(
            target = target,
            groups = groups,
            repository = targetGroupsRepository,
            onNewBucket = onNewBucket,
            onDismiss = { addToBucketTarget = null },
        )
    }
}

@Composable
private fun GroupedTargetRow(
    target: GroupedTarget,
    deviceNames: Map<String, String>,
    members: List<TargetGroupMember>?,
    expanded: Boolean,
    onToggleExpand: (() -> Unit)?,
    onAddToBucket: (() -> Unit)?,
) {
    val limitMinutes = target.dailyLimitMinutes?.takeIf { it > 0 }
    val limitActive = limitMinutes != null && target.limitEnabled != false
    val overLimit = limitActive && target.trackedSeconds >= limitMinutes * 60L
    val deviceLabel = target.deviceIds.mapNotNull { deviceNames[it] }.distinct().joinToString(", ")
    val meta = buildString {
        if (deviceLabel.isNotBlank()) append(deviceLabel)
        if (target.targetKind == "group") {
            if (isNotEmpty()) append(" · ")
            append("merged · ${target.memberCount}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onToggleExpand != null) {
                    Modifier.clickable(onClick = onToggleExpand)
                } else {
                    Modifier
                }
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(
                icon = when (target.targetKind) {
                    "website" -> Icons.Rounded.Language
                    "group" -> Icons.Rounded.Group
                    else -> Icons.Rounded.Apps
                },
                contentDescription = null,
                size = 36.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    target.targetLabel,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                formatUsageSeconds(target.trackedSeconds),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (onAddToBucket != null) {
                IconButton(
                    onClick = onAddToBucket,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Add ${target.targetLabel} to a merged bucket",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (onToggleExpand != null) {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse members" else "Expand members",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 0f else -90f),
                )
            }
        }
        if (limitActive) {
            Text(
                "${formatUsageSeconds(target.trackedSeconds)} / ${limitMinutes}m today",
                style = MaterialTheme.typography.labelMedium,
                color = if (overLimit) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (expanded && !members.isNullOrEmpty()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            members.forEach { member ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        member.targetLabel,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatUsageSeconds(member.trackedSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Chooser opened from a usage row's add action. "Add to existing bucket" appends the
 * target to one of the current groups via [TargetGroupsRepository.upsertGroup] (read,
 * append, keep groupId/updatedAt) and reports the real [UpsertResult]; "New bucket…"
 * hands the target to the Boundaries picker through [onNewBucket], which opens the full
 * editor pre-filled with it (the editor still requires a second member). When no
 * hand-off callback is supplied the button is replaced by a hint pointing at Boundaries.
 */
@Composable
private fun AddToBucketDialog(
    target: GroupedTarget,
    groups: List<LocalTargetGroup>,
    repository: TargetGroupsRepository,
    onNewBucket: ((PendingMergeTarget) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val kind = if (target.targetKind.trim().lowercase() == "website") "website" else "app"
    val key = normalizedMemberKey(kind, target.targetKey).orEmpty()
    val label = target.targetLabel.trim().ifEmpty { target.targetKey.trim() }
    var outcome by remember { mutableStateOf<String?>(null) }
    var outcomeIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val addToGroup: (LocalTargetGroup) -> Unit = { group ->
        if (!busy) {
            if (key.isEmpty()) {
                outcomeIsError = true
                outcome = "This target has no usable key to merge."
            } else {
                busy = true
                outcome = null
                scope.launch {
                    try {
                        val existing = repository.currentGroups()
                            .firstOrNull { it.groupId == group.groupId } ?: group
                        val alreadyMember = existing.members.any {
                            normalizedMemberKey(it.targetKind, it.targetKey) == key
                        }
                        if (alreadyMember) {
                            busy = false
                            outcomeIsError = false
                            outcome = "\"$label\" is already in \"${existing.name}\"."
                        } else {
                            // Read-modify-write on the fresh group: groupId, category,
                            // limit and updatedAt are preserved; only members change.
                            val result = repository.upsertGroup(
                                existing.copy(
                                    members = existing.members + LocalTargetGroupMember(kind, key, label),
                                )
                            )
                            busy = false
                            when {
                                !result.saved -> {
                                    outcomeIsError = true
                                    outcome = "Couldn't update \"${existing.name}\" — try again."
                                }
                                result.groupDiscarded -> {
                                    outcomeIsError = true
                                    outcome = "A bucket needs at least 2 members, so " +
                                        "\"${existing.name}\" wasn't saved."
                                }
                                result.droppedMemberLabels.isNotEmpty() -> {
                                    outcomeIsError = true
                                    outcome = "Saved \"${existing.name}\", but already merged elsewhere: " +
                                        result.droppedMemberLabels.joinToString(", ") + "."
                                }
                                else -> {
                                    outcomeIsError = false
                                    outcome = "Added \"$label\" to \"${existing.name}\"."
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        busy = false
                        outcomeIsError = true
                        outcome = "Couldn't update \"${group.name}\" — try again."
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Bucket \"$label\"",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Count this target's time together with everything in one merged bucket.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (groups.isEmpty()) {
                    Text(
                        text = "No merged buckets yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Add to existing bucket",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    groups.forEach { group ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { addToGroup(group) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Group,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (group.members.size == 1) "1 member"
                                        else "${group.members.size} members",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Add to ${group.name}",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                val outcomeText = outcome
                if (outcomeText != null) {
                    Text(
                        text = outcomeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (outcomeIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                if (onNewBucket == null) {
                    Text(
                        text = "To create a bucket, open Boundaries → Merged groups → New, " +
                            "then pick at least two members.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (onNewBucket != null) {
                Button(
                    onClick = {
                        onNewBucket(
                            PendingMergeTarget(
                                targetKind = kind,
                                targetKey = key.ifEmpty { target.targetKey.trim() },
                                targetLabel = label,
                            )
                        )
                        onDismiss()
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("New bucket…")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (onNewBucket == null) "Close" else "Cancel") }
        },
        shape = MaterialTheme.shapes.large,
    )
}
