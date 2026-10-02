package com.focuslock.app.ui.blocker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.FrogPhase
import com.focuslock.app.service.InstalledAppsRepository
import com.focuslock.app.service.UsageStatsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Shared live data for every block reason; presentation never offers an unlock bypass. */
@Composable
internal fun BlockerPresentation(
    appName: String,
    blockedPackage: String,
    isWebsite: Boolean,
    blockReason: String?,
    onCloseApp: () -> Unit,
    onOpenFocusLock: () -> Unit,
    onFrogComplete: () -> Unit,
    onStaleFrogDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = FocusLockApplication.instance
    val settings = app.settingsRepository
    val bank = app.creditBankRepository
    val lockdown by settings.lockdownModeFlow.collectAsStateWithLifecycle(initialValue = false)
    val automationStrict by produceState(false, blockReason) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                try { app.strictModeAutomationRepository.isActivationActiveNow() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            }
            delay(15_000)
        }
    }
    val strictActive = lockdown || automationStrict || blockReason == "strict"
    val frog by app.frogRepository.frogStateFlow.collectAsStateWithLifecycle(initialValue = null)
    val attempts by remember(blockedPackage) {
        app.blockLogRepository.eventsFlow.map { blockAttemptCounts(it, blockedPackage) }
    }.collectAsStateWithLifecycle(initialValue = null)
    val focused by bank.focusMinutesTodayFlow.collectAsStateWithLifecycle(initialValue = null)
    val balance by produceState<Long?>(null, bank) {
        value = bank.getBalanceSeconds()
        bank.liveBalanceSeconds.collect { value = it }
    }
    val boundaries by settings.blockedAppsFlow.collectAsStateWithLifecycle(initialValue = null)
    val leisure by produceState<Long?>(null, boundaries) {
        val apps = boundaries ?: return@produceState
        value = UsageStatsRepository.getTodayBoundaryForegroundMillis(context, apps)?.div(1_000L)
    }
    val appIcon by produceState<ImageBitmap?>(null, blockedPackage, isWebsite) {
        if (!isWebsite) {
            value = withContext(Dispatchers.IO) {
                InstalledAppsRepository.getAppIconBitmap(context, blockedPackage)
            }
        }
    }

    // Keep the existing frog completion/rollover dismissal behavior on its own block reason.
    LaunchedEffect(blockReason, frog?.phase, frog?.locked) {
        if (blockReason == "frog") {
            when {
                frog?.phase == FrogPhase.COMPLETE -> onFrogComplete()
                frog?.locked == false -> onStaleFrogDismiss()
            }
        }
    }
    BlockedAppScreen(
        appName = appName,
        isWebsite = isWebsite,
        strictActive = strictActive,
        isPermanentBlock = blockReason == "permanent",
        attempts = attempts,
        focusMinutes = focused,
        leisureSeconds = leisure,
        unlockSummary = blockUnlockSummary(blockReason, strictActive, frog, balance),
        frogPending = frog?.let { it.enabled && it.armed && !it.tickedOff } == true,
        appIcon = appIcon,
        onCloseApp = onCloseApp,
        onOpenFocusLock = onOpenFocusLock,
        attemptLabel = if (isWebsite) "Browser block attempts" else "Blocked attempts",
    )
}
