package com.focuslock.app.ui.strict

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.ui.components.IconBadge
import com.focuslock.app.ui.components.MotionTokens
import com.focuslock.app.ui.components.ScreenHeader
import com.focuslock.app.ui.components.StaggeredFadeSlide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Strict Mode tab. Owns the whole 24-hour no-unlock commitment UI that used to live
 * inside SettingsScreen: the enable/disable confirmation dialogs, the error-container
 * status hero with a live cooldown countdown, and the consequences list.
 *
 * Persistence is unchanged — everything goes through [SettingsRepository.setLockdownMode],
 * and the cooldown is enforced by [SettingsRepository.canDisableLockdownMode].
 */
@Composable
fun StrictModeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = FocusLockApplication.instance.settingsRepository
    val strictMode by settings.lockdownModeFlow.collectAsStateWithLifecycle(initialValue = false)

    var showEnableDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    // One-shot entrance cascade (header -> intro -> hero -> rules); remembered so it
    // runs on first composition only and never replays on scroll or state flips.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ScreenHeader's subtitle slot is capped at two ellipsized lines; this commitment
        // copy is longer, so it stays a full body paragraph under the shared title.
        // No extra top padding here: the Scaffold insets own the status-bar protection
        // and ScreenHeader bakes in only a minimal 4dp rhythm gap.
        StaggeredFadeSlide(visible = entered, index = 0) {
            ScreenHeader(title = "Strict Mode")
        }
        StaggeredFadeSlide(visible = entered, index = 1) {
            Text(
                text = "A 24-hour commitment: turn it on and FocusLock refuses every unlock " +
                    "until the day is up — no switching off, no emergency exit, no earned-credit bypass.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        StaggeredFadeSlide(visible = entered, index = 2, modifier = Modifier.fillMaxWidth()) {
            AnimatedContent(
                targetState = strictMode,
                transitionSpec = {
                    (fadeIn(animationSpec = MotionTokens.FadeFloat) +
                        slideInVertically(
                            animationSpec = MotionTokens.SpatialOffset,
                            initialOffsetY = { it / 12 }
                        )) togetherWith fadeOut(animationSpec = MotionTokens.FadeFloat)
                },
                label = "strictHero"
            ) { active ->
                if (active) {
                    StrictActiveCard(
                        settings = settings,
                        onDisableClick = {
                            scope.launch {
                                val canDisable = try {
                                    settings.canDisableLockdownMode()
                                } catch (_: Exception) {
                                    true
                                }
                                if (!canDisable) {
                                    val remaining = try {
                                        settings.lockdownCooldownRemainingMs()
                                    } catch (_: Exception) {
                                        0L
                                    }
                                    Toast.makeText(
                                        context,
                                        "Strict Mode locked: ${formatLockdownRemaining(remaining)} remaining",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    showDisableDialog = true
                                }
                            }
                        }
                    )
                } else {
                    StrictOffCard(onEnableClick = { showEnableDialog = true })
                }
            }
        }

        StaggeredFadeSlide(visible = entered, index = 3, modifier = Modifier.fillMaxWidth()) {
            StrictRulesCard(visible = entered)
        }

        if (showEnableDialog) {
            AlertDialog(
                onDismissRequest = { showEnableDialog = false },
                title = { Text("Enable Strict Mode?") },
                text = { Text("Strict Mode locks in for 24 hours: no unlocks at all for 24h after enabling.") },
                confirmButton = {
                    TextButton(onClick = {
                        showEnableDialog = false
                        scope.launch { settings.setLockdownMode(true) }
                    }) { Text("Enable") }
                },
                dismissButton = {
                    TextButton(onClick = { showEnableDialog = false }) { Text("Cancel") }
                },
                shape = MaterialTheme.shapes.large
            )
        }
        if (showDisableDialog) {
            AlertDialog(
                onDismissRequest = { showDisableDialog = false },
                title = { Text("Disable Strict Mode?") },
                text = { Text("This re-enables unlock options.") },
                confirmButton = {
                    TextButton(onClick = {
                        showDisableDialog = false
                        scope.launch { settings.setLockdownMode(false) }
                    }) { Text("Disable") }
                },
                dismissButton = {
                    TextButton(onClick = { showDisableDialog = false }) { Text("Cancel") }
                },
                shape = MaterialTheme.shapes.large
            )
        }
    }
}

/** Neutral tonal hero shown while Strict Mode is off, with the primary enable action. */
@Composable
private fun StrictOffCard(onEnableClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
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
                    icon = Icons.Rounded.Shield,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Strict Mode is off",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Ready when you need a hard reset",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "Enabling commits you to a full 24 hours with no unlocks. " +
                    "The cooldown starts the moment you turn it on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onEnableClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Enable Strict Mode", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Prominent error-container hero with a live countdown while Strict Mode is active. */
@Composable
private fun StrictActiveCard(settings: SettingsRepository, onDisableClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
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
                    icon = Icons.Rounded.Lock,
                    containerColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Strict Mode is active",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    StrictCooldownText(settings)
                }
            }
            Text(
                text = "Unlocks are disabled for 24 hours after enabling — emergency unlock, " +
                    "earned credits and turning the switch off are all refused.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(
                onClick = onDisableClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onErrorContainer,
                    contentColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Text("Disable", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Short list of consequences, each stated from actual enforcement code paths. */
@Composable
private fun StrictRulesCard(visible: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "What happens in Strict Mode",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            // Rule rows cascade in after the card, capped so the tail settles fast.
            val rules = listOf(
                Triple(
                    Icons.Rounded.Lock,
                    "No early exit",
                    "The Disable button stays locked for 24 hours after enabling — " +
                        "nothing in the app can turn Strict Mode off sooner."
                ),
                Triple(
                    Icons.Rounded.Block,
                    "No unlock paths",
                    "Emergency unlocks and earned-credit unlocks are refused. Credits are " +
                        "still banked, they just can't open a blocked app."
                ),
                Triple(
                    Icons.Rounded.Shield,
                    "Boundaries stay locked",
                    "Blocked apps and websites can't be unblocked until Strict Mode ends — " +
                        "you can still add new blocks."
                ),
                Triple(
                    Icons.Rounded.Timer,
                    "No grace period",
                    "Blocked apps and sites open the blocker immediately, even while you " +
                        "still have leisure balance."
                ),
                Triple(
                    Icons.Rounded.Sync,
                    "Carries across devices",
                    "When you're signed in, the commitment syncs so switching devices " +
                        "won't dodge the lock."
                )
            )
            rules.forEachIndexed { index, (icon, title, body) ->
                StaggeredFadeSlide(visible = visible, index = 4 + minOf(index, 2)) {
                    StrictRuleRow(icon = icon, title = title, body = body)
                }
            }
        }
    }
}

@Composable
private fun StrictRuleRow(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(
            icon = icon,
            size = 36.dp,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StrictCooldownText(settings: SettingsRepository) {
    var cooldownText by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Repeat only while RESUMED so a backgrounded screen stops refreshing its state.
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val remaining = try {
                    settings.lockdownCooldownRemainingMs()
                } catch (_: Exception) {
                    0L
                }
                cooldownText = if (remaining > 0L) {
                    "Unlock available in ${formatLockdownRemaining(remaining)}"
                } else {
                    "Unlock available now"
                }
                delay(30_000)
            }
        }
    }
    if (cooldownText.isNotBlank()) {
        Text(
            text = cooldownText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/**
 * Formats a cooldown remaining time as "Xh Ym". Internal so the Boundaries screens can
 * reuse the exact same formatter in Strict Mode refusal copy.
 */
internal fun formatLockdownRemaining(ms: Long): String {
    val h = ms / 3_600_000L
    val m = (ms % 3_600_000L) / 60_000L
    return "${h}h ${m}m"
}