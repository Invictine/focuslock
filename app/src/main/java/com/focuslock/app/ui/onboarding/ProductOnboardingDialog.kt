package com.focuslock.app.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Reading the guide never grants permissions or enables a commitment. */
@Composable
internal fun ProductOnboardingDialog(
    pageIndex: Int,
    onPageChange: (Int) -> Unit,
    onPause: () -> Unit,
    onFinish: () -> Unit,
    onOpenDestination: (ProductTourDestination) -> Unit,
) {
    val safeIndex = pageIndex.coerceIn(productTourPages.indices)
    val page = productTourPages[safeIndex]
    var showTopics by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    LaunchedEffect(safeIndex, showTopics) { scrollState.scrollTo(0) }

    Dialog(
        onDismissRequest = { if (showTopics) showTopics = false else onPause() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                .widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("FocusLock guide", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showTopics = !showTopics }) {
                            Text(if (showTopics) "Close topics" else "Topics")
                        }
                    }
                    LinearProgressIndicator(
                        progress = { (safeIndex + 1).toFloat() / productTourPages.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${safeIndex + 1} of ${productTourPages.size} · ${page.section}",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (showTopics) {
                        Text("Explore every topic", modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineSmall)
                        Text("Jump to a chapter, or continue where you left off.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        productTourPages.forEachIndexed { index, topic ->
                            OutlinedButton(
                                onClick = { onPageChange(index); showTopics = false },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text("${index + 1}. ${topic.title}", modifier = Modifier.fillMaxWidth())
                            }
                        }
                    } else {
                        Text(page.title, modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                        Text(page.summary, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        page.points.forEach { point ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("•", color = MaterialTheme.colorScheme.primary)
                                Text(point, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (safeIndex == productTourPages.lastIndex) {
                            OutlinedButton(
                                onClick = { onOpenDestination(ProductTourDestination.SETTINGS) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Set up permissions") }
                        } else if (page.destination != null && page.actionLabel != null) {
                            OutlinedButton(
                                onClick = { onOpenDestination(page.destination) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(page.actionLabel) }
                        }
                        if (page.destination != null) {
                            Text("Setup opens the app and saves your place. Resume this guide in Settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    if (!showTopics) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { onPageChange(safeIndex - 1) }, enabled = safeIndex > 0,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) { Text("Back") }
                            Button(
                                onClick = { if (safeIndex == productTourPages.lastIndex) onFinish()
                                    else onPageChange(safeIndex + 1) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) { Text(if (safeIndex == productTourPages.lastIndex) "Finish guide" else "Continue") }
                        }
                    }
                    TextButton(onClick = onPause, modifier = Modifier.align(Alignment.CenterHorizontally)
                        .heightIn(min = 48.dp)) { Text("Save for later") }
                }
            }
        }
    }
}
