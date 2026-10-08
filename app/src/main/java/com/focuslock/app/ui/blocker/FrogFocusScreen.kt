package com.focuslock.app.ui.blocker

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.repository.FrogRepository
import com.focuslock.app.service.FrogAppPolicy
import com.focuslock.app.service.InstalledAppsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val FrogBlackScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color.White,
    onPrimary = androidx.compose.ui.graphics.Color.Black,
    background = androidx.compose.ui.graphics.Color.Black,
    onBackground = androidx.compose.ui.graphics.Color.White,
    surface = androidx.compose.ui.graphics.Color.Black,
    onSurface = androidx.compose.ui.graphics.Color.White,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1D1D1D),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFCCCCCC),
)

private val DEFAULT_SHORTCUT_LABELS = listOf("TickTick", "Phone", "Clock", "Messages", "WhatsApp", "ChatGPT", "Spotify", "Google Pay")

private data class FrogShortcut(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/** Minimal Frog launcher shown while the daily task is still locked. */
@Composable
internal fun FrogFocusScreen(
    onOpenFocusLock: () -> Unit,
    onFrogComplete: () -> Unit,
    onFrogEnded: () -> Unit = {},
    repository: FrogRepository = FocusLockApplication.instance.frogRepository,
    returnAppLabel: String? = null,
    onReturnToApp: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val state by repository.frogStateFlow.collectAsStateWithLifecycle(initialValue = null)
    val extraPackages = state?.takeIf { it.toolsConfirmed }?.allowedToolPackages.orEmpty()
        .sorted()
    val shortcuts by androidx.compose.runtime.produceState(emptyList<FrogShortcut>(), state != null, state?.essentialAppPackages, state?.boundaryAppPackages, extraPackages) {
        val current = state ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val defaults = FrogAppPolicy.defaultLaunchPackages(context)
            val defaultLabels = defaults.zip(DEFAULT_SHORTCUT_LABELS).toMap().toMutableMap().apply {
                putIfAbsent(FrogAppPolicy.GPAY_PACKAGE, "Google Pay")
                putIfAbsent(FrogAppPolicy.GPAY_WALLET_PACKAGE, "Google Pay")
            }
            val essentials = FrogAppPolicy.configuredLaunchPackages(defaults, current.essentialAppPackages, current.boundaryAppPackages)
            (essentials + extraPackages).distinct().map { pkg ->
                val label = defaultLabels[pkg] ?: InstalledAppsRepository.getAppLabel(context, pkg)
                FrogShortcut(
                    packageName = pkg,
                    label = label,
                    icon = InstalledAppsRepository.getAppIconBitmap(context, pkg),
                )
            }
        }
    }
    LaunchedEffect(state?.phase, state?.locked) {
        when {
            state?.phase == FrogPhase.COMPLETE -> onFrogComplete()
            state != null && state?.locked == false -> onFrogEnded()
        }
    }

    MaterialTheme(colorScheme = FrogBlackScheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp)) {
            val iconAreaHeight = maxHeight * 0.48f
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = "Finish your frog to unlock your phone.",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shortcuts.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = iconAreaHeight).verticalScroll(rememberScrollState())) {
                        FrogShortcutGrid(context, shortcuts)
                    }
                }
                if (returnAppLabel != null && onReturnToApp != null) {
                    OutlinedButton(
                        onClick = onReturnToApp,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text("Return to $returnAppLabel", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Button(
                    onClick = onOpenFocusLock,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                ) {
                    Text("Open FocusLock", style = MaterialTheme.typography.titleMedium)
                }
            }
          }
        }
    }
}

@Composable
private fun FrogShortcutGrid(context: Context, shortcuts: List<FrogShortcut>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      shortcuts.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
         row.forEach { shortcut ->
            Surface(
                onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage(shortcut.packageName)
                    if (intent == null) {
                        Toast.makeText(context, "${shortcut.label} is not installed yet.", Toast.LENGTH_SHORT).show()
                    } else {
                        runCatching {
                            com.focuslock.app.service.AppRedirectRecovery.noteLaunch(shortcut.packageName)
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                            .onFailure {
                                com.focuslock.app.service.AppRedirectRecovery.cancelLaunch(shortcut.packageName)
                                Toast.makeText(context, "Couldn't open ${shortcut.label}. Try opening it from FocusLock.", Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                enabled = true,
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .size(72.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = shortcut.label
                        role = Role.Button
                    },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val icon = shortcut.icon
                    if (icon != null) {
                        androidx.compose.foundation.Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                        )
                    } else {
                        Text(
                            text = shortcut.label.take(1),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
         }
        }
      }
        }
}
