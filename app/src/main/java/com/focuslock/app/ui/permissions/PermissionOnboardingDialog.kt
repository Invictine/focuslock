package com.focuslock.app.ui.permissions

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            "1. Tap Open settings to go to Accessibility",
            "2. Find FocusLock and turn it ON",
            "3. Confirm with Allow when asked"
        ),
        icon = Icons.Filled.Accessibility
    )
    PermissionKind.USAGE -> PermissionCopy(
        title = "Usage access",
        why = "Powers the screen-time dashboard so you can see exactly where your time goes.",
        steps = listOf(
            "1. Tap Open settings to open app info",
            "2. Open Usage access / Screen time permission",
            "3. Allow access for FocusLock"
        ),
        icon = Icons.Filled.Analytics
    )
    PermissionKind.OVERLAY -> PermissionCopy(
        title = "Display over other apps",
        why = "Shows the fullscreen lockout on top of blocked apps — without it, blocking can't appear.",
        steps = listOf(
            "1. Tap Open settings to open the overlay page for FocusLock",
            "2. Turn ON Allow display over other apps",
            "3. Go back to FocusLock"
        ),
        icon = Icons.Filled.Layers
    )
    PermissionKind.NOTIFICATION_LISTENER -> PermissionCopy(
        title = "Notification access",
        why = "Catches TickTick Pomodoros and completed tasks automatically as they finish.",
        steps = listOf(
            "1. Tap Open settings to go to Notification access",
            "2. Find FocusLock and turn it ON",
            "3. Confirm with Allow when asked"
        ),
        icon = Icons.Filled.Notifications
    )
    PermissionKind.BATTERY -> PermissionCopy(
        title = "Ignore battery optimizations",
        why = "Keeps protection alive in the background so Android doesn't kill FocusLock mid-day.",
        steps = listOf(
            "1. Tap Open settings to open the battery prompt",
            "2. Choose Allow / Don't optimize",
            "3. Go back to FocusLock"
        ),
        icon = Icons.Filled.BatteryChargingFull
    )
    PermissionKind.DEVICE_ADMIN -> PermissionCopy(
        title = "Prevent uninstall",
        why = "Prevents accidental/sneaky uninstalls during a binge — you can still remove via Settings > Disable admin.",
        steps = listOf(
            "1. Tap Open settings to open the device-admin prompt",
            "2. Tap Activate this device admin app",
            "3. To remove later: Settings > Disable admin, then uninstall"
        ),
        icon = Icons.Filled.Security
    )
    PermissionKind.POST_NOTIFICATIONS -> PermissionCopy(
        title = "App notifications",
        why = "Lets FocusLock confirm earned time, sync results, and protection status.",
        steps = listOf(
            "1. Tap Open settings to open notification settings",
            "2. Turn ON notifications for FocusLock",
            "3. Go back to FocusLock"
        ),
        icon = Icons.Filled.Warning
    )
}

/**
 * One permission step. The call site (DashboardScreen) passes the current missing list and
 * callbacks; all state here is internal, so the signature stays backwards-compatible.
 *
 * The dialog re-checks the live grant state on every ON_RESUME, so returning from Android
 * Settings flips the status pill and turns the primary action into Next/Done. Returning is
 * automatic: PermissionReturnWatcher (accessibility service + in-app fallback) brings the
 * app back to the front as soon as the permission is granted.
 *
 * Layout follows Material 3 dialog metrics: a centered header (tonal icon badge, title with
 * the step count, progress bar, status pill), a left-aligned body (explanation, numbered
 * step rows, fine print), then a full-width primary action and a secondary text-button row.
 * The card is a custom centered Dialog so it sits at the true screen center with balanced
 * spacing and a full-width progress bar.
 */
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
    val context = LocalContext.current

    // Binder-backed check: computed off the main thread whenever the kind changes or the
    // app resumes (same pattern as MainActivity). The Pair discards another step's result,
    // and resetting to null up front prevents showing this kind's previous (stale) result
    // while the ON_RESUME re-check is still in flight.
    var permissionTick by remember(kind) { mutableIntStateOf(0) }
    val grantState by produceState<Pair<PermissionKind, Boolean>?>(null, kind, permissionTick) {
        value = null
        value = kind to withContext(Dispatchers.IO) { PermissionHelper.isGranted(context, kind) }
    }
    val granted = grantState?.takeIf { it.first == kind }?.second ?: false

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, kind) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isLast = safeIndex >= missing.size - 1

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 440.dp)
                    // Consume taps on the card so they don't fall through to the dismiss overlay.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // ---- Centered header: icon badge, title, progress, status pill ----

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = copy.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Step ${safeIndex + 1} of ${missing.size}: ${copy.title}",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { safeIndex / missing.size.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .height(6.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    StatusPill(granted = granted)

                    // ---- Left-aligned body: explanation, numbered steps, fine print ----

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = copy.why,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        copy.steps.forEachIndexed { index, step ->
                            StepRow(
                                number = index + 1,
                                text = step.replace(STEP_NUMBER_PREFIX, "")
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "FocusLock comes back here automatically once it's granted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // ---- Actions ----

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { if (granted) onDismiss() else onGrant(kind) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(if (granted) { if (isLast) "Done" else "Next" } else "Open settings")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) { Text("Skip this step") }
                        TextButton(
                            onClick = onSkipAll,
                            modifier = Modifier.weight(1f)
                        ) { Text("Skip all") }
                    }
                }
            }
        }
    }
}

/** Matches a leading "1. " / "1) " enumerator so the step row can show the number in a badge. */
private val STEP_NUMBER_PREFIX = Regex("^\\s*\\d+[.)]\\s*")

/** One numbered how-to step: tonal number badge + short left-aligned instruction. */
@Composable
private fun StepRow(number: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatusPill(granted: Boolean) {
    Surface(
        color = if (granted) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Info,
                contentDescription = null,
                tint = if (granted) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (granted) "Granted" else "Not granted yet",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (granted) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )
        }
    }
}
