package com.focuslock.app.strict

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StrictAccessRepositoryTest {
    private fun repository(context: Context, active: () -> Boolean = { false }): Triple<StrictAccessRepository, CoroutineScope, java.io.File> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = context.cacheDir.resolve("strict-test-${UUID.randomUUID()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return Triple(StrictAccessRepository(context, store, active), scope, file)
    }

    @Test
    fun selectedRoutesAreOrAndRequestedRouteMustBeConfigured(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (repository, scope, file) = repository(context)
        try {
            repository.save(StrictAccessConfig(method = "pin", methods = listOf("pin", "cooldown"), cooldownMinutes = 2), pin = "1234")
            assertEquals(listOf("pin", "cooldown"), repository.getConfig().selectedMethods)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.requestOfflineUnlock(System.currentTimeMillis() + 60_000, "1234", "charger") }
            }
            assertEquals(System.currentTimeMillis() / 1_000, repository.requestOfflineUnlock(System.currentTimeMillis() + 60_000, "1234", "pin") / 1_000)
        } finally { scope.cancel(); file.delete() }
    }

    @Test
    fun activeCommitmentAcceptsOnlySameOwnerProtectionStrengthening(): Unit = runBlocking {
        var commitmentActive = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (repository, scope, file) = repository(context) { commitmentActive }
        try {
            val owner = "owner-${UUID.randomUUID()}"
            val initial = StrictAccessConfig(method = "cooldown", methods = listOf("cooldown", "pin"), cooldownMinutes = 4)
            repository.save(initial, pin = "1234", accountId = owner)
            commitmentActive = true
            val strengthened = repository.getConfig().copy(preventUninstall = true)
            repository.applyRemote(strengthened, owner)
            assertEquals(strengthened, repository.getConfig())
            val locallyStrengthened = strengthened.copy(blockRecents = true)
            repository.save(locallyStrengthened, accountId = owner)
            assertEquals(locallyStrengthened, repository.getConfig())
            repository.applyRemote(locallyStrengthened.copy(methods = listOf("pin")), owner)
            assertEquals(locallyStrengthened, repository.getConfig())
            repository.applyRemote(locallyStrengthened.copy(preventUninstall = false), owner)
            assertEquals(locallyStrengthened, repository.getConfig())
            repository.applyRemote(locallyStrengthened.copy(blockDeviceSettings = true), "other-${UUID.randomUUID()}")
            assertEquals(locallyStrengthened, repository.getConfig())
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.save(locallyStrengthened, pin = "5678", accountId = owner) }
            }
            assertEquals(locallyStrengthened, repository.getConfig())
        } finally { scope.cancel(); file.delete() }
    }

    @Test
    fun pinRouteRequiresPinEvenWhenLegacyPrimaryMethodIsDifferent(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (repository, scope, file) = repository(context)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.save(StrictAccessConfig(method = "cooldown", methods = listOf("cooldown", "pin"))) }
            }
        } finally { scope.cancel(); file.delete() }
    }

    @Test
    fun activeCommitmentKeepsItsAccountOwnerAcrossRemoteAccountSwitch() = runBlocking {
        var commitmentActive = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val testFile = context.cacheDir.resolve("strict-test-${UUID.randomUUID()}.preferences_pb")
        val testStore = PreferenceDataStoreFactory.create(scope = testScope, produceFile = { testFile })
        val repository = StrictAccessRepository(context, testStore) { commitmentActive }
        try {
        val owner = "owner-${UUID.randomUUID()}"
        val other = "other-${UUID.randomUUID()}"
        val local = StrictAccessConfig(
            method = "cooldown",
            cooldownMinutes = 4,
            preventUninstall = true,
            revision = 3,
        )
        repository.save(local, accountId = owner)
        commitmentActive = true

        repository.applyRemote(
            StrictAccessConfig(method = "timer", revision = 1),
            accountId = other,
        )

        assertEquals(owner, repository.ownerAccountId())
        assertEquals(local, repository.getConfig())

        commitmentActive = false
        val remote = StrictAccessConfig(method = "timer", revision = 1)
        repository.applyRemote(remote, accountId = other)
        assertEquals(other, repository.ownerAccountId())
        assertEquals(remote, repository.getConfig())
        } finally {
            testScope.cancel()
            testFile.delete()
        }
    }
}
