package com.focuslock.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.repository.PermanentBlocksRepository
import com.focuslock.app.sync.RemotePermanentBlock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Local restore/ownership checks; cloud account restoration is covered by backend tests. */
@RunWith(AndroidJUnit4::class)
class PermanentAccountRestoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun restoredCommitmentsEnforceAfterRecreationAndNeverUploadToAnotherAccount() = runBlocking {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val alice = "restore-alice-$suffix"
        val bob = "restore-bob-$suffix"
        val appKey = "com.focuslock.restore.$suffix"
        val siteKey = "restore-$suffix.example"
        val targets = listOf(
            RemotePermanentBlock("android", appKey, "Restored app"),
            RemotePermanentBlock("website", siteKey, "Restored website"),
        )
        val writer = PermanentBlocksRepository(context)
        writer.mergeRemote(targets, alice)
        val recreated = PermanentBlocksRepository(context)
        recreated.warm()
        assertTrue(recreated.isPermanentlyBlocked(appKey))
        assertTrue(recreated.isPermanentlyBlockedDomain("https://child.$siteKey/watch"))
        assertTrue(recreated.remoteTargets(alice).any { it.targetKey == appKey })
        recreated.bindAccount(bob)
        assertFalse(recreated.remoteTargets(bob).any { it.targetKey in setOf(appKey, siteKey) })
        // Switching keeps device enforcement; returning restores upload ownership.
        assertTrue(recreated.isPermanentlyBlocked(appKey))
        recreated.bindAccount(alice)
        assertTrue(recreated.remoteTargets(alice).any { it.targetKey == siteKey })
    }

    @Test
    fun offlineAddIsOwnedByTheAccountActiveAtTheTimeOfTheEdit() = runBlocking {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val alice = "offline-alice-$suffix"
        val bob = "offline-bob-$suffix"
        val site = "offline-$suffix.example"
        val store = PermanentBlocksRepository(context)
        store.bindAccount(alice)
        assertTrue(store.addWebsite(site))
        store.bindAccount(bob)
        assertFalse(store.remoteTargets(bob).any { it.targetKey == site })
        assertTrue(store.remoteTargets(alice).any { it.targetKey == site })
    }
}
