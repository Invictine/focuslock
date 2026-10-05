package com.focuslock.app.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.focuslock.app.FocusLockApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Navigation recovery never grants access to the redirected destination. */
object AppRedirectRecovery {
    const val EXTRA_RETURN_PACKAGE = "extra_return_package"

    private data class RequestedLaunch(val packageName: String, val atMs: Long)
    @Volatile private var requestedLaunch: RequestedLaunch? = null

    /** Remember an explicit tool launch even if its restored top activity belongs to another app. */
    fun noteLaunch(packageName: String) {
        requestedLaunch = RequestedLaunch(packageName, SystemClock.elapsedRealtime())
    }

    fun cancelLaunch(packageName: String) {
        if (requestedLaunch?.packageName == packageName) requestedLaunch = null
    }

    fun candidate(previousPackage: String?, targetPackage: String, nowMs: Long, ownPackage: String): AppRedirectCandidate? {
        val launch = requestedLaunch
        requestedLaunch = null
        val requested = launch?.takeIf {
            nowMs - it.atMs in 0..AppRedirectPolicy.MAX_AGE_MS && it.packageName != targetPackage
        }
        val previousApp = previousPackage?.takeUnless {
            it == targetPackage || it == ownPackage || it == "android" || it == "com.android.systemui"
        }
        val source = previousApp ?: requested?.packageName ?: return null
        if (source == targetPackage) return null
        return AppRedirectCandidate(source, targetPackage, nowMs)
    }

    /** A return shortcut is available only to a currently permitted, installed Frog tool. */
    suspend fun canReturnTo(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (packageName.isBlank() || packageName == context.packageName ||
                packageName == "android" || FrogAppPolicy.isHome(context, packageName) ||
                context.packageManager.getLaunchIntentForPackage(packageName) == null
            ) return@withContext false
            val app = FocusLockApplication.instance
            val settings = app.settingsRepository
            if (settings.isNukeActive()) return@withContext false
            app.permanentBlocksRepository.warm()
            if (settings.isAppPermanent(packageName) ||
                app.permanentBlocksRepository.isPermanentlyBlocked(packageName)
            ) return@withContext false
            val state = app.frogRepository.currentState()
            state.locked && !FrogAppPolicy.isBlocked(context, packageName, state)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    /** Explicit user recovery removes a stale redirected activity above the app's entry screen. */
    fun launchClean(context: Context, packageName: String): Boolean =
        launch(context, packageName, Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** A separate destination task can Back to Home; resume the source without clearing its stack. */
    fun launchResume(context: Context, packageName: String): Boolean =
        launch(context, packageName, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    private fun launch(context: Context, packageName: String, navigationFlag: Int): Boolean = try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) false else {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or navigationFlag or Intent.FLAG_ACTIVITY_SINGLE_TOP
            noteLaunch(packageName)
            context.startActivity(intent)
            true
        }
    } catch (_: Exception) {
        cancelLaunch(packageName)
        false
    }
}
