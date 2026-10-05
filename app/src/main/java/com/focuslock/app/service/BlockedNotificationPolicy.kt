package com.focuslock.app.service

import android.content.Context
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.data.model.FrogState
import com.focuslock.app.data.repository.PermanentBlocksRepository
import com.focuslock.app.data.repository.PermanentBlockPolicy

/** Small seam for notification filtering; enforcement details live with app policy. */
object BlockedNotificationPolicy {
    suspend fun shouldCancel(context: Context, packageName: String, state: FrogState): Boolean {
        if (FrogAppPolicy.isSafetyEssential(context, packageName)) return false
        val app = FocusLockApplication.instance
        val settings = app.settingsRepository
        runCatching { app.permanentBlocksRepository.warm() }
        val permanent = runCatching {
            settings.isAppPermanent(packageName) || app.permanentBlocksRepository.isPermanentlyBlocked(packageName)
        }.getOrDefault(false)
        if (permanent) {
            val protected = PermanentBlocksRepository.isProtectedPackage(context, packageName)
            if (!PermanentBlockPolicy.shouldEnforce(permanent, protected)) return false
            return runCatching { app.homeLocationRepository.shouldEnforceNow(permanent = true) }.getOrDefault(false)
        }
        if (!runCatching { app.homeLocationRepository.shouldEnforceNow() }.getOrDefault(false)) return false
        if (runCatching { settings.isNukeActive() }.getOrDefault(false)) return true
        if (FrogAppPolicy.isBlocked(context, packageName, state)) return true
        if (state.locked) return false // Confirmed tools stay usable for the Frog.
        if (runCatching { app.appLimitsRepository.isLimitExceeded(packageName) }.getOrDefault(false)) return true
        if (GroupLimitPolicy.isExceeded(context, app, "app", packageName)) return true
        if (!runCatching { settings.isAppBlocked(packageName) }.getOrDefault(false)) return false
        val scheduled = runCatching { app.blockSchedulesRepository.isScheduleActiveNow() }.getOrDefault(false)
        val strict = runCatching { settings.isLockdownModeEnabled() || app.strictModeAutomationRepository.isActivationActiveNow() }.getOrDefault(false)
        if (scheduled || strict) return true
        return runCatching { app.creditBankRepository.getBalanceSeconds() <= 0L }.getOrDefault(false)
    }
}
