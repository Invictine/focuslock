package com.focuslock.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.repository.FrogRepository
import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.model.FrogState
import com.focuslock.app.service.FrogAppPolicy
import com.focuslock.app.ui.settings.FrogEssentialAppsScreen
import com.focuslock.app.ui.theme.FocusLockTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated boundary input and preference file; never reads or edits a user's boundary data. */
@RunWith(AndroidJUnit4::class)
class FrogBoundaryEssentialsTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "frog-boundary-essentials-${UUID.randomUUID()}")
    private val job = SupervisorJob()
    private val store = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(job + Dispatchers.IO),
        produceFile = { File(directory, "choices.preferences_pb").apply { parentFile?.mkdirs() } },
    )
    private val boundaryPackages = MutableStateFlow<Set<String>>(emptySet())
    private val repository = FrogRepository(context, store, boundaryPackages)

    @After fun cleanup(): Unit = runBlocking { job.cancelAndJoin(); directory.deleteRecursively(); Unit }

    @Test fun saveAndLegacySelectionAreFilteredFromEveryEffectiveState() = runBlocking {
        boundaryPackages.value = setOf("com.whatsapp")
        assertTrue(repository.setEssentialApps(setOf("com.whatsapp", "com.spotify.music")))
        assertEquals(setOf("com.spotify.music"), repository.essentialAppPackagesFlow.first())
        assertEquals(setOf("com.spotify.music"), FrogRepository(context, store, boundaryPackages).essentialAppPackagesFlow.first())

        // Simulate a selection saved by an older app version before this package became a boundary.
        store.edit { it[FrogRepository.Keys.FROG_ESSENTIAL_APP_PACKAGES] = setOf("com.whatsapp", "com.spotify.music") }
        val current = repository.currentState()
        assertEquals(setOf("com.whatsapp"), current.boundaryAppPackages)
        assertEquals(setOf("com.spotify.music"), current.essentialAppPackages)
        val flowing = repository.frogStateFlow.first { it.boundaryAppPackages == setOf("com.whatsapp") }
        assertEquals(current.boundaryAppPackages, flowing.boundaryAppPackages)
        assertEquals(current.essentialAppPackages, flowing.essentialAppPackages)
        val locked = current.copy(locked = true)
        assertTrue("legacy-selected boundary app must be blocked", FrogAppPolicy.isBlocked(context, "com.whatsapp", locked))
        assertFalse("unrelated selected app remains essential", FrogAppPolicy.isBlocked(context, "com.spotify.music", locked))

        val nextFlowState = async(start = CoroutineStart.UNDISPATCHED) {
            repository.frogStateFlow.first {
                it.boundaryAppPackages == setOf("com.spotify.music") &&
                    it.essentialAppPackages == setOf("com.whatsapp")
            }
        }
        boundaryPackages.value = setOf("com.spotify.music")
        val emitted = withTimeout(10_000) { nextFlowState.await() }
        assertEquals(setOf("com.spotify.music"), emitted.boundaryAppPackages)
        assertEquals(setOf("com.whatsapp"), emitted.essentialAppPackages)
        val updated = repository.currentState()
        assertEquals(setOf("com.spotify.music"), updated.boundaryAppPackages)
        assertFalse(updated.essentialAppPackages.orEmpty().contains("com.spotify.music"))
        assertTrue(FrogAppPolicy.isBlocked(context, "com.spotify.music", updated.copy(locked = true)))
    }

    @Test fun boundaryChangeDisablesPickerRowAndRemovesAlreadySelectedApp() = runBlocking {
        assertTrue(repository.setEssentialApps(setOf("com.whatsapp", "com.spotify.music")))
        boundaryPackages.value = setOf("com.whatsapp")
        var back = false
        compose.setContent {
            FocusLockTheme {
                FrogEssentialAppsScreen(onBack = { back = true }, repository = repository)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("WhatsApp") and isToggleable()).fetchSemanticsNodes().isNotEmpty()
        }
        val whatsapp = compose.onNode(hasText("WhatsApp") and isToggleable())
        whatsapp.assertIsOff().assertIsNotEnabled()
        compose.onNode(hasText("Spotify") and isToggleable()).performScrollTo().assertIsOn()

        // A live change removes an already-selected app and disables it immediately.
        boundaryPackages.value = setOf("com.whatsapp", "com.spotify.music")
        compose.waitUntil(10_000) {
            try {
                compose.onNode(hasText("Spotify") and isToggleable()).assertIsOff().assertIsNotEnabled()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { back }
        assertFalse(repository.currentState().essentialAppPackages.orEmpty().any { it in boundaryPackages.value })
    }

    @Test fun walletBoundaryCannotBeExemptedBySelectedGooglePayAlias() = runBlocking {
        boundaryPackages.value = setOf(FrogAppPolicy.GPAY_WALLET_PACKAGE)
        assertTrue(repository.setEssentialApps(setOf(FrogAppPolicy.GPAY_PACKAGE)))
        val state = repository.currentState().copy(locked = true)
        assertTrue(state.essentialAppPackages?.contains(FrogAppPolicy.GPAY_PACKAGE) == true)
        assertTrue(state.boundaryAppPackages.contains(FrogAppPolicy.GPAY_WALLET_PACKAGE))
        assertTrue(FrogAppPolicy.isBlocked(context, FrogAppPolicy.GPAY_WALLET_PACKAGE, state))
    }

    @Test fun contextConfiguredShortcutsExcludeBoundaryPackages() = runBlocking {
        val defaults = FrogAppPolicy.defaultLaunchPackages(context)
        boundaryPackages.value = defaults.toSet()
        val state = FrogState(
            cycleDate = "2026-10-08",
            enabled = true,
            armed = true,
            phase = FrogPhase.WORKING,
            frog = null,
            tickedOff = false,
            trackedSeconds = 0,
            requiredSeconds = 60,
            locked = true,
            openTasks = emptyList(),
            essentialAppPackages = null,
            boundaryAppPackages = repository.boundaryAppPackagesFlow.first(),
        )

        assertTrue(FrogAppPolicy.configuredLaunchPackages(context, state).isEmpty())
    }
}
