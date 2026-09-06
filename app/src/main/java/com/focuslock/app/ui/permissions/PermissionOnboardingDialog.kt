package com.focuslock.app.ui.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class PermissionCopy(
    val title: String,
    val why: String,
    val steps: List<String>,
    val icon: ImageVector
)

fun permissionCopy(kind: PermissionKind): PermissionCopy = when (kind) {
    PermissionKind.ACCESSIBILITY -> PermissionCopy(
        title = "Accessibility access",
        why = "Detects when a blocked app opens so FocusLock can show the lockout instantly.",
        steps = listOf(
            "1. Tap Grant now to open Accessibility settings",
            "2. Find FocusLock and turn it ON",
            "3. Confirm with Allow when asked"
        ),
        icon = Icons.Filled.Accessibility
    )
    PermissionKind.USAGE -> PermissionCopy(
        title = "Usage access",
        why = "Powers the screen-time dashboard so you can see exactly where your time goes.",
        steps = listOf(
            "1. Tap Grant now to open app info",
            "2. Open Usage access / Screen time permission",
            "3. Allow access for FocusLock"
        ),
        icon = Icons.Filled.Analytics
    )
    PermissionKind.OVERLAY -> PermissionCopy(
        title = "Display over other apps",
        why = "Shows the fullscreen lockout on top of blocked apps — without it, blocking can't appear.",
        steps = listOf(
            "1. Tap Grant now to open the overlay page for FocusLock",
            "2. Turn ON Allow display over other apps",
            "3. Go back to FocusLock"
        ),
        icon = Icons.Filled.Layers
    )
    PermissionKind.NOTIFICATION_LISTENER -> PermissionCopy(
        title = "Notification access",
        why = "Catches TickTick Pomodoros and completed tasks automatically as they finish.",
        steps = listOf(
            "1. Tap Grant now to open Notification access",
            "2. Find FocusLock and turn it ON",
            "3. Confirm with Allow when asked"
        ),
        icon = Icons.Filled.Notifications
    )
    PermissionKind.BATTERY -> PermissionCopy(
        title = "Ignore battery optimizations",
        why = "Keeps protection alive in the background so Android doesn't kill FocusLock mid-day.",
        steps = listOf(
            "1. Tap Grant now to open the battery prompt",
            "2. Choose Allow / Don't optimize",
            "3. Go back to FocusLock"
        ),
        icon = Icons.Filled.BatteryChargingFull
    )
    PermissionKind.DEVICE_ADMIN -> PermissionCopy(
        title = "Prevent uninstall",
        why = "Prevents accidental/sneaky uninstalls during a binge — you can still remove via Settings > Disable admin.",
        steps = listOf(
            "1. Tap Grant now to open the device-admin prompt",
            "2. Tap Activate this device admin app",
            "3. To remove later: Settings > Disable admin, then uninstall"
        ),
        icon = Icons.Filled.Security
    )
    PermissionKind.POST_NOTIFICATIONS -> PermissionCopy(
        title = "App notifications",
        why = "Lets FocusLock confirm earned time, sync results, and protection status.",
        steps = listOf(
            "1. Tap Grant now to open notification settings",
            "2. Turn ON notifications for FocusLock",
            "3. Go back to FocusLock"
        ),
        icon = Icons.Filled.Warning
    )
}

@Composable
fun PermissionOnboardingDialog(
    missing: List<PermissionKind>,
    currentIndex: Int,
    onGrant: (PermissionKind) -> Unit,
    onDismiss: () -> Unit,
    onSkipAll: () -> Unit
) {
    if (missing.isEmpty()) return
    val safeIndex = currentIndex.coerceIn(0, missing.size - 1)
    val kind = missing[safeIndex]
    val copy = permissionCopy(kind)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            androidx.compose.material3.Icon(
                copy.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                "Step ${safeIndex + 1} of ${missing.size}: ${copy.title}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LinearProgressIndicator(
                    progress = { (safeIndex + 1) / missing.size.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    copy.why,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                copy.steps.forEach { step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "You can skip any step and grant it later from Settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onGrant(kind) },
                shape = RoundedCornerShape(20.dp)
            ) { Text("Grant now") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Later") }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onSkipAll) { Text("Skip all") }
            }
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(8.dp)
    )
}
