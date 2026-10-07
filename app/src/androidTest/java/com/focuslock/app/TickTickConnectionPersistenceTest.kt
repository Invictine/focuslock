package com.focuslock.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.model.TickTickConnection
import com.focuslock.app.data.repository.SettingsRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.sync.ConvexSyncClient
import com.focuslock.app.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/** Device-side account migration and concurrency checks using private isolated DataStores. */
@RunWith(AndroidJUnit4::class)
class TickTickConnectionPersistenceTest {
    /** Reads the real signed-in link; all simulated signouts use an isolated store. */
    @Test
    fun liveAccountRestoresIntoAFreshLocalStore(): Unit = runBlocking {
        assumeTrue("Opt in with liveTickTickConnection=true",
            InstrumentationRegistry.getArguments().getString("liveTickTickConnection") == "true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val auth = AuthViewModel()
            val accountId = withTimeoutOrNull(30_000L) {
                var account = auth.getAccountId()
                while (account.isNullOrBlank()) {
                    delay(500L)
                    account = auth.getAccountId()
                }
                account
            }
            assumeTrue("Sign into FocusLock before the live restore check", !accountId.isNullOrBlank())
            val app = appContext.applicationContext as FocusLockApplication
            app.syncManager.syncNow(auth)
            val local = app.settingsRepository.tickTickConnectionState()
            assumeTrue("Connect TickTick before the live restore check", local.connection != null)
            val client = ConvexSyncClient(BuildConfig.CONVEX_URL, { auth.getConvexToken() })
            val remote = client.getTickTickConnection()
            assertTrue("The signed-in account must have a durable TickTick link", remote?.second != null)
            assertTrue("Cloud and local credentials must match", remote!!.second == local.connection)
            assertTrue("A stored account link must have a server revision", remote.first > 0L)
            val fresh = newSettings()
            fresh.bindTickTickAccount(accountId!!)
            assertTrue(fresh.applyTickTickRemote(fresh.tickTickConnectionState(), remote.first, remote.second))
            fresh.bindTickTickAccount("")
            fresh.bindTickTickAccount("isolated-other-account")
            assertTrue("Another account must have no connection", fresh.tickTickConnectionState().connection == null)
            fresh.bindTickTickAccount(accountId)
            assertTrue("Signing back in restores the exact saved link",
                fresh.tickTickConnectionState().connection == remote.second)
            android.util.Log.i("TickTickConnectionTest", "Live account link restored into isolated fresh store; revision=${remote.first}")
        }
    }
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val stores = mutableListOf<IsolatedSettings>()

    @After
    fun cleanIsolatedStores(): Unit = runBlocking {
        stores.forEach { fixture ->
            fixture.job.cancelAndJoin()
            fixture.root.deleteRecursively()
        }
        stores.clear()
        Unit
    }

    @Test
    fun migratesAnUnownedLegacyTokenToTheFirstBoundAccount() = runBlocking {
        val settings = newSettings()
        settings.setTickTickAuthSuccess(
            token = "legacy-access",
            userName = "Legacy User",
            refreshToken = "legacy-refresh",
            expiresInSec = 3_600L,
        )

        val beforeMigration = settings.tickTickConnectionState()
        assertEquals("", beforeMigration.accountId)
        assertEquals("legacy-access", beforeMigration.connection?.accessToken)
        settings.bindTickTickAccount("account-a")

        val migrated = settings.tickTickConnectionState()
        assertEquals("account-a", migrated.accountId)
        assertEquals("legacy-access", migrated.connection?.accessToken)
        assertEquals("legacy-refresh", migrated.connection?.refreshToken)
        assertEquals("Legacy User", migrated.connection?.userName)
        assertTrue(migrated.connection!!.expiresAt > System.currentTimeMillis())
        assertEquals(0L, migrated.revision)
        assertTrue("the migrated legacy token must upload", migrated.dirty)
    }

    @Test
    fun accountSwitchHidesCredentialsAndReturningAccountRestoresItsAcknowledgedConnection() = runBlocking {
        val settings = newSettings()
        settings.bindTickTickAccount("account-a")
        val emptyA = settings.tickTickConnectionState()
        assertTrue(settings.setTickTickAuthSuccess(
            token = "a-access",
            userName = "Alice",
            refreshToken = "a-refresh",
            expiresInSec = 3_600L,
            expectedToken = emptyA.connection?.accessToken,
            expectedAccountId = "account-a",
        ))
        val connectedA = settings.tickTickConnectionState()
        assertTrue(connectedA.dirty)
        assertEquals("a-refresh", connectedA.connection?.refreshToken)
        assertTrue(settings.acknowledgeTickTickUpload(connectedA, revision = 7L))

        settings.bindTickTickAccount("")
        val signedOut = settings.tickTickConnectionState()
        assertEquals("", signedOut.accountId)
        assertEquals(null, signedOut.connection)
        settings.bindTickTickAccount("account-b")
        val accountB = settings.tickTickConnectionState()
        assertEquals("account-b", accountB.accountId)
        assertEquals(null, accountB.connection)
        assertFalse(accountB.dirty)

        settings.bindTickTickAccount("account-a")
        val restored = settings.tickTickConnectionState()
        assertEquals("account-a", restored.accountId)
        assertEquals("a-access", restored.connection?.accessToken)
        assertEquals("a-refresh", restored.connection?.refreshToken)
        assertEquals("Alice", restored.connection?.userName)
        assertTrue(restored.connection!!.expiresAt > System.currentTimeMillis())
        assertEquals(7L, restored.revision)
        assertFalse(restored.dirty)
    }

    @Test
    fun freshDeviceCanInstallACloudConnectionIntoItsIsolatedStore() = runBlocking {
        val phone = newSettings()
        phone.bindTickTickAccount("account-a")
        assertTrue(phone.setTickTickAuthSuccess(
            token = "cloud-access",
            refreshToken = "cloud-refresh",
            userName = "Cloud User",
            expiresInSec = 90L,
            expectedAccountId = "account-a",
        ))
        val uploaded = phone.tickTickConnectionState()
        val cloudConnection = uploaded.connection!!
        assertTrue(phone.acknowledgeTickTickUpload(uploaded, revision = 12L))

        val freshDevice = newSettings()
        freshDevice.bindTickTickAccount("account-a")
        val beforeRemote = freshDevice.tickTickConnectionState()
        assertTrue(freshDevice.applyTickTickRemote(beforeRemote, revision = 12L, connection = cloudConnection))

        val restored = freshDevice.tickTickConnectionState()
        assertEquals("cloud-access", restored.connection?.accessToken)
        assertEquals("cloud-refresh", restored.connection?.refreshToken)
        assertEquals("Cloud User", restored.connection?.userName)
        assertEquals(cloudConnection.expiresAt, restored.connection?.expiresAt)
        assertEquals(12L, restored.revision)
        assertFalse(restored.dirty)
    }

    @Test
    fun disconnectClearsTheConnectionAndMarksTheLocalStateDirty() = runBlocking {
        val settings = newSettings()
        settings.bindTickTickAccount("account-a")
        assertTrue(settings.setTickTickAuthSuccess(
            token = "access-to-disconnect",
            userName = "Alice",
            refreshToken = "refresh-to-disconnect",
            expiresInSec = 3_600L,
            expectedAccountId = "account-a",
        ))
        val connected = settings.tickTickConnectionState()
        assertTrue(settings.acknowledgeTickTickUpload(connected, revision = 4L))

        settings.clearTickTickAuth()
        val disconnected = settings.tickTickConnectionState()
        assertEquals("account-a", disconnected.accountId)
        assertEquals(null, disconnected.connection)
        assertEquals(4L, disconnected.revision)
        assertTrue(disconnected.dirty)
        assertTrue(disconnected.generation > connected.generation)
    }

    @Test
    fun staleRemoteSnapshotCannotOverwriteANewerLocalEdit() = runBlocking {
        val settings = newSettings()
        settings.bindTickTickAccount("account-a")
        val requestSnapshot = settings.tickTickConnectionState()
        assertTrue(settings.setTickTickAuthSuccess(
            token = "new-local-access",
            userName = "Alice",
            refreshToken = "new-local-refresh",
            expiresInSec = 3_600L,
            expectedAccountId = "account-a",
        ))

        val applied = settings.applyTickTickRemote(
            expected = requestSnapshot,
            revision = 5L,
            connection = TickTickConnection("stale-cloud-access"),
        )
        assertFalse(applied)
        val current = settings.tickTickConnectionState()
        assertEquals("new-local-access", current.connection?.accessToken)
        assertEquals("new-local-refresh", current.connection?.refreshToken)
        assertTrue(current.dirty)
    }

    @Test
    fun lateAccountAAuthAndRemoteResponsesCannotChangeAccountB() = runBlocking {
        val settings = newSettings()
        settings.bindTickTickAccount("account-a")
        val accountARequest = settings.tickTickConnectionState()
        settings.bindTickTickAccount("account-b")
        val accountB = settings.tickTickConnectionState()

        assertFalse(settings.setTickTickAuthSuccess(
            token = "late-a-access",
            userName = "Alice",
            refreshToken = "late-a-refresh",
            expiresInSec = 3_600L,
            expectedToken = accountARequest.connection?.accessToken,
            expectedAccountId = "account-a",
        ))
        assertFalse(settings.applyTickTickRemote(
            expected = accountARequest,
            revision = 9L,
            connection = TickTickConnection("late-a-cloud-access"),
        ))

        val afterLateResponses = settings.tickTickConnectionState()
        assertEquals("account-b", afterLateResponses.accountId)
        assertEquals(accountB.generation, afterLateResponses.generation)
        assertEquals(null, afterLateResponses.connection)
        assertFalse(afterLateResponses.dirty)
    }

    @Test
    fun uploadAcknowledgementAdvancesRevisionWithoutClearingAConcurrentLocalEdit() = runBlocking {
        val settings = newSettings()
        settings.bindTickTickAccount("account-a")
        val initial = settings.tickTickConnectionState()
        assertTrue(settings.setTickTickAuthSuccess(
            token = "uploaded-access",
            userName = "Alice",
            refreshToken = "uploaded-refresh",
            expiresInSec = 3_600L,
            expectedAccountId = "account-a",
        ))
        val uploaded = settings.tickTickConnectionState()

        assertTrue(settings.setTickTickAuthSuccess(
            token = "newer-local-access",
            userName = "Alice",
            refreshToken = "newer-local-refresh",
            expiresInSec = 7_200L,
            expectedToken = "uploaded-access",
            expectedAccountId = "account-a",
        ))
        val newerLocal = settings.tickTickConnectionState()
        assertTrue(newerLocal.generation > uploaded.generation)
        assertTrue(newerLocal.dirty)

        assertTrue(settings.acknowledgeTickTickUpload(uploaded, revision = 3L))
        val afterAck = settings.tickTickConnectionState()
        assertEquals("newer-local-access", afterAck.connection?.accessToken)
        assertEquals("newer-local-refresh", afterAck.connection?.refreshToken)
        assertEquals(3L, afterAck.revision)
        assertTrue("the concurrent edit still needs upload", afterAck.dirty)
        assertTrue(afterAck.generation > newerLocal.generation)
        assertEquals("account-a", initial.accountId)
    }

    private fun newSettings(): SettingsRepository = isolatedSettings().settings

    private fun isolatedSettings(): IsolatedSettings {
        val root = File(appContext.cacheDir, "ticktick-connection-${UUID.randomUUID()}")
        check(root.mkdirs() || root.isDirectory)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(root, "datastore/focuslock_settings.preferences_pb").apply {
                parentFile?.mkdirs()
            } },
        )
        return IsolatedSettings(root, job, SettingsRepository(appContext, settingsStore = dataStore))
            .also(stores::add)
    }

    private data class IsolatedSettings(
        val root: File,
        val job: Job,
        val settings: SettingsRepository,
    )
}
