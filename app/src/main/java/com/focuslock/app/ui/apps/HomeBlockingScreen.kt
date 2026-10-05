package com.focuslock.app.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.location.HomeLocationStatus
import com.focuslock.app.location.HomePlace
import com.focuslock.app.ui.components.ScreenHeader
import com.focuslock.app.ui.components.UiTokens
import com.focuslock.app.ui.strict.HomeLocationCard
import com.focuslock.app.ui.strict.StrictLocationPickerDialog
import com.focuslock.app.ui.strict.StrictLocationSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeBlockingScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val homeRepository = FocusLockApplication.instance.homeLocationRepository
    val homePlace by homeRepository.homePlaceFlow.collectAsStateWithLifecycle(initialValue = null)
    val homeOnly by homeRepository.homeOnlyFlow.collectAsStateWithLifecycle(initialValue = false)
    var homeStatus by remember { mutableStateOf(HomeLocationStatus.UNAVAILABLE) }
    var showHomePicker by remember { mutableStateOf(false) }
    var homePickerSelection by remember { mutableStateOf<StrictLocationSelection?>(null) }
    var homePickerLabel by remember { mutableStateOf("Home") }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(homeRepository, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                homeStatus = homeRepository.statusNow(requireBackgroundPermission = true)
                delay(10_000L)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UiTokens.ScreenPadding)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenHeader(title = "Blocking location", subtitle = "Only a fresh, precise away location pauses everyday blocking. Unknown location keeps blocking active; permanent blocks always stay active.", onBack = onBack)
        HomeLocationCard(
            homePlace = homePlace,
            enabled = homeOnly,
            status = homeStatus,
            onSaveHome = { place -> scope.launch {
                homeRepository.saveHome(place)
                if (!homeOnly) homeRepository.setHomeOnly(false)
            } },
            onHomeOnlyChange = { enabled -> scope.launch { homeRepository.setHomeOnly(enabled) } },
            onChooseLocation = { initial, label ->
                homePickerSelection = initial
                homePickerLabel = label
                showHomePicker = true
            }
        )
    }
    if (showHomePicker) {
        StrictLocationPickerDialog(
            initialLabel = homePickerSelection?.label ?: "",
            initialSelection = homePickerSelection,
            onDismiss = { showHomePicker = false }
        ) { selection ->
            scope.launch {
                homeRepository.saveHome(HomePlace(homePickerLabel.ifBlank { "Home" }, selection.latitude, selection.longitude, selection.radiusMeters))
            }
            showHomePicker = false
        }
    }
}
