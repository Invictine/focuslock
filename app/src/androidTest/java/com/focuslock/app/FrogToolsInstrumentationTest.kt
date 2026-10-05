package com.focuslock.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.data.model.FrogTask
import com.focuslock.app.data.repository.FrogRepository
import com.focuslock.app.service.FrogAppPolicy
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side Frog state/policy checks using an isolated private DataStore directory. */
@RunWith(AndroidJUnit4::class)
class FrogToolsInstrumentationTest {
    private val baseContext: Context = ApplicationProvider.getApplicationContext()
    private val isolatedRoot = File(baseContext.cacheDir, "frog-instrumentation-${UUID.randomUUID()}")
    private val isolatedJob = SupervisorJob()
    private val isolatedScope = CoroutineScope(isolatedJob + Dispatchers.IO)
    private val isolatedDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = isolatedScope,
        produceFile = {
            File(isolatedRoot, "datastore/focuslock_frog.preferences_pb").apply {
                parentFile?.mkdirs()
            }
        },
    )
    private val repo by lazy { FrogRepository(baseContext, isolatedDataStore) }

    @After
    fun cleanIsolatedStore(): Unit = runBlocking {
        isolatedJob.cancelAndJoin()
        isolatedRoot.deleteRecursively()
        Unit
    }

    @Test
    fun firstToolConfirmationIsFrozenAndReadableAfterRepositoryRecreation() = runBlocking {
        val now = cycleNoon()
        val firstTool = "com.example.notes"
        val laterTool = "com.example.pdf"
        val repository = repo
        assertTrue(repository.armIfDue(now))
        repository.selectFrog(task("chemistry"), now)

        assertTrue(repository.confirmTools(setOf(firstTool), now))
        assertFalse("A second confirmation must not replace the active tool set", repository.confirmTools(setOf(laterTool), now))

        val recreated = FrogRepository(baseContext, isolatedDataStore)
        val state = recreated.currentState(now)
        assertEquals(FrogPhase.WORKING, state.phase)
        assertTrue(state.toolsConfirmed)
        assertEquals(setOf(firstTool), state.allowedToolPackages)
    }

    @Test
    fun cannotConfirmToolsBeforeSelectingTaskAndPolicyDeniesByDefault() = runBlocking {
        val now = cycleNoon()
        val repository = repo
        assertTrue(repository.armIfDue(now))

        assertFalse(repository.confirmTools(setOf("com.example.notes"), now))
        val state = repository.currentState(now)
        assertEquals(FrogPhase.PICK_FROG, state.phase)
        assertFalse(state.toolsConfirmed)
        assertTrue(state.allowedToolPackages.isEmpty())

        val essential = "com.android.phone"
        assertTrue(FrogAppPolicy.shouldBlock("com.example.unknown", true, state.toolsConfirmed, state.allowedToolPackages, setOf(essential)))
        assertFalse(FrogAppPolicy.shouldBlock(essential, true, state.toolsConfirmed, state.allowedToolPackages, setOf(essential)))
    }

    @Test
    fun taskAndCycleResetConfirmationWhileKeepingNextCycleSuggestions() = runBlocking {
        val firstCycle = cycleNoon()
        val repository = repo
        assertTrue(repository.armIfDue(firstCycle))
        repository.selectFrog(task("chemistry"), firstCycle)
        assertTrue(repository.confirmTools(setOf("com.example.notes"), firstCycle))

        repository.selectFrog(task("physics"), firstCycle)
        val changedTask = repository.currentState(firstCycle)
        assertEquals(FrogPhase.PICK_TOOLS, changedTask.phase)
        assertFalse(changedTask.toolsConfirmed)
        assertEquals(0, changedTask.trackedSeconds)
        assertFalse(changedTask.tickedOff)

        assertTrue(repository.confirmTools(setOf("com.example.calculator"), firstCycle))
        assertTrue(FrogAppPolicy.shouldBlock("com.example.unknown", true, true, setOf("com.example.calculator"), emptySet()))
        assertFalse(FrogAppPolicy.shouldBlock("com.example.calculator", true, true, setOf("com.example.calculator"), emptySet()))

        val nextCycle = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(firstCycle),
            java.time.ZoneId.systemDefault(),
        ).plusDays(1).toInstant().toEpochMilli()
        val reset = repository.currentState(nextCycle)
        assertFalse(reset.armed)
        assertFalse(reset.toolsConfirmed)
        assertEquals(FrogPhase.NOT_ARMED, reset.phase)
        assertEquals("com.example.calculator", reset.allowedToolPackages.single())
    }

    @Test
    fun essentialAppsDistinguishUnsetFromExplicitEmptyAndPersistAcrossCycles() = runBlocking {
        val firstCycle = cycleNoon()
        val repository = repo
        assertEquals(null, repository.essentialAppPackagesFlow.first())
        assertEquals(null, repository.currentState(firstCycle).essentialAppPackages)

        assertTrue(repository.setEssentialApps(emptySet()))
        assertEquals(emptySet<String>(), repository.essentialAppPackagesFlow.first())
        assertEquals(emptySet<String>(), repository.currentState(firstCycle).essentialAppPackages)

        assertTrue(
            repository.setEssentialApps(
                setOf(" com.whatsapp ", "com.openai.chatgpt", "invalid package", "bad..package"),
            )
        )
        val selectedPackages = setOf("com.whatsapp", "com.openai.chatgpt")
        assertEquals(selectedPackages, repository.essentialAppPackagesFlow.first())

        repository.selectFrog(task("biology"), firstCycle)
        val recreated = FrogRepository(baseContext, isolatedDataStore)
        assertEquals(selectedPackages, recreated.currentState(firstCycle).essentialAppPackages)

        val nextCycle = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(firstCycle),
            java.time.ZoneId.systemDefault(),
        ).plusDays(1).toInstant().toEpochMilli()
        assertEquals(selectedPackages, recreated.currentState(nextCycle).essentialAppPackages)
    }

    private fun cycleNoon(): Long = ZonedDateTime.now()
        .plusDays(1)
        .withHour(12)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)
        .toInstant()
        .toEpochMilli()

    private fun task(id: String) = FrogTask(id = id, title = id)
}
