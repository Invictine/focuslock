package com.focuslock.app.ui.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
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
    val progress by animateFloatAsState(
        targetValue = (safeIndex + 1).toFloat() / productTourPages.size,
        animationSpec = tween(durationMillis = 240),
        label = "guide progress",
    )
    LaunchedEffect(safeIndex, showTopics) { scrollState.scrollTo(0) }

    Dialog(
        onDismissRequest = { if (showTopics) showTopics = false else onPause() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        val shortWindow = maxHeight < 600.dp
        Surface(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (shortWindow) 4.dp else 16.dp)
                .widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (shortWindow) 8.dp else 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("FocusLock guide", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text("Learn by doing", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { showTopics = !showTopics }) {
                            Text(if (showTopics) "Close topics" else "All topics")
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                            .semantics { contentDescription = "Guide progress: ${safeIndex + 1} of ${productTourPages.size}" },
                    )
                    Text("${safeIndex + 1} of ${productTourPages.size}  ·  ${page.section}",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
                        .animateContentSize().padding(horizontal = 20.dp, vertical = if (shortWindow) 12.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (shortWindow) 12.dp else 20.dp),
                ) {
                    if (showTopics) {
                        Text("Explore every feature", modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Choose a topic to jump there. Your place is saved as you go.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            productTourPages.forEachIndexed { index, topic ->
                                OutlinedButton(
                                    onClick = { onPageChange(index); showTopics = false },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    Text("${index + 1}", modifier = Modifier.width(28.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold)
                                    Text(topic.title, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(page.title, modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            Text(page.summary, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Text("Try it in the app", style = MaterialTheme.typography.titleMedium)
                        Text(page.liveTasks().first(), style = MaterialTheme.typography.bodyLarge)
                        if (page.destination != null && page.actionLabel != null) {
                            Button(
                                onClick = { onOpenDestination(page.destination) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Start walkthrough") }
                            Text("Follow one step at a time beside the real controls. You can review or skip any optional setting.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (page.id == "frog" || page.id == "strict") {
                            Text("You stay in control: this guide never switches it on.",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (shortWindow) 4.dp else 12.dp)) {
                    if (!showTopics) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { onPageChange(safeIndex - 1) }, enabled = safeIndex > 0,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) { Text("Back") }
                            Button(
                                onClick = { if (safeIndex == productTourPages.lastIndex) onFinish()
                                    else onPageChange(safeIndex + 1) },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) { Text(if (safeIndex == productTourPages.lastIndex) "Finish guide" else "Next") }
                        }
                    }
                    TextButton(onClick = onPause, modifier = Modifier.align(Alignment.CenterHorizontally)
                        .heightIn(min = 48.dp)) { Text("Save for later") }
                }
            }
        }
    }
    }
}
