package com.focuslock.app.ui.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
        Surface(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                .widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(0.94f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("FocusLock guide", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text("One feature at a time", style = MaterialTheme.typography.bodySmall,
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
                        .animateContentSize().padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
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

                        TourExample(page = page)

                        page.points.forEach { point ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top) {
                                Surface(
                                    modifier = Modifier.padding(top = 6.dp).size(8.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                ) {}
                                Text(point, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f))
                            }
                        }

                        if (page.destination != null && page.actionLabel != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { onOpenDestination(page.destination) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                ) { Text(page.actionLabel) }
                                Text("Opens this feature and saves your place. Resume the guide from Settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (page.id == "frog" || page.id == "strict") {
                            Text("You stay in control: this guide never switches it on.",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
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

@Composable
private fun TourExample(page: ProductTourPage) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(page.exampleTitle, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            page.exampleSteps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary) {
                        Text("${index + 1}", modifier = Modifier.size(28.dp)
                            .wrapContentSize(Alignment.Center),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(step, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}
