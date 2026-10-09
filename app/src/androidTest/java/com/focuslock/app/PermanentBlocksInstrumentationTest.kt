package com.focuslock.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.repository.PermanentBlockPolicy
import com.focuslock.app.data.repository.PermanentBlocksRepository
import com.focuslock.app.data.repository.PermanentWebsitePolicy
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.data.repository.StrictModeAutomationRepository
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.model.BlockedWebsite
import com.focuslock.app.data.repository.StrictRecurringWindow
import java.time.ZoneId
import java.time.ZonedDateTime
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side persistence and invariant checks. These intentionally use only the
 * instrumented application's private DataStore; no signed-in account or sync is used.
 * The parent agent owns actually selecting/running the emulator target.
 */
@RunWith(AndroidJUnit4::class)
class PermanentBlocksInstrumentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val jobs = mutableListOf<Job>()

    @After
    fun stopIsolatedStores() {
        jobs.forEach(Job::cancel)
        jobs.clear()
    }

    @Test
    fun permanentBlockSurvivesRepositoryRecreationAndWarmColdStart() = runBlocking {
        val packageName = "com.focuslock.test.${UUID.randomUUID()}"
        val writer = PermanentBlocksRepository(context)
        assertTrue(writer.add(packageName))

        val recreated = PermanentBlocksRepository(context)
        assertFalse("A cold mirror must not claim a package before warm-up", recreated.isPermanentlyBlocked(packageName))
        recreated.warm()
        assertTrue(recreated.isPermanentlyBlocked(packageName))
        assertTrue(PermanentBlockPolicy.shouldEnforce(packageName, setOf(packageName), emptySet()))
    }

    @Test
    fun permanentAppRetainsItsDisplayNameAndMigratesLegacyCommitments() = runBlocking {
        val packageName = "com.focuslock.test.${UUID.randomUUID()}"
        val migratedPackage = "com.focuslock.legacy.${UUID.randomUUID()}"
        val website = "legacy-${UUID.randomUUID().toString().replace("-", "")}.example"
        val writer = PermanentBlocksRepository(context)
        assertTrue(writer.add(packageName, "Saved app label"))
        val settings = SettingsRepository(context)
        settings.setAppPermanent(packageName, true)
        writer.migrateLegacy(
            settings.getBlockedApps() + BlockedApp(migratedPackage, "Legacy label", isPermanent = true),
            settings.getBlockedWebsites() + BlockedWebsite(website, website, isPermanent = true),
        )

        val recreated = PermanentBlocksRepository(context)
        recreated.warm()
        assertTrue(recreated.isPermanentlyBlocked(packageName))
        assertTrue(recreated.isPermanentlyBlocked(migratedPackage))
        assertTrue(recreated.isPermanentlyBlockedDomain("sub.$website"))
        assertTrue(recreated.appNamesFlow.first()[packageName] == "Saved app label")
        assertTrue(recreated.appNamesFlow.first()[migratedPackage] == "Legacy label")
    }

    @Test
    fun permanentWebsiteNormalizationValidatesAndMatchesSubdomains() = runBlocking {
        val repository = PermanentBlocksRepository(context)
        val domain = "site-${UUID.randomUUID().toString().replace("-", "")}.example"
        assertTrue(PermanentWebsitePolicy.normalize("HTTPS://www.$domain/path") == domain)
        assertTrue(PermanentWebsitePolicy.normalize("not a domain") == null)
        assertTrue(repository.addWebsite("https://$domain/login"))
        repository.warm()
        assertTrue(repository.isPermanentlyBlockedDomain("child.$domain"))
        assertFalse(repository.isPermanentlyBlockedDomain("$domain.evil.example"))
        assertFalse(repository.addWebsite("invalid host"))
    }

    @Test
    fun protectedRecoveryPackagesCannotBeAddedOrEnforced() = runBlocking {
        val repository = PermanentBlocksRepository(context)
        assertFalse(repository.add(context.packageName))
        assertTrue(PermanentBlocksRepository.isProtectedPackage(context, context.packageName))
        assertTrue(PermanentBlocksRepository.isProtectedPackage(context, "com.android.settings"))
        assertFalse(
            PermanentBlockPolicy.shouldEnforce(
                context.packageName,
                setOf(context.packageName),
                setOf(context.packageName)
            )
        )
    }

    @Test
    fun strictApprovalRequiresExactCommitmentAndNonStaleApprovalTimestamp() = runBlocking {
        val settings = SettingsRepository(context)
        val now = System.currentTimeMillis()
        val endsAt = now + 60 * 60 * 1000L
        settings.setLockdownMode(enabled = true, endsAt = endsAt)

        assertFalse(settings.applyRemoteApprovedUnlock(expectedEndsAt = endsAt + 1, approvedAt = now + 1))
        assertFalse(settings.applyRemoteApprovedUnlock(expectedEndsAt = endsAt, approvedAt = now - 1))
        assertTrue(settings.applyRemoteApprovedUnlock(expectedEndsAt = endsAt, approvedAt = System.currentTimeMillis()))
    }

    @Test
    fun strictModeAllowsOnlyAdditiveBoundaryAndPermanentBlocks() = runBlocking {
        val settings = isolatedSettings()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val existingApp = "com.focuslock.strict.existing$suffix"
        val allowedApp = "com.focuslock.strict.allowed$suffix"
        val addedApp = "com.focuslock.strict.added$suffix"
        val existingSite = "existing-$suffix.example"
        val allowedSite = "allowed-$suffix.example"
        val addedSite = "added-$suffix.example"
        val customSite = "custom-$suffix.example"

        settings.setAppBlockedFull(existingApp, "Existing", "Other", true)
        settings.setAppBlockedFull(allowedApp, "Allowed label", "Original category", false)
        settings.setWebsiteBlocked(existingSite, true)
        settings.setWebsiteBlocked(allowedSite, false)
        settings.setLockdownMode(true, System.currentTimeMillis() + 60 * 60 * 1000L)

        settings.setAppBlockedFull(addedApp, "Added", "Other", true)
        settings.setAppBlockedFull(allowedApp, "Changed label", "Changed category", true)
        settings.setAppBlockedFull(existingApp, "Existing", "Other", false)
        settings.setWebsiteBlocked(addedSite, true)
        settings.setWebsiteBlocked(allowedSite, true)
        settings.setWebsiteBlocked(existingSite, false)
        assertTrue(settings.addCustomWebsite(customSite))

        settings.setAppPermanent(addedApp, true)
        settings.setAppPermanent(addedApp, false)
        settings.setWebsitePermanent(addedSite, true)
        settings.setWebsitePermanent(addedSite, false)

        assertTrue(settings.isAppBlocked(existingApp))
        assertTrue(settings.isAppBlocked(addedApp))
        assertTrue(settings.isAppBlocked(allowedApp))
        val allowedEntry = settings.getBlockedApps().first { it.packageName == allowedApp }
        assertTrue(allowedEntry.appName == "Allowed label")
        assertTrue(allowedEntry.category == "Original category")
        assertTrue(settings.isWebsiteBlocked(existingSite))
        assertTrue(settings.isWebsiteBlocked(addedSite))
        assertTrue(settings.isWebsiteBlocked(allowedSite))
        assertTrue(settings.isWebsiteBlocked(customSite))
        val permanent = PermanentBlocksRepository(context).also { it.warm() }
        assertTrue(permanent.isPermanentlyBlocked(addedApp))
        assertTrue(permanent.isPermanentlyBlockedDomain(addedSite))
    }

    @Test
    fun scheduledStrictModeAllowsBoundaryAdditionsButRejectsUnblocking() = runBlocking {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val existingApp = "com.focuslock.scheduled.existing$suffix"
        val addedApp = "com.focuslock.scheduled.added$suffix"
        val addedSite = "scheduled-$suffix.example"
        val (store, _) = isolatedDataStore()
        val settings = SettingsRepository(context, strictAutomationActive = { true }, settingsStore = store)
        settings.setAppBlockedFull(existingApp, "Existing", "Other", true)

        settings.setAppBlockedFull(addedApp, "Added", "Other", true)
        settings.setAppBlockedFull(existingApp, "Existing", "Other", false)
        assertTrue(settings.addCustomWebsite(addedSite))
        settings.setAppPermanent(addedApp, true)
        settings.setWebsitePermanent(addedSite, true)

        assertTrue(settings.isAppBlocked(existingApp))
        assertTrue(settings.isAppBlocked(addedApp))
        assertTrue(settings.isWebsiteBlocked(addedSite))
        val permanent = PermanentBlocksRepository(context).also { it.warm() }
        assertTrue(permanent.isPermanentlyBlocked(addedApp))
        assertTrue(permanent.isPermanentlyBlockedDomain(addedSite))
    }

    private fun isolatedSettings(): SettingsRepository {
        val (store, _) = isolatedDataStore()
        return SettingsRepository(context, settingsStore = store)
    }

    private fun isolatedDataStore(): Pair<DataStore<Preferences>, Job> {
        val root = File(context.cacheDir, "strict-additions-${UUID.randomUUID()}")
        check(root.mkdirs() || root.isDirectory)
        val job = SupervisorJob()
        jobs += job
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(root, "datastore/settings.preferences_pb").apply { parentFile?.mkdirs() } },
        )
        return store to job
    }

    @Test
    fun strictRecurringWindowsRejectWrongDayAndHandleOvernightBoundary() {
        val monday = ZonedDateTime.of(2026, 3, 2, 10, 30, 0, 0, ZoneId.of("UTC"))
        val window = StrictRecurringWindow(
            id = "test",
            label = "Study",
            daysOfWeek = setOf(1),
            startMinuteOfDay = 10 * 60,
            endMinuteOfDay = 11 * 60,
        )
        assertTrue(StrictModeAutomationRepository.activeWindowAt(listOf(window), monday))
        assertFalse(StrictModeAutomationRepository.activeWindowAt(listOf(window), monday.plusDays(1)))

        val overnight = window.copy(startMinuteOfDay = 23 * 60, endMinuteOfDay = 60)
        assertTrue(StrictModeAutomationRepository.activeWindowAt(listOf(overnight), monday.plusDays(1).withHour(0)))
        assertFalse(StrictModeAutomationRepository.activeWindowAt(listOf(overnight), monday.plusDays(1).withHour(1)))
    }
}
