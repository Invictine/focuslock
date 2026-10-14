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
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StrictAccessRepositoryTest {
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
