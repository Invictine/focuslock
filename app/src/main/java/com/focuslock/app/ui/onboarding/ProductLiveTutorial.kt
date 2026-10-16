package com.focuslock.app.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** A supporting surface: the real screen remains interactive above it. */
@Composable
internal fun ProductLiveTutorial(
    pageIndex: Int,
    onReview: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onNavigate: (ProductTourDestination) -> Unit = {},
    includeNavigationInset: Boolean = true,
) {
    val page = productTourPages[pageIndex.coerceIn(productTourPages.indices)]
    val tasks = page.liveTasks()
    val compactWindow = LocalConfiguration.current.screenHeightDp < 600
    val store = rememberOnboardingStore()
    var step by rememberSaveable(page.id, tasks.size) {
        mutableIntStateOf(store.taskIndex(page.id).coerceIn(tasks.indices))
    }
    LaunchedEffect(page.id, step) { onNavigate(page.liveDestination(step)) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    Surface(tonalElevation = 3.dp) {
        Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()
            .then(if (includeNavigationInset) Modifier.navigationBarsPadding() else Modifier)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${page.section} / ${step + 1}/${tasks.size}", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide steps" else "Show steps") }
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().heightIn(max = if (compactWindow) 64.dp else 112.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Text(tasks[step], style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onPause) { Text("Pause") }
                    TextButton(onClick = {
                        if (step > 0) { step--; store.saveTask(page.id, step) } else onReview()
                    }) { Text("Back") }
                    TextButton(onClick = {
                        if (step < tasks.lastIndex) { step++; store.saveTask(page.id, step) } else onNext()
                    }) { Text(if (step == tasks.lastIndex) "Next feature" else "Continue") }
                }
            }
        }
        }
    }
}

@Composable
private fun rememberOnboardingStore(): ProductOnboardingStore {
    val context = LocalContext.current
    return remember(context) { ProductOnboardingStore(context) }
}
