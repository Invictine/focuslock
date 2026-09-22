package com.focuslock.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focuslock.app.data.repository.PermanentBlockPolicy
import com.focuslock.app.data.repository.PermanentBlocksRepository
import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.data.repository.StrictModeAutomationRepository
import com.focuslock.app.data.repository.StrictRecurringWindow
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
